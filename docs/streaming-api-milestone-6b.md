# Milestone 6b — Inventory reconciliation

**September 23 amendment:** the SSE public/runtime surface changed after this
source-bound checkpoint. Preserve the counts and hashes below as historical;
reconcile current inventories against the final synchronous-initializer and
broadcaster design before release.

Completed 2026-09-22 in the isolated streaming worktree based on `c5d02871`.
This slice reconciles current source with the finite-limit, privacy, lifecycle
harness and version-transition inventories. The selected streaming public API
and production runtime are unchanged. Historical API signatures, approval
receipts, release-gate dispositions and downstream commit pins are preserved.

The production artifact remains the exact candidate validated in slice 6a:
SHA-256 `3bd93c5f449d2ab5970152b4a45997b5d79b4399ae0c7dc0dbee0f448e51ba24`.
Local inventory validation does not accept an immutable release candidate or
close the independent MCP API refreeze.

## Finite limits and privacy

The finite inventory now represents all 236 derived candidates through 20 bound
contracts and 40 unchanged exclusions. Four new Skills contracts cover eleven
previously omitted source owners: bundle/file/path limits, the shared YAML
budget profile, resource-URI projection and canonical file-owner fan-in. All
16 previous contracts remain unchanged. No production limit or enforcement
rule changed; `SKILLS` is now a required inventory category.

The privacy inventory now classifies all 7,291 candidates exactly once, through
47 concrete boundaries, 32 exact exclusions and three unchanged downstream
delegations. It adds 416 current keys and removes 65 obsolete keys. Of that
delta, 193 additions and 52 removals come from streaming; the remaining drift
already existed at the exact starting commit.

Streaming resource/request/failure values remain exact application boundaries.
The shared supervisor's snapshot and framework-created deadline diagnostic have
the narrower scalar-metadata contract. A new secret-seeding test retains a queued
producer with a sensitive renderer and an application exception with nested and
suppressed sentinels. It verifies that deadline messages, stack traces and
snapshots omit these values, never call the application renderer, and preserve
the exact cause at its intended application boundary.

Skills contracts distinguish exact carriers/accessors, explicit redacted
renderers, numeric parser coordinates/limits, fixed MIME selection, and exact
local fuzz seeds. Fourteen new exclusions identify precise non-telemetry
receivers. No scanner rule or delegation was broadened to bypass review.

The current semantic pins changed only after these classifications were reviewed.
Their previous/new values and rationale are appended to the
[authority-drift record](../release/PLANNING_AUTHORITY_DRIFT_2026-09-13.md).
The [finite/version review](streaming-api-evidence/milestone-6b-2026-09-22/finite-version-reconciliation.md)
and [privacy review](streaming-api-evidence/milestone-6b-2026-09-22/privacy-reconciliation.md)
retain exact added/removed keys, source excerpts, identities, test references and
reproduction scripts. The full matrix-closure validator passes with the existing
263 rows and unchanged registry/residual/row-attribution identities.

The current version census covers 241 files and 819 occurrences. Eight reviewed
target-version references are added, and 99 changed/new source contexts are
refreshed. Existing occurrence classifications, historical governance, deletion
records and semantic removal anchors are preserved. Its final-stage validator
and mutation self-tests pass against the final lifecycle-verifier source.

## Lifecycle harness review

The source-specific review preserves production deadlines and internal test
assertions. It distinguishes actual startup/shutdown generations from
construction-only fixtures and accounts for helper waits and fail-fast cleanup.
Some tests execute multiple complete generations sequentially; a common outer
guard cannot represent their maximum composition.

Twenty-five method guards now have explicit, reviewed allowances: fourteen
120-second guards for HTTP/simulator output and ownership pairs, four 240-second
guards for source-kind/runtime combinations, one 120-second documentation SSE
guard, and six 90-second guards for individually composed timeout/control paths.
Existing 60-second class guards and all internal timing assertions remain intact.
The new diagnostic canary uses a 60-second method guard and an injected clock.

The complete inventory now closes **1,354 lifecycle scopes with zero unresolved
policy, wait, scanner or arithmetic gaps**, and binds all 33 helper observations
and 426 discovered paths. Seventy current streaming scope reviews, nine new
helper bindings and sixty historical file-pin reviews are retained. Existing
retained timing/generation formulas are unchanged; the 54 accepted D1 occurrence
identities remain byte-for-byte equivalent as structured data. Current exclusion
locations are refreshed without adding a general exclusion for new code.

An [independent control review](streaming-api-evidence/milestone-6b-2026-09-22/independent-control-review.md)
checks the SSE saturation/provider cases and simulator cleanup loops. In
particular, a successful join proves a thread is dead, while a failed join aborts
its loop; the three simulator join loops cannot each spend a full deadline on
every already-terminated thread. The inventory retains conservative bounds and
source identities for this topology rather than treating a green test as proof
of worst-case fit.

The independent review also added missing conservative connection/read allowances
to the small HTTP helpers without increasing their selected guards. Socket idle
timeouts apply per read; they do not prove a whole-response wall-clock bound.
The inventory distinguishes this workload from lifecycle phases and test-control
waits. The outer test guard remains separate from production cleanup supervision.
See the [lifecycle review](streaming-api-evidence/milestone-6b-2026-09-22/lifecycle-reconciliation.md)
for exact source bindings, formulas and the limits of the model.

## Validation and completion

| Check | Result |
| --- | --- |
| Finite/privacy inventories and full matrix closure | Pass; all current candidates accounted for, 263 matrix rows preserved |
| Full matrix-verifier self-test | Pass |
| Lifecycle inventory and gap census | Pass; 1,354 scopes, zero gaps |
| Lifecycle verifier self-tests | 134 pass, including helper-only mutation and repeated-generation controls |
| Final-stage version census and mutation tests | Pass against the final source identities |
| Inventory contract tests, actual Java 17 | 298 passed, zero failures/errors/skips |
| Affected lifecycle suites, actual Java 17 | 160 discovered; 76 passed, 84 live-SSE/runtime skips, zero failures/errors |
| Affected lifecycle suites, Java 26 | 160 passed, zero failures/errors/skips |
| Skills fuzz-seed regression, actual Java 17 | 19 passed, zero failures/errors/skips |

The new verifier controls reject edits to helper socket waits or fixture policies
even when the caller method's hash is unchanged, independently require two/four
sequential generations, and verify the 66-second saturation composition under its
90-second guard. The seed run replays existing inputs rather than performing a
sustained coverage-guided fuzz campaign. Its first sandboxed attempt failed to
attach the JVM agent; the same command passed with local agent attachment
available. Initial failed/unsealed attempts remain distinguished from final logs.

Earlier slice 6a full-core and packaged/downstream consumer results remain bound
to their original source/artifact identities. They are not relabeled as new
runs. This slice adds targeted evidence for changed guards, the diagnostic
canary and newly inventoried boundaries.

All 586 packaged production Java sources still match the current tree, and the
main JAR hash is unchanged. The protected API ledgers, matrix registry, release
manifest and accepted historical D1 identities pass the preservation check.
The [preservation receipt](streaming-api-evidence/milestone-6b-2026-09-22/preservation-and-final-checks.json)
checks protected records, final source pins, command-log hashes and retained
JUnit XML. The [final source/evidence manifest](streaming-api-evidence/milestone-6b-2026-09-22/source-identity.json)
binds the new inventories, test sources, review records and command receipts.

The independent aggregate API gate still has ten unexpected and four missing
records from earlier MCP/route-component work. Its disposition and MCP refreeze,
retrievable core/consumer commits, candidate pins, external checks and applicable
approvals remain separate release work. Passive disconnect detection remains
the independent milestone 4 track.

Evidence is under `docs/streaming-api-evidence/milestone-6b-2026-09-22/`. Duplicate
downloaded Gradle binaries accidentally copied into the previous slice's evidence
were removed after verifying every byte remains in the isolated consumer tool
cache. A relocation receipt and the prior manifest are retained; logs, source
proofs and test results are unchanged. Nothing was committed, pushed or published.
