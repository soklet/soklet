# MCP API compatibility and inventories

This directory contains the reviewed, repository-owned evidence for Soklet's
MCP public/protected API. The [Phase 4 freeze rationale](phase-4-freeze-rationale.md)
records the exact 2026-08-06 decision and verification boundary.

`current-incompatibilities.jsonl` is the canonical set of incompatibilities
between the released `com.soklet:soklet:3.5.1` artifact and the current
3.6.0 source tree. It currently contains 561 records and has
SHA-256
`cece9489d13b79e286b0cacb1de10dee3e2884ea6b6f479fbdfd95e59ec4ff33`.
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
| `phase-5.includes` | 36 | Phase 5-owned types; not yet frozen |
| `phase-6.includes` | 6 | Phase 6-owned types; not yet frozen |
| `provisional.includes` | 28 | owner not yet assigned to a frozen phase |
| `non-mcp-public-api.allowlist` | 0 | reviewed unrelated API deltas |

The 203-entry union is sorted, nonoverlapping, and exact. Ownership records
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

The Phase 5 value layer now includes `McpInputRequest` and
`McpInputRequiredResult`. Production tools, prompts, and resource reads can
emit declared input requests; required client capabilities are checked before
application admission, while conditional capability failures are decided only
when a handler emits the corresponding request. Input-required tool results
bypass the complete-output sanitizer, and resource results receive no cache
hints.

This is a bounded outbound-result slice, not complete MRTR support. Protected
request-state emission, inbound retry parsing, repeated rounds, retry-integrity
checks, progress interaction, and the official Phase 5 conformance scenarios
remain open. Request state and inbound retry fields currently fail closed
rather than being treated as fresh operation input.

## Active freeze

`frozen-phases` contains the contiguous, sorted prefix of frozen phases. It
currently contains only Phase 4. `phase-4.signatures.jsonl` freezes 1,049
canonical records across all 133 selected owners: 133 classes, 10
constructors, 78 fields, and 828 methods. Its SHA-256 is
`4672854bddde40c978c1ff40c3233faeaefde25df01af540296f2b0d84ab273f`.

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
caller-selected JDK. Local closeout evidence is green on JDK 21 and
supplemental JDK 26, but final JDK 17 and JDK 25 CI results for this exact
freeze remain open. The pinned official MCP Phase 4 run passed all 23 active
scenarios before the current Phase 5 value and outbound-result slice landed;
a fresh official run against the current packaged candidate remains open.
These caveats do not weaken the checked-in API freeze, but they prevent
treating it as final Phase 4, Phase 5, or release sign-off.

A compatible addition to a frozen owner requires deliberate review, a
snapshot update, and an update to the freeze rationale. An incompatible change
requires an explicit compatibility plan and version decision. No generated
snapshot is accepted automatically, and no Git commit identifier is treated as
the compatibility baseline.
