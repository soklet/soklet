#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  FINITE_BOUND_MATCHER_RULES,
  PRIVACY_MATCHER_RULES,
  PRIVACY_REQUIRED_DELEGATED_OWNERS,
  PRIVACY_SCAN_ROOTS,
  canonicalJson,
  deriveFiniteBoundCandidates,
  derivePrivacyBoundaryCandidates,
  finiteBoundExclusionsSha256,
  finiteBoundSemanticsSha256,
  privacySemanticsSha256,
  verifyFiniteBoundInventory,
  verifyLimitsAccountingAuthority,
  verifyMatrixClosure,
  verifyPrivacyBoundaryInventory,
} from './verify-release-matrix-closure.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const registryPath = join(projectRoot, 'release/mcp-conformance-matrix-closure.json');
const manifestPath = join(projectRoot, 'release/release-validation-manifest.json');
const residualEvidencePath = join(
  projectRoot,
  'release/mcp-residual-closure-evidence.json',
);
const verifierPath = join(projectRoot, 'scripts/verify-release-matrix-closure.mjs');
const temporaryRoot = mkdtempSync(join(tmpdir(), 'soklet-matrix-closure-'));
const untrackedEvidenceName = '.matrix-closure-untracked-self-test.tmp';
const untrackedEvidencePath = join(projectRoot, untrackedEvidenceName);
const symlinkEvidenceName = '.matrix-closure-symlink-self-test';
const symlinkEvidencePath = join(projectRoot, symlinkEvidenceName);
const finiteBoundProjectRoot = join(temporaryRoot, 'finite-bound-project');
const finiteBoundInventoryPath = join(
  finiteBoundProjectRoot,
  'conformance/mcp-finite-bound-inventory.json',
);
const finiteBoundScanRoots = Object.freeze([
  'src/main/java/com/soklet/**/*.java',
  'src/main/java/com/soklet/Mcp*.java',
]);
let finiteBoundFixtureSemanticsSha256;
let finiteBoundFixtureExclusionsSha256;
let privacyFixtureSemanticsSha256;
const privacyProjectRoot = join(temporaryRoot, 'privacy-boundary-project');
const privacyInventoryPath = join(
  privacyProjectRoot,
  'conformance/mcp-privacy-boundary-inventory.json',
);
const privacyScanRoots = Object.freeze([
  'src/main/java/com/soklet/**/*.java',
]);
const privacyArtifactRoots = Object.freeze([
  'conformance/official/final-schema/**/*',
  'conformance/privacy-self-test-exact.golden',
  'conformance/privacy-self-test-recursive/**/*',
  'fuzz/src/test/resources/com/soklet/**/*',
  'src/test/resources/com/soklet/internal/mcp/schema/**/*',
  'src/test/resources/multipart-request-body',
]);

const expectedResidualIds = Object.freeze([
  'SOK-VALID-002',
  'SOK-STATE-002',
  'SOK-STATE-007',
  'SOK-PRIV-001',
  'AMB-002',
]);
const expectedReportKeys = Object.freeze([
  'formatVersion',
  'protocolVersion',
  'releaseVersion',
  'sourceMatrixPath',
  'sourceMatrixLastUpdated',
  'sourceMatrixSha256',
  'status',
  'rowCount',
  'rowIdsSha256',
  'registrySha256',
  'residualSha256',
  'dispositionCounts',
  'releaseGateDependencies',
  'unresolvedRows',
  'rows',
]);

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function writeCanonicalFixture(name, value) {
  const path = join(temporaryRoot, `${name}.json`);
  writeFileSync(path, canonicalJson(value));
  return path;
}

function writeRawFixture(name, text) {
  const path = join(temporaryRoot, `${name}.json`);
  writeFileSync(path, text);
  return path;
}

function verifyFixture(path, options = {}) {
  return verifyMatrixClosure({
    projectRoot,
    manifestPath: options.manifestPath ?? manifestPath,
    registryPath: path,
    ...(options.finiteBoundInventoryPath === undefined ? {}
      : { finiteBoundInventoryPath: options.finiteBoundInventoryPath }),
    ...(options.finiteBoundProjectRoot === undefined ? {}
      : { finiteBoundProjectRoot: options.finiteBoundProjectRoot }),
    ...(options.finiteBoundExpectedScanRoots === undefined ? {}
      : { finiteBoundExpectedScanRoots: options.finiteBoundExpectedScanRoots }),
    ...(options.finiteBoundExpectedCategories === undefined ? {}
      : { finiteBoundExpectedCategories: options.finiteBoundExpectedCategories }),
    ...(options.finiteBoundExpectedExclusionsSha256 === undefined ? {}
      : {
        finiteBoundExpectedExclusionsSha256:
          options.finiteBoundExpectedExclusionsSha256,
      }),
    ...(options.finiteBoundExpectedSemanticsSha256 === undefined ? {}
      : {
        finiteBoundExpectedSemanticsSha256:
          options.finiteBoundExpectedSemanticsSha256,
      }),
    ...(options.gitExecutable === undefined
      ? { gitExecutable: semanticGitExecutable() }
      : { gitExecutable: options.gitExecutable }),
    privacyExpectedArtifactRoots: options.privacyExpectedArtifactRoots
      ?? privacyArtifactRoots,
    privacyExpectedScanRoots: options.privacyExpectedScanRoots
      ?? privacyScanRoots,
    privacyExpectedSemanticsSha256:
      options.privacyExpectedSemanticsSha256
        ?? privacyFixtureSemanticsSha256,
    privacyInventoryPath: options.privacyInventoryPath
      ?? privacyInventoryPath,
    privacyGitExecutable: options.privacyGitExecutable
      ?? semanticGitExecutable(),
    privacyProjectRoot: options.privacyProjectRoot
      ?? privacyProjectRoot,
    residualEvidencePath: options.residualEvidencePath
      ?? residualEvidencePath,
  });
}

function expectInvalid(name, source, mutate, expected, options = {}) {
  const fixture = clone(source);
  mutate(fixture);
  const path = writeCanonicalFixture(name, fixture);
  assert.throws(() => verifyFixture(path, options), expected, name);
}

function expectRawInvalid(name, text, expected) {
  const path = writeRawFixture(name, text);
  assert.throws(() => verifyFixture(path), expected, name);
}

function writeResidualFixture(name, value) {
  const path = join(temporaryRoot, `residual-${name}.json`);
  writeFileSync(path, canonicalJson(value));
  return path;
}

function writeRawResidualFixture(name, text) {
  const path = join(temporaryRoot, `residual-${name}.json`);
  writeFileSync(path, text);
  return path;
}

function expectResidualInvalid(name, source, mutate, expected, options = {}) {
  const fixture = clone(source);
  mutate(fixture);
  const path = writeResidualFixture(name, fixture);
  assert.throws(
    () => verifyFixture(options.registryPath ?? registryPath, {
      ...options,
      residualEvidencePath: path,
    }),
    expected,
    name,
  );
}

function expectRawResidualInvalid(name, text, expected) {
  const path = writeRawResidualFixture(name, text);
  assert.throws(
    () => verifyFixture(registryPath, { residualEvidencePath: path }),
    expected,
    name,
  );
}

function row(registry, id) {
  const value = registry.rows.find((candidate) => candidate.id === id);
  assert.ok(value, `Missing expected row ${id}.`);
  return value;
}

function finiteBoundClassification(candidate) {
  return {
    file: candidate.file,
    key: candidate.key,
    matcherRuleId: candidate.matcherRuleId,
    member: candidate.member,
    owner: candidate.owner,
  };
}

function finiteBoundFixture(candidates) {
  return {
    bounds: [
      {
        boundaryTests: [
          'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java#rejectsOneOver',
        ],
        category: 'SELF_TEST',
        deterministicFailure: {
          contract: 'Synthetic deterministic failure contract.',
          stage: 'SELF_TEST',
        },
        enforcementOwners: [
          {
            file: 'src/main/java/com/soklet/McpFiniteBoundFixture.java',
            member: 'maximumFrameBytes',
            owner: 'com.soklet.McpFiniteBoundFixture',
          },
        ],
        id: 'FINITE-SELF-001',
        name: 'Synthetic finite-bound scanner coverage',
        positiveTests: [
          'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java#acceptsBoundary',
        ],
        sourceOwners: candidates
          .map(finiteBoundClassification)
          .sort((left, right) => left.key < right.key ? -1 : left.key > right.key ? 1 : 0),
        values: [
          {
            accounting: 'synthetic items retained by the self-test fixture',
            key: 'self-test.maximum-items',
            unit: 'ITEMS',
            value: '8',
          },
        ],
      },
    ],
    formatVersion: 1,
    matcherRules: clone(FINITE_BOUND_MATCHER_RULES),
    productionProfile: '2026-07-28',
    releaseTarget: '4.0.0',
    reviewedExclusions: [],
    scanRoots: [...finiteBoundScanRoots],
  };
}

function writeFiniteBoundFixture(name, value) {
  const path = join(
    finiteBoundProjectRoot,
    `conformance/mcp-finite-bound-inventory-${name}.json`,
  );
  writeFileSync(path, canonicalJson(value));
  return path;
}

function verifyFiniteBoundFixture(
    path,
    expectedSemanticsSha256 = finiteBoundFixtureSemanticsSha256,
    expectedExclusionsSha256 = finiteBoundFixtureExclusionsSha256) {
  return verifyFiniteBoundInventory({
    expectedCategories: ['SELF_TEST'],
    expectedExclusionsSha256,
    expectedSemanticsSha256,
    expectedScanRoots: finiteBoundScanRoots,
    inventoryPath: path,
    projectRoot: finiteBoundProjectRoot,
  });
}

function expectFiniteBoundInvalid(name, source, mutate, expected) {
  const fixture = clone(source);
  mutate(fixture);
  const path = writeFiniteBoundFixture(name, fixture);
  assert.throws(() => verifyFiniteBoundFixture(path), expected, name);
}

function reviewedExclusion(owner, id = 'FINITE-EX-001') {
  return {
    file: owner.file,
    id,
    key: owner.key,
    matcherRuleId: owner.matcherRuleId,
    member: owner.member,
    owner: owner.owner,
    rationale: 'Reviewed self-test exclusion with an exact declaration identity.',
  };
}

function privacyClassification(candidate) {
  return {
    file: candidate.file,
    key: candidate.key,
    matcherRuleId: candidate.matcherRuleId,
    member: candidate.member,
    occurrence: candidate.occurrence,
    owner: candidate.owner,
    sink: candidate.sink,
  };
}

function privacyFixture(candidates) {
  const categoryByMatcher = {
    'PRIV-MATCH-001': 'LOG',
    'PRIV-MATCH-002': 'METRIC',
    'PRIV-MATCH-003': 'REQUEST',
    'PRIV-MATCH-004': 'THROWABLE',
    'PRIV-MATCH-005': 'REQUEST',
    'PRIV-MATCH-006': 'EXCEPTION',
    'PRIV-MATCH-007': 'FIXTURE',
    'PRIV-MATCH-008': 'DIAGNOSTIC',
    'PRIV-MATCH-009': 'EXCEPTION',
    'PRIV-MATCH-010': 'FIXTURE',
    'PRIV-MATCH-011': 'EXCEPTION',
    'PRIV-MATCH-012': 'EXCEPTION',
    'PRIV-MATCH-013': 'LOG',
    'PRIV-MATCH-014': 'LOG',
    'PRIV-MATCH-015': 'METRIC',
    'PRIV-MATCH-016': 'LOG',
  };
  return {
    artifactRoots: [...privacyArtifactRoots],
    boundaries: PRIVACY_MATCHER_RULES.map(({ id }, index) => ({
      canaryTests: [
        'src/test/java/com/soklet/McpPrivacyBoundaryFixtureTests.java#canary',
      ],
      category: categoryByMatcher[id],
      classification: id === 'PRIV-MATCH-013' ? 'NO_EMISSION' : 'REDACTED',
      contract: `Synthetic exact privacy contract for ${id}.`,
      id: `PRIV-BOUND-${String(index + 1).padStart(3, '0')}`,
      name: `Synthetic ${id} privacy boundary`,
      sourcePaths: candidates
        .filter(({ matcherRuleId }) => matcherRuleId === id)
        .map(privacyClassification)
        .sort((left, right) => left.key < right.key ? -1
          : left.key > right.key ? 1 : 0),
    })),
    delegations: PRIVACY_REQUIRED_DELEGATED_OWNERS.map((owner, index) => ({
      canaryTests: [],
      contract: `Synthetic explicit delegation for ${owner}.`,
      delegatedOwner: owner,
      id: `PRIV-DELEGATION-${String(index + 1).padStart(3, '0')}`,
      name: `Synthetic ${owner} delegation`,
      sourcePaths: [],
    })),
    formatVersion: 1,
    matcherRules: clone(PRIVACY_MATCHER_RULES),
    productionProfile: '2026-07-28',
    releaseTarget: '4.0.0',
    reviewedExclusions: [],
    scanRoots: [...privacyScanRoots],
  };
}

function writePrivacyFixture(name, value) {
  const path = join(
    privacyProjectRoot,
    `conformance/mcp-privacy-boundary-inventory-${name}.json`,
  );
  writeFileSync(path, canonicalJson(value));
  return path;
}

function verifyPrivacyFixture(path, options = {}) {
  return verifyPrivacyBoundaryInventory({
    expectedArtifactRoots: privacyArtifactRoots,
    expectedScanRoots: privacyScanRoots,
    expectedSemanticsSha256: options.expectedSemanticsSha256
      ?? privacyFixtureSemanticsSha256,
    gitExecutable: options.gitExecutable ?? semanticGitExecutable(),
    inventoryPath: path,
    projectRoot: privacyProjectRoot,
  });
}

function expectPrivacyInvalid(name, source, mutate, expected, options = {}) {
  const fixture = clone(source);
  mutate(fixture);
  const path = writePrivacyFixture(name, fixture);
  assert.throws(
    () => verifyPrivacyFixture(path, options),
    expected,
    name,
  );
}

try {
  mkdirSync(dirname(finiteBoundInventoryPath), { recursive: true });
  const finiteBoundSourceDirectory = join(
    finiteBoundProjectRoot,
    'src/main/java/com/soklet',
  );
  mkdirSync(finiteBoundSourceDirectory, { recursive: true });
  writeFileSync(
    join(finiteBoundSourceDirectory, 'McpFiniteBoundFixture.java'),
    `/* package ignored.shadow; */
package com.soklet;

public final class McpFiniteBoundFixture {
  static final String MASKED = "😃 static final int MAXIMUM_MASKED = 99;";
  static final int MAXIMUM_ITEMS = 8;

  record RequestLimits(int maximumNodes) {}

  static int maximumFrameBytes(RequestLimits limits) {
    return Math.addExact(limits.maximumNodes(), 8);
  }

  public static final class Builder {
    public Builder maximumItems(int maximumItems) {
      return this;
    }
  }
}
`,
  );
  const finiteBoundTestDirectory = join(
    finiteBoundProjectRoot,
    'src/test/java/com/soklet',
  );
  mkdirSync(finiteBoundTestDirectory, { recursive: true });
  writeFileSync(
    join(finiteBoundTestDirectory, 'McpFiniteBoundFixtureTests.java'),
    `package com.soklet;

final class McpFiniteBoundFixtureTests {
  void acceptsBoundary() {}
  void rejectsOneOver() {}
}
`,
  );
  const finiteBoundSyntaxRoot = join(temporaryRoot, 'finite-bound-syntax-project');
  const finiteBoundSyntaxSourceDirectory = join(
    finiteBoundSyntaxRoot,
    'src/main/java/com/soklet',
  );
  mkdirSync(finiteBoundSyntaxSourceDirectory, { recursive: true });
  writeFileSync(
    join(finiteBoundSyntaxSourceDirectory, 'McpFiniteBoundSyntax.java'),
    `package com.soklet;

final class McpFiniteBoundSyntax {
  final static int MAXIMUM_REORDERED = 8;
  static final int MAXIMUM_FIRST = 8, MINIMUM_SECOND = 1;
  static final int DERIVED_MARGIN = MAXIMUM_FIRST + 1;
}
`,
  );
  assert.deepEqual(
    deriveFiniteBoundCandidates(finiteBoundSyntaxRoot, [
      'src/main/java/com/soklet/Mcp*.java',
    ]).map(({ matcherRuleId, member }) => ({ matcherRuleId, member })),
    [
      { matcherRuleId: 'FINITE-MATCH-001', member: 'MAXIMUM_FIRST' },
      { matcherRuleId: 'FINITE-MATCH-001', member: 'MAXIMUM_REORDERED' },
      { matcherRuleId: 'FINITE-MATCH-001', member: 'MINIMUM_SECOND' },
      { matcherRuleId: 'FINITE-MATCH-004', member: 'DERIVED_MARGIN' },
    ],
  );
  const finiteBoundCandidates = deriveFiniteBoundCandidates(
    finiteBoundProjectRoot,
    finiteBoundScanRoots,
  );
  assert.equal(finiteBoundCandidates.length, FINITE_BOUND_MATCHER_RULES.length);
  assert.deepEqual(
    Object.fromEntries(FINITE_BOUND_MATCHER_RULES.map(({ id }) => [
      id,
      finiteBoundCandidates.filter(({ matcherRuleId }) => matcherRuleId === id).length,
    ])),
    {
      'FINITE-MATCH-001': 1,
      'FINITE-MATCH-002': 1,
      'FINITE-MATCH-003': 1,
      'FINITE-MATCH-004': 1,
    },
  );
  assert.deepEqual(
    finiteBoundCandidates.map(({ matcherRuleId, member, owner }) => ({
      matcherRuleId,
      member,
      owner,
    })),
    [
      {
        matcherRuleId: 'FINITE-MATCH-001',
        member: 'MAXIMUM_ITEMS',
        owner: 'com.soklet.McpFiniteBoundFixture',
      },
      {
        matcherRuleId: 'FINITE-MATCH-002',
        member: 'maximumNodes',
        owner: 'com.soklet.McpFiniteBoundFixture.RequestLimits',
      },
      {
        matcherRuleId: 'FINITE-MATCH-003',
        member: 'maximumItems(int)',
        owner: 'com.soklet.McpFiniteBoundFixture.Builder',
      },
      {
        matcherRuleId: 'FINITE-MATCH-004',
        member: 'maximumFrameBytes(RequestLimits)',
        owner: 'com.soklet.McpFiniteBoundFixture',
      },
    ],
  );
  const finiteInventory = finiteBoundFixture(finiteBoundCandidates);
  finiteBoundFixtureSemanticsSha256 = finiteBoundSemanticsSha256(
    finiteInventory.bounds,
  );
  finiteBoundFixtureExclusionsSha256 = finiteBoundExclusionsSha256(
    finiteInventory.reviewedExclusions,
  );
  writeFileSync(finiteBoundInventoryPath, canonicalJson(finiteInventory));
  const finiteBaseline = verifyFiniteBoundFixture(finiteBoundInventoryPath);
  assert.deepEqual(finiteBaseline.candidates, finiteBoundCandidates);
  assert.deepEqual(finiteBaseline.exclusions, []);

  const finiteTwoBoundInventory = clone(finiteInventory);
  const secondFiniteBound = clone(finiteTwoBoundInventory.bounds[0]);
  secondFiniteBound.id = 'FINITE-SELF-002';
  secondFiniteBound.name = 'Synthetic cross-bound semantic attribution';
  secondFiniteBound.sourceOwners = finiteTwoBoundInventory.bounds[0]
    .sourceOwners.splice(-1, 1);
  secondFiniteBound.values = [
    {
      accounting: 'synthetic nodes retained by the self-test fixture',
      key: 'self-test.maximum-nodes',
      unit: 'NODES',
      value: '16',
    },
  ];
  finiteTwoBoundInventory.bounds.push(secondFiniteBound);
  const finiteTwoBoundSemanticsSha256 = finiteBoundSemanticsSha256(
    finiteTwoBoundInventory.bounds,
  );
  const finiteTwoBoundPath = writeFiniteBoundFixture(
    'two-bound-semantic-baseline',
    finiteTwoBoundInventory,
  );
  verifyFiniteBoundFixture(
    finiteTwoBoundPath,
    finiteTwoBoundSemanticsSha256,
  );
  const finiteValuesSwap = clone(finiteTwoBoundInventory);
  [finiteValuesSwap.bounds[0].values, finiteValuesSwap.bounds[1].values] = [
    finiteValuesSwap.bounds[1].values,
    finiteValuesSwap.bounds[0].values,
  ];
  const finiteValuesSwapPath = writeFiniteBoundFixture(
    'cross-bound-values-swap',
    finiteValuesSwap,
  );
  assert.throws(
    () => verifyFiniteBoundFixture(
      finiteValuesSwapPath,
      finiteTwoBoundSemanticsSha256,
    ),
    /Finite-bound semantic attribution SHA-256 differs from the reviewed contract/,
    'cross-bound-values-swap',
  );

  const finiteBoundSourceLinkRoot = join(
    temporaryRoot,
    'finite-bound-source-link-project',
  );
  mkdirSync(join(finiteBoundSourceLinkRoot, 'conformance'), { recursive: true });
  writeFileSync(
    join(finiteBoundSourceLinkRoot, 'conformance/mcp-finite-bound-inventory.json'),
    canonicalJson(finiteInventory),
  );
  symlinkSync(
    join(finiteBoundProjectRoot, 'src'),
    join(finiteBoundSourceLinkRoot, 'src'),
    'dir',
  );
  assert.throws(() => verifyFiniteBoundInventory({
    expectedScanRoots: finiteBoundScanRoots,
    projectRoot: finiteBoundSourceLinkRoot,
  }), /Finite-bound source root src\/main\/java path must not contain symlinks/);

  const finiteBoundInventoryLinkRoot = join(
    temporaryRoot,
    'finite-bound-inventory-link-project',
  );
  mkdirSync(finiteBoundInventoryLinkRoot, { recursive: true });
  symlinkSync(
    join(finiteBoundProjectRoot, 'conformance'),
    join(finiteBoundInventoryLinkRoot, 'conformance'),
    'dir',
  );
  assert.throws(() => verifyFiniteBoundInventory({
    expectedScanRoots: finiteBoundScanRoots,
    projectRoot: finiteBoundInventoryLinkRoot,
  }), /Finite-bound inventory path must not contain symlinks/);

  for (const { id } of FINITE_BOUND_MATCHER_RULES) {
    expectFiniteBoundInvalid(
      `omitted-${id.toLowerCase()}`,
      finiteInventory,
      (value) => {
        const ownerIndex = value.bounds[0].sourceOwners.findIndex(
          ({ matcherRuleId }) => matcherRuleId === id,
        );
        assert.notEqual(ownerIndex, -1);
        value.bounds[0].sourceOwners.splice(ownerIndex, 1);
      },
      new RegExp(`omitted=\\[[^\\]]*${id}:`, 'u'),
    );
  }

  expectFiniteBoundInvalid('extra-source-owner', finiteInventory, (value) => {
    const extra = {
      ...value.bounds[0].sourceOwners[0],
      member: 'MAXIMUM_UNDECLARED',
    };
    extra.key = `${extra.matcherRuleId}:${extra.file}#${extra.owner}#${extra.member}`;
    value.bounds[0].sourceOwners.push(extra);
    value.bounds[0].sourceOwners.sort((left, right) =>
      left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
  }, /extra=\[[^\]]*MAXIMUM_UNDECLARED/);
  expectFiniteBoundInvalid('malformed-source-owner-key', finiteInventory, (value) => {
    value.bounds[0].sourceOwners[0].key = 'FINITE-MATCH-001:wrong';
  }, /\.key must be exactly/);
  expectFiniteBoundInvalid('malformed-source-owner-member', finiteInventory, (value) => {
    value.bounds[0].sourceOwners[0].member = 'not#atomic';
  }, /\.member must be one exact Java declaration name/);
  expectFiniteBoundInvalid('malformed-source-owner-owner', finiteInventory, (value) => {
    value.bounds[0].sourceOwners[0].owner = 'UnqualifiedOwner';
  }, /\.owner must be an exact qualified Java owner/);
  expectFiniteBoundInvalid('unknown-source-owner-matcher', finiteInventory, (value) => {
    value.bounds[0].sourceOwners[0].matcherRuleId = 'FINITE-MATCH-999';
  }, /\.matcherRuleId is unknown/);
  expectFiniteBoundInvalid('missing-source-owner-field', finiteInventory, (value) => {
    delete value.bounds[0].sourceOwners[0].member;
  }, /keys must be exactly/);
  expectFiniteBoundInvalid('duplicate-source-owner', finiteInventory, (value) => {
    value.bounds[0].sourceOwners.push(clone(value.bounds[0].sourceOwners[0]));
    value.bounds[0].sourceOwners.sort((left, right) =>
      left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
  }, /Finite-bound classification is duplicated/);
  expectFiniteBoundInvalid('finite-empty-enforcement-owners', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners = [];
  }, /enforcementOwners must be a nonempty array/);
  expectFiniteBoundInvalid('finite-enforcement-owner-extra-key', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners[0].extra = true;
  }, /enforcementOwners\[0\] keys must be exactly/);
  expectFiniteBoundInvalid('finite-enforcement-owner-missing-file', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners[0].file =
      'src/main/java/com/soklet/McpMissingFixture.java';
  }, /enforcementOwners\[0\]\.file does not exist/);
  expectFiniteBoundInvalid('finite-enforcement-owner-missing-key', finiteInventory, (value) => {
    delete value.bounds[0].enforcementOwners[0].member;
  }, /enforcementOwners\[0\] keys must be exactly/);
  expectFiniteBoundInvalid('finite-enforcement-owner-not-declared', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners[0].owner = 'com.soklet.McpInventedOwner';
  }, /owner is not declared by its production source file/);
  expectFiniteBoundInvalid('finite-duplicate-enforcement-owner', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners.push(
      clone(value.bounds[0].enforcementOwners[0]),
    );
  }, /enforcementOwners must not contain duplicates/);
  expectFiniteBoundInvalid('finite-enforcement-owners-reordered', finiteInventory, (value) => {
    value.bounds[0].enforcementOwners.push({
      ...clone(value.bounds[0].enforcementOwners[0]),
      member: 'aaaEnforcer',
    });
  }, /enforcementOwners must be in ASCII identity order/);
  expectFiniteBoundInvalid('finite-empty-positive-tests', finiteInventory, (value) => {
    value.bounds[0].positiveTests = [];
  }, /positiveTests must be a nonempty array/);
  expectFiniteBoundInvalid('finite-empty-boundary-tests', finiteInventory, (value) => {
    value.bounds[0].boundaryTests = [];
  }, /boundaryTests must be a nonempty array/);
  expectFiniteBoundInvalid('finite-duplicate-positive-test', finiteInventory, (value) => {
    value.bounds[0].positiveTests.push(value.bounds[0].positiveTests[0]);
  }, /positiveTests must not contain duplicates/);
  expectFiniteBoundInvalid('finite-positive-tests-reordered', finiteInventory, (value) => {
    value.bounds[0].positiveTests = [
      'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java#rejectsOneOver',
      'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java#acceptsBoundary',
    ];
  }, /positiveTests must be in ASCII order/);
  expectFiniteBoundInvalid('finite-positive-boundary-test-overlap', finiteInventory, (value) => {
    value.bounds[0].boundaryTests = [...value.bounds[0].positiveTests];
  }, /positiveTests and boundaryTests must be disjoint/);
  expectFiniteBoundInvalid('finite-test-method-missing', finiteInventory, (value) => {
    value.bounds[0].boundaryTests = [
      'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java#missingMethod',
    ];
  }, /names no declared test method: missingMethod/);
  expectFiniteBoundInvalid('finite-test-reference-not-exact', finiteInventory, (value) => {
    value.bounds[0].boundaryTests = [
      'src/test/java/com/soklet/McpFiniteBoundFixtureTests.java',
    ];
  }, /must name one exact Java test method/);
  expectFiniteBoundInvalid('finite-values-not-array', finiteInventory, (value) => {
    value.bounds[0].values = {};
  }, /values must be a nonempty array/);
  expectFiniteBoundInvalid('finite-values-empty', finiteInventory, (value) => {
    value.bounds[0].values = [];
  }, /values must be a nonempty array/);
  expectFiniteBoundInvalid('finite-value-extra-key', finiteInventory, (value) => {
    value.bounds[0].values[0].extra = true;
  }, /values\[0\] keys must be exactly/);
  expectFiniteBoundInvalid('finite-value-key-malformed', finiteInventory, (value) => {
    value.bounds[0].values[0].key = 'SELF_TEST';
  }, /stable lowercase accounting key/);
  expectFiniteBoundInvalid('finite-value-unit-malformed', finiteInventory, (value) => {
    value.bounds[0].values[0].unit = 'items';
  }, /uppercase stable unit token/);
  expectFiniteBoundInvalid('finite-value-not-canonical-integer', finiteInventory, (value) => {
    value.bounds[0].values[0].value = '08';
  }, /canonical finite integer string/);
  expectFiniteBoundInvalid('finite-value-key-duplicate', finiteInventory, (value) => {
    value.bounds[0].values.push(clone(value.bounds[0].values[0]));
  }, /Finite-bound value key is duplicated/);
  expectFiniteBoundInvalid('finite-values-reordered', finiteInventory, (value) => {
    value.bounds[0].values.push({
      ...clone(value.bounds[0].values[0]),
      key: 'self-test.aaa-items',
    });
  }, /values must be in ASCII key order/);
  expectFiniteBoundInvalid('finite-required-category-missing', finiteInventory, (value) => {
    value.bounds[0].category = 'DIFFERENT';
  }, /Finite-bound categories must match the frozen order exactly/);

  const finiteInventoryWithExclusion = clone(finiteInventory);
  const [excludedOwner] = finiteInventoryWithExclusion.bounds[0].sourceOwners.splice(0, 1);
  finiteInventoryWithExclusion.reviewedExclusions = [
    reviewedExclusion(excludedOwner),
  ];
  const finiteExclusionPath = writeFiniteBoundFixture(
    'exact-reviewed-exclusion',
    finiteInventoryWithExclusion,
  );
  const finiteExclusionResult = verifyFiniteBoundFixture(
    finiteExclusionPath,
    finiteBoundSemanticsSha256(finiteInventoryWithExclusion.bounds),
    finiteBoundExclusionsSha256(
      finiteInventoryWithExclusion.reviewedExclusions,
    ),
  );
  const finiteTwoExclusionInventory = clone(finiteInventory);
  const excludedOwners = finiteTwoExclusionInventory.bounds[0]
    .sourceOwners.splice(0, 2);
  finiteTwoExclusionInventory.reviewedExclusions = excludedOwners
    .map((owner, index) => ({
      ...reviewedExclusion(owner, `FINITE-EX-${String(index + 1).padStart(3, '0')}`),
      rationale: `Reviewed self-test exclusion rationale ${index + 1}.`,
    }))
    .sort((left, right) => left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
  const finiteTwoExclusionPath = writeFiniteBoundFixture(
    'two-exclusion-attribution-baseline',
    finiteTwoExclusionInventory,
  );
  const finiteTwoExclusionSha256 = finiteBoundExclusionsSha256(
    finiteTwoExclusionInventory.reviewedExclusions,
  );
  verifyFiniteBoundFixture(
    finiteTwoExclusionPath,
    finiteBoundSemanticsSha256(finiteTwoExclusionInventory.bounds),
    finiteTwoExclusionSha256,
  );
  const finiteExclusionSwap = clone(finiteTwoExclusionInventory);
  [finiteExclusionSwap.reviewedExclusions[0].id,
    finiteExclusionSwap.reviewedExclusions[1].id] = [
    finiteExclusionSwap.reviewedExclusions[1].id,
    finiteExclusionSwap.reviewedExclusions[0].id,
  ];
  [finiteExclusionSwap.reviewedExclusions[0].rationale,
    finiteExclusionSwap.reviewedExclusions[1].rationale] = [
    finiteExclusionSwap.reviewedExclusions[1].rationale,
    finiteExclusionSwap.reviewedExclusions[0].rationale,
  ];
  const finiteExclusionSwapPath = writeFiniteBoundFixture(
    'balanced-cross-exclusion-attribution-swap',
    finiteExclusionSwap,
  );
  assert.throws(
    () => verifyFiniteBoundFixture(
      finiteExclusionSwapPath,
      finiteBoundSemanticsSha256(finiteTwoExclusionInventory.bounds),
      finiteTwoExclusionSha256,
    ),
    /Finite-bound exclusion attribution SHA-256 differs from the reviewed contract/,
    'balanced-cross-exclusion-attribution-swap',
  );
  assert.equal(finiteExclusionResult.exclusions.length, 1);
  assert.equal(finiteExclusionResult.exclusions[0].key, excludedOwner.key);
  assert.equal(finiteExclusionResult.exclusions[0].owner, excludedOwner.owner);
  assert.equal(finiteExclusionResult.exclusions[0].member, excludedOwner.member);
  expectFiniteBoundInvalid(
    'malformed-reviewed-exclusion-key',
    finiteInventoryWithExclusion,
    (value) => {
      value.reviewedExclusions[0].member = 'DIFFERENT_MEMBER';
    },
    /\.key must be exactly/,
  );
  expectFiniteBoundInvalid(
    'wildcard-reviewed-exclusion-member',
    finiteInventoryWithExclusion,
    (value) => {
      value.reviewedExclusions[0].member = '*';
    },
    /\.member must be one exact Java declaration name/,
  );
  expectFiniteBoundInvalid(
    'duplicate-reviewed-exclusion-id',
    finiteInventory,
    (value) => {
      const excluded = value.bounds[0].sourceOwners.splice(0, 2);
      value.reviewedExclusions = excluded
        .map((owner) => reviewedExclusion(owner, 'FINITE-EX-001'))
        .sort((left, right) => left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
    },
    /reviewedExclusions\[1\]\.id is malformed or duplicated/,
  );

  expectFiniteBoundInvalid('finite-schema-extra-key', finiteInventory, (value) => {
    value.extra = true;
  }, /Finite-bound inventory keys must be exactly/);
  expectFiniteBoundInvalid('finite-schema-version-drift', finiteInventory, (value) => {
    value.formatVersion = 2;
  }, /format, profile, or release target is invalid/);
  expectFiniteBoundInvalid('finite-failure-contract-blank', finiteInventory, (value) => {
    value.bounds[0].deterministicFailure.contract = '';
  }, /deterministicFailure\.contract must be a nonblank/);
  expectFiniteBoundInvalid('finite-failure-stage-blank', finiteInventory, (value) => {
    value.bounds[0].deterministicFailure.stage = '';
  }, /deterministicFailure\.stage must be a nonblank/);
  expectFiniteBoundInvalid('finite-matcher-description-drift', finiteInventory, (value) => {
    value.matcherRules[0].description = 'Broadened prose matcher.';
  }, /matcherRules do not match the executable matcher contract/);
  expectFiniteBoundInvalid('finite-matcher-order-drift', finiteInventory, (value) => {
    value.matcherRules.reverse();
  }, /matcherRules do not match the executable matcher contract/);
  expectFiniteBoundInvalid('finite-scan-roots-narrowed', finiteInventory, (value) => {
    value.scanRoots = value.scanRoots.slice(1);
  }, /Finite-bound scanRoots must match the frozen order exactly/);
  expectFiniteBoundInvalid('finite-scan-roots-extra', finiteInventory, (value) => {
    value.scanRoots.push('src/main/java/com/soklet/McpFiniteBound*.java');
    value.scanRoots.sort();
  }, /Finite-bound scanRoots must match the frozen order exactly/);
  expectFiniteBoundInvalid('finite-scan-roots-reordered', finiteInventory, (value) => {
    value.scanRoots.reverse();
  }, /Finite-bound scanRoots must match the frozen order exactly/);

  prepareSemanticGit(privacyInventoryPath);
  mkdirSync(dirname(privacyInventoryPath), { recursive: true });
  const privacySourceDirectory = join(
    privacyProjectRoot,
    'src/main/java/com/soklet',
  );
  const privacyInternalSourceDirectory = join(
    privacyProjectRoot,
    'src/main/java/com/soklet/internal/mcp/protocol',
  );
  const privacyMicrohttpSourceDirectory = join(
    privacyProjectRoot,
    'src/main/java/com/soklet/internal/microhttp',
  );
  const privacyExceptionSourceDirectory = join(
    privacyProjectRoot,
    'src/main/java/com/soklet/exception',
  );
  const privacyTestDirectory = join(
    privacyProjectRoot,
    'src/test/java/com/soklet',
  );
  const privacyFuzzTestDirectory = join(
    privacyProjectRoot,
    'fuzz/src/test/java/com/soklet',
  );
  const privacyArtifactDirectory = join(
    privacyProjectRoot,
    'conformance/privacy-self-test-recursive',
  );
  const privacyFinalSchemaArtifactDirectory = join(
    privacyProjectRoot,
    'conformance/official/final-schema',
  );
  const privacyFuzzArtifactDirectory = join(
    privacyProjectRoot,
    'fuzz/src/test/resources/com/soklet/QueryFormatFuzzTestInputs',
  );
  const privacyMicrohttpFuzzArtifactDirectory = join(
    privacyProjectRoot,
    'fuzz/src/test/resources/com/soklet/internal/microhttp/RequestParserFuzzTestInputs',
  );
  const privacySchemaArtifactDirectory = join(
    privacyProjectRoot,
    'src/test/resources/com/soklet/internal/mcp/schema/profile',
  );
  const privacyExactArtifactPath = join(
    privacyProjectRoot,
    'conformance/privacy-self-test-exact.golden',
  );
  const privacyMultipartArtifactPath = join(
    privacyProjectRoot,
    'src/test/resources/multipart-request-body',
  );
  mkdirSync(privacySourceDirectory, { recursive: true });
  mkdirSync(privacyInternalSourceDirectory, { recursive: true });
  mkdirSync(privacyMicrohttpSourceDirectory, { recursive: true });
  mkdirSync(privacyExceptionSourceDirectory, { recursive: true });
  mkdirSync(privacyTestDirectory, { recursive: true });
  mkdirSync(privacyFuzzTestDirectory, { recursive: true });
  mkdirSync(privacyArtifactDirectory, { recursive: true });
  mkdirSync(privacyFinalSchemaArtifactDirectory, { recursive: true });
  mkdirSync(privacyFuzzArtifactDirectory, { recursive: true });
  mkdirSync(privacyMicrohttpFuzzArtifactDirectory, { recursive: true });
  mkdirSync(privacySchemaArtifactDirectory, { recursive: true });
  mkdirSync(dirname(privacyMultipartArtifactPath), { recursive: true });
  assert.deepEqual(PRIVACY_SCAN_ROOTS, [
    'src/main/java/com/soklet/**/*.java',
  ]);
  writeFileSync(
    join(privacySourceDirectory, 'Request.java'),
    `package com.soklet;

public class Request {
  protected Request() {}
  public String getUrl() { return "exact"; }
  public Object getHeaders() { return null; }

  public static final class Builder {
    public Builder rawPath(String rawPath) { return this; }
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpRequestContext.java'),
    `package com.soklet;

public interface McpRequestContext {
  Request getRequest();
  String getOperationName();
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpRequestStateProtectionContext.java'),
    `package com.soklet;

public final class McpRequestStateProtectionContext {
  public McpRequestStateProtectionContext(String associatedData) {}
  public String getAssociatedData() { return "exact"; }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpPromptGetContext.java'),
    `package com.soklet;

public interface McpPromptGetContext {
  String getPromptName();
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpInvocationFeatures.java'),
    `package com.soklet;

public interface McpInvocationFeatures {
  String getSelectedLocale();
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpToolArguments.java'),
    `package com.soklet;

public interface McpToolArguments<T> {
  T getArguments();
}

final class DefaultToolArguments implements McpToolArguments<String> {
  public String getArguments() { return "exact"; }
  public String getRawSecret() { return "exact"; }

  @Override
  public String toString() { return "DefaultToolArguments{<redacted>}"; }
}

interface SyntheticToolHandler {
  Object handle(McpToolArguments<String> arguments);
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'CancelationToken.java'),
    `package com.soklet;

import java.util.Optional;

interface CancelationToken {
  Optional<Throwable> getCancelationCause();

  Optional<RuntimeException> terminalFailure();
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'CorsAuthorizer.java'),
    `package com.soklet;

interface CorsAuthorizer {
  Object authorize(Request request);
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'MetricsCollector.java'),
    `package com.soklet;

interface MetricsCollector {
  default void collectSecret(Request request, Throwable throwable) {}

  default Object snapshot() {
    return null;
  }

  final class Snapshot {
    public final String exposedSecret = "exact";

    public String getSecret() { return "exact"; }

    public static final class Builder {
      public Builder secret(String value) { return this; }
    }
  }
}

final class DefaultMetricsCollector implements MetricsCollector {
  @Override
  public void collectSecret(Request request, Throwable throwable) {}
}

final class GenericMetricsCaller {
  void emit(MetricsCollector metricsCollector, Request request,
      Throwable throwable) {
    metricsCollector.collectSecret(request, throwable);
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpMetricsEvent.java'),
    `package com.soklet;

abstract class McpMetricsEvent {
  public static McpMetricsEvent requestAccepted() { return null; }

  public static McpMetricsEvent sensitiveEvent(String secret) { return null; }

  public static final class SensitiveEvent extends McpMetricsEvent {
    public final String exposedSecret = "exact";

    public String getSecret() { return "exact"; }
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpMetricsSnapshot.java'),
    `package com.soklet;

final class McpMetricsSnapshot {
  public String getSecret() { return "exact"; }

  public static final class Builder {
    public Builder secret(String value) { return this; }
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpJsonRpcError.java'),
    `package com.soklet;

public final class McpJsonRpcError {
  public static final int APPLICATION_CODE = 7;

  private McpJsonRpcError(int code, String message, Object data) {}

  public static McpJsonRpcError fromApplication(Integer code, String message) {
    return new McpJsonRpcError(code, message, null);
  }

  public Integer getCode() { return 7; }
  public String getMessage() { return "exact"; }
  public Object getData() { return null; }
}

final class McpJsonRpcException extends RuntimeException {
  public McpJsonRpcException(McpJsonRpcError error) {}
  public McpJsonRpcError getError() { return null; }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'LogEvent.java'),
    `package com.soklet;

final class LogEvent {
  public static Builder with(LogEventType type, String message) { return null; }

  public String getMessage() { return null; }
  public ResourceMethod getResourceMethod() { return null; }
  public MarshaledResponse getMarshaledResponse() { return null; }
  public String getResponse() { return null; }
  public Copier copy() { return null; }

  static final class Builder {
    public Builder message(String message) { return this; }
    public Builder resourceMethod(ResourceMethod method) { return this; }
    public Builder marshaledResponse(MarshaledResponse response) { return this; }
    public Builder request(Request request) { return this; }
    public Builder response(String response) { return this; }
    public Builder throwable(Throwable throwable) { return this; }
    public LogEvent build() { return null; }
  }

  static final class Copier {
    public Copier message(String message) { return this; }
    public Copier resourceMethod(ResourceMethod method) { return this; }
    public Copier marshaledResponse(MarshaledResponse response) { return this; }
    public Copier request(Request request) { return this; }
    public Copier response(String response) { return this; }
    public Copier throwable(Throwable throwable) { return this; }
    public LogEvent finish() { return null; }
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'HttpRequestResult.java'),
    `package com.soklet;

public final class HttpRequestResult {
  public HttpRequestResult(String body) {}

  public String getBody() { return "exact"; }

  @Override
  public String toString() { return "HttpRequestResult{body=exact}"; }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'ResponseLike.java'),
    `package com.soklet;

final class ResponseLike {
  @Override
  public String toString() { return "ResponseLike{secret=exact}"; }
}

record SecretCarrier(String value) {}

record GenericSecret<T>(T value) {}

record Allowed() {}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'SyntheticDiagnostic.java'),
    `package com.soklet;

final class SyntheticDiagnostic {
  String summary() { return "exact"; }
  private String render() { return "exact"; }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'DefaultMultipartParser.java'),
    `package com.soklet;

final class DefaultMultipartParser {
  void rejectBody() {
    throw new IllegalRequestBodyException("fixed");
  }

  void rejectRequest() {
    throw new IllegalRequestException("fixed");
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpPrivacyFixture.java'),
    `package com.soklet;

import com.soklet.exception.IllegalRequestException;
import java.util.List;

import static com.soklet.LogEvent.with;
import static com.soklet.McpMetricsEvent.sensitiveEvent;

final class McpPrivacyFixture {
  public Request exposedRequest;
  protected LimitSignal exposedFailure;
  public Request firstRequest, secondRequest;
  protected LimitSignal firstFailure, secondFailure;

  Request getRequest() {
    return null;
  }

  List<Throwable> getThrowables() {
    return List.of();
  }

  void exercise(Request request, Throwable throwable,
      LifecycleObserver observer, McpRequestContext context) {
    observer.didReceiveLogEvent(LogEvent.with(
        LogEventType.MCP_SERVER_CONFIGURATION, "fixed")
        .request(request).throwable(throwable)
        .marshaledResponse(null).response("secret").build());
    observer.didReceiveLogEvent(LogEvent.with(
        LogEventType.MCP_SERVER_CONFIGURATION, "fixed again").build());
    McpMetricsEvent.requestAccepted();
    sensitiveEvent("secret");
    with(LogEventType.MCP_SERVER_CONFIGURATION, "static import").build();
    Object logFactory = LogEvent::with;
    Object metricFactory = McpMetricsEvent::sensitiveEvent;
    observer.didStartMcpRequestHandling(context);
    throw new IllegalRequestException("fixed");
  }

  LogEvent staticAttachments(Request request) {
    return with(LogEventType.MCP_SERVER_CONFIGURATION, "static chain")
        .request(request).response("secret").build();
  }

  void variableAttachments(Request request, LogEvent event) {
    LogEvent.Builder builder = LogEvent.with(
        LogEventType.MCP_SERVER_CONFIGURATION, "variable");
    builder.request(request);
    builder.response("secret");
    LogEvent.Copier copier = event.copy();
    copier.message("secret");
    event.copy().resourceMethod(null).finish();
    var inferredCopier = event.copy();
    inferredCopier.response("secret");
  }

  Object wireError() {
    return new McpJsonRpcError(-1, "fixed", null);
  }

  Object publishWireError(McpJsonRpcException exception,
      McpJsonRpcError error) {
    exception.getError();
    error.getCode();
    error.getMessage();
    return error.getData();
  }

  Object[] errorDtos() {
    return new Object[] {
        new JsonRpcError(),
        new ProtocolError(),
        new RequestError(),
        new com.soklet.internal.mcp.protocol.McpJsonRpcError()
    };
  }

  public String toString() {
    return "McpPrivacyFixture{}";
  }

  static final class NestedCarrierException extends RuntimeException {
    public NestedCarrierException(String exactValue) {}

    public String exactValue() {
      return "exact";
    }

    private String privateValue() {
      return "private";
    }
  }

  public record PublicRequestCarrier(Request request, String label) {
    @Override
    public String toString() { return "PublicRequestCarrier{<redacted>}"; }
  }

  private record PrivateThrowableCarrier(Throwable throwable) {}
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'McpSimulationResponse.java'),
    `package com.soklet;

final class McpSimulationResponse {
  public String getBody() { return "exact"; }
  public String getSecret() { return "exact"; }
}
`,
  );
  writeFileSync(
    join(privacyInternalSourceDirectory, 'McpSimulationRuntime.java'),
    `package com.soklet.internal.mcp.protocol;

final class McpSimulationRuntime {
  Object capture() {
    return new DefaultResponse();
  }

  public byte[] getEncodedBytes() {
    return new byte[0];
  }

  public String getSecret() { return "exact"; }

  Object logger() {
    return NoopLogger.instance();
  }

  Object eventLoop() {
    return new EventLoop(options, NoopLogger.instance(), handler);
  }
}
`,
  );
  writeFileSync(
    join(privacyInternalSourceDirectory, 'McpJsonRpcError.java'),
    `package com.soklet.internal.mcp.protocol;

record McpJsonRpcError(int code, String message, Object data) {
  Object toJsonObject() { return null; }
}
`,
  );
  writeFileSync(
    join(privacyMicrohttpSourceDirectory, 'Logger.java'),
    `package com.soklet.internal.microhttp;

interface Logger {
  void log(Object... entries);
  void logFailure(Throwable throwable, Object... entries);
  default void audit(Object... entries) {}
}
`,
  );
  writeFileSync(
    join(privacyMicrohttpSourceDirectory, 'EventLoop.java'),
    `package com.soklet.internal.microhttp;

final class EventLoop {
  EventLoop(Object options, Object handler) {
    this(options, NoopLogger.instance(), handler);
  }

  EventLoop(Object options, Logger logger, Object handler) {}
}
`,
  );
  writeFileSync(
    join(privacyMicrohttpSourceDirectory, 'ConnectionEventLoop.java'),
    `package com.soklet.internal.microhttp;

final class ConnectionEventLoop {
  Logger logger;
  Logger audit;

  void emit(Exception exception) {
    logger.log();
    logger.logFailure(exception);
    audit.log(exception);
    obtainLogger().log(exception);
    obtainLogger().audit(exception);
  }
}

final class DebugLogger {
  void emit(Exception exception) {
    System.out.println("debug");
    exception.printStackTrace();
  }
}
`,
  );
  writeFileSync(
    join(privacyExceptionSourceDirectory, 'SyntheticPrivacyException.java'),
    `package com.soklet.exception;

public final class SyntheticPrivacyException extends RuntimeException {
  public SyntheticPrivacyException(String message) {}

  protected SyntheticPrivacyException(String message, Throwable cause) {}

  private SyntheticPrivacyException() {}

  public String getExactValue() {
    return "exact";
  }

  private String getPrivateValue() {
    return "private";
  }

  public String helper() {
    return "not a getter";
  }

  protected String renderExactValue() {
    return "exact";
  }

  @Override
  public String toString() {
    return "SyntheticPrivacyException{<redacted>}";
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'Utilities.java'),
    `package com.soklet;

final class Utilities {
  void rejectMissingHeader() {
    throw new MissingRequestHeaderException("fixed");
  }

  void rejectMultipleValues() {
    throw new MultipleValuesException("fixed");
  }

  void rejectMissingFormParameter() {
    throw new MissingFormParameterException("fixed");
  }

  void rejectMissingMultipartField() {
    throw new MissingMultipartFieldException("fixed");
  }

  void rejectMissingQueryParameter() {
    throw new MissingQueryParameterException("fixed");
  }

  void rejectMissingRequestBody() {
    throw new MissingRequestBodyException("fixed");
  }

  void rejectMissingRequestCookie() {
    throw new MissingRequestCookieException("fixed");
  }

  void failInvariant() {
    throw new AssertionError("fixed");
  }

  void failInitialization() {
    throw new ExceptionInInitializerError("fixed");
  }

  void failWithLocalSubtype(String exactValue) {
    throw new LimitSignal(exactValue);
  }

  void writeDirectly(PrintStream stream) {
    stream.write(1);
    Object typedReference = stream::println;
  }

  void writeThroughAlias() {
    var sink = System.err;
    sink.println("secret");
    Object directReference = System.err::println;
    Object aliasReference = sink::println;
  }
}

final class LimitSignal extends RuntimeException {
  public LimitSignal(String message) {}

  public String getSecret() { return "exact"; }
}

final class SyntheticPrivacyError extends Error {
  public SyntheticPrivacyError(String message) {}

  protected String getSecret() { return "exact"; }
}

record PrivateFailureCarrier(LimitSignal failure) {}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'SokletApplicationTerminalReporter.java'),
    `package com.soklet;

final class SokletApplicationTerminalReporter {
  OutputStream errorStream;

  void report() {
    this.errorStream.write(1);
  }

  void warn() {
    System.err.printf("fixed%n");
  }
}
`,
  );
  writeFileSync(
    join(privacySourceDirectory, 'OutsidePrivacyFixture.java'),
    `package com.soklet;

final class OutsidePrivacyFixture {
  void outOfScope() {
    throw new MissingRequestHeaderException("must remain out of scope");
  }
}
`,
  );
  writeFileSync(
    join(privacyTestDirectory, 'McpPrivacyBoundaryFixtureTests.java'),
    `package com.soklet;

final class McpPrivacyBoundaryFixtureTests {
  void canary() {}
}
`,
  );
  writeFileSync(
    join(privacyFuzzTestDirectory, 'McpPrivacyBoundaryFuzzTests.java'),
    `package com.soklet;

final class McpPrivacyBoundaryFuzzTests {
  void fuzzCanary() {}
}
`,
  );
  const privacyArtifactPath = join(
    privacyArtifactDirectory,
    'redacted.golden',
  );
  writeFileSync(privacyExactArtifactPath, 'exact tracked fixture\n');
  writeFileSync(privacyMultipartArtifactPath, 'raw multipart request\n');
  writeFileSync(
    join(privacyFinalSchemaArtifactDirectory, 'LICENSE.upstream'),
    'synthetic license\n',
  );
  writeFileSync(
    join(privacyFinalSchemaArtifactDirectory, 'schema.json'),
    '{"type":"object"}\n',
  );
  writeFileSync(privacyArtifactPath, 'redacted fixture\n');
  writeFileSync(
    join(privacyArtifactDirectory, 'retained.golden'),
    'retained fixture\n',
  );
  writeFileSync(
    join(privacyFuzzArtifactDirectory, 'raw.query'),
    'raw query fixture\n',
  );
  writeFileSync(
    join(privacyMicrohttpFuzzArtifactDirectory, 'raw.http'),
    'raw request fixture\n',
  );
  writeFileSync(
    join(privacySchemaArtifactDirectory, 'schema.json'),
    '{"type":"object"}\n',
  );
  const privacyCandidates = derivePrivacyBoundaryCandidates(
    privacyProjectRoot,
    privacyScanRoots,
    privacyArtifactRoots,
    semanticGitExecutable(),
  );
  assert.deepEqual(
    Object.fromEntries(PRIVACY_MATCHER_RULES.map(({ id }) => [
      id,
      privacyCandidates.filter(({ matcherRuleId }) => matcherRuleId === id).length,
    ])),
    {
      'PRIV-MATCH-001': 6,
      'PRIV-MATCH-002': 9,
      'PRIV-MATCH-003': 3,
      'PRIV-MATCH-004': 1,
      'PRIV-MATCH-005': 52,
      'PRIV-MATCH-006': 11,
      'PRIV-MATCH-007': 10,
      'PRIV-MATCH-008': 24,
      'PRIV-MATCH-009': 20,
      'PRIV-MATCH-010': 9,
      'PRIV-MATCH-011': 14,
      'PRIV-MATCH-012': 14,
      'PRIV-MATCH-013': 12,
      'PRIV-MATCH-014': 9,
      'PRIV-MATCH-015': 7,
      'PRIV-MATCH-016': 36,
    },
  );
  assert.equal(
    privacyCandidates.some(({ file }) =>
      file.endsWith('/OutsidePrivacyFixture.java')),
    true,
  );
  assert.deepEqual(
    [...new Set(privacyCandidates
      .filter(({ matcherRuleId }) => matcherRuleId === 'PRIV-MATCH-006')
      .map(({ sink }) => sink))].sort(),
    [
      'RequestException.IllegalRequestBodyException',
      'RequestException.IllegalRequestException',
      'RequestException.MissingFormParameterException',
      'RequestException.MissingMultipartFieldException',
      'RequestException.MissingQueryParameterException',
      'RequestException.MissingRequestBodyException',
      'RequestException.MissingRequestCookieException',
      'RequestException.MissingRequestHeaderException',
      'RequestException.MultipleValuesException',
    ],
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId }) => matcherRuleId === 'PRIV-MATCH-012')
      .map(({ member, sink }) => `${member}->${sink}`)
      .sort(),
    [
      'LimitSignal(String)->ExceptionCarrier.constructor',
      'McpJsonRpcException(McpJsonRpcError)->ExceptionCarrier.constructor',
      'NestedCarrierException(String)->ExceptionCarrier.constructor',
      'SyntheticPrivacyError(String)->ExceptionCarrier.constructor',
      'SyntheticPrivacyException(String)->ExceptionCarrier.constructor',
      'SyntheticPrivacyException(String,Throwable)->ExceptionCarrier.constructor',
      'exactValue()->ExceptionCarrier.publicOrProtectedMethod',
      'getError()->ExceptionCarrier.publicOrProtectedMethod',
      'getExactValue()->ExceptionCarrier.publicOrProtectedMethod',
      'getSecret()->ExceptionCarrier.publicOrProtectedMethod',
      'getSecret()->ExceptionCarrier.publicOrProtectedMethod',
      'helper()->ExceptionCarrier.publicOrProtectedMethod',
      'renderExactValue()->ExceptionCarrier.publicOrProtectedMethod',
      'toString()->ExceptionCarrier.diagnosticRenderer',
    ],
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && sink.startsWith('RequestOrThrowable.record'))
      .map(({ member, owner, sink }) =>
        `${owner.slice(owner.lastIndexOf('.') + 1)}#${member}->${sink}`)
      .sort(),
    [
      'PrivateFailureCarrier#PrivateFailureCarrier(LimitSignal)->RequestOrThrowable.recordConstructor',
      'PrivateFailureCarrier#failure()->RequestOrThrowable.recordAccessor',
      'PrivateFailureCarrier#toString()->RequestOrThrowable.recordRenderer',
      'PrivateThrowableCarrier#PrivateThrowableCarrier(Throwable)->RequestOrThrowable.recordConstructor',
      'PrivateThrowableCarrier#throwable()->RequestOrThrowable.recordAccessor',
      'PrivateThrowableCarrier#toString()->RequestOrThrowable.recordRenderer',
      'PublicRequestCarrier#PublicRequestCarrier(Request,String)->RequestOrThrowable.recordConstructor',
      'PublicRequestCarrier#request()->RequestOrThrowable.recordAccessor',
    ],
  );
  assert.equal(
    privacyCandidates.some(({ matcherRuleId, member }) =>
      matcherRuleId === 'PRIV-MATCH-012'
        && (member === 'SyntheticPrivacyException()'
          || member === 'getPrivateValue()'
          || member === 'privateValue()')),
    false,
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && sink === 'RequestOrThrowable.declaration')
      .map(({ member }) => member)
      .sort(),
    [
      'SyntheticPrivacyException(String,Throwable)',
      'authorize(Request)',
      'collectSecret(Request,Throwable)',
      'collectSecret(Request,Throwable)',
      'getCancelationCause()',
      'getRequest()',
      'logFailure(Throwable,Object...)',
      'request(Request)',
      'request(Request)',
      'terminalFailure()',
      'throwable(Throwable)',
      'throwable(Throwable)',
    ],
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId }) => matcherRuleId === 'PRIV-MATCH-013')
      .map(({ sink }) => sink)
      .sort(),
    [
      'MicrohttpLogger.EventLoopDefaultWiring:DEFAULT_NOOP',
      'MicrohttpLogger.McpEventLoopWiring:EXPLICIT_NOOP',
      'MicrohttpLogger.NoopLogger',
      'MicrohttpLogger.NoopLogger',
      'MicrohttpLogger.invocation.audit',
      'MicrohttpLogger.invocation.log',
      'MicrohttpLogger.invocation.log',
      'MicrohttpLogger.invocation.log',
      'MicrohttpLogger.invocation.logFailure',
      'MicrohttpLogger.surface.audit',
      'MicrohttpLogger.surface.log',
      'MicrohttpLogger.surface.logFailure',
    ],
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId }) => matcherRuleId === 'PRIV-MATCH-014')
      .map(({ sink }) => sink)
      .sort(),
    [
      'DirectOutput.OutputStream.write',
      'DirectOutput.PrintStream.println',
      'DirectOutput.PrintStream.write',
      'DirectOutput.System.err.printf',
      'DirectOutput.System.err.println',
      'DirectOutput.System.out.println',
      'DirectOutput.SystemErrAlias.println',
      'DirectOutput.SystemErrAlias.println',
      'DirectOutput.Throwable.printStackTrace',
    ],
  );
  assert.equal(
    privacyCandidates.some(({ matcherRuleId, member, owner, sink }) =>
      matcherRuleId === 'PRIV-MATCH-007'
        && owner === 'com.soklet.HttpRequestResult'
        && member === 'getBody()'
        && sink === 'SimulationCapture.publicOrProtectedSurface'),
    true,
  );
  for (const [owner, member] of [
    ['com.soklet.McpMetricsEvent', 'sensitiveEvent(String)'],
    ['com.soklet.McpMetricsEvent.SensitiveEvent', 'getSecret()'],
    ['com.soklet.McpMetricsSnapshot', 'getSecret()'],
    ['com.soklet.McpMetricsSnapshot.Builder', 'secret(String)'],
  ]) {
    assert.equal(
      privacyCandidates.some(({ matcherRuleId, member: candidateMember,
        owner: candidateOwner }) =>
        matcherRuleId === 'PRIV-MATCH-002'
          && candidateOwner === owner && candidateMember === member),
      true,
      `${owner}#${member}`,
    );
  }
  for (const [owner, member, sink] of [
    ['com.soklet.Allowed', 'Allowed()', 'Diagnostic.recordConstructor'],
    ['com.soklet.Allowed', 'toString()', 'Diagnostic.recordRenderer'],
    ['com.soklet.GenericSecret', 'GenericSecret(T)', 'Diagnostic.recordConstructor'],
    ['com.soklet.GenericSecret', 'value()', 'Diagnostic.recordAccessor'],
    ['com.soklet.DefaultToolArguments', 'toString()', 'Diagnostic.toString'],
    ['com.soklet.McpPrivacyFixture.PublicRequestCarrier', 'toString()', 'Diagnostic.toString'],
    ['com.soklet.SecretCarrier', 'SecretCarrier(String)', 'Diagnostic.recordConstructor'],
    ['com.soklet.SecretCarrier', 'value()', 'Diagnostic.recordAccessor'],
    ['com.soklet.ResponseLike', 'toString()', 'Diagnostic.toString'],
  ]) {
    assert.equal(
      privacyCandidates.some((candidate) =>
        candidate.matcherRuleId === 'PRIV-MATCH-008'
          && candidate.owner === owner
          && candidate.member === member
          && candidate.sink === sink),
      true,
      `${owner}#${member}->${sink}`,
    );
  }
  for (const owner of [
    'com.soklet.DefaultToolArguments',
    'com.soklet.McpPrivacyFixture.PublicRequestCarrier',
  ]) {
    assert.deepEqual(
      privacyCandidates
        .filter(({ member, owner: candidateOwner }) =>
          candidateOwner === owner && member === 'toString()')
        .map(({ matcherRuleId, sink }) => `${matcherRuleId}:${sink}`),
      ['PRIV-MATCH-008:Diagnostic.toString'],
      `${owner} explicit renderer must have one diagnostic classification`,
    );
  }
  assert.deepEqual(
    privacyCandidates
      .filter(({ member, owner }) =>
        owner === 'com.soklet.McpPrivacyFixture.PrivateThrowableCarrier'
          && member === 'toString()')
      .map(({ matcherRuleId, sink }) => `${matcherRuleId}:${sink}`),
    ['PRIV-MATCH-005:RequestOrThrowable.recordRenderer'],
    'implicit Throwable record renderer must have one exact carrier classification',
  );
  for (const [owner, member, expectedSink] of [
    [
      'com.soklet.McpPrivacyFixture.PrivateThrowableCarrier',
      'PrivateThrowableCarrier(Throwable)',
      'RequestOrThrowable.recordConstructor',
    ],
    [
      'com.soklet.McpPrivacyFixture.PrivateThrowableCarrier',
      'throwable()',
      'RequestOrThrowable.recordAccessor',
    ],
    [
      'com.soklet.McpPrivacyFixture.PublicRequestCarrier',
      'PublicRequestCarrier(Request,String)',
      'RequestOrThrowable.recordConstructor',
    ],
    [
      'com.soklet.McpPrivacyFixture.PublicRequestCarrier',
      'request()',
      'RequestOrThrowable.recordAccessor',
    ],
  ]) {
    assert.deepEqual(
      privacyCandidates
        .filter(({ member: candidateMember, owner: candidateOwner }) =>
          candidateOwner === owner && candidateMember === member)
        .map(({ matcherRuleId, sink }) => `${matcherRuleId}:${sink}`),
      [`PRIV-MATCH-005:${expectedSink}`],
      `${owner}#${member} must have one exact carrier classification`,
    );
  }
  assert.deepEqual(
    privacyCandidates
      .filter(({ member, owner }) =>
        owner === 'com.soklet.McpPrivacyFixture.PublicRequestCarrier'
          && member === 'label()')
      .map(({ matcherRuleId, sink }) => `${matcherRuleId}:${sink}`),
    ['PRIV-MATCH-008:Diagnostic.recordAccessor'],
    'noncarrier components remain in the diagnostic record census',
  );
  for (const sink of [
    'MetricsCollector.invocation.collectSecret',
    'MetricsCollector.override.collectSecret',
    'MetricsCollector.surface.collectSecret',
    'MetricsCollector.surface.getSecret',
    'MetricsCollector.surface.secret',
    'MetricsCollector.surface.snapshot',
  ]) {
    assert.equal(
      privacyCandidates.some(({ matcherRuleId, sink: candidateSink }) =>
        matcherRuleId === 'PRIV-MATCH-015' && candidateSink === sink),
      true,
      sink,
    );
  }
  assert.equal(
    privacyCandidates.some(({ matcherRuleId, member, sink }) =>
      matcherRuleId === 'PRIV-MATCH-016'
        && member.startsWith('exercise(')
        && sink === 'LogEvent.attachment.marshaledResponse'),
    true,
  );
  assert.equal(
    privacyCandidates.some(({ matcherRuleId, sink }) =>
      matcherRuleId === 'PRIV-MATCH-011'
        && /(?:JsonRpcError|ProtocolError|RequestError)$/u.test(sink)),
    false,
  );
  assert.deepEqual(
    [...new Set(privacyCandidates
      .filter(({ matcherRuleId }) => matcherRuleId === 'PRIV-MATCH-011')
      .map(({ sink }) => sink))].sort(),
    [
      'Throwable.AssertionError',
      'Throwable.ExceptionInInitializerError',
      'Throwable.IllegalRequestBodyException',
      'Throwable.IllegalRequestException',
      'Throwable.LimitSignal',
      'Throwable.MissingFormParameterException',
      'Throwable.MissingMultipartFieldException',
      'Throwable.MissingQueryParameterException',
      'Throwable.MissingRequestBodyException',
      'Throwable.MissingRequestCookieException',
      'Throwable.MissingRequestHeaderException',
      'Throwable.MultipleValuesException',
    ],
  );
  assert.deepEqual(
    privacyCandidates
      .filter(({ matcherRuleId, member, sink }) =>
        matcherRuleId === 'PRIV-MATCH-001'
          && member.startsWith('exercise(')
          && sink === 'LogEvent.with:MCP_SERVER_CONFIGURATION')
      .map(({ occurrence }) => occurrence),
    [1, 2, 3],
  );
  for (const [label, predicate] of [
    [
      'qualified LogEvent method reference',
      ({ matcherRuleId, member, sink }) =>
        matcherRuleId === 'PRIV-MATCH-001'
          && member.startsWith('exercise(')
          && sink === 'LogEvent.with:DYNAMIC',
    ],
    [
      'qualified MCP metric factory method reference',
      ({ matcherRuleId, member, occurrence, sink }) =>
        matcherRuleId === 'PRIV-MATCH-002'
          && member.startsWith('exercise(')
          && occurrence === 1
          && sink === 'McpMetricsEvent.sensitiveEvent',
    ],
    [
      'same-statement statically imported LogEvent chain',
      ({ matcherRuleId, member, sink }) =>
        matcherRuleId === 'PRIV-MATCH-003'
          && member.startsWith('staticAttachments(')
          && sink === 'LogEvent.Builder.request',
    ],
    [
      'inferred LogEvent copier receiver',
      ({ matcherRuleId, member, occurrence, sink }) =>
        matcherRuleId === 'PRIV-MATCH-016'
          && member.startsWith('variableAttachments(')
          && occurrence === 2
          && sink === 'LogEvent.attachment.response',
    ],
    [
      'comma-declared Request carrier field',
      ({ matcherRuleId, member, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && member === 'secondRequest'
          && sink === 'RequestOrThrowable.field',
    ],
    [
      'comma-declared Throwable carrier field',
      ({ matcherRuleId, member, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && member === 'secondFailure'
          && sink === 'RequestOrThrowable.field',
    ],
    [
      'MCP EventLoop logger wiring',
      ({ matcherRuleId, sink }) =>
        matcherRuleId === 'PRIV-MATCH-013'
          && sink === 'MicrohttpLogger.McpEventLoopWiring:EXPLICIT_NOOP',
    ],
    [
      'Request exact visible surface',
      ({ matcherRuleId, member, owner, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && owner === 'com.soklet.Request'
          && member === 'getHeaders()'
          && sink === 'ApplicationRequestCarrier.surface.getHeaders',
    ],
    [
      'MCP state-protection exact visible surface',
      ({ matcherRuleId, member, owner, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && owner === 'com.soklet.McpRequestStateProtectionContext'
          && member === 'getAssociatedData()'
          && sink === 'ApplicationRequestCarrier.surface.getAssociatedData',
    ],
    [
      'transitive tool-argument implementation surface',
      ({ matcherRuleId, member, owner, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && owner === 'com.soklet.DefaultToolArguments'
          && member === 'getRawSecret()'
          && sink === 'ApplicationRequestCarrier.surface.getRawSecret',
    ],
    [
      'application-carrier callback signature',
      ({ matcherRuleId, member, owner, sink }) =>
        matcherRuleId === 'PRIV-MATCH-005'
          && owner === 'com.soklet.SyntheticToolHandler'
          && member === 'handle(McpToolArguments<String>)'
          && sink === 'ApplicationRequestCarrier.declaration',
    ],
  ]) {
    assert.equal(privacyCandidates.some(predicate), true, label);
  }
  const privacyInventory = privacyFixture(privacyCandidates);
  privacyFixtureSemanticsSha256 = privacySemanticsSha256(privacyInventory);
  writeFileSync(privacyInventoryPath, canonicalJson(privacyInventory));
  const privacyBaseline = verifyPrivacyFixture(privacyInventoryPath);
  assert.deepEqual(privacyBaseline.candidates, privacyCandidates);
  assert.deepEqual(privacyBaseline.exclusions, []);

  expectPrivacyInvalid(
    'balanced-boundary-prose-attribution-swap',
    privacyInventory,
    (value) => {
      const first = value.boundaries[0];
      const second = value.boundaries[1];
      for (const field of ['contract', 'name']) {
        [first[field], second[field]] = [second[field], first[field]];
      }
    },
    /Privacy-boundary semantic attribution SHA-256 differs from the reviewed contract/,
  );

  expectPrivacyInvalid(
    'privacy-conflicting-concrete-renderer-classification',
    privacyInventory,
    (value) => {
      const boundary = value.boundaries.find(({ sourcePaths }) =>
        sourcePaths.some(({ matcherRuleId, member, owner }) =>
          matcherRuleId === 'PRIV-MATCH-012'
            && owner.endsWith('.SyntheticPrivacyException')
            && member === 'toString()'));
      assert.ok(boundary);
      boundary.classification = 'EXACT_APPLICATION_BOUNDARY';
    },
    /concrete renderer has conflicting classifications/,
  );

  for (const { id } of PRIVACY_MATCHER_RULES) {
    expectPrivacyInvalid(
      `omitted-${id.toLowerCase()}`,
      privacyInventory,
      (value) => {
        const boundaryIndex = value.boundaries.findIndex(({ sourcePaths }) =>
          sourcePaths.some(({ matcherRuleId }) => matcherRuleId === id));
        assert.notEqual(boundaryIndex, -1);
        const sourcePaths = value.boundaries[boundaryIndex].sourcePaths;
        const sourceIndex = sourcePaths.findIndex(
          ({ matcherRuleId }) => matcherRuleId === id,
        );
        sourcePaths.splice(sourceIndex, 1);
        if (sourcePaths.length === 0) value.boundaries.splice(boundaryIndex, 1);
      },
      new RegExp(`omitted=\\[[^\\]]*${id}:`, 'u'),
    );
  }

  for (const [name, matcherRuleId, predicate, expected] of [
    [
      'privacy-mcp-metric-factory-omitted',
      'PRIV-MATCH-002',
      ({ owner, member }) => owner === 'com.soklet.McpMetricsEvent'
        && member === 'sensitiveEvent(String)',
      /omitted=\[[^\]]*McpMetricsEvent#sensitiveEvent/,
    ],
    [
      'privacy-mcp-metric-getter-omitted',
      'PRIV-MATCH-002',
      ({ owner, member }) => owner.endsWith('.SensitiveEvent')
        && member === 'getSecret()',
      /omitted=\[[^\]]*SensitiveEvent#getSecret/,
    ],
    [
      'privacy-mcp-snapshot-builder-omitted',
      'PRIV-MATCH-002',
      ({ owner, member }) => owner.endsWith('.McpMetricsSnapshot.Builder')
        && member === 'secret(String)',
      /omitted=\[[^\]]*McpMetricsSnapshot\.Builder#secret/,
    ],
    [
      'privacy-full-root-outside-source-omitted',
      'PRIV-MATCH-006',
      ({ file }) => file.endsWith('/OutsidePrivacyFixture.java'),
      /omitted=\[[^\]]*OutsidePrivacyFixture/,
    ],
    [
      'privacy-http-result-surface-omitted',
      'PRIV-MATCH-007',
      ({ owner, member }) => owner === 'com.soklet.HttpRequestResult'
        && member === 'getBody()',
      /omitted=\[[^\]]*HttpRequestResult#getBody/,
    ],
    [
      'privacy-metrics-unconventional-callback-omitted',
      'PRIV-MATCH-015',
      ({ sink }) => sink === 'MetricsCollector.invocation.collectSecret',
      /omitted=\[[^\]]*MetricsCollector\.invocation\.collectSecret/,
    ],
    [
      'privacy-metrics-nested-getter-omitted',
      'PRIV-MATCH-015',
      ({ sink }) => sink === 'MetricsCollector.surface.getSecret',
      /omitted=\[[^\]]*MetricsCollector\.surface\.getSecret/,
    ],
    [
      'privacy-log-value-attachment-omitted',
      'PRIV-MATCH-016',
      ({ sink }) => sink === 'LogEvent.attachment.marshaledResponse',
      /omitted=\[[^\]]*LogEvent\.attachment\.marshaledResponse/,
    ],
    [
      'privacy-log-event-method-reference-omitted',
      'PRIV-MATCH-001',
      ({ member, sink }) => member.startsWith('exercise(')
        && sink === 'LogEvent.with:DYNAMIC',
      /omitted=\[[^\]]*LogEvent\.with:DYNAMIC/,
    ],
    [
      'privacy-mcp-metric-method-reference-omitted',
      'PRIV-MATCH-002',
      ({ member, occurrence, sink }) => member.startsWith('exercise(')
        && occurrence === 1
        && sink === 'McpMetricsEvent.sensitiveEvent',
      /omitted=\[[^\]]*McpMetricsEvent\.sensitiveEvent/,
    ],
    [
      'privacy-static-log-chain-request-omitted',
      'PRIV-MATCH-003',
      ({ member }) => member.startsWith('staticAttachments('),
      /omitted=\[[^\]]*staticAttachments/,
    ],
    [
      'privacy-inferred-copier-attachment-omitted',
      'PRIV-MATCH-016',
      ({ member, occurrence, sink }) => member.startsWith('variableAttachments(')
        && occurrence === 2
        && sink === 'LogEvent.attachment.response',
      /omitted=\[[^\]]*LogEvent\.attachment\.response@2/,
    ],
    [
      'privacy-comma-request-field-omitted',
      'PRIV-MATCH-005',
      ({ member, sink }) => member === 'secondRequest'
        && sink === 'RequestOrThrowable.field',
      /omitted=\[[^\]]*secondRequest/,
    ],
    [
      'privacy-comma-throwable-field-omitted',
      'PRIV-MATCH-005',
      ({ member, sink }) => member === 'secondFailure'
        && sink === 'RequestOrThrowable.field',
      /omitted=\[[^\]]*secondFailure/,
    ],
    [
      'privacy-mcp-event-loop-wiring-omitted',
      'PRIV-MATCH-013',
      ({ sink }) => sink === 'MicrohttpLogger.McpEventLoopWiring:EXPLICIT_NOOP',
      /omitted=\[[^\]]*MicrohttpLogger\.McpEventLoopWiring:EXPLICIT_NOOP/,
    ],
    [
      'privacy-mcp-metric-field-omitted',
      'PRIV-MATCH-002',
      ({ member, owner, sink }) => owner.endsWith('.SensitiveEvent')
        && member === 'exposedSecret'
        && sink === 'McpMetricsSurface.field',
      /omitted=\[[^\]]*SensitiveEvent#exposedSecret/,
    ],
    [
      'privacy-generic-metric-field-omitted',
      'PRIV-MATCH-015',
      ({ member, owner, sink }) => owner.endsWith('.MetricsCollector.Snapshot')
        && member === 'exposedSecret'
        && sink === 'MetricsCollector.surface.field',
      /omitted=\[[^\]]*MetricsCollector\.Snapshot#exposedSecret/,
    ],
    [
      'privacy-system-output-method-reference-omitted',
      'PRIV-MATCH-014',
      ({ member, sink }) => member === 'writeThroughAlias()'
        && sink === 'DirectOutput.System.err.println',
      /omitted=\[[^\]]*DirectOutput\.System\.err\.println/,
    ],
    [
      'privacy-typed-output-method-reference-omitted',
      'PRIV-MATCH-014',
      ({ member, sink }) => member === 'writeDirectly(PrintStream)'
        && sink === 'DirectOutput.PrintStream.println',
      /omitted=\[[^\]]*DirectOutput\.PrintStream\.println/,
    ],
    [
      'privacy-aliased-output-method-reference-omitted',
      'PRIV-MATCH-014',
      ({ member, occurrence, sink }) => member === 'writeThroughAlias()'
        && occurrence === 2
        && sink === 'DirectOutput.SystemErrAlias.println',
      /omitted=\[[^\]]*DirectOutput\.SystemErrAlias\.println@2/,
    ],
    [
      'privacy-wire-error-surface-omitted',
      'PRIV-MATCH-009',
      ({ member, owner, sink }) => owner === 'com.soklet.McpJsonRpcError'
        && member === 'getMessage()'
        && sink === 'McpWireError.surface.getMessage',
      /omitted=\[[^\]]*McpWireError\.surface\.getMessage/,
    ],
    [
      'privacy-wire-record-accessor-omitted',
      'PRIV-MATCH-009',
      ({ member, owner, sink }) =>
        owner === 'com.soklet.internal.mcp.protocol.McpJsonRpcError'
          && member === 'data()'
          && sink === 'McpWireError.recordAccessor',
      /omitted=\[[^\]]*McpWireError\.recordAccessor/,
    ],
    [
      'privacy-qualified-wire-construction-omitted',
      'PRIV-MATCH-009',
      ({ member, sink }) => member === 'errorDtos()'
        && sink === 'McpJsonRpcError.constructor',
      /omitted=\[[^\]]*errorDtos\(\).*McpJsonRpcError\.constructor/,
    ],
    [
      'privacy-wire-accessor-publication-omitted',
      'PRIV-MATCH-009',
      ({ member, sink }) => member.startsWith('publishWireError(')
        && sink === 'McpWireError.publication.getData',
      /omitted=\[[^\]]*McpWireError\.publication\.getData/,
    ],
    [
      'privacy-request-visible-surface-omitted',
      'PRIV-MATCH-005',
      ({ member, owner, sink }) => owner === 'com.soklet.Request'
        && member === 'getHeaders()'
        && sink === 'ApplicationRequestCarrier.surface.getHeaders',
      /omitted=\[[^\]]*ApplicationRequestCarrier\.surface\.getHeaders/,
    ],
    [
      'privacy-context-visible-surface-omitted',
      'PRIV-MATCH-005',
      ({ member, owner, sink }) =>
        owner === 'com.soklet.McpRequestStateProtectionContext'
          && member === 'getAssociatedData()'
          && sink === 'ApplicationRequestCarrier.surface.getAssociatedData',
      /omitted=\[[^\]]*ApplicationRequestCarrier\.surface\.getAssociatedData/,
    ],
    [
      'privacy-carrier-implementation-surface-omitted',
      'PRIV-MATCH-005',
      ({ member, owner, sink }) => owner === 'com.soklet.DefaultToolArguments'
        && member === 'getRawSecret()'
        && sink === 'ApplicationRequestCarrier.surface.getRawSecret',
      /omitted=\[[^\]]*ApplicationRequestCarrier\.surface\.getRawSecret/,
    ],
    [
      'privacy-explicit-redacted-carrier-renderer-omitted',
      'PRIV-MATCH-008',
      ({ member, owner, sink }) =>
        owner === 'com.soklet.McpPrivacyFixture.PublicRequestCarrier'
          && member === 'toString()'
          && sink === 'Diagnostic.toString',
      /omitted=\[[^\]]*PublicRequestCarrier#toString\(\).*Diagnostic\.toString/,
    ],
    [
      'privacy-implicit-throwable-renderer-omitted',
      'PRIV-MATCH-005',
      ({ member, owner, sink }) =>
        owner === 'com.soklet.McpPrivacyFixture.PrivateThrowableCarrier'
          && member === 'toString()'
          && sink === 'RequestOrThrowable.recordRenderer',
      /omitted=\[[^\]]*PrivateThrowableCarrier#toString\(\).*RequestOrThrowable\.recordRenderer/,
    ],
    [
      'privacy-carrier-signature-omitted',
      'PRIV-MATCH-005',
      ({ member, owner, sink }) => owner === 'com.soklet.SyntheticToolHandler'
        && member === 'handle(McpToolArguments<String>)'
        && sink === 'ApplicationRequestCarrier.declaration',
      /omitted=\[[^\]]*ApplicationRequestCarrier\.declaration/,
    ],
  ]) {
    expectPrivacyInvalid(name, privacyInventory, (value) => {
      const boundary = value.boundaries.find(({ sourcePaths }) =>
        sourcePaths.some((sourcePath) =>
          sourcePath.matcherRuleId === matcherRuleId && predicate(sourcePath)));
      assert.ok(boundary);
      const index = boundary.sourcePaths.findIndex((sourcePath) =>
        sourcePath.matcherRuleId === matcherRuleId && predicate(sourcePath));
      boundary.sourcePaths.splice(index, 1);
    }, expected);
  }

  const recordComponentFixturePath = join(
    privacySourceDirectory,
    'ResponseLike.java',
  );
  const originalRecordComponentFixture = readFileSync(
    recordComponentFixturePath,
    'utf8',
  );
  writeFileSync(
    recordComponentFixturePath,
    originalRecordComponentFixture.replace(
      'record SecretCarrier(String value) {}',
      'record SecretCarrier(String value, String newSecret) {}',
    ),
  );
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /omitted=\[[^\]]*(?:SecretCarrier\(String,String\)|newSecret\(\))/,
  );
  writeFileSync(recordComponentFixturePath, originalRecordComponentFixture);

  const applicationCarrierFixturePath = join(
    privacySourceDirectory,
    'McpRequestStateProtectionContext.java',
  );
  const originalApplicationCarrierFixture = readFileSync(
    applicationCarrierFixturePath,
    'utf8',
  );
  writeFileSync(
    applicationCarrierFixturePath,
    originalApplicationCarrierFixture.replace(
      'public String getAssociatedData() { return "exact"; }',
      `public String getAssociatedData() { return "exact"; }
  public String getNewSecret() { return "exact"; }`,
    ),
  );
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /omitted=\[[^\]]*ApplicationRequestCarrier\.surface\.getNewSecret/,
  );
  writeFileSync(applicationCarrierFixturePath, originalApplicationCarrierFixture);

  const eventLoopWiringFixturePath = join(
    privacyInternalSourceDirectory,
    'McpSimulationRuntime.java',
  );
  const originalEventLoopWiringFixture = readFileSync(
    eventLoopWiringFixturePath,
    'utf8',
  );
  writeFileSync(
    eventLoopWiringFixturePath,
    originalEventLoopWiringFixture.replace(
      'return new EventLoop(options, NoopLogger.instance(), handler);',
      'return new EventLoop(options, new DebugLogger(), handler);',
    ),
  );
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /(?:omitted=\[[^\]]*MicrohttpLogger\.McpEventLoopWiring:ALTERNATE|extra=\[[^\]]*MicrohttpLogger\.McpEventLoopWiring:EXPLICIT_NOOP)/,
  );
  writeFileSync(eventLoopWiringFixturePath, originalEventLoopWiringFixture);

  const defaultEventLoopWiringFixturePath = join(
    privacyMicrohttpSourceDirectory,
    'EventLoop.java',
  );
  const originalDefaultEventLoopWiringFixture = readFileSync(
    defaultEventLoopWiringFixturePath,
    'utf8',
  );
  writeFileSync(
    defaultEventLoopWiringFixturePath,
    originalDefaultEventLoopWiringFixture.replace(
      'this(options, NoopLogger.instance(), handler);',
      'this(options, new DebugLogger(), handler);',
    ),
  );
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /(?:omitted=\[[^\]]*MicrohttpLogger\.EventLoopDefaultWiring:ALTERNATE|extra=\[[^\]]*MicrohttpLogger\.EventLoopDefaultWiring:DEFAULT_NOOP)/,
  );
  writeFileSync(
    defaultEventLoopWiringFixturePath,
    originalDefaultEventLoopWiringFixture,
  );

  expectPrivacyInvalid('privacy-exact-artifact-omitted', privacyInventory, (value) => {
    const boundary = value.boundaries.find(({ sourcePaths }) =>
      sourcePaths.some(({ file }) =>
        file === 'conformance/privacy-self-test-exact.golden'));
    assert.ok(boundary);
    boundary.sourcePaths = boundary.sourcePaths.filter(({ file }) =>
      file !== 'conformance/privacy-self-test-exact.golden');
  }, /omitted=\[[^\]]*privacy-self-test-exact\.golden/);
  expectPrivacyInvalid('privacy-multipart-artifact-omitted', privacyInventory, (value) => {
    const boundary = value.boundaries.find(({ sourcePaths }) =>
      sourcePaths.some(({ file }) =>
        file === 'src/test/resources/multipart-request-body'));
    assert.ok(boundary);
    boundary.sourcePaths = boundary.sourcePaths.filter(({ file }) =>
      file !== 'src/test/resources/multipart-request-body');
  }, /omitted=\[[^\]]*multipart-request-body/);
  expectPrivacyInvalid('privacy-record-renderer-omitted', privacyInventory, (value) => {
    const boundary = value.boundaries.find(({ sourcePaths }) =>
      sourcePaths.some(({ owner, sink }) =>
        owner.endsWith('.PrivateThrowableCarrier')
          && sink === 'RequestOrThrowable.recordRenderer'));
    assert.ok(boundary);
    boundary.sourcePaths = boundary.sourcePaths.filter(({ owner, sink }) =>
      !owner.endsWith('.PrivateThrowableCarrier')
        || sink !== 'RequestOrThrowable.recordRenderer');
  }, /omitted=\[[^\]]*PrivateThrowableCarrier#toString/);
  expectPrivacyInvalid('privacy-exception-renderer-omitted', privacyInventory, (value) => {
    const boundary = value.boundaries.find(({ sourcePaths }) =>
      sourcePaths.some(({ matcherRuleId, member }) =>
        matcherRuleId === 'PRIV-MATCH-012' && member === 'toString()'));
    assert.ok(boundary);
    boundary.sourcePaths = boundary.sourcePaths.filter(
      ({ matcherRuleId, member }) =>
        matcherRuleId !== 'PRIV-MATCH-012' || member !== 'toString()',
    );
  }, /omitted=\[[^\]]*PRIV-MATCH-012:[^\]]*toString/);

  expectPrivacyInvalid('privacy-extra-source-path', privacyInventory, (value) => {
    const extra = {
      ...value.boundaries[0].sourcePaths[0],
      member: '$undeclared()',
    };
    extra.key = `${extra.matcherRuleId}:${extra.file}#${extra.owner}#${extra.member}->${extra.sink}@${extra.occurrence}`;
    value.boundaries[0].sourcePaths.push(extra);
    value.boundaries[0].sourcePaths.sort((left, right) =>
      left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
  }, /extra=\[[^\]]*\$undeclared/);
  expectPrivacyInvalid('privacy-malformed-key', privacyInventory, (value) => {
    value.boundaries[0].sourcePaths[0].key = 'PRIV-MATCH-001:wrong';
  }, /\.key must be exactly/);
  expectPrivacyInvalid('privacy-malformed-occurrence', privacyInventory, (value) => {
    value.boundaries[0].sourcePaths[0].occurrence = 0;
  }, /\.occurrence must be a positive safe integer/);
  expectPrivacyInvalid('privacy-unknown-matcher', privacyInventory, (value) => {
    value.boundaries[0].sourcePaths[0].matcherRuleId = 'PRIV-MATCH-999';
  }, /\.matcherRuleId is unknown/);
  expectPrivacyInvalid('privacy-duplicate-classification', privacyInventory, (value) => {
    value.boundaries[0].sourcePaths.push(
      clone(value.boundaries[0].sourcePaths[0]),
    );
    value.boundaries[0].sourcePaths.sort((left, right) =>
      left.key < right.key ? -1 : left.key > right.key ? 1 : 0);
  }, /Privacy-boundary classification is duplicated/);
  expectPrivacyInvalid('privacy-required-delegation-omitted', privacyInventory, (value) => {
    value.delegations = value.delegations.filter(({ delegatedOwner }) =>
      delegatedOwner !== 'OPERATOR_RETENTION');
  }, /delegated owners must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-delegated-owner-duplicate', privacyInventory, (value) => {
    value.delegations[1].delegatedOwner = value.delegations[0].delegatedOwner;
  }, /delegated owners must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-delegated-owner-extra', privacyInventory, (value) => {
    value.delegations.push({
      canaryTests: [],
      contract: 'Invented delegated owner.',
      delegatedOwner: 'INVENTED_OWNER',
      id: 'PRIV-DELEGATION-004',
      name: 'Invented owner',
      sourcePaths: [],
    });
  }, /delegated owners must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-delegated-owner-reordered', privacyInventory, (value) => {
    [value.delegations[0].delegatedOwner, value.delegations[1].delegatedOwner] = [
      value.delegations[1].delegatedOwner,
      value.delegations[0].delegatedOwner,
    ];
  }, /delegated owners must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-schema-extra-key', privacyInventory, (value) => {
    value.extra = true;
  }, /Privacy-boundary inventory keys must be exactly/);
  expectPrivacyInvalid('privacy-matcher-drift', privacyInventory, (value) => {
    value.matcherRules[0].description = 'Narrowed matcher.';
  }, /matcherRules do not match the executable matcher contract/);
  expectPrivacyInvalid('privacy-artifact-roots-drift', privacyInventory, (value) => {
    value.artifactRoots = [];
  }, /Privacy-boundary artifactRoots must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-exact-artifact-root-drift', privacyInventory, (value) => {
    value.artifactRoots[1] = 'conformance/privacy-self-test-renamed.golden';
  }, /Privacy-boundary artifactRoots must match the frozen order exactly/);
  expectPrivacyInvalid('privacy-scan-roots-drift', privacyInventory, (value) => {
    value.scanRoots = [];
  }, /Privacy-boundary scanRoots must match the frozen order exactly/);
  const privacyWithFuzzCanary = clone(privacyInventory);
  privacyWithFuzzCanary.boundaries[0].canaryTests = [
    'fuzz/src/test/java/com/soklet/McpPrivacyBoundaryFuzzTests.java#fuzzCanary',
  ];
  const privacyFuzzCanaryPath = writePrivacyFixture(
    'fuzz-canary-root',
    privacyWithFuzzCanary,
  );
  assert.equal(
    verifyPrivacyFixture(privacyFuzzCanaryPath, {
      expectedSemanticsSha256: privacySemanticsSha256(privacyWithFuzzCanary),
    }).boundaries[0]
      .canaryTests[0],
    privacyWithFuzzCanary.boundaries[0].canaryTests[0],
  );
  expectPrivacyInvalid('privacy-canary-arbitrary-root', privacyInventory, (value) => {
    value.boundaries[0].canaryTests = [
      'src/integration/java/com/soklet/McpPrivacyBoundaryFixtureTests.java#canary',
    ];
  }, /must name a Java test source/);
  expectPrivacyInvalid('privacy-canary-missing', privacyInventory, (value) => {
    value.boundaries[0].canaryTests = [
      'src/test/java/com/soklet/MissingPrivacyCanaryTests.java#canary',
    ];
  }, /canaryTests\[0\] does not exist/);
  expectPrivacyInvalid('privacy-canary-method-missing', privacyInventory, (value) => {
    value.boundaries[0].canaryTests = [
      'src/test/java/com/soklet/McpPrivacyBoundaryFixtureTests.java#missingCanary',
    ];
  }, /names no declared test method: missingCanary/);

  const privacyWithExclusion = clone(privacyInventory);
  const excludedPrivacyPath = privacyWithExclusion.boundaries[0].sourcePaths
    .shift();
  if (privacyWithExclusion.boundaries[0].sourcePaths.length === 0) {
    privacyWithExclusion.boundaries.shift();
  }
  privacyWithExclusion.reviewedExclusions = [{
    file: excludedPrivacyPath.file,
    id: 'PRIV-EX-001',
    key: excludedPrivacyPath.key,
    matcherRuleId: excludedPrivacyPath.matcherRuleId,
    member: excludedPrivacyPath.member,
    occurrence: excludedPrivacyPath.occurrence,
    owner: excludedPrivacyPath.owner,
    rationale: 'Exact reviewed synthetic exclusion.',
    sink: excludedPrivacyPath.sink,
  }];
  const privacyExclusionPath = writePrivacyFixture(
    'exact-reviewed-exclusion',
    privacyWithExclusion,
  );
  assert.equal(
    verifyPrivacyFixture(privacyExclusionPath, {
      expectedSemanticsSha256: privacySemanticsSha256(privacyWithExclusion),
    }).exclusions[0].key,
    excludedPrivacyPath.key,
  );

  const privacyWithDelegatedPath = clone(privacyInventory);
  const delegatedPrivacyPath = privacyWithDelegatedPath.boundaries[0]
    .sourcePaths.shift();
  if (privacyWithDelegatedPath.boundaries[0].sourcePaths.length === 0) {
    privacyWithDelegatedPath.boundaries.shift();
  }
  privacyWithDelegatedPath.delegations[0].canaryTests = [
    'src/test/java/com/soklet/McpPrivacyBoundaryFixtureTests.java#canary',
  ];
  privacyWithDelegatedPath.delegations[0].sourcePaths = [delegatedPrivacyPath];
  const privacyDelegatedPath = writePrivacyFixture(
    'exact-delegated-path',
    privacyWithDelegatedPath,
  );
  assert.equal(
    verifyPrivacyFixture(privacyDelegatedPath, {
      expectedSemanticsSha256: privacySemanticsSha256(
        privacyWithDelegatedPath,
      ),
    }).delegations[0]
      .sourcePaths[0].key,
    delegatedPrivacyPath.key,
  );

  const addedPrivacyArtifactDirectory = join(
    privacyArtifactDirectory,
    'nested/deeper',
  );
  mkdirSync(addedPrivacyArtifactDirectory, { recursive: true });
  const addedPrivacyArtifactPath = join(
    addedPrivacyArtifactDirectory,
    'new-unclassified.golden',
  );
  writeFileSync(addedPrivacyArtifactPath, 'new fixture\n');
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /omitted=\[[^\]]*new-unclassified\.golden/,
  );
  rmSync(join(privacyArtifactDirectory, 'nested'), {
    recursive: true,
  });

  for (const [directory, relativeName, expected] of [
    [
      privacyFinalSchemaArtifactDirectory,
      'nested/new-schema.json',
      /omitted=\[[^\]]*new-schema\.json/,
    ],
    [
      privacyFuzzArtifactDirectory,
      'nested/new-query.query',
      /omitted=\[[^\]]*new-query\.query/,
    ],
    [
      privacyMicrohttpFuzzArtifactDirectory,
      'nested/new-request.http',
      /omitted=\[[^\]]*new-request\.http/,
    ],
    [
      privacySchemaArtifactDirectory,
      'nested/new-profile.json',
      /omitted=\[[^\]]*new-profile\.json/,
    ],
  ]) {
    const addedDirectory = join(directory, 'nested');
    mkdirSync(addedDirectory, { recursive: true });
    const addedPath = join(directory, relativeName);
    writeFileSync(addedPath, 'new tracked fixture\n');
    assert.throws(() => verifyPrivacyFixture(privacyInventoryPath), expected);
    rmSync(addedDirectory, { recursive: true });
  }

  rmSync(privacyExactArtifactPath);
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /Privacy-boundary exact artifact .* does not exist/,
  );
  writeFileSync(privacyExactArtifactPath, 'exact tracked fixture\n');

  rmSync(privacyMultipartArtifactPath);
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /Privacy-boundary exact artifact .*multipart-request-body.* does not exist/,
  );
  writeFileSync(privacyMultipartArtifactPath, 'raw multipart request\n');

  rmSync(privacyArtifactPath);
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath),
    /extra=\[[^\]]*redacted\.golden/,
  );
  writeFileSync(privacyArtifactPath, 'redacted fixture\n');

  const untrackedPrivacyGit = join(temporaryRoot, 'privacy-untracked-git');
  writeFileSync(untrackedPrivacyGit, '#!/bin/sh\nexit 1\n', { mode: 0o700 });
  assert.throws(
    () => verifyPrivacyFixture(privacyInventoryPath, {
      gitExecutable: untrackedPrivacyGit,
    }),
    /Privacy-boundary fixture artifact is not tracked/,
  );

  const registryBytes = readFileSync(prepareSemanticGit(registryPath));
  const registryText = registryBytes.toString('utf8');
  const registry = JSON.parse(registryText);
  assert.equal(registryText, canonicalJson(registry));

  const residualBytes = readFileSync(residualEvidencePath);
  const residualText = residualBytes.toString('utf8');
  const residualEvidence = JSON.parse(residualText);
  assert.equal(residualText, canonicalJson(residualEvidence));
  assert.deepEqual(
    residualEvidence.rows.map(({ id }) => id),
    expectedResidualIds,
  );

  const current = verifyFixture(registryPath);
  assert.equal(current.exitCode, 0);
  assert.equal(current.report.status, 'PASSED');
  assert.deepEqual(Object.keys(current.report), expectedReportKeys);
  assert.equal(current.report.rowCount, 263);
  assert.equal(
    current.report.rowIdsSha256,
    'd7a55f3218e4ea8d18e2f6295f56d9b9b70ecdba9deb8be5a624bae3a9b647b0',
  );
  assert.equal(
    current.report.registrySha256,
    createHash('sha256').update(registryBytes).digest('hex'),
  );
  assert.equal(
    current.report.residualSha256,
    createHash('sha256').update(residualBytes).digest('hex'),
  );
  assert.deepEqual(current.report.dispositionCounts, {
    APPLICATION_OWNED: 12,
    CORE_COMPLETE: 113,
    NOT_APPLICABLE: 19,
    RELEASE_GATED: 119,
    UNRESOLVED: 0,
  });
  assert.deepEqual(current.report.unresolvedRows, []);
  assert.deepEqual(current.report.rows, registry.rows);
  assert.equal(current.reportText, canonicalJson(current.report));
  assert.ok(current.reportText.endsWith('\n'));
  assert.ok(!current.reportText.includes('\r'));

  assert.deepEqual(row(registry, 'MCP-BASE-019').releaseGates, [
    'candidate-build',
    'core-jdk-21',
    'core-jdk-25',
  ]);
  assert.deepEqual(row(registry, 'MCP-HTTP-005').releaseGates, [
    'candidate-conformance',
  ]);
  assert.deepEqual(row(registry, 'MCP-HTTP-022').releaseGates, [
    'candidate-conformance',
  ]);
  for (const id of [
    'MCP-BASE-022',
    'MCP-BASE-023',
    'MCP-VER-001',
    'MCP-HTTP-011',
  ]) {
    assert.equal(row(registry, id).disposition, 'RELEASE_GATED');
    assert.deepEqual(row(registry, id).releaseGates, ['candidate-conformance']);
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java',
    ));
  }
  for (const id of [
    'MCP-BASE-005',
    'MCP-BASE-011',
    'MCP-BASE-024',
    'MCP-VER-003',
    'MCP-VER-004',
    'MCP-HTTP-018',
    'MCP-HTTP-020',
    'MCP-HTTP-024',
    'MCP-HTTP-025',
    'MCP-AUTH-003',
    'MCP-HTTP-021',
    'SOK-ERROR-001',
    'SOK-ERROR-002',
    'SOK-RATE-006',
    'SOK-RATE-007',
    'SOK-CORS-005',
    'SOK-VALID-001',
    'MCP-MRTR-011',
    'SOK-EXEC-005',
  ]) {
    assert.equal(row(registry, id).disposition, 'CORE_COMPLETE');
    assert.deepEqual(row(registry, id).releaseGates, []);
    assert.equal(row(registry, id).reason, '');
  }
  const notificationBoundaryRow = row(registry, 'MCP-BASE-011');
  assert.deepEqual(notificationBoundaryRow.evidence, [
    'src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerNotificationTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpJsonRpcEnvelopeCodecTests.java',
  ]);
  assert.deepEqual(notificationBoundaryRow.releaseGates, []);
  assert.equal(notificationBoundaryRow.reason, '');
  const unknownHeaderPolicyRow = row(registry, 'MCP-HTTP-020');
  assert.deepEqual(unknownHeaderPolicyRow.evidence, [
    'src/test/java/com/soklet/McpMirroredHeaderPublicRuntimeTests.java',
    'src/test/java/com/soklet/McpPreAdmissionMetricsEventPublicRuntimeTests.java',
    'src/test/java/com/soklet/McpProtocolAndUnknownHeaderMetricsAggregationTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerCustomHeaderTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpUnknownMirroredHeaderNameDiagnosticsTests.java',
  ]);
  assert.deepEqual(unknownHeaderPolicyRow.releaseGates, []);
  assert.equal(unknownHeaderPolicyRow.reason, '');
  const conditionalProxyRow = row(registry, 'MCP-MRTR-011');
  assert.deepEqual(conditionalProxyRow.releaseGates, []);
  assert.equal(conditionalProxyRow.reason, '');
  assert.ok(conditionalProxyRow.evidence.includes(
    'src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java',
  ));
  const queuedWinnerRow = row(registry, 'SOK-EXEC-005');
  assert.ok(queuedWinnerRow.evidence.includes(
    'src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java',
  ));
  const simulationBoundaryRow = row(registry, 'SOK-SIM-001');
  assert.equal(simulationBoundaryRow.disposition, 'RELEASE_GATED');
  assert.deepEqual(simulationBoundaryRow.releaseGates, [
    'candidate-build',
    'core-jdk-21',
    'core-jdk-25',
    'fuzz-nightly-history',
    'soak-nightly-history',
    'release-soak',
    'candidate-conformance',
  ]);
  assert.equal(
    simulationBoundaryRow.reason,
    'Remaining immutable or scheduled evidence is owned by: candidate-build, core-jdk-21, core-jdk-25, fuzz-nightly-history, soak-nightly-history, release-soak, candidate-conformance.',
  );
  for (const evidence of [
    'release/release-validation-manifest.json',
    'src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpSimulationCaptureRuntimeTests.java',
  ]) {
    assert.ok(simulationBoundaryRow.evidence.includes(evidence));
  }
  for (const [id, evidence, reason] of [
    [
      'MCP-BASE-015',
      [
        'src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java',
        'src/test/java/com/soklet/internal/mcp/protocol/McpFrameworkRequestStateRuntimeTests.java',
        'src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java',
      ],
      'Applications own durable-handle issuance, persistence, rotation, expiry, and admitted-context binding; the executable public-API example proves that boundary.',
    ],
    [
      'MCP-PROMPT-006',
      [
        'src/test/java/com/soklet/McpPromptPublicRuntimeTests.java',
        'src/test/java/com/soklet/McpPromptRegistrationTests.java',
        'src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java',
      ],
      'Applications own prompt-semantic allowlists, authorization, resource selection, and injection policy; the executable public-API example proves that boundary.',
    ],
    [
      'MCP-RESOURCE-006',
      [
        'MCP.md',
        'src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java',
      ],
      'Applications exposing files own canonical containment, symlink and race policy, authorization, and safe failure mapping; the executable public-API example proves the bounded pattern.',
    ],
    [
      'MCP-RESOURCE-007',
      [
        'src/test/java/com/soklet/McpResourceRegistrationTests.java',
        'src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java',
      ],
      'Applications own the URI schemes their clients can load or their handlers resolve; the executable public-API example distinguishes direct HTTPS from handler-only custom URIs.',
    ],
    [
      'MCP-PAGE-004',
      [
        'src/test/java/com/soklet/McpResourcePublicRuntimeTests.java',
        'src/test/java/com/soklet/internal/mcp/protocol/McpResourceProtocolTests.java',
        'src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java',
      ],
      'Applications own cursor parsing, integrity, expiry, authorization, snapshot lookup, and neutral invalid-parameter mapping; the executable public-API example proves one safe pattern.',
    ],
    [
      'MCP-PAGE-006',
      [
        'MCP.md',
        'SECURITY.md',
        'src/test/java/com/soklet/McpResourcePublicRuntimeTests.java',
        'src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java',
      ],
      'Applications own page size, cross-page uniqueness and snapshot consistency, cursor integrity, authorization, and cross-instance storage/key portability; the executable two-node public-API example proves one shared-snapshot pattern.',
    ],
    [
      'MCP-PAGE-007',
      [
        'MCP.md',
        'src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java',
      ],
      'Applications own cursor stability, retained snapshots, revisions, expiry windows, and integrity; the executable example proves a single-process retained-snapshot pattern.',
    ],
    [
      'SOK-L10N-007',
      [
        'MCP.md',
        'SECURITY.md',
        'src/test/java/com/soklet/McpLocalizationHandlerRuntimeTests.java',
        'src/test/java/com/soklet/McpLocalizationPublicApiTests.java',
        'src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java',
      ],
      'Applications own locale/revision binding in opaque cursors and handler mismatch mapping; the executable two-node public-API example proves provider preselection plus full handler authentication while Soklet preserves every cursor byte.',
    ],
  ]) {
    const applicationRow = row(registry, id);
    assert.equal(applicationRow.disposition, 'APPLICATION_OWNED');
    assert.deepEqual(applicationRow.evidence, evidence);
    assert.deepEqual(applicationRow.releaseGates, []);
    assert.equal(applicationRow.reason, reason);
  }
  for (const id of [
    'MCP-HTTP-021',
    'SOK-ERROR-001',
    'SOK-RATE-007',
  ]) {
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java',
    ));
  }
  assert.ok(row(registry, 'SOK-ERROR-001').evidence.includes(
    'src/test/java/com/soklet/McpBootstrapValueTests.java',
  ));
  for (const evidence of [
    'conformance/golden-result-envelope/live/manifest.sha256',
    'src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java',
  ]) {
    assert.ok(row(registry, 'MCP-BASE-005').evidence.includes(evidence));
  }
  for (const evidence of [
    'conformance/golden-error-mapping/live/manifest.sha256',
    'src/test/java/com/soklet/internal/mcp/protocol/McpErrorMappingGoldenProductionTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java',
    'src/test/java/com/soklet/internal/mcp/protocol/McpProgressPublicRuntimeTests.java',
  ]) {
    assert.ok(row(registry, 'SOK-ERROR-002').evidence.includes(evidence));
  }
  for (const id of ['SOK-RATE-006', 'SOK-RATE-007']) {
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java',
    ));
  }
  assert.ok(row(registry, 'MCP-BASE-024').evidence.includes(
    'src/test/java/com/soklet/McpSelfReportedIdentityPublicRuntimeTests.java',
  ));
  assert.ok(row(registry, 'MCP-VER-003').evidence.includes(
    'src/test/java/com/soklet/McpExtensionCompatibilityPublicRuntimeTests.java',
  ));
  assert.ok(row(registry, 'MCP-VER-004').evidence.includes(
    'src/test/java/com/soklet/internal/mcp/protocol/McpInitializeRejectionDiagnosticsTests.java',
  ));
  for (const evidence of [
    'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java',
    'src/test/java/com/soklet/McpLegacySessionNegativeInventoryTests.java',
  ]) {
    assert.ok(row(registry, 'MCP-HTTP-018').evidence.includes(evidence));
  }
  for (const id of ['MCP-AUTH-003', 'SOK-CORS-005']) {
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/McpAuthorizationIntegrationTests.java',
    ));
  }
  assert.ok(row(registry, 'MCP-AUTH-003').evidence.includes(
    'conformance/golden-http-head/authorization-cors/authorized-bearer-challenge.head',
  ));
  for (const evidence of [
    'conformance/golden-http-head/authorization-cors/authorized-bearer-challenge.head',
    'conformance/golden-http-head/authorization-cors/authorized-preflight.head',
    'conformance/golden-http-head/authorization-cors/empty-cors-rejection.head',
  ]) {
    assert.ok(row(registry, 'SOK-CORS-005').evidence.includes(evidence));
  }
  for (const id of ['MCP-HTTP-024', 'MCP-HTTP-025', 'SOK-VALID-001']) {
    assert.ok(row(registry, id).evidence.includes(
      'conformance/golden-http-contract/precedence-no-store/manifest.sha256',
    ));
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/McpHttpContractGoldenProductionTests.java',
    ));
  }
  for (const id of ['MCP-HTTP-021', 'SOK-ERROR-001', 'SOK-ERROR-002']) {
    assert.ok(row(registry, id).evidence.includes(
      'src/test/java/com/soklet/internal/mcp/protocol/McpInitializeRejectionDiagnosticsTests.java',
    ));
  }
  for (const evidence of [
    'src/test/java/com/soklet/McpInputRequiredPublicRuntimeTests.java',
    'src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java',
  ]) {
    assert.ok(row(registry, 'SOK-VALID-001').evidence.includes(evidence));
  }
  assert.deepEqual(row(registry, 'SOK-METRIC-001').releaseGates, [
    'release-soak',
    'operational-history',
    'soklet-otel',
  ]);
  assert.equal(row(registry, 'SOK-VALID-002').disposition, 'RELEASE_GATED');
  assert.deepEqual(row(registry, 'SOK-VALID-002').releaseGates, [
    'fuzz-nightly-history',
    'soak-nightly-history',
    'release-soak',
  ]);
  assert.equal(row(registry, 'SOK-PRIV-001').disposition, 'RELEASE_GATED');
  assert.deepEqual(row(registry, 'SOK-PRIV-001').releaseGates, [
    'release-soak',
    'operational-history',
    'soklet-otel',
  ]);
  for (const id of ['SOK-STATE-002', 'SOK-STATE-007', 'AMB-002']) {
    assert.equal(row(registry, id).disposition, 'CORE_COMPLETE');
    assert.deepEqual(row(registry, id).releaseGates, []);
    assert.equal(row(registry, id).reason, '');
  }
  assert.ok(new Set(registry.rows.flatMap(({ evidence }) => evidence)).size >= 160);

  const checkedInCli = spawnSync(process.execPath, [verifierPath], {
    cwd: projectRoot,
    encoding: 'utf8', env: semanticGitEnvironment(),
  });
  assert.equal(checkedInCli.status, 0);
  assert.equal(checkedInCli.stdout, current.reportText);
  assert.equal(checkedInCli.stderr, '');

  const usage = spawnSync(process.execPath, [verifierPath, 'unexpected'], {
    cwd: projectRoot,
    encoding: 'utf8',
  });
  assert.equal(usage.status, 2);
  assert.equal(usage.stdout, '');
  assert.equal(
    usage.stderr,
    'Usage: node scripts/verify-release-matrix-closure.mjs\n',
  );

  // The checked-in MCP-C closure is now the only positive integration fixture.
  // Keeping the aliases makes the mutation suite below read as resolved-state
  // verification without synthesizing an invalid all-core shortcut.
  const resolvedRegistry = registry;
  const resolvedPath = registryPath;
  const resolved = current;

  expectRawResidualInvalid(
    'malformed',
    '{\n',
    /MCP-C residual closure evidence is malformed JSON/,
  );
  expectRawResidualInvalid(
    'crlf',
    canonicalJson(residualEvidence).replaceAll('\n', '\r\n'),
    /MCP-C residual closure evidence must use LF line endings/,
  );
  expectRawResidualInvalid(
    'missing-lf',
    canonicalJson(residualEvidence).slice(0, -1),
    /MCP-C residual closure evidence must end with one LF/,
  );
  expectRawResidualInvalid(
    'noncanonical',
    `${JSON.stringify(residualEvidence)}\n`,
    /MCP-C residual closure evidence is not canonical two-space JSON/,
  );
  expectResidualInvalid('missing-top-level-key', residualEvidence, (value) => {
    delete value.protocolVersion;
  }, /MCP-C residual closure evidence keys must be exactly/);
  expectResidualInvalid('extra-top-level-key', residualEvidence, (value) => {
    value.extra = true;
  }, /MCP-C residual closure evidence keys must be exactly/);
  expectResidualInvalid('reordered-top-level-key', residualEvidence, (value) => {
    const formatVersion = value.formatVersion;
    delete value.formatVersion;
    value.formatVersion = formatVersion;
  }, /MCP-C residual closure evidence keys must be exactly/);
  expectResidualInvalid('format-version-drift', residualEvidence, (value) => {
    value.formatVersion = 2;
  }, /format, protocol, or release version is invalid/);
  expectResidualInvalid('protocol-version-drift', residualEvidence, (value) => {
    value.protocolVersion = '2025-11-25';
  }, /format, protocol, or release version is invalid/);
  expectResidualInvalid('release-version-drift', residualEvidence, (value) => {
    value.releaseVersion = '4.0.1';
  }, /format, protocol, or release version is invalid/);
  expectResidualInvalid('missing-id', residualEvidence, (value) => {
    value.rows.pop();
  }, /must contain exactly 5 rows/);
  expectResidualInvalid('extra-id', residualEvidence, (value) => {
    value.rows.push({
      ...clone(value.rows.at(-1)),
      id: 'AMB-003',
    });
  }, /must contain exactly 5 rows/);
  expectResidualInvalid('duplicate-id', residualEvidence, (value) => {
    value.rows[1].id = value.rows[0].id;
  }, /row IDs must match the frozen order exactly/);
  expectResidualInvalid('reordered-ids', residualEvidence, (value) => {
    [value.rows[0], value.rows[1]] = [value.rows[1], value.rows[0]];
  }, /row IDs must match the frozen order exactly/);
  expectResidualInvalid('missing-row-key', residualEvidence, (value) => {
    delete value.rows[0].rationale;
  }, /MCP-C residual row 0 keys must be exactly/);
  expectResidualInvalid('extra-row-key', residualEvidence, (value) => {
    value.rows[0].extra = true;
  }, /MCP-C residual row 0 keys must be exactly/);
  expectResidualInvalid('reordered-row-key', residualEvidence, (value) => {
    const id = value.rows[0].id;
    delete value.rows[0].id;
    value.rows[0].id = id;
  }, /MCP-C residual row 0 keys must be exactly/);
  expectResidualInvalid('incorrect-owner', residualEvidence, (value) => {
    value.rows[0].owningPackage = 'MCP-7';
  }, /residual owningPackage must be MCP-C/);
  expectResidualInvalid('disposition-drift', residualEvidence, (value) => {
    value.rows[0].targetDisposition = 'CORE_COMPLETE';
  }, /residual targetDisposition must be RELEASE_GATED/);
  expectResidualInvalid('evidence-drift', residualEvidence, (value) => {
    value.rows[0].evidencePaths.push('pom.xml');
    value.rows[0].evidencePaths.sort();
  }, /residual evidencePaths must match the frozen order exactly/);
  expectResidualInvalid('documentation-drift', residualEvidence, (value) => {
    value.rows[0].documentationPaths.push('SECURITY.md');
    value.rows[0].documentationPaths.sort();
  }, /residual documentationPaths must match the frozen order exactly/);
  expectResidualInvalid('gate-drift', residualEvidence, (value) => {
    value.rows[0].releaseGates.pop();
  }, /residual releaseGates must match the frozen order exactly/);
  expectResidualInvalid('nonexistent-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = ['does-not-exist.residual-evidence'];
  }, /evidence reference does not exist/);
  expectResidualInvalid('absolute-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = ['/tmp/external-residual-evidence'];
  }, /not a normalized candidate-relative path/);
  expectResidualInvalid('parent-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = ['../mcp/PROFILE_1_NUMERIC_BOUNDS.md'];
  }, /not a normalized candidate-relative path/);
  expectResidualInvalid('directory-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = ['src'];
  }, /must name a regular file/);
  expectResidualInvalid('duplicate-evidence-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths.push(value.rows[0].evidencePaths[0]);
  }, /evidencePaths must not contain duplicates/);
  expectResidualInvalid('unsorted-evidence-paths', residualEvidence, (value) => {
    value.rows[0].evidencePaths.reverse();
  }, /evidencePaths must be in ASCII order/);
  expectResidualInvalid('non-markdown-documentation', residualEvidence, (value) => {
    value.rows[0].documentationPaths = ['pom.xml'];
  }, /must name a Markdown document/);
  expectResidualInvalid('evidence-documentation-overlap', residualEvidence, (value) => {
    value.rows[0].evidencePaths.push('MCP.md');
    value.rows[0].evidencePaths.sort();
  }, /evidencePaths must match the frozen order exactly/);
  expectResidualInvalid('blank-ownership-boundary', residualEvidence, (value) => {
    value.rows[0].ownershipBoundary = ' ';
  }, /ownershipBoundary must be a nonblank single-line string/);
  expectResidualInvalid('multiline-ownership-boundary', residualEvidence, (value) => {
    value.rows[0].ownershipBoundary = 'first\nsecond';
  }, /ownershipBoundary must be a nonblank single-line string/);
  expectResidualInvalid('blank-rationale', residualEvidence, (value) => {
    value.rows[0].rationale = '';
  }, /rationale must be a nonblank single-line string/);
  expectResidualInvalid('duplicate-ownership-boundary', residualEvidence, (value) => {
    value.rows[1].ownershipBoundary = value.rows[0].ownershipBoundary;
  }, /ownershipBoundary must be row-specific/);
  expectResidualInvalid('duplicate-rationale', residualEvidence, (value) => {
    value.rows[1].rationale = value.rows[0].rationale;
  }, /rationale must be row-specific/);
  expectResidualInvalid('balanced-residual-prose-swap', residualEvidence, (value) => {
    const first = value.rows[0];
    const second = value.rows[1];
    for (const field of ['ownershipBoundary', 'rationale']) {
      [first[field], second[field]] = [second[field], first[field]];
    }
  }, /MCP-C residual semantic attribution SHA-256 differs from the reviewed contract/);
  expectResidualInvalid('cross-row-evidence-substitution', residualEvidence, (value) => {
    [value.rows[0].evidencePaths, value.rows[3].evidencePaths] = [
      value.rows[3].evidencePaths,
      value.rows[0].evidencePaths,
    ];
  }, /SOK-VALID-002 residual evidencePaths must match the frozen order exactly/);
  expectResidualInvalid('cross-row-documentation-substitution', residualEvidence, (value) => {
    [value.rows[1].documentationPaths, value.rows[2].documentationPaths] = [
      value.rows[2].documentationPaths,
      value.rows[1].documentationPaths,
    ];
  }, /SOK-STATE-002 residual documentationPaths must match the frozen order exactly/);
  expectInvalid('residual-registry-union-drift', resolvedRegistry, (value) => {
    row(value, 'SOK-STATE-002').evidence.push('pom.xml');
    row(value, 'SOK-STATE-002').evidence.sort();
  }, /SOK-STATE-002 residual evidence\/documentation union must match the frozen order exactly/);
  expectInvalid('residual-registry-gate-drift', resolvedRegistry, (value) => {
    const target = row(value, 'SOK-VALID-002');
    target.releaseGates.push('candidate-conformance');
    target.reason =
      'Remaining immutable or scheduled evidence is owned by: fuzz-nightly-history, soak-nightly-history, release-soak, candidate-conformance.';
  }, /SOK-VALID-002 residual\/registry releaseGates must match the frozen order exactly/);
  expectInvalid('residual-registry-reason-drift', resolvedRegistry, (value) => {
    row(value, 'SOK-VALID-002').reason = 'Invented release-gated reason.';
  }, /closure-registry reason does not match its target disposition and gates/);

  const limitsAuthorityRoot = join(temporaryRoot, 'limits-authority-project');
  const limitsAuthorityPath = join(
    limitsAuthorityRoot,
    'conformance/mcp-limits-and-accounting.json',
  );
  mkdirSync(dirname(limitsAuthorityPath), { recursive: true });
  const limitsArtifact = JSON.parse(readFileSync(
    join(projectRoot, 'conformance/mcp-limits-and-accounting.json'),
    'utf8',
  ));
  const writeLimitsAuthority = (value) => {
    writeFileSync(limitsAuthorityPath, canonicalJson(value));
  };
  const expectLimitsAuthorityInvalid = (name, mutate, expected) => {
    const value = clone(limitsArtifact);
    mutate(value);
    writeLimitsAuthority(value);
    assert.throws(
      () => verifyLimitsAccountingAuthority({ projectRoot: limitsAuthorityRoot }),
      expected,
      name,
    );
  };
  writeLimitsAuthority(limitsArtifact);
  assert.deepEqual(
    verifyLimitsAccountingAuthority({ projectRoot: limitsAuthorityRoot }),
    {
      path: '../mcp/PROFILE_1_NUMERIC_BOUNDS.md',
      sha256: '9477f26dd0d2bbc2f790b8428dd5ad5de7f9d672ba152cfd33fbbf0ae6a78b70',
    },
  );
  expectLimitsAuthorityInvalid('limits-authority-path-drift', (value) => {
    value.numericBoundsAuthority.path = '../mcp/OTHER.md';
  }, /does not match the reviewed external authority/);
  expectLimitsAuthorityInvalid('limits-authority-sha-drift', (value) => {
    value.numericBoundsAuthority.sha256 = '0'.repeat(64);
  }, /does not match the reviewed external authority/);
  expectLimitsAuthorityInvalid('limits-authority-missing-key', (value) => {
    delete value.numericBoundsAuthority.sha256;
  }, /numericBoundsAuthority keys must be exactly/);
  expectLimitsAuthorityInvalid('limits-authority-extra-key', (value) => {
    value.numericBoundsAuthority.external = true;
  }, /numericBoundsAuthority keys must be exactly/);
  writeLimitsAuthority(limitsArtifact);

  const resolvedWithFiniteBoundOverride = verifyFixture(resolvedPath, {
    finiteBoundExpectedCategories: ['SELF_TEST'],
    finiteBoundExpectedExclusionsSha256: finiteBoundFixtureExclusionsSha256,
    finiteBoundExpectedSemanticsSha256: finiteBoundFixtureSemanticsSha256,
    finiteBoundExpectedScanRoots: finiteBoundScanRoots,
    finiteBoundInventoryPath,
    finiteBoundProjectRoot,
  });
  assert.deepEqual(resolvedWithFiniteBoundOverride, resolved);

  const resolvedWithPrivacyOverride = verifyFixture(resolvedPath, {
    privacyExpectedArtifactRoots: privacyArtifactRoots,
    privacyExpectedScanRoots: privacyScanRoots,
    privacyExpectedSemanticsSha256: privacyFixtureSemanticsSha256,
    privacyInventoryPath,
    privacyProjectRoot,
  });
  assert.deepEqual(resolvedWithPrivacyOverride, resolved);

  const matrixOmittedFiniteInventory = clone(finiteInventory);
  matrixOmittedFiniteInventory.bounds[0].sourceOwners.shift();
  const matrixOmittedFiniteInventoryPath = writeFiniteBoundFixture(
    'matrix-integration-omitted-source-owner',
    matrixOmittedFiniteInventory,
  );
  assert.throws(() => verifyFixture(resolvedPath, {
    finiteBoundExpectedCategories: ['SELF_TEST'],
    finiteBoundExpectedExclusionsSha256: finiteBoundFixtureExclusionsSha256,
    finiteBoundExpectedSemanticsSha256: finiteBoundFixtureSemanticsSha256,
    finiteBoundExpectedScanRoots: finiteBoundScanRoots,
    finiteBoundInventoryPath: matrixOmittedFiniteInventoryPath,
    finiteBoundProjectRoot,
  }), /Finite-bound inventory differs from source derivation; omitted=/);

  const matrixOmittedPrivacyInventory = clone(privacyInventory);
  const omittedPrivacyBoundaryIndex = matrixOmittedPrivacyInventory.boundaries
    .findIndex(({ sourcePaths }) => sourcePaths.length > 1);
  assert.notEqual(omittedPrivacyBoundaryIndex, -1);
  matrixOmittedPrivacyInventory.boundaries[omittedPrivacyBoundaryIndex]
    .sourcePaths.shift();
  const matrixOmittedPrivacyInventoryPath = writePrivacyFixture(
    'matrix-integration-omitted-source-path',
    matrixOmittedPrivacyInventory,
  );
  assert.throws(() => verifyFixture(resolvedPath, {
    privacyExpectedArtifactRoots: privacyArtifactRoots,
    privacyExpectedScanRoots: privacyScanRoots,
    privacyInventoryPath: matrixOmittedPrivacyInventoryPath,
    privacyProjectRoot,
  }), /Privacy-boundary inventory differs from source derivation; omitted=/);

  const syntheticProgram = [
    `import { verifyMatrixClosure } from ${JSON.stringify(pathToFileURL(verifierPath).href)};`,
    `const result = verifyMatrixClosure(${JSON.stringify({
      projectRoot, gitExecutable: semanticGitExecutable(),
      manifestPath,
      privacyExpectedArtifactRoots: privacyArtifactRoots,
      privacyExpectedScanRoots: privacyScanRoots,
      privacyExpectedSemanticsSha256: privacyFixtureSemanticsSha256,
      privacyInventoryPath,
      privacyProjectRoot,
      registryPath: resolvedPath,
    })});`,
    'process.stdout.write(result.reportText);',
    'process.exitCode = result.exitCode;',
  ].join('\n');
  const syntheticCli = spawnSync(
    process.execPath,
    ['--input-type=module', '--eval', syntheticProgram],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(syntheticCli.status, 0);
  assert.equal(syntheticCli.stderr, '');
  assert.equal(syntheticCli.stdout, resolved.reportText);
  assert.equal(
    syntheticCli.stdout,
    canonicalJson(JSON.parse(syntheticCli.stdout)),
  );

  expectRawInvalid('malformed', '{\n', /malformed JSON/);
  expectRawInvalid(
    'crlf',
    canonicalJson(resolvedRegistry).replaceAll('\n', '\r\n'),
    /must use LF line endings/,
  );
  expectRawInvalid(
    'missing-lf',
    canonicalJson(resolvedRegistry).slice(0, -1),
    /must end with one LF/,
  );
  expectRawInvalid(
    'noncanonical',
    `${JSON.stringify(resolvedRegistry)}\n`,
    /not canonical two-space JSON/,
  );

  expectInvalid('missing-row', resolvedRegistry, (value) => value.rows.pop(), /exactly 263 rows/);
  expectInvalid('extra-row', resolvedRegistry, (value) => {
    value.rows.push({
      ...clone(value.rows.at(-1)),
      id: 'AMB-005',
    });
  }, /exactly 263 rows/);
  expectInvalid('duplicate-row', resolvedRegistry, (value) => {
    value.rows[1].id = value.rows[0].id;
  }, /duplicate row ID/);
  expectInvalid('renamed-row', resolvedRegistry, (value) => {
    value.rows[0].id = 'MCP-BASE-999';
  }, /missing, extra, renamed, or out of frozen order/);
  expectInvalid('reordered-row', resolvedRegistry, (value) => {
    [value.rows[0], value.rows[1]] = [value.rows[1], value.rows[0]];
  }, /missing, extra, renamed, or out of frozen order/);

  for (const disposition of ['AMBIGUOUS', 'PLANNED', 'UNCLASSIFIED']) {
    expectInvalid(`unknown-${disposition.toLowerCase()}`, resolvedRegistry, (value) => {
      value.rows[0].disposition = disposition;
    }, /unknown disposition/);
  }
  expectInvalid('final-disposition-count-drift', resolvedRegistry, (value) => {
    value.rows[0].disposition = 'RELEASE_GATED';
    value.rows[0].releaseGates = ['candidate-build'];
    value.rows[0].reason =
      'Remaining immutable or scheduled evidence is owned by: candidate-build.';
  }, /final disposition CORE_COMPLETE must equal 113/);
  expectInvalid('balanced-row-attribution-swap', resolvedRegistry, (value) => {
    const coreComplete = row(value, 'MCP-BASE-001');
    const releaseGated = row(value, 'MCP-BASE-012');
    for (const field of ['disposition', 'releaseGates', 'reason']) {
      [coreComplete[field], releaseGated[field]] = [
        releaseGated[field],
        coreComplete[field],
      ];
    }
  }, /Matrix-closure row attribution SHA-256 differs from the reviewed contract/);
  expectInvalid('balanced-non-residual-evidence-swap', resolvedRegistry, (value) => {
    const first = row(value, 'MCP-BASE-001');
    const second = row(value, 'MCP-BASE-003');
    [first.evidence, second.evidence] = [second.evidence, first.evidence];
  }, /Matrix-closure row attribution SHA-256 differs from the reviewed contract/);
  expectInvalid('not-applicable-mismatch', resolvedRegistry, (value) => {
    const target = row(value, 'MCP-BASE-027');
    target.disposition = 'CORE_COMPLETE';
  }, /frozen NOT_APPLICABLE classification/);
  expectInvalid('application-owned-mismatch', resolvedRegistry, (value) => {
    const target = row(value, 'MCP-AUTH-002');
    target.disposition = 'CORE_COMPLETE';
    target.reason = '';
  }, /frozen APPLICATION_OWNED classification/);
  expectInvalid('invented-application-owner', resolvedRegistry, (value) => {
    value.rows[0].disposition = 'APPLICATION_OWNED';
    value.rows[0].reason = 'Invented delegation.';
  }, /frozen APPLICATION_OWNED classification/);

  expectInvalid('empty-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [];
  }, /at least one evidence reference/);
  expectInvalid('empty-evidence-ref', resolvedRegistry, (value) => {
    value.rows[0].evidence = [''];
  }, /empty evidence reference/);
  expectInvalid('missing-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['does-not-exist.matrix-closure'];
  }, /evidence reference does not exist/);
  expectInvalid('directory-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['src'];
  }, /must name a regular file/);
  expectInvalid('target-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['target/generated-evidence.json'];
  }, /not a normalized candidate-relative path/);
  expectInvalid('git-admin-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['.git/config'];
  }, /not a normalized candidate-relative path/);
  expectInvalid('parent-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['../mcp/MCP_CONFORMANCE_MATRIX.md'];
  }, /not a normalized candidate-relative path/);
  expectInvalid('absolute-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['/tmp/matrix-closure-evidence'];
  }, /not a normalized candidate-relative path/);
  writeFileSync(untrackedEvidencePath, 'untracked\n', { flag: 'wx' });
  expectResidualInvalid('untracked-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = [untrackedEvidenceName];
  }, /not tracked by the candidate/, { gitExecutable: 'git' });
  expectInvalid('untracked-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [untrackedEvidenceName];
  }, /not tracked by the candidate/, { gitExecutable: 'git' });
  symlinkSync('MCP.md', symlinkEvidencePath);
  expectResidualInvalid('symlink-candidate-path', residualEvidence, (value) => {
    value.rows[0].evidencePaths = [symlinkEvidenceName];
  }, /contains a symlink/, { gitExecutable: 'git' });
  expectInvalid('symlink-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [symlinkEvidenceName];
  }, /contains a symlink/, { gitExecutable: 'git' });
  expectInvalid('duplicate-evidence', resolvedRegistry, (value) => {
    const reference = value.rows[0].evidence[0];
    value.rows[0].evidence = [reference, reference];
  }, /duplicate evidence references/);
  expectInvalid('unsorted-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [
      'src/test/java/com/soklet/McpWireDtoSketchTests.java',
      'MCP.md',
    ];
  }, /evidence references must be in ASCII order/);
  expectInvalid('prose-only-core-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = ['MCP.md'];
  }, /requires substantive implementation, test, or harness evidence/);
  assert.throws(
    () => verifyFixture(resolvedPath, { gitExecutable: '/missing/git-for-self-test' }),
    /Unable to inspect candidate evidence tracking/,
  );

  expectInvalid('unknown-release-gate', resolvedRegistry, (value) => {
    row(value, 'MCP-BASE-019').releaseGates = ['unknown-gate'];
  }, /depends on unknown release gate/);
  expectInvalid('self-release-gate', resolvedRegistry, (value) => {
    row(value, 'MCP-BASE-019').releaseGates = ['matrix-closure'];
  }, /may not depend on the matrix-closure gate itself/);
  expectInvalid('duplicate-release-gate', resolvedRegistry, (value) => {
    row(value, 'MCP-BASE-019').releaseGates = ['candidate-build', 'candidate-build'];
  }, /duplicate release-gate dependencies/);
  expectInvalid('reordered-release-gates', resolvedRegistry, (value) => {
    row(value, 'MCP-BASE-019').releaseGates = ['core-jdk-21', 'candidate-build'];
  }, /must follow manifest order/);
  expectInvalid('empty-release-gates', resolvedRegistry, (value) => {
    row(value, 'MCP-BASE-019').releaseGates = [];
  }, /RELEASE_GATED requires a release-gate dependency/);
  expectInvalid('gate-on-core-row', resolvedRegistry, (value) => {
    value.rows[0].releaseGates = ['candidate-build'];
  }, /CORE_COMPLETE may not name release gates/);
  expectInvalid('empty-unresolved-reason', resolvedRegistry, (value) => {
    value.rows[0].disposition = 'UNRESOLVED';
    value.rows[0].reason = '';
  }, /UNRESOLVED requires a reason/);
  expectInvalid('reason-on-core', resolvedRegistry, (value) => {
    value.rows[0].reason = 'Not allowed.';
  }, /CORE_COMPLETE requires an empty reason/);

  expectInvalid('release-version-drift', resolvedRegistry, (value) => {
    value.releaseVersion = '3.6.1';
  }, /releaseVersion does not match manifest candidate.version/);
  expectInvalid('source-sha-drift', resolvedRegistry, (value) => {
    value.sourceMatrixSha256 = '0'.repeat(64);
  }, /source-matrix provenance does not match/);
  expectInvalid('source-date-drift', resolvedRegistry, (value) => {
    value.sourceMatrixLastUpdated = '2026-08-19';
  }, /source-matrix provenance does not match/);
  expectInvalid('source-path-drift', resolvedRegistry, (value) => {
    value.sourceMatrixPath = '../mcp/MCP_CONFORMANCE_MATRIX.md';
  }, /source-matrix provenance does not match/);
  expectInvalid('extra-top-level-key', resolvedRegistry, (value) => {
    value.extra = true;
  }, /keys must be exactly/);
  expectInvalid('extra-row-key', resolvedRegistry, (value) => {
    value.rows[0].extra = true;
  }, /keys must be exactly/);

  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const versionDriftManifest = clone(manifest);
  versionDriftManifest.candidate.version = '3.6.1';
  const versionDriftManifestPath = writeCanonicalFixture(
    'manifest-version-drift',
    versionDriftManifest,
  );
  assert.throws(
    () => verifyFixture(resolvedPath, { manifestPath: versionDriftManifestPath }),
    /releaseVersion does not match manifest candidate.version/,
  );
  const reorderedManifest = clone(manifest);
  [reorderedManifest.gates[0], reorderedManifest.gates[1]] = [
    reorderedManifest.gates[1],
    reorderedManifest.gates[0],
  ];
  const reorderedManifestPath = writeCanonicalFixture(
    'manifest-gates-reordered',
    reorderedManifest,
  );
  assert.throws(
    () => verifyFixture(resolvedPath, { manifestPath: reorderedManifestPath }),
    /Release manifest gate IDs must match the frozen order exactly/,
  );

  process.stdout.write('Matrix closure verifier self-test passed.\n');
} finally {
  rmSync(untrackedEvidencePath, { force: true });
  rmSync(symlinkEvidencePath, { force: true });
  rmSync(temporaryRoot, { force: true, recursive: true });
}

function semanticGitExecutable() {
  return join(temporaryRoot, 'git');
}

function prepareSemanticGit(nextPath) {
  writeFileSync(semanticGitExecutable(), `#!/bin/sh
test "$#" -eq 8 \\
  && test "$1" = "-c" \\
  && test "$2" = "safe.directory=$4" \\
  && test "$3" = "-C" \\
  && test "$5" = "ls-files" \\
  && test "$6" = "--error-unmatch" \\
  && test "$7" = "--" \\
  && test -n "$8" \\
  || exit 64
`, { mode: 0o700 });
  return nextPath;
}

function semanticGitEnvironment() {
  return {
    ...process.env,
    PATH: `${temporaryRoot}${delimiter}${process.env.PATH ?? ''}`,
  };
}
