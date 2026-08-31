#!/usr/bin/env node

import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  createReleaseHarnessBundle,
  ReleaseHarnessEvidenceImportError,
} from './import-release-harness-evidence.mjs';

function usage() {
  return 'Usage: node scripts/create-release-harness-bundle.mjs '
    + '--gate <id> --candidate-root <absolute-path> '
    + '--evidence-root <absolute-path> --output <absolute-path>';
}

function parseArguments(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];
    if (!['--gate', '--candidate-root', '--evidence-root', '--output'].includes(flag)
        || value === undefined || values.has(flag)) {
      throw new ReleaseHarnessEvidenceImportError(usage());
    }
    values.set(flag, value);
  }
  if (values.size !== 4)
    throw new ReleaseHarnessEvidenceImportError(usage());
  return {
    candidateRoot: values.get('--candidate-root'),
    evidenceRoot: values.get('--evidence-root'),
    gate: values.get('--gate'),
    outputPath: values.get('--output'),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  try {
    const options = parseArguments(process.argv.slice(2));
    const bundle = createReleaseHarnessBundle(options);
    console.log(`release harness bundle PASS gate=${bundle.content.gate} contentSha256=${bundle.contentSha256}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
