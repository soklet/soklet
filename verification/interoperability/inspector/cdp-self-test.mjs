#!/usr/bin/env node

import assert from 'node:assert/strict';
import { connectCdp } from './cdp.mjs';

const endpoint = 'ws://127.0.0.1:9222/devtools/browser/abcdef-1234';
const secret = 'PRIVATE_CDP_TOKEN_NEVER_IN_ERRORS';
let checks = 0;

function mockSocket(options = {}) {
  const instances = [];
  class MockSocket extends EventTarget {
    constructor(url) {
      super();
      if (options.constructorThrows)
        throw new Error(secret);
      this.url = url;
      this.sent = [];
      this.attached = new Map();
      this.closeCalls = 0;
      this.bufferedAmount = 0;
      instances.push(this);
      if (options.open !== false)
        queueMicrotask(() => this.dispatchEvent(new Event('open')));
    }

    addEventListener(type, listener, ...args) {
      const callbacks = this.attached.get(type) ?? new Set();
      callbacks.add(listener);
      this.attached.set(type, callbacks);
      super.addEventListener(type, listener, ...args);
    }

    removeEventListener(type, listener, ...args) {
      this.attached.get(type)?.delete(listener);
      super.removeEventListener(type, listener, ...args);
    }

    send(payload) {
      if (options.sendThrows)
        throw new Error(secret);
      this.sent.push(JSON.parse(payload));
      options.onSend?.(this, this.sent.at(-1));
    }

    close() {
      this.closeCalls += 1;
      if (options.closeThrows)
        throw new Error(secret);
      if (options.close !== false)
        queueMicrotask(() => this.dispatchEvent(new Event('close')));
    }

    receive(message) {
      this.raw(JSON.stringify(message));
    }

    raw(data) {
      this.dispatchEvent(new MessageEvent('message', { data }));
    }

    reply(result = {}, extras = {}) {
      const command = this.sent.at(-1);
      this.receive({ id: command.id, ...(command.sessionId === undefined
        ? {} : { sessionId: command.sessionId }), result, ...extras });
    }

    assertDetached() {
      assert.equal([...this.attached.values()].reduce((count, set) => count + set.size, 0), 0);
    }
  }
  return { WebSocketImpl: MockSocket, instances };
}

async function fixture(socketOptions = {}, connectionOptions = {}) {
  const mock = mockSocket(socketOptions);
  const connection = await connectCdp(endpoint, {
    WebSocketImpl: mock.WebSocketImpl, timeoutMs: 250, ...connectionOptions,
  });
  return { connection, socket: mock.instances[0] };
}

async function rejectsRedacted(promise, code) {
  await assert.rejects(promise, (error) => {
    assert.equal(error.message, code);
    assert.equal(error.cause, undefined);
    assert.doesNotMatch(error.stack, new RegExp(secret));
    return true;
  });
}

async function check(work) {
  await work();
  checks += 1;
}

await check(async () => {
  const { connection, socket } = await fixture();
  const promise = connection.send('Browser.getVersion');
  assert.deepEqual(socket.sent[0], { id: 1, method: 'Browser.getVersion', params: {} });
  socket.reply({ product: 'Chrome/isolated-test' });
  assert.deepEqual(await promise, { product: 'Chrome/isolated-test' });
  assert.equal(connection.failure(), undefined);
  await Promise.all([connection.close(), connection.close()]);
  assert.equal(socket.closeCalls, 1);
  socket.assertDetached();
  await rejectsRedacted(connection.send('Browser.getVersion'), 'CDP_CLOSED');
  assert.throws(() => connection.on('Target.targetCreated', () => {}), { message: 'CDP_CLOSED' });
});

await check(async () => {
  const { connection, socket } = await fixture();
  const first = connection.send('Runtime.enable', {}, 'SESSION_A');
  const second = connection.send('Page.enable', {}, 'SESSION_B');
  socket.receive({ id: 2, sessionId: 'SESSION_B', result: { second: true } });
  socket.receive({ id: 1, sessionId: 'SESSION_A', result: { first: true } });
  assert.deepEqual(await first, { first: true });
  assert.deepEqual(await second, { second: true });
  const events = [];
  const unsubscribe = connection.on('Runtime.consoleAPICalled', (params, sessionId) => {
    events.push({ params, sessionId });
  });
  socket.receive({ method: 'Runtime.consoleAPICalled', params: { type: 'log' }, sessionId: 'SESSION_A' });
  unsubscribe();
  unsubscribe();
  socket.receive({ method: 'Runtime.consoleAPICalled', params: { type: 'log' }, sessionId: 'SESSION_A' });
  assert.deepEqual(events, [{ params: { type: 'log' }, sessionId: 'SESSION_A' }]);
  socket.receive({ method: 'Target.targetCreated' });
  assert.equal(connection.failure(), undefined);
  await connection.close();
  socket.assertDetached();
});

for (const url of [
  '', 'ws://localhost:9222/devtools/browser/abc', 'wss://127.0.0.1:9222/devtools/browser/abc',
  'ws://127.0.0.2:9222/devtools/browser/abc', 'ws://127.0.0.1:0/devtools/browser/abc',
  'ws://127.0.0.1:65536/devtools/browser/abc', 'ws://127.0.0.1:09222/devtools/browser/abc',
  'ws://127.0.0.1:9222/devtools/page/abc', 'ws://127.0.0.1:9222/devtools/browser/../abc',
  `ws://${secret}@127.0.0.1:9222/devtools/browser/abc`,
  `ws://127.0.0.1:9222/devtools/browser/abc?token=${secret}`,
  'ws://127.0.0.1:9222/devtools/browser/abc#fragment',
  'ws://127.0.0.1:9222/devtools/browser/abc/',
  'ws://127.0.0.1:9222/devtools/browser/abc\n',
]) {
  await check(async () => {
    const mock = mockSocket();
    await rejectsRedacted(connectCdp(url, { WebSocketImpl: mock.WebSocketImpl }), 'CDP_INVALID_OPTIONS');
    assert.equal(mock.instances.length, 0);
  });
}

for (const options of [null, [], { timeoutMs: 0 }, { timeoutMs: 60001 },
  { timeoutMs: 1.5 }, { maxMessageBytes: 0 }, { maxMessageBytes: 16 * 1024 * 1024 + 1 },
  { WebSocketImpl: null }]) {
  await check(() => rejectsRedacted(connectCdp(endpoint, options), 'CDP_INVALID_OPTIONS'));
}

await check(async () => {
  const mock = mockSocket({ constructorThrows: true });
  await rejectsRedacted(connectCdp(endpoint, { WebSocketImpl: mock.WebSocketImpl }), 'CDP_CONNECT_FAILED');
});

await check(async () => {
  const mock = mockSocket({ open: false });
  await rejectsRedacted(connectCdp(endpoint, { WebSocketImpl: mock.WebSocketImpl, timeoutMs: 20 }), 'CDP_CONNECT_TIMEOUT');
  assert.equal(mock.instances[0].closeCalls, 1);
  mock.instances[0].assertDetached();
});

await check(async () => {
  const mock = mockSocket({ open: false, close: false });
  await rejectsRedacted(connectCdp(endpoint, { WebSocketImpl: mock.WebSocketImpl, timeoutMs: 20 }), 'CDP_CONNECT_TIMEOUT');
  mock.instances[0].assertDetached();
});

for (const eventType of ['close', 'error']) {
  await check(async () => {
    const mock = mockSocket({ open: false });
    const connecting = connectCdp(endpoint, { WebSocketImpl: mock.WebSocketImpl, timeoutMs: 50 });
    mock.instances[0].dispatchEvent(new Event(eventType));
    await rejectsRedacted(connecting, eventType === 'close' ? 'CDP_CLOSED' : 'CDP_SOCKET_ERROR');
    mock.instances[0].assertDetached();
  });
}

for (const badMessage of [
  secret, 'null', '[]', '{}', '{"id":1,"result":[]}',
  '{"id":1,"result":{},"error":{"code":-1,"message":"private"}}',
  '{"id":999,"result":{}}', '{"id":1,"result":{},"sessionId":"OTHER"}',
  '{"id":1,"result":{},"method":"Runtime.enable"}',
  '{"id":1,"error":{"code":"private","message":"private"}}',
  '{"method":"Runtime.consoleAPICalled","params":[]}',
  '{"method":"Runtime.consoleAPICalled","result":{}}',
  '{"method":"Runtime.consoleAPICalled","sessionId":"unsafe/token"}',
  new Uint8Array([1, 2, 3]),
]) {
  await check(async () => {
    const { connection, socket } = await fixture();
    const command = connection.send('Browser.getVersion');
    socket.raw(badMessage);
    await rejectsRedacted(command, 'CDP_INVALID_MESSAGE');
    assert.equal(connection.failure(), 'CDP_INVALID_MESSAGE');
    await connection.close();
    socket.assertDetached();
  });
}

await check(async () => {
  const { connection, socket } = await fixture();
  const first = connection.send('Browser.getVersion');
  const second = connection.send('Target.getTargets');
  socket.receive({ id: 1, error: { code: -32000, message: secret, data: secret } });
  await rejectsRedacted(first, 'CDP_COMMAND_ERROR');
  await rejectsRedacted(second, 'CDP_COMMAND_ERROR');
  assert.equal(connection.failure(), 'CDP_COMMAND_ERROR');
  await connection.close();
  socket.assertDetached();
});

await check(async () => {
  const { connection, socket } = await fixture({}, { timeoutMs: 20 });
  await rejectsRedacted(connection.send('Browser.getVersion', { private: secret }), 'CDP_COMMAND_TIMEOUT');
  assert.equal(connection.failure(), 'CDP_COMMAND_TIMEOUT');
  await connection.close();
  socket.assertDetached();
});

for (const eventType of ['close', 'error']) {
  await check(async () => {
    const { connection, socket } = await fixture();
    const command = connection.send('Browser.getVersion');
    socket.dispatchEvent(new Event(eventType));
    await rejectsRedacted(command, eventType === 'close' ? 'CDP_CLOSED' : 'CDP_SOCKET_ERROR');
    await connection.close();
    socket.assertDetached();
  });
}

await check(async () => {
  const { connection, socket } = await fixture();
  const command = connection.send('Browser.getVersion');
  await connection.close();
  await rejectsRedacted(command, 'CDP_CLOSED');
  assert.equal(connection.failure(), undefined);
  socket.assertDetached();
});

await check(async () => {
  const { connection, socket } = await fixture({ close: false }, { timeoutMs: 20 });
  await rejectsRedacted(connection.close(), 'CDP_CLOSE_TIMEOUT');
  assert.equal(connection.failure(), 'CDP_CLOSE_TIMEOUT');
  await rejectsRedacted(connection.close(), 'CDP_CLOSE_TIMEOUT');
  socket.assertDetached();
});

await check(async () => {
  const { connection, socket } = await fixture({ closeThrows: true });
  await rejectsRedacted(connection.close(), 'CDP_CLOSE_FAILED');
  assert.equal(connection.failure(), 'CDP_CLOSE_FAILED');
  socket.assertDetached();
});

await check(async () => {
  const { connection, socket } = await fixture({ sendThrows: true });
  await rejectsRedacted(connection.send('Runtime.evaluate', { expression: secret }), 'CDP_SEND_FAILED');
  assert.equal(connection.failure(), 'CDP_SEND_FAILED');
  await connection.close();
  socket.assertDetached();
});

await check(async () => {
  const { connection, socket } = await fixture();
  const cycle = {};
  cycle.cycle = cycle;
  for (const args of [
    [secret, {}], ['Runtime.evaluate', []], ['Runtime.evaluate', {}, 'unsafe/token'],
    ['Runtime.evaluate\n', {}], ['Runtime.evaluate', {}, 'SESSION\n'],
    ['Runtime.evaluate', cycle], ['Runtime.evaluate', { value: 1n }],
    ['Runtime.evaluate', { toJSON() { throw new Error(secret); } }],
    ['Runtime.evaluate', { toJSON() { return []; } }],
  ])
    await rejectsRedacted(connection.send(...args), 'CDP_INVALID_COMMAND');
  assert.equal(socket.sent.length, 0);
  assert.equal(connection.failure(), undefined);
  await connection.close();
});

await check(async () => {
  const { connection, socket } = await fixture();
  const pending = Array.from({ length: 32 }, () => connection.send('Browser.getVersion'));
  await rejectsRedacted(connection.send('Browser.getVersion'), 'CDP_COMMAND_LIMIT');
  await Promise.all(pending.map((promise) => rejectsRedacted(promise, 'CDP_COMMAND_LIMIT')));
  assert.equal(socket.sent.length, 32);
  assert.equal(connection.failure(), 'CDP_COMMAND_LIMIT');
  await connection.close();
});

for (const limitKind of ['inbound', 'outbound', 'buffered']) {
  await check(async () => {
    const { connection, socket } = await fixture({}, { maxMessageBytes: 100 });
    let command;
    if (limitKind === 'inbound') {
      command = connection.send('Browser.getVersion');
      // UTF-8 bytes, not JavaScript string length, enforce the boundary.
      socket.raw('é'.repeat(51));
    } else {
      socket.bufferedAmount = limitKind === 'buffered' ? 100 : 0;
      command = connection.send('Runtime.evaluate', { expression: limitKind === 'outbound' ? secret.repeat(5) : '1' });
    }
    await rejectsRedacted(command, 'CDP_MESSAGE_LIMIT');
    assert.equal(connection.failure(), 'CDP_MESSAGE_LIMIT');
    await connection.close();
    socket.assertDetached();
  });
}

await check(async () => {
  const { connection, socket } = await fixture({}, { maxMessageBytes: 16 * 1024 * 1024 });
  const message = JSON.stringify({ method: 'Runtime.consoleAPICalled', params: { padding: 'x'.repeat(15 * 1024 * 1024) } });
  for (let index = 0; index < 5; index += 1)
    socket.raw(message);
  assert.equal(connection.failure(), 'CDP_MESSAGE_LIMIT');
  await connection.close();
  socket.assertDetached();
});

for (const asyncCallback of [false, true]) {
  await check(async () => {
    const { connection, socket } = await fixture();
    const command = connection.send('Browser.getVersion');
    const unsubscribe = connection.on('Runtime.consoleAPICalled', asyncCallback
      ? async () => { throw new Error(secret); }
      : () => { throw new Error(secret); });
    socket.receive({ method: 'Runtime.consoleAPICalled', params: { private: secret } });
    await rejectsRedacted(command, 'CDP_EVENT_HANDLER_FAILED');
    assert.equal(connection.failure(), 'CDP_EVENT_HANDLER_FAILED');
    await connection.close();
    unsubscribe();
    socket.assertDetached();
  });
}

await check(async () => {
  const { connection, socket } = await fixture();
  connection.on('Runtime.consoleAPICalled', () => new Promise(() => {}));
  for (let index = 0; index < 129; index += 1)
    socket.receive({ method: 'Runtime.consoleAPICalled' });
  assert.equal(connection.failure(), 'CDP_EVENT_LIMIT');
  await connection.close();
  socket.assertDetached();
});

await check(async () => {
  const { connection } = await fixture();
  assert.throws(() => connection.on(secret, () => {}), { message: 'CDP_INVALID_LISTENER' });
  assert.throws(() => connection.on('Runtime.consoleAPICalled', null), { message: 'CDP_INVALID_LISTENER' });
  const subscriptions = Array.from({ length: 128 }, () => connection.on('Runtime.consoleAPICalled', () => {}));
  assert.throws(() => connection.on('Runtime.consoleAPICalled', () => {}), { message: 'CDP_LISTENER_LIMIT' });
  subscriptions[0]();
  subscriptions[0]();
  connection.on('Runtime.consoleAPICalled', () => {});
  assert.equal(connection.failure(), undefined);
  await connection.close();
  for (const unsubscribe of subscriptions)
    unsubscribe();
});

console.log(`Inspector CDP self-test passed (${checks} checks).`);
