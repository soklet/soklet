#!/usr/bin/env node
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runProcess, startProcess } from '../inspector/process.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const failureLine = '{"format":1,"event":"failed","code":"APPS_FIXTURE_FAILED"}\n';
const stoppedLine = '{"format":1,"event":"stopped","clean":true}\n';

function readyLine(handle) {
  return new Promise((resolveReady, reject) => {
    let bytes = '';
    const timer = setTimeout(() => finish(new Error('APPS_LAUNCHER_READY_TIMEOUT')), 10000);
    const data = chunk => {
      bytes += chunk.toString('utf8');
      if (bytes.length > 1024) return finish(new Error('APPS_LAUNCHER_CONTROL_BOUND'));
      if (!bytes.includes('\n')) return;
      try {
        const result = JSON.parse(bytes.slice(0, bytes.indexOf('\n')));
        assert.deepEqual(Object.keys(result).sort(), ['event', 'format', 'host', 'path', 'port']);
        assert.equal(result.format, 1);
        assert.equal(result.event, 'ready');
        assert.equal(result.host, '127.0.0.1');
        assert.equal(result.path, '/apps');
        assert.ok(Number.isSafeInteger(result.port) && result.port > 0 && result.port <= 65535);
        finish(null, result);
      } catch { finish(new Error('APPS_LAUNCHER_CONTROL_INVALID')); }
    };
    const close = () => finish(new Error('APPS_LAUNCHER_EARLY_EXIT'));
    function finish(error, result) {
      clearTimeout(timer);
      handle.child.stdout.off('data', data);
      handle.child.off('close', close);
      if (error) reject(error); else resolveReady(result);
    }
    handle.child.stdout.on('data', data);
    handle.child.once('close', close);
  });
}

async function boundedText(response) {
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > 65536) {
        await reader.cancel();
        throw new Error('APPS_LAUNCHER_RESPONSE_BOUND');
      }
      chunks.push(Buffer.from(value));
    }
    return Buffer.concat(chunks).toString('utf8');
  } finally { reader.releaseLock(); }
}

/** Real-process checks, separate from both the simulator and browser-host verdicts.
 * Even imported callers receive only a fixed failure code, never an AssertionError
 * retaining unexpected child streams, request credentials or response content. */
export async function launcherContracts(options) {
  try { return await runLauncherContracts(options); }
  catch { throw new Error('APPS_LAUNCHER_CONTRACT_FAILED'); }
}

async function runLauncherContracts({ java, jar, classes, shell, work }) {
  const env = { PATH: process.env.PATH ?? '/usr/bin:/bin', LANG: 'C.UTF-8' };
  const token = randomBytes(24).toString('base64url');
  let cases = 0;
  const spawn = (args = [shell]) => {
    const handle = startProcess(java, ['-classpath', classes + delimiter + jar,
      'com.soklet.interop.apps.AppsFixtureMain', ...args],
    { cwd: work, env, stdin: 'pipe', timeoutMs: 18000, maxOutputBytes: 4096 });
    void handle.completion.catch(() => {});
    handle.child.stdin.on('error', () => {});
    return handle;
  };
  async function cleanup(handle) {
    if (!handle.child.stdin.destroyed) handle.child.stdin.end();
    await handle.completion.catch(() => {});
    await handle.stop();
  }
  async function rejected(input, args = [shell], expectedCode = 1) {
    const handle = spawn(args);
    try {
      if (input !== undefined) handle.child.stdin.end(input);
      const result = await handle.completion;
      assert.equal(result.code, expectedCode);
      assert.equal(result.signal, null);
      assert.ok(result.stdout === '');
      assert.ok(result.stderr === failureLine);
      cases++;
    } finally { await cleanup(handle); }
  }

  const happy = spawn();
  try {
    const ready = readyLine(happy);
    happy.child.stdin.write(token + '\n');
    const address = await ready;
    const url = `http://${address.host}:${address.port}${address.path}`;
    for (const [credential, status] of [['not-the-token', 401], [token, 200]]) {
      const response = await fetch(url, { method: 'POST', signal: AbortSignal.timeout(5000), redirect: 'error',
        headers: { Authorization: `Bearer ${credential}`, Accept: 'application/json, text/event-stream',
          'Content-Type': 'application/json', 'MCP-Protocol-Version': '2026-07-28',
          'Mcp-Method': 'server/discover' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 'launcher-contract', method: 'server/discover',
          params: { _meta: { 'io.modelcontextprotocol/protocolVersion': '2026-07-28',
            'io.modelcontextprotocol/clientCapabilities': {} } } }) });
      const body = await boundedText(response);
      assert.equal(response.status, status);
      assert.ok(!body.includes(token));
    }
    happy.child.stdin.end();
    const result = await happy.completion;
    assert.equal(result.code, 0);
    assert.equal(result.signal, null);
    assert.ok(result.stderr === '');
    assert.ok(result.stdout === JSON.stringify(address) + '\n' + stoppedLine);
    cases++;
  } finally { await cleanup(happy); }

  await rejected('short\n');
  await rejected(token + '!\n');
  await rejected('a'.repeat(129) + '\n');
  await rejected(token);
  await rejected(token + '\n', []);
  await rejected(token + '\n', ['relative-shell.html']);

  const extra = spawn();
  try {
    const ready = readyLine(extra);
    extra.child.stdin.write(token + '\n');
    const address = await ready;
    extra.child.stdin.end('unexpected-control-byte');
    const result = await extra.completion;
    assert.equal(result.code, 1);
    assert.equal(result.signal, null);
    assert.ok(result.stdout === JSON.stringify(address) + '\n');
    assert.ok(result.stderr === failureLine);
    cases++;
  } finally { await cleanup(extra); }

  // No input and no EOF must still stop at the launcher's independent ten-second deadline.
  const started = performance.now();
  await rejected(undefined, [shell], 124);
  assert.ok(performance.now() - started < 17000);
  return { status: 'PASS', cases, requests: 2, scope: 'candidate-public-api-loopback-launcher',
    hostQualification: false };
}

async function runCli(args) {
  const keys = ['--candidate-jar', '--java', '--shell', '--work-dir'];
  if (args.length !== keys.length * 2) throw new Error('APPS_LAUNCHER_ARGUMENTS');
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!keys.includes(args[i]) || options[args[i]] || !args[i + 1])
      throw new Error('APPS_LAUNCHER_ARGUMENTS');
    options[args[i]] = resolve(args[i + 1]);
  }
  const java = options['--java'];
  const jar = options['--candidate-jar'];
  const shell = options['--shell'];
  const work = options['--work-dir'];
  if (existsSync(work)) throw new Error('APPS_LAUNCHER_WORK_EXISTS');
  mkdirSync(work);
  const classes = join(work, 'classes');
  mkdirSync(classes);
  const build = await runProcess(join(dirname(java), 'javac'), ['--release', '17', '-proc:none',
    '-Xlint:all', '-Werror', '-classpath', jar, '-d', classes,
    join(directory, 'src/com/soklet/interop/apps/AppsFixture.java'),
    join(directory, 'src/com/soklet/interop/apps/AppsFixtureMain.java')],
  { cwd: work, env: { PATH: process.env.PATH ?? '/usr/bin:/bin', LANG: 'C.UTF-8' },
    timeoutMs: 120000, maxOutputBytes: 16384 });
  if (build.code !== 0 || build.signal !== null || build.stdout !== '' || build.stderr !== '')
    throw new Error('APPS_LAUNCHER_COMPILE_FAILED');
  return launcherContracts({ java, jar, classes, shell, work });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(await runCli(process.argv.slice(2)))); }
  catch {
    console.error('Apps launcher self-test failed.');
    process.exitCode = 1;
  }
}
