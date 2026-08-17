#!/usr/bin/env node

import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAXIMUM_REPORT_BYTES = 16 * 1024 * 1024;
const COUNT_NAMES = ['tests', 'errors', 'skipped', 'failures'];

function fail(message) {
  throw new Error(message);
}

function parseCount(attributes, name, fileName) {
  const matches = [...attributes.matchAll(new RegExp(`(?:^|\\s)${name}="([0-9]+)"`, 'g'))];
  if (matches.length !== 1)
    fail(`${fileName} must declare exactly one ${name} count`);
  const value = Number(matches[0][1]);
  if (!Number.isSafeInteger(value))
    fail(`${fileName} ${name} count exceeds the safe integer range`);
  return value;
}

export function verifySurefireReports(directory, gateId = 'downstream', leg = 'candidate') {
  const absoluteDirectory = resolve(directory);
  let directoryStats;
  try {
    directoryStats = lstatSync(absoluteDirectory);
  } catch {
    fail(`${gateId} ${leg} Surefire report directory is missing`);
  }
  if (!directoryStats.isDirectory() || directoryStats.isSymbolicLink())
    fail(`${gateId} ${leg} Surefire reports must be a nonsymlink directory`);

  const reportEntries = readdirSync(absoluteDirectory, { withFileTypes: true })
    .filter(({ name }) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .sort((left, right) => left.name.localeCompare(right.name, 'en'));
  if (reportEntries.length === 0)
    fail(`${gateId} ${leg} produced no Surefire TEST-*.xml reports`);

  const totals = Object.fromEntries(COUNT_NAMES.map((name) => [name, 0]));
  for (const entry of reportEntries) {
    if (!entry.isFile() || entry.isSymbolicLink())
      fail(`${gateId} ${leg} Surefire report is not a regular file: ${entry.name}`);
    const reportPath = resolve(absoluteDirectory, entry.name);
    const stats = lstatSync(reportPath);
    if (!stats.isFile() || stats.isSymbolicLink()
        || stats.size <= 0 || stats.size > MAXIMUM_REPORT_BYTES) {
      fail(`${gateId} ${leg} Surefire report has an invalid type or size: ${entry.name}`);
    }
    const bytes = readFileSync(reportPath);
    const text = bytes.toString('utf8');
    if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
      fail(`${gateId} ${leg} Surefire report is not UTF-8: ${entry.name}`);
    const suites = [...text.matchAll(/<testsuite\b([^>]*)>/g)];
    if (suites.length !== 1 || !text.includes('</testsuite>'))
      fail(`${gateId} ${leg} Surefire report must contain one test suite: ${entry.name}`);
    for (const name of COUNT_NAMES) {
      const value = parseCount(suites[0][1], name, entry.name);
      totals[name] += value;
      if (!Number.isSafeInteger(totals[name]))
        fail(`${gateId} ${leg} aggregate ${name} count exceeds the safe integer range`);
    }
  }

  if (totals.tests === 0 || totals.tests <= totals.skipped)
    fail(`${gateId} ${leg} did not execute any tests`);
  if (totals.failures !== 0 || totals.errors !== 0)
    fail(`${gateId} ${leg} Surefire reports contain failures or errors`);
  if (totals.skipped > totals.tests)
    fail(`${gateId} ${leg} Surefire skipped count exceeds its test count`);

  return Object.freeze({
    errors: totals.errors,
    failures: totals.failures,
    files: reportEntries.length,
    skipped: totals.skipped,
    tests: totals.tests,
  });
}

function main(args) {
  if (args.length !== 3) {
    console.error('Usage: node scripts/verify-surefire-reports.mjs <directory> <gate-id> <leg>');
    process.exitCode = 64;
    return;
  }
  const result = verifySurefireReports(args[0], args[1], args[2]);
  console.log(
    `Verified ${args[1]} ${args[2]} Surefire reports: `
      + `${result.tests} tests, ${result.skipped} skipped, ${result.files} files.`,
  );
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
