import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { sha256, sourceTreeIdentity } from '../verify.mjs';
import { assertRawChecks, profiles, revisions, selectedRuns, supplementalRequirements, unselectedOfficialScenarios } from './run.mjs';
import { runtimeSupplementChecks } from './runtime-supplement.mjs';

const INFO = "Server did not provide an MCP-Session-Id header (session ID is optional)";

export function sample(revision, scenario, profile = 'stateless-baseline') {
  const checks = scenario === 'server-initialize'
    ? [check('server-initialize'),
      { id: 'server-session-id-visible-ascii', status: profile === 'session-enabled' ? 'SUCCESS' : 'INFO',
        details: profile === 'session-enabled' ? { sessionId: 'test-session-id' } : { message: INFO } }]
    : scenario === 'server-session-lifecycle'
      ? ['server-session-initialized-accepted', 'server-session-delete-accepted',
        'server-session-terminated-returns-404'].map((id, index) => ({ ...check(id), details: { statusCode: [202, 204, 404][index] } }))
      : scenario === 'server-sse-multiple-streams'
        ? [{ ...check('server-accepts-multiple-post-streams'), details: {
          numStreamsAttempted: 3, numStreamsAccepted: 3, numSseStreams: 0,
          statuses: [200, 200, 200], contentTypes: Array(3).fill('application/json') } },
        { id: 'server-sse-streams-functional', status: 'INFO', details: {
          numSseStreams: 0, message: 'Server returned JSON for all requests - SSE streaming is optional',
          results: [0, 1, 2].map((index) => ({ index, type: 'json', skipped: true })) } }]
    : scenario === 'tools-list'
      ? revision === '2025-11-25'
        ? [check('tools-list'), check('tools-name-format')]
        : [check('tools-list')]
      : [check(scenario)];
  if (scenario === 'prompts-list') checks[0].details = { promptCount: 4 };
  if (scenario === 'resources-list') checks[0].details = { resourceCount: 2 };
  if (scenario === 'completion-complete') checks[0].details = {
    result: { completion: { values: ['test-one', 'test-two'] } } };
  if (scenario === 'tools-call-with-progress') checks[0].details = {
    progressCount: 3, progressNotifications: [0, 50, 100].map((progress) => ({
      progressToken: 'progress-test-1', progress, total: 100 })),
    result: { content: [{ type: 'text', text: 'Progress operation complete.' }] } };
  if (!['server-session-lifecycle', 'server-sse-multiple-streams'].includes(scenario))
    checks.push({ id: 'wire-schema-valid', status: 'SUCCESS',
    details: { messagesValidated: scenario === 'server-initialize' ? 3
      : scenario === 'tools-call-with-progress' ? 8 : scenario === 'resources-unsubscribe' ? 7 : 5,
      violations: [] } });
  return checks;
}

function check(id) {
  return { id, status: 'SUCCESS' };
}

// Synthetic receipts for verifier rejection tests only; never candidate evidence.
export function createLegacyReceiptFixture(projectRoot, evidencePath, provenance, pins) {
  const root = dirname(evidencePath); mkdirSync(root, { recursive: true });
  const classes = resolve(projectRoot, 'target/conformance/legacy-fixture/classes');
  const packageRoot = resolve(classes, 'com/soklet/conformance/legacy');
  mkdirSync(packageRoot, { recursive: true });
  writeFileSync(resolve(packageRoot, 'McpLegacyConformanceFixture.class'), 'synthetic fixture class');
  writeFileSync(resolve(packageRoot, 'McpLegacyRuntimeFixture.class'), 'synthetic runtime class');
  const hashes = {};
  for (const [field, file] of [
    ['runnerSourceSha256', 'run.mjs'], ['fixtureSourceSha256', 'McpLegacyConformanceFixture.java'],
    ['runtimeFixtureSourceSha256', 'McpLegacyRuntimeFixture.java'], ['runtimeRunnerSourceSha256', 'runtime-supplement.mjs'],
  ]) {
    const target = resolve(projectRoot, 'conformance/official/legacy', file);
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(new URL('./' + file, import.meta.url), target);
    hashes[field] = sha256(readFileSync(target));
  }
  const runs = selectedRuns();
  const scenarios = runs.map((run, index) => {
    const prefix = `${run.profile}/${run.revision}/${String(index + 1).padStart(3, '0')}-${run.scenario}`;
    const directory = resolve(root, prefix, 'official-results'); mkdirSync(directory, { recursive: true });
    const bytes = Buffer.from(JSON.stringify(sample(run.revision, run.scenario, run.profile)));
    const checksJson = prefix + '/official-results/checks.json'; writeFileSync(resolve(root, checksJson), bytes);
    writeFileSync(resolve(root, prefix, 'fixture.cleanup.txt'), 'forced=false\nexitCode=0\nsignal=null\n');
    for (const file of ['fixture.stderr.log', 'official.stderr.log']) writeFileSync(resolve(root, prefix, file), '');
    return { ...assertRawChecks(run.revision, run.scenario, JSON.parse(bytes), run.profile), passed: true,
      checksJson, checksJsonSha256: sha256(bytes), commandArguments: ['server', '--url', 'http://127.0.0.1:12345/mcp',
        '--scenario', run.scenario, '--spec-version', run.revision, '-o', directory, '--verbose'] };
  });
  mkdirSync(resolve(root, 'runtime')); writeFileSync(resolve(root, 'runtime/fixture.cleanup.txt'), 'clean=true\n');
  writeFileSync(resolve(root, 'runtime/fixture.stderr.log'), '');
  const evidence = { formatVersion: 2, mode: 'release', evidenceClass: 'IMMUTABLE_LEGACY_RELEASE_CANDIDATE',
    releaseCandidateEvidence: true, releaseCandidateProvenance: provenance, status: 'PASSED',
    suiteCommit: pins.officialConformanceSuite.commit, suiteCliSha256: pins.officialConformanceSuite.builtEntryPoint.sha256,
    jarSha256: provenance.artifacts.mainJar.sha256, ...hashes,
    fixtureClassSha256: sha256(readFileSync(resolve(packageRoot, 'McpLegacyConformanceFixture.class'))),
    fixtureClassesIdentity: sourceTreeIdentity(classes, []),
    runtimeSupplement: { passed: true, results: revisions.map(revision => ({ revision, passed: true, checks: [...runtimeSupplementChecks] })) },
    scenarioSelectionSha256: sha256(Buffer.from(JSON.stringify(runs))), revisions: [...revisions], profiles: profiles.map(profile => profile.id),
    unselectedOfficialScenarios, supplementalRequirements, scenarios, failure: null };
  writeFileSync(evidencePath, JSON.stringify(evidence)); return evidence;
}
