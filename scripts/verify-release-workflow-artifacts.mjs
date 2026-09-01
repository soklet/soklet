#!/usr/bin/env node

import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const MAXIMUM_RESPONSE_BYTES = 1024 * 1024;
const MAXIMUM_ARTIFACTS_PER_RUN = 100;
const GITHUB_API_ROOT = 'https://api.github.com';
const GITHUB_API_VERSION = '2026-03-10';

const GATES = Object.freeze({
  fuzz: Object.freeze({
    artifactPrefix: 'fuzz-nightly-history',
    events: Object.freeze(['schedule', 'workflow_dispatch']),
    workflowPath: '.github/workflows/ci.yml',
  }),
  soak: Object.freeze({
    artifactPrefix: 'soak-nightly-history',
    events: Object.freeze(['schedule', 'workflow_dispatch']),
    workflowPath: '.github/workflows/ci.yml',
  }),
  operational: Object.freeze({
    artifactPrefix: 'operational-history',
    events: Object.freeze(['workflow_dispatch']),
    workflowPath: '.github/workflows/ci.yml',
  }),
  scans: Object.freeze({
    artifactPrefix: 'release-scans',
    events: Object.freeze(['workflow_dispatch']),
    workflowPath: '.github/workflows/release-validation.yml',
  }),
  benchmark: Object.freeze({
    artifactPrefix: 'mcp-benchmarks',
    events: Object.freeze(['workflow_dispatch']),
    workflowPath: '.github/workflows/release-validation.yml',
  }),
});
const GATE_NAMES = Object.freeze(Object.keys(GATES));
const BENCHMARK_DRAFT_CONFIG = Object.freeze({
  artifactPrefix: 'mcp-benchmark-draft',
  events: Object.freeze(['workflow_dispatch']),
  workflowPath: '.github/workflows/release-validation.yml',
});

export class ReleaseWorkflowArtifactVerificationError extends Error {}

class ReleaseWorkflowArtifactCliError extends Error {}

function fail(message) {
  throw new ReleaseWorkflowArtifactVerificationError(message);
}

function requirePlainObject(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  return value;
}

function requireExactKeys(value, expected, label) {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length
      || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label} must contain exactly: ${wanted.join(', ')}.`);
  }
}

function requireRepository(value) {
  if (typeof value !== 'string' || value.length > 201
      || !/^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/u.test(value)) {
    fail('repository must be an exact owner/name slug.');
  }
  return value;
}

function requireCandidate(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{40}$/u.test(value))
    fail('candidate must be a full lowercase 40-character commit ID.');
  return value;
}

function requireRunId(value, label) {
  if (typeof value !== 'string' || !/^[1-9][0-9]*$/u.test(value))
    fail(`${label} must be a positive decimal workflow run ID.`);
  const numeric = Number(value);
  if (!Number.isSafeInteger(numeric))
    fail(`${label} exceeds the supported exact integer range.`);
  return value;
}

function requirePositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value <= 0)
    fail(`${label} must be a positive exact integer.`);
  return value;
}

function requireToken(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 4096
      || /[\u0000-\u0020\u007f]/u.test(value)) {
    fail('GitHub API token is missing or malformed.');
  }
  return value;
}

function headersFor(token) {
  return Object.freeze({
    Accept: 'application/vnd.github+json',
    Authorization: `Bearer ${token}`,
    'User-Agent': 'soklet-release-workflow-artifact-verifier',
    'X-GitHub-Api-Version': GITHUB_API_VERSION,
  });
}

function githubUrl(repository, suffix) {
  const [owner, name] = repository.split('/');
  return `${GITHUB_API_ROOT}/repos/${encodeURIComponent(owner)}/${encodeURIComponent(name)}${suffix}`;
}

async function fetchResponse(fetchImpl, url, token, label) {
  let response;
  try {
    response = await fetchImpl(url, {
      headers: headersFor(token),
      method: 'GET',
      redirect: 'error',
    });
  } catch {
    fail(`GitHub API request failed for ${label}.`);
  }
  if (response === null || typeof response !== 'object'
      || !Number.isInteger(response.status)
      || typeof response.headers?.get !== 'function') {
    fail(`GitHub API returned an invalid response for ${label}.`);
  }
  if (response.status !== 200)
    fail(`GitHub API returned HTTP ${response.status} for ${label}.`);
  if (response.redirected === true)
    fail(`GitHub API redirected the request for ${label}.`);
  return response;
}

async function boundedJson(response, label) {
  const contentType = response.headers.get('content-type');
  if (typeof contentType !== 'string'
      || !/^application\/(?:json|[A-Za-z0-9!#$&^_.+-]+\+json)(?:\s*;|\s*$)/iu.test(contentType)) {
    fail(`GitHub API response for ${label} is not JSON.`);
  }
  const contentLength = response.headers.get('content-length');
  if (contentLength !== null) {
    if (!/^(?:0|[1-9][0-9]*)$/u.test(contentLength)
        || Number(contentLength) > MAXIMUM_RESPONSE_BYTES) {
      fail(`GitHub API response for ${label} exceeds the byte limit.`);
    }
  }
  if (response.body === null || typeof response.body?.getReader !== 'function')
    fail(`GitHub API response for ${label} has no readable body.`);

  const reader = response.body.getReader();
  const chunks = [];
  let byteCount = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done)
        break;
      if (!(value instanceof Uint8Array))
        fail(`GitHub API response for ${label} contains an invalid body chunk.`);
      byteCount += value.byteLength;
      if (byteCount > MAXIMUM_RESPONSE_BYTES) {
        await reader.cancel();
        fail(`GitHub API response for ${label} exceeds the byte limit.`);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(byteCount);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    fail(`GitHub API response for ${label} is not valid UTF-8.`);
  }
  try {
    return JSON.parse(text);
  } catch {
    fail(`GitHub API response for ${label} is not valid JSON.`);
  }
}

function validateRun(run, { candidate, config, repository, runId }, label) {
  requirePlainObject(run, `${label} workflow run`);
  if (requirePositiveInteger(run.id, `${label} workflow run id`).toString() !== runId)
    fail(`${label} workflow run ID does not match the requested run.`);
  if (run.repository?.full_name !== repository)
    fail(`${label} workflow run repository does not match the candidate repository.`);
  if (run.status !== 'completed' || run.conclusion !== 'success')
    fail(`${label} workflow run is not completed successfully.`);
  if (run.head_sha !== candidate)
    fail(`${label} workflow run head_sha does not match the candidate.`);
  const pathHasExactWorkflow = run.path === config.workflowPath
    || (typeof run.path === 'string'
      && run.path.startsWith(`${config.workflowPath}@`)
      && run.path.length > config.workflowPath.length + 1
      && run.path.length <= config.workflowPath.length + 513
      && !/[\u0000-\u0020\u007f]/u.test(
        run.path.slice(config.workflowPath.length + 1),
      ));
  if (!pathHasExactWorkflow)
    fail(`${label} workflow run path is not ${config.workflowPath}.`);
  if (!config.events.includes(run.event))
    fail(`${label} workflow run event is not an allowed producer event.`);
  return requirePositiveInteger(run.run_attempt, `${label} workflow run_attempt`);
}

function validateArtifactListing(listing, {
  artifactName,
  candidate,
  runId,
}, label) {
  requirePlainObject(listing, `${label} artifact listing`);
  if (!Number.isSafeInteger(listing.total_count) || listing.total_count < 0
      || listing.total_count > MAXIMUM_ARTIFACTS_PER_RUN) {
    fail(`${label} artifact total_count is invalid or exceeds the limit.`);
  }
  if (!Array.isArray(listing.artifacts)
      || listing.artifacts.length !== listing.total_count) {
    fail(`${label} artifact listing is incomplete or malformed.`);
  }
  const matches = listing.artifacts.filter((artifact) =>
    artifact !== null && typeof artifact === 'object' && !Array.isArray(artifact)
      && artifact.name === artifactName);
  if (matches.length !== 1)
    fail(`${label} artifact listing must contain exactly one artifact named ${artifactName}.`);
  const artifact = matches[0];
  if (artifact.expired !== false)
    fail(`${label} artifact is expired or has no exact expiration state.`);
  const artifactId = requirePositiveInteger(artifact.id, `${label} artifact id`);
  if (artifact.workflow_run === null || typeof artifact.workflow_run !== 'object'
      || Array.isArray(artifact.workflow_run)) {
    fail(`${label} artifact has no workflow_run provenance.`);
  }
  if (requirePositiveInteger(
    artifact.workflow_run.id,
    `${label} artifact workflow_run id`,
  ).toString() !== runId) {
    fail(`${label} artifact workflow_run ID does not match the requested run.`);
  }
  if (artifact.workflow_run.head_sha !== candidate)
    fail(`${label} artifact workflow_run head_sha does not match the candidate.`);
  return artifactId;
}

function normalizeRequest(request, label) {
  requirePlainObject(request, `${label} request`);
  requireExactKeys(request, ['artifactName', 'runId'], `${label} request`);
  const runId = requireRunId(request.runId, `${label} runId`);
  if (typeof request.artifactName !== 'string' || request.artifactName.length > 256
      || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/u.test(request.artifactName)) {
    fail(`${label} artifactName is malformed.`);
  }
  return Object.freeze({ artifactName: request.artifactName, runId });
}

function normalizeRequests(requests) {
  requirePlainObject(requests, 'requests');
  requireExactKeys(requests, GATE_NAMES, 'requests');
  return Object.fromEntries(GATE_NAMES.map((gate) => [
    gate,
    normalizeRequest(requests[gate], gate),
  ]));
}

async function verifyConfiguredArtifact({
  artifactCache,
  candidate,
  config,
  fetchImpl,
  label,
  repository,
  request,
  runCache,
  token,
}) {
  let run = runCache.get(request.runId);
  if (run === undefined) {
    const runResponse = await fetchResponse(
      fetchImpl,
      githubUrl(repository, `/actions/runs/${request.runId}`),
      token,
      `${label} workflow run`,
    );
    run = await boundedJson(runResponse, `${label} workflow run`);
    runCache.set(request.runId, run);
  }
  const runAttempt = validateRun(run, {
    candidate,
    config,
    repository,
    runId: request.runId,
  }, label);
  const expectedArtifactName = `${config.artifactPrefix}-${candidate}-${request.runId}-${runAttempt}`;
  if (request.artifactName !== expectedArtifactName) {
    fail(`${label} artifactName is not the exact candidate/run/attempt-bound name.`);
  }

  let listing = artifactCache.get(request.runId);
  if (listing === undefined) {
    const artifactResponse = await fetchResponse(
      fetchImpl,
      githubUrl(
        repository,
        `/actions/runs/${request.runId}/artifacts?per_page=${MAXIMUM_ARTIFACTS_PER_RUN}&page=1`,
      ),
      token,
      `${label} artifact listing`,
    );
    listing = await boundedJson(artifactResponse, `${label} artifact listing`);
    artifactCache.set(request.runId, listing);
  }
  const artifactId = validateArtifactListing(listing, {
    artifactName: request.artifactName,
    candidate,
    runId: request.runId,
  }, label);
  return Object.freeze({
    artifactId,
    artifactName: request.artifactName,
    runAttempt,
    runId: request.runId,
  });
}

export async function verifyReleaseWorkflowArtifacts({
  candidate,
  fetchImpl = globalThis.fetch,
  repository,
  requests,
  token,
}) {
  const exactRepository = requireRepository(repository);
  const exactCandidate = requireCandidate(candidate);
  const exactToken = requireToken(token);
  const exactRequests = normalizeRequests(requests);
  if (typeof fetchImpl !== 'function')
    fail('fetchImpl must be a function.');

  const runCache = new Map();
  const artifactCache = new Map();
  const verified = {};
  for (const gate of GATE_NAMES) {
    verified[gate] = await verifyConfiguredArtifact({
      artifactCache,
      candidate: exactCandidate,
      config: GATES[gate],
      fetchImpl,
      label: gate,
      repository: exactRepository,
      request: exactRequests[gate],
      runCache,
      token: exactToken,
    });
  }
  return Object.freeze({
    artifacts: Object.freeze(verified),
    candidate: exactCandidate,
    repository: exactRepository,
  });
}

export async function verifyBenchmarkDraftWorkflowArtifact({
  candidate,
  fetchImpl = globalThis.fetch,
  repository,
  request,
  token,
}) {
  const exactRepository = requireRepository(repository);
  const exactCandidate = requireCandidate(candidate);
  const exactToken = requireToken(token);
  const exactRequest = normalizeRequest(request, 'benchmark-draft');
  if (typeof fetchImpl !== 'function')
    fail('fetchImpl must be a function.');
  const artifact = await verifyConfiguredArtifact({
    artifactCache: new Map(),
    candidate: exactCandidate,
    config: BENCHMARK_DRAFT_CONFIG,
    fetchImpl,
    label: 'benchmark-draft',
    repository: exactRepository,
    request: exactRequest,
    runCache: new Map(),
    token: exactToken,
  });
  return Object.freeze({
    artifact,
    candidate: exactCandidate,
    repository: exactRepository,
  });
}

function usage() {
  return 'Usage: node scripts/verify-release-workflow-artifacts.mjs '
    + '--repository <owner/name> --candidate <commit> '
    + '--fuzz-run-id <id> --fuzz-artifact-name <name> '
    + '--soak-run-id <id> --soak-artifact-name <name> '
    + '--operational-run-id <id> --operational-artifact-name <name> '
    + '--scans-run-id <id> --scans-artifact-name <name> '
    + '--benchmark-run-id <id> --benchmark-artifact-name <name>\n'
    + '   or: node scripts/verify-release-workflow-artifacts.mjs '
    + '--repository <owner/name> --candidate <commit> '
    + '--benchmark-draft-run-id <id> --benchmark-draft-artifact-name <name>';
}

const RELEASE_FLAGS = Object.freeze([
  '--repository',
  '--candidate',
  ...GATE_NAMES.flatMap((gate) => [
    `--${gate}-run-id`,
    `--${gate}-artifact-name`,
  ]),
]);
const BENCHMARK_DRAFT_FLAGS = Object.freeze([
  '--repository',
  '--candidate',
  '--benchmark-draft-run-id',
  '--benchmark-draft-artifact-name',
]);

function exactFlagValues(args, flags) {
  if (args.length !== flags.length * 2)
    return undefined;
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];
    if (!flags.includes(flag) || values.has(flag) || value === undefined)
      return undefined;
    values.set(flag, value);
  }
  return values.size === flags.length ? values : undefined;
}

function parseArguments(args) {
  const releaseValues = exactFlagValues(args, RELEASE_FLAGS);
  if (releaseValues !== undefined) {
    return {
      mode: 'release',
      options: {
        candidate: releaseValues.get('--candidate'),
        repository: releaseValues.get('--repository'),
        requests: Object.fromEntries(GATE_NAMES.map((gate) => [gate, {
          artifactName: releaseValues.get(`--${gate}-artifact-name`),
          runId: releaseValues.get(`--${gate}-run-id`),
        }])),
      },
    };
  }
  const draftValues = exactFlagValues(args, BENCHMARK_DRAFT_FLAGS);
  if (draftValues !== undefined) {
    return {
      mode: 'benchmark-draft',
      options: {
        candidate: draftValues.get('--candidate'),
        repository: draftValues.get('--repository'),
        request: {
          artifactName: draftValues.get('--benchmark-draft-artifact-name'),
          runId: draftValues.get('--benchmark-draft-run-id'),
        },
      },
    };
  }
  throw new ReleaseWorkflowArtifactCliError(usage());
}

export async function runReleaseWorkflowArtifactCli({
  args,
  environment,
  fetchImpl = globalThis.fetch,
  writeOutput = (message) => console.log(message),
}) {
  if (!Array.isArray(args) || environment === null
      || typeof environment !== 'object' || typeof writeOutput !== 'function') {
    throw new ReleaseWorkflowArtifactCliError(usage());
  }
  const token = environment.GITHUB_TOKEN;
  if (token === undefined)
    throw new ReleaseWorkflowArtifactCliError('GITHUB_TOKEN must be set.');
  const parsed = parseArguments(args);
  if (parsed.mode === 'benchmark-draft') {
    const result = await verifyBenchmarkDraftWorkflowArtifact({
      ...parsed.options,
      fetchImpl,
      token,
    });
    writeOutput(`benchmark draft workflow artifact provenance PASS candidate=${result.candidate} artifacts=1`);
    return Object.freeze({ mode: parsed.mode, result });
  }
  const result = await verifyReleaseWorkflowArtifacts({
    ...parsed.options,
    fetchImpl,
    token,
  });
  writeOutput(`release workflow artifact provenance PASS candidate=${result.candidate} artifacts=5`);
  return Object.freeze({ mode: parsed.mode, result });
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  try {
    await runReleaseWorkflowArtifactCli({
      args: process.argv.slice(2),
      environment: process.env,
    });
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseWorkflowArtifactCliError
      ? 64
      : error instanceof ReleaseWorkflowArtifactVerificationError ? 1 : 70;
  }
}
