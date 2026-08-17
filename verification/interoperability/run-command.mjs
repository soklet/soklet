#!/usr/bin/env node

import { spawn } from 'node:child_process';
import { existsSync, lstatSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';

const maximumOutputBytes = 16 * 1024 * 1024;
const terminationGraceMilliseconds = 2000;

function fail(message) {
  throw new Error(message);
}

function realDirectory(path) {
  const absolutePath = resolve(path);
  if (!existsSync(absolutePath))
    fail(`Missing command working directory: ${absolutePath}`);
  const stats = lstatSync(absolutePath);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    fail(`Command working directory must be a regular nonsymlink directory: ${absolutePath}`);
  return realpathSync(absolutePath);
}

function signalProcessGroup(child, signal) {
  if (child.pid === undefined)
    return;
  try {
    if (process.platform === 'win32')
      child.kill(signal);
    else
      process.kill(-child.pid, signal);
  } catch (error) {
    if (error?.code !== 'ESRCH')
      throw error;
  }
}

function processGroupExists(child) {
  if (process.platform === 'win32' || child.pid === undefined)
    return false;
  try {
    process.kill(-child.pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH')
      return false;
    if (error?.code === 'EPERM')
      return true;
    throw error;
  }
}

async function waitForProcessGroupExit(child, timeoutMilliseconds) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (processGroupExists(child) && Date.now() < deadline)
    await new Promise((resolveWait) => setTimeout(resolveWait, 25));
  return !processGroupExists(child);
}

async function cleanProcessGroup(child) {
  if (process.platform === 'win32')
    return;
  signalProcessGroup(child, 'SIGTERM');
  if (await waitForProcessGroupExit(child, terminationGraceMilliseconds))
    return;
  signalProcessGroup(child, 'SIGKILL');
  if (!await waitForProcessGroupExit(child, terminationGraceMilliseconds))
    fail('Command process group remained alive after SIGKILL');
}

async function run(arguments_) {
  if (arguments_.length < 3)
    fail('Usage: run-command.mjs <timeout-seconds> <working-directory> <command> [args...]');

  const timeoutSeconds = Number(arguments_[0]);
  if (!Number.isSafeInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 1800)
    fail('Command timeout must be a whole number from 1 through 1,800 seconds');
  const workingDirectory = realDirectory(arguments_[1]);
  const command = arguments_[2];
  const commandArguments = arguments_.slice(3);
  const child = spawn(command, commandArguments, {
    cwd: workingDirectory,
    detached: process.platform !== 'win32',
    env: process.env,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let failure;
  let killTimer;
  let outputBytes = 0;

  function terminate(message) {
    if (failure !== undefined)
      return;
    failure = message;
    signalProcessGroup(child, 'SIGTERM');
    killTimer = setTimeout(() => signalProcessGroup(child, 'SIGKILL'), terminationGraceMilliseconds);
  }

  function forward(stream, destination) {
    stream.on('data', (chunk) => {
      outputBytes += chunk.length;
      if (outputBytes > maximumOutputBytes) {
        terminate(`Command output exceeded ${maximumOutputBytes} bytes`);
        return;
      }
      destination.write(chunk);
    });
  }

  forward(child.stdout, process.stdout);
  forward(child.stderr, process.stderr);
  const timeout = setTimeout(
    () => terminate(`Command timed out after ${timeoutSeconds} seconds`),
    timeoutSeconds * 1000,
  );

  const exit = await new Promise((resolveExit, rejectExit) => {
    child.once('error', rejectExit);
    child.once('close', (code, signal) => resolveExit({ code, signal }));
  }).finally(() => {
    clearTimeout(timeout);
    clearTimeout(killTimer);
  });

  // A leader must not be able to leave detached descendants in its dedicated
  // group, regardless of whether it returned success or failed a bound.
  await cleanProcessGroup(child);

  if (failure !== undefined)
    fail(failure);
  if (exit.code !== 0 || exit.signal !== null)
    fail(`Command failed (${exit.code ?? exit.signal}): ${command}`);
}

try {
  await run(process.argv.slice(2));
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
