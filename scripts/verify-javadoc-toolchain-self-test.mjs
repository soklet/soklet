#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readToolchainValue } from './read-toolchain-config.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const installer = join(root, 'release/scripts/install-pinned-corretto-linux-x64.sh');
const manifestPath = join(root, 'release/release-validation-manifest.json');
const manifest = JSON.parse(readFileSync(manifestPath));
const pins = manifest.toolchains;
const reader = join(root, 'scripts/read-toolchain-config.mjs');
const temporary = mkdtempSync(join(tmpdir(), 'soklet-javadoc-toolchain-'));
let checks = 0;

try {
  verifyReader();

  function probe(name, mutation = null) {
    const directory = join(temporary, `${name}-${checks++}`);
    const mocks = join(directory, 'mock-bin');
    const template = join(directory, 'template-bin');
    mkdirSync(mocks, { recursive: true });
    mkdirSync(template);
    mkdirSync(join(directory, 'scripts'));
    mkdirSync(join(directory, 'release'));
    const toolchain = structuredClone(pins[name]);
    const archive = join(directory, 'fixture-archive');
    const archiveBytes = Buffer.from('checksum-verified synthetic archive\n');
    writeFileSync(archive, archiveBytes);
    toolchain.archiveSha256 = createHash('sha256').update(archiveBytes).digest('hex');
    if (mutation === 'checksum') toolchain.archiveSha256 = '0'.repeat(64);
    if (mutation === 'release-kind')
      toolchain.runtimeVersion = toolchain.runtimeVersion.replace(/-(FR|LTS)$/, name === 'javadocJava' ? '-LTS' : '-FR');
    writeManifest(directory, name, toolchain);
    copyFileSync(reader, join(directory, 'scripts/read-toolchain-config.mjs'));
    const executable = (path, text) => writeFileSync(path, `#!/bin/sh\nset -eu\n${text}\n`, { mode: 0o755 });
    // Match GNU checksum semantics without depending on the host's BSD/GNU CLI.
    writeFileSync(join(directory, 'checksum.mjs'),
      "import fs from 'node:fs'; import {createHash} from 'node:crypto'; const line = fs.readFileSync(0, 'utf8'); const match = /^([a-f0-9]{64})  (.+)\\n$/.exec(line); if (!match || createHash('sha256').update(fs.readFileSync(match[2])).digest('hex') !== match[1]) process.exit(1);\n");
    executable(join(mocks, 'sha256sum'),
      '[ "$*" = "--check --strict" ]\nexec node "$TEST_CHECKSUM_SCRIPT"');
    executable(join(mocks, 'curl'),
      'printf downloaded > "$TEST_DOWNLOAD_MARKER"\nfor argument do destination=$argument; done\ncp "$TEST_ARCHIVE" "$destination"');
    executable(join(mocks, 'tar'),
      'printf extracted > "$TEST_EXTRACT_MARKER"\nmkdir -p "$TEST_EXTRACT/bin"\ncp "$TEST_TEMPLATE"/* "$TEST_EXTRACT/bin/"');
    executable(join(template, 'java'),
      'printf "    java.version = %s\\n    java.runtime.version = %s\\n    java.vendor = Amazon.com Inc.\\n    java.vendor.version = %s\\n" "$TEST_VERSION" "$TEST_RUNTIME" "$TEST_VENDOR" >&2');
    executable(join(template, 'javac'), 'printf "javac %s\\n" "$TEST_VERSION"');
    executable(join(template, 'javadoc'), 'printf "javadoc %s\\n" "$TEST_JAVADOC_VERSION"');
    const githubPath = join(directory, 'github-path');
    const githubEnvironment = join(directory, 'github-env');
    const originalPath = '/unchanged/compiler/bin\n';
    const originalEnvironment = 'JAVA_HOME=/unchanged/compiler\n';
    writeFileSync(githubPath, originalPath);
    writeFileSync(githubEnvironment, originalEnvironment);
    const evidence = join(directory, 'receipt.txt');
    const distributionVersion = toolchain.vendorVersion.slice('Corretto-'.length);
    const javaHome = join(directory, `soklet-release-${name}-${distributionVersion}`, `amazon-corretto-${distributionVersion}-linux-x64`);
    const downloaded = join(directory, 'downloaded');
    const extracted = join(directory, 'extracted');
    const result = spawnSync('bash', [installer, name, directory, githubPath, githubEnvironment, evidence], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${mocks}:${process.env.PATH}`,
        JAVA_HOME: '/unchanged/compiler',
        TEST_ARCHIVE: archive,
        TEST_CHECKSUM_SCRIPT: join(directory, 'checksum.mjs'),
        TEST_DOWNLOAD_MARKER: downloaded,
        TEST_EXTRACT_MARKER: extracted,
        TEST_TEMPLATE: template,
        TEST_EXTRACT: javaHome,
        TEST_VERSION: pins[name].version,
        TEST_RUNTIME: pins[name].runtimeVersion,
        TEST_VENDOR: pins[name].vendorVersion,
        TEST_JAVADOC_VERSION: mutation === 'executable' ? '26.0.1' : pins[name].version,
      },
    });
    if (mutation !== null) {
      assert.notEqual(result.status, 0, `${name}/${mutation} must fail closed`);
      assert.equal(existsSync(evidence), false);
      assert.equal(readFileSync(githubPath, 'utf8'), originalPath);
      assert.equal(readFileSync(githubEnvironment, 'utf8'), originalEnvironment);
      if (mutation === 'checksum' || mutation === 'release-kind') assert.equal(existsSync(extracted), false);
      if (mutation === 'release-kind') assert.equal(existsSync(downloaded), false);
      return;
    }
    assert.equal(result.status, 0, result.stderr);
    assert.equal(readFileSync(githubPath, 'utf8'), name === 'java' ? `${originalPath}${javaHome}/bin\n` : originalPath);
    const variable = { java: 'JAVA_HOME', coreJdk21: 'SOKLET_RELEASE_CORE_JDK_21_HOME', toystoreJava: 'SOKLET_RELEASE_TOYSTORE_JAVA_HOME', javadocJava: 'SOKLET_JAVADOC_HOME' }[name];
    assert.equal(readFileSync(githubEnvironment, 'utf8'), `${originalEnvironment}${variable}=${javaHome}\n`);
    assert.match(readFileSync(evidence, 'utf8'), new RegExp(`archiveSha256=${toolchain.archiveSha256}\\n$`));
  }

  for (const name of ['java', 'coreJdk21', 'toystoreJava', 'javadocJava']) {
    probe(name);
    probe(name, 'release-kind');
  }
  probe('javadocJava', 'checksum');
  probe('javadocJava', 'executable');
  for (const name of ['maven', 'go']) {
    probeBuildTool(name);
    probeBuildTool(name, 'checksum');
    probeBuildTool(name, 'executable');
  }
  console.log(`Toolchain installer self-test passed (${checks} isolated flows; real configuration reader).`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}

// Keep the actual production manifest, deliberately invalidating the retired
// approval fields. A tool download must not inspect those unrelated fields.
function writeManifest(directory, name, toolchain) {
  const fixture = structuredClone(manifest);
  fixture.toolchains[name] = toolchain;
  fixture.promotion = {
    helper: { path: 'missing-promotion-helper.mjs', sha256: 'retired-source-pin' },
    wrapper: { path: 'missing-wrapper.sh', sha256: 'retired-source-pin' },
  };
  fixture.gates = [{ id: 'historical-only', status: 'NOT_CURRENT_BUILD_INPUT' }];
  writeFileSync(join(directory, 'release/release-validation-manifest.json'), JSON.stringify(fixture));
}

function verifyReader() {
  // This real manifest includes the stale promotion fingerprint that broke CI.
  for (const [name, fields] of Object.entries(pins)) {
    for (const [field, value] of Object.entries(fields))
      assert.equal(readToolchainValue(manifestPath, `toolchains.${name}.${field}`), value);
  }
  const result = spawnSync(process.execPath,
    [reader, manifestPath, 'toolchains.javadocJava.version'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, `${pins.javadocJava.version}\n`);
  for (const path of ['promotion.helper.sha256', 'toolchains.maven', 'toolchains.unknown.version', 'toolchains.maven.toString'])
    assert.throws(() => readToolchainValue(manifestPath, path));
  const invalid = join(temporary, 'invalid-toolchains.json');
  for (const version of ['', 'bad\nvalue', 21, null, {}]) {
    writeFileSync(invalid, JSON.stringify({ toolchains: { java: { version } } }));
    assert.throws(() => readToolchainValue(invalid, 'toolchains.java.version'), /nonempty single-line string/);
  }
}

function probeBuildTool(name, mutation = null) {
  const directory = join(temporary, `${name}-${checks++}`);
  const mocks = join(directory, 'mock-bin');
  mkdirSync(mocks, { recursive: true });
  mkdirSync(join(directory, 'scripts'));
  mkdirSync(join(directory, 'release'));
  const toolchain = structuredClone(pins[name]);
  const algorithm = name === 'maven' ? 'sha512' : 'sha256';
  const hashField = name === 'maven' ? 'archiveSha512' : 'archiveSha256';
  const archive = join(directory, 'fixture-archive');
  const bytes = Buffer.from('checksum-verified synthetic build tool archive\n');
  writeFileSync(archive, bytes);
  toolchain[hashField] = mutation === 'checksum'
    ? '0'.repeat(name === 'maven' ? 128 : 64)
    : createHash(algorithm).update(bytes).digest('hex');
  writeManifest(directory, name, toolchain);
  copyFileSync(reader, join(directory, 'scripts/read-toolchain-config.mjs'));
  const extracted = join(directory, 'extracted');
  const downloaded = join(directory, 'downloaded');
  const bin = name === 'maven'
    ? join(directory, `soklet-release-maven-${toolchain.version}`, `apache-maven-${toolchain.version}`, 'bin')
    : join(directory, `soklet-release-go-${toolchain.version}`, 'go/bin');
  const template = join(directory, 'template-bin');
  mkdirSync(template);
  const executable = (path, source) => writeFileSync(path, `#!/bin/sh\nset -eu\n${source}\n`, { mode: 0o755 });
  writeFileSync(join(directory, 'checksum.mjs'),
    "import fs from 'node:fs'; import {createHash} from 'node:crypto'; const line=fs.readFileSync(0,'utf8'); const match=/^([a-f0-9]+)  (.+)\\n$/.exec(line); if(!match || createHash(process.env.TEST_ALGORITHM).update(fs.readFileSync(match[2])).digest('hex')!==match[1]) process.exit(1);\n");
  executable(join(mocks, `${algorithm}sum`), '[ "$*" = "--check --strict" ]\nexec node "$TEST_CHECKSUM_SCRIPT"');
  executable(join(mocks, 'curl'), 'printf downloaded > "$TEST_DOWNLOAD_MARKER"\nfor argument do destination=$argument; done\ncp "$TEST_ARCHIVE" "$destination"');
  executable(join(mocks, 'tar'), 'printf extracted > "$TEST_EXTRACT_MARKER"\nmkdir -p "$TEST_EXTRACT/bin"\ncp "$TEST_TEMPLATE"/* "$TEST_EXTRACT/bin/"');
  const command = name === 'maven' ? 'mvn' : 'go';
  const reportedVersion = mutation === 'executable' ? '0.0.0' : toolchain.version;
  executable(join(template, command), name === 'maven'
    ? `printf 'Apache Maven ${reportedVersion}\\n'`
    : `printf 'go version go${reportedVersion} linux/amd64\\n'`);
  const githubPath = join(directory, 'github-path');
  const evidence = join(directory, 'distribution.txt');
  writeFileSync(githubPath, '/unchanged/bin\n');
  const result = spawnSync('bash', [join(root, `release/scripts/install-pinned-${name}-linux-x64.sh`), directory, githubPath, evidence], {
    cwd: directory,
    encoding: 'utf8',
    env: { ...process.env, PATH: `${mocks}:${process.env.PATH}`,
      TEST_ARCHIVE: archive, TEST_ALGORITHM: algorithm,
      TEST_CHECKSUM_SCRIPT: join(directory, 'checksum.mjs'),
      TEST_DOWNLOAD_MARKER: downloaded, TEST_EXTRACT_MARKER: extracted,
      TEST_EXTRACT: dirname(bin), TEST_TEMPLATE: template },
  });
  assert.equal(result.error, undefined);
  assert.equal(existsSync(downloaded), true, result.stderr);
  if (mutation !== null) {
    assert.notEqual(result.status, 0, `${name}/${mutation} must reject invalid build inputs`);
    assert.equal(existsSync(evidence), false);
    assert.equal(readFileSync(githubPath, 'utf8'), '/unchanged/bin\n');
    if (mutation === 'checksum') assert.equal(existsSync(extracted), false);
  } else {
    assert.equal(result.status, 0, result.stderr);
    assert.equal(readFileSync(githubPath, 'utf8'), `/unchanged/bin\n${bin}\n`);
    assert.match(readFileSync(evidence, 'utf8'), new RegExp(`${hashField}=${toolchain[hashField]}\\n$`));
  }
}
