#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import {
  basename,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyActiveText as verifyPublicEvolutionActiveText }
  from './verify-mcp-public-evolution.mjs';

const PLANNING_AUTHORITY_PATH =
  'conformance/soklet-4.0-planning-authority.json';
const ROADMAP_PATH =
  'conformance/roadmap-readiness-deferred-features.json';
const POLICY_PATH = 'conformance/MCP_ROADMAP_READINESS_POLICY.md';
const OPENNESS_PATH = 'conformance/mcp-protocol-openness-inventory.json';
const OPENNESS_RENDERING_PATH =
  'conformance/MCP_PROTOCOL_OPENNESS_INVENTORY_2026-07-28.md';

const HEX_40 = /^[0-9a-f]{40}$/u;
const HEX_64 = /^[0-9a-f]{64}$/u;
const NI_ID = /^NI-[0-9]{2}$/u;
const DF_ID = /^DF-[0-9]{2}$/u;
const OPEN_ID = /^OPEN-[0-9]{3}$/u;

const EXPECTED_APPROVAL = Object.freeze({
  path: 'SOKLET_4_0_U0_APPROVAL.json',
  sha256: '451c2be76e76c4803c6b25c1cebbf6fd1468e3df474a1edb1c6eaad2bde4234f',
});
const EXPECTED_AUTHORITIES = Object.freeze([
  Object.freeze({
    path: 'SOKLET_4_0_COMPLETION_PLAN.md',
    sha256: 'cc172c07527c71a99894e228742333443bee6bd8047d228f7615aae7db58b0da',
  }),
  Object.freeze({
    path: 'SOKLET_APPLICATION_IMPLEMENTATION_PLAN_V4.md',
    sha256: 'ad169767cec8e5a70dddde5776d185ba2d543ccc60fabf894e12f7a90c6cc22e',
  }),
  Object.freeze({
    path: 'mcp/MCP_IMPLEMENTATION_PLAN_V11.md',
    sha256: '07dc4313e771a7489de74170c9c6dc301b8cc9c620be6a3b02952b3d7a776f72',
  }),
]);
const EXPECTED_IMMUTABLE_INPUTS = Object.freeze([
  Object.freeze({
    path: 'mcp/MCP_IMPLEMENTATION_PLAN_V10.md',
    sha256: '7f06083391cc9e436b1a044b97b99b62beabc0f1b98815a7ec1fc3943b5b05c4',
  }),
  Object.freeze({
    path: 'mcp/MCP_LOCALIZATION_HANDOFF.md',
    sha256: '76179a5b3207f0fc96b9d2c0e32de424f63aadbed4b98c7d49106c3dfc1bec8b',
  }),
  Object.freeze({
    path: 'mcp/MCP_LOCALIZATION_IMPLEMENTATION_PLAN.md',
    sha256: '597a71990cca03de21eb05aae3e350c1d104f44ea65bd6e94aed4eb396177d42',
  }),
  Object.freeze({
    path: 'mcp/MCP_ROADMAP_READINESS_IMPLEMENTATION_PLAN_V2_3.md',
    sha256: '67224453ec45bccde8997b639c4da06af40380e4123ebf34fae4a9ca573db3a7',
  }),
  Object.freeze({
    path: 'mcp/PHASE_6_IMPLEMENTATION_PROGRESS.md',
    sha256: 'b104c31b16cdf21bc10b445b35c3f9f3a308f347e02073da7a50e1bc92fa6e8f',
  }),
]);
const EXPECTED_BASELINE_CORE = Object.freeze({
  commit: '7c83c20e03be6fc6ac55b6f2b79e5fc8a2ecea23',
  tree: 'efb3bec1478fce31e561aac0103cf9df9e90a15a',
});
const EXPECTED_POST_D2_CORE = Object.freeze({
  commit: '95594f6594eddc499f3dc789d7a19dadf8efccf9',
  tree: '6c3369484ff14786774186f93cb378fb06111709',
});
const EXPECTED_DECISION_IDS = Object.freeze([
  'G0-UMBRELLA',
  'LIFECYCLE-GATE-01',
  'LIFECYCLE-GATE-02',
  'LIFECYCLE-GATE-03',
  'LIFECYCLE-GATE-04',
  'LIFECYCLE-GATE-05',
  'LIFECYCLE-GATE-06',
  'MCP-0-01',
  'MCP-0-02',
  'MCP-0-03',
  'MCP-0-04',
  'MCP-0-05',
  'MCP-0-06',
  'MCP-0-07',
  'MCP-0-08',
  'MCP-0-09',
  'MCP-0-10',
  'MCP-0-11',
  'MCP-0-12',
  'MCP-0-13',
  'MCP-0-14',
  'MCP-0-15',
  'MCP-0-16',
]);
const EXPECTED_NI_IDS = Object.freeze(
  Array.from({ length: 14 }, (_, index) =>
    `NI-${String(index + 1).padStart(2, '0')}`));
const EXPECTED_DF_IDS = Object.freeze(
  Array.from({ length: 15 }, (_, index) =>
    `DF-${String(index + 1).padStart(2, '0')}`));
const SCAN_ROOTS = Object.freeze([
  'src/main/java/com/soklet/internal/mcp/**/*.java',
  'src/main/java/com/soklet/Mcp*.java',
]);
const MATCHER_RULES = Object.freeze([
  Object.freeze({
    description: 'Calls to an inbound unknown-key or metadata rejection helper.',
    family: 'unknown-key-rejection-helper',
    id: 'OPEN-MATCH-001',
  }),
  Object.freeze({
    description: 'Exact allowed-key or literal-vocabulary sets supplied to validators.',
    family: 'exact-allowed-key-or-vocabulary-set',
    id: 'OPEN-MATCH-002',
  }),
  Object.freeze({
    description: 'Method, capability, or discriminator switches and closed lookup sets.',
    family: 'selector-switch-or-closed-lookup',
    id: 'OPEN-MATCH-003',
  }),
  Object.freeze({
    description: 'Literal equality chains over method, name, or capability selectors with a closed fallthrough.',
    family: 'literal-equality-cascade',
    id: 'OPEN-MATCH-004',
  }),
]);
const PHASES = new Set([
  'common-bootstrap',
  'profile-2026-07-28',
  'deferred-r2c',
]);
const CLOSURE_AUTHORITIES = new Set([
  'MCP_2026_07_28',
  'SOKLET_POLICY',
]);
const MATCHER_PRIORITY = Object.freeze([
  // A literal cascade is the most specific classification and preserves the
  // two V11 anchor exemplars even when the same method also uses an allowed
  // set. Helper calls are next, followed by supplied sets and broad switches.
  'OPEN-MATCH-004',
  'OPEN-MATCH-001',
  'OPEN-MATCH-002',
  'OPEN-MATCH-003',
]);

export class McpRoadmapReadinessVerificationError extends Error {}

function fail(message) {
  throw new McpRoadmapReadinessVerificationError(message);
}

function asciiCompare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort(asciiCompare)
      .map((key) => [key, canonicalValue(value[key])]));
  }
  return value;
}

export function canonicalJson(value) {
  return `${JSON.stringify(canonicalValue(value), null, 2)}\n`;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function exactKeys(value, keys, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  const actual = Object.keys(value).sort(asciiCompare);
  const expected = [...keys].sort(asciiCompare);
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${label} must have exact keys ${expected.join(', ')}; found ${actual.join(', ')}.`);
}

function nonblank(value, label) {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0)
    fail(`${label} must be a nonblank, trimmed string.`);
  return value;
}

function candidatePath(root, relativePath, kind, label) {
  if (typeof relativePath !== 'string' || relativePath.length === 0
      || isAbsolute(relativePath) || relativePath.includes('\\'))
    fail(`${label} must be a nonempty POSIX candidate-relative path.`);
  const components = relativePath.split('/');
  if (components.some((component) => component.length === 0
      || component === '.' || component === '..'))
    fail(`${label} must remain beneath the candidate root without dot components.`);

  let current = root;
  const allComponents = [{ path: root, final: components.length === 0 }];
  for (const [index, component] of components.entries()) {
    current = join(current, component);
    allComponents.push({ path: current, final: index === components.length - 1 });
  }
  const normalizedRelative = relative(root, current);
  if (normalizedRelative === '..' || normalizedRelative.startsWith(`..${sep}`)
      || isAbsolute(normalizedRelative))
    fail(`${label} escapes the candidate root.`);

  for (const entry of allComponents) {
    let status;
    try {
      status = lstatSync(entry.path);
    } catch (error) {
      fail(`${label} is missing: ${relativePath} (${error.message})`);
    }
    if (status.isSymbolicLink())
      fail(`${label} must not traverse a symbolic link: ${relativePath}.`);
    if (!entry.final && !status.isDirectory())
      fail(`${label} has a non-directory path component: ${relativePath}.`);
    if (entry.final && kind === 'file' && !status.isFile())
      fail(`${label} must be a regular file: ${relativePath}.`);
    if (entry.final && kind === 'directory' && !status.isDirectory())
      fail(`${label} must be a directory: ${relativePath}.`);
  }
  return current;
}

function readCandidateUtf8(root, relativePath, label) {
  return readFileSync(candidatePath(root, relativePath, 'file', label), 'utf8');
}

function assertCanonicalJson(root, relativePath, label) {
  let text;
  let value;
  try {
    text = readCandidateUtf8(root, relativePath, label);
    value = JSON.parse(text);
  } catch (error) {
    if (error instanceof McpRoadmapReadinessVerificationError) throw error;
    fail(`Unable to read ${label}: ${error.message}`);
  }
  if (text !== canonicalJson(value))
    fail(`${label} is not canonical recursive ASCII-sorted JSON with one LF.`);
  return { bytes: Buffer.from(text, 'utf8'), value };
}

function exactValue(actual, expected, label) {
  if (canonicalJson(actual) !== canonicalJson(expected))
    fail(`${label} does not match the approved value.`);
}

function verifyPlanningAuthority(root) {
  const { bytes, value } = assertCanonicalJson(root, PLANNING_AUTHORITY_PATH,
    'planning-authority snapshot');
  exactKeys(value, [
    'formatVersion',
    'releaseTarget',
    'productionProfile',
    'approval',
    'authorities',
    'immutableInputs',
    'baselineCore',
    'postD2Core',
    'approvedDecisionIds',
  ], 'planning-authority snapshot');
  if (value.formatVersion !== 1 || value.releaseTarget !== '4.0.0'
      || value.productionProfile !== '2026-07-28')
    fail('Planning-authority format, release target, or production profile is invalid.');
  exactValue(value.approval, EXPECTED_APPROVAL, 'Planning approval identity');
  exactValue(value.authorities, EXPECTED_AUTHORITIES, 'Planning authorities');
  exactValue(value.immutableInputs, EXPECTED_IMMUTABLE_INPUTS,
    'Planning immutable inputs');
  exactValue(value.baselineCore, EXPECTED_BASELINE_CORE, 'Baseline core identity');
  exactValue(value.postD2Core, EXPECTED_POST_D2_CORE, 'Post-D2 core identity');
  exactValue(value.approvedDecisionIds, EXPECTED_DECISION_IDS,
    'Approved decision IDs');
  for (const [label, core] of [
    ['baselineCore', value.baselineCore],
    ['postD2Core', value.postD2Core],
  ]) {
    exactKeys(core, ['commit', 'tree'], label);
    if (!HEX_40.test(core.commit) || !HEX_40.test(core.tree))
      fail(`${label} commit and tree must be lowercase 40-character Git identities.`);
  }
  for (const [label, rows] of [
    ['authorities', value.authorities],
    ['immutableInputs', value.immutableInputs],
  ]) {
    if (!Array.isArray(rows)) fail(`${label} must be an array.`);
    for (const [index, row] of rows.entries()) {
      exactKeys(row, ['path', 'sha256'], `${label}[${index}]`);
      nonblank(row.path, `${label}[${index}].path`);
      if (!HEX_64.test(row.sha256)) fail(`${label}[${index}].sha256 is invalid.`);
    }
  }
  return { bytes, sha256: sha256(bytes), value };
}

function uniqueExactIds(rows, expectedIds, pattern, label) {
  if (!Array.isArray(rows)) fail(`${label} must be an array.`);
  const ids = rows.map((row) => row?.id);
  for (const id of ids) {
    if (typeof id !== 'string' || !pattern.test(id))
      fail(`${label} contains a malformed ID: ${String(id)}.`);
  }
  if (new Set(ids).size !== ids.length)
    fail(`${label} contains a duplicate ID.`);
  if (JSON.stringify(ids) !== JSON.stringify(expectedIds))
    fail(`${label} IDs must be exactly ${expectedIds.join(', ')} in order.`);
}

function verifyRoadmap(root, planning) {
  const { value } = assertCanonicalJson(root, ROADMAP_PATH,
    'roadmap-readiness inventory');
  exactKeys(value, [
    'formatVersion',
    'supportedProfile',
    'planningSource',
    'planningAuthoritySnapshotSha256',
    'negativeInventory',
    'deferredFeatures',
  ], 'roadmap-readiness inventory');
  if (value.formatVersion !== 1 || value.supportedProfile !== '2026-07-28')
    fail('Roadmap format or supported profile is invalid.');
  exactValue(value.planningSource, EXPECTED_AUTHORITIES[0],
    'Roadmap planning source');
  if (value.planningAuthoritySnapshotSha256 !== planning.sha256)
    fail('Roadmap planningAuthoritySnapshotSha256 does not match raw planning-authority bytes.');

  uniqueExactIds(value.negativeInventory, EXPECTED_NI_IDS, NI_ID,
    'Negative inventory');
  const knownNi = new Set(EXPECTED_NI_IDS);
  for (const [index, row] of value.negativeInventory.entries()) {
    exactKeys(row, ['id', 'statement', 'status', 'rationale'],
      `negativeInventory[${index}]`);
    nonblank(row.statement, `${row.id}.statement`);
    nonblank(row.rationale, `${row.id}.rationale`);
    if (row.status !== 'ABSENT_IN_4_0_0')
      fail(`${row.id}.status must be ABSENT_IN_4_0_0.`);
  }

  uniqueExactIds(value.deferredFeatures, EXPECTED_DF_IDS, DF_ID,
    'Deferred features');
  for (const [index, row] of value.deferredFeatures.entries()) {
    exactKeys(row, [
      'id',
      'direction',
      'trigger',
      'landingZone',
      'preReleaseHedge',
      'evidenceClassification',
      'testEvidence',
      'negativeInventoryKeys',
      'negativeInventoryReason',
    ], `deferredFeatures[${index}]`);
    for (const field of ['direction', 'trigger', 'landingZone', 'preReleaseHedge'])
      nonblank(row[field], `${row.id}.${field}`);
    if (!Array.isArray(row.negativeInventoryKeys))
      fail(`${row.id}.negativeInventoryKeys must be an array.`);
    if (new Set(row.negativeInventoryKeys).size !== row.negativeInventoryKeys.length)
      fail(`${row.id}.negativeInventoryKeys contains a duplicate.`);
    for (const key of row.negativeInventoryKeys) {
      if (!knownNi.has(key)) fail(`${row.id} references unknown negative inventory ${key}.`);
    }
    const reasonPresent = row.negativeInventoryReason !== null;
    if (row.negativeInventoryKeys.length === 0) {
      if (!reasonPresent) fail(`${row.id} requires a reviewed negativeInventoryReason.`);
      nonblank(row.negativeInventoryReason, `${row.id}.negativeInventoryReason`);
    } else if (reasonPresent) {
      fail(`${row.id}.negativeInventoryReason must be null when mappings are present.`);
    }
    if (row.evidenceClassification !== 'planned'
        && row.evidenceClassification !== 'implemented')
      fail(`${row.id}.evidenceClassification is invalid.`);
    if (row.evidenceClassification === 'planned') {
      if (row.testEvidence !== null)
        fail(`${row.id} is planned and therefore testEvidence must be null.`);
    } else {
      // Promotion changes the reviewed verifier contract during R7; U6 cannot
      // turn a hedge into implementation evidence by editing data alone.
      fail(`${row.id} is prematurely promoted; only R7 may enable implemented evidence.`);
    }
  }

  const rendered = renderRoadmapPolicy(value);
  if (readCandidateUtf8(root, POLICY_PATH, 'roadmap policy') !== rendered)
    fail(`Roadmap policy is stale; regenerate ${POLICY_PATH}.`);
  return { rendered, value };
}

function markdown(value) {
  return String(value).replaceAll('\\', '\\\\').replaceAll('|', '\\|')
    .replaceAll('\r', ' ').replaceAll('\n', ' ');
}

export function renderRoadmapPolicy(roadmap) {
  const lines = [
    '# MCP roadmap readiness policy',
    '',
    'Generated deterministically by `scripts/verify-mcp-roadmap-readiness.mjs` from',
    '`conformance/roadmap-readiness-deferred-features.json`. Do not edit by hand.',
    '',
    `Supported profile: \`${roadmap.supportedProfile}\``,
    '',
    `Planning source: \`${roadmap.planningSource.path}\` (\`${roadmap.planningSource.sha256}\`)`,
    '',
    `Planning-authority snapshot SHA-256: \`${roadmap.planningAuthoritySnapshotSha256}\``,
    '',
    '## Negative inventory',
    '',
    '| ID | 4.0 status | Statement | Rationale |',
    '| --- | --- | --- | --- |',
    ...roadmap.negativeInventory.map((row) =>
      `| ${row.id} | ${row.status} | ${markdown(row.statement)} | ${markdown(row.rationale)} |`),
    '',
    '## Deferred features',
    '',
  ];
  for (const row of roadmap.deferredFeatures) {
    lines.push(
      `### ${row.id} — ${row.direction}`,
      '',
      `- Trigger: ${row.trigger}`,
      `- Landing zone: ${row.landingZone}`,
      `- Pre-release hedge: ${row.preReleaseHedge}`,
      `- Evidence classification: \`${row.evidenceClassification}\``,
      `- Test evidence: ${row.testEvidence === null ? 'None.' : row.testEvidence}`,
      `- Negative-inventory keys: ${row.negativeInventoryKeys.length === 0
        ? 'None.' : row.negativeInventoryKeys.map((key) => `\`${key}\``).join(', ')}`,
      `- Reviewed no-mapping reason: ${row.negativeInventoryReason ?? 'Not applicable.'}`,
      '',
    );
  }
  return lines.join('\n');
}

function maskJava(source, maskStrings) {
  const characters = [...source];
  let state = 'code';
  for (let index = 0; index < characters.length; index++) {
    const current = characters[index];
    const next = characters[index + 1];
    if (state === 'code') {
      if (current === '/' && next === '/') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'line-comment';
      } else if (current === '/' && next === '*') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'block-comment';
      } else if (current === '"') {
        if (source.slice(index, index + 3) === '\"\"\"') {
          if (maskStrings) characters[index] = characters[index + 1] = characters[index + 2] = ' ';
          index += 2;
          state = 'text-block';
        } else {
          if (maskStrings) characters[index] = ' ';
          state = 'string';
        }
      } else if (current === '\'') {
        if (maskStrings) characters[index] = ' ';
        state = 'character';
      }
    } else if (state === 'line-comment') {
      if (current === '\n' || current === '\r') state = 'code';
      else characters[index] = ' ';
    } else if (state === 'block-comment') {
      if (current === '*' && next === '/') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'code';
      } else if (current !== '\n' && current !== '\r') characters[index] = ' ';
    } else if (state === 'text-block') {
      if (source.slice(index, index + 3) === '\"\"\"') {
        if (maskStrings) characters[index] = characters[index + 1] = characters[index + 2] = ' ';
        index += 2;
        state = 'code';
      } else if (maskStrings && current !== '\n' && current !== '\r') characters[index] = ' ';
    } else if (state === 'string' || state === 'character') {
      if (current === '\\') {
        if (maskStrings) {
          characters[index] = ' ';
          if (index + 1 < characters.length) characters[index + 1] = ' ';
        }
        index++;
      } else if ((state === 'string' && current === '"')
          || (state === 'character' && current === '\'')) {
        if (maskStrings) characters[index] = ' ';
        state = 'code';
      } else if (maskStrings && current !== '\n' && current !== '\r') {
        characters[index] = ' ';
      }
    }
  }
  return characters.join('');
}

function javaFiles(root) {
  const files = [];
  const internalRelative = 'src/main/java/com/soklet/internal/mcp';
  const internal = candidatePath(root, internalRelative, 'directory',
    `openness scan root ${internalRelative}`);
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => asciiCompare(left.name, right.name))) {
      const path = join(directory, entry.name);
      if (entry.isSymbolicLink()) fail(`Openness scan root contains a symlink: ${relative(root, path)}.`);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.java')) files.push(path);
    }
  };
  visit(internal);
  const publicRelative = 'src/main/java/com/soklet';
  const publicRoot = candidatePath(root, publicRelative, 'directory',
    `openness scan root ${publicRelative}`);
  for (const entry of readdirSync(publicRoot, { withFileTypes: true })
    .sort((left, right) => asciiCompare(left.name, right.name))) {
    if (/^Mcp.*\.java$/u.test(entry.name)) {
      const path = join(publicRoot, entry.name);
      if (entry.isSymbolicLink() || !entry.isFile())
        fail(`Direct public openness scan entry must be a regular non-symlink file: ${relative(root, path)}.`);
      files.push(path);
    }
  }
  return files.sort(asciiCompare);
}

function matchingBrace(masked, opening) {
  let depth = 0;
  for (let index = opening; index < masked.length; index++) {
    if (masked[index] === '{') depth++;
    else if (masked[index] === '}' && --depth === 0) return index;
  }
  return -1;
}

function containingTypeScopes(structure, index) {
  const scopes = [];
  let delimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') delimiter = opening;
      continue;
    }
    const header = structure.slice(delimiter + 1, opening).trim();
    const type = header.match(
      /\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/u);
    if (type) {
      const closing = matchingBrace(structure, opening);
      if (opening < index && closing >= index)
        scopes.push({ closing, name: type[1], opening });
    }
    delimiter = opening;
  }
  return scopes.sort((left, right) => left.opening - right.opening);
}

function methodScopes(source) {
  const structure = maskJava(source, true);
  const commentsMasked = maskJava(source, false);
  const packageName = source.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
  const typeNames = [...structure.matchAll(/\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/gu)]
    .map((match) => match[1]);
  const primaryType = typeNames[0] ?? basename('Unknown.java', '.java');
  const controls = new Set(['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try']);
  const typeScopes = [];
  let typeDelimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') typeDelimiter = opening;
      continue;
    }
    const header = structure.slice(typeDelimiter + 1, opening).trim();
    const typeMatch = header.match(/\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/u);
    if (typeMatch) {
      const closing = matchingBrace(structure, opening);
      if (closing > opening)
        typeScopes.push({ closing, name: typeMatch[1], opening });
    }
    typeDelimiter = opening;
  }
  const scopes = [];
  let delimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') delimiter = opening;
      continue;
    }
    const header = structure.slice(delimiter + 1, opening).trim();
    let method = null;
    if (header && !header.includes('->')) {
      const throwsRemoved = header.replace(/\bthrows\b[\s\S]*$/u, '').trim();
      if (throwsRemoved.endsWith(')')) {
        let depth = 0;
        let parameterOpening = -1;
        for (let index = throwsRemoved.length - 1; index >= 0; index--) {
          if (throwsRemoved[index] === ')') depth++;
          else if (throwsRemoved[index] === '(' && --depth === 0) {
            parameterOpening = index;
            break;
          }
        }
        if (parameterOpening >= 0) {
          const before = throwsRemoved.slice(0, parameterOpening);
          const nameMatch = before.match(/([A-Za-z_$][\w$]*)\s*$/u);
          if (nameMatch && !controls.has(nameMatch[1])) {
            const nameOffset = before.lastIndexOf(nameMatch[1]);
            const prior = before.slice(0, nameOffset).trimEnd();
            if (!prior.endsWith('.') && !prior.endsWith('::')
                && !/\b(?:class|record|interface|enum)\b/u.test(prior)
                && !/\b(?:return|new|throw)\s*$/u.test(prior))
              method = nameMatch[1];
          }
        }
      } else {
        const compact = header.match(/(?:^|\s)([A-Za-z_$][\w$]*)\s*$/u)?.[1];
        if (compact && typeNames.includes(compact)
            && !/\b(?:class|record|interface|enum)\b/u.test(header)
            && !/[=().]/u.test(header)) method = compact;
      }
    }
    const closing = matchingBrace(structure, opening);
    if (method && closing > opening) {
      const containingTypes = typeScopes.filter((scope) =>
        scope.opening < opening && scope.closing > closing)
        .sort((left, right) => left.opening - right.opening);
      const ownerTypes = containingTypes.map((scope) => scope.name);
      scopes.push({
        body: commentsMasked.slice(opening + 1, closing),
        line: source.slice(0, opening).split(/\r?\n/u).length,
        method,
        owner: `${packageName}.${ownerTypes.length === 0
          ? primaryType : ownerTypes.join('.')}`,
      });
    }
    delimiter = opening;
  }
  return scopes;
}

function matcherIds(body) {
  const ids = [];
  if (/\b(?:requireInboundMetadataFields|rejectUnknown(?:Keys|Fields))\s*\(/su.test(body))
    ids.push('OPEN-MATCH-001');
  if (/\b(?:validateObjectMembers|requireStringValue|validateAllowed(?:Keys|Fields))\s*\([\s\S]{0,360}?\bSet\.of\s*\(/u.test(body)
      || /\b(?:optionalStringArrayValues|requireStringValue)\s*\([\s\S]{0,360}?,\s*[A-Z][A-Z0-9_]*\s*\)/u.test(body)
      || /\bSet\.of\s*\([\s\S]{0,360}?\)\s*\.containsAll\s*\([\s\S]{0,120}?\.keySet\s*\(\s*\)\s*\)/u.test(body)
      || /\b(?:[A-Za-z_$][\w$]*\.)*(?:members\s*\(\s*\)\.)?keySet\s*\(\s*\)\.equals\s*\(\s*[A-Z][A-Z0-9_]*FIELDS\s*\)/u.test(body))
    ids.push('OPEN-MATCH-002');
  if (/\bswitch\s*\(\s*(?:(?:[\w$]+\.)*method\s*\(\s*\)|(?:method|type|wireValue|capability|coreCapability))\s*\)/u.test(body)
      || /\bswitch\s*\(\s*requireNonNull\s*\(\s*(?:method|type|wireValue|capability|coreCapability)\s*\)\s*\)/u.test(body)
      || /\b[A-Z][A-Z0-9_]*(?:KEYWORDS|METHODS|CAPABILITIES|CAPABILITY_NAMES|ROLES)\.contains(?:All)?\s*\(\s*(?:method|name|capability|wireValue|type|keyword|allowedMethods)\s*\)/u.test(body)
      || /\b[A-Z][A-Z0-9_]*(?:KEYWORDS|METHODS|CAPABILITIES|CAPABILITY_NAMES|ROLES)\.contains(?:All)?\s*\(\s*requireNonNull\s*\(\s*(?:method|clientRequestMethod|name|capability|wireValue|type|keyword|allowedMethods)\s*\)\s*\)/u.test(body)
      || /\bfor\s*\([^:]+:\s*(?:[A-Za-z_$][\w$]*\.)?values\s*\(\s*\)\s*\)[\s\S]{0,800}?\.(?:wireValue\s*\(\s*\)|schemaName)\.equals\s*\(\s*(?:wireValue|name)\s*\)[\s\S]{0,800}?(?:throw\s+|Optional\.empty\s*\(\s*\))/u.test(body))
    ids.push('OPEN-MATCH-003');
  const literalFirst = [...body.matchAll(/"(?:\\.|[^"\\])+"\s*\.equals(?:IgnoreCase)?\s*\(\s*(?:(?:[A-Za-z_$][\w$]*\.)*method\s*\(\s*\)|(?:method|name|capability))\s*\)/gu)].length;
  const selectorFirst = [...body.matchAll(/\b(?:method|name|capability)\s*\.equals(?:IgnoreCase)?\s*\(\s*"(?:\\.|[^"\\])+"\s*\)/gu)].length;
  if (literalFirst + selectorFirst >= 2
      && /(?:throw\s+|invalidParams\s*\(|methodNotFound\s*\(|Optional\.empty\s*\(|return\s+[\s\S]{1,500}?;)/u.test(body))
    ids.push('OPEN-MATCH-004');
  return ids;
}

export function deriveOpennessCandidates(root) {
  const normalizedRoot = resolve(root);
  const candidates = [];
  const keys = new Map();
  for (const path of javaFiles(normalizedRoot)) {
    const file = relative(normalizedRoot, path).split(sep).join('/');
    const source = readCandidateUtf8(normalizedRoot, file,
      `openness source ${file}`);
    for (const scope of methodScopes(source)) {
      const matchedRuleIds = matcherIds(scope.body);
      if (matchedRuleIds.length > 0) {
        const matcherRuleId = MATCHER_PRIORITY.find((id) =>
          matchedRuleIds.includes(id));
        const key = `${file}#${scope.owner}#${scope.method}`;
        if (keys.has(key))
          fail(`Openness scanner found an ambiguous duplicate method key at lines ${keys.get(key)} and ${scope.line}: ${key}.`);
        keys.set(key, scope.line);
        candidates.push({
          file,
          key,
          line: scope.line,
          matcherRuleId,
          method: scope.method,
          owner: scope.owner,
        });
      }
    }
  }
  return candidates.sort((left, right) => asciiCompare(left.key, right.key));
}

function verifyReviewedExclusion(root, rows) {
  if (!Array.isArray(rows) || rows.length !== 1)
    fail('Openness inventory must contain exactly one reviewed exclusion.');
  const row = rows[0];
  exactKeys(row, [
    'id', 'file', 'owner', 'field', 'consumer', 'rationale',
  ], 'reviewedExclusions[0]');
  const expected = {
    consumer: 'metricMethod(java.lang.String)',
    field: 'com.soklet.DefaultMcpServer#BOUNDED_METRIC_METHODS',
    file: 'src/main/java/com/soklet/DefaultMcpServer.java',
    id: 'OPEN-EX-001',
    owner: 'com.soklet.DefaultMcpServer',
    rationale: 'Bounds observability cardinality only; it does not accept, route, validate, or serialize a protocol method.',
  };
  exactValue(row, expected, 'OPEN-EX-001');
  const source = readCandidateUtf8(root, row.file, 'OPEN-EX-001 source sentinel');
  const structure = maskJava(source, true);
  const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1];
  const fieldDeclarations = [...structure.matchAll(
    /\bprivate\s+static\s+final\s+Set\s*<\s*(?:@NonNull\s+)?String\s*>\s+BOUNDED_METRIC_METHODS\s*=\s*Set\.of\s*\(/gu)];
  const methodDeclarations = [...structure.matchAll(
    /\bprivate\s+static\s+String\s+metricMethod\s*\(\s*(?:@NonNull\s+)?String\s+jsonRpcMethod\s*\)\s*\{/gu)];
  const fieldReferences = structure.match(/\bBOUNDED_METRIC_METHODS\b/gu) ?? [];
  if (fieldDeclarations.length !== 1 || methodDeclarations.length !== 1
      || fieldReferences.length !== 2)
    fail('OPEN-EX-001 is missing, moved, duplicated, or has an unexpected consumer.');
  const expectedOwner = ['DefaultMcpServer'];
  const fieldScopes = containingTypeScopes(structure, fieldDeclarations[0].index);
  const methodTypeScopes = containingTypeScopes(structure,
    methodDeclarations[0].index);
  const fieldOwners = fieldScopes.map((scope) => scope.name);
  const methodOwners = methodTypeScopes.map((scope) => scope.name);
  if (packageName !== 'com.soklet'
      || JSON.stringify(fieldOwners) !== JSON.stringify(expectedOwner)
      || JSON.stringify(methodOwners) !== JSON.stringify(expectedOwner)
      || fieldScopes[0].opening !== methodTypeScopes[0].opening
      || fieldScopes[0].closing !== methodTypeScopes[0].closing)
    fail('OPEN-EX-001 field and consumer must remain on exact owner com.soklet.DefaultMcpServer.');
  const methodOpening = structure.indexOf('{',
    methodDeclarations[0].index + methodDeclarations[0][0].length - 1);
  const methodClosing = matchingBrace(structure, methodOpening);
  const methodBody = structure.slice(methodOpening + 1, methodClosing);
  if (methodClosing < 0
      || !/^\s*return\s+BOUNDED_METRIC_METHODS\.contains\s*\(\s*requireNonNull\s*\(\s*jsonRpcMethod\s*\)\s*\)\s*\?\s*jsonRpcMethod\s*:\s*McpMetricsEvent\.UNRECOGNIZED_JSON_RPC_METHOD\s*;\s*$/u.test(methodBody))
    fail('OPEN-EX-001 no longer has the exact bounded-metrics use.');
}

function verifyOpenness(root, knownNi) {
  const { value } = assertCanonicalJson(root, OPENNESS_PATH,
    'protocol-openness inventory');
  exactKeys(value, [
    'formatVersion', 'productionProfile', 'scanRoots', 'matcherRules',
    'validators', 'reviewedExclusions',
  ], 'protocol-openness inventory');
  if (value.formatVersion !== 1 || value.productionProfile !== '2026-07-28')
    fail('Protocol-openness format or production profile is invalid.');
  exactValue(value.scanRoots, SCAN_ROOTS, 'Openness scan roots');
  exactValue(value.matcherRules, MATCHER_RULES, 'Openness matcher rules');
  verifyReviewedExclusion(root, value.reviewedExclusions);
  if (!Array.isArray(value.validators)) fail('Openness validators must be an array.');
  const ids = new Set();
  const keys = new Set();
  const matcherIds = new Set(MATCHER_RULES.map((rule) => rule.id));
  for (const [index, row] of value.validators.entries()) {
    exactKeys(row, [
      'id', 'key', 'owner', 'file', 'method', 'matcherRuleId',
      'closureAuthority', 'conformanceRows', 'compatibleOptionalMemberBehavior',
      'phase', 'roadmapNegativeInventoryKeys',
    ], `validators[${index}]`);
    if (!OPEN_ID.test(row.id) || ids.has(row.id))
      fail(`Openness validator ID is malformed or duplicated: ${String(row.id)}.`);
    ids.add(row.id);
    for (const field of ['key', 'owner', 'file', 'method', 'compatibleOptionalMemberBehavior'])
      nonblank(row[field], `${row.id}.${field}`);
    const expectedKey = `${row.file}#${row.owner}#${row.method}`;
    if (row.key !== expectedKey || keys.has(row.key))
      fail(`${row.id} has a malformed or duplicate stable key.`);
    keys.add(row.key);
    if (!matcherIds.has(row.matcherRuleId)) fail(`${row.id} has an unknown matcher rule.`);
    if (!CLOSURE_AUTHORITIES.has(row.closureAuthority))
      fail(`${row.id} has an invalid closure authority.`);
    if (!PHASES.has(row.phase)) fail(`${row.id} has an invalid phase.`);
    if (!Array.isArray(row.conformanceRows) || row.conformanceRows.length === 0
        || new Set(row.conformanceRows).size !== row.conformanceRows.length)
      fail(`${row.id}.conformanceRows must be a nonempty unique array.`);
    row.conformanceRows.forEach((item) => nonblank(item, `${row.id}.conformanceRows item`));
    if (!Array.isArray(row.roadmapNegativeInventoryKeys)
        || new Set(row.roadmapNegativeInventoryKeys).size
          !== row.roadmapNegativeInventoryKeys.length)
      fail(`${row.id}.roadmapNegativeInventoryKeys must be a unique array.`);
    for (const key of row.roadmapNegativeInventoryKeys) {
      if (!knownNi.has(key)) fail(`${row.id} references unknown negative inventory ${key}.`);
    }
  }
  const actualCandidates = deriveOpennessCandidates(root);
  const actualKeys = actualCandidates.map((row) => row.key);
  const inventoriedKeys = value.validators.map((row) => row.key);
  if (JSON.stringify(inventoriedKeys) !== JSON.stringify(actualKeys)) {
    const omitted = actualCandidates.filter((row) => !keys.has(row.key))
      .map((row) => `${row.key}@${row.matcherRuleId}`);
    const extra = inventoriedKeys.filter((key) => !actualKeys.includes(key));
    fail(`Openness inventory differs from source derivation or derived ASCII key order; omitted=[${omitted.join(', ')}], extra=[${extra.join(', ')}].`);
  }
  const inventoriedMatcherByKey = new Map(value.validators.map((row) =>
    [row.key, row.matcherRuleId]));
  const mismatchedMatchers = actualCandidates.filter((row) =>
    inventoriedMatcherByKey.get(row.key) !== row.matcherRuleId)
    .map((row) => `${row.key}: expected ${row.matcherRuleId}, found ${inventoriedMatcherByKey.get(row.key)}`);
  if (mismatchedMatchers.length > 0)
    fail(`Openness matcher classification differs from source derivation: ${mismatchedMatchers.join('; ')}.`);
  const rendered = renderOpennessInventory(value);
  if (readCandidateUtf8(root, OPENNESS_RENDERING_PATH,
    'openness rendering') !== rendered)
    fail(`Openness rendering is stale; regenerate ${OPENNESS_RENDERING_PATH}.`);
  return { rendered, value };
}

export function renderOpennessInventory(inventory) {
  const lines = [
    '# MCP protocol openness inventory — 2026-07-28',
    '',
    'Generated deterministically by `scripts/verify-mcp-roadmap-readiness.mjs` from',
    '`conformance/mcp-protocol-openness-inventory.json`. Do not edit by hand.',
    '',
    `Production profile: \`${inventory.productionProfile}\``,
    '',
    '## Scan contract',
    '',
    ...inventory.scanRoots.map((root) => `- \`${root}\``),
    '',
    '| Matcher | Family | Description |',
    '| --- | --- | --- |',
    ...inventory.matcherRules.map((rule) =>
      `| ${rule.id} | ${rule.family} | ${markdown(rule.description)} |`),
    '',
    '## Closed validators',
    '',
    '| ID | Owner / method | Matcher | Authority | Phase | Rows | Same-revision optional member | Roadmap |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ...inventory.validators.map((row) =>
      `| ${row.id} | \`${row.owner}#${row.method}\`<br>\`${row.file}\` | ${row.matcherRuleId} | ${row.closureAuthority} | ${row.phase} | ${row.conformanceRows.join(', ')} | ${markdown(row.compatibleOptionalMemberBehavior)} | ${row.roadmapNegativeInventoryKeys.length === 0 ? '—' : row.roadmapNegativeInventoryKeys.join(', ')} |`),
    '',
    '## Reviewed exclusions',
    '',
    '| ID | Sentinel | Consumer | Rationale |',
    '| --- | --- | --- | --- |',
    ...inventory.reviewedExclusions.map((row) =>
      `| ${row.id} | \`${row.field}\`<br>\`${row.file}\` | \`${row.consumer}\` | ${markdown(row.rationale)} |`),
    '',
    `Total: ${inventory.validators.length} closed-validator classifications and ${inventory.reviewedExclusions.length} reviewed exclusion.`,
    '',
  ];
  return lines.join('\n');
}

export function verifyCandidateRoot(root) {
  const normalizedRoot = resolve(root);
  const planning = verifyPlanningAuthority(normalizedRoot);
  const roadmap = verifyRoadmap(normalizedRoot, planning);
  const knownNi = new Set(roadmap.value.negativeInventory.map((row) => row.id));
  const openness = verifyOpenness(normalizedRoot, knownNi);
  const activeText = verifyPublicEvolutionActiveText(normalizedRoot);
  return {
    activeTextRuleCount: activeText.ruleCount,
    deferredFeatureCount: roadmap.value.deferredFeatures.length,
    negativeInventoryCount: roadmap.value.negativeInventory.length,
    opennessValidatorCount: openness.value.validators.length,
    planningAuthoritySha256: planning.sha256,
  };
}

function parseArguments(argv) {
  let mode = 'candidate';
  let root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument === '--mode') mode = argv[++index];
    else if (argument === '--root') root = resolve(argv[++index]);
    else if (argument === '--external-root')
      fail('Candidate mode rejects --external-root and never reads sibling bytes.');
    else fail(`Unknown argument: ${argument}.`);
  }
  if (mode !== 'candidate')
    fail(`Unsupported roadmap-readiness mode: ${String(mode)}.`);
  return { root };
}

const isMain = process.argv[1]
  && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));
if (isMain) {
  try {
    const { root } = parseArguments(process.argv.slice(2));
    const result = verifyCandidateRoot(root);
    console.log(`MCP roadmap readiness verification passed: ${result.negativeInventoryCount} negative-inventory rows, ${result.deferredFeatureCount} deferred features, ${result.opennessValidatorCount} openness classifications, ${result.activeTextRuleCount} active-text rules.`);
  } catch (error) {
    console.error(`MCP roadmap readiness verification failed: ${error.message}`);
    process.exitCode = 1;
  }
}
