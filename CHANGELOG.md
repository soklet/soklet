# Changelog

## 3.2.1-SNAPSHOT

- Standard HTTP non-streaming responses now have a 60 second write-idle timeout by default. This protects fixed-length and file responses from stalled readers after request handling completes. Set `HttpServer.Builder.responseWriteIdleTimeout(Duration.ZERO)` to restore the previous no-timeout behavior.
- Standard HTTP, SSE, and MCP transport failures such as response write-idle timeouts, write timeouts, event-loop task failures, selection-key failures, accept-loop failures, and socket read/write errors now emit `LogEventType.SERVER_TRANSPORT_FAILURE` and increment `MetricsCollector` transport-failure counters.
- Idle MCP sessions reclaimed by the opportunistic expiry sweep now emit MCP session-termination lifecycle callbacks and metrics with reason `IDLE_TIMEOUT` instead of being removed silently.
- Hardened the low-level HTTP event loop so unchecked task failures are contained to the affected connection instead of terminating the event loop thread.
- Hardened MCP JSON parsing with nesting-depth, number-token length, and exponent-magnitude limits.
- Hardened MCP JSON round-tripping: unpaired surrogate code units are escaped on output instead of being replaced during UTF-8 encoding, numbers serialize in compact canonical form, and the parser rejects any number whose canonical serialized form would exceed the configured number-length or exponent-magnitude caps. As a result parse and serialize stay self-consistent under those caps - anything Soklet parses, it can serialize and parse again.
- Fixed multipart parsing for unnamed parts and made multipart header decoding explicitly UTF-8.
- Closed accepted SSE socket channels on pre-submit setup failures.
- `SseServer` now requires virtual threads only to **start**, not to construct. An SSE-configured `SokletConfig` can now be built and exercised with the off-network simulator on JDK 17-20; starting a *live* SSE server still requires JDK 21+. Previously, merely constructing an `SseServer` threw on a non-virtual-thread runtime.
