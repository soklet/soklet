# Isolated Inspector host-harness foundation

This P0-H profile runs the **unmodified Inspector CLI**, not a synthetic MCP
client, against the packaged public Soklet fixture. It qualifies base modern
HTTP transport and observes requested Apps/Skills advertisements. It does not
qualify an Apps renderer, a Skills consumer, or an immutable release candidate.
It is not a new release gate and does not replace the TypeScript/Go hooks.

Inspector is exactly `2.7.0` (source commit
`2e90a628e6296c62e4bef942afbb43d3faa4baf4`), with Apps SDK `2.0.0`. Read the
[dependency review](dependency-review.md) before execution. Package and lock
digests are independently pinned by the runner. Installation uses the exact
lock, an isolated npm cache/configuration, and `npm ci --ignore-scripts`.
No floating `npx`, native build, or upstream lifecycle script is used.

## Run

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
The next host slice must exercise genuine OFF behavior through the pinned web
client or a separately reviewed host version; do not patch the CLI and call it
unmodified-host qualification.

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
```

The trace self-test needs loopback sockets. It uses a local mock only to test
the harness's byte forwarding, redaction, limits, and negative cases; its
results are not host interoperability evidence. Positive host evidence must
come from the installed pinned Inspector and the candidate-JAR fixture.
