#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  constants as fsConstants,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  openSync,
  readSync,
  closeSync,
  readdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, isAbsolute, parse, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  canonicalJson,
  createReleaseHarnessBundle,
  releaseHarnessCandidateIdentity,
  ReleaseHarnessEvidenceImportError,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';

const GATE = 'release-scans';
const MAXIMUM_INPUT_BYTES = 128 * 1024 * 1024;
const MAXIMUM_TOOLCHAIN_BYTES = 1024 * 1024 * 1024;
const RAW_REPORT_NAMES = Object.freeze([
  '00-codeql-java.sarif',
  '01-spotbugs.xml',
  '02-gitleaks.sarif',
  '03-gitleaks.json',
  '04-runtime-dependency-surface.json',
]);
const STAGED_PROVENANCE_FILES = Object.freeze({
  codeqlBundle: 'codeql-bundle-linux64.tar.gz',
  codeqlQlpPack: 'codeql-java-queries-qlpack.yml',
  codeqlSuite: 'codeql-java-security-extended.qls',
  codeqlSuiteSelector: 'codeql-java-security-extended-selectors.yml',
  gitleaksArchive: 'gitleaks_8.30.1_linux_x64.tar.gz',
  gitleaksConfig: 'gitleaks.toml',
  spotbugsEngine: 'spotbugs.jar',
  spotbugsFilter: 'spotbugs-exclude.xml',
  spotbugsPlugin: 'spotbugs-maven-plugin.jar',
});

export class ReleaseScanProducerError extends Error {}

function fail(message) {
  throw new ReleaseScanProducerError(message);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function requireNonsymlinkComponents(path, label) {
  const absolute = resolve(path);
  const root = parse(absolute).root;
  let current = root;
  for (const component of absolute.slice(root.length).split(sep).filter(Boolean)) {
    current = resolve(current, component);
    if (existsSync(current) && lstatSync(current).isSymbolicLink())
      fail(`${label} contains a symbolic-link path component: ${current}`);
  }
}

function absolutePath(path, label) {
  if (typeof path !== 'string' || !isAbsolute(path))
    fail(`${label} must be an absolute path.`);
  const absolute = resolve(path);
  requireNonsymlinkComponents(absolute, label);
  return absolute;
}

function readRegularFile(path, label) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} is missing: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink()
      || stats.size <= 0 || stats.size > MAXIMUM_INPUT_BYTES) {
    fail(`${label} must be a nonempty bounded regular nonsymlink file: ${path}`);
  }
  return readFileSync(path);
}

function requireRealDirectory(path, label) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink() || realpathSync(path) !== path)
    fail(`${label} must be a real nonsymlink directory: ${path}`);
}

function requireExactFiles(root, expectedNames, label) {
  requireRealDirectory(root, label);
  const entries = readdirSync(root, { withFileTypes: true })
    .sort((left, right) => compareAscii(left.name, right.name));
  const names = entries.map(({ name }) => name);
  if (names.length !== expectedNames.length
      || names.some((name, index) => name !== expectedNames[index])) {
    fail(`${label} files must be exactly: ${expectedNames.join(', ')}.`);
  }
  for (const entry of entries) {
    if (!entry.isFile() || entry.isSymbolicLink())
      fail(`${label} entry must be a regular nonsymlink file: ${entry.name}`);
  }
}

function fileSha256(path, label) {
  requireNonsymlinkComponents(path, label);
  if (!existsSync(path))
    fail(`${label} is missing: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink()
      || stats.size <= 0 || stats.size > MAXIMUM_TOOLCHAIN_BYTES) {
    fail(`${label} must be a nonempty bounded regular nonsymlink file: ${path}`);
  }
  const hash = createHash('sha256');
  const descriptor = openSync(path, 'r');
  try {
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    for (;;) {
      const count = readSync(descriptor, buffer, 0, buffer.length, null);
      if (count === 0)
        break;
      hash.update(buffer.subarray(0, count));
    }
  } finally {
    closeSync(descriptor);
  }
  return hash.digest('hex');
}

function requireDigest(path, expected, label) {
  const actual = fileSha256(path, label);
  if (actual !== expected)
    fail(`${label} SHA-256 mismatch: expected ${expected}, found ${actual}.`);
}

function writeNewFile(path, value) {
  const descriptor = openSync(path, fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY, 0o600);
  try {
    writeFileSync(descriptor, value, 'utf8');
  } finally {
    closeSync(descriptor);
  }
}

function policyDigest(contract, dottedPath) {
  let value = contract.policy;
  for (const key of dottedPath.split('.'))
    value = value?.[key];
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/u.test(value))
    fail(`release-scans contract has no valid ${dottedPath} SHA-256.`);
  return value;
}

export function prepareReleaseScanEvidence({
  candidate,
  candidateRoot,
  contract,
  evidenceRoot,
  provenanceRoot,
  rawReportsRoot,
}) {
  const absoluteCandidateRoot = absolutePath(candidateRoot, 'candidate root');
  const absoluteEvidenceRoot = absolutePath(evidenceRoot, 'release-scan evidence root');
  const absoluteProvenanceRoot = absolutePath(provenanceRoot, 'release-scan provenance root');
  const absoluteRawReportsRoot = absolutePath(rawReportsRoot, 'release-scan raw-report root');
  if (existsSync(absoluteEvidenceRoot))
    fail(`release-scan evidence root already exists: ${absoluteEvidenceRoot}`);
  if (contract?.id !== GATE)
    fail('release-scan producer requires the exact release-scans contract.');
  requireExactFiles(absoluteRawReportsRoot, RAW_REPORT_NAMES, 'release-scan raw-report root');
  requireExactFiles(
    absoluteProvenanceRoot,
    Object.values(STAGED_PROVENANCE_FILES).sort(compareAscii),
    'release-scan provenance root',
  );

  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.codeqlBundle),
    policyDigest(contract, 'codeql.bundle.linuxTarGzSha256'),
    'CodeQL bundle',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.codeqlQlpPack),
    policyDigest(contract, 'codeql.javaQueries.qlpackSha256'),
    'CodeQL Java query-pack descriptor',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.codeqlSuite),
    policyDigest(contract, 'codeql.javaQueries.securityExtendedSuiteSha256'),
    'CodeQL security-extended suite',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.codeqlSuiteSelector),
    policyDigest(contract, 'codeql.javaQueries.securityExtendedSuiteSelectorSha256'),
    'CodeQL security-extended suite selector',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.gitleaksArchive),
    policyDigest(contract, 'gitleaks.linuxX64ArchiveSha256'),
    'Gitleaks archive',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.gitleaksConfig),
    policyDigest(contract, 'gitleaks.configSha256'),
    'Gitleaks configuration',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.spotbugsEngine),
    policyDigest(contract, 'spotbugs.engineJarSha256'),
    'SpotBugs engine JAR',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.spotbugsFilter),
    policyDigest(contract, 'spotbugs.exclusionFileSha256'),
    'SpotBugs exclusion filter',
  );
  requireDigest(
    resolve(absoluteProvenanceRoot, STAGED_PROVENANCE_FILES.spotbugsPlugin),
    policyDigest(contract, 'spotbugs.mavenPluginJarSha256'),
    'SpotBugs Maven-plugin JAR',
  );

  mkdirSync(absoluteEvidenceRoot);
  const reportDirectory = resolve(absoluteEvidenceRoot, 'release-scans');
  mkdirSync(reportDirectory);
  for (const name of RAW_REPORT_NAMES) {
    copyFileSync(
      resolve(absoluteRawReportsRoot, name),
      resolve(reportDirectory, name),
      fsConstants.COPYFILE_EXCL,
    );
  }
  const provenanceName = '05-toolchain-provenance.json';
  writeNewFile(resolve(reportDirectory, provenanceName), canonicalJson({
    candidate,
    codeql: contract.policy.codeql,
    formatVersion: 1,
    gitleaks: contract.policy.gitleaks,
    producerWorkflowSha256: candidate.producerWorkflowSha256,
    spotbugs: contract.policy.spotbugs,
    toolchains: contract.toolchains,
  }));
  const reportNames = [...RAW_REPORT_NAMES, provenanceName];
  const reports = contract.policy.reports.map(({ name, ordinal }) => {
    if (reportNames[ordinal] !== name)
      fail(`registered release-scan report order drifted at ordinal ${ordinal}.`);
    return {
      name,
      ordinal,
      outcome: 'PASS',
      sha256: sha256(readRegularFile(resolve(reportDirectory, name), `scan report ${name}`)),
    };
  });
  const runtimeSurface = JSON.parse(
    readRegularFile(
      resolve(reportDirectory, '04-runtime-dependency-surface.json'),
      'runtime dependency surface',
    ).toString('utf8'),
  );
  writeNewFile(resolve(absoluteEvidenceRoot, 'release-scans.json'), canonicalJson({
    allowlist: [],
    candidate,
    findings: [],
    formatVersion: 1,
    gate: GATE,
    policySha256: sha256(Buffer.from(canonicalJson(contract.policy), 'utf8')),
    producerStatus: 'PASS',
    reports,
    runtimeDependencySurface: {
      externalRuntimeDependencyCount: runtimeSurface.externalRuntimeDependencyCount,
    },
    toolchainsSha256: sha256(Buffer.from(canonicalJson(contract.toolchains), 'utf8')),
  }));
  return Object.freeze({ evidenceRoot: absoluteEvidenceRoot, reports: Object.freeze(reports) });
}

export function produceReleaseScans({
  candidateIdentityProvider = releaseHarnessCandidateIdentity,
  candidateRoot,
  evidenceRoot,
  outputPath,
  provenanceRoot,
  rawReportsRoot,
}) {
  const absoluteCandidateRoot = absolutePath(candidateRoot, 'candidate root');
  const configuration = verifyReleaseHarnessConfiguration(
    resolve(absoluteCandidateRoot, 'release/release-harness-contracts.json'),
  );
  const contract = configuration.contracts.get(GATE);
  const candidate = candidateIdentityProvider({ candidateRoot: absoluteCandidateRoot, gate: GATE });
  prepareReleaseScanEvidence({
    candidate,
    candidateRoot: absoluteCandidateRoot,
    contract,
    evidenceRoot,
    provenanceRoot,
    rawReportsRoot,
  });
  return createReleaseHarnessBundle({
    candidateRoot: absoluteCandidateRoot,
    evidenceRoot,
    gate: GATE,
    outputPath,
  });
}

function usage() {
  return 'Usage: node scripts/produce-release-scans.mjs '
    + '--candidate-root <absolute-path> --raw-reports-root <absolute-path> '
    + '--provenance-root <absolute-path> --evidence-root <absolute-path> '
    + '--output <absolute-path>';
}

function parseArguments(args) {
  const expected = new Set([
    '--candidate-root',
    '--evidence-root',
    '--output',
    '--provenance-root',
    '--raw-reports-root',
  ]);
  const values = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const flag = args[index];
    const value = args[index + 1];
    if (!expected.has(flag) || value === undefined || values.has(flag))
      fail(usage());
    values.set(flag, value);
  }
  if (values.size !== expected.size)
    fail(usage());
  return {
    candidateRoot: values.get('--candidate-root'),
    evidenceRoot: values.get('--evidence-root'),
    outputPath: values.get('--output'),
    provenanceRoot: values.get('--provenance-root'),
    rawReportsRoot: values.get('--raw-reports-root'),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  try {
    const bundle = produceReleaseScans(parseArguments(process.argv.slice(2)));
    console.log(`release scans producer PASS contentSha256=${bundle.contentSha256}`);
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = error instanceof ReleaseScanProducerError
      || error instanceof ReleaseHarnessEvidenceImportError ? 1 : 70;
  }
}
