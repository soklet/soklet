#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';

import {
  ORDERED_PATTERNS,
  scanText,
  verifyVersionTransition,
} from './verify-version-transition-inventory.mjs';

const INVENTORY_PATH = 'release/version-transition-inventory.json';
const REPLACEMENTS = Object.freeze({
  '3.6': '4.0',
  '3.6.0': '4.0.0',
  '3.6.0-SNAPSHOT': '4.0.0-SNAPSHOT',
});
const BASELINE_FILES = Object.freeze({
  'active.txt': 'snapshot=3.6.0-SNAPSHOT\nexact=3.6.0\nline=3.6\n',
  'fixture.txt': 'negative artifact path 3.6.1\n',
  'history.txt': 'dated checkpoint for 3.6.0\n',
  'plugin.xml': '<artifactId>build-helper-maven-plugin</artifactId><version>3.6.0</version>\n',
  'remove.java': '@Deprecated(since = "3.6.0")\n',
});
const CLASSIFICATIONS = Object.freeze({
  'active.txt': 'RETARGET_NOW',
  'fixture.txt': 'FIXTURE_PRESERVE',
  'history.txt': 'HISTORICAL_PRESERVE',
  'plugin.xml': 'UNRELATED_VERSION_PRESERVE',
  'remove.java': 'REMOVE_BY_MCP_R4',
});

function git(root, args) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `git ${args.join(' ')} failed: ${result.stderr}`);
  return result.stdout.trim();
}

function write(root, path, content) {
  const absolute = join(root, path);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, content, 'utf8');
}

function buildInventory(root, baselineCommit) {
  const occurrences = [];
  for (const path of Object.keys(BASELINE_FILES).sort()) {
    for (const scanned of scanText(path, BASELINE_FILES[path])) {
      const classification = CLASSIFICATIONS[path];
      occurrences.push({
        classification,
        exactLineSha256: scanned.exactLineSha256,
        line: scanned.line,
        literal: scanned.literal,
        occurrenceIndex: scanned.occurrenceIndex,
        owner: classification === 'RETARGET_NOW' ? 'U1/lifecycle-R' : 'fixture-owner',
        path,
        rationale: `Self-test ${classification} occurrence.`,
        replacement: classification === 'RETARGET_NOW'
          ? REPLACEMENTS[scanned.literal]
          : null,
      });
    }
  }
  return {
    baselineCommit,
    comparisonVersion: '3.5.1',
    developmentVersion: '4.0.0-SNAPSHOT',
    formatVersion: 1,
    occurrences,
    orderedPatterns: ORDERED_PATTERNS.map((pattern) => ({
      contextExclusions: [...pattern.contextExclusions],
      id: pattern.id,
      ordinal: pattern.ordinal,
      regex: pattern.regex,
      regexFlags: pattern.regexFlags,
    })),
    releaseVersion: '4.0.0',
  };
}

function createFixture() {
  const root = mkdtempSync(join(tmpdir(), 'soklet-version-inventory-'));
  for (const [path, content] of Object.entries(BASELINE_FILES)) {
    write(root, path, content);
  }
  git(root, ['init', '-q']);
  git(root, ['config', 'user.email', 'self-test@soklet.invalid']);
  git(root, ['config', 'user.name', 'Soklet Self-Test']);
  git(root, ['add', '--', ...Object.keys(BASELINE_FILES)]);
  git(root, ['commit', '-q', '-m', 'synthetic baseline']);
  const baselineCommit = git(root, ['rev-parse', 'HEAD']);
  write(root, INVENTORY_PATH, `${JSON.stringify(buildInventory(root, baselineCommit), null, 2)}\n`);
  return root;
}

function readInventory(root) {
  return JSON.parse(readFileSync(join(root, INVENTORY_PATH), 'utf8'));
}

function writeInventory(root, inventory) {
  write(root, INVENTORY_PATH, `${JSON.stringify(inventory, null, 2)}\n`);
}

function applyRetarget(root, { final = false, removeMarker = false } = {}) {
  const snapshotReplacement = final ? '4.0.0' : '4.0.0-SNAPSHOT';
  write(
    root,
    'active.txt',
    `snapshot=${snapshotReplacement}\nexact=4.0.0\nline=4.0\n`,
  );
  if (removeMarker) {
    write(root, 'remove.java', 'final class Marker {}\n');
  }
}

function expectFailure(root, stage, pattern) {
  assert.throws(
    () => verifyVersionTransition({ root, stage }),
    pattern,
  );
}

function runCase(name, body) {
  const root = createFixture();
  try {
    body(root);
    process.stdout.write(`PASS ${name}\n`);
  } finally {
    rmSync(root, { force: true, recursive: true });
  }
}

runCase('positive stage semantics', (root) => {
  assert.equal(verifyVersionTransition({ root, stage: 'baseline' }).occurrences, 7);
  applyRetarget(root);
  write(root, 'active.txt', `host-wiring=true\n${readFileSync(join(root, 'active.txt'), 'utf8')}`);
  assert.equal(verifyVersionTransition({ root, stage: 'post-retarget' }).stage, 'post-retarget');
  applyRetarget(root, { removeMarker: true });
  assert.equal(verifyVersionTransition({ root, stage: 'post-d2' }).stage, 'post-d2');
  applyRetarget(root, { final: true, removeMarker: true });
  assert.equal(verifyVersionTransition({ root, stage: 'final' }).stage, 'final');
});

runCase('missing occurrence', (root) => {
  const inventory = readInventory(root);
  inventory.occurrences.splice(0, 1);
  writeInventory(root, inventory);
  expectFailure(root, 'baseline', /baseline coverage count differs/u);
});

runCase('duplicate row', (root) => {
  const inventory = readInventory(root);
  inventory.occurrences.splice(1, 0, { ...inventory.occurrences[0] });
  writeInventory(root, inventory);
  expectFailure(root, 'baseline', /duplicates|strict ASCII/u);
});

runCase('line relocation', (root) => {
  write(root, 'history.txt', `inserted line\n${BASELINE_FILES['history.txt']}`);
  expectFailure(root, 'baseline', /changed or relocated/u);
});

runCase('changed preserved bytes', (root) => {
  write(root, 'history.txt', 'altered checkpoint for 3.6.0\n');
  expectFailure(root, 'baseline', /changed or relocated/u);
});

runCase('plugin match cannot be regex-excluded', (root) => {
  const inventory = readInventory(root);
  inventory.occurrences = inventory.occurrences.filter(({ path }) => path !== 'plugin.xml');
  writeInventory(root, inventory);
  expectFailure(root, 'baseline', /baseline coverage count differs/u);
});

runCase('early REMOVE_BY_MCP_R4 removal', (root) => {
  applyRetarget(root, { removeMarker: true });
  expectFailure(root, 'post-retarget', /removed or relocated too early/u);
});

runCase('surviving REMOVE_BY_MCP_R4 marker after D2', (root) => {
  applyRetarget(root);
  expectFailure(root, 'post-d2', /REMOVE_BY_MCP_R4 occurrence survives post-d2/u);
});

runCase('active snapshot at final', (root) => {
  applyRetarget(root, { removeMarker: true });
  expectFailure(root, 'final', /active snapshot 4\.0\.0-SNAPSHOT survives final/u);
});

runCase('escaped active product regex after retarget', (root) => {
  applyRetarget(root);
  write(root, 'active.txt', `${readFileSync(join(root, 'active.txt'), 'utf8')}regex=3\\.6\\.0\n`);
  expectFailure(root, 'post-retarget', /encoded active 3\.6\.0 product-version text survives/u);
});

runCase('hex-encoded active product version after retarget', (root) => {
  applyRetarget(root);
  write(root, 'active.txt', `${readFileSync(join(root, 'active.txt'), 'utf8')}hex=332e362e302d534e415053484f54\n`);
  expectFailure(root, 'post-retarget', /encoded active 3\.6\.0 product-version text survives/u);
});

console.log('version-transition inventory self-test PASS (11 cases)');
