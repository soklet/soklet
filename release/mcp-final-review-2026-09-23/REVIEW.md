# MCP final review checkpoint — 2026-09-23

**Disposition: HOLD.** This review ran the available checks against owner commit
`47eeff6a907a0579b025b6b526b6ade1edbb1344` and its reproducible main JAR
`ea81b1293a8c02827db92b1dfbb6d175d82cc0588f1782a42545f8f8bd6b713f`.
It is development evidence for that committed source. The staged conformance
repin and this review change the eventual source commit, so none of these
receipts is an immutable release-candidate gate result. No release approval,
commit, or push is implied.

## Final API freeze review

The exact-commit Java 17 API-diff build produced the same main JAR SHA above.
The new full japicmp report is SHA-256
`f97afe10eb4e5177ecf33962b313a9a85439ea0e8652c9a509ba917543e279e2`.
Its current incompatibility inventory is still **nine unexpected and four
missing MCP records** against the reviewed ledger. Source-derived signature
comparison against the four checked-in snapshots yields **331 current-only and
63 reviewed-only IDs**, zero changed common IDs: Phase 4 `237/44`, Phase 5
`53/7`, Phase 6 `37/8`, provisional Tasks `4/4`. Relative to the September 22
preflight, the only newly derived ID is
`McpAppResourceMetadata.ContentSecurityPolicy.defaultInstance()` in P3 Apps.
The prior classification of the other 393 IDs remains a review aid, not a
replacement freeze.

The current-source owner inventory had two stale non-MCP entries after the
final streaming surface removed `SseUnicaster` and its old concrete
implementation. This branch removes those entries and documents why; the
exact-report owner partition now passes with **377 owners**. The compatibility
records for the removals remain intact. The other freeze blockers remain:

- `verify-mcp-public-evolution.mjs` reports the missing sealed
  `McpOperationResult` policy row and four enum rows unsupported by the old
  snapshot ledger. The final sealed/result and Apps/Skills enum policy must be
  reviewed with the final surface.
- `verify-mcp-roadmap-readiness.mjs` reports two new source sites
  (`isFrameworkRoutedMethod`, `SkillYamlParser.directive`) and one stale
  `isFrameworkTaskMethod` site. They need deliberate inventory updates.
- The separate transport dependency characterization currently fails its
  `McpRequestSseStream.offerCoalescingMessage` structural assertion after the
  streaming implementation. That assertion needs a reviewed update against
  the actual channel path.

The P1/P1b/P2/N0 API decisions can be finished independently, but **P3 Apps
and P4 Skills cannot be called finally frozen until their host and parser/
operational qualification exits pass or the owner approves a narrower surface**.
Do not replace all four signature snapshots with generated output as a shortcut.

## Official conformance and toolchain

The exact-commit JAR and public fixture ran the pinned alpha.11 official
suite with the dependency-repin installation. All **48 final-tag goldens**
validated. The strict runner completed all **46 selected scenarios**: **45
passed; `server-stateless` failed** on the two previously accepted, precisely
scoped Sampling probe checks. The original official CLI still exited **1**;
its raw two `FAILURE` rows were retained. A separate paired capture against
the same JAR passed the exact P0-C verifier: **28 unaffected stateless checks**
matched the frozen profile, while the real-socket Elicitation negative control
returned HTTP 400 / JSON-RPC `-32021` and the declared-capability positive
control returned HTTP 200. The separately invoked official task-notification
supplement passed all **eight exact socket checks**.

The accepted P0-C policy is now integrated in the canonical runner and release
evidence validator. An explicit policy flag triggers a paired capture and
rechecks the raw official exit, two exact failures, 28 unaffected results,
real-socket controls, fixture identity, and candidate JAR. The runner retains
the failing scenario row and records the distinct aggregate status
`PASSED_WITH_REVIEWED_EXCEPTION`; all other scenarios and the notification
supplement remain strict. An integrated development run finished 46 scenarios:
45 passed, the accepted raw failure was preserved, and all eight supplement
checks passed. This remains development evidence until the owner commits the
staged changes and the immutable release runner repeats them from that commit.
The dependency-repin proposal is
staged separately in this branch: its full audit improves from 16 affected
packages (nine high) to one low development-only `esbuild` finding, with zero
runtime findings. That residual low finding and the overlay are **not yet an
approved release-toolchain disposition**. See
[`alpha11-dependency-repin-2026-09-23/REVIEW.md`](../../conformance/official/proposals/alpha11-dependency-repin-2026-09-23/REVIEW.md).

## Apps qualification

The candidate-bound public-API server fixture **passed 12 scenarios / 34
requests**. The released, unmodified Inspector 2.7.0 and Chrome run observed
actual App render and refresh, but its aggregate result is
`BLOCKED_HOST_AUTH_FALLBACK`: Inspector attempted unsolicited OAuth discovery
after the intentional subscription-policy 403. The proxy rejected all such
requests, and the receipt says `renderRefresh: PASSED_NARROW_OBSERVATION` and
`fullHostQualification: false`. This is **not MCP-G4 GO**. The full real-host
matrix and a pinned production-host receipt remain outstanding. The earlier
isolated patched-host experiments do not qualify the released host.
Inspector [2.8.0](https://github.com/modelcontextprotocol/inspector/releases/tag/2.8.0)
was published after this pinned run. Its new
[notification-stream switch](https://github.com/modelcontextprotocol/inspector/pull/2394)
is documented for legacy standalone GET traffic; the observed failure is a
modern catalog subscription denial. It is therefore not evidence of a fix for
this run. A new dependency pin and real-host receipt would be required to
qualify the newer release.

## Skills qualification

The exact-commit compiled classes completed the pinned **402-case YAML
corpus** with no parser crashes: 308 corpus-valid inputs accepted, 94
corpus-invalid inputs rejected, 305 normalized event matches and three
previously reviewed corpus/spec disagreements. The report is explicitly
`qualified: false`; the approved metadata model rejects 43 corpus-valid
values and two comparable expected JSON values differ at physical EOF. The
released Inspector Skills example passed all nine client probes, including
three file digests and the deliberate one-byte corruption control. This is
retrieval/integrity evidence, not activation, consent, execution, or the
paginated/authorized/localized host matrix.

The pinned Jazzer 0.30.0/JUnit 6.1.3 Skills seed replay passed **19/19**
after giving the local JVM the agent-attachment permission it requires. A
30-second coverage-guided stream/parser smoke completed **1,375,258** runs,
and the frontmatter smoke completed **1,263,070** runs, without a finding.
These short runs do **not** satisfy the required **at least 24-hour**
coverage campaign. The two Skills fuzz methods are now registered as separate
nightly and release-history targets; the expanded history still needs fresh
complete runs after the owner commits the change. P4-Q also still needs a final scoped operational/host
receipt and explicit review of its supported YAML boundary. This is **not
MCP-G5 GO**.

**2026-09-24 owner update:** The local 24-hour Skills campaign is no longer a
qualification requirement. The owner chose the existing nightly CI Skills fuzz
slots instead; their actual run receipts and any findings still need review.
The two local long runs were stopped at the owner's request and do not count
as completed nightly runs. The remaining P4-Q and P4-R checks above are unchanged.

## What remains before candidate gates

1. Commit the reviewed P0-C integration, then replay the immutable candidate
   gate from that exact commit.
2. Review/accept the exact alpha.11 dependency repin and explicitly dispose
   of its single low development finding.
3. Resolve the released Apps host behavior or approve a clearly limited Apps
   claim; complete the chosen real-host matrix. Review Skills nightly CI
   fuzzing and scoped operational/host checks, or approve a narrower Skills
   surface.
4. Finish MCP API/incompatibility/evolution/roadmap ledgers only for qualified
   surfaces; then make the owner's final source commits and rerun all immutable
   candidate gates against those exact commits and downstream pins.

The retained JSON files in [`evidence/`](evidence/) are sanitized receipts and
summaries for review. The full local P0-C raw capture remains at
`/private/tmp/soklet-p0c-dev-20260923`, and the strict 46-scenario work tree
at `/private/tmp/soklet-47-official-dev-20260923b`. The Apps server contract
receipt remains at `/private/tmp/soklet-apps-contracts-47-20260923/receipt.json`.
The integrated accepted-policy development receipt is at
`/private/tmp/soklet-47-official-policy-20260923d/evidence.json`.
Their staged projections
must not be mistaken for a self-contained immutable release receipt.
