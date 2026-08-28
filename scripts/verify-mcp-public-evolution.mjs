#!/usr/bin/env node

import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { isAbsolute, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const INVENTORY_PATH = 'api/mcp/mcp-public-evolution-inventory.json';
const ACTIVE_TEXT_RULES_PATH = 'conformance/roadmap-readiness-active-text-rules.json';
const ACTIVE_TEXT_AUDIT_PATH = 'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md';
const LEDGERS = [
  'api/mcp/phase-4.signatures.jsonl',
  'api/mcp/phase-5.signatures.jsonl',
  'api/mcp/phase-6.signatures.jsonl',
];
const INCLUDE_FILES = [
  'api/mcp/phase-4.includes',
  'api/mcp/phase-5.includes',
  'api/mcp/phase-6.includes',
];
const ALLOWED_PARTITIONS = new Set(['candidate', 'externalSketch']);
const ALLOWED_MCP_STATES = new Set(['Active', 'Deprecated', 'Removed']);
const ALLOWED_API_STATES = new Set(['Supported', 'Deprecated']);
const DEPRECATION_TOKEN = /@SuppressWarnings\s*\(\s*(?:"deprecation"|\{[^}]*"deprecation"[^}]*\})\s*\)/s;

export class McpPublicEvolutionVerificationError extends Error {}

function fail(message) {
  throw new McpPublicEvolutionVerificationError(message);
}

function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    fail(`Unable to read JSON ${path}: ${error.message}`);
  }
}

function literalCount(text, literal) {
  if (!literal.length) fail('Active-text literals must be nonempty.');
  let count = 0;
  let offset = 0;
  while ((offset = text.indexOf(literal, offset)) !== -1) {
    count++;
    offset += literal.length;
  }
  return count;
}

export function verifyActiveText(root) {
  const contract = readJson(join(root, ACTIVE_TEXT_RULES_PATH));
  exactKeys(contract, ['formatVersion', 'rules'], 'active-text contract');
  if (contract.formatVersion !== 1 || !Array.isArray(contract.rules)
      || contract.rules.length === 0)
    fail('Active-text contract must be nonempty format version 1.');
  const ids = new Set();
  const rows = [];
  for (const rule of contract.rules) {
    exactKeys(rule, ['id', 'classification', 'rationale', 'required', 'forbidden'],
      `active-text rule ${rule.id}`);
    if (!/^[A-Z]+-[0-9]{3}$/.test(rule.id) || ids.has(rule.id))
      fail(`Active-text rule ID is malformed or duplicated: ${rule.id}`);
    ids.add(rule.id);
    if (!rule.classification || !rule.rationale) fail(`Active-text rule ${rule.id} lacks policy text.`);
    let requiredMatches = 0;
    let forbiddenMatches = 0;
    for (const assertion of rule.required) {
      exactKeys(assertion, ['path', 'literal', 'minCount', 'maxCount'],
        `required assertion ${rule.id}`);
      const path = join(root, assertion.path);
      if (!existsSync(path)) fail(`Active-text path is missing: ${assertion.path}`);
      const count = literalCount(readFileSync(path, 'utf8'), assertion.literal);
      requiredMatches += count;
      if (!Number.isInteger(assertion.minCount) || !Number.isInteger(assertion.maxCount)
          || count < assertion.minCount || count > assertion.maxCount)
        fail(`${rule.id} required literal count for ${assertion.path} is ${count}; expected ${assertion.minCount}..${assertion.maxCount}.`);
    }
    for (const assertion of rule.forbidden) {
      exactKeys(assertion, ['path', 'literal', 'maxCount'],
        `forbidden assertion ${rule.id}`);
      const path = join(root, assertion.path);
      if (!existsSync(path)) fail(`Active-text path is missing: ${assertion.path}`);
      const count = literalCount(readFileSync(path, 'utf8'), assertion.literal);
      forbiddenMatches += count;
      if (!Number.isInteger(assertion.maxCount) || count > assertion.maxCount)
        fail(`${rule.id} forbidden literal count for ${assertion.path} is ${count}; maximum is ${assertion.maxCount}.`);
    }
    rows.push({ ...rule, requiredMatches, forbiddenMatches });
  }
  const lines = [
    '# MCP roadmap active-text audit',
    '',
    'Generated deterministically by `scripts/verify-mcp-public-evolution.mjs` from',
    '`conformance/roadmap-readiness-active-text-rules.json`. Do not edit by hand.',
    '',
    '| Rule | Classification | Required matches | Forbidden matches | Decision |',
    '| --- | --- | ---: | ---: | --- |',
    ...rows.map((row) => `| ${row.id} | ${row.classification} | ${row.requiredMatches} | ${row.forbiddenMatches} | PASS |`),
    '',
    `Total: ${rows.length} active-text rules passed.`,
    '',
  ];
  const rendered = `${lines.join('\n')}`;
  const auditPath = join(root, ACTIVE_TEXT_AUDIT_PATH);
  if (!existsSync(auditPath)) fail(`Active-text audit is missing: ${ACTIVE_TEXT_AUDIT_PATH}`);
  if (readFileSync(auditPath, 'utf8') !== rendered)
    fail(`Active-text audit is stale; regenerate ${ACTIVE_TEXT_AUDIT_PATH}.`);
  return { ruleCount: rows.length, rendered };
}

function exactKeys(value, keys, label) {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${label} must have exact keys ${expected.join(', ')}; found ${actual.join(', ')}`);
}

export function declarationKey(declaration) {
  if (!declaration || typeof declaration !== 'object' || Array.isArray(declaration))
    fail('Suppression declaration must be an object.');
  const parameters = declaration.erasedParameterTypes;
  const parameterList = Array.isArray(parameters) ? parameters.join(',') : '';
  switch (declaration.kind) {
    case 'type':
      exactKeys(declaration, ['kind', 'owner'], 'type declaration');
      return `T:${declaration.owner}`;
    case 'constructor':
      exactKeys(declaration, ['kind', 'owner', 'erasedParameterTypes'], 'constructor declaration');
      return `C:${declaration.owner}#<init>(${parameterList})`;
    case 'method':
      exactKeys(declaration, ['kind', 'owner', 'name', 'erasedParameterTypes'], 'method declaration');
      return `M:${declaration.owner}#${declaration.name}(${parameterList})`;
    case 'anonymousMethod': {
      exactKeys(declaration,
        ['kind', 'host', 'anonymousSupertype', 'name', 'erasedParameterTypes'],
        'anonymous method declaration');
      const host = declarationKey(declaration.host);
      if (!host.startsWith('C:') && !host.startsWith('M:'))
        fail('Anonymous-method host must be a named constructor or method.');
      return `A:${host}{anonymous-supertype=${declaration.anonymousSupertype}}#${declaration.name}(${parameterList})`;
    }
    default:
      fail(`Unknown suppression declaration kind: ${declaration.kind}`);
  }
}

function stripJavaCommentsAndLiterals(source) {
  return source
    .replace(/"""[\s\S]*?"""/g, (value) => value.replace(/[^\r\n]/g, ' '))
    .replace(/"(?:\\.|[^"\\])*"/g, (value) => ' '.repeat(value.length))
    .replace(/'(?:\\.|[^'\\])'/g, (value) => ' '.repeat(value.length))
    .replace(/\/\*[\s\S]*?\*\//g, (value) => value.replace(/[^\r\n]/g, ' '))
    .replace(/\/\/[^\r\n]*/g, (value) => ' '.repeat(value.length));
}

function simpleTypeName(type) {
  const arrays = type.endsWith('[]') ? '[]' : '';
  const base = arrays ? type.slice(0, -2) : type;
  return `${base.slice(base.lastIndexOf('.') + 1)}${arrays}`;
}

function erasedParameterTypes(parameters) {
  if (!parameters.trim()) return [];
  const values = [];
  let start = 0;
  let angleDepth = 0;
  for (let index = 0; index <= parameters.length; index++) {
    const character = parameters[index];
    if (character === '<') angleDepth++;
    else if (character === '>') angleDepth--;
    if ((character === ',' && angleDepth === 0) || index === parameters.length) {
      let parameter = parameters.slice(start, index)
        .replace(/@[A-Za-z_$][\w$.]*(?:\s*\([^)]*\))?/g, ' ')
        .replace(/\bfinal\b/g, ' ')
        .trim();
      start = index + 1;
      while (/<[^<>]*>/.test(parameter)) parameter = parameter.replace(/<[^<>]*>/g, '');
      parameter = parameter.replace(/\.\.\./g, '[]').trim();
      const nameMatch = parameter.match(/\s+[A-Za-z_$][\w$]*\s*$/);
      if (!nameMatch) return null;
      const type = parameter.slice(0, nameMatch.index).replace(/\s+/g, '');
      values.push(simpleTypeName(type));
    }
  }
  return values;
}

function executableMatches(source, name, expectedParameterTypes) {
  const stripped = stripJavaCommentsAndLiterals(source);
  const escapedName = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`\\b${escapedName}\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[^;{]+)?[;{]`, 'g');
  const expected = expectedParameterTypes.map(simpleTypeName);
  let count = 0;
  for (const match of stripped.matchAll(pattern)) {
    if (match.index > 0 && stripped[match.index - 1] === '.') continue;
    const actual = erasedParameterTypes(match[1]);
    if (actual === null) continue;
    if (JSON.stringify(actual) === JSON.stringify(expected)) count++;
  }
  return count;
}

function declarationResolutionCount(source, declaration) {
  switch (declaration.kind) {
    case 'type': {
      const simple = declaration.owner.slice(declaration.owner.lastIndexOf('.') + 1);
      const stripped = stripJavaCommentsAndLiterals(source);
      return (stripped.match(new RegExp(`\\b(?:class|interface|enum)\\s+${simple}\\b`, 'g')) ?? []).length;
    }
    case 'constructor': {
      const simple = declaration.owner.slice(declaration.owner.lastIndexOf('.') + 1);
      return executableMatches(source, simple, declaration.erasedParameterTypes);
    }
    case 'method':
      return executableMatches(source, declaration.name, declaration.erasedParameterTypes);
    case 'anonymousMethod': {
      if (declarationResolutionCount(source, declaration.host) !== 1) return 0;
      const simpleSupertype = declaration.anonymousSupertype
        .slice(declaration.anonymousSupertype.lastIndexOf('.') + 1);
      const anonymousCount = (stripJavaCommentsAndLiterals(source)
        .match(new RegExp(`\\bnew\\s+${simpleSupertype}\\s*\\([^)]*\\)\\s*\\{`, 'g')) ?? []).length;
      if (anonymousCount !== 1) return 0;
      return executableMatches(source, declaration.name, declaration.erasedParameterTypes);
    }
    default:
      fail(`Unknown suppression declaration kind: ${declaration.kind}`);
  }
}

export function declarationResolutionCountForTest(source, declaration) {
  return declarationResolutionCount(source, declaration);
}

export function hasDeprecationSuppressionForTest(source) {
  return DEPRECATION_TOKEN.test(source);
}

function loadIncludes(root) {
  const values = new Set();
  for (const relativePath of INCLUDE_FILES) {
    const path = join(root, relativePath);
    for (const raw of readFileSync(path, 'utf8').split(/\r?\n/)) {
      const value = raw.trim();
      if (value && !value.startsWith('#')) {
        if (values.has(value)) fail(`Duplicate reviewed include entry: ${value}`);
        values.add(value);
      }
    }
  }
  return values;
}

function ledgerRows(root) {
  const rows = [];
  for (const relativePath of LEDGERS) {
    const path = join(root, relativePath);
    for (const line of readFileSync(path, 'utf8').split(/\r?\n/).filter(Boolean))
      rows.push(JSON.parse(line));
  }
  return rows;
}

function verifyStructuralOwners(root, inventory, includes, ledgers) {
  const actualEnums = ledgers
    .filter(({ id, kind, api }) => kind === 'class'
      && api.classType === 'ENUM'
      && id.startsWith('C:com/soklet/Mcp')
      && includes.has(id.slice(2).replaceAll('/', '.')))
    .map(({ id }) => id.slice(2).replaceAll('/', '.').replaceAll('$', '.'));
  const sealedRoots = [];
  for (const include of includes) {
    if (include.includes('$')) continue;
    const source = join(root, 'src/main/java', ...include.split('.')) + '.java';
    if (!existsSync(source)) continue;
    const simple = include.slice(include.lastIndexOf('.') + 1);
    const text = readFileSync(source, 'utf8');
    if (new RegExp(`public\\s+sealed\\s+(?:interface|class)\\s+${simple}\\b`).test(text))
      sealedRoots.push(include);
  }
  const actual = [...new Set([...sealedRoots, ...actualEnums])].sort();
  const declared = inventory.structuralOwners.map(({ owner }) => owner).sort();
  if (JSON.stringify(actual) !== JSON.stringify(declared)) {
    const missing = actual.filter((owner) => !declared.includes(owner));
    const stale = declared.filter((owner) => !actual.includes(owner));
    fail(`Structural-owner inventory mismatch; missing=${missing.join(',') || '<none>'}; stale=${stale.join(',') || '<none>'}`);
  }
  if (new Set(declared).size !== declared.length) fail('Structural owners must be unique.');
  for (const owner of inventory.structuralOwners) {
    exactKeys(owner,
      ['owner', 'kind', 'status', 'rationale', 'changePolicy', 'javadocGuidance', 'defaultSwitchGuidance'],
      `structural owner ${owner.owner}`);
    if (!['sealedRoot', 'enum'].includes(owner.kind)) fail(`Invalid owner kind: ${owner.owner}`);
    if (!['closed-by-domain', 'evolutionary'].includes(owner.status)) fail(`Invalid owner status: ${owner.owner}`);
    for (const key of ['rationale', 'changePolicy', 'javadocGuidance', 'defaultSwitchGuidance'])
      if (typeof owner[key] !== 'string' || !owner[key].trim()) fail(`${owner.owner}.${key} must be nonempty.`);
  }
}

function deprecatedLedgerIds(ledgers, includes) {
  return ledgers.filter(({ id, api }) => {
    const owner = id.match(/^[CFM]:([^#(]+)/)?.[1]?.replaceAll('/', '.');
    const top = owner?.replace(/\$.*$/, '');
    const reviewed = [...includes].some((entry) => entry === owner || entry === top || entry.startsWith(`${owner}$`));
    return reviewed && (api?.annotations ?? []).some(({ name }) => name === 'java.lang.Deprecated');
  }).map(({ id }) => id);
}

function verifyLifecycle(inventory, ledgers, includes) {
  const elements = new Set();
  for (const entry of inventory.lifecycleEntries) {
    exactKeys(entry, ['element', 'mcpLifecycle', 'sokletApiLifecycle'], `lifecycle entry ${entry.element}`);
    if (elements.has(entry.element)) fail(`Duplicate lifecycle element: ${entry.element}`);
    elements.add(entry.element);
    exactKeys(entry.mcpLifecycle,
      ['state', 'profile', 'source', 'sinceRevision', 'earliestSpecificationRemovalDate'],
      `MCP lifecycle ${entry.element}`);
    exactKeys(entry.sokletApiLifecycle,
      ['state', 'reviewedDecisionReference'], `Soklet API lifecycle ${entry.element}`);
    if (!ALLOWED_MCP_STATES.has(entry.mcpLifecycle.state)) fail(`Invalid MCP lifecycle state: ${entry.element}`);
    if (!ALLOWED_API_STATES.has(entry.sokletApiLifecycle.state)) fail(`Invalid Soklet API lifecycle state: ${entry.element}`);
    if (entry.sokletApiLifecycle.state === 'Deprecated'
      && (typeof entry.sokletApiLifecycle.reviewedDecisionReference !== 'string'
        || !entry.sokletApiLifecycle.reviewedDecisionReference.trim()))
      fail(`Deprecated Soklet API entry lacks a reviewed decision: ${entry.element}`);
  }
  const annotated = deprecatedLedgerIds(ledgers, includes);
  if (annotated.length)
    fail(`Supported lifecycle entries retain Java @Deprecated markers in signature ledgers: ${annotated.join(', ')}`);
}

function verifySuppressionSchema(root, inventory) {
  exactKeys(inventory.suppressionBaseline, ['fingerprintGrammarVersion', 'rows'], 'suppression baseline');
  if (inventory.suppressionBaseline.fingerprintGrammarVersion !== 1)
    fail('Suppression fingerprint grammar version must be 1.');
  const rows = inventory.suppressionBaseline.rows;
  if (!Array.isArray(rows) || rows.length !== 18) fail('Suppression baseline must contain exactly 18 rows.');
  const fingerprints = new Set();
  let candidateCount = 0;
  let externalCount = 0;
  for (const row of rows) {
    if (!ALLOWED_PARTITIONS.has(row.partition)) fail(`Unknown suppression partition: ${row.partition}`);
    const expected = row.partition === 'externalSketch'
      ? ['partition', 'path', 'enforcementHost', 'declaration']
      : ['partition', 'path', 'declaration'];
    exactKeys(row, expected, `suppression row ${row.path}`);
    if (row.path.startsWith('/') || row.path.includes('..') || row.path.includes('\\'))
      fail(`Suppression path must be POSIX root-relative: ${row.path}`);
    const key = `${row.partition}|${row.path}|${declarationKey(row.declaration)}`;
    if (fingerprints.has(key)) fail(`Duplicate suppression fingerprint: ${key}`);
    fingerprints.add(key);
    if (row.partition === 'candidate') {
      candidateCount++;
      const sourcePath = join(root, row.path);
      if (!existsSync(sourcePath)) fail(`Candidate suppression source is missing: ${row.path}`);
      const resolutionCount = declarationResolutionCount(
        readFileSync(sourcePath, 'utf8'), row.declaration);
      if (resolutionCount !== 1)
        fail(`Candidate suppression fingerprint must resolve exactly once (${key}); found ${resolutionCount}.`);
    } else {
      externalCount++;
      if (row.enforcementHost !== 'R4/R7-workspace') fail('External suppression row has wrong enforcementHost.');
    }
  }
  if (candidateCount !== 17 || externalCount !== 1)
    fail(`Suppression partitions must contain 17 candidate and one externalSketch row; found ${candidateCount}/${externalCount}.`);
}

function javaFiles(directory) {
  if (!existsSync(directory)) return [];
  const result = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...javaFiles(path));
    else if (entry.isFile() && entry.name.endsWith('.java')) result.push(path);
  }
  return result;
}

function verifyCandidateSuppressionScan(root) {
  const scopes = [
    ...javaFiles(join(root, 'src/main/java/com/soklet')).filter((path) =>
      /^Mcp.*\.java$/.test(path.slice(path.lastIndexOf(sep) + 1))),
    join(root, 'src/main/java/com/soklet/DefaultMcpServer.java'),
    ...javaFiles(join(root, 'src/test/java/com/soklet')).filter((path) =>
      /^Mcp.*\.java$/.test(path.slice(path.lastIndexOf(sep) + 1))),
  ];
  const offenders = [...new Set(scopes)].filter((path) => DEPRECATION_TOKEN.test(readFileSync(path, 'utf8')));
  if (offenders.length)
    fail(`In-scope deprecation suppressions remain: ${offenders.map((path) => path.slice(root.length + 1)).join(', ')}`);
}

function verifyExternal(root, inventory, externalRoot) {
  if (!isAbsolute(externalRoot) || !existsSync(externalRoot) || !statSync(externalRoot).isDirectory())
    fail('--external-sketch-root must name an absolute existing MCP planning root.');
  const externalRows = inventory.suppressionBaseline.rows.filter(({ partition }) => partition === 'externalSketch');
  if (externalRows.length !== 1) fail('Workspace mode requires exactly one externalSketch row.');
  const row = externalRows[0];
  const path = join(externalRoot, row.path);
  if (!existsSync(path)) fail(`External sketch declaration source is missing: ${row.path}`);
  const text = readFileSync(path, 'utf8');
  const resolutionCount = declarationResolutionCount(text, row.declaration);
  if (resolutionCount !== 1) fail('External sketch getDeprecatedLogLevel declaration must resolve exactly once.');
  if (DEPRECATION_TOKEN.test(text)) fail('External sketch deprecation suppression survives.');
}

export function verifyRoot(root, { externalSketchRoot } = {}) {
  root = resolve(root);
  const inventory = readJson(join(root, INVENTORY_PATH));
  exactKeys(inventory,
    ['formatVersion', 'reviewedIncludeFiles', 'structuralOwners', 'lifecycleEntries', 'suppressionBaseline'],
    'public evolution inventory');
  if (inventory.formatVersion !== 1) fail('Public evolution inventory formatVersion must be 1.');
  if (JSON.stringify(inventory.reviewedIncludeFiles) !== JSON.stringify(INCLUDE_FILES))
    fail('Inventory reviewedIncludeFiles must name Phase 4, 5, and 6 exactly.');
  const includes = loadIncludes(root);
  const ledgers = ledgerRows(root);
  verifyStructuralOwners(root, inventory, includes, ledgers);
  verifySuppressionSchema(root, inventory);
  verifyCandidateSuppressionScan(root);
  verifyLifecycle(inventory, ledgers, includes);
  verifyActiveText(root);
  if (externalSketchRoot !== undefined) verifyExternal(root, inventory, externalSketchRoot);
  return { structuralOwnerCount: inventory.structuralOwners.length,
    lifecycleEntryCount: inventory.lifecycleEntries.length };
}

function parseArgs(argv) {
  let externalSketchRoot;
  let activeTextOnly = false;
  for (let index = 0; index < argv.length; index++) {
    if (argv[index] === '--active-text-only') {
      if (activeTextOnly) fail('--active-text-only may be supplied only once.');
      activeTextOnly = true;
      continue;
    }
    if (argv[index] !== '--external-sketch-root' || index + 1 >= argv.length)
      fail(`Unknown or incomplete argument: ${argv[index]}`);
    if (externalSketchRoot !== undefined) fail('--external-sketch-root may be supplied only once.');
    externalSketchRoot = argv[++index];
  }
  if (activeTextOnly && externalSketchRoot !== undefined)
    fail('--active-text-only cannot be combined with --external-sketch-root.');
  return { externalSketchRoot, activeTextOnly };
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  try {
    const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
    const options = parseArgs(process.argv.slice(2));
    if (options.activeTextOnly) {
      const result = verifyActiveText(root);
      console.log(`MCP roadmap active-text audit verified (${result.ruleCount} rules).`);
      process.exit(0);
    }
    const result = verifyRoot(root, options);
    console.log(`MCP public evolution inventory verified (${result.structuralOwnerCount} structural owners; ${result.lifecycleEntryCount} lifecycle entries).`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
