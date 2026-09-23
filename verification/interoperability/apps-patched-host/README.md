# Apps render/refresh on an isolated auth-patched Inspector

This separate experimental profile runs the existing real candidate-backed Apps
fixture, shell, Inspector bridge and DOM interactions against the exact local
auth patch validated by `../inspector-auth-patch`. It neither modifies the
original host installation nor changes the existing Apps harness or its failed
receipt. No package installation, upstream submission or dependency repin occurs.

## Reproduce

Use the original and already prepared **final** patched installation. All input
paths are explicit, and the work directory must be new with an existing parent:

```sh
node --test verification/interoperability/apps-patched-host/*-self-test.mjs
node verification/interoperability/apps-patched-host/run.mjs \
  --candidate-jar /path/to/current-soklet-4.0.0.jar \
  --candidate-pom /path/to/original-candidate-pom.xml \
  --java /path/to/jdk/bin/java \
  --shell /path/to/catalog-shell.html \
  --original-dependencies /path/to/original-installation \
  --dependencies /path/to/final-patched-installation \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /path/to/new-results
```

The shell's adjacent `.receipt.json` is mandatory. All three profiles pin the
current candidate JAR; shell and patched backend/tree retain their exact prior
identities. The older candidate cannot compile the current fixture's
`ContentSecurityPolicy.defaultInstance()` call; historical receipts remain
unchanged. Original dependencies and the one-file patch recipe are checked
before and after the run, including independent file inodes and unchanged file/link/directory
identities outside the patch. The browser distribution/version is recorded and
rechecked, not silently represented as an approved released-host combination.
Node must match the existing pinned runtime. Java fixture compilation targets
release 17 with annotation processing disabled and lint warnings treated as
errors; public-only dependency analysis is also required.

## Exact success boundary

`EXPERIMENTAL_APPS_RENDER_REFRESH_PASSED` requires:

- Real DOM connection, Apps selection and catalog rendering in the genuine
  opaque `srcdoc` App iframe, followed by its actual Refresh catalog button
  using the bundled SDK and host bridge.
- Exact server-selected English/alpha data, literal hostile-looking text,
  locale/direction, currency and UTC date, and removal of prior data while the
  refresh is pending.
- The same eight successful MCP exchanges as the original profile: discovery,
  four catalog acquisitions, one `show_catalog`, one matching resource read
  (prefetch allowed) and one `refresh_catalog`, in the original required order.
- Between one and eight exact denied subscriptions (HTTP 403 with the fixed
  policy error), at most sixteen total exchanges. Every retry is checked for
  exact method/selection, admission, forwarded credential, protocol/capability/
  MIME fields, response envelope and byte bounds before normalization. The first
  denial precedes the initial tool call. Later retries may interleave while the
  connection remains open, including after refresh. No denial becomes success.
- A fixed six-second observation after refresh, real DOM disconnect, and a
  trace sealed only after all callers and the proxy stop. The allowance for
  retries follows the independently observed original/patched auth matrix;
  it is not an override added to the old exact-one-denial profile.
- Zero OAuth or other proxy gate rejections, zero browser exceptions, zero
  unexpected browser requests, exact sandbox/font policy counts, authenticated
  and origin-restricted host API, and direct wrong/right credential checks
  against Soklet itself.
- Clean browser/host/fixture exits, all supervised cleanup checks, deletion of
  only this invocation's private state, and unchanged candidate/shell/source/
  class-tree/dependency/browser/config identities.

The receipt always states `experimental:true`, `fullHostQualification:false`
and `releaseCandidateEvidence:false`. A failure may retain narrowly observed
UI facts but cannot be upgraded by filtering retries, hiding an exception,
ignoring OAuth or borrowing a previous host PASS.

## Same-App caller transitions

Add `--profile transitions` to the invocation above to select a separate
profile on the same current candidate JAR SHA-256
`e59c107e33187209e504b6e37141d410c0bffedf26e5dd14e2abf28c2d62227f`.
The original render/refresh behavior remains unchanged. After the English/alpha
render and refresh, the fixture acknowledges
two bounded caller changes over its private stdin control stream. The browser
clicks the App's visible button after each acknowledgement, first verifying a
Portuguese/beta result in the same App document, then verifying that denial
clears all prior content and disables refresh with a reopen-required message.
There is no synthesized browser MCP call or browser-provided identity override.

`EXPERIMENTAL_APPS_CALLER_TRANSITIONS_PASSED` additionally requires the exact
initial profile trace, one beta refresh completion, one content-free denied
refresh, at most eight total fixed subscription denials, six seconds of
post-denial observation, no extra tool/resource exchanges, and the same clean
shutdown and input-integrity checks. The profile stores only structural
exchange facts; no token, raw result, shell body, error body or payload hash is
retained. This demonstrates fresh server authorization through this isolated
host and App instance, not credential revocation, automatic invalidation of an
idle view, released-host compatibility, or full P3 qualification.

## Open-App credential revocation

Use `--profile revocation` with a new work directory to start a fresh App. After
its ordinary render and refresh, the fixture removes the same bearer credential
and acknowledges removal over private stdin. The browser then clicks the
visible Refresh button. A qualified UI observation requires an exact Soklet
401 authentication error, a pending state that hides the old data, and the
App's terminal cleared/reopen-required state in the same document. There is no
promise that an idle App clears before it makes another request.

The aggregate host profile returns `EXPERIMENTAL_APPS_CALLER_REVOCATION_PASSED`
only with no unexpected host requests. If Inspector instead performs one or two
bounded, complete OAuth-discovery cycles after the 401, and every other UI,
trace, identity, and cleanup check passes, it returns
`BLOCKED_HOST_AUTH_FALLBACK` with exit code 2. That is explicitly **not** a host
pass. Each discovery request is classified by fixed path/method/error enums;
any other traffic, extra retry, browser exception, or incomplete cleanup fails.
The receipt retains no raw credentials, result bodies, or error bodies.

## Requested browser permission

Use `--profile permissions` with a fresh work directory to request only
`geolocation` in the resource-content metadata. The exact MCP resource-read
projection must contain that marker, and the genuine App must render and
refresh. The browser probe then checks the inner iframe's `allow` attribute
and Chromium's effective Permissions Policy at the main, sandbox, and opaque
App documents. Camera, microphone, and clipboard-write remain undeclared.
It never requests a browser/user grant, location fix, device, or clipboard write.

On the pinned Inspector 2.7.0, Java 17 and 26 both return
`BLOCKED_HOST_PERMISSION_POLICY` (exit 2): Inspector gives the inner iframe
`allow="geolocation"`, but its outer sandbox iframe does not delegate that
feature. Chromium permits it in the main document but denies it in the sandbox
and App; all three undeclared features are denied. The trace, browser,
credential, process cleanup, and input-integrity checks pass. This is a narrow
host limitation, not failed Soklet metadata serialization and not a successful
geolocation grant. An exact observed denial may be classified as blocked, never
promoted to a passing permission profile; other shapes fail. Retained receipts:
`/private/tmp/soklet-apps-permissions-current-jdk17-complete/receipt.json` and
`/private/tmp/soklet-apps-permissions-current-jdk26-complete/receipt.json`.
The [Apps specification](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
allows hosts to choose whether to honor requested permissions; host support
and any browser/user grant must be tested separately from Soklet's metadata.

The separate revocation blocked status is not a Soklet revocation failure. An invalid/revoked
bearer receives HTTP 401, and Inspector's OAuth discovery is an expected
response to that authentication challenge. This disposable bearer fixture has
no OAuth authorization server or protected-resource metadata, so the proxy
rejects discovery and registration; it does not simulate a successful login.
Do not change the 401 to 403/200, suppress the challenge, or add dummy metadata
to turn this into a pass. The earlier *plain* subscription-policy 403 triggering
OAuth recovery is the distinct Inspector behavior isolated by the local patch.
The 2026-07-28 MCP [authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
and [discovery](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery)
requirements explain the 401 path; neither this patched-host experiment nor
its refusal proxy qualifies production OAuth or the unchanged released host.

## Lifecycle and diagnostic safety

The default Apps proxy and Java fixture behavior remains unchanged. Process deadlines
are 120 seconds for compile/fixture/host and 90 seconds for browser, with 2 MiB
captured-output bounds. Identity/readiness operations have ten-second guards;
UI phases have fifteen-second guards. The explicit post-refresh observation is
six seconds. Both normal and failure shutdown paths use three-second
browser/host grace and six-second fixture EOF grace before independent
two-second TERM/KILL fallback. Private creation is inside cleanup coverage;
fallible input snapshots happen before private state or signal handlers exist.
Output cannot overlap dependencies, browser/JDK distributions or source roots.

Child iframe targets remain paused until recursive Fetch interception is
installed; same-process frame contexts are tracked as before. During intentional
browser close, late requests remain counted/classified but paused, and only an
exact `CDP_CLOSED` rejection from an already pending Fetch command is consumed.
Other failures still fail the experiment.

Exception diagnostics retain a bounded structural projection: event-stage,
main/child session category, mapped frame kind, fixed source category and numeric
line/column locations, never raw URLs, exception messages/classes, function
names, payloads, credentials, runtime IDs, console output or storage. Any
exception still fails. These classifications are diagnostic hints, not proof of
fault ownership. Browser interception and fixed local backend destinations do
not constitute an OS-wide network firewall.

This is one dirty candidate, local patch and browser build. The default profile
is English/alpha only; the opt-in profiles add a beta/Portuguese switch and
denial or an open-App revocation. None establishes general CSP/permission
enforcement, localization/RTL, idle-view invalidation, capability-OFF/wrong-MIME
behavior, production OAuth, upstream compatibility or full P3/release qualification.
