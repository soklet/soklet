#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { existsSync, lstatSync } from 'node:fs';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyPublicFixtureClasspath } from './run.mjs';
import { activeScenarios, verifyManifestSet } from './verify.mjs';

const driverMain = 'com.soklet.conformance.McpLocalSimulatorScenarioDriver';
const driverRelativePath =
  'com/soklet/conformance/McpLocalSimulatorScenarioDriver.class';
const timeoutMilliseconds = 120_000;
const maximumOutputBytes = 1024 * 1024;
const maximumDiagnosticCharacters = 16 * 1024;

export function localSimulatorRows(selection) {
  const scenarios = activeScenarios(selection, 5);
  if (scenarios.length !== 39)
    throw new Error('Local simulator manifest projection must contain exactly 39 RUN rows');

  const names = new Set();
  return Object.freeze(scenarios.map((scenario, index) => {
    const expectedOrdinal = index === 0 ? 1 : index + 2;
    if (scenario.ordinal !== expectedOrdinal)
      throw new Error('Local simulator rows differ from strict manifest ordinal order');
    if (typeof scenario.name !== 'string'
        || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(scenario.name)
        || names.has(scenario.name))
      throw new Error('Local simulator rows contain an invalid or duplicate name');
    names.add(scenario.name);
    return Object.freeze({ ordinal: scenario.ordinal, name: scenario.name });
  }));
}

export function localSimulatorDriverArguments(rows) {
  return rows.map((row) => `${row.ordinal}:${row.name}`);
}

export function expectedLocalSimulatorOutput(rows) {
  return Buffer.from(rows.map((row) =>
    `PASS\t${row.ordinal}\t${row.name}\n`).join(''), 'utf8');
}

export function verifyLocalSimulatorDriverResult(result, expectedOutput) {
  if (result.error !== undefined)
    throw new Error('Local simulator driver could not execute', { cause: result.error });
  const stdout = exactUtf8(result.stdout, 'stdout');
  const stderr = exactUtf8(result.stderr, 'stderr');
  if (result.status !== 0 || result.signal !== null) {
    throw new Error(
      `Local simulator driver exited status=${result.status} signal=${result.signal}`
        + `\nstdout:\n${diagnosticText(stdout)}`
        + `\nstderr:\n${diagnosticText(stderr)}`,
    );
  }
  if (stderr.length !== 0)
    throw new Error('Local simulator driver wrote unexpected stderr');
  if (!stdout.equals(expectedOutput))
    throw new Error('Local simulator driver output differs from the exact 39-row projection');
  return stdout;
}

function diagnosticText(value) {
  const text = value.toString('utf8');
  if (text.length === 0)
    return '<empty>';
  if (text.length <= maximumDiagnosticCharacters)
    return text;
  return `${text.slice(0, maximumDiagnosticCharacters)}\n<truncated>`;
}

export function runLocalSimulator(options, { spawn = spawnSync } = {}) {
  const projectRoot = resolve(options.projectRoot ?? process.cwd());
  const { fixtureClasses, candidateJar } = verifyPublicFixtureClasspath(
    options.classpath, projectRoot,
  );
  const testClasses = resolve(dirname(fixtureClasses), 'test-classes');
  const driverClass = resolve(testClasses, driverRelativePath);
  const testStats = existsSync(testClasses) ? lstatSync(testClasses) : null;
  const driverStats = existsSync(driverClass) ? lstatSync(driverClass) : null;
  if (testStats === null || !testStats.isDirectory() || testStats.isSymbolicLink()
      || driverStats === null || !driverStats.isFile() || driverStats.isSymbolicLink())
    throw new Error('Local simulator driver classes are missing or unsafe');

  const rows = localSimulatorRows(verifyManifestSet().selection);
  const classpath = [testClasses, fixtureClasses, candidateJar].join(delimiter);
  const result = spawn(options.javaExecutable ?? 'java', [
    '-ea', '-classpath', classpath, driverMain,
    ...localSimulatorDriverArguments(rows),
  ], {
    cwd: projectRoot,
    env: boundedEnvironment(options.javaExecutable),
    encoding: null,
    maxBuffer: maximumOutputBytes,
    shell: false,
    timeout: timeoutMilliseconds,
  });
  const output = verifyLocalSimulatorDriverResult(
    result, expectedLocalSimulatorOutput(rows),
  );
  process.stdout.write(output);
  return Object.freeze({ rows, output });
}

function exactUtf8(value, description) {
  if (!Buffer.isBuffer(value))
    throw new Error(`Local simulator driver ${description} was not captured as bytes`);
  const text = value.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(value))
    throw new Error(`Local simulator driver ${description} is not UTF-8`);
  return value;
}

function boundedEnvironment(javaExecutable) {
  const environment = {
    LANG: 'C.UTF-8',
    LC_ALL: 'C.UTF-8',
    TZ: 'UTC',
  };
  for (const name of ['HOME', 'JAVA_HOME', 'PATH', 'SystemRoot', 'TMPDIR'])
    if (process.env[name] !== undefined) environment[name] = process.env[name];
  if (javaExecutable !== undefined)
    environment.JAVA_HOME = dirname(dirname(resolve(javaExecutable)));
  return environment;
}

function parseArguments(arguments_) {
  const values = new Map();
  for (let index = 0; index < arguments_.length; index += 2) {
    const name = arguments_[index];
    const value = arguments_[index + 1];
    if (!['--classpath', '--project-root', '--java'].includes(name)
        || value === undefined || values.has(name))
      throw new Error('Invalid local simulator command-line arguments');
    values.set(name, value);
  }
  if (!values.has('--classpath'))
    throw new Error('Missing required --classpath argument');
  return Object.freeze({
    classpath: values.get('--classpath'),
    projectRoot: values.get('--project-root') ?? process.cwd(),
    javaExecutable: values.get('--java'),
  });
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    runLocalSimulator(parseArguments(process.argv.slice(2)));
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  }
}
