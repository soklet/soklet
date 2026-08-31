#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  closeSync,
  existsSync,
  lstatSync,
  openSync,
  readdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, isAbsolute, parse, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { EXPECTED_GATE_EVIDENCE_CONTRACTS } from './release-validation-evidence.mjs';

const COMMIT_PATTERN = /^[0-9a-f]{40}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const SHA256_ID_PATTERN = /^sha256:[0-9a-f]{64}$/;
const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const MAXIMUM_REGISTRY_BYTES = 4 * 1024 * 1024;
const MAXIMUM_BUNDLE_BYTES = 256 * 1024 * 1024;
const MAXIMUM_ROLE_BYTES = 128 * 1024 * 1024;
const APPROVED_REGISTRY_SHA256 =
  '998a1146a4485d195e8811d566bb4d60ba93f5e0061de6520187043bf5e2f962';
const EXPECTED_GATE_IDS = Object.freeze([
  'fuzz-nightly-history',
  'mcp-benchmarks',
  'operational-history',
  'release-scans',
  'soak-nightly-history',
]);
const EXPECTED_CANDIDATE_BINDINGS = Object.freeze([
  'candidateCommit',
  'candidateMainJarSha256',
  'candidatePomSha256',
  'candidateRegistrySha256',
  'candidateTree',
  'immutableBundleSha256',
  'producerWorkflowSha256',
]);
const BUNDLE_CANDIDATE_KEYS = Object.freeze(
  EXPECTED_CANDIDATE_BINDINGS.filter((key) => key !== 'immutableBundleSha256'),
);
const IMPORT_COMMAND = 'import-release-harness-evidence.mjs --import --gate <id> '
  + '--candidate-root <absolute-path> --bundle <absolute-path> --output <absolute-path>';
const VERIFY_CONFIG_COMMAND = 'import-release-harness-evidence.mjs --verify-config';

export class ReleaseHarnessEvidenceImportError extends Error {}

function fail(message) {
  throw new ReleaseHarnessEvidenceImportError(message);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requireObject(value, label) {
  if (!isPlainObject(value))
    fail(`${label} must be an object.`);
  return value;
}

function requireArray(value, label) {
  if (!Array.isArray(value))
    fail(`${label} must be an array.`);
  return value;
}

function requireString(value, label) {
  if (typeof value !== 'string' || value.length === 0)
    fail(`${label} must be a nonempty string.`);
  return value;
}

function requireInteger(value, label, minimum = 0) {
  if (!Number.isSafeInteger(value) || value < minimum)
    fail(`${label} must be an integer greater than or equal to ${minimum}.`);
  return value;
}

function requireNumber(value, label, minimum = 0) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum)
    fail(`${label} must be a finite number greater than or equal to ${minimum}.`);
  return value;
}

function exactKeys(value, expected, label) {
  requireObject(value, label);
  const actual = Object.keys(value).sort(compareAscii);
  const wanted = [...expected].sort(compareAscii);
  if (actual.length !== wanted.length
      || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label} keys must be exactly: ${wanted.join(', ')}.`);
  }
}

function sameArray(actual, expected) {
  return Array.isArray(actual)
    && actual.length === expected.length
    && actual.every((value, index) => value === expected[index]);
}

function canonicalValue(value) {
  if (Array.isArray(value))
    return value.map(canonicalValue);
  if (isPlainObject(value)) {
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
  fail('Canonical JSON contains an unsupported value.');
}

export function canonicalJson(value) {
  return `${JSON.stringify(canonicalValue(value), null, 2)}\n`;
}

function sameJson(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function requireNonsymlinkComponents(path, label) {
  const absolute = resolve(path);
  const root = parse(absolute).root;
  let current = root;
  const rest = absolute.slice(root.length).split(sep).filter(Boolean);
  for (const component of rest) {
    current = resolve(current, component);
    if (!existsSync(current))
      return;
    if (lstatSync(current).isSymbolicLink())
      fail(`${label} contains a symlink path component: ${current}`);
  }
}

function readRegularFile(path, label, maximumBytes) {
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  requireNonsymlinkComponents(path, label);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`${label} must be a regular nonsymlink file: ${path}`);
  if (stats.size <= 0 || stats.size > maximumBytes)
    fail(`${label} has invalid size ${stats.size}: ${path}`);
  return readFileSync(path);
}

function parseCanonicalJsonBytes(bytes, label) {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail(`${label} is not valid UTF-8.`);
  if (text.includes('\r') || !text.endsWith('\n'))
    fail(`${label} must use LF and end in exactly one LF.`);
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is not valid JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text)
    fail(`${label} is not canonical sorted-key JSON.`);
  return value;
}

function parseJsonBytes(bytes, label) {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail(`${label} is not valid UTF-8.`);
  try {
    return JSON.parse(text);
  } catch (error) {
    fail(`${label} is not valid JSON: ${error.message}`);
  }
}

function readCanonicalJson(path, label, maximumBytes) {
  const bytes = readRegularFile(path, label, maximumBytes);
  return {
    bytes,
    value: parseCanonicalJsonBytes(bytes, label),
  };
}

function validateSha256(value, label) {
  if (typeof value !== 'string' || !SHA256_PATTERN.test(value))
    fail(`${label} must be lowercase SHA-256.`);
}

function validateCommit(value, label) {
  if (typeof value !== 'string' || !COMMIT_PATTERN.test(value))
    fail(`${label} must be a lowercase 40-character Git object ID.`);
}

function validateRelativePath(value, label) {
  requireString(value, label);
  if (value.includes('\\') || value.includes('\0') || value.startsWith('/')
      || value.split('/').some((part) => part.length === 0 || part === '.' || part === '..')) {
    fail(`${label} must be a normalized relative POSIX path.`);
  }
}

function validateRegistryRole(role, index, label) {
  exactKeys(role, ['kind', 'mediaType', 'name', 'ordinal', 'path', 'required'], label);
  if (role.kind !== 'file' && role.kind !== 'directory')
    fail(`${label}.kind must be file or directory.`);
  requireString(role.mediaType, `${label}.mediaType`);
  requireString(role.name, `${label}.name`);
  if (role.ordinal !== index)
    fail(`${label}.ordinal must equal ${index}.`);
  validateRelativePath(role.path, `${label}.path`);
  if (role.required !== true)
    fail(`${label}.required must be true.`);
}

function expectedRegistryRole(role, ordinal) {
  return {
    kind: role.type.toLowerCase(),
    mediaType: role.mediaType,
    name: role.role,
    ordinal,
    path: role.fileName,
    required: true,
  };
}

function validateToolchain(toolchain, label) {
  exactKeys(toolchain, ['artifact', 'digest', 'version'], label);
  requireString(toolchain.artifact, `${label}.artifact`);
  requireString(toolchain.version, `${label}.version`);
  if (typeof toolchain.digest !== 'string' || !SHA256_ID_PATTERN.test(toolchain.digest))
    fail(`${label}.digest must be sha256:<lowercase-digest>.`);
}

function validateContract(contract, index) {
  const label = `release harness contract ${index + 1}`;
  exactKeys(contract, [
    'candidateBindings',
    'contractVersion',
    'evidenceContract',
    'id',
    'importerMode',
    'policy',
    'producer',
    'retention',
    'roles',
    'toolchains',
  ], label);
  if (contract.id !== EXPECTED_GATE_IDS[index])
    fail(`${label}.id must be ${EXPECTED_GATE_IDS[index]}.`);
  if (!sameArray(contract.candidateBindings, EXPECTED_CANDIDATE_BINDINGS))
    fail(`${label}.candidateBindings drifted from the approved ordered binding set.`);
  if (contract.contractVersion !== 1)
    fail(`${label}.contractVersion must be 1.`);
  const evidence = EXPECTED_GATE_EVIDENCE_CONTRACTS[contract.id];
  if (evidence === undefined)
    fail(`${label} has no validator-owned evidence contract.`);
  if (contract.evidenceContract !== evidence.contractId)
    fail(`${label}.evidenceContract does not match release validation policy.`);
  exactKeys(contract.importerMode, ['import', 'verifierCommand', 'verifyConfig'], `${label}.importerMode`);
  if (contract.importerMode.import !== IMPORT_COMMAND
      || contract.importerMode.verifyConfig !== VERIFY_CONFIG_COMMAND
      || contract.importerMode.verifierCommand !== evidence.command) {
    fail(`${label}.importerMode drifted from the approved importer/verifier command.`);
  }
  requireObject(contract.policy, `${label}.policy`);
  if (Object.keys(contract.policy).length === 0)
    fail(`${label}.policy must not be empty.`);
  requireString(contract.producer, `${label}.producer`);
  exactKeys(
    contract.retention,
    ['acceptedBundleAndReceiptDaysAfterG5OrInvalidation', 'rawProducerArtifactDays'],
    `${label}.retention`,
  );
  if (contract.retention.acceptedBundleAndReceiptDaysAfterG5OrInvalidation !== 400
      || contract.retention.rawProducerArtifactDays !== 90) {
    fail(`${label}.retention drifted from approved values.`);
  }
  const roles = requireArray(contract.roles, `${label}.roles`);
  roles.forEach((role, roleIndex) => validateRegistryRole(
    role,
    roleIndex,
    `${label}.roles[${roleIndex}]`,
  ));
  const expectedRoles = evidence.roles.map(expectedRegistryRole);
  if (!sameJson(roles, expectedRoles))
    fail(`${label}.roles do not match the validator-owned ordered receipt roles.`);
  const toolchains = requireArray(contract.toolchains, `${label}.toolchains`);
  if (toolchains.length === 0)
    fail(`${label}.toolchains must not be empty.`);
  toolchains.forEach((toolchain, toolchainIndex) => validateToolchain(
    toolchain,
    `${label}.toolchains[${toolchainIndex}]`,
  ));
}

export function verifyReleaseHarnessConfiguration(
  registryPath = resolve(dirname(fileURLToPath(import.meta.url)), '../release/release-harness-contracts.json'),
) {
  const absoluteRegistryPath = resolve(registryPath);
  const { bytes, value } = readCanonicalJson(
    absoluteRegistryPath,
    'Release-harness contract registry',
    MAXIMUM_REGISTRY_BYTES,
  );
  const registrySha256 = sha256(bytes);
  if (registrySha256 !== APPROVED_REGISTRY_SHA256) {
    fail(
      'Release-harness contract registry bytes differ from the exact MCP-0-12 '
        + `approval: expected ${APPROVED_REGISTRY_SHA256}, found ${registrySha256}.`,
    );
  }
  exactKeys(value, ['contracts', 'formatVersion'], 'Release-harness contract registry');
  if (value.formatVersion !== 1)
    fail('Release-harness contract registry formatVersion must be 1.');
  const contracts = requireArray(value.contracts, 'Release-harness contract registry contracts');
  if (contracts.length !== EXPECTED_GATE_IDS.length)
    fail(`Release-harness contract registry must contain exactly ${EXPECTED_GATE_IDS.length} contracts.`);
  contracts.forEach(validateContract);
  return Object.freeze({
    contracts: new Map(contracts.map((contract) => [contract.id, contract])),
    registry: value,
    registryPath: absoluteRegistryPath,
    registrySha256,
  });
}

export function verifyReleaseHarnessManifestParity(
  configuration,
  manifestPath = resolve(dirname(configuration.registryPath), 'release-validation-manifest.json'),
) {
  requireObject(configuration, 'release-harness configuration');
  const label = 'release-validation manifest for harness parity';
  const value = parseJsonBytes(readRegularFile(
    manifestPath,
    label,
    MAXIMUM_REGISTRY_BYTES,
  ), label);
  const gates = requireArray(value.gates, 'release-validation manifest gates');
  const gateById = new Map(gates.map((gate) => [gate.id, gate]));
  for (const [id, contract] of configuration.contracts) {
    const gate = gateById.get(id);
    if (gate === undefined)
      fail(`release-validation manifest is missing harness gate ${id}.`);
    if (gate.evidenceContract !== contract.evidenceContract)
      fail(`release-validation manifest ${id} evidence contract drifted from the registry.`);
    if (gate.status !== 'BLOCKED_HARNESS_MISSING' && gate.status !== 'READY')
      fail(`release-validation manifest ${id} has an invalid harness lifecycle status.`);
  }
  return true;
}

function decodeBase64(value, label) {
  requireString(value, label);
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value))
    fail(`${label} is not canonical base64.`);
  const bytes = Buffer.from(value, 'base64');
  if (bytes.toString('base64') !== value || bytes.length === 0 || bytes.length > MAXIMUM_ROLE_BYTES)
    fail(`${label} is empty, noncanonical, or exceeds the role-size bound.`);
  return bytes;
}

function validateBundleRole(role, expected, index) {
  const label = `bundle role ${index + 1}`;
  const commonKeys = ['kind', 'mediaType', 'name', 'ordinal', 'required', 'sha256'];
  exactKeys(
    role,
    expected.kind === 'file' ? [...commonKeys, 'bytesBase64'] : [...commonKeys, 'entries'],
    label,
  );
  for (const key of ['kind', 'mediaType', 'name', 'ordinal', 'required']) {
    if (role[key] !== expected[key])
      fail(`${label}.${key} does not match the registered role.`);
  }
  validateSha256(role.sha256, `${label}.sha256`);
  if (expected.kind === 'file') {
    const bytes = decodeBase64(role.bytesBase64, `${label}.bytesBase64`);
    if (sha256(bytes) !== role.sha256)
      fail(`${label}.sha256 does not match its declared bytes.`);
    return { bytes, descriptor: { ...expected, sha256: role.sha256, size: bytes.length } };
  }
  const entries = requireArray(role.entries, `${label}.entries`);
  if (entries.length === 0)
    fail(`${label}.entries must not be empty.`);
  let previousPath = null;
  let totalBytes = 0;
  const descriptors = entries.map((entry, entryIndex) => {
    const entryLabel = `${label}.entries[${entryIndex}]`;
    exactKeys(entry, ['bytesBase64', 'path', 'sha256'], entryLabel);
    validateRelativePath(entry.path, `${entryLabel}.path`);
    if (previousPath !== null && compareAscii(previousPath, entry.path) >= 0)
      fail(`${label}.entries must be strictly ASCII path ordered and unique.`);
    previousPath = entry.path;
    validateSha256(entry.sha256, `${entryLabel}.sha256`);
    const bytes = decodeBase64(entry.bytesBase64, `${entryLabel}.bytesBase64`);
    if (sha256(bytes) !== entry.sha256)
      fail(`${entryLabel}.sha256 does not match its declared bytes.`);
    totalBytes += bytes.length;
    if (totalBytes > MAXIMUM_ROLE_BYTES)
      fail(`${label} exceeds the directory-role byte bound.`);
    return { bytes, path: entry.path, sha256: entry.sha256, size: bytes.length };
  });
  if (sha256(Buffer.from(canonicalJson(role.entries), 'utf8')) !== role.sha256)
    fail(`${label}.sha256 does not match its canonical entry set.`);
  return {
    descriptors,
    descriptor: {
      ...expected,
      entries: descriptors.map(({ path, sha256: digest, size }) => ({ path, sha256: digest, size })),
      entryCount: descriptors.length,
      sha256: role.sha256,
      size: totalBytes,
    },
  };
}

function validateCandidate(candidate, label = 'bundle candidate') {
  exactKeys(candidate, BUNDLE_CANDIDATE_KEYS, label);
  validateCommit(candidate.candidateCommit, `${label}.candidateCommit`);
  validateCommit(candidate.candidateTree, `${label}.candidateTree`);
  for (const key of BUNDLE_CANDIDATE_KEYS.filter((key) => key.endsWith('Sha256')))
    validateSha256(candidate[key], `${label}.${key}`);
}

function policySha256(contract) {
  return sha256(Buffer.from(canonicalJson(contract.policy), 'utf8'));
}

function toolchainsSha256(contract) {
  return sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8'));
}

function parseEvidenceJson(bytes, label) {
  return parseCanonicalJsonBytes(bytes, label);
}

function validateCommonEvidence(value, contract, candidate, expectedExtraKeys, label) {
  exactKeys(value, [
    'candidate',
    'formatVersion',
    'gate',
    'policySha256',
    'producerStatus',
    'toolchainsSha256',
    ...expectedExtraKeys,
  ], label);
  if (value.formatVersion !== 1 || value.gate !== contract.id)
    fail(`${label} formatVersion/gate does not match the contract.`);
  validateCandidate(value.candidate, `${label}.candidate`);
  if (!sameJson(value.candidate, candidate))
    fail(`${label}.candidate does not match the immutable bundle candidate.`);
  if (value.policySha256 !== policySha256(contract))
    fail(`${label}.policySha256 does not match the registered policy.`);
  if (value.toolchainsSha256 !== toolchainsSha256(contract))
    fail(`${label}.toolchainsSha256 does not match the registered toolchains.`);
  if (value.producerStatus !== 'PASS')
    fail(`${label}.producerStatus must be PASS.`);
}

function parseUtc(value, label) {
  if (typeof value !== 'string' || !ISO_UTC_PATTERN.test(value))
    fail(`${label} must be an exact UTC timestamp without fractional seconds.`);
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds)
      || new Date(milliseconds).toISOString().replace('.000Z', 'Z') !== value)
    fail(`${label} is not a real UTC timestamp.`);
  return milliseconds;
}

function validateHistoryWindow(runs, policy, now, label) {
  if (runs.length !== policy.consecutiveUtcDates)
    fail(`${label} must contain exactly ${policy.consecutiveUtcDates} runs.`);
  const seenIds = new Set();
  const times = runs.map((run, index) => {
    requireObject(run, `${label}[${index}]`);
    requireString(run.id, `${label}[${index}].id`);
    if (seenIds.has(run.id))
      fail(`${label} contains duplicate run ID ${run.id}.`);
    seenIds.add(run.id);
    const time = parseUtc(run.completedAt, `${label}[${index}].completedAt`);
    if (run.id !== run.completedAt.slice(0, 10))
      fail(`${label}[${index}].id must equal its completion UTC date.`);
    return time;
  });
  for (let index = 1; index < times.length; index++) {
    const previousDate = Date.parse(`${runs[index - 1].id}T00:00:00Z`);
    const currentDate = Date.parse(`${runs[index].id}T00:00:00Z`);
    if (currentDate - previousDate !== 86_400_000)
      fail(`${label} must contain consecutive UTC dates.`);
    const hours = (times[index] - times[index - 1]) / 3_600_000;
    if (hours < policy.cadenceHours - policy.cadenceToleranceHours
        || hours > policy.cadenceHours + policy.cadenceToleranceHours) {
      fail(`${label} violates the approved cadence window.`);
    }
  }
  const newestAge = (now - times.at(-1)) / 3_600_000;
  const oldestAge = (now - times[0]) / 3_600_000;
  if (newestAge < 0 || newestAge > policy.newestMaximumAgeHours)
    fail(`${label} newest run is stale or from the future.`);
  if (oldestAge < policy.oldestAgeHours.minimum || oldestAge > policy.oldestAgeHours.maximum)
    fail(`${label} oldest run is outside the approved history window.`);
}

function validateFuzzHistory(bytes, contract, candidate, now) {
  const value = parseEvidenceJson(bytes, 'fuzz-nightly history');
  validateCommonEvidence(value, contract, candidate, ['runs'], 'fuzz-nightly history');
  const runs = requireArray(value.runs, 'fuzz-nightly history.runs');
  validateHistoryWindow(runs, contract.policy, now, 'fuzz-nightly history.runs');
  for (const [runIndex, run] of runs.entries()) {
    exactKeys(run, ['completedAt', 'corpusHashes', 'id', 'outcome', 'targets'], `fuzz run ${runIndex + 1}`);
    if (run.outcome !== 'PASS')
      fail(`fuzz run ${runIndex + 1} did not PASS.`);
    const targets = requireArray(run.targets, `fuzz run ${runIndex + 1}.targets`);
    if (targets.length !== contract.policy.targets.length)
      fail(`fuzz run ${runIndex + 1} has an incomplete target universe.`);
    targets.forEach((target, targetIndex) => {
      exactKeys(target, ['durationSeconds', 'id', 'ordinal', 'outcome'], `fuzz target ${targetIndex + 1}`);
      const expected = contract.policy.targets[targetIndex];
      if (target.id !== expected.id || target.ordinal !== expected.ordinal
          || target.durationSeconds !== contract.policy.perTargetDurationSeconds
          || target.outcome !== 'PASS') {
        fail(`fuzz run ${runIndex + 1} target ${targetIndex + 1} violates approved policy.`);
      }
    });
    const hashes = requireArray(run.corpusHashes, `fuzz run ${runIndex + 1}.corpusHashes`);
    if (hashes.length !== targets.length || new Set(hashes).size !== hashes.length)
      fail(`fuzz run ${runIndex + 1} corpus hashes must be complete, ordered, and unique.`);
    hashes.forEach((digest, hashIndex) => validateSha256(
      digest,
      `fuzz run ${runIndex + 1}.corpusHashes[${hashIndex}]`,
    ));
  }
}

function validateSoakHistory(bytes, contract, candidate, now) {
  const value = parseEvidenceJson(bytes, 'soak-nightly history');
  validateCommonEvidence(value, contract, candidate, ['runs'], 'soak-nightly history');
  const runs = requireArray(value.runs, 'soak-nightly history.runs');
  validateHistoryWindow(runs, contract.policy, now, 'soak-nightly history.runs');
  for (const [runIndex, run] of runs.entries()) {
    exactKeys(
      run,
      ['completedAt', 'id', 'outcome', 'profile', 'profileSha256', 'scenarios', 'surefire'],
      `soak run ${runIndex + 1}`,
    );
    if (run.outcome !== 'PASS' || run.profile !== contract.policy.profile
        || run.profileSha256 !== contract.policy.profileSha256
        || !sameJson(run.surefire, contract.policy.surefire)) {
      fail(`soak run ${runIndex + 1} profile, Surefire, or outcome violates policy.`);
    }
    const scenarios = requireArray(run.scenarios, `soak run ${runIndex + 1}.scenarios`);
    if (scenarios.length !== contract.policy.scenarios.length)
      fail(`soak run ${runIndex + 1} has an incomplete scenario universe.`);
    scenarios.forEach((scenario, scenarioIndex) => {
      exactKeys(
        scenario,
        ['id', 'ordinal', 'outcome', 'report', 'reportSha256', 'resourceThresholdsPassed'],
        `soak scenario ${scenarioIndex + 1}`,
      );
      const expected = contract.policy.scenarios[scenarioIndex];
      if (scenario.id !== expected.id || scenario.ordinal !== expected.ordinal
          || scenario.outcome !== 'PASS' || scenario.resourceThresholdsPassed !== true) {
        fail(`soak run ${runIndex + 1} scenario ${scenarioIndex + 1} violates policy.`);
      }
      validateSha256(scenario.reportSha256, `soak scenario ${scenarioIndex + 1}.reportSha256`);
      if (scenario.reportSha256
          !== sha256(Buffer.from(canonicalJson(scenario.report), 'utf8'))) {
        fail(`soak scenario ${scenarioIndex + 1} report digest does not match its inline report.`);
      }
      exactKeys(
        scenario.report,
        [
          'candidateCommit',
          'completedAt',
          'outcome',
          'profileSha256',
          'resourceBaseline',
          'resourceDeltas',
          'scenario',
          'surefire',
          'thresholdsPassed',
        ],
        `soak scenario ${scenarioIndex + 1}.report`,
      );
      if (scenario.report.candidateCommit !== candidate.candidateCommit
          || scenario.report.completedAt !== run.completedAt
          || scenario.report.outcome !== 'PASS'
          || scenario.report.profileSha256 !== contract.policy.profileSha256
          || scenario.report.scenario !== scenario.id
          || scenario.report.thresholdsPassed !== true
          || !sameJson(scenario.report.surefire, {
            errors: 0,
            failures: 0,
            skipped: 0,
            tests: 1,
          })) {
        fail(`soak scenario ${scenarioIndex + 1} inline report violates candidate/profile/result policy.`);
      }
      for (const resourceField of ['resourceBaseline', 'resourceDeltas']) {
        exactKeys(
          scenario.report[resourceField],
          ['fileDescriptors', 'heapBytes', 'liveThreads'],
          `soak scenario ${scenarioIndex + 1}.report.${resourceField}`,
        );
        for (const measure of ['fileDescriptors', 'heapBytes', 'liveThreads']) {
          requireInteger(
            scenario.report[resourceField][measure],
            `soak scenario ${scenarioIndex + 1}.report.${resourceField}.${measure}`,
          );
        }
      }
    });
  }
}

function validateOperationalHistory(bytes, contract, candidate) {
  const value = parseEvidenceJson(bytes, 'operational history');
  validateCommonEvidence(value, contract, candidate, [
    'cadenceSeconds',
    'drainSeconds',
    'durationSeconds',
    'finalResourceDeltas',
    'loadShape',
    'maximumSampleGapSeconds',
    'outcomes',
    'postIntervalReserveSeconds',
    'resourceBaselines',
    'samples',
    'sensitiveCanaries',
    'terminalFrameworkCardinality',
  ], 'operational history');
  const policy = contract.policy;
  if (value.cadenceSeconds !== policy.cadenceSeconds
      || value.durationSeconds !== policy.durationSeconds
      || value.maximumSampleGapSeconds !== policy.maximumSampleGapSeconds
      || value.postIntervalReserveSeconds !== policy.postIntervalReserveSeconds
      || value.drainSeconds > policy.drainMaximumSeconds
      || value.sensitiveCanaries !== policy.sensitiveCanariesAllowed
      || value.terminalFrameworkCardinality !== policy.terminalFrameworkCardinality
      || !sameJson(value.loadShape, policy.loadShape)) {
    fail('operational history configuration or terminal outcomes drifted from policy.');
  }
  requireNumber(value.drainSeconds, 'operational history.drainSeconds');
  if (requireArray(value.outcomes, 'operational history.outcomes').length !== 0)
    fail('operational history contains a zero-tolerance outcome.');
  exactKeys(value.resourceBaselines, ['http', 'mcpAndRealtime'], 'operational history.resourceBaselines');
  for (const key of ['http', 'mcpAndRealtime']) {
    exactKeys(
      value.resourceBaselines[key],
      ['fileDescriptors', 'heapBytes', 'liveThreads'],
      `resourceBaselines.${key}`,
    );
    for (const measure of ['fileDescriptors', 'heapBytes', 'liveThreads'])
      requireInteger(value.resourceBaselines[key][measure], `resourceBaselines.${key}.${measure}`);
  }
  exactKeys(value.finalResourceDeltas, ['http', 'mcpAndRealtime'], 'operational history.finalResourceDeltas');
  for (const key of ['http', 'mcpAndRealtime']) {
    exactKeys(value.finalResourceDeltas[key], ['fileDescriptors', 'heapBytes', 'liveThreads'], `finalResourceDeltas.${key}`);
    for (const measure of ['fileDescriptors', 'heapBytes', 'liveThreads']) {
      const actual = requireInteger(value.finalResourceDeltas[key][measure], `finalResourceDeltas.${key}.${measure}`);
      if (actual > policy.finalResourceDeltas[key][measure])
        fail(`operational final resource delta exceeds policy: ${key}.${measure}.`);
    }
  }
  const samples = requireArray(value.samples, 'operational history.samples');
  const requiredSpanSeconds = policy.durationSeconds + policy.postIntervalReserveSeconds;
  const minimumCount = Math.floor(requiredSpanSeconds / policy.cadenceSeconds) + 1;
  if (samples.length < minimumCount)
    fail('operational history has an incomplete sample window.');
  let previous = null;
  for (const [index, sample] of samples.entries()) {
    exactKeys(
      sample,
      [
        'at',
        'droppedLogRecords',
        'frameworkMetricCardinality',
        'rejectedMetricDeliveries',
        'resources',
        'unregisteredMetricDimensions',
      ],
      `operational sample ${index + 1}`,
    );
    const at = parseUtc(sample.at, `operational sample ${index + 1}.at`);
    if (previous !== null) {
      const gap = (at - previous) / 1000;
      if (gap <= 0 || gap > policy.maximumSampleGapSeconds)
        fail('operational history contains a nonmonotonic or excessive sample gap.');
    }
    previous = at;
    if (sample.droppedLogRecords !== 0 || sample.rejectedMetricDeliveries !== 0
        || sample.unregisteredMetricDimensions !== 0
        || sample.frameworkMetricCardinality !== 0) {
      fail('operational history contains a zero-tolerance delivery outcome.');
    }
    exactKeys(sample.resources, ['http', 'mcpAndRealtime'], `operational sample ${index + 1}.resources`);
    for (const key of ['http', 'mcpAndRealtime']) {
      exactKeys(
        sample.resources[key],
        ['fileDescriptors', 'heapBytes', 'liveThreads'],
        `operational sample ${index + 1}.resources.${key}`,
      );
      for (const measure of ['fileDescriptors', 'heapBytes', 'liveThreads']) {
        requireInteger(
          sample.resources[key][measure],
          `operational sample ${index + 1}.resources.${key}.${measure}`,
        );
      }
    }
  }
  const spanSeconds = (parseUtc(samples.at(-1).at, 'last operational sample')
    - parseUtc(samples[0].at, 'first operational sample')) / 1000;
  if (spanSeconds < requiredSpanSeconds)
    fail('operational history duration or post-interval reserve is incomplete.');
  if (!sameJson(value.resourceBaselines, samples[0].resources))
    fail('operational resource baselines do not match the first history sample.');
  const lastResources = samples.at(-1).resources;
  for (const key of ['http', 'mcpAndRealtime']) {
    for (const measure of ['fileDescriptors', 'heapBytes', 'liveThreads']) {
      const derivedGrowth = Math.max(
        0,
        lastResources[key][measure] - value.resourceBaselines[key][measure],
      );
      if (value.finalResourceDeltas[key][measure] !== derivedGrowth) {
        fail(`operational final resource delta is not derived from history: ${key}.${measure}.`);
      }
    }
  }
}

function parseReportJson(bytes, label) {
  return parseJsonBytes(bytes, label);
}

function validateEmptySarif(bytes, expectedTool, label) {
  const value = requireObject(parseReportJson(bytes, label), label);
  if (value.version !== '2.1.0')
    fail(`${label} must be SARIF 2.1.0.`);
  const runs = requireArray(value.runs, `${label}.runs`);
  if (runs.length === 0)
    fail(`${label} must contain at least one scanner run.`);
  for (const [index, run] of runs.entries()) {
    const driver = requireObject(
      requireObject(requireObject(run, `${label}.runs[${index}]`).tool,
        `${label}.runs[${index}].tool`).driver,
      `${label}.runs[${index}].tool.driver`,
    );
    if (typeof driver.name !== 'string'
        || driver.name.toLowerCase() !== expectedTool.toLowerCase()) {
      fail(`${label} run ${index + 1} is not from the registered ${expectedTool} scanner.`);
    }
    if (requireArray(run.results, `${label}.runs[${index}].results`).length !== 0)
      fail(`${label} contains an unapproved scanner result.`);
  }
}

function validateEmptySpotBugs(bytes) {
  const label = 'SpotBugs XML report';
  const text = bytes.toString('utf8');
  const prolog = '<?xml version="1.0" encoding="UTF-8"?>\n';
  if (!Buffer.from(text, 'utf8').equals(bytes)
      || text.includes('\r') || !text.endsWith('\n')
      || !text.startsWith(prolog)) {
    fail(`${label} is malformed or contains a finding/error.`);
  }

  const legalXmlCharacter = (codePoint) => codePoint === 0x09
    || codePoint === 0x0A
    || codePoint === 0x0D
    || (codePoint >= 0x20 && codePoint <= 0xD7FF)
    || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
    || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
  const validateCharactersAndReferences = (value) => {
    for (const character of value) {
      if (!legalXmlCharacter(character.codePointAt(0)))
        fail(`${label} contains a prohibited XML character.`);
    }
    for (let index = 0; index < value.length; index++) {
      if (value[index] !== '&')
        continue;
      const end = value.indexOf(';', index + 1);
      if (end === -1)
        fail(`${label} contains an unterminated XML reference.`);
      const reference = value.slice(index + 1, end);
      const named = ['amp', 'apos', 'gt', 'lt', 'quot'].includes(reference);
      const decimal = reference.match(/^#([0-9]+)$/u);
      const hexadecimal = reference.match(/^#x([0-9A-Fa-f]+)$/u);
      const referencedCodePoint = decimal
        ? Number.parseInt(decimal[1], 10)
        : hexadecimal ? Number.parseInt(hexadecimal[1], 16) : null;
      if (!named && (referencedCodePoint === null || !legalXmlCharacter(referencedCodePoint)))
        fail(`${label} contains a prohibited XML entity/reference.`);
      index = end;
    }
  };
  const parseStartTag = (rawTag) => {
    const selfClosingMatch = rawTag.match(/\/\s*$/u);
    const selfClosing = selfClosingMatch !== null;
    const tag = selfClosing ? rawTag.slice(0, selfClosingMatch.index) : rawTag;
    const nameMatch = tag.match(/^[A-Za-z_][A-Za-z0-9_.:-]*/u);
    if (!nameMatch)
      fail(`${label} contains an invalid element name.`);
    const name = nameMatch[0];
    const attributes = new Set();
    let index = name.length;
    while (index < tag.length) {
      if (!/\s/u.test(tag[index]))
        fail(`${label} contains malformed element attributes.`);
      while (index < tag.length && /\s/u.test(tag[index]))
        index++;
      if (index === tag.length)
        break;
      const attributeMatch = tag.slice(index).match(/^[A-Za-z_][A-Za-z0-9_.:-]*/u);
      if (!attributeMatch || attributes.has(attributeMatch[0]))
        fail(`${label} contains an invalid or duplicate attribute.`);
      const attributeName = attributeMatch[0];
      attributes.add(attributeName);
      index += attributeName.length;
      while (index < tag.length && /\s/u.test(tag[index]))
        index++;
      if (tag[index] !== '=')
        fail(`${label} contains an attribute without a value.`);
      index++;
      while (index < tag.length && /\s/u.test(tag[index]))
        index++;
      const quote = tag[index];
      if (quote !== '"' && quote !== "'")
        fail(`${label} contains an unquoted attribute value.`);
      const end = tag.indexOf(quote, index + 1);
      if (end === -1)
        fail(`${label} contains an unterminated attribute value.`);
      const attributeValue = tag.slice(index + 1, end);
      if (attributeValue.includes('<'))
        fail(`${label} contains an illegal attribute value.`);
      validateCharactersAndReferences(attributeValue);
      index = end + 1;
    }
    return { name, selfClosing };
  };

  const body = text.slice(prolog.length);
  const stack = [];
  let cursor = 0;
  let rootSeen = false;
  let rootClosed = false;
  while (cursor < body.length) {
    if (body[cursor] !== '<') {
      const nextTag = body.indexOf('<', cursor);
      const end = nextTag === -1 ? body.length : nextTag;
      const characterData = body.slice(cursor, end);
      if (stack.length === 0 && characterData.trim() !== '')
        fail(`${label} contains data outside its root element.`);
      if (characterData.includes(']]>'))
        fail(`${label} contains an illegal character-data terminator.`);
      validateCharactersAndReferences(characterData);
      cursor = end;
      continue;
    }
    if (body.startsWith('<!', cursor) || body.startsWith('<?', cursor))
      fail(`${label} contains a prohibited declaration, entity, or processing instruction.`);
    let quote = null;
    let end = -1;
    for (let index = cursor + 1; index < body.length; index++) {
      const character = body[index];
      if (quote !== null) {
        if (character === quote)
          quote = null;
      } else if (character === '"' || character === "'") {
        quote = character;
      } else if (character === '<') {
        fail(`${label} contains an unterminated element.`);
      } else if (character === '>') {
        end = index;
        break;
      }
    }
    if (end === -1 || quote !== null)
      fail(`${label} contains an unterminated element.`);
    const rawTag = body.slice(cursor + 1, end);
    const closingMatch = rawTag.match(/^\/([A-Za-z_][A-Za-z0-9_.:-]*)\s*$/u);
    if (closingMatch) {
      if (stack.length === 0 || stack.at(-1) !== closingMatch[1])
        fail(`${label} contains an unmatched closing element.`);
      stack.pop();
      if (stack.length === 0)
        rootClosed = true;
    } else {
      if (rawTag.startsWith('/'))
        fail(`${label} contains a malformed closing element.`);
      const { name, selfClosing } = parseStartTag(rawTag);
      const localName = name.includes(':') ? name.slice(name.lastIndexOf(':') + 1) : name;
      if (localName === 'BugInstance' || localName === 'Error')
        fail(`${label} contains a finding/error.`);
      if (stack.length === 0) {
        if (rootSeen || rootClosed || name !== 'BugCollection' || selfClosing)
          fail(`${label} must contain exactly one explicit BugCollection root.`);
        rootSeen = true;
      } else if (name === 'BugCollection') {
        fail(`${label} contains a nested BugCollection element.`);
      }
      if (!selfClosing)
        stack.push(name);
    }
    cursor = end + 1;
  }
  if (!rootSeen || !rootClosed || stack.length !== 0)
    fail(`${label} does not contain a complete BugCollection document.`);
}

function validateScanProvenance(bytes, contract, candidate) {
  const value = parseCanonicalJsonBytes(bytes, 'release scan toolchain provenance');
  exactKeys(value, [
    'candidate',
    'codeql',
    'formatVersion',
    'gitleaks',
    'producerWorkflowSha256',
    'spotbugs',
    'toolchains',
  ], 'release scan toolchain provenance');
  if (value.formatVersion !== 1
      || !sameJson(value.candidate, candidate)
      || !sameJson(value.codeql, contract.policy.codeql)
      || !sameJson(value.gitleaks, contract.policy.gitleaks)
      || !sameJson(value.spotbugs, contract.policy.spotbugs)
      || !sameJson(value.toolchains, contract.toolchains)
      || value.producerWorkflowSha256 !== candidate.producerWorkflowSha256) {
    fail('release scan toolchain provenance drifted from the candidate or registry.');
  }
}

function validateReleaseScans(fileBytes, directoryRole, contract, candidate) {
  const value = parseEvidenceJson(fileBytes, 'release scan summary');
  validateCommonEvidence(value, contract, candidate, [
    'allowlist',
    'findings',
    'reports',
    'runtimeDependencySurface',
  ], 'release scan summary');
  const policy = contract.policy;
  const reports = requireArray(value.reports, 'release scan summary.reports');
  if (reports.length !== policy.reports.length)
    fail('release scan summary has a missing or extra report.');
  const entryByPath = new Map(directoryRole.descriptors.map((entry) => [entry.path, entry]));
  reports.forEach((report, index) => {
    exactKeys(report, ['name', 'ordinal', 'outcome', 'sha256'], `scan report ${index + 1}`);
    const expected = policy.reports[index];
    if (report.name !== expected.name || report.ordinal !== expected.ordinal || report.outcome !== 'PASS')
      fail(`scan report ${index + 1} does not match approved order/status.`);
    validateSha256(report.sha256, `scan report ${index + 1}.sha256`);
    if (entryByPath.get(report.name)?.sha256 !== report.sha256)
      fail(`scan report ${report.name} is missing or its bytes do not match the summary.`);
  });
  if (entryByPath.size !== reports.length)
    fail('scan-report directory contains an extra report.');
  validateEmptySarif(
    entryByPath.get('00-codeql-java.sarif').bytes,
    'CodeQL',
    'CodeQL SARIF report',
  );
  validateEmptySpotBugs(entryByPath.get('01-spotbugs.xml').bytes);
  validateEmptySarif(
    entryByPath.get('02-gitleaks.sarif').bytes,
    'gitleaks',
    'gitleaks SARIF report',
  );
  const gitleaksJson = parseReportJson(
    entryByPath.get('03-gitleaks.json').bytes,
    'gitleaks JSON report',
  );
  if (!Array.isArray(gitleaksJson) || gitleaksJson.length !== 0)
    fail('gitleaks JSON report contains an unapproved finding.');
  const runtimeSurface = parseCanonicalJsonBytes(
    entryByPath.get('04-runtime-dependency-surface.json').bytes,
    'runtime dependency surface report',
  );
  exactKeys(
    runtimeSurface,
    ['externalRuntimeDependencyCount', 'formatVersion'],
    'runtime dependency surface report',
  );
  if (runtimeSurface.formatVersion !== 1
      || runtimeSurface.externalRuntimeDependencyCount !== 0) {
    fail('runtime dependency surface report is nonzero or malformed.');
  }
  validateScanProvenance(
    entryByPath.get('05-toolchain-provenance.json').bytes,
    contract,
    candidate,
  );
  exactKeys(
    value.runtimeDependencySurface,
    ['externalRuntimeDependencyCount'],
    'release scan runtimeDependencySurface',
  );
  if (value.runtimeDependencySurface.externalRuntimeDependencyCount
      !== policy.runtimeDependencySurface.expectedExternalRuntimeDependencyCount) {
    fail('release scan runtime dependency surface is nonzero.');
  }
  const allowlist = requireArray(value.allowlist, 'release scan allowlist');
  if (allowlist.length !== 0) {
    fail(
      'release scan allowlist is nonempty, but the exact MCP-0-12 registry '
        + 'contains no individually approved candidate-tracked exceptions.',
    );
  }
  const allowlistKeys = new Set(allowlist.map(
    (entry) => `${entry.scanner}\0${entry.ruleId}\0${entry.path}\0${entry.fingerprint}`,
  ));
  if (allowlistKeys.size !== allowlist.length)
    fail('release scan allowlist contains a duplicate exception.');
  const findings = requireArray(value.findings, 'release scan findings');
  if (findings.length !== 0)
    fail('release scan summary contains an unapproved finding.');
  findings.forEach((finding, index) => {
    const label = `release scan finding ${index + 1}`;
    exactKeys(finding, ['accepted', 'fingerprint', 'path', 'ruleId', 'scanner', 'severity'], label);
    for (const key of ['fingerprint', 'path', 'ruleId', 'scanner', 'severity'])
      requireString(finding[key], `${label}.${key}`);
    if (finding.severity === 'HIGH' || finding.severity === 'CRITICAL')
      fail(`${label} has prohibited ${finding.severity} severity.`);
    const key = `${finding.scanner}\0${finding.ruleId}\0${finding.path}\0${finding.fingerprint}`;
    if (finding.accepted !== true || !allowlistKeys.has(key))
      fail(`${label} is not covered by an exact approved allowlist entry.`);
  });
}

function validateBenchmarks(bytes, logBytes, contract, candidate) {
  const value = parseEvidenceJson(bytes, 'MCP benchmark results');
  validateCommonEvidence(value, contract, candidate, [
    'benchmarkLogSha256',
    'comparison',
    'configuration',
    'environment',
    'profile1Baseline',
    'repetitions',
    'review',
  ], 'MCP benchmark results');
  const policy = contract.policy;
  validateSha256(value.benchmarkLogSha256, 'MCP benchmark benchmarkLogSha256');
  if (value.benchmarkLogSha256 !== sha256(logBytes))
    fail('MCP benchmark log bytes do not match benchmarkLogSha256.');
  const expectedConfiguration = {
    candidateJvm: policy.candidateJvm,
    forks: policy.forks,
    measurement: policy.measurement,
    threads: policy.threads,
    warmup: policy.warmup,
  };
  if (!sameJson(value.configuration, expectedConfiguration))
    fail('MCP benchmark execution configuration drifted from approved policy.');
  exactKeys(
    value.comparison,
    ['artifact', 'jarSha256', 'jsonParseScoreRatio', 'jsonWriteScoreRatio', 'pomSha256'],
    'MCP benchmark comparison',
  );
  for (const key of ['artifact', 'jarSha256', 'pomSha256']) {
    if (value.comparison[key] !== policy.comparison[key])
      fail(`MCP benchmark comparison ${key} drifted from policy.`);
  }
  const parseRatio = requireNumber(value.comparison.jsonParseScoreRatio, 'jsonParseScoreRatio');
  const writeRatio = requireNumber(value.comparison.jsonWriteScoreRatio, 'jsonWriteScoreRatio');
  exactKeys(
    value.review,
    ['approvalReference', 'regressionApprovalReference', 'regressionApproved', 'releaseNoteSha256', 'signoffReference'],
    'MCP benchmark review',
  );
  requireString(value.review.approvalReference, 'MCP benchmark review.approvalReference');
  requireString(value.review.signoffReference, 'MCP benchmark review.signoffReference');
  const regression = parseRatio < policy.comparison.minimumJsonParseWriteScoreRatio
    || writeRatio < policy.comparison.minimumJsonParseWriteScoreRatio;
  if (regression) {
    fail(
      'MCP benchmark regression is not authorized by the exact MCP-0-12 '
        + 'registry; a candidate-tracked release-note/owner-approval contract '
        + 'amendment is required before import.',
    );
  } else if (value.review.regressionApproved !== false
      || value.review.regressionApprovalReference !== null
      || value.review.releaseNoteSha256 !== null) {
    fail('MCP benchmark non-regression must not claim regression approval.');
  }
  exactKeys(
    value.environment,
    ['architecture', 'cpuModel', 'governor', 'image', 'kernel', 'microcode', 'sameBoot', 'samePhysicalRunner', 'turboState'],
    'MCP benchmark environment',
  );
  for (const key of ['architecture', 'cpuModel', 'governor', 'image', 'kernel', 'microcode', 'turboState'])
    requireString(value.environment[key], `MCP benchmark environment.${key}`);
  if (value.environment.architecture !== 'x86_64'
      || !/^ubuntu-24\.04@[A-Za-z0-9][A-Za-z0-9._-]*$/u.test(value.environment.image)) {
    fail('MCP benchmarks require a concrete ubuntu-24.04 image version on x86_64.');
  }
  if (value.environment.sameBoot !== true || value.environment.samePhysicalRunner !== true)
    fail('MCP benchmarks were not run on the same physical runner and boot.');
  const repetitions = requireArray(value.repetitions, 'MCP benchmark repetitions');
  if (repetitions.length !== policy.forks)
    fail('MCP benchmark repetition count does not match approved forks.');
  const scores = {
    '3.5.1': { jsonParse: [], jsonWrite: [] },
    '4.0.0': { jsonParse: [], jsonWrite: [] },
  };
  const expectedLogMarkers = [
    `SOKLET_BENCHMARK_CONFIGURATION_SHA256=${sha256(Buffer.from(canonicalJson(expectedConfiguration), 'utf8'))}`,
  ];
  repetitions.forEach((repetition, index) => {
    exactKeys(repetition, ['first', 'ordinal', 'runs'], `MCP benchmark repetition ${index + 1}`);
    const expectedFirst = index % 2 === 0 ? '3.5.1' : '4.0.0';
    if (repetition.ordinal !== index || repetition.first !== expectedFirst) {
      fail(`MCP benchmark repetition ${index + 1} violates alternating complete-run policy.`);
    }
    const runs = requireArray(repetition.runs, `MCP benchmark repetition ${index + 1}.runs`);
    const expectedArtifacts = expectedFirst === '3.5.1'
      ? ['3.5.1', '4.0.0']
      : ['4.0.0', '3.5.1'];
    if (runs.length !== 2)
      fail(`MCP benchmark repetition ${index + 1} must contain two complete runs.`);
    runs.forEach((run, runIndex) => {
      const label = `MCP benchmark repetition ${index + 1}.runs[${runIndex}]`;
      exactKeys(run, ['artifact', 'outcome', 'rawResult', 'rawResultSha256'], label);
      if (run.artifact !== expectedArtifacts[runIndex] || run.outcome !== 'PASS')
        fail(`${label} violates the alternating artifact/outcome order.`);
      validateSha256(run.rawResultSha256, `${label}.rawResultSha256`);
      if (run.rawResultSha256
          !== sha256(Buffer.from(canonicalJson(run.rawResult), 'utf8'))) {
        fail(`${label} raw-result digest does not match its inline result.`);
      }
      exactKeys(
        run.rawResult,
        ['artifact', 'configuration', 'jsonParseScore', 'jsonWriteScore'],
        `${label}.rawResult`,
      );
      if (run.rawResult.artifact !== run.artifact
          || !sameJson(run.rawResult.configuration, expectedConfiguration)) {
        fail(`${label} raw result has the wrong artifact or execution configuration.`);
      }
      const jsonParseScore = requireNumber(
        run.rawResult.jsonParseScore,
        `${label}.rawResult.jsonParseScore`,
      );
      const jsonWriteScore = requireNumber(
        run.rawResult.jsonWriteScore,
        `${label}.rawResult.jsonWriteScore`,
      );
      if (jsonParseScore <= 0 || jsonWriteScore <= 0)
        fail(`${label} raw benchmark scores must be positive.`);
      scores[run.artifact].jsonParse.push(jsonParseScore);
      scores[run.artifact].jsonWrite.push(jsonWriteScore);
      expectedLogMarkers.push(
        `SOKLET_BENCHMARK_RUN=${index}:${runIndex}:${run.artifact}:PASS:${run.rawResultSha256}`,
      );
    });
  });
  const mean = (values) => values.reduce((sum, score) => sum + score, 0) / values.length;
  const derivedParseRatio = mean(scores['4.0.0'].jsonParse) / mean(scores['3.5.1'].jsonParse);
  const derivedWriteRatio = mean(scores['4.0.0'].jsonWrite) / mean(scores['3.5.1'].jsonWrite);
  if (!Number.isFinite(derivedParseRatio) || !Number.isFinite(derivedWriteRatio)
      || Math.abs(parseRatio - derivedParseRatio) > 1e-12
      || Math.abs(writeRatio - derivedWriteRatio) > 1e-12) {
    fail('MCP benchmark comparison ratios do not derive from the complete raw repetitions.');
  }
  const log = logBytes.toString('utf8');
  if (!Buffer.from(log, 'utf8').equals(logBytes)
      || log.includes('\r') || !log.endsWith('\n')) {
    fail('MCP benchmark log must be nonempty UTF-8/LF text.');
  }
  let previousMarker = -1;
  for (const marker of expectedLogMarkers) {
    const index = log.indexOf(`${marker}\n`);
    if (index <= previousMarker || index !== log.lastIndexOf(`${marker}\n`))
      fail(`MCP benchmark log is missing, duplicates, or reorders marker: ${marker}`);
    previousMarker = index;
  }
  const baseline = requireArray(value.profile1Baseline, 'MCP benchmark profile1Baseline');
  if (baseline.length !== policy.profile1Baseline.operations.length)
    fail('MCP benchmark Profile 1 baseline is incomplete.');
  baseline.forEach((operation, index) => {
    exactKeys(operation, ['errors', 'operation', 'rawResult', 'rawResultSha256'], `Profile 1 operation ${index + 1}`);
    if (operation.operation !== policy.profile1Baseline.operations[index] || operation.errors !== 0)
      fail(`Profile 1 operation ${index + 1} failed or is out of order.`);
    validateSha256(operation.rawResultSha256, `Profile 1 operation ${index + 1}.rawResultSha256`);
    if (operation.rawResultSha256
        !== sha256(Buffer.from(canonicalJson(operation.rawResult), 'utf8'))) {
      fail(`Profile 1 operation ${index + 1} raw-result digest does not match.`);
    }
    exactKeys(
      operation.rawResult,
      ['complete', 'errors', 'operation', 'result'],
      `Profile 1 operation ${index + 1}.rawResult`,
    );
    if (operation.rawResult.complete !== true
        || !sameArray(operation.rawResult.errors, [])
        || operation.rawResult.operation !== operation.operation
        || !isPlainObject(operation.rawResult.result)
        || Object.keys(operation.rawResult.result).length === 0) {
      fail(`Profile 1 operation ${index + 1} raw result is incomplete or contains errors.`);
    }
  });
}

function validateGateEvidence(contract, candidate, roles, now) {
  const byName = new Map(roles.map((role) => [role.descriptor.name, role]));
  if (contract.id === 'fuzz-nightly-history') {
    validateFuzzHistory(byName.get('history').bytes, contract, candidate, now);
  } else if (contract.id === 'soak-nightly-history') {
    validateSoakHistory(byName.get('history').bytes, contract, candidate, now);
  } else if (contract.id === 'operational-history') {
    validateOperationalHistory(byName.get('history').bytes, contract, candidate);
  } else if (contract.id === 'release-scans') {
    validateReleaseScans(
      byName.get('scan-summary').bytes,
      byName.get('scan-reports'),
      contract,
      candidate,
    );
  } else if (contract.id === 'mcp-benchmarks') {
    validateBenchmarks(
      byName.get('benchmark-results').bytes,
      byName.get('benchmark-log').bytes,
      contract,
      candidate,
    );
  } else {
    fail(`No harness evidence validator exists for ${contract.id}.`);
  }
}

function requireEvidenceRoot(evidenceRoot) {
  if (typeof evidenceRoot !== 'string' || !isAbsolute(evidenceRoot))
    fail('release-harness evidence root must be an absolute path.');
  const absoluteRoot = resolve(evidenceRoot);
  requireNonsymlinkComponents(absoluteRoot, 'release-harness evidence root');
  if (!existsSync(absoluteRoot))
    fail(`release-harness evidence root does not exist: ${absoluteRoot}`);
  const stats = lstatSync(absoluteRoot);
  if (!stats.isDirectory() || stats.isSymbolicLink()
      || realpathSync(absoluteRoot) !== absoluteRoot) {
    fail('release-harness evidence root must be a real nonsymlink directory.');
  }
  return absoluteRoot;
}

function readDirectoryRole(path, expected, label) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink() || realpathSync(path) !== resolve(path))
    fail(`${label} must be a real nonsymlink directory: ${path}`);

  const descriptors = [];
  let totalBytes = 0;
  function visit(directoryPath, prefix) {
    const entries = readdirSync(directoryPath, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name));
    if (entries.length === 0)
      fail(`${label} contains an empty directory: ${directoryPath}`);
    for (const entry of entries) {
      const relativePath = prefix.length === 0 ? entry.name : `${prefix}/${entry.name}`;
      validateRelativePath(relativePath, `${label} entry path`);
      const entryPath = resolve(directoryPath, entry.name);
      if (entry.isSymbolicLink())
        fail(`${label} contains a symbolic link: ${relativePath}`);
      if (entry.isDirectory()) {
        visit(entryPath, relativePath);
      } else if (entry.isFile()) {
        const bytes = readRegularFile(entryPath, `${label} entry ${relativePath}`, MAXIMUM_ROLE_BYTES);
        totalBytes += bytes.length;
        if (totalBytes > MAXIMUM_ROLE_BYTES)
          fail(`${label} exceeds the directory-role byte bound.`);
        descriptors.push({
          bytes,
          path: relativePath,
          sha256: sha256(bytes),
          size: bytes.length,
        });
      } else {
        fail(`${label} contains a non-file entry: ${relativePath}`);
      }
    }
  }
  visit(path, '');
  if (descriptors.length === 0)
    fail(`${label} must contain at least one regular file.`);
  descriptors.sort((left, right) => compareAscii(left.path, right.path));
  const bundleEntries = descriptors.map(({ bytes, path: entryPath, sha256: digest }) => ({
    bytesBase64: bytes.toString('base64'),
    path: entryPath,
    sha256: digest,
  }));
  return {
    descriptors,
    descriptor: {
      ...expected,
      entries: descriptors.map(({ path: entryPath, sha256: digest, size }) => ({
        path: entryPath,
        sha256: digest,
        size,
      })),
      entryCount: descriptors.length,
      sha256: sha256(Buffer.from(canonicalJson(bundleEntries), 'utf8')),
      size: totalBytes,
    },
  };
}

function readEvidenceRole(evidenceRoot, expected, index) {
  const label = `release-harness evidence role ${index + 1} (${expected.name})`;
  const path = resolve(evidenceRoot, expected.path);
  if (path !== evidenceRoot && !path.startsWith(`${evidenceRoot}${sep}`))
    fail(`${label} escapes the evidence root.`);
  if (expected.kind === 'directory')
    return readDirectoryRole(path, expected, label);
  const bytes = readRegularFile(path, label, MAXIMUM_ROLE_BYTES);
  return {
    bytes,
    descriptor: {
      ...expected,
      sha256: sha256(bytes),
      size: bytes.length,
    },
  };
}

export function verifyReleaseHarnessRoleDescriptors(expectedImportedReceipt, roleDescriptors) {
  requireObject(expectedImportedReceipt, 'expected imported release-harness receipt');
  const expectedRoles = requireArray(
    expectedImportedReceipt.roles,
    'expected imported release-harness receipt roles',
  );
  const actualRoles = requireArray(roleDescriptors, 'verified release-harness role descriptors');
  if (!sameJson(actualRoles, expectedRoles))
    fail('unpacked release-harness role descriptors do not match the imported receipt.');
  return true;
}

export function verifyReleaseHarnessEvidenceDirectory({
  evidenceRoot = process.cwd(),
  expectedImportedReceipt,
  gate,
  now = Date.now(),
  registryPath,
} = {}) {
  const configuration = verifyReleaseHarnessConfiguration(registryPath);
  const contract = configuration.contracts.get(gate);
  if (contract === undefined)
    fail(`Unknown release harness gate: ${gate}`);
  requireNumber(now, 'release-harness verification time');
  const absoluteRoot = requireEvidenceRoot(evidenceRoot);
  const roles = contract.roles.map((role, index) =>
    readEvidenceRole(absoluteRoot, role, index));
  const primaryRole = roles.find((role) => role.bytes !== undefined);
  if (primaryRole === undefined)
    fail(`${gate} has no file role from which to obtain its candidate binding.`);
  const primaryEvidence = requireObject(
    parseCanonicalJsonBytes(primaryRole.bytes, `${gate} primary evidence`),
    `${gate} primary evidence`,
  );
  const candidate = requireObject(primaryEvidence.candidate, `${gate} primary evidence.candidate`);
  validateCandidate(candidate, `${gate} primary evidence.candidate`);
  if (candidate.candidateRegistrySha256 !== configuration.registrySha256)
    fail(`${gate} candidate registry binding does not match the registry bytes.`);
  validateGateEvidence(contract, candidate, roles, now);
  const roleDescriptors = roles.map(({ descriptor }) => descriptor);
  if (expectedImportedReceipt !== undefined) {
    requireObject(expectedImportedReceipt, 'expected imported release-harness receipt');
    if (expectedImportedReceipt.gate !== gate)
      fail('expected imported release-harness receipt gate does not match the selected gate.');
    const candidateBindings = requireObject(
      expectedImportedReceipt.candidateBindings,
      'expected imported release-harness receipt candidateBindings',
    );
    const receiptCandidate = Object.fromEntries(
      BUNDLE_CANDIDATE_KEYS.map((key) => [key, candidateBindings[key]]),
    );
    validateCandidate(receiptCandidate, 'expected imported release-harness receipt candidateBindings');
    if (!sameJson(candidate, receiptCandidate))
      fail('unpacked release-harness candidate does not match the imported receipt.');
    verifyReleaseHarnessRoleDescriptors(expectedImportedReceipt, roleDescriptors);
  }
  return Object.freeze({
    candidate: Object.freeze({ ...candidate }),
    gate,
    roles: Object.freeze(roleDescriptors),
  });
}

function runGit(candidateRoot, args, label) {
  const result = spawnSync('git', ['-C', candidateRoot, ...args], {
    encoding: 'utf8',
    maxBuffer: 4 * 1024 * 1024,
  });
  if (result.status !== 0)
    fail(`${label} failed: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}

function requireTrackedCandidateFile(candidateRoot, path, label) {
  validateRelativePath(path, `${label} path`);
  readRegularFile(resolve(candidateRoot, path), label, 16 * 1024 * 1024);
  const indexEntry = runGit(
    candidateRoot,
    ['ls-files', '--error-unmatch', '--stage', '--', path],
    `${label} tracked-file lookup`,
  );
  const lines = indexEntry.split('\n');
  if (lines.length !== 1 || !lines[0].includes('\t'))
    fail(`${label} must have exactly one stage-zero tracked index entry.`);
  const [metadata, indexedPath] = lines[0].split('\t');
  const [mode, indexBlob, stage] = metadata.split(' ');
  if (indexedPath !== path || stage !== '0'
      || (mode !== '100644' && mode !== '100755')
      || !/^[0-9a-f]{40,64}$/.test(indexBlob)) {
    fail(`${label} must be a regular stage-zero candidate file.`);
  }
  const headBlob = runGit(
    candidateRoot,
    ['rev-parse', '--verify', `HEAD:${path}`],
    `${label} HEAD blob`,
  );
  const workingBlob = runGit(
    candidateRoot,
    ['hash-object', '--', path],
    `${label} working-tree blob`,
  );
  if (headBlob !== indexBlob || workingBlob !== indexBlob)
    fail(`${label} bytes are not identical across HEAD, index, and working tree.`);
}

function workflowPaths(contract) {
  if (contract.id === 'release-scans')
    return ['.github/workflows/codeql.yml', '.github/workflows/release-validation.yml'];
  const path = contract.producer.split('#', 1)[0];
  validateRelativePath(path, `${contract.id} producer workflow path`);
  return [path];
}

function verifierSourcePaths(contract) {
  if (contract.id === 'fuzz-nightly-history'
      || contract.id === 'operational-history'
      || contract.id === 'soak-nightly-history') {
    return ['scripts/verify-release-history.mjs'];
  }
  if (contract.id === 'release-scans')
    return ['scripts/verify-release-scans.mjs'];
  if (contract.id === 'mcp-benchmarks')
    return ['scripts/verify-release-benchmarks.mjs'];
  fail(`No verifier source path exists for ${contract.id}.`);
}

function digestWorkflow(candidateRoot, contract) {
  const paths = workflowPaths(contract);
  if (paths.length === 1) {
    return sha256(readRegularFile(
      resolve(candidateRoot, paths[0]),
      `${contract.id} producer workflow`,
      4 * 1024 * 1024,
    ));
  }
  const entries = paths.map((path) => ({
    path,
    sha256: sha256(readRegularFile(
      resolve(candidateRoot, path),
      `${contract.id} producer workflow ${path}`,
      4 * 1024 * 1024,
    )),
  }));
  return sha256(Buffer.from(canonicalJson(entries), 'utf8'));
}

function actualCandidateIdentity(candidateRoot, contract, registrySha256) {
  if (!isAbsolute(candidateRoot))
    fail('candidate-root must be absolute.');
  requireNonsymlinkComponents(candidateRoot, 'candidate-root');
  if (!existsSync(candidateRoot) || !lstatSync(candidateRoot).isDirectory()
      || realpathSync(candidateRoot) !== resolve(candidateRoot)) {
    fail('candidate-root must be a real nonsymlink directory.');
  }
  const dirty = runGit(candidateRoot, ['status', '--porcelain=v1', '--untracked-files=no'], 'candidate status');
  if (dirty.length !== 0)
    fail('candidate-root has tracked working-tree changes.');
  const candidateCommit = runGit(candidateRoot, ['rev-parse', '--verify', 'HEAD'], 'candidate commit');
  const candidateTree = runGit(candidateRoot, ['rev-parse', '--verify', 'HEAD^{tree}'], 'candidate tree');
  validateCommit(candidateCommit, 'candidate commit');
  validateCommit(candidateTree, 'candidate tree');
  const trackedSources = new Set([
    'pom.xml',
    'release/release-harness-contracts.json',
    'release/release-validation-manifest.json',
    'scripts/import-release-harness-evidence.mjs',
    'scripts/import-release-harness-evidence-self-test.mjs',
    'scripts/release-validation-evidence.mjs',
    'scripts/validate-release-candidate.sh',
    ...verifierSourcePaths(contract),
    ...workflowPaths(contract),
  ]);
  for (const path of trackedSources)
    requireTrackedCandidateFile(candidateRoot, path, `candidate source ${path}`);
  const executingScriptDirectory = dirname(fileURLToPath(import.meta.url));
  for (const path of [
    'scripts/import-release-harness-evidence.mjs',
    'scripts/release-validation-evidence.mjs',
    ...verifierSourcePaths(contract),
  ]) {
    const executingSource = readRegularFile(
      resolve(executingScriptDirectory, basename(path)),
      `executing ${path}`,
      16 * 1024 * 1024,
    );
    const candidateSource = readRegularFile(
      resolve(candidateRoot, path),
      `candidate ${path}`,
      16 * 1024 * 1024,
    );
    if (sha256(executingSource) !== sha256(candidateSource))
      fail(`executing ${path} is not the candidate-tracked source.`);
  }
  const pomBytes = readRegularFile(resolve(candidateRoot, 'pom.xml'), 'candidate POM', 4 * 1024 * 1024);
  const pomText = pomBytes.toString('utf8');
  const exactCoordinates = /<project\b[^>]*>\s*<modelVersion>\s*4\.0\.0\s*<\/modelVersion>\s*<groupId>\s*com\.soklet\s*<\/groupId>\s*<artifactId>\s*soklet\s*<\/artifactId>\s*<version>\s*4\.0\.0\s*<\/version>\s*<packaging>\s*jar\s*<\/packaging>/s;
  if (!Buffer.from(pomText, 'utf8').equals(pomBytes)
      || !exactCoordinates.test(pomText)) {
    fail('candidate POM does not declare direct com.soklet:soklet:4.0.0 JAR coordinates.');
  }
  const jarBytes = readRegularFile(
    resolve(candidateRoot, 'target/soklet-4.0.0.jar'),
    'candidate main JAR',
    512 * 1024 * 1024,
  );
  return {
    candidateCommit,
    candidateMainJarSha256: sha256(jarBytes),
    candidatePomSha256: sha256(pomBytes),
    candidateRegistrySha256: registrySha256,
    candidateTree,
    producerWorkflowSha256: digestWorkflow(candidateRoot, contract),
  };
}

function validateBundle(bundlePath, contract, candidate, now) {
  if (!isAbsolute(bundlePath))
    fail('bundle must be an absolute path.');
  const { bytes, value } = readCanonicalJson(bundlePath, 'release harness bundle', MAXIMUM_BUNDLE_BYTES);
  exactKeys(value, ['content', 'contentSha256', 'formatVersion'], 'release harness bundle');
  if (value.formatVersion !== 1)
    fail('release harness bundle formatVersion must be 1.');
  validateSha256(value.contentSha256, 'release harness bundle contentSha256');
  if (sha256(Buffer.from(canonicalJson(value.content), 'utf8')) !== value.contentSha256)
    fail('release harness bundle contentSha256 does not match its content.');
  const content = value.content;
  exactKeys(content, [
    'candidate',
    'contractVersion',
    'evidenceContract',
    'gate',
    'policy',
    'producer',
    'producerStatus',
    'roles',
    'toolchains',
  ], 'release harness bundle content');
  if (content.gate !== contract.id || content.contractVersion !== contract.contractVersion
      || content.evidenceContract !== contract.evidenceContract
      || content.producer !== contract.producer) {
    fail('release harness bundle contract identity does not match the selected gate.');
  }
  validateCandidate(content.candidate);
  if (!sameJson(content.candidate, candidate))
    fail('release harness bundle candidate does not match the exact candidate root.');
  if (content.producerStatus !== 'PASS')
    fail('release harness bundle producerStatus must be PASS.');
  if (!sameJson(content.policy, contract.policy))
    fail('release harness bundle policy drifted from the registry.');
  if (!sameJson(content.toolchains, contract.toolchains))
    fail('release harness bundle toolchains drifted from the registry.');
  const roleValues = requireArray(content.roles, 'release harness bundle roles');
  if (roleValues.length !== contract.roles.length)
    fail('release harness bundle has missing or extra roles.');
  const roles = roleValues.map((role, index) => validateBundleRole(role, contract.roles[index], index));
  validateGateEvidence(contract, candidate, roles, now);
  return { bundleSha256: sha256(bytes), roles };
}

function importedReceipt(contract, candidate, bundleSha256, roles) {
  return {
    candidateBindings: {
      ...candidate,
      immutableBundleSha256: bundleSha256,
    },
    contractVersion: contract.contractVersion,
    evidenceContract: contract.evidenceContract,
    formatVersion: 1,
    gate: contract.id,
    policySha256: policySha256(contract),
    producer: contract.producer,
    receiptExpectation: EXPECTED_GATE_EVIDENCE_CONTRACTS[contract.id].expectation,
    receiptProfile: EXPECTED_GATE_EVIDENCE_CONTRACTS[contract.id].profile,
    roles: roles.map(({ descriptor }) => descriptor),
    toolchainsSha256: toolchainsSha256(contract),
    verifierCommand: contract.importerMode.verifierCommand,
  };
}

function writeNewCanonicalJson(outputPath, value) {
  if (!isAbsolute(outputPath))
    fail('output must be an absolute path.');
  const parent = dirname(outputPath);
  requireNonsymlinkComponents(parent, 'output parent');
  if (!existsSync(parent) || !lstatSync(parent).isDirectory() || lstatSync(parent).isSymbolicLink())
    fail('output parent must be a real nonsymlink directory.');
  if (existsSync(outputPath))
    fail('output already exists; importer never overwrites evidence.');
  const descriptor = openSync(outputPath, 'wx', 0o600);
  try {
    writeFileSync(descriptor, canonicalJson(value), { encoding: 'utf8' });
  } finally {
    closeSync(descriptor);
  }
}

export function verifyImportedReceipt(path, registryPath) {
  const { value } = readCanonicalJson(path, 'imported release-harness receipt', MAXIMUM_BUNDLE_BYTES);
  exactKeys(value, [
    'candidateBindings',
    'contractVersion',
    'evidenceContract',
    'formatVersion',
    'gate',
    'policySha256',
    'producer',
    'receiptExpectation',
    'receiptProfile',
    'roles',
    'toolchainsSha256',
    'verifierCommand',
  ], 'imported release-harness receipt');
  if (value.formatVersion !== 1 || !EXPECTED_GATE_IDS.includes(value.gate))
    fail('imported receipt formatVersion/gate is invalid.');
  const configuration = verifyReleaseHarnessConfiguration(registryPath);
  const contract = configuration.contracts.get(value.gate);
  if (value.contractVersion !== contract.contractVersion
      || value.evidenceContract !== contract.evidenceContract
      || value.producer !== contract.producer
      || value.policySha256 !== policySha256(contract)
      || value.toolchainsSha256 !== toolchainsSha256(contract)
      || value.verifierCommand !== contract.importerMode.verifierCommand) {
    fail('imported receipt contract identity drifted from the release-harness registry.');
  }
  const evidenceContract = EXPECTED_GATE_EVIDENCE_CONTRACTS[value.gate];
  if (value.receiptExpectation !== evidenceContract.expectation
      || value.receiptProfile !== evidenceContract.profile) {
    fail('imported receipt expectation/profile drifted from release validation policy.');
  }
  exactKeys(value.candidateBindings, EXPECTED_CANDIDATE_BINDINGS, 'imported receipt candidateBindings');
  validateCandidate(
    Object.fromEntries(BUNDLE_CANDIDATE_KEYS.map((key) => [key, value.candidateBindings[key]])),
    'imported receipt candidateBindings',
  );
  validateSha256(value.candidateBindings.immutableBundleSha256, 'immutableBundleSha256');
  if (value.candidateBindings.candidateRegistrySha256 !== configuration.registrySha256)
    fail('imported receipt candidate registry binding does not match the registry bytes.');
  validateSha256(value.policySha256, 'imported receipt policySha256');
  validateSha256(value.toolchainsSha256, 'imported receipt toolchainsSha256');
  const roles = requireArray(value.roles, 'imported receipt roles');
  if (roles.length !== contract.roles.length)
    fail('imported receipt has missing or extra roles.');
  roles.forEach((role, index) => {
    const expected = contract.roles[index];
    const commonKeys = [
      'kind', 'mediaType', 'name', 'ordinal', 'path', 'required', 'sha256', 'size',
    ];
    exactKeys(
      role,
      expected.kind === 'file' ? commonKeys : [...commonKeys, 'entries', 'entryCount'],
      `imported receipt role ${index + 1}`,
    );
    for (const key of ['kind', 'mediaType', 'name', 'ordinal', 'path', 'required']) {
      if (role[key] !== expected[key])
        fail(`imported receipt role ${index + 1}.${key} drifted from the registry.`);
    }
    validateSha256(role.sha256, `imported receipt role ${index + 1}.sha256`);
    requireInteger(role.size, `imported receipt role ${index + 1}.size`, 1);
    if (expected.kind === 'directory') {
      const entries = requireArray(role.entries, `imported receipt role ${index + 1}.entries`);
      if (role.entryCount !== entries.length || entries.length === 0)
        fail(`imported receipt role ${index + 1} has an invalid entry count.`);
      let previousPath = null;
      entries.forEach((entry, entryIndex) => {
        exactKeys(entry, ['path', 'sha256', 'size'], `imported receipt directory entry ${entryIndex + 1}`);
        validateRelativePath(entry.path, `imported receipt directory entry ${entryIndex + 1}.path`);
        if (previousPath !== null && compareAscii(previousPath, entry.path) >= 0)
          fail(`imported receipt role ${index + 1} entries are not strictly ordered.`);
        previousPath = entry.path;
        validateSha256(entry.sha256, `imported receipt directory entry ${entryIndex + 1}.sha256`);
        requireInteger(entry.size, `imported receipt directory entry ${entryIndex + 1}.size`, 1);
      });
    }
  });
  return value;
}

export function verifyImportedBundleReceipt({
  bundlePath,
  candidateIdentityProvider,
  candidateRoot,
  now = Date.now(),
  receiptPath,
  registryPath,
}) {
  const receipt = verifyImportedReceipt(receiptPath, registryPath);
  const configuration = verifyReleaseHarnessConfiguration(registryPath);
  const contract = configuration.contracts.get(receipt.gate);
  const candidate = Object.fromEntries(
    BUNDLE_CANDIDATE_KEYS.map((key) => [key, receipt.candidateBindings[key]]),
  );
  const { bundleSha256, roles } = validateBundle(
    bundlePath,
    contract,
    candidate,
    now,
  );
  if (bundleSha256 !== receipt.candidateBindings.immutableBundleSha256)
    fail('immutable bundle bytes do not match the imported receipt.');
  verifyReleaseHarnessRoleDescriptors(
    receipt,
    roles.map(({ descriptor }) => descriptor),
  );
  if (candidateIdentityProvider !== undefined && candidateRoot === undefined)
    fail('candidateIdentityProvider requires candidateRoot.');
  if (candidateRoot !== undefined) {
    if (typeof candidateRoot !== 'string' || !isAbsolute(candidateRoot))
      fail('candidate-root must be absolute.');
    if (candidateIdentityProvider === undefined
        && configuration.registryPath
          !== resolve(candidateRoot, 'release/release-harness-contracts.json')) {
      fail('production verification must use the exact candidate-tracked registry path.');
    }
    const currentCandidate = candidateIdentityProvider === undefined
      ? actualCandidateIdentity(candidateRoot, contract, configuration.registrySha256)
      : candidateIdentityProvider({
        candidateRoot,
        contract,
        registrySha256: configuration.registrySha256,
      });
    validateCandidate(currentCandidate, 'current candidate identity');
    if (currentCandidate.candidateRegistrySha256 !== configuration.registrySha256)
      fail('current candidate identity registry SHA-256 does not match the registry bytes.');
    if (!sameJson(currentCandidate, candidate))
      fail('imported bundle and receipt do not match the current candidate root.');
  }
  return Object.freeze(receipt);
}

export function importReleaseHarnessEvidence({
  bundlePath,
  candidateIdentityProvider,
  candidateRoot,
  gate,
  now = Date.now(),
  outputPath,
  registryPath,
}) {
  if (typeof candidateRoot !== 'string' || !isAbsolute(candidateRoot))
    fail('candidate-root must be absolute.');
  const selectedRegistryPath = registryPath
    ?? resolve(candidateRoot, 'release/release-harness-contracts.json');
  const configuration = verifyReleaseHarnessConfiguration(selectedRegistryPath);
  verifyReleaseHarnessManifestParity(configuration);
  if (candidateIdentityProvider === undefined
      && configuration.registryPath
        !== resolve(candidateRoot, 'release/release-harness-contracts.json')) {
    fail('production import must use the exact candidate-tracked registry path.');
  }
  const contract = configuration.contracts.get(gate);
  if (contract === undefined)
    fail(`Unknown release harness gate: ${gate}`);
  const candidate = candidateIdentityProvider === undefined
    ? actualCandidateIdentity(candidateRoot, contract, configuration.registrySha256)
    : candidateIdentityProvider({
      candidateRoot,
      contract,
      registrySha256: configuration.registrySha256,
    });
  validateCandidate(candidate, 'candidate identity');
  if (candidate.candidateRegistrySha256 !== configuration.registrySha256)
    fail('candidate identity registry SHA-256 does not match the selected registry bytes.');
  const { bundleSha256, roles } = validateBundle(bundlePath, contract, candidate, now);
  const receipt = importedReceipt(contract, candidate, bundleSha256, roles);
  writeNewCanonicalJson(outputPath, receipt);
  verifyImportedReceipt(outputPath, configuration.registryPath);
  return Object.freeze(receipt);
}

function usage() {
  return 'Usage:\n'
    + '  node scripts/import-release-harness-evidence.mjs --verify-config\n'
    + '  node scripts/import-release-harness-evidence.mjs --import --gate <id> '
    + '--candidate-root <absolute-path> --bundle <absolute-path> --output <absolute-path>';
}

function parseImportArguments(args) {
  if (args[0] !== '--import')
    fail(usage());
  const values = new Map();
  for (let index = 1; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];
    if (!['--gate', '--candidate-root', '--bundle', '--output'].includes(flag)
        || value === undefined || values.has(flag)) {
      fail(usage());
    }
    values.set(flag, value);
  }
  if (values.size !== 4)
    fail(usage());
  return {
    bundlePath: values.get('--bundle'),
    candidateRoot: values.get('--candidate-root'),
    gate: values.get('--gate'),
    outputPath: values.get('--output'),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  try {
    const args = process.argv.slice(2);
    if (sameArray(args, ['--verify-config'])) {
      const result = verifyReleaseHarnessConfiguration();
      verifyReleaseHarnessManifestParity(result);
      console.log(`release harness configuration PASS contracts=${result.contracts.size} registrySha256=${result.registrySha256}`);
    } else {
      const options = parseImportArguments(args);
      const receipt = importReleaseHarnessEvidence(options);
      console.log(`release harness import PASS gate=${receipt.gate} bundleSha256=${receipt.candidateBindings.immutableBundleSha256}`);
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
