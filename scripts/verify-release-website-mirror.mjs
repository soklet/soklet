#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const WEBSITE_REPOSITORY = 'https://github.com/revetware/soklet.com.git';
const MIRROR_NAME = 'soklet-website-mirror.git';
const COMMIT_PATTERN = /^[0-9a-f]{40}$/u;

function fail(message) {
  throw new Error(`Private website mirror verification failed: ${message}`);
}

function git(mirror, ...arguments_) {
  try {
    return execFileSync('git', ['-C', mirror, ...arguments_], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  } catch {
    fail('the mirror is not a valid, readable Git repository');
  }
}

export function verifyReleaseWebsiteMirror(manifestPath, mirrorPath,
    runnerTemp = process.env.RUNNER_TEMP) {
  if (typeof runnerTemp !== 'string' || !isAbsolute(runnerTemp))
    fail('RUNNER_TEMP must be an absolute directory');
  if (typeof mirrorPath !== 'string' || !isAbsolute(mirrorPath))
    fail('the mirror path must be absolute');

  let runnerRoot;
  let mirror;
  try {
    runnerRoot = realpathSync(runnerTemp);
    if (runnerRoot !== resolve(runnerTemp))
      fail('RUNNER_TEMP resolves through a symlink');
    const expectedPath = join(runnerRoot, MIRROR_NAME);
    if (resolve(mirrorPath) !== expectedPath)
      fail('the mirror path is outside the dedicated runner temporary location');
    if (!lstatSync(expectedPath).isDirectory())
      fail('the mirror is missing, a symlink, or not a directory');
    mirror = realpathSync(expectedPath);
    if (mirror !== expectedPath)
      fail('the mirror resolves through a symlink');
  } catch (error) {
    if (error.message.startsWith('Private website mirror verification failed:'))
      throw error;
    fail('the mirror or RUNNER_TEMP is missing');
  }

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch {
    fail('the candidate manifest cannot be read');
  }
  if (!Array.isArray(manifest.gates))
    fail('the candidate manifest has no gate array');
  const website = manifest.gates.find((gate) => gate.id === 'soklet-website');
  if (website?.access !== 'PRIVATE_READ_ONLY'
      || website.repository !== WEBSITE_REPOSITORY
      || !COMMIT_PATTERN.test(website.commit)) {
    fail('the candidate manifest lacks the canonical private website pin');
  }

  if (git(mirror, 'rev-parse', '--is-bare-repository') !== 'true'
      || git(mirror, 'rev-parse', '--is-inside-work-tree') !== 'false') {
    fail('the mirror must be a bare repository');
  }
  if (git(mirror, 'config', '--local', '--get', 'remote.origin.url')
      !== WEBSITE_REPOSITORY) {
    fail('the mirror origin differs from the candidate manifest');
  }
  if (git(mirror, 'rev-parse', '--verify', 'refs/heads/candidate^{commit}')
      !== website.commit) {
    fail('the mirror commit differs from the candidate manifest');
  }
  const refs = git(mirror, 'for-each-ref', '--format=%(refname)', 'refs/heads');
  if (refs !== 'refs/heads/candidate')
    fail('the mirror contains an unexpected branch');

  let localConfig;
  try {
    const configPath = join(mirror, 'config');
    if (!lstatSync(configPath).isFile()
        || realpathSync(configPath) !== configPath) {
      fail('the mirror configuration must be a regular file');
    }
    localConfig = readFileSync(configPath, 'utf8');
  } catch {
    fail('the mirror has no regular local configuration');
  }
  if (/\b(?:extraheader|credential|insteadof|authorization|password|token)\b/iu
    .test(localConfig)) {
    fail('the mirror contains credential configuration');
  }
  return Object.freeze({ commit: website.commit, mirror, repository: WEBSITE_REPOSITORY });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 4) {
    process.stderr.write('Usage: node scripts/verify-release-website-mirror.mjs <candidate-manifest> <mirror>\n');
    process.exitCode = 64;
  } else {
    try {
      verifyReleaseWebsiteMirror(process.argv[2], process.argv[3]);
      process.stdout.write('Verified exact private website mirror.\n');
    } catch (error) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 1;
    }
  }
}
