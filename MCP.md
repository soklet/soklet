# Model Context Protocol (MCP)

Soklet 3.6.0 targets the MCP `2026-07-28` server protocol. MCP support is part
of core Soklet and uses a dedicated `McpServer` listener; it is not mounted in
the ordinary `HttpServer` or `SseServer`. The API and implementation ship in
the zero-runtime-dependency `com.soklet:soklet` artifact; there is no separate
`soklet-mcp` component.

This guide describes the implemented, locally frozen Phase 4 surface plus the
live Phase 5 multi-round-trip request-state, progress/cancelation, resource-
subscription, deterministic termination, cross-instance state, and residual-
shutdown slices in the current `3.6.0-SNAPSHOT`. It is development
documentation, not a release or final conformance claim. Compile-checked
programmatic and annotation-driven applications live outside this source
repository in the project-root `mcp/examples/phase-4` workspace.

## Current support

| Area | Current behavior |
| --- | --- |
| Transport | Dedicated HTTP/1.1 listener and port; direct first-request discovery; no initialization or session lifecycle |
| Endpoints | One or more exact, non-root paths on one server; capability and operation catalogs remain endpoint-local |
| Tools | Annotated and programmatic discovery, typed or JSON-object arguments, complete typed results, content results, rate limiting, interception, and output sanitization |
| Prompts | Annotated and programmatic catalogs plus string-argument prompt rendering |
| Resources | Exact URIs, bounded RFC 6570 Level 1 URI templates, reads, static catalogs, and application-owned custom listing/pagination |
| Multi-round-trip | Declared `input_required` results and retries for tools, prompt gets, and resource reads; application- or framework-protected request state |
| Invocation control | Request-scoped progress over the MCP response stream plus cooperative cancelation for every application handler |
| Subscriptions | Long-lived `subscriptions/listen` streams for resource-list changes and updates to requested resource URIs; application-owned local or distributed broadcast publishing |
| Policy | Host and Origin checks, application admission, optional request limiting, mandatory fallback tool limiting for tool-bearing servers, bounded execution, and shared Soklet observation hosts |
| Schema | Closed, Java-first Soklet MCP Tool Schema Profile 1; no public hand-authored schema registration |

Operational trace correlation, comprehensive MCP telemetry, and MCP simulation
are not implemented yet. Public descriptors already reserved for that
remaining Phase 6 work are behaviorally neutral and do not cause Soklet to
advertise those capabilities.

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
the admitted queue, and the server lifecycle remain server-wide.

JSON-RPC requires a sender not to reuse an ID while an earlier request from
that sender is still in flight. That is a sender obligation, not a receiver-
side global namespace. Because this protocol is stateless, Soklet cannot
reliably infer whether two HTTP requests came from one sender; a connection,
endpoint, admitted identity, or authorization partition is not a protocol
sender identity. Soklet therefore correlates each response within its own
request/stream and permits independent concurrent requests to carry the same
string or integer ID. It does not reserve IDs across the listener or reject a
request merely because another live request has an equal ID.

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

## Multi-round-trip input and request state

Tools, prompt gets, and resource reads may return `McpInputRequiredResult`
when they need a supported client request, state for a later retry, or both.
Programmatic operations declare every possible `McpInputRequestDeclaration`
with `mayRequestInput(...)`; annotated operations use `@McpMayRequestInput`.
Soklet supports the core `elicitation/create`, `sampling/createMessage`, and
`roots/list` declarations, validates their method-specific parameters, and
rejects an emitted declaration that was not registered for that operation.

`McpInputRequirement.REQUIRED` makes the declaration's capabilities mandatory
before admission on every call. `CONDITIONAL` defers that check until the
handler actually emits the request. All missing capabilities from one result
are reported together before result metadata, request parameters, request
state, or a custom protector is evaluated. A retry's exact responses are
available through `McpRequestContext.getInputResponses()` as raw
`McpJsonValue` values or through its intrinsic typed lookup methods. The same
admitted `McpRequestContext` instance, including verified responses and state,
is supplied to lifecycle callbacks, the handler interceptor, and the handler
for that request.

For example, this raw-JSON tool asks the client for roots and lets Soklet carry
JSON state between calls:

```java
McpInputRequestDeclaration roots = McpInputRequestDeclaration.fromRoots(
  McpInputRequirement.CONDITIONAL
);

McpToolRegistration<McpJsonObject> tool = McpToolRegistration
  .withName("catalog.continue")
  .jsonArguments()
  .handler((request, call, features) -> {
    if (request.getRequestState().isEmpty()) {
      return McpInputRequiredResult.builder()
        .inputRequest("roots", McpInputRequest.fromDeclaration(
          roots, McpJsonObject.emptyInstance()))
        .frameworkRequestState(McpJsonObject.builder()
          .put("phase", "waiting-for-roots")
          .build())
        .build();
    }

    McpJsonObject state = (McpJsonObject) ((McpFrameworkRequestState)
      request.getRequestState().orElseThrow()).value();
    request.getInputResponses().find("roots").orElseThrow();
    return McpCompleteResult.fromToolText(((McpJsonString)
      state.find("phase").orElseThrow()).value());
  })
  .mayRequestInput(roots)
  .requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
  .build();
```

`McpRequestStateMode.NONE` is the default. The other modes have deliberately
different ownership:

- `APPLICATION_PROTECTED` sends the exact nonempty string supplied through
  `applicationRequestState(...)` and returns the exact echoed value as
  `McpApplicationRequestState`. Soklet applies a fixed 65,536-byte UTF-8 bound
  but does not parse, protect, expire, authorize, round-limit, or otherwise
  interpret it. No `McpProtectionConfig` is required.
- `FRAMEWORK_PROTECTED` accepts application JSON through
  `frameworkRequestState(...)`, emits an opaque protected string, and returns
  verified JSON as `McpFrameworkRequestState`. Any operation using this mode
  makes a server-wide `McpProtectionConfig` mandatory.

Choose framework protection explicitly:

- `McpProtectionConfig.withKeyRing(...)` is the production built-in. Supply
  operator-generated `McpProtectionKey` material through an initial
  `McpProtectionKeyRing`; each server copies the ring and exposes live rotation
  through `McpServer.getProtectionControl()`.
- `withDevelopmentEphemeralProtection()` creates process-local keys and emits
  a startup diagnostic. State cannot survive a restart or move between server
  instances, so this mode is for development only.
- `withRequestStateProtector(...)` delegates sealing and opening to one
  thread-safe application provider, suitable for a fleet-owned key service or
  envelope. The provider must authenticate the exact associated data in
  `McpRequestStateProtectionContext`; Soklet still owns canonical JSON,
  binding, size, lifetime, round, and prior-request-ID checks.

The defaults are 65,536 encoded bytes, 49,152 decoded bytes, a 15-minute
lifetime, and 10 rounds; `McpProtectionConfig.Builder` can lower or raise them
within its validated contract. Framework state is bound to the normalized
endpoint path, protocol version, JSON-RPC method, admitted authorization
partition, and stable request parameters. The parameter digest excludes only
the retry's `inputResponses` and `requestState` plus transient `_meta`
progress/trace/baggage fields, allowing those fields to change without moving
state to a different operation or authorization partition.

The first emission records round 1, issuance/expiry, and the emitting request
ID. Re-emission preserves the original expiry, increments the round, and
records the current request ID. The next retry must use an ID different from
the request that emitted that particular state. This prior-ID check is not a
server-side single-use store: an application that needs stronger replay or
workflow-consumption semantics must enforce them itself.

Built-in framework state can continue on another Soklet instance when both
instances use the same production protection material and admission resolves
the retry to the same authorization partition. A matching key ID with
different bytes is not equivalent. Different material or a different
partition fails as the same sanitized HTTP 400 / JSON-RPC `-32602` invalid-
state response before lifecycle observation, interception, or handler entry.
Development-ephemeral protection is intentionally not portable. A custom
protector may provide fleet portability, but it must preserve the same binding
and associated-data contract.

Soklet checks request-state wire shape and size before capability checks or
admission, but does not cryptographically open structurally valid state until
after accepted admission and authorization-partition resolution. Consequently
a missing required capability or admission rejection wins over a later
tamper/binding failure. Invalid, tampered, expired, wrong-bound, over-round, or
same-prior-ID framework state returns HTTP 400 / JSON-RPC `-32602`; temporary
protector unavailability returns HTTP 503 / `-32603`. Invalid-state reports
and malformed, noncanonical, empty, or oversized plaintext returned while
opening custom-protected state collapse to the same 400 / `-32602` response.
Null or unexpected provider behavior, invalid sealing output, and invalid
application output fail closed as HTTP 500 / `-32603`, without reflecting
provider diagnostics.

`input_required` results intentionally carry no protocol cache hints, and tool
input-required results bypass the complete-output sanitizer. A completed
resource read on any retry carrying `inputResponses` or `requestState` is
forced to private scope with zero TTL, regardless of its registration cache
policy. Every HTTP transport response remains `Cache-Control: no-store`.

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

A timeout, disconnect, server shutdown, or response-stream backpressure
failure cancels the invocation's `CancelationToken`. Soklet also interrupts
the dispatch thread where applicable, but Java cannot forcibly stop a
non-cooperative handler. Such a handler retains its execution slot until it
actually exits even if the client request has already completed.

The same non-forcible rule applies to application-supplied request-pipeline
callbacks such as admission, rate limiting, and custom request-state
protection. Terminal ownership prevents a protector or handler that returns
late from publishing a result. Framework request/transport state is released
at the terminal boundary, while the finite application execution remains
accounted until it actually exits.

One `McpHandlerInterceptor` wraps every application-owned tool call, prompt
get, resource read, and custom resource list handler. Its continuation is
synchronous, same-thread, call-lifetime-bound, and one-shot. Framework-owned
discovery and static catalogs do not pass through it because no application
handler exists to intercept.

For a tool call, the application pipeline is structural and required-capability
validation, admission, framework-state opening when present, observation,
optional request limiting, resolved tool limiting, bounded dispatch, handler
interception, complete input conversion/validation and handler invocation,
then output sanitization and final result validation. A capacity or deadline
rejection happens before application interception. A successful dispatch slot
remains charged until the handler/interceptor call actually exits.

`McpToolOutputSanitizer` runs at the tool-output boundary. Interceptor
short-circuits and sanitizer replacements still undergo method compatibility,
recognized-result, content, and structured-output validation. Application
exceptions and unsafe outputs fail closed without reflecting secrets or raw
exception text to the client. An `input_required` result is validated through
its separate declaration/request-state path and does not pass through the
complete tool-output sanitizer.

## Progress and cooperative cancelation

Every application-owned tool, prompt, resource-read, and custom resource-list
handler receives one invocation-scoped `CancelationToken`. Programmatic
handlers retrieve the exact feature instance from `McpInvocationFeatures`:

```java
CancelationToken cancelation =
    features.require(CancelationToken.class);

features.find(McpProgressReporter.class).ifPresent(reporter ->
    reporter.report(McpProgressUpdate.withProgress(50.0d)
        .total(100.0d)
        .message("Halfway")
        .build()));

cancelation.throwIfCanceled();
```

Annotated tool, prompt, resource, and resource-list methods may instead inject
one `CancelationToken` and one `Optional<McpProgressReporter>` directly. They
may also request `McpInvocationFeatures`; direct injection and feature lookup
return the same invocation-scoped instances. A bare `McpProgressReporter`
parameter is rejected because progress is legitimately unavailable for some
requests.

The cancelation token is always present after an application handler is
selected. It is signaled by client disconnect, the absolute request deadline,
server shutdown, or a response-stream write/backpressure failure. Cancelation
is cooperative: handlers should check between expensive operations, register a
short nonblocking callback with `onCancel(...)`, or call
`throwIfCanceled()`. Reports made after cancelation or terminal completion have
no effect.

A progress reporter is present only when the initiating request supplied a
valid string or integer at `params._meta.progressToken` and Soklet can safely
commit request-scoped SSE. Soklet preserves that opaque token's string or
integer form exactly. `McpProgressUpdate` accepts finite floating-point
`progress` and optional `total` values plus an optional message. Accepted
progress values must strictly increase: an equal value is coalesced and a
decrease while the invocation is active throws `IllegalArgumentException`.
Delivery is synchronous through the request's bounded SSE queue, so a slow
client applies bounded backpressure to the reporting handler. Progress and
keep-alive writes never extend the absolute request deadline.

If an operation has a missing `CONDITIONAL` input-request capability, Soklet
must keep the response uncommitted until the handler chooses a complete or
`input_required` result. The progress reporter is therefore absent for that
invocation even if the request carried a token, and suppressed reports are
never replayed later. A missing `REQUIRED` capability still fails during
preflight before the handler runs.

## Resource subscriptions

Soklet implements `subscriptions/listen` as a framework-owned, long-lived POST
SSE stream on the dedicated MCP listener. Applications enable it per endpoint
by attaching an `McpSubscriptionConfig` with an application-owned
`McpSubscriptionEventPublisher` and one or both supported notification types:

```java
McpSubscriptionEventPublisher publisher =
    McpLocalSubscriptionEventPublisher.fromDefaults();

McpSubscriptionConfig subscriptions =
    McpSubscriptionConfig.withEventPublisher(publisher)
        .notificationType(
            McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED)
        .notificationType(
            McpSubscriptionNotificationType.RESOURCE_UPDATED)
        .build();

McpEndpoint endpoint = McpEndpoint.withPath("/mcp")
    // registrations
    .subscriptions(subscriptions)
    .build();
```

The built-in publisher broadcasts synchronously within one process. A custom
thread-safe implementation may bridge Redis or another distributed system,
but it must retain broadcast semantics: every attached Soklet listener gets
the event. Soklet subscribes when its server starts, closes only its listener
registration when the server stops, and never closes the application-owned
publisher. Shutdown first fences the old generation's callback, then invokes
application registration close outside lifecycle locks on bounded daemon
cleanup workers. A throwing or in-flight close remains residual state and
blocks restart; calling `stop()` again retries or joins that cleanup without
overlapping close invocations. Waiting remains bounded by the server's original
global shutdown timeout. Application code publishes coarse change events with
`publishResourcesListChanged()` or `publishResourceUpdated(URI)`; Soklet owns
authorization, requested-filter matching, per-stream coalescing, bounded
queues, backpressure, and wire serialization.

The listener parses all protocol filter fields but acknowledges and emits only
the configured resource-list and requested-resource update families. Tool and
prompt catalogs remain immutable, so their list-change filters are never
acknowledged or advertised. The acknowledgment is always the first stream
message. Every subscription message carries the listen request's exact string
or integer ID as `io.modelcontextprotocol/subscriptionId`. That reuse does not
make the ID listener-global: independent subscriptions with equal IDs remain
separate streams and may coexist, including across authorization partitions.

A valid listen request traverses admission and the optional request limiter;
it does not invoke an application handler, `McpHandlerInterceptor`, a tool
limiter, or consume an application handler slot. Stream count per admitted
principal, duration, pending queue size, and write-idle time are bounded.
Keep-alive comments prevent an otherwise idle writable stream from reaching
its write-idle timeout; `keepAliveInterval` must therefore be strictly shorter
than `writeTimeout`, and `McpServer.Builder.build()` rejects an invalid pair.
A slow or disconnected subscriber is cleaned up without blocking unrelated
subscribers. Graceful HTTP server shutdown sends only the tagged empty terminal
`complete` result when writable; Soklet never emits the stdio-only server
`notifications/cancelled` message on HTTP.

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

An identifiable HTTP `notifications/cancelled` message still traverses version
validation, admission, and request limiting, then returns an empty HTTP 202.
Its payload is ignored and it never cancels active work, including when the
supplied ID names an active request. Stream-level disconnect, deadline,
shutdown, and backpressure signals drive cooperative cancelation for this
transport instead. Other notifications never receive a JSON-RPC response body.

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
| Invalid, expired, or wrong-bound request state | 400 | `-32602` |
| Request-state protector unavailable | 503 | `-32603` |
| Application/protector output contract failure | 500 | `-32603` |
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
`McpHandlerInterceptor` is not an observability substitute. The current
runtime emits the admitted-request lifecycle start/finish pair and the
corresponding `McpMetricsEvent.RequestStarted` and
`McpMetricsEvent.RequestFinished` events for framework and application
operations. Callback failures are logged and contained, and user callbacks do
not run under MCP runtime or dispatcher locks.

Accepted progress emissions and cooperative cancelation signals additionally
produce `McpMetricsEvent.ProgressEmitted` and
`McpMetricsEvent.CancelationSignaled`, labeled only with the bounded endpoint
path and JSON-RPC method. Resource subscriptions additionally produce the
request-stream open/close, subscription open/close, and keep-alive semantic
events through the same collector. The frozen shared-host descriptors also
refer to provisional metric-snapshot, request-outcome, and stream-termination
types. Server diagnostics, status, and shutdown-outcome types are Phase 6-
owned. Comprehensive event storage, remaining connection instrumentation,
simulator integration, and final metric rendering remain Phase 6 work.

`McpServer.stop()` is bounded by the configured shutdown timeout. If an
application-supplied MCP request-processing execution remains afterward,
`getStatus()` reports `STOPPED_WITH_RESIDUAL_HANDLERS` and
`LifecycleObserver.didStopMcpServer(...)` receives `RESIDUAL_HANDLERS` exactly
once. These are compatibility names: they cover registered handlers and
request-pipeline callbacks such as admission, rate limiting, and request-state
protection. Repeated stop and the eventual late exit emit no second outcome.
Restart fails with `Cannot start MCP server while residual handler executions
remain` until the execution actually exits, and then succeeds. The outcome
does not claim that an executor or non-cooperative Java code was forcibly
terminated; process termination is the only hard stop for such code.

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
evidence. The earlier frozen Phase 4 candidate passed its then-active reviewed
profile. The checked-in final-schema corpus now contains 39 production-derived
messages, including five-message progress and subscription exchanges; that
local wire evidence is separate from the official suite. A controlled
observation-only run of the current packaged fixture exercised all 39
applicable pinned scenarios and recorded 147 `SUCCESS`, two exact reviewed
`server-stateless` `SKIPPED`, and one reviewed
`server-sse-streams-functional` `INFO` occurrence, with no warning, failure,
or harness error. Thirty-six automatic wire successes covered 103 messages.
All 16 Phase 5 profiles remain inactive and `null`, the harness remains at
phase 4, and this acquisition is not a frozen profile set, Phase 5 verify pass,
API freeze, or release-candidate result. Soak/resource-delta evidence and the
scoped API review precede atomic activation and a fresh 39-scenario verify run.
Do not treat this snapshot guide as a release-conformance statement.
