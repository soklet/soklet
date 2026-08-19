#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  closeSync,
  constants as fsConstants,
  existsSync,
  fstatSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';

const COMMIT_PATTERN = /^[0-9a-f]{40}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const FINGERPRINT_PATTERN = /^(?:[0-9A-F]{40}|[0-9A-F]{64})$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOKEN_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
const DIRECTORY_EVIDENCE_ALGORITHM =
  "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows";

const EXPECTED_GATE_IDS = Object.freeze([
  'candidate-build',
  'core-jdk-21',
  'core-jdk-25',
  'isolated-install',
  'api-freeze',
  'candidate-javadocs',
  'static-analysis',
  'spotbugs',
  'schema-replay',
  'fuzz-replay',
  'fuzz-nightly-history',
  'soak-smoke',
  'soak-nightly-history',
  'release-soak',
  'localization-fleet',
  'operational-history',
  'release-scans',
  'mcp-benchmarks',
  'matrix-closure',
  'candidate-conformance',
  'candidate-localization',
  'barebones-app',
  'soklet-servlet-javax',
  'soklet-servlet-jakarta',
  'toystore-app',
  'soklet-otel',
  'soklet-website',
  'typescript-interop',
  'go-interop',
]);
const MANIFEST_GATE_KEYS = Object.freeze([
  'access',
  'artifactChecksum',
  'artifactIdentity',
  'commit',
  'defaultArtifactIdentity',
  'defaultArtifactSha256',
  'evidenceContract',
  'id',
  'kind',
  'reason',
  'repository',
  'status',
  'toolchain',
  'versionProperty',
]);

const SUREFIRE_MEDIA_TYPE = 'application/vnd.soklet.surefire-reports';

function evidenceRole(role, type, mediaType, fileName, binding = null) {
  return Object.freeze({ binding, fileName, mediaType, role, type });
}

const GATE_ARTIFACT_CONTRACTS = Object.freeze({
  'candidate-build': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('artifact-descriptor', 'FILE', 'application/json', 'candidate-artifacts.json', 'descriptor'),
      evidenceRole('build-log', 'FILE', 'text/plain', 'candidate-build.log'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
      evidenceRole('node-distribution', 'FILE', 'text/plain', 'release-validation-node-distribution.txt'),
      evidenceRole('maven-distribution', 'FILE', 'text/plain', 'release-validation-maven-distribution.txt'),
      evidenceRole('go-distribution', 'FILE', 'text/plain', 'release-validation-go-distribution.txt'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-java-distribution.txt', 'gateToolchainDistribution'),
    ]),
  }),
  'core-jdk-21': Object.freeze({
    toolchain: 'coreJdk21',
    roles: Object.freeze([
      evidenceRole('build-log', 'FILE', 'text/plain', 'core-jdk-21.log'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-core-jdk-21-distribution.txt', 'gateToolchainDistribution'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'core-jdk-25': Object.freeze({
    toolchain: 'toystoreJava',
    roles: Object.freeze([
      evidenceRole('build-log', 'FILE', 'text/plain', 'core-jdk-25.log'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-toystore-java-distribution.txt', 'gateToolchainDistribution'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'isolated-install': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('installed-pom', 'FILE', 'application/xml', 'soklet-3.6.0.pom', 'pom'),
      evidenceRole('installed-main-jar', 'FILE', 'application/java-archive', 'soklet-3.6.0.jar', 'mainJar'),
      evidenceRole('install-log', 'FILE', 'text/plain', 'isolated-install.log'),
    ]),
  }),
  'api-freeze': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('api-freeze-log', 'FILE', 'text/plain', 'api-freeze.log'),
      evidenceRole('japicmp-diff', 'FILE', 'application/xml', 'mcp-api-diff.xml'),
      evidenceRole('japicmp-incompatibilities', 'FILE', 'application/x-ndjson', 'mcp-api-diff.incompatibilities.jsonl'),
      evidenceRole('api-freeze-report', 'FILE', 'application/xml', 'mcp-api-freeze.xml'),
      evidenceRole('signatures', 'DIRECTORY', 'application/vnd.soklet.api-signatures', 'mcp-api-freezes'),
    ]),
  }),
  'candidate-javadocs': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('javadoc-log', 'FILE', 'text/plain', 'candidate-javadocs.log'),
      evidenceRole('javadoc-jar', 'FILE', 'application/java-archive', 'soklet-3.6.0-javadoc.jar', 'javadocJar'),
      evidenceRole('apidocs', 'DIRECTORY', 'text/html', 'apidocs'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'static-analysis': Object.freeze({
    toolchain: 'coreJdk21',
    roles: Object.freeze([
      evidenceRole('analysis-log', 'FILE', 'text/plain', 'static-analysis.log'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-core-jdk-21-distribution.txt', 'gateToolchainDistribution'),
    ]),
  }),
  spotbugs: Object.freeze({
    toolchain: 'coreJdk21',
    roles: Object.freeze([
      evidenceRole('spotbugs-log', 'FILE', 'text/plain', 'spotbugs.log'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-core-jdk-21-distribution.txt', 'gateToolchainDistribution'),
      evidenceRole('spotbugs-report', 'FILE', 'application/xml', 'spotbugsXml.xml'),
    ]),
  }),
  'schema-replay': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('replay-log', 'FILE', 'text/plain', 'schema-replay.log'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'fuzz-replay': Object.freeze({
    toolchain: 'toystoreJava',
    roles: Object.freeze([
      evidenceRole('replay-log', 'FILE', 'text/plain', 'fuzz-replay.log'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'fuzz-nightly-history': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('history', 'FILE', 'application/json', 'fuzz-nightly-history.json'),
    ]),
  }),
  'soak-smoke': Object.freeze({
    toolchain: 'toystoreJava',
    roles: Object.freeze([
      evidenceRole('soak-log', 'FILE', 'text/plain', 'soak-smoke.log'),
      evidenceRole('soak-report', 'FILE', 'text/markdown', 'soak-report.md'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'soak-nightly-history': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('history', 'FILE', 'application/json', 'soak-nightly-history.json'),
    ]),
  }),
  'release-soak': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('soak-report', 'FILE', 'text/markdown', 'soak-report.md'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
      evidenceRole('soak-log', 'FILE', 'text/plain', 'release-soak.log'),
    ]),
  }),
  'localization-fleet': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('fleet-log', 'FILE', 'text/plain', 'localization-fleet.log'),
      evidenceRole('surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'operational-history': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('history', 'FILE', 'application/json', 'operational-history.json'),
    ]),
  }),
  'release-scans': Object.freeze({
    toolchain: 'coreJdk21',
    roles: Object.freeze([
      evidenceRole('scan-summary', 'FILE', 'application/json', 'release-scans.json'),
      evidenceRole('scan-reports', 'DIRECTORY', 'application/vnd.soklet.scan-reports', 'release-scans'),
    ]),
  }),
  'mcp-benchmarks': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('benchmark-results', 'FILE', 'application/json', 'mcp-benchmarks.json'),
      evidenceRole('benchmark-log', 'FILE', 'text/plain', 'mcp-benchmarks.log'),
    ]),
  }),
  'matrix-closure': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('matrix-report', 'FILE', 'application/json', 'matrix-closure.json'),
    ]),
  }),
  'candidate-conformance': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('conformance-evidence', 'DIRECTORY', 'application/vnd.soklet.conformance-evidence', 'release'),
    ]),
  }),
  'candidate-localization': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('localization-log', 'FILE', 'text/plain', 'candidate-localization.log'),
    ]),
  }),
  'barebones-app': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('port-file', 'FILE', 'text/plain', 'barebones-loopback-port.txt'),
      evidenceRole('reservation-log', 'FILE', 'text/plain', 'barebones-port-reservation.log'),
      evidenceRole('runtime-log', 'FILE', 'text/plain', 'barebones-app.log'),
    ]),
  }),
  'soklet-servlet-javax': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('project-pom', 'FILE', 'application/xml', 'pom.xml'),
      evidenceRole('default-jar', 'FILE', 'application/java-archive', 'soklet-3.1.1.jar', 'gateDefaultArtifact'),
      evidenceRole('default-log', 'FILE', 'text/plain', 'soklet-servlet-javax-default.log'),
      evidenceRole('default-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'soklet-servlet-javax-default-surefire-reports'),
      evidenceRole('candidate-log', 'FILE', 'text/plain', 'soklet-servlet-javax-candidate.log'),
      evidenceRole('candidate-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'soklet-servlet-jakarta': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('project-pom', 'FILE', 'application/xml', 'pom.xml'),
      evidenceRole('default-jar', 'FILE', 'application/java-archive', 'soklet-3.1.1.jar', 'gateDefaultArtifact'),
      evidenceRole('default-log', 'FILE', 'text/plain', 'soklet-servlet-jakarta-default.log'),
      evidenceRole('default-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'soklet-servlet-jakarta-default-surefire-reports'),
      evidenceRole('candidate-log', 'FILE', 'text/plain', 'soklet-servlet-jakarta-candidate.log'),
      evidenceRole('candidate-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'toystore-app': Object.freeze({
    toolchain: 'toystoreJava',
    roles: Object.freeze([
      evidenceRole('project-pom', 'FILE', 'application/xml', 'pom.xml'),
      evidenceRole('candidate-log', 'FILE', 'text/plain', 'toystore-app-candidate.log'),
      evidenceRole('candidate-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
      evidenceRole('java-distribution', 'FILE', 'text/plain', 'release-validation-toystore-java-distribution.txt', 'gateToolchainDistribution'),
    ]),
  }),
  'soklet-otel': Object.freeze({
    toolchain: 'java',
    roles: Object.freeze([
      evidenceRole('project-pom', 'FILE', 'application/xml', 'pom.xml'),
      evidenceRole('candidate-log', 'FILE', 'text/plain', 'soklet-otel-candidate.log'),
      evidenceRole('candidate-surefire-reports', 'DIRECTORY', SUREFIRE_MEDIA_TYPE, 'surefire-reports'),
    ]),
  }),
  'soklet-website': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('build-log', 'FILE', 'text/plain', 'soklet-website.log'),
      evidenceRole('distribution', 'DIRECTORY', 'application/vnd.soklet.site-distribution', 'dist'),
    ]),
  }),
  'typescript-interop': Object.freeze({
    toolchain: 'nodePin',
    roles: Object.freeze([
      evidenceRole('interop-log', 'FILE', 'text/plain', 'typescript-interop.log'),
      evidenceRole('candidate-main-jar', 'FILE', 'application/java-archive', 'soklet-3.6.0.jar', 'mainJar'),
    ]),
  }),
  'go-interop': Object.freeze({
    toolchain: 'go',
    roles: Object.freeze([
      evidenceRole('interop-log', 'FILE', 'text/plain', 'go-interop.log'),
      evidenceRole('candidate-main-jar', 'FILE', 'application/java-archive', 'soklet-3.6.0.jar', 'mainJar'),
    ]),
  }),
});

function receiptIdentity(command, profile, expectation) {
  return Object.freeze({ command, expectation, profile });
}

const GATE_RECEIPT_IDENTITIES = Object.freeze({
  'candidate-build': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true clean verify',
    'release-candidate',
    'BUILD_SUCCESS_AND_CANDIDATE_ARTIFACTS_RECORDED',
  ),
  'core-jdk-21': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true clean test',
    'jdk-21',
    'TESTS_PASS_WITH_ZERO_ERRORS_AND_FAILURES',
  ),
  'core-jdk-25': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true clean test',
    'jdk-25',
    'TESTS_PASS_WITH_ZERO_ERRORS_AND_FAILURES',
  ),
  'isolated-install': receiptIdentity(
    'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    'isolated-repository',
    'INSTALLED_POM_AND_MAIN_JAR_MATCH_CANDIDATE_BYTES',
  ),
  'api-freeze': receiptIdentity(
    'scripts/verify-mcp-api-freezes.sh',
    'mcp-api-freeze',
    'REVIEWED_BIDIRECTIONAL_API_SET_AND_SIGNATURES_MATCH',
  ),
  'candidate-javadocs': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true -Dtest=McpPublicJavadocTests clean package javadoc:javadoc',
    'public-javadocs',
    'PUBLIC_JAVADOC_INVENTORY_JAR_AND_STANDALONE_DOCLINT_PASS',
  ),
  'static-analysis': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true -Pstatic-analysis clean compile',
    'static-analysis',
    'BUILD_SUCCESS',
  ),
  spotbugs: receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true -Pspotbugs -DskipTests clean compile spotbugs:check',
    'spotbugs',
    'ZERO_SPOTBUGS_FINDINGS',
  ),
  'schema-replay': receiptIdentity(
    'mvn -B -ntp -Dgpg.skip=true -Dtest=JsonSchemaTestSuitePinTests,McpToolSchemaProfile* test',
    'profile-1-replay',
    'SELECTED_SCHEMA_CORPUS_AND_PROFILE_TESTS_PASS',
  ),
  'fuzz-replay': receiptIdentity(
    'mvn -B -ntp -f fuzz/pom.xml clean test; node scripts/verify-json-corpus.mjs',
    'checked-in-corpus',
    'ALL_CHECKED_IN_FUZZ_CORPORA_PASS',
  ),
  'fuzz-nightly-history': receiptIdentity(
    'node scripts/verify-release-history.mjs fuzz-nightly',
    'nightly-history',
    'REQUIRED_NIGHTLY_FUZZ_WINDOW_PASSES',
  ),
  'soak-smoke': receiptIdentity(
    'SOKLET_SOAK_PROFILE=smoke mvn -B -ntp -f soak/pom.xml clean test',
    'smoke',
    'SOAK_REPORT_AND_SUREFIRE_PASS_WITHIN_PROFILE_LIMITS',
  ),
  'soak-nightly-history': receiptIdentity(
    'node scripts/verify-release-history.mjs soak-nightly',
    'nightly-history',
    'REQUIRED_NIGHTLY_SOAK_WINDOW_PASSES',
  ),
  'release-soak': receiptIdentity(
    'SOKLET_SOAK_PROFILE=release mvn -B -ntp -f soak/pom.xml clean test',
    'release',
    'SOAK_REPORT_AND_SUREFIRE_PASS_WITHIN_PROFILE_LIMITS',
  ),
  'localization-fleet': receiptIdentity(
    'mvn -B -ntp -Dtest=McpLocalizationFleetPublicRuntimeTests test',
    'two-listener-fleet',
    'FAILED_RELOAD_ROLLING_DRIFT_NODE_LOSS_RECONNECT_AND_CLEANUP_PASS',
  ),
  'operational-history': receiptIdentity(
    'node scripts/verify-release-history.mjs operational',
    'scheduled-history',
    'REQUIRED_OPERATIONAL_HISTORY_WINDOW_PASSES',
  ),
  'release-scans': receiptIdentity(
    'node scripts/verify-release-scans.mjs',
    'release',
    'REQUIRED_RELEASE_SCANS_PASS_WITH_ZERO_UNACCEPTED_FINDINGS',
  ),
  'mcp-benchmarks': receiptIdentity(
    'mvn -B -ntp -f benchmarks/pom.xml clean verify; node scripts/verify-release-benchmarks.mjs',
    'release',
    'JMH_JSON_351_COMPARISON_AND_SCHEMA_360_BASELINE_RECORDED_WITH_SIGNOFF',
  ),
  'matrix-closure': receiptIdentity(
    'node scripts/verify-release-matrix-closure.mjs',
    'release',
    'ZERO_UNRESOLVED_IN_SCOPE_MATRIX_ROWS',
  ),
  'candidate-conformance': receiptIdentity(
    'node conformance/official/run.mjs --phase 5 --mode release',
    'release',
    'ALL_39_CAPABILITY_SELECTED_SCENARIOS_PASS',
  ),
  'candidate-localization': receiptIdentity(
    'verification/localization/verify.sh',
    'generic-provider',
    'CANDIDATE_ARTIFACT_LOCALIZATION_PROVIDER_PASSES',
  ),
  'barebones-app': receiptIdentity(
    'javac --release 17; live loopback probes; clean shutdown',
    'candidate',
    'COMPILE_START_RESPOND_TERMINATE_AND_RELEASE_PORT',
  ),
  'soklet-servlet-javax': receiptIdentity(
    'mvn -B -ntp clean verify; mvn -B -ntp -Dsoklet.version=3.6.0 clean verify',
    'default-and-candidate',
    'DEFAULT_AND_CANDIDATE_LEGS_PASS_WITH_EXACT_ARTIFACTS',
  ),
  'soklet-servlet-jakarta': receiptIdentity(
    'mvn -B -ntp clean verify; mvn -B -ntp -Dsoklet.version=3.6.0 clean verify',
    'default-and-candidate',
    'DEFAULT_AND_CANDIDATE_LEGS_PASS_WITH_EXACT_ARTIFACTS',
  ),
  'toystore-app': receiptIdentity(
    'mvn -B -ntp -Dsoklet.version=3.6.0 clean verify',
    'candidate',
    'CANDIDATE_LEG_PASSES_WITH_EXACT_ARTIFACT',
  ),
  'soklet-otel': receiptIdentity(
    'mvn -B -ntp -Dsoklet.version=3.6.0 clean verify',
    'candidate',
    'CANDIDATE_LEG_PASSES_WITH_EXACT_ARTIFACT',
  ),
  'soklet-website': receiptIdentity(
    'npm ci --ignore-scripts; npm run lint; npm run ssg-build',
    'candidate-documentation',
    'CLEAN_INSTALL_LINT_AND_STATIC_BUILD_PASS',
  ),
  'typescript-interop': receiptIdentity(
    'verification/interoperability/typescript/verify.sh',
    'tools-list',
    'PINNED_SDK_TOOLS_LIST_FIXTURE_PASSES_AND_SHUTS_DOWN_CLEANLY',
  ),
  'go-interop': receiptIdentity(
    'verification/interoperability/go/verify.sh',
    'tools-list',
    'PINNED_SDK_TOOLS_LIST_FIXTURE_PASSES_AND_SHUTS_DOWN_CLEANLY',
  ),
});

export const GATE_EVIDENCE_CONTRACTS = Object.freeze(Object.fromEntries(
  EXPECTED_GATE_IDS.map((gateId) => [
    gateId,
    Object.freeze({
      ...GATE_ARTIFACT_CONTRACTS[gateId],
      ...GATE_RECEIPT_IDENTITIES[gateId],
      contractId: `soklet.release.${gateId}.v1`,
    }),
  ]),
));

const COORDINATES = Object.freeze({
  artifactId: 'soklet',
  groupId: 'com.soklet',
  packaging: 'jar',
  version: '3.6.0',
});

const MAVEN_DIRECTORY = 'com/soklet/soklet/3.6.0';
const BUNDLE_NAME = 'soklet-3.6.0-central-bundle.zip';
const PREPARATION_NAME = 'promotion-preparation.json';
const PUBLISHED_EVIDENCE_NAME = 'central-published-evidence.json';
const PROMOTION_HELPER_PATH = fileURLToPath(import.meta.url);
const PROMOTION_WRAPPER_PATH = join(dirname(PROMOTION_HELPER_PATH), 'promote-release-candidate.sh');

export const CENTRAL_UPLOAD_URL =
  'https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED';
export const CENTRAL_STATUS_BASE_URL =
  'https://central.sonatype.com/api/v1/publisher/status?id=';
export const CENTRAL_REPOSITORY_BASE_URL =
  'https://repo1.maven.org/maven2/com/soklet/soklet/3.6.0/';

const ARTIFACTS = Object.freeze({
  pom: Object.freeze({
    option: 'pom',
    evidenceName: 'pom.xml',
    bundleName: 'soklet-3.6.0.pom',
    kind: 'pom',
  }),
  mainJar: Object.freeze({
    option: 'main-jar',
    evidenceName: 'soklet-3.6.0.jar',
    bundleName: 'soklet-3.6.0.jar',
    kind: 'jar',
  }),
  sourcesJar: Object.freeze({
    option: 'sources-jar',
    evidenceName: 'soklet-3.6.0-sources.jar',
    bundleName: 'soklet-3.6.0-sources.jar',
    kind: 'jar',
  }),
  javadocJar: Object.freeze({
    option: 'javadoc-jar',
    evidenceName: 'soklet-3.6.0-javadoc.jar',
    bundleName: 'soklet-3.6.0-javadoc.jar',
    kind: 'jar',
  }),
});

const CHECKSUM_ALGORITHMS = Object.freeze([
  Object.freeze({ extension: 'md5', nodeName: 'md5' }),
  Object.freeze({ extension: 'sha1', nodeName: 'sha1' }),
  Object.freeze({ extension: 'sha256', nodeName: 'sha256' }),
  Object.freeze({ extension: 'sha512', nodeName: 'sha512' }),
]);

const ZIP_FLAG_UTF8 = 0x0800;
const ZIP_METHOD_STORED = 0;
const ZIP_DOS_TIME = 0;
const ZIP_DOS_DATE = 0x21;
const ZIP_VERSION = 20;
const ZIP_VERSION_MADE_BY = (3 << 8) | ZIP_VERSION;
const ZIP_EXTERNAL_ATTRIBUTES = (0o100644 << 16) >>> 0;
const MAX_RESPONSE_BYTES = 64 * 1024;
const MAX_BUNDLE_BYTES = 1024 * 1024 * 1024;
const CENTRAL_ARTIFACT_URLS = new Set(
  Object.values(ARTIFACTS).map(
    (specification) => `${CENTRAL_REPOSITORY_BASE_URL}${specification.bundleName}`,
  ),
);

function fail(message) {
  throw new Error(message);
}

function requireExactKeys(value, keys, description) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${description} must be an object`);

  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${description} keys must be exactly: ${expected.join(', ')}`);
}

function requireTrimmedString(value, description) {
  if (typeof value !== 'string' || value === '' || value.trim() !== value)
    fail(`${description} must be a non-empty, trimmed string`);
}

function requirePositiveSafeInteger(value, description) {
  if (!Number.isSafeInteger(value) || value <= 0)
    fail(`${description} must be a positive safe integer`);
}

function digest(algorithm, bytes) {
  return createHash(algorithm).update(bytes).digest('hex');
}

function sha256(bytes) {
  return digest('sha256', bytes);
}

function canonicalize(value) {
  if (Array.isArray(value))
    return value.map(canonicalize);

  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]),
    );
  }

  return value;
}

export function canonicalJsonBytes(value) {
  return Buffer.from(`${JSON.stringify(canonicalize(value), null, 2)}\n`, 'utf8');
}

function readRegularFile(path, description) {
  const absolutePath = resolve(path);
  let descriptor;

  try {
    descriptor = openSync(absolutePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
  } catch {
    fail(`${description} must be a readable, regular, nonsymlink file: ${absolutePath}`);
  }

  try {
    const stats = fstatSync(descriptor);
    if (!stats.isFile())
      fail(`${description} must be a regular file: ${absolutePath}`);

    return {
      absolutePath,
      bytes: readFileSync(descriptor),
      identity: `${stats.dev}:${stats.ino}`,
      mode: stats.mode,
      uid: stats.uid,
    };
  } finally {
    closeSync(descriptor);
  }
}

function parseCanonicalJson(path, expectedSha256, description, requireCanonical = true) {
  if (!SHA256_PATTERN.test(expectedSha256))
    fail(`${description} SHA-256 must contain exactly 64 lowercase hexadecimal characters`);

  const file = readRegularFile(path, description);
  if (sha256(file.bytes) !== expectedSha256)
    fail(`${description} does not match the independently supplied SHA-256`);

  const text = file.bytes.toString('utf8');
  if (Buffer.from(text, 'utf8').compare(file.bytes) !== 0)
    fail(`${description} must be UTF-8`);

  let value;
  try {
    value = JSON.parse(text);
  } catch {
    fail(`${description} must be valid JSON`);
  }

  if (requireCanonical && canonicalJsonBytes(value).compare(file.bytes) !== 0)
    fail(`${description} must use the exact canonical JSON encoding`);

  return { ...file, value };
}

function writeNewFile(path, bytes) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes, { flag: 'wx' });
}

function writeCanonicalJson(path, value) {
  writeNewFile(path, canonicalJsonBytes(value));
}

function syncDirectory(path) {
  const descriptor = openSync(path, fsConstants.O_RDONLY);
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function reserveEvidenceFile(path, description) {
  const absolutePath = resolve(path);
  const parent = dirname(absolutePath);
  mkdirSync(parent, { recursive: true });
  let descriptor;
  let created = false;
  try {
    descriptor = openSync(
      absolutePath,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600,
    );
    created = true;
    syncDirectory(parent);
  } catch {
    if (descriptor !== undefined)
      closeSync(descriptor);
    if (created)
      rmSync(absolutePath, { force: true });
    fail(`Refusing to overwrite or use unwritable ${description}: ${absolutePath}`);
  }
  return { absolutePath, committed: false, descriptor };
}

function commitReservedEvidence(reservation, value) {
  if (reservation.descriptor === undefined)
    fail('Promotion evidence reservation is not writable');
  closeSync(reservation.descriptor);
  reservation.descriptor = undefined;
  const parent = dirname(reservation.absolutePath);
  const temporaryDirectory = mkdtempSync(
    join(parent, `.${basename(reservation.absolutePath)}.incomplete-`),
  );
  try {
    const temporaryPath = join(temporaryDirectory, 'evidence.json');
    const descriptor = openSync(
      temporaryPath,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600,
    );
    try {
      writeFileSync(descriptor, canonicalJsonBytes(value));
      fsyncSync(descriptor);
    } finally {
      closeSync(descriptor);
    }
    renameSync(temporaryPath, reservation.absolutePath);
    reservation.committed = true;
    syncDirectory(parent);
  } finally {
    rmSync(temporaryDirectory, { force: true, recursive: true });
  }
}

function abandonEvidenceReservation(reservation) {
  if (reservation === undefined)
    return;
  if (reservation.descriptor !== undefined) {
    closeSync(reservation.descriptor);
    reservation.descriptor = undefined;
  }
  rmSync(reservation.absolutePath, { force: true });
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function requireCoordinates(value, description) {
  requireExactKeys(value, ['artifactId', 'groupId', 'packaging', 'version'], description);
  if (JSON.stringify(value) !== JSON.stringify(COORDINATES))
    fail(`${description} must be exactly com.soklet:soklet:3.6.0 with JAR packaging`);
}

function requireEvidenceItem(item, expectedFileName, description) {
  requireExactKeys(item, ['bytes', 'fileName', 'sha256', 'type'], description);
  requirePositiveSafeInteger(item.bytes, `${description} byte count`);
  if (item.fileName !== expectedFileName)
    fail(`${description} file name must be exactly ${expectedFileName}`);
  if (!SHA256_PATTERN.test(item.sha256))
    fail(`${description} SHA-256 must contain exactly 64 lowercase hexadecimal characters`);
  if (item.type !== 'FILE')
    fail(`${description} must have type FILE`);
}

function requireGateArtifact(item, description) {
  if (item?.type === 'FILE') {
    requireExactKeys(item, ['bytes', 'fileName', 'sha256', 'type'], description);
    requirePositiveSafeInteger(item.bytes, `${description} byte count`);
  } else if (item?.type === 'DIRECTORY') {
    requireExactKeys(
      item,
      ['algorithm', 'fileCount', 'fileName', 'sha256', 'type'],
      description,
    );
    requirePositiveSafeInteger(item.fileCount, `${description} file count`);
    if (item.algorithm !== DIRECTORY_EVIDENCE_ALGORITHM)
      fail(`${description} algorithm is invalid`);
  } else {
    fail(`${description} must describe a FILE or DIRECTORY`);
  }

  requireTrimmedString(item.fileName, `${description} file name`);
  if (basename(item.fileName) !== item.fileName || !SHA256_PATTERN.test(item.sha256))
    fail(`${description} identity is invalid`);
}

function requireGateEvidenceItem(item, description) {
  requireExactKeys(item, ['artifact', 'mediaType', 'role'], description);
  requireTrimmedString(item.role, `${description} role`);
  if (!/^[a-z][a-z0-9-]*$/.test(item.role))
    fail(`${description} role is invalid`);
  requireTrimmedString(item.mediaType, `${description} media type`);
  requireGateArtifact(item.artifact, `${description} artifact`);
}

function requireGateReceipt(receipt, gate, mainJar, gateId, candidateCommit, workflow, contract) {
  requireExactKeys(
    receipt,
    [
      'candidateCommit',
      'candidateSha256',
      'command',
      'contractId',
      'expectation',
      'formatVersion',
      'gateId',
      'profile',
      'result',
      'toolchain',
      'workflow',
    ],
    `${gateId} typed receipt`,
  );
  const expectedContractId = `soklet.release.${gateId}.v1`;
  if (receipt.formatVersion !== 1
      || receipt.candidateCommit !== candidateCommit
      || receipt.candidateSha256 !== mainJar.sha256
      || receipt.contractId !== expectedContractId
      || receipt.contractId !== gate.evidenceContract
      || receipt.gateId !== gateId
      || receipt.result !== 'PASS'
      || receipt.toolchain !== gate.toolchain
      || receipt.toolchain !== contract.toolchain
      || receipt.command !== contract.command
      || receipt.expectation !== contract.expectation
      || receipt.profile !== contract.profile
      || JSON.stringify(canonicalize(receipt.workflow))
        !== JSON.stringify(canonicalize(workflow))) {
    fail(`${gateId} typed receipt does not match the candidate, gate contract, and toolchain`);
  }
}

function requireWorkflowIdentity(workflow, candidateCommit, description) {
  requireExactKeys(
    workflow,
    ['job', 'repository', 'runAttempt', 'runId', 'serverUrl', 'sha'],
    description,
  );
  for (const [name, entry] of Object.entries(workflow))
    requireTrimmedString(entry, `${description} ${name}`);
  if (workflow.sha !== candidateCommit
      || workflow.job !== 'validate'
      || workflow.repository !== 'soklet/soklet'
      || workflow.serverUrl !== 'https://github.com'
      || !/^[1-9][0-9]*$/.test(workflow.runAttempt)
      || !/^[1-9][0-9]*$/.test(workflow.runId)) {
    fail(`${description} is not the reviewed Soklet release job for this candidate`);
  }
}

function requireGateDefaultArtifact(item, gate, expected, gateId) {
  const identity = /^com\.soklet:soklet:([0-9]+\.[0-9]+\.[0-9]+)$/.exec(
    gate.defaultArtifactIdentity ?? '',
  );
  if (identity === null
      || !SHA256_PATTERN.test(gate.defaultArtifactSha256 ?? '')
      || expected.fileName !== `soklet-${identity[1]}.jar`
      || item.artifact.type !== 'FILE'
      || item.artifact.fileName !== expected.fileName
      || item.artifact.sha256 !== gate.defaultArtifactSha256) {
    fail(`Gate ${gateId} default JAR evidence does not match its exact identity and SHA-256`);
  }
}

function canonicalToolchainDistributionBytes(toolchain, description) {
  requireExactKeys(
    toolchain,
    [
      'archive',
      'archiveSha256',
      'distribution',
      'distributionUrl',
      'runtimeVersion',
      'vendorVersion',
      'version',
    ],
    description,
  );
  for (const [field, value] of Object.entries(toolchain))
    requireTrimmedString(value, `${description} ${field}`);
  if (!SHA256_PATTERN.test(toolchain.archiveSha256))
    fail(`${description} archive SHA-256 is invalid`);
  const version = /^([0-9]+)\.0\.([0-9]+)(?:\.([0-9]+))?$/.exec(toolchain.version);
  const vendor = /^Corretto-([0-9]+)\.0\.([0-9]+)\.([0-9]+)\.([0-9]+)$/
    .exec(toolchain.vendorVersion);
  if (toolchain.distribution !== 'corretto'
      || version === null
      || vendor === null
      || version[1] !== vendor[1]
      || version[2] !== vendor[2]
      || (version[3] !== undefined
        && (version[1] !== '21' || version[3] !== vendor[4]))) {
    fail(`${description} is not an exact supported Corretto identity`);
  }
  const distributionVersion = toolchain.vendorVersion.slice('Corretto-'.length);
  const expectedRuntimeVersion = `${toolchain.version}+${vendor[3]}-LTS`;
  const expectedArchive = `amazon-corretto-${distributionVersion}-linux-x64.tar.gz`;
  const expectedUrl = `https://corretto.aws/downloads/resources/${distributionVersion}/${expectedArchive}`;
  if (toolchain.runtimeVersion !== expectedRuntimeVersion
      || toolchain.archive !== expectedArchive
      || toolchain.distributionUrl !== expectedUrl) {
    fail(`${description} fields do not match its exact Corretto distribution`);
  }
  return Buffer.from(
    `distribution=${toolchain.distribution}\n`
      + `version=${toolchain.version}\n`
      + `runtimeVersion=${toolchain.runtimeVersion}\n`
      + `vendorVersion=${toolchain.vendorVersion}\n`
      + `url=${toolchain.distributionUrl}\n`
      + `archive=${toolchain.archive}\n`
      + `archiveSha256=${toolchain.archiveSha256}\n`,
    'utf8',
  );
}

function requireGateToolchainDistribution(item, gate, expected, toolchains, gateId) {
  const expectedBytes = canonicalToolchainDistributionBytes(
    toolchains[gate.toolchain],
    `Reviewed release manifest ${gateId} toolchain`,
  );
  if (item.artifact.type !== 'FILE'
      || item.artifact.fileName !== expected.fileName
      || item.artifact.bytes !== expectedBytes.length
      || item.artifact.sha256 !== sha256(expectedBytes)) {
    fail(`Gate ${gateId} Java distribution evidence does not match its exact manifest toolchain`);
  }
}

function requireGateEvidenceContract(gateEvidence, gateId, artifacts) {
  const contract = GATE_EVIDENCE_CONTRACTS[gateId];
  if (contract === undefined)
    fail(`Promotion has no typed-evidence contract for gate ${gateId}`);
  if (gateEvidence.gate.evidenceContract !== `soklet.release.${gateId}.v1`
      || gateEvidence.gate.toolchain !== contract.toolchain) {
    fail(`Gate ${gateId} does not identify its exact typed-evidence contract and toolchain`);
  }
  if (gateEvidence.evidence.length !== contract.roles.length)
    fail(`Gate ${gateId} does not contain its exact ordered evidence roles`);

  for (const [index, expected] of contract.roles.entries()) {
    const item = gateEvidence.evidence[index];
    if (item.role !== expected.role
        || item.mediaType !== expected.mediaType
        || item.artifact.type !== expected.type
        || item.artifact.fileName !== expected.fileName) {
      fail(`Gate ${gateId} evidence item ${index} does not match role ${expected.role}`);
    }
    if (expected.binding !== null
        && expected.binding !== 'gateDefaultArtifact'
        && expected.binding !== 'gateToolchainDistribution') {
      const artifact = artifacts[expected.binding];
      if (item.artifact.type !== 'FILE'
          || item.artifact.bytes !== artifact.bytes
          || item.artifact.sha256 !== artifact.sha256) {
        fail(`Gate ${gateId} evidence role ${expected.role} does not match candidate ${expected.binding}`);
      }
    } else if (expected.binding === 'gateDefaultArtifact') {
      requireGateDefaultArtifact(item, gateEvidence.gate, expected, gateId);
    }
  }

  return contract;
}

function requireInteroperabilityReceipt(receipt, gate, mainJar, gateId) {
  requireExactKeys(
    receipt,
    [
      'candidateSha256',
      'client',
      'fixtureScenario',
      'fixtureShutdown',
      'formatVersion',
      'protocolVersion',
      'sdkArtifactChecksum',
      'sdkArtifactIdentity',
      'sdkCommit',
      'tool',
    ],
    `${gateId} interoperability receipt`,
  );
  const expectedClient = gateId === 'typescript-interop' ? 'typescript' : 'go';
  if (receipt.candidateSha256 !== mainJar.sha256
      || receipt.client !== expectedClient
      || receipt.fixtureScenario !== 'tools-list'
      || receipt.fixtureShutdown !== 'CLEAN'
      || receipt.formatVersion !== 1
      || receipt.protocolVersion !== '2026-07-28'
      || receipt.sdkArtifactChecksum !== gate.artifactChecksum
      || receipt.sdkArtifactIdentity !== gate.artifactIdentity
      || receipt.sdkCommit !== gate.commit
      || receipt.tool !== 'test_simple_text') {
    fail(`${gateId} interoperability receipt does not match the candidate and SDK pin`);
  }
}

export function validateCompletedReleaseEvidence(value, candidateCommit) {
  if (!COMMIT_PATTERN.test(candidateCommit))
    fail('Candidate commit must contain exactly 40 lowercase hexadecimal characters');

  requireExactKeys(
    value,
    [
      'artifacts',
      'candidateCommit',
      'coordinates',
      'formatVersion',
      'gates',
      'releaseConfigurationSha256',
      'toolchains',
      'workflow',
    ],
    'release-validation evidence',
  );

  if (value.formatVersion !== 2 || value.candidateCommit !== candidateCommit)
    fail('Release-validation evidence does not identify the supplied candidate commit');
  if (!SHA256_PATTERN.test(value.releaseConfigurationSha256))
    fail('Release-validation evidence must identify its release configuration SHA-256');
  requireCoordinates(value.coordinates, 'release-validation coordinates');

  requireExactKeys(
    value.artifacts,
    ['javadocJar', 'mainJar', 'pom', 'sourcesJar'],
    'release-validation artifacts',
  );
  for (const [key, specification] of Object.entries(ARTIFACTS))
    requireEvidenceItem(value.artifacts[key], specification.evidenceName, `release-validation ${key}`);

  requireWorkflowIdentity(value.workflow, candidateCommit, 'release-validation workflow');

  const candidateDescriptorBytes = canonicalJsonBytes({
    artifacts: value.artifacts,
    candidateCommit,
    coordinates: value.coordinates,
    formatVersion: 1,
  });
  const candidateBindings = {
    ...value.artifacts,
    descriptor: {
      bytes: candidateDescriptorBytes.length,
      sha256: sha256(candidateDescriptorBytes),
    },
  };

  if (!Array.isArray(value.gates)
      || JSON.stringify(value.gates.map((gate) => gate?.gate?.id))
        !== JSON.stringify(EXPECTED_GATE_IDS)) {
    fail(`Release-validation gates must be the exact ordered set: ${EXPECTED_GATE_IDS.join(', ')}`);
  }

  for (const [index, gate] of value.gates.entries()) {
    const gateId = EXPECTED_GATE_IDS[index];
    requireExactKeys(
      gate,
      [
        'candidateCommit',
        'evidence',
        'formatVersion',
        'gate',
        'interoperability',
        'receipt',
        'status',
      ],
      `${gateId} gate evidence`,
    );
    requireExactKeys(
      gate.gate,
      [
        'artifactChecksum',
        'artifactIdentity',
        'commit',
        'defaultArtifactIdentity',
        'defaultArtifactSha256',
        'evidenceContract',
        'id',
        'repository',
        'toolchain',
      ],
      `${gateId} gate pin`,
    );
    if (gate.formatVersion !== 2 || gate.candidateCommit !== candidateCommit
        || gate.status !== 'PASS' || gate.gate.id !== gateId
        || !Array.isArray(gate.evidence) || gate.evidence.length === 0) {
      fail(`Gate ${gateId} is not complete PASS evidence for this candidate`);
    }
    const roles = new Set();
    gate.evidence.forEach((item, evidenceIndex) => {
      requireGateEvidenceItem(item, `${gateId} evidence item ${evidenceIndex}`);
      if (roles.has(item.role))
        fail(`Gate ${gateId} repeats evidence role ${item.role}`);
      roles.add(item.role);
    });
    const contract = requireGateEvidenceContract(gate, gateId, candidateBindings);
    requireGateReceipt(
      gate.receipt,
      gate.gate,
      value.artifacts.mainJar,
      gateId,
      candidateCommit,
      value.workflow,
      contract,
    );

    const isInteroperability = gateId === 'typescript-interop' || gateId === 'go-interop';
    if ((gate.interoperability !== null) !== isInteroperability)
      fail(`Gate ${gateId} has invalid interoperability evidence presence`);
    if (isInteroperability) {
      requireInteroperabilityReceipt(
        gate.interoperability,
        gate.gate,
        value.artifacts.mainJar,
        gateId,
      );
      const candidateItems = gate.evidence.filter((item) => item.role === 'candidate-main-jar'
        && item.artifact.type === 'FILE'
        && item.artifact.bytes === value.artifacts.mainJar.bytes
        && item.artifact.fileName === value.artifacts.mainJar.fileName
        && item.artifact.sha256 === value.artifacts.mainJar.sha256);
      if (gate.evidence.length !== 2 || candidateItems.length !== 1)
        fail(`${gateId} evidence must contain its log and the exact candidate main JAR`);
    }
  }

  requireExactKeys(
    value.toolchains,
    ['coreJdk21', 'git', 'go', 'java', 'maven', 'node', 'npm', 'toystoreJava'],
    'release-validation toolchains',
  );
  for (const [name, version] of Object.entries(value.toolchains))
    requireTrimmedString(version, `release-validation ${name} toolchain`);

  return value;
}

function validateReviewedReleaseManifest(value, manifestSha256, evidence) {
  requireExactKeys(
    value,
    ['candidate', 'formatVersion', 'gates', 'promotion', 'toolchains'],
    'reviewed release manifest',
  );
  if (value.formatVersion !== 2)
    fail('Reviewed release manifest formatVersion must be 2');
  requireCoordinates(value.candidate, 'reviewed release manifest candidate');
  if (manifestSha256 !== evidence.releaseConfigurationSha256)
    fail('Reviewed release manifest SHA-256 does not match release-validation evidence');
  if (value.toolchains === null || typeof value.toolchains !== 'object'
      || Array.isArray(value.toolchains) || Object.keys(value.toolchains).length === 0) {
    fail('Reviewed release manifest must retain its toolchain pins');
  }
  if (!Array.isArray(value.gates)
      || JSON.stringify(value.gates.map((gate) => gate?.id)) !== JSON.stringify(EXPECTED_GATE_IDS)) {
    fail(`Reviewed release manifest gates must be exactly: ${EXPECTED_GATE_IDS.join(', ')}`);
  }

  for (const [index, gate] of value.gates.entries()) {
    const gateId = EXPECTED_GATE_IDS[index];
    requireExactKeys(gate, MANIFEST_GATE_KEYS, `reviewed release manifest ${gateId} gate`);
    if (gate.status !== 'READY')
      fail(`Reviewed release manifest gate ${gateId} is not READY`);
    const evidencePin = evidence.gates[index].gate;
    if (gate.artifactChecksum !== evidencePin.artifactChecksum
        || gate.artifactIdentity !== evidencePin.artifactIdentity
        || gate.commit !== evidencePin.commit
        || gate.defaultArtifactIdentity !== evidencePin.defaultArtifactIdentity
        || gate.defaultArtifactSha256 !== evidencePin.defaultArtifactSha256
        || gate.evidenceContract !== evidencePin.evidenceContract
        || gate.repository !== evidencePin.repository) {
      fail(`Reviewed release manifest gate ${gateId} does not match release-validation evidence`);
    }
    if (gate.toolchain !== evidencePin.toolchain)
      fail(`Reviewed release manifest gate ${gateId} toolchain does not match release-validation evidence`);
    const contract = GATE_EVIDENCE_CONTRACTS[gateId];
    for (const [roleIndex, specification] of contract.roles.entries()) {
      if (specification.binding === 'gateToolchainDistribution') {
        requireGateToolchainDistribution(
          evidence.gates[index].evidence[roleIndex],
          gate,
          specification,
          value.toolchains,
          gateId,
        );
      }
    }
  }

  return value;
}

function verifyCandidatePom(bytes) {
  const text = bytes.toString('utf8');
  if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
    fail('Candidate POM must be UTF-8');

  const coordinates = /<project\b[^>]*>\s*<modelVersion>\s*4\.0\.0\s*<\/modelVersion>\s*<groupId>\s*com\.soklet\s*<\/groupId>\s*<artifactId>\s*soklet\s*<\/artifactId>\s*<version>\s*3\.6\.0\s*<\/version>\s*<packaging>\s*jar\s*<\/packaging>/s;
  if (!coordinates.test(text))
    fail('Candidate POM must declare direct com.soklet:soklet:3.6.0 JAR coordinates');
}

function verifyJar(bytes, description) {
  if (bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b)
    fail(`${description} is not a JAR/ZIP file`);
}

function loadCandidateArtifacts(paths, evidence) {
  const seenIdentities = new Set();
  const artifacts = {};

  for (const [key, specification] of Object.entries(ARTIFACTS)) {
    const file = readRegularFile(paths[key], `candidate ${key}`);
    if (basename(file.absolutePath) !== evidence.artifacts[key].fileName)
      fail(`Candidate ${key} path must retain evidence file name ${evidence.artifacts[key].fileName}`);
    if (seenIdentities.has(file.identity))
      fail('The four candidate artifact paths must identify four distinct files');
    seenIdentities.add(file.identity);

    if (file.bytes.length !== evidence.artifacts[key].bytes
        || sha256(file.bytes) !== evidence.artifacts[key].sha256) {
      fail(`Candidate ${key} bytes do not match release-validation evidence`);
    }

    if (specification.kind === 'pom')
      verifyCandidatePom(file.bytes);
    else
      verifyJar(file.bytes, `Candidate ${key}`);

    artifacts[key] = Object.freeze({ ...file, specification });
  }

  return Object.freeze(artifacts);
}

function validatePromotionTool(promotion) {
  requireExactKeys(promotion, ['helper', 'wrapper'], 'reviewed promotion tools');
  const expected = {
    helper: 'scripts/release-promotion.mjs',
    wrapper: 'scripts/promote-release-candidate.sh',
  };
  for (const [name, expectedPath] of Object.entries(expected)) {
    requireExactKeys(promotion[name], ['path', 'sha256'], `reviewed promotion ${name}`);
    if (promotion[name].path !== expectedPath
        || !SHA256_PATTERN.test(promotion[name].sha256)) {
      fail(`Reviewed promotion ${name} pin is invalid`);
    }
  }
  const helper = readRegularFile(PROMOTION_HELPER_PATH, 'promotion helper');
  const wrapper = readRegularFile(PROMOTION_WRAPPER_PATH, 'promotion wrapper');
  if (sha256(helper.bytes) !== promotion.helper.sha256
      || sha256(wrapper.bytes) !== promotion.wrapper.sha256) {
    fail('Executed promotion helper or wrapper does not match its independently reviewed SHA-256');
  }
  return Object.freeze({
    helper: Object.freeze({
      bytes: helper.bytes.length,
      fileName: basename(helper.absolutePath),
      sha256: promotion.helper.sha256,
    }),
    wrapper: Object.freeze({
      bytes: wrapper.bytes.length,
      fileName: basename(wrapper.absolutePath),
      sha256: promotion.wrapper.sha256,
    }),
  });
}

function validateSignerPath(path) {
  if (!isAbsolute(path))
    fail('The GPG executable path must be absolute');

  const absolutePath = resolve(path);
  let stats;
  try {
    stats = lstatSync(absolutePath);
  } catch {
    fail(`Missing GPG executable: ${absolutePath}`);
  }
  if (!stats.isFile() || stats.isSymbolicLink() || (stats.mode & 0o111) === 0)
    fail(`GPG executable must be an executable, regular, nonsymlink file: ${absolutePath}`);
  return realpathSync(absolutePath);
}

function runSigner(executable, args, operation) {
  const result = spawnSync(executable, args, {
    encoding: 'utf8',
    env: process.env,
    maxBuffer: 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 120_000,
    killSignal: 'SIGKILL',
  });
  if (result.error !== undefined || result.status !== 0)
    fail(`GPG ${operation} failed`);
  return result;
}

function requireArmoredSignature(bytes, description) {
  const text = bytes.toString('ascii');
  if (Buffer.from(text, 'ascii').compare(bytes) !== 0
      || !text.startsWith('-----BEGIN PGP SIGNATURE-----\n')
      || !text.endsWith('-----END PGP SIGNATURE-----\n')) {
    fail(`${description} is not an ASCII-armored detached signature`);
  }
}

function signAndVerify(executable, fingerprint, artifactPath, signaturePath, expectedArtifactSha256) {
  runSigner(
    executable,
    [
      '--no-options',
      '--batch',
      '--armor',
      '--detach-sign',
      '--local-user',
      `${fingerprint}!`,
      '--output',
      signaturePath,
      artifactPath,
    ],
    'detached-signature creation',
  );

  const artifact = readRegularFile(artifactPath, 'staged candidate artifact after signing');
  if (sha256(artifact.bytes) !== expectedArtifactSha256)
    fail('GPG modified a staged candidate artifact');

  const signature = readRegularFile(signaturePath, 'detached armored signature');
  requireArmoredSignature(signature.bytes, 'GPG output');

  const verification = runSigner(
    executable,
    [
      '--no-options',
      '--batch',
      '--status-fd',
      '1',
      '--verify',
      signaturePath,
      artifactPath,
    ],
    'detached-signature verification',
  );
  const match = /^\[GNUPG:\] VALIDSIG ([0-9A-F]{40}|[0-9A-F]{64})\b/m.exec(
    verification.stdout.toUpperCase(),
  );
  if (match === null || match[1] !== fingerprint)
    fail('Detached signature was not made by the exact supplied full fingerprint');

  return signature.bytes;
}

let crcTable;
function crc32(bytes) {
  if (crcTable === undefined) {
    crcTable = new Uint32Array(256);
    for (let value = 0; value < 256; ++value) {
      let remainder = value;
      for (let bit = 0; bit < 8; ++bit)
        remainder = (remainder & 1) === 1 ? (remainder >>> 1) ^ 0xedb88320 : remainder >>> 1;
      crcTable[value] = remainder >>> 0;
    }
  }

  let value = 0xffffffff;
  for (const byte of bytes)
    value = crcTable[(value ^ byte) & 0xff] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}

function requireSafeZipPath(path) {
  if (!/^[A-Za-z0-9._/-]+$/.test(path) || path.startsWith('/') || path.endsWith('/')
      || path.split('/').some((component) => component === '' || component === '.' || component === '..')) {
    fail(`Unsafe bundle entry path: ${path}`);
  }
}

export function createDeterministicZip(entries) {
  if (!Array.isArray(entries) || entries.length === 0 || entries.length > 0xffff)
    fail('ZIP entries must be a non-empty array within classic ZIP limits');

  const sorted = [...entries].sort((left, right) => compareAscii(left.path, right.path));
  const seen = new Set();
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const entry of sorted) {
    requireSafeZipPath(entry.path);
    if (seen.has(entry.path))
      fail(`Duplicate bundle entry path: ${entry.path}`);
    seen.add(entry.path);
    if (!Buffer.isBuffer(entry.bytes))
      fail(`Bundle entry ${entry.path} must be a Buffer`);
    if (entry.bytes.length > 0xffffffff)
      fail(`Bundle entry ${entry.path} exceeds classic ZIP limits`);

    const name = Buffer.from(entry.path, 'utf8');
    const checksum = crc32(entry.bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(ZIP_VERSION, 4);
    local.writeUInt16LE(ZIP_FLAG_UTF8, 6);
    local.writeUInt16LE(ZIP_METHOD_STORED, 8);
    local.writeUInt16LE(ZIP_DOS_TIME, 10);
    local.writeUInt16LE(ZIP_DOS_DATE, 12);
    local.writeUInt32LE(checksum, 14);
    local.writeUInt32LE(entry.bytes.length, 18);
    local.writeUInt32LE(entry.bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    localParts.push(local, name, entry.bytes);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(ZIP_VERSION_MADE_BY, 4);
    central.writeUInt16LE(ZIP_VERSION, 6);
    central.writeUInt16LE(ZIP_FLAG_UTF8, 8);
    central.writeUInt16LE(ZIP_METHOD_STORED, 10);
    central.writeUInt16LE(ZIP_DOS_TIME, 12);
    central.writeUInt16LE(ZIP_DOS_DATE, 14);
    central.writeUInt32LE(checksum, 16);
    central.writeUInt32LE(entry.bytes.length, 20);
    central.writeUInt32LE(entry.bytes.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(ZIP_EXTERNAL_ATTRIBUTES, 38);
    central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);

    offset += local.length + name.length + entry.bytes.length;
    if (offset > 0xffffffff)
      fail('Bundle local entries exceed classic ZIP limits');
  }

  const centralDirectory = Buffer.concat(centralParts);
  if (centralDirectory.length > 0xffffffff || offset + centralDirectory.length > 0xffffffff)
    fail('Bundle central directory exceeds classic ZIP limits');

  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(sorted.length, 8);
  end.writeUInt16LE(sorted.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);

  return Buffer.concat([...localParts, centralDirectory, end]);
}

export function readDeterministicZip(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 22)
    fail('Central bundle is not a classic ZIP file');

  const endOffset = bytes.length - 22;
  if (bytes.readUInt32LE(endOffset) !== 0x06054b50
      || bytes.readUInt16LE(endOffset + 4) !== 0
      || bytes.readUInt16LE(endOffset + 6) !== 0
      || bytes.readUInt16LE(endOffset + 20) !== 0) {
    fail('Central bundle must have one deterministic, comment-free ZIP directory');
  }

  const count = bytes.readUInt16LE(endOffset + 8);
  if (count === 0 || count !== bytes.readUInt16LE(endOffset + 10))
    fail('Central bundle ZIP entry counts are invalid');
  const centralSize = bytes.readUInt32LE(endOffset + 12);
  const centralOffset = bytes.readUInt32LE(endOffset + 16);
  if (centralOffset + centralSize !== endOffset)
    fail('Central bundle ZIP directory boundaries are invalid');

  const entries = [];
  let cursor = centralOffset;
  let expectedLocalOffset = 0;
  for (let index = 0; index < count; ++index) {
    if (cursor + 46 > endOffset || bytes.readUInt32LE(cursor) !== 0x02014b50)
      fail('Central bundle ZIP directory entry is invalid');

    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extraLength = bytes.readUInt16LE(cursor + 30);
    const commentLength = bytes.readUInt16LE(cursor + 32);
    const localOffset = bytes.readUInt32LE(cursor + 42);
    const size = bytes.readUInt32LE(cursor + 24);
    const checksum = bytes.readUInt32LE(cursor + 16);
    if (bytes.readUInt16LE(cursor + 4) !== ZIP_VERSION_MADE_BY
        || bytes.readUInt16LE(cursor + 6) !== ZIP_VERSION
        || bytes.readUInt16LE(cursor + 8) !== ZIP_FLAG_UTF8
        || bytes.readUInt16LE(cursor + 10) !== ZIP_METHOD_STORED
        || bytes.readUInt16LE(cursor + 12) !== ZIP_DOS_TIME
        || bytes.readUInt16LE(cursor + 14) !== ZIP_DOS_DATE
        || bytes.readUInt32LE(cursor + 20) !== size
        || extraLength !== 0 || commentLength !== 0
        || bytes.readUInt16LE(cursor + 34) !== 0
        || bytes.readUInt16LE(cursor + 36) !== 0
        || bytes.readUInt32LE(cursor + 38) !== ZIP_EXTERNAL_ATTRIBUTES
        || localOffset !== expectedLocalOffset) {
      fail('Central bundle ZIP metadata is not deterministic');
    }

    const nameStart = cursor + 46;
    const nameEnd = nameStart + nameLength;
    if (nameEnd > endOffset)
      fail('Central bundle ZIP entry name is truncated');
    const path = bytes.subarray(nameStart, nameEnd).toString('utf8');
    if (Buffer.from(path, 'utf8').compare(bytes.subarray(nameStart, nameEnd)) !== 0)
      fail('Central bundle ZIP entry name is not UTF-8');
    requireSafeZipPath(path);

    if (localOffset + 30 > centralOffset || bytes.readUInt32LE(localOffset) !== 0x04034b50)
      fail('Central bundle ZIP local entry is invalid');
    const localNameLength = bytes.readUInt16LE(localOffset + 26);
    const localExtraLength = bytes.readUInt16LE(localOffset + 28);
    const localNameStart = localOffset + 30;
    const localNameEnd = localNameStart + localNameLength;
    const dataStart = localNameEnd + localExtraLength;
    const dataEnd = dataStart + size;
    if (bytes.readUInt16LE(localOffset + 4) !== ZIP_VERSION
        || bytes.readUInt16LE(localOffset + 6) !== ZIP_FLAG_UTF8
        || bytes.readUInt16LE(localOffset + 8) !== ZIP_METHOD_STORED
        || bytes.readUInt16LE(localOffset + 10) !== ZIP_DOS_TIME
        || bytes.readUInt16LE(localOffset + 12) !== ZIP_DOS_DATE
        || bytes.readUInt32LE(localOffset + 14) !== checksum
        || bytes.readUInt32LE(localOffset + 18) !== size
        || bytes.readUInt32LE(localOffset + 22) !== size
        || localExtraLength !== 0 || localNameLength !== nameLength
        || dataEnd > centralOffset
        || bytes.subarray(localNameStart, localNameEnd).compare(bytes.subarray(nameStart, nameEnd)) !== 0) {
      fail('Central bundle ZIP local metadata does not match its directory');
    }

    const data = Buffer.from(bytes.subarray(dataStart, dataEnd));
    if (crc32(data) !== checksum)
      fail(`Central bundle ZIP entry CRC is invalid: ${path}`);
    entries.push(Object.freeze({ path, bytes: data }));
    expectedLocalOffset = dataEnd;
    cursor = nameEnd + extraLength + commentLength;
  }

  if (cursor !== endOffset || expectedLocalOffset !== centralOffset)
    fail('Central bundle ZIP contains unaccounted bytes');
  const paths = entries.map((entry) => entry.path);
  const sortedPaths = [...paths].sort(compareAscii);
  if (new Set(paths).size !== paths.length || JSON.stringify(paths) !== JSON.stringify(sortedPaths))
    fail('Central bundle ZIP entries must be unique and ASCII-sorted');

  return Object.freeze(entries);
}

function entryEvidence(entry) {
  return Object.freeze({
    bytes: entry.bytes.length,
    path: entry.path,
    sha256: sha256(entry.bytes),
  });
}

function preparationArtifactRecord(key, source, baseEntry, signatureEntry, checksumEntries) {
  return Object.freeze({
    bundlePath: baseEntry.path,
    bytes: baseEntry.bytes.length,
    checksums: Object.fromEntries(CHECKSUM_ALGORITHMS.map(({ extension, nodeName }) => {
      const entry = checksumEntries.get(extension);
      return [extension, Object.freeze({
        algorithm: extension.toUpperCase(),
        bundlePath: entry.path,
        bytes: entry.bytes.length,
        sha256: sha256(entry.bytes),
        value: digest(nodeName, baseEntry.bytes),
      })];
    })),
    sha256: sha256(baseEntry.bytes),
    signature: Object.freeze({
      bundlePath: signatureEntry.path,
      bytes: signatureEntry.bytes.length,
      sha256: sha256(signatureEntry.bytes),
    }),
    sourceFileName: source.specification.evidenceName,
  });
}

export function preparePromotion({
  evidencePath,
  evidenceSha256,
  releaseManifestPath,
  releaseManifestSha256,
  candidateCommit,
  artifactPaths,
  signingFingerprint,
  gpgPath,
  outputDirectory,
}) {
  const fingerprint = signingFingerprint.toUpperCase();
  if (!FINGERPRINT_PATTERN.test(fingerprint))
    fail('Signing fingerprint must be a full 40- or 64-character hexadecimal fingerprint');
  const signer = validateSignerPath(gpgPath);

  const evidenceFile = parseCanonicalJson(
    evidencePath,
    evidenceSha256,
    'canonical release-validation evidence',
  );
  const evidence = validateCompletedReleaseEvidence(evidenceFile.value, candidateCommit);
  const manifestFile = parseCanonicalJson(
    releaseManifestPath,
    releaseManifestSha256,
    'reviewed release manifest',
    false,
  );
  validateReviewedReleaseManifest(manifestFile.value, releaseManifestSha256, evidence);
  const promotionTool = validatePromotionTool(manifestFile.value.promotion);
  const sources = loadCandidateArtifacts(artifactPaths, evidence);

  const output = resolve(outputDirectory);
  const outputParent = dirname(output);
  mkdirSync(outputParent, { recursive: true });
  if (!statSync(outputParent).isDirectory())
    fail(`Promotion output parent is not a directory: ${outputParent}`);

  try {
    mkdirSync(output, { mode: 0o700 });
  } catch {
    fail(`Refusing to overwrite or merge promotion output directory: ${output}`);
  }

  let temporary;
  let completed = false;
  try {
    temporary = mkdtempSync(join(output, '.incomplete-'));
    const stageRoot = join(temporary, 'stage');
    const entries = [];
    const records = {};

    for (const [key, source] of Object.entries(sources)) {
      const basePath = `${MAVEN_DIRECTORY}/${source.specification.bundleName}`;
      const stagedArtifact = join(stageRoot, ...basePath.split('/'));
      writeNewFile(stagedArtifact, source.bytes);
      const baseEntry = Object.freeze({ path: basePath, bytes: source.bytes });
      entries.push(baseEntry);

      const signaturePath = `${basePath}.asc`;
      const stagedSignature = join(stageRoot, ...signaturePath.split('/'));
      const signatureBytes = signAndVerify(
        signer,
        fingerprint,
        stagedArtifact,
        stagedSignature,
        sha256(source.bytes),
      );
      const signatureEntry = Object.freeze({ path: signaturePath, bytes: signatureBytes });
      entries.push(signatureEntry);

      const checksumEntries = new Map();
      for (const { extension, nodeName } of CHECKSUM_ALGORITHMS) {
        const checksumPath = `${basePath}.${extension}`;
        const checksumBytes = Buffer.from(`${digest(nodeName, source.bytes)}\n`, 'ascii');
        const checksumEntry = Object.freeze({ path: checksumPath, bytes: checksumBytes });
        checksumEntries.set(extension, checksumEntry);
        entries.push(checksumEntry);
      }

      records[key] = preparationArtifactRecord(
        key,
        source,
        baseEntry,
        signatureEntry,
        checksumEntries,
      );
    }

    const bundleBytes = createDeterministicZip(entries);
    if (bundleBytes.length > MAX_BUNDLE_BYTES)
      fail('Central bundle exceeds the 1 GiB Portal limit');
    const parsedEntries = readDeterministicZip(bundleBytes);
    const bundlePath = join(temporary, BUNDLE_NAME);
    writeNewFile(bundlePath, bundleBytes);

    const preparation = Object.freeze({
      artifacts: records,
      bundle: Object.freeze({
        bytes: bundleBytes.length,
        entries: parsedEntries.map(entryEvidence),
        fileName: BUNDLE_NAME,
        sha256: sha256(bundleBytes),
      }),
      candidateCommit,
      centralPolicy: Object.freeze({
        publishEndpointInvoked: false,
        publishingType: 'USER_MANAGED',
        uploadUrl: CENTRAL_UPLOAD_URL,
      }),
      coordinates: COORDINATES,
      formatVersion: 1,
      mode: 'OFFLINE_PREPARE',
      promotionTool,
      releaseValidationEvidence: Object.freeze({
        bytes: evidenceFile.bytes.length,
        fileName: basename(evidenceFile.absolutePath),
        sha256: evidenceSha256,
      }),
      reviewedReleaseManifest: Object.freeze({
        bytes: manifestFile.bytes.length,
        fileName: basename(manifestFile.absolutePath),
        sha256: releaseManifestSha256,
      }),
      signing: Object.freeze({
        fingerprint,
        format: 'OPENPGP_DETACHED_ASCII_ARMOR',
      }),
    });

    writeCanonicalJson(join(temporary, PREPARATION_NAME), preparation);
    rmSync(stageRoot, { force: true, recursive: true });
    renameSync(bundlePath, join(output, BUNDLE_NAME));
    renameSync(join(temporary, PREPARATION_NAME), join(output, PREPARATION_NAME));
    rmSync(temporary, { force: true, recursive: true });
    completed = true;
    return Object.freeze({
      bundlePath: join(output, BUNDLE_NAME),
      preparation,
      preparationPath: join(output, PREPARATION_NAME),
    });
  } finally {
    if (!completed)
      rmSync(output, { force: true, recursive: true });
  }
}

function validateEntryEvidence(value, expected, description) {
  requireExactKeys(value, ['bytes', 'path', 'sha256'], description);
  requirePositiveSafeInteger(value.bytes, `${description} byte count`);
  if (value.path !== expected.path || value.bytes !== expected.bytes.length
      || value.sha256 !== sha256(expected.bytes)) {
    fail(`${description} does not match the Central bundle`);
  }
}

export function validatePreparationRecord(value, bundleBytes) {
  requireExactKeys(
    value,
    [
      'artifacts',
      'bundle',
      'candidateCommit',
      'centralPolicy',
      'coordinates',
      'formatVersion',
      'mode',
      'promotionTool',
      'releaseValidationEvidence',
      'reviewedReleaseManifest',
      'signing',
    ],
    'promotion preparation',
  );
  if (value.formatVersion !== 1 || value.mode !== 'OFFLINE_PREPARE'
      || !COMMIT_PATTERN.test(value.candidateCommit)) {
    fail('Promotion preparation identity is invalid');
  }
  requireCoordinates(value.coordinates, 'promotion preparation coordinates');
  requireExactKeys(value.promotionTool, ['helper', 'wrapper'], 'promotion tool');
  for (const [name, tool] of Object.entries(value.promotionTool)) {
    requireExactKeys(tool, ['bytes', 'fileName', 'sha256'], `promotion tool ${name}`);
    requirePositiveSafeInteger(tool.bytes, `promotion tool ${name} byte count`);
    if (!new Set(['release-promotion.mjs', 'promote-release-candidate.sh']).has(tool.fileName)
        || !SHA256_PATTERN.test(tool.sha256)) {
      fail(`Promotion tool ${name} identity is invalid`);
    }
    const currentPath = name === 'helper' ? PROMOTION_HELPER_PATH : PROMOTION_WRAPPER_PATH;
    const current = readRegularFile(currentPath, `current promotion tool ${name}`);
    if (current.bytes.length !== tool.bytes || sha256(current.bytes) !== tool.sha256)
      fail(`Current promotion tool ${name} does not match preparation evidence`);
  }
  if (value.promotionTool.helper.fileName !== 'release-promotion.mjs'
      || value.promotionTool.wrapper.fileName !== 'promote-release-candidate.sh') {
    fail('Promotion tool file-name mapping is invalid');
  }
  requireExactKeys(
    value.centralPolicy,
    ['publishEndpointInvoked', 'publishingType', 'uploadUrl'],
    'promotion preparation Central policy',
  );
  if (value.centralPolicy.publishEndpointInvoked !== false
      || value.centralPolicy.publishingType !== 'USER_MANAGED'
      || value.centralPolicy.uploadUrl !== CENTRAL_UPLOAD_URL) {
    fail('Promotion preparation does not enforce USER_MANAGED Central validation');
  }
  requireExactKeys(value.signing, ['fingerprint', 'format'], 'promotion preparation signing');
  if (!FINGERPRINT_PATTERN.test(value.signing.fingerprint)
      || value.signing.format !== 'OPENPGP_DETACHED_ASCII_ARMOR') {
    fail('Promotion preparation signing identity is invalid');
  }
  requireExactKeys(
    value.releaseValidationEvidence,
    ['bytes', 'fileName', 'sha256'],
    'promotion preparation release evidence',
  );
  requirePositiveSafeInteger(
    value.releaseValidationEvidence.bytes,
    'promotion preparation release evidence byte count',
  );
  requireTrimmedString(
    value.releaseValidationEvidence.fileName,
    'promotion preparation release evidence file name',
  );
  if (basename(value.releaseValidationEvidence.fileName)
      !== value.releaseValidationEvidence.fileName
      || !SHA256_PATTERN.test(value.releaseValidationEvidence.sha256)) {
    fail('Promotion preparation release evidence identity is invalid');
  }
  requireExactKeys(
    value.reviewedReleaseManifest,
    ['bytes', 'fileName', 'sha256'],
    'promotion preparation reviewed release manifest',
  );
  requirePositiveSafeInteger(
    value.reviewedReleaseManifest.bytes,
    'promotion preparation reviewed release manifest byte count',
  );
  requireTrimmedString(
    value.reviewedReleaseManifest.fileName,
    'promotion preparation reviewed release manifest file name',
  );
  if (basename(value.reviewedReleaseManifest.fileName)
      !== value.reviewedReleaseManifest.fileName
      || !SHA256_PATTERN.test(value.reviewedReleaseManifest.sha256)) {
    fail('Promotion preparation reviewed release manifest identity is invalid');
  }

  requireExactKeys(value.bundle, ['bytes', 'entries', 'fileName', 'sha256'], 'promotion bundle');
  if (value.bundle.fileName !== BUNDLE_NAME || value.bundle.bytes !== bundleBytes.length
      || value.bundle.sha256 !== sha256(bundleBytes) || bundleBytes.length > MAX_BUNDLE_BYTES) {
    fail('Promotion preparation bundle identity does not match the supplied bundle');
  }
  const parsedEntries = readDeterministicZip(bundleBytes);
  if (!Array.isArray(value.bundle.entries) || value.bundle.entries.length !== parsedEntries.length)
    fail('Promotion preparation bundle entry evidence is incomplete');
  for (const [index, entry] of parsedEntries.entries())
    validateEntryEvidence(value.bundle.entries[index], entry, `promotion bundle entry ${index}`);

  requireExactKeys(
    value.artifacts,
    ['javadocJar', 'mainJar', 'pom', 'sourcesJar'],
    'promotion artifacts',
  );
  const entryMap = new Map(parsedEntries.map((entry) => [entry.path, entry.bytes]));
  const consumed = new Set();
  for (const [key, specification] of Object.entries(ARTIFACTS)) {
    const artifact = value.artifacts[key];
    requireExactKeys(
      artifact,
      ['bundlePath', 'bytes', 'checksums', 'sha256', 'signature', 'sourceFileName'],
      `promotion ${key}`,
    );
    const expectedBasePath = `${MAVEN_DIRECTORY}/${specification.bundleName}`;
    if (artifact.bundlePath !== expectedBasePath
        || artifact.sourceFileName !== specification.evidenceName) {
      fail(`Promotion ${key} names are invalid`);
    }
    const baseBytes = entryMap.get(artifact.bundlePath);
    if (baseBytes === undefined || artifact.bytes !== baseBytes.length
        || artifact.sha256 !== sha256(baseBytes)) {
      fail(`Promotion ${key} bytes do not match the Central bundle`);
    }
    consumed.add(artifact.bundlePath);

    requireExactKeys(artifact.signature, ['bundlePath', 'bytes', 'sha256'], `promotion ${key} signature`);
    if (artifact.signature.bundlePath !== `${artifact.bundlePath}.asc`)
      fail(`Promotion ${key} signature name is invalid`);
    const signatureBytes = entryMap.get(artifact.signature.bundlePath);
    if (signatureBytes === undefined || artifact.signature.bytes !== signatureBytes.length
        || artifact.signature.sha256 !== sha256(signatureBytes)) {
      fail(`Promotion ${key} signature does not match the Central bundle`);
    }
    requireArmoredSignature(signatureBytes, `Promotion ${key} signature`);
    consumed.add(artifact.signature.bundlePath);

    requireExactKeys(
      artifact.checksums,
      ['md5', 'sha1', 'sha256', 'sha512'],
      `promotion ${key} checksums`,
    );
    for (const { extension, nodeName } of CHECKSUM_ALGORITHMS) {
      const checksum = artifact.checksums[extension];
      requireExactKeys(
        checksum,
        ['algorithm', 'bundlePath', 'bytes', 'sha256', 'value'],
        `promotion ${key} ${extension} checksum`,
      );
      const expectedValue = digest(nodeName, baseBytes);
      const expectedPath = `${artifact.bundlePath}.${extension}`;
      const checksumBytes = entryMap.get(expectedPath);
      if (checksum.algorithm !== extension.toUpperCase() || checksum.bundlePath !== expectedPath
          || checksum.value !== expectedValue || checksumBytes === undefined
          || checksum.bytes !== checksumBytes.length || checksum.sha256 !== sha256(checksumBytes)
          || checksumBytes.compare(Buffer.from(`${expectedValue}\n`, 'ascii')) !== 0) {
        fail(`Promotion ${key} ${extension} checksum does not match the base artifact`);
      }
      consumed.add(expectedPath);
    }
  }
  if (consumed.size !== parsedEntries.length
      || parsedEntries.some((entry) => !consumed.has(entry.path))) {
    fail('Central bundle contains files outside the four artifacts, signatures, and checksums');
  }

  return value;
}

function readPreparation(preparationPath, preparationSha256, bundlePath) {
  const preparationFile = parseCanonicalJson(
    preparationPath,
    preparationSha256,
    'promotion preparation',
  );
  const bundleFile = readRegularFile(bundlePath, 'Central bundle');
  if (basename(bundleFile.absolutePath) !== BUNDLE_NAME)
    fail(`Central bundle file name must be exactly ${BUNDLE_NAME}`);
  validatePreparationRecord(preparationFile.value, bundleFile.bytes);
  return Object.freeze({ preparationFile, bundleFile });
}

function requireBoundedSeconds(value, minimum, maximum, description) {
  if (!Number.isInteger(value) || value < minimum || value > maximum)
    fail(`${description} must be an integer from ${minimum} through ${maximum}`);
}

function createMultipartBody(bundleName, bundleBytes) {
  const bundleHash = sha256(bundleBytes);
  let boundaryCounter = 0;
  let boundary;
  do {
    boundary = `soklet-${bundleHash.slice(0, 32)}-${boundaryCounter}`;
    boundaryCounter += 1;
  } while (bundleBytes.subarray(0, boundary.length + 2)
      .compare(Buffer.from(`--${boundary}`, 'ascii')) === 0
    || bundleBytes.includes(Buffer.from(`\r\n--${boundary}`, 'ascii')));
  const prefix = Buffer.from(
    `--${boundary}\r\n`
      + `Content-Disposition: form-data; name="bundle"; filename="${bundleName}"\r\n`
      + 'Content-Type: application/octet-stream\r\n\r\n',
    'ascii',
  );
  const suffix = Buffer.from(`\r\n--${boundary}--\r\n`, 'ascii');
  return Object.freeze({
    body: Buffer.concat([prefix, bundleBytes, suffix]),
    contentType: `multipart/form-data; boundary=${boundary}`,
  });
}

async function boundedResponseBytes(response, maximum) {
  const declared = response.headers.get('content-length');
  if (declared !== null && (!/^(?:0|[1-9][0-9]*)$/.test(declared)
      || Number(declared) > maximum)) {
    fail('Central response exceeds its allowed size');
  }

  if (response.body === null)
    return Buffer.alloc(0);
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done)
      break;
    length += value.length;
    if (length > maximum) {
      await reader.cancel();
      fail('Central response exceeds its allowed size');
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, length);
}

export async function centralTransport(request) {
  const allowedUpload = request?.method === 'POST' && request.url === CENTRAL_UPLOAD_URL;
  const allowedStatus = request?.method === 'POST'
    && typeof request.url === 'string'
    && request.url.startsWith(CENTRAL_STATUS_BASE_URL)
    && UUID_PATTERN.test(request.url.slice(CENTRAL_STATUS_BASE_URL.length));
  const allowedArtifact = request?.method === 'GET' && CENTRAL_ARTIFACT_URLS.has(request.url);
  if (!allowedUpload && !allowedStatus && !allowedArtifact)
    fail('Central transport refused a URL or method outside the promotion allowlist');

  let response;
  try {
    response = await fetch(request.url, {
      body: request.body,
      headers: request.headers,
      method: request.method,
      redirect: 'error',
      signal: AbortSignal.timeout(request.timeoutMilliseconds),
    });
  } catch {
    fail('Central request failed');
  }
  return Object.freeze({
    body: await boundedResponseBytes(response, request.maximumResponseBytes),
    status: response.status,
  });
}

function requireTransportResponse(response) {
  if (response === null || typeof response !== 'object'
      || !Number.isInteger(response.status) || !Buffer.isBuffer(response.body)) {
    fail('Central transport returned an invalid response');
  }
  return response;
}

function parseStatusResponse(response, deploymentId) {
  if (response.status !== 200)
    fail('Central status request did not return HTTP 200');
  if (response.body.length === 0 || response.body.length > MAX_RESPONSE_BYTES)
    fail('Central status response size is invalid');

  const text = response.body.toString('utf8');
  if (Buffer.from(text, 'utf8').compare(response.body) !== 0)
    fail('Central status response is not UTF-8');

  let value;
  try {
    value = JSON.parse(text);
  } catch {
    fail('Central status response is not valid JSON');
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || value.deploymentId !== deploymentId || typeof value.deploymentState !== 'string') {
    fail('Central status response does not identify the expected deployment');
  }
  return value.deploymentState;
}

async function requestWithinDeadline(transport, request, deadline, now) {
  const remaining = deadline - now();
  if (remaining <= 0)
    fail('Central operation timed out');
  return requireTransportResponse(await transport({
    ...request,
    timeoutMilliseconds: Math.max(1, Math.ceil(Math.min(30_000, remaining))),
  }));
}

async function pollDeployment({
  authorization,
  deploymentId,
  deadline,
  now,
  pollIntervalMilliseconds,
  sleep,
  terminalStates,
  transientStates,
  transport,
}) {
  const statusUrl = `${CENTRAL_STATUS_BASE_URL}${encodeURIComponent(deploymentId)}`;
  while (true) {
    const response = await requestWithinDeadline(
      transport,
      {
        body: undefined,
        headers: Object.freeze({
          Accept: 'application/json',
          Authorization: authorization,
        }),
        maximumResponseBytes: MAX_RESPONSE_BYTES,
        method: 'POST',
        url: statusUrl,
      },
      deadline,
      now,
    );
    const state = parseStatusResponse(response, deploymentId);
    if (terminalStates.has(state))
      return Object.freeze({ state, statusUrl });
    if (!transientStates.has(state))
      fail('Central returned a disallowed deployment state');

    const remaining = deadline - now();
    if (remaining <= 0)
      fail('Central operation timed out');
    await sleep(Math.min(pollIntervalMilliseconds, remaining));
  }
}

function preparationReference(file, sha) {
  return Object.freeze({
    bytes: file.bytes.length,
    fileName: basename(file.absolutePath),
    sha256: sha,
  });
}

function deploymentEvidenceRecord({
  preparationFile,
  preparationSha256,
  bundleFile,
  deploymentId,
  state,
  mode,
}) {
  return Object.freeze({
    bundle: Object.freeze({
      bytes: bundleFile.bytes.length,
      fileName: BUNDLE_NAME,
      sha256: sha256(bundleFile.bytes),
    }),
    candidateCommit: preparationFile.value.candidateCommit,
    central: Object.freeze({
      deploymentId,
      publishEndpointInvoked: false,
      publishingType: 'USER_MANAGED',
      state,
      statusUrl: `${CENTRAL_STATUS_BASE_URL}${deploymentId}`,
      uploadUrl: CENTRAL_UPLOAD_URL,
    }),
    coordinates: COORDINATES,
    formatVersion: 1,
    mode,
    preparation: preparationReference(preparationFile, preparationSha256),
  });
}

export async function uploadUserManaged({
  preparationPath,
  preparationSha256,
  bundlePath,
  authorization,
  timeoutSeconds,
  pollIntervalSeconds,
  transport = centralTransport,
  now = () => performance.now(),
  sleep = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds)),
  onAccepted,
}) {
  if (typeof authorization !== 'string' || !authorization.startsWith('Bearer '))
    fail('Central authorization is missing');
  requireBoundedSeconds(timeoutSeconds, 1, 3600, 'Central timeout');
  requireBoundedSeconds(pollIntervalSeconds, 1, 60, 'Central poll interval');
  if (typeof onAccepted !== 'function')
    fail('Central upload requires a durable accepted-deployment journal callback');
  const { preparationFile, bundleFile } = readPreparation(
    preparationPath,
    preparationSha256,
    bundlePath,
  );
  const deadline = now() + timeoutSeconds * 1000;
  const multipart = createMultipartBody(BUNDLE_NAME, bundleFile.bytes);
  const upload = await requestWithinDeadline(
    transport,
    {
      body: multipart.body,
      headers: Object.freeze({
        Accept: 'text/plain',
        Authorization: authorization,
        'Content-Length': String(multipart.body.length),
        'Content-Type': multipart.contentType,
      }),
      maximumResponseBytes: MAX_RESPONSE_BYTES,
      method: 'POST',
      url: CENTRAL_UPLOAD_URL,
    },
    deadline,
    now,
  );
  if (upload.status !== 201)
    fail('Central USER_MANAGED upload did not return HTTP 201');
  const uploadText = upload.body.toString('utf8');
  if (Buffer.from(uploadText, 'utf8').compare(upload.body) !== 0)
    fail('Central USER_MANAGED upload response is not UTF-8');
  const deploymentId = uploadText.endsWith('\r\n')
    ? uploadText.slice(0, -2)
    : uploadText.endsWith('\n')
      ? uploadText.slice(0, -1)
      : uploadText;
  if (!UUID_PATTERN.test(deploymentId))
    fail('Central USER_MANAGED upload did not return a deployment UUID');

  const accepted = deploymentEvidenceRecord({
    bundleFile,
    deploymentId,
    mode: 'CENTRAL_USER_MANAGED_ACCEPTED',
    preparationFile,
    preparationSha256,
    state: 'ACCEPTED',
  });
  await onAccepted(accepted);

  const terminal = await pollDeployment({
    authorization,
    deadline,
    deploymentId,
    now,
    pollIntervalMilliseconds: pollIntervalSeconds * 1000,
    sleep,
    terminalStates: new Set(['VALIDATED', 'FAILED']),
    transientStates: new Set(['PENDING', 'VALIDATING']),
    transport,
  });
  return deploymentEvidenceRecord({
    bundleFile,
    deploymentId,
    mode: 'CENTRAL_USER_MANAGED_UPLOAD',
    preparationFile,
    preparationSha256,
    state: terminal.state,
  });
}

function validateDeploymentEvidence(
  value,
  preparationFile,
  preparationSha256,
  bundleFile,
  { description, mode, states },
) {
  requireExactKeys(
    value,
    ['bundle', 'candidateCommit', 'central', 'coordinates', 'formatVersion', 'mode', 'preparation'],
    description,
  );
  if (value.formatVersion !== 1 || value.mode !== mode
      || value.candidateCommit !== preparationFile.value.candidateCommit) {
    fail(`${description} identity is invalid`);
  }
  requireCoordinates(value.coordinates, `${description} coordinates`);
  requireExactKeys(value.bundle, ['bytes', 'fileName', 'sha256'], `${description} bundle`);
  if (value.bundle.fileName !== BUNDLE_NAME || value.bundle.bytes !== bundleFile.bytes.length
      || value.bundle.sha256 !== sha256(bundleFile.bytes)) {
    fail(`${description} bundle identity is invalid`);
  }
  requireExactKeys(value.preparation, ['bytes', 'fileName', 'sha256'], `${description} preparation`);
  const expectedPreparation = preparationReference(preparationFile, preparationSha256);
  if (JSON.stringify(value.preparation) !== JSON.stringify(expectedPreparation))
    fail(`${description} does not identify the supplied preparation`);
  requireExactKeys(
    value.central,
    [
      'deploymentId',
      'publishEndpointInvoked',
      'publishingType',
      'state',
      'statusUrl',
      'uploadUrl',
    ],
    `${description} deployment`,
  );
  if (!UUID_PATTERN.test(value.central.deploymentId)
      || value.central.publishEndpointInvoked !== false
      || value.central.publishingType !== 'USER_MANAGED'
      || !states.has(value.central.state)
      || value.central.statusUrl !== `${CENTRAL_STATUS_BASE_URL}${value.central.deploymentId}`
      || value.central.uploadUrl !== CENTRAL_UPLOAD_URL) {
    fail(`${description} deployment identity is invalid`);
  }
  return value;
}

function validateUploadEvidence(value, preparationFile, preparationSha256, bundleFile) {
  return validateDeploymentEvidence(
    value,
    preparationFile,
    preparationSha256,
    bundleFile,
    {
      description: 'Central upload evidence',
      mode: 'CENTRAL_USER_MANAGED_UPLOAD',
      states: new Set(['VALIDATED', 'FAILED']),
    },
  );
}

function validateAcceptedEvidence(value, preparationFile, preparationSha256, bundleFile) {
  return validateDeploymentEvidence(
    value,
    preparationFile,
    preparationSha256,
    bundleFile,
    {
      description: 'Central accepted-deployment evidence',
      mode: 'CENTRAL_USER_MANAGED_ACCEPTED',
      states: new Set(['ACCEPTED']),
    },
  );
}

export async function resumeUserManagedStatus({
  preparationPath,
  preparationSha256,
  bundlePath,
  acceptedEvidencePath,
  acceptedEvidenceSha256,
  authorization,
  timeoutSeconds,
  pollIntervalSeconds,
  transport = centralTransport,
  now = () => performance.now(),
  sleep = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds)),
}) {
  if (typeof authorization !== 'string' || !authorization.startsWith('Bearer '))
    fail('Central authorization is missing');
  requireBoundedSeconds(timeoutSeconds, 1, 3600, 'Central timeout');
  requireBoundedSeconds(pollIntervalSeconds, 1, 60, 'Central poll interval');
  const { preparationFile, bundleFile } = readPreparation(
    preparationPath,
    preparationSha256,
    bundlePath,
  );
  const acceptedFile = parseCanonicalJson(
    acceptedEvidencePath,
    acceptedEvidenceSha256,
    'Central accepted-deployment evidence',
  );
  const accepted = validateAcceptedEvidence(
    acceptedFile.value,
    preparationFile,
    preparationSha256,
    bundleFile,
  );
  const deadline = now() + timeoutSeconds * 1000;
  const terminal = await pollDeployment({
    authorization,
    deadline,
    deploymentId: accepted.central.deploymentId,
    now,
    pollIntervalMilliseconds: pollIntervalSeconds * 1000,
    sleep,
    terminalStates: new Set(['VALIDATED', 'FAILED']),
    transientStates: new Set(['PENDING', 'VALIDATING']),
    transport,
  });
  return deploymentEvidenceRecord({
    bundleFile,
    deploymentId: accepted.central.deploymentId,
    mode: 'CENTRAL_USER_MANAGED_UPLOAD',
    preparationFile,
    preparationSha256,
    state: terminal.state,
  });
}

export async function recordUserManagedUpload({
  acceptedOutputPath,
  outputPath,
  afterAccepted = async () => {},
  ...uploadOptions
}) {
  if (resolve(acceptedOutputPath) === resolve(outputPath))
    fail('Accepted and terminal Central evidence paths must be distinct');

  let acceptedReservation;
  let terminalReservation;
  try {
    acceptedReservation = reserveEvidenceFile(
      acceptedOutputPath,
      'Central accepted-deployment evidence output',
    );
    terminalReservation = reserveEvidenceFile(outputPath, 'Central terminal evidence output');
    const terminal = await uploadUserManaged({
      ...uploadOptions,
      onAccepted: async (accepted) => {
        commitReservedEvidence(acceptedReservation, accepted);
        await afterAccepted(accepted, acceptedReservation.absolutePath);
      },
    });
    commitReservedEvidence(terminalReservation, terminal);
    return Object.freeze({
      acceptedPath: acceptedReservation.absolutePath,
      record: terminal,
      outputPath: terminalReservation.absolutePath,
    });
  } finally {
    if (!acceptedReservation?.committed)
      abandonEvidenceReservation(acceptedReservation);
    if (!terminalReservation?.committed)
      abandonEvidenceReservation(terminalReservation);
  }
}

export async function recordUserManagedStatus({ outputPath, ...statusOptions }) {
  let reservation;
  try {
    reservation = reserveEvidenceFile(outputPath, 'Central terminal evidence output');
    const terminal = await resumeUserManagedStatus(statusOptions);
    commitReservedEvidence(reservation, terminal);
    return Object.freeze({ record: terminal, outputPath: reservation.absolutePath });
  } finally {
    if (!reservation?.committed)
      abandonEvidenceReservation(reservation);
  }
}

async function downloadPublishedArtifact({
  deadline,
  expected,
  now,
  pollIntervalMilliseconds,
  sleep,
  specification,
  transport,
}) {
  const url = `${CENTRAL_REPOSITORY_BASE_URL}${specification.bundleName}`;
  const transientStatuses = new Set([404, 408, 425, 429, 500, 502, 503, 504]);
  while (true) {
    const response = await requestWithinDeadline(
      transport,
      {
        body: undefined,
        headers: Object.freeze({ Accept: 'application/octet-stream' }),
        maximumResponseBytes: Math.max(MAX_RESPONSE_BYTES, expected.bytes + 1),
        method: 'GET',
        url,
      },
      deadline,
      now,
    );
    if (response.status === 200) {
      if (response.body.length !== expected.bytes || sha256(response.body) !== expected.sha256)
        fail(`Published Central artifact does not match candidate evidence: ${specification.bundleName}`);
      return Object.freeze({
        bytes: response.body.length,
        fileName: specification.bundleName,
        sha256: sha256(response.body),
        url,
      });
    }
    if (!transientStatuses.has(response.status))
      fail(`Published Central artifact request failed: ${specification.bundleName}`);
    const remaining = deadline - now();
    if (remaining <= 0)
      fail('Central operation timed out');
    await sleep(Math.min(pollIntervalMilliseconds, remaining));
  }
}

export async function verifyPublished({
  preparationPath,
  preparationSha256,
  bundlePath,
  uploadEvidencePath,
  uploadEvidenceSha256,
  authorization,
  timeoutSeconds,
  pollIntervalSeconds,
  transport = centralTransport,
  now = () => performance.now(),
  sleep = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds)),
}) {
  if (typeof authorization !== 'string' || !authorization.startsWith('Bearer '))
    fail('Central authorization is missing');
  requireBoundedSeconds(timeoutSeconds, 1, 3600, 'Central timeout');
  requireBoundedSeconds(pollIntervalSeconds, 1, 60, 'Central poll interval');
  const { preparationFile, bundleFile } = readPreparation(
    preparationPath,
    preparationSha256,
    bundlePath,
  );
  const uploadFile = parseCanonicalJson(
    uploadEvidencePath,
    uploadEvidenceSha256,
    'Central upload evidence',
  );
  const upload = validateUploadEvidence(
    uploadFile.value,
    preparationFile,
    preparationSha256,
    bundleFile,
  );
  if (upload.central.state !== 'VALIDATED')
    fail('Only a recorded VALIDATED deployment can be checked for publication');

  const deadline = now() + timeoutSeconds * 1000;
  const terminal = await pollDeployment({
    authorization,
    deadline,
    deploymentId: upload.central.deploymentId,
    now,
    pollIntervalMilliseconds: pollIntervalSeconds * 1000,
    sleep,
    terminalStates: new Set(['PUBLISHED']),
    transientStates: new Set(['VALIDATED', 'PUBLISHING']),
    transport,
  });

  const artifacts = {};
  for (const [key, specification] of Object.entries(ARTIFACTS)) {
    const expected = preparationFile.value.artifacts[key];
    artifacts[key] = await downloadPublishedArtifact({
      deadline,
      expected,
      now,
      pollIntervalMilliseconds: pollIntervalSeconds * 1000,
      sleep,
      specification,
      transport,
    });
  }

  return Object.freeze({
    artifacts,
    candidateCommit: preparationFile.value.candidateCommit,
    central: Object.freeze({
      deploymentId: upload.central.deploymentId,
      publishEndpointInvoked: false,
      state: terminal.state,
      statusUrl: terminal.statusUrl,
    }),
    coordinates: COORDINATES,
    formatVersion: 1,
    mode: 'CENTRAL_PUBLISHED_VERIFICATION',
    preparation: preparationReference(preparationFile, preparationSha256),
    uploadEvidence: preparationReference(uploadFile, uploadEvidenceSha256),
  });
}

function loadAuthorization() {
  const tokenPath = process.env.SOKLET_CENTRAL_TOKEN_FILE;
  if (typeof tokenPath !== 'string' || tokenPath === '')
    fail('SOKLET_CENTRAL_TOKEN_FILE must identify a private token file');
  const tokenFile = readRegularFile(tokenPath, 'Central token file');
  if ((tokenFile.mode & 0o077) !== 0)
    fail('Central token file must not grant group or other permissions');
  if (typeof process.getuid === 'function' && tokenFile.uid !== process.getuid())
    fail('Central token file must be owned by the current user');
  const tokenText = tokenFile.bytes.toString('utf8');
  if (Buffer.from(tokenText, 'utf8').compare(tokenFile.bytes) !== 0)
    fail('Central token file must be UTF-8');
  const token = tokenText.endsWith('\n') ? tokenText.slice(0, -1) : tokenText;
  if (tokenFile.bytes.length > 4096 || token.includes('\r') || token.includes('\n')
      || !TOKEN_PATTERN.test(token)
      || Buffer.from(token, 'base64').toString('base64') !== token) {
    fail('Central token file must contain one canonical base64 Publisher Portal token');
  }
  const decodedBytes = Buffer.from(token, 'base64');
  const decoded = decodedBytes.toString('utf8');
  const separator = decoded.indexOf(':');
  if (Buffer.from(decoded, 'utf8').compare(decodedBytes) !== 0
      || separator <= 0 || separator === decoded.length - 1
      || /[\u0000-\u001f\u007f]/.test(decoded))
    fail('Central token file does not contain a valid Publisher Portal user token');
  return `Bearer ${token}`;
}

function parseOptions(args, required, optional = {}) {
  const allowed = new Set([...required, ...Object.keys(optional)]);
  const values = {};
  for (let index = 0; index < args.length; index += 2) {
    const option = args[index];
    const value = args[index + 1];
    if (typeof option !== 'string' || !option.startsWith('--') || value === undefined)
      fail('Promotion options must be supplied as --name value pairs');
    const name = option.slice(2);
    if (!allowed.has(name))
      fail(`Unknown promotion option: --${name}`);
    if (Object.hasOwn(values, name))
      fail(`Duplicate promotion option: --${name}`);
    values[name] = value;
  }
  for (const name of required) {
    if (!Object.hasOwn(values, name))
      fail(`Missing required promotion option: --${name}`);
  }
  for (const [name, value] of Object.entries(optional)) {
    if (!Object.hasOwn(values, name))
      values[name] = value;
  }
  return values;
}

function parseSeconds(value, description) {
  if (!/^(?:0|[1-9][0-9]*)$/.test(value))
    fail(`${description} must be a base-10 integer`);
  return Number(value);
}

function requireNewOutputFile(path, defaultName) {
  const absolutePath = resolve(path ?? defaultName);
  if (existsSync(absolutePath))
    fail(`Refusing to overwrite promotion evidence: ${absolutePath}`);
  return absolutePath;
}

function usage() {
  console.error(
    'Usage:\n'
      + '  scripts/promote-release-candidate.sh prepare '
      + '--evidence FILE --evidence-sha256 HEX '
      + '--release-manifest FILE --release-manifest-sha256 HEX --candidate-commit HEX '
      + '--pom FILE --main-jar FILE --sources-jar FILE --javadoc-jar FILE '
      + '--signing-fingerprint HEX --gpg ABSOLUTE_PATH --output-directory DIRECTORY\n'
      + '  SOKLET_CENTRAL_TOKEN_FILE=FILE scripts/promote-release-candidate.sh upload '
      + '--preparation FILE --preparation-sha256 HEX --bundle FILE '
      + '--accepted-output FILE --output FILE '
      + '[--timeout-seconds 900] [--poll-interval-seconds 5]\n'
      + '  SOKLET_CENTRAL_TOKEN_FILE=FILE scripts/promote-release-candidate.sh status '
      + '--preparation FILE --preparation-sha256 HEX --bundle FILE '
      + '--accepted-evidence FILE --accepted-evidence-sha256 HEX --output FILE '
      + '[--timeout-seconds 900] [--poll-interval-seconds 5]\n'
      + '  SOKLET_CENTRAL_TOKEN_FILE=FILE scripts/promote-release-candidate.sh verify-published '
      + '--preparation FILE --preparation-sha256 HEX --bundle FILE '
      + '--upload-evidence FILE --upload-evidence-sha256 HEX --output FILE '
      + '[--timeout-seconds 900] [--poll-interval-seconds 5]',
  );
}

async function main(args) {
  const command = args.shift();
  if (command === 'prepare') {
    const options = parseOptions(args, [
      'evidence',
      'evidence-sha256',
      'release-manifest',
      'release-manifest-sha256',
      'candidate-commit',
      'pom',
      'main-jar',
      'sources-jar',
      'javadoc-jar',
      'signing-fingerprint',
      'gpg',
      'output-directory',
    ]);
    const result = preparePromotion({
      artifactPaths: {
        javadocJar: options['javadoc-jar'],
        mainJar: options['main-jar'],
        pom: options.pom,
        sourcesJar: options['sources-jar'],
      },
      candidateCommit: options['candidate-commit'],
      evidencePath: options.evidence,
      evidenceSha256: options['evidence-sha256'],
      gpgPath: options.gpg,
      outputDirectory: options['output-directory'],
      releaseManifestPath: options['release-manifest'],
      releaseManifestSha256: options['release-manifest-sha256'],
      signingFingerprint: options['signing-fingerprint'],
    });
    console.log(`Prepared offline Central bundle: ${result.bundlePath}`);
    console.log(`Preparation evidence: ${result.preparationPath}`);
    return;
  }

  if (command === 'upload') {
    const options = parseOptions(
      args,
      ['preparation', 'preparation-sha256', 'bundle', 'accepted-output', 'output'],
      { 'poll-interval-seconds': '5', 'timeout-seconds': '900' },
    );
    const result = await recordUserManagedUpload({
      acceptedOutputPath: options['accepted-output'],
      afterAccepted: async (accepted, acceptedPath) => {
        console.log(`Central accepted deployment ${accepted.central.deploymentId}.`);
        console.log(`Accepted-deployment evidence: ${acceptedPath}`);
        console.log(`Accepted-deployment evidence SHA-256: ${sha256(canonicalJsonBytes(accepted))}`);
      },
      authorization: loadAuthorization(),
      bundlePath: options.bundle,
      outputPath: options.output,
      pollIntervalSeconds: parseSeconds(options['poll-interval-seconds'], 'Poll interval'),
      preparationPath: options.preparation,
      preparationSha256: options['preparation-sha256'],
      timeoutSeconds: parseSeconds(options['timeout-seconds'], 'Timeout'),
    });
    console.log(`Central deployment ${result.record.central.deploymentId} reached ${result.record.central.state}.`);
    console.log(`Terminal upload evidence: ${result.outputPath}`);
    if (result.record.central.state === 'FAILED')
      fail('Central validation failed');
    return;
  }

  if (command === 'status') {
    const options = parseOptions(
      args,
      [
        'preparation',
        'preparation-sha256',
        'bundle',
        'accepted-evidence',
        'accepted-evidence-sha256',
        'output',
      ],
      { 'poll-interval-seconds': '5', 'timeout-seconds': '900' },
    );
    const result = await recordUserManagedStatus({
      acceptedEvidencePath: options['accepted-evidence'],
      acceptedEvidenceSha256: options['accepted-evidence-sha256'],
      authorization: loadAuthorization(),
      bundlePath: options.bundle,
      outputPath: options.output,
      pollIntervalSeconds: parseSeconds(options['poll-interval-seconds'], 'Poll interval'),
      preparationPath: options.preparation,
      preparationSha256: options['preparation-sha256'],
      timeoutSeconds: parseSeconds(options['timeout-seconds'], 'Timeout'),
    });
    console.log(`Central deployment ${result.record.central.deploymentId} reached ${result.record.central.state}.`);
    console.log(`Terminal status evidence: ${result.outputPath}`);
    if (result.record.central.state === 'FAILED')
      fail('Central validation failed');
    return;
  }

  if (command === 'verify-published') {
    const options = parseOptions(
      args,
      [
        'preparation',
        'preparation-sha256',
        'bundle',
        'upload-evidence',
        'upload-evidence-sha256',
        'output',
      ],
      { 'poll-interval-seconds': '5', 'timeout-seconds': '900' },
    );
    const output = requireNewOutputFile(options.output, PUBLISHED_EVIDENCE_NAME);
    const record = await verifyPublished({
      authorization: loadAuthorization(),
      bundlePath: options.bundle,
      pollIntervalSeconds: parseSeconds(options['poll-interval-seconds'], 'Poll interval'),
      preparationPath: options.preparation,
      preparationSha256: options['preparation-sha256'],
      timeoutSeconds: parseSeconds(options['timeout-seconds'], 'Timeout'),
      uploadEvidencePath: options['upload-evidence'],
      uploadEvidenceSha256: options['upload-evidence-sha256'],
    });
    writeCanonicalJson(output, record);
    console.log(`Verified four published artifacts for deployment ${record.central.deploymentId}.`);
    console.log(`Published evidence: ${output}`);
    return;
  }

  usage();
  process.exitCode = 64;
}

const invokedPath = process.argv[1] === undefined ? undefined : resolve(process.argv[1]);
if (invokedPath === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(`Promotion failed: ${error instanceof Error ? error.message : 'unknown error'}`);
    process.exitCode = 1;
  });
}
