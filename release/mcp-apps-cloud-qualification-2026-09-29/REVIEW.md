# Claude cloud Apps render and refresh — September 29

**PASS for the bounded modern Apps render/refresh check.** Claude Desktop
**2.16120.0** and its unchanged cloud backend used `2026-07-28`, rendered
Soklet's server-provided catalog, and refreshed it through the App's own button.
No production code or public API fix was needed. This is development evidence;
full host qualification and immutable candidate acceptance are not claimed.

## Exact inputs

The server used the same clean core artifact as the separate prompt/resource
qualification: commit `3fe09465fd1241ffb785493fdff4a2074f8b0e0a`, JAR SHA-256
`c454eac7ff5fd58d564a2c1603133ed1c47a01210d2910be4d60f392ebe1184f`.
The existing public-API `AppsFixture` served only the modern revision. It was
compiled with Java 17, annotation processing disabled, and warnings as errors.
The private launcher copy changed only its independent watchdog from 150 seconds
to 20 minutes to allow interactive cloud setup; production fixture policy,
authorization, results, and shutdown behavior were unchanged.

The previously reviewed standalone shell was reused after checking its build
receipt against all current shell source hashes. Its SHA-256 is
`3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60`;
it bundles the pinned official Apps SDK **2.0.0**, with the disclosed console
removal transformation. It needs no package/CDN fetches in the App.

A temporary token-gated HTTPS tunnel forwarded MCP payloads and headers
unchanged, including the fixture-only bearer credential configured in Claude.
Only HTTP Host and the proxy URL path were rewritten. The fixture was a single
loopback JVM with a 256-MiB heap and two processors; the independent tunnel lease
was 20 minutes. Inputs were rechecked after execution. The cloud backend's
product version was not exposed; its self-reported identities are retained in
the [receipt](cloud-apps-receipt.json.gz).

## Observed result

- Connector setup first probed November initialization, receiving the expected
  unsupported-version HTTP 400, then used modern `server/discover`. A preliminary
  discovery without the fixture credential received 401. Authenticated modern
  discovery succeeded, and later requests supplied current client capabilities.
- Claude displayed **Show catalog** as an interactive tool and **Refresh catalog**
  as app-only. Tool and resource catalogs returned HTTP 200 with the Apps
  association and exact `text/html;profile=mcp-app` MIME type.
- One model-driven `show_catalog({})`, approved once, returned HTTP 200. Claude
  read `ui://soklet/catalog-v1` and rendered the actual supplied shell in its
  Apps sandbox. The displayed view contained tenant `alpha`, the English
  catalog summary, literal hostile-looking item text, USD amount, and UTC date.
- Clicking that embedded view's **Refresh catalog** button produced an actual
  `refresh_catalog({})` through the official SDK bridge, with HTTP 200. The
  catalog rendered successfully again. The model was explicitly instructed not
  to call the refresh tool itself; no synthetic bridge call was used.

All 16 captured responses included `Cache-Control: no-store`. The durable
receipt retains 14 cloud exchanges; two separate preflight controls are excluded
from host counts. Those controls verified modern discovery, an invalid-credential
401 with its Bearer challenge, and a 404 for a path outside the token gate.
The receipt binds source/class/shell identities, wire bodies, observed UI content,
and cleanup. Trace IDs are redacted; URLs, credentials, account/chat identifiers,
the per-App sandbox origin, and unrelated UI content are omitted.

The fixture stopped cleanly, the tunnel stopped, both local listeners closed,
and the temporary connector was disconnected. Its disconnected entry and the
qualification chat remain in Claude.

## Scope

This directly demonstrates modern MCP and Apps render/refresh working in this
named Claude cloud host. It does not qualify production OAuth, host denial or
revocation, CSP enforcement, permission delegation, tenant/locale transitions,
RTL rendering, idle-view invalidation, subscriptions, or other host versions.
It does not add Apps support to the stateless 2025 adapter. The earlier Inspector
plain-403 failure and its approved limitation retain their original disposition.

The [evidence manifest](evidence.json) binds the compressed receipt by both file
and decoded-payload SHA-256. Temporary run files are in
`/private/tmp/soklet-legacy-qualification-20260929/apps-cloud`.
