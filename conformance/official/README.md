# Official MCP conformance manifests

This directory owns Soklet's executable integration with the official MCP
server conformance suite. It is test infrastructure inside the core Soklet
repository, not a separate product or published `soklet-mcp` artifact.

The dedicated CI job checks out the official suite at the exact commit in
`upstream-pins.json`, verifies its complete source tree plus package, lockfile,
and vendored-schema hashes, installs exactly the lockfile with lifecycle
scripts disabled, and builds its CLI explicitly. Before any upstream code is
executed, CI downloads the pinned Linux-x64 Node archive, verifies both the
checksum manifest and archive against reviewed SHA-256 values, extracts it,
and verifies the exact bundled npm version. After the explicit build, the
runner also requires the generated `dist/index.js` byte count and SHA-256 to
match the reviewed build identity before it executes that entry point. The job
has read-only repository permission and persists no checkout credential. It
never uses `npx`, a floating package, the suite's composite action, `--suite`,
`--force`, or `--expected-failures`.

“Complete source tree” means the reviewed digest of every regular file after
the documented exclusions; any missing, changed, or unmanifested file fails
before upstream execution. The suite install/build receives an allowlisted,
secret-free environment, an isolated npm cache, and `/dev/null` npm user/global
configuration. `scripts/install-pinned-node-linux-x64.sh` records the verified
distribution as CI evidence.

`upstream-pins.json` records the immutable suite and final-specification
identities, hashes, toolchain versions, and scenario-inventory digests.
`protocol-profile-evidence.json` is the separate production revision index: it
maps every immutable internal registry entry to the exact specification,
vendored schema, official-suite, scenario, checksum-bound golden, and Go/
TypeScript interoperability pins that define its evidence. The index
must contain exactly the sole `2026-07-28` production profile and the exact
`global-2026-deferred-r2c` method/parameter-ownership sentinel. The paired
`verify-profile-evidence.mjs` verifier rejects missing or extra revisions,
unknown or widened ownership, pin drift, untracked/non-regular/symlinked
evidence, and nondeterministic output; its self-test covers those fail-closed
paths. The candidate-conformance runner verifies this index before consuming
its profile evidence.

`scenarios.json` preserves all 40 names in the pinned CLI's exact order. Its 39
`RUN` rows are the active Soklet 4.0.0 run set; `completion-complete` is the
only `NOT_APPLICABLE` row because Soklet does not advertise Completion.

`earliestPhase` means the first phase in which a scenario is mandatory as part
of that phase's full gate. The 23 applicable non-MRTR scenarios other than
`server-stateless` and `tools-call-with-progress` are mandatory in Phase 4.
Those two scenarios and all 14 MRTR scenarios are mandatory in Phase 5.
`dns-rebinding-protection` was additionally active as an early Phase 3 smoke
test because its production Host/Origin path already existed. Phase 4 now runs
all 23 owned scenarios through one common fullest-truthful Phase 4 fixture.
Each scenario receives a fresh deterministic JVM; the fixture never changes
its advertised capabilities to suit the selected scenario.

The fixture is a candidate-artifact-only black box. Development verification
compiles and runs against packaged `target/soklet-4.0.0-SNAPSHOT.jar`; release
verification instead uses the explicit checksum-locked main JAR. Its runtime
classpath contains only fixture classes plus the selected JAR, never
`target/classes` or `target/test-classes`. Normal configuration and handlers
use public APIs.
One audited same-package, package-private seam registers and enforces the exact
official JSON Schema fixture because Soklet intentionally has no public
hand-authored-schema API. The fixture imports no `com.soklet.internal` type.
The same candidate-JAR-only build also compiles and runs the published
`com.soklet.conformance.transport` reference fixture. It exercises independent
HTTP/SSE engines, transparent decorators, lifecycle-owning decorators, and
two-level owning stacks entirely outside the `com.soklet` package, including
stable graph identity, wrapped request dispatch, SSE broadcaster forwarding,
delegate-subtree proof, decorator-owned cleanup, and complete graceful results.
This is packaged development evidence, not release-candidate evidence; the
later release gate separately requires checksum-matched JAR/POM provenance and
the full 39-scenario run.

Every scenario row names the truthful fixture registrations or features it
needs and the local tests that supplement official-suite coverage. Existing
test names are used where the production seam already exists. Names owned by a
future phase are checked-in evidence obligations: they must be implemented and
green before that row can acquire an expected profile. Empty arrays are valid
only for the intentionally unsupported Completion row.

Expected profiles are evidence, not guesses. `expected-checks.json` retains the
23 historical Phase 3/4 profile IDs and freezes the 16 exact reviewed Phase 5
profiles. All 39 `RUN` rows now have one non-null profile, the manifest records
`currentImplementationPhase: 5`, and the complete profile file has SHA-256
`7852c6bfc8c686f1d9b8b6e2ac27ebe1b69e5b5ee62cc1c09fd874f427d1bc09`.
The activated scenario manifest has SHA-256
`e8f0d1a8c9ac673c80e3a6434f5763bb608f49d1fad62e48c179d26b6bee18e3`.
Null never means “accept anything”; for a future phase it means “not executable
in this phase.”

The complete Maven soak profile passes four tests with zero failures, errors,
or skips on JDK 21 and JDK 26 in smoke mode and on JDK 21 in nightly mode. The
strict evidence verifier accepts exactly four scenario sections and three
Surefire suites with smoke profile SHA-256
`eaa1f52aad86dc2765200273a468801e938f5a6be1719845358c9aa57879bcd6`
and nightly profile SHA-256
`a20a70d6adb1fd2cb5909be76b219e38fc112524a12fc06552b26bdd8ec76d99`;
its self-test is green. This bounded soak evidence does not activate a profile,
advance the manifest phase, or constitute an official conformance verify pass.

The selected suite's schema is not substituted for the final specification
schema. The official checkout remains pristine, and Soklet separately
validates checked-in golden wire messages against the checksum-pinned final
`2026-07-28` schema to cover the known subscription-envelope drift.

`golden-wire/manifest.json` binds every JSON fixture to a concrete final-schema
definition and checksum. Production rows are byte-bound to the live listener
by `McpFinalTagGoldenWireProductionTests`, including the Phase 5
`input_required` tool exchange and the production-derived `inputResponses`
request/complete-response exchange. It also includes a protected request-state
exchange: the initial response emits listener-produced `requestState`, and a
fresh-ID retry echoes it with valid `inputResponses` before completing. The
Phase 5 progress exchange binds its initiating request, exact 0/50/100
notifications, and terminal response to one production SSE stream. A second
five-message production exchange binds a `subscriptions/listen` request,
acknowledgment, resource-list change, resource update, and graceful tagged
terminal result. Phase 3 unknown-method and Phase 5 missing-capability request/
error pairs brought the preceding corpus to 43 messages. The current corpus
adds a Phase 3 rate-limited tool request/error pair, a rate-limited
notification, and a Phase 4 strict-unknown-header request/error pair, bringing
the total to 48 production-derived messages; the earlier subscription terminal
schema canary has been replaced. The
validator uses Ajv and `ajv-formats` from the official suite's
verified lockfile, so no Soklet runtime dependency or second package
installation is added. These corpus additions are local production-listener/
final-schema evidence. The standalone progress observation is useful
diagnostic history. The later complete controlled observation is profile-
acquisition evidence, not the later activated Phase 5 verify gate. The fresh
verification and frozen profiles are recorded below.

The preceding 2026-08-20 protocol/capability reconciliation passed the focused
live golden suite 9/9 on local Corretto 17 and the pinned Corretto 21, the
broader protocol/capability gate 86/86 on Corretto 17, and the runner and
local-simulator self-tests. Full Corretto 17 clean verify passed 1,671/0/0/72 over
462 main and 196 test sources and built the main, sources, and Javadoc JARs.
The pinned official-suite checkout was not locally available, so at that
checkpoint final-tag Ajv validation of the expanded 43-message corpus remained
with candidate conformance. These are local snapshot checks, not immutable-
candidate evidence.

The previous policy/error reconciliation checkpoint was test- and golden-only.
Its exact slice passes 27/27 on the pinned local Corretto 17 and Corretto 21
toolchains, and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK. An unsigned
Corretto 17 `clean verify` passes 1,677/0/0/72 over 462 main and 197 test
sources and builds the main, sources, and Javadoc JARs. No production behavior,
public API, or Phase 4/5/6 freeze inventory changed. At that checkpoint, the
canonical matrix was deliberately `FAILED`: 95 rows were `CORE_COMPLETE`, 116
were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`,
and 29 remained `UNRESOLVED`. Final-tag Ajv validation of the current
48-message corpus had not been rerun locally and remained owned by candidate
conformance. These are local snapshot checks, not immutable-candidate evidence.

The preceding five-row compatibility reconciliation also proved that forged
client self-report cannot replace credential-selected identity and that valid
unknown extension settings remain opaque without inventing server support,
while malformed settings fail before admission. It adds an independent HTTP
response-head evidence surface without changing the official final-schema JSON
corpus. Its focused slice passed 33/33 on the pinned local Corretto 17 and
Corretto 21 toolchains. The separate authorization/CORS HTTP-head manifest at
`../golden-http-head/authorization-cors/manifest.sha256` binds three raw
production response-head fixtures: an exact Bearer challenge, an authorized
CORS preflight, and an empty CORS rejection.
`McpAuthorizationIntegrationTests` contains two test methods: one reads and
verifies those goldens, while the other asserts request and notification
challenge semantics. These fixtures cover an absolute `resource_metadata` URI
and operation scopes, modern and endpoint-registered request headers,
`Authorization`, `WWW-Authenticate` exposure, exact response-head order and
multiplicity, and fail-closed legacy session/replay-header rejection. They are
not Ajv inputs and do not alter the 48 final-schema JSON messages or 11 focused
golden tests.

An unsigned Corretto 17 `clean verify` passed 1,685/0/0/72 over 462 main and
201 test sources and built the main, sources, and Javadoc JARs. The only
production change was an internal policy-response denylist backed by a negative
production-source inventory; public API, signatures, and the Phase 4/5/6
freeze inventories were unchanged. At that checkpoint, the canonical matrix
remained deliberately `FAILED`: 100 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
24 were `UNRESOLVED`. Final-tag Ajv validation of the expanded 48-message corpus was
not rerun locally and remains owned by candidate conformance. These are local
snapshot checks, not immutable-candidate evidence.

The preceding four-row HTTP-contract reconciliation added a third, independent
evidence surface without changing the official corpus: 22 canonical complete
HTTP response fixtures bound by
`../golden-http-contract/precedence-no-store/manifest.sha256` at SHA-256
`273e83945e5bae949c4a2eee85993883abb1350ef7234b98548d1134d0f7af02`.
Five contract tests—three real-listener goldens, one exhaustive response-
authority inventory, and one six-document manifest-digest parity gate—and four
initialize-diagnostic tests pass 9/9 in the current focused execution. Full
clean test previously passed 1,693/0/0/72 and 1,708/0/0/4,
respectively, over 462 main and 203 test sources. A subsequent local Corretto
17 package validation built all three JARs after allowing configured external
Javadoc links. These are local snapshot results, not immutable-candidate
evidence or pinned-Corretto-21 results.

The official final-schema corpus remains exactly 48 JSON messages with 11
focused tests, and the authorization/CORS corpus remains three heads with two
integration tests. The narrow internal diagnostic change implements no
initialization or session and leaves public API and freeze inventories
unchanged. At that checkpoint, the matrix remained `FAILED`: 104 rows were
`CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18
were `NOT_APPLICABLE`, and 20 remained `UNRESOLVED`. Final-tag Ajv validation remains
owned by candidate conformance.

The subsequent 2026-08-21 core-result/error closure does not alter either
official-suite corpus. A separate checksum-bound core result-envelope corpus
contains 25 JSON/SSE fixtures and four production tests; its manifest SHA-256
is `8ad233e91c4898fecaead0f779b13aebbaf3e2211fe3356f376c507736638d9c`.
A second separate corpus contains twelve canonical complete HTTP fixtures across
the eight frozen ordinary error families and two production-listener tests;
its manifest SHA-256 is
`bfaecadaba283df430026504b94f71640c0c56a830159100f9be9179a7ce4e2d`.
Readable-`initialize` and path-specific errors remain separate supplemental
evidence. Five deterministic tests additionally freeze progress/error enqueue
and mapped-error/cancellation ownership in both winning directions.

The combined focused suite passes 21/21 and the adjacent group passes 195/195
on pinned Corretto 17.0.20.1 and local Corretto 21.0.11. Full clean test passes
1,704/0/0/72 and 1,719/0/0/4 over 462 main and 205 test sources; Corretto 17
package validation builds all three JARs. API diff/parser/freezes remain green
with 565 reviewed incompatibilities and unchanged 1,048/179/422 signatures.
No production behavior, public API, freeze, or version changes; the sole
production-source diff is a package-private no-op test hook at the existing-
stream enqueue boundary. At that checkpoint, the matrix remained `FAILED`:
106 rows were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 18 remained `UNRESOLVED`.
These are local snapshot results, not immutable-candidate evidence or release-
pinned Corretto 21 results.

The subsequent application-semantic closure does not alter either official-
suite corpus. The public-API-only
[durable-handle and secured-prompt patterns](../../src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
and [resource, URI, filesystem, and cursor patterns](../../src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java)
contain eight executable tests for responsibilities that remain outside the
official schema layer and Soklet core. They move `MCP-BASE-015`,
`MCP-PROMPT-006`, `MCP-RESOURCE-006/007`, and `MCP-PAGE-004/007` to
`APPLICATION_OWNED`; distributed portable-cursor evidence remains unresolved.
No production behavior or public signature/freeze inventory changed; public
Javadocs now document the existing application-owned boundaries. Focused owner
evidence on Amazon Corretto 17.0.20.1+10-LTS is two separate 4/4 class runs
(eight tests total); the direct combined suite is 8/8 on local Amazon Corretto
21.0.11.10.1 (OpenJDK 21.0.11+10-LTS). The adjacent 12-class suite passes 66/66
on each JDK. Full `mvn -B -ntp clean test` passes 1,712 tests with zero
failures, zero errors, and 72 skips on Corretto 17, and 1,727 tests with zero
failures, zero errors, and four skips on local Corretto 21; both compile 462
main and 207 test sources. At that application-pattern checkpoint, the matrix
remained `FAILED`: 106 rows were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, 10
were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 12 remained
`UNRESOLVED`.

The subsequent conditional-capability proxy closure is independent local
transport evidence for the already-green official missing-capability profile.
`McpConditionalCapabilityProxyRuntimeTests#proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse`
drives a real two-leg loopback socket proxy with a manual monotonic idle clock.
The unsupported control observes zero backend and client-visible response
bytes through exact proxy expiry, one client-disconnect outcome, one
cooperative cancelation, no late result after retained-handler exit, and exact
framework cleanup. The capability-present control forwards the SSE head,
progress notification, and terminal result byte-for-byte through that same
proxy. The focused/adjacent gate passes 33/33 on local Amazon Corretto
17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1 (OpenJDK
21.0.11+10-LTS). Full `mvn -B -ntp clean test` passes 1,713/0/0/72 on
Corretto 17 and 1,728/0/0/4 on local Corretto 21, respectively, over 462 main
and 208 test sources. A narrow internal production observation-race fix preserves
an outer cancel transition's exact reason and cause; public API, signatures,
freeze inventories, and the version are unchanged. This proves one configured
loopback intermediary, not a universal proxy timeout or prompt
non-cooperative application-code exit, and it is not a new official-suite
result. At that checkpoint, `MCP-MRTR-011` became `CORE_COMPLETE`; the matrix
remained `FAILED` at 107 `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10
`APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 11 `UNRESOLVED`. These are local
snapshot results, not immutable-candidate evidence; the Corretto 21 run is not
release-pinned.

The subsequent queued-execution winner-election closure is independent local
runtime evidence and does not change an official-suite profile.
`McpQueuedExecutionWinnerElectionTests` uses a monotonic manual clock, staged
contenders, and a FIFO manual executor to enumerate all six total orders of
promotion, exact-boundary deadline, and client disconnect. Deadline before
promotion while still writable returns the exact queued 503/`-32603`; a
disconnect writes nothing; promotion first leaves the queued state and follows
the separately provisional active-deadline path. Its second cross-layer case
holds the observer-deferral gap after queued-deadline reservation, then makes
the outer request control unwritable before response handoff. It records zero
callback bytes, exactly one `CLIENT_DISCONNECTED` finish and one dequeue/gauge
removal. The one deadline-expiration occurrence and one abandoned response are
diagnostics for the reserved-but-unwritable attempt, not a second outcome. No
queued interceptor or handler runs and framework state returns to baseline.
The focused class passes 2/2 on pinned Amazon Corretto 17.0.20.1+10-LTS and
local Amazon Corretto 21.0.11.10.1; the adjacent Corretto 17 execution bundle
passes 53/53. Full `mvn -B -ntp clean test` passes 1,715/0/0/72 on Corretto 17
and 1,730/0/0/4 on local Corretto 21 over 462 main and 209 test sources. This
test-only slice changes no production behavior, public API, freeze inventory,
version, or official result. It closes `SOK-EXEC-005`; the current matrix
remains `FAILED` at 108 `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10
`APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 10 `UNRESOLVED`. These are
bounded local ordering results, not every possible scheduler/network
interleaving or immutable-candidate evidence; the Corretto 21 run is not
release-pinned.

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
inventory, manifest, version, or official result changes. `SOK-SIM-001` is now
`RELEASE_GATED` by the exact seven named gates. The current report remains
`FAILED` at 108 `CORE_COMPLETE`, 117 `RELEASE_GATED`, 10 `APPLICATION_OWNED`,
18 `NOT_APPLICABLE`, and 9 `UNRESOLVED`; the synthetic all-resolved report is
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
inventory, manifest, version, or official-result change. `MCP-PAGE-006` and
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
version, official result, or official 48-message/11-test corpus changes.
`MCP-BASE-011` is now `CORE_COMPLETE`; the current report remains `FAILED` at
109 `CORE_COMPLETE`, 117 `RELEASE_GATED`, 12 `APPLICATION_OWNED`, 18
`NOT_APPLICABLE`, and 6 `UNRESOLVED`, while the synthetic all-resolved report
is 115/117/12/18/0. The remaining IDs are `MCP-HTTP-020`, `SOK-VALID-002`,
`SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and `AMB-002`. This local
checkpoint adds no new official-suite claim.

The subsequent 2026-08-22 `MCP-HTTP-020` local-policy closure strengthens
`McpMirroredHeaderPublicRuntimeTests` at final source SHA-256
`2c3b912484bd96d0f2f73fc4c3b85fdf9760e22d895acf4145b962bd8fc0b303`.
Its existing real-listener path now proves that an unknown header named for an
unannotated body property cannot replace either converted or raw arguments:
`privilege=reader` remains body-authoritative while
`Mcp-Param-Privilege=administrator-canary` is absent from the successful
response. Existing exact fixtures bound the separate name-only diagnostic to
ten attempted events per server per monotonic 60-second window and 128 ASCII
bytes, attach neither values nor requests, and keep the independent default
aggregate to registered endpoint and bounded method dimensions under an
8,192-entry cap and the same downstream OpenTelemetry shape.

The focused class passes 6/0/0/0, the adjacent five-class set passes 29/0/0/0,
and full clean test passes 1,721/0/0/72 and 1,736/0/0/4 on pinned Corretto 17
and local Corretto 21 over 462 main and 211 test sources. No production
behavior, public API, freeze inventory, manifest, version, official result, or
official 48-message/11-test corpus changed. The pinned 40-scenario inventory
has no exact unknown-header diagnostic, redaction, quota, or cardinality
scenario; the adjacent standard- and registered-custom-header profiles are
not evidence for this Soklet policy. `MCP-HTTP-020` is now
`CORE_COMPLETE`; the report is 110/117/12/19/5 and the remaining IDs are
`SOK-VALID-002`, `SOK-STATE-002`, `SOK-STATE-007`, `SOK-PRIV-001`, and
`AMB-002`. Generic `Request`, `Throwable`, custom-collector, and
application-telemetry privacy remain under `SOK-PRIV-001`. This checkpoint
adds no official-suite claim.

The schema layer checks JSON message shapes only. HTTP status and headers,
CORS, SSE framing, cross-message order, ID correlation, filter containment,
and progress monotonicity remain production/local/official scenario duties.
The `byte` format is annotation-only, matching the official suite.

## Canonical Phase 5 run

First obtain the exact source commit recorded in `upstream-pins.json`. Before
installing or building it, run `verifyOfficialSuite(..., {requireBuilt:false})`
as the CI job does. Install with `npm ci --ignore-scripts`, explicitly run
`npm run build`, and rerun the normal verifier. With that exact built checkout,
compile and execute the local checkpoint as follows:

```sh
mvn -B -ntp -Dtest=McpFinalTagGoldenWireProductionTests clean package
mkdir -p target/conformance/official
sh conformance/official/build-public-fixture.sh \
  /absolute/project/target/soklet-4.0.0-SNAPSHOT.jar \
  /absolute/project/target/conformance/public-fixture \
  > target/conformance/official/public-fixture-classpath.txt
node conformance/official/local-simulator-self-test.mjs
node conformance/official/run-local-simulator.mjs \
  --classpath "$(cat target/conformance/official/public-fixture-classpath.txt)" \
  --project-root /absolute/project
node conformance/official/self-test.mjs --suite-dir /absolute/pinned-suite
node conformance/official/runner-self-test.mjs
mkdir -p target/conformance/official/phase-5
node conformance/official/run.mjs \
  --suite-dir /absolute/pinned-suite \
  --work-dir /absolute/project/target/conformance/official/phase-5 \
  --classpath "$(cat target/conformance/official/public-fixture-classpath.txt)" \
  --project-root /absolute/project \
  --phase 5 \
  --mode verify
```

## Immutable release-candidate verification

`--mode release` executes the same current Phase 5 selection and exact frozen
profiles as `--mode verify`, but it fails before starting the fixture unless it
can bind the run to the final `com.soklet:soklet:4.0.0` POM, main JAR, sources
JAR, Javadocs JAR, candidate commit, protocol pin, and official-suite pin.
Release mode never accepts a snapshot coordinate. It does not rebuild any
candidate artifact.

The preferred input is a separately reviewed, checksum-addressed manifest.
The manifest itself must be a regular non-symlink file, canonical two-space
JSON with one trailing LF, and its independently supplied SHA-256 must match.
Every artifact path is absolute and every hash is lowercase SHA-256:

```json
{
  "formatVersion": 1,
  "candidateCommit": "0123456789abcdef0123456789abcdef01234567",
  "protocolVersion": "2026-07-28",
  "suiteCommit": "49103de6ed70804e940637bf3e9e29e4a3f54e64",
  "coordinates": {
    "groupId": "com.soklet",
    "artifactId": "soklet",
    "version": "4.0.0"
  },
  "artifacts": {
    "pom": {
      "path": "/absolute/candidate/soklet-4.0.0.pom",
      "sha256": "<64-lowercase-hex>"
    },
    "mainJar": {
      "path": "/absolute/candidate/soklet-4.0.0.jar",
      "sha256": "<64-lowercase-hex>"
    },
    "sourcesJar": {
      "path": "/absolute/candidate/soklet-4.0.0-sources.jar",
      "sha256": "<64-lowercase-hex>"
    },
    "javadocJar": {
      "path": "/absolute/candidate/soklet-4.0.0-javadoc.jar",
      "sha256": "<64-lowercase-hex>"
    }
  }
}
```

Compile the unpublished fixture against that same main JAR, then pass the
workflow-trigger candidate commit and the reviewed manifest digest explicitly.
The project root must be a clean Git checkout whose exact `HEAD` equals that
commit; tracked or untracked changes fail the release run, and the supplied
candidate POM must be byte-identical to that checkout's `pom.xml`:

```sh
sh conformance/official/build-public-fixture.sh \
  /absolute/candidate/soklet-4.0.0.jar \
  /absolute/project/target/conformance/public-fixture \
  > /absolute/project/target/conformance/official/release-fixture-classpath.txt
node conformance/official/run.mjs \
  --suite-dir /absolute/pinned-suite \
  --work-dir /absolute/project/target/conformance/official/release-phase-5 \
  --classpath "$(cat /absolute/project/target/conformance/official/release-fixture-classpath.txt)" \
  --project-root /absolute/project \
  --phase 5 \
  --mode release \
  --candidate-commit 0123456789abcdef0123456789abcdef01234567 \
  --release-manifest /absolute/evidence/release-candidate.json \
  --release-manifest-sha256 '<reviewed-manifest-sha256>'
```

An orchestrator that already owns four independently reviewed artifact hashes
may omit the manifest and supply the corresponding `--candidate-pom`,
`--candidate-jar`, `--candidate-sources-jar`, and
`--candidate-javadoc-jar` paths together with each matching
`--candidate-*-sha256` option.
Mixing manifest and explicit-artifact inputs or omitting any member fails.

The runner rejects missing, empty, substituted, duplicate, or symlinked files,
hash mismatches, missing JAR/ZIP signatures, POM-coordinate mismatches, and
candidate, protocol, or suite-pin mismatches. Manifest/POM inputs are bounded
at 1 MiB and each JAR at 128 MiB. It revalidates the complete candidate before
every fixture launch and once after all scenarios. Only complete provenance
sets `releaseCandidateEvidence: true`; the evidence records the commit,
coordinates, pins, file names, sizes, and hashes. A later provenance mismatch
sets it back to false and fails the run. Release evidence uses class
`IMMUTABLE_RELEASE_CANDIDATE`; a successful gate still requires terminal
`status: PASSED`. This mode supplies the conformance runner half of the release
gate; the release orchestrator still owns the
isolated repository, build, signing, downstream, and publication evidence.

`build-public-fixture.sh` requires empty fixture and test-class directories,
compiles the fixture, transport references, and one same-package schema helper
with the candidate JAR as their only Soklet compile dependency, explicitly
disables annotation processing because the fixture uses programmatic
registration, and uses `jdeps` to reject any compiled dependency on
`com.soklet.internal`. It also compiles and runs standalone public-API contract
tests for both the exact Phase 5 registrations and the external transport graph
shapes. The test output also contains a public-API-only local simulator driver.
`run-local-simulator.mjs` derives the 39 RUN rows from the pinned
`scenarios.json` manifest in exact CLI ordinal order, executes every row
off-network against the packaged candidate, and byte-compares the driver's 39
PASS records. The driver covers real fixture handlers, response and SSE shapes,
Host/Origin/header policy, progress isolation, protected multi-round state, and
stopped/unbound diagnostics without opening a socket. Its classes are never
added to the emitted live-fixture runtime classpath.

The local simulator replay is a candidate-artifact development checkpoint. It
does not invoke the official CLI, replay the official expected-check multiset,
exercise kernel transport/backpressure/write-idle behavior, or establish
release-candidate provenance. Those remain responsibilities of the pinned live
verification and later release gates.
The deterministic capture/runtime boundary additionally proves that this
off-network path does not arm live write idle, retains its own exact bounded
capture-limit outcomes, and does not create transport failure when an
unrelated simulation completes. It intentionally does not reinterpret those
facts as kernel/TCP equivalence; live-listener tests remain the transport
authority.
`run.mjs` independently requires the exact fixture-classes/candidate-JAR pair
in that order: the fixed snapshot path in development mode or the validated
main-JAR path in release mode. It refuses missing, substituted, symlinked, or
exploded main/test class paths. The work directory must be empty.

The runner verifies the live 40-name CLI inventory and both reviewed digests
before starting a server. It invokes each active row by exact name and version,
then compares the complete
`(check ID, status, count)` multiset. Missing/extra outcomes, every
`FAILURE`/`WARNING`, unreviewed `INFO`/`SKIPPED`, a wire-schema harness error,
or a wire-success count mismatch fails independently of the CLI exit code.

Every npm-version probe, CLI, and Java child spawned by `run.mjs` belongs to its
cancellation supervisor. On POSIX the runner terminates the whole child process
group, escalating from TERM to KILL; Windows uses a documented direct-child
fallback. Retained output and waiter state are bounded, new spawns are rejected
after cancellation, and `runner-self-test.mjs` covers cooperative and stubborn
descendants, launch failure, signal handling, stale-waiter cleanup, pipe-drain
completion, timeout preservation, and output bounds. Official result trees are
also bounded by depth, file count, individual-file bytes, and aggregate bytes
before `checks.json` is read.

Generated check files, fixture/CLI logs, cleanup disposition, and
`evidence.json` belong under `target/conformance/official/`; none are checked
in. Verification evidence is written durably through `PREPARING`, `RUNNING`,
and terminal `PASSED`, `FAILED`, or `CANCELLED` states, so an early failure
still leaves an explanation. The CI job initializes a separate start marker
and uploads this tree even on failure.

For controlled profile acquisition, `--mode observe` may target only the
phase immediately after the manifest's current implementation phase. It
retains raw checks and reviewable profile drafts, uses evidence class
`PROFILE_OBSERVATION_ONLY`, and terminates as `OBSERVED`, never `PASSED`.
It is not a CI or release gate. Once profiles are reviewed and frozen, normal
verification uses `--mode verify` (the default) and requires the manifest's
exact current phase.

The reviewed 2026-08-08 Phase 5 acquisition exercised all 39 applicable rows
against the packaged public fixture. Its observation-only evidence contains
150 raw check occurrences: 147 `SUCCESS`, two exact `server-stateless`
`SKIPPED`, and one reviewed `server-sse-streams-functional` `INFO`, with zero
warning, failure, or harness-error outcomes. The skip reasons are exactly:

- `Server did not declare prompts.listChanged capability in server/discover`;
- `Server did not declare tools.listChanged capability in server/discover`.

Thirty-six automatic `wire-schema-valid` successes covered 103 messages. The
official suite does not route `server-stateless`, DNS rebinding, or the
multiple-streams scenario through that recorder, so those draft profiles carry
automatic counts `0/0`. The progress scenario retained its five-message
exchange; the 14 MRTR scenarios produced 23 ordinary successes plus 14 wire
successes over 48 messages. Every one of the prior 23 observed multisets and
wire counts matched its frozen Phase 3/4 profile exactly.

The first acquisition attempt found one fixture-only streaming-elicitation
schema defect. The corrected `test_streaming_elicitation` embeds a valid form
request whose `requestedSchema` contains `type: object` and an empty
`properties` object, using the matching registered elicitation declaration.
The rerun inspected one response frame and passed the no-independent-request
check. The durable external checkpoint
`../../../mcp/PHASE_5_PROFILE_OBSERVATION_2026-08-08.md` records acquisition
provenance, review digests, and the complete 16 draft multisets.

At the time, that observation did not change the active gate: the manifest
remained at Phase 4 and all 16 Phase 5 profile references remained null. The
later atomic activation retained the 23 historical IDs, added only the 16
reviewed profiles, advanced the phase/counts/default verification, and froze
the scoped Phase 5 API. The historical acquisition facts remain unchanged.

## Canonical Phase 5 development verification

The fresh `--phase 5 --mode verify` run against protocol `2026-07-28` and suite
commit `49103de6ed70804e940637bf3e9e29e4a3f54e64` passed all 39 selected
scenarios and all exact frozen profiles. Its evidence is classified
`CANDIDATE_ARTIFACT_DEVELOPMENT_ONLY`, records
`releaseCandidateEvidence: false`, and contains exactly 150 outcomes: 147
`SUCCESS`, the two reviewed `server-stateless` capability `SKIPPED` outcomes
listed above, and the one reviewed `server-sse-streams-functional` `INFO`.
Thirty-six wire-schema successes cover 103 messages; all 39 goldens then
checked in validated, and the focused golden-wire suite passed 7/7 at that
checkpoint. No warning, failure, or wire-schema-harness error occurred, every
standard-error stream was empty, and all 39 fixture processes exited cleanly.

The evidence SHA-256 is
`082d841697f472da97a822c4dba35e922378f170a7050eca400b32a3eeaf6fc1`;
the packaged candidate JAR SHA-256 is
`8da753893d18ba64c8442c1e235bab66ccf29d7fe7c177f99702cca252c1b1ad`.
A second clean final replay produced the same evidence digest and exact counts.
This is development-candidate evidence, not checksum-matched release-candidate
JAR/POM provenance. The durable external checkpoint is
`../../../mcp/PHASE_5_ACTIVATION_AND_VERIFICATION_2026-08-08.md`.

The historical Phase 4 gate ran all 23 Phase 4-owned scenarios. Every frozen
multiset and automatic wire-check count matched on a second fail-closed run;
all official
checks were successful except the one reviewed
`server-sse-streams-functional` informational outcome, which truthfully
records that the concurrent requests completed as independent JSON responses
rather than SSE streams. A fresh clean Corretto 21 candidate built from the
final frozen Phase 4 source passed the same exact gate on 2026-08-07, including
all 81 expected outcome occurrences and 22 independently validated golden
messages. This remains candidate-development evidence rather than
release-candidate evidence.

## Current local development revalidation

The final 2026-08-15 local artifact-backed replay is green through both
independent paths: `run-local-simulator.mjs` passes 39/39 in exact manifest
order, and the pinned live official CLI passes the same 39 active profiles.
The fixture is built against the packaged snapshot artifact rather than a
source-tree Soklet classpath. These results are development evidence only;
they do not set `releaseCandidateEvidence: true` and do not replace the
required release-mode run against checksum-matched immutable JAR, POM,
sources, and Javadocs artifacts.

## Updating a pin

A repin is a dedicated review. Update suite/spec identities, package and
schema hashes, exact inventory/order and digests, complete active profiles,
golden fixtures, and fixture behavior together. Import the reviewed final
schema and license with:

```sh
node conformance/official/scripts/import-final-tag-schema.mjs \
  /absolute/schema.json /absolute/LICENSE
```

The importer accepts only the currently reviewed bytes and refuses overwrite;
change its constants only as part of that dedicated repin.
