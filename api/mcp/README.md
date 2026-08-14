# MCP API compatibility and inventories

This directory contains the reviewed, repository-owned evidence for Soklet's
MCP public/protected API. The [Phase 4 freeze rationale](phase-4-freeze-rationale.md)
records the 2026-08-06 decision and the subsequently reviewed wrapper
correction and 2026-08-12 localization host amendment. The external
[Phase 5 API-review checkpoint](../../../mcp/PHASE_5_API_REVIEW_CHECKPOINT_2026-08-08.md)
records review approval for the candidate, and the
[Phase 5 freeze rationale](phase-5-freeze-rationale.md) records its exact
compatibility snapshot. The external
[activation/verification checkpoint](../../../mcp/PHASE_5_ACTIVATION_AND_VERIFICATION_2026-08-08.md)
records the atomic profile activation and fresh official-suite result.

`current-incompatibilities.jsonl` is the canonical set of incompatibilities
between the released `com.soklet:soklet:3.5.1` artifact and the current
3.6.0 source tree. It currently contains 559 records and has
SHA-256
`c0c4b4c68d93e77500b4ffeae07d1cb0bea46bf858c917ef44bbaa6adb61fee4`.
The API-diff gate regenerates the set and compares it in both directions, so
an unexpected addition, removal, or changed record fails.

`phase-0-incompatibilities.jsonl` is the immutable 566-record historical
removal surface from deleting the legacy MCP implementation. It initially
matched the then-current set, but it intentionally does not evolve as the
greenfield implementation reuses legacy names or adds new API.
`phase-0-shared-host-rationales.jsonl` explains every removed MCP-owned member
whose containing public type remains part of Soklet.

## Reviewed ownership

Every current exported MCP type and every shared public/protected host in
scope has exactly one owner:

| Inventory | Entries | Meaning |
| --- | ---: | --- |
| `phase-4.includes` | 133 | frozen Phase 4 types and shared hosts |
| `phase-5.includes` | 39 | frozen Phase 5 types |
| `phase-6.includes` | 33 | Phase 6-owned types; not yet frozen |
| `provisional.includes` | 32 | owner not yet assigned to a frozen phase |
| `non-mcp-public-api.allowlist` | 0 | reviewed unrelated API deltas |

The 237-entry union is sorted, nonoverlapping, and exact. Ownership records
when a type is intended to stabilize; it does not itself freeze the type.
`McpPublicApiInventoryTests` is a fast, independent source/class-tree guard
for exported MCP types, reviewed shared hosts, sorting, overlap, and existence.
It complements the baseline comparison; it is not the authoritative
compatibility inventory.

The authoritative owner inventory comes from the full japicmp report
`target/japicmp/mcp-api-freeze.xml`. It includes:

- every current, non-internal published `Mcp`-named type;
- every current shared host whose public/protected API references an MCP type;
- every other current, non-internal public/protected API delta, which must
  appear in the non-MCP allowlist if it is unrelated to MCP.

The full report is required because a public type or member restored with the
same signature it had in 3.5.1 can be absent from a modified-only report.
`target/japicmp/mcp-api-diff.xml` remains the separate modified-only source for
the canonical incompatibility set. Removed-only containers with no current-side
API do not become current owners, and `com.soklet.internal.*` is excluded from
the ownership inventory.

## Current Phase 5 checkpoint

The local Phase 5 implementation now includes the public MRTR values and
declared outbound `input_required` runtime for tools, prompts, and resource
reads; method-specific embedded-parameter validation; inbound
`inputResponses`; and application- and framework-protected request state.
Framework protection includes authenticated state reopening, operation and
authorization binding, expiry/round checks, and originating-request-ID
evidence. Request-scoped progress and cooperative cancelation are also live on
application handler paths.

Configured endpoints can additionally host framework-owned
`subscriptions/listen` POST/SSE streams for resource-list changes and updates
to requested resource URIs. Application-owned publishers may be in-process or
distributed; Soklet owns admission, filtering, coalescing, stream bounds, and
wire serialization. The checked-in final-tag corpus contains 39
production-derived messages, including progress and subscription exchanges.

Deterministic MRTR termination coverage now includes blocked custom protector
open/seal paths, conditional-capability holds, and independent fresh-ID
branches across shutdown, deadline, and disconnect outcomes. Public listener
tests also prove same-key/same-authorization-partition cross-instance state
continuation and bounded residual-handler shutdown/restart recovery.

The Phase 5 public API is frozen. The bounded cross-feature soak/resource-delta
gate is green: complete Maven smoke runs pass on JDK 21 and JDK 26, the complete
JDK 21 nightly run passes, and the strict verifier requires exactly four
scenarios across three Surefire suites. Sustained/fleet/release-candidate
calibration remains later work. The packaged
fixture and standalone public-API contract cover every Phase 5 scenario row,
and a controlled observation-only run exercised all 39 applicable pinned
scenarios with 147 `SUCCESS`, two exact reviewed `server-stateless` `SKIPPED`,
one reviewed `server-sse-streams-functional` `INFO`, and no bad outcome.
Thirty-six automatic wire successes covered 103 messages, and the prior 23
profiles reproduced exactly. That acquisition was not a profile freeze,
Phase 5 verify pass, API freeze, or release-candidate result. The API snapshot
also does not establish conformance by itself. The later atomic closeout
activated all 39 profiles and passed the fresh exact 39-scenario verify; that
separate evidence is recorded below.

## Active freeze

`frozen-phases` contains the contiguous, sorted prefix of frozen phases. It
currently contains Phase 4 and Phase 5. `phase-4.signatures.jsonl` freezes
1,052 canonical records across all 133 selected owners: 133 classes, 10
constructors, 78 fields, and 831 methods. Its SHA-256 is
`8b5b689525176f63de24d81ce01d26b16b5c27c32c4e5e13f06757d388768bbc`.
`phase-5.signatures.jsonl` freezes 195 canonical records across all 39
selected owners: 39 classes, six constructors, 15 fields, and 135 methods.
Its SHA-256 is
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`.

The snapshot includes a deliberate 2026-08-07 post-freeze correction to Soklet's
unreleased `3.6.0` MCP API: 49 Phase 4 scalar signatures now use non-null
reference wrappers instead of primitives. Five of those corrections restore
the wrapper signatures already present in 3.5.1, so the reviewed baseline
incompatibility set decreased from 561 to 556 records. Regeneration found no
unrelated signature delta; at that correction the Phase 4 snapshot retained
the same 1,049 records and component counts.

A second reviewed amendment on 2026-08-12 adds exactly three descriptors to
frozen Phase 4 hosts: default `McpHandlerInvocation.getFeatures()`, abstract
`McpServer.getLocalizationControl()`, and concrete
`McpServer.Builder.localizer(McpLocalizer)`. The generated snapshot has no
other delta. The one abstract interface method is the sole additional current
source incompatibility; the default interface and concrete builder methods are
compatible additions. The Phase 5 snapshot and nullability digest are
unchanged. See the dated amendment in the
[Phase 4 freeze rationale](phase-4-freeze-rationale.md).

The snapshots protect the complete public/protected signatures of every
selected Phase 4 and Phase 5 owner, including shared hosts. A descriptor on one of those
hosts that names a Phase 5, Phase 6, or provisional type is frozen. The
later-owned type's own members and behavior are not frozen until its owner
phase freezes. Targeted reflection and source-contract tests cover important
details that japicmp does not reliably model, including sealed hierarchies,
public primitive constant values, MCP enum order, record and parameter names,
annotation defaults, exact JSpecify type-use nullability, and thread-safety
markers.

## Current bounded Phase 6 checkpoint

Twenty-one bounded Phase 6 verticals are implemented. The nineteenth is the
downstream-only `soklet-otel` metric migration; the twentieth adds modern
admitted-request spans; the twenty-first adds bounded off-network MCP
simulation. V19 and V20 leave the core owner inventory unchanged; V21 assigns
the shared `Simulator`, seven top-level simulation types, and
`McpSimulationOptions.Builder` to Phase 6.

The separate [MCP localization implementation
plan](../../../mcp/MCP_LOCALIZATION_IMPLEMENTATION_PLAN.md) has completed its
L1 production increment. Eleven top-level localization types and seven nested
owners now provide immutable configuration, request-context SPI, closed
results, revisions, stable text coordinates, catalog extraction, and local
control-plane shapes. Construction-time extraction operates on the final
`McpHandlerResolver`, produces deterministic opaque external keys and
schema-aware response-local slot plans, enforces bounded callback counts, and
preserves the application-owned custom resource-list boundary. The built-in
interceptor continuation now exposes the exact downstream invocation-feature
carrier with its existing thread, one-shot, and call-lifetime rules. L1 does
not invoke a localization provider or alter MCP wire output; request-time
rendering begins in L2. These 18 owners grow `phase-6.includes` to 33 and the
exact reviewed union to 237, but Phase 6 remains unfrozen.
`McpServerDiagnostics` remains
the completed protection and trace diagnostics projection.
`McpServerDiagnostics` now has exactly 12 zero-argument methods: lifecycle
`getStatus()` and `getBoundAddress()`, plus all ten implemented diagnostic
getters. Six use boxed `@NonNull Integer` values:
`getRequestHandlerConcurrency()`, `getRequestHandlerQueueCapacity()`,
`getActiveHandlerExecutions()`, `getQueuedRequests()`,
`getActiveRequestStreams()`, and `getActiveSubscriptions()`. The other four are
`@NonNull McpProtectionMode getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyRingFingerprint()`, and
`getTraceCorrelationConfigurationFingerprint()`; both fingerprint accessors
return non-null `Optional` values with non-null payloads.

Lifecycle, bound address, configured counts, current handler/queue counts, and
the stream/subscription pair form one runtime-owned atomic tuple. Protection
mode, custom-protector presence, production-ring fingerprint, and trace-
configuration fingerprint form a separate security-controls atomic tuple. One
immutable diagnostics result carries both, without claiming one shared global
linearization point. Configured values remain stable before start and across
stop/restart; handler values are bounded server-wide dispatcher counts; and
`0 <= activeSubscriptions <= activeRequestStreams`. A subscription enters both
stream counts once its acknowledgment stream opens, without claiming client
receipt. Retained snapshots never change.

Ordinary, subscription-only, and combined open states report `1/0`, `1/1`,
and `2/1`. Disconnect cleanup moves `2/1` through `1/0` to `0/0`. Completed
clean and residual-handler stops report stream pair `0/0`, even while a
residual handler remains active until actual exit. During internal `FAILED`
cleanup, public residual status may transiently retain `1/1`; completed cleanup
reports `STOPPED` with `0/0`.

Protection mode and the custom-protector flag are fixed at construction and
stable across listener lifecycle; the flag is true exactly for
`CUSTOM_PROTECTOR`. It identifies selection of the custom application-owned
`McpRequestStateProtector`, not an operation's `APPLICATION_PROTECTED` state
mode. The production-ring fingerprint is present exactly in
`PRODUCTION_KEY_RING` mode. The independent trace fingerprint is present
exactly when trace correlation was enabled. Successful live rotations change
only fresh snapshots and persist across listener stop/restart.

Fingerprints are deterministic operational deployment-comparison metadata,
not authentication or token-derivation inputs. Diagnostics expose no raw key
material, key IDs, per-key tags, provider identity, cursors/epochs, or trace
tokens. Equality remains observable and rotations can create high-cardinality
values, so strong operator key entropy, bounded retention, and exclusion from
metric labels and per-request logs remain necessary. This diagnostics vertical
adds no metric family, event type, wire field, label, or other observation
dimension.

The sixth vertical established one context-aware deferred FIFO for the first 16
semantic event variants produced by the runtime: the five handler transitions,
`ServerStopped`, the nine admitted request, stream, subscription, cancelation,
progress, and keep-alive variants, and exact-once `ServerStarted`. The seventh
extended that same FIFO to the 20 variants produced at that checkpoint with
`RequestAccepted`, `RequestRejected`, `ProtocolError`, and
`UnknownMirroredHeader`.

`RequestAccepted` is retained only after successful bounded processor
submission. Executor rejection identity-discards its provisional accepted
entry, then records only `RequestRejected` before the empty 503 response.
Malformed complete requests record accepted, fixed `-32700` protocol error, and
rejected in that order. Strict unknown-header rejection and ignored-header
unresolved-method handling record accepted, one unknown-header event per
occurrence, the applicable fixed protocol error, and rejected in FIFO enqueue
order. Protocol-error production is limited to `-32700`, `-32600`, `-32601`,
`-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998`;
application-owned error codes do not produce this metric event. A fixed error
is recorded only after successful encoding. A streamed `ErrorResponse` keeps
its record provisional until terminal acceptance and discards it if terminal
delivery loses or fails.

Each unknown-header occurrence carries only the endpoint path and a bounded
recognized method or `<unrecognized>`; it carries no header name or value and
no raw unrecognized method. Its occurrence count is independent of the
optional diagnostic-name quota. Pre-admission quartet entries are request-free.
Only an admitted fixed protocol error recorded after request observation may
retain the exact public `McpRequestContext` and originating `Request` for
bounded failure attribution. Pending entries may transiently hold that context
only for delivery and failure logging, and it is never rendered.

Collector callbacks run after the relevant internal locks or monitors are
released. Request-transition deferral is nonwaiting, which preserves liveness
under reentrant collector callbacks. Server-event failures remain request-free,
and all collector failures are contained.

Direct restart orders the old generation's `ServerStopped` before the new
`ServerStarted`; managed startup rollback orders `ServerStarted` before
`ServerStopped`. The FIFO guarantee is metric record/enqueue order only, not a
universal cross-thread causal or per-request total order for independently
racing producers. No public API, diagnostics/snapshot field, aggregate family,
label, event variant, or wire dimension was added.

The eighth vertical extends the same FIFO to all 23 declared variants with
`ConnectionAccepted`, `ConnectionRejected`, and `TransportFailure`.
`ConnectionAccepted` follows operating-system accept and capacity reservation,
before registration or request processing, so a later setup failure may follow
it. `ConnectionRejected` means only that an accepted socket encountered the
configured connection-capacity limit; accept/setup faults instead emit their
typed transport failure without a capacity rejection.

`TransportFailure` is request-free and carries exactly one bounded enum value:
`REQUEST_READ_TIMEOUT`, `REQUEST_TOO_LARGE`, `MALFORMED_REQUEST`, `READ_ERROR`,
`WRITE_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT`, `RESPONSE_READY_ERROR`,
`REQUEST_READ_TIMEOUT_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`,
`ACCEPT_LOOP_ERROR`, `CONNECTION_SETUP_ERROR`, `TASK_ERROR`,
`TIMEOUT_TASK_ERROR`, `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
`EVENT_LOOP_TERMINATED`, or `UNKNOWN`. Neither the event nor its collector-
failure log retains a remote address, raw request/context, throwable, payload,
trace token, or other unbounded dimension. Typed low-level authorities select
the reason without parsing strings.

Typed provisional scopes and a coalescing single-daemon-worker scheduler drain
after transport locks, never synchronously fall back to collector invocation on
a connection thread, and preserve a racing signal across executor rejection.
Blocking lifecycle deferral safely adopts pending delivery. A byte-free idle
close is quiet while a partial request produces `REQUEST_READ_TIMEOUT`;
transport-malformed HTTP stays distinct from complete malformed JSON-RPC. The
request-SSE write-idle winner records one `WRITE_TIMEOUT` before terminals; a
losing/generic close records no `WRITE_TIMEOUT`, and channel cancelation does
not synthesize `WRITE_ERROR`. Fatal `EVENT_LOOP_TERMINATED` is recorded before
stop/wake,
remains scoped through sibling cleanup, and precedes old `ServerStopped` and
new `ServerStarted` before restart returns.

Separate from the first eight production observability and diagnostics
verticals,
a bounded Phase 6 MCP fuzz-registration and hardening checkpoint adds five new
Jazzer methods:
`McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
`McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
`McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
`McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
`McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
This fuzz checkpoint remains unnumbered; at that point the production count
remained eight. Twenty-one checked-in synthetic text seeds cover the new
targets, and the nightly workflow now declares 15 total one-method slots, five
of them new.

The envelope target applies production JSON limits and accepts only classified
success or typed `McpWireDecodingException`, without an unconditional encode
round trip. Mirrored-header decoding applies the production default bound and
only its uniform redacted `IllegalArgumentException`. The Profile 1 target caps
schema/instance input at 64 KiB and requires stage-typed compilation or
production-bounded evaluation outcomes. The cursor target caps input at 64
KiB and cross-checks decoded UTF-8 and raw UTF-16 projections against the JDK
UTF-8 encoder in `REPORT` mode for a derived 1-to-256-byte limit. The request-
state plaintext target fixes its binding, clock, request ID, 4,096-byte bound,
15-minute lifetime, and three-round limit; successful plaintext must re-encode
byte-exactly, rejection remains uniformly redacted, and terminal-LF copying is
limited to 4,097 input bytes. The cursor validator is an internal,
package-private seam shared by incoming and outgoing cursor checks and adds no
public API.

The seeds are synthetic protocol values rather than captured requests,
protected deployment state, secrets, credentials, or raw trace context. No
scheduled or manual coverage-guided nightly run occurred. Deterministic replay
is not sustained, coverage, corpus-saturation, privacy, security,
release-readiness, or Phase 6 freeze proof.

An unnumbered internal trace-correlation derivation checkpoint implements the
frozen token construction. Trace correlation is disabled by default, and
disabled controls capture no token. Enabled controls
snapshot one complete active key ID and key-material pair under the shared
security lock, derive after releasing it with HMAC-SHA-256 over UTF-8
`soklet-mcp-trace-correlation-v1\0` plus the decoded 16-byte trace ID, truncate
to the first 16 digest bytes, and encode an unpadded 22-character Base64URL
token. Invalid and all-zero trace IDs are rejected before derivation; equal
key/trace inputs agree, changed key or trace inputs differ, and concurrent
rotation exposes only coherent old or new `(keyId, token)` pairs. Copied key
material and explicit derivation buffers are zeroed, and the internal carrier
retains only the nonsecret key ID and token while redacting the token from
rendering.

The ninth bounded production vertical now captures one carrier exactly once
for each admitted semantic request before lifecycle and handler observation.
Only a valid MCP `_meta.traceparent` is eligible. Disabled correlation,
invalid or all-zero MCP trace context, absent metadata, and valid physical HTTP
trace without valid MCP metadata all produce no carrier. Lifecycle,
interceptor, handler, and terminal observation share the same immutable
request context and carrier. A pre-rotation request retains its old
`(keyId, token)` through terminal observation, while a fresh post-rotation
request adopts the new pair. Raw validated trace-ID opt-in neither enables nor
changes correlation. The hidden final carrier retains only nonsecret key ID
and token, never raw trace context or key material, and redacts the token from
rendering.

At that point, following the ninth vertical, the prior fuzz and dormant
derivation checkpoints remained unnumbered. `SOK-TRACE-001`, `SOK-TRACE-002`,
and `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` and `SOK-TRACE-005` were
PLANNED; and `SOK-PRIV-001` was PARTIAL. The carrier, accessor, and construction
path are package-private, absent from `phase-6.includes` and public snapshots, and add no public
API or API-sketch source. No structured-log carrier, field, emission point,
cadence, or new `LogEventType` exists; raw trace-ID logging remains
unimplemented. No metric, event, diagnostics/snapshot field, aggregate, label,
or wire dimension was added. Tokens remain pseudonymous high-cardinality
operational metadata, not anonymization, authentication, or authorization
inputs. The carrier is not finish-cleared and has no GC or application-
reference lifetime guarantee; an application-retained context naturally
retains it, while core controls retain only the current key and expose no
history API. This is not comprehensive trace/baggage redaction, cardinality,
privacy/security, aggregate/`AMB-003`, simulator, release-readiness, or Phase 6
freeze evidence.

A third unnumbered Phase 6 checkpoint was covered by
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
The exact sealed inventory remains 23 event records, including 11 fieldless
variants; all other components are endpoint path, bounded method, fixed
outcome/reason/code, or nonnegative duration. Production supplies registered
endpoints, recognized methods or `<unrecognized>`, ten fixed codes, and fixed
enums. Public record constructors still accept arbitrary application-created
nonempty routed strings and non-null codes. At that checkpoint, the snapshot
was three boxed `Long` values plus immutable
`Map<McpShutdownOutcome, Long>`; the default collector aggregated only five
handler variants and `ServerStopped`, ignoring and retaining none of the other
17 variants.

The runtime gate sends 16 sequential admitted requests carrying distinct valid
MCP and HTTP trace IDs, tracestate, baggage, derived tokens, and key canaries.
None appears in built-in MCP event/snapshot state, metric names or labels,
filter-observed samples, Prometheus, OpenMetrics, or reset output. At that
checkpoint, exactly three label-free handler samples plus clean shutdown
appeared before reset; exactly the three label-free samples remained afterward.
The production-vertical count remained nine, and fuzz registration, dormant
derivation, and metric
dimensionality were the three unnumbered checkpoints. `SOK-TRACE-001/002/003`
were COMPLETE; `SOK-TRACE-004` was PLANNED; `SOK-TRACE-005` was PARTIAL
for metric-dimension inventory/default-collector evidence only; and
`SOK-PRIV-001` was PARTIAL. `SOK-METRIC-001` and `SOK-METRIC-004`
remained PARTIAL; `AMB-003` remained AMBIGUOUS.

That checkpoint changed no production source, public API or sketch, owner or
signature inventory, metric family, label, event, or wire behavior. It does
not cover custom collectors; generic HTTP `MetricsCollector` callbacks that
receive a `Request`, request target, or `Throwable`; `LogEvent`, application
callbacks, handler telemetry, or arbitrary application-created event
vocabulary; structured logging or raw-ID emission; future aggregates;
comprehensive trace/baggage redaction; sustained cardinality, fuzz, or soak;
simulator, migration, release-candidate provenance, review, or Phase 6 freeze.

Transport aggregation is the tenth production vertical, server-start
aggregation is the eleventh, and request-boundary aggregation is the twelfth;
the three earlier checkpoints remain unnumbered.
At the tenth checkpoint, `McpMetricsSnapshot` declared seven getters and its
builder eight public methods including `build()`.
The three additive getter/builder pairs expose boxed accepted and rejected
connection counts plus an immutable sparse
`Map<MetricsCollector.TransportFailureReason, Long>`. This is provisional
Phase 6 public API; the frozen Phase 4/5 inventories and hashes are unchanged.

At that checkpoint the default collector aggregated nine variants; 14 variants
were still ignored.
It renders label-free `soklet_mcp_connections_accepted_total` and
`soklet_mcp_connections_rejected_total`, including configured or event-
activated zeros, and merges MCP failure samples into the existing
`soklet_transport_failures_total` family with only `server_type="MCP"` and a
fixed reason. These make seven implemented rendered aggregate families. HTTP,
SSE, and MCP share one HELP/TYPE block; an all-rejecting
sample filter suppresses the block. Reset clears cumulative transport values
without mutating retained snapshots. The 16-request gate consequently has six
MCP-prefixed samples before reset and five after reset, while its sparse
failure map stays empty and trace canaries remain absent.
The exact transport gates are
`McpTransportMetricsAggregationTests#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
`#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
`#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
`#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The eleventh vertical added boxed, nonnegative `getServerStarts()` and
`serverStarts(Long)`. At that checkpoint the provisional snapshot had eight getters and its
builder nine public methods including `build()`: six boxed `Long` values and
two immutable maps. `DefaultMetricsCollector` consumes the existing fieldless
`ServerStarted` event under its exact lifecycle authority—one per successful
listener generation, none for failed staged or already-started no-op attempts,
the successful start before rollback stop, and one for each fresh restart.
Configured collectors render label-free `soklet_mcp_server_starts_total` at
zero. A direct `ServerStarted` or `ServerStopped` activates the lifecycle
subset, so a stop-only collector renders zero starts plus shutdown. Filtering
the start sample suppresses its HELP/TYPE block; reset clears the cumulative
count while preserving zero-family visibility; retained snapshots are
immutable. Start and shutdown totals are not complementary or conserved while
a generation remains running.

The fieldless event and label-free family retain no request, remote identity,
endpoint, method, outcome, throwable, header, trace ID, token, key, tracestate,
baggage, or application label. At that checkpoint the default collector
aggregated 10 of 23 variants and ignored 13, with eight rendered families. The 16-request gate had
seven MCP-prefixed samples before reset and six after. Exact coverage is
`McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
`#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
and
`#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The twelfth vertical added boxed, nonnegative `getRequestsAccepted()` and
`getRequestsRejected()` with matching `requestsAccepted(Long)` and
`requestsRejected(Long)`. At that checkpoint the provisional snapshot had ten
getters and 11 public builder methods including `build()`: eight boxed `Long`
values and two immutable maps.

`RequestAccepted` becomes durable only after the bounded processor accepts
`Executor.execute`; rejection or throw identity-discards the provisional
accepted entry. `RequestRejected` is exact once for a complete Handler request
whose terminal wins before atomic observation-start reservation. A terminal
pre-admission path can produce both, while execute failure can produce rejected
without retained accepted. These are independent request-boundary counts, not
complements or a conservation equation, and exclude early transport/Microhttp,
post-admission outcome, and handler-capacity rejection.

Configured collectors and either direct event activate paired label-free
families: `soklet_mcp_requests_accepted_total` with HELP `Total MCP requests
accepted by the bounded protocol processor`, and
`soklet_mcp_requests_rejected_total` with HELP `Total MCP requests rejected
before admitted semantic handling`. Both render zero when unobserved. Filtering
removes rejected family metadata with its sample; reset clears cumulative
counts but retains configured/event-activated paired visibility. OpenMetrics,
retained immutable snapshots, and post-quiescence concurrent ingest are
covered by
`McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
`#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
and
`#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Exact producer authority is also covered by
`McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
and
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.

The thirteenth vertical implements admitted-request lifecycle aggregation.
Boxed, nonnegative `getActiveRequests()`, immutable `getRequests()` and
`getRequestDurations()` maps, and matching builder methods expand the
provisional snapshot to 13 getters and 14 public builder methods including
`build()`: nine boxed `Long` values and four maps. The new public, thread-safe
`McpMetricsSnapshot.RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome)`
rejects nulls and empty routed strings but does not validate registry
membership. Built-in keys contain only a registered endpoint, recognized
method or `<unrecognized>`, and fixed outcome; count and histogram maps are
independently sparse.

Exact `RequestStarted`/`RequestFinished` delivery drives the active gauge
`soklet_mcp_requests_active`, completed counter `soklet_mcp_requests_total`,
and `soklet_mcp_request_duration_nanos` histogram. Completed samples use only
`endpoint`, `method`, and lower-snake `outcome`; histogram boundaries are 1, 2,
5, 10, 25, 50, 100, 200, 400, 800, 1,500, 3,000, 7,000, and 15,000
milliseconds plus overflow. No standalone start/finish counters exist.
Configured empty state renders only gauge zero; sparse families and HELP/TYPE
metadata remain absent when empty or fully filtered. Reset preserves active
state, clears completed maps/histograms, and a request crossing reset records
its full original duration. Retained snapshots are immutable; balanced
post-quiescence concurrent ingest is lossless.

No request/network identity, raw unrecognized method, error detail, throwable,
header, trace/token/key, tracestate, baggage, or application telemetry enters
these built-in dimensions. This does not constrain custom collectors, generic
HTTP metrics callbacks, logs, application-created events/keys, or telemetry;
promise cross-field atomicity during mutation; or repair unmatched manual
events. Exact tests are
`McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
`#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
`#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Authority/cardinality tests include
`McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
`#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
`#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.

The fourteenth vertical implements request-stream lifecycle aggregation.
Boxed, nonnegative `getActiveRequestStreams()`, immutable
`getRequestStreamDurations()`, and matching builders expand the provisional
snapshot to 15 getters and 16 public builder methods including `build()`: ten
boxed `Long` values and five maps. The public, thread-safe
`McpMetricsSnapshot.RequestStreamTerminationKey(endpointPath, jsonRpcMethod,
reason)` rejects null/empty shape but does not validate registry membership.

Exact `RequestStreamOpened`/`RequestStreamClosed` delivery drives
`soklet_mcp_request_streams_active` with HELP `Currently active MCP request
streams` and `soklet_mcp_request_stream_duration_nanos` with HELP `MCP
request-stream duration in nanoseconds`. The transition records open before
accepted progress/keepalive observations and the single close before terminal
`RequestFinished`; this is FIFO record/enqueue order, not a universal
cross-thread total order. Samples use bounded `endpoint`,
`method`, and lower-snake `reason`: `completed`, `client_disconnected`,
`request_canceled`, `deadline_exceeded`, `write_failed`, `backpressure`,
`server_stopped`, `simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`. The
13 inclusive buckets are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600,
7,200, and 14,400 seconds plus overflow. No standalone open/close counters
exist.

Configured collectors and either direct event activate gauge-zero visibility;
the duration family stays sparse and emits no orphan HELP/TYPE metadata when
empty or fully filtered. Prometheus/OpenMetrics filtering, reset preserving
the live gauge while clearing histograms, full duration across reset, immutable
retained snapshots, and balanced post-quiescence concurrent ingest are covered
by
`McpRequestStreamLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies`,
`#resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
and
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`.

Built-in keys retain only registered endpoint, recognized method or
`<unrecognized>`, and fixed reason. No request/network identity, error detail,
throwable, header, trace/token/key, tracestate, baggage, or application
telemetry enters these dimensions. This does not constrain custom collectors,
generic HTTP/SSE metrics, logs, application-created events/keys, or telemetry;
promise cross-field or concurrent-reset atomicity, repair unmatched manual
events, equate metrics with diagnostics, expose subscription breakdown,
promise canonical order, add OpenTelemetry/trace emission, or prove sustained,
simulator, privacy, release-readiness, or Phase 6 freeze.

The fifteenth vertical implements subscription lifecycle aggregation. Boxed,
nonnegative `getActiveSubscriptions()`, immutable
`getSubscriptionDurations()`, and matching builders expand the provisional
snapshot to 17 getters and 18 public builder methods including `build()`: 11
boxed `Long` values and six maps. The public, thread-safe
`SubscriptionTerminationKey(endpointPath, reason)` rejects null/empty shape but
does not validate registry membership.

Exact `SubscriptionOpened`/`SubscriptionClosed` delivery drives
`soklet_mcp_subscriptions_active` with HELP `Currently active MCP subscriptions`
and `soklet_mcp_subscription_duration_nanos` with HELP `MCP subscription
duration in nanoseconds`. Samples use bounded `endpoint` and lower-snake
`reason`: `completed`, `client_disconnected`, `request_canceled`,
`deadline_exceeded`, `write_failed`, `backpressure`, `server_stopped`,
`simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`. The 13 buckets
are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400 seconds
plus overflow; there are no standalone open/close counters.

Produced FIFO order is `RequestStreamOpened`, `SubscriptionOpened`, then at
termination `RequestStreamClosed`, `SubscriptionClosed`, and
`RequestFinished`; it is not universal cross-thread ordering or an atomic
relationship between gauges. Configured/direct zero visibility, sparse
no-orphan metadata, Prometheus/OpenMetrics filtering, reset preserving the
gauge while clearing histograms, full duration across reset, retained
immutability, and balanced post-quiescence concurrency are covered by
`McpSubscriptionLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableSubscriptionLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersSubscriptionLifecycleFamilies`,
`#resetPreservesActiveSubscriptionsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedSubscriptionLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

Built-in keys retain only registered endpoint and fixed reason—never method,
resource URI, filter, request/network identity, error detail, throwable,
header, trace/token/key, tracestate, baggage, or application telemetry. This
does not constrain custom collectors, generic HTTP/SSE metrics, logs,
application-created events/keys, or telemetry; promise cross-field or
concurrent-reset atomicity, repair unmatched manual events, equate metrics with
diagnostics, promise canonical order or conservation with stream gauges, add
OpenTelemetry/trace emission, or prove sustained, simulator, comprehensive
privacy, release-readiness, or Phase 6 freeze.

The sixteenth vertical implements independent progress and
cooperative-cancelation aggregation. Immutable
`Map<EndpointMethodKey, Long> getCancelationsSignaled()` and
`getProgressEmitted()` plus matching builders expand the provisional snapshot
to 19 getters and 20 public builder methods including `build()`: 11 boxed
`Long` values and eight maps. The public, thread-safe
`EndpointMethodKey(endpointPath, jsonRpcMethod)` rejects null/empty shape but
accepts arbitrary nonempty application-created values.

Exact delivered `CancelationSignaled` drives
`soklet_mcp_cancelations_signaled_total{endpoint,method}` with HELP `Total
cooperative MCP request cancelations signaled by endpoint and method`; exact
`ProgressEmitted` drives
`soklet_mcp_progress_emitted_total{endpoint,method}` with HELP `Total MCP
progress notifications accepted for delivery by endpoint and method`. They are
independent counters, not complements or a conservation equation. Configured
empty state emits neither samples nor metadata, direct events populate only
their own sparse family, all-rejected filters leave no orphan HELP/TYPE block,
OpenMetrics retains one EOF, and reset clears both maps. Defensive copies,
explicit application zeros, retained immutability, and post-quiescence
concurrent losslessness do not imply cross-map atomicity.

Live authority in
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
proves two accepted progress events, one cooperative-cancelation event,
serialized delivery outside the reporter monitor, and no post-cancel progress;
it does not impose universal cross-thread terminal order. Built-in labels
contain only registered endpoint and bounded method—never progress
token/value/total/message, cancelation reason, request/network identity,
throwable, header, trace ID/token/key material, tracestate, baggage, or
application telemetry. Exact tests are
`McpProgressAndCancelationMetricsAggregationTests#snapshotContractUsesSharedImmutableEndpointMethodCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies`,
`#resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata`,
and
`#concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs,
application-created events/keys, or telemetry; prove coverage of every live
cancelation cause, canonical order, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator or release evidence, or Phase 6
freeze.

The seventeenth vertical implements fieldless keep-alive aggregation. Boxed,
nonnegative `@NonNull Long getKeepAlivesEmitted()` and matching
`keepAlivesEmitted(Long)` expand the provisional snapshot to 20 getters and 21
public builder methods including `build()`: 12 boxed `Long` values and eight
immutable maps.

Each exact FIFO-delivered `KeepAliveEmitted` drives the label-free
`soklet_mcp_keep_alives_emitted_total` counter with HELP `Total MCP keep-alive
comments accepted for delivery`. Configured MCP and direct events activate the
family; configured and post-reset states render zero. Filters receive an empty
label map, full rejection leaves no sample or orphan HELP/TYPE metadata,
OpenMetrics emits one EOF, reset clears the count while retaining visibility,
retained snapshots remain immutable, and post-quiescence concurrent direct
ingest is lossless.

Live authority is bounded by
`McpSubscriptionPublicRuntimeTests#keepAliveAcceptanceSharesStreamTransitionWithCloseObservation`
and
`McpSubscriptionRuntimeBoundaryTests#maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents`.
They freeze accepted wire-observation/transition order and an exact-one
deterministic boundary, not timer attempts or client/intermediary receipt; no
conservation with subscriptions, streams, or terminal events is claimed. The
fieldless built-in event retains no request, endpoint, method, remote identity,
duration, reason, throwable, header, trace ID/token/key, tracestate, baggage,
or application label. Exact tests are
`McpKeepAliveMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeKeepAliveCount`,
`#defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset`,
and
`#concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs, or
application telemetry; promise universal cross-thread order, delivery/receipt,
cross-field or concurrent-reset atomicity, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator or release evidence, or Phase 6
freeze.

The eighteenth bounded Phase 6 production vertical completes the provisional
core snapshot aggregate surface with immutable
`Map<Integer, Long> getProtocolErrors()` and
`Map<EndpointMethodKey, Long> getUnknownMirroredHeaders()`, plus matching
`protocolErrors(Map)` and `unknownMirroredHeaders(Map)` builder methods. The
surface now has 22 getters and 23 public builder methods including `build()`:
12 boxed `Long` values and ten maps. No new public owner is introduced, so the
32-entry provisional inventory and 210-owner reviewed union are unchanged. The
three fuzz, dormant-derivation, and metric-dimensionality checkpoints remain
unnumbered.

The default text families are
`soklet_mcp_protocol_errors_total{code}` with HELP `Total client-visible MCP
protocol errors by fixed code` and
`soklet_mcp_unknown_mirrored_headers_total{endpoint,method}` with HELP `Total
unknown MCP mirrored-header occurrences by endpoint and method`. The two maps
are independent and sparse: configuration alone emits no family metadata, a
direct event affects only its own map, fully rejected filters leave no orphan
HELP/TYPE, OpenMetrics retains one EOF, and reset removes all samples and
metadata. Built snapshots are defensive and immutable and preserve explicit
zero counts.

Framework production uses exactly `-32700`, `-32600`, `-32601`, `-32602`,
`-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998` after successful
client-visible encoding or accepted streamed-terminal reservation. Failed
provisional terminal entries, application codes, tool-result `isError`, and
empty-notification HTTP errors are excluded. Unknown headers contribute once
per occurrence under IGNORE and REJECT and use only registered endpoint and a
recognized core method or `<unrecognized>`, never header name/value or raw
unrecognized method. Pre-admission errors are request-free; admitted fixed
errors use the exact admitted context only for bounded delivery/failure
attribution.

The two default maps independently retain at most 8,192 dimensions. Public
builder maps are uncapped value carriers and accept arbitrary non-null Integer
codes and shape-valid nonempty `EndpointMethodKey` values with nonnegative
counts, including explicit zero. Protocol maps iterate in natural Integer
order; no canonical order is promised for EndpointMethodKey maps. The exact
ten codes and bounded live method vocabulary therefore do not constrain
arbitrary public/manual construction.

Exact aggregate coverage is
`McpProtocolAndUnknownHeaderMetricsAggregationTests#snapshotContractUsesImmutableProtocolAndUnknownHeaderCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProtocolAndUnknownHeaderFamilies`,
`#resetClearsSparseProtocolAndUnknownHeaderCountersWithoutLeavingFamilyMetadata`,
`#manualDimensionRetentionIsIndependentlyBoundedPerFamily`, and
`#concurrentDirectProtocolAndUnknownHeaderIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`,
`#applicationCodesAreExcludedWhileAdmittedFixedErrorsRetainExactRequestContext`,
`#unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies`,
`#preAdmissionQuartetDeliveryIsReentrantAndSerializedWithoutCrossRequestOrderClaim`,
`McpHttpServerApplicationExecutionTests#produced_protocol_error_metric_allowlist_is_exact_and_excludes_application_codes`,
and `#failed_stream_terminal_discards_provisional_protocol_error_metric`.

Built-in dimensions contain no header identity, request, throwable, payload,
remote identity, trace ID/token/key material, tracestate, baggage, or generic
application label. Accepted/unknown/error/rejected and admitted
started/error/finished sequences promise FIFO record/enqueue order only. This
does not constrain arbitrary manual vocabulary, custom collectors, generic
HTTP/log/Request/Throwable/application telemetry, or structured/raw-ID
emission; implement downstream OpenTelemetry mapping; prove sustained, soak,
simulator or release-candidate behavior; or freeze Phase 6.

The nineteenth bounded Phase 6 production vertical is downstream-only: it
changes no core API owner, signature, snapshot, sketch, event, label, canary,
or wire inventory. `soklet-otel:1.4.0-SNAPSHOT`, using
`soklet:3.6.0-SNAPSHOT` by default, adds
`didRecordMcpMetricsEvent(McpMetricsEvent)` and maps all 23 variants to exactly
22 instruments—21 MCP-specific instruments plus shared transport failures.
The existing core ledgers therefore remain 22 snapshot getters, 23 public
builder methods including `build()`, 12 boxed `Long` values, ten maps, 32
provisional owners, and a 210-owner reviewed union.

The downstream schema uses seven fixed MCP attributes and the shared
transport type/reason pair, exact lower-snake enum values, and 14 request plus
12 long-lived finite duration bucket boundaries in seconds. For framework-
produced events, the integration adds no dedicated attributes for trace/raw
request IDs, progress token/value/message, mirrored-header identity, request
objects, throwables, operation/resource URIs, principal/address, tracestate,
baggage, or generic bags. Framework-generated vocabulary remains bounded;
arbitrary valid values in direct application-created events may contain
sensitive text, so applications own their confidentiality and cardinality.
OpenTelemetry SDK series retention is not the core default collector's
8,192-entry policy.

At the V19 boundary, this sibling-artifact migration deliberately removed all obsolete MCP
request/session/SSE tracing callbacks, span-policy knobs, and span-naming
methods. The reviewed downstream public comparison then contained exactly 15
removed legacy methods and one added metrics callback. It also removed the
four legacy MCP session instruments and their endpoint-class,
session-termination/identity, and request-ID-presence attributes. Modern MCP
lifecycle callbacks then remained inherited no-ops; no replacement spans were added.
Consumers of the former 1.3.1/3.5.1 MCP tracing surface must migrate
deliberately, while HTTP/SSE telemetry remains supported.

Exact downstream tests are
`OpenTelemetryMetricsCollectorTests#allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions`,
`#mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets`,
`#mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes`,
`#mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary`,
`#handlesConcurrentMcpMetricEventsWithoutLoss`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
At that point, the complete downstream suite passed 28/0/0/0 on both JDK 21
and JDK 26.
`AMB-003` is now RESOLVED CONTRACT 2026-08-10 / CORE IMPLEMENTATION COMPLETE /
DOWNSTREAM METRIC IMPLEMENTATION COMPLETE. Snapshot/reset/filter/OpenMetrics
parity, SDK retention caps, modern MCP spans, structured logs, sustained
cardinality, simulator/release evidence, and Phase 6 freeze remain outside
that V19 result. Modern `McpRequestContext` span semantics were the next
contract slice.

The twentieth bounded production vertical implements those admitted-request
spans in the same unreleased `soklet-otel:1.4.0-SNAPSHOT` against
`soklet:3.6.0-SNAPSHOT`. Boxed `recordMcpRequestSpans` policy defaults true;
the additive context-shaped default naming method preserves existing
three-method strategies. Default name and `rpc.method` expose only the exact
ten core methods or `<unrecognized>`, never an original raw unsupported method.
Custom naming receives the full context and remains application-owned.

One SERVER span covers each admitted request/notification through stream or
subscription lifetime to exact terminal observation. Parentage uses only
validated MCP `_meta.traceparent`/`tracestate`; HTTP headers, ambient context,
and baggage do not backfill it. Start attributes are MCP server type, JSON-RPC
system, bounded method, and endpoint. Physical client address and Soklet
request ID remain off-by-default opt-ins; the latter never uses JSON-RPC ID.

Finish always records lower-snake outcome. A JSON-RPC error writes its decimal
code as string response status and `error.type` and marks ERROR. Without an
error, six fixed error outcomes mark ERROR; complete/input-required/canceled/
client-disconnected remain UNSET. Throwables create no event or material.
Duration overflow uses a plain-end fallback. Disabled policy, missing/late
finish, duplicate direct starts, close drain, concurrent publication/close,
telemetry failure containment, and concurrent context isolation are covered.

Built-in spans exclude JSON-RPC ID, metadata, operation/path/capability/
admission data, baggage, HTTP trace fallback, error message/data, throwable,
and exception events apart from intended MCP parentage and explicit physical
address/request-ID opt-ins. No legacy session/stream/subscription span,
custom-namer safety, structured/raw-ID emission, comprehensive privacy,
sustained cardinality, simulator/release, or Phase 6 freeze is claimed.

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

V20 adds five declared downstream methods relative to V19; the reviewed
`1.3.1` to current diff is 13 removals and four additions. Core inventories
remain 23/23 variants, 22 families, 22 snapshot getters, 23 builder methods,
12 `Long` values, ten maps, 31/12 canary samples, 32 provisional owners, and
210 reviewed owners. `MCP-BASE-026` is COMPLETE. `AMB-003` remains resolved/
core-complete/downstream-metric-complete; metric-only `SOK-TRACE-005`,
`SOK-PRIV-001`, `SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL;
`SOK-TRACE-004` remains PLANNED. At that V20 boundary, MCP simulator
integration was next.

The twenty-first bounded production vertical assigns the shared public
`Simulator` host to Phase 6 and adds its two abstract
`startMcpRequest(Request)` and
`startMcpRequest(Request, McpSimulationOptions)` methods. The seven top-level
public types are `McpSimulation`, `McpSimulationOptions`,
`McpSimulationResponse`, `McpSimulationCompletion`,
`McpSimulationStreamItem`, `McpSimulationBodyMode`, and
`McpSimulationStreamItemType`; the eighth new owner is
`McpSimulationOptions.Builder`. The reflection gate freezes exact reference
nullability, enum order, record-free interface shapes, method names, parameter
names, defaults, and thread-safety markers.

The API represents an asynchronous off-network MCP POST. Default options bound
pending SSE capture to 128 items and cumulative response/frame capture to
10,485,760 bytes. The simulation handle provides repeatable response and
completion waits, destructive FIFO item reads, boxed `isComplete()`, and
idempotent cancel/close. Response, item, and completion collections are
immutable; returned body/frame arrays are defensive copies. Completion retains
the exact public stream reason, an optional terminal message, and an immutable
ordered list of the same Throwable identities.

The production seam uses the real request processor and lifecycle but no live
listener. Public status stays `STOPPED`, bound address and diagnostics remain
empty/zero, and no server/connection/transport event is produced. Caller Host,
Origin, headers, and body are not repaired; configured port `0` requires a
literal `:0` Host authority. Item capacity precedes cumulative bytes, equality
is allowed, dequeuing refunds only a slot, terminal JSON is one counted item
and one no-extra-cost completion reference, and exact JSON/SSE overflow retains
the response head while excluding the offending bytes.

Cancel, close, and scope exit use the existing request terminal reservation and
publish `CLIENT_DISCONNECTED` only when they win. Bounded residual cleanup,
suppression, restart blocking, overflow-safe waits, interruption without
cancelation, MRTR continuation, subscription replay, and cancel-versus-terminal
first-winner behavior are production-tested. These semantics do not make the
public Request, headers/body, or retained Throwables non-sensitive; disclosure
remains application-owned.

Representative exact citations from the full 46-test simulator/API gate are
`McpSimulationPublicApiTests#simulationSurfaceHasExactReferenceNullabilityAndClosedEnums`,
`McpPublicApiReflectionContractTests#phaseSixInventoryAndSharedHostDescriptorsAreExact`,
`McpSimulatorPublicRuntimeTests#startMcpRequestRejectsMissingServerConfiguration`,
`#defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero`,
`#multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest`,
`#subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder`,
`#mcpSimulationCompletionRetainsStreamCaptureFailures`,
`#noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression`,
`#waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently`, and
`McpSimulationCaptureRuntimeTests#cancelAndTerminalRacePublishesOneCoherentFirstWinner`.

At the V21 boundary, `phase-6.includes` had 15 owners,
`provisional.includes` had 32, and the reviewed union had 219. The canonical
comparison had 558 records and
SHA-256
`d40004fa92cc5d095404de2133cf04fcd2b5574e9326eb680f571a017ef33671`.
At that boundary, frozen Phase 4/5 inventories remained 1,049/195 with
unchanged hashes. The core
metric surface is unchanged at 23/23 events, 22 families, 22 snapshot getters,
23 builder methods, 12 boxed `Long` values plus ten maps, and 31/12 canary
samples.

At the V21 boundary, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE but did not yet claim every public simulator operation,
the 39-scenario suite through
simulation, live-network fidelity, stress/soak, sustained fuzz, comprehensive
privacy/security, release provenance, or Phase 6 freeze. Other statuses remain
unchanged. The first complete release-workflow dry run and the remaining
sustained, review, and freeze gates are next.

**Fourth unnumbered Phase 6 every-operation simulator, bounded capture-fuzz,
and off-network soak hardening checkpoint.** It hardens V21 without changing any public
descriptor, API sketch, owner inventory, comparison record, metric/event/
snapshot surface, wire contract, or numbered production vertical.
`McpSimulatorEveryOperationTests#recognizedRequestMethodsReplayExactJsonOrSseShapes`
reports nine request cases, while
`#cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation`
and `#concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained` cover the
ignored compatibility notification and deterministic concurrent isolation.
The exact 11-case class and six-class 57/0/0/0 selector freeze status, header
order, canonical JSON/SSE, same-context lifecycle, metrics, `STOPPED`
diagnostics, and no server/connection/transport events.

Internal capture-state-machine-only fuzz coverage comes from the methods
`McpSimulationCaptureFuzzTest#captureStateMachineRemainsBoundedTerminalAndIdempotent`
and `#curatedSeedsReachJsonSseLimitCancelAndCompletionBranches`, which add bounded
state-machine replay with six ASCII seeds: `json-complete.actions`,
`sse-terminal.actions`, `item-limit.actions`, `byte-limit.actions`,
`cancel.actions`, and `duplicate-terminal.actions`. The bounds are 65,536 input
bytes, 64 actions, 256 payload bytes, 16 pending items, and 4,096 captured
bytes. Focused replay passes 8/0/0/0; deterministic full replay passes
135/0/0/0 across 16 methods, 15 classes, and 27 MCP seeds. A five-second
coverage-guided launch was host-blocked before target execution and supplies no
coverage result; the declared `maxDuration=2m` is a registration bound, not
executed-run evidence.

`McpCrossFeatureSoakTests#mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup`
runs 24 fixed cycles over eight cases repeated three times with item/byte bounds
4/4,096 and one residual wave. It balances request/stream/subscription/handler
counts at 38/38, 24/24, 4/4, and 34/34, with residual 1, transport 0, listener
lifecycle 0, and final `STOPPED`. The JDK 26 smoke profile passes 5/0/0/0 across
three suites/five scenarios, its verifier SHA-256 is
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

At that fourth checkpoint, the ledger remained 21 numbered production
verticals and had four unnumbered checkpoints. `SOK-SIM-001` remains COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE and now includes deterministic every-operation
evidence. This is not the strict local 39-scenario driver, every parameter/
error permutation, live-network fidelity, scheduled/manual or sustained
coverage-guided fuzz, corpus saturation, long/fleet soak, comprehensive
privacy/security, release provenance, or Phase 6 review/freeze.
`SOK-VALID-002` and `SOK-PRIV-001` advance narrowly but remain PARTIAL; all
other statuses remained unchanged. The next slice was a strict 39-row LOCAL
off-network driver tied byte-for-row and name-for-name to the pinned
`CLI/scenarios.json` manifest ordinal order; it was not the official CLI or a
live-network run.

**Fifth unnumbered Phase 6 candidate-artifact/public-API-only local 39-row
simulator-driver checkpoint.** `conformance/official/run-local-simulator.mjs`
validates and follows pinned `CLI/scenarios.json` manifest ordinal order for
the exact 39 active `RUN` rows at ordinals 1 and 3 through 40. Using only the
compiled fixture classes and candidate JAR, it invokes
`McpLocalSimulatorScenarioDriver#runManifestRowsOffNetwork`. Each ordinal/name
pair gets a fresh scenario configuration and simulator scope and performs
bounded public-API work. The package-private fixture source symbol
`McpConformanceFixture#simulationConfigForScenario` supplies the registrations
without extending the public or production surface.

The wrapper byte-compares exactly one
`PASS\t<ordinal>\t<name>\n` record per row in manifest ordinal order and
requires empty standard error and a clean exit. On Corretto 21 and 26, the
fixture and driver compile with `--release 17 -Xlint:all -Werror`, the fixture
contract main passes, `jdeps` finds no `com.soklet.internal` dependency, and
all 39 rows pass. The adversarial
`conformance/official/local-simulator-self-test.mjs` rejects reordered,
duplicate, missing, failed-spawn, nonzero-exit, signaled, standard-error,
wrong-output, `FAIL`, CRLF, and unterminated transcripts.

No production source, public API or sketch, owner/signature inventory,
metric/event/snapshot surface, wire behavior, or numbered vertical changes.
At that fifth checkpoint, the ledger was 21 numbered production verticals plus
five unnumbered checkpoints. The API comparison remained 558 records with the
same hash and had 15 Phase 6 owners, 32 provisional owners, and a 219-owner
reviewed union. The
23/23-event, 22-family, 22-getter/23-builder, and 31/12-canary surfaces remain
unchanged. `SOK-SIM-001` stays COMPLETE BOUNDED PHASE 6 IMPLEMENTATION
EVIDENCE; all other status rows stay unchanged.

This is not the official CLI or an official expected-check multiset replay,
and it opens no live network path. It does not prove listener/kernel behavior,
socket backpressure or write-idle handling, release provenance, sustained
operation, comprehensive privacy/security, or Phase 6 review/freeze. Next are
scheduled coverage-guided fuzz and sustained soak/stress gates, followed by
structured-log, privacy, and API review/freeze work.

The fieldless request-boundary events and label-free families retain no request,
remote identity, endpoint, method, code, outcome, throwable, header, trace ID,
token, key, tracestate, baggage, or application label. With the eighteenth
vertical, the default collector aggregates the full 23/23 variants across 22 text
families, leaving zero core variants unaggregated. The nonsubscription
16-request gate remains exactly 31 MCP-prefixed samples before reset and 12
after reset because the final two map families are sparse on that clean path.

The rest of the resolved contract defines bounded scalar, live-gauge,
endpoint/method/outcome/reason/code map, and duration-histogram families, with
no standalone start/finish/open/close counters and no unknown-header identity.
Configured scalars render zero; maps/histograms are sparse; reset preserves
five live gauges while clearing cumulative state. The authoritative Phase
6/V10 mapping is now implemented downstream without changing this core
surface.
`SOK-TRACE-005` remains PARTIAL for metric-only evidence; `SOK-PRIV-001`,
`SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL. `SOK-METRIC-002`,
`SOK-METRIC-003`, and `SOK-SHUT-002` remain COMPLETE. `AMB-003` is RESOLVED
CONTRACT 2026-08-10 / CORE IMPLEMENTATION COMPLETE / DOWNSTREAM METRIC
IMPLEMENTATION COMPLETE.
`MCP-BASE-026` is COMPLETE. `SOK-TRACE-004` remains PLANNED, while
`MCP-HTTP-020` remains PARTIAL.
This does not constrain custom collectors or application telemetry, promise an
atomic cross-field snapshot during active concurrent mutation, add structured-
log or raw-ID emission, complete privacy/cardinality work, or prove every
parameter/error variant, sustained operation,
release-readiness, review, or Phase 6 freeze.

These are FIFO record/enqueue-order guarantees, not a universal cross-thread
causal total order. Default aggregation now covers `ServerStarted`,
`ServerStopped`, `RequestAccepted`, `RequestRejected`, `RequestStarted`,
`RequestFinished`, `RequestStreamOpened`, `RequestStreamClosed`, the five
handler variants, `SubscriptionOpened`, `SubscriptionClosed`,
`CancelationSignaled`, `ProgressEmitted`, `KeepAliveEmitted`, `ProtocolError`,
`UnknownMirroredHeader`, and the transport trio. Other downstream work,
structured-log carrier/emission, raw-ID opt-in, broader privacy, sustained
cardinality, and redaction work, coverage-guided and sustained fuzz gates,
release-candidate work, and Phase 6 review/freeze remain open. The seventh
through ninth verticals added no public API, snapshot field, aggregate family,
label, event variant, or wire dimension. The tenth added three provisional
getter/builder pairs, the eleventh adds one, the twelfth adds two, the
thirteenth adds three plus `RequestOutcomeKey`, the fourteenth adds two plus
`RequestStreamTerminationKey`, the fifteenth adds two plus
`SubscriptionTerminationKey`, and the sixteenth adds two plus
`EndpointMethodKey`; the seventeenth adds one provisional getter/builder pair;
and the eighteenth adds two provisional map getter/builder pairs.
The nineteenth downstream vertical adds one `soklet-otel` callback while
changing no core event variant, snapshot member, owner, label, or wire
dimension.
The twentieth downstream vertical adds five declared methods relative to V19
while likewise changing no core inventory.
The twenty-first adds seven top-level simulation types,
`McpSimulationOptions.Builder`, and two abstract `Simulator` methods while
leaving the metric/snapshot/canary inventories unchanged.

This checkpoint does not freeze Phase 6. `phase-6.includes` remains outside
`frozen-phases`; all Phase 6-owned and provisional surfaces remain unfrozen.

## Running the gates

Run the aggregate compatibility, ownership, and freeze gate with:

```sh
scripts/verify-mcp-api-freezes.sh
```

The aggregate first runs `scripts/api-diff/verify.sh`, verifies the exact owner
union from the full report, and then regenerates and bidirectionally compares
the signature snapshot for every phase named by `frozen-phases`. Generated
evidence is written under `target/japicmp/` and
`target/mcp-api-freezes/`. Neither script updates a reviewed file.

CI runs the aggregate on JDK 17; the scripts themselves use the
caller-selected JDK. On the exact current source, the aggregate gate is green
for 559 incompatibility records and 237 reviewed current-side API owners. The
32-entry provisional inventory includes `EndpointMethodKey`,
`RequestOutcomeKey`, `RequestStreamTerminationKey`, and
`SubscriptionTerminationKey`; the unchanged
frozen inventories
contain 1,052 Phase 4 signatures and 195 Phase 5 signatures. The Phase 5
snapshot contains 195 records (39 classes, six constructors, 15 fields, and
135 methods), with
SHA-256
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`;
its exact nullability digest is
`d52a424ac33e679e0a0632004ac931e59966b68641659e254214964d9144f8c7`.
The Phase 5 count and hash are unchanged. Phase 6 now owns 33 types and the
provisional inventory remains at 32. The amended Phase 4 snapshot has SHA-256
`8b5b689525176f63de24d81ce01d26b16b5c27c32c4e5e13f06757d388768bbc`;
its exact nullability digest is
`627be93f6c759e194645c022ab854c2fde73d916b4c787f05e7c18b49cbfb197`.

The L1-exit checkpoint tree passed a clean Corretto 26 verify at 1,557/0/0/4
over 456 main and 179 test sources. The exact 2026-08-13 L2 framework-catalog
completed-L2 localization tree (request-scoped context creation for framework
catalogs, all four handler families, and subscription terminal pre-render;
fail-atomic rendering for all five framework catalogs; no new public
`com.soklet` types; freeze gate unchanged) passed 1,620/0/0/4 over 462 main
and 186 test sources, and the L3 HTTP/cache-boundary tree (private/zero
clamping, `Content-Language`, the non-preflight `Vary` merge, and
application-boundary proofs) passes 1,624/0/0/4 after compiling 462 main and
187 test sources. The JDK 21
Error Prone/NullAway profile passes with the existing advisory-warning
inventory, and SpotBugs reports zero bugs and zero errors. The 181-source
Java-17 API sketch and Javadoc/doclint smoke pass, as does the adapter proof
against 60 real Lokalized sources plus three adapter sources. Exact-tree JDK
17/25 CI, L4-L8 localization work, and candidate-artifact Lokalized
validation remain open.

At the V21 boundary, the focused simulator/API gate passed 46/0/0/0 and the
broadened adjacent authority selector passed 215/0/0/0. Clean exact-source
suites on Corretto
21.0.11 and 26.0.1 each pass 1,528/0/0/4 across 165 suites, compiling 440 main
and 175 test Java sources. Enforced static analysis is green with existing
advisory diagnostics; SpotBugs reports 0/0. Candidate main, sources, and
Javadoc JARs plus standalone Javadoc are green using offline-link resolution.
All 167 API-sketch sources compile for Java 17 and pass Javadoc doclint on JDK
26. All 104 files from pinned JSON Schema commit
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate.

The V20 downstream focus at 23/0/0/0 and full `soklet-otel` suite at 36/0/0/0
on each JDK were carried forward and not rerun for V21. The prior focused fuzz
gate remains 28/0/0/0 and deterministic full corpus replay remains 127/0/0/0
on both JDKs; neither was rerun. Prior benchmark-module evidence was also not
rerun for V21.

The conformance runner/infrastructure self-tests and scenario/supplement-
manifest gates are green. The final-tag validator checks all 39 production-
derived golden messages against the pinned final schema with Ajv 8.20.0; the
focused golden-wire suite passes seven tests with no failure, error, or skip.
The separate clean observation supplied the 16 Phase 5 profile candidates. The
later atomic activation retained all 23 historical IDs, activated all 39 exact
profiles at implementation phase 5, and passed the fresh 39-scenario verify
with 150 exact outcomes, 36 wire successes over 103 messages, all 39 goldens,
empty standard error, and 39 clean exits. Evidence SHA-256 is
`082d841697f472da97a822c4dba35e922378f170a7050eca400b32a3eeaf6fc1`.
It is `CANDIDATE_ARTIFACT_DEVELOPMENT_ONLY` evidence with
`releaseCandidateEvidence: false`, not release sign-off. Final JDK 17 and JDK
25 CI results for this exact tree remain open.

A compatible addition to a frozen owner requires deliberate review, a
snapshot update, and an update to the freeze rationale. An incompatible change
requires an explicit compatibility plan and version decision. No generated
snapshot is accepted automatically, and no Git commit identifier is treated as
the compatibility baseline.
