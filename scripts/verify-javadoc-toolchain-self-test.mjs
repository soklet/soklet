#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const installer = join(root, 'release/scripts/install-pinned-corretto-linux-x64.sh');
const pins = JSON.parse(readFileSync(join(root, 'release/release-validation-manifest.json'))).toolchains;
const temporary = mkdtempSync(join(tmpdir(), 'soklet-javadoc-toolchain-'));
let checks = 0;

try {
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
    writeFileSync(join(directory, 'release/release-validation-manifest.json'), JSON.stringify({ toolchains: { [name]: toolchain } }));
    writeFileSync(join(directory, 'scripts/release-validation-evidence.mjs'),
      "import fs from 'node:fs'; const value = JSON.parse(fs.readFileSync(process.argv[3])); console.log(process.argv[4].split('.').reduce((v, k) => v[k], value));\n");
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
  console.log(`Javadoc toolchain installer self-test passed (${checks} isolated flows).`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
