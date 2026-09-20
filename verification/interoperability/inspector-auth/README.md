# Inspector policy-denial auth-recovery regression

This isolated, connect-only diagnostic uses unmodified pinned Inspector 2.7.0
and a fresh Chrome profile. It deliberately uses **no Soklet JAR, JVM, Apps UI,
Apps SDK bridge, or application tool invocation**. It isolates whether a
subscription-policy denial is mistaken for an OAuth authentication challenge.
It is not conformance, release-candidate evidence, or a passing host profile.

## Matrix

All rows use modern HTTP, a fresh disposable static bearer credential, a
tools-only catalog, and the same six-second observation interval after the
initial catalog acquisition. Apps and Skills are disabled in the host config.

| Case | One intended difference | Expected diagnostic |
| --- | --- | --- |
| `no-subscription` | `tools.listChanged` is false | No subscription or OAuth recovery |
| `jsonrpc-200-control` | Identical subscription error body, HTTP 200 instead of 403 | Subscription retries may occur; no OAuth recovery |
| `policy-403` | Policy rejection HTTP 403 without `WWW-Authenticate` | Unsolicited OAuth recovery reproduced |
| `policy-403-throw` | Same 403, `oauth.onInsufficientScope: "throw"` | Does not prevent recovery from generic 403 |
| `policy-403-no-refresh` | Same 403, explicit `autoRefreshOnListChanged: false` | Does not disable subscriptions or auth recovery |

The HTTP-200 and no-subscription rows are causal controls, **not proposed
Soklet workarounds**. The real fixture's discovery, denial semantics, and access
policy remain untouched. Inspector's refresh setting affects catalog refresh,
not whether its host-managed subscription is opened. The generic 403 is
intercepted before the SDK's insufficient-scope handling.

The minimal fixture authenticates every MCP request. It accepts only discovery,
tool listing, and the exact tools-list-changed subscription selection. The
subscription response has the same fixed JSON-RPC error as the Apps fixture.
OAuth metadata and `/register` requests are always refused on this loopback
listener; no request is forwarded and no account or registration is created.

## Run

Use Node 26.5.0, macOS Chrome, and the existing script-disabled installation
of the [reviewed Inspector lock](../inspector/dependency-review.md). No install,
dependency update, upstream rebuild or source patch occurs during the test.

```sh
node verification/interoperability/inspector-auth/run.mjs \
  --dependencies /absolute/path/to/locked-install \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /absolute/path/to/new-diagnostic-run

node --test verification/interoperability/inspector-auth/*self-test.mjs
```

The work directory must be new, outside source directories (or under the core
`target` directory). Each case starts a fresh host/browser/private configuration
and credential, connects using the visible Inspector switch, observes the same
fixed interval, then uses the dedicated disconnect button. HTTP API authentication
and foreign-Origin rejection are independently checked. Only the Inspector
origin is allowed by browser interception; the optional pinned font is blocked.
No browser security protections are disabled and no existing browser profile,
keychain, credential store or host configuration is used.

The fixture caps bodies/responses at 64 KiB, requests at 64, simultaneous
connections at 32, and each exchange at ten seconds. Host/browser process bounds
are 60/45 seconds; startup and catalog acquisition have ten-second deadlines,
disconnect has five seconds, and observation has six seconds. Child output is
bounded to 1 MiB and never persisted. Graceful exit waits are three seconds,
with the existing independent two-second TERM and KILL process-group fallbacks.
SIGINT/SIGTERM stops the current case and prevents later cases from starting.

Fetch events are still classified during shutdown. Once intentional browser
closure begins, late requests remain paused until process exit; only rejection
of an already-issued command with `CDP_CLOSED` is consumed. Other CDP failures
remain failures. Each process must exit cleanly, every private session must be
removed, and the final trace is read only after fixture closure.

## Evidence interpretation

`receipt.json` retains fixed labels, booleans, counts and static input identities,
not raw request/response bodies, IDs, credentials/token hashes, browser storage,
or arbitrary host/browser diagnostics. Browser exceptions remain an explicit
count; their messages and causal attribution are not inferred. The combined
request sequence must be gap-free and bounded, with every OAuth attempt after
the first denied subscription. Known recovery requests remain recorded as
refused; arbitrary extra requests still fail the fixture.

`CONTROL_PASSED` and `BUG_REPRODUCED` are diagnostic row outcomes.
`REPRODUCED_WITH_CONTROLS` means the defect was isolated across all five cases,
**not that Inspector passed qualification**. It requires both negative controls,
the three reproduction rows, unchanged config, clean shutdown, and rechecked
source/dependency/browser identities. `hostQualification` and `candidateEvidence`
always remain false. A failed attempt remains `FAILED`.

The complete installed dependency tree is pinned to the previously reviewed
9,391-entry identity. Before/after source and full browser-distribution identities
are recorded. Existing Apps and ordinary-tools harnesses are neither modified
nor reclassified by this regression. Any upstream issue/PR, dependency repin,
or host-gate disposition is a separate decision; this runner performs none.
