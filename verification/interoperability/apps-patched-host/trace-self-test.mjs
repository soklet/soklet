import assert from 'node:assert/strict';
import { test } from 'node:test';
import { adjudicateAppsTrace, projectAppsExchange, MIME, PROTOCOL, UI } from '../apps/host-trace.mjs';
import { adjudicatePatchedAppsTrace, adjudicatePatchedAppsTransitionTrace,
  adjudicatePatchedAppsRevocationTrace } from './trace.mjs';

const POSITIVES = ['server/discover', 'resources/list', 'tools/list', 'resources/templates/list',
  'tools/list', 'show_catalog', 'resources/read', 'refresh_catalog'];
const FLAGS = ['authorized', 'authorizationForwarded', 'requestEnvelopeValid', 'protocolMetadataMatches',
  'protocolHeaderMatches', 'methodHeaderMatches', 'nameHeaderMatches', 'perRequestCapabilitiesPresent',
  'appsMimeMatches', 'skillsAbsent', 'noSessionState', 'requestSelectionValid', 'responseJson',
  'responseNoStore', 'responseCorrelated', 'responseEnvelopeValid', 'resultMatchesFixture'];
const isTool = operation => ['show_catalog', 'refresh_catalog'].includes(operation);
const row = (operation, sequence) => ({surface: 'apps-web', sequence,
  method: isTool(operation) ? 'tools/call' : operation,
  tool: isTool(operation) ? operation : 'NONE',
  ...Object.fromEntries(FLAGS.map(key => [key, true])),
  responseStatus: operation === 'subscriptions/listen' ? 403 : 200,
  subscriptionDenied: operation === 'subscriptions/listen', requestBytes: 250, responseBytes: 400});
const rows = order => order.map((operation, index) => row(operation, index + 1));
const order = (count = 1) => [POSITIVES[0], 'subscriptions/listen', ...POSITIVES.slice(1),
  ...Array(count - 1).fill('subscriptions/listen')];
const resequence = trace => trace.map((value, index) => ({...value, sequence: index + 1}));

test('experimental profile accepts one through eight exact denials without changing the original profile', () => {
  for (let count = 1; count <= 8; ++count) {
    const trace = rows(order(count));
    const before = structuredClone(trace);
    assert.equal(adjudicatePatchedAppsTrace(trace), 'PASSED', `denials=${count}`);
    assert.deepEqual(trace, before, 'adjudication must not mutate evidence');
    assert.equal(adjudicateAppsTrace(trace), count === 1 ? 'PASSED' : 'FAILED');
  }
});

test('repeat denials can interleave anywhere after discovery and after the refresh', () => {
  for (let position = 2; position <= 9; ++position) {
    const operations = order(); operations.splice(position, 0, 'subscriptions/listen');
    assert.equal(adjudicatePatchedAppsTrace(rows(operations)), 'PASSED', `position=${position}`);
  }
  const interleaved = [POSITIVES[0], ...POSITIVES.slice(1).flatMap(operation => ['subscriptions/listen', operation]),
    'subscriptions/listen'];
  assert.equal(adjudicatePatchedAppsTrace(rows(interleaved)), 'PASSED');
});

test('catalog completions may reorder and the single shell read may prefetch before show_catalog', () => {
  for (let position = 1; position < 8; ++position) {
    const operations = order(2).filter(operation => operation !== 'resources/read');
    operations.splice(position, 0, 'resources/read');
    assert.equal(adjudicatePatchedAppsTrace(rows(operations)), 'PASSED', `prefetch=${position}`);
  }
  const reordered = ['server/discover', 'tools/list', 'subscriptions/listen', 'tools/list',
    'resources/templates/list', 'resources/list', 'resources/read', 'show_catalog', 'refresh_catalog'];
  assert.equal(adjudicatePatchedAppsTrace(rows(reordered)), 'PASSED');
});

test('missing and excessive subscription denials or incomplete/duplicate positive exchanges fail', () => {
  for (const invalid of [rows(POSITIVES), rows(order(9)), [], null, {}, Array(9).fill(null),
    Array(9).fill([]), Array(9).fill('row')])
    assert.equal(adjudicatePatchedAppsTrace(invalid), 'FAILED');
  const valid = rows(order(2));
  for (const index of valid.map((_, index) => index).filter(index => valid[index].method !== 'subscriptions/listen')) {
    assert.equal(adjudicatePatchedAppsTrace(resequence(valid.filter((_, i) => i !== index))), 'FAILED', `missing=${index}`);
    const duplicate = [...valid]; duplicate.splice(index, 0, valid[index]);
    assert.equal(adjudicatePatchedAppsTrace(resequence(duplicate)), 'FAILED', `duplicate=${index}`);
  }
  for (const replacement of ['tools/list', 'resources/list', 'resources/templates/list', 'show_catalog',
    'resources/read', 'refresh_catalog', 'server/discover', 'prompts/list', 'skills/list', 'initialize']) {
    const changed = order(2); changed[3] = replacement;
    if (replacement === 'tools/list') continue;
    assert.equal(adjudicatePatchedAppsTrace(rows(changed)), 'FAILED', replacement);
  }
});

test('discovery, first denial, acquisitions, initial tool, shell and refresh ordering remains strict', () => {
  const invalid = [
    ['subscriptions/listen', ...POSITIVES],
    [...POSITIVES.slice(0, 6), 'subscriptions/listen', ...POSITIVES.slice(6)],
    ['server/discover', 'subscriptions/listen', 'show_catalog', ...POSITIVES.slice(1, 5), ...POSITIVES.slice(6)],
    ['server/discover', 'subscriptions/listen', ...POSITIVES.slice(1, 5), 'refresh_catalog', 'resources/read', 'show_catalog'],
    ['server/discover', 'subscriptions/listen', ...POSITIVES.slice(1, 6), 'refresh_catalog', 'resources/read'],
  ];
  for (const operations of invalid) assert.equal(adjudicatePatchedAppsTrace(rows(operations)), 'FAILED');
});

test('every true predicate remains mandatory on every positive and repeated denial row', () => {
  const valid = rows(order(4));
  for (let index = 0; index < valid.length; ++index) {
    for (const key of FLAGS) {
      for (const value of [false, undefined, 1, 'true']) {
        const changed = structuredClone(valid); changed[index][key] = value;
        assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED', `${index}:${key}:${value}`);
      }
    }
  }
});

test('all rows require exact projected fields and gap-free sequence; malformed retries cannot be filtered away', () => {
  const valid = rows(order(4));
  const changes = [{surface: 'web'}, {sequence: 0}, {sequence: 2.5}, {sequence: '1'},
    {method: 'notifications/tools/list_changed'}, {method: 'initialize'}, {method: 'tools/call', tool: 'unsupported'},
    {requestBytes: 0}, {requestBytes: -1}, {requestBytes: 65537}, {requestBytes: 1.5}, {requestBytes: Infinity},
    {responseBytes: 0}, {responseBytes: -1}, {responseBytes: 1048577}, {responseBytes: NaN},
    {responseBytes: '400'}, {requestBody: 'PRIVATE_CANARY'}];
  for (let index = 0; index < valid.length; ++index) {
    for (const change of changes) {
      const changed = structuredClone(valid); changed[index] = {...changed[index], ...change};
      assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED', `${index}:${Object.keys(change).join(',')}`);
    }
    const missing = structuredClone(valid); delete missing[index].responseBytes;
    assert.equal(adjudicatePatchedAppsTrace(missing), 'FAILED');
  }
  for (let index = 1; index < valid.length; ++index) {
    const changed = structuredClone(valid); changed[index].sequence = changed[index - 1].sequence;
    assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
  }
});

test('status, denial classification and tool tags cannot turn authentication errors or streams into passes', () => {
  const valid = rows(order(4));
  for (let index = 0; index < valid.length; ++index) {
    const subscription = valid[index].method === 'subscriptions/listen';
    const statuses = subscription ? [200, 202, 204, 401, 404, 500, '403'] : [202, 204, 401, 403, 500, '200'];
    for (const status of statuses) {
      const changed = structuredClone(valid); changed[index].responseStatus = status;
      assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
    }
    const changed = structuredClone(valid); changed[index].subscriptionDenied = !subscription;
    assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
    for (const tag of ['UNSUPPORTED', valid[index].tool === 'NONE' ? 'show_catalog' : 'NONE']) {
      const changed = structuredClone(valid); changed[index].tool = tag;
      assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
    }
  }
});

function projectDenial(changes = {}) {
  const request = {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', method: 'subscriptions/listen', params: {
    _meta: {'io.modelcontextprotocol/protocolVersion': PROTOCOL,
      'io.modelcontextprotocol/clientCapabilities': {extensions: {[UI]: {mimeTypes: [MIME], elicitation: {}}}}},
    notifications: {toolsListChanged: true},
  }};
  const response = {jsonrpc: '2.0', id: request.id, error: {code: -32603, message: 'Internal error'}};
  const headers = {'mcp-method': request.method, 'mcp-protocol-version': PROTOCOL};
  return projectAppsExchange({request, response, headers, status: 403,
    responseHeaders: {'content-type': 'application/json', 'cache-control': 'no-store'},
    requestBytes: 250, responseBytes: 400, authorized: true, authorizationForwarded: true,
    sequence: 10, ...changes});
}

test('unchanged proxy projection validates actual repeated-denial request and error payloads', () => {
  const valid = rows(order(2)); valid[9] = projectDenial();
  assert.equal(adjudicatePatchedAppsTrace(valid), 'PASSED');
  assert.equal(JSON.stringify(valid).includes('PRIVATE_CANARY'), false);
  const invalidResponses = [
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', error: {code: -32603, message: 'wrong'}},
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', error: {code: -32603, message: 'Internal error', data: 'PRIVATE_CANARY'}},
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', error: {code: -32601, message: 'Internal error'}},
    {jsonrpc: '2.0', id: 'wrong', error: {code: -32603, message: 'Internal error'}},
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', result: {resultType: 'complete'}},
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', error: {code: -32603, message: 'Internal error'}, extra: true},
  ];
  for (const response of invalidResponses) {
    const changed = [...valid]; changed[9] = projectDenial({response});
    assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
  }
  for (const responseHeaders of [{'content-type': 'text/event-stream', 'cache-control': 'no-store'},
    {'content-type': 'application/json'}, {'content-type': 'application/json', 'cache-control': 'no-store', 'mcp-session-id': 'session'}]) {
    const changed = [...valid]; changed[9] = projectDenial({responseHeaders});
    assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
  }
  for (const request of [
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', method: 'subscriptions/listen', params: {notifications: {resourcesListChanged: true}}},
    {jsonrpc: '2.0', id: 'PRIVATE_CANARY_ID', method: 'subscriptions/listen', params: {notifications: {toolsListChanged: true}, extra: true}},
  ]) {
    const changed = [...valid]; changed[9] = projectDenial({request});
    assert.equal(adjudicatePatchedAppsTrace(changed), 'FAILED');
  }
});

test('inclusive byte bounds remain identical to the original bounded proxy profile', () => {
  for (const sizes of [{requestBytes: 1, responseBytes: 1}, {requestBytes: 65536, responseBytes: 1048576}]) {
    const changed = rows(order(8)).map(value => ({...value, ...sizes}));
    assert.equal(adjudicatePatchedAppsTrace(changed), 'PASSED');
  }
});

test('same-App caller transitions require one beta success, one denial, and only bounded later subscription retries', () => {
  const base = rows(order(4));
  const trace = [...base, row('refresh_catalog', 13),
    {...row('refresh_catalog', 14), responseStatus: 400}, row('subscriptions/listen', 15)];
  assert.equal(adjudicatePatchedAppsTransitionTrace(trace, base.length), 'PASSED');
  assert.equal(adjudicatePatchedAppsTransitionTrace(trace.slice(0, -1), base.length), 'PASSED');
  for (const changed of [trace.slice(0, 13), [...trace, row('show_catalog', 16)],
    [...trace, ...Array.from({length: 5}, (_, i) => row('subscriptions/listen', 16 + i))],
    trace.map((value, index) => index === 13 ? {...value, responseStatus: 200} : value),
    trace.map((value, index) => index === 12 ? {...value, resultMatchesFixture: false} : value),
    trace.map((value, index) => index === 14 ? {...value, responseNoStore: false} : value)])
    assert.equal(adjudicatePatchedAppsTransitionTrace(changed, base.length), 'FAILED');
  for (const invalidCount of [0, 9, 13, 17, '12'])
    assert.equal(adjudicatePatchedAppsTransitionTrace(trace, invalidCount), 'FAILED');
});

test('open-App revocation requires one 401 refresh and only bounded 401 subscription retries', () => {
  const base = rows(order(4));
  const revoked = {...row('refresh_catalog', 13), responseStatus: 401};
  const retry = {...row('subscriptions/listen', 14), responseStatus: 401, subscriptionDenied: false};
  const trace = [...base, revoked, retry];
  assert.equal(adjudicatePatchedAppsRevocationTrace(trace, base.length), 'PASSED');
  assert.equal(adjudicatePatchedAppsRevocationTrace(trace.slice(0, -1), base.length), 'PASSED');
  for (const changed of [base, [...trace, row('show_catalog', 15)],
    [...trace, ...Array.from({length: 5}, (_, i) => ({...retry, sequence: 15 + i}))],
    trace.map((value, index) => index === 12 ? {...value, responseStatus: 200} : value),
    trace.map((value, index) => index === 13 ? {...value, subscriptionDenied: true} : value),
    trace.map((value, index) => index === 13 ? {...value, resultMatchesFixture: false} : value)])
    assert.equal(adjudicatePatchedAppsRevocationTrace(changed, base.length), 'FAILED');
});
