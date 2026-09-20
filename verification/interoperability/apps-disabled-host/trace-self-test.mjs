import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import { createConnection } from 'node:net';
import { test } from 'node:test';
import { MIME, UI_URI } from '../apps/host-trace.mjs';
import { PROTOCOL, UI, SKILLS, adjudicateAppsDisabledTrace, projectAppsDisabledExchange,
  projectDisabledGateRejection, startAppsDisabledProxy, validAppsDisabledResult } from './trace.mjs';

const secret = 'PRIVATE_CANARY_NEVER_PERSIST';
const token = randomBytes(32).toString('hex');
const shell = '<!doctype html><html><body>STATIC_SHELL_NEVER_PERSIST</body></html>';
const metadata = {'io.modelcontextprotocol/serverInfo': {name: 'soklet-apps-fixture', version: 'fixture-v1'}};
const data = {locale: 'en-US', direction: 'ltr', tenant: 'alpha', title: 'Catalog view',
  refreshLabel: 'Refresh catalog', summary: 'Catalog alpha: 1 item.',
  itemLabel: 'Toy <img src=x onerror=alert(1)>', amount: 1234.5, currency: 'USD',
  updatedAt: '2026-09-19T12:00:00Z', timeZone: 'UTC'};
const schema = {type: 'object', properties: {}, additionalProperties: false};
const operations = ['server/discover', 'resources/list', 'tools/list', 'subscriptions/listen',
  'resources/templates/list', 'tools/list', 'show_catalog'];
const wireMethod = operation => ['show_catalog', 'refresh_catalog'].includes(operation) ? 'tools/call' : operation;
function result(operation) {
  const catalog = {resultType: 'complete', _meta: metadata, ttlMs: 0, cacheScope: 'private'};
  if (operation === 'server/discover') return {...catalog, supportedVersions: [PROTOCOL],
    capabilities: {tools: {listChanged: true}, resources: {}, extensions: {[UI]: {mimeTypes: [MIME]}}}};
  if (operation === 'tools/list') return {...catalog, tools: [{name: 'show_catalog', title: 'Show catalog', inputSchema: schema}]};
  if (operation === 'resources/list') return {...catalog, resources: [{uri: UI_URI, name: 'catalog_view', title: 'Catalog view', mimeType: MIME}]};
  if (operation === 'resources/templates/list') return {...catalog, resourceTemplates: []};
  return {resultType: 'complete', _meta: {...metadata, 'example/view': 'catalog-v1'},
    content: [{type: 'text', text: data.summary}], structuredContent: data};
}
function request(operation) {
  return {jsonrpc: '2.0', id: secret, method: wireMethod(operation), params: {
    _meta: {'io.modelcontextprotocol/protocolVersion': PROTOCOL,
      'io.modelcontextprotocol/clientCapabilities': {extensions: {'io.modelcontextprotocol/tasks': {}}}, 'example/private': secret},
    ...(wireMethod(operation) === 'tools/call' ? {name: operation, arguments: {}} : {}),
    ...(operation === 'resources/read' ? {uri: UI_URI} : {}),
    ...(operation === 'subscriptions/listen' ? {notifications: {toolsListChanged: true}} : {}),
  }};
}
function headers(operation) {
  return {'content-type': 'application/json', authorization: `Bearer ${token}`,
    'mcp-protocol-version': PROTOCOL, 'mcp-method': wireMethod(operation),
    ...(wireMethod(operation) === 'tools/call' ? {'mcp-name': operation} : {})};
}
function response(operation) {
  return {jsonrpc: '2.0', id: secret, ...(operation === 'subscriptions/listen'
    ? {error: {code: -32603, message: 'Internal error'}} : {result: result(operation)})};
}
function project(operation, sequence, changes = {}) {
  return projectAppsDisabledExchange({request: request(operation), response: response(operation), headers: headers(operation),
    status: operation === 'subscriptions/listen' ? 403 : 200,
    responseHeaders: {'content-type': 'application/json', 'cache-control': 'no-store'},
    requestBytes: 250, responseBytes: 400, authorized: true, authorizationForwarded: true, sequence, shell, ...changes});
}
const rows = (order = operations) => order.map((operation, index) => project(operation, index + 1));
const resequence = trace => trace.map((row, index) => ({...row, sequence: index + 1}));
function sanitized(value) {
  const json = JSON.stringify(value);
  for (const privateValue of [secret, token, shell, data.summary, data.itemLabel, 'Bearer ', 'ui://'])
    assert.equal(json.includes(privateValue), false, privateValue);
}

test('OFF requires six exact ordinary exchanges and one to eight denied subscription attempts', () => {
  for (let count = 1; count <= 8; ++count) {
    const trace = rows([...operations, ...Array(count - 1).fill('subscriptions/listen')]);
    const original = structuredClone(trace);
    assert.equal(adjudicateAppsDisabledTrace(trace), 'PASSED');
    assert.deepEqual(trace, original); sanitized(trace);
  }
  assert.equal(adjudicateAppsDisabledTrace(rows([...operations, ...Array(8).fill('subscriptions/listen')])), 'FAILED');
  assert.equal(adjudicateAppsDisabledTrace(rows(operations.filter(value => value !== 'subscriptions/listen'))), 'FAILED');
});

test('catalog completions/retries can interleave but discovery, first denial and tool ordering stay strict', () => {
  assert.equal(adjudicateAppsDisabledTrace(rows([operations[0], ...operations.slice(1, 6).reverse(), operations[6]])), 'PASSED');
  for (let position = 1; position <= operations.length; ++position) {
    const order = [...operations]; order.splice(position, 0, 'subscriptions/listen');
    assert.equal(adjudicateAppsDisabledTrace(rows(order)), 'PASSED');
  }
  for (const order of [
    ['subscriptions/listen', ...operations.filter(value => value !== 'subscriptions/listen')],
    [...operations.filter(value => value !== 'subscriptions/listen'), 'subscriptions/listen'],
    [operations[0], 'show_catalog', ...operations.slice(1, 6)],
  ]) assert.equal(adjudicateAppsDisabledTrace(rows(order)), 'FAILED');
});

test('every positive exchange is mandatory exactly once except the two fixed tools acquisitions', () => {
  const valid = rows([...operations, 'subscriptions/listen']);
  for (let index = 0; index < operations.length; ++index) {
    if (operations[index] === 'subscriptions/listen') continue;
    assert.equal(adjudicateAppsDisabledTrace(resequence(valid.filter((_, i) => i !== index))), 'FAILED');
    const duplicate = [...valid]; duplicate.splice(index, 0, valid[index]);
    assert.equal(adjudicateAppsDisabledTrace(resequence(duplicate)), 'FAILED');
  }
  for (const value of [null, [], {}, Array(7).fill(null)]) assert.equal(adjudicateAppsDisabledTrace(value), 'FAILED');
});

test('all mandatory true fields, enums, exact schema, byte bounds and gap-free sequences are enforced on retries too', () => {
  const valid = rows([...operations, 'subscriptions/listen']);
  for (let index = 0; index < valid.length; ++index) {
    for (const [key, value] of Object.entries(valid[index])) {
      if (value !== true) continue;
      for (const replacement of [false, 1, 'true', undefined]) {
        const changed = structuredClone(valid); changed[index][key] = replacement;
        assert.equal(adjudicateAppsDisabledTrace(changed), 'FAILED', `${index}:${key}`);
      }
    }
    for (const change of [{surface: 'apps-web'}, {requestedExtensions: 'ENABLED'}, {sequence: 0}, {sequence: 1.5},
      {method: 'resources/read'}, {tool: 'refresh_catalog'}, {responseStatus: 401}, {responseStatus: 202},
      {responseStatus: 500}, {requestBytes: 0}, {requestBytes: 65537}, {requestBytes: 0.5},
      {requestBytes: Infinity}, {responseBytes: 0}, {responseBytes: 1048577}, {responseBytes: '400'},
      {rawBody: secret}, {subscriptionDenied: !valid[index].subscriptionDenied}]) {
      const changed = structuredClone(valid); changed[index] = {...changed[index], ...change};
      assert.equal(adjudicateAppsDisabledTrace(changed), 'FAILED');
    }
  }
});

test('OFF strips tool UI metadata/helper but does not remove server Apps support or authorized resource descriptors', () => {
  for (const operation of operations.filter(value => value !== 'subscriptions/listen'))
    assert.equal(validAppsDisabledResult(wireMethod(operation), result(operation), shell), true);
  for (const mutate of [
    value => { value.tools[0]._meta = {ui: {resourceUri: UI_URI}}; },
    value => { value.tools[0]._meta = {}; },
    value => { value.tools.push({name: 'refresh_catalog', title: 'Refresh catalog', inputSchema: schema}); },
    value => { value.tools[0].inputSchema.additionalProperties = true; },
    value => { value.tools[0].title = secret; }, value => { value.tools = []; },
    value => { value.extra = secret; }, value => { value.ttlMs = 100; },
  ]) {
    const changed = structuredClone(result('tools/list')); mutate(changed);
    assert.equal(validAppsDisabledResult('tools/list', changed, shell), false);
  }
  for (const [operation, mutate] of [
    ['server/discover', value => { delete value.capabilities.extensions; }],
    ['resources/list', value => { value.resources = []; }],
    ['resources/templates/list', value => { value.resourceTemplates = [{uriTemplate: secret}]; }],
    ['show_catalog', value => { value.content[0].text = secret; }],
    ['show_catalog', value => { value._meta['example/private'] = secret; }],
    ['show_catalog', value => { value.structuredContent.tenant = 'beta'; }],
  ]) {
    const changed = structuredClone(result(operation)); mutate(changed);
    assert.equal(validAppsDisabledResult(wireMethod(operation), changed, shell), false);
  }
});

test('every request independently omits Apps and Skills; malformed extension containers fail closed', () => {
  for (const extension of [UI, SKILLS]) {
    const changed = request('tools/list');
    changed.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions[extension] = {};
    const row = project('tools/list', 1, {request: changed});
    assert.equal(row[extension === UI ? 'appsAbsent' : 'skillsAbsent'], false); sanitized(row);
  }
  for (const extensions of [null, [], false, 'none']) {
    const changed = request('tools/list'); changed.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions = extensions;
    const row = project('tools/list', 1, {request: changed});
    assert.equal(row.appsAbsent, false); assert.equal(row.skillsAbsent, false);
  }
  const noExtensions = request('tools/list'); delete noExtensions.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions;
  assert.equal(project('tools/list', 1, {request: noExtensions}).appsAbsent, true);
  for (const operation of ['resources/read', 'refresh_catalog', 'prompts/list', 'skills/list'])
    assert.equal(project(operation, 1).requestSelectionValid, false);
});

test('only exact bounded 403 subscription JSON errors qualify; streams/auth errors/error extras fail', () => {
  const valid = rows([...operations, 'subscriptions/listen']);
  for (const change of [{status: 200}, {status: 401},
    {responseHeaders: {'content-type': 'text/event-stream', 'cache-control': 'no-store'}},
    {response: {jsonrpc: '2.0', id: secret, error: {code: -32603, message: 'Internal error', data: secret}}},
    {response: {jsonrpc: '2.0', id: secret, error: {code: -32601, message: 'Internal error'}}},
    {response: {jsonrpc: '2.0', id: 'wrong', error: {code: -32603, message: 'Internal error'}}},
  ]) {
    const changed = [...valid]; changed[7] = project('subscriptions/listen', 8, change);
    assert.equal(adjudicateAppsDisabledTrace(changed), 'FAILED'); sanitized(changed);
  }
});

function exchange(port, operation, options = {}) {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({host: '127.0.0.1', port, method: options.method ?? 'POST', path: options.path ?? '/mcp',
      headers: {...headers(operation), ...options.headers}}, incoming => {
      const chunks = []; incoming.on('data', chunk => chunks.push(chunk)); incoming.once('error', reject);
      incoming.once('end', () => resolve({status: incoming.statusCode, body: Buffer.concat(chunks).toString('utf8')}));
    });
    outgoing.setTimeout(2500, () => outgoing.destroy(new Error('TEST_TIMEOUT'))); outgoing.once('error', reject);
    outgoing.end(options.body ?? JSON.stringify(request(operation)));
  });
}

async function fixture(callback) {
  const received = [];
  const server = createServer((incoming, outgoing) => {
    const chunks = []; incoming.on('data', chunk => chunks.push(chunk));
    incoming.once('end', () => {
      const body = Buffer.concat(chunks), value = JSON.parse(body.toString('utf8'));
      received.push({headers: incoming.headers, path: incoming.url, body});
      const operation = value.method === 'tools/call' ? value.params.name : value.method;
      outgoing.writeHead(operation === 'subscriptions/listen' ? 403 : 200,
        {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end(JSON.stringify(response(operation)));
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  try { await callback(server.address().port, received); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

test('loopback proxy forwards identical admitted requests only, preserves authorization, and removes unrelated credentials', async () => {
  await fixture(async (fixturePort, received) => {
    const proxy = await startAppsDisabledProxy({fixturePort, token, shell});
    try {
      for (const operation of [...operations, 'subscriptions/listen']) {
        assert.equal((await exchange(proxy.port, operation, {headers: {cookie: secret, 'proxy-authorization': secret, 'x-api-key': secret}})).status,
          operation === 'subscriptions/listen' ? 403 : 200);
        const latest = received.at(-1);
        assert.equal(latest.path, '/apps'); assert.equal(latest.headers.authorization, `Bearer ${token}`);
        assert.equal(latest.body.toString('utf8'), JSON.stringify(request(operation)));
        for (const key of ['cookie', 'proxy-authorization', 'x-api-key']) assert.equal(latest.headers[key], undefined);
      }
      assert.equal(proxy.failure(), undefined); assert.equal(adjudicateAppsDisabledTrace(proxy.rows), 'PASSED');
      assert.equal(proxy.rejections.length, 0); sanitized(proxy.rows);
    } finally { await proxy.close(); await proxy.close(); }
  });
});

test('UI reads, helper calls, OAuth, capabilities, credentials, origin, method, protocol and argument drift never reach fixture', async () => {
  const withCapability = extension => {
    const changed = request('tools/list'); changed.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions[extension] = {};
    return JSON.stringify(changed);
  };
  const negatives = [
    ['resources/read', {}, 'UNEXPECTED_METHOD'], ['refresh_catalog', {}, 'REQUEST_SELECTION'],
    ['tools/list', {method: 'GET'}, 'POST_ONLY'], ['tools/list', {path: '/register'}, 'PATH'],
    ['tools/list', {path: '/.well-known/oauth-protected-resource/mcp'}, 'PATH'],
    ['tools/list', {headers: {host: 'elsewhere.invalid'}}, 'HOST'],
    ['tools/list', {headers: {authorization: `Bearer ${secret}`}}, 'AUTH_REQUIRED'],
    ['tools/list', {headers: {origin: 'http://127.0.0.1:1'}}, 'ORIGIN'],
    ['tools/list', {headers: {'mcp-protocol-version': '2025-11-25'}}, 'MODERN_PROTOCOL_REQUIRED'],
    ['tools/list', {headers: {'mcp-method': 'tools/call'}}, 'METHOD_HEADER'],
    ['tools/list', {headers: {'mcp-name': 'show_catalog'}}, 'NAME_HEADER'],
    ['tools/list', {body: '{broken'}, 'REQUEST_INVALID'],
    ['tools/list', {body: withCapability(UI)}, 'EXTENSION_SELECTION'],
    ['tools/list', {body: withCapability(SKILLS)}, 'EXTENSION_SELECTION'],
    ['show_catalog', {body: JSON.stringify({...request('show_catalog'), params: {...request('show_catalog').params,
      arguments: {tenant: secret}}})}, 'REQUEST_SELECTION'],
  ];
  await fixture(async (fixturePort, received) => {
    for (const [operation, options, code] of negatives) {
      const proxy = await startAppsDisabledProxy({fixturePort, token, shell});
      try {
        assert.equal((await exchange(proxy.port, operation, options)).status, 400);
        assert.equal(proxy.failure(), `APPS_DISABLED_${code}`); assert.equal(proxy.rows.length, 0); assert.equal(received.length, 0);
        assert.equal(proxy.rejections.length, 1); sanitized(proxy.rejections);
      } finally { await proxy.close(); }
    }
  });
});

test('diagnostic rejection projection retains only reviewed enums and never raw data', () => {
  assert.deepEqual(projectDisabledGateRejection({method: secret, url: '/' + secret, headers: {origin: secret}}, secret, 1),
    {sequence: 1, method: 'OTHER', path: 'OTHER', code: 'APPS_DISABLED_REQUEST_INVALID', originAbsent: false});
  assert.equal(projectDisabledGateRejection({method: 'POST', url: '/register', headers: {}}, 'APPS_DISABLED_PATH', 1).path,
    'OAUTH_REGISTRATION_ROOT');
});

test('invalid proxy configuration and oversized inbound request are rejected', async () => {
  for (const change of [{fixturePort: 0}, {fixturePort: 65536}, {token: secret}, {shell: ''}, {shell: 'x'.repeat(524289)}])
    await assert.rejects(startAppsDisabledProxy({fixturePort: 1, token, shell, ...change}), /APPS_DISABLED_INVALID_PROXY_CONFIGURATION/);
  await fixture(async (fixturePort, received) => {
    const proxy = await startAppsDisabledProxy({fixturePort, token, shell});
    try {
      await exchange(proxy.port, 'tools/list', {body: 'x'.repeat(65537)}).catch(() => {});
      assert.equal(proxy.failure(), 'APPS_DISABLED_BODY_BOUND'); assert.equal(received.length, 0);
    } finally { await proxy.close(); }
  });
});

test('malformed/oversized upstream responses never qualify', async () => {
  for (const body of ['{broken', 'x'.repeat(1048577)]) {
    const upstream = createServer((incoming, outgoing) => {
      incoming.resume(); incoming.once('end', () => { outgoing.writeHead(200, {'content-type': 'application/json'}); outgoing.end(body); });
    });
    await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
    const proxy = await startAppsDisabledProxy({fixturePort: upstream.address().port, token, shell});
    try {
      await assert.rejects(exchange(proxy.port, 'tools/list'));
      assert.equal(proxy.failure(), 'APPS_DISABLED_UPSTREAM_RESPONSE_INVALID'); assert.equal(proxy.rows.length, 0);
    } finally { await proxy.close(); upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve)); }
  }
});

test('the thirty-third request exceeds the independent transport budget before forwarding', async () => {
  await fixture(async (fixturePort, received) => {
    const proxy = await startAppsDisabledProxy({fixturePort, token, shell});
    try {
      for (let index = 0; index < 32; ++index) assert.equal((await exchange(proxy.port, 'tools/list')).status, 200);
      assert.equal((await exchange(proxy.port, 'tools/list')).status, 400);
      assert.equal(proxy.failure(), 'APPS_DISABLED_EXCHANGE_BOUND'); assert.equal(received.length, 32); assert.equal(proxy.rows.length, 32);
    } finally { await proxy.close(); }
  });
});

test('preheader sockets cannot exceed the separate 32-connection budget', async () => {
  const proxy = await startAppsDisabledProxy({fixturePort: 1, token, shell});
  const sockets = [];
  const connect = () => new Promise((resolve, reject) => {
    const socket = createConnection({host: '127.0.0.1', port: proxy.port}); sockets.push(socket);
    socket.setTimeout(2000, () => socket.destroy(new Error('TEST_TIMEOUT')));
    socket.once('error', reject); socket.once('connect', () => resolve(socket));
  });
  try {
    for (let index = 0; index < 32; ++index) await connect();
    const excess = await connect();
    await new Promise((resolve, reject) => {
      if (excess.destroyed) resolve(); else { excess.once('close', resolve); excess.once('error', reject); }
    });
    assert.equal(proxy.failure(), 'APPS_DISABLED_CONNECTION_BOUND'); assert.equal(proxy.rows.length, 0);
  } finally { for (const socket of sockets) socket.destroy(); await proxy.close(); }
});
