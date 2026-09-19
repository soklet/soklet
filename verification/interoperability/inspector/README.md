# Isolated Inspector host-harness foundation

This P0-H profile runs the **unmodified Inspector CLI**, not a synthetic MCP
client, against the packaged public Soklet fixture. A separate profile drives
the **unmodified Inspector web client in real headless Chrome**, including its
visible connection switch, tool list, Execute Tool action, result, and disconnect.
These profiles qualify base modern HTTP transport and observe requested
Apps/Skills advertisements. They do not
qualify an Apps renderer, a Skills consumer, or an immutable release candidate.
It is not a new release gate and does not replace the TypeScript/Go hooks.

Inspector is exactly `2.7.0` (source commit
`2e90a628e6296c62e4bef942afbb43d3faa4baf4`), with Apps SDK `2.0.0`. Read the
[dependency review](dependency-review.md) before execution. Package and lock
digests are independently pinned by the runner. Installation uses the exact
lock, an isolated npm cache/configuration, and `npm ci --ignore-scripts`.
No floating `npx`, native build, or upstream lifecycle script is used.

## Run the CLI profile

Requirements: POSIX process groups, Node `26.5.0`, npm `11.17.0`, a real JDK
executable (not the macOS `/usr/bin/java` launcher), Git, and `unzip`. Network
access is needed for locked npm artifacts; the probes themselves use loopback.
Use the candidate's original POM: its bytes must match the POM embedded in the
JAR. The work directory must be new and below this repository's
`target/inspector/` directory.

```sh
node verification/interoperability/inspector/run.mjs \
  --candidate-jar /absolute/path/to/candidate.jar \
  --candidate-pom /absolute/path/to/pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --work-dir target/inspector/local-run
```

The runner compiles the existing public fixture against only that candidate
JAR and executes its standalone contracts. Each of four probes then starts a
fresh JVM and Inspector process: tool listing and `test_simple_text`, with
Apps/Skills requested enabled and requested disabled. Every config explicitly
uses modern `2026-07-28`, HTTP, read-only `--config`, and `--stored-auth-only`.

`receipt.json` records the candidate JAR/POM, exact working-tree identity,
runtime, lock, installed dependency tree, launcher, compiled fixture identity,
per-probe verdicts, clean shutdown, and input rechecks. `sanitized-trace.json`
contains only structural allowlisted observations, not raw payloads. Preserve
these files outside `target` when recording a checkpoint; a Maven clean removes
the working output. Do not archive the entire installation/cache/session tree
as a trace or release receipt.

## Verdicts and the pinned CLI limitation

Exit `0` requires all four probes to pass. Exit `2` means both enabled probes
pass but both requested-disabled probes still advertise Apps and Skills in the
exact recognized shape: `BLOCKED_HOST_EXTENSION_TOGGLE`. This is a **non-PASS**
qualification result, not an accepted skip. Other failures exit `1`.

Inspector `2.7.0` reads `advertisedExtensions` in the session but its CLI does
not forward it to `InspectorClient`; the web client does. The capability
builder therefore uses default-on advertisements for the CLI's disabled
config. A config file containing `false` is not absence evidence. The harness
checks the actual per-request wire capability maps and preserves the mismatch.
The web profile below exercises genuine OFF behavior without patching the CLI.
The CLI limitation remains a non-passing CLI result; a separate passing web
profile does not relabel it as unmodified-CLI absence coverage.

Source references: [CLI options](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/clients/cli/src/cli.ts),
[extension construction](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/core/mcp/extensions.ts),
[web forwarding](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/clients/web/src/hooks/useConnectionLifecycle.ts).

The memory secret store emits exactly one fixed stderr caveat that secrets are
not written anywhere and are lost on exit. The runner requires that precise
pinned line; extra, missing, or altered stderr fails. CLI output is checked
separately from raw wire: Inspector projects the list to `{tools}` and its SDK
projects the call to `{_meta, content}`, whereas the wire must still contain
`resultType: "complete"`. Both paths perform two tool listings after discovery;
the call path then invokes the tool. The trace guard pins that observed order.

## Run the web profile

The initial web profile supports installed **Google Chrome on macOS**. It uses
the existing locked Inspector distribution; there are no new npm dependencies,
browser downloads, injected MCP client calls, or upstream patches. It verifies
the prebuilt web assets exist so upstream cannot fall back to an npm build.

```sh
node verification/interoperability/inspector/run-web.mjs \
  --candidate-jar /absolute/path/to/candidate.jar \
  --candidate-pom /absolute/path/to/pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir target/inspector/web-local-run
```

Two fresh browser/Inspector/JVM sessions request both extensions ON, then OFF.
Each must display the tool list and expected tool result, disconnect, and shut
down cleanly. Exit 0 requires both rows to pass; every other outcome exits 1.
There is no blocked-toggle exemption in the web profile.

The observed request profile is exactly seven exchanges per session:
discovery first, five catalog-list responses, and the explicit tool call last.
The middle multiset is two `tools/list` requests plus one each of `prompts/list`,
`resources/list`, and `resources/templates/list`; their concurrent completion
order may vary. Selecting Tools causes its second listing. Exact fixture
descriptors are checked for the additional catalogs. The web ON UI declaration
contains both the Apps MIME and `elicitation: {}`; OFF requires both extension
keys actually absent on every request. Neither the extra declaration nor a
running sandbox listener proves an App rendered. SSE between the browser and
Inspector's internal backend is not an MCP subscription or MCP session.

The receipt records Chrome's reported product/revision and a SHA-256 identity
of the entire installed application bundle before and after the run, alongside
the candidate, dependencies, fixture, source tree, and per-session assertions.
This records the actual local binary; it does not claim a reproducible browser
build or authorize an automatic browser update.

### Web isolation and network policy

The web server binds only `127.0.0.1`, with one exact allowed origin and a
separate disposable API token. Authentication remains enabled: missing auth
must return 401 and a foreign Origin must return 403. Authenticated config must
report a read-only catalog and explicitly configured nondurable memory secret
storage. The browser opens the bare origin; upstream injects its API token
internally. **The host's startup banner contains that token: never save or
print raw stdout, HTML, browser storage, network headers, or config panels.**
The live session file must remain byte-identical.

Chrome gets a new private profile, no first-run or sync, a basic password store
and mock keychain, suppressed background networking, no inherited proxy, and
loopback-only hostname resolution. Sandbox, CSP, certificate validation, and
origin protections are not disabled. A dependency-free bounded DevTools
connection drives only DOM controls and reads the displayed result; it does
not call Inspector's client methods or synthesize MCP messages.

The attached page's intercepted requests may continue only to its exact
Inspector origin. The one optional Google Fonts stylesheet declared in the
pinned `index.html` is **blocked**, with its exact URL/method/resource type
recognized and recorded only as a count; system fonts are used. Any other
attempted origin fails the run. This is a page-request guard, not an OS-wide
network sandbox or proof about every Chrome background socket. No font bytes
are fetched or replaced, and this profile makes no visual-layout claim.

The host also starts its normal auxiliary loopback sandbox/App-origin
listeners on disposable ports. They are not exercised as renderers here.
Read-only `--config` does not make browser or OAuth/UI storage read-only:
all such storage belongs to the fresh private session and is removed after
bounded cleanup. No existing user profile, account, catalog, or keychain is
used or cleared.

Web fixture and host processes have 120-second bounds, Chrome 90 seconds,
readiness/UI waits 10 seconds, backend config reads 1.5 seconds/64 KiB,
and page requests a 256-request cap. DevTools commands/messages/event callbacks
and closure have independent bounds and fixed redacted errors. Final page
observations are checked again after browser closure. Process-group cleanup
attempts every owner independently; cleanup failure cannot yield PASS.

## Isolation, bounds, and evidence limits

Each host process receives a fresh HOME, XDG directories, explicit isolated
OAuth/client/secret paths, the memory secret store, and disabled browser
opening. No user catalog, account, keychain, proxy variables, or credentials
are inherited. `MCP_CATALOG_PATH` is deliberately absent because it conflicts
with read-only `--config` in this version. Live configs contain only a random
disposable token, are owner-readable, and are removed with private session
state after each probe. Persisted config projections redact both token and
ephemeral port; they contain no token hash.

The loopback gate validates the token, accepts only the reviewed POST methods,
and forwards unchanged JSON body bytes and allowlisted protocol headers.
It rewrites Host to the fixture's port and strips authentication/cookie/custom
headers. This proves disposable credential isolation at the gate, **not Soklet
authentication or OAuth conformance**. Soklet's fixture remains anonymous; its
exact fixed admission warning is accounted for, while unexpected stderr fails.
The wrapper does not alter legacy requests into modern ones or add a fallback.

Requests and responses are bounded to 1 MiB, each probe to 32 exchanges,
upstream response inactivity to ten seconds, and accepted sockets to fifteen
seconds. Inspector commands have a 30-second process bound and fixture JVMs a
60-second bound. Build and installation have separate 120/300-second bounds.
Managed process groups receive bounded TERM/KILL cleanup, including descendants
after leader exit. Raw stdout/stderr is bounded in memory and never saved.
Unexpected response media types, sessions, methods, outcomes, or acquisition
order fail; known false extension toggles cannot hide unrelated failures.

The CLI does no rendering. The fixture has no Apps/Skills registrations. A
successful core tool result establishes neither UI resource authorization nor
CSP/permissions, app-only calls, fallback selection, locale/tenant isolation,
Skills retrieval/integrity/activation, or production-host compatibility. Those
remain P3/P4 and real-host qualification work. No frozen signature ledger or
official conformance profile is changed by this harness.

## Tests

```sh
node --test verification/interoperability/inspector/config-self-test.mjs \
  verification/interoperability/inspector/runner-self-test.mjs
node verification/interoperability/inspector/process-self-test.mjs
node verification/interoperability/inspector/trace-self-test.mjs
node verification/interoperability/inspector/cdp-self-test.mjs
node --test verification/interoperability/inspector/web-probe-self-test.mjs
node verification/interoperability/inspector/web-trace-self-test.mjs
```

The trace self-test needs loopback sockets. It uses a local mock only to test
the harness's byte forwarding, redaction, limits, and negative cases; its
results are not host interoperability evidence. DevTools self-tests use a
mock WebSocket, not a qualifying browser. Positive host evidence must come
from the installed pinned Inspector, actual browser for the web profile, and
candidate-JAR fixture.
