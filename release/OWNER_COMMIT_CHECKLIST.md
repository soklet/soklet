# Owner commit and publication-preparation checklist

This is a review checklist, not permission for an agent to stage, commit, push,
tag, publish, or deploy. All commits remain owner-controlled. Preparation is
not acceptance, and acceptance is not G5 publication approval. Preserve the
approved K/L order and the existing 25-gate universe.

The main `soklet.com` website is independently owner-deployed at any time.
It has no candidate gate, release pin or frozen hash, required release tag,
deployment-order requirement, or G4/U9/G5 dependency. Website changes do not
require a new library candidate. The four packaged Javadoc sites retain their
artifact provenance and publication contracts.

## Review each repository independently

Apply this checklist to `soklet`, `soklet-servlet-javax`,
`soklet-servlet-jakarta`, `soklet-otel`, `barebones-app`, and `toystore-app`,
plus the four generated sites `javadoc.soklet.com`,
`javax.javadoc.soklet.com`, `jakarta.javadoc.soklet.com`, and
`otel.javadoc.soklet.com`. From each intended repository root, inspect:

```sh
git status --short --untracked-files=all
git branch --show-current
git remote -v
git diff --stat
git diff --check
git diff
git diff --cached
```

Confirm the repository, intended branch, owner-reviewed scope, and destination
before staging. Preserve unrelated work. A branch name does not prove which
remote branch is the default or whether a hosting integration auto-deploys it;
check the remote and actual hosting settings. In particular, verify both
servlet repositories deliberately, rather than assuming their current `main`
checkout is an unpublished feature branch. No deployment policy
was inferred from the absence of a checked-in workflow.

Use explicit reviewed paths for staging, including new files. `git add -u`
and `git commit -a` omit untracked files. Do not substitute a blanket `git add -A`
without first reviewing every included path. After owner staging, inspect:

```sh
git diff --cached --name-status
git diff --cached --check
git diff --cached
git diff --name-status
git ls-files --others --exclude-standard
git status --short
```

The staged status is expected to be nonempty before committing. Require the
intended changes to be staged, no omitted required files, and no unrelated
additions—not an empty pre-commit `git status`. Review binary provenance and
hashes separately; a text diff cannot verify a vendored JAR.

Unchanged tracked files are already in the index and will remain in the resulting
commit. Do not re-stage them merely because an older checklist called them new;
verify presence and contents in the final commit instead.

## Prepare the validation workflow before freezing a candidate

GitHub permits manual dispatch only after the workflow is registered on the
repository's default branch. Before relying on `.github/workflows/release-validation.yml`,
the owner must verify that registration and the reviewed workflow version. If
necessary, make a separately reviewed, non-deploying default-branch preparation
change first. A workflow visible only on a feature branch is not a runnable
release gate. Keep that core workflow preparation separate from independently
managed website work.

Finish code, dependency, release-note, documentation, and packaged-Javadoc
repairs before G4. Build and independently reproduce the candidate artifacts;
import the exact packaged Javadoc JARs into the four site previews and verify
full payload parity. Then obtain owner-created, retrievable downstream commits
and update the five candidate pins. The manifest must name the actual final
commits, not earlier migrations or moving branch names. CI's pre-downstream core
source pin is a separate acyclic build input; do not replace it with a future
final core tag that depends on those same downstream receipts.

G5 does not make these edits or regenerate artifacts. If a repair changes any
approved release input, prepare and accept a new candidate before publication.
Main-website content is outside those release inputs. Keep the 4.0.0 changelog
header `Unreleased` until the owner's tagging step. If that step changes the
tracked release notes or date, include those bytes in a newly accepted candidate
before promotion; do not edit an already accepted source tree during promotion.
Changing the changelog header or date also changes the version-transition census.
Regenerate `release/version-transition-inventory.json` from the final reviewed
bytes and run `node scripts/verify-version-transition-inventory.mjs --stage final`
before creating the candidate commit. Review inventory differences together with
the release-note change.

Before freezing a candidate, inspect every `approvedAt`/`expiresAt` in
`release/release-scan-exceptions.json`. The September 16 approvals expire
2026-10-16 at 14:26:19 UTC. Obtain fresh owner approval for any renewed exception,
keep each lifetime within 30 days, and include the approved registry in the
candidate. An agent must not manufacture owner approval by advancing timestamps.


## Load-bearing new files in the September 13–14 handoff

This explicit list prevents omission; it is not a substitute for the complete
current status/diff. Check every listed path is present in the owner's index
and then in the resulting commit. Existing modified production, test,
configuration, documentation, and generated files must be reviewed as well.

### Core: `soklet`

- `src/test/java/com/soklet/HeaderLocaleTests.java`
- `src/test/java/com/soklet/internal/microhttp/RequestParserFramingTests.java`
- `conformance/official/public-fixture-test-src/com/soklet/conformance/McpChunkedHttpClient.java`
- `conformance/official/public-fixture-test-src/com/soklet/conformance/McpTaskNotificationSocketDriver.java`
- `conformance/official/UPSTREAM_DEPENDENCY_REVIEW_2026-09-13.md`
- `release/PLANNING_AUTHORITY_DRIFT_2026-09-13.md`
- `release/OWNER_COMMIT_CHECKLIST.md`
- `release/scripts/install-pinned-gradle-linux-x64.sh`
- `scripts/verify-consumer-ci-self-test.mjs`
- the entire reviewed `verification/consumer-build/` fixture, including its
  build files, README, integrated HTTP/MCP/SSE Java sources, mutation helper,
  verifier, library, and self-test

The tests, executable helpers, and fixture sources are consumed by builds,
CI, conformance, or exact inventory checks. The review documents also carry
required release decisions and public documentation links; dropping them is
not harmless documentation cleanup.

### Servlet adapters

- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/BodylessResponseTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/BodylessResponseTests.java`
- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/ProtocolLocaleTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/ProtocolLocaleTests.java`
- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/ContextResourceUnionTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/ContextResourceUnionTests.java`
- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/GeneratedResponseWireTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/GeneratedResponseWireTests.java`
- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/ResponseCorrectnessTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/ResponseCorrectnessTests.java`
- `soklet-servlet-javax/src/test/java/com/soklet/servlet/javax/SessionBindingLifecycleTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/SessionBindingLifecycleTests.java`
- `soklet-servlet-jakarta/src/test/java/com/soklet/servlet/jakarta/JakartaResponseContractTests.java`

Review each adapter's POM, request/response conversion, session/context behavior,
README, CONTRIBUTING, and Javadocs with its tests. Include duplicate-preserving
parameters, exact session-cookie matching, obsolete HTTP dates, safe generated
errors and redirects, writer reset state, reentrant session listeners, classpath
resource unions, and the intended Jakarta-specific API differences. Confirm the
installation examples include the Servlet API as well as core. Locale-independent
request URL and redirect authority tests remain required.
Both 2.0.0 adapters require core 4.0.0; do not reinstate the superseded
3.x compatibility leg.

### OTel integration

- `soklet-otel/src/main/java/com/soklet/otel/ServerTypeAttribute.java`
- `soklet-otel/scripts/verify-soklet-candidate.py`
- `soklet-otel/scripts/test_verify_soklet_candidate.py`

This package-private helper supplies the shared, explicit `http`/`sse`/`mcp`
attribute vocabulary for both metrics and spans. Review it with both collectors,
their tests, and the telemetry migration documentation; omitting it breaks the
build. Core now renames `ServerType.STANDARD_HTTP` to `ServerType.HTTP` without
an alias. Review its source and API snapshot changes with the migration guide,
changelog, dependent integrations, and built-in Prometheus/OpenMetrics coverage
for `server_type="HTTP"`. The explicit OTel attribute value remains `http`.

The CI verifier and its tests must accompany the workflow change. Both the
manual default and automatic fallback must select the same reviewed full core
commit SHA. The verifier checks that identity and the declared core dependency
before building; do not restore an arbitrary branch checkout or a version
override that masks an incompatible baseline.

### Independent website content checks

These checks support ordinary website quality and source-link review. They do
not supply a release gate, pin the website, or constrain when the owner deploys
it. The repository's README describes the independent build workflow.

- `soklet.com/scripts/verify-core-blob-links.mjs`
- `soklet.com/scripts/verify-core-blob-links-self-test.mjs`
- `soklet.com/scripts/verify-mcp-api-reference.mjs`
- `soklet.com/scripts/verify-mcp-api-reference-self-test.mjs`
- `soklet.com/scripts/verify-site-presentation.mjs`

Review these with the website's package scripts and README. Discovery includes
all source links, including navigation, and checks the generated page/HTML
link sets without a hardcoded document list. Run `npm run test:core-links` and
`npm run check:core-links -- --core /absolute/soklet --built-site /absolute/soklet.com/dist`
after generation. This offline working-tree preflight does not establish public
availability; add `--core-ref FULL_40_CHARACTER_COMMIT_SHA` to check the intended
owner-committed core tree instead.

Run `node scripts/verify-site-presentation.mjs /absolute/soklet.com/dist`
from the website checkout after generation; it checks metadata, pager links,
icons, and dark 404 presentation across all 32 internal pages.

Also run the API-reference guard and its self-tests. Review the API-freeze
counts, type/member links, provisional Tasks maturity, authored-schema wording,
and regenerated full-text output together; source Java signatures being frozen
does not make provisional protocol features final.

### Generated Javadoc sites

In each of the four site repositories, include `scripts/import-javadoc.py`,
`scripts/test_import_javadoc.py`, `.gitignore`, the README, and the complete
reviewed generated `dist` changes. Use the helper to import a packaged Javadoc
JAR into a new preview directory with an independently verified expected
SHA-256. Do not rebuild Javadocs independently from source during publication.

Review exact artifact provenance, release version, API/deep links, and preview
contents before replacing `dist`; preserve the old tree in a separate backup.
Local generation is preparation only. Before deployment require the final
accepted Javadoc JAR to match the reviewed import and verify hosting triggers.
The Javadoc sites are still published after the accepted libraries and examples;
they are not additional candidate gates. Their order places no constraint on
the independently deployed main website.

### ToyStore

- `toystore-app/src/test/java/com/soklet/toystore/ConfigurationTests.java`
- `toystore-app/src/test/java/com/soklet/toystore/mcp/ToyStoreMcpDockerSmokeTests.java`
- `toystore-app/src/test/java/com/soklet/toystore/mcp/ToyStoreDockerSmokeConfigurationTests.java`
- `toystore-app/scripts/VerifyDockerSmokeReport.java`

Review these with the bind/allowlist configuration, application wiring, Docker
configuration, README, and mirrored website instructions. The Docker smoke
requires the documented exact opt-in and an available daemon; require its
actual execution with zero skipped tests, not merely a successful Maven exit.
Use the fresh-report-directory procedure and its report-verification helper;
an old successful report must not satisfy a new skipped or unexecuted run.
The optional HTTP smoke-port override is test-only and must remain restricted
to a validated decimal port on `127.0.0.1`; it does not change MCP's bound-port
and Host-authority checks. Run its configuration tests without the Docker opt-in.

## Verify the committed tree, not just the working copy

After each owner commit, inspect `git status --short`, `git show --stat HEAD`,
and `git show --name-status HEAD`. A clean checkout is required for candidate
identity checks; do not delete unrelated work merely to obtain clean status.
Use an isolated checkout of the exact commit when necessary. For each required
new path, `git cat-file -e HEAD:<repository-relative-path>` must succeed.

Run the applicable checks from that committed tree. Core checks include the
complete API-freeze chain, lifecycle inventory verifier and self-test,
version-transition inventory, matrix closure, and release-tooling self-tests.
The consumer CI contract and consumer fixture self-tests must also run from
the commit. Run supported-JDK behavioral suites, generated-document checks,
and downstream checks appropriate to each repository; a source `@Test` count
is not proof of test execution or skipped-test policy. Retain actual commands,
exit results, reports, commit/tree identities, and tool/artifact hashes.

Do not regenerate the sealed D1p manifests or edit the historical contract to
make current checks pass. Review the
[historical-source dispositions and current semantic repins](PLANNING_AUTHORITY_DRIFT_2026-09-13.md)
instead. Apply the conformance toolchain's accepted
[September 27 dependency disposition](../conformance/official/DEPENDENCY_REPIN_DISPOSITION_2026-09-27.md)
and require its fresh candidate-run audits; the disposition makes the configured
gate runnable but does not supply a candidate PASS receipt.

## Preserve the acyclic K/L preparation order

1. Finish and review artifact-affecting core changes, then obtain the owner's
   pre-downstream core commit.
2. Build that exact commit in two independent clean checkouts with the pinned
   canonical Linux JDK 21 `mvn -B -ntp -Dgpg.skip=true clean verify` recipe.
   Require main, sources, and Javadoc JAR equality and install the exact main
   JAR/POM into an isolated rehearsal repository.
3. Put those canonical main-JAR bytes into barebones; validate all five
   downstreams, the six packaged-consumer combinations (Maven, Gradle, and
   direct javac on Java 21 and 25), and preparatory client smoke against the
   canonical artifact. Finish tracked launch claims
   and obtain the owner's reviewed downstream commits. Confirm exact commits
   can be retrieved by the runner; do not push automatically.
4. Repin the five downstream commits and associated metadata only after their
   tested identities and executable paths are satisfied. Do not clear a
   security block merely because commits now exist.
5. Obtain the owner's final core candidate commit, rebuild with the identical
   recipe, and require all three JARs to equal the pre-downstream artifacts and
   the main JAR to equal barebones' vendored bytes. A mismatch returns to
   preparation and affected downstream validation, not a parity waiver.
6. Freeze the exact candidate commit/tree, run the required producer/review/
   validation sequence and all 25 typed PASS receipts, and retain final client
   smoke outside the immutable source tree. A tracked correction requires a
   new candidate and acceptance run.

No commit, green development matrix, or local report replaces canonical Linux
parity, the required owner provenance/security dispositions, immutable-
candidate evidence, or separate publication approval.

## Keep retrieval and deployment separate

Before any owner-authorized push, inspect branch protections, CI triggers,
hosting integrations, release automation, and the exact target branch. Choose
an intentionally non-deploying branch or hold the triggering push where
necessary. Repository publication and application/Javadoc deployment are
different actions; do not assume either happens automatically from a local
commit.

Under separate G5 authorization, preserve the established publication order:
accepted core and public resolution, both adapters, OTel, examples, and four
Javadoc sites. Record the actual hosting trigger decision and public link
results in the [G5 authorization and deployment evidence](G5_RELEASE_RUNBOOK.md),
without editing the accepted candidate during promotion.

For independent website work, use its own content checks and inspect actual
public URLs, not merely local paths. The owner may deploy before, during, or
after library publication without a release tag or candidate reacceptance.
Check every dynamically discovered core
`/blob/master/` target, including page links and navigation—not only newly
added documents. From the website checkout, run its documented checker with
`--core /absolute/soklet --core-ref FULL_40_CHARACTER_COMMIT_SHA --built-site /absolute/soklet.com/dist --live`
and retain its JSON output. It requires public HTTP 200 blob pages and public
master file bytes matching the intended commit. A feature-branch commit does
not make a default-branch link valid; a local-only pass is not a substitute.
Check exact public Maven
coordinates, download targets, and Javadoc deep links as well. These link checks
are ordinary content-quality checks; their results and website edits do not
enter the library's G5 receipt set or invalidate its candidate.
