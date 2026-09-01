#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ReleaseHarnessEvidenceImportError,
  canonicalJson,
  createReleaseHarnessBundle,
  importReleaseHarnessEvidence,
  verifyImportedBundleReceipt,
  verifyImportedReceipt,
  verifyReleaseHarnessConfiguration,
  verifyReleaseHarnessEvidenceDirectory,
  verifyReleaseHarnessRoleDescriptors,
} from './import-release-harness-evidence.mjs';

const NOW_TEXT = '2026-08-24T12:00:00Z';
const NOW = Date.parse(NOW_TEXT);
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
let fixtureOrdinal = 0;
let assertionCount = 0;

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function digest(label) {
  return sha256(Buffer.from(label, 'utf8'));
}

function clone(value) {
  return structuredClone(value);
}

function fileRole(expected, value) {
  const bytes = Buffer.isBuffer(value)
    ? value
    : Buffer.from(typeof value === 'string' ? value : canonicalJson(value), 'utf8');
  return {
    bytesBase64: bytes.toString('base64'),
    kind: expected.kind,
    mediaType: expected.mediaType,
    name: expected.name,
    ordinal: expected.ordinal,
    required: expected.required,
    sha256: sha256(bytes),
  };
}

function directoryRole(expected, files) {
  const entries = Object.entries(files)
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
    .map(([path, value]) => {
      const bytes = Buffer.isBuffer(value)
        ? value
        : Buffer.from(typeof value === 'string' ? value : canonicalJson(value), 'utf8');
      return {
        bytesBase64: bytes.toString('base64'),
        path,
        sha256: sha256(bytes),
      };
    });
  return {
    entries,
    kind: expected.kind,
    mediaType: expected.mediaType,
    name: expected.name,
    ordinal: expected.ordinal,
    required: expected.required,
    sha256: sha256(Buffer.from(canonicalJson(entries), 'utf8')),
  };
}

function commonEvidence(contract, candidate) {
  return {
    candidate: clone(candidate),
    formatVersion: 1,
    gate: contract.id,
    policySha256: sha256(Buffer.from(canonicalJson(contract.policy), 'utf8')),
    producerStatus: 'PASS',
    toolchainsSha256: sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8')),
  };
}

function historyTimes(now = NOW) {
  const alignedNow = Math.floor(now / 1000) * 1000;
  return Array.from({ length: 7 }, (_, index) =>
    new Date(alignedNow - (7 - index) * 86_400_000)
      .toISOString().replace('.000Z', 'Z'));
}

function fuzzEvidence(contract, candidate, now = NOW) {
  return {
    ...commonEvidence(contract, candidate),
    runs: historyTimes(now).map((completedAt) => ({
      completedAt,
      corpusHashes: contract.policy.targets.map((target) =>
        digest(`${completedAt}:${target.id}:corpus`)),
      id: completedAt.slice(0, 10),
      outcome: 'PASS',
      targets: contract.policy.targets.map((target) => ({
        durationSeconds: contract.policy.perTargetDurationSeconds,
        id: target.id,
        ordinal: target.ordinal,
        outcome: 'PASS',
      })),
    })),
  };
}

function soakEvidence(contract, candidate, now = NOW) {
  return {
    ...commonEvidence(contract, candidate),
    runs: historyTimes(now).map((completedAt) => ({
      completedAt,
      id: completedAt.slice(0, 10),
      outcome: 'PASS',
      profile: contract.policy.profile,
      profileSha256: contract.policy.profileSha256,
      scenarios: contract.policy.scenarios.map((scenario) => {
        const report = {
          candidateCommit: candidate.candidateCommit,
          completedAt,
          outcome: 'PASS',
          profileSha256: contract.policy.profileSha256,
          resourceBaseline: {
            fileDescriptors: 8,
            heapBytes: 16_777_216,
            liveThreads: 8,
          },
          resourceDeltas: {
            fileDescriptors: 0,
            heapBytes: 0,
            liveThreads: 0,
          },
          scenario: scenario.id,
          surefire: { errors: 0, failures: 0, skipped: 0, tests: 1 },
          thresholdsPassed: true,
        };
        return {
          id: scenario.id,
          ordinal: scenario.ordinal,
          outcome: 'PASS',
          report,
          reportSha256: sha256(Buffer.from(canonicalJson(report), 'utf8')),
          resourceThresholdsPassed: true,
        };
      }),
      surefire: clone(contract.policy.surefire),
    })),
  };
}

function operationalEvidence(contract, candidate) {
  const start = Date.parse('2026-08-24T00:00:00Z');
  const sampleSpanSeconds = contract.policy.durationSeconds
    + contract.policy.postIntervalReserveSeconds;
  const samples = Array.from(
    { length: sampleSpanSeconds / contract.policy.cadenceSeconds + 1 },
    (_, index) => ({
      at: new Date(start + index * contract.policy.cadenceSeconds * 1000)
        .toISOString().replace('.000Z', 'Z'),
      droppedLogRecords: 0,
      frameworkMetricCardinality: 0,
      rejectedMetricDeliveries: 0,
      resources: {
        http: { fileDescriptors: 8, heapBytes: 16_777_216, liveThreads: 8 },
        mcpAndRealtime: { fileDescriptors: 12, heapBytes: 33_554_432, liveThreads: 12 },
      },
      unregisteredMetricDimensions: 0,
    }),
  );
  return {
    ...commonEvidence(contract, candidate),
    cadenceSeconds: contract.policy.cadenceSeconds,
    drainSeconds: 20,
    durationSeconds: contract.policy.durationSeconds,
    finalResourceDeltas: {
      http: { fileDescriptors: 0, heapBytes: 0, liveThreads: 0 },
      mcpAndRealtime: { fileDescriptors: 0, heapBytes: 0, liveThreads: 0 },
    },
    loadShape: clone(contract.policy.loadShape),
    maximumSampleGapSeconds: contract.policy.maximumSampleGapSeconds,
    outcomes: [],
    postIntervalReserveSeconds: contract.policy.postIntervalReserveSeconds,
    resourceBaselines: {
      http: { fileDescriptors: 8, heapBytes: 16_777_216, liveThreads: 8 },
      mcpAndRealtime: { fileDescriptors: 12, heapBytes: 33_554_432, liveThreads: 12 },
    },
    samples,
    sensitiveCanaries: 0,
    terminalFrameworkCardinality: 0,
  };
}

function benchmarkFixture(contract, candidate) {
  const configuration = {
    candidateJvm: clone(contract.policy.candidateJvm),
    forks: contract.policy.forks,
    measurement: clone(contract.policy.measurement),
    threads: contract.policy.threads,
    warmup: clone(contract.policy.warmup),
  };
  const jsonBenchmarks = [
    'com.soklet.McpReleaseJsonJmhBenchmark.jsonParse',
    'com.soklet.McpReleaseJsonJmhBenchmark.jsonWrite',
  ];
  const profileBenchmarks = [
    'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaCompile',
    'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaEvaluate',
  ];
  const entry = (benchmark, artifact, forks, score) => ({
    benchmark,
    forks,
    jdkVersion: '17.0.20',
    jmhVersion: '1.37',
    jvmArgs: clone(configuration.candidateJvm),
    measurementIterations: configuration.measurement.iterations,
    measurementTime: '1 s',
    mode: 'thrpt',
    params: { artifact },
    primaryMetric: {
      rawData: Array.from({ length: forks }, () =>
        Array.from({ length: configuration.measurement.iterations }, () => score)),
      score,
      scoreError: 0,
      scoreUnit: 'ops/s',
    },
    threads: configuration.threads,
    warmupIterations: configuration.warmup.iterations,
    warmupTime: '1 s',
  });
  const normalizedJsonRun = (artifact, results) => {
    const rawResult = {
      artifact,
      configuration: clone(configuration),
      jsonParseScore: results[0].primaryMetric.score,
      jsonWriteScore: results[1].primaryMetric.score,
    };
    return {
      artifact,
      outcome: 'PASS',
      rawResult,
      rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
    };
  };
  const normalizedProfile = (operation, result) => {
    const rawResult = {
      complete: true,
      errors: [],
      operation,
      result: {
        benchmark: result.benchmark,
        rawJmhResultSha256: sha256(Buffer.from(canonicalJson(result), 'utf8')),
        score: result.primaryMetric.score,
        scoreError: result.primaryMetric.scoreError,
        scoreUnit: result.primaryMetric.scoreUnit,
      },
    };
    return {
      errors: 0,
      operation,
      rawResult,
      rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
    };
  };
  const rawJmhResults = [];
  const logParts = [
    'Soklet MCP release benchmark raw execution',
    `SOKLET_BENCHMARK_CONFIGURATION_SHA256=${sha256(Buffer.from(canonicalJson(configuration), 'utf8'))}`,
  ];
  const draftRepetitions = Array.from(
    { length: contract.policy.forks },
    (_, ordinal) => {
      const first = ordinal % 2 === 0 ? '3.5.1' : '4.0.0';
      const artifacts = first === '3.5.1'
        ? ['3.5.1', '4.0.0']
        : ['4.0.0', '3.5.1'];
      return {
        first,
        ordinal,
        runs: artifacts.map((artifact, runIndex) => {
          const results = jsonBenchmarks.map((benchmark) =>
            entry(benchmark, artifact, 1, 100));
          const path = `raw/repetition-${ordinal}-run-${runIndex}-${artifact}.json`;
          const rawSha256 = sha256(Buffer.from(canonicalJson(results), 'utf8'));
          const normalized = normalizedJsonRun(artifact, results);
          rawJmhResults.push({ path, results, sha256: rawSha256 });
          logParts.push(
            `SOKLET_BENCHMARK_RUN=${ordinal}:${runIndex}:${artifact}:PASS:${normalized.rawResultSha256}`,
            `SOKLET_BENCHMARK_RAW=${path}:${rawSha256}`,
            canonicalJson(results),
          );
          return {
            normalized,
            rawJmhPath: path,
            rawJmhSha256: rawSha256,
          };
        }),
      };
    },
  );
  const repetitions = draftRepetitions.map((repetition) => ({
    first: repetition.first,
    ordinal: repetition.ordinal,
    runs: repetition.runs.map(({ normalized }) => clone(normalized)),
  }));
  const profileResults = profileBenchmarks.map((benchmark) =>
    entry(benchmark, '4.0.0', contract.policy.forks, 50));
  const profilePath = 'raw/profile1.json';
  const profileSha256 = sha256(Buffer.from(canonicalJson(profileResults), 'utf8'));
  rawJmhResults.push({ path: profilePath, results: profileResults, sha256: profileSha256 });
  logParts.push(
    `SOKLET_BENCHMARK_RAW=${profilePath}:${profileSha256}`,
    canonicalJson(profileResults),
  );
  const draftProfile = contract.policy.profile1Baseline.operations.map(
    (operation, index) => ({
      normalized: normalizedProfile(operation, profileResults[index]),
      rawJmhPath: profilePath,
      rawJmhSha256: profileSha256,
    }),
  );
  const profile1Baseline = draftProfile.map(({ normalized }) => clone(normalized));
  const log = `${logParts.join('\n').replace(/\n*$/u, '')}\n`;
  const comparison = {
    artifact: contract.policy.comparison.artifact,
    jarSha256: contract.policy.comparison.jarSha256,
    jsonParseScoreRatio: 1,
    jsonWriteScoreRatio: 1,
    pomSha256: contract.policy.comparison.pomSha256,
  };
  const environment = {
    architecture: 'x86_64',
    cpuModel: 'self-test-cpu',
    governor: 'performance',
    image: 'ubuntu-24.04@20260817.1.0',
    kernel: 'self-test-kernel',
    microcode: 'self-test-microcode',
    sameBoot: true,
    samePhysicalRunner: true,
    turboState: 'disabled',
  };
  const reviewedDraft = {
    approvalReference: 'self-test:benchmark-review',
    benchmarkLogSha256: sha256(Buffer.from(log, 'utf8')),
    candidate: clone(candidate),
    comparison: clone(comparison),
    configuration: clone(configuration),
    environment: clone(environment),
    formatVersion: 1,
    gate: contract.id,
    policySha256: sha256(Buffer.from(canonicalJson(contract.policy), 'utf8')),
    producerStatus: 'AWAITING_REVIEW',
    profile1Baseline: draftProfile,
    repetitions: draftRepetitions,
    toolchainsSha256: sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8')),
  };
  const reviewedDraftSha256 = sha256(
    Buffer.from(canonicalJson(reviewedDraft), 'utf8'),
  );
  const evidence = {
    ...commonEvidence(contract, candidate),
    benchmarkLogSha256: sha256(Buffer.from(log, 'utf8')),
    comparison,
    configuration,
    environment,
    profile1Baseline,
    rawJmhResults,
    repetitions,
    review: {
      approvalReference: 'self-test:benchmark-review',
      regressionApprovalReference: null,
      regressionApproved: false,
      releaseNoteSha256: null,
      reviewedDraftSha256,
      signoffReference:
        `self-test:benchmark-signoff#sha256=${reviewedDraftSha256}`,
    },
    reviewedDraft,
  };
  return { evidence, log };
}

function scanRoles(contract, candidate) {
  const emptySarif = (tool) => {
    const run = { results: [], tool: { driver: { name: tool } } };
    if (tool === 'CodeQL') {
      run.invocations = [{
        executionSuccessful: true,
        exitCode: 0,
        toolConfigurationNotifications: [],
        toolExecutionNotifications: [],
      }];
      run.versionControlProvenance = [{
        repositoryUri: 'https://github.com/example/soklet',
        revisionId: candidate.candidateCommit,
      }];
    }
    return canonicalJson({ runs: [run], version: '2.1.0' });
  };
  const reportFiles = {
    '00-codeql-java.sarif': emptySarif('CodeQL'),
    '01-spotbugs.xml': '<?xml version="1.0" encoding="UTF-8"?>\n<BugCollection></BugCollection>\n',
    '02-gitleaks.sarif': emptySarif('gitleaks'),
    '03-gitleaks.json': canonicalJson([]),
    '04-runtime-dependency-surface.json': canonicalJson({
      externalRuntimeDependencyCount: 0,
      formatVersion: 1,
    }),
    '05-toolchain-provenance.json': canonicalJson({
      candidate: clone(candidate),
      codeql: clone(contract.policy.codeql),
      formatVersion: 1,
      gitleaks: clone(contract.policy.gitleaks),
      producerWorkflowSha256: candidate.producerWorkflowSha256,
      spotbugs: clone(contract.policy.spotbugs),
      toolchains: clone(contract.toolchains),
    }),
  };
  const reportsRole = directoryRole(contract.roles[1], reportFiles);
  const digestByPath = new Map(reportsRole.entries.map((entry) => [entry.path, entry.sha256]));
  const summary = {
    ...commonEvidence(contract, candidate),
    allowlist: [],
    findings: [],
    reports: contract.policy.reports.map((report) => ({
      name: report.name,
      ordinal: report.ordinal,
      outcome: 'PASS',
      sha256: digestByPath.get(report.name),
    })),
    runtimeDependencySurface: { externalRuntimeDependencyCount: 0 },
  };
  return [fileRole(contract.roles[0], summary), reportsRole];
}

function approvedScanFixture({ severity } = {}) {
  const rawFinding = {
    Commit: '1'.repeat(40),
    EndColumn: 30,
    EndLine: 12,
    File: 'src/test/resources/example.properties',
    Match: 'REDACTED',
    RuleID: 'generic-api-key',
    Secret: 'REDACTED',
    ...(severity === undefined ? {} : { Severity: severity }),
    StartColumn: 7,
    StartLine: 12,
  };
  const identity = {
    commit: rawFinding.Commit,
    endColumn: rawFinding.EndColumn,
    endLine: rawFinding.EndLine,
    path: rawFinding.File,
    ruleId: rawFinding.RuleID,
    startColumn: rawFinding.StartColumn,
    startLine: rawFinding.StartLine,
  };
  const fingerprint = sha256(Buffer.from(canonicalJson(identity), 'utf8'));
  const approval = {
    approvedAt: '2026-08-23T00:00:00Z',
    approvalReference: 'SEC-1234',
    commit: rawFinding.Commit,
    expiresAt: '2026-09-15T00:00:00Z',
    fingerprint,
    owner: 'security@example.test',
    path: rawFinding.File,
    rationale: 'Synthetic false positive used to test exact exception validation.',
    ruleId: rawFinding.RuleID,
    scanner: 'gitleaks',
  };
  const finding = {
    accepted: true,
    commit: rawFinding.Commit,
    fingerprint,
    path: rawFinding.File,
    ruleId: rawFinding.RuleID,
    scanner: 'gitleaks',
    severity: severity ?? 'UNSPECIFIED',
  };
  const sarif = {
    runs: [{
      results: [{
        locations: [{
          physicalLocation: {
            artifactLocation: { uri: rawFinding.File },
            region: {
              endColumn: rawFinding.EndColumn,
              endLine: rawFinding.EndLine,
              startColumn: rawFinding.StartColumn,
              startLine: rawFinding.StartLine,
            },
          },
        }],
        partialFingerprints: { commitSha: rawFinding.Commit },
        ruleId: rawFinding.RuleID,
      }],
      tool: { driver: { name: 'gitleaks' } },
    }],
    version: '2.1.0',
  };
  return { approval, finding, rawFinding, sarif };
}

function approvedCodeqlScanFixture({
  candidateCommit = 'a'.repeat(40),
  securitySeverity = '6.5',
} = {}) {
  const ruleId = 'java/example-security-rule';
  const path = 'src/main/java/com/soklet/Example.java';
  const identity = {
    commit: candidateCommit,
    endColumn: 18,
    endLine: 7,
    path,
    ruleId,
    startColumn: 5,
    startLine: 7,
  };
  const fingerprint = sha256(Buffer.from(canonicalJson(identity), 'utf8'));
  const severityScore = Number(securitySeverity);
  const severity = severityScore >= 9 ? 'CRITICAL'
    : severityScore >= 7 ? 'HIGH' : severityScore >= 4 ? 'MEDIUM' : 'LOW';
  const approval = {
    approvedAt: '2026-08-23T00:00:00Z',
    approvalReference: 'SEC-5678',
    commit: candidateCommit,
    expiresAt: '2026-09-15T00:00:00Z',
    fingerprint,
    owner: 'security@example.test',
    path,
    rationale: 'Synthetic CodeQL false positive used to test exact exception validation.',
    ruleId,
    scanner: 'codeql',
  };
  const finding = {
    accepted: true,
    commit: candidateCommit,
    fingerprint,
    path,
    ruleId,
    scanner: 'codeql',
    severity,
  };
  const sarif = {
    runs: [{
      invocations: [{
        executionSuccessful: true,
        exitCode: 0,
        toolConfigurationNotifications: [],
        toolExecutionNotifications: [],
      }],
      results: [{
        locations: [{
          physicalLocation: {
            artifactLocation: { uri: path, uriBaseId: '%SRCROOT%' },
            region: {
              endColumn: identity.endColumn,
              endLine: identity.endLine,
              startColumn: identity.startColumn,
              startLine: identity.startLine,
            },
          },
        }],
        ruleId,
      }],
      tool: {
        driver: {
          name: 'CodeQL',
          rules: [{ id: ruleId, properties: { 'security-severity': securitySeverity } }],
        },
      },
      versionControlProvenance: [{
        repositoryUri: 'https://github.com/example/soklet',
        revisionId: candidateCommit,
      }],
    }],
    version: '2.1.0',
  };
  return { approval, finding, sarif };
}

function applyApprovedScanFixture(bundle, fixture) {
  replaceDirectoryEntry(bundle, 'scan-reports', '02-gitleaks.sarif', fixture.sarif);
  replaceDirectoryEntry(bundle, 'scan-reports', '03-gitleaks.json', [fixture.rawFinding]);
  mutateJsonRole(bundle, 'scan-summary', (evidence) => {
    evidence.allowlist = [fixture.approval];
    evidence.findings = [fixture.finding];
  });
}

function applyApprovedCodeqlScanFixture(bundle, fixture) {
  replaceDirectoryEntry(bundle, 'scan-reports', '00-codeql-java.sarif', fixture.sarif);
  mutateJsonRole(bundle, 'scan-summary', (evidence) => {
    evidence.allowlist = [fixture.approval];
    evidence.findings = [fixture.finding];
  });
}

function writeScanApprovalRegistry(root, exceptions) {
  const releaseRoot = join(root, 'release');
  mkdirSync(releaseRoot, { recursive: true });
  writeFileSync(
    join(releaseRoot, 'release-scan-exceptions.json'),
    canonicalJson({ exceptions, formatVersion: 1 }),
  );
}

function rolesFor(contract, candidate, now = NOW) {
  switch (contract.id) {
    case 'fuzz-nightly-history':
      return [fileRole(contract.roles[0], fuzzEvidence(contract, candidate, now))];
    case 'mcp-benchmarks':
      {
        const { evidence, log } = benchmarkFixture(contract, candidate);
        return [
          fileRole(contract.roles[0], evidence),
          fileRole(contract.roles[1], log),
        ];
      }
    case 'operational-history':
      return [fileRole(contract.roles[0], operationalEvidence(contract, candidate))];
    case 'release-scans':
      return scanRoles(contract, candidate);
    case 'soak-nightly-history':
      return [fileRole(contract.roles[0], soakEvidence(contract, candidate, now))];
    default:
      throw new Error(`No self-test fixture for ${contract.id}`);
  }
}

function wrapContent(content) {
  return {
    content,
    contentSha256: sha256(Buffer.from(canonicalJson(content), 'utf8')),
    formatVersion: 1,
  };
}

function validBundle(contract, candidate, now = NOW) {
  return wrapContent({
    candidate: clone(candidate),
    contractVersion: contract.contractVersion,
    evidenceContract: contract.evidenceContract,
    gate: contract.id,
    policy: clone(contract.policy),
    producer: contract.producer,
    producerStatus: 'PASS',
    roles: rolesFor(contract, candidate, now),
    toolchains: clone(contract.toolchains),
  });
}

function mutateJsonRole(bundle, name, mutator) {
  const role = bundle.content.roles.find((candidateRole) => candidateRole.name === name);
  assert.ok(role, `fixture role ${name} exists`);
  const evidence = JSON.parse(Buffer.from(role.bytesBase64, 'base64').toString('utf8'));
  mutator(evidence);
  const bytes = Buffer.from(canonicalJson(evidence), 'utf8');
  role.bytesBase64 = bytes.toString('base64');
  role.sha256 = sha256(bytes);
}

function refreshDirectoryDigest(role) {
  role.sha256 = sha256(Buffer.from(canonicalJson(role.entries), 'utf8'));
}

function replaceDirectoryEntry(bundle, roleName, path, value) {
  const role = bundle.content.roles.find((candidateRole) => candidateRole.name === roleName);
  assert.ok(role, `fixture directory role ${roleName} exists`);
  const entry = role.entries.find((candidateEntry) => candidateEntry.path === path);
  assert.ok(entry, `fixture directory entry ${path} exists`);
  const bytes = Buffer.from(typeof value === 'string' ? value : canonicalJson(value), 'utf8');
  entry.bytesBase64 = bytes.toString('base64');
  entry.sha256 = sha256(bytes);
  refreshDirectoryDigest(role);
  if (roleName === 'scan-reports') {
    mutateJsonRole(bundle, 'scan-summary', (summary) => {
      summary.reports.find((report) => report.name === path).sha256 = entry.sha256;
    });
  }
}

function replaceFileRole(bundle, roleName, value) {
  const role = bundle.content.roles.find((candidateRole) => candidateRole.name === roleName);
  assert.ok(role, `fixture file role ${roleName} exists`);
  const bytes = Buffer.from(typeof value === 'string' ? value : canonicalJson(value), 'utf8');
  role.bytesBase64 = bytes.toString('base64');
  role.sha256 = sha256(bytes);
}

function mutateBenchmarkRoles(bundle, mutator) {
  const evidenceRole = bundle.content.roles.find(({ name }) =>
    name === 'benchmark-results');
  const logRole = bundle.content.roles.find(({ name }) => name === 'benchmark-log');
  assert.ok(evidenceRole && logRole, 'benchmark fixture roles exist');
  const evidence = JSON.parse(
    Buffer.from(evidenceRole.bytesBase64, 'base64').toString('utf8'),
  );
  const log = Buffer.from(logRole.bytesBase64, 'base64').toString('utf8');
  const updatedLog = mutator(evidence, log) ?? log;
  replaceFileRole(bundle, 'benchmark-results', evidence);
  replaceFileRole(bundle, 'benchmark-log', updatedLog);
}

function refreshBenchmarkReview(evidence) {
  const reviewedDraftSha256 = sha256(
    Buffer.from(canonicalJson(evidence.reviewedDraft), 'utf8'),
  );
  evidence.review.reviewedDraftSha256 = reviewedDraftSha256;
  evidence.review.signoffReference =
    `self-test:benchmark-signoff#sha256=${reviewedDraftSha256}`;
}

function writeFixture(root, prefix, value, canonical = true) {
  const path = join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${prefix}.json`);
  writeFileSync(path, canonical ? canonicalJson(value) : JSON.stringify(value), 'utf8');
  return path;
}

function materializeBundleRoles(root, prefix, contract, bundle) {
  const evidenceRoot = join(
    root,
    `${String(fixtureOrdinal++).padStart(3, '0')}-${prefix}`,
  );
  mkdirSync(evidenceRoot);
  bundle.content.roles.forEach((role, index) => {
    const expected = contract.roles[index];
    const rolePath = join(evidenceRoot, expected.path);
    if (expected.kind === 'file') {
      mkdirSync(dirname(rolePath), { recursive: true });
      writeFileSync(rolePath, Buffer.from(role.bytesBase64, 'base64'));
      return;
    }
    mkdirSync(rolePath, { recursive: true });
    for (const entry of role.entries) {
      const entryPath = join(rolePath, entry.path);
      mkdirSync(dirname(entryPath), { recursive: true });
      writeFileSync(entryPath, Buffer.from(entry.bytesBase64, 'base64'));
    }
  });
  return realpathSync(evidenceRoot);
}

function verifierInvocation(gate) {
  if (gate === 'mcp-benchmarks')
    return [join(SCRIPT_DIRECTORY, 'verify-release-benchmarks.mjs')];
  if (gate === 'release-scans')
    return [join(SCRIPT_DIRECTORY, 'verify-release-scans.mjs')];
  const modeByGate = {
    'fuzz-nightly-history': 'fuzz-nightly',
    'operational-history': 'operational',
    'soak-nightly-history': 'soak-nightly',
  };
  return [join(SCRIPT_DIRECTORY, 'verify-release-history.mjs'), modeByGate[gate]];
}

function runVerifier(gate, evidenceRoot, extraArguments = []) {
  const [scriptPath, ...argumentsForScript] = verifierInvocation(gate);
  return spawnSync(process.execPath, [scriptPath, ...argumentsForScript, ...extraArguments], {
    cwd: evidenceRoot,
    encoding: 'utf8',
    maxBuffer: 4 * 1024 * 1024,
  });
}

function expectImportFailure({
  candidate,
  configuration,
  gate,
  label,
  mutate,
  root,
}) {
  const contract = configuration.contracts.get(gate);
  const bundle = validBundle(contract, candidate);
  mutate(bundle);
  if (label !== 'changed-bundle-bytes')
    Object.assign(bundle, wrapContent(bundle.content));
  const bundlePath = writeFixture(root, label, bundle);
  const outputPath = join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${label}-receipt.json`);
  assert.throws(
    () => importReleaseHarnessEvidence({
      bundlePath,
      candidateIdentityProvider: () => clone(candidate),
      candidateRoot: root,
      gate,
      now: NOW,
      outputPath,
      registryPath: configuration.registryPath,
    }),
    ReleaseHarnessEvidenceImportError,
    label,
  );
  assertionCount++;
}

function expectBundleCreationFailure({
  candidate,
  configuration,
  gate,
  label,
  mutate,
  root,
}) {
  const contract = configuration.contracts.get(gate);
  const bundle = validBundle(contract, candidate);
  mutate(bundle);
  Object.assign(bundle, wrapContent(bundle.content));
  const evidenceRoot = materializeBundleRoles(
    root,
    `${label}-evidence`,
    contract,
    bundle,
  );
  assert.throws(
    () => createReleaseHarnessBundle({
      candidateIdentityProvider: () => clone(candidate),
      candidateRoot: root,
      evidenceRoot,
      gate,
      now: NOW,
      outputPath: join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${label}.json`),
      registryPath: configuration.registryPath,
    }),
    ReleaseHarnessEvidenceImportError,
    label,
  );
  assertionCount++;
}

function run() {
  const configuration = verifyReleaseHarnessConfiguration();
  assert.deepEqual([...configuration.contracts.keys()], [
    'fuzz-nightly-history',
    'mcp-benchmarks',
    'operational-history',
    'release-scans',
    'soak-nightly-history',
  ]);
  assertionCount++;

  const candidate = {
    candidateCommit: 'a'.repeat(40),
    candidateMainJarSha256: digest('candidate-main-jar'),
    candidatePomSha256: digest('candidate-pom'),
    candidateRegistrySha256: configuration.registrySha256,
    candidateTree: 'b'.repeat(40),
    producerWorkflowSha256: digest('producer-workflow'),
  };
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-release-harness-self-test-')));
  try {
    writeScanApprovalRegistry(root, []);
    writeFileSync(
      join(root, 'CHANGELOG.md'),
      '# Changelog\n\n## 4.0.0\n\n- Document the accepted JSON benchmark regression.\n\n## 3.5.1\n\n- Baseline fixture.\n',
      'utf8',
    );
    for (const [label, mutateRegistry] of [
      ['registry-policy-drift', (registry) => {
        registry.contracts[0].policy.perTargetDurationSeconds = 1;
      }],
      ['registry-producer-drift', (registry) => {
        registry.contracts[0].producer = '.github/workflows/attacker.yml#fuzz';
      }],
      ['registry-toolchain-drift', (registry) => {
        registry.contracts[0].toolchains[0].digest = `sha256:${'0'.repeat(64)}`;
      }],
    ]) {
      const registry = clone(configuration.registry);
      mutateRegistry(registry);
      const path = writeFixture(root, label, registry);
      assert.throws(
        () => verifyReleaseHarnessConfiguration(path),
        /reviewed U7 approval/,
        label,
      );
      assertionCount++;
    }

    const verificationNow = Date.now();
    const validPathFixtures = new Map();
    let validReceipt;
    for (const [gate, contract] of configuration.contracts) {
      const bundle = validBundle(contract, candidate, verificationNow);
      const bundlePath = writeFixture(root, `${gate}-valid`, bundle);
      const outputPath = join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${gate}-receipt.json`);
      const receipt = importReleaseHarnessEvidence({
        bundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        gate,
        now: verificationNow,
        outputPath,
        registryPath: configuration.registryPath,
      });
      assert.deepEqual(verifyImportedReceipt(outputPath), receipt);
      assert.deepEqual(verifyImportedBundleReceipt({
        bundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        now: verificationNow,
        receiptPath: outputPath,
        registryPath: configuration.registryPath,
      }), receipt);
      assert.equal(receipt.gate, gate);
      assert.equal(receipt.candidateBindings.candidateCommit, candidate.candidateCommit);
      const evidenceRoot = materializeBundleRoles(root, `${gate}-roles`, contract, bundle);
      const verified = verifyReleaseHarnessEvidenceDirectory({
        evidenceRoot,
        expectedImportedReceipt: receipt,
        gate,
        now: verificationNow,
        registryPath: configuration.registryPath,
      });
      assert.deepEqual(verified.roles, receipt.roles);
      assert.equal(verified.candidate.candidateCommit, candidate.candidateCommit);
      const builtBundlePath = join(
        root,
        `${String(fixtureOrdinal++).padStart(3, '0')}-${gate}-built-bundle.json`,
      );
      const builtBundle = createReleaseHarnessBundle({
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        evidenceRoot,
        gate,
        now: verificationNow,
        outputPath: builtBundlePath,
        registryPath: configuration.registryPath,
      });
      assert.deepEqual(builtBundle, bundle);
      assert.deepEqual(JSON.parse(readFileSync(builtBundlePath, 'utf8')), bundle);
      assert.deepEqual(verifyImportedBundleReceipt({
        bundlePath: builtBundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        now: verificationNow,
        receiptPath: outputPath,
        registryPath: configuration.registryPath,
      }), receipt);
      const cli = runVerifier(gate, evidenceRoot);
      assert.equal(cli.status, 0, cli.stderr || cli.stdout);
      assert.match(cli.stdout, /verification PASS/);
      validPathFixtures.set(gate, {
        bundle,
        bundlePath,
        builtBundlePath,
        evidenceRoot,
        outputPath,
        receipt,
        verified,
      });
      validReceipt = receipt;
      assertionCount += 11;
    }

    const approvedScanContract = configuration.contracts.get('release-scans');
    const approvedScan = approvedScanFixture();
    const approvedScanBundle = validBundle(approvedScanContract, candidate, NOW);
    applyApprovedScanFixture(approvedScanBundle, approvedScan);
    Object.assign(approvedScanBundle, wrapContent(approvedScanBundle.content));
    writeScanApprovalRegistry(root, [approvedScan.approval]);
    const approvedScanBundlePath = writeFixture(
      root,
      'approved-release-scan',
      approvedScanBundle,
    );
    const approvedScanReceiptPath = join(
      root,
      `${String(fixtureOrdinal++).padStart(3, '0')}-approved-release-scan-receipt.json`,
    );
    const approvedScanReceipt = importReleaseHarnessEvidence({
      bundlePath: approvedScanBundlePath,
      candidateIdentityProvider: () => clone(candidate),
      candidateRoot: root,
      gate: 'release-scans',
      now: NOW,
      outputPath: approvedScanReceiptPath,
      registryPath: configuration.registryPath,
    });
    assert.equal(approvedScanReceipt.gate, 'release-scans');
    assert.deepEqual(
      verifyImportedBundleReceipt({
        bundlePath: approvedScanBundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        now: NOW,
        receiptPath: approvedScanReceiptPath,
        registryPath: configuration.registryPath,
      }),
      approvedScanReceipt,
    );
    const approvedScanEvidenceRoot = materializeBundleRoles(
      root,
      'approved-release-scan-roles',
      approvedScanContract,
      approvedScanBundle,
    );
    assert.equal(verifyReleaseHarnessEvidenceDirectory({
      evidenceRoot: approvedScanEvidenceRoot,
      gate: 'release-scans',
      now: NOW,
      registryPath: configuration.registryPath,
    }).candidate.candidateCommit, candidate.candidateCommit);
    writeScanApprovalRegistry(root, []);
    assertionCount += 3;

    const approvedCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: candidate.candidateCommit,
    });
    const approvedCodeqlBundle = validBundle(approvedScanContract, candidate, NOW);
    applyApprovedCodeqlScanFixture(approvedCodeqlBundle, approvedCodeqlScan);
    Object.assign(approvedCodeqlBundle, wrapContent(approvedCodeqlBundle.content));
    writeScanApprovalRegistry(root, [approvedCodeqlScan.approval]);
    const approvedCodeqlBundlePath = writeFixture(
      root,
      'approved-codeql-release-scan',
      approvedCodeqlBundle,
    );
    const approvedCodeqlReceiptPath = join(
      root,
      `${String(fixtureOrdinal++).padStart(3, '0')}-approved-codeql-release-scan-receipt.json`,
    );
    const approvedCodeqlReceipt = importReleaseHarnessEvidence({
      bundlePath: approvedCodeqlBundlePath,
      candidateIdentityProvider: () => clone(candidate),
      candidateRoot: root,
      gate: 'release-scans',
      now: NOW,
      outputPath: approvedCodeqlReceiptPath,
      registryPath: configuration.registryPath,
    });
    assert.equal(approvedCodeqlReceipt.gate, 'release-scans');
    assert.deepEqual(
      verifyImportedBundleReceipt({
        bundlePath: approvedCodeqlBundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        now: NOW,
        receiptPath: approvedCodeqlReceiptPath,
        registryPath: configuration.registryPath,
      }),
      approvedCodeqlReceipt,
    );
    writeScanApprovalRegistry(root, []);
    assertionCount += 2;

    const fuzzContract = configuration.contracts.get('fuzz-nightly-history');
    const missingHistoryRoot = materializeBundleRoles(
      root,
      'missing-history-role',
      fuzzContract,
      validPathFixtures.get('fuzz-nightly-history').bundle,
    );
    rmSync(join(missingHistoryRoot, fuzzContract.roles[0].path));
    assert.throws(
      () => verifyReleaseHarnessEvidenceDirectory({
        evidenceRoot: missingHistoryRoot,
        gate: fuzzContract.id,
        now: verificationNow,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'missing unpacked history role',
    );
    assert.equal(runVerifier(fuzzContract.id, missingHistoryRoot).status, 1);
    assert.throws(
      () => createReleaseHarnessBundle({
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        evidenceRoot: missingHistoryRoot,
        gate: fuzzContract.id,
        now: verificationNow,
        outputPath: join(root, 'missing-role-bundle.json'),
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'bundle builder rejects a missing evidence role',
    );
    assertionCount += 3;

    assert.throws(
      () => createReleaseHarnessBundle({
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        evidenceRoot: validPathFixtures.get(fuzzContract.id).evidenceRoot,
        gate: fuzzContract.id,
        now: verificationNow,
        outputPath: validPathFixtures.get(fuzzContract.id).builtBundlePath,
        registryPath: configuration.registryPath,
      }),
      /never overwrites evidence/,
      'bundle builder never overwrites an existing output',
    );
    assertionCount++;

    const scanContract = configuration.contracts.get('release-scans');
    const tamperedScanRoot = materializeBundleRoles(
      root,
      'tampered-scan-role',
      scanContract,
      validPathFixtures.get('release-scans').bundle,
    );
    writeFileSync(
      join(tamperedScanRoot, scanContract.roles[1].path, '00-codeql-java.sarif'),
      'tampered scan report\n',
      'utf8',
    );
    assert.throws(
      () => verifyReleaseHarnessEvidenceDirectory({
        evidenceRoot: tamperedScanRoot,
        gate: scanContract.id,
        now: verificationNow,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'tampered unpacked scan role',
    );
    assert.equal(runVerifier(scanContract.id, tamperedScanRoot).status, 1);
    assertionCount += 2;

    const benchmarkContract = configuration.contracts.get('mcp-benchmarks');
    const tamperedBenchmarkRoot = materializeBundleRoles(
      root,
      'tampered-benchmark-role',
      benchmarkContract,
      validPathFixtures.get('mcp-benchmarks').bundle,
    );
    writeFileSync(
      join(tamperedBenchmarkRoot, benchmarkContract.roles[1].path),
      'tampered benchmark log\n',
      'utf8',
    );
    assert.throws(
      () => verifyReleaseHarnessEvidenceDirectory({
        evidenceRoot: tamperedBenchmarkRoot,
        gate: benchmarkContract.id,
        now: verificationNow,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'tampered unpacked benchmark role',
    );
    assert.equal(runVerifier(benchmarkContract.id, tamperedBenchmarkRoot).status, 1);
    assertionCount += 2;

    const mismatchedReceipt = clone(validPathFixtures.get('release-scans').receipt);
    mismatchedReceipt.roles[0].sha256 = '0'.repeat(64);
    assert.throws(
      () => verifyReleaseHarnessRoleDescriptors(
        mismatchedReceipt,
        validPathFixtures.get('release-scans').verified.roles,
      ),
      ReleaseHarnessEvidenceImportError,
      'unpacked role descriptor receipt mismatch',
    );
    assert.throws(
      () => verifyReleaseHarnessEvidenceDirectory({
        evidenceRoot: validPathFixtures.get('release-scans').evidenceRoot,
        expectedImportedReceipt: mismatchedReceipt,
        gate: 'release-scans',
        now: verificationNow,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'unpacked evidence receipt mismatch',
    );
    assertionCount += 2;

    const retainedScanFixture = validPathFixtures.get('release-scans');
    const bundleMismatchedReceipt = clone(retainedScanFixture.receipt);
    bundleMismatchedReceipt.roles[0].sha256 = '0'.repeat(64);
    const bundleMismatchedReceiptPath = writeFixture(
      root,
      'bundle-receipt-role-mismatch',
      bundleMismatchedReceipt,
    );
    assert.throws(
      () => verifyImportedBundleReceipt({
        bundlePath: retainedScanFixture.bundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        now: verificationNow,
        receiptPath: bundleMismatchedReceiptPath,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'retained bundle receipt role mismatch',
    );
    assertionCount++;

    const substitutedCurrentCandidate = {
      ...candidate,
      candidateTree: 'c'.repeat(40),
      producerWorkflowSha256: digest('substituted-producer-workflow'),
    };
    assert.throws(
      () => verifyImportedBundleReceipt({
        bundlePath: retainedScanFixture.bundlePath,
        candidateIdentityProvider: () => clone(substitutedCurrentCandidate),
        candidateRoot: root,
        now: verificationNow,
        receiptPath: retainedScanFixture.outputPath,
        registryPath: configuration.registryPath,
      }),
      /do not match the current candidate root/,
      'coherent bundle and receipt current-candidate mismatch',
    );
    assertionCount++;

    assert.equal(
      runVerifier(
        'fuzz-nightly-history',
        validPathFixtures.get('fuzz-nightly-history').evidenceRoot,
        ['unexpected'],
      ).status,
      64,
    );
    assert.equal(
      runVerifier(
        'release-scans',
        validPathFixtures.get('release-scans').evidenceRoot,
        ['unexpected'],
      ).status,
      64,
    );
    assert.equal(
      runVerifier(
        'mcp-benchmarks',
        validPathFixtures.get('mcp-benchmarks').evidenceRoot,
        ['unexpected'],
      ).status,
      64,
    );
    assertionCount += 3;

    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'missing-role', root,
      mutate: (bundle) => bundle.content.roles.pop(),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'extra-role', root,
      mutate: (bundle) => bundle.content.roles.push(clone(bundle.content.roles[0])),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'reordered-roles', root,
      mutate: (bundle) => bundle.content.roles.reverse(),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'media-type-drift', root,
      mutate: (bundle) => { bundle.content.roles[1].mediaType = 'application/octet-stream'; },
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'candidate-mismatch', root,
      mutate: (bundle) => { bundle.content.candidate.candidateCommit = 'c'.repeat(40); },
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'toolchain-drift', root,
      mutate: (bundle) => { bundle.content.toolchains[0].version = 'unapproved'; },
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'policy-drift', root,
      mutate: (bundle) => { bundle.content.policy.consecutiveUtcDates = 6; },
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'stale-window', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs.forEach((run, index) => {
          run.completedAt = `2026-07-${String(17 + index).padStart(2, '0')}T12:00:00Z`;
          run.id = run.completedAt.slice(0, 10);
        });
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'soak-nightly-history', label: 'partial-window', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => evidence.runs.pop()),
    });
    expectImportFailure({
      candidate, configuration, gate: 'soak-nightly-history', label: 'missing-soak-report', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        delete evidence.runs[0].scenarios[0].report;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'soak-nightly-history', label: 'soak-surefire-failure', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        const scenario = evidence.runs[0].scenarios[0];
        scenario.report.surefire.failures = 1;
        scenario.reportSha256 = sha256(Buffer.from(canonicalJson(scenario.report), 'utf8'));
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'soak-nightly-history', label: 'soak-profile-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs[0].profile = 'unapproved';
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'soak-nightly-history', label: 'nonconsecutive-dates', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs[1].completedAt = '2026-08-19T08:00:00Z';
        evidence.runs[1].id = '2026-08-19';
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'duplicate-run-id', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs[1].id = evidence.runs[0].id;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'changed-bundle-bytes', root,
      mutate: (bundle) => { bundle.contentSha256 = '0'.repeat(64); },
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'producer-failure', root,
      mutate: (bundle) => { bundle.content.producerStatus = 'FAIL'; },
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'missing-target', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs[0].targets.pop();
        evidence.runs[0].corpusHashes.pop();
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'fuzz-nightly-history', label: 'failed-target', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.runs[0].targets[0].outcome = 'FAIL';
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'unexpected-severity', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'scan-summary', (evidence) => {
        evidence.findings.push({
          accepted: false,
          fingerprint: 'self-test-fingerprint',
          path: 'src/main/java/Example.java',
          ruleId: 'self-test-rule',
          scanner: 'codeql',
          severity: 'HIGH',
        });
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'self-approved-scan-allowlist', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'scan-summary', (evidence) => {
        evidence.allowlist.push({
          approvedAt: '2026-08-24T00:00:00Z',
          approvalReference: 'self-authored:approval',
          commit: candidate.candidateCommit,
          expiresAt: '2026-09-01T00:00:00Z',
          fingerprint: 'self-test-fingerprint',
          owner: 'self-test-owner',
          path: 'src/main/java/Example.java',
          rationale: 'self-authored exception',
          ruleId: 'self-test-rule',
          scanner: 'codeql',
        });
      }),
    });

    const expiredScan = approvedScanFixture();
    expiredScan.approval.approvedAt = '2026-07-01T00:00:00Z';
    expiredScan.approval.expiresAt = '2026-07-30T00:00:00Z';
    writeScanApprovalRegistry(root, [expiredScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'expired-exact-scan-exception', root,
      mutate: (bundle) => applyApprovedScanFixture(bundle, expiredScan),
    });
    writeScanApprovalRegistry(root, []);

    const wildcardScan = approvedScanFixture();
    wildcardScan.approval.path = 'src/**/example.properties';
    writeScanApprovalRegistry(root, [wildcardScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'wildcard-scan-exception', root,
      mutate: (bundle) => applyApprovedScanFixture(bundle, wildcardScan),
    });
    writeScanApprovalRegistry(root, []);

    const duplicateScan = approvedScanFixture();
    writeScanApprovalRegistry(root, [duplicateScan.approval, duplicateScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'duplicate-exact-scan-exception', root,
      mutate: (bundle) => {
        applyApprovedScanFixture(bundle, duplicateScan);
        mutateJsonRole(bundle, 'scan-summary', (evidence) => {
          evidence.allowlist.push(clone(duplicateScan.approval));
        });
      },
    });
    writeScanApprovalRegistry(root, []);

    const commitMismatchScan = approvedScanFixture();
    commitMismatchScan.approval.commit = '2'.repeat(40);
    writeScanApprovalRegistry(root, [commitMismatchScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'scan-exception-commit-mismatch', root,
      mutate: (bundle) => applyApprovedScanFixture(bundle, commitMismatchScan),
    });
    writeScanApprovalRegistry(root, []);

    const highScan = approvedScanFixture({ severity: 'HIGH' });
    writeScanApprovalRegistry(root, [highScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'high-scan-exception', root,
      mutate: (bundle) => applyApprovedScanFixture(bundle, highScan),
    });
    writeScanApprovalRegistry(root, []);

    const unmatchedScan = approvedScanFixture();
    writeScanApprovalRegistry(root, [unmatchedScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'unmatched-scan-exception', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'scan-summary', (evidence) => {
        evidence.allowlist = [unmatchedScan.approval];
      }),
    });
    writeScanApprovalRegistry(root, []);

    const mismatchedRawScan = approvedScanFixture();
    writeScanApprovalRegistry(root, [mismatchedRawScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'gitleaks-format-mismatch', root,
      mutate: (bundle) => {
        applyApprovedScanFixture(bundle, mismatchedRawScan);
        const changedSarif = clone(mismatchedRawScan.sarif);
        changedSarif.runs[0].results[0].locations[0]
          .physicalLocation.region.endColumn++;
        replaceDirectoryEntry(
          bundle,
          'scan-reports',
          '02-gitleaks.sarif',
          changedSarif,
        );
      },
    });
    writeScanApprovalRegistry(root, []);

    const registryMismatchScan = approvedScanFixture();
    writeScanApprovalRegistry(root, []);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'candidate-scan-registry-mismatch', root,
      mutate: (bundle) => applyApprovedScanFixture(bundle, registryMismatchScan),
    });

    const highCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: candidate.candidateCommit,
      securitySeverity: '8.1',
    });
    writeScanApprovalRegistry(root, [highCodeqlScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'high-codeql-exception', root,
      mutate: (bundle) => applyApprovedCodeqlScanFixture(bundle, highCodeqlScan),
    });
    writeScanApprovalRegistry(root, []);

    const criticalCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: candidate.candidateCommit,
      securitySeverity: '9.1',
    });
    writeScanApprovalRegistry(root, [criticalCodeqlScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'critical-codeql-exception', root,
      mutate: (bundle) => applyApprovedCodeqlScanFixture(bundle, criticalCodeqlScan),
    });
    writeScanApprovalRegistry(root, []);

    const wrongRevisionCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: 'c'.repeat(40),
    });
    writeScanApprovalRegistry(root, [wrongRevisionCodeqlScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'codeql-provenance-mismatch', root,
      mutate: (bundle) => applyApprovedCodeqlScanFixture(bundle, wrongRevisionCodeqlScan),
    });
    writeScanApprovalRegistry(root, []);

    const malformedSeverityCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: candidate.candidateCommit,
    });
    malformedSeverityCodeqlScan.sarif.runs[0].tool.driver.rules[0]
      .properties['security-severity'] = 'not-a-score';
    writeScanApprovalRegistry(root, [malformedSeverityCodeqlScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'malformed-codeql-severity', root,
      mutate: (bundle) => applyApprovedCodeqlScanFixture(bundle, malformedSeverityCodeqlScan),
    });
    writeScanApprovalRegistry(root, []);

    const wrongFingerprintCodeqlScan = approvedCodeqlScanFixture({
      candidateCommit: candidate.candidateCommit,
    });
    wrongFingerprintCodeqlScan.approval.fingerprint = '0'.repeat(64);
    writeScanApprovalRegistry(root, [wrongFingerprintCodeqlScan.approval]);
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'codeql-fingerprint-mismatch', root,
      mutate: (bundle) => applyApprovedCodeqlScanFixture(bundle, wrongFingerprintCodeqlScan),
    });
    writeScanApprovalRegistry(root, []);

    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'hidden-codeql-result', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '00-codeql-java.sarif',
        {
          runs: [{
            results: [{ ruleId: 'java/hidden-security-result' }],
            tool: { driver: { name: 'CodeQL' } },
          }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'missing-codeql-invocation', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '00-codeql-java.sarif',
        {
          runs: [{ results: [], tool: { driver: { name: 'CodeQL' } } }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'failed-codeql-invocation', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '00-codeql-java.sarif',
        {
          runs: [{
            invocations: [{ executionSuccessful: false, exitCode: 1 }],
            results: [],
            tool: { driver: { name: 'CodeQL' } },
          }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'nonzero-codeql-invocation', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '00-codeql-java.sarif',
        {
          runs: [{
            invocations: [{ executionSuccessful: true, exitCode: 2 }],
            results: [],
            tool: { driver: { name: 'CodeQL' } },
          }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'codeql-execution-error', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '00-codeql-java.sarif',
        {
          runs: [{
            invocations: [{
              executionSuccessful: true,
              exitCode: 0,
              toolExecutionNotifications: [{
                level: 'error',
                message: { text: 'analysis terminated early' },
              }],
            }],
            results: [],
            tool: { driver: { name: 'CodeQL' } },
          }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'gitleaks-failed-invocation', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '02-gitleaks.sarif',
        {
          runs: [{
            invocations: [{ executionSuccessful: false }],
            results: [],
            tool: { driver: { name: 'gitleaks' } },
          }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'wrong-scanner', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '02-gitleaks.sarif',
        {
          runs: [{ results: [], tool: { driver: { name: 'not-gitleaks' } } }],
          version: '2.1.0',
        },
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'malformed-spotbugs-report', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '01-spotbugs.xml',
        'garbage <BugCollection>\n',
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'unclosed-spotbugs-report', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '01-spotbugs.xml',
        '<?xml version="1.0" encoding="UTF-8"?>\n<BugCollection>\n',
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'spotbugs-entity-declaration', root,
      mutate: (bundle) => replaceDirectoryEntry(
        bundle,
        'scan-reports',
        '01-spotbugs.xml',
        '<?xml version="1.0" encoding="UTF-8"?>\n'
          + '<!DOCTYPE BugCollection [<!ENTITY hidden "content">]>\n'
          + '<BugCollection>&hidden;</BugCollection>\n',
      ),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'wrong-scan-pin', root,
      mutate: (bundle) => {
        const provenance = {
          candidate: clone(candidate),
          codeql: clone(configuration.contracts.get('release-scans').policy.codeql),
          formatVersion: 1,
          gitleaks: clone(configuration.contracts.get('release-scans').policy.gitleaks),
          producerWorkflowSha256: candidate.producerWorkflowSha256,
          spotbugs: clone(configuration.contracts.get('release-scans').policy.spotbugs),
          toolchains: clone(configuration.contracts.get('release-scans').toolchains),
        };
        provenance.codeql.bundle.version = 'unapproved';
        replaceDirectoryEntry(
          bundle,
          'scan-reports',
          '05-toolchain-provenance.json',
          provenance,
        );
      },
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'missing-scan-report', root,
      mutate: (bundle) => {
        const role = bundle.content.roles[1];
        role.entries.pop();
        refreshDirectoryDigest(role);
      },
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'unapproved-regression', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.comparison.jsonParseScoreRatio = 0.89;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'self-authorized-regression', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.comparison.jsonWriteScoreRatio = 0.89;
        evidence.review.regressionApproved = true;
        evidence.review.regressionApprovalReference = 'self-authored:owner-approval';
        evidence.review.releaseNoteSha256 = digest('unretained-release-note');
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'benchmark-config-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.configuration.threads = 2;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'benchmark-os-architecture-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.environment.architecture = 'aarch64';
        evidence.environment.image = 'windows-latest';
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'benchmark-raw-ratio-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        const run = evidence.repetitions[0].runs.find(({ artifact }) => artifact === '4.0.0');
        run.rawResult.jsonParseScore = 50;
        run.rawResultSha256 = sha256(Buffer.from(canonicalJson(run.rawResult), 'utf8'));
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'benchmark-zero-score', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        const run = evidence.repetitions[0].runs[0];
        run.rawResult.jsonParseScore = 0;
        run.rawResultSha256 = sha256(Buffer.from(canonicalJson(run.rawResult), 'utf8'));
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'mcp-benchmarks', label: 'benchmark-log-drift', root,
      mutate: (bundle) => replaceFileRole(bundle, 'benchmark-log', 'tampered benchmark log\n'),
    });
    expectBundleCreationFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-direct-builder-inline-forgery',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        const run = evidence.repetitions[0].runs[0];
        run.rawResult.jsonParseScore = 1_000_000;
        run.rawResultSha256 = sha256(
          Buffer.from(canonicalJson(run.rawResult), 'utf8'),
        );
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-forged-reviewed-draft-digest',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.review.reviewedDraftSha256 = digest('forged-reviewed-draft');
        evidence.review.signoffReference =
          `self-test:forged#sha256=${evidence.review.reviewedDraftSha256}`;
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-unbound-signoff',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.review.signoffReference =
          `self-test:benchmark-signoff#sha256=${'0'.repeat(64)}`;
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-missing-retained-raw-jmh',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.rawJmhResults.pop();
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-duplicate-retained-raw-path',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.rawJmhResults[1].path = evidence.rawJmhResults[0].path;
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-changed-retained-raw-jmh',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.rawJmhResults[0].results[0].primaryMetric.rawData[0][0] = 99;
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-changed-reviewed-draft',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.reviewedDraft.environment.cpuModel = 'changed-after-review';
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-swapped-profile-mapping',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        const profile = evidence.reviewedDraft.profile1Baseline;
        [profile[0].normalized, profile[1].normalized] =
          [profile[1].normalized, profile[0].normalized];
        evidence.profile1Baseline = profile.map(({ normalized }) => clone(normalized));
        refreshBenchmarkReview(evidence);
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-missing-profile-mapping',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        evidence.reviewedDraft.profile1Baseline.pop();
        evidence.profile1Baseline.pop();
        refreshBenchmarkReview(evidence);
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-profile-result-extra-key',
      root,
      mutate: (bundle) => mutateJsonRole(bundle, 'benchmark-results', (evidence) => {
        const normalized = evidence.reviewedDraft.profile1Baseline[0].normalized;
        normalized.rawResult.result.unregistered = true;
        normalized.rawResultSha256 = sha256(
          Buffer.from(canonicalJson(normalized.rawResult), 'utf8'),
        );
        evidence.profile1Baseline[0] = clone(normalized);
        refreshBenchmarkReview(evidence);
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-score-sample-inconsistency',
      root,
      mutate: (bundle) => mutateBenchmarkRoles(bundle, (evidence, log) => {
        const raw = evidence.rawJmhResults[0];
        const oldRawText = canonicalJson(raw.results);
        const oldMarker = `SOKLET_BENCHMARK_RAW=${raw.path}:${raw.sha256}`;
        raw.results[0].primaryMetric.score = 1_000_000;
        raw.sha256 = sha256(Buffer.from(canonicalJson(raw.results), 'utf8'));
        evidence.reviewedDraft.repetitions[0].runs[0].rawJmhSha256 = raw.sha256;
        const newMarker = `SOKLET_BENCHMARK_RAW=${raw.path}:${raw.sha256}`;
        let updatedLog = log.replace(`${oldMarker}\n`, `${newMarker}\n`);
        const markerIndex = updatedLog.indexOf(`${newMarker}\n`);
        const rawIndex = updatedLog.indexOf(oldRawText, markerIndex + newMarker.length);
        assert.notEqual(rawIndex, -1, 'self-test raw JMH log section exists');
        updatedLog = `${updatedLog.slice(0, rawIndex)}${canonicalJson(raw.results)}`
          + updatedLog.slice(rawIndex + oldRawText.length);
        evidence.benchmarkLogSha256 = sha256(Buffer.from(updatedLog, 'utf8'));
        evidence.reviewedDraft.benchmarkLogSha256 = evidence.benchmarkLogSha256;
        refreshBenchmarkReview(evidence);
        return updatedLog;
      }),
    });
    expectImportFailure({
      candidate,
      configuration,
      gate: 'mcp-benchmarks',
      label: 'benchmark-log-missing-retained-raw-jmh',
      root,
      mutate: (bundle) => mutateBenchmarkRoles(bundle, (evidence, log) => {
        const rawText = canonicalJson(evidence.rawJmhResults[0].results);
        const rawIndex = log.indexOf(rawText);
        assert.notEqual(rawIndex, -1, 'self-test raw JMH log bytes exist');
        const updatedLog = log.slice(0, rawIndex)
          + log.slice(rawIndex + rawText.length);
        evidence.benchmarkLogSha256 = sha256(Buffer.from(updatedLog, 'utf8'));
        evidence.reviewedDraft.benchmarkLogSha256 = evidence.benchmarkLogSha256;
        refreshBenchmarkReview(evidence);
        return updatedLog;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'release-scans', label: 'directory-path-escape', root,
      mutate: (bundle) => {
        const role = bundle.content.roles[1];
        role.entries[0].path = '../escape';
        refreshDirectoryDigest(role);
      },
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'partial-operational-window', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.samples.splice(1000);
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'operational-cadence-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.samples = evidence.samples.filter((_, index) => index % 3 === 0);
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'operational-delta-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.samples.at(-1).resources.http.fileDescriptors++;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'operational-measure-over-policy', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        const limit = configuration.contracts.get('operational-history')
          .policy.finalResourceDeltas.http.fileDescriptors;
        evidence.finalResourceDeltas.http.fileDescriptors = limit + 1;
        evidence.samples.at(-1).resources.http.fileDescriptors =
          evidence.resourceBaselines.http.fileDescriptors + limit + 1;
      }),
    });
    expectImportFailure({
      candidate, configuration, gate: 'operational-history', label: 'operational-baseline-drift', root,
      mutate: (bundle) => mutateJsonRole(bundle, 'history', (evidence) => {
        evidence.resourceBaselines.http.fileDescriptors++;
      }),
    });

    const noncanonicalReceiptPath = writeFixture(
      root,
      'noncanonical-output',
      validReceipt,
      false,
    );
    assert.throws(
      () => verifyImportedReceipt(noncanonicalReceiptPath),
      ReleaseHarnessEvidenceImportError,
      'noncanonical output',
    );
    assertionCount++;

    const validBundlePath = writeFixture(
      root,
      'overwrite-valid',
      validBundle(configuration.contracts.get('fuzz-nightly-history'), candidate),
    );
    const existingOutput = writeFixture(root, 'existing-output', { occupied: true });
    assert.throws(
      () => importReleaseHarnessEvidence({
        bundlePath: validBundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        gate: 'fuzz-nightly-history',
        now: NOW,
        outputPath: existingOutput,
        registryPath: configuration.registryPath,
      }),
      ReleaseHarnessEvidenceImportError,
      'immutable output',
    );
    assertionCount++;

    assert.ok(readFileSync(existingOutput, 'utf8').includes('occupied'));
    assertionCount++;
  } finally {
    rmSync(root, { recursive: true });
  }
  console.log(`release harness evidence importer self-test PASS assertions=${assertionCount} gates=5`);
}

run();
