# Built-in SSE lifecycle footprint probe

`benchmarks/src/main/java/com/soklet/SseLifecycleFootprintProbe.java` is a
standalone probe of the built-in SSE server over loopback sockets. The current
fixture has a finite, synchronous initializer and fills connections through
`SseBroadcaster`; it owns no per-client subscription or unicaster termination
listener. The September 22 [milestone 5b measurements](streaming-api-milestone-5b.md)
belong to the earlier subscription/callback candidate and do not quantify this
final 4.0 shape.
Three-trial measurements of the final initializer/broadcaster candidate are
recorded in [the September 23 integration checks](sse-final-candidate-checks-2026-09-23.md).

The probe compiles with Java 17 and requires Java 21+ at runtime. It needs an
already compiled current runtime in `target/classes`; it does not build the
benchmark module or fetch dependencies. Run measurements alone, without
concurrent builds, tests, or throughput benchmarks. The default invocation uses
the server's unmodified **256 lifecycle slots and 128 application queue elements
per connection** and checks both values. `--connections=8` is a smaller smoke
fixture with an explicit eight-slot admission setting; it is not evidence for
the default population. The 257th real handshake at the default is rejected
with HTTP 503 before the initializer runs.

## Controlled states

Two complete 16-client lifecycles warm startup, queue fill, termination,
reflection, and heap sampling. The measured lifecycle then records:

1. A warmed baseline with all warmup runtime references released.
2. The started server with no clients.
3. All 256 clients connected and initialized, with empty application queues.
4. In `--mode=queued`, all writers held inside
   `LifecycleObserver.willWriteSseEvent`, with **one in-flight event plus exactly
   128 queued events per client**, all published through a broadcaster.
5. Completed shutdown while stopped runtime, connection, client-socket, and
   writer-thread references are deliberately retained. This is not an isolated
   public closed-handle size measurement.
6. All probe runtime references released. This separate sample avoids treating
   memory held by the probe's own stopped-runtime references as a leak.

The writer latch proves the first event has left each queue before filling it.
For each further event, `SseBroadcaster.broadcastEvent` uses each client's
context to make distinct payloads. Read-only inspection of private queue
carriers verifies exact depth, order, client-specific content, and distinct
`SseEvent`, data `String`, and serialized byte-array identities. The reflective
adapter fails if those internal carriers change; it never mutates production
queues or bypasses public publication. Temporary identity maps and expected
serialization arrays are discarded before the next GC sample.

`--payload-bytes=64` and `--payload-bytes=1024` count ASCII **data bytes**, not
the entire SSE frame. Each frame contains `data: `, the data, and two line
feeds, adding eight bytes. At 256 clients the full state contains 32,768
queued serialized arrays, plus 256 in-flight events. The inferred in-flight
byte count follows the same serialization path; the probe directly inspects
queued arrays but not writer-local serialized arrays.

| Data bytes per event | Queued serialized bytes | Inferred in-flight serialized bytes | Distinct event objects / data Strings |
| ---: | ---: | ---: | ---: |
| 64 | 2,359,296 | 18,432 | 33,024 each |
| 1,024 | 33,816,576 | 264,192 | 33,024 each |

These are expected payload counts, **not measured total heap values**. Event
and String objects, backing arrays, queue elements, and server state add to
serialized byte storage. Heartbeat interval is one hour and the one-time
connection-verification heartbeat is disabled to keep queue contents stable.
The probe uses 10/5-second graceful/forced shutdown budgets. During teardown it
signals shutdown first, waits for known connections to become terminal, and
then releases held writers; this permits terminal queue discard without trying
to drain into intentionally unread client sockets.

## Standalone compilation and runs

From the worktree root, with the final runtime already compiled:

```sh
probe_jdk=/Users/agents/Java/amazon-corretto-21.jdk/Contents/Home
probe_output=/private/tmp/soklet-sse-footprint-probe
probe_dependencies=target/classes:/Users/agents/.m2/repository/org/jspecify/jspecify/1.0.1/jspecify-1.0.1.jar:/Users/agents/.m2/repository/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar:/Users/agents/.m2/repository/com/google/errorprone/error_prone_annotations/2.50.0/error_prone_annotations-2.50.0.jar
mkdir -p "$probe_output"
"$probe_jdk/bin/javac" --release 17 -proc:none \
  -cp "$probe_dependencies" -d "$probe_output" \
  benchmarks/src/main/java/com/soklet/SseLifecycleFootprintProbe.java

# Small correctness smoke. Do not mix its output with default-capacity results.
"$probe_jdk/bin/java" -Xms256m -Xmx256m -XX:+UseSerialGC \
  -cp "$probe_output:$probe_dependencies" com.soklet.SseLifecycleFootprintProbe \
  --mode=queued --connections=8 --payload-bytes=64

# Each command starts a fresh JVM. Repeat each scenario at least three times.
"$probe_jdk/bin/java" -Xms512m -Xmx512m -XX:+UseSerialGC \
  -cp "$probe_output:$probe_dependencies" com.soklet.SseLifecycleFootprintProbe \
  --mode=queued --connections=256 --payload-bytes=64
"$probe_jdk/bin/java" -Xms512m -Xmx512m -XX:+UseSerialGC \
  -cp "$probe_output:$probe_dependencies" com.soklet.SseLifecycleFootprintProbe \
  --mode=queued --connections=256 --payload-bytes=1024
```

Use `--mode=idle` to omit measured queue fill; warmups still exercise it. The
former `--resources` comparison is removed because the final SSE initializer
does not own a connection-lifetime subscription. Preserve stdout as JSONL and
stderr separately. A successful run ends with a `type=result` record whose
capacity, queue-identity, connection-unregister, payload-release, executor, and
writer-exit checks are all true. An exception or nonzero exit invalidates the
run; do not summarize partial samples as successful evidence. Every stage uses
the minimum heap after three requested GCs, 50 milliseconds apart. Explicit GC
remains a request. Each controlled step has a 20-second bound; failure cleanup
releases the writer latch and closes sockets.

The metadata record preserves runtime location, VM/version/options, and a
SHA-256 of loaded class bytes for `DefaultSseServer`, `ManagedSseLifecycle`,
`StreamLifecycleCoordinator`, `SseEvent`, this probe, and their declared nested
classes. It identifies the measured subset, not the whole application ABI.
Preserve repository/source identity alongside raw output.

## Interpretation limits

Heap numbers include both loopback endpoints' Java objects, server/request
state, application payloads, virtual-thread objects/stack chunks, and probe
reference arrays. They are approximate whole-JVM post-GC heap, not graph-derived
retained size, server-only memory, allocation rate, RSS, native thread stacks,
or kernel socket buffers. Carrier pools and VM bookkeeping can outlive a
lifecycle and change the all-references-released sample without proving a leak.

`ThreadMXBean` and `Thread.getAllStackTraces()` report platform threads only.
The probe separately tracks supplied writer threads and their virtual/platform
identities. It verifies captured SSE executors and the lifecycle coordinator
terminate; it does not infer virtual-thread retirement from platform counts.

A lifecycle capacity of 256 and queue capacity of 128 are **not universal memory
bounds**. SSE limits queued event count rather than bytes, so a single event
String can be arbitrarily large. These controlled populations inform the default
decision alongside connection admission, shutdown, and broadcaster throughput
evidence; the footprint probe alone cannot qualify those defaults.
