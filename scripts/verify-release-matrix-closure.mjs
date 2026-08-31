#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
  realpathSync,
} from 'node:fs';
import {
  dirname,
  isAbsolute,
  join,
  posix,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const EXPECTED_FORMAT_VERSION = 1;
const EXPECTED_PROTOCOL_VERSION = '2026-07-28';
const EXPECTED_SOURCE_MATRIX_PATH = 'mcp/MCP_CONFORMANCE_MATRIX.md';
const EXPECTED_SOURCE_MATRIX_LAST_UPDATED = '2026-08-28';
const EXPECTED_SOURCE_MATRIX_SHA256 =
  '509bf61c509b37549f82d2989aadb44126e9ed6a1dd7456f0790e6c4ad323be7';
const EXPECTED_ROW_COUNT = 263;
const EXPECTED_ROW_IDS_SHA256 =
  'd7a55f3218e4ea8d18e2f6295f56d9b9b70ecdba9deb8be5a624bae3a9b647b0';
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ROW_ID_PATTERN = /^(?:MCP-[A-Z0-9]+-\d{3}|SOK-[A-Z0-9]+-\d{3}|AMB-\d{3})$/;
const DISPOSITIONS = Object.freeze([
  'APPLICATION_OWNED',
  'CORE_COMPLETE',
  'NOT_APPLICABLE',
  'RELEASE_GATED',
  'UNRESOLVED',
]);
const DISPOSITION_SET = new Set(DISPOSITIONS);
const EXPECTED_GATE_IDS = Object.freeze([
  'candidate-build',
  'core-jdk-21',
  'core-jdk-25',
  'isolated-install',
  'api-freeze',
  'candidate-javadocs',
  'static-analysis',
  'spotbugs',
  'schema-replay',
  'fuzz-replay',
  'fuzz-nightly-history',
  'soak-smoke',
  'soak-nightly-history',
  'release-soak',
  'localization-fleet',
  'operational-history',
  'release-scans',
  'mcp-benchmarks',
  'matrix-closure',
  'candidate-conformance',
  'candidate-localization',
  'barebones-app',
  'soklet-servlet-javax',
  'soklet-servlet-jakarta',
  'toystore-app',
  'soklet-otel',
  'soklet-website',
  'typescript-interop',
  'go-interop',
]);
const EXPECTED_NOT_APPLICABLE_IDS = new Set([
  'MCP-BASE-027',
  'MCP-VER-005',
  'MCP-CAP-003',
  'MCP-CAP-005',
  'MCP-HTTP-017',
  'MCP-TOOL-008',
  'MCP-AUTH-001',
  'MCP-AUTH-008',
  'MCP-AUTH-009',
  'SOK-RATE-002',
  'SOK-NA-001',
  'SOK-NA-002',
  'SOK-NA-003',
  'SOK-NA-004',
  'SOK-NA-005',
  'SOK-NA-006',
  'SOK-NA-007',
  'SOK-NA-008',
  'SOK-NA-009',
]);
const EXPECTED_APPLICATION_OWNED_IDS = new Set([
  'MCP-BASE-015',
  'MCP-PROMPT-006',
  'MCP-RESOURCE-006',
  'MCP-RESOURCE-007',
  'MCP-PAGE-004',
  'MCP-PAGE-006',
  'MCP-PAGE-007',
  'MCP-AUTH-002',
  'MCP-AUTH-007',
  'MCP-ELICIT-003',
  'SOK-L10N-007',
  'AMB-004',
]);

const TOP_LEVEL_KEYS = Object.freeze([
  'formatVersion',
  'protocolVersion',
  'releaseVersion',
  'sourceMatrixPath',
  'sourceMatrixLastUpdated',
  'sourceMatrixSha256',
  'releaseGateUniverse',
  'rows',
]);
const ROW_KEYS = Object.freeze([
  'id',
  'disposition',
  'evidence',
  'releaseGates',
  'reason',
]);
const FINITE_BOUND_INVENTORY_PATH = 'conformance/mcp-finite-bound-inventory.json';
const FINITE_BOUND_TOP_LEVEL_KEYS = Object.freeze([
  'bounds',
  'formatVersion',
  'matcherRules',
  'productionProfile',
  'releaseTarget',
  'reviewedExclusions',
  'scanRoots',
]);
const FINITE_BOUND_KEYS = Object.freeze([
  'boundaryTests',
  'category',
  'deterministicFailure',
  'enforcementOwners',
  'id',
  'name',
  'positiveTests',
  'sourceOwners',
  'values',
]);
const FINITE_BOUND_SOURCE_OWNER_KEYS = Object.freeze([
  'file',
  'key',
  'matcherRuleId',
  'member',
  'owner',
]);
const FINITE_BOUND_EXCLUSION_KEYS = Object.freeze([
  'file',
  'id',
  'key',
  'matcherRuleId',
  'member',
  'owner',
  'rationale',
]);
const FINITE_BOUND_FAILURE_KEYS = Object.freeze(['contract', 'stage']);
export const FINITE_BOUND_SCAN_ROOTS = Object.freeze([
  'src/main/java/com/soklet/DefaultMcp*.java',
  'src/main/java/com/soklet/Mcp*.java',
  'src/main/java/com/soklet/SokletProcessor.java',
  'src/main/java/com/soklet/internal/mcp/**/*.java',
]);
export const FINITE_BOUND_MATCHER_RULES = Object.freeze([
  Object.freeze({
    description: 'Byte, short, int, long, BigInteger, or Duration fields explicitly declared with static and final in either modifier order whose identifier contains MAXIMUM or MINIMUM, plus derived declarations classified by FINITE-MATCH-004.',
    family: 'NAMED_LIMIT_CONSTANT',
    id: 'FINITE-MATCH-001',
  }),
  Object.freeze({
    description: 'Components named maximum*, minimum*, *Capacity, *Concurrency, *Timeout, *Deadline, *Duration, *Interval, *Resolution, *Backlog, or *BufferSize on MCP records whose type name ends in Config, Configuration, or Limits.',
    family: 'BOUND_BEARING_CONFIGURATION_COMPONENT',
    id: 'FINITE-MATCH-002',
  }),
  Object.freeze({
    description: 'Public methods on direct src/main/java/com/soklet/Mcp*.java Builder types whose method or parameter name matches the FINITE-MATCH-002 bound-name vocabulary; method members include declared parameter-type signatures.',
    family: 'PUBLIC_BOUND_CONFIGURATION',
    id: 'FINITE-MATCH-003',
  }),
  Object.freeze({
    description: 'Derived or mirrored typed constants and computed methods, production JSON-limit projections, SokletProcessor copies, the HTTP framing allowance, and the localization callback-count mirror; this family takes priority over the other families.',
    family: 'DERIVED_OR_MIRRORED_LIMIT',
    id: 'FINITE-MATCH-004',
  }),
]);
const FINITE_BOUND_MATCHER_IDS = new Set(
  FINITE_BOUND_MATCHER_RULES.map(({ id }) => id),
);
const FINITE_BOUND_ID_PATTERN = /^FINITE-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d{3}$/u;
const FINITE_BOUND_EXCLUSION_ID_PATTERN = /^FINITE-EX-\d{3}$/u;
const JAVA_OWNER_PATTERN = /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$/u;
const JAVA_MEMBER_PATTERN = /^[A-Za-z_$][\w$]*(?:\([^\r\n#]*\))?$/u;
const BOUND_NAME_PATTERN = /^(?:maximum|minimum)[A-Z].*|^.*(?:Capacity|Concurrency|Timeout|Deadline|Duration|Interval|Resolution|Backlog|BufferSize)$/u;
const TRACKED_REFERENCE_CACHE = new Map();

export class MatrixClosureVerificationError extends Error {}

function fail(message) {
  throw new MatrixClosureVerificationError(message);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertExactKeys(value, expected, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = Object.keys(value);
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    fail(`${label} keys must be exactly: ${expected.join(', ')}.`);
  }
}

function assertExactArray(actual, expected, label) {
  if (!Array.isArray(actual)
      || actual.length !== expected.length
      || actual.some((value, index) => value !== expected[index])) {
    fail(`${label} must match the frozen order exactly.`);
  }
}

export function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function readCanonicalJson(file, label) {
  if (!existsSync(file)) {
    fail(`${label} does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`${label} must be a regular non-symlink file: ${file}`);
  }
  const bytes = readFileSync(file);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes)) {
    fail(`${label} is not valid UTF-8.`);
  }
  if (text.includes('\r')) {
    fail(`${label} must use LF line endings.`);
  }
  if (!text.endsWith('\n')) {
    fail(`${label} must end with one LF.`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is malformed JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text) {
    fail(`${label} is not canonical two-space JSON.`);
  }
  return { bytes, value };
}

function nonblank(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0
      || value.includes('\r') || value.includes('\n')) {
    fail(`${label} must be a nonblank single-line string.`);
  }
}

function normalizedCandidatePath(value, label) {
  nonblank(value, label);
  if (value.includes('\\') || isAbsolute(value)
      || posix.normalize(value) !== value || value === '.'
      || value.startsWith('../') || value.includes('/../')
      || value === '.git' || value.startsWith('.git/')
      || value === 'target' || value.startsWith('target/')) {
    fail(`${label} must be a normalized candidate-relative path.`);
  }
}

function requireContainedPath(root, path, label, expectedType) {
  const normalizedRoot = resolve(root);
  const normalizedPath = resolve(path);
  const candidateRelative = relative(normalizedRoot, normalizedPath);
  if (candidateRelative.length === 0 || isAbsolute(candidateRelative)
      || candidateRelative === '..' || candidateRelative.startsWith(`..${sep}`)) {
    fail(`${label} must be contained by the finite-bound project root.`);
  }
  if (!existsSync(normalizedRoot)) {
    fail('Finite-bound project root does not exist.');
  }
  const rootStat = lstatSync(normalizedRoot);
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
    fail('Finite-bound project root must be a regular non-symlink directory.');
  }
  let current = normalizedRoot;
  const segments = candidateRelative.split(sep);
  for (const [index, segment] of segments.entries()) {
    current = join(current, segment);
    if (!existsSync(current)) fail(`${label} does not exist: ${current}`);
    const stat = lstatSync(current);
    if (stat.isSymbolicLink()) {
      fail(`${label} path must not contain symlinks: ${current}`);
    }
    if (index < segments.length - 1 && !stat.isDirectory()) {
      fail(`${label} parent must be a directory: ${current}`);
    }
    if (index === segments.length - 1
        && ((expectedType === 'file' && !stat.isFile())
          || (expectedType === 'directory' && !stat.isDirectory()))) {
      fail(`${label} must be a regular ${expectedType}: ${current}`);
    }
  }
}

function finiteBoundKey(matcherRuleId, file, owner, member) {
  return `${matcherRuleId}:${file}#${owner}#${member}`;
}

function maskJava(source) {
  const characters = source.split('');
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
      } else if (source.slice(index, index + 3) === '\"\"\"') {
        characters[index] = characters[index + 1] = characters[index + 2] = ' ';
        index += 2;
        state = 'text-block';
      } else if (current === '"') {
        characters[index] = ' ';
        state = 'string';
      } else if (current === '\'') {
        characters[index] = ' ';
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
      if (current === '\\') {
        characters[index] = ' ';
        if (index + 1 < characters.length) characters[index + 1] = ' ';
        index++;
      } else if (source.slice(index, index + 3) === '\"\"\"') {
        characters[index] = characters[index + 1] = characters[index + 2] = ' ';
        index += 2;
        state = 'code';
      } else if (current !== '\n' && current !== '\r') {
        characters[index] = ' ';
      }
    } else if (state === 'string' || state === 'character') {
      if (current === '\\') {
        characters[index] = ' ';
        if (index + 1 < characters.length) characters[index + 1] = ' ';
        index++;
      } else if ((state === 'string' && current === '"')
          || (state === 'character' && current === '\'')) {
        characters[index] = ' ';
        state = 'code';
      } else if (current !== '\n' && current !== '\r') {
        characters[index] = ' ';
      }
    }
  }
  return characters.join('');
}

function matchingDelimiter(source, opening, open, close) {
  let depth = 0;
  for (let index = opening; index < source.length; index++) {
    if (source[index] === open) depth++;
    else if (source[index] === close && --depth === 0) return index;
  }
  return -1;
}

function javaTypeScopes(structure) {
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
      /\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/u,
    );
    if (type) {
      const closing = matchingDelimiter(structure, opening, '{', '}');
      if (closing > opening) scopes.push({ closing, name: type[1], opening });
    }
    delimiter = opening;
  }
  return scopes;
}

function ownerAt(packageName, scopes, index, appendedType) {
  const names = scopes
    .filter(({ closing, opening }) => opening < index && closing > index)
    .sort((left, right) => left.opening - right.opening)
    .map(({ name }) => name);
  if (appendedType !== undefined) names.push(appendedType);
  if (packageName.length === 0 || names.length === 0) {
    fail(`Finite-bound scanner cannot resolve Java owner at source offset ${index}.`);
  }
  return `${packageName}.${names.join('.')}`;
}

function splitTopLevel(value) {
  const parts = [];
  let start = 0;
  const closingByOpening = new Map([
    ['(', ')'],
    ['[', ']'],
    ['{', '}'],
    ['<', '>'],
  ]);
  const stack = [];
  for (let index = 0; index < value.length; index++) {
    const token = value[index];
    if (closingByOpening.has(token)) stack.push(closingByOpening.get(token));
    else if (stack.at(-1) === token) stack.pop();
    else if (token === ',' && stack.length === 0) {
      parts.push(value.slice(start, index));
      start = index + 1;
    }
  }
  parts.push(value.slice(start));
  return parts;
}

function parameterName(declaration) {
  return declaration.trim().match(/([A-Za-z_$][\w$]*)\s*$/u)?.[1];
}

function methodMember(method, parameters, file, line) {
  const parameterTypes = parameters.trim().length === 0 ? []
    : splitTopLevel(parameters).map((parameter) => {
      const declaration = parameter
        .replace(/@[A-Za-z_$][\w$]*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*/gu, '')
        .replace(/\bfinal\s+/gu, '')
        .trim();
      const name = parameterName(declaration);
      if (name === undefined) {
        fail(`Finite-bound scanner cannot resolve a parameter at ${file}:${line}.`);
      }
      const type = declaration.slice(0, declaration.lastIndexOf(name))
        .trim()
        .replace(/\s+/gu, ' ');
      if (type.length === 0 || /[\r\n#()]/u.test(type)) {
        fail(`Finite-bound scanner cannot canonicalize a parameter type at ${file}:${line}.`);
      }
      return type;
    });
  return `${method}(${parameterTypes.join(',')})`;
}

function javaMethodScopes(source, structure, typeScopes) {
  const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
  const typeNames = typeScopes.map(({ name }) => name);
  const controls = new Set([
    'catch', 'for', 'if', 'switch', 'synchronized', 'try', 'while',
  ]);
  const scopes = [];
  let delimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') delimiter = opening;
      continue;
    }
    const header = structure.slice(delimiter + 1, opening).trim();
    let method;
    let parameters = '';
    let publicMethod = false;
    if (header.length > 0 && !header.includes('->')) {
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
                && !/\b(?:return|new|throw)\s*$/u.test(prior)) {
              method = nameMatch[1];
              parameters = throwsRemoved.slice(parameterOpening + 1, -1);
              publicMethod = /\bpublic\b/u.test(prior);
            }
          }
        }
      } else {
        const compact = header.match(/(?:^|\s)([A-Za-z_$][\w$]*)\s*$/u)?.[1];
        if (compact && typeNames.includes(compact)
            && !/\b(?:class|record|interface|enum)\b/u.test(header)
            && !/[=().]/u.test(header)) method = compact;
      }
    }
    const closing = matchingDelimiter(structure, opening, '{', '}');
    if (method !== undefined && closing > opening) {
      scopes.push({
        body: structure.slice(opening + 1, closing),
        line: source.slice(0, opening).split(/\r?\n/u).length,
        method,
        owner: ownerAt(packageName, typeScopes, opening),
        parameters,
        publicMethod,
      });
    }
    delimiter = opening;
  }
  return scopes;
}

function globRegularExpression(pattern) {
  let expression = '^';
  for (let index = 0; index < pattern.length; index++) {
    const token = pattern[index];
    if (token === '*' && pattern[index + 1] === '*') {
      if (pattern[index + 2] === '/') {
        expression += '(?:[^/]+/)*';
        index += 2;
      } else {
        expression += '.*';
        index++;
      }
    } else if (token === '*') {
      expression += '[^/]*';
    } else if ('\\^$+?.()|{}[]'.includes(token)) {
      expression += `\\${token}`;
    } else {
      expression += token;
    }
  }
  return new RegExp(`${expression}$`, 'u');
}

function finiteBoundJavaFiles(root, scanRoots) {
  if (!Array.isArray(scanRoots) || scanRoots.length === 0
      || new Set(scanRoots).size !== scanRoots.length) {
    fail('Finite-bound scanRoots must be a nonempty unique array.');
  }
  const sortedRoots = [...scanRoots].sort(compareAscii);
  if (scanRoots.some((value, index) => value !== sortedRoots[index])) {
    fail('Finite-bound scanRoots must be in ASCII order.');
  }
  for (const [index, pattern] of scanRoots.entries()) {
    normalizedCandidatePath(pattern, `scanRoots[${index}]`);
    if (!pattern.startsWith('src/main/java/') || !pattern.endsWith('.java')) {
      fail(`Finite-bound scan root must select Java sources below src/main/java: ${pattern}`);
    }
  }
  const sourceRoot = resolve(root, 'src/main/java');
  requireContainedPath(
    root,
    sourceRoot,
    'Finite-bound source root src/main/java',
    'directory',
  );
  const files = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name))) {
      const path = join(directory, entry.name);
      const candidateRelative = relative(root, path).split(sep).join('/');
      if (entry.isSymbolicLink()) {
        fail(`Finite-bound source tree contains a symlink: ${candidateRelative}.`);
      }
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.java')) files.push(path);
    }
  };
  visit(sourceRoot);
  const matchers = scanRoots.map(globRegularExpression);
  const matched = new Set();
  const selected = files.filter((path) => {
    const candidateRelative = relative(root, path).split(sep).join('/');
    let selectedFile = false;
    for (const [index, matcher] of matchers.entries()) {
      if (matcher.test(candidateRelative)) {
        matched.add(index);
        selectedFile = true;
      }
    }
    return selectedFile;
  });
  for (const [index, pattern] of scanRoots.entries()) {
    if (!matched.has(index)) fail(`Finite-bound scan root matches no source: ${pattern}.`);
  }
  return selected.sort(compareAscii);
}

function readJavaSource(root, path) {
  const file = relative(root, path).split(sep).join('/');
  const bytes = readFileSync(path);
  const source = bytes.toString('utf8');
  if (!Buffer.from(source, 'utf8').equals(bytes)) {
    fail(`Finite-bound source is not valid UTF-8: ${file}.`);
  }
  return { file, source };
}

function derivedInitializer(file, name, initializer) {
  if (file === 'src/main/java/com/soklet/DefaultMcpLocalizationCatalogExtractor.java'
      && name === 'MAXIMUM_SUPPORTED_CALLBACK_COUNT') return true;
  if (file === 'src/main/java/com/soklet/SokletProcessor.java'
      && (/^MAXIMUM_MCP_/u.test(name)
        || /^MCP_SCHEMA_PREFLIGHT_MAXIMUM_/u.test(name))) return true;
  return /\b[A-Z0-9_]*(?:MAXIMUM|MINIMUM)[A-Z0-9_]*\b/u.test(initializer)
    || /\.(?:maximum|minimum)[A-Z][\w$]*\s*\(/u.test(initializer)
    || /\b(?:maximum|minimum)[A-Z][\w$]*\s*\(/u.test(initializer)
    || /\.productionDefaults\s*\(/u.test(initializer);
}

function derivedMethod(scope) {
  return /^(?:maximum|minimum)[A-Z]/u.test(scope.method)
    && /(?:\bMath\.(?:addExact|max|min|multiplyExact|subtractExact)\s*\(|[-+*/])/u
      .test(scope.body)
    && /(?:\.(?:maximum|minimum)[A-Z][\w$]*\s*\(|\.productionDefaults\s*\(|\b[A-Z0-9_]*(?:MAXIMUM|MINIMUM)[A-Z0-9_]*\b)/u
      .test(scope.body);
}

export function deriveFiniteBoundCandidates(root, scanRoots) {
  const normalizedRoot = resolve(root);
  const candidates = [];
  const candidatesByKey = new Map();
  const add = (candidate) => {
    const key = finiteBoundKey(
      candidate.matcherRuleId,
      candidate.file,
      candidate.owner,
      candidate.member,
    );
    if (candidatesByKey.has(key)) {
      fail(`Finite-bound scanner found a duplicate declaration key: ${key}.`);
    }
    const complete = { ...candidate, key };
    candidatesByKey.set(key, complete);
    candidates.push(complete);
  };

  for (const path of finiteBoundJavaFiles(normalizedRoot, scanRoots)) {
    const { file, source } = readJavaSource(normalizedRoot, path);
    const structure = maskJava(source);
    const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
    const typeScopes = javaTypeScopes(structure);
    const lineAt = (index) => source.slice(0, index).split(/\r?\n/u).length;

    const finiteFieldPattern = /\b((?:(?:public|protected|private|static|final|transient|volatile)\s+|(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?)\s+)*)(?:(?:[A-Za-z_$][\w$]*\.)*(?:BigInteger|Duration)|byte|short|int|long)\s+([A-Za-z_$][\w$]*)\s*(?==|,|;)/gu;
    for (const match of structure.matchAll(finiteFieldPattern)) {
      const modifiers = match[1];
      if (!/\bstatic\b/u.test(modifiers) || !/\bfinal\b/u.test(modifiers)) continue;
      const firstName = match[2];
      const firstNameOffset = match.index + match[0].lastIndexOf(firstName);
      const declarationEnd = structure.indexOf(';', firstNameOffset);
      if (declarationEnd < 0) {
        fail(`Finite-bound scanner found an unterminated field ${firstName} in ${file}.`);
      }
      const declaration = structure.slice(firstNameOffset, declarationEnd);
      let declarationOffset = 0;
      for (const declarator of splitTopLevel(declaration)) {
        const partOffset = declaration.indexOf(declarator, declarationOffset);
        declarationOffset = partOffset + declarator.length + 1;
        const parsed = declarator.trim().match(
          /^([A-Za-z_$][\w$]*)\s*=\s*([\s\S]*)$/u,
        );
        const possibleName = declarator.trim().match(
          /^([A-Za-z_$][\w$]*)/u,
        )?.[1];
        if (parsed === null) {
          if (possibleName !== undefined
              && /(?:MAXIMUM|MINIMUM)/u.test(possibleName)) {
            fail(`Finite-bound field ${possibleName} in ${file} must have an initializer.`);
          }
          continue;
        }
        const [, name, initializer] = parsed;
        const nameOffset = firstNameOffset + partOffset
          + declarator.indexOf(name);
        const framingAllowance = file
          === 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpTransportConfiguration.java'
          && name === 'HTTP_FRAMING_ALLOWANCE_BYTES';
        const derived = derivedInitializer(file, name, initializer);
        if (!/(?:MAXIMUM|MINIMUM)/u.test(name) && !framingAllowance && !derived) {
          continue;
        }
        add({
          file,
          line: lineAt(nameOffset),
          matcherRuleId: framingAllowance || derived
            ? 'FINITE-MATCH-004' : 'FINITE-MATCH-001',
          member: name,
          owner: ownerAt(packageName, typeScopes, nameOffset),
        });
      }
    }

    const productionLimitsPattern = /\bstatic\s+final\s+(?:(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?)\s+)*(?:[A-Za-z_$][\w$]*\.)*McpJsonLimits\s+(PRODUCTION_LIMITS)\s*=/gu;
    for (const match of structure.matchAll(productionLimitsPattern)) {
      const initializerStart = match.index + match[0].length;
      const initializerEnd = structure.indexOf(';', initializerStart);
      if (initializerEnd < 0
          || !/\bMcpJsonLimits\.productionDefaults\s*\(/u.test(
            structure.slice(initializerStart, initializerEnd),
          )) continue;
      add({
        file,
        line: lineAt(match.index),
        matcherRuleId: 'FINITE-MATCH-004',
        member: match[1],
        owner: ownerAt(packageName, typeScopes, match.index),
      });
    }

    const recordPattern = /\brecord\s+([A-Za-z_$][\w$]*)\s*\(/gu;
    for (const match of structure.matchAll(recordPattern)) {
      const recordName = match[1];
      if (!/(?:Config|Configuration|Limits)$/u.test(recordName)) continue;
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      if (closing < 0) {
        fail(`Finite-bound scanner found an unterminated record ${recordName} in ${file}.`);
      }
      const owner = ownerAt(packageName, typeScopes, match.index, recordName);
      for (const component of splitTopLevel(
        structure.slice(opening + 1, closing),
      )) {
        const member = parameterName(component);
        if (member === undefined || !BOUND_NAME_PATTERN.test(member)) continue;
        add({
          file,
          line: lineAt(match.index),
          matcherRuleId: 'FINITE-MATCH-002',
          member,
          owner,
        });
      }
    }

    const methods = javaMethodScopes(source, structure, typeScopes);
    for (const scope of methods) {
      if (derivedMethod(scope)) {
        const member = methodMember(scope.method, scope.parameters, file, scope.line);
        add({
          file,
          line: scope.line,
          matcherRuleId: 'FINITE-MATCH-004',
          member,
          owner: scope.owner,
        });
        continue;
      }
      const directPublicMcpSource = /^src\/main\/java\/com\/soklet\/Mcp[^/]*\.java$/u
        .test(file);
      const builderOwner = scope.owner.endsWith('.Builder');
      const parameterNames = splitTopLevel(scope.parameters)
        .map(parameterName)
        .filter((value) => value !== undefined);
      if (directPublicMcpSource && builderOwner && scope.publicMethod
          && (BOUND_NAME_PATTERN.test(scope.method)
            || parameterNames.some((name) => BOUND_NAME_PATTERN.test(name)))) {
        const member = methodMember(scope.method, scope.parameters, file, scope.line);
        add({
          file,
          line: scope.line,
          matcherRuleId: 'FINITE-MATCH-003',
          member,
          owner: scope.owner,
        });
      }
    }
  }
  return candidates.sort((left, right) => compareAscii(left.key, right.key));
}

function validateFiniteClassification(row, label) {
  assertExactKeys(row, FINITE_BOUND_SOURCE_OWNER_KEYS, label);
  normalizedCandidatePath(row.file, `${label}.file`);
  nonblank(row.owner, `${label}.owner`);
  nonblank(row.member, `${label}.member`);
  if (!JAVA_OWNER_PATTERN.test(row.owner)) {
    fail(`${label}.owner must be an exact qualified Java owner.`);
  }
  if (!JAVA_MEMBER_PATTERN.test(row.member)) {
    fail(`${label}.member must be one exact Java declaration name.`);
  }
  if (!FINITE_BOUND_MATCHER_IDS.has(row.matcherRuleId)) {
    fail(`${label}.matcherRuleId is unknown.`);
  }
  const expectedKey = finiteBoundKey(
    row.matcherRuleId,
    row.file,
    row.owner,
    row.member,
  );
  if (row.key !== expectedKey) {
    fail(`${label}.key must be exactly ${expectedKey}.`);
  }
}

export function verifyFiniteBoundInventory(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const inventoryPath = resolve(
    options.inventoryPath
      ?? resolve(projectRoot, FINITE_BOUND_INVENTORY_PATH),
  );
  requireContainedPath(
    projectRoot,
    inventoryPath,
    'Finite-bound inventory',
    'file',
  );
  const { value: inventory } = readCanonicalJson(
    inventoryPath,
    'Finite-bound inventory',
  );
  assertExactKeys(
    inventory,
    FINITE_BOUND_TOP_LEVEL_KEYS,
    'Finite-bound inventory',
  );
  if (inventory.formatVersion !== 1
      || inventory.productionProfile !== EXPECTED_PROTOCOL_VERSION
      || inventory.releaseTarget !== '4.0.0') {
    fail('Finite-bound inventory format, profile, or release target is invalid.');
  }
  if (JSON.stringify(inventory.matcherRules)
      !== JSON.stringify(FINITE_BOUND_MATCHER_RULES)) {
    fail('Finite-bound matcherRules do not match the executable matcher contract.');
  }
  const expectedScanRoots = options.expectedScanRoots
    ?? FINITE_BOUND_SCAN_ROOTS;
  assertExactArray(
    inventory.scanRoots,
    expectedScanRoots,
    'Finite-bound scanRoots',
  );
  const candidates = deriveFiniteBoundCandidates(
    projectRoot,
    expectedScanRoots,
  );
  if (!Array.isArray(inventory.bounds) || inventory.bounds.length === 0) {
    fail('Finite-bound inventory bounds must be a nonempty array.');
  }
  const boundIds = new Set();
  const classifications = [];
  for (const [boundIndex, bound] of inventory.bounds.entries()) {
    const label = `bounds[${boundIndex}]`;
    assertExactKeys(bound, FINITE_BOUND_KEYS, label);
    if (typeof bound.id !== 'string' || !FINITE_BOUND_ID_PATTERN.test(bound.id)
        || FINITE_BOUND_EXCLUSION_ID_PATTERN.test(bound.id)
        || boundIds.has(bound.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    boundIds.add(bound.id);
    for (const field of ['category', 'name']) nonblank(bound[field], `${label}.${field}`);
    assertExactKeys(
      bound.deterministicFailure,
      FINITE_BOUND_FAILURE_KEYS,
      `${label}.deterministicFailure`,
    );
    for (const field of FINITE_BOUND_FAILURE_KEYS) {
      nonblank(
        bound.deterministicFailure[field],
        `${label}.deterministicFailure.${field}`,
      );
    }
    if (!Array.isArray(bound.sourceOwners) || bound.sourceOwners.length === 0) {
      fail(`${bound.id}.sourceOwners must be a nonempty array.`);
    }
    const sortedOwnerKeys = bound.sourceOwners.map(({ key }) => key)
      .sort(compareAscii);
    for (const [ownerIndex, owner] of bound.sourceOwners.entries()) {
      const ownerLabel = `${bound.id}.sourceOwners[${ownerIndex}]`;
      validateFiniteClassification(owner, ownerLabel);
      if (owner.key !== sortedOwnerKeys[ownerIndex]) {
        fail(`${bound.id}.sourceOwners must be in ASCII key order.`);
      }
      classifications.push({ ...owner, location: ownerLabel });
    }
  }
  if (!Array.isArray(inventory.reviewedExclusions)) {
    fail('Finite-bound reviewedExclusions must be an array.');
  }
  const exclusionIds = new Set();
  const sortedExclusionKeys = inventory.reviewedExclusions.map(({ key }) => key)
    .sort(compareAscii);
  for (const [index, exclusion] of inventory.reviewedExclusions.entries()) {
    const label = `reviewedExclusions[${index}]`;
    assertExactKeys(exclusion, FINITE_BOUND_EXCLUSION_KEYS, label);
    const classification = {
      file: exclusion.file,
      key: exclusion.key,
      matcherRuleId: exclusion.matcherRuleId,
      member: exclusion.member,
      owner: exclusion.owner,
    };
    validateFiniteClassification(classification, label);
    if (typeof exclusion.id !== 'string'
        || !FINITE_BOUND_EXCLUSION_ID_PATTERN.test(exclusion.id)
        || exclusionIds.has(exclusion.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    exclusionIds.add(exclusion.id);
    nonblank(exclusion.rationale, `${label}.rationale`);
    if (exclusion.key !== sortedExclusionKeys[index]) {
      fail('Finite-bound reviewedExclusions must be in ASCII key order.');
    }
    classifications.push({ ...classification, location: label });
  }
  const classificationsByKey = new Map();
  for (const classification of classifications) {
    if (classificationsByKey.has(classification.key)) {
      fail(`Finite-bound classification is duplicated at ${classificationsByKey.get(classification.key).location} and ${classification.location}: ${classification.key}.`);
    }
    classificationsByKey.set(classification.key, classification);
  }
  const candidatesByKey = new Map(candidates.map((candidate) =>
    [candidate.key, candidate]));
  const omitted = candidates.filter(({ key }) => !classificationsByKey.has(key));
  const extra = classifications.filter(({ key }) => !candidatesByKey.has(key));
  if (omitted.length > 0 || extra.length > 0) {
    fail(`Finite-bound inventory differs from source derivation; omitted=[${omitted.map(({ key, line }) => `${key}@${line}`).join(', ')}], extra=[${extra.map(({ key }) => key).join(', ')}].`);
  }
  return {
    candidates,
    exclusions: inventory.reviewedExclusions,
    inventory,
  };
}

function readManifest(file) {
  if (!existsSync(file)) {
    fail(`Release manifest does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`Release manifest must be a regular non-symlink file: ${file}`);
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`Release manifest is malformed JSON: ${error.message}`);
  }
  const releaseVersion = manifest?.candidate?.version;
  if (typeof releaseVersion !== 'string' || releaseVersion.length === 0) {
    fail('Release manifest candidate.version must be a nonempty string.');
  }
  if (!Array.isArray(manifest.gates)) {
    fail('Release manifest gates must be an array.');
  }
  const gateIds = manifest.gates.map((gate, index) => {
    if (gate === null || typeof gate !== 'object' || Array.isArray(gate)
        || typeof gate.id !== 'string' || gate.id.length === 0) {
      fail(`Release manifest gate ${index} has no valid id.`);
    }
    return gate.id;
  });
  if (new Set(gateIds).size !== gateIds.length) {
    fail('Release manifest contains duplicate gate IDs.');
  }
  assertExactArray(gateIds, EXPECTED_GATE_IDS, 'Release manifest gate IDs');
  return { releaseVersion, gateIds };
}

function assertContainedEvidence(projectRoot, reference, rowId, gitExecutable) {
  if (typeof reference !== 'string' || reference.length === 0) {
    fail(`Row ${rowId} contains an empty evidence reference.`);
  }
  if (reference.includes('\\') || isAbsolute(reference)
      || posix.normalize(reference) !== reference
      || reference === '.' || reference.startsWith('../')
      || reference.includes('/../')
      || reference === '.git' || reference.startsWith('.git/')
      || reference === 'target' || reference.startsWith('target/')) {
    fail(`Row ${rowId} evidence reference is not a normalized candidate-relative path: ${reference}`);
  }
  const target = resolve(projectRoot, reference);
  const lexicalRelative = relative(projectRoot, target);
  if (lexicalRelative === '..' || lexicalRelative.startsWith(`..${sep}`) || isAbsolute(lexicalRelative)) {
    fail(`Row ${rowId} evidence reference escapes the candidate: ${reference}`);
  }
  if (!existsSync(target)) {
    fail(`Row ${rowId} evidence reference does not exist: ${reference}`);
  }
  let component = projectRoot;
  for (const segment of reference.split('/')) {
    component = resolve(component, segment);
    if (lstatSync(component).isSymbolicLink()) {
      fail(`Row ${rowId} evidence reference contains a symlink: ${reference}`);
    }
  }
  const stat = lstatSync(target);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    fail(`Row ${rowId} evidence reference must name a regular file: ${reference}`);
  }
  const realRoot = realpathSync(projectRoot);
  const realTarget = realpathSync(target);
  const realRelative = relative(realRoot, realTarget);
  if (realRelative === '..' || realRelative.startsWith(`..${sep}`) || isAbsolute(realRelative)) {
    fail(`Row ${rowId} evidence reference resolves outside the candidate: ${reference}`);
  }
  const cacheKey = `${gitExecutable}\0${projectRoot}\0${reference}`;
  let tracked = TRACKED_REFERENCE_CACHE.get(cacheKey);
  if (tracked === undefined) {
    const result = spawnSync(
      gitExecutable,
      [
        '-c',
        `safe.directory=${projectRoot}`,
        '-C',
        projectRoot,
        'ls-files',
        '--error-unmatch',
        '--',
        reference,
      ],
      { encoding: 'utf8' },
    );
    if (result.error !== undefined) {
      fail(`Unable to inspect candidate evidence tracking: ${result.error.message}`);
    }
    tracked = result.status === 0;
    TRACKED_REFERENCE_CACHE.set(cacheKey, tracked);
  }
  if (!tracked) {
    fail(`Row ${rowId} evidence reference is not tracked by the candidate: ${reference}`);
  }
}

function validateReason(row) {
  if (typeof row.reason !== 'string' || row.reason.includes('\r') || row.reason.includes('\n')) {
    fail(`Row ${row.id} reason must be a single-line string.`);
  }
  const requiresReason = row.disposition === 'APPLICATION_OWNED'
    || row.disposition === 'RELEASE_GATED'
    || row.disposition === 'UNRESOLVED';
  if (requiresReason && row.reason.trim().length === 0) {
    fail(`Row ${row.id} disposition ${row.disposition} requires a reason.`);
  }
  if (!requiresReason && row.reason !== '') {
    fail(`Row ${row.id} disposition ${row.disposition} requires an empty reason.`);
  }
  if (row.reason.length > 320) {
    fail(`Row ${row.id} reason exceeds 320 characters.`);
  }
}

function validateRegistry(registry, projectRoot, manifest, gitExecutable) {
  assertExactKeys(registry, TOP_LEVEL_KEYS, 'Matrix-closure registry');
  if (registry.formatVersion !== EXPECTED_FORMAT_VERSION) {
    fail(`Matrix-closure registry formatVersion must be ${EXPECTED_FORMAT_VERSION}.`);
  }
  if (registry.protocolVersion !== EXPECTED_PROTOCOL_VERSION) {
    fail(`Matrix-closure registry protocolVersion must be ${EXPECTED_PROTOCOL_VERSION}.`);
  }
  if (registry.releaseVersion !== manifest.releaseVersion) {
    fail('Matrix-closure registry releaseVersion does not match manifest candidate.version.');
  }
  if (registry.sourceMatrixPath !== EXPECTED_SOURCE_MATRIX_PATH
      || registry.sourceMatrixLastUpdated !== EXPECTED_SOURCE_MATRIX_LAST_UPDATED
      || registry.sourceMatrixSha256 !== EXPECTED_SOURCE_MATRIX_SHA256
      || !SHA256_PATTERN.test(registry.sourceMatrixSha256)) {
    fail('Matrix-closure registry source-matrix provenance does not match the reviewed snapshot.');
  }
  assertExactArray(
    registry.releaseGateUniverse,
    manifest.gateIds,
    'Matrix-closure registry releaseGateUniverse',
  );
  if (!Array.isArray(registry.rows)) {
    fail('Matrix-closure registry rows must be an array.');
  }
  if (registry.rows.length !== EXPECTED_ROW_COUNT) {
    fail(`Matrix-closure registry must contain exactly ${EXPECTED_ROW_COUNT} rows.`);
  }

  const rowIds = [];
  const seenIds = new Set();
  const unresolvedRows = [];
  const dispositionCounts = Object.fromEntries(DISPOSITIONS.map((value) => [value, 0]));
  const releaseGateDependencies = new Set();
  const gateOrdinals = new Map(manifest.gateIds.map((id, index) => [id, index]));

  for (const [index, row] of registry.rows.entries()) {
    assertExactKeys(row, ROW_KEYS, `Matrix-closure row ${index}`);
    if (typeof row.id !== 'string' || !ROW_ID_PATTERN.test(row.id)) {
      fail(`Matrix-closure row ${index} has a malformed ID.`);
    }
    if (seenIds.has(row.id)) {
      fail(`Matrix-closure registry contains duplicate row ID ${row.id}.`);
    }
    seenIds.add(row.id);
    rowIds.push(row.id);

    if (!DISPOSITION_SET.has(row.disposition)) {
      fail(`Row ${row.id} has unknown disposition ${String(row.disposition)}.`);
    }
    if (EXPECTED_NOT_APPLICABLE_IDS.has(row.id) !== (row.disposition === 'NOT_APPLICABLE')) {
      fail(`Row ${row.id} does not match the frozen NOT_APPLICABLE classification.`);
    }
    if (EXPECTED_APPLICATION_OWNED_IDS.has(row.id)
        !== (row.disposition === 'APPLICATION_OWNED')) {
      fail(`Row ${row.id} does not match the frozen APPLICATION_OWNED classification.`);
    }
    dispositionCounts[row.disposition] += 1;
    validateReason(row);

    if (!Array.isArray(row.evidence) || row.evidence.length === 0) {
      fail(`Row ${row.id} must have at least one evidence reference.`);
    }
    if (new Set(row.evidence).size !== row.evidence.length) {
      fail(`Row ${row.id} contains duplicate evidence references.`);
    }
    const sortedEvidence = [...row.evidence].sort(compareAscii);
    if (row.evidence.some((value, evidenceIndex) => value !== sortedEvidence[evidenceIndex])) {
      fail(`Row ${row.id} evidence references must be in ASCII order.`);
    }
    for (const reference of row.evidence) {
      assertContainedEvidence(projectRoot, reference, row.id, gitExecutable);
    }
    if ((row.disposition === 'CORE_COMPLETE' || row.disposition === 'RELEASE_GATED')
        && row.evidence.every((reference) => reference.endsWith('.md')
          || reference === 'release/release-validation-manifest.json')) {
      fail(`Row ${row.id} requires substantive implementation, test, or harness evidence.`);
    }

    if (!Array.isArray(row.releaseGates)) {
      fail(`Row ${row.id} releaseGates must be an array.`);
    }
    if (new Set(row.releaseGates).size !== row.releaseGates.length) {
      fail(`Row ${row.id} contains duplicate release-gate dependencies.`);
    }
    let priorOrdinal = -1;
    for (const gateId of row.releaseGates) {
      if (!gateOrdinals.has(gateId)) {
        fail(`Row ${row.id} depends on unknown release gate ${String(gateId)}.`);
      }
      if (gateId === 'matrix-closure') {
        fail(`Row ${row.id} may not depend on the matrix-closure gate itself.`);
      }
      const ordinal = gateOrdinals.get(gateId);
      if (ordinal <= priorOrdinal) {
        fail(`Row ${row.id} releaseGates must follow manifest order.`);
      }
      priorOrdinal = ordinal;
      releaseGateDependencies.add(gateId);
    }
    if (row.disposition === 'RELEASE_GATED' && row.releaseGates.length === 0) {
      fail(`Row ${row.id} disposition RELEASE_GATED requires a release-gate dependency.`);
    }
    if (row.disposition !== 'RELEASE_GATED' && row.releaseGates.length !== 0) {
      fail(`Row ${row.id} disposition ${row.disposition} may not name release gates.`);
    }
    if (row.disposition === 'UNRESOLVED') {
      unresolvedRows.push({ id: row.id, reason: row.reason });
    }
  }

  const rowIdsSha256 = sha256(`${rowIds.join('\n')}\n`);
  if (rowIdsSha256 !== EXPECTED_ROW_IDS_SHA256) {
    fail('Matrix-closure row IDs are missing, extra, renamed, or out of frozen order.');
  }
  const orderedDependencies = manifest.gateIds.filter((id) => releaseGateDependencies.has(id));
  return {
    dispositionCounts,
    orderedDependencies,
    rowIdsSha256,
    unresolvedRows,
  };
}

export function verifyMatrixClosure(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const finiteBoundProjectRoot = resolve(
    options.finiteBoundProjectRoot ?? projectRoot,
  );
  const finiteBoundInventoryPath = resolve(
    options.finiteBoundInventoryPath
      ?? resolve(finiteBoundProjectRoot, FINITE_BOUND_INVENTORY_PATH),
  );
  const finiteBoundExpectedScanRoots = options.finiteBoundExpectedScanRoots
    ?? FINITE_BOUND_SCAN_ROOTS;
  const registryPath = resolve(
    options.registryPath ?? resolve(projectRoot, 'release/mcp-conformance-matrix-closure.json'),
  );
  const manifestPath = resolve(
    options.manifestPath ?? resolve(projectRoot, 'release/release-validation-manifest.json'),
  );
  const manifest = readManifest(manifestPath);
  verifyFiniteBoundInventory({
    expectedScanRoots: finiteBoundExpectedScanRoots,
    inventoryPath: finiteBoundInventoryPath,
    projectRoot: finiteBoundProjectRoot,
  });
  const gitExecutable = options.gitExecutable ?? 'git';
  if (typeof gitExecutable !== 'string' || gitExecutable.length === 0) {
    fail('gitExecutable must be a nonempty string.');
  }
  const { bytes, value: registry } = readCanonicalJson(
    registryPath,
    'Matrix-closure registry',
  );
  const validated = validateRegistry(registry, projectRoot, manifest, gitExecutable);
  const status = validated.unresolvedRows.length === 0 ? 'PASSED' : 'FAILED';
  const report = {
    formatVersion: EXPECTED_FORMAT_VERSION,
    protocolVersion: registry.protocolVersion,
    releaseVersion: registry.releaseVersion,
    sourceMatrixPath: registry.sourceMatrixPath,
    sourceMatrixLastUpdated: registry.sourceMatrixLastUpdated,
    sourceMatrixSha256: registry.sourceMatrixSha256,
    status,
    rowCount: registry.rows.length,
    rowIdsSha256: validated.rowIdsSha256,
    registrySha256: sha256(bytes),
    dispositionCounts: validated.dispositionCounts,
    releaseGateDependencies: validated.orderedDependencies,
    unresolvedRows: validated.unresolvedRows,
    rows: registry.rows,
  };
  return {
    exitCode: status === 'PASSED' ? 0 : 1,
    report,
    reportText: canonicalJson(report),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 2) {
    console.error('Usage: node scripts/verify-release-matrix-closure.mjs');
    process.exitCode = 2;
  } else {
    try {
      const result = verifyMatrixClosure();
      process.stdout.write(result.reportText);
      if (result.exitCode !== 0) {
        console.error(
          `Matrix closure failed: ${result.report.unresolvedRows.length} unresolved row(s).`,
        );
      }
      process.exitCode = result.exitCode;
    } catch (error) {
      console.error(`Matrix closure verification failed: ${error.message}`);
      process.exitCode = 2;
    }
  }
}
