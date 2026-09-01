#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  activeScenarios,
  verifyManifestSet,
} from '../conformance/official/verify.mjs';
import { verifyMatrixClosure } from './verify-release-matrix-closure.mjs';

const COMMIT_PATTERN = /^[0-9a-f]{40}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const MATRIX_CLOSURE_GATE_ID = 'matrix-closure';
const MATRIX_CLOSURE_REGISTRY_PATH = 'release/mcp-conformance-matrix-closure.json';
const MATRIX_CLOSURE_RESIDUAL_EVIDENCE_PATH =
  'release/mcp-residual-closure-evidence.json';
const MATRIX_CLOSURE_VERIFIER_PATH = 'scripts/verify-release-matrix-closure.mjs';
const IMPORTED_RELEASE_HARNESS_GATE_IDS = new Set([
  'mcp-benchmarks',
  'release-scans',
]);
const SERVLET_DEFAULT_ARTIFACT_IDENTITY = 'com.soklet:soklet:3.1.1';
const SERVLET_DEFAULT_ARTIFACT_SHA256 =
  'a7acd26b5a8933726615719e8d9d766feba6d0ebdb32939fa8ef1eba8094e7a4';
const EXPECTED_GATE_IDS = [
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
  'soak-smoke',
  'release-soak',
  'localization-fleet',
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
];
const ALLOWED_GATE_KINDS = new Set([
  'SOURCE',
  'CANDIDATE_ARTIFACT',
  'DOWNSTREAM',
  'INTEROPERABILITY',
]);
const ALLOWED_GATE_STATUSES = new Set([
  'READY',
  'UNVERIFIED',
  'BLOCKED_REQUIRES_MIGRATION',
  'BLOCKED_UNCOMMITTED_LOCAL_MIGRATION',
  'BLOCKED_HARNESS_MISSING',
]);
const EXPECTED_GATE_CONTRACTS = Object.freeze({
  'candidate-build': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'com.soklet:soklet:4.0.0',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'core-jdk-21': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'maven:clean-test@jdk-21',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'coreJdk21',
    versionProperty: null,
  }),
  'core-jdk-25': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'maven:clean-test@jdk-25',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'toystoreJava',
    versionProperty: null,
  }),
  'isolated-install': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'org.apache.maven.plugins:maven-install-plugin:3.1.4',
    kind: 'CANDIDATE_ARTIFACT',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'api-freeze': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'scripts/verify-mcp-api-freezes.sh',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'candidate-javadocs': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'com.soklet:soklet:4.0.0:javadoc',
    kind: 'CANDIDATE_ARTIFACT',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'static-analysis': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'maven-profile:static-analysis',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'coreJdk21',
    versionProperty: null,
  }),
  spotbugs: Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'maven-profile:spotbugs',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'coreJdk21',
    versionProperty: null,
  }),
  'schema-replay': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'json-schema-test-suite:reviewed-pin',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'fuzz-replay': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'fuzz:checked-in-corpus',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'toystoreJava',
    versionProperty: null,
  }),
  'soak-smoke': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'SOKLET_SOAK_PROFILE=smoke',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'toystoreJava',
    versionProperty: null,
  }),
  'release-soak': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'SOKLET_SOAK_PROFILE=release',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'localization-fleet': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'McpLocalizationFleetPublicRuntimeTests',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'release-scans': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'ci:release-scans',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'coreJdk21',
    versionProperty: null,
  }),
  'mcp-benchmarks': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'benchmarks:mcp-json-schema',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'matrix-closure': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'mcp:conformance-matrix-closure',
    kind: 'SOURCE',
    repository: null,
    toolchain: 'nodePin',
    versionProperty: null,
  }),
  'candidate-conformance': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: '@modelcontextprotocol/conformance:0.2.0-alpha.10-descriptive',
    kind: 'CANDIDATE_ARTIFACT',
    repository: 'https://github.com/modelcontextprotocol/conformance.git',
    toolchain: 'nodePin',
    versionProperty: null,
  }),
  'candidate-localization': Object.freeze({
    access: 'LOCAL_CHECKOUT',
    artifactIdentity: 'verification/localization/generic-provider',
    kind: 'CANDIDATE_ARTIFACT',
    repository: null,
    toolchain: 'java',
    versionProperty: null,
  }),
  'barebones-app': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'soklet/barebones-app-source',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/soklet/barebones-app.git',
    toolchain: 'java',
    versionProperty: null,
  }),
  'soklet-servlet-javax': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'com.soklet:soklet-servlet-javax:1.2.0',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/soklet/soklet-servlet-javax.git',
    toolchain: 'java',
    versionProperty: 'soklet.version',
  }),
  'soklet-servlet-jakarta': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'com.soklet:soklet-servlet-jakarta:1.2.0',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/soklet/soklet-servlet-jakarta.git',
    toolchain: 'java',
    versionProperty: 'soklet.version',
  }),
  'toystore-app': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'com.soklet.toystore:toystore:1.0.0',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/soklet/toystore-app.git',
    toolchain: 'toystoreJava',
    versionProperty: 'soklet.version',
  }),
  'soklet-otel': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'com.soklet:soklet-otel:1.4.0-SNAPSHOT',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/soklet/soklet-otel.git',
    toolchain: 'java',
    versionProperty: 'soklet.version',
  }),
  'soklet-website': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'revetware/soklet.com-source',
    kind: 'DOWNSTREAM',
    repository: 'https://github.com/revetware/soklet.com.git',
    toolchain: 'nodePin',
    versionProperty: null,
  }),
  'typescript-interop': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'npm:@modelcontextprotocol/client@2.0.0',
    kind: 'INTEROPERABILITY',
    repository: 'https://github.com/modelcontextprotocol/typescript-sdk.git',
    toolchain: 'nodePin',
    versionProperty: null,
  }),
  'go-interop': Object.freeze({
    access: 'PUBLIC_READ_ONLY',
    artifactIdentity: 'github.com/modelcontextprotocol/go-sdk@v1.7.0',
    kind: 'INTEROPERABILITY',
    repository: 'https://github.com/modelcontextprotocol/go-sdk.git',
    toolchain: 'go',
    versionProperty: null,
  }),
});
const GATE_KEYS = [
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
];

function evidenceRole(role, type, mediaType, fileName, candidateArtifact = null) {
  return Object.freeze({ candidateArtifact, fileName, mediaType, role, type });
}

const fileRole = (role, mediaType, fileName, candidateArtifact = null) =>
  evidenceRole(role, 'FILE', mediaType, fileName, candidateArtifact);
const directoryRole = (role, mediaType, fileName) =>
  evidenceRole(role, 'DIRECTORY', mediaType, fileName);
const logRole = (role, fileName) => fileRole(role, 'text/plain', fileName);
const surefireRole = (role = 'surefire-reports', fileName = 'surefire-reports') =>
  directoryRole(role, 'application/vnd.soklet.surefire-reports', fileName);

function gateEvidenceContract(
  gateId,
  toolchain,
  command,
  profile,
  expectation,
  roles,
  contractVersion = 1,
) {
  return Object.freeze({
    command,
    contractId: `soklet.release.${gateId}.v${contractVersion}`,
    expectation,
    profile,
    roles: Object.freeze(roles),
    toolchain,
  });
}

/**
 * Validator-owned release-gate evidence contracts. This table is intentionally
 * executable policy rather than manifest-provided data: changing a manifest
 * cannot weaken the retained evidence required for a gate.
 */
export const EXPECTED_GATE_EVIDENCE_CONTRACTS = Object.freeze({
  'candidate-build': gateEvidenceContract(
    'candidate-build',
    'java',
    'mvn -B -ntp -Dgpg.skip=true clean verify',
    'release-candidate',
    'BUILD_SUCCESS_AND_CANDIDATE_ARTIFACTS_RECORDED',
    [
      fileRole('artifact-descriptor', 'application/json', 'candidate-artifacts.json', 'descriptor'),
      logRole('build-log', 'candidate-build.log'),
      surefireRole(),
      fileRole('node-distribution', 'text/plain', 'release-validation-node-distribution.txt'),
      fileRole('maven-distribution', 'text/plain', 'release-validation-maven-distribution.txt'),
      fileRole('go-distribution', 'text/plain', 'release-validation-go-distribution.txt'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-java-distribution.txt',
        'gateToolchainDistribution',
      ),
    ],
  ),
  'core-jdk-21': gateEvidenceContract(
    'core-jdk-21',
    'coreJdk21',
    'mvn -B -ntp -Dgpg.skip=true clean test',
    'jdk-21',
    'TESTS_PASS_WITH_ZERO_ERRORS_AND_FAILURES',
    [
      logRole('build-log', 'core-jdk-21.log'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-core-jdk-21-distribution.txt',
        'gateToolchainDistribution',
      ),
      surefireRole(),
    ],
  ),
  'core-jdk-25': gateEvidenceContract(
    'core-jdk-25',
    'toystoreJava',
    'mvn -B -ntp -Dgpg.skip=true clean test',
    'jdk-25',
    'TESTS_PASS_WITH_ZERO_ERRORS_AND_FAILURES',
    [
      logRole('build-log', 'core-jdk-25.log'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-toystore-java-distribution.txt',
        'gateToolchainDistribution',
      ),
      surefireRole(),
    ],
  ),
  'isolated-install': gateEvidenceContract(
    'isolated-install',
    'java',
    'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
    'isolated-repository',
    'INSTALLED_POM_AND_MAIN_JAR_MATCH_CANDIDATE_BYTES',
    [
      fileRole('installed-pom', 'application/xml', 'soklet-4.0.0.pom', 'pom'),
      fileRole('installed-main-jar', 'application/java-archive', 'soklet-4.0.0.jar', 'mainJar'),
      logRole('install-log', 'isolated-install.log'),
    ],
  ),
  'api-freeze': gateEvidenceContract(
    'api-freeze',
    'java',
    'scripts/verify-mcp-api-freezes.sh',
    'mcp-api-freeze',
    'REVIEWED_BIDIRECTIONAL_API_SET_AND_SIGNATURES_MATCH',
    [
      logRole('api-freeze-log', 'api-freeze.log'),
      fileRole('japicmp-diff', 'application/xml', 'mcp-api-diff.xml'),
      fileRole(
        'japicmp-incompatibilities',
        'application/x-ndjson',
        'mcp-api-diff.incompatibilities.jsonl',
      ),
      fileRole('api-freeze-report', 'application/xml', 'mcp-api-freeze.xml'),
      directoryRole('signatures', 'application/vnd.soklet.api-signatures', 'mcp-api-freezes'),
    ],
  ),
  'candidate-javadocs': gateEvidenceContract(
    'candidate-javadocs',
    'java',
    'mvn -B -ntp -Dgpg.skip=true -Dtest=McpPublicJavadocTests clean package javadoc:javadoc',
    'public-javadocs',
    'PUBLIC_JAVADOC_INVENTORY_JAR_AND_STANDALONE_DOCLINT_PASS',
    [
      logRole('javadoc-log', 'candidate-javadocs.log'),
      fileRole(
        'javadoc-jar',
        'application/java-archive',
        'soklet-4.0.0-javadoc.jar',
        'javadocJar',
      ),
      directoryRole('apidocs', 'text/html', 'apidocs'),
      surefireRole(),
    ],
  ),
  'static-analysis': gateEvidenceContract(
    'static-analysis',
    'coreJdk21',
    'mvn -B -ntp -Dgpg.skip=true -Pstatic-analysis clean compile',
    'static-analysis',
    'BUILD_SUCCESS',
    [
      logRole('analysis-log', 'static-analysis.log'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-core-jdk-21-distribution.txt',
        'gateToolchainDistribution',
      ),
    ],
  ),
  spotbugs: gateEvidenceContract(
    'spotbugs',
    'coreJdk21',
    'mvn -B -ntp -Dgpg.skip=true -Pspotbugs -DskipTests clean compile spotbugs:check',
    'spotbugs',
    'ZERO_SPOTBUGS_FINDINGS',
    [
      logRole('spotbugs-log', 'spotbugs.log'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-core-jdk-21-distribution.txt',
        'gateToolchainDistribution',
      ),
      fileRole('spotbugs-report', 'application/xml', 'spotbugsXml.xml'),
    ],
  ),
  'schema-replay': gateEvidenceContract(
    'schema-replay',
    'java',
    'mvn -B -ntp -Dgpg.skip=true -Dtest=JsonSchemaTestSuitePinTests,McpToolSchemaProfile* test',
    'profile-1-replay',
    'SELECTED_SCHEMA_CORPUS_AND_PROFILE_TESTS_PASS',
    [logRole('replay-log', 'schema-replay.log'), surefireRole()],
  ),
  'fuzz-replay': gateEvidenceContract(
    'fuzz-replay',
    'toystoreJava',
    'mvn -B -ntp -f fuzz/pom.xml clean test; node scripts/verify-json-corpus.mjs',
    'checked-in-corpus',
    'ALL_CHECKED_IN_FUZZ_CORPORA_PASS',
    [logRole('replay-log', 'fuzz-replay.log'), surefireRole()],
  ),
  'soak-smoke': gateEvidenceContract(
    'soak-smoke',
    'toystoreJava',
    'SOKLET_SOAK_PROFILE=smoke mvn -B -ntp -f soak/pom.xml clean test',
    'smoke',
    'SOAK_REPORT_AND_SUREFIRE_PASS_WITHIN_PROFILE_LIMITS',
    [
      logRole('soak-log', 'soak-smoke.log'),
      fileRole('soak-report', 'text/markdown', 'soak-report.md'),
      surefireRole(),
    ],
  ),
  'release-soak': gateEvidenceContract(
    'release-soak',
    'java',
    'SOKLET_SOAK_PROFILE=release mvn -B -ntp -f soak/pom.xml clean test',
    'release',
    'SOAK_REPORT_AND_SUREFIRE_PASS_WITHIN_PROFILE_LIMITS',
    [
      fileRole('soak-report', 'text/markdown', 'soak-report.md'),
      surefireRole(),
      logRole('soak-log', 'release-soak.log'),
    ],
  ),
  'localization-fleet': gateEvidenceContract(
    'localization-fleet',
    'java',
    'mvn -B -ntp -Dtest=McpLocalizationFleetPublicRuntimeTests test',
    'two-listener-fleet',
    'FAILED_RELOAD_ROLLING_DRIFT_NODE_LOSS_RECONNECT_AND_CLEANUP_PASS',
    [logRole('fleet-log', 'localization-fleet.log'), surefireRole()],
  ),
  'release-scans': gateEvidenceContract(
    'release-scans',
    'coreJdk21',
    'node scripts/verify-release-scans.mjs',
    'release',
    'REQUIRED_RELEASE_SCANS_PASS_WITH_ZERO_UNACCEPTED_FINDINGS',
    [
      fileRole('scan-summary', 'application/json', 'release-scans.json'),
      directoryRole('scan-reports', 'application/vnd.soklet.release-scans.v1', 'release-scans'),
    ],
  ),
  'mcp-benchmarks': gateEvidenceContract(
    'mcp-benchmarks',
    'java',
    'mvn -B -ntp -f benchmarks/pom.xml clean verify; node scripts/verify-release-benchmarks.mjs',
    'release',
    'JMH_JSON_351_COMPARISON_AND_SCHEMA_400_BASELINE_RECORDED_WITH_SIGNOFF',
    [
      fileRole('benchmark-results', 'application/json', 'mcp-benchmarks.json'),
      logRole('benchmark-log', 'mcp-benchmarks.log'),
    ],
  ),
  'matrix-closure': gateEvidenceContract(
    'matrix-closure',
    'nodePin',
    'node scripts/verify-release-matrix-closure.mjs',
    'release',
    'ZERO_UNRESOLVED_IN_SCOPE_MATRIX_ROWS',
    [fileRole('matrix-report', 'application/json', 'matrix-closure.json')],
    2,
  ),
  'candidate-conformance': gateEvidenceContract(
    'candidate-conformance',
    'nodePin',
    'node conformance/official/run.mjs --phase 5 --mode release',
    'release',
    'ALL_39_CAPABILITY_SELECTED_SCENARIOS_PASS',
    [directoryRole(
      'conformance-evidence',
      'application/vnd.soklet.conformance-evidence',
      'release',
    )],
  ),
  'candidate-localization': gateEvidenceContract(
    'candidate-localization',
    'java',
    'verification/localization/verify.sh',
    'generic-provider',
    'CANDIDATE_ARTIFACT_LOCALIZATION_PROVIDER_PASSES',
    [logRole('localization-log', 'candidate-localization.log')],
  ),
  'barebones-app': gateEvidenceContract(
    'barebones-app',
    'java',
    'javac --release 17; live loopback probes; clean shutdown',
    'candidate',
    'COMPILE_START_RESPOND_TERMINATE_AND_RELEASE_PORT',
    [
      fileRole('port-file', 'text/plain', 'barebones-loopback-port.txt'),
      logRole('reservation-log', 'barebones-port-reservation.log'),
      logRole('runtime-log', 'barebones-app.log'),
    ],
  ),
  'soklet-servlet-javax': gateEvidenceContract(
    'soklet-servlet-javax',
    'java',
    'mvn -B -ntp clean verify; mvn -B -ntp -Dsoklet.version=4.0.0 clean verify',
    'default-and-candidate',
    'DEFAULT_AND_CANDIDATE_LEGS_PASS_WITH_EXACT_ARTIFACTS',
    [
      fileRole('project-pom', 'application/xml', 'pom.xml'),
      fileRole(
        'default-jar',
        'application/java-archive',
        'soklet-3.1.1.jar',
        'gateDefaultArtifact',
      ),
      logRole('default-log', 'soklet-servlet-javax-default.log'),
      surefireRole('default-surefire-reports', 'soklet-servlet-javax-default-surefire-reports'),
      logRole('candidate-log', 'soklet-servlet-javax-candidate.log'),
      surefireRole('candidate-surefire-reports'),
    ],
  ),
  'soklet-servlet-jakarta': gateEvidenceContract(
    'soklet-servlet-jakarta',
    'java',
    'mvn -B -ntp clean verify; mvn -B -ntp -Dsoklet.version=4.0.0 clean verify',
    'default-and-candidate',
    'DEFAULT_AND_CANDIDATE_LEGS_PASS_WITH_EXACT_ARTIFACTS',
    [
      fileRole('project-pom', 'application/xml', 'pom.xml'),
      fileRole(
        'default-jar',
        'application/java-archive',
        'soklet-3.1.1.jar',
        'gateDefaultArtifact',
      ),
      logRole('default-log', 'soklet-servlet-jakarta-default.log'),
      surefireRole(
        'default-surefire-reports',
        'soklet-servlet-jakarta-default-surefire-reports',
      ),
      logRole('candidate-log', 'soklet-servlet-jakarta-candidate.log'),
      surefireRole('candidate-surefire-reports'),
    ],
  ),
  'toystore-app': gateEvidenceContract(
    'toystore-app',
    'toystoreJava',
    'mvn -B -ntp -Dsoklet.version=4.0.0 clean verify',
    'candidate',
    'CANDIDATE_LEG_PASSES_WITH_EXACT_ARTIFACT',
    [
      fileRole('project-pom', 'application/xml', 'pom.xml'),
      logRole('candidate-log', 'toystore-app-candidate.log'),
      surefireRole('candidate-surefire-reports'),
      fileRole(
        'java-distribution',
        'text/plain',
        'release-validation-toystore-java-distribution.txt',
        'gateToolchainDistribution',
      ),
    ],
  ),
  'soklet-otel': gateEvidenceContract(
    'soklet-otel',
    'java',
    'mvn -B -ntp -Dsoklet.version=4.0.0 clean verify',
    'candidate',
    'CANDIDATE_LEG_PASSES_WITH_EXACT_ARTIFACT',
    [
      fileRole('project-pom', 'application/xml', 'pom.xml'),
      logRole('candidate-log', 'soklet-otel-candidate.log'),
      surefireRole('candidate-surefire-reports'),
    ],
  ),
  'soklet-website': gateEvidenceContract(
    'soklet-website',
    'nodePin',
    'npm ci --ignore-scripts; npm run lint; npm run ssg-build',
    'candidate-documentation',
    'CLEAN_INSTALL_LINT_AND_STATIC_BUILD_PASS',
    [
      logRole('build-log', 'soklet-website.log'),
      directoryRole('distribution', 'application/vnd.soklet.site-distribution', 'dist'),
    ],
  ),
  'typescript-interop': gateEvidenceContract(
    'typescript-interop',
    'nodePin',
    'verification/interoperability/typescript/verify.sh',
    'tools-list',
    'PINNED_SDK_TOOLS_LIST_FIXTURE_PASSES_AND_SHUTS_DOWN_CLEANLY',
    [
      logRole('interop-log', 'typescript-interop.log'),
      fileRole(
        'candidate-main-jar',
        'application/java-archive',
        'soklet-4.0.0.jar',
        'mainJar',
      ),
    ],
  ),
  'go-interop': gateEvidenceContract(
    'go-interop',
    'go',
    'verification/interoperability/go/verify.sh',
    'tools-list',
    'PINNED_SDK_TOOLS_LIST_FIXTURE_PASSES_AND_SHUTS_DOWN_CLEANLY',
    [
      logRole('interop-log', 'go-interop.log'),
      fileRole(
        'candidate-main-jar',
        'application/java-archive',
        'soklet-4.0.0.jar',
        'mainJar',
      ),
    ],
  ),
});

function fail(message) {
  throw new Error(message);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function requireExactKeys(value, keys, description) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${description} must be an object`);

  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();

  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${description} keys must be exactly: ${expected.join(', ')}`);
}

function requireString(value, description) {
  if (typeof value !== 'string' || value === '' || value.trim() !== value)
    fail(`${description} must be a non-empty, trimmed string`);
}

function requireNullableString(value, description) {
  if (value !== null)
    requireString(value, description);
}

function readRealFile(path, description) {
  const absolutePath = resolve(path);

  if (!existsSync(absolutePath))
    fail(`Missing ${description}: ${absolutePath}`);

  const stats = lstatSync(absolutePath);

  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`${description} must be a regular, nonsymlink file: ${absolutePath}`);

  return { absolutePath, bytes: readFileSync(absolutePath) };
}

function readJson(path, description) {
  const { absolutePath, bytes } = readRealFile(path, description);
  const text = bytes.toString('utf8');

  if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
    fail(`${description} must be UTF-8: ${absolutePath}`);

  let value;

  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${description} is not valid JSON: ${error instanceof Error ? error.message : error}`);
  }

  return { absolutePath, bytes, value };
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

function writeCanonicalJson(path, value) {
  const absolutePath = resolve(path);

  if (existsSync(absolutePath))
    fail(`Refusing to overwrite evidence file: ${absolutePath}`);

  mkdirSync(dirname(absolutePath), { recursive: true });
  writeFileSync(
    absolutePath,
    `${JSON.stringify(canonicalize(value), null, 2)}\n`,
    { encoding: 'utf8', flag: 'wx' },
  );
  return absolutePath;
}

function validateCandidate(candidate) {
  requireExactKeys(candidate, ['artifactId', 'groupId', 'packaging', 'version'], 'candidate');

  if (candidate.groupId !== 'com.soklet'
      || candidate.artifactId !== 'soklet'
      || candidate.version !== '4.0.0'
      || candidate.packaging !== 'jar') {
    fail('Candidate coordinates must be exactly com.soklet:soklet:4.0.0 with JAR packaging');
  }
}

function validateCorrettoToolchain(toolchain, major, description) {
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

  const versionMatch = new RegExp(
    major === 21
      ? `^${major}\\.0\\.([0-9]+)(?:\\.([0-9]+))?$`
      : `^${major}\\.0\\.([0-9]+)$`,
  ).exec(toolchain.version);
  const vendorVersionMatch = new RegExp(
    `^Corretto-${major}\\.0\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)$`,
  ).exec(toolchain.vendorVersion);

  if (toolchain.distribution !== 'corretto'
      || versionMatch === null || vendorVersionMatch === null
      || versionMatch[1] !== vendorVersionMatch[1]
      || (versionMatch[2] !== undefined
        && versionMatch[2] !== vendorVersionMatch[3])) {
    fail(`${description} must pin an exact Corretto ${major} build`);
  }

  const distributionVersion = toolchain.vendorVersion.slice('Corretto-'.length);
  const expectedRuntimeVersion = `${toolchain.version}+${vendorVersionMatch[2]}-LTS`;
  const expectedArchive = `amazon-corretto-${distributionVersion}-linux-x64.tar.gz`;
  const expectedUrl = `https://corretto.aws/downloads/resources/${distributionVersion}/${expectedArchive}`;

  if (toolchain.runtimeVersion !== expectedRuntimeVersion)
    fail(`${description} runtime version must be exactly ${expectedRuntimeVersion}`);

  if (toolchain.archive !== expectedArchive)
    fail(`${description} archive must be exactly ${expectedArchive}`);

  if (toolchain.distributionUrl !== expectedUrl)
    fail(`${description} distribution URL must be exactly ${expectedUrl}`);

  if (!SHA256_PATTERN.test(toolchain.archiveSha256))
    fail(`${description} archive SHA-256 must contain exactly 64 lowercase hexadecimal characters`);
}

function validateToolchains(toolchains, projectRoot) {
  requireExactKeys(
    toolchains,
    [
      'coreJdk21',
      'go',
      'java',
      'maven',
      'nodePin',
      'releaseSoakTimeoutSeconds',
      'toystoreJava',
    ],
    'toolchains',
  );
  requireExactKeys(
    toolchains.go,
    ['archive', 'archiveSha256', 'distributionUrl', 'version'],
    'Go toolchain',
  );
  requireExactKeys(
    toolchains.maven,
    [
      'archive',
      'archiveSha512',
      'distributionUrl',
      'installFileGoal',
      'version',
    ],
    'Maven toolchain',
  );
  requireExactKeys(toolchains.nodePin, ['path', 'sha256'], 'Node toolchain pin');

  validateCorrettoToolchain(toolchains.java, 17, 'Candidate Java toolchain');
  if (toolchains.coreJdk21 !== null)
    validateCorrettoToolchain(toolchains.coreJdk21, 21, 'Core JDK 21 toolchain');
  validateCorrettoToolchain(toolchains.toystoreJava, 25, 'ToyStore Java toolchain');

  if (!/^1\.25\.[0-9]+$/.test(toolchains.go.version))
    fail('Go toolchain must pin an exact 1.25.x version');

  const expectedGoArchive = `go${toolchains.go.version}.linux-amd64.tar.gz`;
  if (toolchains.go.archive !== expectedGoArchive)
    fail(`Go archive must be exactly ${expectedGoArchive}`);

  const expectedGoUrl = `https://go.dev/dl/${expectedGoArchive}`;
  if (toolchains.go.distributionUrl !== expectedGoUrl)
    fail(`Go distribution URL must be exactly ${expectedGoUrl}`);

  if (!SHA256_PATTERN.test(toolchains.go.archiveSha256))
    fail('Go archive SHA-256 must contain exactly 64 lowercase hexadecimal characters');

  if (!/^3\.9\.[0-9]+$/.test(toolchains.maven.version))
    fail('Maven toolchain must pin an exact 3.9.x version');

  const expectedMavenArchive = `apache-maven-${toolchains.maven.version}-bin.tar.gz`;
  if (toolchains.maven.archive !== expectedMavenArchive)
    fail(`Maven archive must be exactly ${expectedMavenArchive}`);

  const expectedMavenUrl = `https://dlcdn.apache.org/maven/maven-3/${toolchains.maven.version}/binaries/${expectedMavenArchive}`;
  if (toolchains.maven.distributionUrl !== expectedMavenUrl)
    fail(`Maven distribution URL must be exactly ${expectedMavenUrl}`);

  if (!/^[0-9a-f]{128}$/.test(toolchains.maven.archiveSha512))
    fail('Maven archive SHA-512 must contain exactly 128 lowercase hexadecimal characters');

  if (!/^org\.apache\.maven\.plugins:maven-install-plugin:[0-9]+\.[0-9]+\.[0-9]+:install-file$/
    .test(toolchains.maven.installFileGoal)) {
    fail('Maven install-file goal must include an exact plugin version');
  }

  if (!Number.isSafeInteger(toolchains.releaseSoakTimeoutSeconds)
      || toolchains.releaseSoakTimeoutSeconds !== 3600) {
    fail('Release soak timeout must be exactly 3,600 seconds');
  }

  requireString(toolchains.nodePin.path, 'Node pin path');

  if (!SHA256_PATTERN.test(toolchains.nodePin.sha256))
    fail('Node pin SHA-256 must contain exactly 64 lowercase hexadecimal characters');

  const nodePinPath = resolve(projectRoot, toolchains.nodePin.path);
  const nodePin = readRealFile(nodePinPath, 'Node pin file');
  const actualNodePinSha256 = sha256(nodePin.bytes);

  if (actualNodePinSha256 !== toolchains.nodePin.sha256) {
    fail(`Node pin SHA-256 mismatch: expected ${toolchains.nodePin.sha256}, found ${actualNodePinSha256}`);
  }
}

function validateGate(gate, index, toolchains) {
  requireExactKeys(gate, GATE_KEYS, `gate ${index}`);
  requireString(gate.id, `gate ${index} id`);
  requireString(gate.kind, `gate ${gate.id} kind`);
  requireString(gate.status, `gate ${gate.id} status`);
  requireString(gate.access, `gate ${gate.id} access`);
  requireString(gate.evidenceContract, `gate ${gate.id} evidence contract`);
  requireString(gate.toolchain, `gate ${gate.id} toolchain`);
  requireNullableString(gate.artifactChecksum, `gate ${gate.id} artifact checksum`);
  requireString(gate.artifactIdentity, `gate ${gate.id} artifact identity`);
  requireNullableString(
    gate.defaultArtifactIdentity,
    `gate ${gate.id} default artifact identity`,
  );
  requireNullableString(
    gate.defaultArtifactSha256,
    `gate ${gate.id} default artifact SHA-256`,
  );
  requireNullableString(gate.repository, `gate ${gate.id} repository`);
  requireNullableString(gate.commit, `gate ${gate.id} commit`);
  requireNullableString(gate.versionProperty, `gate ${gate.id} version property`);

  if (!ALLOWED_GATE_KINDS.has(gate.kind))
    fail(`Unsupported gate kind for ${gate.id}: ${gate.kind}`);

  if (!ALLOWED_GATE_STATUSES.has(gate.status))
    fail(`Unsupported gate status for ${gate.id}: ${gate.status}`);

  if (gate.status === 'READY' && gate.reason !== '')
    fail(`READY gate ${gate.id} must have an empty reason`);

  if (gate.status !== 'READY')
    requireString(gate.reason, `non-ready gate ${gate.id} reason`);

  if (gate.repository === null && gate.commit !== null)
    fail(`Local gate ${gate.id} cannot declare an external commit`);

  if ((gate.repository === null && gate.access !== 'LOCAL_CHECKOUT')
      || (gate.repository !== null && gate.access !== 'PUBLIC_READ_ONLY')) {
    fail(`Gate ${gate.id} access mode does not match its repository ownership`);
  }

  if (gate.repository !== null && gate.status === 'READY'
      && (gate.commit === null || !COMMIT_PATTERN.test(gate.commit))) {
    fail(`READY external gate ${gate.id} must pin a full lowercase commit SHA`);
  }

  if (gate.commit !== null && !COMMIT_PATTERN.test(gate.commit))
    fail(`Gate ${gate.id} commit must be a full lowercase SHA`);

  const isServletGate = gate.id === 'soklet-servlet-javax'
    || gate.id === 'soklet-servlet-jakarta';
  const expectedDefaultArtifactIdentity = isServletGate
    ? SERVLET_DEFAULT_ARTIFACT_IDENTITY
    : null;
  const expectedDefaultArtifactSha256 = isServletGate
    ? SERVLET_DEFAULT_ARTIFACT_SHA256
    : null;
  if (gate.defaultArtifactIdentity !== expectedDefaultArtifactIdentity
      || gate.defaultArtifactSha256 !== expectedDefaultArtifactSha256) {
    fail(
      `Gate ${gate.id} default Soklet artifact pin differs from its canonical release contract`,
    );
  }
  if (gate.defaultArtifactSha256 !== null
      && !SHA256_PATTERN.test(gate.defaultArtifactSha256)) {
    fail(`Gate ${gate.id} default artifact SHA-256 must be 64 lowercase hexadecimal characters`);
  }

  if (gate.repository !== null && !/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\.git$/.test(gate.repository))
    fail(`Gate ${gate.id} must use an exact HTTPS GitHub repository URL`);

  const expectedContract = EXPECTED_GATE_CONTRACTS[gate.id];
  if (expectedContract === undefined)
    fail(`Gate ${gate.id} has no canonical release contract`);

  for (const field of [
    'access',
    'artifactIdentity',
    'kind',
    'repository',
    'toolchain',
    'versionProperty',
  ]) {
    if (gate[field] !== expectedContract[field]) {
      fail(
        `Gate ${gate.id} ${field} differs from its canonical release contract: `
          + `expected ${JSON.stringify(expectedContract[field])}, found ${JSON.stringify(gate[field])}`,
      );
    }
  }

  const expectedEvidenceContract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gate.id];
  if (expectedEvidenceContract === undefined)
    fail(`Gate ${gate.id} has no canonical evidence contract`);
  if (gate.evidenceContract !== expectedEvidenceContract.contractId) {
    fail(
      `Gate ${gate.id} evidenceContract differs from its canonical release contract: `
        + `expected ${JSON.stringify(expectedEvidenceContract.contractId)}, `
        + `found ${JSON.stringify(gate.evidenceContract)}`,
    );
  }
  if (gate.toolchain !== expectedEvidenceContract.toolchain)
    fail(`Gate ${gate.id} toolchain differs from its canonical evidence contract`);
  if (!(gate.toolchain in toolchains))
    fail(`Gate ${gate.id} refers to unknown toolchain ${gate.toolchain}`);
  if (gate.status === 'READY' && toolchains[gate.toolchain] === null)
    fail(`READY gate ${gate.id} cannot use unavailable toolchain ${gate.toolchain}`);

  if (gate.id === 'typescript-interop'
      && (gate.artifactIdentity !== 'npm:@modelcontextprotocol/client@2.0.0'
        || !/^sha512-[A-Za-z0-9+/]{86}==$/.test(gate.artifactChecksum ?? ''))) {
    fail('TypeScript interoperability must pin the 2.0.0 client and its npm SHA-512 integrity');
  }

  if (gate.id === 'go-interop'
      && (gate.artifactIdentity !== 'github.com/modelcontextprotocol/go-sdk@v1.7.0'
        || !/^h1:[A-Za-z0-9+/]{43}=$/.test(gate.artifactChecksum ?? ''))) {
    fail('Go interoperability must pin go-sdk v1.7.0 and its module h1 checksum');
  }
}

function requireUtf8Text(file, description) {
  const text = file.bytes.toString('utf8');

  if (Buffer.from(text, 'utf8').compare(file.bytes) !== 0)
    fail(`${description} must be UTF-8: ${file.absolutePath}`);

  if (text.includes('\r'))
    fail(`${description} must use LF line endings: ${file.absolutePath}`);

  return text;
}

function validateTypeScriptInteropPin(gate, projectRoot) {
  const dependencyName = '@modelcontextprotocol/client';
  const dependencyVersion = '2.0.0';
  const dependencyPath = `node_modules/${dependencyName}`;
  const expectedResolved = `https://registry.npmjs.org/${dependencyName}/-/client-${dependencyVersion}.tgz`;
  const packageDirectory = resolve(
    projectRoot,
    'verification/interoperability/typescript',
  );
  const packageManifest = readJson(
    resolve(packageDirectory, 'package.json'),
    'TypeScript interoperability package manifest',
  );
  const packageLock = readJson(
    resolve(packageDirectory, 'package-lock.json'),
    'TypeScript interoperability package lock',
  );

  if (packageManifest.value?.dependencies?.[dependencyName] !== dependencyVersion) {
    fail(`TypeScript interoperability package.json must depend on exactly ${dependencyName}@${dependencyVersion}`);
  }

  if (packageLock.value?.lockfileVersion !== 3)
    fail('TypeScript interoperability package-lock.json must use lockfileVersion 3');

  if (packageLock.value?.packages?.['']?.dependencies?.[dependencyName] !== dependencyVersion) {
    fail(`TypeScript interoperability lock root must depend on exactly ${dependencyName}@${dependencyVersion}`);
  }

  const lockedDependency = packageLock.value?.packages?.[dependencyPath];

  if (lockedDependency?.version !== dependencyVersion
      || lockedDependency?.resolved !== expectedResolved) {
    fail(`TypeScript interoperability lock must resolve exactly ${dependencyName}@${dependencyVersion} from ${expectedResolved}`);
  }

  if (lockedDependency.integrity !== gate.artifactChecksum) {
    fail(`TypeScript interoperability manifest checksum does not match package-lock.json: expected ${gate.artifactChecksum}, found ${lockedDependency.integrity ?? 'missing'}`);
  }
}

function validateGoInteropPin(gate, projectRoot) {
  const moduleName = 'github.com/modelcontextprotocol/go-sdk';
  const moduleVersion = 'v1.7.0';
  const moduleDirectory = resolve(projectRoot, 'verification/interoperability/go');
  const goMod = readRealFile(resolve(moduleDirectory, 'go.mod'), 'Go interoperability module manifest');
  const goSum = readRealFile(resolve(moduleDirectory, 'go.sum'), 'Go interoperability module sums');
  const goModLines = requireUtf8Text(goMod, 'Go interoperability module manifest')
    .split('\n')
    .map((line) => line.replace(/\s*\/\/.*$/, '').trim())
    .filter((line) => line.includes(moduleName));
  const expectedRequire = `require ${moduleName} ${moduleVersion}`;

  if (goModLines.length !== 1 || goModLines[0] !== expectedRequire) {
    fail(`Go interoperability go.mod must contain exactly one direct declaration: ${expectedRequire}`);
  }

  const goSumLines = requireUtf8Text(goSum, 'Go interoperability module sums')
    .split('\n')
    .filter((line) => line.startsWith(`${moduleName} ${moduleVersion} `)
      && !line.startsWith(`${moduleName} ${moduleVersion}/go.mod `));
  const expectedSum = `${moduleName} ${moduleVersion} ${gate.artifactChecksum}`;

  if (goSumLines.length !== 1 || goSumLines[0] !== expectedSum) {
    fail(`Go interoperability manifest checksum must match exactly one go.sum entry: ${expectedSum}`);
  }
}

function validateInteroperabilityPins(gates, projectRoot) {
  const typeScript = gates.find(({ id }) => id === 'typescript-interop');
  const go = gates.find(({ id }) => id === 'go-interop');

  if (typeScript === undefined || go === undefined)
    fail('Manifest must declare both interoperability gates');

  validateTypeScriptInteropPin(typeScript, projectRoot);
  validateGoInteropPin(go, projectRoot);
}

function validatePromotionPins(promotion, projectRoot) {
  requireExactKeys(promotion, ['helper', 'wrapper'], 'promotion tools');
  const expected = {
    helper: 'scripts/release-promotion.mjs',
    wrapper: 'scripts/promote-release-candidate.sh',
  };
  for (const [name, expectedPath] of Object.entries(expected)) {
    const pin = promotion[name];
    requireExactKeys(pin, ['path', 'sha256'], `promotion ${name}`);
    if (pin.path !== expectedPath)
      fail(`Promotion ${name} path must be exactly ${expectedPath}`);
    if (!SHA256_PATTERN.test(pin.sha256))
      fail(`Promotion ${name} SHA-256 must contain 64 lowercase hexadecimal characters`);
    const tool = readRealFile(resolve(projectRoot, pin.path), `promotion ${name}`);
    if (sha256(tool.bytes) !== pin.sha256)
      fail(`Promotion ${name} does not match its reviewed SHA-256`);
  }
}

export function validateReleaseConfiguration(path, { requireReady = false } = {}) {
  const config = readJson(path, 'release-validation manifest');
  const projectRoot = resolve(dirname(config.absolutePath), '..');
  requireExactKeys(
    config.value,
    ['candidate', 'formatVersion', 'gates', 'promotion', 'toolchains'],
    'manifest',
  );

  if (config.value.formatVersion !== 2)
    fail('Release-validation manifest formatVersion must be 2');

  validateCandidate(config.value.candidate);
  validateToolchains(config.value.toolchains, projectRoot);

  if (!Array.isArray(config.value.gates))
    fail('Manifest gates must be an array');

  config.value.gates.forEach((gate, index) => validateGate(gate, index, config.value.toolchains));
  const actualIds = config.value.gates.map(({ id }) => id);

  if (new Set(actualIds).size !== actualIds.length
      || JSON.stringify(actualIds) !== JSON.stringify(EXPECTED_GATE_IDS)) {
    fail(`Manifest gate IDs and order must be exactly: ${EXPECTED_GATE_IDS.join(', ')}`);
  }

  validateInteroperabilityPins(config.value.gates, projectRoot);
  validatePromotionPins(config.value.promotion, projectRoot);

  const blocked = config.value.gates.filter(({ status }) => status !== 'READY');

  if (requireReady && blocked.length > 0) {
    fail(`Release manifest is not runnable: ${blocked.map(({ id, status }) => `${id}=${status}`).join(', ')}`);
  }

  return Object.freeze({
    ...config,
    projectRoot,
    sha256: sha256(config.bytes),
    candidate: Object.freeze({ ...config.value.candidate }),
    gates: Object.freeze(config.value.gates.map((gate) => Object.freeze({ ...gate }))),
    promotion: Object.freeze(config.value.promotion),
    toolchains: Object.freeze(config.value.toolchains),
  });
}

function evidenceForPath(path) {
  const absolutePath = resolve(path);

  if (!existsSync(absolutePath))
    fail(`Missing evidence path: ${absolutePath}`);

  const stats = lstatSync(absolutePath);

  if (stats.isSymbolicLink())
    fail(`Evidence path cannot be a symbolic link: ${absolutePath}`);

  if (stats.isFile()) {
    const bytes = readFileSync(absolutePath);
    return Object.freeze({
      bytes: bytes.length,
      fileName: basename(absolutePath),
      sha256: sha256(bytes),
      type: 'FILE',
    });
  }

  if (!stats.isDirectory())
    fail(`Evidence path must be a regular file or directory: ${absolutePath}`);

  const paths = [];

  function visit(directory) {
    for (const name of readdirSync(directory).sort()) {
      const child = resolve(directory, name);
      const childStats = lstatSync(child);

      if (childStats.isSymbolicLink())
        fail(`Evidence directory cannot contain a symbolic link: ${child}`);

      if (childStats.isDirectory())
        visit(child);
      else if (childStats.isFile())
        paths.push(child);
      else
        fail(`Evidence directory contains an unsupported entry: ${child}`);
    }
  }

  visit(absolutePath);

  if (paths.length === 0)
    fail(`Evidence directory cannot be empty: ${absolutePath}`);

  const rows = paths.map((file) => {
    const normalized = relative(absolutePath, file).split(sep).join('/');
    return `${sha256(readFileSync(file))}  ${normalized}\n`;
  }).join('');

  return Object.freeze({
    algorithm: "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows",
    fileCount: paths.length,
    fileName: basename(absolutePath),
    sha256: sha256(Buffer.from(rows, 'utf8')),
    type: 'DIRECTORY',
  });
}

function requireTrackedCandidateFile(projectRoot, relativePath, description) {
  const file = readRealFile(resolve(projectRoot, relativePath), description);
  const tracked = spawnSync(
    'git',
    [
      '-c',
      `safe.directory=${projectRoot}`,
      '-C',
      projectRoot,
      'ls-files',
      '--error-unmatch',
      '--',
      relativePath,
    ],
    { encoding: 'utf8' },
  );
  if (tracked.error !== undefined) {
    fail(`Unable to inspect ${description} tracking: ${tracked.error.message}`);
  }
  if (tracked.status !== 0)
    fail(`${description} must be tracked by the candidate commit`);
  return file;
}

function expectedMatrixClosureReport(config) {
  const registry = requireTrackedCandidateFile(
    config.projectRoot,
    MATRIX_CLOSURE_REGISTRY_PATH,
    'matrix-closure registry',
  );
  const residualEvidence = requireTrackedCandidateFile(
    config.projectRoot,
    MATRIX_CLOSURE_RESIDUAL_EVIDENCE_PATH,
    'matrix-closure residual evidence',
  );
  const verifier = requireTrackedCandidateFile(
    config.projectRoot,
    MATRIX_CLOSURE_VERIFIER_PATH,
    'matrix-closure verifier',
  );
  const executingVerifier = readRealFile(
    resolve(dirname(fileURLToPath(import.meta.url)), 'verify-release-matrix-closure.mjs'),
    'executing matrix-closure verifier',
  );
  if (verifier.bytes.compare(executingVerifier.bytes) !== 0) {
    fail('Tracked matrix-closure verifier does not match the executing verifier');
  }

  let result;
  try {
    result = verifyMatrixClosure({
      manifestPath: config.absolutePath,
      projectRoot: config.projectRoot,
      registryPath: registry.absolutePath,
      residualEvidencePath: residualEvidence.absolutePath,
    });
  } catch (error) {
    fail(
      `Matrix-closure registry verification failed: ${error instanceof Error
        ? error.message : error}`,
    );
  }
  if (result.exitCode !== 0) {
    fail(
      `Matrix-closure registry is not closed: `
        + `${result.report.unresolvedRows.length} unresolved row(s)`,
    );
  }
  if (result.report.registrySha256 !== sha256(registry.bytes)) {
    fail('Matrix-closure registry changed while its canonical report was derived');
  }
  if (result.report.residualSha256 !== sha256(residualEvidence.bytes)) {
    fail('Matrix-closure residual evidence changed while its canonical report was derived');
  }
  return Buffer.from(result.reportText, 'utf8');
}

function validateMatrixClosureReport(config, path, description) {
  const report = readRealFile(path, description);
  const expected = expectedMatrixClosureReport(config);
  if (report.bytes.compare(expected) !== 0) {
    fail(
      `${description} must exactly match the canonical PASSED report derived `
        + 'from the tracked matrix-closure registry and verifier',
    );
  }
  return Object.freeze({
    bytes: report.bytes.length,
    fileName: basename(report.absolutePath),
    sha256: sha256(report.bytes),
    type: 'FILE',
  });
}

function requireCommit(commit, description = 'candidate commit') {
  if (!COMMIT_PATTERN.test(commit))
    fail(`${description} must be a full lowercase commit SHA`);
}

function validateEvidenceItem(item, description) {
  if (item?.type === 'FILE') {
    requireExactKeys(item, ['bytes', 'fileName', 'sha256', 'type'], description);
    if (!Number.isSafeInteger(item.bytes) || item.bytes <= 0)
      fail(`${description} byte count must be a positive safe integer`);
  } else if (item?.type === 'DIRECTORY') {
    requireExactKeys(
      item,
      ['algorithm', 'fileCount', 'fileName', 'sha256', 'type'],
      description,
    );
    if (!Number.isSafeInteger(item.fileCount) || item.fileCount <= 0)
      fail(`${description} file count must be a positive safe integer`);
    requireString(item.algorithm, `${description} algorithm`);
  } else {
    fail(`${description} must describe a FILE or DIRECTORY`);
  }

  requireString(item.fileName, `${description} file name`);
  if (basename(item.fileName) !== item.fileName)
    fail(`${description} file name must not contain a path`);
  if (!SHA256_PATTERN.test(item.sha256))
    fail(`${description} SHA-256 must contain 64 lowercase hexadecimal characters`);
}

function validateCandidateArtifactDescriptor(config, candidateCommit, path) {
  const descriptor = readJson(path, 'candidate artifact descriptor').value;
  requireExactKeys(
    descriptor,
    ['artifacts', 'candidateCommit', 'coordinates', 'formatVersion'],
    'candidate artifact descriptor',
  );
  requireExactKeys(
    descriptor.artifacts,
    ['javadocJar', 'mainJar', 'pom', 'sourcesJar'],
    'candidate artifact descriptor artifacts',
  );
  if (descriptor.formatVersion !== 1
      || descriptor.candidateCommit !== candidateCommit
      || JSON.stringify(descriptor.coordinates) !== JSON.stringify(config.candidate)) {
    fail('Candidate artifact descriptor identity does not match this validation run');
  }
  for (const [name, artifact] of Object.entries(descriptor.artifacts)) {
    validateEvidenceItem(artifact, `candidate artifact ${name}`);
    if (artifact.type !== 'FILE')
      fail(`Candidate artifact ${name} must be a file`);
  }
  return descriptor;
}

function validateEvidenceContent(path, specification, description) {
  const absolutePath = resolve(path);
  if (!existsSync(absolutePath))
    fail(`Missing ${description}: ${absolutePath}`);
  const stats = lstatSync(absolutePath);
  if (stats.isSymbolicLink())
    fail(`${description} cannot be a symbolic link: ${absolutePath}`);
  if ((specification.type === 'FILE' && !stats.isFile())
      || (specification.type === 'DIRECTORY' && !stats.isDirectory())) {
    fail(`${description} must be a ${specification.type}`);
  }
  if (basename(absolutePath) !== specification.fileName) {
    fail(
      `${description} basename must be exactly ${specification.fileName}, `
        + `found ${basename(absolutePath)}`,
    );
  }
  if (specification.type === 'DIRECTORY')
    return;

  const file = readRealFile(absolutePath, description);
  if (specification.mediaType === 'application/java-archive') {
    if (file.bytes.length < 4 || file.bytes[0] !== 0x50 || file.bytes[1] !== 0x4b)
      fail(`${description} is not a JAR/ZIP file`);
    return;
  }
  if (specification.mediaType === 'application/json') {
    readJson(absolutePath, description);
    return;
  }

  const text = requireUtf8Text(file, description);
  if (text.length === 0)
    fail(`${description} cannot be empty`);
  if (specification.mediaType === 'application/xml' && !text.trimStart().startsWith('<'))
    fail(`${description} is not recognizable XML`);
  if (specification.mediaType === 'application/x-ndjson') {
    const lines = text.split('\n').filter((line) => line !== '');
    if (lines.length === 0)
      fail(`${description} must contain at least one JSON line`);
    for (const [index, line] of lines.entries()) {
      try {
        JSON.parse(line);
      } catch (error) {
        fail(`${description} line ${index + 1} is not valid JSON`);
      }
    }
  }
}

function parseRolePaths(gateId, contract, rolePaths) {
  if (!Array.isArray(rolePaths))
    fail(`Gate ${gateId} evidence role paths must be an array`);
  const parsed = rolePaths.map((rolePath, index) => {
    requireString(rolePath, `gate ${gateId} role path ${index}`);
    const separator = rolePath.indexOf('=');
    if (separator <= 0 || separator === rolePath.length - 1)
      fail(`Gate ${gateId} evidence must use role=path arguments`);
    return Object.freeze({ role: rolePath.slice(0, separator), path: rolePath.slice(separator + 1) });
  });
  const actualRoles = parsed.map(({ role }) => role);
  const expectedRoles = contract.roles.map(({ role }) => role);
  if (new Set(actualRoles).size !== actualRoles.length
      || JSON.stringify(actualRoles) !== JSON.stringify(expectedRoles)) {
    fail(`Gate ${gateId} evidence roles and order must be exactly: ${expectedRoles.join(', ')}`);
  }
  return parsed;
}

const WORKFLOW_KEYS = ['job', 'repository', 'runAttempt', 'runId', 'serverUrl', 'sha'];

function workflowIdentity() {
  return Object.freeze({
    job: requireEnvironment('GITHUB_JOB'),
    repository: requireEnvironment('GITHUB_REPOSITORY'),
    runAttempt: requireEnvironment('GITHUB_RUN_ATTEMPT'),
    runId: requireEnvironment('GITHUB_RUN_ID'),
    serverUrl: requireEnvironment('GITHUB_SERVER_URL'),
    sha: requireEnvironment('GITHUB_SHA'),
  });
}

const GATE_RECEIPT_KEYS = [
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
];

function validateGateReceipt(receipt, gate, contract, candidateCommit, candidateSha256) {
  requireExactKeys(receipt, GATE_RECEIPT_KEYS, `${gate.id} typed receipt`);
  requireExactKeys(receipt.workflow, WORKFLOW_KEYS, `${gate.id} receipt workflow`);
  for (const [key, value] of Object.entries(receipt.workflow))
    requireString(value, `${gate.id} receipt workflow ${key}`);
  if (receipt.formatVersion !== 1
      || receipt.candidateCommit !== candidateCommit
      || receipt.candidateSha256 !== candidateSha256
      || !SHA256_PATTERN.test(receipt.candidateSha256)
      || receipt.command !== contract.command
      || receipt.contractId !== contract.contractId
      || receipt.expectation !== contract.expectation
      || receipt.gateId !== gate.id
      || receipt.profile !== contract.profile
      || receipt.result !== 'PASS'
      || receipt.toolchain !== gate.toolchain
      || receipt.workflow.sha !== candidateCommit) {
    fail(`${gate.id} typed receipt does not match its exact candidate and gate contract`);
  }
  return Object.freeze({ ...receipt });
}

function evidenceMatchesCandidateArtifact(evidence, candidateArtifact, description) {
  if (evidence.type !== 'FILE'
      || evidence.bytes !== candidateArtifact.bytes
      || evidence.sha256 !== candidateArtifact.sha256) {
    fail(`${description} bytes do not match the candidate artifact descriptor`);
  }
}

function validateGateDefaultArtifact(evidence, gate, specification, description) {
  const identity = /^com\.soklet:soklet:([0-9]+\.[0-9]+\.[0-9]+)$/.exec(
    gate.defaultArtifactIdentity ?? '',
  );
  if (identity === null
      || !SHA256_PATTERN.test(gate.defaultArtifactSha256 ?? '')
      || specification.fileName !== `soklet-${identity[1]}.jar`
      || evidence.type !== 'FILE'
      || evidence.fileName !== specification.fileName
      || evidence.sha256 !== gate.defaultArtifactSha256) {
    fail(`${description} does not match the gate's exact default artifact identity and SHA-256`);
  }
}

function canonicalToolchainDistributionBytes(toolchain, description) {
  if (toolchain === null || typeof toolchain !== 'object' || Array.isArray(toolchain))
    fail(`${description} requires an available manifest toolchain pin`);
  for (const field of [
    'archive',
    'archiveSha256',
    'distribution',
    'distributionUrl',
    'runtimeVersion',
    'vendorVersion',
    'version',
  ]) {
    requireString(toolchain[field], `${description} toolchain ${field}`);
  }
  if (!SHA256_PATTERN.test(toolchain.archiveSha256))
    fail(`${description} toolchain archive SHA-256 is invalid`);
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

function validateGateToolchainDistribution(
  evidence,
  gate,
  specification,
  toolchains,
  description,
) {
  const expectedBytes = canonicalToolchainDistributionBytes(
    toolchains[gate.toolchain],
    description,
  );
  if (evidence.type !== 'FILE'
      || evidence.fileName !== specification.fileName
      || evidence.bytes !== expectedBytes.length
      || evidence.sha256 !== sha256(expectedBytes)) {
    fail(`${description} does not match the gate's exact manifest toolchain distribution`);
  }
}

function verifyCandidatePom(bytes) {
  const text = bytes.toString('utf8');

  if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
    fail('Candidate POM must be UTF-8');

  const coordinates = /<project\b[^>]*>\s*<modelVersion>\s*4\.0\.0\s*<\/modelVersion>\s*<groupId>\s*com\.soklet\s*<\/groupId>\s*<artifactId>\s*soklet\s*<\/artifactId>\s*<version>\s*4\.0\.0\s*<\/version>\s*<packaging>\s*jar\s*<\/packaging>/s;

  if (!coordinates.test(text))
    fail('Candidate POM must declare direct com.soklet:soklet:4.0.0 JAR coordinates');
}

export function recordCandidateArtifacts(
  configPath,
  candidateCommit,
  outputPath,
  { pom, mainJar, sourcesJar, javadocJar },
) {
  const config = validateReleaseConfiguration(configPath);
  requireCommit(candidateCommit);
  const candidatePom = readRealFile(pom, 'candidate POM');
  verifyCandidatePom(candidatePom.bytes);

  for (const [name, path] of Object.entries({ mainJar, sourcesJar, javadocJar })) {
    const candidateJar = readRealFile(path, `candidate ${name}`);

    if (candidateJar.bytes.length < 4
        || candidateJar.bytes[0] !== 0x50 || candidateJar.bytes[1] !== 0x4b) {
      fail(`Candidate ${name} is not a JAR/ZIP file`);
    }
  }

  const value = {
    artifacts: {
      javadocJar: evidenceForPath(javadocJar),
      mainJar: evidenceForPath(mainJar),
      pom: evidenceForPath(pom),
      sourcesJar: evidenceForPath(sourcesJar),
    },
    candidateCommit,
    coordinates: config.candidate,
    formatVersion: 1,
  };
  writeCanonicalJson(outputPath, value);
  return value;
}

const INTEROPERABILITY_RECEIPT_KEYS = [
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
];

function validateInteroperabilityReceipt(receipt, gate, candidateSha256) {
  requireExactKeys(receipt, INTEROPERABILITY_RECEIPT_KEYS, `${gate.id} interoperability receipt`);
  const expectedClient = gate.id === 'typescript-interop'
    ? 'typescript'
    : gate.id === 'go-interop'
      ? 'go'
      : fail(`Unsupported interoperability gate: ${gate.id}`);

  if (receipt.formatVersion !== 1
      || receipt.candidateSha256 !== candidateSha256
      || !SHA256_PATTERN.test(receipt.candidateSha256)
      || receipt.client !== expectedClient
      || receipt.fixtureScenario !== 'tools-list'
      || receipt.fixtureShutdown !== 'CLEAN'
      || receipt.protocolVersion !== '2026-07-28'
      || receipt.sdkArtifactChecksum !== gate.artifactChecksum
      || receipt.sdkArtifactIdentity !== gate.artifactIdentity
      || receipt.sdkCommit !== gate.commit
      || receipt.tool !== 'test_simple_text') {
    fail(`${gate.id} interoperability receipt does not match the exact candidate, SDK pin, and fixture contract`);
  }

  return Object.freeze({ ...receipt });
}

function interoperabilityReceiptForEvidence(gate, evidencePaths) {
  if (evidencePaths.length !== 2)
    fail(`Gate ${gate.id} must retain exactly its log and candidate JAR`);

  const log = readRealFile(evidencePaths[0], `${gate.id} interoperability log`);
  const candidateJar = readRealFile(evidencePaths[1], `${gate.id} candidate JAR`);
  if (candidateJar.bytes.length < 4
      || candidateJar.bytes[0] !== 0x50 || candidateJar.bytes[1] !== 0x4b) {
    fail(`${gate.id} candidate evidence is not a JAR/ZIP file`);
  }

  const text = requireUtf8Text(log, `${gate.id} interoperability log`);
  if (!text.endsWith('\n'))
    fail(`${gate.id} interoperability log must end with LF`);
  const lines = text.slice(0, -1).split('\n');
  const client = gate.id === 'typescript-interop' ? 'typescript' : 'go';
  const marker = `SOKLET_INTEROP_PASS 2026-07-28 ${client}`;
  const receiptPrefix = 'SOKLET_INTEROP_EVIDENCE ';
  const markerLines = lines.filter((line) => line === marker);
  const receiptLines = lines.filter((line) => line.startsWith(receiptPrefix));
  if (markerLines.length !== 1 || receiptLines.length !== 1
      || lines.at(-2) !== marker || lines.at(-1) !== receiptLines[0]) {
    fail(`${gate.id} log must end with one exact success marker and one interoperability receipt`);
  }

  let receipt;
  const receiptJson = receiptLines[0].slice(receiptPrefix.length);
  try {
    receipt = JSON.parse(receiptJson);
  } catch (error) {
    fail(`${gate.id} interoperability receipt is not valid JSON: ${error instanceof Error ? error.message : error}`);
  }

  requireExactKeys(receipt, INTEROPERABILITY_RECEIPT_KEYS, `${gate.id} interoperability receipt`);
  const canonicalReceipt = JSON.stringify(Object.fromEntries(
    INTEROPERABILITY_RECEIPT_KEYS.map((key) => [key, receipt[key]]),
  ));
  if (receiptJson !== canonicalReceipt)
    fail(`${gate.id} interoperability receipt must use the exact canonical encoding`);

  return validateInteroperabilityReceipt(receipt, gate, sha256(candidateJar.bytes));
}

export function recordGateEvidence(
  configPath,
  candidateCommit,
  artifactDescriptorPath,
  gateId,
  outputPath,
  rolePaths,
) {
  const config = validateReleaseConfiguration(configPath);
  requireCommit(candidateCommit);
  const descriptor = validateCandidateArtifactDescriptor(
    config,
    candidateCommit,
    artifactDescriptorPath,
  );
  const gate = config.gates.find(({ id }) => id === gateId);

  if (gate === undefined)
    fail(`Unknown release gate: ${gateId}`);

  if (IMPORTED_RELEASE_HARNESS_GATE_IDS.has(gateId)) {
    fail(
      `Gate ${gateId} requires fail-closed imported evidence; `
        + 'use record-imported-gate with its imported receipt and immutable bundle',
    );
  }

  if (gate.status !== 'READY')
    fail(`Cannot record PASS for non-ready gate ${gateId}: ${gate.status}`);

  const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gateId];
  if (contract === undefined)
    fail(`Gate ${gateId} has no validator-owned evidence contract`);
  const parsedRolePaths = parseRolePaths(gateId, contract, rolePaths);
  const evidence = contract.roles.map((specification, index) => {
    const path = parsedRolePaths[index].path;
    validateEvidenceContent(path, specification, `${gateId} ${specification.role} evidence`);
    const artifact = gateId === MATRIX_CLOSURE_GATE_ID
        && specification.role === 'matrix-report'
      ? validateMatrixClosureReport(
        config,
        path,
        `${gateId} ${specification.role} evidence`,
      )
      : evidenceForPath(path);
    if (specification.candidateArtifact === 'descriptor') {
      const expectedDescriptor = evidenceForPath(artifactDescriptorPath);
      if (artifact.type !== 'FILE'
          || artifact.bytes !== expectedDescriptor.bytes
          || artifact.sha256 !== expectedDescriptor.sha256) {
        fail(`${gateId} artifact descriptor role does not match the validated descriptor`);
      }
    } else if (specification.candidateArtifact === 'gateDefaultArtifact') {
      validateGateDefaultArtifact(
        artifact,
        gate,
        specification,
        `${gateId} ${specification.role}`,
      );
    } else if (specification.candidateArtifact === 'gateToolchainDistribution') {
      validateGateToolchainDistribution(
        artifact,
        gate,
        specification,
        config.toolchains,
        `${gateId} ${specification.role}`,
      );
    } else if (specification.candidateArtifact !== null) {
      evidenceMatchesCandidateArtifact(
        artifact,
        descriptor.artifacts[specification.candidateArtifact],
        `${gateId} ${specification.role}`,
      );
    }
    return Object.freeze({
      artifact,
      mediaType: specification.mediaType,
      role: specification.role,
    });
  });
  const workflow = workflowIdentity();
  if (workflow.sha !== candidateCommit)
    fail(`Workflow SHA ${workflow.sha} does not match candidate ${candidateCommit}`);

  const interoperability = gate.kind === 'INTEROPERABILITY'
    ? interoperabilityReceiptForEvidence(gate, parsedRolePaths.map(({ path }) => path))
    : null;

  const receipt = validateGateReceipt(
    {
      candidateCommit,
      candidateSha256: descriptor.artifacts.mainJar.sha256,
      command: contract.command,
      contractId: contract.contractId,
      expectation: contract.expectation,
      formatVersion: 1,
      gateId,
      profile: contract.profile,
      result: 'PASS',
      toolchain: gate.toolchain,
      workflow,
    },
    gate,
    contract,
    candidateCommit,
    descriptor.artifacts.mainJar.sha256,
  );

  const value = {
    candidateCommit,
    evidence,
    formatVersion: 2,
    gate: {
      artifactChecksum: gate.artifactChecksum,
      artifactIdentity: gate.artifactIdentity,
      commit: gate.commit,
      defaultArtifactIdentity: gate.defaultArtifactIdentity,
      defaultArtifactSha256: gate.defaultArtifactSha256,
      evidenceContract: gate.evidenceContract,
      id: gate.id,
      repository: gate.repository,
      toolchain: gate.toolchain,
    },
    interoperability,
    receipt,
    status: 'PASS',
  };
  writeCanonicalJson(outputPath, value);
  return value;
}

function importedRoleArtifact(role, specification, gateId) {
  const description = `${gateId} imported ${specification.role} evidence`;
  const expectedKind = specification.type === 'FILE' ? 'file' : 'directory';
  if (role.name !== specification.role
      || role.kind !== expectedKind
      || role.mediaType !== specification.mediaType
      || basename(role.path) !== specification.fileName) {
    fail(`${description} does not match its validator-owned role contract`);
  }
  if (role.kind === 'file') {
    const artifact = Object.freeze({
      bytes: role.size,
      fileName: specification.fileName,
      sha256: role.sha256,
      type: 'FILE',
    });
    validateEvidenceItem(artifact, description);
    return artifact;
  }
  const rows = role.entries.map((entry) =>
    `${entry.sha256}  ${entry.path}\n`).join('');
  const artifact = Object.freeze({
    algorithm: "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows",
    fileCount: role.entryCount,
    fileName: specification.fileName,
    sha256: sha256(Buffer.from(rows, 'utf8')),
    type: 'DIRECTORY',
  });
  validateEvidenceItem(artifact, description);
  return artifact;
}

/**
 * Records one of the two externally produced release-harness gates after the
 * fail-closed importer has bound its immutable bundle to this candidate. The
 * imported receipt is intentionally an input to the ordinary format-v2 gate
 * envelope; it does not create a second release gate or change any evidence
 * role identity.
 */
export async function recordImportedGateEvidence(
  configPath,
  candidateCommit,
  artifactDescriptorPath,
  gateId,
  outputPath,
  importedReceiptPath,
  immutableBundlePath,
  candidateIdentityProvider,
) {
  const config = validateReleaseConfiguration(configPath);
  requireCommit(candidateCommit);
  const descriptor = validateCandidateArtifactDescriptor(
    config,
    candidateCommit,
    artifactDescriptorPath,
  );
  const gate = config.gates.find(({ id }) => id === gateId);
  if (gate === undefined)
    fail(`Unknown release gate: ${gateId}`);
  if (gate.status !== 'READY')
    fail(`Cannot record PASS for non-ready gate ${gateId}: ${gate.status}`);
  const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[gateId];
  if (contract === undefined)
    fail(`Gate ${gateId} has no validator-owned evidence contract`);

  const { verifyImportedBundleReceipt } = await import(
    './import-release-harness-evidence.mjs'
  );
  const imported = verifyImportedBundleReceipt({
    bundlePath: immutableBundlePath,
    candidateIdentityProvider,
    candidateRoot: config.projectRoot,
    receiptPath: importedReceiptPath,
    registryPath: resolve(config.projectRoot, 'release/release-harness-contracts.json'),
  });
  if (imported.gate !== gateId
      || imported.evidenceContract !== gate.evidenceContract
      || imported.verifierCommand !== contract.command
      || imported.receiptExpectation !== contract.expectation
      || imported.receiptProfile !== contract.profile
      || imported.candidateBindings.candidateCommit !== candidateCommit
      || imported.candidateBindings.candidateMainJarSha256
        !== descriptor.artifacts.mainJar.sha256
      || imported.candidateBindings.candidatePomSha256
        !== descriptor.artifacts.pom.sha256) {
    fail(`${gateId} imported receipt does not match this candidate and gate contract`);
  }
  if (imported.roles.length !== contract.roles.length)
    fail(`${gateId} imported receipt has an incorrect role count`);
  const evidence = contract.roles.map((specification, index) => Object.freeze({
    artifact: importedRoleArtifact(imported.roles[index], specification, gateId),
    mediaType: specification.mediaType,
    role: specification.role,
  }));
  const workflow = workflowIdentity();
  if (workflow.sha !== candidateCommit)
    fail(`Workflow SHA ${workflow.sha} does not match candidate ${candidateCommit}`);
  const receipt = validateGateReceipt(
    {
      candidateCommit,
      candidateSha256: descriptor.artifacts.mainJar.sha256,
      command: contract.command,
      contractId: contract.contractId,
      expectation: contract.expectation,
      formatVersion: 1,
      gateId,
      profile: contract.profile,
      result: 'PASS',
      toolchain: gate.toolchain,
      workflow,
    },
    gate,
    contract,
    candidateCommit,
    descriptor.artifacts.mainJar.sha256,
  );
  const value = {
    candidateCommit,
    evidence,
    formatVersion: 2,
    gate: {
      artifactChecksum: gate.artifactChecksum,
      artifactIdentity: gate.artifactIdentity,
      commit: gate.commit,
      defaultArtifactIdentity: gate.defaultArtifactIdentity,
      defaultArtifactSha256: gate.defaultArtifactSha256,
      evidenceContract: gate.evidenceContract,
      id: gate.id,
      repository: gate.repository,
      toolchain: gate.toolchain,
    },
    interoperability: null,
    receipt,
    status: 'PASS',
  };
  writeCanonicalJson(outputPath, value);
  return value;
}

export function verifyReleaseConformanceEvidence(
  configPath,
  candidateCommit,
  artifactDescriptorPath,
  conformanceEvidencePath,
) {
  const config = validateReleaseConfiguration(configPath);
  requireCommit(candidateCommit);
  const descriptor = readJson(artifactDescriptorPath, 'candidate artifact descriptor').value;
  const evidence = readJson(conformanceEvidencePath, 'release conformance evidence').value;

  requireExactKeys(
    descriptor,
    ['artifacts', 'candidateCommit', 'coordinates', 'formatVersion'],
    'candidate artifact descriptor',
  );
  requireExactKeys(
    descriptor.artifacts,
    ['javadocJar', 'mainJar', 'pom', 'sourcesJar'],
    'candidate artifact descriptor artifacts',
  );
  if (descriptor.formatVersion !== 1 || descriptor.candidateCommit !== candidateCommit
      || JSON.stringify(descriptor.coordinates) !== JSON.stringify(config.candidate)) {
    fail('Candidate artifact descriptor identity does not match the conformance run');
  }
  for (const [name, artifact] of Object.entries(descriptor.artifacts)) {
    validateEvidenceItem(artifact, `candidate artifact ${name}`);
    if (artifact.type !== 'FILE')
      fail(`Candidate artifact ${name} must be a file`);
  }

  requireExactKeys(
    evidence,
    [
      'evidenceClass',
      'failure',
      'formatVersion',
      'goldenMessagesValidated',
      'mode',
      'phase',
      'protocolVersion',
      'releaseCandidateEvidence',
      'releaseCandidateProvenance',
      'scenarios',
      'status',
      'suiteCommit',
    ],
    'release conformance evidence',
  );

  const officialRoot = resolve(config.projectRoot, 'conformance/official');
  const { pins, selection, expectedChecks } = verifyManifestSet(officialRoot);
  const conformanceGate = config.gates.find(({ id }) => id === 'candidate-conformance');
  if (conformanceGate?.commit !== pins.officialConformanceSuite.commit) {
    fail('Release manifest conformance commit does not match the reviewed suite pin');
  }
  const selectedScenarios = activeScenarios(selection, 5);
  if (selectedScenarios.length !== 39)
    fail('Reviewed Phase 5 conformance selection must contain exactly 39 scenarios');

  if (evidence.formatVersion !== 1
      || evidence.evidenceClass !== 'IMMUTABLE_RELEASE_CANDIDATE'
      || evidence.releaseCandidateEvidence !== true
      || evidence.status !== 'PASSED'
      || evidence.phase !== 5
      || evidence.mode !== 'release'
      || evidence.protocolVersion !== pins.protocolVersion
      || evidence.suiteCommit !== pins.officialConformanceSuite.commit
      || evidence.goldenMessagesValidated !== 48
      || evidence.failure !== null
      || !Array.isArray(evidence.scenarios)
      || evidence.scenarios.length !== selectedScenarios.length) {
    fail('Conformance evidence is not a complete passing immutable release-candidate run');
  }

  const profilesById = new Map(expectedChecks.profiles.map((profile) => [profile.id, profile]));
  for (const [index, expectedScenario] of selectedScenarios.entries()) {
    const actual = evidence.scenarios[index];
    requireExactKeys(
      actual,
      ['checkCount', 'expectedCheckProfile', 'name', 'observedProfileDraft', 'passed'],
      `release conformance scenario ${index + 1}`,
    );
    const profile = profilesById.get(expectedScenario.expectedCheckProfile);
    if (profile === undefined)
      fail(`Missing reviewed conformance profile ${expectedScenario.expectedCheckProfile}`);
    const expectedCheckCount = profile.checks.reduce((total, check) => total + check.count, 0)
      + profile.automaticWireChecks['wire-schema-valid']
      + profile.automaticWireChecks['wire-schema-harness-error'];
    if (actual.name !== expectedScenario.name
        || actual.passed !== true
        || actual.checkCount !== expectedCheckCount
        || actual.expectedCheckProfile !== expectedScenario.expectedCheckProfile
        || actual.observedProfileDraft !== null) {
      fail(
        `Release conformance scenario ${index + 1} does not match the reviewed `
          + `${expectedScenario.name} result contract`,
      );
    }
  }

  const provenance = evidence.releaseCandidateProvenance;
  const expectedCoordinates = {
    groupId: config.candidate.groupId,
    artifactId: config.candidate.artifactId,
    version: config.candidate.version,
  };

  requireExactKeys(
    provenance,
    [
      'artifacts',
      'candidateCommit',
      'coordinates',
      'formatVersion',
      'manifestSha256',
      'protocolVersion',
      'source',
      'suiteCommit',
    ],
    'release conformance provenance',
  );
  requireExactKeys(
    provenance.artifacts,
    ['javadocJar', 'mainJar', 'pom', 'sourcesJar'],
    'release conformance provenance artifacts',
  );

  if (provenance?.formatVersion !== 1
      || provenance.source !== 'explicit-artifacts'
      || provenance.manifestSha256 !== null
      || provenance.candidateCommit !== candidateCommit
      || JSON.stringify(provenance.coordinates) !== JSON.stringify(expectedCoordinates)
      || provenance.protocolVersion !== pins.protocolVersion
      || provenance.suiteCommit !== pins.officialConformanceSuite.commit) {
    fail('Conformance release-candidate provenance does not match the validated candidate and pins');
  }

  const artifactNames = ['pom', 'mainJar', 'sourcesJar', 'javadocJar'];
  if (provenance.artifacts === null || typeof provenance.artifacts !== 'object'
      || descriptor.artifacts === null || typeof descriptor.artifacts !== 'object'
      || !artifactNames.every((name) => {
        const actual = provenance.artifacts[name];
        const expected = descriptor.artifacts[name];
        requireExactKeys(actual, ['bytes', 'fileName', 'sha256'], `conformance ${name}`);
        return actual?.bytes === expected?.bytes
          && actual.fileName === expected.fileName
          && actual.sha256 === expected.sha256;
      })) {
    fail('Conformance artifact provenance does not match the candidate artifact descriptor');
  }

  return evidence;
}

function requireEnvironment(name) {
  const value = process.env[name];
  requireString(value, `environment variable ${name}`);
  return value;
}

function runtimeToolchainEvidence(config) {
  const nodePin = readJson(
    resolve(config.projectRoot, config.toolchains.nodePin.path),
    'Node toolchain pin',
  ).value;
  const expected = {
    coreJdk21: config.toolchains.coreJdk21.version,
    go: `go version go${config.toolchains.go.version} linux/amd64`,
    java: config.toolchains.java.version,
    maven: config.toolchains.maven.version,
    node: nodePin?.toolchain?.node,
    npm: nodePin?.toolchain?.npm,
    toystoreJava: config.toolchains.toystoreJava.version,
  };
  const actual = {
    coreJdk21: requireEnvironment('SOKLET_EVIDENCE_CORE_JDK_21_VERSION'),
    git: requireEnvironment('SOKLET_EVIDENCE_GIT_VERSION'),
    go: requireEnvironment('SOKLET_EVIDENCE_GO_VERSION'),
    java: requireEnvironment('SOKLET_EVIDENCE_JAVA_VERSION'),
    maven: requireEnvironment('SOKLET_EVIDENCE_MAVEN_VERSION'),
    node: requireEnvironment('SOKLET_EVIDENCE_NODE_VERSION'),
    npm: requireEnvironment('SOKLET_EVIDENCE_NPM_VERSION'),
    toystoreJava: requireEnvironment('SOKLET_EVIDENCE_TOYSTORE_JAVA_VERSION'),
  };
  for (const [name, expectedVersion] of Object.entries(expected)) {
    if (actual[name] !== expectedVersion) {
      fail(
        `Observed ${name} toolchain must be exactly ${expectedVersion}, `
          + `found ${actual[name]}`,
      );
    }
  }
  if (!/^git version [0-9]+\.[0-9]+(?:\.[0-9]+)?(?:[ .+-].*)?$/.test(actual.git))
    fail(`Observed git toolchain is not an exact git version: ${actual.git}`);
  return Object.freeze(actual);
}

export function assembleReleaseEvidence(
  configPath,
  candidateCommit,
  artifactDescriptorPath,
  gateDirectory,
  outputPath,
) {
  const config = validateReleaseConfiguration(configPath, { requireReady: true });
  requireCommit(candidateCommit);
  const descriptor = validateCandidateArtifactDescriptor(
    config,
    candidateCommit,
    artifactDescriptorPath,
  );
  const workflow = workflowIdentity();
  if (workflow.sha !== candidateCommit)
    fail(`Workflow SHA ${workflow.sha} does not match candidate ${candidateCommit}`);

  const absoluteGateDirectory = resolve(gateDirectory);

  if (!existsSync(absoluteGateDirectory) || !lstatSync(absoluteGateDirectory).isDirectory())
    fail(`Missing gate-evidence directory: ${absoluteGateDirectory}`);

  const retainedMatrixReport = resolve(
    absoluteGateDirectory,
    '..',
    'raw',
    MATRIX_CLOSURE_GATE_ID,
    'matrix-closure.json',
  );
  const retainedMatrixArtifact = validateMatrixClosureReport(
    config,
    retainedMatrixReport,
    'retained matrix-closure report',
  );

  const actualFiles = readdirSync(absoluteGateDirectory).filter((name) => name.endsWith('.json')).sort();
  const expectedFiles = EXPECTED_GATE_IDS.map((id) => `${id}.json`).sort();

  if (JSON.stringify(actualFiles) !== JSON.stringify(expectedFiles))
    fail(`Gate evidence set must be exactly: ${expectedFiles.join(', ')}`);

  const gates = EXPECTED_GATE_IDS.map((id) => {
    const gate = readJson(resolve(absoluteGateDirectory, `${id}.json`), `${id} gate evidence`).value;
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
      `${id} gate evidence`,
    );

    const expectedGate = config.gates.find((candidate) => candidate.id === id);
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
      `${id} gate pin`,
    );

    if (gate.formatVersion !== 2 || gate.candidateCommit !== candidateCommit
        || gate.status !== 'PASS' || gate.gate.id !== id
        || gate.gate.artifactChecksum !== expectedGate.artifactChecksum
        || gate.gate.artifactIdentity !== expectedGate.artifactIdentity
        || gate.gate.commit !== expectedGate.commit
        || gate.gate.defaultArtifactIdentity !== expectedGate.defaultArtifactIdentity
        || gate.gate.defaultArtifactSha256 !== expectedGate.defaultArtifactSha256
        || gate.gate.evidenceContract !== expectedGate.evidenceContract
        || gate.gate.repository !== expectedGate.repository
        || gate.gate.toolchain !== expectedGate.toolchain
        || !Array.isArray(gate.evidence)) {
      fail(`Invalid or incomplete PASS evidence for gate ${id}`);
    }

    const contract = EXPECTED_GATE_EVIDENCE_CONTRACTS[id];
    const actualRoles = gate.evidence.map((item) => item?.role);
    const expectedRoles = contract.roles.map(({ role }) => role);
    if (new Set(actualRoles).size !== actualRoles.length
        || JSON.stringify(actualRoles) !== JSON.stringify(expectedRoles)) {
      fail(`${id} evidence roles and order must be exactly: ${expectedRoles.join(', ')}`);
    }
    gate.evidence.forEach((item, index) => {
      requireExactKeys(item, ['artifact', 'mediaType', 'role'], `${id} evidence item ${index}`);
      const specification = contract.roles[index];
      if (item.role !== specification.role
          || item.mediaType !== specification.mediaType
          || item.artifact?.type !== specification.type
          || item.artifact?.fileName !== specification.fileName) {
        fail(`${id} evidence item ${index} does not match its exact role contract`);
      }
      validateEvidenceItem(item.artifact, `${id} ${item.role} artifact`);
      if (id === MATRIX_CLOSURE_GATE_ID
          && specification.role === 'matrix-report'
          && JSON.stringify(item.artifact) !== JSON.stringify(retainedMatrixArtifact)) {
        fail('matrix-closure artifact metadata does not match the retained raw report');
      }
      if (specification.candidateArtifact === 'descriptor') {
        const expectedDescriptor = evidenceForPath(artifactDescriptorPath);
        if (item.artifact.type !== 'FILE'
            || item.artifact.bytes !== expectedDescriptor.bytes
            || item.artifact.sha256 !== expectedDescriptor.sha256) {
          fail(`${id} artifact descriptor role does not match the validated descriptor`);
        }
      } else if (specification.candidateArtifact === 'gateDefaultArtifact') {
        validateGateDefaultArtifact(
          item.artifact,
          expectedGate,
          specification,
          `${id} ${item.role}`,
        );
      } else if (specification.candidateArtifact === 'gateToolchainDistribution') {
        validateGateToolchainDistribution(
          item.artifact,
          expectedGate,
          specification,
          config.toolchains,
          `${id} ${item.role}`,
        );
      } else if (specification.candidateArtifact !== null) {
        evidenceMatchesCandidateArtifact(
          item.artifact,
          descriptor.artifacts[specification.candidateArtifact],
          `${id} ${item.role}`,
        );
      }
    });

    validateGateReceipt(
      gate.receipt,
      expectedGate,
      contract,
      candidateCommit,
      descriptor.artifacts.mainJar.sha256,
    );
    if (JSON.stringify(gate.receipt.workflow) !== JSON.stringify(workflow))
      fail(`${id} receipt workflow does not match the assembled workflow identity`);

    if (expectedGate.kind === 'INTEROPERABILITY') {
      validateInteroperabilityReceipt(
        gate.interoperability,
        expectedGate,
        descriptor.artifacts.mainJar.sha256,
      );
      const candidateItems = gate.evidence.filter((item) => item.artifact.type === 'FILE'
        && item.artifact.bytes === descriptor.artifacts.mainJar.bytes
        && item.artifact.fileName === descriptor.artifacts.mainJar.fileName
        && item.artifact.sha256 === descriptor.artifacts.mainJar.sha256);
      if (gate.evidence.length !== 2 || candidateItems.length !== 1) {
        fail(`${id} evidence must include the exact candidate main JAR alongside its receipt log`);
      }
    } else if (gate.interoperability !== null) {
      fail(`Non-interoperability gate ${id} cannot contain an interoperability receipt`);
    }

    return gate;
  });

  const value = {
    artifacts: descriptor.artifacts,
    candidateCommit,
    coordinates: config.candidate,
    formatVersion: 2,
    gates,
    releaseConfigurationSha256: config.sha256,
    toolchains: runtimeToolchainEvidence(config),
    workflow,
  };

  const absoluteOutput = writeCanonicalJson(outputPath, value);
  return Object.freeze({ path: absoluteOutput, sha256: sha256(readFileSync(absoluteOutput)) });
}

function configurationValue(config, path) {
  let value = config.value;

  for (const component of path.split('.')) {
    if (value === null || typeof value !== 'object'
        || Array.isArray(value) || !(component in value)) {
      fail(`Unknown manifest value: ${path}`);
    }
    value = value[component];
  }

  if (typeof value !== 'string' && typeof value !== 'number')
    fail(`Manifest value is not scalar: ${path}`);

  return String(value);
}

function gateValue(config, gateId, field) {
  const gate = config.gates.find(({ id }) => id === gateId);
  if (gate === undefined)
    fail(`Unknown release gate: ${gateId}`);
  if (!(field in gate))
    fail(`Unknown release gate field: ${field}`);
  const value = gate[field];
  if (value === null)
    return '';
  if (typeof value !== 'string' && typeof value !== 'number')
    fail(`Release gate field is not scalar: ${field}`);
  return String(value);
}

function usage() {
  console.error(
    'Usage: node scripts/release-validation-evidence.mjs '
      + 'validate-config <manifest> [--require-ready]\n'
      + '   or: node scripts/release-validation-evidence.mjs value <manifest> <path>\n'
      + '   or: node scripts/release-validation-evidence.mjs list-gates <manifest>\n'
      + '   or: node scripts/release-validation-evidence.mjs list-gate-ids <manifest>\n'
      + '   or: node scripts/release-validation-evidence.mjs gate-value '
      + '<manifest> <gate-id> <field>\n'
      + '   or: node scripts/release-validation-evidence.mjs sha256 <file>\n'
      + '   or: node scripts/release-validation-evidence.mjs record-artifacts '
      + '<manifest> <commit> <output> <pom> <main-jar> <sources-jar> <javadoc-jar>\n'
      + '   or: node scripts/release-validation-evidence.mjs record-gate '
      + '<manifest> <commit> <artifact-descriptor> <gate-id> <output> <role=path>...\n'
      + '   or: node scripts/release-validation-evidence.mjs record-imported-gate '
      + '<manifest> <commit> <artifact-descriptor> <gate-id> <output> '
      + '<imported-receipt> <immutable-bundle>\n'
      + '   or: node scripts/release-validation-evidence.mjs verify-conformance '
      + '<manifest> <commit> <artifact-descriptor> <conformance-evidence>\n'
      + '   or: node scripts/release-validation-evidence.mjs assemble '
      + '<manifest> <commit> <artifact-descriptor> <gate-directory> <output>',
  );
  process.exitCode = 64;
}

async function main(args) {
  const command = args.shift();

  if (command === 'validate-config' && (args.length === 1
      || (args.length === 2 && args[1] === '--require-ready'))) {
    const config = validateReleaseConfiguration(args[0], { requireReady: args.length === 2 });
    console.log(`Validated release configuration ${config.sha256}.`);
    return;
  }

  if (command === 'value' && args.length === 2) {
    console.log(configurationValue(validateReleaseConfiguration(args[0]), args[1]));
    return;
  }

  if (command === 'list-gates' && args.length === 1) {
    const config = validateReleaseConfiguration(args[0]);
    for (const gate of config.gates) {
      console.log([
        gate.id,
        gate.kind,
        gate.repository ?? '',
        gate.commit ?? '',
        gate.status,
        gate.artifactIdentity,
        gate.versionProperty ?? '',
        gate.defaultArtifactIdentity ?? '',
        gate.defaultArtifactSha256 ?? '',
        gate.evidenceContract,
        gate.toolchain,
      ].join('\t'));
    }
    return;
  }

  if (command === 'list-gate-ids' && args.length === 1) {
    const config = validateReleaseConfiguration(args[0]);
    for (const gate of config.gates)
      console.log(`${gate.id}\t${gate.status}`);
    return;
  }

  if (command === 'gate-value' && args.length === 3) {
    console.log(gateValue(validateReleaseConfiguration(args[0]), args[1], args[2]));
    return;
  }

  if (command === 'sha256' && args.length === 1) {
    console.log(sha256(readRealFile(args[0], 'input file').bytes));
    return;
  }

  if (command === 'record-artifacts' && args.length === 7) {
    recordCandidateArtifacts(args[0], args[1], args[2], {
      pom: args[3],
      mainJar: args[4],
      sourcesJar: args[5],
      javadocJar: args[6],
    });
    return;
  }

  if (command === 'record-gate' && args.length >= 6) {
    recordGateEvidence(args[0], args[1], args[2], args[3], args[4], args.slice(5));
    return;
  }

  if (command === 'record-imported-gate' && args.length === 7) {
    await recordImportedGateEvidence(
      args[0], args[1], args[2], args[3], args[4], args[5], args[6],
    );
    return;
  }

  if (command === 'verify-conformance' && args.length === 4) {
    verifyReleaseConformanceEvidence(args[0], args[1], args[2], args[3]);
    console.log('Verified immutable release-candidate conformance evidence.');
    return;
  }

  if (command === 'assemble' && args.length === 5) {
    const result = assembleReleaseEvidence(args[0], args[1], args[2], args[3], args[4]);
    console.log(`Assembled release evidence ${result.sha256}.`);
    return;
  }

  usage();
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
