import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { resolve } from 'node:path';
import { connectCdp } from './cdp.mjs';
import { createSessionConfig, sanitizedSessionConfig } from './config.mjs';
import { adjudicateWebTrace, startProxy } from './trace.mjs';
import { parseControl } from '../run-against-public-fixture.mjs';

const fail = code => { throw new Error(code); };
const pause = () => new Promise(resolveWait => setTimeout(resolveWait, 50));
const json = value => `${JSON.stringify(value, null, 2)}\n`;
// Pinned upstream index.html requests an optional Google Fonts stylesheet.
// Block it, use system fonts, and distinguish this known denial from a new URL.
const PINNED_FONT = 'https://fonts.googleapis.com/css2?family=Fredoka:wght@300..700&family=Roboto+Mono:ital,wght@0,100..700;1,100..700&display=swap';

export function browserRequestPolicy(request, origin, resourceType) {
  try {
    if (typeof request?.url !== 'string' || typeof request?.method !== 'string') return 'BLOCKED_UNEXPECTED';
    const url = new URL(request.url);
    if (url.origin === origin && !url.username && !url.password) return 'SAME_ORIGIN';
    if (request.url === PINNED_FONT && request.method === 'GET' && resourceType === 'Stylesheet')
      return 'BLOCKED_PINNED_FONT';
  } catch { /* A malformed URL is never forwarded or persisted. */ }
  return 'BLOCKED_UNEXPECTED';
}

export function chromeArguments(profile) {
  if (typeof profile !== 'string' || profile !== resolve(profile) || profile === '/'
      || /[\u0000-\u001f\u007f]/u.test(profile))
    fail('BROWSER_PROFILE_INVALID');
  return ['--headless=new', `--user-data-dir=${profile}`, '--remote-debugging-address=127.0.0.1',
    '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--disable-default-apps', '--disable-domain-reliability', '--disable-breakpad',
    '--disable-features=MediaRouter,OptimizationHints,AutofillServerCommunication',
    '--password-store=basic', '--use-mock-keychain', '--no-proxy-server',
    '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost',
    '--window-size=1440,1000', 'about:blank'];
}

export function validWebConfig(config, origin) {
  if (config?.writable !== false || config.secretStorage?.kind !== 'memory'
      || config.secretStorage?.reason !== 'configured' || config.secretStorage?.durable !== false)
    return false;
  try {
    const main = new URL(origin);
    const sandbox = new URL(config.sandboxUrl);
    return typeof origin === 'string' && main.origin === origin && Number(main.port) > 0
      && main.protocol === 'http:' && main.hostname === '127.0.0.1'
      && typeof config.sandboxUrl === 'string' && sandbox.href === config.sandboxUrl
      && sandbox.protocol === 'http:' && sandbox.hostname === '127.0.0.1'
      && sandbox.origin !== origin && Number(sandbox.port) > 0 && sandbox.username === ''
      && sandbox.password === '' && sandbox.pathname === '/sandbox' && !sandbox.search && !sandbox.hash;
  } catch { return false; }
}

export function browserVersion(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || typeof value.product !== 'string' || typeof value.revision !== 'string'
      || value.product.length > 80
      || /[\r\n]/.test(value.product + value.revision)
      || !/^(?:Headless)?Chrome\/\d+\.\d+\.\d+\.\d+$/.test(value.product)
      || !/^@[a-f0-9]{40}$/.test(value.revision) || value.protocolVersion !== '1.3')
    fail('BROWSER_VERSION_INVALID');
  return { product: value.product, revision: value.revision, protocolVersion: value.protocolVersion };
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  const port = server.address().port;
  await new Promise(resolveClose => server.close(resolveClose));
  return port;
}

async function api(origin, token, { authenticated = true, requestOrigin = origin } = {}) {
  const response = await fetch(`${origin}/api/config`, {
    headers: { Origin: requestOrigin, ...(authenticated ? { 'x-mcp-remote-auth': `Bearer ${token}` } : {}) },
    signal: AbortSignal.timeout(1500), redirect: 'error',
  });
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 65536) { await reader.cancel(); fail('WEB_CONFIG_BOUND'); }
    chunks.push(Buffer.from(value));
  }
  const bytes = Buffer.concat(chunks);
  return { status: response.status, config: response.status === 200
    ? JSON.parse(new TextDecoder().decode(bytes)) : null };
}

async function readyWeb(origin, token, exited) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (exited()) fail('WEB_HOST_EARLY_EXIT');
    try {
      const result = await api(origin, token);
      if (result.status === 200) return result.config;
    } catch { /* Not listening yet; retry only inside the startup bound. */ }
    await pause();
  }
  fail('WEB_HOST_READY_TIMEOUT');
}

async function devToolsEndpoint(profile, exited) {
  const file = resolve(profile, 'DevToolsActivePort');
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (exited()) fail('BROWSER_EARLY_EXIT');
    if (existsSync(file)) {
      const value = readFileSync(file, 'utf8');
      if (value.length > 512) fail('BROWSER_ENDPOINT_INVALID');
      const [port, path] = value.trimEnd().split('\n');
      if (!/^[1-9][0-9]{0,4}$/.test(port) || Number(port) > 65535
          || !/^\/devtools\/browser\/[a-f0-9-]+$/.test(path)) fail('BROWSER_ENDPOINT_INVALID');
      return `ws://127.0.0.1:${port}${path}`;
    }
    await pause();
  }
  fail('BROWSER_READY_TIMEOUT');
}

async function evaluate(cdp, session, expression) {
  const result = await cdp.send('Runtime.evaluate', { expression,
    returnByValue: true, awaitPromise: false }, session);
  if (result.exceptionDetails || result.result?.type !== 'boolean') fail('WEB_UI_EVALUATION_FAILED');
  return result.result.value === true;
}

async function until(cdp, session, expression) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (await evaluate(cdp, session, expression)) return;
    await pause();
  }
  fail('WEB_UI_TIMEOUT');
}

function click(selector, text) {
  return `(() => { const nodes = [...document.querySelectorAll(${JSON.stringify(selector)})]
    .filter(node => node.getClientRects().length && !node.disabled
      ${text === undefined ? '' : `&& node.textContent.trim() === ${JSON.stringify(text)}`});
    if (nodes.length !== 1) return false; nodes[0].click(); return true; })()`;
}

export async function probeWeb({ java, classpath, entry, work, enabled, chrome, helpers }) {
  const { managed, makeIsolation, readyLine, root, fixtureMain, fixtureWarning } = helpers;
  const name = `web-${enabled ? 'enabled' : 'disabled'}`;
  const sessionRoot = resolve(work, `private-${name}`);
  const env = makeIsolation(sessionRoot, java);
  const token = randomBytes(32).toString('hex');
  const hostToken = randomBytes(32).toString('hex');
  let fixture, proxy, host, browser, cdp;
  let hostExited = false, browserExited = false;
  let unexpectedNetwork = false, browserException = false;
  let requestCount = 0, blockedFontRequests = 0;
  const record = { name, requestedExtensions: enabled ? 'ENABLED' : 'DISABLED',
    operation: 'tools/list+tools/call', status: 'FAILED', stage: 'STARTUP',
    baseTransport: 'NOT_RUN', fixtureShutdown: 'NOT_PROVEN', hostShutdown: 'NOT_PROVEN',
    browserShutdown: 'NOT_PROVEN', configUnchanged: false, trace: [] };
  try {
    fixture = managed(java, ['-cp', classpath, fixtureMain, '--scenario', 'tools-list'],
      { cwd: root, env, timeoutMs: 120000, stdin: 'pipe' });
    void fixture.completion.catch(() => {});
    const ready = await readyLine(fixture);
    if (ready.host !== '127.0.0.1' || ready.path !== '/mcp') fail('FIXTURE_ADDRESS_INVALID');
    proxy = await startProxy({ fixturePort: ready.port, token, enabled, surface: 'web' });
    const config = createSessionConfig(`http://127.0.0.1:${proxy.port}/mcp`, token, enabled);
    const configPath = resolve(sessionRoot, 'session.json');
    const configBytes = json(config);
    writeFileSync(configPath, configBytes, { mode: 0o600, flag: 'wx' });
    record.config = sanitizedSessionConfig(config);
    const port = await freePort();
    const origin = `http://127.0.0.1:${port}`;
    host = managed(process.execPath, [entry, '--web', '--config', configPath], {
      cwd: sessionRoot, env: { ...env, HOST: '127.0.0.1', CLIENT_PORT: String(port),
        ALLOWED_ORIGINS: origin, MCP_SANDBOX_PORT: '0', MCP_APP_ORIGIN_PORT: '0',
        MCP_INSPECTOR_API_TOKEN: hostToken }, timeoutMs: 120000,
    });
    void host.completion.then(() => { hostExited = true; }, () => { hostExited = true; });
    const initial = await readyWeb(origin, hostToken, () => hostExited);
    record.readOnlyMemoryStore = validWebConfig(initial, origin);
    record.hostAuthRequired = (await api(origin, hostToken, { authenticated: false })).status === 401;
    record.hostOriginRestricted = (await api(origin, hostToken,
      { requestOrigin: 'http://127.0.0.1:1' })).status === 403;
    if (!record.readOnlyMemoryStore || !record.hostAuthRequired || !record.hostOriginRestricted)
      fail('WEB_HOST_ISOLATION_FAILED');
    record.stage = 'BROWSER';
    const profile = resolve(sessionRoot, 'chrome-profile');
    mkdirSync(profile, { mode: 0o700 });
    browser = managed(chrome, chromeArguments(profile), { cwd: sessionRoot, env, timeoutMs: 90000 });
    void browser.completion.then(() => { browserExited = true; }, () => { browserExited = true; });
    cdp = await connectCdp(await devToolsEndpoint(profile, () => browserExited));
    record.browserVersion = browserVersion(await cdp.send('Browser.getVersion'));
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    cdp.on('Runtime.exceptionThrown', () => { browserException = true; });
    cdp.on('Fetch.requestPaused', async (event, session) => {
      const policy = browserRequestPolicy(event.request, origin, event.resourceType);
      const allowed = ++requestCount <= 256 && policy === 'SAME_ORIGIN';
      if (policy === 'BLOCKED_PINNED_FONT') blockedFontRequests += 1;
      if (requestCount > 256 || policy === 'BLOCKED_UNEXPECTED') unexpectedNetwork = true;
      await cdp.send(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest',
        { requestId: event.requestId, ...(!allowed ? { errorReason: 'BlockedByClient' } : {}) }, session);
    });
    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] }, sessionId);
    await cdp.send('Page.navigate', { url: origin }, sessionId);
    record.stage = 'CONNECT';
    const switchSelector = '[aria-label=\'Connect or disconnect "soklet"\']';
    await until(cdp, sessionId, click(switchSelector));
    record.stage = 'LIST';
    await until(cdp, sessionId, click('input[type="radio"][value="Tools"]'));
    await until(cdp, sessionId, click('button', 'test_simple_text'));
    record.toolsListVisible = true;
    const listsDeadline = Date.now() + 10000;
    while (proxy.rows.length < 6 && Date.now() < listsDeadline && !proxy.failure()) await pause();
    if (proxy.rows.length !== 6 || proxy.failure()) fail('WEB_CATALOG_LOAD_FAILED');
    record.stage = 'CALL';
    await until(cdp, sessionId, click('button', 'Execute Tool'));
    await until(cdp, sessionId, 'document.body.innerText.includes("This is a simple text response for testing.")');
    record.toolResultVisible = true;
    record.stage = 'DISCONNECT';
    if (!await evaluate(cdp, sessionId, click('[aria-label="Disconnect from server"]')))
      fail('WEB_UI_DISCONNECT_FAILED');
    await until(cdp, sessionId, `document.querySelector(${JSON.stringify(switchSelector)})?.checked === false`);
    record.disconnected = true;
    record.pageNetworkPolicySatisfied = !unexpectedNetwork && blockedFontRequests === 1;
    record.blockedPinnedFontRequests = blockedFontRequests;
    record.browserRequestCount = requestCount;
    record.noBrowserExceptions = !browserException;
    if (cdp.failure() || !record.pageNetworkPolicySatisfied || browserException) fail('WEB_BROWSER_CHECK_FAILED');
    record.stage = 'SHUTDOWN';
    await cdp.send('Browser.close');
    await cdp.close();
    const browserExit = await browser.completion;
    if (browserExit.code === 0 && browserExit.signal === null) record.browserShutdown = 'CLEAN';
    // Seal observations only after the page and CDP have closed: late shutdown
    // events must not escape the verdict computed while the UI was connected.
    record.pageNetworkPolicySatisfied = !unexpectedNetwork && blockedFontRequests === 1;
    record.blockedPinnedFontRequests = blockedFontRequests;
    record.browserRequestCount = requestCount;
    record.noBrowserExceptions = !browserException;
    if (!record.pageNetworkPolicySatisfied || browserException
        || (cdp.failure() && cdp.failure() !== 'CDP_CLOSED')) fail('WEB_BROWSER_CHECK_FAILED');
    host.child.kill('SIGTERM');
    const hostExit = await host.completion;
    if (hostExit.code === 0 && hostExit.signal === null) record.hostShutdown = 'CLEAN';
    fixture.child.stdin.end();
    const fixtureExit = await fixture.completion;
    const lines = fixtureExit.stdout.trimEnd().split('\n');
    const stopped = lines.length === 2 && parseControl(lines[1], 'stopped');
    if (fixtureExit.code === 0 && fixtureExit.signal === null && stopped?.clean === true
        && fixtureExit.stderr === fixtureWarning) record.fixtureShutdown = 'CLEAN';
    record.configUnchanged = readFileSync(configPath, 'utf8') === configBytes;
    record.trace = proxy.rows;
    if (!record.configUnchanged || [record.browserShutdown, record.hostShutdown,
      record.fixtureShutdown].some(value => value !== 'CLEAN') || proxy.failure()) fail('WEB_PROBE_CHECK_FAILED');
    record.status = adjudicateWebTrace(record.trace, enabled);
    if (record.status === 'PASSED') record.baseTransport = 'PASSED';
    record.stage = 'COMPLETE';
  } catch {
    record.failure = proxy?.failure() ?? cdp?.failure() ?? 'WEB_PROBE_FAILED';
    record.trace = proxy?.rows ?? [];
  } finally {
    let cdpClosed = true;
    // Complete the socket close while Chrome is still alive; racing it with
    // process termination can prevent the WebSocket close handshake.
    try { await cdp?.close(); } catch { cdpClosed = false; }
    const cleanup = await Promise.allSettled([
      browser?.stop(), host?.stop(), proxy?.close(), fixture?.stop(),
    ]);
    record.cleanup = { cdp: cdpClosed, browser: cleanup[0].status === 'fulfilled',
      host: cleanup[1].status === 'fulfilled', proxy: cleanup[2].status === 'fulfilled',
      fixture: cleanup[3].status === 'fulfilled' };
    if (!cdpClosed || cleanup.some(result => result.status === 'rejected')) {
      record.status = 'FAILED'; record.failure = 'WEB_PROBE_CLEANUP_FAILED';
    }
    rmSync(sessionRoot, { recursive: true, force: true });
  }
  return record;
}
