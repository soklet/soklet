# Model Context Protocol (MCP)

Soklet 3.6.0 targets the MCP `2026-07-28` server protocol. MCP support is part
of core Soklet and uses a dedicated `McpServer` listener; it is not mounted in
the ordinary `HttpServer` or `SseServer`. The API and implementation ship in
the zero-runtime-dependency `com.soklet:soklet` artifact; there is no separate
`soklet-mcp` component.

This guide describes the implemented, locally frozen Phase 4 surface in the
current `3.6.0-SNAPSHOT`. It is development documentation, not a release or
final conformance claim. Compile-checked programmatic and annotation-driven
applications live outside this source repository in the project-root
`mcp/examples/phase-4` workspace.

## Current support

| Area | Current behavior |
| --- | --- |
| Transport | Dedicated HTTP/1.1 listener and port; direct first-request discovery; no initialization or session lifecycle |
| Endpoints | One or more exact, non-root paths on one server; capability and operation catalogs remain endpoint-local |
| Tools | Annotated and programmatic discovery, typed or JSON-object arguments, complete typed results, content results, rate limiting, interception, and output sanitization |
| Prompts | Annotated and programmatic catalogs plus string-argument prompt rendering |
| Resources | Exact URIs, bounded RFC 6570 Level 1 URI templates, reads, static catalogs, and application-owned custom listing/pagination |
| Policy | Host and Origin checks, application admission, optional request limiting, mandatory fallback tool limiting for tool-bearing servers, bounded execution, and shared Soklet observation hosts |
| Schema | Closed, Java-first Soklet MCP Tool Schema Profile 1; no public hand-authored schema registration |

Progress delivery, operational cancellation, resource-subscription delivery,
multi-round-trip `input_required`, protected request-state execution, trace
correlation, complete MCP telemetry, and MCP simulation are not implemented
yet. Public descriptors already reserved for that Phase 5/6 work are
behaviorally neutral and do not cause Soklet to advertise those capabilities.

## Server and request model

`McpServer.withPort(...)` always creates an independent listener. A Soklet
application may manage HTTP, SSE, and MCP servers together through the
corresponding `SokletConfig` builder setters, but each retains its own bind
address and port.

MCP `2026-07-28` is stateless. Clients do not initialize a session and do not
need to reuse a connection. A client may call `server/discover` immediately.
Every request restates its protocol version and client capabilities; Soklet
validates those fields before application admission and exposes normalized,
bounded request information through `McpRequestContext` and the operation-
specific context.

One server may host multiple `McpEndpoint` instances. Endpoint selection uses
the normalized exact request path. Tool, prompt, and resource names may repeat
on different endpoint paths without leaking across them, while handler slots,
the admitted queue, active JSON-RPC IDs, and the server lifecycle remain
server-wide.

Every server must configure:

- a nonempty `McpHandlerResolver`;
- one `McpRequestAdmissionPolicy`; and
- a server-level fallback `McpRateLimiter` if any endpoint has a tool.

The fallback tool limiter remains required even if every tool has an endpoint
or tool override. Each tool call is charged by exactly one limiter, resolved in
tool, endpoint, then server-fallback order; requiring the fallback makes that
resolution total without relying on override coverage.

The listener binds to `127.0.0.1` by default. Use `host(...)` deliberately for
a container or remote deployment, and configure the deployment's Host names
with `allowedHosts(...)`. Soklet does not terminate TLS.

## Endpoint authoring

### Annotations

`@McpServerEndpoint` declares the path and implementation information.
`@McpTool`, `@McpPrompt`, `@McpResource`, and `@McpListResources` declare its
operations. `SokletProcessor` validates the declarations and writes immutable
descriptors at compile time; Soklet performs no runtime classpath scan of
handler methods. Load selected generated endpoint classes through
`McpHandlerResolver.fromClasses(...)`.

Compile annotated endpoints with `SokletProcessor`, retain parameter names,
and preserve the generated endpoint provider/index resources when shading.
For a named Java module, open or export the endpoint package to Soklet. A
package containing a non-public record used for runtime conversion must be
open to Soklet.

`McpHandlerResolver.fromClasses(...)` selects generated endpoint classes in an
explicit order. `fromClasspathIntrospection(...)` loads every generated
endpoint visible from the context class loader in binary-name order. Both
forms accept an `InstanceProvider`; the provider is called for each annotated
operation invocation, not during discovery or catalog listing, and Soklet does
not retain or close the returned instance.

### Programmatic registration

Programmatic endpoints use the same immutable runtime model. Start with
`McpEndpoint.withPath(...)`, provide the required `McpImplementation`, append
tool, prompt, and resource registrations, and pass the built endpoints to
`McpHandlerResolver.fromEndpoints(...)`.

Registration order is discovery order. Names and resource addresses must be
unique within one endpoint. The advertised capability set is derived from the
registrations; there is no separate capability switch that can drift from the
handlers.

## Tools and typed schemas

The staged `McpToolRegistration` builder makes the argument/result choice
before a handler can be supplied:

| Stage | Use it when |
| --- | --- |
| `types(argumentType, resultType)` | The tool always completes with a supported structured Java result. Soklet derives and enforces both schemas and converts in both directions. |
| `argumentType(argumentType)` | Input should be converted to Java, but the advanced handler needs to return a recognized `McpOperationResult` directly. |
| `jsonArguments()` | The handler wants the immutable `McpJsonObject` directly. Soklet publishes and enforces the fixed `{"type":"object"}` input schema. |

Class tokens cover ordinary types; `TypeReference<T>` preserves nested generic
types such as `List<Item>`. Advanced handlers may produce supported text,
image, audio, embedded-resource, and structured tool content. Soklet rejects a
null result, an unknown `McpOperationResult` implementation, a result that is
wrong for the selected method, or structured output that does not match the
derived output schema.

Typed derivation accepts this closed Java shape family:

- `boolean`, `byte`, `short`, `int`, `long`, `float`, and `double`, plus their
  wrappers;
- `BigInteger`, `BigDecimal`, and `String`;
- enums, arrays, `List<T>`, and `Map<String, T>`;
- records, including supported generic record instantiations; and
- `Optional<T>` only at a record-property or annotated-argument boundary.

A typed tool input root must be a record, a `Map<String, T>`, or the synthetic
object formed from annotated tool arguments. A bare typed `String` output is
rejected because it is ambiguous with text content. Arbitrary beans,
`Object`, non-`String` map keys, raw generics, sets, unresolved wildcards/type
variables, unsupported `CharSequence` implementations, and unsafe recursive
record shapes fail at registration or annotation processing.

### Tool Schema Profile 1

Soklet MCP Tool Schema Profile 1 is a closed generation and evaluation profile
based on JSON Schema Draft 2020-12. It is not complete Draft 2020-12 support.
Applications may inspect an `McpSchema`, but cannot construct, compile, or
replace one, and Soklet never fetches a network reference.

Profile 1 recognizes `$schema`, `$defs`, `$anchor`, `$ref`, `$comment`,
`properties`, `additionalProperties`, `items`, `allOf`, `anyOf`, `if`, `then`,
`else`, `type`, `enum`, `const`, `required`, `minimum`, `maximum`, `title`,
`description`, `default`, `examples`, `deprecated`, `readOnly`, `writeOnly`,
`format`, and `x-mcp-header`.

Every other keyword fails closed. In particular, Profile 1 explicitly rejects
`$id`, `$vocabulary`, `$dynamicAnchor`, `$dynamicRef`, `oneOf`, `not`,
dependent schemas, tuple/contains keywords, regex-bearing `pattern` and
`patternProperties`, property-name constraints, length/item/property-count
constraints, `multipleOf`, exclusive numeric bounds, unevaluated keywords, and
content-schema keywords. `$ref` is limited to same-document `#` JSON Pointer
fragments and local plain-name anchors.

Production parsing and evaluation are bounded independently. Important fixed
defaults include 4 MiB input/output JSON, JSON depth 128, 100,000 JSON or typed
binding nodes, 4,096 compiled schema nodes, schema depth 64, 32,768 keywords,
one million evaluation operations, and 128 active evaluation calls. These are
resource ceilings, not recommended payload sizes. Soklet charges bounded work
before allocation and returns sanitized validation failures.

## Prompts

A prompt is a named, discoverable template that returns ordered user and
assistant messages. Prompt arguments are strings rather than JSON-Schema
values. `McpPromptRegistration` declares required/optional
`McpPromptArgumentDefinition` entries; `@McpPromptArgument` is the annotated
equivalent. Missing, duplicate, unknown, or non-string arguments fail before
the application handler runs.

Soklet validates prompt structure, but prompt injection, authorization, and
the safety of any application resource access remain handler responsibilities.
Treat prompt text and arguments as untrusted input.

The immutable prompt catalog follows registration order and is returned as one
page. A present cursor is invalid because Soklet 3.6.0 does not expose dynamic
prompt-list pagination or a prompt list-change publisher.

## Resources and pagination

An exact resource registration has one concrete URI and contributes to the
static `resources/list` fallback. A URI-template registration uses bounded RFC
6570 Level 1 variables, is advertised by `resources/templates/list`, and is
selected for reads only after exact-resource matching. Exact URI identity uses
RFC 3986 syntax equivalence; declared descriptor spelling is preserved.

Without a custom list handler, Soklet returns exact registrations in
registration order as one page, excludes templates, and rejects every present
cursor. With `McpResourceListHandler` or `@McpListResources`, the application
handler is the sole authority for every `McpResourcePage`—Soklet never merges
static registrations into the handler result. The handler reads the optional
cursor from `McpResourceListContext` and places any following cursor on the
returned page.

`list.getRegisteredResourceDescriptors()` is only an immutable convenience
view of exact registrations. It excludes templates and is not automatically
authorization-filtered.

Cursors are opaque application strings. Soklet preserves the distinction
between absent and present-empty cursor values and enforces a positive UTF-8
size limit (4,096 bytes by default) on incoming and outgoing cursors. The
application owns cursor encoding, validation, expiry, integrity,
authorization binding, backing-snapshot behavior, and cross-instance
portability. An invalid application cursor should produce a safe application
error; Soklet has no cursor store, signing key, or pagination magic.

Soklet ships no `file://` mapper. A handler that maps resource URIs to a
filesystem owns root containment, traversal rejection, canonicalization,
symlink policy, and authorization.

Resources and catalogs carry an `McpCachePolicy` with private/public scope and
a nonnegative time to live. A dynamic resource page may override only its time
to live; its endpoint-level scope remains fixed across pages. Use private scope
for any catalog whose contents vary by caller identity or authorization. Use
public scope only when the same descriptors are safe to share across callers.
Protocol cache hints do not turn the HTTP transport into a shared cache: MCP
transport responses use `Cache-Control: no-store`.

## Admission and identity

`McpRequestAdmissionPolicy` is the authentication, authorization, and
admission boundary for every structurally valid MCP request and notification.
It is mandatory and may be invoked concurrently. Failures and null decisions
fail closed.

`McpRequestAdmissionPolicy.acceptAllInstance()` deliberately accepts the
canonical anonymous identity. It is convenient for a loopback example, not a
production authentication mechanism. An authenticated acceptance supplies an
`McpAdmissionIdentity` with stable, bounded rate-limit and authorization
partition keys and may attach an application principal. Those keys must not be
self-reported client values. Client information, client capabilities, request
`_meta`, and server information are informational rather than authenticated
identity.

Each request is independent. Applications must not rely on authentication,
capabilities, or other metadata from an earlier request on the same TCP
connection. Cross-request application state needs its own explicit identifier
on every request.

## Rate limiting

`McpRateLimiter` is the single thread-safe application SPI. Soklet never closes
an application limiter. An implementation can store state in-process or call a
distributed system such as Redis.

The optional server request limiter runs once after admission for every
request or notification. A tool call then runs exactly one resolved tool
limiter in this order: tool override, endpoint override, server fallback.
Named and direct setters are mutually exclusive and last-call-wins; every name
in `McpRateLimiterRegistry` resolves when the immutable server is built.

The built-in `McpRateLimiter.fromInMemoryDefaults()` is a finite, bounded token
bucket local to one JVM. It is not fleet-wide enforcement. A denial returns
HTTP 429, `Retry-After`, and MCP error `-31999` for a request; a notification
has the HTTP status but no JSON-RPC body. The first denial wins and successful
charges are never refunded after a later denial, failure, timeout,
cancellation, or write failure. Refill accounting uses a private monotonic
clock; there is no public clock or reset/test-mode seam.

## Handler execution, interception, and output

Handlers are synchronous. Defaults are 32 active application handlers, 128
queued requests, and a 60-second absolute request timeout. Queue capacity and
handler concurrency are independent positive finite bounds. A supplied
executor changes where work runs but cannot bypass them. Capacity rejection is
HTTP 503 with JSON-RPC `-32603`; a queued request whose absolute deadline
expires never enters application code.

A timeout or disconnect terminates the current framework response path, but
the Phase 4 runtime does not yet deliver the Phase 5 cooperative-cancellation
signal to application handlers. Java cannot forcibly stop a non-cooperative
handler, so it retains its execution slot until it actually exits even if the
client request has already completed.

One `McpHandlerInterceptor` wraps every application-owned tool call, prompt
get, resource read, and custom resource list handler. Its continuation is
synchronous, same-thread, call-lifetime-bound, and one-shot. Framework-owned
discovery and static catalogs do not pass through it because no application
handler exists to intercept.

For a tool call, the application pipeline is admission, optional request
limiter, resolved tool limiter, bounded dispatch, handler interceptor, complete
input conversion/validation and handler invocation, then output sanitization
and final result validation. A capacity or deadline rejection happens before
application interception. A successful dispatch slot remains charged until
the handler/interceptor call actually exits.

`McpToolOutputSanitizer` runs at the tool-output boundary. Interceptor
short-circuits and sanitizer replacements still undergo method compatibility,
recognized-result, content, and structured-output validation. Application
exceptions and unsafe outputs fail closed without reflecting secrets or raw
exception text to the client.

## HTTP and error policy

MCP messages use POST. `OPTIONS` exists only for the CORS preflight path; GET
and DELETE return 405. POST requires `Content-Type: application/json`, and
`Accept` must permit both `application/json` and `text/event-stream` according
to the protocol's negotiation rules.

Every JSON-RPC request carries `Mcp-Method`. Tool calls, prompt gets, and
resource reads also carry `Mcp-Name`; each header must agree with the JSON-RPC
method or selected operation. Notifications are exempt from these header
requirements. Legacy `MCP-Session-Id` and `Last-Event-ID` headers are ignored,
never stored, and never echoed.

An identifiable `notifications/cancelled` message still traverses version
validation, admission, and request limiting, then returns an empty HTTP 202.
Its payload is ignored and it never cancels active work in the Phase 4 runtime,
including when the supplied ID names an active request. Other notifications
never receive a JSON-RPC response body.

Implemented framework mappings are stable:

| Condition | HTTP | JSON-RPC/MCP code |
| --- | ---: | ---: |
| Rate-limit denial | 429 | `-31999` |
| Strict unknown mirrored header | 400 | `-31998` |
| Handler capacity exhausted | 503 | `-32603` |
| Standard or custom header mismatch | 400 | `-32020` |
| Unsupported protocol version | 400 | `-32022` |
| Missing required capability | 400 | `-32021` |
| Specified invalid parameters | 400 | `-32602` |
| Unknown request method | 404 | `-32601` |

Readable request IDs retain their original string or integer identity. Error
factories prevent applications from spoofing JSON-RPC-, MCP-, or Soklet-owned
reserved codes, and framework failures omit unsafe application diagnostics.

## Host, Origin, and CORS

Host validation is independent of CORS and runs before protocol parsing or
application side effects. The listener's effective authority is accepted;
`McpServer.Builder.allowedHosts(...)` adds deployment-specific hostnames or IP
literals.

An absent `Origin` is allowed by default and may instead be required with
`McpAbsentOriginPolicy.REQUIRE_ORIGIN`. A present Origin is rejected unless
the existing shared `CorsAuthorizer` approves it. Omitting the authorizer uses
reject-all behavior for present origins and emits one fixed startup diagnostic;
supplying `CorsAuthorizer.rejectAllInstance()` makes that choice explicit.

Custom CORS implementations must be thread-safe and support the shared
transport-neutral preflight overload. Deliberate denial is HTTP 403. A null,
throwing, or out-of-surface authorizer result fails closed without CORS allow
headers. See [SECURITY.md](SECURITY.md#mcp-deployment-security) for deployment
guidance.

The allowed request-header surface contains the modern protocol/name headers,
registered `Mcp-Param-*` headers, and `Authorization`; it contains no legacy
session/replay header. Successful CORS responses can expose
`WWW-Authenticate` for application-owned authentication challenges.

## Mirrored tool headers

`@McpHeader("Tenant")` on a typed tool argument publishes the `x-mcp-header`
schema extension and requires `Mcp-Param-Tenant` to agree with that property
already parsed from the JSON arguments. It never supplies an absent or null
argument from the header. Mirroring is limited to statically reachable string,
boolean, or JavaScript-safe integer properties. Both values remain untrusted
input.

Unknown `Mcp-Param-*` headers are ignored by default and never become tool
arguments. `McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS` enables request-
only strict rejection with HTTP 400/MCP `-31998`. Name-bearing diagnostics are
separate, bounded, disabled by default, and may expose attacker-supplied header
names to application logging and retention systems; Soklet never logs their
values through that diagnostic.

## Lifecycle and metrics

MCP reuses Soklet's existing `LifecycleObserver` and `MetricsCollector` hosts.
There is no parallel MCP observer or collector, and
`McpHandlerInterceptor` is not an observability substitute. The current Phase
4 runtime emits the admitted-request lifecycle start/finish pair and the
corresponding `McpMetricsEvent.RequestStarted` and
`McpMetricsEvent.RequestFinished` events for framework and application
operations. Callback failures are logged and contained, and user callbacks do
not run under MCP runtime or dispatcher locks.

The frozen shared-host descriptors also refer to provisional metric-event,
metric-snapshot, request-outcome, and stream-termination types. Server
diagnostics, status, and shutdown-outcome types are Phase 6-owned.
Comprehensive event storage, connection/stream/subscription instrumentation,
simulator integration, and final metric rendering remain Phase 6 work.

## Compatibility and unsupported features

The 3.6.0 MCP API and wire behavior are intentionally incompatible with
Soklet's pre-3.6.0 MCP implementation. Applications that require MCP
`2025-11-25` must remain on Soklet 3.5.x; there is no adapter or dual-protocol
mode.

Soklet 3.6.0 does not provide stdio transport, public arbitrary JSON Schema
registration, MCP Completion, MCP logging capability, mutable tool/prompt list
publishers, or an application result-extension registry. OAuth resource-server
metadata and identity-provider behavior remain deployment responsibilities;
applications may implement authentication and standards-compliant challenges
at the admission boundary. Doing so does not by itself make core Soklet or the
deployment fully conformant with MCP Authorization; the deployment must meet
every applicable authorization-server and resource-server obligation.

The official MCP conformance suite is pinned and automated as release
evidence. A fresh packaged candidate built from the final frozen Phase 4 source
passed all 23 currently active scenarios and their complete expected-check
profiles. The supported JDK 17/25 CI legs remain open, and this
candidate-development result is not release-candidate evidence. Do not treat
this snapshot guide as a release-conformance statement.
