# Legacy prompt and resource qualification — September 29

The expanded compatibility path passed the bounded official scenarios and
Inspector CLI checks below. No core implementation fix was needed in this
slice. The CI fixture and runner now retain this prompt/resource coverage.

## Exact core artifact

The server used a freshly built, clean export of pushed core commit
`3fe09465fd1241ffb785493fdff4a2074f8b0e0a`, tree
`9a2f4962b35079fe40f72c0ce9845e0b2c101470`.
JAR SHA-256:
`c454eac7ff5fd58d564a2c1603133ed1c47a01210d2910be4d60f392ebe1184f`.
The [evidence manifest](evidence.json) records the sources JAR, POM, fixture,
runner, client, and toolchain identities. This is development qualification,
not an immutable release-candidate gate.

## Official conformance subset

The unchanged alpha.11 suite at commit
`a983ba93c91e0bb31d0b6849eeb52f0ad1083107` used the previously reviewed exact
dependency overlay. Its pristine source tree and built CLI were verified
after restoring the upstream lock. Both exact revisions ran these 14 scenarios:

- Initialization, ping, tool listing, simple text call, and tool error.
- Prompt listing, simple prompt, required arguments, embedded resource, and image.
- Resource listing, text read, binary read, and template read.

| Revision | Scenario runs | SUCCESS | INFO | FAILURE / WARNING / SKIPPED |
| --- | ---: | ---: | ---: | ---: |
| `2025-06-18` | 14 | 28 | 1 | 0 |
| `2025-11-25` | 14 | 29 | 1 | 0 |

Both INFO checks say that the optional session ID was omitted. All 28 commands
exited zero, 136 wire messages passed the suite's selected-revision schema
validation, and every fixture stopped cleanly without forced cleanup. The
runner rejects changed check IDs/statuses, message counts, empty prompt/resource
catalogs, unexpected stderr, and timeouts. Each scenario has a fresh loopback
JVM, a 60-second command bound, bounded output, and supervised cleanup.

The [raw official receipts](official-checks.json.gz) preserve every check and
CLI/fixture log. The separate modern conformance selection and its reviewed
exceptions are unchanged. This is not full 2025 conformance: subscriptions,
completion, sessions, SSE, and other excluded features were not selected.

## Named-client checks

Unmodified cached Inspector CLI **2.3.0** used `protocolEra: "legacy"` against
one exact revision per fixture. Its **26 cases passed**: tools list/call;
prompt list and all four retrieval forms; resource and template lists; text,
binary, and template reads; and an expected unknown-resource failure, repeated
for both revisions. Prompt arguments and template values included Unicode.
These were CLI interactions, not model-driven host sessions.

For the unknown resource, Soklet returned HTTP 400 with JSON-RPC code `-32002`
and the requested URI. Inspector reported an HTTP error containing that body
and did not start OAuth. This records the actual error behavior; it does not
claim that Inspector exposes that response as a typed JSON-RPC exception.

The first isolated Claude Code **2.1.274** run connected to the November-only
endpoint, but its model step exited 1 with `Not logged in · Please run /login`
before any resource invocation. That failed run remains in the
[original client receipts](client-results.json.gz). The command environment
could not access the client's authentication; the earlier statement that the
user's account was logged out was not established by this isolated probe.

The retry used Claude Code's normal authenticated environment. **All four
bounded model sessions passed**, using the same exact core JAR and compiled
fixture bytes: one resource session and one prompt-command session for each
2025 revision. Actual `ReadMcpResourceTool` calls read the static text and
template-backed resource, returning the expected text. MCP prompt commands
retrieved `test_prompt_with_arguments` with `世界` and `claude-qualification`,
and the model returned the exact rendered prompt. All six retrievals succeeded,
with no permission denials or timeouts; every fixture stopped cleanly.

The prompt-run wire capture shows the modern discovery probe receiving HTTP
400, fallback initialization selecting the exact 2025 revision, initialized
notification receiving 202, GET receiving the expected 405, and subsequent
catalogs and `prompts/get` receiving 200. Its loopback proxy forwarded MCP
headers and payloads unchanged, rewriting only HTTP Host for the fixture
listener. Resource runs connected directly. The
[model-session receipts](claude-model-results.json.gz) preserve the successful
tool exchanges, prompt wire captures, model results, and raw stdout hashes.
Account paths, session IDs, and authentication environment values are omitted.
No credential was given to the fixture, and no runtime fix was needed.

## Claude Desktop cloud connector check

Claude Desktop **2.16120.0** and its cloud backend passed a separate bounded
check against the same exact core JAR and compiled fixture bytes. The endpoint
served only `2025-11-25`. A temporary token-gated HTTPS tunnel reached the
loopback fixture; the proxy forwarded MCP headers and payloads unchanged,
rewriting only HTTP Host and the token-gated URL path. Cloud backend version
was not exposed; observed client identities were `Anthropic`,
`Anthropic/Toolbox`, and `Anthropic/ClaudeAI`, each reporting `1.0.0`.

The connector's **Add from** picker listed all four prompts and both static
resources. Selecting the parameterized prompt with `世界` and
`cloud-qualification` produced an actual `prompts/get` with HTTP 200. Selecting
the static text resource produced two `resources/read` requests for
`test://static-text`, both HTTP 200. Claude consumed the returned attachments
and quoted their exact text, including Unicode. A model-driven
`test_simple_text` control call, approved once, also returned HTTP 200 and the
expected text. Prompt/resource retrievals were user-selected; their content
was subsequently consumed by the model. They were not tool wrappers.

The retained 17 cloud exchanges include two modern discovery probes receiving
the expected unsupported HTTP 400, successful fallback initialization selecting
November, initialized notifications receiving 202, and successful tool, prompt,
and resource catalogs. One separate preflight is excluded from host counts.
The [cloud receipt](claude-cloud-results.json.gz) preserves captured headers
and bodies with trace IDs redacted, plus the qualification UI observations and
cleanup evidence. Temporary URLs, access tokens, account identifiers, and
unrelated UI content are omitted.

The fixture stopped cleanly, the tunnel process stopped, both local listeners
closed, and the temporary connector was disconnected. Its disconnected entry
and the qualification chat remain in Claude. No core implementation fix was
needed. The earlier local/cloud tool smokes retain their original source
identities and scope.

## Remaining qualification

This cloud smoke qualifies the November parameterized text prompt and static
text resource path. It does not qualify June cloud behavior, cloud template or
binary/image consumption, OAuth or admission denial, GET/SSE, or Apps. The
broader official and Inspector coverage above does not imply those cloud host
operations passed.

The implementation still excludes legacy Apps, Skills, Tasks, subscriptions,
completion, multi-round input/state, server-initiated requests, persistent SSE,
and `2025-03-26`. Candidate and downstream pins remain deferred while core
implementation continues.
