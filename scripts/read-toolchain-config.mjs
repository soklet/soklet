#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Toolchain installation depends on tool versions and archive checksums, not
// historical release gates or fingerprints of the current source files.
export function readToolchainValue(manifestPath, path) {
  if (!/^toolchains\.[A-Za-z][A-Za-z0-9]*\.[A-Za-z][A-Za-z0-9]*$/u.test(path))
    throw new Error(`Expected a toolchains.<tool>.<field> path: ${path}`);
  let value = JSON.parse(readFileSync(manifestPath, 'utf8'));
  for (const component of path.split('.')) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)
        || !Object.hasOwn(value, component))
      throw new Error(`Unknown toolchain value: ${path}`);
    value = value[component];
  }
  if (typeof value !== 'string' || value.trim() === '' || /[\r\n]/u.test(value))
    throw new Error(`Toolchain value must be a nonempty single-line string: ${path}`);
  return value;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [manifestPath, path, ...extra] = process.argv.slice(2);
    if (!manifestPath || !path || extra.length)
      throw new Error('Usage: node scripts/read-toolchain-config.mjs <manifest> <toolchains.tool.field>');
    console.log(readToolchainValue(manifestPath, path));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
