# Changelog

## 3.6.0 (Unreleased)

### Breaking Changes

- Replaced the MCP 2025-11-25 implementation and public API with a greenfield
  MCP 2026-07-28 design. The new transport has no session or initialization
  lifecycle, and the new Java API is intentionally source- and binary-
  incompatible in this minor release. Applications that require the legacy MCP
  implementation must remain on Soklet 3.5.x; 3.6.0 provides no compatibility
  adapter.
- MCP HTTP is stateless: `GET` and `DELETE` return `405`, while
  `MCP-Session-Id` and `Last-Event-ID` are ignored, never stored, and never
  emitted. This includes application-authored policy output; attempts to add
  either legacy header fail closed instead of being echoed.
- The 3.6 modern-only migration diagnostic is intentionally narrower than the
  3.5.x initialization protocol. Once strict JSON exposes the exact readable
  method `initialize`, subsequent envelope/ID, mirrored-header, metadata,
  version, and removed-method rejections carry a supported-version diagnostic
  whose supported-version list names only `2026-07-28`;
  `UnsupportedProtocolVersionError` retains its defined `requested` field.
  Pre-JSON transport failures, unparseable JSON, unreadable methods, and other
  method names do not acquire that diagnostic. This is a fall-forward aid, not
  an initialization handshake or session. Applications that require those
  3.5.x semantics must remain on Soklet 3.5.x.

### Features

- Added a dedicated MCP 2026-07-28 Streamable HTTP server in core Soklet. It
  owns an independent listener and port, integrates with `SokletConfig`, and
  supports discovery as the first request without a session or initialization
  handshake.
- Added a separate complete-HTTP contract corpus: 21 checksum-bound response
  fixtures plus three production-listener golden tests and one exhaustive
  response-authority inventory freeze request/notification first-failure
  order, unsupported-notification handling, and universal HTTP `no-store`.
  Four additional tests freeze the readable-`initialize` diagnostic boundary.
  This evidence surface does not alter the official 48-message final-schema
  corpus or the three-head authorization/CORS corpus.
- Added two independent production-golden corpora for the remaining core result
  and ordinary error contracts. Twenty-five checksum-bound JSON/SSE fixtures
  exhaust Soklet 3.6's core `complete` and `input_required` result-envelope
  authorities; extension result types remain a separate capability boundary.
  Nine canonical complete-HTTP fixtures cover the eight frozen ordinary error
  mapping families, including distinct required and conditional `-32021`
  paths, while readable-`initialize` and other path-specific errors remain
  explicit supplements. Five deterministic tests freeze both progress/error
  enqueue orders and mapped-error/cancellation terminal ownership, without
  changing public API or ordinary runtime behavior.
- Added public-API-only
  [durable-handle and secured-prompt patterns](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
  plus [resource, URI, filesystem, and cursor patterns](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java).
  Their eight executable tests document application-owned durable storage and
  context binding, semantic prompt authorization, canonical filesystem
  containment, delivery-intent URI policy, and cursor integrity, snapshot,
  revision, and expiry. Soklet does not implement those deployment facilities.
  No production behavior or public signature/freeze inventory changed; public
  Javadocs now document the existing application-owned boundaries. Focused
  owner evidence on Amazon Corretto 17.0.20.1+10-LTS is two separate 4/4 class
  runs (eight tests total); the direct combined suite is 8/8 on local Amazon
  Corretto 21.0.11.10.1 (OpenJDK 21.0.11+10-LTS). The adjacent 12-class suite
  passes 66/66 on each JDK. Full `mvn -B -ntp clean test` passes 1,712 tests
  with zero failures, zero errors, and 72 skips on Corretto 17, and 1,727 tests
  with zero failures, zero errors, and four skips on local Corretto 21; both
  compile 462 main and 207 test sources. Following the historical
  106/116/4/18/18 checkpoint, that application-pattern checkpoint was 106
  `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18
  `NOT_APPLICABLE`, and 12 `UNRESOLVED`.
- Added a real two-leg loopback proxy fixture for an unmet conditional input
  capability. A manual monotonic idle cycle proves zero backend and forwarded
  response bytes through exact expiry, one client-disconnect outcome and
  cooperative cancelation, suppression of the retained handler's late result,
  and exact framework cleanup. A capability-present control forwards the SSE
  head, progress, and terminal result byte-for-byte through the same proxy.
  The focused/adjacent gate passes 33/33 on local Amazon Corretto
  17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1. Full
  `mvn -B -ntp clean test` passes 1,713/0/0/72 on Corretto 17 and 1,728/0/0/4
  on local Corretto 21, respectively, over 462 main and 208 test sources. A
  narrow internal production fix
  preserves an outer cancel transition's exact observation reason and cause
  instead of publishing a generic cancelation fallback. Public API,
  signatures, freeze inventories, and the version are unchanged. This proves
  one configured loopback intermediary, not a universal wall-clock proxy
  timeout or prompt non-cooperative application-code exit. At that checkpoint,
  `MCP-MRTR-011` became `CORE_COMPLETE`; the matrix was 107 `CORE_COMPLETE`, 116
  `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 11
  `UNRESOLVED`. These are local snapshot results, not immutable-candidate
  evidence; the Corretto 21 run is not release-pinned.
- Added deterministic queued-execution winner-election evidence. A monotonic
  manual clock, staged contenders, and FIFO manual executor enumerate all six
  total orders of promotion, exact-boundary deadline, and client disconnect.
  Deadline before promotion while still queued and writable returns the exact
  HTTP 503/JSON-RPC `-32603` response; disconnect writes nothing; promotion
  first leaves the queued state and follows the separately provisional active-
  deadline path. A second cross-layer case reserves the queued deadline, then
  makes the outer request control unwritable before response handoff. It
  observes zero callback bytes, exactly one `CLIENT_DISCONNECTED` finish and
  one dequeue/gauge removal; one deadline-expiration occurrence and one
  abandoned response diagnose the reserved-but-unwritable attempt without
  creating a second terminal outcome. No queued interceptor or handler runs,
  cleanup is once-only, and all framework state returns to baseline. The
  focused class passes 2/2 on pinned Amazon Corretto 17.0.20.1+10-LTS and local
  Amazon Corretto 21.0.11.10.1; the adjacent Corretto 17 execution bundle
  passes 53/53. Full `mvn -B -ntp clean test` passes 1,715/0/0/72 and
  1,730/0/0/4, respectively, over 462 main and 209 test sources. This test-only
  slice changes no production behavior, public API, signature/freeze inventory,
  or version. `SOK-EXEC-005` is now `CORE_COMPLETE`; the active matrix is 108
  `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18
  `NOT_APPLICABLE`, and 10 `UNRESOLVED`. These are bounded local ordering
  results, not every possible scheduler/network interleaving or immutable-
  candidate evidence; the Corretto 21 run is not release-pinned.
- Added annotation-first and programmatic tools, prompts, exact resources,
  resource templates, resource reads, and custom resource listing. Tool
  registration uses staged typed, argument-only, or raw-JSON argument paths;
  typed Java declarations produce schemas and conversion plans under Soklet
  MCP Tool Schema Profile 1. There is no public hand-authored JSON Schema
  registration API, and Soklet does not claim general-purpose JSON Schema
  Draft 2020-12 support.
- Added static resource-list fallback and sole-authority custom resource lists,
  application-owned opaque cursors with UTF-8 byte bounds, cache hints, MCP
  content blocks, structured tool results, and standard MCP metadata.
- Added mandatory request admission, optional request-wide rate limiting,
  required fallback tool limiting for tool-bearing servers, named endpoint and
  tool limiter overrides, a bounded in-process token bucket, and a
  `McpRateLimiter` interface suitable for distributed implementations.
- Added one `McpHandlerInterceptor` for all application-owned MCP handlers,
  tool-output sanitization, bounded handler concurrency and queueing, custom
  `Mcp-Param-*` mirrored headers, shared `CorsAuthorizer` integration, strict
  Host validation, a loopback bind default, and MCP lifecycle/metrics
  attachment points on Soklet's existing shared hosts.
- Added multi-round-trip `input_required` results and retries, application- and
  framework-protected request state, request-scoped progress and cooperative
  cancelation, and framework-owned resource-subscription streams with
  application-owned local or distributed event publishing.
- Added exact-once clean/residual MCP shutdown observation for each successful
  listener generation. The default collector exposes an immutable sparse
  shutdown aggregate and the exact bounded
  `soklet_mcp_shutdowns_total{outcome="clean"|"residual_handlers"}` family.
- Added server-wide MCP handler execution, admitted-queue, and queue-full-
  rejection events. `McpMetricsSnapshot` exposes boxed `Long` active-handler,
  queue-depth, and capacity-rejection values, and the default collector renders
  the exact label-free `soklet_mcp_handler_executions_active`,
  `soklet_mcp_handler_queue_depth`, and
  `soklet_mcp_handler_capacity_rejections_total` families. Reset preserves the
  two live gauges while clearing cumulative rejections; queued cancelation,
  deadline, disconnect, and shutdown balance queue depth without incrementing
  the rejection counter; residual handlers remain active until actual exit.
  Handler transitions, lifecycle events, and admitted request, stream,
  subscription, cancelation, progress, and keep-alive events now share one
  context-aware deferred FIFO. Collector callbacks are serialized after the
  relevant internal locks or monitors are released; request-scoped failures
  retain the originating `Request` only for the bounded delivery/failure-
  logging step without rendering that transient pending context.
- Added the bounded Phase 6 diagnostics surface: an immutable server-wide
  handler, stream, protection, and trace-configuration
  projection through `McpServerDiagnostics`. The interface has exactly 12
  zero-argument methods: lifecycle `getStatus()` and `getBoundAddress()`, plus
  all ten implemented diagnostic getters. Six are boxed `@NonNull Integer`
  methods: `getRequestHandlerConcurrency()`,
  `getRequestHandlerQueueCapacity()`, `getActiveHandlerExecutions()`,
  `getQueuedRequests()`, `getActiveRequestStreams()`, and
  `getActiveSubscriptions()`. The other four are `getProtectionMode()`, boxed
  `@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
  `getProtectionKeyRingFingerprint()`, and
  `getTraceCorrelationConfigurationFingerprint()`; both fingerprint getters
  return non-null `Optional` containers with non-null payloads.
- Lifecycle, address, handler, queue, stream, and subscription fields form one
  runtime-owned atomic tuple. The protection/trace fields form a separate
  security-controls atomic tuple; the immutable result does not claim one
  global linearization point across both. Ordinary, subscription-only, and
  combined open states produce stream pairs `1/0`, `1/1`, and `2/1`, with
  `0 <= activeSubscriptions <= activeRequestStreams`. Disconnect cleanup
  returns the pair through `1/0` to `0/0`; completed clean and residual-handler
  stops expose `0/0`; and internal `FAILED` cleanup may transiently retain
  `1/1` under public residual status before reporting `STOPPED` with `0/0`.
  Retained snapshots never change.
- Protection mode and custom-protector presence are construction-time values;
  the boxed flag is true exactly for `CUSTOM_PROTECTOR` and does not mean an
  operation selected `APPLICATION_PROTECTED`. The production-ring fingerprint
  is present exactly for `PRODUCTION_KEY_RING`; the independent trace
  fingerprint is present exactly when trace correlation was enabled. Live
  rotations update only fresh snapshots and persist across listener restart.
  Fingerprints are deterministic operational comparison metadata, not
  authentication inputs, and expose no raw key material, key IDs, per-key tags,
  provider identity, cursors/epochs, or trace tokens. Equality and rotation
  carry entropy/cardinality implications, so these values are unsuitable for
  metric labels or per-request logs. This diagnostics vertical adds no metric,
  event, or wire dimension.
- Added the sixth bounded Phase 6 vertical: one context-aware deferred FIFO now
  serialized the 16 semantic event variants then produced by the runtime—
  the five handler transitions, `ServerStopped`, nine admitted request, stream,
  subscription, cancelation, progress, and keep-alive variants, and exact-once
  `ServerStarted`. Direct restart reserves old `ServerStopped` before new
  `ServerStarted`, while managed startup rollback records `ServerStarted`
  before `ServerStopped`. The guarantee is FIFO metric record/enqueue order,
  not a universal cross-thread causal or per-request total order. At that
  checkpoint, instrumentation had not yet covered `ConnectionAccepted`,
  `ConnectionRejected`, `RequestAccepted`, `RequestRejected`, `ProtocolError`,
  `UnknownMirroredHeader`, or `TransportFailure`. This vertical adds no public
  API, snapshot field, aggregate family, label, event variant, or wire
  dimension.
- Added the seventh bounded Phase 6 vertical by extending the same FIFO to the
  20 semantic variants produced at that checkpoint. Successful bounded-
  processor submission emits `RequestAccepted`; executor rejection discards
  that provisional entry
  and emits only `RequestRejected` before its fixed empty HTTP 503. Malformed,
  strict unknown-header, and unresolved-method requests preserve exact same-
  request accepted/error/rejected record order. `ProtocolError` is limited to
  the ten fixed framework codes `-32700`, `-32600`, `-32601`, `-32602`,
  `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998` after
  successful encoding; application-owned codes are excluded, and a streamed
  error whose terminal reservation fails discards its provisional event. Each
  unknown mirrored-header occurrence records only the finite endpoint and a
  bounded method or `<unrecognized>`, independently of optional name-
  diagnostic quota and without the header name, value, or raw method. All
  pre-admission events are request-free; only admitted fixed errors retain the
  exact request for bounded delivery/failure attribution. Nonwaiting request-
  transition deferral preserves reentrant collector liveness. At that
  checkpoint, `ConnectionAccepted`, `ConnectionRejected`, and
  `TransportFailure` remained for the next vertical. This vertical adds no
  public API, snapshot field, aggregate family, label, event variant, or wire
  dimension.
- Added the eighth bounded Phase 6 vertical by extending that FIFO to all 23
  declared variants. `ConnectionAccepted` follows socket accept and capacity
  reservation but precedes registration/request processing; a later setup
  failure may follow it. `ConnectionRejected` is exact for an accepted socket
  refused by the maximum-connection bound, while accept/setup faults produce
  only their typed `TransportFailure`.
- `TransportFailure` is request-free and carries only one of the exact 18 fixed
  reasons: `REQUEST_READ_TIMEOUT`, `REQUEST_TOO_LARGE`, `MALFORMED_REQUEST`,
  `READ_ERROR`, `WRITE_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT`,
  `RESPONSE_READY_ERROR`, `REQUEST_READ_TIMEOUT_ERROR`,
  `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`, `ACCEPT_LOOP_ERROR`,
  `CONNECTION_SETUP_ERROR`, `TASK_ERROR`, `TIMEOUT_TASK_ERROR`,
  `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
  `EVENT_LOOP_TERMINATED`, and `UNKNOWN`. It retains no remote address, raw
  request/context, throwable, payload, trace token, or other unbounded value;
  low-level typed authorities choose the reason without text parsing.
- Typed provisional failure scopes and a coalescing single-daemon-worker drain
  keep collector callbacks off connection threads, preserve pending delivery
  across executor rejection, and safely join lifecycle deferral. Partial
  request timeout is distinct from quiet byte-free idle close, malformed HTTP
  from malformed JSON-RPC, and a request-SSE write-timeout winner from a losing
  or generic close that records no `WRITE_TIMEOUT`. The winner records one
  `WRITE_TIMEOUT` before terminals without synthetic `WRITE_ERROR`; fatal
  `EVENT_LOOP_TERMINATED` precedes
  stop/wake, remains active through sibling cleanup, and orders before old
  `ServerStopped` and new `ServerStarted` on restart. These remain FIFO record/
  enqueue-order guarantees, not universal cross-thread causal ordering. The
  vertical adds no public API, snapshot/aggregate family, label, event variant,
  or wire dimension.
- Added a separate bounded Phase 6 MCP fuzz-registration and hardening
  checkpoint. It remains unnumbered; at that checkpoint the implemented
  observability and diagnostics vertical count was eight. Its five new
  Jazzer methods are
  `McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
  `McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
  `McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
  `McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
  `McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
  21 checked-in synthetic text seeds cover the new targets, and the nightly
  matrix now declares 15 total one-method slots, five of them new.
- The envelope target uses production JSON limits and permits only classified
  success or typed `McpWireDecodingException`, without an unconditional encode
  round trip. Mirrored-header decoding uses the production default bound and
  only its uniform redacted `IllegalArgumentException`. Profile 1 input is
  capped at 64 KiB and requires stage-typed compilation or production-bounded
  evaluation outcomes. Cursor input is capped at 64 KiB and cross-checked as
  decoded UTF-8 and raw UTF-16 against the JDK `REPORT` encoder at a derived
  1-to-256-byte limit. Request-state plaintext uses deterministic binding/time/
  request identity, a 4,096-byte bound, 15-minute lifetime, and three-round
  limit; accepted input round-trips byte-exactly, rejection remains uniformly
  redacted, and terminal-LF copying is bounded to 4,097 input bytes.
- Cursor validation is exposed to the target only through an internal,
  package-private seam shared by incoming and outgoing cursor checks; no public
  API changed. The 21 seeds are synthetic protocol fixtures, not production
  requests or protected state. No scheduled or manual coverage-guided nightly
  run occurred, and deterministic replay is not sustained, coverage, corpus-
  saturation, privacy, security, release-readiness, or Phase 6 freeze proof.
- Added a separate unnumbered internal Phase 6 trace-correlation derivation
  checkpoint. Trace correlation is disabled by default, and
  disabled controls capture no token.
  Enabled controls snapshot one complete active key ID and key-material pair
  under the shared security lock, then derive after releasing it with
  HMAC-SHA-256 over UTF-8 `soklet-mcp-trace-correlation-v1\0` plus the decoded
  16-byte trace ID. The first 16 digest bytes become an unpadded 22-character
  Base64URL token. Invalid/all-zero trace IDs are rejected before derivation;
  equal key/trace inputs agree, changed inputs differ, and concurrent rotation
  yields only coherent old or new `(keyId, token)` pairs. Copied key material
  and explicit derivation buffers are zeroed, and carrier rendering redacts
  the token.
- Added the ninth bounded Phase 6 production vertical by invoking that frozen
  derivation exactly once for each admitted semantic request before lifecycle
  and handler observation. Only a valid MCP `_meta.traceparent` is eligible;
  disabled correlation, invalid/all-zero or absent MCP trace context, and a
  physical HTTP trace header without valid MCP metadata produce no carrier.
  Lifecycle, interceptor, handler, and terminal observation share the same
  immutable request context and hidden carrier. A pre-rotation request retains
  its old `(keyId, token)` through terminal observation, while a fresh request
  adopts the new pair. Raw validated trace-ID opt-in neither enables nor
  changes token derivation. The final package-private carrier retains only
  nonsecret key ID and token, not raw trace context or key material, and
  redacts the token from rendering.
- At that point, following the ninth vertical, the fuzz and dormant derivation
  checkpoints remained unnumbered. `SOK-TRACE-001`, `SOK-TRACE-002`, and
  `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` and `SOK-TRACE-005` were
  PLANNED; and `SOK-PRIV-001` was PARTIAL. No public API or API-sketch source
  changed. There is no
  structured-log carrier, field, emission point, cadence, or new
  `LogEventType`; raw trace-ID logging remains unimplemented. The vertical adds
  no metric, event, diagnostics/snapshot field, aggregate, label, or wire
  dimension. Tokens remain pseudonymous high-cardinality operational metadata,
  not anonymization or authentication/authorization inputs. The carrier is not
  cleared at finish and has no GC or application-reference lifetime guarantee;
  core controls retain only the current key and expose no history API. This is
  not comprehensive trace/baggage redaction, cardinality, privacy/security,
  aggregate/`AMB-003`, simulator, release-readiness, or Phase 6 freeze proof.
- Added a third unnumbered Phase 6 metric-dimensionality and trace-cardinality
  checkpoint, covered by
  `McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
  and
  `McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
  It freezes exactly 23 event-record schemas, 11 fieldless, with only endpoint,
  bounded method, fixed outcome/reason/code, and nonnegative-duration
  components. Production emits registered endpoints, recognized methods or
  `<unrecognized>`, ten fixed codes, and fixed enums; public constructors still
  permit arbitrary application-created nonempty routed strings and non-null
  codes. At that checkpoint, the snapshot was three boxed `Long` values plus
  its immutable shutdown map. The default collector aggregated only five
  handler variants and `ServerStopped`, ignoring and retaining none of the
  other 17 variants.
  Sixteen sequential real requests with distinct valid MCP/HTTP trace IDs,
  tracestate, baggage, derived tokens, and key canaries remain absent from
  built-in MCP events, snapshots, metric names/labels, filter samples,
  Prometheus, OpenMetrics, and reset output. The exact sample set changed from
  three label-free handler samples plus clean shutdown before reset to only the
  three label-free samples afterward.
- At that test-only checkpoint, the production-vertical count remained nine; fuzz
  registration, dormant derivation, and metric dimensionality were the three
  unnumbered checkpoints.
  `SOK-TRACE-001/002/003` were COMPLETE, `SOK-TRACE-004` was PLANNED,
  `SOK-TRACE-005` was PARTIAL for metric-only inventory/default-collector
  evidence, and `SOK-PRIV-001` was PARTIAL. `SOK-METRIC-001` and
  `SOK-METRIC-004` were PARTIAL; `AMB-003` was AMBIGUOUS. That test-only
  checkpoint changed no production source, public API/sketch, owner/signature inventory,
  family, label, event, or wire behavior. It does not cover custom collectors;
  generic HTTP `MetricsCollector` callbacks receiving `Request`, request
  target, or `Throwable`; `LogEvent`, application callbacks or handler
  telemetry; arbitrary application-created event vocabulary; structured-log
  or raw-ID emission; future aggregates; comprehensive trace/baggage
  redaction; sustained cardinality, fuzz, or soak; simulator, migration,
  release-candidate provenance, review, or Phase 6 freeze.
- Added the tenth bounded Phase 6 production vertical, which resolved the full
  `AMB-003` aggregate contract and implemented its coherent transport-boundary
  slice. `McpMetricsSnapshot` adds boxed nonnegative
  `getConnectionsAccepted()` and `getConnectionsRejected()` values plus an
  immutable, defensive, enum-ordered
  `Map<MetricsCollector.TransportFailureReason, Long>` from
  `getTransportFailures()`, with matching builder methods. The provisional
  snapshot surface at that checkpoint was exactly seven getters and eight
  public builder methods including `build()`.
- At that checkpoint, `DefaultMetricsCollector` aggregated `ConnectionAccepted`,
  `ConnectionRejected`, and `TransportFailure` alongside `ServerStopped` and
  the five handler variants. It renders label-free
  `soklet_mcp_connections_accepted_total` and
  `soklet_mcp_connections_rejected_total` counters, including configured and
  event-activated zeros, and merges MCP failures into the existing
  `soklet_transport_failures_total{server_type="MCP",reason="..."}` family.
  The implemented aggregate-render count was seven families.
  HTTP, SSE, and MCP samples share one HELP/TYPE block; filtering every sample
  suppresses that metadata. Reset clears the cumulative counters and sparse
  map without mutating retained snapshots. All 18 reasons, direct/concurrent
  ingest, filtering, Prometheus, and OpenMetrics are covered by
  `McpTransportMetricsAggregationTests#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
  `#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
  `#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
  `#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
- Added the eleventh bounded Phase 6 production vertical for the contract-fixed
  `ServerStarted` scalar. Boxed, nonnegative `getServerStarts()` and
  `serverStarts(Long)` bring the provisional snapshot to eight getters and nine
  public builder methods including `build()`: six boxed `Long` values and two
  immutable maps. `DefaultMetricsCollector` increments the fieldless lifecycle
  event exactly once per successfully started listener generation; failed
  staged starts and already-started no-ops add nothing, rollback retains its
  successful start before its stop, and restart counts the fresh generation.
  The label-free counter is the eighth rendered aggregate family.
  Configured or event-activated collectors render label-free
  `soklet_mcp_server_starts_total`, including zero. Direct `ServerStopped`
  activates the same family, so a stop-only fresh collector renders zero starts
  plus its shutdown sample. Filters suppress rejected sample metadata; reset
  clears the cumulative count while retaining zero-family visibility; retained
  snapshots remain immutable. Starts and shutdown outcomes are not a
  conservation pair while a generation is running. The fieldless event and
  label-free counter retain no request, network, endpoint, method, outcome,
  throwable, header, trace/token/key, tracestate, baggage, or application
  dimension. Exact coverage is
  `McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
  `#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
  and
  `#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
- Added the twelfth bounded Phase 6 production vertical for independent,
  fieldless request-boundary scalars. Boxed, nonnegative
  `getRequestsAccepted()`/`requestsAccepted(Long)` and
  `getRequestsRejected()`/`requestsRejected(Long)` bring the provisional
  snapshot to ten getters and 11 public builder methods including `build()`:
  eight boxed `Long` values and two immutable maps.
- `RequestAccepted` becomes durable only after the bounded protocol processor
  accepts `Executor.execute`; execute rejection or throw identity-discards the
  provisional accepted entry. `RequestRejected` is exact once for a complete
  Handler request whose terminal wins before atomic observation-start
  reservation. A terminal pre-admission path can produce both, while execute
  failure can produce rejected without retained accepted. The counts are not
  complementary or conserved and exclude early transport/Microhttp failures,
  post-admission outcomes, and handler-capacity rejection.
- Configured collectors and either direct event activate paired label-free zero
  samples for `soklet_mcp_requests_accepted_total` (HELP `Total MCP requests
  accepted by the bounded protocol processor`) and
  `soklet_mcp_requests_rejected_total` (HELP `Total MCP requests rejected before
  admitted semantic handling`). Filters remove family metadata with rejected
  samples; reset clears both cumulative counts while preserving paired
  visibility. OpenMetrics, retained immutable snapshots, and post-quiescence
  concurrent ingest are covered by
  `McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
  `#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
  and
  `#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
  Authority is additionally covered by
  `McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
  and
  `McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.
- These new label-free families retain no request, network identity, endpoint,
  method, code, outcome, throwable, header, trace/token/key, tracestate,
  baggage, or application-controlled dimension. The twelfth vertical adds two
  provisional getter/builder pairs but no event variant or wire dimension.
- Added the thirteenth bounded Phase 6 production vertical for admitted-request
  lifecycle aggregation. Boxed, nonnegative `getActiveRequests()`, immutable
  `getRequests()` and `getRequestDurations()` maps, and matching builder methods
  expand the provisional snapshot to 13 getters and 14 public builder methods
  including `build()`: nine boxed `Long` values and four maps. The public,
  thread-safe `RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome)` rejects
  null/empty shape but does not validate registry membership; built-in keys use
  only registered endpoint, recognized method or `<unrecognized>`, and fixed
  outcome. The two sparse maps remain independent.
- Existing exact `RequestStarted`/`RequestFinished` authority now drives
  `soklet_mcp_requests_active`, `soklet_mcp_requests_total`, and
  `soklet_mcp_request_duration_nanos`. Completed samples use only bounded
  `endpoint`, `method`, and lower-snake `outcome`; durations use the 14 HTTP
  millisecond boundaries plus overflow. There are no standalone start/finish
  counters. Configured empty state renders gauge zero; sparse completed
  families emit neither samples nor orphan HELP/TYPE metadata when empty or
  fully filtered. Reset preserves the live gauge, clears maps/histograms, and a
  crossing request retains its full original duration. Retained snapshots are
  immutable and balanced concurrent ingest is lossless after quiescence.
- These built-in families retain no request/network identity, raw unrecognized
  method, error detail, throwable, header, trace/token/key, tracestate, baggage,
  or application telemetry. They do not constrain custom collectors, generic
  HTTP metrics, logs, application-created events/keys, or telemetry; promise
  cross-field atomicity during mutation; or repair unmatched manual events.
  Exact coverage is
  `McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
  `#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
  `#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
  `#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`,
  with authority/cardinality evidence in
  `McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
  `#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
  `#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
- Added the fourteenth bounded Phase 6 production vertical for request-stream
  lifecycle aggregation. Boxed, nonnegative `getActiveRequestStreams()`,
  immutable `getRequestStreamDurations()`, and matching builder methods expand
  the provisional snapshot to 15 getters and 16 public builder methods
  including `build()`: ten boxed `Long` values and five maps. The public,
  thread-safe `RequestStreamTerminationKey(endpointPath, jsonRpcMethod,
  reason)` rejects null/empty shape but does not validate registry membership.
- Existing exact `RequestStreamOpened`/`RequestStreamClosed` authority now
  drives `soklet_mcp_request_streams_active` (HELP `Currently active MCP
  request streams`) and `soklet_mcp_request_stream_duration_nanos` (HELP `MCP
  request-stream duration in nanoseconds`). The transition records open before
  accepted progress/keepalive observations and the single close before
  terminal `RequestFinished`; this is FIFO record/enqueue order, not a
  universal cross-thread total order. Histogram samples use only bounded
  `endpoint`, `method`, and lower-snake `reason`: `completed`,
  `client_disconnected`, `request_canceled`, `deadline_exceeded`,
  `write_failed`, `backpressure`, `server_stopped`,
  `simulator_capture_item_limit_exceeded`,
  `simulator_capture_byte_limit_exceeded`, and `internal_error`. The 13 buckets
  are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600,
  7,200, and 14,400 seconds plus overflow. There are no standalone open/close
  counters.
- Configured collectors and either direct event activate gauge-zero
  visibility; the histogram stays sparse and emits no orphan HELP/TYPE
  metadata when empty or fully filtered. Prometheus/OpenMetrics filtering,
  reset preserving the live gauge while clearing histograms, full duration for
  a stream crossing reset, immutable retained snapshots, and balanced
  post-quiescence concurrent ingest are covered by
  `McpRequestStreamLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState`,
  `#defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies`,
  `#resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration`,
  and
  `#concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
  Live authority remains covered by
  `McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
  and
  `McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`.
- Runtime keys contain only registered endpoint, recognized method or
  `<unrecognized>`, and fixed reason. No request/network identity, error
  detail, throwable, header, trace/token/key, tracestate, baggage, or
  application telemetry enters these built-in dimensions. This vertical does
  not constrain custom collectors, generic HTTP/SSE metrics, logs,
  application-created events/keys, or telemetry; promise cross-field or
  concurrent-reset atomicity, repair unmatched manual events, equate metrics
  with diagnostics, expose a subscription breakdown, promise canonical order,
  add OpenTelemetry/trace emission, or prove sustained, simulator, privacy,
  release-readiness, or Phase 6 freeze.
- Added the fifteenth bounded Phase 6 production vertical for subscription
  lifecycle aggregation. Boxed, nonnegative `getActiveSubscriptions()`,
  immutable `getSubscriptionDurations()`, and matching builders expand the
  provisional snapshot to 17 getters and 18 public builder methods including
  `build()`: 11 boxed `Long` values and six maps. The public, thread-safe
  `SubscriptionTerminationKey(endpointPath, reason)` rejects null/empty shape
  but does not validate registry membership.
- Exact `SubscriptionOpened`/`SubscriptionClosed` delivery now drives
  `soklet_mcp_subscriptions_active` (HELP `Currently active MCP subscriptions`)
  and `soklet_mcp_subscription_duration_nanos` (HELP `MCP subscription duration
  in nanoseconds`). Samples use only bounded `endpoint` and lower-snake
  `reason`: `completed`, `client_disconnected`, `request_canceled`,
  `deadline_exceeded`, `write_failed`, `backpressure`, `server_stopped`,
  `simulator_capture_item_limit_exceeded`,
  `simulator_capture_byte_limit_exceeded`, and `internal_error`. The 13 buckets
  are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400 seconds
  plus overflow; there are no standalone open/close counters.
- Produced FIFO order is `RequestStreamOpened`, `SubscriptionOpened`, then at
  termination `RequestStreamClosed`, `SubscriptionClosed`, and
  `RequestFinished`; it is not universal cross-thread ordering or an atomic
  relationship between gauges. Configured/direct zero visibility, sparse
  no-orphan histogram metadata, Prometheus/OpenMetrics filtering, reset
  preserving the live gauge while clearing histograms, full duration across
  reset, retained immutability, and balanced post-quiescence concurrency are
  covered by
  `McpSubscriptionLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableSubscriptionLifecycleState`,
  `#defaultCollectorAggregatesRendersAndFiltersSubscriptionLifecycleFamilies`,
  `#resetPreservesActiveSubscriptionsAndLateCloseRecordsFullOriginalDuration`,
  and
  `#concurrentBalancedSubscriptionLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
  Live authority remains covered by
  `McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`
  and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.
- Built-in subscription keys retain only registered endpoint and fixed reason,
  never method, resource URI, filter, request/network identity, error detail,
  throwable, header, trace/token/key, tracestate, baggage, or application
  telemetry. This vertical does not constrain custom collectors, generic
  HTTP/SSE metrics, logs, application-created events/keys, or telemetry;
  promise cross-field/concurrent-reset atomicity, repair unmatched manual
  events, equate metrics with diagnostics, promise canonical order or
  conservation with stream gauges, add OpenTelemetry/trace emission, or prove
  sustained, simulator, comprehensive privacy, release-readiness, or Phase 6
  freeze.
- Added the sixteenth bounded Phase 6 production vertical for independent
  progress and cooperative-cancelation aggregation. Immutable
  `Map<EndpointMethodKey, Long> getCancelationsSignaled()` and
  `getProgressEmitted()` plus matching builders expand the provisional
  snapshot to 19 getters and 20 public builder methods including `build()`:
  11 boxed `Long` values and eight maps. The public, thread-safe
  `EndpointMethodKey(endpointPath, jsonRpcMethod)` rejects null/empty shape but
  accepts arbitrary nonempty application-created values.
- Exact delivered `CancelationSignaled` now drives
  `soklet_mcp_cancelations_signaled_total{endpoint,method}` with HELP `Total
  cooperative MCP request cancelations signaled by endpoint and method`;
  `ProgressEmitted` drives
  `soklet_mcp_progress_emitted_total{endpoint,method}` with HELP `Total MCP
  progress notifications accepted for delivery by endpoint and method`. The
  maps are independent, not complements or a per-request conservation
  equation.
- Both labeled counters are strictly sparse: configured empty state emits no
  sample or HELP/TYPE metadata, direct events populate only their respective
  families, all-rejected filters leave no orphan metadata, OpenMetrics emits
  one EOF, and reset clears both maps. Defensive copies preserve explicit
  application zeros; retained snapshots remain immutable, and
  post-quiescence concurrent direct ingest is lossless. Exact tests are
  `McpProgressAndCancelationMetricsAggregationTests#snapshotContractUsesSharedImmutableEndpointMethodCounterMaps`,
  `#defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies`,
  `#resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata`,
  and
  `#concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
- Live authority in
  `McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
  proves two accepted progress events, one cooperative-cancelation event,
  serialized delivery outside the reporter monitor, and no post-cancel
  progress, without imposing universal cross-thread terminal order. Built-in
  labels retain only registered endpoint and bounded method, never progress
  token/value/total/message, cancelation reason, request/network identity,
  throwable, header, trace/token/key, tracestate, baggage, or application
  telemetry. This vertical does not constrain custom collectors, generic
  HTTP/SSE metrics, logs, application-created events/keys, or telemetry;
  promise cross-map/concurrent-reset atomicity, canonical order,
  OpenTelemetry/trace emission, comprehensive privacy, sustained/simulator or
  release evidence, or Phase 6 freeze.
- Added the seventeenth bounded Phase 6 production vertical for fieldless
  keep-alive aggregation. Boxed, nonnegative
  `@NonNull Long getKeepAlivesEmitted()` and matching
  `keepAlivesEmitted(Long)` expand the provisional snapshot to 20 getters and
  21 public builder methods including `build()`: 12 boxed `Long` values and
  eight immutable maps.
- Exact FIFO-delivered `KeepAliveEmitted` now drives the label-free
  `soklet_mcp_keep_alives_emitted_total` counter with HELP `Total MCP
  keep-alive comments accepted for delivery`. Configured MCP and direct events
  activate the family; configured and post-reset state render zero. Filters see
  no labels, all-rejected filters leave no sample or orphan metadata,
  OpenMetrics terminates once, reset retains visibility while clearing the
  cumulative count, retained snapshots remain immutable, and post-quiescence
  concurrent direct ingest is lossless. Exact tests are
  `McpKeepAliveMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeKeepAliveCount`,
  `#defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset`,
  and
  `#concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
- Live authority remains bounded by
  `McpSubscriptionPublicRuntimeTests#keepAliveAcceptanceSharesStreamTransitionWithCloseObservation`
  and
  `McpSubscriptionRuntimeBoundaryTests#maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents`.
  They freeze accepted wire-observation/transition order and an exact-one
  deterministic boundary, not timer attempts or client/intermediary receipt;
  no conservation with subscriptions, streams, or terminal events is claimed.
  The fieldless built-in event retains no request, endpoint, method, remote
  identity, duration, reason, throwable, header, trace/token/key, tracestate,
  baggage, or application label. This does not constrain custom collectors,
  generic HTTP/SSE metrics, logs, or application telemetry; promise universal
  cross-thread order, delivery/receipt, concurrent-reset atomicity,
  OpenTelemetry/trace emission, comprehensive privacy, sustained/simulator or
  release evidence, or Phase 6 freeze.
- Added the eighteenth bounded Phase 6 production vertical, completing core
  default aggregation with immutable `getProtocolErrors()` and
  `getUnknownMirroredHeaders()` maps plus matching builder methods. The
  provisional snapshot now has 22 getters and 23 public builder methods
  including `build()`: 12 boxed `Long` values and ten maps. The three fuzz,
  dormant-derivation, and metric-dimensionality checkpoints remain unnumbered.
- Added sparse `soklet_mcp_protocol_errors_total{code}` with HELP `Total
  client-visible MCP protocol errors by fixed code` and
  `soklet_mcp_unknown_mirrored_headers_total{endpoint,method}` with HELP `Total
  unknown MCP mirrored-header occurrences by endpoint and method`.
  Configuration alone emits neither family, a direct event affects only its
  own map, full filter rejection leaves no orphan metadata, OpenMetrics emits
  one EOF, and reset removes samples and metadata. Snapshots are defensive and
  immutable, explicit public zeros survive construction, and post-quiescence
  concurrent direct ingest is lossless.
- Framework production remains narrower than public/manual value construction.
  Live protocol codes are exactly `-32700`, `-32600`, `-32601`, `-32602`,
  `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998` after
  successful client-visible encoding or accepted streamed-terminal
  reservation. Failed provisional terminals, application codes, tool-result
  `isError`, and empty-notification HTTP errors are excluded. Unknown-header
  metrics occur once per occurrence under IGNORE and REJECT and contain only a
  registered endpoint plus recognized core method or `<unrecognized>`, never
  header name/value or raw unrecognized method. Pre-admission errors are
  request-free; admitted fixed errors retain exact context only for bounded
  delivery/failure attribution.
- The two default maps independently cap retained dimensions at 8,192. Public
  builder maps are uncapped carriers for arbitrary non-null Integer codes and
  shape-valid nonempty `EndpointMethodKey` values with nonnegative counts;
  explicit zero is retained and protocol maps use natural Integer order. No
  built-in metric dimension contains header identity, request, throwable,
  payload, remote identity, trace/token/key, tracestate, baggage, or a generic
  label. Same-request event sequences preserve FIFO record/enqueue order only,
  not universal cross-thread order or conservation.
- Exact coverage is
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
- At the eighteenth vertical, the remaining resolved core contract used
  bounded label-free scalars, five live gauges, fixed
  endpoint/method/outcome/reason/code maps and duration
  histograms, no standalone start/finish/open/close counters, and no
  unknown-header identity. Configured scalars render zero; maps/histograms are
  sparse; reset preserves live gauges and clears cumulative state. At that
  point, exact downstream OpenTelemetry mapping remained authoritative but
  unimplemented, and `AMB-003` was RESOLVED CONTRACT 2026-08-10 / CORE
  IMPLEMENTATION COMPLETE / DOWNSTREAM IMPLEMENTATION PARTIAL. The full 23/23
  variants were default-aggregated across 22 text families, leaving zero core
  families.
- The transport aggregate retains no remote address, request, throwable,
  header, trace ID, token, key material, tracestate, baggage, or application-
  controlled label. This vertical adds no event variant or wire dimension and
  does not constrain custom collectors, promise an atomic cross-field snapshot
  during active mutation, or provide structured-log/raw-ID, comprehensive privacy,
  sustained-cardinality, simulator, release-readiness, or Phase 6 freeze
  evidence. `SOK-TRACE-004` remains PLANNED. Metric-only `SOK-TRACE-005`,
  `SOK-PRIV-001`, `MCP-HTTP-020`, `SOK-METRIC-001`, and
  `SOK-METRIC-004` remain PARTIAL; `SOK-METRIC-002`, `SOK-METRIC-003`, and
  `SOK-SHUT-002` remain COMPLETE.
- Added the nineteenth bounded Phase 6 production vertical in the sibling
  `soklet-otel` artifact. Unreleased `1.4.0-SNAPSHOT`, built against Soklet
  `3.6.0-SNAPSHOT`, maps all 23 `McpMetricsEvent` variants to exactly 22
  OpenTelemetry instruments: 21 MCP-specific instruments plus the shared
  transport-failure counter. The core event, snapshot, text, sketch, canary,
  and owner inventories are unchanged.
- Added exact MCP metric kinds, units, seven MCP attributes, lower-snake enum
  values, 14 request-duration and 12 long-lived-duration finite bucket
  boundaries in seconds. Shared MCP transport failures use only
  `soklet.server.type="mcp"` and `soklet.failure.reason`, never `error.type`.
  Terminal values use overflow-safe duration conversion, with no
  cross-instrument atomicity or conservation promise.
- Removed the obsolete pre-3.6 MCP request/session/SSE tracing callbacks,
  session instruments, span-policy knobs, and MCP span-naming methods. The
  reviewed V19 `1.3.1` to `1.4.0-SNAPSHOT` public diff was exactly 15 removed
  legacy methods and one added `didRecordMcpMetricsEvent(McpMetricsEvent)`
  method. At that point modern MCP lifecycle callbacks remained inherited
  no-ops; no replacement MCP spans were added, and HTTP/SSE telemetry remained
  intact.
- For framework-produced events, the integration adds no dedicated attributes
  for trace/raw request IDs, progress values, header identity/value, request
  objects, throwables, operation/resource URIs, principals/addresses,
  tracestate, baggage, or generic bags. Direct manual dimension values may
  contain sensitive text; applications own their confidentiality and
  cardinality, and OpenTelemetry SDK series retention remains outside the
  built-in framework-vocabulary guarantee. Snapshot/reset/filter/OpenMetrics
  parity, SDK retention caps, structured logs, sustained cardinality,
  simulator/release evidence, and Phase 6 freeze are not claimed.
- Exact V19 coverage is
  `OpenTelemetryMetricsCollectorTests#allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions`,
  `#mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets`,
  `#mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes`,
  `#mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary`,
  `#handlesConcurrentMcpMetricEventsWithoutLoss`, and
  `OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
  At that point the full downstream suite passed 28/0/0/0 on JDK 21 and JDK 26; main,
  sources, Javadoc, and standalone Javadoc packaging is green. `AMB-003` is
  RESOLVED CONTRACT 2026-08-10 / CORE IMPLEMENTATION COMPLETE / DOWNSTREAM
  METRIC IMPLEMENTATION COMPLETE. At that V19 boundary, modern
  `McpRequestContext` span semantics were the next separate contract slice;
  other Phase 6 statuses remained open as
  documented.
- Added the twentieth bounded Phase 6 production vertical in downstream
  `soklet-otel:1.4.0-SNAPSHOT`. Boxed `recordMcpRequestSpans` policy defaults
  true, and the additive context-shaped naming default preserves existing
  three-method strategies. Default names and `rpc.method` expose only the exact
  ten core methods or `<unrecognized>`; custom naming remains application-owned.
- Added one SERVER span per admitted request/notification, kept open through
  request-stream/subscription lifetime until the exact terminal callback. Only
  validated MCP `_meta.traceparent`/`tracestate` parents it; HTTP headers,
  ambient context, and baggage do not. Start attributes are MCP server type,
  JSON-RPC system, bounded method, and endpoint. Physical client address and
  Soklet request ID remain off-by-default opt-ins and never use the JSON-RPC ID.
- Added exact lower-snake outcome/status semantics. A JSON-RPC error projects
  its decimal code as string response status and `error.type`; without one,
  six fixed error outcomes mark ERROR while complete/input-required/canceled/
  client-disconnected remain UNSET. Throwables create no exception events or
  material. Normal duration controls the end timestamp; overflow falls back to
  plain end.
- Hardened MCP span cleanup: disabled policy and missing/late finish are no-op;
  duplicate direct starts and close plainly end state; a post-publication
  closed recheck removes and ends only the exact state that raced close.
  Telemetry failures remain contained and concurrent contexts isolated.
- Kept the built-in span projection free of JSON-RPC ID, request metadata,
  operation/path/capability/admission data, baggage, physical HTTP trace
  headers, error message/data, throwable, and exception events, apart from the
  intentional MCP parent and explicit physical address/request-ID opt-ins.
  No session/stream/subscription span, custom-namer safety, structured/raw-ID
  emission, comprehensive privacy, sustained cardinality, simulator/release,
  or Phase 6 freeze is claimed.
- Exact V20 coverage is
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
- V20 adds five declared downstream methods relative to V19; the reviewed
  `1.3.1` to current diff is 13 removals and four additions. Core inventories
  remain unchanged. `MCP-BASE-026` is COMPLETE; AMB and metric statuses remain
  unchanged, metric-only `SOK-TRACE-005` and `SOK-PRIV-001` remain PARTIAL,
  and `SOK-TRACE-004` remains PLANNED. The post-fix focus is 23/0/0/0; the full
  module is 36/0/0/0 on each JDK 21/26, with six main and three test sources,
  13 metrics tests, 15 lifecycle tests, and eight MCP lifecycle tests. Offline
  main/source/Javadoc packaging plus standalone Javadoc are green. At that V20
  boundary, MCP simulator integration was next.
- Added the twenty-first bounded Phase 6 production vertical for modern
  off-network MCP simulation through the shared `Simulator`. Its two abstract
  `startMcpRequest(...)` methods, seven top-level public simulation types, and
  `McpSimulationOptions.Builder` provide asynchronous bounded JSON/SSE capture;
  default bounds are 128 pending items and 10,485,760 cumulative bytes.
- Reused the real MCP processor, application, lifecycle, metrics,
  request-stream/subscription, MRTR, and terminal paths without a socket. Public
  server status remains `STOPPED`, bound address empty and diagnostics zero;
  no server/connection/transport event is emitted. Host, Origin, headers, and
  body remain caller values, and literal configured port `0` requires Host
  authority `:0` without synthesis or repair.
- Added immutable response/completion and exact SSE-item projections. A
  terminal JSON frame is one counted queued item and is repeated in completion
  at no additional cost. Item capacity is checked before cumulative bytes;
  equality is accepted, offending frames are excluded, dequeue refunds only a
  slot, and captured bytes never refund. JSON and pre-response SSE overflow
  retain their response heads and exact public termination reasons.
- Routed simulator limits to coarse token reason `SIMULATOR_LIMIT_EXCEEDED` and
  admitted-request outcome `CANCELED`, without protocol/transport failure.
  Cancel, close, and scope exit reserve `CLIENT_DISCONNECTED` only if they win;
  cleanup is idempotent and bounded, residual work blocks new simulation/live
  start until release, escaped handles stay readable, and suppression is
  retained under consumer failure.
- Added bounded zero/huge/interrupted wait behavior, per-request FIFO and
  concurrent isolation, exact JSON/stream/subscription/keep-alive replay, and a
  two-request distinct-ID `input_required` continuation. Caller Request
  material and retained Throwable identities remain application-sensitive;
  carrier rendering is redacted but accessors intentionally expose them.
- Representative exact citations from the full 46-test simulator/API gate are
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
- V21 brings Phase 6/provisional/reviewed owners to 15/32/219. The canonical
  comparison is 558 records with SHA-256
  `d40004fa92cc5d095404de2133cf04fcd2b5574e9326eb680f571a017ef33671`;
  frozen Phase 4/5 inventories and hashes remain unchanged. Core metrics remain
  23/23 events, 22 families, 22 snapshot getters/23 builder methods, 12 boxed
  `Long` values plus ten maps, and the 31/12 canary projection.
- At the V21 boundary, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
  IMPLEMENTATION EVIDENCE, while every-operation and 39-scenario simulator
  coverage, live-network fidelity, stress/
  soak, sustained fuzz, comprehensive privacy/security, release provenance,
  and Phase 6 freeze remain open. Other statuses remain unchanged; the first
  complete release-workflow dry run and remaining sustained/review/freeze gates
  are next.
- The focused simulator/API gate passes 46/0/0/0 and the broadened adjacent
  selector passes 215/0/0/0. Clean Corretto 21.0.11 and 26.0.1 full suites each
  pass 1,528/0/0/4 across 165 suites and 440 main/175 test Java sources. Static
  analysis, SpotBugs 0/0, artifacts/offline standalone Javadoc, the API
  verifier, 167-source sketch, and 104-schema validation are green. V20
  downstream focus 23/0/0/0 and full-suite 36/0/0/0 evidence, plus fuzz
  28/0/0/0 and 127/0/0/0 on both JDKs, were carried forward and not rerun.
- Added the **fourth unnumbered Phase 6 every-operation simulator, bounded
  capture-fuzz, and off-network soak hardening checkpoint**. It adds no
  production source,
  public API/sketch, owner/signature inventory, metric/event/snapshot surface,
  wire behavior, or numbered vertical; the production count remains 21.
- Added `McpSimulatorEveryOperationTests#recognizedRequestMethodsReplayExactJsonOrSseShapes`
  with nine dynamic request cases, plus
  `#cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation`
  and `#concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained`. The 11
  reported cases freeze exact status, header order, canonical JSON/SSE,
  lifecycle/metrics, notification no-op behavior, `STOPPED` diagnostics, no
  server/connection/transport events, and deterministic concurrent drain. The
  six-class operation selector passes 57/0/0/0.
- Added internal capture-state-machine-only fuzz coverage through
  `McpSimulationCaptureFuzzTest#captureStateMachineRemainsBoundedTerminalAndIdempotent`
  and `#curatedSeedsReachJsonSseLimitCancelAndCompletionBranches`, bounded to
  65,536 input bytes, 64 actions, 256 payload bytes, 16 pending items, and
  4,096 captured bytes. Six synthetic ASCII seeds—`json-complete.actions`,
  `sse-terminal.actions`, `item-limit.actions`, `byte-limit.actions`,
  `cancel.actions`, and `duplicate-terminal.actions`—drive exact JSON, SSE,
  both limits, cancel, and duplicate-terminal branches. Focused replay passes
  8/0/0/0; deterministic full fuzz replay passes 135/0/0/0 across 16 methods,
  15 classes, and 27 MCP seeds. A five-second coverage-guided attempt was
  host-blocked before target execution and is not coverage evidence; the
  declared `maxDuration=2m` is a registration bound, not executed-run
  evidence.
- Added `McpCrossFeatureSoakTests#mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup`.
  Its fixed smoke workload runs 24 cycles, eight cases repeated three times,
  item/byte bounds 4/4,096, and one residual recovery wave. Exact results are
  requests 38/38, streams 24/24, subscriptions 4/4, handlers 34/34, residual
  1, transport 0, listener lifecycle 0, and final `STOPPED`. The JDK 26 smoke
  profile passes 5/0/0/0 across three suites/five scenarios; verifier SHA-256
  is `eaa1f52aad86dc2765200273a468801e938f5a6be1719845358c9aa57879bcd6`.
  The broadened JDK 26 selector passes 226/0/0/0.
- Clean exact-source full suites on Corretto 21.0.11 and 26.0.1 each pass
  1,539/0/0/4 across 166 suites, compiling 440 main and 176 test Java sources.
  A separate local JDK 26 nightly-shaped execution passes 5/0/0/0 and its
  verifier is green with SHA-256
  `a20a70d6adb1fd2cb5909be76b219e38fc112524a12fc06552b26bdd8ec76d99`.
  It runs 200 cycles over eight cases repeated 25 times, balances requests
  236/236, streams 156/156, subscriptions 26/26, and handlers 210/210, records
  residual 1, transport 0, listener lifecycle 0, final `STOPPED`, file-
  descriptor delta 0, heap delta +15,272 bytes, and thread delta -1. This was
  local nightly-shaped execution, not scheduled CI, sustained, fleet, or
  release-candidate evidence. V21 static-analysis, SpotBugs, packaging/
  Javadoc, API-verifier, sketch, and schema results were carried forward and
  not rerun for this checkpoint.
- `SOK-SIM-001` remains COMPLETE BOUNDED PHASE 6 IMPLEMENTATION EVIDENCE and
  now includes deterministic every-operation evidence. At that fourth
  checkpoint, the ledger was
  21 numbered verticals plus four unnumbered checkpoints. The strict local
  39-scenario driver, every parameter/error permutation, live-network fidelity,
  scheduled/manual and sustained fuzz, corpus saturation, long/fleet soak,
  comprehensive privacy/security, release provenance, and Phase 6 review/
  freeze remain open. `SOK-VALID-002` and `SOK-PRIV-001` advance narrowly but
  remain PARTIAL; all other statuses remained unchanged. The next slice was a
  strict 39-row LOCAL off-network driver tied byte-for-row and name-for-name to
  the pinned `CLI/scenarios.json` manifest ordinal order; it was not the
  official CLI or a live-network run.
- Added the **fifth unnumbered Phase 6 candidate-artifact/public-API-only local
  39-row simulator-driver checkpoint**. It adds no production source, public
  API/sketch, owner/signature inventory, metric/event/snapshot surface, wire
  behavior, or numbered vertical; the production count remains 21.
- Added `conformance/official/run-local-simulator.mjs`, which validates and
  follows pinned `CLI/scenarios.json` manifest ordinal order for the exact 39
  active `RUN` rows at ordinals 1 and 3 through 40. It invokes
  `McpLocalSimulatorScenarioDriver#runManifestRowsOffNetwork` against only the
  compiled fixture classes and candidate JAR. Each row receives a fresh
  scenario configuration and simulator scope and performs bounded public-API
  work. The package-private fixture source helper
  `McpConformanceFixture#simulationConfigForScenario` supplies the registrations
  without adding production API.
- The wrapper byte-compares exactly one
  `PASS\t<ordinal>\t<name>\n` record per row in manifest ordinal order and
  requires empty standard error and a clean exit. Corretto 21 and 26 each pass
  39/39 after `--release 17 -Xlint:all -Werror` fixture/driver compilation,
  the fixture contract main, and a `jdeps` gate rejecting
  `com.soklet.internal`. The adversarial
  `conformance/official/local-simulator-self-test.mjs` rejects reorder,
  duplicate, missing, spawn error, nonzero exit, signal, standard error,
  wrong output, `FAIL`, CRLF, and unterminated output.
- The current ledger is 21 numbered production verticals plus five unnumbered
  checkpoints. API evidence remains 558 records with the same hash and
  15/32/219 Phase 6/provisional/reviewed owners; metric, snapshot, and canary
  surfaces remain 23/23 events, 22 families, 22 getters/23 builder methods,
  and 31/12 samples. `SOK-SIM-001` remains COMPLETE BOUNDED PHASE 6
  IMPLEMENTATION EVIDENCE and all other status rows remain unchanged. This
  local driver is not the official CLI, does not replay the official expected-
  check multiset, opens no live network path, and does not prove listener/
  kernel behavior, backpressure, write-idle handling, release provenance,
  sustained operation, comprehensive privacy/security, or Phase 6 review/
  freeze. Scheduled coverage-guided fuzz and sustained soak/stress are next,
  followed by structured-log, privacy, and API review/freeze work.

### Development Status

- The locally frozen Phase 4 and Phase 5 surfaces implement discovery, tools,
  prompts, resources, progress, cancelation, subscription delivery, multi-
  round-trip execution, and protected request-state execution. All 39 reviewed
  Phase 5 profiles are active. Twenty-one bounded Phase 6 verticals—shutdown,
  handler-capacity, handler diagnostics, stream/subscription diagnostics,
  protection/trace diagnostics, serialized semantic-event delivery, and
  bounded pre-admission and transport metrics, admitted-request trace-token
  capture, transport-boundary aggregation, server-start aggregation, and
  request-boundary, admitted-request lifecycle, request-stream lifecycle,
  subscription lifecycle, progress/cancelation, keep-alive, and protocol-
  error/unknown-header aggregation, and the downstream OpenTelemetry metric
  migration, modern admitted-request spans, and bounded off-network MCP
  simulation—are implemented and locally
  green. The separate fuzz-
  registration, dormant derivation, metric-dimensionality, simulator
  hardening, and local 39-row driver checkpoints are the five unnumbered
  checkpoints. The nonstreaming 16-request
  cardinality gate observes 31 exact MCP-prefixed samples before reset and 12
  after reset because configured MCP renders the keep-alive scalar at zero.
  The current operation selector passes 57/0/0/0 and the broadened JDK 26
  authority selector passes 226/0/0/0. Clean Corretto 21.0.11 and 26.0.1 full
  suites each pass 1,539/0/0/4 across 166 suites and 440 main/176 test Java
  sources. V21 enforced static analysis was green with existing advisory diagnostics,
  and SpotBugs reports 0/0. Exact API evidence reports 558 incompatibilities,
  15 Phase 6 owners, 32 provisional owners, and a 219-owner reviewed union. The
  frozen 1,049 Phase 4 and 195 Phase 5 inventories and prior hashes remain
  unchanged.
  V21 candidate main, sources, and Javadoc JARs plus standalone Javadoc were
  green using offline-link resolution. At V21, all 167 API-sketch sources
  compiled for Java 17 and passed Javadoc doclint on JDK 26.
  All 104 files from pinned JSON Schema commit
  `0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate. The V20 downstream
  focus at 23/0/0/0 and full suite at 36/0/0/0 on each JDK were carried forward
  and not rerun for V21. The prior focused fuzz run remains 28/0/0/0 and dual-
  JDK deterministic replay remained 127/0/0/0 at V21; V22 deterministic fuzz
  replay now passes 135/0/0/0. Default aggregation
  now covers `ServerStarted`, `ServerStopped`, `RequestAccepted`,
  `RequestRejected`, `RequestStarted`, `RequestFinished`,
  `RequestStreamOpened`, `RequestStreamClosed`, five handler variants,
  `SubscriptionOpened`, `SubscriptionClosed`, `CancelationSignaled`,
  `ProgressEmitted`, `KeepAliveEmitted`, `ProtocolError`,
  `UnknownMirroredHeader`, and the transport trio. The candidate-artifact,
  public-API-only local driver passes all 39 active manifest rows on Corretto
  21 and 26. Scheduled coverage-guided fuzz and sustained soak/stress are next;
  other downstream work,
  structured-log carrier/emission, raw-ID
  opt-in, broader privacy, sustained cardinality, and redaction work,
  coverage-guided and sustained fuzz gates,
  CI/provenance and release-candidate work, and the provisional, unfrozen Phase
  6 API review/freeze remain open. Here, remaining fuzz gates mean
  scheduled/manual coverage-guided and sustained execution; no such nightly
  run has occurred.

## 3.5.1 (2026-07-13)

### Fixes

- Fixed an MCP shutdown race that could leave an established SSE stream registered when its connection processor could not be started.

## 3.5.0 (2026-07-13)

### Features

- Standard HTTP requests can now opt into transparent gzip request-body decompression with `HttpServer.Builder.requestDecompressionPolicy(...)` per RFC 9110 §8.4. When enabled, single-coding `Content-Encoding: gzip`/`x-gzip` bodies are decompressed before request handling (with `Content-Encoding`/`Transfer-Encoding` removed and `Content-Length` updated so handlers observe a self-consistent request); unsupported codings — including multi-coding chains — are rejected with `415 Unsupported Media Type` (RFC 9110 §15.5.16), undecodable bodies with `400 Bad Request`, and decompression-bomb protection rejects bodies exceeding a configurable absolute size (default: the server's `maximumRequestSizeInBytes`) or compression ratio (default `100:1`) with `413 Content Too Large` through the usual content-too-large marshaling path. `Request.getEncodedBodySizeInBytes()` retains the pre-decompression payload size for wire-oriented telemetry while `Request.getBody()` exposes the handler-visible bytes. Unsupported/undecodable decompression failures surface to `LifecycleObserver` consumers as the new `RequestReadFailureReason.REQUEST_BODY_DECOMPRESSION_FAILED`. Decompression remains disabled by default; the SSE and MCP servers are unaffected.
- Added `Request.getMediaRanges()` for parsed `Accept` header content negotiation input. Returns an ordered list of the new `MediaRange` type (type/subtype, `q` weight, media-type parameters) sorted by weight then specificity per RFC 9110 §12.5.1, with lenient handling of malformed media ranges. Per RFC 9110, the `q` weight is recognized at any parameter position and all non-`q` parameters are retained as media-type parameters (the obsolete RFC 7231 `accept-ext` grammar is not implemented). `MediaRange.fromHeaderRepresentation(...)` and `Utilities.extractMediaRangesFromAcceptHeaderValue(...)` are available for standalone parsing.

### Fixes

- Responses to `HEAD` requests that bypass normal HEAD marshaling, including canned failsafe and exceptional error paths, no longer include response content. Per RFC 9110 §9.3.2 the hypothetical `Content-Length` is preserved while the body bytes are omitted, so keep-alive clients can no longer desync by reading error content as the start of the next response.
- MCP `Accept` header evaluation now uses the shared quote-aware media range parser, so quoted commas or semicolons inside parameter values can no longer manufacture spurious acceptable media ranges (for example, overriding an explicit `q=0`). Specificity-first matching behavior is unchanged.
- Hardened multipart header parsing so malformed RFC 2047 Base64 encoded-word values are treated as literal text instead of escaping as unexpected runtime exceptions.
- Accept-loop retry backoff sleeps (up to 1 second during a sustained accept failure such as file-descriptor exhaustion) now observe `stop()` within ~50ms across standard HTTP, SSE, and MCP servers, instead of delaying shutdown by up to the full backoff delay.
- Standard HTTP connection-setup failures (e.g. a connection listener that throws on every accepted connection) now use the same escalating retry backoff and coalesced logging as accept-loop I/O failures, instead of a fixed 50ms delay with per-iteration logging. SSE runtime failures escaping the accept iteration itself now do the same; SSE per-connection setup failures continue to be handled per-connection (logged, recorded, and the connection closed) without delaying the accept loop. The escalating backoff schedule is now shared across all three servers.

### Documentation

- `McpSessionStore.Builder.idleTimeout(...)` now documents that disabling idle expiry with a finite concurrent-session limit requires explicit session lifecycle cleanup to avoid exhausting session slots.
- `RequestInterceptor.interceptRequest(...)` now documents its synchronous, same-thread contract explicitly.

### Tooling

- Added startup/memory benchmarking support for local measurement and future managed-runner release baselines. This release does not publish public benchmark numbers.

## 3.4.0

### Breaking Changes

- MCP session creation is now owned by `McpSessionStore`. Custom stores must implement `create(Request, Class<? extends McpEndpoint>)`, generate valid `MCP-Session-Id` values themselves, and make admission decisions atomically with persistence. `McpServer.Builder.sessionIdGenerator(...)`, `McpServer.Builder.concurrentSessionLimit(...)`, and the old `McpSessionStore.fromInMemory(Duration)` shortcut were removed; use `McpSessionStore.builder()` for the default in-memory store.

### Features

- Added `ConditionalRequests` for dynamic-resource HTTP conditionals. Applications can now evaluate `If-Match`, `If-None-Match`, `If-Modified-Since`, and `If-Unmodified-Since` against application-supplied `EntityTag` and `Last-Modified` validators, use `validatorHeaders(...)` for successful responses, and return bodyless `304 Not Modified` or `412 Precondition Failed` responses when preconditions short-circuit.
- Added `EffectiveClientIpResolver` for deriving a trusted client IP from the raw socket peer plus trusted `Forwarded: for=` or `X-Forwarded-For` headers. It reuses `EffectiveOriginResolver.TrustPolicy`, supports trusted proxy predicates or IP allowlists, prefers standardized `Forwarded` values, accepts only IP literals, and falls back to the socket peer when forwarded headers are untrusted or unavailable.
- MCP now recognizes `notifications/cancelled` as a framework-managed JSON-RPC notification. Soklet validates the session, exposes `McpOperationType.NOTIFICATIONS_CANCELED` to MCP admission/interceptor/lifecycle/metrics hooks, accepts the notification without a response body, and signals matching in-flight handlers through `McpCancelationToken`.
- Standard HTTP responses can now opt into dynamic gzip compression for eligible finalized in-memory byte-array and `ByteBuffer` responses with `HttpServer.Builder.responseGzipPolicy(...)`. Compression is negotiated with `Accept-Encoding`, updates `Vary: Accept-Encoding`, skips already-encoded, range, streaming, and file responses, and includes `ResponseGzipPolicy.fromDefaultsWithMinimumBodySizeInBytes(...)` for common text-like response media types.
- Standard HTTP now supports `Expect: 100-continue` for fixed-length and chunked request bodies by sending an interim `100 Continue` response before reading the body. Unsupported expectations now return `417 Expectation Failed` instead of being treated as malformed requests.
- `MarshaledResponse.withFile(...).contentEncoding(...)` now provides a dedicated way to set `Content-Encoding` for already-compressed file responses while preserving file-response validators and range behavior.

### Behavior Changes

- The default in-memory MCP session store now has `McpSessionStore.builder()` options for idle timeout, session ID generation, and a default `8_192` active-session cap. Reaching that cap rejects new `initialize` requests with HTTP 503 before endpoint initialization runs.
- SSE and MCP event streams now default to a 30 second write timeout so stalled stream readers are disconnected by default. Set `SseServer.Builder.writeTimeout(Duration.ZERO)` or `McpServer.Builder.writeTimeout(Duration.ZERO)` to disable stream write timeouts.
- Standard HTTP, SSE handshakes, and MCP transport requests now enforce a separate 64 KB `maximumHeadersSizeInBytes` default in addition to header-count, request-target, and total request-size limits. Use `HttpServer.Builder.maximumHeadersSizeInBytes(...)`, `SseServer.Builder.maximumHeadersSizeInBytes(...)`, or `McpServer.Builder.maximumHeadersSizeInBytes(...)` to tune it.
- `ShutdownTrigger.ENTER_KEY` now treats stdin EOF as unsupported instead of stopping servers unexpectedly. IDE consoles such as IntelliJ are supported even when `System.console()` is unavailable.

### Packaging

- Added `Automatic-Module-Name: com.soklet` to the core JAR manifest for stable JPMS module naming.

### Fixes

- Standard HTTP shutdown now stops accepting new connections, closes idle keep-alives, and lets already-dispatched handlers flush their responses before force-closing remaining connections at `shutdownTimeout`. Responses produced during drain include `Connection: close`.
- HTTP and SSE accept-loop failures are now contained and surfaced instead of silently leaving dead or partially started servers behind.
- SSE startup bind failures now participate in normal start failure rollback semantics.
- `DefaultMetricsCollector` no longer leaks in-flight request state when a `RequestInterceptor` substitutes the `Request`.
- MCP GET stream rejection paths no longer leak active stream or session-pinning state.
- MCP internal session messages now route to the newest live GET stream by stream registration time, so out-of-order stream header completion cannot make an older stream receive new session messages.
- MCP tool progress notifications now publish immediately to the session's active same-node GET stream when one exists, instead of always buffering progress until the tool call completes. The existing progress-upgraded POST event-stream response remains the fallback when no live GET stream is available.
- Timeout scheduler callbacks are now isolated so one failing timeout task cannot terminate the scheduler worker and silently disable later timeouts.
- HTTP request-handler and SSE handshake timeout tasks no longer retain stale handler-thread references after the handler task returns, preventing late timeouts from interrupting unrelated work on a reused executor thread.
- Standard HTTP responses no longer synthesize `Content-Length: 0` for `1xx`, `204`, `304`, or empty `HEAD` responses where no length was explicitly set.
- SSE shutdown now gives established streams the configured `shutdownTimeout` window to flush already-queued events before force-closing stragglers, and SSE listen sockets now enable address reuse before bind.

## 3.3.0 (2026-06-10)

### Behavior Changes

- Standard HTTP non-streaming responses now have a 60 second write-idle timeout by default. This protects fixed-length and file responses from stalled readers after request handling completes. Set `HttpServer.Builder.responseWriteIdleTimeout(Duration.ZERO)` to restore the previous no-timeout behavior.
- Standard HTTP now defaults to a maximum of 8192 concurrent connections, and MCP live GET streams now default to the same 8192 concurrent-connection cap as SSE. Reaching the limit rejects new connections gracefully (logged, and counted via `MetricsCollector` connection-rejection metrics). Standard HTTP's builder method was renamed from `maximumConnections(...)` to `concurrentConnectionLimit(...)` to match SSE and MCP. Set `concurrentConnectionLimit(0)` on the `HttpServer`, `SseServer`, or `McpServer` builder to disable the cap entirely; `SseServer` previously rejected `0`.
- On virtual-thread runtimes, MCP live GET streams are now processed with one virtual-thread task per established stream so long-lived streams are not limited by MCP request-handler concurrency. On runtimes without virtual threads, MCP live stream processing continues to use the bounded fallback executor, so large live-stream deployments should run on JDK 21+ or provide their own external connection cap. If `McpServer.Builder.concurrentConnectionLimit(0)` is used on a virtual-thread runtime, Soklet no longer has an internal stream-count backstop; use it only when a proxy, load balancer, or OS-level limit provides one.
- Idle MCP sessions reclaimed by the opportunistic expiry sweep now emit MCP session-termination lifecycle callbacks and metrics with reason `IDLE_TIMEOUT` instead of being removed silently.
- MCP transport requests with an `Origin` header are now rejected with HTTP 403 unless the configured `McpCorsAuthorizer` authorizes that origin. This turns the MCP CORS policy into an explicit Origin-validation gate for DNS-rebinding defense.
- MCP JSON-RPC messages with unknown id-less methods are treated as notifications: after normal MCP session/protocol validation, admission, interception, lifecycle, and metrics handling, Soklet returns `202 Accepted` without a JSON-RPC error body. Admission and interceptor contexts see `McpOperationType.UNKNOWN` for these messages. Unknown methods with an `id` still receive `-32601 Method not found`; an explicit JSON-RPC `"id": null` is treated as a request id, not as an absent-id notification.
- Trusted `Forwarded host=` and `X-Forwarded-Host` values used for effective-origin resolution now use the same strict host grammar as the `Host` header; invalid forwarded host values are ignored.
- Chunked request parsing is stricter: chunk data must be followed immediately by `CRLF`, chunk-size tokens may not include a leading sign, and chunk trailers must use valid HTTP header-field syntax.
- Hardened MCP JSON parsing with nesting-depth, number-token length, and exponent-magnitude limits.
- Hardened MCP JSON round-tripping: unpaired surrogate code units are rejected instead of being replaced during UTF-8 encoding, duplicate object keys and leading BOMs are rejected, U+2028/U+2029 are escaped on output, numbers serialize in compact canonical form, and the parser rejects any number whose canonical serialized form would exceed the configured number-length or exponent-magnitude caps. As a result parse and serialize stay self-consistent - anything Soklet parses, it can serialize and parse again.
- `SseServer` now requires virtual threads only to **start**, not to construct. An SSE-configured `SokletConfig` can now be built and exercised with the off-network simulator on JDK 17-20; starting a *live* SSE server still requires JDK 21+. Previously, merely constructing an `SseServer` threw on a non-virtual-thread runtime.

### Observability

- Standard HTTP, SSE, and MCP transport failures such as response write-idle timeouts, write timeouts, event-loop task failures, selection-key failures, accept-loop failures, socket write errors, and socket read errors with request data in flight now emit `LogEventType.SERVER_TRANSPORT_FAILURE` and increment `MetricsCollector` transport-failure counters.
- Zero-progress HTTP, SSE, and MCP request-read timeouts, such as idle keep-alive reaps and browser/LB preconnects that never send bytes, close quietly instead of emitting `SERVER_TRANSPORT_FAILURE` or incrementing transport-failure counters.
- Standard HTTP remote socket resets with no request data in flight, such as browser/static-asset keep-alive churn, close quietly instead of emitting `SERVER_TRANSPORT_FAILURE` or incrementing transport-failure counters. Resets after partial request bytes are still recorded as read failures.
- MCP live-stream writes interrupted by intentional session termination no longer emit false `SERVER_TRANSPORT_FAILURE` events or transport-failure metric increments.

### Fixes

- Fixed `HttpDate.toHeaderValue(Instant)` so it rejects instants outside the four-digit IMF-fixdate year range instead of rendering invalid header values or leaking formatter exceptions.
- Hardened the low-level HTTP event loop so unchecked task failures are contained to the affected connection instead of terminating the event loop thread.
- Fixed multipart parsing for unnamed parts and made multipart header decoding explicitly UTF-8.
- Closed accepted SSE socket channels on pre-submit setup failures.
- Hardened the low-level HTTP worker loop so unchecked loop-skeleton failures stop the server instead of leaving a dead worker loop that can attract new connections.
