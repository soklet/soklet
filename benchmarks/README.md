# MCP release benchmark harness

The ordinary benchmark module still builds and runs the existing exploratory
JMH benchmarks. Its release-specific path additionally compares the exact
released `com.soklet:soklet:3.5.1` JAR with the exact candidate
`com.soklet:soklet:4.0.0` JAR. Each implementation is loaded in an isolated
class loader; neither comparison leg resolves Soklet classes from the harness
class path.

The candidate workflow performs the long-running measurements and writes a
canonical draft plus every raw JMH result. It intentionally does not call the
draft release evidence. The successful workflow log prints the SHA-256 of the
exact canonical draft. A project owner must inspect the retained artifact,
record that exact digest in the durable review, and supply both the reviewed
digest and durable sign-off reference during finalization:

```text
node scripts/produce-release-benchmarks.mjs finalize \
  --candidate-root /absolute/path/to/clean-candidate \
  --work-root /absolute/path/to/downloaded-benchmark-work \
  --evidence-root /absolute/path/to/new-evidence-directory \
  --bundle-output /absolute/path/to/new-mcp-benchmarks-bundle.json \
  --reviewed-draft-sha256 exact-lowercase-sha256-from-review \
  --signoff-reference 'review-system:signoff/456#sha256=exact-lowercase-sha256-from-review'
```

Finalization first requires the downloaded canonical draft to match the
reviewed digest. It then re-derives the frozen candidate identity, re-parses
every retained JMH JSON file, reconstructs all normalized scores, verifies the
reviewed log, and passes the two registered evidence roles through the
canonical release bundle builder. The retained review record includes that
draft digest, and the durable sign-off reference must end with the same digest.
The accepted results role retains the exact reviewed draft and every canonical
raw JMH document. The shared bundle validator independently derives the JMH
scores from the complete raw sample arrays, enforces the exact Profile 1
compile/evaluate mapping, and proves that the log contains each retained raw
document. Changing the draft, raw data, inline score, mapping, or sign-off
binding therefore fails closed even when the generic bundle builder is called
directly. A JSON parse or write ratio below the registered `0.90` threshold
also fails closed; the current contract does not permit the producer to
self-authorize a regression.

## P1b subscription-renewal capacity evidence

`McpSubscriptionRenewalBenchmark` is a standalone real-socket benchmark, not a
JMH microbenchmark or a JUnit test. Its default evidence profile opens 1,000
simultaneous subscription streams and keeps every stream open through two
complete authorization-renewal cycles. The server builder does not override
the application executor, its 32-handler/128-entry bounded admission queue,
the five-second queue-inclusive authorization timeout, or the one-minute
maximum authorization duration.

Build and run the strict evidence profile from `benchmarks/`:

```text
mvn -q clean package
candidate_status="$(git -C .. status --porcelain --untracked-files=all)"
if test -n "$candidate_status"; then
  echo "The candidate checkout must be clean." >&2
  exit 1
fi
candidate_identity="$(git -C .. rev-parse --verify 'HEAD^{commit}')"
java -Dsoklet.subscriptionRenewal.candidate="$candidate_identity" \
  -cp target/soklet-benchmarks.jar \
  com.soklet.McpSubscriptionRenewalBenchmark
```

The run takes a little over one minute after connection establishment and
writes `target/mcp-subscription-renewal-results.json`. The JSON retains the
environment, exact configuration, every raw callback sample, per-cycle
callback throughput, queue-inclusive wait, callback latency, all authorization
outcomes, capacity rejection, and pre-teardown subscription closure counts.
Queue-inclusive wait is derived from the fixed deadline in each public
authorization context, so it includes scheduling and bounded-queue delay before
callback entry. The default acceptance contract requires:

- all 1,000 streams to remain active through two renewals (3,000 successful
  callbacks including initial authorization);
- zero authorization timeouts, denials, failures, stale results, bounded
  capacity rejections, or unintended stream closures;
- p99 queue-inclusive wait to remain below 1.25 seconds and maximum concurrent
  authorization callbacks to remain at or below 24; and
- the effective application-handler defaults to remain 32/128.

The command must run from a clean checkout and record its full 40-hex commit SHA
for a default-profile run to pass. A dirty-tree label or abbreviated SHA remains
useful for provisional measurements, but is explicitly not evidence.
The output is P1b package evidence and does not become final release evidence
merely because the local acceptance checks pass; retain it with the reviewed
candidate and runner record.

For a short harness smoke check, reduce the population and authorization
duration. Any duration override marks the result as non-evidence:

```text
java -Dsoklet.subscriptionRenewal.subscriptions=32 \
  -Dsoklet.subscriptionRenewal.renewalCycles=2 \
  -Dsoklet.subscriptionRenewal.clientOpenConcurrency=8 \
  -Dsoklet.subscriptionRenewal.authorizationDurationSeconds=12 \
  -Dsoklet.subscriptionRenewal.output=target/mcp-subscription-renewal-smoke.json \
  -cp target/soklet-benchmarks.jar \
  com.soklet.McpSubscriptionRenewalBenchmark
```
