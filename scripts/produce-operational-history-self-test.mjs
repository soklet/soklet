#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonicalJson, verifyReleaseHarnessConfiguration } from './import-release-harness-evidence.mjs';
import {
  OperationalHistoryProductionError,
  operationalObservationFromTranscripts,
} from './produce-operational-history.mjs';

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const SCRIPT_PATH = resolve(SCRIPT_DIRECTORY, 'produce-operational-history.mjs');

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function clone(value) {
  return structuredClone(value);
}

function resource(group, index, lastIndex) {
  const last = index === lastIndex;
  if (group === 'http') {
    return {
      fileDescriptors: 20 + (last ? 1 : 0),
      heapBytes: 10_000_000 + (last ? 1_000_000 : 0),
      liveThreads: 10 + (last ? 2 : 0),
    };
  }
  return {
    fileDescriptors: 30 + (last ? 2 : 0),
    heapBytes: 20_000_000 + (last ? 2_000_000 : 0),
    liveThreads: 20 + (last ? 3 : 0),
  };
}

function transcript(contract, group, startMilliseconds) {
  const policyHash = sha256(Buffer.from(canonicalJson(contract.policy), 'utf8'));
  const requiredSpan = contract.policy.durationSeconds
    + contract.policy.postIntervalReserveSeconds;
  const sampleCount = Math.floor(requiredSpan / contract.policy.cadenceSeconds) + 1;
  const expectedOperations = contract.policy.loadShape.clientsPerScenario
    * contract.policy.loadShape.operationsPerClientPerSecond
    * contract.policy.loadShape.secondsPerScenario;
  const scenarioIds = group === 'http' ? ['http'] : ['mcp', 'realtime'];
  const samples = Array.from({ length: sampleCount }, (_, index) => ({
    at: new Date(startMilliseconds + index * contract.policy.cadenceSeconds * 1000)
      .toISOString().replace('.000Z', 'Z'),
    droppedLogRecords: 0,
    frameworkMetricCardinality: 0,
    rejectedMetricDeliveries: 0,
    resources: resource(group, index, sampleCount - 1),
    unregisteredMetricDimensions: 0,
  }));
  return {
    drainSeconds: 0.125,
    droppedLogRecords: 0,
    finishedAt: samples.at(-1).at,
    formatVersion: 1,
    frameworkMetricCardinality: 0,
    group,
    logRecordsObserved: group === 'http' ? 0 : expectedOperations,
    metricEventsObserved: group === 'http' ? 0 : expectedOperations,
    metricSamplesObserved: sampleCount * 3,
    outcomes: [],
    policySha256: policyHash,
    rejectedMetricDeliveries: 0,
    samples,
    scenarios: scenarioIds.map((id) => ({
      expectedOperations,
      id,
      successfulOperations: expectedOperations,
      uniqueAdversarialDimensionValues:
        contract.policy.loadShape.uniqueAdversarialDimensionValuesPerScenario,
    })),
    sensitiveCanaries: 0,
    startedAt: samples[0].at,
    terminalFrameworkCardinality: 0,
    unregisteredMetricDimensions: 0,
  };
}

function expectFailure(operation, pattern) {
  assert.throws(operation, (error) => {
    assert.ok(error instanceof OperationalHistoryProductionError);
    assert.match(error.message, pattern);
    return true;
  });
}

function run() {
  const configuration = verifyReleaseHarnessConfiguration();
  const contract = configuration.contracts.get('operational-history');
  assert.ok(contract !== undefined);
  const requiredSeconds = contract.policy.durationSeconds
    + contract.policy.postIntervalReserveSeconds;
  const elapsedNanoseconds = BigInt(requiredSeconds) * 1_000_000_000n;
  const http = transcript(contract, 'http', Date.UTC(2026, 7, 31, 0, 0, 0));
  const mcp = transcript(contract, 'mcpAndRealtime', Date.UTC(2026, 7, 31, 0, 0, 1));

  const observation = operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: mcp,
  });
  assert.equal(observation.samples.length, 4_441);
  assert.deepEqual(observation.resourceBaselines, {
    http: resource('http', 0, 4_440),
    mcpAndRealtime: resource('mcpAndRealtime', 0, 4_440),
  });
  assert.deepEqual(observation.finalResourceDeltas, {
    http: { fileDescriptors: 1, heapBytes: 1_000_000, liveThreads: 2 },
    mcpAndRealtime: { fileDescriptors: 2, heapBytes: 2_000_000, liveThreads: 3 },
  });
  assert.equal(observation.samples[0].at, '2026-08-31T00:00:01Z');
  assert.equal(observation.samples.at(-1).at, '2026-08-31T06:10:01Z');

  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds: elapsedNanoseconds - 1n,
    httpTranscript: http,
    mcpAndRealtimeTranscript: mcp,
  }), /registered wall-clock interval/u);

  const missingSample = clone(http);
  missingSample.samples.pop();
  missingSample.finishedAt = missingSample.samples.at(-1).at;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: missingSample,
    mcpAndRealtimeTranscript: mcp,
  }), /exactly 4441/u);

  const gap = clone(http);
  gap.samples[20].at = new Date(Date.parse(gap.samples[19].at) + 16_000)
    .toISOString().replace('.000Z', 'Z');
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: gap,
    mcpAndRealtimeTranscript: mcp,
  }), /excessive sample gap/u);

  const incompleteLoad = clone(mcp);
  incompleteLoad.scenarios[0].successfulOperations--;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: incompleteLoad,
  }), /registered load shape/u);

  const missingCanary = clone(mcp);
  missingCanary.scenarios[1].uniqueAdversarialDimensionValues--;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: missingCanary,
  }), /registered load shape/u);

  const droppedLog = clone(mcp);
  droppedLog.samples[100].droppedLogRecords = 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: droppedLog,
  }), /zero-tolerance observation/u);

  const terminalDrop = clone(mcp);
  terminalDrop.droppedLogRecords = 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: terminalDrop,
  }), /terminal zero-tolerance observation/u);

  const cardinality = clone(http);
  cardinality.samples[100].frameworkMetricCardinality = 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: cardinality,
    mcpAndRealtimeTranscript: mcp,
  }), /zero-tolerance observation/u);

  const excessiveResource = clone(http);
  excessiveResource.samples.at(-1).resources.heapBytes =
    excessiveResource.samples[0].resources.heapBytes
      + contract.policy.finalResourceDeltas.http.heapBytes + 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: excessiveResource,
    mcpAndRealtimeTranscript: mcp,
  }), /resource delta exceeds policy/u);

  const emptyTelemetry = clone(mcp);
  emptyTelemetry.metricEventsObserved = 0;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: emptyTelemetry,
  }), /exact semantic MCP metric and trace-log delivery/u);

  const extraMetricEvent = clone(mcp);
  extraMetricEvent.metricEventsObserved++;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: extraMetricEvent,
  }), /exact semantic MCP metric and trace-log delivery/u);

  const extraTraceLog = clone(mcp);
  extraTraceLog.logRecordsObserved++;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: extraTraceLog,
  }), /exact semantic MCP metric and trace-log delivery/u);

  const excessiveDrain = clone(mcp);
  excessiveDrain.drainSeconds = contract.policy.drainMaximumSeconds + 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: http,
    mcpAndRealtimeTranscript: excessiveDrain,
  }), /exceeded the registered drain maximum/u);

  const crossGroupTelemetry = clone(http);
  crossGroupTelemetry.metricEventsObserved = 1;
  expectFailure(() => operationalObservationFromTranscripts({
    contract,
    elapsedNanoseconds,
    httpTranscript: crossGroupTelemetry,
    mcpAndRealtimeTranscript: mcp,
  }), /MCP-only semantic telemetry/u);

  const bypass = spawnSync(process.execPath, [
    SCRIPT_PATH,
    'run',
    '--duration-seconds',
    '1',
  ], { encoding: 'utf8' });
  assert.equal(bypass.status, 1);
  assert.match(bypass.stderr, /Unexpected operational producer arguments: --duration-seconds/u);

  console.log('produce-operational-history self-test PASS assertions=27');
}

run();
