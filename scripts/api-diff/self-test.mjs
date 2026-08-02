#!/usr/bin/env node

import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  incompatibilityJsonlFromXml,
  readUtf8,
  verifyReviewedSet,
} from './japicmp-symbols.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const fixtures = resolve(scriptDirectory, 'fixtures');

function fixture(name, extension) {
  return readUtf8(resolve(fixtures, `${name}.${extension}`));
}

function assertCurrentMatchesPhase0WhileInventoryEmpty(
  includeTexts,
  phase0RemovalSet,
  currentRemovalSet,
) {
  const inventoryHasMcpApi = includeTexts.some((text) =>
    text.split('\n').some((line) => {
      const trimmed = line.trim();
      return trimmed.length !== 0 && !trimmed.startsWith('#');
    }));
  if (inventoryHasMcpApi) return false;
  assert.equal(currentRemovalSet, phase0RemovalSet);
  return true;
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'));
}

function sharedHostOwnershipSeeds(removalRecords) {
  const removedClassNames = new Set(removalRecords
    .filter((record) => record.kind === 'class')
    .map((record) => record.id.slice('C:'.length)));
  const seeds = [];

  for (const record of removalRecords) {
    let owner;

    if (record.kind === 'class') {
      const binaryName = record.id.slice('C:'.length);
      const nestedTypeSeparator = binaryName.lastIndexOf('$');
      if (nestedTypeSeparator === -1) continue;
      owner = binaryName.slice(0, nestedTypeSeparator);
    } else {
      const memberSeparator = record.id.indexOf('#', 2);
      assert.notEqual(memberSeparator, -1, `Missing owner delimiter in ${record.id}`);
      owner = record.id.slice(2, memberSeparator);
    }

    if (!removedClassNames.has(owner)) seeds.push({ id: record.id, kind: record.kind, owner });
  }

  return seeds;
}

function verifySharedHostRationales(rationaleText, removalRecords) {
  assert.equal(rationaleText.includes('\r'), false,
    'Shared-host rationale inventory must use LF line endings');
  assert.ok(rationaleText.endsWith('\n'),
    'Shared-host rationale inventory must end with LF');
  const lines = rationaleText.slice(0, -1).split('\n');
  assert.equal(lines.length, 60,
    `Shared-host rationale inventory must contain exactly 60 rows, found ${lines.length}`);

  const entries = [];
  const seenIds = new Set();
  const seenRationales = new Set();

  for (const line of lines) {
    assert.notEqual(line, '', 'Shared-host rationale inventory must not contain blank rows');
    const entry = JSON.parse(line);
    assert.deepEqual(Object.keys(entry), ['id', 'rationale'],
      `Shared-host rationale row has unknown, missing, or noncanonical fields: ${line}`);
    assert.equal(JSON.stringify(entry), line,
      `Shared-host rationale row is not canonical compact JSON: ${line}`);
    assert.equal(typeof entry.id, 'string', 'Shared-host rationale ID must be a string');
    assert.equal(typeof entry.rationale, 'string',
      `Shared-host rationale for ${entry.id} must be a string`);
    assert.equal(entry.rationale, entry.rationale.trim(),
      `Shared-host rationale for ${entry.id} has surrounding whitespace`);
    assert.ok(entry.rationale.length >= 32,
      `Shared-host rationale for ${entry.id} must be nonblank and specific`);
    assert.ok(!seenIds.has(entry.id), `Duplicate shared-host rationale ID: ${entry.id}`);
    assert.ok(!seenRationales.has(entry.rationale),
      `Duplicate shared-host rationale text is not symbol-specific: ${entry.id}`);
    seenIds.add(entry.id);
    seenRationales.add(entry.rationale);
    entries.push(entry);
  }

  const actualIds = entries.map((entry) => entry.id);
  assert.deepEqual(actualIds, [...actualIds].sort(bytewiseCompare),
    'Shared-host rationale inventory is not in canonical bytewise ID order');

  const expectedSeeds = sharedHostOwnershipSeeds(removalRecords);
  assert.equal(expectedSeeds.length, 60,
    `Immutable removal evidence must contain exactly 60 shared-host ownership seeds, found ${expectedSeeds.length}`);
  assert.deepEqual(expectedSeeds.filter((seed) => seed.kind === 'class').map((seed) => seed.id), [
    'C:com/soklet/MetricsCollector$McpEndpointRequestOutcomeKey',
    'C:com/soklet/MetricsCollector$McpEndpointSessionTerminationKey',
    'C:com/soklet/MetricsCollector$McpEndpointSseStreamTerminationKey',
  ], 'Shared-host nested record ownership seeds changed without review');
  assert.deepEqual(Object.fromEntries(['class', 'field', 'method'].map((kind) => [
    kind,
    expectedSeeds.filter((seed) => seed.kind === kind).length,
  ])), { class: 3, field: 9, method: 48 },
  'Shared-host ownership seed kind counts changed without review');
  assert.deepEqual([...new Set(expectedSeeds.map((seed) => seed.owner))].sort(bytewiseCompare), [
    'com/soklet/IdGenerator',
    'com/soklet/LifecycleObserver',
    'com/soklet/LogEventType',
    'com/soklet/MetricsCollector',
    'com/soklet/MetricsCollector$Snapshot',
    'com/soklet/MetricsCollector$Snapshot$Builder',
    'com/soklet/ServerType',
    'com/soklet/Simulator',
    'com/soklet/Soklet',
    'com/soklet/SokletConfig',
    'com/soklet/SokletConfig$Builder',
    'com/soklet/SokletConfig$Copier',
    'com/soklet/StreamTerminationReason',
  ], 'Shared-host ownership roots changed without review');
  assert.deepEqual(actualIds, expectedSeeds.map((seed) => seed.id),
    'Shared-host rationale IDs must exactly equal the shared-host ownership seeds');

  return entries;
}

for (const name of ['removals', 'changed-descriptor', 'compatible-removals']) {
  const xml = fixture(name, 'xml');
  const expected = fixture(name, 'jsonl');
  assert.equal(incompatibilityJsonlFromXml(xml), expected);
  assert.doesNotThrow(() => verifyReviewedSet(xml, expected));
}

const removalsXml = fixture('removals', 'xml');
const removals = fixture('removals', 'jsonl');
assert.match(removals, /"id":"C:com\/soklet\/McpServer\$RequestHandler"/);
assert.match(removals, /#<init>\(Ljava\/lang\/String;Ljava\/lang\/String;\)V/);
assert.match(removals, /#values\(\)\[Lcom\/soklet\/McpRequestOutcome;/);
assert.match(removals, /"id":"F:com\/soklet\/McpRequestOutcome#JSON_RPC_ERROR:Lcom\/soklet\/McpRequestOutcome;"/);

const changed = fixture('changed-descriptor', 'jsonl');
assert.match(
  changed,
  /"id":"M:com\/soklet\/McpClientInfo#version\(\)Ljava\/lang\/String;","newId":"M:com\/soklet\/McpClientInfo#version\(\)Ljava\/lang\/Object;"/,
);

const compatibleRemovalsXml = fixture('compatible-removals', 'xml');
const compatibleRemovals = fixture('compatible-removals', 'jsonl');
assert.equal(compatibleRemovals.trimEnd().split('\n').length, 5);
assert.match(
  compatibleRemovals,
  /"id":"M:com\/soklet\/Soklet#handleMcpRequest\(Lcom\/soklet\/Request;\)V".*"binaryCompatible":true,"sourceCompatible":true/,
);
assert.match(
  compatibleRemovals,
  /"id":"M:com\/soklet\/McpRemovedResult#getHttpRequestResult\(\)Lcom\/soklet\/HttpRequestResult;".*"binaryCompatible":true,"sourceCompatible":true/,
);
assert.doesNotMatch(compatibleRemovals, /"id":"C:com\/soklet\/Soklet"/);
const compatibleWithoutProtectedMethod = `${compatibleRemovals
  .trimEnd()
  .split('\n')
  .filter((line) => !line.includes('Soklet#handleMcpRequest'))
  .join('\n')}\n`;
assert.throws(
  () => verifyReviewedSet(compatibleRemovalsXml, compatibleWithoutProtectedMethod),
  /unexpected \(1\).*missing \(0\)/s,
);
const compatibleNonRemoval = JSON.parse(changed.split('\n')[0]);
compatibleNonRemoval.changes[0].binaryCompatible = true;
compatibleNonRemoval.changes[0].sourceCompatible = true;
assert.throws(
  () => verifyReviewedSet(compatibleRemovalsXml, `${JSON.stringify(compatibleNonRemoval)}\n`),
  /compatible but is not the direct class removal/,
);

const noChangesXml = fixture('no-changes', 'xml');
assert.equal(incompatibilityJsonlFromXml(noChangesXml), '');

const phase0RemovalSet = readUtf8(resolve(
  projectRoot,
  'api/mcp/phase-0-incompatibilities.jsonl',
));
const currentRemovalSet = readUtf8(resolve(
  projectRoot,
  'api/mcp/current-incompatibilities.jsonl',
));
const mcpApiIncludeTexts = [
  'phase-4.includes',
  'phase-5.includes',
  'phase-6.includes',
  'provisional.includes',
].map((filename) => readUtf8(resolve(projectRoot, 'api/mcp', filename)));
assert.equal(assertCurrentMatchesPhase0WhileInventoryEmpty(
  mcpApiIncludeTexts,
  phase0RemovalSet,
  currentRemovalSet,
), true);
assert.equal(assertCurrentMatchesPhase0WhileInventoryEmpty(
  ['# phase 4\n', '\n# phase 5\n', '', '   # provisional\n'],
  phase0RemovalSet,
  currentRemovalSet,
), true);
assert.equal(assertCurrentMatchesPhase0WhileInventoryEmpty(
  ['com.soklet.McpFutureType\n', '', '', ''],
  phase0RemovalSet,
  changed,
), false);

const phase0RemovalRecords = phase0RemovalSet
  .trimEnd()
  .split('\n')
  .map((line) => JSON.parse(line));
assert.equal(phase0RemovalRecords.length, 566);
const phase0KindCounts = new Map([
  ['class', 0],
  ['constructor', 0],
  ['field', 0],
  ['method', 0],
]);
for (const record of phase0RemovalRecords) {
  assert.ok(phase0KindCounts.has(record.kind), `Unexpected Phase 0 symbol kind ${record.kind}`);
  phase0KindCounts.set(record.kind, phase0KindCounts.get(record.kind) + 1);
}
assert.deepEqual(Object.fromEntries(phase0KindCounts), {
  class: 75,
  constructor: 23,
  field: 38,
  method: 430,
});

const sharedHostRationaleText = readUtf8(resolve(
  projectRoot,
  'api/mcp/phase-0-shared-host-rationales.jsonl',
));
assert.doesNotThrow(() => verifySharedHostRationales(sharedHostRationaleText, phase0RemovalRecords));
const sharedHostRationaleLines = sharedHostRationaleText.trimEnd().split('\n');
assert.throws(
  () => verifySharedHostRationales(`${sharedHostRationaleLines.slice(1).join('\n')}\n`, phase0RemovalRecords),
  /exactly 60 rows/,
);
assert.throws(
  () => verifySharedHostRationales(`${sharedHostRationaleLines.join('\n')}\n` +
    '{"id":"M:com/soklet/Zzz#legacy()V","rationale":"Unexpected ownership expansion must fail closed during review."}\n',
  phase0RemovalRecords),
  /exactly 60 rows/,
);
const duplicateSharedHostRationaleLines = [...sharedHostRationaleLines];
duplicateSharedHostRationaleLines[1] = duplicateSharedHostRationaleLines[0];
assert.throws(
  () => verifySharedHostRationales(`${duplicateSharedHostRationaleLines.join('\n')}\n`, phase0RemovalRecords),
  /Duplicate shared-host rationale ID/,
);
const blankSharedHostRationale = JSON.parse(sharedHostRationaleLines[0]);
blankSharedHostRationale.rationale = '';
assert.throws(
  () => verifySharedHostRationales(
    `${[JSON.stringify(blankSharedHostRationale), ...sharedHostRationaleLines.slice(1)].join('\n')}\n`,
    phase0RemovalRecords,
  ),
  /must be nonblank and specific/,
);
assert.throws(
  () => verifySharedHostRationales(`${[...sharedHostRationaleLines].reverse().join('\n')}\n`,
    phase0RemovalRecords),
  /not in canonical bytewise ID order/,
);

const directRemovalByKind = new Map([
  ['class', { type: 'CLASS_REMOVED', site: 'class' }],
  ['constructor', { type: 'CONSTRUCTOR_REMOVED', site: 'constructor' }],
  ['field', { type: 'FIELD_REMOVED', site: 'field' }],
  ['method', { type: 'METHOD_REMOVED', site: 'method' }],
]);
const compatibleDirectRemovals = [];
for (const record of phase0RemovalRecords) {
  for (const change of record.changes) {
    if (!change.binaryCompatible || !change.sourceCompatible) continue;
    const expected = directRemovalByKind.get(record.kind);
    assert.deepEqual(
      { type: change.type, site: change.site },
      expected,
      `Compatible Phase 0 change on ${record.id} is not its direct symbol removal`,
    );
    compatibleDirectRemovals.push(record.id);
  }
}
assert.deepEqual(compatibleDirectRemovals, [
  'M:com/soklet/McpRequestResult$ResponseCompleted#getHttpRequestResult()Lcom/soklet/HttpRequestResult;',
  'M:com/soklet/McpRequestResult$StreamOpened#getHttpRequestResult()Lcom/soklet/HttpRequestResult;',
  'M:com/soklet/Soklet#handleMcpRequest(Lcom/soklet/Request;Ljava/util/function/Consumer;)V',
  'M:com/soklet/Soklet#handleSimulatedMcpStreamDisconnect(Lcom/soklet/Request;Ljava/lang/String;)V',
]);

assert.throws(
  () => verifyReviewedSet(removalsXml, ''),
  /unexpected \(16\).*missing \(0\)/s,
);
assert.throws(
  () => verifyReviewedSet(noChangesXml, removals),
  /unexpected \(0\).*missing \(16\)/s,
);

const reversed = `${removals.trimEnd().split('\n').reverse().join('\n')}\n`;
assert.throws(
  () => verifyReviewedSet(removalsXml, reversed),
  /not in canonical bytewise-sorted form/,
);
assert.throws(
  () => incompatibilityJsonlFromXml(
    removalsXml.replace('<classes>', '<classes futureAttribute="unsupported">'),
  ),
  /Unknown attribute futureAttribute/,
);
assert.throws(
  () => incompatibilityJsonlFromXml(
    removalsXml.replace(' accessModifier="PROTECTED"', ' accessModifier="PROTECTED" accessModifier="PUBLIC"'),
  ),
  /Duplicate attribute accessModifier/,
);
assert.throws(
  () => incompatibilityJsonlFromXml(
    removalsXml.replace(' packagesInclude="all"', ' packagesInclude="com.soklet.*"'),
  ),
  /complete public\/protected comparison contract/,
);
assert.throws(
  () => incompatibilityJsonlFromXml(
    removalsXml.replace(' name="version"', ' name="bad<name"'),
  ),
  /Raw '<' in XML attribute/,
);

const duplicateClassStart = removalsXml.indexOf(
  '<class binaryCompatible="false" changeStatus="REMOVED" fullyQualifiedName="com.soklet.McpClientInfo"',
);
assert.notEqual(duplicateClassStart, -1);
const duplicateClassEnd = removalsXml.indexOf('</class>', duplicateClassStart) + '</class>'.length;
const duplicateClass = removalsXml.slice(duplicateClassStart, duplicateClassEnd);
assert.throws(
  () => incompatibilityJsonlFromXml(
    removalsXml.replace('</classes>', `${duplicateClass}</classes>`),
  ),
  /Ambiguous duplicate symbol identity/,
);

const reviewedWithUnknownChangeField = JSON.parse(removals.split('\n')[0]);
reviewedWithUnknownChangeField.changes[0].future = true;
assert.throws(
  () => verifyReviewedSet(removalsXml, `${JSON.stringify(reviewedWithUnknownChangeField)}\n`),
  /unknown or missing field/,
);

process.stdout.write('API-diff parser self-tests passed\n');
