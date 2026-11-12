#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdtempSync, readFileSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifySokletCoreJar } from './verify-surefire-reports.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function verifyMcpInteroperability(candidateJar, client = 'all', run = spawnSync) {
  if (!['all', 'typescript', 'go'].includes(client))
    throw new Error('Client must be all, typescript, or go');
  const selectedJar = realpathSync(resolve(candidateJar));
  const hash = createHash('sha256').update(readFileSync(selectedJar)).digest('hex');
  verifySokletCoreJar(selectedJar, hash);
  const scratch = mkdtempSync(join(tmpdir(), 'soklet-sdk-checks-'));
  try {
    const stagedJar = join(scratch, 'selected-soklet.jar');
    copyFileSync(selectedJar, stagedJar);
    for (const name of client === 'all' ? ['typescript', 'go'] : [client]) {
      const hook = join(projectRoot, 'verification/interoperability', name, 'verify.sh');
      // Each hook bounds and cleans its own managed child processes. Killing only
      // the outer shell on a second timeout could leave those process groups alive.
      const result = run('sh', [hook, stagedJar], { stdio: 'inherit' });
      if (result.error || result.status !== 0)
        throw new Error(`${name} interoperability failed (${result.error?.message ?? result.status ?? result.signal})`);
      verifySokletCoreJar(stagedJar, hash);
    }
  } finally { rmSync(scratch, { recursive: true, force: true }); }
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length < 1 || args.length > 2)
      throw new Error('Usage: node scripts/verify-mcp-interoperability.mjs <built-jar> [all|typescript|go]');
    verifyMcpInteroperability(args[0], args[1]);
  } catch (error) { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; }
}
