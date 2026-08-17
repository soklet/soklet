#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { verifySurefireReports } from './verify-surefire-reports.mjs';

const root = mkdtempSync(resolve(tmpdir(), 'soklet-surefire-verifier-'));

function report(name, { tests, errors = 0, skipped = 0, failures = 0 }) {
  const directory = resolve(root, name);
  mkdirSync(directory, { recursive: true });
  writeFileSync(
    resolve(directory, 'TEST-example.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>\n`
      + `<testsuite name="example" tests="${tests}" errors="${errors}" `
      + `skipped="${skipped}" failures="${failures}">\n</testsuite>\n`,
  );
  return directory;
}

try {
  assert.deepEqual(
    verifySurefireReports(report('pass', { tests: 3, skipped: 1 }), 'fixture', 'candidate'),
    { errors: 0, failures: 0, files: 1, skipped: 1, tests: 3 },
  );
  assert.throws(
    () => verifySurefireReports(report('failure', { tests: 1, failures: 1 })),
    /failures or errors/,
  );
  assert.throws(
    () => verifySurefireReports(report('error', { tests: 1, errors: 1 })),
    /failures or errors/,
  );
  assert.throws(
    () => verifySurefireReports(report('all-skipped', { tests: 2, skipped: 2 })),
    /did not execute any tests/,
  );
  const empty = resolve(root, 'empty');
  mkdirSync(empty);
  assert.throws(() => verifySurefireReports(empty), /produced no Surefire/);
  console.log('Surefire report verifier self-test passed.');
} finally {
  rmSync(root, { force: true, recursive: true });
}
