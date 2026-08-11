# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by emailing security@revetware.com.

Include the affected Soklet version, a concise description of the issue, and any reproduction steps or proof-of-concept details that can be shared safely. Please do not open a public GitHub issue for suspected vulnerabilities until we have coordinated disclosure.

You should receive an acknowledgment within 3 business days. We will work with you on a coordinated disclosure timeline appropriate to the severity of the issue.

## Supported Versions

Security fixes are prioritized for the latest released version of `com.soklet:soklet`. Snapshot builds are development artifacts and may change without notice.

## Scope

Soklet has zero runtime dependencies, so vulnerabilities in the released artifact are limited to first-party code. Reports against the embedded HTTP, SSE, and MCP transports — including request parsing, connection lifecycle, and resource-limit enforcement — are especially appreciated.

## MCP Deployment Security

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

Every MCP server requires an explicit `McpRequestAdmissionPolicy`. Production
applications should authenticate and authorize there and return stable,
bounded rate-limit and authorization partition keys in the accepted
`McpAdmissionIdentity`. `McpRequestAdmissionPolicy.acceptAllInstance()` is an
explicit anonymous policy, not a production authentication mechanism.
Client information, client capabilities, request `_meta`, and advertised
server information are self-reported or informational metadata. Never use
them as authenticated identity or as an authorization or rate-limit partition
key.

Request-wide rate limiting is optional. A tool-bearing server must configure a
fallback tool limiter; named endpoint and tool overrides replace that fallback.
The built-in token bucket is bounded but local to one JVM. Multi-instance
deployments that require fleet-wide enforcement should supply their own
thread-safe `McpRateLimiter`, backed by a distributed service, and should fail
closed when that service is unavailable.

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
and fleet portability. Do not put confidential data in a cursor unless the
application protects it appropriately.

Tools, prompt gets, and resource reads may now perform multi-round-trip
`input_required` exchanges. The operation must declare every client request it
may emit. Required capabilities are checked before admission; conditional
capabilities are checked only when emitted, but still before output parameters,
metadata, request state, or a custom protector is processed. Client
`inputResponses` remain untrusted input and must be authorized and validated in
the handler even after Soklet validates their protocol shape.

Request-state protection has two distinct trust boundaries:

- `APPLICATION_PROTECTED` is exact opaque-string pass-through. Soklet enforces
  nonempty/type and a 65,536-byte UTF-8 limit, but supplies no confidentiality,
  integrity, expiry, authorization binding, replay protection, or fleet
  portability. The application must provide every one of those properties it
  needs; do not place secrets in the value without application encryption.
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

Progress reporting, cooperative cancelation, and resource-subscription delivery
are implemented. Fourteen bounded Phase 6 verticals are also implemented: shutdown
observation, handler-capacity metrics, handler diagnostics, live
stream/subscription diagnostics, protection/trace diagnostics, serialized
semantic-event delivery, bounded pre-admission metrics, connection/transport
metric delivery, admitted-request trace-token capture, and the first default
transport-boundary, server-start, request-boundary, admitted-request, and
request-stream lifecycle aggregate families. Shutdown
metrics have only the fixed
`McpShutdownOutcome`-derived
`clean`/`residual_handlers` label. The exact handler-capacity families—
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
exit from manufacturing a negative gauge.

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
boxed `Long` values and four maps. Their public, thread-safe
`RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome)` rejects null/empty
shape but does not validate application-created registry membership; the
built-in producer supplies only a registered endpoint, recognized method or
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
changed. No structured-log
carrier, field, emission point, cadence, or new `LogEventType` exists, and raw
trace-ID logging remains unimplemented. No metric, event, diagnostics/snapshot
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
It freezes the exact 23 event-record schemas, including 11 fieldless variants,
and permits only endpoint path, bounded method, fixed outcome/reason/code, and
nonnegative duration components. Production projects registered endpoints,
recognized methods or `<unrecognized>`, ten fixed codes, and fixed enums;
public event constructors do not enforce those runtime vocabularies for
arbitrary application-created values. At that checkpoint, the MCP snapshot was
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
lifecycle aggregation is the thirteenth, and request-stream lifecycle
aggregation is the fourteenth; all three earlier checkpoints remain
unnumbered. `McpMetricsSnapshot` now has ten boxed `Long` values and five
immutable maps, with 15 getters and 16 public builder methods including
`build()`. The default collector aggregates 16 event variants while ignoring
the remaining seven across 15 rendered aggregate families. The nonstreaming
16-request gate has 29 exact MCP-prefixed samples before reset and ten after;
only the configured zero stream gauge is added and the stream histogram stays
sparse. Its failure map is empty, and every trace/
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
map, and histogram state. The authoritative Phase 6/V10 contract owns the
exact downstream OpenTelemetry mapping.

`SOK-TRACE-005` remains PARTIAL for metric-only evidence; `SOK-PRIV-001`,
`SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL. `SOK-METRIC-002`,
`SOK-METRIC-003`, and `SOK-SHUT-002` remain COMPLETE. `AMB-003` is RESOLVED
CONTRACT / IMPLEMENTATION PARTIAL;
unimplemented core families and downstream snapshot-compatible OpenTelemetry
work remain open.
This does not constrain custom collectors or application telemetry, promise an
atomic cross-field snapshot during active concurrent mutation, add structured-
log or raw-ID emission, complete privacy/cardinality work, or prove simulation,
sustained, release-readiness, review, or Phase 6 freeze.

Default aggregation now covers `ServerStarted`, `ServerStopped`,
`RequestAccepted`, `RequestRejected`, `RequestStarted`, `RequestFinished`,
`RequestStreamOpened`, `RequestStreamClosed`, the five handler variants, and
the transport trio. The next aggregate implementation is subscription
lifecycle aggregation for `SubscriptionOpened` and `SubscriptionClosed`. Other
remaining contract-fixed families and downstream
OpenTelemetry work, structured-log
carrier/emission, raw-ID opt-in,
broader privacy, sustained cardinality, and redaction work, MCP simulation,
coverage-guided and sustained fuzz gates, release-candidate work, and Phase 6
review/freeze remain open. The delivery verticals add no public API, snapshot
field, aggregate family, label, event variant, or wire dimension. The tenth
added three provisional snapshot getters and three matching builder methods;
the eleventh adds one getter/builder pair, the twelfth adds two, the thirteenth
adds three plus `RequestOutcomeKey`, and the fourteenth adds two plus
`RequestStreamTerminationKey`. None adds an event variant or wire dimension.
Phase 6 remains provisional and
unfrozen.

The focused request-stream lifecycle aggregate/adjacent gate passes
58/0/0/0.
The prior focused five-target fuzz gate remains 28/0/0/0 and was not rerun for this
checkpoint; prior deterministic full fuzz corpus replay on both JDKs remains
127/0/0/0 and was likewise not rerun. Exact-source full main suites on
Corretto 21.0.11 and 26.0.1 each pass 1,485/0/0/4. Enforced static analysis is
green with existing advisory diagnostics; SpotBugs reports 0/0. Candidate main,
source, and Javadoc
packages plus standalone Javadoc are green using offline-link resolution. The
API evidence reports 556 incompatibilities and 208 reviewed current-side API
owners; the 30-entry provisional inventory includes `RequestOutcomeKey` and
`RequestStreamTerminationKey`. The prior hashes and frozen
1,049/195 inventories remain unchanged. All 167 sketch sources pass Java 17
compilation and JDK 26 doclint, and 104 pinned schemas validate at
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a`. These are development results, not
privacy, security, release-candidate, or Phase 6 freeze evidence.
