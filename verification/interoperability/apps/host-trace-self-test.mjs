import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import { createConnection } from 'node:net';
import { test } from 'node:test';
import { APPS_PROXY_FAILURES, MIME, PROTOCOL, UI, UI_URI, adjudicateAppsTrace,
  projectAppsExchange, projectGateRejection, startAppsProxy, validAppsResult } from './host-trace.mjs';

const secret = 'PRIVATE_CANARY_NEVER_PERSIST';
const token = randomBytes(32).toString('hex');
const shell = '<!doctype html><html><body>STATIC_SHELL_NEVER_PERSIST</body></html>';
const metadata = {'io.modelcontextprotocol/serverInfo': {name: 'soklet-apps-fixture', version: 'fixture-v1'}};
const data = {locale: 'en-US', direction: 'ltr', tenant: 'alpha', title: 'Catalog view',
  refreshLabel: 'Refresh catalog', summary: 'Catalog alpha: 1 item.',
  itemLabel: 'Toy <img src=x onerror=alert(1)>', amount: 1234.5, currency: 'USD',
  updatedAt: '2026-09-19T12:00:00Z', timeZone: 'UTC'};
const methods = ['server/discover', 'resources/list', 'tools/list', 'subscriptions/listen',
  'resources/templates/list', 'tools/list', 'show_catalog', 'resources/read', 'refresh_catalog'];
const wireMethod = value => ['show_catalog', 'refresh_catalog'].includes(value) ? 'tools/call' : value;
function result(operation) {
  const base = {resultType: 'complete', _meta: structuredClone(metadata)};
  const catalog = {...base, ttlMs: 0, cacheScope: 'private'};
  if (operation === 'server/discover') return {...catalog, supportedVersions: [PROTOCOL],
    capabilities: {tools: {listChanged: true}, resources: {}, extensions: {[UI]: {mimeTypes: [MIME]}}}};
  if (operation === 'tools/list') return {...catalog, tools: [
    {name: 'show_catalog', title: 'Show catalog', inputSchema: {type: 'object', properties: {}, additionalProperties: false},
      _meta: {ui: {resourceUri: UI_URI, visibility: ['model', 'app']}}},
    {name: 'refresh_catalog', title: 'Refresh catalog', inputSchema: {type: 'object', properties: {}, additionalProperties: false},
      _meta: {ui: {visibility: ['app']}}},
  ]};
  if (operation === 'resources/list') return {...catalog, resources: [
    {uri: UI_URI, name: 'catalog_view', title: 'Catalog view', mimeType: MIME}]};
  if (operation === 'prompts/list') return {...catalog, prompts: []};
  if (operation === 'resources/templates/list') return {...catalog, resourceTemplates: []};
  if (operation === 'resources/read') return {...catalog, contents: [{uri: UI_URI, mimeType: MIME, text: shell,
    _meta: {ui: {csp: {connectDomains: [], resourceDomains: [], frameDomains: [], baseUriDomains: []}, prefersBorder: true}}}]};
  return {...base, _meta: {...metadata, 'example/view': 'catalog-v1'},
    content: [{type: 'text', text: data.summary}], structuredContent: {...data}};
}
function request(operation) {
  const method = wireMethod(operation);
  return {jsonrpc: '2.0', id: secret, method, params: {
    _meta: {'io.modelcontextprotocol/protocolVersion': PROTOCOL,
      'io.modelcontextprotocol/clientCapabilities': {extensions: {
        [UI]: {mimeTypes: [MIME], elicitation: {}}, 'io.modelcontextprotocol/tasks': {}}}, 'example/private': secret},
    ...(method === 'tools/call' ? {name: operation, arguments: {}} : {}),
    ...(method === 'resources/read' ? {uri: UI_URI} : {}),
    ...(method === 'subscriptions/listen' ? {notifications: {toolsListChanged: true}} : {}),
  }};
}
function headers(operation) {
  return {'content-type': 'application/json', authorization: `Bearer ${token}`,
    'mcp-protocol-version': PROTOCOL, 'mcp-method': wireMethod(operation),
    ...(wireMethod(operation) === 'tools/call' ? {'mcp-name': operation} : {}),
    ...(operation === 'resources/read' ? {'mcp-name': UI_URI} : {})};
}
function project(operation, sequence, changes = {}) {
  return projectAppsExchange({request: request(operation),
    response: {jsonrpc: '2.0', id: secret, ...(operation === 'subscriptions/listen'
      ? {error: {code: -32603, message: 'Internal error'}} : {result: result(operation)})},
    headers: headers(operation), status: operation === 'subscriptions/listen' ? 403 : 200,
    responseHeaders: {'content-type': 'application/json', 'cache-control': 'no-store'},
    requestBytes: 250, responseBytes: 400, authorized: true, authorizationForwarded: true, sequence, shell, ...changes});
}
const rows = () => methods.map((operation, index) => project(operation, index + 1));
function sanitized(value) {
  const json = JSON.stringify(value);
  for (const privateValue of [secret, token, shell, data.summary, data.itemLabel, 'Bearer ', 'ui://'])
    assert.equal(json.includes(privateValue), false);
}

test('separate Apps trace accepts the exact catalog/render/refresh profile without raw payloads', () => {
  assert.equal(adjudicateAppsTrace(rows()), 'PASSED');
  rows().forEach(sanitized);
  const reordered = [methods[0], ...methods.slice(1, 6).reverse(), ...methods.slice(6)];
  assert.equal(adjudicateAppsTrace(reordered.map((method, i) => project(method, i + 1))), 'PASSED');
});

test('a single shell prefetch may complete before the initial tool result but never after refresh', () => {
  for (let position = 1; position < 8; ++position) {
    const order = methods.filter(method => method !== 'resources/read');
    order.splice(position, 0, 'resources/read');
    assert.equal(adjudicateAppsTrace(order.map((method, i) => project(method, i + 1))), 'PASSED');
  }
  const wrong = [...methods.slice(0, 7), 'refresh_catalog', 'resources/read'];
  assert.equal(adjudicateAppsTrace(wrong.map((method, i) => project(method, i + 1))), 'FAILED');
});

test('all structural requirements, byte bounds, order and exact exchange counts are mandatory', () => {
  const good = rows();
  for (const [key, value] of Object.entries(good[1])) {
    if (value === true)
      assert.equal(adjudicateAppsTrace(good.map((row, i) => i === 1 ? {...row, [key]: false} : row)), 'FAILED', key);
  }
  for (const change of [{sequence: 0}, {surface: 'web'}, {method: 'initialize'}, {tool: 'refresh_catalog'},
    {responseStatus: 401}, {requestBytes: 0}, {requestBytes: 65537}, {requestBytes: Infinity},
    {responseBytes: 0}, {responseBytes: 1048577}])
    assert.equal(adjudicateAppsTrace(good.map((row, i) => i === 1 ? {...row, ...change} : row)), 'FAILED');
  for (const invalid of [null, Array(9).fill(null), good.slice(1), [...good, good[8]],
    [good[1], good[0], ...good.slice(2)], [...good.slice(0, 6), good[8], good[7], good[6]]])
    assert.equal(adjudicateAppsTrace(invalid), 'FAILED');
  const premature = [methods[0], 'show_catalog', ...methods.slice(1, 6), 'resources/read', 'refresh_catalog'];
  assert.equal(adjudicateAppsTrace(premature.map((method, i) => project(method, i + 1))), 'FAILED');
});

test('result checks reject raw metadata/text, tenant changes, schema changes and unsafe shell metadata', () => {
  for (const operation of methods.filter(method => method !== 'subscriptions/listen'))
    assert.equal(validAppsResult(wireMethod(operation), result(operation), shell), true, operation);
  const mutations = [
    ['show_catalog', value => { value.content.push({type: 'text', text: secret}); }],
    ['show_catalog', value => { value._meta['example/private'] = secret; }],
    ['show_catalog', value => { value.structuredContent.tenant = 'beta'; }],
    ['show_catalog', value => { value.structuredContent.locale = 'ar'; }],
    ['show_catalog', value => { value.content[0].text = secret; }],
    ['show_catalog', value => { value.isError = true; }],
    ['show_catalog', value => { value.extra = secret; }],
    ['tools/list', value => { value.tools[0]._meta.ui.resourceUri = 'ui://attacker/view'; }],
    ['tools/list', value => { value.tools[1]._meta.ui.visibility = ['app', 'model']; }],
    ['tools/list', value => { value.tools[1]._meta.ui.resourceUri = UI_URI; }],
    ['tools/list', value => { value.tools[0].inputSchema.additionalProperties = true; }],
    ['tools/list', value => { value.tools.push(value.tools[0]); }],
    ['resources/read', value => { value.contents[0].text += secret; }],
    ['resources/read', value => { value.contents[0].mimeType = 'text/html'; }],
    ['resources/read', value => { value.contents[0]._meta.ui.csp.connectDomains = ['https://example.org']; }],
    ['resources/read', value => { value.contents[0]._meta.ui.permissions = {camera: {}}; }],
    ['resources/read', value => { value.contents[0]._meta.ui.prefersBorder = false; }],
    ['resources/list', value => { value.resources = []; }],
    ['prompts/list', value => { value.prompts.push({name: secret}); }],
    ['resources/templates/list', value => { value.resourceTemplates.push({uriTemplate: secret}); }],
    ['server/discover', value => { delete value.capabilities.extensions; }],
    ['server/discover', value => { value.capabilities.extensions[UI].mimeTypes = ['text/html']; }],
    ['server/discover', value => { value.capabilities.extensions['io.modelcontextprotocol/skills'] = {}; }],
  ];
  for (const [operation, mutate] of mutations) {
    const changed = result(operation); mutate(changed);
    assert.equal(validAppsResult(wireMethod(operation), changed, shell), false, operation);
    const row = project(operation, 1, {response: {jsonrpc: '2.0', id: secret, result: changed}});
    assert.equal(row.resultMatchesFixture, false); sanitized(row);
  }
  for (const operation of methods.filter(method => method !== 'subscriptions/listen')) {
    const changed = result(operation); changed.resultType = 'task';
    assert.equal(validAppsResult(wireMethod(operation), changed, shell), false);
  }
});

test('opt-in geolocation metadata must be exact and is not accepted in the default profile', () => {
  const resource = result('resources/read');
  resource.contents[0]._meta.ui.permissions = {geolocation: {}};
  assert.equal(validAppsResult('resources/read', resource, shell, undefined, 'alpha', true), true);
  assert.equal(validAppsResult('resources/read', resource, shell), false);
  assert.equal(project('resources/read', 1, {geolocation: true,
    response: {jsonrpc: '2.0', id: secret, result: resource}}).resultMatchesFixture, true);
  for (const invalid of [{camera: {}}, {geolocation: true}, {geolocation: {}, microphone: {}}]) {
    const altered = structuredClone(resource);
    altered.contents[0]._meta.ui.permissions = invalid;
    assert.equal(validAppsResult('resources/read', altered, shell, undefined, 'alpha', true), false);
  }
});

test('transition projection validates beta data and a content-free denied refresh without storing either body', () => {
  const beta = result('refresh_catalog');
  beta.content[0].text = 'Catálogo beta: 1 item.';
  Object.assign(beta.structuredContent, {locale: 'pt-BR', tenant: 'beta', title: 'Catálogo',
    refreshLabel: 'Atualizar catálogo', summary: 'Catálogo beta: 1 item.',
    itemLabel: 'Brinquedo <img src=x onerror=alert(1)>'});
  assert.equal(validAppsResult('tools/call', beta, shell, undefined, 'beta'), true);
  assert.equal(validAppsResult('tools/call', beta, shell), false);
  const betaRow = project('refresh_catalog', 10, {callerPhase: 'beta',
    response: {jsonrpc: '2.0', id: secret, result: beta}});
  assert.equal(betaRow.resultMatchesFixture, true); sanitized(betaRow);
  const denied = {jsonrpc: '2.0', id: secret,
    error: {code: -32602, message: 'Invalid params'}};
  const deniedRow = project('refresh_catalog', 11, {callerPhase: 'denied', status: 400, response: denied});
  assert.equal(deniedRow.responseEnvelopeValid, true);
  assert.equal(deniedRow.resultMatchesFixture, true); sanitized(deniedRow);
  for (const error of [{...denied.error, data: secret}, {code: -32602, message: secret},
    {code: -32601, message: 'Method not found'}]) {
    const changed = project('refresh_catalog', 11, {callerPhase: 'denied', status: 400,
      response: {...denied, error}});
    assert.equal(changed.resultMatchesFixture, false);
  }
});

test('revocation projection requires exact 401 authentication errors for refresh and subscription retry', () => {
  const response = {jsonrpc: '2.0', id: secret,
    error: {code: -31901, message: 'Authentication required.'}};
  for (const operation of ['refresh_catalog', 'subscriptions/listen']) {
    const valid = project(operation, 13, {callerPhase: 'revoked', status: 401, response});
    assert.equal(valid.responseEnvelopeValid, true);
    assert.equal(valid.resultMatchesFixture, true);
    assert.equal(valid.subscriptionDenied, false);
    sanitized(valid);
    for (const error of [{...response.error, data: secret},
      {code: -31901, message: secret}, {code: -32602, message: 'Invalid params'}]) {
      const changed = project(operation, 13, {callerPhase: 'revoked', status: 401,
        response: {...response, error}});
      assert.equal(changed.resultMatchesFixture, false);
    }
  }
});

test('request projection rejects wrong Apps shape, unexpected arguments, headers and error envelopes', () => {
  for (const ui of [{mimeTypes: [MIME]}, {mimeTypes: ['text/html'], elicitation: {}},
    {mimeTypes: [MIME], elicitation: {}, extra: true}]) {
    const changed = request('show_catalog'); changed.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions[UI] = ui;
    assert.equal(project('show_catalog', 1, {request: changed}).appsMimeMatches, false);
  }
  const skills = request('show_catalog');
  skills.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions['io.modelcontextprotocol/skills'] = {};
  assert.equal(project('show_catalog', 1, {request: skills}).skillsAbsent, false);
  const spoofed = request('refresh_catalog'); spoofed.params.arguments.tenant = secret;
  assert.equal(project('refresh_catalog', 1, {request: spoofed}).requestSelectionValid, false);
  assert.equal(project('show_catalog', 1, {headers: {...headers('show_catalog'), 'mcp-name': secret}}).nameHeaderMatches, false);
  assert.equal(project('show_catalog', 1, {response: {jsonrpc: '2.0', id: secret,
    error: {message: secret}, result: result('show_catalog')}}).responseEnvelopeValid, false);
});

test('only the exact bounded deny-all tools subscription response qualifies as an expected rejection', () => {
  for (const response of [
    {jsonrpc: '2.0', id: secret, result: {resultType: 'complete'}},
    {jsonrpc: '2.0', id: secret, error: {code: -32603, message: secret}},
    {jsonrpc: '2.0', id: secret, error: {code: -32603, message: 'Internal error', data: secret}},
    {jsonrpc: '2.0', id: secret, error: {code: -32601, message: 'Internal error'}},
  ]) {
    const changed = rows(); changed[3] = project('subscriptions/listen', 4, {response});
    assert.equal(adjudicateAppsTrace(changed), 'FAILED'); sanitized(changed);
  }
  for (const change of [{responseStatus: 200}, {responseJson: false}, {subscriptionDenied: false},
    {responseStatus: 500}, {responseEnvelopeValid: false}, {resultMatchesFixture: false}]) {
    const changed = rows(); changed[3] = {...changed[3], ...change};
    assert.equal(adjudicateAppsTrace(changed), 'FAILED');
  }
  const wrong = request('subscriptions/listen'); wrong.params.notifications.resourcesListChanged = true;
  assert.equal(project('subscriptions/listen', 4, {request: wrong}).requestSelectionValid, false);
});

function exchange(port, operation, options = {}) {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({host: '127.0.0.1', port, method: options.method ?? 'POST',
      path: options.path ?? '/mcp', headers: {...headers(operation), ...options.headers}}, incoming => {
      const chunks = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.once('error', reject);
      incoming.once('end', () => resolve({status: incoming.statusCode, body: Buffer.concat(chunks).toString('utf8')}));
    });
    outgoing.setTimeout(2500, () => outgoing.destroy(new Error('TEST_TIMEOUT')));
    outgoing.once('error', reject);
    outgoing.end(options.body ?? JSON.stringify(request(operation)));
  });
}

test('loopback gate forwards the same authorization to /apps and drops other credentials', async () => {
  const received = [];
  const upstream = createServer((incoming, outgoing) => {
    const chunks = [];
    incoming.on('data', chunk => chunks.push(chunk));
    incoming.once('end', () => {
      const bytes = Buffer.concat(chunks); const message = JSON.parse(bytes.toString('utf8'));
      received.push({bytes, path: incoming.url, headers: incoming.headers});
      outgoing.writeHead(incoming.headers.authorization !== `Bearer ${token}` ? 401
        : message.method === 'subscriptions/listen' ? 403 : 200,
        {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end(JSON.stringify({jsonrpc: '2.0', id: message.id,
        ...(message.method === 'subscriptions/listen' ? {error: {code: -32603, message: 'Internal error'}}
          : {result: result(message.method === 'tools/call' ? message.params.name : message.method)})}));
    });
  });
  await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
  const fixturePort = upstream.address().port;
  const proxy = await startAppsProxy({fixturePort, token, shell});
  try {
    for (const method of methods) {
      assert.equal((await exchange(proxy.port, method, {headers: {cookie: secret,
        'proxy-authorization': secret, 'x-api-key': secret}})).status, method === 'subscriptions/listen' ? 403 : 200);
      const latest = received.at(-1);
      assert.equal(latest.path, '/apps');
      assert.equal(latest.headers.authorization, `Bearer ${token}`);
      assert.equal(latest.bytes.toString('utf8'), JSON.stringify(request(method)));
      for (const field of ['cookie', 'proxy-authorization', 'x-api-key']) assert.equal(latest.headers[field], undefined);
    }
    assert.equal(proxy.failure(), undefined);
    assert.equal(adjudicateAppsTrace(proxy.rows), 'PASSED');
    proxy.rows.forEach(sanitized);
  } finally { await proxy.close(); upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve)); }
});

test('gate rejects wrong path, origin, method, credentials, modern metadata, header and selection before forwarding', async () => {
  const negatives = [
    ['tools/list', {method: 'GET'}, 'APPS_POST_ONLY'],
    ['tools/list', {path: '/apps'}, 'APPS_PATH'],
    ['tools/list', {path: '/mcp?secret=value'}, 'APPS_PATH'],
    ['tools/list', {headers: {authorization: `Bearer ${secret}`}}, 'APPS_AUTH_REQUIRED'],
    ['tools/list', {headers: {origin: 'https://untrusted.example'}}, 'APPS_ORIGIN'],
    ['initialize', {}, 'APPS_UNEXPECTED_METHOD'],
    ['prompts/list', {}, 'APPS_UNEXPECTED_METHOD'],
    ['subscriptions/listen', {body: JSON.stringify({...request('subscriptions/listen'), params: {
      ...request('subscriptions/listen').params, notifications: {resourcesListChanged: true}}})}, 'APPS_REQUEST_SELECTION'],
    ['skills/list', {}, 'APPS_UNEXPECTED_METHOD'],
    ['tools/list', {headers: {'mcp-protocol-version': '2025-11-25'}}, 'APPS_MODERN_PROTOCOL_REQUIRED'],
    ['tools/list', {headers: {'mcp-method': 'tools/call'}}, 'APPS_METHOD_HEADER'],
    ['tools/list', {headers: {'mcp-name': 'show_catalog'}}, 'APPS_NAME_HEADER'],
    ['tools/list', {body: '{broken'}, 'APPS_REQUEST_INVALID'],
    ['show_catalog', {body: JSON.stringify({...request('show_catalog'), params: {
      ...request('show_catalog').params, arguments: {tenant: secret}}})}, 'APPS_REQUEST_SELECTION'],
    ['resources/read', {body: JSON.stringify({...request('resources/read'), params: {
      ...request('resources/read').params, uri: 'ui://other/view'}})}, 'APPS_REQUEST_SELECTION'],
  ];
  let received = 0;
  const upstream = createServer((incoming, outgoing) => { ++received; incoming.resume(); outgoing.end('{}'); });
  await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
  try {
    for (const [method, options, code] of negatives) {
      const proxy = await startAppsProxy({fixturePort: upstream.address().port, token, shell});
      try {
        assert.equal((await exchange(proxy.port, method, options)).status, 400);
        assert.equal(proxy.failure(), code); assert.equal(proxy.rows.length, 0); assert.equal(received, 0);
        assert.equal(APPS_PROXY_FAILURES.includes(proxy.failure()), true); sanitized(proxy.rows);
        assert.equal(proxy.rejections.length, 1); assert.equal(proxy.rejections[0].code, code); sanitized(proxy.rejections);
      } finally { await proxy.close(); }
    }
  } finally { upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve)); }
});

test('gate rejection diagnostics retain only reviewed enums, never raw methods, paths or headers', () => {
  assert.deepEqual(projectGateRejection({method: 'GET', url: '/.well-known/oauth-protected-resource/mcp', headers: {}},
    'APPS_POST_ONLY', 1), {sequence: 1, method: 'GET', path: 'OAUTH_PROTECTED_RESOURCE_PATH',
    code: 'APPS_POST_ONLY', originAbsent: true});
  const redacted = projectGateRejection({method: secret, url: '/' + secret,
    headers: {authorization: `Bearer ${token}`, origin: secret}}, secret, 1);
  assert.deepEqual(redacted, {sequence: 1, method: 'OTHER', path: 'OTHER', code: 'APPS_REQUEST_INVALID', originAbsent: false});
  sanitized(redacted);
  assert.equal(projectGateRejection({method: 'POST', url: '/register', headers: {}}, 'APPS_PATH', 1).path,
    'OAUTH_REGISTRATION_ROOT');
});

test('invalid proxy settings cannot open an unrestricted listener', async () => {
  for (const change of [{fixturePort: 0}, {fixturePort: 65536}, {token: secret}, {shell: ''},
    {shell: 'x'.repeat(524289)}, {allowedOrigin: '*'}, {allowedOrigin: 'http://localhost:8080'},
    {allowedOrigin: 'http://127.0.0.1:65536'}, {allowedOrigin: 'http://127.0.0.1:8080/path'}])
    await assert.rejects(startAppsProxy({fixturePort: 1, token, shell, ...change}), /APPS_INVALID_PROXY_CONFIGURATION/);
});

test('malformed/oversized upstream bodies and oversized requests never become trace success', async () => {
  let mode = 'invalid'; let received = 0;
  const upstream = createServer((incoming, outgoing) => {
    ++received; incoming.resume(); incoming.once('end', () => {
      outgoing.writeHead(200, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end(mode === 'invalid' ? secret : 'x'.repeat(1048577));
    });
  });
  await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
  try {
    for (mode of ['invalid', 'oversized']) {
      const proxy = await startAppsProxy({fixturePort: upstream.address().port, token, shell});
      try {
        await assert.rejects(exchange(proxy.port, 'tools/list'));
        assert.equal(proxy.failure(), 'APPS_UPSTREAM_RESPONSE_INVALID'); assert.equal(proxy.rows.length, 0);
      } finally { await proxy.close(); }
    }
    const before = received;
    const proxy = await startAppsProxy({fixturePort: upstream.address().port, token, shell});
    try {
      await exchange(proxy.port, 'tools/list', {body: 'x'.repeat(65537)}).catch(() => {});
      assert.equal(proxy.failure(), 'APPS_BODY_BOUND'); assert.equal(proxy.rows.length, 0); assert.equal(received, before);
    } finally { await proxy.close(); }
  } finally { upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve)); }
});

test('exchange budget rejects the thirty-third request without forwarding', async () => {
  let received = 0;
  const upstream = createServer((incoming, outgoing) => {
    ++received; incoming.resume(); incoming.once('end', () => {
      outgoing.writeHead(200, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end(JSON.stringify({jsonrpc: '2.0', id: secret, result: result('tools/list')}));
    });
  });
  await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
  const proxy = await startAppsProxy({fixturePort: upstream.address().port, token, shell});
  try {
    for (let i = 0; i < 32; ++i) assert.equal((await exchange(proxy.port, 'tools/list')).status, 200);
    assert.equal((await exchange(proxy.port, 'tools/list')).status, 400);
    assert.equal(proxy.failure(), 'APPS_EXCHANGE_BOUND'); assert.equal(proxy.rows.length, 32); assert.equal(received, 32);
  } finally { await proxy.close(); upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve)); }
});

test('preheader connection budget is finite independently of completed request counts', async () => {
  const proxy = await startAppsProxy({fixturePort: 1, token, shell});
  const sockets = [];
  const connect = () => new Promise((resolve, reject) => {
    const socket = createConnection({host: '127.0.0.1', port: proxy.port});
    sockets.push(socket);
    socket.setTimeout(2000, () => socket.destroy(new Error('TEST_TIMEOUT')));
    socket.once('error', reject);
    socket.once('connect', () => resolve(socket));
  });
  try {
    for (let index = 0; index < 32; ++index) await connect();
    const excess = await connect();
    await new Promise((resolve, reject) => {
      if (excess.destroyed) resolve();
      else { excess.once('close', resolve); excess.once('error', reject); }
    });
    assert.equal(proxy.failure(), 'APPS_CONNECTION_BOUND');
    assert.equal(proxy.rows.length, 0);
  } finally { for (const socket of sockets) socket.destroy(); await proxy.close(); }
});
