#!/usr/bin/env node

import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer, request as httpRequest } from 'node:http';
import { PROTOCOL, UI, SKILLS, adjudicateWebTrace, projectExchange, startProxy, validResult } from './trace.mjs';

const token = randomBytes(32).toString('hex');
const secret = 'WEB_SECRET_NEVER_PERSIST';
const methods = ['server/discover', 'resources/list', 'tools/list', 'prompts/list',
  'resources/templates/list', 'tools/list', 'tools/call'];
const fixtures = {
  'server/discover': { supportedVersions: [PROTOCOL], capabilities: { tools: {}, prompts: {}, resources: {} } },
  'tools/list': { tools: ['json_schema_2020_12_tool', 'test_audio_content', 'test_custom_header',
    'test_embedded_resource', 'test_error_handling', 'test_image_content', 'test_multiple_content_types',
    'test_simple_text', 'test_tool_with_progress'].map(name => ({ name })) },
  'prompts/list': { prompts: [
    { name: 'test_simple_prompt', description: 'Returns a deterministic simple prompt.' },
    { name: 'test_prompt_with_arguments', description: 'Substitutes two required string arguments.',
      arguments: [{ name: 'arg1', description: 'First test argument', required: true },
        { name: 'arg2', description: 'Second test argument', required: true }] },
    { name: 'test_prompt_with_embedded_resource', description: 'Embeds the requested text resource.',
      arguments: [{ name: 'resourceUri', description: 'URI of the resource to embed', required: true }] },
    { name: 'test_prompt_with_image', description: 'Returns deterministic image prompt content.' },
  ] },
  'resources/list': { resources: [
    { uri: 'test://static-text', name: 'Static text resource', description: 'A deterministic UTF-8 text resource.', mimeType: 'text/plain' },
    { uri: 'test://static-binary', name: 'Static binary resource', description: 'A deterministic PNG resource.', mimeType: 'image/png' },
  ] },
  'resources/templates/list': { resourceTemplates: [
    { uriTemplate: 'test://template/{id}/data', name: 'Template data resource',
      description: 'A deterministic RFC 6570 Level 1 template.', mimeType: 'application/json' },
  ] },
  'tools/call': { content: [{ type: 'text', text: 'This is a simple text response for testing.' }] },
};
const result = method => ({ resultType: 'complete', ...structuredClone(fixtures[method]) });
function request(method, enabled) {
  return { jsonrpc: '2.0', id: secret, method, params: {
    _meta: { 'io.modelcontextprotocol/protocolVersion': PROTOCOL,
      'io.modelcontextprotocol/clientCapabilities': { extensions: {
        'io.modelcontextprotocol/tasks': {},
        ...(enabled ? { [UI]: { elicitation: {}, mimeTypes: ['text/html;profile=mcp-app'] }, [SKILLS]: {} } : {}),
      } }, privateValue: secret },
    ...(method === 'tools/call' ? { name: 'test_simple_text', arguments: {} } : {}),
  } };
}
const headers = method => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json',
  'mcp-protocol-version': PROTOCOL, 'mcp-method': method });
function projected(method, enabled, sequence, change = {}) {
  return projectExchange({ surface: 'web', request: request(method, enabled),
    response: { jsonrpc: '2.0', id: secret, result: result(method) }, headers: headers(method),
    responseHeaders: { 'content-type': 'application/json', 'cache-control': 'no-store' },
    status: 200, authorized: true, enabled, sequence, requestBytes: 200, responseBytes: 300, ...change });
}
function sanitized(value) {
  const json = JSON.stringify(value);
  assert.doesNotMatch(json, /WEB_SECRET|Bearer|authorization|cookie|test:\/\//i);
  assert.equal(json.includes(token), false);
}
function permute(values) {
  if (!values.length) return [[]];
  return values.flatMap((value, index) => permute(values.filter((_, i) => i !== index))
    .map(rest => [value, ...rest]));
}

function pureCases() {
  for (const enabled of [true, false]) {
    for (const middle of permute(methods.slice(1, 6))) {
      const rows = [methods[0], ...middle, methods[6]].map((method, index) => projected(method, enabled, index + 1));
      assert.equal(adjudicateWebTrace(rows, enabled), 'PASSED');
      rows.forEach(sanitized);
    }
    const rows = methods.map((method, index) => projected(method, enabled, index + 1));
    for (const key of ['authorized', 'requestEnvelopeValid', 'protocolMetadataMatches', 'protocolHeaderMatches',
      'methodHeaderMatches', 'perRequestCapabilitiesPresent', 'noSessionState', 'toolSelectionValid',
      'responseJson', 'responseNoStore', 'responseCorrelated', 'resultMatchesFixture', 'responseEnvelopeValid',
      'extensionSelectionMatches']) {
      assert.equal(adjudicateWebTrace(rows.map((row, index) => index === 2 ? { ...row, [key]: false } : row), enabled), 'FAILED', key);
    }
    for (const update of [{ surface: 'cli' }, { sequence: 10 }, { responseStatus: 500 },
      { requestBytes: 0 }, { responseBytes: 1048577 }, { requestBytes: Infinity },
      { requestedExtensions: enabled ? 'DISABLED' : 'ENABLED' },
      { appsAdvertised: !enabled }, { skillsAdvertised: !enabled }]) {
      assert.equal(adjudicateWebTrace(rows.map((row, index) => index === 1 ? { ...row, ...update } : row), enabled), 'FAILED');
    }
    assert.equal(adjudicateWebTrace(rows.slice(0, 6), enabled), 'FAILED');
    assert.equal(adjudicateWebTrace([...rows, rows[6]], enabled), 'FAILED');
    const resequence = input => input.map((row, index) => ({ ...row, sequence: index + 1 }));
    assert.equal(adjudicateWebTrace(resequence(rows.filter((_, index) => index !== 5)), enabled), 'FAILED', 'missing second tools listing');
    assert.equal(adjudicateWebTrace(resequence([...rows.slice(0, 6), rows[5], rows[6]]), enabled), 'FAILED', 'extra tools listing');
    assert.equal(adjudicateWebTrace([rows[1], rows[0], ...rows.slice(2)], enabled), 'FAILED');
    assert.equal(adjudicateWebTrace([...rows.slice(0, 5), rows[6], rows[5]], enabled), 'FAILED');
    assert.equal(adjudicateWebTrace(rows.map((row, index) => index === 3 ? { ...row, method: 'tools/list' } : row), enabled), 'FAILED');
  }
  assert.equal(adjudicateWebTrace(null, true), 'FAILED');
  assert.equal(adjudicateWebTrace(Array(7).fill(null), true), 'FAILED');
  assert.equal(adjudicateWebTrace(methods.map((method, index) => projected(method, true, index + 1)), false), 'FAILED');
  const ignoredOff = methods.map((method, index) => projected(method, false, index + 1, { request: request(method, true) }));
  assert.equal(adjudicateWebTrace(ignoredOff, false), 'FAILED');

  for (const method of ['prompts/list', 'resources/list', 'resources/templates/list']) {
    const field = Object.keys(fixtures[method])[0];
    assert.equal(validResult(method, result(method), 'web'), true);
    assert.equal(validResult(method, result(method)), false, 'CLI remains narrow');
    for (const update of [{ nextCursor: 'more' }, { [field]: [] }, { [field]: null }, { resultType: 'task' }])
      assert.equal(validResult(method, { ...result(method), ...update }, 'web'), false);
    const changed = result(method);
    changed[field][0].description = secret;
    assert.equal(validResult(method, changed, 'web'), false);
    const extra = result(method);
    extra[field][0].unexpected = secret;
    assert.equal(validResult(method, extra, 'web'), false);
    const duplicate = result(method);
    duplicate[field].push(duplicate[field][0]);
    assert.equal(validResult(method, duplicate, 'web'), false);
    assert.equal(projected(method, true, 1, { response: { jsonrpc: '2.0', id: secret, result: changed } }).resultMatchesFixture, false);
  }
  const missingCapability = result('server/discover');
  delete missingCapability.capabilities.prompts;
  assert.equal(validResult('server/discover', missingCapability, 'web'), false);
  for (const ui of [{ mimeTypes: ['text/html;profile=mcp-app'] },
    { mimeTypes: ['text/html'], elicitation: {} },
    { mimeTypes: ['text/html;profile=mcp-app'], elicitation: { unexpected: true } },
    { mimeTypes: ['text/html;profile=mcp-app'], elicitation: {}, extra: true }]) {
    const message = request('tools/list', true);
    message.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions[UI] = ui;
    assert.equal(projected('tools/list', true, 1, { request: message }).extensionSelectionMatches, false);
  }
  assert.equal(projected('tools/list', true, 1, { response: { jsonrpc: '2.0', id: secret,
    result: result('tools/list'), error: { message: secret } } }).responseEnvelopeValid, false);
  assert.throws(() => projected('tools/list', true, 1, { surface: 'unknown' }), /INVALID_TRACE_SURFACE/);
}

function exchange(port, payload, headerValues) {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({ host: '127.0.0.1', port, path: '/mcp', method: 'POST', headers: headerValues }, incoming => {
      const chunks = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.once('error', reject);
      incoming.once('end', () => resolve({ status: incoming.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    outgoing.setTimeout(2000, () => outgoing.destroy(new Error('TEST_TIMEOUT')));
    outgoing.once('error', reject);
    outgoing.end(payload);
  });
}

async function socketCases() {
  const received = [];
  let responseMode = 'normal';
  const upstream = createServer((incoming, outgoing) => {
    const chunks = [];
    incoming.on('data', chunk => chunks.push(chunk));
    incoming.once('end', () => {
      const bytes = Buffer.concat(chunks);
      const message = JSON.parse(bytes.toString('utf8'));
      received.push({ bytes, headers: incoming.headers });
      outgoing.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      outgoing.end(responseMode === 'invalid' ? secret : responseMode === 'oversized'
        ? 'x'.repeat(1048577) : JSON.stringify({ jsonrpc: '2.0', id: message.id, result: result(message.method) }));
    });
  });
  await new Promise((resolve, reject) => { upstream.once('error', reject); upstream.listen(0, '127.0.0.1', resolve); });
  const fixturePort = upstream.address().port;
  try {
    await assert.rejects(startProxy({ fixturePort, token, enabled: false, surface: 'unknown' }), /INVALID_PROXY_CONFIGURATION/);
    for (const enabled of [true, false]) {
      const proxy = await startProxy({ fixturePort, token, enabled, surface: 'web' });
      try {
        for (const method of methods) {
          const payload = JSON.stringify(request(method, enabled));
          const response = await exchange(proxy.port, payload, { ...headers(method), cookie: secret,
            'proxy-authorization': secret, 'x-api-key': secret });
          assert.equal(response.status, 200);
          const forwarded = received.at(-1);
          assert.deepEqual(forwarded.bytes, Buffer.from(payload));
          for (const key of ['authorization', 'proxy-authorization', 'cookie', 'x-api-key'])
            assert.equal(forwarded.headers[key], undefined);
        }
        assert.equal(proxy.failure(), undefined);
        assert.equal(adjudicateWebTrace(proxy.rows, enabled), 'PASSED');
        proxy.rows.forEach(sanitized);
      } finally { await proxy.close(); }
    }
    const negatives = [
      ['subscriptions/listen', undefined, undefined, 'UNEXPECTED_METHOD'],
      ['resources/read', undefined, undefined, 'UNEXPECTED_METHOD'],
      ['skills/list', undefined, undefined, 'UNEXPECTED_METHOD'],
      ['initialize', undefined, undefined, 'UNEXPECTED_METHOD'],
      ['prompts/list', undefined, { authorization: secret }, 'DISPOSABLE_AUTH_REQUIRED'],
      ['resources/list', undefined, { 'mcp-protocol-version': '2025-11-25' }, 'MODERN_PROTOCOL_REQUIRED'],
      ['prompts/list', '{broken', undefined, 'REQUEST_INVALID'],
    ];
    for (const [method, payload, extraHeaders, expected] of negatives) {
      const proxy = await startProxy({ fixturePort, token, enabled: false, surface: 'web' });
      try {
        const count = received.length;
        const response = await exchange(proxy.port, payload ?? JSON.stringify(request(method, false)), { ...headers(method), ...extraHeaders });
        assert.equal(response.status, 400);
        sanitized(response);
        assert.equal(proxy.failure(), expected);
        assert.equal(proxy.rows.length, 0);
        assert.equal(received.length, count);
      } finally { await proxy.close(); }
    }
    for (const mode of ['invalid', 'oversized']) {
      responseMode = mode;
      const proxy = await startProxy({ fixturePort, token, enabled: false, surface: 'web' });
      try {
        await assert.rejects(exchange(proxy.port, JSON.stringify(request('resources/list', false)), headers('resources/list')));
        assert.equal(proxy.failure(), 'UPSTREAM_RESPONSE_INVALID');
        assert.equal(proxy.rows.length, 0);
      } finally { await proxy.close(); }
    }
    responseMode = 'normal';
    const bounded = await startProxy({ fixturePort, token, enabled: false, surface: 'web' });
    try {
      const count = received.length;
      await exchange(bounded.port, 'x'.repeat(1048577), headers('resources/list')).catch(() => {});
      assert.equal(bounded.failure(), 'BODY_BOUND');
      assert.equal(bounded.rows.length, 0);
      assert.equal(received.length, count);
    } finally { await bounded.close(); }
    const exchangeBound = await startProxy({ fixturePort, token, enabled: false, surface: 'web' });
    try {
      for (let index = 0; index < 32; ++index)
        assert.equal((await exchange(exchangeBound.port, JSON.stringify(request('prompts/list', false)), headers('prompts/list'))).status, 200);
      const count = received.length;
      assert.equal((await exchange(exchangeBound.port, JSON.stringify(request('prompts/list', false)), headers('prompts/list'))).status, 400);
      assert.equal(exchangeBound.failure(), 'EXCHANGE_BOUND');
      assert.equal(exchangeBound.rows.length, 32);
      assert.equal(received.length, count);
    } finally { await exchangeBound.close(); }
  } finally {
    upstream.closeAllConnections();
    await new Promise(resolve => upstream.close(resolve));
  }
}

pureCases();
await socketCases();
console.log('Inspector web sanitized-trace self-test passed.');
