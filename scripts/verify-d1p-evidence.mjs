#!/usr/bin/env node

import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyEvidence } from './d1p-evidence-lib.mjs';

function usage(message) {
  if (message)
    console.error(message);
  console.error('Usage: node scripts/verify-d1p-evidence.mjs --mode <candidate|workspace> --scope <preparation|tracked|full> [--core-root <absolute-path>] [--external-root <absolute-path>]');
  process.exit(message ? 64 : 0);
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
let coreRoot = resolve(scriptDirectory, '..');
let externalRoot;
let mode;
let scope;
for (let index = 2; index < process.argv.length; ++index) {
  const argument = process.argv[index];
  const value = process.argv[index + 1];
  if (argument === '--help')
    usage();
  if (['--core-root', '--external-root', '--mode', '--scope'].includes(argument)) {
    if (value === undefined || value.startsWith('--'))
      usage(`Missing value for ${argument}`);
    ++index;
    if (argument === '--core-root')
      coreRoot = value;
    else if (argument === '--external-root')
      externalRoot = value;
    else if (argument === '--mode')
      mode = value;
    else
      scope = value;
    continue;
  }
  usage(`Unknown argument: ${argument}`);
}

if (mode === undefined)
  usage('Missing --mode');
if (scope === undefined)
  usage('Missing --scope');

try {
  verifyEvidence({ coreRoot, externalRoot, mode, scope });
  if (mode === 'candidate' && scope === 'tracked') {
    console.log('Verified tracked D1p binding and compiler-derived semantics in sibling-blind candidate mode; retained preview and external bytes require workspace/full verification');
  } else if (scope === 'preparation') {
    console.log('Verified D1p evidence configuration and derived core inputs in memory');
  } else {
    console.log('Verified complete D1p core, retained preview, and sibling evidence');
  }
} catch (error) {
  console.error(`D1p evidence verification failed: ${error.message}`);
  process.exit(1);
}
