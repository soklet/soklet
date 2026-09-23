#!/usr/bin/env node

// This sidecar assesses a proposed upstream-harness exception. It does not
// change the official expected profile, runner verdict, or release gate.
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { adjudicateChecks } from '../adjudicate.mjs';
import { exactlyOneChecksFile } from '../run.mjs';
import { officialScenarioArguments, verifyManifestSet, verifyOfficialSuite } from '../verify.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const scenario = 'server-stateless';
const protocolVersion = '2026-07-28';
const suiteCommit = 'a983ba93c91e0bb31d0b6849eeb52f0ad1083107';
const suiteSourceTreeSha256 = 'e63d6f13100504101afdfd5cfd084c92d801e2b4466d68965aa2e0c48a87998d';
const statelessSourceSha256 = 'a613e6c7ee39af4d09ec416e97922258fe09d0d9665221f850ff77c9f00b6f97';
const toolName = 'test_missing_elicitation_capability';
const elicitationForm = { elicitation: { form: {} } };
const exceptionChecks = Object.freeze([
  Object.freeze({
    id: 'sep-2575-server-rejects-undeclared-capability',
    name: 'ServerRejectsUndeclaredCapability',
    description: 'A server MUST NOT rely on capabilities the client has not declared. If processing a request requires a capability the client did not include in io.modelcontextprotocol/clientCapabilities, the server MUST return a MissingRequiredClientCapabilityError (-32021).',
    errorMessage: "Not testable: server does not list the diagnostic tool 'test_missing_capability' in tools/list and the probe call did not exercise it (required for the undeclared-capability rejection)",
    details: {
      untestable: true,
      response: {
        jsonrpc: '2.0', id: 401,
        error: { code: -32602, message: 'Invalid params' },
      },
    },
  }),
  Object.freeze({
    id: 'sep-2575-missing-capability-http-400',
    name: 'MissingCapabilityHttp400',
    description: 'On HTTP, the response status MUST be 400 Bad Request [for MissingRequiredClientCapabilityError].',
    errorMessage: "Not testable: server does not list the diagnostic tool 'test_missing_capability' in tools/list, so the -32021 HTTP status could not be validated",
    details: { untestable: true, httpStatus: 400 },
  }),
]);
const exceptionIds = new Set(exceptionChecks.map((check) => check.id));
const shaPattern = /^[0-9a-f]{64}$/;

function fail(message) { throw new Error(`P0-C proposal: ${message}`); }
function exact(value, expected, description) {
  if (!isDeepStrictEqual(value, expected)) fail(`${description} changed`);
}
function object(value, description) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${description} must be an object`);
  return value;
}
function keys(value, expected, description) {
  object(value, description);
  exact(Object.keys(value).sort(), [...expected].sort(), `${description} fields`);
}
function sha(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function isSha(value) { return typeof value === 'string' && shaPattern.test(value); }

function receiptIdentity(receipt, derived, label) {
  if (receipt.formatVersion !== 1 || receipt.scenario !== scenario
      || receipt.protocolVersion !== protocolVersion
      || receipt.suiteCommit !== suiteCommit
      || receipt.suiteSourceTreeSha256 !== suiteSourceTreeSha256
      || receipt.candidateJarSha256 !== derived.candidateJarSha256
      || receipt.fixtureSourceSha256 !== derived.fixtureSourceSha256
      || receipt.fixtureClassesSha256 !== derived.fixtureClassesSha256
      || receipt.runId !== derived.runId)
    fail(`${label} identity or paired-run binding changed`);
}

function validateOfficialReceipt(receipt, derived) {
  keys(receipt, ['formatVersion', 'scenario', 'protocolVersion', 'suiteCommit',
    'suiteSourceTreeSha256', 'candidateJarSha256', 'fixtureSourceSha256',
    'fixtureClassesSha256', 'runId', 'endpoint', 'checksSha256', 'stdoutSha256',
    'stderrSha256', 'exitCode', 'signal', 'timedOut', 'outputFailure'],
  'official receipt');
  receiptIdentity(receipt, derived, 'official receipt');
  if (receipt.checksSha256 !== derived.checksSha256
      || receipt.stdoutSha256 !== derived.stdoutSha256
      || receipt.stderrSha256 !== derived.stderrSha256)
    fail('official raw-output hash binding changed');
  if (receipt.exitCode !== 1 || receipt.signal !== null
      || receipt.timedOut !== false || receipt.outputFailure !== null)
    fail('official CLI must retain its exact nonzero failure verdict');
}

function parseExchange(exchange, name) {
  keys(exchange, ['requestBody', 'responseBody', 'httpStatus'], `${name} exchange`);
  if (typeof exchange.requestBody !== 'string' || exchange.requestBody.length === 0
      || typeof exchange.responseBody !== 'string' || exchange.responseBody.length === 0)
    fail(`${name} raw exchange is missing`);
  let request;
  let response;
  try {
    request = JSON.parse(exchange.requestBody);
    response = JSON.parse(exchange.responseBody);
  } catch {
    fail(`${name} raw exchange is not JSON`);
  }
  object(request, `${name} request`);
  object(response, `${name} response`);
  return { request, response, httpStatus: exchange.httpStatus };
}

function validateControlReceipt(receipt, derived) {
  keys(receipt, ['formatVersion', 'captureKind', 'scenario', 'protocolVersion',
    'suiteCommit', 'suiteSourceTreeSha256', 'candidateJarSha256',
    'fixtureSourceSha256', 'fixtureClassesSha256', 'runId', 'endpoint',
    'tool', 'negative', 'positive', 'process'], 'control receipt');
  receiptIdentity(receipt, derived, 'control receipt');
  if (receipt.captureKind !== 'REAL_LOOPBACK_SOCKET' || receipt.tool !== toolName)
    fail('control must be the real-socket Elicitation diagnostic');
  exact(receipt.process, {
    ready: true, stopped: true, exitCode: 0, signal: null, forcedCleanup: false,
  }, 'control fixture process outcome');
  let endpoint;
  try { endpoint = new URL(receipt.endpoint); } catch { fail('control endpoint is invalid'); }
  if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1'
      || !/^[1-9][0-9]*$/.test(endpoint.port)
      || Number(endpoint.port) > 65535 || endpoint.pathname !== '/mcp'
      || endpoint.search !== '' || endpoint.hash !== '')
    fail('control endpoint must be the loopback MCP listener');
  const negative = parseExchange(receipt.negative, 'negative');
  const positive = parseExchange(receipt.positive, 'positive');
  for (const [name, exchange, capabilities] of [
    ['negative', negative, {}], ['positive', positive, elicitationForm],
  ]) {
    const { request, response } = exchange;
    if (request.jsonrpc !== '2.0' || request.method !== 'tools/call'
        || !Number.isSafeInteger(request.id) || request.id < 1
        || request.params?.name !== toolName
        || !isDeepStrictEqual(request.params?.arguments, {})
        || request.params?._meta?.['io.modelcontextprotocol/protocolVersion']
          !== protocolVersion
        || !isDeepStrictEqual(
          request.params?._meta?.['io.modelcontextprotocol/clientCapabilities'],
          capabilities)
        || response.jsonrpc !== '2.0' || response.id !== request.id)
      fail(`${name} request/response identity or capability declaration changed`);
  }
  if (negative.request.id === positive.request.id)
    fail('control request IDs must be distinct');
  if (negative.httpStatus !== 400
      || !isDeepStrictEqual(negative.response.error, {
        code: -32021,
        message: 'Missing required client capability',
        data: { requiredCapabilities: elicitationForm },
      })
      || Object.hasOwn(negative.response, 'result'))
    fail('negative Elicitation control must return exact HTTP 400 / -32021 data');
  if (positive.httpStatus !== 200
      || positive.response.result?.resultType !== 'complete'
      || Object.hasOwn(positive.response, 'error'))
    fail('positive Elicitation control must complete with HTTP 200');
}

export function assessP0C({ checks, profile, pins, officialReceipt,
  controlReceipt, derived }) {
  if (!Array.isArray(checks) || profile?.id !== 'server-stateless.phase5.v1'
      || profile.scenario !== scenario || profile.suiteCommit !== suiteCommit
      || pins?.protocolVersion !== protocolVersion
      || pins.officialConformanceSuite?.commit !== suiteCommit
      || pins.officialConformanceSuite?.sourceTree?.sha256 !== suiteSourceTreeSha256)
    fail('scenario, expected profile, or exact official suite pin changed');
  object(derived, 'derived file identity');
  if (!isSha(derived.candidateJarSha256) || !isSha(derived.fixtureSourceSha256)
      || !isSha(derived.fixtureClassesSha256) || !isSha(derived.checksSha256)
      || !isSha(derived.stdoutSha256) || !isSha(derived.stderrSha256)
      || typeof derived.runId !== 'string'
      || !/^[A-Za-z0-9][A-Za-z0-9._-]{7,127}$/.test(derived.runId))
    fail('derived file identity or run ID is invalid');
  validateOfficialReceipt(officialReceipt, derived);
  validateControlReceipt(controlReceipt, derived);
  if (officialReceipt.endpoint !== controlReceipt.endpoint)
    fail('official probe and socket controls used different fixture endpoints');

  const actualExceptions = checks.filter((check) => exceptionIds.has(check?.id));
  if (actualExceptions.length !== 2) fail('exactly two named official checks are required');
  for (const expected of exceptionChecks) {
    const actual = actualExceptions.find((check) => check.id === expected.id);
    keys(actual, ['id', 'name', 'description', 'status', 'timestamp',
      'errorMessage', 'specReferences', 'details'],
    `official exception check ${expected.id}`);
    if (actual?.status !== 'FAILURE' || actual.name !== expected.name
        || actual.description !== expected.description
        || typeof actual.timestamp !== 'string'
        || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/.test(actual.timestamp)
        || actual.errorMessage !== expected.errorMessage
        || !isDeepStrictEqual(actual.details, expected.details)
        || !isDeepStrictEqual(actual.specReferences, [{
          id: 'SEP-2575',
          url: 'https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2575',
        }]))
      fail(`official exception check ${expected.id} changed shape or status`);
    const frozen = profile.checks.filter((check) => check.id === expected.id);
    if (!isDeepStrictEqual(frozen, [{ id: expected.id, status: 'SUCCESS', count: 1 }]))
      fail(`frozen expected success check ${expected.id} changed`);
  }
  const unaffectedChecks = checks.filter((check) => !exceptionIds.has(check.id));
  const unaffectedProfile = {
    ...profile,
    checks: profile.checks.filter((check) => !exceptionIds.has(check.id)),
  };
  adjudicateChecks(scenario, unaffectedChecks, unaffectedProfile);

  return Object.freeze({
    assessment: 'PROPOSAL_REVIEWABLE',
    adopted: false,
    officialVerdict: 'FAILURE',
    officialExitCode: 1,
    scenario,
    suiteCommit,
    suiteSourceTreeSha256,
    candidateJarSha256: derived.candidateJarSha256,
    fixtureSourceSha256: derived.fixtureSourceSha256,
    fixtureClassesSha256: derived.fixtureClassesSha256,
    checksSha256: derived.checksSha256,
    officialStdoutSha256: derived.stdoutSha256,
    officialStderrSha256: derived.stderrSha256,
    runId: derived.runId,
    endpoint: officialReceipt.endpoint,
    rawCheckCount: checks.length,
    upstreamFailureIds: exceptionChecks.map((check) => check.id),
    unaffectedCheckCount: unaffectedChecks.length,
    independentControl: 'REAL_SOCKET_ELICITATION_NEGATIVE_AND_POSITIVE_PASS',
    releaseGate: 'UNCHANGED',
  });
}

function boundedFile(path, maximumBytes, label) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink() || stats.size > maximumBytes)
    fail(`${label} must be a bounded regular file`);
  return readFileSync(path);
}

function parsedJson(bytes, label) {
  const decoded = bytes.toString('utf8');
  if (Buffer.from(decoded, 'utf8').compare(bytes) !== 0)
    fail(`${label} must be UTF-8`);
  try { return JSON.parse(decoded); } catch { fail(`${label} is not JSON`); }
}

export function fixtureClassesTreeIdentity(directory) {
  const paths = [];
  let totalBytes = 0;
  let directoryCount = 0;
  function visit(dir, depth) {
    directoryCount++;
    if (depth > 32 || directoryCount > 2048)
      fail('fixture classes tree exceeds its directory bound');
    const stats = lstatSync(dir);
    if (!stats.isDirectory() || stats.isSymbolicLink())
      fail('fixture classes tree must contain only real directories');
    for (const name of readdirSync(dir)) {
      const path = resolve(dir, name);
      const entry = lstatSync(path);
      if (entry.isSymbolicLink()) fail('fixture classes tree contains a symbolic link');
      if (entry.isDirectory()) visit(path, depth + 1);
      else if (entry.isFile()) {
        paths.push(path);
        totalBytes += entry.size;
        if (paths.length > 2048 || totalBytes > 64 * 1024 * 1024)
          fail('fixture classes tree exceeds its bound');
      } else fail('fixture classes tree contains an unsupported entry');
    }
  }
  visit(directory, 0);
  if (paths.length === 0) fail('fixture classes tree is empty');
  const rows = paths.map((path) => ({
    name: relative(directory, path).split(sep).join('/'),
    digest: sha(readFileSync(path)),
  })).sort((a, b) => Buffer.compare(Buffer.from(a.name), Buffer.from(b.name)))
    .map((row) => `${row.digest}  ${row.name}\n`).join('');
  return Object.freeze({ sha256: sha(Buffer.from(rows, 'utf8')),
    fileCount: paths.length });
}

export function fixtureClassesTreeSha256(directory) {
  return fixtureClassesTreeIdentity(directory).sha256;
}

function capturedFile(descriptor, expectedPath, maximumBytes, label) {
  keys(descriptor, ['path', 'bytes', 'sha256'], `${label} descriptor`);
  if (descriptor.path !== expectedPath || !Number.isInteger(descriptor.bytes)
      || descriptor.bytes < 0 || descriptor.bytes > maximumBytes
      || !isSha(descriptor.sha256))
    fail(`${label} descriptor path, size, or digest is invalid`);
  const bytes = boundedFile(expectedPath, maximumBytes, label);
  if (bytes.length !== descriptor.bytes || sha(bytes) !== descriptor.sha256)
    fail(`${label} raw bytes differ from the captured manifest`);
  return bytes;
}

export function verifyRawControlArtifacts({ controlPath, controlBytes,
  controlReceipt, candidateJarPath, candidateJarBytes,
  fixtureClassesDirectory, classTree }) {
  const controlDirectory = dirname(controlPath);
  const finalManifestBytes = boundedFile(resolve(controlDirectory,
    'capture-manifest.json'), 64 * 1024, 'control capture manifest');
  const rawManifestBytes = boundedFile(resolve(controlDirectory,
    'capture-raw-manifest.json'), 64 * 1024, 'raw control manifest');
  const finalManifest = parsedJson(finalManifestBytes, 'control capture manifest');
  const rawManifest = parsedJson(rawManifestBytes, 'raw control manifest');
  keys(finalManifest, ['formatVersion', 'runId', 'endpoint', 'candidateJar',
    'fixture', 'exchanges', 'process', 'receiptSha256'], 'control capture manifest');
  keys(rawManifest, ['formatVersion', 'runId', 'endpoint', 'candidateJar',
    'fixture', 'exchanges'], 'raw control manifest');
  if (finalManifest.receiptSha256 !== sha(controlBytes))
    fail('control receipt differs from its capture manifest');
  exact(finalManifest.process, controlReceipt.process,
    'control fixture cleanup manifest');
  const { process: _process, receiptSha256: _receiptSha256,
    ...commonManifest } = finalManifest;
  exact(commonManifest, rawManifest, 'raw and final control manifests');
  if (rawManifest.formatVersion !== 1
      || rawManifest.runId !== controlReceipt.runId
      || rawManifest.endpoint !== controlReceipt.endpoint)
    fail('raw control manifest identity changed');

  const candidateBytes = boundedFile(candidateJarPath,
    128 * 1024 * 1024, 'captured candidate JAR');
  exact(rawManifest.candidateJar, {
    path: candidateJarPath,
    bytes: candidateJarBytes,
    sha256: controlReceipt.candidateJarSha256,
  }, 'captured candidate JAR descriptor');
  if (candidateBytes.length !== candidateJarBytes
      || sha(candidateBytes) !== controlReceipt.candidateJarSha256)
    fail('captured candidate JAR changed');

  const sourcePath = resolve(root,
    'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java');
  const classPath = resolve(fixtureClassesDirectory,
    'com/soklet/conformance/McpConformanceFixture.class');
  const buildScriptPath = resolve(root, 'conformance/official/build-public-fixture.sh');
  keys(rawManifest.fixture, ['sourcePath', 'sourceSha256', 'classPath',
    'classSha256', 'classesSha256', 'classesFileCount', 'buildScriptSha256'],
  'captured fixture');
  if (rawManifest.fixture.sourcePath !== sourcePath
      || rawManifest.fixture.sourceSha256 !== controlReceipt.fixtureSourceSha256
      || rawManifest.fixture.classPath !== classPath
      || rawManifest.fixture.classSha256
        !== sha(boundedFile(classPath, 4 * 1024 * 1024, 'fixture main class'))
      || rawManifest.fixture.classesSha256 !== classTree.sha256
      || rawManifest.fixture.classesFileCount !== classTree.fileCount
      || rawManifest.fixture.buildScriptSha256
        !== sha(boundedFile(buildScriptPath, 1024 * 1024, 'fixture build script')))
    fail('captured fixture source, compiled classes, or build script changed');

  if (!Array.isArray(rawManifest.exchanges) || rawManifest.exchanges.length !== 2)
    fail('control manifest must contain exactly two raw exchanges');
  for (const [index, name] of ['negative', 'positive'].entries()) {
    const row = rawManifest.exchanges[index];
    keys(row, ['case', 'requestBody', 'responseBody', 'requestHeaders',
      'responseHeaders', 'httpStatus'], `${name} raw exchange`);
    if (row.case !== name || row.httpStatus !== controlReceipt[name].httpStatus)
      fail(`${name} raw exchange identity or status changed`);
    const requestBody = capturedFile(row.requestBody,
      resolve(controlDirectory, `${name}.request-body.json`),
      1024 * 1024, `${name} request body`);
    const responseBody = capturedFile(row.responseBody,
      resolve(controlDirectory, `${name}.response-body.json`),
      1024 * 1024, `${name} response body`);
    const requestHeaders = parsedJson(capturedFile(row.requestHeaders,
      resolve(controlDirectory, `${name}.request-headers.json`),
      64 * 1024, `${name} request headers`), `${name} request headers`);
    const responseHeaders = parsedJson(capturedFile(row.responseHeaders,
      resolve(controlDirectory, `${name}.response-headers.json`),
      64 * 1024, `${name} response headers`), `${name} response headers`);
    const requestText = requestBody.toString('utf8');
    const responseText = responseBody.toString('utf8');
    if (!Buffer.from(requestText, 'utf8').equals(requestBody)
        || !Buffer.from(responseText, 'utf8').equals(responseBody)
        || requestText !== controlReceipt[name].requestBody
        || responseText !== controlReceipt[name].responseBody)
      fail(`${name} retained raw bodies differ from the control receipt`);
    const endpoint = new URL(controlReceipt.endpoint);
    exact(requestHeaders, {
      Host: endpoint.host,
      'Content-Type': 'application/json; charset=UTF-8',
      Accept: 'application/json, text/event-stream',
      'MCP-Protocol-Version': protocolVersion,
      'Mcp-Method': 'tools/call',
      'Mcp-Name': toolName,
      'Content-Length': String(requestBody.length),
    }, `${name} socket request headers`);
    keys(responseHeaders, ['statusCode', 'headers', 'rawHeaders'],
      `${name} socket response headers`);
    if (responseHeaders.statusCode !== row.httpStatus
        || responseHeaders.headers?.['content-length'] !== String(responseBody.length)
        || responseHeaders.headers?.['content-type'] !== 'application/json'
        || responseHeaders.headers?.['cache-control'] !== 'no-store'
        || !Array.isArray(responseHeaders.rawHeaders)
        || responseHeaders.rawHeaders.length % 2 !== 0)
      fail(`${name} retained socket response headers changed`);
  }
  return Object.freeze({
    captureManifestSha256: sha(finalManifestBytes),
    rawManifestSha256: sha(rawManifestBytes),
    controlReceiptSha256: sha(controlBytes),
  });
}

function parseArguments(args) {
  const fields = ['--suite-dir', '--candidate-jar', '--fixture-classes-dir',
    '--checks', '--official-stdout', '--official-stderr', '--official-receipt',
    '--control'];
  if (args.length !== fields.length * 2) fail('eight exact path arguments are required');
  const values = new Map();
  for (let i = 0; i < args.length; i += 2) {
    if (!fields.includes(args[i]) || values.has(args[i]) || !args[i + 1])
      fail('unknown, duplicate, or empty path argument');
    values.set(args[i], resolve(args[i + 1]));
  }
  if (values.size !== fields.length) fail('a required path argument is missing');
  return values;
}

export function validateOfficialCommand(command, { pins, runId, endpoint,
  suiteDirectory, resultDirectory }) {
  keys(command, ['executable', 'arguments', 'workingDirectory',
    'fixtureEndpoint', 'timeoutMilliseconds', 'runId'], 'official command');
  if (typeof command.executable !== 'string'
      || !isAbsolute(command.executable) || command.executable.includes('\0')
      || command.workingDirectory !== suiteDirectory
      || command.fixtureEndpoint !== endpoint
      || command.timeoutMilliseconds !== 60_000
      || command.runId !== runId)
    fail('official command provenance changed');
  const entryPoint = resolve(suiteDirectory,
    pins.officialConformanceSuite.entryPoint);
  exact(command.arguments, [entryPoint, ...officialScenarioArguments(pins, {
    fixtureUrl: endpoint,
    scenarioName: scenario,
    outputDirectory: resultDirectory,
  })], 'official command arguments');
}

export function verifyOriginalOfficialChecks(resultDirectory, copiedChecksPath) {
  const originalPath = exactlyOneChecksFile(resultDirectory);
  if (originalPath === copiedChecksPath)
    fail('stable checks copy must be separate from the official result tree');
  const original = boundedFile(originalPath, 8 * 1024 * 1024,
    'original official checks');
  const copy = boundedFile(copiedChecksPath, 8 * 1024 * 1024,
    'stable official checks copy');
  if (!original.equals(copy))
    fail('stable checks copy differs from the original official result');
  return sha(original);
}

export function assessP0CFiles(args) {
  const values = parseArguments(args);
  const { pins, expectedChecks } = verifyManifestSet();
  verifyOfficialSuite(values.get('--suite-dir'), pins);
  if (sha(boundedFile(resolve(values.get('--suite-dir'),
    'src/scenarios/server/stateless.ts'), 1024 * 1024,
  'official stateless source')) !== statelessSourceSha256)
    fail('official stateless scenario source changed');
  const fixtureSourceSha256 = sha(boundedFile(resolve(root,
    'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java'),
  1024 * 1024, 'fixture source'));
  const candidateJarBytes = boundedFile(values.get('--candidate-jar'),
    128 * 1024 * 1024, 'candidate JAR');
  const classTree = fixtureClassesTreeIdentity(values.get('--fixture-classes-dir'));
  const derived = {
    candidateJarSha256: sha(candidateJarBytes),
    fixtureSourceSha256,
    fixtureClassesSha256: classTree.sha256,
    checksSha256: sha(boundedFile(values.get('--checks'), 8 * 1024 * 1024,
      'raw checks')),
    stdoutSha256: sha(boundedFile(values.get('--official-stdout'), 1024 * 1024,
      'official stdout')),
    stderrSha256: sha(boundedFile(values.get('--official-stderr'), 1024 * 1024,
      'official stderr')),
  };
  const officialReceipt = parsedJson(boundedFile(values.get('--official-receipt'),
    64 * 1024, 'official receipt'), 'official receipt');
  const officialDirectory = dirname(values.get('--official-receipt'));
  const commandBytes = boundedFile(resolve(officialDirectory, 'command.json'),
    64 * 1024, 'official command');
  validateOfficialCommand(parsedJson(commandBytes, 'official command'), {
    pins,
    runId: officialReceipt.runId,
    endpoint: officialReceipt.endpoint,
    suiteDirectory: values.get('--suite-dir'),
    resultDirectory: resolve(officialDirectory, 'official-results'),
  });
  const originalChecksSha256 = verifyOriginalOfficialChecks(
    resolve(officialDirectory, 'official-results'), values.get('--checks'));
  const controlBytes = boundedFile(values.get('--control'),
    1024 * 1024, 'control receipt');
  const controlReceipt = parsedJson(controlBytes, 'control receipt');
  derived.runId = officialReceipt.runId;
  const profile = expectedChecks.profiles.find((item) =>
    item.id === 'server-stateless.phase5.v1');
  const assessment = assessP0C({
    checks: parsedJson(boundedFile(values.get('--checks'), 8 * 1024 * 1024,
      'raw checks'), 'raw checks'),
    profile, pins, officialReceipt, controlReceipt, derived,
  });
  const rawControl = verifyRawControlArtifacts({
    controlPath: values.get('--control'),
    controlBytes,
    controlReceipt,
    candidateJarPath: values.get('--candidate-jar'),
    candidateJarBytes: candidateJarBytes.length,
    fixtureClassesDirectory: values.get('--fixture-classes-dir'),
    classTree,
  });
  return Object.freeze({ ...assessment, officialCommandSha256: sha(commandBytes),
    originalChecksSha256,
    ...rawControl });
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const assessment = assessP0CFiles(process.argv.slice(2));
    process.stdout.write(`${JSON.stringify(assessment, null, 2)}\n`);
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
