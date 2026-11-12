#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { prepareDependencies, restoreUpstreamLock } from './dependencies.mjs';
import { sha256, sourceTreeIdentity, verifyManifestSet } from './verify.mjs';

const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-conformance-dependencies-'));
let checks = 0;
try {
  function fixture(name, mutateLock = () => {}) {
    const root = resolve(scratch, name);
    mkdirSync(resolve(root, 'src/spec-types'), { recursive: true });
    mkdirSync(resolve(root, 'dist'));
    const pins = structuredClone(verifyManifestSet().pins);
    const suite = pins.officialConformanceSuite;
    const manifest = {
      name: suite.package.name, version: suite.package.version, type: 'module',
      bin: { conformance: 'dist/index.js' },
      scripts: { build: 'tsdown src/index.ts --minify --clean --target node20 --no-fixed-extension' },
    };
    const original = Buffer.from(JSON.stringify({ lockfileVersion: 3, packages: {
      '': { name: manifest.name, version: manifest.version },
      'node_modules/ajv': { version: suite.lockedSchemaDependencies.ajv },
      'node_modules/ajv-formats': { version: suite.lockedSchemaDependencies['ajv-formats'] },
      'node_modules/example': { version: '1.0.0' },
    } }));
    const lock = JSON.parse(original);
    lock.packages['node_modules/example'].version = '1.0.1';
    mutateLock(lock);
    const overlay = Buffer.from(JSON.stringify(lock));
    writeFileSync(resolve(root, 'package.json'), JSON.stringify(manifest));
    writeFileSync(resolve(root, 'package-lock.json'), original);
    writeFileSync(resolve(root, 'src/spec-types/draft.schema.json'), '{}');
    writeFileSync(resolve(root, 'dist/index.js'), '// fixture CLI');
    suite.package.packageJsonSha256 = sha256(readFileSync(resolve(root, 'package.json')));
    suite.package.packageLockJsonSha256 = sha256(original);
    suite.vendoredProtocolSchema.sha256 = sha256(Buffer.from('{}'));
    Object.assign(suite.builtEntryPoint, { bytes: 14, sha256: sha256(Buffer.from('// fixture CLI')) });
    Object.assign(suite.sourceTree, sourceTreeIdentity(root, suite.sourceTree.excludedDirectoryNames));
    const lockPath = resolve(scratch, `${name}-lock.json`);
    writeFileSync(lockPath, overlay);
    return { root, original, options: { pins, lockPath, originalLockBytes: original } };
  }

  let f = fixture('roundtrip');
  prepareDependencies(f.root, f.options);
  assert.equal(JSON.parse(readFileSync(resolve(f.root, 'package-lock.json')))
    .packages['node_modules/example'].version, '1.0.1'); checks++;
  restoreUpstreamLock(f.root, f.options);
  assert.deepEqual(readFileSync(resolve(f.root, 'package-lock.json')), f.original); checks++;

  f = fixture('lock-format', lock => { lock.lockfileVersion = 2; });
  assert.throws(() => prepareDependencies(f.root, f.options), /identity/); checks++;
  f = fixture('validator', lock => { lock.packages['node_modules/ajv'].version = '99.0.0'; });
  assert.throws(() => prepareDependencies(f.root, f.options), /schema-validator/); checks++;
  f = fixture('declarations', lock => { lock.packages[''].dependencies = { injected: '*' }; });
  assert.throws(() => prepareDependencies(f.root, f.options), /package declarations/); checks++;
  f = fixture('dirty-source');
  writeFileSync(resolve(f.root, 'src/extra.ts'), '// changed');
  assert.throws(() => prepareDependencies(f.root, f.options), /source tree/); checks++;
  f = fixture('changed-lock');
  prepareDependencies(f.root, f.options);
  writeFileSync(resolve(f.root, 'package-lock.json'), '{}');
  assert.throws(() => restoreUpstreamLock(f.root, f.options), /changed during/); checks++;
  f = fixture('changed-source');
  prepareDependencies(f.root, f.options);
  writeFileSync(resolve(f.root, 'src/extra.ts'), '// changed');
  assert.throws(() => restoreUpstreamLock(f.root, f.options), /source tree/); checks++;
  f = fixture('changed-cli');
  prepareDependencies(f.root, f.options);
  writeFileSync(resolve(f.root, 'dist/index.js'), '// changed');
  assert.throws(() => restoreUpstreamLock(f.root, f.options), /built entry point size/); checks++;
  f = fixture('wrong-original');
  prepareDependencies(f.root, f.options);
  assert.throws(() => restoreUpstreamLock(f.root, { ...f.options, originalLockBytes: Buffer.from('{}') }), /Original upstream lock checksum/); checks++;
  console.log(`Conformance dependency refresh self-test passed (${checks} checks).`);
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
