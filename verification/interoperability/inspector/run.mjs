#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync,
  readlinkSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment, createSessionConfig, inspectorArguments, sanitizedSessionConfig } from './config.mjs';
import { startProcess } from './process.mjs';
import { adjudicateTrace, startProxy, validResult, PROTOCOL } from './trace.mjs';
import { parseControl } from '../run-against-public-fixture.mjs';
import {buildUpstream, verifyUpstreamBuild, INSPECTOR_SOURCE_PIN} from './upstream-build.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const PACKAGE_SHA = '8fc642c9ec63e951bf987d4ccf2c7a198a33f350f7d424ad2e95a1f48dbfe2c0';
const LOCK_SHA = '37dfcdb1476f7f31c4aeea13971a729588bad7719c67bb8349f7bd8c52d29f29';
const INSPECTOR_INTEGRITY = 'sha512-V1SqfR+m3NWMkEe2i3v2GMm00pZtAl/JISAHQzYnOZElEuwuLLkU8vTWBYg5HMCYTgpvuMmzb+MZz98HjdcGuw==';
const FIXTURE_WARNING = ''; // Current public fixture explicitly installs acceptAllInstance.
const MEMORY_STORE_CAVEAT = '[mcp-inspector] Secrets are not written anywhere and are lost on exit.\n';
const UPSTREAM_MEMORY_STORE_CAVEAT = MEMORY_STORE_CAVEAT
  + '[mcp-inspector] How the secret store is chosen, and how to secure it: https://github.com/modelcontextprotocol/inspector/blob/main/docs/secret-storage.md\n';
const CLI_PROFILE = 'apps-toggle-skills-enabled';
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

export function workingTreeFileRows(directory, paths) {
  return [...new Set(paths)].sort().map(path => {
    const absolute = resolve(directory, path);
    try { lstatSync(absolute); }
    catch (error) {
      // Git still lists tracked deletions until the owner commits. Preserve
      // their absence in the fingerprint rather than rejecting a dirty tree.
      if (error.code === 'ENOENT') return [path, 'DELETED'];
      throw error;
    }
    return [path, hashFile(regularFile(absolute))];
  });
}

async function sourceIdentity(env) {
  const gitOptions = ['-c', `safe.directory=${root}`];
  const listing = await command('/usr/bin/git', [...gitOptions, 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], root, env);
  const paths = [...new Set(listing.stdout.split('\0').filter(Boolean))].sort();
  const rows = workingTreeFileRows(root, paths);
  const head = await command('/usr/bin/git', [...gitOptions, 'rev-parse', 'HEAD'], root, env);
  const state = await command('/usr/bin/git', [...gitOptions, 'status', '--porcelain'], root, env);
  return { kind: 'TRACKED_AND_UNIGNORED_WORKING_TREE', head: head.stdout.trim(),
    dirty: state.stdout !== '', files: paths.length,
    deletedFiles: rows.filter(([, identity]) => identity === 'DELETED').length,
    sha256: sha(json(rows)) };
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

export function validateCliOutput(stdout, stderr, operation, currentSource = false) {
  // The pinned memory store emits this one fixed caveat. It is accounted for,
  // not a general permission for arbitrary stderr or credential diagnostics.
  if (stderr !== (currentSource ? UPSTREAM_MEMORY_STORE_CAVEAT : MEMORY_STORE_CAVEAT)) return false;
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
  const record = { name, requestedExtensions: enabled ? 'APPS_ENABLED_SKILLS_ENABLED' : 'APPS_DISABLED_SKILLS_ENABLED', operation,
    status: 'FAILED', baseTransport: 'NOT_RUN', cliOutputMatches: false,
    fixtureShutdown: 'NOT_PROVEN', configUnchanged: false, trace: [] };
  try {
    fixture = managed(java, ['-cp', classpath, fixtureMain, '--scenario', 'tools-list'],
      { cwd: root, env, timeoutMs: 60000, stdin: 'pipe' });
    void fixture.completion.catch(() => {});
    const ready = await readyLine(fixture);
    if (ready.host !== '127.0.0.1' || ready.path !== '/mcp'
        || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535) fail('FIXTURE_ADDRESS_INVALID');
    proxy = await startProxy({ fixturePort: ready.port, token, enabled, cliProfile: CLI_PROFILE });
    const config = createSessionConfig(`http://127.0.0.1:${proxy.port}/mcp`, token, {apps: enabled, skills: true});
    const configPath = resolve(sessionRoot, 'session.json');
    const configBytes = json(config);
    writeFileSync(configPath, configBytes, { mode: 0o600, flag: 'wx' });
    record.config = sanitizedSessionConfig(config);
    client = managed(process.execPath, [...inspectorArguments(entry, configPath, operation),
      ...(enabled ? ['--advertise-apps'] : [])],
      { cwd: sessionRoot, env, timeoutMs: 30000 });
    const exit = await client.completion;
    record.cliExit = { code: exit.code, signal: exit.signal };
    record.cliOutputMatches = validateCliOutput(exit.stdout, exit.stderr, operation, true);
    record.memoryStoreCaveatObserved = exit.stderr === UPSTREAM_MEMORY_STORE_CAVEAT;
    record.configUnchanged = readFileSync(configPath, 'utf8') === configBytes;
    record.trace = proxy.rows;
    fixture.child.stdin.end();
    const fixtureExit = await fixture.completion;
    const lines = fixtureExit.stdout.trimEnd().split('\n');
    const stopped = lines.length === 2 && parseControl(lines[1], 'stopped');
    if (fixtureExit.code === 0 && fixtureExit.signal === null && stopped?.clean === true
        && fixtureExit.stderr === FIXTURE_WARNING) record.fixtureShutdown = 'CLEAN';
    record.fixtureExplicitAdmissionQuiet = fixtureExit.stderr === FIXTURE_WARNING;
    if (exit.code !== 0 || exit.signal !== null || !record.cliOutputMatches
        || !record.configUnchanged || record.fixtureShutdown !== 'CLEAN' || proxy.failure()) fail('PROBE_CHECK_FAILED');
    record.status = adjudicateTrace(record.trace, enabled, operation, CLI_PROFILE);
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

export async function runHarness({ candidateJar, candidatePom, java, workDirectory,
  surface = 'cli', browserExecutable, inspectorBuild }) {
  if (!['cli', 'web'].includes(surface)) fail('HARNESS_SURFACE_INVALID');
  const web = surface === 'web';
  const jar = regularFile(candidateJar);
  const pom = regularFile(candidatePom);
  const javaPath = regularFile(java);
  const chrome = web ? regularFile(browserExecutable) : null;
  if (web && (process.platform !== 'darwin'
      || !chrome.endsWith('/Google Chrome.app/Contents/MacOS/Google Chrome')))
    fail('WEB_BROWSER_DISTRIBUTION_INVALID');
  const browserRoot = web ? resolve(dirname(chrome), '../..') : null;
  const work = resolve(workDirectory);
  const outputParent = resolve(root, 'target/inspector');
  if (!work.startsWith(`${outputParent}${sep}`) || existsSync(work)) fail('WORK_DIRECTORY_MUST_BE_FRESH');
  for (let parent = dirname(work); parent !== root; parent = dirname(parent)) {
    if (existsSync(parent) && (!lstatSync(parent).isDirectory() || lstatSync(parent).isSymbolicLink()))
      fail('WORK_DIRECTORY_ANCESTOR_INVALID');
  }
  if (process.versions.node !== '26.5.0') fail('NODE_VERSION_MISMATCH');
  mkdirSync(work, { recursive: true, mode: 0o700 });
  const env = makeIsolation(resolve(work, 'build-isolation'), javaPath);
  const receipt = { formatVersion: 1, evidenceClass: 'LOCAL_HOST_HARNESS_DEVELOPMENT_ONLY', stage: 'INPUTS',
    status: 'FAILED', releaseCandidateEvidence: false, sourceTreeIdentity: null,
    candidateJarSha256: hashFile(jar), candidatePomSha256: hashFile(pom),
    harnessId: 'soklet.inspector.base-transport.v1',
    profileId: web ? 'modern-web-extension-toggles.v1' : 'modern-cli-apps-toggle-skills-enabled.v1',
    targetName: '@modelcontextprotocol/inspector', targetVersion: INSPECTOR_SOURCE_PIN.packageVersion,
    targetCommit: INSPECTOR_SOURCE_PIN.commit, distribution: INSPECTOR_SOURCE_PIN.distribution,
    sourceArchiveSha256: INSPECTOR_SOURCE_PIN.archiveSha256,
    protocolVersion: PROTOCOL, executedAt: new Date().toISOString(),
    appsRendering: 'NOT_RUN', skillsRetrieval: 'NOT_RUN', browserVersion: null,
    limitations: ['Dirty-tree development evidence is not a release receipt.',
      web ? 'The real web UI exercises ordinary tools only; Apps rendering, CSP and permissions are not tested.'
        : 'The unchanged CLI has no renderer; Apps rendering, CSP and permissions are not tested.',
      'The current fixture has no Apps or Skills registrations; core fallback only is exercised.',
      'Disposable bearer authorization terminates at the loopback proxy, not at Soklet.',
      'No OAuth, production host, localization, tenant isolation, or Skills activation is qualified.',
      web ? 'Both web rows require exact actual extension advertisements; a disabled-row mismatch fails.'
        : 'The CLI supports Apps toggling with --advertise-apps; Skills stay enabled in both rows. No Skills-OFF qualification is claimed.'],
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
    if (web) receipt.browserDistributionIdentity = directoryIdentity(browserRoot);
    const npmVersion = await command('npm', ['--version'], work, env);
    if (npmVersion.stdout.trim() !== '11.17.0') fail('NPM_VERSION_MISMATCH');
    const javaVersion = await command(javaPath, ['-version'], work, env);
    receipt.runtime = { node: process.versions.node, npm: npmVersion.stdout.trim(),
      java: javaVersion.stderr.trim(), platform: process.platform, architecture: process.arch };
    const embeddedPom = await command('/usr/bin/unzip', ['-p', jar,
      'META-INF/maven/com.soklet/soklet/pom.xml'], work, env);
    if (sha(embeddedPom.stdout) !== receipt.candidatePomSha256) fail('CANDIDATE_POM_MISMATCH');
    const buildDirectory = inspectorBuild ? resolve(inspectorBuild) : resolve(work, 'upstream-build');
    receipt.stage = 'UPSTREAM_SOURCE_BUILD';
    const upstream = inspectorBuild ? verifyUpstreamBuild(buildDirectory)
      : await buildUpstream({workDirectory: buildDirectory});
    const entry = upstream.entry;
    receipt.upstreamBuild = upstream;
    receipt.installedTreeIdentity = upstream.identity;
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
      if (web) {
        const { probeWeb } = await import('./web-probe.mjs');
        receipt.runs.push(await probeWeb({ java: javaPath, classpath, entry, work, enabled, chrome,
          helpers: { managed, makeIsolation, readyLine, root, fixtureMain, fixtureWarning: FIXTURE_WARNING } }));
        persist();
      } else {
        for (const operation of ['tools/list', 'tools/call']) {
          receipt.runs.push(await probe({ java: javaPath, classpath, entry, work, enabled, operation }));
          persist();
        }
      }
    }
    receipt.stage = 'POST_RUN_INTEGRITY';
    if (receipt.candidateJarSha256 !== hashFile(jar) || receipt.candidatePomSha256 !== hashFile(pom)
        || JSON.stringify(receipt.installedTreeIdentity) !== JSON.stringify(verifyUpstreamBuild(buildDirectory).identity)
        || JSON.stringify(receipt.fixtureClassTree) !== JSON.stringify(directoryIdentity(resolve(fixtureOutput, 'classes')))
        || JSON.stringify(receipt.sourceTreeIdentity) !== JSON.stringify(await sourceIdentity(env))) fail('RUN_INPUT_DRIFT');
    if (web) {
      if (JSON.stringify(receipt.browserDistributionIdentity) !== JSON.stringify(directoryIdentity(browserRoot)))
        fail('BROWSER_INPUT_DRIFT');
      receipt.browserVersion = receipt.runs[0]?.browserVersion ?? null;
      if (!receipt.browserVersion || receipt.runs.some(row =>
        JSON.stringify(row.browserVersion) !== JSON.stringify(receipt.browserVersion))) fail('BROWSER_IDENTITY_MISMATCH');
    }
    receipt.inputsUnchanged = true;
    receipt.status = receipt.runs.every(row => row.status === 'PASSED') ? 'PASSED'
      : !web && receipt.runs.slice(0, 2).every(row => row.status === 'PASSED')
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
    if (![8, 10].includes(args.length) || (args.length === 10 && args[8] !== '--inspector-build') || args[0] !== '--candidate-jar' || args[2] !== '--candidate-pom'
        || args[4] !== '--java' || args[6] !== '--work-dir') fail('USAGE');
    const result = await runHarness({ candidateJar: args[1], candidatePom: args[3],
      java: args[5], workDirectory: args[7], inspectorBuild: args[9] });
    console.log(`Inspector harness: ${result.status}`);
    process.exitCode = result.status === 'PASSED' ? 0 : result.status === 'BLOCKED_HOST_EXTENSION_TOGGLE' ? 2 : 1;
  } catch {
    console.error('Inspector harness failed before a qualified receipt; inspect only sanitized artifacts.');
    process.exitCode = 1;
  }
}
