import assert from 'node:assert/strict';
import {Agent, request} from 'node:http';
import {createConnection} from 'node:net';
import {inflateSync} from 'node:zlib';
import {test} from 'node:test';
import {adjudicateCanary, PHASES, startCanary} from './canary.mjs';

const secret = 'PRIVATE_CANARY_NEVER_RETAIN';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function exchange(canary, path, {method = 'GET', headers = {}, body, agent} = {}) {
  return new Promise((resolve, reject) => {
    const outgoing = request(new URL(path, canary.origin), {method, headers, agent}, incoming => {
      const chunks = []; incoming.on('data', chunk => chunks.push(chunk)); incoming.once('error', reject);
      incoming.once('end', () => resolve({status: incoming.statusCode, headers: incoming.headers, body: Buffer.concat(chunks)}));
    });
    outgoing.setTimeout(2000, () => outgoing.destroy(new Error('TEST_TIMEOUT')));
    outgoing.once('error', reject); outgoing.end(body);
  });
}
async function withCanary(fn) {
  const canary = await startCanary();
  try {await fn(canary);} finally {await canary.close();}
}
function finishPhases(canary) {
  for (const phase of PHASES.slice(PHASES.indexOf(canary.facts().phase) + 1)) canary.setPhase(phase);
}
function sanitized(facts, canary) {
  const text = JSON.stringify(facts);
  for (const value of [secret, canary.origin, canary.connectUrl, canary.imageUrl, 'Bearer ', '/connect', '/image', 'Cookie'])
    assert.equal(text.includes(value), false);
}
function verifyPng(bytes) {
  assert.deepEqual(bytes.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
  assert.equal(bytes.readUInt32BE(16), 1); assert.equal(bytes.readUInt32BE(20), 1);
  const names = [];
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset), payload = bytes.subarray(offset + 4, offset + 8 + length);
    let crc = 0xffffffff;
    for (const value of payload) {
      crc ^= value;
      for (let bit = 0; bit < 8; ++bit) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    assert.equal((crc ^ 0xffffffff) >>> 0, bytes.readUInt32BE(offset + 8 + length));
    const name = payload.subarray(0, 4).toString('ascii'); names.push(name);
    if (name === 'IDAT') assert.deepEqual(inflateSync(payload.subarray(4)), Buffer.from([1, 0, 255]));
    offset += length + 12;
  }
  assert.deepEqual(names, ['IHDR', 'IDAT', 'IEND']);
}
async function controls(canary, order = ['/connect', '/image']) {
  for (const path of order) {
    const response = await exchange(canary, path);
    assert.equal(response.status, 200); assert.equal(response.headers['cache-control'], 'no-store');
    assert.equal(response.headers['access-control-allow-origin'], '*'); assert.equal(response.headers['set-cookie'], undefined);
    assert.equal(Number(response.headers['content-length']), response.body.length);
    if (path === '/connect') {
      assert.equal(response.headers['content-type'], 'text/plain;charset=utf-8');
      assert.equal(response.body.toString('ascii'), 'soklet-csp-canary');
    } else {assert.equal(response.headers['content-type'], 'image/png'); verifyPng(response.body);}
  }
}

test('fixed no-store CORS endpoints pass only after both controls, no App traffic and clean sealed close', async () => {
  await withCanary(async canary => {
    assert.match(canary.origin, /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/);
    assert.equal(canary.connectUrl, canary.origin + '/connect'); assert.equal(canary.imageUrl, canary.origin + '/image');
    assert.equal(canary.facts().phase, 'IDLE');
    canary.setPhase('CONTROL_BEFORE'); await controls(canary);
    canary.setPhase('APP'); canary.setPhase('CONTROL_AFTER'); await controls(canary, ['/image?control=after', '/connect']);
    canary.setPhase('SEALED'); assert.equal(adjudicateCanary(canary.facts()), 'FAILED');
    const closing = canary.close(); assert.equal(canary.close(), closing); await closing;
    const facts = canary.facts(); assert.equal(adjudicateCanary(facts), 'PASSED'); sanitized(facts, canary);
    facts.phases.APP.connect = 10; facts.requests[0].code = secret;
    assert.equal(adjudicateCanary(canary.facts()), 'PASSED', 'snapshots cannot mutate retained evidence');
  });
});

test('fixed after-image alias has identical bytes and content headers in every phase, including normal App success', async () => {
  await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE');
    const original = await exchange(canary, '/image');
    const alias = await exchange(canary, '/image?control=after');
    for (const response of [original, alias]) {assert.equal(response.status, 200); verifyPng(response.body);}
    assert.deepEqual(alias.body, original.body);
    for (const name of ['content-type', 'content-length', 'cache-control', 'access-control-allow-origin'])
      assert.equal(alias.headers[name], original.headers[name]);
    assert.equal(canary.failure(), null, 'server does not bind the reviewed alias to a control phase');
    canary.setPhase('APP');
    const app = await exchange(canary, '/image?control=after', {headers: {Origin: 'null'}});
    assert.equal(app.status, 200); assert.deepEqual(app.body, original.body);
    for (const name of ['content-type', 'content-length', 'cache-control', 'access-control-allow-origin'])
      assert.equal(app.headers[name], original.headers[name]);
    assert.equal(canary.failure(), 'CANARY_APP_CONTACT');
    assert.equal(canary.facts().phases.APP.image, 1); finishPhases(canary); await canary.close();
    assert.equal(adjudicateCanary(canary.facts()), 'FAILED'); sanitized(canary.facts(), canary);
  });
});

test('absent, opaque null and exact HTTP loopback Origins are accepted without credentials', async () => {
  for (const origin of [undefined, 'null', 'http://127.0.0.1:1', 'http://127.0.0.1:65535']) {
    await withCanary(async canary => {
      canary.setPhase('CONTROL_BEFORE');
      assert.equal((await exchange(canary, '/connect', {headers: origin === undefined ? {} : {Origin: origin}})).status, 200);
      assert.equal(canary.failure(), null); sanitized(canary.facts(), canary);
    });
  }
});

test('App contact succeeds on the network but always fails the CSP verdict', async () => {
  await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE'); await controls(canary); canary.setPhase('APP');
    const response = await exchange(canary, '/connect', {headers: {Origin: 'null'}});
    assert.equal(response.status, 200); assert.equal(response.body.toString('ascii'), 'soklet-csp-canary');
    assert.equal(canary.failure(), 'CANARY_APP_CONTACT');
    assert.deepEqual(canary.facts().phases.APP, {connect: 1, image: 0, rejected: 0});
    canary.setPhase('CONTROL_AFTER'); await controls(canary); canary.setPhase('SEALED'); await canary.close();
    assert.equal(adjudicateCanary(canary.facts()), 'FAILED'); sanitized(canary.facts(), canary);
  });
});

test('methods, queries, paths, Host, credentials, Origins and request bodies fail closed without reflection', async () => {
  const cases = [
    ['/connect', {method: 'POST'}, 'CANARY_METHOD'], ['/image', {method: 'HEAD'}, 'CANARY_METHOD'],
    ['/connect?' + secret, {}, 'CANARY_PATH'], ['/image?x=1', {}, 'CANARY_PATH'], ['/' + secret, {}, 'CANARY_PATH'],
    ...['/image?control=before', '/image?control=After', '/image?control=%61fter',
      '/image?control=after&', '/image?control=after&x=1', '/image?control=after&control=after']
      .map(path => [path, {}, 'CANARY_PATH']),
    ['/connect', {headers: {Host: 'localhost:80'}}, 'CANARY_HOST'],
    ...['authorization', 'proxy-authorization', 'cookie', 'cookie2', 'x-api-key', 'x-auth-token', 'x-mcp-remote-auth']
      .map(name => ['/connect', {headers: {[name]: secret}}, 'CANARY_CREDENTIAL']),
    ...['referer', 'referrer'].map(name => ['/connect', {headers: {[name]: 'http://127.0.0.1:12345/' + secret}}, 'CANARY_REFERRER']),
    ...['https://127.0.0.1:1', 'http://localhost:1', 'http://127.0.0.1', 'http://127.0.0.1:0',
      'http://127.0.0.1:65536', 'http://127.0.0.1:1/', 'null, null', 'https://' + secret]
      .map(origin => ['/connect', {headers: {Origin: origin}}, 'CANARY_ORIGIN']),
    ['/connect', {headers: {'content-length': '1'}, body: 'x'}, 'CANARY_BODY'],
    ['/image', {headers: {'transfer-encoding': 'chunked'}, body: secret}, 'CANARY_BODY'],
  ];
  for (const [path, options, code] of cases) await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE');
    const response = await exchange(canary, path, options); assert.equal(response.status, 400);
    assert.equal(response.body.toString().includes(secret), false); assert.equal(canary.failure(), code);
    assert.equal(canary.facts().phases.CONTROL_BEFORE.rejected, 1); sanitized(canary.facts(), canary);
  });
});

test('phase order is exact, immutable after close, and close never invents a sealed phase', async () => {
  for (const invalid of ['APP', 'CONTROL_AFTER', 'SEALED', 'IDLE', secret, undefined]) await withCanary(async canary => {
    assert.throws(() => canary.setPhase(invalid), /CANARY_PHASE_ORDER/);
    assert.equal(canary.facts().phase, 'IDLE'); assert.equal(canary.failure(), 'CANARY_PHASE_ORDER');
  });
  await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE'); assert.throws(() => canary.setPhase('CONTROL_BEFORE'), /CANARY_PHASE_ORDER/);
    await canary.close(); assert.equal(canary.facts().phase, 'CONTROL_BEFORE');
    assert.throws(() => canary.setPhase('APP'), /CANARY_PHASE_ORDER/); assert.equal(adjudicateCanary(canary.facts()), 'FAILED');
  });
  await withCanary(async canary => {
    finishPhases(canary);
    for (const next of [undefined, null, 'SEALED', 'IDLE']) {
      assert.throws(() => canary.setPhase(next), /CANARY_PHASE_ORDER/);
      assert.equal(canary.facts().phase, 'SEALED');
    }
  });
});

test('IDLE and SEALED traffic remains observable and cannot pass', async () => {
  for (const phase of ['IDLE', 'SEALED']) await withCanary(async canary => {
    if (phase === 'SEALED') finishPhases(canary);
    assert.equal((await exchange(canary, '/image')).status, 200);
    assert.equal(canary.failure(), 'CANARY_PHASE_CONTACT'); assert.equal(canary.facts().phases[phase].image, 1);
    await canary.close(); assert.equal(adjudicateCanary(canary.facts()), 'FAILED');
  });
});

test('adjudication rejects missing/extra/sparse evidence, counters, contact, phase and cleanup mutations', async () => {
  await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE'); await controls(canary); canary.setPhase('APP');
    canary.setPhase('CONTROL_AFTER'); await controls(canary); canary.setPhase('SEALED'); await canary.close();
    const good = canary.facts();
    for (const change of [{closed: false}, {closeClean: false}, {phase: 'APP'}, {failure: 'CANARY_APP_CONTACT'},
      {requestCount: 3}, {requestCount: 5}, {connectionCount: 0}, {connectionCount: 17}, {connectionCount: 1.5},
      {requests: []}, {requests: Array(4)}, {requests: [...good.requests, good.requests[0]]}, {raw: secret}])
      assert.equal(adjudicateCanary({...good, ...change}), 'FAILED');
    for (const key of Object.keys(good)) {const changed = structuredClone(good); delete changed[key]; assert.equal(adjudicateCanary(changed), 'FAILED');}
    for (const phase of PHASES) for (const field of ['connect', 'image', 'rejected']) {
      const changed = structuredClone(good); changed.phases[phase][field] += 1; assert.equal(adjudicateCanary(changed), 'FAILED');
    }
    for (let index = 0; index < 4; ++index) {
      const sparse = structuredClone(good); delete sparse.requests[index]; assert.equal(adjudicateCanary(sparse), 'FAILED');
      for (const change of [{sequence: 0}, {phase: 'APP'}, {operation: 'OTHER'}, {method: 'OTHER'}, {accepted: false}, {code: 'CANARY_BODY'}, {raw: secret}]) {
        const changed = structuredClone(good); Object.assign(changed.requests[index], change); assert.equal(adjudicateCanary(changed), 'FAILED');
      }
    }
  });
});

test('request budget saturates safely and records at most sixteen fixed-shape observations', async () => {
  const agent = new Agent({keepAlive: true, maxSockets: 1});
  try {await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE');
    for (let index = 0; index < 16; ++index) assert.equal((await exchange(canary, '/connect', {agent})).status, 200);
    for (let index = 0; index < 3; ++index) assert.equal((await exchange(canary, '/connect', {agent})).status, 400);
    const facts = canary.facts(); assert.equal(facts.requestCount, 17); assert.equal(facts.requests.length, 16);
    assert.deepEqual(facts.requests.map(row => row.sequence), Array.from({length: 16}, (_, index) => index + 1));
    assert.equal(canary.failure(), 'CANARY_REQUEST_BOUND'); sanitized(facts, canary);
  });} finally {agent.destroy();}
});

function connect(canary, sockets = []) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({host: '127.0.0.1', port: Number(new URL(canary.origin).port)}); sockets.push(socket);
    socket.once('error', reject); socket.once('connect', () => resolve(socket));
  });
}
function socketClosed(socket, deadlineMs = 2000) {
  return new Promise((resolve, reject) => {
    if (socket.destroyed) return resolve();
    const timer = setTimeout(() => {socket.destroy(); reject(new Error('TEST_TIMEOUT'));}, deadlineMs);
    socket.once('close', () => {clearTimeout(timer); resolve();}); socket.once('error', () => {});
  });
}

test('connection budget rejects the seventeenth socket independently of HTTP request counts', async () => {
  const sockets = [];
  try {await withCanary(async canary => {
    for (let index = 0; index < 16; ++index) await connect(canary, sockets);
    const excess = await connect(canary, sockets); await socketClosed(excess);
    assert.equal(canary.failure(), 'CANARY_CONNECTION_BOUND');
    assert.equal(canary.facts().connectionCount, 17); assert.equal(canary.facts().requestCount, 0);
  });} finally {sockets.forEach(socket => socket.destroy());}
});

test('oversized headers and malformed HTTP are destroyed without preserving raw bytes', async () => {
  for (const [payload, code] of [
    ['GET /connect HTTP/1.1\r\nHost: PLACEHOLDER\r\nX-Test: ' + secret.repeat(400) + '\r\n\r\n', 'CANARY_HEADER_BOUND'],
    [secret + '\r\n\r\n', 'CANARY_HTTP_INVALID'],
  ]) await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE'); const socket = await connect(canary);
    socket.write(payload.replace('PLACEHOLDER', new URL(canary.origin).host)); await socketClosed(socket);
    assert.equal(canary.failure(), code); assert.equal(canary.facts().phases.CONTROL_BEFORE.rejected, 1);
    sanitized(canary.facts(), canary);
  });
});

test('close destroys idle owned sockets promptly and does not hang or skip phases', async () => {
  await withCanary(async canary => {
    const socket = await connect(canary); finishPhases(canary);
    const start = Date.now(); await canary.close(); await socketClosed(socket);
    assert.ok(Date.now() - start < 1000); assert.equal(canary.facts().closed, true); assert.equal(canary.facts().closeClean, true);
    assert.equal(adjudicateCanary(canary.facts()), 'FAILED', 'cleanup alone cannot replace missing controls');
  });
});

test('preheader sockets have a five-second idle bound while completed control keep-alives retire cleanly', async () => {
  await withCanary(async canary => {
    const socket = await connect(canary); const start = Date.now(); await socketClosed(socket, 6500);
    assert.ok(Date.now() - start >= 4500); assert.ok(Date.now() - start < 6500);
    assert.equal(canary.failure(), 'CANARY_SOCKET_TIMEOUT');
  });
  const agent = new Agent({keepAlive: true, maxSockets: 1});
  try {await withCanary(async canary => {
    canary.setPhase('CONTROL_BEFORE');
    await exchange(canary, '/connect', {agent}); await exchange(canary, '/image', {agent});
    canary.setPhase('APP'); await pause(5500);
    assert.equal(canary.failure(), null, 'normal control socket expiry is not App traffic or an error');
    canary.setPhase('CONTROL_AFTER'); await exchange(canary, '/connect', {agent}); await exchange(canary, '/image', {agent});
    canary.setPhase('SEALED'); await canary.close(); assert.equal(adjudicateCanary(canary.facts()), 'PASSED');
  });} finally {agent.destroy();}
});
