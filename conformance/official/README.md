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
`RUN` rows are the eventual Soklet 3.6.0 run set; `completion-complete` is the
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

The fixture is a candidate-artifact-only black box. It compiles and runs
against packaged `target/soklet-3.6.0-SNAPSHOT.jar`, and its runtime classpath
contains only fixture classes plus that JAR, never `target/classes` or
`target/test-classes`. Normal configuration and handlers use public APIs.
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

Expected profiles are evidence, not guesses. `expected-checks.json` retains
the Phase 3 DNS profile and freezes the complete observed profiles for the
other 22 Phase 4 rows. The 16 Phase 5 `RUN` rows keep a null
`expectedCheckProfile` until their owning phase supplies truthful behavior,
runs the exact pinned scenario, and reviews and freezes the complete result.
Null never means “accept anything”; it means “not executable in this phase.”

The selected suite's schema is not substituted for the final specification
schema. The official checkout remains pristine, and Soklet separately
validates checked-in golden wire messages against the checksum-pinned final
`2026-07-28` schema to cover the known subscription-envelope drift.

`golden-wire/manifest.json` binds every JSON fixture to a concrete final-schema
definition and checksum. Production rows are byte-bound to the live listener
by `McpFinalTagGoldenWireProductionTests`, including the Phase 5
`input_required` tool exchange. The subscription terminal row is an explicit
schema canary, not production evidence; Phase 5 must add a production-derived
row when subscriptions exist. The validator uses Ajv and `ajv-formats` from
the official suite's verified lockfile, so no Soklet runtime dependency or
second package installation is added.

The schema layer checks JSON message shapes only. HTTP status and headers,
CORS, SSE framing, cross-message order, ID correlation, filter containment,
and progress monotonicity remain production/local/official scenario duties.
The `byte` format is annotation-only, matching the official suite.

## Canonical Phase 4 run

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
node conformance/official/self-test.mjs --suite-dir /absolute/pinned-suite
node conformance/official/runner-self-test.mjs
mkdir -p target/conformance/official/phase-4
node conformance/official/run.mjs \
  --suite-dir /absolute/pinned-suite \
  --work-dir /absolute/project/target/conformance/official/phase-4 \
  --classpath "$(cat target/conformance/official/public-fixture-classpath.txt)" \
  --project-root /absolute/project \
  --phase 4
```

`build-public-fixture.sh` requires an empty fixture-classes directory,
compiles the fixture and its one same-package schema helper with the candidate
JAR as their only Soklet compile dependency, explicitly disables annotation
processing because the fixture uses programmatic registration, and uses
`jdeps` to reject any compiled dependency on `com.soklet.internal`.
`run.mjs` independently requires the exact fixture-classes/candidate-JAR pair
in that order and refuses missing, substituted, symlinked, or exploded main/test
class paths. The work directory must be empty.

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

The Phase 4 gate now runs all 23 owned scenarios. Every frozen multiset and
automatic wire-check count matched on a second fail-closed run; all official
checks were successful except the one reviewed
`server-sse-streams-functional` informational outcome, which truthfully
records that the concurrent requests completed as independent JSON responses
rather than SSE streams. A fresh clean Corretto 21 candidate built from the
final frozen Phase 4 source passed the same exact gate on 2026-08-07, including
all 81 expected outcome occurrences and 22 independently validated golden
messages. This remains candidate-development evidence rather than
release-candidate evidence.

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
