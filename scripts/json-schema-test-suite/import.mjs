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
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve, sep } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repository = 'https://github.com/json-schema-org/JSON-Schema-Test-Suite';
const commit = '0c7b65dc16dd8eaa7bd83e21099c76610c3b246a';
const archiveSha256 = '405fa34d133c5a5dd3280399e0dafa379bcbf5adb17d180bd7b1b1aaa5afaa1b';
const importedRoots = [
  'LICENSE.upstream',
  'remotes/draft2019-09/ignore-prefixItems.json',
  'remotes/draft2020-12',
  'tests/draft2020-12',
];

if (process.argv.length !== 4 || process.argv[2] !== '--archive') {
  console.error('Usage: node scripts/json-schema-test-suite/import.mjs --archive <pinned-suite.tar.gz>');
  process.exit(64);
}

const archivePath = resolve(process.argv[3]);
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const destinationRoot = resolve(
  projectRoot,
  'src/test/resources/com/soklet/internal/mcp/schema/json-schema-test-suite',
);

if (existsSync(destinationRoot)) {
  throw new Error(`Destination already exists; move it aside before importing: ${destinationRoot}`);
}

const actualArchiveSha256 = sha256(readFileSync(archivePath));
if (actualArchiveSha256 !== archiveSha256) {
  throw new Error(`Archive checksum mismatch: expected ${archiveSha256}, found ${actualArchiveSha256}`);
}

const extractionRoot = mkdtempSync(join(tmpdir(), 'soklet-json-schema-test-suite.'));
try {
  execFileSync('tar', ['-xzf', archivePath, '-C', extractionRoot], { stdio: 'inherit' });
  const sourceRoot = resolve(extractionRoot, `JSON-Schema-Test-Suite-${commit}`);
  if (!lstatSync(sourceRoot).isDirectory()) {
    throw new Error(`Pinned archive did not contain the expected root: ${basename(sourceRoot)}`);
  }

  mkdirSync(destinationRoot, { recursive: true });
  copyRegularFile(resolve(sourceRoot, 'LICENSE'), resolve(destinationRoot, 'LICENSE.upstream'));
  copyTree(
    resolve(sourceRoot, 'tests/draft2020-12'),
    resolve(destinationRoot, 'tests/draft2020-12'),
  );
  copyTree(
    resolve(sourceRoot, 'remotes/draft2020-12'),
    resolve(destinationRoot, 'remotes/draft2020-12'),
  );
  copyRegularFile(
    resolve(sourceRoot, 'remotes/draft2019-09/ignore-prefixItems.json'),
    resolve(destinationRoot, 'remotes/draft2019-09/ignore-prefixItems.json'),
  );

  const importedPaths = collectRegularFiles(destinationRoot);
  const manifest = importedPaths
    .map((path) => `${sha256(readFileSync(resolve(destinationRoot, path)))}  ${path}`)
    .join('\n') + '\n';
  const manifestBytes = Buffer.from(manifest, 'ascii');
  writeFileSync(resolve(destinationRoot, 'manifest.sha256'), manifestBytes);

  const pin = {
    importFormat: 1,
    repository,
    commit,
    archiveSha256,
    importedRoots,
    importedFileCount: importedPaths.length,
    manifestSha256: sha256(manifestBytes),
    license: 'MIT',
    licenseSha256: sha256(readFileSync(resolve(destinationRoot, 'LICENSE.upstream'))),
  };
  writeFileSync(
    resolve(destinationRoot, 'upstream-pin.json'),
    `${JSON.stringify(pin, null, 2)}\n`,
    'utf8',
  );

  console.log(
    `Imported ${importedPaths.length} pinned JSON Schema Test Suite files at ${commit}.`,
  );
} finally {
  rmSync(extractionRoot, { recursive: true, force: true });
}

function copyTree(sourceDirectory, destinationDirectory) {
  const sourceStats = lstatSync(sourceDirectory);
  if (!sourceStats.isDirectory() || sourceStats.isSymbolicLink()) {
    throw new Error(`Upstream corpus entry is not a regular directory: ${sourceDirectory}`);
  }
  mkdirSync(destinationDirectory, { recursive: true });

  for (const entry of bytewiseEntries(sourceDirectory)) {
    const source = resolve(sourceDirectory, entry.name);
    const destination = resolve(destinationDirectory, entry.name);
    if (entry.isSymbolicLink()) {
      throw new Error(`Upstream corpus must not contain symbolic links: ${source}`);
    }
    if (entry.isDirectory()) {
      copyTree(source, destination);
    } else if (entry.isFile()) {
      if (!entry.name.endsWith('.json')) {
        throw new Error(`Unexpected non-JSON upstream corpus file: ${source}`);
      }
      copyRegularFile(source, destination);
    } else {
      throw new Error(`Unexpected upstream corpus entry: ${source}`);
    }
  }
}

function copyRegularFile(source, destination) {
  const stats = lstatSync(source);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`Upstream corpus entry is not a regular file: ${source}`);
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
        throw new Error(`Imported corpus must not contain symbolic links: ${absolutePath}`);
      }
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else if (entry.isFile()) {
        paths.push(relative(root, absolutePath).split(sep).join('/'));
      } else {
        throw new Error(`Unexpected imported corpus entry: ${absolutePath}`);
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
