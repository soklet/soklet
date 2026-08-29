import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { isAbsolute, relative, resolve } from 'node:path';
import {
  verifyJapicmpReportPair,
  verifyReviewedApiInventory,
  verifyReviewedApiSignatures,
  verifyReviewedSet,
} from './api-diff/japicmp-symbols.mjs';

export const CONFIG_PATH = 'release/d1p-evidence-config.json';
export const TRACKED_BLOB_PATH = 'release/d1p-tracked-blobs.sha256';
export const SEMANTIC_PATH = 'release/d1p-canonical-semantic-digests.json';
export const PREVIEW_PATH = 'target/d1p-preview-evidence.json';
export const ROOT_PATH = 'release/d1p-public-cutover-manifest.json';
export const EXTERNAL_PATH = 'mcp/SOKLET_4_0_D1P_EXTERNAL_MANIFEST.json';
export const APPROVED_PREVIEW_PATH = 'release/d1p-approved-preview.json';

const REQUIRED_COMMITTED_D1P_PATHS = [
  '.github/workflows/ci.yml',
  CONFIG_PATH,
  'release/d1p-evidence-contract.md',
  SEMANTIC_PATH,
  ROOT_PATH,
  TRACKED_BLOB_PATH,
  'scripts/generate-d1p-approved-preview.mjs',
  'scripts/d1p-evidence-lib.mjs',
  'scripts/generate-d1p-evidence.mjs',
  'scripts/release-validation-self-test.mjs',
  'scripts/validate-release-candidate.sh',
  'scripts/verify-d1p-evidence-self-test.mjs',
  'scripts/verify-d1p-evidence.mjs',
  'scripts/verify-mcp-api-freezes.sh',
];

const IMMUTABLE_SEALED_D1P_PATHS = [
  CONFIG_PATH,
  'release/d1p-evidence-contract.md',
  SEMANTIC_PATH,
  ROOT_PATH,
  TRACKED_BLOB_PATH,
  'scripts/api-diff/japicmp-symbols.mjs',
  'scripts/d1p-evidence-lib.mjs',
  'scripts/generate-d1p-approved-preview.mjs',
  'scripts/generate-d1p-evidence.mjs',
  'scripts/verify-d1p-evidence-self-test.mjs',
  'scripts/verify-d1p-evidence.mjs',
];

const HEX_40 = /^[0-9a-f]{40}$/;
const HEX_64 = /^[0-9a-f]{64}$/;
const G3_APPROVAL_REFERENCE = /^sha256:[0-9a-f]{64}$/;
const EXPECTED_CONFIG = {
  baseCoreCommit: '315b759b97a3c32b420f34c3c137d72a09db9a11',
  baseCoreTree: '270907757a4cfd6d11703838c530cd70906e7d18',
  externalEntries: [
    {
      allowedPostD2Owner: ['MCP-C', 'MCP-7'],
      baseSha256: 'ecd69178a61b1eb118334dc9ee147e7f490a99f37adcd68f7a50b9e0e04631f8',
      changeKind: 'modified',
      owner: 'MCP-4',
      path: 'mcp/MCP_CONFORMANCE_MATRIX.md',
      reason: 'Record the MCP-4 conformance disposition while preserving the MCP-C then MCP-7 writer order.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '7d65cb5ff5ef2fa5b3f5ec3187180d6b085190e0bcd1677a710700f1ad526db3',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/MCP_PUBLIC_API_SKETCH_V9.md',
      reason: 'Render the combined cutover with MCP-3B toBuilder and MCP-4 lifecycle and marker-removal attribution.',
    },
    {
      allowedPostD2Owner: ['MCP-7'],
      baseSha256: 'a06131fc939e9acdb56f7f1d465e7c8e8eec17ea320a21975e76d1f62e0e0b61',
      changeKind: 'modified',
      owner: 'MCP-4',
      path: 'mcp/README.md',
      reason: 'Render the MCP-4 API lifecycle and D1p evidence guidance before MCP-7 reconciliation.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '5eefa94ee7898f206290e3665027be29caadb39333e55a157bbeb54ded1aca20',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/README.md',
      reason: 'Reconcile the compiled sketch README with the corrected D1p API surface.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: 'c4c5185ab4950125e48f36c0f1e03350cebb8fcb65738440776f874ada917d20',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/examples/java/examples/AnnotatedCatalogServerExample.java',
      reason: 'Render the corrected D1p argument and property annotation split in the sketch example.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '70b0021d5a29255113a089f74ac1da1286a78e608e9407e76121a2a64a19eaf6',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpAdmissionContext.java',
      reason: 'Render the corrected D1p admission-context subscription URI projection.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: 'b03561ef42e85f755bc831cef65a22cf636a3553a2e50676d7633e50b500296c',
      changeKind: 'deleted',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpBooleanHint.java',
      reason: 'Remove the deferred 4.1 annotation hint type from the corrected D1p sketch.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '69f1472f944101a9338f7eeb1d4c0a108dd190c6b0e4ff4a0eadbb8c50f4903c',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpHandlerContinuation.java',
      reason: 'Render the corrected D1p continuation API without duplicate feature access.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '03243703a6628913b39d539a45f4881e3d072316b80b725e7fbfd226ed5117ea',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpHandlerInterceptor.java',
      reason: 'Render the corrected D1p interceptor signature with explicit negotiated features.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '55487f395e501b7963493c3b790652914aa3800da12dd25a68571a3de3dfb0b4',
      changeKind: 'modified',
      owner: 'MCP-4',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpLogLevel.java',
      reason: 'Render the MCP-4 Java and Javadoc deprecation-marker removal preview.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '0dabee4cd0f5bcb88564e5b07a797f61d5279dff0abc2781c167093c1afa2270',
      changeKind: 'modified',
      owner: 'MCP-4',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpRequestContext.java',
      reason: 'Render the MCP-4 request-context lifecycle documentation preview.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: 'cbff2a2a38af0dbe43411805475e1e9cf3c67a68653752321bc08ddaf25c6c2b',
      changeKind: 'modified',
      owner: 'MCP-3B',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpToolOutput.java',
      reason: 'Render the MCP-3B McpToolOutput.toBuilder preview.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: 'dd3d75ebcea18153d8e5584374b1f7785a71030cb8b1f703ff16b21da4dbcd1c',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpTool.java',
      reason: 'Remove the deferred 4.1 annotation icon and hint members from the corrected D1p sketch.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '447be7b9ed924029818968469c6130c8c8ad462e412dd28249fb66c8a036a399',
      changeKind: 'modified',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpToolArgument.java',
      reason: 'Restrict the corrected D1p tool-argument annotation to method parameters.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: null,
      changeKind: 'added',
      owner: 'U5/D1p',
      path: 'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpToolProperty.java',
      reason: 'Add the corrected D1p record-component property annotation to the compiled sketch.',
    },
    {
      allowedPostD2Owner: null,
      baseSha256: '255b729f3194c4eb441e1ae14712b06225e275290008e7b1d17c4e911391a0d0',
      changeKind: 'modified',
      owner: 'MCP-4',
      path: 'mcp/design/mcp-api-sketch/verify.sh',
      reason: 'Render the MCP-4 candidate-root lifecycle assertion and sibling-blind verification path.',
    },
  ],
  formatVersion: 1,
  previewArtifactPaths: [
    'target/japicmp/mcp-api-diff.incompatibilities.jsonl',
    'target/japicmp/mcp-api-diff.xml',
    'target/japicmp/mcp-api-freeze.xml',
    'target/soklet-4.0.0-SNAPSHOT.jar',
  ],
  protectedPostD2Paths: [
    'api/mcp/current-incompatibilities.jsonl',
    'api/mcp/non-mcp-public-api.allowlist',
    'api/mcp/phase-4.includes',
    'api/mcp/phase-4.signatures.jsonl',
    'api/mcp/phase-5.includes',
    'api/mcp/phase-5.signatures.jsonl',
    'api/mcp/phase-6.includes',
    'api/mcp/phase-6.signatures.jsonl',
    'api/mcp/provisional.includes',
    'conformance/official/build-public-fixture.sh',
    'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java',
    'conformance/official/public-fixture-src/com/soklet/conformance/transport/TransportCompositionFixture.java',
    'conformance/official/public-fixture-test-src/com/soklet/conformance/McpLocalSimulatorScenarioDriver.java',
    'conformance/official/public-fixture-test-src/com/soklet/conformance/transport/TransportCompositionFixtureContractTest.java',
    'conformance/official/run.mjs',
    'src/test/java/com/soklet/McpPublicApiReflectionContractTests.java',
  ],
  reflectionDigestSourcePath:
    'src/test/java/com/soklet/McpPublicApiReflectionContractTests.java',
  semanticExpectations: {
    allowlistCount: 39,
    incompatibilityCount: 618,
    ownerCounts: {
      'phase-4': 133,
      'phase-5': 36,
      'phase-6': 64,
      provisional: 0,
    },
    signatureCounts: {
      'phase-4': 1029,
      'phase-5': 179,
      'phase-6': 421,
    },
  },
  trackedBlobExclusions: [SEMANTIC_PATH, ROOT_PATH, TRACKED_BLOB_PATH],
};

const CONFIG_KEYS = [
  'baseCoreCommit',
  'baseCoreTree',
  'externalEntries',
  'formatVersion',
  'previewArtifactPaths',
  'protectedPostD2Paths',
  'reflectionDigestSourcePath',
  'semanticExpectations',
  'trackedBlobExclusions',
];
const EXTERNAL_CONFIG_KEYS = [
  'allowedPostD2Owner',
  'baseSha256',
  'changeKind',
  'owner',
  'path',
  'reason',
];
const EXTERNAL_MANIFEST_ENTRY_KEYS = [
  'allowedPostD2Owner',
  'baseSha256',
  'owner',
  'path',
  'previewSha256',
  'reason',
];

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function compareBytes(left, right) {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'));
}

function isPlainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

export function sortJson(value) {
  if (Array.isArray(value))
    return value.map(sortJson);
  if (isPlainObject(value)) {
    const sorted = {};
    for (const key of Object.keys(value).sort(compareBytes))
      sorted[key] = sortJson(value[key]);
    return sorted;
  }
  return value;
}

export function canonicalJson(value, { compact = false } = {}) {
  return `${JSON.stringify(sortJson(value), null, compact ? undefined : 2)}\n`;
}

function fail(message) {
  throw new Error(message);
}

function assertExactKeys(value, keys, label) {
  if (!isPlainObject(value))
    fail(`${label} must be an object`);
  const actual = Object.keys(value);
  if (actual.length !== keys.length || actual.some((key, index) => key !== keys[index]))
    fail(`${label} keys must be exactly ${keys.join(', ')} in lexicographic order`);
}

function assertArray(value, label) {
  if (!Array.isArray(value))
    fail(`${label} must be an array`);
}

function assertNonblank(value, label) {
  if (typeof value !== 'string' || value.length === 0 || value.trim() !== value)
    fail(`${label} must be a nonblank trimmed string`);
}

function assertNonnegativeInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 0)
    fail(`${label} must be a nonnegative safe integer`);
}

export function validateRelativePath(path, label = 'path') {
  assertNonblank(path, label);
  if (isAbsolute(path) || path.includes('\\') || /[\u0000-\u001f\u007f]/u.test(path))
    fail(`${label} must be a control-free POSIX relative path`);
  const parts = path.split('/');
  if (parts.some((part) => part.length === 0 || part === '.' || part === '..'))
    fail(`${label} contains an empty, dot, or parent component`);
  return path;
}

function assertSortedUniqueStrings(values, label, { nonempty = false } = {}) {
  assertArray(values, label);
  if (nonempty && values.length === 0)
    fail(`${label} must not be empty`);
  for (let index = 0; index < values.length; ++index) {
    assertNonblank(values[index], `${label}[${index}]`);
    if (index > 0 && compareBytes(values[index - 1], values[index]) >= 0)
      fail(`${label} must be bytewise sorted and unique`);
  }
}

function assertRegularFile(root, path, label = path) {
  validateRelativePath(path, label);
  assertNoSymlinkComponents(root, path, label);
  const absolute = resolve(root, path);
  const relativePath = relative(resolve(root), absolute);
  if (relativePath.startsWith('..') || isAbsolute(relativePath))
    fail(`${label} escapes its root`);
  if (!existsSync(absolute))
    fail(`Missing ${label}: ${absolute}`);
  const stats = lstatSync(absolute);
  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`${label} must be a regular non-symlink file`);
  const resolvedRoot = realpathSync(resolve(root));
  const resolvedFile = realpathSync(absolute);
  const realRelative = relative(resolvedRoot, resolvedFile);
  if (realRelative.startsWith('..') || isAbsolute(realRelative))
    fail(`${label} resolves outside its root through a symlinked parent`);
  return absolute;
}

function assertMissingPath(root, path, label = path) {
  validateRelativePath(path, label);
  assertNoSymlinkComponents(root, path, label);
  const absolute = resolve(root, path);
  const relativePath = relative(resolve(root), absolute);
  if (relativePath.startsWith('..') || isAbsolute(relativePath))
    fail(`${label} escapes its root`);
  try {
    lstatSync(absolute);
  } catch (error) {
    if (error?.code === 'ENOENT')
      return absolute;
    throw error;
  }
  fail(`${label} must be absent for a configured deletion`);
}

function assertNoSymlinkComponents(root, path, label) {
  const absoluteRoot = resolve(root);
  let current = absoluteRoot;
  for (const component of path.split('/')) {
    current = resolve(current, component);
    if (!existsSync(current))
      continue;
    const stats = lstatSync(current);
    if (stats.isSymbolicLink())
      fail(`${label} contains symlink component ${component}`);
  }
}

function preflightOutputPath(root, path, label) {
  validateRelativePath(path, label);
  const absoluteRoot = realpathSync(resolve(root));
  const parts = path.split('/');
  let current = absoluteRoot;
  for (const component of parts.slice(0, -1)) {
    current = resolve(current, component);
    if (existsSync(current)) {
      const stats = lstatSync(current);
      if (stats.isSymbolicLink() || !stats.isDirectory())
        fail(`${label} parent component ${component} must be a non-symlink directory`);
    }
  }
  const output = resolve(absoluteRoot, path);
  if (existsSync(output)) {
    const stats = lstatSync(output);
    if (stats.isSymbolicLink() || !stats.isFile())
      fail(`${label} output must be a regular non-symlink file when it exists`);
  }
  return output;
}

function createOutputParents(root, path, label) {
  const absoluteRoot = realpathSync(resolve(root));
  let current = absoluteRoot;
  for (const component of path.split('/').slice(0, -1)) {
    current = resolve(current, component);
    if (existsSync(current)) {
      const stats = lstatSync(current);
      if (stats.isSymbolicLink() || !stats.isDirectory())
        fail(`${label} parent component ${component} must be a non-symlink directory`);
    } else {
      mkdirSync(current);
    }
  }
}

export function parseCanonicalJsonBytes(bytes, label) {
  if (!Buffer.isBuffer(bytes))
    bytes = Buffer.from(bytes);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail(`${label} is not valid UTF-8`);
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is not valid JSON: ${error.message}`);
  }
  const expected = canonicalJson(value);
  if (text !== expected)
    fail(`${label} is not canonical lexicographic UTF-8/LF JSON`);
  return value;
}

function validateExternalPolicyEntry(entry, index, { manifest = false } = {}) {
  const label = `${manifest ? 'external manifest' : 'configuration'} entry ${index}`;
  assertExactKeys(
    entry,
    manifest ? EXTERNAL_MANIFEST_ENTRY_KEYS : EXTERNAL_CONFIG_KEYS,
    label,
  );
  if (entry.allowedPostD2Owner !== null) {
    assertArray(entry.allowedPostD2Owner, `${label}.allowedPostD2Owner`);
    if (entry.allowedPostD2Owner.length === 0)
      fail(`${label}.allowedPostD2Owner must not be empty`);
    const seenOwners = new Set();
    entry.allowedPostD2Owner.forEach((owner, ownerIndex) => {
      assertNonblank(owner, `${label}.allowedPostD2Owner[${ownerIndex}]`);
      if (seenOwners.has(owner))
        fail(`${label}.allowedPostD2Owner must be unique in approved package order`);
      seenOwners.add(owner);
    });
  }
  if (entry.baseSha256 !== null && !HEX_64.test(entry.baseSha256))
    fail(`${label}.baseSha256 must be null or lowercase SHA-256`);
  if (!manifest) {
    if (!['added', 'deleted', 'modified'].includes(entry.changeKind))
      fail(`${label}.changeKind must be exactly added, deleted, or modified`);
    if (entry.changeKind === 'added' && entry.baseSha256 !== null)
      fail(`${label}.baseSha256 must be null for an added path`);
    if (entry.changeKind !== 'added' && entry.baseSha256 === null)
      fail(`${label}.baseSha256 must be lowercase SHA-256 for ${entry.changeKind} path`);
  }
  assertNonblank(entry.owner, `${label}.owner`);
  validateRelativePath(entry.path, `${label}.path`);
  if (manifest) {
    if (entry.previewSha256 !== null && !HEX_64.test(entry.previewSha256))
      fail(`${label}.previewSha256 must be null or lowercase SHA-256`);
    if (entry.baseSha256 === null && entry.previewSha256 === null)
      fail(`${label} baseSha256 and previewSha256 must not both be null`);
  }
  assertNonblank(entry.reason, `${label}.reason`);
}

export function validateConfig(config) {
  assertExactKeys(config, CONFIG_KEYS, 'D1p evidence configuration');
  if (!HEX_40.test(config.baseCoreCommit))
    fail('baseCoreCommit must be 40 lowercase hexadecimal characters');
  if (!HEX_40.test(config.baseCoreTree))
    fail('baseCoreTree must be 40 lowercase hexadecimal characters');
  assertArray(config.externalEntries, 'externalEntries');
  if (config.externalEntries.length === 0)
    fail('externalEntries must not be empty');
  config.externalEntries.forEach((entry, index) =>
    validateExternalPolicyEntry(entry, index));
  const externalPaths = config.externalEntries.map((entry) => entry.path);
  assertSortedUniqueStrings(externalPaths, 'externalEntries paths', { nonempty: true });
  if (config.formatVersion !== 1)
    fail('formatVersion must be exactly 1');
  assertSortedUniqueStrings(config.previewArtifactPaths, 'previewArtifactPaths', {
    nonempty: true,
  });
  config.previewArtifactPaths.forEach((path, index) =>
    validateRelativePath(path, `previewArtifactPaths[${index}]`));
  assertSortedUniqueStrings(config.protectedPostD2Paths, 'protectedPostD2Paths', {
    nonempty: true,
  });
  config.protectedPostD2Paths.forEach((path, index) =>
    validateRelativePath(path, `protectedPostD2Paths[${index}]`));
  validateRelativePath(config.reflectionDigestSourcePath, 'reflectionDigestSourcePath');
  assertExactKeys(
    config.semanticExpectations,
    ['allowlistCount', 'incompatibilityCount', 'ownerCounts', 'signatureCounts'],
    'semanticExpectations',
  );
  assertNonnegativeInteger(
    config.semanticExpectations.allowlistCount,
    'semanticExpectations.allowlistCount',
  );
  assertNonnegativeInteger(
    config.semanticExpectations.incompatibilityCount,
    'semanticExpectations.incompatibilityCount',
  );
  assertExactKeys(
    config.semanticExpectations.ownerCounts,
    ['phase-4', 'phase-5', 'phase-6', 'provisional'],
    'semanticExpectations.ownerCounts',
  );
  assertExactKeys(
    config.semanticExpectations.signatureCounts,
    ['phase-4', 'phase-5', 'phase-6'],
    'semanticExpectations.signatureCounts',
  );
  for (const [name, count] of Object.entries(config.semanticExpectations.ownerCounts))
    assertNonnegativeInteger(count, `semanticExpectations.ownerCounts.${name}`);
  for (const [name, count] of Object.entries(config.semanticExpectations.signatureCounts))
    assertNonnegativeInteger(count, `semanticExpectations.signatureCounts.${name}`);
  assertSortedUniqueStrings(config.trackedBlobExclusions, 'trackedBlobExclusions', {
    nonempty: true,
  });
  config.trackedBlobExclusions.forEach((path, index) =>
    validateRelativePath(path, `trackedBlobExclusions[${index}]`));
  return config;
}

export function assertProductionConfig(config) {
  validateConfig(config);
  if (canonicalJson(config) !== canonicalJson(EXPECTED_CONFIG))
    fail('D1p evidence configuration does not match the frozen production contract');
  return config;
}

export function readConfig(coreRoot, { production = true } = {}) {
  const path = assertRegularFile(coreRoot, CONFIG_PATH, 'D1p evidence configuration');
  const config = parseCanonicalJsonBytes(readFileSync(path), CONFIG_PATH);
  return production ? assertProductionConfig(config) : validateConfig(config);
}

function git(coreRoot, args, options = {}) {
  try {
    return execFileSync('git', ['-C', coreRoot, ...args], {
      encoding: options.encoding ?? 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    const stderr = Buffer.isBuffer(error.stderr)
      ? error.stderr.toString('utf8')
      : (error.stderr ?? error.message);
    fail(`Git command failed (${args.join(' ')}): ${String(stderr).trim()}`);
  }
}

export function verifyBaseIdentity(coreRoot, config) {
  const actualTree = git(coreRoot, ['rev-parse', `${config.baseCoreCommit}^{tree}`]).trim();
  if (actualTree !== config.baseCoreTree)
    fail(`Accepted D1 base tree mismatch: expected ${config.baseCoreTree}, got ${actualTree}`);
  try {
    execFileSync('git', ['-C', coreRoot, 'merge-base', '--is-ancestor', config.baseCoreCommit, 'HEAD'], {
      stdio: 'ignore',
    });
  } catch {
    fail(`Accepted D1 base ${config.baseCoreCommit} is not an ancestor of HEAD`);
  }
}

function linearPosition(coreRoot, config, revision) {
  const commit = git(coreRoot, ['rev-parse', `${revision}^{commit}`]).trim();
  if (commit === config.baseCoreCommit)
    return { commit, commitCount: 0, kind: 'base' };
  const firstParentPath = git(coreRoot, [
    'rev-list',
    '--first-parent',
    '--reverse',
    '--parents',
    `${config.baseCoreCommit}..${commit}`,
  ]).trim().split('\n').filter(Boolean);
  if (firstParentPath.length === 0)
    fail(`D1p candidate ${revision} is not on the first-parent path after accepted D1`);
  let expectedParent = config.baseCoreCommit;
  for (const commitLine of firstParentPath) {
    const fields = commitLine.split(' ');
    if (fields.length !== 2 || fields[1] !== expectedParent) {
      fail(
        'D1p candidate history after accepted D1 must be a linear non-merge first-parent chain',
      );
    }
    expectedParent = fields[0];
  }
  if (expectedParent !== commit)
    fail(`D1p candidate ${revision} does not terminate the accepted linear history`);
  return {
    commit,
    commitCount: firstParentPath.length,
    kind: 'descendant',
  };
}

function candidatePosition(coreRoot, config) {
  const position = linearPosition(coreRoot, config, 'HEAD');
  return { ...position, head: position.commit };
}

function verifyBasePosition(coreRoot, config, position) {
  const candidate = candidatePosition(coreRoot, config);
  if (candidate.kind === 'base') {
    if (!['generation', 'preparation', 'workspace'].includes(position))
      fail('tracked candidate verification requires a committed descendant of accepted D1');
    return candidate;
  }
  return candidate;
}

function gitCommandSucceeds(coreRoot, args) {
  try {
    execFileSync('git', ['-C', coreRoot, ...args], {
      stdio: 'ignore',
    });
    return true;
  } catch {
    return false;
  }
}

function revisionBlob(coreRoot, revision, path, label = path) {
  validateRelativePath(path, label);
  const listing = git(coreRoot, ['ls-tree', '-z', revision, '--', path], {
    encoding: 'buffer',
  });
  const match = /^([0-9]{6}) blob ([0-9a-f]{40})\t([^\0]+)\0$/u.exec(
    listing.toString('utf8'),
  );
  if (match === null || match[3] !== path || !['100644', '100755'].includes(match[1]))
    fail(`${label} must be a regular tracked blob in ${revision}`);
  return git(coreRoot, ['cat-file', 'blob', `${revision}:${path}`], { encoding: 'buffer' });
}

function revisionHasPath(coreRoot, revision, path) {
  return git(coreRoot, ['ls-tree', '-z', revision, '--', path], {
    encoding: 'buffer',
  }).length !== 0;
}

function revisionBlobIdentity(coreRoot, revision, path, label = path) {
  validateRelativePath(path, label);
  const listing = git(coreRoot, ['ls-tree', '-z', revision, '--', path], {
    encoding: 'buffer',
  });
  const match = /^([0-9]{6}) blob ([0-9a-f]{40})\t([^\0]+)\0$/u.exec(
    listing.toString('utf8'),
  );
  if (match === null || match[3] !== path || !['100644', '100755'].includes(match[1]))
    fail(`${label} must be a regular tracked blob in ${revision}`);
  return `${match[1]} blob ${match[2]}`;
}

function approvedPreviewHistory(coreRoot, config, head) {
  return git(coreRoot, [
    'rev-list',
    '--reverse',
    `${config.baseCoreCommit}..${head}`,
    '--',
    APPROVED_PREVIEW_PATH,
  ]).trim().split('\n').filter(Boolean);
}

function pathEntryExists(root, path) {
  try {
    lstatSync(resolve(root, path));
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT')
      return false;
    throw error;
  }
}

function assertProvisionalEvidenceState(coreRoot, config, currentPosition, operation) {
  if (approvedPreviewHistory(coreRoot, config, currentPosition.head).length !== 0) {
    fail(`${APPROVED_PREVIEW_PATH} history seals post-D2 evidence; ${operation} is pre-G3 only`);
  }
  if (pathEntryExists(coreRoot, APPROVED_PREVIEW_PATH)) {
    fail(`${APPROVED_PREVIEW_PATH} is reserved for the dedicated post-D2 seal commit`);
  }
}

function approvedPreviewSeal(coreRoot, config, currentPosition) {
  const history = approvedPreviewHistory(coreRoot, config, currentPosition.head);
  if (history.length === 0) {
    if (revisionHasPath(coreRoot, currentPosition.head, APPROVED_PREVIEW_PATH))
      fail(`${APPROVED_PREVIEW_PATH} exists without an auditable add commit`);
    return null;
  }
  if (history.length !== 1)
    fail(`${APPROVED_PREVIEW_PATH} must be added exactly once and never changed or deleted`);

  const sealCommit = history[0];
  const changed = git(coreRoot, [
    'diff-tree',
    '--no-commit-id',
    '--name-status',
    '-r',
    sealCommit,
  ]).trim().split('\n').filter(Boolean);
  if (changed.length !== 1 || changed[0] !== `A\t${APPROVED_PREVIEW_PATH}`) {
    fail(
      `${APPROVED_PREVIEW_PATH} must be the only change in its dedicated post-D2 seal commit`,
    );
  }
  const parentLine = git(coreRoot, ['rev-list', '--parents', '-n', '1', sealCommit])
    .trim().split(' ');
  if (parentLine.length !== 2)
    fail(`${APPROVED_PREVIEW_PATH} seal commit must have exactly one parent`);
  if (!revisionHasPath(coreRoot, currentPosition.head, APPROVED_PREVIEW_PATH))
    fail(`${APPROVED_PREVIEW_PATH} must not be deleted after sealing`);
  const sealBytes = revisionBlob(
    coreRoot,
    sealCommit,
    APPROVED_PREVIEW_PATH,
    'approved-preview seal',
  );
  const currentBytes = revisionBlob(
    coreRoot,
    currentPosition.head,
    APPROVED_PREVIEW_PATH,
    'current approved-preview seal',
  );
  if (!currentBytes.equals(sealBytes))
    fail(`${APPROVED_PREVIEW_PATH} must remain byte-identical to its add commit`);
  const seal = parseCanonicalJsonBytes(sealBytes, APPROVED_PREVIEW_PATH);
  assertExactKeys(seal, [
    'approvedPreviewCommit',
    'approvedPreviewTree',
    'canonicalSemanticManifestSha256',
    'externalEntrySetSha256',
    'externalManifestSha256',
    'formatVersion',
    'g3ApprovalReference',
    'previewEvidenceManifestSha256',
    'rootManifestSha256',
    'trackedBlobManifestSha256',
  ], 'approved-preview seal');
  if (seal.formatVersion !== 1)
    fail('approved-preview seal formatVersion must be exactly 1');
  if (!HEX_40.test(seal.approvedPreviewCommit))
    fail('approvedPreviewCommit must be 40 lowercase hexadecimal characters');
  if (!HEX_40.test(seal.approvedPreviewTree))
    fail('approvedPreviewTree must be 40 lowercase hexadecimal characters');
  for (const field of [
    'canonicalSemanticManifestSha256',
    'externalEntrySetSha256',
    'externalManifestSha256',
    'previewEvidenceManifestSha256',
    'rootManifestSha256',
    'trackedBlobManifestSha256',
  ]) {
    if (!HEX_64.test(seal[field]))
      fail(`approved-preview seal ${field} must be lowercase SHA-256`);
  }
  if (!G3_APPROVAL_REFERENCE.test(seal.g3ApprovalReference)) {
    fail('approved-preview seal g3ApprovalReference must be sha256:<64 lowercase hex>');
  }
  if (parentLine[1] !== seal.approvedPreviewCommit) {
    fail('approved-preview seal commit parent must be the recorded G3-approved preview commit');
  }
  const previewPosition = linearPosition(coreRoot, config, seal.approvedPreviewCommit);
  if (previewPosition.kind === 'base')
    fail('approved preview must be a committed descendant of accepted D1');
  const actualTree = git(coreRoot, [
    'rev-parse',
    `${seal.approvedPreviewCommit}^{tree}`,
  ]).trim();
  if (actualTree !== seal.approvedPreviewTree)
    fail('approved-preview seal tree does not match its recorded preview commit');
  if (revisionHasPath(coreRoot, seal.approvedPreviewCommit, APPROVED_PREVIEW_PATH))
    fail('approved-preview seal must not exist in the G3-approved preview commit');

  const rootBytes = revisionBlob(
    coreRoot,
    seal.approvedPreviewCommit,
    ROOT_PATH,
    'approved-preview root manifest',
  );
  const rootManifest = parseCanonicalJsonBytes(
    rootBytes,
    `${seal.approvedPreviewCommit}:${ROOT_PATH}`,
  );
  validateRootManifest(rootManifest, config);
  const expectedBindings = {
    canonicalSemanticManifestSha256: rootManifest.canonicalSemanticManifest.sha256,
    externalEntrySetSha256: rootManifest.externalEntrySetSha256,
    externalManifestSha256: rootManifest.externalManifestSha256,
    previewEvidenceManifestSha256: rootManifest.previewEvidenceManifest.sha256,
    rootManifestSha256: sha256(rootBytes),
    trackedBlobManifestSha256: rootManifest.trackedBlobManifest.sha256,
  };
  for (const [field, expected] of Object.entries(expectedBindings)) {
    if (seal[field] !== expected)
      fail(`approved-preview seal ${field} does not match the preview root tuple`);
  }
  return { commit: seal.approvedPreviewCommit, seal, sealCommit };
}

export function validateG3ApprovalReceipt(bytes, expected) {
  const receipt = parseCanonicalJsonBytes(bytes, 'G3 approval receipt');
  assertExactKeys(receipt, [
    'approvedAt',
    'approvedPreviewCommit',
    'approvedPreviewTree',
    'canonicalSemanticManifestSha256',
    'decision',
    'externalEntrySetSha256',
    'externalManifestSha256',
    'formatVersion',
    'ownerApprovalReference',
    'previewEvidenceManifestSha256',
    'rootManifestSha256',
    'trackedBlobManifestSha256',
  ], 'G3 approval receipt');
  if (receipt.formatVersion !== 1)
    fail('G3 approval receipt formatVersion must be exactly 1');
  if (receipt.decision !== 'APPROVED')
    fail('G3 approval receipt decision must be exactly APPROVED');
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u.test(receipt.approvedAt))
    fail('G3 approval receipt approvedAt must be UTC ISO-8601 with whole seconds');
  const parsedApprovedAt = new Date(receipt.approvedAt);
  if (Number.isNaN(parsedApprovedAt.valueOf())
      || parsedApprovedAt.toISOString() !== receipt.approvedAt.replace('Z', '.000Z')) {
    fail('G3 approval receipt approvedAt must be a real UTC instant');
  }
  assertNonblank(receipt.ownerApprovalReference, 'G3 approval receipt ownerApprovalReference');
  const expectedFields = [
    'approvedPreviewCommit',
    'approvedPreviewTree',
    'canonicalSemanticManifestSha256',
    'externalEntrySetSha256',
    'externalManifestSha256',
    'previewEvidenceManifestSha256',
    'rootManifestSha256',
    'trackedBlobManifestSha256',
  ];
  for (const field of expectedFields) {
    if (receipt[field] !== expected[field])
      fail(`G3 approval receipt ${field} does not match the candidate evidence tuple`);
  }
  return receipt;
}

function assertPreviewGitPolicy(coreRoot) {
  if (gitCommandSucceeds(coreRoot, ['ls-files', '--error-unmatch', '--', PREVIEW_PATH]))
    fail(`${PREVIEW_PATH} must remain untracked`);
  if (!gitCommandSucceeds(coreRoot, ['check-ignore', '-q', '--', PREVIEW_PATH]))
    fail(`${PREVIEW_PATH} must remain ignored by Git`);
}

function assertCommittedCandidateState(coreRoot, position, evidenceCommit) {
  assertPreviewGitPolicy(coreRoot);
  const previewListing = git(coreRoot, [
    'ls-tree',
    '-z',
    position.head,
    '--',
    PREVIEW_PATH,
  ], { encoding: 'buffer' });
  if (previewListing.length !== 0)
    fail(`${PREVIEW_PATH} must not be tracked by candidate HEAD`);
  for (const path of REQUIRED_COMMITTED_D1P_PATHS) {
    revisionBlob(
      coreRoot,
      position.head,
      path,
      `committed D1p path ${path}`,
    );
  }
  for (const path of IMMUTABLE_SEALED_D1P_PATHS) {
    const committed = revisionBlob(coreRoot, evidenceCommit, path, `candidate D1p path ${path}`);
    const current = revisionBlob(coreRoot, position.head, path, `current D1p path ${path}`);
    const committedIdentity = revisionBlobIdentity(
      coreRoot,
      evidenceCommit,
      path,
      `candidate D1p path ${path}`,
    );
    const currentIdentity = revisionBlobIdentity(
      coreRoot,
      position.head,
      path,
      `current D1p path ${path}`,
    );
    if (currentIdentity !== committedIdentity)
      fail(`candidate D1p path ${path} Git identity differs from approved preview ${evidenceCommit}`);
    if (!current.equals(committed))
      fail(`candidate D1p path ${path} differs from approved preview ${evidenceCommit}`);
    const working = readFileSync(assertRegularFile(coreRoot, path, `candidate D1p path ${path}`));
    if (!working.equals(current))
      fail(`candidate D1p path ${path} differs from committed HEAD`);
  }
  const status = git(coreRoot, [
    'status',
    '--porcelain=v1',
    '-z',
    '--untracked-files=all',
    '--',
  ], { encoding: 'buffer' });
  if (status.length !== 0) {
    fail(
      'postcommit D1p verification rejects staged, unstaged, or nonignored untracked core state',
    );
  }
}

function nulPaths(bytes, label) {
  if (bytes.length === 0)
    return [];
  const parts = bytes.toString('utf8').split('\0');
  if (parts.at(-1) !== '')
    fail(`${label} did not end in NUL`);
  parts.pop();
  return parts;
}

export function candidateDeltaPaths(
  coreRoot,
  config,
  { revision = 'HEAD', source = 'workspace' } = {},
) {
  if (!['head', 'workspace'].includes(source))
    fail('candidate delta source must be exactly head or workspace');
  verifyBaseIdentity(coreRoot, config);
  candidatePosition(coreRoot, config);
  const changed = nulPaths(
    git(coreRoot, [
      'diff',
      '--name-only',
      '-z',
      '--diff-filter=ACMRTUXB',
      config.baseCoreCommit,
      ...(source === 'head' ? [revision] : []),
      '--',
    ], { encoding: 'buffer' }),
    'git diff path list',
  );
  const untracked = source === 'workspace'
    ? nulPaths(
      git(coreRoot, ['ls-files', '--others', '--exclude-standard', '-z'], {
        encoding: 'buffer',
      }),
      'git untracked path list',
    )
    : [];
  const excluded = new Set(config.trackedBlobExclusions);
  const paths = [...new Set([...changed, ...untracked])]
    .filter((path) => !excluded.has(path))
    .sort(compareBytes);
  if (paths.length === 0)
    fail('D1p candidate delta contains no hashable paths');
  for (const path of paths) {
    if (source === 'head')
      revisionBlob(coreRoot, revision, path, `candidate delta path ${path}`);
    else
      assertRegularFile(coreRoot, path, `candidate delta path ${path}`);
  }
  return paths;
}

export function deriveTrackedBlobManifest(
  coreRoot,
  config,
  { revision = 'HEAD', source = 'workspace' } = {},
) {
  const paths = candidateDeltaPaths(coreRoot, config, { revision, source });
  return `${paths.map((path) => {
    const bytes = source === 'head'
      ? revisionBlob(coreRoot, revision, path, `candidate delta path ${path}`)
      : readFileSync(resolve(coreRoot, path));
    return `${sha256(bytes)}  ${path}`;
  })
    .join('\n')}\n`;
}

export function validateTrackedBlobManifest(bytes, config) {
  const text = Buffer.isBuffer(bytes) ? bytes.toString('utf8') : String(bytes);
  if (!text.endsWith('\n') || text.includes('\r'))
    fail('tracked-blob manifest must use LF and end in exactly one LF');
  const lines = text.slice(0, -1).split('\n');
  if (lines.some((line) => line.length === 0))
    fail('tracked-blob manifest contains an empty record');
  const records = lines.map((line, index) => {
    if (!/^[0-9a-f]{64}  .+$/u.test(line))
      fail(`tracked-blob record ${index} does not use the exact SHA-256/two-space/path format`);
    const path = line.slice(66);
    validateRelativePath(path, `tracked-blob record ${index} path`);
    if (config.trackedBlobExclusions.includes(path))
      fail(`tracked-blob manifest contains excluded manifest path ${path}`);
    if (path === PREVIEW_PATH)
      fail(`tracked-blob manifest must not contain untracked preview path ${PREVIEW_PATH}`);
    return { path, sha256: line.slice(0, 64) };
  });
  assertSortedUniqueStrings(records.map((record) => record.path), 'tracked-blob paths', {
    nonempty: true,
  });
  return records;
}

function readStrictLines(root, path, { allowEmpty = false } = {}) {
  const absolute = assertRegularFile(root, path);
  const bytes = readFileSync(absolute);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail(`${path} is not UTF-8`);
  if (text.includes('\r'))
    fail(`${path} contains CR line endings`);
  if (text.length === 0) {
    if (allowEmpty)
      return [];
    fail(`${path} must not be empty`);
  }
  if (!text.endsWith('\n'))
    fail(`${path} must end in LF`);
  const lines = text.slice(0, -1).split('\n');
  if (lines.some((line) => line.length === 0))
    fail(`${path} contains a blank line`);
  return lines;
}

function canonicalJsonlTuples(root, path) {
  return readStrictLines(root, path, { allowEmpty: true }).map((line, index) => {
    let value;
    try {
      value = JSON.parse(line);
    } catch (error) {
      fail(`${path}:${index + 1} is invalid JSON: ${error.message}`);
    }
    if (!isPlainObject(value))
      fail(`${path}:${index + 1} must contain a JSON object`);
    return JSON.stringify(sortJson(value));
  });
}

function readUtf8File(root, path) {
  const bytes = readFileSync(assertRegularFile(root, path));
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail(`${path} is not UTF-8`);
  return text;
}

function verifyCompilerBindings(coreRoot) {
  const diffPath = 'target/japicmp/mcp-api-diff.xml';
  const freezePath = 'target/japicmp/mcp-api-freeze.xml';
  const reviewedIncompatibilityPath = 'api/mcp/current-incompatibilities.jsonl';
  const allowlistPath = 'api/mcp/non-mcp-public-api.allowlist';
  const diffXml = readUtf8File(coreRoot, diffPath);
  const freezeXml = readUtf8File(coreRoot, freezePath);
  const reviewedIncompatibilities = readUtf8File(coreRoot, reviewedIncompatibilityPath);
  const allowlist = readUtf8File(coreRoot, allowlistPath);
  const includePaths = [
    'api/mcp/phase-4.includes',
    'api/mcp/phase-5.includes',
    'api/mcp/phase-6.includes',
    'api/mcp/provisional.includes',
  ];
  const includes = includePaths.map((path) => readUtf8File(coreRoot, path));

  verifyJapicmpReportPair(diffXml, freezeXml, '3.5.1', 'soklet-3.5.1.jar');
  verifyReviewedSet(diffXml, reviewedIncompatibilities);
  verifyReviewedApiInventory(freezeXml, allowlist, includes);
  for (const phase of [4, 5, 6]) {
    verifyReviewedApiSignatures(
      freezeXml,
      includes[phase - 4],
      readUtf8File(coreRoot, `api/mcp/phase-${phase}.signatures.jsonl`),
    );
  }
}

function sortedUnique(values, label) {
  const sorted = [...values].sort(compareBytes);
  for (let index = 1; index < sorted.length; ++index) {
    if (sorted[index - 1] === sorted[index])
      fail(`${label} contains duplicate tuple ${sorted[index]}`);
  }
  return sorted;
}

function tupleDigest(tuples) {
  return sha256(Buffer.from(tuples.map((tuple) => `${tuple}\n`).join(''), 'utf8'));
}

function tupleSet(name, sourcePaths, tuples) {
  const sortedTuples = sortedUnique(tuples, name);
  const sortedSources = sortedUnique(sourcePaths, `${name} source paths`);
  return {
    count: sortedTuples.length,
    name,
    sha256: tupleDigest(sortedTuples),
    sourcePaths: sortedSources,
    tuples: sortedTuples,
  };
}

function assertTupleParity(left, right, label) {
  const sortedLeft = sortedUnique(left, `${label} generated tuples`);
  const sortedRight = sortedUnique(right, `${label} reviewed tuples`);
  if (canonicalJson(sortedLeft) !== canonicalJson(sortedRight))
    fail(`${label} generated and reviewed tuple sets differ`);
}

function reflectionTuples(coreRoot, sourcePath) {
  const source = readFileSync(assertRegularFile(coreRoot, sourcePath), 'utf8');
  const matches = [...source.matchAll(
    /PHASE_(FOUR|FIVE|SIX)_NULLABILITY_SHA_256\s*=\s*"([0-9a-f]{64})"/gu,
  )];
  if (matches.length !== 3)
    fail(`${sourcePath} must contain exactly three reflection/nullability SHA-256 constants`);
  const phase = new Map([['FOUR', 'phase-4'], ['FIVE', 'phase-5'], ['SIX', 'phase-6']]);
  const tuples = matches.map((match) => `${phase.get(match[1])}|${match[2]}`);
  if (new Set(tuples.map((tuple) => tuple.split('|')[0])).size !== 3)
    fail(`${sourcePath} reflection/nullability phases must be unique`);
  return tuples;
}

export function deriveSemanticManifest(coreRoot, config) {
  verifyCompilerBindings(coreRoot);
  const sets = [];
  const descriptorSets = [];
  for (const phase of [4, 5, 6]) {
    const generatedPath = `target/mcp-api-freezes/phase-${phase}.signatures.jsonl`;
    const reviewedPath = `api/mcp/phase-${phase}.signatures.jsonl`;
    const generated = canonicalJsonlTuples(coreRoot, generatedPath);
    const reviewed = canonicalJsonlTuples(coreRoot, reviewedPath);
    assertTupleParity(generated, reviewed, `Phase ${phase} descriptors`);
    const set = tupleSet(
      `descriptor.phase-${phase}`,
      [reviewedPath, generatedPath, 'target/japicmp/mcp-api-freeze.xml'],
      generated,
    );
    const expectedCount = config.semanticExpectations.signatureCounts[`phase-${phase}`];
    if (set.count !== expectedCount)
      fail(`Phase ${phase} signature count must be ${expectedCount}, got ${set.count}`);
    descriptorSets.push(set);
    sets.push(set);
  }

  const ownerSources = [
    'api/mcp/phase-4.includes',
    'api/mcp/phase-5.includes',
    'api/mcp/phase-6.includes',
    'api/mcp/provisional.includes',
  ];
  const ownerTuples = [];
  const ownerPartitions = new Map();
  for (const path of ownerSources) {
    const partition = path.slice('api/mcp/'.length, -'.includes'.length);
    const ownersForPartition = readStrictLines(coreRoot, path, { allowEmpty: true });
    const expectedCount = config.semanticExpectations.ownerCounts[partition];
    if (ownersForPartition.length !== expectedCount)
      fail(`${partition} owner count must be ${expectedCount}, got ${ownersForPartition.length}`);
    for (const owner of ownersForPartition) {
      if (ownerPartitions.has(owner)) {
        fail(`MCP owner ${owner} appears in both ${ownerPartitions.get(owner)} and ${partition}`);
      }
      ownerPartitions.set(owner, partition);
      ownerTuples.push(`${partition}|${owner}`);
    }
  }
  const expectedOwnerUnion = Object.values(config.semanticExpectations.ownerCounts)
    .reduce((sum, count) => sum + count, 0);
  if (ownerPartitions.size !== expectedOwnerUnion)
    fail(`MCP owner union must be ${expectedOwnerUnion}, got ${ownerPartitions.size}`);
  const owners = tupleSet(
    'freeze.owners',
    [...ownerSources, 'target/japicmp/mcp-api-freeze.xml'],
    ownerTuples,
  );
  sets.push(owners);

  const generatedIncompatibilityPath =
    'target/japicmp/mcp-api-diff.incompatibilities.jsonl';
  const reviewedIncompatibilityPath = 'api/mcp/current-incompatibilities.jsonl';
  const generatedIncompatibilities = canonicalJsonlTuples(
    coreRoot,
    generatedIncompatibilityPath,
  );
  const reviewedIncompatibilities = canonicalJsonlTuples(
    coreRoot,
    reviewedIncompatibilityPath,
  );
  assertTupleParity(
    generatedIncompatibilities,
    reviewedIncompatibilities,
    'API incompatibilities',
  );
  const incompatibilities = tupleSet(
    'incompatibility',
    [
      reviewedIncompatibilityPath,
      generatedIncompatibilityPath,
      'target/japicmp/mcp-api-diff.xml',
    ],
    generatedIncompatibilities,
  );
  if (incompatibilities.count !== config.semanticExpectations.incompatibilityCount) {
    fail(`incompatibility count must be ${config.semanticExpectations.incompatibilityCount}, got ${incompatibilities.count}`);
  }
  sets.push(incompatibilities);

  const allowlistPath = 'api/mcp/non-mcp-public-api.allowlist';
  const allowlist = tupleSet(
    'allowlist',
    [allowlistPath, 'target/japicmp/mcp-api-freeze.xml'],
    readStrictLines(coreRoot, allowlistPath),
  );
  if (allowlist.count !== config.semanticExpectations.allowlistCount) {
    fail(`allowlist count must be ${config.semanticExpectations.allowlistCount}, got ${allowlist.count}`);
  }
  sets.push(allowlist);

  const protectedPostD2 = tupleSet(
    'protected.post-d2',
    config.protectedPostD2Paths,
    config.protectedPostD2Paths.map((path) => {
      return `${path}|${sha256(readFileSync(assertRegularFile(
        coreRoot,
        path,
        `protected post-D2 path ${path}`,
      )))}`;
    }),
  );
  if (protectedPostD2.count !== config.protectedPostD2Paths.length)
    fail('protected post-D2 tuple cardinality does not match configuration');
  sets.push(protectedPostD2);

  const reflection = tupleSet(
    'reflection.nullability',
    [config.reflectionDigestSourcePath],
    reflectionTuples(coreRoot, config.reflectionDigestSourcePath),
  );
  sets.push(reflection);

  const freezeTuples = [
    ...descriptorSets.map((set) => `${set.name}|${set.count}|${set.sha256}`),
    `${owners.name}|${owners.count}|${owners.sha256}`,
    `${incompatibilities.name}|${incompatibilities.count}|${incompatibilities.sha256}`,
    `${allowlist.name}|${allowlist.count}|${allowlist.sha256}`,
    `${protectedPostD2.name}|${protectedPostD2.count}|${protectedPostD2.sha256}`,
    `${reflection.name}|${reflection.count}|${reflection.sha256}`,
  ];
  sets.push(tupleSet(
    'freeze',
    sortedUnique(
      [...new Set(sets.flatMap((set) => set.sourcePaths))],
      'freeze source paths',
    ),
    freezeTuples,
  ));

  sets.sort((left, right) => compareBytes(left.name, right.name));
  return { formatVersion: 1, tupleSets: sets };
}

export function validateSemanticManifest(manifest) {
  assertExactKeys(manifest, ['formatVersion', 'tupleSets'], 'canonical-semantic manifest');
  if (manifest.formatVersion !== 1)
    fail('canonical-semantic formatVersion must be exactly 1');
  assertArray(manifest.tupleSets, 'canonical-semantic tupleSets');
  if (manifest.tupleSets.length === 0)
    fail('canonical-semantic tupleSets must not be empty');
  const names = [];
  for (const [index, set] of manifest.tupleSets.entries()) {
    const label = `canonical-semantic tupleSets[${index}]`;
    assertExactKeys(set, ['count', 'name', 'sha256', 'sourcePaths', 'tuples'], label);
    assertNonnegativeInteger(set.count, `${label}.count`);
    assertNonblank(set.name, `${label}.name`);
    if (!HEX_64.test(set.sha256))
      fail(`${label}.sha256 must be lowercase SHA-256`);
    assertSortedUniqueStrings(set.sourcePaths, `${label}.sourcePaths`, { nonempty: true });
    set.sourcePaths.forEach((path, pathIndex) =>
      validateRelativePath(path, `${label}.sourcePaths[${pathIndex}]`));
    assertArray(set.tuples, `${label}.tuples`);
    set.tuples.forEach((tuple, tupleIndex) => {
      assertNonblank(tuple, `${label}.tuples[${tupleIndex}]`);
      if (tuple.includes('\n') || tuple.includes('\r'))
        fail(`${label}.tuples[${tupleIndex}] contains a line break`);
    });
    if (set.tuples.length > 0)
      assertSortedUniqueStrings(set.tuples, `${label}.tuples`);
    if (set.count !== set.tuples.length)
      fail(`${label}.count does not match tuple cardinality`);
    if (set.sha256 !== tupleDigest(set.tuples))
      fail(`${label}.sha256 does not match canonical tuple bytes`);
    names.push(set.name);
  }
  assertSortedUniqueStrings(names, 'canonical-semantic tuple-set names', { nonempty: true });
  const required = [
    'allowlist',
    'descriptor.phase-4',
    'descriptor.phase-5',
    'descriptor.phase-6',
    'freeze',
    'freeze.owners',
    'incompatibility',
    'protected.post-d2',
    'reflection.nullability',
  ];
  if (canonicalJson(names) !== canonicalJson(required))
    fail(`canonical-semantic tuple-set names must be exactly ${required.join(', ')}`);
  return manifest;
}

export function derivePreviewEvidence(coreRoot, config) {
  return {
    artifacts: config.previewArtifactPaths.map((path) => ({
      path,
      sha256: sha256(readFileSync(assertRegularFile(coreRoot, path, `preview artifact ${path}`))),
    })),
    formatVersion: 1,
  };
}

export function validatePreviewEvidence(manifest, config) {
  assertExactKeys(manifest, ['artifacts', 'formatVersion'], 'preview-evidence manifest');
  assertArray(manifest.artifacts, 'preview-evidence artifacts');
  if (manifest.formatVersion !== 1)
    fail('preview-evidence formatVersion must be exactly 1');
  const paths = [];
  for (const [index, artifact] of manifest.artifacts.entries()) {
    const label = `preview-evidence artifacts[${index}]`;
    assertExactKeys(artifact, ['path', 'sha256'], label);
    validateRelativePath(artifact.path, `${label}.path`);
    if (!HEX_64.test(artifact.sha256))
      fail(`${label}.sha256 must be lowercase SHA-256`);
    paths.push(artifact.path);
  }
  assertSortedUniqueStrings(paths, 'preview-evidence artifact paths', { nonempty: true });
  if (canonicalJson(paths) !== canonicalJson(config.previewArtifactPaths))
    fail('preview-evidence artifact paths do not match the frozen configuration');
  if (paths.includes(PREVIEW_PATH))
    fail('preview-evidence manifest must not hash itself');
  return manifest;
}

export function deriveExternalManifest(externalRoot, config) {
  const entries = config.externalEntries.map((configured) => {
    let previewSha256;
    if (configured.changeKind === 'deleted') {
      assertMissingPath(
        externalRoot,
        configured.path,
        `external preview ${configured.path}`,
      );
      previewSha256 = null;
    } else {
      previewSha256 = sha256(readFileSync(assertRegularFile(
        externalRoot,
        configured.path,
        `external preview ${configured.path}`,
      )));
    }
    return {
      allowedPostD2Owner: configured.allowedPostD2Owner,
      baseSha256: configured.baseSha256,
      owner: configured.owner,
      path: configured.path,
      previewSha256,
      reason: configured.reason,
    };
  });
  return {
    baseCoreCommit: config.baseCoreCommit,
    baseCoreTree: config.baseCoreTree,
    entries,
    formatVersion: 1,
  };
}

export function validateExternalManifest(manifest, config) {
  assertExactKeys(
    manifest,
    ['baseCoreCommit', 'baseCoreTree', 'entries', 'formatVersion'],
    'external manifest',
  );
  if (manifest.baseCoreCommit !== config.baseCoreCommit)
    fail('external manifest baseCoreCommit mismatch');
  if (manifest.baseCoreTree !== config.baseCoreTree)
    fail('external manifest baseCoreTree mismatch');
  if (manifest.formatVersion !== 1)
    fail('external manifest formatVersion must be exactly 1');
  assertArray(manifest.entries, 'external manifest entries');
  manifest.entries.forEach((entry, index) =>
    validateExternalPolicyEntry(entry, index, { manifest: true }));
  const paths = manifest.entries.map((entry) => entry.path);
  assertSortedUniqueStrings(paths, 'external manifest entry paths', { nonempty: true });
  if (manifest.entries.length !== config.externalEntries.length)
    fail('external manifest entry cardinality does not match the frozen configuration');
  for (let index = 0; index < config.externalEntries.length; ++index) {
    const actual = { ...manifest.entries[index] };
    delete actual.previewSha256;
    const expected = { ...config.externalEntries[index] };
    delete expected.changeKind;
    if (canonicalJson(actual) !== canonicalJson(expected))
      fail(`external manifest entry ${index} policy does not match the frozen configuration`);
    const previewSha256 = manifest.entries[index].previewSha256;
    const changeKind = config.externalEntries[index].changeKind;
    if (changeKind === 'deleted' && previewSha256 !== null)
      fail(`external manifest entry ${index}.previewSha256 must be null for a deleted path`);
    if (changeKind !== 'deleted' && previewSha256 === null)
      fail(`external manifest entry ${index}.previewSha256 must be lowercase SHA-256 for ${changeKind} path`);
  }
  return manifest;
}

export function externalEntrySetBytes(externalManifest) {
  return Buffer.from(canonicalJson({ entries: externalManifest.entries }, { compact: true }));
}

function leafBinding(path, bytes) {
  return { path, sha256: sha256(bytes) };
}

export function deriveRootManifest(config, {
  semanticBytes,
  trackedBlobBytes,
  previewBytes,
  externalBytes,
  externalManifest,
}) {
  return {
    baseCoreCommit: config.baseCoreCommit,
    baseCoreTree: config.baseCoreTree,
    canonicalSemanticManifest: leafBinding(SEMANTIC_PATH, semanticBytes),
    externalEntrySetSha256: sha256(externalEntrySetBytes(externalManifest)),
    externalManifestPath: EXTERNAL_PATH,
    externalManifestSha256: sha256(externalBytes),
    formatVersion: 1,
    previewEvidenceManifest: leafBinding(PREVIEW_PATH, previewBytes),
    trackedBlobManifest: leafBinding(TRACKED_BLOB_PATH, trackedBlobBytes),
  };
}

function validateLeafBinding(binding, expectedPath, label) {
  assertExactKeys(binding, ['path', 'sha256'], label);
  if (binding.path !== expectedPath)
    fail(`${label}.path must be exactly ${expectedPath}`);
  if (!HEX_64.test(binding.sha256))
    fail(`${label}.sha256 must be lowercase SHA-256`);
  if (binding.path === ROOT_PATH)
    fail(`${label} must not bind the root manifest`);
}

export function validateRootManifest(manifest, config) {
  assertExactKeys(manifest, [
    'baseCoreCommit',
    'baseCoreTree',
    'canonicalSemanticManifest',
    'externalEntrySetSha256',
    'externalManifestPath',
    'externalManifestSha256',
    'formatVersion',
    'previewEvidenceManifest',
    'trackedBlobManifest',
  ], 'root manifest');
  if (manifest.baseCoreCommit !== config.baseCoreCommit)
    fail('root manifest baseCoreCommit mismatch');
  if (manifest.baseCoreTree !== config.baseCoreTree)
    fail('root manifest baseCoreTree mismatch');
  validateLeafBinding(
    manifest.canonicalSemanticManifest,
    SEMANTIC_PATH,
    'canonicalSemanticManifest',
  );
  if (!HEX_64.test(manifest.externalEntrySetSha256))
    fail('externalEntrySetSha256 must be lowercase SHA-256');
  if (manifest.externalManifestPath !== EXTERNAL_PATH)
    fail(`externalManifestPath must be exactly ${EXTERNAL_PATH}`);
  if (!HEX_64.test(manifest.externalManifestSha256))
    fail('externalManifestSha256 must be lowercase SHA-256');
  if (manifest.formatVersion !== 1)
    fail('root manifest formatVersion must be exactly 1');
  validateLeafBinding(
    manifest.previewEvidenceManifest,
    PREVIEW_PATH,
    'previewEvidenceManifest',
  );
  validateLeafBinding(
    manifest.trackedBlobManifest,
    TRACKED_BLOB_PATH,
    'trackedBlobManifest',
  );
  const leafPaths = [
    manifest.canonicalSemanticManifest.path,
    manifest.previewEvidenceManifest.path,
    manifest.trackedBlobManifest.path,
  ];
  if (new Set(leafPaths).size !== leafPaths.length)
    fail('root manifest leaf paths must be unique');
  return manifest;
}

function readCanonicalManifest(root, path, label, { revision } = {}) {
  return {
    bytes: revision === undefined
      ? readFileSync(assertRegularFile(root, path, label))
      : revisionBlob(root, revision, path, label),
    path: revision === undefined ? resolve(root, path) : `${revision}:${path}`,
  };
}

function assertBytesEqual(actual, expected, label) {
  const actualBuffer = Buffer.isBuffer(actual) ? actual : Buffer.from(actual);
  const expectedBuffer = Buffer.isBuffer(expected) ? expected : Buffer.from(expected);
  if (!actualBuffer.equals(expectedBuffer))
    fail(`${label} does not match deterministic derivation`);
}

function safeRoot(path, label) {
  const absolute = resolve(path);
  if (!existsSync(absolute) || !lstatSync(absolute).isDirectory())
    fail(`${label} must be an existing directory`);
  return realpathSync(absolute);
}

export function deriveCoreEvidence(
  coreRoot,
  config,
  { includePreview = true, revision = 'HEAD', source = 'workspace' } = {},
) {
  const trackedBlobBytes = Buffer.from(
    deriveTrackedBlobManifest(coreRoot, config, { revision, source }),
  );
  validateTrackedBlobManifest(trackedBlobBytes, config);
  const semantic = deriveSemanticManifest(coreRoot, config);
  validateSemanticManifest(semantic);
  const semanticBytes = Buffer.from(canonicalJson(semantic));
  if (!includePreview)
    return { semantic, semanticBytes, trackedBlobBytes };
  const preview = derivePreviewEvidence(coreRoot, config);
  validatePreviewEvidence(preview, config);
  const previewBytes = Buffer.from(canonicalJson(preview));
  return { preview, previewBytes, semantic, semanticBytes, trackedBlobBytes };
}

export function generateEvidence({ coreRoot, externalRoot, config: suppliedConfig }) {
  coreRoot = safeRoot(coreRoot, 'core root');
  externalRoot = safeRoot(externalRoot, 'external workspace root');
  const config = suppliedConfig === undefined
    ? readConfig(coreRoot)
    : validateConfig(suppliedConfig);
  verifyBaseIdentity(coreRoot, config);
  const position = verifyBasePosition(coreRoot, config, 'generation');
  assertPreviewGitPolicy(coreRoot);
  assertProvisionalEvidenceState(coreRoot, config, position, 'evidence generation');
  const core = deriveCoreEvidence(coreRoot, config);
  const externalManifest = deriveExternalManifest(externalRoot, config);
  validateExternalManifest(externalManifest, config);
  const externalBytes = Buffer.from(canonicalJson(externalManifest));

  const coreOutputs = [
    [TRACKED_BLOB_PATH, core.trackedBlobBytes],
    [SEMANTIC_PATH, core.semanticBytes],
    [PREVIEW_PATH, core.previewBytes],
  ];

  const rootManifest = deriveRootManifest(config, {
    semanticBytes: core.semanticBytes,
    trackedBlobBytes: core.trackedBlobBytes,
    previewBytes: core.previewBytes,
    externalBytes,
    externalManifest,
  });
  validateRootManifest(rootManifest, config);
  const rootBytes = Buffer.from(canonicalJson(rootManifest));
  const externalOutput = preflightOutputPath(externalRoot, EXTERNAL_PATH, EXTERNAL_PATH);
  const resolvedCoreOutputs = coreOutputs.map(([path, bytes]) => [
    path,
    bytes,
    preflightOutputPath(coreRoot, path, path),
  ]);
  const rootOutput = preflightOutputPath(coreRoot, ROOT_PATH, ROOT_PATH);

  createOutputParents(externalRoot, EXTERNAL_PATH, EXTERNAL_PATH);
  for (const [path] of coreOutputs)
    createOutputParents(coreRoot, path, path);
  createOutputParents(coreRoot, ROOT_PATH, ROOT_PATH);

  writeFileSync(externalOutput, externalBytes);
  for (const [, bytes, output] of resolvedCoreOutputs)
    writeFileSync(output, bytes);
  writeFileSync(rootOutput, rootBytes);
  return {
    ...core,
    externalBytes,
    externalManifest,
    rootBytes,
    rootManifest,
  };
}

export function verifyEvidence({
  coreRoot,
  externalRoot,
  mode,
  scope,
  config: suppliedConfig,
}) {
  coreRoot = safeRoot(coreRoot, 'core root');
  if (!['candidate', 'workspace'].includes(mode))
    fail('mode must be exactly candidate or workspace');
  if (!['preparation', 'tracked', 'full'].includes(scope))
    fail('scope must be exactly preparation, tracked, or full');
  if (mode === 'candidate' && externalRoot !== undefined)
    fail('candidate mode rejects --external-root and never reads sibling bytes');
  if (mode === 'candidate' && scope === 'full')
    fail('full scope requires workspace mode');
  if (mode === 'workspace' && scope !== 'full')
    fail('workspace mode requires full scope');
  if (mode === 'workspace')
    externalRoot = safeRoot(externalRoot, 'external workspace root');

  const config = suppliedConfig === undefined
    ? readConfig(coreRoot)
    : validateConfig(suppliedConfig);
  verifyBaseIdentity(coreRoot, config);
  const position = verifyBasePosition(
    coreRoot,
    config,
    scope === 'preparation' ? 'preparation' : (mode === 'workspace' ? 'workspace' : 'bound'),
  );
  assertPreviewGitPolicy(coreRoot);
  if (mode === 'workspace')
    assertProvisionalEvidenceState(coreRoot, config, position, 'workspace/full verification');
  const source = mode === 'workspace' || scope === 'preparation' ? 'workspace' : 'head';
  const seal = source === 'head' ? approvedPreviewSeal(coreRoot, config, position) : null;
  const evidenceCommit = seal?.commit ?? position.head;
  if (source === 'head')
    assertCommittedCandidateState(coreRoot, position, evidenceCommit);
  const derived = deriveCoreEvidence(coreRoot, config, {
    includePreview: mode === 'workspace',
    revision: evidenceCommit,
    source,
  });
  if (scope === 'preparation')
    return { config, derived };

  const trackedFile = readCanonicalManifest(
    coreRoot,
    TRACKED_BLOB_PATH,
    'tracked-blob manifest',
    { revision: source === 'head' ? evidenceCommit : undefined },
  );
  validateTrackedBlobManifest(trackedFile.bytes, config);
  assertBytesEqual(trackedFile.bytes, derived.trackedBlobBytes, TRACKED_BLOB_PATH);

  const semanticFile = readCanonicalManifest(
    coreRoot,
    SEMANTIC_PATH,
    'canonical-semantic manifest',
    { revision: source === 'head' ? evidenceCommit : undefined },
  );
  const semanticManifest = parseCanonicalJsonBytes(semanticFile.bytes, SEMANTIC_PATH);
  validateSemanticManifest(semanticManifest);
  assertBytesEqual(semanticFile.bytes, derived.semanticBytes, SEMANTIC_PATH);

  const rootFile = readCanonicalManifest(coreRoot, ROOT_PATH, 'root manifest', {
    revision: source === 'head' ? evidenceCommit : undefined,
  });
  const rootManifest = parseCanonicalJsonBytes(rootFile.bytes, ROOT_PATH);
  validateRootManifest(rootManifest, config);
  if (rootManifest.trackedBlobManifest.sha256 !== sha256(trackedFile.bytes))
    fail('root trackedBlobManifest SHA-256 does not match leaf bytes');
  if (rootManifest.canonicalSemanticManifest.sha256 !== sha256(semanticFile.bytes))
    fail('root canonicalSemanticManifest SHA-256 does not match leaf bytes');

  if (scope === 'tracked')
    return { config, derived, evidenceCommit, rootManifest, seal };

  const previewFile = readCanonicalManifest(coreRoot, PREVIEW_PATH, 'preview-evidence manifest');
  const previewManifest = parseCanonicalJsonBytes(previewFile.bytes, PREVIEW_PATH);
  validatePreviewEvidence(previewManifest, config);
  assertBytesEqual(previewFile.bytes, derived.previewBytes, PREVIEW_PATH);
  if (rootManifest.previewEvidenceManifest.sha256 !== sha256(previewFile.bytes))
    fail('root previewEvidenceManifest SHA-256 does not match retained leaf bytes');

  const externalFile = readCanonicalManifest(
    externalRoot,
    EXTERNAL_PATH,
    'external manifest',
  );
  const externalManifest = parseCanonicalJsonBytes(externalFile.bytes, EXTERNAL_PATH);
  validateExternalManifest(externalManifest, config);
  const expectedExternal = deriveExternalManifest(externalRoot, config);
  const expectedExternalBytes = Buffer.from(canonicalJson(expectedExternal));
  assertBytesEqual(externalFile.bytes, expectedExternalBytes, EXTERNAL_PATH);
  if (rootManifest.externalManifestSha256 !== sha256(externalFile.bytes))
    fail('root externalManifestSha256 does not match external manifest bytes');
  const entrySetSha = sha256(externalEntrySetBytes(externalManifest));
  if (rootManifest.externalEntrySetSha256 !== entrySetSha)
    fail('root externalEntrySetSha256 does not match canonical external entries');

  const expectedRoot = deriveRootManifest(config, {
    semanticBytes: semanticFile.bytes,
    trackedBlobBytes: trackedFile.bytes,
    previewBytes: previewFile.bytes,
    externalBytes: externalFile.bytes,
    externalManifest,
  });
  assertBytesEqual(rootFile.bytes, Buffer.from(canonicalJson(expectedRoot)), ROOT_PATH);
  return { config, derived, externalManifest, rootManifest };
}

export function generateApprovedPreviewSeal({
  coreRoot,
  g3ApprovalReceiptPath,
  config: suppliedConfig,
}) {
  coreRoot = safeRoot(coreRoot, 'core root');
  if (!isAbsolute(g3ApprovalReceiptPath))
    fail('G3 approval receipt path must be absolute');
  const receiptPath = resolve(g3ApprovalReceiptPath);
  if (!existsSync(receiptPath))
    fail(`Missing G3 approval receipt: ${receiptPath}`);
  const receiptStats = lstatSync(receiptPath);
  if (!receiptStats.isFile() || receiptStats.isSymbolicLink())
    fail('G3 approval receipt must be a regular non-symlink file');
  const receiptBytes = readFileSync(receiptPath);
  if (receiptBytes.length === 0)
    fail('G3 approval receipt must not be empty');

  const config = suppliedConfig === undefined
    ? readConfig(coreRoot)
    : validateConfig(suppliedConfig);
  const verified = verifyEvidence({
    coreRoot,
    mode: 'candidate',
    scope: 'tracked',
    config,
  });
  if (verified.seal !== null)
    fail(`${APPROVED_PREVIEW_PATH} already seals the approved preview`);
  const approvedPreviewCommit = verified.evidenceCommit;
  const approvedPreviewTree = git(coreRoot, [
    'rev-parse',
    `${approvedPreviewCommit}^{tree}`,
  ]).trim();
  const rootBytes = revisionBlob(
    coreRoot,
    approvedPreviewCommit,
    ROOT_PATH,
    'approved-preview root manifest',
  );
  const rootManifest = verified.rootManifest;
  validateG3ApprovalReceipt(receiptBytes, {
    approvedPreviewCommit,
    approvedPreviewTree,
    canonicalSemanticManifestSha256: rootManifest.canonicalSemanticManifest.sha256,
    externalEntrySetSha256: rootManifest.externalEntrySetSha256,
    externalManifestSha256: rootManifest.externalManifestSha256,
    previewEvidenceManifestSha256: rootManifest.previewEvidenceManifest.sha256,
    rootManifestSha256: sha256(rootBytes),
    trackedBlobManifestSha256: rootManifest.trackedBlobManifest.sha256,
  });
  const seal = {
    approvedPreviewCommit,
    approvedPreviewTree,
    canonicalSemanticManifestSha256: rootManifest.canonicalSemanticManifest.sha256,
    externalEntrySetSha256: rootManifest.externalEntrySetSha256,
    externalManifestSha256: rootManifest.externalManifestSha256,
    formatVersion: 1,
    g3ApprovalReference: `sha256:${sha256(receiptBytes)}`,
    previewEvidenceManifestSha256: rootManifest.previewEvidenceManifest.sha256,
    rootManifestSha256: sha256(rootBytes),
    trackedBlobManifestSha256: rootManifest.trackedBlobManifest.sha256,
  };
  const sealBytes = Buffer.from(canonicalJson(seal));
  assertMissingPath(coreRoot, APPROVED_PREVIEW_PATH, APPROVED_PREVIEW_PATH);
  const output = preflightOutputPath(
    coreRoot,
    APPROVED_PREVIEW_PATH,
    APPROVED_PREVIEW_PATH,
  );
  createOutputParents(coreRoot, APPROVED_PREVIEW_PATH, APPROVED_PREVIEW_PATH);
  writeFileSync(output, sealBytes);
  return { path: output, seal, sealBytes };
}

export function productionConfigForSelfTest() {
  return structuredClone(EXPECTED_CONFIG);
}
