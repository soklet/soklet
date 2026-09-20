import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import { appsRequestPolicy, beginBrowserObservation, clickExpression, disconnectApps,
  exerciseApps, frameKind, validAppsOrigins } from './browser-probe.mjs';

const origin = 'http://127.0.0.1:48311';
const sandboxUrl = 'http://127.0.0.1:48312/sandbox';
const options = { origin, sandboxUrl, sessionId: 'MAIN' };
const font = 'https://fonts.googleapis.com/css2?family=Fredoka:wght@300..700&family=Roboto+Mono:ital,wght@0,100..700;1,100..700&display=swap';

function mockCdp() {
  const listeners = new Map(), commands = [];
  const cdp = {
    on(method, listener) {
      const callbacks = listeners.get(method) ?? new Set();
      callbacks.add(listener); listeners.set(method, callbacks);
      return () => callbacks.delete(listener);
    },
    async emit(method, event, session = 'MAIN') {
      for (const listener of listeners.get(method) ?? []) await listener(event, session);
    },
    async send(method, params = {}, session) {
      commands.push({ method, params, session });
      if (method === 'Page.getFrameTree') return { frameTree: { frame: {
        id: session === 'APP' ? 'app' : session === 'SANDBOX' ? 'sandbox' : 'main',
        url: session === 'APP' ? 'about:srcdoc' : session === 'SANDBOX' ? sandboxUrl : `${origin}/`,
        ...(session === 'APP' ? { parentId: 'sandbox' } : session === 'SANDBOX' ? { parentId: 'main' } : {}),
      } } };
      if (method === 'Runtime.evaluate') return { result: { type: 'boolean', value: true } };
      return {};
    },
    failure: () => undefined,
    commands,
  };
  return cdp;
}

async function attach(cdp, id, parent, frameId) {
  await cdp.emit('Target.attachedToTarget', { sessionId: id, waitingForDebugger: true,
    targetInfo: { type: 'iframe', targetId: frameId } }, parent);
  await cdp.emit('Runtime.executionContextCreated', { context: { id: 1,
    auxData: { isDefault: true, frameId } } }, id);
}

test('Apps profile requires two distinct canonical credential-free loopback origins', () => {
  assert.equal(validAppsOrigins(origin, sandboxUrl), true);
  for (const bad of [null, undefined, '', `${origin}/`, origin + '\n', origin + '?secret=token',
    'http://localhost:48311', 'https://127.0.0.1:48311', 'http://127.0.0.1:0',
    'http://127.1:48311', 'http://user:private@127.0.0.1:48311'])
    assert.equal(validAppsOrigins(bad, sandboxUrl), false);
  for (const bad of [null, undefined, '', `${origin}/sandbox`, sandboxUrl + '/', sandboxUrl + '?secret=token',
    sandboxUrl + '#private', sandboxUrl + '\n', 'http://127.0.0.1:48312/other/../sandbox',
    'http://localhost:48312/sandbox', 'https://127.0.0.1:48312/sandbox',
    'http://127.0.0.1:0/sandbox', 'http://user:private@127.0.0.1:48312/sandbox'])
    assert.equal(validAppsOrigins(origin, bad), false);
});

test('only the exact sandbox GET Document expands the ordinary host request allowlist', () => {
  assert.equal(appsRequestPolicy({ url: sandboxUrl, method: 'GET' }, options, 'Document'), 'SANDBOX_DOCUMENT');
  assert.equal(appsRequestPolicy({ url: `${origin}/api/mcp/send`, method: 'POST' }, options, 'Fetch'), 'SAME_ORIGIN');
  assert.equal(appsRequestPolicy({ url: font, method: 'GET' }, options, 'Stylesheet'), 'BLOCKED_PINNED_FONT');
  for (const [url, method, type] of [
    [sandboxUrl, 'POST', 'Document'], [sandboxUrl, 'GET', 'Fetch'],
    [sandboxUrl, 'get', 'Document'], [sandboxUrl + '?secret=x', 'GET', 'Document'],
    [sandboxUrl + '#private', 'GET', 'Document'], [sandboxUrl + '\n', 'GET', 'Document'],
    ['http://127.0.0.1:48312/other', 'GET', 'Document'],
    ['http://127.0.0.1:48313/app/private', 'GET', 'Document'],
    ['https://example.com/private', 'GET', 'Script'], ['file:///private', 'GET', 'Document'],
    ['data:text/html,private', 'GET', 'Document'], ['about:srcdoc', 'GET', 'Document'],
  ]) assert.equal(appsRequestPolicy({ url, method }, options, type), 'BLOCKED_UNEXPECTED');
  assert.equal(appsRequestPolicy({ url: origin, method: 'GET' }, { origin, sandboxUrl: origin }, 'Document'), 'BLOCKED_UNEXPECTED');
  assert.equal(appsRequestPolicy({ get url() { throw new Error('private'); } }, options, 'Document'), 'BLOCKED_UNEXPECTED');
});

test('frame classification retains no arbitrary URL or personalized content', () => {
  for (const [url, expected] of [[`${origin}/`, 'main'], [sandboxUrl, 'sandbox'], ['about:srcdoc', 'app'],
    ['about:blank', 'blank'], ['', 'blank'], [null, 'unexpected'],
    ['https://private.example/secret', 'unexpected'], [`${origin}/private`, 'unexpected']])
    assert.equal(frameKind(url, options), expected);
});

test('DOM click requires exactly one visible enabled matching control and no synthetic server calls', () => {
  let clicks = 0;
  const node = { disabled: false, textContent: ' Show catalog ', getClientRects: () => [1], click: () => clicks++ };
  const expression = clickExpression('button', 'Show catalog');
  assert.equal(runInNewContext(expression, { document: { querySelectorAll: () => [node] } }), true);
  assert.equal(clicks, 1);
  for (const nodes of [[], [node, node], [{ ...node, disabled: true }],
    [{ ...node, getClientRects: () => [] }], [{ ...node, textContent: 'Refresh catalog' }]])
    assert.equal(runInNewContext(expression, { document: { querySelectorAll: () => nodes } }), false);
  assert.equal(clicks, 1);
  assert.doesNotMatch(expression, /fetch|postMessage|callServerTool|tools\/call|XMLHttpRequest/);
});

test('browser setup installs interception before recursively unpausing OOPIF targets', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox');
  await attach(cdp, 'APP', 'SANDBOX', 'app');
  for (const session of ['SANDBOX', 'APP']) {
    const commands = cdp.commands.filter(command => command.session === session);
    assert.ok(commands.findIndex(command => command.method === 'Fetch.enable')
      < commands.findIndex(command => command.method === 'Runtime.runIfWaitingForDebugger'));
    const auto = commands.find(command => command.method === 'Target.setAutoAttach');
    assert.deepEqual(auto.params, { autoAttach: true, waitForDebuggerOnStart: true, flatten: true,
      filter: [{ type: 'iframe', exclude: false }, { exclude: true }] });
  }
  assert.equal(observation.facts().childTargets, 2);
  assert.equal(observation.facts().defaultContextsObserved, 2);
  assert.equal(observation.failure(), undefined);
  observation.close();
});

test('network observation blocks unexpected child-frame requests and never persists URL/header fields', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox');
  for (const [url, resourceType] of [[font, 'Stylesheet'], [sandboxUrl, 'Document']])
    await cdp.emit('Fetch.requestPaused', { requestId: 'ONE', request: { url, method: 'GET' }, resourceType });
  assert.equal(observation.facts().pageNetworkPolicySatisfied, true);
  await cdp.emit('Fetch.requestPaused', { requestId: 'TWO',
    request: { url: 'https://private.example/secret-token', method: 'GET', headers: { Authorization: 'private' } },
    resourceType: 'Fetch' }, 'SANDBOX');
  assert.equal(observation.failure(), 'APPS_BROWSER_NETWORK_REJECTED');
  assert.equal(observation.facts().unexpectedBrowserRequests, 1);
  assert.equal(observation.facts().pageNetworkPolicySatisfied, false);
  assert.equal(cdp.commands.at(-1).method, 'Fetch.failRequest');
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private|secret-token|Authorization/);
  observation.close();
});

test('fixed request, context, frame, target and exception failure codes remain bounded and redacted', async () => {
  for (const kind of ['request', 'context', 'frame', 'target', 'exception']) {
    const cdp = mockCdp();
    const observation = await beginBrowserObservation(cdp, options);
    if (kind === 'request') for (let i = 0; i < 257; ++i)
      await cdp.emit('Fetch.requestPaused', { requestId: `R${i}`, request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' });
    if (kind === 'context') for (let i = 1; i <= 33; ++i)
      await cdp.emit('Runtime.executionContextCreated', { context: { id: i, auxData: { isDefault: true, frameId: 'main' } } });
    if (kind === 'frame') for (let i = 1; i <= 16; ++i)
      await cdp.emit('Page.frameAttached', { frameId: `F${i}`, parentFrameId: 'main' });
    if (kind === 'target') for (let i = 1; i <= 8; ++i)
      await cdp.emit('Target.attachedToTarget', { sessionId: `S${i}`, targetInfo: { type: 'iframe' } });
    if (kind === 'exception') await cdp.emit('Runtime.exceptionThrown', { exceptionDetails: { text: 'private-token' } });
    assert.match(observation.failure(), /^APPS_BROWSER_[A-Z_]+$/);
    assert.doesNotMatch(JSON.stringify(observation.facts()), /private-token/);
    observation.close();
  }
});

test('mock orchestration exercises genuine DOM controls and evaluates App only in matched child context', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox');
  await attach(cdp, 'APP', 'SANDBOX', 'app');
  const facts = await exerciseApps(cdp, 'MAIN', observation);
  assert.equal(facts.catalogRendered, true);
  assert.equal(facts.refreshClickedViaDom, true);
  assert.equal(facts.pendingClearedPriorData, true);
  assert.equal(facts.refreshRendered, true);
  assert.equal(observation.facts().stage, 'REFRESH_COMPLETE');
  const evaluates = cdp.commands.filter(command => command.method === 'Runtime.evaluate');
  const refresh = evaluates.find(command => command.params.expression.includes('refresh.click()'));
  assert.equal(refresh.session, 'APP');
  assert.equal(refresh.params.contextId, 1);
  const attrs = evaluates.find(command => command.params.expression.includes("getAttribute('sandbox')"));
  assert.equal(attrs.session, 'SANDBOX');
  assert.ok(evaluates.every(command => !/fetch\(|callServerTool\(|postMessage\(/.test(command.params.expression)));
  assert.deepEqual(await disconnectApps(cdp, 'MAIN', observation), { disconnected: true });
  assert.equal(observation.facts().stage, 'DISCONNECTED');
  observation.close();
});

test('same-process nested frames work without creating artificial targets or isolated worlds', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  for (const [id, parentId, url, contextId] of [
    ['sandbox', 'main', sandboxUrl, 2], ['app', 'sandbox', 'about:srcdoc', 3],
  ]) {
    await cdp.emit('Page.frameAttached', { frameId: id, parentFrameId: parentId });
    await cdp.emit('Page.frameNavigated', { frame: { id, parentId, url } });
    await cdp.emit('Runtime.executionContextCreated', { context: { id: contextId,
      auxData: { isDefault: true, frameId: id } } });
  }
  const facts = await exerciseApps(cdp, 'MAIN', observation);
  assert.equal(facts.refreshRendered, true);
  assert.equal(observation.facts().childTargets, 0);
  const refresh = cdp.commands.find(command => command.method === 'Runtime.evaluate'
    && command.params.expression.includes('refresh.click()'));
  assert.equal(refresh.session, 'MAIN');
  assert.equal(refresh.params.contextId, 3);
  assert.ok(cdp.commands.every(command => command.method !== 'Page.createIsolatedWorld'));
  observation.close();
});

test('stale destroyed or cleared contexts cannot make the real App context ambiguous', async () => {
  for (const clear of ['destroy', 'clear', 'detach']) {
    const cdp = mockCdp();
    const observation = await beginBrowserObservation(cdp, options);
    await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox');
    await attach(cdp, 'APP', 'SANDBOX', 'app');
    if (clear === 'destroy') await cdp.emit('Runtime.executionContextDestroyed', { executionContextId: 1 }, 'APP');
    if (clear === 'clear') await cdp.emit('Runtime.executionContextsCleared', {}, 'APP');
    if (clear === 'detach') await cdp.emit('Page.frameDetached', { frameId: 'app', reason: 'swap' }, 'APP');
    await cdp.emit('Runtime.executionContextCreated', { context: { id: 2,
      auxData: { isDefault: true, frameId: 'app' } } }, 'APP');
    const facts = await exerciseApps(cdp, 'MAIN', observation);
    assert.equal(facts.refreshRendered, true);
    const refresh = cdp.commands.find(command => command.method === 'Runtime.evaluate'
      && command.params.expression.includes('refresh.click()'));
    assert.equal(refresh.params.contextId, 2);
    observation.close();
  }
});

test('observations cannot be reused with a different CDP connection or target session', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await assert.rejects(exerciseApps(mockCdp(), 'MAIN', observation), { message: 'APPS_BROWSER_OPTIONS_INVALID' });
  await assert.rejects(disconnectApps(cdp, 'OTHER', observation), { message: 'APPS_BROWSER_OPTIONS_INVALID' });
  observation.close();
  await assert.rejects(exerciseApps(cdp, 'MAIN', observation), { message: 'APPS_BROWSER_CLOSED' });
});

test('browser evaluation rejects exceptions and non-boolean results without retaining diagnostics', async () => {
  for (const response of [{ exceptionDetails: { text: 'private-token' }, result: { type: 'boolean', value: true } },
    { result: { type: 'string', value: 'private-token' } }]) {
    const cdp = mockCdp();
    const original = cdp.send;
    cdp.send = async (...args) => args[0] === 'Runtime.evaluate' ? response : original(...args);
    const observation = await beginBrowserObservation(cdp, options);
    await assert.rejects(exerciseApps(cdp, 'MAIN', observation), { message: 'APPS_BROWSER_EVALUATION_FAILED' });
    assert.doesNotMatch(JSON.stringify(observation.facts()), /private-token/);
    observation.close();
  }
});
