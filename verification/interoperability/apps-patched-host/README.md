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
  --candidate-jar /path/to/soklet-apps-result-candidate.jar \
  --candidate-pom /path/to/original-candidate-pom.xml \
  --java /path/to/jdk/bin/java \
  --shell /path/to/catalog-shell.html \
  --original-dependencies /path/to/original-installation \
  --dependencies /path/to/final-patched-installation \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /path/to/new-results
```

The shell's adjacent `.receipt.json` is mandatory. The candidate JAR, shell and
patched backend/tree are pinned to the exact previous receipts. Original
dependencies and the one-file patch recipe are checked before and after the
run, including independent file inodes and unchanged file/link/directory
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

## Lifecycle and diagnostic safety

The existing Apps proxy and Java fixture remain unchanged. Process deadlines
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

This is one dirty candidate, local patch, browser build and authenticated
English/alpha scenario. It does not establish general CSP/permission enforcement,
localization/RTL, tenant switching, revocation, capability-OFF/wrong-MIME behavior,
production OAuth, upstream compatibility or full P3/release qualification.
