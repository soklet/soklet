# MCP Phase 4 API-freeze rationale

Date: 2026-08-06

Post-freeze correction reviewed: 2026-08-07

This record approves the Phase 4 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a scoped API decision; it is
not a Phase 5/6 implementation, full conformance, or release-candidate claim.

## Compatibility and report model

The reviewed current incompatibility set contains exactly 556 canonical
symbols and has SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
`target/japicmp/mcp-api-diff.xml` is the modified-only report used to derive
that set. It deliberately omits compatible unchanged/restored containers.

`target/japicmp/mcp-api-freeze.xml` is the matching full report. Ownership and
selected-signature discovery use the full report so an MCP type whose JVM
surface was restored identically to 3.5.1 cannot disappear merely because it
is absent from modified-only output. The aggregate verifier first proves that
the reports have the same baseline and current archives, then applies their
separate roles.

The exact reviewed owner universe is:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- six Phase 6 owners;
- 28 provisional owners; and
- 206 owners in total.

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
`89d96458cee33f96b6eef3be4b971cbf887f087f6a604b8f0e7041891b8530b5`.
The independent reflection contract freezes the Phase 4 JSpecify type-use
layout with SHA-256
`c10d11f1c510b5219f819d19ff4dec687eec4fbfb13006b988366253eec70cab`.

### Post-freeze wrapper correction

The subsequent Phase 5 API review exposed one cross-cutting Soklet convention
that the initial freeze had not applied consistently: exported API should use
reference wrappers for scalar values, reserving primitives for internal code.
Because the MCP API is still unreleased in 3.6.0, the Phase 4 surface was
deliberately corrected rather than preserving the inconsistency.

Exactly 49 Phase 4 signatures changed from primitive scalars to non-null
reference wrappers. The regenerated snapshot still contains the same 1,049
records with the same class, constructor, field, and method counts. Five of the
49 corrections restore wrapper signatures already present in released 3.5.1,
which reduces the reviewed baseline incompatibility set from 561 to 556
records. Review found no unrelated signature delta.

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

## Historical local verification

The original 2026-08-06 closeout pass, before the wrapper correction and the
current Phase 5 runtime slices, produced this historical local evidence:

- `scripts/verify-mcp-api-freezes.sh` passed bidirectionally with 561 reviewed
  incompatibilities, 200 exact owners, and 1,049 exact Phase 4 signatures;
- the full JDK 21 suite passed 1,246 tests with zero failures, zero errors, and
  four expected skips;
- a clean JDK 26 package passed the same suite and built the main, source, and
  Javadoc artifacts;
- the JDK 21 Error Prone/NullAway compile passed at the configured severity;
  pre-existing warnings remain and NullAway is in advisory `WARN` mode, so
  this is not a warning-free claim;
- JDK 21 SpotBugs reported zero bugs and zero errors;
- the compile-only API sketch compiled all 167 sources for Java 17 and passed
  its Javadoc doclint gate;
- the pinned JSON Schema corpus verification and official conformance-runner
  self-test passed; and
- the JDK 21 benchmark sources compiled.

One full-suite run exposed a test-only ordering race in the transport
containment fixture: two independent connections could acquire the only
handler slot in the opposite order from their construction. The fixture now
proves the active request has acquired its slot before opening the queued
connection. The focused 30-case containment suite and subsequent complete JDK
21 and JDK 26 suites passed; no production scheduling change was made.

The later wrapper review regenerated the current 556-record compatibility
set, the 1,049-record Phase 4 signature snapshot, and the exact JSpecify
nullability digest recorded above. This rationale does not infer a fresh full
package, static-analysis, benchmark, or official-conformance run from those API
artifact checks.

## Evidence that remains open

JDK 17 and JDK 25 results for the current tree remain CI-authoritative. The
earlier exact 23-scenario candidate-artifact result remains historical Phase 4
implementation evidence, while a fresh run against the current artifact
remains open.

Subsequent local development has implemented MRTR input-required and retry
paths, application- and framework-protected request state, request-scoped
progress/cancelation, and resource subscriptions. Those Phase 5 APIs remain
unfrozen, however, and every official Phase 5 scenario profile and pass remains
open. This rationale does not claim the Phase 5/final 39-scenario gate,
current-tree package or static-analysis sign-off, release-candidate JAR/POM
provenance, complete Phase 4 exit, or Phase 6 runtime behavior. It intentionally
contains no commit identifier; repository history and publication remain
maintainer-owned.
