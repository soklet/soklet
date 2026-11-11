#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
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

function git(directory, args) {
  const result = spawnSync('git', ['-C', directory, ...args], {
    encoding: 'utf8',
    timeout: 10_000,
  });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
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

  // Reuse an existing commit in a disposable clone. This fixture never creates
  // a commit, and the checkout proves identity independently of SARIF metadata.
  const sourceRoot = resolve(scriptDirectory, '..');
  const checkout = join(root, 'candidate-checkout');
  git(root, ['clone', '--quiet', '--shared', '--no-checkout', sourceRoot, checkout]);
  const checkoutCommit = git(checkout, ['rev-parse', 'HEAD']);
  git(checkout, ['checkout', '--quiet', '--detach', checkoutCommit]);
  const repositoryUri = 'https://github.com/example/soklet';
  git(checkout, ['remote', 'set-url', 'origin', repositoryUri]);
  const environment = {
    GITHUB_ACTIONS: 'true',
    GITHUB_REPOSITORY: 'example/soklet',
    GITHUB_RUN_ATTEMPT: '1',
    GITHUB_RUN_ID: '123',
    GITHUB_SERVER_URL: 'https://github.com',
    GITHUB_SHA: checkoutCommit,
    GITHUB_WORKFLOW_REF: 'example/soklet/.github/workflows/release-validation.yml@refs/heads/release',
    GITHUB_WORKFLOW_SHA: checkoutCommit,
    GITHUB_WORKSPACE: checkout,
  };
  const missingProvenance = sarif({ findings: [retainedFinding] });
  delete missingProvenance.runs[0].versionControlProvenance;
  missingProvenance.runs[0].properties = { existingScannerProperty: true };
  const missingProvenanceInput = fixture('missing-provenance', missingProvenance);
  const supplementedOutput = join(root, 'supplemented-output.sarif');
  prepareCodeqlReleaseReport({
    candidateCommit: checkoutCommit,
    candidateRoot: checkout,
    environment,
    inputRoot: missingProvenanceInput,
    outputPath: supplementedOutput,
  });
  const supplemented = JSON.parse(readFileSync(supplementedOutput, 'utf8'));
  const originalBytes = readFileSync(join(missingProvenanceInput, 'java.sarif'));
  assert.deepEqual(readFileSync(`${supplementedOutput}.source`), originalBytes);
  assert.deepEqual(supplemented.runs[0].versionControlProvenance,
    [{ repositoryUri, revisionId: checkoutCommit }]);
  const normalization = supplemented.runs[0].properties.sokletReleaseProvenance;
  assert.deepEqual(normalization, {
    repositoryUri,
    revisionId: checkoutCommit,
    source: 'verified-github-actions-checkout',
    sourceSarifFile: 'supplemented-output.sarif.source',
    sourceSarifSha256: createHash('sha256').update(originalBytes).digest('hex'),
    workflowRef: environment.GITHUB_WORKFLOW_REF,
    workflowRunAttempt: '1',
    workflowRunId: '123',
    workflowSha: checkoutCommit,
  });
  delete supplemented.runs[0].versionControlProvenance;
  delete supplemented.runs[0].properties.sokletReleaseProvenance;
  assert.deepEqual(supplemented, missingProvenance);
  assertions += 4;

  assert.throws(() => prepareCodeqlReleaseReport({
    candidateCommit: checkoutCommit,
    inputRoot: missingProvenanceInput,
    outputPath: join(root, 'unbound-output.sarif'),
  }), /no version-control provenance/);
  assertions++;

  for (const [field, value] of [
    ['GITHUB_ACTIONS', 'false'],
    ['GITHUB_SHA', 'b'.repeat(40)],
    ['GITHUB_WORKFLOW_SHA', 'b'.repeat(40)],
    ['GITHUB_WORKSPACE', root],
    ['GITHUB_REPOSITORY', 'elsewhere/soklet'],
    ['GITHUB_SERVER_URL', 'https://example.com'],
    ['GITHUB_RUN_ID', ''],
    ['GITHUB_RUN_ATTEMPT', '0'],
    ['GITHUB_WORKFLOW_REF', 'example/soklet/scripts/untrusted.mjs'],
  ]) {
    assert.throws(() => prepareCodeqlReleaseReport({
      candidateCommit: checkoutCommit,
      candidateRoot: checkout,
      environment: { ...environment, [field]: value },
      inputRoot: missingProvenanceInput,
      outputPath: join(root, `wrong-${field}.sarif`),
    }), /matching GitHub Actions candidate\/workflow identity/, field);
    assertions++;
  }

  const emptyProvenance = sarif();
  emptyProvenance.runs[0].versionControlProvenance = [];
  prepareCodeqlReleaseReport({
    candidateCommit: checkoutCommit,
    candidateRoot: checkout,
    environment,
    inputRoot: fixture('empty-provenance', emptyProvenance),
    outputPath: join(root, 'empty-provenance-output.sarif'),
  });
  assertions++;

  for (const [name, provenance] of [
    ['null-provenance', null],
    ['object-provenance', {}],
    ['conflicting-revision', [{ repositoryUri, revisionId: 'b'.repeat(40) }]],
    ['conflicting-repository', [{ repositoryUri: 'https://github.com/elsewhere/soklet', revisionId: checkoutCommit }]],
    ['conflicting-extra-entry', [
      { repositoryUri, revisionId: checkoutCommit },
      { repositoryUri, revisionId: 'b'.repeat(40) },
    ]],
  ]) {
    const report = sarif();
    report.runs[0].versionControlProvenance = provenance;
    const outputPath = join(root, `${name}-output.sarif`);
    assert.throws(() => prepareCodeqlReleaseReport({
      candidateCommit: checkoutCommit,
      candidateRoot: checkout,
      environment,
      inputRoot: fixture(name, report),
      outputPath,
    }), /malformed version-control provenance|does not bind the exact candidate/);
    assert.equal(existsSync(outputPath), false);
    assert.equal(existsSync(`${outputPath}.source`), false);
    assertions += 3;
  }

  const dirtyPath = join(checkout, 'LICENSE');
  const cleanBytes = readFileSync(dirtyPath);
  writeFileSync(dirtyPath, 'changed source\n');
  assert.throws(() => prepareCodeqlReleaseReport({
    candidateCommit: checkoutCommit,
    candidateRoot: checkout,
    environment,
    inputRoot: missingProvenanceInput,
    outputPath: join(root, 'dirty-output.sarif'),
  }), /clean candidate commit\/repository/);
  writeFileSync(dirtyPath, cleanBytes);
  assertions++;

  git(checkout, ['remote', 'set-url', 'origin', 'https://github.com/elsewhere/soklet']);
  assert.throws(() => prepareCodeqlReleaseReport({
    candidateCommit: checkoutCommit,
    candidateRoot: checkout,
    environment,
    inputRoot: missingProvenanceInput,
    outputPath: join(root, 'wrong-origin-output.sarif'),
  }), /clean candidate commit\/repository/);
  git(checkout, ['remote', 'set-url', 'origin', repositoryUri]);
  assertions++;

  assert.throws(() => prepareCodeqlReleaseReport({
    candidateCommit,
    candidateRoot: checkout,
    environment: { ...environment, GITHUB_SHA: candidateCommit, GITHUB_WORKFLOW_SHA: candidateCommit },
    inputRoot: missingProvenanceInput,
    outputPath: join(root, 'wrong-checkout-output.sarif'),
  }), /clean candidate commit\/repository/);
  assertions++;

  const cliOutput = join(root, 'cli-output.sarif');
  const cli = spawnSync(process.execPath, [
    scriptPath, missingProvenanceInput, cliOutput, checkoutCommit, checkout,
  ], {
    encoding: 'utf8',
    env: { ...process.env, ...environment },
    timeout: 10_000,
  });
  assert.equal(cli.status, 0, cli.stderr);
  assert.deepEqual(
    JSON.parse(readFileSync(cliOutput, 'utf8')).runs[0].versionControlProvenance,
    [{ repositoryUri, revisionId: checkoutCommit }],
  );
  assert.deepEqual(readFileSync(`${cliOutput}.source`), originalBytes);
  assertions += 3;

  const usage = spawnSync(process.execPath, [scriptPath], { encoding: 'utf8' });
  assert.equal(usage.status, 64);
  assert.match(usage.stderr, /Usage: node scripts\/prepare-codeql-release-report\.mjs/);
  assertions += 2;

  console.log(`CodeQL release-report preparer self-test passed (${assertions} assertions).`);
} finally {
  rmSync(root, { recursive: true });
}
