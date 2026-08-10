# MCP API compatibility and inventories

This directory contains the reviewed, repository-owned evidence for Soklet's
MCP public/protected API. The [Phase 4 freeze rationale](phase-4-freeze-rationale.md)
records the 2026-08-06 decision and the subsequently reviewed wrapper
correction. The external
[Phase 5 API-review checkpoint](../../../mcp/PHASE_5_API_REVIEW_CHECKPOINT_2026-08-08.md)
records review approval for the candidate, and the
[Phase 5 freeze rationale](phase-5-freeze-rationale.md) records its exact
compatibility snapshot. The external
[activation/verification checkpoint](../../../mcp/PHASE_5_ACTIVATION_AND_VERIFICATION_2026-08-08.md)
records the atomic profile activation and fresh official-suite result.

`current-incompatibilities.jsonl` is the canonical set of incompatibilities
between the released `com.soklet:soklet:3.5.1` artifact and the current
3.6.0 source tree. It currently contains 556 records and has
SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
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
| `phase-6.includes` | 6 | Phase 6-owned types; not yet frozen |
| `provisional.includes` | 28 | owner not yet assigned to a frozen phase |
| `non-mcp-public-api.allowlist` | 0 | reviewed unrelated API deltas |

The 206-entry union is sorted, nonoverlapping, and exact. Ownership records
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
1,049 canonical records across all 133 selected owners: 133 classes, 10
constructors, 78 fields, and 828 methods. Its SHA-256 is
`89d96458cee33f96b6eef3be4b971cbf887f087f6a604b8f0e7041891b8530b5`.
`phase-5.signatures.jsonl` freezes 195 canonical records across all 39
selected owners: 39 classes, six constructors, 15 fields, and 135 methods.
Its SHA-256 is
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`.

The snapshot includes a deliberate post-freeze correction to Soklet's
unreleased `3.6.0` MCP API: 49 Phase 4 scalar signatures now use non-null
reference wrappers instead of primitives. Five of those corrections restore
the wrapper signatures already present in 3.5.1, so the reviewed baseline
incompatibility set decreased from 561 to 556 records. Regeneration found no
unrelated signature delta; the Phase 4 snapshot retains the same 1,049 records
and component counts.

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

Twelve bounded Phase 6 verticals are implemented. `McpServerDiagnostics` remains
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

The twelfth vertical adds boxed, nonnegative `getRequestsAccepted()` and
`getRequestsRejected()` with matching `requestsAccepted(Long)` and
`requestsRejected(Long)`. The provisional snapshot now has ten getters and 11
public builder methods including `build()`: eight boxed `Long` values and two
immutable maps.

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

The fieldless events and label-free families retain no request, remote
identity, endpoint, method, code, outcome, throwable, header, trace ID, token,
key, tracestate, baggage, or application label. The default collector now
aggregates 12 of 23 variants and ignores 11 across ten text families. The
16-request gate has nine MCP-prefixed samples before reset and eight after.

The rest of the resolved contract defines bounded scalar, live-gauge,
endpoint/method/outcome/reason/code map, and duration-histogram families, with
no standalone start/finish/open/close counters and no unknown-header identity.
Configured scalars render zero; maps/histograms are sparse; reset preserves
five live gauges while clearing cumulative state. The authoritative Phase
6/V10 contract owns the exact downstream OpenTelemetry mapping.
`SOK-TRACE-005` remains PARTIAL for metric-only evidence; `SOK-PRIV-001`,
`SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL. `SOK-METRIC-002`,
`SOK-METRIC-003`, and `SOK-SHUT-002` remain COMPLETE. `AMB-003` is RESOLVED
CONTRACT / IMPLEMENTATION PARTIAL;
remaining core families and downstream snapshot-compatible OpenTelemetry work
remain open.
This does not constrain custom collectors or application telemetry, promise an
atomic cross-field snapshot during active concurrent mutation, add structured-
log or raw-ID emission, complete privacy/cardinality work, or prove simulation,
sustained, release-readiness, review, or Phase 6 freeze.

These are FIFO record/enqueue-order guarantees, not a universal cross-thread
causal total order. Default aggregation now covers `ServerStarted`,
`ServerStopped`, `RequestAccepted`, `RequestRejected`, the five handler
variants, and the transport trio. The next aggregate implementation is
admitted-request lifecycle aggregation for `RequestStarted` and
`RequestFinished`. Other remaining contract-fixed families
and downstream OpenTelemetry work,
structured-log carrier/emission, raw-ID opt-in, broader privacy, sustained
cardinality, and redaction work,
simulator integration, coverage-guided and sustained fuzz gates,
release-candidate work, and Phase 6 review/freeze remain open. The seventh
through ninth verticals added no public API, snapshot field, aggregate family,
label, event variant, or wire dimension. The tenth added three provisional
getter/builder pairs, the eleventh adds one, and the twelfth adds two; none adds
an event variant or wire dimension.

This checkpoint does not freeze Phase 6. `phase-6.includes` remains outside
`frozen-phases`, and the diagnostics owner remains provisional and unfrozen.

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
for 556 incompatibility records, 206 reviewed owners, 1,049 frozen Phase 4
signatures, and 195 frozen Phase 5 signatures. The Phase 5 snapshot contains 195
records (39 classes, six constructors, 15 fields, and 135 methods), with
SHA-256
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`;
its exact nullability digest is
`d52a424ac33e679e0a0632004ac931e59966b68641659e254214964d9144f8c7`.
The 556/206/1,049/195 evidence counts are unchanged by these provisional
verticals.

The focused request-boundary aggregate/adjacent gate passes 70/0/0/0.
The prior focused five-target fuzz run remains 28/0/0/0 and was not rerun for
this checkpoint;
the prior deterministic full fuzz corpus replay on both JDKs remains
127/0/0/0 and was likewise not rerun. Exact-source full main suites on
Corretto 21.0.11 and 26.0.1 each execute 1,477/0/0/4. Enforced static analysis
is green with existing advisory diagnostics. SpotBugs reports 0/0. The focused
Phase 5 API-review contract run passes 45 tests with
no failure, error, or skip. Candidate main, source, and Javadoc packages plus
standalone Javadoc are green using offline-link resolution. All 167 API-sketch
sources compile for Java 17 and pass Javadoc doclint on JDK 26. All 104 files
from pinned JSON Schema commit
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate. The benchmark module
compiles 437 Java source files for Java 17 on JDK 21.

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
