#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  expectedLocalSimulatorOutput,
  localSimulatorDriverArguments,
  localSimulatorRows,
  verifyLocalSimulatorDriverResult,
} from './run-local-simulator.mjs';
import { verifyManifestSet } from './verify.mjs';

const { selection } = verifyManifestSet();
const rows = localSimulatorRows(selection);
assert.equal(rows.length, 36);
assert.equal(rows[0].ordinal, 1);
assert.equal(rows[0].name, 'server-stateless');
assert.equal(rows[1].ordinal, 2);
assert.equal(rows[1].name, 'completion-complete');
assert.equal(rows[2].ordinal, 3);
assert.equal(rows[2].name, 'tools-list');
assert.equal(rows[35].ordinal, 50);
assert.equal(rows[35].name, 'input-required-result-validate-input');
assert.deepEqual(localSimulatorDriverArguments(rows).slice(0, 3), [
  '1:server-stateless',
  '2:completion-complete',
  '3:tools-list',
]);

const expected = expectedLocalSimulatorOutput(rows);
assert.equal(expected.toString('utf8').split('\n').length, 37);
assert.equal(expected.toString('utf8').split('\n')[0],
  'PASS\t1\tserver-stateless');
assert.equal(expected.toString('utf8').split('\n')[1],
  'PASS\t2\tcompletion-complete');
assert.equal(expected.toString('utf8').split('\n')[35],
  'PASS\t50\tinput-required-result-validate-input');
assert.deepEqual(verifyLocalSimulatorDriverResult({
  error: undefined,
  signal: null,
  status: 0,
  stderr: Buffer.alloc(0),
  stdout: expected,
}, expected), expected);

assert.throws(() => verifyLocalSimulatorDriverResult({
  error: undefined,
  signal: null,
  status: 1,
  stderr: Buffer.from('java.lang.AssertionError: scenario 12 failed\n'),
  stdout: Buffer.from('PASS\t1\tserver-stateless\n'),
}, expected), (error) => {
  assert.match(error.message, /exited status=1 signal=null/);
  assert.match(error.message, /stdout:\nPASS\t1\tserver-stateless/);
  assert.match(error.message,
    /stderr:\njava\.lang\.AssertionError: scenario 12 failed/);
  return true;
});

const oversizedDiagnostic = 'x'.repeat(17 * 1024);
assert.throws(() => verifyLocalSimulatorDriverResult({
  error: undefined,
  signal: null,
  status: 1,
  stderr: Buffer.alloc(0),
  stdout: Buffer.from(oversizedDiagnostic),
}, expected), (error) => {
  assert.ok(error.message.length < oversizedDiagnostic.length);
  assert.match(error.message, /stdout:\nx+\n<truncated>\nstderr:\n<empty>$/);
  return true;
});

const reordered = structuredClone(selection);
[reordered.scenarios[0], reordered.scenarios[2]] =
  [reordered.scenarios[2], reordered.scenarios[0]];
assert.throws(() => localSimulatorRows(reordered), /strict manifest ordinal order/);
const duplicate = structuredClone(selection);
duplicate.scenarios[3].name = duplicate.scenarios[2].name;
assert.throws(() => localSimulatorRows(duplicate), /invalid or duplicate name/);
const missing = structuredClone(selection);
missing.scenarios[3].selection = 'NOT_APPLICABLE';
assert.throws(() => localSimulatorRows(missing), /exactly 36 RUN rows/);

for (const result of [
  { error: new Error('spawn failed'), signal: null, status: null,
    stderr: Buffer.alloc(0), stdout: Buffer.alloc(0) },
  { error: undefined, signal: null, status: 1,
    stderr: Buffer.alloc(0), stdout: expected },
  { error: undefined, signal: 'SIGTERM', status: null,
    stderr: Buffer.alloc(0), stdout: expected },
  { error: undefined, signal: null, status: 0,
    stderr: Buffer.from('unexpected'), stdout: expected },
  { error: undefined, signal: null, status: 0,
    stderr: Buffer.alloc(0), stdout: Buffer.from('PASS\t1\twrong\n') },
  { error: undefined, signal: null, status: 0,
    stderr: Buffer.alloc(0), stdout: Buffer.from('FAIL\t1\tserver-stateless\n') },
  { error: undefined, signal: null, status: 0,
    stderr: Buffer.alloc(0), stdout: Buffer.from(expected.toString('utf8')
      .replaceAll('\n', '\r\n')) },
  { error: undefined, signal: null, status: 0,
    stderr: Buffer.alloc(0), stdout: expected.subarray(0, expected.length - 1) },
]) assert.throws(() => verifyLocalSimulatorDriverResult(result, expected));

console.log('Local MCP simulator harness self-test passed.');
