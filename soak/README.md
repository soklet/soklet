# Soklet Soak Tests

Resource-leak probes for live loopback transports.

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

The soak module compiles Soklet's main sources directly and keeps soak-only test
dependencies out of the published artifact. It therefore validates the source
at the checked-out commit; it does not claim to consume a prebuilt candidate
JAR. The smoke profile is suitable for local checks and pull-request/push leak
protection. The nightly profile increases operation counts, concurrency,
timeouts, shutdown cycles, and explicit resource-delta thresholds.

Every profile probes file-descriptor, thread, heap, and active-gauge leaks by
driving repeated live loopback transport activity and then asserting resources
return near a warmed, scenario-specific baseline. The HTTP and SSE scenarios
use their running-idle baselines; the MCP scenario uses a stopped/warmed
baseline so listener and executor resources are measured across restarts.
`SOKLET_SOAK_PROFILE` accepts exactly `smoke` or `nightly`; an omitted value
selects `smoke` for local convenience.

The mandatory `MCP Phase 5 cross-feature churn` scenario runs mixed MCP work
through cancellation and repeated shutdown boundaries, then requires request,
stream, subscription, lifecycle, and process-resource accounting to return to
its expected baseline. Its `mcp.*` profile keys freeze client/cycle counts,
handler and stream bounds, subscription bounds, request/write/shutdown timing,
shutdown cycles, and resource-delta tolerances for both profiles.

## CI Profiles

- Pull requests and pushes run `smoke` with a 10-minute job timeout.
- The daily schedule and a manual CI dispatch run `nightly` with a 30-minute
  job timeout. A manual dispatch also starts the nightly fuzz matrix.

Release-candidate orchestration is intentionally deferred until the release
validation phase; Phase 0 does not pre-scaffold it.

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
four HTTP-abort, HTTP-churn, SSE-churn, and MCP Phase 5 cross-feature scenarios
passed. It also requires the exact three expected Surefire XML reports, exact
test counts, zero failures/errors/skips, and no stale XML report. Only then does
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
