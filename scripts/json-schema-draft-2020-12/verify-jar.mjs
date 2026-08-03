#!/usr/bin/env node

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

if (process.argv.length !== 4 || process.argv[2] !== '--directory') {
  console.error(
    'Usage: node scripts/json-schema-draft-2020-12/verify-jar.mjs ' +
    '--directory <Maven-target-directory>',
  );
  process.exit(64);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const sourceRoot = resolve(
  projectRoot,
  'src/main/resources/com/soklet/internal/mcp/schema/draft-2020-12',
);
const targetDirectory = resolve(process.argv[3]);
const jarCandidates = readdirSync(targetDirectory, { withFileTypes: true })
  .filter((entry) => entry.isFile() && entry.name.endsWith('.jar') &&
    !entry.name.endsWith('-sources.jar') &&
    !entry.name.endsWith('-javadoc.jar') &&
    !entry.name.startsWith('original-'))
  .map((entry) => resolve(targetDirectory, entry.name));
if (jarCandidates.length !== 1) {
  throw new Error(
    `Expected exactly one main project JAR in ${targetDirectory}, found ${jarCandidates.length}`,
  );
}
const jarPath = jarCandidates[0];
const jarPrefix = 'com/soklet/internal/mcp/schema/draft-2020-12/';
const expectedPaths = collectRegularFiles(sourceRoot);
const listedPaths = execFileSync('unzip', ['-Z1', jarPath], {
  encoding: 'utf8',
  maxBuffer: 4 * 1024 * 1024,
}).split('\n').filter((path) => path.startsWith(jarPrefix) &&
  !path.endsWith('/')).map((path) => path.slice(jarPrefix.length));

listedPaths.sort(bytewiseCompare);
if (JSON.stringify(listedPaths) !== JSON.stringify(expectedPaths)) {
  throw new Error('Packaged Draft 2020-12 resource membership differs from src/main/resources');
}

for (const relativePath of expectedPaths) {
  const sourceBytes = readFileSync(resolve(sourceRoot, relativePath));
  const packagedBytes = execFileSync('unzip', [
    '-p', jarPath, `${jarPrefix}${relativePath}`,
  ], { encoding: 'buffer', maxBuffer: 4 * 1024 * 1024 });
  if (!sourceBytes.equals(packagedBytes)) {
    throw new Error(`Packaged Draft 2020-12 bytes differ: ${relativePath}`);
  }
}

console.log(
  `Verified ${expectedPaths.length} Draft 2020-12 resources in ${jarPath}.`,
);

function collectRegularFiles(root) {
  const paths = [];
  visit(root);
  paths.sort(bytewiseCompare);
  return paths;

  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isDirectory()) {
        visit(absolutePath);
      } else if (entry.isFile()) {
        paths.push(relative(root, absolutePath).split(sep).join('/'));
      } else {
        throw new Error(`Unexpected source resource entry: ${absolutePath}`);
      }
    }
  }
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left), Buffer.from(right));
}
