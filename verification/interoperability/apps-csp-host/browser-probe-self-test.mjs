import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import { appsRequestPolicy, beginBrowserObservation, clickExpression, disconnectApps,
  exerciseApps, frameKind, validAppsOrigins, validCanaryOrigin, EXPECTED_CSP, CSP_FAILURES,
  cspOperationExpression, exerciseCsp, projectCspRow, projectCspOperationResult, adjudicateCspEvidence } from './browser-probe.mjs';

const origin = 'http://127.0.0.1:48311';
const sandboxUrl = 'http://127.0.0.1:48312/sandbox';
const canaryOrigin = 'http://127.0.0.1:48313';
const options = { origin, sandboxUrl, canaryOrigin, sessionId: 'MAIN' };
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
  for (const session of ['MAIN', 'SANDBOX', 'APP']) {
    const commands = cdp.commands.filter(command => command.session === session);
    const enabled = commands.findIndex(command => command.method === 'Network.enable');
    const disabled = commands.findIndex(command => command.method === 'Network.setCacheDisabled');
    assert.deepEqual(commands[enabled].params,
      { maxTotalBufferSize: 0, maxResourceBufferSize: 0, maxPostDataSize: 0 });
    assert.deepEqual(commands[disabled].params, { cacheDisabled: true });
    assert.ok(enabled >= 0 && disabled > enabled);
    assert.ok(disabled < commands.findIndex(command => command.method === 'Runtime.enable'));
    if (session !== 'MAIN') assert.ok(disabled < commands.findIndex(command => command.method === 'Runtime.runIfWaitingForDebugger'));
  }
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
  assert.equal(observation.facts().cacheDisabledSessions, 3);
  assert.equal(observation.facts().allObservedSessionsCacheDisabled, true);
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

test('exception diagnostics retain only bounded structural facts at the event stage', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox');
  await attach(cdp, 'APP', 'SANDBOX', 'app');
  await exerciseApps(cdp, 'MAIN', observation);
  const privateText = 'private-token-exception-diagnostic';
  const details = { executionContextId: 1, text: privateText, exceptionId: 987654,
    url: 'about:srcdoc', lineNumber: 12, columnNumber: 34,
    exception: { className: privateText, description: privateText, objectId: privateText },
    stackTrace: { description: privateText, parentId: { id: privateText },
      callFrames: Array.from({ length: 10 }, (_, index) => ({ functionName: privateText,
        scriptId: privateText, url: index === 0 ? `${origin}/assets/index-DZkZ6KYt.js`
          : `https://private.example/${privateText}`, lineNumber: index, columnNumber: index + 10 })) } };
  await cdp.emit('Runtime.exceptionThrown', { exceptionDetails: details }, 'APP');
  const facts = observation.facts();
  assert.equal(observation.failure(), 'APPS_BROWSER_EXCEPTION');
  assert.equal(facts.browserExceptionCount, 1);
  assert.equal(facts.noBrowserExceptions, false);
  assert.equal(facts.exceptionObservationsTruncated, false);
  assert.deepEqual(facts.exceptionObservations, [{ sequence: 1, stage: 'REFRESH_COMPLETE',
    sessionKind: 'CHILD', frameKind: 'app', location: { source: 'SRCDOC_APP', line: 12, column: 34 },
    stack: Array.from({ length: 8 }, (_, index) => ({ source: index === 0 ? 'PINNED_HOST_BUNDLE' : 'UNKNOWN',
      line: index, column: index + 10 })), stackTruncated: true, asyncParentPresent: true }]);
  assert.doesNotMatch(JSON.stringify(facts), /private|token|987654|http|about:|functionName|scriptId|executionContextId|className|description/);
  await assert.rejects(disconnectApps(cdp, 'MAIN', observation), { message: 'APPS_BROWSER_EXCEPTION' });
  assert.equal(observation.facts().exceptionObservations[0].stage, 'REFRESH_COMPLETE');
  // Fact consumers cannot mutate the retained projection.
  facts.exceptionObservations[0].location.source = privateText;
  facts.exceptionObservations[0].stack[0].source = privateText;
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private/);
  observation.close();
});

test('unknown exception contexts, malformed coordinates and overflowing diagnostics stay bounded', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  const event = { exceptionDetails: { executionContextId: 999, url: `${sandboxUrl}?private-token`,
    lineNumber: -1, columnNumber: 10000001, stackTrace: { callFrames: [
      { url: `${origin}/assets/index-DZkZ6KYt.js?private-token`, lineNumber: 'private-token', columnNumber: 1.5 },
      { url: sandboxUrl, lineNumber: 0, columnNumber: 0 } ] } } };
  await cdp.emit('Runtime.exceptionThrown', event, 'UNRELATED');
  assert.equal(observation.facts().browserExceptionCount, 0);
  for (let i = 0; i < 300; ++i) await cdp.emit('Runtime.exceptionThrown', event);
  const facts = observation.facts();
  assert.equal(facts.exceptionObservations.length, 8);
  assert.equal(facts.exceptionObservationsTruncated, true);
  assert.equal(facts.browserExceptionCount, 257);
  assert.equal(facts.exceptionCountSaturated, true);
  assert.deepEqual(facts.exceptionObservations[0], { sequence: 1, stage: 'NAVIGATE',
    sessionKind: 'MAIN', frameKind: 'unknown', location: { source: 'UNKNOWN', line: null, column: null },
    stack: [{ source: 'UNKNOWN', line: null, column: null },
      { source: 'SANDBOX_DOCUMENT', line: 0, column: 0 }], stackTruncated: false, asyncParentPresent: false });
  assert.doesNotMatch(JSON.stringify(facts), /private|http|999/);
  observation.close();
});

test('intentional closing keeps late requests classified and paused without forwarding', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  await cdp.emit('Fetch.requestPaused', { requestId: 'BEFORE',
    request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' });
  assert.equal(cdp.commands.at(-1).method, 'Fetch.continueRequest');
  const before = cdp.commands.length;
  observation.beginClosing();
  for (const url of [`${origin}/`, 'https://private.example/late'])
    await cdp.emit('Fetch.requestPaused', { requestId: 'AFTER', request: { url, method: 'GET' }, resourceType: 'Fetch' });
  assert.equal(cdp.commands.length, before);
  assert.equal(observation.facts().browserRequestCount, 3);
  assert.equal(observation.facts().unexpectedBrowserRequests, 1);
  assert.equal(observation.facts().stage, 'BROWSER_CLOSE');
  assert.equal(observation.failure(), 'APPS_BROWSER_NETWORK_REJECTED');
  assert.equal(observation.facts().pageNetworkPolicySatisfied, false);
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private|http/);
  observation.close();
});

test('only exact CDP_CLOSED after intentional closing is consumed for a pending Fetch command', async () => {
  for (const [message, close, consumed] of [
    ['CDP_CLOSED', true, true], ['CDP_CLOSED', false, false],
    ['CDP_EVENT_HANDLER_FAILED', true, false], ['CDP_CLOSED private-token', true, false],
  ]) {
    const cdp = mockCdp();
    const observation = await beginBrowserObservation(cdp, options);
    const original = cdp.send;
    let rejectCommand;
    cdp.send = (...args) => args[0].startsWith('Fetch.')
      ? new Promise((_, reject) => { rejectCommand = reject; }) : original(...args);
    const pending = cdp.emit('Fetch.requestPaused', { requestId: 'RACE',
      request: { url: `${origin}/`, method: 'GET' }, resourceType: 'Document' });
    assert.equal(typeof rejectCommand, 'function');
    if (close) observation.beginClosing();
    rejectCommand(new Error(message));
    if (consumed) await pending;
    else await assert.rejects(pending, { message });
    assert.equal(observation.facts().browserRequestCount, 1);
    observation.close();
  }
});

test('intentional closing does not suppress unrelated child setup failures or exceptions', async () => {
  const cdp = mockCdp();
  const observation = await beginBrowserObservation(cdp, options);
  const original = cdp.send;
  cdp.send = async (...args) => {
    if (args[0] === 'Page.enable' && args[2] === 'APP') throw new Error('CDP_CLOSED');
    return original(...args);
  };
  observation.beginClosing();
  await cdp.emit('Target.attachedToTarget', { sessionId: 'APP',
    targetInfo: { type: 'iframe', targetId: 'app' } });
  assert.equal(observation.failure(), 'APPS_BROWSER_CHILD_SETUP_FAILED');
  await cdp.emit('Runtime.exceptionThrown', { exceptionDetails: { text: 'private-token' } });
  assert.equal(observation.facts().noBrowserExceptions, false);
  assert.equal(observation.facts().exceptionObservations[0].stage, 'BROWSER_CLOSE');
  assert.doesNotMatch(JSON.stringify(observation.facts()), /private-token/);
  observation.close();
});

// Canonical receipt example: only these fixed structural fields may escape.
function goodRow(index) {
  const negative = index === 2 || index === 3;
  return { phase: ['CONTROL_BEFORE', 'APP', 'CONTROL_AFTER'][Math.floor(index / 2)],
    operation: index % 2 ? 'image' : 'connect', context: negative ? 'APP' : 'MAIN',
    attempted: true, completed: true, timedOut: false, contextMatches: true,
    succeeded: !negative, failed: negative, responseMatches: !negative,
    violationBoundExceeded: false, settled: true, trusted: true, enforced: true,
    directiveMatches: true, targetMatches: true, documentMatches: true, policyMatches: true,
    violationCount: negative ? 1 : 0, matchingViolationCount: negative ? 1 : 0,
    unexpectedViolationCount: 0 };
}
function goodEvidence() {
  return { status: 'PASSED', rows: Array.from({ length: 6 }, (_, index) => goodRow(index)),
    policyVerified: true, appContextVerified: true, sandboxVerified: true,
    controlsBeforePassed: true, appDenialsPassed: true, controlsAfterPassed: true, settled: true };
}
function goodOperationResult(index) {
  return { row: goodRow(index), diagnostics: { documentKind: index === 2 || index === 3 ? 'ABOUT_SCHEME' : 'NONE' } };
}
function canaryEvent(index, overrides = {}) {
  const row = goodRow(index);
  return { requestId: `CANARY${index}`, frameId: row.context === 'APP' ? 'app' : 'main',
    resourceType: row.operation === 'connect' ? 'XHR' : 'Image',
    request: { url: `${canaryOrigin}/${row.operation}${index === 5 ? '?control=after' : ''}`, method: 'GET',
      headers: { Origin: row.context === 'APP' ? 'null' : origin,
        'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': row.operation === 'connect' ? 'empty' : 'image' } },
    ...overrides };
}

test('canary allowance requires a third canonical origin and the exact credential-free GET pair', async () => {
  assert.equal(validCanaryOrigin(canaryOrigin, options), true);
  for (const value of [null, undefined, '', origin, new URL(sandboxUrl).origin,
    canaryOrigin + '/', canaryOrigin + '\n', canaryOrigin + '?private',
    'http://localhost:48313', 'https://127.0.0.1:48313', 'http://127.0.0.1:0',
    'http://private@127.0.0.1:48313']) {
    assert.equal(validCanaryOrigin(value, options), false);
    await assert.rejects(beginBrowserObservation(mockCdp(), { ...options, canaryOrigin: value }),
      { message: 'APPS_BROWSER_OPTIONS_INVALID' });
  }
  for (const index of [0, 1, 5]) {
    const event = canaryEvent(index);
    assert.equal(appsRequestPolicy(event.request, options, event.resourceType),
      index ? 'CANARY_IMAGE' : 'CANARY_CONNECT');
    for (const change of [{ method: 'POST' }, { method: 'get' },
      { url: event.request.url + '?private=secret' }, { url: event.request.url + '#secret' },
      { url: canaryOrigin + '/private' }, { postData: '' }, { postDataEntries: [] }, { hasPostData: true },
      { headers: null }, { headers: { Authorization: 'private' } },
      { headers: { Cookie: 'private' } }, { headers: { 'Proxy-Authorization': 'private' } },
      { headers: { Referer: origin } }, { headers: { 'X-Private': 'secret' } },
      { headers: { Origin: origin, origin } }, { headers: { Accept: '\r\nprivate' } },
      { headers: { Accept: 'x'.repeat(2049) } }, { headers: { 'Sec-Fetch-Mode': 'no-cors' } },
      { headers: { 'Sec-Fetch-Dest': 'script' } }])
      assert.equal(appsRequestPolicy({ ...event.request, ...change }, options, event.resourceType), 'BLOCKED_UNEXPECTED');
    assert.equal(appsRequestPolicy(event.request, options, 'Document'), 'BLOCKED_UNEXPECTED');
  }
});

test('strict CSP verdict rejects missing, reordered, sparse, forged, extra and type-coerced evidence', () => {
  assert.equal(adjudicateCspEvidence(goodEvidence()), 'PASSED');
  assert.deepEqual(projectCspRow(goodRow(2)), goodRow(2));
  for (const key of Object.keys(goodEvidence()).filter(key => !['rows', 'status'].includes(key))) {
    const evidence = goodEvidence(); evidence[key] = false;
    assert.equal(adjudicateCspEvidence(evidence), 'FAILED', key);
  }
  for (let index = 0; index < 6; index++) for (const [key, value] of Object.entries(goodRow(index))) {
    const evidence = goodEvidence();
    evidence.rows[index][key] = typeof value === 'boolean' ? !value : typeof value === 'number' ? value + 1 : 'private';
    assert.equal(adjudicateCspEvidence(evidence), 'FAILED', `${index}:${key}`);
    const missing = goodEvidence(); delete missing.rows[index][key];
    assert.equal(adjudicateCspEvidence(missing), 'FAILED', `missing:${key}`);
  }
  for (const evidence of [null, {}, { ...goodEvidence(), private: 'secret' },
    { ...goodEvidence(), status: 'FAILED' }, { ...goodEvidence(), rows: Array(6) },
    { ...goodEvidence(), rows: goodEvidence().rows.toReversed() },
    { ...goodEvidence(), rows: goodEvidence().rows.slice(1) },
    { ...goodEvidence(), rows: [...goodEvidence().rows, goodRow(5)] }])
    assert.equal(adjudicateCspEvidence(evidence), 'FAILED');
  const hole = goodEvidence(); delete hole.rows[3];
  assert.equal(adjudicateCspEvidence(hole), 'FAILED');
  for (const row of [{ ...goodRow(2), rawUrl: 'private' }, { ...goodRow(2), violationCount: NaN },
    { ...goodRow(2), violationCount: 10 }, { ...goodRow(2), trusted: 1 },
    { ...goodRow(2), get policyMatches() { throw new Error('private'); } }]) assert.equal(projectCspRow(row), null);
});

async function simulatedOperation(index, { eventChange = {}, eventCount = 1, failLoad,
  neverCompletes = false, badBody = false, wrongLocation = false, badDimensions = false,
  decodeFails = false, eventDelay = 5 } = {}) {
  const row = goodRow(index), negative = row.context === 'APP', listeners = new Set();
  const calls = { fetch: 0, image: 0, decode: 0, listenerBeforeAction: false };
  const event = { isTrusted: true, disposition: 'enforce',
    effectiveDirective: row.operation === 'connect' ? 'connect-src' : 'img-src',
    blockedURI: `${canaryOrigin}/${row.operation}`, documentURI: 'about', originalPolicy: EXPECTED_CSP,
    ...eventChange };
  const fire = () => { for (let i = 0; i < eventCount; i++) for (const listener of listeners) listener(event); };
  const fail = failLoad ?? negative;
  const document = { addEventListener(type, listener) {
    assert.equal(type, 'securitypolicyviolation'); listeners.add(listener);
  }, removeEventListener(type, listener) { assert.equal(type, 'securitypolicyviolation'); listeners.delete(listener); } };
  const onAction = () => {
    calls.listenerBeforeAction = listeners.size === 1;
    if (negative && eventCount) setTimeout(fire, eventDelay);
  };
  class Image {
    constructor() { this.naturalWidth = badDimensions ? 2 : 1; this.naturalHeight = 1; }
    set src(value) {
      assert.equal(value, `${canaryOrigin}/image${index === 5 ? '?control=after' : ''}`);
      assert.equal(this.crossOrigin, 'anonymous'); assert.equal(this.referrerPolicy, 'no-referrer');
      calls.image++; onAction();
      if (!neverCompletes) setTimeout(() => fail ? this.onerror?.() : this.onload?.(), 0);
    }
    decode() { calls.decode++; return decodeFails ? Promise.reject(new Error('private')) : Promise.resolve(); }
  }
  const expression = cspOperationExpression({ ...options, phase: row.phase, operation: row.operation });
  const result = await runInNewContext(expression, { document, Image, location: {
    href: wrongLocation ? 'about:blank' : negative ? 'about:srcdoc' : `${origin}/` },
    setTimeout: (callback, ms) => setTimeout(callback, ms === 2000 ? 35 : ms === 100 ? 8 : ms), clearTimeout,
    fetch: async (url, init) => {
      calls.fetch++; assert.equal(url, `${canaryOrigin}/connect`);
      assert.deepEqual(JSON.parse(JSON.stringify(init)), { mode: 'cors', credentials: 'omit', cache: 'no-store',
        referrerPolicy: 'no-referrer', redirect: 'error' });
      onAction(); if (neverCompletes) return new Promise(() => {});
      if (fail) throw new Error('private-network-error');
      return { ok: true, status: 200, headers: { get: name => name === 'content-type' ? 'text/plain;charset=utf-8' : null },
        text: async () => badBody ? 'private-body' : 'soklet-csp-canary' };
    },
  });
  assert.equal(listeners.size, 0);
  assert.equal(calls.listenerBeforeAction, true);
  assert.doesNotMatch(JSON.stringify(result), /private|http|about:|originalPolicy|blockedURI/);
  return { ...projectCspOperationResult(result), calls };
}

test('operation expressions use exact safe fetch/image settings and independently await queued trusted events', async () => {
  for (let index = 0; index < 6; index++) {
    const { row, calls } = await simulatedOperation(index);
    assert.deepEqual(row, goodRow(index));
    assert.equal(calls.fetch, index % 2 ? 0 : 1);
    assert.equal(calls.image, index % 2 ? 1 : 0);
    assert.equal(calls.decode, index % 2 && index !== 3 ? 1 : 0);
  }
  for (const blockedURI of [canaryOrigin, `${canaryOrigin}/connect`])
    assert.deepEqual((await simulatedOperation(2, { eventChange: { blockedURI } })).row, goodRow(2));
  assert.equal(Object.isFrozen(CSP_FAILURES), true);
  assert.throws(() => cspOperationExpression({ ...options, phase: 'private', operation: 'connect' }),
    { message: 'APPS_CSP_OPTIONS_INVALID' });
});

test('fake/report-only/wrong-directive or policy/no-event/duplicate CSP denials cannot pass', async () => {
  for (const config of [{ eventChange: { isTrusted: false } }, { eventChange: { disposition: 'report' } },
    { eventChange: { effectiveDirective: 'script-src' } }, { eventChange: { blockedURI: 'https://private/secret' } },
    { eventChange: { documentURI: 'http://private/secret' } }, { eventChange: { originalPolicy: 'private' } },
    { eventCount: 0 }, { eventCount: 2 }, { eventCount: 10 }, { failLoad: false },
    { neverCompletes: true }, { wrongLocation: true }]) {
    const evidence = goodEvidence(); evidence.rows[2] = (await simulatedOperation(2, config)).row;
    assert.equal(adjudicateCspEvidence(evidence), 'FAILED');
  }
  for (const [index, config] of [[0, { failLoad: true }], [0, { badBody: true }],
    [1, { badDimensions: true }], [1, { decodeFails: true }], [1, { neverCompletes: true }]]) {
    const evidence = goodEvidence(); evidence.rows[index] = (await simulatedOperation(index, config)).row;
    assert.equal(adjudicateCspEvidence(evidence), 'FAILED');
  }
});

async function preparedCsp({ appContinued = true, beforeRow, resultForRow, skipCanaryAt } = {}) {
  const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
  await cdp.emit('Runtime.executionContextCreated', { context: { id: 11, auxData: { isDefault: true, frameId: 'main' } } });
  await attach(cdp, 'SANDBOX', 'MAIN', 'sandbox'); await attach(cdp, 'APP', 'SANDBOX', 'app');
  await exerciseApps(cdp, 'MAIN', observation);
  const original = cdp.send; let index = 0;
  cdp.send = async (method, params, session) => {
    if (method !== 'Runtime.evaluate' || params.awaitPromise !== true) return original(method, params, session);
    cdp.commands.push({ method, params, session });
    const current = index++, row = goodRow(current);
    assert.equal(session, row.context); assert.equal(params.contextId, row.context === 'MAIN' ? 11 : 1);
    assert.equal(params.allowUnsafeEvalBlockedByCSP, false);
    await beforeRow?.({ cdp, observation, index: current });
    if ((row.context === 'MAIN' || appContinued) && current !== skipCanaryAt)
      await cdp.emit('Fetch.requestPaused', canaryEvent(current), row.context);
    return resultForRow ? resultForRow(current) : { result: { type: 'object', value: goodOperationResult(current) } };
  };
  const phases = [];
  const setPhase = phase => { phases.push(phase); };
  return { cdp, observation, phases, setPhase };
}

test('full CSP orchestration continues real App canary interceptions and requires four main controls', async () => {
  for (const appContinued of [false, true]) {
    const { cdp, observation, phases, setPhase } = await preparedCsp({ appContinued });
    const evidence = await exerciseCsp(cdp, 'MAIN', observation, { setPhase });
    assert.deepEqual(evidence, goodEvidence());
    assert.deepEqual(phases, ['CONTROL_BEFORE', 'APP', 'CONTROL_AFTER']);
    assert.deepEqual(observation.facts().canaryNetwork, { phase: 'CONTROL_AFTER',
      continuedByOperation: [1, 1, +appContinued, +appContinued, 1, 1],
      mainContinued: 4, appContinued: appContinued ? 2 : 0, blocked: 0 });
    const appRequests = cdp.commands.filter(command => command.method.startsWith('Fetch.')
      && command.params.requestId?.startsWith('CANARY') && command.session === 'APP');
    assert.equal(appRequests.length, appContinued ? 2 : 0);
    assert.ok(appRequests.every(command => command.method === 'Fetch.continueRequest'));
    assert.ok(cdp.commands.filter(command => command.method === 'Runtime.evaluate')
      .every(command => command.params.allowUnsafeEvalBlockedByCSP === false));
    assert.ok(cdp.commands.every(command => !['Page.setBypassCSP', 'Page.createIsolatedWorld'].includes(command.method)));
    evidence.rows[0].trusted = false;
    assert.equal(observation.facts().cspEvidence.rows[0].trusted, true);
    assert.doesNotMatch(JSON.stringify(observation.facts()), /http|private|soklet-csp-canary|about:srcdoc/);
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_OPTIONS_INVALID' });
    observation.close();
  }
});

test('canary interception rejects idle/wrong-phase/wrong-frame/wrong-origin and duplicate traffic', async () => {
  const idleCdp = mockCdp(), idle = await beginBrowserObservation(idleCdp, options);
  await idleCdp.emit('Fetch.requestPaused', canaryEvent(0));
  assert.equal(idle.failure(), 'APPS_CSP_NETWORK_REJECTED');
  assert.equal(idle.facts().canaryNetwork.blocked, 1);
  assert.equal(idleCdp.commands.at(-1).method, 'Fetch.failRequest'); idle.close();
  for (const kind of ['wrong-phase', 'wrong-frame', 'wrong-origin', 'duplicate']) {
    const { cdp, observation, setPhase } = await preparedCsp({ beforeRow: async ({ cdp, index }) => {
      if (index !== 2) return;
      const event = canaryEvent(2);
      if (kind === 'wrong-phase') { event.frameId = 'main'; event.request.headers.Origin = origin; }
      if (kind === 'wrong-frame') event.frameId = 'sandbox';
      if (kind === 'wrong-origin') event.request.headers.Origin = origin;
      await cdp.emit('Fetch.requestPaused', event, kind === 'wrong-phase' ? 'MAIN' : 'APP');
    } });
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_NETWORK_REJECTED' });
    assert.equal(observation.facts().canaryNetwork.blocked, 1);
    assert.ok(cdp.commands.some(command => command.method === 'Fetch.failRequest'
      && command.params.requestId === 'CANARY2'));
    observation.close();
  }
});

test('failed/malformed evaluations and unknown exceptions keep only safe partial evidence', async () => {
  for (const kind of ['row', 'raw', 'throw', 'exception']) {
    const { cdp, observation, phases, setPhase } = await preparedCsp({ resultForRow: index => {
      if (index !== 2) return { result: { type: 'object', value: goodOperationResult(index) } };
      if (kind === 'throw') throw new Error('private-token http://private/path');
      if (kind === 'exception') return { exceptionDetails: { text: 'private-token' } };
      return { result: { type: 'object', value: kind === 'raw' ? { private: 'secret' }
        : { ...goodOperationResult(index), row: { ...goodRow(index), trusted: false } } } };
    } });
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), {
      message: kind === 'row' ? 'APPS_CSP_ROW_REJECTED' : 'APPS_CSP_EVALUATION_FAILED' });
    const facts = observation.facts();
    assert.equal(facts.cspEvidence.status, 'FAILED');
    assert.equal(facts.cspEvidence.rows.length, kind === 'row' ? 3 : 2);
    assert.deepEqual(phases, ['CONTROL_BEFORE', 'APP']);
    assert.doesNotMatch(JSON.stringify(facts), /private|secret|http/);
    assert.equal(adjudicateCspEvidence(facts.cspEvidence), 'FAILED'); observation.close();
  }
});

test('CSP setup enforces actual surviving default context, exact meta policy, and phase callback', async () => {
  for (const kind of ['context', 'policy', 'phase']) {
    const { cdp, observation, setPhase } = await preparedCsp();
    if (kind === 'context') await cdp.emit('Runtime.executionContextDestroyed', { executionContextId: 1 }, 'APP');
    if (kind === 'policy') {
      const original = cdp.send;
      cdp.send = (...args) => args[0] === 'Runtime.evaluate' && args[1].expression.includes('metas.length')
        ? Promise.resolve({ result: { type: 'boolean', value: false } }) : original(...args);
    }
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase: kind === 'phase'
      ? () => { throw new Error('private-token'); } : setPhase }), {
      message: kind === 'context' ? 'APPS_CSP_CONTEXT_MISMATCH' : kind === 'policy'
        ? 'APPS_CSP_POLICY_MISMATCH' : 'APPS_CSP_PHASE_FAILED' });
    assert.equal(observation.facts().cspEvidence.rows.length, 0);
    assert.doesNotMatch(JSON.stringify(observation.facts()), /private-token/); observation.close();
  }
});

test('first canary rejection has fixed bounded admission diagnostics without changing strict decisions', async () => {
  const variants = [
    ['ORIGIN_MISMATCH', event => { delete event.request.headers.Origin; }],
    ['UNKNOWN_HEADER', event => { event.request.headers['X-Private-Secret'] = 'private-token'; }],
    ['UNKNOWN_HEADER', event => { event.request.headers.Authorization = 'private-token'; }],
    ['DUPLICATE_HEADER', event => { event.request.headers.origin = origin; }],
    ['HEADER_VALUE_INVALID', event => { event.request.headers.Accept = 'private\r\nvalue'; }],
    ['FETCH_MODE_MISMATCH', event => { event.request.headers['Sec-Fetch-Mode'] = 'no-cors'; }],
    ['FETCH_DESTINATION_MISMATCH', event => { event.request.headers['Sec-Fetch-Dest'] = 'script'; }],
    ['FRAME_PHASE_MISMATCH', event => { event.frameId = 'sandbox'; }],
    ['METHOD_MISMATCH', event => { event.request.method = 'POST'; }],
    ['BODY_PRESENT', event => { event.request.postData = 'private-token'; }],
    ['HEADERS_NOT_OBJECT', event => { event.request.headers = null; }],
    ['RESOURCE_TYPE_MISMATCH', event => { event.resourceType = 'Fetch'; }],
    ['URL_MISMATCH', event => { event.request.url += '?private-token'; }],
  ];
  for (const [reason, mutate] of variants) {
    const { cdp, observation, setPhase } = await preparedCsp({ beforeRow: async ({ cdp, index }) => {
      if (index !== 0) return;
      const event = canaryEvent(0); mutate(event);
      await cdp.emit('Fetch.requestPaused', event);
    } });
    assert.equal(observation.facts().canaryAdmissionRejection, null);
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_NETWORK_REJECTED' });
    const diagnostic = observation.facts().canaryAdmissionRejection;
    assert.equal(diagnostic.reason, reason);
    assert.equal(diagnostic.phase, 'CONTROL_BEFORE');
    assert.equal(diagnostic.mainPhase, true); assert.equal(diagnostic.appPhase, false);
    assert.equal(diagnostic.mainSessionMatches, true);
    assert.equal(diagnostic.activeOperationMatches, reason !== 'URL_MISMATCH');
    assert.equal(diagnostic.requestBoundSatisfied, true); assert.equal(diagnostic.browserOpen, true);
    assert.equal(diagnostic.firstForOperation, reason !== 'URL_MISMATCH');
    if (reason === 'ORIGIN_MISMATCH') {
      assert.equal(diagnostic.requestOriginKind, 'ABSENT');
      assert.equal(diagnostic.expectedOriginMatches, false);
      assert.equal(diagnostic.initialPolicy, 'CANARY_CONNECT');
      assert.equal(diagnostic.knownHeadersPresent.origin, false);
    }
    assert.ok(Object.values(diagnostic.knownHeadersPresent).every(value => typeof value === 'boolean'));
    assert.equal(Object.keys(diagnostic.knownHeadersPresent).length, 18);
    assert.equal(diagnostic.resourceType, reason === 'RESOURCE_TYPE_MISMATCH' ? 'Fetch' : 'XHR');
    assert.doesNotMatch(JSON.stringify(diagnostic), /X-Private|private-token|http|no-cors|script/);
    assert.ok(JSON.stringify(diagnostic).length < 2000);
    await cdp.emit('Fetch.requestPaused', canaryEvent(1));
    assert.deepEqual(observation.facts().canaryAdmissionRejection, diagnostic);
    diagnostic.knownHeadersPresent.origin = !diagnostic.knownHeadersPresent.origin;
    assert.notEqual(observation.facts().canaryAdmissionRejection.knownHeadersPresent.origin,
      diagnostic.knownHeadersPresent.origin);
    observation.close();
  }
});

test('rejected resource type diagnostics use only the fixed CDP enum and never broaden admission', async () => {
  for (const resourceType of ['Document', 'Stylesheet', 'Image', 'Media', 'Font', 'Script',
    'TextTrack', 'Fetch', 'Prefetch', 'EventSource', 'WebSocket', 'Manifest', 'SignedExchange',
    'Ping', 'CSPViolationReport', 'Preflight', 'Other', 'private-secret', undefined, null, 1]) {
    const cdp = mockCdp(), observation = await beginBrowserObservation(cdp, options);
    await cdp.emit('Fetch.requestPaused', canaryEvent(0, { resourceType }));
    const diagnostic = observation.facts().canaryAdmissionRejection;
    assert.equal(diagnostic.reason, 'RESOURCE_TYPE_MISMATCH');
    assert.equal(diagnostic.resourceType, ['private-secret', undefined, null, 1].includes(resourceType)
      ? 'UNKNOWN' : resourceType);
    assert.equal(diagnostic.resourceTypeAllowed, false);
    assert.equal(cdp.commands.at(-1).method, 'Fetch.failRequest');
    assert.doesNotMatch(JSON.stringify(diagnostic), /private-secret/);
    observation.close();
  }
});

test('document URI matches only the pinned scheme-only report while real srcdoc identity stays independent', async () => {
  for (const [documentURI, documentKind] of [
    ['about:srcdoc', 'SRCDOC'], ['about', 'ABOUT_SCHEME'], ['about:blank', 'ABOUT_BLANK'], ['about:', 'UNKNOWN'],
    ['', 'EMPTY'], [null, 'NULL'], [undefined, 'UNDEFINED'], [sandboxUrl, 'SANDBOX_DOCUMENT'],
    [`${origin}/`, 'MAIN_DOCUMENT'], [new URL(sandboxUrl).origin, 'SANDBOX_ORIGIN'],
    [origin, 'MAIN_ORIGIN'], [canaryOrigin, 'CANARY_ORIGIN'],
    ['https://private.example/secret-token', 'UNKNOWN'], [17, 'UNKNOWN'],
  ]) {
    const result = await simulatedOperation(2, { eventChange: { documentURI } });
    assert.deepEqual(result.diagnostics, { documentKind });
    assert.equal(result.row.documentMatches, documentKind === 'ABOUT_SCHEME');
    const evidence = goodEvidence(); evidence.rows[2] = result.row;
    assert.equal(adjudicateCspEvidence(evidence), documentKind === 'ABOUT_SCHEME' ? 'PASSED' : 'FAILED');
    assert.doesNotMatch(JSON.stringify(result), /private.example|secret-token|http|about:/);
  }
  assert.deepEqual((await simulatedOperation(0)).diagnostics, { documentKind: 'NONE' });
  for (const value of [null, {}, { ...goodOperationResult(2), private: 'secret' },
    { row: goodRow(2), diagnostics: { documentKind: 'private-secret' } },
    { row: goodRow(2), diagnostics: { documentKind: 'SRCDOC', raw: 'secret' } },
    { row: goodRow(2), diagnostics: { get documentKind() { throw new Error('private'); } } }])
    assert.equal(projectCspOperationResult(value), null);
  const { cdp, observation, setPhase } = await preparedCsp({ resultForRow: index => ({ result: {
    type: 'object', value: index === 2 ? { row: { ...goodRow(2), documentMatches: false,
      matchingViolationCount: 0, unexpectedViolationCount: 1, timedOut: true, settled: false },
    diagnostics: { documentKind: 'SRCDOC' } } : goodOperationResult(index),
  } }) });
  await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_ROW_REJECTED' });
  const diagnostics = observation.facts().cspViolationDiagnostics;
  assert.deepEqual(diagnostics, [
    { phase: 'CONTROL_BEFORE', operation: 'connect', documentKind: 'NONE' },
    { phase: 'CONTROL_BEFORE', operation: 'image', documentKind: 'NONE' },
    { phase: 'APP', operation: 'connect', documentKind: 'SRCDOC' },
  ]);
  diagnostics[2].documentKind = 'UNKNOWN';
  assert.equal(observation.facts().cspViolationDiagnostics[2].documentKind, 'SRCDOC');
  observation.close();
});

test('cache instrumentation failures stop main setup or child unpause with fixed redacted failure', async () => {
  for (const command of ['Network.enable', 'Network.setCacheDisabled']) {
    for (const session of ['MAIN', 'APP']) {
      const cdp = mockCdp(), original = cdp.send;
      cdp.send = async (...args) => {
        if (args[0] === command && args[2] === session) throw new Error('private-network-setup-error');
        return original(...args);
      };
      if (session === 'MAIN') {
        await assert.rejects(beginBrowserObservation(cdp, options), { message: 'APPS_BROWSER_CACHE_SETUP_FAILED' });
        assert.ok(cdp.commands.every(row => row.method !== 'Page.navigate' && row.method !== 'Runtime.enable'));
      } else {
        const observation = await beginBrowserObservation(cdp, options);
        await attach(cdp, 'APP', 'MAIN', 'app');
        assert.equal(observation.failure(), 'APPS_BROWSER_CACHE_SETUP_FAILED');
        assert.equal(observation.facts().cacheDisabledSessions, 1);
        assert.equal(observation.facts().allObservedSessionsCacheDisabled, false);
        assert.ok(cdp.commands.every(row => row.session !== 'APP' || row.method !== 'Runtime.runIfWaitingForDebugger'));
        assert.doesNotMatch(JSON.stringify(observation.facts()), /private-network-setup-error/);
        observation.close();
      }
    }
  }
});

test('fixed final image alias is phase-bound and arbitrary query variants stay blocked', async () => {
  const base = canaryEvent(1), after = canaryEvent(5);
  for (const event of [base, after]) assert.equal(appsRequestPolicy(event.request, options, 'Image'), 'CANARY_IMAGE');
  for (const suffix of ['?', '?control=before', '?control=after&private=x', '?control=after&control=after',
    '?control=AFTER', '?control=%61fter', '?control=after#private', '?private=secret', '?control=after/'])
    assert.equal(appsRequestPolicy({ ...base.request, url: `${canaryOrigin}/image${suffix}` }, options, 'Image'), 'BLOCKED_UNEXPECTED');
  assert.equal(appsRequestPolicy({ ...canaryEvent(0).request, url: `${canaryOrigin}/connect?control=after` },
    options, 'XHR'), 'BLOCKED_UNEXPECTED');
  for (const wrongIndex of [1, 3, 5]) {
    const { cdp, observation, setPhase } = await preparedCsp({ beforeRow: async ({ cdp, index }) => {
      if (index !== wrongIndex) return;
      const event = canaryEvent(index);
      event.request.url = `${canaryOrigin}/image${index === 5 ? '' : '?control=after'}`;
      await cdp.emit('Fetch.requestPaused', event, index === 3 ? 'APP' : 'MAIN');
    } });
    await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_NETWORK_REJECTED' });
    const diagnostic = observation.facts().canaryAdmissionRejection;
    assert.equal(diagnostic.reason, 'URL_PHASE_MISMATCH');
    assert.equal(diagnostic.operation, 'image'); assert.equal(diagnostic.exactUrl, true);
    assert.equal(diagnostic.resourceTypeAllowed, true); assert.equal(diagnostic.urlMatchesPhase, false);
    assert.equal(diagnostic.framePhaseMatches, true); assert.equal(diagnostic.expectedOriginMatches, true);
    assert.equal(observation.facts().canaryNetwork.blocked, 1);
    observation.close();
  }
});

test('successful decoded final image cannot replace the required live final continuation', async () => {
  const { cdp, observation, setPhase } = await preparedCsp({ appContinued: false, skipCanaryAt: 5 });
  await assert.rejects(exerciseCsp(cdp, 'MAIN', observation, { setPhase }), { message: 'APPS_CSP_EVIDENCE_REJECTED' });
  assert.deepEqual(observation.facts().canaryNetwork.continuedByOperation, [1, 1, 0, 0, 1, 0]);
  assert.equal(observation.facts().cspEvidence.rows.length, 6);
  assert.equal(observation.facts().cspEvidence.rows[5].responseMatches, true);
  assert.equal(adjudicateCspEvidence(observation.facts().cspEvidence), 'FAILED');
  observation.close();
});
