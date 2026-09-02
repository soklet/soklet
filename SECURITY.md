# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by emailing security@revetware.com.

Include the affected Soklet version, a concise description of the issue, and any reproduction steps or proof-of-concept details that can be shared safely. Please do not open a public GitHub issue for suspected vulnerabilities until we have coordinated disclosure.

You should receive an acknowledgment within 3 business days. We will work with you on a coordinated disclosure timeline appropriate to the severity of the issue.

## Supported Versions

Until 4.0.0 is published, 3.5.1 remains the latest supported release. On the
date 4.0.0 is published, the entire 3.x line reaches end of life and receives
no promised maintenance or security fixes. After publication, only the latest
4.x patch release is supported. Older 4.x patches, snapshots, and unreleased
source builds are unsupported.

| Release line | Status |
| --- | --- |
| Latest published 4.x patch | Supported after 4.0.0 publication |
| 3.5.1 / all 3.x | Supported only until 4.0.0 publication; EOL immediately afterward |
| Snapshots and unreleased source | Unsupported development artifacts |

See the [migration guide](MIGRATING_TO_4_0.md#supported-release-lines) for the
same user-facing policy.

## Scope

Soklet resolves no external runtime dependencies, but the released artifact
includes credited, repackaged third-party source. Security reports may concern
either Soklet-authored behavior or that embedded code. Reports against the HTTP,
SSE, and MCP transports—including request parsing, connection lifecycle, and
resource-limit enforcement—are especially appreciated. See the tracked
[third-party audit](release/THIRD_PARTY_AUDIT.md) and [`NOTICE`](NOTICE).

## Security Boundary and Non-Claims

Soklet supplies bounded parsing, validation, lifecycle, transport, and selected
cryptographic mechanisms inside the documented framework boundary. It does not
claim to secure an application or deployment end to end. In particular:

- Soklet does not implement an OAuth authorization server, access-token/JWT
  verifier, introspection client, dynamic client registration, protected
  resource metadata hosting, consent flow, identity-provider policy, or
  business authorization. See the worked
  [application-owned OAuth pattern](release/MCP_OAUTH_RESOURCE_SERVER.md).
- Lifecycle and transport attestation can validate evidence supplied through
  an honest custom implementation; Soklet cannot detect a custom transport or
  decorator that lies about its identity, delegation, termination, or resource
  ownership.
- MCP Tool Schema Profile 1 is a closed, bounded Java-first subset. It is not
  universal JSON Schema safety, semantic sensitive-data classification,
  protection against prompt injection, or validation of application business
  rules.
- The built-in request-state and trace-correlation cryptography has frozen
  profiles, vectors, and implementation tests. It has not received an
  independent cryptographic audit, formal verification, or certification.
- Host, Origin, header, request, state, cursor, URI, filesystem, and proxy
  protections end at their documented boundary. Network topology, TLS,
  identity systems, key custody, logs, databases, downstream services,
  application handlers, custom code, and data retention remain deployment or
  application responsibilities.
- Conformance suites, simulators, goldens, fuzzing, soak runs, static analysis,
  and compatibility smoke are evidence for their stated cases. They are not a
  penetration test, a proof of absence of vulnerabilities, or protection
  against every scheduler, network, proxy, or hostile-input behavior.

The dated [security-claims audit](release/SECURITY_CLAIMS_AUDIT.md) records the
release wording that was deliberately accepted, rejected, or narrowed.

## MCP Deployment Security

See the [MCP privacy boundary](release/MCP_PRIVACY_BOUNDARY.md) for the exact
division between Soklet-owned redacted diagnostics and built-in metrics,
application callback values, simulator fixtures, and operator retention.

Soklet's MCP 2026-07-28 support runs on a dedicated `McpServer` listener. It is
not mounted on the ordinary HTTP or SSE listener. The MCP listener binds to
`127.0.0.1` by default; a container or remote deployment must opt into a
reachable bind host and provide appropriate network controls. Soklet does not
terminate TLS, so expose a non-loopback listener only behind suitable TLS
termination and access controls.

Host and Origin checks are independent. Soklet validates `Host`, including its
effective port, and `McpServer.Builder.allowedHosts(...)` adds deployment-
specific hostnames or IP literals. A request without `Origin` is allowed by
default, unless `McpAbsentOriginPolicy.REQUIRE_ORIGIN` is configured. A request
with `Origin` is rejected unless the shared `CorsAuthorizer` explicitly
authorizes it; omitting an authorizer is reject-all for present origins. Do not
treat browser CORS response headers as a substitute for authentication or
network isolation.

Validation precedence is a security boundary. Transport limits and endpoint routing run first, followed by Host, Origin/CORS, POST/media negotiation,
strict JSON, and JSON-RPC envelope classification.
Requests then traverse mirrored-header form and method/name agreement; a read-only nested body-version probe and exact registry profile selection; then selected-profile required
metadata/extensions, universal-spine validation, and post-map header/body version agreement. Cheap structure and required capabilities precede admission, request/tool limiting,
bounded dispatch, interception, full input validation, handler execution, output processing in the exact order of preliminary result-shape recognition, applicable sanitization,
and remaining result/output-schema validation, and then writing. Notifications instead validate selector cardinality/form and registry membership before any selected-profile
present metadata, admission, and the optional request limiter after the common transport prefix, then terminate with an empty response; identifiable
`notifications/cancelled` skips parameter/present-metadata validation only.
Compound failures never move application callbacks ahead of their documented stage.

A readable post-JSON `initialize` method receives a modern-only rejection diagnostic whose supported-version list names only `2026-07-28`; a selector
that has passed cardinality/plain-string validation and is absent from the immutable production registry is the only additive trigger for other methods.
This does not implement initialization or a session. Pre-JSON failures, unparseable JSON, unreadable methods,
and row-1 failures for other methods receive no selector-derived diagnostic.
Rejected header/metadata values or secret canaries are not reflected beyond the defined request-ID and unsupported-version `requested` fields.
Every MCP HTTP response family—including early parser errors, fixed empty/JSON/preflight responses, and SSE—carries exactly one `Cache-Control: no-store`.
An application-authored attempt to replace that header fails closed.

Every MCP server requires an explicit `McpAdmissionController`. Production
applications should authenticate and authorize there and return stable,
bounded rate-limit and authorization partition keys in the accepted
`McpAdmissionIdentity`. `McpAdmissionController.acceptAllInstance()` is an
explicit anonymous policy, not a production authentication mechanism.
Admission and rate-limit decisions are created only through the named sealed-
root factories (`accepted(...)`, `rejected(...)`, `allowed()`, and
`denied(...)`). Their nested final variants have private constructors and
remain public only for typed pattern matching; inspect them through
`Accepted.getIdentity()`, `Rejected.getRejection()`, and
`Denied.getRetryAfter()` rather than record-style component accessors.
Client information, client capabilities, request `_meta`, and advertised
server information are self-reported or informational metadata. Never use
them as authenticated identity or as an authorization or rate-limit partition
key.

Resource-subscription publishers emit coarse identity-free broadcasts.
Soklet matches those events against each accepted URI filter, but the stored
authorization partition only scopes registration, quota accounting, and stream isolation; it is not an event target or semantic URI-authorization check.
Admission receives the validated, deduplicated resource-subscription URIs via
`McpAdmissionContext.getRequestedResourceSubscriptionUris()`; it need not
reparse the bounded request body.
Authorize confidential or capability-bearing subscription URIs during admission, and do not treat an unguessable URI as a secrecy boundary.
A rejected admission activates no subscription even though the generation's shared publisher listener may already exist.
Accept-all anonymous callers on one endpoint share one empty authorization/quota partition,
so one caller can exhaust their common bucket.

Soklet validates response-header safety and transports application-owned
authentication decisions and challenges, including Bearer challenges with an
absolute `resource_metadata` URI and operation scopes. It treats the challenge
syntax as opaque and does not publish OAuth protected-resource metadata or
choose an authorization server. A deployment claiming MCP Authorization owns
the referenced metadata, authorization-server selection, scope semantics, and
RFC compliance, including RFC 9728 protected-resource metadata with at least
one authorization server; it must not require `offline_access` as a protected-
resource scope. Transporting a challenge does not by itself make core Soklet or
the deployment conformant with MCP Authorization.

`Forwarded` and `X-Forwarded-For` are also ordinary untrusted request headers;
they never alter `McpAdmissionIdentity` by themselves. If an application
deliberately derives an anonymous rate-limit partition from client IP, do so in
the admission controller with `EffectiveClientIpResolver` and an explicit
`EffectiveOriginResolver.TrustPolicy`. Use `TRUST_NONE` for direct traffic, or
`TRUST_PROXY_ALLOWLIST` with an exact allowlist covering every possible
physical socket peer and every trusted proxy-hop address expected in the
forwarding chain, never end-client addresses. When the physical peer is not
trusted, the resolver ignores the forwarding headers and uses the raw socket
peer when available. The trusted proxy or network edge must strip or overwrite
both `Forwarded` and `X-Forwarded-For`; usable `Forwarded: for=` values take
precedence. Do not use `TRUST_ALL` on a listener reachable by untrusted clients,
do not treat the allowlist as a replacement for network controls that prevent
proxy bypass, and never derive a partition from the request-controlled MCP
`clientInfo` returned by `McpAdmissionContext.getClientInfo()`.

Request-wide rate limiting is optional. A tool-bearing server must configure a
fallback tool limiter; named endpoint and tool overrides replace that fallback.
The built-in token bucket is bounded but local to one JVM. Multi-instance
deployments that require fleet-wide enforcement should supply their own
thread-safe `McpRateLimiter`, backed by a distributed service, and should fail
closed when that service is unavailable.

The built-in limiter partitions only on the admitted identity. A custom limiter
also receives the raw request through `McpRateLimitContext.getRequest()` and
must not treat its forwarding headers or self-reported MCP metadata as trusted
partition input unless application policy deliberately resolves them under the
same proxy boundary.

Localization contexts and catalog snapshots are node-local; they are not
authentication state or a distributed session. Every request reconstructs its
context from the request's bounded preferences and authenticated application
policy. For a rolling reload, build and validate the complete candidate off the
request path, atomically install it on one node, and only then call that node's
`catalogsChanged()` control; repeat explicitly for every applicable node and
expect temporary cross-node revision drift. If the deployment requires a
fleet-atomic cutover, stage and validate the candidate everywhere before any
node mutates, then use an application-owned coordinator, proxy, or traffic
switch to activate it. A failed candidate must produce neither a swap nor an
invalidation. After node loss, a client reconnects and repeats its credentials,
preferences, and any portable protected state; Soklet recovers no localization
session from the lost process.

`@McpHeader` deliberately requires a registered `Mcp-Param-*` request-header
value to agree with the corresponding property already parsed from the JSON
tool arguments. It never supplies an absent or null argument from a header.
Treat both values as untrusted application input and avoid placing secrets in
mirrored headers unless every intermediary and application log is configured
accordingly. Unregistered mirrored headers are ignored and never trusted by
default; strict request rejection is available through
`McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS`. Optional name-bearing
diagnostics are disabled by default and can still disclose received header
names to application-owned logging and retention systems, although Soklet
never includes their values.

Pagination cursors are opaque, application-owned strings. Soklet enforces type
and UTF-8 byte bounds but does not mint, decode, sign, encrypt, authorize, or
make cursors portable between instances. A custom resource-list handler owns
cursor integrity, expiry, authorization binding, backing snapshot semantics,
catalog revision and page-position binding, and fleet portability. Tampered,
expired, cross-principal, missing-snapshot, wrong-revision, and malformed
cursors should collapse to one neutral error without diagnostic data. Do not
put confidential data in a cursor unless the application protects it
appropriately.

Tools, prompt gets, and resource reads may now perform multi-round-trip
`input_required` exchanges. The operation must declare every client request it
may emit. Required capabilities are checked before admission; conditional
capabilities are checked only when emitted, but still before output parameters,
metadata, request state, or a custom protector is processed. Client
`inputResponses` remain untrusted input and must be authorized and validated in
the handler even after Soklet validates their protocol shape.

In particular, applications must correlate each response key with the request
they emitted, distinguish missing, `accept`, `decline`, and `cancel` outcomes,
and validate accepted form content against the exact requested schema and
business policy before a side effect. Form elicitation must reject semantic
secret fields such as passwords, keys, tokens, and payment credentials rather
than assuming structural schema validation can classify them. URL-mode flows
should use a server-owned HTTPS destination with an opaque state handle bound
to the verified initiating user; do not put identity, credentials, userinfo, a
pre-authenticated bearer capability, query data, or fragments in the emitted
URL. Applications also own sensitive-data classification and finite iteration
limits for sampling, plus `toRealPath()`-based containment, symlink policy, and
authorization for returned roots. The public-API-only
[MCP input-security patterns](src/test/java/examples/mcp/McpInputSecurityApplicationPatternsTests.java)
exercise each of these fail-closed boundaries without claiming a universal
semantic classifier or a real downstream authorization deployment. The
[durable-handle and prompt-security patterns](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
add an application-owned durable repository boundary, exact admitted-context
binding, prompt business allowlisting, authorization-before-resource-access,
and neutral failures. The
[resource and cursor-security patterns](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java)
add canonical filesystem containment, delivery-intent URI allowlists, and
signed snapshot/revision/expiry-bound cursors. Their in-memory repositories and
fixed canaries are test doubles and deployment examples, not Soklet services
or universal security classifiers.
The
[localized cursor fleet pattern](src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java)
adds independently configured nodes with copied application key rings and
retained snapshots. It proves exact cursor-byte preservation through provider
preselection and handler authentication, authorization binding as HMAC
associated data, locale/catalog/localization revision checks, exact expiry,
and one no-data `-32602` result for every exercised invalid classification.
The separately populated repositories model application replication; Soklet
still supplies no distributed cursor store, key distribution, replication,
or routing affinity.

Request-state protection has two distinct trust boundaries:

- `APPLICATION_PROTECTED` is exact opaque-string pass-through. Soklet enforces
  nonempty/type and a 65,536-byte UTF-8 limit, but supplies no confidentiality,
  integrity, expiry, authorization binding, replay protection, or fleet
  portability. The application must provide every one of those properties it
  needs; do not place secrets in the value without application encryption.
  For durable continuation, store the state in an application-owned durable
  repository, expose only an unguessable handle, bind it to the admitted
  principal and authorization context, rotate it atomically as required, and
  require the current handle on every retry and new connection.
- `FRAMEWORK_PROTECTED` lets the handler supply JSON while Soklet owns canonical
  serialization, context binding, protection, lifetime, rounds, and immediate
  prior-request-ID freshness. A server with any such operation fails to build
  or start without `McpProtectionConfig`.

Production deployments should use
`McpProtectionConfig.withKeyRing(...)` with operator-generated, purpose-specific
key material containing at least 256 bits of cryptographic entropy. Soklet's
built-in versioned envelope uses authenticated encryption, copies the initial
ring into server-owned state, redacts key material from public surfaces, and
supports live stage/activate/remove rotation through `McpProtectionControl`.
For a fleet, stage the identical new key everywhere, compare secret-free
snapshots, activate it everywhere, wait at least the configured state lifetime
and for outstanding sealing reservations, then remove the former key.
The exact [built-in cryptographic profile](release/MCP_REQUEST_STATE_SECURITY_PROFILE.md)
and [production rotation runbook](release/MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md)
define the frozen labels, envelope, binding, vectors, publication boundary,
drain check, rollback, and emergency-revocation procedure. In particular, a
ring fingerprint proves complete configuration equality but does not prove
that sealing reservations have drained; removal is the authoritative drain
check and must be retried after `McpKeyInUseException`.

`withDevelopmentEphemeralProtection()` is an explicit development convenience.
Its state is process-local and becomes unreadable after restart or on another
instance; the startup diagnostic is intentional. Never use it when a client
may retry through a different process. A thread-safe
`McpRequestStateProtector` is the alternative for application-owned or
distributed protection. It must authenticate the exact associated-data bytes
from `McpRequestStateProtectionContext`, return fresh plaintext arrays, avoid
retaining call-confined plaintext, and collapse all invalid/tampered/context-
mismatched input into `INVALID_STATE`. Report only temporary provider outages
as `PROTECTOR_UNAVAILABLE`; do not expose backend diagnostics in the checked
exception.

Framework state is bound to endpoint path, protocol version, JSON-RPC method,
the admitted authorization partition, and stable validated parameters. Retry-
only fields and transient progress/trace/baggage metadata are excluded from the
parameter digest; application operation arguments and identity partition are
not. Wire shape and size are checked before capability/admission side effects,
but structurally valid state is opened only after admission, preventing an
unauthenticated cryptographic validity oracle. Invalid, tampered, expired, or
wrong-bound state is a sanitized HTTP 400 / JSON-RPC `-32602`; temporary
protection unavailability is HTTP 503 / `-32603`. Invalid-state reports and
malformed, noncanonical, empty, or oversized custom-open plaintext collapse to
the same 400 / `-32602` response. Null or unexpected provider behavior and
invalid sealing/server output fail as HTTP 500 / `-32603`.

The first framework state starts the configured lifetime and round count.
Re-emission preserves that original expiry, increments the round, and records
the emitting request ID; the next retry must use a different ID. This is not a
single-use replay database. Workflows that require one-time approval or
consumption must store and enforce that fact in application infrastructure.
Input-required results have no protocol cache hints, and completed resource
retries are forced to private, zero-TTL cache policy; the HTTP transport remains
`Cache-Control: no-store`.

Progress reporting, cooperative cancelation, localization, and resource-
subscription delivery are implemented. Every selected MCP application handler
receives one framework token whose cancellation category is a fixed
`StreamTerminationReason`; the framework supplies no underlying cause through
`CancelationToken.getCancelationCause()` or
`StreamingResponseCanceledException`. An application may retain that fixed
category under its own policy, but must not substitute attacker-controlled
free-form text or make a cancellation detail a metric dimension. Incoming HTTP
`notifications/cancelled` is a compatibility no-op after admission and request
limiting; disconnect, deadline, shutdown, and response-stream failure drive
cooperative cancellation on this transport. The exact
`McpProgressAndCancelationRuntimeTests#every_cancelation_category_is_bounded_observable_and_carries_no_framework_cause`
gate iterates every non-`COMPLETED` category and proves the fixed reason, empty
cause, and bounded exception message.

Trace correlation is default-off. With a configured trace-correlation key,
Soklet attempts one bounded `MCP_TRACE_CORRELATION` log record at the admitted
request's exactly-once finish authority; a separate
`logRawValidatedTraceIds(true)` opt-in may add only the validated lowercase MCP
trace ID. The event never carries the full `traceparent`, parent/span ID, trace
flags, `tracestate`, baggage, request, throwable, method, or marshaled response,
and trace values never become built-in metric dimensions. The pseudonymous
token and any opted-in raw ID are still sensitive, high-cardinality correlation
data. Restrict log access and retention, and do not treat validation or
pseudonymization as authentication or anonymization.

All 65 Phase 6 owners are now frozen and the provisional inventory is empty.
Twenty-one bounded Phase 6 verticals are also implemented: shutdown
observation, handler-capacity metrics, handler diagnostics, live
stream/subscription diagnostics, protection/trace diagnostics, serialized
semantic-event delivery, bounded pre-admission metrics, connection/transport
metric delivery, admitted-request trace-token capture, and the first default
transport-boundary, server-start, request-boundary, admitted-request,
request-stream, subscription lifecycle, progress/cancelation, keep-alive, and
protocol-error/unknown-header aggregate families, followed by the downstream
OpenTelemetry metric mapping, modern admitted-request spans, and bounded
off-network MCP simulation. Shutdown
metrics have only the fixed `ShutdownComponentDisposition`-derived labels
`not_started`/`graceful_termination`/`forced_termination`/`unexpected_termination`/
`residual_activity`/`termination_unknown`. The exact handler-capacity families—
`soklet_mcp_handler_executions_active`, `soklet_mcp_handler_queue_depth`, and
`soklet_mcp_handler_capacity_rejections_total`—are label-free. They contain
only server-wide counts and no endpoint, method, request, principal, URI,
header, trace, baggage, state, or application-controlled value.

The public aggregate represents these nonnegative counts with boxed `Long`
snapshot getters and builder methods. Reset preserves the live active-handler
and queue-depth gauges while clearing cumulative queue-full rejections. A
residual handler remains visible as active after bounded shutdown until it
actually exits; queued deadline, disconnect, cancelation, and shutdown removal
are dequeues, not capacity rejections. These semantics prevent reset or late
exit from manufacturing a negative gauge. Promotion, queued deadline, and
disconnect elect one queue removal: a still-queued writable deadline returns
the fixed overload response, while disconnect writes nothing even if it makes
an internally reserved deadline response unwritable before handoff.

The tenth vertical resolved the full `AMB-003` aggregate contract and added
three provisional `McpMetricsSnapshot` getter/builder pairs for accepted
connections, capacity-rejected connections, and a sparse fixed-reason
transport-failure map. The connection counters render without labels,
including configured zeros. MCP failures reuse
`soklet_transport_failures_total` with only `server_type="MCP"` and one of the
18 fixed `TransportFailureReason` names; they do not create a second family.
At that checkpoint the transport trio brought the aggregate-render count to seven.
The immutable map is defensive and enum-ordered. Reset clears these cumulative
values without mutating retained snapshots, and a rejected-all sample filter
cannot leave orphaned HELP/TYPE metadata.

This aggregation introduces no remote address, network identity, request,
throwable, header identity/value, trace ID, token, key material, tracestate,
baggage, or application-controlled label. `ConnectionAccepted` and
`ConnectionRejected` remain fieldless; `TransportFailure` contributes only its
fixed reason. It is bounded operational telemetry, not authentication,
authorization, anonymization, or comprehensive privacy evidence.
The focused gates are
`McpTransportMetricsAggregationTests#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
`#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
`#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
`#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The eleventh bounded Phase 6 production vertical adds boxed, nonnegative
`getServerStarts()`/`serverStarts(Long)` to the provisional snapshot and
aggregates the existing fieldless `ServerStarted` lifecycle event. Its
authority remains one event per successfully started listener generation:
failed staged starts and already-started no-ops do not increment; rollback
retains its successful start before its stop; restart counts the new
generation. Configured collectors render label-free
`soklet_mcp_server_starts_total` at zero. Direct `ServerStarted` or
`ServerStopped` activates the lifecycle subset, including a zero start sample
for a stop-only fresh collector. Filtering the sample removes its HELP/TYPE
metadata, reset clears the count while preserving zero-family visibility, and
retained snapshots remain immutable. Start and shutdown counts are not a
conservation or complement pair at arbitrary snapshots because a running
generation has not stopped.

The fieldless event and label-free aggregate retain no request, network
identity, endpoint, method, outcome, throwable, header, trace ID, token, key,
tracestate, baggage, or application-controlled dimension. Exact tests are
`McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
`#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
and
`#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The twelfth bounded Phase 6 production vertical adds the boxed, nonnegative
`getRequestsAccepted()`/`requestsAccepted(Long)` and
`getRequestsRejected()`/`requestsRejected(Long)` request-boundary scalars. The
provisional snapshot at that checkpoint had ten getters and 11 public builder
methods including `build()`: eight boxed `Long` values and two immutable maps.

Accepted becomes durable only after the bounded protocol processor accepts
`Executor.execute`; rejection or throw discards the provisional accepted
identity. Rejected is exact once for a complete Handler request whose terminal
wins before atomic observation-start reservation. A terminal pre-admission
path may record both, while execute failure may record rejected without a
retained accepted event. They are not complementary or conserved and exclude
early transport/Microhttp failure, post-admission outcome, and handler-capacity
rejection.

Configured collectors and either directly ingested event activate paired,
label-free zero-visible families: `soklet_mcp_requests_accepted_total` with
HELP `Total MCP requests accepted by the bounded protocol processor`, and
`soklet_mcp_requests_rejected_total` with HELP `Total MCP requests rejected
before admitted semantic handling`. Filtering removes rejected family metadata
with its sample. Reset clears both cumulative counts while preserving paired
visibility; OpenMetrics, retained snapshots, and post-quiescence concurrent
ingest preserve the same bounds.

The fieldless events and label-free families retain no request, network
identity, endpoint, method, code, outcome, throwable, header, trace ID, token,
key, tracestate, baggage, or application-controlled dimension. Exact tests are
`McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
`#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
and
`#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
The exact producer authority is additionally covered by
`McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
and
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.

The thirteenth bounded Phase 6 production vertical aggregates the existing
exact admitted-request lifecycle authority. Boxed, nonnegative
`getActiveRequests()` plus immutable `getRequests()` and
`getRequestDurations()` maps and matching builders expand the provisional
snapshot to 13 getters and 14 public builder methods including `build()`: nine
boxed `Long` values and four maps. Their public, thread-safe final key is
created with
`RequestOutcomeKey.fromDimensions(endpointPath, jsonRpcMethod, outcome)` and
rejects null/empty shape but does not validate application-created registry
membership; its dimensions are read with `getEndpointPath()`,
`getJsonRpcMethod()`, and `getOutcome()`. The built-in producer supplies only a
registered endpoint, recognized method or
`<unrecognized>`, and fixed outcome. Count and histogram maps are independently
sparse and do not imply a cross-map invariant.

`RequestStarted` increments the live `soklet_mcp_requests_active` gauge and the
exact terminal `RequestFinished` decrements it while recording
`soklet_mcp_requests_total` and `soklet_mcp_request_duration_nanos`. The latter
two use only `endpoint`, `method`, and lower-snake `outcome`, with the 14 HTTP
latency boundaries from 1 through 15,000 milliseconds plus overflow. There are
no standalone start/finish counters. Configured empty state exposes only gauge
zero; sparse counter/histogram families and their HELP/TYPE metadata remain
absent when empty or fully filtered. Reset preserves the live gauge, clears
completed maps/histograms, and a request crossing reset records its full
original duration. Retained snapshots are immutable and balanced concurrent
ingest is lossless after quiescence.

No request/network identity, raw unrecognized method, error detail, throwable,
header, trace ID, token, key material, tracestate, baggage, or application
telemetry enters these built-in dimensions or rendered values. This does not
constrain custom collectors, generic HTTP metrics callbacks, logs,
application-created events/keys, or application telemetry; promise atomic
cross-field snapshots during mutation; or clamp unmatched manual lifecycle
events. Exact tests are
`McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
`#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
`#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Relevant authority/cardinality tests are
`McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
`#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
`#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.

The fourteenth bounded Phase 6 production vertical aggregates the exact
request-stream lifecycle authority. Boxed, nonnegative
`getActiveRequestStreams()`, immutable `getRequestStreamDurations()`, and
matching builders expand the provisional snapshot to 15 getters and 16 public
builder methods including `build()`: ten boxed `Long` values and five maps.
The public, thread-safe
`RequestStreamTerminationKey(endpointPath, jsonRpcMethod, reason)` rejects
null/empty shape but does not validate application-created registry
membership.

Exact delivered `RequestStreamOpened` increments
`soklet_mcp_request_streams_active` with HELP `Currently active MCP request
streams`; exact terminal `RequestStreamClosed` decrements it and records
`soklet_mcp_request_stream_duration_nanos` with HELP `MCP request-stream
duration in nanoseconds`. Stream-transition order is open before accepted
progress/keepalive observations and the single close before terminal
`RequestFinished`; this is FIFO record/enqueue order, not a universal
cross-thread total order. Samples use only bounded `endpoint`, `method`, and
lower-snake `reason`: `completed`, `client_disconnected`, `request_canceled`,
`deadline_exceeded`, `write_failed`, `backpressure`, `server_stopped`,
`simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`.
The 13 inclusive buckets are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600,
7,200, and 14,400 seconds plus overflow. There are no standalone open/close
counters.

Configured collectors and either direct event activate gauge-zero visibility;
the histogram remains sparse and leaves no orphan HELP/TYPE metadata when
empty or fully filtered. Prometheus/OpenMetrics, reset-crossing full duration,
retained immutability, and balanced post-quiescence concurrent ingest are
covered. Reset preserves the live gauge and clears histogram state.

Built-in keys contain only registered endpoint, recognized method or
`<unrecognized>`, and fixed reason. No request/network identity, error detail,
throwable, header, trace ID/token/key material, tracestate, baggage, or
application telemetry enters these dimensions. This does not constrain custom
collectors, generic HTTP/SSE metrics, logs, application-created events/keys, or
telemetry; promise atomic cross-field or concurrent-reset snapshots; repair
unmatched manual events; equate metrics with diagnostics; expose a
subscription breakdown; promise canonical order; add OpenTelemetry or trace
emission; or prove comprehensive privacy, sustained, simulator,
release-readiness, or Phase 6 freeze. Exact tests are
`McpRequestStreamLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies`,
`#resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority is bounded by
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
and
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`.

The fifteenth bounded Phase 6 production vertical aggregates the exact
subscription lifecycle authority. Boxed, nonnegative
`getActiveSubscriptions()`, immutable `getSubscriptionDurations()`, and
matching builders expand the provisional snapshot to 17 getters and 18 public
builder methods including `build()`: 11 boxed `Long` values and six maps. The
public, thread-safe `SubscriptionTerminationKey(endpointPath, reason)` rejects
null/empty shape but does not validate application-created registry
membership.

Exact delivered `SubscriptionOpened` increments
`soklet_mcp_subscriptions_active` with HELP `Currently active MCP
subscriptions`; exact terminal `SubscriptionClosed` decrements it and records
`soklet_mcp_subscription_duration_nanos` with HELP `MCP subscription duration
in nanoseconds`. Samples use bounded `endpoint` and lower-snake `reason`:
`completed`, `client_disconnected`, `request_canceled`, `deadline_exceeded`,
`write_failed`, `backpressure`, `server_stopped`,
`simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`. The 13 inclusive
buckets are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400
seconds plus overflow. No standalone open/close counters exist.

Produced order is `RequestStreamOpened`, `SubscriptionOpened`, then at
termination `RequestStreamClosed`, `SubscriptionClosed`, and
`RequestFinished`. This is FIFO record/enqueue order, not universal
cross-thread ordering or an atomic relationship between separately delivered
gauges. Configured/direct gauge visibility, sparse no-orphan histogram
metadata, Prometheus/OpenMetrics filtering, reset preserving the live gauge
while clearing histograms, full duration across reset, retained immutability,
and balanced post-quiescence concurrency are covered.

Built-in keys contain only registered endpoint and fixed reason—never method,
resource URI, subscription filter, request/network identity, error detail,
throwable, header, trace ID/token/key material, tracestate, baggage, or
application telemetry. This does not constrain custom collectors, generic
HTTP/SSE metrics, logs, application-created events/keys, or telemetry; promise
cross-field or concurrent-reset atomicity, repair unmatched manual events,
equate aggregates with diagnostics, promise canonical order or conservation
with request-stream gauges, add OpenTelemetry/trace emission, or prove
comprehensive privacy, sustained, simulator, release-readiness, or Phase 6
freeze. Exact tests are
`McpSubscriptionLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableSubscriptionLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersSubscriptionLifecycleFamilies`,
`#resetPreservesActiveSubscriptionsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedSubscriptionLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority is bounded by
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

The sixteenth bounded Phase 6 production vertical aggregates exact delivered
`CancelationSignaled` and `ProgressEmitted` events into independent immutable
`Map<EndpointMethodKey, Long>` values exposed by
`getCancelationsSignaled()`/`getProgressEmitted()` and matching builders. The
public, thread-safe `EndpointMethodKey(endpointPath, jsonRpcMethod)` rejects
null/empty shape but accepts arbitrary nonempty application-created values.
The provisional snapshot now has 19 getters and 20 public builder methods
including `build()`: 11 boxed `Long` values and eight maps.

The exact families are
`soklet_mcp_cancelations_signaled_total{endpoint,method}` with HELP `Total
cooperative MCP request cancelations signaled by endpoint and method`, and
`soklet_mcp_progress_emitted_total{endpoint,method}` with HELP `Total MCP
progress notifications accepted for delivery by endpoint and method`.
Configuration alone emits neither samples nor metadata; either direct event
populates only its own sparse family. Fully rejected filters leave no orphan
HELP/TYPE block, OpenMetrics terminates once, and reset clears both maps.
Defensive copying, explicit application zeros, retained immutability, and
post-quiescence concurrent losslessness do not imply cross-map atomicity.

Runtime-produced labels contain only registered endpoint and bounded method,
never progress token/value/total/message, cancelation reason, request/network
identity, throwable, header, trace ID/token/key material, tracestate, baggage,
or application telemetry. The counters are neither complements nor a
per-request conservation equation. Live authority in
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
proves two accepted progress events, one cooperative-cancelation event,
serialized collector callbacks outside the reporter monitor, and no
post-cancel progress, without asserting universal cross-thread terminal order.
Exact focused tests are
`McpProgressAndCancelationMetricsAggregationTests#snapshotContractUsesSharedImmutableEndpointMethodCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies`,
`#resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata`,
and
`#concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs,
application-created events/keys, or telemetry; prove every live cancelation
cause, canonical ordering, comprehensive privacy, sustained/simulator or
release evidence, OpenTelemetry/trace emission, or Phase 6 freeze.

The seventeenth bounded Phase 6 production vertical aggregates the exact
fieldless `KeepAliveEmitted` event into boxed, nonnegative
`@NonNull Long getKeepAlivesEmitted()` state with matching
`keepAlivesEmitted(Long)`. The provisional snapshot now has 20 getters and 21
public builder methods including `build()`: 12 boxed `Long` values and eight
immutable maps.

The label-free family is `soklet_mcp_keep_alives_emitted_total`, with HELP
`Total MCP keep-alive comments accepted for delivery`. Configured MCP and a
direct event activate it; configured and reset state render zero. Filters see
an empty label map, full rejection suppresses samples and HELP/TYPE metadata,
OpenMetrics emits one terminator, and reset clears the cumulative value while
retaining visibility. Retained boxed snapshots are immutable, and
post-quiescence concurrent direct ingest is lossless.

The fieldless built-in event and scalar retain no request, endpoint, method,
remote identity, duration, termination reason, throwable, header, trace ID,
token, key material, tracestate, baggage, or application label. Live authority
is bounded by
`McpSubscriptionPublicRuntimeTests#keepAliveAcceptanceSharesStreamTransitionWithCloseObservation`
and
`McpSubscriptionRuntimeBoundaryTests#maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents`:
they freeze accepted wire-observation/transition order and the exact-one
boundary in deterministic scenarios, not timer attempts or client/intermediary
receipt. No conservation relationship with subscriptions, streams, or terminal
events is claimed. Exact focused tests are
`McpKeepAliveMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeKeepAliveCount`,
`#defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset`,
and
`#concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs, or
application telemetry; promise universal cross-thread ordering,
delivery/receipt, cross-field or concurrent-reset atomicity,
OpenTelemetry/trace emission, comprehensive privacy, sustained/simulator
evidence, release readiness, or Phase 6 freeze.

The eighteenth bounded Phase 6 production vertical completes core default
aggregation with immutable protocol-error and unknown-mirrored-header maps:
`Map<Integer, Long> getProtocolErrors()` and
`Map<EndpointMethodKey, Long> getUnknownMirroredHeaders()`, plus matching
builder methods. The provisional snapshot now has 22 getters and 23 public
builder methods including `build()`: 12 boxed `Long` values and ten maps. The
three earlier fuzz, dormant-derivation, and metric-dimensionality checkpoints
remain unnumbered.

The sparse families are `soklet_mcp_protocol_errors_total{code}` with HELP
`Total client-visible MCP protocol errors by fixed code` and
`soklet_mcp_unknown_mirrored_headers_total{endpoint,method}` with HELP `Total
unknown MCP mirrored-header occurrences by endpoint and method`. Configuration
alone emits neither family; one direct event activates only its own map. Reset
removes samples and metadata, full filter rejection leaves no orphan HELP/TYPE,
OpenMetrics emits one EOF, and retained snapshots remain immutable.

Framework-produced protocol codes are exactly `-32700`, `-32600`, `-32601`,
`-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998` after
successful client-visible encoding or accepted streamed-terminal reservation.
Failed provisional terminals, application codes, tool-result `isError`, and
empty-notification HTTP errors do not contribute. Pre-admission errors remain
request-free; admitted fixed errors use their exact context only for bounded
delivery/failure attribution. Unknown-header metrics count once per occurrence
under IGNORE and REJECT and contain only registered endpoint and recognized
core method or `<unrecognized>`, never a header name/value or raw unrecognized
method.

The two default maps independently cap retained dimensions at 8,192. Public
builder maps are uncapped value carriers that accept arbitrary non-null Integer
codes and shape-valid nonempty `EndpointMethodKey` values with nonnegative,
including explicit-zero, counts. Protocol maps iterate in natural Integer
order. These public/manual freedoms do not expand framework production. No
built-in dimension contains header identity, request, throwable, payload,
remote identity, trace ID/token/key material, tracestate, baggage, or a generic
application label.

Exact tests are
`McpProtocolAndUnknownHeaderMetricsAggregationTests#snapshotContractUsesImmutableProtocolAndUnknownHeaderCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProtocolAndUnknownHeaderFamilies`,
`#resetClearsSparseProtocolAndUnknownHeaderCountersWithoutLeavingFamilyMetadata`,
`#manualDimensionRetentionIsIndependentlyBoundedPerFamily`, and
`#concurrentDirectProtocolAndUnknownHeaderIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live-path evidence is
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`,
`#applicationCodesAreExcludedWhileAdmittedFixedErrorsRetainExactRequestContext`,
`#unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies`,
`#preAdmissionQuartetDeliveryIsReentrantAndSerializedWithoutCrossRequestOrderClaim`,
`McpHttpServerApplicationExecutionTests#produced_protocol_error_metric_allowlist_is_exact_and_excludes_application_codes`,
and `#failed_stream_terminal_discards_provisional_protocol_error_metric`.
Their accepted/unknown/error/rejected and admitted started/error/finished orders
are FIFO record/enqueue order, not universal cross-thread order or a
conservation equation.

At that checkpoint, the vertical did not constrain arbitrary manual
vocabulary, custom collectors, generic HTTP callbacks, `LogEvent`, `Request`,
`Throwable`, or application telemetry; add structured/raw-ID emission or
downstream OpenTelemetry mapping; prove sustained, soak, simulator or release-
candidate behavior; or freeze Phase 6.

The nineteenth bounded Phase 6 production vertical implements the frozen MCP
metric matrix in `soklet-otel:1.4.0-SNAPSHOT` against
`soklet:3.6.0-SNAPSHOT`. All 23 event variants map to exactly 22 downstream
instruments: 21 MCP-specific instruments and the shared transport-failure
counter. The only MCP-specific attributes are `soklet.mcp.endpoint`,
`rpc.method`, `soklet.mcp.request.outcome`,
`soklet.mcp.stream.termination.reason`,
`soklet.mcp.subscription.termination.reason`,
`rpc.jsonrpc.error_code`, and `soklet.mcp.shutdown.outcome`. Shared transport
adds only `soklet.server.type="mcp"` and fixed lower-snake
`soklet.failure.reason`; it deliberately omits `error.type` because the MCP
event carries no throwable.

For framework-produced events, the integration adds no dedicated attribute
for a trace ID or raw request ID, progress token/value/message,
mirrored-header name/value, request object, throwable, operation/resource URI,
principal, network address, tracestate, baggage, or generic label bag.
Framework-produced endpoint, method, outcome, reason, and code dimensions
retain the bounded core vocabularies. Direct public/manual events may supply
arbitrary valid values, including sensitive text; applications own their
confidentiality and cardinality, and the OpenTelemetry SDK—not Soklet's
default collector cap—owns downstream series retention. This is a
metric privacy/cardinality boundary, not anonymization or proof about custom
collectors, generic HTTP metrics (including their existing `error.type`),
application telemetry, logs, or SDK export/storage policy.

At the V19 boundary, the migration intentionally removed all obsolete MCP request/session/SSE span
callbacks, session instruments, span-policy knobs, and MCP span-naming
methods. The reviewed public delta was exactly 15 removed legacy methods and
one added metrics callback. Modern MCP lifecycle callbacks then remained
inherited no-ops, so that metric slice had no replacement MCP spans and no new
trace-parent or request-context attributes. HTTP/SSE tracing remained
unchanged. That metric slice also made no snapshot/reset/configuration-zero/filter/
OpenMetrics parity claim, cross-instrument atomicity or conservation claim,
structured-log claim, sustained-cardinality claim, or release/freeze claim.

Exact tests are
`OpenTelemetryMetricsCollectorTests#allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions`,
`#mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets`,
`#mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes`,
`#mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary`,
`#handlesConcurrentMcpMetricEventsWithoutLoss`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
At that point, both Corretto 21.0.11 and 26.0.1 passed the complete downstream
suite at 28/0/0/0. Core API, sketch, owner, canary, event, and wire inventories
remained unchanged. Modern `McpRequestContext` span parenting, naming, policy,
and terminal semantics were the next separate security and observability
contract slice.

The twentieth bounded Phase 6 production vertical implements those modern
admitted-request spans in `soklet-otel:1.4.0-SNAPSHOT` against
`soklet:3.6.0-SNAPSHOT`. Boxed `recordMcpRequestSpans` policy defaults to true;
the additive context-shaped default naming method preserves existing
three-method implementations. Default names and `rpc.method` expose only the
exact ten core methods or `<unrecognized>`, never the raw unsupported method
or an original-method attribute. Custom naming receives the raw context and is
therefore application-owned for confidentiality and cardinality.

One SERVER span covers each admitted request or notification through any
stream or subscription to terminal observation. Only validated MCP
`_meta.traceparent`/`tracestate` can parent it; physical HTTP trace headers,
ambient OpenTelemetry context, and baggage are not fallback parents. Start
attributes are exactly server type MCP, RPC system JSON-RPC, bounded method,
and registered endpoint. Physical `client.address` and Soklet request ID are
separate opt-ins disabled by default; the latter is never the JSON-RPC ID.

Every normal finish records lower-snake outcome. A client-visible JSON-RPC
error records its decimal code as string response status and `error.type` and
marks ERROR. Without an error, rejected/application/protocol/internal/deadline/
write outcomes are ERROR with the outcome as `error.type`; complete,
input-required, canceled, and client-disconnected remain UNSET without it.
Throwable lists never become exception events, status, attributes, messages,
data, or stack material. Duration overflow falls back to a plain end.

Disabled policy emits nothing. Missing/late finishes are no-ops; duplicate
direct starts and close plainly end state. A deterministic close/publication
barrier proves the post-publication closed recheck removes and ends the exact
new state, leaving no live span. Telemetry failures are contained and
concurrent contexts stay isolated. The built-in projection carries no JSON-RPC
ID, request metadata, operation/path/capability/admission data, baggage,
physical HTTP trace header, error message/data, throwable, or exception event,
apart from the intended MCP parent and explicitly opted-in physical address or
request ID. Raw validated trace identity is intentionally present as
OpenTelemetry parent/span identity, never duplicated into an attribute or
event; SDK/backend sampling, export, and retention are operator-owned. Custom
span names and application-supplied JSON-RPC error codes exposed through
`rpc.response.status_code` and `error.type` are application-owned for
confidentiality and cardinality. No session/stream/subscription span, custom-namer safety,
structured/raw-ID emission, comprehensive privacy, sustained cardinality,
simulator/release, or Phase 6 freeze was claimed at that V20 checkpoint.

Exact V20 tests are
`OpenTelemetryMcpLifecycleObserverTests#mcpMetadataTraceContextIsTheOnlyRemoteParentAndPreservesTraceState`,
`#mcpSpanUsesExactDefaultAndCustomNamesAttributesAndTerminalSemantics`,
`#allMcpRequestOutcomesMapToExactStatusAndErrorVocabulary`,
`#mcpRequestSpanStaysOpenUntilTerminalFinishAcrossStreamAndSubscriptionLifetimes`,
`#mcpPolicyAndNamingAreModernAdditiveAndLegacySessionControlsRemainAbsent`,
`#mcpTelemetryFailuresAreContainedAndReleaseStateExactlyOnce`,
`#concurrentMcpSpansRemainContextIsolatedAndCloseDrainsEveryState`,
`#mcpSpanProjectionExcludesSensitiveContextAndHttpFallbackCanaries`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
Core authority is
`McpRequestObservationPublicRuntimeTests#successfulToolSharesOneContextAndFinishesExactlyOnce`,
`#traceCaptureUsesOnlyValidMcpMetadataWithoutHttpFallback`,
`#handlerFailurePublishesExactInternalErrorAndImmutableThrowable`,
`#unsupportedNotificationRetainsRawLifecycleMethodAndBoundsMetrics`,
`#throwingObservationCallbacksAreContainedLoggedAndPartitioned`,
`McpRequestPropagationTests#validatedMetadataReachesAdmissionAndToolHandlersInsteadOfHttpTraceHeaders`,
`#invalidOrMistypedMetadataIsOmittedWithoutFallingBackToHttpHeaders`,
`#baggageParsingIsBoundedDecodedAndImmutable`,
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`,
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

At the V20 boundary, only the downstream artifact changed and all core
inventories were unchanged. Five declared downstream methods were added
relative to V19; the reviewed `1.3.1` to then-current diff was 13 removals and
four additions. `MCP-BASE-026` was COMPLETE. `AMB-003` was resolved/core-complete/downstream-
metric-complete; `SOK-METRIC-001`, `SOK-METRIC-004`, metric-only
`SOK-TRACE-005`, and `SOK-PRIV-001` remained PARTIAL; `SOK-TRACE-004`
remained PLANNED. MCP simulator integration was next.

The twenty-first bounded Phase 6 production vertical implements modern MCP
simulation through the shared `Simulator` without binding a socket. The two
abstract `startMcpRequest(...)` methods, seven top-level public simulation
types, and `McpSimulationOptions.Builder` expose bounded asynchronous JSON and
SSE capture. Defaults are 128 pending SSE items and 10,485,760 cumulative
captured bytes; both configured bounds must be positive.

Simulation reuses the real processor, application, admission, lifecycle,
metrics, request-stream/subscription, and request-control terminal paths, but
not the live listener. Public status remains `STOPPED`, bound address empty,
diagnostics zero, and server/connection/transport events absent. Host, Origin,
headers, and body are caller values and are not normalized or repaired.
Configured host policy evaluates the literal configured port, so port `0`
requires an exact `:0` Host authority and no Host is synthesized.

Item capacity is enforced before cumulative encoded bytes. Equality is
accepted; an offending frame is excluded; dequeue refunds only an item slot;
bytes never refund. JSON and pre-response SSE overflow retain their response
head and exact public item/byte terminal reason. Limit termination maps to the
coarse `SIMULATOR_LIMIT_EXCEEDED` token and admitted-request `CANCELED`
outcome, never to a protocol or transport failure. A captured terminal JSON
frame is one counted queued item and one completion reference at no additional
cost. Exact SSE bytes, response/body copies, header coalescing/order, completion
collections, and terminal-message duplication are immutable or defensively
copied.

Cancel, close, and scope exit reserve `CLIENT_DISCONNECTED` only if they win.
They are idempotent and cannot replace an earlier terminal. Cleanup is bounded;
noncooperative residual work fails the scope, is suppressed under a consumer
failure, and blocks new simulation and live start until release. Escaped
handles remain readable. Zero waits poll, huge waits saturate safely,
interruption does not cancel, and per-request FIFO does not imply global order.

Request Host/Origin/headers/body and retained Throwable identities are
application-sensitive. The framework does not add them to public diagnostics,
metrics, or carrier rendering, but the simulation accessors intentionally
return caller material; applications own logging, storage, and disclosure.
This is bounded local test evidence, not live-network isolation or a general
privacy boundary.

Representative exact citations from the full 46-test simulator/API gate are
`McpSimulationPublicApiTests#simulationSurfaceHasExactReferenceNullabilityAndClosedEnums`,
`McpPublicApiReflectionContractTests#phaseSixSimulatorInventoryAndSharedHostDescriptorsAreExact`,
`McpSimulatorPublicRuntimeTests#startMcpRequestRejectsMissingServerConfiguration`,
`#defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero`,
`#multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest`,
`#subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder`,
`#mcpSimulationCompletionRetainsStreamCaptureFailures`,
`#noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression`,
`#waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently`, and
`McpSimulationCaptureRuntimeTests#cancelAndTerminalRacePublishesOneCoherentFirstWinner`.

At the V21 boundary, Phase 6 had 15 owners, the provisional inventory had 32,
and the reviewed union had 219. The canonical comparison had 558 records and SHA-256
`d40004fa92cc5d095404de2133cf04fcd2b5574e9326eb680f571a017ef33671`;
frozen Phase 4/5 inventories and hashes were unchanged. Core metric and canary
state remained 23/23 events, 22 families, 22 snapshot getters/23 builder
methods, 12 boxed `Long` values plus ten maps, and 31/12 samples.

At the V21 boundary, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE but was not yet every-operation or 39-scenario
simulator proof, live-network fidelity, stress/
soak or sustained fuzz evidence, comprehensive privacy/security, release-
candidate provenance, or Phase 6 freeze. Other statuses remained unchanged; the
next bounded work was the complete release-workflow dry run and remaining
sustained, privacy, review, and freeze gates.

**Fourth unnumbered Phase 6 every-operation simulator, bounded capture-fuzz,
and off-network soak hardening checkpoint.** It now supplies deterministic
every-operation, capture-state-machine, and bounded smoke evidence without
changing production, public API, API inventories, wire behavior, or the 21
numbered production verticals.
`McpSimulatorEveryOperationTests#recognizedRequestMethodsReplayExactJsonOrSseShapes`
reports nine exact request cases; the companion
`#cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation`
and `#concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained` cases
freeze notification no-op semantics and concurrent isolation. They exercise
exact caller-supplied Host/media/protocol/operation headers and canonical
JSON/SSE, same-context lifecycle, bounded metrics,
`STOPPED` diagnostics, and zero server/connection/transport events. The exact
six-class operation selector passes 57/0/0/0.

Internal capture-state-machine-only fuzz coverage comes from the methods
`McpSimulationCaptureFuzzTest#captureStateMachineRemainsBoundedTerminalAndIdempotent`
and `#curatedSeedsReachJsonSseLimitCancelAndCompletionBranches`, which cap input at
65,536 bytes, 64 actions, 256 payload bytes, 16 pending items, and 4,096
captured bytes. Their six synthetic ASCII seeds are `json-complete.actions`,
`sse-terminal.actions`, `item-limit.actions`, `byte-limit.actions`,
`cancel.actions`, and `duplicate-terminal.actions`; focused replay passes
8/0/0/0 and full deterministic replay passes 135/0/0/0 across 16 methods, 15
classes, and 27 MCP seeds. The attempted five-second coverage-guided launch
was host-blocked before execution and proves nothing about explored coverage;
the declared `maxDuration=2m` is a registration bound, not executed-run
evidence.

`McpCrossFeatureSoakTests#mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup`
runs 24 fixed cycles over eight cases repeated three times at item/byte bounds
4/4,096 plus one residual recovery wave. It balances requests 38/38, streams
24/24, subscriptions 4/4, and handlers 34/34; records residual 1, transport 0,
listener lifecycle 0, and final `STOPPED`. The JDK 26 smoke profile passes
5/0/0/0 across three suites/five scenarios; verifier SHA-256 is
`eaa1f52aad86dc2765200273a468801e938f5a6be1719845358c9aa57879bcd6`,
and the broadened JDK 26 selector passes 226/0/0/0. Clean exact-source full
suites on Corretto 21.0.11 and 26.0.1 each pass 1,539/0/0/4 across 166 suites,
compiling 440 main and 176 test Java sources. A separate local JDK 26 nightly-
shaped execution passes 5/0/0/0 with verifier SHA-256
`a20a70d6adb1fd2cb5909be76b219e38fc112524a12fc06552b26bdd8ec76d99`.
It runs 200 cycles over eight cases repeated 25 times and balances requests
236/236, streams 156/156, subscriptions 26/26, and handlers 210/210, with
residual 1, transport 0, listener lifecycle 0, final `STOPPED`, file-
descriptor delta 0, heap delta +15,272 bytes, and thread delta -1. This was a
local nightly-shaped execution, not scheduled CI, sustained, fleet, or
release-candidate evidence. V21 static-analysis, SpotBugs, packaging/Javadoc,
API-verifier, sketch, and schema evidence is carried forward and was not rerun
for this checkpoint.

At that fourth checkpoint, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE and included deterministic every-operation evidence.
The ledger was 21 numbered verticals plus four unnumbered checkpoints. Those
bounded results were not the strict local 39-scenario driver, every parameter/error permutation,
live-network fidelity, scheduled/manual or sustained coverage-guided fuzz,
corpus saturation, long/fleet soak, comprehensive privacy/security, release-
candidate provenance, or Phase 6 review/freeze. `SOK-VALID-002` and
`SOK-PRIV-001` advanced narrowly but remained PARTIAL; all
other statuses remained unchanged. The next slice was a strict 39-row LOCAL
off-network driver tied byte-for-row and name-for-name to the pinned
`CLI/scenarios.json` manifest ordinal order; it was not the official CLI or a
live-network run.

**Fifth unnumbered Phase 6 candidate-artifact/public-API-only local 39-row
simulator-driver checkpoint.** `conformance/official/run-local-simulator.mjs`
validates the exact active set and preserves pinned `CLI/scenarios.json`
manifest ordinal order: 39 `RUN` rows at ordinals 1 and 3 through 40. It passes
those ordinal/name pairs to
`McpLocalSimulatorScenarioDriver#runManifestRowsOffNetwork` with only the
compiled fixture classes and candidate JAR on the class path. Every row uses a
fresh scenario configuration and simulator scope and performs bounded public-
API operations; package-private fixture source helper
`McpConformanceFixture#simulationConfigForScenario` provides the registrations
without creating a production entry point.

The wrapper byte-compares exactly one
`PASS\t<ordinal>\t<name>\n` record per row in manifest ordinal order and
requires empty standard error and a clean exit. Corretto 21 and 26 both compile
the fixture and driver with `--release 17 -Xlint:all -Werror`, pass the fixture
contract main, pass a `jdeps` rejection gate for `com.soklet.internal`, and
execute 39/39 rows. The adversarial
`conformance/official/local-simulator-self-test.mjs` rejects reordered,
duplicate, missing, failed-spawn, nonzero-exit, signaled, standard-error,
wrong-output, `FAIL`, CRLF, and unterminated transcripts.

This fifth checkpoint changes no production source, public API or sketch,
owner/signature inventory, metric/event/snapshot surface, wire behavior, or
numbered vertical. The ledger is 21 numbered production verticals plus five
unnumbered checkpoints. The API comparison was 558 records with its same
hash and at that checkpoint had 15/32/219 Phase 6/provisional/reviewed owners;
the 23/23 event,
22-family, 22-getter/23-builder, and 31/12 canary surfaces were unchanged.
`SOK-SIM-001` was COMPLETE BOUNDED PHASE 6 IMPLEMENTATION EVIDENCE; all other
status rows were unchanged.

That fifth checkpoint was not the official CLI or an official expected-check
multiset replay, and it opened no live network path. It did not exercise listener/kernel
behavior, socket backpressure or write-idle handling, or establish release
provenance, sustained-operation, comprehensive privacy/security, or Phase 6
review/freeze evidence. Next are scheduled coverage-guided fuzz and sustained
soak/stress gates, followed by structured-log, privacy, and API review/freeze
work. Phase 6 remained provisional and unfrozen at that checkpoint.

`McpServerDiagnostics` now declares exactly 12 zero-argument methods:
`getStatus()` and `getBoundAddress()`, plus all ten implemented diagnostic
getters. Six are boxed `@NonNull Integer` values from
`getRequestHandlerConcurrency()`, `getRequestHandlerQueueCapacity()`,
`getActiveHandlerExecutions()`, `getQueuedRequests()`,
`getActiveRequestStreams()`, and `getActiveSubscriptions()`. The remaining four
are `getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyRingFingerprint()`, and
`getTraceCorrelationConfigurationFingerprint()`; both fingerprint getters
return non-null `Optional` containers with non-null payload types.

The numeric fields cover configured handler bounds, occupied handler slots,
physically queued requests, open request-scoped SSE streams, and their
resource-subscription subset. They are server-wide counts with no endpoint,
method, identity, request, header, URI, trace, state, or application-controlled
label or text. A subscription enters both stream counts once its acknowledgment
stream opens; the values do not claim client receipt, and
`0 <= activeSubscriptions <= activeRequestStreams`.

Lifecycle status, bound address, configured bounds, handler counts, and the
paired stream/subscription counts are captured atomically as one runtime tuple.
Protection and trace fields are captured atomically as a separate security-
controls tuple. Both enter one immutable result, but no invariant joins them at
one global linearization point. Retained snapshots do not change. Ordinary,
subscription-only, and combined open states produce `1/0`, `1/1`, and `2/1`.
Disconnect cleanup moves `2/1` to `1/0` and then `0/0`. Completed clean and
residual-handler stops both expose stream pair `0/0`, even while a residual
handler remains active until actual exit. During internal `FAILED` cleanup,
public residual status may transiently retain `1/1`; completed cleanup exposes
`STOPPED` with `0/0`. These diagnostics do not expose queue contents, rejection
causes, stream contents, or subscription filters.

The two live-stream fields add no metric family, event type, label, or other
observation dimension, and collector reset cannot alter them.

The protection mode and custom-protector flag are fixed at server construction
and stable across listener stop/restart. The flag is `true` exactly in
`CUSTOM_PROTECTOR` mode and reports the custom application-owned
`McpRequestStateProtector` SPI. It does not report whether any operation selects
`APPLICATION_PROTECTED`; that application-owned opaque mode needs no framework
protector and bypasses one even when configured.

The protection-ring fingerprint is present exactly for
`PRODUCTION_KEY_RING`. It is absent for no framework keys, custom protection,
and development-ephemeral protection. The trace-configuration fingerprint is
independent of protection mode and present exactly when trace correlation was
enabled at construction. Successful live ring/key rotations appear in fresh
snapshots, survive listener lifecycle transitions, and do not change retained
snapshots.

Both fingerprints are deterministic operational deployment-comparison
metadata, not authentication, authorization, or token-derivation inputs. The
diagnostics contain no raw key material, key IDs, per-key fingerprint tags,
custom-provider identity, request-state cursors or epochs, or trace-correlation
tokens. They do not compensate for low-entropy key material: equality remains
observable, and rotation can create high-cardinality values. Operators should
therefore provision high-entropy keys, keep fingerprints out of metric labels,
and avoid per-request logging or unbounded retention of them.

One context-aware deferred FIFO now serializes all 23 declared semantic event
variants produced by the runtime: the prior 20 handler, lifecycle, request,
stream, subscription, cancelation, progress, keep-alive, protocol, and
unknown-header variants, plus `ConnectionAccepted`, `ConnectionRejected`, and
`TransportFailure`. Collector callbacks run after the relevant dispatcher,
progress-reporter, stream-transition, request-control, runtime, server, and
Soklet lifecycle locks or monitors are released. Nonwaiting request-transition
deferral preserves reentrant collector liveness without moving callbacks under
those locks.

`ProtocolError` is limited to the fixed `-32700`, `-32600`, `-32601`,
`-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998`
codes after successful encoding; application-owned codes are excluded. A
streamed error is provisional until its terminal reservation succeeds and is
discarded otherwise. Each unknown mirrored-header occurrence emits one event
containing only its finite endpoint path and a bounded recognized method or
`<unrecognized>`. The event contains no header name, header value, or raw
unrecognized method, and its count is independent of the optional name-bearing
diagnostic quota.

All pre-admission events are request-free. Only an admitted fixed
`ProtocolError` retains its exact originating `Request` for the bounded
delivery and correctly attributed failure-log step. It is never rendered,
exposed as a label, or promoted to an aggregate dimension. Collector failures
are contained without stalling later delivery. This narrow statement does not
close the broader secret-retention, cardinality, or redaction review.

`ConnectionAccepted` follows operating-system accept and successful capacity
reservation but precedes connection-loop registration and request parsing. A
subsequent setup failure may therefore follow it as
`TransportFailure(CONNECTION_SETUP_ERROR)`. `ConnectionRejected` is emitted
only for an accepted socket refused at the configured connection-capacity
limit. An accept or setup throwable is a typed transport failure and never a
capacity rejection.

Every `TransportFailure` is server-scoped and request-free. Its complete
bounded vocabulary is `REQUEST_READ_TIMEOUT`, `REQUEST_TOO_LARGE`,
`MALFORMED_REQUEST`, `READ_ERROR`, `WRITE_ERROR`,
`RESPONSE_WRITE_IDLE_TIMEOUT`, `RESPONSE_READY_ERROR`,
`REQUEST_READ_TIMEOUT_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`,
`ACCEPT_LOOP_ERROR`, `CONNECTION_SETUP_ERROR`, `TASK_ERROR`,
`TIMEOUT_TASK_ERROR`, `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
`EVENT_LOOP_TERMINATED`, and `UNKNOWN`. The event and any collector-failure log
retain only that fixed enum: no remote/socket address, raw request, request
context, throwable, payload, header, trace token, or provider-controlled text.
Reasons are selected at typed low-level authorities, not parsed from exception
or log strings.

Typed provisional scopes keep a reason active through the matching synchronous
close/cancel/terminal consequences and discard it on successful transitions.
Their coalescing single-daemon-worker drain never invokes the application
collector synchronously on a connection thread and preserves pending work
across a rejected executor submission. Blocking lifecycle adoption preserves
fatal `EVENT_LOOP_TERMINATED`, old `ServerStopped`, new `ServerStarted` order
before restart returns.

A byte-free idle close is quiet, but real partial request bytes produce
`REQUEST_READ_TIMEOUT`. Transport-malformed HTTP produces `MALFORMED_REQUEST`;
malformed JSON inside a complete HTTP request stays on the bounded JSON-RPC
protocol-error path. A request-SSE write-idle winner produces exactly one
`WRITE_TIMEOUT` before stream/request terminals; a losing or generic close
produces no `WRITE_TIMEOUT`, and intentional channel cancelation does not
synthesize `WRITE_ERROR`. The sole fatal-loop winner records
`EVENT_LOOP_TERMINATED`
before stop/wake and retains its failure scope through sibling-loop cleanup.
Ordinary clean disconnect and stream backpressure remain represented by their
existing terminal events rather than an invented transport failure.

The FIFO guarantee is metric record/enqueue order, not a universal cross-thread
causal or per-request total order for independently racing producers. Direct
restart orders the old generation's `ServerStopped` before the new
`ServerStarted`; managed startup rollback orders its `ServerStarted` before
`ServerStopped`.

Separate from the first eight production observability and diagnostics
verticals, a
bounded Phase 6 MCP fuzz-registration and hardening checkpoint adds five new
Jazzer methods:
`McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
`McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
`McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
`McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
`McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
21 checked-in synthetic text seeds cover those targets, and the nightly matrix
declares 15 total one-method slots, five of them new. This fuzz checkpoint
remains unnumbered; it is not the ninth production vertical described below.

Envelope decoding uses production JSON limits and admits only a classified
envelope or typed `McpWireDecodingException`, without an unconditional
encode-round-trip claim. Mirrored-header decoding uses the production default
bound and verifies its uniform redacted `IllegalArgumentException`. Profile 1
schema/instance input is capped at 64 KiB and produces only stage-typed
compilation or production-bounded evaluation outcomes. Cursor input is capped
at 64 KiB and checked through decoded UTF-8 and raw UTF-16 projections against
the JDK UTF-8 encoder in `REPORT` mode at a derived 1-to-256-byte limit.
Request-state plaintext uses a fixed binding, clock, request ID, 4,096-byte
bound, 15-minute lifetime, and three-round limit; accepted plaintext must
re-encode byte-exactly, rejection must remain uniformly redacted, and the
terminal-LF copy is bounded to 4,097 input bytes. The cursor validation seam is
internal and package-private, shared by incoming and outgoing checks, and adds
no public API.

The 21 seeds are synthetic protocol values, not captured requests, protected
deployment state, secrets, credentials, or raw trace context. Deterministic
replay is a parser/validator regression gate only. No scheduled or manual
coverage-guided nightly run occurred, and replay is not sustained, coverage,
corpus-saturation, privacy, security, release-readiness, or Phase 6 freeze
proof.

An unnumbered internal trace-correlation derivation checkpoint implements the
frozen token construction. Trace correlation is disabled by default, and
disabled controls capture no token. Enabled controls
snapshot one complete active key ID and key-material pair under the shared
security lock, derive after releasing it using HMAC-SHA-256 over UTF-8
`soklet-mcp-trace-correlation-v1\0` plus the decoded 16-byte trace ID, truncate
to the first 16 digest bytes, and encode an unpadded 22-character Base64URL
token. Invalid and all-zero trace IDs are rejected before derivation. Equal
key/trace inputs agree, changed inputs differ, and concurrent rotation admits
only coherent old or new `(keyId, token)` pairs. Copied key material and
explicit derivation buffers are zeroed; the internal carrier retains only the
nonsecret key ID and token and redacts the token from rendering.

The ninth bounded production vertical captures one carrier exactly once for
each admitted semantic request before lifecycle and handler observation. Only
a valid MCP `_meta.traceparent` is eligible. Disabled correlation, invalid or
all-zero MCP trace context, absent metadata, and a valid physical HTTP trace
header without valid MCP metadata all produce no carrier. Lifecycle,
interceptor, handler, and terminal observation share the same immutable
request context and carrier. A pre-rotation request retains its old
`(keyId, token)` through terminal observation; a fresh post-rotation request
uses the new pair. Raw validated trace-ID opt-in neither enables correlation
nor changes the token. The hidden final carrier retains only nonsecret key ID
and token, never raw trace context or key material, and redacts the token from
rendering.

At that point, following the ninth vertical, the prior fuzz and dormant
derivation checkpoints remained unnumbered. `SOK-TRACE-001`, `SOK-TRACE-002`,
and `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` and `SOK-TRACE-005` were
PLANNED; and `SOK-PRIV-001` was PARTIAL. No public API or API-sketch source
changed. No structured-log carrier, field, emission point, cadence, or new
`LogEventType` existed, and raw trace-ID logging was unimplemented at that
checkpoint. No metric, event, diagnostics/snapshot
field, aggregate, label, or wire dimension was added. The token remains
pseudonymous high-cardinality sensitive telemetry, not anonymization or an
authentication/authorization input, and must stay out of metrics. The carrier
is not cleared at finish and has no GC or application-reference lifetime
guarantee; an application-retained context naturally retains it while core
controls retain only the current key and expose no history API. This is not
comprehensive trace/baggage redaction, cardinality, privacy/security,
aggregate/`AMB-003`, simulator, release-readiness, or Phase 6 freeze evidence.

A third unnumbered Phase 6 metric-dimensionality checkpoint was covered by
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
It freezes the exact 23 public final event variants, including 11 fieldless
variants, whose conventional getters expose only endpoint path, bounded
method, fixed outcome/reason/code, and nonnegative duration. Production
projects registered endpoints, recognized methods or `<unrecognized>`, ten
fixed codes, and fixed enums; named `McpMetricsEvent` factories do not enforce
those runtime vocabularies for arbitrary application-created values. Variant
constructors are private, while the nested types remain public for typed
pattern matching. At that checkpoint, the MCP snapshot was
exactly three boxed `Long` values plus an immutable shutdown map. The default
collector aggregated only the five handler variants and `ServerStopped`,
ignoring and retaining none of the other 17 variants.

Sixteen sequential admitted requests with distinct valid MCP and HTTP trace
IDs, tracestate, baggage, derived tokens, and key canaries do not alter or
appear in built-in MCP events, snapshot state, metric names/labels,
filter-observed samples, Prometheus, OpenMetrics, or reset output. At that
checkpoint, the exact pre-reset MCP labels were three empty handler-label sets
plus shutdown `outcome=clean`; post-reset only the three empty sets remained.
The production-vertical count remained nine, and fuzz registration, dormant
derivation, and metric
dimensionality were three unnumbered checkpoints. `SOK-TRACE-001/002/003`
were COMPLETE; `SOK-TRACE-004` was PLANNED; `SOK-TRACE-005` was PARTIAL
for metric-only inventory/default-collector evidence; and `SOK-PRIV-001`
was PARTIAL. `SOK-METRIC-001` and `SOK-METRIC-004` remained PARTIAL;
`AMB-003` remained AMBIGUOUS.

That checkpoint did not cover custom collector storage, rendering, or cardinality;
generic HTTP `MetricsCollector` callbacks receiving a `Request`, request
target, or `Throwable`; `LogEvent`, application callbacks, handler telemetry,
or arbitrary application-created event vocabulary; structured logging or
raw-ID emission; future aggregate families; comprehensive trace/baggage
redaction; sustained cardinality, fuzz, or soak; simulation, migration,
release-candidate provenance, review, or Phase 6 freeze. No production source,
public API, API sketch, owner/signature inventory, family, label, event, or
wire behavior changed.

The transport aggregate is the tenth production vertical, server-start is the
eleventh, request-boundary aggregation is the twelfth, admitted-request
lifecycle aggregation is the thirteenth, request-stream lifecycle aggregation
is the fourteenth, subscription lifecycle aggregation is the fifteenth,
progress/cancelation aggregation is the sixteenth, keep-alive aggregation is
the seventeenth, and protocol/error-header aggregation is the eighteenth; all
three earlier checkpoints remain unnumbered, the downstream OpenTelemetry
metric migration is the nineteenth production vertical, modern admitted-
request spans are the twentieth, and bounded off-network MCP simulation is the
twenty-first. `McpMetricsSnapshot`
remains unchanged with 12
boxed `Long` values and ten immutable maps, with 22 getters and 23 public
builder methods including `build()`. The default collector aggregates the full 23/23
event variants across 22 rendered families, leaving zero core variants
unaggregated. The nonsubscription 16-request gate remains exactly 31
MCP-prefixed samples before reset and 12 after reset because both new map
families are sparse on that clean path. Its
failure map is empty, and every trace/
tracestate/baggage/token/key canary remains absent from the built-in MCP and
shared transport metric surfaces.

The rest of the resolved contract permits only bounded endpoint/method,
fixed-outcome/reason/code, duration, and label-free scalar dimensions. It
defines live request/stream/subscription gauges, bounded completion and
duration families, cancelation/progress counters, start/accept/reject/
keep-alive scalars, a fixed-code protocol-error map, and an unknown-header map
that never includes header identity. There are no standalone
start/finish/open/close counters. Configured scalars render zero; maps and
histograms are sparse; reset preserves five live gauges and clears cumulative,
map, and histogram state. The downstream implementation now maps the same 23
transitions to 22 instruments without changing this core contract.

At that aggregate checkpoint, `SOK-TRACE-005` remained PARTIAL for metric-only
evidence; `SOK-PRIV-001`, `MCP-HTTP-020`, `SOK-METRIC-001`, and
`SOK-METRIC-004` remained PARTIAL; and `SOK-METRIC-002`, `SOK-METRIC-003`, and
`SOK-SHUT-002` were COMPLETE. `AMB-003` was RESOLVED CONTRACT 2026-08-10 /
CORE IMPLEMENTATION COMPLETE / DOWNSTREAM METRIC IMPLEMENTATION COMPLETE,
`MCP-BASE-026` was COMPLETE, and `SOK-TRACE-004` remained PLANNED. That
checkpoint did not constrain custom collectors or application telemetry, promise an
atomic cross-field snapshot during active concurrent mutation, add structured-
log or raw-ID emission, complete privacy/cardinality work, or prove every-
operation simulation, sustained, release-readiness, review, or Phase 6 freeze.

Default aggregation now covers `ServerStarted`, `ServerStopped`,
`RequestAccepted`, `RequestRejected`, `RequestStarted`, `RequestFinished`,
`RequestStreamOpened`, `RequestStreamClosed`, the five handler variants,
`SubscriptionOpened`, `SubscriptionClosed`, `CancelationSignaled`,
`ProgressEmitted`, `KeepAliveEmitted`, `ProtocolError`,
`UnknownMirroredHeader`, and the transport trio. At that checkpoint, other
downstream work, structured-log carrier/emission, raw-ID opt-in,
broader privacy, sustained cardinality, and redaction work,
coverage-guided and sustained fuzz gates, release-candidate work, and Phase 6
review/freeze remained open. The delivery verticals added no public API, snapshot
field, aggregate family, label, event variant, or wire dimension. The tenth
added three provisional snapshot getters and three matching builder methods;
the eleventh adds one getter/builder pair, the twelfth adds two, the thirteenth
adds three plus `RequestOutcomeKey`, the fourteenth adds two plus
`RequestStreamTerminationKey`, the fifteenth adds two plus
`SubscriptionTerminationKey`, and the sixteenth adds two plus
`EndpointMethodKey`; the seventeenth adds one provisional getter/builder pair;
and the eighteenth adds two provisional map getter/builder pairs.
The nineteenth changes only the downstream `soklet-otel` artifact and adds no
core event variant, snapshot member, owner, label, or wire dimension.
The twentieth also changes only that downstream artifact and adds five declared
methods relative to V19, with no core inventory change.
The twenty-first adds seven top-level public simulation types,
`McpSimulationOptions.Builder`, and two abstract methods to `Simulator`, with no
core metric, snapshot, family, or canary change.
Phase 6 remained provisional and unfrozen at that historical checkpoint.

At the V21 boundary, the focused five-class simulator/API gate passed
46/0/0/0, and the broadened adjacent authority selector passed 215/0/0/0.
Clean exact-source full suites on
Corretto 21.0.11 and 26.0.1 each pass 1,528/0/0/4 across 165 suites, compiling
440 main and 175 test Java sources. Enforced static analysis is green with
existing advisory diagnostics; SpotBugs reports 0/0. Candidate main, sources,
and Javadoc JARs plus standalone Javadoc are green using offline-link
resolution. The API verifier is green for 558 incompatibility records, 15
Phase 6 owners, 32 provisional owners, and a 219-owner reviewed union. Frozen
1,049/195 inventories and hashes remain unchanged. All 167 sketch sources pass
Java 17 compilation and JDK 26 doclint, and 104 pinned schemas validate at
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a`.

The V20 downstream focus at 23/0/0/0 and full `soklet-otel` suite at 36/0/0/0
on each JDK were carried forward and not rerun for V21. The prior focused fuzz
gate remained 28/0/0/0 and deterministic full corpus replay remained 127/0/0/0
on both JDKs; neither was rerun. These are bounded development results, not
every-operation simulator, sustained fuzz/soak, privacy, security, live-network
fidelity, release-candidate, or Phase 6 freeze evidence.

### Deprecated compatibility surfaces

SEP-2577 deprecates Roots and Sampling at the MCP layer in `2026-07-28`; it
does not deprecate Soklet's retained Java API. New designs should pass files or
directories through explicit tool parameters, resource URIs, or server
configuration and integrate directly with a model provider. Soklet also does
not advertise or implement MCP Logging: retained log-level metadata is parsed
for compatibility, while applications use the existing observability path.
No negotiation-triggered warning is emitted. Adding one requires a separately
reviewed, default-off, bounded and redacted diagnostic rather than Java
`@Deprecated`, which describes a different lifecycle and trigger.

## Current API and release-security state

The current owner inventory is 133 Phase 4, 36 Phase 5, and 64 Phase 6 (233
total), with all three phases frozen and no provisional owner. The implemented
structured-log boundary completes the bounded `MCP_TRACE_CORRELATION` carrier
and separate raw-ID opt-in, but operator access, storage, retention, and
sustained cardinality/drain evidence remain outside that implementation proof.
Custom collectors, generic HTTP callbacks, application telemetry, and
application-constructed metric dimensions remain application-owned privacy and
cardinality surfaces.

Release validation is fail-closed and has no publish or signing authority. The
checked-in workflow requires an immutable clean candidate commit, checksum-
matched POM/main/sources/Javadocs artifacts, an isolated Maven installation,
the release soak, official release-mode conformance, localization verification,
and exact pinned downstream/interop evidence before it can assemble a PASS
manifest. It has not produced release evidence. The last full pre-typed-state
local checks passed core clean verify at 1,671/0/0/4 over 464 main and 193 test
sources, JDK 21 static-analysis `BUILD SUCCESS`, SpotBugs 0, Javadocs, fuzz
replay 139/139, and smoke soak 6/6 plus verifier. After the no-alias greenfield
typed-state amendment, fresh Corretto 26 clean verify passes 1,673/0/0/4 over
462 main and 193 test sources and builds the main, sources, and Javadoc
artifacts. Focused request-state/runtime tests pass 50/50;
reflection/inventory contracts pass 24/24; and the aggregate API gate verifies
565 incompatibilities, 234 owners, and 1,048/179/422 records. The maintained
179-source API sketch passes Java 17 compilation, Javadoc doclint, and its
localization smoke contract. That 1,673 result remains the typed-request-state
amendment checkpoint. The 1,676/0/0/4 result over 462 main and 194 test sources
remains the rate-limit identity/trusted-proxy checkpoint. The independent-
request direction-boundary result remains 1,678/0/0/4 over 462 main and 195
test sources, with its focused protocol gate at 35/35. At the localization-
fleet checkpoint, Corretto 26 clean verify passed 1,681/0/0/4 over 462 main and
196 test sources and built the main, sources, and Javadoc artifacts; the
fixture passes 3/3 and its related localization regression set
passes 24/24. The preceding Corretto 17 clean-test run passed 1,659/0/0/72
before the rate-limit identity, independent-request, and localization-fleet
runtime test sources were added, so it remains prior supported-JDK evidence
rather than a current 196-source result. The exact six-
scenario smoke soak passes 6/6 with its strict verifier. Carried-forward local
evidence remains green for candidate localization, artifact-backed simulator
39/39, pinned live official CLI 39/39, the website's offline clean-install,
lint, and 33-route SSG build, and OpenTelemetry 36/36. The checksum-pinned
TypeScript and Go harnesses are `READY` and green against the local snapshot.
The six reviewed downstream change sets remain uncommitted local work. They are
therefore unpublished and unpinned, so the manifest continues to carry its old
public commits. All four servlet legs pass 158/158 locally: the default 3.1.1 and
4.0.0 legs for both javax and Jakarta. ToyStore's completed local
migration passes 14/14, including six MCP tests. Its per-request credential
proof accepts a valid request, then returns 401 for malformed, missing, expired, and wrong-audience
credentials and 403 for an insufficient-scope credential; no prior request
identity or authorization is inherited. Its old manifest pin stays blocked
until its reviewed local changes are committed and published, the resulting
commit is pinned, and the immutable-candidate/JDK-25 validation passes.
Barebones compiles and its exact live
probes pass locally on a reserved ephemeral IPv4 loopback port supplied through
`SOKLET_BAREBONES_LOOPBACK_PORT` without disturbing the unrelated Docker
listener on port 8080. Its source and validator changes remain uncommitted and
unpinned, so its old public pin stays blocked.
A same-version macOS arm64 Corretto 21.0.12.9.1 run passed the full core
`clean test` at 1,681/0/0/4 at the initial JDK 21 gate checkpoint. Static
analysis reports `BUILD SUCCESS` with the
existing advisory inventory after the `SelfAssignment` fix, and SpotBugs
reports zero bugs and errors. The exact checksum-pinned Corretto 21.0.12.9.1
toolchain now drives `core-jdk-21`, `static-analysis`, and `spotbugs`.
The bounded two-listener localization fixture now covers failed reload,
rolling revision drift without within-response mixing, node loss,
subscription reconnect, node-local delivery, and final runtime cleanup.
The format-v2 release contract now enumerates exactly 26 ordered gates.
Twenty are dispatch-configured with executable `READY` paths, and none
remain `BLOCKED_HARNESS_MISSING`; the six downstreams remain
`BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`, leaving six fail-closed blockers.
`READY` means configured, never passed. The matrix-closure hook is `READY`, and
the candidate-contained registry and residual evidence produce a canonical
`PASSED` report at 113 `CORE_COMPLETE`, 119 `RELEASE_GATED`, 12
`APPLICATION_OWNED`, 19 `NOT_APPLICABLE`, and zero `UNRESOLVED`. Only the exact
candidate workflow can record its typed PASS receipt. Release scans,
benchmarks, published downstream pins, and immutable candidate
conformance/provenance remain open. Scheduled fuzz, nightly soak, and
operational histories remain advisory post-release monitoring and do not
block 4.0.
Candidate Javadoc
generation/completeness is configured; public deployment remains
post-validation publication work. The bounded two-listener localization
fixture is the Soklet-owned fleet gate; production multi-host coordination is
an application/deployment security responsibility. See
[release/README.md](release/README.md) for the exact validator contract and
current fail-closed statuses.

The preceding 2026-08-20 protocol/capability golden checkpoint passed 9/9 on
local Corretto 17 and the pinned Corretto 21, 86/86 across the broader
Corretto 17 protocol/capability gate, and both runner and local-simulator self-
tests. Full Corretto 17 clean verify passed 1,671/0/0/72 over 462 main and 196
test sources and built all three JARs. The manifest bound 43 production-
derived messages; expanded-corpus final-tag Ajv validation remained with
candidate conformance because the pinned official-suite checkout was not
locally available. This is local snapshot evidence, not immutable-candidate
evidence.

Those 1,681-test results remain the localization-fleet and initial JDK 21 gate
checkpoints. Current post-fix Corretto 21 validation passes core `clean test`
at 1,682/0/0/4 over the unchanged 462 main and 196 test sources. The focused
terminal/subscription regression set passes 32/32, a clean smoke soak passes
6/6 with its strict verifier and verifier self-test, and the cross-feature
smoke method passes 10/10 repeated stress runs. A fast inline application
stream that reserves its terminal response now retains transport-callback
ownership when the protocol task returns, avoiding premature terminal cleanup
without relaxing a timeout or expected count. The subscription activation
regression no longer treats asynchronous metric delivery as a barrier: it
checks acknowledgment-then-notification wire order directly, while the runtime
still queues the acknowledgment, activates the subscription, and only then
invokes the transport response callback.
Current supported-JDK revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes `mvn -B -ntp clean test` at 1,667/0/0/72 over the
same 462 main and 196 test sources. The two corrected methods pass 2/2 once
and 20/20 across ten combined repetitions. Both corrections are test-only
synchronization: live transport smoke waits for the complete idle snapshot,
and observation containment waits for actual typed failure-log publication
before exact inspection. The original exact counts and timeout assertions
remain; production behavior, public API, and frozen inventories are unchanged.

A subsequent containment revalidation on the pinned Amazon Corretto
21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact
`mvn -B -ntp clean test` at 1,682/0/0/4 over 462 main and 196 test sources.
The focused platform-plus-virtual-thread containment matrix passes 30/30, and
20/20 complete repetitions cover 600 dynamic cases; the affected JDK 17
platform-thread matrix also passes 15/15. This is test-only synchronization:
containment waits now include the exact expected cleanup count before returning.
Expected cleanup counts, timeout bounds, and assertions are unchanged;
production behavior, public API, and frozen inventories are unchanged. These
local snapshot checks are not immutable-candidate release evidence.

A later subscription observer-scope revalidation on the same pinned Amazon
Corretto 21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact full
`mvn -B -ntp clean test` at 1,682/0/0/4 over the unchanged 462 main and 196
test sources. The affected method passes 1/1 focused and 20/20 repeated runs;
`McpSubscriptionPublicRuntimeTests` plus
`McpSubscriptionRuntimeBoundaryTests` pass 26/26. The test-only correction
sets the per-authorization-partition subscription cap to one and holds the recovery
subscription open while the original disconnect observer's exact-once count
is asserted, preventing that recovery request's legitimate finish from
entering the first request's observation phase. No production behavior,
public API, Phase 4/5/6 freeze inventory, timeout, or asserted count changed.
This is local snapshot evidence, not an immutable release-candidate PASS
receipt.

Current JDK 17 application-execution revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes the exact `mvn -B -ntp clean test` at 1,667/0/0/72
over the unchanged 462 main and 196 test sources. The affected method passes
1/1 focused and 20/20 repeated runs, and the full
`McpApplicationExecutionTests` class passes 10/10. The same affected method
also passes 1/1 on the pinned Corretto 21.0.12.9.1 toolchain. The test-only
correction uses an exact post-observer stable fence requiring both
`retainedExchanges == 1` and `queuedCleanups == 1` before inspecting the
dequeued snapshot. Existing timeout bounds and expected counts are unchanged;
production behavior, public API, and the Phase 4/5/6 freeze inventories are
unchanged. This is local snapshot evidence, not immutable release-candidate
evidence.

The previous policy/error reconciliation checkpoint was test- and golden-only.
Its exact slice passes 27/27 on the pinned local Corretto 17 and Corretto 21
toolchains, and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK, and the
manifest now binds 48 production-derived messages. An unsigned Corretto 17
`clean verify` passes 1,677/0/0/72 over 462 main and 197 test sources and
builds the main, sources, and Javadoc JARs. No production behavior, public API,
or Phase 4/5/6 freeze inventory changed. At that checkpoint, the canonical
matrix was deliberately `FAILED`: 95 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
29 remained `UNRESOLVED`. Final-tag Ajv validation of the expanded 48-message
corpus had not been rerun locally and remained owned by candidate conformance.
These are local snapshot checks, not immutable-candidate evidence.

The preceding five-row compatibility reconciliation closed the core rows for
admitted identity versus client self-report, unknown client-extension
fallback, Bearer challenge transport, authorization/CORS response-head
behavior, and legacy session/replay-header containment. A real listener keeps
credential-selected identity authoritative despite forged client metadata.
Valid unknown extension settings remain opaque admission input without
inventing or advertising core support; malformed settings fail before
admission. A safe Bearer challenge can carry an absolute `resource_metadata`
URI and operation scopes, but the application owns their meaning and standards
compliance. The independent CORS goldens cover `Authorization`, modern and
registered MCP headers, `WWW-Authenticate` exposure, exact order and
multiplicity, and fail-closed legacy-header rejection.

The focused compatibility slice passed 33/33 on the pinned local Corretto 17
and Corretto 21 toolchains. The separate authorization/CORS HTTP-head manifest
at `conformance/golden-http-head/authorization-cors/manifest.sha256` binds
three raw production response-head fixtures.
`McpAuthorizationIntegrationTests` contains two test methods: one reads and
verifies those goldens, while the other asserts request and notification
challenge semantics. This separate corpus does not alter the final-schema
corpus, which remains 48 JSON messages with 11 focused
golden tests. An unsigned Corretto 17 `clean verify` passed 1,685/0/0/72 over
462 main and 201 test sources and built the main, sources, and Javadoc JARs.
The only production change was an internal policy-response denylist for legacy
MCP session/replay headers; a negative
production-source inventory confines those names to that denylist. Public API,
signatures, and the Phase 4/5/6 freeze inventories were unchanged. At that
checkpoint, the canonical matrix remained deliberately `FAILED`: 100 rows
were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 24 were `UNRESOLVED`.
Final-tag Ajv validation of the
expanded 48-message corpus was not rerun locally and remains owned by candidate
conformance. These are local snapshot checks, not immutable-candidate evidence.

The preceding four-row HTTP-contract reconciliation closed the exact validation-
precedence, diagnostic-boundary, unsupported-notification, and universal
`no-store` security contracts. The separate
`conformance/golden-http-contract/precedence-no-store/manifest.sha256` binds 22
canonical complete responses at SHA-256
`273e83945e5bae949c4a2eee85993883abb1350ef7234b98548d1134d0f7af02`.
Five contract tests comprise three real-listener golden tests, one exhaustive response-authority inventory, and one six-document manifest-digest parity gate;
four initialize-diagnostic tests include 23 readable-`initialize` rejection
cases and the negative boundary. Those two classes pass 9/9 in the current
focused execution.

Full clean test passes 1,693/0/0/72 on Corretto 17 and 1,708/0/0/4 on Corretto
21 over 462 main and 203 test sources. A subsequent local Corretto 17 package
validation built all three JARs after allowing configured external Javadoc
links. This corpus is separate from the unchanged official 48-message/11-test
JSON corpus and three-head/two-test authorization/CORS corpus. The narrow
internal change preserves readable `initialize` after strict JSON and adds a
bounded diagnostic only after unsupported-selector form and membership
validation; it implements no initialization or session.
Public API and freeze inventories are unchanged. At that checkpoint, the
matrix remained `FAILED`: 104 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
20 remained `UNRESOLVED`.
These are local snapshot results, not immutable-candidate evidence or results
from the release-pinned Corretto 21.0.12.9.1 toolchain.

The subsequent 2026-08-21 core-result/error closure adds two independent,
checksum-bound production corpora. The 25-fixture result-envelope manifest is
SHA-256
`d2eaa03c24927d45ef350b187624f50448d78a6531a26dedbbe07ee327b91b14`;
its four live tests and source/authority inventory exhaust Soklet 3.6's core
`complete` and `input_required` envelope authorities without claiming
extension result types. The twelve-fixture canonical complete-HTTP error
manifest is SHA-256
`bfaecadaba283df430026504b94f71640c0c56a830159100f9be9179a7ce4e2d`;
it covers the eight frozen ordinary mapping families, including separate
required-preflight and conditional-result `-32021` paths, exact `no-store`,
Retry-After exclusivity, original string/integer IDs across the corpus, and
hostile-value redaction. Readable-`initialize` and path-specific data-bearing
error evidence remain explicit supplements rather than being folded into that
ordinary family count.

Five deterministic races freeze both progress/error enqueue orders and the
mapped-error/cancellation ownership boundary. A nonstream mapped response owns
its terminal before a late pre-body cancellation; written streamed-error bytes
beat a concurrent cancellation exactly once; cancellation before streamed-
error reservation discards both the terminal and provisional protocol-error
metric; and no progress frame follows a mapped terminal. The combined focused
suite passes 21/21 and the adjacent group passes 195/195 on pinned Corretto
17.0.20.1 and local Corretto 21.0.11. Full clean test passes 1,704/0/0/72 and
1,719/0/0/4 over 462 main and 205 test sources. Corretto 17 package validation
builds all three JARs; API diff/parser/freezes remain green with 565 reviewed
incompatibilities and unchanged 1,048/179/422 signature counts. No production
behavior, public API, freeze, or version changes; the sole production-source
diff is a package-private no-op test hook at the existing-stream enqueue
boundary. At that checkpoint, the matrix remained `FAILED`: 106 rows were
`CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18
were `NOT_APPLICABLE`, and 18 remained `UNRESOLVED`. These are local snapshot
results, not immutable-candidate evidence or results from the release-pinned
Corretto 21.0.12.9.1 toolchain.

The subsequent application-security closure adds the public-API-only
[durable-handle and secured-prompt patterns](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
and [resource, URI, filesystem, and cursor patterns](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java).
Their eight tests make the application boundary executable: durable repository
and context-bound handle rotation, semantic prompt allowlisting and
authorization, canonical filesystem containment, delivery-intent URI policy,
and integrity/identity/snapshot/revision/expiry-bound cursors with neutral
failures. Soklet does not implement those policies or turn the examples' test
doubles into fleet services. The evidence moves `MCP-BASE-015`,
`MCP-PROMPT-006`, `MCP-RESOURCE-006/007`, and `MCP-PAGE-004/007` to
`APPLICATION_OWNED`; portable distributed-cursor proof remains open. No
production behavior or public signature/freeze inventory changed; public
Javadocs now document the existing application-owned boundaries. Focused owner
evidence on Amazon Corretto 17.0.20.1+10-LTS is two separate 4/4 class runs
(eight tests total); the direct combined suite is 8/8 on local Amazon Corretto
21.0.11.10.1 (OpenJDK 21.0.11+10-LTS). The adjacent 12-class suite passes 66/66
on each JDK. Full `mvn -B -ntp clean test` passes 1,712 tests with zero
failures, zero errors, and 72 skips on Corretto 17, and 1,727 tests with zero
failures, zero errors, and four skips on local Corretto 21; both compile 462
main and 207 test sources. At that application-pattern checkpoint, the matrix
remained `FAILED`: 106 rows were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, 10
were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 12 remained
`UNRESOLVED`.

The subsequent conditional-capability proxy closure adds the
[real loopback intermediary fixture](src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java).
Its single test drives a two-leg socket proxy with a manual monotonic idle
clock. With conditional support absent, the proxy observes zero backend and
client-visible response bytes before expiring at its exact configured boundary;
the reset produces one exact client-disconnect outcome and one cooperative
cancelation. The handler remains accounted until explicitly released, and its
late result emits no bytes. With support present, the same proxy forwards the
SSE head, progress notification, and terminal result byte-for-byte. The
focused/adjacent gate passes 33/33 on local Amazon Corretto
17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1 (OpenJDK
21.0.11+10-LTS). Full `mvn -B -ntp clean test` passes 1,713 tests with zero
failures, zero errors, and 72 skips on Corretto 17, and 1,728 tests with zero
failures, zero errors, and four skips on local Corretto 21; both compile 462
main and 208 test sources. A narrow internal production
fix preserves an outer cancel transition's exact observation reason and cause
instead of publishing a generic cancelation fallback. Public API, signatures,
freeze inventories, and the version are unchanged. This evidence models one
configured loopback intermediary; it does not establish a wall-clock
production timeout, universal proxy behavior, or prompt non-cooperative
application-code exit. At that checkpoint, `MCP-MRTR-011` became
`CORE_COMPLETE`; the matrix remained `FAILED` at 107 `CORE_COMPLETE`, 116
`RELEASE_GATED`, 10
`APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 11 `UNRESOLVED`. These are local
snapshot results, not immutable-candidate evidence; the Corretto 21 run is not
release-pinned.

The subsequent queued-execution winner-election closure adds
[deterministic queue ownership evidence](src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java).
One method stages promotion, exact-boundary deadline, and client disconnect,
then enumerates all six total orders with a monotonic manual clock and FIFO
manual executor. Deadline before promotion while the request remains writable
returns the exact queued HTTP 503/JSON-RPC `-32603` response; disconnect writes
nothing; promotion first ends the queued state and follows the separately
provisional active-deadline path. A second cross-layer case holds the exact
observer-deferral gap after the application layer reserves a queued deadline,
then makes the outer request control unwritable by disconnect before response
handoff. It observes zero callback bytes, exactly one `CLIENT_DISCONNECTED`
finish with the disconnect cause, and one dequeue/gauge removal. One deadline-
expiration occurrence and one abandoned response account for the reserved-but-
unwritable attempt, not a second terminal outcome. No queued interceptor or
handler sees the request, cleanup occurs once per request, and all retained
framework state returns to baseline. The focused class passes 2/2 on pinned
Amazon Corretto 17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1; the
adjacent Corretto 17 execution bundle passes 53/53. Full
`mvn -B -ntp clean test` passes 1,715/0/0/72 on Corretto 17 and 1,730/0/0/4
on local Corretto 21 over 462 main and 209 test sources. This slice changes no
production behavior, public API, signature/freeze inventory, or version. It
closes `SOK-EXEC-005`; the current matrix remains `FAILED` at 108
`CORE_COMPLETE`, 116 `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 10 `UNRESOLVED`. These are bounded local ordering
results, not proof of every scheduler/network interleaving or immutable-
candidate evidence; the Corretto 21 run is not release-pinned.

The subsequent off-network simulation boundary closure adds deterministic
internal and public evidence with source SHA-256 values
`7ab30148451fbef7e8a8131486cb67989ac133271797502920ef4aa2f1db6bd5` and
`b666ad1bcb6a3bca6e3af46505fe46b7365042b06189cc8daf41d5fb51e05350`.
Off-network capture never arms live write idle; non-drained item/byte limits
preserve retained frames, omit the offender, and remain immutable once-only
simulator outcomes. An unrelated simulation completes while the limited
handler still owns its slot, with balanced accounting and no transport event.
Separate real-listener tests remain the authorities for bounded slow-reader
TCP backpressure and actual response-write-idle closure/interruption. The two
selectors pass 2/0/0/0, both affected classes pass 25/0/0/0, and the adjacent
loopback/simulator bundle passes 26/0/0/0 on pinned Corretto 17 and local
Corretto 21. Full clean test passes 1,717/0/0/72 and 1,732/0/0/4 over 462 main
and 209 test sources. No production behavior, API, freeze inventory, manifest,
or version changes. `SOK-SIM-001` is now `RELEASE_GATED` by the exact seven
named gates; the current matrix is 108 `CORE_COMPLETE`, 117 `RELEASE_GATED`,
10 `APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 9 `UNRESOLVED`, with a
117/117/10/18/0 synthetic all-resolved report. This is deliberate simulator/
live separation, not kernel, TCP, or live write-idle equivalence.

The subsequent localized-cursor fleet application-pattern closure adds
`McpLocalizedCursorFleetApplicationPatternsTests` at final source SHA-256
`10d872127f2a25632137899986ea75cfdfe838eb2d6fbfa395283285b678d567`.
Its two public-API-only methods transfer only the exact opaque cursor between
independently configured simulator nodes; preserve bounded, stable, unique
traversal of a retained snapshot after another node activates a replacement
catalog; bind snapshot/catalog and locale/localization revisions plus expiry,
offset, and authorization; and preserve the same bytes from provider
preselection through full handler authentication. Every exercised invalid
classification produces one fixed no-data application `-32602`/400 error with
zero lifecycle throwables. The selector passes 2/0/0/0, the adjacent six-class
set passes 30/0/0/0, and full clean test passes 1,719/0/0/72 and
1,734/0/0/4 on pinned Corretto 17 and local Corretto 21 over 462 main and 210
test sources. No production behavior, API, freeze inventory, manifest, or
version changes. `MCP-PAGE-006` and `SOK-L10N-007` are now
`APPLICATION_OWNED`; the matrix is 108/117/12/18/7 and the synthetic report
is 115/117/12/18/0. The two-node fixture models application replication; it is
not Soklet-provided storage, key management, replication, affinity, or a
positive cache-TTL claim.

The subsequent `MCP-BASE-011` notification-identifier boundary closure adds
`src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java` at final
source SHA-256
`ce10724e565470bdcd6f005ad3d332ea473698f7c7754c765c3bfc73a8c3a3f5`.
Its two public-API-only methods prove that classified inbound notifications
always have an empty HTTP transport body and bypass application request-
handler and handler-interceptor stages. Malformed JSON that fails before
notification classification is outside this claim. Outbound progress,
subscription-acknowledgment, and list-changed notification frames carry a
method and omit top-level `id`; nested `progressToken`,
`io.modelcontextprotocol/subscriptionId`, and cancellation `requestId`
parameter members remain legitimate rather than correlation leaks. Only the
method-free terminal result retains the initiating request's top-level `id`.
Soklet 3.6 registers no extension-notification handler and exposes no arbitrary
extension-notification handler API. The exact selector passes 2/0/0/0, the
adjacent set passes 83/0/0/0 on both JDKs, and full clean test passes
1,721/0/0/72 and 1,736/0/0/4 on pinned Corretto 17 and local Corretto 21 over
462 main and 211 test sources. No production behavior, API, freeze inventory,
manifest, version, or official 48-message/11-test corpus changes.
`MCP-BASE-011` is now `CORE_COMPLETE`; the current report remains `FAILED` at
109 `CORE_COMPLETE`, 117 `RELEASE_GATED`, 12 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 6 `UNRESOLVED`, while the synthetic all-resolved report
is 115/117/12/18/0. The remaining IDs are `MCP-HTTP-020`, `SOK-VALID-002`,
`SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and `AMB-002`.

The subsequent 2026-08-22 `MCP-HTTP-020` closure strengthens
`McpMirroredHeaderPublicRuntimeTests` at final source SHA-256
`2c3b912484bd96d0f2f73fc4c3b85fdf9760e22d895acf4145b962bd8fc0b303`.
An unannotated `privilege` body property carries `reader` while unknown
`Mcp-Param-Privilege` carries `administrator-canary`; converted and raw
arguments remain body-authoritative and the response excludes the canary.
Existing exact fixtures prove name diagnostics are off by default; opt-in
emits sanitized names only, permits at most ten attempted events per server in
any monotonic 60-second window, truncates at 128 ASCII bytes, and attaches
neither values nor requests. Each occurrence independently aggregates only by
registered endpoint and bounded method, never header identity, with an
8,192-dimension default-map cap and the same downstream OpenTelemetry shape.

The focused class passes 6/0/0/0, the adjacent five-class set passes 29/0/0/0,
and full clean test passes 1,721/0/0/72 and 1,736/0/0/4 on pinned Corretto 17
and local Corretto 21 over 462 main and 211 test sources. The pinned
40-scenario official inventory has no exact scenario for this policy; this
test-only slice changes no production behavior, public API, freeze inventory,
manifest, version, official result, or official corpus. `MCP-HTTP-020` is now
`CORE_COMPLETE`; the report is 110/117/12/19/5 and the remaining IDs are
`SOK-VALID-002`, `SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and
`AMB-002`. This boundary does not close generic `Request`, `Throwable`,
custom-collector, or application-telemetry privacy; those remain owned by
`SOK-PRIV-001`.
