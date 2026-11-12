#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { appendFileSync, existsSync, lstatSync, readFileSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const findingsExitCode = 10;

class SecretScanError extends Error {
  constructor(message, exitCode = 1) {
    super(message);
    this.exitCode = exitCode;
  }
}

async function executeScanner(scanner, args, cwd, timeoutMilliseconds) {
  const child = spawn(scanner, args, {
    cwd, detached: process.platform !== 'win32', stdio: 'inherit', shell: false,
  });
  let timedOut = false;
  let killTimer;
  const signal = value => {
    if (child.pid === undefined) return;
    try {
      if (process.platform === 'win32') child.kill(value);
      else process.kill(-child.pid, value);
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  };
  const timer = setTimeout(() => {
    timedOut = true;
    signal('SIGTERM');
    killTimer = setTimeout(() => signal('SIGKILL'), 1000);
  }, timeoutMilliseconds);
  let result;
  try {
    result = await new Promise((resolveExit, rejectExit) => {
      child.once('error', () => rejectExit(new SecretScanError('Gitleaks could not be started')));
      child.once('close', (code, exitSignal) => resolveExit({ code, signal: exitSignal }));
    });
  } finally {
    clearTimeout(timer);
    clearTimeout(killTimer);
    // A terminated scanner must not leave Git descendants running after a timeout.
    if (timedOut) signal('SIGKILL');
  }
  if (timedOut) throw new SecretScanError('Gitleaks scan timed out', 124);
  if (result.signal !== null)
    throw new SecretScanError(`Gitleaks was terminated by ${result.signal}`);
  if (result.code !== 0 && result.code !== findingsExitCode)
    throw new SecretScanError(`Gitleaks failed with exit code ${result.code}`, result.code || 1);
  return result.code;
}

export async function reportSecretScan({
  repository,
  reportPath,
  scanner = process.env.SOKLET_GITLEAKS_BIN || 'gitleaks',
  summaryPath = process.env.GITHUB_STEP_SUMMARY,
  timeoutMilliseconds = 10 * 60 * 1000,
}) {
  const root = realpathSync(resolve(repository));
  if (!lstatSync(root).isDirectory()) throw new SecretScanError('The scan source must be a directory');
  const report = resolve(reportPath);
  if (existsSync(report)) throw new SecretScanError('The Gitleaks report path must be new');
  if (!Number.isSafeInteger(timeoutMilliseconds) || timeoutMilliseconds < 1)
    throw new SecretScanError('The scanner timeout must be a positive number of milliseconds');

  const status = await executeScanner(scanner, [
    'git', '--log-opts=HEAD', '--redact=100', '--exit-code', String(findingsExitCode),
    '--report-format', 'json', '--report-path', report, root,
  ], root, timeoutMilliseconds);

  if (!existsSync(report) || !lstatSync(report).isFile() || lstatSync(report).isSymbolicLink())
    throw new SecretScanError('Gitleaks did not produce a regular JSON report');
  let findings;
  try { findings = JSON.parse(readFileSync(report, 'utf8')); }
  catch { throw new SecretScanError('The Gitleaks report is not valid JSON'); }
  if (!Array.isArray(findings) || findings.some(value => value === null || typeof value !== 'object' || Array.isArray(value)))
    throw new SecretScanError('The Gitleaks report must contain an array of findings');
  if (status === findingsExitCode && findings.length === 0)
    throw new SecretScanError('Gitleaks reported findings but its report is empty');

  const count = findings.length;
  const message = count === 0 ? 'Gitleaks reported no findings.'
    : `Gitleaks reported ${count} potential secret finding(s). Review the complete redacted report artifact.`;
  if (count > 0) console.warn(`::warning title=Advisory secret scan::${message}`);
  else console.log(message);
  const summary = [
    '## Advisory secret scan', '', message, '',
    'Findings are reported without suppression. This report requires no exception or approval records.', '',
  ].join('\n');
  if (summaryPath) {
    try { appendFileSync(summaryPath, summary); }
    catch { console.warn('::warning title=Advisory secret scan::Could not write the step summary; the scanner result and redacted report remain available.'); }
  }
  return { findingCount: count, reportPath: report };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [repository, reportPath, ...extra] = process.argv.slice(2);
    if (!repository || !reportPath || extra.length)
      throw new SecretScanError('Usage: node scripts/report-secret-scan.mjs <repository-directory> <new-report-json>');
    await reportSecretScan({ repository, reportPath });
  } catch (error) {
    console.error(error instanceof SecretScanError ? error.message : 'The secret scan could not be completed');
    process.exitCode = error instanceof SecretScanError ? error.exitCode : 1;
  }
}
