#!/usr/bin/env node

import {
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, parse, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAXIMUM_REPORT_BYTES = 64 * 1024 * 1024;

export class CodeqlReleaseReportError extends Error {}

function fail(message) {
  throw new CodeqlReleaseReportError(message);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function canonicalValue(value) {
  if (Array.isArray(value))
    return value.map(canonicalValue);
  if (value !== null && typeof value === 'object') {
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null)
      fail('CodeQL SARIF contains a non-JSON object.');
    return Object.fromEntries(
      Object.keys(value)
        .sort(compareAscii)
        .map((key) => [key, canonicalValue(value[key])]),
    );
  }
  if (value === null || typeof value === 'string' || typeof value === 'boolean')
    return value;
  if (typeof value === 'number' && Number.isFinite(value) && !Object.is(value, -0))
    return value;
  fail('CodeQL SARIF contains an unsupported JSON value.');
}

function canonicalJson(value) {
  return `${JSON.stringify(canonicalValue(value), null, 2)}\n`;
}

function requireNonsymlinkComponents(path, label) {
  const absolute = resolve(path);
  const root = parse(absolute).root;
  let current = root;
  for (const component of absolute.slice(root.length).split(sep).filter(Boolean)) {
    current = resolve(current, component);
    if (existsSync(current) && lstatSync(current).isSymbolicLink())
      fail(`${label} contains a symbolic-link path component: ${current}`);
  }
}

function requireAbsolutePath(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  return resolve(path);
}

function validateSuccessfulInvocation(invocation, label) {
  if (invocation === null || typeof invocation !== 'object'
      || Array.isArray(invocation)) {
    fail(`${label} is not an invocation object.`);
  }
  if (invocation.executionSuccessful !== true)
    fail(`${label} does not prove successful scanner execution.`);
  if (invocation.exitCode !== undefined
      && (!Number.isSafeInteger(invocation.exitCode) || invocation.exitCode !== 0)) {
    fail(`${label} has a nonzero or malformed exit code.`);
  }
  if (invocation.processStartFailureMessage !== undefined)
    fail(`${label} records a scanner process-start failure.`);
  for (const field of [
    'toolExecutionNotifications',
    'toolConfigurationNotifications',
  ]) {
    if (invocation[field] === undefined)
      continue;
    if (!Array.isArray(invocation[field]))
      fail(`${label}.${field} must be an array.`);
    for (const [index, notification] of invocation[field].entries()) {
      if (notification === null || typeof notification !== 'object'
          || Array.isArray(notification)) {
        fail(`${label}.${field}[${index}] is malformed.`);
      }
      if (notification.level === 'error' || notification.exception !== undefined) {
        fail(`${label}.${field}[${index}] records an incomplete scanner execution.`);
      }
    }
  }
}

function requireSuccessfulInvocations(run, label) {
  if (!Array.isArray(run.invocations) || run.invocations.length === 0)
    fail(`${label} has no scanner invocation evidence.`);
  run.invocations.forEach((invocation, index) =>
    validateSuccessfulInvocation(invocation, `${label}.invocations[${index}]`));
}

function readSarif(inputRoot) {
  requireNonsymlinkComponents(inputRoot, 'CodeQL SARIF input directory');
  if (!existsSync(inputRoot))
    fail(`CodeQL SARIF input directory does not exist: ${inputRoot}`);
  const rootStats = lstatSync(inputRoot);
  if (!rootStats.isDirectory() || rootStats.isSymbolicLink()
      || realpathSync(inputRoot) !== inputRoot) {
    fail('CodeQL SARIF input must be a real nonsymlink directory.');
  }

  const reports = [];
  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name))) {
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink())
        fail(`CodeQL SARIF input contains a symbolic link: ${path}`);
      if (entry.isDirectory()) {
        visit(path);
      } else if (entry.isFile()) {
        if (!entry.name.endsWith('.sarif'))
          fail(`CodeQL SARIF input contains an unexpected file: ${path}`);
        reports.push(path);
      } else {
        fail(`CodeQL SARIF input contains a non-file entry: ${path}`);
      }
    }
  }
  visit(inputRoot);
  if (reports.length !== 1)
    fail(`CodeQL analysis must produce exactly one SARIF report; found ${reports.length}.`);

  const reportStats = lstatSync(reports[0]);
  if (reportStats.size <= 0 || reportStats.size > MAXIMUM_REPORT_BYTES)
    fail(`CodeQL SARIF report has invalid size ${reportStats.size}.`);
  const bytes = readFileSync(reports[0]);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail('CodeQL SARIF report is not valid UTF-8.');
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`CodeQL SARIF report is not valid JSON: ${error.message}`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || value.version !== '2.1.0' || !Array.isArray(value.runs)
      || value.runs.length === 0) {
    fail('CodeQL report must be SARIF 2.1.0 with at least one scanner run.');
  }
  value.runs.forEach((run, index) => {
    const driver = run?.tool?.driver;
    if (driver === null || typeof driver !== 'object' || Array.isArray(driver)
        || typeof driver.name !== 'string'
        || driver.name.toLowerCase() !== 'codeql') {
      fail(`CodeQL SARIF run ${index + 1} is not from the CodeQL scanner.`);
    }
    if (!Array.isArray(run.results))
      fail(`CodeQL SARIF run ${index + 1} has no results array.`);
    if (run.results.length !== 0)
      fail(`CodeQL SARIF run ${index + 1} contains an unaccepted finding.`);
    requireSuccessfulInvocations(run, `CodeQL SARIF run ${index + 1}`);
  });
  return value;
}

export function prepareCodeqlReleaseReport({ candidateCommit, inputRoot, outputPath }) {
  if (typeof candidateCommit !== 'string' || !/^[0-9a-f]{40}$/u.test(candidateCommit))
    fail('CodeQL candidate commit must be a full lowercase 40-hex object ID.');
  const absoluteInputRoot = requireAbsolutePath(inputRoot, 'CodeQL SARIF input');
  const absoluteOutputPath = requireAbsolutePath(outputPath, 'CodeQL release-report output');
  requireNonsymlinkComponents(absoluteOutputPath, 'CodeQL release-report output');
  if (existsSync(absoluteOutputPath))
    fail(`CodeQL release-report output already exists: ${absoluteOutputPath}`);
  const outputDirectory = dirname(absoluteOutputPath);
  requireNonsymlinkComponents(outputDirectory, 'CodeQL release-report output directory');
  mkdirSync(outputDirectory, { recursive: true });
  if (lstatSync(outputDirectory).isSymbolicLink()
      || realpathSync(outputDirectory) !== outputDirectory) {
    fail('CodeQL release-report output directory must be a real nonsymlink directory.');
  }
  const value = readSarif(absoluteInputRoot);
  value.runs.forEach((run, index) => {
    if (!Array.isArray(run.versionControlProvenance)
        || run.versionControlProvenance.length === 0) {
      fail(`CodeQL SARIF run ${index + 1} has no version-control provenance.`);
    }
    for (const [entryIndex, entry] of run.versionControlProvenance.entries()) {
      if (entry === null || typeof entry !== 'object' || Array.isArray(entry)
          || entry.revisionId !== candidateCommit
          || typeof entry.repositoryUri !== 'string' || entry.repositoryUri.length === 0) {
        fail(
          `CodeQL SARIF run ${index + 1} version-control provenance ${entryIndex + 1} `
            + 'does not bind the exact candidate commit/repository.',
        );
      }
    }
  });
  writeFileSync(absoluteOutputPath, canonicalJson(value), { encoding: 'utf8', flag: 'wx' });
  return { outputPath: absoluteOutputPath, runCount: value.runs.length };
}

function usage() {
  console.error(
    'Usage: node scripts/prepare-codeql-release-report.mjs '
      + '<absolute-sarif-directory> <absolute-output-file> <candidate-commit>',
  );
}

function main() {
  if (process.argv.length !== 5) {
    usage();
    process.exitCode = 64;
    return;
  }
  try {
    const result = prepareCodeqlReleaseReport({
      candidateCommit: process.argv[4],
      inputRoot: process.argv[2],
      outputPath: process.argv[3],
    });
    console.log(`CodeQL release report PASS runs=${result.runCount} output=${result.outputPath}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof CodeqlReleaseReportError ? 1 : 70;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href)
  main();
