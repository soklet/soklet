#!/usr/bin/env node

import {
  ReleaseHarnessEvidenceImportError,
  verifyReleaseHarnessEvidenceDirectory,
} from './import-release-harness-evidence.mjs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const candidateRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

if (process.argv.length !== 2) {
  console.error('Usage: node scripts/verify-release-scans.mjs');
  process.exitCode = 64;
} else {
  try {
    const result = verifyReleaseHarnessEvidenceDirectory({
      candidateRoot,
      gate: 'release-scans',
    });
    console.log(
      `release scans verification PASS candidateCommit=${result.candidate.candidateCommit}`,
    );
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
