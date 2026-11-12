#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const temporaryRoot = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-validation-self-test-')));
let checks = 0;

try {
  // Deliberately omit Git metadata, release manifests, inventories, and receipts.
  const fixtureRoot = join(temporaryRoot, 'working tree');
  const scriptsRoot = join(fixtureRoot, 'scripts');
  mkdirSync(scriptsRoot, { recursive: true });
  const runner = join(scriptsRoot, 'validate-release-candidate.sh');
  copyFileSync(join(projectRoot, 'scripts/validate-release-candidate.sh'), runner);
  const pomBytes = '<project/>\n';
  const artifactPaths = [
    'pom.xml', 'target/soklet-4.0.0.jar', 'target/soklet-4.0.0-sources.jar',
    'target/soklet-4.0.0-javadoc.jar',
  ];
  writeFileSync(join(fixtureRoot, 'pom.xml'), pomBytes);
  writeFileSync(join(fixtureRoot, 'uncommitted.txt'), 'Current local edits are valid inputs.\n');

  const invocationPath = join(temporaryRoot, 'maven-invocation.txt');
  const fakeMaven = join(temporaryRoot, 'fake maven');
  writeFileSync(fakeMaven, `#!/bin/sh
printf '%s\\n' "$PWD" "$@" > "$SOKLET_TEST_INVOCATION"
printf 'Maven output\\n'
status=\${SOKLET_TEST_MAVEN_STATUS:-0}
[ "$status" -eq 0 ] || exit "$status"
mkdir -p target
for artifact in target/soklet-4.0.0.jar target/soklet-4.0.0-sources.jar target/soklet-4.0.0-javadoc.jar; do
  if [ "$artifact" != "\${SOKLET_TEST_OMIT_ARTIFACT:-}" ]; then
    if [ "$artifact" = "\${SOKLET_TEST_EMPTY_ARTIFACT:-}" ]; then
      : > "$artifact"
    else
      printf 'Built %s%s\\n' "$artifact" "\${SOKLET_TEST_ARTIFACT_SUFFIX:-}" > "$artifact"
    fi
  fi
done
if [ "\${SOKLET_TEST_OMIT_ARTIFACT:-}" = pom.xml ]; then
  rm -f pom.xml
fi
`);
  chmodSync(fakeMaven, 0o755);

  const fakeJavadocHome = join(temporaryRoot, 'fake jdk');
  mkdirSync(join(fakeJavadocHome, 'bin'), { recursive: true });
  writeFileSync(join(fakeJavadocHome, 'bin/javadoc'), '#!/bin/sh\nexit 0\n');
  chmodSync(join(fakeJavadocHome, 'bin/javadoc'), 0o755);

  function run(args = [], environment = {}) {
    rmSync(invocationPath, { force: true });
    rmSync(join(fixtureRoot, 'target'), { recursive: true, force: true });
    writeFileSync(join(fixtureRoot, 'pom.xml'), pomBytes);
    return spawnSync('bash', [runner, ...args], {
      cwd: temporaryRoot,
      env: {
        ...process.env,
        SOKLET_VALIDATION_MAVEN: fakeMaven,
        SOKLET_TEST_INVOCATION: invocationPath,
        SOKLET_TEST_MAVEN_STATUS: '0',
        SOKLET_JAVADOC_HOME: fakeJavadocHome,
        ...environment,
      },
      encoding: 'utf8',
    });
  }

  const expectedInvocation = [
    fixtureRoot, '-B', '-ntp', '-Dgpg.skip=true', '-DskipTests=false',
    '-Dmaven.test.skip=false', 'clean', 'verify', '',
  ].join('\n');

  function assertArtifactHashes(output, suffix = '') {
    const expected = artifactPaths.map((path) => {
      const bytes = path === 'pom.xml' ? pomBytes : `Built ${path}${suffix}\n`;
      const hash = createHash('sha256').update(bytes).digest('hex');
      return `${hash}  ${path}`;
    });
    const actual = output.split('\n').filter((line) => /^[a-f0-9]{64}  /u.test(line));
    assert.deepEqual(actual, expected);
  }

  const success = run();
  assert.equal(success.status, 0, success.stderr);
  assert.equal(readFileSync(invocationPath, 'utf8'), expectedInvocation);
  assert.match(success.stdout, /Maven output/u);
  assert.match(success.stdout, /Validation passed/u);
  assertArtifactHashes(success.stdout);
  assert.equal(existsSync(join(fixtureRoot, '.git')), false);
  assert.equal(existsSync(join(fixtureRoot, 'release')), false);
  assert.equal(readFileSync(join(fixtureRoot, 'uncommitted.txt'), 'utf8'), 'Current local edits are valid inputs.\n');
  checks += 1;

  const changedArtifacts = run([], { SOKLET_TEST_ARTIFACT_SUFFIX: ' changed bytes' });
  assert.equal(changedArtifacts.status, 0, changedArtifacts.stderr);
  assertArtifactHashes(changedArtifacts.stdout, ' changed bytes');
  checks += 1;

  // Maven uses a nonzero status for failing tests, compilation, or packaging.
  for (const status of [1, 7]) {
    const failedBuild = run([], { SOKLET_TEST_MAVEN_STATUS: String(status) });
    assert.equal(failedBuild.status, status, failedBuild.stderr);
    assert.equal(readFileSync(invocationPath, 'utf8'), expectedInvocation);
    assert.doesNotMatch(failedBuild.stdout, /Validation passed/u);
    assert.doesNotMatch(failedBuild.stdout, /Artifact SHA-256|^[a-f0-9]{64}  /mu);
    checks += 1;
  }

  for (const path of artifactPaths) {
    const missingArtifact = run([], { SOKLET_TEST_OMIT_ARTIFACT: path });
    assert.equal(missingArtifact.status, 1);
    assert.match(missingArtifact.stderr, /expected artifact is missing or empty/u);
    assert.ok(missingArtifact.stderr.includes(path));
    assert.doesNotMatch(missingArtifact.stdout, /Validation passed|Artifact SHA-256|^[a-f0-9]{64}  /mu);
    checks += 1;
  }

  const emptyArtifact = run([], { SOKLET_TEST_EMPTY_ARTIFACT: 'target/soklet-4.0.0-javadoc.jar' });
  assert.equal(emptyArtifact.status, 1);
  assert.match(emptyArtifact.stderr, /expected artifact is missing or empty/u);
  assert.doesNotMatch(emptyArtifact.stdout, /Validation passed|Artifact SHA-256|^[a-f0-9]{64}  /mu);
  checks += 1;

  for (const args of [['not-a-candidate-commit'], ['--manifest', 'release.json']]) {
    const invalidArguments = run(args);
    assert.equal(invalidArguments.status, 64);
    assert.match(invalidArguments.stderr, /Usage:/u);
    assert.equal(existsSync(invocationPath), false);
    checks += 1;
  }

  const help = run(['--help']);
  assert.equal(help.status, 0);
  assert.match(help.stdout, /current working tree/u);
  assert.equal(existsSync(invocationPath), false);
  checks += 1;

  const missingMaven = run([], { SOKLET_VALIDATION_MAVEN: join(temporaryRoot, 'missing-maven') });
  assert.equal(missingMaven.status, 1);
  assert.match(missingMaven.stderr, /Maven was not found/u);
  assert.equal(existsSync(invocationPath), false);
  checks += 1;

  // Packaging needs the separate Javadoc JDK; its absence is reported before any test runs.
  const emptyJavadocHome = join(temporaryRoot, 'jdk without javadoc');
  mkdirSync(join(emptyJavadocHome, 'bin'), { recursive: true });
  const nonExecutableJavadocHome = join(temporaryRoot, 'jdk with plain javadoc');
  mkdirSync(join(nonExecutableJavadocHome, 'bin'), { recursive: true });
  writeFileSync(join(nonExecutableJavadocHome, 'bin/javadoc'), 'not executable\n', { mode: 0o644 });
  const directoryJavadocHome = join(temporaryRoot, 'jdk with a javadoc directory');
  mkdirSync(join(directoryJavadocHome, 'bin/javadoc'), { recursive: true, mode: 0o755 });
  for (const javadocHome of [undefined, '', emptyJavadocHome, nonExecutableJavadocHome, directoryJavadocHome]) {
    const missingJavadoc = run([], { SOKLET_JAVADOC_HOME: javadocHome });
    assert.equal(missingJavadoc.status, 1);
    assert.match(missingJavadoc.stderr, /SOKLET_JAVADOC_HOME must name a JDK/u);
    assert.equal(existsSync(invocationPath), false);
    checks += 1;
  }

  console.log(`Release validation runner self-test passed (${checks} checks).`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
