#!/usr/bin/env node

import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import {
  PROTOCOL, SKILLS, UI, adjudicateTrace, projectExchange, startProxy, validResult,
} from './trace.mjs';

const secret = 'SECRET_TOKEN_DO_NOT_PERSIST';
const toolNames = [
  'json_schema_2020_12_tool', 'test_audio_content', 'test_custom_header',
  'test_embedded_resource', 'test_error_handling', 'test_image_content',
  'test_multiple_content_types', 'test_simple_text', 'test_tool_with_progress',
];
const token = randomBytes(32).toString('hex');

function capabilities(enabled) {
  return enabled ? { extensions: {
    [UI]: { mimeTypes: ['text/html;profile=mcp-app'] }, [SKILLS]: {},
  } } : {};
}

function body(method, enabled, id = 'probe') {
  return JSON.stringify({
    jsonrpc: '2.0', id, method,
    params: {
      _meta: {
        'io.modelcontextprotocol/protocolVersion': PROTOCOL,
        'io.modelcontextprotocol/clientCapabilities': capabilities(enabled),
      },
      ...(method === 'tools/call' ? { name: 'test_simple_text', arguments: {} } : {}),
    },
  });
}

function result(method) {
  if (method === 'server/discover')
    return { resultType: 'complete', supportedVersions: [PROTOCOL],
      capabilities: { tools: {}, prompts: {}, resources: {}, completions: {} } };
  if (method === 'tools/list')
    return { resultType: 'complete', tools: toolNames.map(name => ({ name })) };
  return { resultType: 'complete', content: [
    { type: 'text', text: 'This is a simple text response for testing.' },
  ] };
}

function response(method, id = 'probe') {
  return { jsonrpc: '2.0', id, result: result(method) };
}

function wireHeaders(method, credential = token, extra = {}) {
  return {
    Authorization: `Bearer ${credential}`,
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    'MCP-Protocol-Version': PROTOCOL,
    'Mcp-Method': method,
    ...extra,
  };
}

function exchange(port, method, payload, headers, path = '/mcp') {
  return new Promise((resolveExchange, rejectExchange) => {
    const request = httpRequest({ host: '127.0.0.1', port, method, path, headers },
      (incoming) => {
        const chunks = [];
        incoming.on('data', chunk => chunks.push(chunk));
        incoming.once('error', rejectExchange);
        incoming.once('end', () => resolveExchange({
          status: incoming.statusCode,
          body: Buffer.concat(chunks).toString('utf8'),
        }));
      });
    request.setTimeout(2000, () => request.destroy(new Error('TEST_SOCKET_TIMEOUT')));
    request.once('error', rejectExchange);
    request.end(payload);
  });
}

async function listen(server) {
  await new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  return server.address().port;
}

async function closeServer(server) {
  server.closeAllConnections();
  await new Promise(resolveClose => server.close(resolveClose));
}

function assertSanitized(row) {
  const text = JSON.stringify(row);
  assert.doesNotMatch(text, /SECRET_TOKEN|Bearer|authorization|cookie|x-api-key/i);
  assert.deepEqual(Object.keys(row).filter(key => /header|body|argument|metadata/i.test(key)),
    ['protocolMetadataMatches', 'protocolHeaderMatches', 'methodHeaderMatches']);
}

function pureProjectionCases() {
  assert.equal(validResult('server/discover', result('server/discover')), true);
  assert.equal(validResult('tools/list', result('tools/list')), true);
  assert.equal(validResult('tools/call', result('tools/call')), true);
  assert.equal(validResult('tools/list', { resultType: 'complete', tools: [] }), false);
  assert.equal(validResult('server/discover', { ...result('server/discover'),
    supportedVersions: ['2025-11-25'] }), false);
  assert.equal(validResult('tools/call', { resultType: 'complete', content: [
    { type: 'text', text: secret },
  ] }), false);
  assert.equal(validResult('tools/call', null), false);

  const rows = [];
  for (const [index, method] of ['server/discover', 'tools/list',
    'tools/list'].entries()) {
    const request = JSON.parse(body(method, true, `id-${index}`));
    request.params._meta.privateValue = secret;
    request.params.context = { arguments: { privateValue: secret } };
    const projected = projectExchange({
      request, response: { ...response(method, `id-${index}`), _meta: { secret } },
      headers: {
        ...Object.fromEntries(Object.entries(wireHeaders(method))
          .map(([name, value]) => [name.toLowerCase(), value])),
        cookie: secret, 'x-api-key': secret,
      },
      status: 200,
      responseHeaders: { 'content-type': 'application/json', 'cache-control': 'no-store' },
      requestBytes: 111, responseBytes: 222, authorized: true, enabled: true,
      sequence: index + 1,
    });
    assertSanitized(projected);
    assert.equal(projected.noSessionState, true);
    assert.equal(projected.extensionSelectionMatches, true);
    rows.push(projected);
  }
  assert.equal(adjudicateTrace(rows, true, 'tools/list'), 'PASSED');
  const call = projectExchange({
    request: JSON.parse(body('tools/call', true, 'id-3')),
    response: response('tools/call', 'id-3'),
    headers: {
      'mcp-protocol-version': PROTOCOL, 'mcp-method': 'tools/call',
    },
    status: 200,
    responseHeaders: { 'content-type': 'application/json', 'cache-control': 'no-store' },
    requestBytes: 333, responseBytes: 444, authorized: true, enabled: true,
    sequence: 4,
  });
  assertSanitized(call);
  assert.equal(adjudicateTrace([...rows, call], true, 'tools/call'), 'PASSED');
  assert.equal(adjudicateTrace(rows, true, 'tools/call'), 'FAILED');
  assert.equal(adjudicateTrace([rows[0], rows[1]], true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([...rows, { ...rows[2], sequence: 4 }],
    true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([rows[1], rows[0], rows[2]],
    true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([rows[0], rows[1], { ...rows[2], sequence: 4 }],
    true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([rows[0], rows[1], call],
    true, 'tools/call'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...rows[0], responseCorrelated: false },
    rows[1], rows[2]],
    true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...rows[0], noSessionState: false },
    rows[1], rows[2]],
    true, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...rows[0], method: 'initialize' },
    rows[1], rows[2]],
    true, 'tools/list'), 'FAILED');

  const blocked = rows.map(row => ({ ...row, requestedExtensions: 'DISABLED',
    extensionSelectionMatches: false }));
  assert.equal(adjudicateTrace(blocked, false, 'tools/list'),
    'BLOCKED_HOST_EXTENSION_TOGGLE');
  assert.equal(adjudicateTrace([{ ...blocked[0], skillsAdvertised: false },
    blocked[1], blocked[2]],
    false, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...blocked[0], appsMimeMatches: false },
    blocked[1], blocked[2]],
    false, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...blocked[0], responseStatus: 400 },
    blocked[1], blocked[2]],
    false, 'tools/list'), 'FAILED');
  assert.equal(adjudicateTrace([{ ...blocked[0], requestedExtensions: 'ARBITRARY' },
    blocked[1], blocked[2]], false, 'tools/list'), 'FAILED');

  const malformed = projectExchange({ request: { method: 'initialize' },
    response: { error: { data: secret } }, headers: { authorization: secret },
    status: 400, responseHeaders: {}, requestBytes: 7, responseBytes: 8,
    authorized: false, enabled: false, sequence: 1 });
  assert.equal(malformed.method, 'UNSUPPORTED');
  assertSanitized(malformed);
}

async function socketCases() {
  await assert.rejects(startProxy({ fixturePort: 1, token: 'not-a-token',
    enabled: false }), /INVALID_PROXY_CONFIGURATION/);
  const received = [];
  const upstream = createServer((incoming, outgoing) => {
    const chunks = [];
    incoming.on('data', chunk => chunks.push(chunk));
    incoming.once('end', () => {
      const bytes = Buffer.concat(chunks);
      received.push({ bytes, headers: incoming.headers, method: incoming.method,
        path: incoming.url });
      const message = JSON.parse(bytes.toString('utf8'));
      outgoing.writeHead(200, { 'content-type': 'application/json',
        'cache-control': 'no-store' });
      outgoing.end(JSON.stringify(response(message.method, message.id)));
    });
  });
  const fixturePort = await listen(upstream);
  try {
    for (const enabled of [true, false]) {
      const proxy = await startProxy({ fixturePort, token, enabled });
      try {
        const before = received.length;
        for (const method of ['server/discover', 'tools/list', 'tools/list']) {
          const payload = body(method, enabled);
          const reply = await exchange(proxy.port, 'POST', payload,
            wireHeaders(method, token, {
              'Proxy-Authorization': `Bearer ${secret}`,
              Cookie: `session=${secret}`,
              'X-Api-Key': secret,
            }));
          assert.equal(reply.status, 200, reply.body);
          assert.deepEqual(JSON.parse(reply.body), response(method));
          const forwarded = received.at(-1);
          assert.equal(forwarded.method, 'POST');
          assert.equal(forwarded.path, '/mcp');
          assert.deepEqual(forwarded.bytes, Buffer.from(payload));
          assert.equal(forwarded.headers.host, `127.0.0.1:${fixturePort}`);
          for (const name of ['authorization', 'proxy-authorization', 'cookie',
            'x-api-key']) assert.equal(forwarded.headers[name], undefined);
        }
        assert.equal(received.length, before + 3);
        assert.equal(proxy.failure(), undefined);
        assert.equal(proxy.rows.length, 3);
        for (const row of proxy.rows) {
          assertSanitized(row);
          assert.equal(row.noSessionState, true);
          assert.equal(row.extensionSelectionMatches, true);
        }
        assert.equal(adjudicateTrace(proxy.rows, enabled, 'tools/list'), 'PASSED');
      } finally {
        await proxy.close();
      }
      await assert.rejects(exchange(proxy.port, 'POST', body('tools/list', enabled),
        wireHeaders('tools/list')), error =>
        ['ECONNREFUSED', 'ECONNRESET'].includes(error.code));
    }

    const callProxy = await startProxy({ fixturePort, token, enabled: true });
    try {
      for (const method of ['server/discover', 'tools/list', 'tools/list',
        'tools/call']) {
        const reply = await exchange(callProxy.port, 'POST', body(method, true),
          wireHeaders(method));
        assert.equal(reply.status, 200);
      }
      assert.equal(callProxy.rows.length, 4);
      assert.equal(adjudicateTrace(callProxy.rows, true, 'tools/call'), 'PASSED');
      assert.equal(adjudicateTrace(callProxy.rows.slice(0, 3).concat(
        { ...callProxy.rows[3], method: 'tools/list' }), true, 'tools/call'), 'FAILED');
    } finally {
      await callProxy.close();
    }

    const blocked = await startProxy({ fixturePort, token, enabled: false });
    try {
      for (const method of ['server/discover', 'tools/list', 'tools/list'])
        assert.equal((await exchange(blocked.port, 'POST', body(method, true),
          wireHeaders(method))).status, 200);
      assert.equal(adjudicateTrace(blocked.rows, false, 'tools/list'),
        'BLOCKED_HOST_EXTENSION_TOGGLE');
    } finally {
      await blocked.close();
    }

    async function rejectedCase(label, method, payload, headers, expectedFailure,
      path = '/mcp') {
      const proxy = await startProxy({ fixturePort, token, enabled: false });
      try {
        const before = received.length;
        const reply = await exchange(proxy.port, method, payload, headers, path);
        assert.equal(reply.status, 400, label);
        assert.doesNotMatch(reply.body, /SECRET_TOKEN|Bearer/);
        assert.equal(proxy.failure(), expectedFailure, label);
        assert.equal(proxy.rows.length, 0, label);
        assert.equal(received.length, before, label);
      } finally {
        await proxy.close();
      }
    }
    await rejectedCase('wrong auth', 'POST', body('tools/list', false),
      wireHeaders('tools/list', secret), 'DISPOSABLE_AUTH_REQUIRED');
    await rejectedCase('legacy initialize', 'POST', body('initialize', false),
      wireHeaders('initialize'), 'UNEXPECTED_METHOD');
    await rejectedCase('legacy version', 'POST', body('tools/list', false),
      { ...wireHeaders('tools/list'), 'MCP-Protocol-Version': '2025-11-25' },
      'MODERN_PROTOCOL_REQUIRED');
    const legacyMetadata = JSON.parse(body('tools/list', false));
    legacyMetadata.params._meta['io.modelcontextprotocol/protocolVersion'] =
      '2025-11-25';
    await rejectedCase('legacy metadata', 'POST', JSON.stringify(legacyMetadata),
      wireHeaders('tools/list'), 'MODERN_PROTOCOL_REQUIRED');
    await rejectedCase('GET transport', 'GET', undefined,
      wireHeaders('tools/list'), 'POST_ONLY');
    await rejectedCase('wrong path', 'POST', body('tools/list', false),
      wireHeaders('tools/list'), 'POST_ONLY', '/else');
    await rejectedCase('malformed JSON', 'POST', '{"jsonrpc":',
      wireHeaders('tools/list'), 'REQUEST_INVALID');

    const bounded = await startProxy({ fixturePort, token, enabled: false });
    try {
      const before = received.length;
      const oversized = Buffer.alloc(1024 * 1024 + 1, 0x61);
      await exchange(bounded.port, 'POST', oversized, wireHeaders('tools/list'))
        .catch(() => {});
      assert.equal(bounded.failure(), 'BODY_BOUND');
      assert.equal(received.length, before);
      assert.equal(bounded.rows.length, 0);
    } finally {
      await bounded.close();
    }

    const exchangeBound = await startProxy({ fixturePort, token, enabled: false });
    try {
      for (let index = 0; index < 32; ++index) {
        const method = index === 0 ? 'server/discover' : 'tools/list';
        const reply = await exchange(exchangeBound.port, 'POST',
          body(method, false, `bounded-${index}`), wireHeaders(method));
        assert.equal(reply.status, 200);
      }
      const before = received.length;
      const rejected = await exchange(exchangeBound.port, 'POST',
        body('tools/list', false, 'bounded-32'), wireHeaders('tools/list'));
      assert.equal(rejected.status, 400);
      assert.equal(exchangeBound.failure(), 'EXCHANGE_BOUND');
      assert.equal(exchangeBound.rows.length, 32);
      assert.equal(received.length, before);
    } finally {
      await exchangeBound.close();
    }
  } finally {
    await closeServer(upstream);
  }
}

pureProjectionCases();
await socketCases();
console.log('Inspector sanitized-trace self-test passed.');
