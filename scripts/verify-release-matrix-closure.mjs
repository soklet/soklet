#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
  realpathSync,
} from 'node:fs';
import {
  dirname,
  isAbsolute,
  join,
  posix,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const EXPECTED_FORMAT_VERSION = 1;
const EXPECTED_PROTOCOL_VERSION = '2026-07-28';
const EXPECTED_SOURCE_MATRIX_PATH = 'mcp/MCP_CONFORMANCE_MATRIX.md';
const EXPECTED_SOURCE_MATRIX_LAST_UPDATED = '2026-09-01';
const EXPECTED_SOURCE_MATRIX_SHA256 =
  'e30767960da9d1ce7cad608faaa53dff85e59f1acffdd30b4a0073af08bec7ac';
const EXPECTED_ROW_COUNT = 263;
const EXPECTED_ROW_IDS_SHA256 =
  'd7a55f3218e4ea8d18e2f6295f56d9b9b70ecdba9deb8be5a624bae3a9b647b0';
const EXPECTED_ROW_ATTRIBUTIONS_SHA256 =
  'e4036d03459f11c189b88b00c8b0d02da6be6f6af491c7a7e662cbca68d95110';
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const ROW_ID_PATTERN = /^(?:MCP-[A-Z0-9]+-\d{3}|SOK-[A-Z0-9]+-\d{3}|AMB-\d{3})$/;
const DISPOSITIONS = Object.freeze([
  'APPLICATION_OWNED',
  'CORE_COMPLETE',
  'NOT_APPLICABLE',
  'RELEASE_GATED',
  'UNRESOLVED',
]);
const DISPOSITION_SET = new Set(DISPOSITIONS);
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
]);
const EXPECTED_NOT_APPLICABLE_IDS = new Set([
  'MCP-BASE-027',
  'MCP-VER-005',
  'MCP-CAP-003',
  'MCP-CAP-005',
  'MCP-HTTP-017',
  'MCP-TOOL-008',
  'MCP-AUTH-001',
  'MCP-AUTH-008',
  'MCP-AUTH-009',
  'SOK-RATE-002',
  'SOK-NA-001',
  'SOK-NA-002',
  'SOK-NA-003',
  'SOK-NA-004',
  'SOK-NA-005',
  'SOK-NA-006',
  'SOK-NA-007',
  'SOK-NA-008',
  'SOK-NA-009',
]);
const EXPECTED_APPLICATION_OWNED_IDS = new Set([
  'MCP-BASE-015',
  'MCP-PROMPT-006',
  'MCP-RESOURCE-006',
  'MCP-RESOURCE-007',
  'MCP-PAGE-004',
  'MCP-PAGE-006',
  'MCP-PAGE-007',
  'MCP-AUTH-002',
  'MCP-AUTH-007',
  'MCP-ELICIT-003',
  'SOK-L10N-007',
  'AMB-004',
]);

const TOP_LEVEL_KEYS = Object.freeze([
  'formatVersion',
  'protocolVersion',
  'releaseVersion',
  'sourceMatrixPath',
  'sourceMatrixLastUpdated',
  'sourceMatrixSha256',
  'releaseGateUniverse',
  'rows',
]);
const ROW_KEYS = Object.freeze([
  'id',
  'disposition',
  'evidence',
  'releaseGates',
  'reason',
]);
const FINITE_BOUND_INVENTORY_PATH = 'conformance/mcp-finite-bound-inventory.json';
const EXPECTED_FINITE_BOUND_SEMANTICS_SHA256 =
  'cc991a9d092cfdb9d3ed13896de5a51ffebc3efd3f90c33fc4029efbdda19282';
const EXPECTED_FINITE_BOUND_EXCLUSIONS_SHA256 =
  '821bd1913c4a3d05afaf774f2cd1975abcb0530fbff3eea379392c44eea8ce8d';
const FINITE_BOUND_TOP_LEVEL_KEYS = Object.freeze([
  'bounds',
  'formatVersion',
  'matcherRules',
  'productionProfile',
  'releaseTarget',
  'reviewedExclusions',
  'scanRoots',
]);
const FINITE_BOUND_KEYS = Object.freeze([
  'boundaryTests',
  'category',
  'deterministicFailure',
  'enforcementOwners',
  'id',
  'name',
  'positiveTests',
  'sourceOwners',
  'values',
]);
const FINITE_BOUND_SOURCE_OWNER_KEYS = Object.freeze([
  'file',
  'key',
  'matcherRuleId',
  'member',
  'owner',
]);
const FINITE_BOUND_EXCLUSION_KEYS = Object.freeze([
  'file',
  'id',
  'key',
  'matcherRuleId',
  'member',
  'owner',
  'rationale',
]);
const FINITE_BOUND_FAILURE_KEYS = Object.freeze(['contract', 'stage']);
const FINITE_BOUND_ENFORCEMENT_OWNER_KEYS = Object.freeze([
  'file',
  'member',
  'owner',
]);
const FINITE_BOUND_VALUE_KEYS = Object.freeze([
  'accounting',
  'key',
  'unit',
  'value',
]);
export const FINITE_BOUND_REQUIRED_CATEGORIES = Object.freeze([
  'BODY',
  'CONNECTION',
  'CURSOR',
  'HEADER',
  'JSON',
  'OUTPUT',
  'PROFILE_1_COMPILER',
  'PROFILE_1_EVALUATOR',
  'QUEUE_STREAM',
  'SERIALIZED_RESULT',
  'TIME',
  'TYPED_BINDING',
  'URI_TEMPLATE',
]);
export const FINITE_BOUND_SCAN_ROOTS = Object.freeze([
  'src/main/java/com/soklet/DefaultMcp*.java',
  'src/main/java/com/soklet/Mcp*.java',
  'src/main/java/com/soklet/SokletProcessor.java',
  'src/main/java/com/soklet/internal/mcp/**/*.java',
]);
export const FINITE_BOUND_MATCHER_RULES = Object.freeze([
  Object.freeze({
    description: 'Byte, short, int, long, BigInteger, or Duration fields explicitly declared with static and final in either modifier order whose identifier contains MAXIMUM or MINIMUM, plus derived declarations classified by FINITE-MATCH-004.',
    family: 'NAMED_LIMIT_CONSTANT',
    id: 'FINITE-MATCH-001',
  }),
  Object.freeze({
    description: 'Components named maximum*, minimum*, *Capacity, *Concurrency, *Timeout, *Deadline, *Duration, *Interval, *Resolution, *Backlog, or *BufferSize on MCP records whose type name ends in Config, Configuration, or Limits.',
    family: 'BOUND_BEARING_CONFIGURATION_COMPONENT',
    id: 'FINITE-MATCH-002',
  }),
  Object.freeze({
    description: 'Public methods on direct src/main/java/com/soklet/Mcp*.java Builder types whose method or parameter name matches the FINITE-MATCH-002 bound-name vocabulary; method members include declared parameter-type signatures.',
    family: 'PUBLIC_BOUND_CONFIGURATION',
    id: 'FINITE-MATCH-003',
  }),
  Object.freeze({
    description: 'Derived or mirrored typed constants and computed methods, production JSON-limit projections, SokletProcessor copies, the HTTP framing allowance, and the localization callback-count mirror; this family takes priority over the other families.',
    family: 'DERIVED_OR_MIRRORED_LIMIT',
    id: 'FINITE-MATCH-004',
  }),
]);
const FINITE_BOUND_MATCHER_IDS = new Set(
  FINITE_BOUND_MATCHER_RULES.map(({ id }) => id),
);
const FINITE_BOUND_ID_PATTERN = /^FINITE-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d{3}$/u;
const FINITE_BOUND_EXCLUSION_ID_PATTERN = /^FINITE-EX-\d{3}$/u;
const JAVA_OWNER_PATTERN = /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$/u;
const JAVA_MEMBER_PATTERN = /^[A-Za-z_$][\w$]*(?:\([^\r\n#]*\))?$/u;
const BOUND_NAME_PATTERN = /^(?:maximum|minimum)[A-Z].*|^.*(?:Capacity|Concurrency|Timeout|Deadline|Duration|Interval|Resolution|Backlog|BufferSize)$/u;
const PRIVACY_BOUNDARY_INVENTORY_PATH =
  'conformance/mcp-privacy-boundary-inventory.json';
const EXPECTED_PRIVACY_SEMANTICS_SHA256 =
  '6072b455df341f83d331ffc7a26461f28750e746c8776dc9a7f99965022c9166';
const PRIVACY_TOP_LEVEL_KEYS = Object.freeze([
  'artifactRoots',
  'boundaries',
  'delegations',
  'formatVersion',
  'matcherRules',
  'productionProfile',
  'releaseTarget',
  'reviewedExclusions',
  'scanRoots',
]);
const PRIVACY_BOUNDARY_KEYS = Object.freeze([
  'canaryTests',
  'category',
  'classification',
  'contract',
  'id',
  'name',
  'sourcePaths',
]);
const PRIVACY_DELEGATION_KEYS = Object.freeze([
  'canaryTests',
  'contract',
  'delegatedOwner',
  'id',
  'name',
  'sourcePaths',
]);
const PRIVACY_SOURCE_PATH_KEYS = Object.freeze([
  'file',
  'key',
  'matcherRuleId',
  'member',
  'occurrence',
  'owner',
  'sink',
]);
const PRIVACY_EXCLUSION_KEYS = Object.freeze([
  'file',
  'id',
  'key',
  'matcherRuleId',
  'member',
  'occurrence',
  'owner',
  'rationale',
  'sink',
]);
const PRIVACY_BOUNDARY_ID_PATTERN = /^PRIV-BOUND-\d{3}$/u;
const PRIVACY_DELEGATION_ID_PATTERN = /^PRIV-DELEGATION-\d{3}$/u;
const PRIVACY_EXCLUSION_ID_PATTERN = /^PRIV-EX-\d{3}$/u;
const PRIVACY_CATEGORIES = new Set([
  'DIAGNOSTIC',
  'EXCEPTION',
  'FIXTURE',
  'LOG',
  'METRIC',
  'REQUEST',
  'THROWABLE',
]);
const PRIVACY_CLASSIFICATIONS = new Set([
  'BOUNDED_METADATA',
  'EXACT_APPLICATION_BOUNDARY',
  'EXACT_FIXTURE_CAPTURE',
  'EXPLICIT_OPT_IN',
  'NO_EMISSION',
  'REDACTED',
]);
export const PRIVACY_REQUIRED_DELEGATED_OWNERS = Object.freeze([
  'APPLICATION_TELEMETRY_AND_MANUAL_VALUES',
  'CUSTOM_COLLECTORS',
  'OPERATOR_RETENTION',
]);
export const PRIVACY_SCAN_ROOTS = Object.freeze([
  'src/main/java/com/soklet/**/*.java',
]);
export const PRIVACY_ARTIFACT_ROOTS = Object.freeze([
  'conformance/golden-*/**/*',
  'conformance/official/expected-checks.json',
  'conformance/official/final-schema/**/*',
  'conformance/official/golden-wire/**/*',
  'conformance/official/protocol-profile-evidence.json',
  'conformance/official/scenarios.json',
  'fuzz/src/test/resources/com/soklet/**/*',
  'src/test/resources/com/soklet/internal/mcp/schema/**/*',
  'src/test/resources/multipart-request-body',
]);
export const PRIVACY_MATCHER_RULES = Object.freeze([
  Object.freeze({
    description: 'Every qualified or statically imported LogEvent.with(...) construction or LogEvent::with method reference in the full Soklet production source tree; the sink records the exact LogEventType token when statically named.',
    family: 'SOKLET_LOG_EMISSION',
    id: 'PRIV-MATCH-001',
  }),
  Object.freeze({
    description: 'Every qualified or statically imported McpMetricsEvent factory invocation, every qualified factory method reference, plus every visible method, constructor, or field declaration owned by McpMetricsEvent, McpMetricsSnapshot, or their nested event/key/builder carriers; signatures, accessors, and fields make new metric dimensions fail closed.',
    family: 'MCP_METRIC_EMISSION',
    id: 'PRIV-MATCH-002',
  }),
  Object.freeze({
    description: 'Every LogEvent builder request(...) attachment, including copy/builder forwarding inside LogEvent itself; unrelated reactive-stream request(...) calls are excluded structurally.',
    family: 'LOG_REQUEST_ATTACHMENT',
    id: 'PRIV-MATCH-003',
  }),
  Object.freeze({
    description: 'Every fluent throwable(...) attachment in the declared production roots; current production uses this vocabulary exclusively for LogEvent throwable attachment and forwarding.',
    family: 'LOG_THROWABLE_ATTACHMENT',
    id: 'PRIV-MATCH-004',
  }),
  Object.freeze({
    description: 'Exact Request/Throwable accessors and lifecycle callback invocations; every public/protected field, method, or constructor whose type/signature exposes Throwable or a frozen Request/context carrier seed; every non-rendering visible declared surface of those seeds, nested builders/copiers, and transitive implementations; and canonical constructors, carrier accessors, and implicit renderers for every record with such a component regardless of record visibility. Explicit toString overrides are represented only by the diagnostic-renderer family. Seeds include Request, trace/multipart carriers, all top-level Mcp*Context owners, localization/invocation/tool/request-id/propagation/admission/input-response carriers. Throws clauses alone are not exposure candidates.',
    family: 'REQUEST_OR_THROWABLE_EXPOSURE',
    id: 'PRIV-MATCH-005',
  }),
  Object.freeze({
    description: 'Construction of the defined Soklet request exceptions whose payload can originate in a path, body, query parameter, form parameter, header, cookie, multipart field, or duplicate/missing value diagnostic.',
    family: 'REQUEST_EXCEPTION_CONSTRUCTION',
    id: 'PRIV-MATCH-006',
  }),
  Object.freeze({
    description: 'Every public/protected generic HTTP/SSE and MCP simulator/result constructor or method surface, plus MCP implementation capture construction/access paths for response headers/body, stream bytes/messages, completion state, and terminal Throwables.',
    family: 'SIMULATOR_FIXTURE_CAPTURE',
    id: 'PRIV-MATCH-007',
  }),
  Object.freeze({
    description: 'Every explicit production toString() declaration; canonical constructor and component accessor paths for records except the constructor and carrier-typed accessors already represented by PRIV-MATCH-005, plus an implicit renderer only when the record declares no toString override and is not already a Request/Throwable carrier rendered by PRIV-MATCH-005; and structural summary/render declarations in diagnostic, summary, and terminal-reporter owners. Signature-bearing candidates make newly added exact diagnostic values fail closed without double-classifying a concrete surface.',
    family: 'PRIVACY_RELEVANT_DIAGNOSTIC_RENDERER',
    id: 'PRIV-MATCH-008',
  }),
  Object.freeze({
    description: 'Every declared public/internal McpJsonRpcError or public McpJsonRpcException method, constructor, visible field, record component, construction/factory, typed accessor publication, and catch path that can place exact application messages or data on the wire; qualified constructions are included.',
    family: 'MCP_WIRE_ERROR_PUBLICATION',
    id: 'PRIV-MATCH-009',
  }),
  Object.freeze({
    description: 'Every tracked regular file named by the frozen exact artifacts or below the recursive Soklet golden, official final-schema/wire, generic fuzz, and MCP schema-resource roots; a newly added artifact is an unclassified candidate and an untracked artifact fails verification.',
    family: 'TRACKED_MCP_FIXTURE_ARTIFACT',
    id: 'PRIV-MATCH-010',
  }),
  Object.freeze({
    description: 'Every explicit construction of a declared or conventional Throwable subtype, including non-suffix local subtypes discovered transitively from extends clauses, while excluding the non-Throwable DTO names JsonRpcError, McpJsonRpcError, ProtocolError, and RequestError; this general lens intentionally overlaps the request-exception and wire-error families.',
    family: 'EXPLICIT_THROWABLE_CONSTRUCTION',
    id: 'PRIV-MATCH-011',
  }),
  Object.freeze({
    description: 'Every public or protected constructor or method declaration whose selected owner is a transitively discovered Throwable subtype, including non-suffix and Error carriers; current methods expose exact values or bounded reasons, future carrier/rendering methods fail closed, and overloads retain canonical parameter signatures.',
    family: 'EXCEPTION_CARRIER_PUBLIC_SURFACE',
    id: 'PRIV-MATCH-012',
  }),
  Object.freeze({
    description: 'Every void emission method declared by the internal microhttp Logger abstraction, every same-vocabulary dot invocation in internal microhttp regardless of receiver expression, each no-Logger EventLoop constructor delegation keyed by its no-op/default semantics, plus every MCP-runtime EventLoop construction keyed by explicit-no-op/default-no-op/alternate semantics and every NoopLogger.instance() wiring; logger replacement therefore changes the inventory key.',
    family: 'MICROHTTP_LOGGER_CHANNEL',
    id: 'PRIV-MATCH-013',
  }),
  Object.freeze({
    description: 'Every direct System.err/System.out print, printf, println, or write call or method reference, every typed PrintStream or System-stream var-alias output call or method reference, the terminal reporter OutputStream write, and every Throwable.printStackTrace(...) call in production source.',
    family: 'DIRECT_OUTPUT_OR_STACKTRACE',
    id: 'PRIV-MATCH-014',
  }),
  Object.freeze({
    description: 'Every visible method, constructor, or field declaration owned by the top-level MetricsCollector interface or any nested metrics carrier/builder, every visible production override of the complete top-level method vocabulary, and every dot invocation of that vocabulary across production source.',
    family: 'METRICS_COLLECTOR_SURFACE',
    id: 'PRIV-MATCH-015',
  }),
  Object.freeze({
    description: 'Every visible LogEvent/Builder/Copier declaration plus every invocation of the derived Builder/Copier vocabulary on qualified, statically imported, typed-receiver, or copy-chain LogEvent construction paths; new attachment names and variable-based attachments fail closed.',
    family: 'LOG_VALUE_ATTACHMENT',
    id: 'PRIV-MATCH-016',
  }),
]);
const PRIVACY_MATCHER_IDS = new Set(
  PRIVACY_MATCHER_RULES.map(({ id }) => id),
);
const PRIVACY_NON_THROWABLE_ERROR_TYPES = new Set([
  'JsonRpcError',
  'McpJsonRpcError',
  'ProtocolError',
  'RequestError',
]);
const RESIDUAL_EVIDENCE_PATH =
  'release/mcp-residual-closure-evidence.json';
const EXPECTED_RESIDUAL_SEMANTICS_SHA256 =
  '37d333ed185acfe423d4fd1aeee13b46a82911e7098ef4b7ba68e051e14be2fb';
const RESIDUAL_TOP_LEVEL_KEYS = Object.freeze([
  'formatVersion',
  'protocolVersion',
  'releaseVersion',
  'rows',
]);
const RESIDUAL_ROW_KEYS = Object.freeze([
  'id',
  'targetDisposition',
  'owningPackage',
  'evidencePaths',
  'documentationPaths',
  'releaseGates',
  'ownershipBoundary',
  'rationale',
]);
const RESIDUAL_ROW_CONTRACTS = Object.freeze([
  Object.freeze({
    id: 'SOK-VALID-002',
    targetDisposition: 'RELEASE_GATED',
    documentationPaths: Object.freeze([
      'MCP.md',
      'release/README.md',
    ]),
    evidencePaths: Object.freeze([
      'conformance/mcp-finite-bound-inventory.json',
      'release/release-validation-manifest.json',
      'src/test/java/com/soklet/internal/mcp/protocol/McpFiniteBoundInventoryTests.java',
    ]),
    releaseGates: Object.freeze([
      'release-soak',
    ]),
  }),
  Object.freeze({
    id: 'SOK-STATE-002',
    targetDisposition: 'CORE_COMPLETE',
    documentationPaths: Object.freeze([
      'MCP.md',
      'SECURITY.md',
      'api/mcp/README.md',
      'release/MCP_REQUEST_STATE_SECURITY_PROFILE.md',
      'release/README.md',
    ]),
    evidencePaths: Object.freeze([
      'src/main/java/com/soklet/DefaultMcpSecurityControls.java',
      'src/test/java/com/soklet/McpSecurityControlsTests.java',
    ]),
    releaseGates: Object.freeze([]),
  }),
  Object.freeze({
    id: 'SOK-STATE-007',
    targetDisposition: 'CORE_COMPLETE',
    documentationPaths: Object.freeze([
      'release/MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md',
    ]),
    evidencePaths: Object.freeze([
      'src/main/java/com/soklet/DefaultMcpSecurityControls.java',
      'src/test/java/com/soklet/McpSecurityControlsTests.java',
    ]),
    releaseGates: Object.freeze([]),
  }),
  Object.freeze({
    id: 'SOK-PRIV-001',
    targetDisposition: 'RELEASE_GATED',
    documentationPaths: Object.freeze([
      'MCP.md',
      'SECURITY.md',
      'release/MCP_PRIVACY_BOUNDARY.md',
      'release/README.md',
    ]),
    evidencePaths: Object.freeze([
      'conformance/mcp-privacy-boundary-inventory.json',
      'release/release-validation-manifest.json',
      'src/test/java/com/soklet/McpPrivacyBoundaryTests.java',
      'src/test/java/com/soklet/internal/mcp/protocol/McpPrivacyBoundaryInternalTests.java',
    ]),
    releaseGates: Object.freeze([
      'release-soak',
      'soklet-otel',
    ]),
  }),
  Object.freeze({
    id: 'AMB-002',
    targetDisposition: 'CORE_COMPLETE',
    documentationPaths: Object.freeze([
      'MCP.md',
    ]),
    evidencePaths: Object.freeze([
      'conformance/mcp-limits-and-accounting.json',
      'src/test/java/com/soklet/internal/mcp/protocol/McpLimitsAndAccountingTests.java',
    ]),
    releaseGates: Object.freeze([]),
  }),
]);
const LIMITS_AND_ACCOUNTING_PATH =
  'conformance/mcp-limits-and-accounting.json';
const EXPECTED_NUMERIC_BOUNDS_AUTHORITY = Object.freeze({
  path: '../mcp/PROFILE_1_NUMERIC_BOUNDS.md',
  sha256: '9477f26dd0d2bbc2f790b8428dd5ad5de7f9d672ba152cfd33fbbf0ae6a78b70',
});
const EXPECTED_FINAL_DISPOSITION_COUNTS = Object.freeze({
  APPLICATION_OWNED: 12,
  CORE_COMPLETE: 113,
  NOT_APPLICABLE: 19,
  RELEASE_GATED: 119,
  UNRESOLVED: 0,
});
const PRIVACY_SINK_PATTERN =
  /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?::[A-Z0-9_]+)?$/u;
const TRACKED_REFERENCE_CACHE = new Map();

export class MatrixClosureVerificationError extends Error {}

function fail(message) {
  throw new MatrixClosureVerificationError(message);
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function assertExactKeys(value, expected, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object.`);
  }
  const actual = Object.keys(value);
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    fail(`${label} keys must be exactly: ${expected.join(', ')}.`);
  }
}

function assertExactArray(actual, expected, label) {
  if (!Array.isArray(actual)
      || actual.length !== expected.length
      || actual.some((value, index) => value !== expected[index])) {
    fail(`${label} must match the frozen order exactly.`);
  }
}

export function canonicalJson(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

export function finiteBoundSemanticsSha256(bounds) {
  const semanticsById = Object.fromEntries(bounds.map((bound) => [
    bound.id,
    {
      boundaryTests: bound.boundaryTests,
      category: bound.category,
      deterministicFailure: bound.deterministicFailure,
      enforcementOwners: bound.enforcementOwners,
      name: bound.name,
      positiveTests: bound.positiveTests,
      sourceOwners: bound.sourceOwners,
      values: bound.values,
    },
  ]));
  return sha256(canonicalJson(semanticsById));
}

export function finiteBoundExclusionsSha256(reviewedExclusions) {
  return sha256(canonicalJson(reviewedExclusions));
}

export function privacySemanticsSha256(inventory) {
  return sha256(canonicalJson({
    boundaries: inventory.boundaries,
    delegations: inventory.delegations,
    reviewedExclusions: inventory.reviewedExclusions,
  }));
}

function matrixRowAttributionsSha256(rows) {
  const attributions = rows.map((row) => ({
    id: row.id,
    disposition: row.disposition,
    evidence: row.evidence,
    releaseGates: row.releaseGates,
    reason: row.reason,
  }));
  return sha256(canonicalJson(attributions));
}

function readCanonicalJson(file, label) {
  if (!existsSync(file)) {
    fail(`${label} does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`${label} must be a regular non-symlink file: ${file}`);
  }
  const bytes = readFileSync(file);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes)) {
    fail(`${label} is not valid UTF-8.`);
  }
  if (text.includes('\r')) {
    fail(`${label} must use LF line endings.`);
  }
  if (!text.endsWith('\n')) {
    fail(`${label} must end with one LF.`);
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(`${label} is malformed JSON: ${error.message}`);
  }
  if (canonicalJson(value) !== text) {
    fail(`${label} is not canonical two-space JSON.`);
  }
  return { bytes, value };
}

function nonblank(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0
      || value.includes('\r') || value.includes('\n')) {
    fail(`${label} must be a nonblank single-line string.`);
  }
}

function normalizedCandidatePath(value, label) {
  nonblank(value, label);
  if (value.includes('\\') || isAbsolute(value)
      || posix.normalize(value) !== value || value === '.'
      || value.startsWith('../') || value.includes('/../')
      || value === '.git' || value.startsWith('.git/')
      || value === 'target' || value.startsWith('target/')) {
    fail(`${label} must be a normalized candidate-relative path.`);
  }
}

function requireContainedPath(root, path, label, expectedType,
    projectLabel = 'Finite-bound') {
  const normalizedRoot = resolve(root);
  const normalizedPath = resolve(path);
  const candidateRelative = relative(normalizedRoot, normalizedPath);
  if (candidateRelative.length === 0 || isAbsolute(candidateRelative)
      || candidateRelative === '..' || candidateRelative.startsWith(`..${sep}`)) {
    fail(`${label} must be contained by the ${projectLabel.toLowerCase()} project root.`);
  }
  if (!existsSync(normalizedRoot)) {
    fail(`${projectLabel} project root does not exist.`);
  }
  const rootStat = lstatSync(normalizedRoot);
  if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
    fail(`${projectLabel} project root must be a regular non-symlink directory.`);
  }
  let current = normalizedRoot;
  const segments = candidateRelative.split(sep);
  for (const [index, segment] of segments.entries()) {
    current = join(current, segment);
    if (!existsSync(current)) fail(`${label} does not exist: ${current}`);
    const stat = lstatSync(current);
    if (stat.isSymbolicLink()) {
      fail(`${label} path must not contain symlinks: ${current}`);
    }
    if (index < segments.length - 1 && !stat.isDirectory()) {
      fail(`${label} parent must be a directory: ${current}`);
    }
    if (index === segments.length - 1
        && ((expectedType === 'file' && !stat.isFile())
          || (expectedType === 'directory' && !stat.isDirectory()))) {
      fail(`${label} must be a regular ${expectedType}: ${current}`);
    }
  }
}

function finiteBoundKey(matcherRuleId, file, owner, member) {
  return `${matcherRuleId}:${file}#${owner}#${member}`;
}

function maskJava(source) {
  const characters = source.split('');
  let state = 'code';
  for (let index = 0; index < characters.length; index++) {
    const current = characters[index];
    const next = characters[index + 1];
    if (state === 'code') {
      if (current === '/' && next === '/') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'line-comment';
      } else if (current === '/' && next === '*') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'block-comment';
      } else if (source.slice(index, index + 3) === '\"\"\"') {
        characters[index] = characters[index + 1] = characters[index + 2] = ' ';
        index += 2;
        state = 'text-block';
      } else if (current === '"') {
        characters[index] = ' ';
        state = 'string';
      } else if (current === '\'') {
        characters[index] = ' ';
        state = 'character';
      }
    } else if (state === 'line-comment') {
      if (current === '\n' || current === '\r') state = 'code';
      else characters[index] = ' ';
    } else if (state === 'block-comment') {
      if (current === '*' && next === '/') {
        characters[index] = characters[index + 1] = ' ';
        index++;
        state = 'code';
      } else if (current !== '\n' && current !== '\r') characters[index] = ' ';
    } else if (state === 'text-block') {
      if (current === '\\') {
        characters[index] = ' ';
        if (index + 1 < characters.length) characters[index + 1] = ' ';
        index++;
      } else if (source.slice(index, index + 3) === '\"\"\"') {
        characters[index] = characters[index + 1] = characters[index + 2] = ' ';
        index += 2;
        state = 'code';
      } else if (current !== '\n' && current !== '\r') {
        characters[index] = ' ';
      }
    } else if (state === 'string' || state === 'character') {
      if (current === '\\') {
        characters[index] = ' ';
        if (index + 1 < characters.length) characters[index + 1] = ' ';
        index++;
      } else if ((state === 'string' && current === '"')
          || (state === 'character' && current === '\'')) {
        characters[index] = ' ';
        state = 'code';
      } else if (current !== '\n' && current !== '\r') {
        characters[index] = ' ';
      }
    }
  }
  return characters.join('');
}

function matchingDelimiter(source, opening, open, close) {
  let depth = 0;
  for (let index = opening; index < source.length; index++) {
    if (source[index] === open) depth++;
    else if (source[index] === close && --depth === 0) return index;
  }
  return -1;
}

function javaTypeScopes(structure) {
  const scopes = [];
  let delimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') delimiter = opening;
      continue;
    }
    const header = structure.slice(delimiter + 1, opening).trim();
    const type = header.match(
      /\b(class|record|interface|enum)\s+([A-Za-z_$][\w$]*)/u,
    );
    if (type) {
      const closing = matchingDelimiter(structure, opening, '{', '}');
      if (closing > opening) {
        scopes.push({
          closing,
          header,
          headerStart: delimiter + 1,
          kind: type[1],
          name: type[2],
          opening,
        });
      }
    }
    delimiter = opening;
  }
  return scopes;
}

function ownerAt(packageName, scopes, index, appendedType) {
  const names = scopes
    .filter(({ closing, opening }) => opening < index && closing > index)
    .sort((left, right) => left.opening - right.opening)
    .map(({ name }) => name);
  if (appendedType !== undefined) names.push(appendedType);
  if (packageName.length === 0 || names.length === 0) {
    fail(`Finite-bound scanner cannot resolve Java owner at source offset ${index}.`);
  }
  return `${packageName}.${names.join('.')}`;
}

function splitTopLevel(value) {
  const parts = [];
  let start = 0;
  const closingByOpening = new Map([
    ['(', ')'],
    ['[', ']'],
    ['{', '}'],
    ['<', '>'],
  ]);
  const stack = [];
  for (let index = 0; index < value.length; index++) {
    const token = value[index];
    if (closingByOpening.has(token)) stack.push(closingByOpening.get(token));
    else if (stack.at(-1) === token) stack.pop();
    else if (token === ',' && stack.length === 0) {
      parts.push(value.slice(start, index));
      start = index + 1;
    }
  }
  parts.push(value.slice(start));
  return parts;
}

function parameterName(declaration) {
  return declaration.trim().match(/([A-Za-z_$][\w$]*)\s*$/u)?.[1];
}

function methodMember(method, parameters, file, line,
    scannerLabel = 'Finite-bound') {
  const parameterTypes = parameters.trim().length === 0 ? []
    : splitTopLevel(parameters).map((parameter) => {
      const declaration = parameter
        .replace(/@[A-Za-z_$][\w$]*(?:\([^()]*(?:\([^()]*\)[^()]*)*\))?\s*/gu, '')
        .replace(/\bfinal\s+/gu, '')
        .trim();
      const name = parameterName(declaration);
      if (name === undefined) {
        fail(`${scannerLabel} scanner cannot resolve a parameter at ${file}:${line}.`);
      }
      const type = declaration.slice(0, declaration.lastIndexOf(name))
        .trim()
        .replace(/\s+/gu, ' ');
      if (type.length === 0 || /[\r\n#()]/u.test(type)) {
        fail(`${scannerLabel} scanner cannot canonicalize a parameter type at ${file}:${line}.`);
      }
      return type;
    });
  return `${method}(${parameterTypes.join(',')})`;
}

function javaMethodScopes(source, structure, typeScopes) {
  const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
  const typeNames = typeScopes.map(({ name }) => name);
  const controls = new Set([
    'catch', 'for', 'if', 'switch', 'synchronized', 'try', 'while',
  ]);
  const scopes = [];
  let delimiter = -1;
  for (let opening = 0; opening < structure.length; opening++) {
    const token = structure[opening];
    if (token !== '{') {
      if (token === ';' || token === '}') delimiter = opening;
      continue;
    }
    const header = structure.slice(delimiter + 1, opening).trim();
    let method;
    let parameters = '';
    let publicMethod = false;
    if (header.length > 0 && !header.includes('->')) {
      const throwsRemoved = header.replace(/\bthrows\b[\s\S]*$/u, '').trim();
      if (throwsRemoved.endsWith(')')) {
        let depth = 0;
        let parameterOpening = -1;
        for (let index = throwsRemoved.length - 1; index >= 0; index--) {
          if (throwsRemoved[index] === ')') depth++;
          else if (throwsRemoved[index] === '(' && --depth === 0) {
            parameterOpening = index;
            break;
          }
        }
        if (parameterOpening >= 0) {
          const before = throwsRemoved.slice(0, parameterOpening);
          const nameMatch = before.match(/([A-Za-z_$][\w$]*)\s*$/u);
          if (nameMatch && !controls.has(nameMatch[1])) {
            const nameOffset = before.lastIndexOf(nameMatch[1]);
            const prior = before.slice(0, nameOffset).trimEnd();
            if (!prior.endsWith('.') && !prior.endsWith('::')
                && !/\b(?:class|record|interface|enum)\b/u.test(prior)
                && !/\b(?:return|new|throw)\s*$/u.test(prior)) {
              method = nameMatch[1];
              parameters = throwsRemoved.slice(parameterOpening + 1, -1);
              publicMethod = /\bpublic\b/u.test(prior);
            }
          }
        }
      } else {
        const compact = header.match(/(?:^|\s)([A-Za-z_$][\w$]*)\s*$/u)?.[1];
        if (compact && typeNames.includes(compact)
            && !/\b(?:class|record|interface|enum)\b/u.test(header)
            && !/[=().]/u.test(header)) method = compact;
      }
    }
    const closing = matchingDelimiter(structure, opening, '{', '}');
    if (method !== undefined && closing > opening) {
      scopes.push({
        body: structure.slice(opening + 1, closing),
        closing,
        headerStart: delimiter + 1,
        line: source.slice(0, opening).split(/\r?\n/u).length,
        method,
        opening,
        owner: ownerAt(packageName, typeScopes, opening),
        parameters,
        publicMethod,
      });
    }
    delimiter = opening;
  }
  return scopes;
}

function javaVisibleMemberDeclarations(source, structure, typeScopes,
    methodScopes) {
  const declarations = [];
  const seen = new Set();
  const controls = new Set([
    'catch', 'for', 'if', 'switch', 'synchronized', 'try', 'while',
  ]);
  const add = ({ headerStart, method, opening, owner, parameters }) => {
    const header = structure.slice(headerStart, opening);
    const containingTypes = typeScopes
      .filter(({ closing, opening: typeOpening }) =>
        typeOpening < opening && closing > opening)
      .sort((left, right) => right.opening - left.opening);
    const implicitInterfaceMethod = containingTypes[0]?.kind === 'interface'
      && !/\bprivate\b/u.test(header);
    if (!/\b(?:public|protected)\b/u.test(header)
        && !implicitInterfaceMethod) return;
    const line = source.slice(0, opening).split(/\r?\n/u).length;
    const member = methodMember(
      method,
      parameters,
      '.privacy-source',
      line,
      'Privacy-boundary',
    );
    const key = `${owner}\0${member}`;
    if (seen.has(key)) return;
    seen.add(key);
    declarations.push({
      header,
      headerStart,
      line,
      member,
      method,
      owner,
      parameters,
    });
  };

  for (const scope of methodScopes) add(scope);

  for (const nameMatch of structure.matchAll(
    /\b([A-Za-z_$][\w$]*)\s*\(/gu,
  )) {
    const method = nameMatch[1];
    if (controls.has(method)) continue;
    const nameIndex = nameMatch.index;
    const opening = nameMatch.index + nameMatch[0].lastIndexOf('(');
    const closing = matchingDelimiter(structure, opening, '(', ')');
    if (closing < 0) continue;
    const semicolon = structure.indexOf(';', closing + 1);
    if (semicolon < 0) continue;
    const nextOpeningBrace = structure.indexOf('{', closing + 1);
    const nextClosingBrace = structure.indexOf('}', closing + 1);
    if ((nextOpeningBrace >= 0 && nextOpeningBrace < semicolon)
        || (nextClosingBrace >= 0 && nextClosingBrace < semicolon)) continue;
    const tail = structure.slice(closing + 1, semicolon).trim();
    if (tail.length > 0 && !/^throws\b[^;{}]*$/u.test(tail)) continue;
    const headerStart = Math.max(
      structure.lastIndexOf(';', nameIndex),
      structure.lastIndexOf('{', nameIndex),
      structure.lastIndexOf('}', nameIndex),
    ) + 1;
    const prior = structure.slice(headerStart, nameIndex).trim();
    if (prior.length === 0 || prior.endsWith('.') || prior.endsWith('::')
        || prior.includes('=')
        || /\b(?:new|return|throw)\s*$/u.test(prior)) continue;
    const containingTypes = typeScopes
      .filter(({ closing: typeClosing, opening: typeOpening }) =>
        typeOpening < opening && typeClosing > opening);
    if (containingTypes.length === 0) continue;
    add({
      headerStart,
      method,
      opening: semicolon,
      owner: ownerAt(
        structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '',
        typeScopes,
        opening,
      ),
      parameters: structure.slice(opening + 1, closing),
    });
  }
  return declarations.sort((left, right) => left.headerStart - right.headerStart);
}

function javaVisibleFieldDeclarations(source, structure, typeScopes,
    methodScopes) {
  const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
  const declarations = [];
  const fieldPattern = /^[ \t]*((?:(?:public|protected|private|static|final|transient|volatile)\s+|@[A-Za-z_$][\w$]*(?:\([^()\r\n]*\))?\s+)*)([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*(?:\s*<[^;={}()]*>)?(?:\s*\[\s*\])*)\s+([^;\r\n]+);/gmu;
  for (const match of structure.matchAll(fieldPattern)) {
    const declarationIndex = match.index + match[0].indexOf(match[3]);
    if (methodScopes.some(({ closing, headerStart }) =>
      headerStart <= declarationIndex && closing > declarationIndex)) continue;
    const containingTypes = typeScopes
      .filter(({ closing, opening }) =>
        opening < declarationIndex && closing > declarationIndex)
      .sort((left, right) => right.opening - left.opening);
    if (containingTypes.length === 0) continue;
    const modifiers = match[1];
    const implicitInterfaceField = containingTypes[0].kind === 'interface'
      && !/\bprivate\b/u.test(modifiers);
    if (!/\b(?:public|protected)\b/u.test(modifiers)
        && !implicitInterfaceField) continue;
    let searchOffset = 0;
    for (const declarator of splitTopLevel(match[3])) {
      const parsed = declarator.trim().match(
        /^([A-Za-z_$][\w$]*)(?:\s*\[\s*\])?\s*(?:=|$)/u,
      );
      if (parsed === null) continue;
      const relativeIndex = match[3].indexOf(parsed[1], searchOffset);
      searchOffset = relativeIndex + parsed[1].length;
      const index = declarationIndex + relativeIndex;
      declarations.push({
        field: parsed[1],
        index,
        line: source.slice(0, index).split(/\r?\n/u).length,
        owner: ownerAt(packageName, typeScopes, index),
        type: match[2].replace(/\s+/gu, ' ').trim(),
      });
    }
  }
  return declarations;
}

function globRegularExpression(pattern) {
  let expression = '^';
  for (let index = 0; index < pattern.length; index++) {
    const token = pattern[index];
    if (token === '*' && pattern[index + 1] === '*') {
      if (pattern[index + 2] === '/') {
        expression += '(?:[^/]+/)*';
        index += 2;
      } else {
        expression += '.*';
        index++;
      }
    } else if (token === '*') {
      expression += '[^/]*';
    } else if ('\\^$+?.()|{}[]'.includes(token)) {
      expression += `\\${token}`;
    } else {
      expression += token;
    }
  }
  return new RegExp(`${expression}$`, 'u');
}

function finiteBoundJavaFiles(root, scanRoots) {
  if (!Array.isArray(scanRoots) || scanRoots.length === 0
      || new Set(scanRoots).size !== scanRoots.length) {
    fail('Finite-bound scanRoots must be a nonempty unique array.');
  }
  const sortedRoots = [...scanRoots].sort(compareAscii);
  if (scanRoots.some((value, index) => value !== sortedRoots[index])) {
    fail('Finite-bound scanRoots must be in ASCII order.');
  }
  for (const [index, pattern] of scanRoots.entries()) {
    normalizedCandidatePath(pattern, `scanRoots[${index}]`);
    if (!pattern.startsWith('src/main/java/') || !pattern.endsWith('.java')) {
      fail(`Finite-bound scan root must select Java sources below src/main/java: ${pattern}`);
    }
  }
  const sourceRoot = resolve(root, 'src/main/java');
  requireContainedPath(
    root,
    sourceRoot,
    'Finite-bound source root src/main/java',
    'directory',
  );
  const files = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name))) {
      const path = join(directory, entry.name);
      const candidateRelative = relative(root, path).split(sep).join('/');
      if (entry.isSymbolicLink()) {
        fail(`Finite-bound source tree contains a symlink: ${candidateRelative}.`);
      }
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.java')) files.push(path);
    }
  };
  visit(sourceRoot);
  const matchers = scanRoots.map(globRegularExpression);
  const matched = new Set();
  const selected = files.filter((path) => {
    const candidateRelative = relative(root, path).split(sep).join('/');
    let selectedFile = false;
    for (const [index, matcher] of matchers.entries()) {
      if (matcher.test(candidateRelative)) {
        matched.add(index);
        selectedFile = true;
      }
    }
    return selectedFile;
  });
  for (const [index, pattern] of scanRoots.entries()) {
    if (!matched.has(index)) fail(`Finite-bound scan root matches no source: ${pattern}.`);
  }
  return selected.sort(compareAscii);
}

function readJavaSource(root, path) {
  const file = relative(root, path).split(sep).join('/');
  const bytes = readFileSync(path);
  const source = bytes.toString('utf8');
  if (!Buffer.from(source, 'utf8').equals(bytes)) {
    fail(`Finite-bound source is not valid UTF-8: ${file}.`);
  }
  return { file, source };
}

function derivedInitializer(file, name, initializer) {
  if (file === 'src/main/java/com/soklet/DefaultMcpLocalizationCatalogExtractor.java'
      && name === 'MAXIMUM_SUPPORTED_CALLBACK_COUNT') return true;
  if (file === 'src/main/java/com/soklet/SokletProcessor.java'
      && (/^MAXIMUM_MCP_/u.test(name)
        || /^MCP_SCHEMA_PREFLIGHT_MAXIMUM_/u.test(name))) return true;
  return /\b[A-Z0-9_]*(?:MAXIMUM|MINIMUM)[A-Z0-9_]*\b/u.test(initializer)
    || /\.(?:maximum|minimum)[A-Z][\w$]*\s*\(/u.test(initializer)
    || /\b(?:maximum|minimum)[A-Z][\w$]*\s*\(/u.test(initializer)
    || /\.productionDefaults\s*\(/u.test(initializer);
}

function derivedMethod(scope) {
  return /^(?:maximum|minimum)[A-Z]/u.test(scope.method)
    && /(?:\bMath\.(?:addExact|max|min|multiplyExact|subtractExact)\s*\(|[-+*/])/u
      .test(scope.body)
    && /(?:\.(?:maximum|minimum)[A-Z][\w$]*\s*\(|\.productionDefaults\s*\(|\b[A-Z0-9_]*(?:MAXIMUM|MINIMUM)[A-Z0-9_]*\b)/u
      .test(scope.body);
}

export function deriveFiniteBoundCandidates(root, scanRoots) {
  const normalizedRoot = resolve(root);
  const candidates = [];
  const candidatesByKey = new Map();
  const add = (candidate) => {
    const key = finiteBoundKey(
      candidate.matcherRuleId,
      candidate.file,
      candidate.owner,
      candidate.member,
    );
    if (candidatesByKey.has(key)) {
      fail(`Finite-bound scanner found a duplicate declaration key: ${key}.`);
    }
    const complete = { ...candidate, key };
    candidatesByKey.set(key, complete);
    candidates.push(complete);
  };

  for (const path of finiteBoundJavaFiles(normalizedRoot, scanRoots)) {
    const { file, source } = readJavaSource(normalizedRoot, path);
    const structure = maskJava(source);
    const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
    const typeScopes = javaTypeScopes(structure);
    const lineAt = (index) => source.slice(0, index).split(/\r?\n/u).length;

    const finiteFieldPattern = /\b((?:(?:public|protected|private|static|final|transient|volatile)\s+|(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?)\s+)*)(?:(?:[A-Za-z_$][\w$]*\.)*(?:BigInteger|Duration)|byte|short|int|long)\s+([A-Za-z_$][\w$]*)\s*(?==|,|;)/gu;
    for (const match of structure.matchAll(finiteFieldPattern)) {
      const modifiers = match[1];
      if (!/\bstatic\b/u.test(modifiers) || !/\bfinal\b/u.test(modifiers)) continue;
      const firstName = match[2];
      const firstNameOffset = match.index + match[0].lastIndexOf(firstName);
      const declarationEnd = structure.indexOf(';', firstNameOffset);
      if (declarationEnd < 0) {
        fail(`Finite-bound scanner found an unterminated field ${firstName} in ${file}.`);
      }
      const declaration = structure.slice(firstNameOffset, declarationEnd);
      let declarationOffset = 0;
      for (const declarator of splitTopLevel(declaration)) {
        const partOffset = declaration.indexOf(declarator, declarationOffset);
        declarationOffset = partOffset + declarator.length + 1;
        const parsed = declarator.trim().match(
          /^([A-Za-z_$][\w$]*)\s*=\s*([\s\S]*)$/u,
        );
        const possibleName = declarator.trim().match(
          /^([A-Za-z_$][\w$]*)/u,
        )?.[1];
        if (parsed === null) {
          if (possibleName !== undefined
              && /(?:MAXIMUM|MINIMUM)/u.test(possibleName)) {
            fail(`Finite-bound field ${possibleName} in ${file} must have an initializer.`);
          }
          continue;
        }
        const [, name, initializer] = parsed;
        const nameOffset = firstNameOffset + partOffset
          + declarator.indexOf(name);
        const framingAllowance = file
          === 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpTransportConfiguration.java'
          && name === 'HTTP_FRAMING_ALLOWANCE_BYTES';
        const derived = derivedInitializer(file, name, initializer);
        if (!/(?:MAXIMUM|MINIMUM)/u.test(name) && !framingAllowance && !derived) {
          continue;
        }
        add({
          file,
          line: lineAt(nameOffset),
          matcherRuleId: framingAllowance || derived
            ? 'FINITE-MATCH-004' : 'FINITE-MATCH-001',
          member: name,
          owner: ownerAt(packageName, typeScopes, nameOffset),
        });
      }
    }

    const productionLimitsPattern = /\bstatic\s+final\s+(?:(?:@[A-Za-z_$][\w$]*(?:\([^)]*\))?)\s+)*(?:[A-Za-z_$][\w$]*\.)*McpJsonLimits\s+(PRODUCTION_LIMITS)\s*=/gu;
    for (const match of structure.matchAll(productionLimitsPattern)) {
      const initializerStart = match.index + match[0].length;
      const initializerEnd = structure.indexOf(';', initializerStart);
      if (initializerEnd < 0
          || !/\bMcpJsonLimits\.productionDefaults\s*\(/u.test(
            structure.slice(initializerStart, initializerEnd),
          )) continue;
      add({
        file,
        line: lineAt(match.index),
        matcherRuleId: 'FINITE-MATCH-004',
        member: match[1],
        owner: ownerAt(packageName, typeScopes, match.index),
      });
    }

    const recordPattern = /\brecord\s+([A-Za-z_$][\w$]*)(?:\s*<[^{};()]*>)?\s*\(/gu;
    for (const match of structure.matchAll(recordPattern)) {
      const recordName = match[1];
      if (!/(?:Config|Configuration|Limits)$/u.test(recordName)) continue;
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      if (closing < 0) {
        fail(`Finite-bound scanner found an unterminated record ${recordName} in ${file}.`);
      }
      const owner = ownerAt(packageName, typeScopes, match.index, recordName);
      for (const component of splitTopLevel(
        structure.slice(opening + 1, closing),
      )) {
        const member = parameterName(component);
        if (member === undefined || !BOUND_NAME_PATTERN.test(member)) continue;
        add({
          file,
          line: lineAt(match.index),
          matcherRuleId: 'FINITE-MATCH-002',
          member,
          owner,
        });
      }
    }

    const methods = javaMethodScopes(source, structure, typeScopes);
    for (const scope of methods) {
      if (derivedMethod(scope)) {
        const member = methodMember(scope.method, scope.parameters, file, scope.line);
        add({
          file,
          line: scope.line,
          matcherRuleId: 'FINITE-MATCH-004',
          member,
          owner: scope.owner,
        });
        continue;
      }
      const directPublicMcpSource = /^src\/main\/java\/com\/soklet\/Mcp[^/]*\.java$/u
        .test(file);
      const builderOwner = scope.owner.endsWith('.Builder');
      const parameterNames = splitTopLevel(scope.parameters)
        .map(parameterName)
        .filter((value) => value !== undefined);
      if (directPublicMcpSource && builderOwner && scope.publicMethod
          && (BOUND_NAME_PATTERN.test(scope.method)
            || parameterNames.some((name) => BOUND_NAME_PATTERN.test(name)))) {
        const member = methodMember(scope.method, scope.parameters, file, scope.line);
        add({
          file,
          line: scope.line,
          matcherRuleId: 'FINITE-MATCH-003',
          member,
          owner: scope.owner,
        });
      }
    }
  }
  return candidates.sort((left, right) => compareAscii(left.key, right.key));
}

function validateFiniteClassification(row, label) {
  assertExactKeys(row, FINITE_BOUND_SOURCE_OWNER_KEYS, label);
  normalizedCandidatePath(row.file, `${label}.file`);
  nonblank(row.owner, `${label}.owner`);
  nonblank(row.member, `${label}.member`);
  if (!JAVA_OWNER_PATTERN.test(row.owner)) {
    fail(`${label}.owner must be an exact qualified Java owner.`);
  }
  if (!JAVA_MEMBER_PATTERN.test(row.member)) {
    fail(`${label}.member must be one exact Java declaration name.`);
  }
  if (!FINITE_BOUND_MATCHER_IDS.has(row.matcherRuleId)) {
    fail(`${label}.matcherRuleId is unknown.`);
  }
  const expectedKey = finiteBoundKey(
    row.matcherRuleId,
    row.file,
    row.owner,
    row.member,
  );
  if (row.key !== expectedKey) {
    fail(`${label}.key must be exactly ${expectedKey}.`);
  }
}

function validateFiniteSourceFile(projectRoot, file, label) {
  normalizedCandidatePath(file, label);
  if (!file.startsWith('src/main/java/') || !file.endsWith('.java')) {
    fail(`${label} must name a production Java source.`);
  }
  requireContainedPath(
    projectRoot,
    resolve(projectRoot, file),
    label,
    'file',
  );
}

function validateFiniteTestReferences(tests, label, projectRoot) {
  if (!Array.isArray(tests) || tests.length === 0) {
    fail(`${label} must be a nonempty array.`);
  }
  if (new Set(tests).size !== tests.length) {
    fail(`${label} must not contain duplicates.`);
  }
  const sorted = [...tests].sort(compareAscii);
  for (const [index, reference] of tests.entries()) {
    const referenceLabel = `${label}[${index}]`;
    if (reference !== sorted[index]) {
      fail(`${label} must be in ASCII order.`);
    }
    nonblank(reference, referenceLabel);
    const parts = reference.split('#');
    if (parts.length !== 2
        || !/^[A-Za-z_$][\w$]*$/u.test(parts[1])) {
      fail(`${referenceLabel} must name one exact Java test method.`);
    }
    const file = parts[0];
    normalizedCandidatePath(file, referenceLabel);
    if (!file.startsWith('src/test/java/') || !file.endsWith('.java')) {
      fail(`${referenceLabel} must name a core Java test source.`);
    }
    const path = resolve(projectRoot, file);
    requireContainedPath(projectRoot, path, referenceLabel, 'file');
    const bytes = readFileSync(path);
    const source = bytes.toString('utf8');
    if (!Buffer.from(source, 'utf8').equals(bytes)) {
      fail(`${referenceLabel} test source is not valid UTF-8.`);
    }
    const structure = maskJava(source);
    const methodExists = javaMethodScopes(
      source,
      structure,
      javaTypeScopes(structure),
    ).some(({ method }) => method === parts[1]);
    if (!methodExists) {
      fail(`${referenceLabel} names no declared test method: ${parts[1]}.`);
    }
  }
}

function validateFiniteEnforcementOwners(owners, label, projectRoot) {
  if (!Array.isArray(owners) || owners.length === 0) {
    fail(`${label} must be a nonempty array.`);
  }
  const keys = [];
  for (const [index, owner] of owners.entries()) {
    const ownerLabel = `${label}[${index}]`;
    assertExactKeys(owner, FINITE_BOUND_ENFORCEMENT_OWNER_KEYS, ownerLabel);
    validateFiniteSourceFile(projectRoot, owner.file, `${ownerLabel}.file`);
    nonblank(owner.member, `${ownerLabel}.member`);
    nonblank(owner.owner, `${ownerLabel}.owner`);
    if (!JAVA_OWNER_PATTERN.test(owner.owner)) {
      fail(`${ownerLabel}.owner must be an exact qualified Java owner.`);
    }
    const sourceBytes = readFileSync(resolve(projectRoot, owner.file));
    const source = sourceBytes.toString('utf8');
    if (!Buffer.from(source, 'utf8').equals(sourceBytes)) {
      fail(`${ownerLabel}.file is not valid UTF-8.`);
    }
    const structure = maskJava(source);
    const packageName = structure.match(
      /\bpackage\s+([\w.]+)\s*;/u,
    )?.[1] ?? '';
    const typeScopes = javaTypeScopes(structure);
    const declaredOwners = typeScopes.map((type) => ownerAt(
      packageName,
      typeScopes,
      type.opening,
      type.name,
    ));
    if (!declaredOwners.includes(owner.owner)) {
      fail(`${ownerLabel}.owner is not declared by its production source file.`);
    }
    keys.push(`${owner.file}#${owner.owner}#${owner.member}`);
  }
  if (new Set(keys).size !== keys.length) {
    fail(`${label} must not contain duplicates.`);
  }
  const sorted = [...keys].sort(compareAscii);
  if (keys.some((key, index) => key !== sorted[index])) {
    fail(`${label} must be in ASCII identity order.`);
  }
}

function validateFiniteValues(values, label, seenKeys) {
  if (!Array.isArray(values) || values.length === 0) {
    fail(`${label} must be a nonempty array.`);
  }
  const keys = [];
  for (const [index, value] of values.entries()) {
    const valueLabel = `${label}[${index}]`;
    assertExactKeys(value, FINITE_BOUND_VALUE_KEYS, valueLabel);
    for (const field of FINITE_BOUND_VALUE_KEYS) {
      nonblank(value[field], `${valueLabel}.${field}`);
    }
    if (!/^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$/u.test(value.key)) {
      fail(`${valueLabel}.key must be a stable lowercase accounting key.`);
    }
    if (!/^[A-Z][A-Z0-9_]*$/u.test(value.unit)) {
      fail(`${valueLabel}.unit must be an uppercase stable unit token.`);
    }
    if (!/^-?(?:0|[1-9][0-9]*)$/u.test(value.value)) {
      fail(`${valueLabel}.value must be a canonical finite integer string.`);
    }
    if (seenKeys.has(value.key)) {
      fail(`Finite-bound value key is duplicated: ${value.key}.`);
    }
    seenKeys.add(value.key);
    keys.push(value.key);
  }
  const sorted = [...keys].sort(compareAscii);
  if (keys.some((key, index) => key !== sorted[index])) {
    fail(`${label} must be in ASCII key order.`);
  }
}

export function verifyFiniteBoundInventory(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const inventoryPath = resolve(
    options.inventoryPath
      ?? resolve(projectRoot, FINITE_BOUND_INVENTORY_PATH),
  );
  requireContainedPath(
    projectRoot,
    inventoryPath,
    'Finite-bound inventory',
    'file',
  );
  const { value: inventory } = readCanonicalJson(
    inventoryPath,
    'Finite-bound inventory',
  );
  assertExactKeys(
    inventory,
    FINITE_BOUND_TOP_LEVEL_KEYS,
    'Finite-bound inventory',
  );
  if (inventory.formatVersion !== 1
      || inventory.productionProfile !== EXPECTED_PROTOCOL_VERSION
      || inventory.releaseTarget !== '4.0.0') {
    fail('Finite-bound inventory format, profile, or release target is invalid.');
  }
  if (JSON.stringify(inventory.matcherRules)
      !== JSON.stringify(FINITE_BOUND_MATCHER_RULES)) {
    fail('Finite-bound matcherRules do not match the executable matcher contract.');
  }
  const expectedScanRoots = options.expectedScanRoots
    ?? FINITE_BOUND_SCAN_ROOTS;
  assertExactArray(
    inventory.scanRoots,
    expectedScanRoots,
    'Finite-bound scanRoots',
  );
  const candidates = deriveFiniteBoundCandidates(
    projectRoot,
    expectedScanRoots,
  );
  if (!Array.isArray(inventory.bounds) || inventory.bounds.length === 0) {
    fail('Finite-bound inventory bounds must be a nonempty array.');
  }
  const boundIds = new Set();
  const boundCategories = new Set();
  const valueKeys = new Set();
  const classifications = [];
  const sortedBoundIds = inventory.bounds.map(({ id }) => id)
    .sort(compareAscii);
  for (const [boundIndex, bound] of inventory.bounds.entries()) {
    const label = `bounds[${boundIndex}]`;
    assertExactKeys(bound, FINITE_BOUND_KEYS, label);
    if (typeof bound.id !== 'string' || !FINITE_BOUND_ID_PATTERN.test(bound.id)
        || FINITE_BOUND_EXCLUSION_ID_PATTERN.test(bound.id)
        || boundIds.has(bound.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    boundIds.add(bound.id);
    if (bound.id !== sortedBoundIds[boundIndex]) {
      fail('Finite-bound inventory bounds must be in ASCII ID order.');
    }
    for (const field of ['category', 'name']) nonblank(bound[field], `${label}.${field}`);
    boundCategories.add(bound.category);
    assertExactKeys(
      bound.deterministicFailure,
      FINITE_BOUND_FAILURE_KEYS,
      `${label}.deterministicFailure`,
    );
    for (const field of FINITE_BOUND_FAILURE_KEYS) {
      nonblank(
        bound.deterministicFailure[field],
        `${label}.deterministicFailure.${field}`,
      );
    }
    if (!Array.isArray(bound.sourceOwners) || bound.sourceOwners.length === 0) {
      fail(`${bound.id}.sourceOwners must be a nonempty array.`);
    }
    validateFiniteEnforcementOwners(
      bound.enforcementOwners,
      `${bound.id}.enforcementOwners`,
      projectRoot,
    );
    validateFiniteTestReferences(
      bound.positiveTests,
      `${bound.id}.positiveTests`,
      projectRoot,
    );
    validateFiniteTestReferences(
      bound.boundaryTests,
      `${bound.id}.boundaryTests`,
      projectRoot,
    );
    const overlappingTests = bound.positiveTests.filter((test) =>
      bound.boundaryTests.includes(test));
    if (overlappingTests.length > 0) {
      fail(`${bound.id} positiveTests and boundaryTests must be disjoint.`);
    }
    validateFiniteValues(bound.values, `${bound.id}.values`, valueKeys);
    const sortedOwnerKeys = bound.sourceOwners.map(({ key }) => key)
      .sort(compareAscii);
    for (const [ownerIndex, owner] of bound.sourceOwners.entries()) {
      const ownerLabel = `${bound.id}.sourceOwners[${ownerIndex}]`;
      validateFiniteClassification(owner, ownerLabel);
      validateFiniteSourceFile(projectRoot, owner.file, `${ownerLabel}.file`);
      if (owner.key !== sortedOwnerKeys[ownerIndex]) {
        fail(`${bound.id}.sourceOwners must be in ASCII key order.`);
      }
      classifications.push({ ...owner, location: ownerLabel });
    }
  }
  const expectedCategories = options.expectedCategories
    ?? FINITE_BOUND_REQUIRED_CATEGORIES;
  const actualCategories = [...boundCategories].sort(compareAscii);
  assertExactArray(
    actualCategories,
    [...expectedCategories].sort(compareAscii),
    'Finite-bound categories',
  );
  if (!Array.isArray(inventory.reviewedExclusions)) {
    fail('Finite-bound reviewedExclusions must be an array.');
  }
  const exclusionIds = new Set();
  const sortedExclusionKeys = inventory.reviewedExclusions.map(({ key }) => key)
    .sort(compareAscii);
  for (const [index, exclusion] of inventory.reviewedExclusions.entries()) {
    const label = `reviewedExclusions[${index}]`;
    assertExactKeys(exclusion, FINITE_BOUND_EXCLUSION_KEYS, label);
    const classification = {
      file: exclusion.file,
      key: exclusion.key,
      matcherRuleId: exclusion.matcherRuleId,
      member: exclusion.member,
      owner: exclusion.owner,
    };
    validateFiniteClassification(classification, label);
    validateFiniteSourceFile(projectRoot, exclusion.file, `${label}.file`);
    if (typeof exclusion.id !== 'string'
        || !FINITE_BOUND_EXCLUSION_ID_PATTERN.test(exclusion.id)
        || exclusionIds.has(exclusion.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    exclusionIds.add(exclusion.id);
    nonblank(exclusion.rationale, `${label}.rationale`);
    if (exclusion.key !== sortedExclusionKeys[index]) {
      fail('Finite-bound reviewedExclusions must be in ASCII key order.');
    }
    classifications.push({ ...classification, location: label });
  }
  const classificationsByKey = new Map();
  for (const classification of classifications) {
    if (classificationsByKey.has(classification.key)) {
      fail(`Finite-bound classification is duplicated at ${classificationsByKey.get(classification.key).location} and ${classification.location}: ${classification.key}.`);
    }
    classificationsByKey.set(classification.key, classification);
  }
  const candidatesByKey = new Map(candidates.map((candidate) =>
    [candidate.key, candidate]));
  const omitted = candidates.filter(({ key }) => !classificationsByKey.has(key));
  const extra = classifications.filter(({ key }) => !candidatesByKey.has(key));
  if (omitted.length > 0 || extra.length > 0) {
    fail(`Finite-bound inventory differs from source derivation; omitted=[${omitted.map(({ key, line }) => `${key}@${line}`).join(', ')}], extra=[${extra.map(({ key }) => key).join(', ')}].`);
  }
  const expectedExclusionsSha256 = options.expectedExclusionsSha256
    ?? EXPECTED_FINITE_BOUND_EXCLUSIONS_SHA256;
  if (!SHA256_PATTERN.test(expectedExclusionsSha256)) {
    fail('Expected finite-bound exclusions SHA-256 must be 64 lowercase hexadecimal characters.');
  }
  const exclusionsSha256 = finiteBoundExclusionsSha256(
    inventory.reviewedExclusions,
  );
  if (exclusionsSha256 !== expectedExclusionsSha256) {
    fail(`Finite-bound exclusion attribution SHA-256 differs from the reviewed contract: expected ${expectedExclusionsSha256}, found ${exclusionsSha256}.`);
  }
  const expectedSemanticsSha256 = options.expectedSemanticsSha256
    ?? EXPECTED_FINITE_BOUND_SEMANTICS_SHA256;
  if (!SHA256_PATTERN.test(expectedSemanticsSha256)) {
    fail('Expected finite-bound semantics SHA-256 must be 64 lowercase hexadecimal characters.');
  }
  const semanticsSha256 = finiteBoundSemanticsSha256(inventory.bounds);
  if (semanticsSha256 !== expectedSemanticsSha256) {
    fail(`Finite-bound semantic attribution SHA-256 differs from the reviewed contract: expected ${expectedSemanticsSha256}, found ${semanticsSha256}.`);
  }
  return {
    candidates,
    exclusions: inventory.reviewedExclusions,
    inventory,
  };
}

function privacyKey(matcherRuleId, file, owner, member, sink, occurrence) {
  return `${matcherRuleId}:${file}#${owner}#${member}->${sink}@${occurrence}`;
}

function privacyJavaFiles(root, scanRoots) {
  if (!Array.isArray(scanRoots) || scanRoots.length === 0
      || new Set(scanRoots).size !== scanRoots.length) {
    fail('Privacy-boundary scanRoots must be a nonempty unique array.');
  }
  const sortedRoots = [...scanRoots].sort(compareAscii);
  if (scanRoots.some((value, index) => value !== sortedRoots[index])) {
    fail('Privacy-boundary scanRoots must be in ASCII order.');
  }
  for (const [index, pattern] of scanRoots.entries()) {
    normalizedCandidatePath(pattern, `scanRoots[${index}]`);
    if (!pattern.startsWith('src/main/java/') || !pattern.endsWith('.java')) {
      fail(`Privacy-boundary scan root must select Java sources below src/main/java: ${pattern}`);
    }
  }
  const sourceRoot = resolve(root, 'src/main/java');
  requireContainedPath(
    root,
    sourceRoot,
    'Privacy-boundary source root src/main/java',
    'directory',
    'Privacy-boundary',
  );
  const files = [];
  const visit = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareAscii(left.name, right.name))) {
      const path = join(directory, entry.name);
      const candidateRelative = relative(root, path).split(sep).join('/');
      if (entry.isSymbolicLink()) {
        fail(`Privacy-boundary source tree contains a symlink: ${candidateRelative}.`);
      }
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.java')) files.push(path);
    }
  };
  visit(sourceRoot);
  const matchers = scanRoots.map(globRegularExpression);
  const matched = new Set();
  const selected = files.filter((path) => {
    const candidateRelative = relative(root, path).split(sep).join('/');
    let selectedFile = false;
    for (const [index, matcher] of matchers.entries()) {
      if (matcher.test(candidateRelative)) {
        matched.add(index);
        selectedFile = true;
      }
    }
    return selectedFile;
  });
  for (const [index, pattern] of scanRoots.entries()) {
    if (!matched.has(index)) {
      fail(`Privacy-boundary scan root matches no source: ${pattern}.`);
    }
  }
  return selected.sort(compareAscii);
}

function readPrivacyJavaSource(root, path) {
  const file = relative(root, path).split(sep).join('/');
  const bytes = readFileSync(path);
  const source = bytes.toString('utf8');
  if (!Buffer.from(source, 'utf8').equals(bytes)) {
    fail(`Privacy-boundary source is not valid UTF-8: ${file}.`);
  }
  return { file, source };
}

function privacyLocation(source, structure, packageName, typeScopes,
    methodScopes, index, explicitMethod) {
  const scopes = methodScopes
    .filter(({ closing, headerStart }) => headerStart <= index && closing > index)
    .sort((left, right) => right.opening - left.opening);
  const scope = scopes[0];
  if (scope !== undefined) {
    return {
      member: methodMember(
        scope.method,
        scope.parameters,
        relative('.', '.privacy-source'),
        scope.line,
        'Privacy-boundary',
      ),
      owner: scope.owner,
    };
  }
  if (explicitMethod !== undefined) {
    const opening = structure.indexOf('(', index + explicitMethod.length);
    const closing = opening < 0 ? -1
      : matchingDelimiter(structure, opening, '(', ')');
    if (opening >= 0 && closing > opening) {
      const parameters = structure.slice(opening + 1, closing);
      return {
        member: methodMember(
          explicitMethod,
          parameters,
          relative('.', '.privacy-source'),
          source.slice(0, index).split(/\r?\n/u).length,
          'Privacy-boundary',
        ),
        owner: ownerAt(packageName, typeScopes, index),
      };
    }
  }
  return {
    member: '$typeInitializer()',
    owner: ownerAt(packageName, typeScopes, index),
  };
}

function isLogRequestAttachment(structure, owner, index, context = {}) {
  const statementStart = Math.max(
    structure.lastIndexOf(';', index),
    structure.lastIndexOf('{', index),
    structure.lastIndexOf('}', index),
  );
  const prefix = structure.slice(statementStart + 1, index);
  const receiver = structure.slice(0, index).match(
    /([A-Za-z_$][\w$]*)\s*$/u,
  )?.[1];
  const copiedEvent = /\.\s*copy\s*\(/u.test(prefix);
  return /\bLogEvent\s*\.\s*(?:builder|with)\s*\(/u.test(prefix)
    || (context.staticWith === true && /(?<![\w$.])with\s*\(/u.test(prefix))
    || (receiver !== undefined
      && (context.builderReceivers ?? new Set()).has(receiver))
    || copiedEvent
    || owner.endsWith('.LogEvent') || owner.includes('.LogEvent.');
}

function privacyThrowableTypeNames(sourceModels) {
  const declaredParents = [];
  const throwableNames = new Set([
    'Error',
    'Exception',
    'RuntimeException',
    'Throwable',
  ]);
  for (const { typeScopes } of sourceModels) {
    for (const { header, kind, name } of typeScopes) {
      if (kind !== 'class') continue;
      const parent = header.match(
        /\bextends\s+(?:[A-Za-z_$][\w$]*\s*\.\s*)*([A-Za-z_$][\w$]*)/u,
      )?.[1];
      if (parent !== undefined) declaredParents.push({ name, parent });
      if ((name.endsWith('Exception') || name.endsWith('Error'))
          && !PRIVACY_NON_THROWABLE_ERROR_TYPES.has(name)) {
        throwableNames.add(name);
      }
    }
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const { name, parent } of declaredParents) {
      const throwableParent = throwableNames.has(parent)
        || parent.endsWith('Exception') || parent.endsWith('Error');
      if (throwableNames.has(name) || !throwableParent) continue;
      throwableNames.add(name);
      changed = true;
    }
  }
  return throwableNames;
}

function containsPrivacyCarrierType(value, throwableTypeNames) {
  for (const match of value.matchAll(/\b([A-Za-z_$][\w$]*)\b/gu)) {
    const name = match[1];
    if (name === 'Request' || name === 'Throwable') return true;
    if (PRIVACY_NON_THROWABLE_ERROR_TYPES.has(name)) continue;
    if (throwableTypeNames.has(name)
        || name.endsWith('Exception') || name.endsWith('Error')) return true;
  }
  return false;
}

function privacyArtifactFiles(root, artifactRoots, gitExecutable) {
  if (!Array.isArray(artifactRoots) || artifactRoots.length === 0
      || new Set(artifactRoots).size !== artifactRoots.length) {
    fail('Privacy-boundary artifactRoots must be a nonempty unique array.');
  }
  const sortedRoots = [...artifactRoots].sort(compareAscii);
  if (artifactRoots.some((value, index) => value !== sortedRoots[index])) {
    fail('Privacy-boundary artifactRoots must be in ASCII order.');
  }
  if (typeof gitExecutable !== 'string' || gitExecutable.length === 0) {
    fail('Privacy-boundary gitExecutable must be a nonempty string.');
  }
  const allFiles = new Set();
  for (const [index, pattern] of artifactRoots.entries()) {
    normalizedCandidatePath(pattern, `artifactRoots[${index}]`);
    if (!(pattern.startsWith('conformance/')
        || pattern.startsWith('fuzz/src/test/resources/')
        || pattern.startsWith('src/test/resources/'))) {
      fail(`Privacy-boundary artifact root is outside the frozen fixture trees: ${pattern}.`);
    }
    const wildcardIndex = pattern.search(/[?*]/u);
    if (wildcardIndex < 0) {
      const exactPath = resolve(root, pattern);
      requireContainedPath(
        root,
        exactPath,
        `Privacy-boundary exact artifact ${pattern}`,
        'file',
        'Privacy-boundary',
      );
      allFiles.add(pattern);
      continue;
    }
    const fixedPrefix = pattern.slice(0, wildcardIndex);
    const baseRelative = fixedPrefix.endsWith('/')
      ? fixedPrefix.slice(0, -1) : posix.dirname(fixedPrefix);
    const base = resolve(root, baseRelative);
    requireContainedPath(
      root,
      base,
      `Privacy-boundary artifact base ${baseRelative}`,
      'directory',
      'Privacy-boundary',
    );
    const visit = (directory) => {
      for (const entry of readdirSync(directory, { withFileTypes: true })
        .sort((left, right) => compareAscii(left.name, right.name))) {
        const path = join(directory, entry.name);
        const candidateRelative = relative(root, path).split(sep).join('/');
        if (entry.isSymbolicLink()) {
          fail(`Privacy-boundary artifact tree contains a symlink: ${candidateRelative}.`);
        }
        if (entry.isDirectory()) visit(path);
        else if (entry.isFile()) allFiles.add(candidateRelative);
      }
    };
    visit(base);
  }
  const matchers = artifactRoots.map(globRegularExpression);
  const matched = new Set();
  const artifacts = [];
  for (const file of [...allFiles].sort(compareAscii)) {
    for (const [index, matcher] of matchers.entries()) {
      if (!matcher.test(file)) continue;
      matched.add(index);
      const cacheKey = `${gitExecutable}\0${root}\0${file}`;
      let tracked = TRACKED_REFERENCE_CACHE.get(cacheKey);
      if (tracked === undefined) {
        const result = spawnSync(
          gitExecutable,
          [
            '-c',
            `safe.directory=${root}`,
            '-C',
            root,
            'ls-files',
            '--error-unmatch',
            '--',
            file,
          ],
          { encoding: 'utf8' },
        );
        if (result.error !== undefined) {
          fail(`Unable to inspect privacy artifact tracking: ${result.error.message}`);
        }
        tracked = result.status === 0;
        TRACKED_REFERENCE_CACHE.set(cacheKey, tracked);
      }
      if (!tracked) {
        fail(`Privacy-boundary fixture artifact is not tracked: ${file}.`);
      }
      artifacts.push({ file, rootIndex: index });
      break;
    }
  }
  for (const [index, pattern] of artifactRoots.entries()) {
    if (!matched.has(index)) {
      fail(`Privacy-boundary artifact root matches no tracked artifact: ${pattern}.`);
    }
  }
  return artifacts;
}

export function derivePrivacyBoundaryCandidates(root, scanRoots,
    artifactRoots = PRIVACY_ARTIFACT_ROOTS, gitExecutable = 'git') {
  const normalizedRoot = resolve(root);
  const candidates = [];
  const occurrenceByIdentity = new Map();
  const add = ({ file, index = 0, matcherRuleId, member, owner, sink, source }) => {
    const identity = `${matcherRuleId}\0${file}\0${owner}\0${member}\0${sink}`;
    const occurrence = (occurrenceByIdentity.get(identity) ?? 0) + 1;
    occurrenceByIdentity.set(identity, occurrence);
    const key = privacyKey(
      matcherRuleId,
      file,
      owner,
      member,
      sink,
      occurrence,
    );
    candidates.push({
      file,
      key,
      line: source === undefined ? 1
        : source.slice(0, index).split(/\r?\n/u).length,
      matcherRuleId,
      member,
      occurrence,
      owner,
      sink,
    });
  };

  const sourceModels = privacyJavaFiles(normalizedRoot, scanRoots).map((path) => {
    const { file, source } = readPrivacyJavaSource(normalizedRoot, path);
    const structure = maskJava(source);
    const packageName = structure.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? '';
    const typeScopes = javaTypeScopes(structure);
    const methodScopes = javaMethodScopes(source, structure, typeScopes);
    const visibleMemberDeclarations = javaVisibleMemberDeclarations(
      source,
      structure,
      typeScopes,
      methodScopes,
    );
    const visibleFieldDeclarations = javaVisibleFieldDeclarations(
      source,
      structure,
      typeScopes,
      methodScopes,
    );
    return {
      file,
      methodScopes,
      packageName,
      source,
      structure,
      typeScopes,
      visibleFieldDeclarations,
      visibleMemberDeclarations,
    };
  });
  const throwableTypeNames = privacyThrowableTypeNames(sourceModels);
  const applicationRequestCarrierOwners = new Set([
    'com.soklet.McpAdmissionIdentity',
    'com.soklet.McpInputResponses',
    'com.soklet.McpInvocationFeatures',
    'com.soklet.McpLocalizationRequest',
    'com.soklet.McpRequestId',
    'com.soklet.McpRequestPropagation',
    'com.soklet.McpToolArguments',
    'com.soklet.MultipartField',
    'com.soklet.Request',
    'com.soklet.TraceContext',
    'com.soklet.TraceStateEntry',
  ]);
  for (const { file } of sourceModels) {
    const contextName = file.match(
      /^src\/main\/java\/com\/soklet\/(Mcp[A-Za-z_$][\w$]*Context)\.java$/u,
    )?.[1];
    if (contextName !== undefined) {
      applicationRequestCarrierOwners.add(`com.soklet.${contextName}`);
    }
  }
  const applicationRequestCarrierSeedNames = new Set(
    [...applicationRequestCarrierOwners].map((owner) =>
      owner.slice(owner.lastIndexOf('.') + 1)),
  );
  let addedApplicationCarrierImplementation = true;
  while (addedApplicationCarrierImplementation) {
    addedApplicationCarrierImplementation = false;
    const selectedTypeNames = new Set(
      [...applicationRequestCarrierOwners].map((owner) =>
        owner.slice(owner.lastIndexOf('.') + 1)),
    );
    for (const { packageName, typeScopes } of sourceModels) {
      for (const scope of typeScopes) {
        const inheritance = scope.header.match(
          /\b(?:extends|implements)\b([\s\S]*)$/u,
        )?.[1];
        if (inheritance === undefined
            || ![...selectedTypeNames].some((name) =>
              new RegExp(`\\b${name}\\b`, 'u').test(inheritance))) continue;
        const owner = ownerAt(
          packageName,
          typeScopes,
          scope.opening,
          scope.name,
        );
        if (applicationRequestCarrierOwners.has(owner)) continue;
        applicationRequestCarrierOwners.add(owner);
        addedApplicationCarrierImplementation = true;
      }
    }
  }
  const mcpMetricsEventModel = sourceModels.find(({ file }) =>
    file === 'src/main/java/com/soklet/McpMetricsEvent.java');
  if (mcpMetricsEventModel === undefined) {
    fail('Privacy-boundary scan roots must include McpMetricsEvent.java.');
  }
  const mcpMetricsFactoryNames = new Set(mcpMetricsEventModel
    .visibleMemberDeclarations
    .filter(({ header, method, owner }) =>
      owner === 'com.soklet.McpMetricsEvent'
        && /\bstatic\b/u.test(header) && /^[a-z]/u.test(method))
    .map(({ method }) => method));
  if (mcpMetricsFactoryNames.size === 0) {
    fail('Privacy-boundary scanner found no McpMetricsEvent factory vocabulary.');
  }
  const metricsCollectorModel = sourceModels.find(({ file }) =>
    file === 'src/main/java/com/soklet/MetricsCollector.java');
  if (metricsCollectorModel === undefined) {
    fail('Privacy-boundary scan roots must include MetricsCollector.java.');
  }
  const metricsMethodNames = new Set(metricsCollectorModel
    .visibleMemberDeclarations
    .filter(({ owner }) => owner === 'com.soklet.MetricsCollector')
    .map(({ method }) => method));
  if (metricsMethodNames.size === 0) {
    fail('Privacy-boundary scanner found no MetricsCollector callback vocabulary.');
  }
  const metricsInvocationPattern = new RegExp(
    `\\.\\s*(${[...metricsMethodNames].sort(compareAscii).join('|')})\\s*\\(`,
    'gu',
  );
  const logEventModel = sourceModels.find(({ file }) =>
    file === 'src/main/java/com/soklet/LogEvent.java');
  if (logEventModel === undefined) {
    fail('Privacy-boundary scan roots must include LogEvent.java.');
  }
  const logEventAttachmentNames = new Set(logEventModel
    .visibleMemberDeclarations
    .filter(({ owner }) => owner === 'com.soklet.LogEvent.Builder'
      || owner === 'com.soklet.LogEvent.Copier')
    .map(({ method }) => method));
  if (logEventAttachmentNames.size === 0) {
    fail('Privacy-boundary scanner found no LogEvent attachment vocabulary.');
  }
  const logEventAttachmentPattern = new RegExp(
    `\\.\\s*(${[...logEventAttachmentNames].sort(compareAscii).join('|')})\\s*\\(`,
    'gu',
  );
  const loggerModel = sourceModels.find(({ file }) =>
    file === 'src/main/java/com/soklet/internal/microhttp/Logger.java');
  if (loggerModel === undefined) {
    fail('Privacy-boundary scan roots must include internal microhttp Logger.java.');
  }
  const microhttpLoggerMethodNames = new Set(loggerModel
    .visibleMemberDeclarations
    .filter(({ header, method, owner }) => {
      if (owner !== 'com.soklet.internal.microhttp.Logger') return false;
      const methodOffset = header.lastIndexOf(method);
      return methodOffset >= 0
        && /\bvoid\s*$/u.test(header.slice(0, methodOffset));
    })
    .map(({ method }) => method));
  if (microhttpLoggerMethodNames.size === 0) {
    fail('Privacy-boundary scanner found no internal microhttp Logger emission vocabulary.');
  }
  const microhttpLoggerInvocationPattern = new RegExp(
    `\\.\\s*(${[...microhttpLoggerMethodNames].sort(compareAscii).join('|')})\\s*\\(`,
    'gu',
  );

  for (const {
    file,
    methodScopes,
    packageName,
    source,
    structure,
    typeScopes,
    visibleFieldDeclarations,
    visibleMemberDeclarations,
  } of sourceModels) {
    const addMatch = (match, matcherRuleId, sink, explicitMethod) => {
      const location = privacyLocation(
        source,
        structure,
        packageName,
        typeScopes,
        methodScopes,
        match.index,
        explicitMethod,
      );
      add({
        file,
        index: match.index,
        matcherRuleId,
        sink,
        source,
        ...location,
      });
    };
    const staticLogEventWith = /\bimport\s+static\s+com\.soklet\.LogEvent\.(?:with|\*)\s*;/u
      .test(structure);
    const importedMcpMetricsFactories = new Set();
    for (const match of structure.matchAll(
      /\bimport\s+static\s+com\.soklet\.McpMetricsEvent\.([A-Za-z_$*][\w$]*)\s*;/gu,
    )) {
      if (match[1] === '*') {
        for (const name of mcpMetricsFactoryNames) {
          importedMcpMetricsFactories.add(name);
        }
      } else if (mcpMetricsFactoryNames.has(match[1])) {
        importedMcpMetricsFactories.add(match[1]);
      }
    }
    const logBuilderReceivers = new Set([...structure.matchAll(
      /\bLogEvent\s*\.\s*(?:Builder|Copier)\s+([A-Za-z_$][\w$]*)/gu,
    )].map((match) => match[1]));
    if (/\bimport\s+com\.soklet\.LogEvent\.(?:Builder|Copier|\*)\s*;/u
      .test(structure)) {
      for (const match of structure.matchAll(
        /\b(?:Builder|Copier)\s+([A-Za-z_$][\w$]*)/gu,
      )) logBuilderReceivers.add(match[1]);
    }
    for (const match of structure.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*(?:LogEvent\s*\.\s*)?(?:with|builder)\s*\(/gu,
    )) logBuilderReceivers.add(match[1]);
    for (const match of structure.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*[^;\r\n]*\.\s*copy\s*\(/gu,
    )) logBuilderReceivers.add(match[1]);
    const logAttachmentContext = {
      builderReceivers: logBuilderReceivers,
      staticWith: staticLogEventWith,
    };

    for (const declaration of visibleMemberDeclarations) {
      const simpleOwner = declaration.owner.slice(
        declaration.owner.lastIndexOf('.') + 1,
      );
      if (!throwableTypeNames.has(simpleOwner)
          || PRIVACY_NON_THROWABLE_ERROR_TYPES.has(simpleOwner)) continue;
      const visibleConstructor = declaration.method === simpleOwner;
      const sink = visibleConstructor ? 'ExceptionCarrier.constructor'
        : declaration.method === 'toString'
            && declaration.parameters.trim().length === 0
          ? 'ExceptionCarrier.diagnosticRenderer'
          : 'ExceptionCarrier.publicOrProtectedMethod';
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-012',
        member: declaration.member,
        owner: declaration.owner,
        sink,
        source,
      });
    }
    for (const declaration of visibleFieldDeclarations) {
      const metricsSurface = declaration.owner === 'com.soklet.McpMetricsEvent'
        || declaration.owner.startsWith('com.soklet.McpMetricsEvent.')
        || declaration.owner === 'com.soklet.McpMetricsSnapshot'
        || declaration.owner.startsWith('com.soklet.McpMetricsSnapshot.');
      if (!metricsSurface) continue;
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-002',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'McpMetricsSurface.field',
        source,
      });
    }

    for (const declaration of visibleFieldDeclarations) {
      if (!containsPrivacyCarrierType(declaration.type, throwableTypeNames)) {
        continue;
      }
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'RequestOrThrowable.field',
        source,
      });
    }
    const applicationCarrierOwner = (owner) =>
      [...applicationRequestCarrierOwners].some((baseOwner) =>
        owner === baseOwner || owner.startsWith(`${baseOwner}.`));
    const mentionsApplicationCarrier = (value) =>
      [...value.matchAll(/\b([A-Za-z_$][\w$]*)\b/gu)]
        .some((match) => applicationRequestCarrierSeedNames.has(match[1]));
    for (const declaration of visibleMemberDeclarations) {
      if (!applicationCarrierOwner(declaration.owner)) continue;
      if (declaration.method === 'toString'
          && declaration.parameters.trim().length === 0) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.member,
        owner: declaration.owner,
        sink: `ApplicationRequestCarrier.surface.${declaration.method}`,
        source,
      });
    }
    for (const declaration of visibleFieldDeclarations) {
      if (!applicationCarrierOwner(declaration.owner)) continue;
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'ApplicationRequestCarrier.surface.field',
        source,
      });
    }
    for (const declaration of visibleMemberDeclarations) {
      if (applicationCarrierOwner(declaration.owner)) continue;
      const methodOffset = declaration.header.lastIndexOf(
        declaration.method,
      );
      const returnPortion = methodOffset < 0 ? ''
        : declaration.header.slice(0, methodOffset);
      if (!mentionsApplicationCarrier(declaration.parameters)
          && !mentionsApplicationCarrier(returnPortion)) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.member,
        owner: declaration.owner,
        sink: 'ApplicationRequestCarrier.declaration',
        source,
      });
    }
    for (const declaration of visibleFieldDeclarations) {
      if (applicationCarrierOwner(declaration.owner)
          || !mentionsApplicationCarrier(declaration.type)) continue;
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'ApplicationRequestCarrier.field',
        source,
      });
    }

    const addLogEventWith = (match) => {
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      const argumentsText = closing > opening
        ? structure.slice(opening + 1, closing) : '';
      const eventType = argumentsText.match(
        /\bLogEventType\s*\.\s*([A-Z][A-Z0-9_]*)/u,
      )?.[1] ?? 'DYNAMIC';
      addMatch(match, 'PRIV-MATCH-001', `LogEvent.with:${eventType}`);
    };
    for (const match of structure.matchAll(/\bLogEvent\s*\.\s*with\s*\(/gu)) {
      addLogEventWith(match);
    }
    for (const match of structure.matchAll(/\bLogEvent\s*::\s*with\b/gu)) {
      addMatch(match, 'PRIV-MATCH-001', 'LogEvent.with:DYNAMIC');
    }
    if (staticLogEventWith) {
      for (const match of structure.matchAll(/(?<![\w$.])with\s*\(/gu)) {
        addLogEventWith(match);
      }
    }

    for (const match of structure.matchAll(
      /\bMcpMetricsEvent\s*\.\s*([a-z][\w$]*)\s*\(/gu,
    )) {
      addMatch(match, 'PRIV-MATCH-002', `McpMetricsEvent.${match[1]}`);
    }
    for (const match of structure.matchAll(
      /\bMcpMetricsEvent\s*::\s*([a-z][\w$]*)\b/gu,
    )) {
      if (!mcpMetricsFactoryNames.has(match[1])) continue;
      addMatch(match, 'PRIV-MATCH-002', `McpMetricsEvent.${match[1]}`);
    }
    for (const factoryName of [...importedMcpMetricsFactories]
      .sort(compareAscii)) {
      const unqualifiedFactoryPattern = new RegExp(
        `(?<![\\w$.])(${factoryName})\\s*\\(`,
        'gu',
      );
      for (const match of structure.matchAll(unqualifiedFactoryPattern)) {
        addMatch(match, 'PRIV-MATCH-002', `McpMetricsEvent.${factoryName}`);
      }
    }
    for (const declaration of visibleMemberDeclarations) {
      const metricsSurface = declaration.owner === 'com.soklet.McpMetricsEvent'
        || declaration.owner.startsWith('com.soklet.McpMetricsEvent.')
        || declaration.owner === 'com.soklet.McpMetricsSnapshot'
        || declaration.owner.startsWith('com.soklet.McpMetricsSnapshot.');
      if (!metricsSurface) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-002',
        member: declaration.member,
        owner: declaration.owner,
        sink: `McpMetricsSurface.${declaration.method}`,
        source,
      });
    }
    for (const declaration of visibleFieldDeclarations) {
      const metricsOwned = declaration.owner === 'com.soklet.MetricsCollector'
        || declaration.owner.startsWith('com.soklet.MetricsCollector.');
      if (!metricsOwned) continue;
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-015',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'MetricsCollector.surface.field',
        source,
      });
    }

    for (const declaration of visibleMemberDeclarations) {
      const metricsOwned = declaration.owner === 'com.soklet.MetricsCollector'
        || declaration.owner.startsWith('com.soklet.MetricsCollector.');
      if (!metricsOwned && !metricsMethodNames.has(declaration.method)) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-015',
        member: declaration.member,
        owner: declaration.owner,
        sink: metricsOwned
          ? `MetricsCollector.surface.${declaration.method}`
          : `MetricsCollector.override.${declaration.method}`,
        source,
      });
    }
    for (const match of structure.matchAll(metricsInvocationPattern)) {
      addMatch(
        match,
        'PRIV-MATCH-015',
        `MetricsCollector.invocation.${match[1]}`,
      );
    }

    for (const match of structure.matchAll(/\.\s*request\s*\(/gu)) {
      const location = privacyLocation(
        source,
        structure,
        packageName,
        typeScopes,
        methodScopes,
        match.index,
      );
      if (!isLogRequestAttachment(
        structure,
        location.owner,
        match.index,
        logAttachmentContext,
      )) continue;
      add({
        file,
        index: match.index,
        matcherRuleId: 'PRIV-MATCH-003',
        sink: 'LogEvent.Builder.request',
        source,
        ...location,
      });
    }

    for (const match of structure.matchAll(/\.\s*throwable\s*\(/gu)) {
      addMatch(match, 'PRIV-MATCH-004', 'LogEvent.Builder.throwable');
    }

    for (const declaration of visibleMemberDeclarations) {
      if (!(declaration.owner === 'com.soklet.LogEvent'
          || declaration.owner.startsWith('com.soklet.LogEvent.'))) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-016',
        member: declaration.member,
        owner: declaration.owner,
        sink: `LogEvent.surface.${declaration.method}`,
        source,
      });
    }
    for (const match of structure.matchAll(logEventAttachmentPattern)) {
      const location = privacyLocation(
        source,
        structure,
        packageName,
        typeScopes,
        methodScopes,
        match.index,
      );
      if (!isLogRequestAttachment(
        structure,
        location.owner,
        match.index,
        logAttachmentContext,
      )) {
        continue;
      }
      add({
        file,
        index: match.index,
        matcherRuleId: 'PRIV-MATCH-016',
        sink: `LogEvent.attachment.${match[1]}`,
        source,
        ...location,
      });
    }

    const exposurePattern = /\b(didStartRequestHandling|didFinishRequestHandling|didStartMcpRequestHandling|didFinishMcpRequestHandling|getRequest|getThrowables)\s*\(|\b(request|throwables)\s*\(\s*\)/gu;
    for (const match of structure.matchAll(exposurePattern)) {
      const method = match[1] ?? match[2];
      addMatch(
        match,
        'PRIV-MATCH-005',
        `RequestOrThrowable.${method}`,
        method,
      );
    }

    for (const declaration of visibleMemberDeclarations) {
      const methodOffset = declaration.header.lastIndexOf(
        declaration.method,
      );
      const returnPortion = methodOffset < 0 ? ''
        : declaration.header.slice(0, methodOffset);
      if (!containsPrivacyCarrierType(
        declaration.parameters,
        throwableTypeNames,
      ) && !containsPrivacyCarrierType(returnPortion, throwableTypeNames)) {
        continue;
      }
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-005',
        member: declaration.member,
        owner: declaration.owner,
        sink: 'RequestOrThrowable.declaration',
        source,
      });
    }

    for (const match of structure.matchAll(
      /\brecord\s+([A-Za-z_$][\w$]*)(?:\s*<[^{};()]*>)?\s*\(/gu,
    )) {
      const recordName = match[1];
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      if (closing < 0) {
        fail(`Privacy-boundary scanner found an unterminated record ${recordName} in ${file}.`);
      }
      const componentsText = structure.slice(opening + 1, closing);
      const componentDeclarations = componentsText.trim().length === 0
        ? [] : splitTopLevel(componentsText);
      const owner = ownerAt(
        packageName,
        typeScopes,
        match.index,
        recordName,
      );
      const allComponents = componentDeclarations.map((component) => {
          const name = parameterName(component);
          if (name === undefined) {
            fail(`Privacy-boundary scanner cannot resolve a record component in ${file}.`);
          }
          const type = component.slice(0, component.lastIndexOf(name));
          return { name, type };
        });
      const carrierComponents = applicationCarrierOwner(owner)
        ? allComponents : allComponents.filter(({ type }) =>
          containsPrivacyCarrierType(type, throwableTypeNames)
            || mentionsApplicationCarrier(type));
      if (carrierComponents.length === 0
          && !applicationCarrierOwner(owner)) continue;
      add({
        file,
        index: match.index,
        matcherRuleId: 'PRIV-MATCH-005',
        member: methodMember(
          recordName,
          componentsText,
          file,
          source.slice(0, match.index).split(/\r?\n/u).length,
          'Privacy-boundary',
        ),
        owner,
        sink: 'RequestOrThrowable.recordConstructor',
        source,
      });
      for (const { name } of carrierComponents) {
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-005',
          member: `${name}()`,
          owner,
          sink: 'RequestOrThrowable.recordAccessor',
          source,
        });
      }
      const explicitRecordRenderer = methodScopes.some((scope) =>
        scope.owner === owner && scope.method === 'toString'
          && scope.parameters.trim().length === 0);
      if (!explicitRecordRenderer) {
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-005',
          member: 'toString()',
          owner,
          sink: 'RequestOrThrowable.recordRenderer',
          source,
        });
      }
    }

    const requestExceptionPattern = /\bnew\s+((?:Illegal(?:FormParameter|MultipartField|PathParameter|QueryParameter|RequestBody|RequestCookie|RequestHeader|Request)|Missing(?:FormParameter|MultipartField|QueryParameter|RequestBody|RequestCookie|RequestHeader)|MultipleValues)Exception)\s*\(/gu;
    for (const match of structure.matchAll(requestExceptionPattern)) {
      addMatch(
        match,
        'PRIV-MATCH-006',
        `RequestException.${match[1]}`,
      );
    }

    const genericFixtureOwner = /^com\.soklet\.(?:Simulator|SokletSimulator|HttpRequestResult|SseRequestResult|SseHandshakeResult)(?:\.|$)/u;
    const mcpFixtureOwners = [
      'com.soklet.McpSimulation',
      'com.soklet.McpSimulationCompletion',
      'com.soklet.McpSimulationResponse',
      'com.soklet.McpSimulationStreamItem',
      'com.soklet.internal.mcp.protocol.McpSimulationRuntime',
    ];
    for (const declaration of visibleMemberDeclarations) {
      const genericFixture = genericFixtureOwner.test(declaration.owner);
      const mcpFixture = mcpFixtureOwners.some((owner) =>
        declaration.owner === owner
          || declaration.owner.startsWith(`${owner}.`));
      if (!genericFixture && !mcpFixture) continue;
      add({
        file,
        index: declaration.headerStart,
        matcherRuleId: 'PRIV-MATCH-007',
        member: declaration.member,
        owner: declaration.owner,
        sink: mcpFixture ? 'McpSimulation.publicOrProtectedSurface'
          : 'SimulationCapture.publicOrProtectedSurface',
        source,
      });
    }
    if (/^src\/main\/java\/com\/soklet\/(?:McpSimulation[^/]*|internal\/mcp\/(?:[^/]+\/)*McpSimulation[^/]*)\.java$/u
      .test(file)) {
      const simulationCapturePattern = /\bnew\s+(DefaultResponse|DefaultStreamItem|CapturedItem|DefaultCompletion)\s*\(|\b(awaitResponse|awaitStreamItem|awaitCompletion|getHeaders|getBody|getEncodedBytes|getTerminalMessage|getThrowables)\s*\(/gu;
      for (const match of structure.matchAll(simulationCapturePattern)) {
        const operation = match[1] ?? match[2];
        addMatch(
          match,
          'PRIV-MATCH-007',
          `McpSimulation.${operation}`,
          match[2],
        );
      }
    }

    for (const match of structure.matchAll(
      /\bString\s+(toString)\s*\(\s*\)/gu,
    )) {
      addMatch(
        match,
        'PRIV-MATCH-008',
        'Diagnostic.toString',
        match[1],
      );
    }
    for (const scope of methodScopes) {
      const simpleOwner = scope.owner.slice(scope.owner.lastIndexOf('.') + 1);
      const diagnosticOwner = /(?:Diagnostic|Diagnostics|Summary|TerminalReporter)$/u
        .test(simpleOwner);
      if (!diagnosticOwner
          && !/^(?:diagnosticSummary|render|summary)$/u.test(scope.method)) {
        continue;
      }
      add({
        file,
        index: scope.headerStart,
        matcherRuleId: 'PRIV-MATCH-008',
        member: methodMember(
          scope.method,
          scope.parameters,
          file,
          scope.line,
          'Privacy-boundary',
        ),
        owner: scope.owner,
        sink: `Diagnostic.structuredSurface.${scope.method}`,
        source,
      });
    }
    for (const match of structure.matchAll(
      /\brecord\s+([A-Za-z_$][\w$]*)(?:\s*<[^{};()]*>)?\s*\(/gu,
    )) {
      const recordName = match[1];
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      if (closing < 0) {
        fail(`Privacy-boundary scanner found an unterminated record ${recordName} in ${file}.`);
      }
      const componentsText = structure.slice(opening + 1, closing);
      const componentDeclarations = componentsText.trim().length === 0
        ? [] : splitTopLevel(componentsText);
      const components = componentDeclarations.map((component) => {
        const name = parameterName(component);
        if (name === undefined) {
          fail(`Privacy-boundary scanner cannot resolve a record component in ${file}.`);
        }
        return { declaration: component, name };
      });
      const owner = ownerAt(
        packageName,
        typeScopes,
        match.index,
        recordName,
      );
      const requestOrThrowableRecord = applicationCarrierOwner(owner)
        || containsPrivacyCarrierType(componentsText, throwableTypeNames)
        || mentionsApplicationCarrier(componentsText);
      if (!requestOrThrowableRecord) {
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-008',
          member: methodMember(
            recordName,
            componentsText,
            file,
            source.slice(0, match.index).split(/\r?\n/u).length,
            'Privacy-boundary',
          ),
          owner,
          sink: 'Diagnostic.recordConstructor',
          source,
        });
      }
      for (const { declaration, name } of components) {
        const carrierComponent = applicationCarrierOwner(owner)
          || containsPrivacyCarrierType(declaration, throwableTypeNames)
          || mentionsApplicationCarrier(declaration);
        if (carrierComponent) continue;
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-008',
          member: `${name}()`,
          owner,
          sink: 'Diagnostic.recordAccessor',
          source,
        });
      }
      const explicitRecordRenderer = methodScopes.some((scope) =>
        scope.owner === owner && scope.method === 'toString'
          && scope.parameters.trim().length === 0);
      if (!explicitRecordRenderer && !requestOrThrowableRecord) {
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-008',
          member: 'toString()',
          owner,
          sink: 'Diagnostic.recordRenderer',
          source,
        });
      }
    }

    const wireErrorOwners = new Set([
      'com.soklet.McpJsonRpcError',
      'com.soklet.McpJsonRpcException',
      'com.soklet.internal.mcp.protocol.McpJsonRpcError',
    ]);
    for (const scope of methodScopes) {
      if (!wireErrorOwners.has(scope.owner)) continue;
      add({
        file,
        index: scope.headerStart,
        matcherRuleId: 'PRIV-MATCH-009',
        member: methodMember(
          scope.method,
          scope.parameters,
          file,
          scope.line,
          'Privacy-boundary',
        ),
        owner: scope.owner,
        sink: `McpWireError.surface.${scope.method}`,
        source,
      });
    }
    for (const declaration of visibleFieldDeclarations) {
      if (!wireErrorOwners.has(declaration.owner)) continue;
      add({
        file,
        index: declaration.index,
        matcherRuleId: 'PRIV-MATCH-009',
        member: declaration.field,
        owner: declaration.owner,
        sink: 'McpWireError.surface.field',
        source,
      });
    }
    for (const match of structure.matchAll(
      /\brecord\s+(McpJsonRpcError)(?:\s*<[^{};()]*>)?\s*\(/gu,
    )) {
      const opening = structure.indexOf('(', match.index);
      const closing = matchingDelimiter(structure, opening, '(', ')');
      if (closing < 0) {
        fail(`Privacy-boundary scanner found an unterminated record ${match[1]} in ${file}.`);
      }
      const componentsText = structure.slice(opening + 1, closing);
      const componentNames = componentsText.trim().length === 0 ? []
        : splitTopLevel(componentsText).map((component) => {
          const name = parameterName(component);
          if (name === undefined) {
            fail(`Privacy-boundary scanner cannot resolve a wire-error record component in ${file}.`);
          }
          return name;
        });
      const owner = ownerAt(packageName, typeScopes, match.index, match[1]);
      if (!wireErrorOwners.has(owner)) continue;
      add({
        file,
        index: match.index,
        matcherRuleId: 'PRIV-MATCH-009',
        member: methodMember(
          match[1],
          componentsText,
          file,
          source.slice(0, match.index).split(/\r?\n/u).length,
          'Privacy-boundary',
        ),
        owner,
        sink: 'McpWireError.recordConstructor',
        source,
      });
      for (const componentName of componentNames) {
        add({
          file,
          index: match.index,
          matcherRuleId: 'PRIV-MATCH-009',
          member: `${componentName}()`,
          owner,
          sink: 'McpWireError.recordAccessor',
          source,
        });
      }
    }
    const wireErrorReceivers = new Set([...structure.matchAll(
      /\b(?:(?:[A-Za-z_$][\w$]*\s*\.\s*)*)(?:McpJsonRpcError|McpJsonRpcException)\s+([A-Za-z_$][\w$]*)/gu,
    )].map((match) => match[1]));
    for (const receiver of wireErrorReceivers) {
      const accessorPattern = new RegExp(
        `\\b${receiver}\\s*\\.\\s*(getError|getCode|getMessage|getData|code|message|data|toJsonObject)\\s*\\(`,
        'gu',
      );
      for (const match of structure.matchAll(accessorPattern)) {
        addMatch(
          match,
          'PRIV-MATCH-009',
          `McpWireError.publication.${match[1]}`,
        );
      }
    }
    const wireErrorPattern = /\bnew\s+(?:(?:[A-Za-z_$][\w$]*\s*\.\s*)*)(McpJsonRpcError|McpJsonRpcException)\s*\(|\bMcpJsonRpcError\s*\.\s*(fromApplication|fromInvalidParameters|fromServer)\s*\(|\bcatch\s*\(\s*(?:(?:[A-Za-z_$][\w$]*\s*\.\s*)*)(McpJsonRpcException)\b/gu;
    for (const match of structure.matchAll(wireErrorPattern)) {
      const operation = match[1] === 'McpJsonRpcException'
        || match[3] === 'McpJsonRpcException'
        ? 'McpJsonRpcException'
        : match[2] === undefined ? 'McpJsonRpcError.constructor'
          : `McpJsonRpcError.${match[2]}`;
      addMatch(match, 'PRIV-MATCH-009', operation);
    }

    if (file.startsWith('src/main/java/com/soklet/internal/microhttp/')) {
      for (const declaration of visibleMemberDeclarations) {
        if (declaration.owner !== 'com.soklet.internal.microhttp.Logger'
            || !microhttpLoggerMethodNames.has(declaration.method)) continue;
        add({
          file,
          index: declaration.headerStart,
          matcherRuleId: 'PRIV-MATCH-013',
          member: declaration.member,
          owner: declaration.owner,
          sink: `MicrohttpLogger.surface.${declaration.method}`,
          source,
        });
      }
      for (const match of structure.matchAll(
        microhttpLoggerInvocationPattern,
      )) {
        addMatch(
          match,
          'PRIV-MATCH-013',
          `MicrohttpLogger.invocation.${match[1]}`,
        );
      }
      if (file === 'src/main/java/com/soklet/internal/microhttp/EventLoop.java') {
        for (const scope of methodScopes) {
          if (scope.owner !== 'com.soklet.internal.microhttp.EventLoop'
              || scope.method !== 'EventLoop'
              || /\bLogger\b/u.test(scope.parameters)) continue;
          add({
            file,
            index: scope.headerStart,
            matcherRuleId: 'PRIV-MATCH-013',
            member: methodMember(
              scope.method,
              scope.parameters,
              file,
              scope.line,
              'Privacy-boundary',
            ),
            owner: scope.owner,
            sink: /\bNoopLogger\s*\.\s*instance\s*\(/u.test(scope.body)
              ? 'MicrohttpLogger.EventLoopDefaultWiring:DEFAULT_NOOP'
              : 'MicrohttpLogger.EventLoopDefaultWiring:ALTERNATE',
            source,
          });
        }
      }
    }
    if (file.startsWith('src/main/java/com/soklet/internal/mcp/')) {
      for (const match of structure.matchAll(
        /\bnew\s+EventLoop\s*\(/gu,
      )) {
        const opening = structure.indexOf('(', match.index);
        const closing = matchingDelimiter(structure, opening, '(', ')');
        if (closing < 0) {
          fail(`Privacy-boundary scanner found unterminated MCP EventLoop wiring in ${file}.`);
        }
        const argumentsText = structure.slice(opening + 1, closing);
        const argumentCount = argumentsText.trim().length === 0 ? 0
          : splitTopLevel(argumentsText).length;
        const wiring = /\bNoopLogger\s*\.\s*instance\s*\(/u.test(argumentsText)
          ? 'EXPLICIT_NOOP' : argumentCount <= 2 ? 'DEFAULT_NOOP' : 'ALTERNATE';
        addMatch(
          match,
          'PRIV-MATCH-013',
          `MicrohttpLogger.McpEventLoopWiring:${wiring}`,
        );
      }
      for (const match of structure.matchAll(
        /\bNoopLogger\s*\.\s*(instance)\s*\(/gu,
      )) {
        addMatch(match, 'PRIV-MATCH-013', 'MicrohttpLogger.NoopLogger');
      }
    }

    for (const match of structure.matchAll(
      /\bSystem\s*\.\s*(err|out)\s*\.\s*(print|printf|println|write)\s*\(/gu,
    )) {
      addMatch(
        match,
        'PRIV-MATCH-014',
        `DirectOutput.System.${match[1]}.${match[2]}`,
      );
    }
    for (const match of structure.matchAll(
      /\bSystem\s*\.\s*(err|out)\s*::\s*(print|printf|println|write)\b/gu,
    )) {
      addMatch(
        match,
        'PRIV-MATCH-014',
        `DirectOutput.System.${match[1]}.${match[2]}`,
      );
    }
    const directOutputReceivers = new Map();
    for (const match of structure.matchAll(
      /\b(PrintStream|OutputStream)\s+([A-Za-z_$][\w$]*)/gu,
    )) {
      if (match[1] === 'PrintStream'
          || file === 'src/main/java/com/soklet/SokletApplicationTerminalReporter.java') {
        directOutputReceivers.set(match[2], match[1]);
      }
    }
    for (const match of structure.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*System\s*\.\s*(err|out)\s*;/gu,
    )) {
      directOutputReceivers.set(
        match[1],
        match[2] === 'err' ? 'SystemErrAlias' : 'SystemOutAlias',
      );
    }
    for (const [receiver, receiverType] of directOutputReceivers) {
      const receiverPattern = new RegExp(
        `\\b(?:this\\s*\\.\\s*)?${receiver}\\s*\\.\\s*(print|printf|println|write)\\s*\\(`,
        'gu',
      );
      for (const match of structure.matchAll(receiverPattern)) {
        addMatch(
          match,
          'PRIV-MATCH-014',
          `DirectOutput.${receiverType}.${match[1]}`,
        );
      }
      const receiverReferencePattern = new RegExp(
        `\\b(?:this\\s*\\.\\s*)?${receiver}\\s*::\\s*(print|printf|println|write)\\b`,
        'gu',
      );
      for (const match of structure.matchAll(receiverReferencePattern)) {
        addMatch(
          match,
          'PRIV-MATCH-014',
          `DirectOutput.${receiverType}.${match[1]}`,
        );
      }
    }
    for (const match of structure.matchAll(
      /\b[A-Za-z_$][\w$]*\s*\.\s*(printStackTrace)\s*\(/gu,
    )) {
      addMatch(
        match,
        'PRIV-MATCH-014',
        'DirectOutput.Throwable.printStackTrace',
      );
    }

    const throwableConstructionPattern = /\bnew\s+([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)\s*\(/gu;
    for (const match of structure.matchAll(throwableConstructionPattern)) {
      const typeName = match[1].replace(/\s+/gu, '');
      const simpleTypeName = typeName.slice(typeName.lastIndexOf('.') + 1);
      if (PRIVACY_NON_THROWABLE_ERROR_TYPES.has(simpleTypeName)) continue;
      if (!throwableTypeNames.has(simpleTypeName)
          && !simpleTypeName.endsWith('Exception')
          && !simpleTypeName.endsWith('Error')) continue;
      addMatch(
        match,
        'PRIV-MATCH-011',
        `Throwable.${typeName}`,
      );
    }
  }

  for (const { file, rootIndex } of privacyArtifactFiles(
    normalizedRoot,
    artifactRoots,
    gitExecutable,
  )) {
    add({
      file,
      matcherRuleId: 'PRIV-MATCH-010',
      member: '$trackedArtifact()',
      owner: 'com.soklet.privacy.FixtureArtifact',
      sink: `FixtureArtifact.ROOT_${String(rootIndex + 1).padStart(3, '0')}`,
    });
  }

  return candidates.sort((left, right) => compareAscii(left.key, right.key));
}

function validatePrivacyClassification(row, label) {
  assertExactKeys(row, PRIVACY_SOURCE_PATH_KEYS, label);
  normalizedCandidatePath(row.file, `${label}.file`);
  nonblank(row.owner, `${label}.owner`);
  nonblank(row.member, `${label}.member`);
  nonblank(row.sink, `${label}.sink`);
  if (!JAVA_OWNER_PATTERN.test(row.owner)) {
    fail(`${label}.owner must be an exact qualified Java owner.`);
  }
  if (!JAVA_MEMBER_PATTERN.test(row.member)) {
    fail(`${label}.member must be one exact Java method or initializer identity.`);
  }
  if (!PRIVACY_SINK_PATTERN.test(row.sink)) {
    fail(`${label}.sink must be one exact executable privacy sink.`);
  }
  if (!Number.isSafeInteger(row.occurrence) || row.occurrence < 1) {
    fail(`${label}.occurrence must be a positive safe integer.`);
  }
  if (!PRIVACY_MATCHER_IDS.has(row.matcherRuleId)) {
    fail(`${label}.matcherRuleId is unknown.`);
  }
  const expectedKey = privacyKey(
    row.matcherRuleId,
    row.file,
    row.owner,
    row.member,
    row.sink,
    row.occurrence,
  );
  if (row.key !== expectedKey) {
    fail(`${label}.key must be exactly ${expectedKey}.`);
  }
}

function validatePrivacyCanaryTests(tests, label, projectRoot, required) {
  if (!Array.isArray(tests) || (required && tests.length === 0)) {
    fail(`${label} must be ${required ? 'a nonempty' : 'an'} array.`);
  }
  if (new Set(tests).size !== tests.length) {
    fail(`${label} must not contain duplicates.`);
  }
  const sorted = [...tests].sort(compareAscii);
  for (const [index, reference] of tests.entries()) {
    if (reference !== sorted[index]) fail(`${label} must be in ASCII order.`);
    nonblank(reference, `${label}[${index}]`);
    const parts = reference.split('#');
    if (parts.length > 2 || (parts.length === 2
        && !/^[A-Za-z_$][\w$]*$/u.test(parts[1]))) {
      fail(`${label}[${index}] must be a test source with an optional exact method name.`);
    }
    const file = parts[0];
    normalizedCandidatePath(file, `${label}[${index}]`);
    if (!(file.startsWith('src/test/java/')
        || file.startsWith('fuzz/src/test/java/'))
        || !file.endsWith('.java')) {
      fail(`${label}[${index}] must name a Java test source.`);
    }
    requireContainedPath(
      projectRoot,
      resolve(projectRoot, file),
      `${label}[${index}]`,
      'file',
      'Privacy-boundary',
    );
    if (parts.length === 2) {
      const path = resolve(projectRoot, file);
      const bytes = readFileSync(path);
      const source = bytes.toString('utf8');
      if (!Buffer.from(source, 'utf8').equals(bytes)) {
        fail(`${label}[${index}] test source is not valid UTF-8.`);
      }
      const structure = maskJava(source);
      const typeScopes = javaTypeScopes(structure);
      const methodExists = javaMethodScopes(
        source,
        structure,
        typeScopes,
      ).some(({ method }) => method === parts[1]);
      if (!methodExists) {
        fail(`${label}[${index}] names no declared test method: ${parts[1]}.`);
      }
    }
  }
}

function validatePrivacySourcePaths(
  sourcePaths,
  label,
  classifications,
  semanticClassification,
) {
  if (!Array.isArray(sourcePaths)) fail(`${label} must be an array.`);
  const sorted = sourcePaths.map(({ key }) => key).sort(compareAscii);
  for (const [index, sourcePath] of sourcePaths.entries()) {
    const sourceLabel = `${label}[${index}]`;
    validatePrivacyClassification(sourcePath, sourceLabel);
    if (sourcePath.key !== sorted[index]) fail(`${label} must be in ASCII key order.`);
    classifications.push({
      ...sourcePath,
      location: sourceLabel,
      semanticClassification,
    });
  }
}

export function verifyPrivacyBoundaryInventory(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const inventoryPath = resolve(
    options.inventoryPath
      ?? resolve(projectRoot, PRIVACY_BOUNDARY_INVENTORY_PATH),
  );
  requireContainedPath(
    projectRoot,
    inventoryPath,
    'Privacy-boundary inventory',
    'file',
    'Privacy-boundary',
  );
  const { value: inventory } = readCanonicalJson(
    inventoryPath,
    'Privacy-boundary inventory',
  );
  assertExactKeys(
    inventory,
    PRIVACY_TOP_LEVEL_KEYS,
    'Privacy-boundary inventory',
  );
  if (inventory.formatVersion !== 1
      || inventory.productionProfile !== EXPECTED_PROTOCOL_VERSION
      || inventory.releaseTarget !== '4.0.0') {
    fail('Privacy-boundary inventory format, profile, or release target is invalid.');
  }
  if (JSON.stringify(inventory.matcherRules)
      !== JSON.stringify(PRIVACY_MATCHER_RULES)) {
    fail('Privacy-boundary matcherRules do not match the executable matcher contract.');
  }
  const expectedArtifactRoots = options.expectedArtifactRoots
    ?? PRIVACY_ARTIFACT_ROOTS;
  assertExactArray(
    inventory.artifactRoots,
    expectedArtifactRoots,
    'Privacy-boundary artifactRoots',
  );
  const expectedScanRoots = options.expectedScanRoots ?? PRIVACY_SCAN_ROOTS;
  assertExactArray(
    inventory.scanRoots,
    expectedScanRoots,
    'Privacy-boundary scanRoots',
  );
  const candidates = derivePrivacyBoundaryCandidates(
    projectRoot,
    expectedScanRoots,
    expectedArtifactRoots,
    options.gitExecutable ?? 'git',
  );
  for (const { id } of PRIVACY_MATCHER_RULES) {
    if (!candidates.some(({ matcherRuleId }) => matcherRuleId === id)) {
      fail(`Privacy-boundary matcher family derived no candidate: ${id}.`);
    }
  }

  if (!Array.isArray(inventory.boundaries) || inventory.boundaries.length === 0) {
    fail('Privacy-boundary boundaries must be a nonempty array.');
  }
  const classifications = [];
  const boundaryIds = new Set();
  const sortedBoundaryIds = inventory.boundaries.map(({ id }) => id)
    .sort(compareAscii);
  for (const [index, boundary] of inventory.boundaries.entries()) {
    const label = `boundaries[${index}]`;
    assertExactKeys(boundary, PRIVACY_BOUNDARY_KEYS, label);
    if (typeof boundary.id !== 'string'
        || !PRIVACY_BOUNDARY_ID_PATTERN.test(boundary.id)
        || boundaryIds.has(boundary.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    boundaryIds.add(boundary.id);
    if (boundary.id !== sortedBoundaryIds[index]) {
      fail('Privacy-boundary boundaries must be in ASCII ID order.');
    }
    for (const field of ['contract', 'name']) {
      nonblank(boundary[field], `${label}.${field}`);
    }
    if (!PRIVACY_CATEGORIES.has(boundary.category)) {
      fail(`${label}.category is unknown.`);
    }
    if (!PRIVACY_CLASSIFICATIONS.has(boundary.classification)) {
      fail(`${label}.classification is unknown.`);
    }
    if (!Array.isArray(boundary.sourcePaths)
        || boundary.sourcePaths.length === 0) {
      fail(`${label}.sourcePaths must be a nonempty array.`);
    }
    validatePrivacyCanaryTests(
      boundary.canaryTests,
      `${label}.canaryTests`,
      projectRoot,
      true,
    );
    validatePrivacySourcePaths(
      boundary.sourcePaths,
      `${label}.sourcePaths`,
      classifications,
      boundary.classification,
    );
  }

  if (!Array.isArray(inventory.delegations)
      || inventory.delegations.length === 0) {
    fail('Privacy-boundary delegations must be a nonempty array.');
  }
  const delegationIds = new Set();
  const sortedDelegationIds = inventory.delegations.map(({ id }) => id)
    .sort(compareAscii);
  for (const [index, delegation] of inventory.delegations.entries()) {
    const label = `delegations[${index}]`;
    assertExactKeys(delegation, PRIVACY_DELEGATION_KEYS, label);
    if (typeof delegation.id !== 'string'
        || !PRIVACY_DELEGATION_ID_PATTERN.test(delegation.id)
        || delegationIds.has(delegation.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    delegationIds.add(delegation.id);
    if (delegation.id !== sortedDelegationIds[index]) {
      fail('Privacy-boundary delegations must be in ASCII ID order.');
    }
    for (const field of ['contract', 'name']) {
      nonblank(delegation[field], `${label}.${field}`);
    }
    nonblank(delegation.delegatedOwner, `${label}.delegatedOwner`);
    if (!/^[A-Z][A-Z0-9_]*$/u.test(delegation.delegatedOwner)) {
      fail(`${label}.delegatedOwner must be an uppercase stable owner token.`);
    }
    validatePrivacySourcePaths(
      delegation.sourcePaths,
      `${label}.sourcePaths`,
      classifications,
    );
    validatePrivacyCanaryTests(
      delegation.canaryTests,
      `${label}.canaryTests`,
      projectRoot,
      delegation.sourcePaths.length > 0,
    );
  }
  assertExactArray(
    inventory.delegations.map(({ delegatedOwner }) => delegatedOwner),
    PRIVACY_REQUIRED_DELEGATED_OWNERS,
    'Privacy-boundary delegated owners',
  );

  if (!Array.isArray(inventory.reviewedExclusions)) {
    fail('Privacy-boundary reviewedExclusions must be an array.');
  }
  const exclusionIds = new Set();
  const sortedExclusionKeys = inventory.reviewedExclusions.map(({ key }) => key)
    .sort(compareAscii);
  for (const [index, exclusion] of inventory.reviewedExclusions.entries()) {
    const label = `reviewedExclusions[${index}]`;
    assertExactKeys(exclusion, PRIVACY_EXCLUSION_KEYS, label);
    const classification = {
      file: exclusion.file,
      key: exclusion.key,
      matcherRuleId: exclusion.matcherRuleId,
      member: exclusion.member,
      occurrence: exclusion.occurrence,
      owner: exclusion.owner,
      sink: exclusion.sink,
    };
    validatePrivacyClassification(classification, label);
    if (typeof exclusion.id !== 'string'
        || !PRIVACY_EXCLUSION_ID_PATTERN.test(exclusion.id)
        || exclusionIds.has(exclusion.id)) {
      fail(`${label}.id is malformed or duplicated.`);
    }
    exclusionIds.add(exclusion.id);
    nonblank(exclusion.rationale, `${label}.rationale`);
    if (exclusion.key !== sortedExclusionKeys[index]) {
      fail('Privacy-boundary reviewedExclusions must be in ASCII key order.');
    }
    classifications.push({ ...classification, location: label });
  }

  const classificationsByKey = new Map();
  for (const classification of classifications) {
    if (classificationsByKey.has(classification.key)) {
      fail(`Privacy-boundary classification is duplicated at ${classificationsByKey.get(classification.key).location} and ${classification.location}: ${classification.key}.`);
    }
    classificationsByKey.set(classification.key, classification);
  }
  const concreteRenderers = new Map();
  for (const classification of classifications) {
    if (classification.semanticClassification === undefined
        || classification.member !== 'toString()'
        || !/(?:\.toString|recordRenderer|diagnosticRenderer)$/u
          .test(classification.sink)) {
      continue;
    }
    const concreteKey = [
      classification.file,
      classification.owner,
      classification.member,
    ].join('#');
    const previous = concreteRenderers.get(concreteKey);
    if (previous !== undefined
        && previous.semanticClassification
          !== classification.semanticClassification) {
      fail(`Privacy-boundary concrete renderer has conflicting classifications at ${previous.location} and ${classification.location}: ${concreteKey}.`);
    }
    concreteRenderers.set(concreteKey, classification);
  }
  const candidatesByKey = new Map(candidates.map((candidate) =>
    [candidate.key, candidate]));
  const omitted = candidates.filter(({ key }) => !classificationsByKey.has(key));
  const extra = classifications.filter(({ key }) => !candidatesByKey.has(key));
  if (omitted.length > 0 || extra.length > 0) {
    fail(`Privacy-boundary inventory differs from source derivation; omitted=[${omitted.map(({ key, line }) => `${key}@${line}`).join(', ')}], extra=[${extra.map(({ key }) => key).join(', ')}].`);
  }
  const expectedSemanticsSha256 = options.expectedSemanticsSha256
    ?? EXPECTED_PRIVACY_SEMANTICS_SHA256;
  if (!SHA256_PATTERN.test(expectedSemanticsSha256)) {
    fail('Expected privacy-boundary semantics SHA-256 must be 64 lowercase hexadecimal characters.');
  }
  const semanticsSha256 = privacySemanticsSha256(inventory);
  if (semanticsSha256 !== expectedSemanticsSha256) {
    fail(`Privacy-boundary semantic attribution SHA-256 differs from the reviewed contract: expected ${expectedSemanticsSha256}, found ${semanticsSha256}.`);
  }
  return {
    boundaries: inventory.boundaries,
    candidates,
    delegations: inventory.delegations,
    exclusions: inventory.reviewedExclusions,
    inventory,
  };
}

function readManifest(file) {
  if (!existsSync(file)) {
    fail(`Release manifest does not exist: ${file}`);
  }
  const stat = lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink()) {
    fail(`Release manifest must be a regular non-symlink file: ${file}`);
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`Release manifest is malformed JSON: ${error.message}`);
  }
  const releaseVersion = manifest?.candidate?.version;
  if (typeof releaseVersion !== 'string' || releaseVersion.length === 0) {
    fail('Release manifest candidate.version must be a nonempty string.');
  }
  if (!Array.isArray(manifest.gates)) {
    fail('Release manifest gates must be an array.');
  }
  const gateIds = manifest.gates.map((gate, index) => {
    if (gate === null || typeof gate !== 'object' || Array.isArray(gate)
        || typeof gate.id !== 'string' || gate.id.length === 0) {
      fail(`Release manifest gate ${index} has no valid id.`);
    }
    return gate.id;
  });
  if (new Set(gateIds).size !== gateIds.length) {
    fail('Release manifest contains duplicate gate IDs.');
  }
  assertExactArray(gateIds, EXPECTED_GATE_IDS, 'Release manifest gate IDs');
  return { releaseVersion, gateIds };
}

function assertContainedEvidence(projectRoot, reference, rowId, gitExecutable) {
  if (typeof reference !== 'string' || reference.length === 0) {
    fail(`Row ${rowId} contains an empty evidence reference.`);
  }
  if (reference.includes('\\') || isAbsolute(reference)
      || posix.normalize(reference) !== reference
      || reference === '.' || reference.startsWith('../')
      || reference.includes('/../')
      || reference === '.git' || reference.startsWith('.git/')
      || reference === 'target' || reference.startsWith('target/')) {
    fail(`Row ${rowId} evidence reference is not a normalized candidate-relative path: ${reference}`);
  }
  const target = resolve(projectRoot, reference);
  const lexicalRelative = relative(projectRoot, target);
  if (lexicalRelative === '..' || lexicalRelative.startsWith(`..${sep}`) || isAbsolute(lexicalRelative)) {
    fail(`Row ${rowId} evidence reference escapes the candidate: ${reference}`);
  }
  if (!existsSync(target)) {
    fail(`Row ${rowId} evidence reference does not exist: ${reference}`);
  }
  let component = projectRoot;
  for (const segment of reference.split('/')) {
    component = resolve(component, segment);
    if (lstatSync(component).isSymbolicLink()) {
      fail(`Row ${rowId} evidence reference contains a symlink: ${reference}`);
    }
  }
  const stat = lstatSync(target);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    fail(`Row ${rowId} evidence reference must name a regular file: ${reference}`);
  }
  const realRoot = realpathSync(projectRoot);
  const realTarget = realpathSync(target);
  const realRelative = relative(realRoot, realTarget);
  if (realRelative === '..' || realRelative.startsWith(`..${sep}`) || isAbsolute(realRelative)) {
    fail(`Row ${rowId} evidence reference resolves outside the candidate: ${reference}`);
  }
  const cacheKey = `${gitExecutable}\0${projectRoot}\0${reference}`;
  let tracked = TRACKED_REFERENCE_CACHE.get(cacheKey);
  if (tracked === undefined) {
    const result = spawnSync(
      gitExecutable,
      [
        '-c',
        `safe.directory=${projectRoot}`,
        '-C',
        projectRoot,
        'ls-files',
        '--error-unmatch',
        '--',
        reference,
      ],
      { encoding: 'utf8' },
    );
    if (result.error !== undefined) {
      fail(`Unable to inspect candidate evidence tracking: ${result.error.message}`);
    }
    tracked = result.status === 0;
    TRACKED_REFERENCE_CACHE.set(cacheKey, tracked);
  }
  if (!tracked) {
    fail(`Row ${rowId} evidence reference is not tracked by the candidate: ${reference}`);
  }
}

function validateReason(row) {
  if (typeof row.reason !== 'string' || row.reason.includes('\r') || row.reason.includes('\n')) {
    fail(`Row ${row.id} reason must be a single-line string.`);
  }
  const requiresReason = row.disposition === 'APPLICATION_OWNED'
    || row.disposition === 'RELEASE_GATED'
    || row.disposition === 'UNRESOLVED';
  if (requiresReason && row.reason.trim().length === 0) {
    fail(`Row ${row.id} disposition ${row.disposition} requires a reason.`);
  }
  if (!requiresReason && row.reason !== '') {
    fail(`Row ${row.id} disposition ${row.disposition} requires an empty reason.`);
  }
  if (row.reason.length > 320) {
    fail(`Row ${row.id} reason exceeds 320 characters.`);
  }
}

function canonicalReleaseGatedReason(releaseGates) {
  return `Remaining immutable or scheduled evidence is owned by: ${releaseGates.join(', ')}.`;
}

function validateRegistry(registry, projectRoot, manifest, gitExecutable) {
  assertExactKeys(registry, TOP_LEVEL_KEYS, 'Matrix-closure registry');
  if (registry.formatVersion !== EXPECTED_FORMAT_VERSION) {
    fail(`Matrix-closure registry formatVersion must be ${EXPECTED_FORMAT_VERSION}.`);
  }
  if (registry.protocolVersion !== EXPECTED_PROTOCOL_VERSION) {
    fail(`Matrix-closure registry protocolVersion must be ${EXPECTED_PROTOCOL_VERSION}.`);
  }
  if (registry.releaseVersion !== manifest.releaseVersion) {
    fail('Matrix-closure registry releaseVersion does not match manifest candidate.version.');
  }
  if (registry.sourceMatrixPath !== EXPECTED_SOURCE_MATRIX_PATH
      || registry.sourceMatrixLastUpdated !== EXPECTED_SOURCE_MATRIX_LAST_UPDATED
      || registry.sourceMatrixSha256 !== EXPECTED_SOURCE_MATRIX_SHA256
      || !SHA256_PATTERN.test(registry.sourceMatrixSha256)) {
    fail('Matrix-closure registry source-matrix provenance does not match the reviewed snapshot.');
  }
  assertExactArray(
    registry.releaseGateUniverse,
    manifest.gateIds,
    'Matrix-closure registry releaseGateUniverse',
  );
  if (!Array.isArray(registry.rows)) {
    fail('Matrix-closure registry rows must be an array.');
  }
  if (registry.rows.length !== EXPECTED_ROW_COUNT) {
    fail(`Matrix-closure registry must contain exactly ${EXPECTED_ROW_COUNT} rows.`);
  }

  const rowIds = [];
  const seenIds = new Set();
  const unresolvedRows = [];
  const dispositionCounts = Object.fromEntries(DISPOSITIONS.map((value) => [value, 0]));
  const releaseGateDependencies = new Set();
  const gateOrdinals = new Map(manifest.gateIds.map((id, index) => [id, index]));

  for (const [index, row] of registry.rows.entries()) {
    assertExactKeys(row, ROW_KEYS, `Matrix-closure row ${index}`);
    if (typeof row.id !== 'string' || !ROW_ID_PATTERN.test(row.id)) {
      fail(`Matrix-closure row ${index} has a malformed ID.`);
    }
    if (seenIds.has(row.id)) {
      fail(`Matrix-closure registry contains duplicate row ID ${row.id}.`);
    }
    seenIds.add(row.id);
    rowIds.push(row.id);

    if (!DISPOSITION_SET.has(row.disposition)) {
      fail(`Row ${row.id} has unknown disposition ${String(row.disposition)}.`);
    }
    if (EXPECTED_NOT_APPLICABLE_IDS.has(row.id) !== (row.disposition === 'NOT_APPLICABLE')) {
      fail(`Row ${row.id} does not match the frozen NOT_APPLICABLE classification.`);
    }
    if (EXPECTED_APPLICATION_OWNED_IDS.has(row.id)
        !== (row.disposition === 'APPLICATION_OWNED')) {
      fail(`Row ${row.id} does not match the frozen APPLICATION_OWNED classification.`);
    }
    dispositionCounts[row.disposition] += 1;
    validateReason(row);

    if (!Array.isArray(row.evidence) || row.evidence.length === 0) {
      fail(`Row ${row.id} must have at least one evidence reference.`);
    }
    if (new Set(row.evidence).size !== row.evidence.length) {
      fail(`Row ${row.id} contains duplicate evidence references.`);
    }
    const sortedEvidence = [...row.evidence].sort(compareAscii);
    if (row.evidence.some((value, evidenceIndex) => value !== sortedEvidence[evidenceIndex])) {
      fail(`Row ${row.id} evidence references must be in ASCII order.`);
    }
    for (const reference of row.evidence) {
      assertContainedEvidence(projectRoot, reference, row.id, gitExecutable);
    }
    if ((row.disposition === 'CORE_COMPLETE' || row.disposition === 'RELEASE_GATED')
        && row.evidence.every((reference) => reference.endsWith('.md')
          || reference === 'release/release-validation-manifest.json')) {
      fail(`Row ${row.id} requires substantive implementation, test, or harness evidence.`);
    }

    if (!Array.isArray(row.releaseGates)) {
      fail(`Row ${row.id} releaseGates must be an array.`);
    }
    if (new Set(row.releaseGates).size !== row.releaseGates.length) {
      fail(`Row ${row.id} contains duplicate release-gate dependencies.`);
    }
    let priorOrdinal = -1;
    for (const gateId of row.releaseGates) {
      if (!gateOrdinals.has(gateId)) {
        fail(`Row ${row.id} depends on unknown release gate ${String(gateId)}.`);
      }
      if (gateId === 'matrix-closure') {
        fail(`Row ${row.id} may not depend on the matrix-closure gate itself.`);
      }
      const ordinal = gateOrdinals.get(gateId);
      if (ordinal <= priorOrdinal) {
        fail(`Row ${row.id} releaseGates must follow manifest order.`);
      }
      priorOrdinal = ordinal;
      releaseGateDependencies.add(gateId);
    }
    if (row.disposition === 'RELEASE_GATED' && row.releaseGates.length === 0) {
      fail(`Row ${row.id} disposition RELEASE_GATED requires a release-gate dependency.`);
    }
    if (row.disposition !== 'RELEASE_GATED' && row.releaseGates.length !== 0) {
      fail(`Row ${row.id} disposition ${row.disposition} may not name release gates.`);
    }
    if (row.disposition === 'UNRESOLVED') {
      unresolvedRows.push({ id: row.id, reason: row.reason });
    }
  }

  const rowIdsSha256 = sha256(`${rowIds.join('\n')}\n`);
  if (rowIdsSha256 !== EXPECTED_ROW_IDS_SHA256) {
    fail('Matrix-closure row IDs are missing, extra, renamed, or out of frozen order.');
  }
  for (const [disposition, expected] of Object.entries(
    EXPECTED_FINAL_DISPOSITION_COUNTS,
  )) {
    if (dispositionCounts[disposition] !== expected) {
      fail(`Matrix-closure final disposition ${disposition} must equal ${expected}.`);
    }
  }
  if (unresolvedRows.length !== 0) {
    fail('Matrix-closure final registry must contain zero unresolved rows.');
  }
  const orderedDependencies = manifest.gateIds.filter((id) => releaseGateDependencies.has(id));
  return {
    dispositionCounts,
    orderedDependencies,
    rowIdsSha256,
    unresolvedRows,
  };
}

export function verifyLimitsAccountingAuthority(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const limitsPath = resolve(
    options.limitsPath ?? resolve(projectRoot, LIMITS_AND_ACCOUNTING_PATH),
  );
  requireContainedPath(
    projectRoot,
    limitsPath,
    'Limits-and-accounting inventory',
    'file',
    'Matrix-closure',
  );
  const { value } = readCanonicalJson(
    limitsPath,
    'Limits-and-accounting inventory',
  );
  assertExactKeys(
    value.numericBoundsAuthority,
    ['path', 'sha256'],
    'Limits-and-accounting numericBoundsAuthority',
  );
  if (value.numericBoundsAuthority.path !== EXPECTED_NUMERIC_BOUNDS_AUTHORITY.path
      || value.numericBoundsAuthority.sha256
        !== EXPECTED_NUMERIC_BOUNDS_AUTHORITY.sha256) {
    fail('Limits-and-accounting numericBoundsAuthority does not match the reviewed external authority.');
  }
  return value.numericBoundsAuthority;
}

function validateResidualPaths(paths, label, rowId, projectRoot,
    gitExecutable, documentation) {
  if (!Array.isArray(paths)) {
    fail(`${label} must be an array.`);
  }
  if (new Set(paths).size !== paths.length) {
    fail(`${label} must not contain duplicates.`);
  }
  const sorted = [...paths].sort(compareAscii);
  for (const [index, path] of paths.entries()) {
    if (path !== sorted[index]) fail(`${label} must be in ASCII order.`);
    if (documentation && !path.endsWith('.md')) {
      fail(`${label}[${index}] must name a Markdown document.`);
    }
    assertContainedEvidence(projectRoot, path, rowId, gitExecutable);
  }
}

function validateResidualClosure(options) {
  const {
    gitExecutable,
    manifest,
    path,
    projectRoot,
    registry,
  } = options;
  const { bytes, value } = readCanonicalJson(
    path,
    'MCP-C residual closure evidence',
  );
  assertExactKeys(
    value,
    RESIDUAL_TOP_LEVEL_KEYS,
    'MCP-C residual closure evidence',
  );
  if (value.formatVersion !== EXPECTED_FORMAT_VERSION
      || value.protocolVersion !== registry.protocolVersion
      || value.releaseVersion !== registry.releaseVersion
      || value.releaseVersion !== manifest.releaseVersion) {
    fail('MCP-C residual closure format, protocol, or release version is invalid.');
  }
  if (!Array.isArray(value.rows)
      || value.rows.length !== RESIDUAL_ROW_CONTRACTS.length) {
    fail(`MCP-C residual closure must contain exactly ${RESIDUAL_ROW_CONTRACTS.length} rows.`);
  }
  const registryRows = new Map(registry.rows.map((row) => [row.id, row]));
  const boundaries = new Set();
  const rationales = new Set();
  for (const [index, contract] of RESIDUAL_ROW_CONTRACTS.entries()) {
    const residualRow = value.rows[index];
    const label = `MCP-C residual row ${index}`;
    assertExactKeys(residualRow, RESIDUAL_ROW_KEYS, label);
    if (residualRow.id !== contract.id) {
      fail('MCP-C residual row IDs must match the frozen order exactly.');
    }
    if (residualRow.targetDisposition !== contract.targetDisposition) {
      fail(`${contract.id} residual targetDisposition must be ${contract.targetDisposition}.`);
    }
    if (residualRow.owningPackage !== 'MCP-C') {
      fail(`${contract.id} residual owningPackage must be MCP-C.`);
    }
    nonblank(residualRow.ownershipBoundary, `${contract.id}.ownershipBoundary`);
    nonblank(residualRow.rationale, `${contract.id}.rationale`);
    if (boundaries.has(residualRow.ownershipBoundary)) {
      fail(`${contract.id} residual ownershipBoundary must be row-specific.`);
    }
    if (rationales.has(residualRow.rationale)) {
      fail(`${contract.id} residual rationale must be row-specific.`);
    }
    boundaries.add(residualRow.ownershipBoundary);
    rationales.add(residualRow.rationale);

    validateResidualPaths(
      residualRow.evidencePaths,
      `${contract.id}.evidencePaths`,
      contract.id,
      projectRoot,
      gitExecutable,
      false,
    );
    validateResidualPaths(
      residualRow.documentationPaths,
      `${contract.id}.documentationPaths`,
      contract.id,
      projectRoot,
      gitExecutable,
      true,
    );
    assertExactArray(
      residualRow.evidencePaths,
      contract.evidencePaths,
      `${contract.id} residual evidencePaths`,
    );
    assertExactArray(
      residualRow.documentationPaths,
      contract.documentationPaths,
      `${contract.id} residual documentationPaths`,
    );
    const combined = [
      ...residualRow.evidencePaths,
      ...residualRow.documentationPaths,
    ];
    if (new Set(combined).size !== combined.length) {
      fail(`${contract.id} residual evidence and documentation paths must be disjoint.`);
    }
    const registryRow = registryRows.get(contract.id);
    if (registryRow === undefined) {
      fail(`Matrix-closure registry omits MCP-C residual row ${contract.id}.`);
    }
    assertExactArray(
      [...combined].sort(compareAscii),
      registryRow.evidence,
      `${contract.id} residual evidence/documentation union`,
    );
    assertExactArray(
      residualRow.releaseGates,
      contract.releaseGates,
      `${contract.id} residual releaseGates`,
    );
    assertExactArray(
      residualRow.releaseGates,
      registryRow.releaseGates,
      `${contract.id} residual/registry releaseGates`,
    );
    if (registryRow.disposition !== residualRow.targetDisposition) {
      fail(`${contract.id} residual targetDisposition does not match the closure registry.`);
    }
    const expectedReason = contract.targetDisposition === 'RELEASE_GATED'
      ? canonicalReleaseGatedReason(contract.releaseGates)
      : '';
    if (registryRow.reason !== expectedReason) {
      fail(`${contract.id} closure-registry reason does not match its target disposition and gates.`);
    }
  }
  const semanticsSha256 = sha256(canonicalJson(value.rows));
  if (semanticsSha256 !== EXPECTED_RESIDUAL_SEMANTICS_SHA256) {
    fail(`MCP-C residual semantic attribution SHA-256 differs from the reviewed contract: expected ${EXPECTED_RESIDUAL_SEMANTICS_SHA256}, found ${semanticsSha256}.`);
  }
  verifyLimitsAccountingAuthority({ projectRoot });
  return { bytes, value };
}

export function verifyMatrixClosure(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const gitExecutable = options.gitExecutable ?? 'git';
  if (typeof gitExecutable !== 'string' || gitExecutable.length === 0) {
    fail('gitExecutable must be a nonempty string.');
  }
  const privacyGitExecutable = options.privacyGitExecutable ?? gitExecutable;
  if (typeof privacyGitExecutable !== 'string'
      || privacyGitExecutable.length === 0) {
    fail('privacyGitExecutable must be a nonempty string.');
  }
  const finiteBoundProjectRoot = resolve(
    options.finiteBoundProjectRoot ?? projectRoot,
  );
  const finiteBoundInventoryPath = resolve(
    options.finiteBoundInventoryPath
      ?? resolve(finiteBoundProjectRoot, FINITE_BOUND_INVENTORY_PATH),
  );
  const finiteBoundExpectedScanRoots = options.finiteBoundExpectedScanRoots
    ?? FINITE_BOUND_SCAN_ROOTS;
  const finiteBoundExpectedCategories = options.finiteBoundExpectedCategories
    ?? FINITE_BOUND_REQUIRED_CATEGORIES;
  const privacyProjectRoot = resolve(
    options.privacyProjectRoot ?? projectRoot,
  );
  const privacyInventoryPath = resolve(
    options.privacyInventoryPath
      ?? resolve(privacyProjectRoot, PRIVACY_BOUNDARY_INVENTORY_PATH),
  );
  const privacyExpectedArtifactRoots = options.privacyExpectedArtifactRoots
    ?? PRIVACY_ARTIFACT_ROOTS;
  const privacyExpectedScanRoots = options.privacyExpectedScanRoots
    ?? PRIVACY_SCAN_ROOTS;
  const registryPath = resolve(
    options.registryPath ?? resolve(projectRoot, 'release/mcp-conformance-matrix-closure.json'),
  );
  const manifestPath = resolve(
    options.manifestPath ?? resolve(projectRoot, 'release/release-validation-manifest.json'),
  );
  const manifest = readManifest(manifestPath);
  verifyFiniteBoundInventory({
    expectedCategories: finiteBoundExpectedCategories,
    expectedExclusionsSha256: options.finiteBoundExpectedExclusionsSha256,
    expectedSemanticsSha256: options.finiteBoundExpectedSemanticsSha256,
    expectedScanRoots: finiteBoundExpectedScanRoots,
    inventoryPath: finiteBoundInventoryPath,
    projectRoot: finiteBoundProjectRoot,
  });
  verifyPrivacyBoundaryInventory({
    expectedArtifactRoots: privacyExpectedArtifactRoots,
    expectedScanRoots: privacyExpectedScanRoots,
    expectedSemanticsSha256: options.privacyExpectedSemanticsSha256,
    gitExecutable: privacyGitExecutable,
    inventoryPath: privacyInventoryPath,
    projectRoot: privacyProjectRoot,
  });
  const { bytes, value: registry } = readCanonicalJson(
    registryPath,
    'Matrix-closure registry',
  );
  const validated = validateRegistry(registry, projectRoot, manifest, gitExecutable);
  const residualEvidencePath = resolve(
    options.residualEvidencePath
      ?? resolve(projectRoot, RESIDUAL_EVIDENCE_PATH),
  );
  const residual = validateResidualClosure({
    gitExecutable,
    manifest,
    path: residualEvidencePath,
    projectRoot,
    registry,
  });
  const rowAttributionsSha256 = matrixRowAttributionsSha256(registry.rows);
  if (rowAttributionsSha256 !== EXPECTED_ROW_ATTRIBUTIONS_SHA256) {
    fail(`Matrix-closure row attribution SHA-256 differs from the reviewed contract: expected ${EXPECTED_ROW_ATTRIBUTIONS_SHA256}, found ${rowAttributionsSha256}.`);
  }
  const status = 'PASSED';
  const report = {
    formatVersion: EXPECTED_FORMAT_VERSION,
    protocolVersion: registry.protocolVersion,
    releaseVersion: registry.releaseVersion,
    sourceMatrixPath: registry.sourceMatrixPath,
    sourceMatrixLastUpdated: registry.sourceMatrixLastUpdated,
    sourceMatrixSha256: registry.sourceMatrixSha256,
    status,
    rowCount: registry.rows.length,
    rowIdsSha256: validated.rowIdsSha256,
    registrySha256: sha256(bytes),
    residualSha256: sha256(residual.bytes),
    dispositionCounts: validated.dispositionCounts,
    releaseGateDependencies: validated.orderedDependencies,
    unresolvedRows: validated.unresolvedRows,
    rows: registry.rows,
  };
  return {
    exitCode: 0,
    report,
    reportText: canonicalJson(report),
  };
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 2) {
    console.error('Usage: node scripts/verify-release-matrix-closure.mjs');
    process.exitCode = 2;
  } else {
    try {
      const result = verifyMatrixClosure();
      process.stdout.write(result.reportText);
      if (result.exitCode !== 0) {
        console.error(
          `Matrix closure failed: ${result.report.unresolvedRows.length} unresolved row(s).`,
        );
      }
      process.exitCode = result.exitCode;
    } catch (error) {
      console.error(`Matrix closure verification failed: ${error.message}`);
      process.exitCode = 2;
    }
  }
}
