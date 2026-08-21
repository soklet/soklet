#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  readFileSync,
  realpathSync,
} from 'node:fs';
import {
  dirname,
  isAbsolute,
  posix,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const EXPECTED_FORMAT_VERSION = 1;
const EXPECTED_PROTOCOL_VERSION = '2026-07-28';
const EXPECTED_SOURCE_MATRIX_PATH = 'mcp/MCP_CONFORMANCE_MATRIX.md';
const EXPECTED_SOURCE_MATRIX_LAST_UPDATED = '2026-08-21';
const EXPECTED_SOURCE_MATRIX_SHA256 =
  'b0415d770f2e2452a1a512848275cec9543cbf1ee61cbe3a78d811ee134b29ff';
const EXPECTED_ROW_COUNT = 262;
const EXPECTED_ROW_IDS_SHA256 =
  'ce16b46738d8033db5770d91c8adfd02ab6894a5dbf7ce80f588c2c3e018b015';
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ROW_ID_PATTERN = /^(?:MCP-[A-Z0-9]+-\d{3}|SOK-[A-Z0-9]+-\d{3}|AMB-\d{3})$/;
const DISPOSITIONS = Object.freeze([
  'APPLICATION_OWNED',
  'CORE_COMPLETE',
  'NOT_APPLICABLE',
  'RELEASE_GATED',
  'UNRESOLVED',
]);
const DISPOSITION_SET = new Set(DISPOSITIONS);
const EXPECTED_GATE_IDS = Object.freeze([
  'candidate-build',
  'core-jdk-21',
  'core-jdk-25',
  'isolated-install',
  'api-freeze',
  'candidate-javadocs',
  'static-analysis',
  'spotbugs',
  'schema-replay',
  'fuzz-replay',
  'fuzz-nightly-history',
  'soak-smoke',
  'soak-nightly-history',
  'release-soak',
  'localization-fleet',
  'operational-history',
  'release-scans',
  'mcp-benchmarks',
  'matrix-closure',
  'candidate-conformance',
  'candidate-localization',
  'barebones-app',
  'soklet-servlet-javax',
  'soklet-servlet-jakarta',
  'toystore-app',
  'soklet-otel',
  'soklet-website',
  'typescript-interop',
  'go-interop',
]);
const EXPECTED_NOT_APPLICABLE_IDS = new Set([
  'MCP-BASE-027',
  'MCP-VER-005',
  'MCP-CAP-003',
  'MCP-HTTP-017',
  'MCP-TOOL-008',
  'MCP-AUTH-001',
  'MCP-AUTH-008',
  'MCP-AUTH-009',
  'SOK-RATE-002',
  'SOK-NA-001',
  'SOK-NA-002',
  'SOK-NA-003',
  'SOK-NA-004',
  'SOK-NA-005',
  'SOK-NA-006',
  'SOK-NA-007',
  'SOK-NA-008',
  'SOK-NA-009',
]);
const EXPECTED_APPLICATION_OWNED_IDS = new Set([
  'MCP-AUTH-002',
  'MCP-AUTH-007',
  'MCP-ELICIT-003',
  'AMB-004',
]);

const TOP_LEVEL_KEYS = Object.freeze([
  'formatVersion',
  'protocolVersion',
  'releaseVersion',
  'sourceMatrixPath',
  'sourceMatrixLastUpdated',
  'sourceMatrixSha256',
  'releaseGateUniverse',
  'rows',
]);
const ROW_KEYS = Object.freeze([
  'id',
  'disposition',
  'evidence',
  'releaseGates',
  'reason',
]);
const TRACKED_REFERENCE_CACHE = new Map();

export class MatrixClosureVerificationError extends Error {}

function fail(message) {
  throw new MatrixClosureVerificationError(message);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertExactKeys(value, expected, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = Object.keys(value);
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    fail(`${label} keys must be exactly: ${expected.join(', ')}.`);
  }
}

function assertExactArray(actual, expected, label) {
  if (!Array.isArray(actual)
      || actual.length !== expected.length
      || actual.some((value, index) => value !== expected[index])) {
    fail(`${label} must match the frozen order exactly.`);
  }
}

export function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function readCanonicalJson(file, label) {
  if (!existsSync(file)) {
    fail(`${label} does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`${label} must be a regular non-symlink file: ${file}`);
  }
  const bytes = readFileSync(file);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes)) {
    fail(`${label} is not valid UTF-8.`);
  }
  if (text.includes('\r')) {
    fail(`${label} must use LF line endings.`);
  }
  if (!text.endsWith('\n')) {
    fail(`${label} must end with one LF.`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is malformed JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text) {
    fail(`${label} is not canonical two-space JSON.`);
  }
  return { bytes, value };
}

function readManifest(file) {
  if (!existsSync(file)) {
    fail(`Release manifest does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`Release manifest must be a regular non-symlink file: ${file}`);
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`Release manifest is malformed JSON: ${error.message}`);
  }
  const releaseVersion = manifest?.candidate?.version;
  if (typeof releaseVersion !== 'string' || releaseVersion.length === 0) {
    fail('Release manifest candidate.version must be a nonempty string.');
  }
  if (!Array.isArray(manifest.gates)) {
    fail('Release manifest gates must be an array.');
  }
  const gateIds = manifest.gates.map((gate, index) => {
    if (gate === null || typeof gate !== 'object' || Array.isArray(gate)
        || typeof gate.id !== 'string' || gate.id.length === 0) {
      fail(`Release manifest gate ${index} has no valid id.`);
    }
    return gate.id;
  });
  if (new Set(gateIds).size !== gateIds.length) {
    fail('Release manifest contains duplicate gate IDs.');
  }
  assertExactArray(gateIds, EXPECTED_GATE_IDS, 'Release manifest gate IDs');
  return { releaseVersion, gateIds };
}

function assertContainedEvidence(projectRoot, reference, rowId, gitExecutable) {
  if (typeof reference !== 'string' || reference.length === 0) {
    fail(`Row ${rowId} contains an empty evidence reference.`);
  }
  if (reference.includes('\\') || isAbsolute(reference)
      || posix.normalize(reference) !== reference
      || reference === '.' || reference.startsWith('../')
      || reference.includes('/../')
      || reference === '.git' || reference.startsWith('.git/')
      || reference === 'target' || reference.startsWith('target/')) {
    fail(`Row ${rowId} evidence reference is not a normalized candidate-relative path: ${reference}`);
  }
  const target = resolve(projectRoot, reference);
  const lexicalRelative = relative(projectRoot, target);
  if (lexicalRelative === '..' || lexicalRelative.startsWith(`..${sep}`) || isAbsolute(lexicalRelative)) {
    fail(`Row ${rowId} evidence reference escapes the candidate: ${reference}`);
  }
  if (!existsSync(target)) {
    fail(`Row ${rowId} evidence reference does not exist: ${reference}`);
  }
  let component = projectRoot;
  for (const segment of reference.split('/')) {
    component = resolve(component, segment);
    if (lstatSync(component).isSymbolicLink()) {
      fail(`Row ${rowId} evidence reference contains a symlink: ${reference}`);
    }
  }
  const stat = lstatSync(target);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    fail(`Row ${rowId} evidence reference must name a regular file: ${reference}`);
  }
  const realRoot = realpathSync(projectRoot);
  const realTarget = realpathSync(target);
  const realRelative = relative(realRoot, realTarget);
  if (realRelative === '..' || realRelative.startsWith(`..${sep}`) || isAbsolute(realRelative)) {
    fail(`Row ${rowId} evidence reference resolves outside the candidate: ${reference}`);
  }
  const cacheKey = `${gitExecutable}\0${projectRoot}\0${reference}`;
  let tracked = TRACKED_REFERENCE_CACHE.get(cacheKey);
  if (tracked === undefined) {
    const result = spawnSync(
      gitExecutable,
      [
        '-c',
        `safe.directory=${projectRoot}`,
        '-C',
        projectRoot,
        'ls-files',
        '--error-unmatch',
        '--',
        reference,
      ],
      { encoding: 'utf8' },
    );
    if (result.error !== undefined) {
      fail(`Unable to inspect candidate evidence tracking: ${result.error.message}`);
    }
    tracked = result.status === 0;
    TRACKED_REFERENCE_CACHE.set(cacheKey, tracked);
  }
  if (!tracked) {
    fail(`Row ${rowId} evidence reference is not tracked by the candidate: ${reference}`);
  }
}

function validateReason(row) {
  if (typeof row.reason !== 'string' || row.reason.includes('\r') || row.reason.includes('\n')) {
    fail(`Row ${row.id} reason must be a single-line string.`);
  }
  const requiresReason = row.disposition === 'APPLICATION_OWNED'
    || row.disposition === 'RELEASE_GATED'
    || row.disposition === 'UNRESOLVED';
  if (requiresReason && row.reason.trim().length === 0) {
    fail(`Row ${row.id} disposition ${row.disposition} requires a reason.`);
  }
  if (!requiresReason && row.reason !== '') {
    fail(`Row ${row.id} disposition ${row.disposition} requires an empty reason.`);
  }
  if (row.reason.length > 320) {
    fail(`Row ${row.id} reason exceeds 320 characters.`);
  }
}

function validateRegistry(registry, projectRoot, manifest, gitExecutable) {
  assertExactKeys(registry, TOP_LEVEL_KEYS, 'Matrix-closure registry');
  if (registry.formatVersion !== EXPECTED_FORMAT_VERSION) {
    fail(`Matrix-closure registry formatVersion must be ${EXPECTED_FORMAT_VERSION}.`);
  }
  if (registry.protocolVersion !== EXPECTED_PROTOCOL_VERSION) {
    fail(`Matrix-closure registry protocolVersion must be ${EXPECTED_PROTOCOL_VERSION}.`);
  }
  if (registry.releaseVersion !== manifest.releaseVersion) {
    fail('Matrix-closure registry releaseVersion does not match manifest candidate.version.');
  }
  if (registry.sourceMatrixPath !== EXPECTED_SOURCE_MATRIX_PATH
      || registry.sourceMatrixLastUpdated !== EXPECTED_SOURCE_MATRIX_LAST_UPDATED
      || registry.sourceMatrixSha256 !== EXPECTED_SOURCE_MATRIX_SHA256
      || !SHA256_PATTERN.test(registry.sourceMatrixSha256)) {
    fail('Matrix-closure registry source-matrix provenance does not match the reviewed snapshot.');
  }
  assertExactArray(
    registry.releaseGateUniverse,
    manifest.gateIds,
    'Matrix-closure registry releaseGateUniverse',
  );
  if (!Array.isArray(registry.rows)) {
    fail('Matrix-closure registry rows must be an array.');
  }
  if (registry.rows.length !== EXPECTED_ROW_COUNT) {
    fail(`Matrix-closure registry must contain exactly ${EXPECTED_ROW_COUNT} rows.`);
  }

  const rowIds = [];
  const seenIds = new Set();
  const unresolvedRows = [];
  const dispositionCounts = Object.fromEntries(DISPOSITIONS.map((value) => [value, 0]));
  const releaseGateDependencies = new Set();
  const gateOrdinals = new Map(manifest.gateIds.map((id, index) => [id, index]));

  for (const [index, row] of registry.rows.entries()) {
    assertExactKeys(row, ROW_KEYS, `Matrix-closure row ${index}`);
    if (typeof row.id !== 'string' || !ROW_ID_PATTERN.test(row.id)) {
      fail(`Matrix-closure row ${index} has a malformed ID.`);
    }
    if (seenIds.has(row.id)) {
      fail(`Matrix-closure registry contains duplicate row ID ${row.id}.`);
    }
    seenIds.add(row.id);
    rowIds.push(row.id);

    if (!DISPOSITION_SET.has(row.disposition)) {
      fail(`Row ${row.id} has unknown disposition ${String(row.disposition)}.`);
    }
    if (EXPECTED_NOT_APPLICABLE_IDS.has(row.id) !== (row.disposition === 'NOT_APPLICABLE')) {
      fail(`Row ${row.id} does not match the frozen NOT_APPLICABLE classification.`);
    }
    if (EXPECTED_APPLICATION_OWNED_IDS.has(row.id)
        !== (row.disposition === 'APPLICATION_OWNED')) {
      fail(`Row ${row.id} does not match the frozen APPLICATION_OWNED classification.`);
    }
    dispositionCounts[row.disposition] += 1;
    validateReason(row);

    if (!Array.isArray(row.evidence) || row.evidence.length === 0) {
      fail(`Row ${row.id} must have at least one evidence reference.`);
    }
    if (new Set(row.evidence).size !== row.evidence.length) {
      fail(`Row ${row.id} contains duplicate evidence references.`);
    }
    const sortedEvidence = [...row.evidence].sort(compareAscii);
    if (row.evidence.some((value, evidenceIndex) => value !== sortedEvidence[evidenceIndex])) {
      fail(`Row ${row.id} evidence references must be in ASCII order.`);
    }
    for (const reference of row.evidence) {
      assertContainedEvidence(projectRoot, reference, row.id, gitExecutable);
    }
    if ((row.disposition === 'CORE_COMPLETE' || row.disposition === 'RELEASE_GATED')
        && row.evidence.every((reference) => reference.endsWith('.md')
          || reference === 'release/release-validation-manifest.json')) {
      fail(`Row ${row.id} requires substantive implementation, test, or harness evidence.`);
    }

    if (!Array.isArray(row.releaseGates)) {
      fail(`Row ${row.id} releaseGates must be an array.`);
    }
    if (new Set(row.releaseGates).size !== row.releaseGates.length) {
      fail(`Row ${row.id} contains duplicate release-gate dependencies.`);
    }
    let priorOrdinal = -1;
    for (const gateId of row.releaseGates) {
      if (!gateOrdinals.has(gateId)) {
        fail(`Row ${row.id} depends on unknown release gate ${String(gateId)}.`);
      }
      if (gateId === 'matrix-closure') {
        fail(`Row ${row.id} may not depend on the matrix-closure gate itself.`);
      }
      const ordinal = gateOrdinals.get(gateId);
      if (ordinal <= priorOrdinal) {
        fail(`Row ${row.id} releaseGates must follow manifest order.`);
      }
      priorOrdinal = ordinal;
      releaseGateDependencies.add(gateId);
    }
    if (row.disposition === 'RELEASE_GATED' && row.releaseGates.length === 0) {
      fail(`Row ${row.id} disposition RELEASE_GATED requires a release-gate dependency.`);
    }
    if (row.disposition !== 'RELEASE_GATED' && row.releaseGates.length !== 0) {
      fail(`Row ${row.id} disposition ${row.disposition} may not name release gates.`);
    }
    if (row.disposition === 'UNRESOLVED') {
      unresolvedRows.push({ id: row.id, reason: row.reason });
    }
  }

  const rowIdsSha256 = sha256(`${rowIds.join('\n')}\n`);
  if (rowIdsSha256 !== EXPECTED_ROW_IDS_SHA256) {
    fail('Matrix-closure row IDs are missing, extra, renamed, or out of frozen order.');
  }
  const orderedDependencies = manifest.gateIds.filter((id) => releaseGateDependencies.has(id));
  return {
    dispositionCounts,
    orderedDependencies,
    rowIdsSha256,
    unresolvedRows,
  };
}

export function verifyMatrixClosure(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const registryPath = resolve(
    options.registryPath ?? resolve(projectRoot, 'release/mcp-conformance-matrix-closure.json'),
  );
  const manifestPath = resolve(
    options.manifestPath ?? resolve(projectRoot, 'release/release-validation-manifest.json'),
  );
  const manifest = readManifest(manifestPath);
  const gitExecutable = options.gitExecutable ?? 'git';
  if (typeof gitExecutable !== 'string' || gitExecutable.length === 0) {
    fail('gitExecutable must be a nonempty string.');
  }
  const { bytes, value: registry } = readCanonicalJson(
    registryPath,
    'Matrix-closure registry',
  );
  const validated = validateRegistry(registry, projectRoot, manifest, gitExecutable);
  const status = validated.unresolvedRows.length === 0 ? 'PASSED' : 'FAILED';
  const report = {
    formatVersion: EXPECTED_FORMAT_VERSION,
    protocolVersion: registry.protocolVersion,
    releaseVersion: registry.releaseVersion,
    sourceMatrixPath: registry.sourceMatrixPath,
    sourceMatrixLastUpdated: registry.sourceMatrixLastUpdated,
    sourceMatrixSha256: registry.sourceMatrixSha256,
    status,
    rowCount: registry.rows.length,
    rowIdsSha256: validated.rowIdsSha256,
    registrySha256: sha256(bytes),
    dispositionCounts: validated.dispositionCounts,
    releaseGateDependencies: validated.orderedDependencies,
    unresolvedRows: validated.unresolvedRows,
    rows: registry.rows,
  };
  return {
    exitCode: status === 'PASSED' ? 0 : 1,
    report,
    reportText: canonicalJson(report),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 2) {
    console.error('Usage: node scripts/verify-release-matrix-closure.mjs');
    process.exitCode = 2;
  } else {
    try {
      const result = verifyMatrixClosure();
      process.stdout.write(result.reportText);
      if (result.exitCode !== 0) {
        console.error(
          `Matrix closure failed: ${result.report.unresolvedRows.length} unresolved row(s).`,
        );
      }
      process.exitCode = result.exitCode;
    } catch (error) {
      console.error(`Matrix closure verification failed: ${error.message}`);
      process.exitCode = 2;
    }
  }
}
