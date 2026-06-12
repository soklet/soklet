# Soklet Soak Tests

Resource-leak probes for live loopback transports.

Run the short smoke mode:

```sh
mvn -f soak/pom.xml test
```

Run the higher-volume scheduled mode:

```sh
SOKLET_SOAK=1 mvn -f soak/pom.xml test
```

The soak module compiles Soklet's main sources directly and keeps soak-only test dependencies out of the published artifact. Default smoke mode is suitable for local checks and pull-request compile protection.

`SOKLET_SOAK=1` is a fast, high-volume leak probe for scheduled CI and manual pre-release runs, not a multi-hour duration soak. It is designed to expose per-operation file-descriptor, thread, heap, and active-gauge leaks by driving many connection/session cycles and then asserting resources return to the running-idle baseline.

## Report Artifact

Every successful soak test run writes an auditable Markdown report to:

```text
soak/target/soak-report.md
```

The report includes the run mode, JVM/OS/process metadata, one section per scenario, workload parameters, elapsed time, baseline and final resource snapshots, resource deltas, tolerances, completed operation counts, and final active gauges.

Surefire still writes its normal test output to `soak/target/surefire-reports/`. The custom soak report is the human-readable artifact to attach to release notes or manual soak evidence. Generated reports and target output are intentionally gitignored.

Tracked historical soak evidence lives in [`MANUAL_SOAKS.md`](MANUAL_SOAKS.md).

To write the report somewhere else, set `SOKLET_SOAK_REPORT`:

```sh
SOKLET_SOAK=1 SOKLET_SOAK_REPORT=/tmp/soklet-soak-report.md mvn -f soak/pom.xml test
```
