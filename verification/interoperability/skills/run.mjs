#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment } from '../inspector/config.mjs';
import { directoryIdentity, verifyDependencyPins } from '../inspector/run.mjs';
import { startProcess } from '../inspector/process.mjs';
import { startProxy } from './proxy.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const example = resolve(root, 'examples/skills');
const mainClass = 'com.soklet.examples.skills.SkillsExample';
const skillUri = 'skill://soklet.example/toy-catalog-guide/SKILL.md';
const baseUri = skillUri.slice(0, -'SKILL.md'.length);
const memoryNotice = '[mcp-inspector] Secrets are not written anywhere and are lost on exit.\n';
const originalTree = { files: 9391, sha256: '8c0b1ed101c4c7e7497aa4aaba7e953b03a44bc58179308db1613a988d5a2b8d' };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hashFile = path => sha(readFileSync(path));
const json = value => `${JSON.stringify(value, null, 2)}\n`;

function sampleFiles() {
  return new Map([
    [skillUri, readFileSync(resolve(example, 'resources/SKILL.md'))],
    [`${baseUri}references/catalog.csv`, readFileSync(resolve(example, 'resources/references/catalog.csv'))],
    [`${baseUri}assets/sample.bin`, Buffer.from([0x53, 0x4f, 0x4b, 0x4c, 0x45, 0x54, 0, 1, 0xff])],
  ]);
}

export function validateEntry(entry, files = sampleFiles()) {
  assert.equal(entry.uri, skillUri);
  assert.deepEqual(entry.frontmatter, {
    name: 'toy-catalog-guide', description: 'A synthetic guide for exploring a tiny toy catalog.',
    example: { catalog: { currency: 'USD', departments: ['puzzles', 'outdoor'] },
      provenance: { kind: 'synthetic', revision: 1 } },
  });
  assert.equal(entry.resources.length, files.size);
  assert.deepEqual(new Set(entry.resources.map(file => file.uri)), new Set(files.keys()));
  for (const resource of entry.resources) {
    const bytes = files.get(resource.uri);
    assert.equal(resource.size, bytes.length);
    assert.equal(resource.digest, `sha256:${sha(bytes)}`);
  }
}

export function validateExchanges(rows, method, uri, verify = false, tampered = false) {
  assert.equal(rows.length, verify ? 5 : 2);
  assert.ok(rows.every(row => row.valid));
  assert.deepEqual(rows.slice(0, 2).map(row => row.method), ['server/discover', method]);
  assert.equal(rows[0].target, 'NONE');
  const target = uri === skillUri ? 'ROOT' : uri?.endsWith('/catalog.csv') ? 'CSV' : uri ? 'BINARY' : 'NONE';
  assert.equal(rows[1].target, target);
  if (verify) {
    assert.ok(rows.slice(2).every(row => row.method === 'resources/read'));
    assert.deepEqual(rows.slice(2).map(row => row.target).sort(), ['BINARY', 'CSV', 'ROOT']);
  }
  assert.equal(rows.filter(row => row.tampered).length, tampered ? 1 : 0);
  if (tampered) assert.equal(rows.find(row => row.tampered).target, 'BINARY');
}

export function validateVerification(exit, tampered = false, files = sampleFiles()) {
  assert.equal(exit.signal, null);
  assert.equal(exit.code, tampered ? 7 : 0);
  const reports = exit.stdout.trim().split('\n').map(line => JSON.parse(line));
  assert.equal(reports.length, 1);
  const report = reports[0];
  assert.equal(report.uri, skillUri);
  assert.equal(report.name, 'toy-catalog-guide');
  assert.equal(report.outcome, tampered ? 'failed' : 'verified');
  assert.equal(report.ok, !tampered);
  assert.equal(report.incomplete, undefined);
  assert.deepEqual(report.conformance, []);
  assert.deepEqual(report.frontmatter, []);
  assert.equal(report.files.length, files.size);
  assert.deepEqual(new Set(report.files.map(file => file.uri)), new Set(files.keys()));
  for (const file of report.files) {
    const expected = files.get(file.uri);
    assert.equal(file.expectedSize, expected.length);
    assert.equal(file.actualSize, expected.length);
    assert.equal(file.expectedDigest, `sha256:${sha(expected)}`);
    if (tampered && file.uri.endsWith('/assets/sample.bin')) {
      assert.equal(file.status, 'mismatch');
      const changed = Buffer.from(expected);
      changed[0] ^= 1;
      assert.equal(file.actualDigest, `sha256:${sha(changed)}`);
    } else {
      assert.equal(file.status, 'verified');
      assert.equal(file.actualDigest, file.expectedDigest);
    }
  }
  const summary = tampered
    ? '1 of 1 skill failed verification (1 digest/size mismatch across 3 files).'
    : 'Verified 1 skill and 3 files: no conformance errors.';
  const expectedError = tampered ? `${JSON.stringify({ error: { code: 'skills_nonconformant', message: summary } })}\n` : '';
  assert.equal(exit.stderr, `${memoryNotice}${summary}\n${expectedError}`);
  return { outcome: report.outcome, files: report.files.length,
    verified: report.files.filter(file => file.status === 'verified').length,
    mismatched: report.files.filter(file => file.status === 'mismatch').length,
    frontmatterMatches: true, exitCode: exit.code };
}

function ready(handle) {
  return new Promise((resolveReady, reject) => {
    let text = '';
    const timer = setTimeout(() => finish(new Error('Example readiness timed out')), 10000);
    const onClose = () => finish(new Error('Example exited before readiness'));
    const onData = chunk => {
      text += chunk.toString('utf8');
      if (text.length > 4096) return finish(new Error('Invalid readiness output'));
      if (!text.includes('\n')) return;
      try {
        const value = JSON.parse(text.slice(0, text.indexOf('\n')));
        assert.equal(value.event, 'ready');
        assert.equal(value.host, '127.0.0.1');
        assert.equal(value.path, '/mcp');
        assert.ok(Number.isInteger(value.port) && value.port > 0 && value.port <= 65535);
        finish(null, value);
      } catch { finish(new Error('Invalid readiness output')); }
    };
    function finish(error, value) {
      clearTimeout(timer);
      handle.child.stdout.off('data', onData);
      handle.child.off('close', onClose);
      if (error) reject(error); else resolveReady(value);
    }
    handle.child.stdout.on('data', onData);
    handle.child.once('close', onClose);
  });
}

export async function run({ candidateJar, candidatePom, java, dependencies, workDirectory }) {
  const jar = realpathSync(candidateJar);
  const pom = realpathSync(candidatePom);
  const jvm = realpathSync(java);
  const install = realpathSync(dependencies);
  const work = resolve(workDirectory);
  mkdirSync(work, { mode: 0o700 }); // Refuse reuse, including previous failed runs.
  const privateState = resolve(work, 'private');
  mkdirSync(privateState, { mode: 0o700 });
  const env = createEnvironment(privateState, jvm);
  for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
    mkdirSync(env[key], { recursive: true, mode: 0o700 });
  const processes = new Set();
  let interrupted = false;
  const managed = (command, args, timeoutMs = 30000, stdin = 'ignore') => {
    if (interrupted) throw new Error('Probe interrupted');
    const handle = startProcess(command, args, { cwd: work, env, timeoutMs, stdin, maxOutputBytes: 2 * 1024 * 1024 });
    processes.add(handle);
    void handle.completion.catch(() => {});
    return handle;
  };
  const command = async (program, args, timeout) => {
    const exit = await managed(program, args, timeout).completion;
    assert.equal(exit.code, 0, 'Managed command failed');
    assert.equal(exit.signal, null);
    return exit;
  };
  const receipt = { format: 1, status: 'FAILED', scope: 'EXAMPLE_AND_RELEASED_INSPECTOR_RETRIEVAL',
    activationTested: false, releaseQualification: false, probes: [], trace: [] };
  let proxy;
  let server;
  const stop = () => { interrupted = true; for (const handle of processes) void handle.stop().catch(() => {}); };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  try {
    receipt.stage = 'INPUTS';
    assert.equal(process.version, 'v26.5.0', 'Use the reviewed Node runtime');
    verifyDependencyPins(readFileSync(resolve(install, 'package.json')), readFileSync(resolve(install, 'package-lock.json')));
    const dependencyTree = directoryIdentity(resolve(install, 'node_modules'));
    assert.deepEqual(dependencyTree, originalTree, 'Use the original, unmodified pinned Inspector install');
    const inspector = resolve(install, 'node_modules/@modelcontextprotocol/inspector');
    const identity = JSON.parse(readFileSync(resolve(inspector, 'package.json')));
    assert.equal(identity.version, '2.7.0');
    const entry = resolve(inspector, 'clients/launcher/build/index.js');
    const inputs = { candidateJar: hashFile(jar), candidatePom: hashFile(pom),
      example: directoryIdentity(example), harness: directoryIdentity(here), dependencyTree,
      launcher: hashFile(entry), lock: hashFile(resolve(install, 'package-lock.json')) };
    receipt.inputs = inputs;
    receipt.client = { name: identity.name, version: identity.version,
      sourceCommit: '2e90a628e6296c62e4bef942afbb43d3faa4baf4', modified: false };
    receipt.node = process.version;
    const version = await command(jvm, ['-version']);
    receipt.java = version.stderr.trim();
    const embeddedPom = await command('/usr/bin/unzip', ['-p', jar, 'META-INF/maven/com.soklet/soklet/pom.xml']);
    assert.equal(embeddedPom.stdout, readFileSync(pom, 'utf8'));
    receipt.stage = 'COMPILE';
    const classes = resolve(work, 'classes');
    mkdirSync(classes);
    const compile = await command(resolve(dirname(jvm), 'javac'), ['--release', '17', '-proc:none', '-Xlint:all', '-Werror',
      '-cp', jar, '-d', classes, resolve(example, 'src/com/soklet/examples/skills/SkillsExample.java')], 60000);
    assert.equal(compile.stderr, '');
    const audit = await command(resolve(dirname(jvm), 'jdeps'), ['--multi-release', '17', '-verbose:class', '-filter:none',
      '-cp', jar, classes]);
    assert.doesNotMatch(audit.stdout, /com\.soklet\.internal\.|not found/);
    receipt.publicApiOnly = true;
    const classIdentity = directoryIdentity(classes);
    receipt.compiledClasses = classIdentity;
    receipt.stage = 'CLIENT';
    server = managed(jvm, ['-cp', [classes, resolve(example, 'resources'), jar].join(delimiter), mainClass], 120000, 'pipe');
    const address = await ready(server);
    proxy = await startProxy(address.port);
    const config = { mcpServers: { soklet: { type: 'http', url: `http://127.0.0.1:${proxy.port}/mcp`, protocolEra: 'modern',
      advertisedExtensions: { 'io.modelcontextprotocol/skills': true } } } };
    const configPath = resolve(privateState, 'session.json');
    writeFileSync(configPath, json(config), { mode: 0o400, flag: 'wx' });
    const cli = async (method, uri, verify = false, tampered = false) => {
      receipt.stage = `CLIENT_${method.replace('/', '_')}${verify ? '_verify' : ''}${tampered ? '_negative' : ''}`;
      proxy.setTampered(tampered);
      const traceStart = proxy.rows.length;
      const args = [entry, '--cli', '--config', configPath, '--server', 'soklet', '--method', method,
        '--format', 'json', '--stored-auth-only', ...(uri ? ['--uri', uri] : []), ...(verify ? ['--verify'] : [])];
      const exit = await managed(process.execPath, args).completion;
      validateExchanges(proxy.rows.slice(traceStart), method, uri, verify, tampered);
      if (verify) {
        receipt.probes.push({ method, verification: validateVerification(exit, tampered), tampered });
        return;
      }
      assert.equal(exit.code, 0);
      assert.equal(exit.signal, null);
      assert.equal(exit.stderr, memoryNotice);
      const envelope = JSON.parse(exit.stdout);
      assert.deepEqual(Object.keys(envelope), ['result']);
      receipt.probes.push({ method, exitCode: 0 });
      return envelope.result;
    };
    const list = await cli('skills/list');
    assert.equal(list.skills.length, 1);
    validateEntry(list.skills[0]);
    const get = await cli('skills/get', skillUri);
    assert.deepEqual(get.skill, list.skills[0]);
    const ordinary = await cli('resources/list');
    assert.deepEqual(ordinary.resources, []);
    for (const [uri, bytes] of sampleFiles()) {
      const result = await cli('resources/read', uri);
      assert.equal(result.contents.length, 1);
      const content = result.contents[0];
      assert.equal(content.uri, uri);
      const binary = uri.endsWith('/sample.bin');
      assert.equal(content.mimeType, binary ? 'application/octet-stream' : uri === skillUri ? 'text/markdown' : 'text/plain');
      assert.equal(typeof content[binary ? 'blob' : 'text'], 'string');
      assert.equal(content[binary ? 'text' : 'blob'], undefined);
      const received = binary ? Buffer.from(content.blob, 'base64') : Buffer.from(content.text, 'utf8');
      assert.deepEqual(received, bytes);
    }
    await cli('skills/list', null, true);
    await cli('skills/get', skillUri, true);
    await cli('skills/get', skillUri, true, true);
    assert.equal(proxy.rows.filter(row => row.tampered).length, 1);
    assert.ok(proxy.rows.length > 0 && proxy.rows.every(row => row.valid));
    assert.equal(readFileSync(configPath, 'utf8'), json(config));
    receipt.stage = 'SHUTDOWN';
    receipt.trace = proxy.rows;
    await proxy.close();
    proxy = undefined;
    assert.equal(receipt.probes.length, 9);
    assert.equal(receipt.trace.length, 27);
    assert.ok(receipt.trace.every(row => row.valid));
    assert.equal(receipt.trace.filter(row => row.tampered).length, 1);
    server.child.stdin.end();
    const exit = await server.completion;
    assert.equal(exit.code, 0);
    assert.equal(exit.signal, null);
    assert.equal(exit.stderr, '');
    const controls = exit.stdout.trim().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(controls, [address, { event: 'stopped', clean: true }]);
    receipt.cleanShutdown = true;
    receipt.stage = 'POST_RUN_INTEGRITY';
    assert.equal(hashFile(jar), inputs.candidateJar);
    assert.equal(hashFile(pom), inputs.candidatePom);
    assert.deepEqual(directoryIdentity(example), inputs.example);
    assert.deepEqual(directoryIdentity(here), inputs.harness);
    assert.deepEqual(directoryIdentity(resolve(install, 'node_modules')), inputs.dependencyTree);
    assert.equal(hashFile(resolve(install, 'package-lock.json')), inputs.lock);
    assert.deepEqual(directoryIdentity(classes), classIdentity);
    receipt.inputsUnchanged = true;
    assert.equal(interrupted, false);
    receipt.status = 'PASSED';
    receipt.stage = 'COMPLETE';
  } catch {
    receipt.failure = 'EXAMPLE_OR_CLIENT_CHECK_FAILED';
  } finally {
    if (proxy) {
      receipt.trace = proxy.rows;
      try { await proxy.close(); } catch { receipt.status = 'FAILED'; }
    }
    for (const handle of processes) {
      try { await handle.stop(); } catch { receipt.status = 'FAILED'; }
    }
    if (interrupted) receipt.status = 'FAILED';
    process.off('SIGINT', stop);
    process.off('SIGTERM', stop);
    rmSync(privateState, { recursive: true, force: true }); // Exact newly-created per-run state only.
    receipt.privateStateRemoved = true;
    writeFileSync(resolve(work, 'receipt.json'), json(receipt), { mode: 0o600, flag: 'wx' });
  }
  return receipt;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  try {
    assert.deepEqual(args.filter((_, index) => index % 2 === 0),
      ['--candidate-jar', '--candidate-pom', '--java', '--dependencies', '--work-dir']);
    const receipt = await run({ candidateJar: args[1], candidatePom: args[3], java: args[5], dependencies: args[7], workDirectory: args[9] });
    console.log(`Skills example / Inspector: ${receipt.status} (${receipt.stage})`);
    process.exitCode = receipt.status === 'PASSED' ? 0 : 1;
  } catch {
    console.error('Skills example probe could not start; check arguments and use a new work directory.');
    process.exitCode = 1;
  }
}
