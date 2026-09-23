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

### HTTP streaming callbacks and sources

`StreamingResponseWriter.writeTo` now takes one `ResponseStream` argument;
the separate public `StreamingResponseContext` is removed. Move request,
deadline, and idle-timeout access to `responseStream.getRequest()`,
`getDeadline()`, and `getIdleTimeout()`. Use
`responseStream.getCancelationToken()` for cancelation checks and callback
registration. `CancelationToken.onCancel(...)` returns `CallbackRegistration`,
whose `close()` removes an unclaimed callback without checked exceptions.

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
scoped shutdown records the outstanding work within its own deadline.

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

`SimulatorConfig.fromSokletConfig(...)`, `withSokletConfig(...)`, and the
corresponding `SokletSimulator.run(...)` overload inherit these immutable values
from a built-in HTTP server while creating fresh simulation state. Derivation
does not start or otherwise change the source transport, which is not retained
by that state. Deriving again
from a simulator configuration preserves the values. A simulator created without
a source, or derived from a custom HTTP transport, uses the defaults above.

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

Derived simulators copy lifecycle capacity and connection queue capacity
from a built-in SSE server into fresh state, without starting or changing the
source transport. Re-derivation preserves them; default or custom source
transports use the simulator defaults. `SseRequestResult.HandshakeAccepted`
now implements `AutoCloseable` with unchecked, idempotent `close()`, which
simulates `CLIENT_DISCONNECTED`. Simulator teardown terminates remaining
connections with `SERVER_STOPPING`, even when no event/comment consumers were
registered. The first outcome wins. Closing removes delivery registrations,
rejects new consumers and writes, and retains any physically unfinished work.

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

The old synchronous, void `Soklet.stop()` stopped transports before returning
but provided no aggregate terminal evidence. It is replaced by:

```java
CompletionStage<ShutdownResult> completion = soklet.shutdown();
ShutdownResult result = soklet.awaitShutdown();
```

`shutdown()` promptly publishes intent and always returns the same read-only
completion stage. `awaitShutdown()` takes no shutdown trigger and returns the
immutable terminal result. `Soklet.close()` remains available for direct
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
own process hooks. When a standalone process owns application resources too,
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

Review the builder Javadocs before selecting zero-duration phases or changing
the finite startup timeout. A normal running shutdown with defaults is bounded
by 18 seconds; shutdown intent during startup is bounded by 20 seconds from
that intent.

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
seconds. Round up to at least 31 seconds. The commonly documented 35-second
setting retains a reserve. Adding a five-second cleanup budget raises the same
example's minimum to 36 seconds, so use at least 40 seconds or reduce a measured
component.

### Observer and result changes

`LifecycleObserver.didFailToStopSoklet(...)` and the three transport-specific
`didFailToStop...` callbacks are removed. The corresponding `didStop...`
callback now receives `ShutdownResult` or `ShutdownComponentResult`, which
is the terminal evidence for successful, forced, unexpected, residual, and
unknown termination. Observer callbacks are observational: exceptions are
contained and do not rewrite lifecycle results.

MCP shutdown metrics and downstream OpenTelemetry projections use exactly:

- `not_started`
- `graceful_termination`
- `forced_termination`
- `unexpected_termination`
- `residual_activity`
- `termination_unknown`

Do not infer this set dynamically from enum constants; use an exhaustive
mapping so a future enum addition cannot silently change metric cardinality.

## HTTP, SSE, and custom transports

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

`ResponseMarshaler` now provides a separate hook for four parser-owned failures
that occur before the standard HTTP transport can construct a valid request:
malformed requests, overlong request targets, unsupported expectations, and
oversized request headers. Direct implementations of the interface inherit the
bodyless default for `forUnparsedRequest(UnparsedRequest)` and may override it;
applications using `ResponseMarshaler.builder()` may keep the default
implementation or configure `unparsedRequestHandler(...)`. The immutable value
supplies `ServerType`, an `UnparsedRequestReason`, a best-effort remote address,
a fresh read-only view of the bounded raw-input capture, the byte count
attributed to the rejected request through the parser-proven failure boundary,
and whether the capture omits any of those attributed bytes. The built-in HTTP
capture is capped at 64 KiB. Bytes already read from the socket beyond the
failure boundary are neither captured nor counted because they might belong to
a pipelined request.

The default marshaler and built-in fallback use these conventional statuses:

- `MALFORMED_REQUEST` (`400`)
- `REQUEST_TARGET_TOO_LONG` (`414`)
- `EXPECTATION_FAILED` (`417`)
- `REQUEST_HEADERS_TOO_LARGE` (`431`)

The enum does not own a status: a custom marshaler may return any final response
status from `200` through `599`. It deliberately receives no synthetic or
nullable `Request`, parsed headers or target, or parser exception. Captured
bytes are raw, unredacted network input and can contain credentials, cookies,
body fragments, control bytes, or non-text data; do not log, meter, reflect, or
persist them without application-specific redaction and retention controls.

For each eligible parser rejection, Soklet submits one task to the configured
request-handler executor. The framework-managed default executor has bounded
concurrency and queue capacity; a custom executor controls its own capacity. If
admitted, Soklet calls
`LifecycleObserver.didRejectUnparsedRequest(UnparsedRequest)` before the
marshaler; observer failures are contained, both operations share
`requestHandlerTimeout`, and the marshaler runs only if budget remains after
observation. That timeout bounds how long the transport waits and interrupts the
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

Other failures before request construction, such as a partial-request read
timeout or an aggregate-size violation before the request line can be trusted,
may close the connection without either detailed callback.

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

See the [javax Javadocs](https://javax.javadoc.soklet.com/com/soklet/servlet/javax/package-summary.html)
or [Jakarta Javadocs](https://jakarta.javadoc.soklet.com/com/soklet/servlet/jakarta/package-summary.html)
for the matching container namespace.

## Annotation-processing migration

Enable `SokletProcessor` and `-parameters` for every module containing Soklet
annotations. Runtime handler-method classpath scanning is not a fallback.
Generated resources must survive shading and packaging.

The processor rejects unsupported or ambiguous method/record shapes at build
time. This can surface errors that 3.5.1 deferred until runtime.

## Request diagnostics and privacy

Framework-created diagnostics no longer embed request-controlled IDs, paths,
headers, cookies, query/form values, multipart fields, bodies, or malformed raw
URLs. Malformed URL failures no longer retain an input-bearing
`URISyntaxException` cause, and default annotation binding does not retain an
input-bearing conversion failure as a cause. If application code parsed
`Request.toString()`, exception messages, or cause chains, replace that with
typed `Request` and structured exception accessors and apply application-owned
redaction before logging.

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
- MCP clients use Streamable HTTP and the exact `2026-07-28` profile.
- Authentication and authorization failures reveal no token or protected
  resource value.
- A real localhost listener passes discovery, list, call/read/get as applicable,
  and clean shutdown/port-release smoke.
- Dashboards and alerts use the six current shutdown outcome labels.
