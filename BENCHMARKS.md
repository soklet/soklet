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

- `soklet.e2e.scenarios`: comma-separated list of `plaintext`, `json`, and `post-json`; defaults to all three
- `soklet.e2e.warmupSeconds`: warmup seconds per scenario per iteration; defaults to `3`
- `soklet.e2e.durationSeconds`: measurement seconds per scenario per iteration; defaults to `10`
- `soklet.e2e.iterations`: repeated measurement iterations with rotated scenario order; defaults to `3`
- `soklet.e2e.clients`: concurrent keep-alive client sockets; defaults to `availableProcessors * 4`
- `soklet.e2e.serverConcurrency`: embedded HTTP server event-loop concurrency; defaults to `availableProcessors`
- `soklet.e2e.handlerConcurrency`: request handler concurrency; defaults to `serverConcurrency * 16`
- `soklet.e2e.metrics`: `true` to include the default metrics collector; defaults to `false`
- `soklet.e2e.output`: JSON output path; defaults to `target/e2e-results.json`

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

Soklet 3.5.0 adds benchmark harnesses for local measurement and future release baselines. It does not publish public comparative benchmark numbers.
