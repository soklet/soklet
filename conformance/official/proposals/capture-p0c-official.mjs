#!/usr/bin/env node

// Development evidence only. This captures the upstream verdict verbatim and
// never changes the official expected profile or release conformance runner.
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ChildSupervisor,
  exactlyOneChecksFile,
  installSignalHandlers,
  runBoundedCommand,
} from '../run.mjs';
import { validateFinalTagWire } from '../validate-final-tag-wire.mjs';
import { verifyProfileEvidence } from '../verify-profile-evidence.mjs';
import {
  officialScenarioArguments,
  sha256,
  verifyListedInventory,
  verifyManifestSet,
  verifyOfficialSuite,
  verifyToolchain,
} from '../verify.mjs';
import { fixtureClassesTreeSha256 } from './p0c-disposition.mjs';
import {
  captureElicitationSocketExchanges,
  preparePublicFixture,
  startPublicFixture,
  stopPublicFixture,
  writeControlReceipt,
} from './capture-elicitation-socket.mjs';

const officialRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const projectRoot = resolve(officialRoot, '..', '..');
const scenarioName = 'server-stateless';
const commandTimeoutMilliseconds = 60_000;
const preflightTimeoutMilliseconds = 30_000;
const runIdPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{7,127}$/;

export function parseCaptureArguments(arguments_) {
  if (arguments_.length !== 10)
    throw new Error('Usage: node capture-p0c-official.mjs --suite-dir ABSOLUTE --candidate-jar ABSOLUTE --java-home ABSOLUTE --output-dir ABSOLUTE --run-id ID');
  const names = ['--suite-dir', '--candidate-jar', '--java-home', '--output-dir', '--run-id'];
  const values = {};
  for (let index = 0; index < names.length; index++) {
    if (arguments_[index * 2] !== names[index])
      throw new Error(`Expected ${names[index]} at argument ${index * 2 + 1}`);
    values[names[index].slice(2)] = arguments_[index * 2 + 1];
  }
  for (const name of names.slice(0, 4)) {
    const value = values[name.slice(2)];
    if (typeof value !== 'string' || !isAbsolute(value) || value.includes('\0'))
      throw new Error(`${name} must be an absolute path`);
  }
  if (!runIdPattern.test(values['run-id']))
    throw new Error('--run-id must be 8–128 ASCII letters, digits, dot, dash or underscore, starting with a letter or digit');
  return Object.freeze({
    suiteDirectory: resolve(values['suite-dir']),
    candidateJarPath: resolve(values['candidate-jar']),
    javaHome: resolve(values['java-home']),
    outputDirectory: resolve(values['output-dir']),
    runId: values['run-id'],
  });
}

export function officialCaptureReceipt({ pins, runId, prepared, result, checksPath,
  stdoutPath, stderrPath, endpoint }) {
  return Object.freeze({
    formatVersion: 1,
    scenario: scenarioName,
    protocolVersion: pins.protocolVersion,
    suiteCommit: pins.officialConformanceSuite.commit,
    suiteSourceTreeSha256: pins.officialConformanceSuite.sourceTree.sha256,
    candidateJarSha256: prepared.candidateJarSha256,
    fixtureSourceSha256: prepared.fixtureSourceSha256,
    fixtureClassesSha256: prepared.fixtureClassesSha256,
    runId,
    endpoint,
    checksSha256: sha256(readFileSync(checksPath)),
    stdoutSha256: sha256(readFileSync(stdoutPath)),
    stderrSha256: sha256(readFileSync(stderrPath)),
    exitCode: result.status,
    signal: result.signal,
    timedOut: result.timedOut,
    outputFailure: result.outputFailure,
  });
}

export async function captureP0cProposal(options) {
  const { suiteDirectory, candidateJarPath, javaHome, outputDirectory, runId } = options;
  requireRegularFile(candidateJarPath, 'candidate JAR');
  requireRealDirectory(suiteDirectory, 'pinned suite');
  requireRealDirectory(javaHome, 'Java home');
  if (existsSync(outputDirectory))
    throw new Error('Proposal output directory must be absent');

  const { pins, selection } = verifyManifestSet();
  const scenario = selection.scenarios.find((entry) => entry.name === scenarioName);
  if (scenario === undefined || scenario.selection !== 'RUN'
      || scenario.expectedCheckProfile !== 'server-stateless.phase5.v1')
    throw new Error('Pinned server-stateless selection changed');
  verifyProfileEvidence({ projectRoot });
  verifyOfficialSuite(suiteDirectory, pins);

  const supervisor = new ChildSupervisor();
  const removeSignalHandlers = installSignalHandlers(supervisor);
  let fixture;
  let failure;
  let capture;
  let prepared;
  let officialReceipt;
  let processReceipt;
  let finalizedControlReceipt;
  let stage = 'preflight';
  try {
    const npmResult = await runBoundedCommand('npm', ['--version'], {
      timeoutMilliseconds: 10_000, workingDirectory: suiteDirectory, supervisor,
    });
    requireSuccessfulCommand(npmResult, 'pinned npm version');
    verifyToolchain(pins, npmResult.stdout.trim());
    const entryPoint = resolve(suiteDirectory, pins.officialConformanceSuite.entryPoint);
    const listing = await runBoundedCommand(process.execPath,
      [entryPoint, ...pins.officialConformanceSuite.listCommandArguments], {
        timeoutMilliseconds: preflightTimeoutMilliseconds,
        workingDirectory: suiteDirectory, supervisor,
      });
    requireSuccessfulCommand(listing, 'official scenario listing');
    verifyListedInventory(listing.stdout, selection, pins);
    const golden = validateFinalTagWire({ suiteDirectory });
    if (golden.validated.length !== 48)
      throw new Error('Final-tag golden-message count changed');

    mkdirSync(outputDirectory, { recursive: false });
    const fixtureDirectory = resolve(outputDirectory, 'fixture');
    const controlDirectory = resolve(outputDirectory, 'control');
    const officialDirectory = resolve(outputDirectory, 'official');
    mkdirSync(fixtureDirectory);
    mkdirSync(controlDirectory);
    mkdirSync(officialDirectory);
    writeJson(resolve(outputDirectory, 'capture-invocation.json'), {
      formatVersion: 1,
      kind: 'p0c-proposal-capture',
      runId,
      suiteDirectory,
      suiteCommit: pins.officialConformanceSuite.commit,
      suiteSourceTreeSha256: pins.officialConformanceSuite.sourceTree.sha256,
      candidateJarPath,
      candidateJarSha256: sha256(readFileSync(candidateJarPath)),
      javaHome,
      nodeExecutable: process.execPath,
      nodeVersion: process.version,
      npmVersion: npmResult.stdout.trim(),
      goldenMessagesValidated: golden.validated.length,
      proposalOnly: true,
    });
    stage = 'fixture-build';
    prepared = await preparePublicFixture({
      candidateJarPath, outputDirectory: fixtureDirectory, javaHome,
    });
    verifyPreparedIdentity(prepared, candidateJarPath);
    stage = 'fixture-start';
    fixture = await startPublicFixture({ prepared, outputDirectory: fixtureDirectory });
    stage = 'socket-controls';
    capture = await captureElicitationSocketExchanges({
      fixture, prepared, runId, outputDirectory: controlDirectory,
    });
    stage = 'official-command';
    const resultDirectory = resolve(officialDirectory, 'official-results');
    mkdirSync(resultDirectory);
    const commandArguments = [entryPoint, ...officialScenarioArguments(pins, {
      fixtureUrl: fixture.endpoint,
      scenarioName,
      outputDirectory: resultDirectory,
    })];
    writeJson(resolve(officialDirectory, 'command.json'), {
      executable: process.execPath,
      arguments: commandArguments,
      workingDirectory: suiteDirectory,
      fixtureEndpoint: fixture.endpoint,
      timeoutMilliseconds: commandTimeoutMilliseconds,
      runId,
    });
    const result = await runBoundedCommand(process.execPath, commandArguments, {
      timeoutMilliseconds: commandTimeoutMilliseconds,
      workingDirectory: suiteDirectory,
      supervisor,
    });
    const stdoutPath = resolve(officialDirectory, 'official.stdout.log');
    const stderrPath = resolve(officialDirectory, 'official.stderr.log');
    writeFileSync(stdoutPath, result.stdout);
    writeFileSync(stderrPath, result.stderr);
    // Keep the complete upstream result tree and a stable exact raw copy for
    // the separate proposal verifier. No status is rewritten or normalized.
    const checksSource = exactlyOneChecksFile(resultDirectory);
    const checksPath = resolve(officialDirectory, 'checks.json');
    copyFileSync(checksSource, checksPath);
    officialReceipt = officialCaptureReceipt({
      pins, runId, prepared, result, checksPath, stdoutPath, stderrPath,
      endpoint: fixture.endpoint,
    });
    writeJson(resolve(officialDirectory, 'official-receipt.json'), officialReceipt);
    verifyPreparedIdentity(prepared, candidateJarPath);
    verifyOfficialSuite(suiteDirectory, pins);
    stage = 'fixture-stop';
  } catch (error) {
    failure = error;
  } finally {
    if (fixture !== undefined) {
      try {
        processReceipt = await stopPublicFixture({
          fixture, outputDirectory: resolve(outputDirectory, 'fixture'),
        });
        requireCleanFixtureStop(processReceipt);
        verifyPreparedIdentity(prepared, candidateJarPath);
        verifyOfficialSuite(suiteDirectory, pins);
      } catch (cleanupError) {
        failure = failure === undefined ? cleanupError
          : new AggregateError([failure, cleanupError], 'Capture and fixture cleanup failed');
      }
    }
    try {
      await supervisor.terminateAndWaitForAll();
    } catch (cleanupError) {
      failure = failure === undefined ? cleanupError
        : new AggregateError([failure, cleanupError], 'Capture and official cleanup failed');
    }
    removeSignalHandlers();
  }

  if (capture !== undefined && processReceipt !== undefined) {
    finalizedControlReceipt = writeControlReceipt({
      receipt: capture.receipt,
      process: processReceipt,
      outputDirectory: resolve(outputDirectory, 'control'),
    });
  }
  if (existsSync(outputDirectory)) {
    writeJson(resolve(outputDirectory, 'capture-status.json'), {
      formatVersion: 1,
      kind: 'p0c-proposal-capture',
      runId,
      stage,
      status: failure === undefined ? 'CAPTURED' : 'INCOMPLETE',
      officialReceiptPresent: officialReceipt !== undefined,
      controlReceiptPresent: capture !== undefined && processReceipt !== undefined,
      failure: failure === undefined ? null : String(failure),
      releaseCandidateEvidence: false,
    });
  }
  if (failure !== undefined) throw failure;
  return Object.freeze({ officialReceipt, controlReceipt: finalizedControlReceipt,
    processReceipt });
}

function requireCleanFixtureStop(processReceipt) {
  if (processReceipt?.ready !== true || processReceipt.stopped !== true
      || processReceipt.exitCode !== 0 || processReceipt.signal !== null
      || processReceipt.forcedCleanup !== false)
    throw new Error('Public fixture did not report clean bounded shutdown');
}

function verifyPreparedIdentity(prepared, candidateJarPath) {
  requireRegularFile(candidateJarPath, 'candidate JAR');
  if (sha256(readFileSync(candidateJarPath)) !== prepared.candidateJarSha256)
    throw new Error('Candidate JAR changed during proposal capture');
  if (fixtureClassesTreeSha256(prepared.fixtureClassesDirectory)
      !== prepared.fixtureClassesSha256)
    throw new Error('Public fixture classes changed during proposal capture');
  const fixtureSource = resolve(officialRoot,
    'public-fixture-src/com/soklet/conformance/McpConformanceFixture.java');
  if (sha256(readFileSync(fixtureSource)) !== prepared.fixtureSourceSha256)
    throw new Error('Public fixture source changed during proposal capture');
}

function requireSuccessfulCommand(result, label) {
  if (result.timedOut || result.status !== 0 || result.signal !== null
      || result.outputFailure !== null)
    throw new Error(`${label} failed or exceeded its bound`);
}

function requireRegularFile(path, label) {
  if (!existsSync(path))
    throw new Error(`${label} is missing`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink() || stats.size > 128 * 1024 * 1024)
    throw new Error(`${label} must be a bounded regular file`);
}

function requireRealDirectory(path, label) {
  if (!existsSync(path))
    throw new Error(`${label} is missing`);
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    throw new Error(`${label} must be a real directory`);
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await captureP0cProposal(parseCaptureArguments(process.argv.slice(2)));
    console.log('Captured proposal-only official and Elicitation evidence; run the separate exact disposition verifier for review.');
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  }
}
