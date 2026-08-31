#!/usr/bin/env node

import {
  ReleaseHarnessEvidenceImportError,
  verifyReleaseHarnessEvidenceDirectory,
} from './import-release-harness-evidence.mjs';

const GATE_BY_MODE = new Map([
  ['fuzz-nightly', 'fuzz-nightly-history'],
  ['operational', 'operational-history'],
  ['soak-nightly', 'soak-nightly-history'],
]);

const args = process.argv.slice(2);
const gate = args.length === 1 ? GATE_BY_MODE.get(args[0]) : undefined;
if (gate === undefined) {
  console.error(
    'Usage: node scripts/verify-release-history.mjs '
      + '<fuzz-nightly|soak-nightly|operational>',
  );
  process.exitCode = 64;
} else {
  try {
    const result = verifyReleaseHarnessEvidenceDirectory({ gate });
    console.log(
      `release history verification PASS gate=${result.gate} `
        + `candidateCommit=${result.candidate.candidateCommit}`,
    );
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
