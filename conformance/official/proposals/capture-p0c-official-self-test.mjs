#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import {
  captureP0cProposal,
  officialCaptureReceipt,
  parseCaptureArguments,
} from './capture-p0c-official.mjs';
import { sha256 } from '../verify.mjs';

const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-p0c-capture-self-test-'));
try {
  const paths = [scratch, scratch, scratch, resolve(scratch, 'output')];
  const args = ['--suite-dir', paths[0], '--candidate-jar', paths[1],
    '--java-home', paths[2], '--output-dir', paths[3], '--run-id', 'P0C.test_01'];
  const parsed = parseCaptureArguments(args);
  assert.equal(parsed.runId, 'P0C.test_01');
  assert.throws(() => parseCaptureArguments([...args.slice(0, -1), 'bad']), /run-id/);
  assert.throws(() => parseCaptureArguments([...args.slice(0, 8),
    '--wrong', 'P0C.test_01']), /--run-id/);
  assert.throws(() => parseCaptureArguments([...args.slice(0, 1),
    'relative-suite', ...args.slice(2)]), /absolute path/);

  const checksPath = resolve(scratch, 'checks.json');
  const stdoutPath = resolve(scratch, 'official.stdout.log');
  const stderrPath = resolve(scratch, 'official.stderr.log');
  writeFileSync(checksPath, '[{"id":"first","status":"FAILURE"}]\n');
  writeFileSync(stdoutPath, 'official failure\n');
  writeFileSync(stderrPath, '');
  const pins = {
    protocolVersion: '2026-07-28',
    officialConformanceSuite: {
      commit: 'a983ba93c91e0bb31d0b6849eeb52f0ad1083107',
      sourceTree: { sha256: 'e63d6f13100504101afdfd5cfd084c92d801e2b4466d68965aa2e0c48a87998d' },
    },
  };
  const prepared = {
    candidateJarSha256: 'a'.repeat(64),
    fixtureSourceSha256: 'b'.repeat(64),
    fixtureClassesSha256: 'c'.repeat(64),
  };
  const result = {
    status: 1, signal: null, timedOut: false, outputFailure: null,
  };
  const endpoint = 'http://127.0.0.1:19020/mcp';
  const receipt = officialCaptureReceipt({
    pins, runId: parsed.runId, prepared, result,
    checksPath, stdoutPath, stderrPath, endpoint,
  });
  assert.equal(receipt.exitCode, 1);
  assert.equal(receipt.signal, null);
  assert.equal(receipt.endpoint, endpoint);
  assert.equal(receipt.checksSha256, sha256(readFileSync(checksPath)));
  assert.equal(receipt.stdoutSha256, sha256(readFileSync(stdoutPath)));
  assert.equal(receipt.stderrSha256, sha256(readFileSync(stderrPath)));
  // Capture records the child truth, including a regression to success or a
  // timeout. The independent disposition verifier must reject those outcomes.
  assert.equal(officialCaptureReceipt({
    pins, runId: parsed.runId, prepared, result: { ...result, status: 0 },
    checksPath, stdoutPath, stderrPath, endpoint,
  }).exitCode, 0);
  assert.equal(officialCaptureReceipt({
    pins, runId: parsed.runId, prepared, result: { ...result, timedOut: true },
    checksPath, stdoutPath, stderrPath, endpoint,
  }).timedOut, true);

  const candidateJarPath = resolve(scratch, 'candidate.jar');
  writeFileSync(candidateJarPath, 'development self-test placeholder');
  await assert.rejects(captureP0cProposal({
    ...parsed,
    candidateJarPath,
    suiteDirectory: resolve(scratch, 'missing-suite'),
  }), /pinned suite/);
  assert.throws(() => readFileSync(parsed.outputDirectory), /ENOENT/);
  console.log('P0-C official capture self-test passed (argument, raw receipt, and preflight boundaries).');
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
