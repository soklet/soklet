#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment, createSessionConfig, sanitizedSessionConfig } from '../inspector/config.mjs';
import { startProcess } from '../inspector/process.mjs';
import { directoryIdentity, verifyDependencyPins } from '../inspector/run.mjs';
import { browserVersion, chromeArguments, validWebConfig } from '../inspector/web-probe.mjs';
import { connectCdp } from '../inspector/cdp.mjs';
import { regularFile, validateShellBuild } from './run.mjs';
import { startAppsProxy, adjudicateAppsTrace } from './host-trace.mjs';
import { beginBrowserObservation, exerciseApps, disconnectApps } from './browser-probe.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hash = path => sha(readFileSync(path));
const json = value => JSON.stringify(value, null, 2) + '\n';
const fail = code => { throw new Error(code); };
const pause = () => new Promise(done => setTimeout(done, 50));
const uiChecks = ['selectedAppViaDom', 'hostAppReady', 'catalogRendered', 'textOnlyHostileLabel',
  'serverSelectedLocaleRendered', 'currencyAndUtcDateRendered', 'opaqueSrcdocSandboxObserved',
  'refreshClickedViaDom', 'pendingClearedPriorData', 'refreshRendered'];
// Exact installed tree retained from the reviewed, script-disabled lock install.
// Reusing it avoids npm/network/lifecycle work during the browser probe.
const installedPin = { files: 9391, sha256: '8c0b1ed101c4c7e7497aa4aaba7e953b03a44bc58179308db1613a988d5a2b8d' };

export function parseHostArguments(args) {
  const keys = ['--candidate-jar', '--candidate-pom', '--java', '--shell', '--dependencies', '--browser', '--work-dir'];
  if (args.length !== keys.length * 2) fail('APPS_HOST_ARGUMENTS');
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!keys.includes(args[i]) || Object.hasOwn(options, args[i]) || !args[i + 1]) fail('APPS_HOST_ARGUMENTS');
    options[args[i]] = args[i + 1];
  }
  return options;
}

export function fixtureControl(line, event) {
  let value;
  try { value = JSON.parse(line); } catch { fail('APPS_HOST_CONTROL'); }
  if (value?.format !== 1 || value.event !== event) fail('APPS_HOST_CONTROL');
  if (event === 'ready' && Object.keys(value).sort().join(',') === 'event,format,host,path,port'
      && value.host === '127.0.0.1' && value.path === '/apps'
      && Number.isSafeInteger(value.port) && value.port > 0 && value.port <= 65535) return value;
  if (event === 'stopped' && Object.keys(value).sort().join(',') === 'clean,event,format' && value.clean === true) return value;
  fail('APPS_HOST_CONTROL');
}

export function completedHostChecks(receipt) {
  return receipt.traceVerdict === 'PASSED' && uiChecks.every(key => receipt.ui?.[key] === true)
    && receipt.browser?.pageNetworkPolicySatisfied === true && receipt.browser?.noBrowserExceptions === true
    && receipt.authentication?.invalidCredentialRejected === true && receipt.authentication?.validCredentialAccepted === true
    && ['fixtureShutdown', 'hostShutdown', 'browserShutdown'].every(key => receipt[key] === 'CLEAN')
    && ['readOnlyMemoryStore', 'hostAuthRequired', 'hostOriginRestricted', 'configUnchanged',
      'inputsUnchanged', 'disconnected', 'privateStateRemoved'].every(key => receipt[key] === true)
    && ['cdp', 'browser', 'host', 'proxy', 'fixture'].every(key => receipt.cleanup?.[key] === true);
}

/** An observed rendering result is not an aggregate host PASS when the pinned
 * host attempts unconfigured OAuth recovery after a deliberate policy denial. */
export function adjudicateHost(receipt, rejections) {
  if (!completedHostChecks(receipt) || receipt.browserFailure || receipt.cleanupFailure
      || !Array.isArray(rejections)) return 'FAILED';
  if (!receipt.traceFailure && rejections.length === 0) return 'PASSED';
  const paths = ['OAUTH_PROTECTED_RESOURCE_PATH', 'OAUTH_PROTECTED_RESOURCE_ROOT',
    'OAUTH_AUTHORIZATION_SERVER_ROOT', 'OPENID_CONFIGURATION_ROOT',
    'OPENID_CONFIGURATION_ROOT', 'OAUTH_REGISTRATION_ROOT'];
  return receipt.traceFailure === 'APPS_POST_ONLY' && rejections.length === paths.length
    && rejections.every((row, index) => row.sequence === index + 1 && row.path === paths[index]
      && row.method === (index === 5 ? 'POST' : 'GET')
      && row.code === (index === 5 ? 'APPS_PATH' : 'APPS_POST_ONLY') && row.originAbsent === true)
    ? 'BLOCKED_HOST_AUTH_FALLBACK' : 'FAILED';
}

function cleanFixtureExit(exit) {
  const lines = exit.stdout.trimEnd().split('\n');
  try {
    return lines.length === 2 && fixtureControl(lines[0], 'ready')
      && fixtureControl(lines[1], 'stopped').clean && exit.code === 0
      && exit.signal === null && exit.stderr === '';
  } catch { return false; }
}

async function fixtureEof(handle) {
  if (!handle) return null;
  if (!handle.child.stdin.destroyed) handle.child.stdin.end();
  let timer;
  try {
    return await Promise.race([handle.completion,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('APPS_HOST_FIXTURE_STOP_TIMEOUT')), 6000); })]);
  } finally { clearTimeout(timer); }
}

async function boundedExit(handle) {
  let timer;
  try {
    return await Promise.race([handle.completion,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('APPS_HOST_STOP_TIMEOUT')), 3000); })]);
  } finally { clearTimeout(timer); }
}

function readyLine(handle) {
  return new Promise((done, reject) => {
    let buffer = '';
    const timer = setTimeout(() => finish(new Error('APPS_HOST_FIXTURE_READY_TIMEOUT')), 10000);
    const data = bytes => {
      buffer += bytes.toString('utf8');
      if (Buffer.byteLength(buffer) > 8192) return finish(new Error('APPS_HOST_CONTROL_BOUND'));
      const index = buffer.indexOf('\n');
      if (index !== -1) {
        try { finish(null, fixtureControl(buffer.slice(0, index), 'ready')); }
        catch { finish(new Error('APPS_HOST_CONTROL')); }
      }
    };
    const close = () => finish(new Error('APPS_HOST_FIXTURE_EARLY_EXIT'));
    function finish(error, value) {
      clearTimeout(timer);
      handle.child.stdout.off('data', data);
      handle.child.off('close', close);
      if (error) reject(error); else done(value);
    }
    handle.child.stdout.on('data', data);
    handle.child.once('close', close);
  });
}

async function boundedJson(response) {
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > 65536) { await reader.cancel(); fail('APPS_HOST_RESPONSE_BOUND'); }
    chunks.push(Buffer.from(value));
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

async function hostApi(origin, token, authenticated = true, requestOrigin = origin) {
  const response = await fetch(`${origin}/api/config`, { headers: { Origin: requestOrigin,
    ...(authenticated ? { 'x-mcp-remote-auth': `Bearer ${token}` } : {}) },
  signal: AbortSignal.timeout(1500), redirect: 'error' });
  if (response.status !== 200) { await response.body.cancel(); return {status: response.status}; }
  return {status: response.status, config: await boundedJson(response)};
}

async function readyHost(origin, token, exited) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (exited()) fail('APPS_HOST_EARLY_EXIT');
    try {
      const result = await hostApi(origin, token);
      if (result.status === 200) return result.config;
    } catch { /* A startup retry never extends the deadline. */ }
    await pause();
  }
  fail('APPS_HOST_READY_TIMEOUT');
}

async function freePort() {
  const server = createServer();
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  const port = server.address().port;
  await new Promise(done => server.close(done));
  return port;
}

async function devToolsEndpoint(profile, exited) {
  const path = resolve(profile, 'DevToolsActivePort');
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (exited()) fail('APPS_HOST_BROWSER_EARLY_EXIT');
    if (existsSync(path)) {
      const value = readFileSync(path, 'utf8');
      const match = /^([1-9][0-9]{0,4})\n(\/devtools\/browser\/[a-f0-9-]+)\n?$/.exec(value);
      if (value.length > 512 || !match || Number(match[1]) > 65535) fail('APPS_HOST_BROWSER_ENDPOINT');
      return `ws://127.0.0.1:${match[1]}${match[2]}`;
    }
    await pause();
  }
  fail('APPS_HOST_BROWSER_READY_TIMEOUT');
}

async function fixtureAuthentication(port, token) {
  const facts = {};
  for (const authorized of [false, true]) {
    const body = {jsonrpc: '2.0', id: 1, method: 'server/discover', params: {_meta: {
      'io.modelcontextprotocol/protocolVersion': '2026-07-28',
      'io.modelcontextprotocol/clientCapabilities': {extensions: {
        'io.modelcontextprotocol/ui': {mimeTypes: ['text/html;profile=mcp-app'], elicitation: {}}
      }}
    }}};
    const response = await fetch(`http://127.0.0.1:${port}/apps`, {
      method: 'POST', headers: {'content-type': 'application/json', accept: 'application/json, text/event-stream',
        'mcp-protocol-version': '2026-07-28', 'mcp-method': 'server/discover',
        Authorization: `Bearer ${authorized ? token : 'invalid-disposable-token'}`},
      body: JSON.stringify(body), signal: AbortSignal.timeout(5000), redirect: 'error'
    });
    const result = await boundedJson(response);
    if (response.headers.get('cache-control') !== 'no-store') fail('APPS_HOST_AUTH_CACHE');
    if (authorized) facts.validCredentialAccepted = response.status === 200 && result.result?.resultType === 'complete';
    else facts.invalidCredentialRejected = response.status === 401 && result.error?.code === -31901
      && result.error?.message === 'Authentication required.';
  }
  if (Object.values(facts).some(value => value !== true)) fail('APPS_HOST_AUTHENTICATION');
  return facts;
}

function sourceInputs() {
  const rows = [];
  function visit(path) {
    for (const entry of readdirSync(path, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isSymbolicLink()) fail('APPS_HOST_SOURCE_SYMLINK');
      const file = resolve(path, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) rows.push({path: file.slice(root.length + 1), sha256: hash(file)});
    }
  }
  visit(here);
  visit(resolve(here, '../inspector'));
  const control = resolve(here, '../run-against-public-fixture.mjs');
  rows.push({path: control.slice(root.length + 1), sha256: hash(control)});
  return rows;
}

export async function runHost(options) {
  const jar = regularFile(options['--candidate-jar']);
  const pom = regularFile(options['--candidate-pom']);
  const java = regularFile(options['--java']);
  const shell = regularFile(options['--shell']);
  const shellReceipt = regularFile(shell + '.receipt.json');
  const browserPath = regularFile(options['--browser']);
  if (process.platform !== 'darwin' || !browserPath.endsWith('/Google Chrome.app/Contents/MacOS/Google Chrome')
      || process.versions.node !== '26.5.0') fail('APPS_HOST_RUNTIME');
  const browserRoot = resolve(dirname(browserPath), '../..');
  const dependencies = resolve(options['--dependencies']);
  const modules = resolve(dependencies, 'node_modules');
  const inspector = resolve(modules, '@modelcontextprotocol/inspector');
  const entry = regularFile(resolve(inspector, 'clients/launcher/build/index.js'));
  regularFile(resolve(inspector, 'clients/web/dist/index.html'));
  verifyDependencyPins(readFileSync(resolve(dependencies, 'package.json')), readFileSync(resolve(dependencies, 'package-lock.json')));
  const installed = directoryIdentity(modules);
  if (JSON.stringify(installed) !== JSON.stringify(installedPin)) fail('APPS_HOST_INSTALLED_TREE_DRIFT');
  if (lstatSync(shell).size > 512 * 1024 || lstatSync(shellReceipt).size > 2 * 1024 * 1024) fail('APPS_HOST_SHELL_BOUND');
  const shellInputs = ['../inspector/process.mjs', 'assets/catalog-entry.mjs', 'assets/catalog-shell.html',
    'assets/catalog-shell.mjs', 'build-shell.mjs'].map(path => ({path,
      sha256: hash(resolve(here, path)), bytes: lstatSync(resolve(here, path)).size}));
  validateShellBuild(JSON.parse(readFileSync(shellReceipt)), readFileSync(shell), shellInputs);
  const work = resolve(options['--work-dir']);
  if (existsSync(work) || work.startsWith(root + '/') && !work.startsWith(resolve(root, 'target') + '/'))
    fail('APPS_HOST_WORK_DIRECTORY');
  for (let parent = dirname(work); parent !== dirname(parent); parent = dirname(parent)) {
    if (existsSync(parent) && (!lstatSync(parent).isDirectory() || lstatSync(parent).isSymbolicLink()))
      fail('APPS_HOST_WORK_ANCESTOR');
  }
  mkdirSync(work, {recursive: true, mode: 0o700});
  const privateRoot = resolve(work, 'private-session');
  mkdirSync(privateRoot, {mode: 0o700});
  const env = createEnvironment(privateRoot, java);
  for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
    mkdirSync(env[key], {recursive: true, mode: 0o700});
  const active = new Set();
  let interrupted = false;
  function managed(executable, args, extra = {}) {
    if (interrupted) fail('APPS_HOST_INTERRUPTED');
    const handle = startProcess(executable, args, {cwd: privateRoot, env,
      timeoutMs: 120000, maxOutputBytes: 2 * 1024 * 1024, ...extra});
    active.add(handle);
    void handle.completion.then(() => active.delete(handle), () => active.delete(handle));
    return handle;
  }
  const interrupt = () => { interrupted = true; for (const handle of active) void handle.stop().catch(() => {}); };
  process.on('SIGINT', interrupt);
  process.on('SIGTERM', interrupt);
  const receipt = {formatVersion: 1, profile: 'soklet.inspector.apps-render-refresh.v1',
    status: 'FAILED', stage: 'INPUTS', fullHostQualification: false, releaseCandidateEvidence: false,
    executedAt: new Date().toISOString(), protocolVersion: '2026-07-28',
    target: {name: '@modelcontextprotocol/inspector', version: '2.7.0', commit: '2e90a628e6296c62e4bef942afbb43d3faa4baf4'},
    candidate: {jarSha256: hash(jar), pomSha256: hash(pom)},
    shell: {sha256: hash(shell), receiptSha256: hash(shellReceipt), bytes: lstatSync(shell).size},
    installedTreeIdentity: installed, browserDistributionIdentity: directoryIdentity(browserRoot),
    inputs: sourceInputs(), runtime: {node: process.version, platform: process.platform, architecture: process.arch},
    fixtureShutdown: 'NOT_PROVEN', hostShutdown: 'NOT_PROVEN', browserShutdown: 'NOT_PROVEN',
    bounds: {compileMs: 120000, identityMs: 10000, fixtureMs: 120000, hostMs: 120000, browserMs: 90000,
      childOutputBytes: 2 * 1024 * 1024, gracefulExitWaitMs: 3000, fixtureEofWaitMs: 6000,
      termGraceMs: 2000, killGraceMs: 2000},
    limitations: ['Dirty-tree local development evidence, not immutable release conformance.',
      'One Inspector build, browser build, authenticated English alpha caller and Apps-enabled modern-HTTP profile only.',
      'No general CSP/permissions enforcement, localization matrix, tenant switching, revocation, OAuth, or production-host qualification.',
      'Only allowlisted structural facts retained; private browser/config/runtime state is deleted after supervised cleanup.']};
  let fixture, proxy, host, browser, cdp, observation;
  let hostExited = false, browserExited = false;
  async function command(executable, args, timeoutMs = 10000) {
    const result = await managed(executable, args, {timeoutMs}).completion;
    if (result.code !== 0 || result.signal !== null) fail('APPS_HOST_CHILD_FAILED');
    return result;
  }
  function recheckInputs() {
    verifyDependencyPins(readFileSync(resolve(dependencies, 'package.json')), readFileSync(resolve(dependencies, 'package-lock.json')));
    return hash(jar) === receipt.candidate.jarSha256 && hash(pom) === receipt.candidate.pomSha256
      && hash(shell) === receipt.shell.sha256 && hash(shellReceipt) === receipt.shell.receiptSha256
      && JSON.stringify(sourceInputs()) === JSON.stringify(receipt.inputs)
      && JSON.stringify(directoryIdentity(modules)) === JSON.stringify(installed)
      && receipt.fixtureClassTree !== undefined
      && JSON.stringify(directoryIdentity(resolve(work, 'classes'))) === JSON.stringify(receipt.fixtureClassTree)
      && JSON.stringify(directoryIdentity(browserRoot)) === JSON.stringify(receipt.browserDistributionIdentity);
  }
  try {
    receipt.runtime.java = (await command(java, ['-version'])).stderr.trim();
    receipt.coreCommit = (await command('/usr/bin/git', ['-c', `safe.directory=${root}`, '-C', root, 'rev-parse', 'HEAD'])).stdout.trim();
    const embedded = await command('/usr/bin/unzip', ['-p', jar, 'META-INF/maven/com.soklet/soklet/pom.xml']);
    if (sha(embedded.stdout) !== receipt.candidate.pomSha256) fail('APPS_HOST_POM_MISMATCH');
    const classes = resolve(work, 'classes');
    mkdirSync(classes);
    receipt.stage = 'COMPILE';
    const sources = ['AppsFixture.java', 'AppsFixtureMain.java'].map(file => resolve(here, 'src/com/soklet/interop/apps', file));
    if (sources.some(path => readFileSync(path, 'utf8').includes('com.soklet.internal'))) fail('APPS_HOST_INTERNAL_REFERENCE');
    const compile = await command(resolve(dirname(java), 'javac'), ['--release', '17', '-proc:none', '-Xlint:all', '-Werror',
      '-classpath', jar, '-d', classes, ...sources], 120000);
    if (compile.stdout !== '' || compile.stderr !== '') fail('APPS_HOST_COMPILE_OUTPUT');
    const audit = await command(resolve(dirname(java), 'jdeps'), ['-q', '--multi-release', '17', '-verbose:class', '-classpath', jar, classes], 60000);
    if (audit.stderr !== '' || audit.stdout.includes('com.soklet.internal') || audit.stdout.includes('not found')) fail('APPS_HOST_DEPENDENCY_AUDIT');
    writeFileSync(resolve(work, 'dependencies.txt'), audit.stdout, {mode: 0o600, flag: 'wx'});
    receipt.fixtureClassTree = directoryIdentity(classes);
    const token = randomBytes(32).toString('hex');
    const hostToken = randomBytes(32).toString('hex');
    receipt.stage = 'FIXTURE';
    fixture = managed(java, ['-cp', classes + delimiter + jar, 'com.soklet.interop.apps.AppsFixtureMain', shell], {stdin: 'pipe'});
    const readiness = readyLine(fixture);
    fixture.child.stdin.write(token + '\n');
    const ready = await readiness;
    receipt.authentication = await fixtureAuthentication(ready.port, token);
    proxy = await startAppsProxy({fixturePort: ready.port, token, shell: readFileSync(shell, 'utf8')});
    const config = createSessionConfig(`http://127.0.0.1:${proxy.port}/mcp`, token, {apps: true, skills: false});
    const configPath = resolve(privateRoot, 'session.json');
    const configBytes = json(config);
    writeFileSync(configPath, configBytes, {mode: 0o600, flag: 'wx'});
    receipt.config = sanitizedSessionConfig(config);
    const origin = `http://127.0.0.1:${await freePort()}`;
    receipt.stage = 'HOST';
    host = managed(process.execPath, [entry, '--web', '--config', configPath], {env: {...env,
      HOST: '127.0.0.1', CLIENT_PORT: new URL(origin).port, ALLOWED_ORIGINS: origin,
      MCP_SANDBOX_PORT: '0', MCP_APP_ORIGIN_PORT: '0', MCP_INSPECTOR_API_TOKEN: hostToken}});
    void host.completion.then(() => {hostExited = true;}, () => {hostExited = true;});
    const initial = await readyHost(origin, hostToken, () => hostExited);
    receipt.readOnlyMemoryStore = validWebConfig(initial, origin);
    receipt.hostAuthRequired = (await hostApi(origin, hostToken, false)).status === 401;
    receipt.hostOriginRestricted = (await hostApi(origin, hostToken, true, 'http://127.0.0.1:1')).status === 403;
    if (!receipt.readOnlyMemoryStore || !receipt.hostAuthRequired || !receipt.hostOriginRestricted) fail('APPS_HOST_ISOLATION');
    receipt.stage = 'BROWSER';
    const profile = resolve(privateRoot, 'chrome-profile');
    mkdirSync(profile, {mode: 0o700});
    browser = managed(browserPath, chromeArguments(profile), {timeoutMs: 90000});
    void browser.completion.then(() => {browserExited = true;}, () => {browserExited = true;});
    cdp = await connectCdp(await devToolsEndpoint(profile, () => browserExited));
    receipt.browserVersion = browserVersion(await cdp.send('Browser.getVersion'));
    const {targetId} = await cdp.send('Target.createTarget', {url: 'about:blank'});
    const {sessionId} = await cdp.send('Target.attachToTarget', {targetId, flatten: true});
    observation = await beginBrowserObservation(cdp, {sessionId, origin, sandboxUrl: initial.sandboxUrl});
    await cdp.send('Page.navigate', {url: origin}, sessionId);
    receipt.stage = 'APPS_UI';
    receipt.ui = await exerciseApps(cdp, sessionId, observation);
    const traceDeadline = Date.now() + 5000;
    while (Date.now() < traceDeadline && adjudicateAppsTrace(proxy.rows) !== 'PASSED' && !proxy.failure()) await pause();
    receipt.traceVerdict = adjudicateAppsTrace(proxy.rows);
    // Finish a genuine UI disconnect and graceful shutdown even if a trace
    // check failed; a failure still cannot become PASS below or after sealing.
    await disconnectApps(cdp, sessionId, observation);
    receipt.disconnected = true;
    receipt.stage = 'SHUTDOWN';
    await cdp.send('Browser.close');
    await cdp.close();
    const browserExit = await browser.completion;
    if (browserExit.code === 0 && browserExit.signal === null) receipt.browserShutdown = 'CLEAN';
    receipt.browser = observation.facts();
    if (observation.failure() || !receipt.browser.pageNetworkPolicySatisfied || !receipt.browser.noBrowserExceptions
        || cdp.failure() && cdp.failure() !== 'CDP_CLOSED') fail('APPS_HOST_BROWSER_CHECK');
    host.child.kill('SIGTERM');
    const hostExit = await host.completion;
    if (hostExit.code === 0 && hostExit.signal === null) receipt.hostShutdown = 'CLEAN';
    fixture.child.stdin.end();
    const fixtureExit = await fixture.completion;
    if (cleanFixtureExit(fixtureExit)) receipt.fixtureShutdown = 'CLEAN';
    receipt.configUnchanged = readFileSync(configPath, 'utf8') === configBytes;
    receipt.stage = 'INPUT_RECHECK';
    if (!receipt.configUnchanged || [receipt.fixtureShutdown, receipt.hostShutdown, receipt.browserShutdown].some(value => value !== 'CLEAN'))
      fail('APPS_HOST_SHUTDOWN');
    if (!recheckInputs()) fail('APPS_HOST_INPUT_DRIFT');
    receipt.inputsUnchanged = true;
    if (receipt.traceVerdict !== 'PASSED' || proxy.failure()) fail('APPS_HOST_TRACE');
    receipt.status = 'PASSED';
    receipt.stage = 'COMPLETE';
  } catch (error) {
    receipt.failure = /^APPS_(?:HOST|BROWSER|UI)_[A-Z_]+$/.test(error.message) ? error.message : 'APPS_HOST_PROBE_FAILED';
    receipt.traceFailure = proxy?.failure() ?? null;
    receipt.browserFailure = observation?.failure() ?? cdp?.failure() ?? null;
  } finally {
    let cdpClosed = true;
    if (browser && receipt.browserShutdown !== 'CLEAN') {
      try {
        if (cdp && !cdp.failure()) await cdp.send('Browser.close');
        const exit = await boundedExit(browser);
        if (exit.code === 0 && exit.signal === null) receipt.browserShutdown = 'CLEAN';
      } catch { /* Independent process-group fallback remains below. */ }
    }
    try { await cdp?.close(); } catch { cdpClosed = false; }
    receipt.browser = observation?.facts() ?? null;
    if (host && receipt.hostShutdown !== 'CLEAN') {
      try {
        host.child.kill('SIGTERM');
        const exit = await boundedExit(host);
        if (exit.code === 0 && exit.signal === null) receipt.hostShutdown = 'CLEAN';
      } catch { /* Independent process-group fallback remains below. */ }
    }
    // Prefer the fixture's bounded protocol shutdown even on a failed UI/trace
    // check; the process-group supervisor remains the independent fallback.
    try {
      const exit = await fixtureEof(fixture);
      if (exit && cleanFixtureExit(exit)) receipt.fixtureShutdown = 'CLEAN';
    } catch { /* The fallback stop below determines cleanup success. */ }
    const cleanup = await Promise.allSettled([browser?.stop(), host?.stop(), proxy?.close(), fixture?.stop()]);
    receipt.cleanup = {cdp: cdpClosed, browser: cleanup[0].status === 'fulfilled', host: cleanup[1].status === 'fulfilled',
      proxy: cleanup[2].status === 'fulfilled', fixture: cleanup[3].status === 'fulfilled'};
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    // Seal after all callers and the proxy have stopped. Late disconnect or
    // shutdown traffic must not escape the pre-disconnect adjudication.
    receipt.traceVerdict = adjudicateAppsTrace(proxy?.rows ?? []);
    receipt.traceFailure = proxy?.failure() ?? null;
    receipt.browserFailure = observation?.failure()
      ?? (cdp?.failure() === 'CDP_CLOSED' ? null : cdp?.failure()) ?? null;
    if (receipt.status === 'PASSED' && (receipt.traceVerdict !== 'PASSED' || receipt.traceFailure)) {
      receipt.status = 'FAILED'; receipt.failure = 'APPS_HOST_LATE_TRACE';
    }
    if (!cdpClosed || cleanup.some(row => row.status !== 'fulfilled')) {
      receipt.status = 'FAILED'; receipt.cleanupFailure = 'APPS_HOST_CLEANUP'; receipt.failure ??= 'APPS_HOST_CLEANUP';
    }
    // This exact directory was exclusively created by this invocation. No user
    // profile, credential store, shared install or retained evidence is removed.
    try { rmSync(privateRoot, {recursive: true, force: true}); }
    catch {
      receipt.status = 'FAILED'; receipt.cleanupFailure = 'APPS_HOST_PRIVATE_STATE_CLEANUP';
      receipt.failure ??= 'APPS_HOST_PRIVATE_STATE_CLEANUP';
    }
    receipt.privateStateRemoved = !existsSync(privateRoot);
    observation?.close();
    // Failed runs also need final identities: do not lose provenance simply
    // because the host raised an exception before the normal shutdown path.
    try { receipt.inputsUnchanged = recheckInputs(); }
    catch { receipt.inputsUnchanged = false; }
    if (!receipt.inputsUnchanged) {
      receipt.status = 'FAILED'; receipt.integrityFailure = 'APPS_HOST_INPUT_DRIFT';
      receipt.failure ??= 'APPS_HOST_INPUT_DRIFT';
    }
    const finalStatus = adjudicateHost(receipt, proxy?.rejections ?? []);
    receipt.renderRefresh = completedHostChecks(receipt) && !receipt.browserFailure && !receipt.cleanupFailure
      ? 'PASSED_NARROW_OBSERVATION'
      : uiChecks.every(key => receipt.ui?.[key] === true) ? 'OBSERVED_BEFORE_HOST_FAILURE' : 'NOT_QUALIFIED';
    if (finalStatus === 'BLOCKED_HOST_AUTH_FALLBACK') {
      receipt.status = finalStatus; receipt.stage = 'COMPLETE'; receipt.failure = 'APPS_HOST_OAUTH_RECOVERY_UNSUPPORTED';
    } else if (receipt.status === 'PASSED' && finalStatus !== 'PASSED') {
      receipt.status = 'FAILED'; receipt.failure = 'APPS_HOST_INCOMPLETE_EVIDENCE';
    }
    const trace = {formatVersion: 1, policy: 'STRUCTURAL_ALLOWLIST_NO_RAW_PAYLOAD_OR_BODY_HASH',
      exchanges: proxy?.rows ?? [], rejections: proxy?.rejections ?? []};
    writeFileSync(resolve(work, 'sanitized-trace.json'), json(trace), {mode: 0o600, flag: 'wx'});
    receipt.sanitizedTraceSha256 = sha(json(trace));
    writeFileSync(resolve(work, 'receipt.json'), json(receipt), {mode: 0o600, flag: 'wx'});
  }
  return receipt;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const receipt = await runHost(parseHostArguments(process.argv.slice(2)));
    console.log(JSON.stringify({status: receipt.status, stage: receipt.stage, failure: receipt.failure, fullHostQualification: false}));
    if (receipt.status !== 'PASSED') process.exitCode = receipt.status === 'BLOCKED_HOST_AUTH_FALLBACK' ? 2 : 1;
  } catch {
    console.error('Apps host runner rejected its inputs; no qualified receipt.');
    process.exitCode = 1;
  }
}
