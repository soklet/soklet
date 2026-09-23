# MCP-G2 current-signature preflight — 2026-09-22

Status: **classification for review, not an API refreeze**. This preflight
compares the existing packaged development candidate with the four checked-in
MCP signature snapshots. It changes no public source, reviewed signature or
compatibility ledger, suite profile, or release gate. Its [ID-level
classification](mcp-g2-preflight-delta-2026-09-22.json) records every difference
and the exact inputs used below.

## What the current comparison says

The existing full japicmp report is SHA-256
`878f5da01fd153f66825efb61e80df9a64c9a63e10d43ac0e54fc6b7c2aca353`;
the packaged main JAR is SHA-256
`3bd93c5f449d2ab5970152b4a45997b5d79b4399ae0c7dc0dbee0f448e51ba24`.
The full and modified-only japicmp reports pass the report-pair check. The
existing owner partition covers all **379** current public/protected owners.
Extraction with that partition yields **330 current-only and 63 reviewed-only
signature IDs**, with **zero changed common records**. “Current-only” and
“reviewed-only” describe a snapshot difference, not an approved addition or
removal. A changed method descriptor appears as two IDs.

| Snapshot | Reviewed | Derived | Current-only | Reviewed-only | Changed common |
| --- | ---: | ---: | ---: | ---: | ---: |
| Phase 4 | 1,130 | 1,322 | 236 | 44 | 0 |
| Phase 5 | 194 | 240 | 53 | 7 | 0 |
| Phase 6 | 425 | 454 | 37 | 8 | 0 |
| Provisional Tasks | 98 | 98 | 4 | 4 | 0 |
| **Total** | **1,847** | **2,114** | **330** | **63** | **0** |

The complete ID-level map separates the changes by owning workstream:

| Workstream | Phase 4 current/reviewed-only | Phase 5 | Phase 6 | Provisional | Total current/reviewed-only |
| --- | ---: | ---: | ---: | ---: | ---: |
| N0 naming and collection replacements | 28 / 38 | 7 / 7 | 8 / 8 | 4 / 4 | **47 / 57** |
| P1 caller-aware catalogs | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | **0 / 0** |
| P1b subscription authorization, notifications, and maintenance | 6 / 0 | 46 / 0 | 28 / 0 | 0 / 0 | **80 / 0** |
| P2 Completion | 34 / 0 | 0 / 0 | 0 / 0 | 0 / 0 | **34 / 0** |
| P3 Apps and complete-result sanitizer | 78 / 6 | 0 / 0 | 0 / 0 | 0 / 0 | **78 / 6** |
| P4 Skills | 90 / 0 | 0 / 0 | 1 / 0 | 0 / 0 | **91 / 0** |

N0 covers the control-to-manager and Task-control-to-creation-context renames,
the whole-list replacement builders, and the more precise registration,
descriptor, annotation, and feature getters described in
[the current API inventory](README.md#2026-09-20-n0-naming-and-collection-replacements-not-a-refreeze).
P1b covers new subscription events, authorizer/reconciler, termination reasons,
and maintenance metrics. P2 covers Completion result, context, handler,
annotations, and operation constant. P3 covers Apps metadata/capability members
and the approved complete-result sanitizer/builder replacement. P4 covers Skills
registration, policy, page, endpoint and server configuration, operation
constants, and the Skills-list localization cursor. The sidecar classifies
individual IDs where these workstreams share an owner such as `McpServer` or
`McpEndpoint`; ownership by class name alone would misclassify those members.

The separate [compatibility review](../../docs/streaming-api-evidence/milestone-6c-2026-09-22/mcp-api-drift-review.md)
found exactly nine current-only and four reviewed-only MCP *incompatibility*
records after the route amendment. Those are a different comparison from the
393 signature IDs above. `McpServer.java` and `McpResourceContents.java` are
unchanged against this streaming worktree's base commit; these MCP differences
were already present before the streaming redesign. The streaming compatibility
amendment accounts for its own HTTP/SSE changes and does not absorb these MCP
records.

## Bounded next refreeze work

The first reviewable MCP-G2 implementation pass can take **N0, P1b, and P2**
as one explicitly classified candidate: 161 current-only and 57 reviewed-only
signature IDs across the four snapshots, plus their precise compatibility,
evolution, and roadmap effects. That is a candidate for coordinated review,
**not yet permission to replace the snapshots**. The P1b official notification
checks still need observed dispositions. The owner accepted the narrow P0-C
classification, but runner integration and immutable-candidate replay remain
open before declaring that portion refrozen. No P1 signature delta was observed.

P3's 78 / 6 and P4's 91 / 0 signature differences remain provisional. The
execution tracker requires the Apps real-host matrix at MCP-G4 and Skills
bounded-parser/operational qualification at MCP-G5 before either public surface
is final. Both families already appear in the current Phase 4/6 owner partition,
so copying all generated signatures into checked-in snapshots now would silently
freeze unqualified APIs. A partial snapshot update would intentionally leave the
aggregate gate red; it must not be described as an MCP-G2 pass.

The current source-derived readiness work is also visible independently:
`verify-mcp-public-evolution.mjs` reports missing sealed root
`McpOperationResult` and stale Apps/maintenance enum entries against the old
ledgers. Its self-test also encounters a stale constructor suppression
fingerprint. `verify-mcp-roadmap-readiness.mjs` reports two newly discovered
source sites (`isFrameworkRoutedMethod` and `SkillYamlParser.directive`) and one
stale `isFrameworkTaskMethod` site. These are bounded inventory/review tasks,
not reasons to change protocol behavior in this preflight.

## Reproduction and limits

With the existing report and candidate bytes, extract each snapshot to a
temporary directory, compare canonical JSON records by `id`, and classify each
current-only/reviewed-only ID using the workstream boundaries above. The
checked-in [sidecar](mcp-g2-preflight-delta-2026-09-22.json) binds the full
report, candidate JAR, four include inventories, and four reviewed snapshots by
SHA-256. It lists all **393** differences; no generated signature file or
checked-in ledger is used as a replacement baseline.

The following read-only checks passed for this preflight:

```text
node scripts/api-diff/self-test.mjs
node scripts/api-diff/japicmp-symbols.mjs --verify-report-pair target/japicmp/mcp-api-diff.xml target/japicmp/mcp-api-freeze.xml
node scripts/api-diff/japicmp-symbols.mjs --verify-inventory target/japicmp/mcp-api-freeze.xml api/mcp/non-mcp-public-api.allowlist api/mcp/phase-4.includes api/mcp/phase-5.includes api/mcp/phase-6.includes api/mcp/provisional.includes
node scripts/verify-mcp-metadata-builders.mjs
```

The aggregate `scripts/verify-mcp-api-freezes.sh` remains red on the previously
observed compatibility mismatch and stale signatures; this preflight did not
rerun its Maven build. The sidecar is bound to the existing development JAR and
japicmp report, not an immutable release candidate. Any later source, owner
partition, or candidate change requires fresh extraction and classification.
