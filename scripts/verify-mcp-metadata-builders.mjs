#!/usr/bin/env node

import { lstatSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

export const ELIGIBLE_INTERFACES = Object.freeze([
  'com.soklet.McpContentBlock',
  'com.soklet.McpResourceContents',
]);
export const INVENTORY_PATH = 'api/mcp/mcp-metadata-builder-inventory.json';
export const METADATA_PARAMETER = 'com.soklet.McpJsonObject';
export const SIGNATURE_DIRECTORY = 'target/mcp-api-freezes';
export const SIGNATURE_PHASE_INVENTORY = 'api/mcp/frozen-phases';

const TOP_LEVEL_FIELDS = Object.freeze(['builders', 'derivation', 'formatVersion']);
const DERIVATION_FIELDS = Object.freeze([
  'eligibleInterfaces',
  'metadataParameter',
  'signatureDirectory',
  'signaturePhaseInventory',
]);
const BUILDER_FIELDS = Object.freeze([
  'builder',
  'family',
  'metadataMethod',
  'owner',
  'phase',
]);
const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true });

function fail(message) {
  throw new Error(message);
}

function asciiCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'ascii'), Buffer.from(right, 'ascii'));
}

function requireExactFields(value, fields, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = Object.keys(value).sort(asciiCompare);
  const expected = [...fields].sort(asciiCompare);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} fields must be exactly ${expected.join(', ')}; found ${actual.join(', ')}.`);
  }
}

function requireRegularFile(path, label) {
  let status;
  try {
    status = lstatSync(path);
  } catch (error) {
    fail(`Missing ${label}: ${path} (${error.message})`);
  }
  if (!status.isFile() || status.isSymbolicLink()) {
    fail(`${label} must be a regular non-symlink file: ${path}`);
  }
}

function readUtf8(path, label) {
  requireRegularFile(path, label);
  try {
    return UTF8_DECODER.decode(readFileSync(path));
  } catch (error) {
    fail(`${label} is not valid UTF-8: ${path} (${error.message})`);
  }
}

function metadataMethodId(owner) {
  const internalOwner = owner.replaceAll('.', '/');
  return `M:${internalOwner}$Builder#metadata(Lcom/soklet/McpJsonObject;)L${internalOwner}$Builder;`;
}

function canonicalBuilderRow(owner, family, phase) {
  return {
    builder: `${owner}$Builder`,
    family,
    metadataMethod: metadataMethodId(owner),
    owner,
    phase,
  };
}

function sameArray(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function validateInventory(inventory, phases) {
  requireExactFields(inventory, TOP_LEVEL_FIELDS, 'Metadata-builder inventory');
  if (inventory.formatVersion !== 1) {
    fail('Metadata-builder inventory formatVersion must be 1.');
  }
  requireExactFields(inventory.derivation, DERIVATION_FIELDS, 'Inventory derivation');
  if (!sameArray(inventory.derivation.eligibleInterfaces, ELIGIBLE_INTERFACES)) {
    fail('Inventory derivation eligibleInterfaces changed.');
  }
  if (inventory.derivation.metadataParameter !== METADATA_PARAMETER
      || inventory.derivation.signatureDirectory !== SIGNATURE_DIRECTORY
      || inventory.derivation.signaturePhaseInventory !== SIGNATURE_PHASE_INVENTORY) {
    fail('Inventory derivation paths or metadata parameter changed.');
  }
  if (!Array.isArray(inventory.builders) || inventory.builders.length === 0) {
    fail('Metadata-builder inventory builders must be a nonempty array.');
  }

  let previousMethod = null;
  const identities = new Set();
  for (const [index, row] of inventory.builders.entries()) {
    const label = `Metadata-builder inventory row ${index}`;
    requireExactFields(row, BUILDER_FIELDS, label);
    if (typeof row.owner !== 'string' || !/^com\.soklet\.Mcp[^./$#]+$/u.test(row.owner)) {
      fail(`${label} has an invalid owner.`);
    }
    if (!ELIGIBLE_INTERFACES.includes(row.family)) {
      fail(`${label} has an ineligible family.`);
    }
    if (!Number.isSafeInteger(row.phase) || !phases.includes(row.phase)) {
      fail(`${label} has an invalid frozen phase.`);
    }
    const expected = canonicalBuilderRow(row.owner, row.family, row.phase);
    for (const field of BUILDER_FIELDS) {
      if (row[field] !== expected[field]) {
        fail(`${label} ${field} does not match its canonical owner identity.`);
      }
    }
    if (identities.has(row.metadataMethod)) {
      fail(`${label} duplicates metadata method ${row.metadataMethod}.`);
    }
    if (previousMethod !== null
        && asciiCompare(previousMethod, row.metadataMethod) >= 0) {
      fail('Metadata-builder inventory rows must be strictly ASCII-sorted by metadataMethod.');
    }
    identities.add(row.metadataMethod);
    previousMethod = row.metadataMethod;
  }
  return inventory;
}

export function deriveMetadataBuilders(signaturePhases) {
  if (!Array.isArray(signaturePhases) || signaturePhases.length === 0) {
    fail('Signature phases must be a nonempty array.');
  }
  const recordsById = new Map();
  const phaseById = new Map();
  for (const phaseEntry of signaturePhases) {
    if (phaseEntry === null || typeof phaseEntry !== 'object'
        || !Number.isSafeInteger(phaseEntry.phase)
        || !Array.isArray(phaseEntry.records)) {
      fail('Each signature phase requires an integer phase and records array.');
    }
    for (const record of phaseEntry.records) {
      if (record === null || typeof record !== 'object'
          || typeof record.id !== 'string' || typeof record.kind !== 'string'
          || record.api === null || typeof record.api !== 'object') {
        fail(`Phase ${phaseEntry.phase} contains a malformed signature record.`);
      }
      if (recordsById.has(record.id)) {
        fail(`Duplicate current signature identity: ${record.id}`);
      }
      recordsById.set(record.id, record);
      phaseById.set(record.id, phaseEntry.phase);
    }
  }

  const rows = [];
  for (const [id, record] of recordsById) {
    const match = /^M:(com\/soklet\/Mcp[^/#$]+)\$Builder#metadata\(Lcom\/soklet\/McpJsonObject;\)L\1\$Builder;$/u.exec(id);
    if (match === null) {
      continue;
    }
    const internalOwner = match[1];
    const ownerRecord = recordsById.get(`C:${internalOwner}`);
    const builderRecord = recordsById.get(`C:${internalOwner}$Builder`);
    if (ownerRecord === undefined || builderRecord === undefined
        || ownerRecord.kind !== 'class' || builderRecord.kind !== 'class') {
      continue;
    }
    const ownerModifiers = ownerRecord.api.modifiers;
    const builderModifiers = builderRecord.api.modifiers;
    const methodModifiers = record.api.modifiers;
    const ownerInterfaces = ownerRecord.api.interfaces;
    if (!Array.isArray(ownerModifiers) || !ownerModifiers.includes('PUBLIC')
        || !Array.isArray(builderModifiers) || !builderModifiers.includes('PUBLIC')
        || !Array.isArray(methodModifiers) || !methodModifiers.includes('PUBLIC')
        || !Array.isArray(ownerInterfaces)) {
      continue;
    }
    const families = ownerInterfaces.filter((name) => ELIGIBLE_INTERFACES.includes(name));
    if (families.length === 0) {
      continue;
    }
    if (families.length !== 1) {
      fail(`Eligible metadata builder has ambiguous families: ${id}`);
    }
    if (!Array.isArray(record.api.parameters) || record.api.parameters.length !== 1
        || record.api.parameters[0]?.type !== METADATA_PARAMETER
        || record.api.returnType?.type !== `${internalOwner.replaceAll('/', '.')}$Builder`) {
      fail(`Eligible metadata method API does not match its identity: ${id}`);
    }
    rows.push(canonicalBuilderRow(
      internalOwner.replaceAll('/', '.'),
      families[0],
      phaseById.get(id),
    ));
  }
  return rows.sort((left, right) => asciiCompare(left.metadataMethod, right.metadataMethod));
}

export function verifyMetadataBuilders({ inventory, phases, signaturePhases }) {
  validateInventory(inventory, phases);
  const derived = deriveMetadataBuilders(signaturePhases);
  const inventoriedByMethod = new Map(
    inventory.builders.map((row) => [row.metadataMethod, row]),
  );
  const derivedByMethod = new Map(derived.map((row) => [row.metadataMethod, row]));
  const missing = derived
    .filter((row) => !inventoriedByMethod.has(row.metadataMethod))
    .map((row) => row.metadataMethod);
  const extra = inventory.builders
    .filter((row) => !derivedByMethod.has(row.metadataMethod))
    .map((row) => row.metadataMethod);
  if (missing.length > 0 || extra.length > 0) {
    fail(`Metadata-builder inventory set differs from current signatures; missing=[${missing.join(', ')}], extra=[${extra.join(', ')}].`);
  }
  for (const row of derived) {
    const inventoried = inventoriedByMethod.get(row.metadataMethod);
    if (JSON.stringify(inventoried) !== JSON.stringify(row)) {
      fail(`Metadata-builder inventory row drifted: ${row.metadataMethod}`);
    }
  }
  return { builders: derived.length, phases: [...phases] };
}

function parsePhases(root) {
  const text = readUtf8(join(root, SIGNATURE_PHASE_INVENTORY),
    'frozen-phase inventory');
  const phases = text.split(/\r\n|\n|\r/u)
    .filter((line) => line.length > 0)
    .map((line) => Number(line));
  if (phases.length === 0 || phases.some((phase) => !Number.isSafeInteger(phase))) {
    fail('Frozen-phase inventory must contain integer phase lines.');
  }
  for (const [index, phase] of phases.entries()) {
    if (phase !== 4 + index) {
      fail('Frozen phases must be the contiguous sorted prefix beginning with Phase 4.');
    }
  }
  return phases;
}

function parseJson(text, label) {
  try {
    return JSON.parse(text);
  } catch (error) {
    fail(`${label} is not valid JSON: ${error.message}`);
  }
}

function parseSignatureFile(root, phase) {
  const path = join(root, SIGNATURE_DIRECTORY, `phase-${phase}.signatures.jsonl`);
  const text = readUtf8(path, `Phase ${phase} generated signature file`);
  const records = [];
  for (const [index, line] of text.split(/\r\n|\n|\r/u).entries()) {
    if (line.length === 0) {
      continue;
    }
    records.push(parseJson(line, `Phase ${phase} signature line ${index + 1}`));
  }
  if (records.length === 0) {
    fail(`Phase ${phase} generated signature file is empty.`);
  }
  return { phase, records };
}

export function verifyMetadataBuildersAtRoot(root) {
  const resolvedRoot = resolve(root);
  const phases = parsePhases(resolvedRoot);
  const inventory = parseJson(
    readUtf8(join(resolvedRoot, INVENTORY_PATH), 'metadata-builder inventory'),
    'Metadata-builder inventory',
  );
  return verifyMetadataBuilders({
    inventory,
    phases,
    signaturePhases: phases.map((phase) => parseSignatureFile(resolvedRoot, phase)),
  });
}

const modulePath = fileURLToPath(import.meta.url);
if (process.argv[1] !== undefined && resolve(process.argv[1]) === modulePath) {
  if (process.argv.length !== 2) {
    process.stderr.write('Usage: node scripts/verify-mcp-metadata-builders.mjs\n');
    process.exitCode = 64;
  } else {
    try {
      const root = resolve(dirname(modulePath), '..');
      const result = verifyMetadataBuildersAtRoot(root);
      process.stdout.write(`Verified ${result.builders} MCP metadata builders against current Phase ${result.phases.join('/')} signatures\n`);
    } catch (error) {
      process.stderr.write(`${error.message}\n`);
      process.exitCode = 1;
    }
  }
}
