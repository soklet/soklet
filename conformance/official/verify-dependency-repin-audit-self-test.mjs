#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyDependencyRepinAudit } from './verify-dependency-repin-audit.mjs';

const proposal = resolve(dirname(fileURLToPath(import.meta.url)),
  'proposals/alpha11-dependency-repin-2026-09-23');
const lock = readFileSync(resolve(proposal, 'package-lock.json'));
const runtime = JSON.parse(readFileSync(resolve(proposal,
  'evidence/runtime-audit.json'), 'utf8'));
const full = JSON.parse(readFileSync(resolve(proposal,
  'evidence/repin-audit.json'), 'utf8'));
const clone = (value) => structuredClone(value);

assert.equal(verifyDependencyRepinAudit(lock, runtime, full).lockSha256,
  '4bbf44df937f30f99f56dcb359ec5ca67c8200241b279f49f25d4b646e38fa1f');
assert.throws(() => verifyDependencyRepinAudit(Buffer.from('{}'), runtime, full),
  /lockfile differs/);
const runtimeDrift = clone(runtime);
runtimeDrift.metadata.vulnerabilities.high = 1;
assert.throws(() => verifyDependencyRepinAudit(lock, runtimeDrift, full),
  /runtime audit high count changed/);
const fullDrift = clone(full);
fullDrift.vulnerabilities.esbuild.via[0].url = 'https://example.invalid/new';
assert.throws(() => verifyDependencyRepinAudit(lock, runtime, fullDrift),
  /reviewed esbuild finding changed/);
const extraFinding = clone(full);
extraFinding.vulnerabilities.another = { severity: 'low' };
assert.throws(() => verifyDependencyRepinAudit(lock, runtime, extraFinding),
  /single reviewed esbuild finding/);
console.log('Verified exact conformance dependency audit policy and drift rejection');
