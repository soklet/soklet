#!/usr/bin/env node

import assert from 'node:assert/strict';

import {
  ELIGIBLE_INTERFACES,
  METADATA_PARAMETER,
  SIGNATURE_DIRECTORY,
  SIGNATURE_PHASE_INVENTORY,
  deriveMetadataBuilders,
  verifyMetadataBuilders,
} from './verify-mcp-metadata-builders.mjs';

function internalName(owner) {
  return owner.replaceAll('.', '/');
}

function recordsFor(owner, family) {
  const internalOwner = internalName(owner);
  const builder = `${internalOwner}$Builder`;
  return [
    {
      id: `C:${internalOwner}`,
      kind: 'class',
      api: { interfaces: [family], modifiers: ['PUBLIC'] },
    },
    {
      id: `C:${builder}`,
      kind: 'class',
      api: { interfaces: [], modifiers: ['PUBLIC'] },
    },
    {
      id: `M:${builder}#metadata(Lcom/soklet/McpJsonObject;)L${builder};`,
      kind: 'method',
      api: {
        modifiers: ['PUBLIC'],
        parameters: [{ type: METADATA_PARAMETER }],
        returnType: { type: `${owner}$Builder` },
      },
    },
  ];
}

function baseSignaturePhases() {
  return [{
    phase: 4,
    records: [
      ...recordsFor('com.soklet.McpTextContent', 'com.soklet.McpContentBlock'),
      ...recordsFor('com.soklet.McpTextResourceContents',
        'com.soklet.McpResourceContents'),
    ],
  }];
}

function baseInventory(signaturePhases = baseSignaturePhases()) {
  return {
    builders: deriveMetadataBuilders(signaturePhases),
    derivation: {
      eligibleInterfaces: [...ELIGIBLE_INTERFACES],
      metadataParameter: METADATA_PARAMETER,
      signatureDirectory: SIGNATURE_DIRECTORY,
      signaturePhaseInventory: SIGNATURE_PHASE_INVENTORY,
    },
    formatVersion: 1,
  };
}

function verify(inventory, signaturePhases = baseSignaturePhases()) {
  return verifyMetadataBuilders({
    inventory,
    phases: [4],
    signaturePhases,
  });
}

function runCase(name, body) {
  body();
  process.stdout.write(`PASS ${name}\n`);
}

runCase('positive exact derivation', () => {
  assert.equal(verify(baseInventory()).builders, 2);
});

runCase('omitted inventory builder', () => {
  const inventory = baseInventory();
  inventory.builders.pop();
  assert.throws(() => verify(inventory), /inventory set differs/u);
});

runCase('extra inventory builder', () => {
  const inventory = baseInventory();
  inventory.builders.push({
    ...inventory.builders.at(-1),
    builder: 'com.soklet.McpVideoContent$Builder',
    metadataMethod: 'M:com/soklet/McpVideoContent$Builder#metadata(Lcom/soklet/McpJsonObject;)Lcom/soklet/McpVideoContent$Builder;',
    owner: 'com.soklet.McpVideoContent',
  });
  assert.throws(() => verify(inventory), /inventory set differs/u);
});

runCase('new eligible signature builder', () => {
  const signaturePhases = baseSignaturePhases();
  const inventory = baseInventory(signaturePhases);
  signaturePhases[0].records.push(
    ...recordsFor('com.soklet.McpImageContent', 'com.soklet.McpContentBlock'),
  );
  assert.throws(() => verify(inventory, signaturePhases), /inventory set differs/u);
});

runCase('missing required metadata method', () => {
  const signaturePhases = baseSignaturePhases();
  const inventory = baseInventory(signaturePhases);
  signaturePhases[0].records = signaturePhases[0].records.filter(
    (record) => !record.id.includes('McpTextResourceContents$Builder#metadata'),
  );
  assert.throws(() => verify(inventory, signaturePhases), /inventory set differs/u);
});

runCase('duplicate inventory identity', () => {
  const inventory = baseInventory();
  inventory.builders.splice(1, 0, { ...inventory.builders[0] });
  assert.throws(() => verify(inventory), /duplicates metadata method|strictly ASCII-sorted/u);
});

runCase('row identity drift', () => {
  const inventory = baseInventory();
  inventory.builders[0].builder = 'com.soklet.McpWrong$Builder';
  assert.throws(() => verify(inventory), /canonical owner identity/u);
});

runCase('malformed method API', () => {
  const signaturePhases = baseSignaturePhases();
  const inventory = baseInventory(signaturePhases);
  const method = signaturePhases[0].records.find(
    (record) => record.id.includes('McpTextContent$Builder#metadata'),
  );
  method.api.parameters = [{ type: 'java.lang.String' }];
  assert.throws(() => verify(inventory, signaturePhases), /does not match its identity/u);
});

runCase('duplicate signature identity', () => {
  const signaturePhases = baseSignaturePhases();
  const inventory = baseInventory(signaturePhases);
  signaturePhases[0].records.push({ ...signaturePhases[0].records[0] });
  assert.throws(() => verify(inventory, signaturePhases), /Duplicate current signature/u);
});

process.stdout.write('Verified MCP metadata-builder verifier self-test (9 cases)\n');
