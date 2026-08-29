#!/usr/bin/env node

import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateApprovedPreviewSeal } from './d1p-evidence-lib.mjs';

function usage(message) {
  if (message)
    console.error(message);
  console.error(
    'Usage: node scripts/generate-d1p-approved-preview.mjs '
      + '--g3-approval-receipt <absolute-path> [--core-root <absolute-path>]',
  );
  process.exit(message ? 64 : 0);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
let coreRoot = resolve(scriptDirectory, '..');
let g3ApprovalReceiptPath;
for (let index = 2; index < process.argv.length; ++index) {
  const argument = process.argv[index];
  const value = process.argv[index + 1];
  if (argument === '--help')
    usage();
  if (['--core-root', '--g3-approval-receipt'].includes(argument)) {
    if (value === undefined || value.startsWith('--'))
      usage(`Missing value for ${argument}`);
    ++index;
    if (argument === '--core-root')
      coreRoot = value;
    else
      g3ApprovalReceiptPath = value;
    continue;
  }
  usage(`Unknown argument: ${argument}`);
}

if (g3ApprovalReceiptPath === undefined)
  usage('Missing --g3-approval-receipt');
if (!isAbsolute(coreRoot))
  usage('--core-root must be absolute');
if (!isAbsolute(g3ApprovalReceiptPath))
  usage('--g3-approval-receipt must be absolute');

try {
  const result = generateApprovedPreviewSeal({ coreRoot, g3ApprovalReceiptPath });
  console.log(`Generated immutable approved-preview seal at ${result.path}`);
  console.log('Commit that file as the sole change in the first ordinary commit after D2; this tool never stages or commits.');
} catch (error) {
  console.error(`D1p approved-preview seal generation failed: ${error.message}`);
  process.exit(1);
}
