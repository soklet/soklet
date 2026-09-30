#!/usr/bin/env node

import {
  existsSync, lstatSync, mkdirSync, readFileSync, readdirSync,
  renameSync, writeFileSync,
} from 'node:fs';
import { delimiter, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  boundedCollector, boundedLineReader, ChildSupervisor,
  exactlyOneChecksFile, installSignalHandlers, runBoundedCommand,
} from '../run.mjs';
import {
  sha256, verifyListedInventory, verifyManifestSet,
  verifyOfficialSuite, verifyToolchain,
} from '../verify.mjs';

// This is an intentionally narrow development check, separate from the 46-row
// 2026 release conformance manifest and its candidate-artifact receipt.
export const revisions = Object.freeze(['2025-06-18', '2025-11-25']);
export const scenarios = Object.freeze([
  'server-initialize', 'ping', 'tools-list',
  'tools-call-simple-text', 'tools-call-error',
  'prompts-list', 'prompts-get-simple', 'prompts-get-with-args',
  'prompts-get-embedded-resource', 'prompts-get-with-image',
  'resources-list', 'resources-read-text', 'resources-read-binary',
  'resources-templates-read',
]);
const fixtureMain = 'com.soklet.conformance.legacy.McpLegacyConformanceFixture';
const fixtureSource = resolve(fileURLToPath(new URL('./McpLegacyConformanceFixture.java', import.meta.url)));
const startupTimeoutMilliseconds = 10_000;
const scenarioTimeoutMilliseconds = 60_000;
const shutdownTimeoutMilliseconds = 10_000;

export function assertRawChecks(revision, scenario, checks) {
  if (!revisions.includes(revision) || !scenarios.includes(scenario))
    throw new Error('Unreviewed legacy revision or scenario');
  if (!Array.isArray(checks))
    throw new Error(`${revision}/${scenario} checks must be an array`);
  const ordinary = scenario === 'server-initialize'
    ? [['server-initialize', 'SUCCESS'], ['server-session-id-visible-ascii', 'INFO']]
    : scenario === 'tools-list'
      ? revision === '2025-11-25'
        ? [['tools-list', 'SUCCESS'], ['tools-name-format', 'SUCCESS']]
        : [['tools-list', 'SUCCESS']]
      : [[scenario, 'SUCCESS']];
  const expected = [...ordinary, ['wire-schema-valid', 'SUCCESS']];
  const actual = checks.map((check) => [check?.id, check?.status]);
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    throw new Error(`${revision}/${scenario} raw official check IDs or statuses changed: `
      + JSON.stringify(actual));
  if (scenario === 'server-initialize'
      && checks[1].details?.message
        !== 'Server did not provide an MCP-Session-Id header (session ID is optional)')
    throw new Error(`${revision}/${scenario} optional-session INFO meaning changed`);
  const catalogCountField = scenario === 'prompts-list' ? 'promptCount'
    : scenario === 'resources-list' ? 'resourceCount' : null;
  if (catalogCountField !== null
      && checks[0].details?.[catalogCountField]
        !== (scenario === 'prompts-list' ? 4 : 2))
    throw new Error(`${revision}/${scenario} fixture catalog count changed`);
  const expectedMessageCount = scenario === 'server-initialize' ? 3 : 5;
  if (checks.at(-1).details?.messagesValidated !== expectedMessageCount
      || !Array.isArray(checks.at(-1).details?.violations)
      || checks.at(-1).details.violations.length !== 0)
    throw new Error(`${revision}/${scenario} wire-schema validation changed`);
  return Object.freeze({
    revision, scenario, checkCount: checks.length,
    checks: Object.freeze(actual.map(([id, status]) => Object.freeze({ id, status }))),
    wireMessagesValidated: expectedMessageCount,
  });
}

export function parseReadyLine(line, expectedRevision) {
  let ready;
  try {
    ready = JSON.parse(line);
  } catch (error) {
    throw new Error('Legacy fixture READY line is not JSON', { cause: error });
  }
  if (JSON.stringify(Object.keys(ready))
        !== '["format","event","host","port","path","revision"]'
      || ready.format !== 1 || ready.event !== 'ready'
      || ready.host !== '127.0.0.1' || ready.path !== '/mcp'
      || ready.revision !== expectedRevision
      || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535)
    throw new Error('Legacy fixture emitted an invalid READY line');
  return ready;
}

function parseOptions(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!['--suite-dir', '--work-dir', '--classpath', '--java'].includes(key)
        || value === undefined || values.has(key))
      throw new Error('Usage: run.mjs --suite-dir <pinned-suite> '
        + '--work-dir <empty-absolute-directory> '
        + '--classpath <fixture-classes:candidate-jar> [--java <java>]');
    values.set(key, value);
  }
  for (const required of ['--suite-dir', '--work-dir', '--classpath']) {
    if (!values.has(required)) throw new Error(`Missing ${required}`);
  }
  for (const pathFlag of ['--suite-dir', '--work-dir']) {
    if (!isAbsolute(values.get(pathFlag)))
      throw new Error(`${pathFlag} must be absolute`);
  }
  return Object.freeze({
    suiteDirectory: resolve(values.get('--suite-dir')),
    workDirectory: resolve(values.get('--work-dir')),
    classpath: values.get('--classpath'),
    javaExecutable: values.get('--java') ?? 'java',
  });
}

function requireClasspath(classpath) {
  const entries = classpath.split(delimiter);
  if (entries.length !== 2 || entries.some((entry) => !isAbsolute(entry)))
    throw new Error('Legacy fixture classpath must contain exactly absolute classes and JAR paths');
  const [classes, jar] = entries.map((entry) => resolve(entry));
  const mainClass = resolve(classes,
    'com/soklet/conformance/legacy/McpLegacyConformanceFixture.class');
  for (const [path, directory] of [[classes, true], [jar, false], [mainClass, false]]) {
    if (!existsSync(path)) throw new Error(`Legacy fixture input is missing: ${path}`);
    const stats = lstatSync(path);
    if (stats.isSymbolicLink() || (directory ? !stats.isDirectory() : !stats.isFile()))
      throw new Error(`Legacy fixture input is unsafe: ${path}`);
  }
  return Object.freeze({ classes, jar, mainClass });
}

function prepareWorkDirectory(path) {
  if (!existsSync(path)) mkdirSync(path, { recursive: true });
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink() || readdirSync(path).length !== 0)
    throw new Error('Legacy conformance work directory must be a real empty directory');
}

function persist(path, value) {
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
  renameSync(temporary, path);
}

function boundedEnvironment() {
  const environment = { NO_COLOR: '1' };
  for (const name of ['PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TMPDIR']) {
    if (process.env[name] !== undefined) environment[name] = process.env[name];
  }
  return environment;
}

async function stopFixture(fixture, directory) {
  let failure;
  let forced = false;
  try {
    if (fixture.child.exitCode !== null || fixture.child.signalCode !== null)
      throw new Error('Legacy fixture exited before graceful shutdown');
    const deadline = process.hrtime.bigint()
      + BigInt(shutdownTimeoutMilliseconds) * 1_000_000n;
    const remaining = () => {
      const nanoseconds = deadline - process.hrtime.bigint();
      if (nanoseconds <= 0n)
        throw new Error('Legacy fixture exceeded its graceful shutdown bound');
      return Number((nanoseconds + 999_999n) / 1_000_000n);
    };
    fixture.child.stdin.end();
    const stopped = JSON.parse(await fixture.lines.next(remaining()));
    if (JSON.stringify(stopped) !== '{"format":1,"event":"stopped","clean":true}')
      throw new Error('Legacy fixture emitted an invalid STOPPED line');
    const exit = await fixture.supervisor.waitForClose(
      fixture.child, remaining());
    if (exit.code !== 0 || exit.signal !== null || fixture.lines.lineCount() !== 2)
      throw new Error('Legacy fixture did not exit cleanly with exactly two control lines');
    fixture.lines.assertHealthy();
    fixture.stderr.assertWithinLimit();
    if (fixture.stderr.text() !== '')
      throw new Error('Legacy fixture emitted unexpected stderr');
  } catch (error) {
    failure = error;
    forced = true;
    try {
      await fixture.supervisor.terminate(fixture.child);
      await fixture.supervisor.waitForClose(fixture.child, shutdownTimeoutMilliseconds);
    } catch (cleanupError) {
      failure = new AggregateError([failure, cleanupError],
        'Legacy fixture shutdown and forced cleanup both failed');
    }
  } finally {
    writeFileSync(resolve(directory, 'fixture.stdout.log'), fixture.lines.text());
    writeFileSync(resolve(directory, 'fixture.stderr.log'), fixture.stderr.text());
    writeFileSync(resolve(directory, 'fixture.cleanup.txt'),
      `forced=${forced}\nexitCode=${fixture.child.exitCode}\nsignal=${fixture.child.signalCode}\n`);
  }
  if (failure !== undefined) throw failure;
}

async function runOne(options, revision, scenario, ordinal, entryPoint, supervisor) {
  const directory = resolve(options.workDirectory, revision,
    `${String(ordinal).padStart(3, '0')}-${scenario}`);
  const resultDirectory = resolve(directory, 'official-results');
  mkdirSync(resultDirectory, { recursive: true });
  const child = supervisor.spawn(options.javaExecutable,
    ['-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', options.classpath,
      fixtureMain, '--version', revision], {
      cwd: options.workDirectory,
      env: boundedEnvironment(),
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  const fixture = {
    child, supervisor,
    lines: boundedLineReader(child.stdout, 'legacy fixture stdout'),
    stderr: boundedCollector(child.stderr, 'legacy fixture stderr'),
  };
  child.once('error', (error) => fixture.lines.fail(error));
  writeFileSync(resolve(directory, 'fixture.pid'), `${child.pid}\n`);
  let failure;
  let result;
  try {
    const ready = parseReadyLine(
      await fixture.lines.next(startupTimeoutMilliseconds), revision);
    const arguments_ = [entryPoint, 'server', '--url',
      `http://${ready.host}:${ready.port}${ready.path}`,
      '--scenario', scenario, '--spec-version', revision,
      '-o', resultDirectory, '--verbose'];
    const command = await runBoundedCommand(process.execPath, arguments_, {
      timeoutMilliseconds: scenarioTimeoutMilliseconds,
      workingDirectory: options.suiteDirectory,
      supervisor,
    });
    writeFileSync(resolve(directory, 'official.stdout.log'), command.stdout);
    writeFileSync(resolve(directory, 'official.stderr.log'), command.stderr);
    if (command.timedOut || command.status !== 0 || command.signal !== null
        || command.outputFailure !== null || command.stderr !== '')
      throw new Error(`${revision}/${scenario} official CLI failed: `
        + JSON.stringify({ status: command.status, signal: command.signal,
          timedOut: command.timedOut, outputFailure: command.outputFailure,
          stderr: command.stderr }));
    const checksPath = exactlyOneChecksFile(resultDirectory);
    const checksBytes = readFileSync(checksPath);
    const observed = assertRawChecks(revision, scenario,
      JSON.parse(checksBytes.toString('utf8')));
    result = Object.freeze({
      ...observed,
      passed: true,
      checksJson: checksPath,
      checksJsonSha256: sha256(checksBytes),
      commandArguments: arguments_.slice(1),
    });
  } catch (error) {
    failure = error;
  } finally {
    try {
      await stopFixture(fixture, directory);
    } catch (cleanupError) {
      failure = failure === undefined ? cleanupError
        : new AggregateError([failure, cleanupError],
          `${revision}/${scenario} and fixture cleanup failed`);
    }
  }
  if (failure !== undefined) throw failure;
  return result;
}

export async function runLegacyPreparatory(options) {
  prepareWorkDirectory(options.workDirectory);
  const fixture = requireClasspath(options.classpath);
  const { pins, selection } = verifyManifestSet();
  const evidencePath = resolve(options.workDirectory, 'evidence.json');
  const evidence = {
    formatVersion: 1,
    evidenceClass: 'LEGACY_PREPARATORY_DEVELOPMENT_ONLY',
    releaseCandidateEvidence: false,
    status: 'PREPARING',
    suiteCommit: pins.officialConformanceSuite.commit,
    suiteCliSha256: pins.officialConformanceSuite.builtEntryPoint.sha256,
    jarSha256: sha256(readFileSync(fixture.jar)),
    fixtureSourceSha256: sha256(readFileSync(fixtureSource)),
    fixtureClassSha256: sha256(readFileSync(fixture.mainClass)),
    revisions: [...revisions],
    scenarios: [],
    failure: null,
  };
  persist(evidencePath, evidence);
  const supervisor = new ChildSupervisor();
  const removeSignalHandlers = installSignalHandlers(supervisor);
  let primaryFailure;
  try {
    verifyOfficialSuite(options.suiteDirectory, pins);
    const npmVersion = await runBoundedCommand('npm', ['--version'], {
      timeoutMilliseconds: 10_000,
      workingDirectory: options.suiteDirectory, supervisor,
    });
    if (npmVersion.timedOut || npmVersion.status !== 0
        || npmVersion.outputFailure !== null || npmVersion.stderr !== '')
      throw new Error('Pinned npm version could not be verified');
    verifyToolchain(pins, npmVersion.stdout.trim());
    const entryPoint = resolve(options.suiteDirectory,
      pins.officialConformanceSuite.entryPoint);
    const listing = await runBoundedCommand(process.execPath,
      [entryPoint, ...pins.officialConformanceSuite.listCommandArguments], {
        timeoutMilliseconds: 30_000,
        workingDirectory: options.suiteDirectory, supervisor,
      });
    if (listing.timedOut || listing.status !== 0
        || listing.outputFailure !== null || listing.stderr !== '')
      throw new Error('Pinned official scenario inventory could not be verified');
    verifyListedInventory(listing.stdout, selection, pins);
    evidence.status = 'RUNNING';
    persist(evidencePath, evidence);
    for (const revision of revisions) {
      for (const [index, scenario] of scenarios.entries()) {
        supervisor.throwIfCancellationRequested();
        try {
          evidence.scenarios.push(await runOne(
            options, revision, scenario, index + 1, entryPoint, supervisor));
          persist(evidencePath, evidence);
        } catch (error) {
          evidence.scenarios.push({ revision, scenario, passed: false,
            error: `${error}` });
          throw error;
        }
      }
    }
    if (sha256(readFileSync(fixture.jar)) !== evidence.jarSha256
        || sha256(readFileSync(fixture.mainClass)) !== evidence.fixtureClassSha256)
      throw new Error('Legacy preparatory JAR or fixture class changed during the run');
    evidence.status = 'PASSED';
    persist(evidencePath, evidence);
    return evidence;
  } catch (error) {
    primaryFailure = error;
    evidence.status = 'FAILED';
    evidence.failure = `${error}`;
    persist(evidencePath, evidence);
    throw error;
  } finally {
    removeSignalHandlers();
    try {
      await supervisor.terminateAndWaitForAll();
    } catch (cleanupError) {
      const combined = primaryFailure === undefined ? cleanupError
        : new AggregateError([primaryFailure, cleanupError],
          'Legacy conformance run and process cleanup both failed');
      evidence.status = 'FAILED';
      evidence.failure = `${combined}`;
      persist(evidencePath, evidence);
      throw combined;
    }
  }
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const result = await runLegacyPreparatory(parseOptions(process.argv.slice(2)));
    console.log(`Legacy MCP preparatory check passed: ${result.scenarios.length} `
      + 'official scenario/revision runs.');
  } catch (error) {
    console.error(error);
    if (process.exitCode === undefined || process.exitCode === 0) process.exitCode = 1;
  }
}
