#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { TextDecoder } from 'node:util';

const INVENTORY_PATH = 'release/version-transition-inventory.json';

export const INVENTORY_EXCLUSIONS = Object.freeze([
  'release/version-transition-inventory.json',
  'scripts/verify-version-transition-inventory-self-test.mjs',
  'scripts/verify-version-transition-inventory.mjs',
]);
export const PENDING_CURRENT_STAGE_PATHS = Object.freeze([
  'release/mcp-residual-closure-evidence.json',
]);

export const ORDERED_PATTERNS = Object.freeze([
  Object.freeze({
    contextExclusions: Object.freeze([]),
    id: 'product-snapshot-3.6.0',
    ordinal: 0,
    regex: '3\\.6\\.0-SNAPSHOT',
    regexFlags: 'gu',
  }),
  Object.freeze({
    contextExclusions: Object.freeze([]),
    id: 'product-exact-3.6.0',
    ordinal: 1,
    regex: '3\\.6\\.0',
    regexFlags: 'gu',
  }),
  Object.freeze({
    contextExclusions: Object.freeze([]),
    id: 'product-line-3.6',
    ordinal: 2,
    regex: '3\\.6',
    regexFlags: 'gu',
  }),
]);

const TOP_LEVEL_FIELDS = Object.freeze([
  'baselineCommit',
  'comparisonVersion',
  'currentStage',
  'developmentVersion',
  'formatVersion',
  'occurrences',
  'orderedPatterns',
  'releaseVersion',
]);
const PATTERN_FIELDS = Object.freeze([
  'contextExclusions',
  'id',
  'ordinal',
  'regex',
  'regexFlags',
]);
const OCCURRENCE_FIELDS = Object.freeze([
  'classification',
  'exactLineSha256',
  'line',
  'literal',
  'occurrenceIndex',
  'owner',
  'path',
  'rationale',
  'replacement',
]);
const CLASSIFICATIONS = Object.freeze([
  'FIXTURE_PRESERVE',
  'HISTORICAL_PRESERVE',
  'REMOVE_BY_MCP_R4',
  'RETARGET_NOW',
  'RETARGET_THEN_REMOVE_BY_U7',
  'UNRELATED_VERSION_PRESERVE',
]);
const PRESERVE_CLASSIFICATIONS = new Set([
  'FIXTURE_PRESERVE',
  'HISTORICAL_PRESERVE',
  'UNRELATED_VERSION_PRESERVE',
]);
const STAGES = new Set([
  'baseline',
  'post-retarget',
  'post-d2',
  'post-u7',
  'final',
]);
const RETARGET_REPLACEMENTS = Object.freeze({
  '3.6': '4.0',
  '3.6.0': '4.0.0',
  '3.6.0-SNAPSHOT': '4.0.0-SNAPSHOT',
});
const TARGET_PROJECTION_PATTERNS = Object.freeze([
  '4.0.0-SNAPSHOT',
  '4.0.0',
  '4.0',
]);
const CURRENT_STAGE_FIELDS = Object.freeze([
  'censusSha256',
  'd2RemovalAnchors',
  'files',
  'name',
  'occurrences',
  'removedBaselineKeys',
]);
const CURRENT_STAGE_NAME = 'post-u7';
export const EXPECTED_CURRENT_STAGE_CENSUS_SHA256 =
  'f3df1beda1039285bcff8b88f84cdfecdb73713bd36d552db7a1a69335310388';
export const EXPECTED_BASELINE_GOVERNANCE_SHA256 =
  '862417a75ee2b8aa4c04eff14713b47eedc22060319ef4f369e4ad6beff10afb';
const CURRENT_STAGE_OCCURRENCE_CLASSES = new Set([
  'PRESERVED',
  'REPLACED',
  'TARGET_ONLY',
]);
const VERSION_MASK = '@@SOKLET-VERSION@@';
const CURRENT_VERSION_PATTERNS = Object.freeze([
  '4.0.0-SNAPSHOT',
  '3.6.0-SNAPSHOT',
  '4.0.0',
  '3.6.0',
  '4.0',
  '3.6',
]);
// Regex-bearing release checks encode product dots as backslash-dot and are
// intentionally outside the owner-approved literal occurrence grammar.  They
// still must not retain the old active product line after the U1 retarget.
const ENCODED_OLD_PRODUCT_PATTERN = /3\\+\.6(?:\\+\.0)?(?:-SNAPSHOT)?/u;
const HEX_ENCODED_OLD_PRODUCT_PATTERN = /332e362e30(?:2d534e415053484f54)?/iu;
const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true });

function fail(message) {
  throw new Error(message);
}

function sortedKeys(value) {
  return Object.keys(value).sort();
}

function requireExactFields(value, fields, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = sortedKeys(value);
  const expected = [...fields].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} fields must be exactly ${expected.join(', ')}; found ${actual.join(', ')}.`);
  }
}

function asciiCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'ascii'), Buffer.from(right, 'ascii'));
}

function compareOccurrences(left, right) {
  return asciiCompare(left.path, right.path)
    || left.line - right.line
    || left.occurrenceIndex - right.occurrenceIndex;
}

export function lineSha256(line) {
  return createHash('sha256').update(line, 'utf8').digest('hex');
}

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function splitLines(text) {
  return text.split(/\r\n|\n|\r/u);
}

function stickyPatterns(patterns) {
  return patterns.map((pattern) => {
    const flags = `${pattern.regexFlags.replaceAll('g', '').replaceAll('y', '')}y`;
    return { ...pattern, compiled: new RegExp(pattern.regex, flags) };
  });
}

export function scanText(path, text, patterns = ORDERED_PATTERNS) {
  const compiledPatterns = stickyPatterns(patterns);
  const occurrences = [];
  for (const [lineOffset, lineText] of splitLines(text).entries()) {
    let offset = 0;
    let occurrenceIndex = 0;
    while (offset < lineText.length) {
      let literal = null;
      let patternId = null;
      for (const pattern of compiledPatterns) {
        pattern.compiled.lastIndex = offset;
        const match = pattern.compiled.exec(lineText);
        if (match !== null) {
          literal = match[0];
          patternId = pattern.id;
          break;
        }
      }
      if (literal !== null) {
        occurrences.push({
          column: offset,
          exactLineSha256: lineSha256(lineText),
          line: lineOffset + 1,
          lineText,
          literal,
          occurrenceIndex,
          path,
          patternId,
        });
        occurrenceIndex += 1;
        offset += literal.length;
      } else {
        const codePoint = lineText.codePointAt(offset);
        offset += codePoint > 0xffff ? 2 : 1;
      }
    }
  }
  return occurrences;
}

function isOldVersionLiteral(literal) {
  return Object.hasOwn(RETARGET_REPLACEMENTS, literal);
}

function isTargetVersionLiteral(literal) {
  return TARGET_PROJECTION_PATTERNS.includes(literal);
}

function isAsciiDigit(character) {
  return character !== undefined && character >= '0' && character <= '9';
}

function hasTargetVersionBoundaries(text, offset, token) {
  const before = offset === 0 ? undefined : text[offset - 1];
  const afterOffset = offset + token.length;
  const after = afterOffset >= text.length ? undefined : text[afterOffset];
  const afterNext = afterOffset + 1 >= text.length ? undefined : text[afterOffset + 1];
  return before !== '.'
    && !isAsciiDigit(before)
    && !isAsciiDigit(after)
    && !(after === '.' && isAsciiDigit(afterNext));
}

function currentVersionLiteralAt(line, offset) {
  for (const literal of CURRENT_VERSION_PATTERNS) {
    if (!line.startsWith(literal, offset)) {
      continue;
    }
    if (isOldVersionLiteral(literal)
        || hasTargetVersionBoundaries(line, offset, literal)) {
      return literal;
    }
  }
  return null;
}

export function scanCurrentVersionText(path, text) {
  const occurrences = [];
  for (const [lineOffset, lineText] of splitLines(text).entries()) {
    let offset = 0;
    let occurrenceIndex = 0;
    let oldOccurrenceIndex = 0;
    while (offset < lineText.length) {
      const literal = currentVersionLiteralAt(lineText, offset);
      if (literal !== null) {
        occurrences.push({
          column: offset,
          exactLineSha256: lineSha256(lineText),
          line: lineOffset + 1,
          lineText,
          literal,
          occurrenceIndex,
          oldOccurrenceIndex: isOldVersionLiteral(literal)
            ? oldOccurrenceIndex
            : null,
          path,
        });
        occurrenceIndex += 1;
        if (isOldVersionLiteral(literal)) {
          oldOccurrenceIndex += 1;
        }
        offset += literal.length;
      } else {
        const codePoint = lineText.codePointAt(offset);
        offset += codePoint > 0xffff ? 2 : 1;
      }
    }
  }
  return occurrences;
}

function maskVersionLine(line, { includeTargets = true } = {}) {
  const occurrences = scanCurrentVersionText('', line)
    .filter(({ literal }) => includeTargets || isOldVersionLiteral(literal));
  if (occurrences.length === 0) {
    return line;
  }
  let masked = '';
  let offset = 0;
  for (const occurrence of occurrences) {
    masked += line.slice(offset, occurrence.column);
    masked += VERSION_MASK;
    offset = occurrence.column + occurrence.literal.length;
  }
  return masked + line.slice(offset);
}

export function maskedVersionFileSha256(text, options = {}) {
  const pieces = text.split(/(\r\n|\n|\r)/u);
  for (let index = 0; index < pieces.length; index += 2) {
    pieces[index] = maskVersionLine(pieces[index], options);
  }
  return sha256(pieces.join(''));
}

function decodeTrackedText(buffer, label) {
  if (buffer.includes(0)) {
    return null;
  }
  try {
    return UTF8_DECODER.decode(buffer);
  } catch {
    return null;
  }
}

function runGit(root, args, { encoding = null } = {}) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding,
    maxBuffer: 128 * 1024 * 1024,
  });
  if (result.status !== 0) {
    const stderr = Buffer.isBuffer(result.stderr)
      ? result.stderr.toString('utf8')
      : result.stderr;
    fail(`git ${args.join(' ')} failed: ${(stderr || '').trim()}`);
  }
  return result.stdout;
}

function currentTrackedPaths(root) {
  return runGit(root, ['ls-files', '-z'])
    .toString('utf8')
    .split('\0')
    .filter(Boolean)
    .filter((path) => !INVENTORY_EXCLUSIONS.includes(path))
    .sort(asciiCompare);
}

function validatePendingCurrentStagePaths(paths) {
  if (!Array.isArray(paths)) {
    fail('pending current-stage paths must be an array.');
  }
  const seen = new Set();
  for (const path of paths) {
    if (typeof path !== 'string'
        || path.length === 0
        || !/^[\x20-\x7e]+$/u.test(path)
        || isAbsolute(path)
        || path.split('/').includes('..')
        || INVENTORY_EXCLUSIONS.includes(path)) {
      fail(`pending current-stage path is not a safe reviewed relative path: ${path}.`);
    }
    if (seen.has(path)) {
      fail(`pending current-stage path is duplicated: ${path}.`);
    }
    seen.add(path);
  }
  return [...paths].sort(asciiCompare);
}

function baselineTrackedPaths(root, commit) {
  return runGit(root, ['ls-tree', '-r', '-z', commit])
    .toString('utf8')
    .split('\0')
    .filter(Boolean)
    .map((entry) => {
      const tab = entry.indexOf('\t');
      const metadata = entry.slice(0, tab).split(' ');
      return { mode: metadata[0], path: entry.slice(tab + 1) };
    })
    .filter(({ mode, path }) => mode.startsWith('100') && !INVENTORY_EXCLUSIONS.includes(path))
    .map(({ path }) => path)
    .sort(asciiCompare);
}

function readCurrentTexts(root, pendingCurrentStagePaths = PENDING_CURRENT_STAGE_PATHS) {
  const texts = new Map();
  const pendingPaths = validatePendingCurrentStagePaths(pendingCurrentStagePaths);
  const pendingPathSet = new Set(pendingPaths);
  const paths = [...new Set([...currentTrackedPaths(root), ...pendingPaths])]
    .sort(asciiCompare);
  for (const path of paths) {
    const absolute = join(root, path);
    let stat;
    try {
      stat = lstatSync(absolute);
    } catch (error) {
      if (error.code === 'ENOENT') {
        if (pendingPathSet.has(path)) {
          fail(`reviewed pending current-stage file is missing: ${path}.`);
        }
        continue;
      }
      throw error;
    }
    if (!stat.isFile()) {
      if (pendingPathSet.has(path)) {
        fail(`reviewed pending current-stage path must be a regular non-symlink file: ${path}.`);
      }
      continue;
    }
    const text = decodeTrackedText(readFileSync(absolute), path);
    if (text === null) {
      if (pendingPathSet.has(path)) {
        fail(`reviewed pending current-stage file must be UTF-8 text without NUL bytes: ${path}.`);
      }
      continue;
    }
    texts.set(path, text);
  }
  return texts;
}

function readBaselineTexts(root, commit) {
  const texts = new Map();
  for (const path of baselineTrackedPaths(root, commit)) {
    const buffer = runGit(root, ['show', `${commit}:${path}`]);
    const text = decodeTrackedText(buffer, `${commit}:${path}`);
    if (text !== null) {
      texts.set(path, text);
    }
  }
  return texts;
}

function scanTexts(texts, patterns = ORDERED_PATTERNS) {
  const occurrences = [];
  for (const [path, text] of [...texts.entries()].sort(([left], [right]) => asciiCompare(left, right))) {
    occurrences.push(...scanText(path, text, patterns));
  }
  return occurrences;
}

function occurrenceKey(occurrence) {
  return `${occurrence.path}\u0000${occurrence.line}\u0000${occurrence.occurrenceIndex}`;
}

function printableOccurrenceKey(occurrence) {
  return `${occurrence.path}\t${occurrence.line}\t${occurrence.occurrenceIndex}`;
}

function parseCanonicalInteger(value, label, { minimum = 0 } = {}) {
  if (!/^(?:0|[1-9][0-9]*)$/u.test(value)) {
    fail(`${label} must be a canonical nonnegative integer.`);
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum) {
    fail(`${label} is outside the supported integer range.`);
  }
  return parsed;
}

function parseFileTuple(tuple, label) {
  if (typeof tuple !== 'string') {
    fail(`${label} must be a tab-delimited string.`);
  }
  const fields = tuple.split('\t');
  if (fields.length !== 2) {
    fail(`${label} must contain exactly path and masked SHA-256.`);
  }
  const [path, maskedSha256] = fields;
  if (path.length === 0 || !/^[\x20-\x7e]+$/u.test(path)) {
    fail(`${label} path must be nonempty printable ASCII.`);
  }
  if (!/^[0-9a-f]{64}$/u.test(maskedSha256)) {
    fail(`${label} masked SHA-256 must be lowercase hexadecimal.`);
  }
  return { maskedSha256, path };
}

function parseRemovedTuple(tuple, label) {
  if (typeof tuple !== 'string') {
    fail(`${label} must be a tab-delimited string.`);
  }
  const fields = tuple.split('\t');
  if (fields.length !== 3) {
    fail(`${label} must contain exactly path, line, and occurrence index.`);
  }
  const [path, line, occurrenceIndex] = fields;
  if (path.length === 0 || !/^[\x20-\x7e]+$/u.test(path)) {
    fail(`${label} path must be nonempty printable ASCII.`);
  }
  return {
    line: parseCanonicalInteger(line, `${label} line`, { minimum: 1 }),
    occurrenceIndex: parseCanonicalInteger(
      occurrenceIndex,
      `${label} occurrence index`,
    ),
    path,
  };
}

function parseCurrentOccurrenceTuple(tuple, label) {
  if (typeof tuple !== 'string') {
    fail(`${label} must be a tab-delimited string.`);
  }
  const fields = tuple.split('\t');
  if (fields.length !== 10) {
    fail(`${label} must contain exactly ten fields.`);
  }
  const [
    classification,
    path,
    line,
    column,
    occurrenceIndex,
    literal,
    finalLiteral,
    baselinePath,
    baselineLine,
    baselineOccurrenceIndex,
  ] = fields;
  if (!CURRENT_STAGE_OCCURRENCE_CLASSES.has(classification)) {
    fail(`${label} classification is not approved.`);
  }
  if (path.length === 0 || !/^[\x20-\x7e]+$/u.test(path)) {
    fail(`${label} path must be nonempty printable ASCII.`);
  }
  if (!CURRENT_VERSION_PATTERNS.includes(literal)) {
    fail(`${label} literal is not a bounded current-stage version token.`);
  }
  if (!CURRENT_VERSION_PATTERNS.includes(finalLiteral)
      || (finalLiteral !== literal
        && !(literal === '4.0.0-SNAPSHOT' && finalLiteral === '4.0.0'))) {
    fail(`${label} final literal must be unchanged or exactly 4.0.0-SNAPSHOT -> 4.0.0.`);
  }
  const baselineIsNull = baselinePath === '-'
    && baselineLine === '-'
    && baselineOccurrenceIndex === '-';
  if (classification === 'TARGET_ONLY' && !baselineIsNull) {
    fail(`${label} TARGET_ONLY anchor must have a null baseline key.`);
  }
  if (classification !== 'TARGET_ONLY' && baselineIsNull) {
    fail(`${label} ${classification} anchor requires a baseline key.`);
  }
  if (!baselineIsNull
      && (baselinePath.length === 0
        || !/^[\x20-\x7e]+$/u.test(baselinePath))) {
    fail(`${label} baseline path must be nonempty printable ASCII.`);
  }
  return {
    baselineKey: baselineIsNull ? null : {
      line: parseCanonicalInteger(
        baselineLine,
        `${label} baseline line`,
        { minimum: 1 },
      ),
      occurrenceIndex: parseCanonicalInteger(
        baselineOccurrenceIndex,
        `${label} baseline occurrence index`,
      ),
      path: baselinePath,
    },
    classification,
    column: parseCanonicalInteger(column, `${label} column`),
    finalLiteral,
    line: parseCanonicalInteger(line, `${label} line`, { minimum: 1 }),
    literal,
    occurrenceIndex: parseCanonicalInteger(
      occurrenceIndex,
      `${label} occurrence index`,
    ),
    path,
  };
}

function parseD2RemovalAnchorTuple(tuple, label) {
  if (typeof tuple !== 'string') {
    fail(`${label} must be a tab-delimited string.`);
  }
  const fields = tuple.split('\t');
  if (fields.length !== 5) {
    fail(`${label} must contain baseline path, line, occurrence index, current path, and context SHA-256.`);
  }
  const [baselinePath, baselineLine, baselineOccurrenceIndex, path, contextSha256] = fields;
  for (const [field, value] of [['baseline path', baselinePath], ['current path', path]]) {
    if (value.length === 0 || !/^[\x20-\x7e]+$/u.test(value)) {
      fail(`${label} ${field} must be nonempty printable ASCII.`);
    }
  }
  if (!/^[0-9a-f]{64}$/u.test(contextSha256)) {
    fail(`${label} context SHA-256 must be lowercase hexadecimal.`);
  }
  return {
    baselineKey: {
      line: parseCanonicalInteger(baselineLine, `${label} baseline line`, { minimum: 1 }),
      occurrenceIndex: parseCanonicalInteger(
        baselineOccurrenceIndex,
        `${label} baseline occurrence index`,
      ),
      path: baselinePath,
    },
    contextSha256,
    path,
  };
}

function encodeFileTuple({ path, maskedSha256 }) {
  return `${path}\t${maskedSha256}`;
}

function encodeRemovedTuple({ path, line, occurrenceIndex }) {
  return `${path}\t${line}\t${occurrenceIndex}`;
}

function encodeCurrentOccurrenceTuple(occurrence) {
  const baseline = occurrence.baselineKey === null
    ? ['-', '-', '-']
    : [
      occurrence.baselineKey.path,
      occurrence.baselineKey.line,
      occurrence.baselineKey.occurrenceIndex,
    ];
  return [
    occurrence.classification,
    occurrence.path,
    occurrence.line,
    occurrence.column,
    occurrence.occurrenceIndex,
    occurrence.literal,
    occurrence.finalLiteral,
    ...baseline,
  ].join('\t');
}

function encodeD2RemovalAnchorTuple(anchor) {
  return [
    anchor.baselineKey.path,
    anchor.baselineKey.line,
    anchor.baselineKey.occurrenceIndex,
    anchor.path,
    anchor.contextSha256,
  ].join('\t');
}

function compareCurrentOccurrences(left, right) {
  return asciiCompare(left.path, right.path)
    || left.line - right.line
    || left.column - right.column
    || left.occurrenceIndex - right.occurrenceIndex;
}

export function currentStageCensusSha256(currentStage) {
  return sha256(JSON.stringify({
    d2RemovalAnchors: currentStage.d2RemovalAnchors,
    files: currentStage.files,
    name: currentStage.name,
    occurrences: currentStage.occurrences,
    removedBaselineKeys: currentStage.removedBaselineKeys,
  }));
}

export function baselineGovernanceSha256(inventory) {
  return sha256(JSON.stringify({
    baselineCommit: inventory.baselineCommit,
    comparisonVersion: inventory.comparisonVersion,
    developmentVersion: inventory.developmentVersion,
    formatVersion: inventory.formatVersion,
    occurrences: inventory.occurrences,
    orderedPatterns: inventory.orderedPatterns,
    releaseVersion: inventory.releaseVersion,
  }));
}

function validateCurrentStage(inventory, expectedCurrentStageCensusSha256) {
  const currentStage = inventory.currentStage;
  requireExactFields(currentStage, CURRENT_STAGE_FIELDS, 'currentStage');
  if (currentStage.name !== CURRENT_STAGE_NAME) {
    fail(`currentStage.name must be ${CURRENT_STAGE_NAME}.`);
  }
  for (const [field, value] of [
    ['d2RemovalAnchors', currentStage.d2RemovalAnchors],
    ['files', currentStage.files],
    ['occurrences', currentStage.occurrences],
    ['removedBaselineKeys', currentStage.removedBaselineKeys],
  ]) {
    if (!Array.isArray(value)) {
      fail(`currentStage.${field} must be an array.`);
    }
  }
  if (!/^[0-9a-f]{64}$/u.test(currentStage.censusSha256)
      || currentStage.censusSha256 !== currentStageCensusSha256(currentStage)) {
    fail('currentStage censusSha256 does not seal the exact reviewed census.');
  }
  if (currentStage.censusSha256 !== expectedCurrentStageCensusSha256) {
    fail(`currentStage censusSha256 does not match the independent verifier pin ${expectedCurrentStageCensusSha256}.`);
  }

  const inventoryPaths = [...new Set(inventory.occurrences.map(({ path }) => path))]
    .sort(asciiCompare);
  const files = currentStage.files.map((tuple, index) =>
    parseFileTuple(tuple, `currentStage.files[${index}]`));
  for (let index = 1; index < files.length; index += 1) {
    if (asciiCompare(files[index - 1].path, files[index].path) >= 0) {
      fail('currentStage files must be in strict ASCII path order.');
    }
  }
  const filePathSet = new Set(files.map(({ path }) => path));
  const missingInventoryPath = inventoryPaths.find((path) => !filePathSet.has(path));
  if (missingInventoryPath !== undefined) {
    fail(`currentStage files must include every baseline-inventoried path; missing ${missingInventoryPath}.`);
  }
  if (new Set(currentStage.files).size !== currentStage.files.length) {
    fail('currentStage files contain a duplicate tuple.');
  }

  const baselineByKey = new Map(inventory.occurrences.map((row) => [
    printableOccurrenceKey(row),
    row,
  ]));
  const d2RemovalAnchors = currentStage.d2RemovalAnchors.map((tuple, index) =>
    parseD2RemovalAnchorTuple(tuple, `currentStage.d2RemovalAnchors[${index}]`));
  const d2RemovalKeys = d2RemovalAnchors.map(({ baselineKey }) =>
    encodeRemovedTuple(baselineKey));
  const expectedD2RemovalKeys = inventory.occurrences
    .filter(({ classification }) => classification === 'REMOVE_BY_MCP_R4')
    .map(printableOccurrenceKey);
  if (JSON.stringify(d2RemovalKeys) !== JSON.stringify(expectedD2RemovalKeys)
      || new Set(d2RemovalKeys).size !== d2RemovalKeys.length) {
    fail('currentStage d2RemovalAnchors must cover every REMOVE_BY_MCP_R4 row exactly once in baseline order.');
  }
  for (const anchor of d2RemovalAnchors) {
    if (anchor.path !== anchor.baselineKey.path) {
      fail(`D2 removal anchor ${encodeRemovedTuple(anchor.baselineKey)} may not move to another file.`);
    }
  }
  const mappedBaselineKeys = new Set();
  const occurrences = currentStage.occurrences.map((tuple, index) =>
    parseCurrentOccurrenceTuple(
      tuple,
      `currentStage.occurrences[${index}]`,
    ));
  let previous = null;
  const anchorKeys = new Set();
  for (const [index, occurrence] of occurrences.entries()) {
    if (previous !== null && compareCurrentOccurrences(previous, occurrence) >= 0) {
      fail(`currentStage.occurrences[${index}] is not in strict ASCII path/location order.`);
    }
    previous = occurrence;
    const anchorKey = `${occurrence.path}\t${occurrence.line}\t${occurrence.occurrenceIndex}`;
    if (anchorKeys.has(anchorKey)) {
      fail(`currentStage occurrence anchor duplicates ${anchorKey}.`);
    }
    anchorKeys.add(anchorKey);
    if (occurrence.classification === 'TARGET_ONLY') {
      if (!isTargetVersionLiteral(occurrence.literal)) {
        fail(`TARGET_ONLY anchor at ${occurrence.path}:${occurrence.line} must be a target-version literal.`);
      }
      continue;
    }
    const baselineKey = encodeRemovedTuple(occurrence.baselineKey);
    const baseline = baselineByKey.get(baselineKey);
    if (baseline === undefined) {
      fail(`currentStage anchor names unknown baseline occurrence ${baselineKey}.`);
    }
    if (mappedBaselineKeys.has(baselineKey)) {
      fail(`baseline occurrence ${baselineKey} is mapped more than once.`);
    }
    mappedBaselineKeys.add(baselineKey);
    if (occurrence.path !== baseline.path) {
      fail(`baseline occurrence ${baselineKey} may not move to another file.`);
    }
    if (occurrence.classification === 'PRESERVED') {
      if (!PRESERVE_CLASSIFICATIONS.has(baseline.classification)
          || occurrence.literal !== baseline.literal
          || occurrence.finalLiteral !== occurrence.literal) {
        fail(`PRESERVED anchor ${baselineKey} does not preserve its exact baseline classification and literal.`);
      }
    } else if (baseline.classification !== 'RETARGET_NOW'
        || occurrence.literal !== baseline.replacement
        || occurrence.finalLiteral !== (occurrence.literal === inventory.developmentVersion
          ? inventory.releaseVersion
          : occurrence.literal)) {
      fail(`REPLACED anchor ${baselineKey} does not identify its exact required replacement.`);
    }
  }

  const removed = currentStage.removedBaselineKeys.map((tuple, index) =>
    parseRemovedTuple(tuple, `currentStage.removedBaselineKeys[${index}]`));
  const removedKeys = removed.map(encodeRemovedTuple);
  const sortedRemoved = [...removed].sort(compareOccurrences).map(encodeRemovedTuple);
  if (JSON.stringify(removedKeys) !== JSON.stringify(sortedRemoved)
      || new Set(removedKeys).size !== removedKeys.length) {
    fail('currentStage removedBaselineKeys must be unique and in strict ASCII path/location order.');
  }
  for (const key of removedKeys) {
    const baseline = baselineByKey.get(key);
    if (baseline === undefined) {
      fail(`currentStage removed key ${key} does not identify a baseline occurrence.`);
    }
    if (baseline.classification !== 'REMOVE_BY_MCP_R4'
        && baseline.classification !== 'RETARGET_THEN_REMOVE_BY_U7') {
      fail(`currentStage removed key ${key} does not have an approved removal classification.`);
    }
    if (mappedBaselineKeys.has(key)) {
      fail(`baseline occurrence ${key} is both mapped and removed.`);
    }
  }

  const accounted = new Set([...mappedBaselineKeys, ...removedKeys]);
  if (accounted.size !== inventory.occurrences.length) {
    const missing = inventory.occurrences
      .map(printableOccurrenceKey)
      .find((key) => !accounted.has(key));
    fail(`currentStage does not account exactly once for every baseline occurrence; first missing key is ${missing}.`);
  }
}

function validateInventoryShape(
  inventory,
  expectedCurrentStageCensusSha256,
  expectedBaselineGovernanceSha256,
) {
  requireExactFields(inventory, TOP_LEVEL_FIELDS, 'inventory');
  if (inventory.formatVersion !== 2) {
    fail('inventory formatVersion must be 2.');
  }
  if (!/^[0-9a-f]{40}$/u.test(inventory.baselineCommit)) {
    fail('inventory baselineCommit must be a lowercase 40-character Git object ID.');
  }
  if (inventory.comparisonVersion !== '3.5.1'
      || inventory.developmentVersion !== '4.0.0-SNAPSHOT'
      || inventory.releaseVersion !== '4.0.0') {
    fail('inventory versions must be comparison=3.5.1, development=4.0.0-SNAPSHOT, release=4.0.0.');
  }
  if (!Array.isArray(inventory.orderedPatterns)
      || inventory.orderedPatterns.length !== ORDERED_PATTERNS.length) {
    fail('inventory orderedPatterns must contain the three approved patterns.');
  }
  for (const [index, pattern] of inventory.orderedPatterns.entries()) {
    requireExactFields(pattern, PATTERN_FIELDS, `orderedPatterns[${index}]`);
    if (JSON.stringify(pattern) !== JSON.stringify(ORDERED_PATTERNS[index])) {
      fail(`orderedPatterns[${index}] differs from the approved grammar.`);
    }
  }
  if (!Array.isArray(inventory.occurrences)) {
    fail('inventory occurrences must be an array.');
  }
  const seen = new Set();
  let previous = null;
  for (const [index, occurrence] of inventory.occurrences.entries()) {
    const label = `occurrences[${index}]`;
    requireExactFields(occurrence, OCCURRENCE_FIELDS, label);
    if (typeof occurrence.path !== 'string'
        || occurrence.path.length === 0
        || !/^[\x20-\x7e]+$/u.test(occurrence.path)) {
      fail(`${label}.path must be nonempty ASCII text.`);
    }
    if (INVENTORY_EXCLUSIONS.includes(occurrence.path)) {
      fail(`${label}.path is one of the three excluded inventory-trio paths.`);
    }
    if (!Number.isInteger(occurrence.line) || occurrence.line < 1) {
      fail(`${label}.line must be a positive integer.`);
    }
    if (!Number.isInteger(occurrence.occurrenceIndex) || occurrence.occurrenceIndex < 0) {
      fail(`${label}.occurrenceIndex must be a nonnegative integer.`);
    }
    if (!Object.hasOwn(RETARGET_REPLACEMENTS, occurrence.literal)) {
      fail(`${label}.literal is not recognized by the approved grammar.`);
    }
    if (!CLASSIFICATIONS.includes(occurrence.classification)) {
      fail(`${label}.classification is not approved.`);
    }
    if (typeof occurrence.owner !== 'string' || occurrence.owner.trim().length === 0) {
      fail(`${label}.owner must be nonblank.`);
    }
    if (typeof occurrence.rationale !== 'string' || occurrence.rationale.trim().length === 0) {
      fail(`${label}.rationale must be nonblank.`);
    }
    if (!/^[0-9a-f]{64}$/u.test(occurrence.exactLineSha256)) {
      fail(`${label}.exactLineSha256 must be lowercase SHA-256.`);
    }
    const expectedReplacement = occurrence.classification === 'RETARGET_NOW'
        || occurrence.classification === 'RETARGET_THEN_REMOVE_BY_U7'
      ? RETARGET_REPLACEMENTS[occurrence.literal]
      : null;
    if (occurrence.replacement !== expectedReplacement) {
      fail(`${label}.replacement must be ${JSON.stringify(expectedReplacement)}.`);
    }
    const key = occurrenceKey(occurrence);
    if (seen.has(key)) {
      fail(`${label} duplicates (${occurrence.path},${occurrence.line},${occurrence.occurrenceIndex}).`);
    }
    seen.add(key);
    if (previous !== null && compareOccurrences(previous, occurrence) >= 0) {
      fail(`${label} is not in strict ASCII path/line/occurrence-index order.`);
    }
    previous = occurrence;
  }

  const u7Rows = inventory.occurrences.filter(({ classification }) =>
    classification === 'RETARGET_THEN_REMOVE_BY_U7');
  if (u7Rows.length !== 2
      || u7Rows.some((row) =>
        row.literal !== '3.6.0'
          || row.replacement !== '4.0.0'
          || row.owner !== 'U7/MCP-C')) {
    fail('inventory must contain exactly two U7/MCP-C RETARGET_THEN_REMOVE_BY_U7 3.6.0 -> 4.0.0 rows.');
  }

  if (baselineGovernanceSha256(inventory) !== expectedBaselineGovernanceSha256) {
    fail(`baseline governance does not match the independent verifier pin ${expectedBaselineGovernanceSha256}.`);
  }

  validateCurrentStage(inventory, expectedCurrentStageCensusSha256);
}

function verifyBaselineCoverage(inventory, baselineTexts) {
  const scanned = scanTexts(baselineTexts);
  if (scanned.length !== inventory.occurrences.length) {
    fail(`baseline coverage count differs: scanned ${scanned.length}, inventory ${inventory.occurrences.length}.`);
  }
  for (let index = 0; index < scanned.length; index += 1) {
    const actual = scanned[index];
    const expected = inventory.occurrences[index];
    if (occurrenceKey(actual) !== occurrenceKey(expected)
        || actual.literal !== expected.literal
        || actual.exactLineSha256 !== expected.exactLineSha256) {
      fail(`baseline coverage differs at index ${index}: scanned ${actual.path}:${actual.line}:${actual.occurrenceIndex} ${actual.literal}.`);
    }
  }
}

function verifyEncodedOldVersions(currentTexts, stage) {
  if (stage === 'baseline') {
    return;
  }
  for (const [path, text] of currentTexts) {
    const lineOffset = splitLines(text).findIndex((line) =>
      ENCODED_OLD_PRODUCT_PATTERN.test(line)
        || HEX_ENCODED_OLD_PRODUCT_PATTERN.test(line));
    if (lineOffset !== -1) {
      fail(`encoded active 3.6.0 product-version text survives ${stage} at ${path}:${lineOffset + 1}.`);
    }
  }
}

function expectedEarlyProjection(inventory, baselineText, path, stage) {
  const inventoryByKey = new Map(inventory.occurrences.map((row) => [
    occurrenceKey(row),
    row,
  ]));
  const expected = [];
  for (const token of scanCurrentVersionText(path, baselineText)) {
    if (!isOldVersionLiteral(token.literal)) {
      expected.push(token.literal);
      continue;
    }
    const row = inventoryByKey.get(`${path}\u0000${token.line}\u0000${token.oldOccurrenceIndex}`);
    if (row === undefined) {
      fail(`baseline projection lacks inventory row ${path}:${token.line}:${token.oldOccurrenceIndex}.`);
    }
    if (row.classification === 'RETARGET_NOW'
        || row.classification === 'RETARGET_THEN_REMOVE_BY_U7') {
      expected.push(row.replacement);
    } else if (row.classification === 'REMOVE_BY_MCP_R4') {
      if (stage === 'post-retarget') {
        expected.push(row.literal);
      }
    } else {
      expected.push(row.literal);
    }
  }
  return expected;
}

function nearestNonblankLine(lines, start, step) {
  for (let index = start; index >= 0 && index < lines.length; index += step) {
    if (lines[index].trim().length > 0) {
      return maskVersionLine(lines[index]);
    }
  }
  return null;
}

function markdownHeadingOwners(lines, lineIndex) {
  const owners = [];
  for (let index = 0; index < lineIndex; index += 1) {
    const line = maskVersionLine(lines[index]).trim();
    const match = /^(#{1,6})\s+(.+?)\s*#*$/u.exec(line);
    if (match === null) {
      continue;
    }
    const level = match[1].length;
    owners.length = level - 1;
    owners[level - 1] = match[2];
  }
  return owners.map((owner, index) => `${index + 1}:${owner ?? ''}`);
}

function xmlStructuralOwners(lines, lineIndex) {
  const stack = [];
  for (let index = 0; index < lineIndex; index += 1) {
    const line = maskVersionLine(lines[index]);
    for (const match of line.matchAll(/<(artifactId|groupId|id|name)>([^<]*)<\/\1>/gu)) {
      if (stack.length > 0) {
        stack[stack.length - 1].identities.push(`${match[1]}=${match[2].trim()}`);
      }
    }
    for (const match of line.matchAll(/<\s*(\/?)\s*([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*)>/gu)) {
      const closing = match[1] === '/';
      const tag = match[2];
      const suffix = match[3];
      if (closing) {
        const ownerIndex = stack.map(({ tag: ownerTag }) => ownerTag).lastIndexOf(tag);
        if (ownerIndex !== -1) {
          stack.length = ownerIndex;
        }
      } else if (!suffix.trimEnd().endsWith('/')
          && !line.slice(match.index).startsWith('<?')
          && !line.slice(match.index).startsWith('<!')) {
        stack.push({ identities: [], tag });
      }
    }
  }
  return stack.map(({ identities, tag }) => ({ identities, tag }));
}

function braceStructuralOwners(lines, lineIndex) {
  const owners = [];
  let previousNonblank = '';
  for (let index = 0; index < lineIndex; index += 1) {
    const line = maskVersionLine(lines[index]).trim();
    const label = line === '{' ? previousNonblank : line;
    for (const character of line) {
      if (character === '{') {
        owners.push(label);
      } else if (character === '}') {
        owners.pop();
      }
    }
    if (line.length > 0) {
      previousNonblank = line;
    }
  }
  return owners;
}

function semanticOwnerContext(lines, lineIndex) {
  return {
    braces: braceStructuralOwners(lines, lineIndex),
    headings: markdownHeadingOwners(lines, lineIndex),
    xml: xmlStructuralOwners(lines, lineIndex),
  };
}

function semanticLineContext(lines, lineIndex) {
  return sha256(JSON.stringify({
    current: maskVersionLine(lines[lineIndex]),
    next: nearestNonblankLine(lines, lineIndex + 1, 1),
    owners: semanticOwnerContext(lines, lineIndex),
    previous: nearestNonblankLine(lines, lineIndex - 1, -1),
  }));
}

function earlyExpectedLiteral(row, stage) {
  if (row.classification === 'RETARGET_NOW'
      || row.classification === 'RETARGET_THEN_REMOVE_BY_U7') {
    return row.replacement;
  }
  if (row.classification === 'REMOVE_BY_MCP_R4' && stage === 'post-d2') {
    return null;
  }
  return row.literal;
}

function incrementCount(counts, key) {
  counts.set(key, (counts.get(key) ?? 0) + 1);
}

function verifyEarlySemanticAnchors(inventory, baselineTexts, currentTexts, stage) {
  const expectedByPath = new Map();
  for (const row of inventory.occurrences) {
    const expectedLiteral = earlyExpectedLiteral(row, stage);
    if (expectedLiteral === null) {
      continue;
    }
    const baselineLines = splitLines(baselineTexts.get(row.path));
    const baselineToken = scanCurrentVersionText(
      row.path,
      baselineLines[row.line - 1],
    ).find(({ oldOccurrenceIndex }) =>
      oldOccurrenceIndex === row.occurrenceIndex);
    if (baselineToken === undefined || baselineToken.literal !== row.literal) {
      fail(`cannot derive early semantic anchor from baseline ${row.path}:${row.line}:${row.occurrenceIndex}.`);
    }
    const counts = expectedByPath.get(row.path) ?? new Map();
    incrementCount(counts, JSON.stringify({
      context: semanticLineContext(baselineLines, row.line - 1),
      literal: expectedLiteral,
      occurrenceIndex: baselineToken.occurrenceIndex,
    }));
    expectedByPath.set(row.path, counts);
  }

  for (const [path, expectedCounts] of expectedByPath) {
    const currentLines = splitLines(currentTexts.get(path) ?? '');
    const actualCounts = new Map();
    for (const token of scanCurrentVersionText(path, currentTexts.get(path) ?? '')) {
      incrementCount(actualCounts, JSON.stringify({
        context: semanticLineContext(currentLines, token.line - 1),
        literal: token.literal,
        occurrenceIndex: token.occurrenceIndex,
      }));
    }
    for (const [anchor, expectedCount] of expectedCounts) {
      if ((actualCounts.get(anchor) ?? 0) < expectedCount) {
        fail(`masked baseline-line semantic context differs during ${stage} in ${path}: ${anchor}.`);
      }
    }
  }
}

function verifyD2RemovalAnchors(inventory, currentTexts) {
  const anchors = inventory.currentStage.d2RemovalAnchors.map((tuple, index) =>
    parseD2RemovalAnchorTuple(tuple, `currentStage.d2RemovalAnchors[${index}]`));
  for (const anchor of anchors) {
    const lines = splitLines(currentTexts.get(anchor.path) ?? '');
    const contexts = new Set(lines.map((line, index) =>
      semanticLineContext(lines, index)));
    if (!contexts.has(anchor.contextSha256)) {
      fail(`reviewed D2 removal/replacement context is missing for ${encodeRemovedTuple(anchor.baselineKey)} in ${anchor.path}.`);
    }
  }
}

function verifyEarlyStage(inventory, baselineTexts, currentTexts, stage) {
  const inventoriedPaths = [...new Set(inventory.occurrences.map(({ path }) => path))]
    .sort(asciiCompare);
  const inventoriedPathSet = new Set(inventoriedPaths);
  for (const occurrence of scanTexts(currentTexts)) {
    if (!inventoriedPathSet.has(occurrence.path)) {
      fail(`unclassified product-version occurrence at ${occurrence.path}:${occurrence.line}:${occurrence.occurrenceIndex} (${occurrence.literal}).`);
    }
  }
  for (const path of inventoriedPaths) {
    const expected = expectedEarlyProjection(
      inventory,
      baselineTexts.get(path),
      path,
      stage,
    );
    const actual = scanCurrentVersionText(path, currentTexts.get(path) ?? '')
      .map(({ literal }) => literal);
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      fail(`exact bounded version projection differs in ${path}; expected ${JSON.stringify(expected)}, found ${JSON.stringify(actual)}.`);
    }
  }
  verifyEarlySemanticAnchors(inventory, baselineTexts, currentTexts, stage);
  if (stage === 'post-d2') {
    verifyD2RemovalAnchors(inventory, currentTexts);
  }
}

function expectedReviewedOccurrences(inventory, stage) {
  const stored = inventory.currentStage.occurrences.map((tuple, index) =>
    parseCurrentOccurrenceTuple(
      tuple,
      `currentStage.occurrences[${index}]`,
    ));
  if (stage !== 'final') {
    return stored;
  }
  const shifts = new Map();
  return stored.map((occurrence) => {
    const lineKey = `${occurrence.path}\u0000${occurrence.line}`;
    const priorShift = shifts.get(lineKey) ?? 0;
    const transformed = {
      ...occurrence,
      column: occurrence.column - priorShift,
      literal: occurrence.finalLiteral,
    };
    const shift = occurrence.literal.length - occurrence.finalLiteral.length;
    if (shift !== 0) {
      shifts.set(
        lineKey,
        priorShift + shift,
      );
    }
    return transformed;
  });
}

function comparableCurrentOccurrence(occurrence) {
  return {
    column: occurrence.column,
    line: occurrence.line,
    literal: occurrence.literal,
    occurrenceIndex: occurrence.occurrenceIndex,
    path: occurrence.path,
  };
}

function currentCensusOccurrences(path, text) {
  return scanCurrentVersionText(path, text);
}

function requiredCurrentStagePaths(inventory, currentTexts) {
  const paths = new Set(inventory.occurrences.map(({ path }) => path));
  for (const [path, text] of currentTexts) {
    if (currentCensusOccurrences(path, text).length > 0) {
      paths.add(path);
    }
  }
  return [...paths].sort(asciiCompare);
}

function verifyReviewedStage(inventory, currentTexts, stage) {
  const files = inventory.currentStage.files.map((tuple, index) =>
    parseFileTuple(tuple, `currentStage.files[${index}]`));
  const requiredPaths = requiredCurrentStagePaths(inventory, currentTexts);
  if (JSON.stringify(files.map(({ path }) => path)) !== JSON.stringify(requiredPaths)) {
    fail('reviewed current-stage files differ from the exact union of baseline paths and current bounded-version paths.');
  }
  const expected = expectedReviewedOccurrences(inventory, stage);
  const actual = [];
  for (const file of files) {
    const text = currentTexts.get(file.path);
    if (text === undefined) {
      fail(`reviewed current-stage file is missing or is not tracked text: ${file.path}.`);
    }
    const actualMaskedSha256 = maskedVersionFileSha256(text);
    if (actualMaskedSha256 !== file.maskedSha256) {
      fail(`reviewed masked file context changed at ${file.path}; expected ${file.maskedSha256}, found ${actualMaskedSha256}.`);
    }
    actual.push(...currentCensusOccurrences(file.path, text));
  }
  const expectedComparable = expected.map(comparableCurrentOccurrence);
  const actualComparable = actual.map(comparableCurrentOccurrence);
  if (JSON.stringify(actualComparable) !== JSON.stringify(expectedComparable)) {
    const difference = Math.min(actualComparable.length, expectedComparable.length);
    let index = 0;
    while (index < difference
        && JSON.stringify(actualComparable[index]) === JSON.stringify(expectedComparable[index])) {
      index += 1;
    }
    fail(`reviewed current-stage census differs at index ${index}; expected ${JSON.stringify(expectedComparable[index] ?? null)}, found ${JSON.stringify(actualComparable[index] ?? null)}.`);
  }

  const expectedOld = expected
    .filter(({ classification }) => classification === 'PRESERVED')
    .map(({ column, line, literal, path }) => ({ column, line, literal, path }));
  const actualOld = scanTexts(currentTexts)
    .map(({ column, line, literal, path }) => ({ column, line, literal, path }));
  if (JSON.stringify(actualOld) !== JSON.stringify(expectedOld)) {
    fail('current tree old-version occurrences do not equal the reviewed PRESERVED census exactly.');
  }
}

function verifyStage(inventory, baselineTexts, currentTexts, stage) {
  const currentOccurrences = scanTexts(currentTexts);
  const currentByKey = new Map(currentOccurrences.map((row) => [occurrenceKey(row), row]));

  verifyEncodedOldVersions(currentTexts, stage);

  if (stage === 'baseline') {
    if (currentOccurrences.length !== inventory.occurrences.length) {
      fail(`baseline current-tree count differs: scanned ${currentOccurrences.length}, inventory ${inventory.occurrences.length}.`);
    }
    for (const row of inventory.occurrences) {
      const current = currentByKey.get(occurrenceKey(row));
      if (current === undefined
          || current.literal !== row.literal
          || current.exactLineSha256 !== row.exactLineSha256) {
        fail(`baseline occurrence changed or relocated at ${row.path}:${row.line}:${row.occurrenceIndex}.`);
      }
    }
    return;
  }

  if (stage === 'post-retarget' || stage === 'post-d2') {
    verifyEarlyStage(inventory, baselineTexts, currentTexts, stage);
  } else {
    verifyReviewedStage(inventory, currentTexts, stage);
  }
}

function normalizedLineLocations(text) {
  const locations = new Map();
  for (const [index, line] of splitLines(text).entries()) {
    const normalized = maskVersionLine(line);
    const lines = locations.get(normalized) ?? [];
    lines.push(index + 1);
    locations.set(normalized, lines);
  }
  return locations;
}

function automaticCurrentAnchor(row, expectedLiteral, baselineText, currentText) {
  const baselineLines = splitLines(baselineText);
  const currentLines = splitLines(currentText);
  const normalized = maskVersionLine(baselineLines[row.line - 1]);
  const baselineLocations = normalizedLineLocations(baselineText).get(normalized) ?? [];
  const currentLocations = normalizedLineLocations(currentText).get(normalized) ?? [];
  if (baselineLocations.length !== currentLocations.length) {
    return null;
  }
  const lineOrdinal = baselineLocations.indexOf(row.line);
  if (lineOrdinal === -1) {
    return null;
  }
  const currentLine = currentLocations[lineOrdinal];
  const baselineToken = scanCurrentVersionText(
    row.path,
    baselineLines[row.line - 1],
  ).find(({ oldOccurrenceIndex }) =>
    oldOccurrenceIndex === row.occurrenceIndex);
  if (baselineToken === undefined) {
    return null;
  }
  const currentToken = scanCurrentVersionText(
    row.path,
    currentLines[currentLine - 1],
  ).find(({ occurrenceIndex }) =>
    occurrenceIndex === baselineToken.occurrenceIndex);
  if (currentToken === undefined || currentToken.literal !== expectedLiteral) {
    return null;
  }
  return {
    ...currentToken,
    line: currentLine,
  };
}

function reviewedOverrideAnchor(row, expectedLiteral, currentText, override) {
  if (override === undefined) {
    return null;
  }
  if (override === null
      || typeof override !== 'object'
      || Array.isArray(override)
      || !Number.isInteger(override.line)
      || override.line < 1
      || !Number.isInteger(override.occurrenceIndex)
      || override.occurrenceIndex < 0) {
    fail(`reviewed override for ${printableOccurrenceKey(row)} must contain a positive line and nonnegative occurrenceIndex.`);
  }
  const token = scanCurrentVersionText(row.path, currentText).find(({ line, occurrenceIndex }) =>
    line === override.line && occurrenceIndex === override.occurrenceIndex);
  if (token === undefined || token.literal !== expectedLiteral) {
    fail(`reviewed override for ${printableOccurrenceKey(row)} does not identify ${expectedLiteral} at ${row.path}:${override.line}:${override.occurrenceIndex}.`);
  }
  return token;
}

export function derivePostU7CurrentStage({
  root,
  inventory,
  reviewedOverrides = {},
  d2RemovalAnchorLines = {},
  pendingCurrentStagePaths = PENDING_CURRENT_STAGE_PATHS,
  preservedFinalSnapshotAnchors = [],
}) {
  const absoluteRoot = resolve(root);
  const baselineTexts = readBaselineTexts(absoluteRoot, inventory.baselineCommit);
  const currentTexts = readCurrentTexts(absoluteRoot, pendingCurrentStagePaths);
  const paths = requiredCurrentStagePaths(inventory, currentTexts);
  const mappings = new Map();
  const unresolved = [];
  const usedOverrides = new Set();
  for (const row of inventory.occurrences) {
    let classification;
    let expectedLiteral;
    if (PRESERVE_CLASSIFICATIONS.has(row.classification)) {
      classification = 'PRESERVED';
      expectedLiteral = row.literal;
    } else if (row.classification === 'RETARGET_NOW') {
      classification = 'REPLACED';
      expectedLiteral = row.replacement;
    } else {
      continue;
    }
    const key = printableOccurrenceKey(row);
    let anchor = reviewedOverrideAnchor(
      row,
      expectedLiteral,
      currentTexts.get(row.path) ?? '',
      reviewedOverrides[key],
    );
    if (anchor !== null) {
      usedOverrides.add(key);
    } else {
      anchor = automaticCurrentAnchor(
        row,
        expectedLiteral,
        baselineTexts.get(row.path),
        currentTexts.get(row.path) ?? '',
      );
    }
    if (anchor === null) {
      unresolved.push(key);
      continue;
    }
    const anchorKey = `${anchor.path}\t${anchor.line}\t${anchor.occurrenceIndex}`;
    if (mappings.has(anchorKey)) {
      fail(`reviewed mapping collision at ${anchorKey} between ${mappings.get(anchorKey).baselineKey.path} and ${row.path}.`);
    }
    mappings.set(anchorKey, {
      baselineKey: {
        line: row.line,
        occurrenceIndex: row.occurrenceIndex,
        path: row.path,
      },
      classification,
    });
  }
  const unusedOverrides = Object.keys(reviewedOverrides)
    .filter((key) => !usedOverrides.has(key));
  if (unusedOverrides.length > 0) {
    fail(`reviewed current-stage overrides were unused: ${unusedOverrides.join(', ')}.`);
  }
  if (unresolved.length > 0) {
    fail(`current-stage mappings require explicit reviewed overrides: ${unresolved.join(', ')}.`);
  }

  const occurrences = [];
  const files = [];
  const preservedFinalSnapshots = new Set(preservedFinalSnapshotAnchors);
  const usedPreservedFinalSnapshots = new Set();
  for (const path of paths) {
    const text = currentTexts.get(path);
    if (text === undefined) {
      fail(`cannot derive current-stage census because ${path} is missing or is not tracked text.`);
    }
    files.push(encodeFileTuple({
      maskedSha256: maskedVersionFileSha256(text),
      path,
    }));
    for (const occurrence of currentCensusOccurrences(path, text)) {
      const anchorKey = `${path}\t${occurrence.line}\t${occurrence.occurrenceIndex}`;
      const mapping = mappings.get(anchorKey);
      if (mapping === undefined && isOldVersionLiteral(occurrence.literal)) {
        fail(`unmapped old-version occurrence remains at ${path}:${occurrence.line}:${occurrence.occurrenceIndex}.`);
      }
      const classification = mapping?.classification ?? 'TARGET_ONLY';
      const preserveFinalSnapshot = preservedFinalSnapshots.has(anchorKey);
      if (preserveFinalSnapshot) {
        if (classification !== 'TARGET_ONLY'
            || occurrence.literal !== '4.0.0-SNAPSHOT') {
          fail(`preserved final snapshot anchor ${anchorKey} must identify a TARGET_ONLY 4.0.0-SNAPSHOT token.`);
        }
        usedPreservedFinalSnapshots.add(anchorKey);
      }
      occurrences.push(encodeCurrentOccurrenceTuple({
        ...occurrence,
        baselineKey: mapping?.baselineKey ?? null,
        classification,
        finalLiteral: occurrence.literal === '4.0.0-SNAPSHOT'
            && !preserveFinalSnapshot
          ? '4.0.0'
          : occurrence.literal,
      }));
    }
  }
  const unusedPreservedFinalSnapshots = [...preservedFinalSnapshots]
    .filter((key) => !usedPreservedFinalSnapshots.has(key));
  if (unusedPreservedFinalSnapshots.length > 0) {
    fail(`preserved final snapshot anchors were unused: ${unusedPreservedFinalSnapshots.join(', ')}.`);
  }
  const d2RemovalAnchors = [];
  const usedD2RemovalAnchors = new Set();
  for (const row of inventory.occurrences.filter(({ classification }) =>
    classification === 'REMOVE_BY_MCP_R4')) {
    const key = printableOccurrenceKey(row);
    const line = d2RemovalAnchorLines[key];
    if (!Number.isInteger(line) || line < 1) {
      fail(`D2 removal anchor ${key} requires an explicit positive current line.`);
    }
    const currentLines = splitLines(currentTexts.get(row.path) ?? '');
    if (line > currentLines.length) {
      fail(`D2 removal anchor ${key} current line ${line} is outside ${row.path}.`);
    }
    d2RemovalAnchors.push(encodeD2RemovalAnchorTuple({
      baselineKey: {
        line: row.line,
        occurrenceIndex: row.occurrenceIndex,
        path: row.path,
      },
      contextSha256: semanticLineContext(currentLines, line - 1),
      path: row.path,
    }));
    usedD2RemovalAnchors.add(key);
  }
  const unusedD2RemovalAnchors = Object.keys(d2RemovalAnchorLines)
    .filter((key) => !usedD2RemovalAnchors.has(key));
  if (unusedD2RemovalAnchors.length > 0) {
    fail(`D2 removal anchor lines were unused: ${unusedD2RemovalAnchors.join(', ')}.`);
  }
  const removedBaselineKeys = inventory.occurrences
    .filter(({ classification }) =>
      classification === 'REMOVE_BY_MCP_R4'
        || classification === 'RETARGET_THEN_REMOVE_BY_U7')
    .map(encodeRemovedTuple);
  const currentStage = {
    censusSha256: '',
    d2RemovalAnchors,
    files,
    name: CURRENT_STAGE_NAME,
    occurrences,
    removedBaselineKeys,
  };
  currentStage.censusSha256 = currentStageCensusSha256(currentStage);
  return currentStage;
}

function countBy(items, selector) {
  const counts = new Map();
  for (const item of items) {
    const key = selector(item);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

export function verifyVersionTransition({
  root,
  stage,
  inventoryPath = INVENTORY_PATH,
  expectedCurrentStageCensusSha256 = EXPECTED_CURRENT_STAGE_CENSUS_SHA256,
  expectedBaselineGovernanceSha256 = EXPECTED_BASELINE_GOVERNANCE_SHA256,
  pendingCurrentStagePaths = PENDING_CURRENT_STAGE_PATHS,
}) {
  if (!STAGES.has(stage)) {
    fail(`stage must be exactly one of baseline, post-retarget, post-d2, post-u7, final; found ${stage}.`);
  }
  const absoluteRoot = resolve(root);
  const absoluteInventory = isAbsolute(inventoryPath)
    ? inventoryPath
    : join(absoluteRoot, inventoryPath);
  let inventory;
  try {
    inventory = JSON.parse(readFileSync(absoluteInventory, 'utf8'));
  } catch (error) {
    fail(`cannot read inventory ${absoluteInventory}: ${error.message}`);
  }
  validateInventoryShape(
    inventory,
    expectedCurrentStageCensusSha256,
    expectedBaselineGovernanceSha256,
  );
  const baselineTexts = readBaselineTexts(absoluteRoot, inventory.baselineCommit);
  verifyBaselineCoverage(inventory, baselineTexts);
  const currentTexts = readCurrentTexts(absoluteRoot, pendingCurrentStagePaths);
  verifyStage(inventory, baselineTexts, currentTexts, stage);

  const classifications = countBy(inventory.occurrences, ({ classification }) => classification);
  return {
    classifications: Object.fromEntries([...classifications].sort(([left], [right]) => asciiCompare(left, right))),
    files: new Set(inventory.occurrences.map(({ path }) => path)).size,
    occurrences: inventory.occurrences.length,
    stage,
  };
}

function main() {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--stage' || !STAGES.has(args[1])) {
    console.error('Usage: node scripts/verify-version-transition-inventory.mjs --stage <baseline|post-retarget|post-d2|post-u7|final>');
    process.exitCode = 2;
    return;
  }
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  try {
    const result = verifyVersionTransition({ root, stage: args[1] });
    const counts = CLASSIFICATIONS
      .map((classification) => `${classification}=${result.classifications[classification] ?? 0}`)
      .join(' ');
    console.log(`version-transition inventory PASS stage=${result.stage} occurrences=${result.occurrences} files=${result.files} ${counts}`);
  } catch (error) {
    console.error(`version-transition inventory FAIL: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
