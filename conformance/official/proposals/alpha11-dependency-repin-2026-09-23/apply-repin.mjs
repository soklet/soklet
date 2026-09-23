#!/usr/bin/env node

// Prepare and then restore the exact upstream checkout around a repinned install.
// This proposal is not used by the release runner until its owner review closes.

import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { sha256, verifyManifestSet, verifyOfficialSuite } from '../../verify.mjs';

const proposalRoot = dirname(fileURLToPath(import.meta.url));
const overlayPath = resolve(proposalRoot, 'package-lock.json');
const overlaySha256 = '4bbf44df937f30f99f56dcb359ec5ca67c8200241b279f49f25d4b646e38fa1f';

if (process.argv.length !== 4 || !['prepare', 'restore'].includes(process.argv[2])
    || !isAbsolute(process.argv[3])) {
  console.error('Usage: node apply-repin.mjs <prepare|restore> <absolute-upstream-suite-dir>');
  process.exit(64);
}

const mode = process.argv[2];
const suiteDirectory = resolve(process.argv[3]);
const { pins } = verifyManifestSet();
const suite = pins.officialConformanceSuite;
const lockPath = resolve(suiteDirectory, 'package-lock.json');
const overlayStats = lstatSync(overlayPath);
if (!overlayStats.isFile() || overlayStats.isSymbolicLink())
  throw new Error('Repinned lock must be a regular nonsymlink file');
const overlayBytes = readFileSync(overlayPath);
if (sha256(overlayBytes) !== overlaySha256)
  throw new Error('Repinned lock differs from the reviewed proposal');
const overlay = JSON.parse(overlayBytes.toString('utf8'));
if (overlay.lockfileVersion !== 3
    || overlay.packages?.['']?.name !== suite.package.name
    || overlay.packages?.['']?.version !== suite.package.version
    || overlay.packages?.['node_modules/ajv']?.version !== suite.lockedSchemaDependencies.ajv
    || overlay.packages?.['node_modules/ajv-formats']?.version
      !== suite.lockedSchemaDependencies['ajv-formats'])
  throw new Error('Repinned lock changed the package or schema-validator identity');

function git(...args) {
  return execFileSync('git', ['-C', suiteDirectory, ...args], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

if (git('rev-parse', 'HEAD').trim() !== suite.commit)
  throw new Error('Upstream suite checkout is not at the pinned commit');
const lockStats = lstatSync(lockPath);
if (!lockStats.isFile() || lockStats.isSymbolicLink())
  throw new Error('Upstream suite lock must be a regular nonsymlink file');

if (mode === 'prepare') {
  if (git('status', '--porcelain=v1', '--untracked-files=all') !== '')
    throw new Error('Upstream suite checkout must be clean before repin');
  verifyOfficialSuite(suiteDirectory, pins, { requireBuilt: false });
  writeFileSync(lockPath, overlayBytes);
  if (sha256(readFileSync(lockPath)) !== overlaySha256
      || git('status', '--porcelain=v1', '--untracked-files=no')
        !== ' M package-lock.json\n')
    throw new Error('Repin changed more than the upstream lockfile');
  console.log(`Prepared exact conformance dependency repin ${overlaySha256}`);
} else {
  if (sha256(readFileSync(lockPath)) !== overlaySha256
      || git('status', '--porcelain=v1', '--untracked-files=no')
        !== ' M package-lock.json\n')
    throw new Error('Upstream suite changed outside the exact repinned lock');
  const originalBytes = execFileSync('git',
    ['-C', suiteDirectory, 'show', 'HEAD:package-lock.json'],
    { stdio: ['ignore', 'pipe', 'pipe'] });
  if (sha256(originalBytes) !== suite.package.packageLockJsonSha256)
    throw new Error('Pinned upstream lock could not be recovered exactly');
  writeFileSync(lockPath, originalBytes);
  verifyOfficialSuite(suiteDirectory, pins);
  if (git('status', '--porcelain=v1', '--untracked-files=no') !== '')
    throw new Error('Upstream suite tracked files are not clean after repin');
  console.log(`Restored exact upstream source; built CLI used repin ${overlaySha256}`);
}
