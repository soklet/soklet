#!/usr/bin/env node

import assert from 'node:assert/strict';
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
import { join } from 'node:path';
import {
  canonicalJson,
  createReleaseHarnessBundle,
  ReleaseHarnessEvidenceImportError,
  verifyReleaseHarnessEvidenceDirectory,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';
import {
  finalizeMcpReleaseBenchmarks,
  summarizeJmhResults,
} from './produce-release-benchmarks.mjs';

const JSON_BENCHMARKS = [
  'com.soklet.McpReleaseJsonJmhBenchmark.jsonParse',
  'com.soklet.McpReleaseJsonJmhBenchmark.jsonWrite',
];
const PROFILE_BENCHMARKS = [
  'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaCompile',
  'com.soklet.McpReleaseJsonJmhBenchmark.profile1SchemaEvaluate',
];
const configuration = {
  candidateJvm: ['-Xms1g', '-Xmx1g', '-XX:+AlwaysPreTouch'],
  forks: 5,
  measurement: { iterations: 10, secondsPerIteration: 1 },
  threads: 1,
  warmup: { iterations: 10, secondsPerIteration: 1 },
};

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function digestJson(value) {
  return sha256(Buffer.from(canonicalJson(value), 'utf8'));
}

function entry(benchmark, score, artifact = '3.5.1', forks = 1) {
  return {
    benchmark,
    forks,
    jdkVersion: '17.0.20',
    jmhVersion: '1.37',
    jvmArgs: [...configuration.candidateJvm],
    measurementIterations: configuration.measurement.iterations,
    measurementTime: '1 s',
    mode: 'thrpt',
    params: { artifact },
    primaryMetric: {
      rawData: Array.from(
        { length: forks },
        () => Array.from({ length: configuration.measurement.iterations }, () => score),
      ),
      score,
      scoreError: 0,
      scoreUnit: 'ops/s',
    },
    threads: 1,
    warmupIterations: configuration.warmup.iterations,
    warmupTime: '1 s',
  };
}

function clone(value) {
  return structuredClone(value);
}

function writeCanonical(path, value) {
  const bytes = Buffer.from(canonicalJson(value), 'utf8');
  writeFileSync(path, bytes);
  return bytes;
}

const valid = JSON_BENCHMARKS.map((benchmark, index) =>
  entry(benchmark, 100 + index));
const summary = summarizeJmhResults(valid, {
  artifact: '3.5.1',
  benchmarks: JSON_BENCHMARKS,
  configuration,
  expectedForks: 1,
});
assert.deepEqual(summary.map(({ benchmark, score }) => ({ benchmark, score })), [
  { benchmark: JSON_BENCHMARKS[0], score: 100 },
  { benchmark: JSON_BENCHMARKS[1], score: 101 },
]);

let assertionCount = 1;
for (const [label, mutate] of [
  ['missing benchmark', (value) => value.pop()],
  ['duplicate benchmark', (value) => { value[1].benchmark = JSON_BENCHMARKS[0]; }],
  ['artifact drift', (value) => { value[0].params.artifact = '4.0.0'; }],
  ['JMH drift', (value) => { value[0].jmhVersion = '1.36'; }],
  ['JDK drift', (value) => { value[0].jdkVersion = '17.0.19'; }],
  ['JVM drift', (value) => { value[0].jvmArgs = ['-Xmx2g']; }],
  ['fork drift', (value) => { value[0].forks = 5; }],
  ['warmup drift', (value) => { value[0].warmupIterations = 9; }],
  ['measurement drift', (value) => { value[0].measurementTime = '2 s'; }],
  ['nonpositive score', (value) => { value[0].primaryMetric.score = 0; }],
  ['score/sample inconsistency', (value) => { value[0].primaryMetric.score = 99; }],
  ['missing raw sample', (value) => { value[0].primaryMetric.rawData[0].pop(); }],
  ['nonpositive raw sample', (value) => { value[0].primaryMetric.rawData[0][0] = 0; }],
]) {
  const malformed = clone(valid);
  mutate(malformed);
  assert.throws(() => summarizeJmhResults(malformed, {
    artifact: '3.5.1',
    benchmarks: JSON_BENCHMARKS,
    configuration,
    expectedForks: 1,
  }), ReleaseHarnessEvidenceImportError, label);
  assertionCount++;
}

const selfTestRoot = realpathSync(
  mkdtempSync(join(tmpdir(), 'soklet-benchmark-finalize-')),
);
const registry = verifyReleaseHarnessConfiguration();
const contract = registry.contracts.get('mcp-benchmarks');
const candidate = {
  candidateCommit: '1'.repeat(40),
  candidateMainJarSha256: sha256(Buffer.from('candidate-main-jar', 'utf8')),
  candidatePomSha256: sha256(Buffer.from('candidate-pom', 'utf8')),
  candidateRegistrySha256: registry.registrySha256,
  candidateTree: '2'.repeat(40),
  producerWorkflowSha256: sha256(Buffer.from('producer-workflow', 'utf8')),
};

function normalizedJsonRun(artifact, parseScore, writeScore) {
  const rawResult = {
    artifact,
    configuration,
    jsonParseScore: parseScore,
    jsonWriteScore: writeScore,
  };
  return {
    artifact,
    outcome: 'PASS',
    rawResult,
    rawResultSha256: digestJson(rawResult),
  };
}

function normalizedProfile(operation, result) {
  const rawResult = {
    complete: true,
    errors: [],
    operation,
    result: {
      benchmark: result.benchmark,
      rawJmhResultSha256: digestJson(result),
      score: result.primaryMetric.score,
      scoreError: result.primaryMetric.scoreError,
      scoreUnit: result.primaryMetric.scoreUnit,
    },
  };
  return {
    errors: 0,
    operation,
    rawResult,
    rawResultSha256: digestJson(rawResult),
  };
}

const defaultChangelog = '# Changelog\n\n## 4.0.0\n\n- Release fixture.\n\n## 3.5.1\n\n- Baseline fixture.\n';
const regressionChangelog = '# Changelog\n\n## 4.0.0\n\n- Document the accepted JSON benchmark regression.\n\n## 3.5.1\n\n- Baseline fixture.\n';
const regressionReleaseNote = '## 4.0.0\n\n- Document the accepted JSON benchmark regression.\n\n';

function finalizationFixture(label, {
  baselineScore = 100,
  candidateScore = 100,
  changelog = defaultChangelog,
} = {}) {
  const root = join(selfTestRoot, label);
  const candidateRoot = join(root, 'candidate');
  const workRoot = join(root, 'work');
  const rawRoot = join(workRoot, 'raw');
  mkdirSync(candidateRoot, { recursive: true });
  mkdirSync(rawRoot, { recursive: true });
  writeFileSync(join(candidateRoot, 'CHANGELOG.md'), changelog, 'utf8');
  const log = [
    'Soklet MCP release benchmark raw execution',
    `SOKLET_BENCHMARK_CONFIGURATION_SHA256=${digestJson(configuration)}`,
  ];
  const rawPaths = [];
  const repetitions = [];
  for (let ordinal = 0; ordinal < configuration.forks; ordinal++) {
    const first = ordinal % 2 === 0 ? '3.5.1' : '4.0.0';
    const artifacts = first === '3.5.1'
      ? ['3.5.1', '4.0.0'] : ['4.0.0', '3.5.1'];
    const runs = artifacts.map((artifact, runIndex) => {
      const score = artifact === '4.0.0' ? candidateScore : baselineScore;
      const results = JSON_BENCHMARKS.map((benchmark) =>
        entry(benchmark, score, artifact));
      const relativePath = `raw/repetition-${ordinal}-run-${runIndex}-${artifact}.json`;
      const path = join(workRoot, relativePath);
      const rawBytes = writeCanonical(path, results);
      rawPaths.push(path);
      const normalized = normalizedJsonRun(artifact, score, score);
      log.push(
        `SOKLET_BENCHMARK_RUN=${ordinal}:${runIndex}:${artifact}:PASS:${normalized.rawResultSha256}`,
        `SOKLET_BENCHMARK_RAW=${relativePath}:${sha256(rawBytes)}`,
        rawBytes.toString('utf8'),
      );
      return {
        normalized,
        rawJmhPath: relativePath,
        rawJmhSha256: sha256(rawBytes),
      };
    });
    repetitions.push({ first, ordinal, runs });
  }
  const profileResults = PROFILE_BENCHMARKS.map((benchmark) =>
    entry(benchmark, 50, '4.0.0', configuration.forks));
  const profileRelativePath = 'raw/profile1.json';
  const profilePath = join(workRoot, profileRelativePath);
  const profileBytes = writeCanonical(profilePath, profileResults);
  rawPaths.push(profilePath);
  log.push(
    `SOKLET_BENCHMARK_RAW=${profileRelativePath}:${sha256(profileBytes)}`,
    profileBytes.toString('utf8'),
  );
  const profile1Baseline = contract.policy.profile1Baseline.operations.map(
    (operation, index) => ({
      normalized: normalizedProfile(operation, profileResults[index]),
      rawJmhPath: profileRelativePath,
      rawJmhSha256: sha256(profileBytes),
    }),
  );
  const logBytes = Buffer.from(`${log.join('\n')}\n`, 'utf8');
  writeFileSync(join(workRoot, 'mcp-benchmarks.log'), logBytes);
  const draft = {
    approvalReference: 'review-system:approval/123',
    benchmarkLogSha256: sha256(logBytes),
    candidate,
    comparison: {
      artifact: contract.policy.comparison.artifact,
      jarSha256: contract.policy.comparison.jarSha256,
      jsonParseScoreRatio: candidateScore / baselineScore,
      jsonWriteScoreRatio: candidateScore / baselineScore,
      pomSha256: contract.policy.comparison.pomSha256,
    },
    configuration,
    environment: {
      architecture: 'x86_64',
      cpuModel: 'self-test-cpu',
      governor: 'performance',
      image: 'ubuntu-24.04@20260825.1.0',
      kernel: 'self-test-kernel',
      microcode: 'self-test-microcode',
      sameBoot: true,
      samePhysicalRunner: true,
      turboState: 'disabled:self-test',
    },
    formatVersion: 1,
    gate: 'mcp-benchmarks',
    policySha256: digestJson(contract.policy),
    producerStatus: 'AWAITING_REVIEW',
    profile1Baseline,
    repetitions,
    toolchainsSha256: digestJson(contract.toolchains),
  };
  const draftPath = join(workRoot, 'mcp-benchmarks-draft.json');
  const draftBytes = writeCanonical(draftPath, draft);
  return {
    bundleOutput: join(root, 'mcp-benchmarks-bundle.json'),
    candidateRoot,
    draft,
    draftPath,
    evidenceRoot: join(root, 'evidence'),
    rawPaths,
    reviewedDraftSha256: sha256(draftBytes),
    workRoot,
  };
}

function finalizeFixture(fixture, overrides = {}) {
  return finalizeMcpReleaseBenchmarks({
    ...fixture,
    bundleBuilder: (options) => createReleaseHarnessBundle({
      ...options,
      candidateIdentityProvider: () => clone(candidate),
      registryPath: registry.registryPath,
    }),
    candidateIdentityProvider: () => clone(candidate),
    contractConfigurationProvider: () => ({ configuration: registry, contract }),
    signoffReference: `review-system:signoff/456#sha256=${fixture.reviewedDraftSha256}`,
    ...overrides,
  });
}

try {
  const accepted = finalizationFixture('accepted');
  const bundle = finalizeFixture(accepted);
  assert.match(bundle.contentSha256, /^[0-9a-f]{64}$/u);
  assertionCount++;
  const evidence = JSON.parse(readFileSync(
    join(accepted.evidenceRoot, 'mcp-benchmarks.json'),
    'utf8',
  ));
  assert.equal(evidence.review.reviewedDraftSha256, accepted.reviewedDraftSha256);
  assert.equal(
    evidence.review.signoffReference,
    `review-system:signoff/456#sha256=${accepted.reviewedDraftSha256}`,
  );
  assert.equal(evidence.rawJmhResults.length, configuration.forks * 2 + 1);
  assert.deepEqual(evidence.reviewedDraft, accepted.draft);
  assert.equal(evidence.review.regressionApproved, false);
  assert.equal(evidence.review.regressionApprovalReference, null);
  assert.equal(evidence.review.releaseNoteSha256, null);
  assertionCount += 7;

  const regression = finalizationFixture('accepted-regression', {
    candidateScore: 80,
    changelog: regressionChangelog,
  });
  const regressionApprovalReference =
    `owner-system:benchmark-regression/789#sha256=${regression.reviewedDraftSha256}`;
  finalizeFixture(regression, { regressionApprovalReference });
  const regressionEvidence = JSON.parse(readFileSync(
    join(regression.evidenceRoot, 'mcp-benchmarks.json'),
    'utf8',
  ));
  assert.equal(regressionEvidence.comparison.jsonParseScoreRatio, 0.8);
  assert.equal(regressionEvidence.comparison.jsonWriteScoreRatio, 0.8);
  assert.equal(regressionEvidence.review.regressionApproved, true);
  assert.equal(
    regressionEvidence.review.regressionApprovalReference,
    regressionApprovalReference,
  );
  assert.equal(
    regressionEvidence.review.releaseNoteSha256,
    sha256(Buffer.from(regressionReleaseNote, 'utf8')),
  );
  assert.equal(verifyReleaseHarnessEvidenceDirectory({
    candidateRoot: regression.candidateRoot,
    evidenceRoot: regression.evidenceRoot,
    gate: 'mcp-benchmarks',
    registryPath: registry.registryPath,
  }).candidate.candidateCommit, candidate.candidateCommit);
  assertionCount += 6;

  writeFileSync(
    join(regression.candidateRoot, 'CHANGELOG.md'),
    regressionChangelog.replace(
      'Document the accepted JSON benchmark regression.',
      'Document a different accepted JSON benchmark regression.',
    ),
    'utf8',
  );
  assert.throws(
    () => verifyReleaseHarnessEvidenceDirectory({
      candidateRoot: regression.candidateRoot,
      evidenceRoot: regression.evidenceRoot,
      gate: 'mcp-benchmarks',
      registryPath: registry.registryPath,
    }),
    /release-note digest does not match the candidate/u,
  );
  assertionCount++;

  const missingRegressionApproval = finalizationFixture(
    'missing-regression-approval',
    { candidateScore: 80, changelog: regressionChangelog },
  );
  assert.throws(
    () => finalizeFixture(missingRegressionApproval),
    /benchmark regression approval reference must be/u,
  );
  assertionCount++;

  const unboundRegressionApproval = finalizationFixture(
    'unbound-regression-approval',
    { candidateScore: 80, changelog: regressionChangelog },
  );
  assert.throws(
    () => finalizeFixture(unboundRegressionApproval, {
      regressionApprovalReference:
        `owner-system:benchmark-regression/789#sha256=${'0'.repeat(64)}`,
    }),
    /regression approval reference must be .*reviewed-draft-sha256/u,
  );
  assertionCount++;

  const sharedRegressionApproval = finalizationFixture(
    'shared-regression-approval',
    { candidateScore: 80, changelog: regressionChangelog },
  );
  const sharedReference =
    `review-system:signoff/456#sha256=${sharedRegressionApproval.reviewedDraftSha256}`;
  assert.throws(
    () => finalizeFixture(sharedRegressionApproval, {
      regressionApprovalReference: sharedReference,
      signoffReference: sharedReference,
    }),
    /must be separate from benchmark review sign-off/u,
  );
  assertionCount++;

  const undocumentedRegression = finalizationFixture(
    'undocumented-regression',
    { candidateScore: 80 },
  );
  assert.throws(
    () => finalizeFixture(undocumentedRegression, {
      regressionApprovalReference:
        `owner-system:benchmark-regression/789#sha256=${undocumentedRegression.reviewedDraftSha256}`,
    }),
    /CHANGELOG entry to describe the benchmark regression/u,
  );
  assertionCount++;

  const prefixCollisionRegression = finalizationFixture(
    'prefix-collision-regression',
    {
      candidateScore: 80,
      changelog: '# Changelog\n\n## 4.0.01\n\n- Document the accepted JSON benchmark regression.\n',
    },
  );
  assert.throws(
    () => finalizeFixture(prefixCollisionRegression, {
      regressionApprovalReference:
        `owner-system:benchmark-regression/789#sha256=${prefixCollisionRegression.reviewedDraftSha256}`,
    }),
    /exactly one 4\.0\.0 release-note heading/u,
  );
  assertionCount++;

  const duplicateReleaseNoteRegression = finalizationFixture(
    'duplicate-release-note-regression',
    {
      candidateScore: 80,
      changelog: `${regressionChangelog}\n## 4.0.0 (Duplicate)\n\n- Duplicate benchmark regression.\n`,
    },
  );
  assert.throws(
    () => finalizeFixture(duplicateReleaseNoteRegression, {
      regressionApprovalReference:
        `owner-system:benchmark-regression/789#sha256=${duplicateReleaseNoteRegression.reviewedDraftSha256}`,
    }),
    /exactly one 4\.0\.0 release-note heading/u,
  );
  assertionCount++;

  const spuriousRegressionApproval = finalizationFixture(
    'spurious-regression-approval',
  );
  assert.throws(
    () => finalizeFixture(spuriousRegressionApproval, {
      regressionApprovalReference:
        `owner-system:benchmark-regression/789#sha256=${spuriousRegressionApproval.reviewedDraftSha256}`,
    }),
    /non-regressing benchmark must not claim regression approval/u,
  );
  assertionCount++;

  const alteredDraft = finalizationFixture('altered-draft');
  alteredDraft.draft.environment.cpuModel = 'changed-after-review';
  writeCanonical(alteredDraft.draftPath, alteredDraft.draft);
  assert.throws(
    () => finalizeFixture(alteredDraft),
    /differs from the exact immutable bytes approved by the reviewer/u,
  );
  assertionCount++;

  const alteredRaw = finalizationFixture('altered-raw');
  const rawValue = JSON.parse(readFileSync(alteredRaw.rawPaths[0], 'utf8'));
  rawValue[0].primaryMetric.score = 99;
  writeCanonical(alteredRaw.rawPaths[0], rawValue);
  assert.throws(
    () => finalizeFixture(alteredRaw),
    /Retained raw JMH result does not match the reviewed draft/u,
  );
  assertionCount++;

  const unboundSignoff = finalizationFixture('unbound-signoff');
  assert.throws(
    () => finalizeFixture(unboundSignoff, {
      signoffReference: `review-system:signoff/456#sha256=${'0'.repeat(64)}`,
    }),
    /sign-off reference must be .*reviewed-draft-sha256/u,
  );
  assertionCount++;

  const swappedProfile = finalizationFixture('swapped-profile');
  [
    swappedProfile.draft.profile1Baseline[0].normalized,
    swappedProfile.draft.profile1Baseline[1].normalized,
  ] = [
    swappedProfile.draft.profile1Baseline[1].normalized,
    swappedProfile.draft.profile1Baseline[0].normalized,
  ];
  const swappedDraftBytes = writeCanonical(
    swappedProfile.draftPath,
    swappedProfile.draft,
  );
  swappedProfile.reviewedDraftSha256 = sha256(swappedDraftBytes);
  assert.throws(
    () => finalizeFixture(swappedProfile),
    /Profile 1 operation 1 has the wrong benchmark mapping/u,
  );
  assertionCount++;
} finally {
  rmSync(selfTestRoot, { recursive: true });
}

console.log(`release benchmark producer self-test PASS (${assertionCount} assertions)`);
