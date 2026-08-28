#!/usr/bin/env node

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  apiSignatureJsonlFromXml,
  incompatibilityJsonlFromXml,
} from './api-diff/japicmp-symbols.mjs';
import {
  CONFIG_PATH,
  EXTERNAL_PATH,
  PREVIEW_PATH,
  ROOT_PATH,
  SEMANTIC_PATH,
  TRACKED_BLOB_PATH,
  assertProductionConfig,
  canonicalJson,
  generateEvidence,
  productionConfigForSelfTest,
  readConfig,
  sha256,
  validateExternalManifest,
  validatePreviewEvidence,
  validateRootManifest,
  validateSemanticManifest,
  validateTrackedBlobManifest,
  verifyEvidence,
} from './d1p-evidence-lib.mjs';

const temporaryRoot = mkdtempSync(resolve(tmpdir(), 'soklet-d1p-evidence-self-test-'));
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const goldenCore = resolve(temporaryRoot, 'golden-core');
const goldenExternal = resolve(temporaryRoot, 'golden-external');
let passedCases = 0;

function write(root, path, value) {
  const absolute = resolve(root, path);
  mkdirSync(dirname(absolute), { recursive: true });
  writeFileSync(absolute, value);
}

function git(root, ...args) {
  return execFileSync('git', ['-C', root, ...args], {
    encoding: 'utf8',
    env: {
      ...process.env,
      GIT_AUTHOR_EMAIL: 'd1p-self-test@example.invalid',
      GIT_AUTHOR_NAME: 'D1p self-test',
      GIT_COMMITTER_EMAIL: 'd1p-self-test@example.invalid',
      GIT_COMMITTER_NAME: 'D1p self-test',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function createGoldenFixture() {
  mkdirSync(goldenCore, { recursive: true });
  mkdirSync(goldenExternal, { recursive: true });
  git(goldenCore, 'init', '--quiet');
  write(goldenCore, '.gitignore', '/target/\n');
  write(goldenCore, 'base.txt', 'accepted D1\n');
  git(goldenCore, 'add', '.gitignore', 'base.txt');
  git(goldenCore, 'commit', '--quiet', '-m', 'fixture base');
  const baseCoreCommit = git(goldenCore, 'rev-parse', 'HEAD');
  const baseCoreTree = git(goldenCore, 'rev-parse', 'HEAD^{tree}');

  const config = productionConfigForSelfTest();
  config.baseCoreCommit = baseCoreCommit;
  config.baseCoreTree = baseCoreTree;
  write(goldenCore, 'README.md', 'candidate preview\n');

  const freezeXml = readFileSync(
    resolve(projectRoot, 'scripts/api-diff/fixtures/added-api.xml'),
    'utf8',
  ).replace('oldJar="baseline.jar"', 'oldJar="soklet-3.5.1.jar"')
    .replace('oldVersion="old"', 'oldVersion="3.5.1"');
  const diffXml = freezeXml.replace('onlyModifications="false"', 'onlyModifications="true"');
  const phaseIncludes = new Map([
    [4, 'com.soklet.McpFixture\ncom.soklet.RestoredHost\n'],
    [5, 'com.soklet.SharedHost\n'],
    [6, 'com.soklet.annotation.McpMarker\n'],
  ]);
  const signatureCounts = {};
  for (const phase of [4, 5, 6]) {
    const includes = phaseIncludes.get(phase);
    const signatures = apiSignatureJsonlFromXml(freezeXml, includes);
    signatureCounts[`phase-${phase}`] = signatures.trimEnd().split('\n').length;
    write(goldenCore, `api/mcp/phase-${phase}.includes`, includes);
    write(goldenCore, `api/mcp/phase-${phase}.signatures.jsonl`, signatures);
    write(goldenCore, `target/mcp-api-freezes/phase-${phase}.signatures.jsonl`, signatures);
  }
  write(goldenCore, 'api/mcp/provisional.includes', '');
  write(
    goldenCore,
    'api/mcp/non-mcp-public-api.allowlist',
    'com.soklet.extra.FutureApi\n',
  );
  const incompatibility = incompatibilityJsonlFromXml(diffXml);
  write(goldenCore, 'api/mcp/current-incompatibilities.jsonl', incompatibility);
  write(
    goldenCore,
    'target/japicmp/mcp-api-diff.incompatibilities.jsonl',
    incompatibility,
  );
  write(goldenCore, 'target/japicmp/mcp-api-diff.xml', diffXml);
  write(goldenCore, 'target/japicmp/mcp-api-freeze.xml', freezeXml);
  write(goldenCore, 'target/soklet-4.0.0-SNAPSHOT.jar', Buffer.from([0, 1, 2, 3]));
  config.semanticExpectations = {
    allowlistCount: 1,
    incompatibilityCount: incompatibility.trimEnd().split('\n').length,
    ownerCounts: {
      'phase-4': 2,
      'phase-5': 1,
      'phase-6': 1,
      provisional: 0,
    },
    signatureCounts,
  };
  for (const path of config.protectedPostD2Paths.filter(
    (path) => path.startsWith('conformance/'),
  ))
    write(goldenCore, path, `protected fixture ${path}\n`);
  write(goldenCore, CONFIG_PATH, canonicalJson(config));
  for (const path of [
    '.github/workflows/ci.yml',
    'release/d1p-evidence-contract.md',
    'scripts/d1p-evidence-lib.mjs',
    'scripts/generate-d1p-evidence.mjs',
    'scripts/release-validation-self-test.mjs',
    'scripts/validate-release-candidate.sh',
    'scripts/verify-d1p-evidence-self-test.mjs',
    'scripts/verify-d1p-evidence.mjs',
    'scripts/verify-mcp-api-freezes.sh',
  ])
    write(goldenCore, path, `fixture ${path}\n`);
  write(
    goldenCore,
    config.reflectionDigestSourcePath,
    `private static final String PHASE_FOUR_NULLABILITY_SHA_256 = "${'1'.repeat(64)}";\n`
      + `private static final String PHASE_FIVE_NULLABILITY_SHA_256 = "${'2'.repeat(64)}";\n`
      + `private static final String PHASE_SIX_NULLABILITY_SHA_256 = "${'3'.repeat(64)}";\n`,
  );

  for (const [index, entry] of config.externalEntries.entries()) {
    if (entry.changeKind !== 'deleted')
      write(goldenExternal, entry.path, `external preview ${index}\n`);
  }

  const unsafeOutputCore = resolve(temporaryRoot, 'unsafe-output-core');
  const unsafeOutputExternal = resolve(temporaryRoot, 'unsafe-output-external');
  cpSync(goldenCore, unsafeOutputCore, { recursive: true });
  cpSync(goldenExternal, unsafeOutputExternal, { recursive: true });
  write(
    unsafeOutputExternal,
    EXTERNAL_PATH,
    'preexisting external manifest bytes must remain untouched\n',
  );
  write(unsafeOutputCore, 'release/symlink-victim', 'must not be overwritten\n');
  symlinkSync('symlink-victim', resolve(unsafeOutputCore, TRACKED_BLOB_PATH));
  assert.throws(
    () => generateEvidence({
      coreRoot: unsafeOutputCore,
      externalRoot: unsafeOutputExternal,
      config,
    }),
    /output must be a regular non-symlink file/u,
  );
  assert.equal(
    readFileSync(resolve(unsafeOutputCore, 'release/symlink-victim'), 'utf8'),
    'must not be overwritten\n',
  );
  assert.equal(
    readFileSync(resolve(unsafeOutputExternal, EXTERNAL_PATH), 'utf8'),
    'preexisting external manifest bytes must remain untouched\n',
  );
  ++passedCases;

  generateEvidence({ coreRoot: goldenCore, externalRoot: goldenExternal, config });
  verifyEvidence({
    coreRoot: goldenCore,
    externalRoot: goldenExternal,
    mode: 'workspace',
    scope: 'full',
    config,
  });
  git(goldenCore, 'add', '-A');
  git(goldenCore, 'commit', '--quiet', '-m', 'sole D1p preview');
  verifyEvidence({
    coreRoot: goldenCore,
    externalRoot: goldenExternal,
    mode: 'workspace',
    scope: 'full',
    config,
  });
  return config;
}

const goldenConfig = createGoldenFixture();

function cloneFixture(label) {
  const safe = label.replaceAll(/[^A-Za-z0-9.-]+/gu, '-');
  const core = resolve(temporaryRoot, `case-${passedCases}-${safe}-core`);
  const external = resolve(temporaryRoot, `case-${passedCases}-${safe}-external`);
  cpSync(goldenCore, core, { recursive: true });
  cpSync(goldenExternal, external, { recursive: true });
  return { core, external };
}

function fixtureConfig(core) {
  return readConfig(core, { production: false });
}

function verifyWorkspace(core, external) {
  return verifyEvidence({
    coreRoot: core,
    externalRoot: external,
    mode: 'workspace',
    scope: 'full',
    config: fixtureConfig(core),
  });
}

function verifyCandidate(core) {
  return verifyEvidence({
    coreRoot: core,
    mode: 'candidate',
    scope: 'tracked',
    config: fixtureConfig(core),
  });
}

function expectRejected(label, mutate, pattern = /./u) {
  const { core, external } = cloneFixture(label);
  mutate({ core, external });
  git(core, 'add', '-A');
  git(core, 'commit', '--quiet', '--amend', '--no-edit');
  assert.throws(() => verifyWorkspace(core, external), pattern, label);
  ++passedCases;
}

function mutateJson(root, path, mutator) {
  const absolute = resolve(root, path);
  const value = JSON.parse(readFileSync(absolute, 'utf8'));
  mutator(value);
  writeFileSync(absolute, canonicalJson(value));
}

function readJson(root, path) {
  return JSON.parse(readFileSync(resolve(root, path), 'utf8'));
}

try {
  assertProductionConfig(readConfig(projectRoot));
  assert.equal(productionConfigForSelfTest().externalEntries.length, 16);
  assert.deepEqual(
    productionConfigForSelfTest().externalEntries.map((entry) => entry.path),
    [
      'mcp/MCP_CONFORMANCE_MATRIX.md',
      'mcp/MCP_PUBLIC_API_SKETCH_V9.md',
      'mcp/README.md',
      'mcp/design/mcp-api-sketch/README.md',
      'mcp/design/mcp-api-sketch/src/examples/java/examples/AnnotatedCatalogServerExample.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpAdmissionContext.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpBooleanHint.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpHandlerContinuation.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpHandlerInterceptor.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpLogLevel.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpRequestContext.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/McpToolOutput.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpTool.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpToolArgument.java',
      'mcp/design/mcp-api-sketch/src/main/java/com/soklet/annotation/McpToolProperty.java',
      'mcp/design/mcp-api-sketch/verify.sh',
    ],
  );
  ++passedCases;

  verifyWorkspace(goldenCore, goldenExternal);
  ++passedCases;

  verifyEvidence({
    coreRoot: goldenCore,
    mode: 'candidate',
    scope: 'preparation',
    config: goldenConfig,
  });
  ++passedCases;

  {
    const { core, external } = cloneFixture('candidate sibling blindness');
    rmSync(resolve(external, 'mcp'), { recursive: true, force: true });
    rmSync(resolve(core, PREVIEW_PATH));
    rmSync(resolve(core, 'target/soklet-4.0.0-SNAPSHOT.jar'));
    write(core, 'target/soklet-4.0.0.jar', 'final version candidate\n');
    verifyEvidence({
      coreRoot: core,
      mode: 'candidate',
      scope: 'tracked',
      config: fixtureConfig(core),
    });
    ++passedCases;
  }

  assert.throws(
    () => verifyEvidence({
      coreRoot: goldenCore,
      externalRoot: goldenExternal,
      mode: 'candidate',
      scope: 'tracked',
      config: goldenConfig,
    }),
    /rejects --external-root/u,
  );
  ++passedCases;

  {
    const { core, external } = cloneFixture('generation after preview commit');
    assert.throws(
      () => generateEvidence({
        coreRoot: core,
        externalRoot: external,
        config: fixtureConfig(core),
      }),
      /generation must run before the sole preview commit/u,
    );
    ++passedCases;
  }
  {
    const { core, external } = cloneFixture('two commit descendant');
    write(core, 'second-commit.txt', 'forbidden\n');
    git(core, 'add', 'second-commit.txt');
    git(core, 'commit', '--quiet', '-m', 'serialized post-D2 package');
    verifyCandidate(core);
    assert.throws(
      () => verifyWorkspace(core, external),
      /workspace\/full verification is limited/u,
    );
    passedCases += 2;
  }
  {
    const { core, external } = cloneFixture('merge descendant');
    const config = fixtureConfig(core);
    const previewHead = git(core, 'rev-parse', 'HEAD');
    git(core, 'checkout', '--quiet', '-b', 'other-parent', config.baseCoreCommit);
    write(core, 'other-parent.txt', 'other\n');
    git(core, 'add', 'other-parent.txt');
    git(core, 'commit', '--quiet', '-m', 'other parent');
    git(core, 'checkout', '--quiet', '--detach', previewHead);
    git(core, 'merge', '--quiet', '--no-ff', '--no-edit', 'other-parent');
    assert.throws(
      () => verifyWorkspace(core, external),
      /workspace\/full verification is limited/u,
    );
    ++passedCases;
  }
  {
    const { core } = cloneFixture('descendant protected drift');
    write(
      core,
      goldenConfig.protectedPostD2Paths.find((path) => path.endsWith('McpConformanceFixture.java')),
      'changed protected fixture bytes\n',
    );
    git(core, 'add', '-A');
    git(core, 'commit', '--quiet', '-m', 'forbidden protected drift');
    assert.throws(
      () => verifyCandidate(core),
      /release\/d1p-canonical-semantic-digests\.json does not match deterministic derivation/u,
    );
    ++passedCases;
  }
  {
    const { core } = cloneFixture('descendant manifest drift');
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.externalManifestSha256 = '0'.repeat(64);
    });
    git(core, 'add', ROOT_PATH);
    git(core, 'commit', '--quiet', '-m', 'forbidden manifest drift');
    assert.throws(
      () => verifyCandidate(core),
      /immutable D1p path .* differs from preview P/u,
    );
    ++passedCases;
  }
  {
    const { core, external } = cloneFixture('dirty postcommit rederivation');
    write(core, 'README.md', 'dirty postcommit bytes\n');
    const trackedPath = resolve(core, TRACKED_BLOB_PATH);
    const trackedLines = readFileSync(trackedPath, 'utf8').trimEnd().split('\n');
    const readmeIndex = trackedLines.findIndex((line) => line.endsWith('  README.md'));
    trackedLines[readmeIndex] = `${sha256('dirty postcommit bytes\n')}  README.md`;
    writeFileSync(trackedPath, `${trackedLines.join('\n')}\n`);
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.trackedBlobManifest.sha256 = sha256(readFileSync(trackedPath));
    });
    assert.throws(
      () => verifyWorkspace(core, external),
      /differs from preview P|does not match the final preview commit|rejects staged, unstaged/u,
    );
    ++passedCases;
  }
  {
    const { core, external } = cloneFixture('postcommit untracked core file');
    write(core, 'untracked-after-preview.txt', 'not part of P\n');
    assert.throws(
      () => verifyWorkspace(core, external),
      /rejects staged, unstaged, or nonignored untracked core state/u,
    );
    ++passedCases;
  }
  {
    const { core } = cloneFixture('postcommit deleted manifest');
    rmSync(resolve(core, ROOT_PATH));
    assert.throws(
      () => verifyCandidate(core),
      /Missing immutable D1p path|rejects staged, unstaged/u,
    );
    ++passedCases;
  }
  {
    const { core } = cloneFixture('postcommit staged manifest drift');
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.externalEntrySetSha256 = '0'.repeat(64);
    });
    git(core, 'add', ROOT_PATH);
    assert.throws(
      () => verifyCandidate(core),
      /immutable D1p path .* differs from preview P/u,
    );
    ++passedCases;
  }
  {
    const { core } = cloneFixture('postcommit intent to add');
    write(core, 'intent-to-add.txt', 'unstaged intent bytes\n');
    git(core, 'add', '--intent-to-add', 'intent-to-add.txt');
    assert.throws(
      () => verifyCandidate(core),
      /rejects staged, unstaged, or nonignored untracked core state/u,
    );
    ++passedCases;
  }
  {
    const { core, external } = cloneFixture('postcommit omitted tracked manifests');
    git(core, 'rm', '--quiet', '--cached', TRACKED_BLOB_PATH, SEMANTIC_PATH, ROOT_PATH);
    git(core, 'commit', '--quiet', '--amend', '--no-edit');
    assert.throws(
      () => verifyWorkspace(core, external),
      /must be a regular tracked blob in [0-9a-f]{40}/u,
    );
    ++passedCases;
  }
  {
    const { core, external } = cloneFixture('tracked preview evidence');
    git(core, 'add', '-f', PREVIEW_PATH);
    git(core, 'commit', '--quiet', '--amend', '--no-edit');
    assert.throws(
      () => verifyWorkspace(core, external),
      /must remain untracked/u,
    );
    ++passedCases;
  }
  assert.throws(
    () => verifyEvidence({
      coreRoot: goldenCore,
      mode: 'candidate',
      scope: 'full',
      config: goldenConfig,
    }),
    /full scope requires workspace/u,
  );
  ++passedCases;

  const root = readJson(goldenCore, ROOT_PATH);
  const trackedPaths = readFileSync(resolve(goldenCore, TRACKED_BLOB_PATH), 'utf8')
    .trimEnd().split('\n').map((line) => line.slice(66));
  assert.equal(trackedPaths.includes(TRACKED_BLOB_PATH), false);
  assert.equal(trackedPaths.includes(SEMANTIC_PATH), false);
  assert.equal(trackedPaths.includes(ROOT_PATH), false);
  assert.notEqual(root.canonicalSemanticManifest.sha256, sha256(readFileSync(resolve(goldenCore, ROOT_PATH))));
  ++passedCases;

  expectRejected('noncanonical config keys', ({ core }) => {
    const config = readJson(core, CONFIG_PATH);
    writeFileSync(resolve(core, CONFIG_PATH), `${JSON.stringify(config)}\n`);
  }, /not canonical/u);
  expectRejected('wrong base tree', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => { config.baseCoreTree = '0'.repeat(40); });
  }, /base tree mismatch/u);
  expectRejected('omitted configured external row', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => { config.externalEntries.splice(2, 1); });
  });
  expectRejected('reordered configured external rows', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      [config.externalEntries[0], config.externalEntries[1]] =
        [config.externalEntries[1], config.externalEntries[0]];
    });
  }, /bytewise sorted/u);
  expectRejected('duplicate later owner', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      config.externalEntries[0].allowedPostD2Owner.push('MCP-C');
    });
  }, /unique in approved package order/u);
  expectRejected('unknown external change kind', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      config.externalEntries[0].changeKind = 'renamed';
    });
  }, /changeKind must be exactly/u);
  expectRejected('added external row with base hash', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      const entry = config.externalEntries.find((candidate) => candidate.changeKind === 'added');
      entry.baseSha256 = '0'.repeat(64);
    });
  }, /baseSha256 must be null for an added path/u);
  expectRejected('modified external row without base hash', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      const entry = config.externalEntries.find((candidate) => candidate.changeKind === 'modified');
      entry.baseSha256 = null;
    });
  }, /baseSha256 must be lowercase SHA-256 for modified path/u);

  expectRejected('tracked blob missing row', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    const lines = readFileSync(path, 'utf8').trimEnd().split('\n');
    writeFileSync(path, `${lines.slice(1).join('\n')}\n`);
  });
  expectRejected('tracked blob extra row', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    const lines = readFileSync(path, 'utf8').trimEnd().split('\n');
    lines.push(`${'0'.repeat(64)}  z-extra`);
    writeFileSync(path, `${lines.join('\n')}\n`);
  });
  expectRejected('tracked blob reordered rows', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    const lines = readFileSync(path, 'utf8').trimEnd().split('\n');
    [lines[0], lines[1]] = [lines[1], lines[0]];
    writeFileSync(path, `${lines.join('\n')}\n`);
  }, /bytewise sorted/u);
  expectRejected('tracked blob delimiter drift', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    writeFileSync(path, readFileSync(path, 'utf8').replace('  ', ' '));
  }, /exact SHA-256\/two-space\/path/u);
  expectRejected('tracked blob self hash', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    writeFileSync(path, `${readFileSync(path, 'utf8')}${'0'.repeat(64)}  ${TRACKED_BLOB_PATH}\n`);
  }, /excluded manifest path/u);
  {
    const path = resolve(goldenCore, TRACKED_BLOB_PATH);
    assert.throws(
      () => validateTrackedBlobManifest(
        Buffer.from(`${readFileSync(path, 'utf8')}${'0'.repeat(64)}  ${PREVIEW_PATH}\n`),
        goldenConfig,
      ),
      /must not contain untracked preview path/u,
    );
    ++passedCases;
  }
  expectRejected('tracked blob path escape', ({ core }) => {
    const path = resolve(core, TRACKED_BLOB_PATH);
    writeFileSync(path, `${'0'.repeat(64)}  ../escape\n`);
  }, /parent component/u);
  expectRejected('tracked source byte drift', ({ core }) => {
    write(core, 'README.md', 'changed after evidence\n');
  });
  expectRejected('tracked source symlink', ({ core }) => {
    rmSync(resolve(core, 'README.md'));
    symlinkSync('base.txt', resolve(core, 'README.md'));
  }, /regular non-symlink|symlink component/u);
  expectRejected('compiler input symlink parent', ({ core }) => {
    renameSync(
      resolve(core, 'target/mcp-api-freezes'),
      resolve(core, 'target/real-api-freezes'),
    );
    symlinkSync('real-api-freezes', resolve(core, 'target/mcp-api-freezes'));
  }, /symlink component/u);
  expectRejected('external input symlink parent', ({ external }) => {
    renameSync(resolve(external, 'mcp/design'), resolve(external, 'mcp/real-design'));
    symlinkSync('real-design', resolve(external, 'mcp/design'));
  }, /symlink component/u);

  expectRejected('semantic tuple mutation', ({ core }) => {
    mutateJson(core, SEMANTIC_PATH, (manifest) => {
      manifest.tupleSets[0].tuples[0] = 'changed';
    });
  }, /sha256 does not match/u);
  expectRejected('semantic count drift', ({ core }) => {
    mutateJson(core, SEMANTIC_PATH, (manifest) => { ++manifest.tupleSets[0].count; });
  }, /count does not match/u);
  expectRejected('semantic tuple reorder', ({ core }) => {
    mutateJson(core, SEMANTIC_PATH, (manifest) => {
      const set = manifest.tupleSets.find((candidate) => candidate.name === 'freeze');
      [set.tuples[0], set.tuples[1]] = [set.tuples[1], set.tuples[0]];
    });
  }, /bytewise sorted/u);
  expectRejected('semantic extra set', ({ core }) => {
    mutateJson(core, SEMANTIC_PATH, (manifest) => {
      manifest.tupleSets.push({ count: 0, name: 'z-extra', sha256: sha256(''), sourcePaths: ['x'], tuples: [] });
    });
  }, /tuple-set names must be exactly/u);
  expectRejected('generated signature drift', ({ core }) => {
    write(core, 'target/mcp-api-freezes/phase-4.signatures.jsonl', '{"id":"different"}\n');
  }, /generated and reviewed tuple sets differ/u);
  expectRejected('signature cardinality drift', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      --config.semanticExpectations.signatureCounts['phase-4'];
    });
  }, /Phase 4 signature count must be \d+, got \d+/u);
  expectRejected('owner cardinality drift', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      --config.semanticExpectations.ownerCounts['phase-4'];
    });
  }, /phase-4 owner count must be \d+, got \d+/u);
  expectRejected('owner partition overlap', ({ core }) => {
    write(core, 'api/mcp/phase-5.includes', 'com.soklet.McpFixture\n');
  }, /appears in both .*inventory|appears in both phase-4 and phase-5/u);
  expectRejected('generated incompatibility drift', ({ core }) => {
    write(core, 'target/japicmp/mcp-api-diff.incompatibilities.jsonl', '{"id":"different"}\n');
  }, /generated and reviewed tuple sets differ/u);
  expectRejected('incompatibility cardinality drift', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      --config.semanticExpectations.incompatibilityCount;
    });
  }, /incompatibility count must be \d+, got \d+/u);
  expectRejected('allowlist cardinality drift', ({ core }) => {
    mutateJson(core, CONFIG_PATH, (config) => {
      config.semanticExpectations.allowlistCount = 0;
    });
  }, /allowlist count must be 0, got 1/u);
  expectRejected('allowlist inventory mismatch', ({ core }) => {
    write(
      core,
      'api/mcp/non-mcp-public-api.allowlist',
      'com.soklet.extra.NotInCompilerReport\n',
    );
  }, /unexpected .*missing|missing .*unexpected/su);
  expectRejected('missing reflection phase', ({ core }) => {
    const path = resolve(core, goldenConfig.reflectionDigestSourcePath);
    writeFileSync(path, readFileSync(path, 'utf8').replace('PHASE_SIX_', 'PHASE_FIVE_'));
  }, /exactly three|phases must be unique/u);

  expectRejected('preview evidence missing row', ({ core }) => {
    mutateJson(core, PREVIEW_PATH, (manifest) => { manifest.artifacts.pop(); });
  }, /do not match the frozen configuration/u);
  expectRejected('preview evidence hash drift', ({ core }) => {
    mutateJson(core, PREVIEW_PATH, (manifest) => { manifest.artifacts[0].sha256 = '0'.repeat(64); });
  });
  expectRejected('preview evidence self path', ({ core }) => {
    mutateJson(core, PREVIEW_PATH, (manifest) => {
      manifest.artifacts[0].path = PREVIEW_PATH;
      manifest.artifacts.sort((a, b) => Buffer.compare(Buffer.from(a.path), Buffer.from(b.path)));
    });
  }, /do not match the frozen configuration|must not hash itself/u);
  expectRejected('compiler XML drift with stable JSONL', ({ core }) => {
    const path = resolve(core, 'target/japicmp/mcp-api-freeze.xml');
    writeFileSync(
      path,
      readFileSync(path, 'utf8').replace('newVersion="new"', 'newVersion="drift"'),
    );
  }, /report pair metadata differs/u);

  expectRejected('external manifest omitted README', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => { manifest.entries.splice(2, 1); });
  }, /cardinality/u);
  expectRejected('external manifest extra row', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => {
      manifest.entries.push({ ...manifest.entries.at(-1), path: 'mcp/z-extra' });
    });
  }, /cardinality/u);
  expectRejected('external manifest reorder', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => {
      [manifest.entries[0], manifest.entries[1]] = [manifest.entries[1], manifest.entries[0]];
    });
  }, /bytewise sorted/u);
  expectRejected('external owner drift', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => { manifest.entries[0].owner = 'MCP-7'; });
  }, /policy does not match/u);
  expectRejected('external reason drift', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => { manifest.entries[0].reason += ' drift'; });
  }, /policy does not match/u);
  expectRejected('external base hash drift', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => { manifest.entries[0].baseSha256 = '0'.repeat(64); });
  }, /policy does not match/u);
  expectRejected('external preview hash drift', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => { manifest.entries[0].previewSha256 = '0'.repeat(64); });
  });
  expectRejected('external preview byte drift', ({ external }) => {
    write(external, goldenConfig.externalEntries[0].path, 'changed sibling bytes\n');
  });
  expectRejected('deleted external path unexpectedly present', ({ external }) => {
    const entry = goldenConfig.externalEntries.find((candidate) => candidate.changeKind === 'deleted');
    write(external, entry.path, 'unexpected restored path\n');
  }, /must be absent for a configured deletion/u);
  expectRejected('added external path unexpectedly absent', ({ external }) => {
    const entry = goldenConfig.externalEntries.find((candidate) => candidate.changeKind === 'added');
    rmSync(resolve(external, entry.path));
  }, /Missing external preview/u);
  expectRejected('modified external path unexpectedly absent', ({ external }) => {
    const entry = goldenConfig.externalEntries.find((candidate) => candidate.changeKind === 'modified');
    rmSync(resolve(external, entry.path));
  }, /Missing external preview/u);
  expectRejected('external manifest has both hashes null', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => {
      manifest.entries[0].baseSha256 = null;
      manifest.entries[0].previewSha256 = null;
    });
  }, /must not both be null/u);
  expectRejected('deleted external manifest has preview hash', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => {
      const entry = manifest.entries.find((candidate) => candidate.previewSha256 === null);
      entry.previewSha256 = '0'.repeat(64);
    });
  }, /previewSha256 must be null for a deleted path/u);
  expectRejected('added external manifest lacks preview hash', ({ external }) => {
    mutateJson(external, EXTERNAL_PATH, (manifest) => {
      const entry = manifest.entries.find((candidate) => candidate.baseSha256 === null);
      entry.previewSha256 = null;
    });
  }, /must not both be null|previewSha256 must be lowercase SHA-256 for added path/u);
  expectRejected('external noncanonical JSON', ({ external }) => {
    const value = readJson(external, EXTERNAL_PATH);
    writeFileSync(resolve(external, EXTERNAL_PATH), `${JSON.stringify(value)}\n`);
  }, /not canonical/u);

  expectRejected('root extra field', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => { manifest.unexpected = true; });
  }, /keys must be exactly/u);
  expectRejected('root self cycle', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.trackedBlobManifest.path = ROOT_PATH;
    });
  }, /must be exactly/u);
  expectRejected('root tracked leaf hash drift', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.trackedBlobManifest.sha256 = '0'.repeat(64);
    });
  }, /does not match leaf bytes/u);
  expectRejected('root preview leaf hash drift', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.previewEvidenceManifest.sha256 = '0'.repeat(64);
    });
  }, /does not match retained leaf bytes|deterministic derivation/u);
  expectRejected('root external raw hash drift', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.externalManifestSha256 = '0'.repeat(64);
    });
  }, /does not match external manifest bytes/u);
  expectRejected('root external entry-set hash drift', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => {
      manifest.externalEntrySetSha256 = '0'.repeat(64);
    });
  }, /does not match canonical external entries/u);
  expectRejected('root wrong base commit', ({ core }) => {
    mutateJson(core, ROOT_PATH, (manifest) => { manifest.baseCoreCommit = '0'.repeat(40); });
  }, /baseCoreCommit mismatch/u);
  expectRejected('root CRLF', ({ core }) => {
    const path = resolve(core, ROOT_PATH);
    writeFileSync(path, readFileSync(path, 'utf8').replaceAll('\n', '\r\n'));
  }, /not canonical/u);

  const productionExternal = productionConfigForSelfTest().externalEntries;
  assert.equal(productionExternal[0].owner, 'MCP-4');
  assert.deepEqual(productionExternal[0].allowedPostD2Owner, ['MCP-C', 'MCP-7']);
  assert.equal(productionExternal[1].owner, 'U5/D1p');
  assert.deepEqual(productionExternal[2].allowedPostD2Owner, ['MCP-7']);
  assert.equal(productionExternal[6].changeKind, 'deleted');
  assert.equal(productionExternal[6].baseSha256,
    'b03561ef42e85f755bc831cef65a22cf636a3553a2e50676d7633e50b500296c');
  assert.equal(productionExternal[11].owner, 'MCP-3B');
  assert.equal(productionExternal[14].changeKind, 'added');
  assert.equal(productionExternal[14].baseSha256, null);
  ++passedCases;

  const semantic = readJson(goldenCore, SEMANTIC_PATH);
  validateSemanticManifest(semantic);
  validatePreviewEvidence(readJson(goldenCore, PREVIEW_PATH), goldenConfig);
  validateExternalManifest(readJson(goldenExternal, EXTERNAL_PATH), goldenConfig);
  validateRootManifest(readJson(goldenCore, ROOT_PATH), goldenConfig);
  validateTrackedBlobManifest(readFileSync(resolve(goldenCore, TRACKED_BLOB_PATH)), goldenConfig);
  ++passedCases;

  console.log(`D1p evidence verifier self-test passed ${passedCases} cases`);
} finally {
  rmSync(temporaryRoot, { recursive: true, force: true });
}
