#!/usr/bin/env node

import assert from 'node:assert/strict';
import { assertRawChecks, parseReadyLine, selectedRuns, unselectedOfficialScenarios,
  supplementalRequirements, parseOptions } from './run.mjs';

import { sample } from './test-support.mjs';

assert.equal(selectedRuns().length, 41);
assert.equal(selectedRuns('stateless-baseline').length, 28);
assert.equal(selectedRuns('stateless-expansion').length, 4);
assert.equal(selectedRuns('session-enabled').length, 9);
assert.equal(unselectedOfficialScenarios.length, 3);
assert.equal(supplementalRequirements.length, 5);
assert.throws(() => selectedRuns('unreviewed'), /Unreviewed legacy profile/);
const arguments_ = ['--suite-dir', '/suite', '--work-dir', '/work', '--classpath', '/classes:/jar'];
assert.equal(parseOptions(arguments_).mode, 'development');
assert.throws(() => parseOptions([...arguments_, '--mode', 'release']), /all candidate inputs/);
assert.throws(() => parseOptions([...arguments_, '--mode', 'observe']), /development or release/);
assert.throws(() => parseOptions([...arguments_, '--candidate-commit', 'a'.repeat(40)]), /require legacy release mode/);

for (const { profile, revision, scenario } of selectedRuns()) {
    const raw = sample(revision, scenario, profile);
    const accepted = assertRawChecks(revision, scenario, raw, profile);
    assert.equal(accepted.profile, profile);
    assert.equal(accepted.revision, revision);
    assert.equal(accepted.scenario, scenario);
    assert.equal(accepted.checkCount, raw.length);
    assert.throws(() => assertRawChecks(revision, scenario, raw.slice(0, -1), profile),
      /raw official check IDs or statuses changed/);
    assert.throws(() => assertRawChecks(revision, scenario,
      [...raw.slice(0, -1), { ...raw.at(-1), status: 'SKIPPED' }], profile),
    /raw official check IDs or statuses changed/);
    assert.throws(() => assertRawChecks(revision, scenario,
      [...raw, { id: 'unreviewed', status: 'SUCCESS' }], profile),
    /raw official check IDs or statuses changed/);
    if (accepted.wireMessagesValidated > 0) assert.throws(() => assertRawChecks(revision, scenario,
      [...raw.slice(0, -1), { ...raw.at(-1),
        details: { ...raw.at(-1).details, messagesValidated: 0 } }], profile),
    /wire-schema validation changed/);
    if (scenario === 'prompts-list' || scenario === 'resources-list') {
      const field = scenario === 'prompts-list' ? 'promptCount' : 'resourceCount';
      assert.throws(() => assertRawChecks(revision, scenario,
        [{ ...raw[0], details: { [field]: 0 } }, raw[1]], profile),
      /fixture catalog count changed/);
    }
    if (scenario === 'server-initialize' && profile === 'stateless-baseline') {
      assert.throws(() => assertRawChecks(revision, scenario,
        [raw[0], { ...raw[1], details: { message: 'Different INFO' } }, raw[2]], profile),
      /optional-session INFO meaning changed/);
    }
    if (scenario === 'server-initialize' && profile === 'session-enabled')
      assert.throws(() => assertRawChecks(revision, scenario,
        [raw[0], { ...raw[1], details: { sessionId: 'bad\nheader' } }, raw[2]], profile),
      /no valid session ID/);
    if (scenario === 'completion-complete') {
      const broken = structuredClone(raw);
      broken[0].details.result.completion.values = [];
      assert.throws(() => assertRawChecks(revision, scenario, broken, profile), /completion values changed/);
    }
    if (scenario === 'tools-call-with-progress') {
      for (const mutate of [
        (detail) => { detail.progressCount = 2; },
        (detail) => { detail.progressNotifications[1].progress = 1; },
        (detail) => { detail.progressNotifications[1].progressToken = 'foreign'; },
        (detail) => { detail.result.content[0].text = 'Other result'; },
      ]) {
        const broken = structuredClone(raw); mutate(broken[0].details);
        assert.throws(() => assertRawChecks(revision, scenario, broken, profile), /progress or complete result changed/);
      }
    }
    if (scenario === 'server-session-lifecycle') {
      const broken = structuredClone(raw); broken[1].details.statusCode = 405;
      assert.throws(() => assertRawChecks(revision, scenario, broken, profile), /lifecycle HTTP outcomes changed/);
    }
    if (scenario === 'server-sse-multiple-streams') {
      assert.equal(accepted.progressNotificationsObserved, false);
      assert.equal(accepted.wireMessagesValidated, 0);
      const broken = structuredClone(raw); broken[1].details.message = 'SSE worked';
      assert.throws(() => assertRawChecks(revision, scenario, broken, profile), /concurrent-JSON INFO meaning changed/);
    }
}

const validReady = '{"format":1,"event":"ready","host":"127.0.0.1",'
  + '"port":12345,"path":"/mcp","revision":"2025-11-25"}';
assert.equal(parseReadyLine(validReady, '2025-11-25').port, 12345);
const sessionReady = validReady.replace('}', ',"profile":"session-enabled"}');
assert.equal(parseReadyLine(sessionReady, '2025-11-25', 'session-enabled').profile, 'session-enabled');
assert.throws(() => parseReadyLine(sessionReady, '2025-11-25', 'stateless-baseline'), /invalid READY line/);
assert.throws(() => parseReadyLine(validReady, '2025-11-25', 'session-enabled'), /invalid READY line/);
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
  /Unreviewed legacy revision, scenario or profile/);
assert.throws(() => assertRawChecks('2025-06-18', 'server-sse-multiple-streams', [], 'session-enabled'),
  /Unreviewed legacy revision, scenario or profile/);
assert.throws(() => assertRawChecks('2025-11-25', 'resources-subscribe', [], 'stateless-baseline'),
  /Unreviewed legacy revision, scenario or profile/);

console.log('Legacy MCP preparatory runner self-test passed.');
