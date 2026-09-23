# Benchmarks

Soklet includes JMH microbenchmarks for internal hot paths and a Soklet-only end-to-end loopback benchmark for the embedded HTTP server. These benchmarks are intended to make Soklet's own performance behavior measurable and repeatable without comparing against other HTTP libraries.

The benchmark project lives in `benchmarks/` and compiles the current `src/main/java` sources directly into the benchmark jar. This keeps JMH and its dependencies out of the published Soklet artifact and avoids requiring a local `mvn install` before benchmarking.

## Build

```shell
$ cd benchmarks
$ mvn -q clean package
```

This produces:

```text
benchmarks/target/soklet-benchmarks.jar
```

## JMH Quick Smoke Run

Use a short run to verify the benchmark jar and generated JMH metadata:

```shell
$ java -jar target/soklet-benchmarks.jar -f 1 -wi 1 -i 1 -w 250ms -r 250ms
```

Smoke runs are only for checking that benchmarks execute. Do not use them for performance claims.

JMH forked runs use local process-control sockets. If a restricted sandbox blocks loopback sockets, run the benchmark jar in a normal local shell.

## JMH Full Local Run

Use the default benchmark annotations for a local run:

```shell
$ java -jar target/soklet-benchmarks.jar -prof gc -rf json -rff target/jmh-results.json
```

The `gc` profiler reports allocation rate and garbage collection behavior, and the JSON result file is suitable for archiving with release notes or comparing between commits.

## MCP Release Comparison

The benchmark jar also contains the candidate-bound
`McpReleaseJsonJmhBenchmark`. It compares MCP JSON parsing and writing between
the exact released 3.5.1 artifact and the exact 4.0.0 candidate in isolated
class loaders, and measures Profile 1 schema compilation and evaluation on the
candidate. The isolation is intentional: neither comparison leg resolves
Soklet classes from the benchmark harness class path.

Release evidence must be produced through the registered `mcp-benchmarks`
workflow, which pins the candidate commit and toolchain, retains every raw JMH
document, and requires project-owner review of the canonical draft before
finalization. See [`benchmarks/README.md`](benchmarks/README.md) for the
producer/finalizer contract. An ad hoc local invocation remains exploratory
and is not release evidence.

## End-To-End HTTP Smoke Run

The end-to-end benchmark starts a real Soklet instance on `127.0.0.1`, resolves annotated resource methods through `ResourceMethodResolver.fromClasses(...)`, and drives the embedded HTTP server with keep-alive client sockets.

Use a short run to verify the harness:

```shell
$ java -Dsoklet.e2e.warmupSeconds=1 \
  -Dsoklet.e2e.durationSeconds=1 \
  -Dsoklet.e2e.iterations=1 \
  -Dsoklet.e2e.clients=2 \
  -Dsoklet.e2e.scenarios=plaintext \
  -cp target/soklet-benchmarks.jar com.soklet.EndToEndHttpBenchmark
```

Smoke runs are only for checking that the harness starts, sends requests, parses responses, and records results.

## End-To-End HTTP Full Local Run

Use a longer run for a local throughput and latency baseline:

```shell
$ java -Dsoklet.e2e.warmupSeconds=5 \
  -Dsoklet.e2e.durationSeconds=30 \
  -Dsoklet.e2e.iterations=3 \
  -Dsoklet.e2e.clients=32 \
  -cp target/soklet-benchmarks.jar com.soklet.EndToEndHttpBenchmark
```

The harness rotates scenario order on each iteration, prints per-iteration request throughput, error counts, and average/p50/p90/p99/max latency for each scenario, then prints a median summary across iterations. It also writes per-iteration results and summaries as JSON to `target/e2e-results.json` by default.

Useful properties:

- `soklet.e2e.scenarios`: comma-separated list of `plaintext`, `json`, `post-json`, `streaming`, `streaming-bulk`, and `streaming-paced`; defaults to the first three. The optional streaming scenarios use chunked keep-alive responses: `streaming` writes 13 bytes once, `streaming-bulk` writes 256 KiB in 64 writes of 4 KiB, and `streaming-paced` writes 32 KiB in 32 writes of 1 KiB, requesting a 1 ms sleep before each write. Actual pacing includes JVM/OS scheduling delay.
- The same selector also accepts `output-native`, `output-scalar`, and `output-mixed`. Each produces the same 64 KiB Unicode body from pre-encoded UTF-8 bytes: eight native 8 KiB writes, 65,536 scalar view writes, or mixed scalar/slice/heap/direct/read-only buffer/view writes. Exact body validation applies to all three.
- `soklet.e2e.warmupSeconds`: warmup seconds per scenario per iteration; defaults to `3`
- `soklet.e2e.durationSeconds`: measurement seconds per scenario per iteration; defaults to `10`
- `soklet.e2e.iterations`: repeated measurement iterations with rotated scenario order; defaults to `3`
- `soklet.e2e.clients`: concurrent keep-alive client sockets; defaults to `availableProcessors * 4`
- `soklet.e2e.serverConcurrency`: embedded HTTP server event-loop concurrency; defaults to `availableProcessors`
- `soklet.e2e.handlerConcurrency`: request handler concurrency; defaults to `serverConcurrency * 16`
- `soklet.e2e.socketPendingConnectionLimit`: listen backlog; defaults to `0` (the OS default). Size explicitly for bursts of simultaneous client connections, for example `256` with 128 clients.
- `soklet.e2e.metrics`: `true` to include the default metrics collector; defaults to `false`
- `soklet.e2e.output`: JSON output path; defaults to `target/e2e-results.json`
- `soklet.e2e.requireAllocationMetrics`: fail qualification if the JVM's total thread-allocation counter is unavailable; defaults to `false`. JSON records allocated bytes per successful request and validated body byte, plus GC collection count/time. These are whole-JVM measurements including colocated clients, server work, observer tails, and measurement bookkeeping; they are not server-only allocation figures. Unavailable counters remain `null`.

Only responses with the expected status and exact complete body count as successful
requests. JSON also records workload sizes, requested pacing, validated byte totals,
and body throughput. Validation runs on the measured client path; these are
end-to-end measurements, not isolated producer throughput. Eight fixed error
categories retain counts and one bounded example each, including the connection,
request-write, or response-read phase; response errors remain excluded from
successful throughput. See the
[streaming lifecycle qualification](docs/streaming-lifecycle-qualification.md)
for the baseline comparison and memory-probe commands.

The current harness uses the one-argument streaming writer API. It cannot run
against a historical two-argument runtime by swapping only the runtime classpath;
historical comparisons require a compatible harness for each ABI. The current
equal-body output scenarios compare API paths within the same runtime.
The [HTTP streaming API qualification](docs/streaming-api-qualification-2026-09-22.md)
preserves the earlier output-path throughput/allocation and full-capacity heap
results. Its UTF-8 helper measurements predate removal of `writeUtf8`; rerun the
remaining workloads before treating those results as current qualification.

## Startup And Memory Footprint Run

Measure cold-start latency and settled memory footprint for a minimal one-route Soklet HTTP application:

```shell
$ java -cp target/soklet-benchmarks.jar com.soklet.StartupAndMemoryBenchmark
```

Each iteration forks a fresh JVM — no JIT or class-data carryover between iterations (the JIT itself remains active; it is not suppressed) — starts the server, serves a real `GET /ping` over a loopback socket, then idles while the parent samples its resident set size from the OS. The harness reports mean ± sample stddev with min/max over all iterations and writes JSON to `target/startup-results.json` by default.

Measured per iteration:

- `startedMillis`: JVM start to `Soklet#start()` returning, via `RuntimeMXBean#getUptime()` (JVM-internal time; OS process fork/exec cost before JVM initialization is not included)
- `firstResponseMillis`: JVM start to the first HTTP response fully read off a real socket
- `usedHeapBytes`: used heap after startup and serving one `GET /ping` request, following two `System.gc()` passes (approximate by nature; includes any live request-serving infrastructure)
- `rssBytes`: median resident set size sampled via `ps -o rss=` over the settle window — the median is robust against a transient GC spike mid-window (macOS/Linux; reported as unavailable elsewhere)
- `threadCount`: live JVM threads at rest

Useful properties:

- `soklet.startup.iterations`: cold-JVM iterations; defaults to `5`
- `soklet.startup.settleMillis`: idle window for RSS sampling per iteration; defaults to `1000`
- `soklet.startup.metrics`: `true` to include the default metrics collector in the child; defaults to `false`
- `soklet.startup.childJvmArgs`: extra JVM arguments for the child (e.g. `-Xmx64m`), space-separated; arguments containing embedded spaces are not supported
- `soklet.startup.output`: JSON output path; defaults to `target/startup-results.json`

Caveats: RSS includes the whole JVM (heap, metaspace, code cache, GC structures, thread stacks), so it is the honest "what does ops see" number and is expected to dwarf used heap. Post-GC heap measurement is a convention, not an exact science. Cold-start numbers are deliberately unwarmed; do not compare them against steady-state throughput runs.

## Scope

Current benchmarks cover:

- microhttp request parsing and tokenizer compaction for keep-alive and pipelined requests
- HTTP request header conversion from microhttp's parsed representation into Soklet's public header map
- public `Request` construction from embedded HTTP requests, including header, query, and form-access variants
- `MarshaledResponse` conversion to the embedded HTTP response representation for static and dynamic byte-array, cookie, file, file-channel, and byte-buffer bodies
- Server-Sent Event event/comment formatting, UTF-8 payload serialization, and comment fan-out serialization strategy
- MCP JSON parse/write comparison for the exact 3.5.1 baseline and 4.0.0 candidate, plus candidate Profile 1 schema compile/evaluate paths
- end-to-end embedded HTTP handling over loopback for small plaintext, JSON, and POST JSON requests
- cold-JVM startup latency (to started and to first response served) and settled memory footprint (post-GC heap, OS-level RSS, thread count) for a minimal application

The JMH benchmarks can support claims about internal hot-path timing and allocation behavior. The end-to-end loopback benchmark can support claims about whole-process embedded HTTP behavior on one machine: request parsing, Soklet routing, handler invocation, response marshaling, event-loop scheduling, and socket I/O.

Loopback benchmarks do not prove internet-facing latency, TLS overhead, load balancer behavior, or multi-host network performance. Treat them as a stronger local baseline, not as a deployment benchmark.

## Reporting Results

When sharing benchmark results, include:

- commit SHA
- baseline commit SHA, when reporting a before/after comparison
- Java vendor and version
- OS and CPU
- exact benchmark command
- JMH JSON output, when reporting JMH results
- exact baseline and candidate artifact identities when reporting MCP release-benchmark results
- end-to-end JSON output, when reporting HTTP loopback results
- startup JSON output, when reporting startup/memory results

For local regression tracking, keep the raw JSON files with a short note that records the environment, commands, and commit SHAs:

```text
Baseline: v3.4.0 (ba8ed98)
Candidate: <sha>
Java: <vendor> <version>
OS/CPU: <os>, <cpu>

JMH: <scenario> <score> (<+/- percent vs baseline>), allocation <B/op> (<+/- percent>)
E2E: <scenario>, <clients> clients, throughput <rps> (<+/- percent>), p99 <nanos> (<+/- percent>)
Startup: started <ms> (<+/- percent>), first response <ms> (<+/- percent>), RSS <MB> (<+/- percent>)
```

Prefer allocation and relative before/after changes over broad performance claims. Only compare numbers produced on the same machine, operating system, JDK, and benchmark command. Whole-server throughput claims should cite the end-to-end benchmark scenario, server settings, client count, and latency percentiles.

## Public Release Baselines

Public release baselines should be produced only from a stable managed runner, such as a dedicated EC2 instance type with a pinned AMI, JDK, JVM flags, benchmark commands, and machine-quieting procedure. Ad hoc laptop numbers are useful for local regression checks, but should not be published as release evidence.

Soklet 3.5.0 introduced the local benchmark harnesses. Soklet 4.0.0 adds the
candidate-bound MCP comparison and its reviewed evidence path; neither release
claims public benchmark numbers without results from the stable managed runner.

## SSE connection-lifetime qualification

The standalone [SSE connection harness](docs/sse-connection-qualification.md)
checks paced delivery to 256 live clients, overload rejection, subscription churn,
and physical teardown. The separate [SSE heap probe](docs/sse-lifecycle-footprint-probe.md)
measures idle connections, distinct full queues, retained terminated runtimes, and
released references. Both compile with Java 17 and require Java 21 or newer for
live SSE. Their standalone commands avoid the recorded MCP benchmark-module
compile blocker. See the [milestone 5b report](docs/streaming-api-milestone-5b.md)
for the tested workloads, selected defaults, raw evidence, and limitations.
