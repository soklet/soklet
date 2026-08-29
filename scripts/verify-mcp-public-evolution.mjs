#!/usr/bin/env node

import {
  readFileSync,
  existsSync,
  lstatSync,
  realpathSync,
  statSync,
  readdirSync,
} from 'node:fs';
import { isAbsolute, join, relative, resolve, sep } from 'node:path';
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
const ACTIVE_TEXT_EXPECTATIONS = new Set(['zero', 'nonzero-with-notice']);
const ACTIVE_TEXT_MATCHER_KINDS = new Set(['literal', 'regex']);
const ACTIVE_TEXT_ROLES = new Set([
  'defaultPath',
  'compatibility',
  'migration',
  'security',
  'factualSupport',
]);
const ACTIVE_TEXT_SCOPE_KINDS = new Set([
  'wholeFile',
  'headingSubtree',
  'fencedBlock',
]);
const ACTIVE_TEXT_REQUIRED_RULE_EXPECTATIONS = new Map([
  ['PROFILE-001', 'nonzero-with-notice'],
  ['PROFILE-002', 'zero'],
  ['PROFILE-003', 'nonzero-with-notice'],
  ['AUTH-001', 'zero'],
  ['AUTH-002', 'nonzero-with-notice'],
  ['AUTH-003', 'nonzero-with-notice'],
  ['COUNT-001', 'nonzero-with-notice'],
  ['CACHE-001', 'zero'],
  ['CACHE-002', 'nonzero-with-notice'],
  ['TRANSPORT-001', 'zero'],
  ['TRANSPORT-002', 'nonzero-with-notice'],
  ['DPOP-001', 'zero'],
  ['DPOP-002', 'nonzero-with-notice'],
  ['EXTENSION-001', 'zero'],
  ['EXTENSION-002', 'nonzero-with-notice'],
  ['LIFECYCLE-001', 'nonzero-with-notice'],
  ['LIFECYCLE-002', 'zero'],
  ['LIFECYCLE-003', 'zero'],
  ['LIFECYCLE-004', 'nonzero-with-notice'],
  ['LIFECYCLE-005', 'nonzero-with-notice'],
  ['DCR-001', 'nonzero-with-notice'],
  ['EXAMPLE-001', 'nonzero-with-notice'],
]);
const ACTIVE_TEXT_GOVERNED_DOCUMENTS = Object.freeze([
  Object.freeze({ path: 'MCP.md', role: 'factualSupport' }),
  Object.freeze({ path: 'README.md', role: 'factualSupport' }),
  Object.freeze({ path: 'SECURITY.md', role: 'security' }),
  Object.freeze({ path: 'api/mcp/README.md', role: 'factualSupport' }),
  Object.freeze({ path: 'CHANGELOG.md', role: 'migration' }),
  Object.freeze({ path: 'release/README.md', role: 'factualSupport' }),
]);
const ACTIVE_TEXT_FULL_CENSUS_RULES = Object.freeze([
  'PROFILE-002',
  'AUTH-001',
  'COUNT-001',
  'CACHE-001',
  'TRANSPORT-001',
  'DPOP-001',
  'EXTENSION-001',
]);
const ACTIVE_TEXT_REQUIRED_LIFECYCLE_SCOPES = Object.freeze([
  Object.freeze({
    id: 'LIFECYCLE-001',
    path: 'MCP.md',
    scope: Object.freeze({ kind: 'wholeFile', role: 'factualSupport' }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-001',
    path: 'README.md',
    scope: Object.freeze({ kind: 'wholeFile', role: 'factualSupport' }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-001',
    path: 'SECURITY.md',
    scope: Object.freeze({ kind: 'wholeFile', role: 'security' }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-002',
    path: 'MCP.md',
    scope: Object.freeze({
      headingPath: Object.freeze([
        'Model Context Protocol (MCP)',
        'Multi-round-trip input and request state',
      ]),
      kind: 'headingSubtree',
      role: 'defaultPath',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-002',
    path: 'MCP.md',
    scope: Object.freeze({
      fenceLanguage: 'java',
      headingPath: Object.freeze([
        'Model Context Protocol (MCP)',
        'Multi-round-trip input and request state',
      ]),
      kind: 'fencedBlock',
      role: 'defaultPath',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-003',
    path: 'README.md',
    scope: Object.freeze({
      headingPath: Object.freeze([
        'What Else Does It Do?',
        'Model Context Protocol (MCP)',
        'Recommended MCP setup',
      ]),
      kind: 'headingSubtree',
      role: 'defaultPath',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-003',
    path: 'README.md',
    scope: Object.freeze({
      fenceLanguage: 'java',
      headingPath: Object.freeze([
        'What Else Does It Do?',
        'Model Context Protocol (MCP)',
        'Recommended MCP setup',
      ]),
      kind: 'fencedBlock',
      role: 'defaultPath',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-004',
    path: 'MCP.md',
    scope: Object.freeze({
      headingPath: Object.freeze([
        'Model Context Protocol (MCP)',
        'Compatibility and unsupported features',
        'Deprecated compatibility surfaces',
      ]),
      kind: 'headingSubtree',
      role: 'compatibility',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-004',
    path: 'README.md',
    scope: Object.freeze({
      headingPath: Object.freeze([
        'What Else Does It Do?',
        'Model Context Protocol (MCP)',
        'Deprecated compatibility surfaces',
      ]),
      kind: 'headingSubtree',
      role: 'compatibility',
    }),
  }),
  Object.freeze({
    id: 'LIFECYCLE-004',
    path: 'SECURITY.md',
    scope: Object.freeze({
      headingPath: Object.freeze([
        'Security Policy',
        'MCP Deployment Security',
        'Deprecated compatibility surfaces',
      ]),
      kind: 'headingSubtree',
      role: 'security',
    }),
  }),
]);
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

function activeTextPathSegments(path, label) {
  if (typeof path !== 'string' || !path.length || isAbsolute(path)
      || path.includes('\\'))
    fail(`${label} must be a nonempty POSIX root-relative path: ${String(path)}`);
  const segments = path.split('/');
  if (segments.some((segment) => !segment.length
      || segment === '.' || segment === '..'))
    fail(`${label} must be a contained POSIX root-relative path: ${path}`);
  return segments;
}

function readActiveTextCandidateFile(root, path, label = 'Active-text path') {
  const segments = activeTextPathSegments(path, label);
  const normalizedRoot = resolve(root);
  let realRoot;
  try {
    const rootEntry = lstatSync(normalizedRoot);
    if (rootEntry.isSymbolicLink())
      fail(`Active-text candidate root must not be a symbolic link: ${normalizedRoot}`);
    if (!rootEntry.isDirectory())
      fail(`Active-text candidate root is not a directory: ${normalizedRoot}`);
    realRoot = realpathSync(normalizedRoot);
  } catch (error) {
    if (error instanceof McpPublicEvolutionVerificationError) throw error;
    fail(`Unable to resolve active-text candidate root ${normalizedRoot}: ${error.message}`);
  }
  let candidate = normalizedRoot;
  for (let index = 0; index < segments.length; index++) {
    candidate = join(candidate, segments[index]);
    let entry;
    try {
      entry = lstatSync(candidate);
    } catch (error) {
      fail(`${label} is missing: ${path}`);
    }
    if (entry.isSymbolicLink())
      fail(`${label} must not traverse a symbolic link: ${path}`);
    if (index < segments.length - 1 && !entry.isDirectory())
      fail(`${label} has a non-directory path component: ${path}`);
    if (index === segments.length - 1 && !entry.isFile())
      fail(`${label} must name a regular file: ${path}`);
  }
  const realCandidate = realpathSync(candidate);
  const containment = relative(realRoot, realCandidate);
  if (containment.startsWith(`..${sep}`) || containment === '..'
      || isAbsolute(containment))
    fail(`${label} escapes the active-text candidate root: ${path}`);
  return readFileSync(realCandidate, 'utf8');
}

function activeTextMatcher(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  exactKeys(value, ['kind', 'pattern', 'caseSensitive'], label);
  if (!ACTIVE_TEXT_MATCHER_KINDS.has(value.kind)
      || typeof value.pattern !== 'string' || !value.pattern.length)
    fail(`${label} must contain a nonempty literal or regex pattern.`);
  if (typeof value.caseSensitive !== 'boolean')
    fail(`${label}.caseSensitive must be boolean.`);
  if (value.kind === 'regex') {
    try {
      const regex = new RegExp(value.pattern,
        value.caseSensitive ? 'gu' : 'giu');
      if (regex.test('')) fail(`${label} regex must not match empty text.`);
    } catch (error) {
      if (error instanceof McpPublicEvolutionVerificationError) throw error;
      fail(`${label} regex is invalid: ${error.message}`);
    }
  }
  return value;
}

function activeTextScope(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  if (!ACTIVE_TEXT_SCOPE_KINDS.has(value.kind)
      || !ACTIVE_TEXT_ROLES.has(value.role))
    fail(`${label} has an invalid kind or role.`);
  if (value.kind === 'wholeFile') {
    exactKeys(value, ['kind', 'role'], label);
  } else if (value.kind === 'headingSubtree') {
    exactKeys(value, ['kind', 'headingPath', 'role'], label);
    if (!Array.isArray(value.headingPath) || !value.headingPath.length
        || value.headingPath.some((heading) => typeof heading !== 'string'
          || !heading.trim()))
      fail(`${label}.headingPath must contain stable nonblank heading text.`);
  } else {
    const expected = value.fenceLanguage === undefined
      ? ['kind', 'headingPath', 'role']
      : ['kind', 'headingPath', 'fenceLanguage', 'role'];
    exactKeys(value, expected, label);
    if (!Array.isArray(value.headingPath) || !value.headingPath.length
        || value.headingPath.some((heading) => typeof heading !== 'string'
          || !heading.trim()))
      fail(`${label}.headingPath must contain stable nonblank heading text.`);
    if (value.fenceLanguage !== undefined
        && (typeof value.fenceLanguage !== 'string'
          || !value.fenceLanguage.trim()))
      fail(`${label}.fenceLanguage must be nonblank when present.`);
  }
  return value;
}

function scopeIdentity(scope) {
  if (scope.kind === 'wholeFile') return `wholeFile|role=${scope.role}`;
  const heading = JSON.stringify(scope.headingPath);
  if (scope.kind === 'headingSubtree')
    return `headingSubtree|headingPath=${heading}|role=${scope.role}`;
  const language = scope.fenceLanguage === undefined
    ? '<any>' : JSON.stringify(scope.fenceLanguage);
  return `fencedBlock|headingPath=${heading}|fenceLanguage=${language}|role=${scope.role}`;
}

function isMarkdownEscaped(text, offset) {
  let slashCount = 0;
  for (let index = offset - 1; index >= 0 && text[index] === '\\'; index--)
    slashCount++;
  return slashCount % 2 === 1;
}

function backtickRunLength(text, offset) {
  let end = offset;
  while (text[end] === '`') end++;
  return end - offset;
}

function matchingBacktickRun(text, offset, length) {
  let candidate = offset;
  while ((candidate = text.indexOf('`', candidate)) !== -1) {
    const runLength = backtickRunLength(text, candidate);
    if (runLength === length && !isMarkdownEscaped(text, candidate))
      return candidate;
    candidate += runLength;
  }
  return -1;
}

function maskMarkdownHtmlComments(text, path) {
  const masked = text.split('');
  let offset = 0;
  let openFence;
  while (offset < text.length) {
    const atLineStart = offset === 0 || text[offset - 1] === '\n';
    if (atLineStart) {
      const newline = text.indexOf('\n', offset);
      const lineEnd = newline === -1 ? text.length : newline + 1;
      const line = text.slice(offset, newline === -1 ? lineEnd : newline)
        .replace(/\r$/u, '');
      if (openFence !== undefined) {
        const closing = line.match(/^ {0,3}(`{3,}|~{3,})[ \t]*$/u);
        if (closing && closing[1][0] === openFence.marker
            && closing[1].length >= openFence.length)
          openFence = undefined;
        offset = lineEnd;
        continue;
      }
      const opening = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/u);
      if (opening) {
        const information = opening[2].trim();
        if (opening[1][0] === '`' && information.includes('`'))
          fail(`Active-text Markdown fence has invalid info text: ${path}`);
        openFence = {
          length: opening[1].length,
          marker: opening[1][0],
        };
        offset = lineEnd;
        continue;
      }
      if (/^(?: {4}|\t)/u.test(line)) {
        offset = lineEnd;
        continue;
      }
    }
    if (text.startsWith('<!--', offset)) {
      const closing = text.indexOf('-->', offset + 4);
      const end = closing === -1 ? text.length : closing + 3;
      for (let index = offset; index < end; index++) {
        if (masked[index] !== '\n' && masked[index] !== '\r')
          masked[index] = ' ';
      }
      offset = end;
      continue;
    }
    if (text[offset] === '`' && !isMarkdownEscaped(text, offset)) {
      const runLength = backtickRunLength(text, offset);
      const closing = matchingBacktickRun(text, offset + runLength,
        runLength);
      offset = closing === -1 ? offset + runLength : closing + runLength;
      continue;
    }
    offset++;
  }
  return masked.join('');
}

function verifyRequiredActiveTextCoverage(rows) {
  const rowsById = new Map(rows.map((row) => [row.id, row]));
  for (const [id, expectation] of ACTIVE_TEXT_REQUIRED_RULE_EXPECTATIONS) {
    const row = rowsById.get(id);
    if (row === undefined)
      fail(`Active-text contract is missing required rule ${id}.`);
    if (row.expectation !== expectation)
      fail(`Active-text rule ${id} must retain expectation ${expectation}.`);
  }
  const requiredScopes = [];
  for (const id of ACTIVE_TEXT_FULL_CENSUS_RULES) {
    for (const document of ACTIVE_TEXT_GOVERNED_DOCUMENTS) {
      requiredScopes.push({
        id,
        path: document.path,
        scope: { kind: 'wholeFile', role: document.role },
      });
    }
  }
  requiredScopes.push(...ACTIVE_TEXT_REQUIRED_LIFECYCLE_SCOPES);
  for (const required of requiredScopes) {
    const row = rowsById.get(required.id);
    const requiredKey = JSON.stringify([
      required.path,
      scopeIdentity(required.scope),
    ]);
    const actualKeys = new Set(row.fileScopes.map((fileScope) => JSON.stringify([
      fileScope.path,
      scopeIdentity(fileScope.scope),
    ])));
    if (!actualKeys.has(requiredKey))
      fail(`Active-text rule ${required.id} is missing required governed scope ${required.path} at ${scopeIdentity(required.scope)}.`);
  }
}

function markdownStructure(text, path) {
  const lines = [];
  let offset = 0;
  while (offset < text.length) {
    const newline = text.indexOf('\n', offset);
    const end = newline === -1 ? text.length : newline + 1;
    const withoutNewline = text.slice(offset, newline === -1 ? end : newline)
      .replace(/\r$/u, '');
    lines.push({ start: offset, end, text: withoutNewline });
    offset = end;
  }
  const headings = [];
  const fences = [];
  const headingStack = [];
  let openFence;
  for (const line of lines) {
    if (openFence !== undefined) {
      const closing = line.text.match(/^ {0,3}(`{3,}|~{3,})[ \t]*$/u);
      if (closing && closing[1][0] === openFence.marker
          && closing[1].length >= openFence.length) {
        fences.push({
          end: line.start,
          language: openFence.language,
          start: openFence.contentStart,
        });
        openFence = undefined;
      }
      continue;
    }
    const opening = line.text.match(/^ {0,3}(`{3,}|~{3,})(.*)$/u);
    if (opening) {
      const information = opening[2].trim();
      if (opening[1][0] === '`' && information.includes('`'))
        fail(`Active-text Markdown fence has invalid info text: ${path}`);
      openFence = {
        contentStart: line.end,
        language: information.split(/[ \t]/u)[0] ?? '',
        length: opening[1].length,
        marker: opening[1][0],
      };
      continue;
    }
    const headingMatch = line.text.match(/^ {0,3}(#{1,6})[ \t]+(.+?)\s*$/u);
    if (!headingMatch) continue;
    const level = headingMatch[1].length;
    const headingText = headingMatch[2].replace(/[ \t]+#+[ \t]*$/u, '').trim();
    while (headingStack.length
        && headingStack[headingStack.length - 1].level >= level)
      headingStack.pop();
    const heading = {
      contentStart: line.end,
      end: text.length,
      level,
      lineStart: line.start,
      path: [...headingStack.map((entry) => entry.text), headingText],
      text: headingText,
    };
    headings.push(heading);
    headingStack.push(heading);
  }
  if (openFence !== undefined)
    fail(`Active-text Markdown contains an unclosed fenced block: ${path}`);
  for (let index = 0; index < headings.length; index++) {
    for (let candidate = index + 1; candidate < headings.length; candidate++) {
      if (headings[candidate].level <= headings[index].level) {
        headings[index].end = headings[candidate].lineStart;
        break;
      }
    }
  }
  return { fences, headings };
}

function activeTextRegion(text, path, scope, structure) {
  if (scope.kind === 'wholeFile')
    return { end: text.length, start: 0, text };
  const headings = structure.headings.filter((heading) =>
    JSON.stringify(heading.path) === JSON.stringify(scope.headingPath));
  if (headings.length !== 1)
    fail(`Active-text scope must resolve one heading in ${path}: ${JSON.stringify(scope.headingPath)}; found ${headings.length}.`);
  const heading = headings[0];
  if (scope.kind === 'headingSubtree')
    return {
      end: heading.end,
      start: heading.contentStart,
      text: text.slice(heading.contentStart, heading.end),
    };
  const fences = structure.fences.filter((fence) =>
    fence.start >= heading.contentStart && fence.end <= heading.end
      && (scope.fenceLanguage === undefined
        || fence.language === scope.fenceLanguage));
  if (fences.length !== 1)
    fail(`Active-text scope must resolve one fenced block in ${path}: ${scopeIdentity(scope)}; found ${fences.length}.`);
  return {
    end: fences[0].end,
    start: fences[0].start,
    text: text.slice(fences[0].start, fences[0].end),
  };
}

function activeTextMatches(text, matcher, label) {
  if (matcher.kind === 'literal') {
    const values = [];
    if (matcher.caseSensitive) {
      let offset = 0;
      while ((offset = text.indexOf(matcher.pattern, offset)) !== -1) {
        values.push({ matchedText: matcher.pattern, start: offset });
        offset += matcher.pattern.length;
      }
      return values;
    }
    const escaped = matcher.pattern.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    for (const match of text.matchAll(new RegExp(escaped, 'giu'))) {
      values.push({ matchedText: match[0], start: match.index });
    }
    return values;
  }
  const values = [];
  const flags = matcher.caseSensitive ? 'gu' : 'giu';
  for (const match of text.matchAll(new RegExp(matcher.pattern, flags))) {
    if (!match[0].length) fail(`${label} regex matched empty text.`);
    values.push({ matchedText: match[0], start: match.index });
  }
  return values;
}

function fingerprintKey(fingerprint) {
  return JSON.stringify([
    fingerprint.path,
    fingerprint.matchedText,
    scopeIdentity(fingerprint.scope),
  ]);
}

function fingerprintCounts(fingerprints) {
  const counts = new Map();
  for (const fingerprint of fingerprints) {
    const key = fingerprintKey(fingerprint);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

function fingerprintDifference(left, right) {
  const rightCounts = fingerprintCounts(right);
  const result = [];
  for (const fingerprint of left) {
    const key = fingerprintKey(fingerprint);
    const count = rightCounts.get(key) ?? 0;
    if (count === 0) result.push(fingerprint);
    else rightCounts.set(key, count - 1);
  }
  return result;
}

function evaluateActiveText(root) {
  let contract;
  try {
    contract = JSON.parse(readActiveTextCandidateFile(root,
      ACTIVE_TEXT_RULES_PATH, 'Active-text contract'));
  } catch (error) {
    if (error instanceof McpPublicEvolutionVerificationError) throw error;
    fail(`Unable to read active-text contract: ${error.message}`);
  }
  exactKeys(contract, ['formatVersion', 'rules'], 'active-text contract');
  if (contract.formatVersion !== 2 || !Array.isArray(contract.rules)
      || contract.rules.length === 0)
    fail('Active-text contract must be nonempty format version 2.');
  const ids = new Set();
  const rows = [];
  for (const rule of contract.rules) {
    const expectedRuleKeys = rule.expectation === 'nonzero-with-notice'
      ? ['id', 'classification', 'rationale', 'files', 'matcher',
        'expectation', 'noticePattern', 'allowedMatches']
      : ['id', 'classification', 'rationale', 'files', 'matcher',
        'expectation', 'allowedMatches'];
    exactKeys(rule, expectedRuleKeys,
      `active-text rule ${String(rule.id)}`);
    if (!/^[A-Z][A-Z0-9]*-[0-9]{3}$/u.test(rule.id) || ids.has(rule.id))
      fail(`Active-text rule ID is malformed or duplicated: ${String(rule.id)}`);
    ids.add(rule.id);
    if (typeof rule.classification !== 'string' || !rule.classification.trim()
        || typeof rule.rationale !== 'string' || !rule.rationale.trim())
      fail(`Active-text rule ${rule.id} lacks policy text.`);
    if (!ACTIVE_TEXT_EXPECTATIONS.has(rule.expectation))
      fail(`Active-text rule ${rule.id} has an invalid expectation.`);
    const matcher = activeTextMatcher(rule.matcher, `${rule.id}.matcher`);
    const noticePattern = rule.expectation === 'nonzero-with-notice'
      ? activeTextMatcher(rule.noticePattern, `${rule.id}.noticePattern`)
      : undefined;
    if (!Array.isArray(rule.files) || !rule.files.length)
      fail(`Active-text rule ${rule.id} must scope at least one file.`);
    const fileScopes = [];
    const fileScopeKeys = new Set();
    for (const [index, file] of rule.files.entries()) {
      if (!file || typeof file !== 'object' || Array.isArray(file))
        fail(`${rule.id}.files[${index}] must be an object.`);
      exactKeys(file, ['path', 'scope'], `${rule.id}.files[${index}]`);
      activeTextPathSegments(file.path, `${rule.id}.files[${index}].path`);
      const scope = activeTextScope(file.scope,
        `${rule.id}.files[${index}].scope`);
      const key = JSON.stringify([file.path, scopeIdentity(scope)]);
      if (fileScopeKeys.has(key))
        fail(`Active-text rule ${rule.id} duplicates a file scope: ${key}`);
      fileScopeKeys.add(key);
      fileScopes.push({ path: file.path, scope });
    }
    if (!Array.isArray(rule.allowedMatches))
      fail(`Active-text rule ${rule.id}.allowedMatches must be an array.`);
    const allowedMatches = rule.allowedMatches.map((fingerprint, index) => {
      if (!fingerprint || typeof fingerprint !== 'object'
          || Array.isArray(fingerprint))
        fail(`${rule.id}.allowedMatches[${index}] must be an object.`);
      exactKeys(fingerprint, ['path', 'matchedText', 'scope'],
        `${rule.id}.allowedMatches[${index}]`);
      activeTextPathSegments(fingerprint.path,
        `${rule.id}.allowedMatches[${index}].path`);
      const scope = activeTextScope(fingerprint.scope,
        `${rule.id}.allowedMatches[${index}].scope`);
      if (typeof fingerprint.matchedText !== 'string'
          || !fingerprint.matchedText.length)
        fail(`${rule.id}.allowedMatches[${index}].matchedText must be nonempty.`);
      const fileScopeKey = JSON.stringify([
        fingerprint.path,
        scopeIdentity(scope),
      ]);
      if (!fileScopeKeys.has(fileScopeKey))
        fail(`${rule.id}.allowedMatches[${index}] does not name a scoped file region.`);
      return { path: fingerprint.path,
        matchedText: fingerprint.matchedText, scope };
    });
    if (rule.expectation === 'zero' && allowedMatches.length)
      fail(`Active-text rule ${rule.id} has expectation zero and must not whitelist allowed matches.`);
    const actualMatches = [];
    const resolvedScopes = [];
    const scopesByPath = new Map();
    for (const fileScope of fileScopes) {
      if (!scopesByPath.has(fileScope.path)) {
        const text = maskMarkdownHtmlComments(readActiveTextCandidateFile(root,
          fileScope.path), fileScope.path);
        scopesByPath.set(fileScope.path, {
          structure: markdownStructure(text, fileScope.path),
          text,
        });
      }
      const source = scopesByPath.get(fileScope.path);
      resolvedScopes.push({
        ...fileScope,
        region: activeTextRegion(source.text, fileScope.path,
          fileScope.scope, source.structure),
      });
    }
    for (const [path, source] of scopesByPath) {
      const pathScopes = resolvedScopes.filter((entry) => entry.path === path);
      for (const match of activeTextMatches(source.text, matcher,
        `${rule.id}.matcher`)) {
        const matchEnd = match.start + match.matchedText.length;
        const candidates = pathScopes.filter(({ region }) =>
          match.start >= region.start && matchEnd <= region.end)
          .sort((left, right) =>
            (left.region.end - left.region.start)
              - (right.region.end - right.region.start));
        if (!candidates.length) continue;
        if (candidates.length > 1
            && candidates[0].region.end - candidates[0].region.start
              === candidates[1].region.end - candidates[1].region.start)
          fail(`Active-text rule ${rule.id} has ambiguous equally specific scopes for a match in ${path}.`);
        actualMatches.push({
          matchedText: match.matchedText,
          path,
          scope: candidates[0].scope,
        });
      }
    }
    if (rule.expectation === 'zero' && actualMatches.length)
      fail(`Active-text rule ${rule.id} expected zero matches; found ${actualMatches.length}.`);
    if (rule.expectation === 'nonzero-with-notice') {
      if (!actualMatches.length)
        fail(`Active-text rule ${rule.id} requires at least one allowed match.`);
      const matchedRegions = new Set(actualMatches.map((fingerprint) =>
        JSON.stringify([fingerprint.path, scopeIdentity(fingerprint.scope)])));
      for (const resolved of resolvedScopes) {
        const key = JSON.stringify([
          resolved.path,
          scopeIdentity(resolved.scope),
        ]);
        if (!matchedRegions.has(key)) continue;
        if (!activeTextMatches(resolved.region.text, noticePattern,
          `${rule.id}.noticePattern`).length)
          fail(`Active-text rule ${rule.id} lacks its notice in ${resolved.path} at ${scopeIdentity(resolved.scope)}.`);
      }
    }
    const unexpected = fingerprintDifference(actualMatches, allowedMatches);
    const missing = fingerprintDifference(allowedMatches, actualMatches);
    if (unexpected.length || missing.length)
      fail(`Active-text rule ${rule.id} fingerprint mismatch; unexpected=${unexpected.map(fingerprintKey).join(', ') || '<none>'}; missing=${missing.map(fingerprintKey).join(', ') || '<none>'}.`);
    rows.push({ ...rule, actualMatches, fileScopes });
  }
  verifyRequiredActiveTextCoverage(rows);
  return { contract, rows };
}

function markdownCell(value) {
  return String(value).replaceAll('\\', '\\\\').replaceAll('|', '\\|');
}

function htmlCode(value) {
  return `<code>${String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('|', '&#124;')}</code>`;
}

function matcherCell(matcher) {
  if (matcher.kind === 'literal')
    return `literal (${matcher.caseSensitive ? 'case-sensitive' : 'case-insensitive'}) ${JSON.stringify(matcher.pattern)}`;
  return `regex (${matcher.caseSensitive ? 'case-sensitive' : 'case-insensitive'}) /${matcher.pattern}/${matcher.caseSensitive ? 'gu' : 'giu'}`;
}

export function renderActiveTextAudit(root) {
  const { contract, rows } = evaluateActiveText(root);
  const lines = [
    '# MCP roadmap active-text audit',
    '',
    'Generated deterministically by `scripts/verify-mcp-public-evolution.mjs` from',
    '`conformance/roadmap-readiness-active-text-rules.json`. Do not edit by hand.',
    '',
    `Contract format: ${contract.formatVersion}.`,
    '',
    '| Rule | Classification | Matcher | Expectation | Notice matcher | Scoped regions | Allowed matches | Rationale | Decision |',
    '| --- | --- | --- | --- | --- | ---: | ---: | --- | --- |',
    ...rows.map((row) => `| ${row.id} | ${markdownCell(row.classification)} | ${htmlCode(matcherCell(row.matcher))} | ${row.expectation} | ${row.noticePattern === undefined ? '—' : htmlCode(matcherCell(row.noticePattern))} | ${row.fileScopes.length} | ${row.actualMatches.length} | ${markdownCell(row.rationale)} | PASS |`),
    '',
    '## Scoped regions',
    '',
    '| Rule | Path | Complete scope identity |',
    '| --- | --- | --- |',
    ...rows.flatMap((row) => row.fileScopes.map((fileScope) =>
      `| ${row.id} | ${htmlCode(fileScope.path)} | ${htmlCode(scopeIdentity(fileScope.scope))} |`)),
    '',
    '## Allowed-match fingerprints',
    '',
    '| Rule | Path | Matched text | Complete scope identity |',
    '| --- | --- | --- | --- |',
    ...rows.flatMap((row) => row.actualMatches.length
      ? row.actualMatches.map((fingerprint) =>
        `| ${row.id} | ${htmlCode(fingerprint.path)} | ${htmlCode(JSON.stringify(fingerprint.matchedText))} | ${htmlCode(scopeIdentity(fingerprint.scope))} |`)
      : [`| ${row.id} | — | — | — |`]),
    '',
    `Total: ${rows.length} active-text rules passed.`,
    '',
  ];
  return { ruleCount: rows.length, rendered: lines.join('\n') };
}

export function verifyActiveText(root) {
  const result = renderActiveTextAudit(root);
  const audit = readActiveTextCandidateFile(root, ACTIVE_TEXT_AUDIT_PATH,
    'Active-text audit');
  if (audit !== result.rendered)
    fail(`Active-text audit is stale; regenerate ${ACTIVE_TEXT_AUDIT_PATH}.`);
  return result;
}

function exactKeys(value, keys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
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
