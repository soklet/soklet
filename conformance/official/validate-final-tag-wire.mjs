#!/usr/bin/env node

import { createRequire } from 'node:module';
import {
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  readCanonicalJson,
  sha256,
  sourceTreeIdentity,
  verifyManifestSet,
  verifyOfficialSuite,
} from './verify.mjs';

const officialRoot = resolve(dirname(fileURLToPath(import.meta.url)));
const schemaIdentifier = 'urn:soklet:mcp-schema:2026-07-28';

export function validateFinalTagWire({ suiteDirectory, root = officialRoot }) {
  const { pins } = verifyManifestSet(root);
  const suiteInputs = verifyOfficialSuite(suiteDirectory, pins);
  const before = suiteInputHashes(suiteInputs);
  const specification = pins.finalSpecification;
  const schemaPath = resolve(root, specification.schema.vendoredPath);
  const licensePath = resolve(root, specification.license.vendoredPath);
  const schemaBytes = readFileSync(schemaPath);
  const licenseBytes = readFileSync(licensePath);
  assertArtifact(
    schemaPath,
    schemaBytes,
    specification.schema.bytes,
    specification.schema.sha256,
    'final tagged schema',
  );
  assertArtifact(
    licensePath,
    licenseBytes,
    specification.license.bytes,
    specification.license.sha256,
    'final specification license',
  );

  const schema = JSON.parse(schemaBytes.toString('utf8'));
  if (schema === null || typeof schema !== 'object' || Array.isArray(schema)
      || Object.keys(schema).sort().join(',') !== '$defs,$schema'
      || schema.$defs === null || typeof schema.$defs !== 'object'
      || Object.keys(schema.$defs).length !== specification.schema.definitionCount) {
    throw new Error('Final tagged schema root shape or definition count changed');
  }
  if (schema.$defs.SubscriptionsListenResultResponse === undefined
      || schema.$defs.SubscriptionsListenResultMetaObject === undefined
      || schema.$defs.SubscriptionsListenResultMeta !== undefined) {
    throw new Error('Final subscription terminal definitions do not match the reviewed tag');
  }

  const manifestPath = resolve(root, 'golden-wire', 'manifest.json');
  const manifest = readCanonicalJson(manifestPath, 'golden-wire manifest');
  verifyManifestIdentity(manifest, specification);
  const goldenRoot = resolve(root, 'golden-wire');
  const manifestedPaths = new Set();
  const fixtureNames = new Set();

  const requireFromSuite = createRequire(resolve(suiteDirectory, 'package.json'));
  const ajvVersion = requireFromSuite('ajv/package.json').version;
  const ajvFormatsVersion = requireFromSuite('ajv-formats/package.json').version;
  const expectedDependencies = pins.officialConformanceSuite.lockedSchemaDependencies;
  if (ajvVersion !== expectedDependencies.ajv
      || ajvFormatsVersion !== expectedDependencies['ajv-formats']) {
    throw new Error(
      `Pinned validator dependency mismatch: ajv ${ajvVersion}, ajv-formats ${ajvFormatsVersion}`,
    );
  }
  const Ajv2020 = requireFromSuite('ajv/dist/2020').default;
  const addFormats = requireFromSuite('ajv-formats').default;
  const ajv = new Ajv2020({ strict: false, allErrors: true });
  addFormats(ajv);
  ajv.addFormat('byte', true);
  ajv.addSchema({ ...schema, $id: schemaIdentifier });

  const validated = [];
  let previousPath;
  for (const fixture of manifest.fixtures) {
    verifyFixtureRecord(fixture);
    if (!fixtureNames.add(fixture.name))
      throw new Error(`Duplicate golden-wire fixture name ${fixture.name}`);
    if (!manifestedPaths.add(fixture.path))
      throw new Error(`Duplicate golden-wire fixture path ${fixture.path}`);
    if (previousPath !== undefined && bytewiseCompare(previousPath, fixture.path) >= 0)
      throw new Error('Golden-wire fixtures must be bytewise path-sorted');
    previousPath = fixture.path;

    const fixturePath = containedRegularFile(goldenRoot, fixture.path);
    const bytes = readFileSync(fixturePath);
    if (sha256(bytes) !== fixture.sha256)
      throw new Error(`Golden-wire checksum mismatch for ${fixture.path}`);
    const text = bytes.toString('utf8');
    if (Buffer.from(text, 'utf8').compare(bytes) !== 0 || text.includes('\r')
        || !text.endsWith('\n') || text.slice(0, -1).includes('\n')) {
      throw new Error(`Golden-wire fixture ${fixture.path} must be one compact UTF-8 JSON line`);
    }
    const definition = fixture.schemaRef.slice('#/$defs/'.length);
    if (schema.$defs[definition] === undefined)
      throw new Error(`Golden-wire fixture ${fixture.path} names unknown definition ${definition}`);
    const validate = ajv.compile({ $ref: `${schemaIdentifier}${fixture.schemaRef}` });
    const value = JSON.parse(text);
    if (!validate(value)) {
      throw new Error(
        `Golden-wire fixture ${fixture.path} failed ${fixture.schemaRef}: `
          + `${ajv.errorsText(validate.errors, { separator: '; ' })}`,
      );
    }
    validated.push(Object.freeze({ name: fixture.name, schemaRef: fixture.schemaRef }));
  }

  const actualPaths = collectRegularFiles(goldenRoot)
    .filter((path) => path !== 'manifest.json');
  if (actualPaths.length !== manifestedPaths.size
      || actualPaths.some((path) => !manifestedPaths.has(path))) {
    throw new Error('Golden-wire tree contains missing or unmanifested files');
  }
  const after = suiteInputHashes(suiteInputs);
  if (JSON.stringify(after) !== JSON.stringify(before))
    throw new Error('Official suite inputs changed while final-tag validation ran');
  return Object.freeze({ validated, ajvVersion, ajvFormatsVersion });
}

function verifyManifestIdentity(manifest, specification) {
  const expectedKeys = ['fixtures', 'formatVersion', 'schemaSha256', 'specificationCommit'];
  if (JSON.stringify(Object.keys(manifest).sort(bytewiseCompare))
      !== JSON.stringify(expectedKeys.sort(bytewiseCompare))
      || manifest.formatVersion !== 1
      || manifest.specificationCommit !== specification.commit
      || manifest.schemaSha256 !== specification.schema.sha256
      || !Array.isArray(manifest.fixtures) || manifest.fixtures.length === 0) {
    throw new Error('Golden-wire manifest identity or shape is invalid');
  }
}

function verifyFixtureRecord(fixture) {
  const expectedKeys = [
    'name', 'owningTest', 'path', 'phase', 'schemaRef', 'sha256', 'source', 'transportForm',
  ].sort(bytewiseCompare);
  if (fixture === null || typeof fixture !== 'object' || Array.isArray(fixture)
      || JSON.stringify(Object.keys(fixture).sort(bytewiseCompare)) !== JSON.stringify(expectedKeys)
      || typeof fixture.name !== 'string' || fixture.name.length === 0
      || !Number.isInteger(fixture.phase) || fixture.phase < 3 || fixture.phase > 7
      || !['production', 'schema-canary'].includes(fixture.source)
      || typeof fixture.owningTest !== 'string' || fixture.owningTest.length === 0
      || typeof fixture.transportForm !== 'string' || fixture.transportForm.length === 0
      || !/^[0-9a-f]{64}$/.test(fixture.sha256)
      || !/^#\/\$defs\/[A-Za-z][A-Za-z0-9]*$/.test(fixture.schemaRef)) {
    throw new Error(`Invalid golden-wire manifest row ${JSON.stringify(fixture?.name)}`);
  }
}

function assertArtifact(path, bytes, expectedBytes, expectedSha256, description) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`${description} must be a regular non-symbolic-link file`);
  if (bytes.length !== expectedBytes || sha256(bytes) !== expectedSha256)
    throw new Error(`${description} does not match the reviewed checksum and byte count`);
}

function containedRegularFile(root, relativePath) {
  if (typeof relativePath !== 'string' || !/^[A-Za-z0-9._/-]+$/.test(relativePath)
      || relativePath.startsWith('/') || relativePath.startsWith('../')
      || relativePath.includes('/../') || relativePath.includes('//'))
    throw new Error(`Unsafe golden-wire path ${relativePath}`);
  const path = resolve(root, relativePath);
  if (!path.startsWith(`${root}${sep}`))
    throw new Error(`Golden-wire path escapes root: ${relativePath}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`Golden-wire path is not a regular file: ${relativePath}`);
  return path;
}

function collectRegularFiles(root) {
  const result = [];
  visit(root);
  return result.sort(bytewiseCompare);

  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink())
        throw new Error(`Golden-wire tree may not contain symbolic links: ${path}`);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile())
        result.push(relative(root, path).split(sep).join('/'));
      else throw new Error(`Unsupported golden-wire entry: ${path}`);
    }
  }
}

function suiteInputHashes(inputs) {
  const sourceTree = sourceTreeIdentity(inputs.sourceRoot, ['.git', 'dist', 'node_modules']);
  return Object.freeze({
    packageJson: sha256(readFileSync(inputs.packagePath)),
    packageLock: sha256(readFileSync(inputs.lockPath)),
    suiteSchema: sha256(readFileSync(inputs.suiteSchemaPath)),
    sourceTree,
  });
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left), Buffer.from(right));
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--suite-dir') {
    console.error(
      'Usage: node conformance/official/validate-final-tag-wire.mjs '
        + '--suite-dir <built-suite>',
    );
    process.exit(64);
  }
  const result = validateFinalTagWire({ suiteDirectory: resolve(args[1]) });
  console.log(
    `Validated ${result.validated.length} final-tag golden messages `
      + `with Ajv ${result.ajvVersion}.`,
  );
}
