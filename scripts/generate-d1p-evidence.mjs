#!/usr/bin/env node

import { resolve } from 'node:path';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EXTERNAL_PATH,
  PREVIEW_PATH,
  ROOT_PATH,
  SEMANTIC_PATH,
  TRACKED_BLOB_PATH,
  generateEvidence,
  sha256,
} from './d1p-evidence-lib.mjs';

function usage(message) {
  if (message)
    console.error(message);
  console.error('Usage: node scripts/generate-d1p-evidence.mjs --mode workspace --external-root <absolute-path> [--core-root <absolute-path>]');
  process.exit(message ? 64 : 0);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
let coreRoot = resolve(scriptDirectory, '..');
let externalRoot;
let mode;
for (let index = 2; index < process.argv.length; ++index) {
  const argument = process.argv[index];
  const value = process.argv[index + 1];
  if (argument === '--help')
    usage();
  if (argument === '--core-root' || argument === '--external-root' || argument === '--mode') {
    if (value === undefined || value.startsWith('--'))
      usage(`Missing value for ${argument}`);
    ++index;
    if (argument === '--core-root')
      coreRoot = value;
    else if (argument === '--external-root')
      externalRoot = value;
    else
      mode = value;
    continue;
  }
  usage(`Unknown argument: ${argument}`);
}

if (mode !== 'workspace')
  usage('Evidence generation requires --mode workspace');
if (externalRoot === undefined)
  usage('Evidence generation requires --external-root');

try {
  const result = generateEvidence({ coreRoot, externalRoot });
  console.log(`Generated ${EXTERNAL_PATH} (${sha256(result.externalBytes)})`);
  console.log(`Generated ${TRACKED_BLOB_PATH} (${sha256(result.trackedBlobBytes)})`);
  console.log(`Generated ${SEMANTIC_PATH} (${sha256(result.semanticBytes)})`);
  console.log(`Generated ${PREVIEW_PATH} (${sha256(result.previewBytes)})`);
  console.log(`Generated ${ROOT_PATH} (${sha256(result.rootBytes)})`);
} catch (error) {
  console.error(`D1p evidence generation failed: ${error.message}`);
  process.exit(1);
}
