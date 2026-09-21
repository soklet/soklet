#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateEntry, validateExchanges, validateVerification } from './run.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const example = resolve(here, '../../../examples/skills');
const skillUri = 'skill://soklet.example/toy-catalog-guide/SKILL.md';
const baseUri = skillUri.slice(0, -'SKILL.md'.length);
const memoryNotice = '[mcp-inspector] Secrets are not written anywhere and are lost on exit.\n';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const clone = value => JSON.parse(JSON.stringify(value));

function files() {
  return new Map([
    [skillUri, readFileSync(resolve(example, 'resources/SKILL.md'))],
    [`${baseUri}references/catalog.csv`,
      readFileSync(resolve(example, 'resources/references/catalog.csv'))],
    [`${baseUri}assets/sample.bin`,
      Buffer.from([0x53, 0x4f, 0x4b, 0x4c, 0x45, 0x54, 0, 1, 0xff])],
  ]);
}

function entry(expected = files()) {
  return {
    uri: skillUri,
    frontmatter: {
      name: 'toy-catalog-guide',
      description: 'A synthetic guide for exploring a tiny toy catalog.',
      example: { catalog: { currency: 'USD', departments: ['puzzles', 'outdoor'] },
        provenance: { kind: 'synthetic', revision: 1 } },
    },
    resources: [...expected].map(([uri, bytes]) => ({
      uri,
      size: bytes.length,
      digest: `sha256:${sha(bytes)}`,
    })),
  };
}

function verification(tampered = false, expected = files()) {
  const reportFiles = [...expected].map(([uri, bytes]) => {
    const digest = `sha256:${sha(bytes)}`;
    const changed = tampered && uri.endsWith('/assets/sample.bin');
    const actual = Buffer.from(bytes);
    if (changed) actual[0] ^= 1;
    return {
      uri,
      expectedSize: bytes.length,
      actualSize: bytes.length,
      expectedDigest: digest,
      actualDigest: `sha256:${sha(actual)}`,
      status: changed ? 'mismatch' : 'verified',
    };
  });
  const report = {
    uri: skillUri,
    name: 'toy-catalog-guide',
    outcome: tampered ? 'failed' : 'verified',
    ok: !tampered,
    conformance: [],
    frontmatter: [],
    files: reportFiles,
  };
  const summary = tampered
    ? '1 of 1 skill failed verification (1 digest/size mismatch across 3 files).'
    : 'Verified 1 skill and 3 files: no conformance errors.';
  const error = tampered
    ? `${JSON.stringify({ error: { code: 'skills_nonconformant', message: summary } })}\n`
    : '';
  return {
    signal: null,
    code: tampered ? 7 : 0,
    stdout: `${JSON.stringify(report)}\n`,
    stderr: `${memoryNotice}${summary}\n${error}`,
  };
}

test('validateEntry accepts the exact three-file manifest', () => {
  assert.doesNotThrow(() => validateEntry(entry()));
});

test('validateEntry rejects a missing or substituted binary resource', () => {
  const missing = entry();
  missing.resources.pop();
  assert.throws(() => validateEntry(missing));

  const substituted = entry();
  substituted.resources[2].uri = `${baseUri}assets/other.bin`;
  assert.throws(() => validateEntry(substituted));
});

test('validateEntry rejects incorrect size and digest evidence', () => {
  const wrongSize = entry();
  wrongSize.resources[1].size++;
  assert.throws(() => validateEntry(wrongSize));

  const wrongDigest = entry();
  wrongDigest.resources[0].digest = `sha256:${'0'.repeat(64)}`;
  assert.throws(() => validateEntry(wrongDigest));
});

test('validateEntry rejects lost or changed authored frontmatter', () => {
  const missing = entry();
  delete missing.frontmatter.example;
  assert.throws(() => validateEntry(missing));
  const changed = entry();
  changed.frontmatter.description = 'Different description';
  assert.throws(() => validateEntry(changed));
});

test('each CLI verification must cause exact correlated wire operations', () => {
  const rows = [
    { method: 'server/discover', target: 'NONE', valid: true },
    { method: 'skills/get', target: 'ROOT', valid: true },
    ...['ROOT', 'BINARY', 'CSV'].map(target => ({ method: 'resources/read', target, valid: true })),
  ];
  assert.doesNotThrow(() => validateExchanges(rows, 'skills/get', skillUri, true));
  assert.throws(() => validateExchanges([], 'skills/get', skillUri, true));
  assert.throws(() => validateExchanges(rows.slice(0, -1), 'skills/get', skillUri, true));
  const wrong = clone(rows);
  wrong[3].target = 'ROOT';
  assert.throws(() => validateExchanges(wrong, 'skills/get', skillUri, true));
  rows[4].valid = false;
  assert.throws(() => validateExchanges(rows, 'skills/get', skillUri, true));
});

test('validateVerification accepts the exact successful report', () => {
  const result = validateVerification(verification());
  assert.deepEqual(result, {
    outcome: 'verified', files: 3, verified: 3, mismatched: 0,
    frontmatterMatches: true, exitCode: 0,
  });
});

test('validateVerification accepts only the expected binary negative control', () => {
  const result = validateVerification(verification(true), true);
  assert.deepEqual(result, {
    outcome: 'failed', files: 3, verified: 2, mismatched: 1,
    frontmatterMatches: true, exitCode: 7,
  });
});

test('validateVerification rejects incomplete, missing, or wrong-digest evidence', () => {
  const incomplete = verification();
  const incompleteReport = JSON.parse(incomplete.stdout);
  incompleteReport.incomplete = true;
  incomplete.stdout = `${JSON.stringify(incompleteReport)}\n`;
  assert.throws(() => validateVerification(incomplete));

  const missing = verification();
  const missingReport = JSON.parse(missing.stdout);
  missingReport.files.pop();
  missing.stdout = `${JSON.stringify(missingReport)}\n`;
  assert.throws(() => validateVerification(missing));

  const wrongDigest = clone(verification());
  const wrongDigestReport = JSON.parse(wrongDigest.stdout);
  wrongDigestReport.files[0].actualDigest = `sha256:${'f'.repeat(64)}`;
  wrongDigest.stdout = `${JSON.stringify(wrongDigestReport)}\n`;
  assert.throws(() => validateVerification(wrongDigest));

  const arbitraryNegativeDigest = verification(true);
  const arbitraryNegativeReport = JSON.parse(arbitraryNegativeDigest.stdout);
  arbitraryNegativeReport.files[2].actualDigest = `sha256:${'f'.repeat(64)}`;
  arbitraryNegativeDigest.stdout = `${JSON.stringify(arbitraryNegativeReport)}\n`;
  assert.throws(() => validateVerification(arbitraryNegativeDigest, true));
});
