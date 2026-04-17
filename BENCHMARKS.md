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

## Scope

Current benchmarks cover:

- microhttp request parsing and tokenizer compaction for keep-alive and pipelined requests
- HTTP request header conversion from microhttp's parsed representation into Soklet's public header map
- Server-Sent Event event/comment formatting, UTF-8 payload serialization, and comment fan-out serialization strategy
- end-to-end embedded HTTP handling over loopback for small plaintext, JSON, and POST JSON requests

The JMH benchmarks can support claims about internal hot-path timing and allocation behavior. The end-to-end loopback benchmark can support claims about whole-process embedded HTTP behavior on one machine: request parsing, Soklet routing, handler invocation, response marshaling, event-loop scheduling, and socket I/O.

Loopback benchmarks do not prove internet-facing latency, TLS overhead, load balancer behavior, or multi-host network performance. Treat them as a stronger local baseline, not as a deployment benchmark.

## Reporting Results

When sharing benchmark results, include:

- commit SHA
- Java vendor and version
- OS and CPU
- exact benchmark command
- JMH JSON output, when reporting JMH results
- end-to-end JSON output, when reporting HTTP loopback results

Prefer allocation and relative before/after changes over broad claims like "Soklet is fast." Whole-server throughput claims should cite the end-to-end benchmark scenario, server settings, client count, and latency percentiles.
