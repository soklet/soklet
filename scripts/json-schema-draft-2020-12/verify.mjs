#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

if (process.argv.length !== 2) {
  console.error('Usage: node scripts/json-schema-draft-2020-12/verify.mjs');
  process.exit(64);
}

const expectedSpecificationRepository =
  'https://github.com/json-schema-org/json-schema-spec';
const expectedSpecificationCommit =
  '601a66c8b0f25246bf0e1fb488c5b5f030a79b72';
const expectedSpecificationArchiveSha256 =
  '578dd7a1fcf66f46aaf2af4f45a37f3205c6c572f838f45fe89cb2e19f9164a1';
const expectedSpecificationArchiveByteCount = 107257;
const expectedWebsiteRepository = 'https://github.com/json-schema-org/website';
const expectedWebsiteCommit = '77cc0650649558df71b0c5a404486dce3d95c81a';
const expectedLicenseSource =
  `https://raw.githubusercontent.com/json-schema-org/website/${expectedWebsiteCommit}/LICENSE`;
const expectedLicenseSha256 =
  '909b25a80d4945b21d3adb2fb17f90bf592e0274bdf117c069c088a8e44dc7b6';
const expectedLicenseByteCount = 11739;
const expectedManifestSha256 =
  '3c7a6495a01028e007b0afe3841e0523871bc3afd4d7d788c95c9f30633b200c';
const expectedPinSha256 =
  '17669be20eb59aad1b4a953c99501b55cb8332d6f0ebf7fb4746177081d6632b';
const expectedFileHashes = new Map([
  ['LICENSE.upstream', expectedLicenseSha256],
  ['README.upstream.md', '54848e8b5b5932577091349eef76cc567be48d31f760c184bb364be0c758477b'],
  ['meta/applicator.json', 'bf273b26f9f735b93ece78f2b61b36676e1d122ce78ab37ad5a2e45dfa1ca2b1'],
  ['meta/content.json', 'a10456605b2b5bb12a1b4dcfc0300f02f54d3e8bb3646bed7724583866627682'],
  ['meta/core.json', '21f79d143fab1f180245c331e5657057045b36794d41fe151e6e4fed65035299'],
  ['meta/format-annotation.json', '5c79404f831dd905c0f40fefac7c6f3e51bf3729b4a876a5c2020178d97f3bcc'],
  ['meta/format-assertion.json', '6a5a8e13c605e3eff51f9bf8da18078880d81ff1634e391760ccc2e16ee2146f'],
  ['meta/meta-data.json', 'c664d438a84d58889c8edecd248ce2f945a4bc0e3b087323b11303dc136abfbe'],
  ['meta/unevaluated.json', 'fc99f32188da41689a9382af174dd42e8b255e4374965c157b8286556b4ab2bc'],
  ['meta/validation.json', 'e921c5b79264d3689af01c1af1ffdf692e09f1c45df90a0f08eb7288c9acdeab'],
  ['schema.json', '41da76f5afb7ce062d248f762463a92f7ca47e4e0f905b224ba6afeef91ded0f'],
]);
const expectedSchemaIds = new Map([
  ['schema.json', 'https://json-schema.org/draft/2020-12/schema'],
  ['meta/applicator.json', 'https://json-schema.org/draft/2020-12/meta/applicator'],
  ['meta/content.json', 'https://json-schema.org/draft/2020-12/meta/content'],
  ['meta/core.json', 'https://json-schema.org/draft/2020-12/meta/core'],
  ['meta/format-annotation.json', 'https://json-schema.org/draft/2020-12/meta/format-annotation'],
  ['meta/format-assertion.json', 'https://json-schema.org/draft/2020-12/meta/format-assertion'],
  ['meta/meta-data.json', 'https://json-schema.org/draft/2020-12/meta/meta-data'],
  ['meta/unevaluated.json', 'https://json-schema.org/draft/2020-12/meta/unevaluated'],
  ['meta/validation.json', 'https://json-schema.org/draft/2020-12/meta/validation'],
]);
const expectedDefaultVocabularies = {
  'https://json-schema.org/draft/2020-12/vocab/core': true,
  'https://json-schema.org/draft/2020-12/vocab/applicator': true,
  'https://json-schema.org/draft/2020-12/vocab/unevaluated': true,
  'https://json-schema.org/draft/2020-12/vocab/validation': true,
  'https://json-schema.org/draft/2020-12/vocab/meta-data': true,
  'https://json-schema.org/draft/2020-12/vocab/format-annotation': true,
  'https://json-schema.org/draft/2020-12/vocab/content': true,
};

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(scriptDirectory, '../..');
const bundleRoot = resolve(
  projectRoot,
  'src/main/resources/com/soklet/internal/mcp/schema/draft-2020-12',
);
const pinPath = resolve(bundleRoot, 'upstream-pin.json');
const manifestPath = resolve(bundleRoot, 'manifest.sha256');

verifyRootShape();

const pinBytes = readFileSync(pinPath);
if (sha256(pinBytes) !== expectedPinSha256) {
  throw new Error('Draft 2020-12 pin bytes do not match the reviewed pin');
}
const pin = JSON.parse(decodeUtf8(pinBytes, 'upstream-pin.json'));
const expectedPinKeys = [
  'importFormat',
  'importedFileCount',
  'license',
  'licenseByteCount',
  'licenseSha256',
  'licenseSource',
  'manifestSha256',
  'specificationArchiveByteCount',
  'specificationArchiveSha256',
  'specificationCommit',
  'specificationRepository',
  'websiteCommit',
  'websiteRepository',
];
if (JSON.stringify(Object.keys(pin).sort(bytewiseCompare)) !==
    JSON.stringify(expectedPinKeys)) {
  throw new Error('Draft 2020-12 pin contains missing or unexpected fields');
}
if (pin.importFormat !== 1 || pin.importedFileCount !== 11 ||
    pin.specificationRepository !== expectedSpecificationRepository ||
    pin.specificationCommit !== expectedSpecificationCommit ||
    pin.specificationArchiveSha256 !== expectedSpecificationArchiveSha256 ||
    pin.specificationArchiveByteCount !== expectedSpecificationArchiveByteCount ||
    pin.websiteRepository !== expectedWebsiteRepository ||
    pin.websiteCommit !== expectedWebsiteCommit ||
    pin.licenseSource !== expectedLicenseSource ||
    pin.license !== 'BSD-3-Clause OR AFL-3.0' ||
    pin.licenseSha256 !== expectedLicenseSha256 ||
    pin.licenseByteCount !== expectedLicenseByteCount ||
    pin.manifestSha256 !== expectedManifestSha256) {
  throw new Error('Draft 2020-12 pin does not match the reviewed upstream selection');
}

const manifestBytes = readFileSync(manifestPath);
const manifest = manifestBytes.toString('ascii');
if (manifest.includes('\r') || !manifest.endsWith('\n') ||
    sha256(manifestBytes) !== expectedManifestSha256) {
  throw new Error('Draft 2020-12 manifest bytes do not match the reviewed manifest');
}

const lines = manifest.slice(0, -1).split('\n');
const manifestPaths = new Set();
let previousPath;
for (const [index, line] of lines.entries()) {
  const match = /^([0-9a-f]{64})  ([A-Za-z0-9._/-]+)$/.exec(line);
  if (match === null) {
    throw new Error(`Malformed Draft 2020-12 manifest row ${index + 1}`);
  }
  const [, expectedSha256, relativePath] = match;
  if (unsafePath(relativePath) ||
      (previousPath !== undefined && bytewiseCompare(previousPath, relativePath) >= 0)) {
    throw new Error(`Unsafe or unsorted Draft 2020-12 manifest path: ${relativePath}`);
  }
  previousPath = relativePath;
  if (manifestPaths.has(relativePath)) {
    throw new Error(`Duplicate Draft 2020-12 manifest path: ${relativePath}`);
  }
  manifestPaths.add(relativePath);
  if (expectedFileHashes.get(relativePath) !== expectedSha256) {
    throw new Error(`Unreviewed Draft 2020-12 manifest entry: ${relativePath}`);
  }

  const absolutePath = resolve(bundleRoot, relativePath);
  if (!absolutePath.startsWith(`${bundleRoot}${sep}`)) {
    throw new Error(`Draft 2020-12 manifest path escapes its root: ${relativePath}`);
  }
  const stats = lstatSync(absolutePath);
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new Error(`Draft 2020-12 entry is not a regular file: ${relativePath}`);
  }
  const bytes = readFileSync(absolutePath);
  if (sha256(bytes) !== expectedSha256) {
    throw new Error(`Draft 2020-12 checksum mismatch: ${relativePath}`);
  }
}
if (manifestPaths.size !== expectedFileHashes.size ||
    [...expectedFileHashes.keys()].some((path) => !manifestPaths.has(path))) {
  throw new Error('Draft 2020-12 manifest membership is incomplete');
}

const actualPaths = collectRegularFiles(bundleRoot)
  .filter((path) => path !== 'manifest.sha256' && path !== 'upstream-pin.json');
if (actualPaths.length !== manifestPaths.size ||
    actualPaths.some((path) => !manifestPaths.has(path))) {
  throw new Error('Draft 2020-12 files do not exactly match manifest.sha256');
}

const schemasById = new Map();
for (const [path, expectedId] of expectedSchemaIds) {
  const schema = JSON.parse(decodeUtf8(readFileSync(resolve(bundleRoot, path)), path));
  if (schema.$id !== expectedId ||
      schema.$schema !== 'https://json-schema.org/draft/2020-12/schema' ||
      schema.$dynamicAnchor !== 'meta') {
    throw new Error(`Draft 2020-12 identity mismatch: ${path}`);
  }
  schemasById.set(expectedId, schema);
}

const root = schemasById.get('https://json-schema.org/draft/2020-12/schema');
if (JSON.stringify(root.$vocabulary) !== JSON.stringify(expectedDefaultVocabularies)) {
  throw new Error('Draft 2020-12 default vocabulary declaration changed');
}
const expectedAllOf = [...Object.keys(expectedDefaultVocabularies)]
  .map((uri) => uri.replace('/vocab/', '/meta/'));
const actualAllOf = root.allOf.map((entry) => new URL(entry.$ref, root.$id).href);
if (JSON.stringify(actualAllOf) !== JSON.stringify(expectedAllOf)) {
  throw new Error('Draft 2020-12 default meta-schema closure changed');
}
if ('https://json-schema.org/draft/2020-12/vocab/format-assertion' in
    root.$vocabulary) {
  throw new Error('Draft 2020-12 default dialect must not enable format assertion');
}

for (const [id, schema] of schemasById) {
  visit(schema, id);
}

console.log(
  `Verified ${expectedFileHashes.size} pinned Draft 2020-12 bundle files at ` +
  `${expectedSpecificationCommit}.`,
);

function visit(value, baseId) {
  if (Array.isArray(value)) {
    for (const element of value) visit(element, baseId);
    return;
  }
  if (value === null || typeof value !== 'object') return;

  const localBase = typeof value.$id === 'string'
    ? new URL(value.$id, baseId).href : baseId;
  for (const keyword of ['$schema', '$ref', '$dynamicRef']) {
    if (typeof value[keyword] !== 'string') continue;
    const resolved = new URL(value[keyword], localBase);
    resolved.hash = '';
    if (!schemasById.has(resolved.href)) {
      throw new Error(`Draft 2020-12 bundle has an external ${keyword}: ${value[keyword]}`);
    }
  }
  for (const child of Object.values(value)) visit(child, localBase);
}

function verifyRootShape() {
  const stats = lstatSync(bundleRoot);
  if (!stats.isDirectory() || stats.isSymbolicLink()) {
    throw new Error('Draft 2020-12 bundle root must be a real directory');
  }
  const expectedNames = [
    'LICENSE.upstream',
    'README.upstream.md',
    'manifest.sha256',
    'meta',
    'schema.json',
    'upstream-pin.json',
  ];
  const entries = bytewiseEntries(bundleRoot);
  if (entries.length !== expectedNames.length ||
      entries.some((entry, index) => entry.name !== expectedNames[index])) {
    throw new Error(`Draft 2020-12 bundle root must contain exactly: ${expectedNames.join(', ')}`);
  }
  for (const entry of entries) {
    const directory = entry.name === 'meta';
    if (entry.isSymbolicLink() || (directory ? !entry.isDirectory() : !entry.isFile())) {
      throw new Error(`Unexpected Draft 2020-12 root entry type: ${entry.name}`);
    }
  }

	const expectedMetaNames = [
	  'applicator.json',
	  'content.json',
	  'core.json',
	  'format-annotation.json',
	  'format-assertion.json',
	  'meta-data.json',
	  'unevaluated.json',
	  'validation.json',
	];
	const metaEntries = bytewiseEntries(resolve(bundleRoot, 'meta'));
	if (metaEntries.length !== expectedMetaNames.length ||
	    metaEntries.some((entry, index) => entry.name !== expectedMetaNames[index] ||
	      entry.isSymbolicLink() || !entry.isFile())) {
	  throw new Error(
	    `Draft 2020-12 meta directory must contain exactly: ${expectedMetaNames.join(', ')}`,
	  );
	}
}

function collectRegularFiles(rootDirectory) {
  const paths = [];
  visitDirectory(rootDirectory);
  paths.sort(bytewiseCompare);
  return paths;

  function visitDirectory(directory) {
    for (const entry of bytewiseEntries(directory)) {
      const absolutePath = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) {
        throw new Error(`Draft 2020-12 bundle must not contain symbolic links: ${absolutePath}`);
      }
      if (entry.isDirectory()) {
        visitDirectory(absolutePath);
      } else if (entry.isFile()) {
        paths.push(relative(rootDirectory, absolutePath).split(sep).join('/'));
      } else {
        throw new Error(`Unexpected Draft 2020-12 bundle entry: ${absolutePath}`);
      }
    }
  }
}

function decodeUtf8(bytes, path) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (error) {
    throw new Error(`Draft 2020-12 file is not valid UTF-8: ${path}`, { cause: error });
  }
}

function unsafePath(path) {
  return path.startsWith('/') || path.startsWith('../') || path === '..' ||
    path.includes('/../') || path.endsWith('/..') || path.includes('//');
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
