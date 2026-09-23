import { browserRequestPolicy } from '../inspector/web-probe.mjs';

const states = new WeakMap();
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
const fail = code => { throw new Error(code); };
const identifier = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(value);
const MAX_REQUESTS = 256;
const MAX_CONTEXTS = 32;
const MAX_FRAMES = 16;
const MAX_SESSIONS = 8;
const MAX_EXCEPTION_DETAILS = 8;
const MAX_EXCEPTION_STACK = 8;
const UI_TIMEOUT_MS = 15000;
export const EXPECTED_CSP = "default-src 'none'; connect-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; media-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'; object-src 'none'; worker-src 'none'";
export function expectedAllowlistCsp(origin) {
  if (typeof origin !== 'string' || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(origin)
      || Number(origin.split(':').at(-1)) > 65535) fail('APPS_CSP_OPTIONS_INVALID');
  return `default-src 'none'; connect-src ${origin}; script-src 'unsafe-inline' ${origin}; style-src 'unsafe-inline' ${origin}; img-src ${origin}; font-src ${origin}; media-src ${origin}; frame-src 'none'; base-uri 'self'; form-action 'none'; object-src 'none'; worker-src 'none'`;
}
export const CSP_FAILURES = Object.freeze(['APPS_CSP_OPTIONS_INVALID', 'APPS_CSP_CONTEXT_MISMATCH',
  'APPS_CSP_POLICY_MISMATCH', 'APPS_CSP_PHASE_FAILED', 'APPS_CSP_NETWORK_REJECTED',
  'APPS_CSP_EVALUATION_FAILED', 'APPS_CSP_ROW_REJECTED', 'APPS_CSP_EVIDENCE_REJECTED']);
const PHASES = Object.freeze(['CONTROL_BEFORE', 'APP', 'CONTROL_AFTER']);
const OPERATIONS = Object.freeze(['connect', 'image']);
const CSP_FLAGS = Object.freeze(['policyVerified', 'appContextVerified', 'sandboxVerified',
  'controlsBeforePassed', 'appDenialsPassed', 'controlsAfterPassed', 'settled']);
const ALLOWLIST_FLAGS = Object.freeze(['policyVerified', 'appContextVerified', 'sandboxVerified',
  'controlsBeforePassed', 'appChecksPassed', 'controlsAfterPassed', 'settled']);
const ROW_BOOLEANS = Object.freeze(['attempted', 'completed', 'timedOut', 'contextMatches',
  'succeeded', 'failed', 'responseMatches', 'violationBoundExceeded', 'settled',
  'trusted', 'enforced', 'directiveMatches', 'targetMatches', 'documentMatches', 'policyMatches']);
const ROW_COUNTS = Object.freeze(['violationCount', 'matchingViolationCount', 'unexpectedViolationCount']);
const ROW_KEYS = Object.freeze(['phase', 'operation', 'context', ...ROW_BOOLEANS, ...ROW_COUNTS]);
const CANARY_HEADERS = Object.freeze(['accept', 'accept-encoding', 'accept-language', 'cache-control',
  'pragma', 'origin', 'user-agent', 'sec-fetch-dest', 'sec-fetch-mode', 'sec-fetch-site',
  'sec-ch-ua', 'sec-ch-ua-mobile', 'sec-ch-ua-platform', 'priority']);
const DIAGNOSTIC_HEADERS = Object.freeze([...CANARY_HEADERS, 'authorization', 'cookie',
  'proxy-authorization', 'referer']);
const RESOURCE_TYPES = Object.freeze(['Document', 'Stylesheet', 'Image', 'Media', 'Font', 'Script',
  'TextTrack', 'XHR', 'Fetch', 'Prefetch', 'EventSource', 'WebSocket', 'Manifest',
  'SignedExchange', 'Ping', 'CSPViolationReport', 'Preflight', 'Other']);
const DOCUMENT_KINDS = Object.freeze(['NONE', 'SRCDOC', 'ABOUT_SCHEME', 'ABOUT_BLANK', 'EMPTY',
  'NULL', 'UNDEFINED', 'SANDBOX_DOCUMENT', 'MAIN_DOCUMENT', 'SANDBOX_ORIGIN', 'MAIN_ORIGIN',
  'CANARY_ORIGIN', 'UNKNOWN']);
const ownKeysExactly = (value, keys) => !!value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));
const canaryTargetUrl = (origin, phase, operation) => `${origin}/${operation}`
  + (phase === 'CONTROL_AFTER' && operation === 'image' ? '?control=after' : '');
function canaryOperation(url, origin) {
  if (url === `${origin}/connect`) return 'connect';
  if (url === `${origin}/image` || url === `${origin}/image?control=after`) return 'image';
  return 'UNKNOWN';
}

export function validCanaryOrigin(canaryOrigin, { origin, sandboxUrl }) {
  try {
    const canary = new URL(canaryOrigin);
    return validAppsOrigins(origin, sandboxUrl) && typeof canaryOrigin === 'string'
      && canary.origin === canaryOrigin && canary.protocol === 'http:'
      && canary.hostname === '127.0.0.1' && Number(canary.port) > 0
      && canaryOrigin !== origin && canaryOrigin !== new URL(sandboxUrl).origin;
  } catch { return false; }
}

function safeCanaryHeaders(request, operation) {
  if (request.postData !== undefined || request.postDataEntries !== undefined || request.hasPostData === true) return false;
  const headers = request.headers;
  if (!headers || typeof headers !== 'object' || Array.isArray(headers)) return false;
  const allowed = new Set(CANARY_HEADERS);
  const seen = new Set();
  for (const [name, value] of Object.entries(headers)) {
    const key = name.toLowerCase();
    if (!allowed.has(key) || seen.has(key) || typeof value !== 'string' || value.length > 2048
        || /[\r\n]/.test(value)) return false;
    seen.add(key);
    if (key === 'sec-fetch-mode' && value !== 'cors') return false;
    if (key === 'sec-fetch-dest' && value !== (operation === 'connect' ? 'empty' : 'image')) return false;
  }
  return true;
}

// Diagnostics do not participate in admission. Capture only the first rejected
// canary request, with fixed keys/enums; never copy a header name/value or URL.
function canaryAdmissionRejection(event, session, s, initialPolicy) {
  const request = event.request;
  const operation = canaryOperation(request.url, s.canaryOrigin);
  const phaseIndex = PHASES.indexOf(s.cspPhase), operationIndex = OPERATIONS.indexOf(operation);
  const frame = s.frames.get(event.frameId), app = s.appContext;
  const headersObject = !!request.headers && typeof request.headers === 'object' && !Array.isArray(request.headers);
  const entries = headersObject ? Object.entries(request.headers) : [];
  const knownHeadersPresent = Object.fromEntries(DIAGNOSTIC_HEADERS.map(name => [name, false]));
  const seen = new Set();
  let unknownHeaderPresent = false, duplicateHeaderPresent = false, headerValuesValid = true;
  let fetchModeMatches = true, fetchDestinationMatches = true, requestOrigin;
  for (const [name, value] of entries) {
    const key = name.toLowerCase();
    if (Object.hasOwn(knownHeadersPresent, key)) knownHeadersPresent[key] = true;
    if (!CANARY_HEADERS.includes(key)) unknownHeaderPresent = true;
    if (seen.has(key)) duplicateHeaderPresent = true;
    seen.add(key);
    if (typeof value !== 'string' || value.length > 2048 || /[\r\n]/.test(value)) headerValuesValid = false;
    if (key === 'sec-fetch-mode' && value !== 'cors') fetchModeMatches = false;
    if (key === 'sec-fetch-dest' && value !== (operation === 'connect' ? 'empty' : 'image')) fetchDestinationMatches = false;
    if (key === 'origin' && requestOrigin === undefined) requestOrigin = value;
  }
  const mainPhase = phaseIndex === 0 || phaseIndex === 2, appPhase = phaseIndex === 1;
  const mainSessionMatches = session === s.sessionId, appSessionMatches = !!app && session === app.session;
  const mainFrameMatches = event.frameId === s.cspMainContext?.frameId && frame?.kind === 'main';
  const appFrameMatches = !!app && event.frameId === app.frameId && frame?.kind === 'app'
    && s.frames.get(frame.parentId)?.kind === 'sandbox';
  const framePhaseMatches = (mainPhase && mainSessionMatches && mainFrameMatches)
    || (appPhase && appSessionMatches && appFrameMatches);
  const expectedOriginMatches = requestOrigin === (appPhase && appSessionMatches && appFrameMatches ? 'null' : s.origin);
  const facts = { initialPolicy: ['CANARY_CONNECT', 'CANARY_IMAGE'].includes(initialPolicy) ? initialPolicy : 'BLOCKED_UNEXPECTED',
    phase: ['IDLE', ...PHASES].includes(s.cspPhase) ? s.cspPhase : 'UNKNOWN', operation,
    frameKind: ['main', 'sandbox', 'app', 'blank', 'unexpected'].includes(frame?.kind) ? frame.kind : 'unknown',
    resourceType: RESOURCE_TYPES.includes(event.resourceType) ? event.resourceType : 'UNKNOWN',
    exactUrl: operation !== 'UNKNOWN', methodAllowed: request.method === 'GET',
    urlMatchesPhase: PHASES.includes(s.cspPhase) && request.url === canaryTargetUrl(s.canaryOrigin, s.cspPhase, operation),
    resourceTypeAllowed: event.resourceType === (operation === 'connect' ? 'XHR' : operation === 'image' ? 'Image' : null),
    bodyAbsent: request.postData === undefined && request.postDataEntries === undefined && request.hasPostData !== true,
    headersObject, unknownHeaderPresent, duplicateHeaderPresent, headerValuesValid, fetchModeMatches, fetchDestinationMatches,
    knownHeadersPresent, mainPhase, appPhase, mainSessionMatches, appSessionMatches,
    mainFrameMatches, appFrameMatches, framePhaseMatches,
    activeOperationMatches: s.activeOperation === operation,
    requestOriginKind: requestOrigin === undefined ? 'ABSENT' : requestOrigin === s.origin ? 'MAIN'
      : requestOrigin === 'null' ? 'APP_OPAQUE' : 'OTHER', expectedOriginMatches,
    firstForOperation: phaseIndex >= 0 && operationIndex >= 0 && s.canaryContinued[phaseIndex * 2 + operationIndex] === 0,
    requestBoundSatisfied: s.requests < MAX_REQUESTS, browserOpen: !s.closingBrowser };
  const reasons = [
    ['exactUrl', 'URL_MISMATCH'], ['methodAllowed', 'METHOD_MISMATCH'],
    ['resourceTypeAllowed', 'RESOURCE_TYPE_MISMATCH'], ['bodyAbsent', 'BODY_PRESENT'],
    ['headersObject', 'HEADERS_NOT_OBJECT'],
  ];
  facts.reason = reasons.find(([key]) => !facts[key])?.[1]
    ?? (unknownHeaderPresent ? 'UNKNOWN_HEADER' : duplicateHeaderPresent ? 'DUPLICATE_HEADER'
      : !headerValuesValid ? 'HEADER_VALUE_INVALID' : !fetchModeMatches ? 'FETCH_MODE_MISMATCH'
      : !fetchDestinationMatches ? 'FETCH_DESTINATION_MISMATCH' : !facts.urlMatchesPhase ? 'URL_PHASE_MISMATCH'
      : !facts.activeOperationMatches ? 'OPERATION_MISMATCH'
      : !framePhaseMatches ? 'FRAME_PHASE_MISMATCH' : !expectedOriginMatches ? 'ORIGIN_MISMATCH'
      : !facts.firstForOperation ? 'DUPLICATE_OPERATION' : !facts.requestBoundSatisfied ? 'REQUEST_BOUND'
      : !facts.browserOpen ? 'BROWSER_CLOSING' : 'OTHER_POLICY');
  return facts;
}

/** Project only fixed structural values; never retain evaluation diagnostics. */
export function projectCspRow(value) {
  try {
    if (!ownKeysExactly(value, ROW_KEYS) || !PHASES.includes(value.phase)
        || !OPERATIONS.includes(value.operation) || !['MAIN', 'APP'].includes(value.context)
        || !ROW_BOOLEANS.every(key => typeof value[key] === 'boolean')
        || !ROW_COUNTS.every(key => Number.isSafeInteger(value[key]) && value[key] >= 0 && value[key] <= 9))
      return null;
    return Object.fromEntries(ROW_KEYS.map(key => [key, value[key]]));
  } catch { return null; }
}

export function projectCspOperationResult(value) {
  try {
    if (!ownKeysExactly(value, ['row', 'diagnostics'])
        || !ownKeysExactly(value.diagnostics, ['documentKind'])
        || !DOCUMENT_KINDS.includes(value.diagnostics.documentKind)) return null;
    const row = projectCspRow(value.row);
    return row ? { row, diagnostics: { documentKind: value.diagnostics.documentKind } } : null;
  } catch { return null; }
}

function validCspRow(row, index) {
  if (!projectCspRow(row) || index < 0 || index > 5) return false;
  const negative = index === 2 || index === 3;
  return row.phase === PHASES[Math.floor(index / 2)] && row.operation === OPERATIONS[index % 2]
    && row.context === (negative ? 'APP' : 'MAIN') && row.attempted && row.completed
    && !row.timedOut && row.contextMatches && row.settled && !row.violationBoundExceeded
    && row.succeeded === !negative && row.failed === negative && row.responseMatches === !negative
    && row.violationCount === (negative ? 1 : 0) && row.matchingViolationCount === (negative ? 1 : 0)
    && row.unexpectedViolationCount === 0 && row.trusted && row.enforced && row.directiveMatches
    && row.targetMatches && row.documentMatches && row.policyMatches;
}

export function adjudicateCspEvidence(evidence) {
  try {
    const valid = ownKeysExactly(evidence, ['status', 'rows', ...CSP_FLAGS])
      && evidence.status === 'PASSED' && CSP_FLAGS.every(key => evidence[key] === true)
      && Array.isArray(evidence.rows) && evidence.rows.length === 6
      && Array.from({ length: 6 }, (_, index) => index).every(index => validCspRow(evidence.rows[index], index));
    return valid ? 'PASSED' : 'FAILED';
  } catch { return 'FAILED'; }
}

export function adjudicateAllowlistEvidence(evidence) {
  try {
    const expected = [
      ['CONTROL_BEFORE', 'connect', 'DECLARED', false], ['CONTROL_BEFORE', 'image', 'DECLARED', false],
      ['APP', 'connect', 'DECLARED', false], ['APP', 'image', 'DECLARED', false],
      ['APP', 'connect', 'UNDECLARED', true], ['APP', 'image', 'UNDECLARED', true],
      ['CONTROL_AFTER', 'connect', 'DECLARED', false], ['CONTROL_AFTER', 'image', 'DECLARED', false],
    ];
    if (!ownKeysExactly(evidence, ['status', 'rows', ...ALLOWLIST_FLAGS]) || evidence.status !== 'PASSED'
        || !ALLOWLIST_FLAGS.every(key => evidence[key] === true)
        || !Array.isArray(evidence.rows) || evidence.rows.length !== expected.length) return 'FAILED';
    return expected.every(([phase, operation, targetKind, negative], index) => {
      const row = evidence.rows[index];
      const {targetKind: observedKind, ...base} = row ?? {};
      return observedKind === targetKind && projectCspRow(base) !== null
        && row.phase === phase && row.operation === operation
        && row.context === (phase === 'APP' ? 'APP' : 'MAIN')
        && row.attempted && row.completed && !row.timedOut && row.contextMatches
        && row.settled && !row.violationBoundExceeded && row.succeeded === !negative
        && row.failed === negative && row.responseMatches === !negative
        && row.violationCount === Number(negative) && row.matchingViolationCount === Number(negative)
        && row.unexpectedViolationCount === 0 && row.trusted && row.enforced
        && row.directiveMatches && row.targetMatches && row.documentMatches && row.policyMatches;
    }) ? 'PASSED' : 'FAILED';
  } catch { return 'FAILED'; }
}

// This is the exact asset path in the unchanged pinned browser distribution.
// Only an enum and bounded numeric source coordinates leave this projection.
function exceptionSource(url, s) {
  if (url === `${s.origin}/assets/index-DZkZ6KYt.js`) return 'PINNED_HOST_BUNDLE';
  if (url === 'about:srcdoc') return 'SRCDOC_APP';
  if (url === s.sandboxUrl) return 'SANDBOX_DOCUMENT';
  return 'UNKNOWN';
}

function exceptionLocation(value, s) {
  const coordinate = value => Number.isSafeInteger(value) && value >= 0 && value <= 10000000 ? value : null;
  return { source: exceptionSource(value?.url, s),
    line: coordinate(value?.lineNumber), column: coordinate(value?.columnNumber) };
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
  // Late events remain observed/classified, but cannot race a new command
  // against the explicit browser shutdown. An already-pending command may
  // consume only the exact expected connection-closed rejection.
  if (s.closingBrowser) return;
  try {
    await s.cdp.send(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest', {
      requestId: event.requestId, ...(!allowed ? { errorReason: 'BlockedByClient' } : {}),
    }, session);
  } catch (error) {
    if (!s.closingBrowser || error?.message !== 'CDP_CLOSED') throw error;
  }
}

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

/** The only additional network allowance is three exact canary URLs: connect,
 * image, and the fixed final-image control alias. BEFORE and APP share image.
 * The event handler separately binds it to an active operation and real frame. */
export function appsRequestPolicy(request, { origin, sandboxUrl, canaryOrigin }, resourceType) {
  if (!validAppsOrigins(origin, sandboxUrl)) return 'BLOCKED_UNEXPECTED';
  try {
    if (request?.url === sandboxUrl && request.method === 'GET' && resourceType === 'Document')
      return 'SANDBOX_DOCUMENT';
    if (validCanaryOrigin(canaryOrigin, { origin, sandboxUrl })) {
      for (const operation of OPERATIONS) {
        // Pinned Chrome 153 reports this JS fetch as XHR in Fetch.requestPaused:
        // preserved apps-csp-host attempt3 receipt. Keep the observed type exact.
        if (canaryOperation(request?.url, canaryOrigin) === operation && request.method === 'GET'
            && resourceType === (operation === 'connect' ? 'XHR' : 'Image')
            && safeCanaryHeaders(request, operation)) return `CANARY_${operation.toUpperCase()}`;
      }
    }
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
export async function beginBrowserObservation(cdp, { sessionId, origin, sandboxUrl, canaryOrigin, allowlist = false }) {
  if (!identifier(sessionId) || !validCanaryOrigin(canaryOrigin, { origin, sandboxUrl })
      || typeof allowlist !== 'boolean') fail('APPS_BROWSER_OPTIONS_INVALID');
  const s = { cdp, sessionId, origin, sandboxUrl, canaryOrigin, allowlist, stage: 'BROWSER_SETUP', failureCode: undefined,
    sessions: new Set([sessionId]), contexts: new Map(), frames: new Map(), removers: [],
    requests: 0, sandboxRequests: 0, blockedFontRequests: 0, unexpectedRequests: 0,
    browserExceptions: 0, exceptionObservations: [], exceptionCountSaturated: false,
    childTargets: 0, totalContexts: 0, totalFrames: 0, closed: false, closingBrowser: false,
    cspPhase: 'IDLE', activeOperation: undefined, appContext: undefined, cspMainContext: undefined, cspEvidence: undefined,
    canaryContinued: [0, 0, 0, 0, 0, 0], canaryBlocked: 0, canaryAdmissionRejection: null,
    cspViolationDiagnostics: [], cacheDisabledSessions: 0 };
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
    // Attempts6/7 reused the decoded image despite no-store/cache disabling.
    // Enable renderer network instrumentation, then disable cache before any
    // navigation/child unpause. No Network events or response bodies are read.
    // The final image also uses its own fixed control URL; four live server hits
    // remain mandatory. No CSP bypass or response rewrite is used.
    try {
      await cdp.send('Network.enable', { maxTotalBufferSize: 0, maxResourceBufferSize: 0, maxPostDataSize: 0 }, session);
      await cdp.send('Network.setCacheDisabled', { cacheDisabled: true }, session);
      s.cacheDisabledSessions += 1;
    } catch {
      recordFailure('APPS_BROWSER_CACHE_SETUP_FAILED');
      fail('APPS_BROWSER_CACHE_SETUP_FAILED');
    }
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
    let policy = appsRequestPolicy(event.request, s, event.resourceType);
    const initialPolicy = policy;
    let canaryTarget = false;
    try { canaryTarget = new URL(event.request?.url).origin === s.canaryOrigin; } catch { /* no URL is retained */ }
    if (policy.startsWith('CANARY_')) {
      const operation = policy === 'CANARY_CONNECT' ? 'connect' : 'image';
      const phaseIndex = PHASES.indexOf(s.cspPhase), operationIndex = OPERATIONS.indexOf(operation);
      const index = phaseIndex * 2 + operationIndex;
      const frame = s.frames.get(event.frameId), app = s.appContext;
      const appMatches = phaseIndex === 1 && app && session === app.session && event.frameId === app.frameId
        && frame?.kind === 'app' && s.frames.get(frame.parentId)?.kind === 'sandbox';
      const mainMatches = (phaseIndex === 0 || phaseIndex === 2) && session === s.sessionId
        && event.frameId === s.cspMainContext?.frameId && frame?.kind === 'main';
      const requestOrigin = Object.entries(event.request.headers).find(([name]) => name.toLowerCase() === 'origin')?.[1];
      if (s.activeOperation === operation && (appMatches || mainMatches)
          && event.request.url === canaryTargetUrl(s.canaryOrigin, s.cspPhase, operation)
          && requestOrigin === (appMatches ? 'null' : s.origin) && s.canaryContinued[index] === 0
          && s.requests < MAX_REQUESTS && !s.closingBrowser) s.canaryContinued[index] += 1;
      else policy = 'BLOCKED_UNEXPECTED';
    }
    if (canaryTarget && policy === 'BLOCKED_UNEXPECTED') {
      s.canaryAdmissionRejection ??= canaryAdmissionRejection(event, session, s, initialPolicy);
      s.canaryBlocked = Math.min(s.canaryBlocked + 1, MAX_REQUESTS + 1);
      recordFailure('APPS_CSP_NETWORK_REJECTED');
    }
    s.requests += 1;
    if (policy === 'BLOCKED_PINNED_FONT') s.blockedFontRequests += 1;
    if (policy === 'SANDBOX_DOCUMENT') s.sandboxRequests += 1;
    if (policy === 'BLOCKED_UNEXPECTED') s.unexpectedRequests += 1;
    if (s.requests > MAX_REQUESTS || policy === 'BLOCKED_UNEXPECTED') recordFailure('APPS_BROWSER_NETWORK_REJECTED');
    const allowed = s.requests <= MAX_REQUESTS
      && ['SAME_ORIGIN', 'SANDBOX_DOCUMENT', 'CANARY_CONNECT', 'CANARY_IMAGE'].includes(policy);
    await forwardPaused(s, event, session, allowed);
  });
  const observation = Object.freeze({
    facts: () => ({ stage: s.stage, browserRequestCount: s.requests,
      sandboxDocumentRequests: s.sandboxRequests, blockedPinnedFontRequests: s.blockedFontRequests,
      unexpectedBrowserRequests: s.unexpectedRequests, childTargets: s.childTargets,
      defaultContextsObserved: s.totalContexts, framesObserved: s.totalFrames,
      cacheDisabledSessions: s.cacheDisabledSessions,
      allObservedSessionsCacheDisabled: s.cacheDisabledSessions === s.childTargets + 1,
      browserExceptionCount: s.browserExceptions, exceptionCountSaturated: s.exceptionCountSaturated,
      exceptionObservations: s.exceptionObservations.map(row => ({ ...row,
        location: { ...row.location }, stack: row.stack.map(frame => ({ ...frame })) })),
      exceptionObservationsTruncated: s.browserExceptions > MAX_EXCEPTION_DETAILS,
      noBrowserExceptions: s.browserExceptions === 0,
      cspEvidence: s.cspEvidence ? { ...s.cspEvidence, rows: s.cspEvidence.rows.map(row => ({ ...row })) } : null,
      cspViolationDiagnostics: s.cspViolationDiagnostics.map(row => ({ ...row })),
      canaryNetwork: { phase: s.cspPhase, continuedByOperation: [...s.canaryContinued],
        mainContinued: s.canaryContinued[0] + s.canaryContinued[1] + s.canaryContinued[4] + s.canaryContinued[5],
        appContinued: s.canaryContinued[2] + s.canaryContinued[3], blocked: s.canaryBlocked },
      canaryAdmissionRejection: s.canaryAdmissionRejection ? { ...s.canaryAdmissionRejection,
        knownHeadersPresent: { ...s.canaryAdmissionRejection.knownHeadersPresent } } : null,
      pageNetworkPolicySatisfied: s.unexpectedRequests === 0 && s.requests <= MAX_REQUESTS
        && s.blockedFontRequests === 1 && s.sandboxRequests === 1 }),
    failure: () => s.failureCode,
    beginClosing: () => { s.closingBrowser = true; s.stage = 'BROWSER_CLOSE'; },
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
    awaitPromise: false, allowUnsafeEvalBlockedByCSP: false,
    ...(context ? { contextId: context.contextId } : {}) }, context?.session ?? s.sessionId);
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
  s.appContext = context;
  s.stage = 'REFRESH_COMPLETE';
  return facts;
}

/** The listener precedes the action, and a negative operation waits for its
 * independently queued violation event, not merely the fetch/image rejection.
 * A timer callback performs the network action in the actual default world. */
export function cspOperationExpression({ phase, operation, origin, canaryOrigin, sandboxUrl,
  allowlist = false, blocked = false }) {
  if (!PHASES.includes(phase) || !OPERATIONS.includes(operation)
      || !validCanaryOrigin(canaryOrigin, { origin, sandboxUrl })
      || typeof allowlist !== 'boolean' || typeof blocked !== 'boolean'
      || (blocked && (!allowlist || phase !== 'APP'))) fail('APPS_CSP_OPTIONS_INVALID');
  const negative = phase === 'APP' && (!allowlist || blocked);
  const target = blocked ? `${origin}/` : canaryTargetUrl(canaryOrigin, phase, operation);
  const targetOrigin = blocked ? origin : canaryOrigin;
  const policy = allowlist ? expectedAllowlistCsp(canaryOrigin) : EXPECTED_CSP;
  return `new Promise(resolve => {
    const expectedPolicy = ${JSON.stringify(policy)};
    const target = ${JSON.stringify(target)};
    const targetOrigin = ${JSON.stringify(targetOrigin)};
    const negative = ${negative};
    const diagnostics = { documentKind: 'NONE' };
    const row = { phase: ${JSON.stringify(phase)}, operation: ${JSON.stringify(operation)},
      context: ${JSON.stringify(phase === 'APP' ? 'APP' : 'MAIN')},
      attempted: false, completed: false, timedOut: false,
      contextMatches: location.href === ${JSON.stringify(phase === 'APP' ? 'about:srcdoc' : `${origin}/`)},
      succeeded: false, failed: false, responseMatches: false, violationBoundExceeded: false,
      settled: false, trusted: true, enforced: true, directiveMatches: true,
      targetMatches: true, documentMatches: true, policyMatches: true,
      violationCount: 0, matchingViolationCount: 0, unexpectedViolationCount: 0 };
    let finished = false, settleTimer, image;
    const finish = () => {
      if (finished) return;
      finished = true; clearTimeout(deadline); clearTimeout(settleTimer);
      document.removeEventListener('securitypolicyviolation', violation);
      if (image) { image.onload = null; image.onerror = null; }
      resolve({ row, diagnostics });
    };
    const maybeSettle = () => {
      if (finished || settleTimer !== undefined || !row.completed
          || (negative && row.failed && row.matchingViolationCount === 0)) return;
      settleTimer = setTimeout(() => { row.settled = true; finish(); }, 100);
    };
    const violation = event => {
      if (finished) return;
      if (row.violationCount === 0) {
        const value = event.documentURI;
        diagnostics.documentKind = value === 'about:srcdoc' ? 'SRCDOC' : value === 'about' ? 'ABOUT_SCHEME'
          : value === 'about:blank' ? 'ABOUT_BLANK' : value === '' ? 'EMPTY'
          : value === null ? 'NULL' : value === undefined ? 'UNDEFINED'
          : value === ${JSON.stringify(sandboxUrl)} ? 'SANDBOX_DOCUMENT'
          : value === ${JSON.stringify(`${origin}/`)} ? 'MAIN_DOCUMENT'
          : value === ${JSON.stringify(new URL(sandboxUrl).origin)} ? 'SANDBOX_ORIGIN'
          : value === ${JSON.stringify(origin)} ? 'MAIN_ORIGIN'
          : value === targetOrigin ? 'CANARY_ORIGIN' : 'UNKNOWN';
      }
      row.violationCount = Math.min(9, row.violationCount + 1);
      if (row.violationCount > 8) row.violationBoundExceeded = true;
      const checks = {
        trusted: event.isTrusted === true, enforced: event.disposition === 'enforce',
        directiveMatches: event.effectiveDirective === ${JSON.stringify(operation === 'connect' ? 'connect-src' : 'img-src')},
        targetMatches: event.blockedURI === target || event.blockedURI === targetOrigin,
        // Pinned Chrome's attempt5 reports the non-HTTP srcdoc URL as its
        // scheme, as CSP's report-URL stripping requires. Real frame/location
        // identity is checked independently; no other reported spelling passes.
        documentMatches: event.documentURI === 'about',
        policyMatches: event.originalPolicy === expectedPolicy };
      const matched = Object.values(checks).every(value => value === true);
      for (const key of Object.keys(checks)) row[key] = row[key] && checks[key];
      const counter = matched ? 'matchingViolationCount' : 'unexpectedViolationCount';
      row[counter] = Math.min(9, row[counter] + 1);
      maybeSettle();
    };
    const complete = (succeeded, responseMatches) => {
      if (finished || row.completed) return;
      row.completed = true; row.succeeded = succeeded;
      row.failed = !succeeded; row.responseMatches = responseMatches;
      maybeSettle();
    };
    document.addEventListener('securitypolicyviolation', violation);
    const deadline = setTimeout(() => { row.timedOut = true; finish(); }, 2000);
    setTimeout(() => {
      if (finished) return;
      row.attempted = true;
      ${operation === 'connect' ? `
      fetch(target, { mode: 'cors', credentials: 'omit', cache: 'no-store',
        referrerPolicy: 'no-referrer', redirect: 'error' })
        .then(async response => {
          const body = await response.text();
          complete(true, response.ok && response.status === 200
            && response.headers.get('content-type') === 'text/plain;charset=utf-8'
            && body === 'soklet-csp-canary');
        }).catch(() => complete(false, false));` : `
      image = new Image(); image.crossOrigin = 'anonymous'; image.referrerPolicy = 'no-referrer';
      image.onload = () => image.decode().then(() =>
        complete(true, image.naturalWidth === 1 && image.naturalHeight === 1))
        .catch(() => complete(false, false));
      image.onerror = () => complete(false, false);
      image.src = target;`}
    }, 0);
  })`;
}

/** Probe only the two empty-CSP egress directives. Frame/base-uri restrictions
 * are verified as policy text, not claimed as active denial tests. */
export async function exerciseCsp(cdp, sessionId, observation, { setPhase } = {}) {
  const s = states.get(observation);
  if (!s || s.cdp !== cdp || s.sessionId !== sessionId || typeof setPhase !== 'function'
      || s.cspPhase !== 'IDLE' || s.stage !== 'REFRESH_COMPLETE') fail('APPS_CSP_OPTIONS_INVALID');
  const evidence = { status: 'FAILED', rows: [], ...Object.fromEntries(CSP_FLAGS.map(key => [key, false])) };
  s.cspEvidence = evidence;
  const reject = code => { s.failureCode ??= code; fail(code); };
  try {
    s.stage = 'CSP_VERIFY';
    const app = s.appContext, frame = app && s.frames.get(app.frameId);
    const mains = [...s.contexts.values()].filter(context => context.session === s.sessionId
      && s.frames.get(context.frameId)?.kind === 'main');
    if (!app || s.contexts.get(`${app.session}:${app.contextId}`) !== app
        || frame?.kind !== 'app' || s.frames.get(frame.parentId)?.kind !== 'sandbox'
        || mains.length !== 1 || !await evaluate(s, readyExpression, app)) reject('APPS_CSP_CONTEXT_MISMATCH');
    evidence.appContextVerified = true;
    s.cspMainContext = mains[0];
    const sandboxes = [...s.contexts.values()].filter(context => context.frameId === frame.parentId);
    if (sandboxes.length !== 1 || !await evaluate(s, `(() => {
      const frames = [...document.querySelectorAll('iframe')];
      return location.href === ${JSON.stringify(s.sandboxUrl)} && frames.length === 1
        && frames[0].hasAttribute('srcdoc') && !frames[0].hasAttribute('src')
        && frames[0].getAttribute('sandbox') === 'allow-scripts allow-forms'
        && !frames[0].hasAttribute('allow');
    })()`, sandboxes[0])) reject('APPS_CSP_CONTEXT_MISMATCH');
    evidence.sandboxVerified = true;
    if (!await evaluate(s, `(() => {
      const metas = [...document.querySelectorAll('meta[http-equiv]')]
        .filter(meta => meta.getAttribute('http-equiv').toLowerCase() === 'content-security-policy');
      return location.href === 'about:srcdoc' && globalThis.origin === 'null'
        && metas.length === 1 && document.head.firstElementChild === metas[0]
        && metas[0].getAttribute('content') === ${JSON.stringify(EXPECTED_CSP)};
    })()`, app)) reject('APPS_CSP_POLICY_MISMATCH');
    evidence.policyVerified = true;
    for (let phaseIndex = 0; phaseIndex < PHASES.length; phaseIndex++) {
      const phase = PHASES[phaseIndex];
      try { await setPhase(phase); } catch { reject('APPS_CSP_PHASE_FAILED'); }
      s.cspPhase = phase;
      s.stage = `CSP_${phase}`;
      for (const operation of OPERATIONS) {
        if (s.failureCode) fail(s.failureCode);
        if (s.closed || s.closingBrowser || cdp.failure()) reject('APPS_CSP_EVALUATION_FAILED');
        const context = phase === 'APP' ? app : mains[0];
        if (s.contexts.get(`${context.session}:${context.contextId}`) !== context)
          reject('APPS_CSP_CONTEXT_MISMATCH');
        s.activeOperation = operation;
        let result;
        try {
          result = await cdp.send('Runtime.evaluate', {
            expression: cspOperationExpression({ ...s, phase, operation }), returnByValue: true,
            awaitPromise: true, allowUnsafeEvalBlockedByCSP: false, contextId: context.contextId,
          }, context.session);
        } catch { reject('APPS_CSP_EVALUATION_FAILED'); }
        finally { s.activeOperation = undefined; }
        if (result.exceptionDetails || result.result?.type !== 'object') reject('APPS_CSP_EVALUATION_FAILED');
        const projected = projectCspOperationResult(result.result.value);
        if (!projected) reject('APPS_CSP_EVALUATION_FAILED');
        const { row, diagnostics } = projected;
        evidence.rows.push(row);
        s.cspViolationDiagnostics.push({ phase: row.phase, operation: row.operation, ...diagnostics });
        if (!validCspRow(row, evidence.rows.length - 1)) reject('APPS_CSP_ROW_REJECTED');
        if (s.failureCode) fail(s.failureCode);
        if (cdp.failure()) reject('APPS_CSP_EVALUATION_FAILED');
      }
      evidence[['controlsBeforePassed', 'appDenialsPassed', 'controlsAfterPassed'][phaseIndex]] = true;
    }
    evidence.settled = true;
    evidence.status = 'PASSED';
    if (adjudicateCspEvidence(evidence) !== 'PASSED'
        || ![0, 1, 4, 5].every(index => s.canaryContinued[index] === 1)
        || ![2, 3].every(index => s.canaryContinued[index] >= 0 && s.canaryContinued[index] <= 1)
        || s.canaryBlocked !== 0) reject('APPS_CSP_EVIDENCE_REJECTED');
    s.stage = 'CSP_COMPLETE';
    return { ...evidence, rows: evidence.rows.map(row => ({ ...row })) };
  } catch (error) {
    evidence.status = 'FAILED';
    const code = CSP_FAILURES.includes(error?.message) || (s.failureCode && error?.message === s.failureCode)
      ? error.message : 'APPS_CSP_EVALUATION_FAILED';
    s.failureCode ??= code;
    fail(code);
  }
}

/** Exercise the same rendered App with one declared loopback origin and the
 * live Inspector origin as an undeclared target. Real server contact is checked
 * independently by the canary and host trace. */
export async function exerciseAllowlist(cdp, sessionId, observation, {setPhase} = {}) {
  const s = states.get(observation);
  if (!s || s.cdp !== cdp || s.sessionId !== sessionId || s.allowlist !== true
      || typeof setPhase !== 'function' || s.cspPhase !== 'IDLE'
      || s.stage !== 'REFRESH_COMPLETE') fail('APPS_CSP_OPTIONS_INVALID');
  const evidence = {status: 'FAILED', rows: [], ...Object.fromEntries(ALLOWLIST_FLAGS.map(key => [key, false]))};
  s.cspEvidence = evidence;
  const reject = code => { s.failureCode ??= code; fail(code); };
  try {
    s.stage = 'CSP_VERIFY';
    const app = s.appContext, frame = app && s.frames.get(app.frameId);
    const mains = [...s.contexts.values()].filter(context => context.session === s.sessionId
      && s.frames.get(context.frameId)?.kind === 'main');
    if (!app || s.contexts.get(`${app.session}:${app.contextId}`) !== app
        || frame?.kind !== 'app' || s.frames.get(frame.parentId)?.kind !== 'sandbox'
        || mains.length !== 1 || !await evaluate(s, readyExpression, app)) reject('APPS_CSP_CONTEXT_MISMATCH');
    evidence.appContextVerified = true;
    s.cspMainContext = mains[0];
    const sandboxes = [...s.contexts.values()].filter(context => context.frameId === frame.parentId);
    if (sandboxes.length !== 1 || !await evaluate(s, `(() => {
      const frames = [...document.querySelectorAll('iframe')];
      return location.href === ${JSON.stringify(s.sandboxUrl)} && frames.length === 1
        && frames[0].hasAttribute('srcdoc') && !frames[0].hasAttribute('src')
        && frames[0].getAttribute('sandbox') === 'allow-scripts allow-forms'
        && !frames[0].hasAttribute('allow');
    })()`, sandboxes[0])) reject('APPS_CSP_CONTEXT_MISMATCH');
    evidence.sandboxVerified = true;
    if (!await evaluate(s, `(() => {
      const metas = [...document.querySelectorAll('meta[http-equiv]')]
        .filter(meta => meta.getAttribute('http-equiv').toLowerCase() === 'content-security-policy');
      return location.href === 'about:srcdoc' && globalThis.origin === 'null'
        && metas.length === 1 && document.head.firstElementChild === metas[0]
        && metas[0].getAttribute('content') === ${JSON.stringify(expectedAllowlistCsp(s.canaryOrigin))};
    })()`, app)) reject('APPS_CSP_POLICY_MISMATCH');
    evidence.policyVerified = true;
    const steps = [
      [{operation: 'connect'}, {operation: 'image'}],
      [{operation: 'connect'}, {operation: 'image'},
        {operation: 'connect', blocked: true}, {operation: 'image', blocked: true}],
      [{operation: 'connect'}, {operation: 'image'}],
    ];
    for (let phaseIndex = 0; phaseIndex < PHASES.length; ++phaseIndex) {
      const phase = PHASES[phaseIndex];
      try { await setPhase(phase); } catch { reject('APPS_CSP_PHASE_FAILED'); }
      s.cspPhase = phase;
      s.stage = `CSP_${phase}`;
      for (const {operation, blocked = false} of steps[phaseIndex]) {
        if (s.failureCode) fail(s.failureCode);
        if (s.closed || s.closingBrowser || cdp.failure()) reject('APPS_CSP_EVALUATION_FAILED');
        const context = phase === 'APP' ? app : mains[0];
        if (s.contexts.get(`${context.session}:${context.contextId}`) !== context)
          reject('APPS_CSP_CONTEXT_MISMATCH');
        s.activeOperation = blocked ? undefined : operation;
        let result;
        try {
          result = await cdp.send('Runtime.evaluate', {
            expression: cspOperationExpression({...s, phase, operation, blocked}), returnByValue: true,
            awaitPromise: true, allowUnsafeEvalBlockedByCSP: false, contextId: context.contextId,
          }, context.session);
        } catch { reject('APPS_CSP_EVALUATION_FAILED'); }
        finally { s.activeOperation = undefined; }
        if (result.exceptionDetails || result.result?.type !== 'object') reject('APPS_CSP_EVALUATION_FAILED');
        const projected = projectCspOperationResult(result.result.value);
        if (!projected) reject('APPS_CSP_EVALUATION_FAILED');
        const {row, diagnostics} = projected;
        evidence.rows.push({...row, targetKind: blocked ? 'UNDECLARED' : 'DECLARED'});
        s.cspViolationDiagnostics.push({phase: row.phase, operation: row.operation, ...diagnostics});
        if (s.failureCode || cdp.failure()) reject('APPS_CSP_ROW_REJECTED');
      }
      evidence[['controlsBeforePassed', 'appChecksPassed', 'controlsAfterPassed'][phaseIndex]] = true;
    }
    evidence.settled = true;
    evidence.status = 'PASSED';
    if (adjudicateAllowlistEvidence(evidence) !== 'PASSED'
        || !s.canaryContinued.every(count => count === 1) || s.canaryBlocked !== 0)
      reject('APPS_CSP_EVIDENCE_REJECTED');
    s.stage = 'CSP_COMPLETE';
    return {...evidence, rows: evidence.rows.map(row => ({...row}))};
  } catch (error) {
    evidence.status = 'FAILED';
    const code = CSP_FAILURES.includes(error?.message) || (s.failureCode && error?.message === s.failureCode)
      ? error.message : 'APPS_CSP_EVALUATION_FAILED';
    s.failureCode ??= code;
    fail(code);
  }
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
