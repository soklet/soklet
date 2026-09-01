#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { delimiter, dirname, isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  canonicalJson,
  releaseHarnessCandidateIdentity,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';
import { verifyCorrettoEvidence } from './produce-release-history.mjs';

const MAXIMUM_TRANSCRIPT_BYTES = 128 * 1024 * 1024;
const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u;
const TRANSCRIPT_KEYS = Object.freeze([
  'drainSeconds',
  'droppedLogRecords',
  'finishedAt',
  'formatVersion',
  'frameworkMetricCardinality',
  'group',
  'logRecordsObserved',
  'metricEventsObserved',
  'metricSamplesObserved',
  'outcomes',
  'policySha256',
  'rejectedMetricDeliveries',
  'samples',
  'scenarios',
  'sensitiveCanaries',
  'startedAt',
  'terminalFrameworkCardinality',
  'unregisteredMetricDimensions',
]);
const SAMPLE_KEYS = Object.freeze([
  'at',
  'droppedLogRecords',
  'frameworkMetricCardinality',
  'rejectedMetricDeliveries',
  'resources',
  'unregisteredMetricDimensions',
]);
const RESOURCE_KEYS = Object.freeze([
  'fileDescriptors',
  'heapBytes',
  'liveThreads',
]);

export class OperationalHistoryProductionError extends Error {}

function fail(message) {
  throw new OperationalHistoryProductionError(message);
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
    fail(`${label} keys do not match the operational producer contract.`);
  }
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

function requireAbsolute(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  return resolve(path);
}

function readCanonicalJson(path, label) {
  const absolute = requireAbsolute(path, label);
  if (!existsSync(absolute))
    fail(`Missing ${label}: ${absolute}`);
  const stats = lstatSync(absolute);
  if (!stats.isFile() || stats.isSymbolicLink() || realpathSync(absolute) !== absolute)
    fail(`${label} must be a real nonsymlink regular file.`);
  if (stats.size <= 0 || stats.size > MAXIMUM_TRANSCRIPT_BYTES)
    fail(`${label} has an invalid byte size: ${stats.size}.`);
  const bytes = readFileSync(absolute);
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

function validateResource(value, label) {
  exactKeys(value, RESOURCE_KEYS, label);
  for (const key of RESOURCE_KEYS)
    requireInteger(value[key], `${label}.${key}`);
}

function policySha256(contract) {
  return sha256(Buffer.from(canonicalJson(contract.policy), 'utf8'));
}

function validateScenario(value, expectedId, policy, label) {
  exactKeys(value, [
    'expectedOperations',
    'id',
    'successfulOperations',
    'uniqueAdversarialDimensionValues',
  ], label);
  const expectedOperations = policy.loadShape.clientsPerScenario
    * policy.loadShape.operationsPerClientPerSecond
    * policy.loadShape.secondsPerScenario;
  if (value.id !== expectedId
      || value.expectedOperations !== expectedOperations
      || value.successfulOperations !== expectedOperations
      || value.uniqueAdversarialDimensionValues
        !== policy.loadShape.uniqueAdversarialDimensionValuesPerScenario) {
    fail(`${label} does not prove the registered load shape.`);
  }
}

function validateTranscript(value, expectedGroup, contract) {
  const label = `${expectedGroup} operational transcript`;
  exactKeys(value, TRANSCRIPT_KEYS, label);
  if (value.formatVersion !== 1 || value.group !== expectedGroup
      || value.policySha256 !== policySha256(contract)) {
    fail(`${label} identity does not match the registered policy.`);
  }
  parseUtc(value.startedAt, `${label}.startedAt`);
  parseUtc(value.finishedAt, `${label}.finishedAt`);
  requireNumber(value.drainSeconds, `${label}.drainSeconds`);
  if (value.drainSeconds > contract.policy.drainMaximumSeconds)
    fail(`${label} exceeded the registered drain maximum.`);
  if (requireArray(value.outcomes, `${label}.outcomes`).length !== 0)
    fail(`${label} contains a load outcome failure.`);
  for (const field of [
    'frameworkMetricCardinality',
    'logRecordsObserved',
    'metricEventsObserved',
    'metricSamplesObserved',
    'sensitiveCanaries',
    'terminalFrameworkCardinality',
  ]) {
    requireInteger(value[field], `${label}.${field}`);
  }
  for (const field of [
    'droppedLogRecords',
    'rejectedMetricDeliveries',
    'unregisteredMetricDimensions',
  ]) {
    if (requireInteger(value[field], `${label}.${field}`) !== 0)
      fail(`${label} contains a terminal zero-tolerance observation.`);
  }
  if (value.frameworkMetricCardinality !== 0
      || value.terminalFrameworkCardinality !== contract.policy.terminalFrameworkCardinality
      || value.sensitiveCanaries !== contract.policy.sensitiveCanariesAllowed
      || value.metricSamplesObserved === 0) {
    fail(`${label} did not prove clean, nonempty framework telemetry.`);
  }
  const expectedScenarios = expectedGroup === 'http'
    ? ['http']
    : ['mcp', 'realtime'];
  const scenarios = requireArray(value.scenarios, `${label}.scenarios`);
  if (scenarios.length !== expectedScenarios.length)
    fail(`${label} has the wrong scenario count.`);
  scenarios.forEach((scenario, index) => validateScenario(
    scenario,
    expectedScenarios[index],
    contract.policy,
    `${label}.scenarios[${index}]`,
  ));

  const requiredSpanSeconds = contract.policy.durationSeconds
    + contract.policy.postIntervalReserveSeconds;
  const requiredSamples = Math.floor(
    requiredSpanSeconds / contract.policy.cadenceSeconds,
  ) + 1;
  const samples = requireArray(value.samples, `${label}.samples`);
  if (samples.length !== requiredSamples)
    fail(`${label} must contain exactly ${requiredSamples} registered-cadence samples.`);
  if (value.metricSamplesObserved < samples.length)
    fail(`${label} did not audit real framework metrics at every sample.`);
  if (expectedGroup === 'http') {
    if (value.metricEventsObserved !== 0 || value.logRecordsObserved !== 0) {
      fail(`${label} contains MCP-only semantic telemetry.`);
    }
  } else {
    const mcpOperations = scenarios[0].successfulOperations;
    if (value.metricEventsObserved !== mcpOperations
        || value.logRecordsObserved !== mcpOperations) {
      fail(`${label} did not observe exact semantic MCP metric and trace-log delivery under load.`);
    }
  }
  let previous = null;
  for (const [index, sample] of samples.entries()) {
    const sampleLabel = `${label}.samples[${index}]`;
    exactKeys(sample, SAMPLE_KEYS, sampleLabel);
    const at = parseUtc(sample.at, `${sampleLabel}.at`);
    if (previous !== null) {
      const gap = (at - previous) / 1000;
      if (gap <= 0 || gap > contract.policy.maximumSampleGapSeconds)
        fail(`${label} has a nonmonotonic or excessive sample gap.`);
    }
    previous = at;
    for (const field of [
      'droppedLogRecords',
      'frameworkMetricCardinality',
      'rejectedMetricDeliveries',
      'unregisteredMetricDimensions',
    ]) {
      if (requireInteger(sample[field], `${sampleLabel}.${field}`) !== 0)
        fail(`${label} contains a zero-tolerance observation.`);
    }
    validateResource(sample.resources, `${sampleLabel}.resources`);
  }
  const spanSeconds = parseUtc(samples.at(-1).at, `${label} last sample`)
    - parseUtc(samples[0].at, `${label} first sample`);
  if (spanSeconds < requiredSpanSeconds * 1000)
    fail(`${label} does not span the registered wall-clock interval.`);
  if (value.startedAt !== samples[0].at || value.finishedAt !== samples.at(-1).at)
    fail(`${label} start/finish timestamps are not bound to its samples.`);
  return samples;
}

function combinedSample(http, mcpAndRealtime, label) {
  const httpAt = parseUtc(http.at, `${label}.http.at`);
  const mcpAt = parseUtc(mcpAndRealtime.at, `${label}.mcpAndRealtime.at`);
  if (Math.abs(httpAt - mcpAt) > 5_000)
    fail(`${label} child observations are not contemporaneous.`);
  return {
    at: httpAt >= mcpAt ? http.at : mcpAndRealtime.at,
    droppedLogRecords: http.droppedLogRecords + mcpAndRealtime.droppedLogRecords,
    frameworkMetricCardinality:
      http.frameworkMetricCardinality + mcpAndRealtime.frameworkMetricCardinality,
    rejectedMetricDeliveries:
      http.rejectedMetricDeliveries + mcpAndRealtime.rejectedMetricDeliveries,
    resources: {
      http: structuredClone(http.resources),
      mcpAndRealtime: structuredClone(mcpAndRealtime.resources),
    },
    unregisteredMetricDimensions:
      http.unregisteredMetricDimensions + mcpAndRealtime.unregisteredMetricDimensions,
  };
}

function resourceGrowth(first, last) {
  return Object.fromEntries(RESOURCE_KEYS.map((key) => [key, Math.max(0, last[key] - first[key])]));
}

export function operationalObservationFromTranscripts({
  contract,
  elapsedNanoseconds,
  httpTranscript,
  mcpAndRealtimeTranscript,
}) {
  requireObject(contract, 'operational contract');
  const policy = requireObject(contract.policy, 'operational contract policy');
  const httpSamples = validateTranscript(httpTranscript, 'http', contract);
  const mcpSamples = validateTranscript(
    mcpAndRealtimeTranscript,
    'mcpAndRealtime',
    contract,
  );
  if (typeof elapsedNanoseconds !== 'bigint' || elapsedNanoseconds < 0n)
    fail('elapsedNanoseconds must be a nonnegative bigint.');
  const requiredNanoseconds = BigInt(
    policy.durationSeconds + policy.postIntervalReserveSeconds,
  ) * 1_000_000_000n;
  if (elapsedNanoseconds < requiredNanoseconds)
    fail('The producer process did not observe the registered wall-clock interval.');

  const samples = httpSamples.map((sample, index) => combinedSample(
    sample,
    mcpSamples[index],
    `combined operational sample ${index + 1}`,
  ));
  let previous = null;
  for (const sample of samples) {
    const at = parseUtc(sample.at, 'combined operational sample.at');
    if (previous !== null) {
      const gap = (at - previous) / 1000;
      if (gap <= 0 || gap > policy.maximumSampleGapSeconds)
        fail('Combined operational samples have a nonmonotonic or excessive gap.');
    }
    previous = at;
  }
  const spanSeconds = (parseUtc(samples.at(-1).at, 'combined last sample')
    - parseUtc(samples[0].at, 'combined first sample')) / 1000;
  if (spanSeconds < policy.durationSeconds + policy.postIntervalReserveSeconds)
    fail('Combined operational samples do not span the registered interval.');

  const resourceBaselines = structuredClone(samples[0].resources);
  const finalResourceDeltas = {
    http: resourceGrowth(resourceBaselines.http, samples.at(-1).resources.http),
    mcpAndRealtime: resourceGrowth(
      resourceBaselines.mcpAndRealtime,
      samples.at(-1).resources.mcpAndRealtime,
    ),
  };
  for (const group of ['http', 'mcpAndRealtime']) {
    for (const measure of RESOURCE_KEYS) {
      if (finalResourceDeltas[group][measure] > policy.finalResourceDeltas[group][measure])
        fail(`Operational resource delta exceeds policy: ${group}.${measure}.`);
    }
  }

  return {
    cadenceSeconds: policy.cadenceSeconds,
    drainSeconds: Math.max(httpTranscript.drainSeconds, mcpAndRealtimeTranscript.drainSeconds),
    durationSeconds: policy.durationSeconds,
    finalResourceDeltas,
    loadShape: structuredClone(policy.loadShape),
    maximumSampleGapSeconds: policy.maximumSampleGapSeconds,
    outcomes: [],
    postIntervalReserveSeconds: policy.postIntervalReserveSeconds,
    resourceBaselines,
    samples,
    sensitiveCanaries:
      httpTranscript.sensitiveCanaries + mcpAndRealtimeTranscript.sensitiveCanaries,
    terminalFrameworkCardinality:
      httpTranscript.terminalFrameworkCardinality
        + mcpAndRealtimeTranscript.terminalFrameworkCardinality,
  };
}

function parseArguments(args) {
  if (args[0] !== 'run')
    fail('Usage: produce-operational-history.mjs run <registered arguments>.');
  const options = new Map();
  for (let index = 1; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!name?.startsWith('--') || value === undefined || value.startsWith('--'))
      fail(`Malformed operational producer argument near ${name ?? '<end>'}.`);
    if (options.has(name))
      fail(`Duplicate operational producer argument: ${name}.`);
    options.set(name, value);
  }
  const expected = new Set([
    '--candidate-root',
    '--observation-output',
    '--toolchain-evidence',
    '--work-root',
  ]);
  const unexpected = [...options.keys()].filter((key) => !expected.has(key));
  if (unexpected.length !== 0)
    fail(`Unexpected operational producer arguments: ${unexpected.join(', ')}.`);
  for (const key of expected) {
    if (!options.has(key))
      fail(`Missing required operational producer argument: ${key}.`);
  }
  return options;
}

function runChecked(command, args, label) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.error !== undefined || result.status !== 0) {
    const detail = `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
    fail(`${label} failed${detail.length === 0 ? '.' : `: ${detail}`}`);
  }
}

function runChild(java, classpath, args, label, runningChildren) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(java, ['-cp', classpath, 'com.soklet.OperationalHistoryHarness', ...args], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    runningChildren.add(child);
    const rejectAndStopChildren = (error) => {
      for (const running of runningChildren)
        running.kill('SIGTERM');
      rejectPromise(error);
    };
    let output = '';
    const capture = (bytes) => {
      output += bytes.toString('utf8');
      if (Buffer.byteLength(output, 'utf8') > 8 * 1024 * 1024) {
        rejectAndStopChildren(new OperationalHistoryProductionError(
          `${label} emitted excessive output.`,
        ));
      }
    };
    child.stdout.on('data', capture);
    child.stderr.on('data', capture);
    child.on('error', (error) => rejectAndStopChildren(
      new OperationalHistoryProductionError(
        `${label} failed to start: ${error.message}`,
      ),
    ));
    child.on('close', (status, signal) => {
      runningChildren.delete(child);
      if (status !== 0) {
        rejectAndStopChildren(new OperationalHistoryProductionError(
          `${label} failed status=${status ?? 'none'} signal=${signal ?? 'none'}: ${output.trim()}`,
        ));
      } else {
        resolvePromise();
      }
    });
  });
}

function javaSources(candidateRoot) {
  return [
    resolve(candidateRoot, 'verification/operational/src/main/java/com/soklet/OperationalHistoryHarness.java'),
    resolve(candidateRoot, 'verification/operational/src/test/java/com/soklet/OperationalHistoryHarnessSelfTest.java'),
  ];
}

function propertiesText(contract) {
  const { loadShape } = contract.policy;
  const values = {
    cadenceSeconds: contract.policy.cadenceSeconds,
    clientsPerScenario: loadShape.clientsPerScenario,
    deterministicSeed: loadShape.deterministicSeed,
    drainMaximumSeconds: contract.policy.drainMaximumSeconds,
    durationSeconds: contract.policy.durationSeconds,
    maximumSampleGapSeconds: contract.policy.maximumSampleGapSeconds,
    operationsPerClientPerSecond: loadShape.operationsPerClientPerSecond,
    policySha256: policySha256(contract),
    postIntervalReserveSeconds: contract.policy.postIntervalReserveSeconds,
    secondsPerScenario: loadShape.secondsPerScenario,
    uniqueAdversarialDimensionValuesPerScenario:
      loadShape.uniqueAdversarialDimensionValuesPerScenario,
  };
  return `${Object.keys(values).sort().map((key) => `${key}=${values[key]}`).join('\n')}\n`;
}

async function produce(options) {
  const candidateRoot = requireAbsolute(options.get('--candidate-root'), 'candidate-root');
  const workRoot = requireAbsolute(options.get('--work-root'), 'work-root');
  const observationOutput = requireAbsolute(
    options.get('--observation-output'),
    'observation-output',
  );
  const toolchainEvidence = requireAbsolute(
    options.get('--toolchain-evidence'),
    'toolchain-evidence',
  );
  if (existsSync(workRoot))
    fail(`Operational work-root already exists: ${workRoot}`);
  if (existsSync(observationOutput))
    fail(`Operational observation output already exists: ${observationOutput}`);

  const configuration = verifyReleaseHarnessConfiguration(
    resolve(candidateRoot, 'release/release-harness-contracts.json'),
  );
  const contract = configuration.contracts.get('operational-history');
  if (contract === undefined)
    fail('Missing registered operational-history contract.');
  verifyCorrettoEvidence(contract, toolchainEvidence);
  const candidateBefore = releaseHarnessCandidateIdentity({
    candidateRoot,
    gate: contract.id,
  });

  const candidateJar = resolve(candidateRoot, 'target/soklet-4.0.0.jar');
  if (!existsSync(candidateJar))
    fail(`Missing exact candidate main JAR: ${candidateJar}`);
  mkdirSync(workRoot, { recursive: false, mode: 0o700 });
  const classes = resolve(workRoot, 'classes');
  mkdirSync(classes, { mode: 0o700 });
  const policyPath = resolve(workRoot, 'registered-operational-policy.properties');
  writeFileSync(policyPath, propertiesText(contract), { flag: 'wx', mode: 0o600 });
  const javaHome = process.env.JAVA_HOME;
  if (javaHome === undefined || !isAbsolute(javaHome))
    fail('JAVA_HOME must identify the pinned registered Corretto installation.');
  const javac = resolve(javaHome, 'bin/javac');
  const java = resolve(javaHome, 'bin/java');
  runChecked(javac, [
    '--release', '17',
    '-classpath', candidateJar,
    '-d', classes,
    ...javaSources(candidateRoot),
  ], 'Operational Java harness compilation');
  const classpath = `${classes}${delimiter}${candidateJar}`;
  runChecked(
    java,
    ['-cp', classpath, 'com.soklet.OperationalHistoryHarnessSelfTest'],
    'Operational Java harness self-test',
  );

  const startAt = new Date(Math.ceil((Date.now() + 30_000) / 1000) * 1000)
    .toISOString().replace('.000Z', 'Z');
  const httpPath = resolve(workRoot, 'http-transcript.json');
  const mcpPath = resolve(workRoot, 'mcp-and-realtime-transcript.json');
  const runningChildren = new Set();
  const started = process.hrtime.bigint();
  await Promise.all([
    runChild(java, classpath, [
      '--group', 'http',
      '--output', httpPath,
      '--policy', policyPath,
      '--start-at', startAt,
    ], 'HTTP operational child', runningChildren),
    runChild(java, classpath, [
      '--group', 'mcpAndRealtime',
      '--output', mcpPath,
      '--policy', policyPath,
      '--start-at', startAt,
    ], 'MCP/realtime operational child', runningChildren),
  ]);
  const elapsedNanoseconds = process.hrtime.bigint() - started;
  const observation = operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: readCanonicalJson(httpPath, 'HTTP operational transcript'),
    mcpAndRealtimeTranscript: readCanonicalJson(
      mcpPath,
      'MCP/realtime operational transcript',
    ),
  });
  const candidateAfter = releaseHarnessCandidateIdentity({
    candidateRoot,
    gate: contract.id,
  });
  if (canonicalJson(candidateAfter) !== canonicalJson(candidateBefore))
    fail('Exact candidate identity changed while operational evidence was produced.');
  mkdirSync(dirname(observationOutput), { recursive: true });
  writeFileSync(observationOutput, canonicalJson(observation), {
    encoding: 'utf8',
    flag: 'wx',
    mode: 0o600,
  });
  console.log(`operational observation PASS samples=${observation.samples.length}`);
}

function isDirectExecution() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isDirectExecution()) {
  try {
    await produce(parseArguments(process.argv.slice(2)));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof OperationalHistoryProductionError ? 1 : 70;
  }
}
