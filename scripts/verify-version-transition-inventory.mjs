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
  'UNRELATED_VERSION_PRESERVE',
]);
const PRESERVE_CLASSIFICATIONS = new Set([
  'FIXTURE_PRESERVE',
  'HISTORICAL_PRESERVE',
  'UNRELATED_VERSION_PRESERVE',
]);
const STAGES = new Set(['baseline', 'post-retarget', 'post-d2', 'final']);
const RETARGET_REPLACEMENTS = Object.freeze({
  '3.6': '4.0',
  '3.6.0': '4.0.0',
  '3.6.0-SNAPSHOT': '4.0.0-SNAPSHOT',
});
const TOKEN_PROJECTION_PATTERNS = Object.freeze([
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

function readCurrentTexts(root) {
  const texts = new Map();
  for (const path of currentTrackedPaths(root)) {
    const absolute = join(root, path);
    let stat;
    try {
      stat = lstatSync(absolute);
    } catch (error) {
      if (error.code === 'ENOENT') {
        continue;
      }
      throw error;
    }
    if (!stat.isFile()) {
      continue;
    }
    const text = decodeTrackedText(readFileSync(absolute), path);
    if (text !== null) {
      texts.set(path, text);
    }
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

function validateInventoryShape(inventory) {
  requireExactFields(inventory, TOP_LEVEL_FIELDS, 'inventory');
  if (inventory.formatVersion !== 1) {
    fail('inventory formatVersion must be 1.');
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

function projectedTokens(line) {
  const tokens = [];
  let offset = 0;
  while (offset < line.length) {
    let token = null;
    for (const candidate of TOKEN_PROJECTION_PATTERNS) {
      if (line.startsWith(candidate, offset)) {
        token = candidate;
        break;
      }
    }
    if (token !== null) {
      tokens.push(token);
      offset += token.length;
    } else {
      const codePoint = line.codePointAt(offset);
      offset += codePoint > 0xffff ? 2 : 1;
    }
  }
  return tokens;
}

function expectedProjectionForPath(path, baselineText, inventoryByKey, stage) {
  const expected = [];
  for (const [lineOffset, baselineLine] of splitLines(baselineText).entries()) {
    let oldOccurrenceIndex = 0;
    for (const token of projectedTokens(baselineLine)) {
      if (!Object.hasOwn(RETARGET_REPLACEMENTS, token)) {
        expected.push(token);
        continue;
      }
      const lineNumber = lineOffset + 1;
      const row = inventoryByKey.get(`${path}\u0000${lineNumber}\u0000${oldOccurrenceIndex}`);
      if (row === undefined) {
        fail(`baseline projection lacks inventory row ${path}:${lineNumber}:${oldOccurrenceIndex}.`);
      }
      oldOccurrenceIndex += 1;
      if (row.classification === 'RETARGET_NOW') {
        expected.push(stage === 'final' && token === '3.6.0-SNAPSHOT'
          ? '4.0.0'
          : row.replacement);
      } else if (row.classification === 'REMOVE_BY_MCP_R4'
          && (stage === 'post-d2' || stage === 'final')) {
        // The marker is removed or the owning assertion is replaced at D2.
      } else {
        expected.push(token);
      }
    }
  }
  return expected;
}

function verifyStage(inventory, baselineTexts, currentTexts, stage) {
  const inventoryByKey = new Map(inventory.occurrences.map((row) => [occurrenceKey(row), row]));
  const currentOccurrences = scanTexts(currentTexts);
  const currentByKey = new Map(currentOccurrences.map((row) => [occurrenceKey(row), row]));

  if (stage === 'final') {
    for (const [path, text] of currentTexts) {
      const lineOffset = splitLines(text).findIndex((line) => line.includes(inventory.developmentVersion));
      if (lineOffset !== -1) {
        fail(`active snapshot ${inventory.developmentVersion} survives final stage at ${path}:${lineOffset + 1}.`);
      }
    }
  }

  if (stage !== 'baseline') {
    for (const [path, text] of currentTexts) {
      const lineOffset = splitLines(text).findIndex((line) =>
        ENCODED_OLD_PRODUCT_PATTERN.test(line)
          || HEX_ENCODED_OLD_PRODUCT_PATTERN.test(line));
      if (lineOffset !== -1) {
        fail(`encoded active 3.6.0 product-version text survives ${stage} at ${path}:${lineOffset + 1}.`);
      }
    }
  }

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

  const allowedOldClasses = new Set(PRESERVE_CLASSIFICATIONS);
  if (stage === 'post-retarget') {
    allowedOldClasses.add('REMOVE_BY_MCP_R4');
  }
  for (const current of currentOccurrences) {
    const inventoryRow = inventoryByKey.get(occurrenceKey(current));
    if (inventoryRow === undefined) {
      fail(`unclassified or relocated product-version occurrence at ${current.path}:${current.line}:${current.occurrenceIndex} (${current.literal}).`);
    }
    if (!allowedOldClasses.has(inventoryRow.classification)) {
      fail(`${inventoryRow.classification} occurrence survives ${stage} at ${current.path}:${current.line}:${current.occurrenceIndex}.`);
    }
    if (current.literal !== inventoryRow.literal
        || current.exactLineSha256 !== inventoryRow.exactLineSha256) {
      fail(`preserved occurrence bytes changed at ${current.path}:${current.line}:${current.occurrenceIndex}.`);
    }
  }
  for (const row of inventory.occurrences) {
    if (!allowedOldClasses.has(row.classification)) {
      continue;
    }
    const current = currentByKey.get(occurrenceKey(row));
    if (current === undefined) {
      fail(`${row.classification} occurrence was removed or relocated too early at ${row.path}:${row.line}:${row.occurrenceIndex}.`);
    }
  }

  const pathsToProject = new Set(inventory.occurrences
    .filter(({ classification }) => classification === 'RETARGET_NOW'
      || classification === 'REMOVE_BY_MCP_R4')
    .map(({ path }) => path));
  for (const path of pathsToProject) {
    const expected = expectedProjectionForPath(
      path,
      baselineTexts.get(path),
      inventoryByKey,
      stage,
    );
    const actual = projectedTokens(currentTexts.get(path) ?? '');
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      fail(`ordered version replacement projection differs in ${path}; expected ${JSON.stringify(expected)}, found ${JSON.stringify(actual)}.`);
    }
  }
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
}) {
  if (!STAGES.has(stage)) {
    fail(`stage must be exactly one of baseline, post-retarget, post-d2, final; found ${stage}.`);
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
  validateInventoryShape(inventory);
  const baselineTexts = readBaselineTexts(absoluteRoot, inventory.baselineCommit);
  verifyBaselineCoverage(inventory, baselineTexts);
  const currentTexts = readCurrentTexts(absoluteRoot);
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
    console.error('Usage: node scripts/verify-version-transition-inventory.mjs --stage <baseline|post-retarget|post-d2|final>');
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
