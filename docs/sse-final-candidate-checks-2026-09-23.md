# Final SSE initializer candidate: integration checks (2026-09-23)

These checks exercise the 4.0 API in which `SseClientInitializer` runs once for
bounded setup or catch-up, `SseUnicaster` is usable only during that callback,
and `SseBroadcaster` carries ongoing events. They supersede the September 22
SSE ownership-candidate measurements. The source tree is the uncommitted
`streaming-integration-2026-09-22` worktree; the rebuilt candidate JAR has
SHA-256 `9a1efc5bb90e756f86844b98cb03d27ebfa2a69a3e3c4747b32ad45869d7c4eb`.

## Connection workload

One fresh-JVM default run of `SseConnectionQualification` on Amazon Corretto
21.0.11 passed. It used 256 clients, a 5-second warmup, a 60-second measured
window, 20 broadcaster rounds per second, and 1,024-byte data payloads. The
result was 307,200 exactly validated measured client events in 60.018 seconds
(5,118 client events/second), followed by ten churn cycles replacing 640
clients. The 257th connection was rejected before its initializer ran. Shutdown
left zero reservations, zero retained work, and zero SSE diagnostics. Its
reported p99 latency bucket ended at 5 ms and maximum observed latency was
7.864 ms. This is a paced correctness/load trial, not maximum throughput or a
long-term soak. The [raw output](streaming-api-evidence/sse-broadcaster-default-2026-09-23.log)
includes every phase and latency bucket.

```text
RESULT {"passed":true,"java":"21.0.11","connections":256,"deliveryMode":"broadcaster","queueCapacity":128,"payloadBytes":1024,"roundsPerSecond":20,"warmupRounds":100,"measuredRounds":1200,"measuredEvents":307200,"allValidatedEvents":337280,"measuredSeconds":60.017702,"eventsPerSecond":5118.490,"establishmentMillis":207.173,"p50LatencyUpperMicros":2000,"p95LatencyUpperMicros":5000,"p99LatencyUpperMicros":5000,"maximumLatencyMicros":7864.041,"maximumPacingDelayMillis":5.073,"wholeJvmCpuSeconds":15.398759,"wholeJvmGcCount":5,"wholeJvmGcMillis":5,"churnCycles":10,"replacedClients":640,"churnSeconds":0.490897,"shutdownMillis":19.423,"initializers":896,"maximumSampledReservations":256,"remainingReservations":0,"remainingWork":0,"diagnosticErrors":0}
```

## Full-queue footprint correctness

Three fresh-JVM runs per payload size of `SseLifecycleFootprintProbe` passed on
Corretto 21.0.11 with `-Xms512m -Xmx512m -XX:+UseSerialGC`. All used the
unmodified 256 lifecycle slots and 128 queue elements per connection. The
probe/runtime class subset hash was
`232a65ff4dfc006dc0a98ad96b2641373372d69c12c437d71e755799e1736b8e`.

| Data bytes/event | Idle increment over started (range) | Full queue increment over idle (range) | Queued serialized bytes (each run) | After all probe references released, delta from warmed baseline (range) |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 3,832,984–4,036,520 B | 9,638,960–9,659,040 B | 2,359,296 B | 26,704–37,112 B |
| 1,024 | 3,829,688–3,950,704 B | 72,911,264–72,956,344 B | 33,816,576 B | 20,224–35,184 B |

All six runs verified 32,768 queued payload identities plus 256 in-flight events,
HTTP 503 before the 257th initializer, connection unregistration, queue
release, coordinator/executor termination, and writer exit. The raw records
include every controlled heap sample: 64-byte trials
[1](streaming-api-evidence/sse-queued-64-2026-09-23.jsonl),
[2](streaming-api-evidence/sse-queued-64-2026-09-23-r2.jsonl),
[3](streaming-api-evidence/sse-queued-64-2026-09-23-r3.jsonl); 1,024-byte trials
[1](streaming-api-evidence/sse-queued-1024-2026-09-23.jsonl),
[2](streaming-api-evidence/sse-queued-1024-2026-09-23-r2.jsonl),
[3](streaming-api-evidence/sse-queued-1024-2026-09-23-r3.jsonl). These are
approximate whole-JVM post-GC heap observations, including the loopback clients
and probe objects. Three trials describe this controlled setup but do not
establish a universal heap bound or an allocation rate. The queue limits
element count, not event bytes.
