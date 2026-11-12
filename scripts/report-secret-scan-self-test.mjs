#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { reportSecretScan } from './report-secret-scan.mjs';

const temporary = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-secret-report-')));
let checks = 0;
try {
  const scanner = join(temporary, 'fake-gitleaks.mjs');
  writeFileSync(scanner, `#!/usr/bin/env node
import { writeFileSync } from 'node:fs';
const args = process.argv.slice(2);
writeFileSync(process.env.SOKLET_TEST_SCAN_ARGS, JSON.stringify(args));
const output = args[args.indexOf('--report-path') + 1];
const mode = process.env.SOKLET_TEST_SCAN_MODE;
if (mode === 'timeout') setInterval(() => {}, 1000);
else {
  if (mode !== 'missing') writeFileSync(output,
    mode === 'malformed' ? '{invalid' : mode === 'object' ? '{}' :
    JSON.stringify(mode === 'findings' ? [{ RuleID: 'fixture-a', Secret: 'REDACTED' }, { RuleID: 'fixture-b', Secret: 'REDACTED' }] : []));
  process.exit(mode === 'error' ? 7 : mode === 'findings' || mode === 'empty-findings' ? 10 : 0);
}
`);
  chmodSync(scanner, 0o755);
  const helper = join(dirname(fileURLToPath(import.meta.url)), 'report-secret-scan.mjs');
  function probe(mode, extraEnv = {}) {
    const root = join(temporary, `${mode}-${checks++}`);
    mkdirSync(root);
    const report = join(root, 'gitleaks.json');
    const summary = join(root, 'summary.md');
    const argsPath = join(root, 'args.json');
    const result = spawnSync(process.execPath, [helper, root, report], {
      encoding: 'utf8', timeout: 10_000,
      env: { ...process.env, SOKLET_GITLEAKS_BIN: scanner, GITHUB_STEP_SUMMARY: summary,
        SOKLET_TEST_SCAN_ARGS: argsPath, SOKLET_TEST_SCAN_MODE: mode, ...extraEnv },
    });
    assert.ifError(result.error);
    return { root, report, summary, argsPath, result };
  }

  const clean = probe('clean');
  assert.equal(clean.result.status, 0, clean.result.stderr);
  assert.match(clean.result.stdout, /no findings/u);
  assert.match(readFileSync(clean.summary, 'utf8'), /Advisory secret scan/u);
  const args = JSON.parse(readFileSync(clean.argsPath, 'utf8'));
  assert.deepEqual(args, ['git', '--log-opts=HEAD', '--redact=100', '--exit-code', '10', '--report-format', 'json', '--report-path', clean.report, clean.root]);
  assert.equal(existsSync(join(clean.root, '.git')), false); // No Git fixtures or commits are needed.

  const findings = probe('findings');
  assert.equal(findings.result.status, 0, findings.result.stderr);
  assert.match(findings.result.stderr, /::warning.*2 potential secret finding/u);
  assert.match(readFileSync(findings.summary, 'utf8'), /2 potential secret finding/u);
  assert.equal(JSON.parse(readFileSync(findings.report, 'utf8')).length, 2);
  assert.doesNotMatch(findings.result.stdout + findings.result.stderr, /fixture-a|fixture-b|REDACTED/u);

  const failure = probe('error');
  assert.equal(failure.result.status, 7);
  assert.match(failure.result.stderr, /Gitleaks failed with exit code 7/u);
  assert.equal(existsSync(failure.summary), false);
  for (const [mode, message] of [
    ['missing', /did not produce/u], ['malformed', /not valid JSON/u],
    ['object', /array of findings/u], ['empty-findings', /report is empty/u],
  ]) {
    const broken = probe(mode);
    assert.equal(broken.result.status, 1, mode);
    assert.match(broken.result.stderr, message);
    assert.equal(existsSync(broken.summary), false);
  }

  const missingTool = probe('no-tool', { SOKLET_GITLEAKS_BIN: join(temporary, 'missing-scanner') });
  assert.equal(missingTool.result.status, 1);
  assert.match(missingTool.result.stderr, /could not be started/u);

  const noSummary = probe('findings', { GITHUB_STEP_SUMMARY: join(temporary, 'missing-directory/summary.md') });
  assert.equal(noSummary.result.status, 0, noSummary.result.stderr);
  assert.match(noSummary.result.stderr, /Could not write the step summary/u);
  assert.equal(JSON.parse(readFileSync(noSummary.report, 'utf8')).length, 2);

  const existing = join(temporary, 'existing.json');
  writeFileSync(existing, '[]');
  await assert.rejects(reportSecretScan({ repository: temporary, reportPath: existing, scanner }), /must be new/u);
  checks++;

  const oldArgs = process.env.SOKLET_TEST_SCAN_ARGS;
  const oldMode = process.env.SOKLET_TEST_SCAN_MODE;
  try {
    process.env.SOKLET_TEST_SCAN_ARGS = join(temporary, 'timeout-args.json');
    process.env.SOKLET_TEST_SCAN_MODE = 'timeout';
    await assert.rejects(reportSecretScan({ repository: temporary, reportPath: join(temporary, 'timeout.json'), scanner,
      timeoutMilliseconds: 100 }), error => error.exitCode === 124 && /timed out/u.test(error.message));
    checks++;
  } finally {
    if (oldArgs === undefined) delete process.env.SOKLET_TEST_SCAN_ARGS;
    else process.env.SOKLET_TEST_SCAN_ARGS = oldArgs;
    if (oldMode === undefined) delete process.env.SOKLET_TEST_SCAN_MODE;
    else process.env.SOKLET_TEST_SCAN_MODE = oldMode;
  }
  console.log(`Advisory secret scan self-test passed (${checks} checks).`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
