import assert from 'node:assert/strict';
import test from 'node:test';
import { adjudicateHost, adjudicateUpstreamAppsTrace, completedHostChecks, fixtureControl, parseHostArguments, sourceInputs } from './run-host.mjs';

test('host argument parser requires all eight unique explicit inputs', () => {
  const args = ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd',
    '--dependencies', 'e', '--inspector-build', 'h', '--browser', 'f', '--work-dir', 'g'];
  assert.equal(Object.keys(parseHostArguments(args)).length, 8);
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
  return {observationWindowCompleted: true, traceVerdict: 'PASSED', ui: Object.fromEntries(['selectedAppViaDom', 'hostAppReady',
    'catalogRendered', 'textOnlyHostileLabel', 'serverSelectedLocaleRendered', 'currencyAndUtcDateRendered',
    'opaqueSrcdocSandboxObserved', 'refreshClickedViaDom', 'pendingClearedPriorData', 'refreshRendered'].map(key => [key, true])),
  browser: {pageNetworkPolicySatisfied: true, noBrowserExceptions: true},
  authentication: {invalidCredentialRejected: true, validCredentialAccepted: true},
  fixtureShutdown: 'CLEAN', hostShutdown: 'CLEAN', browserShutdown: 'CLEAN', readOnlyMemoryStore: true,
  hostAuthRequired: true, hostOriginRestricted: true, configUnchanged: true, inputsUnchanged: true,
  sourceIdentityVerified: true,
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
    {traceVerdict: 'FAILED'}, {observationWindowCompleted: false}, {failure: 'APPS_HOST_INTERRUPTED'},
    {integrityFailure: 'APPS_HOST_INPUT_DRIFT'}, {browserFailure: 'APPS_BROWSER_EXCEPTION'},
    {cleanupFailure: 'APPS_HOST_CLEANUP'}, {traceFailure: 'APPS_ORIGIN'}])
    assert.equal(adjudicateHost({...good, ...change}, denied), 'FAILED');
});


test('upstream source profile accepts only bounded fully validated policy denials', () => {
  const flags = ['authorized', 'authorizationForwarded', 'requestEnvelopeValid', 'protocolMetadataMatches',
    'protocolHeaderMatches', 'methodHeaderMatches', 'nameHeaderMatches', 'perRequestCapabilitiesPresent',
    'appsMimeMatches', 'skillsAbsent', 'noSessionState', 'requestSelectionValid', 'responseJson',
    'responseNoStore', 'responseCorrelated', 'responseEnvelopeValid', 'resultMatchesFixture'];
  const positives = ['server/discover', 'resources/list', 'tools/list', 'resources/templates/list',
    'tools/list', 'show_catalog', 'resources/read', 'refresh_catalog'];
  const rows = denials => [positives[0], 'subscriptions/listen', ...positives.slice(1),
    ...Array(denials - 1).fill('subscriptions/listen')].map((operation, index) => {
      const tool = ['show_catalog', 'refresh_catalog'].includes(operation);
      const denied = operation === 'subscriptions/listen';
      return {surface: 'apps-web', sequence: index + 1, method: tool ? 'tools/call' : operation,
        tool: tool ? operation : 'NONE', ...Object.fromEntries(flags.map(flag => [flag, true])),
        responseStatus: denied ? 403 : 200, subscriptionDenied: denied,
        requestBytes: 250, responseBytes: 400};
    });
  for (let denials = 1; denials <= 8; denials++) {
    const trace = rows(denials);
    const original = structuredClone(trace);
    assert.equal(adjudicateUpstreamAppsTrace(trace), 'PASSED');
    assert.deepEqual(trace, original);
  }
  assert.equal(adjudicateUpstreamAppsTrace(rows(9)), 'FAILED');
  for (const change of [{responseStatus: 200}, {responseStatus: 401}, {responseEnvelopeValid: false},
    {resultMatchesFixture: false}, {sequence: 1}, {privateBody: 'must-not-be-retained'}]) {
    const trace = rows(4);
    Object.assign(trace.at(-1), change);
    assert.equal(adjudicateUpstreamAppsTrace(trace), 'FAILED');
  }
  const missingPositive = rows(4).filter(row => row.tool !== 'show_catalog')
    .map((row, index) => ({...row, sequence: index + 1}));
  assert.equal(adjudicateUpstreamAppsTrace(missingPositive), 'FAILED');
});

test('input identity covers both the historical and bounded trace validators', () => {
  const inputs = sourceInputs();
  for (const path of ['verification/interoperability/apps/host-trace.mjs',
    'verification/interoperability/apps-patched-host/trace.mjs']) {
    const matches = inputs.filter(row => row.path === path);
    assert.equal(matches.length, 1);
    assert.match(matches[0].sha256, /^[a-f0-9]{64}$/);
  }
});
