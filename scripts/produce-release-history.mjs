#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  canonicalJson,
  releaseHarnessCandidateIdentity,
  verifyReleaseHarnessConfiguration,
  verifyReleaseHarnessEvidenceDirectory,
} from './import-release-harness-evidence.mjs';
import { verifySoakEvidence } from './verify-soak-evidence.mjs';

const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const MAXIMUM_STATE_BYTES = 16 * 1024 * 1024;
const MAXIMUM_CORPUS_FILES = 100_000;
const MAXIMUM_CORPUS_BYTES = 128 * 1024 * 1024;
const SUPPLEMENTAL_JAZZER_SHA256 = Object.freeze({
  api: 'd36a725cfedcb7f3590206866cc2836f84d12afccf7c98912f5c720e4d2208d7',
  engine: '8bdeac017bcd3d9473c9772fac62111c4df830188571def1d001a1b743a62b2f',
});
const FUZZ_TARGET_EXECUTIONS = Object.freeze({
  'request-parser': Object.freeze({
    className: 'com.soklet.internal.microhttp.RequestParserFuzzTest',
    methodName: 'parseIncrementalRequestOnlyRejectsWithDeclaredExceptions',
  }),
  'multipart-parser': Object.freeze({
    className: 'com.soklet.DefaultMultipartParserFuzzTest',
    methodName: 'extractMultipartFieldsOnlyRejectsWithExpectedExceptions',
  }),
  'http-date': Object.freeze({
    className: 'com.soklet.HttpDateFuzzTest',
    methodName: 'fromHeaderValueNeverThrows',
  }),
  'parameterized-header-value': Object.freeze({
    className: 'com.soklet.ParameterizedHeaderValueFuzzTest',
    methodName: 'builderOnlyRejectsWithIllegalArgumentException',
  }),
  'media-range': Object.freeze({
    className: 'com.soklet.MediaRangeFuzzTest',
    methodName: 'mediaRangeParsingNeverThrows',
  }),
  'query-format': Object.freeze({
    className: 'com.soklet.QueryFormatFuzzTest',
    methodName: 'extractQueryParametersOnlyRejectsWithIllegalRequestException',
  }),
  'response-cookie': Object.freeze({
    className: 'com.soklet.ResponseCookieFuzzTest',
    methodName: 'fromSetCookieHeaderRepresentationOnlyRejectsWithIllegalArgumentException',
  }),
  'trace-context': Object.freeze({
    className: 'com.soklet.TraceContextFuzzTest',
    methodName: 'fromHeaderValuesNeverThrows',
  }),
  'mcp-json-parse': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpJsonCodecFuzzTest',
    methodName: 'strictJsonFuzzRejectsInvalidBytesOnlyWithIllegalArgumentException',
  }),
  'mcp-json-round-trip': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpJsonCodecFuzzTest',
    methodName: 'strictJsonFuzzRoundTripsStructurally',
  }),
  'mcp-json-rpc-envelope': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpJsonRpcEnvelopeCodecFuzzTest',
    methodName: 'decodeClassifiesOrRejectsOnlyWithTypedWireFailure',
  }),
  'mcp-mirrored-header': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpMirroredHeaderCodecFuzzTest',
    methodName: 'decodeStringOnlyRejectsWithRedactedIllegalArgumentException',
  }),
  'mcp-profile-1-schema': Object.freeze({
    className: 'com.soklet.internal.mcp.schema.McpToolSchemaProfileFuzzTest',
    methodName: 'compileAndEvaluateRemainTypedAndBounded',
  }),
  'mcp-cursor-validator': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpCursorValidatorFuzzTest',
    methodName: 'cursorValidationIsUtf8ExactAndTotal',
  }),
  'mcp-request-state-plaintext': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpRequestStatePlaintextCodecFuzzTest',
    methodName: 'decodeOnlyRejectsWithUniformRedactedIllegalArgumentException',
  }),
  'mcp-simulation-capture': Object.freeze({
    className: 'com.soklet.internal.mcp.protocol.McpSimulationCaptureFuzzTest',
    methodName: 'captureStateMachineRemainsBoundedTerminalAndIdempotent',
  }),
  'mcp-localization-byte-accounting': Object.freeze({
    className: 'com.soklet.McpLocalizationFuzzTest',
    methodName: 'replacementByteAccountingMatchesTheProductionEncoder',
  }),
  'mcp-localization-preferences': Object.freeze({
    className: 'com.soklet.McpLocalizationFuzzTest',
    methodName: 'boundedPreferenceDerivationIsTotalAndNeverTruncates',
  }),
  'mcp-localization-overlay': Object.freeze({
    className: 'com.soklet.McpLocalizationFuzzTest',
    methodName: 'overlayPointerHandlingIsTotalAndNonMutating',
  }),
});
const HISTORY_GATE_IDS = new Set([
  'fuzz-nightly-history',
  'operational-history',
  'soak-nightly-history',
]);
const OPERATIONAL_FIELDS = Object.freeze([
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
]);

export class ReleaseHistoryProductionError extends Error {}

function fail(message) {
  throw new ReleaseHistoryProductionError(message);
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

function exactKeys(value, expected, label) {
  requireObject(value, label);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length
      || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label} keys do not match the producer contract.`);
  }
}

function sameJson(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function requireAbsolute(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  return resolve(path);
}

function readRegular(path, label, maximumBytes = MAXIMUM_STATE_BYTES) {
  if (!existsSync(path))
    fail(`Missing ${label}: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink() || realpathSync(path) !== resolve(path))
    fail(`${label} must be a real nonsymlink regular file: ${path}`);
  if (stats.size === 0 || stats.size > maximumBytes)
    fail(`${label} has an invalid byte size: ${stats.size}.`);
  return readFileSync(path);
}

function parseCanonicalJson(path, label, maximumBytes = MAXIMUM_STATE_BYTES) {
  const bytes = readRegular(path, label, maximumBytes);
  let value;
  try {
    value = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`${label} is not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
  }
  if (!Buffer.from(canonicalJson(value), 'utf8').equals(bytes))
    fail(`${label} is not canonical JSON.`);
  return value;
}

function parseUtc(value, label) {
  if (typeof value !== 'string' || !ISO_UTC_PATTERN.test(value))
    fail(`${label} must be an exact UTC timestamp without fractional seconds.`);
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds)
      || new Date(milliseconds).toISOString().replace('.000Z', 'Z') !== value) {
    fail(`${label} is not a real UTC timestamp.`);
  }
  return milliseconds;
}

function contractFor(gate, registryPath) {
  if (!HISTORY_GATE_IDS.has(gate))
    fail(`Unsupported release-history gate: ${gate}.`);
  const configuration = verifyReleaseHarnessConfiguration(registryPath);
  const contract = configuration.contracts.get(gate);
  if (contract === undefined)
    fail(`Missing registered release-history contract: ${gate}.`);
  return { configuration, contract };
}

function policySha256(contract) {
  return sha256(Buffer.from(canonicalJson(contract.policy), 'utf8'));
}

function toolchainsSha256(contract) {
  return sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8'));
}

function commonEvidence(contract, candidate) {
  return {
    candidate,
    formatVersion: 1,
    gate: contract.id,
    policySha256: policySha256(contract),
    producerStatus: 'PASS',
    toolchainsSha256: toolchainsSha256(contract),
  };
}

function stateDocument(contract, candidate, runs) {
  return {
    candidate,
    formatVersion: 1,
    gate: contract.id,
    policySha256: policySha256(contract),
    runs,
    toolchainsSha256: toolchainsSha256(contract),
  };
}

function parseToolchainEvidence(path) {
  const text = readRegular(path, 'pinned Corretto evidence', 16 * 1024).toString('utf8');
  if (text.includes('\r') || !text.endsWith('\n'))
    fail('Pinned Corretto evidence must be LF-terminated text.');
  const values = new Map();
  for (const line of text.slice(0, -1).split('\n')) {
    const equals = line.indexOf('=');
    if (equals <= 0 || equals === line.length - 1)
      fail('Pinned Corretto evidence contains a malformed line.');
    const key = line.slice(0, equals);
    if (values.has(key))
      fail(`Pinned Corretto evidence repeats ${key}.`);
    values.set(key, line.slice(equals + 1));
  }
  const expectedKeys = [
    'archive', 'archiveSha256', 'distribution', 'runtimeVersion',
    'url', 'vendorVersion', 'version',
  ];
  if (values.size !== expectedKeys.length
      || expectedKeys.some((key) => !values.has(key))) {
    fail('Pinned Corretto evidence has an unexpected field set.');
  }
  return values;
}

export function verifyCorrettoEvidence(contract, evidencePath) {
  const expected = contract.toolchains.find((toolchain) =>
    toolchain.artifact.includes('corretto'));
  if (expected === undefined)
    fail(`${contract.id} does not register a Corretto toolchain.`);
  const values = parseToolchainEvidence(requireAbsolute(
    evidencePath,
    'pinned Corretto evidence path',
  ));
  if (values.get('distribution') !== 'corretto'
      || values.get('vendorVersion') !== `Corretto-${expected.version}`
      || values.get('archiveSha256') !== expected.digest.slice('sha256:'.length)) {
    fail('Installed Corretto evidence does not match the registered history toolchain.');
  }
  return true;
}

function corpusEntries(rootPath) {
  const root = requireAbsolute(rootPath, 'fuzz corpus root');
  if (!existsSync(root) || !lstatSync(root).isDirectory()
      || lstatSync(root).isSymbolicLink() || realpathSync(root) !== root) {
    fail('Fuzz corpus root must be a real nonsymlink directory.');
  }
  const entries = [];
  let totalBytes = 0;
  function visit(directory) {
    for (const name of readdirSync(directory).sort()) {
      const path = resolve(directory, name);
      const stats = lstatSync(path);
      if (stats.isSymbolicLink())
        fail(`Fuzz corpus contains a symbolic link: ${path}`);
      if (stats.isDirectory()) {
        visit(path);
      } else if (stats.isFile()) {
        totalBytes += stats.size;
        if (entries.length >= MAXIMUM_CORPUS_FILES || totalBytes > MAXIMUM_CORPUS_BYTES)
          fail('Fuzz corpus exceeds the producer byte or file-count bound.');
        const bytes = readFileSync(path);
        entries.push({
          path: relative(root, path).split(sep).join('/'),
          sha256: sha256(bytes),
          size: bytes.length,
        });
      } else {
        fail(`Fuzz corpus contains a non-regular entry: ${path}`);
      }
    }
  }
  visit(root);
  if (entries.length === 0)
    fail('Fuzz corpus must contain at least one regular file.');
  return entries;
}

function xmlAttributes(fragment) {
  return new Map(
    [...fragment.matchAll(/([A-Za-z_:][A-Za-z0-9_.:-]*)="([^"]*)"/g)]
      .map((match) => [match[1], match[2]]),
  );
}

function verifyPassingSurefireDirectory(path, targetId, configuredSeconds) {
  const root = requireAbsolute(path, 'fuzz Surefire directory');
  if (!existsSync(root) || !lstatSync(root).isDirectory()
      || lstatSync(root).isSymbolicLink() || realpathSync(root) !== root) {
    fail('Fuzz Surefire directory must be a real nonsymlink directory.');
  }
  const reports = readdirSync(root)
    .filter((name) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .sort();
  if (reports.length !== 1)
    fail(`Fuzz target must have exactly one Surefire XML report, found ${reports.length}.`);
  const xml = readRegular(resolve(root, reports[0]), 'fuzz Surefire XML').toString('utf8');
  const suite = xml.match(/<testsuite\b([^>]*)>/);
  if (suite === null || [...xml.matchAll(/<testsuite\b/g)].length !== 1
      || !xml.trimEnd().endsWith('</testsuite>')) {
    fail('Fuzz Surefire XML is malformed or incomplete.');
  }
  const execution = FUZZ_TARGET_EXECUTIONS[targetId];
  if (execution === undefined)
    fail(`Fuzz target has no registered executable mapping: ${targetId}.`);
  if (reports[0] !== `TEST-${execution.className}.xml`)
    fail(`Fuzz Surefire report does not match registered target ${targetId}.`);
  const attributes = xmlAttributes(suite[1]);
  for (const [name, expected] of [['tests', '1'], ['errors', '0'], ['failures', '0'], ['skipped', '0']]) {
    if (attributes.get(name) !== expected)
      fail(`Fuzz Surefire XML ${name} does not equal ${expected}.`);
  }
  if (attributes.get('name') !== execution.className)
    fail(`Fuzz Surefire suite does not match registered target ${targetId}.`);
  if (/<(?:error|failure|skipped)\b/.test(xml))
    fail('Fuzz Surefire XML contains a nonpassing testcase outcome.');
  const testcases = [...xml.matchAll(/<testcase\b([^>]*)>/g)];
  if (testcases.length !== 1)
    fail(`Fuzz Surefire XML must contain exactly one testcase, found ${testcases.length}.`);
  const testcase = xmlAttributes(testcases[0][1]);
  const testcaseName = testcase.get('name');
  if (testcase.get('classname') !== execution.className
      || (testcaseName !== execution.methodName
        && !testcaseName?.startsWith(`${execution.methodName}(`))) {
    fail(`Fuzz Surefire testcase does not match registered target ${targetId}.`);
  }
  const configured = [...xml.matchAll(/<property\b([^>]*)\/>/g)]
    .map((match) => xmlAttributes(match[1]))
    .find((property) => property.get('name') === 'jazzer.max_duration');
  if (configured?.get('value') !== `${configuredSeconds}s`)
    fail('Fuzz Surefire XML does not bind the registered Jazzer duration.');
  const elapsed = Number(testcase.get('time'));
  if (!Number.isFinite(elapsed) || elapsed < configuredSeconds)
    fail('Fuzz testcase duration does not prove the registered active-fuzz interval.');
  return { elapsedSeconds: Math.floor(elapsed), report: reports[0] };
}

export function createFuzzTargetReceipt({
  contract,
  corpusRoot,
  jazzerApiJarPath,
  jazzerEngineJarPath,
  jazzerJarPath,
  outputPath,
  supplementalJazzerSha256 = SUPPLEMENTAL_JAZZER_SHA256,
  surefireRoot,
  targetId,
  toolchainEvidencePath,
}) {
  verifyCorrettoEvidence(contract, toolchainEvidencePath);
  const target = contract.policy.targets.find((value) => value.id === targetId);
  if (target === undefined)
    fail(`Unknown registered fuzz target: ${targetId}.`);
  const jazzer = contract.toolchains.find((toolchain) => toolchain.artifact === 'jazzer-junit.jar');
  if (jazzer === undefined)
    fail('Fuzz history contract does not register jazzer-junit.jar.');
  const jazzerBytes = readRegular(
    requireAbsolute(jazzerJarPath, 'Jazzer JUnit path'),
    'Jazzer JUnit JAR',
    128 * 1024 * 1024,
  );
  if (sha256(jazzerBytes) !== jazzer.digest.slice('sha256:'.length))
    fail('Jazzer JUnit JAR does not match the registered digest.');
  const expectedSupplemental = requireObject(
    supplementalJazzerSha256,
    'supplemental Jazzer digest inventory',
  );
  exactKeys(expectedSupplemental, ['api', 'engine'], 'supplemental Jazzer digest inventory');
  for (const [label, path, expectedDigest] of [
    ['Jazzer API JAR', jazzerApiJarPath, expectedSupplemental.api],
    ['Jazzer engine JAR', jazzerEngineJarPath, expectedSupplemental.engine],
  ]) {
    if (!SHA256_PATTERN.test(expectedDigest))
      fail(`${label} approved SHA-256 is malformed.`);
    const bytes = readRegular(
      requireAbsolute(path, `${label} path`),
      label,
      128 * 1024 * 1024,
    );
    if (sha256(bytes) !== expectedDigest)
      fail(`${label} does not match the producer-pinned digest.`);
  }
  const { elapsedSeconds, report } = verifyPassingSurefireDirectory(
    surefireRoot,
    targetId,
    contract.policy.perTargetDurationSeconds,
  );
  const entries = corpusEntries(corpusRoot);
  const receipt = {
    corpusHash: sha256(Buffer.from(canonicalJson({ entries, targetId }), 'utf8')),
    formatVersion: 1,
    gate: contract.id,
    measuredDurationSeconds: elapsedSeconds,
    surefireReport: report,
    target: {
      durationSeconds: contract.policy.perTargetDurationSeconds,
      id: target.id,
      ordinal: target.ordinal,
      outcome: 'PASS',
    },
    toolchainsSha256: toolchainsSha256(contract),
  };
  writeCreateNewCanonical(requireAbsolute(outputPath, 'fuzz target receipt output'), receipt);
  return receipt;
}

function validateFuzzTargetReceipt(value, contract, expected, label) {
  exactKeys(
    value,
    [
      'corpusHash', 'formatVersion', 'gate', 'measuredDurationSeconds',
      'surefireReport', 'target', 'toolchainsSha256',
    ],
    label,
  );
  exactKeys(value.target, ['durationSeconds', 'id', 'ordinal', 'outcome'], `${label}.target`);
  if (value.formatVersion !== 1 || value.gate !== contract.id
      || value.toolchainsSha256 !== toolchainsSha256(contract)
      || typeof value.surefireReport !== 'string' || value.surefireReport === ''
      || !Number.isSafeInteger(value.measuredDurationSeconds)
      || value.measuredDurationSeconds < contract.policy.perTargetDurationSeconds
      || !SHA256_PATTERN.test(value.corpusHash)
      || value.target.id !== expected.id || value.target.ordinal !== expected.ordinal
      || value.target.durationSeconds !== contract.policy.perTargetDurationSeconds
      || value.target.outcome !== 'PASS') {
    fail(`${label} does not match the registered fuzz target contract.`);
  }
}

export function collectFuzzRun(contract, receiptsRoot, completedAt) {
  parseUtc(completedAt, 'fuzz run completion');
  const root = requireAbsolute(receiptsRoot, 'fuzz target receipts root');
  if (!existsSync(root) || !lstatSync(root).isDirectory()
      || lstatSync(root).isSymbolicLink() || realpathSync(root) !== root) {
    fail('Fuzz target receipts root must be a real nonsymlink directory.');
  }
  const expectedNames = contract.policy.targets.map(({ id }) => `${id}.json`).sort();
  const actualNames = readdirSync(root).sort();
  if (!sameJson(actualNames, expectedNames))
    fail('Fuzz target receipt set is incomplete or contains unexpected files.');
  const receipts = contract.policy.targets.map((target, index) => {
    const receipt = parseCanonicalJson(
      resolve(root, `${target.id}.json`),
      `fuzz target receipt ${index + 1}`,
    );
    validateFuzzTargetReceipt(receipt, contract, target, `fuzz target receipt ${index + 1}`);
    return receipt;
  });
  const corpusHashes = receipts.map((receipt) => receipt.corpusHash);
  if (new Set(corpusHashes).size !== corpusHashes.length)
    fail('Fuzz target corpus hashes must be unique within a run.');
  return {
    completedAt,
    corpusHashes,
    id: completedAt.slice(0, 10),
    outcome: 'PASS',
    targets: receipts.map((receipt) => receipt.target),
  };
}

function validateState(contract, candidate, value) {
  exactKeys(
    value,
    ['candidate', 'formatVersion', 'gate', 'policySha256', 'runs', 'toolchainsSha256'],
    'release history state',
  );
  if (value.formatVersion !== 1 || value.gate !== contract.id
      || value.policySha256 !== policySha256(contract)
      || value.toolchainsSha256 !== toolchainsSha256(contract)
      || !sameJson(value.candidate, candidate)) {
    fail('Release history state does not match the exact candidate and registered policy.');
  }
  const runs = requireArray(value.runs, 'release history state.runs');
  if (runs.length > contract.policy.consecutiveUtcDates)
    fail('Release history state contains too many runs.');
  let previousTime;
  let previousDate;
  for (const [index, run] of runs.entries()) {
    requireObject(run, `release history state.runs[${index}]`);
    const time = parseUtc(run.completedAt, `release history state.runs[${index}].completedAt`);
    if (run.id !== run.completedAt.slice(0, 10))
      fail('Release history state run ID does not match its completion date.');
    const date = Date.parse(`${run.id}T00:00:00Z`);
    if (previousTime !== undefined) {
      const hours = (time - previousTime) / 3_600_000;
      if (date - previousDate !== 86_400_000
          || hours < contract.policy.cadenceHours - contract.policy.cadenceToleranceHours
          || hours > contract.policy.cadenceHours + contract.policy.cadenceToleranceHours) {
        fail('Release history state violates the registered consecutive-date cadence.');
      }
    }
    previousTime = time;
    previousDate = date;
  }
  return runs;
}

export function appendHistoryRun({ candidate, contract, previousState, run }) {
  const priorRuns = previousState === undefined
    ? []
    : validateState(contract, candidate, previousState);
  const runs = [...priorRuns, run];
  if (priorRuns.length > 0) {
    const previous = priorRuns.at(-1);
    const previousTime = parseUtc(previous.completedAt, 'previous history run completion');
    const currentTime = parseUtc(run.completedAt, 'current history run completion');
    const hours = (currentTime - previousTime) / 3_600_000;
    const previousDate = Date.parse(`${previous.id}T00:00:00Z`);
    const currentDate = Date.parse(`${run.id}T00:00:00Z`);
    if (currentDate - previousDate !== 86_400_000
        || hours < contract.policy.cadenceHours - contract.policy.cadenceToleranceHours
        || hours > contract.policy.cadenceHours + contract.policy.cadenceToleranceHours) {
      fail('Current history run does not follow the previous run at the registered cadence.');
    }
  }
  if (runs.length > contract.policy.consecutiveUtcDates)
    runs.shift();
  return stateDocument(contract, candidate, runs);
}

function reportSection(report, scenario) {
  const marker = `## ${scenario}\n\n`;
  const start = report.indexOf(marker);
  if (start === -1 || report.indexOf(marker, start + marker.length) !== -1)
    fail(`Soak report is missing scenario section: ${scenario}.`);
  const bodyStart = start + marker.length;
  const next = report.indexOf('\n## ', bodyStart);
  return report.slice(bodyStart, next === -1 ? report.length : next + 1);
}

function parseResourceLine(section, prefix, label) {
  const pattern = new RegExp(
    `^${prefix}: fd=([+-]?\\d+), heap=([+-]?\\d+) bytes \\([^\\n]*\\), threads=([+-]?\\d+)$`,
    'm',
  );
  const match = section.match(pattern);
  if (match === null)
    fail(`Soak report is missing ${label}.`);
  return {
    fileDescriptors: Math.max(0, Number(match[1])),
    heapBytes: Math.max(0, Number(match[2])),
    liveThreads: Math.max(0, Number(match[3])),
  };
}

export function createSoakRunFromReport({ candidate, completedAt, contract, report }) {
  parseUtc(completedAt, 'soak run completion');
  const scenarios = contract.policy.scenarios.map((scenario) => {
    const section = reportSection(report, scenario.id);
    const resourceBaseline = parseResourceLine(
      section,
      '- Baseline resources',
      `${scenario.id} baseline resources`,
    );
    const resourceDeltas = parseResourceLine(
      section,
      '- Resource deltas',
      `${scenario.id} resource deltas`,
    );
    const inlineReport = {
      candidateCommit: candidate.candidateCommit,
      completedAt,
      outcome: 'PASS',
      profileSha256: contract.policy.profileSha256,
      resourceBaseline,
      resourceDeltas,
      scenario: scenario.id,
      surefire: { errors: 0, failures: 0, skipped: 0, tests: 1 },
      thresholdsPassed: true,
    };
    return {
      id: scenario.id,
      ordinal: scenario.ordinal,
      outcome: 'PASS',
      report: inlineReport,
      reportSha256: sha256(Buffer.from(canonicalJson(inlineReport), 'utf8')),
      resourceThresholdsPassed: true,
    };
  });
  return {
    completedAt,
    id: completedAt.slice(0, 10),
    outcome: 'PASS',
    profile: contract.policy.profile,
    profileSha256: contract.policy.profileSha256,
    scenarios,
    surefire: structuredClone(contract.policy.surefire),
  };
}

export function requireApprovedSoakProfile(contract, actualSha256) {
  if (!SHA256_PATTERN.test(actualSha256)
      || actualSha256 !== contract.policy.profileSha256) {
    fail('Checked-in nightly soak profile does not match the approved history profile SHA-256.');
  }
  return true;
}

export function collectSoakRun({ candidate, completedAt, contract, projectRoot }) {
  const verified = verifySoakEvidence('nightly', projectRoot);
  requireApprovedSoakProfile(contract, verified.profileSha256);
  const report = readRegular(
    resolve(projectRoot, 'soak/target/soak-report.md'),
    'nightly soak report',
    32 * 1024 * 1024,
  ).toString('utf8');
  return createSoakRunFromReport({ candidate, completedAt, contract, report });
}

export function historyEvidenceFromState(contract, state) {
  validateState(contract, state.candidate, state);
  if (state.runs.length !== contract.policy.consecutiveUtcDates)
    fail('Release history state is not yet complete.');
  return { ...commonEvidence(contract, state.candidate), runs: state.runs };
}

export function createOperationalEvidence(contract, candidate, observation) {
  exactKeys(observation, OPERATIONAL_FIELDS, 'operational observation');
  return { ...commonEvidence(contract, candidate), ...structuredClone(observation) };
}

function writeCreateNewCanonical(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  try {
    writeFileSync(path, canonicalJson(value), { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  } catch (error) {
    fail(`Unable to create immutable producer output ${path}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

function writeMutableCanonical(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}`;
  if (existsSync(temporary))
    fail(`Producer state temporary path already exists: ${temporary}`);
  writeFileSync(temporary, canonicalJson(value), { encoding: 'utf8', flag: 'wx', mode: 0o600 });
  renameSync(temporary, path);
}

function writeVerifiedHistory({ contract, evidence, now, outputPath }) {
  const output = requireAbsolute(outputPath, `${contract.id} history output`);
  const expectedName = contract.roles[0].path;
  if (basename(output) !== expectedName)
    fail(`${contract.id} history output basename must be ${expectedName}.`);
  const staging = mkdtempSync(join(tmpdir(), `soklet-${contract.id}-`));
  try {
    writeCreateNewCanonical(resolve(staging, expectedName), evidence);
    verifyReleaseHarnessEvidenceDirectory({
      evidenceRoot: staging,
      gate: contract.id,
      now,
    });
    writeCreateNewCanonical(output, evidence);
  } finally {
    rmSync(staging, { recursive: true });
  }
}

function parseArguments(args) {
  if (args.length === 0)
    fail('Missing release-history producer mode.');
  const mode = args[0];
  const options = new Map();
  for (let index = 1; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!name?.startsWith('--') || value === undefined || value.startsWith('--'))
      fail(`Malformed release-history producer argument near ${name ?? '<end>'}.`);
    if (options.has(name))
      fail(`Duplicate release-history producer argument: ${name}.`);
    options.set(name, value);
  }
  return { mode, options };
}

function required(options, name) {
  const value = options.get(name);
  if (value === undefined)
    fail(`Missing required release-history producer argument: ${name}.`);
  return value;
}

function previousState(options) {
  const path = options.get('--previous-state');
  return path === undefined
    ? undefined
    : parseCanonicalJson(requireAbsolute(path, 'previous history state'), 'previous history state');
}

function ensureExactOptions(options, names) {
  const expected = new Set(names);
  const unexpected = [...options.keys()].filter((name) => !expected.has(name));
  if (unexpected.length !== 0)
    fail(`Unexpected release-history producer arguments: ${unexpected.join(', ')}.`);
}

function produceFuzzTarget(options) {
  ensureExactOptions(options, [
    '--corpus-root', '--jazzer-api-jar', '--jazzer-engine-jar', '--jazzer-jar',
    '--output', '--surefire-root',
    '--target-id', '--toolchain-evidence',
  ]);
  const { contract } = contractFor('fuzz-nightly-history');
  return createFuzzTargetReceipt({
    contract,
    corpusRoot: required(options, '--corpus-root'),
    jazzerApiJarPath: required(options, '--jazzer-api-jar'),
    jazzerEngineJarPath: required(options, '--jazzer-engine-jar'),
    jazzerJarPath: required(options, '--jazzer-jar'),
    outputPath: required(options, '--output'),
    surefireRoot: required(options, '--surefire-root'),
    targetId: required(options, '--target-id'),
    toolchainEvidencePath: required(options, '--toolchain-evidence'),
  });
}

function produceFuzzHistory(options) {
  ensureExactOptions(options, [
    '--candidate-root', '--completed-at', '--history-output', '--previous-state',
    '--state-output', '--target-receipts-root', '--toolchain-evidence',
  ]);
  const { contract } = contractFor('fuzz-nightly-history');
  verifyCorrettoEvidence(contract, required(options, '--toolchain-evidence'));
  const root = requireAbsolute(required(options, '--candidate-root'), 'candidate-root');
  const candidate = releaseHarnessCandidateIdentity({
    candidateRoot: root,
    gate: contract.id,
  });
  const completedAt = required(options, '--completed-at');
  const run = collectFuzzRun(
    contract,
    required(options, '--target-receipts-root'),
    completedAt,
  );
  const state = appendHistoryRun({
    candidate,
    contract,
    previousState: previousState(options),
    run,
  });
  writeMutableCanonical(
    requireAbsolute(required(options, '--state-output'), 'fuzz history state output'),
    state,
  );
  if (state.runs.length === contract.policy.consecutiveUtcDates) {
    writeVerifiedHistory({
      contract,
      evidence: historyEvidenceFromState(contract, state),
      now: Date.now(),
      outputPath: required(options, '--history-output'),
    });
  }
  return { complete: state.runs.length === contract.policy.consecutiveUtcDates, runs: state.runs.length };
}

function produceSoakHistory(options) {
  ensureExactOptions(options, [
    '--candidate-root', '--completed-at', '--history-output', '--previous-state',
    '--state-output', '--toolchain-evidence',
  ]);
  const { contract } = contractFor('soak-nightly-history');
  verifyCorrettoEvidence(contract, required(options, '--toolchain-evidence'));
  const root = requireAbsolute(required(options, '--candidate-root'), 'candidate-root');
  const candidate = releaseHarnessCandidateIdentity({
    candidateRoot: root,
    gate: contract.id,
  });
  const run = collectSoakRun({
    candidate,
    completedAt: required(options, '--completed-at'),
    contract,
    projectRoot: root,
  });
  const state = appendHistoryRun({
    candidate,
    contract,
    previousState: previousState(options),
    run,
  });
  writeMutableCanonical(
    requireAbsolute(required(options, '--state-output'), 'soak history state output'),
    state,
  );
  if (state.runs.length === contract.policy.consecutiveUtcDates) {
    writeVerifiedHistory({
      contract,
      evidence: historyEvidenceFromState(contract, state),
      now: Date.now(),
      outputPath: required(options, '--history-output'),
    });
  }
  return { complete: state.runs.length === contract.policy.consecutiveUtcDates, runs: state.runs.length };
}

function produceOperationalHistory(options) {
  ensureExactOptions(options, [
    '--candidate-root', '--history-output', '--observation', '--toolchain-evidence',
  ]);
  const { contract } = contractFor('operational-history');
  verifyCorrettoEvidence(contract, required(options, '--toolchain-evidence'));
  const root = requireAbsolute(required(options, '--candidate-root'), 'candidate-root');
  const candidate = releaseHarnessCandidateIdentity({
    candidateRoot: root,
    gate: contract.id,
  });
  const observation = parseCanonicalJson(
    requireAbsolute(required(options, '--observation'), 'operational observation'),
    'operational observation',
    128 * 1024 * 1024,
  );
  writeVerifiedHistory({
    contract,
    evidence: createOperationalEvidence(contract, candidate, observation),
    now: Date.now(),
    outputPath: required(options, '--history-output'),
  });
  return { complete: true, runs: 1 };
}

function isDirectExecution() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isDirectExecution()) {
  try {
    const { mode, options } = parseArguments(process.argv.slice(2));
    let result;
    if (mode === 'fuzz-target') {
      result = produceFuzzTarget(options);
    } else if (mode === 'fuzz-nightly') {
      result = produceFuzzHistory(options);
    } else if (mode === 'soak-nightly') {
      result = produceSoakHistory(options);
    } else if (mode === 'operational') {
      result = produceOperationalHistory(options);
    } else {
      fail(`Unsupported release-history producer mode: ${mode}.`);
    }
    if (result.complete !== undefined)
      console.log(`release history production PASS mode=${mode} complete=${result.complete} runs=${result.runs}`);
    else
      console.log(`release history production PASS mode=${mode}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHistoryProductionError ? 1 : 70;
  }
}
