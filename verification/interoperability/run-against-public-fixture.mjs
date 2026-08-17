#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import {
  existsSync,
  lstatSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const protocolVersion = '2026-07-28';
const maximumOutputBytes = 1024 * 1024;
const maximumControlBytes = 64 * 1024;
const maximumControlLines = 16;
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const fixtureMain = 'com.soklet.conformance.McpConformanceFixture';
const supportedClients = new Set(['go', 'typescript']);

function fail(message) {
  throw new Error(message);
}

function realFile(path, description) {
  const absolutePath = resolve(path);
  if (!existsSync(absolutePath))
    fail(`Missing ${description}: ${absolutePath}`);
  const stats = lstatSync(absolutePath);
  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`${description} must be a regular nonsymlink file: ${absolutePath}`);
  return realpathSync(absolutePath);
}

function realDirectory(path, description) {
  const absolutePath = resolve(path);
  if (!existsSync(absolutePath))
    fail(`Missing ${description}: ${absolutePath}`);
  const stats = lstatSync(absolutePath);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    fail(`${description} must be a regular nonsymlink directory: ${absolutePath}`);
  return realpathSync(absolutePath);
}

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function boundedEnvironment(extra = {}) {
  const environment = {
    CI: 'true',
    HOME: process.env.HOME,
    LANG: 'C.UTF-8',
    NO_COLOR: '1',
    PATH: process.env.PATH,
    TMPDIR: process.env.TMPDIR ?? tmpdir(),
    ...extra,
  };
  if (process.env.JAVA_HOME !== undefined)
    environment.JAVA_HOME = process.env.JAVA_HOME;
  return Object.fromEntries(
    Object.entries(environment).filter(([, value]) => value !== undefined),
  );
}

function boundedCollector(stream, description) {
  let output = '';
  let failure;
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    if (failure !== undefined)
      return;
    output += chunk;
    if (Buffer.byteLength(output, 'utf8') > maximumOutputBytes)
      failure = new Error(`${description} exceeded ${maximumOutputBytes} bytes`);
  });
  return {
    output: () => {
      if (failure !== undefined)
        throw failure;
      return output;
    },
  };
}

function lineQueue(stream, description) {
  const lines = [];
  const waiters = [];
  let controlBytes = 0;
  let controlLines = 0;
  let terminal;
  const reader = createInterface({ input: stream, crlfDelay: Number.POSITIVE_INFINITY });

  function settle() {
    while (waiters.length > 0 && (lines.length > 0 || terminal !== undefined)) {
      const waiter = waiters.shift();
      if (lines.length > 0)
        waiter.resolve(lines.shift());
      else
        waiter.reject(terminal);
    }
  }

  reader.on('line', (line) => {
    if (terminal !== undefined)
      return;
    controlBytes += Buffer.byteLength(line, 'utf8') + 1;
    controlLines += 1;
    if (Buffer.byteLength(line, 'utf8') > 8192) {
      terminal = new Error(`${description} emitted an overlong control line`);
      lines.length = 0;
      stream.resume();
      reader.close();
      settle();
      return;
    }
    if (controlBytes > maximumControlBytes || controlLines > maximumControlLines) {
      terminal = new Error(`${description} exceeded its bounded control output`);
      lines.length = 0;
      stream.resume();
      reader.close();
      settle();
      return;
    }
    lines.push(line);
    settle();
  });
  reader.on('close', () => {
    terminal ??= new Error(`${description} closed before the expected control line`);
    settle();
  });

  return {
    async next(timeoutMilliseconds) {
      if (lines.length > 0)
        return lines.shift();
      if (terminal !== undefined)
        throw terminal;
      return await new Promise((resolveLine, rejectLine) => {
        const waiter = {
          reject(error) {
            clearTimeout(timer);
            rejectLine(error);
          },
          resolve(line) {
            clearTimeout(timer);
            resolveLine(line);
          },
        };
        const timer = setTimeout(() => {
          const index = waiters.indexOf(waiter);
          if (index >= 0)
            waiters.splice(index, 1);
          rejectLine(new Error(`${description} timed out after ${timeoutMilliseconds}ms`));
        }, timeoutMilliseconds);
        waiters.push(waiter);
      });
    },
    assertDrained() {
      if (lines.length > 0)
        fail(`${description} emitted unexpected trailing control lines`);
    },
  };
}

function spawnManaged(command, arguments_, options) {
  return spawn(command, arguments_, {
    ...options,
    detached: process.platform !== 'win32',
    shell: false,
  });
}

function signalProcessTree(child, signal) {
  if (child === undefined || child.pid === undefined)
    return;

  try {
    if (process.platform === 'win32')
      child.kill(signal);
    else
      process.kill(-child.pid, signal);
  } catch (error) {
    if (error?.code === 'EPERM') {
      child.sokletProcessGroupUnavailable = true;
      child.kill(signal);
    } else if (error?.code !== 'ESRCH') {
      throw error;
    }
  }
}

function processGroupExists(child) {
  if (child === undefined || child.pid === undefined)
    return false;
  if (process.platform === 'win32' || child.sokletProcessGroupUnavailable === true)
    return child.exitCode === null && child.signalCode === null;
  try {
    process.kill(-child.pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH')
      return false;
    if (error?.code === 'EPERM') {
      child.sokletProcessGroupUnavailable = true;
      return child.exitCode === null && child.signalCode === null;
    }
    throw error;
  }
}

async function waitForProcessGroupExit(child, timeoutMilliseconds) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (processGroupExists(child) && Date.now() < deadline)
    await new Promise((resolveWait) => setTimeout(resolveWait, 25));
  return !processGroupExists(child);
}

async function waitForProcess(child, timeoutMilliseconds, description) {
  return await new Promise((resolveExit, rejectExit) => {
    const timer = setTimeout(() => {
      signalProcessTree(child, 'SIGTERM');
      rejectExit(new Error(`${description} timed out after ${timeoutMilliseconds}ms`));
    }, timeoutMilliseconds);
    child.once('error', (error) => {
      clearTimeout(timer);
      rejectExit(error);
    });
    child.once('close', (code, signal) => {
      clearTimeout(timer);
      resolveExit({ code, signal });
    });
  });
}

async function waitForClose(child, timeoutMilliseconds) {
  if (child.exitCode !== null || child.signalCode !== null)
    return true;

  return await new Promise((resolveClosed) => {
    const onClose = () => {
      clearTimeout(timer);
      resolveClosed(true);
    };
    const timer = setTimeout(() => {
      child.removeListener('close', onClose);
      resolveClosed(false);
    }, timeoutMilliseconds);
    child.once('close', onClose);
  });
}

async function terminate(child) {
  if (child === undefined || child.exitCode !== null || child.signalCode !== null)
    return;
  signalProcessTree(child, 'SIGTERM');
  const closed = await waitForClose(child, 2000);
  const groupExited = await waitForProcessGroupExit(child, 2000);
  if (closed && groupExited)
    return;
  signalProcessTree(child, 'SIGKILL');
  await waitForClose(child, 2000);
  if (!await waitForProcessGroupExit(child, 2000))
    fail('Managed process group remained alive after SIGKILL');
}

export function parseControl(line, expectedEvent) {
  let value;
  try {
    value = JSON.parse(line);
  } catch (error) {
    fail(`Fixture emitted malformed control JSON: ${error instanceof Error ? error.message : error}`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`Fixture emitted a non-object ${expectedEvent} control line: ${line}`);

  const expectedKeys = expectedEvent === 'ready'
    ? ['event', 'format', 'host', 'path', 'port']
    : expectedEvent === 'stopped'
      ? ['clean', 'event', 'format']
      : fail(`Unsupported fixture control event: ${expectedEvent}`);
  const actualKeys = Object.keys(value).sort();
  if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys))
    fail(`Fixture emitted unexpected ${expectedEvent} control keys: ${line}`);

  if (value.format !== 1 || value.event !== expectedEvent)
    fail(`Fixture emitted an unexpected ${expectedEvent} control line: ${line}`);
  return value;
}

export function validateClientOutput(stdout, stderr, expectedClient) {
  if (!supportedClients.has(expectedClient))
    fail(`Unsupported interoperability client: ${expectedClient}`);
  const expectedOutput = `SOKLET_INTEROP_PASS ${protocolVersion} ${expectedClient}\n`;
  if (stdout !== expectedOutput)
    fail(`Interop client stdout was not the exact success marker:\n${stdout}`);
  if (stderr !== '')
    fail(`Interop client wrote unexpected stderr:\n${stderr}`);
  return expectedOutput;
}

function validateSdkPin(clientName, sdkPin) {
  const expected = clientName === 'typescript'
    ? {
      artifactChecksum: /^sha512-[A-Za-z0-9+/]{86}==$/,
      artifactIdentity: 'npm:@modelcontextprotocol/client@2.0.0',
    }
    : clientName === 'go'
      ? {
        artifactChecksum: /^h1:[A-Za-z0-9+/]{43}=$/,
        artifactIdentity: 'github.com/modelcontextprotocol/go-sdk@v1.7.0',
      }
      : fail(`Unsupported interoperability client: ${clientName}`);

  if (sdkPin?.artifactIdentity !== expected.artifactIdentity
      || !expected.artifactChecksum.test(sdkPin?.artifactChecksum ?? '')
      || !/^[0-9a-f]{40}$/.test(sdkPin?.commit ?? '')) {
    fail(`Invalid ${clientName} SDK artifact or commit pin`);
  }
}

export function interoperabilityEvidenceLine(candidateSha256, clientName, sdkPin) {
  if (!/^[0-9a-f]{64}$/.test(candidateSha256))
    fail('Candidate SHA-256 must contain exactly 64 lowercase hexadecimal characters');
  if (!supportedClients.has(clientName))
    fail(`Unsupported interoperability client: ${clientName}`);
  validateSdkPin(clientName, sdkPin);
  return `SOKLET_INTEROP_EVIDENCE ${JSON.stringify({
    candidateSha256,
    client: clientName,
    fixtureScenario: 'tools-list',
    fixtureShutdown: 'CLEAN',
    formatVersion: 1,
    protocolVersion,
    sdkArtifactChecksum: sdkPin.artifactChecksum,
    sdkArtifactIdentity: sdkPin.artifactIdentity,
    sdkCommit: sdkPin.commit,
    tool: 'test_simple_text',
  })}\n`;
}

async function fixtureControl(queue, fixture, fixtureStderr, event, timeoutMilliseconds) {
  try {
    return parseControl(await queue.next(timeoutMilliseconds), event);
  } catch (error) {
    if (fixture.exitCode === null && fixture.signalCode === null) {
      try {
        await waitForProcess(fixture, 2000, `public fixture ${event} failure`);
      } catch {
        // The original control failure remains authoritative; final cleanup
        // escalates if the fixture did not exit on its own.
      }
    }
    const detail = error instanceof Error ? error.message : String(error);
    fail(`Public fixture failed before ${event}: ${detail}\n${fixtureStderr.output()}`);
  }
}

async function main(arguments_) {
  if (arguments_.length < 7)
    fail('Usage: run-against-public-fixture.mjs <candidate-jar> <client-cwd> <client-name> <sdk-artifact-identity> <sdk-artifact-checksum> <sdk-commit> <client-command> [client-args...]');

  const candidateJar = realFile(arguments_[0], 'candidate JAR');
  const clientWorkingDirectory = realDirectory(arguments_[1], 'client working directory');
  const clientName = arguments_[2];
  if (!supportedClients.has(clientName))
    fail(`Unsupported interoperability client: ${clientName}`);
  const sdkPin = {
    artifactIdentity: arguments_[3],
    artifactChecksum: arguments_[4],
    commit: arguments_[5],
  };
  validateSdkPin(clientName, sdkPin);
  const clientCommand = arguments_[6];
  const clientArguments = arguments_.slice(7);
  const candidateSha256 = sha256File(candidateJar);
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-interop-fixture-'));
  const fixtureOutput = resolve(scratch, 'public-fixture');
  let build;
  let client;
  let fixture;

  try {
    build = spawnManaged(
      'sh',
      [resolve(projectRoot, 'conformance/official/build-public-fixture.sh'), candidateJar, fixtureOutput],
      {
        cwd: projectRoot,
        env: boundedEnvironment(),
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    const buildStdout = boundedCollector(build.stdout, 'public fixture build stdout');
    const buildStderr = boundedCollector(build.stderr, 'public fixture build stderr');
    const buildExit = await waitForProcess(build, 120000, 'public fixture build');
    await terminate(build);
    build = undefined;
    if (buildExit.code !== 0 || buildExit.signal !== null) {
      fail(`Public fixture build failed (${buildExit.code ?? buildExit.signal}):\n${buildStdout.output()}\n${buildStderr.output()}`);
    }

    const expectedClasspath = `${fixtureOutput}/classes${delimiter}${candidateJar}`;
    if (buildStdout.output() !== `${expectedClasspath}\n` || buildStderr.output() !== '')
      fail(`Public fixture build emitted unexpected output:\n${buildStdout.output()}\n${buildStderr.output()}`);
    const classpath = expectedClasspath;

    fixture = spawnManaged(
      'java',
      ['-cp', classpath, fixtureMain, '--scenario', 'tools-list'],
      {
        cwd: projectRoot,
        env: boundedEnvironment(),
        stdio: ['pipe', 'pipe', 'pipe'],
      },
    );
    const fixtureLines = lineQueue(fixture.stdout, 'public fixture');
    const fixtureStderr = boundedCollector(fixture.stderr, 'public fixture stderr');
    const ready = await fixtureControl(
      fixtureLines,
      fixture,
      fixtureStderr,
      'ready',
      30000,
    );
    if (ready.host !== '127.0.0.1'
        || !Number.isSafeInteger(ready.port) || ready.port < 1 || ready.port > 65535
        || ready.path !== '/mcp') {
      fail(`Fixture published an invalid address: ${JSON.stringify(ready)}`);
    }

    client = spawnManaged(clientCommand, clientArguments, {
      cwd: clientWorkingDirectory,
      env: boundedEnvironment({
        SOKLET_INTEROP_EXPECTED_TOOL: 'test_simple_text',
        SOKLET_INTEROP_PROTOCOL_VERSION: protocolVersion,
        SOKLET_INTEROP_URL: `http://${ready.host}:${ready.port}${ready.path}`,
      }),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const clientStdout = boundedCollector(client.stdout, 'interop client stdout');
    const clientStderr = boundedCollector(client.stderr, 'interop client stderr');
    const clientExit = await waitForProcess(client, 120000, 'interop client');
    await terminate(client);
    client = undefined;
    if (clientExit.code !== 0 || clientExit.signal !== null) {
      fail(`Interop client failed (${clientExit.code ?? clientExit.signal}):\n${clientStdout.output()}\n${clientStderr.output()}`);
    }
    const verifiedClientOutput = validateClientOutput(
      clientStdout.output(),
      clientStderr.output(),
      clientName,
    );

    fixture.stdin.end();
    const stopped = await fixtureControl(
      fixtureLines,
      fixture,
      fixtureStderr,
      'stopped',
      30000,
    );
    if (stopped.clean !== true)
      fail(`Fixture did not report a clean stop: ${JSON.stringify(stopped)}`);
    const fixtureExit = await waitForProcess(fixture, 30000, 'public fixture');
    await terminate(fixture);
    fixture = undefined;
    if (fixtureExit.code !== 0 || fixtureExit.signal !== null)
      fail(`Fixture failed (${fixtureExit.code ?? fixtureExit.signal}):\n${fixtureStderr.output()}`);
    if (fixtureStderr.output() !== '')
      fail(`Fixture wrote unexpected stderr:\n${fixtureStderr.output()}`);
    fixtureLines.assertDrained();
    if (sha256File(candidateJar) !== candidateSha256)
      fail('Candidate JAR changed during interoperability verification');

    process.stdout.write(verifiedClientOutput);
    process.stdout.write(interoperabilityEvidenceLine(candidateSha256, clientName, sdkPin));
  } finally {
    await terminate(build);
    await terminate(client);
    await terminate(fixture);
    if (scratch.startsWith(resolve(tmpdir(), 'soklet-interop-fixture-')))
      rmSync(scratch, { force: true, recursive: true });
  }
}

if (fileURLToPath(import.meta.url) === resolve(process.argv[1] ?? '')) {
  try {
    await main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
