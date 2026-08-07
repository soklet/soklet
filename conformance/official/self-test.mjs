#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  cpSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { adjudicateChecks } from './adjudicate.mjs';
import { validateFinalTagWire } from './validate-final-tag-wire.mjs';
import {
	activeScenarios,
	inventoryBytes,
  officialScenarioArguments,
  parseOfficialScenarioList,
  readCanonicalJson,
  sha256,
  verifyListedInventory,
  verifyManifestSet,
  verifyOfficialSuite,
} from './verify.mjs';

if (process.argv.length !== 4 || process.argv[2] !== '--suite-dir') {
  console.error('Usage: node conformance/official/self-test.mjs --suite-dir <built-suite>');
  process.exit(64);
}

const officialRoot = resolve(dirname(fileURLToPath(import.meta.url)));
const suiteDirectory = resolve(process.argv[3]);
const scratch = mkdtempSync(resolve(tmpdir(), 'soklet-mcp-conformance-self-test-'));

try {
	const manifests = verifyManifestSet();
	assert.equal(manifests.selection.currentImplementationPhase, 4);
	assert.equal(activeScenarios(manifests.selection, 4).length, 23);
	assert.equal(manifests.expectedChecks.profiles.length, 23);
  const syntheticListing = 'Server scenarios (test against a server):\n'
    + manifests.selection.scenarios
      .map((scenario) => `  - ${scenario.name} [2026-07-28]\n`)
      .join('');
  assert.deepEqual(
    parseOfficialScenarioList(syntheticListing),
    manifests.selection.scenarios.map((scenario) => scenario.name),
  );
  assert.equal(
    sha256(inventoryBytes(parseOfficialScenarioList(syntheticListing))),
    manifests.pins.scenarioInventory.fullInventorySha256,
  );
  assert.doesNotThrow(() => verifyListedInventory(
    syntheticListing, manifests.selection, manifests.pins,
  ));
  assert.deepEqual(officialScenarioArguments(manifests.pins, {
    fixtureUrl: 'http://127.0.0.1:12345/mcp',
    scenarioName: 'dns-rebinding-protection',
    outputDirectory: '/tmp/results',
  }), [
    'server', '--url', 'http://127.0.0.1:12345/mcp',
    '--scenario', 'dns-rebinding-protection',
    '--spec-version', '2026-07-28', '-o', '/tmp/results', '--verbose',
  ]);
  for (const invalid of [
    syntheticListing.replace('\n  - server-stateless', '\nextra\n  - server-stateless'),
    syntheticListing.replace('server-stateless', 'server_stateless'),
    syntheticListing.replace(/\n$/, ''),
    syntheticListing.replace('\n', '\r\n'),
  ]) assert.throws(() => parseOfficialScenarioList(invalid));

	const profile = manifests.expectedChecks.profiles.find(
		(candidate) => candidate.id === 'dns-rebinding-protection.phase3.v1',
	);
	assert.notEqual(profile, undefined);
  const validChecks = [
    { id: 'localhost-host-rebinding-rejected', status: 'SUCCESS' },
    { id: 'localhost-host-valid-accepted', status: 'SUCCESS' },
  ];
  assert.doesNotThrow(() => adjudicateChecks(profile.scenario, validChecks, profile));
  assert.throws(
    () => adjudicateChecks(profile.scenario, validChecks.slice(0, 1), profile),
    /multiset mismatch/,
  );
  assert.throws(
    () => adjudicateChecks(profile.scenario, [
      ...validChecks,
      { id: 'unexpected', status: 'SUCCESS' },
    ], profile),
    /multiset mismatch/,
  );
  assert.throws(
    () => adjudicateChecks(profile.scenario, [
      { id: 'localhost-host-rebinding-rejected', status: 'WARNING' },
      validChecks[1],
    ], profile),
    /forbidden WARNING/,
  );
  assert.throws(
    () => adjudicateChecks(profile.scenario, [
      ...validChecks,
      { id: 'wire-schema-harness-error', status: 'FAILURE' },
    ], profile),
    /forbidden FAILURE|wire-schema-harness-error/,
  );

  const validResult = validateFinalTagWire({ suiteDirectory });
  const goldenManifest = readCanonicalJson(
    resolve(officialRoot, 'golden-wire/manifest.json'),
  );
  assert.equal(validResult.validated.length, goldenManifest.fixtures.length);
  assert.equal(validResult.ajvVersion, '8.20.0');

  const builtMutation = resolve(scratch, 'suite-built-entry-mutation');
  cpSync(suiteDirectory, builtMutation, {
    recursive: true,
    filter: (source) => !/[/\\](?:\.git|node_modules)(?:[/\\]|$)/.test(source),
  });
  const builtEntryPath = resolve(
    builtMutation, manifests.pins.officialConformanceSuite.entryPoint,
  );
  const builtEntryBytes = readFileSync(builtEntryPath);
  builtEntryBytes[0] ^= 1;
  writeFileSync(builtEntryPath, builtEntryBytes);
  assert.throws(
    () => verifyOfficialSuite(builtMutation, manifests.pins),
    /built entry point checksum differs/,
  );

  let copy = copyOfficialRoot('schema-mutation');
  const schemaPath = resolve(copy, 'final-schema/schema.json');
  const schemaBytes = readFileSync(schemaPath);
  schemaBytes[0] ^= 1;
  writeFileSync(schemaPath, schemaBytes);
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /final tagged schema does not match/,
  );

  copy = copyOfficialRoot('missing-result-type');
  rewriteFixture(copy, 'phase-3/discover-response.json', (value) => {
    delete value.result.resultType;
    return value;
  });
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /failed #\/\$defs\/DiscoverResultResponse/,
  );

  copy = copyOfficialRoot('unknown-definition');
  rewriteGoldenManifest(copy, (manifest) => {
    manifest.fixtures[0].schemaRef = '#/$defs/DoesNotExist';
  });
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /unknown definition/,
  );

  copy = copyOfficialRoot('missing-subscription-id');
  rewriteFixture(copy, 'schema-canaries/subscription-listen-terminal.json', (value) => {
    delete value.result._meta['io.modelcontextprotocol/subscriptionId'];
    return value;
  });
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /failed #\/\$defs\/SubscriptionsListenResultResponse/,
  );

  copy = copyOfficialRoot('unmanifested');
  writeFileSync(resolve(copy, 'golden-wire/unmanifested.json'), '{}\n');
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /unmanifested/,
  );

  copy = copyOfficialRoot('unmanifested-non-json');
  writeFileSync(resolve(copy, 'golden-wire/unmanifested.txt'), 'not a fixture\n');
  assert.throws(
    () => validateFinalTagWire({ suiteDirectory, root: copy }),
    /unmanifested/,
  );

  copy = copyOfficialRoot('dependency-pin');
  const pinPath = resolve(copy, 'upstream-pins.json');
  const pins = readCanonicalJson(pinPath);
  pins.officialConformanceSuite.lockedSchemaDependencies.ajv = '8.19.0';
  writeFileSync(pinPath, `${JSON.stringify(pins, null, 2)}\n`);
  assert.throws(() => validateFinalTagWire({ suiteDirectory, root: copy }), /pin differs/);

  for (const [name, mutate] of [
    ['suite-repository-pin', (value) => {
      value.officialConformanceSuite.repository = 'https://example.invalid/conformance.git';
    }],
    ['suite-entry-point-pin', (value) => {
      value.officialConformanceSuite.entryPoint = 'dist/other.js';
    }],
    ['suite-built-entry-point-pin', (value) => {
      value.officialConformanceSuite.builtEntryPoint.sha256 = '0'.repeat(64);
    }],
    ['suite-command-template-pin', (value) => {
      value.officialConformanceSuite.scenarioCommandArguments[7] = '--output-dir';
    }],
    ['suite-source-algorithm-pin', (value) => {
      value.officialConformanceSuite.sourceTree.algorithm = 'different';
    }],
    ['suite-schema-provenance-pin', (value) => {
      value.officialConformanceSuite.vendoredProtocolSchema.path = 'other.json';
    }],
    ['node-distribution-pin', (value) => {
      value.toolchain.nodeDistribution.linuxX64Artifact = 'node-other.tar.xz';
    }],
  ]) {
    const pinCopy = copyOfficialRoot(name);
    const mutatedPinPath = resolve(pinCopy, 'upstream-pins.json');
    const mutatedPins = readCanonicalJson(mutatedPinPath);
    mutate(mutatedPins);
    writeFileSync(mutatedPinPath, `${JSON.stringify(mutatedPins, null, 2)}\n`);
    assert.throws(() => verifyManifestSet(pinCopy), /pin differs/);
  }

  console.log('Official MCP conformance infrastructure self-test passed.');
} finally {
  rmSync(scratch, { recursive: true, force: true });
}

function copyOfficialRoot(name) {
  const destination = resolve(scratch, name);
  cpSync(officialRoot, destination, {
    recursive: true,
    filter: (source) => !source.includes('/target/'),
  });
  return destination;
}

function rewriteFixture(root, path, transform) {
  const fixturePath = resolve(root, 'golden-wire', path);
  const value = transform(JSON.parse(readFileSync(fixturePath, 'utf8')));
  const bytes = Buffer.from(`${JSON.stringify(value)}\n`, 'utf8');
  writeFileSync(fixturePath, bytes);
  rewriteGoldenManifest(root, (manifest) => {
    const fixture = manifest.fixtures.find((candidate) => candidate.path === path);
    assert.notEqual(fixture, undefined);
    fixture.sha256 = sha256(bytes);
  });
}

function rewriteGoldenManifest(root, transform) {
  const manifestPath = resolve(root, 'golden-wire/manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  transform(manifest);
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
}
