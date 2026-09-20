# Apps MIME capability boundary — candidate direct HTTP

This separate experimental profile sends fixed modern-protocol requests to the
unchanged public-API Apps fixture on a real loopback Soklet listener, using the
exact previously retained candidate and static shell. It does not run a browser,
Inspector, the App SDK or a bridge. It does not install or patch dependencies.
Original and experimental host profiles and their receipts remain unchanged.

## Why direct HTTP

Pinned Inspector 2.7.0 accepts boolean `advertisedExtensions` settings and its
browser registry hardcodes `text/html;profile=mcp-app`. There is no supported
session/UI setting for arbitrary Apps MIME types in that build. Rewriting its
wire traffic would not be evidence of a genuine host advertisement. This matrix
is explicitly server/wire contract evidence, not wrong-MIME browser qualification.

## Fixed matrix

Each profile performs `tools/list`, ordinary `show_catalog`, app-only
`refresh_catalog`, and an independently authorized UI resource read. Each batch
also performs discovery and resource listing with absent Apps capabilities.

| Batch | Capability shape | Apps expected |
| --- | --- | --- |
| 1 | Generic `text/html` | No |
| 1 | Exact `text/html;profile=mcp-app` | Yes |
| 1 | Empty MIME array | No |
| 1 | Generic HTML followed by exact Apps MIME | Yes |
| 2 | UI extension present, MIME field absent | No |
| 2 | Type/parameter-name case, spaces, quoted equivalent value | Yes |
| 2 | Uppercase `MCP-APP` parameter value | No |
| 2 | Exact profile plus `charset=UTF-8` | No |
| 3 | Exact Apps MIME followed by a numeric member | No |
| 3 | Unterminated quoted MIME before exact Apps MIME | No |
| 3 | Exact Apps MIME followed by generic HTML | Yes |
| 3 | Generic HTML again | No |

Structural matching is not literal string equality. Type/subtype and parameter
names ignore ASCII case; parameter values remain case-sensitive and extra
parameters change the match. A valid unrelated MIME can coexist with an exact
match, but any malformed member invalidates the entire array, before or after
the match. A positive case after malformed cases must recover normally.

For unsupported shapes, the tool catalog contains the ordinary tool without
Apps hints and hides the app-only helper. Direct helper invocation must return
HTTP 400 / -32021 with the exact canonical missing-capability diagnostic. For
supported shapes, both descriptors and Apps metadata appear and the helper
succeeds. The ordinary tool always returns its exact sanitized English/alpha
result. Discovery/resource offers remain unchanged; authorized resource reads
always return the exact static shell and typed security metadata with private
zero-TTL caching. This is negotiation, not resource access revocation. It does
not prove handler non-invocation via instrumentation or general authorization.

## Reproduce

```sh
node --test verification/interoperability/apps-mime-boundary/*-self-test.mjs
node verification/interoperability/apps-mime-boundary/run.mjs \
  --candidate-jar /path/to/soklet-apps-result-candidate.jar \
  --candidate-pom /path/to/original-candidate-pom.xml \
  --java /path/to/jdk/bin/java \
  --shell /path/to/catalog-shell.html \
  --work-dir /path/to/new-results
```

The adjacent shell `.receipt.json` is mandatory. Candidate and shell must match
the exact prior pins; the embedded candidate POM and shell source/build receipt
must agree. Work must be a new directory under an existing non-symlink parent,
outside protected source/input/JDK paths. Fixture sources compile once against
the candidate using release 17, no annotation processing and lint warnings as
errors. Public dependency analysis rejects internal/missing references.

## Success, lifecycle and privacy

`EXPERIMENTAL_APPS_MIME_BOUNDARY_PASSED` requires exactly 54 successful projected
matrix observations in fixed order across three fresh fixture batches. Here
"successful" includes each precisely expected capability rejection, not an
HTTP-200-only rule. Each batch has 18 matrix requests plus wrong/right credential
controls: 20 total HTTP requests, at most 19 admitted, within the unchanged
fixture's capacity-20 request bucket. There is no automatic retry or policy
workaround. All profiles within a batch reuse one credential; batches do not
claim cross-credential/session continuity.

Each request has a five-second abort deadline and 1 MiB response cap; each batch
has a sixty-second matrix deadline. Invalid JSON, wrong envelopes/headers,
unexpected status/content, session state, auth challenges or extra/missing rows
fail. Complete parsed responses retain one allowlisted structural row even if
incorrect; earlier rows survive transport/body failures. Interruption prevents
later requests and fixture batches and cannot produce a passing receipt.

Compilation/fixture processes are bounded to 120 seconds, dependency analysis
to sixty seconds, identity/readiness to ten seconds and child output to 2 MiB.
Every fixture receives EOF for graceful termination with six-second grace,
then independent supervised two-second TERM/KILL fallback. One fixture must be
fully stopped before the next starts. Exclusive private state is cleanup-covered;
only invocation-owned environment/storage directories are removed. Candidate,
POM, shell/build receipt, sources and class-tree identities are checked after
failure as well as success. Every batch must exit CLEAN with complete cleanup.

Receipts explicitly state `scope: candidate-direct-http`, `experimental:true`,
`browserExercised:false`, `hostQualification:false`, and
`releaseCandidateEvidence:false`. Rows retain fixed case/operation labels,
booleans, HTTP status and bounded byte counts. No raw bodies, arbitrary errors,
runtime credential/token hashes or caller data are archived. The static fixture
shell and exact harness sources are retained as build inputs, not response logs.

This does not establish native wrong-MIME host UI behavior, general CSP or
permission enforcement, localization/RTL, tenant switching, revocation,
production OAuth or complete P3/release qualification.
