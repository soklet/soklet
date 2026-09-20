# Apps CSP — real-host connect and image denial

This separately named experimental profile uses the exact prior candidate,
unchanged public fixture/static shell and isolated one-file auth-patched
Inspector. It preserves the real DOM catalog open and SDK-backed Refresh action,
then exercises two page operations in the genuine opaque App default context:
`fetch` (`connect-src`) and `Image` (`img-src`). No fixture, shell, production,
compression or dependency bytes are changed.

## Causal evidence, not a harness-blocked request

The fixture supplies explicit empty CSP allowlists. The pinned host wraps its
resource with a CSP meta element before loading the real `about:srcdoc` App.
That policy includes `connect-src 'none'` and `img-src 'none'`. A raw CSP string
or a failed fetch by itself is not sufficient evidence.

After successful render/refresh, six bounded operations run in this order:

1. Main-frame fetch and image positive controls succeed against a fresh local
   canary server, checking its fixed text and decoded one-pixel PNG.
2. App-frame fetch and image operations fail, each with exactly one matching
   trusted `securitypolicyviolation` event, disposition `enforce`, expected
   effective directive and fixed destination/policy/context checks.
3. The same two main-frame controls succeed again against the same endpoint.
   Fetch reuses its exact URL; the final image uses the one fixed
   `/image?control=after` variant to force a fresh load of the identical PNG.

The listener is installed before each operation. Operation outcome and queued
CSP event delivery are checked independently. Missing, forged, report-only,
unmatched, duplicate, timed-out or unexpected observations fail closed.
The actual App location must remain `about:srcdoc`; the violation's document URI
must be exactly `about`, as observed in pinned Chromium and specified by CSP's
non-HTTP report-URL stripping rule. These are separate checks, not interchangeable
document identities. The listener runs in the already matched opaque App context.

The harness explicitly continues each phase's exact canary GET URL from its
matched main or App frame. It never substitutes `Fetch.failRequest` for these denial tests.
The pinned Chromium Fetch-interception event labels the JavaScript fetch as
`XHR`; admission requires that observed type for `/connect` and `Image` for
`/image`, independently of the page operation being tested.
App requests can be stopped by CSP before reaching interception; if interception
does see one, continuing it must still not produce a server request. The canary
must see exactly one fetch and one image in each positive-control phase and
zero requests in the App, idle and sealed phases. A valid App request that does
reach the canary receives the normal HTTP 200 response while latching failure,
so an endpoint denial cannot impersonate browser CSP enforcement.

The App image and preceding positive image control use the exact same `/image`
URL. Only the after-control image URL differs: Chromium can decode a repeated
image without a network request even with HTTP `no-store` and CDP cache disabling.
Both exact image URLs map to the same server handler, bytes and response headers,
including if requested in the App phase; no arbitrary query variants are allowed.
Four live requests remain mandatory, so local image reuse cannot pass as endpoint
liveness. No conclusion about positive App allowlist translation is drawn.

The dedicated loopback origin is distinct from the host and sandbox. Responses
have CORS `*` and `no-store`, fixed bodies and no redirects. Fetch uses omitted
credentials, no referrer, no cache and redirect rejection. Image uses anonymous
CORS and no referrer. Browser cache is disabled in each owned CDP session before
navigation, in addition to the fixed after-image variant and live-server gate.
Credential/cookie-bearing requests fail; no raw headers,
URLs or payloads are copied into the evidence.

The page operations are explicitly test-driver-triggered through CDP, not
claimed as actions already implemented by the App UI or SDK. They run in the
existing matched default context, not an isolated world. CSP is not bypassed;
unsafe-eval bypass is explicitly disabled for the probe evaluations. The exact
host-generated inner CSP and existing opaque iframe attributes must match.

## Reproduce

```sh
node --test verification/interoperability/apps-csp-host/*-self-test.mjs
node verification/interoperability/apps-csp-host/run.mjs \
  --candidate-jar /path/to/soklet-apps-result-candidate.jar \
  --candidate-pom /path/to/original-candidate-pom.xml \
  --java /path/to/jdk/bin/java \
  --shell /path/to/catalog-shell.html \
  --original-dependencies /path/to/original-installation \
  --dependencies /path/to/final-patched-installation \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /path/to/new-results
```

The adjacent shell build receipt is required. Candidate, shell, original and
patched dependencies are pinned to earlier retained evidence. Browser and all
source/class/config identities are recorded and rechecked. Public fixture
compilation targets release 17 with annotation processing disabled and lint
warnings as errors; dependency analysis must remain public-API-only.

## Final gate and bounds

`EXPERIMENTAL_APPS_CSP_DENIAL_PASSED` requires genuine initial render/refresh,
all six CSP/control rows, exact canary counts, the unchanged experimental MCP
trace of eight successes plus one to eight exact subscription denials, zero
OAuth/unexpected traffic/exceptions, fixed six-second post-probe observation,
real DOM disconnect and clean browser/host/fixture/canary cleanup. Observations
stay active through shutdown; the verdict is sealed only after callers and
servers stop. Late interruption or input drift fails.

Each probe operation has a two-second deadline and a bounded event-settling
interval. The canary caps total requests/connections at sixteen each, headers
at 8 KiB, headers/exchanges/sockets at five seconds, and close at one second.
Existing proxy limits remain
32 exchanges/connections, 64 KiB request/1 MiB response and fifteen-second whole
exchanges. Accepted MCP traces are narrower: at most sixteen rows.

Compile/fixture/host processes have 120-second deadlines, browser ninety
seconds, child output 2 MiB, and ordinary DOM phases fifteen seconds. Browser
and host graceful exit waits are three seconds; fixture EOF grace is six
seconds, with independent two-second TERM/KILL fallback. Only invocation-owned
private profiles/config/storage are removed. No shared installation is reused
as writable state, and outputs cannot overlap protected inputs/source roots.

Receipts retain only fixed structural labels, booleans, bounded counts and
redacted exception locations. Raw policies, arbitrary URLs, exception strings,
runtime credentials/token hashes, browser storage and personalized responses
are not archived. The exact static shell and harness source remain build inputs.
Browser interception is not an OS-wide network firewall.

This proves only these two denial cases in this pinned local host/browser with
empty allowlists. It does not establish other CSP directives, positive App
allowlist translation, permissions, localization, tenant switching, revocation,
OAuth or full P3/release qualification. The receipt remains `experimental:true`,
`fullHostQualification:false`, `releaseCandidateEvidence:false`; original
released-host FAILED evidence is unchanged.
