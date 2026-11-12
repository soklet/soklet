#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  linkSync,
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
function digest(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function writeNew(path, bytes, mode) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { flag: 'wx', mode });
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

  return { artifactPaths: paths };
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
    gpgPath: fakeGpg,
    outputDirectory: join(root, outputName),
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

    const corruptedBundle = Buffer.from(firstBundle);
    corruptedBundle[40] ^= 0x01;
    expectFailure(
      () => validatePreparationRecord(first.preparation, corruptedBundle),
      /bundle identity|CRC|metadata|match/,
    );

    // Preparation works without any candidate commit, approval, manifest, or gate receipt.
    assert.equal(first.preparation.candidateCommit, null);
    assert.equal(first.preparation.formatVersion, 2);
    assert.equal(Object.hasOwn(first.preparation, 'releaseValidationEvidence'), false);
    assert.equal(Object.hasOwn(first.preparation, 'reviewedReleaseManifest'), false);
    const attributed = preparePromotion({
      artifactPaths: inputs.artifactPaths,
      candidateCommit: CANDIDATE_COMMIT,
      gpgPath: fakeGpg,
      outputDirectory: join(temporary, 'attributed'),
      signingFingerprint: SIGNING_FINGERPRINT,
    });
    assert.equal(attributed.preparation.candidateCommit, CANDIDATE_COMMIT);
    const observedTools = structuredClone(first.preparation);
    observedTools.promotionTool.helper.sha256 = '0'.repeat(64);
    validatePreparationRecord(observedTools, firstBundle);
    const legacyPreparation = structuredClone(first.preparation);
    legacyPreparation.formatVersion = 1;
    legacyPreparation.candidateCommit = CANDIDATE_COMMIT;
    legacyPreparation.releaseValidationEvidence = {
      bytes: 1, fileName: 'old-evidence.json', sha256: '1'.repeat(64),
    };
    legacyPreparation.reviewedReleaseManifest = {
      bytes: 1, fileName: 'old-manifest.json', sha256: '2'.repeat(64),
    };
    validatePreparationRecord(legacyPreparation, firstBundle);
    const wrongCoordinates = structuredClone(first.preparation);
    wrongCoordinates.coordinates.version = '3.5.1';
    expectFailure(() => validatePreparationRecord(wrongCoordinates, firstBundle), /coordinates/);

    const originalMain = readFileSync(inputs.artifactPaths.mainJar);
    const changedMain = Buffer.concat([originalMain, Buffer.from([0])]);
    writeFileSync(inputs.artifactPaths.mainJar, changedMain);
    const changed = prepare(temporary, inputs, fakeGpg, 'changed-artifact');
    assert.equal(changed.preparation.artifacts.mainJar.sha256, digest(changedMain),
      'Changed artifacts are bound to their current bytes without a release-ledger amendment');
    writeFileSync(inputs.artifactPaths.mainJar, Buffer.from('invalid JAR'));
    expectFailure(() => prepare(temporary, inputs, fakeGpg, 'invalid-jar'), /not a JAR/);
    writeFileSync(inputs.artifactPaths.mainJar, originalMain);
    const originalPom = readFileSync(inputs.artifactPaths.pom);
    writeFileSync(inputs.artifactPaths.pom, originalPom.toString('utf8')
      .replace('<version>4.0.0</version>', '<version>3.5.1</version>'));
    expectFailure(() => prepare(temporary, inputs, fakeGpg, 'wrong-pom-coordinate'), /coordinates/);
    writeFileSync(inputs.artifactPaths.pom, originalPom);

    // Inputs are identified by their exact Maven file names and must be four distinct files.
    const renamedDirectory = join(temporary, 'renamed-artifacts');
    mkdirSync(renamedDirectory);
    const renamedMain = join(renamedDirectory, 'soklet.jar');
    writeFileSync(renamedMain, originalMain);
    expectFailure(
      () => preparePromotion({
        artifactPaths: { ...inputs.artifactPaths, mainJar: renamedMain },
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'renamed-artifact'),
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /file name must be exactly soklet-4\.0\.0\.jar/,
    );
    const aliasDirectory = join(temporary, 'alias-artifacts');
    mkdirSync(aliasDirectory);
    const aliasedSources = join(aliasDirectory, basename(inputs.artifactPaths.sourcesJar));
    linkSync(inputs.artifactPaths.mainJar, aliasedSources);
    expectFailure(
      () => preparePromotion({
        artifactPaths: { ...inputs.artifactPaths, sourcesJar: aliasedSources },
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'aliased-artifact'),
        signingFingerprint: SIGNING_FINGERPRINT,
      }),
      /four distinct files/,
    );

    // The optional commit is unauthenticated metadata, but it must still look like a commit.
    for (const [index, candidateCommit] of ['main', CANDIDATE_COMMIT.slice(0, 12), CANDIDATE_COMMIT.toUpperCase()].entries()) {
      expectFailure(
        () => preparePromotion({
          artifactPaths: inputs.artifactPaths,
          candidateCommit,
          gpgPath: fakeGpg,
          outputDirectory: join(temporary, `malformed-commit-${index}`),
          signingFingerprint: SIGNING_FINGERPRINT,
        }),
        /Optional candidate commit/,
      );
    }

    // Version-1 preparations keep well-formed historical references even though nothing reads them.
    for (const [key, mutate, pattern] of [
      ['releaseValidationEvidence', (reference) => { reference.sha256 = 'not-a-digest'; }, /Legacy releaseValidationEvidence identity/],
      ['reviewedReleaseManifest', (reference) => { reference.fileName = 'nested/old-manifest.json'; }, /Legacy reviewedReleaseManifest identity/],
      ['reviewedReleaseManifest', (reference) => { reference.bytes = 0; }, /legacy reviewedReleaseManifest byte count/],
      ['releaseValidationEvidence', (reference) => { reference.extra = true; }, /legacy releaseValidationEvidence reference/],
    ]) {
      const malformedLegacy = structuredClone(legacyPreparation);
      mutate(malformedLegacy[key]);
      expectFailure(() => validatePreparationRecord(malformedLegacy, firstBundle), pattern);
    }
    const legacyWithoutReference = structuredClone(legacyPreparation);
    delete legacyWithoutReference.reviewedReleaseManifest;
    expectFailure(() => validatePreparationRecord(legacyWithoutReference, firstBundle), /promotion preparation/);

    const linkDirectory = join(temporary, 'link-artifacts');
    mkdirSync(linkDirectory);
    const linkPath = join(linkDirectory, basename(inputs.artifactPaths.mainJar));
    symlinkSync(inputs.artifactPaths.mainJar, linkPath);
    expectFailure(
      () => preparePromotion({
        artifactPaths: { ...inputs.artifactPaths, mainJar: linkPath },
        gpgPath: fakeGpg,
        outputDirectory: join(temporary, 'symlink-artifact'),
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
