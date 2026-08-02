#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.argv.length !== 2) {
  console.error('Usage: node scripts/verify-json-corpus.mjs');
  process.exit(64);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '..');
const corpusRoot = resolve(
  projectRoot,
  'fuzz/src/test/resources/com/soklet/json-corpus',
);
const manifestPath = resolve(corpusRoot, 'manifest.sha256');

const rootEntries = readdirSync(corpusRoot, { withFileTypes: true })
  .sort((left, right) => Buffer.compare(Buffer.from(left.name), Buffer.from(right.name)));
const expectedRootEntries = ['manifest.sha256', 'parse', 'round-trip'];
if (rootEntries.length !== expectedRootEntries.length ||
    rootEntries.some((entry, index) => entry.name !== expectedRootEntries[index])) {
  throw new Error(`JSON corpus root must contain exactly: ${expectedRootEntries.join(', ')}`);
}

for (const entry of rootEntries) {
  const expectedType = entry.name === 'manifest.sha256' ? 'file' : 'directory';
  const hasExpectedType = expectedType === 'file' ? entry.isFile() : entry.isDirectory();
  if (!hasExpectedType || entry.isSymbolicLink()) {
    throw new Error(`JSON corpus root entry must be a regular ${expectedType}: ${entry.name}`);
  }
}

const manifest = readFileSync(manifestPath, 'utf8');

if (manifest.includes('\r') || !manifest.endsWith('\n')) {
  throw new Error('JSON corpus manifest must use LF and end with a newline');
}

const lines = manifest.slice(0, -1).split('\n');
if (lines.length !== 25) {
  throw new Error(`Expected 25 JSON corpus entries, found ${lines.length}`);
}

const expectedPaths = new Set();
let previousPath;
for (const [index, line] of lines.entries()) {
  const match = /^([0-9a-f]{64})  ((?:parse|round-trip)\/[A-Za-z0-9._-]+)$/.exec(line);
  if (match === null) {
    throw new Error(`Malformed JSON corpus manifest row ${index + 1}`);
  }

  const [, expectedSha256, relativePath] = match;
  if (previousPath !== undefined &&
      Buffer.compare(Buffer.from(previousPath), Buffer.from(relativePath)) >= 0) {
    throw new Error(`JSON corpus manifest is not bytewise path-sorted at ${relativePath}`);
  }
  previousPath = relativePath;
  expectedPaths.add(relativePath);

  const absolutePath = resolve(corpusRoot, relativePath);
  const stats = lstatSync(absolutePath);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`JSON corpus entry is not a regular file: ${relativePath}`);
  }
  const actualSha256 = createHash('sha256').update(readFileSync(absolutePath)).digest('hex');
  if (actualSha256 !== expectedSha256) {
    throw new Error(`JSON corpus checksum mismatch: ${relativePath}`);
  }
}

const actualPaths = [];
for (const directoryName of ['parse', 'round-trip']) {
  const directory = resolve(corpusRoot, directoryName);
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new Error(`Unexpected JSON corpus entry: ${directoryName}/${entry.name}`);
    }
    actualPaths.push(relative(corpusRoot, resolve(directory, entry.name)));
  }
}

actualPaths.sort((left, right) => Buffer.compare(Buffer.from(left), Buffer.from(right)));
if (actualPaths.length !== expectedPaths.size ||
    actualPaths.some((path) => !expectedPaths.has(path))) {
  throw new Error('JSON corpus files do not exactly match manifest.sha256');
}

const parseCount = actualPaths.filter((path) => path.startsWith('parse/')).length;
const roundTripCount = actualPaths.filter((path) => path.startsWith('round-trip/')).length;
if (parseCount !== 20 || roundTripCount !== 5) {
  throw new Error(`Expected 20 parse and 5 round-trip fixtures, found ${parseCount} and ${roundTripCount}`);
}

console.log('Verified 25 preserved JSON corpus fixtures (20 parse, 5 round-trip).');
