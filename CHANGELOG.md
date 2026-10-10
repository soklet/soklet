# Changelog

## 4.0.0 (Unreleased)

### Breaking Changes

- **HTTP occurrence lists and strict text:** HTTP multi-value collections now
  use `List`, preserving duplicates, order and empty values. Headers retain
  physical occurrences rather than splitting comma lists. Malformed text is
  rejected instead of silently replacing invalid characters. See
  [HTTP values preserve every occurrence](MIGRATING_TO_4_0.md#http-values-preserve-every-occurrence).
- **Observer logging:** a custom observer replaces the default stderr logger;
  `didReceiveLogEvent` defaults to a no-op. Explicitly configure a logging sink.
  See [Log-event routing](MIGRATING_TO_4_0.md#log-event-routing-with-custom-observers).
- **Finite response framing:** the built-in HTTP transport owns framing and
  removes application hop-by-hop headers. Valid bodyless HEAD representation
  lengths supplied by final HEAD marshaling and validated `426` Upgrade
  advertisements are preserved. The default HEAD marshaler computes length
  from the body; bodyless explicit HEAD methods can use a custom `HeadHandler`
  or final `PostProcessor` to advertise nonzero lengths. Ordinary
  final statuses must be `200`–`599`, and the default marshaler rejects a
  `StreamingResponseBody` in `Response.body(...)`. Use a marshaled streaming
  response. Expected typed streaming cancelation is quiet; independent failures
  remain observable. Client disconnect and shutdown no longer produce routine
  `RESPONSE_STREAM_CANCELED` events. See [HTTP and SSE framing](MIGRATING_TO_4_0.md#http-and-sse-framing).
- **Forwarded client IP:** under `TRUST_PROXY_ALLOWLIST`, an unusable
  `Forwarded: for=` value stops the trusted-chain walk and falls back to the
  socket peer. Trusted proxies must emit IP literals; `Forwarded` without
  `for=` still permits `X-Forwarded-For` fallback. See
  [Forwarded origin and servlet client addresses](MIGRATING_TO_4_0.md#forwarded-origin-and-servlet-client-addresses).
- **File delivery:** the standard HTTP transport retains the real-socket
  `FileChannel.transferTo` path for the JDK's standard file channels, allowing
  native transfer when supported. Custom/provider channels use a typed socket
  adapter without retrying or probe-writing after uncertain partial progress.
  This can disable zero-copy for delegating and provider channels, reducing
  throughput and increasing CPU use; standard JDK channels retain native transfer.
  Failed native transfers use bounded file/socket probes to identify peer loss;
  ambiguous errors remain observable, and delivery ends after failure.
- **Histogram snapshots:** snapshot values are boxed and histogram sums are
  floating point. See [Histogram sums and snapshot values](MIGRATING_TO_4_0.md#histogram-sums-and-snapshot-values).
- **Graceful HTTP feeds:** implementations of `ResponseStream` must implement
  `isGracefulShutdownRequested()`. Indefinite feeds use that advisory signal to
  complete during graceful shutdown. See
  [HTTP streaming callbacks and sources](MIGRATING_TO_4_0.md#http-streaming-callbacks-and-sources).
- **Route ambiguity:** conflicting HTTP and SSE declarations are rejected at
  compilation or resolver construction instead of selecting a winner. See
  [HTTP and SSE route precedence](MIGRATING_TO_4_0.md#http-and-sse-route-precedence).
- **HTTP streaming API:** writers now receive one `ResponseStream` with output,
  request, cancelation-token, and timing access; the separate
  `StreamingResponseContext` is removed. Response builders and copiers add
  `stream(StreamingResponseWriter)`. Cancelation registrations use unchecked
  `CallbackRegistration.close()`, and input-stream/reader descriptors use
  checked `StreamResourceFactory` acquisition with renamed factory getters.
  `ResponseStream` adds `open`, `own`, and `using` with producer-thread ownership,
  coordinated abort/final close, lexical cleanup, and supervised resource lifetimes
  in HTTP and simulation. Retained output is invalid after managed finalization;
  unclassified producer interruption reports `APPLICATION_CANCELED`.
  Publisher adapters now retain asynchronous subscription acquisition and entered
  provider calls through physical completion, including late cancelation and
  synchronous completion followed by a blocked provider return.
  Added array-slice writes and independently closeable
  `OutputStream` views with shared scalar buffering, accepted-prefix interruption
  accounting, and owned encoder finalization before response completion.
  HTTP builders expose lifecycle capacity, callback concurrency, and cleanup
  timeout with defaults of 256, four, and five seconds; nullable resets and
  cross-setting validation apply at build time. Derived simulators inherit the
  built-in HTTP server's effective settings without reusing its transport.
  See [HTTP streaming callbacks and sources](MIGRATING_TO_4_0.md#http-streaming-callbacks-and-sources).
- **SSE initialization and admission:** replaced
  `Consumer<SseUnicaster>` initialization with checked `SseClientInitializer`,
  without compatibility overloads. The initializer remains a one-time,
  synchronous, bounded setup/catch-up step; ongoing delivery uses
  `SseBroadcaster`. Queue overflow is terminal even when caught. Lifecycle
  admission precedes accepted headers and returns HTTP 503 when exhausted.
  The SSE builder adds `streamingLifecycleCapacity` (default 256), separate from
  the 128-write connection queue. Derived simulators inherit both settings;
  simulated accepted results are `AutoCloseable`, with client-disconnect close
  and server-stopping teardown even without consumers. The first termination
  outcome wins, and admitted work remains accounted for through physical exit.
  See [SSE client initialization and connection admission](MIGRATING_TO_4_0.md#sse-client-initialization-and-connection-admission).
- **SSE write deadlines:** `writeTimeout` applies to stream, handshake and
  rejection-response writes. `Duration.ZERO` disables all of those deadlines,
  including failsafe responses. Keep a finite deadline when clients can stall.
- **Naming and collection APIs:** completed the scoped 4.0 renames for streaming
  bodies, transport attachment signals, metric route properties, servlet response
  factories, and MCP role types. MCP endpoint/registration/output/page builders
  now use whole-list replacement properties without additive aliases. See
  [Naming and collection replacements](MIGRATING_TO_4_0.md#naming-and-collection-replacements).
- **Response compression:** replaced `ResponseGzipPolicy` and
  `HttpServer.Builder.responseGzipPolicy(...)` with `ResponseCompressor` and
  the sole `responseCompressor(...)` setting, without deprecated aliases.
  A compressor returns `ResponseCompressionPlan.none()` or a plan selecting
  a `ResponseCompressionCodec`, optionally with an application-owned cache
  callback. Gzip remains the only built-in codec, and compression remains
  disabled by default. See [Response compression](MIGRATING_TO_4_0.md#response-compression).
- **HTTP server type:** `ServerType.STANDARD_HTTP` is now `ServerType.HTTP`,
  with no deprecated alias, and `ServerType.MCP` is removed in favor of the
  dedicated MCP lifecycle and metrics APIs. Update source references, switch cases, and stored
  enum names, then recompile integrations. The built-in Prometheus/OpenMetrics
  `soklet_transport_failures_total` label changes from
  `server_type="STANDARD_HTTP"` to `server_type="HTTP"`; update metric queries,
  dashboards, and alerts. The explicit OpenTelemetry `soklet.server.type`
  vocabulary remains `http`, `sse`, and `mcp`. See
  [HTTP server type](MIGRATING_TO_4_0.md#http-server-type).
- **Metric snapshot keys:** thirteen public HTTP/SSE/transport
  `MetricsCollector` key records are now final classes with getter accessors;
  for example, `method()` becomes `getHttpMethod()` and `route()` becomes
  `getResourcePathDeclaration()`. Record deconstruction no longer applies. See
  [Metric snapshot keys](MIGRATING_TO_4_0.md#metric-snapshot-keys).
- **Servlet integrations:** both javax and Jakarta adapters move to 2.0.0
  and require Soklet 4.0.0. The former 3.x compatibility baseline is removed;
  applications must declare their core dependency explicitly because the
  adapters retain provided scope. See
  [Servlet adapters](MIGRATING_TO_4_0.md#servlet-adapters).
- **Aggregate lifecycle:** `Soklet` is now a one-shot owner of every configured
  transport. `HttpServer`, `SseServer`, and `McpServer` no longer expose direct
  start/stop/status/close ownership. `Soklet.shutdown()` returns the one cached
  completion stage, `awaitShutdown()` returns immutable terminal evidence, and
  lifecycle observers receive aggregate or participant shutdown results. The
  old failure-only stop callbacks and config copier are removed. See
  [Lifecycle and process ownership](MIGRATING_TO_4_0.md#lifecycle-and-process-ownership).
- **Shared deadlines:** transport-specific shutdown-deadline setters are
  replaced by one `LifecyclePolicy`. The defaults are 30 seconds for normal
  startup, 2 seconds for cancellation of live startup, 15 seconds graceful,
  and 3 seconds forced. This lengthens the old HTTP, SSE, and MCP defaults;
  deployments must recalculate their termination grace. See
  [changed defaults](MIGRATING_TO_4_0.md#shared-lifecycle-policy-and-changed-defaults).
- **Standalone runner:** process shutdown hooks, the `ENTER_KEY` trigger, the
  terminal report, and optional bounded application cleanup now belong to
  `SokletApplication`. Embedders continue to own direct `Soklet` lifecycle and
  must not mix the two ownership models. See
  [standalone applications](MIGRATING_TO_4_0.md#standalone-applications-use-the-runner).
- **Transport SPI and injection:** custom HTTP/SSE implementations migrate
  to stable transport identity, attachment/runtime, lifecycle context, and
  termination-proof contracts. `HttpServer` is no longer injectable into
  resource methods; `SseServer` broadcaster access remains supported.
  `McpServer` is now sealed to Soklet's built-in HTTP/1.1 implementation and
  has no custom transport SPI, so a 3.5.1 custom `McpServer` implementation has
  no direct 4.0.0 replacement. See
  [HTTP, SSE, and custom transports](MIGRATING_TO_4_0.md#http-sse-and-custom-transports).
- **MCP listener hardening controls:** restored all nine 3.5.1
  `McpServer.Builder` transport-limit setters on the sealed built-in listener.
  Existing defaults remain 10 MiB per request body, 60 seconds per header/body
  read phase, 100 headers, 64 KiB aggregate headers, an 8,192-byte request
  target, a 64 KiB read buffer, 8,192 concurrent connections, and a 128-item
  stream queue. Configured request bodies are bounded by the 16 MiB
  production-JSON ceiling, while any single JSON string or token remains
  capped at 1,048,576 characters; `connectionQueueCapacity` is an alias of
  `streamQueueCapacity`. A loopback bind literal or `localhost` seeds its
  effective Host authority; every non-loopback bind now requires at least one
  explicit `allowedHosts(...)` entry or server construction fails.
- **Fixed MCP endpoint paths:** templated endpoint HTTP paths and
  `@McpEndpointPathParameter` have no 4.0.0 replacement. Register separate fixed
  endpoints for a bounded tenant set, or use application-authenticated
  admission/header tenancy. Resource URI templates remain supported; the
  retained request/admission endpoint-path-parameter maps are always empty.
- **Simulator:** the static `Soklet.runSimulator` entry points are removed.
  `Simulator.performMcpRequest` is replaced by `startMcpRequest`, returning
  `McpSimulation`; inspect its completion instead of the removed
  `onMcpStreamError` callback.
  `SokletSimulator.run` now accepts either an existing `SokletConfig` or a
  single-use `SimulatorConfig`, supplies a scope-bound `Simulator` to the
  simulation body, and returns the simulation's shutdown result. Build a fresh
  off-network HTTP, SSE, and MCP graph separately with `SimulatorConfig.Builder`.
  See
  [Simulator migration](MIGRATING_TO_4_0.md#simulator-migration).
- **MCP wire/profile:** the 3.5.1 session and GET/SSE replay design is removed.
  The `2026-07-28` profile uses direct requests or `server/discover`, with
  per-request version/capabilities and no session lifecycle. An explicitly
  selected `2025-06-18` or `2025-11-25` stateless compatibility adapter accepts
  `initialize`, `notifications/initialized`, `ping`, synchronous
  `tools/list`/`tools/call`, ordinary prompts/resources, argument completion,
  and request-scoped POST progress.
  Sessions remain disabled unless both endpoint revisions and server
  ownership/bounds are configured. Optional HTTP admission enables leased GET
  opening, verified DELETE, and authorized resource/catalog invalidations. Applications declare exact revisions
  on endpoints and tools; there is no implicit profile fallback. See
  [current MCP compatibility](MCP.md#compatibility-and-unsupported-features).
  Client feature support varies; verify the features and protocol revision
  you intend to deploy.
- **2025 argument completion:** prompt and URI-template completers now select
  `2025-06-18`, `2025-11-25`, and `2026-07-28` independently within their
  owning operation's revisions. Annotation and programmatic declarations use
  the same handlers, fresh admission, request limiting, and bounded results.
  Capabilities and dispatch use the selected revision; modern-only completers
  stay hidden from 2025 requests. On a Completion-enabled legacy view, a
  visible target with a declared argument and no enabled completer returns
  empty suggestions.
- **2025 POST progress:** the existing reporter now supports explicitly selected
  `2025-06-18` and `2025-11-25` operations with valid progress tokens. The first
  accepted update commits SSE; no update returns JSON. Progress and the whole
  terminal result/error use the selected legacy projection through the existing
  bounded stream. A committed legacy SSE disconnect or lost-writer write failure
  detaches delivery and makes
  later reports inert without itself canceling work; deadlines and physical
  worker ownership remain. Finite/uncommitted and queued legacy disconnects
  still cancel, as do modern disconnects. Simulation follows the same distinction.
  Legacy streams omit event IDs and November's recommended empty priming event
  because they are persistent and nonresumable; no replay or recovery is added.
- **2025 static catalog pagination:** framework `tools/list`, `prompts/list`,
  static `resources/list`, and `resources/templates/list` use bounded pages
  when the selected catalog exceeds existing response or localization limits.
  A fitting catalog remains one page; continuations use current admission,
  request limits, and applicable catalog policy. Framework cursors have a
  separate 2,048-byte ceiling and neutral `-32602` mismatch handling; they are
  unsigned navigation data with no retained session or snapshot. Custom-list
  cursors and modern static catalog behavior are unchanged. No public API is
  added.
- **2025 minimum sessions:** `McpSessionConfig` and its required stable owner
  resolver configure bounded node-local sessions for explicitly selected June
  and November endpoint revisions. Every use is freshly admitted and owner,
  path, revision, and lifecycle-bound before remembered public client metadata
  is supplied. Anonymous allocation requires explicit opt-in. Active-request
  cancellation now targets only the verified session and preserves a terminal
  response whose reservation won first; physical work/evidence accounting
  survives logical cancellation or expiry. Default/modern paths remain
  stateless.
  `CLIENT_CANCELED` is a
  neutral shared token reason; MCP adds `SESSION_EXPIRED`/`SESSION_CLOSED`.
  Recompile exhaustive enum switches and update downstream mappings. The
  removed 3.5.1 store/context/ID-generator APIs are not restored.
- **2025 session HTTP transport:** an optional provisional admission controller
  authorizes real GET/DELETE requests with a fresh identity, explicit expiry,
  and a subset of offered families. GET requires selected legacy subscriptions
  and effective sources, opens bounded leased SSE with keepalives, and renews
  against the original credentials. Verified DELETE returns `204` and retires
  the session with `SESSION_CLOSED`. Modern and session-disabled views keep
  `405`. These paths add no fabricated RPC context or RPC metrics/limiter call.
- **2025 GET notifications:** session-owned `resources/subscribe` and
  `resources/unsubscribe` use fresh admission, request limiting, readable-route
  checks, and independent URI authorization; success returns `result: {}`.
  Duplicate subscribe replaces historical evidence under a new generation;
  unsubscribe/reconciliation fence establishing and active grants. URI grants
  and bounded catalog/URI dirty bits survive GET gaps. The newest eligible GET
  receives coalesced resource/catalog hints with authorization checked at every
  socket write; revoked unwritten frames are purged and partially written frames
  close before further bytes. Grant, URI-byte, encoded-output, and aggregate
  maintenance caps retain physical ownership through callback exit. Due GET and
  URI renewals share deadline priority so longer-lived grants cannot take every
  maintenance slot ahead of an urgent renewal. This does not interrupt running
  callbacks or extend authorization deadlines. Delayed fence cancellation only
  targets older-generation checks, preserving fresh GET and URI renewals under
  the fenced generation. Refreshed GET credentials do not replace URI grant
  credentials; each URI needs a fresh or duplicate subscribe. No Tasks,
  event history, replay, or POST result recovery is added. `McpOperationType`
  adds `RESOURCES_SUBSCRIBE`/`RESOURCES_UNSUBSCRIBE`; update exhaustive switches.
  Client refresh behavior and limitations are described in
  [client compatibility](https://soklet.com/docs/mcp-compatibility).
- **MCP Java API:** the old sessions, initialization contexts, handlers,
  schemas, request results, and value carriers are removed. Applications use
  immutable `McpJson*` values, operation-specific contexts and registrations,
  Java-derived Tool Schema Profile 1 schemas, per-request admission identity,
  explicit invocation features/interceptor continuation, and aggregate
  lifecycle results. See
  [current MCP endpoint authoring](MCP.md#endpoint-authoring).
- **MCP logging metadata:** removed `McpLogLevel` and
  `McpRequestContext.getLogLevel()`. Soklet does not implement MCP Logging.
  The request metadata field remains internally validated and available through
  `McpRequestContext.getRequestMetadata()`; applications use their own logging
  and Soklet's observability/OpenTelemetry integrations.
- **MCP client input:** removed the Roots and Sampling request types,
  declarations, capabilities, and annotation configuration before release.
  Multi-round input supports form and URL elicitation; applications pass file
  information through tool parameters or resource URIs and call model providers
  directly when needed.
- **Public naming pass:** `CorsPreflight.with(...)` is now
  `fromOrigin(...)`; MCP input requests expose `getJsonRpcMethod()`; HTTP route
  metric keys expose `getHttpMethod()`; and the `McpEndpoint` server-information
  getter/builder family consistently uses `ServerInfo`/`serverInfo`. These are
  hard renames with no deprecated aliases. See
  [the naming migration](MIGRATING_TO_4_0.md#public-api-naming-pass).
- **Annotation processing:** MCP endpoints and operations are generated at
  compile time; runtime handler-method scanning is not a fallback. Builds must
  enable `SokletProcessor`, retain `-parameters`, and preserve generated
  resources. `@McpArgument`/`@McpListResources` and the old URI parameter
  annotations are replaced by the current tool/property/resource-list/resource-
  URI annotations. See
  [Annotation-processing migration](MIGRATING_TO_4_0.md#annotation-processing-migration).
- **Request diagnostics:** framework-created failures no longer embed request-
  controlled values or retain input-bearing URL/conversion causes. Code that
  parsed messages, `Request.toString()`, or cause chains must use typed
  accessors and application-owned redaction. See
  [Request diagnostics and privacy](MIGRATING_TO_4_0.md#request-diagnostics-and-privacy).
  Custom request-body marshalers must translate expected malformed-input
  failures to `IllegalRequestBodyException`; unexpected runtime failures remain
  HTTP 500 server faults.

Soklet 3.x reaches end of life when 4.0.0 is published; it receives no promised
maintenance or security fixes afterward. See the explicit
[supported-release policy](MIGRATING_TO_4_0.md#supported-release-lines).

### Release Highlights

- **MCP Apps CSP convenience:** `McpAppResourceMetadata.ContentSecurityPolicy.defaultInstance()`
  returns the immutable policy with empty origin allowlists. The metadata getter
  remains optional, preserving omitted versus explicitly supplied CSP metadata.
- Response compression now supports application-provided codecs and caching
  of compressed body bytes through lazy `ResponseCompressionPlan` providers.
  Soklet retains encoding acceptance, `Vary`, validator, and framing handling;
  there is no shared compression cache in core. Eligible `HEAD` responses
  select a plan from the uncompressed representation without invoking its
  body provider or codec.
- Added a dedicated, zero-runtime-dependency MCP `2026-07-28` server with
  annotated and programmatic tools, prompts, exact/template resources,
  Java-derived typed schemas, multi-round-trip input, progress, cooperative
  cancellation, resource subscriptions, localization, simulation, admission,
  limiting, interception, sanitization, and bounded diagnostics.
- Added the `io.modelcontextprotocol/tasks` extension with application-owned
  durable state through
  [`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html),
  typed task creation, `tasks/get`, `tasks/update`, `tasks/cancel`, optional
  task-status subscriptions, cross-node recovery when backed by a shared
  application-provided manager, and simulator parity. The
  explicit in-memory manager is bounded and process-local; it is intended for
  development, tests, and deliberately ephemeral single-process use, not as a
  production durability or worker system. The Tasks extension retains a
  provisional protocol/API maturity label.
- Added Skills bundles with immutable files and generated manifests,
  `skills/list`, `skills/get`, authorized `resources/read`, locale groups,
  and independent access and discovery policies.
- Added one lifecycle coordinator and immutable result model across HTTP, SSE,
  MCP, direct embedders, the standalone runner, and the off-network simulator.
- Added a copy/paste [MCP quickstart](MCP_QUICKSTART.md), prose
  [3.5.1 migration guide](MIGRATING_TO_4_0.md),
  [client compatibility guide](https://soklet.com/docs/mcp-compatibility), and
  worked [application-owned OAuth resource-server pattern](release/MCP_OAUTH_RESOURCE_SERVER.md).
- Added explicit license/NOTICE packaging and a tracked
  [third-party audit](release/THIRD_PARTY_AUDIT.md).

### Correctness Fixes

- The standard HTTP listener now enables `TCP_NODELAY` on accepted connections,
  avoiding Linux delayed-ACK stalls for small keep-alive HTTP and MCP responses.
  Failure to apply this optional tuning leaves normal socket I/O handling intact.
- Protocol numbers no longer depend on the JVM's default formatting locale:
  file Content-Range values, default weak ETags, cookie Max-Age, SSE error
  status/length fields, and servlet request/redirect ports use ASCII decimal
  digits. Arabic/Persian formatting locales previously caused response
  validation failures or malformed wire output. Application-selected display
  formatting is unchanged.
- EntityTag factories now reject characters above `0xFF`, including surrogate
  code units, and parsing returns empty for those invalid values. Legal
  `0x80–0xFF` obs-text remains accepted; ETags are not restricted to ASCII.

### Security Hardening

- Fixed a configured request-header and trailer size-limit bypass when a
  pipelined input buffer was compacted after a section began. Section sizes
  could be undercounted by the discarded buffer prefix, allowing requests to
  exceed their configured limits and weakening resource-exhaustion defenses.
  Accounting now stays request-relative across compaction, and rejected-input
  capture remains confined to the offending request.
- Content-Length now requires ASCII digits before numeric conversion, rejecting
  signed forms such as `+5` and `-0` as malformed requests. Numeric overflow
  continues to follow the malformed-request response path. These changes
  harden HTTP framing; no request-smuggling exploit chain is claimed.

### Known Limitations

- Persisted output schemas are compiled eagerly during task lookup, including
  subscription authorization. This introduces bounded additional CPU work.

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
