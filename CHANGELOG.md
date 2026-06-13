# Changelog

## 3.3.1-SNAPSHOT (Unreleased)

### Behavior Changes

- SSE and MCP event streams now default to a 30 second write timeout so stalled stream readers are disconnected by default. Set `SseServer.Builder.writeTimeout(Duration.ZERO)` or `McpServer.Builder.writeTimeout(Duration.ZERO)` to disable stream write timeouts.
- Standard HTTP, SSE handshakes, and MCP transport requests now enforce a separate 64 KB `maximumHeadersSizeInBytes` default in addition to header-count, request-target, and total request-size limits. Use `HttpServer.Builder.maximumHeadersSizeInBytes(...)`, `SseServer.Builder.maximumHeadersSizeInBytes(...)`, or `McpServer.Builder.maximumHeadersSizeInBytes(...)` to tune it.

### Fixes

- Standard HTTP shutdown now stops accepting new connections, closes idle keep-alives, and lets already-dispatched handlers flush their responses before force-closing remaining connections at `shutdownTimeout`. Responses produced during drain include `Connection: close`.
- MCP internal session messages now route to the newest live GET stream by stream registration time, so out-of-order stream header completion cannot make an older stream receive new session messages.
- MCP tool progress notifications now publish immediately to the session's active same-node GET stream when one exists, instead of always buffering progress until the tool call completes. The existing progress-upgraded POST event-stream response remains the fallback when no live GET stream is available.
- Timeout scheduler callbacks are now isolated so one failing timeout task cannot terminate the scheduler worker and silently disable later timeouts.
- HTTP request-handler and SSE handshake timeout tasks no longer retain stale handler-thread references after the handler task returns, preventing late timeouts from interrupting unrelated work on a reused executor thread.

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
