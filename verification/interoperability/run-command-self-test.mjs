#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const runner = resolve(scriptDirectory, 'run-command.mjs');
const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-interop-command-test-'));

function invoke(...arguments_) {
  return spawnSync(process.execPath, [runner, ...arguments_], {
    encoding: 'utf8',
    timeout: 10000,
  });
}

try {
  const success = invoke(
    '5',
    scratch,
    process.execPath,
    '-e',
    'process.stdout.write("bounded success\\n")',
  );
  assert.equal(success.status, 0, success.stderr);
  assert.equal(success.stdout, 'bounded success\n');
  assert.equal(success.stderr, '');

  const failure = invoke('5', scratch, process.execPath, '-e', 'process.exit(7)');
  assert.equal(failure.status, 1);
  assert.match(failure.stderr, /Command failed \(7\)/);

  const childPidPath = resolve(scratch, 'child.pid');
  const timeout = invoke(
    '1',
    scratch,
    process.execPath,
    '-e',
    [
      'const {spawn}=require("node:child_process")',
      'const {writeFileSync}=require("node:fs")',
      'const child=spawn(process.execPath,["-e","setInterval(()=>{},1000)"],{stdio:"ignore"})',
      `writeFileSync(${JSON.stringify(childPidPath)},String(child.pid))`,
      'setInterval(()=>{},1000)',
    ].join(';'),
  );
  assert.equal(timeout.status, 1);
  assert.match(timeout.stderr, /Command timed out after 1 seconds/);
  assert.equal(existsSync(childPidPath), true);

  if (process.platform !== 'win32') {
    const childPid = Number(readFileSync(childPidPath, 'utf8'));
    assert.throws(() => process.kill(childPid, 0), { code: 'ESRCH' });
  }

  console.log('Interoperability bounded-command self-test passed.');
} finally {
  rmSync(scratch, { force: true, recursive: true });
}
