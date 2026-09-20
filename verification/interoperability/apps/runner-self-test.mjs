import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { contractSummary, parseArguments, regularFile, validateShellBuild } from './run.mjs';
import { dependencyPins } from './build-shell.mjs';

const valid = ['--candidate-jar', '/candidate.jar', '--candidate-pom', '/candidate.pom',
  '--java', '/jdk/bin/java', '--shell', '/catalog.html', '--work-dir', '/new-work'];

test('exact named inputs are required and ordering may vary', () => {
  assert.equal(parseArguments(valid)['--java'], '/jdk/bin/java');
  assert.equal(parseArguments([...valid.slice(2), ...valid.slice(0, 2)])['--shell'], '/catalog.html');
});

test('missing, unknown, duplicate, blank and excess arguments fail closed', () => {
  for (const args of [[], valid.slice(0, -1), [...valid, '--extra', 'x'],
    ['--unknown', 'x', ...valid.slice(2)], ['--java', 'x', ...valid.slice(2)],
    ['--candidate-jar', '', ...valid.slice(2)]])
    assert.throws(() => parseArguments(args), /APPS_ARGUMENTS/);
});

test('candidate paths must be regular existing files', () => {
  assert.throws(() => regularFile('/definitely-not-an-apps-candidate'), /APPS_INPUT_FILE/);
  assert.throws(() => regularFile('/private/tmp'), /APPS_INPUT_FILE/);
});

test('only the complete candidate contract set can pass', () => {
  const good = {status: 'PASS', cases: 12, requests: 34, scope: 'candidate-public-api-simulator'};
  assert.deepEqual(contractSummary(JSON.stringify(good), ''), good);
  for (const changed of [{...good, status: 'SKIP'}, {...good, cases: 11},
    {...good, requests: 0}, {...good, requests: 33}, {...good, scope: 'mock'},
    {...good, extra: 'unreviewed'}])
    assert.throws(() => contractSummary(JSON.stringify(changed), ''), /APPS_UNEXPECTED_CONTRACT_OUTPUT/);
  assert.throws(() => contractSummary(JSON.stringify(good), 'unexpected warning'), /APPS_UNEXPECTED_CONTRACT_OUTPUT/);
  assert.throws(() => contractSummary('PASS', ''), /APPS_UNEXPECTED_CONTRACT_OUTPUT/);
});

test('shell build receipt binds exact approved dependencies, source inputs and output bytes', () => {
  const bytes = Buffer.from('<html>fixture</html>');
  const expected = [{path: 'assets/catalog-entry.mjs', sha256: 'a'.repeat(64), bytes: 1}];
  const good = {schemaVersion: 1, kind: 'soklet-apps-shell-build', sdk: '2.0.0', bundler: '1.2.9',
    lockSha256: dependencyPins['package-lock.json'], packageSha256: dependencyPins['package.json'],
    hostQualification: false, consoleCallsRemoved: true, sourceRechecked: true, bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'), sourceInputs: expected};
  assert.equal(validateShellBuild(good, bytes, expected), good);
  for (const changed of [{...good, sdk: 'latest'}, {...good, lockSha256: 'x'},
    {...good, hostQualification: true}, {...good, consoleCallsRemoved: false}, {...good, sourceRechecked: false},
    {...good, bytes: 0}, {...good, sha256: 'x'}, {...good, sourceInputs: []},
    {...good, sourceInputs: [{...expected[0], path: '../unreviewed'}]},
    {...good, sourceInputs: [{...expected[0], sha256: 'b'.repeat(64)}]}])
    assert.throws(() => validateShellBuild(changed, bytes, expected), /APPS_SHELL_BUILD_MISMATCH/);
  assert.throws(() => validateShellBuild(good, Buffer.from('changed'), expected), /APPS_SHELL_BUILD_MISMATCH/);
});
