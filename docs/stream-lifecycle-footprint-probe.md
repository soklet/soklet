# Exploratory streaming lifecycle footprint

`StreamLifecycleFootprintProbe` measures the coordinator in isolation with the
provisional settings of 1,024 lifetime slots, four callback workers, and a
five-second cleanup grace. These results help describe overhead; they do not
qualify those defaults or constitute release benchmark evidence.

The probe warms the same paths through five complete coordinator lifecycles,
then records six stages in a fresh JVM. At each stage it retains the intended
objects through static strong references and samples the minimum used Java heap
after three explicit GCs, separated by 50 milliseconds. Cancelation publishes two
empty terminal jobs per reservation, waits for physical drain, and drops the
probe's reservation references. The coordinator remains accepting until the next
stage explicitly stops it.

Three separate Java 17.0.20.1 JVMs on macOS/aarch64 used
`-Xms128m -Xmx128m -XX:+UseSerialGC`. The measured coordinator and all recursively
declared classes had combined SHA-256
`f69f413e7a8dd0bc1aaf44109643f47aca8f2ea913d639865edd77542ec0482d`.
The hash incorporates each sorted binary class name, a NUL byte, and its loaded
class-file bytes. It identifies the compiled snapshot actually measured, even
while this development worktree is changing.

| Stage | Approximate heap above warmed baseline | Owned live threads |
| --- | ---: | ---: |
| Empty, accepting coordinator | 13,712 bytes / 13.4 KiB | 0 |
| 1,024 idle reservations | 181,680–182,072 bytes / 177.4–177.8 KiB | 0 |
| All cancelations drained, still accepting | 186,792–188,312 bytes / 182.4–183.9 KiB | 4 callback + 1 supervisor |
| Stopped coordinator still retained by probe | 23,912–25,488 bytes / 23.4–24.9 KiB | 0 |
| Probe drops coordinator reference | 1,024–1,704 bytes / 1.0–1.7 KiB | 0 |

The idle population increased measured heap by 167,968–168,360 bytes over the empty
coordinator, approximately 164.0–164.4 bytes per reservation. This includes
the probe's 1,024-element reference array and the coordinator's membership storage.
The idle stages start no workers because executor threads are created lazily.
Normal cancelation exercised all four callback workers and the supervisor; it
did not start a diagnostic worker. Every run reported zero diagnostics and zero
owned live threads after termination.

[Raw output from all three JVMs](stream-lifecycle-footprint-2026-09-21.txt) preserves
the exact bytes, thread counts, VM arguments, class location, and bytecode hash.
After production bytecode changes, rerun the probe before attributing these
measurements to the changed implementation.

## Reproduce without Maven

From the repository root, use already compiled `target/classes` and locally
available annotation JARs. The following paths match the machine used for this
measurement; adjust them for another machine. No dependencies are fetched.

```sh
probe_jdk=/Users/agents/Java/amazon-corretto-17.jdk/Contents/Home
probe_output=/private/tmp/soklet-streaming-footprint-2026-09-21
probe_dependencies=target/classes:/Users/agents/.m2/repository/org/jspecify/jspecify/1.0.1/jspecify-1.0.1.jar:/Users/agents/.m2/repository/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar
mkdir -p "$probe_output"
"$probe_jdk/bin/javac" --release 17 -proc:none \
  -cp "$probe_dependencies" -d "$probe_output" \
  benchmarks/src/main/java/com/soklet/StreamLifecycleFootprintProbe.java
"$probe_jdk/bin/java" -Xms128m -Xmx128m -XX:+UseSerialGC \
  -cp "$probe_output:$probe_dependencies" com.soklet.StreamLifecycleFootprintProbe
```

Repeat the final command in separate JVMs. A normal run takes about two seconds;
each drain and worker-termination wait has a five-second limit. The probe releases
retained admission handles and stops the coordinator on failure.

## Limits

This measures approximate post-GC Java heap, not retained size from a heap graph,
allocation rate, RSS, native thread stacks, or total HTTP server memory. Explicit
GC is a request; VM bookkeeping, compilation, and class metadata can introduce
small differences between stages, including the small residual after all probe
references are released. These numbers do not establish a leak.

Reservations are idle synthetic handles with no producer executors, transport
connections, response buffers, application payloads, or blocking callbacks. The
probe measures drained callback infrastructure, not a full terminal-work backlog
or a stalled application. Workload sizing, overloaded service behavior, and memory
under real long-running streams require separate measurement.
