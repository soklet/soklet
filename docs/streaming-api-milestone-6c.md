# Milestone 6c — Route API accounting and candidate preparation

**September 23 amendment:** the subsequent SSE API simplification changes the
current incompatibility ledger and candidate source. The route decision and
counts below describe this September 22 checkpoint; refreshed final-candidate
checks remain necessary.

Completed 2026-09-22 in the isolated streaming worktree based on `c5d02871`.
This slice accepts one source-backed route-component compatibility removal,
identifies the remaining MCP signature work, and records the exact route toward
an immutable candidate. It does not close the aggregate MCP API freeze or
release qualification.

## Route compatibility amendment

`ResourcePathDeclaration.Component.with(String, ComponentType)` was renamed to
`fromValueAndType(String, ComponentType)` before this streaming worktree's base
commit. The migration guide documents the replacement and the public naming
test checks both the new factory and removal of the old one. The
[separate amendment](../api/mcp/route-component-api-amendment-2026-09-22.md)
adds its exact generated `METHOD_REMOVED` record to the current compatibility
ledger. All 706 records from the streaming checkpoint remain byte-for-byte
unchanged; the ledger now has 707 records, SHA-256
`8a9e988868b5be52c46c931e38d0e1911d3cd7c1d8bde5128417e8b1962736be`.
Production source and the previously validated main artifact are unchanged.

The candidate comparison has 712 records. Its remaining difference is exactly
**nine unexpected and four missing MCP records**, with zero changed common
records. The [record-level review](streaming-api-evidence/milestone-6c-2026-09-22/mcp-api-drift-review.md)
and [reconciliation receipt](streaming-api-evidence/milestone-6c-2026-09-22/route-ledger-reconciliation.json)
bind the generated report, previous ledger, current ledger and the thirteen
remaining descriptors. MCP and route source files are unchanged against the
worktree's base commit. The thirteen MCP records require the coordinated
MCP-G2 refreeze; inserting them into the earlier streaming amendment would
misstate its scope.

The ledger insertion shifted one existing line-bound lifecycle exclusion from
line 663 to 664. Its source content, line hash and exclusion policy are
unchanged. Restoring that single address in the prior lifecycle inventory
reconstructs the exact slice 6b inventory hash. The current verifier still
passes all 1,354 scopes, and all 134 lifecycle self-tests pass. The active API
README now names the 707-record current ledger and retains the earlier 706
records as a dated checkpoint. That edit required a reviewed version-census
[context reseal](streaming-api-evidence/milestone-6c-2026-09-22/version-current-context-reseal.json):
the same 241 files and 819 occurrences remain classified; the independent
current-census pin changed only for the README context/line positions. Historical
version governance and removal anchors are unchanged.

## Focused verification

| Check | Result |
| --- | --- |
| Actual Java 17 `PublicNamingContractTests` | 11 passed, zero failures/errors/skips |
| Current public API ownership | 379 owners accounted for |
| Compatibility comparison | Expected failure: nine unexpected/four missing MCP records, zero changed |
| Lifecycle inventory and self-tests | Pass; 1,354 scopes and 134 self-tests |
| Final-stage version census and mutation tests | Pass; 241 current files/819 occurrences, 46 fixtures plus five external-boundary negatives |
| Matrix closure and verifier self-test | Pass; 263 rows preserved |

The compatibility comparison remains a real failed gate. A green ownership
check or focused test does not substitute for the full API freeze.
The [command summary](streaming-api-evidence/milestone-6c-2026-09-22/summary.json)
and [source/evidence manifest](streaming-api-evidence/milestone-6c-2026-09-22/source-identity.json)
pin the checks, reviewed files and retained receipts.

## Candidate preparation and remaining gates

The [candidate preparation audit](/Users/Shared/ai-shared/soklet/.worktrees/streaming-consumers-2026-09-22/evidence/candidate-preparation-audit.md)
and [path manifest](/Users/Shared/ai-shared/soklet/.worktrees/streaming-consumers-2026-09-22/evidence/candidate-staging-manifest.json)
record the current isolated checkout, six migrated consumer copies, existing
reserved worktrees, release-manifest gate states and the required preparation
order. The inventory snapshot precedes this report and amendment, so the final
path review must refresh it before staging. The core tree is detached and
uncommitted; the six consumer copies have no Git metadata, and four reserved
candidate worktrees contain preserved POM edits. They are migration material,
not retrievable downstream commit pins. The conformance security disposition
also remains open. The final tracked tree must rerun the version census after
the exact evidence files to commit are selected; the current census does not
claim to cover every untracked log and receipt.

The next MCP step is the reviewed P0-C disposition of two official
Sampling-specific assertions, or a verified upstream correction. Then MCP-G2
must review the full phase/provisional signature delta and related inventories;
the thirteen compatibility records are only its visible aggregate difference.
The candidate preparation path subsequently requires a reviewed core commit,
independent clean Linux artifact reproduction, six retrievable downstream
commits and exact pins, followed by the applicable release checks and approvals.
No existing seal, historical signature snapshot, downstream pin or approval was
rewritten, and nothing was committed, pushed or published.
