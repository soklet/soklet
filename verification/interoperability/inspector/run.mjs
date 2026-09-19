#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync,
  readlinkSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment, createSessionConfig, inspectorArguments, sanitizedSessionConfig } from './config.mjs';
import { startProcess } from './process.mjs';
import { adjudicateTrace, startProxy, validResult, PROTOCOL } from './trace.mjs';
import { parseControl } from '../run-against-public-fixture.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const PACKAGE_SHA = '8fc642c9ec63e951bf987d4ccf2c7a198a33f350f7d424ad2e95a1f48dbfe2c0';
const LOCK_SHA = '37dfcdb1476f7f31c4aeea13971a729588bad7719c67bb8349f7bd8c52d29f29';
const INSPECTOR_INTEGRITY = 'sha512-V1SqfR+m3NWMkEe2i3v2GMm00pZtAl/JISAHQzYnOZElEuwuLLkU8vTWBYg5HMCYTgpvuMmzb+MZz98HjdcGuw==';
const FIXTURE_WARNING = 'LifecycleObserver::didReceiveLogEvent [MCP_SERVER_CONFIGURATION]: No admission controller is configured for the MCP server; every request and notification will be admitted as anonymous.\n';
const MEMORY_STORE_CAVEAT = '[mcp-inspector] Secrets are not written anywhere and are lost on exit.\n';
const fixtureMain = 'com.soklet.conformance.McpConformanceFixture';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hashFile = path => sha(readFileSync(path));
const json = value => `${JSON.stringify(value, null, 2)}\n`;
const fail = code => { throw new Error(code); };
const activeProcesses = new Set();
let interrupted = false;

function managed(command, args, options) {
  if (interrupted) fail('HARNESS_INTERRUPTED');
  const handle = startProcess(command, args, options);
  activeProcesses.add(handle);
  void handle.completion.then(() => activeProcesses.delete(handle),
    () => activeProcesses.delete(handle));
  return handle;
}

function regularFile(path) {
  const absolute = resolve(path);
  if (!lstatSync(absolute).isFile() || lstatSync(absolute).isSymbolicLink()) fail('INPUT_FILE_INVALID');
  return realpathSync(absolute);
}

export function verifyDependencyPins(packageBytes, lockBytes) {
  if (sha(packageBytes) !== PACKAGE_SHA || sha(lockBytes) !== LOCK_SHA) fail('DEPENDENCY_PIN_DRIFT');
  const lock = JSON.parse(lockBytes);
  if (lock.lockfileVersion !== 3
      || lock.packages['node_modules/@modelcontextprotocol/inspector']?.integrity !== INSPECTOR_INTEGRITY)
    fail('DEPENDENCY_IDENTITY_INVALID');
  for (const [name, value] of Object.entries(lock.packages)) {
    if (name !== '' && (!value.resolved?.startsWith('https://registry.npmjs.org/')
        || !/^sha512-[A-Za-z0-9+/]+=*$/.test(value.integrity))) fail('DEPENDENCY_SOURCE_INVALID');
  }
}

export function directoryIdentity(directory) {
  if (!lstatSync(directory).isDirectory() || lstatSync(directory).isSymbolicLink())
    fail('INSTALLED_ROOT_INVALID');
  const rows = [];
  function visit(path) {
    for (const name of readdirSync(path).sort()) {
      const absolute = resolve(path, name);
      const entry = lstatSync(absolute);
      const key = relative(directory, absolute).split(sep).join('/');
      if (entry.isDirectory()) visit(absolute);
      else if (entry.isFile()) rows.push([key, 'file', hashFile(absolute)]);
      else if (entry.isSymbolicLink()) {
        const target = readlinkSync(absolute);
        const relativeTarget = relative(directory, resolve(dirname(absolute), target));
        if (isAbsolute(target) || relativeTarget === '..' || relativeTarget.startsWith(`..${sep}`))
          fail('INSTALLED_SYMLINK_ESCAPE');
        rows.push([key, 'link', target]);
      } else fail('INSTALLED_ENTRY_INVALID');
    }
  }
  visit(directory);
  return { files: rows.length, sha256: sha(json(rows)) };
}

async function command(command, args, cwd, env, timeoutMs = 30000) {
  const result = await managed(command, args, { cwd, env, timeoutMs, maxOutputBytes: 8 * 1024 * 1024 }).completion;
  if (result.code !== 0 || result.signal !== null) fail('COMMAND_FAILED');
  return result;
}

async function sourceIdentity(env) {
  const gitOptions = ['-c', `safe.directory=${root}`];
  const listing = await command('/usr/bin/git', [...gitOptions, 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], root, env);
  const paths = [...new Set(listing.stdout.split('\0').filter(Boolean))].sort();
  const rows = paths.map(path => [path, hashFile(regularFile(resolve(root, path)))]);
  const head = await command('/usr/bin/git', [...gitOptions, 'rev-parse', 'HEAD'], root, env);
  const state = await command('/usr/bin/git', [...gitOptions, 'status', '--porcelain'], root, env);
  return { kind: 'TRACKED_AND_UNIGNORED_WORKING_TREE', head: head.stdout.trim(),
    dirty: state.stdout !== '', files: paths.length, sha256: sha(json(rows)) };
}

function makeIsolation(path, java) {
  mkdirSync(path, { recursive: false, mode: 0o700 });
  const env = createEnvironment(path, java);
  for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
    mkdirSync(env[key], { recursive: true, mode: 0o700 });
  return env;
}

function readyLine(processHandle) {
  return new Promise((resolveReady, reject) => {
    let buffer = '';
    const timer = setTimeout(() => finish(new Error('FIXTURE_READY_TIMEOUT')), 10000);
    const onData = bytes => {
      buffer += bytes.toString('utf8');
      if (Buffer.byteLength(buffer) > 8192) return finish(new Error('FIXTURE_CONTROL_BOUND'));
      const end = buffer.indexOf('\n');
      if (end !== -1) {
        try { finish(null, parseControl(buffer.slice(0, end), 'ready')); }
        catch { finish(new Error('FIXTURE_CONTROL_INVALID')); }
      }
    };
    const onClose = () => finish(new Error('FIXTURE_EARLY_EXIT'));
    function finish(error, value) {
      clearTimeout(timer);
      processHandle.child.stdout.off('data', onData);
      processHandle.child.off('close', onClose);
      if (error) reject(error); else resolveReady(value);
    }
    processHandle.child.stdout.on('data', onData);
    processHandle.child.once('close', onClose);
  });
}

export function validateCliOutput(stdout, stderr, operation) {
  // The pinned memory store emits this one fixed caveat. It is accounted for,
  // not a general permission for arbitrary stderr or credential diagnostics.
  if (stderr !== MEMORY_STORE_CAVEAT) return false;
  try {
    const output = JSON.parse(stdout);
    if (Object.keys(output).length !== 1 || !Object.hasOwn(output, 'result')) return false;
    if (operation === 'tools/list') {
      return Object.keys(output.result).length === 1 && Object.hasOwn(output.result, 'tools')
        && validResult(operation, { ...output.result, resultType: 'complete' });
    }
    return operation === 'tools/call'
      && JSON.stringify(Object.keys(output.result).sort()) === JSON.stringify(['_meta', 'content'])
      && output.result._meta !== null && typeof output.result._meta === 'object'
      && !Array.isArray(output.result._meta)
      && validResult(operation, { ...output.result, resultType: 'complete' });
  } catch { return false; }
}

async function probe({ java, classpath, entry, work, enabled, operation }) {
  const name = `${enabled ? 'enabled' : 'disabled'}-${operation.split('/')[1]}`;
  const sessionRoot = resolve(work, `private-${name}`);
  const env = makeIsolation(sessionRoot, java);
  const token = randomBytes(32).toString('hex');
  let fixture;
  let proxy;
  let client;
  const record = { name, requestedExtensions: enabled ? 'ENABLED' : 'DISABLED', operation,
    status: 'FAILED', baseTransport: 'NOT_RUN', cliOutputMatches: false,
    fixtureShutdown: 'NOT_PROVEN', configUnchanged: false, trace: [] };
  try {
    fixture = managed(java, ['-cp', classpath, fixtureMain, '--scenario', 'tools-list'],
      { cwd: root, env, timeoutMs: 60000, stdin: 'pipe' });
    void fixture.completion.catch(() => {});
    const ready = await readyLine(fixture);
    if (ready.host !== '127.0.0.1' || ready.path !== '/mcp'
        || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535) fail('FIXTURE_ADDRESS_INVALID');
    proxy = await startProxy({ fixturePort: ready.port, token, enabled });
    const config = createSessionConfig(`http://127.0.0.1:${proxy.port}/mcp`, token, enabled);
    const configPath = resolve(sessionRoot, 'session.json');
    const configBytes = json(config);
    writeFileSync(configPath, configBytes, { mode: 0o600, flag: 'wx' });
    record.config = sanitizedSessionConfig(config);
    client = managed(process.execPath, inspectorArguments(entry, configPath, operation),
      { cwd: sessionRoot, env, timeoutMs: 30000 });
    const exit = await client.completion;
    record.cliExit = { code: exit.code, signal: exit.signal };
    record.cliOutputMatches = validateCliOutput(exit.stdout, exit.stderr, operation);
    record.memoryStoreCaveatObserved = exit.stderr === MEMORY_STORE_CAVEAT;
    record.configUnchanged = readFileSync(configPath, 'utf8') === configBytes;
    record.trace = proxy.rows;
    fixture.child.stdin.end();
    const fixtureExit = await fixture.completion;
    const lines = fixtureExit.stdout.trimEnd().split('\n');
    const stopped = lines.length === 2 && parseControl(lines[1], 'stopped');
    if (fixtureExit.code === 0 && fixtureExit.signal === null && stopped?.clean === true
        && fixtureExit.stderr === FIXTURE_WARNING) record.fixtureShutdown = 'CLEAN';
    record.fixtureAnonymousAdmissionWarning = fixtureExit.stderr === FIXTURE_WARNING;
    if (exit.code !== 0 || exit.signal !== null || !record.cliOutputMatches
        || !record.configUnchanged || record.fixtureShutdown !== 'CLEAN' || proxy.failure()) fail('PROBE_CHECK_FAILED');
    record.status = adjudicateTrace(record.trace, enabled, operation);
    if (record.status !== 'FAILED') record.baseTransport = 'PASSED';
  } catch {
    // Never emit arbitrary upstream exception messages, stdout, or stderr.
    record.failure = proxy?.failure() ?? 'PROBE_FAILED';
    record.trace = proxy?.rows ?? [];
  } finally {
    try {
      const cleanup = await Promise.allSettled([
        client?.stop(), proxy?.close(), fixture?.stop(),
      ]);
      if (cleanup.some(result => result.status === 'rejected')) {
        record.status = 'FAILED';
        record.failure = 'PROBE_CLEANUP_FAILED';
      }
    } finally {
      // Exactly the newly created private session, never a user's host state.
      rmSync(sessionRoot, { recursive: true, force: true });
    }
  }
  return record;
}

export async function runHarness({ candidateJar, candidatePom, java, workDirectory }) {
  const jar = regularFile(candidateJar);
  const pom = regularFile(candidatePom);
  const javaPath = regularFile(java);
  const work = resolve(workDirectory);
  const outputParent = resolve(root, 'target/inspector');
  if (!work.startsWith(`${outputParent}${sep}`) || existsSync(work)) fail('WORK_DIRECTORY_MUST_BE_FRESH');
  for (let parent = dirname(work); parent !== root; parent = dirname(parent)) {
    if (existsSync(parent) && (!lstatSync(parent).isDirectory() || lstatSync(parent).isSymbolicLink()))
      fail('WORK_DIRECTORY_ANCESTOR_INVALID');
  }
  if (process.versions.node !== '26.5.0') fail('NODE_VERSION_MISMATCH');
  const packageBytes = readFileSync(resolve(here, 'package.json'));
  const lockBytes = readFileSync(resolve(here, 'package-lock.json'));
  verifyDependencyPins(packageBytes, lockBytes);
  mkdirSync(work, { recursive: true, mode: 0o700 });
  const env = makeIsolation(resolve(work, 'build-isolation'), javaPath);
  const receipt = { formatVersion: 1, evidenceClass: 'LOCAL_HOST_HARNESS_DEVELOPMENT_ONLY', stage: 'INPUTS',
    status: 'FAILED', releaseCandidateEvidence: false, sourceTreeIdentity: null,
    candidateJarSha256: hashFile(jar), candidatePomSha256: hashFile(pom),
    harnessId: 'soklet.inspector.base-transport.v1', profileId: 'modern-cli-extension-toggles.v1',
    targetName: '@modelcontextprotocol/inspector', targetVersion: '2.7.0',
    targetCommit: '2e90a628e6296c62e4bef942afbb43d3faa4baf4',
    artifactIntegrity: INSPECTOR_INTEGRITY, packageLockSha256: LOCK_SHA,
    protocolVersion: PROTOCOL, executedAt: new Date().toISOString(),
    appsRendering: 'NOT_RUN', skillsRetrieval: 'NOT_RUN', browserVersion: null,
    limitations: ['Dirty-tree development evidence is not a release receipt.',
      'The unchanged CLI has no renderer; Apps rendering, CSP and permissions are not tested.',
      'The current fixture has no Apps or Skills registrations; core fallback only is exercised.',
      'Disposable bearer authorization terminates at the loopback proxy, not at Soklet.',
      'No OAuth, production host, localization, tenant isolation, or Skills activation is qualified.',
      'Requested extension settings are verified on the wire; a disabled-row mismatch stays blocked.'],
    runs: [] };
  const persist = () => writeFileSync(resolve(work, 'receipt.json'), json(receipt), { mode: 0o600 });
  const onSignal = () => {
    interrupted = true;
    for (const handle of activeProcesses) void handle.stop().catch(() => {});
  };
  process.on('SIGINT', onSignal);
  process.on('SIGTERM', onSignal);
  persist();
  try {
    receipt.sourceTreeIdentity = await sourceIdentity(env);
    const npmVersion = await command('npm', ['--version'], work, env);
    if (npmVersion.stdout.trim() !== '11.17.0') fail('NPM_VERSION_MISMATCH');
    const javaVersion = await command(javaPath, ['-version'], work, env);
    receipt.runtime = { node: process.versions.node, npm: npmVersion.stdout.trim(),
      java: javaVersion.stderr.trim(), platform: process.platform, architecture: process.arch };
    const embeddedPom = await command('/usr/bin/unzip', ['-p', jar,
      'META-INF/maven/com.soklet/soklet/pom.xml'], work, env);
    if (sha(embeddedPom.stdout) !== receipt.candidatePomSha256) fail('CANDIDATE_POM_MISMATCH');
    const install = resolve(work, 'installed');
    mkdirSync(install, { mode: 0o700 });
    copyFileSync(resolve(here, 'package.json'), resolve(install, 'package.json'));
    copyFileSync(resolve(here, 'package-lock.json'), resolve(install, 'package-lock.json'));
    const npmUserConfig = resolve(work, 'build-isolation/npm-user.npmrc');
    const npmGlobalConfig = resolve(work, 'build-isolation/npm-global.npmrc');
    writeFileSync(npmUserConfig, '', { mode: 0o600, flag: 'wx' });
    writeFileSync(npmGlobalConfig, '', { mode: 0o600, flag: 'wx' });
    const installEnv = { ...env, npm_config_cache: resolve(work, 'npm-cache'),
      npm_config_userconfig: npmUserConfig, npm_config_globalconfig: npmGlobalConfig,
      npm_config_registry: 'https://registry.npmjs.org/', npm_config_ignore_scripts: 'true',
      INSPECTOR_SKIP_CLIENT_INSTALL: '1' };
    receipt.stage = 'INSTALL';
    await command('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], install, installEnv, 300000);
    verifyDependencyPins(readFileSync(resolve(install, 'package.json')), readFileSync(resolve(install, 'package-lock.json')));
    const inspector = resolve(install, 'node_modules/@modelcontextprotocol/inspector');
    const entry = regularFile(resolve(inspector, 'clients/launcher/build/index.js'));
    const identity = JSON.parse(readFileSync(resolve(inspector, 'package.json')));
    if (identity.name !== receipt.targetName || identity.version !== receipt.targetVersion) fail('INSTALLED_IDENTITY_MISMATCH');
    receipt.installedTreeIdentity = directoryIdentity(resolve(install, 'node_modules'));
    receipt.launcherSha256 = hashFile(entry);
    const fixtureOutput = resolve(work, 'public-fixture');
    receipt.stage = 'FIXTURE_BUILD';
    const build = await command('/bin/sh', [resolve(root, 'conformance/official/build-public-fixture.sh'),
      jar, fixtureOutput], root, env, 120000);
    const classpath = `${fixtureOutput}/classes${delimiter}${jar}`;
    if (build.stdout !== `${classpath}\n` || build.stderr !== '') fail('FIXTURE_BUILD_OUTPUT_INVALID');
    receipt.fixtureClassTree = directoryIdentity(resolve(fixtureOutput, 'classes'));
    receipt.stage = 'PROBES';
    for (const enabled of [true, false]) {
      for (const operation of ['tools/list', 'tools/call']) {
        receipt.runs.push(await probe({ java: javaPath, classpath, entry, work, enabled, operation }));
        persist();
      }
    }
    receipt.stage = 'POST_RUN_INTEGRITY';
    if (receipt.candidateJarSha256 !== hashFile(jar) || receipt.candidatePomSha256 !== hashFile(pom)
        || JSON.stringify(receipt.installedTreeIdentity) !== JSON.stringify(directoryIdentity(resolve(install, 'node_modules')))
        || JSON.stringify(receipt.fixtureClassTree) !== JSON.stringify(directoryIdentity(resolve(fixtureOutput, 'classes')))
        || JSON.stringify(receipt.sourceTreeIdentity) !== JSON.stringify(await sourceIdentity(env))) fail('RUN_INPUT_DRIFT');
    receipt.inputsUnchanged = true;
    receipt.status = receipt.runs.every(row => row.status === 'PASSED') ? 'PASSED'
      : receipt.runs.slice(0, 2).every(row => row.status === 'PASSED')
        && receipt.runs.slice(2).every(row => row.status === 'BLOCKED_HOST_EXTENSION_TOGGLE')
        ? 'BLOCKED_HOST_EXTENSION_TOGGLE' : 'FAILED';
    receipt.stage = 'COMPLETE';
  } catch {
    receipt.failure = 'HARNESS_OR_INPUT_CHECK_FAILED';
  } finally {
    process.off('SIGINT', onSignal);
    process.off('SIGTERM', onSignal);
    for (const handle of activeProcesses) {
      try { await handle.stop(); }
      catch { receipt.status = 'FAILED'; receipt.failure = 'PROCESS_CLEANUP_FAILED'; }
    }
    const trace = { formatVersion: 1, policy: 'STRUCTURAL_ALLOWLIST_NO_RAW_PAYLOAD_OR_BODY_HASH',
      runs: receipt.runs.map(({ name, trace }) => ({ name, exchanges: trace })) };
    writeFileSync(resolve(work, 'sanitized-trace.json'), json(trace), { mode: 0o600 });
    receipt.sanitizedTracePath = 'sanitized-trace.json';
    receipt.sanitizedTraceSha256 = sha(json(trace));
    persist();
  }
  return receipt;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 8 || args[0] !== '--candidate-jar' || args[2] !== '--candidate-pom'
        || args[4] !== '--java' || args[6] !== '--work-dir') fail('USAGE');
    const result = await runHarness({ candidateJar: args[1], candidatePom: args[3],
      java: args[5], workDirectory: args[7] });
    console.log(`Inspector harness: ${result.status}`);
    process.exitCode = result.status === 'PASSED' ? 0 : result.status === 'BLOCKED_HOST_EXTENSION_TOGGLE' ? 2 : 1;
  } catch {
    console.error('Inspector harness failed before a qualified receipt; inspect only sanitized artifacts.');
    process.exitCode = 1;
  }
}
