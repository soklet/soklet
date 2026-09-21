import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import test from 'node:test';
import { startProxy } from './proxy.mjs';

const protocol = '2026-07-28';
const skill = 'io.modelcontextprotocol/skills';
const uri = 'skill://example/demo/file.bin';
const secret = 'PRIVATE_TEST_SENTINEL';
const tick = () => new Promise(resolve => setImmediate(resolve));
const wire = (method = 'skills/list', changes = {}) => ({
  jsonrpc: '2.0', id: 'probe', method,
  params: { _meta: { 'io.modelcontextprotocol/protocolVersion': protocol,
    'io.modelcontextprotocol/clientCapabilities': { extensions: { [skill]: {} } } },
    ...(method === 'resources/read' || method === 'skills/get' ? { uri } : {}) },
  ...changes,
});
const headers = method => ({ 'content-type': 'application/json', 'mcp-protocol-version': protocol,
  'mcp-method': method, ...(method === 'resources/read' ? { 'mcp-name': uri } : {}) });
const result = (fields = {}) => ({ jsonrpc: '2.0', id: 'probe', result: { resultType: 'complete', ...fields } });
const responseHeaders = { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };

function begin(port, message = wire(), options = {}) {
  const body = Buffer.isBuffer(message) ? message : Buffer.from(JSON.stringify(message));
  let request;
  const result = new Promise((resolve, reject) => {
    request = httpRequest({ hostname: '127.0.0.1', port, method: options.method ?? 'POST',
      path: options.path ?? '/mcp', headers: { ...headers(options.rpcMethod ?? message.method ?? 'skills/list'),
        ...options.headers, 'content-length': body.length }, agent: false }, response => {
      const parts = [];
      response.on('data', part => parts.push(part));
      response.once('error', reject);
      response.once('end', () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts) }));
    });
    request.once('error', reject);
    request.setTimeout(7500, () => request.destroy(new Error('Test request timed out')));
    request.end(body);
  });
  return { request, result };
}

async function fixture(t, handler) {
  const upstream = createServer((request, response) => {
    void (async () => {
      const parts = [];
      for await (const part of request) parts.push(part);
      await handler(request, response, Buffer.concat(parts));
    })().catch(() => response.destroy());
  });
  await new Promise((resolve, reject) => {
    upstream.once('error', reject);
    upstream.listen(0, '127.0.0.1', resolve);
  });
  const proxy = await startProxy(upstream.address().port);
  t.after(async () => {
    try { await proxy.close(); }
    finally {
      const stopped = new Promise(resolve => upstream.close(resolve));
      upstream.closeAllConnections();
      await stopped;
    }
  });
  return proxy;
}

function answer(response, body = JSON.stringify(result()), status = 200, extraHeaders = {}) {
  response.writeHead(status, { ...responseHeaders, ...extraHeaders });
  response.end(body);
}

test('normal observation preserves exact bytes for every supported method', async t => {
  const received = [];
  const upstreamBody = Buffer.from(' { "jsonrpc":"2.0", "id":"probe", "result":{"resultType":"complete","text":"café 🙂"} }\n');
  const proxy = await fixture(t, (request, response, body) => {
    received.push({ method: request.headers['mcp-method'], name: request.headers['mcp-name'], body });
    answer(response, upstreamBody);
  });
  for (const method of ['server/discover', 'skills/list', 'skills/get', 'resources/list', 'resources/templates/list', 'resources/read']) {
    const body = Buffer.from(` ${JSON.stringify(wire(method))}\n`);
    const observed = await begin(proxy.port, body, { rpcMethod: method }).result;
    assert.equal(observed.status, 200);
    assert.deepEqual(observed.body, upstreamBody);
    assert.deepEqual(received.at(-1).body, body);
    assert.equal(received.at(-1).method, method);
    assert.equal(received.at(-1).name, method === 'resources/read' ? uri : undefined);
  }
  assert.equal(proxy.rows.length, 6);
  assert.ok(proxy.rows.every(row => row.valid && row.modernProtocol && row.methodMirrored
    && row.nameMirrored && row.skillsAdvertised && row.noSession && !row.tampered));
});

test('legacy, unsupported, missing identity and mismatched headers are never forwarded', async t => {
  let received = 0;
  const proxy = await fixture(t, (_request, response) => { received++; answer(response); });
  const absentId = wire(); delete absentId.id;
  const noSkills = wire(); noSkills.params._meta['io.modelcontextprotocol/clientCapabilities'].extensions[skill] = null;
  const cases = [
    [wire(), { headers: { 'mcp-protocol-version': '2025-11-25' } }],
    [wire(), { headers: { 'mcp-method': 'other' } }],
    [wire('resources/read'), { headers: { 'mcp-name': secret } }],
    [wire('skills/get'), { headers: { 'mcp-name': uri } }],
    [wire(), { headers: { 'mcp-session-id': secret } }],
    [wire(secret), {}], [absentId, {}], [noSkills, {}],
  ];
  for (const [message, options] of cases) {
    const observed = await begin(proxy.port, message, options).result;
    assert.equal(observed.status, 502);
    assert.equal(observed.body.toString(), 'Skills example probe failed.');
  }
  assert.equal(received, 0);
  assert.ok(proxy.rows.every(row => !row.valid));
  assert.ok(!JSON.stringify(proxy.rows).includes(secret));
});

test('malformed, failed, truncated and oversized responses never become valid rows', async t => {
  let mode = 'status';
  const small = JSON.stringify(result({ text: '' }));
  const maximum = small.replace('"text":""', `"text":"${'x'.repeat(4 * 1024 * 1024 - Buffer.byteLength(small))}"`);
  assert.equal(Buffer.byteLength(maximum), 4 * 1024 * 1024);
  const proxy = await fixture(t, (_request, response) => {
    if (mode === 'status') answer(response, JSON.stringify(result()), 503);
    else if (mode === 'json') answer(response, 'not json');
    else if (mode === 'correlation') answer(response, JSON.stringify({ ...result(), id: 'wrong' }));
    else if (mode === 'error') answer(response, JSON.stringify({ ...result(), error: {} }));
    else if (mode === 'session') answer(response, JSON.stringify(result()), 200, { 'mcp-session-id': secret });
    else if (mode === 'truncated') {
      response.writeHead(200, { ...responseHeaders, 'content-length': 500 });
      response.write('{'); response.destroy();
    } else answer(response, mode === 'exact' ? maximum : maximum + ' ');
  });
  for (mode of ['status', 'json', 'correlation', 'error', 'session', 'truncated', 'over']) {
    assert.equal((await begin(proxy.port).result).status, 502);
    assert.equal(proxy.rows.at(-1).valid, false);
  }
  mode = 'exact';
  assert.equal((await begin(proxy.port).result).body.length, 4 * 1024 * 1024);
  assert.equal(proxy.rows.at(-1).valid, true);
});

test('request bytes and count are capped with bounded overflow evidence', async t => {
  let received = 0;
  const proxy = await fixture(t, (_request, response) => { received++; answer(response); });
  const body = JSON.stringify(wire('skills/list', { padding: '' }));
  const exact = Buffer.from(body.replace('"padding":""', `"padding":"${'x'.repeat(1024 * 1024 - Buffer.byteLength(body))}"`));
  assert.equal(exact.length, 1024 * 1024);
  assert.equal((await begin(proxy.port, exact).result).status, 200);
  const over = await begin(proxy.port, Buffer.concat([exact, Buffer.from(' ')])).result.catch(() => null);
  assert.ok(over === null || over.status === 502);
  assert.equal(received, 1);
  for (let i = 0; i < 62; i++) assert.equal((await begin(proxy.port).result).status, 200);
  for (let i = 0; i < 4; i++) assert.equal((await begin(proxy.port).result).status, 502);
  assert.equal(received, 63);
  assert.equal(proxy.rows.length, 65);
  assert.deepEqual(proxy.rows.at(-1), { method: 'UNSUPPORTED', valid: false, tampered: false, limitExceeded: true });
});

test('negative control changes only a binary read and can be disabled again', async t => {
  let binary = true;
  const bytes = Buffer.from([0, 255, 13, 10]);
  const proxy = await fixture(t, (_request, response) => answer(response, JSON.stringify(result({ contents: [
    { uri, mimeType: 'application/octet-stream', ...(binary ? { blob: bytes.toString('base64') } : { text: 'café\r\n' }) },
  ] }))));
  const original = await begin(proxy.port, wire('resources/read')).result;
  proxy.setTampered(true);
  const changed = await begin(proxy.port, wire('resources/read')).result;
  const contents = JSON.parse(changed.body).result.contents[0];
  assert.deepEqual(Buffer.from(contents.blob, 'base64'), Buffer.from([1, 255, 13, 10]));
  assert.equal(proxy.rows.at(-1).tampered, true);
  binary = false;
  assert.equal(JSON.parse((await begin(proxy.port, wire('resources/read')).result).body).result.contents[0].text, 'café\r\n');
  assert.equal(proxy.rows.at(-1).tampered, false);
  binary = true;
  const get = await begin(proxy.port, wire('skills/get')).result;
  assert.deepEqual(get.body, original.body);
  assert.equal(proxy.rows.at(-1).tampered, false);
  proxy.setTampered(false);
  assert.deepEqual((await begin(proxy.port, wire('resources/read')).result).body, original.body);
  assert.ok(proxy.rows.every(row => row.valid));
});

test('deadline, downstream abort and idempotent close cancel hanging upstream work', { timeout: 15000 }, async t => {
  let observed;
  let onObserved = () => {};
  const proxy = await fixture(t, (_request, response) => { observed = response; onObserved(); });
  const started = Date.now();
  await assert.rejects(begin(proxy.port).result);
  assert.ok(Date.now() - started >= 4500);
  assert.equal(proxy.rows.at(-1).valid, false);

  let arrived = new Promise(resolve => { onObserved = resolve; });
  const interrupted = begin(proxy.port);
  const interruptedResult = interrupted.result.catch(() => null);
  await arrived;
  interrupted.request.destroy();
  await interruptedResult;
  await tick();
  assert.equal(proxy.rows.at(-1).valid, false);

  arrived = new Promise(resolve => { onObserved = resolve; });
  const pending = begin(proxy.port).result.catch(() => null);
  await arrived;
  const upstreamClosed = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Upstream socket was not closed')), 1000);
    observed.once('close', () => { clearTimeout(timeout); resolve(); });
  });
  const closeStarted = Date.now();
  await Promise.all([proxy.close(), proxy.close()]);
  await pending;
  await upstreamClosed;
  assert.ok(Date.now() - closeStarted < 1500);
  assert.equal(observed.destroyed, true);
  assert.ok(proxy.rows.every(row => !row.valid));
});
