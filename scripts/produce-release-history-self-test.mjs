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
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  canonicalJson,
  verifyReleaseHarnessConfiguration,
  verifyReleaseHarnessEvidenceDirectory,
} from './import-release-harness-evidence.mjs';
import {
  ReleaseHistoryProductionError,
  appendHistoryRun,
  collectFuzzRun,
  createFuzzTargetReceipt,
  createOperationalEvidence,
  createSoakRunFromReport,
  historyEvidenceFromState,
  requireApprovedSoakProfile,
  verifyCorrettoEvidence,
} from './produce-release-history.mjs';
import { verifySoakProfile } from './verify-soak-evidence.mjs';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const SCRIPT_PATH = resolve(SCRIPT_DIRECTORY, 'produce-release-history.mjs');
const OPERATIONAL_SELF_TEST_PATH = resolve(
  SCRIPT_DIRECTORY,
  'produce-operational-history-self-test.mjs',
);
const NOW = Date.parse('2026-08-30T12:00:00Z');
let assertions = 0;

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function digest(label) {
  return sha256(Buffer.from(label, 'utf8'));
}

function clone(value) {
  return structuredClone(value);
}

function writeCanonical(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, canonicalJson(value));
  return path;
}

function candidate(registrySha256) {
  return {
    candidateCommit: '1'.repeat(40),
    candidateMainJarSha256: digest('main-jar'),
    candidatePomSha256: digest('pom'),
    candidateRegistrySha256: registrySha256,
    candidateTree: '2'.repeat(40),
    producerWorkflowSha256: digest('workflow'),
  };
}

function times() {
  return Array.from({ length: 7 }, (_, index) =>
    new Date(NOW - (7 - index) * 86_400_000).toISOString().replace('.000Z', 'Z'));
}

function toolchainEvidence(contract) {
  const toolchain = contract.toolchains.find((value) => value.artifact.includes('corretto'));
  return [
    `distribution=corretto`,
    `version=${toolchain.version.split('.').slice(0, 3).join('.')}`,
    `runtimeVersion=${toolchain.version.split('.').slice(0, 3).join('.')}+1-LTS`,
    `vendorVersion=Corretto-${toolchain.version}`,
    `url=https://example.invalid/${toolchain.artifact}`,
    `archive=amazon-${toolchain.artifact}`,
    `archiveSha256=${toolchain.digest.slice('sha256:'.length)}`,
    '',
  ].join('\n');
}

function targetReceipt(contract, target, corpusHash = digest(target.id)) {
  return {
    corpusHash,
    formatVersion: 1,
    gate: contract.id,
    measuredDurationSeconds: contract.policy.perTargetDurationSeconds + 1,
    surefireReport: `TEST-${target.id}.xml`,
    target: {
      durationSeconds: contract.policy.perTargetDurationSeconds,
      id: target.id,
      ordinal: target.ordinal,
      outcome: 'PASS',
    },
    toolchainsSha256: sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8')),
  };
}

function fuzzReceipts(root, contract) {
  const receipts = join(root, 'fuzz-receipts');
  mkdirSync(receipts, { recursive: true });
  for (const target of contract.policy.targets)
    writeCanonical(join(receipts, `${target.id}.json`), targetReceipt(contract, target));
  return receipts;
}

function soakReport(contract) {
  return `${contract.policy.scenarios.map((scenario, index) => `## ${scenario.id}

- Result: PASS
- Baseline resources: fd=${8 + index}, heap=${16_777_216 + index} bytes (16.00 MiB), threads=${10 + index}
- Resource deltas: fd=${index % 2 === 0 ? '+0' : '-1'}, heap=+${index} bytes (0.00 MiB), threads=+0
- Tolerance: fd<=+12, heap<=+100663296 bytes (96.00 MiB), threads<=+64
`).join('\n')}`;
}

function operationalObservation(contract) {
  const start = Date.parse('2026-08-29T00:00:00Z');
  const span = contract.policy.durationSeconds + contract.policy.postIntervalReserveSeconds;
  const resources = {
    http: { fileDescriptors: 8, heapBytes: 16_777_216, liveThreads: 8 },
    mcpAndRealtime: { fileDescriptors: 12, heapBytes: 33_554_432, liveThreads: 12 },
  };
  return {
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
    resourceBaselines: clone(resources),
    samples: Array.from(
      { length: span / contract.policy.cadenceSeconds + 1 },
      (_, index) => ({
        at: new Date(start + index * contract.policy.cadenceSeconds * 1000)
          .toISOString().replace('.000Z', 'Z'),
        droppedLogRecords: 0,
        frameworkMetricCardinality: 0,
        rejectedMetricDeliveries: 0,
        resources: clone(resources),
        unregisteredMetricDimensions: 0,
      }),
    ),
    sensitiveCanaries: 0,
    terminalFrameworkCardinality: 0,
  };
}

function verifyRole(root, contract, evidence, now = NOW) {
  const evidenceRoot = join(root, `evidence-${contract.id}`);
  writeCanonical(join(evidenceRoot, contract.roles[0].path), evidence);
  return verifyReleaseHarnessEvidenceDirectory({
    evidenceRoot,
    gate: contract.id,
    now,
  });
}

function expectThrows(callback, pattern, label) {
  assert.throws(callback, pattern, label);
  assertions++;
}

function run() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-release-history-producer-')));
  try {
    const configuration = verifyReleaseHarnessConfiguration();
    const identity = candidate(configuration.registrySha256);
    const fuzz = configuration.contracts.get('fuzz-nightly-history');
    const soak = configuration.contracts.get('soak-nightly-history');
    const operational = configuration.contracts.get('operational-history');

    assert.equal(
      soak.policy.profileSha256,
      verifySoakProfile('nightly').profileSha256,
      'Registered soak-history policy must bind the exact checked-in nightly profile.',
    );
    assertions++;

    const evidencePath = join(root, 'corretto.txt');
    writeFileSync(evidencePath, toolchainEvidence(fuzz));
    assert.equal(verifyCorrettoEvidence(fuzz, evidencePath), true);
    assertions++;
    writeFileSync(evidencePath, toolchainEvidence(fuzz).replace(/archiveSha256=[0-9a-f]+/, `archiveSha256=${'0'.repeat(64)}`));
    expectThrows(
      () => verifyCorrettoEvidence(fuzz, evidencePath),
      ReleaseHistoryProductionError,
      'Corretto digest drift',
    );

    const localContract = clone(fuzz);
    const jazzerBytes = Buffer.from('fixture-jazzer', 'utf8');
    localContract.toolchains.find((value) => value.artifact === 'jazzer-junit.jar').digest =
      `sha256:${sha256(jazzerBytes)}`;
    const localEvidencePath = join(root, 'local-corretto.txt');
    writeFileSync(localEvidencePath, toolchainEvidence(localContract));
    const corpus = join(root, 'corpus');
    mkdirSync(corpus);
    writeFileSync(join(corpus, 'seed'), 'seed');
    const surefire = join(root, 'surefire');
    mkdirSync(surefire);
    const fuzzClass = 'com.soklet.internal.microhttp.RequestParserFuzzTest';
    const fuzzMethod = 'parseIncrementalRequestOnlyRejectsWithDeclaredExceptions';
    const surefireReport = join(surefire, `TEST-${fuzzClass}.xml`);
    const writeSurefire = ({
      className = fuzzClass,
      durationSeconds = localContract.policy.perTargetDurationSeconds + 0.75,
      methodName = fuzzMethod,
    } = {}) => writeFileSync(
      surefireReport,
      `<testsuite name="${className}" time="${durationSeconds}" tests="1" errors="0" failures="0" skipped="0">\n`
        + '<properties>\n'
        + `<property name="jazzer.max_duration" value="${localContract.policy.perTargetDurationSeconds}s"/>\n`
        + '</properties>\n'
        + `<testcase classname="${className}" name="${methodName}(byte[])" time="${durationSeconds}"/>\n`
        + '</testsuite>\n',
    );
    writeSurefire();
    const jazzer = join(root, 'jazzer-junit.jar');
    const jazzerApi = join(root, 'jazzer-api.jar');
    const jazzerEngine = join(root, 'jazzer-engine.jar');
    const jazzerApiBytes = Buffer.from('fixture-jazzer-api', 'utf8');
    const jazzerEngineBytes = Buffer.from('fixture-jazzer-engine', 'utf8');
    const supplementalJazzerSha256 = {
      api: sha256(jazzerApiBytes),
      engine: sha256(jazzerEngineBytes),
    };
    writeFileSync(jazzer, jazzerBytes);
    writeFileSync(jazzerApi, jazzerApiBytes);
    writeFileSync(jazzerEngine, jazzerEngineBytes);
    const targetOutput = join(root, 'target-receipt.json');
    const target = createFuzzTargetReceipt({
      contract: localContract,
      corpusRoot: corpus,
      jazzerApiJarPath: jazzerApi,
      jazzerEngineJarPath: jazzerEngine,
      jazzerJarPath: jazzer,
      outputPath: targetOutput,
      supplementalJazzerSha256,
      surefireRoot: surefire,
      targetId: localContract.policy.targets[0].id,
      toolchainEvidencePath: localEvidencePath,
    });
    assert.equal(target.target.id, localContract.policy.targets[0].id);
    assert.match(target.corpusHash, /^[0-9a-f]{64}$/);
    assert.equal(readFileSync(targetOutput, 'utf8'), canonicalJson(target));
    assertions += 3;
    writeSurefire({ durationSeconds: localContract.policy.perTargetDurationSeconds - 0.25 });
    expectThrows(
      () => createFuzzTargetReceipt({
        contract: localContract,
        corpusRoot: corpus,
        jazzerApiJarPath: jazzerApi,
        jazzerEngineJarPath: jazzerEngine,
        jazzerJarPath: jazzer,
        outputPath: join(root, 'short-target-receipt.json'),
        supplementalJazzerSha256,
        surefireRoot: surefire,
        targetId: localContract.policy.targets[0].id,
        toolchainEvidencePath: localEvidencePath,
      }),
      /active-fuzz interval/u,
      'short fuzz duration',
    );
    writeSurefire({ methodName: 'differentPassingFuzzTarget' });
    expectThrows(
      () => createFuzzTargetReceipt({
        contract: localContract,
        corpusRoot: corpus,
        jazzerApiJarPath: jazzerApi,
        jazzerEngineJarPath: jazzerEngine,
        jazzerJarPath: jazzer,
        outputPath: join(root, 'wrong-target-receipt.json'),
        supplementalJazzerSha256,
        surefireRoot: surefire,
        targetId: localContract.policy.targets[0].id,
        toolchainEvidencePath: localEvidencePath,
      }),
      /testcase does not match registered target/u,
      'wrong passing fuzz target',
    );
    writeSurefire();
    writeFileSync(jazzer, 'changed');
    expectThrows(
      () => createFuzzTargetReceipt({
        contract: localContract,
        corpusRoot: corpus,
        jazzerApiJarPath: jazzerApi,
        jazzerEngineJarPath: jazzerEngine,
        jazzerJarPath: jazzer,
        outputPath: join(root, 'bad-target-receipt.json'),
        supplementalJazzerSha256,
        surefireRoot: surefire,
        targetId: localContract.policy.targets[0].id,
        toolchainEvidencePath: localEvidencePath,
      }),
      /Jazzer JUnit JAR/u,
      'Jazzer digest drift',
    );
    writeFileSync(jazzer, jazzerBytes);
    writeFileSync(jazzerEngine, 'changed');
    expectThrows(
      () => createFuzzTargetReceipt({
        contract: localContract,
        corpusRoot: corpus,
        jazzerApiJarPath: jazzerApi,
        jazzerEngineJarPath: jazzerEngine,
        jazzerJarPath: jazzer,
        outputPath: join(root, 'bad-engine-target-receipt.json'),
        supplementalJazzerSha256,
        surefireRoot: surefire,
        targetId: localContract.policy.targets[0].id,
        toolchainEvidencePath: localEvidencePath,
      }),
      /Jazzer engine JAR/u,
      'Jazzer engine digest drift',
    );

    const receipts = fuzzReceipts(root, fuzz);
    let fuzzState;
    for (const completedAt of times()) {
      const run = collectFuzzRun(fuzz, receipts, completedAt);
      fuzzState = appendHistoryRun({
        candidate: identity,
        contract: fuzz,
        previousState: fuzzState,
        run,
      });
    }
    assert.equal(fuzzState.runs.length, 7);
    assertions++;
    const fuzzEvidence = historyEvidenceFromState(fuzz, fuzzState);
    assert.equal(verifyRole(root, fuzz, fuzzEvidence).gate, fuzz.id);
    assertions++;

    const missingReceipt = join(receipts, `${fuzz.policy.targets.at(-1).id}.json`);
    const savedReceipt = readFileSync(missingReceipt);
    rmSync(missingReceipt);
    expectThrows(
      () => collectFuzzRun(fuzz, receipts, times().at(-1)),
      /receipt set/u,
      'missing fuzz target',
    );
    writeFileSync(missingReceipt, savedReceipt);
    const duplicate = clone(targetReceipt(fuzz, fuzz.policy.targets[1], digest(fuzz.policy.targets[0].id)));
    writeCanonical(join(receipts, `${fuzz.policy.targets[1].id}.json`), duplicate);
    expectThrows(
      () => collectFuzzRun(fuzz, receipts, times().at(-1)),
      /corpus hashes/u,
      'duplicate fuzz corpus hash',
    );

    const wrongIdentity = clone(identity);
    wrongIdentity.candidateTree = '3'.repeat(40);
    expectThrows(
      () => appendHistoryRun({
        candidate: wrongIdentity,
        contract: fuzz,
        previousState: fuzzState,
        run: fuzzState.runs.at(-1),
      }),
      /exact candidate/u,
      'candidate drift in cached history',
    );
    const duplicateDay = clone(fuzzState.runs.at(-1));
    expectThrows(
      () => appendHistoryRun({
        candidate: identity,
        contract: fuzz,
        previousState: fuzzState,
        run: duplicateDay,
      }),
      /registered cadence/u,
      'duplicate history date',
    );

    let soakState;
    const report = soakReport(soak);
    for (const completedAt of times()) {
      const run = createSoakRunFromReport({
        candidate: identity,
        completedAt,
        contract: soak,
        report,
      });
      soakState = appendHistoryRun({
        candidate: identity,
        contract: soak,
        previousState: soakState,
        run,
      });
    }
    assert.equal(soakState.runs[0].scenarios.length, soak.policy.scenarios.length);
    assert.equal(
      soakState.runs[0].scenarios[1].report.resourceDeltas.fileDescriptors,
      0,
      'Negative final deltas are represented as zero growth.',
    );
    assertions += 2;
    assert.equal(
      verifyRole(root, soak, historyEvidenceFromState(soak, soakState)).gate,
      soak.id,
    );
    assertions++;
    expectThrows(
      () => requireApprovedSoakProfile(soak, '0'.repeat(64)),
      /approved history profile SHA-256/u,
      'soak profile authority drift',
    );
    expectThrows(
      () => createSoakRunFromReport({
        candidate: identity,
        completedAt: times()[0],
        contract: soak,
        report: report.replace(`## ${soak.policy.scenarios[0].id}`, '## unexpected'),
      }),
      /missing scenario section/u,
      'missing soak scenario',
    );

    const observation = operationalObservation(operational);
    const operationalEvidence = createOperationalEvidence(
      operational,
      identity,
      observation,
    );
    assert.equal(verifyRole(root, operational, operationalEvidence).gate, operational.id);
    assertions++;
    const partialObservation = clone(observation);
    partialObservation.samples.pop();
    expectThrows(
      () => verifyRole(
        root,
        operational,
        createOperationalEvidence(operational, identity, partialObservation),
      ),
      /incomplete sample window/u,
      'partial operational window',
    );
    const extraObservation = { ...observation, invented: true };
    expectThrows(
      () => createOperationalEvidence(operational, identity, extraObservation),
      /keys/u,
      'operational field drift',
    );

    const invocation = spawnSync(process.execPath, [SCRIPT_PATH], { encoding: 'utf8' });
    assert.equal(invocation.status, 1);
    assert.match(invocation.stderr, /Missing release-history producer mode/u);
    const operationalSelfTest = spawnSync(
      process.execPath,
      [OPERATIONAL_SELF_TEST_PATH],
      { encoding: 'utf8' },
    );
    assert.equal(operationalSelfTest.status, 0, operationalSelfTest.stderr);
    assert.match(operationalSelfTest.stdout, /produce-operational-history self-test PASS/u);
    assertions += 4;

    const workflow = readFileSync(resolve(SCRIPT_DIRECTORY, '../.github/workflows/ci.yml'), 'utf8');
    assert.match(workflow, /^  fuzz-nightly-history:$/m);
    assert.match(workflow, /-Djazzer\.max_duration=300s surefire:test/u);
    assert.doesNotMatch(workflow, /--duration-evidence/u);
    assert.match(workflow, /produce-release-history\.mjs fuzz-target/u);
    assert.match(workflow, /produce-release-history\.mjs fuzz-nightly/u);
    assert.match(workflow, /create-release-harness-bundle\.mjs/u);
    assert.match(workflow, /fuzz-nightly-history-bundle\.json/u);
    assert.match(workflow, /8bdeac017bcd3d9473c9772fac62111c4df830188571def1d001a1b743a62b2f/u);
    assert.match(workflow, /--jazzer-engine-jar/u);
    const fuzzProducerBlock = workflow.match(
      /^  fuzz-nightly:[\s\S]*?(?=^  soak-nightly:)/mu,
    )?.[0];
    const soakProducerBlock = workflow.match(
      /^  soak-nightly:[\s\S]*?(?=^  operational-history:)/mu,
    )?.[0];
    const operationalProducerBlock = workflow.match(
      /^  operational-history:[\s\S]*$/mu,
    )?.[0];
    assert.ok(fuzzProducerBlock !== undefined);
    assert.ok(soakProducerBlock !== undefined);
    assert.ok(operationalProducerBlock !== undefined);
    assert.doesNotMatch(fuzzProducerBlock, /uses: actions\/[^@\s]+@v[0-9]+\b/u);
    assert.doesNotMatch(soakProducerBlock, /uses: actions\/[^@\s]+@v[0-9]+\b/u);
    assert.doesNotMatch(operationalProducerBlock, /uses: actions\/[^@\s]+@v[0-9]+\b/u);
    const fuzzBundleUpload = fuzzProducerBlock.match(
      /      - name: Upload immutable fuzz history bundle\n([\s\S]*?)(?=\n      - name:)/u,
    );
    const soakBundleUpload = soakProducerBlock.match(
      /      - name: Upload immutable soak history bundle\n([\s\S]*?)(?=\n      - name:)/u,
    );
    const operationalBundleUpload = operationalProducerBlock.match(
      /      - name: Upload immutable operational history bundle\n([\s\S]*?)(?=\n      - name:)/u,
    );
    assert.notEqual(fuzzBundleUpload, null);
    assert.notEqual(soakBundleUpload, null);
    assert.notEqual(operationalBundleUpload, null);
    assert.match(
      fuzzBundleUpload[1],
      /^          name: fuzz-nightly-history-\$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}$/m,
    );
    assert.match(
      soakBundleUpload[1],
      /^          name: soak-nightly-history-\$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}$/m,
    );
    assert.match(
      operationalBundleUpload[1],
      /^          name: operational-history-\$\{\{ inputs\.operational_candidate_commit \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}$/m,
    );
    assert.match(
      fuzzBundleUpload[1],
      /^          path: target\/release-history\/fuzz-nightly-history-bundle\.json$/m,
    );
    assert.match(
      soakBundleUpload[1],
      /^          path: target\/release-history\/soak-nightly-history-bundle\.json$/m,
    );
    assert.match(
      operationalBundleUpload[1],
      /^          path: target\/release-history\/operational-history-bundle\.json$/m,
    );
    assert.match(soakProducerBlock, /release-soak-history-\$\{\{ runner\.os \}\}-\$\{\{ github\.sha \}\}-/u);
    assert.match(soakProducerBlock, /produce-release-history\.mjs soak-nightly/u);
    assert.match(soakProducerBlock, /verify-release-history\.mjs soak-nightly/u);
    assert.match(soakProducerBlock, /--gate soak-nightly-history/u);
    assert.match(soakProducerBlock, /soak-nightly-history-bundle\.json/u);
    assert.match(soakProducerBlock, /soak-history-state-\$\{\{ github\.sha \}\}/u);
    assert.match(workflow, /operational_candidate_commit:\n[\s\S]*?type: string/u);
    assert.match(
      operationalProducerBlock,
      /if: github\.event_name == 'workflow_dispatch' && inputs\.operational_candidate_commit != ''/u,
    );
    assert.match(operationalProducerBlock, /runs-on: \[self-hosted, linux, x64\]/u);
    assert.match(operationalProducerBlock, /timeout-minutes: 420/u);
    assert.match(operationalProducerBlock, /ref: \$\{\{ inputs\.operational_candidate_commit \}\}/u);
    assert.match(operationalProducerBlock, /git rev-parse --verify HEAD/u);
    assert.match(operationalProducerBlock, /install-pinned-node-linux-x64\.sh/u);
    assert.match(operationalProducerBlock, /install-pinned-corretto-linux-x64\.sh\n          java/u);
    assert.match(operationalProducerBlock, /install-pinned-maven-linux-x64\.sh/u);
    assert.match(operationalProducerBlock, /\$\{RUNNER_TEMP\}\/operational-node-distribution\.txt/u);
    assert.match(operationalProducerBlock, /\$\{RUNNER_TEMP\}\/operational-java-distribution\.txt/u);
    assert.match(operationalProducerBlock, /\$\{RUNNER_TEMP\}\/operational-maven-distribution\.txt/u);
    assert.match(
      operationalProducerBlock,
      /- name: Retain operational toolchain provenance after clean build/u,
    );
    const operationalBuildIndex = operationalProducerBlock.indexOf(
      'mvn -B -ntp -DskipTests clean package',
    );
    const operationalRetainIndex = operationalProducerBlock.indexOf(
      '- name: Retain operational toolchain provenance after clean build',
    );
    const operationalRunIndex = operationalProducerBlock.indexOf(
      'node scripts/produce-operational-history.mjs run',
    );
    assert.ok(
      operationalBuildIndex >= 0
        && operationalBuildIndex < operationalRetainIndex
        && operationalRetainIndex < operationalRunIndex,
    );
    assert.doesNotMatch(
      operationalProducerBlock.slice(0, operationalRetainIndex),
      /target\/release-history\/operational-raw\/(?:node|java|maven)-distribution\.txt/u,
    );
    assert.match(operationalProducerBlock, /produce-operational-history-self-test\.mjs/u);
    assert.match(operationalProducerBlock, /produce-operational-history\.mjs run/u);
    assert.match(
      operationalProducerBlock,
      /\[\[ "\$GITHUB_SHA" == "\$SOKLET_OPERATIONAL_CANDIDATE_COMMIT" \]\]/u,
    );
    assert.doesNotMatch(
      operationalProducerBlock,
      /--(?:duration|cadence|seconds-per-scenario)/u,
    );
    assert.match(operationalProducerBlock, /produce-release-history\.mjs operational/u);
    assert.match(operationalProducerBlock, /verify-release-history\.mjs operational/u);
    assert.match(operationalProducerBlock, /--gate operational-history/u);
    assert.match(operationalProducerBlock, /operational-history-raw-/u);
    for (const target of fuzz.policy.targets)
      assert.match(workflow, new RegExp(`target_id: ${target.id}\\n`, 'u'));
    assertions += 52 + fuzz.policy.targets.length;
  } finally {
    rmSync(root, { recursive: true });
  }
  console.log(`release history producer self-test PASS assertions=${assertions} gates=3`);
}

run();
