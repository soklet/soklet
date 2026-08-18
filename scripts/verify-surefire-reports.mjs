#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { delimiter, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAXIMUM_REPORT_BYTES = 16 * 1024 * 1024;
const COUNT_NAMES = ['tests', 'errors', 'skipped', 'failures'];
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const SOKLET_CORE_MARKER = 'com/soklet/Soklet.class';
const JAR_MANIFEST = 'META-INF/MANIFEST.MF';
const ZIP_END_SIGNATURE = 0x06054b50;
const ZIP_CENTRAL_SIGNATURE = 0x02014b50;
const ZIP_LOCAL_SIGNATURE = 0x04034b50;

function fail(message) {
  throw new Error(message);
}

function parseCount(attributes, name, fileName) {
  const matches = [...attributes.matchAll(new RegExp(`(?:^|\\s)${name}="([0-9]+)"`, 'g'))];
  if (matches.length !== 1)
    fail(`${fileName} must declare exactly one ${name} count`);
  const value = Number(matches[0][1]);
  if (!Number.isSafeInteger(value))
    fail(`${fileName} ${name} count exceeds the safe integer range`);
  return value;
}

function decodeXmlAttribute(value, fileName) {
  const decoded = value
    .replace(/&#x([0-9A-Fa-f]+);/g, (_, codePoint) => String.fromCodePoint(Number.parseInt(codePoint, 16)))
    .replace(/&#([0-9]+);/g, (_, codePoint) => String.fromCodePoint(Number.parseInt(codePoint, 10)))
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');
  if (/&[^;\s]+;/.test(decoded))
    fail(`${fileName} contains an unsupported XML entity in java.class.path`);
  return decoded;
}

function findEndOfCentralDirectory(bytes, fileName) {
  const minimumOffset = Math.max(0, bytes.length - 65_557);
  for (let offset = bytes.length - 22; offset >= minimumOffset; offset -= 1) {
    if (bytes.readUInt32LE(offset) !== ZIP_END_SIGNATURE)
      continue;
    const commentLength = bytes.readUInt16LE(offset + 20);
    if (offset + 22 + commentLength === bytes.length)
      return offset;
  }
  fail(`${fileName} is not a valid non-ZIP64 JAR: end record is missing`);
}

function inspectJar(path, fileName, cache) {
  const absolutePath = resolve(path);
  const cached = cache.get(absolutePath);
  if (cached !== undefined)
    return cached;

  let stats;
  try {
    stats = statSync(absolutePath);
  } catch {
    fail(`${fileName} classpath JAR is missing: ${absolutePath}`);
  }
  if (!stats.isFile())
    fail(`${fileName} classpath JAR is not a regular file: ${absolutePath}`);

  const bytes = readFileSync(absolutePath);
  if (bytes.length < 22)
    fail(`${fileName} classpath JAR is too small to be valid: ${absolutePath}`);

  let endOffset;
  try {
    endOffset = findEndOfCentralDirectory(bytes, fileName);
  } catch (error) {
    if (error instanceof RangeError)
      fail(`${fileName} classpath JAR is structurally invalid: ${absolutePath}`);
    throw error;
  }

  const disk = bytes.readUInt16LE(endOffset + 4);
  const centralDisk = bytes.readUInt16LE(endOffset + 6);
  const diskEntries = bytes.readUInt16LE(endOffset + 8);
  const entryCount = bytes.readUInt16LE(endOffset + 10);
  const centralSize = bytes.readUInt32LE(endOffset + 12);
  const centralOffset = bytes.readUInt32LE(endOffset + 16);
  if (disk !== 0 || centralDisk !== 0 || diskEntries !== entryCount
      || entryCount === 0 || entryCount === 0xffff
      || centralSize === 0xffffffff || centralOffset === 0xffffffff
      || centralOffset + centralSize !== endOffset) {
    fail(`${fileName} classpath JAR has an unsupported or invalid central directory: ${absolutePath}`);
  }

  let cursor = centralOffset;
  let markerCount = 0;
  let manifestCount = 0;
  try {
    for (let index = 0; index < entryCount; index += 1) {
      if (cursor + 46 > endOffset || bytes.readUInt32LE(cursor) !== ZIP_CENTRAL_SIGNATURE)
        fail(`${fileName} classpath JAR has an invalid central entry: ${absolutePath}`);
      const flags = bytes.readUInt16LE(cursor + 8);
      const method = bytes.readUInt16LE(cursor + 10);
      const compressedSize = bytes.readUInt32LE(cursor + 20);
      const nameLength = bytes.readUInt16LE(cursor + 28);
      const extraLength = bytes.readUInt16LE(cursor + 30);
      const commentLength = bytes.readUInt16LE(cursor + 32);
      const localOffset = bytes.readUInt32LE(cursor + 42);
      const nextCursor = cursor + 46 + nameLength + extraLength + commentLength;
      if ((flags & 0x0001) !== 0 || (method !== 0 && method !== 8)
          || nextCursor > endOffset || localOffset + 30 > centralOffset
          || bytes.readUInt32LE(localOffset) !== ZIP_LOCAL_SIGNATURE) {
        fail(`${fileName} classpath JAR has an invalid or unsupported entry: ${absolutePath}`);
      }
      const localFlags = bytes.readUInt16LE(localOffset + 6);
      const localMethod = bytes.readUInt16LE(localOffset + 8);
      const localNameLength = bytes.readUInt16LE(localOffset + 26);
      const localExtraLength = bytes.readUInt16LE(localOffset + 28);
      const localDataOffset = localOffset + 30 + localNameLength + localExtraLength;
      const centralName = bytes.subarray(cursor + 46, cursor + 46 + nameLength);
      const localName = bytes.subarray(
        localOffset + 30,
        localOffset + 30 + localNameLength,
      );
      if (localFlags !== flags || localMethod !== method
          || !centralName.equals(localName)
          || localDataOffset + compressedSize > centralOffset) {
        fail(`${fileName} classpath JAR local entry does not match its central entry: ${absolutePath}`);
      }
      const entryName = centralName.toString('utf8');
      if (entryName === SOKLET_CORE_MARKER)
        markerCount += 1;
      if (entryName === JAR_MANIFEST)
        manifestCount += 1;
      cursor = nextCursor;
    }
  } catch (error) {
    if (error instanceof RangeError)
      fail(`${fileName} classpath JAR is structurally invalid: ${absolutePath}`);
    throw error;
  }
  if (cursor !== endOffset)
    fail(`${fileName} classpath JAR central directory size is inconsistent: ${absolutePath}`);

  const result = Object.freeze({
    markerCount,
    manifestCount,
    sha256: createHash('sha256').update(bytes).digest('hex'),
  });
  cache.set(absolutePath, result);
  return result;
}

export function verifySokletCoreJar(
  path,
  expectedSha256,
  description = 'Soklet core JAR',
  cache = new Map(),
) {
  if (!SHA256_PATTERN.test(expectedSha256))
    fail(`${description} expected SHA-256 must be 64 lowercase hexadecimal characters`);
  const absolutePath = resolve(path);
  let expectedStats;
  try {
    expectedStats = lstatSync(absolutePath);
  } catch {
    fail(`${description} is missing: ${absolutePath}`);
  }
  if (!expectedStats.isFile() || expectedStats.isSymbolicLink())
    fail(`${description} must be a regular, nonsymlink file: ${absolutePath}`);
  const inspection = inspectJar(absolutePath, description, cache);
  if (inspection.sha256 !== expectedSha256)
    fail(`${description} SHA-256 differs from ${expectedSha256}: ${absolutePath}`);
  if (inspection.manifestCount !== 1 || inspection.markerCount !== 1)
    fail(`${description} is not a valid marked JAR: ${absolutePath}`);
  return Object.freeze({ path: absolutePath, sha256: inspection.sha256 });
}

function verifySokletClasspath(
  text,
  fileName,
  expectedSokletJar,
  expectedSokletSha256,
  jarCache,
) {
  const classpaths = [];
  for (const property of text.matchAll(/<property\b([^>]*)\/?\s*>/g)) {
    const name = property[1].match(/(?:^|\s)name="([^"]*)"/);
    if (name?.[1] !== 'java.class.path')
      continue;
    const values = [...property[1].matchAll(/(?:^|\s)value="([^"]*)"/g)];
    if (values.length !== 1)
      fail(`${fileName} java.class.path property must declare exactly one value`);
    classpaths.push(decodeXmlAttribute(values[0][1], fileName));
  }
  if (classpaths.length !== 1)
    fail(`${fileName} must declare exactly one java.class.path property`);

  const entries = classpaths[0].split(delimiter).map((entry) => resolve(entry));
  const expected = resolve(expectedSokletJar);
  const expectedMatches = entries.filter((entry) => entry === expected).length;
  if (expectedMatches !== 1)
    fail(`${fileName} test classpath must contain the expected Soklet core JAR exactly once: ${expected}`);

  verifySokletCoreJar(
    expected,
    expectedSokletSha256,
    `${fileName} expected Soklet core JAR`,
    jarCache,
  );

  let expectedMarkerCount = 0;
  for (const entry of entries) {
    let entryStats;
    try {
      entryStats = lstatSync(entry);
    } catch {
      fail(`${fileName} classpath entry is missing: ${entry}`);
    }
    if (entryStats.isSymbolicLink()) {
      let targetStats;
      try {
        targetStats = statSync(entry);
      } catch {
        fail(`${fileName} classpath entry is a dangling symlink: ${entry}`);
      }
      if (targetStats.isDirectory())
        fail(`${fileName} classpath entry must not be a symlink directory: ${entry}`);
    }
    if (entryStats.isDirectory()) {
      const directoryMarker = resolve(entry, SOKLET_CORE_MARKER);
      let markerExists = false;
      try {
        lstatSync(directoryMarker);
        markerExists = true;
      } catch (error) {
        if (error?.code !== 'ENOENT')
          fail(`${fileName} cannot inspect classpath directory marker: ${directoryMarker}`);
      }
      if (markerExists)
        fail(`${fileName} classpath directory contains the Soklet core marker: ${directoryMarker}`);
      continue;
    }
    // The JVM can consume a ZIP/JAR classpath entry without a .jar suffix, so
    // classify regular-file entries by content rather than by filename.
    const inspection = inspectJar(entry, fileName, jarCache);
    if (entry === expected) {
      expectedMarkerCount += inspection.markerCount;
    } else if (inspection.markerCount !== 0) {
      fail(`${fileName} classpath contains a non-expected archive with the Soklet core marker: ${entry}`);
    }
  }
  if (expectedMarkerCount !== 1)
    fail(`${fileName} expected Soklet core marker count is not exactly one: ${expected}`);
}

export function verifySurefireReports(
  directory,
  gateId = 'downstream',
  leg = 'candidate',
  expectedSokletJar = null,
  expectedSokletSha256 = null,
) {
  if ((expectedSokletJar === null) !== (expectedSokletSha256 === null))
    fail(`${gateId} ${leg} must provide both the expected Soklet JAR and SHA-256`);
  if (expectedSokletSha256 !== null && !SHA256_PATTERN.test(expectedSokletSha256))
    fail(`${gateId} ${leg} expected Soklet SHA-256 must be 64 lowercase hexadecimal characters`);
  const absoluteDirectory = resolve(directory);
  const jarCache = new Map();
  let directoryStats;
  try {
    directoryStats = lstatSync(absoluteDirectory);
  } catch {
    fail(`${gateId} ${leg} Surefire report directory is missing`);
  }
  if (!directoryStats.isDirectory() || directoryStats.isSymbolicLink())
    fail(`${gateId} ${leg} Surefire reports must be a nonsymlink directory`);

  const reportEntries = readdirSync(absoluteDirectory, { withFileTypes: true })
    .filter(({ name }) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .sort((left, right) => left.name.localeCompare(right.name, 'en'));
  if (reportEntries.length === 0)
    fail(`${gateId} ${leg} produced no Surefire TEST-*.xml reports`);

  const totals = Object.fromEntries(COUNT_NAMES.map((name) => [name, 0]));
  for (const entry of reportEntries) {
    if (!entry.isFile() || entry.isSymbolicLink())
      fail(`${gateId} ${leg} Surefire report is not a regular file: ${entry.name}`);
    const reportPath = resolve(absoluteDirectory, entry.name);
    const stats = lstatSync(reportPath);
    if (!stats.isFile() || stats.isSymbolicLink()
        || stats.size <= 0 || stats.size > MAXIMUM_REPORT_BYTES) {
      fail(`${gateId} ${leg} Surefire report has an invalid type or size: ${entry.name}`);
    }
    const bytes = readFileSync(reportPath);
    const text = bytes.toString('utf8');
    if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
      fail(`${gateId} ${leg} Surefire report is not UTF-8: ${entry.name}`);
    const suites = [...text.matchAll(/<testsuite\b([^>]*)>/g)];
    if (suites.length !== 1 || !text.includes('</testsuite>'))
      fail(`${gateId} ${leg} Surefire report must contain one test suite: ${entry.name}`);
    if (expectedSokletJar !== null) {
      verifySokletClasspath(
        text,
        entry.name,
        expectedSokletJar,
        expectedSokletSha256,
        jarCache,
      );
    }
    for (const name of COUNT_NAMES) {
      const value = parseCount(suites[0][1], name, entry.name);
      totals[name] += value;
      if (!Number.isSafeInteger(totals[name]))
        fail(`${gateId} ${leg} aggregate ${name} count exceeds the safe integer range`);
    }
  }

  if (totals.tests === 0 || totals.tests <= totals.skipped)
    fail(`${gateId} ${leg} did not execute any tests`);
  if (totals.failures !== 0 || totals.errors !== 0)
    fail(`${gateId} ${leg} Surefire reports contain failures or errors`);
  if (totals.skipped > totals.tests)
    fail(`${gateId} ${leg} Surefire skipped count exceeds its test count`);

  return Object.freeze({
    errors: totals.errors,
    failures: totals.failures,
    files: reportEntries.length,
    skipped: totals.skipped,
    tests: totals.tests,
  });
}

function main(args) {
  if (args.length === 3 && args[0] === 'verify-jar') {
    const result = verifySokletCoreJar(args[1], args[2]);
    console.log(`Verified Soklet core JAR ${result.sha256}: ${result.path}`);
    return;
  }
  if (args.length !== 3 && args.length !== 5) {
    console.error('Usage: node scripts/verify-surefire-reports.mjs <directory> <gate-id> <leg> [expected-soklet-jar expected-soklet-sha256]\n   or: node scripts/verify-surefire-reports.mjs verify-jar <soklet-jar> <expected-sha256>');
    process.exitCode = 64;
    return;
  }
  const result = verifySurefireReports(
    args[0],
    args[1],
    args[2],
    args[3] ?? null,
    args[4] ?? null,
  );
  console.log(
    `Verified ${args[1]} ${args[2]} Surefire reports: `
      + `${result.tests} tests, ${result.skipped} skipped, ${result.files} files.`,
  );
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
