import assert from 'node:assert/strict';
import test from 'node:test';
import {adjudicateHost, canaryNetworkMatches, completedHostChecks, fixtureControl, parseHostArguments,
  PROFILE, SUCCESS, ALLOWLIST_PROFILE, ALLOWLIST_SUCCESS, validateExperimentPins} from './run.mjs';
import {adjudicateCspEvidence, adjudicateAllowlistEvidence} from './browser-probe.mjs';
import {adjudicateCanary, CANARY_FAILURES} from './canary.mjs';

function goodCsp() {
  return {status: 'PASSED', policyVerified: true, appContextVerified: true, sandboxVerified: true,
    controlsBeforePassed: true, appDenialsPassed: true, controlsAfterPassed: true, settled: true,
    rows: ['CONTROL_BEFORE', 'CONTROL_BEFORE', 'APP', 'APP', 'CONTROL_AFTER', 'CONTROL_AFTER']
      .map((phase, index) => {
        const negative = phase === 'APP';
        return {phase, operation: index % 2 === 0 ? 'connect' : 'image', context: negative ? 'APP' : 'MAIN',
          attempted: true, completed: true, contextMatches: true, settled: true,
          trusted: true, enforced: true, directiveMatches: true, targetMatches: true,
          documentMatches: true, policyMatches: true, timedOut: false, violationBoundExceeded: false,
          succeeded: !negative, failed: negative, responseMatches: !negative,
          violationCount: negative ? 1 : 0, matchingViolationCount: negative ? 1 : 0,
          unexpectedViolationCount: 0};
      })};
}

function goodCanary() {
  return {phase: 'SEALED', closed: true, closeClean: true, failure: null,
    requestCount: 4, connectionCount: 2,
    phases: {IDLE: {connect: 0, image: 0, rejected: 0},
      CONTROL_BEFORE: {connect: 1, image: 1, rejected: 0},
      APP: {connect: 0, image: 0, rejected: 0},
      CONTROL_AFTER: {connect: 1, image: 1, rejected: 0},
      SEALED: {connect: 0, image: 0, rejected: 0}},
    requests: ['CONTROL_BEFORE', 'CONTROL_BEFORE', 'CONTROL_AFTER', 'CONTROL_AFTER']
      .map((phase, index) => ({sequence: index + 1, phase,
        operation: index % 2 === 0 ? 'CONNECT' : 'IMAGE', method: 'GET', accepted: true, code: 'OK'}))};
}

function complete() {
  return {profile: PROFILE, experimental: true, patchIdentityVerified: true, fullHostQualification: false,
    releaseCandidateEvidence: false, observationWindowCompleted: true, interrupted: false,
    csp: goodCsp(), canary: goodCanary(), traceVerdict: 'PASSED',
    ui: Object.fromEntries(['selectedAppViaDom', 'hostAppReady', 'catalogRendered', 'textOnlyHostileLabel',
      'serverSelectedLocaleRendered', 'currencyAndUtcDateRendered', 'opaqueSrcdocSandboxObserved',
      'refreshClickedViaDom', 'pendingClearedPriorData', 'refreshRendered'].map(key => [key, true])),
    browser: {pageNetworkPolicySatisfied: true, noBrowserExceptions: true, allObservedSessionsCacheDisabled: true,
      canaryNetwork: {phase: 'CONTROL_AFTER', continuedByOperation: [1, 1, 0, 0, 1, 1],
        mainContinued: 4, appContinued: 0, blocked: 0}},
    authentication: {invalidCredentialRejected: true, validCredentialAccepted: true},
    fixtureShutdown: 'CLEAN', hostShutdown: 'CLEAN', browserShutdown: 'CLEAN', readOnlyMemoryStore: true,
    hostAuthRequired: true, hostOriginRestricted: true, configUnchanged: true, inputsUnchanged: true,
    disconnected: true, privateStateRemoved: true,
    cleanup: {cdp: true, browser: true, host: true, proxy: true, fixture: true, canary: true}};
}

function completeAllowlist() {
  const receipt = complete();
  receipt.profile = ALLOWLIST_PROFILE;
  receipt.csp = {...goodCsp(), rows: [
    ['CONTROL_BEFORE', 'connect', false], ['CONTROL_BEFORE', 'image', false],
    ['APP', 'connect', false], ['APP', 'image', false],
    ['APP', 'connect', true], ['APP', 'image', true],
    ['CONTROL_AFTER', 'connect', false], ['CONTROL_AFTER', 'image', false],
  ].map(([phase, operation, negative]) => ({
    ...goodCsp().rows[negative ? 2 : 0], phase, operation,
    context: phase === 'APP' ? 'APP' : 'MAIN',
    targetKind: negative ? 'UNDECLARED' : 'DECLARED',
    succeeded: !negative, failed: negative, responseMatches: !negative,
    violationCount: Number(negative), matchingViolationCount: Number(negative),
  }))};
  delete receipt.csp.appDenialsPassed;
  receipt.csp.appChecksPassed = true;
  receipt.canary.requestCount = 6;
  receipt.canary.phases.APP = {connect: 1, image: 1, rejected: 0};
  receipt.canary.requests.splice(2, 0,
    {sequence: 3, phase: 'APP', operation: 'CONNECT', method: 'GET', accepted: true, code: 'OK'},
    {sequence: 4, phase: 'APP', operation: 'IMAGE', method: 'GET', accepted: true, code: 'OK'});
  receipt.canary.requests[4].sequence = 5;
  receipt.canary.requests[5].sequence = 6;
  receipt.browser.canaryNetwork.continuedByOperation = [1, 1, 1, 1, 1, 1];
  receipt.browser.canaryNetwork.appContinued = 2;
  return receipt;
}

function paths(value, prefix = []) {
  return Object.keys(value).flatMap(key => {
    const path = [...prefix, key], child = value[key];
    return [path, ...(child !== null && typeof child === 'object' ? paths(child, path) : [])];
  });
}

function parentOf(value, path) {
  return path.slice(0, -1).reduce((parent, key) => parent[key], value);
}

function rejected(receipt, label) {
  assert.equal(completedHostChecks(receipt), false, label);
  assert.equal(adjudicateHost(receipt, []), 'FAILED', label);
}

const argumentsFixture = () => ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd',
  '--dependencies', 'e', '--browser', 'f', '--work-dir', 'g', '--original-dependencies', 'h'];

test('CSP profile requires all eight unique explicit inputs and does not reuse a prior profile', () => {
  assert.equal(PROFILE, 'soklet.inspector.experimental-apps-csp-denial.v1');
  assert.equal(SUCCESS, 'EXPERIMENTAL_APPS_CSP_DENIAL_PASSED');
  assert.equal(Object.keys(parseHostArguments(argumentsFixture())).length, 8);
  for (const args of [argumentsFixture().slice(0, -2), [...argumentsFixture(), '--unknown', 'x'],
    [...argumentsFixture().slice(0, -2), '--shell', 'again'], [...argumentsFixture().slice(0, -1), ''],
    Array(16)]) assert.throws(() => parseHostArguments(args), /APPS_HOST_ARGUMENTS/);
  for (let index = 0; index < argumentsFixture().length; ++index) {
    const args = argumentsFixture(); delete args[index];
    assert.throws(() => parseHostArguments(args), /APPS_HOST_ARGUMENTS/, `missing argument ${index}`);
  }
});

test('allowlist profile requires declared App network success and undeclared CSP denials', () => {
  assert.equal(parseHostArguments([...argumentsFixture(), '--profile', 'allowlist'])['--profile'], 'allowlist');
  assert.throws(() => parseHostArguments([...argumentsFixture(), '--profile', 'unknown']), /APPS_HOST_ARGUMENTS/);
  const receipt = completeAllowlist();
  assert.equal(adjudicateAllowlistEvidence(receipt.csp), 'PASSED');
  assert.equal(adjudicateCanary(receipt.canary, {allowApp: true}), 'PASSED');
  assert.equal(canaryNetworkMatches(receipt.browser.canaryNetwork, {allowlist: true}), true);
  assert.equal(adjudicateHost(receipt, []), ALLOWLIST_SUCCESS);
  for (const index of [2, 3]) {
    const altered = completeAllowlist(); altered.csp.rows[index].succeeded = false;
    rejected(altered, 'declared App request must succeed');
  }
  for (const index of [4, 5]) {
    const altered = completeAllowlist(); altered.csp.rows[index].violationCount = 0;
    rejected(altered, 'undeclared App request needs enforced CSP evidence');
  }
  const noContact = completeAllowlist(); noContact.canary.phases.APP.connect = 0;
  rejected(noContact, 'declared App request must reach the canary');
  const blocked = completeAllowlist(); blocked.browser.canaryNetwork.blocked = 1;
  rejected(blocked, 'the harness must not impersonate CSP');
});

test('fixture control accepts only the exact loopback listener and clean stop envelopes', () => {
  const ready = {format: 1, event: 'ready', host: '127.0.0.1', port: 1234, path: '/apps'};
  assert.deepEqual(fixtureControl(JSON.stringify(ready), 'ready'), ready);
  for (const change of [{port: 0}, {port: 65536}, {port: 1234.5}, {port: '1234'}, {host: 'localhost'},
    {path: '/mcp'}, {token: 'private'}, {format: 2}, {event: 'stopped'}])
    assert.throws(() => fixtureControl(JSON.stringify({...ready, ...change}), 'ready'), /APPS_HOST_CONTROL/);
  for (const key of Object.keys(ready)) {
    const incomplete = {...ready}; delete incomplete[key];
    assert.throws(() => fixtureControl(JSON.stringify(incomplete), 'ready'), /APPS_HOST_CONTROL/);
  }
  const stopped = {format: 1, event: 'stopped', clean: true};
  assert.deepEqual(fixtureControl(JSON.stringify(stopped), 'stopped'), stopped);
  for (const change of [{clean: false}, {clean: 1}, {extra: true}, {format: 2}, {event: 'ready'}])
    assert.throws(() => fixtureControl(JSON.stringify({...stopped, ...change}), 'stopped'), /APPS_HOST_CONTROL/);
  for (const value of ['null', '[]', 'not-json', '{}'])
    assert.throws(() => fixtureControl(value, 'ready'), /APPS_HOST_CONTROL/);
});

test('candidate, shell and auth-patched dependencies remain pinned to the prior experiment', () => {
  const provenance = {patchedTree: {files: 9391,
    sha256: '6546d769cd9fd869b7608c774b9dcfc39b3050d851c57ad83b439cdcbb84ebcb'},
  patchedFileSha256: '405da5e71b887403bb53ff2e3984cec631a1138f50662ad199dfb8e536dcd47a',
  experimental: true, releasedHostQualification: false};
  const candidate = {jarSha256: 'e59c107e33187209e504b6e37141d410c0bffedf26e5dd14e2abf28c2d62227f'};
  const shell = {sha256: '3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60'};
  assert.doesNotThrow(() => validateExperimentPins(provenance, candidate, shell));
  for (const [p, c, s] of [[null, candidate, shell], [{}, candidate, shell],
    [{...provenance, experimental: false}, candidate, shell],
    [{...provenance, releasedHostQualification: true}, candidate, shell],
    [{...provenance, patchedFileSha256: '0'.repeat(64)}, candidate, shell],
    [{...provenance, patchedTree: {...provenance.patchedTree, files: 9392}}, candidate, shell],
    [{...provenance, patchedTree: {...provenance.patchedTree, sha256: '0'.repeat(64)}}, candidate, shell],
    [provenance, null, shell], [provenance, {jarSha256: '0'.repeat(64)}, shell],
    [provenance, candidate, null], [provenance, candidate, {sha256: '0'.repeat(64)}]])
    assert.throws(() => validateExperimentPins(p, c, s), /APPS_HOST_EXPERIMENT_PIN/);
});

test('canary fixture uses the real strict adjudicator and requires clean sealed evidence', () => {
  assert.equal(adjudicateCanary(goodCanary()), 'PASSED');
  for (const change of [{closed: false}, {closeClean: false}, {phase: 'CONTROL_AFTER'},
    {requestCount: 0}, {requestCount: 3}, {requestCount: 5}, {connectionCount: 0},
    {connectionCount: 17}, {connectionCount: 1.5}, {connectionCount: '2'},
    {requests: []}, {requests: Array(4)}, {rawUrl: 'private'}])
    assert.equal(adjudicateCanary({...goodCanary(), ...change}), 'FAILED');
  for (const failure of CANARY_FAILURES)
    assert.equal(adjudicateCanary({...goodCanary(), failure}), 'FAILED', failure);
});

test('only complete UI, CSP, independent canary, trace and supervised cleanup evidence passes', () => {
  const receipt = complete();
  assert.equal(adjudicateCspEvidence(receipt.csp), 'PASSED');
  assert.equal(adjudicateCanary(receipt.canary), 'PASSED');
  assert.equal(completedHostChecks(receipt), true);
  assert.equal(adjudicateHost(receipt, []), SUCCESS);
  assert.equal(receipt.fullHostQualification, false);
  assert.equal(receipt.releaseCandidateEvidence, false);
  for (const value of [null, undefined, false, 0, '', [], {}]) rejected(value, 'malformed receipt');
});

test('every mandatory receipt field and every nested CSP/canary field is independently required', () => {
  for (const path of paths(complete())) {
    const receipt = complete();
    delete parentOf(receipt, path)[path.at(-1)];
    rejected(receipt, `missing ${path.join('.')}`);
  }
});

test('mandatory facts require exact booleans and typed values, not truthy substitutions', () => {
  for (const path of paths(complete())) {
    const source = parentOf(complete(), path)[path.at(-1)];
    if (source !== null && typeof source === 'object') continue;
    const changes = typeof source === 'boolean' ? [!source, String(source), Number(source), null]
      : typeof source === 'number' ? [String(source), NaN, Infinity, null]
      : source === null ? [false, '', 'private'] : [null, false, 1, 'UNKNOWN'];
    for (const change of changes) {
      const receipt = complete(); parentOf(receipt, path)[path.at(-1)] = change;
      rejected(receipt, `invalid ${path.join('.')}`);
    }
  }
});

test('CSP and canary row arrays reject missing, extra, fully sparse and partially sparse observations', () => {
  for (const [key, field] of [['csp', 'rows'], ['canary', 'requests']]) {
    const expected = complete()[key][field];
    for (const rows of [null, {}, [], expected.slice(1), [...expected, expected[0]], Array(expected.length)]) {
      const receipt = complete(); receipt[key][field] = rows;
      rejected(receipt, `${key}.${field} invalid cardinality or sparse array`);
    }
    for (let index = 0; index < expected.length; ++index) {
      const receipt = complete(); delete receipt[key][field][index];
      rejected(receipt, `${key}.${field} missing row ${index}`);
    }
  }
});

test('network permit gate requires all four main controls, permits CSP pre-interception, and never permits harness denial', () => {
  for (const connect of [0, 1]) for (const image of [0, 1]) {
    const receipt = complete();
    Object.assign(receipt.browser.canaryNetwork, {
      continuedByOperation: [1, 1, connect, image, 1, 1], appContinued: connect + image});
    assert.equal(canaryNetworkMatches(receipt.browser.canaryNetwork), true);
    assert.equal(adjudicateHost(receipt, []), SUCCESS);
  }
  for (const change of [{phase: 'APP'}, {phase: 'SEALED'}, {blocked: 1}, {mainContinued: 3},
    {mainContinued: 5}, {appContinued: 1}, {rawUrl: 'private'}, {continuedByOperation: Array(6)},
    {continuedByOperation: []}, {continuedByOperation: [1, 1, 0, 0, 1]},
    {continuedByOperation: [1, 1, 0, 0, 1, 1, 0]}]) {
    const receipt = complete(); Object.assign(receipt.browser.canaryNetwork, change);
    assert.equal(canaryNetworkMatches(receipt.browser.canaryNetwork), false);
    rejected(receipt, `canary network ${Object.keys(change).join(',')}`);
  }
  for (let index = 0; index < 6; ++index) {
    const sparse = complete(); delete sparse.browser.canaryNetwork.continuedByOperation[index];
    assert.equal(canaryNetworkMatches(sparse.browser.canaryNetwork), false);
    rejected(sparse, `canary network missing index ${index}`);
    for (const count of index === 2 || index === 3 ? [-1, 2] : [0, 2]) {
      const receipt = complete(); receipt.browser.canaryNetwork.continuedByOperation[index] = count;
      assert.equal(canaryNetworkMatches(receipt.browser.canaryNetwork), false);
      rejected(receipt, `canary network index ${index} count ${count}`);
    }
  }
  for (const value of [null, undefined, false, 0, '', [], {}])
    assert.equal(canaryNetworkMatches(value), false);
});

test('a failed App operation without its matching trusted enforced CSP event never proves denial', () => {
  for (const index of [2, 3]) {
    for (const change of [{violationCount: 0, matchingViolationCount: 0},
      {violationCount: 1, matchingViolationCount: 0, unexpectedViolationCount: 1},
      {violationCount: 2, matchingViolationCount: 2}, {trusted: false}, {enforced: false},
      {directiveMatches: false}, {targetMatches: false}, {documentMatches: false},
      {policyMatches: false}, {timedOut: true}, {settled: false}]) {
      const receipt = complete(); Object.assign(receipt.csp.rows[index], change);
      assert.equal(receipt.csp.rows[index].failed, true);
      assert.equal(adjudicateCspEvidence(receipt.csp), 'FAILED');
      rejected(receipt, `App ${receipt.csp.rows[index].operation}: ${Object.keys(change).join(',')}`);
    }
  }
});

test('successful before/after controls and exact MAIN/APP execution order are independently required', () => {
  for (const index of [0, 1, 4, 5]) {
    for (const change of [{succeeded: false}, {responseMatches: false}, {context: 'APP'},
      {violationCount: 1}, {failed: true}, {completed: false}]) {
      const receipt = complete(); Object.assign(receipt.csp.rows[index], change);
      rejected(receipt, `control row ${index}`);
    }
  }
  for (let index = 0; index < 5; ++index) {
    const receipt = complete();
    [receipt.csp.rows[index], receipt.csp.rows[index + 1]] = [receipt.csp.rows[index + 1], receipt.csp.rows[index]];
    rejected(receipt, `reordered row ${index}`);
  }
});

test('App or late canary contact fails even when all page CSP facts claim success', () => {
  for (const phase of ['IDLE', 'APP', 'SEALED']) {
    for (const counter of ['connect', 'image', 'rejected']) {
      const receipt = complete(); receipt.canary.phases[phase][counter] = 1;
      assert.equal(adjudicateCspEvidence(receipt.csp), 'PASSED');
      assert.equal(adjudicateCanary(receipt.canary), 'FAILED');
      rejected(receipt, `late/forbidden ${phase}.${counter}`);
    }
  }
  for (const phase of ['CONTROL_BEFORE', 'CONTROL_AFTER']) {
    for (const counter of ['connect', 'image']) {
      for (const count of [0, 2]) {
        const receipt = complete(); receipt.canary.phases[phase][counter] = count;
        rejected(receipt, `wrong control count ${phase}.${counter}`);
      }
    }
  }
  for (const failure of CANARY_FAILURES) {
    const receipt = complete(); receipt.canary.failure = failure;
    rejected(receipt, `canary ${failure}`);
  }
});

test('CSP and canary structural receipts reject additional raw fields rather than retaining them', () => {
  for (const [key, field] of [['csp', 'rows'], ['canary', 'requests']]) {
    for (const extra of ['url', 'originalPolicy', 'documentURI', 'headers', 'error', 'body']) {
      const atRoot = complete(); atRoot[key][extra] = 'private-canary';
      rejected(atRoot, `extra ${key}.${extra}`);
      for (let index = 0; index < complete()[key][field].length; ++index) {
        const atRow = complete(); atRow[key][field][index][extra] = 'private-canary';
        rejected(atRow, `extra ${key}.${field}.${index}.${extra}`);
      }
    }
  }
});

test('late interruption, cleanup, input, browser, endpoint and transport failures override page success', () => {
  const changes = [{interrupted: true}, {inputsUnchanged: false}, {privateStateRemoved: false},
    {traceVerdict: 'FAILED'}, {observationWindowCompleted: false}, {browserShutdown: 'NOT_PROVEN'},
    {hostShutdown: 'NOT_PROVEN'}, {fixtureShutdown: 'NOT_PROVEN'}, {failure: 'APPS_HOST_INTERRUPTED'},
    {integrityFailure: 'APPS_HOST_INPUT_DRIFT'}, {browserFailure: 'APPS_CSP_NETWORK_REJECTED'},
    {cleanupFailure: 'APPS_HOST_CLEANUP'}, {canaryFailure: 'CANARY_APP_CONTACT'},
    {traceFailure: 'APPS_ORIGIN'}, {experimental: false}, {patchIdentityVerified: false},
    {fullHostQualification: true}, {releaseCandidateEvidence: true}, {profile: 'soklet.inspector.apps-render-refresh.v1'}];
  for (const change of changes)
    assert.equal(adjudicateHost({...complete(), ...change}, []), 'FAILED', Object.keys(change)[0]);
  for (const key of Object.keys(complete().cleanup)) {
    const receipt = complete(); receipt.cleanup[key] = false;
    rejected(receipt, `cleanup.${key}`);
  }
  for (const rejections of [null, {}, Array(1), [{}], [{code: 'APPS_AUTH_REQUIRED'}],
    [{path: 'OAUTH_REGISTRATION_ROOT', method: 'POST', code: 'APPS_POST_ONLY'}]])
    assert.equal(adjudicateHost(complete(), rejections), 'FAILED');
});
