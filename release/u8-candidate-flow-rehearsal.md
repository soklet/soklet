# U8 candidate-flow evidence-graph rehearsal

Status: paper execution required before G3; actual U8 execution remains future
work

This document paper-executes the seven ordered U8 steps in
`SOKLET_4_0_COMPLETION_PLAN.md` section 6.7. It demonstrates that the evidence
flow is owned, producible, consumed, and acyclic before the D1p public cutover
is approved. It does not perform the final version transition, build or install
an artifact, modify or commit a downstream, pin a commit, freeze a candidate,
or create a release-gate `PASS` receipt.

The accountable owner throughout is the project owner. The implementation
owners are MCP-7 and lifecycle F1 for reconciliation and exact-version core
preparation, lifecycle F2 for the six clean downstream migrations, and U8 for
the pin, parity, and freeze integration. G4 is the decision owner for the
proposed immutable candidate. U9, not U8, owns immutable-candidate execution
and the ordered 29-gate `PASS` bundle.

The project owner is the sole creator of every core or downstream commit in
this route, including pre-downstream, post-pin, and recovery commits. MCP-7,
F1, F2, and U8 prepare and verify bytes and present them for owner review; they
do not create commits.

## How future identities are represented

No future commit, tree, timestamp, checksum, run ID, approval time, or receipt
value is asserted here. When a step below names a receipt field, its value is
**intentionally unresolved until that U8 command actually runs**. That phrase
means that the producer, type, and consumer are resolved while the future
value is not. It is not a sample value and must not be copied into evidence.

Every actual U8 receipt must be retained as immutable input to the G4 decision
and must identify, as applicable:

- the full core or downstream commit and root tree;
- the clean source directory or reserved clean worktree and its clean status;
- the literal `project.build.outputTimestamp` read from the committed POM;
- the exact command, working directory, environment, and pinned toolchain
  identity;
- every input path and input SHA-256 required by the command;
- every produced artifact path, size, and SHA-256;
- the release-manifest and planning-snapshot SHA-256 values; and
- the owner review or approval that accepted the result.

An execution log or local success without those bindings is not a receipt. A
U8 receipt records preparation or readiness only; it is never a candidate-gate
`PASS` receipt.

## Fixed repository and toolchain inputs

The core repository is the Soklet checkout whose candidate-tracked inputs
include this file. The two build source directories in step 3 and the post-pin
source directory in step 6 must be newly created, mutually independent, clean
checkouts of the exact named commit; none may reuse the working directory that
prepared the commit or another build's `target/` tree.

The U8 build recipe is the `candidate-build` recipe already enforced by
`scripts/validate-release-candidate.sh` and
`scripts/release-validation-evidence.mjs`:

```text
mvn -B -ntp -Dgpg.skip=true clean verify
```

It runs with the checksum-pinned `release/release-validation-manifest.json`
candidate toolchain: Amazon Corretto 17.0.20.8.1
(`java.version` 17.0.20, runtime 17.0.20+8-LTS) and Apache Maven 3.9.16.
The exact distribution digests in that tracked manifest are part of the
recipe. The three parity artifacts are:

```text
target/soklet-4.0.0.jar
target/soklet-4.0.0-sources.jar
target/soklet-4.0.0-javadoc.jar
```

The exact isolated-install goal is
`org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file`, with
`-Dfile` bound to the verified main JAR, `-DpomFile` bound to the committed
root `pom.xml`, `-DgeneratePom=false`, and `-DlocalRepositoryPath` bound to a
new empty private Maven repository. Installation must compare the installed
POM and main JAR byte-for-byte with their inputs.

The six protected paths remain read-only. Only the corresponding reserved
clean paths may be used in step 4:

| Gate | Recorded clean base | Protected path (never read for migration bytes) | Reserved clean U8 path |
|---|---|---|---|
| `barebones-app` | `0665e2808ecae930d73a59e91d47cc58bed1e9b5` | `/Users/Shared/ai-shared/soklet/barebones-app` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/barebones-app` |
| `soklet-servlet-javax` | `8bab7a04fc2b45eee1c50e9b989a7963eb2b9a9c` | `/Users/Shared/ai-shared/soklet/soklet-servlet-javax` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/soklet-servlet-javax` |
| `soklet-servlet-jakarta` | `5fadb19dbbbf11a8b74164eb9c9980d7c16b4f13` | `/Users/Shared/ai-shared/soklet/soklet-servlet-jakarta` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/soklet-servlet-jakarta` |
| `toystore-app` | `209781472b2d308cbc5538f2a7f956bc97b399b7` | `/Users/Shared/ai-shared/soklet/toystore-app` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/toystore-app` |
| `soklet-otel` | `d4a55b486c4b35a4d1b972ec9c5b4aa9d8d56020` | `/Users/Shared/ai-shared/soklet/soklet-otel` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/soklet-otel` |
| `soklet-website` (`soklet.com`) | `7717cea81776a1f6567f428cb0518702159fd5bc` | `/Users/Shared/ai-shared/soklet/soklet.com` | `/Users/Shared/ai-shared/soklet/.worktrees/soklet-4.0/soklet.com` |

A reserved path must either be absent before creation or already be an
owner-approved clean worktree at its recorded base. An unexplained existing
path blocks the step; it is not deleted, reset, cleaned, or reused.

## Acyclic evidence route

The successful route has these forward-only edges:

```text
reconciled tracked core inputs
  -> exact 4.0.0 source + committed literal output timestamp
  -> one pre-downstream core commit/tree
  -> clean build A + clean build B + identical three-JAR hashes
  -> byte-identical isolated rehearsal install
  -> six clean, tested, owner-approved downstream commits
  -> one core pin/READY commit with all 29 configurations READY
  -> clean post-pin build with the same timestamp/toolchain/recipe
  -> equality with both earlier three-JAR hash sets
  -> exact G4-frozen core commit/tree
```

The downstream commits do not depend on the future core pin commit. They
depend on the exact-version rehearsal artifact whose bytes were established by
the two pre-downstream builds. The core pin commit then depends on those six
downstream commits. Step 6 closes the construction loop without creating an
evidence cycle: it proves that the post-pin core tree emits the same three
artifact byte streams. If it does not, the route has failed and no G4 node is
created. Recovery starts a new U8 iteration, reruns every affected downstream,
repins new clean commits, and invalidates all receipts bound to the old input.

## Step 1 — reconcile the complete integration tree

**Owner.** MCP-7 and lifecycle F1 produce the integrated tracked state; the
project owner reviews the release-facing documentation, audits, and
non-executing G5 runbook.

**Command family.** Regenerate artifacts only through their checked-in
producers, then run their named verifier/self-test hosts. The aggregate
`scripts/verify-mcp-api-freezes.sh` must include all five read-only pairs:

- `conformance/official/verify-profile-evidence.mjs` and its self-test;
- `scripts/verify-mcp-metadata-builders.mjs` and its self-test;
- `scripts/verify-mcp-public-evolution.mjs` and its self-test;
- `scripts/verify-mcp-transport-dependencies.mjs` and its self-test; and
- `scripts/verify-mcp-roadmap-readiness.mjs` and its self-test.

The release-tooling command family includes
`node scripts/release-validation-self-test.mjs`,
`node scripts/import-release-harness-evidence.mjs --verify-config`, its
adversarial self-test, the matrix verifier/self-test, and the promotion
tooling self-tests. Focused and supported-JDK Maven, static-analysis,
Javadoc/doclint, fuzz, simulator, development-conformance, localization, and
interoperability checks cover every changed surface. These are integration
checks, not U9 gate receipts.

**Tracked inputs.** The producer consumes the landed exact source and tests;
`README.md`, `CHANGELOG.md`, `MCP.md`, `SECURITY.md`, public Javadocs, license
and NOTICE material; the prose 3.5.1-to-4.0 migration/support guidance; the
dated host/Inspector compatibility record and localhost recipe; the worked
application-owned OAuth resource-server example; `api/mcp/` freeze inputs;
the conformance/profile/matrix sources; `release/release-validation-manifest.json`;
`release/release-harness-contracts.json`; `release/version-transition-inventory.json`;
the D1p evidence contracts; release/promotion scripts; and the non-executing
G5 runbook. It also consumes the immutable U0 approval plus the exact umbrella,
MCP V11, and lifecycle V4 authority bytes through the candidate-tracked
`conformance/soklet-4.0-planning-authority.json` snapshot and its workspace
parity producer.

**Outputs and identities.** The output is one reviewed, internally coherent
tracked core tree in which generated artifacts, active documentation, matrix
wrappers, Phase 4/5/6 freezes, planning snapshot, release tooling, five
harness contracts, release-readiness documentation, attribution/license audit,
narrowed security claims, and unexecuted G5 runbook agree. Its future commit,
tree, generated digests, and review time are intentionally unresolved until
U8.

**Consumer.** Step 2 consumes this tracked tree for the final version
transition. G4 later consumes the retained reconciliation review, planning
snapshot identity, release-readiness artifacts, audits, and runbook.

**Invalidation edge.** A generated/source mismatch, authority-snapshot parity
failure, missing verifier pair, changed five-role `api-freeze` contract,
unresolved matrix row, non-READY harness contract, missing required release
document, unattributed third-party byte, overstated security claim, or
unregistered tracked drift invalidates this step and all later U8 work. The
declared step 2 final-version transition and step 5 pin/readiness change are
registered producer edges, not drift; each is valid only after its mandated
rechecks pass and its replacement tree is reviewed.

**Receipt.** Retain the reconciliation command/log index, exact tracked commit
and tree, planning-snapshot SHA-256, generator/verifier results, documentation
review, license/NOTICE audit, security-claim review, and G5-runbook review. All
future values are intentionally unresolved until U8. The receipt says
“reviewed integration input,” not `PASS`.

## Step 2 — perform the exact final-version transition

**Owner.** Lifecycle F1 prepares the transition inside U8; MCP-7 rechecks
identity-bearing MCP artifacts; the project owner reviews the resulting exact
version and literal timestamp and alone creates the pre-downstream core commit.

**Command family.** Change the root and every reactor/module POM from
`4.0.0-SNAPSHOT` to exact `4.0.0`, regenerate every active version-bearing
identity through its producer, and run:

```text
node scripts/verify-version-transition-inventory-self-test.mjs
node scripts/verify-version-transition-inventory.mjs --stage final
```

Then rerun the API/freeze, matrix, release-tooling, harness, and documentation
checks from step 1 that consume version-bearing bytes.

**Tracked inputs.** Step 1's reviewed tree; all root/module POMs; the complete
`release/version-transition-inventory.json`; active fixtures, goldens,
conformance inputs, manifests, artifact/bundle/URL names, receipt expectations,
diagnostics, workflow inputs, and documentation identities. Released `3.5.1`
compatibility inputs, historical/fixture literals, and unrelated tool versions
remain preserved according to the inventory.

**Outputs and identities.** The output is exact-version source with no active
snapshot identity and one `pom.xml` `project.build.outputTimestamp` whose
committed value is a literal ISO-8601 UTC instant. An environment reference,
clock expression, unresolved property, or property-derived value is forbidden.
The literal timestamp, finalization commit, and tree are intentionally
unresolved until U8; the timestamp will be chosen once and committed, not
computed during either build.

**Consumer.** Step 3 checks out the resulting proposed pre-downstream
commit/tree twice and obtains its timestamp only from the committed POM.

**Invalidation edge.** A surviving active snapshot, changed preserved literal,
nonliteral timestamp, version-bearing generated drift, or any later
source/artifact-bearing edit invalidates the transition and every build or
downstream receipt derived from it.

**Receipt.** Retain the final-stage inventory/self-test logs, exact commit and
tree, the literal timestamp as read from tracked `pom.xml`, the classified
preservation result, and the hashes of regenerated version-bearing inputs.
Those future values are intentionally unresolved until U8. This is a source-
finalization receipt, not publication or candidate `PASS` evidence.

## Step 3 — prove two clean builds and install the rehearsal bytes

**Owner.** Lifecycle F1 is the build and install producer; the project owner
reviews the recipe and the pairwise hash comparison.

**Command family.** Create two independent clean source directories with
`git clone --no-checkout --no-hardlinks`, detach each at the same full proposed
pre-downstream commit, and verify the same root tree and clean status in both.
In each directory, with the pinned Corretto 17 and Maven distributions, run
exactly:

```text
mvn -B -ntp -Dgpg.skip=true clean verify
```

Hash the main, sources, and Javadoc JAR in each directory. Require build A's
main/sources/Javadoc SHA-256 tuple to equal build B's tuple component by
component. Only after that equality succeeds, install one of those already-
built main JARs and the committed POM into a newly created isolated Maven
repository with the pinned `maven-install-plugin:3.1.4:install-file` mapping
defined above. Do not invoke another build to install it.

**Tracked inputs.** Step 2's exact pre-downstream commit/tree, literal
timestamp, `pom.xml`, all compiled source/resources/tests, build plugins and
configuration, checksum-pinned toolchain declarations, and every tracked
input reached by `clean verify`. Neither source directory may see another
directory's untracked files, local `target/`, or local Maven output as source
input; dependency caches do not replace the clean-source and exact-toolchain
identity checks.

**Outputs and identities.** Two build logs and two independently produced
triples of `soklet-4.0.0` main/sources/Javadoc JARs; one agreed triple of
SHA-256 values; and one isolated repository containing a POM and main JAR that
compare byte-for-byte with the reviewed build output. The commit/tree,
timestamp, artifact sizes/hashes, directory identities, and install-repository
identity are intentionally unresolved until U8.

**Consumer.** Step 4 consumes only the checksum-matched installed
`com.soklet:soklet:4.0.0` POM/main JAR, except `barebones-app`, which consumes
the same main JAR as its exact vendored file, and `soklet-website`, which
consumes the exact documented source identity rather than a Maven coordinate.
Step 6 consumes both three-hash tuples as its required equality target. G4
consumes both build receipts and the install rehearsal receipt.

**Invalidation edge.** A different commit/tree, dirty checkout, recipe or
toolchain difference, nonliteral timestamp, missing artifact, any pairwise
hash mismatch, failed byte comparison after install, or a later artifact-
bearing core edit blocks downstream work and invalidates the step. No
“equivalent” rebuild may silently replace either reviewed build.

**Receipt.** Retain separate clean-build A and clean-build B receipts plus one
isolated-install rehearsal receipt. Each build receipt records the exact
commit/tree, literal timestamp, complete recipe/toolchain, clean status, and
all three artifact paths/sizes/SHA-256 values. The install receipt records the
coordinate, POM/main-JAR input and installed hashes, byte comparisons, and the
same three-JAR tuple for provenance. All values are intentionally unresolved
until U8. None is a U9 `candidate-build` or `isolated-install` `PASS` receipt.

## Step 4 — reconstruct and commit all six clean downstream migrations

**Owner.** Lifecycle F2 owns reconstruction and validation. The project owner
alone creates and approves every clean downstream commit. Each repository's
migration producer works only at its reserved clean path and presents verified
bytes to the project owner without committing them.

**Command family.** Create or verify each reserved path with
`git worktree add --detach` using the exact path/base pair in the table above,
then reconstruct the approved migration without copying bytes from the
protected path. Validate against the step 3 rehearsal input using the
checked-in release-validator command family:

| Gate | Required rehearsal command family and binding |
|---|---|
| `barebones-app` | Compile with `javac --release 17 -parameters -processor com.soklet.SokletProcessor -classpath soklet-4.0.0.jar`, then perform the noninteractive loopback start/probe/graceful-stop/port-release checks. Record the exact vendored main-JAR SHA-256. |
| `soklet-servlet-javax` | `mvn -B -ntp -Dgpg.skip=true clean verify`, then `mvn -B -ntp -Dgpg.skip=true -Dsoklet.version=4.0.0 clean verify`, with the candidate leg forced to the isolated repository. Prove default `com.soklet:soklet:3.1.1` and candidate `com.soklet:soklet:4.0.0` plus both resolved JAR hashes. |
| `soklet-servlet-jakarta` | The same released-default and exact-candidate command family and effective-coordinate/hash proof as the `javax` repository. |
| `toystore-app` | Run `mvn -B -ntp -Dgpg.skip=true clean verify`, then `mvn -B -ntp -Dgpg.skip=true -Dsoklet.version=4.0.0 clean verify`, both against the isolated repository. On the final F2 tree, prove both legs resolve the exact candidate coordinate/hash because the tracked property default is exact `4.0.0`. Retain the separate Milestone-R pinned-base receipt that proved the former default `3.5.1` and candidate override; do not misstate that historical default as the final F2 default. |
| `soklet-otel` | `mvn -B -ntp -Dgpg.skip=true -Dsoklet.version=4.0.0 clean verify` against the isolated repository, including the exact six-value shutdown-outcome vocabulary and effective coordinate/hash proof. |
| `soklet-website` | `npm ci --ignore-scripts`, `npm run lint`, `npm run ssg-build`, and `git diff --exit-code`; record the exact documented core identity plus source, generated-distribution, lockfile/workflow, and generator hashes. No Maven coordinate is fabricated. |

For every Maven consumer, the rehearsal additionally uses
`-Dmaven.repo.local` bound to step 3's isolated repository and the checked-in
effective-POM/JAR verifier so a successful no-op override cannot qualify. Once
the migration and its evidence are reviewed, the project owner creates its
commit in that clean downstream repository and requires the worktree to be
clean at the new commit.

**Tracked inputs.** The six recorded bases and repositories; owner-approved
migration specification; each downstream's tracked source, POM or lockfile,
validator hook, generated-content rules, and preserved released-default
dependency where required; step 3's exact POM/main-JAR or documented-source
identity; and the candidate-tracked downstream evidence contract in
`scripts/release-validation-evidence.mjs`.

**Outputs and identities.** Six clean owner-approved commits and root trees,
six clean-status proofs, six dispatchable validation hooks, and six rehearsal
input/result records. Artifact consumers record the effective exact
`com.soklet:soklet:4.0.0` coordinate and step 3 main-JAR SHA-256 (or the exact
vendored JAR for `barebones-app`); the website records its source/generated
identity. All six future commit/tree and generated checksum values are
intentionally unresolved until U8.

**Consumer.** Step 5 writes these six exact commits, checksums, and evidence-
contract bindings into `release/release-validation-manifest.json`. Step 6 uses
their consumed main-JAR checksum to decide whether any downstream must be
rerun. G4 consumes the six owner approvals and clean commit/tree receipts.

**Invalidation edge.** Reading migration bytes from a protected path; a wrong
base; unexplained or dirty reserved path; missing owner approval; dirty result;
no-op version override; wrong effective coordinate/JAR; generated website
drift; failed validator hook; or changed rehearsal input invalidates that
downstream receipt. A change to any downstream commit after pinning invalidates
steps 5–7 and returns to U8.

**Receipt.** Retain one preparation receipt per downstream with repository,
recorded base, reserved path, creation command, new commit/tree, clean status,
review approval, exact commands/toolchains, input core identity, effective
coordinate and JAR SHA-256 or website/vendored equivalent, validator outputs,
and evidence-contract identity. Future values are intentionally unresolved
until U8. These are U8 readiness receipts, not the six U9 downstream `PASS`
receipts.

## Step 5 — pin downstreams and make all 29 configurations READY

**Owner.** U8 prepares one reviewed core change. Lifecycle F2 supplies the six
downstream records; MCP-7/U7 supplies and revalidates the five harness
contracts; the project owner reviews the complete pin/readiness change and
alone creates the post-pin core commit.

**Command family.** Update `release/release-validation-manifest.json` to pin
the six step 4 commits and required checksums, change their six rows to
`READY`, and preserve all five U7 harness rows as `READY`. Keep the ordered
29-gate universe unchanged. Run:

```text
node scripts/import-release-harness-evidence.mjs --verify-config
node scripts/import-release-harness-evidence-self-test.mjs
node scripts/release-validation-self-test.mjs
node scripts/release-validation-evidence.mjs validate-config release/release-validation-manifest.json --require-ready
```

The self-tests must still reject 28- or 30-row manifests, blocked status,
nonblank reasons, missing pins/checksums, and registry/dispatch mismatch. The
five harnesses remain the existing `fuzz-nightly-history`,
`soak-nightly-history`, `operational-history`, `release-scans`, and
`mcp-benchmarks` rows; no new gate ID is introduced.

**Tracked inputs.** Step 4's six exact commit/tree and validation records;
`release/release-validation-manifest.json`;
`release/release-harness-contracts.json`; the importer, evidence helper,
candidate validator, promotion consumer, workflow dispatches, and their self-
tests; the ordered 29 evidence-contract identities; and the exact step 3
main-JAR checksum used by downstream artifact consumers.

**Outputs and identities.** One reviewed post-pin core commit/tree; one
candidate-tracked manifest with exactly 29 `READY`, zero blocked rows, and an
empty reason wherever the schema requires it; six immutable downstream pins
and checksums; five executable harness dispatches; and a raw manifest SHA-256.
The post-pin core commit/tree and manifest/checksum values are intentionally
unresolved until U8.

**Consumer.** Step 6 checks out this post-pin commit/tree and rebuilds it. Step
7 and G4 consume the exact manifest bytes, readiness report, harness dispatch
proofs, and all six pins.

**Invalidation edge.** Any missing/changed pin or checksum, non-READY row,
nonblank reason, gate count/order drift, absent harness dispatch, registry/
manifest/evidence mismatch, self-test weakening, manifest edit, or downstream
commit change invalidates this step and all later receipts. A manifest-only pin
change still requires the post-pin build; parity is proved, never assumed.

**Receipt.** Retain the exact post-pin commit/tree, manifest path/raw SHA-256,
canonical ordered readiness report, `--require-ready` log, all negative self-
test results, five harness registry/dispatch identities, and six downstream
pin/checksum bindings. Future values are intentionally unresolved until U8.
`READY` means configured and dispatchable; this receipt contains no `PASS`.

## Step 6 — prove same-recipe post-pin three-JAR parity

**Owner.** U8/lifecycle F2 owns the rebuild and invalidation decision; the
project owner reviews the equality result and any required downstream rerun.

**Command family.** Create another new clean source directory with
`git clone --no-checkout --no-hardlinks`, detach it at step 5's exact post-pin
core commit/tree, and verify its clean status. Read the same committed literal
timestamp, use the same pinned Corretto 17/Maven distributions, and run
exactly:

```text
mvn -B -ntp -Dgpg.skip=true clean verify
```

Hash the post-pin main, sources, and Javadoc JARs and compare each SHA-256 to
the corresponding values in both step 3 build receipts. Also compare the
post-pin main-JAR SHA-256 to every artifact-consuming downstream rehearsal
record.

**Tracked inputs.** Step 5's exact post-pin commit/tree and manifest; the same
POM timestamp, build recipe, toolchain distribution pins, source/resources,
plugins, and dependency inputs used in step 3; both step 3 clean-build
receipts; and all step 4 downstream consumed-artifact bindings.

**Outputs and identities.** One post-pin clean-build receipt and a component-
by-component equality record showing one main, one sources, and one Javadoc
SHA-256 shared by build A, build B, and the post-pin build. The post-pin
artifact values are intentionally unresolved until U8, but at execution they
must equal—not merely correspond to—the two earlier tuples.

**Consumer.** Step 7 and G4 consume the post-pin receipt and equality record.
U9 later rebuilds only from the G4-frozen tree and must reproduce this same
approved tuple before any candidate receipt is accepted.

**Invalidation edge.** Any timestamp, recipe, toolchain, tree, or artifact
hash difference blocks G4. If the main JAR differs, every artifact-consuming
downstream is affected; rerun it against the rebuilt artifact, then have the
project owner create and approve replacement clean commits and a new U8 core
pin commit before repeating step 6. A sources- or Javadoc-JAR difference also blocks G4 and
invalidates every receipt that consumed the changed artifact or recipe. No
old receipt survives changed input bytes.

**Receipt.** Retain the exact post-pin commit/tree, clean status, literal
timestamp, full recipe/toolchain, all three paths/sizes/SHA-256 values, both
three-way comparisons, downstream main-JAR comparison, and an explicit list
of invalidated/rerun receipts (empty only if no input changed). Values are
intentionally unresolved until U8. This is reproducibility/readiness evidence,
not a U9 candidate `PASS`.

## Step 7 — freeze the proposed immutable-candidate source at G4

**Owner.** U8 proposes the exact source identity. The project owner acting at
G4 approves or rejects that identity; U9 is only authorized after approval.

**Command family.** From the clean step 5/6 core checkout, use
`git rev-parse --verify HEAD`, `git rev-parse HEAD^{tree}`, and
`git status --porcelain --untracked-files=all`, then read and retain the
tracked manifest SHA-256, planning-snapshot SHA-256, and literal timestamp.
Re-run the fail-closed final readiness checks whose tracked inputs could have
changed, including final version inventory, API/freeze aggregate, matrix,
harness/importer, release-tooling, downstream-dispatch, and
`release-validation-evidence.mjs validate-config ... --require-ready`. Do not
invoke `scripts/validate-release-candidate.sh` as a 29-gate candidate run in
U8; that belongs to U9 after G4.

**Tracked inputs.** The exact post-pin core commit/tree; step 1's planning
snapshot, release-readiness documents, audits, narrowed security claims, and
unexecuted G5 runbook; step 2's literal timestamp and final-version receipt;
step 3's two clean-build and install rehearsal receipts; step 4's six clean
downstream approvals; step 5's manifest/readiness/harness receipt; and step
6's post-pin three-JAR parity receipt.

**Outputs and identities.** The output is one G4 decision binding the exact U8
core commit/tree, manifest SHA-256, six downstream commits/checksums, planning
snapshot, literal timestamp, exact build recipe/toolchains, two-clean-build
and post-pin main/sources/Javadoc parity receipts, release-readiness artifacts,
five harness dispatches, and exact 29-row readiness report. Every future
identity and the approval time are intentionally unresolved until U8/G4.

**Consumer.** U9 consumes only this frozen decision. Its first candidate build
must use the approved tree/timestamp/toolchain/recipe and reproduce the
approved three-JAR tuple before it runs/imports the ordered 29 candidate-bound
gates. The separately authorized G5 runbook may consume only the later U9-
accepted candidate and 29 `PASS` receipts.

**Invalidation edge.** A dirty checkout; changed commit/tree, source,
generated artifact, timestamp, planning snapshot, manifest, harness,
downstream pin/commit, documentation/audit/runbook input, or parity receipt
after G4 invalidates the proposed candidate. The program returns to U8 or the
earlier semantic owner, produces a new stable tree, obtains a new G4 approval,
and restarts U9. Candidate history is not inherited by a later tree.

**Receipt.** The durable G4 approval is the sole freeze receipt. It records the
complete binding above and authorizes U9; it does not assert that an immutable
candidate was built, that any release gate passed, or that promotion was
authorized. Its actual commit/tree, hashes, timestamp, and approval identity
are intentionally unresolved until G4.

## Blocking conditions and rehearsal conclusion

This paper execution resolves an owner, producer command family, tracked input
set, output identity, consumer, invalidation edge, and receipt contract for
all seven steps. The successful dependency graph is acyclic. The only route
that points back to earlier work is failure recovery; failed parity or changed
input creates a new U8 iteration and invalidates old receipts rather than
closing a cycle with stale evidence.

G3 remains blocked if this checked-in rehearsal is absent or changed without
regeneration of its D1p tracked-blob binding, or if review discovers an
unowned producer, missing consumer, untracked live gate input, impossible
build/install/downstream command, or circular parity requirement. G4 remains
blocked until the future values intentionally left unresolved here are
produced by actual clean U8 execution and all 29 manifest rows are `READY`.

U8 readiness is deliberately weaker than U9 acceptance:

- U8 may retain clean-build, install, downstream-preparation, readiness, and
  parity receipts, but none has release-gate `PASS` status.
- `READY` means all 29 configurations are pinned and dispatchable with blank
  blocking reasons; it does not mean they ran against the immutable candidate.
- U9 alone rebuilds from the G4-frozen source, verifies the approved artifact
  tuple, and collects 29 typed candidate-bound `PASS` receipts.
- G5 remains a separate explicit owner authorization even after U9 succeeds.

Therefore this rehearsal establishes that U8 can be executed without
cryptographic self-reference or evidence borrowing. It does not claim that
U8, U9, G4, or G5 has occurred.
