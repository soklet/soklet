#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import {
  closeSync,
  existsSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  canonicalJson,
  createReleaseHarnessBundle,
  releaseHarnessCandidateIdentity,
  ReleaseHarnessEvidenceImportError,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';

const GATE = 'mcp-benchmarks';
const BASELINE = '3.5.1';
const CANDIDATE = '4.0.0';
const BENCHMARK_RELEASE_NOTE_PATH = 'CHANGELOG.md';
const MAXIMUM_FILE_BYTES = 64 * 1024 * 1024;
const JSON_BENCHMARKS = Object.freeze([
  'com.soklet.McpReleaseJsonJmhBenchmark.jsonParse',
  'com.soklet.McpReleaseJsonJmhBenchmark.jsonWrite',
]);
const PROFILE_BENCHMARKS = Object.freeze([
  'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaCompile',
  'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaEvaluate',
]);

function fail(message) {
  throw new ReleaseHarnessEvidenceImportError(message);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function sameJson(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function requireString(value, label) {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0
      || value.length > 512 || /[\u0000-\u001f\u007f]/u.test(value)) {
    fail(`${label} must be a nonempty, trimmed, single-line reference of at most 512 characters.`);
  }
  return value;
}

function requireSha256(value, label) {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/u.test(value))
    fail(`${label} must be a lowercase SHA-256 digest.`);
  return value;
}

function requireDigestBoundSignoffReference(value, reviewedDraftSha256) {
  requireString(value, 'benchmark sign-off reference');
  const match = value.match(
    /^([A-Za-z][A-Za-z0-9+.-]*:[^\s#]+)#sha256=([0-9a-f]{64})$/u,
  );
  if (match === null || match[2] !== reviewedDraftSha256) {
    fail(
      'Benchmark sign-off reference must be a durable URI-like reference '
        + 'ending in #sha256=<reviewed-draft-sha256>.',
    );
  }
  return value;
}

function requireDigestBoundRegressionApprovalReference(
  value,
  reviewedDraftSha256,
) {
  requireString(value, 'benchmark regression approval reference');
  const match = value.match(
    /^([A-Za-z][A-Za-z0-9+.-]*:[^\s#]+)#sha256=([0-9a-f]{64})$/u,
  );
  if (match === null || match[2] !== reviewedDraftSha256) {
    fail(
      'Benchmark regression approval reference must be a durable URI-like reference '
        + 'ending in #sha256=<reviewed-draft-sha256>.',
    );
  }
  return value;
}

function requireAbsoluteDirectory(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  const absolute = resolve(path);
  if (!existsSync(absolute))
    fail(`${label} does not exist: ${absolute}`);
  const stats = lstatSync(absolute);
  if (!stats.isDirectory() || stats.isSymbolicLink()
      || realpathSync(absolute) !== absolute) {
    fail(`${label} must be a real nonsymlink directory.`);
  }
  return absolute;
}

function createDirectory(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  const absolute = resolve(path);
  if (existsSync(absolute))
    fail(`${label} already exists: ${absolute}`);
  mkdirSync(absolute, { mode: 0o700, recursive: false });
  return requireAbsoluteDirectory(absolute, label);
}

function readRegularFile(path, label, maximumBytes = MAXIMUM_FILE_BYTES) {
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink() || stats.size > maximumBytes)
    fail(`${label} must be a bounded regular nonsymlink file.`);
  return readFileSync(path);
}

function benchmarkReleaseNoteEntry(candidateRoot) {
  const bytes = readRegularFile(
    join(candidateRoot, BENCHMARK_RELEASE_NOTE_PATH),
    'candidate benchmark release note',
    4 * 1024 * 1024,
  );
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes)
      || text.includes('\r') || !text.endsWith('\n')) {
    fail('Candidate benchmark release note must be UTF-8/LF text ending in LF.');
  }
  const headings = [...text.matchAll(
    /^## 4\.0\.0(?: \([^()\n]+\))?\n/gmu,
  )];
  if (headings.length !== 1) {
    fail(
      'Candidate CHANGELOG.md must have exactly one 4.0.0 release-note '
        + 'heading, optionally followed by one parenthetical label.',
    );
  }
  const start = headings[0].index;
  const next = text.indexOf('\n## ', start + 1);
  const entry = text.slice(start, next < 0 ? text.length : next + 1);
  if (!/\bbenchmark(?:s|ing)?\b/iu.test(entry) || !/\bregression\b/iu.test(entry)) {
    fail(
      'A benchmark regression requires the 4.0.0 CHANGELOG entry to describe '
        + 'the benchmark regression.',
    );
  }
  return Buffer.from(entry, 'utf8');
}

function writeNewFile(path, bytes, mode = 0o600) {
  const descriptor = openSync(path, 'wx', mode);
  try {
    writeFileSync(descriptor, bytes);
  } finally {
    closeSync(descriptor);
  }
}

function writeNewJson(path, value) {
  writeNewFile(path, Buffer.from(canonicalJson(value), 'utf8'));
}

function readCanonicalJson(path, label) {
  const bytes = readRegularFile(path, label);
  let value;
  try {
    value = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`${label} is not JSON: ${error.message}`);
  }
  if (!Buffer.from(canonicalJson(value), 'utf8').equals(bytes))
    fail(`${label} is not canonical UTF-8/LF JSON.`);
  return { bytes, value };
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: 'utf8',
    env: options.env,
    maxBuffer: MAXIMUM_FILE_BYTES,
  });
  if (result.error !== undefined)
    fail(`Unable to execute ${command}: ${result.error.message}`);
  const transcript = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  if (result.status !== 0)
    fail(`${command} exited ${result.status}.\n${transcript}`);
  return transcript;
}

function requiredLinuxValue(path, label) {
  const value = readRegularFile(path, label, 64 * 1024).toString('utf8').trim();
  return requireString(value, label);
}

function cpuInfoField(name) {
  const cpuInfo = readRegularFile('/proc/cpuinfo', 'Linux CPU information',
    4 * 1024 * 1024).toString('utf8');
  const match = cpuInfo.match(new RegExp(`^${name}\\s*:\\s*(.+)$`, 'mu'));
  if (match === null)
    fail(`Linux CPU information does not expose ${name}.`);
  return requireString(match[1].trim(), `Linux CPU ${name}`);
}

function turboState() {
  const intel = '/sys/devices/system/cpu/intel_pstate/no_turbo';
  if (existsSync(intel)) {
    const value = requiredLinuxValue(intel, 'Intel no-turbo state');
    if (value === '0')
      return 'enabled:intel_pstate/no_turbo=0';
    if (value === '1')
      return 'disabled:intel_pstate/no_turbo=1';
    fail('Intel no-turbo state is not 0 or 1.');
  }
  const boost = '/sys/devices/system/cpu/cpufreq/boost';
  if (existsSync(boost)) {
    const value = requiredLinuxValue(boost, 'CPU boost state');
    if (value === '0')
      return 'disabled:cpufreq/boost=0';
    if (value === '1')
      return 'enabled:cpufreq/boost=1';
    fail('CPU boost state is not 0 or 1.');
  }
  fail('The runner does not expose a supported turbo/boost state interface.');
}

function captureEnvironment() {
  if (process.platform !== 'linux' || process.arch !== 'x64')
    fail('MCP release benchmarks require Linux x86_64.');
  if (process.env.ImageOS !== 'ubuntu24')
    fail('MCP release benchmarks require the GitHub ubuntu-24.04 image.');
  const imageVersion = requireString(process.env.ImageVersion,
    'GitHub runner image version');
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/u.test(imageVersion))
    fail('GitHub runner image version has an unsupported format.');
  const architecture = run('/usr/bin/uname', ['-m']).trim();
  if (architecture !== 'x86_64')
    fail(`MCP release benchmark architecture is ${architecture}, not x86_64.`);
  return {
    architecture,
    cpuModel: cpuInfoField('model name'),
    governor: requiredLinuxValue(
      '/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor',
      'CPU scaling governor'),
    image: `ubuntu-24.04@${imageVersion}`,
    kernel: requireString(run('/usr/bin/uname', ['-r']).trim(), 'Linux kernel'),
    microcode: cpuInfoField('microcode'),
    sameBoot: true,
    samePhysicalRunner: true,
    turboState: turboState(),
  };
}

function verifyFileDigest(path, expected, label) {
  const actual = sha256(readRegularFile(path, label));
  if (actual !== expected)
    fail(`${label} SHA-256 is ${actual}, expected ${expected}.`);
}

function verifyToolchains(contract, inputRoot, javaPath) {
  for (const toolchain of contract.toolchains) {
    let path;
    if (toolchain.artifact === 'corretto-17.0.20.8.1-linux-x64.tar.gz') {
      const settings = run(javaPath, ['-XshowSettings:properties', '-version']);
      if (!settings.includes('java.vendor = Amazon.com Inc.')
          || !settings.includes(`java.vendor.version = Corretto-${toolchain.version}`)) {
        fail('The executing Java runtime is not the registered Corretto build.');
      }
      continue;
    } else if (toolchain.artifact === 'jmh-core.jar') {
      path = join(inputRoot, `jmh-core-${toolchain.version}.jar`);
    } else if (toolchain.artifact === 'jmh-generator-annprocess.jar') {
      path = join(inputRoot,
        `jmh-generator-annprocess-${toolchain.version}.jar`);
    } else {
      fail(`Unsupported MCP benchmark toolchain artifact: ${toolchain.artifact}`);
    }
    verifyFileDigest(path, toolchain.digest.replace(/^sha256:/u, ''),
      `MCP benchmark toolchain ${toolchain.artifact}`);
  }
}

function requirePositiveNumber(value, label) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0)
    fail(`${label} must be a positive finite number.`);
  return value;
}

export function summarizeJmhResults(results, {
  artifact,
  benchmarks,
  configuration,
  expectedForks,
  expectedJdkVersion = '17.0.20',
}) {
  if (!Array.isArray(results) || results.length !== benchmarks.length)
    fail('JMH JSON has a missing or extra benchmark result.');
  const byBenchmark = new Map(results.map((entry) => [entry.benchmark, entry]));
  if (byBenchmark.size !== results.length)
    fail('JMH JSON contains a duplicate benchmark result.');
  return benchmarks.map((benchmark) => {
    const entry = byBenchmark.get(benchmark);
    if (entry === undefined
        || entry.jmhVersion !== '1.37'
        || entry.jdkVersion !== expectedJdkVersion
        || entry.mode !== 'thrpt'
        || entry.threads !== configuration.threads
        || entry.forks !== expectedForks
        || entry.warmupIterations !== configuration.warmup.iterations
        || entry.warmupTime !== `${configuration.warmup.secondsPerIteration} s`
        || entry.measurementIterations !== configuration.measurement.iterations
        || entry.measurementTime
          !== `${configuration.measurement.secondsPerIteration} s`
        || !sameJson(entry.params, { artifact })
        || entry.primaryMetric?.scoreUnit !== 'ops/s'
        || !sameJson(entry.jvmArgs, configuration.candidateJvm)) {
      fail(`JMH result ${benchmark} drifted from registered execution policy.`);
    }
    const declaredScore = requirePositiveNumber(entry.primaryMetric.score,
      `${benchmark} score`);
    if (!Array.isArray(entry.primaryMetric.rawData)
        || entry.primaryMetric.rawData.length !== expectedForks
        || entry.primaryMetric.rawData.some((fork) =>
          !Array.isArray(fork)
            || fork.length !== configuration.measurement.iterations
            || fork.some((sample) => typeof sample !== 'number'
              || !Number.isFinite(sample) || sample <= 0))) {
      fail(`JMH result ${benchmark} does not retain every positive raw sample.`);
    }
    const samples = entry.primaryMetric.rawData.flat();
    const score = samples.reduce((sum, sample) => sum + sample, 0)
      / samples.length;
    const tolerance = Math.max(1e-12, Math.abs(score) * 1e-12);
    if (Math.abs(declaredScore - score) > tolerance) {
      fail(
        `JMH result ${benchmark} declared score does not derive from its raw samples.`,
      );
    }
    return { benchmark, entry, score };
  });
}

function jmhArguments({ artifact, benchmarks, configuration, forks, outputPath }) {
  const escapedNames = benchmarks.map((name) =>
    name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  return [
    '-jar', 'benchmarks/target/soklet-benchmarks.jar',
    `^(${escapedNames.join('|')})$`,
    '-p', `artifact=${artifact}`,
    '-bm', 'thrpt',
    '-tu', 's',
    '-wi', String(configuration.warmup.iterations),
    '-w', `${configuration.warmup.secondsPerIteration}s`,
    '-i', String(configuration.measurement.iterations),
    '-r', `${configuration.measurement.secondsPerIteration}s`,
    '-f', String(forks),
    '-t', String(configuration.threads),
    '-jvmArgs', configuration.candidateJvm.join(' '),
    '-rf', 'json',
    '-rff', outputPath,
  ];
}

function runJmh({
  artifact,
  benchmarks,
  candidateRoot,
  configuration,
  environment,
  forks,
  jdkVersion,
  javaPath,
  outputPath,
}) {
  const transcript = run(javaPath, jmhArguments({
    artifact, benchmarks, configuration, forks, outputPath,
  }), { cwd: candidateRoot, env: environment });
  const { bytes, value } = readCanonicalOrJmhJson(outputPath,
    `JMH ${artifact} result`);
  const summary = summarizeJmhResults(value, {
    artifact, benchmarks, configuration, expectedForks: forks,
    expectedJdkVersion: jdkVersion,
  });
  return { bytes, summary, transcript };
}

function readCanonicalOrJmhJson(path, label) {
  const original = readRegularFile(path, label);
  let value;
  try {
    value = JSON.parse(original.toString('utf8'));
  } catch (error) {
    fail(`${label} is not JSON: ${error.message}`);
  }
  const bytes = Buffer.from(canonicalJson(value), 'utf8');
  return { bytes, value };
}

function configurationFor(contract) {
  return {
    candidateJvm: contract.policy.candidateJvm,
    forks: contract.policy.forks,
    measurement: contract.policy.measurement,
    threads: contract.policy.threads,
    warmup: contract.policy.warmup,
  };
}

function jdkVersionFor(contract) {
  const toolchain = contract.toolchains.find(({ artifact }) =>
    artifact.startsWith('corretto-'));
  if (toolchain === undefined || !/^17\.0\.20\./u.test(toolchain.version))
    fail('MCP benchmark contract has an unsupported JDK pin.');
  return toolchain.version.split('.').slice(0, 3).join('.');
}

function normalizedJsonRun(artifact, summary, configuration) {
  const scoreByBenchmark = new Map(summary.map((entry) =>
    [entry.benchmark, entry.score]));
  const rawResult = {
    artifact,
    configuration,
    jsonParseScore: scoreByBenchmark.get(JSON_BENCHMARKS[0]),
    jsonWriteScore: scoreByBenchmark.get(JSON_BENCHMARKS[1]),
  };
  return {
    artifact,
    outcome: 'PASS',
    rawResult,
    rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
  };
}

function normalizedProfile(operation, result) {
  const rawEntry = Buffer.from(canonicalJson(result.entry), 'utf8');
  const rawResult = {
    complete: true,
    errors: [],
    operation,
    result: {
      benchmark: result.benchmark,
      rawJmhResultSha256: sha256(rawEntry),
      score: result.score,
      scoreError: result.entry.primaryMetric.scoreError,
      scoreUnit: result.entry.primaryMetric.scoreUnit,
    },
  };
  return {
    errors: 0,
    operation,
    rawResult,
    rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
  };
}

function relativeRawPath(workRoot, path) {
  const value = relative(workRoot, path).split(sep).join('/');
  if (value.startsWith('../') || value.length === 0)
    fail('Raw JMH path escapes its work root.');
  return value;
}

function draftRun(workRoot, rawPath, runResult, rawBytes) {
  return {
    normalized: runResult,
    rawJmhPath: relativeRawPath(workRoot, rawPath),
    rawJmhSha256: sha256(rawBytes),
  };
}

function benchmarkMean(repetitions, artifact, key) {
  const scores = repetitions.flatMap((repetition) => repetition.runs)
    .map((run) => run.normalized ?? run)
    .filter((run) => run.artifact === artifact)
    .map((run) => run.rawResult[key]);
  return scores.reduce((sum, score) => sum + score, 0) / scores.length;
}

function contractConfiguration(candidateRoot) {
  const configuration = verifyReleaseHarnessConfiguration(
    join(candidateRoot, 'release/release-harness-contracts.json'));
  const contract = configuration.contracts.get(GATE);
  if (contract === undefined)
    fail(`Missing ${GATE} release harness contract.`);
  return { configuration, contract };
}

export function runMcpReleaseBenchmarks({
  approvalReference,
  candidateRoot,
  workRoot,
}) {
  const exactCandidateRoot = requireAbsoluteDirectory(candidateRoot,
    'candidate root');
  requireString(approvalReference, 'benchmark approval reference');
  const candidate = releaseHarnessCandidateIdentity({
    candidateRoot: exactCandidateRoot,
    gate: GATE,
  });
  const { configuration: registry, contract } =
    contractConfiguration(exactCandidateRoot);
  const configuration = configurationFor(contract);
  const exactWorkRoot = createDirectory(workRoot, 'benchmark work root');
  const rawRoot = join(exactWorkRoot, 'raw');
  mkdirSync(rawRoot, { mode: 0o700 });
  const inputRoot = join(exactCandidateRoot, 'benchmarks', 'target',
    'release-inputs');
  const baselineJar = join(inputRoot, 'soklet-3.5.1.jar');
  const baselinePom = join(inputRoot, 'soklet-3.5.1.pom');
  const candidateJar = join(exactCandidateRoot, 'target', 'soklet-4.0.0.jar');
  verifyFileDigest(baselineJar, contract.policy.comparison.jarSha256,
    'released Soklet 3.5.1 JAR');
  verifyFileDigest(baselinePom, contract.policy.comparison.pomSha256,
    'released Soklet 3.5.1 POM');
  if (sha256(readRegularFile(candidateJar, 'candidate Soklet 4.0.0 JAR'))
      !== candidate.candidateMainJarSha256) {
    fail('Candidate benchmark JAR does not match the frozen candidate identity.');
  }
  const javaHome = requireString(process.env.JAVA_HOME, 'JAVA_HOME');
  const javaPath = join(javaHome, 'bin', 'java');
  verifyToolchains(contract, inputRoot, javaPath);
  const hostEnvironment = captureEnvironment();
  const childEnvironment = {
    ...process.env,
    SOKLET_BENCHMARK_BASELINE_JAR: baselineJar,
    SOKLET_BENCHMARK_CANDIDATE_JAR: candidateJar,
  };
  const log = [
    'Soklet MCP release benchmark raw execution',
    `SOKLET_BENCHMARK_CONFIGURATION_SHA256=${sha256(
      Buffer.from(canonicalJson(configuration), 'utf8'))}`,
  ];
  const repetitions = [];
  for (let ordinal = 0; ordinal < contract.policy.forks; ordinal++) {
    const first = ordinal % 2 === 0 ? BASELINE : CANDIDATE;
    const artifacts = first === BASELINE
      ? [BASELINE, CANDIDATE] : [CANDIDATE, BASELINE];
    const runs = [];
    for (let runIndex = 0; runIndex < artifacts.length; runIndex++) {
      const artifact = artifacts[runIndex];
      const rawPath = join(rawRoot,
        `repetition-${ordinal}-run-${runIndex}-${artifact}.json`);
      const result = runJmh({
        artifact,
        benchmarks: JSON_BENCHMARKS,
        candidateRoot: exactCandidateRoot,
        configuration,
        environment: childEnvironment,
        forks: 1,
        jdkVersion: jdkVersionFor(contract),
        javaPath,
        outputPath: rawPath,
      });
      writeFileSync(rawPath, result.bytes);
      const normalized = normalizedJsonRun(artifact, result.summary,
        configuration);
      const drafted = draftRun(exactWorkRoot, rawPath, normalized, result.bytes);
      runs.push(drafted);
      log.push(
        `SOKLET_BENCHMARK_RUN=${ordinal}:${runIndex}:${artifact}:PASS:${normalized.rawResultSha256}`,
        `SOKLET_BENCHMARK_RAW=${drafted.rawJmhPath}:${drafted.rawJmhSha256}`,
        result.transcript,
        result.bytes.toString('utf8'),
      );
    }
    repetitions.push({ first, ordinal, runs });
  }
  const profilePath = join(rawRoot, 'profile1.json');
  const profile = runJmh({
    artifact: CANDIDATE,
    benchmarks: PROFILE_BENCHMARKS,
    candidateRoot: exactCandidateRoot,
    configuration,
    environment: childEnvironment,
    forks: contract.policy.forks,
    jdkVersion: jdkVersionFor(contract),
    javaPath,
    outputPath: profilePath,
  });
  writeFileSync(profilePath, profile.bytes);
  const profileOperations = contract.policy.profile1Baseline.operations.map(
    (operation, index) => draftRun(exactWorkRoot, profilePath,
      normalizedProfile(operation, profile.summary[index]), profile.bytes));
  log.push(
    `SOKLET_BENCHMARK_RAW=${profileOperations[0].rawJmhPath}:${profileOperations[0].rawJmhSha256}`,
    profile.transcript,
    profile.bytes.toString('utf8'),
  );
  const parseRatio = benchmarkMean(repetitions, CANDIDATE, 'jsonParseScore')
    / benchmarkMean(repetitions, BASELINE, 'jsonParseScore');
  const writeRatio = benchmarkMean(repetitions, CANDIDATE, 'jsonWriteScore')
    / benchmarkMean(repetitions, BASELINE, 'jsonWriteScore');
  const logBytes = Buffer.from(`${log.join('\n').replace(/\n*$/u, '')}\n`,
    'utf8');
  writeNewFile(join(exactWorkRoot, 'mcp-benchmarks.log'), logBytes);
  const draft = {
    approvalReference,
    benchmarkLogSha256: sha256(logBytes),
    candidate,
    comparison: {
      artifact: contract.policy.comparison.artifact,
      jarSha256: contract.policy.comparison.jarSha256,
      jsonParseScoreRatio: parseRatio,
      jsonWriteScoreRatio: writeRatio,
      pomSha256: contract.policy.comparison.pomSha256,
    },
    configuration,
    environment: hostEnvironment,
    formatVersion: 1,
    gate: GATE,
    policySha256: sha256(Buffer.from(canonicalJson(contract.policy), 'utf8')),
    producerStatus: 'AWAITING_REVIEW',
    profile1Baseline: profileOperations,
    repetitions,
    toolchainsSha256: sha256(Buffer.from(canonicalJson(contract.toolchains),
      'utf8')),
  };
  writeNewJson(join(exactWorkRoot, 'mcp-benchmarks-draft.json'), draft);
  return Object.freeze(draft);
}

function verifyDraftRaw(workRoot, rawDraft, artifact, benchmarks,
    configuration, expectedForks, expectedNormalized, expectedJdkVersion) {
  const rawPath = resolve(workRoot, rawDraft.rawJmhPath);
  if (relative(workRoot, rawPath).startsWith(`..${sep}`))
    fail('Draft raw JMH path escapes its work root.');
  const { bytes, value } = readCanonicalJson(rawPath, 'retained raw JMH result');
  if (sha256(bytes) !== rawDraft.rawJmhSha256)
    fail('Retained raw JMH result does not match the reviewed draft.');
  const summary = summarizeJmhResults(value, {
    artifact, benchmarks, configuration, expectedForks, expectedJdkVersion,
  });
  const normalized = benchmarks === JSON_BENCHMARKS
    ? normalizedJsonRun(artifact, summary, configuration)
    : normalizedProfile(expectedNormalized.operation, summary[
      PROFILE_BENCHMARKS.indexOf(expectedNormalized.rawResult.result.benchmark)]);
  if (!sameJson(normalized, expectedNormalized))
    fail('Retained raw JMH result does not reproduce the reviewed normalized result.');
  return {
    normalized,
    retainedRaw: {
      path: rawDraft.rawJmhPath,
      results: value,
      sha256: sha256(bytes),
    },
  };
}

function retainRawJmhResult(retainedByPath, retainedRaw) {
  const existing = retainedByPath.get(retainedRaw.path);
  if (existing !== undefined && !sameJson(existing, retainedRaw))
    fail(`Retained raw JMH path has conflicting content: ${retainedRaw.path}`);
  if (existing === undefined)
    retainedByPath.set(retainedRaw.path, retainedRaw);
}

export function finalizeMcpReleaseBenchmarks({
  bundleBuilder = createReleaseHarnessBundle,
  bundleOutput,
  candidateIdentityProvider = releaseHarnessCandidateIdentity,
  candidateRoot,
  contractConfigurationProvider = contractConfiguration,
  evidenceRoot,
  regressionApprovalReference,
  reviewedDraftSha256,
  signoffReference,
  workRoot,
}) {
  const exactCandidateRoot = requireAbsoluteDirectory(candidateRoot,
    'candidate root');
  const exactWorkRoot = requireAbsoluteDirectory(workRoot, 'benchmark work root');
  requireSha256(reviewedDraftSha256, 'reviewed benchmark draft SHA-256');
  requireDigestBoundSignoffReference(signoffReference, reviewedDraftSha256);
  const { bytes: draftBytes, value: draft } = readCanonicalJson(
    join(exactWorkRoot, 'mcp-benchmarks-draft.json'), 'benchmark draft');
  const actualDraftSha256 = sha256(draftBytes);
  if (actualDraftSha256 !== reviewedDraftSha256) {
    fail(
      'Benchmark draft differs from the exact immutable bytes approved by the reviewer: '
        + `expected ${reviewedDraftSha256}, found ${actualDraftSha256}.`,
    );
  }
  if (draft.formatVersion !== 1 || draft.gate !== GATE
      || draft.producerStatus !== 'AWAITING_REVIEW')
    fail('Benchmark draft has the wrong contract identity or status.');
  requireString(draft.approvalReference, 'benchmark approval reference');
  const candidate = candidateIdentityProvider({
    candidateRoot: exactCandidateRoot,
    gate: GATE,
  });
  if (!sameJson(candidate, draft.candidate))
    fail('Benchmark draft candidate differs from the finalization candidate.');
  const { contract } = contractConfigurationProvider(exactCandidateRoot);
  const configuration = configurationFor(contract);
  if (!sameJson(configuration, draft.configuration)
      || draft.policySha256
        !== sha256(Buffer.from(canonicalJson(contract.policy), 'utf8'))
      || draft.toolchainsSha256
        !== sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8')))
    fail('Benchmark draft policy/toolchain configuration drifted.');
  const retainedByPath = new Map();
  const repetitions = draft.repetitions.map((repetition, ordinal) => ({
    first: repetition.first,
    ordinal: repetition.ordinal,
    runs: repetition.runs.map((rawDraft, runIndex) => {
      const expectedArtifact = ordinal % 2 === 0
        ? [BASELINE, CANDIDATE][runIndex]
        : [CANDIDATE, BASELINE][runIndex];
      const verified = verifyDraftRaw(exactWorkRoot, rawDraft, expectedArtifact,
        JSON_BENCHMARKS, configuration, 1, rawDraft.normalized,
        jdkVersionFor(contract));
      retainRawJmhResult(retainedByPath, verified.retainedRaw);
      return verified.normalized;
    }),
  }));
  const profile1Baseline = draft.profile1Baseline.map((rawDraft, index) => {
    const verified = verifyDraftRaw(exactWorkRoot, rawDraft, CANDIDATE,
      PROFILE_BENCHMARKS,
      configuration, contract.policy.forks, rawDraft.normalized,
      jdkVersionFor(contract));
    if (verified.normalized.operation
        !== contract.policy.profile1Baseline.operations[index]
        || verified.normalized.rawResult.result.benchmark
          !== PROFILE_BENCHMARKS[index]) {
      fail(`Profile 1 operation ${index + 1} has the wrong benchmark mapping.`);
    }
    retainRawJmhResult(retainedByPath, verified.retainedRaw);
    return verified.normalized;
  });
  const derivedParseRatio = benchmarkMean(
    repetitions,
    CANDIDATE,
    'jsonParseScore',
  ) / benchmarkMean(repetitions, BASELINE, 'jsonParseScore');
  const derivedWriteRatio = benchmarkMean(
    repetitions,
    CANDIDATE,
    'jsonWriteScore',
  ) / benchmarkMean(repetitions, BASELINE, 'jsonWriteScore');
  if (!Number.isFinite(derivedParseRatio) || !Number.isFinite(derivedWriteRatio)
      || Math.abs(derivedParseRatio - draft.comparison.jsonParseScoreRatio) > 1e-12
      || Math.abs(derivedWriteRatio - draft.comparison.jsonWriteScoreRatio) > 1e-12) {
    fail('Benchmark draft comparison ratios do not derive from retained raw JMH.');
  }
  const regression = derivedParseRatio
      < contract.policy.comparison.minimumJsonParseWriteScoreRatio
    || derivedWriteRatio < contract.policy.comparison.minimumJsonParseWriteScoreRatio;
  let acceptedRegressionApprovalReference = null;
  let releaseNoteSha256 = null;
  if (regression) {
    acceptedRegressionApprovalReference =
      requireDigestBoundRegressionApprovalReference(
        regressionApprovalReference,
        reviewedDraftSha256,
      );
    if (acceptedRegressionApprovalReference === signoffReference) {
      fail('Benchmark regression approval must be separate from benchmark review sign-off.');
    }
    releaseNoteSha256 = sha256(benchmarkReleaseNoteEntry(exactCandidateRoot));
  } else if (regressionApprovalReference !== undefined
      && regressionApprovalReference !== null
      && regressionApprovalReference !== '') {
    fail('A non-regressing benchmark must not claim regression approval.');
  }
  const logBytes = readRegularFile(join(exactWorkRoot, 'mcp-benchmarks.log'),
    'benchmark log');
  if (sha256(logBytes) !== draft.benchmarkLogSha256)
    fail('Benchmark log differs from the reviewed draft.');
  const exactEvidenceRoot = createDirectory(evidenceRoot,
    'benchmark evidence root');
  const evidence = {
    benchmarkLogSha256: draft.benchmarkLogSha256,
    candidate,
    comparison: draft.comparison,
    configuration,
    environment: draft.environment,
    formatVersion: 1,
    gate: GATE,
    policySha256: draft.policySha256,
    producerStatus: 'PASS',
    profile1Baseline,
    rawJmhResults: [...retainedByPath.values()],
    repetitions,
    review: {
      approvalReference: draft.approvalReference,
      regressionApprovalReference: acceptedRegressionApprovalReference,
      regressionApproved: regression,
      releaseNoteSha256,
      reviewedDraftSha256,
      signoffReference,
    },
    reviewedDraft: draft,
    toolchainsSha256: draft.toolchainsSha256,
  };
  writeNewJson(join(exactEvidenceRoot, 'mcp-benchmarks.json'), evidence);
  writeNewFile(join(exactEvidenceRoot, 'mcp-benchmarks.log'), logBytes);
  const bundle = bundleBuilder({
    candidateRoot: exactCandidateRoot,
    evidenceRoot: exactEvidenceRoot,
    gate: GATE,
    outputPath: bundleOutput,
  });
  return Object.freeze(bundle);
}

function usage() {
  return 'Usage:\n'
    + '  node scripts/produce-release-benchmarks.mjs run '
    + '--candidate-root <absolute-path> --work-root <new-absolute-path> '
    + '--approval-reference <reference>\n'
    + '  node scripts/produce-release-benchmarks.mjs finalize '
    + '--candidate-root <absolute-path> --work-root <absolute-path> '
    + '--evidence-root <new-absolute-path> --bundle-output <new-absolute-path> '
    + '--reviewed-draft-sha256 <lowercase-sha256> '
    + '--signoff-reference <uri#sha256=reviewed-draft-sha256> '
    + '[--regression-approval-reference '
    + '<separate-owner-uri#sha256=reviewed-draft-sha256>]';
}

function parseArguments(args) {
  const mode = args.shift();
  const required = mode === 'run'
    ? new Set(['--candidate-root', '--work-root', '--approval-reference'])
    : mode === 'finalize'
      ? new Set(['--candidate-root', '--work-root', '--evidence-root',
        '--bundle-output', '--reviewed-draft-sha256', '--signoff-reference'])
      : null;
  const allowed = required === null
    ? null
    : new Set([
      ...required,
      ...(mode === 'finalize' ? ['--regression-approval-reference'] : []),
    ]);
  if (allowed === null || args.length % 2 !== 0)
    fail(usage());
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    if (!allowed.has(args[index]) || values.has(args[index])
        || args[index + 1] === undefined)
      fail(usage());
    values.set(args[index], args[index + 1]);
  }
  if ([...required].some((flag) => !values.has(flag)))
    fail(usage());
  if (mode === 'run') {
    return { mode, options: {
      approvalReference: values.get('--approval-reference'),
      candidateRoot: values.get('--candidate-root'),
      workRoot: values.get('--work-root'),
    } };
  }
  return { mode, options: {
    bundleOutput: values.get('--bundle-output'),
    candidateRoot: values.get('--candidate-root'),
    evidenceRoot: values.get('--evidence-root'),
    regressionApprovalReference: values.get('--regression-approval-reference'),
    reviewedDraftSha256: values.get('--reviewed-draft-sha256'),
    signoffReference: values.get('--signoff-reference'),
    workRoot: values.get('--work-root'),
  } };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  try {
    const { mode, options } = parseArguments(process.argv.slice(2));
    if (mode === 'run') {
      const draft = runMcpReleaseBenchmarks(options);
      const draftSha256 = sha256(Buffer.from(canonicalJson(draft), 'utf8'));
      console.log(
        'MCP release benchmark execution PASS; review required '
          + `candidate=${draft.candidate.candidateCommit} draftSha256=${draftSha256}`,
      );
    } else {
      const bundle = finalizeMcpReleaseBenchmarks(options);
      console.log(`MCP release benchmark finalization PASS contentSha256=${bundle.contentSha256}`);
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
