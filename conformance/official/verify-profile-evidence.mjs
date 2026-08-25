#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  readFileSync,
  realpathSync,
} from 'node:fs';
import {
  dirname,
  isAbsolute,
  posix,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const FORMAT_VERSION = 1;
const OWNERSHIP_SENTINEL = 'global-2026-deferred-r2c';
const PRODUCTION_REVISIONS = Object.freeze(['2026-07-28']);
const EXPECTED_GOLDEN_PATHS = Object.freeze([
  'conformance/golden-error-mapping/live/manifest.sha256',
  'conformance/golden-http-contract/precedence-no-store/manifest.sha256',
  'conformance/golden-http-head/authorization-cors/manifest.sha256',
  'conformance/golden-result-envelope/live/manifest.sha256',
  'conformance/official/golden-wire/manifest.json',
]);
const EXPECTED_INTEROPERABILITY = Object.freeze(['go', 'typescript']);
const SHA256 = /^[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const TOP_LEVEL_KEYS = Object.freeze([
  'formatVersion',
  'methodParameterOwnership',
  'profiles',
]);
const PROFILE_KEYS = Object.freeze([
  'revision',
  'specification',
  'schema',
  'officialConformance',
  'scenarios',
  'goldens',
  'interoperability',
]);

export class ProfileEvidenceVerificationError extends Error {}

function fail(message) {
  throw new ProfileEvidenceVerificationError(message);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

function exactKeys(value, keys, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = Object.keys(value);
  if (actual.length !== keys.length
      || actual.some((key, index) => key !== keys[index])) {
    fail(`${label} keys must be exactly: ${keys.join(', ')}.`);
  }
}

function readCanonicalJson(path, label) {
  const bytes = readRegularFile(path, label);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes)) fail(`${label} must be UTF-8.`);
  if (text.includes('\r') || !text.endsWith('\n')) {
    fail(`${label} must use LF endings and end with one LF.`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is malformed JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text) fail(`${label} must be canonical two-space JSON.`);
  return { bytes, value };
}

function readRegularFile(path, label) {
  if (!existsSync(path)) fail(`${label} does not exist: ${path}`);
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`${label} must be a regular non-symlink file: ${path}`);
  }
  return readFileSync(path);
}

function containedTrackedFile(projectRoot, candidatePath, label, gitExecutable) {
  if (typeof candidatePath !== 'string' || candidatePath.length === 0
      || candidatePath.includes('\\') || isAbsolute(candidatePath)
      || posix.normalize(candidatePath) !== candidatePath
      || candidatePath === '.' || candidatePath.startsWith('../')
      || candidatePath.includes('/../') || candidatePath === '.git'
      || candidatePath.startsWith('.git/') || candidatePath === 'target'
      || candidatePath.startsWith('target/')) {
    fail(`${label} must be a normalized candidate-relative path.`);
  }
  const absolute = resolve(projectRoot, candidatePath);
  const lexical = relative(projectRoot, absolute);
  if (lexical === '..' || lexical.startsWith(`..${sep}`) || isAbsolute(lexical)) {
    fail(`${label} escapes the candidate: ${candidatePath}`);
  }
  let component = projectRoot;
  for (const segment of candidatePath.split('/')) {
    component = resolve(component, segment);
    if (!existsSync(component) || lstatSync(component).isSymbolicLink()) {
      fail(`${label} is missing or traverses a symlink: ${candidatePath}`);
    }
  }
  readRegularFile(absolute, label);
  const physical = relative(realpathSync(projectRoot), realpathSync(absolute));
  if (physical === '..' || physical.startsWith(`..${sep}`) || isAbsolute(physical)) {
    fail(`${label} resolves outside the candidate: ${candidatePath}`);
  }
  const tracked = spawnSync(gitExecutable, [
    '-c', `safe.directory=${projectRoot}`, '-C', projectRoot,
    'ls-files', '--error-unmatch', '--', candidatePath,
  ], { encoding: 'utf8' });
  if (tracked.error !== undefined) {
    fail(`Unable to inspect ${label} tracking: ${tracked.error.message}`);
  }
  if (tracked.status !== 0) fail(`${label} is not tracked: ${candidatePath}`);
  return absolute;
}

function verifyPinnedFile(projectRoot, pin, label, gitExecutable) {
  exactKeys(pin, ['path', 'sha256'], label);
  if (typeof pin.sha256 !== 'string' || !SHA256.test(pin.sha256)) {
    fail(`${label}.sha256 must be lowercase SHA-256.`);
  }
  const path = containedTrackedFile(projectRoot, pin.path, label, gitExecutable);
  const actual = sha256(readFileSync(path));
  if (actual !== pin.sha256) fail(`${label} digest does not match ${pin.path}.`);
  return pin.path;
}

function verifyProfile(profile, projectRoot, releaseManifest, gitExecutable) {
  exactKeys(profile, PROFILE_KEYS, `Profile ${String(profile?.revision)}`);
  if (!PRODUCTION_REVISIONS.includes(profile.revision)) {
    fail(`Unknown production profile revision ${String(profile.revision)}.`);
  }

  exactKeys(profile.specification, ['repository', 'tag', 'commit'],
    `Profile ${profile.revision} specification`);
  if (profile.specification.repository
        !== 'https://github.com/modelcontextprotocol/modelcontextprotocol.git'
      || profile.specification.tag !== profile.revision
      || typeof profile.specification.commit !== 'string'
      || !COMMIT.test(profile.specification.commit)) {
    fail(`Profile ${profile.revision} specification pin is invalid.`);
  }

  const schemaPath = verifyPinnedFile(
    projectRoot, profile.schema, `Profile ${profile.revision} schema`, gitExecutable,
  );
  exactKeys(profile.officialConformance,
    ['pinsPath', 'pinsSha256', 'suiteCommit'],
    `Profile ${profile.revision} officialConformance`);
  if (!SHA256.test(profile.officialConformance.pinsSha256)
      || !COMMIT.test(profile.officialConformance.suiteCommit)) {
    fail(`Profile ${profile.revision} official-conformance pins are malformed.`);
  }
  const pinsPath = containedTrackedFile(projectRoot,
    profile.officialConformance.pinsPath,
    `Profile ${profile.revision} official-conformance pins`, gitExecutable);
  if (sha256(readFileSync(pinsPath)) !== profile.officialConformance.pinsSha256) {
    fail(`Profile ${profile.revision} official-conformance digest does not match.`);
  }
  const pins = readCanonicalJson(pinsPath, 'Official conformance pins').value;
  if (pins.protocolVersion !== profile.revision
      || pins.officialConformanceSuite?.commit
        !== profile.officialConformance.suiteCommit
      || pins.finalSpecification?.repository !== profile.specification.repository
      || pins.finalSpecification?.tag !== profile.specification.tag
      || pins.finalSpecification?.commit !== profile.specification.commit
      || resolve(projectRoot, 'conformance/official',
        pins.finalSpecification?.schema?.vendoredPath ?? '')
        !== resolve(projectRoot, schemaPath)
      || pins.finalSpecification?.schema?.sha256 !== profile.schema.sha256) {
    fail(`Profile ${profile.revision} does not match upstream-pins.json.`);
  }

  const scenarioPath = verifyPinnedFile(projectRoot, profile.scenarios,
    `Profile ${profile.revision} scenarios`, gitExecutable);
  if (profile.scenarios.path !== 'conformance/official/scenarios.json') {
    fail(`Profile ${profile.revision} scenario path is not canonical.`);
  }
  const scenarios = readCanonicalJson(resolve(projectRoot, scenarioPath),
    'Official scenarios').value;
  if (scenarios.protocolVersion !== profile.revision) {
    fail(`Profile ${profile.revision} scenario revision does not match.`);
  }

  if (!Array.isArray(profile.goldens)
      || profile.goldens.length !== EXPECTED_GOLDEN_PATHS.length) {
    fail(`Profile ${profile.revision} must bind every reviewed golden manifest.`);
  }
  const goldenPaths = profile.goldens.map((pin, index) => verifyPinnedFile(
    projectRoot, pin, `Profile ${profile.revision} golden ${index}`, gitExecutable,
  ));
  if (goldenPaths.some((path, index) => path !== EXPECTED_GOLDEN_PATHS[index])) {
    fail(`Profile ${profile.revision} golden paths must match canonical ASCII order.`);
  }

  if (!Array.isArray(profile.interoperability)
      || profile.interoperability.length !== EXPECTED_INTEROPERABILITY.length) {
    fail(`Profile ${profile.revision} must bind both interoperability pins.`);
  }
  for (const [index, pin] of profile.interoperability.entries()) {
    const implementation = EXPECTED_INTEROPERABILITY[index];
    exactKeys(pin, [
      'implementation', 'artifactIdentity', 'artifactChecksum', 'commit',
      'lockPath', 'lockSha256',
    ], `Profile ${profile.revision} interoperability ${index}`);
    if (pin.implementation !== implementation || !COMMIT.test(pin.commit)
        || !SHA256.test(pin.lockSha256)) {
      fail(`Profile ${profile.revision} interoperability pin ${index} is invalid.`);
    }
    const gateId = implementation === 'go' ? 'go-interop' : 'typescript-interop';
    const gate = releaseManifest.gates.find((candidate) => candidate.id === gateId);
    if (gate === undefined || gate.artifactIdentity !== pin.artifactIdentity
        || gate.artifactChecksum !== pin.artifactChecksum
        || gate.commit !== pin.commit) {
      fail(`Profile ${profile.revision} ${implementation} pin differs from the release manifest.`);
    }
    const lockPath = containedTrackedFile(projectRoot, pin.lockPath,
      `Profile ${profile.revision} ${implementation} lock`, gitExecutable);
    if (sha256(readFileSync(lockPath)) !== pin.lockSha256) {
      fail(`Profile ${profile.revision} ${implementation} lock digest does not match.`);
    }
  }

  return Object.freeze({
    revision: profile.revision,
    specificationCommit: profile.specification.commit,
    officialSuiteCommit: profile.officialConformance.suiteCommit,
    goldenManifestCount: profile.goldens.length,
    interoperabilityPinCount: profile.interoperability.length,
  });
}

export function verifyProfileEvidence(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const indexPath = resolve(options.indexPath
    ?? resolve(projectRoot, 'conformance/official/protocol-profile-evidence.json'));
  const gitExecutable = options.gitExecutable ?? 'git';
  if (typeof gitExecutable !== 'string' || gitExecutable.length === 0) {
    fail('gitExecutable must be a nonempty string.');
  }
  const { bytes, value: index } = readCanonicalJson(indexPath,
    'Protocol-profile evidence index');
  exactKeys(index, TOP_LEVEL_KEYS, 'Protocol-profile evidence index');
  if (index.formatVersion !== FORMAT_VERSION) {
    fail(`Protocol-profile evidence formatVersion must be ${FORMAT_VERSION}.`);
  }
  if (index.methodParameterOwnership !== OWNERSHIP_SENTINEL) {
    fail(`methodParameterOwnership must be exactly ${OWNERSHIP_SENTINEL}.`);
  }
  if (!Array.isArray(index.profiles) || index.profiles.length !== 1) {
    fail('Deferred R2C ownership requires exactly one production profile entry.');
  }
  const revisions = index.profiles.map((profile) => profile?.revision);
  if (new Set(revisions).size !== revisions.length
      || revisions.some((revision, i) => revision !== PRODUCTION_REVISIONS[i])) {
    fail('Production profile entries are missing, unknown, duplicated, or reordered.');
  }

  const manifestPath = containedTrackedFile(projectRoot,
    'release/release-validation-manifest.json', 'Release manifest', gitExecutable);
  const releaseManifest = readCanonicalJson(manifestPath, 'Release manifest').value;
  if (!Array.isArray(releaseManifest.gates)) fail('Release manifest has no gates array.');

  const verifiedProfiles = index.profiles.map((profile) => verifyProfile(
    profile, projectRoot, releaseManifest, gitExecutable,
  ));
  const report = Object.freeze({
    formatVersion: FORMAT_VERSION,
    methodParameterOwnership: index.methodParameterOwnership,
    status: 'PASSED',
    productionProfileCount: verifiedProfiles.length,
    revisions,
    evidenceIndexSha256: sha256(bytes),
    verifiedProfiles,
  });
  return Object.freeze({ report, reportText: canonicalJson(report) });
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 2) {
    console.error('Usage: node conformance/official/verify-profile-evidence.mjs');
    process.exitCode = 2;
  } else {
    try {
      process.stdout.write(verifyProfileEvidence().reportText);
    } catch (error) {
      console.error(`Protocol-profile evidence verification failed: ${error.message}`);
      process.exitCode = 1;
    }
  }
}
