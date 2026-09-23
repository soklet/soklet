# Milestone 6a — Consumers, documentation, and candidate evidence

**September 23 amendment:** this checkpoint compiled and packaged the earlier
SSE ownership candidate. The final 4.0 SSE surface removes those unicaster and
builder methods; affected examples, consumers, API ledgers, and candidate checks
must be refreshed. The build hashes and receipts below remain historical.

Completed 2026-09-22 in the isolated streaming worktree based on
`c5d02871d95d6dac1b4e5366496c08d6c314b383`. The selected public names remain
unchanged. Consumer migrations, compiled examples, public contract annotations,
and the deliberate streaming API amendment are complete for this slice.
**Milestone 6 remains open for inventory reconciliation and release qualification.**

The final local development JAR is `soklet-4.0.0.jar`, SHA-256
`3bd93c5f449d2ab5970152b4a45997b5d79b4399ae0c7dc0dbee0f448e51ba24`.
The clean package uses the actual Java 17 compiler and Java 26 Javadoc tool;
main, sources, Javadoc and POM identities are retained in the
[artifact receipt](streaming-api-evidence/milestone-6a-2026-09-22/candidate-artifacts-final.json).
All 586 packaged Java sources match the current production source byte-for-byte.
This is an unsigned, uncommitted local candidate, not an immutable release
acceptance or publication receipt. Earlier artifacts and failed attempts remain
explicitly distinguished from the final results.

## Changes

Core README, migration notes and changelog now describe the implemented ownership
surface and qualified SSE defaults. Examples explain checked acquisition,
concurrent close-as-abort requirements, producer-thread encoder finalization,
retained SSE lifetimes and simulator teardown. Four documentation tests compile
literal Markdown examples and exercise UTF-8 output, lazy source ownership, ZIP
finalization, SSE subscription lifetime, simulator copying and server settings.

Full core testing exposed missing public nullness and thread-safety annotations.
The stream callback/factory types now follow the repository's public contract
conventions. Documentation distinguishes concurrent reuse of a callback from
confinement of a particular HTTP response producer. These corrections do not
change selected names or runtime implementation behavior. Twenty new streaming
test classes now use the lifecycle inventory's standard 60-second outer harness
guard; internal deadlines, bounded waits and behavioral assertions are unchanged.

The packaged consumer fixture now uses the selected checked HTTP and SSE APIs,
including generated endpoint indexes. It verifies exact response bodies,
once-only managed cleanup, SSE delivery after initialization, complete shutdown,
the `SERVER_STOPPING` observation and rejection through a retained closed handle.
Existing missing/corrupt-index controls remain active.

Downstream source migrations live in
[an isolated consumer workspace](/Users/Shared/ai-shared/soklet/.worktrees/streaming-consumers-2026-09-22/CONSUMER_MIGRATION.md),
with reviewable patches, original HEAD/status/dirty-source provenance, commands
and results. Toy Store uses the single-argument writer and UTF-8 helper;
OpenTelemetry handles `CLEANUP_TIMEOUT` and asserts its bounded error attribute.
The website compiles its authored streaming/SSE examples and documents ownership,
callback registration, settings and simulator behavior. Both servlet adapters and
Barebones were validated against the final JAR. All six original consumer trees
remain unchanged, including their preexisting user edits.

The copied Toy Store and website also need one separately recorded MCP adjustment:
their dirty source calls `ContentSecurityPolicy.defaultInstance()`, while this
candidate exposes `builder().build()`. That hunk is separate from the streaming
migration patches and applies only to this candidate. It must not be applied
blindly to a checkout targeting the newer MCP API. The handoff explains ordering
and verifies patch application reproduces the migrated files byte-for-byte.

The benchmark and soak consumers now use the selected MCP registration list
setters. The HTTP benchmark discovers the same whole-JVM allocation counter
through cached reflection so its Java 17 API target compiles; there is no
per-thread approximation or measurement-window change. Counter unavailability
remains explicit. Both installed Java 17 and 21 runtimes expose the counter, so
the absent-capability test branch was not exercised by these runs.

## Validation

All results below are fresh for this slice. Core suites execute from the final
source tree; packaged and downstream consumers use the exact final JAR above.

| Check | Result |
| --- | --- |
| Full core, actual Java 17 | 3,500 discovered; **3,392 passed**, 108 skipped, zero failures/errors |
| Full core, Java 26 | 3,515 discovered; **3,511 passed**, four skipped, zero failures/errors |
| Packaged direct `javac` and Maven | All eight combinations pass on Java 17, 21, 25 and 26 |
| Packaged Gradle 9.1.0 | All three combinations pass on Java 17, 21 and 25 |
| Packaged negative controls and fixture self-tests | 26 expected-failure controls and 24 self-tests pass |
| Toy Store, Java 25 | 33 passed; one existing opt-in Docker test skipped |
| OpenTelemetry, Java 17 | 53 passed |
| Jakarta and javax adapters, Java 17 | 229 and 217 passed respectively |
| Barebones, Java 17 | Annotation-processor compilation, two loopback route checks and bounded process shutdown pass |
| Website | 19 compiled example classes, 41 mutation controls, reference/search checks and static build pass |
| Benchmark module, actual Java 17 and Java 21 | 15 tests pass on each runtime; release-17 compilation succeeds |
| Soak module, Java 21 / release 17 | All seven test source files compile; soak workloads were not executed |
| Clean candidate package, Javadocs and API reports | Pass; report pair matches the final artifact |

The Java 26 suite's four skips are the existing opt-in heavy-load and memory
stability tests. Java 17 additionally skips tests requiring live SSE or other
runtime capabilities. Per-test reasons and suite XML hashes are recorded in the
[Java 17 manifest](streaming-api-evidence/milestone-6a-2026-09-22/core-java17-final-suites.json)
and [Java 26 manifest](streaming-api-evidence/milestone-6a-2026-09-22/core-java26-final-suites.json).
The Docker publication path, full soak workloads and performance qualification
were not rerun. Earlier milestone performance evidence has not been relabeled.

The [packaged matrix](streaming-api-evidence/milestone-6a-2026-09-22/consumer-matrix-final/README.md)
retains command receipts, controls and source/artifact identity checks. Java 17
uses the HTTP/MCP fixture; Java 21 and later also exercise dedicated SSE. Gradle
9.1.0 is pinned and its downloaded distribution hash was verified before use.
It was not used on Java 26. Downstream logs, XML, patches and scripts are copied
under [downstream evidence](streaming-api-evidence/milestone-6a-2026-09-22/downstream-final/README.md),
with full source baselines retained in the isolated consumer workspace.

## API accounting

The [streaming amendment](../api/mcp/streaming-api-amendment-2026-09-22.md)
adds exactly **47 intentional incompatibility records**, preserving all 659
previous records byte-for-byte. The current ledger now has 706 records. No
historical MCP signature ledger, phase-0 snapshot or sealed receipt was rewritten.
The current ownership inventory passes with 294 MCP and 85 non-MCP types, 379 in
total. Classifying the existing route-component type fixes its missing owner;
its independent factory rename is not accepted by this streaming amendment.

The candidate still produces 712 incompatibilities. The aggregate gate remains
failed with **ten unexpected, four missing and zero changed records**: nine
unexpected MCP members, four replaced MCP descriptors, and the route-component
factory rename. Generated MCP signatures are unchanged from milestone 5a; their
independent refreeze remains open. Exact report hashes and comparisons are in
the [API summary](streaming-api-evidence/milestone-6a-2026-09-22/api-final-summary.json).

## Inventory work and next slice

The [privacy proposal](streaming-api-evidence/milestone-6a-2026-09-22/streaming-privacy-review.md)
separates 193 added / 52 removed streaming candidates from 223 added / 13 removed
candidates already present at the exact base commit. It proposes 181 concrete
boundary assignments and 12 exact receiver-based exclusions. Generic HTTP/SSE
application exceptions retain application-owned disclosure semantics; scalar
diagnostics do not imply arbitrary resources or callbacks are redacted. Existing
canary references were checked, and a dedicated secret-seeding diagnostic check
remains appropriate before freezing that attribution. The authoritative privacy
inventory and semantic pin are unchanged.

The [lifecycle proposal](streaming-api-evidence/milestone-6a-2026-09-22/streaming-lifecycle-review.md)
retains valid baseline/current censuses: 1,255/1,354 scopes and 276/351 gaps.
Streaming adds 101 scopes: 32 have mechanically derived candidate rows and 69
need policy, wait or topology review. Seven other gaps are unchanged callables
whose owning file pin changed; 275 gaps predate the work. Nine new helper
observations and 32 new discovery paths also need attribution. These first-error
counts do not prove that unresolved paths fit the 60-second guard. The
authoritative inventory is not refreshed merely because tests pass.

The independent matrix
closure check reports 11 omitted Skills finite bounds. Version-transition
verification omits three required paths: `NAMING_CONVENTIONS.md`,
`examples/skills/README.md` and `McpSkillPublicRuntimeTests.java`. Both failures
reproduce byte-for-byte at the exact base commit, including identical derived
path sets. The [baseline comparison](streaming-api-evidence/milestone-6a-2026-09-22/validator-baseline-comparison.md)
retains commands, logs, inventory identities and exact Git-blob verification.
This attributes the first reported failures; later validator phases remain
unqualified.

The next slice is **6b: reconcile lifecycle/privacy evidence and the remaining
current-candidate release inventories**, using exact baseline/current deltas and
contract review. Keep unrelated MCP API/refreeze decisions explicit. Then prepare
retrievable core/consumer commits, candidate pins and required release checks and
approvals under the repository's release process. Local consumer success does
not replace those immutable candidate receipts. No source was committed, pushed
or published by this slice. Passive HTTP disconnect detection remains the
independent milestone 4 track.

The evidence directory retains initial failures, final command receipts and a
source/evidence identity manifest. Final review found no actionable issue in the
streaming amendment, annotations or packaged fixture assertions. This report
closes slice 6a only; it does not mark the whole release green.
