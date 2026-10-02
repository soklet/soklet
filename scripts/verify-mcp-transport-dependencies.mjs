#!/usr/bin/env node

import {
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

export const BASELINE_PATH = 'conformance/mcp-transport-dependency-baseline.json';
export const MICROHTTP_PACKAGE_PREFIX = 'com.soklet.internal.microhttp.';

const INTERNAL_SCAN_DIRECTORY = 'src/main/java/com/soklet/internal/mcp';
const PUBLIC_SCAN_DIRECTORY = 'src/main/java/com/soklet';
const PUBLIC_SCAN_FILE = /^Mcp[^/]*\.java$/u;
const ADDITIONAL_COMPOSITION_OWNER_FILES = Object.freeze([
  'src/main/java/com/soklet/DefaultMcpServer.java',
  'src/main/java/com/soklet/Soklet.java',
]);

export const PRODUCTION_SCOPE = Object.freeze({
  additionalCompositionOwnerFiles: ADDITIONAL_COMPOSITION_OWNER_FILES,
  directPublicFilePattern: 'src/main/java/com/soklet/Mcp*.java',
  rationale: 'MCP V11 section 15.2 roots plus the two exact public composition owners that can acquire direct transport dependencies.',
  recursiveInternalDirectory: INTERNAL_SCAN_DIRECTORY,
});

export const NETWORK_EVENT_LOOP_EXACT_TYPES = Object.freeze([
  'java.net.DatagramSocket',
  'java.net.InetSocketAddress',
  'java.net.MulticastSocket',
  'java.net.ServerSocket',
  'java.net.Socket',
  'java.net.SocketAddress',
  'java.net.UnixDomainSocketAddress',
  'java.nio.channels.AsynchronousServerSocketChannel',
  'java.nio.channels.AsynchronousSocketChannel',
  'java.nio.channels.DatagramChannel',
  'java.nio.channels.MulticastChannel',
  'java.nio.channels.NetworkChannel',
  'java.nio.channels.SelectableChannel',
  'java.nio.channels.SelectionKey',
  'java.nio.channels.Selector',
  'java.nio.channels.ServerSocketChannel',
  'java.nio.channels.SocketChannel',
  'java.nio.channels.spi.AbstractSelectableChannel',
  'java.nio.channels.spi.AbstractSelector',
  'java.nio.channels.spi.SelectorProvider',
]);

export const NETWORK_EVENT_LOOP_PACKAGE_FAMILIES = Object.freeze([
  'io.netty.channel.',
  'io.netty.incubator.channel.uring.',
  'org.apache.mina.core.polling.',
  'org.eclipse.jetty.io.',
  'org.glassfish.grizzly.nio.',
  'org.xnio.',
  'reactor.netty.',
]);

export const NETWORK_EVENT_LOOP_REVIEWED_SIMPLE_NAMES = Object.freeze([
  'EventLoop',
  'EventLoopGroup',
]);

const STATE_DOMAIN_TERMS = Object.freeze(['replay', 'session', 'stdio']);
const STATE_STORAGE_ROLE_TERMS = Object.freeze([
  'buffer', 'cache', 'journal', 'log', 'queue', 'registry', 'repository',
  'state', 'storage', 'store',
]);
const REVIEWED_EXISTING_DOMAIN_FIELDS = Object.freeze([
  {
    field: 'mcpSimulationSession',
    file: 'src/main/java/com/soklet/Soklet.java',
    owner: 'com.soklet.Soklet.DefaultSimulator',
    rationale: 'Existing single-process simulation lifecycle handle; it is not an MCP wire session or future transport state.',
  },
  {
    field: 'rejectedMcpSimulationSession',
    file: 'src/main/java/com/soklet/Soklet.java',
    owner: 'com.soklet.Soklet.DefaultSimulator',
    rationale: 'Existing rejected simulation lifecycle proof handle; it is not an MCP wire session or future transport state.',
  },
]);
const REVIEWED_EXISTING_DOMAIN_TYPES = Object.freeze([
  {
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java',
    owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.SimulationSession',
    rationale: 'Existing in-memory simulator lifecycle object; it is not an MCP wire session or future transport state.',
  },
  {
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java',
    owner: 'com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.SimulationSession',
    rationale: 'Existing public-simulation bridge lifecycle object; it is not an MCP wire session or future transport state.',
  },
]);

const REVIEWED_2025_SESSION_DOMAIN_FIELDS = Object.freeze([
  {
    "field": "MAXIMUM_URI_GRANTS_PER_SESSION",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Exact 64 logical URI grants per enabled 2025 session; no historical payload or replay."
  },
  {
    "field": "MAXIMUM_RETAINED_URI_BYTES_PER_SESSION",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Exact 65536 retained URI bytes per enabled 2025 session through residual callback exit."
  },
  {
    "field": "session",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.Delivery",
    "rationale": "Bounded one-session dirty-state delivery view; no historical event payload."
  },
  {
    "field": "session",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.EvidenceReservation",
    "rationale": "Shared bounded subscribe evidence stays charged until its final logical/physical reference exits."
  },
  {
    "field": "session",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.GrantEntry",
    "rationale": "Bounded node-local URI grant tied to the exact 2025 session and authorization generation."
  },
  {
    field: 'MAXIMUM_LOGICAL_GETS_PER_SESSION',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java',
    owner: 'com.soklet.internal.mcp.protocol.McpLegacySessionStore',
    rationale: 'Exact two-logical-GET cap for explicitly enabled 2025 sessions; no replay or modern session state.',
  },
  {
    field: 'session',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java',
    owner: 'com.soklet.internal.mcp.protocol.McpLegacySessionStore.Get',
    rationale: 'Bounded GET handle retains its exact node-local session through physical completion; no event history.',
  },
  {
    "field": "maximumSessions",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved provisional minimum 2025 session configuration; no modern session state or replay."
  },
  {
    "field": "maximumSessionsPerOwner",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved provisional minimum 2025 session configuration; no modern session state or replay."
  },
  {
    "field": "maximumSessionIdleDuration",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved provisional minimum 2025 session configuration; no modern session state or replay."
  },
  {
    "field": "maximumSessionDuration",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved provisional minimum 2025 session configuration; no modern session state or replay."
  },
  {
    "field": "anonymousSessionsAllowed",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved provisional minimum 2025 session configuration; no modern session state or replay."
  },
  {
    "field": "DEFAULT_MAXIMUM_SESSIONS",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "DEFAULT_MAXIMUM_SESSIONS_PER_OWNER",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "DEFAULT_MAXIMUM_SESSION_IDLE_DURATION",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "DEFAULT_MAXIMUM_SESSION_DURATION",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "maximumSessions",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "maximumSessionsPerOwner",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "maximumSessionIdleDuration",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "maximumSessionDuration",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "anonymousSessionsAllowed",
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig.Builder",
    "rationale": "Reviewed defaults and copied tuning values for explicitly enabled 2025 sessions only."
  },
  {
    "field": "sessionConfig",
    "file": "src/main/java/com/soklet/McpServer.java",
    "owner": "com.soklet.McpServer.Builder",
    "rationale": "Optional server ownership/bounds configuration requiring a corresponding explicitly enabled 2025 endpoint."
  },
  {
    "field": "sessionConfig",
    "file": "src/main/java/com/soklet/DefaultMcpServer.java",
    "owner": "com.soklet.DefaultMcpServer",
    "rationale": "Immutable server session configuration forwarded to the exact legacy runtime; modern remains stateless."
  },
  {
    "field": "sessionProtocolVersions",
    "file": "src/main/java/com/soklet/McpEndpoint.java",
    "owner": "com.soklet.McpEndpoint",
    "rationale": "Explicit endpoint subset restricted to served June/November 2025 revisions; empty disables sessions."
  },
  {
    "field": "sessionProtocolVersions",
    "file": "src/main/java/com/soklet/McpEndpoint.java",
    "owner": "com.soklet.McpEndpoint.Builder",
    "rationale": "Explicit endpoint subset restricted to served June/November 2025 revisions; empty disables sessions."
  },
  {
    "field": "MAXIMUM_ACTIVE_CALLS_PER_SESSION",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local 2025 session records/correlation; no event journal or lifetime ID history."
  },
  {
    "field": "MAXIMUM_ANONYMOUS_SESSIONS",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local 2025 session records/correlation; no event journal or lifetime ID history."
  },
  {
    "field": "MAXIMUM_SESSION_ID_HEADER_BYTES",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local 2025 session records/correlation; no event journal or lifetime ID history."
  },
  {
    "field": "sessions",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local 2025 session records/correlation; no event journal or lifetime ID history."
  },
  {
    "field": "anonymousSessions",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local 2025 session records/correlation; no event journal or lifetime ID history."
  },
  {
    "field": "session",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.Initialization",
    "rationale": "Bounded initialization delivery and physical-exit handle for the selected 2025 record."
  },
  {
    "field": "session",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.Call",
    "rationale": "Bounded admitted-use handle retaining evidence until actual physical exit."
  },
  {
    "field": "liveSessions",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.OwnerUsage",
    "rationale": "Per-owner logical session accounting, separate from retained physical evidence."
  },
  {
    "field": "MCP_SESSION_ID",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacySessionRevisions",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacySessionStore",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacySessionOwnerResolver",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacySessionsConfigured",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacyAnonymousSessionsAllowed",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime",
    "rationale": "Exact 2025 opt-in wiring; framework-only initialization ID publication and fresh owner/revision binding."
  },
  {
    "field": "legacySessionSelected",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl",
    "rationale": "Per-request selected-session and exactly-once termination ownership; no resumable delivery state."
  },
  {
    "field": "legacySessionTerminationCause",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl",
    "rationale": "Per-request selected-session and exactly-once termination ownership; no resumable delivery state."
  },
  {
    "field": "legacySessionStreamTerminationObserved",
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java",
    "owner": "com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl",
    "rationale": "Per-request selected-session and exactly-once termination ownership; no resumable delivery state."
  },
  {
    field: 'legacySessionTransportCompletionDeadlineNanos',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java',
    owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl',
    rationale: 'Existing response-write timeout bounds physical terminal delivery after session cancellation; no deadline extension or replay.',
  },
  {
    field: 'sessionCancellationCallbacksFinished',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange',
    rationale: 'Exact bounded cancellation sidecar completion fence; the handler retains its slot until public callbacks physically exit.',
  },
  {
    field: 'sessionCancellationEffectsReady',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange',
    rationale: 'Exact sidecar publication fence ensuring cancellation callbacks wait until HTTP effects are offered.',
  },
  {
    field: 'sessionCancellationCallbacksOutstanding',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange',
    rationale: 'Physical callback ownership flag bounded by the existing handler concurrency; not a session registry.',
  },
  {
    field: 'MAXIMUM_SESSION_OWNER_POLICY_CONCURRENCY',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution',
    rationale: 'Exact four-worker ceiling for fresh legacy owner resolution; additionally bounded by configured handler concurrency.',
  },
  {
    field: 'sessionOwnerPolicyExecutor',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution',
    rationale: 'Dedicated bounded owner-resolution execution, included in physical shutdown accounting without public handler metrics.',
  },
  {
    field: 'sessionOwnerPolicyDispatcher',
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java',
    owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution',
    rationale: 'Configured bounded owner-resolution queue/worker dispatcher; cancellation has no application-hook sidecar.',
  },
]);
const REVIEWED_2025_SESSION_DOMAIN_TYPES = Object.freeze([
  {
    file: 'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java',
    owner: 'com.soklet.internal.mcp.protocol.McpServerRuntimeBridge.DefaultSessionTransportAdmissionContext',
    rationale: 'Immutable exact-request context for explicit 2025 GET/DELETE admission and renewal; it carries no JSON-RPC context or replay position.',
  },
  {
    "file": "src/main/java/com/soklet/McpSessionConfig.java",
    "owner": "com.soklet.McpSessionConfig",
    "rationale": "Approved immutable provisional minimum session configuration, not a wire history or modern lifecycle."
  },
  {
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore",
    "rationale": "Approved bounded node-local store for explicitly enabled June/November 2025 sessions only."
  },
  {
    "file": "src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java",
    "owner": "com.soklet.internal.mcp.protocol.McpLegacySessionStore.Session",
    "rationale": "Bounded owner/path/revision/generation record, retained metadata and active correlation; no replay or lifetime ID history."
  }
]);

const DERIVATION = Object.freeze({
  dependencyLexing: {
    fullyQualifiedUses: 'comment/string/text-block-free fully-qualified type uses are included',
    importParsing: 'legal Java whitespace and comments are normalized; reviewed-family static or wildcard imports fail closed',
    unicodeEscapes: 'eligible Java Unicode escapes are translated before lexical analysis',
  },
  microhttpPackagePrefix: MICROHTTP_PACKAGE_PREFIX,
  networkEventLoopTaxonomy: {
    exactTypes: NETWORK_EVENT_LOOP_EXACT_TYPES,
    excludedExamples: [
      'java.net.InetAddress (host-validation value)',
      'java.net.URI and java.net.URL (protocol/resource values)',
      'java.nio.channels.FileChannel (non-network I/O)',
    ],
    packageFamilies: NETWORK_EVENT_LOOP_PACKAGE_FAMILIES,
    reviewedSimpleNames: NETWORK_EVENT_LOOP_REVIEWED_SIMPLE_NAMES,
    scope: 'Reviewed concrete JDK socket, bound-address, selector, and network-channel primitives plus named third-party network/event-loop ownership families and EventLoop/EventLoopGroup simple-name fallbacks; this is an explicit taxonomy, not a claim to recognize every possible library.',
  },
  productionScope: PRODUCTION_SCOPE,
  stateStorageDeclarationPolicy: {
    crossDomainRule: 'An identifier combining two or more future-transport domain terms is state-bearing even without a separate storage-role term.',
    dataBearingClassRule: 'A class whose name contains a future-transport domain term and that declares direct fields is state-bearing unless it is one exact reviewed preexisting simulation-lifecycle type or approved bounded 2025 session type.',
    dataBearingRecordRule: 'A nonempty record whose name contains a future-transport domain term is state-bearing.',
    directFieldRule: 'A direct field name containing any future-transport domain term is state-bearing unless it is one exact reviewed preexisting non-roadmap field or approved 2025 session field.',
    domainTerms: STATE_DOMAIN_TERMS,
    reviewed2025SessionDomainFields: REVIEWED_2025_SESSION_DOMAIN_FIELDS,
    reviewed2025SessionDomainTypes: REVIEWED_2025_SESSION_DOMAIN_TYPES,
    reviewedExistingDomainFields: REVIEWED_EXISTING_DOMAIN_FIELDS,
    reviewedExistingDomainTypes: REVIEWED_EXISTING_DOMAIN_TYPES,
    roleTerms: STATE_STORAGE_ROLE_TERMS,
    scope: 'Named type declarations in the production scope fail when one identifier combines a future-transport domain term with a state/storage role term or combines multiple domain terms; every unreviewed direct field name containing a future-transport domain term, nonempty domain-named record, and field-bearing domain-named class also fail, while exact reviewed preexisting declarations, approved bounded 2025 session declarations, and fieldless single-domain capability/control declarations remain allowed. These exact session allowances grant neither modern lifecycle nor replay/stdio state.',
  },
});

const CHARACTERIZATIONS = Object.freeze([
  {
    evidence: [
      { owner: 'com.soklet.internal.mcp.protocol.McpServerRuntimeBridge#progressEmitterFor', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl#writeApplicationNotification', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl#offerSubscriptionEvent', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#processRequestSafely::<anonymous McpApplicationResponseWriter>#writeNotification', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange#writeNotification', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange#runHandler::<anonymous McpApplicationNotificationWriter>#isActive', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpApplicationInvocation#sendNotification', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream#enqueueMessage', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream#encodeMessage', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream#frame', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream.Frame#Frame', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpLegacyResponseWire#projectEnvelope', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacyResponseWire.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpSimulationRuntime#captureFrameWhileLocked', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpSimulationRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpSimulationRuntime#complete', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpSimulationRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream#offerCoalescingMessage', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel#delegate', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java' },
    ],
    id: 'MCP-TRANSPORT-001',
    statement: 'Progress and subscription notifications use the request-scoped SSE stream and its single bounded McpOutboundChannel delegate. Selected legacy POST streams share the finite legacy terminal projection with their immutable simulation JSON snapshots; the default modern encoder is preserved and message frames contain no event IDs or priming fields. Guarded legacy GET invalidations recheck source/GET/grant authority before every socket write; revoked unwritten chunks are purged, partially written chunks fail, and encoded-byte releases run outside the channel lock.',
  },
  {
    evidence: [
      { owner: 'com.soklet.internal.microhttp.WritableSource#writeTo', path: 'src/main/java/com/soklet/internal/microhttp/WritableSource.java' },
      { owner: 'com.soklet.internal.mcp.transport.McpOutboundChannel.WritableSourceFacade#writeTo', path: 'src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java' },
    ],
    id: 'MCP-TRANSPORT-002',
    statement: 'The streaming write floor remains WritableSource.writeTo(SocketChannel, long).',
  },
  {
    evidence: [
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#startWhileMetricsDeferred::<anonymous Handler>#monitorClientDisconnectsDuringStreamingResponse', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.microhttp.Handler#streamingResponseInputPolicy', path: 'src/main/java/com/soklet/internal/microhttp/Handler.java' },
      { owner: 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection#doOnReadable', path: 'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java' },
      { owner: 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection#prepareToWriteResponse', path: 'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java' },
      { owner: 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection#doOnReadableDuringStreamingResponse', path: 'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java' },
      { owner: 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection#doOnWritable', path: 'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java' },
    ],
    id: 'MCP-TRANSPORT-003',
    statement: 'The live MCP handler selects DISCARD for committed-stream input; subsequent input is bounded, discarded, and closes the connection rather than being parsed as another request.',
  },
  {
    evidence: [
      { owner: 'com.soklet.McpServer#withPort', path: 'src/main/java/com/soklet/McpServer.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#startWhileMetricsDeferred', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#processRequest', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#processRequestSafely', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
    ],
    id: 'MCP-TRANSPORT-004',
    statement: 'McpServer.withPort reaches a dedicated Microhttp EventLoop and its request processor rejects non-HTTP/1.1 requests.',
  },
  {
    evidence: [
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#FORBIDDEN_LEGACY_MCP_POLICY_HEADERS', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#validatedPolicyHeaders', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#configureLegacySessions', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#sessionsEnabled', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#legacyHttpMethods', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#processLegacySessionHttp', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime#mcpPreflightRequestHeaders', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl#withInitializationResponse', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java' },
      { owner: 'com.soklet.internal.mcp.protocol.McpLegacySessionStore#requireLegacy', path: 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java' },
      { owner: 'MCP production-scope named type and direct-field declarations', path: 'src/main/java/com/soklet/internal/mcp' },
    ],
    id: 'MCP-TRANSPORT-005',
    statement: 'Production declarations allow only exact reviewed preexisting handles and approved bounded 2025 session/leased GET/URI grant state under the domain/role, cross-domain, and data-bearing-declaration rules. GET/DELETE require their selected legacy session facilities; Last-Event-ID is ignored input allowed in preflight only where GET is configured. Modern sessions, replay, and stdio state remain excluded, and application-authored session/replay headers remain forbidden.',
  },
]);

const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true });
const JAVA_TYPE = /^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+$/u;

export class McpTransportDependencyVerificationError extends Error {}

function fail(message) { throw new McpTransportDependencyVerificationError(message); }
function asciiCompare(left, right) { return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8')); }
function deepClone(value) { return JSON.parse(JSON.stringify(value)); }
function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort(asciiCompare)
      .map((key) => [key, canonicalize(value[key])]));
  }
  return value;
}
export function canonicalJson(value) { return `${JSON.stringify(canonicalize(value), null, 2)}\n`; }

function exactFields(value, fields, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail(`${label} must be an object.`);
  const actual = Object.keys(value).sort(asciiCompare);
  const expected = [...fields].sort(asciiCompare);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) fail(`${label} fields must be exactly ${expected.join(', ')}; found ${actual.join(', ')}.`);
}
function status(path, label) {
  try { return lstatSync(path); } catch (error) { fail(`Missing ${label}: ${path} (${error.message})`); }
}
function requireDirectory(path, label) {
  const entry = status(path, label);
  if (!entry.isDirectory() || entry.isSymbolicLink()) fail(`${label} must be a non-symlink directory: ${path}`);
}
function requireCandidateRoot(root) {
  const entry = status(root, 'candidate root');
  if (!entry.isDirectory() || entry.isSymbolicLink()) {
    fail(`Candidate root must be a non-symlink directory: ${root}`);
  }
}
function safeProjectEntry(root, relativePath, label) {
  const components = relativePath.split('/');
  if (relativePath === '' || components.some((component) =>
    component === '' || component === '.' || component === '..')) {
    fail(`${label} has an invalid project-relative path: ${relativePath}`);
  }
  let current = root;
  for (const [index, component] of components.entries()) {
    current = join(current, component);
    const entry = status(current, `${label} path component`);
    if (entry.isSymbolicLink()) {
      fail(`${label} path must not traverse a symbolic link: ${relativePath}`);
    }
    if (index < components.length - 1 && !entry.isDirectory()) {
      fail(`${label} ancestor must be a directory: ${relativePath}`);
    }
  }
  return current;
}
function readUtf8Regular(path, label) {
  const entry = status(path, label);
  if (!entry.isFile() || entry.isSymbolicLink()) fail(`${label} must be a regular non-symlink file: ${path}`);
  try { return UTF8_DECODER.decode(readFileSync(path)); } catch (error) { fail(`${label} is not valid UTF-8: ${path} (${error.message})`); }
}
function projectPath(root, absolute) { return relative(root, absolute).split(sep).join('/'); }

function discoverJavaFiles(root, relativeDirectory) {
  const directory = safeProjectEntry(root, relativeDirectory,
    `scan directory ${relativeDirectory}`);
  requireDirectory(directory, `scan directory ${relativeDirectory}`);
  const files = [];
  function visit(current) {
    const entries = readdirSync(current, { withFileTypes: true }).sort((a, b) => asciiCompare(a.name, b.name));
    for (const entry of entries) {
      const path = join(current, entry.name);
      if (entry.isSymbolicLink()) fail(`Scan roots must not contain symbolic links: ${projectPath(root, path)}`);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.java')) files.push(projectPath(root, path));
    }
  }
  visit(directory);
  return files;
}
function requireDirectRegular(root, file, label) {
  const entry = status(safeProjectEntry(root, file, label), label);
  if (!entry.isFile() || entry.isSymbolicLink()) fail(`${label} must be a regular non-symlink file: ${file}`);
}
function discoverScanFiles(root) {
  const files = discoverJavaFiles(root, INTERNAL_SCAN_DIRECTORY);
  const publicDirectory = safeProjectEntry(root, PUBLIC_SCAN_DIRECTORY,
    `scan directory ${PUBLIC_SCAN_DIRECTORY}`);
  requireDirectory(publicDirectory, `scan directory ${PUBLIC_SCAN_DIRECTORY}`);
  for (const entry of readdirSync(publicDirectory, { withFileTypes: true }).sort((a, b) => asciiCompare(a.name, b.name))) {
    if (!PUBLIC_SCAN_FILE.test(entry.name)) continue;
    const file = `${PUBLIC_SCAN_DIRECTORY}/${entry.name}`;
    requireDirectRegular(root, file, 'direct public MCP scan entry');
    files.push(file);
  }
  for (const file of ADDITIONAL_COMPOSITION_OWNER_FILES) {
    requireDirectRegular(root, file, 'additional MCP composition-owner source');
    files.push(file);
  }
  return [...new Set(files)].sort(asciiCompare);
}

function blank(output, source, index) {
  if (source[index] !== '\n' && source[index] !== '\r') output[index] = ' ';
}
function translateUnicodeEscapes(source, file) {
  let translated = '';
  let contiguousBackslashes = 0;
  for (let index = 0; index < source.length;) {
    const character = source[index];
    if (character !== '\\') {
      translated += character;
      contiguousBackslashes = 0;
      index++;
      continue;
    }
    const eligible = contiguousBackslashes % 2 === 0;
    let cursor = index + 1;
    if (eligible && source[cursor] === 'u') {
      while (source[cursor] === 'u') cursor++;
      const hexadecimal = source.slice(cursor, cursor + 4);
      if (/^[0-9A-Fa-f]{4}$/u.test(hexadecimal)) {
        const decoded = String.fromCharCode(Number.parseInt(hexadecimal, 16));
        translated += decoded;
        contiguousBackslashes = decoded === '\\' ? contiguousBackslashes + 1 : 0;
        index = cursor + 4;
        continue;
      }
      fail(`Malformed eligible Java Unicode escape in reviewed transport source: ${file}`);
    }
    translated += character;
    contiguousBackslashes++;
    index++;
  }
  return translated;
}

export function lexJava(source, file = '<source>') {
  source = translateUnicodeEscapes(source, file);
  const code = source.split('');
  const commentFree = source.split('');
  let index = 0;
  let state = 'normal';
  while (index < source.length) {
    const character = source[index];
    const next = source[index + 1];
    const third = source[index + 2];
    if (state === 'normal') {
      if (character === '/' && next === '/') {
        blank(code, source, index); blank(code, source, index + 1);
        blank(commentFree, source, index); blank(commentFree, source, index + 1);
        state = 'line-comment'; index += 2; continue;
      }
      if (character === '/' && next === '*') {
        blank(code, source, index); blank(code, source, index + 1);
        blank(commentFree, source, index); blank(commentFree, source, index + 1);
        state = 'block-comment'; index += 2; continue;
      }
      if (character === '"' && next === '"' && third === '"') {
        blank(code, source, index); blank(code, source, index + 1); blank(code, source, index + 2);
        state = 'text-block'; index += 3; continue;
      }
      if (character === '"' || character === '\'') {
        blank(code, source, index); state = character === '"' ? 'string' : 'character'; index++; continue;
      }
      index++; continue;
    }
    blank(code, source, index);
    if (state === 'line-comment') {
      blank(commentFree, source, index);
      if (character === '\n' || character === '\r') state = 'normal';
      index++; continue;
    }
    if (state === 'block-comment') {
      blank(commentFree, source, index);
      if (character === '*' && next === '/') {
        blank(code, source, index + 1); blank(commentFree, source, index + 1);
        state = 'normal'; index += 2;
      } else index++;
      continue;
    }
    if (state === 'text-block') {
      if (character === '\\') {
        if (next !== undefined) blank(code, source, index + 1);
        index += 2; continue;
      }
      if (character === '"' && next === '"' && third === '"') {
        blank(code, source, index + 1); blank(code, source, index + 2);
        state = 'normal'; index += 3;
      } else index++;
      continue;
    }
    if (character === '\n' || character === '\r') fail(`Unterminated Java ${state} literal in ${file}`);
    if (character === '\\') {
      if (next !== undefined) blank(code, source, index + 1);
      index += 2; continue;
    }
    if ((state === 'string' && character === '"') || (state === 'character' && character === '\'')) state = 'normal';
    index++;
  }
  if (state !== 'normal' && state !== 'line-comment') fail(`Unterminated Java ${state} construct in ${file}`);
  return { code: code.join(''), commentFree: commentFree.join('') };
}

function escapeRegex(value) { return value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'); }
function networkEventLoopType(type) {
  return NETWORK_EVENT_LOOP_EXACT_TYPES.includes(type)
    || NETWORK_EVENT_LOOP_PACKAGE_FAMILIES.some((prefix) => type.startsWith(prefix))
    || NETWORK_EVENT_LOOP_REVIEWED_SIMPLE_NAMES.includes(
      type.slice(type.lastIndexOf('.') + 1));
}
function reviewedImportFamily(type) {
  if (type.startsWith(MICROHTTP_PACKAGE_PREFIX) || networkEventLoopType(type)) return true;
  const prefix = type.endsWith('.*') ? type.slice(0, -1) : type;
  if (MICROHTTP_PACKAGE_PREFIX.startsWith(prefix) || prefix.startsWith(MICROHTTP_PACKAGE_PREFIX)) return true;
  if (NETWORK_EVENT_LOOP_PACKAGE_FAMILIES.some((family) => family.startsWith(prefix) || prefix.startsWith(family))) return true;
  return NETWORK_EVENT_LOOP_EXACT_TYPES.some((exact) =>
    exact.startsWith(prefix) || type.startsWith(`${exact}.`));
}
function parsedImports(lexed, file) {
  const matches = [...lexed.code.matchAll(/\bimport\b/gu)];
  const imports = [];
  const seen = new Set();
  for (const match of matches) {
    const semicolon = lexed.code.indexOf(';', match.index);
    if (semicolon < 0) fail(`Malformed Java import in reviewed transport source: ${file}`);
    const declaration = lexed.code.slice(match.index, semicolon + 1);
    const parsed = /^import\s+(static\s+)?([A-Za-z_$][\w$]*(?:\s*\.\s*(?:[A-Za-z_$][\w$]*|\*))*)\s*;$/su
      .exec(declaration);
    if (parsed === null) fail(`Malformed or ambiguous Java import in reviewed transport source: ${file}: ${declaration.trim()}`);
    const isStatic = parsed[1] !== undefined;
    const type = parsed[2].replaceAll(/\s+/gu, '');
    if (reviewedImportFamily(type) && (isStatic || type.endsWith('.*'))) fail(`Reviewed transport dependencies require explicit non-static imports: ${file}: ${declaration.trim()}`);
    const key = `${isStatic ? 'static ' : ''}${type}`;
    if (seen.has(key)) fail(`Duplicate Java import is forbidden by the canonical dependency scan: ${file}: ${key}`);
    seen.add(key);
    if (!isStatic) imports.push(type);
  }
  return imports;
}
function microhttpFullyQualifiedTypes(code) {
  const packagePattern = MICROHTTP_PACKAGE_PREFIX.slice(0, -1).split('.')
    .map(escapeRegex).join('\\s*\\.\\s*');
  const pattern = new RegExp(`\\b${packagePattern}\\s*\\.\\s*[A-Za-z_$][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Z][A-Za-z0-9_$]*)*`, 'gu');
  return [...code.matchAll(pattern)]
    .map((match) => match[0].replaceAll(/\s+/gu, ''));
}
function networkFullyQualifiedTypes(code) {
  const types = [];
  const exactPattern = new RegExp(`\\b(?:${NETWORK_EVENT_LOOP_EXACT_TYPES
    .map((type) => type.split('.').map(escapeRegex).join('\\s*\\.\\s*'))
    .join('|')})(?![A-Za-z0-9_$])`, 'gu');
  types.push(...[...code.matchAll(exactPattern)]
    .map((match) => match[0].replaceAll(/\s+/gu, '')));
  for (const family of NETWORK_EVENT_LOOP_PACKAGE_FAMILIES) {
    const familyPattern = family.slice(0, -1).split('.')
      .map(escapeRegex).join('\\s*\\.\\s*');
    const pattern = new RegExp(`\\b${familyPattern}\\s*\\.\\s*(?:[a-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)*[A-Z][A-Za-z0-9_$]*(?:\\s*\\.\\s*[A-Z][A-Za-z0-9_$]*)*`, 'gu');
    const conventionalTypes = [...code.matchAll(pattern)]
      .map((match) => match[0].replaceAll(/\s+/gu, ''));
    types.push(...conventionalTypes);
    const lowercaseFallback = new RegExp(
      `\\b${familyPattern}\\s*\\.\\s*[a-z_$][A-Za-z0-9_$]*`, 'gu');
    for (const match of code.matchAll(lowercaseFallback)) {
      const candidate = match[0].replaceAll(/\s+/gu, '');
      if (!conventionalTypes.some((type) => type.startsWith(`${candidate}.`)))
        types.push(candidate);
    }
  }
  const reviewedSimpleNamePattern = new RegExp(
    `\\b(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\.\\s*)+(?:${NETWORK_EVENT_LOOP_REVIEWED_SIMPLE_NAMES
      .map(escapeRegex).join('|')})\\b`, 'gu');
  types.push(...[...code.matchAll(reviewedSimpleNamePattern)]
    .map((match) => match[0].replaceAll(/\s+/gu, '')));
  return types;
}
function dependencyTypes(sourceFile) {
  const imports = parsedImports(sourceFile.lexed, sourceFile.file);
  return {
    microhttp: new Set([...imports.filter((type) => type.startsWith(MICROHTTP_PACKAGE_PREFIX)), ...microhttpFullyQualifiedTypes(sourceFile.lexed.code)]),
    network: new Set([...imports.filter(networkEventLoopType), ...networkFullyQualifiedTypes(sourceFile.lexed.code)]),
  };
}
function dependencyFingerprint(row) { return `${row.file}\u0000${row.type}`; }
function dependencyRows(sourceFiles, family) {
  const rows = [];
  for (const sourceFile of sourceFiles) {
    for (const type of dependencyTypes(sourceFile)[family]) rows.push({ file: sourceFile.file, type });
  }
  return rows.sort((a, b) => asciiCompare(dependencyFingerprint(a), dependencyFingerprint(b)));
}
export function summaryForDependencies(microhttp, network) {
  const summarize = (rows) => ({
    fileCount: new Set(rows.map(({ file }) => file)).size,
    pairCount: rows.length,
    typeCount: new Set(rows.map(({ type }) => type)).size,
  });
  return { directMicrohttp: summarize(microhttp), directSocketEventLoop: summarize(network) };
}

function matchingDelimiter(code, start, open, close, label) {
  if (code[start] !== open) fail(`Expected ${open} while parsing ${label}.`);
  let depth = 0;
  for (let index = start; index < code.length; index++) {
    if (code[index] === open) depth++;
    else if (code[index] === close && --depth === 0) return index;
  }
  fail(`Unbalanced ${open}${close} while parsing ${label}.`);
}
function namedTypes(lexed, file) {
  const packageMatch = /^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;/mu.exec(lexed.code);
  if (packageMatch === null) fail(`Java source has no canonical package declaration: ${file}`);
  const rows = [];
  const declaration = /\b(class|interface|record|enum)\s+([A-Za-z_$][\w$]*)\b/gu;
  for (const match of lexed.code.matchAll(declaration)) {
    const bodyStart = lexed.code.indexOf('{', match.index + match[0].length);
    const semicolon = lexed.code.indexOf(';', match.index + match[0].length);
    if (bodyStart < 0 || (semicolon >= 0 && semicolon < bodyStart)) continue;
    const bodyEnd = matchingDelimiter(lexed.code, bodyStart, '{', '}', `${file}:${match[2]}`);
    let recordComponentCode = null;
    if (match[1] === 'record') {
      const componentStart = lexed.code.indexOf('(', match.index + match[0].length);
      if (componentStart < 0 || componentStart > bodyStart) {
        fail(`Record declaration has no canonical component list: ${file}:${match[2]}`);
      }
      const componentEnd = matchingDelimiter(lexed.code, componentStart, '(', ')',
        `${file}:${match[2]} record components`);
      if (componentEnd > bodyStart) {
        fail(`Record component list crosses its body: ${file}:${match[2]}`);
      }
      recordComponentCode = lexed.code.slice(componentStart + 1, componentEnd);
    }
    rows.push({ bodyEnd, bodyStart, declarationStart: match.index, kind: match[1], name: match[2], owner: null, parent: null, recordComponentCode });
  }
  for (const row of rows) {
    row.parent = rows.filter((candidate) => candidate !== row
      && candidate.bodyStart < row.declarationStart && candidate.bodyEnd > row.bodyEnd)
      .sort((a, b) => (a.bodyEnd - a.bodyStart) - (b.bodyEnd - b.bodyStart))[0] ?? null;
  }
  function owner(row) {
    if (row.owner === null) row.owner = row.parent === null ? `${packageMatch[1]}.${row.name}` : `${owner(row.parent)}.${row.name}`;
    return row.owner;
  }
  for (const row of rows) owner(row);
  return rows;
}
function nearestType(types, index) {
  return types.filter((type) => type.bodyStart < index && type.bodyEnd > index)
    .sort((a, b) => (a.bodyEnd - a.bodyStart) - (b.bodyEnd - b.bodyStart))[0] ?? null;
}
function methodDeclarations(lexed, file, ownerName, methodName) {
  const types = namedTypes(lexed, file);
  if (!types.some((type) => type.owner === ownerName)) fail(`Required transport characterization owner is missing: ${ownerName} in ${file}`);
  const candidates = [];
  const pattern = new RegExp(`\\b${escapeRegex(methodName)}\\s*\\(`, 'gu');
  for (const match of lexed.code.matchAll(pattern)) {
    if (nearestType(types, match.index)?.owner !== ownerName) continue;
    const openParen = lexed.code.indexOf('(', match.index);
    const closeParen = matchingDelimiter(lexed.code, openParen, '(', ')', `${ownerName}#${methodName}`);
    let cursor = closeParen + 1;
    while (/\s/u.test(lexed.code[cursor] ?? '')) cursor++;
    if (lexed.code.startsWith('throws', cursor)) {
      while (cursor < lexed.code.length && lexed.code[cursor] !== '{' && lexed.code[cursor] !== ';') cursor++;
    }
    while (/\s/u.test(lexed.code[cursor] ?? '')) cursor++;
    if (lexed.code[cursor] !== '{' && lexed.code[cursor] !== ';') continue;
    const bodyStart = lexed.code[cursor] === '{' ? cursor : null;
    const bodyEnd = bodyStart === null ? null : matchingDelimiter(lexed.code, bodyStart, '{', '}', `${ownerName}#${methodName}`);
    candidates.push({
      bodyCode: bodyStart === null ? '' : lexed.code.slice(bodyStart + 1, bodyEnd),
      bodyCommentFree: bodyStart === null ? '' : lexed.commentFree.slice(bodyStart + 1, bodyEnd),
      signatureCode: lexed.code.slice(match.index, cursor),
      terminator: lexed.code[cursor],
    });
  }
  return candidates;
}
function oneMethod(lexed, file, owner, method, terminator = '{') {
  const candidates = methodDeclarations(lexed, file, owner, method).filter((candidate) => candidate.terminator === terminator);
  if (candidates.length !== 1) fail(`Expected exactly one ${terminator === '{' ? 'implemented' : 'abstract'} method ${owner}#${method} in ${file}; found ${candidates.length}.`);
  return candidates[0];
}
function oneMethodMatching(lexed, file, owner, method, predicate, label,
  terminator = '{') {
  const candidates = methodDeclarations(lexed, file, owner, method)
    .filter((candidate) => candidate.terminator === terminator
      && predicate(candidate));
  if (candidates.length !== 1) {
    fail(`Expected exactly one ${label} ${owner}#${method} in ${file}; found ${candidates.length}.`);
  }
  return candidates[0];
}
function requireStructural(method, pattern, label) {
  if (!pattern.test(method.bodyCode)) fail(`Transport characterization structural assertion failed: ${label}`);
}
function requireLiveLiteral(method, pattern, label) {
  if (!pattern.test(method.bodyCommentFree)) fail(`Transport characterization live-literal assertion failed: ${label}`);
}
function assignmentsTo(method, identifier) {
  const pattern = new RegExp(
    `\\b${escapeRegex(identifier)}\\s*([&|^]?=)(?!=)\\s*([^;]+);`, 'gu');
  return [...method.bodyCode.matchAll(pattern)].map((match) => ({
    index: match.index,
    operator: match[1],
    rightHandSide: match[2].replaceAll(/\s+/gu, ''),
  }));
}
function requireExactSimpleAssignments(method, identifier, rightHandSides,
  label) {
  const assignments = assignmentsTo(method, identifier);
  if (assignments.length !== rightHandSides.length
    || assignments.some((assignment, index) => assignment.operator !== '='
      || assignment.rightHandSide !== rightHandSides[index])) {
    fail(`Transport characterization structural assertion failed: ${label}`);
  }
  return assignments;
}

function writableFloorParameterNames(method, label) {
  const modifiers = '(?:(?:final\\s+)|(?:@[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*(?:\\s*\\([^)]*\\))?\\s+))*';
  const socketType = '(?:java\\s*\\.\\s*nio\\s*\\.\\s*channels\\s*\\.\\s*)?SocketChannel';
  const exceptionType = '(?:java\\s*\\.\\s*io\\s*\\.\\s*)?IOException';
  const pattern = new RegExp(
    `^writeTo\\s*\\(\\s*${modifiers}${socketType}\\s+([A-Za-z_$][\\w$]*)\\s*,\\s*${modifiers}long\\s+([A-Za-z_$][\\w$]*)\\s*\\)\\s*throws\\s+${exceptionType}\\s*$`,
    'u');
  const match = pattern.exec(method.signatureCode.trim());
  if (match === null) {
    fail(`Transport characterization structural assertion failed: ${label}`);
  }
  return { maximumBytes: match[2], socketChannel: match[1] };
}

function oneAnonymousMethod(outerMethod, anonymousType, methodName) {
  const construction = new RegExp(`\\bnew\\s+${escapeRegex(anonymousType)}\\s*\\(\\s*\\)\\s*\\{`, 'gu');
  const constructions = [...outerMethod.bodyCode.matchAll(construction)];
  if (constructions.length !== 1) {
    fail(`Expected exactly one anonymous ${anonymousType} construction in the live MCP start path; found ${constructions.length}.`);
  }
  const constructionMatch = constructions[0];
  const bodyStart = outerMethod.bodyCode.indexOf('{', constructionMatch.index);
  const bodyEnd = matchingDelimiter(outerMethod.bodyCode, bodyStart, '{', '}',
    `anonymous ${anonymousType}`);
  const code = outerMethod.bodyCode.slice(bodyStart + 1, bodyEnd);
  const commentFree = outerMethod.bodyCommentFree.slice(bodyStart + 1, bodyEnd);
  const pattern = new RegExp(`\\b${escapeRegex(methodName)}\\s*\\(`, 'gu');
  const candidates = [];
  for (const match of code.matchAll(pattern)) {
    const openParen = code.indexOf('(', match.index);
    const closeParen = matchingDelimiter(code, openParen, '(', ')',
      `anonymous ${anonymousType}#${methodName}`);
    let cursor = closeParen + 1;
    while (/\s/u.test(code[cursor] ?? '')) cursor++;
    if (code.startsWith('throws', cursor)) {
      while (cursor < code.length && code[cursor] !== '{'
        && code[cursor] !== ';') cursor++;
    }
    while (/\s/u.test(code[cursor] ?? '')) cursor++;
    if (code[cursor] !== '{') continue;
    const methodBodyEnd = matchingDelimiter(code, cursor, '{', '}',
      `anonymous ${anonymousType}#${methodName}`);
    candidates.push({
      bodyCode: code.slice(cursor + 1, methodBodyEnd),
      bodyCommentFree: commentFree.slice(cursor + 1, methodBodyEnd),
    });
  }
  if (candidates.length !== 1) {
    fail(`Expected exactly one implemented anonymous ${anonymousType}#${methodName} in the live MCP start path; found ${candidates.length}.`);
  }
  return candidates[0];
}

function oneConditionalBlock(method, condition, label) {
  const matches = [...method.bodyCode.matchAll(condition)];
  if (matches.length !== 1) {
    fail(`Transport characterization structural assertion failed: ${label}; found ${matches.length} matching conditions`);
  }
  const bodyStart = method.bodyCode.indexOf('{', matches[0].index);
  const bodyEnd = matchingDelimiter(method.bodyCode, bodyStart, '{', '}',
    label);
  return method.bodyCode.slice(bodyStart + 1, bodyEnd);
}

function topLevelAssignment(segment) {
  let paren = 0;
  let bracket = 0;
  for (let index = 0; index < segment.length; index++) {
    const character = segment[index];
    if (character === '(') paren++;
    else if (character === ')') paren--;
    else if (character === '[') bracket++;
    else if (character === ']') bracket--;
    else if (character === '=' && paren === 0 && bracket === 0
      && segment[index - 1] !== '=' && segment[index + 1] !== '=') return index;
  }
  return -1;
}
function splitTopLevelCommas(statement) {
  const pieces = [];
  let start = 0;
  let paren = 0;
  let bracket = 0;
  let brace = 0;
  let angle = 0;
  for (let index = 0; index < statement.length; index++) {
    const character = statement[index];
    if (character === '(') paren++;
    else if (character === ')') paren--;
    else if (character === '[') bracket++;
    else if (character === ']') bracket--;
    else if (character === '{') brace++;
    else if (character === '}') brace--;
    else if (character === '<') angle++;
    else if (character === '>' && angle > 0) angle--;
    else if (character === ',' && paren === 0 && bracket === 0
      && brace === 0 && angle === 0) {
      pieces.push(statement.slice(start, index));
      start = index + 1;
    }
  }
  pieces.push(statement.slice(start));
  return pieces;
}
function withoutJavaAnnotations(code, label) {
  const output = code.split('');
  for (let index = 0; index < code.length; index++) {
    if (code[index] !== '@') continue;
    let cursor = index + 1;
    while (/\s/u.test(code[cursor] ?? '')) cursor++;
    if (!/[A-Za-z_$]/u.test(code[cursor] ?? '')) continue;
    cursor++;
    while (/[A-Za-z0-9_$]/u.test(code[cursor] ?? '')) cursor++;
    while (true) {
      let component = cursor;
      while (/\s/u.test(code[component] ?? '')) component++;
      if (code[component] !== '.') break;
      component++;
      while (/\s/u.test(code[component] ?? '')) component++;
      if (!/[A-Za-z_$]/u.test(code[component] ?? '')) break;
      component++;
      while (/[A-Za-z0-9_$]/u.test(code[component] ?? '')) component++;
      cursor = component;
    }
    while (/\s/u.test(code[cursor] ?? '')) cursor++;
    if (code[cursor] === '(') {
      cursor = matchingDelimiter(code, cursor, '(', ')', label) + 1;
    }
    for (let erase = index; erase < cursor; erase++) {
      if (output[erase] !== '\n' && output[erase] !== '\r') output[erase] = ' ';
    }
    index = cursor - 1;
  }
  return output.join('');
}
function directFieldStatements(lexed, file, type) {
  const fields = [];
  let statementStart = type.bodyStart + 1;
  let retainedBraceDepth = 0;
  for (let index = statementStart; index < type.bodyEnd; index++) {
    const character = lexed.code[index];
    if (character === '{') {
      if (retainedBraceDepth > 0) retainedBraceDepth++;
      else {
        const header = lexed.code.slice(statementStart, index);
        if (topLevelAssignment(header) >= 0) retainedBraceDepth = 1;
        else {
          const end = matchingDelimiter(lexed.code, index, '{', '}', `${file}:${type.owner}`);
          index = end; statementStart = end + 1;
        }
      }
      continue;
    }
    if (character === '}' && retainedBraceDepth > 0) { retainedBraceDepth--; continue; }
    if (character !== ';' || retainedBraceDepth > 0) continue;
    const rawStart = statementStart;
    const statement = lexed.code.slice(rawStart, index).trim();
    statementStart = index + 1;
    if (statement === '') continue;
    const declarators = splitTopLevelCommas(statement);
    const firstAssignment = topLevelAssignment(declarators[0]);
    const firstPrefix = (firstAssignment >= 0
      ? declarators[0].slice(0, firstAssignment) : declarators[0]).trim();
    const firstWithoutAnnotations = withoutJavaAnnotations(firstPrefix,
      `${file}:${type.owner} field or method declaration`);
    if (firstAssignment < 0 && /\(/u.test(firstWithoutAnnotations)) continue;
    const names = [];
    for (const part of declarators) {
      const assignment = topLevelAssignment(part);
      const prefix = assignment >= 0 ? part.slice(0, assignment) : part;
      const identifiers = [...withoutJavaAnnotations(prefix,
        `${file}:${type.owner} field declaration`)
        .matchAll(/[A-Za-z_$][\w$]*/gu)].map((match) => match[0]);
      if (identifiers.length === 0) {
        fail(`Could not derive a direct field name: ${file}:${type.owner}: ${statement}`);
      }
      names.push(identifiers.at(-1));
    }
    fields.push({
      code: lexed.code.slice(rawStart, index + 1),
      commentFree: lexed.commentFree.slice(rawStart, index + 1),
      names,
    });
  }
  return fields;
}
function directFieldNames(lexed, file, type) {
  return directFieldStatements(lexed, file, type)
    .flatMap((field) => field.names);
}
function normalizedIdentifierWords(identifier) {
  return identifier.replace(/([A-Z]+)([A-Z][a-z])/gu, '$1 $2')
    .replace(/([a-z0-9])([A-Z])/gu, '$1 $2')
    .replace(/[_$]+/gu, ' ').toLowerCase().trim().split(/\s+/u);
}
function identifierPolicyTerms(identifier) {
  const words = normalizedIdentifierWords(identifier);
  const has = (term) => words.includes(term) || words.includes(`${term}s`);
  return {
    domains: STATE_DOMAIN_TERMS.filter(has),
    roles: STATE_STORAGE_ROLE_TERMS.filter(has),
  };
}
function suspiciousStateStorageIdentifier(identifier) {
  const terms = identifierPolicyTerms(identifier);
  return terms.domains.length >= 2
    || terms.domains.length >= 1 && terms.roles.length >= 1;
}
function futureDomainFieldName(identifier) {
  return identifierPolicyTerms(identifier).domains.length >= 1;
}
function dataBearingDomainRecord(type) {
  return type.kind === 'record'
    && type.recordComponentCode?.trim() !== ''
    && identifierPolicyTerms(type.name).domains.length >= 1;
}
function dataBearingDomainClass(type, directFields) {
  return type.kind === 'class'
    && directFields.length > 0
    && identifierPolicyTerms(type.name).domains.length >= 1;
}
function reviewedDomainFieldKey(file, owner, field) {
  return `${file}\u0000${owner}\u0000${field}`;
}
function reviewedDomainTypeKey(file, owner) {
  return `${file}\u0000${owner}`;
}
function verifyStateStorageDeclarations(sourceFiles) {
  const fieldRows = [...REVIEWED_EXISTING_DOMAIN_FIELDS, ...REVIEWED_2025_SESSION_DOMAIN_FIELDS];
  const typeRows = [...REVIEWED_EXISTING_DOMAIN_TYPES, ...REVIEWED_2025_SESSION_DOMAIN_TYPES];
  const reviewedFields = new Map(fieldRows.map((row) => [
    reviewedDomainFieldKey(row.file, row.owner, row.field), row,
  ]));
  const reviewedTypes = new Map(typeRows.map((row) => [
    reviewedDomainTypeKey(row.file, row.owner), row,
  ]));
  if (reviewedFields.size !== fieldRows.length || reviewedTypes.size !== typeRows.length)
    fail('Reviewed transport-domain declaration allowances must have unique exact keys.');
  const resolvedReviewedFields = new Set();
  const resolvedReviewedTypes = new Set();
  for (const sourceFile of sourceFiles) {
    const types = namedTypes(sourceFile.lexed, sourceFile.file);
    for (const type of types) {
      const reviewedTypeKey = reviewedDomainTypeKey(sourceFile.file, type.owner);
      if (suspiciousStateStorageIdentifier(type.name) && !reviewedTypes.has(reviewedTypeKey)) fail(`Future transport state/storage type declaration is outside the 4.0 baseline: ${sourceFile.file}: ${type.owner}`);
      if (dataBearingDomainRecord(type) && !reviewedTypes.has(reviewedTypeKey)) fail(`Data-bearing future-domain record declaration is outside the 4.0 baseline: ${sourceFile.file}: ${type.owner}`);
      const directFields = directFieldNames(sourceFile.lexed, sourceFile.file,
        type);
      if (suspiciousStateStorageIdentifier(type.name) || dataBearingDomainRecord(type)
        || dataBearingDomainClass(type, directFields)) {
        const key = reviewedDomainTypeKey(sourceFile.file, type.owner);
        if (!reviewedTypes.has(key)) fail(`Data-bearing future-domain class declaration is outside the 4.0 baseline: ${sourceFile.file}: ${type.owner}`);
        if (resolvedReviewedTypes.has(key)) fail(`Reviewed existing transport-domain type resolved more than once: ${sourceFile.file}: ${type.owner}`);
        resolvedReviewedTypes.add(key);
      }
      for (const field of directFields) {
        if (!futureDomainFieldName(field)) continue;
        const key = reviewedDomainFieldKey(sourceFile.file, type.owner, field);
        if (!reviewedFields.has(key)) fail(`Future transport state/storage field declaration is outside the 4.0 baseline: ${sourceFile.file}: ${type.owner}#${field}`);
        if (resolvedReviewedFields.has(key)) fail(`Reviewed existing transport-domain field resolved more than once: ${sourceFile.file}: ${type.owner}#${field}`);
        resolvedReviewedFields.add(key);
      }
    }
  }
  const unresolved = [...reviewedFields.keys()].filter((key) =>
    !resolvedReviewedFields.has(key));
  if (unresolved.length > 0) {
    fail(`Reviewed existing transport-domain fields did not resolve exactly: ${unresolved.join(', ')}`);
  }
  const unresolvedTypes = [...reviewedTypes.keys()].filter((key) =>
    !resolvedReviewedTypes.has(key));
  if (unresolvedTypes.length > 0) {
    fail(`Reviewed existing transport-domain types did not resolve exactly: ${unresolvedTypes.join(', ')}`);
  }
}
function sourceBundle(root, file, cache) {
  if (!cache.has(file)) {
    const source = readUtf8Regular(safeProjectEntry(root, file,
      `transport characterization source ${file}`),
    `transport characterization source ${file}`);
    cache.set(file, { file, lexed: lexJava(source, file), source });
  }
  return cache.get(file);
}

function verifyCharacterizationSources(root, sourceFiles) {
  const cache = new Map(sourceFiles.map((sourceFile) => [sourceFile.file, sourceFile]));
  const requestPath = 'src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java';
  const runtimePath = 'src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java';
  const bridgePath = 'src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java';
  const legacyWirePath = 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacyResponseWire.java';
  const simulationPath = 'src/main/java/com/soklet/internal/mcp/protocol/McpSimulationRuntime.java';
  const sessionStorePath = 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacySessionStore.java';
  const legacyHttpPath = 'src/main/java/com/soklet/internal/mcp/protocol/McpLegacyHttpWire.java';
  const routerPath = 'src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java';
  const outboundPath = 'src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java';
  const writablePath = 'src/main/java/com/soklet/internal/microhttp/WritableSource.java';
  const eventLoopPath = 'src/main/java/com/soklet/internal/microhttp/ConnectionEventLoop.java';
  const handlerPath = 'src/main/java/com/soklet/internal/microhttp/Handler.java';
  const serverPath = 'src/main/java/com/soklet/McpServer.java';
  const request = sourceBundle(root, requestPath, cache);
  const runtime = sourceBundle(root, runtimePath, cache);
  const bridge = sourceBundle(root, bridgePath, cache);
  const legacyWire = sourceBundle(root, legacyWirePath, cache);
  const simulation = sourceBundle(root, simulationPath, cache);
  const sessionStore = sourceBundle(root, sessionStorePath, cache);
  const legacyHttp = sourceBundle(root, legacyHttpPath, cache);
  const router = sourceBundle(root, routerPath, cache);
  const outbound = sourceBundle(root, outboundPath, cache);
  const writable = sourceBundle(root, writablePath, cache);
  const eventLoop = sourceBundle(root, eventLoopPath, cache);
  const handler = sourceBundle(root, handlerPath, cache);
  const server = sourceBundle(root, serverPath, cache);
  const process = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime',
    'processRequestSafely');
  const processEntry = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime',
    'processRequest');
  requireStructural(processEntry,
    /MicrohttpResponse\s+response\s*=\s*processRequestSafely\s*\(\s*effectiveAddress\s*,\s*request\s*,\s*requestControl\s*,\s*application\s*\)\s*;/u,
    'the live processRequest entry must route through processRequestSafely');
  const networkSubmit = oneMethodMatching(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'submitRequest',
    (candidate) => !/\bRequest\s+publicRequest\b/u.test(candidate.signatureCode),
    'network submitRequest overload');
  requireStructural(networkSubmit,
    /submitRequest\s*\(\s*processor\s*,\s*application\s*,\s*effectiveAddress\s*,\s*request\s*,\s*null\s*,\s*null\s*,\s*lifecycleAdmission\s*,\s*callback\s*\)\s*;/u,
    'the network submitRequest overload must enter the contextual request-control path');
  const contextualSubmit = oneMethodMatching(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'submitRequest',
    (candidate) => /\bRequest\s+publicRequest\b/u.test(candidate.signatureCode),
    'contextual submitRequest overload');
  requireStructural(contextualSubmit,
    /MicrohttpResponse\s+response\s*=\s*processRequest\s*\(\s*requiredAddress\s*,\s*request\s*,\s*requestControl\s*,\s*application\s*\)\s*;/u,
    'the contextual request-control task must invoke the live processRequest entry');

  const progress = oneMethod(bridge.lexed, bridgePath, 'com.soklet.internal.mcp.protocol.McpServerRuntimeBridge', 'progressEmitterFor');
  requireStructural(progress, /invocation\s*\.\s*sendNotification\s*\(\s*invocation\s*\.\s*protocolProfile\s*\(\s*\)\s*\.\s*renderFrameworkNotification\s*\(\s*McpProfileFrameworkNotificationKind\s*\.\s*PROGRESS/u, 'progressEmitterFor must render and send the PROGRESS notification on the invocation');
  const invocationSend = oneMethod(router.lexed, routerPath,
    'com.soklet.internal.mcp.protocol.McpApplicationInvocation',
    'sendNotification');
  if (!/^\s*return\s+notificationWriter\s*\.\s*write\s*\(\s*requireNonNull\s*\(\s*notification\s*\)\s*\)\s*;\s*$/u
    .test(invocationSend.bodyCode)) {
    fail('Transport characterization structural assertion failed: McpApplicationInvocation.sendNotification must use its retained notification writer');
  }
  const exchangeRun = oneMethod(router.lexed, routerPath,
    'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange',
    'runHandler');
  requireStructural(exchangeRun,
    /new\s+McpApplicationInvocation\s*\([\s\S]*?new\s+McpApplicationNotificationWriter\s*\(\s*\)\s*\{[\s\S]*?\}\s*,\s*this\s*::\s*requirePublicHandlerEntry/u,
    'the application invocation notification slot must bind its checked notification writer');
  const invocationNotificationWriter = oneAnonymousMethod(exchangeRun,
    'McpApplicationNotificationWriter', 'write');
  requireStructural(invocationNotificationWriter,
    /^\s*return\s+Exchange\s*\.\s*this\s*\.\s*writeNotification\s*\(\s*notification\s*\)\s*;\s*$/u,
    'the application invocation notification writer must route explicitly through Exchange.writeNotification');
  const invocationNotificationActive = oneAnonymousMethod(exchangeRun,
    'McpApplicationNotificationWriter', 'isActive');
  requireStructural(invocationNotificationActive,
    /^\s*TransportLease\s+lease\s*=\s*transportLease\s*\.\s*get\s*\(\s*\)\s*;\s*return\s+lease\s*!=\s*null\s*&&\s*lease\s*\.\s*responseWriter\s*\(\s*\)\s*\.\s*isNotificationDeliveryActive\s*\(\s*\)\s*;\s*$/u,
    'the application invocation notification activity must follow its retained transport lease');
  const exchangeWriteNotification = oneMethod(router.lexed, routerPath,
    'com.soklet.internal.mcp.protocol.McpApplicationExecution.Exchange',
    'writeNotification');
  requireStructural(exchangeWriteNotification,
    /return\s+requireNonNull\s*\(\s*lease\s*\)\s*\.\s*responseWriter\s*\(\s*\)\s*\.\s*writeNotification\s*\(\s*notification\s*\)\s*;/u,
    'Exchange.writeNotification must route through its retained transport response writer');
  const runtimeNotificationWriter = oneAnonymousMethod(process,
    'McpApplicationResponseWriter', 'writeNotification');
  if (!/^\s*return\s+requestControl\s*\.\s*writeApplicationNotification\s*\(\s*notification\s*,\s*corsHeaders\s*\)\s*;\s*$/u
    .test(runtimeNotificationWriter.bodyCode)) {
    fail('Transport characterization structural assertion failed: the runtime notification writer must route through RequestControl.writeApplicationNotification');
  }
  const dispatchCalls = [...process.bodyCode.matchAll(
    /\bapplication\s*\.\s*dispatchWithSokletRequest\s*\(/gu)];
  if (dispatchCalls.length !== 2) {
    fail(`Transport characterization structural assertion failed: the runtime must have exactly two contextual application dispatches; found ${dispatchCalls.length}`);
  }
  for (const dispatchCall of dispatchCalls) {
    const openParen = process.bodyCode.indexOf('(', dispatchCall.index);
    const closeParen = matchingDelimiter(process.bodyCode, openParen, '(', ')',
      'McpHttpServerRuntime application dispatch');
    const argumentsCode = process.bodyCode.slice(openParen + 1, closeParen);
    if ([...argumentsCode.matchAll(/\bresponseWriter\b/gu)].length !== 1
      || !/requestControl\s*::\s*applicationTerminated/u.test(argumentsCode)) {
      fail('Transport characterization structural assertion failed: every contextual application dispatch must retain the request-scoped response writer and terminal callback');
    }
  }
  const writeNotification = oneMethod(runtime.lexed, runtimePath, 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl', 'writeApplicationNotification');
  requireStructural(writeNotification, /stream\s*\.\s*enqueueMessage\s*\(\s*notification\s*\)\s*;/u, 'writeApplicationNotification must enqueue on its request stream');
  requireExactSimpleAssignments(writeNotification, 'stream',
    ['responseStream', 'responseStream', 'newResponseStream()'],
    'writeApplicationNotification must source both observed stream reads from responseStream and create only its one installed first-message stream');
  requireExactSimpleAssignments(writeNotification, 'responseStream', ['stream'],
    'writeApplicationNotification must install its only newly created stream as responseStream');
  const offerEvent = oneMethod(runtime.lexed, runtimePath, 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl', 'offerSubscriptionEvent');
  requireStructural(offerEvent, /stream\s*\.\s*offerCoalescingMessage\s*\(\s*notification\s*,\s*coalescingKey\s*\)/u, 'offerSubscriptionEvent must offer on its request stream');
  requireExactSimpleAssignments(offerEvent, 'stream', ['responseStream'],
    'offerSubscriptionEvent must use the installed responseStream');
  const productionStreamConstructor = oneMethodMatching(request.lexed,
    requestPath, 'com.soklet.internal.mcp.protocol.McpRequestSseStream',
    'McpRequestSseStream', (candidate) =>
      /\bint\s+frameCapacity\b/u.test(candidate.signatureCode)
      && /\bMcpProtocolProfile\s+protocolProfile\b/u.test(candidate.signatureCode),
    'selected-profile production constructor');
  requireStructural(productionStreamConstructor,
    /this\s*\.\s*legacyJsonCodec\s*=\s*legacyJsonCodec\s*\(\s*jsonCodec\s*,\s*protocolProfile\s*\)\s*;/u,
    'the selected-profile request stream must retain its exact legacy codec selection');
  requireStructural(productionStreamConstructor,
    /this\s*\.\s*channel\s*=\s*new\s+TransportChannel\s*\(\s*frameCapacity\s*,\s*maximumFrameBytes\s*,\s*requireNonNull\s*\(\s*clock\s*\)\s*,\s*requireNonNull\s*\(\s*listener\s*\)\s*\)\s*;/u,
    'the production request stream must install one TransportChannel');
  const defaultStreamConstructor = oneMethodMatching(request.lexed,
    requestPath, 'com.soklet.internal.mcp.protocol.McpRequestSseStream',
    'McpRequestSseStream', (candidate) =>
      /\bint\s+frameCapacity\b/u.test(candidate.signatureCode)
      && !/\bMcpProtocolProfile\s+protocolProfile\b/u.test(candidate.signatureCode),
    'default modern production constructor');
  requireStructural(defaultStreamConstructor,
    /^\s*this\s*\(\s*frameCapacity\s*,\s*jsonLimits\s*,\s*envelopeCodec\s*,\s*null\s*,\s*Mcp20260728ProtocolProfile\s*\.\s*INSTANCE\s*,\s*clock\s*,\s*listener\s*\)\s*;\s*$/u,
    'the default request stream must preserve the modern profile');
  const selectedEncoder = oneMethodMatching(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'encodeMessage',
    (candidate) => /\bMcpProtocolProfile\s+protocolProfile\b/u.test(candidate.signatureCode),
    'selected-profile message encoder');
  requireStructural(selectedEncoder,
    /^\s*return\s+encodeMessage\s*\(\s*requireNonNull\s*\(\s*envelopeCodec\s*\)\s*,\s*legacyJsonCodec\s*\(\s*jsonCodec\s*,\s*protocolProfile\s*\)\s*,\s*message\s*\)\s*;\s*$/u,
    'the selected-profile message encoder must share the stream legacy codec selection');
  const messageEncoder = oneMethodMatching(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'encodeMessage',
    (candidate) => !/\bMcpProtocolProfile\s+protocolProfile\b/u.test(candidate.signatureCode),
    'retained-codec message encoder');
  requireStructural(messageEncoder,
    /McpJsonRpcMessage\s+outbound\s*=\s*McpProtocolSupport\s*\.\s*requireServerOutboundMessage\s*\(\s*message\s*\)\s*;/u,
    'the message encoder must validate server outbound message kinds');
  requireStructural(messageEncoder,
    /return\s+legacyJsonCodec\s*!=\s*null\s*&&\s*outbound\s+instanceof\s+McpJsonRpcMessage\s*\.\s*ResultResponse\s+response\s*\?\s*McpLegacyResponseWire\s*\.\s*encode\s*\(\s*legacyJsonCodec\s*,\s*response\s*\)\s*:\s*envelopeCodec\s*\.\s*encode\s*\(\s*outbound\s*\)\s*;/u,
    'the retained-codec message encoder must preserve selected legacy terminal projection and common envelopes');
  const messageFrame = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'frame');
  requireStructural(messageFrame,
    /byte\s*\[\s*\]\s+json\s*=\s*encodeMessage\s*\(\s*envelopeCodec\s*,\s*legacyJsonCodec\s*,\s*message\s*\)\s*;/u,
    'message frames must use the retained selected-profile encoder');
  requireStructural(messageFrame,
    /McpJsonObject\s+jsonMessage\s*=\s*legacyJsonCodec\s*!=\s*null\s*&&\s*message\s+instanceof\s+McpJsonRpcMessage\s*\.\s*ResultResponse\s+response\s*\?\s*McpLegacyResponseWire\s*\.\s*projectEnvelope\s*\(\s*response\s*\)\s*:\s*message\s*\.\s*toJsonObject\s*\(\s*\)\s*;/u,
    'message frames must retain the selected-profile JSON snapshot');
  requireStructural(messageFrame,
    /return\s+new\s+Frame\s*\(\s*FrameType\s*\.\s*JSON_MESSAGE\s*,\s*message\s*,\s*jsonMessage\s*,\s*frame\s*\)\s*;/u,
    'message frames must carry the projected JSON snapshot');
  const defaultFrameConstructor = oneMethodMatching(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream.Frame', 'Frame',
    (candidate) => /\bFrameType\s+type\b/u.test(candidate.signatureCode)
      && !/\bMcpJsonObject\b/u.test(candidate.signatureCode),
    'modern-default frame constructor');
  requireStructural(defaultFrameConstructor,
    /^\s*this\s*\(\s*type\s*,\s*message\s*,\s*message\s*==\s*null\s*\?\s*null\s*:\s*message\s*\.\s*toJsonObject\s*\(\s*\)\s*,\s*encodedBytes\s*\)\s*;\s*$/u,
    'the default frame constructor must preserve the modern JSON snapshot');
  const finiteLegacyEncoder = oneMethod(legacyWire.lexed, legacyWirePath,
    'com.soklet.internal.mcp.protocol.McpLegacyResponseWire', 'encode');
  requireStructural(finiteLegacyEncoder,
    /^\s*return\s+requireNonNull\s*\(\s*jsonCodec\s*\)\s*\.\s*toUtf8Bytes\s*\(\s*projectEnvelope\s*\(\s*response\s*\)\s*\)\s*;\s*$/u,
    'finite legacy encoding must share the projected JSON envelope');
  const simulationCapture = oneMethod(simulation.lexed, simulationPath,
    'com.soklet.internal.mcp.protocol.McpSimulationRuntime', 'captureFrameWhileLocked');
  requireStructural(simulationCapture,
    /McpJsonValue\s+message\s*=\s*McpServerRuntimeBridge\s*\.\s*toPublic\s*\(\s*requireNonNull\s*\(\s*frame\s*\.\s*jsonMessage\s*\(\s*\)\s*\)\s*\)\s*;/u,
    'simulation stream items must expose the projected frame JSON snapshot');
  const simulationCompletion = oneMethod(simulation.lexed, simulationPath,
    'com.soklet.internal.mcp.protocol.McpSimulationRuntime', 'complete');
  requireStructural(simulationCompletion,
    /this\s*\.\s*terminalMessage\s*=\s*McpServerRuntimeBridge\s*\.\s*toPublic\s*\(\s*requireNonNull\s*\(\s*terminalFrame\s*\.\s*jsonMessage\s*\(\s*\)\s*\)\s*\)\s*;/u,
    'simulation terminal duplicates must expose the projected frame JSON snapshot');
  if (!/MESSAGE_PREFIX\s*=\s*"data: "\s*\.\s*getBytes\s*\(\s*StandardCharsets\s*\.\s*US_ASCII\s*\)/u.test(request.lexed.commentFree)
    || !/MESSAGE_SUFFIX\s*=\s*"\\n\\n"\s*\.\s*getBytes\s*\(\s*StandardCharsets\s*\.\s*US_ASCII\s*\)/u.test(request.lexed.commentFree)) {
    fail('Transport characterization live-literal assertion failed: request message framing must retain data-only SSE without event IDs or priming fields');
  }
  const requestResponse = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'response');
  requireStructural(requestResponse,
    /return\s+channel\s*\.\s*response\s*\(\s*List\s*\.\s*copyOf\s*\(\s*headers\s*\)\s*\)\s*;/u,
    'McpRequestSseStream.response must expose its installed channel');
  const requestEnqueue = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'enqueueMessage');
  requireStructural(requestEnqueue,
    /channel\s*\.\s*enqueue\s*\(\s*frame\s*\(\s*requireNonNull\s*\(\s*message\s*\)\s*\)\s*\)\s*;/u,
    'McpRequestSseStream.enqueueMessage must use its installed channel');
  const requestOffer = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream', 'offerMessage');
  if (!/^\s*return\s+channel\s*\.\s*offer\s*\(\s*frame\s*\(\s*requireNonNull\s*\(\s*message\s*\)\s*\)\s*\)\s*;\s*$/u
    .test(requestOffer.bodyCode)) {
    fail('Transport characterization structural assertion failed: McpRequestSseStream.offerMessage must use its installed channel');
  }
  const requestOfferCoalescing = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream',
    'offerCoalescingMessage');
  if (!/^\s*Frame\s+frame\s*=\s*frame\s*\(\s*requireNonNull\s*\(\s*message\s*\)\s*\)\s*;\s*testHooks\s*\.\s*beforeCoalescingMessageOffer\s*\(\s*\)\s*;\s*return\s+channel\s*\.\s*offerCoalescing\s*\(\s*frame\s*,\s*requireNonNull\s*\(\s*coalescingKey\s*\)\s*\)\s*;\s*$/u
    .test(requestOfferCoalescing.bodyCode)) {
    fail('Transport characterization structural assertion failed: McpRequestSseStream.offerCoalescingMessage must use its installed channel');
  }
  const requestTypes = namedTypes(request.lexed, requestPath);
  const transportType = requestTypes.find((type) =>
    type.owner === 'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel');
  if (transportType === undefined) fail('Transport characterization structural assertion failed: TransportChannel type is missing');
  const outboundFields = directFieldStatements(request.lexed, requestPath,
    transportType).filter((field) => /\bMcpOutboundChannel\b/u.test(field.code));
  if (outboundFields.length !== 1
    || !/^\s*(?:@[A-Za-z_$][\w$]*(?:\s*\([^;]*\))?\s*)*private\s+final\s+McpOutboundChannel\s+delegate\s*;\s*$/u
      .test(outboundFields[0].code)) {
    fail('Transport characterization structural assertion failed: TransportChannel must declare exactly one direct McpOutboundChannel field named delegate');
  }
  const transportBody = request.lexed.code.slice(transportType.bodyStart + 1,
    transportType.bodyEnd);
  const outboundConstructions = [...transportBody.matchAll(
    /\bnew\s+McpOutboundChannel\s*\(/gu)];
  if (outboundConstructions.length !== 1) {
    fail(`Transport characterization structural assertion failed: TransportChannel must construct exactly one McpOutboundChannel; found ${outboundConstructions.length}`);
  }
  const transportConstructor = oneMethod(request.lexed, requestPath, 'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel', 'TransportChannel');
  requireStructural(transportConstructor, /this\s*\.\s*delegate\s*=\s*new\s+McpOutboundChannel\s*\(/u, 'TransportChannel must construct its single McpOutboundChannel delegate');
  const transportResponse = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel',
    'response');
  requireStructural(transportResponse,
    /^\s*return\b[\s\S]*this\s*\.\s*delegate\s*::\s*newWritableSource[\s\S]*;\s*$/u,
    'TransportChannel.response must expose the shared delegate writable source');
  const transportEnqueue = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel',
    'enqueue');
  requireStructural(transportEnqueue,
    /^\s*return\s+this\s*\.\s*delegate\s*\.\s*enqueue\s*\([\s\S]*\)\s*;\s*$/u,
    'TransportChannel.enqueue must use the shared delegate');
  const transportOffer = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel',
    'offer');
  requireStructural(transportOffer,
    /^\s*return\s+this\s*\.\s*delegate\s*\.\s*offer\s*\([\s\S]*\)\s*;\s*$/u,
    'TransportChannel.offer must use the shared delegate');
  const transportOfferCoalescing = oneMethod(request.lexed, requestPath,
    'com.soklet.internal.mcp.protocol.McpRequestSseStream.TransportChannel',
    'offerCoalescing');
  requireStructural(transportOfferCoalescing,
    /^\s*return\s+this\s*\.\s*delegate\s*\.\s*offerCoalescing\s*\([\s\S]*\)\s*;\s*$/u,
    'TransportChannel.offerCoalescing must use the shared delegate');

  const writableFloor = oneMethod(writable.lexed, writablePath, 'com.soklet.internal.microhttp.WritableSource', 'writeTo', ';');
  writableFloorParameterNames(writableFloor,
    'WritableSource.writeTo must retain the SocketChannel/long socket floor');
  const outboundWrite = oneMethod(outbound.lexed, outboundPath, 'com.soklet.internal.mcp.transport.McpOutboundChannel.WritableSourceFacade', 'writeTo');
  const outboundWriteParameters = writableFloorParameterNames(outboundWrite,
    'WritableSourceFacade.writeTo must retain the SocketChannel/long socket floor');
  const outboundWriteBody = new RegExp(
    `^\\s*return\\s+McpOutboundChannel\\s*\\.\\s*this\\s*\\.\\s*writeTo\\s*\\(\\s*${escapeRegex(outboundWriteParameters.socketChannel)}\\s*,\\s*${escapeRegex(outboundWriteParameters.maximumBytes)}\\s*\\)\\s*;\\s*$`,
    'u');
  if (!outboundWriteBody.test(outboundWrite.bodyCode)) {
    fail('Transport characterization structural assertion failed: WritableSourceFacade.writeTo must return the exact McpOutboundChannel write result');
  }

  const start = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime',
    'startWhileMetricsDeferred');
  const handlerEntry = oneAnonymousMethod(start, 'Handler', 'handle');
  requireStructural(handlerEntry,
    /submitRequest\s*\(\s*readyProcessor\s*,\s*readyApplicationExecution\s*,\s*candidateAddress\s*\.\s*get\s*\(\s*\)\s*,\s*request\s*,\s*trackedLifecycleAdmission\s*,\s*callback\s*\)\s*;/u,
    'the live Microhttp handler must submit each admitted request to the MCP processor');
  const startCalls = [...start.bodyCode.matchAll(
    /\bcandidateEventLoop\s*\.\s*start\s*\(\s*\)\s*;/gu)];
  if (startCalls.length !== 1) {
    fail(`Transport characterization structural assertion failed: the dedicated MCP EventLoop must be started exactly once; found ${startCalls.length}`);
  }
  const monitor = oneAnonymousMethod(start, 'Handler',
    'monitorClientDisconnectsDuringStreamingResponse');
  if (!/^\s*return\s+true\s*;\s*$/u.test(monitor.bodyCode)) fail('Transport characterization structural assertion failed: live MCP handler must opt into streaming disconnect monitoring');
  const inputPolicy = oneMethod(handler.lexed, handlerPath,
    'com.soklet.internal.microhttp.Handler', 'streamingResponseInputPolicy');
  if (!/^\s*return\s+monitorClientDisconnectsDuringStreamingResponse\s*\(\s*request\s*\)\s*\?\s*StreamingResponseInputPolicy\s*\.\s*DISCARD\s*:\s*StreamingResponseInputPolicy\s*\.\s*NONE\s*;\s*$/u
    .test(inputPolicy.bodyCode)) {
    fail('Transport characterization structural assertion failed: the MCP monitor opt-in must select DISCARD through the Handler default');
  }
  const prepare = oneMethod(eventLoop.lexed, eventLoopPath, 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection', 'prepareToWriteResponse');
  requireStructural(prepare, /if\s*\(\s*microhttpResponse\s*\.\s*streaming\s*\(\s*\)\s*\)\s*\{\s*nextStreamingInputPolicy\s*=\s*handler\s*\.\s*streamingResponseInputPolicy\s*\(\s*dispatch\s*\.\s*request\s*\)\s*;/u, 'prepareToWriteResponse must call the handler streaming-input policy for a streaming response');
  requireExactSimpleAssignments(prepare, 'nextStreamingInputPolicy', [
    'Handler.StreamingResponseInputPolicy.NONE',
    'handler.streamingResponseInputPolicy(dispatch.request)',
  ], 'prepareToWriteResponse must derive exactly one streaming-input policy from the handler');
  requireExactSimpleAssignments(prepare,
    'streamingResponseInputPolicy',
    ['nextStreamingInputPolicy'],
    'prepareToWriteResponse must install the streaming-input policy exactly once without resetting it');
  requireStructural(prepare,
    /streamingResponseInputPolicy\s*=\s*nextStreamingInputPolicy\s*;\s*streamingResponseBytesDiscarded\s*=\s*0\s*;\s*if\s*\(\s*nextStreamingInputPolicy\s*!=\s*Handler\s*\.\s*StreamingResponseInputPolicy\s*\.\s*NONE\s*\)\s*\{\s*enableReadInterestForDisconnectMonitoring\s*\(\s*\)\s*;/u,
    'prepareToWriteResponse must arm reads only for the installed streaming-input policy');
  const readable = oneMethod(eventLoop.lexed, eventLoopPath,
    'com.soklet.internal.microhttp.ConnectionEventLoop.Connection',
    'doOnReadable');
  requireStructural(readable,
    /if\s*\(\s*streamingResponseInputPolicy\s*!=\s*Handler\s*\.\s*StreamingResponseInputPolicy\s*\.\s*NONE\s*&&\s*writableSource\s*!=\s*null\s*\)\s*\{\s*doOnReadableDuringStreamingResponse\s*\(\s*\)\s*;\s*return\s*;\s*\}/u,
    'the live readable dispatcher must route committed monitored streams to the discard path');
  const discard = oneMethod(eventLoop.lexed, eventLoopPath, 'com.soklet.internal.microhttp.ConnectionEventLoop.Connection', 'doOnReadableDuringStreamingResponse');
  requireStructural(discard, /if\s*\(\s*streamingResponseInputPolicy\s*==\s*Handler\s*\.\s*StreamingResponseInputPolicy\s*\.\s*NONE\s*\|\|\s*writableSource\s*==\s*null\s*\)/u, 'streaming readable path must be guarded by the active input policy and source');
  requireStructural(discard, /socketChannel\s*\.\s*read\s*\(\s*buffer\s*\)/u, 'streaming readable path must consume client bytes');
  requireStructural(discard,
    /boolean\s+retainPipelinedRequests\s*=\s*streamingResponseInputPolicy\s*==\s*Handler\s*\.\s*StreamingResponseInputPolicy\s*\.\s*RETAIN\s*;/u,
    'only RETAIN may buffer pipelined streaming input');
  requireStructural(discard,
    /int\s+remainingCapacity\s*=\s*options\s*\.\s*maxRequestSize\s*\(\s*\)\s*-\s*bufferedBytes\s*;[\s\S]*?buffer\s*\.\s*limit\s*\(\s*overflowProbe\s*\?\s*1\s*:\s*Math\s*\.\s*min\s*\(\s*buffer\s*\.\s*capacity\s*\(\s*\)\s*,\s*remainingCapacity\s*\)\s*\)\s*;/u,
    'streaming input reads must remain bounded by the request-size limit');
  const retainBranch = oneConditionalBlock(discard,
    /\bif\s*\(\s*retainPipelinedRequests\s*\)\s*\{/gu,
    'the streaming RETAIN branch must remain unique');
  if (!/\bbyteTokenizer\s*\.\s*add\s*\(\s*buffer\s*\)\s*;/u.test(retainBranch)
      || !/\breturn\s*;\s*$/u.test(retainBranch.trim())
      || [...discard.bodyCode.matchAll(/\bbyteTokenizer\s*\.\s*add\s*\(/gu)].length !== 1) {
    fail('Transport characterization structural assertion failed: DISCARD input must bypass ByteTokenizer');
  }
  const discardIncrement = discard.bodyCode.search(/streamingResponseBytesDiscarded\s*\+=\s*numBytes\s*;/u);
  const closeAssignments = assignmentsTo(discard, 'closeAfterResponse');
  if (discardIncrement < 0 || closeAssignments.length !== 2
    || closeAssignments.some(({ operator, rightHandSide }) =>
      operator !== '=' || rightHandSide !== 'true')
    || closeAssignments[0].index >= discardIncrement
    || closeAssignments[1].index <= discardIncrement) {
    fail('Transport characterization structural assertion failed: discarded bytes must be counted before the final closeAfterResponse assignment forces true');
  }
  const writableCompletion = oneMethod(eventLoop.lexed, eventLoopPath,
    'com.soklet.internal.microhttp.ConnectionEventLoop.Connection',
    'doOnWritable');
  const closeCompletion = oneConditionalBlock(writableCompletion,
    /\bif\s*\(\s*closeAfterResponse\s*\)\s*\{/gu,
    'the response-completion closeAfterResponse branch must remain unique');
  if ([...closeCompletion.matchAll(/\bfailSafeClose\s*\(\s*\)\s*;/gu)]
    .length !== 1) {
    fail('Transport characterization structural assertion failed: the response-completion path must close when discarded streaming input set closeAfterResponse');
  }
  const persistentCompletion = oneConditionalBlock(writableCompletion,
    /\bif\s*\(\s*!\s*closeAfterResponse\s*\)\s*\{/gu,
    'the response-completion persistent branch must remain unique');
  if (!/^\s*parseBufferedRequestAfterResponse\s*\(\s*\)\s*;\s*$/u
    .test(persistentCompletion)) {
    fail('Transport characterization structural assertion failed: the response-completion path may parse buffered input only when closeAfterResponse is false');
  }

  const withPort = oneMethod(server.lexed, serverPath, 'com.soklet.McpServer', 'withPort');
  requireStructural(withPort,
    /return\s+new\s+Builder\s*\(\s*requirePort\s*\(\s*requireNonNull\s*\(\s*port\s*\)\s*\)\s*\)\s*;/u,
    'McpServer.withPort must prime its dedicated builder with the validated port');
  requireStructural(start, /candidateEventLoop\s*=\s*new\s+EventLoop\s*\(\s*options\s*,\s*NoopLogger\s*\.\s*instance\s*\(\s*\)\s*,\s*handler/u, 'MCP start path must construct its Microhttp EventLoop');
  const httpVersionReferences = [...process.bodyCode.matchAll(
    /\brequest\s*\.\s*version\s*\(\s*\)/gu)];
  const httpVersionStructures = [...process.bodyCode.matchAll(
    /if\s*\(\s*!\s+\.\s*equals\s*\(\s*request\s*\.\s*version\s*\(\s*\)\s*\)\s*\)\s*return\s+emptyResponse\s*\(\s*505\s*,\s*,\s*List\s*\.\s*of\s*\(\s*\)\s*\)\s*;/gu)];
  const httpVersionLiterals = [...process.bodyCommentFree.matchAll(
    /if\s*\(\s*!\s*"HTTP\/1\.1"\s*\.\s*equals\s*\(\s*request\s*\.\s*version\s*\(\s*\)\s*\)\s*\)\s*return\s+emptyResponse\s*\(\s*505\s*,\s*"HTTP Version Not Supported"\s*,\s*List\s*\.\s*of\s*\(\s*\)\s*\)\s*;/gu)];
  if (httpVersionReferences.length !== 1
    || httpVersionStructures.length !== 1
    || httpVersionLiterals.length !== 1
    || httpVersionStructures[0].index !== httpVersionLiterals[0].index) {
    fail('Transport characterization live-literal assertion failed: MCP request processing must have exactly one HTTP-version read and use it to return the exact HTTP/1.1-only 505 response');
  }

  const runtimeType = namedTypes(runtime.lexed, runtimePath).find((type) => type.owner === 'com.soklet.internal.mcp.protocol.McpHttpServerRuntime');
  const fieldNames = directFieldNames(runtime.lexed, runtimePath, runtimeType);
  if (!fieldNames.includes('FORBIDDEN_LEGACY_MCP_POLICY_HEADERS')) fail('Transport characterization structural assertion failed: legacy MCP policy-header field is missing');
  const runtimeCommentFree = runtime.lexed.commentFree.slice(runtimeType.bodyStart, runtimeType.bodyEnd);
  if (!/FORBIDDEN_LEGACY_MCP_POLICY_HEADERS\s*=\s*Set\s*\.\s*of\s*\(\s*"mcp-session-id"\s*,\s*"last-event-id"\s*\)/u.test(runtimeCommentFree)) fail('Transport characterization live-literal assertion failed: legacy session/replay policy headers must remain forbidden');
  const validatedPolicyHeaders = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime',
    'validatedPolicyHeaders');
  requireStructural(validatedPolicyHeaders,
    /FORBIDDEN_LEGACY_MCP_POLICY_HEADERS\s*\.\s*contains\s*\(\s*lowerName\s*\)/u,
    'validated admission-policy headers must reject the legacy MCP session/replay names');
  const legacyRevision = oneMethod(legacyHttp.lexed, legacyHttpPath,
    'com.soklet.internal.mcp.protocol.McpLegacyHttpWire', 'isLegacyRevision');
  if (!/^\s*return\s+"2025-06-18"\s*\.\s*equals\s*\(\s*requireNonNull\s*\(\s*revision\s*\)\s*\)\s*\|\|\s*"2025-11-25"\s*\.\s*equals\s*\(\s*revision\s*\)\s*;\s*$/u
    .test(legacyRevision.bodyCommentFree))
    fail('Transport characterization live-literal assertion failed: legacy session eligibility must remain exactly June and November 2025');
  const requireLegacy = oneMethod(sessionStore.lexed, sessionStorePath,
    'com.soklet.internal.mcp.protocol.McpLegacySessionStore', 'requireLegacy');
  requireStructural(requireLegacy,
    /if\s*\(\s*!\s*McpLegacyHttpWire\s*\.\s*isLegacyRevision\s*\(\s*requireNonNull\s*\(\s*revision\s*\)\s*\)\s*\)\s*throw\s+new\s+IllegalArgumentException/u,
    'the bounded store must reject modern and unsupported session revisions');
  for (const methodName of ['publish', 'acquire']) {
    const storeEntry = oneMethodMatching(sessionStore.lexed, sessionStorePath,
      'com.soklet.internal.mcp.protocol.McpLegacySessionStore', methodName,
      (candidate) => /\blong\s+retainedRequestEvidenceBytes\b/u.test(candidate.signatureCode),
      `bounded session ${methodName}`);
    requireStructural(storeEntry, /\brequireLegacy\s*\(\s*revision\s*\)\s*;/u,
      `every bounded store ${methodName} must validate its exact legacy revision`);
  }
  requireStructural(process,
    /boolean\s+sessionEnabled\s*=\s*legacy\s*&&\s*sessionsEnabled\s*\(\s*endpointRuntime\s*\.\s*path\s*\(\s*\)\s*,\s*selectedRevision\s*\)\s*;/u,
    'modern requests must never enter the opted-in legacy session path');
  const processNotification = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'processNotification');
  requireStructural(processNotification,
    /boolean\s+sessionEnabled\s*=\s*wireEra\s*==\s*McpLegacyHttpWire\s*\.\s*Era\s*\.\s*LEGACY\s*&&\s*sessionsEnabled\s*\(\s*endpointRuntime\s*\.\s*path\s*\(\s*\)\s*,\s*protocolVersion\s*\)\s*;/u,
    'modern notifications must never enter the opted-in legacy session path');
  const configureSessions = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'configureLegacySessions');
  requireStructural(configureSessions,
    /!\s*entry\s*\.\s*getValue\s*\(\s*\)\s*\.\s*stream\s*\(\s*\)\s*\.\s*allMatch\s*\(\s*McpLegacyHttpWire\s*::\s*isLegacyRevision\s*\)/u,
    'session configuration must reject every revision outside the exact 2025 adapter');
  const sessionsEnabled = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'sessionsEnabled');
  requireStructural(sessionsEnabled,
    /^\s*return\s+legacySessionRevisions\s*\.\s*getOrDefault\s*\(\s*path\s*,\s*Set\s*\.\s*of\s*\(\s*\)\s*\)\s*\.\s*contains\s*\(\s*revision\s*\)\s*;\s*$/u,
    'sessions require the exact opted-in path and revision membership');
  const legacyHttpMethods = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'legacyHttpMethods');
  requireStructural(legacyHttpMethods,
    /if\s*\(\s*legacyTransportAdmission\s*==\s*null\s*\)\s*return\s+Set\s*\.\s*copyOf\s*\(\s*methods\s*\)\s*;/u,
    'legacy GET/DELETE facilities require the explicit HTTP admission controller');
  requireStructural(legacyHttpMethods,
    /sessionsEnabled\s*\(\s*endpoint\s*\.\s*path\s*\(\s*\)\s*,\s*revision\s*\)\s*\?\s*Set\s*\.\s*of\s*\(\s*revision\s*\)\s*:\s*Set\s*\.\s*of\s*\(\s*\)/u,
    'actual GET/DELETE facilities require the selected path and revision session membership');
  requireStructural(legacyHttpMethods,
    /if\s*\(\s*!\s*revisions\s*\.\s*isEmpty\s*\(\s*\)\s*\)\s*methods\s*\.\s*add\s*\(\s*HttpMethod\s*\.\s*DELETE\s*\)\s*;/u,
    'DELETE availability requires at least one selected session revision');
  requireStructural(legacyHttpMethods,
    /if\s*\(\s*revisions\s*\.\s*stream\s*\(\s*\)\s*\.\s*anyMatch\s*\(\s*value\s*->\s*!\s*legacyTransportFamilies[\s\S]*?getOrDefault\s*\(\s*value\s*,\s*Set\s*\.\s*of\s*\(\s*\)\s*\)\s*\.\s*isEmpty\s*\(\s*\)\s*\)\s*\)\s*methods\s*\.\s*add\s*\(\s*HttpMethod\s*\.\s*GET\s*\)\s*;/u,
    'GET availability requires nonempty effective selected-revision notification families');
  const legacySessionHttp = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'processLegacySessionHttp');
  requireStructural(legacySessionHttp,
    /if\s*\(\s*versions\s*\.\s*size\s*\(\s*\)\s*!=\s*1\s*\|\|\s*versions\s*\.\s*get\s*\(\s*0\s*\)\s*\.\s*isBlank\s*\(\s*\)\s*\)/u,
    'GET/DELETE require one explicit nonblank protocol revision');
  requireStructural(legacySessionHttp,
    /if\s*\(\s*!\s*legacyHttpMethods\s*\(\s*endpoint\s*,\s*revision\s*\)\s*\.\s*contains\s*\(\s*method\s*\)\s*\)\s*return\s+methodNotAllowed\s*\(\s*headers\s*,\s*legacyHttpMethods\s*\(\s*endpoint\s*,\s*revision\s*\)\s*\)\s*;/u,
    'actual GET/DELETE dispatch and Allow must use the exact selected revision');
  const preflightHeaders = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime', 'mcpPreflightRequestHeaders');
  if (!/if\s*\(\s*legacyHttpMethods\s*\(\s*endpointRuntime\s*,\s*null\s*\)\s*\.\s*contains\s*\(\s*HttpMethod\s*\.\s*GET\s*\)\s*\)\s*headers\s*\.\s*add\s*\(\s*"Last-Event-ID"\s*\)\s*;/u
    .test(preflightHeaders.bodyCommentFree))
    fail('Transport characterization live-literal assertion failed: Last-Event-ID preflight input requires a configured legacy GET facility');
  if ([...runtimeCommentFree.matchAll(/"last-event-id"/giu)].length !== 2)
    fail('Transport characterization live-literal assertion failed: Last-Event-ID may occur only in the policy-output denylist and ignored GET preflight input');
  const initializationResponse = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl', 'withInitializationResponse');
  requireStructural(initializationResponse,
    /if\s*\(\s*initialization\s*==\s*null\s*\)\s*return\s+response\s*;/u,
    'a session-disabled initialization must publish no session ID');
  requireStructural(initializationResponse,
    /headers\s*\.\s*add\s*\(\s*new\s+Header\s*\(\s*MCP_SESSION_ID\s*,\s*initialization\s*\.\s*sessionId\s*\(\s*\)\s*\)\s*\)\s*;/u,
    'only the stored framework initialization handle may supply the response session ID');
  if ([...runtime.lexed.code.matchAll(/new\s+Header\s*\(\s*MCP_SESSION_ID\b/gu)].length !== 1)
    fail('Transport characterization structural assertion failed: session ID publication must have exactly one framework-owned response-header construction');
  const newResponseStream = oneMethod(runtime.lexed, runtimePath,
    'com.soklet.internal.mcp.protocol.McpHttpServerRuntime.RequestControl',
    'newResponseStream');
  requireStructural(newResponseStream,
    /return\s+new\s+McpRequestSseStream\s*\(\s*transportConfiguration\s*\.\s*streamQueueCapacity\s*\(\s*\)\s*,\s*jsonLimits\s*,\s*envelopeCodec\s*,\s*jsonCodec\s*,\s*protocolProfile\s*\(\s*\)\s*,\s*applicationClock\s*,\s*new\s+McpOutboundChannel\s*\.\s*Listener\s*\(\s*\)\s*\{/u,
    'the production response-stream factory must retain the configured bounded outbound-channel constructor');
  requireStructural(newResponseStream,
    /return\s+new\s+McpRequestSseStream\s*\(\s*envelopeCodec\s*,\s*jsonCodec\s*,\s*protocolProfile\s*\(\s*\)\s*,\s*simulation\s*\.\s*openChannel\s*\(\s*listener\s*\)\s*\)\s*;/u,
    'the simulation response-stream factory must retain the selected protocol profile');
  verifyStateStorageDeclarations(sourceFiles);
}

function readScanFiles(root) {
  return discoverScanFiles(root).map((file) => {
    const source = readUtf8Regular(safeProjectEntry(root, file,
      `transport scan source ${file}`), `transport scan source ${file}`);
    return { file, lexed: lexJava(source, file), source };
  });
}
export function deriveTransportBaselineAtRoot(root) {
  const resolvedRoot = resolve(root);
  requireCandidateRoot(resolvedRoot);
  const sourceFiles = readScanFiles(resolvedRoot);
  verifyCharacterizationSources(resolvedRoot, sourceFiles);
  const directMicrohttpDependencies = dependencyRows(sourceFiles, 'microhttp');
  const directSocketEventLoopDependencies = dependencyRows(sourceFiles, 'network');
  return {
    characterizations: deepClone(CHARACTERIZATIONS),
    derivation: deepClone(DERIVATION),
    directMicrohttpDependencies,
    directSocketEventLoopDependencies,
    formatVersion: 2,
    summary: summaryForDependencies(directMicrohttpDependencies, directSocketEventLoopDependencies),
  };
}
function same(left, right) { return JSON.stringify(left) === JSON.stringify(right); }
function validScanFile(file) {
  return file.startsWith(`${INTERNAL_SCAN_DIRECTORY}/`) && file.endsWith('.java')
    || file.startsWith(`${PUBLIC_SCAN_DIRECTORY}/`)
      && !file.slice(PUBLIC_SCAN_DIRECTORY.length + 1).includes('/')
      && (PUBLIC_SCAN_FILE.test(file.slice(PUBLIC_SCAN_DIRECTORY.length + 1)) || ADDITIONAL_COMPOSITION_OWNER_FILES.includes(file));
}
function validateDependencyRows(rows, family, predicate) {
  if (!Array.isArray(rows)) fail(`${family} dependencies must be an array.`);
  let previous = null;
  for (const [index, row] of rows.entries()) {
    const label = `${family} dependency row ${index}`;
    exactFields(row, ['file', 'type'], label);
    if (typeof row.file !== 'string' || !validScanFile(row.file)) fail(`${label} has a file outside the approved MCP production scope.`);
    if (typeof row.type !== 'string' || !JAVA_TYPE.test(row.type) || !predicate(row.type)) fail(`${label} has a type outside its dependency family.`);
    const fingerprint = dependencyFingerprint(row);
    if (previous !== null && asciiCompare(previous, fingerprint) >= 0) fail(`${family} dependency rows must be unique and strictly ASCII-sorted by file and type.`);
    previous = fingerprint;
  }
}
export function validateTransportBaseline(baseline) {
  exactFields(baseline, ['characterizations', 'derivation', 'directMicrohttpDependencies', 'directSocketEventLoopDependencies', 'formatVersion', 'summary'], 'Transport dependency baseline');
  if (baseline.formatVersion !== 2) fail('Transport dependency baseline formatVersion must be 2.');
  if (!same(baseline.derivation, DERIVATION)) fail('Transport dependency derivation rules changed.');
  validateDependencyRows(baseline.directMicrohttpDependencies, 'Microhttp', (type) => type.startsWith(MICROHTTP_PACKAGE_PREFIX));
  validateDependencyRows(baseline.directSocketEventLoopDependencies, 'Socket/event-loop', networkEventLoopType);
  exactFields(baseline.summary, ['directMicrohttp', 'directSocketEventLoop'], 'Transport dependency summary');
  for (const [name, value] of Object.entries(baseline.summary)) {
    exactFields(value, ['fileCount', 'pairCount', 'typeCount'], `Transport dependency summary ${name}`);
    for (const field of ['fileCount', 'pairCount', 'typeCount']) if (!Number.isSafeInteger(value[field]) || value[field] < 0) fail(`Transport dependency summary ${name}.${field} must be a nonnegative integer.`);
  }
  const expectedSummary = summaryForDependencies(baseline.directMicrohttpDependencies, baseline.directSocketEventLoopDependencies);
  if (!same(baseline.summary, expectedSummary)) fail('Transport dependency summary is not derived from the inventoried pairs.');
  if (!same(baseline.characterizations, CHARACTERIZATIONS)) fail('Transport characterizations must contain the five exact reviewed rows in order.');
  return baseline;
}
function dependencyDifference(expected, actual) {
  const expectedSet = new Set(expected.map(dependencyFingerprint));
  const actualSet = new Set(actual.map(dependencyFingerprint));
  return {
    extra: actual.filter((row) => !expectedSet.has(dependencyFingerprint(row))).map(dependencyFingerprint),
    missing: expected.filter((row) => !actualSet.has(dependencyFingerprint(row))).map(dependencyFingerprint),
  };
}
function verifyDependencyFamily(label, inventoried, derived) {
  const difference = dependencyDifference(derived, inventoried);
  if (difference.missing.length > 0 || difference.extra.length > 0) {
    const display = (values) => values.map((value) => value.replace('\u0000', ' | ')).join(', ') || '<none>';
    fail(`${label} dependency baseline differs from source; missing=[${display(difference.missing)}], extra=[${display(difference.extra)}].`);
  }
}
function parseJson(text, label) {
  try { return JSON.parse(text); } catch (error) { fail(`${label} is not valid JSON: ${error.message}`); }
}
export function verifyTransportDependenciesAtRoot(root) {
  const resolvedRoot = resolve(root);
  requireCandidateRoot(resolvedRoot);
  const baselineText = readUtf8Regular(safeProjectEntry(resolvedRoot,
    BASELINE_PATH, 'transport dependency baseline'),
  'transport dependency baseline');
  const baseline = validateTransportBaseline(parseJson(baselineText, 'Transport dependency baseline'));
  if (baselineText !== canonicalJson(baseline)) fail('Transport dependency baseline is not canonical two-space JSON with one trailing LF.');
  const derived = deriveTransportBaselineAtRoot(resolvedRoot);
  verifyDependencyFamily('Microhttp', baseline.directMicrohttpDependencies, derived.directMicrohttpDependencies);
  verifyDependencyFamily('Socket/event-loop', baseline.directSocketEventLoopDependencies, derived.directSocketEventLoopDependencies);
  if (!same(baseline, derived)) fail('Transport dependency baseline differs from its deterministic source derivation.');
  return { ...derived.summary, characterizationCount: derived.characterizations.length, scannedFileCount: discoverScanFiles(resolvedRoot).length };
}

const modulePath = fileURLToPath(import.meta.url);
if (process.argv[1] !== undefined && resolve(process.argv[1]) === modulePath) {
  if (process.argv.length !== 2) {
    process.stderr.write('Usage: node scripts/verify-mcp-transport-dependencies.mjs\n');
    process.exitCode = 64;
  } else {
    try {
      const result = verifyTransportDependenciesAtRoot(resolve(dirname(modulePath), '..'));
      process.stdout.write(`Verified ${result.directMicrohttp.pairCount} direct Microhttp dependency pairs across ${result.directMicrohttp.fileCount} files and ${result.directMicrohttp.typeCount} types, ${result.directSocketEventLoop.pairCount} direct socket/event-loop dependency pairs across ${result.directSocketEventLoop.fileCount} files and ${result.directSocketEventLoop.typeCount} types, and ${result.characterizationCount} transport characterizations in sibling-blind candidate mode\n`);
    } catch (error) {
      process.stderr.write(`MCP transport dependency verification failed: ${error.message}\n`);
      process.exitCode = 1;
    }
  }
}
