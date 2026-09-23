#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assessP0C, fixtureClassesTreeIdentity,
  validateOfficialCommand, verifyOriginalOfficialChecks,
  verifyRawControlArtifacts } from './p0c-disposition.mjs';
import { officialScenarioArguments, verifyManifestSet } from '../verify.mjs';

const { pins, expectedChecks } = verifyManifestSet();
const profile = expectedChecks.profiles.find((item) =>
  item.id === 'server-stateless.phase5.v1');
assert.ok(profile);
const suiteCommit = pins.officialConformanceSuite.commit;
const suiteSourceTreeSha256 = pins.officialConformanceSuite.sourceTree.sha256;
const protocolVersion = pins.protocolVersion;
const derived = {
  candidateJarSha256: 'a'.repeat(64),
  fixtureSourceSha256: 'b'.repeat(64),
  fixtureClassesSha256: 'c'.repeat(64),
  checksSha256: 'd'.repeat(64),
  stdoutSha256: 'e'.repeat(64),
  stderrSha256: 'f'.repeat(64),
  runId: 'p0c-test-run-20260922',
};
const commonReceipt = {
  formatVersion: 1,
  scenario: 'server-stateless',
  protocolVersion,
  suiteCommit,
  suiteSourceTreeSha256,
  candidateJarSha256: derived.candidateJarSha256,
  fixtureSourceSha256: derived.fixtureSourceSha256,
  fixtureClassesSha256: derived.fixtureClassesSha256,
  runId: derived.runId,
  endpoint: 'http://127.0.0.1:43123/mcp',
};
const officialReceipt = {
  ...commonReceipt,
  checksSha256: derived.checksSha256,
  stdoutSha256: derived.stdoutSha256,
  stderrSha256: derived.stderrSha256,
  exitCode: 1,
  signal: null,
  timedOut: false,
  outputFailure: null,
};
const request = (id, capabilities) => JSON.stringify({
  jsonrpc: '2.0', id, method: 'tools/call',
  params: {
    name: 'test_missing_elicitation_capability', arguments: {},
    _meta: {
      'io.modelcontextprotocol/protocolVersion': protocolVersion,
      'io.modelcontextprotocol/clientCapabilities': capabilities,
    },
  },
});
const form = { elicitation: { form: {} } };
const controlReceipt = {
  ...commonReceipt,
  captureKind: 'REAL_LOOPBACK_SOCKET',
  endpoint: 'http://127.0.0.1:43123/mcp',
  tool: 'test_missing_elicitation_capability',
  negative: {
    requestBody: request(9101, {}),
    responseBody: JSON.stringify({
      jsonrpc: '2.0', id: 9101,
      error: {
        code: -32021,
        message: 'Missing required client capability',
        data: { requiredCapabilities: form },
      },
    }),
    httpStatus: 400,
  },
  positive: {
    requestBody: request(9102, form),
    responseBody: JSON.stringify({
      jsonrpc: '2.0', id: 9102,
      result: { resultType: 'complete', content: [] },
    }),
    httpStatus: 200,
  },
  process: {
    ready: true, stopped: true, exitCode: 0, signal: null, forcedCleanup: false,
  },
};
const failures = [
  {
    id: 'sep-2575-server-rejects-undeclared-capability',
    name: 'ServerRejectsUndeclaredCapability',
    description: 'A server MUST NOT rely on capabilities the client has not declared. If processing a request requires a capability the client did not include in io.modelcontextprotocol/clientCapabilities, the server MUST return a MissingRequiredClientCapabilityError (-32021).',
    status: 'FAILURE', timestamp: '2026-09-22T12:34:56.000Z',
    errorMessage: "Not testable: server does not list the diagnostic tool 'test_missing_capability' in tools/list and the probe call did not exercise it (required for the undeclared-capability rejection)",
    specReferences: [{
      id: 'SEP-2575',
      url: 'https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2575',
    }],
    details: {
      untestable: true,
      response: {
        jsonrpc: '2.0', id: 401,
        error: { code: -32602, message: 'Invalid params' },
      },
    },
  },
  {
    id: 'sep-2575-missing-capability-http-400',
    name: 'MissingCapabilityHttp400',
    description: 'On HTTP, the response status MUST be 400 Bad Request [for MissingRequiredClientCapabilityError].',
    status: 'FAILURE', timestamp: '2026-09-22T12:34:56.000Z',
    errorMessage: "Not testable: server does not list the diagnostic tool 'test_missing_capability' in tools/list, so the -32021 HTTP status could not be validated",
    specReferences: [{
      id: 'SEP-2575',
      url: 'https://github.com/modelcontextprotocol/modelcontextprotocol/pull/2575',
    }],
    details: { untestable: true, httpStatus: 400 },
  },
];
const checks = profile.checks.flatMap((expected) =>
  Array.from({ length: expected.count }, () => expected.status === 'SKIPPED'
    ? { id: expected.id, status: 'SKIPPED', details: { note: expected.reason } }
    : { id: expected.id, status: expected.status }));
for (const failure of failures) {
  const index = checks.findIndex((check) => check.id === failure.id);
  assert.ok(index >= 0);
  checks[index] = failure;
}
for (let i = 0; i < profile.automaticWireChecks['wire-schema-valid']; i++)
  checks.push({ id: 'wire-schema-valid', status: 'SUCCESS',
    details: { messagesValidated: 1 } });

const original = { checks, profile, pins, officialReceipt, controlReceipt, derived };
const result = assessP0C(original);
assert.equal(result.assessment, 'PROPOSAL_REVIEWABLE');
assert.equal(result.adopted, false);
assert.equal(result.officialVerdict, 'FAILURE');
assert.equal(result.rawCheckCount, 30);
assert.equal(result.upstreamFailureIds.length, 2);

const commandOptions = {
  pins,
  runId: derived.runId,
  endpoint: officialReceipt.endpoint,
  suiteDirectory: '/absolute/pinned-suite',
  resultDirectory: '/absolute/proposal/official/official-results',
};
const command = {
  executable: '/absolute/node',
  arguments: [
    '/absolute/pinned-suite/dist/index.js',
    ...officialScenarioArguments(pins, {
      fixtureUrl: commandOptions.endpoint,
      scenarioName: 'server-stateless',
      outputDirectory: commandOptions.resultDirectory,
    }),
  ],
  workingDirectory: commandOptions.suiteDirectory,
  fixtureEndpoint: commandOptions.endpoint,
  timeoutMilliseconds: 60_000,
  runId: commandOptions.runId,
};
assert.doesNotThrow(() => validateOfficialCommand(command, commandOptions));
for (const [name, modify] of [
  ['wrong official command endpoint', (value) => { value.fixtureEndpoint = 'http://127.0.0.1:1/mcp'; }],
  ['wrong official command scenario', (value) => { value.arguments[5] = 'other'; }],
  ['wrong official command timeout', (value) => { value.timeoutMilliseconds = 0; }],
  ['wrong official command run', (value) => { value.runId = 'other-run'; }],
]) {
  const changed = structuredClone(command);
  modify(changed);
  assert.throws(() => validateOfficialCommand(changed, commandOptions),
    undefined, name);
}

let mutationsRejected = 0;
function reject(label, mutate) {
  const input = structuredClone(original);
  mutate(input);
  assert.throws(() => assessP0C(input), undefined, label);
  mutationsRejected++;
}
function failed(input, id) {
  return input.checks.find((check) => check.id === id);
}
const first = failures[0].id;
const second = failures[1].id;
reject('missing official receipt', (x) => { x.officialReceipt = null; });
reject('zero official exit', (x) => { x.officialReceipt.exitCode = 0; });
reject('other nonzero official exit', (x) => { x.officialReceipt.exitCode = 2; });
reject('official timeout', (x) => { x.officialReceipt.timedOut = true; });
reject('official signal', (x) => { x.officialReceipt.signal = 'SIGTERM'; });
reject('official checks hash drift', (x) => { x.officialReceipt.checksSha256 = '0'.repeat(64); });
reject('official stdout hash drift', (x) => { x.officialReceipt.stdoutSha256 = '0'.repeat(64); });
reject('official suite drift', (x) => { x.officialReceipt.suiteCommit = '0'.repeat(40); });
reject('official suite source drift', (x) => {
  x.officialReceipt.suiteSourceTreeSha256 = '0'.repeat(64);
});
reject('missing socket control', (x) => { x.controlReceipt = null; });
reject('different run', (x) => { x.controlReceipt.runId = 'another-p0c-run'; });
reject('different fixture endpoint', (x) => {
  x.officialReceipt.endpoint = 'http://127.0.0.1:43124/mcp';
});
reject('different candidate', (x) => { x.controlReceipt.candidateJarSha256 = '0'.repeat(64); });
reject('different fixture source', (x) => { x.controlReceipt.fixtureSourceSha256 = '0'.repeat(64); });
reject('different fixture classes', (x) => { x.controlReceipt.fixtureClassesSha256 = '0'.repeat(64); });
reject('different protocol', (x) => { x.controlReceipt.protocolVersion = 'other'; });
reject('simulator control', (x) => { x.controlReceipt.captureKind = 'SIMULATOR'; });
reject('control not stopped', (x) => { x.controlReceipt.process.stopped = false; });
reject('control exited nonzero', (x) => { x.controlReceipt.process.exitCode = 1; });
reject('forced cleanup', (x) => { x.controlReceipt.process.forcedCleanup = true; });
reject('missing raw negative body', (x) => { x.controlReceipt.negative.responseBody = ''; });
reject('wrong negative HTTP status', (x) => { x.controlReceipt.negative.httpStatus = 200; });
reject('wrong negative error code', (x) => {
  const body = JSON.parse(x.controlReceipt.negative.responseBody);
  body.error.code = -32602;
  x.controlReceipt.negative.responseBody = JSON.stringify(body);
});
reject('wrong required capability', (x) => {
  const body = JSON.parse(x.controlReceipt.negative.responseBody);
  body.error.data.requiredCapabilities = { sampling: {} };
  x.controlReceipt.negative.responseBody = JSON.stringify(body);
});
reject('undeclared positive capability', (x) => {
  const body = JSON.parse(x.controlReceipt.positive.requestBody);
  body.params._meta['io.modelcontextprotocol/clientCapabilities'] = {};
  x.controlReceipt.positive.requestBody = JSON.stringify(body);
});
reject('positive fails', (x) => { x.controlReceipt.positive.httpStatus = 400; });
reject('positive not complete', (x) => {
  const body = JSON.parse(x.controlReceipt.positive.responseBody);
  body.result.resultType = 'input_required';
  x.controlReceipt.positive.responseBody = JSON.stringify(body);
});
reject('control uses wrong tool', (x) => {
  const body = JSON.parse(x.controlReceipt.negative.requestBody);
  body.params.name = 'test_missing_capability';
  x.controlReceipt.negative.requestBody = JSON.stringify(body);
});
reject('control mismatched response ID', (x) => {
  const body = JSON.parse(x.controlReceipt.positive.responseBody);
  body.id = 9999;
  x.controlReceipt.positive.responseBody = JSON.stringify(body);
});
reject('missing first failure', (x) => {
  x.checks = x.checks.filter((check) => check.id !== first);
});
reject('duplicate failure', (x) => {
  x.checks.push(structuredClone(failed(x, first)));
});
reject('first failure becomes skip', (x) => { failed(x, first).status = 'SKIPPED'; });
reject('first failure becomes success', (x) => { failed(x, first).status = 'SUCCESS'; });
reject('second failure becomes success', (x) => { failed(x, second).status = 'SUCCESS'; });
reject('first failure diagnostic changes', (x) => {
  failed(x, first).errorMessage = 'Another failure';
});
reject('first failure error shape changes', (x) => {
  failed(x, first).details.response.error.code = -32021;
});
reject('second failure HTTP status changes', (x) => {
  failed(x, second).details.httpStatus = 500;
});
reject('unexpected failure', (x) => {
  x.checks.push({ id: 'unexpected', status: 'FAILURE' });
});
reject('unaffected success missing', (x) => {
  x.checks.splice(x.checks.findIndex((check) => check.status === 'SUCCESS'), 1);
});
reject('unaffected success duplicated', (x) => {
  x.checks.push(structuredClone(x.checks.find((check) => check.status === 'SUCCESS')));
});
reject('unaffected warning', (x) => {
  x.checks.find((check) => check.status === 'SUCCESS').status = 'WARNING';
});
reject('skip reason changed', (x) => {
  x.checks.find((check) => check.status === 'SKIPPED').details.note = 'changed';
});
reject('wire error added', (x) => {
  x.checks.push({ id: 'wire-schema-harness-error', status: 'FAILURE' });
});
reject('frozen profile weakens success', (x) => {
  x.profile.checks.find((check) => check.id === first).status = 'FAILURE';
});

rawControlArtifactsRejectTampering();

console.log(`P0-C proposal sidecar self-test passed: ${mutationsRejected + 12} mutations rejected.`);

function rawControlArtifactsRejectTampering() {
  const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-p0c-raw-self-test-'));
  const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
  const candidateJarPath = resolve(scratch, 'candidate.jar');
  const classesDirectory = resolve(scratch, 'classes');
  const classPath = resolve(classesDirectory,
    'com/soklet/conformance/McpConformanceFixture.class');
  const controlDirectory = resolve(scratch, 'control');
  const controlPath = resolve(controlDirectory, 'control-receipt.json');
  const sourcePath = resolve(projectRoot,
    'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java');
  const buildScriptPath = resolve(projectRoot,
    'conformance/official/build-public-fixture.sh');
  const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
  const describe = (path) => {
    const bytes = readFileSync(path);
    return { path, bytes: bytes.length, sha256: digest(bytes) };
  };
  const writeJson = (path, value) => writeFileSync(path,
    `${JSON.stringify(value, null, 2)}\n`);
  try {
    mkdirSync(dirname(classPath), { recursive: true });
    mkdirSync(controlDirectory);
    writeFileSync(candidateJarPath, 'candidate bytes');
    writeFileSync(classPath, 'class bytes');
    const classTree = fixtureClassesTreeIdentity(classesDirectory);
    const receipt = structuredClone(controlReceipt);
    receipt.candidateJarSha256 = describe(candidateJarPath).sha256;
    receipt.fixtureSourceSha256 = describe(sourcePath).sha256;
    receipt.fixtureClassesSha256 = classTree.sha256;
    writeJson(controlPath, receipt);
    const exchanges = [];
    for (const name of ['negative', 'positive']) {
      const requestBodyPath = resolve(controlDirectory, `${name}.request-body.json`);
      const responseBodyPath = resolve(controlDirectory, `${name}.response-body.json`);
      const requestHeadersPath = resolve(controlDirectory, `${name}.request-headers.json`);
      const responseHeadersPath = resolve(controlDirectory, `${name}.response-headers.json`);
      writeFileSync(requestBodyPath, receipt[name].requestBody);
      writeFileSync(responseBodyPath, receipt[name].responseBody);
      const host = new URL(receipt.endpoint).host;
      writeJson(requestHeadersPath, {
        Host: host,
        'Content-Type': 'application/json; charset=UTF-8',
        Accept: 'application/json, text/event-stream',
        'MCP-Protocol-Version': protocolVersion,
        'Mcp-Method': 'tools/call',
        'Mcp-Name': receipt.tool,
        'Content-Length': String(Buffer.byteLength(receipt[name].requestBody)),
      });
      writeJson(responseHeadersPath, {
        statusCode: receipt[name].httpStatus,
        headers: {
          'content-length': String(Buffer.byteLength(receipt[name].responseBody)),
          'cache-control': 'no-store',
          'content-type': 'application/json',
        },
        rawHeaders: ['Content-Type', 'application/json'],
      });
      exchanges.push({
        case: name,
        requestBody: describe(requestBodyPath),
        responseBody: describe(responseBodyPath),
        requestHeaders: describe(requestHeadersPath),
        responseHeaders: describe(responseHeadersPath),
        httpStatus: receipt[name].httpStatus,
      });
    }
    const rawManifest = {
      formatVersion: 1,
      runId: receipt.runId,
      endpoint: receipt.endpoint,
      candidateJar: describe(candidateJarPath),
      fixture: {
        sourcePath,
        sourceSha256: describe(sourcePath).sha256,
        classPath,
        classSha256: describe(classPath).sha256,
        classesSha256: classTree.sha256,
        classesFileCount: classTree.fileCount,
        buildScriptSha256: describe(buildScriptPath).sha256,
      },
      exchanges,
    };
    const rawManifestPath = resolve(controlDirectory, 'capture-raw-manifest.json');
    const finalManifestPath = resolve(controlDirectory, 'capture-manifest.json');
    writeJson(rawManifestPath, rawManifest);
    const finalManifest = {
      ...rawManifest,
      process: receipt.process,
      receiptSha256: describe(controlPath).sha256,
    };
    writeJson(finalManifestPath, finalManifest);
    const args = {
      controlPath,
      controlBytes: readFileSync(controlPath),
      controlReceipt: receipt,
      candidateJarPath,
      candidateJarBytes: describe(candidateJarPath).bytes,
      fixtureClassesDirectory: classesDirectory,
      classTree,
    };
    assert.doesNotThrow(() => verifyRawControlArtifacts(args));

    const bodyPath = resolve(controlDirectory, 'negative.response-body.json');
    writeFileSync(bodyPath, 'tampered');
    assert.throws(() => verifyRawControlArtifacts(args), /raw bytes differ/);
    writeFileSync(bodyPath, receipt.negative.responseBody);

    rmSync(resolve(controlDirectory, 'positive.request-body.json'));
    assert.throws(() => verifyRawControlArtifacts(args));
    writeFileSync(resolve(controlDirectory, 'positive.request-body.json'),
      receipt.positive.requestBody);

    writeJson(finalManifestPath, { ...finalManifest,
      receiptSha256: '0'.repeat(64) });
    assert.throws(() => verifyRawControlArtifacts(args), /receipt differs/);
    writeJson(finalManifestPath, finalManifest);

    writeJson(rawManifestPath, { ...rawManifest,
      exchanges: rawManifest.exchanges.slice(0, 1) });
    assert.throws(() => verifyRawControlArtifacts(args), /manifests/);
    writeJson(rawManifestPath, rawManifest);

    rmSync(rawManifestPath);
    assert.throws(() => verifyRawControlArtifacts(args));
    writeJson(rawManifestPath, rawManifest);

    rmSync(finalManifestPath);
    assert.throws(() => verifyRawControlArtifacts(args));

    const resultDirectory = resolve(scratch, 'official/official-results');
    const originalDirectory = resolve(resultDirectory,
      'server-server-stateless-test');
    const originalChecksPath = resolve(originalDirectory, 'checks.json');
    const copiedChecksPath = resolve(scratch, 'official/checks.json');
    mkdirSync(originalDirectory, { recursive: true });
    writeFileSync(originalChecksPath, 'original official checks');
    writeFileSync(copiedChecksPath, 'original official checks');
    assert.equal(verifyOriginalOfficialChecks(resultDirectory, copiedChecksPath),
      digest(Buffer.from('original official checks')));
    writeFileSync(originalChecksPath, 'tampered official checks');
    assert.throws(() => verifyOriginalOfficialChecks(resultDirectory,
      copiedChecksPath), /differs from the original/);
    writeFileSync(originalChecksPath, 'original official checks');
    rmSync(originalChecksPath);
    assert.throws(() => verifyOriginalOfficialChecks(resultDirectory,
      copiedChecksPath), /exactly one official checks/);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}
