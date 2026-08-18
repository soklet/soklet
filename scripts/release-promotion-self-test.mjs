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
  canonicalJsonBytes,
  centralTransport,
  preparePromotion,
  readDeterministicZip,
  recordUserManagedStatus,
  recordUserManagedUpload,
  uploadUserManaged,
  validatePreparationRecord,
  verifyPublished,
} from './release-promotion.mjs';

const CANDIDATE_COMMIT = '0123456789abcdef0123456789abcdef01234567';
const SIGNING_FINGERPRINT = 'ABCDEF0123456789ABCDEF0123456789ABCDEF01';
const OTHER_FINGERPRINT = '1111111111111111111111111111111111111111';
const DEPLOYMENT_ID = '12345678-1234-4234-8234-123456789abc';
const SECRET_AUTHORIZATION = 'Bearer dXNlcjpzdXBlci1zZWNyZXQ=';
const GATE_IDS = [
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
];

function digest(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
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

function syntheticGate(id, mainJar) {
  const isInteroperability = id === 'typescript-interop' || id === 'go-interop';
  const artifactChecksum = isInteroperability ? `${id}-checksum` : null;
  const artifactIdentity = `${id}-identity`;
  const commit = isInteroperability ? '3'.repeat(40) : null;
  const interoperability = isInteroperability
    ? {
        candidateSha256: mainJar.sha256,
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
    evidence: [
      { bytes: 1, fileName: `${id}.log`, sha256: '1'.repeat(64), type: 'FILE' },
      ...(isInteroperability ? [mainJar] : []),
    ],
    formatVersion: 1,
    gate: {
      artifactChecksum,
      artifactIdentity,
      commit,
      defaultArtifactIdentity: null,
      defaultArtifactSha256: null,
      id,
      repository: null,
    },
    interoperability,
    status: 'PASS',
  };
}

function writeSyntheticInputs(root) {
  const artifactsDirectory = join(root, 'artifacts');
  mkdirSync(artifactsDirectory, { recursive: true });
  const paths = {
    javadocJar: join(artifactsDirectory, 'soklet-3.6.0-javadoc.jar'),
    mainJar: join(artifactsDirectory, 'soklet-3.6.0.jar'),
    pom: join(artifactsDirectory, 'pom.xml'),
    sourcesJar: join(artifactsDirectory, 'soklet-3.6.0-sources.jar'),
  };
  writeNew(
    paths.pom,
    Buffer.from(
      '<?xml version="1.0" encoding="UTF-8"?>\n'
        + '<project><modelVersion>4.0.0</modelVersion>'
        + '<groupId>com.soklet</groupId><artifactId>soklet</artifactId>'
        + '<version>3.6.0</version><packaging>jar</packaging></project>\n',
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
  const evidence = {
    artifacts,
    candidateCommit: CANDIDATE_COMMIT,
    coordinates: {
      artifactId: 'soklet',
      groupId: 'com.soklet',
      packaging: 'jar',
      version: '3.6.0',
    },
    formatVersion: 1,
    gates: GATE_IDS.map((id) => syntheticGate(id, artifacts.mainJar)),
    releaseConfigurationSha256: '2'.repeat(64),
    toolchains: {
      git: 'git version synthetic',
      go: 'go version synthetic',
      java: 'java version synthetic',
      maven: 'maven version synthetic',
      node: 'node version synthetic',
      npm: 'npm version synthetic',
      toystoreJava: 'toystore java version synthetic',
    },
    workflow: {
      job: 'validate',
      repository: 'soklet/soklet',
      runAttempt: '1',
      runId: '1234',
      serverUrl: 'https://github.com',
      sha: CANDIDATE_COMMIT,
    },
  };
  const promotionHelperBytes = readFileSync(
    fileURLToPath(new URL('./release-promotion.mjs', import.meta.url)),
  );
  const promotionWrapperBytes = readFileSync(
    fileURLToPath(new URL('./promote-release-candidate.sh', import.meta.url)),
  );
  const releaseManifest = {
    candidate: evidence.coordinates,
    formatVersion: 1,
    gates: evidence.gates.map(({ gate }) => ({
      access: 'SYNTHETIC',
      artifactChecksum: gate.artifactChecksum,
      artifactIdentity: gate.artifactIdentity,
      commit: gate.commit,
      defaultArtifactIdentity: gate.defaultArtifactIdentity,
      defaultArtifactSha256: gate.defaultArtifactSha256,
      id: gate.id,
      kind: gate.id === 'typescript-interop' || gate.id === 'go-interop'
        ? 'INTEROPERABILITY'
        : 'SOURCE',
      reason: '',
      repository: gate.repository,
      status: 'READY',
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
    toolchains: { synthetic: 'pinned' },
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
    evidence,
    evidencePath,
    evidenceSha256: digest(evidenceBytes),
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
    assert.ok(entries.every((entry) => entry.path.startsWith('com/soklet/soklet/3.6.0/')));
    assert.equal(entries.filter((entry) => entry.path.endsWith('.asc')).length, 4);
    assert.equal(entries.filter((entry) => /\.asc\.(?:md5|sha1|sha256|sha512)$/.test(entry.path)).length, 0);
    assert.equal(entries.filter((entry) => /\.(?:md5|sha1|sha256|sha512)$/.test(entry.path)).length, 16);
    validatePreparationRecord(first.preparation, firstBundle);

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
    assert.match(uploadRequests[0].body.toString('latin1'), /name="bundle"; filename="soklet-3\.6\.0-central-bundle\.zip"/);
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
      const bytes = entryMap.get(`com/soklet/soklet/3.6.0/${fileName}`);
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
          const bytes = entryMap.get(`com/soklet/soklet/3.6.0/${fileName}`);
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
    for (const forbiddenCommand of ['mvn ', 'javac ', 'javadoc ', 'maven '])
      assert.ok(!helperSource.includes(forbiddenCommand), `helper must not invoke ${forbiddenCommand.trim()}`);

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
