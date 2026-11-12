#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compareIncompatibilities, incompatibilitySummary } from './summarize.mjs';

const jsonl = values => values.map(value => JSON.stringify(value)).join('\n') + '\n';
const reference = jsonl([
  { id: 'unchanged', kind: 'method', changes: [] },
  { id: 'changed', changes: [{ sourceCompatible: false }] },
  { id: 'removed', changes: [] },
]);
const current = jsonl([
  { changes: [], kind: 'method', id: 'unchanged' },
  { id: 'changed', changes: [{ sourceCompatible: true }] },
  { id: 'added<A|B>', changes: [] },
]);
const delta = compareIncompatibilities(reference, current);
assert.deepEqual(delta, {
  added: ['added<A|B>'], removed: ['removed'], changed: ['changed'],
  referenceCount: 3, currentCount: 3,
});
assert.match(incompatibilitySummary(delta), /added&lt;A&#124;B&gt;/u);
assert.match(incompatibilitySummary(delta), /no ledger refresh is required/u);
assert.match(incompatibilitySummary(compareIncompatibilities(reference, reference)), /No differences/u);
assert.match(incompatibilitySummary({ ...delta, added: Array.from({ length: 102 }, (_, i) => String(i)) }),
  /first 100 of 104 differences/u);
assert.throws(() => compareIncompatibilities('{broken', ''), SyntaxError);
assert.throws(() => compareIncompatibilities(jsonl([{ id: 'duplicate' }, { id: 'duplicate' }]), ''), /unique/u);

const temporary = mkdtempSync(join(tmpdir(), 'soklet-api-summary-'));
try {
  const baseline = join(temporary, 'reference.jsonl');
  const report = join(temporary, 'current.jsonl');
  const output = join(temporary, 'summary.md');
  writeFileSync(baseline, reference);
  writeFileSync(report, current);
  const result = spawnSync(process.execPath, [
    join(dirname(fileURLToPath(import.meta.url)), 'summarize.mjs'), baseline, report, output,
  ], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr); // Differences must not fail the report command.
  assert.equal(readFileSync(output, 'utf8'), incompatibilitySummary(delta));
  assert.equal(result.stdout, readFileSync(output, 'utf8'));
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
console.log('Advisory API summary self-test passed.');
