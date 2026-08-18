#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, resolve } from 'node:path';
import { verifySurefireReports } from './verify-surefire-reports.mjs';

const root = mkdtempSync(resolve(tmpdir(), 'soklet-surefire-verifier-'));

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1)
      crc = (crc >>> 1) ^ ((crc & 1) === 0 ? 0 : 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function storedZip(entries) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const [name, value] of entries) {
    const nameBytes = Buffer.from(name, 'utf8');
    const valueBytes = Buffer.from(value);
    const checksum = crc32(valueBytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(valueBytes.length, 18);
    local.writeUInt32LE(valueBytes.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(valueBytes.length, 20);
    central.writeUInt32LE(valueBytes.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(localOffset, 42);
    localParts.push(local, nameBytes, valueBytes);
    centralParts.push(central, nameBytes);
    localOffset += local.length + nameBytes.length + valueBytes.length;
  }
  const centralBytes = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(localOffset, 16);
  return Buffer.concat([...localParts, centralBytes, end]);
}

function coreJar(markerValue = 'class bytes') {
  return storedZip([
    ['META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n'],
    ['com/soklet/Soklet.class', markerValue],
  ]);
}

function digest(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function report(
  name,
  { tests, errors = 0, skipped = 0, failures = 0 },
  classpath = null,
) {
  const directory = resolve(root, name);
  mkdirSync(directory, { recursive: true });
  const properties = classpath === null
    ? ''
    : `<properties><property name="java.class.path" value="${classpath}"/></properties>\n`;
  writeFileSync(
    resolve(directory, 'TEST-example.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>\n`
      + `<testsuite name="example" tests="${tests}" errors="${errors}" `
      + `skipped="${skipped}" failures="${failures}">\n`
      + `${properties}</testsuite>\n`,
  );
  return directory;
}

try {
  assert.deepEqual(
    verifySurefireReports(report('pass', { tests: 3, skipped: 1 }), 'fixture', 'candidate'),
    { errors: 0, failures: 0, files: 1, skipped: 1, tests: 3 },
  );
  assert.throws(
    () => verifySurefireReports(report('failure', { tests: 1, failures: 1 })),
    /failures or errors/,
  );
  assert.throws(
    () => verifySurefireReports(report('error', { tests: 1, errors: 1 })),
    /failures or errors/,
  );
  assert.throws(
    () => verifySurefireReports(report('all-skipped', { tests: 2, skipped: 2 })),
    /did not execute any tests/,
  );
  const empty = resolve(root, 'empty');
  mkdirSync(empty);
  assert.throws(() => verifySurefireReports(empty), /produced no Surefire/);

  const candidateJar = resolve(
    root,
    'repository/com/soklet/soklet/3.6.0/soklet-3.6.0.jar',
  );
  const oldJar = resolve(
    root,
    'repository/com/soklet/soklet/3.5.1/soklet-3.5.1.jar',
  );
  mkdirSync(dirname(candidateJar), { recursive: true });
  mkdirSync(dirname(oldJar), { recursive: true });
  writeFileSync(candidateJar, coreJar('candidate'));
  writeFileSync(oldJar, coreJar('old'));
  const candidateSha256 = digest(candidateJar);
  const classDirectory = resolve(root, 'classes');
  mkdirSync(classDirectory);
  assert.equal(
    verifySurefireReports(
      report('candidate-classpath', { tests: 1 }, `${classDirectory}${delimiter}${candidateJar}`),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ).tests,
    1,
  );
  assert.throws(
    () => verifySurefireReports(
      report('wrong-classpath', { tests: 1 }, `${classDirectory}${delimiter}${oldJar}`),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /expected Soklet core JAR exactly once/,
  );
  assert.throws(
    () => verifySurefireReports(
      report(
        'duplicate-classpath',
        { tests: 1 },
        `${classDirectory}${delimiter}${candidateJar}${delimiter}${oldJar}`,
      ),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /non-expected archive with the Soklet core marker/,
  );

  const shadowDirectory = resolve(root, 'shadow-classes');
  const shadowMarker = resolve(shadowDirectory, 'com/soklet/Soklet.class');
  mkdirSync(dirname(shadowMarker), { recursive: true });
  writeFileSync(shadowMarker, 'shadow class');
  assert.throws(
    () => verifySurefireReports(
      report(
        'directory-shadow-first',
        { tests: 1 },
        `${shadowDirectory}${delimiter}${candidateJar}`,
      ),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /classpath directory contains the Soklet core marker/,
  );

  const realDirectory = resolve(root, 'real-classes');
  const symlinkDirectory = resolve(root, 'symlink-classes');
  mkdirSync(realDirectory);
  symlinkSync(realDirectory, symlinkDirectory);
  assert.throws(
    () => verifySurefireReports(
      report(
        'symlink-directory',
        { tests: 1 },
        `${symlinkDirectory}${delimiter}${candidateJar}`,
      ),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /must not be a symlink directory/,
  );

  const symlinkMarkerDirectory = resolve(root, 'symlink-marker-classes');
  const symlinkMarker = resolve(symlinkMarkerDirectory, 'com/soklet/Soklet.class');
  mkdirSync(dirname(symlinkMarker), { recursive: true });
  symlinkSync(candidateJar, symlinkMarker);
  assert.throws(
    () => verifySurefireReports(
      report(
        'symlink-marker',
        { tests: 1 },
        `${symlinkMarkerDirectory}${delimiter}${candidateJar}`,
      ),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /classpath directory contains the Soklet core marker/,
  );
  assert.throws(
    () => verifySurefireReports(
      report('missing-classpath', { tests: 1 }),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /exactly one java\.class\.path/,
  );
  const missingJar = resolve(
    root,
    'repository/com/soklet/soklet/3.6.1/soklet-3.6.1.jar',
  );
  assert.throws(
    () => verifySurefireReports(
      report('missing-jar', { tests: 1 }, `${classDirectory}${delimiter}${missingJar}`),
      'fixture',
      'candidate',
      missingJar,
      '0'.repeat(64),
    ),
    /expected Soklet core JAR is missing/,
  );

  const renamedOldCore = resolve(root, 'repository/renamed-dependency.bin');
  writeFileSync(renamedOldCore, coreJar('renamed old core'));
  assert.throws(
    () => verifySurefireReports(
      report(
        'renamed-old-core-first',
        { tests: 1 },
        `${renamedOldCore}${delimiter}${candidateJar}${delimiter}${classDirectory}`,
      ),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /non-expected archive with the Soklet core marker/,
  );

  const plainJar = resolve(root, 'repository/plain.jar');
  writeFileSync(plainJar, 'not a JAR');
  assert.throws(
    () => verifySurefireReports(
      report('plain-expected', { tests: 1 }, plainJar),
      'fixture',
      'candidate',
      plainJar,
      digest(plainJar),
    ),
    /too small to be valid/,
  );

  const corruptJar = resolve(root, 'repository/corrupt.jar');
  const corruptBytes = coreJar('corrupt');
  corruptBytes.writeUInt32LE(0, corruptBytes.length - 22);
  writeFileSync(corruptJar, corruptBytes);
  assert.throws(
    () => verifySurefireReports(
      report('corrupt-expected', { tests: 1 }, corruptJar),
      'fixture',
      'candidate',
      corruptJar,
      digest(corruptJar),
    ),
    /end record is missing/,
  );

  const symlinkJar = resolve(root, 'repository/symlink.jar');
  symlinkSync(candidateJar, symlinkJar);
  assert.throws(
    () => verifySurefireReports(
      report('symlink-expected', { tests: 1 }, symlinkJar),
      'fixture',
      'candidate',
      symlinkJar,
      candidateSha256,
    ),
    /regular, nonsymlink file/,
  );

  assert.throws(
    () => verifySurefireReports(
      report('wrong-bytes', { tests: 1 }, candidateJar),
      'fixture',
      'candidate',
      candidateJar,
      'f'.repeat(64),
    ),
    /SHA-256 differs/,
  );

  writeFileSync(candidateJar, coreJar('post-install mutation'));
  assert.throws(
    () => verifySurefireReports(
      report('post-install-mutation', { tests: 1 }, candidateJar),
      'fixture',
      'candidate',
      candidateJar,
      candidateSha256,
    ),
    /SHA-256 differs/,
  );
  console.log('Surefire report verifier self-test passed.');
} finally {
  rmSync(root, { force: true, recursive: true });
}
