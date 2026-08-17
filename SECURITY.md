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

Every MCP server requires an explicit `McpAdmissionController`. Production
applications should authenticate and authorize there and return stable,
bounded rate-limit and authorization partition keys in the accepted
`McpAdmissionIdentity`. `McpAdmissionController.acceptAllInstance()` is an
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
semantic classifier or a real downstream authorization deployment.

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

All 64 Phase 6 owners are now frozen and the provisional inventory is empty.
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

## Current API and release-security state

The current owner inventory is 133 Phase 4, 39 Phase 5, and 64 Phase 6 (236
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
manifest. It has not produced release evidence. Final local checks pass core
clean verify at 1,667/0/0/4 over 464 main and 193 test sources, JDK 21 static-
analysis `BUILD SUCCESS`, SpotBugs 0, Javadocs, API 564/236 with
1,053/195/420 records, fuzz replay 139/139, smoke
soak 6/6 plus verifier, candidate localization, artifact-backed simulator
39/39, pinned live official CLI 39/39, site lint/build, and OpenTelemetry 36/36.
The checksum-
pinned TypeScript and Go harnesses are `READY` and green against the local
snapshot. Both servlet candidate matrices pass 158/158 locally, but their
version-property edits remain uncommitted. ToyStore's completed local migration
passes 13/13, including five MCP tests, while its old manifest pin stays blocked
until a reviewed commit and immutable-candidate/JDK-25 validation. Barebones
compiles, but its live proof remains `UNVERIFIED` because an unrelated Docker
process owns port 8080; the hook fails closed. The current `soklet-otel` and
website migrations are not represented by clean committed pins.
Scheduled/nightly and sustained fuzz-soak history, real multi-node localization
orchestration, public Javadocs, release scans, and immutable candidate
conformance/provenance also remain open. See
[release/README.md](release/README.md) for the exact validator contract and
current fail-closed statuses.
