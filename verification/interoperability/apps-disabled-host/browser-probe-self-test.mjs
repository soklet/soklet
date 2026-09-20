import assert from 'node:assert/strict';
import { runInNewContext, Script } from 'node:vm';
import { test } from 'node:test';
import { appsDisabledExpression, appsRequestPolicy, assertAppsDisabled, beginBrowserObservation,
  clickExpression, decodeDisabledSnapshot, disabledSnapshotExpression, disconnectApps,
  exerciseAppsDisabled, frameKind, validAppsOrigins } from './browser-probe.mjs';

const origin = 'http://127.0.0.1:48311';
const sandboxUrl = 'http://127.0.0.1:48312/sandbox';
const options = { origin, sandboxUrl, sessionId: 'MAIN' };
const font = 'https://fonts.googleapis.com/css2?family=Fredoka:wght@300..700&family=Roboto+Mono:ital,wght@0,100..700;1,100..700&display=swap';

function mockCdp() {
  const listeners = new Map(), commands = [];
  return {
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
      if (method === 'Page.getFrameTree') return { frameTree: { frame: session === 'CHILD'
        ? { id: 'child', parentId: 'main', url: sandboxUrl } : { id: 'main', url: `${origin}/` } } };
      if (method === 'Runtime.evaluate') return { result: params.expression === disabledSnapshotExpression
        ? { type: 'number', value: 65535 } : { type: 'boolean', value: true } };
      return {};
    },
    failure: () => undefined,
    commands,
  };
}

function disabledDocument(changes = {}) {
  const connected = changes.connected === null ? null : { checked: true, ...changes.connected };
  const tools = changes.tools === null ? null : { checked: true, disabled: false, getClientRects: () => [1], ...changes.tools };
  const disconnect = changes.disconnect === null ? null : { disabled: false, getClientRects: () => [1],
    closest: () => ({ getAttribute: () => 'in' }), ...changes.disconnect };
  const doc = {
    querySelector: selector => selector.includes('Connect or disconnect') ? connected
      : selector.includes('Disconnect from server') ? disconnect : tools,
    querySelectorAll: selector => selector === 'button' ? [] : [],
  };
  if (changes.querySelectorAll) doc.querySelectorAll = changes.querySelectorAll;
  return doc;
}

test('profile requires canonical distinct credential-free loopback origins', () => {
  assert.equal(validAppsOrigins(origin, sandboxUrl), true);
  for (const bad of [null, undefined, '', `${origin}/`, origin + '\n', origin + '?secret=token',
    'http://localhost:48311', 'https://127.0.0.1:48311', 'http://127.0.0.1:0',
    'http://127.1:48311', 'http://user:private@127.0.0.1:48311'])
    assert.equal(validAppsOrigins(bad, sandboxUrl), false);
  for (const bad of [null, undefined, '', `${origin}/sandbox`, sandboxUrl + '/', sandboxUrl + '?private',
    sandboxUrl + '#private', sandboxUrl + '\n', 'http://127.0.0.1:48312/other/../sandbox',
    'http://localhost:48312/sandbox', 'https://127.0.0.1:48312/sandbox',
    'http://127.0.0.1:0/sandbox', 'http://user:private@127.0.0.1:48312/sandbox'])
    assert.equal(validAppsOrigins(origin, bad), false);
});

test('disabled profile never permits the separately declared sandbox, even for GET Document', () => {
  for (const method of ['GET', 'POST']) for (const type of ['Document', 'Fetch'])
    assert.equal(appsRequestPolicy({ url: sandboxUrl, method }, options, type), 'BLOCKED_SANDBOX');
  assert.equal(appsRequestPolicy({ url: `${origin}/api/mcp/send`, method: 'POST' }, options, 'Fetch'), 'SAME_ORIGIN');
  assert.equal(appsRequestPolicy({ url: font, method: 'GET' }, options, 'Stylesheet'), 'BLOCKED_PINNED_FONT');
  for (const url of [sandboxUrl + '?private', sandboxUrl + '#private', sandboxUrl + '\n',
    'http://127.0.0.1:48312/other', 'https://private.example/secret', 'file:///private',
    'data:text/html,private', 'about:srcdoc'])
    assert.equal(appsRequestPolicy({ url, method: 'GET' }, options, 'Document'), 'BLOCKED_UNEXPECTED');
  assert.equal(appsRequestPolicy({ url: `${origin}/`, method: 'GET' }, { origin, sandboxUrl: origin }, 'Document'), 'BLOCKED_UNEXPECTED');
  assert.equal(appsRequestPolicy({ get url() { throw new Error('private'); } }, options, 'Document'), 'BLOCKED_UNEXPECTED');
});

test('frame classifications retain only fixed enums', () => {
  for (const [url, expected] of [[`${origin}/`, 'main'], [sandboxUrl, 'sandbox'], ['about:srcdoc', 'app'],
    ['about:blank', 'blank'], ['', 'blank'], [null, 'unexpected'], ['https://private.example', 'unexpected']])
    assert.equal(frameKind(url, options), expected);
});

test('DOM click requires exactly one visible enabled matching control', () => {
  let clicks = 0;
  const node = { disabled: false, textContent: ' Execute Tool ', getClientRects: () => [1], click: () => clicks++ };
  const expression = clickExpression('button', 'Execute Tool');
  assert.equal(runInNewContext(expression, { document: { querySelectorAll: () => [node] } }), true);
  for (const nodes of [[], [node, node], [{ ...node, disabled: true }],
    [{ ...node, getClientRects: () => [] }], [{ ...node, textContent: 'Open App' }]])
    assert.equal(runInNewContext(expression, { document: { querySelectorAll: () => nodes } }), false);
  assert.equal(clicks, 1);
  assert.doesNotMatch(expression, /fetch|postMessage|callServerTool|tools\/call|XMLHttpRequest/);
});

test('absence expression requires a healthy connected selected Tools surface', () => {
  assert.equal(runInNewContext(appsDisabledExpression, { document: disabledDocument() }), true);
  for (const changes of [{ disconnect: null }, { disconnect: { disabled: true } },
    { disconnect: { getClientRects: () => [] } }, { disconnect: { closest: () => null } },
    { disconnect: { closest: () => ({ getAttribute: () => 'out' }) } },
    { tools: { checked: false } }, { tools: { disabled: true } }, { tools: { getClientRects: () => [] } }])
    assert.equal(runInNewContext(appsDisabledExpression, { document: disabledDocument(changes) }), false);
  for (const match of ['value="Apps"', 'iframe']) {
    const document = disabledDocument({ querySelectorAll: selector => selector.includes(match) ? [{}] : [] });
    assert.equal(runInNewContext(appsDisabledExpression, { document }), false);
  }
  const document = disabledDocument({ querySelectorAll: selector => selector === 'button'
    ? [{ getClientRects: () => [1], textContent: 'Refresh catalog refresh_catalog' }] : [] });
  assert.equal(runInNewContext(appsDisabledExpression, { document }), false);
});

test('atomic mask requires durable connected header and leaves the unmounted Servers switch diagnostic only', () => {
  const cases = [{}, { connected: null }, { connected: { checked: false } }, { tools: null },
    { tools: { disabled: true } }, { tools: { checked: false } }, { tools: { getClientRects: () => [] } },
    { disconnect: null }, { disconnect: { disabled: true, getClientRects: () => [], closest: () => null } }];
  for (const match of ['value="Apps"', 'value="Skills"', 'iframe', 'apps-form', 'open-app'])
    cases.push({ querySelectorAll: selector => selector.includes(match) ? [{}] : [] });
  cases.push({ querySelectorAll: selector => selector === 'button'
    ? [{ getClientRects: () => [1], textContent: 'Refresh catalog refresh_catalog' }] : [] });
  for (const changes of cases) {
    const document = disabledDocument(changes);
    const accepted = runInNewContext(appsDisabledExpression, { document });
    const snapshot = runInNewContext(disabledSnapshotExpression, { document });
    assert.equal((snapshot & 0xfffc) === 0xfffc, accepted);
    assert.equal(Object.keys(decodeDisabledSnapshot(snapshot)).length, 16);
  }
  const unmounted = decodeDisabledSnapshot(runInNewContext(disabledSnapshotExpression,
    { document: disabledDocument({ connected: null }) }));
  assert.equal(unmounted.connectSwitchExists, false);
  assert.equal(unmounted.connectSwitchChecked, false);
  assert.equal(unmounted.disconnectHeaderVisible, true);
  assert.equal(unmounted.connectedHeaderState, true);
  assert.equal(runInNewContext(appsDisabledExpression, { document: disabledDocument({ connected: null }) }), true);
  const missingHeader = runInNewContext(disabledSnapshotExpression, { document: disabledDocument({ disconnect: null }) });
  assert.equal(missingHeader, 0x0fff);
  assert.notEqual(missingHeader & 0xfffc, 0xfffc);
});

test('snapshot decoder rejects nonintegers and out-of-range values without preserving diagnostics', () => {
  for (const mask of [undefined, null, false, true, 'private-token', -1, 65536, NaN, Infinity, 1.5, {}])
    assert.throws(() => decodeDisabledSnapshot(mask), { message: 'APPS_BROWSER_EVALUATION_FAILED' });
  assert.ok(Object.values(decodeDisabledSnapshot(0)).every(value => value === false));
  assert.ok(Object.values(decodeDisabledSnapshot(65535)).every(value => value === true));
});

test('one atomic failed evaluation projects fixed booleans without accepting a hidden connected header', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  const original = cdp.send;
  let evaluations = 0;
  cdp.send = async (...args) => {
    if (args[0] !== 'Runtime.evaluate') return original(...args);
    evaluations += 1;
    assert.equal(args[1].expression, disabledSnapshotExpression);
    return { result: { type: 'number', value: 49148 } };
  };
  assert.equal(observation.facts().disabledPredicateFailure, null);
  await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_UI_DISABLED_ASSERTION_FAILED' });
  assert.equal(evaluations, 1);
  assert.equal(observation.facts().appsDisabledChecks, 0);
  const projection = observation.facts().disabledPredicateFailure;
  assert.deepEqual(projection, decodeDisabledSnapshot(49148));
  assert.equal(projection.connectedHeaderState, true);
  assert.equal(projection.disconnectHeaderVisible, false);
  assert.ok(Object.values(projection).every(value => typeof value === 'boolean'));
  assert.doesNotMatch(JSON.stringify(projection), /private|http|selector|textContent/);
  projection.connectSwitchExists = 'private-token';
  assert.equal(observation.facts().disabledPredicateFailure.connectSwitchExists, false);
  observation.close();
});

test('missing Servers switch passes only with every durable header and Tools/absence predicate satisfied', async () => {
  for (const unsetBit of [null, ...Array.from({ length: 14 }, (_, index) => index + 2)]) {
    const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
    const original = cdp.send;
    const mask = unsetBit === null ? 65532 : 65532 & ~(1 << unsetBit);
    cdp.send = async (...args) => args[0] === 'Runtime.evaluate'
      ? { result: { type: 'number', value: mask } } : original(...args);
    if (unsetBit === null) {
      assert.equal(await assertAppsDisabled(cdp, 'MAIN', observation), true);
      assert.equal(observation.facts().appsDisabledChecks, 1);
      assert.equal(observation.facts().disabledPredicateFailure, null);
    } else {
      await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_UI_DISABLED_ASSERTION_FAILED' });
      assert.equal(observation.facts().appsDisabledChecks, 0);
      assert.deepEqual(observation.facts().disabledPredicateFailure, decodeDisabledSnapshot(mask));
    }
    observation.close();
  }
});

test('ordinary DOM orchestration makes one tool call and repeatedly verifies Apps absence', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  const facts = await exerciseAppsDisabled(cdp, 'MAIN', observation);
  assert.deepEqual(facts, { connectedViaDom: true, toolsSelectedViaDom: true, ordinaryToolSelectedViaDom: true,
    ordinaryToolCalledViaDom: true, ordinaryResultRendered: true, appsControlsAbsent: true,
    skillsUiAbsent: true, appOnlyHelperAbsent: true, noAppFrames: true });
  assert.equal(observation.facts().appsDisabledChecks, 3);
  assert.equal(observation.facts().stage, 'ORDINARY_TOOL_COMPLETE');
  assert.equal(await assertAppsDisabled(cdp, 'MAIN', observation), true);
  assert.deepEqual(await disconnectApps(cdp, 'MAIN', observation), { disconnected: true });
  assert.equal(observation.facts().appsDisabledChecks, 5);
  assert.equal(observation.facts().stage, 'DISCONNECTED');
  const evals = cdp.commands.filter(command => command.method === 'Runtime.evaluate');
  for (const command of evals) assert.doesNotThrow(() => new Script(command.params.expression));
  assert.equal(evals.filter(command => command.params.expression.includes('Execute Tool')).length, 1);
  assert.ok(evals.every(command => command.session === 'MAIN'
    && !Object.hasOwn(command.params, 'contextId')
    && !/fetch\(|callServerTool\(|postMessage\(/.test(command.params.expression)));
  const select = evals.find(command => command.params.expression.includes('Showcatalogshow_catalog'));
  let clicks = 0;
  const node = { disabled: false, getClientRects: () => [1], textContent: 'Show catalog\nshow_catalog', click: () => clicks++ };
  assert.equal(runInNewContext(select.params.expression, { document: { querySelectorAll: () => [node] } }), true);
  for (const textContent of ['show_catalog', 'Refresh catalog\nrefresh_catalog', 'Show catalog\nshow_catalog_extra'])
    assert.equal(runInNewContext(select.params.expression, { document: { querySelectorAll: () => [{ ...node, textContent }] } }), false);
  assert.equal(clicks, 1);
  observation.close();
});

test('negative DOM mismatch is a failure, never converted to a passing absence', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  const original = cdp.send;
  cdp.send = async (...args) => args[0] === 'Runtime.evaluate'
    ? { result: { type: 'number', value: 0 } } : original(...args);
  await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_UI_DISABLED_ASSERTION_FAILED' });
  assert.equal(observation.facts().appsDisabledChecks, 0);
  observation.close();
});

test('same-process transient blank, sandbox and srcdoc frames permanently fail the negative profile', async () => {
  for (const url of ['', sandboxUrl, 'about:srcdoc']) {
    const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
    await cdp.emit('Page.frameAttached', { frameId: 'child', parentFrameId: 'main' });
    await cdp.emit('Page.frameNavigated', { frame: { id: 'child', parentId: 'main', url } });
    await cdp.emit('Page.frameDetached', { frameId: 'child', reason: 'remove' });
    assert.equal(observation.failure(), 'APPS_BROWSER_CHILD_FRAME_FORBIDDEN');
    assert.equal(observation.facts().childFramesObserved, 1);
    assert.equal(observation.facts().noChildFramesObserved, false);
    await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_BROWSER_CHILD_FRAME_FORBIDDEN' });
    observation.close();
  }
});

test('unexpected OOPIF is intercepted but never unpaused or allowed to issue same-origin traffic', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  await cdp.emit('Target.attachedToTarget', { sessionId: 'CHILD', waitingForDebugger: true,
    targetInfo: { type: 'iframe', targetId: 'child' } });
  assert.equal(observation.failure(), 'APPS_BROWSER_CHILD_FRAME_FORBIDDEN');
  assert.equal(observation.facts().childTargets, 1);
  assert.equal(observation.facts().sandboxFramesObserved, 1);
  assert.ok(cdp.commands.some(command => command.method === 'Fetch.enable' && command.session === 'CHILD'));
  assert.ok(cdp.commands.every(command => command.method !== 'Runtime.runIfWaitingForDebugger'));
  await cdp.emit('Fetch.requestPaused', { requestId: 'CHILD_REQUEST',
    request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' }, 'CHILD');
  assert.equal(cdp.commands.at(-1).method, 'Fetch.failRequest');
  observation.close();
});

test('network facts require the one pinned-font denial and zero sandbox visits', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  await cdp.emit('Fetch.requestPaused', { requestId: 'FONT', request: { url: font, method: 'GET' }, resourceType: 'Stylesheet' });
  assert.equal(observation.facts().pageNetworkPolicySatisfied, true);
  assert.equal(observation.facts().noChildFramesObserved, true);
  await cdp.emit('Fetch.requestPaused', { requestId: 'SANDBOX', request: { url: sandboxUrl, method: 'GET' }, resourceType: 'Document' });
  assert.equal(observation.failure(), 'APPS_BROWSER_NETWORK_REJECTED');
  assert.equal(observation.facts().sandboxDocumentRequests, 1);
  assert.equal(observation.facts().unexpectedBrowserRequests, 1);
  assert.equal(observation.facts().pageNetworkPolicySatisfied, false);
  assert.equal(cdp.commands.at(-1).method, 'Fetch.failRequest');
  assert.doesNotMatch(JSON.stringify(observation.facts()), /http|private|Authorization/);
  observation.close();
});

test('observation identity, closed state, evaluation exceptions and nonbooleans fail closed', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  await assert.rejects(exerciseAppsDisabled(mockCdp(), 'MAIN', observation), { message: 'APPS_BROWSER_OPTIONS_INVALID' });
  await assert.rejects(assertAppsDisabled(cdp, 'OTHER', observation), { message: 'APPS_BROWSER_OPTIONS_INVALID' });
  observation.close();
  await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_BROWSER_CLOSED' });
  for (const response of [{ exceptionDetails: { text: 'private-token' }, result: { type: 'boolean', value: true } },
    { result: { type: 'string', value: 'private-token' } }]) {
    const testCdp = mockCdp(), testObservation = await beginBrowserObservation(testCdp, options);
    const original = testCdp.send;
    testCdp.send = async (...args) => args[0] === 'Runtime.evaluate' ? response : original(...args);
    await assert.rejects(assertAppsDisabled(testCdp, 'MAIN', testObservation), { message: 'APPS_BROWSER_EVALUATION_FAILED' });
    assert.doesNotMatch(JSON.stringify(testObservation.facts()), /private-token/);
    testObservation.close();
  }
});

test('fixed observation limits reject excess requests, contexts, frames, targets and absence checks', async () => {
  for (const kind of ['request', 'context', 'frame', 'target', 'absence']) {
    const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
    if (kind === 'request') for (let i = 0; i < 257; ++i)
      await cdp.emit('Fetch.requestPaused', { requestId: `R${i}`, request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' });
    if (kind === 'context') for (let i = 1; i <= 33; ++i)
      await cdp.emit('Runtime.executionContextCreated', { context: { id: i, auxData: { isDefault: true, frameId: 'main' } } });
    if (kind === 'frame') for (let i = 1; i <= 16; ++i)
      await cdp.emit('Page.frameNavigated', { frame: { id: `F${i}`, url: `${origin}/` } });
    if (kind === 'target') await cdp.emit('Target.attachedToTarget', { sessionId: 'INVALID', targetInfo: { type: 'worker' } });
    if (kind === 'absence') {
      for (let i = 0; i < 256; ++i) await assertAppsDisabled(cdp, 'MAIN', observation);
      await assert.rejects(assertAppsDisabled(cdp, 'MAIN', observation), { message: 'APPS_UI_OBSERVATION_BOUND' });
    } else assert.match(observation.failure(), /^APPS_BROWSER_[A-Z_]+$/);
    observation.close();
  }
});

test('exception receipts preserve bounded locations but no error messages, tokens or arbitrary URLs', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  await cdp.emit('Runtime.executionContextCreated', { context: { id: 1, auxData: { isDefault: true, frameId: 'main' } } });
  const event = { exceptionDetails: { executionContextId: 1, text: 'private-token',
    url: `${origin}/assets/index-DZkZ6KYt.js`, lineNumber: 12, columnNumber: 34,
    exception: { description: 'private-token' }, stackTrace: { parentId: { id: 'private-token' },
      callFrames: Array.from({ length: 10 }, (_, i) => ({ functionName: 'private-token',
        url: 'https://private.example/secret', lineNumber: i, columnNumber: -1 })) } } };
  await cdp.emit('Runtime.exceptionThrown', event, 'UNRELATED');
  assert.equal(observation.facts().browserExceptionCount, 0);
  for (let i = 0; i < 300; ++i) await cdp.emit('Runtime.exceptionThrown', event);
  const facts = observation.facts();
  assert.equal(observation.failure(), 'APPS_BROWSER_EXCEPTION');
  assert.equal(facts.exceptionObservations.length, 8);
  assert.equal(facts.browserExceptionCount, 257);
  assert.equal(facts.exceptionCountSaturated, true);
  assert.equal(facts.exceptionObservationsTruncated, true);
  assert.equal(facts.exceptionObservations[0].frameKind, 'main');
  assert.deepEqual(facts.exceptionObservations[0].location, { source: 'PINNED_HOST_BUNDLE', line: 12, column: 34 });
  assert.equal(facts.exceptionObservations[0].stack.length, 8);
  assert.equal(facts.exceptionObservations[0].stackTruncated, true);
  assert.equal(facts.exceptionObservations[0].asyncParentPresent, true);
  assert.doesNotMatch(JSON.stringify(facts), /private|http|functionName|executionContextId|description/);
  facts.exceptionObservations[0].location.source = 'private-token';
  facts.exceptionObservations[0].stack[0].source = 'private-token';
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private/);
  observation.close();
});

test('intentional closing still classifies late blocked traffic and leaves it paused', async () => {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  const before = cdp.commands.length;
  observation.beginClosing();
  for (const url of [`${origin}/`, sandboxUrl, 'https://private.example/late'])
    await cdp.emit('Fetch.requestPaused', { requestId: 'LATE', request: { url, method: 'GET' }, resourceType: 'Document' });
  assert.equal(cdp.commands.length, before);
  assert.equal(observation.facts().browserRequestCount, 3);
  assert.equal(observation.facts().unexpectedBrowserRequests, 2);
  assert.equal(observation.facts().stage, 'BROWSER_CLOSE');
  assert.equal(observation.failure(), 'APPS_BROWSER_NETWORK_REJECTED');
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private|http/);
  observation.close();
});

test('only exact CDP_CLOSED after explicit close consumes a pending Fetch command failure', async () => {
  for (const [message, close, consumed] of [
    ['CDP_CLOSED', true, true], ['CDP_CLOSED', false, false],
    ['CDP_EVENT_HANDLER_FAILED', true, false], ['CDP_CLOSED private-token', true, false],
  ]) {
    const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
    const original = cdp.send;
    let rejectCommand;
    cdp.send = (...args) => args[0].startsWith('Fetch.')
      ? new Promise((_, reject) => { rejectCommand = reject; }) : original(...args);
    const pending = cdp.emit('Fetch.requestPaused', { requestId: 'RACE',
      request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' });
    if (close) observation.beginClosing();
    rejectCommand(new Error(message));
    if (consumed) await pending;
    else await assert.rejects(pending, { message });
    observation.close();
  }
});
