#!/usr/bin/env node

import { spawn } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from 'node:fs';
import { delimiter, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { adjudicateChecks } from './adjudicate.mjs';
import { validateFinalTagWire } from './validate-final-tag-wire.mjs';
import {
  activeScenarios,
  officialScenarioArguments,
  verifyListedInventory,
  verifyManifestSet,
  verifyOfficialSuite,
  verifyToolchain,
} from './verify.mjs';

const fixtureMain = 'com.soklet.conformance.McpConformanceFixture';
const startupTimeoutMilliseconds = 10_000;
const scenarioTimeoutMilliseconds = 60_000;
const shutdownTimeoutMilliseconds = 10_000;
const maximumLogBytes = 1024 * 1024;
const maximumResultFileCount = 128;
const maximumResultDirectoryDepth = 8;
const maximumResultFileBytes = 8 * 1024 * 1024;
const maximumResultTreeBytes = 16 * 1024 * 1024;
const maximumChecksFileBytes = 8 * 1024 * 1024;

export async function runOfficialConformance(options, { processObject = process } = {}) {
	prepareEmptyWorkDirectory(options.workDirectory);
	const mode = options.mode ?? 'verify';
	const supervisor = new ChildSupervisor();
	const removeSignalHandlers = installSignalHandlers(supervisor, processObject);
	const evidence = createInitialEvidence(options.phase, mode);
  const evidencePath = resolve(options.workDirectory, 'evidence.json');
  let reportedFailure;

  try {
    writeJsonAtomically(evidencePath, evidence);
    supervisor.throwIfCancellationRequested();
		const { pins, selection, expectedChecks } = verifyManifestSet();
		const observing = mode === 'observe';
		if (observing) {
			if (options.phase !== selection.currentImplementationPhase + 1)
				throw new Error(
					`Profile observation must target Phase ${selection.currentImplementationPhase + 1}`,
				);
		} else if (mode !== 'verify'
				|| options.phase !== selection.currentImplementationPhase) {
			throw new Error(
				`Verification must target current implementation Phase ${selection.currentImplementationPhase}`,
			);
		}
		verifyPublicFixtureClasspath(options.classpath, options.projectRoot);
    evidence.suiteCommit = pins.officialConformanceSuite.commit;
    evidence.protocolVersion = pins.protocolVersion;
    persistEvidence(evidencePath, evidence);

    verifyOfficialSuite(options.suiteDirectory, pins);
    supervisor.throwIfCancellationRequested();
    const npmResult = await runBoundedCommand(
      'npm', ['--version'],
      {
        timeoutMilliseconds: 10_000,
        workingDirectory: options.suiteDirectory,
        supervisor,
      },
    );
    if (npmResult.timedOut || npmResult.status !== 0 || npmResult.outputFailure !== null) {
      const diagnostic = npmResult.outputFailure ?? npmResult.stderr;
      throw new Error(`Unable to determine the pinned npm version: ${diagnostic}`);
    }
    verifyToolchain(pins, npmResult.stdout.trim());
    supervisor.throwIfCancellationRequested();

    const entryPoint = resolve(options.suiteDirectory, pins.officialConformanceSuite.entryPoint);
    const listResult = await runBoundedCommand(
      process.execPath,
      [entryPoint, ...pins.officialConformanceSuite.listCommandArguments],
      {
        timeoutMilliseconds: 30_000,
        workingDirectory: options.suiteDirectory,
        supervisor,
      },
    );
    if (listResult.timedOut)
      throw new Error('Official scenario listing timed out');
    if (listResult.outputFailure !== null)
      throw new Error(`Official scenario listing output was invalid: ${listResult.outputFailure}`);
    if (listResult.status !== 0)
      throw new Error(`Official scenario listing failed:\n${listResult.stderr}`);
    verifyListedInventory(listResult.stdout, selection, pins);
    supervisor.throwIfCancellationRequested();
    const goldenResult = validateFinalTagWire({ suiteDirectory: options.suiteDirectory });
    evidence.goldenMessagesValidated = goldenResult.validated.length;
    evidence.status = 'RUNNING';
    persistEvidence(evidencePath, evidence);
    supervisor.throwIfCancellationRequested();

		const scenarios = activeScenarios(selection, options.phase);
		const expectedScenarioCount = options.phase === 3
			? 1
			: options.phase === 4
				? 23
				: options.phase === 5 ? 39 : null;
		if (expectedScenarioCount !== null && scenarios.length !== expectedScenarioCount)
			throw new Error(
				`Phase ${options.phase} must select exactly ${expectedScenarioCount} reviewed scenarios`,
			);

    let runFailure;
    for (const [index, scenario] of scenarios.entries()) {
      supervisor.throwIfCancellationRequested();
      try {
			evidence.scenarios.push(await runScenario({
          ordinal: index + 1,
          scenario,
          options,
          entryPoint,
          pins,
          expectedChecks,
				supervisor,
				observing,
			}));
      } catch (error) {
        runFailure ??= error;
        evidence.scenarios.push({ name: scenario.name, passed: false, error: safeMessage(error) });
      }
      persistEvidence(evidencePath, evidence);
    }

    if (runFailure !== undefined) throw runFailure;
		evidence.status = observing ? 'OBSERVED' : 'PASSED';
    evidence.failure = null;
    persistEvidence(evidencePath, evidence);
		console.log(observing
			? `Observed official MCP Phase ${options.phase} profiles for review: `
				+ `${scenarios.map((scenario) => scenario.name).join(', ')}.`
			: `Official MCP Phase ${options.phase} development check passed: `
				+ `${scenarios.map((scenario) => scenario.name).join(', ')}.`);
  } catch (error) {
    const failure = supervisor.cancellationRequested
      ? new RunnerCancelledError(supervisor.cancellationSignal, { cause: error })
      : error;
    reportedFailure = failure;
    evidence.status = supervisor.cancellationRequested ? 'CANCELLED' : 'FAILED';
    evidence.failure = safeMessage(failure);
    try {
      persistEvidence(evidencePath, evidence);
    } catch (evidenceError) {
      throw new AggregateError(
        [failure, evidenceError], 'Conformance run and durable evidence write failed',
      );
    }
    throw failure;
  } finally {
    let cleanupError;
    const unexpectedLiveChildren = !supervisor.cancellationRequested
      && reportedFailure === undefined
      ? supervisor.activeChildCount
      : 0;
    try {
      await supervisor.terminateAndWaitForAll();
      if (supervisor.cancellationRequested && reportedFailure === undefined)
        cleanupError = new RunnerCancelledError(supervisor.cancellationSignal);
      else if (unexpectedLiveChildren !== 0)
        cleanupError = new Error(
          `Conformance run completed with ${unexpectedLiveChildren} live child process(es)`,
        );
    } catch (error) {
      cleanupError = error;
    } finally {
      removeSignalHandlers();
    }
    if (cleanupError !== undefined) {
      const failure = reportedFailure === undefined
        ? cleanupError
        : new AggregateError(
          [reportedFailure, cleanupError], 'Conformance run and child cleanup failed',
        );
      evidence.status = supervisor.cancellationRequested ? 'CANCELLED' : 'FAILED';
      evidence.failure = safeMessage(failure);
      persistEvidence(evidencePath, evidence);
      throw failure;
    }
  }
}

async function runScenario({
  ordinal,
  scenario,
  options,
  entryPoint,
  pins,
	expectedChecks,
	supervisor,
	observing,
}) {
  const scenarioDirectory = resolve(
    options.workDirectory,
    `${String(ordinal).padStart(3, '0')}-${scenario.name}`,
  );
  const resultDirectory = resolve(scenarioDirectory, 'official-results');
  mkdirSync(resultDirectory, { recursive: true });
  let fixture;
  let commandResult;
  let checks;
  let primaryFailure;

  try {
    supervisor.throwIfCancellationRequested();
    fixture = startFixture(scenario.name, options, supervisor);
    writeFileSync(resolve(scenarioDirectory, 'fixture.pid'), `${fixture.child.pid}\n`);
    const readyLine = await fixture.lines.next(startupTimeoutMilliseconds);
    supervisor.throwIfCancellationRequested();
    const ready = parseReadyLine(readyLine);
    const url = `http://${ready.host}:${ready.port}${ready.path}`;
    commandResult = await runBoundedCommand(
      process.execPath,
      [entryPoint, ...officialScenarioArguments(pins, {
        fixtureUrl: url,
        scenarioName: scenario.name,
        outputDirectory: resultDirectory,
      })],
      {
        timeoutMilliseconds: scenarioTimeoutMilliseconds,
        workingDirectory: options.suiteDirectory,
        supervisor,
      },
    );
    writeFileSync(resolve(scenarioDirectory, 'official.stdout.log'), commandResult.stdout);
    writeFileSync(resolve(scenarioDirectory, 'official.stderr.log'), commandResult.stderr);
    if (commandResult.timedOut)
      throw new Error(`Official scenario ${scenario.name} timed out`);
    if (commandResult.outputFailure !== null)
      throw new Error(
        `Official scenario ${scenario.name} output was invalid: ${commandResult.outputFailure}`,
      );
		const checksPath = exactlyOneChecksFile(resultDirectory);
		checks = JSON.parse(readFileSync(checksPath, 'utf8'));
		if (observing) {
			writeJsonAtomically(resolve(scenarioDirectory, 'observed-profile-draft.json'),
				observedProfileDraft(scenario.name, checks, options.phase,
					pins.officialConformanceSuite.commit));
		} else {
			const profile = expectedChecks.profiles.find(
				(candidate) => candidate.id === scenario.expectedCheckProfile,
			);
			if (profile === undefined)
				throw new Error(`Missing expected-check profile ${scenario.expectedCheckProfile}`);
			adjudicateChecks(scenario.name, checks, profile);
		}
		if (commandResult.status !== 0)
			throw new Error(
				`Official scenario ${scenario.name} exited ${commandResult.status}: `
					+ commandResult.stderr,
			);
  } catch (error) {
    primaryFailure = error;
  } finally {
    if (fixture !== undefined) {
      try {
        await stopFixture(fixture, scenarioDirectory);
      } catch (cleanupError) {
        if (primaryFailure === undefined) primaryFailure = cleanupError;
        else primaryFailure = new AggregateError(
          [primaryFailure, cleanupError], `Scenario ${scenario.name} and fixture cleanup failed`,
        );
      }
    }
  }
  if (primaryFailure !== undefined) throw primaryFailure;
	return {
		name: scenario.name,
		passed: true,
		checkCount: checks.length,
		expectedCheckProfile: observing ? null : scenario.expectedCheckProfile,
		observedProfileDraft: observing
			? `${String(ordinal).padStart(3, '0')}-${scenario.name}/observed-profile-draft.json`
			: null,
	};
}

export function observedProfileDraft(scenarioName, checks, phase, suiteCommit) {
	if (!Array.isArray(checks))
		throw new Error(`Official checks for ${scenarioName} must be an array`);
	const ordinary = new Map();
	let wireSuccesses = 0;
	let wireHarnessErrors = 0;
	for (const [index, check] of checks.entries()) {
		if (check === null || typeof check !== 'object'
				|| typeof check.id !== 'string' || check.id.length === 0
				|| typeof check.status !== 'string')
			throw new Error(`Malformed official check ${index + 1} for ${scenarioName}`);
		if (check.id === 'wire-schema-valid') {
			wireSuccesses++;
			continue;
		}
		if (check.id === 'wire-schema-harness-error') {
			wireHarnessErrors++;
			continue;
		}
		const reason = check.status === 'SKIPPED'
			? observedSkipReason(check)
			: null;
		const key = `${check.id}\u0000${check.status}\u0000${reason ?? ''}`;
		const previous = ordinary.get(key);
		if (previous === undefined) {
			const row = { id: check.id, status: check.status, count: 1 };
			if (reason !== null) row.reason = reason;
			ordinary.set(key, row);
		} else {
			previous.count++;
		}
	}
	return {
		id: `${scenarioName}.phase${phase}.v1`,
		scenario: scenarioName,
		frozenInPhase: phase,
		suiteCommit,
		checks: [...ordinary.values()].sort((left, right) =>
			Buffer.compare(Buffer.from(`${left.id}\u0000${left.status}\u0000${left.reason ?? ''}`),
				Buffer.from(`${right.id}\u0000${right.status}\u0000${right.reason ?? ''}`))),
		automaticWireChecks: {
			'wire-schema-valid': wireSuccesses,
			'wire-schema-harness-error': wireHarnessErrors,
			rationale: 'Observation-only draft from the exact pinned official scenario; review before freezing.',
		},
	};
}

function observedSkipReason(check) {
	if (typeof check.errorMessage === 'string' && check.errorMessage.length !== 0)
		return check.errorMessage;
	if (check.details !== null && typeof check.details === 'object'
			&& typeof check.details.reason === 'string'
			&& check.details.reason.length !== 0)
		return check.details.reason;
	if (check.details !== null && typeof check.details === 'object'
			&& typeof check.details.note === 'string'
			&& check.details.note.length !== 0)
		return check.details.note;
	return null;
}

function startFixture(scenarioName, options, supervisor) {
  verifyPublicFixtureClasspath(options.classpath, options.projectRoot);
  const child = supervisor.spawn(
    options.javaExecutable,
    ['-cp', options.classpath, fixtureMain, '--scenario', scenarioName],
    {
      cwd: options.projectRoot,
      env: boundedEnvironment(),
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  const lines = boundedLineReader(child.stdout, 'fixture stdout');
  const stderr = boundedCollector(child.stderr, 'fixture stderr');
  child.once('error', (error) => lines.fail(error));
  return { child, lines, stderr, supervisor };
}

export function verifyPublicFixtureClasspath(classpath, projectRoot) {
  const root = resolve(projectRoot);
  const fixtureClasses = resolve(root, 'target/conformance/public-fixture/classes');
  const candidateJar = resolve(root, 'target/soklet-3.6.0-SNAPSHOT.jar');
  const fixtureMainClass = resolve(
    fixtureClasses, 'com/soklet/conformance/McpConformanceFixture.class',
  );
  const entries = classpath.split(delimiter).map((entry) => resolve(root, entry));
  const exactEntries = entries.length === 2
    && entries[0] === fixtureClasses
    && entries[1] === candidateJar;
  const fixtureStats = existsSync(fixtureClasses) ? lstatSync(fixtureClasses) : null;
  const fixtureMainStats = existsSync(fixtureMainClass) ? lstatSync(fixtureMainClass) : null;
  const candidateStats = existsSync(candidateJar) ? lstatSync(candidateJar) : null;
  if (!exactEntries
      || fixtureStats === null || !fixtureStats.isDirectory() || fixtureStats.isSymbolicLink()
      || fixtureMainStats === null || !fixtureMainStats.isFile()
      || fixtureMainStats.isSymbolicLink()
      || candidateStats === null || !candidateStats.isFile()
      || candidateStats.isSymbolicLink()) {
    throw new Error(
      'Public MCP fixture classpath must be exactly '
        + 'target/conformance/public-fixture/classes followed by '
        + 'target/soklet-3.6.0-SNAPSHOT.jar',
    );
  }
  return Object.freeze({ fixtureClasses, candidateJar });
}

async function stopFixture(fixture, scenarioDirectory) {
  let forced = false;
  let failure;
  try {
    if (fixture.child.exitCode !== null || fixture.child.signalCode !== null)
      throw new Error(
        `Fixture exited before graceful shutdown code=${fixture.child.exitCode} `
          + `signal=${fixture.child.signalCode}: ${fixture.stderr.text()}`,
      );
    if (fixture.child.exitCode === null) fixture.child.stdin.end();
    const stoppedLine = await fixture.lines.next(shutdownTimeoutMilliseconds);
    const stopped = JSON.parse(stoppedLine);
    if (JSON.stringify(Object.keys(stopped)) !== '["format","event","clean"]'
        || stopped.format !== 1 || stopped.event !== 'stopped' || stopped.clean !== true)
      throw new Error('Fixture emitted an invalid STOPPED control line');
    const exit = await fixture.supervisor.waitForClose(
      fixture.child, shutdownTimeoutMilliseconds,
    );
    if (exit.code !== 0 || exit.signal !== null)
      throw new Error(`Fixture exited code=${exit.code} signal=${exit.signal}`);
    if (fixture.lines.lineCount() !== 2)
      throw new Error('Fixture emitted unexpected stdout control lines');
    fixture.lines.assertHealthy();
    fixture.stderr.assertWithinLimit();
  } catch (error) {
    forced = true;
    failure = error;
    try {
      await fixture.supervisor.terminate(fixture.child);
      await fixture.supervisor.waitForClose(fixture.child, shutdownTimeoutMilliseconds);
    } catch (cleanupError) {
      failure = new AggregateError(
        [failure, cleanupError], 'Fixture shutdown and pipe cleanup both failed',
      );
    }
  } finally {
    writeFileSync(resolve(scenarioDirectory, 'fixture.stdout.log'), fixture.lines.text());
    writeFileSync(resolve(scenarioDirectory, 'fixture.stderr.log'), fixture.stderr.text());
    writeFileSync(resolve(scenarioDirectory, 'fixture.cleanup.txt'),
      `forced=${forced}\nexitCode=${fixture.child.exitCode}\nsignal=${fixture.child.signalCode}\n`);
  }
  if (failure !== undefined) throw failure;
}

function parseReadyLine(line) {
  let ready;
  try {
    ready = JSON.parse(line);
  } catch (error) {
    throw new Error('Fixture readiness line is not JSON', { cause: error });
  }
  if (JSON.stringify(Object.keys(ready)) !== '["format","event","host","port","path"]'
      || ready.format !== 1 || ready.event !== 'ready'
      || ready.host !== '127.0.0.1' || ready.path !== '/mcp'
      || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535) {
    throw new Error('Fixture emitted an invalid READY control line');
  }
  return ready;
}

export function exactlyOneChecksFile(root) {
  const found = [];
  let fileCount = 0;
  let totalBytes = 0;
  const rootStats = lstatSync(root);
  if (!rootStats.isDirectory() || rootStats.isSymbolicLink())
    throw new Error('Official result root must be a real directory');
  visit(root, 0);
  if (found.length !== 1)
    throw new Error(`Expected exactly one official checks.json, found ${found.length}`);
  if (found[0].bytes > maximumChecksFileBytes)
    throw new Error(`Official checks.json exceeded ${maximumChecksFileBytes} bytes`);
  return found[0].path;

  function visit(directory, depth) {
    if (depth > maximumResultDirectoryDepth)
      throw new Error(
        `Official result tree exceeded depth ${maximumResultDirectoryDepth}`,
      );
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink())
        throw new Error(`Official result tree contains a symbolic link: ${path}`);
      if (entry.isDirectory()) {
        visit(path, depth + 1);
      } else if (entry.isFile()) {
        const stats = lstatSync(path);
        if (!stats.isFile() || stats.isSymbolicLink())
          throw new Error(`Official result tree contains an unsafe file: ${path}`);
        fileCount++;
        if (fileCount > maximumResultFileCount)
          throw new Error(`Official result tree exceeded ${maximumResultFileCount} files`);
        if (stats.size > maximumResultFileBytes)
          throw new Error(
            `Official result file exceeded ${maximumResultFileBytes} bytes: ${path}`,
          );
        totalBytes += stats.size;
        if (totalBytes > maximumResultTreeBytes)
          throw new Error(`Official result tree exceeded ${maximumResultTreeBytes} bytes`);
        if (entry.name === 'checks.json') found.push({ path, bytes: stats.size });
      } else {
        throw new Error(`Unsupported official result entry: ${path}`);
      }
    }
  }
}

export async function runBoundedCommand(
  command,
  args,
  { timeoutMilliseconds, workingDirectory, supervisor = new ChildSupervisor() },
) {
  const child = supervisor.spawn(command, args, {
    cwd: workingDirectory,
    env: boundedEnvironment(),
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const stdout = boundedCollector(child.stdout, 'child stdout');
  const stderr = boundedCollector(child.stderr, 'child stderr');
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    supervisor.signal(child, 'SIGTERM');
  }, timeoutMilliseconds);
  try {
    const exit = await supervisor.waitForClose(child, timeoutMilliseconds + 5_000);
    if (timedOut) await supervisor.terminate(child);
    supervisor.throwIfCancellationRequested();
    const outputFailures = [];
    try {
      stdout.assertWithinLimit();
    } catch (error) {
      outputFailures.push(error);
    }
    try {
      stderr.assertWithinLimit();
    } catch (error) {
      outputFailures.push(error);
    }
    return {
      status: exit.code,
      signal: exit.signal,
      timedOut,
      stdout: stdout.text(),
      stderr: stderr.text(),
      outputFailure: outputFailures.length === 0
        ? null
        : outputFailures.map(safeMessage).join('; '),
    };
  } finally {
    clearTimeout(timer);
    await supervisor.terminate(child);
  }
}

export function boundedCollector(stream, description, maximumBytes = maximumLogBytes) {
  const chunks = [];
  let bytes = 0;
  let failure;
  stream.on('data', (chunk) => {
    if (failure !== undefined) return;
    bytes += chunk.length;
    if (bytes > maximumBytes) {
      failure = new Error(`${description} exceeded ${maximumBytes} bytes`);
      return;
    }
    chunks.push(chunk);
  });
  return {
    text: () => Buffer.concat(chunks).toString('utf8'),
    retainedByteCount: () => chunks.reduce((total, chunk) => total + chunk.length, 0),
    assertWithinLimit() {
      if (failure !== undefined) throw failure;
    },
  };
}

export function boundedLineReader(stream, description, maximumBytes = maximumLogBytes) {
  let buffered = '';
  let raw = '';
  let retainedBytes = 0;
  const lines = [];
  const waiters = [];
  let failure;
  let ended = false;
  stream.setEncoding('utf8');
  stream.on('data', (chunk) => {
    if (failure !== undefined) return;
    const chunkBytes = Buffer.byteLength(chunk, 'utf8');
    if (retainedBytes + chunkBytes > maximumBytes) {
      fail(new Error(`${description} exceeded ${maximumBytes} bytes`));
      return;
    }
    retainedBytes += chunkBytes;
    raw += chunk;
    buffered += chunk;
    let newline;
    while ((newline = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, newline);
      buffered = buffered.slice(newline + 1);
      if (line.includes('\r')) {
        fail(new Error(`${description} must be LF-only`));
        return;
      }
      lines.push(line);
      const waiter = waiters.shift();
      if (waiter !== undefined) waiter.resolve(line);
    }
  });
  stream.once('end', () => {
    ended = true;
    if (buffered.length !== 0) fail(new Error(`${description} ended without LF`));
    for (const waiter of waiters.splice(0))
      waiter.reject(new Error(`${description} ended before the next control line`));
  });

  function fail(error) {
    failure ??= error;
    for (const waiter of waiters.splice(0)) waiter.reject(failure);
  }

  return {
    fail,
    async next(timeoutMilliseconds) {
      if (failure !== undefined) throw failure;
      const consumed = this.consumed ?? 0;
      if (consumed < lines.length) {
        this.consumed = consumed + 1;
        return lines[consumed];
      }
      if (ended) throw new Error(`${description} ended before the next control line`);
      let waiter;
      const pendingLine = new Promise((resolveLine, reject) => {
        waiter = { resolve: resolveLine, reject };
        waiters.push(waiter);
      });
      let line;
      try {
        line = await withTimeout(
          pendingLine,
          timeoutMilliseconds,
          `${description} control-line timeout`,
        );
      } catch (error) {
        const waiterIndex = waiters.indexOf(waiter);
        if (waiterIndex >= 0) waiters.splice(waiterIndex, 1);
        throw error;
      }
      this.consumed = (this.consumed ?? 0) + 1;
      return line;
    },
    lineCount: () => lines.length,
    text: () => raw,
    retainedByteCount: () => retainedBytes,
    assertHealthy() {
      if (failure !== undefined) throw failure;
    },
    consumed: 0,
  };
}

function waitForExit(child, timeoutMilliseconds) {
  if (child.exitCode !== null || child.signalCode !== null)
    return Promise.resolve({ code: child.exitCode, signal: child.signalCode });
  return withTimeout(new Promise((resolveExit, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => resolveExit({ code, signal }));
  }), timeoutMilliseconds, 'Child process exit timeout');
}

async function forceStop(record, graceMilliseconds = 2_000) {
  if (!treeIsAlive(record)) return;
  signalTree(record, 'SIGTERM');
  try {
    await waitForTreeExit(record, graceMilliseconds);
  } catch {
    signalTree(record, 'SIGKILL');
    await waitForTreeExit(record, graceMilliseconds);
  }
}

function treeIsAlive({ child, processGroup }) {
  if (child.pid === undefined) return false;
  if (!processGroup) return child.exitCode === null && child.signalCode === null;
  try {
    process.kill(-child.pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH') return false;
    if (error?.code === 'EPERM') return true;
    throw error;
  }
}

function signalTree(record, signal) {
  if (!treeIsAlive(record)) return false;
  if (!record.processGroup) return record.child.kill(signal);
  try {
    process.kill(-record.child.pid, signal);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH') return false;
    throw error;
  }
}

async function waitForTreeExit(record, timeoutMilliseconds) {
  if (!record.processGroup) return waitForExit(record.child, timeoutMilliseconds);
  const deadline = Date.now() + timeoutMilliseconds;
  while (treeIsAlive(record)) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('Child process-group exit timeout');
    await new Promise((resolveDelay) => setTimeout(resolveDelay, Math.min(remaining, 20)));
  }
  return { code: record.child.exitCode, signal: record.child.signalCode };
}

export class RunnerCancelledError extends Error {
  constructor(signal, options) {
    super(`Conformance runner cancelled by ${signal}`, options);
    this.name = 'RunnerCancelledError';
    this.signal = signal;
  }
}

export class ChildSupervisor {
  #children = new Map();
  #allChildren = new WeakMap();
  #cancellationSignal;
  #terminationPromise;
  #terminationGraceMilliseconds;

  constructor({ terminationGraceMilliseconds = 2_000 } = {}) {
    if (!Number.isInteger(terminationGraceMilliseconds) || terminationGraceMilliseconds < 1)
      throw new Error('Child termination grace must be a positive integer');
    this.#terminationGraceMilliseconds = terminationGraceMilliseconds;
  }

  get cancellationRequested() {
    return this.#cancellationSignal !== undefined;
  }

  get cancellationSignal() {
    return this.#cancellationSignal;
  }

  get activeChildCount() {
    this.#pruneExitedTrees();
    return this.#children.size;
  }

  spawn(command, args, options) {
    this.throwIfCancellationRequested();
    const processGroup = process.platform !== 'win32';
    const child = spawn(command, args, { ...options, detached: processGroup });
    let resolveCompletion;
    const completion = new Promise((resolveResult) => {
      resolveCompletion = resolveResult;
    });
    const record = { child, processGroup, completion };
    this.#children.set(child, record);
    this.#allChildren.set(child, record);
    child.once('exit', () => {
      if (!treeIsAlive(record)) this.#children.delete(child);
    });
    child.once('error', (error) => {
      resolveCompletion({ error });
      this.#children.delete(child);
    });
    child.once('close', (code, signal) => {
      resolveCompletion({ code, signal });
      if (!treeIsAlive(record)) this.#children.delete(child);
    });
    return child;
  }

  async waitForClose(child, timeoutMilliseconds) {
    const record = this.#allChildren.get(child);
    if (record === undefined) throw new Error('Child is not owned by this supervisor');
    const result = await withTimeout(
      record.completion, timeoutMilliseconds, 'Child process close timeout',
    );
    if (result.error !== undefined) throw result.error;
    return result;
  }

  signal(child, signal) {
    const record = this.#children.get(child) ?? {
      child,
      processGroup: false,
    };
    return signalTree(record, signal);
  }

  async terminate(child) {
    const record = this.#children.get(child) ?? {
      child,
      processGroup: false,
    };
    try {
      await forceStop(record, this.#terminationGraceMilliseconds);
    } finally {
      if (!treeIsAlive(record)) this.#children.delete(child);
    }
  }

  requestCancellation(signal) {
    if (!['SIGINT', 'SIGTERM'].includes(signal))
      throw new Error(`Unsupported cancellation signal ${signal}`);
    if (this.#cancellationSignal === undefined) {
      this.#cancellationSignal = signal;
      const termination = this.terminateAndWaitForAll();
      termination.catch(() => {
        // The main runner awaits and reports this same failure during final cleanup.
      });
    } else {
      for (const record of this.#children.values()) signalTree(record, 'SIGKILL');
    }
  }

  throwIfCancellationRequested() {
    if (this.#cancellationSignal !== undefined)
      throw new RunnerCancelledError(this.#cancellationSignal);
  }

  async terminateAndWaitForAll() {
    if (this.#terminationPromise !== undefined) return this.#terminationPromise;
    this.#pruneExitedTrees();
    const records = [...this.#children.values()];
    this.#terminationPromise = Promise.allSettled(records.map((record) =>
      forceStop(record, this.#terminationGraceMilliseconds))).then((results) => {
      const failures = results
        .filter((result) => result.status === 'rejected')
        .map((result) => result.reason);
      if (failures.length !== 0)
        throw new AggregateError(failures, 'One or more conformance children resisted cleanup');
      this.#pruneExitedTrees();
      if (this.#children.size !== 0)
        throw new Error('One or more conformance process groups remained alive after cleanup');
    });
    return this.#terminationPromise;
  }

  #pruneExitedTrees() {
    for (const [child, record] of this.#children) {
      if (!treeIsAlive(record)) this.#children.delete(child);
    }
  }
}

export function installSignalHandlers(supervisor, processObject = process) {
  const handlers = new Map();
  for (const signal of ['SIGINT', 'SIGTERM']) {
    const handler = () => {
      processObject.exitCode = 128 + (signal === 'SIGINT' ? 2 : 15);
      supervisor.requestCancellation(signal);
    };
    handlers.set(signal, handler);
    processObject.on(signal, handler);
  }
  return () => {
    for (const [signal, handler] of handlers) processObject.removeListener(signal, handler);
  };
}

function withTimeout(promise, milliseconds, message) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), milliseconds);
    }),
  ]).finally(() => clearTimeout(timer));
}

function prepareEmptyWorkDirectory(path) {
  if (!isAbsolute(path)) throw new Error('Conformance work directory must be absolute');
  if (!existsSync(path)) mkdirSync(path, { recursive: true });
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    throw new Error('Conformance work directory must be a real directory');
  if (readdirSync(path).length !== 0)
    throw new Error('Conformance work directory must be empty');
}

function boundedEnvironment() {
  const environment = { NO_COLOR: '1' };
  for (const name of ['PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TMPDIR']) {
    if (process.env[name] !== undefined) environment[name] = process.env[name];
  }
  return environment;
}

function writeJsonAtomically(path, value) {
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
  renameSync(temporary, path);
}

function createInitialEvidence(phase, mode) {
	return {
		formatVersion: 1,
		evidenceClass: mode === 'observe'
			? 'PROFILE_OBSERVATION_ONLY'
			: 'CANDIDATE_ARTIFACT_DEVELOPMENT_ONLY',
		releaseCandidateEvidence: false,
    status: 'PREPARING',
    suiteCommit: null,
    protocolVersion: null,
		phase,
		mode,
    goldenMessagesValidated: null,
    scenarios: [],
    failure: null,
  };
}

function persistEvidence(path, evidence) {
  writeJsonAtomically(path, evidence);
}

function safeMessage(error) {
  if (error instanceof Error) return `${error.name}: ${error.message}`;
  return String(error);
}

function parseArguments(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
		if (!['--suite-dir', '--work-dir', '--classpath', '--project-root', '--java', '--phase',
			'--mode']
      .includes(name) || value === undefined || values.has(name)) {
      usage();
    }
    values.set(name, value);
  }
  for (const required of ['--suite-dir', '--work-dir', '--classpath']) {
    if (!values.has(required)) usage();
  }
  const projectRoot = resolve(values.get('--project-root') ?? process.cwd());
  return Object.freeze({
    suiteDirectory: resolve(values.get('--suite-dir')),
    workDirectory: resolve(values.get('--work-dir')),
    classpath: values.get('--classpath'),
    projectRoot,
		javaExecutable: values.get('--java') ?? 'java',
		phase: Number(values.get('--phase') ?? '5'),
		mode: values.get('--mode') ?? 'verify',
  });
}

function usage() {
  console.error(
    'Usage: node conformance/official/run.mjs '
      + '--suite-dir <built-suite> --work-dir <empty-absolute-directory> '
      + '--classpath <fixture-classes-and-candidate-jar> [--project-root <root>] '
			+ '[--java <java>] [--phase <phase>] [--mode verify|observe]',
  );
  process.exit(64);
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await runOfficialConformance(parseArguments(process.argv.slice(2)));
  } catch (error) {
    console.error(error);
    if (process.exitCode === undefined || process.exitCode === 0) process.exitCode = 1;
  }
}
