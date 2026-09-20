import assert from 'node:assert/strict';
import test from 'node:test';
import { adjudicateHost, completedHostChecks, fixtureControl, parseHostArguments, PROFILE, SUCCESS, validateExperimentPins } from './run.mjs';

test('experimental host arguments require all eight unique explicit inputs', () => {
  const args = ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd',
    '--dependencies', 'e', '--browser', 'f', '--work-dir', 'g', '--original-dependencies', 'h'];
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
  return {profile:PROFILE, experimental:true, patchIdentityVerified:true, fullHostQualification:false,
  releaseCandidateEvidence:false, observationWindowCompleted:true, appsAbsentThroughoutObservation:true, interrupted:false,
  directControls:['helper-off-before','helper-on','helper-off-after','ordinary-resource-off'].map((name,i)=>({
    name,appsAdvertised:i===1,status:i===0||i===2?400:200,responseMatches:true,requestBytes:200,responseBytes:200})),
  traceVerdict: 'PASSED', ui: Object.fromEntries(['connectedViaDom','toolsSelectedViaDom','ordinaryToolSelectedViaDom',
    'ordinaryToolCalledViaDom','ordinaryResultRendered','appsControlsAbsent','appOnlyHelperAbsent','noAppFrames'].map(key => [key, true])),
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
    {experimental:false}, {profile:'soklet.inspector.apps-render-refresh.v1'}, {observationWindowCompleted:false},
    {appsAbsentThroughoutObservation:false},{interrupted:true},{directControls:[]}]) {
    assert.equal(adjudicateHost({...good, ...change}, denied), 'FAILED');
    assert.equal(adjudicateHost({...complete(), ...change}, []), 'FAILED');
  }
});

test('candidate, shell and auth-patched runtime are exact prior-experiment identities',()=>{
  const provenance={patchedTree:{files:9391,sha256:'6546d769cd9fd869b7608c774b9dcfc39b3050d851c57ad83b439cdcbb84ebcb'},
    patchedFileSha256:'405da5e71b887403bb53ff2e3984cec631a1138f50662ad199dfb8e536dcd47a',
    experimental:true,releasedHostQualification:false};
  const candidate={jarSha256:'1782dcaa2270cb543c49abc80c942a2ff0f1ab72f9abb88a5d2556d200bd8d74'};
  const shell={sha256:'3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60'};
  assert.doesNotThrow(()=>validateExperimentPins(provenance,candidate,shell));
  for(const [p,c,s] of [[null,candidate,shell],[{...provenance,experimental:false},candidate,shell],
    [{...provenance,releasedHostQualification:true},candidate,shell],
    [{...provenance,patchedFileSha256:'0'.repeat(64)},candidate,shell],
    [{...provenance,patchedTree:{...provenance.patchedTree,files:9392}},candidate,shell],
    [{...provenance,patchedTree:{...provenance.patchedTree,sha256:'0'.repeat(64)}},candidate,shell],
    [provenance,{jarSha256:'0'.repeat(64)},shell],[provenance,candidate,{sha256:'0'.repeat(64)}]])
    assert.throws(()=>validateExperimentPins(p,c,s),/APPS_HOST_EXPERIMENT_PIN/);
});
