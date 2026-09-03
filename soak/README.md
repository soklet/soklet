# Soklet Soak Tests

Resource-leak probes for live loopback transports and the off-network MCP
simulator.

The harness loads one immutable workload profile from
`src/test/resources/com/soklet/soak-profiles/`. Run the short `smoke` profile
(also the local default) with:

```sh
SOKLET_SOAK_PROFILE=smoke mvn -f soak/pom.xml clean test
node scripts/verify-soak-evidence.mjs smoke
```

Run the scheduled profile explicitly with:

```sh
SOKLET_SOAK_PROFILE=nightly mvn -f soak/pom.xml clean test
node scripts/verify-soak-evidence.mjs nightly
```

Run the release-duration profile at the exact release commit with:

```sh
SOKLET_SOAK_PROFILE=release mvn -B -ntp -f soak/pom.xml clean test
node scripts/verify-soak-evidence.mjs release
```

The soak module compiles Soklet's main sources directly and keeps soak-only test
dependencies out of the published artifact. It therefore validates the source
at the checked-out commit; it does not claim to consume a prebuilt candidate
JAR. The smoke profile is suitable for local checks and pull-request/push leak
protection. The nightly profile increases operation counts, concurrency,
timeouts, shutdown cycles, and explicit resource-delta thresholds. The release
profile fixes the longest bounded workload used for release evidence: 64 HTTP
clients, 16 MCP clients, 32 SSE clients, eight MCP shutdown cycles, five-minute
HTTP and SSE workload timeouts, and a ten-minute MCP workload timeout. Its final
resource-delta ceilings are 12 file descriptors, 96 MiB of heap, and 48 threads
for HTTP, and 16 file descriptors, 128 MiB of heap, and 96 threads for MCP and
SSE. The canonical configuration in the report records all operation counts,
queue and subscription bounds, timing, and resource thresholds byte-for-byte.

Every profile probes file-descriptor, thread, heap, and active-gauge leaks by
driving repeated live loopback or off-network simulator activity and then
asserting resources return near a warmed, scenario-specific baseline. The HTTP
and SSE scenarios use their running-idle baselines; the live MCP and simulator
scenarios use stopped/warmed baselines. The simulator scenario never binds a
listener, so its file-descriptor observation is a leak guard rather than socket
or kernel-transport fidelity evidence.
`SOKLET_SOAK_PROFILE` accepts exactly `smoke`, `nightly`, or `release`; an
omitted value selects `smoke` for local convenience. Unknown names, missing
profile resources, missing or extra keys, nonpositive or out-of-range numbers,
noncanonical ordering, and non-LF files fail closed.

The mandatory `MCP Phase 5 cross-feature churn` scenario runs mixed MCP work
through cancellation and repeated shutdown boundaries, then requires request,
stream, subscription, lifecycle, and process-resource accounting to return to
its expected baseline. Its `mcp.*` profile keys freeze client/cycle counts,
handler and stream bounds, subscription bounds, request/write/shutdown timing,
shutdown cycles, and resource-delta tolerances for all three profiles.
`mcp.settleTimeoutMillis` remains the runtime-resource cleanup deadline;
`mcp.metricDeliveryTimeoutMillis` separately bounds serialized semantic-metric
delivery only after runtime resources are idle, so observer lag does not widen
the production cleanup tolerance.

The mandatory `MCP localization render and invalidation churn` scenario uses
the same bounded MCP settings to exercise localized response rendering,
subscription-terminal pre-rendering, catalog invalidation delivery, and final
cleanup. The verifier requires context, lookup, preference-match, invalidation,
and zero-resource cardinalities to agree rather than accepting a PASS label
alone.

The mandatory `MCP off-network simulator churn` scenario uses those same
profile bounds without sockets or sleeps. Concurrent clients within sequential
simulator scopes rotate deterministically through JSON, progress SSE,
subscription, MRTR, explicit cancellation, item-limit, byte-limit, and
close-versus-terminal cases. Smoke executes 24 cycles, nightly executes 200,
and release executes 1,600. Each run also holds and explicitly releases one
non-cooperative handler to
prove bounded scope cleanup, restart exclusion while residual work remains, and
recovery before the final resource snapshot. This is bounded simulator soak
evidence, not slow-reader, write-idle, kernel-backpressure, or corpus-saturation
evidence.

## CI Profiles

- Pull requests and pushes run `smoke` with a 10-minute job timeout.
- The daily schedule and a manual CI dispatch run `nightly` with a 30-minute
  job timeout. A manual dispatch also starts the nightly fuzz matrix.
- Release validation runs `release` at the exact release commit with a
  60-minute outer job timeout. The soak module compiles the source at that
  commit; it does not claim to consume the candidate JAR.

The release orchestrator is responsible for invoking the checked-in profile,
requiring the verifier, and uploading the Markdown and Surefire artifacts. The
profile and verifier are release-ready inputs; they do not by themselves claim
that a release-duration run has occurred.

## Report Artifact

Every successful soak test run writes an auditable Markdown report to:

```text
soak/target/soak-report.md
```

The report includes the selected profile, its classpath resource, the exact
profile SHA-256 and canonical configuration, JVM/OS/process metadata, one
section per scenario, workload parameters, elapsed time, baseline and final
resource snapshots, resource deltas, tolerances, completed operation counts,
and final active gauges.

Surefire still writes its normal test output to
`soak/target/surefire-reports/`. Every CI soak job starts with `clean`, then
`scripts/verify-soak-evidence.mjs` proves that the selected profile resource
and SHA-256 match, the canonical configuration is byte-exact, and exactly the
six HTTP-abort, HTTP-churn, SSE-churn, MCP Phase 5 cross-feature, MCP
localization, and off-network MCP simulator scenarios passed. It also requires
the exact four expected Surefire XML reports, exact six-test count, zero
failures/errors/skips, and no stale XML report. Only then does
CI upload the
Markdown report and Surefire directory; missing or inconsistent evidence fails
the job. The custom report is the human-readable artifact to attach to release
notes or manual soak evidence. Generated reports and target output are
intentionally gitignored.

The verifier itself has a dependency-free regression suite:

```sh
node scripts/verify-soak-evidence-self-test.mjs
```

Tracked manual soak evidence lives in [`MANUAL_SOAKS.md`](MANUAL_SOAKS.md).

To write the report somewhere else, set `SOKLET_SOAK_REPORT`:

```sh
SOKLET_SOAK_PROFILE=nightly \
  SOKLET_SOAK_REPORT=/tmp/soklet-soak-report.md \
  mvn -f soak/pom.xml test
```
