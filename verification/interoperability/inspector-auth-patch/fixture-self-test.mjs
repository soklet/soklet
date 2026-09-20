import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { createConnection } from 'node:net';
import { test } from 'node:test';
import { fixtureResult, MAX_BODY, MAX_CONNECTIONS, MAX_REQUESTS, MODES, PROTOCOL,
  projectRejection, startFixture } from './fixture.mjs';

const token = randomBytes(32).toString('hex');
const secret = 'PRIVATE_CANARY_NEVER_RETAIN';
const meta = {'io.modelcontextprotocol/protocolVersion': PROTOCOL,
  'io.modelcontextprotocol/clientCapabilities': {}, 'example/private': secret};
function rpc(method = 'server/discover', changes = {}) {
  return {jsonrpc: '2.0', id: secret, method, params: {_meta: structuredClone(meta),
    ...(method === 'subscriptions/listen' ? {notifications: {toolsListChanged: true}} : {})}, ...changes};
}
function send(fixture, request = rpc(), changes = {}) {
  const body = changes.body ?? JSON.stringify(request);
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({host: '127.0.0.1', port: fixture.port, method: changes.method ?? 'POST',
      path: changes.path ?? '/mcp', agent: false,
      headers: {'content-type': 'application/json', 'content-length': Buffer.byteLength(body),
        authorization: `Bearer ${token}`, 'mcp-protocol-version': PROTOCOL,
        'mcp-method': request.method, ...changes.headers}}, incoming => {
      const chunks = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.once('end', () => resolve({status: incoming.statusCode, headers: incoming.headers,
        body: JSON.parse(Buffer.concat(chunks).toString('utf8'))}));
      incoming.once('error', reject);
    });
    outgoing.setTimeout(2000, () => outgoing.destroy(new Error('TEST_TIMEOUT')));
    outgoing.once('error', reject);
    outgoing.end(body);
  });
}
async function using(mode, callback) {
  const fixture = await startFixture({token, mode});
  try { return await callback(fixture); } finally { await fixture.close(); }
}
function sanitized(fixture) {
  const value = JSON.stringify({rows: fixture.rows, rejections: fixture.rejections, failure: fixture.failure()});
  for (const privateValue of [secret, token, 'Bearer ', 'example/private', 'Authorization'])
    assert.equal(value.includes(privateValue), false);
}

test('fixture configuration only permits five explicit causal/challenge cases and disposable token shape', async () => {
  assert.deepEqual(MODES, ['policy-403', 'no-subscription', 'jsonrpc-200-control',
    'auth-401-invalid-token', 'auth-403-insufficient-scope']);
  for (const options of [undefined, {}, {token, mode: 'PASS'}, {token: '', mode: MODES[0]},
    {token: token.toUpperCase(), mode: MODES[0]}, {token: `${token}${secret}`, mode: MODES[0]}])
    await assert.rejects(startFixture(options), /AUTH_INVALID_CONFIGURATION/);
});

test('loopback discovery and a single catalog tool have exact modern envelopes and no Apps capability', async () => {
  for (const mode of MODES) await using(mode, async fixture => {
    for (const method of ['server/discover', 'tools/list']) {
      const response = await send(fixture, rpc(method));
      assert.equal(response.status, 200);
      assert.equal(response.headers['cache-control'], 'no-store');
      assert.equal(response.headers['mcp-session-id'], undefined);
      assert.equal(response.headers['www-authenticate'], undefined);
      assert.deepEqual(response.body, {jsonrpc: '2.0', id: secret, result: fixtureResult(method, mode)});
    }
    const discovery = fixtureResult('server/discover', mode);
    assert.deepEqual(discovery.capabilities, {tools: {listChanged: mode !== 'no-subscription'}});
    assert.equal(fixture.failure(), undefined);
    assert.deepEqual(fixture.rows.map(row => row.requestSequence), [1, 2]);
    assert.ok(fixture.rows.every(row => row.surface === 'inspector-auth-patch'
      && row.authChallenge === 'none' && row.wwwAuthenticatePresent === false
      && row.requiredScopeCatalogRead === false));
    sanitized(fixture);
  });
});

test('HTTP403 denial and HTTP200 causal control carry the identical JSON-RPC error', async () => {
  for (const mode of ['policy-403', 'jsonrpc-200-control']) await using(mode, async fixture => {
    const response = await send(fixture, rpc('subscriptions/listen'));
    assert.equal(response.status, mode === 'policy-403' ? 403 : 200);
    assert.deepEqual(response.body, {jsonrpc: '2.0', id: secret, error: {code: -32603, message: 'Internal error'}});
    assert.equal(response.headers['www-authenticate'], undefined);
    assert.equal(fixture.rows[0].subscriptionDenied, true);
    assert.equal(fixture.rows[0].authChallenge, 'none');
    assert.equal(fixture.rows[0].wwwAuthenticatePresent, false);
    assert.equal(fixture.rows[0].requiredScopeCatalogRead, false);
    assert.equal(fixture.failure(), undefined);
    sanitized(fixture);
  });
});

test('positive controls emit exact Bearer challenges only on admitted subscriptions', async () => {
  const cases = [
    ['auth-401-invalid-token', 401, 'invalid_token', 'Bearer error="invalid_token"', false],
    ['auth-403-insufficient-scope', 403, 'insufficient_scope',
      'Bearer error="insufficient_scope", scope="catalog.read"', true],
  ];
  for (const [mode, status, reason, header, requiresScope] of cases) await using(mode, async fixture => {
    const response = await send(fixture, rpc('subscriptions/listen'));
    assert.equal(response.status, status);
    assert.equal(response.headers['www-authenticate'], header);
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.equal(response.headers['mcp-session-id'], undefined);
    assert.deepEqual(response.body, {jsonrpc: '2.0', id: secret, error: {code: -32603, message: 'Internal error'}});
    assert.deepEqual(fixture.rows.map(row => ({status: row.responseStatus, reason: row.authChallenge,
      challenged: row.wwwAuthenticatePresent, requiresScope: row.requiredScopeCatalogRead,
      denied: row.subscriptionDenied, authorized: row.authorized})),
      [{status, reason, challenged: true, requiresScope, denied: true, authorized: true}]);
    assert.equal(fixture.failure(), undefined);
    sanitized(fixture);
  });
});

test('positive controls do not bypass token admission or turn request failures into simulated challenges', async () => {
  for (const mode of ['auth-401-invalid-token', 'auth-403-insufficient-scope']) await using(mode, async fixture => {
    const response = await send(fixture, rpc('subscriptions/listen'), {headers: {authorization: `Bearer ${secret}`}});
    assert.equal(response.status, 401);
    assert.equal(response.headers['www-authenticate'], undefined);
    assert.deepEqual(response.body, {error: 'Fixture request refused'});
    assert.equal(fixture.failure(), 'AUTH_REQUIRED');
    assert.equal(fixture.rows.length, 0);
    sanitized(fixture);
  });
});

test('no-subscription control fails closed on an unexpected subscription', async () => {
  await using('no-subscription', async fixture => {
    assert.equal((await send(fixture, rpc('subscriptions/listen'))).status, 400);
    assert.equal(fixture.failure(), 'AUTH_SELECTION');
    assert.equal(fixture.rows.length, 0);
    sanitized(fixture);
  });
});

test('OAuth discovery and registration stop locally with fixed path enums in every mode, even without credentials', async () => {
  for (const mode of MODES) await using(mode, async fixture => {
    const paths = [['/.well-known/oauth-protected-resource/mcp', 'OAUTH_PROTECTED_RESOURCE_PATH'],
      ['/.well-known/oauth-protected-resource', 'OAUTH_PROTECTED_RESOURCE_ROOT'],
      ['/.well-known/oauth-authorization-server', 'OAUTH_AUTHORIZATION_SERVER_ROOT'],
      ['/.well-known/openid-configuration', 'OPENID_CONFIGURATION_ROOT'],
      ['/register', 'OAUTH_REGISTRATION_ROOT']];
    for (const [path] of paths) {
      const response = await send(fixture, rpc(), {path, method: path === '/register' ? 'POST' : 'GET',
        headers: {authorization: ''}, body: path === '/register' ? JSON.stringify({client_name: secret}) : ''});
      assert.equal(response.status, 400);
      assert.deepEqual(response.body, {error: 'Fixture request refused'});
    }
    assert.deepEqual(fixture.rejections.map(row => row.path), paths.map(([, path]) => path));
    assert.ok(fixture.rejections.every(row => row.code === 'AUTH_OAUTH_REFUSED'));
    assert.equal(fixture.failure(), undefined);
    await send(fixture);
    assert.equal(fixture.rows[0].requestSequence, 6);
    sanitized(fixture);
  });
});

test('private paths, methods and error text cannot enter the rejection projection', () => {
  assert.deepEqual(projectRejection({url: `/${secret}`, method: secret, headers: {origin: secret}}, secret, 1, 2),
    {sequence: 1, requestSequence: 2, method: 'OTHER', path: 'OTHER', code: 'AUTH_REQUEST_INVALID', originAbsent: false});
});

test('wrong bearer, host, origin, paths, methods, framing and unsupported calls fail closed', async () => {
  const cases = [
    [{headers: {authorization: `Bearer ${secret}`}}, 'AUTH_REQUIRED', 401],
    [{headers: {authorization: `bearer ${token}`}}, 'AUTH_REQUIRED', 401],
    [{headers: {host: 'example.invalid'}}, 'AUTH_HOST'],
    [{headers: {origin: 'http://example.invalid'}}, 'AUTH_ORIGIN'],
    [{path: `/mcp?${secret}`}, 'AUTH_PATH'],
    [{path: '/register', method: 'GET'}, 'AUTH_PATH'],
    [{method: 'GET'}, 'AUTH_METHOD'],
    [{headers: {'content-type': 'text/plain'}}, 'AUTH_HEADERS'],
    [{headers: {'mcp-session-id': secret}}, 'AUTH_HEADERS'],
    [{headers: {'mcp-name': secret}}, 'AUTH_HEADERS'],
    [{headers: {'mcp-method': secret}}, 'AUTH_HEADERS'],
    [{headers: {'mcp-protocol-version': secret}}, 'AUTH_PROTOCOL'],
    [{body: secret}, 'AUTH_REQUEST_INVALID'],
    [{request: rpc('initialize')}, 'AUTH_METHOD'],
    [{request: rpc('tools/call')}, 'AUTH_METHOD'],
    [{request: rpc('server/discover', {id: secret.repeat(20)})}, 'AUTH_REQUEST_INVALID'],
    [{request: rpc('server/discover', {params: {_meta: {...meta, 'io.modelcontextprotocol/clientCapabilities': null}}})}, 'AUTH_PROTOCOL'],
    [{request: rpc('server/discover', {params: {_meta: meta, cursor: secret}})}, 'AUTH_SELECTION'],
    [{request: rpc('subscriptions/listen', {params: {_meta: meta, notifications: {toolsListChanged: false}}})}, 'AUTH_SELECTION'],
  ];
  for (const [changes, expected, status = 400] of cases) await using('policy-403', async fixture => {
    const response = await send(fixture, changes.request ?? rpc(), changes);
    assert.equal(response.status, status, expected);
    assert.equal(fixture.failure(), expected);
    assert.equal(fixture.rows.length, 0);
    sanitized(fixture);
  });
});

test('bodies are bounded before parsing and OAuth registration uses the same bound', async () => {
  for (const path of ['/mcp', '/register']) await using('policy-403', async fixture => {
    const response = await send(fixture, rpc(), {path, body: 'x'.repeat(MAX_BODY + 1)});
    assert.equal(response.status, 400);
    assert.equal(fixture.failure(), 'AUTH_BODY_BOUND');
    assert.equal(fixture.rows.length, 0);
  });
});

test('chunked bodies cannot bypass the byte bound', async () => {
  await using('policy-403', async fixture => {
    const socket = createConnection({host: '127.0.0.1', port: fixture.port});
    socket.setTimeout(2000, () => socket.destroy(new Error('TEST_TIMEOUT')));
    const closed = new Promise((resolve, reject) => {
      socket.once('close', resolve);
      socket.once('error', error => error.code === 'ECONNRESET' ? resolve() : reject(error));
    });
    socket.resume();
    socket.write(`POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${fixture.port}\r\nTransfer-Encoding: chunked\r\n\r\n`);
    socket.write(`${(MAX_BODY + 1).toString(16)}\r\n${'x'.repeat(MAX_BODY + 1)}\r\n0\r\n\r\n`);
    await closed;
    assert.equal(fixture.failure(), 'AUTH_BODY_BOUND');
    assert.equal(fixture.rows.length, 0);
  });
});

test('connections above the simultaneous bound are dropped, and all accepted sockets close', async () => {
  const fixture = await startFixture({token, mode: 'policy-403'});
  const sockets = [];
  try {
    for (let i = 0; i < MAX_CONNECTIONS; ++i) {
      const socket = createConnection({host: '127.0.0.1', port: fixture.port});
      sockets.push(socket);
      await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
    }
    const extra = createConnection({host: '127.0.0.1', port: fixture.port});
    sockets.push(extra);
    extra.setTimeout(2000, () => extra.destroy(new Error('TEST_TIMEOUT')));
    await new Promise((resolve, reject) => {
      extra.once('close', resolve);
      extra.once('error', error => error.code === 'ECONNRESET' ? resolve() : reject(error));
    });
    assert.equal(fixture.failure(), 'AUTH_CONNECTION_BOUND');
    assert.equal(fixture.rows.length, 0);
    const closed = sockets.filter(socket => !socket.destroyed).map(socket =>
      new Promise(resolve => socket.once('close', resolve)));
    await fixture.close();
    await Promise.all(closed);
  } finally {
    for (const socket of sockets) socket.destroy();
    await fixture.close();
  }
});

test('the total request limit includes OAuth and does not permit unbounded transcript retention', async () => {
  await using('policy-403', async fixture => {
    for (let i = 0; i < MAX_REQUESTS; ++i) {
      const response = await send(fixture, rpc(), {path: '/.well-known/oauth-protected-resource', method: 'GET', body: ''});
      assert.equal(response.status, 400);
    }
    assert.equal(fixture.failure(), undefined);
    await send(fixture);
    assert.equal(fixture.failure(), 'AUTH_REQUEST_BOUND');
    assert.equal(fixture.rejections.length, MAX_REQUESTS);
    assert.equal(fixture.rows.length, 0);
    sanitized(fixture);
  });
});

test('close destroys an incomplete request and is idempotent', async () => {
  const fixture = await startFixture({token, mode: 'policy-403'});
  const socket = createConnection({host: '127.0.0.1', port: fixture.port});
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  socket.write(`POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:${fixture.port}\r\nContent-Length: 40\r\n\r\n{`);
  const closed = new Promise(resolve => socket.once('close', resolve));
  await fixture.close();
  await closed;
  await fixture.close();
});
