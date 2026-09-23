# SSE connection qualification harness

The current `SseConnectionQualification` drives the built-in SSE server and
validating loopback clients in one JVM. A bounded, synchronous initializer runs
once per client; ongoing events are published through `SseBroadcaster` for the
same concrete resource path. This tests the final 4.0 initializer/broadcaster
model, 256-slot connection admission, exact delivery, churn recovery, and
physical teardown. It is a paced workload, not a maximum-throughput benchmark
or a long-term soak. The September 22
[milestone 5b measurements](streaming-api-milestone-5b.md) used the superseded
per-client subscription/callback design and must not be presented as results of
this harness.
The first run of the final initializer/broadcaster candidate is recorded in
[the September 23 integration checks](sse-final-candidate-checks-2026-09-23.md).

## Reproduction

Build the current runtime first (`mvn -DskipTests package`, using the repository's
usual signing/Javadoc switches when appropriate). The harness compiles with Java
17; live SSE requires Java 21 or newer. Compile separately from the JMH module:

```sh
mkdir -p /tmp/soklet-sse-qualification
javac --release 17 -proc:none -parameters -cp target/classes \
  -d /tmp/soklet-sse-qualification \
  benchmarks/src/main/java/com/soklet/SseConnectionQualification.java
java -cp target/classes:/tmp/soklet-sse-qualification \
  com.soklet.SseConnectionQualification --self-test
java -Xms512m -Xmx512m -cp target/classes:/tmp/soklet-sse-qualification \
  com.soklet.SseConnectionQualification
```

Run from the repository root with loopback sockets allowed. Run each trial in a
fresh JVM without another qualification workload running concurrently. Record
the JDK, JVM arguments, source/binary identity, command, output, and exit status.
The standalone command avoids the separately recorded stale MCP benchmark source
that prevents building the complete benchmark module.

Defaults are five seconds of warmup, 60 seconds of measured delivery, 20 rounds
per second, 1,024 ASCII payload bytes, and ten churn cycles replacing 64 clients
each. A shorter correctness smoke uses system properties:

```sh
java -Xms512m -Xmx512m -Dsoklet.sse.seconds=2 \
  -Dsoklet.sse.warmupSeconds=1 -Dsoklet.sse.churnCycles=2 \
  -cp target/classes:/tmp/soklet-sse-qualification \
  com.soklet.SseConnectionQualification
```

All properties use the `soklet.sse.` prefix: `seconds`, `warmupSeconds`,
`roundsPerSecond`, `payloadBytes`, `churnCycles`, and `churnClients`. The
population stays at 256. The harness uses the built-in defaults of 256 SSE
lifecycle slots, 128 application queue elements per connection, and the usual
heartbeat and transport timeouts. It asserts the two capacity defaults. There
are no SSE-specific callback-concurrency or cleanup-timeout settings.

## What a passing run establishes

Every client validates its exact next event ID, event type, full payload, and
complete frame. Heartbeat comments are accepted. Delivery to all clients
completes before each phase boundary. Healthy clients survive warmup and
measurement. The 257th request gets HTTP 503 without entering its initializer.

During churn, selected clients reset their sockets; continuing broadcaster
probes make those disconnects observable to the transport. The harness waits for
physical connection and reservation retirement before admitting replacements.
Surviving clients retain their sequence; new clients start at the next
published ID. Every replacement recovers the 256-client population, which again
rejects the next admission before initialization.

Shutdown starts after the last delivered frame. A passing run requires a
complete shutdown result, finished client readers, a terminated coordinator,
zero reservations and retained work, zero SSE internal diagnostics, and no
remaining server connections. The self-test covers parser and sequence/payload
rejection without sockets.

The `RESULT` JSON line is emitted only after all assertions pass and labels
`deliveryMode` as `broadcaster`. At the default pace, 20 rounds times 256 clients
is 5,120 expected client events per second; this is a selected input rate, not
discovered server capacity. Latency runs from publication to complete client
parsing, including fan-out and loopback-client costs. CPU and GC figures cover
the whole JVM. No allocation, process RSS, native-memory, or universal heap
bound is claimed. Use the separate [footprint probe](sse-lifecycle-footprint-probe.md)
for controlled idle and full-queue Java-heap observations.
