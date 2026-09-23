#!/usr/bin/env node

// Proposed P0-C supplement. This captures a real socket control without
// changing the official suite, its checks, or Soklet's conformance gate.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, renameSync,
  writeFileSync,
} from 'node:fs';
import { request as httpRequest } from 'node:http';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { fixtureClassesTreeSha256 } from './p0c-disposition.mjs';

const thisFile = fileURLToPath(import.meta.url);
const officialRoot = resolve(dirname(thisFile), '..');
const fixtureSourcePath = join(officialRoot, 'public-fixture-src', 'com',
  'soklet', 'conformance', 'McpConformanceFixture.java');
const buildScriptPath = join(officialRoot, 'build-public-fixture.sh');
const pinsPath = join(officialRoot, 'upstream-pins.json');
const fixtureMain = 'com.soklet.conformance.McpConformanceFixture';
const tool = 'test_missing_elicitation_capability';
const maximumLogBytes = 1024 * 1024;
const maximumBodyBytes = 1024 * 1024;
const buildTimeoutMilliseconds = 120_000;
const readyTimeoutMilliseconds = 15_000;
const stopTimeoutMilliseconds = 10_000;
const requestTimeoutMilliseconds = 10_000;

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function fileIdentity(path) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`Expected a regular file: ${path}`);
  const bytes = readFileSync(path);
  return { path, bytes: bytes.length, sha256: sha256(bytes) };
}

function requireDirectory(path) {
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    throw new Error(`Expected a real directory: ${path}`);
}

function requireAbsolute(path, description) {
  if (typeof path !== 'string' || !isAbsolute(path))
    throw new Error(`${description} must be an absolute path`);
  return resolve(path);
}

function requireRunId(runId) {
  if (typeof runId !== 'string'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(runId))
    throw new Error('runId must be a short, filesystem-safe identifier');
  return runId;
}

function writeJson(path, value) {
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
  renameSync(temporary, path);
}

function captureStream(stream, maximumBytes, onOverflow) {
  const chunks = [];
  let bytes = 0;
  let overflow = false;
  stream.on('data', (chunk) => {
    bytes += chunk.length;
    if (bytes > maximumBytes) {
      overflow = true;
      onOverflow();
      return;
    }
    chunks.push(chunk);
  });
  return {
    bytes: () => Buffer.concat(chunks),
    assertWithinLimit: () => {
      if (overflow) throw new Error('Fixture or build output exceeded its byte limit');
    },
  };
}

function boundedEnvironment(javaHome) {
  const environment = {
    JAVA_HOME: javaHome,
    PATH: `${join(javaHome, 'bin')}:${process.env.PATH ?? ''}`,
    NO_COLOR: '1',
  };
  for (const name of ['LANG', 'LC_ALL', 'TMPDIR'])
    if (process.env[name] !== undefined) environment[name] = process.env[name];
  return environment;
}

function signalProcessGroup(child, signal) {
  if (child.pid === undefined) return;
  try {
    if (process.platform === 'win32') child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

function installChildSignalHandlers(child) {
  const handlers = ['SIGINT', 'SIGTERM'].map((signal) => {
    const handler = () => signalProcessGroup(child, signal);
    process.on(signal, handler);
    return [signal, handler];
  });
  return () => {
    for (const [signal, handler] of handlers)
      process.removeListener(signal, handler);
  };
}

function processTreeAlive(child) {
  if (child.pid === undefined) return false;
  if (process.platform === 'win32')
    return child.exitCode === null && child.signalCode === null;
  try {
    process.kill(-child.pid, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    if (error.code === 'EPERM') return true;
    throw error;
  }
}

async function waitForTreeExit(child, milliseconds) {
  const deadline = Date.now() + milliseconds;
  while (processTreeAlive(child)) {
    if (Date.now() >= deadline)
      throw new Error('Fixture process group did not exit within its bound');
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 20));
  }
}

function childCompletion(child) {
  return new Promise((resolveResult, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) => resolveResult({ exitCode: code, signal }));
  });
}

async function withDeadline(promise, milliseconds, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function forceStop(child, completion) {
  if (!processTreeAlive(child)) return;
  signalProcessGroup(child, 'SIGTERM');
  try {
    await waitForTreeExit(child, 2_000);
  } catch {
    signalProcessGroup(child, 'SIGKILL');
    await waitForTreeExit(child, 2_000);
  }
  await withDeadline(completion, 2_000, 'Fixture close after process-tree cleanup');
}

async function runBuild(command, args, { outputDirectory, javaHome }) {
  const child = spawn(command, args, {
    cwd: officialRoot,
    env: boundedEnvironment(javaHome),
    detached: process.platform !== 'win32',
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const completion = childCompletion(child);
  const removeSignalHandlers = installChildSignalHandlers(child);
  const stdout = captureStream(child.stdout, maximumLogBytes,
    () => signalProcessGroup(child, 'SIGTERM'));
  const stderr = captureStream(child.stderr, maximumLogBytes,
    () => signalProcessGroup(child, 'SIGTERM'));
  let exit;
  try {
    exit = await withDeadline(completion, buildTimeoutMilliseconds,
      'Public fixture build');
  } finally {
    try {
      await forceStop(child, completion);
    } finally {
      removeSignalHandlers();
      writeFileSync(join(outputDirectory, 'build.stdout.log'), stdout.bytes(),
        { flag: 'wx' });
      writeFileSync(join(outputDirectory, 'build.stderr.log'), stderr.bytes(),
        { flag: 'wx' });
    }
  }
  stdout.assertWithinLimit();
  stderr.assertWithinLimit();
  if (exit.exitCode !== 0 || exit.signal !== null)
    throw new Error(`Public fixture build exited ${exit.exitCode}/${exit.signal}`);
  return exit;
}

function classTreeIdentity(root) {
  requireDirectory(root);
  const entries = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      const stats = lstatSync(path);
      if (stats.isSymbolicLink()) throw new Error('Fixture classes contain a symlink');
      if (stats.isDirectory()) visit(path);
      else if (stats.isFile()) entries.push(path);
      else throw new Error('Fixture classes contain a nonregular entry');
    }
  };
  visit(root);
  if (entries.length === 0) throw new Error('Fixture build produced no class files');
  const rows = entries.map((path) => ({
    path: relative(root, path).split('\\').join('/'),
    sha256: fileIdentity(path).sha256,
  }));
  rows.sort((left, right) => Buffer.compare(Buffer.from(left.path),
    Buffer.from(right.path)));
  return {
    fileCount: rows.length,
    sha256: sha256(Buffer.from(rows.map((row) =>
      `${row.sha256}  ${row.path}\n`).join(''), 'utf8')),
  };
}

export async function preparePublicFixture({ candidateJarPath, outputDirectory, javaHome }) {
  candidateJarPath = requireAbsolute(candidateJarPath, 'candidateJarPath');
  outputDirectory = requireAbsolute(outputDirectory, 'outputDirectory');
  javaHome = requireAbsolute(javaHome, 'javaHome');
  requireDirectory(outputDirectory);
  requireDirectory(javaHome);
  const javaExecutable = join(javaHome, 'bin', 'java');
  fileIdentity(javaExecutable);
  const candidateJar = fileIdentity(candidateJarPath);
  const fixtureSource = fileIdentity(fixtureSourcePath);
  const buildScript = fileIdentity(buildScriptPath);
  const buildOutputDirectory = join(outputDirectory, 'fixture-build');
  if (existsSync(buildOutputDirectory))
    throw new Error('Fixture build output must not already exist');
  mkdirSync(buildOutputDirectory);
  await runBuild('/bin/sh',
    [buildScriptPath, candidateJarPath, buildOutputDirectory],
    { outputDirectory, javaHome });
  const fixtureClassDirectory = join(buildOutputDirectory, 'classes');
  const fixtureClass = fileIdentity(join(fixtureClassDirectory, 'com', 'soklet',
    'conformance', 'McpConformanceFixture.class'));
  const fixtureClasses = classTreeIdentity(fixtureClassDirectory);
  if (fixtureClasses.sha256 !== fixtureClassesTreeSha256(fixtureClassDirectory))
    throw new Error('Fixture class-tree digest differs from the disposition verifier');
  if (fileIdentity(candidateJarPath).sha256 !== candidateJar.sha256)
    throw new Error('Candidate JAR changed during fixture compilation');
  const prepared = {
    candidateJarPath,
    candidateJarSha256: candidateJar.sha256,
    candidateJarBytes: candidateJar.bytes,
    fixtureSourceSha256: fixtureSource.sha256,
    fixtureSourcePath,
    fixtureClassPath: fixtureClass.path,
    fixtureClassSha256: fixtureClass.sha256,
    fixtureClassDirectory,
    fixtureClassesDirectory: fixtureClassDirectory,
    fixtureClassesSha256: fixtureClasses.sha256,
    fixtureClassesFileCount: fixtureClasses.fileCount,
    buildScriptSha256: buildScript.sha256,
    classpath: `${fixtureClassDirectory}:${candidateJarPath}`,
    javaExecutable,
    javaHome,
  };
  writeJson(join(outputDirectory, 'fixture-identity.json'), prepared);
  return prepared;
}

export async function startPublicFixture({ prepared, outputDirectory }) {
  outputDirectory = requireAbsolute(outputDirectory, 'outputDirectory');
  requireDirectory(outputDirectory);
  if (fileIdentity(prepared.candidateJarPath).sha256 !== prepared.candidateJarSha256)
    throw new Error('Candidate JAR changed before fixture launch');
  const child = spawn(prepared.javaExecutable,
    ['-ea', '-classpath', prepared.classpath, fixtureMain,
      '--scenario', 'server-stateless'], {
      cwd: officialRoot,
      env: boundedEnvironment(prepared.javaHome),
      detached: process.platform !== 'win32',
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  const completion = childCompletion(child);
  const removeSignalHandlers = installChildSignalHandlers(child);
  const stdout = captureStream(child.stdout, maximumLogBytes,
    () => signalProcessGroup(child, 'SIGTERM'));
  const stderr = captureStream(child.stderr, maximumLogBytes,
    () => signalProcessGroup(child, 'SIGTERM'));
  let pending = '';
  const lines = [];
  let resolveReady;
  let rejectReady;
  const readyPromise = new Promise((resolveResult, reject) => {
    resolveReady = resolveResult;
    rejectReady = reject;
  });
  child.stdout.on('data', (chunk) => {
    pending += chunk.toString('utf8');
    while (pending.includes('\n')) {
      const index = pending.indexOf('\n');
      const line = pending.slice(0, index);
      pending = pending.slice(index + 1);
      lines.push(line);
      if (lines.length === 1) {
        try {
          const ready = JSON.parse(line);
          if (!isDeepStrictEqual(Object.keys(ready).sort(),
            ['event', 'format', 'host', 'path', 'port'])
              || ready.format !== 1 || ready.event !== 'ready'
              || ready.host !== '127.0.0.1' || ready.path !== '/mcp'
              || !Number.isInteger(ready.port) || ready.port < 1
              || ready.port > 65535)
            throw new Error('Fixture emitted an invalid ready record');
          resolveReady(ready);
        } catch (error) {
          rejectReady(error);
        }
      }
    }
  });
  completion.then(() => rejectReady(new Error('Fixture exited before ready')),
    rejectReady);
  const fixture = { child, completion, stdout, stderr, lines,
    pendingControlText: () => pending,
    prepared, outputDirectory, removeSignalHandlers };
  try {
    fixture.ready = await withDeadline(readyPromise, readyTimeoutMilliseconds,
      'Fixture readiness');
    fixture.endpoint = `http://127.0.0.1:${fixture.ready.port}/mcp`;
    return fixture;
  } catch (error) {
    try {
      await forceStop(child, completion);
    } finally {
      removeSignalHandlers();
      writeFileSync(join(outputDirectory, 'fixture.stdout.log'), stdout.bytes(),
        { flag: 'wx' });
      writeFileSync(join(outputDirectory, 'fixture.stderr.log'), stderr.bytes(),
        { flag: 'wx' });
    }
    throw error;
  }
}

export async function stopPublicFixture({ fixture, outputDirectory }) {
  outputDirectory = requireAbsolute(outputDirectory, 'outputDirectory');
  requireDirectory(outputDirectory);
  let forcedCleanup = false;
  let exit;
  try {
    fixture.child.stdin.end();
    exit = await withDeadline(fixture.completion, stopTimeoutMilliseconds,
      'Fixture graceful stop');
    if (processTreeAlive(fixture.child)) {
      forcedCleanup = true;
      await forceStop(fixture.child, fixture.completion);
    }
  } catch {
    forcedCleanup = true;
    await forceStop(fixture.child, fixture.completion);
    try { exit = await fixture.completion; } catch { exit = null; }
  } finally {
    fixture.removeSignalHandlers();
    writeFileSync(join(outputDirectory, 'fixture.stdout.log'),
      fixture.stdout.bytes(), { flag: 'wx' });
    writeFileSync(join(outputDirectory, 'fixture.stderr.log'),
      fixture.stderr.bytes(), { flag: 'wx' });
  }
  fixture.stdout.assertWithinLimit();
  fixture.stderr.assertWithinLimit();
  const stopped = fixture.lines.length === 2
    && fixture.pendingControlText() === '' && (() => {
    try {
      const event = JSON.parse(fixture.lines[1]);
      return isDeepStrictEqual(event,
        { format: 1, event: 'stopped', clean: true });
    } catch { return false; }
  })();
  const processReceipt = {
    ready: fixture.ready !== undefined,
    stopped,
    exitCode: exit?.exitCode ?? null,
    signal: exit?.signal ?? null,
    forcedCleanup,
  };
  writeJson(join(outputDirectory, 'fixture-cleanup.json'), {
    ...processReceipt,
    stdoutPath: join(outputDirectory, 'fixture.stdout.log'),
    stderrPath: join(outputDirectory, 'fixture.stderr.log'),
    stdoutSha256: fileIdentity(join(outputDirectory, 'fixture.stdout.log')).sha256,
    stderrSha256: fileIdentity(join(outputDirectory, 'fixture.stderr.log')).sha256,
  });
  return processReceipt;
}

function requestBody(id, capabilities) {
  return Buffer.from(JSON.stringify({
    jsonrpc: '2.0', id, method: 'tools/call',
    params: {
      _meta: {
        'io.modelcontextprotocol/protocolVersion': '2026-07-28',
        'io.modelcontextprotocol/clientCapabilities': capabilities,
      },
      name: tool,
      arguments: {},
    },
  }), 'utf8');
}

async function exchange(endpoint, body) {
  const url = new URL(endpoint);
  const headers = {
    Host: url.host,
    'Content-Type': 'application/json; charset=UTF-8',
    Accept: 'application/json, text/event-stream',
    'MCP-Protocol-Version': '2026-07-28',
    'Mcp-Method': 'tools/call',
    'Mcp-Name': tool,
    'Content-Length': String(body.length),
  };
  return await new Promise((resolveResult, reject) => {
    let timer;
    const rejectAndClear = (error) => {
      clearTimeout(timer);
      reject(error);
    };
    const request = httpRequest(url, {
      method: 'POST', headers,
    }, (response) => {
      const chunks = [];
      let size = 0;
      response.on('data', (chunk) => {
        size += chunk.length;
        if (size > maximumBodyBytes) {
          request.destroy(new Error('Control response exceeded byte limit'));
          return;
        }
        chunks.push(chunk);
      });
      response.once('end', () => {
        clearTimeout(timer);
        resolveResult({
          requestHeaders: headers,
          statusCode: response.statusCode,
          responseHeaders: response.headers,
          responseRawHeaders: response.rawHeaders,
          responseBody: Buffer.concat(chunks),
        });
      });
      response.once('error', rejectAndClear);
    });
    request.once('error', rejectAndClear);
    request.setTimeout(requestTimeoutMilliseconds,
      () => request.destroy(new Error('Control request timed out')));
    timer = setTimeout(() => request.destroy(
      new Error('Control request exceeded absolute deadline')),
    requestTimeoutMilliseconds);
    request.end(body);
  });
}

function exactUtf8(bytes, description) {
  const value = bytes.toString('utf8');
  if (!Buffer.from(value, 'utf8').equals(bytes))
    throw new Error(`${description} is not exact UTF-8`);
  return value;
}

export function validateControlExchange(which, requestBodyText, responseBodyText,
  httpStatus) {
  const request = JSON.parse(requestBodyText);
  const response = JSON.parse(responseBodyText);
  const expectedCapabilities = which === 'negative'
    ? {} : { elicitation: { form: {} } };
  if (request.method !== 'tools/call' || request.params?.name !== tool
      || !isDeepStrictEqual(request.params.arguments, {})
      || request.params._meta?.['io.modelcontextprotocol/protocolVersion']
        !== '2026-07-28'
      || !isDeepStrictEqual(request.params._meta?.[
        'io.modelcontextprotocol/clientCapabilities'], expectedCapabilities)
      || !Number.isSafeInteger(request.id) || request.id <= 0
      || response.jsonrpc !== '2.0' || response.id !== request.id)
    throw new Error(`${which} Elicitation request/response identity mismatch`);
  if (which === 'negative') {
    if (httpStatus !== 400 || !isDeepStrictEqual(response.error, {
      code: -32021,
      message: 'Missing required client capability',
      data: { requiredCapabilities: { elicitation: { form: {} } } },
    })
        || response.result !== undefined)
      throw new Error('Negative Elicitation control did not reject the missing form');
  } else if (which === 'positive') {
    if (httpStatus !== 200 || response.error !== undefined
        || response.result?.resultType !== 'complete')
      throw new Error('Positive Elicitation control did not complete');
  } else {
    throw new Error('Unknown Elicitation control case');
  }
  return request.id;
}

export async function captureElicitationSocketExchanges({
  fixture, prepared, runId, outputDirectory,
}) {
  requireRunId(runId);
  outputDirectory = requireAbsolute(outputDirectory, 'outputDirectory');
  requireDirectory(outputDirectory);
  if (fixture.endpoint !== `http://127.0.0.1:${fixture.ready.port}/mcp`)
    throw new Error('Fixture endpoint changed');
  if (prepared.candidateJarSha256 !== fixture.prepared.candidateJarSha256
      || prepared.fixtureClassesSha256 !== fixture.prepared.fixtureClassesSha256)
    throw new Error('Prepared fixture identity mismatch');
  const pins = JSON.parse(readFileSync(pinsPath, 'utf8'));
  const exchanges = {};
  const manifestRows = [];
  for (const [which, id, capabilities] of [
    ['negative', 9101, {}],
    ['positive', 9102, { elicitation: { form: {} } }],
  ]) {
    const body = requestBody(id, capabilities);
    const requestBodyPath = join(outputDirectory, `${which}.request-body.json`);
    writeFileSync(requestBodyPath, body, { flag: 'wx' });
    const observed = await exchange(fixture.endpoint, body);
    const responseBodyPath = join(outputDirectory, `${which}.response-body.json`);
    writeFileSync(responseBodyPath, observed.responseBody, { flag: 'wx' });
    const requestHeadersPath = join(outputDirectory, `${which}.request-headers.json`);
    const responseHeadersPath = join(outputDirectory, `${which}.response-headers.json`);
    writeJson(requestHeadersPath, observed.requestHeaders);
    writeJson(responseHeadersPath, {
      statusCode: observed.statusCode,
      headers: observed.responseHeaders,
      rawHeaders: observed.responseRawHeaders,
    });
    const requestBodyText = exactUtf8(body, `${which} request body`);
    const responseBodyText = exactUtf8(observed.responseBody,
      `${which} response body`);
    validateControlExchange(which, requestBodyText, responseBodyText,
      observed.statusCode);
    exchanges[which] = {
      requestBody: requestBodyText,
      responseBody: responseBodyText,
      httpStatus: observed.statusCode,
    };
    manifestRows.push({
      case: which,
      requestBody: fileIdentity(requestBodyPath),
      responseBody: fileIdentity(responseBodyPath),
      requestHeaders: fileIdentity(requestHeadersPath),
      responseHeaders: fileIdentity(responseHeadersPath),
      httpStatus: observed.statusCode,
    });
  }
  const negativeId = JSON.parse(exchanges.negative.requestBody).id;
  const positiveId = JSON.parse(exchanges.positive.requestBody).id;
  if (negativeId === positiveId) throw new Error('Control request IDs must differ');
  const receipt = {
    formatVersion: 1,
    captureKind: 'REAL_LOOPBACK_SOCKET',
    scenario: 'server-stateless',
    protocolVersion: pins.protocolVersion,
    suiteCommit: pins.officialConformanceSuite.commit,
    suiteSourceTreeSha256: pins.officialConformanceSuite.sourceTree.sha256,
    candidateJarSha256: prepared.candidateJarSha256,
    fixtureSourceSha256: prepared.fixtureSourceSha256,
    fixtureClassesSha256: prepared.fixtureClassesSha256,
    runId,
    endpoint: fixture.endpoint,
    tool,
    negative: exchanges.negative,
    positive: exchanges.positive,
  };
  const manifest = {
    formatVersion: 1,
    runId,
    endpoint: fixture.endpoint,
    candidateJar: {
      path: prepared.candidateJarPath,
      bytes: prepared.candidateJarBytes,
      sha256: prepared.candidateJarSha256,
    },
    fixture: {
      sourcePath: prepared.fixtureSourcePath,
      sourceSha256: prepared.fixtureSourceSha256,
      classPath: prepared.fixtureClassPath,
      classSha256: prepared.fixtureClassSha256,
      classesSha256: prepared.fixtureClassesSha256,
      classesFileCount: prepared.fixtureClassesFileCount,
      buildScriptSha256: prepared.buildScriptSha256,
    },
    exchanges: manifestRows,
  };
  writeJson(join(outputDirectory, 'capture-raw-manifest.json'), manifest);
  return { receipt, manifest };
}

export function writeControlReceipt({ receipt, manifest, process, outputDirectory }) {
  outputDirectory = requireAbsolute(outputDirectory, 'outputDirectory');
  requireDirectory(outputDirectory);
  if (manifest === undefined)
    manifest = JSON.parse(readFileSync(join(outputDirectory,
      'capture-raw-manifest.json'), 'utf8'));
  const finalized = { ...receipt, process };
  writeJson(join(outputDirectory, 'control-receipt.json'), finalized);
  writeJson(join(outputDirectory, 'capture-manifest.json'), {
    ...manifest,
    process,
    receiptSha256: fileIdentity(join(outputDirectory,
      'control-receipt.json')).sha256,
  });
  return finalized;
}

function parseArguments(args) {
  if (args.length !== 8) throw new Error('Expected four name/value arguments');
  const values = new Map();
  for (let i = 0; i < args.length; i += 2) {
    if (!['--run-id', '--candidate-jar', '--java-home', '--output-dir']
      .includes(args[i]) || values.has(args[i]))
      throw new Error(`Unknown or duplicate argument: ${args[i]}`);
    values.set(args[i], args[i + 1]);
  }
  if (values.size !== 4) throw new Error('All four arguments are required');
  return {
    runId: requireRunId(values.get('--run-id')),
    candidateJarPath: requireAbsolute(values.get('--candidate-jar'),
      '--candidate-jar'),
    javaHome: requireAbsolute(values.get('--java-home'), '--java-home'),
    outputDirectory: requireAbsolute(values.get('--output-dir'), '--output-dir'),
  };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (existsSync(options.outputDirectory))
    throw new Error('Output directory must not already exist');
  requireDirectory(dirname(options.outputDirectory));
  mkdirSync(options.outputDirectory, { mode: 0o700 });
  const fixtureDirectory = join(options.outputDirectory, 'fixture');
  const controlDirectory = join(options.outputDirectory, 'control');
  mkdirSync(fixtureDirectory);
  mkdirSync(controlDirectory);
  const prepared = await preparePublicFixture({
    candidateJarPath: options.candidateJarPath,
    outputDirectory: fixtureDirectory,
    javaHome: options.javaHome,
  });
  const fixture = await startPublicFixture({
    prepared, outputDirectory: fixtureDirectory,
  });
  let captured;
  let captureError;
  try {
    captured = await captureElicitationSocketExchanges({
      fixture, prepared, runId: options.runId, outputDirectory: controlDirectory,
    });
  } catch (error) {
    captureError = error;
  }
  const processReceipt = await stopPublicFixture({
    fixture, outputDirectory: fixtureDirectory,
  });
  if (captureError !== undefined) throw captureError;
  const receipt = writeControlReceipt({
    ...captured, process: processReceipt, outputDirectory: controlDirectory,
  });
  if (!processReceipt.ready || !processReceipt.stopped
      || processReceipt.exitCode !== 0 || processReceipt.signal !== null
      || processReceipt.forcedCleanup)
    throw new Error('Fixture did not stop cleanly');
  process.stdout.write(`${join(controlDirectory, 'control-receipt.json')}\n`);
  return receipt;
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === thisFile)
  main().catch((error) => {
    console.error(`Elicitation socket capture failed: ${error.message}`);
    process.exitCode = 1;
  });
