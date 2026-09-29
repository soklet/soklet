#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { verifyReleaseWebsiteMirror } from './verify-release-website-mirror.mjs';

const temporaryRoot = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-website-mirror-')));
const mirror = join(temporaryRoot, 'soklet-website-mirror.git');
const source = join(temporaryRoot, 'source');
const manifestPath = join(temporaryRoot, 'manifest.json');
const canonicalRepository = 'https://github.com/revetware/soklet.com.git';

function git(...arguments_) {
  const result = spawnSync('git', arguments_, { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function writeManifest(commit, access = 'PRIVATE_READ_ONLY',
    repository = canonicalRepository) {
  writeFileSync(manifestPath, `${JSON.stringify({
    gates: [{ access, commit, id: 'soklet-website', repository }],
  })}\n`);
}

try {
  git('init', '--quiet', source);
  git('-C', source, 'config', 'user.name', 'Website Mirror Test');
  git('-C', source, 'config', 'user.email', 'website-mirror@example.invalid');
  writeFileSync(join(source, 'index.txt'), 'pinned private website fixture\n');
  git('-C', source, 'add', 'index.txt');
  git('-C', source, 'commit', '--quiet', '-m', 'Create website fixture');
  const commit = git('-C', source, 'rev-parse', 'HEAD');
  writeManifest(commit);

  git('init', '--bare', '--quiet', mirror);
  git('-C', mirror, 'remote', 'add', 'origin', canonicalRepository);
  git('-C', mirror, 'fetch', '--quiet', '--no-tags', source, commit);
  git('-C', mirror, 'update-ref', 'refs/heads/candidate', commit);
  assert.equal(verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot).commit, commit);

  const checkout = join(temporaryRoot, 'checkout');
  git('init', '--quiet', checkout);
  git('-C', checkout, 'fetch', '--quiet', '--no-tags', '--depth=1', mirror, commit);
  git('-C', checkout, 'checkout', '--quiet', '--detach', 'FETCH_HEAD');
  assert.equal(git('-C', checkout, 'rev-parse', 'HEAD'), commit);
  assert.equal(readFileSync(join(checkout, 'index.txt'), 'utf8'),
    'pinned private website fixture\n');

  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, join(temporaryRoot, 'other'), temporaryRoot),
    /outside the dedicated runner temporary location/u,
  );
  writeManifest(commit, 'PUBLIC_READ_ONLY');
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /canonical private website pin/u,
  );
  writeManifest(commit, 'PRIVATE_READ_ONLY', 'https://github.com/other/site.git');
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /canonical private website pin/u,
  );
  writeManifest('0'.repeat(40));
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /mirror commit differs/u,
  );
  writeManifest(commit);

  git('-C', mirror, 'remote', 'set-url', 'origin', 'https://example.invalid/site.git');
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /mirror origin differs/u,
  );
  git('-C', mirror, 'remote', 'set-url', 'origin', canonicalRepository);

  git('-C', mirror, 'update-ref', 'refs/heads/other', commit);
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /unexpected branch/u,
  );
  git('-C', mirror, 'update-ref', '-d', 'refs/heads/other');

  git('-C', mirror, 'config', '--local', 'http.https://github.com/.extraheader',
    'Authorization: token synthetic');
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /credential configuration/u,
  );
  git('-C', mirror, 'config', '--local', '--unset', 'http.https://github.com/.extraheader');

  const parkedMirror = join(temporaryRoot, 'parked-mirror.git');
  renameSync(mirror, parkedMirror);
  symlinkSync(parkedMirror, mirror);
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /symlink/u,
  );
  rmSync(mirror);
  renameSync(parkedMirror, mirror);

  rmSync(mirror, { force: true, recursive: true });
  assert.throws(
    () => verifyReleaseWebsiteMirror(manifestPath, mirror, temporaryRoot),
    /mirror or RUNNER_TEMP is missing/u,
  );

  process.stdout.write('Private website mirror self-test passed.\n');
} finally {
  rmSync(temporaryRoot, { force: true, recursive: true });
}
