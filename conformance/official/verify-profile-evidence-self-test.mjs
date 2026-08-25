#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  canonicalJson,
  verifyProfileEvidence,
} from './verify-profile-evidence.mjs';

const officialRoot = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(officialRoot, '../..');
const indexPath = resolve(officialRoot, 'protocol-profile-evidence.json');
const verifierPath = resolve(officialRoot, 'verify-profile-evidence.mjs');
const temporaryRoot = mkdtempSync(join(tmpdir(), 'soklet-profile-evidence-'));

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function fixture(name, value) {
  const path = join(temporaryRoot, `${name}.json`);
  writeFileSync(path, canonicalJson(value));
  return path;
}

function expectInvalid(name, source, mutate, pattern) {
  const value = clone(source);
  mutate(value);
  assert.throws(() => verifyProfileEvidence({
    projectRoot,
    indexPath: fixture(name, value),
  }), pattern, name);
}

try {
  const source = JSON.parse(readFileSync(indexPath, 'utf8'));
  const first = verifyProfileEvidence({ projectRoot, indexPath });
  const second = verifyProfileEvidence({ projectRoot, indexPath });
  assert.deepEqual(first, second, 'Verification output must be deterministic.');
  assert.equal(first.report.status, 'PASSED');
  assert.equal(first.report.productionProfileCount, 1);
  assert.deepEqual(first.report.revisions, ['2026-07-28']);
  assert.equal(first.report.verifiedProfiles[0].goldenManifestCount, 5);
  assert.equal(first.report.verifiedProfiles[0].interoperabilityPinCount, 2);
  assert.equal(first.reportText, canonicalJson(first.report));

  expectInvalid('missing-entry', source,
    (value) => { value.profiles = []; },
    /exactly one production profile/);
  expectInvalid('unknown-entry', source,
    (value) => { value.profiles[0].revision = '2099-01-01'; },
    /missing, unknown, duplicated, or reordered/);
  expectInvalid('missing-schema-authority', source,
    (value) => { delete value.profiles[0].schema; },
    /keys must be exactly/);
  expectInvalid('unknown-profile-key', source,
    (value) => { value.profiles[0].unexpected = true; },
    /keys must be exactly/);
  expectInvalid('unknown-ownership', source,
    (value) => { value.methodParameterOwnership = 'profile-local'; },
    /must be exactly global-2026-deferred-r2c/);
  expectInvalid('widened-ownership', source,
    (value) => {
      value.methodParameterOwnership = [
        'global-2026-deferred-r2c', 'profile-local',
      ];
    },
    /must be exactly global-2026-deferred-r2c/);
  expectInvalid('deferred-r2c-cardinality', source,
    (value) => { value.profiles.push(clone(value.profiles[0])); },
    /exactly one production profile/);
  expectInvalid('changed-pin', source,
    (value) => { value.profiles[0].schema.sha256 = '0'.repeat(64); },
    /digest does not match/);

  const subprocess = spawnSync(process.execPath, [verifierPath], {
    cwd: projectRoot,
    encoding: 'utf8',
  });
  assert.equal(subprocess.status, 0, subprocess.stderr);
  assert.equal(subprocess.stderr, '');
  assert.equal(subprocess.stdout, first.reportText);

  console.log('Protocol-profile evidence verifier self-test passed (8 negative cases).');
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
