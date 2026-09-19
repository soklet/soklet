import { spawn } from 'node:child_process';
import { existsSync, lstatSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { TextDecoder } from 'node:util';

const terminationGraceMs = 2000;
const defaultMaxOutputBytes = 1024 * 1024;
const maximumTimeoutMs = 30 * 60 * 1000;
const utf8 = new TextDecoder('utf-8', { fatal: true });

function invalidOptions() {
  throw new Error('Invalid managed process options');
}

function validatedOptions(command, args, options) {
  if (process.platform === 'win32')
    throw new Error('Managed process group supervision is unavailable');
  if (typeof command !== 'string' || command.length === 0
      || !Array.isArray(args) || args.some((arg) => typeof arg !== 'string')
      || options === null || typeof options !== 'object'
      || typeof options.cwd !== 'string' || typeof options.env !== 'object'
      || options.env === null || Array.isArray(options.env)
      || !Number.isSafeInteger(options.timeoutMs) || options.timeoutMs < 1
      || options.timeoutMs > maximumTimeoutMs
      || !Number.isSafeInteger(options.maxOutputBytes ?? defaultMaxOutputBytes)
      || (options.maxOutputBytes ?? defaultMaxOutputBytes) < 1
      || !['ignore', 'pipe'].includes(options.stdin ?? 'ignore'))
    invalidOptions();
  if (Object.entries(options.env).some(([key, value]) =>
    key.length === 0 || typeof value !== 'string'))
    invalidOptions();
  const cwd = resolve(options.cwd);
  if (!existsSync(cwd))
    invalidOptions();
  const stats = lstatSync(cwd);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    invalidOptions();
  return {
    cwd: realpathSync(cwd),
    env: { ...options.env },
    maxOutputBytes: options.maxOutputBytes ?? defaultMaxOutputBytes,
    stdin: options.stdin ?? 'ignore',
    timeoutMs: options.timeoutMs,
  };
}

function groupExists(pid) {
  if (pid === undefined)
    return false;
  try {
    process.kill(-pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH')
      return false;
    throw new Error('Managed process group status is unavailable');
  }
}

function signalGroup(pid, signal) {
  if (pid === undefined)
    return;
  try {
    process.kill(-pid, signal);
  } catch (error) {
    if (error?.code !== 'ESRCH')
      throw new Error('Managed process group signal failed');
  }
}

async function waitForGroupExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (groupExists(pid) && Date.now() < deadline)
    await new Promise((resolveWait) => setTimeout(resolveWait, 25));
  return !groupExists(pid);
}

async function cleanGroup(pid) {
  if (!groupExists(pid))
    return;
  signalGroup(pid, 'SIGTERM');
  if (await waitForGroupExit(pid, terminationGraceMs))
    return;
  signalGroup(pid, 'SIGKILL');
  if (!await waitForGroupExit(pid, terminationGraceMs))
    throw new Error('Managed process group remained alive after SIGKILL');
}

function decoded(chunks) {
  try {
    return utf8.decode(Buffer.concat(chunks));
  } catch {
    throw new Error('Managed process output is not UTF-8');
  }
}

/**
 * Starts one bounded, isolated process. Output is captured but never forwarded;
 * callers may also observe child.stdout for bounded control-line parsing.
 */
export function startProcess(command, args, options) {
  const config = validatedOptions(command, args, options);
  let child;
  try {
    child = spawn(command, args, {
      cwd: config.cwd,
      detached: true,
      env: config.env,
      shell: false,
      stdio: [config.stdin, 'pipe', 'pipe'],
    });
  } catch {
    throw new Error('Managed process failed to start');
  }

  let exitObserved = false;
  let spawnFailed = false;
  let exitResolve;
  const exit = new Promise((resolveExit) => {
    exitResolve = resolveExit;
  });
  // Register both terminal listeners before timers, collectors, or caller work.
  child.once('error', () => {
    spawnFailed = true;
    exitObserved = true;
    exitResolve({ code: null, signal: null });
  });
  child.once('close', (code, signal) => {
    exitObserved = true;
    exitResolve({ code, signal });
  });

  const stdout = [];
  const stderr = [];
  let outputBytes = 0;
  let failure;
  let abortResolve;
  const aborted = new Promise((resolveAbort) => {
    abortResolve = resolveAbort;
  });
  let cleanup;
  function clean() {
    cleanup ??= cleanGroup(child.pid);
    return cleanup;
  }
  function abort(message) {
    if (failure !== undefined)
      return;
    failure = message;
    abortResolve();
    // The completion promise awaits this same cleanup; consume this eager
    // rejection so an OS signal failure never becomes an unhandled rejection.
    void clean().catch(() => {});
  }
  function collect(stream, chunks) {
    stream.once('error', () => abort('Managed process output failed'));
    stream.on('data', (chunk) => {
      if (failure !== undefined)
        return;
      outputBytes += chunk.length;
      if (outputBytes > config.maxOutputBytes) {
        abort('Managed process output exceeded its bound');
        return;
      }
      chunks.push(Buffer.from(chunk));
    });
  }
  collect(child.stdout, stdout);
  collect(child.stderr, stderr);
  if (config.stdin === 'pipe')
    child.stdin.once('error', () => abort('Managed process input failed'));
  const timer = setTimeout(() => abort('Managed process timed out'),
    config.timeoutMs);

  const completion = (async () => {
    const first = await Promise.race([
      exit.then((result) => ({ kind: 'exit', result })),
      aborted.then(() => ({ kind: 'abort' })),
    ]);
    clearTimeout(timer);
    await clean();
    if (failure !== undefined)
      throw new Error(failure);
    if (spawnFailed)
      throw new Error('Managed process failed to start');
    if (first.kind !== 'exit')
      throw new Error('Managed process stopped');
    return {
      code: first.result.code,
      signal: first.result.signal,
      stdout: decoded(stdout),
      stderr: decoded(stderr),
    };
  })();

  async function stop() {
    clearTimeout(timer);
    if (!exitObserved)
      abort('Managed process stopped');
    await clean();
  }

  return { child, completion, stop };
}

export async function runProcess(command, args, options) {
  return await startProcess(command, args, options).completion;
}
