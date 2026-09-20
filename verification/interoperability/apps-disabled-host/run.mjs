#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment, createSessionConfig, sanitizedSessionConfig } from '../inspector/config.mjs';
import { startProcess } from '../inspector/process.mjs';
import { directoryIdentity } from '../inspector/run.mjs';
import { browserVersion, chromeArguments, validWebConfig } from '../inspector/web-probe.mjs';
import { connectCdp } from '../inspector/cdp.mjs';
import { regularFile, validateShellBuild } from '../apps/run.mjs';
import { startAppsDisabledProxy as startAppsProxy, adjudicateAppsDisabledTrace as adjudicateAppsTrace } from './trace.mjs';
import { beginBrowserObservation, exerciseAppsDisabled, assertAppsDisabled, disconnectApps } from './browser-probe.mjs';
import { runDirectControls, adjudicateDirectControls } from './direct-controls.mjs';
import { verifyPatchedDependencies } from '../inspector-auth-patch/patch.mjs';
import { validateWorkDirectory } from '../inspector-auth-patch/run.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const apps = resolve(here, '../apps');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hash = path => sha(readFileSync(path));
const json = value => JSON.stringify(value, null, 2) + '\n';
const fail = code => { throw new Error(code); };
const pause = () => new Promise(done => setTimeout(done, 50));
const uiChecks = ['connectedViaDom','toolsSelectedViaDom','ordinaryToolSelectedViaDom',
  'ordinaryToolCalledViaDom','ordinaryResultRendered','appsControlsAbsent','appOnlyHelperAbsent','noAppFrames'];
export const PROFILE = 'soklet.inspector.experimental-apps-disabled.v1';
export const SUCCESS = 'EXPERIMENTAL_APPS_DISABLED_PASSED';
export function validateExperimentPins(provenance, candidate, shell) {
  if (provenance?.patchedTree?.files !== 9391
      || provenance.patchedTree.sha256 !== '6546d769cd9fd869b7608c774b9dcfc39b3050d851c57ad83b439cdcbb84ebcb'
      || provenance.patchedFileSha256 !== '405da5e71b887403bb53ff2e3984cec631a1138f50662ad199dfb8e536dcd47a'
      || provenance.experimental !== true || provenance.releasedHostQualification !== false
      || candidate?.jarSha256 !== '1782dcaa2270cb543c49abc80c942a2ff0f1ab72f9abb88a5d2556d200bd8d74'
      || shell?.sha256 !== '3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60')
    fail('APPS_HOST_EXPERIMENT_PIN');
}

export function parseHostArguments(args) {
  const keys = ['--candidate-jar', '--candidate-pom', '--java', '--shell', '--original-dependencies', '--dependencies', '--browser', '--work-dir'];
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
  return receipt.profile === PROFILE && receipt.experimental === true && receipt.patchIdentityVerified === true
    && receipt.fullHostQualification === false && receipt.releaseCandidateEvidence === false
    && receipt.observationWindowCompleted === true
    && receipt.interrupted === false
    && receipt.appsAbsentThroughoutObservation === true
    && adjudicateDirectControls(receipt.directControls) === 'PASSED'
    && receipt.traceVerdict === 'PASSED' && uiChecks.every(key => receipt.ui?.[key] === true)
    && receipt.browser?.pageNetworkPolicySatisfied === true && receipt.browser?.noBrowserExceptions === true
    && receipt.authentication?.invalidCredentialRejected === true && receipt.authentication?.validCredentialAccepted === true
    && ['fixtureShutdown', 'hostShutdown', 'browserShutdown'].every(key => receipt[key] === 'CLEAN')
    && ['readOnlyMemoryStore', 'hostAuthRequired', 'hostOriginRestricted', 'configUnchanged',
      'inputsUnchanged', 'disconnected', 'privateStateRemoved'].every(key => receipt[key] === true)
    && ['cdp', 'browser', 'host', 'proxy', 'fixture'].every(key => receipt.cleanup?.[key] === true);
}

/** A passing local experiment is never a released-host qualification. */
export function adjudicateHost(receipt, rejections) {
  if (!completedHostChecks(receipt) || receipt.failure || receipt.integrityFailure || receipt.browserFailure || receipt.cleanupFailure
      || !Array.isArray(rejections)) return 'FAILED';
  return !receipt.traceFailure && rejections.length === 0 ? SUCCESS : 'FAILED';
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
      'io.modelcontextprotocol/clientCapabilities': {}
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
  visit(apps);
  visit(resolve(here, '../inspector'));
  visit(resolve(here, '../inspector-auth-patch'));
  visit(resolve(here, '../apps-patched-host'));
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
  const original = resolve(options['--original-dependencies']);
  const modules = resolve(dependencies, 'node_modules');
  const inspector = resolve(modules, '@modelcontextprotocol/inspector');
  const entry = regularFile(resolve(inspector, 'clients/launcher/build/index.js'));
  regularFile(resolve(inspector, 'clients/web/dist/index.html'));
  const provenance = verifyPatchedDependencies(original, dependencies);
  const installed = provenance.patchedTree;
  validateExperimentPins(provenance, {jarSha256: hash(jar)}, {sha256: hash(shell)});
  if (lstatSync(shell).size > 512 * 1024 || lstatSync(shellReceipt).size > 2 * 1024 * 1024) fail('APPS_HOST_SHELL_BOUND');
  const shellInputs = ['../inspector/process.mjs', 'assets/catalog-entry.mjs', 'assets/catalog-shell.html',
    'assets/catalog-shell.mjs', 'build-shell.mjs'].map(path => ({path,
      sha256: hash(resolve(apps, path)), bytes: lstatSync(resolve(apps, path)).size}));
  validateShellBuild(JSON.parse(readFileSync(shellReceipt)), readFileSync(shell), shellInputs);
  // All fallible input snapshots precede creation of private state/listeners.
  const candidateIdentity = {jarSha256: hash(jar), pomSha256: hash(pom)};
  const shellIdentity = {sha256: hash(shell), receiptSha256: hash(shellReceipt), bytes: lstatSync(shell).size};
  const browserIdentity = directoryIdentity(browserRoot);
  const inputSnapshot = sourceInputs();
  const work = resolve(options['--work-dir']);
  validateWorkDirectory(work, [original, dependencies, browserRoot, resolve(dirname(java), '..')]);
  const privateRoot = resolve(work, 'private-session');
  const env = createEnvironment(privateRoot, java);
  // The parent must exist, and an existing/racing output directory is never reused.
  mkdirSync(work, {mode: 0o700});
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
  const receipt = {formatVersion: 1, profile: PROFILE,
    status: 'FAILED', stage: 'INPUTS', experimental: true, patchIdentityVerified: true, provenance,
    fullHostQualification: false, releaseCandidateEvidence: false,
    executedAt: new Date().toISOString(), protocolVersion: '2026-07-28',
    target: {name: '@modelcontextprotocol/inspector', version: '2.7.0', commit: '2e90a628e6296c62e4bef942afbb43d3faa4baf4'},
    candidate: candidateIdentity, shell: shellIdentity,
    installedTreeIdentity: installed, browserDistributionIdentity: browserIdentity,
    inputs: inputSnapshot, runtime: {node: process.version, platform: process.platform, architecture: process.arch},
    fixtureShutdown: 'NOT_PROVEN', hostShutdown: 'NOT_PROVEN', browserShutdown: 'NOT_PROVEN',
    bounds: {compileMs: 120000, identityMs: 10000, fixtureMs: 120000, hostMs: 120000, browserMs: 90000,
      childOutputBytes: 2 * 1024 * 1024, gracefulExitWaitMs: 3000, fixtureEofWaitMs: 6000,
      termGraceMs: 2000, killGraceMs: 2000, postToolObservationMs: 6000, deniedSubscriptions: 8, mcpExchanges: 14,
      directCapabilityRequests: 4, directRequestMs: 5000, directResponseBytes: 1024 * 1024},
    limitations: ['Dirty-tree local development evidence, not immutable release conformance.',
      'One Inspector build, browser build, authenticated English alpha caller and Apps-disabled modern-HTTP profile only.',
      'Apps capability negotiation is not resource authorization; an ordinary authorized resource read remains allowed.',
      'Four direct OFF/ON/OFF/helper/resource controls are separate from actual browser-originated evidence.',
      'An exact isolated auth-patched host, not the unchanged released Inspector; historical FAILED host evidence remains unchanged.',
      'One to eight exact denied subscription retries are observed, never authorized or converted to success.',
      'No general CSP/permissions enforcement, localization matrix, tenant switching, revocation, OAuth, or production-host qualification.',
      'Only allowlisted structural facts retained; private browser/config/runtime state is deleted after supervised cleanup.']};
  let fixture, proxy, host, browser, cdp, observation, configPath, configBytes, privateCreated = false;
  let hostExited = false, browserExited = false;
  async function command(executable, args, timeoutMs = 10000) {
    const result = await managed(executable, args, {timeoutMs}).completion;
    if (result.code !== 0 || result.signal !== null) fail('APPS_HOST_CHILD_FAILED');
    return result;
  }
  function recheckInputs() {
    return hash(jar) === receipt.candidate.jarSha256 && hash(pom) === receipt.candidate.pomSha256
      && hash(shell) === receipt.shell.sha256 && hash(shellReceipt) === receipt.shell.receiptSha256
      && JSON.stringify(sourceInputs()) === JSON.stringify(receipt.inputs)
      && JSON.stringify(verifyPatchedDependencies(original, dependencies)) === JSON.stringify(provenance)
      && receipt.fixtureClassTree !== undefined
      && JSON.stringify(directoryIdentity(resolve(work, 'classes'))) === JSON.stringify(receipt.fixtureClassTree)
      && JSON.stringify(directoryIdentity(browserRoot)) === JSON.stringify(receipt.browserDistributionIdentity);
  }
  try {
    mkdirSync(privateRoot, {mode: 0o700});
    privateCreated = true;
    for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
      mkdirSync(env[key], {recursive: true, mode: 0o700});
    receipt.runtime.java = (await command(java, ['-version'])).stderr.trim();
    receipt.coreCommit = (await command('/usr/bin/git', ['-c', `safe.directory=${root}`, '-C', root, 'rev-parse', 'HEAD'])).stdout.trim();
    const embedded = await command('/usr/bin/unzip', ['-p', jar, 'META-INF/maven/com.soklet/soklet/pom.xml']);
    if (sha(embedded.stdout) !== receipt.candidate.pomSha256) fail('APPS_HOST_POM_MISMATCH');
    const classes = resolve(work, 'classes');
    mkdirSync(classes);
    receipt.stage = 'COMPILE';
    const sources = ['AppsFixture.java', 'AppsFixtureMain.java'].map(file => resolve(apps, 'src/com/soklet/interop/apps', file));
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
    receipt.directControls = await runDirectControls({port:ready.port,token,shell:readFileSync(shell,'utf8')});
    proxy = await startAppsProxy({fixturePort: ready.port, token, shell: readFileSync(shell, 'utf8')});
    const config = createSessionConfig(`http://127.0.0.1:${proxy.port}/mcp`, token, {apps: false, skills: false});
    configPath = resolve(privateRoot, 'session.json');
    configBytes = json(config);
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
    receipt.stage = 'ORDINARY_TOOL_UI';
    receipt.ui = await exerciseAppsDisabled(cdp, sessionId, observation);
    receipt.stage = 'OBSERVE';
    const observeUntil = Date.now() + 6000;
    while (Date.now() < observeUntil) {
      if (interrupted || hostExited || browserExited || cdp.failure() || proxy.failure() || observation.failure())
        fail('APPS_HOST_OBSERVATION_INTERRUPTED');
      if (!await assertAppsDisabled(cdp, sessionId, observation)) fail('APPS_HOST_UNEXPECTED_APP_UI');
      await pause();
    }
    receipt.observationWindowCompleted = true;
    receipt.appsAbsentThroughoutObservation = true;
    receipt.traceVerdict = adjudicateAppsTrace(proxy.rows);
    // Finish a genuine UI disconnect and graceful shutdown even if a trace
    // check failed; a failure still cannot become PASS below or after sealing.
    await disconnectApps(cdp, sessionId, observation);
    receipt.disconnected = true;
    receipt.stage = 'SHUTDOWN';
    observation.beginClosing();
    await cdp.send('Browser.close');
    await cdp.close();
    const browserExit = await boundedExit(browser);
    if (browserExit.code === 0 && browserExit.signal === null) receipt.browserShutdown = 'CLEAN';
    receipt.browser = observation.facts();
    if (observation.failure() || !receipt.browser.pageNetworkPolicySatisfied || !receipt.browser.noBrowserExceptions
        || cdp.failure() && cdp.failure() !== 'CDP_CLOSED') fail('APPS_HOST_BROWSER_CHECK');
    host.child.kill('SIGTERM');
    const hostExit = await boundedExit(host);
    if (hostExit.code === 0 && hostExit.signal === null) receipt.hostShutdown = 'CLEAN';
    const fixtureExit = await fixtureEof(fixture);
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
        if (cdp && !cdp.failure()) {
          observation?.beginClosing();
          await cdp.send('Browser.close');
          await cdp.close();
        }
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
    try { receipt.configUnchanged = !!configPath && readFileSync(configPath, 'utf8') === configBytes; }
    catch { receipt.configUnchanged = false; }
    // This exact directory was exclusively created by this invocation. No user
    // profile, credential store, shared install or retained evidence is removed.
    try { if (privateCreated) rmSync(privateRoot, {recursive: true, force: true}); }
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
    receipt.interrupted = interrupted;
    if (interrupted) receipt.failure = 'APPS_HOST_INTERRUPTED';
    const finalStatus = adjudicateHost(receipt, proxy?.rejections ?? []);
    receipt.appsDisabled = finalStatus === SUCCESS
      ? 'EXPERIMENTAL_NEGATIVE_CAPABILITY_PASSED'
      : uiChecks.every(key => receipt.ui?.[key] === true) ? 'OBSERVED_BEFORE_HOST_FAILURE' : 'NOT_QUALIFIED';
    receipt.status = finalStatus;
    if (finalStatus === SUCCESS) receipt.stage = 'COMPLETE';
    else receipt.failure ??= 'APPS_HOST_INCOMPLETE_EVIDENCE';
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
    if (receipt.status !== SUCCESS) process.exitCode = 1;
  } catch {
    console.error('Apps host runner rejected its inputs; no qualified receipt.');
    process.exitCode = 1;
  }
}
