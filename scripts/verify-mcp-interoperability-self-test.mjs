#!/usr/bin/env node

import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createDeterministicZip } from './release-promotion.mjs';
import { verifyMcpInteroperability } from './verify-mcp-interoperability.mjs';

const temporary = mkdtempSync(join(tmpdir(), 'soklet-sdk-self-test-'));
try {
  const candidate = join(temporary, 'built.jar');
  writeFileSync(candidate, createDeterministicZip([
    { path: 'META-INF/MANIFEST.MF', bytes: Buffer.from('Manifest-Version: 1.0\n\n') },
    { path: 'com/soklet/Soklet.class', bytes: Buffer.from('fixture') },
  ]));
  const calls = [];
  verifyMcpInteroperability(candidate, 'all', (command, args) => {
    calls.push({ command, args });
    assert.equal(command, 'sh');
    assert.equal(args.length, 2, 'hooks take only their selected JAR, no SDK checkout or approval');
    assert.deepEqual(readFileSync(args[1]), readFileSync(candidate));
    return { status: 0 };
  });
  assert.equal(calls.length, 2);
  assert.match(calls[0].args[0], /typescript\/verify\.sh$/);
  assert.match(calls[1].args[0], /go\/verify\.sh$/);
  assert.equal(existsSync(dirname(calls[0].args[1])), false, 'temporary JAR snapshot must be removed');
  let failedCalls = 0;
  assert.throws(() => verifyMcpInteroperability(candidate, 'all', () => {
    failedCalls++;
    return { status: 7 };
  }), /typescript interoperability failed \(7\)/);
  assert.equal(failedCalls, 1, 'a failed real SDK check must stop the run');
  assert.throws(() => verifyMcpInteroperability(candidate, 'unknown'), /Client must be/);
  assert.throws(() => verifyMcpInteroperability(candidate, 'typescript', (command, args) => {
    writeFileSync(args[1], 'changed');
    return { status: 0 };
  }), /valid|JAR|ZIP/);
  console.log('SDK runner self-test passed: selected JAR snapshot, both SDK hooks, failure propagation, cleanup, and mutation rejection.');
} finally { rmSync(temporary, { recursive: true, force: true }); }
