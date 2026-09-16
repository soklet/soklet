#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const installer = join(root, 'release/scripts/install-pinned-gradle-linux-x64.sh');
const installerText = readFileSync(installer, 'utf8');
const gradleVersion = '9.1.0';
const archiveSha256 = 'a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806';

export function verifyConsumerCiContract(workflow, installationScript, consumerReadme) {
  const start = workflow.indexOf('\n  packaged-consumer:\n');
  const end = workflow.indexOf('\n  api-diff:\n', start);
  assert.ok(start >= 0 && end > start, 'Packaged consumer CI job is missing');
  const job = workflow.slice(start, end);
  for (const required of [
    "if: github.event_name == 'push' || github.event_name == 'pull_request'",
    'runs-on: ubuntu-24.04', 'timeout-minutes: 30', 'fail-fast: false',
    'mode: [maven, gradle, javac]',
    "{ jdk: '17', pin: java, environment: JAVA_HOME }",
    "{ jdk: '21', pin: coreJdk21, environment: SOKLET_RELEASE_CORE_JDK_21_HOME }",
    "{ jdk: '25', pin: toystoreJava, environment: SOKLET_RELEASE_TOYSTORE_JAVA_HOME }",
    'install-pinned-node-linux-x64.sh', 'install-pinned-corretto-linux-x64.sh',
    'install-pinned-maven-linux-x64.sh', 'install-pinned-gradle-linux-x64.sh',
    'javadocJava "${RUNNER_TEMP}"',
    '-Dgpg.skip=true -DskipTests clean package',
    'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    'node verification/consumer-build/self-test.mjs',
    'node verification/consumer-build/verify.mjs "${arguments[@]}"',
    'sha256sum --check --strict "${RUNNER_TEMP}/consumer-inputs.sha256"',
    'These PR/push results are not immutable-candidate release receipts.',
  ]) assert.ok(job.includes(required), `Consumer CI contract omitted ${required}`);
  assert.ok(job.indexOf('java "${RUNNER_TEMP}"') < job.indexOf('-DskipTests clean package'),
    'Canonical JDK 17 must be installed before packaging');
  assert.ok(job.indexOf('javadocJava "${RUNNER_TEMP}"') < job.indexOf('-DskipTests clean package'),
    'Separate checksum-pinned Javadoc JDK must be installed before packaging');
  assert.ok(job.indexOf('consumer-prewarm/pom.xml') < job.indexOf('node verification/consumer-build/verify.mjs'),
    'Maven plugin preparation must precede the independent consumer run');
  assert.ok(!job.includes('actions/setup-java@'), 'Consumer CI must use checksum-pinned Java');
  assert.ok(installationScript.includes(`gradle_version=${gradleVersion}`));
  assert.ok(installationScript.includes(`archive_sha256=${archiveSha256}`));
  assert.ok(installationScript.includes('https://services.gradle.org/distributions/$archive'));
  const checksum = installationScript.indexOf('| sha256sum --check --strict');
  const extraction = installationScript.indexOf('unzip -q "$archive_path"');
  const execute = installationScript.indexOf('"$gradle_bin/gradle" --offline --no-daemon --version');
  assert.ok(checksum > 0 && extraction > checksum && execute > extraction,
    'Checksum verification must precede extraction and execution');
  assert.ok(installationScript.includes('[[ "$actual_version" == "$gradle_version" ]]'),
    'Extracted executable version must be checked');
  const documentedDistributions = [...consumerReadme.matchAll(
    /\bGradle (\d+\.\d+\.\d+) supports all three runtimes;\s*its official binary distribution SHA-256 is\s*`([a-f0-9]{64})`\./g,
  )];
  assert.equal(documentedDistributions.length, 1,
    'Consumer README must declare exactly one Gradle distribution version/checksum pair');
  assert.equal(documentedDistributions[0][1], gradleVersion,
    'Consumer README Gradle version differs from the reviewed installer pin');
  assert.equal(documentedDistributions[0][2], archiveSha256,
    'Consumer README Gradle checksum differs from the reviewed installer pin');
}

function installerFlowSelfTest() {
  const scratch = mkdtempSync(join(tmpdir(), 'soklet-gradle-installer-'));
  try {
    const bin = join(scratch, 'bin');
    mkdirSync(bin);
    const executable = (path, content) => writeFileSync(path, content, { mode: 0o755 });
    executable(join(bin, 'curl'), `#!/usr/bin/env bash
set -eu
[[ "$*" == *"https://services.gradle.org/distributions/gradle-9.1.0-bin.zip"* ]]
[[ "$*" == *"--proto =https --tlsv1.2 --fail --location"* ]]
[[ "$SOKLET_TEST_FAILURE" != download ]] || exit 22
while [[ $# -gt 0 ]]; do
  if [[ "$1" == --output ]]; then shift; printf 'mock verified archive' > "$1"; exit 0; fi
  shift
done
exit 1
`);
    executable(join(bin, 'sha256sum'), `#!/usr/bin/env bash
set -eu
[[ "$*" == '--check --strict' ]]
IFS= read -r checksum
[[ "$checksum" == '${archiveSha256}  '* ]]
[[ "$SOKLET_TEST_FAILURE" != checksum ]]
`);
    executable(join(scratch, 'fake-gradle'), `#!/usr/bin/env bash
set -eu
[[ "$*" == '--offline --no-daemon --version' ]]
if [[ "$SOKLET_TEST_FAILURE" == version ]]; then printf 'Gradle 9.2.0\n'; else printf 'Gradle 9.1.0\n'; fi
`);
    executable(join(bin, 'unzip'), `#!/usr/bin/env bash
set -eu
while [[ $# -gt 0 ]]; do
  if [[ "$1" == -d ]]; then shift; destination=$1; fi
  shift
done
touch "$SOKLET_TEST_ROOT/extracted"
mkdir -p "$destination/gradle-9.1.0/bin"
[[ "$SOKLET_TEST_FAILURE" != executable ]] || exit 0
cp "$SOKLET_TEST_ROOT/fake-gradle" "$destination/gradle-9.1.0/bin/gradle"
`);
    for (const failure of ['', 'download', 'checksum', 'version', 'executable', 'existing']) {
      const caseRoot = join(scratch, failure || 'success');
      mkdirSync(caseRoot);
      const pathFile = join(caseRoot, 'github-path');
      const evidenceFile = join(caseRoot, 'distribution.txt');
      if (failure === 'existing') mkdirSync(join(caseRoot, 'soklet-release-gradle-9.1.0'));
      const marker = join(scratch, 'extracted');
      if (existsSync(marker)) rmSync(marker);
      const result = spawnSync('bash', [installer, caseRoot, pathFile, evidenceFile], {
        cwd: root, encoding: 'utf8', timeout: 10_000,
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`,
          SOKLET_TEST_ROOT: scratch, SOKLET_TEST_FAILURE: failure },
      });
      assert.equal(result.error, undefined);
      assert.equal(result.signal, null);
      if (failure === '') {
        assert.equal(result.status, 0, result.stderr);
        assert.equal(readFileSync(pathFile, 'utf8'), `${caseRoot}/soklet-release-gradle-9.1.0/gradle-9.1.0/bin\n`);
        assert.match(readFileSync(evidenceFile, 'utf8'), new RegExp(`archiveSha256=${archiveSha256}\\n$`));
      } else {
        assert.notEqual(result.status, 0, `Installer accepted ${failure} failure`);
        assert.equal(existsSync(pathFile), false, `Failed ${failure} installer modified executable PATH`);
        assert.equal(existsSync(evidenceFile), false, `Failed ${failure} installer emitted success evidence`);
        if (['download', 'checksum', 'existing'].includes(failure))
          assert.equal(existsSync(marker), false, 'Unverified archive reached extraction');
      }
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8');
  const consumerReadme = readFileSync(join(root, 'verification/consumer-build/README.md'), 'utf8');
  verifyConsumerCiContract(workflow, installerText, consumerReadme);
  for (const removed of ['mode: [maven, gradle, javac]', "{ jdk: '25', pin: toystoreJava, environment: SOKLET_RELEASE_TOYSTORE_JAVA_HOME }", 'node verification/consumer-build/verify.mjs "${arguments[@]}"'])
    assert.throws(() => verifyConsumerCiContract(workflow.replace(removed, ''), installerText, consumerReadme));
  assert.throws(() => verifyConsumerCiContract(workflow, installerText.replace(archiveSha256, '0'.repeat(64)), consumerReadme));
  assert.throws(() => verifyConsumerCiContract(workflow, installerText.replace('| sha256sum --check --strict', ''), consumerReadme));
  for (const changedReadme of [
    consumerReadme.replace(`Gradle ${gradleVersion}`, 'Gradle 9.2.0'),
    consumerReadme.replace(archiveSha256, '0'.repeat(64)),
    consumerReadme.replace(`Gradle ${gradleVersion}`, 'Gradle'),
    consumerReadme.replace(archiveSha256, ''),
  ]) assert.throws(() => verifyConsumerCiContract(workflow, installerText, changedReadme));
  installerFlowSelfTest();
  console.log('Consumer CI matrix/Gradle installer self-test passed (nine contract mutations, six installer flows).');
}
