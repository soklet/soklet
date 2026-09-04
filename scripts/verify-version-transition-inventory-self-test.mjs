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
  baselineGovernanceSha256,
  currentStageCensusSha256,
  derivePostU7CurrentStage,
  scanText,
  verifyVersionTransition,
} from './verify-version-transition-inventory.mjs';

const INVENTORY_PATH = 'release/version-transition-inventory.json';
const SELF_TEST_PENDING_PATH = 'pending-evidence.json';
const REPLACEMENTS = Object.freeze({
  '3.6': '4.0',
  '3.6.0': '4.0.0',
  '3.6.0-SNAPSHOT': '4.0.0-SNAPSHOT',
});
const BASELINE_FILES = Object.freeze({
  'active.txt': 'snapshot=3.6.0-SNAPSHOT\nexact=3.6.0\nline=3.6\n',
  'fixture.txt': 'negative artifact path 3.6.1\n',
  'history.txt': 'dated checkpoint for 3.6.0\ndated checkpoint for 3.6.0\n',
  'plugin.xml': '<profiles>\n<profile>\n<id>owner-a</id>\n<plugin>\n<artifactId>build-helper-maven-plugin</artifactId>\n<version>3.6.0</version>\n</plugin>\n</profile>\n</profiles>\n',
  'remove.java': '@Deprecated(since = "3.6.0")\n',
  'u7-second.txt': 'schema-baseline=3.6.0\n',
  'u7.txt': 'benchmark=3.6.0\n',
});
const CLASSIFICATIONS = Object.freeze({
  'active.txt': 'RETARGET_NOW',
  'fixture.txt': 'FIXTURE_PRESERVE',
  'history.txt': 'HISTORICAL_PRESERVE',
  'plugin.xml': 'UNRELATED_VERSION_PRESERVE',
  'remove.java': 'REMOVE_BY_MCP_R4',
  'u7-second.txt': 'RETARGET_THEN_REMOVE_BY_U7',
  'u7.txt': 'RETARGET_THEN_REMOVE_BY_U7',
});
const EXPECTED_BASELINE_BY_ROOT = new Map();

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

function buildInventory(baselineCommit) {
  const occurrences = [];
  for (const path of Object.keys(BASELINE_FILES).sort()) {
    for (const scanned of scanText(path, BASELINE_FILES[path])) {
      const classification = CLASSIFICATIONS[path];
      const retarget = classification === 'RETARGET_NOW'
        || classification === 'RETARGET_THEN_REMOVE_BY_U7';
      occurrences.push({
        classification,
        exactLineSha256: scanned.exactLineSha256,
        line: scanned.line,
        literal: scanned.literal,
        occurrenceIndex: scanned.occurrenceIndex,
        owner: classification === 'RETARGET_THEN_REMOVE_BY_U7'
          ? 'U7/MCP-C'
          : classification === 'RETARGET_NOW'
            ? 'U1/lifecycle-R'
            : 'fixture-owner',
        path,
        rationale: classification === 'RETARGET_THEN_REMOVE_BY_U7'
          ? 'The benchmark comparison is retargeted through post-D2 and removed by U7.'
          : `Self-test ${classification} occurrence.`,
        replacement: retarget ? REPLACEMENTS[scanned.literal] : null,
      });
    }
  }
  return {
    baselineCommit,
    comparisonVersion: '3.5.1',
    currentStage: null,
    developmentVersion: '4.0.0-SNAPSHOT',
    formatVersion: 2,
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

function readInventory(root) {
  return JSON.parse(readFileSync(join(root, INVENTORY_PATH), 'utf8'));
}

function writeInventory(root, inventory) {
  write(root, INVENTORY_PATH, `${JSON.stringify(inventory, null, 2)}\n`);
}

function acceptMutatedBaselineForCase(root, inventory) {
  EXPECTED_BASELINE_BY_ROOT.set(root, baselineGovernanceSha256(inventory));
}

function applyStage(root, {
  final = false,
  removeD2 = false,
  removeU7 = false,
} = {}) {
  write(
    root,
    'active.txt',
    `snapshot=${final ? '4.0.0' : '4.0.0-SNAPSHOT'}\nexact=4.0.0\nline=4.0\n`,
  );
  write(
    root,
    'remove.java',
    removeD2 ? 'final class Marker {}\n' : BASELINE_FILES['remove.java'],
  );
  write(
    root,
    'u7.txt',
    removeU7 ? 'benchmark retired\n' : 'benchmark=4.0.0\n',
  );
  write(
    root,
    'u7-second.txt',
    removeU7 ? 'schema baseline retired\n' : 'schema-baseline=4.0.0\n',
  );
  write(
    root,
    'current-only.txt',
    `runtime=${final ? '4.0.0' : '4.0.0-SNAPSHOT'}\n`,
  );
  write(root, 'current-only-negative.txt', 'unsupported=4.0.0-SNAPSHOT\n');
  write(root, SELF_TEST_PENDING_PATH, 'release=4.0.0\n');
}

function restoreBaseline(root) {
  for (const [path, content] of Object.entries(BASELINE_FILES)) {
    write(root, path, content);
  }
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
  const inventory = buildInventory(git(root, ['rev-parse', 'HEAD']));
  write(root, 'current-only.txt', 'runtime=4.0.0-SNAPSHOT\n');
  write(root, 'current-only-negative.txt', 'unsupported=4.0.0-SNAPSHOT\n');
  write(root, SELF_TEST_PENDING_PATH, 'release=4.0.0\n');
  git(root, ['add', '--', 'current-only.txt', 'current-only-negative.txt']);
  applyStage(root, { removeD2: true, removeU7: true });
  inventory.currentStage = derivePostU7CurrentStage({
    d2RemovalAnchorLines: { 'remove.java\t1\t0': 1 },
    inventory,
    pendingCurrentStagePaths: [SELF_TEST_PENDING_PATH],
    preservedFinalSnapshotAnchors: ['current-only-negative.txt\t1\t0'],
    root,
  });
  const expectedCurrentStageCensusSha256 = inventory.currentStage.censusSha256;
  EXPECTED_BASELINE_BY_ROOT.set(root, baselineGovernanceSha256(inventory));
  restoreBaseline(root);
  writeInventory(root, inventory);
  return { expectedCurrentStageCensusSha256, root };
}

function verify(root, stage, expectedCurrentStageCensusSha256) {
  const expectedBaselineGovernanceSha256 = EXPECTED_BASELINE_BY_ROOT.get(root);
  assert.notEqual(expectedBaselineGovernanceSha256, undefined);
  return verifyVersionTransition({
    expectedBaselineGovernanceSha256,
    expectedCurrentStageCensusSha256,
    pendingCurrentStagePaths: [SELF_TEST_PENDING_PATH],
    root,
    stage,
  });
}

function expectFailure(root, stage, expectedCurrentStageCensusSha256, pattern) {
  assert.throws(
    () => verify(root, stage, expectedCurrentStageCensusSha256),
    pattern,
  );
}

function runCase(name, body) {
  const fixture = createFixture();
  try {
    body(fixture);
    process.stdout.write(`PASS ${name}\n`);
  } finally {
    EXPECTED_BASELINE_BY_ROOT.delete(fixture.root);
    rmSync(fixture.root, { force: true, recursive: true });
  }
}

runCase('positive stage semantics and deterministic final conversion', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  assert.equal(verify(root, 'baseline', expectedCurrentStageCensusSha256).occurrences, 10);
  applyStage(root);
  assert.equal(verify(root, 'post-retarget', expectedCurrentStageCensusSha256).stage, 'post-retarget');
  applyStage(root, { removeD2: true });
  assert.equal(verify(root, 'post-d2', expectedCurrentStageCensusSha256).stage, 'post-d2');
  applyStage(root, { removeD2: true, removeU7: true });
  assert.equal(verify(root, 'post-u7', expectedCurrentStageCensusSha256).stage, 'post-u7');
  git(root, ['add', '--', SELF_TEST_PENDING_PATH]);
  assert.equal(verify(root, 'post-u7', expectedCurrentStageCensusSha256).stage, 'post-u7');
  applyStage(root, { final: true, removeD2: true, removeU7: true });
  assert.equal(verify(root, 'final', expectedCurrentStageCensusSha256).stage, 'final');
});

runCase('missing inventory occurrence', ({ expectedCurrentStageCensusSha256, root }) => {
  const inventory = readInventory(root);
  inventory.occurrences.splice(0, 1);
  acceptMutatedBaselineForCase(root, inventory);
  writeInventory(root, inventory);
  expectFailure(
    root,
    'baseline',
    expectedCurrentStageCensusSha256,
    /currentStage|baseline coverage count differs/u,
  );
});

runCase('duplicate inventory row', ({ expectedCurrentStageCensusSha256, root }) => {
  const inventory = readInventory(root);
  inventory.occurrences.splice(1, 0, { ...inventory.occurrences[0] });
  acceptMutatedBaselineForCase(root, inventory);
  writeInventory(root, inventory);
  expectFailure(root, 'baseline', expectedCurrentStageCensusSha256, /duplicates|strict ASCII/u);
});

runCase('baseline relocation remains strict', ({ expectedCurrentStageCensusSha256, root }) => {
  write(root, 'history.txt', `inserted line\n${BASELINE_FILES['history.txt']}`);
  expectFailure(root, 'baseline', expectedCurrentStageCensusSha256, /changed or relocated/u);
});

runCase('deleted preserved occurrence after U7', ({ expectedCurrentStageCensusSha256, root }) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'history.txt', 'dated checkpoint for 3.6.0\n');
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed masked file context changed|reviewed current-stage census differs/u,
  );
});

runCase('semantic plugin-owner laundering is rejected', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(
    root,
    'plugin.xml',
    '<profiles>\n<profile>\n<id>owner-a</id>\n<plugin>\n<artifactId>different-maven-plugin</artifactId>\n<version>3.6.0</version>\n</plugin>\n</profile>\n</profiles>\n',
  );
  expectFailure(root, 'post-u7', expectedCurrentStageCensusSha256, /reviewed masked file context changed/u);
});

runCase('unrelated target cannot substitute for required replacement', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'active.txt', 'snapshot=4.0.0-SNAPSHOT\nunrelated=4.0.0\nline=4.0\n');
  expectFailure(root, 'post-u7', expectedCurrentStageCensusSha256, /reviewed masked file context changed/u);
});

runCase('early semantic plugin-owner laundering is rejected', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root);
  write(
    root,
    'plugin.xml',
    BASELINE_FILES['plugin.xml'].replace('owner-a', 'owner-b'),
  );
  expectFailure(root, 'post-retarget', expectedCurrentStageCensusSha256, /semantic context differs/u);
});

runCase('early unrelated target cannot substitute for required replacement', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root);
  write(root, 'active.txt', 'snapshot=4.0.0-SNAPSHOT\nunrelated=4.0.0\nline=4.0\n');
  expectFailure(root, 'post-retarget', expectedCurrentStageCensusSha256, /semantic context differs/u);
});

runCase('current census target addition is rejected', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(
    root,
    'active.txt',
    `${readFileSync(join(root, 'active.txt'), 'utf8')}new-target=4.0.0\n`,
  );
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed masked file context changed|reviewed current-stage census differs/u,
  );
});

runCase('current census target removal is rejected', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'active.txt', 'snapshot=4.0.0-SNAPSHOT\nline=4.0\n');
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed masked file context changed|reviewed current-stage census differs/u,
  );
});

runCase('new tracked target-bearing path is rejected tree-wide', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'rogue-current.txt', 'rogue=4.0.0-SNAPSHOT\n');
  git(root, ['add', '--', 'rogue-current.txt']);
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed current-stage files differ/u,
  );
});

runCase('arbitrary untracked target-bearing path is outside reviewed scope', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'ignored-untracked.txt', 'ignored=4.0.0-SNAPSHOT\n');
  assert.equal(verify(root, 'post-u7', expectedCurrentStageCensusSha256).stage, 'post-u7');
});

runCase('reviewed pending current-stage file is required', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  rmSync(join(root, SELF_TEST_PENDING_PATH));
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed pending current-stage file is missing/u,
  );
});

runCase('coherently resealed mapping tamper is rejected', ({ root }) => {
  const inventory = readInventory(root);
  const index = inventory.currentStage.occurrences.findIndex((tuple) =>
    tuple.startsWith('REPLACED\tactive.txt\t'));
  assert.notEqual(index, -1);
  const fields = inventory.currentStage.occurrences[index].split('\t');
  fields.splice(7, 3, 'history.txt', '1', '0');
  inventory.currentStage.occurrences[index] = fields.join('\t');
  inventory.currentStage.censusSha256 = currentStageCensusSha256(inventory.currentStage);
  writeInventory(root, inventory);
  expectFailure(
    root,
    'baseline',
    inventory.currentStage.censusSha256,
    /may not move to another file|does not identify its exact required replacement/u,
  );
});

runCase('coherently resealed removed-key tamper is rejected', ({ root }) => {
  const inventory = readInventory(root);
  inventory.currentStage.removedBaselineKeys[0] = 'history.txt\t1\t0';
  inventory.currentStage.censusSha256 = currentStageCensusSha256(inventory.currentStage);
  writeInventory(root, inventory);
  expectFailure(
    root,
    'baseline',
    inventory.currentStage.censusSha256,
    /removedBaselineKeys|does not have an approved removal classification/u,
  );
});

runCase('reviewed late removal remains explicit and deterministic', ({ root }) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(root, 'active.txt', 'snapshot=4.0.0-SNAPSHOT\nexact=4.0.0\n');
  const inventory = readInventory(root);
  inventory.currentStage = derivePostU7CurrentStage({
    d2RemovalAnchorLines: { 'remove.java\t1\t0': 1 },
    inventory,
    pendingCurrentStagePaths: [SELF_TEST_PENDING_PATH],
    preservedFinalSnapshotAnchors: ['current-only-negative.txt\t1\t0'],
    reviewedRemovedBaselineKeys: ['active.txt\t3\t0'],
    root,
  });
  writeInventory(root, inventory);
  assert.equal(
    verify(root, 'post-u7', inventory.currentStage.censusSha256).stage,
    'post-u7',
  );
});

runCase('internal census digest seal rejects tuple tampering', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  const inventory = readInventory(root);
  inventory.currentStage.files[0] += 'tamper';
  writeInventory(root, inventory);
  expectFailure(root, 'baseline', expectedCurrentStageCensusSha256, /does not seal the exact reviewed census/u);
});

runCase('independent verifier census pin rejects a coherent replacement', ({ root }) => {
  expectFailure(root, 'baseline', '0'.repeat(64), /does not match the independent verifier pin/u);
});

runCase('independent baseline-governance pin rejects coherent metadata tampering', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  const inventory = readInventory(root);
  const row = inventory.occurrences.find(({ classification }) =>
    classification === 'HISTORICAL_PRESERVE');
  assert.notEqual(row, undefined);
  row.classification = 'FIXTURE_PRESERVE';
  row.owner = 'bogus-owner';
  row.rationale = 'Bogus but nonblank governance metadata.';
  writeInventory(root, inventory);
  expectFailure(
    root,
    'baseline',
    expectedCurrentStageCensusSha256,
    /baseline governance does not match the independent verifier pin/u,
  );
});

runCase('early REMOVE_BY_MCP_R4 removal', ({ expectedCurrentStageCensusSha256, root }) => {
  applyStage(root, { removeD2: true });
  expectFailure(root, 'post-retarget', expectedCurrentStageCensusSha256, /exact bounded version projection differs/u);
});

runCase('surviving REMOVE_BY_MCP_R4 marker after D2', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root);
  expectFailure(root, 'post-d2', expectedCurrentStageCensusSha256, /exact bounded version projection differs/u);
});

runCase('partial REMOVE_BY_MCP_R4 literal stripping is rejected after D2', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true });
  write(root, 'remove.java', '@Deprecated\nfinal class Marker {}\n');
  expectFailure(
    root,
    'post-d2',
    expectedCurrentStageCensusSha256,
    /reviewed D2 removal\/replacement context is missing/u,
  );
});

runCase('early RETARGET_THEN_REMOVE_BY_U7 removal', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  expectFailure(root, 'post-d2', expectedCurrentStageCensusSha256, /exact bounded version projection differs/u);
});

runCase('surviving RETARGET_THEN_REMOVE_BY_U7 replacement after U7', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true });
  expectFailure(
    root,
    'post-u7',
    expectedCurrentStageCensusSha256,
    /reviewed masked file context changed|reviewed current-stage census differs/u,
  );
});

runCase('wrong final literal conversion', ({ expectedCurrentStageCensusSha256, root }) => {
  applyStage(root, { final: true, removeD2: true, removeU7: true });
  write(root, 'active.txt', 'snapshot=4.0\nexact=4.0.0\nline=4.0\n');
  expectFailure(root, 'final', expectedCurrentStageCensusSha256, /reviewed current-stage census differs/u);
});

runCase('current-only active token cannot disappear at final', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { final: true, removeD2: true, removeU7: true });
  rmSync(join(root, 'current-only.txt'));
  expectFailure(
    root,
    'final',
    expectedCurrentStageCensusSha256,
    /reviewed current-stage files differ/u,
  );
});

runCase('current-only active token requires exact final conversion', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { final: true, removeD2: true, removeU7: true });
  write(root, 'current-only.txt', 'runtime=5.0.0\n');
  expectFailure(
    root,
    'final',
    expectedCurrentStageCensusSha256,
    /reviewed current-stage files differ/u,
  );
});

runCase('preserved final snapshot fixture cannot be converted', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { final: true, removeD2: true, removeU7: true });
  write(root, 'current-only-negative.txt', 'unsupported=4.0.0\n');
  expectFailure(root, 'final', expectedCurrentStageCensusSha256, /reviewed current-stage census differs/u);
});

runCase('active snapshot at final', ({ expectedCurrentStageCensusSha256, root }) => {
  applyStage(root, { removeD2: true, removeU7: true });
  expectFailure(root, 'final', expectedCurrentStageCensusSha256, /reviewed current-stage census differs/u);
});

runCase('escaped active product regex after U7', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(
    root,
    'active.txt',
    `${readFileSync(join(root, 'active.txt'), 'utf8')}regex=3\\.6\\.0\n`,
  );
  expectFailure(root, 'post-u7', expectedCurrentStageCensusSha256, /encoded active 3\.6\.0 product-version text survives/u);
});

runCase('hex-encoded active product version after U7', ({
  expectedCurrentStageCensusSha256,
  root,
}) => {
  applyStage(root, { removeD2: true, removeU7: true });
  write(
    root,
    'active.txt',
    `${readFileSync(join(root, 'active.txt'), 'utf8')}hex=332e362e302d534e415053484f54\n`,
  );
  expectFailure(root, 'post-u7', expectedCurrentStageCensusSha256, /encoded active 3\.6\.0 product-version text survives/u);
});

console.log('version-transition inventory self-test PASS (32 cases)');
