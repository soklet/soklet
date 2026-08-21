#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  canonicalJson,
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

const expectedUnresolvedIds = Object.freeze([
  'MCP-BASE-005',
  'MCP-BASE-011',
  'MCP-BASE-015',
  'MCP-VER-004',
  'MCP-HTTP-020',
  'MCP-HTTP-024',
  'MCP-HTTP-025',
  'MCP-MRTR-011',
  'MCP-PROMPT-006',
  'MCP-RESOURCE-006',
  'MCP-RESOURCE-007',
  'MCP-PAGE-004',
  'MCP-PAGE-006',
  'MCP-PAGE-007',
  'SOK-EXEC-005',
  'SOK-VALID-001',
  'SOK-VALID-002',
  'SOK-ERROR-002',
  'SOK-STATE-002',
  'SOK-STATE-007',
  'SOK-PRIV-001',
  'SOK-SIM-001',
  'SOK-L10N-007',
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
    ...(options.gitExecutable === undefined
      ? {}
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

try {
  const registryBytes = readFileSync(registryPath);
  const registryText = registryBytes.toString('utf8');
  const registry = JSON.parse(registryText);
  assert.equal(registryText, canonicalJson(registry));

  const current = verifyFixture(registryPath);
  assert.equal(current.exitCode, 1);
  assert.equal(current.report.status, 'FAILED');
  assert.deepEqual(Object.keys(current.report), expectedReportKeys);
  assert.equal(current.report.rowCount, 262);
  assert.equal(
    current.report.rowIdsSha256,
    'ce16b46738d8033db5770d91c8adfd02ab6894a5dbf7ce80f588c2c3e018b015',
  );
  assert.equal(
    current.report.registrySha256,
    createHash('sha256').update(registryBytes).digest('hex'),
  );
  assert.deepEqual(current.report.dispositionCounts, {
    APPLICATION_OWNED: 4,
    CORE_COMPLETE: 100,
    NOT_APPLICABLE: 18,
    RELEASE_GATED: 116,
    UNRESOLVED: 24,
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
    'MCP-BASE-024',
    'MCP-VER-003',
    'MCP-HTTP-018',
    'MCP-AUTH-003',
    'MCP-HTTP-021',
    'SOK-ERROR-001',
    'SOK-RATE-006',
    'SOK-RATE-007',
    'SOK-CORS-005',
  ]) {
    assert.equal(row(registry, id).disposition, 'CORE_COMPLETE');
    assert.deepEqual(row(registry, id).releaseGates, []);
    assert.equal(row(registry, id).reason, '');
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
  assert.deepEqual(row(registry, 'SOK-METRIC-001').releaseGates, [
    'release-soak',
    'operational-history',
    'soklet-otel',
  ]);
  assert.equal(row(registry, 'SOK-VALID-002').disposition, 'UNRESOLVED');
  assert.ok(new Set(registry.rows.flatMap(({ evidence }) => evidence)).size >= 160);

  const checkedInCli = spawnSync(process.execPath, [verifierPath], {
    cwd: projectRoot,
    encoding: 'utf8',
  });
  assert.equal(checkedInCli.status, 1);
  assert.equal(checkedInCli.stdout, current.reportText);
  assert.equal(
    checkedInCli.stderr,
    'Matrix closure failed: 24 unresolved row(s).\n',
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
    APPLICATION_OWNED: 4,
    CORE_COMPLETE: 124,
    NOT_APPLICABLE: 18,
    RELEASE_GATED: 116,
    UNRESOLVED: 0,
  });
  assert.deepEqual(resolved.report.unresolvedRows, []);
  assert.deepEqual(resolved.report.rows, resolvedRegistry.rows);
  assert.equal(resolved.reportText, canonicalJson(resolved.report));

  const syntheticProgram = [
    `import { verifyMatrixClosure } from ${JSON.stringify(pathToFileURL(verifierPath).href)};`,
    `const result = verifyMatrixClosure(${JSON.stringify({
      projectRoot,
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

  expectInvalid('missing-row', resolvedRegistry, (value) => value.rows.pop(), /exactly 262 rows/);
  expectInvalid('extra-row', resolvedRegistry, (value) => {
    value.rows.push({
      ...clone(value.rows.at(-1)),
      id: 'AMB-005',
    });
  }, /exactly 262 rows/);
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
  }, /not tracked by the candidate/);
  symlinkSync('MCP.md', symlinkEvidencePath);
  expectInvalid('symlink-evidence', resolvedRegistry, (value) => {
    value.rows[0].evidence = [symlinkEvidenceName];
  }, /contains a symlink/);
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
