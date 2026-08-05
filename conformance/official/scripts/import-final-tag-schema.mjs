#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
} from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const expected = Object.freeze({
  schema: Object.freeze({
    bytes: 181474,
    sha256: 'ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203',
  }),
  license: Object.freeze({
    bytes: 12227,
    sha256: '0382b0057770ca05e9c350a50aa3b1c1fea84da0bc81d723bf00b9aa841be58a',
  }),
});

if (process.argv.length !== 4) {
  console.error(
    'Usage: node conformance/official/scripts/import-final-tag-schema.mjs '
      + '<schema.json> <LICENSE>',
  );
  process.exit(64);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const destination = resolve(scriptDirectory, '..', 'final-schema');
const inputs = [
  ['schema', resolve(process.argv[2]), resolve(destination, 'schema.json')],
  ['license', resolve(process.argv[3]), resolve(destination, 'LICENSE.upstream')],
];

for (const [kind, source, target] of inputs) {
  const stats = lstatSync(source);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`${kind} source must be a regular non-symbolic-link file`);
  const bytes = readFileSync(source);
  if (bytes.length !== expected[kind].bytes || sha256(bytes) !== expected[kind].sha256)
    throw new Error(`${kind} source does not match the reviewed final-tag artifact`);
  if (existsSync(target))
    throw new Error(`Refusing to overwrite existing ${basename(target)}`);
}

mkdirSync(destination, { recursive: true });
for (const [, source, target] of inputs) copyFileSync(source, target);

console.log('Imported checksum-verified MCP 2026-07-28 schema and upstream license.');

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
