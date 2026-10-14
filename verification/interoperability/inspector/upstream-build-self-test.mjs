import assert from 'node:assert/strict';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {BUILD_COMMANDS, INSPECTOR_SOURCE_PIN, buildUpstream, upstreamSourceIdentity,
  upstreamTreeIdentity, verifyUpstreamBuild, verifyUpstreamSources} from './upstream-build.mjs';

function scratch(run) {
  const directory = mkdtempSync(join(tmpdir(), 'soklet-inspector-upstream-'));
  try {return run(directory);} finally {rmSync(directory, {recursive: true, force: true});}
}

test('source pin names the merged fix and distinguishes it from published 2.10.1', () => {
  assert.equal(INSPECTOR_SOURCE_PIN.commit, '1974465ccb5454a5110e609e4140171fa360a544');
  assert.equal(INSPECTOR_SOURCE_PIN.distribution, 'UNRELEASED_UPSTREAM_SOURCE_BUILD');
  assert.equal(INSPECTOR_SOURCE_PIN.packageVersion, '2.10.1');
  assert.equal(INSPECTOR_SOURCE_PIN.sourceFiles, 1658);
  assert.equal(Object.keys(INSPECTOR_SOURCE_PIN.locks).length, 4);
  for (const value of [INSPECTOR_SOURCE_PIN.archiveSha256, INSPECTOR_SOURCE_PIN.sourceSha256,
    ...Object.values(INSPECTOR_SOURCE_PIN.locks)]) assert.match(value, /^[a-f0-9]{64}$/);
  assert(BUILD_COMMANDS.every(row => !row.tool.includes('install') && !row.tool.includes('release')));
  assert(BUILD_COMMANDS.at(-1).tool.startsWith('clients/web/node_modules/vitest/'));
});

test('source identity detects changed, missing and injected upstream source files', () => scratch(directory => {
  writeFileSync(join(directory, 'package.json'), '{}');
  mkdirSync(join(directory, 'core'));
  const file = join(directory, 'core', 'challenge.ts');
  writeFileSync(file, 'original');
  const original = upstreamSourceIdentity(directory);
  writeFileSync(file, 'modified');
  assert.notDeepEqual(upstreamSourceIdentity(directory), original);
  writeFileSync(file, 'original');
  writeFileSync(join(directory, 'injected.ts'), 'side effect');
  assert.notDeepEqual(upstreamSourceIdentity(directory), original);
  rmSync(join(directory, 'injected.ts'));
  rmSync(file);
  assert.notDeepEqual(upstreamSourceIdentity(directory), original);
  assert.throws(() => verifyUpstreamSources(directory), /UPSTREAM_SOURCE_DRIFT/);
}));

test('generated output is covered by full build identity even when excluded from source identity', () => scratch(directory => {
  writeFileSync(join(directory, 'package.json'), '{}');
  mkdirSync(join(directory, 'clients/web/build'), {recursive: true});
  const file = join(directory, 'clients/web/build/index.js');
  writeFileSync(file, 'first build');
  const source = upstreamSourceIdentity(directory), build = upstreamTreeIdentity(directory);
  writeFileSync(file, 'altered build');
  assert.deepEqual(upstreamSourceIdentity(directory), source);
  assert.notDeepEqual(upstreamTreeIdentity(directory), build);
}));

test('source symlinks and dependency symlinks escaping the build are rejected', () => scratch(directory => {
  mkdirSync(join(directory, 'source'));
  writeFileSync(join(directory, 'outside'), 'external');
  symlinkSync('../outside', join(directory, 'source', 'alias'));
  assert.throws(() => upstreamSourceIdentity(join(directory, 'source')), /UPSTREAM_SOURCE_SYMLINK/);
  assert.throws(() => upstreamTreeIdentity(join(directory, 'source')), /UPSTREAM_SYMLINK_ESCAPE/);
}));

test('a forged success receipt without exact recipe and source pin is rejected', () => scratch(directory => {
  writeFileSync(join(directory, 'receipt.json'), JSON.stringify({
    kind: 'soklet.inspector.upstream-source-build.v1', status: 'PASS', pin: INSPECTOR_SOURCE_PIN,
  }));
  assert.throws(() => verifyUpstreamBuild(directory), /UPSTREAM_RECEIPT_INVALID/);
}));

test('build roots must be fresh so existing installs cannot be relabelled as the fixed source', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'soklet-inspector-existing-'));
  const nodeVersion = Object.getOwnPropertyDescriptor(process.versions, 'node');
  try {
    writeFileSync(join(directory, 'keep'), 'existing');
    // This test must reach the freshness guard even when the self-tests run on
    // another Node version. No build command can run for an existing root.
    Object.defineProperty(process.versions, 'node', {...nodeVersion, value: INSPECTOR_SOURCE_PIN.node});
    await assert.rejects(buildUpstream({workDirectory: directory}), /UPSTREAM_WORK_MUST_BE_FRESH/);
    assert.equal(readFileSync(join(directory, 'keep'), 'utf8'), 'existing');
  } finally {
    Object.defineProperty(process.versions, 'node', nodeVersion);
    rmSync(directory, {recursive: true, force: true});
  }
});

test('a wrong Node version rejects the build before creating any files', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'soklet-inspector-version-'));
  const nodeVersion = Object.getOwnPropertyDescriptor(process.versions, 'node');
  try {
    Object.defineProperty(process.versions, 'node', {...nodeVersion, value: '0.0.0'});
    await assert.rejects(buildUpstream({workDirectory: join(directory, 'build')}), /UPSTREAM_NODE_VERSION/);
    assert.equal(existsSync(join(directory, 'build')), false);
  } finally {
    Object.defineProperty(process.versions, 'node', nodeVersion);
    rmSync(directory, {recursive: true, force: true});
  }
});
