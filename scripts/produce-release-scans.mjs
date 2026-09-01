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
const APPROVALS_RELATIVE_PATH = 'release/release-scan-exceptions.json';
const COMMIT_PATTERN = /^[0-9a-f]{40}$/u;
const SHA256_PATTERN = /^[0-9a-f]{64}$/u;
const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/u;
const MAXIMUM_EXCEPTION_LIFETIME_MILLISECONDS = 30 * 24 * 60 * 60 * 1000;
const WILDCARD_PATTERN = /[*?\[\]{}]/u;
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

function requirePlainObject(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  return value;
}

function requireExactKeys(value, expected, label) {
  requirePlainObject(value, label);
  const actual = Object.keys(value).sort(compareAscii);
  const wanted = [...expected].sort(compareAscii);
  if (actual.length !== wanted.length
      || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label} keys must be exactly: ${wanted.join(', ')}.`);
  }
}

function requireTrimmedText(value, label, maximumLength = 512) {
  if (typeof value !== 'string' || value.length === 0 || value.length > maximumLength
      || value.trim() !== value || /[\u0000-\u001f\u007f]/u.test(value)) {
    fail(`${label} must be nonempty trimmed single-line text of at most ${maximumLength} characters.`);
  }
  return value;
}

function requireRelativeScanPath(value, label) {
  requireTrimmedText(value, label, 4096);
  if (value.includes('\\') || value.startsWith('/')
      || value.split('/').some((part) => part.length === 0 || part === '.' || part === '..')) {
    fail(`${label} must be a normalized relative POSIX path.`);
  }
  return value;
}

function requirePositiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 1)
    fail(`${label} must be a positive integer.`);
  return value;
}

function parseUtc(value, label) {
  if (typeof value !== 'string' || !ISO_UTC_PATTERN.test(value))
    fail(`${label} must be a second-precision UTC timestamp.`);
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString() !== value.replace('Z', '.000Z'))
    fail(`${label} must be a valid second-precision UTC timestamp.`);
  return milliseconds;
}

function parseCanonicalJson(bytes, label) {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes) || text.includes('\r') || !text.endsWith('\n'))
    fail(`${label} must be UTF-8 canonical JSON ending in LF.`);
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is not valid JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text)
    fail(`${label} must be canonical sorted-key JSON.`);
  return value;
}

function findingIdentity({ commit, endColumn, endLine, path, ruleId, startColumn, startLine }) {
  return { commit, endColumn, endLine, path, ruleId, startColumn, startLine };
}

export function releaseScanFindingFingerprint(identity) {
  return sha256(Buffer.from(canonicalJson(findingIdentity(identity)), 'utf8'));
}

function findingKey(finding) {
  return [finding.scanner, finding.ruleId, finding.commit, finding.path, finding.fingerprint].join('\0');
}

function normalizeSeverity(value, label) {
  if (value === undefined || value === null || value === '')
    return 'UNSPECIFIED';
  if (typeof value !== 'string')
    fail(`${label} severity must be a string when present.`);
  const severity = value.toUpperCase();
  if (!['UNSPECIFIED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(severity))
    fail(`${label} has unsupported severity ${value}.`);
  return severity;
}

function codeqlSecuritySeverity(value, label) {
  if (value === undefined)
    return 'UNSPECIFIED';
  if (typeof value !== 'string' || !/^(?:\d|10)(?:\.\d+)?$/u.test(value))
    fail(`${label} CodeQL security-severity must be a numeric string from 0 through 10.`);
  const score = Number(value);
  if (!Number.isFinite(score) || score < 0 || score > 10)
    fail(`${label} CodeQL security-severity must be from 0 through 10.`);
  if (score >= 9)
    return 'CRITICAL';
  if (score >= 7)
    return 'HIGH';
  if (score >= 4)
    return 'MEDIUM';
  return 'LOW';
}

function validateCodeqlInvocation(invocation, label) {
  requirePlainObject(invocation, label);
  if (invocation.executionSuccessful !== true
      || (invocation.exitCode !== undefined && invocation.exitCode !== 0)
      || invocation.processStartFailureMessage !== undefined) {
    fail(`${label} does not prove successful CodeQL execution.`);
  }
  for (const field of ['toolExecutionNotifications', 'toolConfigurationNotifications']) {
    if (invocation[field] === undefined)
      continue;
    if (!Array.isArray(invocation[field]))
      fail(`${label}.${field} must be an array.`);
    invocation[field].forEach((notification, index) => {
      requirePlainObject(notification, `${label}.${field}[${index}]`);
      if (notification.level === 'error' || notification.exception !== undefined)
        fail(`${label}.${field}[${index}] records incomplete CodeQL execution.`);
    });
  }
}

function codeqlRuleSeverityById(run, label) {
  const extensions = run.tool?.extensions ?? [];
  if (!Array.isArray(extensions))
    fail(`${label}.tool.extensions must be an array when present.`);
  const components = [run.tool?.driver, ...extensions];
  const severityById = new Map();
  components.forEach((component, componentIndex) => {
    requirePlainObject(component, `${label} tool component ${componentIndex + 1}`);
    if (component.rules === undefined)
      return;
    if (!Array.isArray(component.rules))
      fail(`${label} tool component ${componentIndex + 1}.rules must be an array.`);
    component.rules.forEach((rule, ruleIndex) => {
      const ruleLabel = `${label} rule ${componentIndex + 1}.${ruleIndex + 1}`;
      requirePlainObject(rule, ruleLabel);
      const ruleId = requireTrimmedText(rule.id, `${ruleLabel}.id`, 512);
      if (severityById.has(ruleId))
        fail(`${label} contains duplicate CodeQL rule metadata for ${ruleId}.`);
      severityById.set(
        ruleId,
        codeqlSecuritySeverity(rule.properties?.['security-severity'], ruleLabel),
      );
    });
  });
  return severityById;
}

function normalizeCodeqlSarif(bytes, candidateCommit) {
  if (typeof candidateCommit !== 'string' || !COMMIT_PATTERN.test(candidateCommit))
    fail('CodeQL candidate commit must be a lowercase 40-character Git object ID.');
  let value;
  try {
    value = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`CodeQL SARIF report is not valid JSON: ${error.message}`);
  }
  requirePlainObject(value, 'CodeQL SARIF report');
  if (value.version !== '2.1.0' || !Array.isArray(value.runs) || value.runs.length === 0)
    fail('CodeQL SARIF report must contain at least one SARIF 2.1.0 run.');
  const findings = [];
  value.runs.forEach((run, runIndex) => {
    const label = `CodeQL SARIF run ${runIndex + 1}`;
    requirePlainObject(run, label);
    const driverName = run.tool?.driver?.name;
    if (typeof driverName !== 'string' || driverName.toLowerCase() !== 'codeql')
      fail(`${label} is not from CodeQL.`);
    if (!Array.isArray(run.invocations) || run.invocations.length === 0)
      fail(`${label} has no scanner invocation evidence.`);
    run.invocations.forEach((invocation, index) =>
      validateCodeqlInvocation(invocation, `${label}.invocations[${index}]`));
    if (!Array.isArray(run.versionControlProvenance)
        || run.versionControlProvenance.length === 0) {
      fail(`${label} has no candidate version-control provenance.`);
    }
    run.versionControlProvenance.forEach((provenance, index) => {
      const provenanceLabel = `${label}.versionControlProvenance[${index}]`;
      requirePlainObject(provenance, provenanceLabel);
      if (provenance.revisionId !== candidateCommit)
        fail(`${provenanceLabel} does not bind the exact candidate commit.`);
      requireTrimmedText(provenance.repositoryUri, `${provenanceLabel}.repositoryUri`, 4096);
    });
    const severityByRuleId = codeqlRuleSeverityById(run, label);
    if (!Array.isArray(run.results))
      fail(`${label}.results must be an array.`);
    run.results.forEach((result, resultIndex) => {
      const resultLabel = `${label} result ${resultIndex + 1}`;
      requirePlainObject(result, resultLabel);
      const ruleId = requireTrimmedText(result.ruleId, `${resultLabel}.ruleId`, 512);
      if (!Array.isArray(result.locations) || result.locations.length !== 1)
        fail(`${resultLabel} must contain exactly one primary location.`);
      const physical = result.locations[0]?.physicalLocation;
      const artifact = physical?.artifactLocation;
      const region = physical?.region;
      if (artifact?.uriBaseId !== undefined && artifact.uriBaseId !== '%SRCROOT%')
        fail(`${resultLabel}.uriBaseId must be %SRCROOT% when present.`);
      const startLine = requirePositiveInteger(region?.startLine, `${resultLabel}.startLine`);
      const startColumn = region?.startColumn === undefined
        ? 1 : requirePositiveInteger(region.startColumn, `${resultLabel}.startColumn`);
      const endLine = region?.endLine === undefined
        ? startLine : requirePositiveInteger(region.endLine, `${resultLabel}.endLine`);
      const endColumn = region?.endColumn === undefined
        ? startColumn : requirePositiveInteger(region.endColumn, `${resultLabel}.endColumn`);
      const identity = findingIdentity({
        commit: candidateCommit,
        endColumn,
        endLine,
        path: requireRelativeScanPath(artifact?.uri, `${resultLabel}.uri`),
        ruleId,
        startColumn,
        startLine,
      });
      if (identity.endLine < identity.startLine
          || (identity.endLine === identity.startLine && identity.endColumn < identity.startColumn)) {
        fail(`${resultLabel} has an inverted source region.`);
      }
      const severity = result.properties?.['security-severity'] === undefined
        ? severityByRuleId.get(ruleId) ?? 'UNSPECIFIED'
        : codeqlSecuritySeverity(
          result.properties['security-severity'],
          `${resultLabel}.properties`,
        );
      findings.push({
        accepted: true,
        commit: candidateCommit,
        fingerprint: releaseScanFindingFingerprint(identity),
        path: identity.path,
        ruleId,
        scanner: 'codeql',
        severity,
        sourceIdentity: identity,
      });
    });
  });
  findings.sort((left, right) => compareAscii(findingKey(left), findingKey(right)));
  const keys = findings.map(findingKey);
  if (new Set(keys).size !== keys.length)
    fail('CodeQL SARIF report contains a duplicate exact finding identity.');
  return findings;
}

function normalizeGitleaksJson(bytes) {
  let value;
  try {
    value = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`Gitleaks JSON report is not valid JSON: ${error.message}`);
  }
  if (!Array.isArray(value))
    fail('Gitleaks JSON report must contain an array.');
  const findings = value.map((entry, index) => {
    const label = `Gitleaks JSON finding ${index + 1}`;
    requirePlainObject(entry, label);
    const identity = findingIdentity({
      commit: entry.Commit,
      endColumn: requirePositiveInteger(entry.EndColumn, `${label}.EndColumn`),
      endLine: requirePositiveInteger(entry.EndLine, `${label}.EndLine`),
      path: requireRelativeScanPath(entry.File, `${label}.File`),
      ruleId: requireTrimmedText(entry.RuleID, `${label}.RuleID`, 512),
      startColumn: requirePositiveInteger(entry.StartColumn, `${label}.StartColumn`),
      startLine: requirePositiveInteger(entry.StartLine, `${label}.StartLine`),
    });
    if (typeof identity.commit !== 'string' || !COMMIT_PATTERN.test(identity.commit))
      fail(`${label}.Commit must be a lowercase 40-character Git object ID.`);
    if (identity.endLine < identity.startLine
        || (identity.endLine === identity.startLine && identity.endColumn < identity.startColumn)) {
      fail(`${label} has an inverted source region.`);
    }
    return {
      accepted: true,
      commit: identity.commit,
      fingerprint: releaseScanFindingFingerprint(identity),
      path: identity.path,
      ruleId: identity.ruleId,
      scanner: 'gitleaks',
      severity: normalizeSeverity(entry.Severity, label),
      sourceIdentity: identity,
    };
  }).sort((left, right) => compareAscii(findingKey(left), findingKey(right)));
  const keys = findings.map(findingKey);
  if (new Set(keys).size !== keys.length)
    fail('Gitleaks JSON report contains a duplicate exact finding identity.');
  return findings;
}

function normalizeGitleaksSarif(bytes) {
  let value;
  try {
    value = JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    fail(`Gitleaks SARIF report is not valid JSON: ${error.message}`);
  }
  requirePlainObject(value, 'Gitleaks SARIF report');
  if (value.version !== '2.1.0' || !Array.isArray(value.runs) || value.runs.length === 0)
    fail('Gitleaks SARIF report must contain at least one SARIF 2.1.0 run.');
  const identities = [];
  value.runs.forEach((run, runIndex) => {
    const label = `Gitleaks SARIF run ${runIndex + 1}`;
    requirePlainObject(run, label);
    const driverName = run.tool?.driver?.name;
    if (typeof driverName !== 'string' || driverName.toLowerCase() !== 'gitleaks')
      fail(`${label} is not from Gitleaks.`);
    if (!Array.isArray(run.results))
      fail(`${label}.results must be an array.`);
    run.results.forEach((result, resultIndex) => {
      const resultLabel = `${label} result ${resultIndex + 1}`;
      requirePlainObject(result, resultLabel);
      if (!Array.isArray(result.locations) || result.locations.length !== 1)
        fail(`${resultLabel} must contain exactly one location.`);
      const physical = result.locations[0]?.physicalLocation;
      const region = physical?.region;
      const identity = findingIdentity({
        commit: result.partialFingerprints?.commitSha,
        endColumn: requirePositiveInteger(region?.endColumn, `${resultLabel}.endColumn`),
        endLine: requirePositiveInteger(region?.endLine, `${resultLabel}.endLine`),
        path: requireRelativeScanPath(physical?.artifactLocation?.uri, `${resultLabel}.uri`),
        ruleId: requireTrimmedText(result.ruleId, `${resultLabel}.ruleId`, 512),
        startColumn: requirePositiveInteger(region?.startColumn, `${resultLabel}.startColumn`),
        startLine: requirePositiveInteger(region?.startLine, `${resultLabel}.startLine`),
      });
      if (typeof identity.commit !== 'string' || !COMMIT_PATTERN.test(identity.commit))
        fail(`${resultLabel}.partialFingerprints.commitSha must be a lowercase Git object ID.`);
      identities.push(identity);
    });
  });
  identities.sort((left, right) => compareAscii(
    releaseScanFindingFingerprint(left),
    releaseScanFindingFingerprint(right),
  ));
  const fingerprints = identities.map(releaseScanFindingFingerprint);
  if (new Set(fingerprints).size !== fingerprints.length)
    fail('Gitleaks SARIF report contains a duplicate exact finding identity.');
  return identities;
}

function validateApprovalRegistry(bytes, contract, now) {
  const registry = parseCanonicalJson(bytes, 'release-scan exception registry');
  requireExactKeys(registry, ['exceptions', 'formatVersion'], 'release-scan exception registry');
  if (registry.formatVersion !== 1 || !Array.isArray(registry.exceptions))
    fail('release-scan exception registry must have formatVersion 1 and an exceptions array.');
  const fields = contract.policy?.allowlist?.fields;
  if (!Array.isArray(fields) || contract.policy.allowlist.maximumLifetimeDays !== 30
      || contract.policy.allowlist.wildcardSuppression !== 'PROHIBITED') {
    fail('release-scan exception policy drifted from the supported exact 30-day contract.');
  }
  const nowMilliseconds = now instanceof Date
    ? now.getTime() : typeof now === 'number' ? now : Date.parse(now);
  if (!Number.isFinite(nowMilliseconds))
    fail('release-scan exception evaluation time is invalid.');
  const approvals = registry.exceptions.map((approval, index) => {
    const label = `release-scan exception ${index + 1}`;
    requireExactKeys(approval, fields, label);
    for (const field of ['approvalReference', 'owner', 'rationale'])
      requireTrimmedText(approval[field], `${label}.${field}`);
    if (approval.scanner !== 'codeql' && approval.scanner !== 'gitleaks')
      fail(`${label}.scanner must be codeql or gitleaks; SpotBugs remains fail-closed.`);
    requireTrimmedText(approval.ruleId, `${label}.ruleId`, 512);
    requireRelativeScanPath(approval.path, `${label}.path`);
    if (!COMMIT_PATTERN.test(approval.commit))
      fail(`${label}.commit must be a lowercase 40-character Git object ID.`);
    if (!SHA256_PATTERN.test(approval.fingerprint))
      fail(`${label}.fingerprint must be lowercase SHA-256.`);
    for (const field of ['scanner', 'ruleId', 'commit', 'path', 'fingerprint']) {
      if (WILDCARD_PATTERN.test(approval[field]))
        fail(`${label}.${field} must not contain a wildcard.`);
    }
    const approvedAt = parseUtc(approval.approvedAt, `${label}.approvedAt`);
    const expiresAt = parseUtc(approval.expiresAt, `${label}.expiresAt`);
    if (expiresAt <= approvedAt
        || expiresAt - approvedAt > MAXIMUM_EXCEPTION_LIFETIME_MILLISECONDS) {
      fail(`${label} lifetime must be positive and no more than 30 days.`);
    }
    if (approvedAt > nowMilliseconds || expiresAt <= nowMilliseconds)
      fail(`${label} is not currently effective and unexpired.`);
    return approval;
  });
  const keys = approvals.map(findingKey);
  if (new Set(keys).size !== keys.length)
    fail('release-scan exception registry contains a duplicate exact exception.');
  if (keys.some((key, index) => index > 0 && compareAscii(keys[index - 1], key) >= 0))
    fail('release-scan exception registry must be in strict exact-match order.');
  return approvals;
}

function evaluateReleaseScanReports({
  approvalBytes,
  candidateCommit,
  codeqlBytes,
  contract,
  gitleaksJsonBytes,
  gitleaksSarifBytes,
  now,
}) {
  const gitleaksFindings = normalizeGitleaksJson(gitleaksJsonBytes);
  const sarifFingerprints = normalizeGitleaksSarif(gitleaksSarifBytes)
    .map(releaseScanFindingFingerprint)
    .sort(compareAscii);
  const jsonFingerprints = gitleaksFindings
    .map(({ fingerprint }) => fingerprint)
    .sort(compareAscii);
  if (sarifFingerprints.length !== jsonFingerprints.length
      || sarifFingerprints.some((fingerprint, index) => fingerprint !== jsonFingerprints[index])) {
    fail('Gitleaks SARIF and JSON reports do not describe the same exact findings.');
  }
  const findings = [
    ...normalizeCodeqlSarif(codeqlBytes, candidateCommit),
    ...gitleaksFindings,
  ].sort((left, right) => compareAscii(findingKey(left), findingKey(right)));
  const approvals = validateApprovalRegistry(approvalBytes, contract, now);
  const approvalByKey = new Map(approvals.map((approval) => [findingKey(approval), approval]));
  for (const finding of findings) {
    if (finding.severity === 'HIGH' || finding.severity === 'CRITICAL')
      fail(`${finding.scanner} ${finding.severity} finding cannot be excepted: ${finding.path}`);
    if (!approvalByKey.delete(findingKey(finding)))
      fail(`${finding.scanner} finding has no exact unexpired exception: ${finding.path}`);
  }
  if (approvalByKey.size !== 0)
    fail('release-scan exception registry contains an unmatched exception.');
  return {
    allowlist: approvals,
    findings: findings.map(({ sourceIdentity: ignored, ...finding }) => finding),
  };
}

export function prepareReleaseScanEvidence({
  approvalsPath,
  candidate,
  candidateRoot,
  contract,
  evidenceRoot,
  now = new Date(),
  provenanceRoot,
  rawReportsRoot,
}) {
  const absoluteCandidateRoot = absolutePath(candidateRoot, 'candidate root');
  const absoluteEvidenceRoot = absolutePath(evidenceRoot, 'release-scan evidence root');
  const absoluteProvenanceRoot = absolutePath(provenanceRoot, 'release-scan provenance root');
  const absoluteRawReportsRoot = absolutePath(rawReportsRoot, 'release-scan raw-report root');
  const absoluteApprovalsPath = absolutePath(approvalsPath, 'release-scan exception registry');
  const expectedApprovalsPath = resolve(absoluteCandidateRoot, APPROVALS_RELATIVE_PATH);
  if (absoluteApprovalsPath !== expectedApprovalsPath)
    fail(`release-scan exception registry must be the exact candidate path: ${expectedApprovalsPath}`);
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
  const decision = evaluateReleaseScanReports({
    approvalBytes: readRegularFile(absoluteApprovalsPath, 'release-scan exception registry'),
    candidateCommit: candidate.candidateCommit,
    codeqlBytes: readRegularFile(
      resolve(absoluteRawReportsRoot, '00-codeql-java.sarif'),
      'CodeQL SARIF report',
    ),
    contract,
    gitleaksJsonBytes: readRegularFile(
      resolve(absoluteRawReportsRoot, '03-gitleaks.json'),
      'Gitleaks JSON report',
    ),
    now,
    gitleaksSarifBytes: readRegularFile(
      resolve(absoluteRawReportsRoot, '02-gitleaks.sarif'),
      'Gitleaks SARIF report',
    ),
  });

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
    allowlist: decision.allowlist,
    candidate,
    findings: decision.findings,
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
  approvalsPath,
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
    approvalsPath,
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
    + '--approvals <absolute-path> --output <absolute-path>';
}

function parseArguments(args) {
  const expected = new Set([
    '--approvals',
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
    approvalsPath: values.get('--approvals'),
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
