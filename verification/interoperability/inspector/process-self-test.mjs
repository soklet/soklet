#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { runProcess, startProcess } from './process.mjs';

const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-inspector-process-test-'));
const environment = { PATH: process.env.PATH ?? '', LANG: 'C.UTF-8' };

function options(overrides = {}) {
  return { cwd: scratch, env: environment, timeoutMs: 5000, ...overrides };
}

async function rejectsRedacted(promise, expectedMessage) {
  await assert.rejects(promise, (error) => {
    assert.equal(error.message, expectedMessage);
    assert.doesNotMatch(error.message, /SECRET_TOKEN|private-output|token-123/);
    return true;
  });
}

try {
  const success = await runProcess(process.execPath,
    ['-e', 'process.stdout.write("ok\\n"); process.stderr.write("note\\n")'],
    options());
  assert.deepEqual(success, {
    code: 0, signal: null, stdout: 'ok\n', stderr: 'note\n',
  });

  // A nonzero process exit is an observed result; the caller applies policy.
  const nonzero = await runProcess(process.execPath,
    ['-e', 'process.stdout.write("private-output"); process.exit(7)'], options());
  assert.deepEqual(nonzero, {
    code: 7, signal: null, stdout: 'private-output', stderr: '',
  });

  await rejectsRedacted(runProcess(resolve(scratch, 'missing-executable'), [],
    options()), 'Managed process failed to start');

  await rejectsRedacted(runProcess(process.execPath,
    ['-e', 'process.stdout.write("SECRET_TOKEN-overflow")'],
    options({ maxOutputBytes: 8 })),
  'Managed process output exceeded its bound');

  await rejectsRedacted(runProcess(process.execPath,
    ['-e', 'process.stderr.write("token-123"); setInterval(()=>{}, 1000)'],
    options({ timeoutMs: 100 })), 'Managed process timed out');

  const stdin = startProcess(process.execPath,
    ['-e', 'let value=""; process.stdin.setEncoding("utf8"); '
      + 'process.stdin.on("data", chunk => value += chunk); '
      + 'process.stdin.on("end", () => process.stdout.write("EOF:" + value))'],
    options({ stdin: 'pipe' }));
  let observed = '';
  stdin.child.stdout.on('data', (chunk) => { observed += chunk.toString('utf8'); });
  stdin.child.stdin.end('input');
  const eof = await stdin.completion;
  assert.equal(eof.code, 0);
  assert.equal(eof.stdout, 'EOF:input');
  assert.equal(observed, 'EOF:input');

  const descendant = await runProcess(process.execPath,
    ['-e', 'const {spawn}=require("node:child_process"); '
      + 'const child=spawn(process.execPath,["-e","setInterval(()=>{},1000)"],'
      + '{stdio:"ignore"}); child.unref(); process.stdout.write(String(child.pid))'],
    options());
  assert.equal(descendant.code, 0);
  const descendantPid = Number(descendant.stdout);
  assert.ok(Number.isSafeInteger(descendantPid) && descendantPid > 0);
  assert.throws(() => process.kill(descendantPid, 0), { code: 'ESRCH' });

  const stopped = startProcess(process.execPath,
    ['-e', 'setInterval(()=>{}, 1000)'], options());
  await Promise.all([stopped.stop(), stopped.stop(), stopped.stop()]);
  await rejectsRedacted(stopped.completion, 'Managed process stopped');

  const signaled = startProcess(process.execPath,
    ['-e', 'setInterval(()=>{}, 1000)'], options());
  signaled.child.kill('SIGTERM');
  const signaledResult = await signaled.completion;
  assert.equal(signaledResult.code, null);
  assert.equal(signaledResult.signal, 'SIGTERM');

  assert.throws(() => startProcess(process.execPath, [],
    options({ timeoutMs: 0 })), /Invalid managed process options/);

  console.log('Inspector managed-process self-test passed.');
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
