#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  ReleaseHarnessEvidenceImportError,
  canonicalJson,
  importReleaseHarnessEvidence,
  verifyImportedReceipt,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';

const NOW_TEXT = '2026-08-24T12:00:00Z';
const NOW = Date.parse(NOW_TEXT);
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

function historyTimes() {
  return Array.from({ length: 7 }, (_, index) =>
    `2026-08-${String(17 + index).padStart(2, '0')}T12:00:00Z`);
}

function fuzzEvidence(contract, candidate) {
  return {
    ...commonEvidence(contract, candidate),
    runs: historyTimes().map((completedAt) => ({
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

function soakEvidence(contract, candidate) {
  return {
    ...commonEvidence(contract, candidate),
    runs: historyTimes().map((completedAt) => ({
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
  const repetitions = Array.from({ length: contract.policy.forks }, (_, ordinal) => {
    const first = ordinal % 2 === 0 ? '3.5.1' : '4.0.0';
    const artifacts = first === '3.5.1'
      ? ['3.5.1', '4.0.0']
      : ['4.0.0', '3.5.1'];
    return {
      first,
      ordinal,
      runs: artifacts.map((artifact) => {
        const rawResult = {
          artifact,
          configuration: clone(configuration),
          jsonParseScore: 100,
          jsonWriteScore: 100,
        };
        return {
          artifact,
          outcome: 'PASS',
          rawResult,
          rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
        };
      }),
    };
  });
  const logMarkers = [
    `SOKLET_BENCHMARK_CONFIGURATION_SHA256=${sha256(Buffer.from(canonicalJson(configuration), 'utf8'))}`,
    ...repetitions.flatMap((repetition) => repetition.runs.map((run, runIndex) =>
      `SOKLET_BENCHMARK_RUN=${repetition.ordinal}:${runIndex}:${run.artifact}:PASS:${run.rawResultSha256}`)),
  ];
  const log = `self-test benchmark log PASS\n${logMarkers.join('\n')}\n`;
  const evidence = {
    ...commonEvidence(contract, candidate),
    benchmarkLogSha256: sha256(Buffer.from(log, 'utf8')),
    comparison: {
      artifact: contract.policy.comparison.artifact,
      jarSha256: contract.policy.comparison.jarSha256,
      jsonParseScoreRatio: 1,
      jsonWriteScoreRatio: 1,
      pomSha256: contract.policy.comparison.pomSha256,
    },
    configuration,
    environment: {
      architecture: 'x86_64',
      cpuModel: 'self-test-cpu',
      governor: 'performance',
      image: 'ubuntu-24.04@20260817.1.0',
      kernel: 'self-test-kernel',
      microcode: 'self-test-microcode',
      sameBoot: true,
      samePhysicalRunner: true,
      turboState: 'disabled',
    },
    profile1Baseline: contract.policy.profile1Baseline.operations.map((operation) => {
      const rawResult = {
        complete: true,
        errors: [],
        operation,
        result: { status: 'COMPLETE_RAW_RESULT' },
      };
      return {
        errors: 0,
        operation,
        rawResult,
        rawResultSha256: sha256(Buffer.from(canonicalJson(rawResult), 'utf8')),
      };
    }),
    repetitions,
    review: {
      approvalReference: 'self-test:benchmark-review',
      regressionApprovalReference: null,
      regressionApproved: false,
      releaseNoteSha256: null,
      signoffReference: 'self-test:benchmark-signoff',
    },
  };
  return { evidence, log };
}

function scanRoles(contract, candidate) {
  const emptySarif = (tool) => canonicalJson({
    runs: [{ results: [], tool: { driver: { name: tool } } }],
    version: '2.1.0',
  });
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

function rolesFor(contract, candidate) {
  switch (contract.id) {
    case 'fuzz-nightly-history':
      return [fileRole(contract.roles[0], fuzzEvidence(contract, candidate))];
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
      return [fileRole(contract.roles[0], soakEvidence(contract, candidate))];
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

function validBundle(contract, candidate) {
  return wrapContent({
    candidate: clone(candidate),
    contractVersion: contract.contractVersion,
    evidenceContract: contract.evidenceContract,
    gate: contract.id,
    policy: clone(contract.policy),
    producer: contract.producer,
    producerStatus: 'PASS',
    roles: rolesFor(contract, candidate),
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

function writeFixture(root, prefix, value, canonical = true) {
  const path = join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${prefix}.json`);
  writeFileSync(path, canonical ? canonicalJson(value) : JSON.stringify(value), 'utf8');
  return path;
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
        /exact MCP-0-12 approval/,
        label,
      );
      assertionCount++;
    }

    let validReceipt;
    for (const [gate, contract] of configuration.contracts) {
      const bundlePath = writeFixture(root, `${gate}-valid`, validBundle(contract, candidate));
      const outputPath = join(root, `${String(fixtureOrdinal++).padStart(3, '0')}-${gate}-receipt.json`);
      const receipt = importReleaseHarnessEvidence({
        bundlePath,
        candidateIdentityProvider: () => clone(candidate),
        candidateRoot: root,
        gate,
        now: NOW,
        outputPath,
        registryPath: configuration.registryPath,
      });
      assert.deepEqual(verifyImportedReceipt(outputPath), receipt);
      assert.equal(receipt.gate, gate);
      assert.equal(receipt.candidateBindings.candidateCommit, candidate.candidateCommit);
      validReceipt = receipt;
      assertionCount += 3;
    }

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
