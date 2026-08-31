#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFileSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import net from 'node:net';
import { tmpdir } from 'node:os';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  activeScenarios,
  verifyManifestSet,
} from '../conformance/official/verify.mjs';
import {
  EXPECTED_GATE_EVIDENCE_CONTRACTS,
  assembleReleaseEvidence,
  recordCandidateArtifacts,
  recordGateEvidence,
  recordImportedGateEvidence,
  validateReleaseConfiguration,
  verifyReleaseConformanceEvidence,
} from './release-validation-evidence.mjs';
import { verifyMavenDownstreamPom } from './verify-maven-downstream-pom.mjs';
import { createLoopbackPortReservation } from './reserve-loopback-port.mjs';
import { verifyMatrixClosure } from './verify-release-matrix-closure.mjs';
import {
  canonicalJson,
  importReleaseHarnessEvidence,
  verifyReleaseHarnessConfiguration,
  verifyReleaseHarnessManifestParity,
} from './import-release-harness-evidence.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '..');
const trackedManifestPath = resolve(projectRoot, 'release/release-validation-manifest.json');
const ciWorkflowPath = resolve(projectRoot, '.github/workflows/ci.yml');
const codeqlWorkflowPath = resolve(projectRoot, '.github/workflows/codeql.yml');
const releaseWorkflowPath = resolve(projectRoot, '.github/workflows/release-validation.yml');
const releaseValidatorPath = resolve(projectRoot, 'scripts/validate-release-candidate.sh');
const apiFreezeWrapperPath = resolve(projectRoot, 'scripts/verify-mcp-api-freezes.sh');

function assertExactHostBlock(source, lines, label) {
  const block = lines.join('\n');
  const escapedBlock = block.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
  const matches = source.match(new RegExp(`(?:^|\\n)${escapedBlock}(?=\\n|$)`, 'gu'));
  assert.equal(
    matches?.length ?? 0,
    1,
    `${label} must contain its exact executable block once`,
  );
}
const matrixClosureRegistryPath = resolve(
  projectRoot,
  'release/mcp-conformance-matrix-closure.json',
);
const matrixClosureVerifierPath = resolve(
  projectRoot,
  'scripts/verify-release-matrix-closure.mjs',
);
const matrixClosureSelfTestPath = resolve(
  projectRoot,
  'scripts/verify-release-matrix-closure-self-test.mjs',
);
const versionTransitionInventoryPath = resolve(
  projectRoot,
  'release/version-transition-inventory.json',
);
const versionTransitionVerifierPath = resolve(
  projectRoot,
  'scripts/verify-version-transition-inventory.mjs',
);
const versionTransitionSelfTestPath = resolve(
  projectRoot,
  'scripts/verify-version-transition-inventory-self-test.mjs',
);
const lifecycleBoundHarnessInventoryPath = resolve(
  projectRoot,
  'release/lifecycle-bound-harness-inventory.json',
);
const lifecycleBoundHarnessVerifierPath = resolve(
  projectRoot,
  'scripts/verify-lifecycle-bound-harness-inventory.mjs',
);
const lifecycleBoundHarnessSelfTestPath = resolve(
  projectRoot,
  'scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs',
);
const d1pEvidenceConfigPath = resolve(projectRoot, 'release/d1p-evidence-config.json');
const d1pEvidenceContractPath = resolve(projectRoot, 'release/d1p-evidence-contract.md');
const d1pEvidenceLibraryPath = resolve(projectRoot, 'scripts/d1p-evidence-lib.mjs');
const d1pEvidenceGeneratorPath = resolve(projectRoot, 'scripts/generate-d1p-evidence.mjs');
const d1pEvidenceVerifierPath = resolve(projectRoot, 'scripts/verify-d1p-evidence.mjs');
const d1pEvidenceSelfTestPath = resolve(
  projectRoot,
  'scripts/verify-d1p-evidence-self-test.mjs',
);
const releaseHarnessRegistryPath = resolve(
  projectRoot,
  'release/release-harness-contracts.json',
);
const releaseHarnessBundleBuilderPath = resolve(
  projectRoot,
  'scripts/create-release-harness-bundle.mjs',
);
const releaseHarnessImporterPath = resolve(
  projectRoot,
  'scripts/import-release-harness-evidence.mjs',
);
const releaseHarnessImporterSelfTestPath = resolve(
  projectRoot,
  'scripts/import-release-harness-evidence-self-test.mjs',
);
const releaseProducerSourcePaths = [
  'release/scripts/produce-release-scans-linux-x64.sh',
  'scripts/prepare-codeql-release-report.mjs',
  'scripts/prepare-codeql-release-report-self-test.mjs',
  'scripts/produce-release-benchmarks.mjs',
  'scripts/produce-release-benchmarks-self-test.mjs',
  'scripts/produce-release-history.mjs',
  'scripts/produce-release-history-self-test.mjs',
  'scripts/produce-release-scans.mjs',
  'scripts/produce-release-scans-self-test.mjs',
  'scripts/stage-codeql-release-provenance.mjs',
  'scripts/stage-codeql-release-provenance-self-test.mjs',
  'scripts/verify-runtime-dependency-surface.mjs',
  'scripts/verify-runtime-dependency-surface-self-test.mjs',
].map((path) => resolve(projectRoot, path));
const releaseHistoryVerifierPath = resolve(projectRoot, 'scripts/verify-release-history.mjs');
const releaseScansVerifierPath = resolve(projectRoot, 'scripts/verify-release-scans.mjs');
const releaseBenchmarksVerifierPath = resolve(
  projectRoot,
  'scripts/verify-release-benchmarks.mjs',
);
const loopbackPortReserverPath = resolve(projectRoot, 'scripts/reserve-loopback-port.mjs');
const promotionHelperPath = resolve(projectRoot, 'scripts/release-promotion.mjs');
const promotionWrapperPath = resolve(projectRoot, 'scripts/promote-release-candidate.sh');
const pinnedJavaInstallerPath = resolve(
  projectRoot,
  'release/scripts/install-pinned-corretto-linux-x64.sh',
);
const fixtureRoot = realpathSync(
  mkdtempSync(resolve(tmpdir(), 'soklet-release-validation-')),
);
const candidateCommit = 'a'.repeat(40);
const importedReleaseHarnessGateIds = new Set([
  'fuzz-nightly-history',
  'mcp-benchmarks',
  'operational-history',
  'release-scans',
  'soak-nightly-history',
]);

function canBindLoopback(port) {
  return new Promise((resolveBind) => {
    const server = net.createServer();
    server.once('error', () => resolveBind(false));
    server.listen({ exclusive: true, host: '127.0.0.1', port }, () => {
      server.close(() => resolveBind(true));
    });
  });
}

async function verifyLoopbackPortReservation(outputPath) {
  const reservation = await createLoopbackPortReservation(outputPath);
  try {
    assert.equal(readFileSync(outputPath, 'utf8'), `${reservation.port}\n`);
    const stats = lstatSync(outputPath);
    assert.equal(stats.isFile(), true);
    assert.equal(stats.isSymbolicLink(), false);
    assert.equal(stats.mode & 0o077, 0);
    assert.equal(await canBindLoopback(reservation.port), false);
  } finally {
    await reservation.close();
  }
  assert.equal(await canBindLoopback(reservation.port), true);
}

function fixturePath(...parts) {
  return resolve(fixtureRoot, ...parts);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

try {
  for (const [label, path] of [
    ['matrix-closure registry', matrixClosureRegistryPath],
    ['matrix-closure verifier', matrixClosureVerifierPath],
    ['matrix-closure verifier self-test', matrixClosureSelfTestPath],
    ['version-transition inventory', versionTransitionInventoryPath],
    ['version-transition verifier', versionTransitionVerifierPath],
    ['version-transition verifier self-test', versionTransitionSelfTestPath],
    ['lifecycle-bound harness inventory', lifecycleBoundHarnessInventoryPath],
    ['lifecycle-bound harness verifier', lifecycleBoundHarnessVerifierPath],
    ['lifecycle-bound harness verifier self-test', lifecycleBoundHarnessSelfTestPath],
    ['D1p evidence configuration', d1pEvidenceConfigPath],
    ['D1p evidence contract', d1pEvidenceContractPath],
    ['D1p evidence library', d1pEvidenceLibraryPath],
    ['D1p evidence generator', d1pEvidenceGeneratorPath],
    ['D1p evidence verifier', d1pEvidenceVerifierPath],
    ['D1p evidence verifier self-test', d1pEvidenceSelfTestPath],
    ['release-harness registry', releaseHarnessRegistryPath],
    ['release-harness bundle builder', releaseHarnessBundleBuilderPath],
    ['release-harness importer', releaseHarnessImporterPath],
    ['release-harness importer self-test', releaseHarnessImporterSelfTestPath],
    ...releaseProducerSourcePaths.map((path) => ['release producer source', path]),
    ['release-history verifier', releaseHistoryVerifierPath],
    ['release-scans verifier', releaseScansVerifierPath],
    ['release-benchmarks verifier', releaseBenchmarksVerifierPath],
  ]) {
    const stats = lstatSync(path);
    assert.equal(stats.isFile(), true, `${label} must be a regular file`);
    assert.equal(stats.isSymbolicLink(), false, `${label} must not be a symlink`);
  }
  const matrixClosureSelfTest = spawnSync(
    process.execPath,
    [matrixClosureSelfTestPath],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(
    matrixClosureSelfTest.status,
    0,
    `Matrix-closure verifier self-test failed: ${matrixClosureSelfTest.error?.message
      ?? matrixClosureSelfTest.stderr ?? matrixClosureSelfTest.stdout}`,
  );
  const versionTransitionSelfTest = spawnSync(
    process.execPath,
    [versionTransitionSelfTestPath],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(
    versionTransitionSelfTest.status,
    0,
    `Version-transition verifier self-test failed: ${versionTransitionSelfTest.error?.message
      ?? versionTransitionSelfTest.stderr ?? versionTransitionSelfTest.stdout}`,
  );
  const lifecycleBoundHarnessSelfTest = spawnSync(
    process.execPath,
    [lifecycleBoundHarnessSelfTestPath],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(
    lifecycleBoundHarnessSelfTest.status,
    0,
    `Lifecycle-bound harness verifier self-test failed: ${
      lifecycleBoundHarnessSelfTest.error?.message
      ?? lifecycleBoundHarnessSelfTest.stderr
      ?? lifecycleBoundHarnessSelfTest.stdout}`,
  );
  const d1pEvidenceSelfTest = spawnSync(
    process.execPath,
    [d1pEvidenceSelfTestPath],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(
    d1pEvidenceSelfTest.status,
    0,
    `D1p evidence verifier self-test failed: ${d1pEvidenceSelfTest.error?.message
      ?? d1pEvidenceSelfTest.stderr ?? d1pEvidenceSelfTest.stdout}`,
  );
  const releaseHarnessImporterSelfTest = spawnSync(
    process.execPath,
    [releaseHarnessImporterSelfTestPath],
    { cwd: projectRoot, encoding: 'utf8' },
  );
  assert.equal(
    releaseHarnessImporterSelfTest.status,
    0,
    `Release-harness importer self-test failed: ${releaseHarnessImporterSelfTest.error?.message
      ?? releaseHarnessImporterSelfTest.stderr ?? releaseHarnessImporterSelfTest.stdout}`,
  );

  await verifyLoopbackPortReservation(fixturePath('reserved-loopback-port.txt'));

  const tracked = validateReleaseConfiguration(trackedManifestPath);
  const releaseHarnessConfiguration = verifyReleaseHarnessConfiguration();
  assert.equal(
    verifyReleaseHarnessManifestParity(releaseHarnessConfiguration, trackedManifestPath),
    true,
  );
  assert.deepEqual([...releaseHarnessConfiguration.contracts.keys()], [
    'fuzz-nightly-history',
    'mcp-benchmarks',
    'operational-history',
    'release-scans',
    'soak-nightly-history',
  ]);
  assert.equal(tracked.candidate.version, '4.0.0');
  assert.equal(tracked.value.formatVersion, 2);
  assert.equal(tracked.gates.length, 29);
  assert.equal(tracked.toolchains.java.vendorVersion, 'Corretto-17.0.20.8.1');
  assert.deepEqual(tracked.toolchains.coreJdk21, {
    archive: 'amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
    archiveSha256: 'f79824540cef882da0cdf1369f9d1d69afc14b5a9bc3a771fd5bb795793ce2f2',
    distribution: 'corretto',
    distributionUrl:
      'https://corretto.aws/downloads/resources/21.0.12.9.1/amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
    runtimeVersion: '21.0.12.1+9-LTS',
    vendorVersion: 'Corretto-21.0.12.9.1',
    version: '21.0.12.1',
  });
  assert.equal(tracked.toolchains.toystoreJava.vendorVersion, 'Corretto-25.0.4.7.1');
  assert.equal(tracked.promotion.helper.path, 'scripts/release-promotion.mjs');
  assert.equal(tracked.promotion.wrapper.path, 'scripts/promote-release-candidate.sh');
  assert.equal(tracked.gates.filter(({ status }) => status === 'READY').length, 18);
  assert.equal(
    tracked.gates.filter(({ status }) => status === 'BLOCKED_HARNESS_MISSING').length,
    5,
  );
  assert.equal(
    tracked.gates.filter(
      ({ status }) => status === 'BLOCKED_UNCOMMITTED_LOCAL_MIGRATION',
    ).length,
    6,
  );
  for (const gateId of [
    'barebones-app',
    'soklet-servlet-javax',
    'soklet-servlet-jakarta',
    'toystore-app',
    'soklet-otel',
    'soklet-website',
  ]) {
    const gate = tracked.gates.find(({ id }) => id === gateId);
    assert.equal(gate.status, 'BLOCKED_UNCOMMITTED_LOCAL_MIGRATION');
  }
  for (const gateId of [
    'fuzz-nightly-history',
    'soak-nightly-history',
    'operational-history',
    'release-scans',
    'mcp-benchmarks',
  ]) {
    const gate = tracked.gates.find(({ id }) => id === gateId);
    assert.equal(gate.status, 'BLOCKED_HARNESS_MISSING');
  }
  for (const gateId of [
    'core-jdk-21',
    'static-analysis',
    'spotbugs',
    'matrix-closure',
  ]) {
    const gate = tracked.gates.find(({ id }) => id === gateId);
    assert.equal(gate.status, 'READY');
    assert.equal(gate.reason, '');
  }
  assert.deepEqual(
    tracked.gates.map(({ id }) => id),
    Object.keys(EXPECTED_GATE_EVIDENCE_CONTRACTS),
  );
  assert.equal(
    EXPECTED_GATE_EVIDENCE_CONTRACTS['mcp-benchmarks'].expectation,
    'JMH_JSON_351_COMPARISON_AND_SCHEMA_400_BASELINE_RECORDED_WITH_SIGNOFF',
  );
  for (const gate of tracked.gates) {
    const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gate.id];
    assert.equal(gate.evidenceContract, contract.contractId);
    assert.equal(gate.toolchain, contract.toolchain);
  }
  const trackedMatrixClosureGate = tracked.gates.find(
    ({ id }) => id === 'matrix-closure',
  );
  assert.equal(trackedMatrixClosureGate.artifactIdentity, 'mcp:conformance-matrix-closure');
  assert.equal(trackedMatrixClosureGate.status, 'READY');
  assert.equal(trackedMatrixClosureGate.reason, '');
  assert.deepEqual(EXPECTED_GATE_EVIDENCE_CONTRACTS['matrix-closure'], {
    command: 'node scripts/verify-release-matrix-closure.mjs',
    contractId: 'soklet.release.matrix-closure.v1',
    expectation: 'ZERO_UNRESOLVED_IN_SCOPE_MATRIX_ROWS',
    profile: 'release',
    roles: [{
      candidateArtifact: null,
      fileName: 'matrix-closure.json',
      mediaType: 'application/json',
      role: 'matrix-report',
      type: 'FILE',
    }],
    toolchain: 'nodePin',
  });
  const trackedToyStoreGate = tracked.gates.find(({ id }) => id === 'toystore-app');
  assert.equal(trackedToyStoreGate.commit, '209781472b2d308cbc5538f2a7f956bc97b399b7');
  assert.equal(
    trackedToyStoreGate.artifactIdentity,
    'com.soklet.toystore:toystore:1.0.0',
  );
  const trackedBarebonesGate = tracked.gates.find(({ id }) => id === 'barebones-app');
  assert.match(trackedBarebonesGate.reason, /two local source-tree changes are uncommitted/);
  for (const gateId of ['soklet-servlet-javax', 'soklet-servlet-jakarta']) {
    const gate = tracked.gates.find(({ id }) => id === gateId);
    assert.match(gate.reason, /uncommitted local POM/);
    assert.equal(gate.defaultArtifactIdentity, 'com.soklet:soklet:3.1.1');
    assert.equal(
      gate.defaultArtifactSha256,
      'a7acd26b5a8933726615719e8d9d766feba6d0ebdb32939fa8ef1eba8094e7a4',
    );
  }
  const trackedLocalizationGate = tracked.gates.find(
    ({ id }) => id === 'candidate-localization',
  );
  assert.equal(trackedLocalizationGate.access, 'LOCAL_CHECKOUT');
  assert.equal(
    trackedLocalizationGate.artifactIdentity,
    'verification/localization/generic-provider',
  );
  assert.equal(trackedLocalizationGate.commit, null);
  assert.equal(trackedLocalizationGate.repository, null);
  assert.throws(
    () => validateReleaseConfiguration(trackedManifestPath, { requireReady: true }),
    /barebones-app=BLOCKED_UNCOMMITTED_LOCAL_MIGRATION/,
  );
  for (const [gateId, directory] of [
    ['typescript-interop', 'typescript'],
    ['go-interop', 'go'],
  ]) {
    const gate = tracked.gates.find(({ id }) => id === gateId);
    const hook = readFileSync(
      resolve(projectRoot, 'verification/interoperability', directory, 'verify.sh'),
      'utf8',
    );
    for (const pin of [gate.artifactIdentity, gate.artifactChecksum, gate.commit]) {
      assert.equal(
        hook.split(pin).length - 1,
        1,
        `${gateId} hook must contain its exact manifest pin once`,
      );
    }
  }

  const releaseWorkflow = readFileSync(releaseWorkflowPath, 'utf8');
  assert.match(releaseWorkflow, /runs-on: ubuntu-24\.04/);
  assert.doesNotMatch(releaseWorkflow, /ubuntu-latest/);
  assert.doesNotMatch(releaseWorkflow, /actions\/setup-java/);
  assert.match(releaseWorkflow, /^\s+fetch-depth: 0$/m);
  assert.match(
    releaseWorkflow,
    /SOKLET_CANDIDATE_COMMIT: \$\{\{ inputs\.candidate_commit \}\}/,
  );
  const releaseScansJob = releaseWorkflow.match(
    /\n  release-scans:\n([\s\S]*?)\n  mcp-benchmarks:/,
  );
  const benchmarkJob = releaseWorkflow.match(
    /\n  mcp-benchmarks:\n([\s\S]*?)\n  validate:/,
  );
  assert.notEqual(releaseScansJob, null);
  assert.notEqual(benchmarkJob, null);
  for (const [label, job] of [
    ['release-scans', releaseScansJob[1]],
    ['mcp-benchmarks', benchmarkJob[1]],
  ]) {
    assert.match(
      job,
      /\[\[ "\$GITHUB_SHA" == "\$SOKLET_CANDIDATE_COMMIT" \]\]/,
      `${label} must bind the executing workflow revision to the candidate`,
    );
  }

  const codeqlWorkflow = readFileSync(codeqlWorkflowPath, 'utf8');
  assert.match(codeqlWorkflow, /runs-on: ubuntu-24\.04/);
  assert.doesNotMatch(codeqlWorkflow, /ubuntu-latest/);
  assert.match(
    codeqlWorkflow,
    /ref: \$\{\{ inputs\.candidate_commit \|\| github\.sha \}\}\n\n      - name: Verify exact release candidate/,
  );
  assert.match(
    codeqlWorkflow,
    /install-pinned-maven-linux-x64\.sh[\s\S]*?codeql-maven-distribution\.txt/,
  );
  const candidateCodeqlGuard = codeqlWorkflow.match(
    /      - name: Verify exact release candidate\n([\s\S]*?)\n      - name: Install checksum-pinned Corretto 21/,
  );
  assert.notEqual(candidateCodeqlGuard, null);
  assert.match(
    candidateCodeqlGuard[1],
    /\[\[ "\$GITHUB_SHA" == "\$SOKLET_CANDIDATE_COMMIT" \]\]/,
  );

  const ciWorkflow = readFileSync(ciWorkflowPath, 'utf8');
  const apiDiffJob = ciWorkflow.match(
    /\n  api-diff:\n([\s\S]*?)\n  fuzz-regression:/,
  );
  assert.notEqual(apiDiffJob, null);
  const apiFreezeCiBlock = [
    '      - name: Verify reviewed API incompatibilities and MCP freezes',
    '        run: scripts/verify-mcp-api-freezes.sh',
  ];
  assertExactHostBlock(apiDiffJob[1], apiFreezeCiBlock, 'ordinary CI API-freeze host');
  assert.doesNotMatch(apiDiffJob[1], /fetch-depth:|^\s+ref:/mu);
  for (const releaseOnlyCommand of [
    /verify-d1p-evidence/u,
    /verify-lifecycle-bound-harness-inventory/u,
    /verify-version-transition-inventory/u,
  ])
    assert.doesNotMatch(apiDiffJob[1], releaseOnlyCommand);
  for (const path of [
    'release/d1p-evidence-config.json',
    'release/d1p-tracked-blobs.sha256',
    'release/d1p-canonical-semantic-digests.json',
    'release/d1p-public-cutover-manifest.json',
  ]) {
    assert.equal(
      ciWorkflow.includes(path),
      false,
      `${path} must not be uploaded by ordinary CI`,
    );
  }

  const apiFreezeWrapper = readFileSync(apiFreezeWrapperPath, 'utf8');
  assert.doesNotMatch(apiFreezeWrapper, /verify-d1p-evidence/u);
  assert.doesNotMatch(apiFreezeWrapper, /--scope (?:preparation|tracked)/u);
  assert.match(
    releaseWorkflow,
    /run: scripts\/validate-release-candidate\.sh "\$SOKLET_CANDIDATE_COMMIT"/,
  );
  assert.doesNotMatch(
    releaseWorkflow,
    /run:[^\n]*\$\{\{\s*inputs\.candidate_commit\s*\}\}/,
  );
  const validateJob = releaseWorkflow.match(/\n  validate:\n([\s\S]*)$/);
  assert.notEqual(validateJob, null);
  const toyStoreJavaInstall = validateJob[1].indexOf(
    'install-pinned-corretto-linux-x64.sh\n          toystoreJava',
  );
  const candidateJavaInstall = validateJob[1].indexOf(
    'install-pinned-corretto-linux-x64.sh\n          java',
  );
  const coreJdk21Install = validateJob[1].indexOf(
    'install-pinned-corretto-linux-x64.sh\n          coreJdk21',
  );
  assert.ok(toyStoreJavaInstall >= 0);
  assert.ok(coreJdk21Install > toyStoreJavaInstall);
  assert.ok(candidateJavaInstall > coreJdk21Install);
  assert.match(
    releaseWorkflow,
    /SOKLET_RELEASE_CORE_JDK_21_DISTRIBUTION_EVIDENCE: \$\{\{ runner\.temp \}\}\/release-validation-core-jdk-21-distribution\.txt/,
  );

  const releaseValidator = readFileSync(releaseValidatorPath, 'utf8');
  const loopbackPortReserver = readFileSync(loopbackPortReserverPath, 'utf8');
  assert.match(releaseValidator, /^set -euo pipefail$/m);
  assert.match(releaseValidator, /assert_ready_gate_has_dispatch\(\)/);
  assert.match(
    releaseValidator,
    /gate \$gate_id is READY but has no release-validator dispatch/,
  );
  assert.match(
    releaseValidator,
    /d1p_evidence_config="\$project_root\/release\/d1p-evidence-config\.json"/,
  );
  assert.match(
    releaseValidator,
    /d1p_evidence_verifier="\$project_root\/scripts\/verify-d1p-evidence\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /d1p_evidence_self_test="\$project_root\/scripts\/verify-d1p-evidence-self-test\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /for d1p_evidence_source in[\s\S]*?\[\[ -f "\$d1p_evidence_source" && ! -L "\$d1p_evidence_source" \]\]/,
  );
  assert.match(releaseValidator, /configured_gate_count" -eq 29/);
  assert.match(
    releaseValidator,
    /fuzz-nightly-history\|soak-smoke\|soak-nightly-history\|release-soak\|[\s\\]*localization-fleet\|operational-history\|release-scans\|mcp-benchmarks\|matrix-closure\|/,
  );
  assert.match(
    releaseValidator,
    /version_transition_inventory="\$project_root\/release\/version-transition-inventory\.json"/,
  );
  assert.match(
    releaseValidator,
    /version_transition_verifier="\$project_root\/scripts\/verify-version-transition-inventory\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /version_transition_self_test="\$project_root\/scripts\/verify-version-transition-inventory-self-test\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /for version_transition_source in[\s\\\n]+"\$version_transition_inventory" "\$version_transition_verifier"[\s\\\n]+"\$version_transition_self_test"; do[\s\S]*?\[\[ -f "\$version_transition_source" && ! -L "\$version_transition_source" \]\]/,
  );
  assert.match(
    releaseValidator,
    /lifecycle_bound_harness_inventory="\$project_root\/release\/lifecycle-bound-harness-inventory\.json"/,
  );
  assert.match(
    releaseValidator,
    /lifecycle_bound_harness_verifier="\$project_root\/scripts\/verify-lifecycle-bound-harness-inventory\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /lifecycle_bound_harness_self_test="\$project_root\/scripts\/verify-lifecycle-bound-harness-inventory-self-test\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /for lifecycle_bound_harness_source in[\s\S]*?\[\[ -f "\$lifecycle_bound_harness_source"[\s\\\n]+&& ! -L "\$lifecycle_bound_harness_source" \]\]/,
  );
  assert.match(
    releaseValidator,
    /release_harness_registry="\$project_root\/release\/release-harness-contracts\.json"/,
  );
  assert.match(
    releaseValidator,
    /release_harness_bundle_builder="\$project_root\/scripts\/create-release-harness-bundle\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /release_harness_importer="\$project_root\/scripts\/import-release-harness-evidence\.mjs"/,
  );
  assert.match(
    releaseValidator,
    /release_harness_importer_self_test="\$project_root\/scripts\/import-release-harness-evidence-self-test\.mjs"/,
  );
  for (const source of [
    'prepare-codeql-release-report.mjs',
    'prepare-codeql-release-report-self-test.mjs',
    'produce-release-benchmarks.mjs',
    'produce-release-benchmarks-self-test.mjs',
    'produce-release-history.mjs',
    'produce-release-history-self-test.mjs',
    'produce-release-scans.mjs',
    'produce-release-scans-self-test.mjs',
    'stage-codeql-release-provenance.mjs',
    'stage-codeql-release-provenance-self-test.mjs',
    'verify-runtime-dependency-surface.mjs',
    'verify-runtime-dependency-surface-self-test.mjs',
    'verify-release-history.mjs',
    'verify-release-scans.mjs',
    'verify-release-benchmarks.mjs',
  ]) {
    assert.match(
      releaseValidator,
      new RegExp(`\\$project_root\\/scripts\\/${source.replaceAll('.', '\\.')}`),
    );
  }
  assert.match(
    releaseValidator,
    /\$project_root\/release\/scripts\/produce-release-scans-linux-x64\.sh/,
  );
  assert.match(
    releaseValidator,
    /for release_harness_source in[\s\S]*?\[\[ -f "\$release_harness_source" && ! -L "\$release_harness_source" \]\]/,
  );
  const harnessConfigIndex = releaseValidator.indexOf(
    'node "$release_harness_importer" --verify-config',
  );
  const harnessSelfTestIndex = releaseValidator.indexOf(
    'node "$release_harness_importer_self_test"',
  );
  const firstEvidenceRecordIndex = releaseValidator.indexOf(
    'node "$evidence_helper" record-gate',
  );
  assert.ok(harnessConfigIndex >= 0);
  assert.ok(harnessConfigIndex < harnessSelfTestIndex);
  assert.ok(harnessSelfTestIndex < firstEvidenceRecordIndex);
  for (const selfTestVariable of [
    'release_history_producer_self_test',
    'release_scans_codeql_preparer_self_test',
    'release_scans_codeql_provenance_self_test',
    'release_scans_runtime_surface_self_test',
    'release_scans_producer_self_test',
    'release_benchmarks_producer_self_test',
  ]) {
    assert.match(releaseValidator, new RegExp(`node "\\$${selfTestVariable}"`));
  }
  const importedHarnessFunction = releaseValidator.match(
    /\nrun_imported_release_harness\(\) \{\n([\s\S]*?)\n\}\n\nrun_isolated_install\(\)/,
  );
  assert.notEqual(importedHarnessFunction, null);
  assert.match(importedHarnessFunction[1], /source_bundle=\$\{!bundle_environment:-\}/);
  assert.match(importedHarnessFunction[1], /--candidate-root "\$project_root"/);
  assert.match(importedHarnessFunction[1], /--bundle "\$retained_bundle"/);
  assert.match(importedHarnessFunction[1], /--output "\$imported_receipt"/);
  assert.match(importedHarnessFunction[1], /record-imported-gate/);
  for (const [gateId, environment] of [
    ['fuzz-nightly-history', 'SOKLET_RELEASE_FUZZ_NIGHTLY_HISTORY_BUNDLE'],
    ['soak-nightly-history', 'SOKLET_RELEASE_SOAK_NIGHTLY_HISTORY_BUNDLE'],
    ['operational-history', 'SOKLET_RELEASE_OPERATIONAL_HISTORY_BUNDLE'],
    ['release-scans', 'SOKLET_RELEASE_SCANS_BUNDLE'],
    ['mcp-benchmarks', 'SOKLET_RELEASE_MCP_BENCHMARKS_BUNDLE'],
  ]) {
    assert.equal(
      releaseValidator.match(new RegExp(
        `^run_imported_release_harness \\\\\\n\\t${gateId} ${environment}$`,
        'gm',
      ))?.length,
      1,
    );
  }
  const candidateBuildBlock = releaseValidator.match(
    /\nbuild_log="\$temporary_directory\/candidate-build\.log"\n\{\n([\s\S]*?)\n\} 2>&1 \| tee "\$build_log"/,
  );
  assert.notEqual(candidateBuildBlock, null);
  const candidateBuildVerificationBlock = [
    '\tnode "$version_transition_self_test"',
    '\tnode "$version_transition_verifier" --stage final',
    '\tnode "$lifecycle_bound_harness_self_test"',
    '\tnode "$lifecycle_bound_harness_verifier"',
    '\tnode "$d1p_evidence_self_test"',
    '\tmvn -B -ntp -Dgpg.skip=true clean verify',
  ];
  assertExactHostBlock(
    candidateBuildBlock[1],
    candidateBuildVerificationBlock,
    'candidate-build inventory and D1p host',
  );
  assert.throws(
    () => assertExactHostBlock(
      candidateBuildBlock[1].replace(
        '\tnode "$d1p_evidence_self_test"',
        '\techo node "$d1p_evidence_self_test"',
      ),
      candidateBuildVerificationBlock,
      'mutated candidate-build D1p host',
    ),
    /exact executable block once/,
  );
  assert.equal(
    candidateBuildBlock[1].match(/node "\$version_transition_self_test"/g)?.length,
    1,
  );
  assert.equal(
    candidateBuildBlock[1].match(/node "\$version_transition_verifier" --stage final/g)
      ?.length,
    1,
  );
  assert.equal(
    candidateBuildBlock[1].match(/node "\$lifecycle_bound_harness_self_test"/g)?.length,
    1,
  );
  assert.equal(
    candidateBuildBlock[1].match(/node "\$lifecycle_bound_harness_verifier"/g)?.length,
    1,
  );
  assert.equal(
    candidateBuildBlock[1].match(/node "\$d1p_evidence_self_test"/g)?.length,
    1,
  );
  assert.equal(
    candidateBuildBlock[1].match(/mvn -B -ntp -Dgpg\.skip=true clean verify/g)?.length,
    1,
  );
  assert.ok(
    candidateBuildBlock[1].indexOf('node "$version_transition_self_test"')
      < candidateBuildBlock[1].indexOf(
        'node "$version_transition_verifier" --stage final',
      ),
  );
  assert.ok(
    candidateBuildBlock[1].indexOf(
      'node "$version_transition_verifier" --stage final',
    ) < candidateBuildBlock[1].indexOf('node "$lifecycle_bound_harness_self_test"'),
  );
  assert.ok(
    candidateBuildBlock[1].indexOf('node "$lifecycle_bound_harness_self_test"')
      < candidateBuildBlock[1].indexOf('node "$lifecycle_bound_harness_verifier"'),
  );
  assert.ok(
    candidateBuildBlock[1].indexOf('node "$lifecycle_bound_harness_verifier"')
      < candidateBuildBlock[1].indexOf('node "$d1p_evidence_self_test"'),
  );
  assert.ok(
    candidateBuildBlock[1].indexOf('node "$d1p_evidence_self_test"')
      < candidateBuildBlock[1].indexOf('mvn -B -ntp -Dgpg.skip=true clean verify'),
  );
  const apiFreezeFunction = releaseValidator.match(
    /\nrun_api_freeze\(\) \{\n([\s\S]*?)\n\}\n\nrun_candidate_javadocs\(\)/,
  );
  assert.notEqual(apiFreezeFunction, null);
  const releaseD1pBlock = [
    '\t\tenv JAVA_HOME="$core_java_home" PATH="$core_java_home/bin:$PATH" \\',
    '\t\t\tscripts/verify-mcp-api-freezes.sh',
    '\t\tnode scripts/verify-d1p-evidence.mjs --mode candidate --scope preparation',
    '\t\tnode scripts/verify-d1p-evidence.mjs --mode candidate --scope tracked',
  ];
  assertExactHostBlock(apiFreezeFunction[1], releaseD1pBlock, 'release API-freeze D1p host');
  assert.throws(
    () => assertExactHostBlock(
      apiFreezeFunction[1].replace(releaseD1pBlock[2], `\t\techo ${releaseD1pBlock[2].trim()}`),
      releaseD1pBlock,
      'mutated release API-freeze D1p host',
    ),
    /exact executable block once/,
  );
  assert.throws(
    () => assertExactHostBlock(
      apiFreezeFunction[1].replace(releaseD1pBlock[3], `${releaseD1pBlock[3]} || true`),
      releaseD1pBlock,
      'suffix-neutralized release API-freeze D1p host',
    ),
    /exact executable block once/,
  );
  assert.throws(
    () => assertExactHostBlock(
      apiFreezeFunction[1].replace(
        `${releaseD1pBlock[2]}\n${releaseD1pBlock[3]}`,
        `${releaseD1pBlock[3]}\n${releaseD1pBlock[2]}`,
      ),
      releaseD1pBlock,
      'reordered release API-freeze D1p host',
    ),
    /exact executable block once/,
  );
  assert.equal(
    apiFreezeFunction[1].match(/scripts\/verify-mcp-api-freezes\.sh/g)?.length,
    1,
  );
  assert.equal(
    apiFreezeFunction[1].match(
      /node scripts\/verify-d1p-evidence\.mjs --mode candidate --scope preparation/g,
    )?.length,
    1,
  );
  assert.equal(
    apiFreezeFunction[1].match(
      /node scripts\/verify-d1p-evidence\.mjs --mode candidate --scope tracked/g,
    )?.length,
    1,
  );
  assert.ok(
    apiFreezeFunction[1].indexOf('scripts/verify-mcp-api-freezes.sh')
      < apiFreezeFunction[1].indexOf(
        'node scripts/verify-d1p-evidence.mjs --mode candidate --scope preparation',
      ),
  );
  assert.ok(
    apiFreezeFunction[1].indexOf(
      'node scripts/verify-d1p-evidence.mjs --mode candidate --scope preparation',
    )
      < apiFreezeFunction[1].indexOf(
        'node scripts/verify-d1p-evidence.mjs --mode candidate --scope tracked',
      ),
  );
  const isolatedInstallFunction = releaseValidator.match(
    /\nrun_isolated_install\(\) \{\n([\s\S]*?)\n\}\n\nrun_core_jdk_21\(\)/,
  );
  assert.notEqual(isolatedInstallFunction, null);
  assert.equal(
    isolatedInstallFunction[1].match(
      /node "\$version_transition_verifier" --stage final/g,
    )?.length,
    1,
  );
  assert.ok(
    isolatedInstallFunction[1].indexOf(
      'node "$version_transition_verifier" --stage final',
    ) < isolatedInstallFunction[1].indexOf('mvn -B -ntp "$install_file_goal"'),
  );
  assert.match(
    isolatedInstallFunction[1],
    /\} 2>&1 \| tee "\$install_log"/,
  );
  assert.equal(
    releaseValidator.match(
      /node "\$version_transition_verifier" --stage final/g,
    )?.length,
    2,
  );
  assert.doesNotMatch(releaseValidator, /record_gate version-transition/);
  const matrixClosureFunction = releaseValidator.match(
    /\nrun_matrix_closure\(\) \{\n([\s\S]*?)\n\}\n\nrun_candidate_conformance\(\)/,
  );
  assert.notEqual(matrixClosureFunction, null);
  assert.match(
    matrixClosureFunction[1],
    /release\/mcp-conformance-matrix-closure\.json/,
  );
  assert.match(
    matrixClosureFunction[1],
    /scripts\/verify-release-matrix-closure\.mjs/,
  );
  assert.match(
    matrixClosureFunction[1],
    /scripts\/verify-release-matrix-closure-self-test\.mjs/,
  );
  assert.match(
    matrixClosureFunction[1],
    /for source in "\$registry" "\$verifier" "\$verifier_self_test"/,
  );
  assert.match(
    matrixClosureFunction[1],
    /\[\[ -f "\$source" && ! -L "\$source" \]\]/,
  );
  assert.match(
    matrixClosureFunction[1],
    /git ls-files --error-unmatch "\$relative"/,
  );
  assert.match(
    matrixClosureFunction[1],
    /local report="\$raw_root\/matrix-closure\.json"/,
  );
  assert.match(
    matrixClosureFunction[1],
    /node scripts\/verify-release-matrix-closure\.mjs > "\$report"/,
  );
  assert.match(
    matrixClosureFunction[1],
    /record_gate matrix-closure "matrix-report=\$report"/,
  );
  assert.ok(
    matrixClosureFunction[1].indexOf(
      'node scripts/verify-release-matrix-closure.mjs > "$report"',
    )
      < matrixClosureFunction[1].indexOf(
        'record_gate matrix-closure "matrix-report=$report"',
      ),
  );
  assert.equal(releaseValidator.match(/^run_matrix_closure$/gm)?.length, 1);
  const localizationFleetInvocation = releaseValidator.indexOf('\nrun_localization_fleet\n');
  const matrixClosureInvocation = releaseValidator.indexOf('\nrun_matrix_closure\n');
  const candidateConformanceInvocation = releaseValidator.indexOf(
    '\nrun_candidate_conformance\n',
  );
  assert.ok(localizationFleetInvocation >= 0);
  assert.ok(matrixClosureInvocation > localizationFleetInvocation);
  assert.ok(candidateConformanceInvocation > matrixClosureInvocation);
  assert.match(
    releaseValidator,
    /"\$surefire_verifier" "\$project_root\/target\/surefire-reports"[\s\\\n]+candidate-build candidate/,
  );
  assert.match(
    releaseValidator,
    /"build-log=\$evidence_root\/candidate-build\.log"[\s\\\n]+"surefire-reports=\$candidate_build_surefire_reports"/,
  );
  assert.match(
    releaseValidator,
    /record_gate core-jdk-25[\s\\\n]+"build-log=\$log"[\s\\\n]+"java-distribution=\$toystore_java_distribution_evidence"[\s\\\n]+"surefire-reports=\$reports"/,
  );
  assert.match(
    releaseValidator,
    /record_gate core-jdk-21[\s\\\n]+"build-log=\$log"[\s\\\n]+"java-distribution=\$core_jdk_21_distribution_evidence"[\s\\\n]+"surefire-reports=\$reports"/,
  );
  assert.match(
    releaseValidator,
    /run_static_analysis\(\)[\s\S]*?-Pstatic-analysis clean compile[\s\S]*?record_gate static-analysis[\s\\\n]+"analysis-log=\$log"[\s\\\n]+"java-distribution=\$core_jdk_21_distribution_evidence"/,
  );
  assert.match(
    releaseValidator,
    /run_spotbugs\(\)[\s\S]*?-Pspotbugs -DskipTests[\s\\\n]+clean compile spotbugs:check[\s\S]*?record_gate spotbugs[\s\\\n]+"spotbugs-log=\$log"[\s\\\n]+"java-distribution=\$core_jdk_21_distribution_evidence"[\s\\\n]+"spotbugs-report=\$report"/,
  );
  assert.match(
    releaseValidator,
    /export SOKLET_EVIDENCE_CORE_JDK_21_VERSION=\$actual_core_jdk_21_version/,
  );
  assert.match(
    releaseValidator,
    /-Dtest=McpPublicJavadocTests[\s\\\n]+clean package javadoc:javadoc/,
  );
  assert.match(
    releaseValidator,
    /"\$surefire_verifier" "\$checkout\/target\/surefire-reports"[\s\\\n]+candidate-javadocs candidate/,
  );
  assert.match(
    releaseValidator,
    /"apidocs=\$apidocs"[\s\\\n]+"surefire-reports=\$reports"/,
  );
  assert.doesNotMatch(
    releaseValidator,
    /clone_pinned_gate candidate-localization/,
  );
  assert.match(
    releaseValidator,
    /verification\/localization\/verify\.sh "\$candidate_jar"/,
  );
  const candidateOnlyBranch = releaseValidator.match(
    /\tif \[\[ "\$gate_id" == "toystore-app" \|\| "\$gate_id" == "soklet-otel" \]\]; then\n([\s\S]*?)\n\tfi\n/,
  );
  assert.notEqual(candidateOnlyBranch, null);
  assert.equal(candidateOnlyBranch[1].match(/clean verify/g)?.length, 1);
  assert.match(candidateOnlyBranch[1], /downstream_java_home=\$core_java_home/);
  assert.match(candidateOnlyBranch[1], /downstream_java_home=\$toystore_java_home/);
  assert.match(candidateOnlyBranch[1], /JAVA_HOME="\$downstream_java_home"/);
  assert.match(candidateOnlyBranch[1], /-DfailIfNoTests=true/);
  assert.match(candidateOnlyBranch[1], /-D"\$version_property"="\$candidate_version"/);
  assert.match(candidateOnlyBranch[1], /"\$surefire_verifier" "\$surefire_reports"/);
  assert.match(
    candidateOnlyBranch[1],
    /"java-distribution=\$toystore_java_distribution_evidence"/,
  );
  assert.match(candidateOnlyBranch[1], /\n\t\treturn$/);
  for (const gateId of ['toystore-app', 'soklet-otel']) {
    assert.equal(
      releaseValidator.match(new RegExp(`^run_maven_downstream ${gateId}$`, 'gm'))?.length,
      1,
    );
  }
  assert.match(
    releaseValidator,
    /record_gate "\$gate_id"[\s\\\n]+"interop-log=\$log" "candidate-main-jar=\$candidate_jar"/,
  );
  assert.match(
    releaseValidator,
    /verify-maven-downstream-pom\.mjs/,
  );
  assert.match(
    releaseValidator,
    /"\$downstream_pom" "\$artifact_identity" "\$version_property"/,
  );
  assert.match(
    releaseValidator,
    /"\$surefire_verifier" "\$surefire_reports" "\$gate_id" candidate[\s\\\n\t]+"\$installed_jar" "\$candidate_jar_sha256"/,
  );
  assert.match(releaseValidator, /prepare_servlet_default_jar/);
  assert.match(releaseValidator, /repo1\.maven\.org\/maven2\/com\/soklet\/soklet/);
  assert.match(releaseValidator, /"\$default_jar" "\$default_artifact_sha256"/);
  assert.match(
    releaseValidator,
    /"project-pom=\$retained_pom"[\s\\\n]+"default-jar=\$retained_default_jar"/,
  );
  assert.ok(
    (releaseValidator.match(/assert_installed_candidate_unchanged/g)?.length ?? 0) >= 10,
  );
  const barebonesFunction = releaseValidator.match(
    /\nrun_barebones\(\) \{\n([\s\S]*?)\n\}\n\nrun_website\(\)/,
  );
  assert.notEqual(barebonesFunction, null);
  assert.doesNotMatch(barebonesFunction[1], /8080/);
  assert.match(barebonesFunction[1], /reserve_loopback_port "\$port_file" "\$reservation_log"/);
  assert.match(
    barebonesFunction[1],
    /env RUNNING_IN_DOCKER=true SOKLET_BAREBONES_LOOPBACK_PORT="\$barebones_port"/,
  );
  assert.equal(barebonesFunction[1].match(/127\.0\.0\.1:\$barebones_port/g)?.length, 3);
  assert.match(barebonesFunction[1], /grep --fixed-strings --line-regexp --quiet/);
  assert.match(barebonesFunction[1], /! kill -0 "\$reservation_pid"/);
  assert.match(barebonesFunction[1], /! kill -0 "\$app_pid"/);
  assert.match(barebonesFunction[1], /assert_loopback_port_available "\$barebones_port"/);
  assert.match(
    barebonesFunction[1],
    /record_gate barebones-app[\s\\\n]+"port-file=\$retained_port_file"[\s\\\n]+"reservation-log=\$reservation_log"[\s\\\n]+"runtime-log=\$log"/,
  );
  assert.ok(
    barebonesFunction[1].indexOf('stop_active_process')
      < barebonesFunction[1].indexOf('SOKLET_BAREBONES_LOOPBACK_PORT="$barebones_port"'),
  );
  assert.match(releaseValidator, /scripts\/reserve-loopback-port\.mjs/);
  assert.match(loopbackPortReserver, /host: '127\.0\.0\.1', port: 0/);
  assert.match(loopbackPortReserver, /flag: 'wx'/);
  assert.doesNotMatch(releaseValidator, /assert_loopback_port_available 8080/);
  assert.doesNotMatch(releaseValidator, /trap cleanup EXIT HUP INT TERM/);
  for (const [signal, status] of [['HUP', 129], ['INT', 130], ['TERM', 143]]) {
    assert.match(releaseValidator, new RegExp(`trap 'exit ${status}' ${signal}`));
  }

  const pinnedJavaInstaller = readFileSync(pinnedJavaInstallerPath, 'utf8');
  assert.doesNotMatch(pinnedJavaInstaller, /downloads\/latest/);
  assert.match(pinnedJavaInstaller, /downloads\/resources\/\$distribution_version/);
  assert.match(pinnedJavaInstaller, /sha256sum --check --strict/);
  assert.match(pinnedJavaInstaller, /java\.runtime\.version/);
  assert.match(pinnedJavaInstaller, /java\.vendor\.version/);
  assert.match(pinnedJavaInstaller, /coreJdk21\)/);
  assert.match(pinnedJavaInstaller, /\^21\\\.0\\\.\[0-9\]\+\(\\\.\[0-9\]\+\)\?\$/);

  const readyManifest = JSON.parse(readFileSync(trackedManifestPath, 'utf8'));
  readyManifest.toolchains.coreJdk21 = {
    archive: 'amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
    archiveSha256: 'f79824540cef882da0cdf1369f9d1d69afc14b5a9bc3a771fd5bb795793ce2f2',
    distribution: 'corretto',
    distributionUrl:
      'https://corretto.aws/downloads/resources/21.0.12.9.1/amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
    runtimeVersion: '21.0.12.1+9-LTS',
    vendorVersion: 'Corretto-21.0.12.9.1',
    version: '21.0.12.1',
  };
  for (const gate of readyManifest.gates) {
    gate.status = 'READY';
    gate.reason = '';

    if (gate.repository !== null && gate.commit === null)
      gate.commit = 'b'.repeat(40);
  }

  const fixtureManifestPath = fixturePath('release/release-validation-manifest.json');
  const fixtureNodePinPath = fixturePath('conformance/official/upstream-pins.json');
  const fixtureTypeScriptDirectory = fixturePath('verification/interoperability/typescript');
  const fixtureGoDirectory = fixturePath('verification/interoperability/go');
  mkdirSync(dirname(fixtureManifestPath), { recursive: true });
  mkdirSync(dirname(fixtureNodePinPath), { recursive: true });
  mkdirSync(fixtureTypeScriptDirectory, { recursive: true });
  mkdirSync(fixtureGoDirectory, { recursive: true });
  mkdirSync(fixturePath('scripts'), { recursive: true });
  copyFileSync(
    releaseHarnessRegistryPath,
    fixturePath('release/release-harness-contracts.json'),
  );
  copyFileSync(promotionHelperPath, fixturePath('scripts/release-promotion.mjs'));
  copyFileSync(promotionWrapperPath, fixturePath('scripts/promote-release-candidate.sh'));
  copyFileSync(
    resolve(projectRoot, 'conformance/official/upstream-pins.json'),
    fixtureNodePinPath,
  );
  for (const name of ['scenarios.json', 'expected-checks.json']) {
    copyFileSync(
      resolve(projectRoot, 'conformance/official', name),
      fixturePath('conformance/official', name),
    );
  }
  for (const name of ['package.json', 'package-lock.json']) {
    copyFileSync(
      resolve(projectRoot, 'verification/interoperability/typescript', name),
      resolve(fixtureTypeScriptDirectory, name),
    );
  }
  for (const name of ['go.mod', 'go.sum']) {
    copyFileSync(
      resolve(projectRoot, 'verification/interoperability/go', name),
      resolve(fixtureGoDirectory, name),
    );
  }
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  const downstreamPomPath = fixturePath('downstream/pom.xml');
  mkdirSync(dirname(downstreamPomPath), { recursive: true });
  const downstreamPom = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.soklet</groupId>
  <artifactId>fixture</artifactId>
  <version>1.0.0</version>
  <properties><soklet.version>3.1.1</soklet.version></properties>
  <dependencies>
    <dependency>
      <groupId>com.soklet</groupId>
      <artifactId>soklet</artifactId>
      <version>\${soklet.version}</version>
    </dependency>
  </dependencies>
</project>
`;
  writeFileSync(downstreamPomPath, downstreamPom);
  assert.deepEqual(
    verifyMavenDownstreamPom(
      downstreamPomPath,
      'com.soklet:fixture:1.0.0',
      'soklet.version',
      'com.soklet:soklet:3.1.1',
    ),
    {
      artifactId: 'fixture',
      defaultArtifactIdentity: 'com.soklet:soklet:3.1.1',
      defaultSokletVersion: '3.1.1',
      groupId: 'com.soklet',
      version: '1.0.0',
    },
  );
  assert.throws(
    () => verifyMavenDownstreamPom(
      downstreamPomPath,
      'com.soklet:not-fixture:1.0.0',
      'soklet.version',
      'com.soklet:soklet:3.1.1',
    ),
    /project identity is/,
  );
  writeFileSync(
    downstreamPomPath,
    downstreamPom.replace('<version>\${soklet.version}</version>', '<version>3.1.1</version>'),
  );
  assert.throws(
    () => verifyMavenDownstreamPom(
      downstreamPomPath,
      'com.soklet:fixture:1.0.0',
      'soklet.version',
      'com.soklet:soklet:3.1.1',
    ),
    /dependency version is/,
  );
  writeFileSync(downstreamPomPath, downstreamPom);
  writeFileSync(
    downstreamPomPath,
    downstreamPom.replace(
      '<soklet.version>3.1.1</soklet.version>',
      '<soklet.version>3.1.2</soklet.version>',
    ),
  );
  assert.throws(
    () => verifyMavenDownstreamPom(
      downstreamPomPath,
      'com.soklet:fixture:1.0.0',
      'soklet.version',
      'com.soklet:soklet:3.1.1',
    ),
    /expected exact stable version 3\.1\.1/,
  );
  for (const dynamicVersion of ['3.1.1-SNAPSHOT', 'LATEST', 'RELEASE', '[3.1,4.0)']) {
    const dynamicPom = downstreamPom.replace(
      '<soklet.version>3.1.1</soklet.version>',
      `<soklet.version>${dynamicVersion}</soklet.version>`,
    );
    writeFileSync(downstreamPomPath, dynamicPom);
    assert.throws(
      () => verifyMavenDownstreamPom(
        downstreamPomPath,
        'com.soklet:fixture:1.0.0',
        'soklet.version',
        `com.soklet:soklet:${dynamicVersion}`,
      ),
      /(?:expected exact stable version|must be a concrete Maven version)/,
    );
  }
  writeFileSync(downstreamPomPath, downstreamPom);

  const ready = validateReleaseConfiguration(fixtureManifestPath, { requireReady: true });
  assert.equal(ready.gates.length, 29);

  function assertRejectsGateContractMutation(mutate) {
    const substituted = JSON.parse(JSON.stringify(readyManifest));
    mutate(substituted.gates);
    writeFileSync(fixtureManifestPath, `${JSON.stringify(substituted, null, 2)}\n`);
    assert.throws(
      () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
      /canonical release contract/,
    );
  }

  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'barebones-app').repository =
      'https://github.com/example/substituted-app.git';
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'barebones-app').kind = 'SOURCE';
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'soklet-otel').artifactIdentity =
      'com.soklet:substituted:1.4.0-SNAPSHOT';
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'soklet-servlet-javax').versionProperty = null;
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'soklet-servlet-javax').defaultArtifactIdentity =
      'com.soklet:soklet:3.1.2';
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'soklet-servlet-jakarta').defaultArtifactSha256 =
      '0'.repeat(64);
  });
  assertRejectsGateContractMutation((gates) => {
    const candidateBuild = gates.find(({ id }) => id === 'candidate-build');
    candidateBuild.access = 'PUBLIC_READ_ONLY';
    candidateBuild.repository = 'https://github.com/example/substituted-build.git';
    candidateBuild.commit = 'b'.repeat(40);
  });
  assertRejectsGateContractMutation((gates) => {
    const candidateLocalization = gates.find(
      ({ id }) => id === 'candidate-localization',
    );
    candidateLocalization.access = 'PUBLIC_READ_ONLY';
    candidateLocalization.repository =
      'https://github.com/example/localization-adapter.git';
    candidateLocalization.commit = 'b'.repeat(40);
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'candidate-build').evidenceContract =
      'soklet.release.substituted.v1';
  });
  assertRejectsGateContractMutation((gates) => {
    gates.find(({ id }) => id === 'candidate-build').toolchain = 'nodePin';
  });
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  function assertRejectsManifestMutation(mutate, pattern) {
    const substituted = JSON.parse(JSON.stringify(readyManifest));
    mutate(substituted);
    writeFileSync(fixtureManifestPath, `${JSON.stringify(substituted, null, 2)}\n`);
    assert.throws(
      () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
      pattern,
    );
  }

  assertRejectsManifestMutation(
    (manifest) => { manifest.formatVersion = 1; },
    /formatVersion must be 2/,
  );
  assertRejectsManifestMutation(
    (manifest) => { manifest.gates.splice(3, 1); },
    /gate IDs and order must be exactly/,
  );
  assertRejectsManifestMutation(
    (manifest) => { manifest.gates.push({ ...manifest.gates[0], id: 'extra' }); },
    /no canonical release contract/,
  );
  assertRejectsManifestMutation(
    (manifest) => { [manifest.gates[0], manifest.gates[1]] = [manifest.gates[1], manifest.gates[0]]; },
    /gate IDs and order must be exactly/,
  );
  assertRejectsManifestMutation(
    (manifest) => {
      const gate = manifest.gates.find(({ id }) => id === 'core-jdk-21');
      gate.status = 'BLOCKED_HARNESS_MISSING';
      gate.reason = 'Fixture blocked gate.';
    },
    /core-jdk-21=BLOCKED_HARNESS_MISSING/,
  );
  assertRejectsManifestMutation(
    (manifest) => { manifest.toolchains.coreJdk21 = null; },
    /READY gate core-jdk-21 cannot use unavailable toolchain coreJdk21/,
  );
  assertRejectsManifestMutation(
    (manifest) => {
      manifest.toolchains.coreJdk21.version = '21.0.12.2';
      manifest.toolchains.coreJdk21.runtimeVersion = '21.0.12.2+9-LTS';
    },
    /Core JDK 21 toolchain must pin an exact Corretto 21 build/,
  );
  assertRejectsManifestMutation(
    (manifest) => {
      manifest.toolchains.java.version = '17.0.20.1';
      manifest.toolchains.java.runtimeVersion = '17.0.20.1+8-LTS';
    },
    /Candidate Java toolchain must pin an exact Corretto 17 build/,
  );
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  const savedToyStoreJavaUrl = readyManifest.toolchains.toystoreJava.distributionUrl;
  readyManifest.toolchains.toystoreJava.distributionUrl =
    'https://corretto.aws/downloads/latest/amazon-corretto-25-x64-linux-jdk.tar.gz';
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /ToyStore Java toolchain distribution URL must be exactly/,
  );
  readyManifest.toolchains.toystoreJava.distributionUrl = savedToyStoreJavaUrl;
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  const savedPromotionHelperSha256 = readyManifest.promotion.helper.sha256;
  readyManifest.promotion.helper.sha256 = '0'.repeat(64);
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /Promotion helper does not match its reviewed SHA-256/,
  );
  readyManifest.promotion.helper.sha256 = savedPromotionHelperSha256;
  const savedPromotionWrapperPath = readyManifest.promotion.wrapper.path;
  readyManifest.promotion.wrapper.path = 'scripts/not-the-promotion-wrapper.sh';
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /Promotion wrapper path must be exactly/,
  );
  readyManifest.promotion.wrapper.path = savedPromotionWrapperPath;
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  const typeScriptGate = readyManifest.gates.find(({ id }) => id === 'typescript-interop');
  const savedTypeScriptChecksum = typeScriptGate.artifactChecksum;
  typeScriptGate.artifactChecksum = savedTypeScriptChecksum.replace('sha512-8', 'sha512-9');
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /TypeScript interoperability manifest checksum does not match package-lock\.json/,
  );
  typeScriptGate.artifactChecksum = savedTypeScriptChecksum;
  writeFileSync(fixtureManifestPath, `${JSON.stringify(readyManifest, null, 2)}\n`);

  const fixtureGoSumPath = resolve(fixtureGoDirectory, 'go.sum');
  const savedGoSum = readFileSync(fixtureGoSumPath, 'utf8');
  writeFileSync(
    fixtureGoSumPath,
    savedGoSum.replace(
      'h1:yqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44=',
      'h1:zqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44=',
    ),
  );
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /Go interoperability manifest checksum must match exactly one go\.sum entry/,
  );
  writeFileSync(fixtureGoSumPath, savedGoSum);

  const fixtureGoModPath = resolve(fixtureGoDirectory, 'go.mod');
  const savedGoMod = readFileSync(fixtureGoModPath, 'utf8');
  writeFileSync(
    fixtureGoModPath,
    savedGoMod.replace(
      'require github.com/modelcontextprotocol/go-sdk v1.7.0',
      'require github.com/modelcontextprotocol/go-sdk v1.7.1',
    ),
  );
  assert.throws(
    () => validateReleaseConfiguration(fixtureManifestPath, { requireReady: true }),
    /Go interoperability go\.mod must contain exactly one direct declaration/,
  );
  writeFileSync(fixtureGoModPath, savedGoMod);

  const resolvedMatrixClosureRegistry = JSON.parse(
    readFileSync(matrixClosureRegistryPath, 'utf8'),
  );
  for (const row of resolvedMatrixClosureRegistry.rows) {
    if (row.disposition === 'UNRESOLVED') {
      row.disposition = 'CORE_COMPLETE';
      row.reason = '';
      if (row.evidence.every((reference) => reference.endsWith('.md'))) {
        row.evidence.push('scripts/verify-release-matrix-closure-self-test.mjs');
        row.evidence.sort();
      }
    }
  }
  for (const reference of new Set(
    resolvedMatrixClosureRegistry.rows.flatMap(({ evidence }) => evidence),
  )) {
    if (reference === 'release/release-validation-manifest.json')
      continue;
    const destination = fixturePath(reference);
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(resolve(projectRoot, reference), destination);
  }
  const fixtureMatrixClosureRegistryPath = fixturePath(
    'release/mcp-conformance-matrix-closure.json',
  );
  const fixtureMatrixClosureVerifierPath = fixturePath(
    'scripts/verify-release-matrix-closure.mjs',
  );
  copyFileSync(matrixClosureVerifierPath, fixtureMatrixClosureVerifierPath);
  writeFileSync(
    fixtureMatrixClosureRegistryPath,
    `${JSON.stringify(resolvedMatrixClosureRegistry, null, 2)}\n`,
  );
  for (const args of [
    ['init', '--quiet'],
    ['add', '--', '.'],
  ]) {
    const result = spawnSync('git', ['-C', fixtureRoot, ...args], { encoding: 'utf8' });
    assert.equal(
      result.status,
      0,
      `Unable to prepare tracked matrix-closure fixture: ${result.error?.message
        ?? result.stderr}`,
    );
  }

  const pomPath = fixturePath('pom.xml');
  const mainJarPath = fixturePath('soklet-4.0.0.jar');
  const sourcesJarPath = fixturePath('soklet-4.0.0-sources.jar');
  const javadocJarPath = fixturePath('soklet-4.0.0-javadoc.jar');
  const artifactDescriptorPath = fixturePath('evidence/candidate-artifacts.json');
  const gateDirectory = fixturePath('evidence/gates');
  const finalEvidencePath = fixturePath('evidence/release-validation-evidence.json');
  const matrixReportPath = fixturePath(
    'evidence/raw/matrix-closure/matrix-closure.json',
  );
  const resolvedMatrixClosure = verifyMatrixClosure({
    finiteBoundProjectRoot: projectRoot,
    manifestPath: fixtureManifestPath,
    projectRoot: fixtureRoot,
    registryPath: fixtureMatrixClosureRegistryPath,
  });
  assert.equal(resolvedMatrixClosure.exitCode, 0);
  assert.equal(resolvedMatrixClosure.report.status, 'PASSED');
  assert.equal(resolvedMatrixClosure.report.rowCount, 263);
  assert.deepEqual(resolvedMatrixClosure.report.unresolvedRows, []);
  mkdirSync(dirname(matrixReportPath), { recursive: true });
  writeFileSync(matrixReportPath, resolvedMatrixClosure.reportText);
  mkdirSync(dirname(artifactDescriptorPath), { recursive: true });
  mkdirSync(gateDirectory, { recursive: true });
  writeFileSync(pomPath, `<?xml version="1.0" encoding="UTF-8"?>
<project><modelVersion>4.0.0</modelVersion><groupId>com.soklet</groupId><artifactId>soklet</artifactId><version>4.0.0</version><packaging>jar</packaging></project>
`);
  for (const path of [mainJarPath, sourcesJarPath, javadocJarPath])
    writeFileSync(path, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x01]));

  Object.assign(process.env, {
    GITHUB_JOB: 'validate',
    GITHUB_REPOSITORY: 'soklet/soklet',
    GITHUB_RUN_ATTEMPT: '1',
    GITHUB_RUN_ID: '1234',
    GITHUB_SERVER_URL: 'https://github.com',
    GITHUB_SHA: candidateCommit,
    SOKLET_EVIDENCE_CORE_JDK_21_VERSION: '21.0.12.1',
    SOKLET_EVIDENCE_GIT_VERSION: 'git version 2.50.1',
    SOKLET_EVIDENCE_GO_VERSION: 'go version go1.25.12 linux/amd64',
    SOKLET_EVIDENCE_JAVA_VERSION: '17.0.20',
    SOKLET_EVIDENCE_MAVEN_VERSION: '3.9.16',
    SOKLET_EVIDENCE_NODE_VERSION: '26.5.0',
    SOKLET_EVIDENCE_NPM_VERSION: '11.17.0',
    SOKLET_EVIDENCE_TOYSTORE_JAVA_VERSION: '25.0.4',
  });

  recordCandidateArtifacts(
    fixtureManifestPath,
    candidateCommit,
    artifactDescriptorPath,
    {
      pom: pomPath,
      mainJar: mainJarPath,
      sourcesJar: sourcesJarPath,
      javadocJar: javadocJarPath,
    },
  );

  const descriptor = JSON.parse(readFileSync(artifactDescriptorPath, 'utf8'));
  const importedContract = releaseHarnessConfiguration.contracts.get(
    'fuzz-nightly-history',
  );
  const importedBundlePath = fixturePath('evidence/imported-fuzz-history-bundle.json');
  const importedReceiptPath = fixturePath('evidence/imported-fuzz-history-receipt.json');
  const importedGatePath = fixturePath('evidence/imported-fuzz-history-gate.json');
  const importedCandidate = {
    candidateCommit,
    candidateMainJarSha256: descriptor.artifacts.mainJar.sha256,
    candidatePomSha256: descriptor.artifacts.pom.sha256,
    candidateRegistrySha256: releaseHarnessConfiguration.registrySha256,
    candidateTree: 'b'.repeat(40),
    producerWorkflowSha256: 'd'.repeat(64),
  };
  const importedNow = Math.floor(Date.now() / 1_000) * 1_000;
  const importedHistory = {
    candidate: importedCandidate,
    formatVersion: 1,
    gate: importedContract.id,
    policySha256: sha256(Buffer.from(canonicalJson(importedContract.policy), 'utf8')),
    producerStatus: 'PASS',
    runs: Array.from({ length: 7 }, (_, runIndex) => {
      const completedAt = new Date(
        importedNow - (7 - runIndex) * 86_400_000,
      ).toISOString().replace('.000Z', 'Z');
      return {
        completedAt,
        corpusHashes: importedContract.policy.targets.map((target) =>
          sha256(Buffer.from(`${completedAt}:${target.id}:corpus`, 'utf8'))),
        id: completedAt.slice(0, 10),
        outcome: 'PASS',
        targets: importedContract.policy.targets.map((target) => ({
          durationSeconds: importedContract.policy.perTargetDurationSeconds,
          id: target.id,
          ordinal: target.ordinal,
          outcome: 'PASS',
        })),
      };
    }),
    toolchainsSha256: sha256(Buffer.from(canonicalJson(importedContract.toolchains), 'utf8')),
  };
  const importedRoleBytes = Buffer.from(canonicalJson(importedHistory), 'utf8');
  const importedHistoryPath = fixturePath('evidence/fuzz-nightly-history.json');
  writeFileSync(importedHistoryPath, importedRoleBytes);
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      importedContract.id,
      fixturePath('evidence/generic-fuzz-history-gate.json'),
      [`history=${importedHistoryPath}`],
    ),
    /requires fail-closed imported evidence; use record-imported-gate/,
  );
  const importedBundleRole = {
    bytesBase64: importedRoleBytes.toString('base64'),
    kind: importedContract.roles[0].kind,
    mediaType: importedContract.roles[0].mediaType,
    name: importedContract.roles[0].name,
    ordinal: importedContract.roles[0].ordinal,
    required: importedContract.roles[0].required,
    sha256: sha256(importedRoleBytes),
  };
  const importedBundleContent = {
    candidate: importedCandidate,
    contractVersion: importedContract.contractVersion,
    evidenceContract: importedContract.evidenceContract,
    gate: importedContract.id,
    policy: importedContract.policy,
    producer: importedContract.producer,
    producerStatus: 'PASS',
    roles: [importedBundleRole],
    toolchains: importedContract.toolchains,
  };
  const importedBundleBytes = Buffer.from(canonicalJson({
    content: importedBundleContent,
    contentSha256: sha256(Buffer.from(canonicalJson(importedBundleContent), 'utf8')),
    formatVersion: 1,
  }), 'utf8');
  writeFileSync(importedBundlePath, importedBundleBytes);
  importReleaseHarnessEvidence({
    bundlePath: importedBundlePath,
    candidateIdentityProvider: () => importedCandidate,
    candidateRoot: fixtureRoot,
    gate: importedContract.id,
    now: importedNow,
    outputPath: importedReceiptPath,
    registryPath: fixturePath('release/release-harness-contracts.json'),
  });
  const importedGate = await recordImportedGateEvidence(
    fixtureManifestPath,
    candidateCommit,
    artifactDescriptorPath,
    importedContract.id,
    importedGatePath,
    importedReceiptPath,
    importedBundlePath,
    () => importedCandidate,
  );
  assert.deepEqual(importedGate.evidence.map(({ role }) => role), ['history']);
  assert.deepEqual(importedGate.evidence[0].artifact, {
    bytes: importedRoleBytes.length,
    fileName: 'fuzz-nightly-history.json',
    sha256: sha256(importedRoleBytes),
    type: 'FILE',
  });
  const substitutedBundlePath = fixturePath('evidence/substituted-fuzz-history-bundle.json');
  writeFileSync(substitutedBundlePath, Buffer.from('{"immutable":"substituted"}\n', 'utf8'));
  await assert.rejects(
    recordImportedGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      importedContract.id,
      fixturePath('evidence/substituted-fuzz-history-gate.json'),
      importedReceiptPath,
      substitutedBundlePath,
      () => importedCandidate,
    ),
    /release harness bundle|immutable bundle/,
  );
  const wrongCandidateReceiptPath = fixturePath(
    'evidence/wrong-candidate-fuzz-history-receipt.json',
  );
  const wrongCandidateReceipt = JSON.parse(readFileSync(importedReceiptPath, 'utf8'));
  wrongCandidateReceipt.candidateBindings.candidateMainJarSha256 = '9'.repeat(64);
  writeFileSync(wrongCandidateReceiptPath, canonicalJson(wrongCandidateReceipt));
  await assert.rejects(
    recordImportedGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      importedContract.id,
      fixturePath('evidence/wrong-candidate-fuzz-history-gate.json'),
      wrongCandidateReceiptPath,
      importedBundlePath,
      () => importedCandidate,
    ),
    /candidate/,
  );
  const coherentWrongCandidate = {
    ...importedCandidate,
    candidateTree: 'c'.repeat(40),
    producerWorkflowSha256: 'e'.repeat(64),
  };
  const coherentWrongHistory = structuredClone(importedHistory);
  coherentWrongHistory.candidate = coherentWrongCandidate;
  const coherentWrongRoleBytes = Buffer.from(canonicalJson(coherentWrongHistory), 'utf8');
  const coherentWrongBundleRole = {
    ...importedBundleRole,
    bytesBase64: coherentWrongRoleBytes.toString('base64'),
    sha256: sha256(coherentWrongRoleBytes),
  };
  const coherentWrongBundleContent = {
    ...importedBundleContent,
    candidate: coherentWrongCandidate,
    roles: [coherentWrongBundleRole],
  };
  const coherentWrongBundlePath = fixturePath(
    'evidence/coherent-wrong-candidate-fuzz-history-bundle.json',
  );
  const coherentWrongReceiptPath = fixturePath(
    'evidence/coherent-wrong-candidate-fuzz-history-receipt.json',
  );
  writeFileSync(coherentWrongBundlePath, canonicalJson({
    content: coherentWrongBundleContent,
    contentSha256: sha256(Buffer.from(canonicalJson(coherentWrongBundleContent), 'utf8')),
    formatVersion: 1,
  }));
  importReleaseHarnessEvidence({
    bundlePath: coherentWrongBundlePath,
    candidateIdentityProvider: () => coherentWrongCandidate,
    candidateRoot: fixtureRoot,
    gate: importedContract.id,
    now: importedNow,
    outputPath: coherentWrongReceiptPath,
    registryPath: fixturePath('release/release-harness-contracts.json'),
  });
  await assert.rejects(
    recordImportedGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      importedContract.id,
      fixturePath('evidence/coherent-wrong-candidate-fuzz-history-gate.json'),
      coherentWrongReceiptPath,
      coherentWrongBundlePath,
      () => importedCandidate,
    ),
    /do not match the current candidate root/,
  );
  const conformanceEvidencePath = fixturePath('evidence/conformance-evidence.json');
  const conformanceArtifacts = Object.fromEntries(
    ['pom', 'mainJar', 'sourcesJar', 'javadocJar'].map((name) => [name, {
      bytes: descriptor.artifacts[name].bytes,
      fileName: descriptor.artifacts[name].fileName,
      sha256: descriptor.artifacts[name].sha256,
    }]),
  );
  const conformanceManifests = verifyManifestSet(fixturePath('conformance/official'));
  const conformanceProfiles = new Map(
    conformanceManifests.expectedChecks.profiles.map((profile) => [profile.id, profile]),
  );
  const conformanceScenarios = activeScenarios(conformanceManifests.selection, 5)
    .map((scenario) => {
      const profile = conformanceProfiles.get(scenario.expectedCheckProfile);
      const checkCount = profile.checks.reduce((total, check) => total + check.count, 0)
        + profile.automaticWireChecks['wire-schema-valid']
        + profile.automaticWireChecks['wire-schema-harness-error'];
      return {
        name: scenario.name,
        passed: true,
        checkCount,
        expectedCheckProfile: scenario.expectedCheckProfile,
        observedProfileDraft: null,
      };
    });
  const conformanceEvidence = {
    evidenceClass: 'IMMUTABLE_RELEASE_CANDIDATE',
    failure: null,
    formatVersion: 1,
    goldenMessagesValidated: 48,
    mode: 'release',
    phase: 5,
    protocolVersion: conformanceManifests.pins.protocolVersion,
    releaseCandidateEvidence: true,
    releaseCandidateProvenance: {
      artifacts: conformanceArtifacts,
      candidateCommit,
      coordinates: {
        groupId: 'com.soklet',
        artifactId: 'soklet',
        version: '4.0.0',
      },
      formatVersion: 1,
      manifestSha256: null,
      protocolVersion: conformanceManifests.pins.protocolVersion,
      source: 'explicit-artifacts',
      suiteCommit: conformanceManifests.pins.officialConformanceSuite.commit,
    },
    scenarios: conformanceScenarios,
    status: 'PASSED',
    suiteCommit: conformanceManifests.pins.officialConformanceSuite.commit,
  };
  writeFileSync(conformanceEvidencePath, `${JSON.stringify(conformanceEvidence, null, 2)}\n`);
  verifyReleaseConformanceEvidence(
    fixtureManifestPath,
    candidateCommit,
    artifactDescriptorPath,
    conformanceEvidencePath,
  );

  function assertRejectsConformanceMutation(mutate, pattern) {
    const substituted = JSON.parse(JSON.stringify(conformanceEvidence));
    mutate(substituted);
    writeFileSync(conformanceEvidencePath, `${JSON.stringify(substituted, null, 2)}\n`);
    assert.throws(
      () => verifyReleaseConformanceEvidence(
        fixtureManifestPath,
        candidateCommit,
        artifactDescriptorPath,
        conformanceEvidencePath,
      ),
      pattern,
    );
  }

  assertRejectsConformanceMutation(
    (value) => { value.releaseCandidateEvidence = false; },
    /not a complete passing immutable release-candidate run/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.mode = 'verify'; },
    /not a complete passing immutable release-candidate run/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.goldenMessagesValidated = 47; },
    /not a complete passing immutable release-candidate run/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.scenarios[0].name = value.scenarios[1].name; },
    /does not match the reviewed server-stateless result contract/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.scenarios[0].checkCount += 1; },
    /does not match the reviewed server-stateless result contract/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.scenarios[0].expectedCheckProfile = null; },
    /does not match the reviewed server-stateless result contract/,
  );
  assertRejectsConformanceMutation(
    (value) => { value.unreviewed = true; },
    /release conformance evidence keys must be exactly/,
  );
  writeFileSync(conformanceEvidencePath, `${JSON.stringify(conformanceEvidence, null, 2)}\n`);

  function writeInteropLog(gate, path, overrides = {}) {
    const client = gate.id === 'typescript-interop' ? 'typescript' : 'go';
    const receipt = {
      candidateSha256: descriptor.artifacts.mainJar.sha256,
      client,
      fixtureScenario: 'tools-list',
      fixtureShutdown: 'CLEAN',
      formatVersion: 1,
      protocolVersion: '2026-07-28',
      sdkArtifactChecksum: gate.artifactChecksum,
      sdkArtifactIdentity: gate.artifactIdentity,
      sdkCommit: gate.commit,
      tool: 'test_simple_text',
      ...overrides,
    };
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(
      path,
      `dependency setup output\nSOKLET_INTEROP_PASS 2026-07-28 ${client}\nSOKLET_INTEROP_EVIDENCE ${JSON.stringify(receipt)}\n`,
    );
  }

  const typeScriptReadyGate = ready.gates.find(({ id }) => id === 'typescript-interop');
  const wrongCandidateLogPath = fixturePath(
    'evidence/wrong-candidate/typescript-interop.log',
  );
  writeInteropLog(typeScriptReadyGate, wrongCandidateLogPath, {
    candidateSha256: 'd'.repeat(64),
  });
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-wrong-candidate.json'),
      [
        `interop-log=${wrongCandidateLogPath}`,
        `candidate-main-jar=${mainJarPath}`,
      ],
    ),
    /does not match the exact candidate, SDK pin, and fixture contract/,
  );
  const wrongSdkLogPath = fixturePath('evidence/wrong-sdk/typescript-interop.log');
  writeInteropLog(typeScriptReadyGate, wrongSdkLogPath, {
    sdkArtifactChecksum: typeScriptReadyGate.artifactChecksum.replace(
      'sha512-8',
      'sha512-9',
    ),
  });
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-wrong-sdk.json'),
      [
        `interop-log=${wrongSdkLogPath}`,
        `candidate-main-jar=${mainJarPath}`,
      ],
    ),
    /does not match the exact candidate, SDK pin, and fixture contract/,
  );
  const noncanonicalLogPath = fixturePath('evidence/noncanonical/typescript-interop.log');
  writeInteropLog(typeScriptReadyGate, noncanonicalLogPath);
  writeFileSync(
    noncanonicalLogPath,
    readFileSync(noncanonicalLogPath, 'utf8').replace(
      'SOKLET_INTEROP_EVIDENCE {"candidateSha256"',
      'SOKLET_INTEROP_EVIDENCE { "candidateSha256"',
    ),
  );
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-noncanonical.json'),
      [
        `interop-log=${noncanonicalLogPath}`,
        `candidate-main-jar=${mainJarPath}`,
      ],
    ),
    /receipt must use the exact canonical encoding/,
  );

  function evidencePathForRole(gate, specification) {
    if (specification.candidateArtifact === 'descriptor')
      return artifactDescriptorPath;
    if (specification.candidateArtifact === 'mainJar')
      return mainJarPath;
    if (specification.candidateArtifact === 'javadocJar')
      return javadocJarPath;
    if (gate.id === 'matrix-closure' && specification.role === 'matrix-report')
      return matrixReportPath;

    const path = fixturePath(
      'evidence/role-fixtures',
      gate.id,
      specification.fileName,
    );
    mkdirSync(dirname(path), { recursive: true });
    if (specification.candidateArtifact === 'gateToolchainDistribution') {
      const toolchain = ready.toolchains[gate.toolchain];
      writeFileSync(
        path,
        `distribution=${toolchain.distribution}\n`
          + `version=${toolchain.version}\n`
          + `runtimeVersion=${toolchain.runtimeVersion}\n`
          + `vendorVersion=${toolchain.vendorVersion}\n`
          + `url=${toolchain.distributionUrl}\n`
          + `archive=${toolchain.archive}\n`
          + `archiveSha256=${toolchain.archiveSha256}\n`,
      );
    } else if (specification.type === 'DIRECTORY') {
      mkdirSync(path, { recursive: true });
      writeFileSync(resolve(path, 'evidence.txt'), 'fixture directory evidence\n');
    } else if (specification.candidateArtifact === 'pom') {
      copyFileSync(pomPath, path);
    } else if (specification.mediaType === 'application/java-archive') {
      writeFileSync(path, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x01]));
    } else if (specification.mediaType === 'application/json') {
      writeFileSync(
        path,
        `${JSON.stringify({ gateId: gate.id, result: 'PASS', role: specification.role })}\n`,
      );
    } else if (specification.mediaType === 'application/xml') {
      writeFileSync(path, '<evidence result="PASS"/>\n');
    } else if (specification.mediaType === 'application/x-ndjson') {
      writeFileSync(path, '{"result":"PASS"}\n');
    } else {
      writeFileSync(path, 'fixture evidence\n');
    }
    return path;
  }

  function rolePathsForGate(gate) {
    const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gate.id];
    return contract.roles.map((specification) => {
      let path = evidencePathForRole(gate, specification);
      if (gate.kind === 'INTEROPERABILITY' && specification.role === 'interop-log') {
        writeInteropLog(gate, path);
      }
      return `${specification.role}=${path}`;
    });
  }

  function syntheticEvidenceDescriptor(path, specification) {
    if (specification.type === 'DIRECTORY') {
      const bytes = readFileSync(resolve(path, 'evidence.txt'));
      const rows = `${sha256(bytes)}  evidence.txt\n`;
      return {
        algorithm:
          "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows",
        fileCount: 1,
        fileName: basename(path),
        sha256: sha256(Buffer.from(rows, 'utf8')),
        type: 'DIRECTORY',
      };
    }
    const bytes = readFileSync(path);
    return {
      bytes: bytes.length,
      fileName: basename(path),
      sha256: sha256(bytes),
      type: 'FILE',
    };
  }

  function writeSyntheticGateEvidence(gate, outputPath, rolePaths) {
    const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gate.id];
    const paths = new Map(rolePaths.map((rolePath) => {
      const separator = rolePath.indexOf('=');
      return [rolePath.slice(0, separator), rolePath.slice(separator + 1)];
    }));
    const workflow = {
      job: process.env.GITHUB_JOB,
      repository: process.env.GITHUB_REPOSITORY,
      runAttempt: process.env.GITHUB_RUN_ATTEMPT,
      runId: process.env.GITHUB_RUN_ID,
      serverUrl: process.env.GITHUB_SERVER_URL,
      sha: process.env.GITHUB_SHA,
    };
    const evidence = contract.roles.map((specification) => ({
      artifact: specification.candidateArtifact === 'gateDefaultArtifact'
        ? {
          bytes: 1037363,
          fileName: specification.fileName,
          sha256: gate.defaultArtifactSha256,
          type: 'FILE',
        }
        : syntheticEvidenceDescriptor(paths.get(specification.role), specification),
      mediaType: specification.mediaType,
      role: specification.role,
    }));
    writeFileSync(outputPath, `${JSON.stringify({
      candidateCommit,
      evidence,
      formatVersion: 2,
      gate: {
        artifactChecksum: gate.artifactChecksum,
        artifactIdentity: gate.artifactIdentity,
        commit: gate.commit,
        defaultArtifactIdentity: gate.defaultArtifactIdentity,
        defaultArtifactSha256: gate.defaultArtifactSha256,
        evidenceContract: gate.evidenceContract,
        id: gate.id,
        repository: gate.repository,
        toolchain: gate.toolchain,
      },
      interoperability: null,
      receipt: {
        candidateCommit,
        candidateSha256: descriptor.artifacts.mainJar.sha256,
        command: contract.command,
        contractId: contract.contractId,
        expectation: contract.expectation,
        formatVersion: 1,
        gateId: gate.id,
        profile: contract.profile,
        result: 'PASS',
        toolchain: gate.toolchain,
        workflow,
      },
      status: 'PASS',
    }, null, 2)}\n`);
  }

  const candidateLocalizationGate = ready.gates.find(
    ({ id }) => id === 'candidate-localization',
  );
  const localizationPaths = rolePathsForGate(candidateLocalizationGate);
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      candidateLocalizationGate.id,
      fixturePath('evidence/missing-role.json'),
      [],
    ),
    /evidence roles and order must be exactly/,
  );
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      candidateLocalizationGate.id,
      fixturePath('evidence/substituted-role.json'),
      [`substituted=${localizationPaths[0].split('=').slice(1).join('=')}`],
    ),
    /evidence roles and order must be exactly/,
  );
  const wrongBasenamePath = fixturePath('evidence/wrong-localization-name.log');
  writeFileSync(wrongBasenamePath, 'fixture evidence\n');
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      candidateLocalizationGate.id,
      fixturePath('evidence/substituted-path.json'),
      [`localization-log=${wrongBasenamePath}`],
    ),
    /basename must be exactly candidate-localization\.log/,
  );
  const ordinaryGenericGate = recordGateEvidence(
    fixtureManifestPath,
    candidateCommit,
    artifactDescriptorPath,
    candidateLocalizationGate.id,
    fixturePath('evidence/ordinary-generic-gate.json'),
    localizationPaths,
  );
  assert.equal(ordinaryGenericGate.gate.id, candidateLocalizationGate.id);
  assert.equal(ordinaryGenericGate.status, 'PASS');

  const coreJdk21Gate = ready.gates.find(({ id }) => id === 'core-jdk-21');
  const coreJdk21RolePaths = rolePathsForGate(coreJdk21Gate);
  const coreJdk21DistributionPath = coreJdk21RolePaths
    .find((rolePath) => rolePath.startsWith('java-distribution='))
    .slice('java-distribution='.length);
  const coreJdk21Distribution = readFileSync(coreJdk21DistributionPath, 'utf8');
  for (const [label, pattern, replacement] of [
    ['url', /^url=.*$/m, 'url=https://example.invalid/substituted.tar.gz'],
    ['sha', /^archiveSha256=.*$/m, `archiveSha256=${'9'.repeat(64)}`],
    ['runtime', /^runtimeVersion=.*$/m, 'runtimeVersion=21.0.12.1+8-LTS'],
    ['vendor', /^vendorVersion=.*$/m, 'vendorVersion=Corretto-21.0.12.8.1'],
  ]) {
    writeFileSync(
      coreJdk21DistributionPath,
      coreJdk21Distribution.replace(pattern, replacement),
    );
    assert.throws(
      () => recordGateEvidence(
        fixtureManifestPath,
        candidateCommit,
        artifactDescriptorPath,
        coreJdk21Gate.id,
        fixturePath(`evidence/core-jdk-21-wrong-${label}.json`),
        coreJdk21RolePaths,
      ),
      /does not match the gate's exact manifest toolchain distribution/,
    );
    writeFileSync(coreJdk21DistributionPath, coreJdk21Distribution);
  }

  const matrixClosureGate = ready.gates.find(({ id }) => id === 'matrix-closure');
  function assertRejectsMatrixReport(label, text, pattern) {
    const path = fixturePath(
      'evidence/rejected-matrix-reports',
      label,
      'matrix-closure.json',
    );
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
    assert.throws(
      () => recordGateEvidence(
        fixtureManifestPath,
        candidateCommit,
        artifactDescriptorPath,
        matrixClosureGate.id,
        fixturePath(`evidence/rejected-matrix-${label}.json`),
        [`matrix-report=${path}`],
      ),
      pattern,
    );
  }
  assertRejectsMatrixReport(
    'generic-json',
    '{"result":"PASS"}\n',
    /must exactly match the canonical PASSED report/,
  );
  const failedMatrixReport = structuredClone(resolvedMatrixClosure.report);
  failedMatrixReport.status = 'FAILED';
  failedMatrixReport.dispositionCounts.CORE_COMPLETE -= 1;
  failedMatrixReport.dispositionCounts.UNRESOLVED = 1;
  failedMatrixReport.unresolvedRows = [{
    id: 'MCP-BASE-005',
    reason: 'Synthetic unresolved row.',
  }];
  assertRejectsMatrixReport(
    'failed',
    `${JSON.stringify(failedMatrixReport, null, 2)}\n`,
    /must exactly match the canonical PASSED report/,
  );
  assertRejectsMatrixReport(
    'noncanonical',
    `${JSON.stringify(resolvedMatrixClosure.report)}\n`,
    /must exactly match the canonical PASSED report/,
  );

  for (const gate of ready.gates) {
    const rolePaths = rolePathsForGate(gate);
    if (importedReleaseHarnessGateIds.has(gate.id)) {
      const gateOutputPath = resolve(gateDirectory, `${gate.id}.json`);
      if (gate.id === importedContract.id)
        copyFileSync(importedGatePath, gateOutputPath);
      else
        writeSyntheticGateEvidence(gate, gateOutputPath, rolePaths);
      continue;
    }
    if (gate.id === 'soklet-servlet-javax'
        || gate.id === 'soklet-servlet-jakarta') {
      assert.throws(
        () => recordGateEvidence(
          fixtureManifestPath,
          candidateCommit,
          artifactDescriptorPath,
          gate.id,
          fixturePath(`evidence/${gate.id}-wrong-default-jar.json`),
          rolePaths,
        ),
        /does not match the gate's exact default artifact identity and SHA-256/,
      );
      writeSyntheticGateEvidence(
        gate,
        resolve(gateDirectory, `${gate.id}.json`),
        rolePaths,
      );
      continue;
    }
    recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gate.id,
      resolve(gateDirectory, `${gate.id}.json`),
      rolePaths,
    );
  }

  const assembled = assembleReleaseEvidence(
    fixtureManifestPath,
    candidateCommit,
    artifactDescriptorPath,
    gateDirectory,
    finalEvidencePath,
  );
  assert.match(assembled.sha256, /^[0-9a-f]{64}$/);
  const evidence = JSON.parse(readFileSync(finalEvidencePath, 'utf8'));
  assert.equal(evidence.candidateCommit, candidateCommit);
  assert.equal(evidence.formatVersion, 2);
  assert.equal(evidence.gates.length, 29);
  assert.ok(evidence.gates.every(({ status }) => status === 'PASS'));
  assert.ok(evidence.gates
    .filter(({ gate }) => gate.id.endsWith('-interop'))
    .every(({ interoperability }) =>
      interoperability.candidateSha256 === descriptor.artifacts.mainJar.sha256));
  assert.equal(evidence.toolchains.coreJdk21, '21.0.12.1');
  assert.equal(evidence.toolchains.java, '17.0.20');
  assert.equal(evidence.toolchains.toystoreJava, '25.0.4');

  const savedMatrixReport = readFileSync(matrixReportPath);
  writeFileSync(matrixReportPath, Buffer.concat([savedMatrixReport, Buffer.from('\n')]));
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/rejected-tampered-matrix-raw.json'),
    ),
    /must exactly match the canonical PASSED report/,
  );
  writeFileSync(matrixReportPath, savedMatrixReport);

  rmSync(matrixReportPath);
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/rejected-deleted-matrix-raw.json'),
    ),
    /Missing retained matrix-closure report/,
  );
  writeFileSync(matrixReportPath, savedMatrixReport);

  const matrixGateEvidencePath = resolve(gateDirectory, 'matrix-closure.json');
  const savedMatrixGateEvidence = readFileSync(matrixGateEvidencePath);
  const forgedMatrixReport = `${JSON.stringify(failedMatrixReport, null, 2)}\n`;
  const forgedMatrixGateEvidence = JSON.parse(savedMatrixGateEvidence);
  forgedMatrixGateEvidence.evidence[0].artifact.bytes = Buffer.byteLength(
    forgedMatrixReport,
  );
  forgedMatrixGateEvidence.evidence[0].artifact.sha256 = sha256(
    Buffer.from(forgedMatrixReport),
  );
  writeFileSync(matrixReportPath, forgedMatrixReport);
  writeFileSync(
    matrixGateEvidencePath,
    `${JSON.stringify(forgedMatrixGateEvidence, null, 2)}\n`,
  );
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/rejected-forged-matrix-raw-and-metadata.json'),
    ),
    /must exactly match the canonical PASSED report/,
  );
  writeFileSync(matrixReportPath, savedMatrixReport);
  writeFileSync(matrixGateEvidencePath, savedMatrixGateEvidence);

  function assertRejectsGateEvidenceMutation(gateId, label, mutate, pattern) {
    const path = resolve(gateDirectory, `${gateId}.json`);
    const saved = readFileSync(path, 'utf8');
    const substituted = JSON.parse(saved);
    mutate(substituted);
    writeFileSync(path, `${JSON.stringify(substituted, null, 2)}\n`);
    assert.throws(
      () => assembleReleaseEvidence(
        fixtureManifestPath,
        candidateCommit,
        artifactDescriptorPath,
        gateDirectory,
        fixturePath(`evidence/rejected-${label}.json`),
      ),
      pattern,
    );
    writeFileSync(path, saved);
  }

  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'wrong-contract',
    (value) => { value.receipt.contractId = 'soklet.release.substituted.v1'; },
    /typed receipt does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'matrix-closure',
    'forged-matrix-artifact-metadata',
    (value) => { value.evidence[0].artifact.sha256 = '0'.repeat(64); },
    /artifact metadata does not match the retained raw report/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'wrong-toolchain',
    (value) => { value.receipt.toolchain = 'nodePin'; },
    /typed receipt does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'wrong-candidate-sha',
    (value) => { value.receipt.candidateSha256 = '0'.repeat(64); },
    /typed receipt does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'v1-envelope',
    (value) => { value.formatVersion = 1; },
    /Invalid or incomplete PASS evidence/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'missing-receipt',
    (value) => { delete value.receipt; },
    /gate evidence keys must be exactly/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'wrong-media',
    (value) => { value.evidence[0].mediaType = 'application/json'; },
    /does not match its exact role contract/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'missing-evidence-role',
    (value) => { value.evidence = []; },
    /evidence roles and order must be exactly/,
  );
  for (const [gateId, label, role] of [
    ['candidate-build', 'missing-build-surefire', 'surefire-reports'],
    ['core-jdk-21', 'missing-core-jdk-21-distribution', 'java-distribution'],
    ['core-jdk-25', 'missing-jdk-distribution', 'java-distribution'],
    ['candidate-javadocs', 'missing-javadoc-surefire', 'surefire-reports'],
    ['static-analysis', 'missing-static-analysis-distribution', 'java-distribution'],
    ['spotbugs', 'missing-spotbugs-distribution', 'java-distribution'],
    ['spotbugs', 'missing-spotbugs-report', 'spotbugs-report'],
  ]) {
    assertRejectsGateEvidenceMutation(
      gateId,
      label,
      (value) => {
        value.evidence = value.evidence.filter((item) => item.role !== role);
      },
      /evidence roles and order must be exactly/,
    );
  }
  assertRejectsGateEvidenceMutation(
    'candidate-build',
    'reordered-evidence-roles',
    (value) => { [value.evidence[0], value.evidence[1]] = [value.evidence[1], value.evidence[0]]; },
    /evidence roles and order must be exactly/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-build',
    'substituted-descriptor',
    (value) => { value.evidence[0].artifact.sha256 = '0'.repeat(64); },
    /artifact descriptor role does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'core-jdk-21',
    'wrong-core-jdk-21-distribution',
    (value) => {
      value.evidence.find(({ role }) => role === 'java-distribution').artifact.sha256 =
        '0'.repeat(64);
    },
    /does not match the gate's exact manifest toolchain distribution/,
  );
  assertRejectsGateEvidenceMutation(
    'core-jdk-21',
    'wrong-core-jdk-21-toolchain',
    (value) => { value.receipt.toolchain = 'java'; },
    /typed receipt does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'static-analysis',
    'wrong-static-analysis-distribution',
    (value) => {
      value.evidence.find(({ role }) => role === 'java-distribution').artifact.sha256 =
        '0'.repeat(64);
    },
    /does not match the gate's exact manifest toolchain distribution/,
  );
  assertRejectsGateEvidenceMutation(
    'static-analysis',
    'wrong-static-analysis-command',
    (value) => { value.receipt.command = 'mvn compile'; },
    /typed receipt does not match/,
  );
  assertRejectsGateEvidenceMutation(
    'spotbugs',
    'wrong-spotbugs-distribution',
    (value) => {
      value.evidence.find(({ role }) => role === 'java-distribution').artifact.sha256 =
        '0'.repeat(64);
    },
    /does not match the gate's exact manifest toolchain distribution/,
  );
  assertRejectsGateEvidenceMutation(
    'spotbugs',
    'wrong-spotbugs-media',
    (value) => {
      value.evidence.find(({ role }) => role === 'spotbugs-report').mediaType = 'text/plain';
    },
    /does not match its exact role contract/,
  );
  assertRejectsGateEvidenceMutation(
    'soklet-servlet-javax',
    'wrong-default-jar-bytes',
    (value) => {
      value.evidence.find(({ role }) => role === 'default-jar').artifact.sha256 =
        '0'.repeat(64);
    },
    /does not match the gate's exact default artifact identity and SHA-256/,
  );
  assertRejectsGateEvidenceMutation(
    'candidate-localization',
    'wrong-workflow',
    (value) => { value.receipt.workflow.runId = '9876'; },
    /receipt workflow does not match/,
  );

  const typeScriptEvidencePath = resolve(gateDirectory, 'typescript-interop.json');
  const savedTypeScriptEvidence = readFileSync(typeScriptEvidencePath, 'utf8');
  const tamperedTypeScriptEvidence = JSON.parse(savedTypeScriptEvidence);
  tamperedTypeScriptEvidence.interoperability.sdkCommit = 'd'.repeat(40);
  writeFileSync(
    typeScriptEvidencePath,
    `${JSON.stringify(tamperedTypeScriptEvidence, null, 2)}\n`,
  );
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/tampered-interop-receipt.json'),
    ),
    /does not match the exact candidate, SDK pin, and fixture contract/,
  );
  writeFileSync(typeScriptEvidencePath, savedTypeScriptEvidence);

  const missingGatePath = resolve(gateDirectory, 'go-interop.json');
  const savedMissingGate = readFileSync(missingGatePath);
  rmSync(missingGatePath);
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/missing-gate.json'),
    ),
    /Gate evidence set must be exactly/,
  );
  writeFileSync(missingGatePath, savedMissingGate);

  process.env.GITHUB_SHA = 'c'.repeat(40);
  assert.throws(
    () => assembleReleaseEvidence(
      fixtureManifestPath,
      candidateCommit,
      artifactDescriptorPath,
      gateDirectory,
      fixturePath('evidence/wrong-workflow-sha.json'),
    ),
    /does not match candidate/,
  );

  console.log('Release-validation evidence self-test passed.');
} finally {
  rmSync(fixtureRoot, { recursive: true, force: true });
}
