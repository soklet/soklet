# MCP Phase 4 API-freeze rationale

Date: 2026-08-06

This record approves the Phase 4 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a scoped API decision; it is
not a Phase 5/6 implementation, full conformance, or release-candidate claim.

## Compatibility and report model

The reviewed current incompatibility set contains exactly 561 canonical
symbols. `target/japicmp/mcp-api-diff.xml` is the modified-only report used to
derive that set. It deliberately omits compatible unchanged/restored
containers.

`target/japicmp/mcp-api-freeze.xml` is the matching full report. Ownership and
selected-signature discovery use the full report so an MCP type whose JVM
surface was restored identically to 3.5.1 cannot disappear merely because it
is absent from modified-only output. The aggregate verifier first proves that
the reports have the same baseline and current archives, then applies their
separate roles.

The exact reviewed owner universe is:

- 133 Phase 4 owners;
- 33 Phase 5 owners;
- six Phase 6 owners;
- 28 provisional owners; and
- 200 owners in total.

`Simulator` remains conceptually assigned to Phase 6, but it is not in a
current inventory because no MCP descriptor has landed on that shared host.
Adding it early would make the exact inventory stale rather than protecting a
real API.

## Frozen Phase 4 snapshot

`phase-4.signatures.jsonl` contains exactly 1,049 canonical records:

- 133 classes;
- 10 constructors;
- 78 fields; and
- 828 methods.

The reviewed file's SHA-256 is
`4672854bddde40c978c1ff40c3233faeaefde25df01af540296f2b0d84ab273f`.
The independent reflection contract freezes the Phase 4 JSpecify type-use
layout with SHA-256
`ad66bd34619a7b769bc637124c6eb49fe44b27ec6a8e214e1b88b7b4ccf657a1`.

The snapshot includes every final descriptor that a later phase needs on a
Phase 4-owned host:

- endpoint subscription configuration, endpoint copying, and exact
  endpoint-class overlay through `McpHandlerResolver`;
- request-context input responses and request state;
- tool, prompt, and resource registration input declarations and request-state
  mode, plus the equivalent handler-annotation elements and defaults;
- server protection-control and trace-correlation accessors;
- builder inputs for protection configuration, the dedicated trace key, and
  raw-validated-trace-ID logging;
- stream-queue, write-timeout, keep-alive, shutdown-timeout,
  per-principal-subscription, and subscription-duration controls; and
- the existing lifecycle/metrics shared-host attachment descriptors.

The six operational defaults are positive and finite:

- stream queue capacity: 128;
- write timeout: 30 seconds;
- keep-alive interval: 15 seconds;
- shutdown timeout: 30 seconds;
- maximum subscriptions per principal: 32; and
- maximum subscription duration: 24 hours.

The referenced Phase 5/6 types' own members remain unfrozen until their owning
phases freeze. Their Phase 4 attachments are behaviorally neutral: they do not
advertise a later capability, subscribe to an event publisher, execute MRTR,
protect request state, or perform trace correlation merely because the final
descriptor exists.

The reviewed surface deliberately excludes an initial-protection-config
getter, configurable invalid-trace-context policy, a server-level server-
information switch, the rejected legacy raw transport knobs, and Phase 4
`Simulator` MCP members. These are exclusions, not deferred additions to a
frozen Phase 4 host.

## Local verification

The closeout pass produced this local evidence:

- `scripts/verify-mcp-api-freezes.sh` passes bidirectionally with 561 reviewed
  incompatibilities, 200 exact owners, and 1,049 exact Phase 4 signatures;
- the full JDK 21 suite passes 1,246 tests with zero failures, zero errors, and
  four expected skips;
- a clean JDK 26 package passes the same suite and builds the main, source, and
  Javadoc artifacts;
- the JDK 21 Error Prone/NullAway compile passes at the configured severity;
  pre-existing warnings remain and NullAway is in advisory `WARN` mode, so
  this is not a warning-free claim;
- JDK 21 SpotBugs reports zero bugs and zero errors;
- the compile-only API sketch compiles all 167 sources for Java 17 and passes
  its Javadoc doclint gate;
- the pinned JSON Schema corpus verification and official conformance-runner
  self-test pass; and
- the JDK 21 benchmark sources compile.

One full-suite run exposed a test-only ordering race in the transport
containment fixture: two independent connections could acquire the only
handler slot in the opposite order from their construction. The fixture now
proves the active request has acquired its slot before opening the queued
connection. The focused 30-case containment suite and subsequent complete JDK
21 and JDK 26 suites pass; no production scheduling change was made.

## Evidence that remains open

JDK 17 and JDK 25 are not installed locally; their supported-matrix results
remain CI-authoritative. The exact pinned official suite checkout was also not
present during this final freeze pass. The earlier exact 23-scenario
candidate-artifact result remains valid implementation evidence, while one
fresh run against the final post-freeze artifact remains a Phase 4 exit
requirement.

This rationale does not claim the Phase 5/final 39-scenario gate,
release-candidate JAR/POM provenance, complete Phase 4 exit, or any Phase 5/6
runtime behavior. It intentionally contains no commit identifier; repository
history and publication remain maintainer-owned.
