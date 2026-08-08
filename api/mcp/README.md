# MCP API compatibility and inventories

This directory contains the reviewed, repository-owned evidence for Soklet's
MCP public/protected API. The [Phase 4 freeze rationale](phase-4-freeze-rationale.md)
records the 2026-08-06 decision and the subsequently reviewed wrapper
correction.

`current-incompatibilities.jsonl` is the canonical set of incompatibilities
between the released `com.soklet:soklet:3.5.1` artifact and the current
3.6.0 source tree. It currently contains 556 records and has
SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
The API-diff gate regenerates the set and compares it in both directions, so
an unexpected addition, removal, or changed record fails.

`phase-0-incompatibilities.jsonl` is the immutable 566-record historical
removal surface from deleting the legacy MCP implementation. It initially
matched the then-current set, but it intentionally does not evolve as the
greenfield implementation reuses legacy names or adds new API.
`phase-0-shared-host-rationales.jsonl` explains every removed MCP-owned member
whose containing public type remains part of Soklet.

## Reviewed ownership

Every current exported MCP type and every shared public/protected host in
scope has exactly one owner:

| Inventory | Entries | Meaning |
| --- | ---: | --- |
| `phase-4.includes` | 133 | frozen Phase 4 types and shared hosts |
| `phase-5.includes` | 39 | Phase 5-owned types; not yet frozen |
| `phase-6.includes` | 6 | Phase 6-owned types; not yet frozen |
| `provisional.includes` | 28 | owner not yet assigned to a frozen phase |
| `non-mcp-public-api.allowlist` | 0 | reviewed unrelated API deltas |

The 206-entry union is sorted, nonoverlapping, and exact. Ownership records
when a type is intended to stabilize; it does not itself freeze the type.
`McpPublicApiInventoryTests` is a fast, independent source/class-tree guard
for exported MCP types, reviewed shared hosts, sorting, overlap, and existence.
It complements the baseline comparison; it is not the authoritative
compatibility inventory.

The authoritative owner inventory comes from the full japicmp report
`target/japicmp/mcp-api-freeze.xml`. It includes:

- every current, non-internal published `Mcp`-named type;
- every current shared host whose public/protected API references an MCP type;
- every other current, non-internal public/protected API delta, which must
  appear in the non-MCP allowlist if it is unrelated to MCP.

The full report is required because a public type or member restored with the
same signature it had in 3.5.1 can be absent from a modified-only report.
`target/japicmp/mcp-api-diff.xml` remains the separate modified-only source for
the canonical incompatibility set. Removed-only containers with no current-side
API do not become current owners, and `com.soklet.internal.*` is excluded from
the ownership inventory.

## Current Phase 5 checkpoint

The local Phase 5 implementation now includes the public MRTR values and
declared outbound `input_required` runtime for tools, prompts, and resource
reads; method-specific embedded-parameter validation; inbound
`inputResponses`; and application- and framework-protected request state.
Framework protection includes authenticated state reopening, operation and
authorization binding, expiry/round checks, and originating-request-ID
evidence. Request-scoped progress and cooperative cancelation are also live on
application handler paths.

Configured endpoints can additionally host framework-owned
`subscriptions/listen` POST/SSE streams for resource-list changes and updates
to requested resource URIs. Application-owned publishers may be in-process or
distributed; Soklet owns admission, filtering, coalescing, stream bounds, and
wire serialization. The checked-in final-tag corpus contains 39
production-derived messages, including progress and subscription exchanges.

Deterministic MRTR termination coverage now includes blocked custom protector
open/seal paths, conditional-capability holds, and independent fresh-ID
branches across shutdown, deadline, and disconnect outcomes. Public listener
tests also prove same-key/same-authorization-partition cross-instance state
continuation and bounded residual-handler shutdown/restart recovery.

Phase 5 is still unfrozen. Broader long-running cross-feature soak and
resource-delta evidence remains open. All official Phase 5 scenario profiles
and the complete Phase 5 pass remain open as well; the harness continues to
leave all 16 Phase 5 expected profiles inactive and `null`. Local runtime and
wire-schema evidence must not be read as an official Phase 5 conformance pass.

## Active freeze

`frozen-phases` contains the contiguous, sorted prefix of frozen phases. It
currently contains only Phase 4. `phase-4.signatures.jsonl` freezes 1,049
canonical records across all 133 selected owners: 133 classes, 10
constructors, 78 fields, and 828 methods. Its SHA-256 is
`89d96458cee33f96b6eef3be4b971cbf887f087f6a604b8f0e7041891b8530b5`.

The snapshot includes a deliberate post-freeze correction to Soklet's
unreleased `3.6.0` MCP API: 49 Phase 4 scalar signatures now use non-null
reference wrappers instead of primitives. Five of those corrections restore
the wrapper signatures already present in 3.5.1, so the reviewed baseline
incompatibility set decreased from 561 to 556 records. Regeneration found no
unrelated signature delta; the Phase 4 snapshot retains the same 1,049 records
and component counts.

The snapshot protects the complete public/protected signature of every
selected Phase 4 owner, including shared hosts. A descriptor on one of those
hosts that names a Phase 5, Phase 6, or provisional type is frozen. The
later-owned type's own members and behavior are not frozen until its owner
phase freezes. Targeted reflection and source-contract tests cover important
details that japicmp does not reliably model, including sealed hierarchies,
public primitive constant values, MCP enum order, record and parameter names,
annotation defaults, exact JSpecify type-use nullability, and thread-safety
markers.

## Running the gates

Run the aggregate compatibility, ownership, and freeze gate with:

```sh
scripts/verify-mcp-api-freezes.sh
```

The aggregate first runs `scripts/api-diff/verify.sh`, verifies the exact owner
union from the full report, and then regenerates and bidirectionally compares
the signature snapshot for every phase named by `frozen-phases`. Generated
evidence is written under `target/japicmp/` and
`target/mcp-api-freezes/`. Neither script updates a reviewed file.

CI runs the aggregate on JDK 17; the scripts themselves use the
caller-selected JDK. On the exact current source, the aggregate gate is green
for 556 incompatibility records, 206 reviewed owners, and 1,049 frozen Phase 4
signatures. The full JDK 21 and JDK 26 test suites each execute 1,381 tests
with zero failures, zero errors, and four expected skips. The JDK 21 Error
Prone/NullAway profile passes all enforced checks; SpotBugs reports zero
`BugInstance` values and zero errors. The focused subscription-registration
boundary suite passes all 10 tests. The 167-source API sketch compiles for Java
17 and passes Javadoc
doclint, and the benchmark module compiles 437 Java source files for Java 17
on JDK 21.

The conformance runner/infrastructure self-tests and scenario/supplement-
manifest gates are green. The final-tag validator checks all 39 production-
derived golden messages against the pinned final schema with Ajv 8.20.0. All
16 Phase 5 expected profiles remain inactive and `null`; these local gates do
not claim an official Phase 5 scenario/profile pass. Final JDK 17 and JDK 25 CI
results for this exact tree remain open. The pinned official MCP Phase 4 run
that passed all 23 then-active scenarios remains historical evidence from
before the current Phase 5 runtime slices, not current Phase 5 or release
sign-off.

A compatible addition to a frozen owner requires deliberate review, a
snapshot update, and an update to the freeze rationale. An incompatible change
requires an explicit compatibility plan and version decision. No generated
snapshot is accepted automatically, and no Git commit identifier is treated as
the compatibility baseline.
