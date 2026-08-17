# Manual Soak Evidence

This file tracks durable summaries of manual Soklet 3.6 soak runs. Raw per-run
reports belong under `soak/target/` or another ignored output directory. The
repository currently has no recorded manual 3.6 run.

Pre-3.6 runs used the retired harness contract and remain available in Git
history; they are not valid 3.6 evidence.

Run and verify the checked-in nightly profile with:

```sh
SOKLET_SOAK_PROFILE=nightly mvn -B -ntp -f soak/pom.xml clean test
node scripts/verify-soak-evidence.mjs nightly
```

Release evidence uses the checked-in release profile at the exact release
commit, with a 60-minute outer job timeout:

```sh
SOKLET_SOAK_PROFILE=release mvn -B -ntp -f soak/pom.xml clean test
node scripts/verify-soak-evidence.mjs release
```

Running these commands does not by itself create durable release evidence; the
verified report and Surefire files must be retained and bound to the commit.

A durable summary added here should record:

- the exact Soklet commit SHA
- start and finish timestamps
- OS, architecture, and complete Java runtime identity
- profile name and configuration SHA-256 from the report
- scenario operation counts, elapsed times, final active gauges, resource
  deltas, and thresholds
- the retained location and SHA-256 of the Markdown and Surefire evidence
- the final result
