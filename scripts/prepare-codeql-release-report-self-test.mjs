#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  CodeqlReleaseReportError,
  prepareCodeqlReleaseReport,
} from './prepare-codeql-release-report.mjs';

const scriptDirectory = fileURLToPath(new URL('.', import.meta.url));
const scriptPath = resolve(scriptDirectory, 'prepare-codeql-release-report.mjs');
const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-codeql-release-report-')));
const candidateCommit = 'a'.repeat(40);
let assertions = 0;

function sarif({
  findings = [],
  invocations = [{
    executionSuccessful: true,
    exitCode: 0,
    toolConfigurationNotifications: [],
    toolExecutionNotifications: [],
  }],
  revisionId = candidateCommit,
  tool = 'CodeQL',
} = {}) {
  return {
    runs: [{
      invocations,
      results: findings,
      tool: { driver: { name: tool } },
      versionControlProvenance: [{
        repositoryUri: 'https://github.com/example/soklet',
        revisionId,
      }],
    }],
    version: '2.1.0',
  };
}

function fixture(name, value = sarif()) {
  const directory = join(root, name);
  mkdirSync(directory);
  writeFileSync(join(directory, 'java.sarif'), JSON.stringify(value), 'utf8');
  return directory;
}

try {
  const validInput = fixture('valid');
  const validOutput = join(root, 'out', '00-codeql-java.sarif');
  assert.deepEqual(
    prepareCodeqlReleaseReport({ candidateCommit, inputRoot: validInput, outputPath: validOutput }),
    { outputPath: validOutput, runCount: 1 },
  );
  assertions++;

  const retainedFinding = {
    locations: [{
      physicalLocation: {
        artifactLocation: { uri: 'src/main/java/com/soklet/Example.java', uriBaseId: '%SRCROOT%' },
        region: { endColumn: 18, endLine: 7, startColumn: 5, startLine: 7 },
      },
    }],
    ruleId: 'java/example-security-rule',
  };
  const findingInput = fixture('finding', sarif({ findings: [retainedFinding] }));
  const findingOutput = join(root, 'finding-output.sarif');
  assert.deepEqual(
    prepareCodeqlReleaseReport({
      candidateCommit,
      inputRoot: findingInput,
      outputPath: findingOutput,
    }),
    { outputPath: findingOutput, runCount: 1 },
  );
  assert.deepEqual(
    JSON.parse(readFileSync(findingOutput, 'utf8')).runs[0].results,
    [retainedFinding],
  );
  assertions += 2;
  assert.equal(
    readFileSync(validOutput, 'utf8'),
    `{\n  "runs": [\n    {\n      "invocations": [\n        {\n          "executionSuccessful": true,\n          "exitCode": 0,\n          "toolConfigurationNotifications": [],\n          "toolExecutionNotifications": []\n        }\n      ],\n      "results": [],\n      "tool": {\n        "driver": {\n          "name": "CodeQL"\n        }\n      },\n      "versionControlProvenance": [\n        {\n          "repositoryUri": "https://github.com/example/soklet",\n          "revisionId": "${candidateCommit}"\n        }\n      ]\n    }\n  ],\n  "version": "2.1.0"\n}\n`,
  );
  assertions++;

  assert.throws(
    () => prepareCodeqlReleaseReport({ candidateCommit, inputRoot: validInput, outputPath: validOutput }),
    CodeqlReleaseReportError,
  );
  assertions++;

  const missingInvocationSarif = sarif();
  delete missingInvocationSarif.runs[0].invocations;
  for (const [name, value, pattern] of [
    ['wrong-tool', sarif({ tool: 'not-codeql' }), /not from the CodeQL scanner/],
    ['missing-runs', { runs: [], version: '2.1.0' }, /at least one scanner run/],
    ['wrong-version', { runs: sarif().runs, version: '2.0.0' }, /SARIF 2\.1\.0/],
    ['missing-invocations', missingInvocationSarif, /no scanner invocation evidence/],
    [
      'unsuccessful-invocation',
      sarif({ invocations: [{ executionSuccessful: false, exitCode: 1 }] }),
      /does not prove successful scanner execution/,
    ],
    [
      'nonzero-invocation-exit',
      sarif({ invocations: [{ executionSuccessful: true, exitCode: 2 }] }),
      /nonzero or malformed exit code/,
    ],
    [
      'execution-error-notification',
      sarif({ invocations: [{
        executionSuccessful: true,
        exitCode: 0,
        toolExecutionNotifications: [{ level: 'error', message: { text: 'analysis failed' } }],
      }] }),
      /incomplete scanner execution/,
    ],
    [
      'configuration-exception-notification',
      sarif({ invocations: [{
        executionSuccessful: true,
        exitCode: 0,
        toolConfigurationNotifications: [{ exception: { message: 'configuration failed' } }],
      }] }),
      /incomplete scanner execution/,
    ],
  ]) {
    assert.throws(
      () => prepareCodeqlReleaseReport({
        inputRoot: fixture(name, value),
        candidateCommit,
        outputPath: join(root, `${name}.sarif`),
      }),
      pattern,
      name,
    );
    assertions++;
  }

  const multiple = fixture('multiple');
  writeFileSync(join(multiple, 'second.sarif'), JSON.stringify(sarif()), 'utf8');
  assert.throws(
    () => prepareCodeqlReleaseReport({
      inputRoot: multiple,
      candidateCommit,
      outputPath: join(root, 'multiple-output.sarif'),
    }),
    /exactly one SARIF report/,
  );
  assertions++;

  const unexpected = fixture('unexpected');
  writeFileSync(join(unexpected, 'notes.txt'), 'not evidence\n', 'utf8');
  assert.throws(
    () => prepareCodeqlReleaseReport({
      inputRoot: unexpected,
      candidateCommit,
      outputPath: join(root, 'unexpected-output.sarif'),
    }),
    /unexpected file/,
  );
  assertions++;

  const symlinked = fixture('symlinked');
  symlinkSync(join(symlinked, 'java.sarif'), join(symlinked, 'alias.sarif'));
  assert.throws(
    () => prepareCodeqlReleaseReport({
      inputRoot: symlinked,
      candidateCommit,
      outputPath: join(root, 'symlinked-output.sarif'),
    }),
    /symbolic link/,
  );
  assertions++;

  assert.throws(
    () => prepareCodeqlReleaseReport({
      inputRoot: 'relative',
      candidateCommit,
      outputPath: join(root, 'relative-output.sarif'),
    }),
    /absolute path/,
  );
  assertions++;

  assert.throws(
    () => prepareCodeqlReleaseReport({
      candidateCommit,
      inputRoot: fixture('wrong-revision', sarif({ revisionId: 'b'.repeat(40) })),
      outputPath: join(root, 'wrong-revision-output.sarif'),
    }),
    /does not bind the exact candidate commit/,
  );
  assertions++;

  const usage = spawnSync(process.execPath, [scriptPath], { encoding: 'utf8' });
  assert.equal(usage.status, 64);
  assert.match(usage.stderr, /Usage: node scripts\/prepare-codeql-release-report\.mjs/);
  assertions += 2;

  console.log(`CodeQL release-report preparer self-test passed (${assertions} assertions).`);
} finally {
  rmSync(root, { recursive: true });
}
