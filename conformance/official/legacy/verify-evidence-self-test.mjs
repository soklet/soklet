import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { verifyManifestSet } from '../verify.mjs';
import { verifyLegacyEvidence } from './verify-evidence.mjs';
import { createLegacyReceiptFixture } from './test-support.mjs';

const root = mkdtempSync(resolve(tmpdir(), 'soklet-legacy-evidence-test-'));
try {
  const { pins } = verifyManifestSet();
  const expectedProvenance = { candidateCommit: 'a'.repeat(40), artifacts: { mainJar: { sha256: 'b'.repeat(64) } } };
  const evidencePath = resolve(root, 'receipt/evidence.json');
  const evidence = createLegacyReceiptFixture(root, evidencePath, expectedProvenance, pins);
  const options = { projectRoot: root, evidencePath, expectedProvenance, pins };
  verifyLegacyEvidence(options);
  let cases = 0;
  for (const mutate of [
    value => { value.mode = 'development'; },
    value => { value.evidenceClass = 'LEGACY_PREPARATORY_DEVELOPMENT_ONLY'; },
    value => { value.releaseCandidateEvidence = false; },
    value => { value.releaseCandidateProvenance.candidateCommit = 'c'.repeat(40); },
    value => { value.jarSha256 = 'c'.repeat(64); },
    value => { value.status = 'FAILED'; },
    value => { value.failure = 'incomplete'; },
    value => { value.suiteCommit = 'c'.repeat(40); },
    value => { value.suiteCliSha256 = 'c'.repeat(64); },
    value => { value.scenarioSelectionSha256 = 'c'.repeat(64); },
    value => { value.scenarios.pop(); },
    value => { value.scenarios.reverse(); },
    value => { value.scenarios[1] = value.scenarios[0]; },
    value => { value.scenarios[0].passed = false; },
    value => { value.scenarios[0].wireMessagesValidated = 0; },
    value => { value.scenarios[0].checksJsonSha256 = 'c'.repeat(64); },
    value => { value.scenarios[0].checksJson = '../outside.json'; },
    value => { value.scenarios[0].commandArguments[6] = '2026-07-28'; },
    value => { value.scenarios[0].commandArguments[2] = 'https://example.com/mcp'; },
    value => { value.fixtureClassSha256 = 'c'.repeat(64); },
    value => { value.fixtureClassesIdentity.sha256 = 'c'.repeat(64); },
    value => { value.runnerSourceSha256 = 'c'.repeat(64); },
    value => { value.runtimeFixtureSourceSha256 = 'c'.repeat(64); },
    value => { value.runtimeRunnerSourceSha256 = 'c'.repeat(64); },
    value => { value.runtimeSupplement = null; },
    value => { value.runtimeSupplement.passed = false; },
    value => { value.runtimeSupplement.results.pop(); },
    value => { value.runtimeSupplement.results[0].passed = false; },
    value => { value.runtimeSupplement.results[0].checks.pop(); },
    value => { value.runtimeSupplement.results[0].checks.reverse(); },
    value => { value.runtimeSupplement.results[0].checks.push('unreviewed'); },
    value => { value.unselectedOfficialScenarios = []; },
    value => { value.supplementalRequirements = []; },
    value => { value.extra = true; },
  ]) {
    const changed = structuredClone(evidence); mutate(changed); writeFileSync(evidencePath, JSON.stringify(changed));
    assert.throws(() => verifyLegacyEvidence(options)); cases++;
  }
  writeFileSync(evidencePath, JSON.stringify(evidence));
  for (const [path, replacement] of [
    ['receipt/runtime/fixture.cleanup.txt', 'clean=false\n'],
    ['receipt/runtime/fixture.stderr.log', 'failure'],
    ['receipt/' + evidence.scenarios[0].checksJson, '[]'],
    ['receipt/stateless-baseline/2025-06-18/001-server-initialize/fixture.cleanup.txt', 'forced=true\nexitCode=0\nsignal=null\n'],
  ]) {
    const file = resolve(root, path), bytes = readFileSync(file); writeFileSync(file, replacement);
    assert.throws(() => verifyLegacyEvidence(options)); writeFileSync(file, bytes); cases++;
  }
  const raw = resolve(root, 'receipt', evidence.scenarios[0].checksJson);
  const bytes = readFileSync(raw); unlinkSync(raw); symlinkSync(evidencePath, raw);
  assert.throws(() => verifyLegacyEvidence(options), /symlinks/); unlinkSync(raw); writeFileSync(raw, bytes); cases++;
  unlinkSync(evidencePath); assert.throws(() => verifyLegacyEvidence(options)); cases++;
  console.log(`Legacy release evidence verifier self-test passed (${cases} rejection cases).`);
} finally { rmSync(root, { recursive: true, force: true }); }
