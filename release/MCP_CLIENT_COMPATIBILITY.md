# MCP client and host compatibility

This launch-facing matrix records what was actually exercised, with exact tool
versions and a manual-smoke date. It is not a candidate release gate and does
not create a release-validation PASS receipt.

## September 28 real-host development check

On **2026-09-28**, a disposable, loopback-bound fixture built with Soklet from
exact commit `fd5e0d479216aeda7565a379f27bd32fa79604cc` exposed one
`interop.echo` tool. The locally built Soklet JAR had SHA-256
`e09bb1edf4dfccc99c6ff481183ba2694c8a20e68aa944cb7d0db57e3a546216`.
The dual-era URL explicitly served `2026-07-28`, `2025-06-18`, and
`2025-11-25`; separate fixture URLs restricted selection to both 2025
revisions or to one exact 2025 revision. Each passing local row below includes an
actual model-driven tool invocation and its returned echo value.

| Client or host | Exact version | URL revisions and observed negotiation | Observed operation and result | Status |
| --- | --- | --- | --- | --- |
| Codex CLI | `0.155.0-alpha.16.4` | Dual-era URL; offered and selected `2025-06-18` through `initialize`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:codex-dual-approved` with the `2025-06-18` protocol header. | **PASS (local development tool smoke)** |
| Codex CLI | `0.155.0-alpha.16.4` | `2025-11-25`-only URL; offered `2025-06-18`, then selected the server's `2025-11-25` response. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:codex-november-20260928` with the `2025-11-25` protocol header. | **PASS (local development tool smoke)** |
| Claude Code | `2.1.274` | 2025-only URL; probed `server/discover` for `2026-07-28`, then fell back to `initialize` and selected `2025-11-25`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:claude-legacy-default`. | **PASS (local development tool smoke)** |
| Claude Code | `2.1.274` | Dual-era URL; selected `2026-07-28` through `server/discover`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:claude-dual-default`. | **PASS (local development tool smoke)** |
| Visual Studio Code / Copilot | VS Code `1.139.1`, bundled Copilot `0.67.0` | Installed locally; no authenticated Copilot model session or tool invocation completed. | None. | **NOT TESTED** |

These observations establish that current initialization clients can use an
endpoint **when the application explicitly declares the matching 2025
revision**, while Claude Code can also choose `2026-07-28` on a dual-era URL.
They do not establish support for every 2025 operation or every host. The
fixture exercised synchronous `tools/list` and `tools/call`, not denial,
OAuth recovery, disconnect behavior, prompts, resources, Apps, Skills, Tasks,
subscriptions, or SSE streaming. Other host rows are still pending. This was a
local development build from a known commit, not an immutable release
candidate or public Maven artifact; the release gate must repeat relevant
checks against the exact candidate.

### Claude Desktop custom connector cloud check

The cloud test used a **different, uncommitted working-tree build** with
validated hybrid 2025 header handling. Its Soklet JAR SHA-256 was
`64253678715421002d70ec2ea049650429a7af5ec31700cf03e6f5e1d784a727`.
A temporary token-gated HTTPS tunnel forwarded the client's MCP headers and
payloads unchanged to the disposable 2025-only fixture. The temporary URL and
token are intentionally omitted from this record.

| Client or host | Exact version/state | Observed negotiation and operation | Status |
| --- | --- | --- | --- |
| Claude Desktop custom connector and Claude cloud backend | Desktop `2.9939.2`; cloud backend version not exposed | `server/discover` probing `2026-07-28` received the expected unsupported HTTP 400; fallback `initialize` selected `2025-11-25`; `notifications/initialized` received HTTP 202; `tools/list` received HTTP 200 and exposed `interop.echo`; a model-driven `tools/call` received HTTP 200 and returned `soklet-echo:claude-cloud-unmodified-20260928`. | **PASS (cloud development tool smoke)** |

This demonstrates one cloud-hosted connector reaching the stateless 2025 tool
path through an HTTPS endpoint. It does not qualify cloud GET SSE, denial,
OAuth recovery, disconnect behavior, or any non-tool operation. The uncommitted
build and temporary tunnel are not an immutable release candidate or a public
artifact. Candidate qualification must repeat the applicable checks on the
owner's exact release commits.

The focused legacy-wire and adjacent runtime regression batch passed **44/44**
tests on both JDK 17 and JDK 26. This verifies the observed cloud fallback and
malformed mixed-framing cases, but does not replace the full release test suite.

## September 1 baseline matrix

Manual smoke date: **2026-09-01**

Server target: Soklet 4.0.0, exact MCP profile `2026-07-28`, Streamable HTTP
`POST`, endpoint `http://127.0.0.1:8081/catalog/mcp`.

| Client or host | Exact version/state | Transport/profile result | Status |
| --- | --- | --- | --- |
| MCP Inspector CLI | `@modelcontextprotocol/inspector` 2.3.0 | Modern HTTP with `protocolEra: "modern"`; `tools/list`, `tools/call`, `prompts/list`, and `resources/list` completed against a local pre-release source build. | **PASS (pre-release manual smoke)** |
| curl | 8.7.1 | Raw HTTP `server/discover` returned HTTP 200 and advertised exactly `2026-07-28`. | **PASS (pre-release manual smoke)** |
| Visual Studio Code | 1.135.0, commit `08d4889f9ec4a1685d257b9b95de036c8e1ce1e5`, arm64 | Installed locally; no MCP model/extension session was available, so no discovery or invocation was run. | **NOT TESTED** |
| Claude Code | Not installed; no version asserted | No connection was attempted. | **NOT TESTED** |
| Cursor | Not installed; no version asserted | No connection was attempted. | **NOT TESTED** |
| A client fixed to Soklet 3.5.1's initialization/session/GET-SSE contract | Legacy profile, independent of product version | The September 1 modern-only endpoint could not serve that contract. The September 28 checks above establish only the named, stateless 2025 tool paths. | **INCOMPATIBLE WITH SEPTEMBER 1 ENDPOINT** |

None of these host rows exercised the
[`io.modelcontextprotocol/tasks` extension](../MCP.md#durable-tasks). Soklet's
server implementation and protocol conformance coverage do not imply that a
listed host version negotiates Tasks. A future manual Tasks row must record the
exact client version and exercise capability negotiation, task creation,
`tasks/get`, input or cancelation where supported, reconnect recovery, and
optional `notifications/tasks` independently of the core smoke above.

## September 13 packaged-development recheck

On **2026-09-13**, the generated website quickstart consumer was executed with
only its consumer JAR and the corrected Soklet 4.0.0 main JAR at runtime. Core
SHA-256 was
`b192e36e7d92d319a1f60fa2cd7d6ad6ca2bd8481bd5f46d530a9721b41a0e50`.
The listener was `http://127.0.0.1:8082/catalog/mcp`, using the same modern
profile and loopback-only smoke policy described below.

| Client or host | Exact version/state | September 13 observation |
| --- | --- | --- |
| MCP Inspector CLI | Cached, installed `@modelcontextprotocol/inspector` 2.3.0; Node 26.5.0 | **PASS (packaged development smoke)**: `tools/list` exposed `catalog.search`; `tools/call` with query `sprocket` returned the fixture's typed `sprocket:10` result. No dependency install or credentials were used. |
| curl | 8.7.1 | **PASS (packaged development smoke)**: `server/discover` and complete `tools/call` with matching `Mcp-Name`, metadata, and arguments. |
| Visual Studio Code | 1.137.0, commit `645f29cc3176500b4b5762ba887cf2a7f0ffdf2c`, arm64 | Version rechecked; no model/extension MCP session was exercised. **NOT TESTED**. |
| Claude Code / Cursor | Neither CLI found on PATH in this environment | No integration run or version asserted. **NOT TESTED**. A separately installed Claude desktop application does not establish Claude Code availability. |

The server ran on Corretto 17 and was stopped after the smoke. Inspector prompt
and resource catalog checks from the September 1 row were not repeated here;
neither run establishes host-level Tasks support. The local working record is
`/private/tmp/soklet-inspector-smoke.SHBwj5/result.json` with adjacent per-command
logs. This is not immutable-candidate evidence or a durable release receipt;
repeat on the canonical artifact and retain final results through K/L.

## Tasks protocol conformance

On 2026-09-09, official MCP conformance CLI `0.2.0-alpha.11` at commit
`a983ba93c91e0bb31d0b6849eeb52f0ad1083107` exercised Soklet through the
public-API-only fixture. All nine runnable Tasks scenarios passed, totaling
**44/44 successful checks** across capability negotiation, lifecycle, wire
fields, removed request state, task input, routing headers, dispatch and
envelopes, required-task errors, and multi-round-input composition. Every
fixture process shut down cleanly.

The suite's `tasks-status-notifications` scenario reported its one check as
**SKIPPED** because the upstream runner does not yet open and observe a
`subscriptions/listen` task stream. Soklet's own production-path tests cover
task subscription authorization, fresh manager lookup, event ordering,
backpressure, reconnect, and terminal-state races. This is a local pre-release
protocol check, not a compatibility result for any client or host in the table
and not a release-candidate gate result.

On 2026-09-13 the normal conformance gate was repinned to that exact alpha.11
commit. All 49 selected profiles (the prior 39 core rows plus ten Tasks rows)
were re-observed against the packaged working-tree JAR. The nine runnable Tasks
scenarios again produced 44 successful checks; the notification row retains its
exact declared upstream skip and independent socket-test supplements. The gate
now replays those profiles, but this development verification does not replace
candidate provenance, client-host smokes, or the unresolved
[external-toolchain risk disposition](../conformance/official/UPSTREAM_DEPENDENCY_REVIEW_2026-09-13.md).

“PASS (pre-release manual smoke)” means only that the named local interaction
worked on the stated date. It does not mean every feature of that host was
tested, a live language model was involved, or the eventual published artifact
was exercised. The September 28 rows explicitly used model-driven calls but
carry the same development-evidence limitation. Before publishing, repeat the
relevant smoke against the exact candidate JAR; after Central synchronization,
repeat it from a clean directory against the public
`com.soklet:soklet:4.0.0` coordinate.

Test environment: macOS 26.6.2 (build 25G83) on arm64, Amazon Corretto
26.0.1+8-FR, Node.js 26.5.0, and npm 11.17.0. These are the manual client's
environment, not Soklet's supported or release-pinned toolchain statement.

## Server used for the smoke

The manual smoke used the annotated `catalog.search` endpoint from the
[copy/paste quickstart](../MCP_QUICKSTART.md), bound only to
`127.0.0.1:8081`. Its tool call with `{"query":"sprocket"}` returned a typed
structured result containing `"Match for sprocket"`. The prompt and resource
list checks used additional test-only declarations on the same endpoint.

Anonymous admission, a node-local in-memory tool limiter, reject-all Origin
policy, and a localhost Host allowlist were intentional for this loopback
smoke. They are not production authentication, distributed rate limiting, or
browser CORS policy.

## Reproduce the Inspector smoke

Start the quickstart application, then save this exact configuration as
`inspector.json`:

```json
{
  "mcpServers": {
    "soklet": {
      "type": "http",
      "url": "http://127.0.0.1:8081/catalog/mcp",
      "protocolEra": "modern"
    }
  }
}
```

Run:

```sh
npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/list --format json

npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/call --tool-name catalog.search \
  --tool-args-json '{"query":"sprocket"}' --format json
```

Expected observations:

- `tools/list` contains exactly the generated `catalog.search` definition for
  this endpoint and its Java-derived input/output schemas;
- `tools/call` completes and returns the typed structured result;
- no initialization call or session ID is required; and
- stopping the application completes and releases port 8081.

MCP Inspector documentation and releases are maintained by the MCP project:
[Inspector documentation](https://modelcontextprotocol.io/docs/tools/inspector)
and [Inspector releases](https://github.com/modelcontextprotocol/inspector/releases).

## Raw localhost HTTP recipe

This request checks the modern discovery boundary without relying on a host:

```sh
curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8081/catalog/mcp \
  --header 'Host: 127.0.0.1:8081' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'MCP-Protocol-Version: 2026-07-28' \
  --header 'Mcp-Method: server/discover' \
  --data '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

To invoke the quickstart tool, mirror its name in `Mcp-Name` as well:

```sh
curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8081/catalog/mcp \
  --header 'Host: 127.0.0.1:8081' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'MCP-Protocol-Version: 2026-07-28' \
  --header 'Mcp-Method: tools/call' \
  --header 'Mcp-Name: catalog.search' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"catalog.search","arguments":{"query":"sprocket"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

The name header is invocation-specific: depending on the selected method it
mirrors the tool/prompt name, resource URI, or task ID. Discovery and other
methods with no such selector must omit `Mcp-Name`; an unexpected name header
is rejected as a header mismatch.

The `Host` value includes the port because Soklet validates both host and
effective listener port. For local development, use `127.0.0.1` consistently
rather than mixing it with `localhost` unless both names are explicitly
allowed. Browser-based clients also need a deliberate Origin policy.

Expected success is HTTP 200, `Cache-Control: no-store`, and a JSON-RPC result
whose supported version is exactly `2026-07-28`. A `GET` or `DELETE` request is
expected to return 405; that is the modern stateless contract, not a failed
legacy session setup.

## Mainstream host setup notes

The untested rows above are not implied compatible. When testing a host, pin
and record its exact version, use its HTTP/Streamable HTTP server form, and
point it at the application's configured endpoint URL. A client that uses
`initialize` may connect only if the endpoint explicitly declares a supported
2025 revision; the 2026-only configuration used for the September 1 Inspector
smoke does not accept that wire protocol. The implemented 2025 tool adapter is
stateless: it does not provide Soklet 3.5.1 sessions, GET SSE, or the removed
standalone HTTP+SSE transport. Do not select an stdio command or a deprecated
transport when testing the HTTP endpoint.

- Visual Studio Code documents workspace/user MCP configuration in
  [Use MCP servers in VS Code](https://code.visualstudio.com/docs/copilot/chat/mcp-servers).
- Claude Code documents HTTP server configuration in
  [Connect Claude Code to tools via MCP](https://docs.anthropic.com/en/docs/claude-code/mcp).
- Cursor documents its host configuration in
  [Model Context Protocol](https://docs.cursor.com/context/model-context-protocol).

For every new host/version, record discovery, list, one invocation, expected
failure behavior, clean disconnect, and server shutdown/port release. Keep an
untested or incompatible result in the table instead of converting product
documentation into an unsupported compatibility claim.
