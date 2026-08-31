#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  CodeqlReleaseProvenanceError,
  stageCodeqlReleaseProvenance,
} from './stage-codeql-release-provenance.mjs';

const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-codeql-provenance-')));
let assertions = 0;

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function fixture(label) {
  const fixtureRoot = join(root, label);
  const codeqlRoot = join(fixtureRoot, 'codeql');
  mkdirSync(join(codeqlRoot, 'qlpacks', 'java'), { recursive: true });
  const codeqlPath = join(codeqlRoot, 'codeql');
  writeFileSync(codeqlPath, '#!/usr/bin/env bash\nexit 0\n', { mode: 0o755 });
  const bundlePath = join(fixtureRoot, 'codeql-bundle-linux64.tar.gz');
  writeFileSync(bundlePath, 'bundle');
  const values = {
    qlpackSha256: 'qlpack',
    securityExtendedSuiteSelectorSha256: 'selector',
    securityExtendedSuiteSha256: 'suite',
  };
  writeFileSync(join(codeqlRoot, 'qlpacks', 'java', 'qlpack.yml'), values.qlpackSha256);
  writeFileSync(
    join(codeqlRoot, 'qlpacks', 'java', 'security-extended-selectors.yml'),
    values.securityExtendedSuiteSelectorSha256,
  );
  writeFileSync(
    join(codeqlRoot, 'qlpacks', 'java', 'security-extended.qls'),
    values.securityExtendedSuiteSha256,
  );
  return {
    bundlePath,
    codeqlPath,
    expectedBundleSha256: sha256('bundle'),
    expectedDescriptors: Object.fromEntries(
      Object.entries(values).map(([name, value]) => [name, sha256(value)]),
    ),
    outputRoot: join(fixtureRoot, 'output'),
  };
}

try {
  const valid = fixture('valid');
  const result = stageCodeqlReleaseProvenance(valid);
  assert.equal(result.bundleSha256, valid.expectedBundleSha256);
  assertions++;
  assert.equal(
    readFileSync(join(valid.outputRoot, 'codeql-java-queries-qlpack.yml'), 'utf8'),
    'qlpack',
  );
  assert.equal(
    readFileSync(join(valid.outputRoot, 'codeql-java-security-extended-selectors.yml'), 'utf8'),
    'selector',
  );
  assert.equal(
    readFileSync(join(valid.outputRoot, 'codeql-java-security-extended.qls'), 'utf8'),
    'suite',
  );
  assertions += 3;

  assert.throws(() => stageCodeqlReleaseProvenance(valid), /already exists/);
  assertions++;

  const wrongBundle = fixture('wrong-bundle');
  wrongBundle.expectedBundleSha256 = '0'.repeat(64);
  assert.throws(
    () => stageCodeqlReleaseProvenance(wrongBundle),
    /bundle SHA-256 mismatch/,
  );
  assertions++;

  const missingDescriptor = fixture('missing-descriptor');
  missingDescriptor.expectedDescriptors.securityExtendedSuiteSha256 = '1'.repeat(64);
  assert.throws(
    () => stageCodeqlReleaseProvenance(missingDescriptor),
    /missing approved securityExtendedSuiteSha256 bytes/,
  );
  assertions++;

  const malformed = fixture('malformed');
  malformed.expectedDescriptors.qlpackSha256 = 'not-a-digest';
  assert.throws(
    () => stageCodeqlReleaseProvenance(malformed),
    CodeqlReleaseProvenanceError,
  );
  assertions++;

  const directoryInsteadOfBinary = fixture('directory-instead-of-binary');
  directoryInsteadOfBinary.codeqlPath = join(
    directoryInsteadOfBinary.codeqlPath,
    '..',
  );
  assert.throws(
    () => stageCodeqlReleaseProvenance(directoryInsteadOfBinary),
    /CodeQL executable must be a nonempty bounded regular nonsymlink file/,
  );
  assertions++;

  console.log(`CodeQL release-provenance self-test passed (${assertions} assertions).`);
} finally {
  rmSync(root, { recursive: true });
}
