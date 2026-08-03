#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve, sep } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const specificationRepository = 'https://github.com/json-schema-org/json-schema-spec';
const specificationCommit = '601a66c8b0f25246bf0e1fb488c5b5f030a79b72';
const specificationArchiveSha256 =
  '578dd7a1fcf66f46aaf2af4f45a37f3205c6c572f838f45fe89cb2e19f9164a1';
const specificationArchiveByteCount = 107257;
const websiteRepository = 'https://github.com/json-schema-org/website';
const websiteCommit = '77cc0650649558df71b0c5a404486dce3d95c81a';
const licenseSha256 =
  '909b25a80d4945b21d3adb2fb17f90bf592e0274bdf117c069c088a8e44dc7b6';
const licenseByteCount = 11739;
const importedSourcePaths = [
  'README.md',
  'schema.json',
  'meta/applicator.json',
  'meta/content.json',
  'meta/core.json',
  'meta/format-annotation.json',
  'meta/format-assertion.json',
  'meta/meta-data.json',
  'meta/unevaluated.json',
  'meta/validation.json',
];

if (process.argv.length !== 6 || process.argv[2] !== '--archive' ||
    process.argv[4] !== '--license') {
  console.error(
    'Usage: node scripts/json-schema-draft-2020-12/import.mjs ' +
    '--archive <pinned-spec.tar.gz> --license <pinned-license>',
  );
  process.exit(64);
}

const archivePath = resolve(process.argv[3]);
const licensePath = resolve(process.argv[5]);
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const destinationRoot = resolve(
  projectRoot,
  'src/main/resources/com/soklet/internal/mcp/schema/draft-2020-12',
);

if (existsSync(destinationRoot)) {
  throw new Error(`Destination already exists; move it aside before importing: ${destinationRoot}`);
}

verifyInput(archivePath, specificationArchiveByteCount,
  specificationArchiveSha256, 'Specification archive');
verifyInput(licensePath, licenseByteCount, licenseSha256, 'License');

const extractionRoot = mkdtempSync(join(tmpdir(), 'soklet-json-schema-spec.'));
try {
  execFileSync('tar', ['-xzf', archivePath, '-C', extractionRoot], { stdio: 'inherit' });
  const sourceRoot = resolve(extractionRoot,
    `json-schema-spec-${specificationCommit}`);
  if (!lstatSync(sourceRoot).isDirectory()) {
    throw new Error(`Pinned archive did not contain the expected root: ${basename(sourceRoot)}`);
  }

  mkdirSync(destinationRoot, { recursive: true });
  copyRegularFile(licensePath, resolve(destinationRoot, 'LICENSE.upstream'));
  for (const sourcePath of importedSourcePaths) {
    const destinationPath = sourcePath === 'README.md'
      ? 'README.upstream.md' : sourcePath;
    copyRegularFile(resolve(sourceRoot, sourcePath),
      resolve(destinationRoot, destinationPath));
  }

  const importedPaths = collectRegularFiles(destinationRoot);
  const manifest = importedPaths
    .map((path) => `${sha256(readFileSync(resolve(destinationRoot, path)))}  ${path}`)
    .join('\n') + '\n';
  const manifestBytes = Buffer.from(manifest, 'ascii');
  writeFileSync(resolve(destinationRoot, 'manifest.sha256'), manifestBytes);

  const pin = {
    importFormat: 1,
    specificationRepository,
    specificationCommit,
    specificationArchiveSha256,
    specificationArchiveByteCount,
    websiteRepository,
    websiteCommit,
    licenseSource:
      `https://raw.githubusercontent.com/json-schema-org/website/${websiteCommit}/LICENSE`,
    license: 'BSD-3-Clause OR AFL-3.0',
    licenseSha256,
    licenseByteCount,
    importedFileCount: importedPaths.length,
    manifestSha256: sha256(manifestBytes),
  };
  writeFileSync(
    resolve(destinationRoot, 'upstream-pin.json'),
    `${JSON.stringify(pin, null, 2)}\n`,
    'utf8',
  );

  console.log(
    `Imported ${importedPaths.length} pinned Draft 2020-12 bundle files at ` +
    `${specificationCommit}.`,
  );
} finally {
  rmSync(extractionRoot, { recursive: true, force: true });
}

function verifyInput(path, expectedByteCount, expectedSha256, label) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`${label} must be a regular file: ${path}`);
  }
  if (statSync(path).size !== expectedByteCount) {
    throw new Error(`${label} byte count mismatch`);
  }
  const actualSha256 = sha256(readFileSync(path));
  if (actualSha256 !== expectedSha256) {
    throw new Error(
      `${label} checksum mismatch: expected ${expectedSha256}, found ${actualSha256}`,
    );
  }
}

function copyRegularFile(source, destination) {
  const stats = lstatSync(source);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`Upstream bundle entry is not a regular file: ${source}`);
  }
  mkdirSync(dirname(destination), { recursive: true });
  copyFileSync(source, destination);
}

function collectRegularFiles(root) {
  const paths = [];
  visit(root);
  paths.sort(bytewiseCompare);
  return paths;

  function visit(directory) {
    for (const entry of bytewiseEntries(directory)) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) {
        throw new Error(`Imported bundle must not contain symbolic links: ${absolutePath}`);
      }
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else if (entry.isFile()) {
        paths.push(relative(root, absolutePath).split(sep).join('/'));
      } else {
        throw new Error(`Unexpected imported bundle entry: ${absolutePath}`);
      }
    }
  }
}

function bytewiseEntries(directory) {
  return readdirSync(directory, { withFileTypes: true })
    .sort((left, right) => bytewiseCompare(left.name, right.name));
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left), Buffer.from(right));
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
