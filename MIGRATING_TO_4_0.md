# Migrating from Soklet 3.5.1 to 4.0.0

Soklet 4.0.0 is a deliberate breaking release. Upgrade the lifecycle first,
then HTTP/SSE integration and simulation. Do not place the
4.0.0 JAR under an unchanged 3.5.1 application and expect binary compatibility.
The machine-readable [incompatibility ledger](api/mcp/current-incompatibilities.jsonl)
is an audit aid; this guide is the migration path.

For MCP development, start with the [MCP quickstart](MCP_QUICKSTART.md) and
[current API and wire reference](MCP.md).

## Supported release lines

Until 4.0.0 is published, 3.5.1 remains the latest supported release. On the
date 4.0.0 is published, the entire 3.x line reaches end of life: it receives
no new features, compatibility work, maintenance releases, or promised
security fixes. Published 3.x artifacts remain available, but applications
that stay on them do so without project support.

After publication, only the latest 4.x patch release is supported. Snapshots,
older 4.x patches, and unreleased source builds are not supported releases.

## Recommended migration order

1. Move the build to Java 17 or later and set the Soklet coordinate to
   `com.soklet:soklet:4.0.0`.
2. Replace direct transport lifecycle calls and per-server shutdown settings
   with the Soklet-wide lifecycle.
3. Choose either `SokletApplication` for a standalone process or direct
   `Soklet` ownership for an embedder.
4. Pass the completed application configuration to
   [`SokletSimulator`](https://javadoc.soklet.com/com/soklet/SokletSimulator.html);
   add a simulator-specific builder only where a test needs an override.
5. Recompile all annotation-driven code with `-parameters` and
   `SokletProcessor` enabled.
6. Update deployment termination budgets, lifecycle observers, metrics, and
   downstream integrations.
7. Exercise the application through a real loopback listener in addition to
   off-network simulation.

### HTTP values preserve every occurrence

HTTP value collections now use ordered lists instead of sets:

| Values | Type |
| --- | --- |
| Query and form parameters, request cookies, request and response headers | `Map<String, List<String>>` |
| Multipart fields grouped by name | `Map<String, List<MultipartField>>` |
| Response cookies | `List<ResponseCookie>` |

This applies to `Request`, `Response`, `MarshaledResponse`, `MultipartParser`,
`SseHandshakeResult.Accepted`, `McpSimulationResponse`, HTTP utilities, proxy
resolvers, and static-file header resolvers, including their builders and copiers.
Custom implementations and integrations must use the new signatures.

Replace HTTP value factories such as `Set.of("value")` with `List.of("value")`.
Keep genuine sets, including allowed HTTP methods, CORS policy header names,
trusted proxy addresses, and MCP protocol versions and capabilities.

```java
Request request = Request.withPath(HttpMethod.GET, "/items")
    .queryParameters(Map.of("id", List.of("one", "one", "two")))
    .build();

// Returns all three occurrences, in order.
List<String> ids = request.getQueryParameters().get("id");

Response response = Response.withStatusCode(200)
    .headers(Map.of("X-Example", List.of("first", "first", "second")))
    .cookies(List.of(ResponseCookie.with("session", "value").build()))
    .build();
```

The maps and nested lists returned by built requests and responses are immutable
snapshots. A copier's consumer overload provides mutable maps and lists:

```java
Response updated = response.copy()
    .headers(headers -> headers.get("X-Example").add("third"))
    .finish();
```

Identical values count as separate occurrences. Single-value request accessors
and scalar annotation parameters reject more than one occurrence with the
existing request exceptions; default HTTP error handling returns a bad request.
Use list accessors or annotated `List<T>` parameters when repetition is allowed.
Header names remain case insensitive, while query, form, and cookie names remain
case sensitive. Header and outgoing-cookie values retain their supplied order.

Outgoing header validation is stricter than 3.5.1: names are checked exactly as
stored, so leading or trailing whitespace is rejected rather than validating a
trimmed copy and later serializing the original. Header values also reject the
literal DEL character (`0x7F`). Existing valid Latin-1 obs-text remains supported.

Each request-header List entry now represents one complete field occurrence.
For example, `Accept-Encoding: gzip, deflate, br` becomes
`List.of("gzip, deflate, br")`, and a scalar `@RequestHeader String` receives
the whole value. Physical header parsing no longer splits selected fields at
commas. Media negotiation, locales, CORS, and compression parse their own lists.
Empty field values are preserved, including repeated empties. Surrounding HTTP
spaces and tabs are removed consistently in physical and map-backed requests;
other whitespace is retained by the header accessors. A single empty field
returns `Optional.of("")` from `getHeader()`, while default annotation conversion
maps one blank `Optional<T>` value to `Optional.empty()`. Repeated fields still
fail scalar access and binding, including identical or empty repeats. Servlet
`getHeader()` keeps its first-occurrence contract, and `getHeaders()` exposes
each complete occurrence.

For signatures or ordering across different query names, use `Request.getRawQuery()`;
a grouped map preserves per-name order rather than the entire interleaved query.

### Multipart field equality

`MultipartField.equals()` and `hashCode()` now compare binary contents, along
with name, filename, content type and explicitly configured charset. Fields
built with separate arrays containing identical bytes compare equal and work
as equivalent hash-map or hash-set keys. Lazy string decoding does not affect
equality. Null and empty data arrays still represent an absent value.

Equal multipart occurrences remain separate entries in the request's ordered
lists. Scalar accessors and bindings still reject repeated occurrences.
Backing bytes remain shared without defensive copying; do not modify the input
array after building a field or the array returned by `getData()`. Clone the
array when you need a mutable working copy.

### Public API naming pass

The 4.0.0 release candidate uses the following names without deprecated aliases.
Applications built against an earlier 4.0.0 preview must update these calls; the
`CorsPreflight` factory rename also applies directly to 3.5.1 applications.

| Previous name | 4.0.0 name |
| --- | --- |
| `CorsPreflight.with(...)` | `CorsPreflight.fromOrigin(...)` |
| `MultipartField.Copier.contentType(Charset)` | `MultipartField.Copier.charset(Charset)` |
| `MetricsCollector.HttpServerRouteKey.getMethod()` | `getHttpMethod()` |
| `MetricsCollector.HttpServerRouteStatusKey.getMethod()` | `getHttpMethod()` |

`fromOrigin(...)` remains overloaded for calls with and without requested
headers. It names the origin—the value from which the preflight representation
is constructed—without misidentifying the preflight's actual HTTP method,
which is always `OPTIONS`.

### Metric snapshot keys

The thirteen HTTP/SSE/transport `MetricsCollector` key types are final classes
instead of records. Replace record-component accessors with bean-style getters:
`method()` becomes `getHttpMethod()`, `route()` becomes `getResourcePathDeclaration()`,
`routeType()` becomes `getRouteType()`, `serverType()` becomes `getServerType()`,
and `reason()` becomes `getReason()`. Likewise, use `getStatusClass()`,
`getCommentType()`, `getOutcome()`, `getDropReason()`, or
`getTerminationReason()` for those former components.
Record deconstruction patterns no longer apply.

For comment-route keys specifically, use `getEventEnqueueOutcome()` and
`getEventDropReason()`; event-route keys retain `getOutcome()` and
`getDropReason()`. These Java API changes do not rename metric wire labels.

The affected types are `TransportFailureKey`, `RequestReadFailureKey`,
`RequestRejectionKey`, `HttpServerRouteKey`, `HttpServerRouteStatusKey`,
`SseCommentRouteKey`, `SseEventRouteKey`, `SseEventRouteHandshakeFailureKey`,
`SseEventRouteEnqueueOutcomeKey`, `SseCommentRouteEnqueueOutcomeKey`,
`SseEventRouteDropKey`, `SseCommentRouteDropKey`, and
`SseStreamRouteTerminationKey`, all nested in `MetricsCollector`.

### Naming and collection replacements

The remaining 4.0 naming cleanup removes the old names without aliases:

| Previous API | Replacement |
| --- | --- |
| `MarshaledResponse` descriptor setter `stream(StreamingResponseBody)`, `getStream()`, `withoutStream()` | `streamingResponseBody(...)`, `getStreamingResponseBody()`, `withoutStreamingResponseBody()` (builder/copier where applicable); the new `stream(StreamingResponseWriter)` convenience accepts a writer callback |
| HTTP/SSE attachment `getTerminationSignal()` | `getTransportTerminationSignal()` |
| `ResourcePathDeclaration.Component.with(...)` | `fromValueAndType(...)` |
| Servlet response `fromRequest(HttpServletRequest)` | `fromHttpServletRequest(HttpServletRequest)`; the Soklet `Request` + `ServletContext` overload stays `fromRequest(...)` |
| `McpProtectionControl`, `getProtectionControl()` | `McpProtectionKeyringManager`, `getProtectionKeyringManager()` |
| `McpTraceCorrelationControl`, `getTraceCorrelationControl()` | `McpTraceCorrelationKeyManager`, `getTraceCorrelationKeyManager()` |
| `McpTaskControl`, `getTaskControl()` | `McpTaskCreationContext`, `getTaskCreationContext()` |
| Endpoint `addTool(s)`, `addPrompt(s)`, `addResource(s)` | `toolRegistrations(List)`, `promptRegistrations(List)`, `resourceRegistrations(List)` and matching full-name getters |
| Resource-page `addResource(s)`, `getResources()` | `resourceDescriptors(List)`, `getResourceDescriptors()` |
| Registration `addIcon`, `addInputRequestDeclaration(s)`; prompt `addArgument` | `icons(List)`, `inputRequestDeclarations(List)`, `arguments(List)` |
| Tool-output `addContent(s)`; prompt-output `addMessage(s)` | `content(List)`, `messages(List)` |
| Icon `sizes(String...)` | `sizes(List)` |
| Tool-registration `annotations(...)`, `getAnnotations()` | `toolAnnotations(...)`, `getToolAnnotations()`; resource/content annotations stay unchanged |

Replacement setters snapshot complete lists. Optional lists clear on `null` or
empty; null elements fail atomically. Replace `.addTool(a).addTool(b)` with
`.toolRegistrations(List.of(a, b))`, not two calls to the replacing setter.
Loops should collect values first. Required resource-output contents and
subscription notification sets remain nonempty. Resource-descriptor/link
`addIcon(...)` and `McpJsonArray.Builder.add(...)` are unchanged.

### Protection-keyring fingerprint version

The production keyring's diagnostic fingerprint now uses encoding `v2`.
`McpProtectionKeyringFingerprint.VERSION` and `getVersion()` report `v2`;
`getProfile()` still reports `soklet-mcp-protection-v1`. The earlier diagnostic
construction could equate distinct raw keys even when sealed state could not
move between their servers. The corrected construction includes exact raw
bytes for active and verification-only keys.

Update any stored diagnostic baselines, and compare version, profile, and
value together. Different fingerprint versions are incomparable; finish the
software rollout before relying on fingerprints to approve fleet key rotation.
The fingerprint correction requires no secret rotation and changes neither
request-state encryption nor trace-correlation fingerprints or tokens. Public
signatures are unchanged.

### Strict request text decoding

Malformed request text is rejected rather than converted to replacement
characters. This applies to percent-decoded paths and query/form parameters,
cookie values, request-body text, multipart metadata, and multipart field text.
Paths, queries, and cookie values use UTF-8. Body and field text use their
specified charset, falling back to UTF-8 when none is specified. Valid Unicode,
including an explicitly encoded U+FFFD replacement character, remains accepted.

Path and query validation occurs when the request is constructed, including
query values that a handler never selects. Body, form, cookie, and multipart
decoding remains lazy. Existing `IllegalRequestException` and
`IllegalRequestBodyException` types report these failures; the default response
marshaler returns HTTP 400 with a redacted diagnostic.

For binary payloads or application-defined text decoding, continue using
`Request.getBody()` and `MultipartField.getData()`. The raw bytes are unchanged.
Cookie `+` characters remain literal, and malformed cookie percent-escapes such
as `%ZZ` remain literal text; syntactically valid escapes containing invalid
UTF-8, such as `%FF`, are rejected.

Request parsing also corrects these cases:

- An origin-form target such as `//x/admin` retains every path component.
  `getRawPath()` returns `//x/admin`; the existing decoded normalization makes
  `getPath()` return `/x/admin`, preserving `x` when routing.
- Absolute request URLs supply the effective `Host`, replacing the supplied field;
  `EffectiveOriginResolver.withRequest` retains the target scheme as a fallback.
  HTTP/1.1 still requires one syntactically valid physical `Host` field.
- Explicit empty names (`=value`) are retained under the empty string in query and
  form maps. Empty segments between `&` separators are ignored.
- Form bodies are parsed as pairs directly. Literal `#`, spaces, quotes, and
  similar characters remain data, and fields after them are retained. Low-level
  names and values are not trimmed. Default annotation value conversion remains
  unchanged: one blank value becomes `Optional.empty()`, while repeated blank
  values still fail scalar binding.
- `Content-Type` charset selection respects quoted parameters in any order.
  Multiple charset parameters now raise `IllegalRequestException` (HTTP 400
  through the default marshaler), even when identical. A single invalid or
  unsupported charset retains the existing UTF-8 fallback.

### HTTP and SSE framing

The standard HTTP parser rejects HTTP/1.0 transfer encoding, malformed chunk
extensions, and whitespace around a chunk size without a following extension.
Method tokens are limited to 64 bytes; chunk-size lines, including extensions
but excluding CRLF, are limited to 8,192 bytes. These are fixed bounds independent
of the aggregate request-size setting. Malformed framing returns HTTP 400 and
closes the connection before a resource method runs.

The built-in HTTP transport owns finite-response framing. It recomputes
`Content-Length`, removes application `Transfer-Encoding`, `Keep-Alive`,
`Proxy-Connection`, `TE`, `Trailer` and headers nominated by `Connection`, and
controls `Connection` itself. A valid single decimal `Content-Length` on a
bodyless HEAD response is preserved as representation metadata. `Upgrade` and
`Connection: Upgrade` are retained only for a validated `426 Upgrade Required`
advertisement; other ordinary responses cannot initiate protocol switching.
Final ordinary responses require a status from `200` through `599`; returning
a `1xx` or out-of-range status fails processing with HTTP `500`. Put a streaming
body in `MarshaledResponse.streamingResponseBody(...)` or `.stream(...)`:
the default response marshaler rejects `StreamingResponseBody` in `Response.body(...)`.
Expected typed cancellation during streaming cleanup is quiet; independent
producer, cleanup and transport failures remain observable.

SSE handshakes reject bare carriage returns, folded or whitespace-only header
lines, signed content lengths, repeated content-length fields, and transfer
encoding. An unsigned decimal zero, including leading zeros, is allowed.
Rejected and ordinary finite responses on the SSE port discard application
framing and hop-by-hop headers, including fields named by `Connection`, then
emit one `Connection: close` and the actual body length. Bodyless status codes
keep their body and length restrictions. Other headers and cookies remain.

### HTTP streaming callbacks and sources

`StreamingResponseWriter.writeTo` now takes one `ResponseStream` argument;
the separate public `StreamingResponseContext` is removed. Move request,
deadline, and idle-timeout access to `responseStream.getRequest()`,
`getDeadline()`, and `getIdleTimeout()`. Use
`responseStream.getCancelationToken()` for cancelation checks and callback
registration. `CancelationToken.onCancel(...)` returns `CallbackRegistration`,
whose `close()` removes an unclaimed callback without checked exceptions.

`RequestInterceptor.wrapRequest` and `interceptRequest` cover synchronous request
handling and response handoff, not HTTP body production. The built-in HTTP server
runs writers and source factories on a separate producer thread without propagating
`ThreadLocal`, `ScopedValue`, transaction or tracing scopes. Production can overlap
remaining wrapping or observer callbacks; do not assume those callbacks have all
returned first. Capture needed immutable values in the Resource Method, bind them
explicitly around producer work, and restore or remove thread-local bindings in
`finally`. Do not capture cursors or lazy entities whose request transaction ends
before they are used. Materialize the needed data or open a separate bounded
transaction in the producer.

Simulation materializes HTTP streaming bodies on the `performHttpRequest` caller's
thread after request handling returns. Interceptor scopes have ended, but ambient
bindings around the simulator call may still be visible; that does not establish
production context propagation. Producer-local scopes also do not automatically
cover later resource finalization, cancelation callbacks or publisher-owned threads.

Indefinite HTTP feeds can observe graceful shutdown with the new
`@NonNull Boolean ResponseStream.isGracefulShutdownRequested()` method. This
thread-safe state belongs to each response execution. During graceful drain it
requests normal completion without canceling the token, interrupting the producer,
aborting resources or closing output. Finite responses may continue draining.
Feed writers should check between items and use bounded waits well within the
owner's graceful budget, write any final record and return normally. Owned
encoders then finalize normally. Client disconnects, response/idle deadlines and
forced shutdown can still cancel output; keep checking the cancelation token.
`getDeadline()` remains the configured response deadline. Automatic InputStream,
Reader and Publisher bodies retain their finite drain behavior. Custom
`ResponseStream` implementations must implement this new method. There is no
graceful-shutdown callback or additional callback executor.

Use `.stream(responseStream -> { ... })` on `MarshaledResponse.Builder` or
`Copier` to register a writer without wrapping it in `StreamingResponseBody`.
The callback is non-null and remains lazy. This method does not remove a
known-length body: call `withoutBody()` when switching body modes. The existing
`streamingResponseBody(...)` descriptor setter remains, including its `null`
clearing behavior; `withoutStreamingResponseBody()` also removes the stream.
Finish construction with builder `build()` or copier `finish()`.

Input-stream and reader descriptors now accept `StreamResourceFactory`, whose
`open()` can throw checked exceptions, in place of `Supplier`. Rename getter
calls to `getInputStreamFactory()` and `getReaderFactory()`, and invoke the
returned factory with `open()`. Acquisition remains lazy; each execution must
receive its own resource, and the provider must support close racing a blocked
read. Buffer, charset, and encoder-error settings are unchanged.

`ResponseStream` now manages resources with `open(factory)`, `open(factory, aborter)`,
`own(resource)`, and both `using` overloads. `open(factory)` requires a provider
whose close can safely abort concurrent consumption; close is attempted once.
A separate aborter and final close are independent operations, each attempted
once, so `open(factory, Resource::close)` can close twice. `own` finalizes only
on the producer thread. Normal finalization of a `using` block closes all resources
acquired or adopted inside it in reverse order before returning. Cancelation callbacks
may close resources earlier and have no guaranteed global order. Do not manually
close a resource after transferring ownership.

Native output and resource operations now enforce the producer thread and
lifetime. Cleanup can write trailing bytes on success; afterward, retained native
write/flush calls fail with `IllegalStateException`. A new `own(resource)` call
outside the active producer lifetime rejects before transfer, leaving cleanup
to the caller. Resources returned by acquisitions that began before cancelation
are still disposed and remain accounted for until cleanup exits. A caught output
failure stays terminal. An unclassified producer interruption is
`APPLICATION_CANCELED`, preserving an already-winning specific reason when present.

Use `write(bytes, offset, length)` for a byte-array slice,
`write(string.getBytes(StandardCharsets.UTF_8))` for a short UTF-8 text chunk,
and `asOutputStream()` with a `Writer` for sustained text or other Java I/O. The slice's
offset and length use non-null `Integer` parameters, matching Soklet's public
conventions. Bounds are validated before accepting bytes or draining prior staged
output, and caller `ByteBuffer` position/limit remain unchanged even on failure.
There is no text-encoding shorthand on `ResponseStream`; the application chooses
the charset. `String.getBytes(StandardCharsets.UTF_8)` replaces malformed
surrogate sequences with `?`.

All output views share a bounded, lazily allocated scalar-write buffer. Older
staged bytes drain before native/bulk writes, preserving mixed-call order.
Closing a view flushes shared staging and closes that view even if flush fails;
other views and native output remain usable while the response permits writes.
Repeated close on the producer thread is a no-op. A view does not own the socket
or finish the HTTP response. Transfer an encoder or `Writer` with `own(...)` if
Soklet should finalize it after the producer callback returns. Successful managed
cleanup precedes the final staging flush; failed production discards staging.

Closed or expired view writes/flushes throw `IOException`; native operations
outside their lifetime throw `IllegalStateException`. Both remain confined to
the producer thread. A view translates a framework output interruption to
`InterruptedIOException`, retaining its cause and restoring the interrupt flag.
Its `bytesTransferred` reports the prefix accepted by Soklet from that call,
excluding earlier staged bytes; it is not a delivery acknowledgment. A typed
cancelation already elected when interruption is handled takes precedence.
`SocketTimeoutException` is not translated as a bridge interruption. I/O failure
or interruption during an otherwise valid output operation stays terminal even
when caught; do not retry partial writes. Argument, thread, and lifetime
validation failures do not invalidate otherwise usable output.

HTTP and simulated streams supervise cleanup with finite lifecycle accounting.
Expiry can end the supervisor's wait and report `CLEANUP_TIMEOUT`; it does not
move, retry, or forcibly stop an arbitrary `close()`, or free its lifecycle slot
before physical exit. Simulation keeps producer execution synchronous and tracks
its caller until it exits. Simulation termination observers run on bounded
callback workers; the request caller waits for their delivery. An application
that blocks its own producer or observer can still block that synchronous call;
scoped shutdown records the outstanding work within its own deadline. Other
admitted streams have independent termination-observer capacity and can finish
while that observer remains blocked.

Configure the built-in HTTP server's streaming lifecycle with:

```java
HttpServer httpServer = HttpServer.withPort(8080)
    .streamingLifecycleCapacity(256)
    .streamingCallbackConcurrency(4)
    .streamingCleanupTimeout(Duration.ofSeconds(5))
    .build();
```

These are also the defaults. All three setters accept `null` to restore the
default and validate effective values at `build()`, independent of setter order.
Capacity must be between 1 and `Integer.MAX_VALUE / 2`; callback concurrency must
be positive and no greater than capacity. A capacity below four therefore also
requires a smaller callback concurrency. Cleanup grace must be positive and
representable in nanoseconds; zero does not disable supervision. Response and
shutdown timeouts impose no construction-time ordering on cleanup grace.

Admission includes outstanding cleanup and callbacks through physical exit, so
cleanup expiry does not free a slot. Exhausted admission returns HTTP 503 before
invoking the producer or committing streaming headers. Callback workers are
separate from producer execution; normal managed finalization still runs on the
producer thread.

`streamingCallbackConcurrency` limits cancelation batches and unadmitted
rejection observers. Admitted termination notifications and diagnostics now use
separate executors, each with at most `streamingLifecycleCapacity` workers and
one prepaid observation per lifetime. Workers grow with outstanding work and
are reused; idle workers expire. Observations can run more concurrently than
the callback setting, so observers must support concurrent delivery. A blocked
observation retains its own slot without queuing another admitted stream's
observation behind it. Blocked cancelation workers can still queue later cancel
batches; all admitted application work remains counted until it exits.

Precommit HTTP streaming rejection now reports the finite failsafe status to request write/finish observers and metrics, with bounded termination observation retaining the original rejected stream. The finite response and request finish do not wait for termination observers. These observers use a separate bounded allowance on the managed callback executor; accepted work remains tracked through shutdown. If that allowance is exhausted or infrastructure has stopped, Soklet logs the omitted unadmitted-stream notification. Admitted streams retain their reserved callback jobs. Transport handoff exceptions reach `didFailToWriteResponse` instead of being reported as successful writes.

The default HTTP streaming executor uses one virtual thread per admitted producer
on JDK 21+. Lifecycle admission bounds these tasks and retained cleanup. On JDK
17, the default pool has four platform workers per event-loop concurrency unit
and no pending producer queue: exhaustion returns 503 before streaming headers.
A custom streaming executor must dispatch asynchronously and start accepted work
promptly; use direct handoff and rejection to avoid queuing behind long-lived
producers. Inline or caller-runs execution is rejected.

Custom HTTP request-handler executors must also dispatch asynchronously. A direct
executor or saturated caller-runs policy now receives a 503 response with
connection closure before resource handling begins. Use a throwing rejection
policy; Soklet does not add concurrency or queue-capacity controls around your
executor. Custom SSE handshake executors must likewise dispatch asynchronously
to avoid blocking connection admission.

HTTP `requestHandlerTimeout` includes time in the handler queue. Already-admitted
requests keep their original deadlines during graceful shutdown: expiry claims
a 503 response and cooperatively interrupts a running handler. Returning that
response does not establish handler termination; residual work remains tracked
under the shared graceful/forced shutdown budgets. The request deadline scheduler
retires after the handler executor terminates or when forced shutdown begins.

SSE `requestHandlerTimeout` now covers queue wait and application handshake
handling with one budget. Request-line/header reading and parsing pause that
budget; successful parsing resumes its remaining duration, preserving time
already spent in the queue. `requestHeaderTimeout` independently bounds reading
once the worker begins. Partial-header read expiry uses `forUnparsedRequest(...)`
with a default 408; rejection observation and marshaling use the remaining
handler budget. Expiry while handling a rejection uses its bodyless fallback,
while idle read expiry and EOF before complete headers close quietly without
internal-error diagnostics. Queue or parsed application expiry still receives 503.

HTTP and MCP output backpressure parks outside state monitors, including on JDK
21. Synchronous publishers receive iterative one-item demand, without recursive
`request()` calls. During healthy HTTP encoder finalization, queue backpressure
pauses the remaining cleanup budget while response and idle deadlines continue
to apply. Socket writes refresh idle activity after production ends; a stalled
drain can still expire. Cancellation resumes finite cleanup supervision and
encoder tail output is discarded while resources close. Cancelation interrupts the producer; output preserves the interrupt flag when translating an elected cancellation, and managed cleanup temporarily clears and restores it. Ordinary producer output
continues to fail after cancellation, and unrelated close failures remain visible.

The built-in HTTP server commits status and headers before invoking the writer. Producer failure, including failure before the first body byte, aborts delivery without changing that status. Perform fallible status selection before returning the response. Simulation materializes output and does not reproduce a committed socket head. Quiesce stops new streaming admission: a previously dispatched handler may finish its side effects but receive a finite 503 if it returns a new stream afterward; admitted streams and buffered responses may drain. Streaming response timeouts are server-wide; per-response overrides remain unavailable.

`SimulatorConfig.fromSokletConfig(...)`, `withSokletConfig(...)`, and the
corresponding `SokletSimulator.run(...)` overload inherit these immutable values
from a built-in HTTP server while creating fresh simulation state. Derivation
does not start or otherwise change the source transport, which is not retained
by that state. Deriving again
from a simulator configuration preserves the values. A simulator created without
a source, or derived from a custom HTTP transport, uses the defaults above.

Effective streaming total and idle timeouts must be nonnegative and representable in nanoseconds (at most `Duration.ofNanos(Long.MAX_VALUE)`, about 292 years). `build()` rejects larger values, including an oversized idle timeout inherited from `requestBodyTimeout`. Zero disables a timeout; `null` restores the total timeout’s disabled default or the idle timeout’s effective request-body default.

Ordinary HTTP simulation runs streaming producers on the caller thread, materializes successful output, and waits for the admitted termination observer before returning. It does not apply HTTP streaming total or idle timeouts; `ResponseStream.getDeadline()` and `getIdleTimeout()` are empty. Cleanup supervision, scope shutdown and output limits still apply. A producer failure that wins termination throws `IllegalStateException` with the original cause without returning partial bytes; an application `Error` is rethrown when it wins that outcome. Use a real HTTP fixture to test response deadlines or committed partial delivery. In HTTP, the already committed status remains and producer failure aborts the body. Both runtimes report `PRODUCER_FAILED` with the original cause for that outcome; an earlier elected cancelation still wins.

Exhausted HTTP streaming admission in simulation returns the same built-in finite `503` as HTTP: `Content-Type: text/plain; charset=UTF-8`, `Connection: close`, and body `HTTP 503: Service Unavailable`. The result clears the rejected logical response and retains the resource method. `didWriteResponse` and `didFinishRequestHandling` observers and metrics describe that finite response. `willWriteResponse` sees the original stream before admission, as it does in HTTP. The producer or source factory is never acquired; a bounded asynchronous rejection notification retains the original streaming descriptor and reports `BACKPRESSURE`, without delaying the finite result. An admitted simulator call still waits for its own termination observer.

Publisher bodies share the same lifecycle accounting in HTTP and simulation.
If `subscribe()` returns normally before delivering its first subscription,
cancelation retains that pending acquisition until the subscription arrives and
its once-only cancel attempt finishes. It receives no new demand after cancelation.
A subscription that never arrives remains an overdue lifecycle obligation and
consumes capacity; it does not require a waiting producer or callback worker.
Shutdown reports this as outstanding stream/callback work. Successful production
also waits for entered provider calls to return, including calls that synchronously
publish completion before returning.

A throwing `subscribe()` before the first subscription is a failed acquisition;
the publisher owns cleanup of its partial resources and must not send later
callbacks. Signals before the first subscription are protocol failures. A
subscription offered after failed acquisition or completed lifetime is rejected
before Soklet invokes its methods, leaving cleanup with the publisher. Late
cleanup/provider failures use the existing bounded first-diagnostic policy and
do not replace the original cancelation reason.

### SSE client initialization and connection admission

`SseHandshakeResult.Accepted.Builder.clientInitializer(...)` now accepts the
standalone `SseClientInitializer`, whose
`initialize(SseUnicaster sseUnicaster)` method can throw checked exceptions.
The old `Consumer<SseUnicaster>` signature is removed without an overload or
deprecated alias. Change stored callback types to `SseClientInitializer` and
replace explicit `accept(...)` calls with `initialize(...)`. The accepted
result's `getClientInitializer()` returns `Optional<SseClientInitializer>`;
passing `null` to the builder still clears it.

`SseUnicaster` remains the short-lived initializer's way to queue events or
comments for this client. Run the initializer synchronously for bounded setup
or finite catch-up, then return. Do not retain the unicaster or register it as
an upstream event callback. Use `SseBroadcaster` for ongoing delivery to
connected clients. The initializer's queued events are sent before broadcasts
begin, but a successful unicast is queue acceptance, not a delivery receipt.
Broadcasts published before this client joins the broadcaster are not buffered
for it. Applications needing gap-free `Last-Event-ID` replay must coordinate
their own replay-to-live handoff; the initializer guarantees ordering only.

The initializer can throw a checked exception, for example when loading a
catch-up page. Its events remain buffered until successful return. It cannot
host an indefinite upstream loop. Queue overflow terminates with `BACKPRESSURE`
even if application code catches the
thrown `IllegalStateException`. An escaping initializer exception terminates
with `PRODUCER_FAILED` unless another reason already won. After termination,
unicast rejects; successful earlier queue acceptance does not guarantee client
delivery.

On the built-in server, an initializer failure after the accepted `200` now
emits `didEstablishSseConnection`, `willTerminateSseConnection`, and
`didTerminateSseConnection`, in that order, for both lifecycle observers and
metrics collectors. This keeps connection accounting paired even though the
connection never joins a broadcaster. Its elected reason and cause are
preserved; it is not reported as an internal connection rejection or a failed
handshake. Before the accepted response is written, capacity rejection uses
`SseConnection.HandshakeFailureReason.CAPACITY_EXCEEDED`; processing,
preparation, and write failures use `INTERNAL_ERROR` with their actual cause.
Explicit application rejection remains `HANDSHAKE_REJECTED`.

`HEAD` on an `@SseEventSource` route now receives a bodyless `405` through
`ResponseMarshaler.forMethodNotAllowed`. It does not invoke the event source,
initializer, or stream admission. `OPTIONS` advertises `GET` and `OPTIONS`.
Custom and failsafe responses to parsed SSE `HEAD` requests also suppress
content. Ordinary HTTP routes retain their automatic `HEAD`-to-`GET` fallback.

Memoized `SseBroadcaster.broadcastEvent(...)` and `broadcastComment(...)` now
retain both successful generation and failure once per key for each broadcast
call, including a null key. A provider exception or serialization failure
skips that key's clients while other groups continue; a later broadcast can
retry. The built-in server logs once per failed key with the affected client
count and original cause, without rendering the key or contexts.

The simulator likewise reports provider failures, including forbidden null
payloads, to `onBroadcastError` once per key per broadcast. Without a handler,
or if it throws, that failure is logged once per key. Key-selector and
simulator-consumer failures remain per client; a failing consumer does not
invalidate the shared payload for other clients. These changes add no public
method or type.

Configure SSE lifecycle supervision separately from its per-connection queue:

```java
SseServer sseServer = SseServer.withPort(8081)
    .streamingLifecycleCapacity(256)
    .connectionQueueCapacity(128)
    .build();
```

Lifecycle capacity defaults to 256 and connection queue capacity separately
defaults to 128 application writes. Passing `null` to the lifecycle-capacity
setter restores its default; positive capacity is validated at `build()`.
Exhausted lifecycle admission returns HTTP 503 before accepted handshake
headers or initializer invocation.

Lifecycle capacity is independent of the 8,192 default transport connection
limit: the two defaults together admit at most 256 SSE lifetimes, including
pending initialization. Applications needing more clients must raise lifecycle
capacity and size their payload queues accordingly. Queue capacity counts
events, not bytes; it is not a total memory bound.

`concurrentConnectionLimit(0)` disables only the transport connection cap;
lifecycle admission remains bounded. A positive connection cap and lifecycle
capacity both apply, and retained cleanup can leave fewer slots available.
Lifecycle admission happens after application handshake handling, so an event
source method may run before a capacity 503. Put work that requires an admitted
connection in its client initializer.

SSE `heartbeatInterval` now fails at `build()` if it is below one millisecond
or its whole-millisecond value overflows a `long`. Heartbeat waits discard
fractional milliseconds; `null` restores the 15-second default. This prevents
sub-millisecond polling loops and delayed overflow failures on established
connections. `SseEvent` similarly rejects a `retry` whose whole-millisecond
value overflows a `long` when the event is built. Retry remains non-negative,
including zero; fractional milliseconds are discarded on the wire, while
`getRetry()` retains the supplied duration.

An SSE event name without data remains legal, but browser `EventSource` does
not dispatch it. Include `.data("")` to deliver an event with empty data.
ID-only and retry-only payloads update reconnect state without dispatching
a message. See the [EventSource parsing and dispatch rules](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation).

Derived simulators copy lifecycle capacity and connection queue capacity
from a built-in SSE server into fresh state, without starting or changing the
source transport. Re-derivation preserves them; default or custom source
transports use the simulator defaults. `SseRequestResult.HandshakeAccepted`
now implements `AutoCloseable` with unchecked, idempotent `close()`, which
simulates `CLIENT_DISCONNECTED`. Simulator teardown terminates remaining
connections with `SERVER_STOPPING`, even when no event/comment consumers were
registered. The first outcome wins. Closing removes delivery registrations,
rejects new consumers and writes, and retains any physically unfinished work.

Simulated SSE now calls the configured `LifecycleObserver` and `MetricsCollector`
connection establishment and termination hooks. Each accepted lifetime has one
immutable `SseConnection` metadata snapshot. Initializer failure still pairs
establishment with termination; the termination hooks receive the first elected
`StreamTermination`, its original cause and duration measured at election.
Terminal callbacks run asynchronously after framework delivery state is released
and establishment callbacks finish. `close()` does not wait for those callbacks;
tests should await their own latch before asserting terminal observations.
Teardown waits within its budgets, and unfinished observers remain physically
accounted for and can cause incomplete teardown. Handshake failures use a separate
bounded observation allowance; excess notifications are omitted with a log event.
Typed simulator deliveries do not emit socket-write callbacks or wire metrics.

For simulated SSE, the first event or comment consumer starts client reading.
New payloads of unregistered types are then discarded, so an event-only test
does not disconnect because the application also sends comments (and vice versa).
Payloads captured before reading, including initializer output, remain available
when their consumer registers later; payloads discarded after reading began
are not replayed to a late consumer.

Before any consumer registers, event and comment capture share one
`connectionQueueCapacity` limit. Pending deliveries to registered consumers
share a separate limit of the same size. Retained capture therefore does not
consume delivery capacity: each buffer can retain at most the configured number
of payloads, excluding consumer calls in progress. Payload sizes are not bounded
by these counts. An unread client or a blocked registered consumer can still
overflow its corresponding buffer and terminate the connection. `close()` clears
both buffers while unfinished consumer calls remain lifecycle-accounted.

SSE shutdown now signals `SERVER_STOPPING` during quiesce and closes the
connection immediately. Accepted events may be discarded; queue acceptance is
not a delivery acknowledgment. The configured graceful and forced shutdown
budgets still bound waiting for admitted initializer and connection work to
exit. A blocked initializer appears as residual activity rather than extending
the shutdown deadline.

### SSE broadcast callback counts

The `attempted`, `enqueued`, and `dropped` parameters of
`MetricsCollector.didBroadcastSseEvent(...)` and `didBroadcastSseComment(...)`
now use `@NonNull Integer` rather than primitive `int`. Update custom overrides
and recompile; direct callers can continue passing primitive counts through
autoboxing. Zero remains a valid count, but `null` is not.

### HTTP server type

Replace `ServerType.STANDARD_HTTP` with `ServerType.HTTP`, including static
imports and enum switch cases. The old constant is removed without an alias;
recompile custom transports, lifecycle observers, metrics collectors, and
other integrations that reference it. Migrate stored enum names or
`ServerType.valueOf("STANDARD_HTTP")` inputs to `HTTP` as well.

`ServerType` contains `HTTP` and `SSE`. MCP uses its dedicated request,
lifecycle, and metrics APIs instead of the `ServerType`-parameterized
HTTP/SSE callbacks.

The built-in Prometheus and OpenMetrics export now uses
`soklet_transport_failures_total{server_type="HTTP",reason="..."}` for HTTP
transport failures. Update filters, dashboards, and alerts that selected
`server_type="STANDARD_HTTP"`. The metric name and SSE/MCP label values are
unchanged; the MCP label is emitted by the dedicated MCP metrics path, not
by a `ServerType.MCP` constant. This uppercase built-in label is separate from `soklet-otel`'s
explicit `soklet.server.type` vocabulary, which remains lowercase `http`,
`sse`, and `mcp`.

## Response compression

`ResponseGzipPolicy` and `HttpServer.Builder.responseGzipPolicy(...)` are
removed without aliases. Configure one `ResponseCompressor` using
`HttpServer.Builder.responseCompressor(...)`. The compressor combines the
application's compression decision with its choice of codec and optional
compressed-body cache.

Replace the common default configuration:

```java
HttpServer httpServer = HttpServer.withPort(8080)
    .responseCompressor(
        ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(1_024))
    .build();
```

The old `ResponseGzipPolicy.disabledInstance()` becomes
`ResponseCompressor.disabledInstance()`. Omitting `responseCompressor(...)`
or passing `null` still disables compression.

For a custom policy, replace `shouldGzip(Request, MarshaledResponse)` returning
`Boolean` with `plan(Request, MarshaledResponse)` returning a non-null
`ResponseCompressionPlan`. Where the old method returned `false`, return
`ResponseCompressionPlan.none()`. Where it returned `true`, return
`ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance())`.
The second parameter is the finalized uncompressed response, including its
in-memory body when planning an eligible `HEAD` response.

A plan can also use `compress(codec, compressedBodyProvider)` to wrap Soklet's
lazy, per-response memoized compression supplier with an application-owned
cache. The provider runs synchronously only when body bytes are needed;
`HEAD` invokes neither the provider nor the codec. Cache the resulting bytes,
not the supplier, and never mutate cached arrays. Use bounded, thread-safe
caches keyed by the exact representation content or a reliable version
covering every variant, plus the codec and its settings. No shared cache is
installed by default.

Soklet checks protocol eligibility before planning and checks acceptance of
the selected codec's content encoding before obtaining bytes. A compressor
may therefore be called even when the client rejects the selected codec;
providers and codecs are not invoked in that case. The server handles `Vary`,
compressed-representation validators, and content length on cache hits and
misses alike. Eligible responses considered by an enabled compressor receive
`Vary: Accept-Encoding` even when the plan is `none()` or the selected encoding
is rejected, so caches distinguish encoded and unencoded outcomes. If the
plan declines compression or selects a rejected encoding and the client
explicitly forbids `identity`, the server returns `406 Not Acceptable` without
invoking the provider or codec. Requests without `Accept-Encoding` remain
uncompressed.
Streaming, file, file-channel, range, already-encoded,
transfer-encoded, and bodyless responses remain excluded. The old gzip policy
ran only after acceptance of gzip had been checked; do not use planning as a
compression-success notification.

`ResponseCompressionCodec` keeps `getContentEncoding()` and
`compress(ByteBuffer)` together. Soklet supplies only `gzipInstance()`;
applications can implement other codecs. No enum or second server setting is
required. A plan selects one codec: Soklet does not automatically choose a
fallback codec if the client rejects it. See [Response Compression](https://www.soklet.com/docs/response-writing#response-compression)
for current examples and the codec contract.

## Lifecycle and process ownership

### One lifecycle owns all transports

`Soklet` is now a one-shot aggregate lifecycle. `HttpServer`, `SseServer`, and
`McpServer` are configured components; their public `start()`, `stop()`,
`isStarted()`, `close()`, and `AutoCloseable` contracts are gone. Start and
shut down the containing `Soklet` instead. A stopped instance cannot restart;
construct a new configuration and a new `Soklet` for a new generation.

`Soklet.fromConfig(config)` claims configured transport identities immediately,
before `start()`. Reusing an already claimed identity throws
`TransportOwnershipException`. `SokletApplication.fromConfig(config)` only
configures a runner; it claims the identities when its run begins.

Default HTTP and SSE listener threads keep the JVM alive if the caller returns
from `start()`. Complete shutdown releases those threads. Custom transports
own their thread-liveness policy.


The old synchronous, void `Soklet.stop()` stopped transports before returning
but provided no aggregate terminal evidence. It is replaced by:

```java
CompletionStage<ShutdownResult> completion = soklet.shutdown();
ShutdownResult result = soklet.awaitShutdown();
```

`shutdown()` promptly publishes intent and always returns the same read-only
completion stage. `awaitShutdown()` takes no shutdown trigger and returns the
immutable terminal result. It only waits: it installs no JVM hook or signal
trigger. In 3.5.1, bare `awaitShutdown()` installed a JVM shutdown hook; in
4.0.0 it does not. Migrate standalone applications to `SokletApplication`, or
have the embedding process explicitly request and await shutdown on termination.
`Soklet.close()` remains available for direct
embedders; it requests shutdown, joins it uninterruptibly, restores interrupt
status, and throws if the result is unsuccessful.

### Standalone applications use the runner

The old pattern put process concerns into `Soklet.awaitShutdown(trigger)`:

```java
try (Soklet soklet = Soklet.fromConfig(config)) {
  soklet.start();
  soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
}
```

For a standalone process, replace it with:

```java
ShutdownResult result =
    SokletApplication.run(config, ShutdownTrigger.ENTER_KEY);
```

`SokletApplication` owns the JVM shutdown hook and optional runner-scoped
`ENTER_KEY` trigger. The core lifecycle itself does not read standard input or
own process hooks. Active registrations share one daemon listener, which reads
one byte at a time and stops at the first LF or CR without adding read-ahead
buffers or closing stdin. EOF without a line terminator leaves the runner and
its signal hook active. When the last registration is removed during a blocked
read, at most that one pending byte may be consumed before the listener retires;
Java cannot portably cancel the read without closing process-owned stdin. Reserve
stdin until that read completes. A trailing LF after a CR remains available to
later consumers. When a standalone process owns application resources too,
configure one one-shot application and supply the bounded cleanup and any
additional triggers to its run:

```java
ShutdownResult result = SokletApplication.fromConfig(config).run(
    ShutdownCleanup.fromTimeoutAndAction(
        Duration.ofSeconds(5),
        shutdownResult -> applicationResources.close()),
    ShutdownTrigger.ENTER_KEY);
```

After any run attempt begins, the application cannot be run a second time or
concurrently. Use cleanup only for a resource that is application-owned,
ingress-exclusive, safe to clean after a complete core shutdown, and bounded by
an explicit timeout. Stateful observers are not automatically safe cleanup
targets: they need an application-defined delivery barrier first. Cleanup is
skipped when core shutdown is incomplete. If you tested an earlier 4.0.0
snapshot, remove `SokletApplicationOptions`; create the one-shot application
with `SokletApplication.fromConfig(config)` and pass triggers and cleanup to its
`run(...)` invocation instead.

Embedders that already own process signals should continue to use
`Soklet.fromConfig(config)`, `start()`, `shutdown()`, and `awaitShutdown()` and
should not add the standalone runner's process ownership.
A manually registered JVM hook must wait for shutdown to finish. Migrating
`stop()` to the asynchronous `shutdown()` method reference loses that wait;
use blocking `close()` instead:

```java
Soklet soklet = Soklet.fromConfig(config);
Thread shutdownHook = new Thread(soklet::close, "application-shutdown");
Runtime.getRuntime().addShutdownHook(shutdownHook);
try (soklet) {
  soklet.start();
  ShutdownResult result = soklet.awaitShutdown();
} finally {
  try {
    Runtime.getRuntime().removeShutdownHook(shutdownHook);
  } catch (IllegalStateException shutdownInProgress) {
    // The JVM is already executing its registered shutdown hooks.
  }
}
```

[JVM shutdown hooks](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/Runtime.html)
run concurrently with no guaranteed order. Coordinate independent logging and
resource hooks so their dependencies remain available during Soklet's drain.
On signal-driven JVM shutdown, `run(...)`, post-run code and a caller's
`finally` block may never finish. Required application cleanup belongs in the
bounded `ShutdownCleanup` action. The runner does not select process exit
status; successful drain does not promise exit code zero after a signal.

Do not call `System.exit()` from work that shutdown must join, such as handlers,
streaming producers or cleanup actions. The JVM hook can then wait for the
same work that is waiting for JVM exit, exhausting the shutdown budget.
Let an external process owner or supervisor terminate the process.

Soklet-created auxiliary HTTP/SSE/MCP workers (including streaming callbacks,
diagnostics, supervision, MCP platform handlers, policy/cancelation workers and
protocol deadline timers) are daemon threads. A blocked residual callback on those auxiliary workers alone
cannot keep the JVM alive after `run()` returns or throws. Built-in HTTP/SSE
listener threads remain non-daemon: an inline connection observer or metric
callback that never returns can retain the listener and keep the JVM alive.
Keep those callbacks prompt. It remains residual
work: an incomplete result stays incomplete, and configured application cleanup
is still skipped. Running built-in listener threads retain process liveness;
complete shutdown releases them. Custom transports and supplied executors own
their thread/liveness policies. The runner does not call `System.exit()` or
`Runtime.halt()`.

Shutdown requested during startup may return normally without reaching
readiness; this alone does not imply `SokletStartupException`. Inspect the
returned lifecycle result.


### Shared lifecycle policy and changed defaults

The three transport-specific shutdown-deadline setters are removed. Configure
one `LifecyclePolicy` on `SokletConfig`; it has no hidden HTTP, SSE, or MCP
graceful cap.

| Boundary | 3.5.1 default/guidance | 4.0.0 default | Migration effect |
| --- | ---: | ---: | --- |
| Normal startup | Unbounded transport-specific behavior | 30 s | Startup now always has a finite shared deadline; configure an explicit timeout when the default is unsuitable. |
| Cancelation of live startup after shutdown intent | Not a shared phase | 2 s | A non-cooperative startup can produce an incomplete result after this boundary. |
| HTTP graceful shutdown | 5 s default; 30 s production guidance | 15 s | More time than the old default, less than the old guidance. |
| SSE graceful shutdown | 1 s | 15 s | Idle streams close promptly; outstanding writes, loops, and executors share the 15 s boundary. |
| Forced shutdown | Not a shared phase | 3 s | Owned work is interrupted/canceled and observed within this separate phase. |

The default 15-second graceful drain is independent of the default 60-second
request-handler timeout. If deployment policy requires all permitted in-flight
requests to finish, allow their maximum remaining work plus response transmission
time. Choosing a shorter drain deliberately permits interrupted work and closed
connections. Interrupting a handler cannot guarantee that noncooperative code
stops. Long-lived streams need their own completion policy, and the container or
process termination budget must include forced shutdown and operational margin
in addition to graceful drain.

For example:

```java
LifecyclePolicy policy = LifecyclePolicy.builder()
    .startupTimeout(Duration.ofSeconds(30))
    .startupCancelationTimeout(Duration.ofSeconds(2))
    .gracefulShutdownTimeout(Duration.ofSeconds(20))
    .forcedShutdownTimeout(Duration.ofSeconds(3))
    .build();

SokletConfig config = SokletConfig.withHttpServer(httpServer)
    .lifecyclePolicy(policy)
    .build();
```

Passing `null` to any `LifecyclePolicy` timeout setter restores that timeout's
built-in default. Passing `null` to either
`SokletConfig.Builder.lifecyclePolicy(...)` or
`SimulatorConfig.Builder.lifecyclePolicy(...)` restores the complete default
policy.

`LifecyclePolicy` is a value type in 4.0.0: `equals(...)` and `hashCode()` use
all four timeout values. Equal independently-built policies therefore compare
equal and behave as one key in sets and maps; code written against an earlier
4.0.0 prerelease that deliberately depended on object identity should use an
identity-based collection instead.

All four lifecycle timeouts accept `Duration.ZERO` as an immediate deadline,
not an unlimited wait or a disabled phase. A zero startup budget leaves no
waiting allowance for setup/startup and can immediately yield `TIMED_OUT`.
Zero startup cancelation leaves no waiting allowance for the active startup
call to return before the shutdown phases. Zero graceful drain moves unresolved
participants straight to forced shutdown; zero forced shutdown leaves no
waiting allowance for termination proof. With both shutdown budgets zero,
even an idle running listener can produce `INCOMPLETE` because stop calls and
proof arrive asynchronously. Proof already available at classification can
still establish completion; zero does not promise an incomplete result in
every lifecycle state. Use positive budgets for normal deployments.

Review the builder Javadocs before changing the finite startup timeout.
A normal running shutdown with defaults is bounded
by 18 seconds; shutdown intent during startup is bounded by 20 seconds from
that intent.

### Standalone terminal report

`SokletApplication` attempts a multiline `soklet-terminal-report` directly on
standard error, captured when the run's runtime is created, if the runner's
primary outcome indicates failure, startup fails or times out, shutdown is `FORCED` or
`INCOMPLETE`, a component terminates unexpectedly, or configured application
cleanup fails or times out. A complete `FORCED` shutdown still triggers it.
Ordinary expected graceful shutdown is silent unless another trigger applies.

The report bypasses `LifecycleObserver` and `LogEvent`. It contains bounded
lifecycle/component/cleanup state, retention and observer diagnostics, and
failure class names, without exception messages, stack traces or traversal of
causes/suppressed exceptions. Output is capped at 16 KiB of valid UTF-8. The
runner and JVM hook share one 250-millisecond reporting allowance; reporting
is best effort, and failures or timeout do not replace the lifecycle result.
A blocked stderr worker can outlive that allowance. There is no public reporter
switch; account for this channel in process stderr capture and deployment
budgets. This report is separate from the default observer's stderr logging.

### Kubernetes and orchestrator budget

The platform must allow more time than Soklet's internal phases:

```text
termination grace
  > preStop/load-balancer delay
  + startup cancellation (when termination can arrive during boot)
  + graceful shutdown
  + forced shutdown
  + configured application cleanup (when present)
  + 250 ms terminal-report attempt
  + other JVM hooks and VM halt
  + safety reserve
```

With the defaults, a five-second external drain, no application cleanup, two
seconds for other hooks/VM halt, and a three-second reserve totals 30.25
seconds. Round up to at least 31 seconds. The website deployment example uses a 46-second
termination budget for its longer graceful phase, retaining an operational reserve.
Adding a five-second cleanup budget raises the default-policy
example's minimum to 36 seconds, so use at least 40 seconds or reduce a measured
component.

### Docker stop budget

[Docker stop](https://docs.docker.com/reference/cli/docker/container/stop/)
uses a 10-second default on Linux unless configured otherwise. That is shorter
than Soklet's default 18-second running shutdown. Set a larger container budget
with `docker run --stop-timeout 40 ...`, Compose `stop_grace_period: 40s`, or
`docker stop --timeout 40 myapp`. The 40-second example includes startup
cancelation, graceful/forced phases, five seconds of application cleanup,
terminal reporting, other hooks and reserve. Recalculate it for your configured
budgets and external drain delay; Docker force-kills the container when its
budget expires.

### Observer and result changes

`LifecycleObserver.didFailToStopSoklet(...)` and the three transport-specific
`didFailToStop...` callbacks are removed. The corresponding `didStop...`
callback now receives `ShutdownResult` or `ShutdownComponentResult`, which
is the terminal evidence for successful, forced, unexpected, residual, and
unknown termination. Observer callbacks are observational: exceptions are
contained and do not rewrite lifecycle results.
Lifecycle-transition callbacks are serialized on a daemon observer worker;
returning from shutdown or `SokletApplication.run(...)` does not join their
delivery. They may be lost at JVM exit. Required cleanup belongs in bounded
`ShutdownCleanup`. Cleanup that depends on observer state needs an
application-owned delivery barrier. Other callbacks retain the inline behavior
documented on their individual methods.


MCP shutdown metrics and downstream OpenTelemetry projections use exactly:

- `not_started`
- `graceful_termination`
- `forced_termination`
- `unexpected_termination`
- `residual_activity`
- `termination_unknown`

Do not infer this set dynamically from enum constants; use an exhaustive
mapping so a future enum addition cannot silently change metric cardinality.

### Log-event routing with custom observers

`LifecycleObserver.didReceiveLogEvent(...)` now defaults to a no-op. The
instance returned by `LifecycleObserver.defaultInstance()` retains the existing
stderr message and attached-Throwable stack-trace output. `SokletConfig` selects
that instance when no observer configuration is supplied, so unconfigured
applications still receive diagnostics.

Configuring a custom observer or observer collection replaces the unconfigured
default. Custom observers that only handle tracing or other lifecycle callbacks
no longer each print an implicit stderr copy. Override `didReceiveLogEvent` in
an application logging observer, or explicitly include
`LifecycleObserver.defaultInstance()` in the collection to retain stderr
logging. Null/empty observer configuration remains silent. Fan-out order,
exact event/Throwable identity and callback-failure handling are unchanged.

`OpenTelemetryLifecycleObserver` records spans and does not log events or export
OpenTelemetry logs. Pair it with your application's logging observer if you
want routed logs. The standalone terminal report and bounded emergency fallback
for a failing log observer remain separate stderr channels.

## HTTP, SSE, and custom transports

Startup dispositions are unified, but built-in bind-failure causes retain their
transport wrappers: HTTP uses `UncheckedIOException`, SSE `IllegalStateException`,
and MCP can expose `BindException` directly. Inspect the cause chain rather than
assuming the same exception class across transports.

Custom HTTP and SSE transport SPIs now participate in an aggregate lifecycle
rather than being independently started and stopped. Migrate those custom
implementations to
the current transport identity, attachment/runtime, lifecycle context, and
termination-proof contracts. A decorator must preserve stable identity and
must distinguish framework-mediated transparent delegation from a
termination-owning delegate. Implement graceful and forced shutdown through
`TransportRuntime.shutdownGracefully(ShutdownContext)` and
`TransportRuntime.shutdownForcibly(ShutdownContext)`. The attachment-context
method for an independently terminating child is
`attachTerminationOwningDelegate(...)`; transparent delegation remains
`attachTransparentDelegate(...)`. Soklet can validate honest evidence presented
through those contracts; it cannot detect a custom transport that lies about
its own attestation or behavior.

Custom SSE transports can now read the logical handshake through
`@NonNull public Optional<@NonNull SseHandshakeResult> HttpRequestResult.getSseHandshakeResult()`.
The response consumer supplied to `SseServer.RequestHandler.handleRequest(...)`
receives an `HttpRequestResult`; its `getMarshaledResponse()` returns the offered
HTTP response. An absent or rejected handshake does not become accepted because
its offered HTTP status is `200`.

An accepted result exposes the existing client initializer and client context.
The transport invokes the initializer once after writing the accepted response,
keeps setup bounded, and releases queued catch-up writes/joins the broadcaster
only after successful initialization. Failure or prior termination prevents
activation; establishment observation precedes termination observation even if
initialization fails. An `Accepted` value alone is not proof of committed headers,
successful initialization or an active connection. Initializers and contexts are
application-owned references; the getter does not invoke or copy them. Callers
own their retention/logging, and `HttpRequestResult.toString()` continues to omit
the handshake. Builder/copier handshake setters remain internal.

A synchronous `TransportRuntime.start(StartupContext)` failure must throw its
original runtime exception/error, retaining a checked cause when wrapping is
needed. Let the owner initiate rollback before reporting termination proof.
Signaling failure or proof before that owner's shutdown intent describes an
independent premature termination and can control the startup result instead
of the thrown cause. Independent worker failures must still be signaled when
observed, even before readiness.

Both shutdown phase methods must be safe after partially failed startup. They
initiate cleanup promptly; the runtime signals `signalTerminated()` only when
all its owned resources and activity have ended. Returning from a failed start
or a shutdown method alone is not proof. A termination-owning decorator must
also drive its child's shutdown; the child's independent proof remains required.
Built-in bind failures now preserve the original cause through transparent,
termination-owning and nested decorators. Their rollback completed within the grace period is classified
as `GRACEFUL_TERMINATION` for HTTP, SSE and MCP; this disposition describes
cleanup, while `StartupDisposition.FAILED` still describes the failed startup.
An earlier independent failure remains `UNEXPECTED_TERMINATION`.

Soklet defers phase delivery while a configured custom runtime's `start()` is
still running. If it ignores cancelation and returns or throws after the forced
boundary, its existing tracked lifecycle worker delivers one best-effort
`shutdownForcibly(...)` call with the original deadline; remaining time may be
zero. No additional cleanup worker or fresh budget is allocated. Later proof or
cleanup failure cannot rewrite the frozen shutdown result or its recorded
causes. A blocked compensation remains on that daemon lifecycle worker, and the
transport remains responsible for closing its resources. This fallback does not
replace honoring startup cancelation or keeping shutdown methods prompt.


Protocol numeric fields are now independent of the JVM's formatting locale,
including file ranges, default weak ETags, cookie Max-Age, SSE error status and
length fields, and servlet URL ports. Applications do not need to change their
global locale to obtain valid protocol output; application-facing formatting
retains its existing behavior.

`EntityTag.fromStrongValue(...)` and `fromWeakValue(...)` now reject characters
above `0xFF`, including surrogate code units, at construction rather than
allowing them to fail later during response marshaling. `fromHeaderValue(...)`
returns empty for such values. Valid `0x80–0xFF` obs-text remains supported;
this is not an ASCII-only restriction.

The built-in SSE server now hard-bounds client-initializer catch-up buffering
with `SseServer.Builder.connectionQueueCapacity(...)`, using the same 128-write
default as the active connection queue. An initializer may use all configured
application slots; the optional framework verification heartbeat is accounted
separately. Leave headroom for live broadcasts arriving before a catch-up page
drains. Initializers that can replay more than the configured capacity must
page or cap that work before accepting the handshake. Overflow throws
`IllegalStateException` and terminates the already-accepted connection with
`BACKPRESSURE`, even when caught by the initializer. See
[SSE client initialization and connection admission](#sse-client-initialization-and-connection-admission)
for the revised callback and admission contract.

For accepted SSE handshakes, Soklet now ignores application-provided
`Connection` and `Keep-Alive` headers and emits its canonical
`Connection: keep-alive` value. Applications may remove those redundant
headers. `Content-Length`, `Transfer-Encoding`, and other unsupported hop-by-hop
headers still fail the accepted handshake because they can conflict with
stream framing.

The standard HTTP server now separates its body-only request bound from its
aggregate request bound. Configure
`HttpServer.Builder.maximumRequestBodySizeInBytes(...)` when payload capacity
should be lower than `maximumRequestSizeInBytes(...)`; if omitted, the
body-only bound tracks the aggregate bound regardless of setter order. The
body-only bound counts received payload bytes after HTTP transfer framing is
removed and before optional `Content-Encoding` decompression; transfer framing
remains part of the aggregate bound.

`ResponseMarshaler` provides a hook for input failures before the built-in HTTP
or SSE transport can construct a valid request: malformed requests, overlong
request targets, unsupported expectations, oversized request headers, partial
read timeouts and early aggregate-size failures. HTTP URI/encoding validation,
unsupported content coding and malformed compressed bodies also use this hook
when no valid request exists. Direct implementations of the interface inherit the
bodyless default for `forUnparsedRequest(UnparsedRequest)` and may override it;
applications using `ResponseMarshaler.builder()` may keep the default
implementation or configure `unparsedRequestHandler(...)`. The immutable value
supplies `ServerType`, an `UnparsedRequestReason`, a best-effort remote address,
a fresh read-only view of the bounded raw-input capture, the byte count
attributed to the rejected request through its rejection boundary,
and whether the capture omits any of those attributed bytes. The built-in HTTP
and SSE captures are capped at 64 KiB. Later HTTP construction failures report
an observed wire count with an empty capture and `isCaptureTruncated() == true`,
without retaining another raw copy for successfully parsed traffic. Bytes already read from the socket beyond the
failure boundary are neither captured nor counted because they might belong to
a pipelined request.

The default marshaler and built-in fallback use these conventional statuses:

- `MALFORMED_REQUEST` (`400`), including unrecognized methods, unsupported
  transfer codings and unsupported HTTP versions under the current strict parser
- `REQUEST_TARGET_TOO_LONG` (`414`)
- `EXPECTATION_FAILED` (`417`)
- `REQUEST_HEADERS_TOO_LARGE` (`431`)
- `REQUEST_READ_TIMEOUT` (`408`)
- `REQUEST_TOO_LARGE` (`413`)
- `UNSUPPORTED_CONTENT_ENCODING` (`415`)
- `REQUEST_BODY_DECOMPRESSION_FAILED` (`400`)

The enum does not own a status: a custom marshaler may return any final response
status from `200` through `599`. It deliberately receives no synthetic or
nullable `Request`, parsed headers or target, or parser exception. Captured
bytes are raw, unredacted network input and can contain credentials, cookies,
body fragments, control bytes, or non-text data; do not log, meter, reflect, or
persist them without application-specific redaction and retention controls.

For each eligible rejection, Soklet runs the observation and marshaling pipeline
on the request-handler executor. Later HTTP construction failures use their
already-admitted handler; parser and SSE rejections submit a bounded detail task. The framework-managed default executor has bounded
concurrency and queue capacity; a custom executor controls its own capacity. If
admitted, Soklet calls
`LifecycleObserver.didRejectUnparsedRequest(UnparsedRequest)` before the
marshaler; observer failures are contained, both operations share
`requestHandlerTimeout` (its remaining duration for later HTTP failures and SSE
rejections), and the marshaler runs only if budget remains after observation. That timeout bounds how long the transport waits and interrupts the
worker; application code that ignores interruption can continue until executor
shutdown. Neither callback runs inline on the socket selector.
If executor admission is rejected, the work times out, or response generation
fails, Soklet writes the conventional bodyless fallback. The detailed observer
callback is therefore best-effort under overload; the existing low-cardinality
transport metric still records the rejection. The transport retains control of
connection closing and `Connection`, `Content-Length`, and
`Transfer-Encoding` framing. The complete serialized custom response is capped
at 64 KiB and must use a finite in-memory body; an oversized, streaming, or
file-backed response uses the bodyless fallback.

Idle read timeouts and EOF before complete headers close quietly. Admission,
shutdown and broken-socket failsafes do not invoke either detailed callback.
When a valid `Request` can be constructed for a size violation, including a
bounded decompression size or ratio failure, `forContentTooLarge(...)` continues
to handle it with the available request/resource context.

`forContentTooLarge(Request, ResourceMethod)` is unchanged. It remains the
route-aware `413` path when Soklet parsed enough input to construct a real
request, including body-only and post-decompression size violations. The new
method is only for failures where that trustworthy request context does not
exist.

If migrating from an earlier 4.0.0 snapshot, update lifecycle result and exception
names as a hard cutover:

| Earlier snapshot | 4.0.0 |
| --- | --- |
| `ShutdownComponentResult.getFailures()` | `getThrowables()` |
| `ShutdownCleanupFailure` | `ShutdownCleanupFailureReason` |
| `SokletTerminatedUnexpectedlyException` | `SokletUnexpectedTerminationException` |
| `ShutdownIncompleteException` | `SokletShutdownIncompleteException` |
| `SokletApplicationCleanupException` | `SokletShutdownCleanupException` |

`SokletLifecycleException` is sealed to its four concrete lifecycle outcomes.
Lifecycle exception instances and `TransportOwnershipException` are not
thread-safe; their retained `ShutdownResult` values remain immutable.

`HttpServer` is no longer an injectable resource-method parameter. Remove it
from resource method signatures and acquire application services through the
application's own dependency injection. `SseServer` injection remains
available so a resource method can acquire its `SseBroadcaster`.

`SokletConfig.copy()` and `SokletConfig.Copier` are removed. Build each config
explicitly with one of the public `withHttpServer(...)`, `withSseServer(...)`,
or `withMcpServer(...)` entry points. Reusing a transport object across
lifecycle generations is not a replacement for copying: each generation needs
fresh one-shot transports.

## MCP listener binding and construction

The MCP listener's default bind host changes from `0.0.0.0` in 3.5.1 to
`127.0.0.1` in 4.0.0. Default HTTP and SSE listeners still bind to `0.0.0.0`.
An MCP listener left at its new default is reachable only through loopback;
container and remote deployments must select their bind address explicitly.
Any non-loopback bind also requires a nonempty `allowedHosts` set.

For example, supply the application's endpoint registry and tool limiter:

```java
McpServer mcpServer = McpServer.withPort(8082)
    .endpointRegistry(endpointRegistry)
    .host("0.0.0.0")
    .allowedHosts(Set.of("mcp.example.com"))
    .toolRateLimiter(toolRateLimiter)
    .build();
```

`allowedHosts` contains hostnames or IP literals, without a scheme, path or
port. An explicitly allowed hostname accepts any syntactically valid public
port, or no port, so `mcp.example.com:443` may be forwarded to this listener
on `8082`. Automatic loopback aliases require the effective listener port.
Soklet does not derive an allowed hostname from `Forwarded` or
`X-Forwarded-Host`. Bind/Host validation, Origin policy and application
admission remain independent; remote binding does not add TLS.

Construction validates the combined configuration. A tool-bearing server
requires its fallback `toolRateLimiter`, even when tools or endpoints name
overrides. Completion requires `requestRateLimiter`. An endpoint enabling
Tasks, or a task-required tool, requires `taskManager`. Any operation using
`FRAMEWORK_PROTECTED` request state requires `protectionConfig`. Missing
required collaborators fail with `IllegalStateException` at `build()`.
Invalid allowed hosts and non-loopback binding without an allowlist fail
with `IllegalArgumentException`. The body, header-section and request-target
byte limits, plus 1,024 bytes of HTTP framing allowance, must also fit within
`Integer.MAX_VALUE`; an oversized combined limit fails with
`IllegalArgumentException` at `build()` even if each individual setter accepts
its value.

## Simulator migration

[`Soklet::runSimulator`](<https://javadoc.soklet.com/com/soklet/Soklet.html>)
is removed. In the common case, pass the application's completed
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html)
directly to
[`SokletSimulator::run`](<https://javadoc.soklet.com/com/soklet/SokletSimulator.html#run(com.soklet.SokletConfig,com.soklet.SokletSimulator.Simulation)>):

```java
ShutdownResult result = SokletSimulator.run(sokletConfig, simulator -> {
  HttpRequestResult response = simulator.performHttpRequest(request);
  // assertions
});
```

If teardown cannot prove completion, a successful body is followed by
`SokletShutdownIncompleteException`. When the body already failed, the same
teardown exception is suppressed on that original failure. Its message now
identifies component dispositions and residual-activity categories. Outstanding
work alone has no Throwable cause; inspect `getShutdownResult()` and the
component's `getResidualActivityEvidence()` for the frozen evidence. Releasing a
worker later does not change the published result.

`ShutdownResult`, `ShutdownComponentResult` and `ResidualActivityEvidence` now
have compact, bounded `toString()` diagnostics. They render enum categories,
failure counts/presence and retained-activity counts when available, without
invoking application Throwables or traversing retained objects. Free-text
residual summaries and failure details remain available through typed accessors;
these diagnostic strings are not a serialization format.

Each call derives a fresh off-network HTTP, SSE, and MCP transport for every
corresponding transport present in the source configuration. The simulator
call never starts, claims, or changes the source transport instances.
Explicitly configured application collaborators are reused by identity,
while unset defaults that depend on the completed configuration are derived
again. An imported MCP server's build settings produce fresh framework-owned
listener and runtime state, but application-supplied MCP collaborators,
including rate limiters, are reused by identity. Tests remain responsible for
isolating their mutable state. This is transport isolation, not a deep copy:
Soklet does not inspect or rebind a dependency-injection provider or other
collaborator that captured a source transport.

Every transport present in the source remains present in the imported shape.
If a test must omit one, use the standalone
[`SimulatorConfig::builder`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#builder()>)
form and configure only the intended transports and application settings.

Use
[`SimulatorConfig::fromSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#fromSokletConfig(com.soklet.SokletConfig)>)
when an API expects a completed simulator configuration:

```java
SimulatorConfig simulatorConfig =
    SimulatorConfig.fromSokletConfig(sokletConfig);
```

Use
[`SimulatorConfig::withSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#withSokletConfig(com.soklet.SokletConfig)>)
when one test needs an override. Later builder calls take precedence over the
imported settings:

```java
SimulatorConfig simulatorConfig = SimulatorConfig
    .withSokletConfig(sokletConfig)
    .simulatorOptions(simulatorOptions)
    .configureMcpServer(mcpServerBuilder -> mcpServerBuilder
        .requestTimeout(shortTestTimeout)
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults()))
    .build();
```

[`configureMcpServer(Consumer)`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#configureMcpServer(java.util.function.Consumer)>)
customizes the MCP server imported from the application configuration. It can
also replace an imported collaborator, such as a stateful rate limiter, with a
test-scoped implementation. When the builder did not import an MCP server, the
same method creates a fresh one from the standard MCP builder defaults.

For a standalone, transport-isolated simulation that does not start from an
application configuration, build the graph explicitly. The fresh MCP builder's
logical port defaults to `0` and may be overridden in the callback. It otherwise
uses the same classpath-discovery and accept-all admission defaults as
[`McpServer::withPort`](<https://javadoc.soklet.com/com/soklet/McpServer.html#withPort(java.lang.Integer)>):

```java
SimulatorConfig simulatorConfig = SimulatorConfig.builder()
    .httpServer()
    .sseServer()
    .configureMcpServer(mcpServerBuilder -> mcpServerBuilder
        .port(port)
        .endpointRegistry(endpointRegistry)
        .admissionController(admissionController)
        .requestTimeout(requestTimeout)
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults()))
    .resourceMethodResolver(resourceMethods)
    .build();
```

Set an explicit endpoint registry or admission controller on the MCP builder
supplied to `configureMcpServer`, as shown above. Like
`McpServer.withPort(port)`, the standalone form still requires a fallback tool
rate limiter when a discovered endpoint has a tool. The outer
[`SimulatorConfig.Builder`](https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html)
owns the call to [`McpServer.Builder::build`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#build()>);
the configurer must not build or retain the supplied MCP builder.

Within the body,
[`Simulator::getHttpServer`](<https://javadoc.soklet.com/com/soklet/Simulator.html#getHttpServer()>),
[`Simulator::getSseServer`](<https://javadoc.soklet.com/com/soklet/Simulator.html#getSseServer()>),
and
[`Simulator::getMcpServer`](<https://javadoc.soklet.com/com/soklet/Simulator.html#getMcpServer()>)
expose the exact transports selected for that run. A completed
[`SimulatorConfig`](https://javadoc.soklet.com/com/soklet/SimulatorConfig.html)
can be claimed by exactly one run; derive or build a new one instead of reusing
a configuration, builder, or simulated transport.
[`SimulatorOptions`](https://javadoc.soklet.com/com/soklet/SimulatorOptions.html)
controls materialization and capture behavior and is supplied with
[`SimulatorConfig.Builder::simulatorOptions`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#simulatorOptions(com.soklet.SimulatorOptions)>).
Set lifecycle deadlines with
[`SimulatorConfig.Builder::lifecyclePolicy`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#lifecyclePolicy(com.soklet.LifecyclePolicy)>).
Simulation is deterministic and off-network, so it does not prove kernel TCP,
proxy, TLS, or live write-idle behavior.

## Servlet adapters

Upgrade either adapter to 2.0.0 alongside Soklet 4.0.0:

- `com.soklet:soklet-servlet-javax:2.0.0` for `javax.servlet` containers;
- `com.soklet:soklet-servlet-jakarta:2.0.0` for `jakarta.servlet` containers.

Both adapters now require core Soklet 4.0.0; compatibility with core 3.x is no
longer supported. Their Soklet dependency remains `provided`, so the application
must explicitly supply `com.soklet:soklet:4.0.0`. The adapter entry-point API is
retained, but this minimum-core change is a breaking dependency migration.
The four nested adapter builder types are now final. Internal mutation hooks
`SokletHttpSession.setSessionId(...)` and
`SokletHttpServletResponse.setPrintWriter(...)` are no longer public: use
`HttpServletRequest.changeSessionId()` and `HttpServletResponse.getWriter()`
for their supported servlet operations.
Empty 204 and 304 responses remain bodyless through either conversion method;
ordinary empty 200 responses retain their byte-array representation.

Both `SokletHttpServletResponse` implementations still capture their entire body
in memory and copy it into a finite response through `toMarshaledResponse()` or
`toResponse()`. Conversion does not send the response. `flushBuffer()` and writer
or output-stream flushes affect local commitment, not network delivery;
`isCommitted()` does not prove that Soklet has sent the response head.
`setBufferSize(...)` is a local commit threshold, not a body-size or memory cap.
Capture continues beyond it, and conversion adds body copies. Bound response
sizes and concurrent captures in the application. Request-size settings and
native streaming queue limits do not cap this output.

There is no `ResponseStream`-backed response-adapter mode. Use native file-backed
responses for large known-length files, native HTTP streaming for incremental
production, and `SseServer` for event streams. The standalone
`SokletServletOutputStream` helper can wrap a supplied sink, but it does not bridge
the response adapter's status, headers or commitment state to that sink. See
[response buffering guidance](https://www.soklet.com/docs/servlet-integration#response-buffering-and-streaming).

See the [javax Javadocs](https://javax.javadoc.soklet.com/com/soklet/servlet/javax/package-summary.html)
or [Jakarta Javadocs](https://jakarta.javadoc.soklet.com/com/soklet/servlet/jakarta/package-summary.html)
for the matching container namespace.

## Annotation-processing migration

Enable `SokletProcessor` and `-parameters` for every module containing Soklet
annotations. Runtime handler-method classpath scanning is not a fallback.
Generated resources must survive shading and packaging.

The processor rejects unsupported or ambiguous method/record shapes at build
time. This can surface errors that 3.5.1 deferred until runtime.

HTTP/SSE path placeholders must occupy a whole slash-delimited component:
`/users/{id}` is valid; `/users/prefix{id}` and `/users/{id}suffix` are not.
Empty, nested and unbalanced placeholders are rejected. A varargs placeholder
such as `{tail*}` must be the final component, and its name must not duplicate
another placeholder (`/{id}/{id*}` is invalid). The processor reports these
errors on the annotated method, including each repeatable route declaration.
`ResourcePathDeclaration.fromPath(...)` and explicit class/method resolvers
apply the same syntax rules; malformed paths formerly accepted as literals
now fail at construction or resolver setup. Correct these declarations and
rebuild their generated indexes.

Each HTTP/SSE Java parameter may have only one Soklet binding annotation
(`@PathParameter`, `@QueryParameter`, `@FormParameter`, `@RequestHeader`,
`@RequestCookie`, `@Multipart` or `@RequestBody`). Custom qualifier annotations
are separate and remain supported. Path parameters cannot use `Optional<T>`,
and a varargs path parameter must be a `String`.

Varargs placeholders match zero or more path components. For example,
`/assets/{tail*}` matches `/assets` and `/assets/`, and `/{tail*}` matches `/`.
With the default parameter provider and String converter, these matches now
inject `""` instead of failing with 400. Nonempty suffixes keep their existing
conversion behavior; explicitly configured String converters still run for
empty suffixes and may reject them. A matching fixed route still takes
precedence. Handlers that require a nonempty suffix should check it explicitly
and return the appropriate response, such as 404 for a missing file.

`optional=true` on query, form, header, cookie and multipart bindings now requires
a reference type. Replace optional primitives such as `int` with `Integer` or
`Optional<Integer>`. Required primitives remain supported. An absent optional
`@RequestBody` keeps its existing Java primitive default (`0`, `false`, etc.).
The processor reports these mistakes on the parameter; the default runtime
parameter provider also checks explicitly registered declarations during setup,
before instance acquisition for dynamically resolved methods, and before direct
extraction. Custom parameter providers retain their runtime binding control.
With default value conversion, absent or single blank `Optional<T>` value
bindings still produce `Optional.empty()`.

The default `InstanceProvider` now checks resource classes during HTTP/SSE
setup without instantiating them. They must be concrete and have a no-argument
constructor accessible to Soklet (normally a public class and public constructor).
If a custom provider constructs your resources, this default-constructor check
does not apply; the processor does not reject non-public or constructor-injected
resource classes. Custom value-converter targets are not limited to a built-in
type list.

During incremental HTTP/SSE compilation, the processor updates one prior route
snapshot. Current compiler output takes precedence over the enabled sidecar
and persistent caches; an empty current index is authoritative. Recompiled
types replace their old declarations, and retained owners absent from the
compiler's sources/classpath are removed. Untouched sources, compiled classes
and dependency JARs must remain visible to the compiler.

After deleting or renaming a resource, remove its old compiled classfiles or
perform a clean build. An old classfile still on the compiler's classpath remains
a usable type. A malformed selected index now fails compilation with its
location; delete the reported generated index and rebuild all annotated code.
The processor preserves that invalid snapshot and existing caches on a read
failure. If a cache update fails, it invalidates the stale cache; inability to
update or invalidate it also fails compilation.

## HTTP and SSE route precedence

Competing varargs routes now compare path components from left to right:
a literal outranks a single-component placeholder, and both outrank a varargs
suffix. For example, `/widgets/{id}/{rest*}` receives `/widgets/42/details`
when `/{path*}` is also registered. At the first differing component,
`/a/b/{rest*}` also outranks `/a/{id}/c/{rest*}` for `/a/b/c/file`.

Declarations without varargs remain preferred; their existing rule of fewer
placeholders, then more literals, is preserved. Equally specific overlapping
routes are rejected by the processor and built-in resolvers. Rename-only
differences between placeholder names do not break a tie. Review applications
that relied on a catch-all winning over a specific varargs route.

## MCP JSON number equality and protected state

`McpJsonNumber.equals(...)` and `hashCode()` now use numeric value regardless
of decimal scale: `1`, `1.0` and `1E+0` are equal. This also applies to numeric
leaves inside JSON objects and arrays and to their use as hash collection keys.
`getValue()` retains the supplied `BigDecimal` and scale for ordinary JSON
values. Check the decimal explicitly if scale is part of your application data.

Framework-protected request state returns canonical numbers on retries:
`100` becomes `1E+2`, `1.50` becomes `1.5`, and `0.0` becomes `0`.
The numeric value and JSON-value equality/hash contract survive the round trip;
the original number spelling and scale do not. Use exact numeric conversions
or `compareTo`. Store identifiers and exact decimal text as JSON strings.
`toPlainString()` avoids exponent notation without restoring the original
scale. The protected-state encoding and sealing profile are unchanged.

## Legacy MCP POST progress

Explicitly selected `2025-06-18` and `2025-11-25` operations use the existing
optional `McpProgressReporter` with a valid request progress token. The first
update commits POST SSE; no update returns JSON, and the terminal result stays
one complete result. After commitment, a client disconnect or lost-writer write
failure detaches delivery without itself canceling the handler. Finite/uncommitted or queued legacy calls
and modern calls still cancel on disconnect. Deadlines and physical worker
reservations remain in force, including in simulation.

Legacy streams are persistent and nonresumable, with no empty priming event,
event IDs, polling, replay/history, or recovery of a lost POST result. This does
not restore the Soklet 3.5.1 session/GET transport API. Opt-in 2025 sessions now
use the package described below; leased GET opening and verified DELETE are
available with optional HTTP admission, along with separately authorized
session-owned URI grants and resource/catalog invalidations.
Verify the actual client's progress display and retry behavior against the exact candidate before relying on them. See
[Progress and cooperative cancelation](MCP.md#progress-and-cooperative-cancelation).

## Legacy MCP session migration

The new supported `McpSessionConfig`/`McpSessionOwnerKeyResolver` package applies
only to endpoint `sessionProtocolVersions` explicitly selecting `2025-06-18`
or `2025-11-25`. Configure server ownership/bounds and endpoint selection
together; modern and default 2025 views remain stateless. See
[2025 sessions](MCP.md#explicitly-enabled-2025-sessions) for header, expiry,
quota, metadata, and cancellation behavior.

There is no source migration from `McpSessionStore`, `McpSessionContext`, a
custom session-ID generator, old session lifecycle callbacks/instruments, or
`SESSION_TERMINATED`. Use application-owned state keyed by authenticated domain
identity. Session defaults are now 256 per server and 16 per owner, with positive
limits; zero is invalid and null tuning values restore defaults. The 24-hour
idle lifetime means actual quiescence, and the separate hard lifetime defaults
to seven days. These ceilings do not promise reserved heap or host recovery.
Deployments need learned affinity from the initial response; restart/node loss
requires reinitialization and may require manual client reconnection.

GET/DELETE require the optional `transportAdmissionController` on the new
configuration. It authorizes an actual HTTP request with a current identity,
explicit expiry, and allowed notification families; no RPC method/context is
fabricated. DELETE requires empty families. GET additionally requires selected
legacy subscription revisions, matching session revisions, and effective
sources, and opens leased SSE with keepalives and authorized invalidations.
`resources/subscribe`/`resources/unsubscribe` use real POST admission and request
limiting; successful responses contain `result: {}`. URI permission comes from
the subscription authorizer, independently of GET admission. Grants survive GET
loss within their original total lifetime; duplicate subscribe refreshes evidence
without resetting that lifetime. Unsubscribe/reconciliation fence old generations.
Existing subscription duration/authorization and partition limits apply to GETs
and URI grants; renewal uses each operation's historical credentials. Keep
retained application principals/contexts small; their arbitrary graphs are not
covered by framework byte accounting.
See [leased GET and verified DELETE](MCP.md#leased-get-and-verified-delete).
Shared storage, replay, and recovery of lost POST results are not added.

`StreamTerminationReason.CLIENT_CANCELED` is a new neutral token category.
`McpStreamTerminationReason` adds `SESSION_EXPIRED` and `SESSION_CLOSED`, with
explicit MCP cause tracking. `McpOperationType` also adds `RESOURCES_SUBSCRIBE`
and `RESOURCES_UNSUBSCRIBE`. Recompile and update exhaustive switches; a
previously compiled exhaustive switch can fail if a new value reaches it.
Client cancellation has no framework-provided free-form cause. Cancellation
and expiry preserve a terminal reservation that already won and retain physical
worker/evidence reservations until exit; cancellation is not rollback.

## Legacy MCP HTTP metric routes

For observed GET/DELETE requests handled by a 2025 MCP session endpoint, the
default collector now uses the selected configured endpoint path as the
`soklet_http_*` route label and exposes `RouteType.MATCHED` in snapshot keys.
This includes endpoint-selected rejections such as an unknown session (404),
invalid request (400), or unavailable session facility (405). They no longer
share the `unmatched` route with ordinary unmatched HTTP requests. Query
values, session IDs and arbitrary request targets are not used as route labels.

Custom collectors and lifecycle observers still receive the actual HTTP
request with `ServerType.HTTP` and a null `ResourceMethod`: the MCP transport
does not invoke an HTTP resource method. Custom collectors own their route
classification/export policy. MCP POST observations continue to use the MCP
semantic callbacks.

Generic GET/DELETE finish callbacks now run on dedicated bounded workers.
Capacity is reserved before start and retained through the request lifetime
and physical finish. Saturation or quiescence skips both callbacks with a
fixed diagnostic. A blocked callback retains capacity and may leave shutdown
incomplete. See [Legacy URI grants and catalog invalidations](MCP.md#legacy-uri-grants-and-catalog-invalidations) for
the worker and queue limits.

## OpenMetrics histogram bucket labels

The default collector now renders finite histogram `le` labels in
`OPEN_METRICS_1_0` using the canonical floating-point patterns from the
[OpenMetrics 1.0 specification](https://prometheus.io/docs/specs/om/open_metrics_spec/#considerations-canonical-numbers).
For example, `128` becomes `128.0`, `1000000` becomes `1e+06`, and
`1048576` becomes `1.048576e+06`. The overflow bucket remains `+Inf`.
This formatting change applies to HTTP, SSE and MCP histograms and preserves
numeric thresholds, units and sample values. The separate sum and snapshot
API changes are described below.

`PROMETHEUS` (text format 0.0.4) retains its existing integer finite-boundary
labels. For OpenMetrics, update queries and `SnapshotTextOptions.metricFilter`
predicates that match `le` strings literally. Filters receive the exact label
string used in the selected output format. Formatting is locale independent
and preserves the integer boundary value without conversion to `double`.

## Subscription-maintenance metrics

The default collector now counts delivered `McpMetricsEvent.SubscriptionMaintenance`
events in `McpMetricsSnapshot.getSubscriptionMaintenance()`, an immutable
`Map<McpMetricsSnapshot.SubscriptionMaintenanceKey, Long>`. Create keys through
`SubscriptionMaintenanceKey.fromDimensions(endpointPath, maintenanceWork,
maintenanceOutcome)` using the existing `Work` and `Outcome` enums. The key
exposes `getEndpointPath()`, `getWork()` and `getOutcome()`; its diagnostic
rendering redacts the endpoint. This aggregate applies across supported MCP
revisions.

The snapshot builder's `subscriptionMaintenance(...)` setter copies its map,
rejects null entries and negative counts, preserves explicit zero counts, and
clears the map when passed null or an empty map. Earlier snapshots are immutable.
`reset()` clears these cumulative counts while retaining live gauges.

Both exporters add `soklet_mcp_subscription_maintenance_total`, labeled only by
configured `endpoint`, fixed `work` and fixed `outcome`. Work/outcome values are
lowercase. Maintenance-only observations are exported even without a
subscription-open event. Counts describe delivered outcome events, including
coalescing and stale-result discards; they are not unique subscriptions,
started attempts, durations or active work. Retention uses the existing
8,192-key limit; eviction or semantic-delivery overflow can omit observations.
Concurrent snapshots and resets retain the existing weak observation semantics.

## MCP request-duration histogram buckets

The default collector adds 30-, 60-, 120-, and 300-second finite boundaries
to `soklet_mcp_request_duration_nanos`. The previous finite boundaries through
15 seconds remain. These new boundaries cover the default 60-second MCP
request timeout and longer configured deadlines; values above five minutes
still use the `+Inf` bucket. Boundaries and samples remain nanoseconds, with
the same endpoint, method and outcome labels, across supported MCP revisions.

`soklet-otel` advises the same additional boundaries, expressed in seconds,
for `soklet.mcp.request.duration`. OpenTelemetry SDK views can override that
advice. Recheck dashboards, alerts and metric filters that assume the old
bucket vector or choose an exact `le` value. HTTP request, request-stream and
subscription duration layouts are unchanged by this MCP request change.

## Histogram sums and snapshot values

`MetricsCollector.HistogramSnapshot.getSum()` now returns nonnull `Double`.
The shared histogram accumulator uses floating point, so totals can grow
beyond `Long.MAX_VALUE`. This applies to duration, byte and queue-depth
histograms. Duration values remain nanoseconds, including the `_nanos` metric
families; other units and all metric names remain unchanged. Both text export
formats serialize sums as floating-point numbers.

These sums are approximate operational metrics. Rounding can discard a small
addition to a large total, and concurrent accumulation order can affect the
result. Use an application-owned exact total for exact accounting.

The snapshot constructor now accepts `List<Long>` bucket boundaries and
cumulative counts, `Long` count/min/max, and `Double` sum. Lists are
defensively copied. Both lists must have the same size, all arguments and list
elements must be nonnull, and the sum must be finite and nonnegative. Negative
zero is normalized to `0.0`. Independently constructed snapshots retain the
supplied count; it is not recomputed from the supplied buckets.

All snapshot scalar accessors now return boxed values: `Integer` for
`getBucketCount()`, `Double` for `getSum()`, and `Long` for boundary, cumulative
count, total count, minimum, maximum and percentile accessors. Indexed
accessors accept nonnull `Integer`; `getPercentile` accepts nonnull `Double`.
Zero remains a present value. Update array constructor arguments to lists,
use floating-point sum arguments, and replace sum assignments to `long`
with `double` or `Double`. Integer getters retain their full `long` precision.
Recompile applications using these changed signatures; the old primitive and
array snapshot overloads are removed.

## Histogram snapshots and reset

Snapshots returned by `MetricsCollector.Histogram.snapshot()` now derive
`getCount()` from the final captured cumulative bucket. The default collector's
histogram `_count` and `+Inf` bucket therefore agree within the same exported
point, including during concurrent recording or reset. This corrects a
separate-counter race that could leave a persistent mismatch after reset.
Metric names, labels, units and numeric bucket boundaries are unchanged.
See the boxed snapshot API and floating-point sum migration above.
An actual `Long.MAX_VALUE` sample also retains that minimum instead of being
confused with the empty-histogram marker.

Snapshots remain concurrent observations: the bucket vector, sum, minimum and
maximum do not form an atomic transaction. A record overlapping `reset()` can
be discarded or represented partly across those fields. Coordinate writers
with reset if you need an exact observation-window boundary. With no recording
in flight, reset clears the histogram and subsequent records start a new
window; previously returned snapshots remain immutable. Collector reset still
preserves live gauges and their lifecycle identity bookkeeping.

## Request diagnostics and privacy

Framework-created diagnostics no longer embed request-controlled IDs, paths,
headers, cookies, query/form values, multipart fields, bodies, or malformed raw
URLs. Malformed URL failures no longer retain an input-bearing
`URISyntaxException` cause, and default annotation binding does not retain an
input-bearing conversion failure as a cause. If application code parsed
`Request.toString()`, exception messages, or cause chains, replace that with
typed `Request` and structured exception accessors and apply application-owned
redaction before logging.

The built-in MCP server deliberately omits Throwable, Request, ResourceMethod
and MarshaledResponse attachments from its request-observer, semantic-metrics
collector and transport failure `LogEvent`s. Messages identify the failing
operation without exception text or stack traces. HTTP/SSE failure events may
still attach the original Throwable. An empty `LogEvent.getThrowable()` on
these MCP events therefore does not mean no exception occurred.

This boundary does not redact typed MCP callback arguments, exact observed
Throwables, application-created events, or the generic HTTP lifecycle callbacks
for enabled 2025 session GET/DELETE requests. Those objects remain subject to
application-owned logging and retention controls. Aggregate lifecycle-transition
observer failure events also retain their original Throwable; they are a
separate channel from MCP request observation.

Custom `RequestBodyMarshaler` implementations now distinguish expected client
parse failures from unexpected implementation failures. Catch the JSON/parser
library's malformed-input exception and throw `IllegalRequestBodyException`
with a bounded, non-input-bearing message to produce HTTP 400. Other runtime
failures propagate as server faults, are logged through the configured logger,
and produce HTTP 500; Soklet no longer converts every arbitrary marshaler
failure into a client error.

## Final verification checklist

- A fresh clean compile succeeds with annotation processing enabled.
- No server builder uses a removed transport-specific shutdown-deadline setter.
- No application calls transport `start()`, `stop()`, `close()`, or
  `isStarted()`.
- Standalone and embedded lifecycle ownership are not mixed.
- Deployment termination grace is larger than the complete documented sum.
- Simulator configurations use only scope-vended transports.
- MCP clients use Streamable HTTP and an exact revision declared by the endpoint
  and operation. Use `2026-07-28` for the full 4.0 feature set; the explicitly
  selected `2025-06-18`/`2025-11-25` adapter covers synchronous tools and
  ordinary prompt listing/retrieval and resource listing/reading, including
  Level 1 templates, custom and framework static catalog pagination, argument
  completion, and request-scoped POST progress. Framework continuation cursors
  use a separate 2,048-byte ceiling; each page uses current permissions, with
  neutral `-32602` for catalog/locale and other cursor mismatches. Modern
  static catalogs remain one page. Skills, Apps, Tasks, `subscriptions/listen`, and multi-round input
  still require `2026-07-28`. Opt-in 2025 sessions/cancellation require stable
  ownership and learned node affinity. GET/DELETE also need explicit HTTP
  admission and effective notification families; URI subscriptions need independent
  authorization. Named-host delivery and exact-candidate qualification remain pending.
- Authentication and authorization failures reveal no token or protected
  resource value.
- A real localhost listener passes discovery, list, call/read/get as applicable,
  and clean shutdown/port-release smoke.
- Dashboards and alerts use the six current shutdown outcome labels.

### Forwarded origin and servlet client addresses

`TRUST_PROXY_ALLOWLIST` origin resolution uses the rightmost proxy-supplied
`Forwarded` entry and the rightmost values of the `X-Forwarded-*` origin fields.
Missing or invalid nearest origin metadata cannot fall back to an earlier,
client-controlled entry. `TRUST_ALL` retains leftmost selection. Configure the
trusted edge to overwrite all forwarded families it accepts.

Both servlet adapters use `EffectiveClientIpResolver` for client address
selection. A forwarded client port comes from that same selected entry, or is
zero when absent; it is never borrowed from another entry or the proxy socket.
Without usable trusted forwarding, the socket address and port are returned.
IP literals are normalized with `InetAddress.getHostAddress()`, including IPv6.

### Static-file identity and cache metadata

Static-file resolver spelling follows native real-path resolution. On platforms
that canonicalize case and Unicode aliases, resolvers receive directory-entry
spelling; some Linux case-insensitive mounts retain the requested spelling instead.
Do not use spelling-only access checks on those mounts; use a case-sensitive
static root or policy suited to its filesystem. Distinct hard-link names keep their
own policies. No-follow mode rejects a symbolic-link root and links below it,
retains the configured ancestor path and resolves trusted ancestor links afresh.
With `followSymlinks` enabled, the root target is resolved at construction;
swapping an ancestor link does not retarget that helper. No-follow mode revalidates
file identity after application resolvers. Keep the served directory namespace stable during
response delivery; path-backed bodies do not provide an atomic filesystem snapshot.

The default response marshaler supplies Content-Type only when a body exists.
Explicit representation headers, including headers for HEAD, are retained.
Origin-dependent CORS responses vary by Origin across allowed, denied, and absent-Origin
requests. Controlled CORS fields are updated case-insensitively; existing Vary tokens
and wildcard variation are preserved.

When transport compression is enabled, 304 responses retain Vary: Accept-Encoding
without invoking a compressor or its body provider. Applications own representation
validators: use the same weak ETag in 200 and 304 responses when one semantic version
covers identity and compressed bytes. Automatic weakening of an encoded 200 does not
make a separately constructed strong 304 validator correct. Weak tags are valid for
If-None-Match cache validation; If-Match requires a strong, representation-specific
validator. Bodyless responses retain the application's declared validator.

### HTTP response stream metrics

For admitted `HttpServer` response streams, `MetricsCollector.didFinishRequestHandling` marks application handling and transport handoff. The active HTTP request remains counted until `didTerminateResponseStream(StreamingResponseHandle, StreamTermination, Duration, Long)` runs. That default callback receives the original dispatch request, the terminal outcome, the full monotonic request duration, and observed payload bytes. It runs once after metrics handling finish and before lifecycle stream observers. Custom collectors overriding handling finish should defer stream duration/body-size samples and their active-request decrement to this callback.

The duration starts at request processing entry and ends at logical transport termination. It excludes observer queue delay and later physical resource cleanup. `StreamTermination.getDuration()` still describes only the stream lifetime. Socket body-size observations count bytes accepted by socket writes, including partial writes before a later failure; they exclude headers and chunk framing and do not assert client receipt. Simulation counts accepted materialized bytes, including the prefix before a producer failure or simulator limit.

`MetricsCollector.Snapshot.getHttpResponseStreamTerminations()` returns an immutable `Map<HttpResponseStreamTerminationKey, Long>`. Its builder setter defensively copies input; null/empty clears, zero is retained, and null keys/values or negative counts are rejected. Keys compose `HttpServerRouteStatusKey` with `StreamTerminationReason`; diagnostic rendering redacts application dimensions. The default collector exports `soklet_http_response_stream_terminations_total` with `method`, `route`, `status_class`, and uppercase `reason` labels, under the existing 8,192-key capacity. Reset clears cumulative termination counts while preserving live request state.

A producer failure after a committed 200 remains `2xx` with `PRODUCER_FAILED`. Precommit finite replacements (including 505/503), HEAD/bodyless suppression, and unadmitted streams retain finite HTTP accounting and do not invoke the admitted-stream metrics callback. Existing handler-duration and approximate time-to-first-byte scopes remain unchanged. Dedicated SSE-server and semantic MCP metrics retain their existing ownership.

### Custom transport streaming completion

Both `MetricsCollector` and `LifecycleObserver` have a default callback:

```java
default void willWriteResponseStream(
    @NonNull StreamingResponseHandle streamingResponseHandle);
```

A transport calls it before request-handling finish only when it guarantees a
later `didTerminateResponseStream` callback, including failed handoff. Use the
same handle instance and the original dispatched `Request` identity throughout.
The terminal callback may precede handling finish; collectors must buffer that
observation without blocking the transport. A streaming response alone does not
establish that guarantee. Without preparation, the built-in metrics collector
and OTel finish request accounting at handoff. Decorators must forward both the
preparation and terminal callbacks to preserve lifetime accounting.

Request-handler decorators should preserve the returned `HttpRequestResult`,
using its `copy().finish()` path for changes. Reconstructing it from scratch
drops the dispatch association. When the decorator also copies the request,
observers may then treat termination as an independent stream observation.

Custom HTTP/SSE `TransportRuntime` shutdown methods must also tolerate
shutdown when `start` was never invoked. Report affirmative termination proof
when no owned resources or activity were started, as well as after partial
startup cleanup.

### Simulation and shutdown diagnostics

An accepted SSE initializer that fails, overflows its queue or terminates before
activation makes `Simulator.performSseRequest` throw. Established simulated
consumers run synchronously on the broadcasting or registration thread. Their
reentrant/concurrent pending deliveries remain bounded, but simulation does not
model network backpressure and emits no broadcast/drop or socket-write metrics.

`ShutdownResult.toString()` labels residual counts as
`residualComponentCounts`: each count is the number of components reporting a
category, not the number of retained tasks or callbacks. One object used in both
HTTP and SSE configuration slots must provide distinct transport identities.

Lifecycle completion stages are handed off to isolated completion execution.
`awaitShutdown` waits for lifecycle classification and does not wait for arbitrary
dependents of the public stage. Join the stage explicitly when its completion
publication or your dependent action is required. Keep dependent actions bounded.
