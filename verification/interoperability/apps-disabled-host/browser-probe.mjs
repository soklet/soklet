import { browserRequestPolicy } from '../inspector/web-probe.mjs';

const states = new WeakMap();
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
const fail = code => { throw new Error(code); };
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const MAX_REQUESTS = 256, MAX_CONTEXTS = 32, MAX_FRAMES = 16, MAX_SESSIONS = 8;
const MAX_EXCEPTION_DETAILS = 8, MAX_EXCEPTION_STACK = 8, UI_TIMEOUT_MS = 15000;

export function validAppsOrigins(origin, sandboxUrl) {
  try {
    const main = new URL(origin), sandbox = new URL(sandboxUrl);
    return typeof origin === 'string' && main.origin === origin && main.protocol === 'http:'
      && main.hostname === '127.0.0.1' && Number(main.port) > 0
      && typeof sandboxUrl === 'string' && sandbox.href === sandboxUrl
      && sandbox.protocol === 'http:' && sandbox.hostname === '127.0.0.1'
      && Number(sandbox.port) > 0 && sandbox.origin !== origin
      && !sandbox.username && !sandbox.password && sandbox.pathname === '/sandbox'
      && !sandbox.search && !sandbox.hash;
  } catch { return false; }
}

/** The host declares a sandbox URL even when Apps are disabled. This profile
 * does not allow it: every sandbox request is a failed negative observation. */
export function appsRequestPolicy(request, { origin, sandboxUrl }, resourceType) {
  if (!validAppsOrigins(origin, sandboxUrl)) return 'BLOCKED_UNEXPECTED';
  try {
    if (request?.url === sandboxUrl) return 'BLOCKED_SANDBOX';
    return browserRequestPolicy(request, origin, resourceType);
  } catch { return 'BLOCKED_UNEXPECTED'; }
}

export function frameKind(url, { origin, sandboxUrl }) {
  if (url === sandboxUrl) return 'sandbox';
  if (url === `${origin}/`) return 'main';
  if (url === 'about:srcdoc') return 'app';
  if (url === 'about:blank' || url === '') return 'blank';
  return 'unexpected';
}

function exceptionLocation(value, s) {
  const coordinate = value => Number.isSafeInteger(value) && value >= 0 && value <= 10000000 ? value : null;
  const source = value?.url === `${s.origin}/assets/index-DZkZ6KYt.js` ? 'PINNED_HOST_BUNDLE'
    : value?.url === 'about:srcdoc' ? 'SRCDOC_APP'
      : value?.url === s.sandboxUrl ? 'SANDBOX_DOCUMENT' : 'UNKNOWN';
  return { source, line: coordinate(value?.lineNumber), column: coordinate(value?.columnNumber) };
}

function exceptionObservation(event, session, s) {
  const details = event?.exceptionDetails;
  const context = s.contexts.get(`${session}:${details?.executionContextId}`);
  const frames = Array.isArray(details?.stackTrace?.callFrames) ? details.stackTrace.callFrames : [];
  const kind = context && s.frames.get(context.frameId)?.kind;
  return { sequence: s.browserExceptions, stage: s.stage,
    sessionKind: session === s.sessionId ? 'MAIN' : 'CHILD',
    frameKind: ['main', 'sandbox', 'app', 'blank', 'unexpected'].includes(kind) ? kind : 'unknown',
    location: exceptionLocation(details, s),
    stack: frames.slice(0, MAX_EXCEPTION_STACK).map(frame => exceptionLocation(frame, s)),
    stackTruncated: frames.length > MAX_EXCEPTION_STACK,
    asyncParentPresent: details?.stackTrace?.parent !== undefined || details?.stackTrace?.parentId !== undefined };
}

async function forwardPaused(s, event, session, allowed) {
  // Intentional browser shutdown does not suppress observation of late traffic.
  if (s.closingBrowser) return;
  try {
    await s.cdp.send(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest', {
      requestId: event.requestId, ...(!allowed ? { errorReason: 'BlockedByClient' } : {}),
    }, session);
  } catch (error) {
    if (!s.closingBrowser || error?.message !== 'CDP_CLOSED') throw error;
  }
}

/** Install before navigation. Both same-process children and paused OOPIFs are
 * terminal failures, including transient blank children that later disappear.
 * All retained browser facts are counts, booleans or fixed structural enums. */
export async function beginBrowserObservation(cdp, { sessionId, origin, sandboxUrl }) {
  if (!identifier(sessionId) || !validAppsOrigins(origin, sandboxUrl)) fail('APPS_BROWSER_OPTIONS_INVALID');
  const s = { cdp, sessionId, origin, sandboxUrl, stage: 'BROWSER_SETUP', failureCode: undefined,
    sessions: new Set([sessionId]), contexts: new Map(), frames: new Map(), removers: [],
    requests: 0, sandboxRequests: 0, blockedFontRequests: 0, unexpectedRequests: 0,
    browserExceptions: 0, exceptionObservations: [], exceptionCountSaturated: false,
    childTargets: 0, childFrames: 0, sandboxFrames: 0, appFrames: 0,
    totalContexts: 0, totalFrames: 0, disabledChecks: 0, closed: false, closingBrowser: false };
  const recordFailure = code => { s.failureCode ??= code; };
  const on = (method, callback) => s.removers.push(cdp.on(method, callback));
  function rememberFrame(frame) {
    if (!identifier(frame?.id) || (frame.parentId !== undefined && !identifier(frame.parentId))) {
      recordFailure('APPS_BROWSER_FRAME_INVALID'); return;
    }
    const prior = s.frames.get(frame.id);
    if (!prior && ++s.totalFrames > MAX_FRAMES) { recordFailure('APPS_BROWSER_FRAME_BOUND'); return; }
    const parentId = frame.parentId ?? prior?.parentId;
    const kind = frameKind(frame.url, s);
    if (parentId !== undefined && prior?.parentId === undefined) s.childFrames += 1;
    if (kind === 'sandbox' && prior?.kind !== kind) s.sandboxFrames += 1;
    if (kind === 'app' && prior?.kind !== kind) s.appFrames += 1;
    if (parentId !== undefined || kind === 'sandbox' || kind === 'app') recordFailure('APPS_BROWSER_CHILD_FRAME_FORBIDDEN');
    if (kind === 'unexpected') recordFailure('APPS_BROWSER_NAVIGATION_UNEXPECTED');
    s.frames.set(frame.id, { kind, parentId });
  }
  function rememberTree(tree) {
    if (!tree) return;
    rememberFrame(tree.frame);
    for (const child of tree.childFrames ?? []) rememberTree(child);
  }
  async function configure(session) {
    await cdp.send('Page.enable', {}, session);
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] }, session);
    await cdp.send('Runtime.enable', {}, session);
    await cdp.send('Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: true,
      flatten: true, filter: [{ type: 'iframe', exclude: false }, { exclude: true }] }, session);
    rememberTree((await cdp.send('Page.getFrameTree', {}, session)).frameTree);
  }
  on('Target.attachedToTarget', async (event, parentSession) => {
    if (!s.sessions.has(parentSession)) return;
    if (!identifier(event.sessionId) || event.targetInfo?.type !== 'iframe'
        || ++s.childTargets > MAX_SESSIONS - 1 || s.sessions.has(event.sessionId)) {
      recordFailure('APPS_BROWSER_TARGET_INVALID'); return;
    }
    s.sessions.add(event.sessionId);
    recordFailure('APPS_BROWSER_CHILD_FRAME_FORBIDDEN');
    // Configure interception, but never resume an unexpected child document.
    try { await configure(event.sessionId); }
    catch { recordFailure('APPS_BROWSER_CHILD_SETUP_FAILED'); }
  });
  on('Target.detachedFromTarget', event => {
    s.sessions.delete(event.sessionId);
    for (const [key, context] of s.contexts)
      if (context.session === event.sessionId) s.contexts.delete(key);
  });
  on('Page.frameNavigated', (event, session) => { if (s.sessions.has(session)) rememberFrame(event.frame); });
  on('Page.frameAttached', (event, session) => {
    if (s.sessions.has(session)) rememberFrame({ id: event.frameId, parentId: event.parentFrameId, url: '' });
  });
  on('Page.frameDetached', (event, session) => {
    if (!s.sessions.has(session)) return;
    for (const [key, context] of s.contexts)
      if (context.frameId === event.frameId && context.session === session) s.contexts.delete(key);
    if (event.reason !== 'swap') s.frames.delete(event.frameId);
  });
  on('Runtime.executionContextCreated', (event, session) => {
    if (!s.sessions.has(session) || !event.context?.auxData?.isDefault) return;
    const { id, auxData } = event.context;
    if (!Number.isSafeInteger(id) || id < 1 || !identifier(auxData.frameId)
        || ++s.totalContexts > MAX_CONTEXTS) { recordFailure('APPS_BROWSER_CONTEXT_BOUND'); return; }
    s.contexts.set(`${session}:${id}`, { session, contextId: id, frameId: auxData.frameId });
  });
  on('Runtime.executionContextDestroyed', (event, session) => { s.contexts.delete(`${session}:${event.executionContextId}`); });
  on('Runtime.executionContextsCleared', (_, session) => {
    for (const [key, context] of s.contexts) if (context.session === session) s.contexts.delete(key);
  });
  on('Runtime.exceptionThrown', (event, session) => {
    if (!s.sessions.has(session)) return;
    if (s.browserExceptions < MAX_REQUESTS + 1) s.browserExceptions += 1;
    else s.exceptionCountSaturated = true;
    if (s.exceptionObservations.length < MAX_EXCEPTION_DETAILS)
      s.exceptionObservations.push(exceptionObservation(event, session, s));
    recordFailure('APPS_BROWSER_EXCEPTION');
  });
  on('Fetch.requestPaused', async (event, session) => {
    if (!s.sessions.has(session)) return;
    const policy = appsRequestPolicy(event.request, s, event.resourceType);
    s.requests += 1;
    if (policy === 'BLOCKED_PINNED_FONT') s.blockedFontRequests += 1;
    if (policy === 'BLOCKED_SANDBOX') s.sandboxRequests += 1;
    if (policy === 'BLOCKED_UNEXPECTED' || policy === 'BLOCKED_SANDBOX') s.unexpectedRequests += 1;
    if (s.requests > MAX_REQUESTS || s.unexpectedRequests) recordFailure('APPS_BROWSER_NETWORK_REJECTED');
    await forwardPaused(s, event, session, s.requests <= MAX_REQUESTS && policy === 'SAME_ORIGIN' && session === sessionId);
  });
  const observation = Object.freeze({
    facts: () => ({ stage: s.stage, browserRequestCount: s.requests,
      sandboxDocumentRequests: s.sandboxRequests, blockedPinnedFontRequests: s.blockedFontRequests,
      unexpectedBrowserRequests: s.unexpectedRequests, childTargets: s.childTargets,
      childFramesObserved: s.childFrames, sandboxFramesObserved: s.sandboxFrames, appFramesObserved: s.appFrames,
      defaultContextsObserved: s.totalContexts, framesObserved: s.totalFrames, appsDisabledChecks: s.disabledChecks,
      disabledPredicateFailure: s.disabledPredicateFailure ? { ...s.disabledPredicateFailure } : null,
      browserExceptionCount: s.browserExceptions, exceptionCountSaturated: s.exceptionCountSaturated,
      exceptionObservations: s.exceptionObservations.map(row => ({ ...row,
        location: { ...row.location }, stack: row.stack.map(frame => ({ ...frame })) })),
      exceptionObservationsTruncated: s.browserExceptions > MAX_EXCEPTION_DETAILS,
      noBrowserExceptions: s.browserExceptions === 0,
      noChildFramesObserved: s.childTargets === 0 && s.childFrames === 0 && s.sandboxFrames === 0 && s.appFrames === 0,
      pageNetworkPolicySatisfied: s.unexpectedRequests === 0 && s.requests <= MAX_REQUESTS
        && s.blockedFontRequests === 1 && s.sandboxRequests === 0 }),
    failure: () => s.failureCode,
    beginClosing: () => { s.closingBrowser = true; s.stage = 'BROWSER_CLOSE'; },
    close: () => { s.closed = true; for (const remove of s.removers) remove(); },
  });
  states.set(observation, s);
  await configure(sessionId);
  s.stage = 'NAVIGATE';
  return observation;
}

function state(cdp, sessionId, observation) {
  const s = states.get(observation);
  if (!s || s.cdp !== cdp || s.sessionId !== sessionId) fail('APPS_BROWSER_OPTIONS_INVALID');
  return s;
}

async function evaluate(s, expression) {
  if (s.failureCode) fail(s.failureCode);
  if (s.closed || s.cdp.failure()) fail('APPS_BROWSER_CLOSED');
  const result = await s.cdp.send('Runtime.evaluate', { expression, returnByValue: true,
    awaitPromise: false }, s.sessionId);
  if (result.exceptionDetails || result.result?.type !== 'boolean') fail('APPS_BROWSER_EVALUATION_FAILED');
  return result.result.value === true;
}

async function until(s, test, code) {
  const deadline = Date.now() + UI_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (s.failureCode) fail(s.failureCode);
    if (await test()) return;
    await pause();
  }
  fail(code);
}

export function clickExpression(selector, text) {
  return `(() => { const nodes = [...document.querySelectorAll(${JSON.stringify(selector)})]
    .filter(node => node.getClientRects().length && !node.disabled
      ${text === undefined ? '' : `&& node.textContent.trim() === ${JSON.stringify(text)}`});
    if (nodes.length !== 1) return false; nodes[0].click(); return true; })()`;
}

// Source-pinned selectors: Inspector's Apps tab is derived from tool UI metadata,
// not directly from the advertised-extension setting. The ordinary tool sidebar
// contains both the human title and machine name; its call button says Execute Tool.
export const appsDisabledExpression = `(() => {
  const disconnect = document.querySelector('[aria-label="Disconnect from server"]');
  const tools = document.querySelector('input[type="radio"][value="Tools"]');
  return !!disconnect && !disconnect.disabled && disconnect.getClientRects().length > 0
    && disconnect.closest('[data-anim]')?.getAttribute('data-anim') === 'in'
    && !!tools && !tools.disabled && tools.checked === true
    && tools.getClientRects().length > 0
    && document.querySelectorAll('input[type="radio"][value="Apps"], input[type="radio"][value="Skills"]').length === 0
    && document.querySelectorAll('iframe, [data-testid="apps-form"], [data-testid="open-app"]').length === 0
    && ![...document.querySelectorAll('button')].some(node => node.getClientRects().length
      && node.textContent.includes('refresh_catalog'));
})()`;

const disabledPredicateKeys = Object.freeze([
  'connectSwitchExists', 'connectSwitchChecked', 'toolsExists', 'toolsEnabled', 'toolsChecked', 'toolsVisible',
  'appsControlsAbsent', 'skillsControlsAbsent', 'iframesAbsent', 'appsFormAbsent', 'openAppAbsent', 'appOnlyHelperAbsent',
  'disconnectHeaderExists', 'disconnectHeaderEnabled', 'disconnectHeaderVisible', 'connectedHeaderState',
]);
const DISABLED_PREDICATE_MASK = 0xfffc;
const DISABLED_SNAPSHOT_MASK = 0xffff;

/** One atomic evaluation requires Tools and absence checks (bits 2–11), plus
 * the persistent connected header (bits 12–15). The Servers-pane switch in bits
 * 0–1 is diagnostic only: that pane unmounts after selecting Tools. The header
 * must exist, be enabled and visible, and retain its connected animation state;
 * an exiting stale header therefore cannot substitute for a live connection. */
export const disabledSnapshotExpression = `(() => {
  const connected = document.querySelector(${JSON.stringify('[aria-label=\'Connect or disconnect "soklet"\']')});
  const tools = document.querySelector('input[type="radio"][value="Tools"]');
  const disconnect = document.querySelector('[aria-label="Disconnect from server"]');
  const predicates = [
    !!connected, connected?.checked === true, !!tools, !!tools && !tools.disabled,
    tools?.checked === true, !!tools && tools.getClientRects().length > 0,
    document.querySelectorAll('input[type="radio"][value="Apps"]').length === 0,
    document.querySelectorAll('input[type="radio"][value="Skills"]').length === 0,
    document.querySelectorAll('iframe').length === 0,
    document.querySelectorAll('[data-testid="apps-form"]').length === 0,
    document.querySelectorAll('[data-testid="open-app"]').length === 0,
    ![...document.querySelectorAll('button')].some(node => node.getClientRects().length
      && node.textContent.includes('refresh_catalog')),
    !!disconnect, !!disconnect && !disconnect.disabled,
    !!disconnect && disconnect.getClientRects().length > 0,
    disconnect?.closest('[data-anim]')?.getAttribute('data-anim') === 'in',
  ];
  return predicates.reduce((mask, value, index) => mask | (value ? 1 << index : 0), 0);
})()`;

export function decodeDisabledSnapshot(mask) {
  if (!Number.isSafeInteger(mask) || mask < 0 || mask > DISABLED_SNAPSHOT_MASK)
    fail('APPS_BROWSER_EVALUATION_FAILED');
  return Object.fromEntries(disabledPredicateKeys.map((key, index) => [key, (mask & (1 << index)) !== 0]));
}

export async function assertAppsDisabled(cdp, sessionId, observation) {
  const s = state(cdp, sessionId, observation);
  if (s.failureCode) fail(s.failureCode);
  if (s.closed || s.cdp.failure()) fail('APPS_BROWSER_CLOSED');
  const result = await s.cdp.send('Runtime.evaluate', { expression: disabledSnapshotExpression,
    returnByValue: true, awaitPromise: false }, s.sessionId);
  if (result.exceptionDetails || result.result?.type !== 'number') fail('APPS_BROWSER_EVALUATION_FAILED');
  const projection = decodeDisabledSnapshot(result.result.value);
  if ((result.result.value & DISABLED_PREDICATE_MASK) !== DISABLED_PREDICATE_MASK) {
    s.disabledPredicateFailure ??= projection;
    fail('APPS_UI_DISABLED_ASSERTION_FAILED');
  }
  if (++s.disabledChecks > 256) fail('APPS_UI_OBSERVATION_BOUND');
  return true;
}

/** The only MCP invocation here comes from the pinned host's ordinary tool form.
 * Resource catalog access is not denied by disabling the Apps advertisement. */
export async function exerciseAppsDisabled(cdp, sessionId, observation) {
  const s = state(cdp, sessionId, observation);
  s.stage = 'CONNECT';
  await until(s, () => evaluate(s, clickExpression('[aria-label=\'Connect or disconnect "soklet"\']')), 'APPS_UI_CONNECT_TIMEOUT');
  s.stage = 'SELECT_TOOL';
  await until(s, () => evaluate(s, clickExpression('input[type="radio"][value="Tools"]')), 'APPS_UI_TAB_TIMEOUT');
  await assertAppsDisabled(cdp, sessionId, observation);
  await until(s, () => evaluate(s, `(() => {
    const nodes = [...document.querySelectorAll('[data-testid="tools-screen"] button')]
      .filter(node => node.getClientRects().length && !node.disabled
        && node.textContent.replace(/\\s+/g, '') === 'Showcatalogshow_catalog');
    if (nodes.length !== 1) return false; nodes[0].click(); return true;
  })()`), 'APPS_UI_SELECT_TIMEOUT');
  await assertAppsDisabled(cdp, sessionId, observation);
  s.stage = 'CALL_TOOL';
  await until(s, () => evaluate(s, clickExpression('button', 'Execute Tool')), 'APPS_UI_CALL_TIMEOUT');
  await until(s, () => evaluate(s, `(() => {
    const screen = document.querySelector('[data-testid="tools-screen"]');
    return screen?.getAttribute('data-call-status') === 'ok'
      && screen.getClientRects().length > 0 && screen.innerText.includes('Catalog alpha: 1 item.')
      && !document.body.innerText.includes('fixture-private-canary')
      && [...screen.querySelectorAll('[aria-label="Close results"]')].filter(node => node.getClientRects().length).length === 1;
  })()`), 'APPS_UI_RESULT_TIMEOUT');
  await assertAppsDisabled(cdp, sessionId, observation);
  s.stage = 'ORDINARY_TOOL_COMPLETE';
  return { connectedViaDom: true, toolsSelectedViaDom: true, ordinaryToolSelectedViaDom: true,
    ordinaryToolCalledViaDom: true, ordinaryResultRendered: true,
    appsControlsAbsent: true, skillsUiAbsent: true, appOnlyHelperAbsent: true, noAppFrames: true };
}

/** Call only after the independent server trace has settled. */
export async function disconnectApps(cdp, sessionId, observation) {
  const s = state(cdp, sessionId, observation);
  await assertAppsDisabled(cdp, sessionId, observation);
  s.stage = 'DISCONNECT';
  if (!await evaluate(s, clickExpression('[aria-label="Disconnect from server"]')))
    fail('APPS_UI_DISCONNECT_FAILED');
  const selector = '[aria-label=\'Connect or disconnect "soklet"\']';
  await until(s, () => evaluate(s, `document.querySelector(${JSON.stringify(selector)})?.checked === false`), 'APPS_UI_DISCONNECT_TIMEOUT');
  s.stage = 'DISCONNECTED';
  return { disconnected: true };
}
