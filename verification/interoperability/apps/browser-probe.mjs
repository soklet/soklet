import { browserRequestPolicy } from '../inspector/web-probe.mjs';

const states = new WeakMap();
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
const fail = code => { throw new Error(code); };
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const MAX_REQUESTS = 256;
const MAX_CONTEXTS = 32;
const MAX_FRAMES = 16;
const MAX_SESSIONS = 8;
const UI_TIMEOUT_MS = 15000;

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

/** This srcdoc fixture has no dedicated app origin and needs no network egress. */
export function appsRequestPolicy(request, { origin, sandboxUrl }, resourceType) {
  if (!validAppsOrigins(origin, sandboxUrl)) return 'BLOCKED_UNEXPECTED';
  try {
    if (request?.url === sandboxUrl && request.method === 'GET' && resourceType === 'Document')
      return 'SANDBOX_DOCUMENT';
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

/** Install before navigation. Child targets are paused until their own Fetch
 * interception and recursive auto-attach are installed. Same-process frames are
 * tracked by their default execution context; opaque-origin OOPIFs are tracked
 * in attached sessions. We never read console arguments, storage, tokens, raw
 * resource documents or personalized response payloads into the receipt. */
export async function beginBrowserObservation(cdp, { sessionId, origin, sandboxUrl }) {
  if (!identifier(sessionId) || !validAppsOrigins(origin, sandboxUrl)) fail('APPS_BROWSER_OPTIONS_INVALID');
  const s = { cdp, sessionId, origin, sandboxUrl, stage: 'BROWSER_SETUP', failureCode: undefined,
    sessions: new Set([sessionId]), contexts: new Map(), frames: new Map(), removers: [],
    requests: 0, sandboxRequests: 0, blockedFontRequests: 0, unexpectedRequests: 0,
    browserExceptions: 0, childTargets: 0, totalContexts: 0, totalFrames: 0, closed: false };
  const recordFailure = code => { s.failureCode ??= code; };
  const on = (method, callback) => s.removers.push(cdp.on(method, callback));
  function rememberFrame(frame) {
    if (!identifier(frame?.id) || (frame.parentId !== undefined && !identifier(frame.parentId))) {
      recordFailure('APPS_BROWSER_FRAME_INVALID'); return;
    }
    if (!s.frames.has(frame.id) && ++s.totalFrames > MAX_FRAMES) {
      recordFailure('APPS_BROWSER_FRAME_BOUND'); return;
    }
    const prior = s.frames.get(frame.id);
    const kind = frameKind(frame.url, s);
    if (kind === 'unexpected') recordFailure('APPS_BROWSER_NAVIGATION_UNEXPECTED');
    s.frames.set(frame.id, { kind, parentId: frame.parentId ?? prior?.parentId });
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
    try {
      await configure(event.sessionId);
      await cdp.send('Runtime.runIfWaitingForDebugger', {}, event.sessionId);
    } catch { recordFailure('APPS_BROWSER_CHILD_SETUP_FAILED'); }
  });
  on('Target.detachedFromTarget', event => {
    s.sessions.delete(event.sessionId);
    for (const [key, context] of s.contexts)
      if (context.session === event.sessionId) s.contexts.delete(key);
  });
  on('Page.frameNavigated', (event, session) => {
    if (s.sessions.has(session)) rememberFrame(event.frame);
  });
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
  on('Runtime.executionContextDestroyed', (event, session) => {
    s.contexts.delete(`${session}:${event.executionContextId}`);
  });
  on('Runtime.executionContextsCleared', (_, session) => {
    for (const [key, context] of s.contexts)
      if (context.session === session) s.contexts.delete(key);
  });
  on('Runtime.exceptionThrown', (_, session) => {
    if (s.sessions.has(session)) { s.browserExceptions += 1; recordFailure('APPS_BROWSER_EXCEPTION'); }
  });
  on('Fetch.requestPaused', async (event, session) => {
    if (!s.sessions.has(session)) return;
    const policy = appsRequestPolicy(event.request, s, event.resourceType);
    s.requests += 1;
    if (policy === 'BLOCKED_PINNED_FONT') s.blockedFontRequests += 1;
    if (policy === 'SANDBOX_DOCUMENT') s.sandboxRequests += 1;
    if (policy === 'BLOCKED_UNEXPECTED') s.unexpectedRequests += 1;
    if (s.requests > MAX_REQUESTS || policy === 'BLOCKED_UNEXPECTED') recordFailure('APPS_BROWSER_NETWORK_REJECTED');
    const allowed = s.requests <= MAX_REQUESTS && ['SAME_ORIGIN', 'SANDBOX_DOCUMENT'].includes(policy);
    await cdp.send(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest', {
      requestId: event.requestId, ...(!allowed ? { errorReason: 'BlockedByClient' } : {}),
    }, session);
  });
  const observation = Object.freeze({
    facts: () => ({ stage: s.stage, browserRequestCount: s.requests,
      sandboxDocumentRequests: s.sandboxRequests, blockedPinnedFontRequests: s.blockedFontRequests,
      unexpectedBrowserRequests: s.unexpectedRequests, childTargets: s.childTargets,
      defaultContextsObserved: s.totalContexts, framesObserved: s.totalFrames,
      noBrowserExceptions: s.browserExceptions === 0,
      pageNetworkPolicySatisfied: s.unexpectedRequests === 0 && s.requests <= MAX_REQUESTS
        && s.blockedFontRequests === 1 && s.sandboxRequests === 1 }),
    failure: () => s.failureCode,
    close: () => { s.closed = true; for (const remove of s.removers) remove(); },
  });
  states.set(observation, s);
  await configure(sessionId);
  s.stage = 'NAVIGATE';
  return observation;
}

async function evaluate(s, expression, context) {
  if (s.failureCode) fail(s.failureCode);
  if (s.closed || s.cdp.failure()) fail('APPS_BROWSER_CLOSED');
  const result = await s.cdp.send('Runtime.evaluate', { expression, returnByValue: true,
    awaitPromise: false, ...(context ? { contextId: context.contextId } : {}) }, context?.session ?? s.sessionId);
  if (result.exceptionDetails || result.result?.type !== 'boolean') fail('APPS_BROWSER_EVALUATION_FAILED');
  return result.result.value === true;
}

async function until(s, test, code) {
  const deadline = Date.now() + UI_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (s.failureCode) fail(s.failureCode);
    const result = await test();
    if (result) return result;
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

const readyExpression = `(() => {
  const byId = id => document.getElementById('catalog-' + id);
  const root = byId('root'), view = byId('view'), refresh = byId('refresh');
  return location.href === 'about:srcdoc' && !!root && root.dataset.state === 'ready'
    && root.getAttribute('aria-busy') === 'false' && !!view && !view.hidden
    && view.getClientRects().length > 0 && !!refresh && !refresh.disabled
    && document.documentElement.lang === 'en-US' && document.documentElement.dir === 'ltr'
    && byId('tenant')?.textContent === 'alpha' && byId('title')?.textContent === 'Catalog view'
    && byId('summary')?.textContent === 'Catalog alpha: 1 item.'
    && byId('item')?.textContent === 'Toy <img src=x onerror=alert(1)>'
    && byId('item').children.length === 0 && document.querySelectorAll('img').length === 0
    && byId('amount')?.textContent === '$1,234.50'
    && byId('updated')?.getAttribute('datetime') === '2026-09-19T12:00:00Z'
    && byId('updated')?.textContent === new Intl.DateTimeFormat('en-US', {
      dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC'
    }).format(new Date('2026-09-19T12:00:00Z'))
    && refresh.textContent === 'Refresh catalog'
    && !document.body.innerText.includes('fixture-private-canary');
})()`;

/** Pinned Inspector's Apps sidebar uses the tool title and automatically opens
 * input-free Apps when selected. Only visible DOM controls invoke host actions.
 * The App's own button invokes its bundled SDK bridge. No MCP call is synthesized. */
export async function exerciseApps(cdp, sessionId, observation) {
  const s = states.get(observation);
  if (!s || s.cdp !== cdp || s.sessionId !== sessionId) fail('APPS_BROWSER_OPTIONS_INVALID');
  const facts = {};
  s.stage = 'CONNECT';
  await until(s, () => evaluate(s, clickExpression('[aria-label=\'Connect or disconnect "soklet"\']')), 'APPS_UI_CONNECT_TIMEOUT');
  s.stage = 'SELECT_APP';
  await until(s, () => evaluate(s, clickExpression('input[type="radio"][value="Apps"]')), 'APPS_UI_TAB_TIMEOUT');
  await until(s, () => evaluate(s, clickExpression('button', 'Show catalog')), 'APPS_UI_SELECT_TIMEOUT');
  facts.selectedAppViaDom = true;
  s.stage = 'RENDER';
  await until(s, async () => {
    if (await evaluate(s, 'document.querySelector(\'[data-testid="apps-form"]\')?.getAttribute("data-app-status") === "ready"')) return true;
    // Input schemas that the pinned host treats as requiring input show this
    // button. A successful click removes it, so it cannot create duplicate calls.
    await evaluate(s, clickExpression('[data-testid="open-app"]'));
    return false;
  }, 'APPS_UI_INITIALIZE_TIMEOUT');
  facts.hostAppReady = true;
  const context = await until(s, async () => {
    const candidates = [...s.contexts.values()].filter(context => {
      const frame = s.frames.get(context.frameId);
      return frame?.kind === 'app' && s.frames.get(frame.parentId)?.kind === 'sandbox';
    });
    if (candidates.length > 1) fail('APPS_BROWSER_APP_CONTEXT_AMBIGUOUS');
    if (candidates.length === 1 && await evaluate(s, readyExpression, candidates[0])) return candidates[0];
    return undefined;
  }, 'APPS_UI_RENDER_TIMEOUT');
  facts.catalogRendered = true;
  facts.textOnlyHostileLabel = true;
  facts.serverSelectedLocaleRendered = true;
  facts.currencyAndUtcDateRendered = true;
  const parentFrameId = s.frames.get(context.frameId).parentId;
  const sandboxContexts = [...s.contexts.values()].filter(item => item.frameId === parentFrameId);
  if (sandboxContexts.length !== 1 || !await evaluate(s, `(() => {
    const frames = [...document.querySelectorAll('iframe')];
    return location.href === ${JSON.stringify(s.sandboxUrl)} && frames.length === 1
      && frames[0].hasAttribute('srcdoc') && !frames[0].hasAttribute('src')
      && frames[0].getAttribute('sandbox') === 'allow-scripts allow-forms'
      && !frames[0].hasAttribute('allow');
  })()`, sandboxContexts[0])) fail('APPS_BROWSER_EMBEDDING_MISMATCH');
  facts.opaqueSrcdocSandboxObserved = true;
  s.stage = 'REFRESH';
  const pending = await evaluate(s, `(() => {
    const root = document.getElementById('catalog-root');
    const refresh = document.getElementById('catalog-refresh');
    if (!refresh || refresh.disabled || !refresh.getClientRects().length) return false;
    refresh.click();
    return root.dataset.state === 'pending' && root.getAttribute('aria-busy') === 'true'
      && refresh.disabled && document.getElementById('catalog-view').hidden
      && document.getElementById('catalog-summary').textContent === '';
  })()`, context);
  if (!pending) fail('APPS_UI_REFRESH_NOT_PENDING');
  facts.refreshClickedViaDom = true;
  facts.pendingClearedPriorData = true;
  await until(s, () => evaluate(s, readyExpression, context), 'APPS_UI_REFRESH_TIMEOUT');
  facts.refreshRendered = true;
  s.stage = 'REFRESH_COMPLETE';
  return facts;
}

/** Call only after the independent server trace has settled. */
export async function disconnectApps(cdp, sessionId, observation) {
  const s = states.get(observation);
  if (!s || s.cdp !== cdp || s.sessionId !== sessionId) fail('APPS_BROWSER_OPTIONS_INVALID');
  s.stage = 'DISCONNECT';
  if (!await evaluate(s, clickExpression('[aria-label="Disconnect from server"]')))
    fail('APPS_UI_DISCONNECT_FAILED');
  const switchSelector = '[aria-label=\'Connect or disconnect "soklet"\']';
  await until(s, () => evaluate(s, `document.querySelector(${JSON.stringify(switchSelector)})?.checked === false`), 'APPS_UI_DISCONNECT_TIMEOUT');
  s.stage = 'DISCONNECTED';
  return { disconnected: true };
}
