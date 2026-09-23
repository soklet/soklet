import assert from 'node:assert/strict';
import test from 'node:test';
import { adjudicateHost, completedHostChecks, fixtureControl, parseHostArguments,
  PROFILE, SUCCESS, TRANSITION_PROFILE, TRANSITION_SUCCESS, REVOCATION_PROFILE,
  REVOCATION_SUCCESS, REVOCATION_HOST_BLOCKED, PERMISSIONS_PROFILE,
  PERMISSIONS_SUCCESS, PERMISSIONS_HOST_BLOCKED, expectedRevocationOAuthFallback,
  validateExperimentPins } from './run.mjs';

test('experimental host arguments require all eight unique explicit inputs', () => {
  const args = ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd',
    '--dependencies', 'e', '--browser', 'f', '--work-dir', 'g', '--original-dependencies', 'h'];
  assert.equal(Object.keys(parseHostArguments(args)).length, 8);
  assert.equal(parseHostArguments([...args, '--profile', 'transitions'])['--profile'], 'transitions');
  assert.equal(parseHostArguments([...args, '--profile', 'revocation'])['--profile'], 'revocation');
  assert.equal(parseHostArguments([...args, '--profile', 'permissions'])['--profile'], 'permissions');
  for (const invalid of [args.slice(0, -2), [...args, '--unknown', 'x'],
    [...args.slice(0, -2), '--shell', 'again'], [...args.slice(0, -1), ''],
    [...args, '--profile', 'other'], [...args, '--profile', 'transitions', '--profile', 'transitions']])
    assert.throws(() => parseHostArguments(invalid), /APPS_HOST_ARGUMENTS/);
});

test('control lines accept only exact loopback listener and clean stop shapes', () => {
  const ready = {format: 1, event: 'ready', host: '127.0.0.1', port: 1234, path: '/apps'};
  assert.deepEqual(fixtureControl(JSON.stringify(ready), 'ready'), ready);
  for (const change of [{port: 0}, {port: 65536}, {port: '1234'}, {host: 'localhost'},
    {path: '/mcp'}, {token: 'private'}, {format: 2}, {event: 'stopped'}])
    assert.throws(() => fixtureControl(JSON.stringify({...ready, ...change}), 'ready'), /APPS_HOST_CONTROL/);
  assert.equal(fixtureControl('{"format":1,"event":"stopped","clean":true}', 'stopped').clean, true);
  assert.equal(fixtureControl('{"format":1,"event":"caller","state":"beta"}', 'caller').state, 'beta');
  assert.equal(fixtureControl('{"format":1,"event":"caller","state":"denied"}', 'caller').state, 'denied');
  assert.equal(fixtureControl('{"format":1,"event":"caller","state":"revoked"}', 'caller').state, 'revoked');
  for (const line of ['{"format":1,"event":"caller","state":"alpha"}',
    '{"format":1,"event":"caller","state":"beta","token":"private"}'])
    assert.throws(() => fixtureControl(line, 'caller'));
  assert.throws(() => fixtureControl('{"format":1,"event":"stopped","clean":false}', 'stopped'));
  for (const value of ['null', '[]', 'not-json', '{}']) assert.throws(() => fixtureControl(value, 'ready'));
});

function complete() {
  return {profile:PROFILE, experimental:true, patchIdentityVerified:true, fullHostQualification:false,
  releaseCandidateEvidence:false, observationWindowCompleted:true,
  traceVerdict: 'PASSED', ui: Object.fromEntries(['selectedAppViaDom', 'hostAppReady',
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

test('any OAuth fallback or failure fails the patched experiment despite narrow rendering', () => {
  const denied = ['OAUTH_PROTECTED_RESOURCE_PATH', 'OAUTH_PROTECTED_RESOURCE_ROOT',
    'OAUTH_AUTHORIZATION_SERVER_ROOT', 'OPENID_CONFIGURATION_ROOT',
    'OPENID_CONFIGURATION_ROOT', 'OAUTH_REGISTRATION_ROOT'].map((path, i) => ({sequence: i + 1,
      path, method: i === 5 ? 'POST' : 'GET', code: i === 5 ? 'APPS_PATH' : 'APPS_POST_ONLY', originAbsent: true}));
  const good = complete();
  assert.equal(adjudicateHost(good, []), SUCCESS);
  assert.equal(adjudicateHost(good, denied), 'FAILED');
  good.traceFailure = 'APPS_POST_ONLY';
  assert.equal(adjudicateHost(good, denied), 'FAILED');
  assert.equal(adjudicateHost(good, []), 'FAILED');
  for (const change of [{sequence: 0}, {path: 'OTHER'}, {method: 'DELETE'},
    {code: 'APPS_AUTH_REQUIRED'}, {originAbsent: false}])
    assert.equal(adjudicateHost(good, denied.map((row, i) => i === 5 ? {...row, ...change} : row)), 'FAILED');
  for (const rows of [denied.slice(1), [...denied, denied[5]], denied.toReversed(), null])
    assert.equal(adjudicateHost(good, rows), 'FAILED');
  for (const change of [{inputsUnchanged: false}, {privateStateRemoved: false},
    {traceVerdict: 'FAILED'}, {browserFailure: 'APPS_BROWSER_EXCEPTION'},
    {cleanupFailure: 'APPS_HOST_CLEANUP'}, {traceFailure: 'APPS_ORIGIN'},
    {failure:'APPS_HOST_PROBE_FAILED'}, {integrityFailure:'APPS_HOST_INPUT_DRIFT'},
    {fullHostQualification:true}, {releaseCandidateEvidence:true}, {patchIdentityVerified:false},
    {experimental:false}, {profile:'soklet.inspector.apps-render-refresh.v1'}, {observationWindowCompleted:false}]) {
    assert.equal(adjudicateHost({...good, ...change}, denied), 'FAILED');
    assert.equal(adjudicateHost({...complete(), ...change}, []), 'FAILED');
  }
});

test('transition PASS requires the initial trace, same-App beta and denial facts, and clean final state', () => {
  const good = {...complete(), profile: TRANSITION_PROFILE, initialTraceCount: 12,
    transitions: {betaRenderedInSameApp: true, previousTenantCleared: true,
      denialClearedView: true, deniedViewRequiresReopen: true}};
  assert.equal(completedHostChecks(good), true);
  assert.equal(adjudicateHost(good, []), TRANSITION_SUCCESS);
  for (const key of Object.keys(good.transitions))
    assert.equal(adjudicateHost({...good, transitions: {...good.transitions, [key]: false}}, []), 'FAILED');
  for (const change of [{initialTraceCount: undefined}, {initialTraceCount: '12'},
    {traceVerdict: 'FAILED'}, {browserFailure: 'APPS_BROWSER_EXCEPTION'}])
    assert.equal(adjudicateHost({...good, ...change}, []), 'FAILED');
  assert.equal(adjudicateHost(good, [{code: 'APPS_POST_ONLY'}]), 'FAILED');
});

test('open-App revocation distinguishes clean 401 handling from exact OAuth fallback', () => {
  const good = {...complete(), profile: REVOCATION_PROFILE, initialTraceCount: 12,
    revocation: {revokedRefreshClickedViaDom: true, revokedViewCleared: true,
      revokedViewRequiresReopen: true}};
  assert.equal(completedHostChecks(good), true);
  assert.equal(adjudicateHost(good, []), REVOCATION_SUCCESS);
  for (const key of Object.keys(good.revocation))
    assert.equal(adjudicateHost({...good, revocation: {...good.revocation, [key]: false}}, []), 'FAILED');
  const cycle = ['OAUTH_PROTECTED_RESOURCE_PATH', 'OAUTH_PROTECTED_RESOURCE_ROOT',
    'OAUTH_AUTHORIZATION_SERVER_ROOT', 'OPENID_CONFIGURATION_ROOT',
    'OPENID_CONFIGURATION_ROOT', 'OAUTH_REGISTRATION_ROOT'];
  const project = (path, index) => ({sequence: index + 1, path,
    method: path === 'OAUTH_REGISTRATION_ROOT' ? 'POST' : 'GET',
    code: path === 'OAUTH_REGISTRATION_ROOT' ? 'APPS_PATH' : 'APPS_POST_ONLY', originAbsent: true});
  const one = cycle.map(project);
  const two = [...cycle, ...cycle].map(project);
  assert.equal(expectedRevocationOAuthFallback(one), true);
  assert.equal(expectedRevocationOAuthFallback(two), true);
  assert.equal(adjudicateHost({...good, traceFailure: 'APPS_POST_ONLY'}, two), REVOCATION_HOST_BLOCKED);
  for (const invalid of [null, one.slice(1), [...one, one[0]],
    one.map((row, index) => index === 2 ? {...row, path: 'OTHER'} : row),
    one.map((row, index) => index === 2 ? {...row, originAbsent: false} : row),
    one.map((row, index) => index === 2 ? {...row, extra: true} : row)]) {
    assert.equal(expectedRevocationOAuthFallback(invalid), false);
    assert.equal(adjudicateHost({...good, traceFailure: 'APPS_POST_ONLY'}, invalid), 'FAILED');
  }
  assert.equal(adjudicateHost({...good, traceFailure: 'APPS_ORIGIN'}, two), 'FAILED');
});

test('permission profile distinguishes effective grant from exact outer-sandbox host block', () => {
  const permissions = {policyApiPresent: true, mainPolicyAllowsGeolocation: true,
    sandboxPolicyAllowsGeolocation: true, outerSandboxLocated: true,
    outerSandboxGrantsGeolocation: true, declaredGeolocationEffective: true,
    undeclaredCameraDenied: true, undeclaredMicrophoneDenied: true,
    undeclaredClipboardWriteDenied: true};
  const good = {...complete(), profile: PERMISSIONS_PROFILE, permissions};
  assert.equal(completedHostChecks(good), true);
  assert.equal(adjudicateHost(good, []), PERMISSIONS_SUCCESS);
  const blocked = {...good, permissions: {...permissions,
    sandboxPolicyAllowsGeolocation: false, outerSandboxGrantsGeolocation: false,
    declaredGeolocationEffective: false}};
  assert.equal(completedHostChecks(blocked), false);
  assert.equal(adjudicateHost(blocked, []), PERMISSIONS_HOST_BLOCKED);
  for (const change of [{mainPolicyAllowsGeolocation: false}, {outerSandboxLocated: false},
    {undeclaredCameraDenied: false}, {policyApiPresent: false}, {extra: true}]) {
    const altered = {...blocked, permissions: {...blocked.permissions, ...change}};
    assert.equal(adjudicateHost(altered, []), 'FAILED');
  }
  for (const change of [{outerSandboxGrantsGeolocation: true},
    {sandboxPolicyAllowsGeolocation: true}, {declaredGeolocationEffective: true}])
    assert.equal(adjudicateHost({...blocked, permissions: {...blocked.permissions, ...change}}, []), 'FAILED');
  assert.equal(adjudicateHost({...blocked, traceFailure: 'APPS_POST_ONLY'}, []), 'FAILED');
  assert.equal(adjudicateHost(blocked, [{code: 'APPS_POST_ONLY'}]), 'FAILED');
  assert.equal(adjudicateHost({...blocked, cleanup: {...blocked.cleanup, browser: false}}, []), 'FAILED');
});

test('current candidate, shell and auth-patched runtime are exact experiment identities',()=>{
  const provenance={patchedTree:{files:9391,sha256:'6546d769cd9fd869b7608c774b9dcfc39b3050d851c57ad83b439cdcbb84ebcb'},
    patchedFileSha256:'405da5e71b887403bb53ff2e3984cec631a1138f50662ad199dfb8e536dcd47a',
    experimental:true,releasedHostQualification:false};
  const candidate={jarSha256:'e59c107e33187209e504b6e37141d410c0bffedf26e5dd14e2abf28c2d62227f'};
  const shell={sha256:'3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60'};
  assert.doesNotThrow(()=>validateExperimentPins(provenance,candidate,shell));
  assert.throws(()=>validateExperimentPins(provenance,
    {jarSha256:'1782dcaa2270cb543c49abc80c942a2ff0f1ab72f9abb88a5d2556d200bd8d74'},
    shell),/APPS_HOST_EXPERIMENT_PIN/);
  for(const [p,c,s] of [[null,candidate,shell],[{...provenance,experimental:false},candidate,shell],
    [{...provenance,releasedHostQualification:true},candidate,shell],
    [{...provenance,patchedFileSha256:'0'.repeat(64)},candidate,shell],
    [{...provenance,patchedTree:{...provenance.patchedTree,files:9392}},candidate,shell],
    [{...provenance,patchedTree:{...provenance.patchedTree,sha256:'0'.repeat(64)}},candidate,shell],
    [provenance,{jarSha256:'0'.repeat(64)},shell],[provenance,candidate,{sha256:'0'.repeat(64)}]])
    assert.throws(()=>validateExperimentPins(p,c,s),/APPS_HOST_EXPERIMENT_PIN/);
});
