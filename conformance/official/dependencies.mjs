#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { sha256, verifyManifestSet, verifyOfficialSuite } from './verify.mjs';

const officialRoot = dirname(fileURLToPath(import.meta.url));
export const dependencyLockPath = resolve(officialRoot, 'dependencies/package-lock.json');

function regularFile(path, label) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink())
    throw new Error(`${label} must be a regular nonsymlink file`);
  return readFileSync(path);
}

function readDependencyLock(pins, options) {
  const bytes = regularFile(options.lockPath ?? dependencyLockPath, 'Dependency lock');
  const lock = JSON.parse(bytes.toString('utf8'));
  const suite = pins.officialConformanceSuite;
  if (lock.lockfileVersion !== 3
      || lock.packages?.['']?.name !== suite.package.name
      || lock.packages?.['']?.version !== suite.package.version
      || lock.packages?.['node_modules/ajv']?.version !== suite.lockedSchemaDependencies.ajv
      || lock.packages?.['node_modules/ajv-formats']?.version
        !== suite.lockedSchemaDependencies['ajv-formats'])
    throw new Error('Dependency refresh changed package or schema-validator identity');
  return { bytes, lock };
}

function requireOriginalLock(bytes, pins, lock) {
  if (sha256(bytes) !== pins.officialConformanceSuite.package.packageLockJsonSha256)
    throw new Error('Original upstream lock checksum differs');
  const original = JSON.parse(bytes.toString('utf8'));
  if (!isDeepStrictEqual(original.packages?.[''], lock.packages['']))
    throw new Error('Dependency refresh changed upstream package declarations');
}

export function prepareDependencies(suiteDirectory, options = {}) {
  const pins = options.pins ?? verifyManifestSet().pins;
  verifyOfficialSuite(suiteDirectory, pins, { requireBuilt: false });
  const { bytes, lock } = readDependencyLock(pins, options);
  const destination = resolve(suiteDirectory, 'package-lock.json');
  requireOriginalLock(regularFile(destination, 'Upstream lock'), pins, lock);
  writeFileSync(destination, bytes);
  return { lockSha256: sha256(bytes) };
}

export function restoreUpstreamLock(suiteDirectory, options = {}) {
  const pins = options.pins ?? verifyManifestSet().pins;
  const { bytes, lock } = readDependencyLock(pins, options);
  const destination = resolve(suiteDirectory, 'package-lock.json');
  if (!regularFile(destination, 'Installed dependency lock').equals(bytes))
    throw new Error('Installed dependency lock changed during the build');
  // Recover the immutable upstream input without creating or modifying commits.
  const original = options.originalLockBytes ?? execFileSync('git', [
    '-c', `safe.directory=${suiteDirectory}`, '-C', suiteDirectory,
    'show', `${pins.officialConformanceSuite.commit}:package-lock.json`,
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  requireOriginalLock(original, pins, lock);
  writeFileSync(destination, original);
  // Installed node_modules retain the refreshed dependencies. Restore only the
  // upstream source input, then verify the complete source and built CLI again.
  verifyOfficialSuite(suiteDirectory, pins);
  return { lockSha256: sha256(bytes) };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [mode, directory, ...extra] = process.argv.slice(2);
    if (!['prepare', 'restore'].includes(mode) || !directory
        || !isAbsolute(directory) || extra.length)
      throw new Error('Usage: node dependencies.mjs <prepare|restore> <absolute-suite-directory>');
    const result = mode === 'prepare' ? prepareDependencies(directory)
      : restoreUpstreamLock(directory);
    console.log(`${mode === 'prepare' ? 'Prepared dependency lock' : 'Restored upstream source; installed dependency lock'} ${result.lockSha256}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
