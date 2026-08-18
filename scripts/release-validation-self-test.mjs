#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  copyFileSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import net from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  activeScenarios,
  verifyManifestSet,
} from '../conformance/official/verify.mjs';
import {
  assembleReleaseEvidence,
  recordCandidateArtifacts,
  recordGateEvidence,
  validateReleaseConfiguration,
  verifyReleaseConformanceEvidence,
} from './release-validation-evidence.mjs';
import { verifyMavenDownstreamPom } from './verify-maven-downstream-pom.mjs';
import { createLoopbackPortReservation } from './reserve-loopback-port.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '..');
const trackedManifestPath = resolve(projectRoot, 'release/release-validation-manifest.json');
const releaseWorkflowPath = resolve(projectRoot, '.github/workflows/release-validation.yml');
const releaseValidatorPath = resolve(projectRoot, 'scripts/validate-release-candidate.sh');
const loopbackPortReserverPath = resolve(projectRoot, 'scripts/reserve-loopback-port.mjs');
const promotionHelperPath = resolve(projectRoot, 'scripts/release-promotion.mjs');
const promotionWrapperPath = resolve(projectRoot, 'scripts/promote-release-candidate.sh');
const pinnedJavaInstallerPath = resolve(
  projectRoot,
  'release/scripts/install-pinned-corretto-linux-x64.sh',
);
const fixtureRoot = mkdtempSync(resolve(tmpdir(), 'soklet-release-validation-'));
const candidateCommit = 'a'.repeat(40);

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

try {
  await verifyLoopbackPortReservation(fixturePath('reserved-loopback-port.txt'));

  const tracked = validateReleaseConfiguration(trackedManifestPath);
  assert.equal(tracked.candidate.version, '3.6.0');
  assert.equal(tracked.gates.length, 13);
  assert.equal(tracked.toolchains.java.vendorVersion, 'Corretto-17.0.20.8.1');
  assert.equal(tracked.toolchains.toystoreJava.vendorVersion, 'Corretto-25.0.4.7.1');
  assert.equal(tracked.promotion.helper.path, 'scripts/release-promotion.mjs');
  assert.equal(tracked.promotion.wrapper.path, 'scripts/promote-release-candidate.sh');
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
  assert.match(
    releaseWorkflow,
    /SOKLET_CANDIDATE_COMMIT: \$\{\{ inputs\.candidate_commit \}\}/,
  );
  assert.match(
    releaseWorkflow,
    /run: scripts\/validate-release-candidate\.sh "\$SOKLET_CANDIDATE_COMMIT"/,
  );
  assert.doesNotMatch(
    releaseWorkflow,
    /run:[^\n]*\$\{\{\s*inputs\.candidate_commit\s*\}\}/,
  );
  const toyStoreJavaInstall = releaseWorkflow.indexOf(
    'install-pinned-corretto-linux-x64.sh\n          toystoreJava',
  );
  const candidateJavaInstall = releaseWorkflow.indexOf(
    'install-pinned-corretto-linux-x64.sh\n          java',
  );
  assert.ok(toyStoreJavaInstall >= 0);
  assert.ok(candidateJavaInstall > toyStoreJavaInstall);

  const releaseValidator = readFileSync(releaseValidatorPath, 'utf8');
  const loopbackPortReserver = readFileSync(loopbackPortReserverPath, 'utf8');
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
  assert.match(candidateOnlyBranch[1], /"\$toystore_java_distribution_evidence"/);
  assert.match(candidateOnlyBranch[1], /\n\t\treturn$/);
  for (const gateId of ['toystore-app', 'soklet-otel']) {
    assert.equal(
      releaseValidator.match(new RegExp(`^run_maven_downstream ${gateId}$`, 'gm'))?.length,
      1,
    );
  }
  assert.match(
    releaseValidator,
    /record_gate "\$gate_id" "\$log" "\$candidate_jar"/,
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
  assert.match(releaseValidator, /"\$downstream_pom" "\$default_jar"/);
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
    /record_gate barebones-app "\$port_file" "\$reservation_log" "\$log"/,
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

  const readyManifest = JSON.parse(readFileSync(trackedManifestPath, 'utf8'));
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
  assert.equal(ready.gates.length, 13);

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

  const pomPath = fixturePath('pom.xml');
  const mainJarPath = fixturePath('soklet-3.6.0.jar');
  const sourcesJarPath = fixturePath('soklet-3.6.0-sources.jar');
  const javadocJarPath = fixturePath('soklet-3.6.0-javadoc.jar');
  const artifactDescriptorPath = fixturePath('evidence/candidate-artifacts.json');
  const evidencePayloadPath = fixturePath('evidence/payload.txt');
  const gateDirectory = fixturePath('evidence/gates');
  const finalEvidencePath = fixturePath('evidence/release-validation-evidence.json');
  mkdirSync(dirname(artifactDescriptorPath), { recursive: true });
  mkdirSync(gateDirectory, { recursive: true });
  writeFileSync(pomPath, `<?xml version="1.0" encoding="UTF-8"?>
<project><modelVersion>4.0.0</modelVersion><groupId>com.soklet</groupId><artifactId>soklet</artifactId><version>3.6.0</version><packaging>jar</packaging></project>
`);
  for (const path of [mainJarPath, sourcesJarPath, javadocJarPath])
    writeFileSync(path, Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x01]));
  writeFileSync(evidencePayloadPath, 'fixture evidence\n');

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
    goldenMessagesValidated: 39,
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
        version: '3.6.0',
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
    (value) => { value.goldenMessagesValidated = 38; },
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
    writeFileSync(
      path,
      `dependency setup output\nSOKLET_INTEROP_PASS 2026-07-28 ${client}\nSOKLET_INTEROP_EVIDENCE ${JSON.stringify(receipt)}\n`,
    );
  }

  const typeScriptReadyGate = ready.gates.find(({ id }) => id === 'typescript-interop');
  const wrongCandidateLogPath = fixturePath('evidence/typescript-wrong-candidate.log');
  writeInteropLog(typeScriptReadyGate, wrongCandidateLogPath, {
    candidateSha256: 'd'.repeat(64),
  });
  assert.throws(
    () => recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-wrong-candidate.json'),
      [wrongCandidateLogPath, mainJarPath],
    ),
    /does not match the exact candidate, SDK pin, and fixture contract/,
  );
  const wrongSdkLogPath = fixturePath('evidence/typescript-wrong-sdk.log');
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
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-wrong-sdk.json'),
      [wrongSdkLogPath, mainJarPath],
    ),
    /does not match the exact candidate, SDK pin, and fixture contract/,
  );
  const noncanonicalLogPath = fixturePath('evidence/typescript-noncanonical.log');
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
      typeScriptReadyGate.id,
      fixturePath('evidence/typescript-noncanonical.json'),
      [noncanonicalLogPath, mainJarPath],
    ),
    /receipt must use the exact canonical encoding/,
  );

  for (const gate of ready.gates) {
    let evidencePaths = [evidencePayloadPath];
    if (gate.kind === 'INTEROPERABILITY') {
      const logPath = fixturePath('evidence', `${gate.id}.log`);
      writeInteropLog(gate, logPath);
      evidencePaths = [logPath, mainJarPath];
    }
    recordGateEvidence(
      fixtureManifestPath,
      candidateCommit,
      gate.id,
      resolve(gateDirectory, `${gate.id}.json`),
      evidencePaths,
    );
  }

  Object.assign(process.env, {
    GITHUB_JOB: 'validate',
    GITHUB_REPOSITORY: 'soklet/soklet',
    GITHUB_RUN_ATTEMPT: '1',
    GITHUB_RUN_ID: '1234',
    GITHUB_SERVER_URL: 'https://github.com',
    GITHUB_SHA: candidateCommit,
    SOKLET_EVIDENCE_GIT_VERSION: 'git version fixture',
    SOKLET_EVIDENCE_GO_VERSION: 'go version go1.25.12 linux/amd64',
    SOKLET_EVIDENCE_JAVA_VERSION: '17.0.20',
    SOKLET_EVIDENCE_MAVEN_VERSION: '3.9.16',
    SOKLET_EVIDENCE_NODE_VERSION: '26.5.0',
    SOKLET_EVIDENCE_NPM_VERSION: '11.17.0',
    SOKLET_EVIDENCE_TOYSTORE_JAVA_VERSION: '25.0.4',
  });

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
  assert.equal(evidence.gates.length, 13);
  assert.ok(evidence.gates.every(({ status }) => status === 'PASS'));
  assert.ok(evidence.gates
    .filter(({ gate }) => gate.id.endsWith('-interop'))
    .every(({ interoperability }) =>
      interoperability.candidateSha256 === descriptor.artifacts.mainJar.sha256));
  assert.equal(evidence.toolchains.java, '17.0.20');
  assert.equal(evidence.toolchains.toystoreJava, '25.0.4');

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
