#!/usr/bin/env node

import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  truncateSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, resolve } from 'node:path';
import { PassThrough } from 'node:stream';
import {
  boundedCollector,
  boundedLineReader,
  ChildSupervisor,
  exactlyOneChecksFile,
	installSignalHandlers,
	observedProfileDraft,
	runBoundedCommand,
  runOfficialConformance,
  RunnerCancelledError,
  verifyExplicitReleaseCandidate,
  verifyPublicFixtureClasspath,
  verifyReleaseCandidateManifest,
} from './run.mjs';
import { sha256 } from './verify.mjs';

class ReadinessAwaitingChildSupervisor extends ChildSupervisor {
  #readinessPath;
  #waitState = new Int32Array(new SharedArrayBuffer(4));

  constructor(readinessPath) {
    super();
    this.#readinessPath = readinessPath;
  }

  spawn(command, args, options) {
    const child = super.spawn(command, args, options);
    const deadline = Date.now() + 5_000;
    while (!existsSync(this.#readinessPath)) {
      if (Date.now() >= deadline)
        throw new Error('Child did not report signal-handler readiness');
      Atomics.wait(this.#waitState, 0, 0, 5);
    }
    return child;
  }
}

await boundedLineReaderStopsRetainingAfterOverflow();
await timedOutLineWaiterDoesNotConsumeTheNextLine();
boundedCollectorStopsRetainingAfterOverflow();
await boundedCommandDrainsPipesAndReportsOutputLimit();
await timedOutCommandThatExitsZeroRemainsTimedOut();
publicFixtureClasspathRequiresExactCandidateBoundary();
releaseCandidateProvenanceIsFailClosed();
resultTreeTraversalIsBounded();
observationDraftPreservesCompleteMultisetsAndSkipReasons();
await supervisorCancelsEveryChildAndRejectsLaterSpawns();
if (process.platform !== 'win32') await supervisorCancelsOrdinaryDescendants();
await failedSpawnDoesNotWaitForTerminationTimeouts();
await installedSignalHandlerRequestsBoundedCancellation();
await earlyFailureWritesDurableEvidence();
await incompleteReleaseEvidenceStaysFalse();

console.log('Official MCP conformance runner self-test passed.');

async function boundedLineReaderStopsRetainingAfterOverflow() {
  const stream = new PassThrough();
  const reader = boundedLineReader(stream, 'test control stream', 8);
  const pending = reader.next(1_000);
  stream.write('12345678');
  stream.write('overflow');
  stream.write('x'.repeat(1024 * 1024));
  stream.end();

  await assert.rejects(pending, /exceeded 8 bytes/);
  assert.equal(reader.text(), '12345678');
  assert.equal(reader.retainedByteCount(), 8);
  assert.throws(() => reader.assertHealthy(), /exceeded 8 bytes/);
}

async function timedOutLineWaiterDoesNotConsumeTheNextLine() {
  const stream = new PassThrough();
  const reader = boundedLineReader(stream, 'test control stream', 64);
  await assert.rejects(reader.next(10), /control-line timeout/);

  const pending = reader.next(1_000);
  stream.end('ready\n');
  assert.equal(await pending, 'ready');
  assert.equal(reader.lineCount(), 1);
}

function boundedCollectorStopsRetainingAfterOverflow() {
  const stream = new PassThrough();
  const collector = boundedCollector(stream, 'test diagnostic stream', 8);
  stream.write(Buffer.from('12345678'));
  stream.write(Buffer.from('overflow'));
  stream.end(Buffer.alloc(1024 * 1024));

  assert.equal(collector.text(), '12345678');
  assert.equal(collector.retainedByteCount(), 8);
  assert.throws(() => collector.assertWithinLimit(), /exceeded 8 bytes/);
}

async function boundedCommandDrainsPipesAndReportsOutputLimit() {
  const result = await runBoundedCommand(
    process.execPath,
    ['-e', "process.stdout.write(Buffer.alloc(2 * 1024 * 1024, 'x'))"],
    { timeoutMilliseconds: 5_000, workingDirectory: tmpdir() },
  );
  assert.equal(result.status, 0);
  assert.equal(result.timedOut, false);
  assert.match(result.outputFailure, /child stdout exceeded/);
  assert.ok(Buffer.byteLength(result.stdout, 'utf8') <= 1024 * 1024);
}

async function timedOutCommandThatExitsZeroRemainsTimedOut() {
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-timeout-self-test-'));
  const readinessPath = resolve(scratch, 'signal-handler-ready');
  const supervisor = new ReadinessAwaitingChildSupervisor(readinessPath);
  try {
    const script = [
      "const { writeFileSync } = require('node:fs');",
      "process.on('SIGTERM', () => process.exit(0));",
      "writeFileSync(process.argv[1], 'ready');",
      'setInterval(() => {}, 1000);',
    ].join('\n');
    const result = await runBoundedCommand(
      process.execPath,
      ['-e', script, readinessPath],
      { timeoutMilliseconds: 25, workingDirectory: scratch, supervisor },
    );
    assert.equal(result.status, 0);
    assert.equal(result.signal, null);
    assert.equal(result.timedOut, true);
  } finally {
    await supervisor.terminateAndWaitForAll();
    rmSync(scratch, { recursive: true, force: true });
  }
}

function publicFixtureClasspathRequiresExactCandidateBoundary() {
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-classpath-self-test-'));
  const fixtureClasses = resolve(scratch, 'target/conformance/public-fixture/classes');
  const fixtureMainClass = resolve(
    fixtureClasses, 'com/soklet/conformance/McpConformanceFixture.class',
  );
  const candidateJar = resolve(scratch, 'target/soklet-4.0.0-SNAPSHOT.jar');
  try {
    mkdirSync(resolve(fixtureMainClass, '..'), { recursive: true });
    writeFileSync(fixtureMainClass, 'fixture');
    writeFileSync(candidateJar, 'candidate');
    const classpath = [
      'target/conformance/public-fixture/classes',
      'target/soklet-4.0.0-SNAPSHOT.jar',
    ].join(delimiter);

    assert.deepEqual(verifyPublicFixtureClasspath(classpath, scratch), {
      fixtureClasses,
      candidateJar,
    });
		const releaseCandidateJar = resolve(scratch, 'release/soklet-4.0.0.jar');
		mkdirSync(resolve(releaseCandidateJar, '..'), { recursive: true });
		writeFileSync(releaseCandidateJar, 'release-candidate');
		const releaseClasspath = [fixtureClasses, releaseCandidateJar].join(delimiter);
		assert.deepEqual(
			verifyPublicFixtureClasspath(releaseClasspath, scratch, releaseCandidateJar),
			{ fixtureClasses, candidateJar: releaseCandidateJar },
		);
		assert.throws(
			() => verifyPublicFixtureClasspath(classpath, scratch, releaseCandidateJar),
			/validated release-candidate JAR/,
		);
    assert.throws(() => verifyPublicFixtureClasspath(
      ['target/soklet-4.0.0-SNAPSHOT.jar',
        'target/conformance/public-fixture/classes'].join(delimiter),
      scratch,
    ), /classpath must be exactly/);
    assert.throws(() => verifyPublicFixtureClasspath(
      ['target/conformance/public-fixture/classes', 'target/classes'].join(delimiter),
      scratch,
    ), /classpath must be exactly/);
    assert.throws(() => verifyPublicFixtureClasspath(
      [classpath, 'target/test-classes'].join(delimiter), scratch,
    ), /classpath must be exactly/);

    rmSync(fixtureMainClass);
    assert.throws(() => verifyPublicFixtureClasspath(classpath, scratch),
      /classpath must be exactly/);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

function releaseCandidateProvenanceIsFailClosed() {
	const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-release-self-test-'));
	const commit = '1'.repeat(40);
	const suiteCommit = '2'.repeat(40);
	const pins = {
		protocolVersion: '2026-07-28',
		officialConformanceSuite: { commit: suiteCommit },
	};
	const pom = resolve(scratch, 'soklet-4.0.0.pom');
	const mainJar = resolve(scratch, 'soklet-4.0.0.jar');
	const sourcesJar = resolve(scratch, 'soklet-4.0.0-sources.jar');
	const javadocJar = resolve(scratch, 'soklet-4.0.0-javadoc.jar');
	const manifestPath = resolve(scratch, 'release-candidate.json');
	const pomBytes = Buffer.from([
		'<?xml version="1.0" encoding="UTF-8"?>',
		'<project xmlns="http://maven.apache.org/POM/4.0.0">',
		'  <modelVersion>4.0.0</modelVersion>',
		'  <groupId>com.soklet</groupId>',
		'  <artifactId>soklet</artifactId>',
		'  <version>4.0.0</version>',
		'  <packaging>jar</packaging>',
		'</project>',
		'',
	].join('\n'));
	const jarBytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00]);
	try {
		writeFileSync(pom, pomBytes);
		writeFileSync(mainJar, jarBytes);
		writeFileSync(sourcesJar, Buffer.concat([jarBytes, Buffer.from('sources')]));
		writeFileSync(javadocJar, Buffer.concat([jarBytes, Buffer.from('javadoc')]));
		const manifest = {
			formatVersion: 1,
			candidateCommit: commit,
			protocolVersion: pins.protocolVersion,
			suiteCommit,
			coordinates: {
				groupId: 'com.soklet',
				artifactId: 'soklet',
				version: '4.0.0',
			},
			artifacts: {
				pom: { path: pom, sha256: sha256(pomBytes) },
				mainJar: { path: mainJar, sha256: sha256(jarBytes) },
				sourcesJar: {
					path: sourcesJar,
					sha256: sha256(Buffer.concat([jarBytes, Buffer.from('sources')])),
				},
				javadocJar: {
					path: javadocJar,
					sha256: sha256(Buffer.concat([jarBytes, Buffer.from('javadoc')])),
				},
			},
		};
		const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
		writeFileSync(manifestPath, manifestBytes);
		const manifestSha256 = sha256(manifestBytes);
		const verified = verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, commit, pins,
		);
		assert.equal(verified.candidateJar, mainJar);
		assert.equal(verified.evidence.source, 'reviewed-manifest');
		assert.equal(verified.evidence.manifestSha256, manifestSha256);
		assert.equal(verified.evidence.artifacts.mainJar.sha256, sha256(jarBytes));

		const explicit = verifyExplicitReleaseCandidate(manifest, pins);
		assert.equal(explicit.evidence.source, 'explicit-artifacts');
		assert.equal(explicit.evidence.manifestSha256, null);
		const nonJarBytes = Buffer.from('not a jar');
		writeFileSync(sourcesJar, nonJarBytes);
		assert.throws(() => verifyExplicitReleaseCandidate({
			...manifest,
			artifacts: {
				...manifest.artifacts,
				sourcesJar: { path: sourcesJar, sha256: sha256(nonJarBytes) },
			},
		}, pins), /sourcesJar must be a JAR\/ZIP file/);
		writeFileSync(sourcesJar, Buffer.concat([jarBytes, Buffer.from('sources')]));

		writeFileSync(mainJar, Buffer.concat([jarBytes, Buffer.from('tampered')]));
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, commit, pins,
		), /mainJar SHA-256 mismatch/);
		writeFileSync(mainJar, jarBytes);

		rmSync(javadocJar);
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, commit, pins,
		), /javadocJar does not exist/);
		writeFileSync(javadocJar, Buffer.concat([jarBytes, Buffer.from('javadoc')]));

		if (process.platform !== 'win32') {
			const linkedManifest = resolve(scratch, 'linked-release-candidate.json');
			symlinkSync(manifestPath, linkedManifest);
			assert.throws(() => verifyReleaseCandidateManifest(
				linkedManifest, manifestSha256, commit, pins,
			), /manifest must be a regular non-symbolic-link file/i);
			rmSync(sourcesJar);
			symlinkSync(mainJar, sourcesJar);
			assert.throws(() => verifyReleaseCandidateManifest(
				manifestPath, manifestSha256, commit, pins,
			), /sourcesJar must be a regular non-symbolic-link file/);
			rmSync(sourcesJar);
			writeFileSync(sourcesJar, Buffer.concat([jarBytes, Buffer.from('sources')]));
		}

		const nonCanonicalManifest = Buffer.from(`${JSON.stringify(manifest)}\n`);
		writeFileSync(manifestPath, nonCanonicalManifest);
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, sha256(nonCanonicalManifest), commit, pins,
		), /must be canonical two-space JSON/);
		writeFileSync(manifestPath, manifestBytes);

		writeFileSync(manifestPath, `${JSON.stringify({ ...manifest, candidateCommit: '3'.repeat(40) }, null, 2)}\n`);
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, commit, pins,
		), /manifest SHA-256 mismatch/);
		writeFileSync(manifestPath, manifestBytes);
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, '4'.repeat(40), pins,
		), /does not match expected commit/);
		assert.throws(() => verifyReleaseCandidateManifest(
			manifestPath, manifestSha256, commit,
			{ ...pins, protocolVersion: '2025-11-25' },
		), /does not match pinned/);
	} finally {
		rmSync(scratch, { recursive: true, force: true });
	}
}

function resultTreeTraversalIsBounded() {
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-result-tree-self-test-'));
  try {
    const valid = resolve(scratch, 'valid');
    mkdirSync(resolve(valid, 'nested'), { recursive: true });
    const validChecks = resolve(valid, 'nested/checks.json');
    writeFileSync(validChecks, '[]\n');
    assert.equal(exactlyOneChecksFile(valid), validChecks);

    const oversized = resolve(scratch, 'oversized');
    mkdirSync(oversized);
    const oversizedChecks = resolve(oversized, 'checks.json');
    writeFileSync(oversizedChecks, '[]\n');
    truncateSync(oversizedChecks, 8 * 1024 * 1024 + 1);
    assert.throws(() => exactlyOneChecksFile(oversized), /exceeded .* bytes/);

    const tooMany = resolve(scratch, 'too-many');
    mkdirSync(tooMany);
    writeFileSync(resolve(tooMany, 'checks.json'), '[]\n');
    for (let index = 0; index < 128; ++index)
      writeFileSync(resolve(tooMany, `extra-${String(index).padStart(3, '0')}.txt`), 'x');
    assert.throws(() => exactlyOneChecksFile(tooMany), /exceeded 128 files/);

    const tooDeep = resolve(scratch, 'too-deep');
    let directory = tooDeep;
    for (let depth = 0; depth < 9; ++depth) directory = resolve(directory, `d${depth}`);
    mkdirSync(directory, { recursive: true });
    writeFileSync(resolve(directory, 'checks.json'), '[]\n');
    assert.throws(() => exactlyOneChecksFile(tooDeep), /exceeded depth 8/);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

function observationDraftPreservesCompleteMultisetsAndSkipReasons() {
	const draft = observedProfileDraft('tools-list', [
		{ id: 'tools-list', status: 'SUCCESS' },
		{ id: 'tools-list', status: 'SUCCESS' },
		{
			id: 'conditional-list-change',
			status: 'SKIPPED',
			errorMessage: 'Capability flag is truthfully absent',
		},
		{
			id: 'server-stateless-list-change',
			status: 'SKIPPED',
			details: {
				note: 'Server did not declare the optional listChanged capability',
			},
		},
		{
			id: 'wire-schema-valid',
			status: 'SUCCESS',
			details: { messagesValidated: 2 },
		},
	], 4, 'a'.repeat(40));

	assert.equal(draft.id, 'tools-list.phase4.v1');
	assert.deepEqual(draft.checks, [
		{
			id: 'conditional-list-change',
			status: 'SKIPPED',
			count: 1,
			reason: 'Capability flag is truthfully absent',
		},
		{
			id: 'server-stateless-list-change',
			status: 'SKIPPED',
			count: 1,
			reason: 'Server did not declare the optional listChanged capability',
		},
		{ id: 'tools-list', status: 'SUCCESS', count: 2 },
	]);
	assert.equal(draft.automaticWireChecks['wire-schema-valid'], 1);
	assert.equal(draft.automaticWireChecks['wire-schema-harness-error'], 0);
}

async function supervisorCancelsEveryChildAndRejectsLaterSpawns() {
  const supervisor = new ChildSupervisor({ terminationGraceMilliseconds: 50 });
  const cooperative = supervisor.spawn(
    process.execPath,
    ['-e', "process.stdout.write('ready\\n'); setInterval(() => {}, 1000)"],
    { stdio: ['ignore', 'pipe', 'ignore'] },
  );
  const stubborn = supervisor.spawn(
    process.execPath,
    ['-e', "process.on('SIGTERM', () => {}); process.stdout.write('ready\\n'); "
      + 'setInterval(() => {}, 1000)'],
    { stdio: ['ignore', 'pipe', 'ignore'] },
  );
  await Promise.all([once(cooperative.stdout, 'data'), once(stubborn.stdout, 'data')]);
  assert.equal(supervisor.activeChildCount, 2);

  supervisor.requestCancellation('SIGTERM');
  await supervisor.terminateAndWaitForAll();

  assert.equal(supervisor.activeChildCount, 0);
  assert.notEqual(cooperative.signalCode, null);
  assert.equal(stubborn.signalCode, 'SIGKILL');
  assert.throws(
    () => supervisor.spawn(process.execPath, ['-e', ''], { stdio: 'ignore' }),
    RunnerCancelledError,
  );
}

async function installedSignalHandlerRequestsBoundedCancellation() {
  const processObject = new EventEmitter();
  processObject.exitCode = undefined;
  const supervisor = new ChildSupervisor({ terminationGraceMilliseconds: 25 });
  const remove = installSignalHandlers(supervisor, processObject);
  try {
    processObject.emit('SIGTERM');
    await supervisor.terminateAndWaitForAll();
    assert.equal(processObject.exitCode, 143);
    assert.equal(supervisor.cancellationSignal, 'SIGTERM');
  } finally {
    remove();
  }
  assert.equal(processObject.listenerCount('SIGINT'), 0);
  assert.equal(processObject.listenerCount('SIGTERM'), 0);
}

async function supervisorCancelsOrdinaryDescendants() {
  const supervisor = new ChildSupervisor({ terminationGraceMilliseconds: 50 });
  const parentScript = [
    "const { spawn } = require('node:child_process');",
    "const descendant = spawn(process.execPath, ['-e',",
    "  \"process.on('SIGTERM', () => {}); setInterval(() => {}, 1000)\"],",
    "  { stdio: 'ignore' });",
    "process.on('SIGTERM', () => {});",
    "setTimeout(() => process.stdout.write(`${descendant.pid}\\n`), 100);",
    "setInterval(() => {}, 1000);",
  ].join('\n');
  const parent = supervisor.spawn(
    process.execPath,
    ['-e', parentScript],
    { stdio: ['ignore', 'pipe', 'ignore'] },
  );
  let descendantPid;
  try {
    const [chunk] = await once(parent.stdout, 'data');
    descendantPid = Number(chunk.toString('utf8').trim());
    assert.ok(Number.isInteger(descendantPid) && descendantPid > 0);
    assert.equal(processExists(descendantPid), true);

    supervisor.requestCancellation('SIGTERM');
    await supervisor.terminateAndWaitForAll();

    assert.equal(supervisor.activeChildCount, 0);
    assert.equal(parent.signalCode, 'SIGKILL');
    assert.equal(processExists(descendantPid), false, 'descendant must not survive its leader');
  } finally {
    if (!supervisor.cancellationRequested) supervisor.requestCancellation('SIGTERM');
    await supervisor.terminateAndWaitForAll();
  }
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH') return false;
    throw error;
  }
}

async function failedSpawnDoesNotWaitForTerminationTimeouts() {
  const missingExecutable = resolve(
    tmpdir(), `soklet-missing-conformance-command-${process.pid}`,
  );
  await assert.rejects(() => runBoundedCommand(missingExecutable, [], {
    timeoutMilliseconds: 100,
    workingDirectory: tmpdir(),
  }), /ENOENT/);
}

async function earlyFailureWritesDurableEvidence() {
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-runner-self-test-'));
  const workDirectory = resolve(scratch, 'evidence');
  const processObject = new EventEmitter();
  processObject.exitCode = undefined;
  try {
    await assert.rejects(() => runOfficialConformance({
      suiteDirectory: scratch,
      workDirectory,
      classpath: 'unused',
      projectRoot: scratch,
      javaExecutable: 'java',
		phase: 7,
	}, { processObject }), /Verification must target current implementation Phase/);

    const evidence = JSON.parse(readFileSync(resolve(workDirectory, 'evidence.json'), 'utf8'));
    assert.equal(evidence.status, 'FAILED');
	assert.equal(evidence.phase, 7);
	assert.equal(evidence.mode, 'verify');
	assert.equal(evidence.evidenceClass, 'CANDIDATE_ARTIFACT_DEVELOPMENT_ONLY');
	assert.equal(evidence.releaseCandidateEvidence, false);
	assert.equal(Object.hasOwn(evidence, 'releaseCandidateProvenance'), false);
    assert.equal(evidence.suiteCommit, null);
    assert.deepEqual(evidence.scenarios, []);
	assert.match(evidence.failure, /Verification must target current implementation Phase/);
    assert.deepEqual(
      readdirSync(workDirectory).sort(),
      ['evidence.json'],
      'atomic evidence update must not leave a temporary file',
    );
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

async function incompleteReleaseEvidenceStaysFalse() {
	const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-release-evidence-self-test-'));
	const workDirectory = resolve(scratch, 'evidence');
	const processObject = new EventEmitter();
	processObject.exitCode = undefined;
	try {
		await assert.rejects(() => runOfficialConformance({
			suiteDirectory: scratch,
			workDirectory,
			classpath: 'unused',
			projectRoot: scratch,
			javaExecutable: 'java',
			phase: 5,
			mode: 'release',
		}, { processObject }), /requires --candidate-commit/);

		const evidence = JSON.parse(readFileSync(resolve(workDirectory, 'evidence.json'), 'utf8'));
		assert.equal(evidence.status, 'FAILED');
		assert.equal(evidence.mode, 'release');
		assert.equal(evidence.evidenceClass, 'IMMUTABLE_RELEASE_CANDIDATE');
		assert.equal(evidence.releaseCandidateEvidence, false);
		assert.equal(evidence.releaseCandidateProvenance, null);
		assert.deepEqual(evidence.scenarios, []);
		assert.match(evidence.failure, /requires --candidate-commit/);
		assert.deepEqual(readdirSync(workDirectory).sort(), ['evidence.json']);
	} finally {
		rmSync(scratch, { recursive: true, force: true });
	}
}
