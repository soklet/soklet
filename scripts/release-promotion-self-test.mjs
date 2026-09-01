#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  CENTRAL_REPOSITORY_BASE_URL,
  CENTRAL_STATUS_BASE_URL,
  CENTRAL_UPLOAD_URL,
  GATE_EVIDENCE_CONTRACTS,
  VERSION_TRANSITION_FINAL_PASS_LINE,
  canonicalJsonBytes,
  centralTransport,
  preparePromotion,
  readDeterministicZip,
  recordUserManagedStatus,
  recordUserManagedUpload,
  uploadUserManaged,
  validatePreparationRecord,
  verifyPromotionHarnessRegistryParity,
  verifyPublished,
} from './release-promotion.mjs';
import { EXPECTED_GATE_EVIDENCE_CONTRACTS } from './release-validation-evidence.mjs';

const CANDIDATE_COMMIT = '0123456789abcdef0123456789abcdef01234567';
const SIGNING_FINGERPRINT = 'ABCDEF0123456789ABCDEF0123456789ABCDEF01';
const OTHER_FINGERPRINT = '1111111111111111111111111111111111111111';
const DEPLOYMENT_ID = '12345678-1234-4234-8234-123456789abc';
const SECRET_AUTHORIZATION = 'Bearer dXNlcjpzdXBlci1zZWNyZXQ=';
const GATE_IDS = [
  'candidate-build',
  'core-jdk-21',
  'core-jdk-25',
  'isolated-install',
  'api-freeze',
  'candidate-javadocs',
  'static-analysis',
  'spotbugs',
  'schema-replay',
  'fuzz-replay',
  'soak-smoke',
  'release-soak',
  'localization-fleet',
  'release-scans',
  'mcp-benchmarks',
  'matrix-closure',
  'candidate-conformance',
  'candidate-localization',
  'barebones-app',
  'soklet-servlet-javax',
  'soklet-servlet-jakarta',
  'toystore-app',
  'soklet-otel',
  'soklet-website',
  'typescript-interop',
  'go-interop',
];

function digest(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function verifyGateContractParity() {
  assert.match(verifyPromotionHarnessRegistryParity(), /^[0-9a-f]{64}$/);
  assert.deepEqual(Object.keys(GATE_EVIDENCE_CONTRACTS), GATE_IDS);
  assert.deepEqual(Object.keys(EXPECTED_GATE_EVIDENCE_CONTRACTS), GATE_IDS);
  assert.equal(
    GATE_EVIDENCE_CONTRACTS['matrix-closure'].contractId,
    'soklet.release.matrix-closure.v2',
  );
  for (const gateId of GATE_IDS) {
    const promotion = GATE_EVIDENCE_CONTRACTS[gateId];
    const evidence = EXPECTED_GATE_EVIDENCE_CONTRACTS[gateId];
    assert.equal(promotion.command, evidence.command, `${gateId} command drift`);
    assert.equal(promotion.contractId, evidence.contractId, `${gateId} contract drift`);
    assert.equal(promotion.expectation, evidence.expectation, `${gateId} expectation drift`);
    assert.equal(promotion.profile, evidence.profile, `${gateId} profile drift`);
    assert.equal(promotion.toolchain, evidence.toolchain, `${gateId} toolchain drift`);
    assert.equal(promotion.roles.length, evidence.roles.length, `${gateId} role-count drift`);
    for (const [index, expected] of evidence.roles.entries()) {
      const actual = promotion.roles[index];
      assert.deepEqual(
        {
          fileName: actual.fileName,
          mediaType: actual.mediaType,
          role: actual.role,
          type: actual.type,
        },
        {
          fileName: expected.fileName,
          mediaType: expected.mediaType,
          role: expected.role,
          type: expected.type,
        },
        `${gateId} role ${index} drift`,
      );
      assert.equal(
        actual.binding,
        expected.candidateArtifact,
        `${gateId} role ${index} candidate binding drift`,
      );
    }
  }
}

function writeNew(path, bytes, mode) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { flag: 'wx', mode });
}

function evidenceItem(path) {
  const bytes = readFileSync(path);
  return {
    bytes: bytes.length,
    fileName: basename(path),
    sha256: digest(bytes),
    type: 'FILE',
  };
}

function syntheticToolchainDistributionBytes(toolchain) {
  return Buffer.from(
    `distribution=${toolchain.distribution}\n`
      + `version=${toolchain.version}\n`
      + `runtimeVersion=${toolchain.runtimeVersion}\n`
      + `vendorVersion=${toolchain.vendorVersion}\n`
      + `url=${toolchain.distributionUrl}\n`
      + `archive=${toolchain.archive}\n`
      + `archiveSha256=${toolchain.archiveSha256}\n`,
    'utf8',
  );
}

function syntheticEvidenceItem(specification, artifacts, gate, toolchains) {
  let artifact;
  if (specification.binding === 'gateToolchainDistribution') {
    const bytes = syntheticToolchainDistributionBytes(toolchains[gate.toolchain]);
    artifact = {
      bytes: bytes.length,
      fileName: specification.fileName,
      sha256: digest(bytes),
      type: 'FILE',
    };
  } else if (specification.binding !== null
      && specification.binding !== 'gateDefaultArtifact') {
    artifact = {
      ...artifacts[specification.binding],
      fileName: specification.fileName,
    };
  } else if (specification.type === 'DIRECTORY') {
    artifact = {
      algorithm: "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows",
      fileCount: 1,
      fileName: specification.fileName,
      sha256: digest(Buffer.from(specification.role, 'utf8')),
      type: 'DIRECTORY',
    };
  } else {
    artifact = {
      bytes: 1,
      fileName: specification.fileName,
      sha256: specification.binding === 'gateDefaultArtifact'
        ? gate.defaultArtifactSha256
        : digest(Buffer.from(specification.role, 'utf8')),
      type: 'FILE',
    };
  }
  return {
    artifact,
    mediaType: specification.mediaType,
    role: specification.role,
  };
}

function syntheticGate(id, artifacts, workflow, toolchains) {
  const isInteroperability = id === 'typescript-interop' || id === 'go-interop';
  const isServlet = id === 'soklet-servlet-javax' || id === 'soklet-servlet-jakarta';
  const artifactChecksum = isInteroperability ? `${id}-checksum` : null;
  const artifactIdentity = `${id}-identity`;
  const commit = isInteroperability ? '3'.repeat(40) : null;
  const contract = GATE_EVIDENCE_CONTRACTS[id];
  const gate = {
    artifactChecksum,
    artifactIdentity,
    commit,
    defaultArtifactIdentity: isServlet ? 'com.soklet:soklet:3.1.1' : null,
    defaultArtifactSha256: isServlet ? '4'.repeat(64) : null,
    evidenceContract: contract.contractId,
    id,
    repository: null,
    toolchain: contract.toolchain,
  };
  const interoperability = isInteroperability
    ? {
        candidateSha256: artifacts.mainJar.sha256,
        client: id === 'typescript-interop' ? 'typescript' : 'go',
        fixtureScenario: 'tools-list',
        fixtureShutdown: 'CLEAN',
        formatVersion: 1,
        protocolVersion: '2026-07-28',
        sdkArtifactChecksum: artifactChecksum,
        sdkArtifactIdentity: artifactIdentity,
        sdkCommit: commit,
        tool: 'test_simple_text',
      }
    : null;
  return {
    candidateCommit: CANDIDATE_COMMIT,
    evidence: contract.roles.map((specification) =>
      syntheticEvidenceItem(specification, artifacts, gate, toolchains)),
    formatVersion: 2,
    gate,
    interoperability,
    receipt: {
      candidateCommit: CANDIDATE_COMMIT,
      candidateSha256: artifacts.mainJar.sha256,
      command: contract.command,
      contractId: gate.evidenceContract,
      expectation: contract.expectation,
      formatVersion: 1,
      gateId: id,
      profile: contract.profile,
      result: 'PASS',
      toolchain: contract.toolchain,
      workflow,
    },
    status: 'PASS',
  };
}

function writeSyntheticInputs(root) {
  const artifactsDirectory = join(root, 'artifacts');
  mkdirSync(artifactsDirectory, { recursive: true });
  const paths = {
    javadocJar: join(artifactsDirectory, 'soklet-4.0.0-javadoc.jar'),
    mainJar: join(artifactsDirectory, 'soklet-4.0.0.jar'),
    pom: join(artifactsDirectory, 'pom.xml'),
    sourcesJar: join(artifactsDirectory, 'soklet-4.0.0-sources.jar'),
  };
  writeNew(
    paths.pom,
    Buffer.from(
      '<?xml version="1.0" encoding="UTF-8"?>\n'
        + '<project><modelVersion>4.0.0</modelVersion>'
        + '<groupId>com.soklet</groupId><artifactId>soklet</artifactId>'
        + '<version>4.0.0</version><packaging>jar</packaging></project>\n',
      'utf8',
    ),
  );
  writeNew(paths.mainJar, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x01, 0x02, 0x03]));
  writeNew(paths.sourcesJar, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x04, 0x05, 0x06, 0x07]));
  writeNew(paths.javadocJar, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x08, 0x09, 0x0a]));

  const artifacts = {
    javadocJar: evidenceItem(paths.javadocJar),
    mainJar: evidenceItem(paths.mainJar),
    pom: evidenceItem(paths.pom),
    sourcesJar: evidenceItem(paths.sourcesJar),
  };
  const coordinates = {
    artifactId: 'soklet',
    groupId: 'com.soklet',
    packaging: 'jar',
    version: '4.0.0',
  };
  const candidateDescriptorBytes = canonicalJsonBytes({
    artifacts,
    candidateCommit: CANDIDATE_COMMIT,
    coordinates,
    formatVersion: 1,
  });
  const candidateBindings = {
    ...artifacts,
    descriptor: {
      bytes: candidateDescriptorBytes.length,
      sha256: digest(candidateDescriptorBytes),
      type: 'FILE',
    },
  };
  const workflow = {
    job: 'validate',
    repository: 'soklet/soklet',
    runAttempt: '1',
    runId: '1234',
    serverUrl: 'https://github.com',
    sha: CANDIDATE_COMMIT,
  };
  const toolchains = {
    coreJdk21: {
      archive: 'amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
      archiveSha256: 'f79824540cef882da0cdf1369f9d1d69afc14b5a9bc3a771fd5bb795793ce2f2',
      distribution: 'corretto',
      distributionUrl:
        'https://corretto.aws/downloads/resources/21.0.12.9.1/amazon-corretto-21.0.12.9.1-linux-x64.tar.gz',
      runtimeVersion: '21.0.12.1+9-LTS',
      vendorVersion: 'Corretto-21.0.12.9.1',
      version: '21.0.12.1',
    },
    java: {
      archive: 'amazon-corretto-17.0.20.8.1-linux-x64.tar.gz',
      archiveSha256: '3'.repeat(64),
      distribution: 'corretto',
      distributionUrl:
        'https://corretto.aws/downloads/resources/17.0.20.8.1/amazon-corretto-17.0.20.8.1-linux-x64.tar.gz',
      runtimeVersion: '17.0.20+8-LTS',
      vendorVersion: 'Corretto-17.0.20.8.1',
      version: '17.0.20',
    },
    toystoreJava: {
      archive: 'amazon-corretto-25.0.4.7.1-linux-x64.tar.gz',
      archiveSha256: '4'.repeat(64),
      distribution: 'corretto',
      distributionUrl:
        'https://corretto.aws/downloads/resources/25.0.4.7.1/amazon-corretto-25.0.4.7.1-linux-x64.tar.gz',
      runtimeVersion: '25.0.4+7-LTS',
      vendorVersion: 'Corretto-25.0.4.7.1',
      version: '25.0.4',
    },
  };
  const evidence = {
    artifacts,
    candidateCommit: CANDIDATE_COMMIT,
    coordinates,
    formatVersion: 2,
    gates: GATE_IDS.map((id) => syntheticGate(id, candidateBindings, workflow, toolchains)),
    releaseConfigurationSha256: '2'.repeat(64),
    toolchains: {
      coreJdk21: '21.0.12.1',
      git: 'git version synthetic',
      go: 'go version synthetic',
      java: 'java version synthetic',
      maven: 'maven version synthetic',
      node: 'node version synthetic',
      npm: 'npm version synthetic',
      toystoreJava: 'toystore java version synthetic',
    },
    workflow,
  };
  const candidateBuildLogPath = join(root, 'candidate-build.log');
  const candidateBuildLogBytes = Buffer.from(
    `${VERSION_TRANSITION_FINAL_PASS_LINE}\n[INFO] synthetic candidate build passed\n`,
    'utf8',
  );
  writeNew(candidateBuildLogPath, candidateBuildLogBytes);
  const candidateBuildLog = evidence.gates
    .find(({ gate }) => gate.id === 'candidate-build')
    .evidence.find(({ role }) => role === 'build-log');
  candidateBuildLog.artifact = evidenceItem(candidateBuildLogPath);
  const promotionHelperBytes = readFileSync(
    fileURLToPath(new URL('./release-promotion.mjs', import.meta.url)),
  );
  const promotionWrapperBytes = readFileSync(
    fileURLToPath(new URL('./promote-release-candidate.sh', import.meta.url)),
  );
  const releaseManifest = {
    candidate: evidence.coordinates,
    formatVersion: 2,
    gates: evidence.gates.map(({ gate }) => ({
      access: 'SYNTHETIC',
      artifactChecksum: gate.artifactChecksum,
      artifactIdentity: gate.artifactIdentity,
      commit: gate.commit,
      defaultArtifactIdentity: gate.defaultArtifactIdentity,
      defaultArtifactSha256: gate.defaultArtifactSha256,
      evidenceContract: gate.evidenceContract,
      id: gate.id,
      kind: gate.id === 'typescript-interop' || gate.id === 'go-interop'
        ? 'INTEROPERABILITY'
        : 'SOURCE',
      reason: '',
      repository: gate.repository,
      status: 'READY',
      toolchain: gate.toolchain,
      versionProperty: null,
    })),
    promotion: {
      helper: {
        path: 'scripts/release-promotion.mjs',
        sha256: digest(promotionHelperBytes),
      },
      wrapper: {
        path: 'scripts/promote-release-candidate.sh',
        sha256: digest(promotionWrapperBytes),
      },
    },
    toolchains,
  };
  const releaseManifestPath = join(root, 'release-validation-manifest.json');
  const releaseManifestBytes = canonicalJsonBytes(releaseManifest);
  writeNew(releaseManifestPath, releaseManifestBytes);
  const releaseManifestSha256 = digest(releaseManifestBytes);
  evidence.releaseConfigurationSha256 = releaseManifestSha256;
  const evidencePath = join(root, 'release-validation-evidence.json');
  const evidenceBytes = canonicalJsonBytes(evidence);
  writeNew(evidencePath, evidenceBytes);
  return {
    artifactPaths: paths,
    candidateBuildLogBytes,
    candidateBuildLogPath,
    evidence,
    evidencePath,
    evidenceSha256: digest(evidenceBytes),
    releaseManifest,
    releaseManifestPath,
    releaseManifestSha256,
  };
}

function writeFakeGpg(root) {
  const path = join(root, 'fake-gpg.mjs');
  const source = `#!/usr/bin/env node
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const args = process.argv.slice(2);
if (args.includes('--detach-sign')) {
  const output = args[args.indexOf('--output') + 1];
  const fingerprint = args[args.indexOf('--local-user') + 1].replace(/!$/, '');
  const artifact = args.at(-1);
  const hash = createHash('sha256').update(readFileSync(artifact)).digest('hex');
  writeFileSync(output, '-----BEGIN PGP SIGNATURE-----\\n\\n' + Buffer.from(fingerprint + ':' + hash).toString('base64') + '\\n-----END PGP SIGNATURE-----\\n');
  if (process.env.SOKLET_FAKE_GPG_MUTATE === '1')
    appendFileSync(artifact, Buffer.from([0]));
  process.exit(0);
}
if (args.includes('--verify')) {
  const signature = readFileSync(args.at(-2), 'ascii');
  const encoded = signature.split('\\n')[2];
  const fingerprint = Buffer.from(encoded, 'base64').toString('ascii').split(':')[0];
  console.log('[GNUPG:] VALIDSIG ' + (process.env.SOKLET_FAKE_GPG_FINGERPRINT || fingerprint) + ' 0 0 0 0 0 0 0 0 0');
  process.exit(0);
}
process.exit(2);
`;
  writeNew(path, Buffer.from(source, 'utf8'), 0o700);
  chmodSync(path, 0o700);
  return path;
}

function prepare(root, inputs, fakeGpg, outputName) {
  return preparePromotion({
    artifactPaths: inputs.artifactPaths,
    candidateCommit: CANDIDATE_COMMIT,
    evidencePath: inputs.evidencePath,
    evidenceSha256: inputs.evidenceSha256,
    gpgPath: fakeGpg,
    outputDirectory: join(root, outputName),
    releaseManifestPath: inputs.releaseManifestPath,
    releaseManifestSha256: inputs.releaseManifestSha256,
    signingFingerprint: SIGNING_FINGERPRINT,
  });
}

function expectEvidenceFailure(root, inputs, fakeGpg, name, evidence, pattern) {
  const evidencePath = join(root, `${name}.json`);
  const evidenceBytes = canonicalJsonBytes(evidence);
  writeNew(evidencePath, evidenceBytes);
  expectFailure(
    () => preparePromotion({
      artifactPaths: inputs.artifactPaths,
      candidateCommit: CANDIDATE_COMMIT,
      evidencePath,
      evidenceSha256: digest(evidenceBytes),
      gpgPath: fakeGpg,
      outputDirectory: join(root, name),
      releaseManifestPath: inputs.releaseManifestPath,
      releaseManifestSha256: inputs.releaseManifestSha256,
      signingFingerprint: SIGNING_FINGERPRINT,
    }),
    pattern,
  );
}

function expectManifestFailure(root, inputs, fakeGpg, name, manifest, pattern) {
  const manifestPath = join(root, `${name}-manifest.json`);
  const manifestBytes = canonicalJsonBytes(manifest);
  writeNew(manifestPath, manifestBytes);
  const evidence = structuredClone(inputs.evidence);
  evidence.releaseConfigurationSha256 = digest(manifestBytes);
  const evidencePath = join(root, `${name}-evidence.json`);
  const evidenceBytes = canonicalJsonBytes(evidence);
  writeNew(evidencePath, evidenceBytes);
  expectFailure(
    () => preparePromotion({
      artifactPaths: inputs.artifactPaths,
      candidateCommit: CANDIDATE_COMMIT,
      evidencePath,
      evidenceSha256: digest(evidenceBytes),
      gpgPath: fakeGpg,
      outputDirectory: join(root, name),
      releaseManifestPath: manifestPath,
      releaseManifestSha256: digest(manifestBytes),
      signingFingerprint: SIGNING_FINGERPRINT,
    }),
    pattern,
  );
}

function expectRetainedBuildLogFailure(
  root,
  inputs,
  fakeGpg,
  name,
  pattern,
  {
    logBytes = inputs.candidateBuildLogBytes,
    metadataBytes = logBytes,
    mode = 'file',
  } = {},
) {
  const inputDirectory = join(root, `${name}-input`);
  mkdirSync(inputDirectory);
  const evidence = structuredClone(inputs.evidence);
  const buildLog = evidence.gates
    .find(({ gate }) => gate.id === 'candidate-build')
    .evidence.find(({ role }) => role === 'build-log');
  buildLog.artifact = {
    bytes: metadataBytes.length,
    fileName: 'candidate-build.log',
    sha256: digest(metadataBytes),
    type: 'FILE',
  };
  const evidencePath = join(inputDirectory, 'release-validation-evidence.json');
  const evidenceBytes = canonicalJsonBytes(evidence);
  writeNew(evidencePath, evidenceBytes);

  const retainedLogPath = join(inputDirectory, 'candidate-build.log');
  if (mode === 'file') {
    writeNew(retainedLogPath, logBytes);
  } else if (mode === 'symlink') {
    const target = join(inputDirectory, 'real-candidate-build.log');
    writeNew(target, logBytes);
    symlinkSync(target, retainedLogPath);
  } else {
    assert.equal(mode, 'missing');
  }

  expectFailure(
    () => preparePromotion({
      artifactPaths: inputs.artifactPaths,
      candidateCommit: CANDIDATE_COMMIT,
      evidencePath,
      evidenceSha256: digest(evidenceBytes),
      gpgPath: fakeGpg,
      outputDirectory: join(root, `${name}-output`),
      releaseManifestPath: inputs.releaseManifestPath,
      releaseManifestSha256: inputs.releaseManifestSha256,
      signingFingerprint: SIGNING_FINGERPRINT,
    }),
    pattern,
  );
}

function expectFailure(action, pattern) {
  assert.throws(action, pattern);
}

async function expectAsyncFailure(action, pattern) {
  await assert.rejects(action, pattern);
}

function jsonResponse(state) {
  return {
    body: Buffer.from(JSON.stringify({ deploymentId: DEPLOYMENT_ID, deploymentState: state })),
    status: 200,
  };
}

function sequentialTransport(responses, requests) {
  let index = 0;
  return async (request) => {
    requests.push(request);
    assert.ok(index < responses.length, 'unexpected extra transport request');
    const response = responses[index++];
    return typeof response === 'function' ? response(request) : response;
  };
}

function clock() {
  let value = 0;
  return {
    now: () => value,
    sleep: async (milliseconds) => {
      value += milliseconds;
    },
  };
}

async function run() {
  verifyGateContractParity();
  const temporary = mkdtempSync(join(tmpdir(), 'soklet-promotion-self-test-'));
  try {
    const inputs = writeSyntheticInputs(temporary);
    const fakeGpg = writeFakeGpg(temporary);
    const first = prepare(temporary, inputs, fakeGpg, 'first');
    const second = prepare(temporary, inputs, fakeGpg, 'second');
    const firstBundle = readFileSync(first.bundlePath);
    const secondBundle = readFileSync(second.bundlePath);
    const firstPreparationBytes = readFileSync(first.preparationPath);
    const secondPreparationBytes = readFileSync(second.preparationPath);

    const wrapperPath = fileURLToPath(new URL('./promote-release-candidate.sh', import.meta.url));
    const cliOutput = join(temporary, 'cli-output');
    const cliPrepare = spawnSync(
      wrapperPath,
      [
        'prepare',
        '--evidence', inputs.evidencePath,
        '--evidence-sha256', inputs.evidenceSha256,
        '--release-manifest', inputs.releaseManifestPath,
        '--release-manifest-sha256', inputs.releaseManifestSha256,
        '--candidate-commit', CANDIDATE_COMMIT,
        '--pom', inputs.artifactPaths.pom,
        '--main-jar', inputs.artifactPaths.mainJar,
        '--sources-jar', inputs.artifactPaths.sourcesJar,
        '--javadoc-jar', inputs.artifactPaths.javadocJar,
        '--signing-fingerprint', SIGNING_FINGERPRINT,
        '--gpg', fakeGpg,
        '--output-directory', cliOutput,
      ],
      { encoding: 'utf8' },
    );
    assert.equal(cliPrepare.status, 0, cliPrepare.stderr);
    assert.deepEqual(readFileSync(join(cliOutput, basename(first.bundlePath))), firstBundle);

    assert.deepEqual(firstBundle, secondBundle, 'same inputs must produce the same bundle bytes');
    assert.deepEqual(
      firstPreparationBytes,
      secondPreparationBytes,
      'same inputs must produce the same canonical preparation evidence',
    );
    assert.deepEqual(firstPreparationBytes, canonicalJsonBytes(first.preparation));
    assert.equal(first.preparation.centralPolicy.publishEndpointInvoked, false);
    assert.equal(first.preparation.centralPolicy.publishingType, 'USER_MANAGED');
    assert.equal(first.preparation.centralPolicy.uploadUrl, CENTRAL_UPLOAD_URL);
    assert.equal(first.preparation.signing.fingerprint, SIGNING_FINGERPRINT);
    assert.equal(
      first.preparation.promotionTool.helper.sha256,
      digest(readFileSync(fileURLToPath(new URL('./release-promotion.mjs', import.meta.url)))),
    );
    assert.equal(
      first.preparation.promotionTool.wrapper.sha256,
      digest(readFileSync(fileURLToPath(new URL('./promote-release-candidate.sh', import.meta.url)))),
    );

    const entries = readDeterministicZip(firstBundle);
    assert.equal(entries.length, 24, 'four base files each need one signature and four checksums');
    assert.deepEqual(
      entries.map((entry) => entry.path),
      [...entries.map((entry) => entry.path)].sort(),
      'bundle entries must be ASCII-sorted',
    );
    assert.ok(entries.every((entry) => entry.path.startsWith('com/soklet/soklet/4.0.0/')));
    assert.equal(entries.filter((entry) => entry.path.endsWith('.asc')).length, 4);
    assert.equal(entries.filter((entry) => /\.asc\.(?:md5|sha1|sha256|sha512)$/.test(entry.path)).length, 0);
    assert.equal(entries.filter((entry) => /\.(?:md5|sha1|sha256|sha512)$/.test(entry.path)).length, 16);
    validatePreparationRecord(first.preparation, firstBundle);

    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'missing-retained-build-log',
      /readable, regular, nonsymlink/,
      { mode: 'missing' },
    );
    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'symlink-retained-build-log',
      /readable, regular, nonsymlink/,
      { mode: 'symlink' },
    );
    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'mismatched-retained-build-log',
      /does not match release-validation evidence/,
      {
        logBytes: Buffer.concat([
          inputs.candidateBuildLogBytes,
          Buffer.from('[INFO] unrecorded bytes\n', 'utf8'),
        ]),
        metadataBytes: inputs.candidateBuildLogBytes,
      },
    );
    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-stage-retained-build-log',
      /exactly one canonical version-transition final-stage PASS line/,
      {
        logBytes: Buffer.from(
          `${VERSION_TRANSITION_FINAL_PASS_LINE.replace('stage=final', 'stage=post-retarget')}\n`,
          'utf8',
        ),
      },
    );
    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-count-retained-build-log',
      /exactly one canonical version-transition final-stage PASS line/,
      {
        logBytes: Buffer.from(
          `${VERSION_TRANSITION_FINAL_PASS_LINE.replace('occurrences=365', 'occurrences=364')}\n`,
          'utf8',
        ),
      },
    );
    expectRetainedBuildLogFailure(
      temporary,
      inputs,
      fakeGpg,
      'duplicate-pass-retained-build-log',
      /exactly one canonical version-transition final-stage PASS line/,
      {
        logBytes: Buffer.from(
          `${VERSION_TRANSITION_FINAL_PASS_LINE}\n${VERSION_TRANSITION_FINAL_PASS_LINE}\n`,
          'utf8',
        ),
      },
    );

    const corruptedBundle = Buffer.from(firstBundle);
    corruptedBundle[40] ^= 0x01;
    expectFailure(
      () => validatePreparationRecord(first.preparation, corruptedBundle),
      /bundle identity|CRC|metadata|match/,
    );

    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: inputs.evidencePath,
        evidenceSha256: '0'.repeat(64),
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'bad-evidence-sha'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /independently supplied SHA-256/,
    );

    const compactEvidencePath = join(temporary, 'compact-evidence.json');
    const compactEvidence = Buffer.from(JSON.stringify(inputs.evidence), 'utf8');
    writeNew(compactEvidencePath, compactEvidence);
    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: compactEvidencePath,
        evidenceSha256: digest(compactEvidence),
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'noncanonical'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /canonical JSON encoding/,
    );

    const legacyFormatEvidence = structuredClone(inputs.evidence);
    legacyFormatEvidence.formatVersion = 1;
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'legacy-v1-evidence',
      legacyFormatEvidence,
      /does not identify the supplied candidate commit/,
    );

    const legacyGateIds = new Set([
      'candidate-build',
      'isolated-install',
      'release-soak',
      'candidate-conformance',
      'candidate-localization',
      'barebones-app',
      'soklet-servlet-javax',
      'soklet-servlet-jakarta',
      'toystore-app',
      'soklet-otel',
      'soklet-website',
      'typescript-interop',
      'go-interop',
    ]);
    const legacyGateSetEvidence = structuredClone(inputs.evidence);
    legacyGateSetEvidence.gates = legacyGateSetEvidence.gates.filter(({ gate }) =>
      legacyGateIds.has(gate.id));
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'legacy-13-gate-evidence',
      legacyGateSetEvidence,
      /exact ordered set/,
    );

    const missingGateEvidence = structuredClone(inputs.evidence);
    missingGateEvidence.gates.splice(5, 1);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'missing-gate-evidence',
      missingGateEvidence,
      /exact ordered set/,
    );

    const extraGateEvidence = structuredClone(inputs.evidence);
    extraGateEvidence.gates.push(structuredClone(extraGateEvidence.gates.at(-1)));
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'extra-gate-evidence',
      extraGateEvidence,
      /exact ordered set/,
    );

    const reorderedGateEvidence = structuredClone(inputs.evidence);
    [reorderedGateEvidence.gates[1], reorderedGateEvidence.gates[2]] =
      [reorderedGateEvidence.gates[2], reorderedGateEvidence.gates[1]];
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'reordered-gate-evidence',
      reorderedGateEvidence,
      /exact ordered set/,
    );

    const legacyManifest = structuredClone(inputs.releaseManifest);
    legacyManifest.formatVersion = 1;
    expectManifestFailure(
      temporary,
      inputs,
      fakeGpg,
      'legacy-v1-manifest',
      legacyManifest,
      /formatVersion must be 2/,
    );

    const legacyGateSetManifest = structuredClone(inputs.releaseManifest);
    legacyGateSetManifest.gates = legacyGateSetManifest.gates.filter(({ id }) =>
      legacyGateIds.has(id));
    expectManifestFailure(
      temporary,
      inputs,
      fakeGpg,
      'legacy-13-gate-manifest',
      legacyGateSetManifest,
      /gates must be exactly/,
    );

    const missingGateManifest = structuredClone(inputs.releaseManifest);
    missingGateManifest.gates.splice(5, 1);
    expectManifestFailure(
      temporary,
      inputs,
      fakeGpg,
      'missing-gate-manifest',
      missingGateManifest,
      /gates must be exactly/,
    );

    const extraGateManifest = structuredClone(inputs.releaseManifest);
    extraGateManifest.gates.push(structuredClone(extraGateManifest.gates.at(-1)));
    expectManifestFailure(
      temporary,
      inputs,
      fakeGpg,
      'extra-gate-manifest',
      extraGateManifest,
      /gates must be exactly/,
    );

    const reorderedGateManifest = structuredClone(inputs.releaseManifest);
    [reorderedGateManifest.gates[1], reorderedGateManifest.gates[2]] =
      [reorderedGateManifest.gates[2], reorderedGateManifest.gates[1]];
    expectManifestFailure(
      temporary,
      inputs,
      fakeGpg,
      'reordered-gate-manifest',
      reorderedGateManifest,
      /gates must be exactly/,
    );

    for (const [label, mutate, pattern] of [
      [
        'url',
        (toolchain) => { toolchain.distributionUrl = 'https://example.invalid/substituted.tar.gz'; },
        /fields do not match its exact Corretto distribution/,
      ],
      [
        'sha',
        (toolchain) => { toolchain.archiveSha256 = '9'.repeat(64); },
        /Java distribution evidence does not match its exact manifest toolchain/,
      ],
      [
        'runtime',
        (toolchain) => { toolchain.runtimeVersion = '21.0.12.1+8-LTS'; },
        /fields do not match its exact Corretto distribution/,
      ],
      [
        'vendor',
        (toolchain) => { toolchain.vendorVersion = 'Corretto-21.0.12.8.1'; },
        /fields do not match its exact Corretto distribution/,
      ],
    ]) {
      const manifest = structuredClone(inputs.releaseManifest);
      mutate(manifest.toolchains.coreJdk21);
      expectManifestFailure(
        temporary,
        inputs,
        fakeGpg,
        `wrong-core-jdk-21-${label}-manifest`,
        manifest,
        pattern,
      );
    }

    const legacyGateEnvelopeEvidence = structuredClone(inputs.evidence);
    legacyGateEnvelopeEvidence.gates[0].formatVersion = 1;
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'legacy-gate-envelope',
      legacyGateEnvelopeEvidence,
      /not complete PASS evidence/,
    );

    const missingReceiptEvidence = structuredClone(inputs.evidence);
    delete missingReceiptEvidence.gates[0].receipt;
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'missing-typed-receipt',
      missingReceiptEvidence,
      /gate evidence keys must be exactly/,
    );

    const wrongContractReceiptEvidence = structuredClone(inputs.evidence);
    wrongContractReceiptEvidence.gates[0].receipt.contractId =
      'soklet.release.candidate-build.v0';
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-contract',
      wrongContractReceiptEvidence,
      /typed receipt does not match/,
    );

    const wrongCommitReceiptEvidence = structuredClone(inputs.evidence);
    wrongCommitReceiptEvidence.gates[2].receipt.candidateCommit = 'f'.repeat(40);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-candidate-commit',
      wrongCommitReceiptEvidence,
      /typed receipt does not match/,
    );

    const wrongWorkflowReceiptEvidence = structuredClone(inputs.evidence);
    wrongWorkflowReceiptEvidence.gates[2].receipt.workflow = {
      ...wrongWorkflowReceiptEvidence.gates[2].receipt.workflow,
      runId: '9999',
    };
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-workflow',
      wrongWorkflowReceiptEvidence,
      /typed receipt does not match/,
    );

    const wrongToolchainReceiptEvidence = structuredClone(inputs.evidence);
    wrongToolchainReceiptEvidence.gates[1].receipt.toolchain = 'java';
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-toolchain',
      wrongToolchainReceiptEvidence,
      /typed receipt does not match/,
    );

    const coreJdk21GateIndex = GATE_IDS.indexOf('core-jdk-21');
    const coreJdk21DistributionRoleIndex = inputs.evidence.gates[coreJdk21GateIndex]
      .evidence.findIndex(({ role }) => role === 'java-distribution');
    const wrongCoreJdk21DistributionEvidence = structuredClone(inputs.evidence);
    wrongCoreJdk21DistributionEvidence.gates[coreJdk21GateIndex]
      .evidence[coreJdk21DistributionRoleIndex].artifact.sha256 = '7'.repeat(64);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-core-jdk-21-distribution-evidence',
      wrongCoreJdk21DistributionEvidence,
      /Java distribution evidence does not match its exact manifest toolchain/,
    );

    for (const gateId of ['static-analysis', 'spotbugs']) {
      const gateIndex = GATE_IDS.indexOf(gateId);
      const distributionRoleIndex = inputs.evidence.gates[gateIndex].evidence
        .findIndex(({ role }) => role === 'java-distribution');
      const wrongDistributionEvidence = structuredClone(inputs.evidence);
      wrongDistributionEvidence.gates[gateIndex]
        .evidence[distributionRoleIndex].artifact.sha256 = '6'.repeat(64);
      expectEvidenceFailure(
        temporary,
        inputs,
        fakeGpg,
        `wrong-${gateId}-distribution-evidence`,
        wrongDistributionEvidence,
        /Java distribution evidence does not match its exact manifest toolchain/,
      );
    }

    const missingStaticAnalysisDistributionEvidence = structuredClone(inputs.evidence);
    missingStaticAnalysisDistributionEvidence.gates[GATE_IDS.indexOf('static-analysis')]
      .evidence.splice(1, 1);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'missing-static-analysis-distribution-evidence',
      missingStaticAnalysisDistributionEvidence,
      /does not contain its exact ordered evidence roles/,
    );

    const reorderedSpotbugsEvidence = structuredClone(inputs.evidence);
    const spotbugsEvidence = reorderedSpotbugsEvidence
      .gates[GATE_IDS.indexOf('spotbugs')].evidence;
    [spotbugsEvidence[1], spotbugsEvidence[2]] = [spotbugsEvidence[2], spotbugsEvidence[1]];
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'reordered-spotbugs-evidence',
      reorderedSpotbugsEvidence,
      /does not match role java-distribution/,
    );

    const wrongCandidateReceiptEvidence = structuredClone(inputs.evidence);
    wrongCandidateReceiptEvidence.gates[2].receipt.candidateSha256 = '9'.repeat(64);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-candidate',
      wrongCandidateReceiptEvidence,
      /typed receipt does not match/,
    );

    const wrongCommandReceiptEvidence = structuredClone(inputs.evidence);
    wrongCommandReceiptEvidence.gates[3].receipt.command = 'mvn test';
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-receipt-command',
      wrongCommandReceiptEvidence,
      /typed receipt does not match/,
    );

    const reorderedRolesEvidence = structuredClone(inputs.evidence);
    [reorderedRolesEvidence.gates[0].evidence[0], reorderedRolesEvidence.gates[0].evidence[1]] =
      [reorderedRolesEvidence.gates[0].evidence[1], reorderedRolesEvidence.gates[0].evidence[0]];
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'reordered-evidence-roles',
      reorderedRolesEvidence,
      /does not match role/,
    );

    const servletGateIndex = GATE_IDS.indexOf('soklet-servlet-javax');
    const servletDefaultRoleIndex = inputs.evidence.gates[servletGateIndex].evidence
      .findIndex(({ role }) => role === 'default-jar');
    const wrongDefaultShaEvidence = structuredClone(inputs.evidence);
    wrongDefaultShaEvidence.gates[servletGateIndex]
      .evidence[servletDefaultRoleIndex].artifact.sha256 = '8'.repeat(64);
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-default-artifact-sha',
      wrongDefaultShaEvidence,
      /default JAR evidence does not match its exact identity and SHA-256/,
    );

    const wrongDefaultIdentityEvidence = structuredClone(inputs.evidence);
    wrongDefaultIdentityEvidence.gates[servletGateIndex].gate.defaultArtifactIdentity =
      'com.soklet:soklet:3.1.2';
    expectEvidenceFailure(
      temporary,
      inputs,
      fakeGpg,
      'wrong-default-artifact-identity',
      wrongDefaultIdentityEvidence,
      /default JAR evidence does not match its exact identity and SHA-256/,
    );

    const incompleteEvidence = structuredClone(inputs.evidence);
    incompleteEvidence.gates[0].status = 'SKIPPED';
    const incompleteEvidencePath = join(temporary, 'incomplete-evidence.json');
    const incompleteEvidenceBytes = canonicalJsonBytes(incompleteEvidence);
    writeNew(incompleteEvidencePath, incompleteEvidenceBytes);
    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: incompleteEvidencePath,
        evidenceSha256: digest(incompleteEvidenceBytes),
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'incomplete-evidence'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /not complete PASS evidence/,
    );

    const defaultPinDriftEvidence = structuredClone(inputs.evidence);
    defaultPinDriftEvidence.gates[0].gate.defaultArtifactIdentity =
      'com.soklet:soklet:3.1.1';
    defaultPinDriftEvidence.gates[0].gate.defaultArtifactSha256 = '0'.repeat(64);
    const defaultPinDriftPath = join(temporary, 'default-pin-drift-evidence.json');
    const defaultPinDriftBytes = canonicalJsonBytes(defaultPinDriftEvidence);
    writeNew(defaultPinDriftPath, defaultPinDriftBytes);
    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: defaultPinDriftPath,
        evidenceSha256: digest(defaultPinDriftBytes),
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'default-pin-drift'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /does not match release-validation evidence/,
    );

    const receiptDriftEvidence = structuredClone(inputs.evidence);
    receiptDriftEvidence.gates.at(-1).interoperability.candidateSha256 = '9'.repeat(64);
    const receiptDriftPath = join(temporary, 'receipt-drift-evidence.json');
    const receiptDriftBytes = canonicalJsonBytes(receiptDriftEvidence);
    writeNew(receiptDriftPath, receiptDriftBytes);
    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: receiptDriftPath,
        evidenceSha256: digest(receiptDriftBytes),
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'receipt-drift'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /receipt does not match/,
    );

    const wrongCommit = `f${CANDIDATE_COMMIT.slice(1)}`;
    expectFailure(
      () => preparePromotion({
        artifactPaths: inputs.artifactPaths,
        candidateCommit: wrongCommit,
        evidencePath: inputs.evidencePath,
        evidenceSha256: inputs.evidenceSha256,
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'wrong-commit'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /candidate commit/,
    );

    const originalMain = readFileSync(inputs.artifactPaths.mainJar);
    writeFileSync(inputs.artifactPaths.mainJar, Buffer.concat([originalMain, Buffer.from([0])]));
    expectFailure(
      () => prepare(temporary, inputs, fakeGpg, 'tampered-artifact'),
      /do not match release-validation evidence/,
    );
    writeFileSync(inputs.artifactPaths.mainJar, originalMain);

    const linkDirectory = join(temporary, 'link-artifacts');
    mkdirSync(linkDirectory);
    const linkPath = join(linkDirectory, basename(inputs.artifactPaths.mainJar));
    symlinkSync(inputs.artifactPaths.mainJar, linkPath);
    expectFailure(
      () => preparePromotion({
        artifactPaths: { ...inputs.artifactPaths, mainJar: linkPath },
        candidateCommit: CANDIDATE_COMMIT,
        evidencePath: inputs.evidencePath,
        evidenceSha256: inputs.evidenceSha256,
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'symlink-artifact'),
        releaseManifestPath: inputs.releaseManifestPath,
        releaseManifestSha256: inputs.releaseManifestSha256,
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /nonsymlink/,
    );

    process.env.SOKLET_FAKE_GPG_FINGERPRINT = OTHER_FINGERPRINT;
    expectFailure(
      () => prepare(temporary, inputs, fakeGpg, 'wrong-signing-key'),
      /exact supplied full fingerprint/,
    );
    delete process.env.SOKLET_FAKE_GPG_FINGERPRINT;

    process.env.SOKLET_FAKE_GPG_MUTATE = '1';
    expectFailure(
      () => prepare(temporary, inputs, fakeGpg, 'signer-mutated-artifact'),
      /modified a staged candidate artifact/,
    );
    delete process.env.SOKLET_FAKE_GPG_MUTATE;

    const existingOutput = join(temporary, 'existing-output');
    mkdirSync(existingOutput);
    expectFailure(
      () => prepare(temporary, inputs, fakeGpg, 'existing-output'),
      /Refusing to overwrite or merge/,
    );

    const preparationSha256 = digest(firstPreparationBytes);
    const uploadRequests = [];
    const uploadClock = clock();
    const upload = await uploadUserManaged({
      authorization: SECRET_AUTHORIZATION,
      bundlePath: first.bundlePath,
      onAccepted: async () => {},
      now: uploadClock.now,
      pollIntervalSeconds: 1,
      preparationPath: first.preparationPath,
      preparationSha256,
      sleep: uploadClock.sleep,
      timeoutSeconds: 10,
      transport: sequentialTransport([
        { body: Buffer.from(`${DEPLOYMENT_ID}\n`), status: 201 },
        jsonResponse('PENDING'),
        jsonResponse('VALIDATING'),
        jsonResponse('VALIDATED'),
      ], uploadRequests),
    });
    assert.equal(upload.central.state, 'VALIDATED');
    assert.equal(upload.central.deploymentId, DEPLOYMENT_ID);
    assert.equal(upload.central.publishEndpointInvoked, false);
    assert.equal(uploadRequests[0].url, CENTRAL_UPLOAD_URL);
    assert.equal(uploadRequests[0].method, 'POST');
    assert.match(uploadRequests[0].headers['Content-Type'], /^multipart\/form-data; boundary=/);
    assert.match(uploadRequests[0].body.toString('latin1'), /name="bundle"; filename="soklet-4\.0\.0-central-bundle\.zip"/);
    assert.ok(uploadRequests.slice(1).every((request) =>
      request.url === `${CENTRAL_STATUS_BASE_URL}${DEPLOYMENT_ID}` && request.method === 'POST'));
    assert.ok(!JSON.stringify(upload).includes(SECRET_AUTHORIZATION));

    await expectAsyncFailure(
      () => uploadUserManaged({
        authorization: SECRET_AUTHORIZATION,
        bundlePath: first.bundlePath,
        onAccepted: async () => {},
        pollIntervalSeconds: 1,
        preparationPath: first.preparationPath,
        preparationSha256,
        timeoutSeconds: 10,
        transport: sequentialTransport([{ body: Buffer.from(DEPLOYMENT_ID), status: 200 }], []),
      }),
      /HTTP 201/,
    );
    await expectAsyncFailure(
      () => uploadUserManaged({
        authorization: SECRET_AUTHORIZATION,
        bundlePath: first.bundlePath,
        onAccepted: async () => {},
        pollIntervalSeconds: 1,
        preparationPath: first.preparationPath,
        preparationSha256,
        timeoutSeconds: 10,
        transport: sequentialTransport([{ body: Buffer.from('not-a-uuid'), status: 201 }], []),
      }),
      /deployment UUID/,
    );
    await expectAsyncFailure(
      () => uploadUserManaged({
        authorization: SECRET_AUTHORIZATION,
        bundlePath: first.bundlePath,
        onAccepted: async () => {},
        pollIntervalSeconds: 1,
        preparationPath: first.preparationPath,
        preparationSha256,
        timeoutSeconds: 10,
        transport: sequentialTransport([
          { body: Buffer.from(DEPLOYMENT_ID), status: 201 },
          jsonResponse('PUBLISHED'),
        ], []),
      }),
      /disallowed deployment state/,
    );
    const failed = await uploadUserManaged({
      authorization: SECRET_AUTHORIZATION,
      bundlePath: first.bundlePath,
      onAccepted: async () => {},
      pollIntervalSeconds: 1,
      preparationPath: first.preparationPath,
      preparationSha256,
      timeoutSeconds: 10,
      transport: sequentialTransport([
        { body: Buffer.from(DEPLOYMENT_ID), status: 201 },
        jsonResponse('FAILED'),
      ], []),
    });
    assert.equal(failed.central.state, 'FAILED');

    const timeoutClock = clock();
    const acceptedEvidencePath = join(temporary, 'timeout-accepted.json');
    const timeoutTerminalPath = join(temporary, 'timeout-terminal.json');
    await expectAsyncFailure(
      () => recordUserManagedUpload({
        acceptedOutputPath: acceptedEvidencePath,
        authorization: SECRET_AUTHORIZATION,
        bundlePath: first.bundlePath,
        now: timeoutClock.now,
        outputPath: timeoutTerminalPath,
        pollIntervalSeconds: 1,
        preparationPath: first.preparationPath,
        preparationSha256,
        sleep: timeoutClock.sleep,
        timeoutSeconds: 1,
        transport: async (request) => request.url === CENTRAL_UPLOAD_URL
          ? { body: Buffer.from(DEPLOYMENT_ID), status: 201 }
          : jsonResponse('PENDING'),
      }),
      /timed out/,
    );
    assert.equal(existsSync(acceptedEvidencePath), true, 'accepted UUID must survive poll timeout');
    assert.equal(existsSync(timeoutTerminalPath), false, 'incomplete terminal evidence must be removed');
    const acceptedEvidenceBytes = readFileSync(acceptedEvidencePath);
    const acceptedEvidence = JSON.parse(acceptedEvidenceBytes.toString('utf8'));
    assert.deepEqual(acceptedEvidenceBytes, canonicalJsonBytes(acceptedEvidence));
    assert.equal(acceptedEvidence.mode, 'CENTRAL_USER_MANAGED_ACCEPTED');
    assert.equal(acceptedEvidence.central.deploymentId, DEPLOYMENT_ID);
    assert.equal(acceptedEvidence.central.state, 'ACCEPTED');

    const statusRequests = [];
    const statusClock = clock();
    const statusResult = await recordUserManagedStatus({
      acceptedEvidencePath,
      acceptedEvidenceSha256: digest(acceptedEvidenceBytes),
      authorization: SECRET_AUTHORIZATION,
      bundlePath: first.bundlePath,
      now: statusClock.now,
      outputPath: join(temporary, 'resumed-terminal.json'),
      pollIntervalSeconds: 1,
      preparationPath: first.preparationPath,
      preparationSha256,
      sleep: statusClock.sleep,
      timeoutSeconds: 10,
      transport: sequentialTransport([
        jsonResponse('PENDING'),
        jsonResponse('VALIDATED'),
      ], statusRequests),
    });
    assert.equal(statusResult.record.central.state, 'VALIDATED');
    assert.ok(statusRequests.every((request) =>
      request.method === 'POST'
        && request.url === `${CENTRAL_STATUS_BASE_URL}${DEPLOYMENT_ID}`));
    assert.ok(statusRequests.every((request) => request.url !== CENTRAL_UPLOAD_URL));

    const uploadEvidencePath = statusResult.outputPath;
    const uploadEvidenceBytes = readFileSync(uploadEvidencePath);
    const uploadEvidenceSha256 = digest(uploadEvidenceBytes);
    const entryMap = new Map(entries.map((entry) => [entry.path, entry.bytes]));
    const publishedRequests = [];
    const publishedClock = clock();
    let transientRepositoryMiss = true;
    const publishedTransport = async (request) => {
      publishedRequests.push(request);
      if (request.url === `${CENTRAL_STATUS_BASE_URL}${DEPLOYMENT_ID}`) {
        const statusCount = publishedRequests.filter((candidate) =>
          candidate.url === request.url).length;
        return jsonResponse(statusCount === 1 ? 'PUBLISHING' : 'PUBLISHED');
      }
      assert.equal(request.method, 'GET');
      assert.ok(request.url.startsWith(CENTRAL_REPOSITORY_BASE_URL));
      if (transientRepositoryMiss) {
        transientRepositoryMiss = false;
        return { body: Buffer.from('not propagated'), status: 404 };
      }
      const fileName = request.url.slice(CENTRAL_REPOSITORY_BASE_URL.length);
      const bytes = entryMap.get(`com/soklet/soklet/4.0.0/${fileName}`);
      assert.ok(bytes !== undefined, `unexpected published artifact ${fileName}`);
      return { body: bytes, status: 200 };
    };
    const published = await verifyPublished({
      authorization: SECRET_AUTHORIZATION,
      bundlePath: first.bundlePath,
      now: publishedClock.now,
      pollIntervalSeconds: 1,
      preparationPath: first.preparationPath,
      preparationSha256,
      sleep: publishedClock.sleep,
      timeoutSeconds: 10,
      transport: publishedTransport,
      uploadEvidencePath,
      uploadEvidenceSha256,
    });
    assert.equal(published.central.state, 'PUBLISHED');
    assert.equal(Object.keys(published.artifacts).length, 4);
    assert.ok(!JSON.stringify(published).includes(SECRET_AUTHORIZATION));
    assert.equal(publishedRequests.filter((request) => request.method === 'GET').length, 5);
    assert.ok(publishedRequests.every((request) =>
      request.url.startsWith(CENTRAL_STATUS_BASE_URL)
        || request.url.startsWith(CENTRAL_REPOSITORY_BASE_URL)));

    let corruptedDownload = false;
    await expectAsyncFailure(
      () => verifyPublished({
        authorization: SECRET_AUTHORIZATION,
        bundlePath: first.bundlePath,
        pollIntervalSeconds: 1,
        preparationPath: first.preparationPath,
        preparationSha256,
        timeoutSeconds: 10,
        transport: async (request) => {
          if (request.url.startsWith(CENTRAL_STATUS_BASE_URL))
            return jsonResponse('PUBLISHED');
          const fileName = request.url.slice(CENTRAL_REPOSITORY_BASE_URL.length);
          const bytes = entryMap.get(`com/soklet/soklet/4.0.0/${fileName}`);
          if (!corruptedDownload) {
            corruptedDownload = true;
            return { body: Buffer.concat([bytes, Buffer.from([0])]), status: 200 };
          }
          return { body: bytes, status: 200 };
        },
        uploadEvidencePath,
        uploadEvidenceSha256,
      }),
      /does not match candidate evidence/,
    );

    const helperSource = readFileSync(new URL('./release-promotion.mjs', import.meta.url), 'utf8');
    const wrapperSource = readFileSync(new URL('./promote-release-candidate.sh', import.meta.url), 'utf8');
    assert.ok(!helperSource.includes('/publisher/deployment/'), 'helper must have no publish endpoint');
    assert.ok(!helperSource.includes('--token'), 'credentials must not be accepted on argv');
    assert.match(wrapperSource, /prepare\|upload\|status\|verify-published/);
    assert.ok(!wrapperSource.includes('publish)'));
    const rejectedMode = spawnSync(
      'bash',
      [wrapperPath, 'publish'],
      { encoding: 'utf8' },
    );
    assert.equal(rejectedMode.status, 64);
    assert.match(rejectedMode.stderr, /Unsupported promotion mode/);
    assert.equal(
      (helperSource.match(/\bspawnSync\(/g) ?? []).length,
      1,
      'helper may spawn only the reviewed signer operation',
    );
    assert.match(
      helperSource,
      /function runSigner\(executable, args, operation\).*?spawnSync\(executable, args,/s,
      'the sole child process must be the supplied signer executable',
    );
    for (const forbiddenExecutable of ['mvn', 'maven', 'javac', 'javadoc']) {
      assert.doesNotMatch(
        helperSource,
        new RegExp(`(?:spawnSync|execFileSync|execSync|spawn)\\(\\s*['\"]${forbiddenExecutable}['\"]`),
        `helper must not invoke ${forbiddenExecutable}`,
      );
    }

    await expectAsyncFailure(
      () => centralTransport({
        body: undefined,
        headers: {},
        maximumResponseBytes: 1,
        method: 'POST',
        timeoutMilliseconds: 1,
        url: 'https://central.sonatype.com/api/v1/publisher/not-allowed',
      }),
      /outside the promotion allowlist/,
    );

    const syntax = spawnSync(
      process.execPath,
      ['--check', fileURLToPath(new URL('./release-promotion.mjs', import.meta.url))],
      {
      encoding: 'utf8',
      },
    );
    assert.equal(syntax.status, 0, syntax.stderr);

    console.log('Release promotion self-test passed.');
  } finally {
    delete process.env.SOKLET_FAKE_GPG_FINGERPRINT;
    delete process.env.SOKLET_FAKE_GPG_MUTATE;
    rmSync(temporary, { force: true, recursive: true });
  }
}

await run();
