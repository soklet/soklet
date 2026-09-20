import assert from 'node:assert/strict';
import {test} from 'node:test';
import {MIME, PROTOCOL, UI} from '../apps/host-trace.mjs';
import {BATCH_COUNT, REQUEST_COUNT, MIME_FAILURES} from './matrix.mjs';
import {PROFILE, SUCCESS, adjudicateReceipt, boundedJson, collectMatrix, completedChecks,
  failureCode, fixtureAuthentication, fixtureControl, parseArguments, validatePins} from './run.mjs';

const candidate = {jarSha256: '1782dcaa2270cb543c49abc80c942a2ff0f1ab72f9abb88a5d2556d200bd8d74'};
const shell = {sha256: '3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60'};

test('five explicit unique arguments exclude browser, host and dependency inputs', () => {
  const args = ['--candidate-jar', 'a', '--candidate-pom', 'b', '--java', 'c', '--shell', 'd', '--work-dir', 'e'];
  assert.equal(Object.keys(parseArguments(args)).length, 5);
  for (const invalid of [null, [], args.slice(0, -2), [...args, '--browser', 'Chrome'],
    [...args.slice(0, -2), '--dependencies', 'x'], [...args.slice(0, -2), '--shell', 'again'],
    [...args.slice(0, -1), ''], [...args.slice(0, -1), true]])
    assert.throws(() => parseArguments(invalid), /APPS_MIME_ARGUMENTS/);
});

test('candidate and shell pins are mandatory and cannot be substituted', () => {
  assert.doesNotThrow(() => validatePins(candidate, shell));
  for (const [c, s] of [[null, shell], [candidate, null], [{jarSha256: '0'.repeat(64)}, shell],
    [candidate, {sha256: '0'.repeat(64)}], [{}, shell], [candidate, {}]])
    assert.throws(() => validatePins(c, s), /APPS_MIME_PIN/);
});

test('fixture control frames accept exact loopback readiness and clean stop without arbitrary fields', () => {
  const ready = {format: 1, event: 'ready', host: '127.0.0.1', port: 12345, path: '/apps'};
  assert.deepEqual(fixtureControl(JSON.stringify(ready), 'ready'), ready);
  assert.equal(fixtureControl('{"format":1,"event":"stopped","clean":true}', 'stopped').clean, true);
  for (const change of [{port: 0}, {port: 65536}, {port: '12345'}, {host: 'localhost'}, {path: '/mcp'},
    {token: 'secret'}, {format: 2}, {event: 'failed'}])
    assert.throws(() => fixtureControl(JSON.stringify({...ready, ...change}), 'ready'), /APPS_MIME_CONTROL/);
  for (const value of ['null', '[]', '{}', 'not-json', '{"format":1,"event":"stopped","clean":false}'])
    assert.throws(() => fixtureControl(value, 'stopped'), /APPS_MIME_CONTROL/);
});

function complete() {
  return {profile: PROFILE, scope: 'candidate-direct-http', experimental: true, hostQualification: false,
    browserExercised: false, releaseCandidateEvidence: false, inputPinsVerified: true, publicApiOnly: true,
    embeddedPomMatches: true, inputsUnchanged: true, privateStateRemoved: true, interrupted: false,
    matrixVerdict: 'PASSED', matrixRequestCount: REQUEST_COUNT, cleanup: {processes: true},
    fixtureRuns: Array.from({length: BATCH_COUNT}, (_, batchIndex) => ({batchIndex, completed: true,
      fixtureShutdown: 'CLEAN', cleanup: true, matrixRows: REQUEST_COUNT / BATCH_COUNT,
      authentication: {requests: 2, invalidCredentialRejected: true, validCredentialAccepted: true}}))};
}

test('success requires direct-only scope, every identity/lifecycle fact and three individually clean fresh batches', () => {
  assert.equal(completedChecks(complete()), true); assert.equal(adjudicateReceipt(complete()), SUCCESS);
  for (const key of Object.keys(complete())) {
    const changed = complete(); delete changed[key];
    assert.equal(completedChecks(changed), false, key);
    assert.equal(adjudicateReceipt(changed), 'FAILED', key);
  }
  for (let index = 0; index < BATCH_COUNT; ++index) {
    for (const change of [{batchIndex: -1}, {completed: false}, {fixtureShutdown: 'FORCED'}, {cleanup: false},
      {matrixRows: 17}, {failure: 'APPS_MIME_RUN_FAILED'}, {authentication: {}},
      {authentication: {requests: 3, invalidCredentialRejected: true, validCredentialAccepted: true}},
      {authentication: {requests: 2, invalidCredentialRejected: false, validCredentialAccepted: true}},
      {authentication: {requests: 2, invalidCredentialRejected: true, validCredentialAccepted: false}}]) {
      const changed = complete(); Object.assign(changed.fixtureRuns[index], change);
      assert.equal(adjudicateReceipt(changed), 'FAILED');
    }
  }
  for (const change of [{fixtureRuns: []}, {fixtureRuns: complete().fixtureRuns.toReversed()},
    {fixtureRuns: Array(BATCH_COUNT)},
    {fixtureRuns: [...complete().fixtureRuns, complete().fixtureRuns[0]]}, {matrixRequestCount: 108},
    {scope: 'browser'}, {hostQualification: true}, {browserExercised: true}, {experimental: false},
    {releaseCandidateEvidence: true}, {interrupted: true}, {cleanup: {processes: false}},
    {failure: 'APPS_MIME_RUN_FAILED'}, {integrityFailure: 'APPS_MIME_INPUT_DRIFT'}, {cleanupFailure: 'APPS_MIME_CLEANUP'}])
    assert.equal(adjudicateReceipt({...complete(), ...change}), 'FAILED');
  for (let index = 0; index < BATCH_COUNT; ++index) {
    const changed = complete(); delete changed.fixtureRuns[index];
    assert.equal(adjudicateReceipt(changed), 'FAILED');
  }
});

test('only exact reviewed failure enums survive redaction', () => {
  for (const message of ['APPS_MIME_PIN', 'APPS_MIME_INTERRUPTED', ...MIME_FAILURES])
    assert.equal(failureCode(new Error(message)), message);
  for (const error of [undefined, null, {message: 'APPS_MIME_PRIVATE_CANARY'}, new Error('Bearer secret'),
    new Error('/private/tmp/private-secret'), new Error('APPS_MIME_INPUT\nsecret')])
    assert.equal(failureCode(error), 'APPS_MIME_RUN_FAILED');
});

const authResponse = authorized => new Response(JSON.stringify(authorized
  ? {jsonrpc: '2.0', id: 1, result: {resultType: 'complete', ttlMs: 0, cacheScope: 'private',
    _meta: {'io.modelcontextprotocol/serverInfo': {name: 'soklet-apps-fixture', version: 'fixture-v1'}},
    supportedVersions: [PROTOCOL], capabilities: {tools: {listChanged: true}, resources: {}, extensions: {[UI]: {mimeTypes: [MIME]}}}}}
  : {jsonrpc: '2.0', id: 1, error: {code: -31901, message: 'Authentication required.'}}),
{status: authorized ? 200 : 401, headers: {'content-type': 'application/json', 'cache-control': 'no-store',
  ...(!authorized ? {'www-authenticate': 'Bearer'} : {})}});

test('wrong/right credential controls are bounded, sequential and retain only fixed facts', async () => {
  const token = 'c'.repeat(64), calls = [];
  const facts = await fixtureAuthentication({port: 12345, token, fetchImpl: async (url, options) => {
    const authorized = calls.length === 1; calls.push(options);
    assert.equal(url, 'http://127.0.0.1:12345/apps'); assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal); assert.equal(options.method, 'POST');
    assert.equal(options.headers.Authorization, `Bearer ${authorized ? token : 'invalid-disposable-token'}`);
    assert.equal(JSON.parse(options.body).method, 'server/discover'); return authResponse(authorized);
  }});
  assert.deepEqual(facts, {requests: 2, invalidCredentialRejected: true, validCredentialAccepted: true});
  assert.equal(calls.length, 2); assert.equal(JSON.stringify(facts).includes(token), false);
});

test('authentication mismatches, invalid inputs and oversized/malformed bodies stop before later controls', async () => {
  for (const change of [{port: 0}, {port: 65536}, {token: 'bad'}])
    await assert.rejects(fixtureAuthentication({port: 12345, token: 'c'.repeat(64), ...change}), /APPS_MIME_AUTH_INPUT/);
  for (const response of [authResponse(true), new Response('not-json'), new Response('x'.repeat(65537)),
    new Response('{}', {status: 401, headers: {'content-type': 'application/json', 'cache-control': 'no-store'}})]) {
    let calls = 0;
    await assert.rejects(fixtureAuthentication({port: 12345, token: 'c'.repeat(64), fetchImpl: async () => {++calls; return response;}}),
      /APPS_MIME_(?:AUTHENTICATION|RESPONSE_JSON|RESPONSE_BOUND)/);
    assert.equal(calls, 1);
  }
});

test('bounded JSON releases its reader and cancellation prevents later authentication requests', async () => {
  const response = new Response('{"ok":true}');
  assert.deepEqual(await boundedJson(response), {ok: true}); assert.equal(response.body.locked, false);
  for (const maximum of [0, 1048577, Infinity]) await assert.rejects(boundedJson(new Response('{}'), {maximum}), /APPS_MIME_RESPONSE_BOUND/);
  await assert.rejects(boundedJson(new Response(null)), /APPS_MIME_RESPONSE_BODY/);
  const abort = new AbortController(); let calls = 0;
  await assert.rejects(fixtureAuthentication({port: 12345, token: 'c'.repeat(64), signal: abort.signal,
    fetchImpl: async () => {++calls; abort.abort(); return authResponse(false);}}), /APPS_MIME_INTERRUPTED/);
  assert.equal(calls, 1);
  await assert.rejects(fixtureAuthentication({port: 12345, token: 'c'.repeat(64), signal: abort.signal,
    fetchImpl: async () => {++calls; return authResponse(false);}}), /APPS_MIME_INTERRUPTED/);
  assert.equal(calls, 1);
});

test('matrix callback retention preserves failed partial rows and requires exact returned batches', async () => {
  const retained = [];
  const expected = Array.from({length: REQUEST_COUNT / BATCH_COUNT}, (_, index) => ({sequence: index + 1}));
  await collectMatrix({}, retained, async ({onRow}) => {expected.forEach(onRow); return expected;});
  assert.deepEqual(retained, expected); expected[0].sequence = 999; assert.equal(retained[0].sequence, 1);
  const partial = [];
  await assert.rejects(collectMatrix({}, partial, async ({onRow}) => {
    onRow({sequence: 1, resultMatches: true}); onRow({sequence: 2, resultMatches: false}); throw new Error('APPS_MIME_RESPONSE');
  }), /APPS_MIME_RESPONSE/);
  assert.deepEqual(partial, [{sequence: 1, resultMatches: true}, {sequence: 2, resultMatches: false}]);
  for (const execute of [async () => expected, async ({onRow}) => {onRow(expected[0]); return expected;},
    async ({onRow}) => {expected.forEach(onRow); return expected.toReversed();}])
    await assert.rejects(collectMatrix({}, [], execute), /APPS_MIME_MATRIX_CALLBACK_MISMATCH/);
  await assert.rejects(collectMatrix({}, Array(REQUEST_COUNT).fill({}), async ({onRow}) => {onRow({});}), /APPS_MIME_MATRIX_CALLBACK_BOUND/);
});

test('pre-cancellation skips matrix scheduling and cancellation during completion cannot pass', async () => {
  const abort = new AbortController(); abort.abort(); let runs = 0;
  await assert.rejects(collectMatrix({signal: abort.signal}, [], async () => {++runs; return [];}), /APPS_MIME_INTERRUPTED/);
  assert.equal(runs, 0);
  const late = new AbortController(), retained = [];
  await assert.rejects(collectMatrix({signal: late.signal}, retained, async ({onRow}) => {
    const batch = Array.from({length: REQUEST_COUNT / BATCH_COUNT}, (_, index) => ({sequence: index + 1}));
    batch.forEach(onRow); late.abort(); return batch;
  }), /APPS_MIME_INTERRUPTED/);
  assert.equal(retained.length, REQUEST_COUNT / BATCH_COUNT);
});
