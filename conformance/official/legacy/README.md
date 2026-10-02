# Selected 2025 conformance checks

This runner uses the unchanged checksum-pinned official alpha.11 CLI and a
public-API-only Soklet fixtures. Development mode is the default. Release mode
is required by the version-3 candidate-conformance gate, alongside the existing
modern 46-scenario and eight task-notification checks. Passing the selected
legacy checks does not establish full 2025 conformance or named-host behavior.

## Profiles

| Profile | Revisions | Selected cases | What the checks establish |
| --- | --- | --- | --- |
| `stateless-baseline` | June and November | The existing 14 initialization/ping/tool/prompt/resource cases per revision | Preserves the exact optional-session `INFO`, catalog counts and wire-schema checks |
| `stateless-expansion` | June and November | `completion-complete`, `tools-call-with-progress` | Exact suggestions and three matching-token progress updates plus one whole result |
| `session-enabled` | June and November | `server-initialize`, `server-session-lifecycle`, `resources-subscribe`, `resources-unsubscribe` | Visible-ASCII session ID, initialized `202`, DELETE `204`, subsequent `404`, and successful subscription RPCs |
| `session-enabled` | November only | `server-sse-multiple-streams` | Three concurrent successful JSON responses; the official SSE check remains exact `INFO` |

The default runs all **41** reviewed revision/profile/scenario combinations
sequentially, followed by **15 HTTP runtime contracts per revision**.
`--profile` selects one official profile in development mode. Each scenario gets a fresh
loopback fixture process with a 256 MiB heap and two processors. Processes,
output and shutdown are bounded. Every raw official check ID and status must
match its reviewed vector; extra checks, skipped cases, changed `INFO` meanings
or reduced schema coverage fail closed. No `--force` or skip suppression is used.

The session fixture explicitly permits one anonymous test owner. The watched
resource exists only in that profile; the baseline keeps its original two
listed resources. Production authorization, owner isolation and OAuth remain
separate application/local-test obligations.

The official lifecycle and concurrent-response scenarios use raw HTTP or an
uninstrumented SDK client. They emit no automatic schema-validation row; their
receipt records zero schema-validated messages. They must not be represented
as independent schema coverage or SSE streaming proof.

## Remaining supplements and exclusions

Official subscribe/unsubscribe cases check successful RPC acceptance; they do
not observe updates or prove that unsubscribe stops delivery. The integrated
HTTP supplement checks exact/template URI delivery, freshly listed catalogs,
GET credential refresh, independent historical URI credentials, duplicate and
fresh resubscribe, quiet renewal without replay, dirty-gap reconnect, unsubscribe,
revocation, a Bearer `invalid_token` challenge, authorized DELETE, legacy wire
fields, and clean shutdown. It uses native HTTP against a public-API-only
fixture; it does not qualify a named SDK or host. Static paging, cooperative
cancellation, physical ownership under blocked callbacks, and memory/maintenance
bounds retain their separate local/reference-client obligations. Actual
display/refresh and recovery also require named released hosts.

`server-sse-polling` is outside Soklet's selected November compatibility claim:
the case requires event IDs, `Last-Event-ID` replay and lost POST-result recovery.
Both that scenario and the multiple-streams scenario are inapplicable to June
in the pinned suite. These dispositions are recorded in runner evidence; they
are not successful checks. The session layer does not enable replay or recovery.

## Running

Use the Node/npm pins in `../upstream-pins.json`, a verified suite checkout,
and the exact JAR under test. Put generated files in ignored output or `/tmp`.

```sh
WORK_ROOT=$(mktemp -d)
sh conformance/official/legacy/build-fixture.sh \
  "$CANDIDATE_JAR" "$WORK_ROOT/fixture" > "$WORK_ROOT/classpath.txt"
node conformance/official/legacy/runner-self-test.mjs
node conformance/official/legacy/verify-evidence-self-test.mjs
node conformance/official/legacy/run.mjs \
  --suite-dir "$SUITE_DIR" --work-dir "$WORK_ROOT/run" \
  --classpath "$(cat "$WORK_ROOT/classpath.txt")" \
  --java "$JAVA_HOME/bin/java"
```

For a release, use `scripts/validate-release-candidate.sh`. It supplies the same
exact commit, POM, main JAR, sources JAR and Javadoc JAR to both runners. Legacy
release mode requires all profiles, a clean candidate checkout, the canonical
compiled-fixture location, and matching artifact hashes before and after the
run. Its receipt has `IMMUTABLE_LEGACY_RELEASE_CANDIDATE` and
`releaseCandidateEvidence: true` only after those checks succeed. The release
verifier independently checks every raw official result, source/class digest,
runtime contract and teardown. Development receipts cannot satisfy that gate.

## October 2 release-integration development verification

All **41 official combinations and 15 HTTP contracts for each 2025 revision**
passed against the copied JAR SHA-256
`78db80cf18605f00b7bd6a7ee213250ed056f649c9194443ee1ff4bd15264e32`.
The receipt verifier independently rechecked that run. The 40 receipt rejection
cases and runner negative controls passed, as did 54 targeted JDK 17 tests,
Javadoc generation and API compatibility/freezes. This rebuild replaces the
private scheduler dispatch record with a plain holder to avoid generated
diagnostic renderers; no public API or scheduling behavior changed.

A release-mode attempt rejected the dirty checkout before starting scenarios.
No immutable candidate PASS was created. The legacy lifecycle audit has since
been repaired through source review: 1,449 scopes across 222 JUnit files now
include imported lifecycle fixtures, shared request/read deadlines and composed
outer guards. Its verifier, 138-case adversarial self-test and 157 focused tests
on each of JDK 17 and 25 pass. Exact committed candidate validation is still
required. No downstream pins changed. Raw receipts remain outside Git.

The focused release-integration assertions also passed, including rejection of
missing, development-only, wrong-JAR, incomplete official and incomplete runtime
legacy evidence through the combined verifier. That diagnostic excluded the
separate nested audit suites; it is not a passing aggregate release self-test.

## October 2 earlier development verification

After the URI grant renewal, maintenance deadline-priority and delayed-fence cancellation fixes, all 41
combinations passed against the exact
copied JAR SHA-256
`1863f35500193df8130e25f231e3716175c92922da14eed35c328b7b16ede4f4`:
**86 `SUCCESS`, three expected `INFO`, 192 schema-validated messages**, with
clean shutdown for every fixture. The fixture also passed JDK 17 compilation
with `-Xlint:all -Werror` and a public-dependency `jdeps` check. Runner negative
controls passed. Toolchain: Node 26.5.0/npm 11.17.0 from Homebrew on macOS and
Corretto 17.0.20.1. This is development evidence; the macOS installation is not
the pinned Linux candidate distribution. Raw receipts stay outside Git.

The earlier working-tree JAR
`b20cfdd49fc824d060ae2eec0fdd99f19185397730734e022369b7c9a176e182`
also passed the same selected official checks. URI notification delivery and
renewal suppression require the separate supplements described above.
