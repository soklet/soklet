# MCP client and host compatibility

This launch-facing matrix records what was actually exercised, with exact tool
versions and a manual-smoke date. It is not a candidate release gate and does
not create a release-validation PASS receipt.

## Matrix

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
| A client fixed to Soklet 3.5.1's initialization/session/GET-SSE contract | Legacy profile, independent of product version | Cannot use the 4.0.0 endpoint without a client migration. | **INCOMPATIBLE BY DESIGN** |

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
was exercised. Before publishing, repeat the same smoke against the exact
candidate JAR; after Central synchronization, repeat it from a clean directory
against the public `com.soklet:soklet:4.0.0` coordinate.

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

The untested rows above are not implied compatible. When testing them, pin and
record the exact host version, use its HTTP/Streamable HTTP server form, and
point it at the same endpoint URL. Do not select an stdio command, the removed
standalone HTTP+SSE transport, or a client mode that requires `initialize` and
an MCP session ID.

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
