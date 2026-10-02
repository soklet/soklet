import assert from 'node:assert/strict';
import { lstatSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { sha256, sourceTreeIdentity } from '../verify.mjs';
import { assertRawChecks, profiles, revisions, selectedRuns, supplementalRequirements, unselectedOfficialScenarios } from './run.mjs';
import { runtimeSupplementChecks } from './runtime-supplement.mjs';

function exactKeys(value, keys, description) {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value), description);
  assert.deepEqual(Object.keys(value).sort(), [...keys].sort(), `${description} keys changed`);
}
function readInside(root, path, maximumBytes = 4 * 1024 * 1024) {
  const absolute = resolve(root, path);
  const local = relative(root, absolute);
  assert.ok(local && !isAbsolute(local) && !local.split(sep).includes('..'), 'Legacy evidence path escapes its root');
  let current = root;
  assert.ok(lstatSync(root).isDirectory() && !lstatSync(root).isSymbolicLink(), 'Unsafe legacy evidence root');
  for (const part of local.split(sep)) {
    current = resolve(current, part);
    assert.ok(!lstatSync(current).isSymbolicLink(), 'Legacy evidence must not contain symlinks');
  }
  const stat = lstatSync(absolute);
  assert.ok(stat.isFile() && stat.size <= maximumBytes, 'Legacy evidence must be a bounded regular file');
  return readFileSync(absolute);
}

export function verifyLegacyEvidence({ projectRoot, evidencePath, expectedProvenance, pins,
  classesDirectory = resolve(projectRoot, 'target/conformance/legacy-fixture/classes'), requireRelease = true }) {
  const root = dirname(evidencePath);
  const evidence = JSON.parse(readInside(root, evidencePath));
  exactKeys(evidence, ['formatVersion', 'mode', 'evidenceClass', 'releaseCandidateEvidence', 'releaseCandidateProvenance',
    'status', 'suiteCommit', 'suiteCliSha256', 'jarSha256', 'fixtureSourceSha256', 'fixtureClassSha256',
    'runnerSourceSha256', 'runtimeFixtureSourceSha256', 'runtimeRunnerSourceSha256', 'fixtureClassesIdentity',
    'runtimeSupplement', 'scenarioSelectionSha256', 'revisions', 'profiles', 'unselectedOfficialScenarios',
    'supplementalRequirements', 'scenarios', 'failure'], 'Legacy receipt');
  assert.equal(evidence.formatVersion, 2, 'Legacy receipt format');
  assert.equal(evidence.status, 'PASSED', 'Legacy receipt must pass'); assert.equal(evidence.failure, null);
  assert.equal(evidence.suiteCommit, pins.officialConformanceSuite.commit, 'Legacy suite commit');
  assert.equal(evidence.suiteCliSha256, pins.officialConformanceSuite.builtEntryPoint.sha256, 'Legacy suite CLI');
  assert.equal(evidence.jarSha256, expectedProvenance.artifacts.mainJar.sha256, 'Legacy candidate JAR mismatch');
  if (requireRelease) {
    assert.equal(evidence.mode, 'release', 'Development legacy evidence cannot qualify a release');
    assert.equal(evidence.evidenceClass, 'IMMUTABLE_LEGACY_RELEASE_CANDIDATE');
    assert.equal(evidence.releaseCandidateEvidence, true);
    assert.deepEqual(evidence.releaseCandidateProvenance, expectedProvenance, 'Legacy candidate provenance mismatch');
  } else {
    assert.equal(evidence.mode, 'development');
    assert.equal(evidence.evidenceClass, 'LEGACY_PREPARATORY_DEVELOPMENT_ONLY');
    assert.equal(evidence.releaseCandidateEvidence, false); assert.equal(evidence.releaseCandidateProvenance, null);
  }
  assert.deepEqual(evidence.revisions, revisions); assert.deepEqual(evidence.profiles, profiles.map(profile => profile.id));
  assert.deepEqual(evidence.unselectedOfficialScenarios, unselectedOfficialScenarios, 'Legacy exclusion dispositions');
  assert.deepEqual(evidence.supplementalRequirements, supplementalRequirements, 'Legacy supplement obligations');
  const runs = selectedRuns();
  assert.equal(evidence.scenarioSelectionSha256, sha256(Buffer.from(JSON.stringify(runs))), 'Legacy selection digest');
  assert.equal(evidence.scenarios.length, 41, 'All 41 legacy combinations are required');
  for (const [field, file] of [
    ['runnerSourceSha256', 'run.mjs'], ['fixtureSourceSha256', 'McpLegacyConformanceFixture.java'],
    ['runtimeFixtureSourceSha256', 'McpLegacyRuntimeFixture.java'], ['runtimeRunnerSourceSha256', 'runtime-supplement.mjs'],
  ]) assert.equal(evidence[field], sha256(readInside(projectRoot, `conformance/official/legacy/${file}`)), `Legacy ${file} source mismatch`);
  assert.equal(evidence.fixtureClassSha256,
    sha256(readInside(classesDirectory, 'com/soklet/conformance/legacy/McpLegacyConformanceFixture.class')), 'Legacy fixture class mismatch');
  assert.deepEqual(evidence.fixtureClassesIdentity, sourceTreeIdentity(classesDirectory, []), 'Legacy fixture class tree mismatch');
  for (const [index, selected] of runs.entries()) {
    const actual = evidence.scenarios[index];
    exactKeys(actual, ['profile', 'revision', 'scenario', 'checkCount', 'checks', 'wireMessagesValidated',
      'progressNotificationsObserved', 'passed', 'checksJson', 'checksJsonSha256', 'commandArguments'], 'Legacy scenario');
    const prefix = `${selected.profile}/${selected.revision}/${String(index + 1).padStart(3, '0')}-${selected.scenario}`;
    assert.ok(typeof actual.checksJson === 'string' && !isAbsolute(actual.checksJson)
      && actual.checksJson.startsWith(`${prefix}/official-results/`)
      && !actual.checksJson.includes('\\')
      && actual.checksJson.split('/').every(part => part && part !== '.' && part !== '..'), 'Legacy raw checks path mismatch');
    const bytes = readInside(root, actual.checksJson);
    assert.equal(sha256(bytes), actual.checksJsonSha256, 'Legacy raw checks digest mismatch');
    const observed = assertRawChecks(selected.revision, selected.scenario, JSON.parse(bytes), selected.profile);
    for (const [key, value] of Object.entries(observed)) assert.deepEqual(actual[key], value, `Legacy ${key} result mismatch`);
    assert.equal(actual.passed, true);
    const args = actual.commandArguments;
    assert.equal(args.length, 10); assert.deepEqual([args[0], args[1], ...args.slice(3, 8), args[9]],
      ['server', '--url', '--scenario', selected.scenario, '--spec-version', selected.revision, '-o', '--verbose']);
    const url = new URL(args[2]); assert.equal(url.protocol, 'http:'); assert.equal(url.hostname, '127.0.0.1');
    assert.ok(url.port); assert.equal(url.pathname, '/mcp'); assert.equal(url.username + url.password + url.search + url.hash, '');
    assert.equal(readInside(root, `${prefix}/fixture.cleanup.txt`).toString(), 'forced=false\nexitCode=0\nsignal=null\n', 'Legacy fixture shutdown failed');
    assert.equal(readInside(root, `${prefix}/fixture.stderr.log`).length, 0);
    assert.equal(readInside(root, `${prefix}/official.stderr.log`).length, 0);
  }
  exactKeys(evidence.runtimeSupplement, ['passed', 'results'], 'Legacy runtime supplement');
  assert.equal(evidence.runtimeSupplement.passed, true);
  assert.equal(evidence.runtimeSupplement.results.length, 2, 'Both legacy runtime revisions are required');
  for (const [index, result] of evidence.runtimeSupplement.results.entries()) {
    exactKeys(result, ['revision', 'passed', 'checks'], 'Legacy runtime result');
    assert.equal(result.revision, revisions[index]); assert.equal(result.passed, true);
    assert.deepEqual(result.checks, runtimeSupplementChecks, 'Legacy runtime contract checks must match exactly');
  }
  assert.equal(readInside(root, 'runtime/fixture.cleanup.txt').toString(), 'clean=true\n', 'Legacy runtime shutdown failed');
  assert.equal(readInside(root, 'runtime/fixture.stderr.log').length, 0);
  return evidence;
}
