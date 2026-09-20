#!/usr/bin/env node
import {createHash, randomBytes} from 'node:crypto';
import {existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync} from 'node:fs';
import {delimiter, dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {isDeepStrictEqual} from 'node:util';
import {createEnvironment} from '../inspector/config.mjs';
import {startProcess} from '../inspector/process.mjs';
import {directoryIdentity} from '../inspector/run.mjs';
import {regularFile, validateShellBuild} from '../apps/run.mjs';
import {validAppsResult, PROTOCOL} from '../apps/host-trace.mjs';
import {validateWorkDirectory} from '../inspector-auth-patch/run.mjs';
import {adjudicateMimeMatrix, runMimeMatrix, REQUEST_COUNT, BATCH_COUNT, MIME_FAILURES} from './matrix.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const apps = resolve(here, '../apps');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hash = path => sha(readFileSync(path));
const json = value => JSON.stringify(value, null, 2) + '\n';
const fail = code => {throw new Error(code);};
const same = isDeepStrictEqual;
export const PROFILE = 'soklet.apps.experimental-mime-boundary.v1';
export const SUCCESS = 'EXPERIMENTAL_APPS_MIME_BOUNDARY_PASSED';
const RUNNER_FAILURES = Object.freeze(['APPS_MIME_ARGUMENTS', 'APPS_MIME_PIN', 'APPS_MIME_CONTROL',
  'APPS_MIME_RESPONSE_BODY', 'APPS_MIME_RESPONSE_BOUND', 'APPS_MIME_RESPONSE_JSON', 'APPS_MIME_AUTHENTICATION',
  'APPS_MIME_AUTH_INPUT', 'APPS_MIME_INTERRUPTED', 'APPS_MIME_MATRIX_CALLBACK_BOUND',
  'APPS_MIME_MATRIX_CALLBACK_MISMATCH', 'APPS_MIME_SOURCE_SYMLINK', 'APPS_MIME_SHELL_BOUND',
  'APPS_MIME_CHILD_FAILED', 'APPS_MIME_POM_MISMATCH', 'APPS_MIME_INTERNAL_REFERENCE',
  'APPS_MIME_COMPILE_OUTPUT', 'APPS_MIME_DEPENDENCY_AUDIT', 'APPS_MIME_FIXTURE_READY_TIMEOUT',
  'APPS_MIME_CONTROL_BOUND', 'APPS_MIME_FIXTURE_EARLY_EXIT', 'APPS_MIME_FIXTURE_STOP_TIMEOUT',
  'APPS_MIME_FIXTURE_SHUTDOWN', 'APPS_MIME_INPUT_DRIFT', 'APPS_MIME_CLEANUP',
  'APPS_MIME_PRIVATE_STATE_CLEANUP', 'APPS_MIME_MATRIX_FAILED', 'APPS_MIME_INCOMPLETE_EVIDENCE',
  'APPS_MIME_RUN_FAILED']);

export function failureCode(error) {
  return [...RUNNER_FAILURES, ...MIME_FAILURES].includes(error?.message) ? error.message : 'APPS_MIME_RUN_FAILED';
}

export function parseArguments(args) {
  const keys = ['--candidate-jar', '--candidate-pom', '--java', '--shell', '--work-dir'];
  if (!Array.isArray(args) || args.length !== keys.length * 2) fail('APPS_MIME_ARGUMENTS');
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!keys.includes(args[i]) || Object.hasOwn(options, args[i])
        || typeof args[i + 1] !== 'string' || !args[i + 1]) fail('APPS_MIME_ARGUMENTS');
    options[args[i]] = args[i + 1];
  }
  return options;
}

export function validatePins(candidate, shell) {
  if (candidate?.jarSha256 !== '1782dcaa2270cb543c49abc80c942a2ff0f1ab72f9abb88a5d2556d200bd8d74'
      || shell?.sha256 !== '3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60') fail('APPS_MIME_PIN');
}

export function fixtureControl(line, event) {
  let value;
  try {value = JSON.parse(line);} catch {fail('APPS_MIME_CONTROL');}
  if (value?.format !== 1 || value.event !== event) fail('APPS_MIME_CONTROL');
  if (event === 'ready' && same(Object.keys(value).sort(), ['event', 'format', 'host', 'path', 'port'])
      && value.host === '127.0.0.1' && value.path === '/apps'
      && Number.isSafeInteger(value.port) && value.port > 0 && value.port <= 65535) return value;
  if (event === 'stopped' && same(Object.keys(value).sort(), ['clean', 'event', 'format']) && value.clean === true) return value;
  fail('APPS_MIME_CONTROL');
}

export function completedChecks(receipt) {
  return receipt?.profile === PROFILE && receipt.scope === 'candidate-direct-http' && receipt.experimental === true
    && receipt.hostQualification === false && receipt.browserExercised === false && receipt.releaseCandidateEvidence === false
    && receipt.inputPinsVerified === true && receipt.publicApiOnly === true && receipt.embeddedPomMatches === true
    && receipt.inputsUnchanged === true && receipt.privateStateRemoved === true && receipt.interrupted === false
    && receipt.matrixVerdict === 'PASSED' && receipt.matrixRequestCount === REQUEST_COUNT
    && receipt.cleanup?.processes === true && Array.isArray(receipt.fixtureRuns) && receipt.fixtureRuns.length === BATCH_COUNT
    && Array.from({length: BATCH_COUNT}, (_, index) => receipt.fixtureRuns[index]).every((run, index) => run?.batchIndex === index && run.completed === true
      && run.fixtureShutdown === 'CLEAN' && run.cleanup === true && !run.failure
      && run.matrixRows === REQUEST_COUNT / BATCH_COUNT && run.authentication?.requests === 2
      && run.authentication.invalidCredentialRejected === true && run.authentication.validCredentialAccepted === true);
}

export function adjudicateReceipt(receipt) {
  return completedChecks(receipt) && !receipt.failure && !receipt.integrityFailure && !receipt.cleanupFailure
    ? SUCCESS : 'FAILED';
}

function assertActive(signal) {if (signal?.aborted) fail('APPS_MIME_INTERRUPTED');}

export async function boundedJson(response, {signal, maximum = 65536} = {}) {
  if (!Number.isSafeInteger(maximum) || maximum < 1 || maximum > 1024 * 1024) fail('APPS_MIME_RESPONSE_BOUND');
  assertActive(signal);
  if (!response.body) fail('APPS_MIME_RESPONSE_BODY');
  const reader = response.body.getReader(), chunks = [];
  let bytes = 0;
  try {
    while (true) {
      assertActive(signal);
      const {done, value} = await reader.read();
      assertActive(signal);
      if (done) break;
      if ((bytes += value.byteLength) > maximum) {await reader.cancel(); fail('APPS_MIME_RESPONSE_BOUND');}
      chunks.push(Buffer.from(value));
    }
  } finally {reader.releaseLock();}
  try {return JSON.parse(Buffer.concat(chunks).toString('utf8'));} catch {fail('APPS_MIME_RESPONSE_JSON');}
}

export async function fixtureAuthentication({port, token, signal, fetchImpl = fetch}) {
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535
      || typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token)) fail('APPS_MIME_AUTH_INPUT');
  const facts = {requests: 0, invalidCredentialRejected: false, validCredentialAccepted: false};
  for (const authorized of [false, true]) {
    assertActive(signal);
    const body = {jsonrpc: '2.0', id: 1, method: 'server/discover', params: {_meta: {
      'io.modelcontextprotocol/protocolVersion': PROTOCOL, 'io.modelcontextprotocol/clientCapabilities': {}}}};
    const response = await fetchImpl(`http://127.0.0.1:${port}/apps`, {method: 'POST', redirect: 'error',
      signal: AbortSignal.any([AbortSignal.timeout(5000), ...(signal ? [signal] : [])]),
      headers: {'content-type': 'application/json', accept: 'application/json, text/event-stream',
        'mcp-protocol-version': PROTOCOL, 'mcp-method': 'server/discover',
        Authorization: `Bearer ${authorized ? token : 'invalid-disposable-token'}`}, body: JSON.stringify(body)});
    const result = await boundedJson(response, {signal});
    if (!/^application\/json(?:;|$)/i.test(response.headers.get('content-type') ?? '')
        || response.headers.get('cache-control') !== 'no-store' || response.headers.has('mcp-session-id'))
      fail('APPS_MIME_AUTHENTICATION');
    ++facts.requests;
    if (authorized) facts.validCredentialAccepted = response.status === 200 && !response.headers.has('www-authenticate')
      && result?.jsonrpc === '2.0' && result.id === 1 && same(Object.keys(result).sort(), ['id', 'jsonrpc', 'result'])
      && validAppsResult('server/discover', result.result);
    else facts.invalidCredentialRejected = response.status === 401 && response.headers.get('www-authenticate') === 'Bearer'
      && same(result, {jsonrpc: '2.0', id: 1, error: {code: -31901, message: 'Authentication required.'}});
    if (authorized ? !facts.validCredentialAccepted : !facts.invalidCredentialRejected) fail('APPS_MIME_AUTHENTICATION');
  }
  assertActive(signal);
  return facts;
}

// Keep failed-response rows as well as successful rows. Returned rows must match
// callbacks exactly; neither a dropped row nor an invented completion can pass.
export async function collectMatrix(options, retainedRows, execute = runMimeMatrix) {
  assertActive(options.signal);
  const first = retainedRows.length;
  const returned = await execute({...options, onRow: row => {
    if (retainedRows.length >= REQUEST_COUNT) fail('APPS_MIME_MATRIX_CALLBACK_BOUND');
    retainedRows.push(structuredClone(row));
  }});
  assertActive(options.signal);
  if (returned?.length !== REQUEST_COUNT / BATCH_COUNT || !same(returned, retainedRows.slice(first)))
    fail('APPS_MIME_MATRIX_CALLBACK_MISMATCH');
  return returned;
}

function readyLine(handle) {
  return new Promise((done, reject) => {
    let buffer = '';
    const timer = setTimeout(() => finish(new Error('APPS_MIME_FIXTURE_READY_TIMEOUT')), 10000);
    const data = bytes => {
      buffer += bytes.toString('utf8');
      if (Buffer.byteLength(buffer) > 8192) return finish(new Error('APPS_MIME_CONTROL_BOUND'));
      const index = buffer.indexOf('\n');
      if (index !== -1) {
        try {finish(null, fixtureControl(buffer.slice(0, index), 'ready'));}
        catch {finish(new Error('APPS_MIME_CONTROL'));}
      }
    };
    const close = () => finish(new Error('APPS_MIME_FIXTURE_EARLY_EXIT'));
    function finish(error, value) {
      clearTimeout(timer); handle.child.stdout.off('data', data); handle.child.off('close', close);
      if (error) reject(error); else done(value);
    }
    handle.child.stdout.on('data', data); handle.child.once('close', close);
  });
}

async function fixtureEof(handle) {
  if (!handle.child.stdin.destroyed) handle.child.stdin.end();
  let timer;
  try {return await Promise.race([handle.completion, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('APPS_MIME_FIXTURE_STOP_TIMEOUT')), 6000);
  })]);} finally {clearTimeout(timer);}
}

function cleanFixtureExit(exit) {
  try {
    const lines = exit.stdout.trimEnd().split('\n');
    return lines.length === 2 && fixtureControl(lines[0], 'ready') && fixtureControl(lines[1], 'stopped').clean
      && exit.code === 0 && exit.signal === null && exit.stderr === '';
  } catch {return false;}
}

function sourceInputs() {
  const rows = [];
  function visit(path) {
    for (const entry of readdirSync(path, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isSymbolicLink()) fail('APPS_MIME_SOURCE_SYMLINK');
      const file = resolve(path, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) rows.push({path: file.slice(root.length + 1), sha256: hash(file)});
    }
  }
  for (const directory of [here, apps, ...['inspector', 'inspector-auth-patch', 'apps-disabled-host', 'apps-patched-host']
    .map(name => resolve(here, '..', name))]) visit(directory);
  const control = resolve(here, '../run-against-public-fixture.mjs');
  rows.push({path: control.slice(root.length + 1), sha256: hash(control)});
  return rows;
}

export async function run(options) {
  const jar = regularFile(options['--candidate-jar']), pom = regularFile(options['--candidate-pom']);
  const java = regularFile(options['--java']), javac = regularFile(resolve(dirname(java), 'javac'));
  const jdeps = regularFile(resolve(dirname(java), 'jdeps'));
  const shell = regularFile(options['--shell']), shellReceipt = regularFile(shell + '.receipt.json');
  if (lstatSync(shell).size > 512 * 1024 || lstatSync(shellReceipt).size > 2 * 1024 * 1024) fail('APPS_MIME_SHELL_BOUND');
  const candidate = {jarSha256: hash(jar), pomSha256: hash(pom)};
  const shellIdentity = {sha256: hash(shell), receiptSha256: hash(shellReceipt), bytes: lstatSync(shell).size};
  validatePins(candidate, shellIdentity);
  const shellInputs = ['../inspector/process.mjs', 'assets/catalog-entry.mjs', 'assets/catalog-shell.html',
    'assets/catalog-shell.mjs', 'build-shell.mjs'].map(path => ({path, sha256: hash(resolve(apps, path)), bytes: lstatSync(resolve(apps, path)).size}));
  validateShellBuild(JSON.parse(readFileSync(shellReceipt)), readFileSync(shell), shellInputs);
  const inputs = sourceInputs();
  const executables = [java, javac, jdeps].map(path => ({name: path.split('/').at(-1), sha256: hash(path)}));
  const shellText = readFileSync(shell, 'utf8');
  const work = resolve(options['--work-dir']), privateRoot = resolve(work, 'private-session');
  validateWorkDirectory(work, [jar, pom, shell, shellReceipt, resolve(dirname(java), '..')]);
  const env = createEnvironment(privateRoot, java);
  // Preflight above is read-only. Never reuse an existing or racing work root.
  mkdirSync(work, {mode: 0o700});
  const abort = new AbortController(), active = new Set(), handles = [], rows = [];
  let interrupted = false, privateCreated = false, fixture, fixtureRun;
  function managed(executable, args, extra = {}) {
    assertActive(abort.signal);
    const handle = startProcess(executable, args, {cwd: privateRoot, env, timeoutMs: 120000,
      maxOutputBytes: 2 * 1024 * 1024, ...extra});
    active.add(handle); handles.push(handle);
    void handle.completion.then(() => active.delete(handle), () => active.delete(handle));
    return handle;
  }
  const interrupt = () => {
    interrupted = true; abort.abort();
    for (const handle of active) void handle.stop().catch(() => {});
  };
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
  const receipt = {formatVersion: 1, profile: PROFILE, scope: 'candidate-direct-http', status: 'FAILED', stage: 'INPUTS',
    experimental: true, hostQualification: false, browserExercised: false, releaseCandidateEvidence: false,
    inputPinsVerified: true, executedAt: new Date().toISOString(), protocolVersion: PROTOCOL,
    candidate, shell: shellIdentity, inputs, executableIdentities: executables, fixtureRuns: [],
    runtime: {node: process.version, platform: process.platform, architecture: process.arch},
    bounds: {compileMs: 120000, fixtureMs: 120000, dependencyAuditMs: 60000, identityMs: 10000,
      readinessMs: 10000, fixtureEofMs: 6000, termGraceMs: 2000, killGraceMs: 2000,
      childOutputBytes: 2 * 1024 * 1024, authenticationRequestMs: 5000, authenticationResponseBytes: 65536,
      fixtureBatches: BATCH_COUNT, authenticationRequestsPerBatch: 2, matrixRequests: REQUEST_COUNT,
      matrixRequestsPerBatch: REQUEST_COUNT / BATCH_COUNT, matrixRequestMs: 5000, matrixBatchMs: 60000},
    limitations: ['Direct real-HTTP requests against a pinned dirty development candidate; no browser or host was exercised.',
      'No dependency installation, host patch, host MIME-setting assumption or released-host qualification is part of this profile.',
      'Three fresh fixtures preserve the unchanged request-rate policy; each batch has a separate disposable credential.',
      'Capability negotiation is not resource authorization; authorized ordinary UI resource access remains distinct.',
      'No general CSP/permission, localization, tenant, revocation, OAuth, production or immutable release qualification.',
      'Only structural matrix rows are retained; credentials, raw payloads and private runtime state are not archived.']};
  async function command(executable, args, timeoutMs = 10000) {
    const result = await managed(executable, args, {timeoutMs}).completion;
    if (result.code !== 0 || result.signal !== null) fail('APPS_MIME_CHILD_FAILED');
    return result;
  }
  function recheckInputs() {
    return hash(jar) === candidate.jarSha256 && hash(pom) === candidate.pomSha256
      && hash(shell) === shellIdentity.sha256 && hash(shellReceipt) === shellIdentity.receiptSha256
      && same(sourceInputs(), inputs) && same([java, javac, jdeps].map(path => ({name: path.split('/').at(-1), sha256: hash(path)})), executables)
      && receipt.fixtureClassTree !== undefined && same(directoryIdentity(resolve(work, 'classes')), receipt.fixtureClassTree);
  }
  try {
    mkdirSync(privateRoot, {mode: 0o700}); privateCreated = true;
    for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
      mkdirSync(env[key], {recursive: true, mode: 0o700});
    receipt.runtime.java = (await command(java, ['-version'])).stderr.trim();
    receipt.coreCommit = (await command('/usr/bin/git', ['-c', `safe.directory=${root}`, '-C', root, 'rev-parse', 'HEAD'])).stdout.trim();
    const embedded = await command('/usr/bin/unzip', ['-p', jar, 'META-INF/maven/com.soklet/soklet/pom.xml']);
    if (sha(embedded.stdout) !== candidate.pomSha256) fail('APPS_MIME_POM_MISMATCH');
    receipt.embeddedPomMatches = true;
    receipt.stage = 'COMPILE';
    const classes = resolve(work, 'classes'); mkdirSync(classes);
    const sources = ['AppsFixture.java', 'AppsFixtureMain.java'].map(name => resolve(apps, 'src/com/soklet/interop/apps', name));
    if (sources.some(path => readFileSync(path, 'utf8').includes('com.soklet.internal'))) fail('APPS_MIME_INTERNAL_REFERENCE');
    const compile = await command(javac, ['--release', '17', '-proc:none', '-Xlint:all', '-Werror', '-classpath', jar, '-d', classes, ...sources], 120000);
    if (compile.stdout !== '' || compile.stderr !== '') fail('APPS_MIME_COMPILE_OUTPUT');
    const audit = await command(jdeps, ['-q', '--multi-release', '17', '-verbose:class', '-classpath', jar, classes], 60000);
    if (audit.stderr !== '' || audit.stdout.includes('com.soklet.internal') || audit.stdout.includes('not found')) fail('APPS_MIME_DEPENDENCY_AUDIT');
    writeFileSync(resolve(work, 'dependencies.txt'), audit.stdout, {mode: 0o600, flag: 'wx'});
    receipt.publicApiOnly = true; receipt.fixtureClassTree = directoryIdentity(classes);
    for (let batchIndex = 0; batchIndex < BATCH_COUNT; ++batchIndex) {
      assertActive(abort.signal);
      receipt.stage = 'FIXTURE';
      fixtureRun = {batchIndex, completed: false, fixtureShutdown: 'NOT_PROVEN', cleanup: false, matrixRows: 0};
      receipt.fixtureRuns.push(fixtureRun);
      const token = randomBytes(32).toString('hex');
      fixture = managed(java, ['-cp', classes + delimiter + jar, 'com.soklet.interop.apps.AppsFixtureMain', shell], {stdin: 'pipe'});
      const readiness = readyLine(fixture); fixture.child.stdin.write(token + '\n');
      const ready = await readiness;
      fixtureRun.authentication = await fixtureAuthentication({port: ready.port, token, signal: abort.signal});
      receipt.stage = 'MATRIX';
      const first = rows.length;
      try {await collectMatrix({batchIndex, port: ready.port, token, shell: shellText, signal: abort.signal}, rows);}
      finally {fixtureRun.matrixRows = rows.length - first;}
      receipt.stage = 'SHUTDOWN';
      const exit = await fixtureEof(fixture);
      if (!cleanFixtureExit(exit)) fail('APPS_MIME_FIXTURE_SHUTDOWN');
      fixtureRun.fixtureShutdown = 'CLEAN';
      await fixture.stop(); fixtureRun.cleanup = true;
      assertActive(abort.signal);
      fixtureRun.completed = true; fixture = undefined; fixtureRun = undefined;
    }
    receipt.matrixVerdict = adjudicateMimeMatrix(rows);
    if (receipt.matrixVerdict !== 'PASSED') fail('APPS_MIME_MATRIX_FAILED');
    receipt.stage = 'INPUT_RECHECK';
    if (!recheckInputs()) fail('APPS_MIME_INPUT_DRIFT');
    receipt.inputsUnchanged = true;
  } catch (error) {
    receipt.failure = failureCode(error);
    if (fixtureRun) fixtureRun.failure = receipt.failure;
  } finally {
    if (fixture) {
      try {if (cleanFixtureExit(await fixtureEof(fixture))) fixtureRun.fixtureShutdown = 'CLEAN';} catch { /* Supervised fallback below. */ }
      try {await fixture.stop(); fixtureRun.cleanup = true;} catch {fixtureRun.cleanup = false;}
    }
    const cleanup = await Promise.allSettled(handles.map(handle => handle.stop()));
    receipt.cleanup = {processes: cleanup.every(item => item.status === 'fulfilled')};
    if (!receipt.cleanup.processes) receipt.cleanupFailure = 'APPS_MIME_CLEANUP';
    try {if (privateCreated) rmSync(privateRoot, {recursive: true, force: true});}
    catch {receipt.cleanupFailure = 'APPS_MIME_PRIVATE_STATE_CLEANUP';}
    receipt.privateStateRemoved = !existsSync(privateRoot);
    try {receipt.inputsUnchanged = recheckInputs();} catch {receipt.inputsUnchanged = false;}
    if (!receipt.inputsUnchanged) receipt.integrityFailure = 'APPS_MIME_INPUT_DRIFT';
    receipt.matrixVerdict = adjudicateMimeMatrix(rows); receipt.matrixRequestCount = rows.length;
    // Deliver any signal queued during the synchronous final identity census
    // before deciding the receipt status, while interruption listeners remain.
    await new Promise(done => setImmediate(done));
    receipt.interrupted = interrupted;
    if (interrupted) receipt.failure = 'APPS_MIME_INTERRUPTED';
    receipt.status = adjudicateReceipt(receipt);
    if (receipt.status === SUCCESS) receipt.stage = 'COMPLETE';
    else receipt.failure ??= 'APPS_MIME_INCOMPLETE_EVIDENCE';
    const trace = {formatVersion: 1, scope: 'candidate-direct-http', policy: 'STRUCTURAL_ALLOWLIST_NO_RAW_PAYLOAD_OR_BODY_HASH', exchanges: rows};
    try {
      writeFileSync(resolve(work, 'sanitized-trace.json'), json(trace), {mode: 0o600, flag: 'wx'});
      receipt.sanitizedTraceSha256 = sha(json(trace));
      writeFileSync(resolve(work, 'receipt.json'), json(receipt), {mode: 0o600, flag: 'wx'});
    } finally {process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt);}
  }
  return receipt;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const receipt = await run(parseArguments(process.argv.slice(2)));
    console.log(JSON.stringify({status: receipt.status, stage: receipt.stage, failure: receipt.failure,
      scope: receipt.scope, hostQualification: false, browserExercised: false}));
    if (receipt.status !== SUCCESS) process.exitCode = 1;
  } catch {
    console.error('Apps MIME boundary runner rejected its inputs; no qualified receipt.'); process.exitCode = 1;
  }
}
