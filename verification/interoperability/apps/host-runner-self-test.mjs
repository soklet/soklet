import assert from 'node:assert/strict';
import test from 'node:test';
import { adjudicateHost, completedHostChecks, fixtureControl, parseHostArguments } from './run-host.mjs';

test('host argument parser requires all seven unique explicit inputs', () => {
  const args = ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd',
    '--dependencies', 'e', '--browser', 'f', '--work-dir', 'g'];
  assert.equal(Object.keys(parseHostArguments(args)).length, 7);
  for (const invalid of [args.slice(0, -2), [...args, '--unknown', 'x'],
    [...args.slice(0, -2), '--shell', 'again'], [...args.slice(0, -1), '']])
    assert.throws(() => parseHostArguments(invalid), /APPS_HOST_ARGUMENTS/);
});

test('control lines accept only exact loopback listener and clean stop shapes', () => {
  const ready = {format: 1, event: 'ready', host: '127.0.0.1', port: 1234, path: '/apps'};
  assert.deepEqual(fixtureControl(JSON.stringify(ready), 'ready'), ready);
  for (const change of [{port: 0}, {port: 65536}, {port: '1234'}, {host: 'localhost'},
    {path: '/mcp'}, {token: 'private'}, {format: 2}, {event: 'stopped'}])
    assert.throws(() => fixtureControl(JSON.stringify({...ready, ...change}), 'ready'), /APPS_HOST_CONTROL/);
  assert.equal(fixtureControl('{"format":1,"event":"stopped","clean":true}', 'stopped').clean, true);
  assert.throws(() => fixtureControl('{"format":1,"event":"stopped","clean":false}', 'stopped'));
  for (const value of ['null', '[]', 'not-json', '{}']) assert.throws(() => fixtureControl(value, 'ready'));
});

function complete() {
  return {traceVerdict: 'PASSED', ui: Object.fromEntries(['selectedAppViaDom', 'hostAppReady',
    'catalogRendered', 'textOnlyHostileLabel', 'serverSelectedLocaleRendered', 'currencyAndUtcDateRendered',
    'opaqueSrcdocSandboxObserved', 'refreshClickedViaDom', 'pendingClearedPriorData', 'refreshRendered'].map(key => [key, true])),
  browser: {pageNetworkPolicySatisfied: true, noBrowserExceptions: true},
  authentication: {invalidCredentialRejected: true, validCredentialAccepted: true},
  fixtureShutdown: 'CLEAN', hostShutdown: 'CLEAN', browserShutdown: 'CLEAN', readOnlyMemoryStore: true,
  hostAuthRequired: true, hostOriginRestricted: true, configUnchanged: true, inputsUnchanged: true,
  disconnected: true, privateStateRemoved: true, cleanup: {cdp: true, browser: true, host: true, proxy: true, fixture: true}};
}

test('PASS requires every observed UI, transport, identity, auth and cleanup fact', () => {
  assert.equal(completedHostChecks(complete()), true);
  for (const [key, value] of Object.entries(complete())) {
    const receipt = complete();
    delete receipt[key];
    assert.equal(completedHostChecks(receipt), false, key);
    if (value && typeof value === 'object') for (const field of Object.keys(value)) {
      const nested = complete();
      nested[key][field] = false;
      assert.equal(completedHostChecks(nested), false, `${key}.${field}`);
    }
  }
});

test('exact refused OAuth fallback stays BLOCKED despite successful narrow rendering', () => {
  const denied = ['OAUTH_PROTECTED_RESOURCE_PATH', 'OAUTH_PROTECTED_RESOURCE_ROOT',
    'OAUTH_AUTHORIZATION_SERVER_ROOT', 'OPENID_CONFIGURATION_ROOT',
    'OPENID_CONFIGURATION_ROOT', 'OAUTH_REGISTRATION_ROOT'].map((path, i) => ({sequence: i + 1,
      path, method: i === 5 ? 'POST' : 'GET', code: i === 5 ? 'APPS_PATH' : 'APPS_POST_ONLY', originAbsent: true}));
  const good = complete();
  assert.equal(adjudicateHost(good, []), 'PASSED');
  assert.equal(adjudicateHost(good, denied), 'FAILED');
  good.traceFailure = 'APPS_POST_ONLY';
  assert.equal(adjudicateHost(good, denied), 'BLOCKED_HOST_AUTH_FALLBACK');
  assert.equal(adjudicateHost(good, []), 'FAILED');
  for (const change of [{sequence: 0}, {path: 'OTHER'}, {method: 'DELETE'},
    {code: 'APPS_AUTH_REQUIRED'}, {originAbsent: false}])
    assert.equal(adjudicateHost(good, denied.map((row, i) => i === 5 ? {...row, ...change} : row)), 'FAILED');
  for (const rows of [denied.slice(1), [...denied, denied[5]], denied.toReversed(), null])
    assert.equal(adjudicateHost(good, rows), 'FAILED');
  for (const change of [{inputsUnchanged: false}, {privateStateRemoved: false},
    {traceVerdict: 'FAILED'}, {browserFailure: 'APPS_BROWSER_EXCEPTION'},
    {cleanupFailure: 'APPS_HOST_CLEANUP'}, {traceFailure: 'APPS_ORIGIN'}])
    assert.equal(adjudicateHost({...good, ...change}, denied), 'FAILED');
});
