# HTTP streaming API qualification — 2026-09-22

This records the final HTTP output-path qualification after slices 3a–3f in the
isolated worktree based on `c5d02871`. It covers HTTP/simulator implementation,
output batching, allocation, and retained queue memory. SSE connection ownership
and passive disconnect detection retain their separate milestone acceptance
conditions. This is not a 4.0 release approval.

Current API amendment, 2026-09-22: `ResponseStream.writeUtf8(String)` was
removed before 4.0.0, and the `output-utf8` benchmark workload was retired.
The measurements below remain an exact record of the earlier implementation,
not performance or heap qualification for the final API. Rerun the affected
workloads and footprint probe before citing current performance bounds.

## Measurement boundaries

The standalone Java 17 loopback harness compares four ways to produce the same
65,536-byte Unicode body: eight native 8 KiB writes; 65,536 scalar view writes;
one `writeUtf8` call; or mixed scalar, array-slice, heap/direct/read-only buffer,
and bulk-view writes. Every successful response must contain the complete exact
body and expected status. The harness rotates scenario order across iterations.

Allocation uses the JVM's total thread-allocation counter. It includes colocated
clients, server work, lifecycle observer tails, and measurement bookkeeping;
it is not server-only allocation and does not measure native/direct memory.
The harness requires a supported allocation counter for these runs. Missing or
invalid allocation/GC counters remain unavailable rather than becoming zero.
All tracked streaming lifetimes drain before and after each allocation window.
Client execution is joined before the ending snapshot, while request throughput
and latency stop at client completion and exclude this final drain.

The four output modes compare API alternatives within the same current runtime.
The current one-argument writer harness is incompatible with the historical
two-argument writer runtime. No classpath swap or unpaired historical measurement
is used to claim a baseline performance improvement. The earlier milestone 1
comparison, including its material tiny-response regression, remains historical
evidence and is not superseded by an absolute current-runtime snapshot.

The benchmark is compiled independently against the current core. Its 11 harness
tests pass; the full benchmark module's previously recorded unrelated
`McpSubscriptionRenewalBenchmark.addResource(...)` compile blocker remains open.
The [harness test log](streaming-api-evidence/qualification-2026-09-22/harness-tests.log)
records exact compiler and launcher commands.

## Results

The repeated equal-body run uses 16 clients, two event-loop threads, eight
request handlers, and backlog 256, with two seconds of warm-up and five seconds
of measurement per mode across three iterations. Metrics collection is disabled.
Throughput and p99 below are medians across those iterations; allocation is total
allocated bytes divided by total successful requests across the measured windows.

| Output path, 64 KiB response | Requests/s | Body MiB/s | Whole-JVM KiB/request | p99 latency |
|---|---:|---:|---:|---:|
| Native, eight 8 KiB writes | 27,331 | 1,708.17 | 96.25 | 0.77 ms |
| Scalar view, 65,536 calls | 414 | 25.85 | 106.62 | 43.50 ms |
| `writeUtf8`, one call | 21,379 | 1,336.18 | 104.27 | 0.95 ms |
| Mixed output, 1,064 calls | 9,921 | 620.08 | 141.01 | 2.97 ms |

All **901,171 measured responses** in this run validated, with zero client
errors. The [raw results](streaming-api-evidence/qualification-2026-09-22/output-16.json)
include individual iterations, latency summaries, allocation counters,
and GC count/time. Across each mode's three repeated windows, GC
collection counts/time were native 74/119 ms, scalar 1/2 ms, UTF-8 65/105 ms,
and mixed 34/59 ms.

Before the polling correction, local medians were 4.54 MiB/s for scalar output
and 265.23 MiB/s for mixed output. The same harness and settings measured
**5.69× scalar** and **2.34× mixed** throughput after correction. Native and UTF-8
medians were about 4% and 6% higher, respectively; these smaller differences are
not treated as a general performance guarantee. These are repeated local runs,
not an independent hardware benchmark or an isolated estimate of every cost.

Scalar calls remain much slower than bulk writes: bounded buffering avoids a
payload allocation/chunk per byte, but each call still checks owner, lifetime,
cancelation, interruption, and idle activity. The workload deliberately stresses
65,536 calls per response. The 11 harness tests independently verify that native,
scalar, and UTF-8 paths each generate eight underlying writes for this body;
the mixed path generates 48. Whole-JVM allocation of scalar output is about
10.4 KiB/request above native in this fixture, consistent with bounded staging
and framework overhead, not a per-byte payload allocation claim.

A separate current-runtime snapshot uses one second of warm-up and four seconds
of measurement over two iterations for the existing workloads: a 13-byte response
at 47,027 requests/s, 256 KiB in 64 native writes at 7,701 requests/s
(1,925 MiB/s), and 32 paced 1 KiB writes at about 200 requests/s. All
**439,524 measured responses** validate. See the
[snapshot results](streaming-api-evidence/qualification-2026-09-22/streaming-16.json).
Requested pacing is 1 ms before each write; observed latency also includes OS
scheduling and producer-pool queueing.

The [128-client check](streaming-api-evidence/qualification-2026-09-22/output-128.json)
uses the same four equal-body modes with one second of warm-up and three seconds
of measurement each, one iteration, and backlog 256. All **188,615 measured
responses** validate with zero errors. This checks concurrent delivery, not
paired throughput improvement or 256 simultaneous producers. In total the three
final runs validate **1,529,310 responses with zero errors**. The
[measurement summary](streaming-api-evidence/qualification-2026-09-22/measurement-summary.json)
and [serial runner](streaming-api-evidence/qualification-2026-09-22/run-loopback.py)
preserve counts and reproduction settings.

## Scalar polling correction

The first equal-body run exposed a shared-lock bottleneck: each scalar call
repeatedly polled the coordinator through the HTTP cancelation token. The
before-change run is preserved with its
[source identity](streaming-api-evidence/qualification-2026-09-22/before-polling-fix-identity.json)
and [raw results](streaming-api-evidence/qualification-2026-09-22/output-16-before-polling-fix.json).

The correction makes the coordinator's monotonic cancelation and successful
production status observable through volatile reads. Outcome elections and
paired reason/cause reads remain under the existing lock. Healthy token polling
avoids that shared lock; a positive cancelation check uses the locked getters to
read the fully published outcome. Cancelation is read before completion so a
late transport failure cannot revive a completed producer token.

The final 419-test regression includes controlled checks for cancelation before
source-hook publication, exact reason/cause preservation, late transport failure
after successful production, and status polling while another stream's cleanup
transition holds the coordinator lock. This is a small observation-path change,
not a change to outcome election or physical-retirement accounting.

## Full-capacity retained heap

Three fresh Java 17 JVMs per mode use `-Xms768m -Xmx768m -XX:+UseSerialGC`, 256
lifecycle slots, 1 MiB queues, 16 KiB chunks, and eight prestarted producer
threads. The empty-runtime comparison includes the same producer pool and
reference arrays. Samples use the minimum heap usage following three requested
GCs; they estimate retained Java heap, not RSS or exact object sizes.

| Mode | Increment over empty runtime | Retained producer state |
|---|---:|---|
| 256 full queues | 273,749,216–273,749,768 bytes (261.068 MiB) | All producers physically exited |
| 256 full queues plus eight active output helpers | 273,952,768–273,953,496 bytes (261.262 MiB) | Eight producers blocked waiting for queue capacity |

Both modes retain 256 MiB of queued payload. In the active mode, each of the
eight blocked producers also retains an allocated scalar stage, UTF-8 encoder
scratch, and pending payload of 8192 bytes each: 196,608 additional payload
bytes in total. Queue-filled latches, exact coordinator counts, and bounded
stack/state checks verify the eight queue waits before sampling. Earlier
producers exit normally, which permits all 256 queues to fill using eight
platform threads.

All six runs reject admission 257 before production, then verify exactly one
termination notification per response, zero retained reservations, and zero
owned threads after teardown. Retaining disposed fixture handles still retains
small response/request objects; releasing those handles returns close to the
warmed baseline. Raw [full-queue](streaming-api-evidence/qualification-2026-09-22/footprint-full-1.log)
and [active-output](streaming-api-evidence/qualification-2026-09-22/footprint-active-output-1.log)
logs and the [six-run summary](streaming-api-evidence/qualification-2026-09-22/footprint-summary.json)
record the bounds and teardown proof.

The separate analytic maximum allows every admitted lifetime to retain staging,
UTF-8 scratch, and a maximum pending chunk at once: 256 MiB queued + 2 MiB staging +
2 MiB scratch + 4 MiB pending = **264 MiB of framework payload**, before object
overhead. The experiment does not claim to measure 256 simultaneous producers.
It also excludes sockets, native stacks, direct/native memory, application-owned
resources/input buffers, and arbitrary observer state. Larger configured
capacities require their own memory budget; this does not qualify every accepted
builder value.

## Milestone outcome

**Milestone 3 is complete for the documented HTTP/simulator scope.** The
[settings/integration report](streaming-api-milestone-3f.md) records 419 passing
Java 17 regression tests and the clean package/API-report build. The output
contracts, ZIP central-directory finalization, typed interruption, ownership,
publisher accounting, and simulator parity were integrated in slices 3a–3e;
this pass completes public HTTP settings and final output-path qualification.
Eleven standalone harness tests and the measurements above provide batching,
allocation, throughput, full-capacity retention, and teardown evidence.

SSE connection ownership and its defaults still require milestone 5. The
independent passive-disconnect work remains milestone 4. Existing aggregate API
review drift, candidate-bound privacy inventory regeneration, downstream
consumers, the unrelated full benchmark-module compile blocker, and broader
release checks remain milestone 6 work. No historical signature snapshot or
release approval is replaced by this HTTP qualification.

The [qualification source identity](streaming-api-evidence/qualification-2026-09-22/source-identity.json)
binds the final runtime/harness sources, canonical plan, and raw measurement
artifacts. The earlier polling run has its separate source identity.

## Reproduction

Compile the current core with Java 17 and compile the standalone
`EndToEndHttpBenchmark` and `StreamingQueueFootprintProbe` against those classes.
The evidence logs record the exact compiler classpaths. The repeated equal-body
run uses:

```sh
java -Dsoklet.e2e.requireAllocationMetrics=true \
  -Dsoklet.e2e.warmupSeconds=2 -Dsoklet.e2e.durationSeconds=5 \
  -Dsoklet.e2e.iterations=3 -Dsoklet.e2e.clients=16 \
  -Dsoklet.e2e.serverConcurrency=2 -Dsoklet.e2e.handlerConcurrency=8 \
  -Dsoklet.e2e.socketPendingConnectionLimit=256 \
  -Dsoklet.e2e.scenarios=output-native,output-scalar,output-utf8,output-mixed \
  -Dsoklet.e2e.output=output-16.json \
  -cp target/streaming-benchmark:target/classes com.soklet.EndToEndHttpBenchmark
```

The actual runs use `/private/tmp/soklet-m3f-benchmarks` for standalone harness
classes so a later core clean build cannot remove the harness. All measurements
are local exploratory observations, not portable hardware capacity guarantees.

For each full-capacity memory sample, run a fresh JVM with:

```sh
java -Xms768m -Xmx768m -XX:+UseSerialGC \
  -Dsoklet.queueFootprint.capacity=256 \
  -Dsoklet.queueFootprint.activeOutput=true \
  -cp target/streaming-benchmark:target/classes \
  com.soklet.internal.microhttp.StreamingQueueFootprintProbe
```

Omit `activeOutput=true` for the exited-producer mode. Each mode was run three
times. The raw logs include commands, JVM settings, runtime/probe bytecode hashes,
and explicit measurement limits.
