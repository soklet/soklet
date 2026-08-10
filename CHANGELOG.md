# Changelog

## 3.6.0 (Unreleased)

### Breaking Changes

- Replaced the MCP 2025-11-25 implementation and public API with a greenfield
  MCP 2026-07-28 design. The new transport has no session or initialization
  lifecycle, and the new Java API is intentionally source- and binary-
  incompatible in this minor release. Applications that require the legacy MCP
  implementation must remain on Soklet 3.5.x; 3.6.0 provides no compatibility
  adapter.

### Features

- Added a dedicated MCP 2026-07-28 Streamable HTTP server in core Soklet. It
  owns an independent listener and port, integrates with `SokletConfig`, and
  supports discovery as the first request without a session or initialization
  handshake.
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
  checkpoint. This is not a ninth production vertical: the implemented
  observability and diagnostics vertical count remains eight. Its five new
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
- Added a separate internal Phase 6 trace-correlation derivation/capture
  checkpoint without creating a ninth production vertical; the completed
  production-vertical count remains eight. Disabled controls return no token.
  Enabled controls snapshot one complete active key ID and key-material pair
  under the shared security lock, then derive after releasing it with
  HMAC-SHA-256 over UTF-8 `soklet-mcp-trace-correlation-v1\0` plus the decoded
  16-byte trace ID. The first 16 digest bytes become an unpadded 22-character
  Base64URL token. Invalid/all-zero trace IDs are rejected before derivation;
  equal key/trace inputs agree, changed inputs differ, and concurrent rotation
  yields only coherent old or new `(keyId, token)` pairs. Copied key material
  and explicit derivation buffers are zeroed, and carrier rendering redacts
  the token.
- This checkpoint advances `SOK-TRACE-001` and `SOK-TRACE-002` to PARTIAL,
  leaves `SOK-TRACE-003` COMPLETE, leaves `SOK-TRACE-004` and
  `SOK-TRACE-005` PLANNED, and leaves `SOK-PRIV-001` PARTIAL. Its
  package-private seam adds no public API and is not integrated into request
  lifecycles or a structured-log carrier, field, emission cadence, or
  `LogEventType`. It enables no raw trace-ID logging and adds no metric, event,
  diagnostics/snapshot field, aggregate, label, or wire dimension. Tokens are
  pseudonymous high-cardinality operational metadata, not anonymization or
  authentication/authorization inputs. This is not broader trace/baggage-
  redaction, cardinality, privacy, security, sustained-coverage, release-
  readiness, or Phase 6 freeze proof.

### Development Status

- The locally frozen Phase 4 and Phase 5 surfaces implement discovery, tools,
  prompts, resources, progress, cancelation, subscription delivery, multi-
  round-trip execution, and protected request-state execution. All 39 reviewed
  Phase 5 profiles are active. Eight bounded Phase 6 verticals—shutdown,
  handler-capacity, handler diagnostics, stream/subscription diagnostics,
  protection/trace diagnostics, serialized semantic-event delivery, and
  bounded pre-admission and transport metrics—are implemented and locally
  green. The separate fuzz-registration checkpoint above leaves that count at
  eight, as does the internal trace-correlation checkpoint. The focused trace-
  foundation regression run passes 53/0/0/0. The prior focused five-target
  fuzz run remains 28/0/0/0 and was not rerun for this checkpoint; the prior
  deterministic full fuzz corpus replay on both JDKs remains 127/0/0/0 and was
  likewise not rerun. Exact-source full main suites on JDK 21 and JDK 26 each
  report 1,462/0/0/4. The JDK 21 enforced static-analysis profile is
  green without counting advisory warnings, and SpotBugs reports 0/0. Exact
  API-freeze evidence remains unchanged at 556 incompatibilities, 206 reviewed
  owners, 1,049 Phase 4 records, and 195 Phase 5 records with the prior hashes.
  Candidate main, source, and Javadoc packages plus standalone Javadoc are
  green using offline-link resolution. All 167 API-sketch sources compile for
  Java 17 and pass Javadoc doclint on JDK 26.
  All 104 files from pinned JSON Schema commit
  `0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate. Default aggregation
  remains limited to `ServerStopped` and five handler variants. Unresolved
  aggregate families and `AMB-003`, request-lifecycle trace integration,
  structured-log carrier/emission, raw-ID opt-in, broader privacy/cardinality
  and redaction work, simulation, coverage-guided and
  sustained fuzz gates,
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
