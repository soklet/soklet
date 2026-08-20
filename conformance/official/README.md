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
`scenarios.json` preserves all 40 names in the pinned CLI's exact order. Its 39
`RUN` rows are the active Soklet 3.6.0 run set; `completion-complete` is the
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
compiles and runs against packaged `target/soklet-3.6.0-SNAPSHOT.jar`; release
verification instead uses the explicit checksum-locked main JAR. Its runtime
classpath contains only fixture classes plus the selected JAR, never
`target/classes` or `target/test-classes`. Normal configuration and handlers
use public APIs.
One audited same-package, package-private seam registers and enforces the exact
official JSON Schema fixture because Soklet intentionally has no public
hand-authored-schema API. The fixture imports no `com.soklet.internal` type.
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
`59a4b982e04c7649b3318b6227a6bd73b000c690b7e5c9edab4a164069a76730`.
The activated scenario manifest has SHA-256
`8d97a1560a70373d4832c96bb627eb8e4009de77c86bf3a2d9772403621fe5df`.
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

The subsequent policy/error reconciliation is test- and golden-only. Its exact
slice passes 27/27 on the pinned local Corretto 17 and Corretto 21 toolchains,
and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK. An unsigned
Corretto 17 `clean verify` passes 1,677/0/0/72 over 462 main and 197 test
sources and builds the main, sources, and Javadoc JARs. No production behavior,
public API, or Phase 4/5/6 freeze inventory changed. The canonical matrix
remains deliberately `FAILED`: 95 rows are `CORE_COMPLETE`, 116 are
`RELEASE_GATED`, four are `APPLICATION_OWNED`, 18 are `NOT_APPLICABLE`, and 29
remain `UNRESOLVED`. Final-tag Ajv validation of the current 48-message corpus
was not rerun locally and remains owned by candidate conformance. These are
local snapshot checks, not immutable-candidate evidence.

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
  /absolute/project/target/soklet-3.6.0-SNAPSHOT.jar \
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
can bind the run to the final `com.soklet:soklet:3.6.0` POM, main JAR, sources
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
    "version": "3.6.0"
  },
  "artifacts": {
    "pom": {
      "path": "/absolute/candidate/soklet-3.6.0.pom",
      "sha256": "<64-lowercase-hex>"
    },
    "mainJar": {
      "path": "/absolute/candidate/soklet-3.6.0.jar",
      "sha256": "<64-lowercase-hex>"
    },
    "sourcesJar": {
      "path": "/absolute/candidate/soklet-3.6.0-sources.jar",
      "sha256": "<64-lowercase-hex>"
    },
    "javadocJar": {
      "path": "/absolute/candidate/soklet-3.6.0-javadoc.jar",
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
  /absolute/candidate/soklet-3.6.0.jar \
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
compiles the fixture and its one same-package schema helper with the candidate
JAR as their only Soklet compile dependency, explicitly disables annotation
processing because the fixture uses programmatic registration, and uses
`jdeps` to reject any compiled dependency on `com.soklet.internal`. It also
compiles and runs a separate standalone public-API contract test for the exact
Phase 5 registrations, declarations, handler branches, and application-state
transitions. The test output also contains a public-API-only local simulator
driver. `run-local-simulator.mjs` derives the 39 RUN rows from the pinned
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
