# Manual Soak Evidence

This file tracks durable summaries of manual soak runs. Raw per-run reports belong under
`soak/target/` or another ignored output directory.

## 2026-06-06 Two-Hour Pre-Release Run

- Started: 2026-06-06T20:26:16Z
- Finished: 2026-06-06T22:26:22Z
- Requested duration: 7200s
- Actual elapsed: 7206s
- Command per iteration: `SOKLET_SOAK=1 mvn -q -f soak/pom.xml test`
- Iterations completed: 688
- Result: 688/688 PASS
- Host: lucy
- OS: Darwin 25.5.0 arm64
- Java: Corretto 26+35-FR

Final iteration highlights:

- MCP abandoned session churn: PASS, 64 abandoned sessions, 0 active MCP sessions, 0 active MCP SSE streams, resource deltas inside tolerance.
- Concurrent SSE churn: PASS, 1600 completed operations, 0 active SSE streams, resource deltas inside tolerance.
- HTTP abort churn: PASS, 4800 completed operations, 0 active requests, resource deltas inside tolerance.
- Concurrent HTTP churn: PASS, 8000 completed operations, 0 active requests, resource deltas inside tolerance.
