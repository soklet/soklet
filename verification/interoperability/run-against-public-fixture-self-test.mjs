#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  parseFixtureArguments,
  parseControl,
  validateClientOutput,
} from './run-against-public-fixture.mjs';

const protocolVersion = '2026-07-28';
assert.deepEqual(parseFixtureArguments(['built.jar', 'client-dir', 'go', './client']), {
  candidateJar: 'built.jar', clientWorkingDirectory: 'client-dir', clientName: 'go',
  clientCommand: './client', clientArguments: [],
});
assert.deepEqual(parseFixtureArguments(['built.jar', 'client-dir', 'typescript', 'node', 'client.mjs']).clientArguments, ['client.mjs']);
assert.throws(() => parseFixtureArguments(['built.jar', 'client-dir', 'go']), /Usage:/);
assert.throws(() => parseFixtureArguments(['built.jar', 'client-dir', 'unknown', './client']), /Unsupported interoperability client/);
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

}

assert.throws(
  () => validateClientOutput(
    `SOKLET_INTEROP_PASS ${protocolVersion} python\n`,
    '',
    'python',
  ),
  /Unsupported interoperability client/,
);

console.log('Interoperability runner self-test passed.');
