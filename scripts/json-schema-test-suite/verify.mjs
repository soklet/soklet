#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.argv.length !== 2) {
  console.error('Usage: node scripts/json-schema-test-suite/verify.mjs');
  process.exit(64);
}

const expectedRepository = 'https://github.com/json-schema-org/JSON-Schema-Test-Suite';
const expectedCommit = '0c7b65dc16dd8eaa7bd83e21099c76610c3b246a';
const expectedArchiveSha256 = '405fa34d133c5a5dd3280399e0dafa379bcbf5adb17d180bd7b1b1aaa5afaa1b';
const expectedManifestSha256 = '70be2fa92b362ee738144c4d581bd6cf45b9f47ef4276a942a49eacf2bbbfa88';
const expectedLicenseSha256 = '837402bd25fad9b704265801ca3f92566a98157c1f9a7acd6f446299ba1c305a';
const expectedImportedFileCount = 104;
const expectedImportedRoots = [
  'LICENSE.upstream',
  'remotes/draft2019-09/ignore-prefixItems.json',
  'remotes/draft2020-12',
  'tests/draft2020-12',
];

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const corpusRoot = resolve(
  projectRoot,
  'src/test/resources/com/soklet/internal/mcp/schema/json-schema-test-suite',
);
const pinPath = resolve(corpusRoot, 'upstream-pin.json');
const manifestPath = resolve(corpusRoot, 'manifest.sha256');

verifyRootShape();

const pinBytes = readFileSync(pinPath);
const pinText = pinBytes.toString('utf8');
if (pinText.includes('\r') || !pinText.endsWith('\n')) {
  throw new Error('JSON Schema suite pin must use LF and end with a newline');
}
const pin = JSON.parse(pinText);
const expectedPinKeys = [
  'archiveSha256',
  'commit',
  'importFormat',
  'importedFileCount',
  'importedRoots',
  'license',
  'licenseSha256',
  'manifestSha256',
  'repository',
];
const actualPinKeys = Object.keys(pin).sort(bytewiseCompare);
if (JSON.stringify(actualPinKeys) !== JSON.stringify(expectedPinKeys)) {
  throw new Error('JSON Schema suite pin contains missing or unexpected fields');
}
if (pin.importFormat !== 1 || pin.repository !== expectedRepository ||
    pin.commit !== expectedCommit || pin.archiveSha256 !== expectedArchiveSha256 ||
    pin.manifestSha256 !== expectedManifestSha256 ||
    pin.licenseSha256 !== expectedLicenseSha256 ||
    pin.importedFileCount !== expectedImportedFileCount ||
    pin.license !== 'MIT' ||
    JSON.stringify(pin.importedRoots) !== JSON.stringify(expectedImportedRoots)) {
  throw new Error('JSON Schema suite pin does not match the reviewed upstream selection');
}

const manifestBytes = readFileSync(manifestPath);
const manifest = manifestBytes.toString('ascii');
if (manifest.includes('\r') || !manifest.endsWith('\n')) {
  throw new Error('JSON Schema suite manifest must use LF and end with a newline');
}
if (sha256(manifestBytes) !== pin.manifestSha256) {
  throw new Error('JSON Schema suite manifest digest does not match upstream-pin.json');
}

const lines = manifest.slice(0, -1).split('\n');
const expectedPaths = new Set();
let previousPath;
for (const [index, line] of lines.entries()) {
  const match = /^([0-9a-f]{64})  ([A-Za-z0-9._/-]+)$/.exec(line);
  if (match === null) {
    throw new Error(`Malformed JSON Schema suite manifest row ${index + 1}`);
  }
  const [, expectedSha256, relativePath] = match;
  if (relativePath.startsWith('/') || relativePath.includes('/../') ||
      relativePath.startsWith('../') || relativePath === '..' ||
      relativePath.endsWith('/..') || relativePath.includes('//')) {
    throw new Error(`Unsafe JSON Schema suite manifest path: ${relativePath}`);
  }
  if (previousPath !== undefined && bytewiseCompare(previousPath, relativePath) >= 0) {
    throw new Error(`JSON Schema suite manifest is not bytewise path-sorted at ${relativePath}`);
  }
  previousPath = relativePath;
  if (expectedPaths.has(relativePath)) {
    throw new Error(`Duplicate JSON Schema suite manifest path: ${relativePath}`);
  }
  expectedPaths.add(relativePath);

  const absolutePath = resolve(corpusRoot, relativePath);
  if (!absolutePath.startsWith(`${corpusRoot}${sep}`)) {
    throw new Error(`JSON Schema suite manifest path escapes its root: ${relativePath}`);
  }
  const stats = lstatSync(absolutePath);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`JSON Schema suite entry is not a regular file: ${relativePath}`);
  }
  const bytes = readFileSync(absolutePath);
  if (sha256(bytes) !== expectedSha256) {
    throw new Error(`JSON Schema suite checksum mismatch: ${relativePath}`);
  }
  if (relativePath.endsWith('.json')) {
    JSON.parse(bytes.toString('utf8'));
  }
}

const actualPaths = collectRegularFiles(corpusRoot)
  .filter((path) => path !== 'manifest.sha256' && path !== 'upstream-pin.json');
if (actualPaths.length !== expectedPaths.size ||
    actualPaths.some((path) => !expectedPaths.has(path))) {
  throw new Error('JSON Schema suite files do not exactly match manifest.sha256');
}
if (pin.importedFileCount !== expectedPaths.size) {
  throw new Error(
    `JSON Schema suite pin expected ${pin.importedFileCount} files, found ${expectedPaths.size}`,
  );
}
if (sha256(readFileSync(resolve(corpusRoot, 'LICENSE.upstream'))) !== pin.licenseSha256) {
  throw new Error('JSON Schema suite license digest does not match upstream-pin.json');
}

console.log(
  `Verified ${expectedPaths.size} pinned JSON Schema Test Suite files at ${expectedCommit}.`,
);

function verifyRootShape() {
  const rootStats = lstatSync(corpusRoot);
  if (!rootStats.isDirectory() || rootStats.isSymbolicLink()) {
    throw new Error('JSON Schema suite root must be a real directory');
  }
  const entries = bytewiseEntries(corpusRoot);
  const expectedNames = [
    'LICENSE.upstream',
    'manifest.sha256',
    'remotes',
    'tests',
    'upstream-pin.json',
  ];
  if (entries.length !== expectedNames.length ||
      entries.some((entry, index) => entry.name !== expectedNames[index])) {
    throw new Error(`JSON Schema suite root must contain exactly: ${expectedNames.join(', ')}`);
  }
  for (const entry of entries) {
    const directory = entry.name === 'remotes' || entry.name === 'tests';
    if (entry.isSymbolicLink() || (directory ? !entry.isDirectory() : !entry.isFile())) {
      throw new Error(`Unexpected JSON Schema suite root entry type: ${entry.name}`);
    }
  }
}

function collectRegularFiles(root) {
  const paths = [];
  visit(root);
  paths.sort(bytewiseCompare);
  return paths;

  function visit(directory) {
    for (const entry of bytewiseEntries(directory)) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) {
        throw new Error(`JSON Schema suite must not contain symbolic links: ${absolutePath}`);
      }
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else if (entry.isFile()) {
        paths.push(relative(root, absolutePath).split(sep).join('/'));
      } else {
        throw new Error(`Unexpected JSON Schema suite entry: ${absolutePath}`);
      }
    }
  }
}

function bytewiseEntries(directory) {
  return readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => bytewiseCompare(left.name, right.name));
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left), Buffer.from(right));
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
