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
  canonicalJson,
  deriveFiniteBoundCandidates,
  verifyFiniteBoundInventory,
  verifyMatrixClosure,
} from './verify-release-matrix-closure.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const registryPath = join(projectRoot, 'release/mcp-conformance-matrix-closure.json');
const manifestPath = join(projectRoot, 'release/release-validation-manifest.json');
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

const expectedUnresolvedIds = Object.freeze([
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
    ...(options.gitExecutable === undefined
      ? { gitExecutable: semanticGitExecutable() }
      : { gitExecutable: options.gitExecutable }),
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
        boundaryTests: [],
        category: 'SELF_TEST',
        deterministicFailure: {
          contract: 'Synthetic deterministic failure contract.',
          stage: 'SELF_TEST',
        },
        enforcementOwners: [],
        id: 'FINITE-SELF-001',
        name: 'Synthetic finite-bound scanner coverage',
        positiveTests: [],
        sourceOwners: candidates
          .map(finiteBoundClassification)
          .sort((left, right) => left.key < right.key ? -1 : left.key > right.key ? 1 : 0),
        values: {},
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

function verifyFiniteBoundFixture(path) {
  return verifyFiniteBoundInventory({
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
  writeFileSync(finiteBoundInventoryPath, canonicalJson(finiteInventory));
  const finiteBaseline = verifyFiniteBoundFixture(finiteBoundInventoryPath);
  assert.deepEqual(finiteBaseline.candidates, finiteBoundCandidates);
  assert.deepEqual(finiteBaseline.exclusions, []);

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

  const finiteInventoryWithExclusion = clone(finiteInventory);
  const [excludedOwner] = finiteInventoryWithExclusion.bounds[0].sourceOwners.splice(0, 1);
  finiteInventoryWithExclusion.reviewedExclusions = [
    reviewedExclusion(excludedOwner),
  ];
  const finiteExclusionPath = writeFiniteBoundFixture(
    'exact-reviewed-exclusion',
    finiteInventoryWithExclusion,
  );
  const finiteExclusionResult = verifyFiniteBoundFixture(finiteExclusionPath);
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

  const registryBytes = readFileSync(prepareSemanticGit(registryPath));
  const registryText = registryBytes.toString('utf8');
  const registry = JSON.parse(registryText);
  assert.equal(registryText, canonicalJson(registry));

  const current = verifyFixture(registryPath);
  assert.equal(current.exitCode, 1);
  assert.equal(current.report.status, 'FAILED');
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
  assert.deepEqual(current.report.dispositionCounts, {
    APPLICATION_OWNED: 12,
    CORE_COMPLETE: 110,
    NOT_APPLICABLE: 19,
    RELEASE_GATED: 117,
    UNRESOLVED: 5,
  });
  assert.deepEqual(
    current.report.unresolvedRows.map(({ id }) => id),
    expectedUnresolvedIds,
  );
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
  assert.equal(row(registry, 'SOK-VALID-002').disposition, 'UNRESOLVED');
  assert.ok(new Set(registry.rows.flatMap(({ evidence }) => evidence)).size >= 160);

  const checkedInCli = spawnSync(process.execPath, [verifierPath], {
    cwd: projectRoot,
    encoding: 'utf8', env: semanticGitEnvironment(),
  });
  assert.equal(checkedInCli.status, 1);
  assert.equal(checkedInCli.stdout, current.reportText);
  assert.equal(
    checkedInCli.stderr,
    'Matrix closure failed: 5 unresolved row(s).\n',
  );

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

  const resolvedRegistry = clone(registry);
  for (const unresolvedRow of resolvedRegistry.rows) {
    if (unresolvedRow.disposition !== 'UNRESOLVED') {
      continue;
    }
    unresolvedRow.disposition = 'CORE_COMPLETE';
    unresolvedRow.reason = '';
    if (unresolvedRow.evidence.every((path) => path.endsWith('.md'))) {
      unresolvedRow.evidence = ['conformance/official/verify.mjs'];
    }
  }
  const resolvedPath = writeCanonicalFixture('resolved', resolvedRegistry);
  const resolved = verifyFixture(resolvedPath);
  assert.equal(resolved.exitCode, 0);
  assert.equal(resolved.report.status, 'PASSED');
  assert.deepEqual(resolved.report.dispositionCounts, {
    APPLICATION_OWNED: 12,
    CORE_COMPLETE: 115,
    NOT_APPLICABLE: 19,
    RELEASE_GATED: 117,
    UNRESOLVED: 0,
  });
  assert.equal(
    resolved.report.dispositionCounts.CORE_COMPLETE,
    current.report.dispositionCounts.CORE_COMPLETE + expectedUnresolvedIds.length,
  );
  assert.deepEqual(resolved.report.unresolvedRows, []);
  assert.deepEqual(resolved.report.rows, resolvedRegistry.rows);
  assert.equal(resolved.reportText, canonicalJson(resolved.report));

  const resolvedWithFiniteBoundOverride = verifyFixture(resolvedPath, {
    finiteBoundExpectedScanRoots: finiteBoundScanRoots,
    finiteBoundInventoryPath,
    finiteBoundProjectRoot,
  });
  assert.deepEqual(resolvedWithFiniteBoundOverride, resolved);

  const matrixOmittedFiniteInventory = clone(finiteInventory);
  matrixOmittedFiniteInventory.bounds[0].sourceOwners.shift();
  const matrixOmittedFiniteInventoryPath = writeFiniteBoundFixture(
    'matrix-integration-omitted-source-owner',
    matrixOmittedFiniteInventory,
  );
  assert.throws(() => verifyFixture(resolvedPath, {
    finiteBoundExpectedScanRoots: finiteBoundScanRoots,
    finiteBoundInventoryPath: matrixOmittedFiniteInventoryPath,
    finiteBoundProjectRoot,
  }), /Finite-bound inventory differs from source derivation; omitted=/);

  const syntheticProgram = [
    `import { verifyMatrixClosure } from ${JSON.stringify(pathToFileURL(verifierPath).href)};`,
    `const result = verifyMatrixClosure(${JSON.stringify({
      projectRoot, gitExecutable: semanticGitExecutable(),
      manifestPath,
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
  expectInvalid('untracked-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [untrackedEvidenceName];
  }, /not tracked by the candidate/, { gitExecutable: 'git' });
  symlinkSync('MCP.md', symlinkEvidencePath);
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
