# Release-candidate validation

The release validator is an explicit-dispatch, fail-closed skeleton for the
Soklet 4.0.0 candidate. It is deliberately separate from ordinary CI: its input
is a full candidate commit SHA, the workflow checks out that exact commit, and
any repository change requires a new commit and a complete new run.

The format-v2 manifest defines an exact ordered universe of 29 release gates.
It is intentionally **not release-runnable end to end yet**: 18 gates have
complete checked-in dispatch configuration, five remain
`BLOCKED_HARNESS_MISSING`, and six downstream gates remain
`BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`, for 11 fail-closed blockers total.
`READY` means only that a gate has an executable, pinned validation path. It
never means that the gate has passed for a candidate; only a typed PASS receipt
inside the format-v2 evidence envelope from the exact candidate workflow can
establish that.

Five gates remain `BLOCKED_HARNESS_MISSING`, although their shared consumer
and bundle path now exists:

- `fuzz-nightly-history` has a candidate-bound CI producer and rolling
  accumulator. After U8 commits the candidate's literal reproducible-build
  timestamp, that final candidate must still complete seven consecutive
  scheduled UTC days before it can emit a bundle;
- `soak-nightly-history` has fail-closed assembly code, but is not scheduled
  for accumulation because the current nightly profile SHA-256
  (`e405a0ad59c4f60feb06a99e3ea01568fc9379476819314e31fd1cd7cae914b3`)
  does not equal the frozen registered SHA-256
  (`cfd2b6efbac2257eff4615b22462939779bf06de7f23ed719d0faac03e6a022c`);
- `operational-history` has a strict observation validator and packager, but
  no load producer yet. The registered six-hour observation plus ten-minute
  reserve cannot fit GitHub's six-hour hosted-job limit, so it needs an
  approved longer-lived runner or a contract amendment;
- `release-scans` has the exact-candidate CodeQL/SpotBugs/Gitleaks/runtime-
  dependency producer wired into release validation. A full-ancestry run on
  the current candidate finds 30 Gitleaks `generic-api-key` results while the
  frozen contract records 26 pending review and contains no approved
  exceptions, so the producer correctly rejects the candidate; and
- `mcp-benchmarks` has the isolated 3.5.1-versus-candidate JSON comparison and
  candidate Profile 1 schema baseline implementation. It still needs a final
  non-SNAPSHOT 4.0.0 candidate run on the registered Ubuntu environment and a
  durable owner review bound to the exact raw draft before finalization.

Their candidate-side consumer path is now fail closed. The producer commands
validate the exact registered role basenames in their artifact directory, and
the release validator accepts each result only as a pre-acquired canonical
bundle named by its dedicated environment variable:

- `SOKLET_RELEASE_FUZZ_NIGHTLY_HISTORY_BUNDLE`;
- `SOKLET_RELEASE_SOAK_NIGHTLY_HISTORY_BUNDLE`;
- `SOKLET_RELEASE_OPERATIONAL_HISTORY_BUNDLE`;
- `SOKLET_RELEASE_SCANS_BUNDLE`; and
- `SOKLET_RELEASE_MCP_BENCHMARKS_BUNDLE`.

For each gate, candidate validation retains the immutable bundle, runs the
canonical importer against the clean candidate root, retains the imported
receipt, and converts only the receipt's verified ordered role descriptors
into the ordinary format-v2 gate evidence. A missing, symlinked, substituted,
wrong-candidate, or byte-changed bundle fails before PASS evidence is recorded.
The five manifest rows remain blocked until their workflow producers can
actually create those bundles; the existence of this consumer dispatch alone
does not make a harness `READY`.

The shared producer-side bundle command is:

```text
node scripts/create-release-harness-bundle.mjs --gate <id> \
  --candidate-root <absolute-path> --evidence-root <absolute-path> \
  --output <absolute-path>
```

It derives the candidate identity from the exact clean checkout and built main
JAR, verifies every registered evidence role semantically, and creates a new
canonical immutable bundle without overwriting an existing file. The command
proves the bundle's byte consistency and registered semantics; it is not a
signature or an independent authentication of who ran the producer. Release
validation therefore accepts a bundle only from the named candidate-bound
workflow artifact, or from the trusted release operator when a contract
explicitly requires operator review/finalization. Merely possessing the
candidate checkout and this builder is not evidence that a scanner, sustained
run, or benchmark actually executed.

The checked-in registry, verifier, and verifier self-test make
`matrix-closure` `READY`. The registry deliberately produces a canonical
`FAILED` report while five rows remain `UNRESOLVED`, so the validator cannot
record a typed PASS receipt yet. `RELEASE_GATED` means that a row has
candidate-contained implementation or evidence anchors and that its remaining
immutable-candidate, scheduled-history, sustained-run, or pinned-downstream
proof is owned by the exact named release gate or gates. It must not be used to
hide a local implementation, test, documentation, golden, or fixture gap.

The registry contains 263 rows. `MCP-CAP-005` is the nineteenth
`NOT_APPLICABLE` disposition and records the one jointly declined SEP-2577
warning SHOULD across logging and developer-tooling channels; its N/A closure
reason remains empty. The five unrelated `UNRESOLVED` rows remain unchanged.

Two candidate-contained MCP-C artifacts now make the request-state rows
reviewable without changing their matrix dispositions prematurely:

- [MCP_REQUEST_STATE_SECURITY_PROFILE.md](MCP_REQUEST_STATE_SECURITY_PROFILE.md)
  binds the sole built-in profile, exact cryptographic components, envelope,
  associated data, canonical plaintext, and rejection behavior to executable
  vectors; and
- [MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md](MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md)
  binds fleet sequencing, node-local atomic publication, old/new reservation
  races, demotion, drain/removal, rollback, and emergency revocation to the
  exact rotation tests.

`SOK-STATE-002` and `SOK-STATE-007` remain `UNRESOLVED` in the current closure
registry until the final atomic MCP-C regeneration lands every row's own
evidence and matching verifier constants. These documents do not borrow a
release-gate result or claim third-party security audit.

Lifecycle-bound test and process-harness closure is tracked separately in
`release/lifecycle-bound-harness-inventory.json`. Its verifier binds the
accepted-D1 legacy shutdown-timeout baseline, a line-addressed current-source
discovery census, method-level lifecycle arithmetic and outer guards, the
standard 60-second JUnit deadlock guard, and the five short-bound harness
families settled by the lifecycle plan. The verifier fails on source-line or
policy drift, an unclassified candidate, an unresolved migration action, an
unbounded startup without controlled-completion proof, or a lifecycle path
that does not fit its recorded guard. Its adversarial self-test runs before the
inventory verifier during release-candidate validation. Ordinary push and pull
request CI does not run this release-governance census.

D1p public-cutover evidence has a separate deterministic contract in
`release/d1p-evidence-contract.md` and frozen inputs in
`release/d1p-evidence-config.json`. The release validator runs the adversarial
self-test during its candidate build. Its `api-freeze` gate then produces the
compiler-backed reports and explicitly runs both preparation and sibling-blind
tracked verification against the exact clean cumulative `HEAD`. The ordinary
push and pull request API-freeze wrapper remains API-only. Ordinary pre-G3
remediation commits are supported;
their evidence must be regenerated from accepted D1 through the new tip before
that tip can pass candidate verification. After D2, a one-time dedicated
`release/d1p-approved-preview.json` seal authenticates approved `P` from Git
history; candidate checks then keep the approved root/leaves fixed at `P` while
rederiving current protected compiler semantics for the named post-D2 owners.
`scripts/generate-d1p-approved-preview.mjs` creates that sole seal file from a
content-addressed durable G3 receipt without staging or committing it.
The final workspace command additionally verifies the
untracked retained JAR/report evidence and all seven sibling-workspace rows;
candidate mode never opens sibling bytes and does not claim otherwise.

Candidate conformance also verifies
`conformance/official/protocol-profile-evidence.json` before setup. That index
binds the sole immutable production revision to its specification, schema,
official-suite, scenario, golden, and interoperability evidence; its strict
verifier/self-test run directly and through the API-freeze wrapper. A missing
or extra profile, ownership-sentinel change, pin drift, untracked or symlinked
evidence, or nondeterministic report fails closed. Test-only injected profiles
are intentionally absent from discovery and candidate evidence.

The preceding 2026-08-20 protocol/capability golden checkpoint passed 9/9 on
local Corretto 17 and the pinned Corretto 21, 86/86 across the broader
Corretto 17 protocol/capability gate, and both runner and local-simulator self-
tests. Full Corretto 17 clean verify passed 1,671/0/0/72 over 462 main and 196
test sources and built all three JARs. The manifest bound 43 production-
derived messages; expanded-corpus final-tag Ajv validation remained with
candidate conformance because the pinned official-suite checkout was not
locally available. This is local snapshot evidence, not a candidate PASS
receipt.

The exact checksum-pinned Corretto 21.0.12.9.1 toolchain now drives
`core-jdk-21`, `static-analysis`, and `spotbugs`. At the initial JDK 21 gate
checkpoint, a same-version macOS arm64 local validation passed the full core
`clean test` at 1,681/0/0/4, reported
static-analysis `BUILD SUCCESS` with the existing advisory inventory after the
`SelfAssignment` fix, and reported zero SpotBugs bugs and errors. Current
post-fix Corretto 21 validation passes core `clean test` at 1,682/0/0/4 over
the unchanged 462 main and 196 test sources, with static-analysis clean compile
successful, the focused terminal/subscription set at 32/32, clean smoke at 6/6
plus its strict verifier and verifier self-test, and the cross-feature smoke
method at 10/10 repeated stress runs. These are local snapshot results, not
candidate PASS receipts. Current supported-JDK revalidation on local Amazon
Corretto 17.0.20.1+10-LTS passes `mvn -B -ntp clean test` at 1,667/0/0/72
over the same 462 main and 196 test sources. The two corrected methods pass
2/2 once and 20/20 across ten combined repetitions. Both corrections are
test-only synchronization: live transport smoke waits for the complete idle
snapshot, and observation containment waits for actual typed failure-log
publication before exact inspection. The original exact counts and timeout
assertions remain; production behavior, public API, and frozen inventories
are unchanged. This local clean-test result does not replace the candidate's
pinned JDK 17 `clean verify` gate. `release-scans` remains
blocked until its exact scanner/toolchain pin, severity policy, and retained
report contract are implemented.

A subsequent containment revalidation on the pinned Amazon Corretto
21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact
`mvn -B -ntp clean test` at 1,682/0/0/4 over 462 main and 196 test sources.
The focused platform-plus-virtual-thread containment matrix passes 30/30, and
20/20 complete repetitions cover 600 dynamic cases; the affected JDK 17
platform-thread matrix also passes 15/15. This is test-only synchronization:
containment waits now include the exact expected cleanup count before returning.
Expected cleanup counts, timeout bounds, and assertions are unchanged;
production behavior, public API, and frozen inventories are unchanged. These
local snapshot checks are not immutable-candidate PASS receipts or release
evidence.

A later subscription observer-scope revalidation on the same pinned Amazon
Corretto 21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact full
`mvn -B -ntp clean test` at 1,682/0/0/4 over the unchanged 462 main and 196
test sources. The affected method passes 1/1 focused and 20/20 repeated runs;
`McpSubscriptionPublicRuntimeTests` plus
`McpSubscriptionRuntimeBoundaryTests` pass 26/26. The test-only correction
sets the per-authorization-partition subscription cap to one and holds the recovery
subscription open while the original disconnect observer's exact-once count
is asserted, preventing that recovery request's legitimate finish from
entering the first request's observation phase. No production behavior,
public API, Phase 4/5/6 freeze inventory, timeout, or asserted count changed.
This is local snapshot evidence, not an immutable release-candidate PASS
receipt.

Current JDK 17 application-execution revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes the exact `mvn -B -ntp clean test` at 1,667/0/0/72
over the unchanged 462 main and 196 test sources. The affected method passes
1/1 focused and 20/20 repeated runs, and the full
`McpApplicationExecutionTests` class passes 10/10. The same affected method
also passes 1/1 on the pinned Corretto 21.0.12.9.1 toolchain. The test-only
correction uses an exact post-observer stable fence requiring both
`retainedExchanges == 1` and `queuedCleanups == 1` before inspecting the
dequeued snapshot. Existing timeout bounds and expected counts are unchanged;
production behavior, public API, and the Phase 4/5/6 freeze inventories are
unchanged. This is local snapshot evidence, not immutable release-candidate
evidence.

The previous policy/error reconciliation checkpoint was test- and golden-only.
Its exact slice passes 27/27 on the pinned local Corretto 17 and Corretto 21
toolchains, and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK, and the
manifest now binds 48 production-derived messages. An unsigned Corretto 17
`clean verify` passes 1,677/0/0/72 over 462 main and 197 test sources and
builds the main, sources, and Javadoc JARs. No production behavior, public API,
or Phase 4/5/6 freeze inventory changed. At that checkpoint, the canonical
matrix was deliberately `FAILED`: 95 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
29 remained `UNRESOLVED`. Final-tag Ajv validation of the expanded 48-message
corpus had not been rerun locally and remained owned by candidate conformance.
These local checks are not candidate PASS receipts.

The preceding five-row compatibility reconciliation closed the core rows for
admitted identity versus client self-report, unknown client-extension
fallback, Bearer challenge transport, authorization/CORS response-head
behavior, and legacy session/replay-header containment. A real listener keeps
credential-selected identity authoritative despite forged client metadata.
Valid unknown extension settings remain opaque admission input without
inventing or advertising core support; malformed settings fail before
admission. A safe Bearer challenge can carry an absolute `resource_metadata`
URI and operation scopes, but the application owns their meaning and standards
compliance. The independent CORS goldens cover `Authorization`, modern and
registered MCP headers, `WWW-Authenticate` exposure, exact order and
multiplicity, and fail-closed legacy-header rejection.

The focused compatibility slice passed 33/33 on the pinned local Corretto 17
and Corretto 21 toolchains. The separate authorization/CORS HTTP-head manifest
at `conformance/golden-http-head/authorization-cors/manifest.sha256` binds
three raw production response-head fixtures.
`McpAuthorizationIntegrationTests` contains two test methods: one reads and
verifies those goldens, while the other asserts request and notification
challenge semantics. This separate corpus does not alter the final-schema
corpus, which remains 48 JSON messages with 11 focused
golden tests. An unsigned Corretto 17 `clean verify` passed 1,685/0/0/72 over
462 main and 201 test sources and built the main, sources, and Javadoc JARs.
The only production change was an internal policy-response denylist for legacy
MCP session/replay headers; a negative
production-source inventory confines those names to that denylist. Public API,
signatures, and the Phase 4/5/6 freeze inventories were unchanged. At that
checkpoint, the canonical matrix remained deliberately `FAILED`: 100 rows
were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 24 were `UNRESOLVED`.
Final-tag Ajv validation of the
expanded 48-message corpus was not rerun locally and remains owned by candidate
conformance. These local checks are not candidate PASS receipts.

The preceding four-row HTTP-contract reconciliation closed readable
`initialize` and validated-unsupported-selector rejection diagnostics,
unsupported classified-notification handling, universal MCP HTTP `no-store`,
and exact validation precedence. The
separate `conformance/golden-http-contract/precedence-no-store/manifest.sha256`
binds 22 canonical complete responses at SHA-256
`273e83945e5bae949c4a2eee85993883abb1350ef7234b98548d1134d0f7af02`.
Five contract tests comprise three real-listener goldens, one exhaustive
response-authority inventory, and one six-document manifest-digest parity gate;
four diagnostic tests cover the positive post-JSON and negative pre-JSON/
unreadable-method boundary. Those two classes pass 9/9 in the current focused
execution.

Full clean test passes 1,693/0/0/72 and 1,708/0/0/4, respectively, over 462
main and 203 test sources; the JDK 21 total includes 15 extra virtual-thread
containment cases. A subsequent local Corretto 17 package validation built all
three JARs after allowing configured external Javadoc links. This corpus is
separate from the official 48-message/11-test and auth/CORS three-head/two-test
corpora. Public API and freeze inventories are unchanged. At that checkpoint,
the matrix remained deliberately `FAILED`: 104 rows were `CORE_COMPLETE`, 116
were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`,
and 20 remained `UNRESOLVED`. These local snapshot checks are neither candidate PASS
receipts nor results from the release-pinned Corretto 21.0.12.9.1 toolchain.

The subsequent 2026-08-21 core-result/error closure binds two more independent
corpora without changing the official 48-message/11-test or authorization/
CORS three-head/two-test corpora. The 25-fixture core result-envelope manifest
at `conformance/golden-result-envelope/live/manifest.sha256` has SHA-256
`8ad233e91c4898fecaead0f779b13aebbaf3e2211fe3356f376c507736638d9c`.
Four production-listener tests and the checksum/source-authority inventory
exhaust Soklet 4.0's core `complete` and `input_required` envelope authorities;
extension result types remain separately bounded by `MCP-BASE-006`. The twelve-
fixture canonical complete-HTTP error manifest at
`conformance/golden-error-mapping/live/manifest.sha256` has SHA-256
`bfaecadaba283df430026504b94f71640c0c56a830159100f9be9179a7ce4e2d`.
Two production-listener tests cover all eight frozen ordinary mapping families
and both `-32021` paths; readable-`initialize` and path-specific error evidence
remain explicit supplements. Five deterministic tests freeze the two progress/
error enqueue orders and three mapped-error/cancellation ownership boundaries.

The combined focused suite passes 21/21 and the adjacent group passes 195/195
on pinned Corretto 17.0.20.1 and local Corretto 21.0.11. Full clean test passes
1,704/0/0/72 and 1,719/0/0/4 over 462 main and 205 test sources. Corretto 17
package validation builds the main, sources, and Javadoc JARs; API diff/parser/
freezes remain green at 565 reviewed incompatibilities and unchanged
1,048/179/422 signature counts. No production behavior, public API, freeze, or
version changes; the sole production-source diff is a package-private no-op
test hook at the existing-stream enqueue boundary. At that checkpoint, the
matrix remained deliberately `FAILED`: 106 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
18 remained `UNRESOLVED`. These local snapshot checks are neither candidate
PASS receipts nor results from the release-pinned Corretto 21.0.12.9.1
toolchain.

The subsequent application-semantic closure adds the public-API-only
[durable-handle and secured-prompt patterns](../src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
and [resource, URI, filesystem, and cursor patterns](../src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java).
They move `MCP-BASE-015`, `MCP-PROMPT-006`, `MCP-RESOURCE-006/007`, and
`MCP-PAGE-004/007` to `APPLICATION_OWNED`; the examples demonstrate the
application's durable repository, semantic authorization, canonical
filesystem containment, delivery-intent URI policy, and cursor snapshot/
integrity/expiry duties without attributing those facilities to Soklet.
Distributed portable-cursor evidence remains unresolved. No production
behavior or public signature/freeze inventory changed; public Javadocs now
document the existing application-owned boundaries. Focused owner evidence on
Amazon Corretto 17.0.20.1+10-LTS is two separate 4/4 class runs (eight tests
total); the direct combined suite is 8/8 on local Amazon Corretto 21.0.11.10.1
(OpenJDK 21.0.11+10-LTS). The adjacent 12-class suite passes 66/66 on each JDK.
Full `mvn -B -ntp clean test` passes 1,712 tests with zero failures, zero errors,
and 72 skips on Corretto 17, and 1,727 tests with zero failures, zero errors,
and four skips on local Corretto 21; both compile 462 main and 207 test sources.
At that application-pattern checkpoint, the matrix remained deliberately
`FAILED`: 106 rows were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, 10 were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 12 remained `UNRESOLVED`.

The subsequent conditional-capability proxy closure adds
`McpConditionalCapabilityProxyRuntimeTests#proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse`.
Its real two-leg loopback socket proxy uses a manual monotonic idle clock. The
unsupported control observes zero backend and client-visible response bytes
through exact proxy expiry, one client-disconnect outcome, one cooperative
cancelation, no late result after retained-handler exit, and exact framework
cleanup. The capability-present control forwards the SSE head, progress
notification, and terminal result byte-for-byte through that same proxy. The
focused/adjacent gate passes 33/33 on local Amazon Corretto 17.0.20.1+10-LTS
and local Amazon Corretto 21.0.11.10.1 (OpenJDK 21.0.11+10-LTS). Full
`mvn -B -ntp clean test` passes 1,713/0/0/72 on Corretto 17 and 1,728/0/0/4 on
local Corretto 21, respectively, over 462 main and 208 test sources. A narrow
internal
production observation-race fix preserves an outer cancel transition's exact
reason and cause; public API, signatures, freeze inventories, and the version
are unchanged. This proves one configured loopback intermediary, not a
universal proxy timeout or prompt non-cooperative application-code exit.
At that checkpoint, `MCP-MRTR-011` became `CORE_COMPLETE`; the matrix remained
deliberately `FAILED` at 107 `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10
`APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 11 `UNRESOLVED`. These are local
snapshot results, not candidate PASS receipts; the Corretto 21 run is not
release-pinned.

The subsequent queued-execution winner-election closure adds
`McpQueuedExecutionWinnerElectionTests`. Its first method enumerates all six
total orders of promotion, exact-boundary deadline, and client disconnect with
a monotonic manual clock, staged contenders, and a FIFO manual executor.
Deadline before promotion while still queued and writable produces the exact
503/`-32603` response; disconnect writes nothing; promotion first leaves the
queued state and follows the separately provisional active-deadline path. The
second method holds the cross-layer observer-deferral gap after a queued
deadline is reserved, then makes the outer request control unwritable before
response handoff. It records zero callback bytes, one exact
`CLIENT_DISCONNECTED` finish, and one dequeue/gauge removal. One application
deadline-expiration occurrence and one abandoned response diagnose the
reserved-but-unwritable attempt without creating a second request outcome. No
queued interceptor or handler runs, cleanup remains once-only, and framework
state returns to baseline. The focused class passes 2/2 on pinned Amazon
Corretto 17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1; the adjacent
Corretto 17 execution bundle passes 53/53. Full `mvn -B -ntp clean test`
passes 1,715/0/0/72 on Corretto 17 and 1,730/0/0/4 on local Corretto 21 over
462 main and 209 test sources. This test-only slice changes no production
behavior, public API, signature/freeze inventory, or version. It closes
`SOK-EXEC-005`; the current matrix remains deliberately `FAILED` at 108
`CORE_COMPLETE`, 116 `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 10 `UNRESOLVED`. These are bounded local ordering
results, not every possible scheduler/network interleaving, candidate PASS
receipts, or immutable-candidate evidence; the Corretto 21 run is not release-
pinned.

The subsequent off-network simulation boundary closure adds deterministic
internal and public evidence. Off-network capture never arms live write idle;
non-drained item/byte limits preserve retained frames, omit the offender, and
remain immutable once-only simulator outcomes. An unrelated simulation
completes while the limited handler still owns its slot, with balanced
accounting and no transport event. Separate real-listener tests remain the
authorities for bounded slow-reader TCP backpressure and actual response-
write-idle closure/interruption. The internal/public source SHA-256 values are
`7ab30148451fbef7e8a8131486cb67989ac133271797502920ef4aa2f1db6bd5` and
`b666ad1bcb6a3bca6e3af46505fe46b7365042b06189cc8daf41d5fb51e05350`.
The two selectors pass 2/0/0/0, both affected classes pass 25/0/0/0, and the
adjacent loopback/simulator bundle passes 26/0/0/0 on pinned Corretto 17 and
local Corretto 21. Full clean test passes 1,717/0/0/72 and 1,732/0/0/4 over
462 main and 209 test sources. No production behavior, public API, freeze
inventory, manifest, or version changes. `SOK-SIM-001` is now
`RELEASE_GATED` by `candidate-build`, `core-jdk-21`, `core-jdk-25`,
`fuzz-nightly-history`, `soak-nightly-history`, `release-soak`, and
`candidate-conformance`. The current report remains `FAILED` at 108
`CORE_COMPLETE`, 117 `RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 9 `UNRESOLVED`; the synthetic all-resolved report is
117/117/10/18/0. This is deliberate simulator/live separation, not kernel,
TCP, or live write-idle equivalence.

The subsequent localized-cursor fleet application-pattern closure adds the
public-API-only `McpLocalizedCursorFleetApplicationPatternsTests` at final
source SHA-256
`10d872127f2a25632137899986ea75cfdfe838eb2d6fbfa395283285b678d567`.
It transfers only an exact opaque cursor across independently configured
simulator nodes, proves stable bounded retained-snapshot traversal and
locale/catalog/localization-revision binding, preserves identical bytes from
provider preselection through handler authentication, and maps every
exercised invalid classification to one fixed no-data application
`-32602`/400 error with zero lifecycle throwables. The exact selector passes
2/0/0/0, the adjacent six-class set passes 30/0/0/0, and full clean test
passes 1,719/0/0/72 and 1,734/0/0/4 on pinned Corretto 17 and local Corretto
21 over 462 main and 210 test sources. No production behavior, API, freeze
inventory, manifest, version, or gate-status change. `MCP-PAGE-006` and
`SOK-L10N-007` are now `APPLICATION_OWNED`; the report is
108/117/12/18/7 and the synthetic report is 115/117/12/18/0. This proves one
in-process two-node application pattern, not Soklet storage, key management,
replication, affinity, or a positive cache-TTL policy.

The subsequent `MCP-BASE-011` notification-identifier boundary closure adds
the public-API-only
`src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java` at final
source SHA-256
`ce10724e565470bdcd6f005ad3d332ea473698f7c7754c765c3bfc73a8c3a3f5`.
Its two methods prove that classified inbound notifications always have an
empty HTTP transport body and bypass application request-handler and handler-
interceptor stages. Malformed JSON that fails before notification
classification is outside this claim. Outbound progress, subscription-
acknowledgment, and list-changed notification frames carry a method and omit
top-level `id`; nested `progressToken`,
`io.modelcontextprotocol/subscriptionId`, and cancellation `requestId`
parameter members remain legitimate. Only the method-free terminal result
retains the initiating request's top-level `id`. Soklet 4.0 registers no
extension-notification handler and exposes no arbitrary extension-notification
handler API. The exact selector passes 2/0/0/0, the adjacent set passes
83/0/0/0 on both JDKs, and full clean test passes 1,721/0/0/72 and
1,736/0/0/4 on pinned Corretto 17 and local Corretto 21 over 462 main and 211
test sources. No production behavior, API, freeze inventory, manifest,
version, gate-status, or official 48-message/11-test corpus changes.
`MCP-BASE-011` is now `CORE_COMPLETE`; the current report remains `FAILED` at
109 `CORE_COMPLETE`, 117 `RELEASE_GATED`, 12 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 6 `UNRESOLVED`, while the synthetic all-resolved report
is 115/117/12/18/0. The remaining IDs are `MCP-HTTP-020`, `SOK-VALID-002`,
`SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and `AMB-002`. This local
checkpoint adds no new official-suite or release-gate claim.

The subsequent 2026-08-22 `MCP-HTTP-020` local-policy closure strengthens
`McpMirroredHeaderPublicRuntimeTests` at final source SHA-256
`2c3b912484bd96d0f2f73fc4c3b85fdf9760e22d895acf4145b962bd8fc0b303`.
Its real-listener path proves that an unknown header named for an unannotated
body property cannot replace converted or raw arguments. Existing exact
fixtures prove the separate name-only diagnostic is off by default and
bounded to ten attempted events per server per monotonic 60-second window and
128 ASCII bytes, without values or request attachment; the independent
default aggregate retains only registered endpoint and bounded method under
an 8,192-entry cap and the same downstream OpenTelemetry shape.

The focused class passes 6/0/0/0, the adjacent five-class set passes 29/0/0/0,
and full clean test passes 1,721/0/0/72 and 1,736/0/0/4 on pinned Corretto 17
and local Corretto 21 over 462 main and 211 test sources. No production
behavior, public API, freeze inventory, manifest, version, gate status,
official result, or official 48-message/11-test corpus changed. The pinned
40-scenario inventory has no exact scenario for this Soklet policy, so this
adds no official claim or release gate. `MCP-HTTP-020` is now
`CORE_COMPLETE`; the report is 110/117/12/19/5 and the remaining IDs are
`SOK-VALID-002`, `SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and
`AMB-002`. Generic `Request`, `Throwable`, custom-collector, and
application-telemetry privacy remain owned by `SOK-PRIV-001`.

Six downstream gates remain `BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`. The manifest
records their exact public commit pins without treating uncommitted sibling
work as evidence:

- ToyStore's local 4.0 MCP migration passes 14/14 tests, including six MCP
  tests and exact per-request 401/403 coverage, but the migration is
  uncommitted and is not represented by the manifest's pre-migration pin;
- the current `soklet-otel` migration passes 36/36, but it remains uncommitted
  and is not represented by its pinned commit;
- the `soklet.com` migration passes its offline clean install, lint, and
  33-route static-generation build, but it remains uncommitted and is not
  represented by its pinned commit;
- both servlet integrations pass 158/158 at their 3.1.1 default and at the
  local 4.0.0 snapshot, but each required `soklet.version` POM edit is
  uncommitted and absent from the pinned 1.2.0 release commit; and
- Barebones compiles and passes its exact local live probes against the local
  snapshot on an ephemeral loopback port without disturbing the unrelated
  process on port 8080, but its two local source-tree changes, including the
  required noninteractive port override, are uncommitted and absent from the
  pinned public commit.

The checksum-pinned TypeScript and Go interoperability harnesses are checked in,
pass against the local snapshot candidate, and are `READY`; the release run must
still execute them against the exact immutable candidate before either can
produce PASS evidence. The same distinction applies to every other `READY`
row, including the candidate build, JDK 25, API freeze, candidate Javadocs,
the JDK 21 core/static-analysis/SpotBugs gates, schema and deterministic fuzz
replay, smoke and release soak, the bounded two-listener localization fleet
fixture, conformance, and candidate
localization gates.

`scripts/validate-release-candidate.sh` stops on these statuses before building.
Change a gate to `READY` only in the same reviewed commit that supplies its
immutable pin and working validation entry point. A branch, tag, dirty sibling
checkout, or local substitution is never accepted as a pin.

The 4.0 candidate keeps static `tools/list` and `prompts/list` catalogs
immutable and caller-neutral after admission; their descriptors are not
authorization-filtered. A registered tool remains listed when it declares a
required client capability, while the matching call can receive `-32021`
before admission if that capability is absent. The list responses retain
private, zero-TTL protocol cache hints and HTTP `Cache-Control: no-store`;
this distinction is not an authorization boundary or an ETag/dynamic-catalog
promise.

## Contract

Once every gate is ready, the validator:

1. requires a clean checkout whose `HEAD` is the supplied 40-character SHA;
2. verifies the checked-in manifest and exact Corretto, Maven, Node, npm, and
   Go toolchains, rejecting any `READY` gate whose named toolchain is absent or
   unpinned;
3. performs one unsigned JDK 17 `clean verify` build and hashes the POM plus the
   main, sources, and Javadocs JARs, then runs the separately configured
   supported-JDK gates against the same candidate tree;
4. installs the already-built POM and main JAR with the pinned `install-file`
   goal into a fresh isolated Maven repository and byte-compares the result;
5. runs the API-freeze, candidate-Javadoc, JDK 21 clean-test, static-analysis,
   SpotBugs, schema-replay, deterministic-fuzz, smoke-soak, release-soak,
   bounded two-listener localization-fleet, and matrix-closure gates, retaining
   each gate's exact machine-readable and human-readable reports; matrix
   closure records a PASS only when its canonical report has zero unresolved
   rows;
6. imports the separately defined scheduled fuzz, nightly soak, operational,
   scan, and benchmark evidence only through their canonical gate contracts;
   absent or malformed history cannot be replaced by a local path or prose
   note;
7. runs official conformance in release mode against the exact candidate bytes,
   requires `IMMUTABLE_RELEASE_CANDIDATE`, `releaseCandidateEvidence: true`, and
   terminal `PASSED` evidence, then compiles and runs a library-neutral
   localization provider against the candidate JAR alone;
8. checks out every downstream at its exact manifest commit and invokes its
   candidate hook, including default/candidate servlet matrices, candidate-only
   ToyStore and OpenTelemetry 4.0 migrations, Barebones startup/probe/termination,
   website generated-artifact cleanliness, and the interoperability entry
   points; ToyStore alone runs under the separately pinned Corretto 25
   compiler/runtime because its POM requires release 25; and
9. rehashes the candidate and assembles a canonical evidence manifest only
   after the exact ordered 29-gate set has typed PASS evidence.

Each gate has one immutable evidence-contract ID and one manifest-selected
toolchain. `record-gate` requires the artifact descriptor plus an exact ordered
set of `role=path` inputs. Its receipt binds the gate ID, contract, canonical
command/profile/expectation, workflow run and job, candidate commit, and main
candidate-JAR SHA-256. Every role also fixes its media type, artifact type, and
basename. Duplicate, missing, extra, substituted, or misnamed artifacts fail
closed. Promotion independently revalidates that complete receipt structure;
it does not trust a generic list of nonempty paths.

The JDK 17 candidate build and every supported-JDK gate retain verified
Surefire reports; the JDK-specific gates also bind their checksum-verified
distribution receipts. The candidate-Javadoc gate runs the exact
`McpPublicJavadocTests` inventory contract and retains its Surefire reports,
Javadoc JAR, and standalone doclint output. Servlet baseline JAR roles bind
directly to each gate's reviewed default identity and SHA-256 during both
validation and promotion rather than relying on a role name or filename.

The `localization-fleet` gate is the real two-listener node-loss, revision-
drift, failed-reload, rolling-activation, reconnect, and cleanup fixture.
Production multi-host coordination remains an application/deployment
responsibility and is not represented as a fictitious Soklet-owned release
harness.

The soak module compiles source at the candidate commit, as documented in
`soak/README.md`; it does not claim to consume the candidate JAR. Artifact-based
gates use the checksum-matched JAR or the isolated Maven repository.

The candidate build uses the exact Corretto 17 toolchain. The workflow installs
the exact Corretto 25 archive first, Corretto 21.0.12.9.1 second, and Corretto
17 last; the validator verifies each full vendor build, runtime version, and
compiler version plus the default Maven runtime before any build. Corretto 21
is selected only for `core-jdk-21`, `static-analysis`, and `spotbugs`.
Corretto 25 is selected only for `core-jdk-25`, `fuzz-replay`, `soak-smoke`,
and ToyStore; the other currently configured Java gates use Corretto 17.
ToyStore and `soklet-otel` intentionally
have no default compatibility leg: both migrated sources target the new 4.0
API and currently default to an unpublished snapshot, while the servlet
integrations retain their released-default and candidate-version legs. Every
Maven leg first proves that the downstream POM coordinates match the manifest,
that `soklet.version` is a concrete property, and that the direct Soklet
dependency uses it. The servlet default legs additionally pin the exact
`com.soklet:soklet:3.1.1` identity and its reviewed Maven Central SHA-256. The
validator checks both the default and candidate JARs as regular, nonsymlink
archives with the expected checksum and Soklet core marker before and after
each Maven leg, and rechecks the candidate during finalization. It then sets
`failIfNoTests` and independently verifies and retains nonempty Surefire XML
with at least one executed test, zero failures or errors, and exactly the
expected Soklet core on every test classpath. Every classpath JAR is inspected
by content regardless of its filename, and directories are rejected if they
contain a shadowing `com/soklet/Soklet.class`. Both Java archive identities and
checksums are retained as gate evidence. No `current`, `latest`, branch,
dynamic, or feature-only Java resolver is used.

The Barebones hook asks the operating system for an exclusive ephemeral IPv4
loopback port and holds that reservation until immediately before process
startup. It passes the selected port through the sample's scoped
`SOKLET_BAREBONES_LOOPBACK_PORT` override, requires the matching startup marker,
probes only that exact address, terminates only the recorded child PID, and
proves that the same port can be rebound after shutdown. It does not inspect,
signal, or depend on a process using the sample's normal port 8080.

The workflow has no publish or signing authority. Promotion remains a separate
maintainer-authorized operation and must consume the completed evidence
manifest without rebuilding. The release manifest pins the exact
`scripts/release-promotion.mjs` helper and
`scripts/promote-release-candidate.sh` wrapper paths and SHA-256 values;
configuration validation fails if either checked-in tool drifts from its
reviewed pin. The offline, upload, status-recovery, and published-verification
procedure is documented in `release/PROMOTION.md`.

## Checks

The dependency-free structural tests are safe to run without Maven or network
access. Direct lifecycle-inventory verification requires the accepted-D1 commit
recorded by the inventory to exist in the local Git object database:

```sh
node scripts/release-validation-evidence.mjs \
  validate-config release/release-validation-manifest.json
node scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs
node scripts/verify-lifecycle-bound-harness-inventory.mjs
node scripts/release-validation-self-test.mjs
bash -n scripts/validate-release-candidate.sh
bash -n release/scripts/install-pinned-corretto-linux-x64.sh
```

The first command validates the currently recorded pins, exact gate order,
evidence contracts, toolchain references, and statuses. Adding
`--require-ready` is expected to fail until all 11 blockers above are resolved.

After the final candidate commit exists, dispatch
`.github/workflows/release-validation.yml` from that commit and supply the same
full SHA as its input. A successful run
uploads the four unsigned candidate inputs and the complete typed gate-evidence
tree. Missing files, substituted artifact roles, extra, missing, or reordered
gate rows, failed/skipped suites, checksum drift, a changed `HEAD`, or a
workflow SHA mismatch prevents final evidence assembly.
