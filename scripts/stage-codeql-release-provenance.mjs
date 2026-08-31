#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  constants as fsConstants,
  closeSync,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  readSync,
  realpathSync,
} from 'node:fs';
import { dirname, isAbsolute, parse, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { verifyReleaseHarnessConfiguration } from './import-release-harness-evidence.mjs';

const MAXIMUM_DESCRIPTOR_BYTES = 4 * 1024 * 1024;
const MAXIMUM_BUNDLE_BYTES = 1024 * 1024 * 1024;
const TARGETS = Object.freeze({
  qlpackSha256: 'codeql-java-queries-qlpack.yml',
  securityExtendedSuiteSelectorSha256: 'codeql-java-security-extended-selectors.yml',
  securityExtendedSuiteSha256: 'codeql-java-security-extended.qls',
});

export class CodeqlReleaseProvenanceError extends Error {}

function fail(message) {
  throw new CodeqlReleaseProvenanceError(message);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function requireNonsymlinkComponents(path, label) {
  const absolute = resolve(path);
  const root = parse(absolute).root;
  let current = root;
  for (const component of absolute.slice(root.length).split(sep).filter(Boolean)) {
    current = resolve(current, component);
    if (existsSync(current) && lstatSync(current).isSymbolicLink())
      fail(`${label} contains a symbolic-link path component: ${current}`);
  }
}

function absolutePath(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  const absolute = resolve(path);
  requireNonsymlinkComponents(absolute, label);
  return absolute;
}

function regularFileStats(path, label, maximumBytes) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} is missing: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink()
      || stats.size <= 0 || stats.size > maximumBytes) {
    fail(`${label} must be a nonempty bounded regular nonsymlink file: ${path}`);
  }
  return stats;
}

function fileSha256(path, label, maximumBytes) {
  regularFileStats(path, label, maximumBytes);
  const hash = createHash('sha256');
  const descriptor = openSync(path, 'r');
  try {
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    for (;;) {
      const count = readSync(descriptor, buffer, 0, buffer.length, null);
      if (count === 0)
        break;
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    closeSync(descriptor);
  }
  return hash.digest('hex');
}

function requireRealDirectory(path, label) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} is missing: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink() || realpathSync(path) !== path)
    fail(`${label} must be a real nonsymlink directory: ${path}`);
}

export function stageCodeqlReleaseProvenance({
  bundlePath,
  codeqlPath,
  expectedBundleSha256,
  expectedDescriptors,
  outputRoot,
}) {
  const absoluteBundlePath = absolutePath(bundlePath, 'CodeQL bundle');
  const absoluteCodeqlPath = absolutePath(codeqlPath, 'CodeQL executable');
  const absoluteOutputRoot = absolutePath(outputRoot, 'CodeQL provenance output root');
  const codeqlStats = regularFileStats(
    absoluteCodeqlPath,
    'CodeQL executable',
    MAXIMUM_BUNDLE_BYTES,
  );
  if ((codeqlStats.mode & 0o111) === 0)
    fail('CodeQL executable is not executable.');
  const absoluteCodeqlRoot = dirname(absoluteCodeqlPath);
  requireRealDirectory(absoluteCodeqlRoot, 'CodeQL installation root');
  if (existsSync(absoluteOutputRoot))
    fail(`CodeQL provenance output root already exists: ${absoluteOutputRoot}`);
  if (typeof expectedBundleSha256 !== 'string'
      || !/^[0-9a-f]{64}$/u.test(expectedBundleSha256)) {
    fail('CodeQL bundle SHA-256 is malformed.');
  }
  const bundleSha256 = fileSha256(
    absoluteBundlePath,
    'CodeQL bundle',
    MAXIMUM_BUNDLE_BYTES,
  );
  if (bundleSha256 !== expectedBundleSha256) {
    fail(`CodeQL bundle SHA-256 mismatch: expected ${expectedBundleSha256}, found ${bundleSha256}.`);
  }

  const expected = new Map(
    Object.keys(TARGETS).map((name) => [name, expectedDescriptors?.[name]]),
  );
  if (expected.size !== 3
      || [...expected.values()].some((digest) =>
        typeof digest !== 'string' || !/^[0-9a-f]{64}$/u.test(digest))) {
    fail('CodeQL query descriptor SHA-256 inventory is malformed.');
  }
  const matches = new Map();
  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name))) {
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink())
        continue;
      if (entry.isDirectory()) {
        visit(path);
        continue;
      }
      if (!entry.isFile()
          || !(entry.name === 'qlpack.yml'
            || entry.name.endsWith('.qls')
            || entry.name.endsWith('.yml')
            || entry.name.endsWith('.yaml'))) {
        continue;
      }
      const stats = lstatSync(path);
      if (stats.size <= 0 || stats.size > MAXIMUM_DESCRIPTOR_BYTES)
        continue;
      const digest = createHash('sha256').update(readFileSync(path)).digest('hex');
      for (const [name, wanted] of expected) {
        if (digest !== wanted)
          continue;
        if (matches.has(name))
          fail(`CodeQL installation contains duplicate approved ${name} bytes.`);
        matches.set(name, path);
      }
    }
  }
  visit(absoluteCodeqlRoot);
  for (const name of expected.keys()) {
    if (!matches.has(name))
      fail(`CodeQL installation is missing approved ${name} bytes.`);
  }

  mkdirSync(absoluteOutputRoot);
  copyFileSync(
    absoluteBundlePath,
    resolve(absoluteOutputRoot, 'codeql-bundle-linux64.tar.gz'),
    fsConstants.COPYFILE_EXCL,
  );
  for (const [name, outputName] of Object.entries(TARGETS)) {
    copyFileSync(
      matches.get(name),
      resolve(absoluteOutputRoot, outputName),
      fsConstants.COPYFILE_EXCL,
    );
  }
  return Object.freeze({
    bundleSha256,
    descriptorPaths: Object.freeze(Object.fromEntries(matches)),
    outputRoot: absoluteOutputRoot,
  });
}

function usage() {
  return 'Usage: node scripts/stage-codeql-release-provenance.mjs '
    + '<absolute-codeql-executable> <absolute-bundle> <absolute-output-root>';
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 5) {
    console.error(usage());
    process.exitCode = 64;
  } else {
    try {
      const contract = verifyReleaseHarnessConfiguration()
        .contracts.get('release-scans');
      const result = stageCodeqlReleaseProvenance({
        bundlePath: process.argv[3],
        codeqlPath: process.argv[2],
        expectedBundleSha256: contract.policy.codeql.bundle.linuxTarGzSha256,
        expectedDescriptors: contract.policy.codeql.javaQueries,
        outputRoot: process.argv[4],
      });
      console.log(`CodeQL release provenance PASS bundleSha256=${result.bundleSha256}`);
    } catch (error) {
      console.error(error instanceof Error ? error.message : String(error));
      process.exitCode = error instanceof CodeqlReleaseProvenanceError ? 1 : 70;
    }
  }
}
