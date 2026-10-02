#!/usr/bin/env node

import {
  existsSync, lstatSync, mkdirSync, readFileSync, readdirSync,
  renameSync, writeFileSync,
} from 'node:fs';
import { delimiter, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  boundedCollector, boundedLineReader, ChildSupervisor,
  exactlyOneChecksFile, installSignalHandlers, runBoundedCommand,
  verifyReleaseCandidateOptions, verifyProjectCheckout,
  verifyCandidatePomMatchesCheckout, assertReleaseCandidateUnchanged,
} from '../run.mjs';
import {
  sha256, verifyListedInventory, verifyManifestSet,
  verifyOfficialSuite, verifyToolchain,
  sourceTreeIdentity,
} from '../verify.mjs';
import { runRuntimeSupplement } from './runtime-supplement.mjs';

// Selected legacy profiles remain separate from the 46-row modern selection.
export const revisions = Object.freeze(['2025-06-18', '2025-11-25']);
export const scenarios = Object.freeze([
  'server-initialize', 'ping', 'tools-list',
  'tools-call-simple-text', 'tools-call-error',
  'prompts-list', 'prompts-get-simple', 'prompts-get-with-args',
  'prompts-get-embedded-resource', 'prompts-get-with-image',
  'resources-list', 'resources-read-text', 'resources-read-binary',
  'resources-templates-read',
]);
export const profiles = Object.freeze([
  Object.freeze({ id: 'stateless-baseline', revisions, scenarios }),
  Object.freeze({ id: 'stateless-expansion', revisions,
    scenarios: Object.freeze(['completion-complete', 'tools-call-with-progress']) }),
  Object.freeze({ id: 'session-enabled', revisions,
    scenarios: Object.freeze(['server-initialize', 'server-session-lifecycle',
      'resources-subscribe', 'resources-unsubscribe', 'server-sse-multiple-streams']) }),
]);

// These are claim boundaries, not successful official checks or runtime skips.
export const supplementalRequirements = Object.freeze([
  'Static paging and page-local policy/localization',
  'Cooperative cancellation and physical cleanup',
  'URI/catalog notification delivery, revocation and dirty-bit reconnect',
  'Fresh credential renewal and named-host display/refresh',
  'Bounded churn, maintenance demand and retained-memory measurement',
]);
export const unselectedOfficialScenarios = Object.freeze([
  Object.freeze({ name: 'server-sse-polling', revision: '2025-11-25',
    disposition: 'OUTSIDE_SELECTED_COMPATIBILITY_CLAIM',
    reason: 'The case requires event IDs, Last-Event-ID replay and lost POST-result recovery, which Soklet excludes.' }),
  Object.freeze({ name: 'server-sse-polling', revision: '2025-06-18',
    disposition: 'NOT_APPLICABLE_AT_REVISION',
    reason: 'The pinned scenario was introduced in 2025-11-25.' }),
  Object.freeze({ name: 'server-sse-multiple-streams', revision: '2025-06-18',
    disposition: 'NOT_APPLICABLE_AT_REVISION',
    reason: 'The pinned scenario was introduced in 2025-11-25; November proves concurrent JSON acceptance only.' }),
]);

export function selectedRuns(profile = 'all') {
  if (profile !== 'all' && !profiles.some((value) => value.id === profile))
    throw new Error('Unreviewed legacy profile');
  return Object.freeze(profiles.filter((value) => profile === 'all' || value.id === profile)
    .flatMap((value) => value.revisions.flatMap((revision) => value.scenarios
      .filter((scenario) => scenario !== 'server-sse-multiple-streams' || revision === '2025-11-25')
      .map((scenario) => Object.freeze({ profile: value.id, revision, scenario })))));
}
const fixtureMain = 'com.soklet.conformance.legacy.McpLegacyConformanceFixture';
const fixtureSource = resolve(fileURLToPath(new URL('./McpLegacyConformanceFixture.java', import.meta.url)));
const runnerSource = fileURLToPath(import.meta.url);
const startupTimeoutMilliseconds = 10_000;
const scenarioTimeoutMilliseconds = 60_000;
const shutdownTimeoutMilliseconds = 10_000;

export function assertRawChecks(revision, scenario, checks, profile = 'stateless-baseline') {
  if (!selectedRuns(profile).some((value) => value.revision === revision && value.scenario === scenario))
    throw new Error('Unreviewed legacy revision, scenario or profile');
  if (!Array.isArray(checks))
    throw new Error(`${revision}/${scenario} checks must be an array`);
  const ordinary = scenario === 'server-initialize'
    ? [['server-initialize', 'SUCCESS'], ['server-session-id-visible-ascii',
      profile === 'session-enabled' ? 'SUCCESS' : 'INFO']]
    : scenario === 'server-session-lifecycle'
      ? [['server-session-initialized-accepted', 'SUCCESS'],
        ['server-session-delete-accepted', 'SUCCESS'], ['server-session-terminated-returns-404', 'SUCCESS']]
      : scenario === 'server-sse-multiple-streams'
        ? [['server-accepts-multiple-post-streams', 'SUCCESS'], ['server-sse-streams-functional', 'INFO']]
    : scenario === 'tools-list'
      ? revision === '2025-11-25'
        ? [['tools-list', 'SUCCESS'], ['tools-name-format', 'SUCCESS']]
        : [['tools-list', 'SUCCESS']]
      : [[scenario, 'SUCCESS']];
  const expectedMessageCount = scenario === 'server-initialize' ? 3
    : scenario === 'tools-call-with-progress' ? 8
      : scenario === 'resources-unsubscribe' ? 7
        : ['server-session-lifecycle', 'server-sse-multiple-streams'].includes(scenario) ? 0 : 5;
  const expected = expectedMessageCount === 0 ? ordinary : [...ordinary, ['wire-schema-valid', 'SUCCESS']];
  const actual = checks.map((check) => [check?.id, check?.status]);
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    throw new Error(`${revision}/${scenario} raw official check IDs or statuses changed: `
      + JSON.stringify(actual));
  if (scenario === 'server-initialize' && profile === 'stateless-baseline'
      && checks[1].details?.message
        !== 'Server did not provide an MCP-Session-Id header (session ID is optional)')
    throw new Error(`${revision}/${scenario} optional-session INFO meaning changed`);
  if (scenario === 'server-initialize' && profile === 'session-enabled'
      && (typeof checks[1].details?.sessionId !== 'string'
        || !/^[\x21-\x7e]+$/.test(checks[1].details.sessionId)))
    throw new Error(`${revision}/${scenario} session-enabled initialization has no valid session ID`);
  const catalogCountField = scenario === 'prompts-list' ? 'promptCount'
    : scenario === 'resources-list' ? 'resourceCount' : null;
  if (catalogCountField !== null
      && checks[0].details?.[catalogCountField]
        !== (scenario === 'prompts-list' ? 4 : 2))
    throw new Error(`${revision}/${scenario} fixture catalog count changed`);
  if (expectedMessageCount > 0 && (checks.at(-1).details?.messagesValidated !== expectedMessageCount
      || !Array.isArray(checks.at(-1).details?.violations)
      || checks.at(-1).details.violations.length !== 0))
    throw new Error(`${revision}/${scenario} wire-schema validation changed`);
  if (scenario === 'completion-complete'
      && JSON.stringify(checks[0].details?.result?.completion?.values) !== '["test-one","test-two"]')
    throw new Error(`${revision}/${scenario} fixture completion values changed`);
  if (scenario === 'tools-call-with-progress') {
    const detail = checks[0].details;
    const updates = detail?.progressNotifications;
    if (detail?.progressCount !== 3 || !Array.isArray(updates) || updates.length !== 3
        || updates.some((value, index) => value.progressToken !== 'progress-test-1'
          || value.progress !== index * 50 || value.total !== 100)
        || JSON.stringify(detail?.result?.content) !== '[{"type":"text","text":"Progress operation complete."}]')
      throw new Error(`${revision}/${scenario} fixture progress or complete result changed`);
  }
  if (scenario === 'server-session-lifecycle'
      && JSON.stringify(checks.map((value) => value.details?.statusCode)) !== '[202,204,404]')
    throw new Error(`${revision}/${scenario} fixture lifecycle HTTP outcomes changed`);
  if (scenario === 'server-sse-multiple-streams') {
    const accepted = checks[0].details;
    const functional = checks[1].details;
    if (accepted?.numStreamsAttempted !== 3 || accepted.numStreamsAccepted !== 3
        || accepted.numSseStreams !== 0 || JSON.stringify(accepted.statuses) !== '[200,200,200]'
        || !Array.isArray(accepted.contentTypes) || accepted.contentTypes.length !== 3
        || accepted.contentTypes.some((type) => typeof type !== 'string' || !type.includes('application/json'))
        || functional?.numSseStreams !== 0
        || functional.message !== 'Server returned JSON for all requests - SSE streaming is optional'
        || JSON.stringify(functional.results) !== '[{"index":0,"type":"json","skipped":true},{"index":1,"type":"json","skipped":true},{"index":2,"type":"json","skipped":true}]')
      throw new Error(`${revision}/${scenario} concurrent-JSON INFO meaning changed`);
  }
  return Object.freeze({
    profile, revision, scenario, checkCount: checks.length,
    checks: Object.freeze(actual.map(([id, status]) => Object.freeze({ id, status }))),
    wireMessagesValidated: expectedMessageCount,
    progressNotificationsObserved: scenario === 'tools-call-with-progress',
  });
}

export function parseReadyLine(line, expectedRevision, expectedProfile) {
  let ready;
  try {
    ready = JSON.parse(line);
  } catch (error) {
    throw new Error('Legacy fixture READY line is not JSON', { cause: error });
  }
  if (JSON.stringify(Object.keys(ready)) !== (expectedProfile === undefined
        ? '["format","event","host","port","path","revision"]'
        : '["format","event","host","port","path","revision","profile"]')
      || ready.format !== 1 || ready.event !== 'ready'
      || ready.host !== '127.0.0.1' || ready.path !== '/mcp'
      || ready.revision !== expectedRevision
      || (expectedProfile !== undefined && ready.profile !== expectedProfile)
      || !Number.isInteger(ready.port) || ready.port < 1 || ready.port > 65535)
    throw new Error('Legacy fixture emitted an invalid READY line');
  return ready;
}

export function parseOptions(args) {
  const candidateFlags = {
    '--project-root': 'projectRoot', '--candidate-commit': 'candidateCommit',
    '--candidate-pom': 'candidatePom', '--candidate-pom-sha256': 'candidatePomSha256',
    '--candidate-jar': 'candidateJar', '--candidate-jar-sha256': 'candidateJarSha256',
    '--candidate-sources-jar': 'candidateSourcesJar', '--candidate-sources-jar-sha256': 'candidateSourcesJarSha256',
    '--candidate-javadoc-jar': 'candidateJavadocJar', '--candidate-javadoc-jar-sha256': 'candidateJavadocJarSha256',
  };
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!['--suite-dir', '--work-dir', '--classpath', '--java', '--profile', '--mode', ...Object.keys(candidateFlags)].includes(key)
        || value === undefined || values.has(key))
      throw new Error('Usage: run.mjs --suite-dir <pinned-suite> '
        + '--work-dir <empty-absolute-directory> '
        + '--classpath <fixture-classes:candidate-jar> [--java <java>] '
        + '[--profile <all|stateless-baseline|stateless-expansion|session-enabled>]');
    values.set(key, value);
  }
  for (const required of ['--suite-dir', '--work-dir', '--classpath']) {
    if (!values.has(required)) throw new Error(`Missing ${required}`);
  }
  for (const pathFlag of ['--suite-dir', '--work-dir']) {
    if (!isAbsolute(values.get(pathFlag)))
      throw new Error(`${pathFlag} must be absolute`);
  }
  const mode = values.get('--mode') ?? 'development';
  if (!['development', 'release'].includes(mode)) throw new Error('Legacy mode must be development or release');
  if (mode === 'development' && Object.keys(candidateFlags).some(flag => values.has(flag)))
    throw new Error('Candidate inputs require legacy release mode');
  if (mode === 'release' && (Object.keys(candidateFlags).some(flag => !values.has(flag))
      || (values.get('--profile') ?? 'all') !== 'all'))
    throw new Error('Legacy release mode requires all candidate inputs and all profiles');
  return Object.freeze({
    suiteDirectory: resolve(values.get('--suite-dir')),
    workDirectory: resolve(values.get('--work-dir')),
    classpath: values.get('--classpath'),
    javaExecutable: values.get('--java') ?? 'java',
    profile: values.get('--profile') ?? 'all',
    mode,
    ...Object.fromEntries(Object.entries(candidateFlags).filter(([flag]) => values.has(flag))
      .map(([flag, property]) => [property, values.get(flag)])),
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

async function runOne(options, profile, revision, scenario, ordinal, entryPoint, supervisor) {
  const directory = resolve(options.workDirectory, profile, revision,
    `${String(ordinal).padStart(3, '0')}-${scenario}`);
  const resultDirectory = resolve(directory, 'official-results');
  mkdirSync(resultDirectory, { recursive: true });
  const child = supervisor.spawn(options.javaExecutable,
    ['-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', options.classpath,
      fixtureMain, '--version', revision, '--profile', profile], {
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
      await fixture.lines.next(startupTimeoutMilliseconds), revision, profile);
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
      JSON.parse(checksBytes.toString('utf8')), profile);
    result = Object.freeze({
      ...observed,
      passed: true,
      checksJson: relative(options.workDirectory, checksPath).split(delimiter === ';' ? '\\' : '/').join('/'),
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
  const releasing = options.mode === 'release';
  if (!['development', 'release'].includes(options.mode ?? 'development')
      || releasing && (options.profile ?? 'all') !== 'all')
    throw new Error('Legacy release mode requires all profiles');
  const runs = selectedRuns(options.profile ?? 'all');
  prepareWorkDirectory(options.workDirectory);
  const fixture = requireClasspath(options.classpath);
  const { pins, selection } = verifyManifestSet();
  const evidencePath = resolve(options.workDirectory, 'evidence.json');
  const evidence = {
    formatVersion: 2,
    mode: options.mode ?? 'development',
    evidenceClass: releasing ? 'IMMUTABLE_LEGACY_RELEASE_CANDIDATE' : 'LEGACY_PREPARATORY_DEVELOPMENT_ONLY',
    releaseCandidateEvidence: false,
    releaseCandidateProvenance: null,
    status: 'PREPARING',
    suiteCommit: pins.officialConformanceSuite.commit,
    suiteCliSha256: pins.officialConformanceSuite.builtEntryPoint.sha256,
    jarSha256: sha256(readFileSync(fixture.jar)),
    fixtureSourceSha256: sha256(readFileSync(fixtureSource)),
    fixtureClassSha256: sha256(readFileSync(fixture.mainClass)),
    runnerSourceSha256: sha256(readFileSync(runnerSource)),
    runtimeFixtureSourceSha256: sha256(readFileSync(new URL('./McpLegacyRuntimeFixture.java', import.meta.url))),
    runtimeRunnerSourceSha256: sha256(readFileSync(new URL('./runtime-supplement.mjs', import.meta.url))),
    fixtureClassesIdentity: sourceTreeIdentity(fixture.classes, []),
    runtimeSupplement: null,
    scenarioSelectionSha256: sha256(Buffer.from(JSON.stringify(runs))),
    revisions: [...revisions],
    profiles: profiles.filter((profile) => runs.some((run) => run.profile === profile.id))
      .map((profile) => profile.id),
    unselectedOfficialScenarios,
    supplementalRequirements,
    scenarios: [],
    failure: null,
  };
  persist(evidencePath, evidence);
  const supervisor = new ChildSupervisor();
  const removeSignalHandlers = installSignalHandlers(supervisor);
  let primaryFailure;
  try {
    let releaseOptions;
    if (releasing) {
      const releaseCandidate = verifyReleaseCandidateOptions(options, pins);
      if (fixture.jar !== releaseCandidate.candidateJar
          || fixture.classes !== resolve(options.projectRoot, 'target/conformance/legacy-fixture/classes'))
        throw new Error('Legacy release classpath does not match the candidate JAR and fixture classes');
      await verifyProjectCheckout(options.projectRoot, options.candidateCommit, supervisor);
      verifyCandidatePomMatchesCheckout(releaseCandidate, options.projectRoot);
      evidence.releaseCandidateProvenance = releaseCandidate.evidence;
      releaseOptions = { ...options, releasePins: pins, releaseCandidate };
    }
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
    for (const [index, { profile, revision, scenario }] of runs.entries()) {
        supervisor.throwIfCancellationRequested();
        try {
          evidence.scenarios.push(await runOne(
            options, profile, revision, scenario, index + 1, entryPoint, supervisor));
          persist(evidencePath, evidence);
        } catch (error) {
          evidence.scenarios.push({ profile, revision, scenario, passed: false,
            error: `${error}` });
          throw error;
        }
    }
    evidence.runtimeSupplement = await runRuntimeSupplement(options, supervisor);
    if (sha256(readFileSync(fixture.jar)) !== evidence.jarSha256
        || sha256(readFileSync(fixture.mainClass)) !== evidence.fixtureClassSha256
        || sha256(readFileSync(fixtureSource)) !== evidence.fixtureSourceSha256
        || sha256(readFileSync(runnerSource)) !== evidence.runnerSourceSha256
        || sha256(readFileSync(new URL('./McpLegacyRuntimeFixture.java', import.meta.url))) !== evidence.runtimeFixtureSourceSha256
        || sha256(readFileSync(new URL('./runtime-supplement.mjs', import.meta.url))) !== evidence.runtimeRunnerSourceSha256
        || JSON.stringify(sourceTreeIdentity(fixture.classes, [])) !== JSON.stringify(evidence.fixtureClassesIdentity))
      throw new Error('Legacy preparatory JAR, fixture or runner changed during the run');
    if (releasing) {
      assertReleaseCandidateUnchanged(releaseOptions);
      await verifyProjectCheckout(options.projectRoot, options.candidateCommit, supervisor);
      evidence.releaseCandidateEvidence = true;
    }
    evidence.status = 'PASSED';
    persist(evidencePath, evidence);
    return evidence;
  } catch (error) {
    primaryFailure = error;
    evidence.releaseCandidateEvidence = false;
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
      evidence.releaseCandidateEvidence = false;
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
