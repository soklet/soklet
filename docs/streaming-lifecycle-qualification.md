# Streaming lifecycle qualification — 2026-09-21

This qualifies the bounded HTTP lifecycle runtime for the next API-design
milestone. It does not qualify 4.0 for release, the public ownership scope, SSE
ownership, or asynchronous publisher acquisition. The implementation remains in
the isolated worktree based on `c5d02871`; no historical release approval is reused.
The [implementation checkpoint](streaming-lifecycle-milestone-1.md) describes the
accounting and admission design.

## Decisions

Select **256 admitted lifetimes, four callback workers, and five seconds of
cleanup grace** as the initial HTTP defaults. Public builder spelling and
placement remain milestone 2 work. `DefaultHttpServer` now uses named internal
constants for these defaults.

The earlier 1,024-slot experiment understated potential memory exposure when
viewed through its 164-byte idle-reservation measurement. A stream may retain
its existing 1 MiB output queue after its producer exits. Reducing capacity to
256 limits that particular queued-payload exposure to 256 MiB at the default
queue size; the full-queue probe below includes another approximately 5 MiB of
Java objects. This is a conservative starting policy, not a heap-sizing formula.
Applications with smaller heaps or different concurrency needs must be able to
set both lifecycle capacity and per-stream queue size through the final builders.

Capacity does not bound arbitrary resources, callback registrations, or
application buffers attached to one lifetime. Active producers can additionally
hold a chunk awaiting enqueue. Request/connection state, native thread stacks,
and application memory are outside the queued-payload calculation. Connection
and producer-executor admission remain independent limits. A slow receiver or
blocked terminal callback retains a lifecycle slot until its obligations retire.

Four callback workers bound server-owned execution independently of producer
threads. Four stuck callbacks can stop subsequent callback progress; admission
and residual accounting still contain that backlog. The supervisor and the
single diagnostic worker remain independent. Five seconds is the deadline for
marking cleanup overdue and signaling termination where appropriate, not a
promise that application cleanup returns within five seconds. An enclosing
shutdown budget can stop waiting sooner without retiring that work.

Validation is explicit:

- Lifecycle capacity is positive and at most `Integer.MAX_VALUE / 2`, allowing
  the bounded queue for two terminal jobs per reservation to be represented.
- Callback concurrency is positive and no greater than lifecycle capacity.
- Cleanup grace is strictly positive and representable in nanoseconds. Zero
  does not disable supervision; overflow is rejected instead of truncated.
- `null` will restore each default at the public builder layer. Internal
  coordinator dependencies are required and non-null.
- Cleanup grace need not be less than the response or shutdown timeout. These
  are separate budgets, with the deadline/precedence rules in the checkpoint.

Normal managed finalization that exceeds its grace now elects
**`StreamTerminationReason.CLEANUP_TIMEOUT`**, with no synthetic application
cause. Existing disconnect, response timeout, producer failure, or shutdown
reasons retain precedence. Observer expiry after successful delivery is
diagnostic-only and cannot rewrite `COMPLETED`. Both MCP mappings classify
cleanup expiry as `INTERNAL_ERROR`; it is not a request deadline violation.
`StreamTerminationReason` is classified as a non-MCP API-delta owner in the
existing inventory. Frozen MCP signatures and historical evidence are unchanged.

## Throughput and profiling

The same enhanced loopback harness ran against clean baseline classes and the
implementation. It validates status 200, exact body length and bytes, and
complete chunk framing before counting a successful response. Five parser tests
exercise wrong status/content, truncation, premature EOF, and consecutive
keep-alive responses. Three further tests verify bounded error categorization and
aggregation. Validation is included in measured client work.

Both runs used Corretto 17.0.20.1 on the same macOS/arm64 host, 16 clients, two
event loops, eight request-handler threads, metrics disabled, and three rotated
iterations with three seconds of warm-up and ten seconds of measurement per
scenario. Each revision ran in a fresh JVM, sequentially, without machine
isolation. The baseline ran first. These are development measurements, not
confidence intervals or public performance guarantees.

| Workload | Baseline req/s | Implementation req/s | Change | Baseline p99 | Implementation p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 14-byte writer, one write | 55,163 | 47,374 | −14.1% | 532 µs | 639 µs |
| 256 KiB, 64 writes of 4 KiB | 7,959 | 7,843 | −1.5% | 4.42 ms | 3.80 ms |
| 32 KiB, 32 paced writes of 1 KiB | 201 | 201 | approximately unchanged | 84.10 ms | 84.36 ms |

The paced writer requests a 1 ms sleep before every write; actual timing includes
OS scheduling and producer-pool queueing. Its median end-to-end latency was about
79 ms with either runtime. It is a modest-duration streaming workload, not
evidence about hour-long connections. Bulk body throughput was approximately
1,990 versus 1,961 MiB/s. Every measured response validated without errors.

The tiny-response cost remains material. Keep it visible as a release
performance consideration; this milestone does not claim throughput parity.
The longer-workload results support continuing the bounded runtime design and
its public naming gate without speculative concurrency rewrites. Precommit
producer admission and asynchronous observer execution remain necessary parts
of the contract.

A 19-second JFR recording of the preceding runtime yielded 141 Java execution
samples and no monitor-enter events at the profile configuration's default
threshold. Much sampled work was outside the new coordinator, including request
timeouts and parsing. This does not establish that short lock contention is
absent or identify one cause of the regression. The recording justified no
lock-free outcome rewrite or replacement timer scheduler. Those optimizations
remain contingent on better attribution and race qualification.

The implemented allocation improvement is deliberately smaller: callback sets
are allocated on first registration, detached without replacement, and empty
completion uses a shared action. Empty cancelation batches need no dispatcher
job. The existing elections and physical-retirement rules remain unchanged.
These runs do not isolate or quantify that optimization's contribution.

A separate 128-client check initially exposed connection errors on both the
implementation and clean baseline. The diagnostic harness classified the errors
as connect failures (`Invalid argument`) and response-read socket resets; there
were no unexpected statuses or body/framing validation failures. Increasing only
the harness's listen backlog from the OS default to 256 eliminated them in the
controlled rerun. This supports the synchronized connection-burst explanation;
the production backlog default is unchanged. Errorful runs are retained and
excluded from performance-pass claims.

With that explicit backlog, 128 clients completed **289,676 validated responses
with zero errors** across all three workloads (one-second warm-up, five-second
measurement each, one iteration). This is a concurrency check, not a paired
throughput comparison. It exercises the selected 256-slot runtime default but
does not establish a peak reservation bound of 128: prior response observers may
overlap subsequent requests. The listen backlog and lifecycle admission capacity
serve different purposes and remain independently configurable.

## Buffered-stream memory

`StreamingQueueFootprintProbe` fills 256 real streaming queues with 1 MiB each,
using eight producer threads and the current 16 KiB chunk default. It consumes
no body bytes. All 256 producer envelopes exit while all 256 reservations and
their queued output remain retained. The 257th admission is rejected.

Three fresh Java 17 JVMs with `-Xms768m -Xmx768m -XX:+UseSerialGC` measured an
increment of **273,733,160–273,733,520 bytes (261.052 MiB)** over an empty runtime
with the same prestarted producers and reference arrays. Of that, 268,435,456
bytes are body payload. This is approximately 1.020 MiB per stream for this
fixture, excluding sockets, native stacks, active producer input, and arbitrary
application resources. It is approximate post-GC retained Java heap, not RSS.

Every run verified exactly one termination notification per stream, zero retained
reservations after forced discard, and zero owned threads after shutdown.
Keeping the disposed fixture objects reachable retained about 0.6 MiB; releasing
the fixture returned near the warmed baseline. This distinguishes proper payload
release from expecting the disposed response handles themselves to disappear.

The raw measurements and source identities live in
[`streaming-lifecycle-evidence/qualification-2026-09-21/`](streaming-lifecycle-evidence/qualification-2026-09-21/).
Earlier exploratory measurements remain separate and are not overwritten.

## Verification and milestone outcome

The Java 17 targeted regression batch passes **176 tests**, with no failures,
errors, or skips. It covers constructor validation, nanosecond deadline bounds
and signed clock wrap, blocked physical cleanup, saturated workers, admission,
transport/producer races, normal delivery, and MCP classification of cleanup
expiry. The real HTTP blocked-finalizer test now asserts `CLEANUP_TIMEOUT`
specifically. The [test manifest](streaming-lifecycle-evidence/qualification-2026-09-21/tests.json)
records the exact selection and per-suite results.

All **eight harness tests** pass through the JUnit 6.1.3 launcher against the
standalone benchmark classes. The full benchmark-module Maven build has a
preexisting compile failure in `McpSubscriptionRenewalBenchmark`: its
`McpEndpoint.Builder.addResource(...)` call no longer exists. That benchmark and
the endpoint builder are unchanged by this work. The isolated test run checks
the edited harness without representing the full benchmark module as passing.

The aggregate API freeze check is also a **preexisting release blocker**. Clean
`c5d02871` and this worktree produce byte-identical 670-entry incompatibility
lists; both differ from the reviewed snapshot by 15 unexpected and four missing
entries. Independent inventory and all four MCP signature checks fail identically
on both revisions, and their generated MCP signatures are byte-identical. The
[incremental comparison](streaming-lifecycle-evidence/qualification-2026-09-21/api-incremental-summary.txt)
records the details. No reviewed incompatibility set, frozen signature snapshot,
or historical G3/D1p evidence was regenerated. Reconcile that existing drift as
part of release qualification; these results are not a green aggregate gate.

**Milestone 1's bounded-runtime feasibility checkpoint is complete.** The tested
design requires neither unbounded workers/queues, application cleanup on
protected infrastructure threads, post-commit producer admission, nor premature
physical-work retirement. The selected defaults and material tiny-response cost
are explicit. Proceed to milestone 2's compiled naming/handler fixtures. This
decision accepts the observed runtime tradeoff for continued implementation;
it does not declare the redesign or its release qualification complete.

Remaining effort is still several substantive passes: settle ownership and
builder spelling with compiled examples; migrate the HTTP public scope; bring
source/publisher adapters and the simulator into parity, including pending
asynchronous acquisition; integrate SSE connection ownership; and qualify
consumers, documentation, performance, and release artifacts. The preexisting
benchmark and API-gate drift must be resolved for the last pass. Passive
disconnect detection can proceed independently. No calendar commitment follows
from these exploratory measurements.

## Reproduction

Compile core classes with Java 17. Compile the standalone benchmark and probe
with annotation dependencies available, writing only benchmark classes to
`target/streaming-benchmark`. The throughput comparison used:

```sh
java -Dsoklet.e2e.warmupSeconds=3 -Dsoklet.e2e.durationSeconds=10 \
  -Dsoklet.e2e.iterations=3 -Dsoklet.e2e.clients=16 \
  -Dsoklet.e2e.serverConcurrency=2 -Dsoklet.e2e.handlerConcurrency=8 \
  -Dsoklet.e2e.scenarios=streaming,streaming-bulk,streaming-paced \
  -Dsoklet.e2e.output=target/streaming-results.json \
  -cp target/streaming-benchmark:target/classes com.soklet.EndToEndHttpBenchmark
```

For baseline measurement, replace the second classpath entry with the clean
`c5d02871` checkout's Java 17 `target/classes`. Keep the same benchmark classes
and arguments. Do not place an implementation-containing benchmark JAR before
baseline runtime classes on the classpath.

For the successful concurrency check, use `clients=128`, `warmupSeconds=1`,
`durationSeconds=5`, `iterations=1`, and
`-Dsoklet.e2e.socketPendingConnectionLimit=256`. JSON includes the explicit
backlog and bounded client-error categories.

```sh
java -Xms768m -Xmx768m -XX:+UseSerialGC \
  -Dsoklet.queueFootprint.capacity=256 \
  -cp target/streaming-benchmark:target/classes \
  com.soklet.internal.microhttp.StreamingQueueFootprintProbe
```

The full-queue probe accepts other positive capacities, but larger settings need
a deliberately chosen heap. It does not select production defaults from JVM
heap size or claim to qualify those settings automatically.
