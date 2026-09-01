#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  ReleaseWorkflowArtifactVerificationError,
  runReleaseWorkflowArtifactCli,
  verifyBenchmarkDraftWorkflowArtifact,
  verifyReleaseWorkflowArtifacts,
} from './verify-release-workflow-artifacts.mjs';

const CANDIDATE = 'a'.repeat(40);
const OTHER_COMMIT = 'b'.repeat(40);
const REPOSITORY = 'soklet-project/soklet';
const TOKEN = 'github-test-token-never-print';
const MAXIMUM_RESPONSE_BYTES = 1024 * 1024;
const GATES = Object.freeze({
  fuzz: Object.freeze({
    artifactPrefix: 'fuzz-nightly-history',
    event: 'schedule',
    runId: '101',
    workflowPath: '.github/workflows/ci.yml',
  }),
  soak: Object.freeze({
    artifactPrefix: 'soak-nightly-history',
    event: 'schedule',
    runId: '102',
    workflowPath: '.github/workflows/ci.yml',
  }),
  operational: Object.freeze({
    artifactPrefix: 'operational-history',
    event: 'workflow_dispatch',
    runId: '103',
    workflowPath: '.github/workflows/ci.yml',
  }),
  scans: Object.freeze({
    artifactPrefix: 'release-scans',
    event: 'workflow_dispatch',
    runId: '104',
    workflowPath: '.github/workflows/release-validation.yml',
  }),
  benchmark: Object.freeze({
    artifactPrefix: 'mcp-benchmarks',
    event: 'workflow_dispatch',
    runId: '105',
    workflowPath: '.github/workflows/release-validation.yml',
  }),
});

let assertions = 0;

function response(body, {
  contentLength,
  contentType = 'application/json; charset=utf-8',
  status = 200,
} = {}) {
  const headers = { 'content-type': contentType };
  if (contentLength !== undefined)
    headers['content-length'] = String(contentLength);
  return new Response(body, { headers, status });
}

function jsonResponse(value, options) {
  return response(JSON.stringify(value), options);
}

function fixture() {
  const runs = {};
  const listings = {};
  const requests = {};
  const calls = [];
  for (const [index, [gate, config]] of Object.entries(GATES).entries()) {
    const attempt = index + 1;
    const artifactName = `${config.artifactPrefix}-${CANDIDATE}-${config.runId}-${attempt}`;
    runs[gate] = {
      conclusion: 'success',
      event: config.event,
      head_sha: CANDIDATE,
      id: Number(config.runId),
      path: config.workflowPath,
      repository: { full_name: REPOSITORY },
      run_attempt: attempt,
      status: 'completed',
    };
    const workflowRun = { head_sha: CANDIDATE, id: Number(config.runId) };
    listings[gate] = {
      artifacts: [
        {
          expired: false,
          id: 1000 + index,
          name: artifactName,
          workflow_run: workflowRun,
        },
        {
          expired: false,
          id: 2000 + index,
          name: `${config.artifactPrefix}-raw-${CANDIDATE}-${config.runId}-${attempt}`,
          workflow_run: workflowRun,
        },
      ],
      total_count: 2,
    };
    requests[gate] = { artifactName, runId: config.runId };
  }

  const gateByRunId = Object.fromEntries(
    Object.entries(GATES).map(([gate, config]) => [config.runId, gate]),
  );
  async function fetchImpl(url, options) {
    calls.push({ options, url });
    const match = new URL(url).pathname.match(/\/actions\/runs\/([1-9][0-9]*)(\/artifacts)?$/u);
    if (match === null)
      return jsonResponse({ message: 'not found' }, { status: 404 });
    const gate = gateByRunId[match[1]];
    if (gate === undefined)
      return jsonResponse({ message: 'not found' }, { status: 404 });
    return jsonResponse(match[2] === undefined ? runs[gate] : listings[gate]);
  }
  return {
    calls,
    listings,
    options: {
      candidate: CANDIDATE,
      fetchImpl,
      repository: REPOSITORY,
      requests,
      token: TOKEN,
    },
    requests,
    runs,
  };
}

function benchmarkDraftFixture() {
  const runId = '201';
  const runAttempt = 6;
  const artifactName = `mcp-benchmark-draft-${CANDIDATE}-${runId}-${runAttempt}`;
  const run = {
    conclusion: 'success',
    event: 'workflow_dispatch',
    head_sha: CANDIDATE,
    id: Number(runId),
    path: '.github/workflows/release-validation.yml@refs/heads/release-candidate',
    repository: { full_name: REPOSITORY },
    run_attempt: runAttempt,
    status: 'completed',
  };
  const listing = {
    artifacts: [
      {
        expired: false,
        id: 3001,
        name: artifactName,
        workflow_run: { head_sha: CANDIDATE, id: Number(runId) },
      },
      {
        expired: false,
        id: 3002,
        name: `mcp-benchmark-draft-raw-${CANDIDATE}-${runId}-${runAttempt}`,
        workflow_run: { head_sha: CANDIDATE, id: Number(runId) },
      },
    ],
    total_count: 2,
  };
  const calls = [];
  async function fetchImpl(url, options) {
    calls.push({ options, url });
    const parsed = new URL(url);
    if (parsed.pathname === `/repos/soklet-project/soklet/actions/runs/${runId}`)
      return jsonResponse(run);
    if (parsed.pathname === `/repos/soklet-project/soklet/actions/runs/${runId}/artifacts`)
      return jsonResponse(listing);
    return jsonResponse({ message: 'not found' }, { status: 404 });
  }
  return {
    artifactName,
    calls,
    listing,
    options: {
      candidate: CANDIDATE,
      fetchImpl,
      repository: REPOSITORY,
      request: { artifactName, runId },
      token: TOKEN,
    },
    run,
    runAttempt,
    runId,
  };
}

async function rejectsAfterMutation(mutator, pattern) {
  const value = fixture();
  mutator(value);
  await assert.rejects(
    () => verifyReleaseWorkflowArtifacts(value.options),
    pattern,
  );
  assertions++;
}

const valid = fixture();
const verified = await verifyReleaseWorkflowArtifacts(valid.options);
assert.deepEqual(Object.keys(verified.artifacts), Object.keys(GATES));
assert.equal(verified.candidate, CANDIDATE);
assert.equal(verified.repository, REPOSITORY);
assert.equal(verified.artifacts.benchmark.runAttempt, 5);
assert.equal(valid.calls.length, 10);
assert.ok(valid.calls.every(({ options }) =>
  options.method === 'GET'
    && options.redirect === 'error'
    && options.headers.Authorization === `Bearer ${TOKEN}`
    && options.headers.Accept === 'application/vnd.github+json'
    && options.headers['X-GitHub-Api-Version'] === '2026-03-10'));
assert.ok(valid.calls.every(({ url }) => !url.includes(TOKEN)));
assert.ok(valid.calls.some(({ url }) =>
  url.endsWith('/actions/runs/101/artifacts?per_page=100&page=1')));
assert.ok(!JSON.stringify(verified).includes(TOKEN));
assertions += 9;

const manuallyDispatchedNightly = fixture();
manuallyDispatchedNightly.runs.fuzz.event = 'workflow_dispatch';
manuallyDispatchedNightly.runs.soak.event = 'workflow_dispatch';
manuallyDispatchedNightly.runs.fuzz.path = '.github/workflows/ci.yml@refs/heads/release-candidate';
manuallyDispatchedNightly.runs.soak.path = '.github/workflows/ci.yml@main';
const manualResult = await verifyReleaseWorkflowArtifacts(
  manuallyDispatchedNightly.options,
);
assert.equal(manualResult.artifacts.fuzz.runId, GATES.fuzz.runId);
assert.equal(manualResult.artifacts.soak.runId, GATES.soak.runId);
assert.equal(manuallyDispatchedNightly.calls.length, 10);
assertions += 3;

const benchmarkDraft = benchmarkDraftFixture();
const verifiedDraft = await verifyBenchmarkDraftWorkflowArtifact(
  benchmarkDraft.options,
);
assert.equal(verifiedDraft.candidate, CANDIDATE);
assert.equal(verifiedDraft.repository, REPOSITORY);
assert.equal(verifiedDraft.artifact.artifactId, 3001);
assert.equal(verifiedDraft.artifact.artifactName, benchmarkDraft.artifactName);
assert.equal(verifiedDraft.artifact.runAttempt, benchmarkDraft.runAttempt);
assert.equal(verifiedDraft.artifact.runId, benchmarkDraft.runId);
assert.equal(benchmarkDraft.calls.length, 2);
assert.ok(benchmarkDraft.calls.every(({ options }) =>
  options.headers.Authorization === `Bearer ${TOKEN}`
    && options.headers['X-GitHub-Api-Version'] === '2026-03-10'));
assertions += 8;

const draftWrongEvent = benchmarkDraftFixture();
draftWrongEvent.run.event = 'schedule';
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(draftWrongEvent.options),
  /workflow run event is not an allowed producer event/u,
);
assertions++;

const draftWrongWorkflow = benchmarkDraftFixture();
draftWrongWorkflow.run.path = '.github/workflows/ci.yml@main';
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(draftWrongWorkflow.options),
  /workflow run path is not .github\/workflows\/release-validation.yml/u,
);
assertions++;

const draftWrongArtifactName = benchmarkDraftFixture();
draftWrongArtifactName.options.request.artifactName += '-wrong';
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(draftWrongArtifactName.options),
  /not the exact candidate\/run\/attempt-bound name/u,
);
assertions++;

const expiredDraft = benchmarkDraftFixture();
expiredDraft.listing.artifacts[0].expired = true;
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(expiredDraft.options),
  /artifact is expired/u,
);
assertions++;

const wrongDraftRun = benchmarkDraftFixture();
wrongDraftRun.listing.artifacts[0].workflow_run.id = 999;
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(wrongDraftRun.options),
  /artifact workflow_run ID does not match/u,
);
assertions++;

const wrongDraftHead = benchmarkDraftFixture();
wrongDraftHead.listing.artifacts[0].workflow_run.head_sha = OTHER_COMMIT;
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(wrongDraftHead.options),
  /artifact workflow_run head_sha does not match/u,
);
assertions++;

const duplicateDraft = benchmarkDraftFixture();
const duplicateDraftArtifact = structuredClone(duplicateDraft.listing.artifacts[0]);
duplicateDraftArtifact.id = 9999;
duplicateDraft.listing.artifacts.push(duplicateDraftArtifact);
duplicateDraft.listing.total_count += 1;
await assert.rejects(
  () => verifyBenchmarkDraftWorkflowArtifact(duplicateDraft.options),
  /must contain exactly one artifact named/u,
);
assertions++;

await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.id = 999; },
  /workflow run ID does not match/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.repository.full_name = 'other/soklet'; },
  /repository does not match/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.status = 'in_progress'; },
  /not completed successfully/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.conclusion = 'failure'; },
  /not completed successfully/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.head_sha = OTHER_COMMIT; },
  /head_sha does not match/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.path = '.github/workflows/release-validation.yml'; },
  /workflow run path/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.event = 'push'; },
  /workflow run event/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.path = '.github/workflows/ci.yml@'; },
  /workflow run path/u,
);
await rejectsAfterMutation(
  ({ runs }) => { runs.fuzz.run_attempt = 0; },
  /run_attempt must be a positive/u,
);
await rejectsAfterMutation(
  ({ requests }) => { requests.fuzz.artifactName += '-wrong'; },
  /not the exact candidate\/run\/attempt-bound name/u,
);
await rejectsAfterMutation(
  ({ listings }) => { listings.fuzz.artifacts[0].expired = true; },
  /artifact is expired/u,
);
await rejectsAfterMutation(
  ({ listings }) => { listings.fuzz.artifacts[0].workflow_run.id = 999; },
  /artifact workflow_run ID does not match/u,
);
await rejectsAfterMutation(
  ({ listings }) => { listings.fuzz.artifacts[0].workflow_run.head_sha = OTHER_COMMIT; },
  /artifact workflow_run head_sha does not match/u,
);
await rejectsAfterMutation(({ listings }) => {
  const duplicate = structuredClone(listings.fuzz.artifacts[0]);
  duplicate.id = 9999;
  listings.fuzz.artifacts.push(duplicate);
  listings.fuzz.total_count += 1;
}, /must contain exactly one artifact named/u);
await rejectsAfterMutation(({ listings }) => {
  listings.fuzz.artifacts[0].name = 'unrelated';
}, /must contain exactly one artifact named/u);
await rejectsAfterMutation(
  ({ listings }) => { listings.fuzz.total_count = 101; },
  /total_count is invalid or exceeds/u,
);
await rejectsAfterMutation(
  ({ listings }) => { listings.fuzz.total_count = 1; },
  /listing is incomplete or malformed/u,
);

await rejectsAfterMutation(({ options }) => {
  options.repository = 'missing-slash';
}, /repository must be an exact owner\/name/u);
await rejectsAfterMutation(({ options }) => {
  options.candidate = CANDIDATE.toUpperCase();
}, /full lowercase 40-character commit ID/u);
await rejectsAfterMutation(({ options }) => {
  options.token = 'token with spaces';
}, /token is missing or malformed/u);
await rejectsAfterMutation(({ requests }) => {
  requests.extra = { artifactName: 'extra', runId: '999' };
}, /requests must contain exactly/u);
await rejectsAfterMutation(({ requests }) => {
  requests.fuzz.extra = true;
}, /fuzz request must contain exactly/u);
await rejectsAfterMutation(({ requests }) => {
  requests.fuzz.runId = '01';
}, /positive decimal workflow run ID/u);
await rejectsAfterMutation(({ requests }) => {
  requests.fuzz.runId = String(Number.MAX_SAFE_INTEGER + 1);
}, /supported exact integer range/u);

const httpFailure = fixture();
httpFailure.options.fetchImpl = async () =>
  jsonResponse({ message: 'forbidden' }, { status: 403 });
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(httpFailure.options),
  /HTTP 403/u,
);
assertions++;

const wrongMediaType = fixture();
wrongMediaType.options.fetchImpl = async () =>
  response('{}', { contentType: 'text/plain' });
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(wrongMediaType.options),
  /is not JSON/u,
);
assertions++;

const oversizedHeader = fixture();
oversizedHeader.options.fetchImpl = async () => response('{}', {
  contentLength: MAXIMUM_RESPONSE_BYTES + 1,
});
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(oversizedHeader.options),
  /exceeds the byte limit/u,
);
assertions++;

const oversizedBody = fixture();
oversizedBody.options.fetchImpl = async () =>
  response('x'.repeat(MAXIMUM_RESPONSE_BYTES + 1));
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(oversizedBody.options),
  /exceeds the byte limit/u,
);
assertions++;

const malformedJson = fixture();
malformedJson.options.fetchImpl = async () => response('{');
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(malformedJson.options),
  /not valid JSON/u,
);
assertions++;

const invalidUtf8 = fixture();
invalidUtf8.options.fetchImpl = async () =>
  response(new Uint8Array([0xff]));
await assert.rejects(
  () => verifyReleaseWorkflowArtifacts(invalidUtf8.options),
  /not valid UTF-8/u,
);
assertions++;

const tokenLeak = fixture();
tokenLeak.options.fetchImpl = async () => {
  throw new Error(`transport exposed ${TOKEN}`);
};
try {
  await verifyReleaseWorkflowArtifacts(tokenLeak.options);
  assert.fail('transport failure should reject');
} catch (error) {
  assert.ok(error instanceof ReleaseWorkflowArtifactVerificationError);
  assert.equal(error.message, 'GitHub API request failed for fuzz workflow run.');
  assert.ok(!error.message.includes(TOKEN));
  assertions += 3;
}

const scriptPath = fileURLToPath(new URL(
  './verify-release-workflow-artifacts.mjs',
  import.meta.url,
));
const successfulReleaseCliFixture = fixture();
const successfulReleaseCliOutput = [];
const successfulReleaseCli = await runReleaseWorkflowArtifactCli({
  args: [
    '--repository', REPOSITORY,
    '--candidate', CANDIDATE,
    ...Object.keys(GATES).flatMap((gate) => [
      `--${gate}-run-id`, successfulReleaseCliFixture.requests[gate].runId,
      `--${gate}-artifact-name`,
      successfulReleaseCliFixture.requests[gate].artifactName,
    ]),
  ],
  environment: { GITHUB_TOKEN: TOKEN },
  fetchImpl: successfulReleaseCliFixture.options.fetchImpl,
  writeOutput: (message) => successfulReleaseCliOutput.push(message),
});
assert.equal(successfulReleaseCli.mode, 'release');
assert.equal(successfulReleaseCli.result.artifacts.fuzz.artifactId, 1000);
assert.deepEqual(successfulReleaseCliOutput, [
  `release workflow artifact provenance PASS candidate=${CANDIDATE} artifacts=5`,
]);
assert.equal(successfulReleaseCliFixture.calls.length, 10);
assertions += 4;

const successfulDraftCliFixture = benchmarkDraftFixture();
const successfulDraftCliOutput = [];
const successfulDraftCli = await runReleaseWorkflowArtifactCli({
  args: [
    '--repository', REPOSITORY,
    '--candidate', CANDIDATE,
    '--benchmark-draft-run-id', successfulDraftCliFixture.runId,
    '--benchmark-draft-artifact-name', successfulDraftCliFixture.artifactName,
  ],
  environment: { GITHUB_TOKEN: TOKEN },
  fetchImpl: successfulDraftCliFixture.options.fetchImpl,
  writeOutput: (message) => successfulDraftCliOutput.push(message),
});
assert.equal(successfulDraftCli.mode, 'benchmark-draft');
assert.equal(successfulDraftCli.result.artifact.artifactId, 3001);
assert.deepEqual(successfulDraftCliOutput, [
  `benchmark draft workflow artifact provenance PASS candidate=${CANDIDATE} artifacts=1`,
]);
assert.equal(successfulDraftCliFixture.calls.length, 2);
assertions += 4;

const releaseCliArguments = [
  '--repository', REPOSITORY,
  '--candidate', CANDIDATE,
  ...Object.entries(GATES).flatMap(([gate, config]) => [
    `--${gate}-run-id`, gate === 'fuzz' ? '0' : config.runId,
    `--${gate}-artifact-name`, `${config.artifactPrefix}-${CANDIDATE}-${config.runId}-1`,
  ]),
];
const recognizedReleaseCli = spawnSync(process.execPath, [
  scriptPath,
  ...releaseCliArguments,
], {
  encoding: 'utf8',
  env: { ...process.env, GITHUB_TOKEN: TOKEN },
});
assert.equal(recognizedReleaseCli.status, 1);
assert.match(recognizedReleaseCli.stderr, /fuzz runId must be a positive decimal/u);
assert.ok(!recognizedReleaseCli.stderr.includes(TOKEN));
assertions += 3;

const draftCliArguments = [
  '--repository', REPOSITORY,
  '--candidate', CANDIDATE,
  '--benchmark-draft-run-id', '0',
  '--benchmark-draft-artifact-name', 'mcp-benchmark-draft-placeholder',
];
const recognizedDraftCli = spawnSync(process.execPath, [
  scriptPath,
  ...draftCliArguments,
], {
  encoding: 'utf8',
  env: { ...process.env, GITHUB_TOKEN: TOKEN },
});
assert.equal(recognizedDraftCli.status, 1);
assert.match(
  recognizedDraftCli.stderr,
  /benchmark-draft runId must be a positive decimal/u,
);
assert.ok(!recognizedDraftCli.stderr.includes(TOKEN));
assertions += 3;

const mixedCli = spawnSync(process.execPath, [
  scriptPath,
  ...draftCliArguments,
  '--fuzz-run-id', '1',
], {
  encoding: 'utf8',
  env: { ...process.env, GITHUB_TOKEN: TOKEN },
});
assert.equal(mixedCli.status, 64);
assert.match(mixedCli.stderr, /^Usage:/u);
assertions += 2;

const invalidCli = spawnSync(process.execPath, [scriptPath, '--unknown', 'value'], {
  encoding: 'utf8',
  env: { ...process.env, GITHUB_TOKEN: TOKEN },
});
assert.equal(invalidCli.status, 64);
assert.match(invalidCli.stderr, /^Usage:/u);
assert.ok(!invalidCli.stderr.includes(TOKEN));
assertions += 3;

const missingTokenEnvironment = { ...process.env };
delete missingTokenEnvironment.GITHUB_TOKEN;
const missingTokenCli = spawnSync(process.execPath, [scriptPath], {
  encoding: 'utf8',
  env: missingTokenEnvironment,
});
assert.equal(missingTokenCli.status, 64);
assert.equal(missingTokenCli.stderr, 'GITHUB_TOKEN must be set.\n');
assertions += 2;

console.log(`release workflow artifact provenance self-test PASS assertions=${assertions}`);
