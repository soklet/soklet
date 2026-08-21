# Release-candidate validation

The release validator is an explicit-dispatch, fail-closed skeleton for the
Soklet 3.6.0 candidate. It is deliberately separate from ordinary CI: its input
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

Five gates remain `BLOCKED_HARNESS_MISSING`:

- `fuzz-nightly-history` and `soak-nightly-history` require a canonical
  importer for immutable scheduled-run history;
- `operational-history` requires a bounded sustained cardinality, log-drain,
  and resource-history receipt contract;
- `release-scans` requires an exact scanner/toolchain pin, severity policy,
  and retained report contract;
- `mcp-benchmarks` requires the isolated 3.5.1-versus-3.6.0 JSON comparison
  and 3.6.0 schema-baseline harness.

The checked-in registry, verifier, and verifier self-test make
`matrix-closure` `READY`. The registry deliberately produces a canonical
`FAILED` report while 20 rows remain `UNRESOLVED`, so the validator cannot
record a typed PASS receipt yet. `RELEASE_GATED` means that a row has
candidate-contained implementation or evidence anchors and that its remaining
immutable-candidate, scheduled-history, sustained-run, or pinned-downstream
proof is owned by the exact named release gate or gates. It must not be used to
hide a local implementation, test, documentation, golden, or fixture gap.

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
sets the per-principal subscription cap to one and holds the recovery
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

The current four-row HTTP-contract reconciliation closes modern-only readable-
`initialize` rejection diagnostics, unsupported classified-notification
handling, universal MCP HTTP `no-store`, and exact validation precedence. The
separate `conformance/golden-http-contract/precedence-no-store/manifest.sha256`
binds 21 canonical complete responses at SHA-256
`ec1bd3f13c70bec100b18e774bfbdf2d9e574c1d8df99f2acc4b36e85f51702c`.
Four contract tests comprise three real-listener goldens and one exhaustive
response-authority inventory; four diagnostic tests cover the positive post-
JSON and negative pre-JSON/unreadable-method boundary. They pass 8/8, and the
adjacent group passes
108/108, on local Corretto 17.0.20.1 and local Corretto 21.0.11.

Full clean test passes 1,693/0/0/72 and 1,708/0/0/4, respectively, over 462
main and 203 test sources; the JDK 21 total includes 15 extra virtual-thread
containment cases. A subsequent local Corretto 17 package validation built all
three JARs after allowing configured external Javadoc links. This corpus is
separate from the official 48-message/11-test and auth/CORS three-head/two-test
corpora. Public API and freeze inventories are unchanged. The current matrix
remains deliberately `FAILED`: 104 rows are `CORE_COMPLETE`, 116 are
`RELEASE_GATED`, four are `APPLICATION_OWNED`, 18 are `NOT_APPLICABLE`, and 20
remain `UNRESOLVED`. These local snapshot checks are neither candidate PASS
receipts nor results from the release-pinned Corretto 21.0.12.9.1 toolchain.

Six downstream gates remain `BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`. The manifest
records their exact public commit pins without treating uncommitted sibling
work as evidence:

- ToyStore's local 3.6 MCP migration passes 14/14 tests, including six MCP
  tests and exact per-request 401/403 coverage, but the migration is
  uncommitted and is not represented by the manifest's pre-migration pin;
- the current `soklet-otel` migration passes 36/36, but it remains uncommitted
  and is not represented by its pinned commit;
- the `soklet.com` migration passes its offline clean install, lint, and
  33-route static-generation build, but it remains uncommitted and is not
  represented by its pinned commit;
- both servlet integrations pass 158/158 at their 3.1.1 default and at the
  local 3.6.0 snapshot, but each required `soklet.version` POM edit is
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
   ToyStore and OpenTelemetry 3.6 migrations, Barebones startup/probe/termination,
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
have no default compatibility leg: both migrated sources target the new 3.6
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
access:

```sh
node scripts/release-validation-evidence.mjs \
  validate-config release/release-validation-manifest.json
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
