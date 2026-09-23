# Milestone 5b — SSE defaults qualification

**September 23 amendment:** these measurements qualify the superseded
per-client ownership/callback candidate. They do not qualify the final
broadcaster-based SSE model or establish callback-concurrency and cleanup-timeout
settings for its public builder. Re-run connection, broadcaster, and footprint
checks against the final API; preserve the original receipts below.

Completed 2026-09-22 in the isolated streaming worktree based on `c5d02871`.
Milestone 5 is complete for the selected live/simulated SSE ownership contract.
Keep **256 lifecycle slots, four terminal callback workers, and five seconds of
cleanup supervision** as the SSE defaults. No public signatures or production
runtime behavior changed in this slice; the independent SSE measurements now
support the values introduced provisionally in milestone 5a.

This is qualification of a conservative starting configuration on the documented
workloads, not a claim that these values maximize SSE throughput or suit every
application. Milestone 6 consumer/release qualification remains open.

## Default decisions

| Setting | Decision and evidence |
| --- | --- |
| `streamingLifecycleCapacity` | Keep 256. All 256 clients remain live beyond cleanup grace, deliver ordered events, own subscriptions, and recover their slots through churn. The next handshake is rejected before initialization. Idle and full-queue heap are measured separately below. |
| `streamingCallbackConcurrency` | Keep four. At saturation exactly four terminal jobs execute and 508 wait; transport termination proceeds while these jobs are blocked. Cooperative subscriptions retire through repeated churn and final shutdown. More workers are not shown necessary by this workload; this is not a fairness or arbitrary-callback latency guarantee. |
| `streamingCleanupTimeout` | Keep five seconds. The real default expires under blocked cleanup, reports overdue work, preserves the first termination outcome, and retains reservations until physical completion. It supervises termination; healthy connections outlive it, and it cannot force user `close()` to return. |

The lifecycle cap is independent of the older 8,192 transport connection limit.
With both defaults, lifecycle admission is the effective 256-connection limit;
pending initialization and terminated-but-unfinished work also consume it.
Applications needing more clients must size lifecycle capacity along with payload
queues, subscriptions, and cleanup behavior. Four terminal workers do not bound
provider event callback concurrency or the transport's request/connection workers.

The existing 128-slot queue bounds event count, not bytes. Neither lifecycle
capacity nor this queue limit bounds arbitrary event sizes, application resource
graphs, or the number of resources/listeners acquired within a connection. The
measurements below must not be presented as a total heap cap.

## Saturation and regression tests

[200 tests pass on Corretto 21.0.11.10.1](streaming-api-evidence/milestone-5b-2026-09-22/java21-tests.json)
across 12 suites, with zero failures, errors, or skips. The
[raw Maven log](streaming-api-evidence/milestone-5b-2026-09-22/java21-tests.log)
includes the existing live SSE, shared lifecycle/coordinator, public settings,
contracts, and simulator suites, plus two new `SseLifecycleSaturationTests`:

- At the unmodified 256/four/five-second lifecycle defaults, the 257th request
  gets 503 without entering its initializer. Resetting all clients and writing
  to observe those resets publishes exactly 512 terminal jobs. Four execute and
  508 remain queued while both notification and resource cleanup are held.
  All 256 reservations survive actual cleanup deadline expiry; all 256 deadline
  diagnostics arrive. Replayed outcomes remain the identical
  `CLIENT_DISCONNECTED` objects. Admission remains closed to new work until the
  jobs are released, then resource/listener counts reach exactly 256 and a new
  connection is admitted.
- A provider callback remains entered while subscription `close()` waits for it.
  Termination notification still runs. A short enclosing shutdown budget returns
  an incomplete result with `STREAM` and `CALLBACK` residual evidence instead of
  hanging or releasing the reservation. The callback's late event is rejected;
  releasing it lets the resource close once and the coordinator physically retire.

Both new standalone harnesses compile under the actual Java 17 compiler with
`--release 17`. The load parser's ten positive/negative self-checks pass on both
[Java 17](streaming-api-evidence/milestone-5b-2026-09-22/harness-java17-self-test.log)
and [Java 21](streaming-api-evidence/milestone-5b-2026-09-22/harness-java21-self-test.log).
Live SSE requires Java 21; its live tests and measurements ran on that runtime.
The 453-test Java 17 production/simulator/MCP result remains the previously
recorded milestone 5a evidence, not a new test run claimed by this slice.

## Delivery, churn, and teardown

The new [connection qualification harness](sse-connection-qualification.md) runs
real sockets, a minimal owned subscription per client, and validating clients in
one JVM. It leaves lifecycle, queue, heartbeat, and transport timeout defaults
unchanged. Each of three fresh JVMs uses five seconds of warmup, 60 seconds of
measured delivery, 256 clients, 20 rounds/second, and a 1,024-byte ASCII application
payload, plus event type/ID/timestamp framing. Each then performs ten cycles that
replace 64 clients and finally shuts down with the remaining 256 clients live.
Trials use Corretto 21, a fixed 512 MiB Java heap, and the JVM's default collector.
No tests or other qualification workloads run concurrently with these trials.

| Trial | Validated measured events | Events/second | p99 latency upper bucket | Maximum observed latency | Final shutdown |
| --- | ---: | ---: | ---: | ---: | ---: |
| [1](streaming-api-evidence/milestone-5b-2026-09-22/load-1024-run1.log) | 307,200 | 5,118.169 | 5 ms | 9.916 ms | 18.386 ms |
| [2](streaming-api-evidence/milestone-5b-2026-09-22/load-1024-run2.log) | 307,200 | 5,118.561 | 10 ms | 18.816 ms | 21.427 ms |
| [3](streaming-api-evidence/milestone-5b-2026-09-22/load-1024-run3.log) | 307,200 | 5,118.419 | 5 ms | 16.396 ms | 25.804 ms |

All **921,600 measured events** pass exact per-client sequence, full-frame, event
type, and payload validation. Including warmup and churn probes, 1,011,840 events
are validated. The trials replace 1,920 clients and finish 2,688 owned lifetimes,
each with exactly one resource close and termination notification. Every trial
ends with zero reservations, retained work, callbacks, diagnostics, or live
subscriptions, and a terminated coordinator and client-reader executor. The
257th handshake is rechecked at each full-population phase. There are no
unexpected healthy terminations or internal SSE error diagnostics.

This is a paced 5,120-event/second target, not a measured maximum throughput.
Latency includes sequential fan-out and colocated client parsing. Whole-JVM CPU
and GC figures in the raw records include both server and clients. These
one-minute trials show healthy connections outliving cleanup grace; continuous
delivery does not exercise idle-heartbeat emission, and this is not a
long-duration leak soak. Churn detects client resets
through subsequent writes and does not qualify passive idle disconnect latency.

## Retained Java heap

The separate [footprint probe](sse-lifecycle-footprint-probe.md) uses real SSE
connections and the unmodified lifecycle/queue defaults. Six fresh JVM trials
cover three repetitions each of 64-byte and 1,024-byte distinct ASCII data values,
with a fixed 512 MiB heap and Serial GC. Heartbeats are set to one hour and the
initial verification heartbeat is disabled to stabilize queue contents. Two
small completed lifecycles precede each measured lifecycle.

At the full-queue sample, every writer is held after taking one event and each
connection has exactly 128 more events queued: **32,768 queued events and 256
in-flight events**, with 33,024 distinct event objects and data Strings. Queued
serialized arrays, order, contents, and non-sharing are inspected directly. The
in-flight serialized size is inferred from the same serialization path and
reported separately. Samples take the minimum heap after three requested GCs.

| Java heap measurement, range across three JVMs | 64-byte data | 1,024-byte data |
| --- | ---: | ---: |
| Idle 256-client increment over the empty started server | 3.918–3.983 MiB | 3.918–3.931 MiB |
| Full queues plus one in-flight event/client, increment over idle | 9.135–9.147 MiB | 69.520–69.534 MiB |
| Full active runtime, increment over warmed baseline | 13&#46;630–13&#46;683 MiB | 73.981–73.991 MiB |
| Terminated runtime and handles retained, increment over warmed baseline | 2.094–2.099 MiB | 2.092–2.102 MiB |
| All probe runtime references released, increment over warmed baseline | 0.025–0.032 MiB | 0.023–0.034 MiB |

For the 1 KiB case, absolute full-state whole-JVM heap is 76.653–76.662 MiB;
33,816,576 bytes are directly measured queued serialized payload and another
264,192 in-flight serialized bytes are inferred. Event/String/queue objects,
backing storage, and runtime state account for the rest of the increment.

Every trial verifies 256 once-only resource closes and notifications, removal of
all connection registrations, no retained application queue payloads, zero
reservations/retained work/callbacks, terminated captured SSE executors and
coordinator, and exit of all 256 explicitly tracked virtual connection writers.
The retained terminal sample deliberately includes the whole stopped runtime,
client sockets, connection objects, and thread references; it is not the isolated
size of public handles. Dropping those references brings the sampled heap close
to the warmed baseline, with small remaining VM/cache differences. This is not a
proof that all possible applications are leak-free.

The results include both loopback endpoints' Java objects and a minimal test
subscription. They exclude RSS, native stacks, kernel socket buffers, and
application-specific resource costs; no allocation-rate or total-memory bound is
claimed. Platform-thread counters cannot establish virtual-thread retirement,
which is why the probe tracks and verifies the actual connection writer threads.
See the [machine-readable summary](streaming-api-evidence/milestone-5b-2026-09-22/summary.json)
for all six samples and their raw-log names.

## Evidence and next milestone

[Environment and class identity](streaming-api-evidence/milestone-5b-2026-09-22/environment.json),
per-run command/exit/hash receipts, raw logs, the aggregated summary, and the
[source identity](streaming-api-evidence/milestone-5b-2026-09-22/source-identity.json)
are retained in the milestone 5b evidence directory. The preliminary smoke logs
are explicitly excluded from the measured trial totals; their older metric/stage
labels predate the harness review corrections. Java executables are explicit in
the receipts; `javaHomeEnvironment` records the inherited environment, which need
not select the executable used.

The production/API source files recorded for milestone 5a are unchanged. There
is no additional public API delta and no repeated package/API-freeze claim in
this slice. Preserve the earlier 712-record candidate inventory, 57 unexpected /
four missing aggregate compatibility records, unassigned
`ResourcePathDeclaration.Component` ownership entry, historical MCP signatures,
and candidate/privacy/downstream release work as explicit milestone 6 concerns.
The stale `McpSubscriptionRenewalBenchmark.addResource` compile blocker remains
separate; both new harnesses build independently without introducing Java 21
source symbols into the Java 17 benchmark source set.

Next is milestone 6: consumer migrations and compiled examples, current-candidate
API/lifecycle/privacy evidence, affected core/MCP/downstream validation, and
release-gate reconciliation. Passive HTTP disconnect detection remains the
independent milestone 4 track and has not become an API milestone prerequisite.
