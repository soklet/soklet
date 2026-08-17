#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  interoperabilityEvidenceLine,
  parseControl,
  validateClientOutput,
} from './run-against-public-fixture.mjs';

const protocolVersion = '2026-07-28';
const sdkPins = {
  go: {
    artifactChecksum: 'h1:yqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44=',
    artifactIdentity: 'github.com/modelcontextprotocol/go-sdk@v1.7.0',
    commit: 'bc72835f62eb94d0fb484439f886b6885b075f36',
  },
  typescript: {
    artifactChecksum: 'sha512-8f1OghQ2rjzIOfqgUCP+8GiUWqRs89njoWLNqAe8kWmDePv3s1fZXseej+QXemssEuuOvLLmLO/kqM3IQHtISw==',
    artifactIdentity: 'npm:@modelcontextprotocol/client@2.0.0',
    commit: 'cc4b41617ce3601b1290d67216ea0b194a3cd9ac',
  },
};
const ready = parseControl(
  '{"format":1,"event":"ready","host":"127.0.0.1","port":12345,"path":"/mcp"}',
  'ready',
);
assert.equal(ready.port, 12345);
assert.deepEqual(
  parseControl('{"format":1,"event":"stopped","clean":true}', 'stopped'),
  { format: 1, event: 'stopped', clean: true },
);
assert.throws(
  () => parseControl(
    '{"format":1,"event":"ready","host":"127.0.0.1","port":12345,"path":"/mcp","ignored":true}',
    'ready',
  ),
  /unexpected ready control keys/,
);
assert.throws(
  () => parseControl('[]', 'ready'),
  /non-object ready control line/,
);
assert.throws(
  () => parseControl('{"format":1,"event":"stopped","clean":true}', 'ready'),
  /unexpected ready control keys|unexpected ready control line/,
);

for (const client of ['go', 'typescript']) {
  const marker = `SOKLET_INTEROP_PASS ${protocolVersion} ${client}\n`;
  assert.equal(validateClientOutput(marker, '', client), marker);
  assert.throws(
    () => validateClientOutput(`${marker}forged trailing output\n`, '', client),
    /not the exact success marker/,
  );
  assert.throws(
    () => validateClientOutput(marker, 'warning\n', client),
    /unexpected stderr/,
  );

  const line = interoperabilityEvidenceLine('a'.repeat(64), client, sdkPins[client]);
  assert.ok(line.startsWith('SOKLET_INTEROP_EVIDENCE '));
  const receipt = JSON.parse(line.slice('SOKLET_INTEROP_EVIDENCE '.length));
  assert.deepEqual(receipt, {
    candidateSha256: 'a'.repeat(64),
    client,
    fixtureScenario: 'tools-list',
    fixtureShutdown: 'CLEAN',
    formatVersion: 1,
    protocolVersion,
    sdkArtifactChecksum: sdkPins[client].artifactChecksum,
    sdkArtifactIdentity: sdkPins[client].artifactIdentity,
    sdkCommit: sdkPins[client].commit,
    tool: 'test_simple_text',
  });
}

assert.throws(
  () => interoperabilityEvidenceLine('A'.repeat(64), 'go', sdkPins.go),
  /64 lowercase hexadecimal/,
);
assert.throws(
  () => interoperabilityEvidenceLine(
    'a'.repeat(64),
    'go',
    { ...sdkPins.go, artifactChecksum: sdkPins.typescript.artifactChecksum },
  ),
  /Invalid go SDK artifact or commit pin/,
);
assert.throws(
  () => validateClientOutput(
    `SOKLET_INTEROP_PASS ${protocolVersion} python\n`,
    '',
    'python',
  ),
  /Unsupported interoperability client/,
);

console.log('Interoperability runner self-test passed.');
