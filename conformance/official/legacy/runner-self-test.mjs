#!/usr/bin/env node

import assert from 'node:assert/strict';
import { assertRawChecks, parseReadyLine, revisions, scenarios } from './run.mjs';

const INFO = 'Server did not provide an MCP-Session-Id header (session ID is optional)';

for (const revision of revisions) {
  for (const scenario of scenarios) {
    const raw = sample(revision, scenario);
    const accepted = assertRawChecks(revision, scenario, raw);
    assert.equal(accepted.revision, revision);
    assert.equal(accepted.scenario, scenario);
    assert.equal(accepted.checkCount, raw.length);
    assert.throws(() => assertRawChecks(revision, scenario, raw.slice(0, -1)),
      /raw official check IDs or statuses changed/);
    assert.throws(() => assertRawChecks(revision, scenario,
      [...raw.slice(0, -1), { ...raw.at(-1), status: 'SKIPPED' }]),
    /raw official check IDs or statuses changed/);
    assert.throws(() => assertRawChecks(revision, scenario,
      [...raw, { id: 'unreviewed', status: 'SUCCESS' }]),
    /raw official check IDs or statuses changed/);
    assert.throws(() => assertRawChecks(revision, scenario,
      [...raw.slice(0, -1), { ...raw.at(-1),
        details: { ...raw.at(-1).details, messagesValidated: 0 } }]),
    /wire-schema validation changed/);
    if (scenario === 'prompts-list' || scenario === 'resources-list') {
      const field = scenario === 'prompts-list' ? 'promptCount' : 'resourceCount';
      assert.throws(() => assertRawChecks(revision, scenario,
        [{ ...raw[0], details: { [field]: 0 } }, raw[1]]),
      /fixture catalog count changed/);
    }
    if (scenario === 'server-initialize') {
      assert.throws(() => assertRawChecks(revision, scenario,
        [raw[0], { ...raw[1], details: { message: 'Different INFO' } }, raw[2]]),
      /optional-session INFO meaning changed/);
    }
  }
}

const validReady = '{"format":1,"event":"ready","host":"127.0.0.1",'
  + '"port":12345,"path":"/mcp","revision":"2025-11-25"}';
assert.equal(parseReadyLine(validReady, '2025-11-25').port, 12345);
for (const broken of [
  validReady.replace('2025-11-25', '2026-07-28'),
  validReady.replace('127.0.0.1', '0.0.0.0'),
  validReady.replace('12345', '0'),
  validReady.replace('/mcp', '/wrong'),
  validReady.replace('"format":1,', ''),
]) assert.throws(() => parseReadyLine(broken, '2025-11-25'),
  /invalid READY line/);
assert.throws(() => parseReadyLine('not-json', '2025-11-25'),
  /not JSON/);
assert.throws(() => assertRawChecks('2025-03-26', 'ping', sample('2025-06-18', 'ping')),
  /Unreviewed legacy revision or scenario/);

console.log('Legacy MCP preparatory runner self-test passed.');

function sample(revision, scenario) {
  const checks = scenario === 'server-initialize'
    ? [check('server-initialize'),
      { id: 'server-session-id-visible-ascii', status: 'INFO',
        details: { message: INFO } }]
    : scenario === 'tools-list'
      ? revision === '2025-11-25'
        ? [check('tools-list'), check('tools-name-format')]
        : [check('tools-list')]
      : [check(scenario)];
  if (scenario === 'prompts-list') checks[0].details = { promptCount: 4 };
  if (scenario === 'resources-list') checks[0].details = { resourceCount: 2 };
  checks.push({ id: 'wire-schema-valid', status: 'SUCCESS',
    details: { messagesValidated: scenario === 'server-initialize' ? 3 : 5,
      violations: [] } });
  return checks;
}

function check(id) {
  return { id, status: 'SUCCESS' };
}
