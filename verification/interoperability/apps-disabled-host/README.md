# Apps-disabled real-host negative capability profile

This separate experimental profile requests both Apps and Skills OFF on the
same exact candidate, static shell and isolated auth-patched Inspector used by
`../apps-patched-host`. The wire, DOM and server controls must independently
confirm the boundary; configuration alone is not evidence. Existing positive,
ordinary Inspector, auth and original failed-host profiles remain unchanged.

## Negotiation is not authorization

With Apps OFF, `show_catalog` remains an ordinary tool but its descriptor loses
Apps resource/visibility hints. The app-only `refresh_catalog` disappears from
the tool catalog. Inspector should expose Tools and Resources without an Apps
tab, iframe, bridge or automatic UI resource read. A genuine ordinary Tools
selection and Execute Tool click must still return the exact sanitized result.

The server's discovery offer remains Apps-capable, and the ordinary resource
catalog still contains `ui://soklet/catalog-v1`. An authorized caller may read
that resource without advertising Apps. Neither its URI nor its MIME type is
an authorization boundary. This profile must not claim that Apps OFF revokes
resource access or that absent UI is proof of server-side authorization.

Four separate direct requests use the same credential before the browser starts:

| Control | Expected result |
| --- | --- |
| `refresh_catalog`, Apps OFF | HTTP 400 / -32021, exact missing-capability diagnostic |
| `refresh_catalog`, Apps ON with exact MIME | HTTP 200, exact sanitized fixture result |
| `refresh_catalog`, Apps OFF again | Same HTTP 400 capability diagnostic |
| Known UI resource read, Apps OFF | HTTP 200, exact independently authorized static shell |

These establish request-local capability evaluation and the negotiation/access
distinction. They do not prove handler non-invocation through instrumentation,
UI origin authorization, revocation or any browser action. Wrong/right direct
credential checks remain separate, too. Only browser-originated requests pass
through the OFF host trace.

## Reproduce

Use explicit existing inputs and a new work directory with an existing parent:

```sh
node --test verification/interoperability/apps-disabled-host/*-self-test.mjs
node verification/interoperability/apps-disabled-host/run.mjs \
  --candidate-jar /path/to/soklet-apps-result-candidate.jar \
  --candidate-pom /path/to/original-candidate-pom.xml \
  --java /path/to/jdk/bin/java \
  --shell /path/to/catalog-shell.html \
  --original-dependencies /path/to/original-installation \
  --dependencies /path/to/final-patched-installation \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /path/to/new-results
```

The adjacent shell `.receipt.json` is mandatory. The original dependency pin,
single-file patch recipe, exact previously retained candidate/shell/patch
identities and independent copy inodes are verified before and after the run.
The browser build is recorded/rechecked. No install, repin, upstream write,
production edit or change to the shared original installation is performed.

## Fail-closed success requirements

`EXPERIMENTAL_APPS_DISABLED_PASSED` requires all four direct controls plus:

- Real DOM connection and ordinary tool selection/call/result, with the app-only
  helper and Apps/Skills controls absent and no App/sandbox/child iframe.
- Exactly six successful browser-originated MCP exchanges: one discovery,
  two tools lists, one resources list, one templates list and one `show_catalog`
  call, with exact ordering, descriptors and sanitized result. Every request
  must actually omit both UI and Skills extension capabilities.
- One to eight exact policy-403 subscription denials, validated individually;
  at most fourteen retained MCP exchanges. As established by the prior auth
  A/B experiment, these bounded retries are not OAuth or authorized streams.
- No automatic UI resource read or app-only helper call. The proxy rejects such
  traffic as unexpected in this workflow, not as a claimed server permission
  denial. No OAuth or other gate rejection can pass.
- Six seconds of repeated connected-DOM absence checks after the ordinary result,
  then actual disconnect. Frame/session tracking catches transient iframe
  creation as well as end-state presence. The trace seals after callers stop.
- Zero exceptions or unexpected browser requests, zero sandbox document
  requests, exactly one blocked pinned font request, host API authentication /
  Origin protection, clean process exits and complete owned-private-state cleanup.
- Exact final source/candidate/POM/shell/class-tree/dependency/browser/config
  identities and no interruption, including late interruption during shutdown.

Receipts retain `experimental:true`, `fullHostQualification:false` and
`releaseCandidateEvidence:false`; the original released-host failure is not
relabelled. No general CSP/permission, wrong-MIME, tenant, localization,
revocation, production OAuth or release qualification follows from this profile.

## Bounds and privacy

The proxy has a 32-request hard transport bound; the accepted trace is narrower
at fourteen rows. Request/response bounds remain 64 KiB / 1 MiB, connections 32,
exchange deadline fifteen seconds and upstream/header deadlines ten seconds.
Direct controls each use a five-second abort deadline and 1 MiB response bound.
Compile/fixture/host processes have 120-second deadlines, browser 90 seconds,
captured output 2 MiB, UI phases fifteen seconds and fixed observation six seconds.
Both normal and failed shutdown use three-second browser/host and six-second
fixture EOF grace, with separate supervised two-second TERM/KILL fallbacks.

Input snapshots precede private state/listeners, private creation is cleanup-
covered, and outputs cannot overlap protected installations/source roots. Late
browser-close requests remain classified/counted but paused; only the exact
intentional pending `CDP_CLOSED` race is consumed. Every other failure still fails.

Only allowlisted enums, booleans and bounded counts leave the proxy/direct
controls. Exception diagnostics use bounded stage/frame/source-category and
numeric locations, never raw messages, URLs, IDs, function names or private data.
No runtime credentials/token hashes, raw bodies or browser storage are archived.
Browser interception plus fixed local backend destinations is not an OS-wide
network firewall.
