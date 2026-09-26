#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createEnvironment } from '../inspector/config.mjs';
import { directoryIdentity, verifyDependencyPins } from '../inspector/run.mjs';
import { startProcess } from '../inspector/process.mjs';
import { validateWorkDirectory } from '../inspector-auth-patch/run.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, 'SkillsMatrixFixture.java');
const mainClass = 'com.soklet.interop.skills.SkillsMatrixFixture';
const originalTree = { files: 9391, sha256: '8c0b1ed101c4c7e7497aa4aaba7e953b03a44bc58179308db1613a988d5a2b8d' };
const common = 'skill://matrix/common/overview/SKILL.md';
const parent = 'skill://matrix/shared/parent/SKILL.md';
const child = 'skill://matrix/shared/parent/child/SKILL.md';
const hidden = 'skill://matrix/hidden/reference/SKILL.md';
const sharedBinary = 'skill://matrix/shared/parent/child/assets/shared.bin';
const english = 'skill://matrix/en/guide/SKILL.md';
const french = 'skill://matrix/fr/guide/SKILL.md';
const credentials = { en: 'matrix-en-credential', fr: 'matrix-fr-credential', denied: 'matrix-denied-credential' };
const protocol = '2026-07-28';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const hashFile = path => sha(readFileSync(path));
const json = value => `${JSON.stringify(value, null, 2)}\n`;

function controlLine(handle, expectedEvent) {
  return new Promise((resolveControl, reject) => {
    let buffered = '';
    const timer = setTimeout(() => finish(new Error('Fixture control timed out')), 10000);
    const onClose = () => finish(new Error('Fixture exited before control event'));
    const onData = bytes => {
      buffered += bytes.toString('utf8');
      if (Buffer.byteLength(buffered) > 4096) return finish(new Error('Fixture control exceeded its bound'));
      const newline = buffered.indexOf('\n');
      if (newline < 0) return;
      try {
        const value = JSON.parse(buffered.slice(0, newline));
        assert.equal(value.event, expectedEvent);
        finish(null, value);
      } catch { finish(new Error('Invalid fixture control event')); }
    };
    function finish(error, value) {
      clearTimeout(timer);
      handle.child.stdout.off('data', onData);
      handle.child.off('close', onClose);
      if (error) reject(error); else resolveControl(value);
    }
    handle.child.stdout.on('data', onData);
    handle.child.once('close', onClose);
  });
}

function noPrivateSkill(body) {
  assert.doesNotMatch(body, /English guide|Guide français|Nested child|Parent with shared child|Unlisted reference|Synthetic instructions/);
}

async function direct(url, method, principal, extra = {}, language = 'fr') {
  const params = { _meta: {
    'io.modelcontextprotocol/protocolVersion': protocol,
    'io.modelcontextprotocol/clientCapabilities': {
      extensions: { 'io.modelcontextprotocol/skills': {} },
    },
  }, ...extra };
  const response = await fetch(url, {
    method: 'POST', signal: AbortSignal.timeout(5000),
    headers: { Authorization: `Bearer ${credentials[principal]}`, 'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': protocol,
      'Mcp-Method': method, 'Accept-Language': language,
      ...(method === 'resources/read' ? { 'Mcp-Name': extra.uri } : {}) },
    body: JSON.stringify({ jsonrpc: '2.0', id: 'matrix', method, params }),
  });
  const body = await response.text();
  assert.ok(Buffer.byteLength(body) <= 1024 * 1024);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const envelope = JSON.parse(body);
  assert.equal(envelope.id, 'matrix');
  return { status: response.status, envelope, body };
}

function expectPage(result, uris, hasCursor) {
  assert.equal(result.status, 200);
  assert.deepEqual(result.envelope.result.skills.map(skill => skill.uri), uris);
  assert.equal(typeof result.envelope.result.nextCursor === 'string', hasCursor);
  assert.equal(result.envelope.result.resultType, 'complete');
}

async function startFrontmatterTamperProxy(targetUrl) {
  const rows = [];
  const server = createServer(async (request, response) => {
    const row = { method: 'UNEXPECTED', tampered: false, valid: false };
    rows.push(row);
    try {
      assert.ok(rows.length <= 12);
      assert.equal(request.method, 'POST');
      assert.equal(request.url, '/mcp');
      const chunks = [];
      let length = 0;
      for await (const chunk of request) {
        length += chunk.length;
        assert.ok(length <= 64 * 1024);
        chunks.push(chunk);
      }
      const body = Buffer.concat(chunks);
      const message = JSON.parse(body.toString('utf8'));
      row.method = message.method;
      assert.ok(['server/discover', 'skills/get', 'resources/read'].includes(row.method));
      assert.equal(request.headers['mcp-method'], row.method);
      assert.equal(request.headers['mcp-protocol-version'], protocol);
      const upstream = await fetch(targetUrl, {
        method: 'POST', signal: AbortSignal.timeout(5000), body,
        headers: { Authorization: request.headers.authorization,
          'Content-Type': request.headers['content-type'], Accept: request.headers.accept,
          'MCP-Protocol-Version': request.headers['mcp-protocol-version'],
          'Mcp-Method': request.headers['mcp-method'],
          ...(request.headers['mcp-name'] ? { 'Mcp-Name': request.headers['mcp-name'] } : {}),
          ...(request.headers['accept-language'] ? { 'Accept-Language': request.headers['accept-language'] } : {}) },
      });
      assert.equal(upstream.status, 200);
      let bytes = Buffer.from(await upstream.arrayBuffer());
      assert.ok(bytes.length <= 1024 * 1024);
      if (row.method === 'resources/read' && message.params?.uri === english) {
        const envelope = JSON.parse(bytes.toString('utf8'));
        const content = envelope.result?.contents?.[0];
        assert.equal(content?.uri, english);
        assert.equal(typeof content.text, 'string');
        assert.match(content.text, /description: English guide/);
        content.text = content.text.replace('description: English guide',
          'description: Changed guide');
        bytes = Buffer.from(JSON.stringify(envelope));
        row.tampered = true;
      }
      const headers = Object.fromEntries(upstream.headers);
      delete headers['transfer-encoding'];
      headers['content-length'] = String(bytes.length);
      response.writeHead(upstream.status, headers);
      response.end(bytes);
      row.valid = true;
    } catch {
      if (!response.headersSent) response.writeHead(502, { 'content-type': 'text/plain' });
      response.end('Skills matrix proxy failed.');
    }
  });
  server.headersTimeout = 5000;
  server.requestTimeout = 5000;
  server.timeout = 5000;
  await new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  return { url: `http://127.0.0.1:${server.address().port}/mcp`, rows,
    async close() {
      server.closeAllConnections();
      await new Promise((resolveClose, reject) =>
        server.close(error => error ? reject(error) : resolveClose()));
    } };
}

export async function run({ candidateJar, candidatePom, java, dependencies, workDirectory }) {
  const jar = realpathSync(candidateJar);
  const pom = realpathSync(candidatePom);
  const jvm = realpathSync(java);
  const install = realpathSync(dependencies);
  const work = resolve(workDirectory);
  validateWorkDirectory(work, [jar, pom, source, install, resolve(dirname(jvm), '..')]);
  mkdirSync(work, { mode: 0o700 });
  const privateState = resolve(work, 'private');
  mkdirSync(privateState, { mode: 0o700 });
  const env = createEnvironment(privateState, jvm);
  for (const key of ['HOME', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'MCP_STORAGE_DIR'])
    mkdirSync(env[key], { recursive: true, mode: 0o700 });
  const processes = new Set();
  let interrupted = false;
  const managed = (command, args, timeoutMs = 30000, stdin = 'ignore') => {
    if (interrupted) throw new Error('Probe interrupted');
    const handle = startProcess(command, args,
      { cwd: work, env, timeoutMs, stdin, maxOutputBytes: 2 * 1024 * 1024 });
    processes.add(handle);
    void handle.completion.catch(() => {});
    return handle;
  };
  const command = async (program, args, timeoutMs) => {
    const exit = await managed(program, args, timeoutMs).completion;
    assert.equal(exit.code, 0);
    assert.equal(exit.signal, null);
    return exit;
  };
  const receipt = { format: 1, status: 'FAILED', scope: 'SKILLS_RELEASED_CLI_OPERATIONAL_MATRIX',
    releaseQualification: false, agentActivationTested: false, checks: [] };
  const stop = () => { interrupted = true; for (const handle of processes) void handle.stop().catch(() => {}); };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
  let server;
  let tamperProxy;
  try {
    receipt.stage = 'INPUTS';
    assert.equal(process.version, 'v26.5.0');
    verifyDependencyPins(readFileSync(resolve(install, 'package.json')),
      readFileSync(resolve(install, 'package-lock.json')));
    const dependencyTree = directoryIdentity(resolve(install, 'node_modules'));
    assert.deepEqual(dependencyTree, originalTree);
    const inspector = resolve(install, 'node_modules/@modelcontextprotocol/inspector');
    const identity = JSON.parse(readFileSync(resolve(inspector, 'package.json')));
    assert.equal(identity.version, '2.7.0');
    const entry = resolve(inspector, 'clients/launcher/build/index.js');
    const inputs = { candidateJar: hashFile(jar), candidatePom: hashFile(pom),
      fixture: hashFile(source), harness: directoryIdentity(here), dependencyTree,
      launcher: hashFile(entry), lock: hashFile(resolve(install, 'package-lock.json')) };
    receipt.inputs = inputs;
    receipt.client = { name: identity.name, version: identity.version,
      sourceCommit: '2e90a628e6296c62e4bef942afbb43d3faa4baf4', modified: false };
    receipt.node = process.version;
    receipt.java = (await command(jvm, ['-version'])).stderr.trim();
    const embeddedPom = await command('/usr/bin/unzip', ['-p', jar,
      'META-INF/maven/com.soklet/soklet/pom.xml']);
    assert.equal(embeddedPom.stdout, readFileSync(pom, 'utf8'));
    receipt.stage = 'COMPILE';
    const classes = resolve(work, 'classes');
    mkdirSync(classes);
    const compile = await command(resolve(dirname(jvm), 'javac'),
      ['--release', '17', '-proc:none', '-Xlint:all', '-Werror', '-cp', jar, '-d', classes, source], 60000);
    assert.equal(compile.stderr, '');
    const audit = await command(resolve(dirname(jvm), 'jdeps'),
      ['--multi-release', '17', '-verbose:class', '-filter:none', '-cp', jar, classes], 60000);
    assert.doesNotMatch(audit.stdout, /com\.soklet\.internal\.|not found/);
    receipt.publicApiOnly = true;
    const classIdentity = directoryIdentity(classes);
    receipt.stage = 'HOST';
    server = managed(jvm, ['-cp', [classes, jar].join(delimiter), mainClass], 120000, 'pipe');
    const ready = await controlLine(server, 'ready');
    assert.equal(ready.host, '127.0.0.1');
    assert.equal(ready.path, '/mcp');
    assert.ok(Number.isInteger(ready.port) && ready.port > 0 && ready.port <= 65535);
    const url = `http://127.0.0.1:${ready.port}/mcp`;
    const cli = async (principal, method, language, uri = null, verify = false, endpoint = url) => {
      receipt.stage = `CLI_${principal}_${method.replace('/', '_')}`;
      const args = [entry, '--cli', '--server-url', endpoint, '--protocol-era', 'modern',
        '--header', `Authorization: Bearer ${credentials[principal]}`, `Accept-Language: ${language}`,
        '--method', method, '--format', 'json', '--stored-auth-only',
        ...(uri ? ['--uri', uri] : []), ...(verify ? ['--verify'] : [])];
      const exit = await managed(process.execPath, args, 30000).completion;
      assert.equal(exit.signal, null);
      return exit;
    };

    const enList = await cli('en', 'skills/list', 'fr');
    assert.equal(enList.code, 0);
    assert.deepEqual(JSON.parse(enList.stdout).result.skills.map(skill => skill.uri),
      [common, parent, child, english]);
    receipt.checks.push('ENGLISH_HOST_WALKS_TWO_PAGES_WITH_SPOOFED_FRENCH_HEADER');

    const frList = await cli('fr', 'skills/list', 'en');
    assert.equal(frList.code, 0);
    assert.deepEqual(JSON.parse(frList.stdout).result.skills.map(skill => skill.uri),
      [common, parent, child, french]);
    receipt.checks.push('FRENCH_HOST_WALKS_TWO_PAGES_WITH_SPOOFED_ENGLISH_HEADER');

    const deniedList = await cli('denied', 'skills/list', 'fr');
    assert.equal(deniedList.code, 0);
    assert.deepEqual(JSON.parse(deniedList.stdout).result.skills, []);
    receipt.checks.push('DENIED_HOST_LIST_EMPTY');

    const verified = await cli('en', 'skills/list', 'fr', null, true);
    assert.equal(verified.code, 0);
    const reports = verified.stdout.trim().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(reports.map(report => report.uri), [common, parent, child, english]);
    assert.ok(reports.every(report => report.ok === true && report.outcome === 'verified'));
    const parentShared = reports[1].files.find(file => file.uri === sharedBinary);
    const childShared = reports[2].files.find(file => file.uri === sharedBinary);
    assert.ok(parentShared && childShared);
    assert.equal(parentShared.status, 'verified');
    assert.equal(childShared.status, 'verified');
    assert.equal(parentShared.actualDigest, childShared.actualDigest);
    receipt.checks.push('HOST_VERIFIES_SHARED_NESTED_BYTES_AND_FRONTMATTER');

    const directGet = await cli('en', 'skills/get', 'fr', english);
    assert.equal(directGet.code, 0);
    assert.equal(JSON.parse(directGet.stdout).result.skill.uri, english);
    const directRead = await cli('fr', 'resources/read', 'en', french);
    assert.equal(directRead.code, 0);
    assert.equal(JSON.parse(directRead.stdout).result.contents[0].uri, french);
    assert.match(JSON.parse(directRead.stdout).result.contents[0].text, /Guide français/);
    receipt.checks.push('DIRECT_URI_HOST_READS_IGNORE_OPPOSING_LANGUAGE_HEADER');

    const unlistedGet = await cli('en', 'skills/get', 'fr', hidden);
    assert.equal(unlistedGet.code, 0);
    assert.equal(JSON.parse(unlistedGet.stdout).result.skill.uri, hidden);
    const unlistedRead = await cli('en', 'resources/read', 'fr', hidden);
    assert.equal(unlistedRead.code, 0);
    assert.match(JSON.parse(unlistedRead.stdout).result.contents[0].text, /Unlisted reference/);
    receipt.checks.push('HOST_GETS_AUTHORIZED_UNLISTED_SKILL');

    receipt.stage = 'TAMPERED_FRONTMATTER';
    tamperProxy = await startFrontmatterTamperProxy(url);
    const tampered = await cli('en', 'skills/get', 'fr', english, true, tamperProxy.url);
    assert.equal(tampered.code, 7);
    const report = JSON.parse(tampered.stdout.trim());
    assert.equal(report.uri, english);
    assert.equal(report.outcome, 'failed');
    assert.equal(report.ok, false);
    assert.ok(report.frontmatter.length > 0);
    assert.equal(report.files.find(file => file.uri === english)?.status, 'mismatch');
    assert.deepEqual(tamperProxy.rows.map(row => row.method),
      ['server/discover', 'skills/get', 'resources/read']);
    assert.ok(tamperProxy.rows.every(row => row.valid));
    assert.equal(tamperProxy.rows.filter(row => row.tampered).length, 1);
    await tamperProxy.close();
    tamperProxy = undefined;
    receipt.checks.push('HOST_REJECTS_TAMPERED_FRONTMATTER_AND_DIGEST');

    receipt.stage = 'DIRECT_CONTROLS';
    const first = await direct(url, 'skills/list', 'en');
    expectPage(first, [common], true);
    const cursor = first.envelope.result.nextCursor;
    const replay = await direct(url, 'skills/list', 'fr', { cursor });
    assert.equal(replay.status, 400);
    assert.equal(replay.envelope.error.code, -32602);
    noPrivateSkill(replay.body);
    const second = await direct(url, 'skills/list', 'en', { cursor });
    expectPage(second, [parent, child, english], false);
    const duplicate = await direct(url, 'skills/list', 'en', { cursor });
    assert.equal(duplicate.status, 400);
    assert.equal(duplicate.envelope.error.code, -32602);
    receipt.checks.push('CALLER_BOUND_SINGLE_USE_CURSOR');

    const hiddenGet = await direct(url, 'skills/get', 'en', { uri: french });
    assert.notEqual(hiddenGet.status, 200);
    noPrivateSkill(hiddenGet.body);
    const hiddenRead = await direct(url, 'resources/read', 'denied', { uri: sharedBinary });
    assert.notEqual(hiddenRead.status, 200);
    noPrivateSkill(hiddenRead.body);
    receipt.checks.push('DIRECT_GET_AND_FILE_READ_RECHECK_ACCESS');

    receipt.stage = 'REVOCATION';
    const revokedEvent = controlLine(server, 'revoked');
    server.child.stdin.write('revoke\n');
    await revokedEvent;
    const revoked = await direct(url, 'skills/list', 'en');
    assert.equal(revoked.status, 401);
    assert.equal(revoked.envelope.error.code, -31901);
    noPrivateSkill(revoked.body);
    const revokedFile = await direct(url, 'resources/read', 'en', { uri: english });
    assert.equal(revokedFile.status, 401);
    noPrivateSkill(revokedFile.body);
    const revokedCli = await cli('en', 'skills/list', 'fr');
    assert.notEqual(revokedCli.code, 0);
    assert.doesNotMatch(revokedCli.stdout + revokedCli.stderr, /English guide|Guide français/);
    receipt.checks.push('REVOKED_CREDENTIAL_DENIED_ON_NEXT_HOST_REQUEST');

    receipt.stage = 'SHUTDOWN';
    server.child.stdin.end();
    const exit = await server.completion;
    assert.equal(exit.code, 0);
    assert.equal(exit.signal, null);
    assert.equal(exit.stderr, '');
    assert.deepEqual(exit.stdout.trim().split('\n').map(line => JSON.parse(line)),
      [ready, { event: 'revoked' }, { event: 'stopped', clean: true }]);
    receipt.cleanShutdown = true;
    receipt.stage = 'POST_RUN_INTEGRITY';
    assert.equal(hashFile(jar), inputs.candidateJar);
    assert.equal(hashFile(pom), inputs.candidatePom);
    assert.equal(hashFile(source), inputs.fixture);
    assert.deepEqual(directoryIdentity(here), inputs.harness);
    assert.deepEqual(directoryIdentity(resolve(install, 'node_modules')), dependencyTree);
    assert.equal(hashFile(resolve(install, 'package-lock.json')), inputs.lock);
    assert.deepEqual(directoryIdentity(classes), classIdentity);
    assert.equal(interrupted, false);
    receipt.inputsUnchanged = true;
    receipt.status = 'PASSED';
    receipt.stage = 'COMPLETE';
  } catch {
    receipt.failure = 'SKILLS_HOST_MATRIX_CHECK_FAILED';
  } finally {
    if (tamperProxy) {
      try { await tamperProxy.close(); } catch { receipt.status = 'FAILED'; }
    }
    for (const handle of processes) {
      try { await handle.stop(); } catch { receipt.status = 'FAILED'; }
    }
    if (interrupted) receipt.status = 'FAILED';
    process.off('SIGINT', stop);
    process.off('SIGTERM', stop);
    rmSync(privateState, { recursive: true, force: true });
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
    const receipt = await run({ candidateJar: args[1], candidatePom: args[3],
      java: args[5], dependencies: args[7], workDirectory: args[9] });
    console.log(`Skills host matrix: ${receipt.status} (${receipt.stage})`);
    process.exitCode = receipt.status === 'PASSED' ? 0 : 1;
  } catch {
    console.error('Skills host matrix could not start; check inputs and use a new work directory.');
    process.exitCode = 1;
  }
}
