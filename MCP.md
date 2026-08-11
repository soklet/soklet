# Model Context Protocol (MCP)

Soklet 3.6.0 targets the MCP `2026-07-28` server protocol. MCP support is part
of core Soklet and uses a dedicated `McpServer` listener; it is not mounted in
the ordinary `HttpServer` or `SseServer`. The API and implementation ship in
the zero-runtime-dependency `com.soklet:soklet` artifact; there is no separate
`soklet-mcp` component.

This guide describes the implemented, locally frozen Phase 4 and Phase 5
surfaces, including multi-round-trip request state, progress/cancelation,
resource subscriptions, deterministic termination, cross-instance state, and
residual shutdown, plus thirteen completed bounded Phase 6 verticals covering
shutdown, handler capacity, handler diagnostics, stream/subscription
diagnostics, protection/trace diagnostics, serialized semantic-event delivery,
the bounded pre-admission metric quartet, and connection/transport metric
delivery, followed by admitted-request trace-token capture and the first
default transport-boundary, server-start, request-boundary, and admitted-
request lifecycle aggregate families, in the current
`3.6.0-SNAPSHOT`.
It is development documentation, not a release or final conformance claim.
Compile-checked programmatic and annotation-driven applications live outside
this source repository in the project-root `mcp/examples/phase-4` workspace.

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
| Bounded observation | Exactly one clean/residual outcome per successfully started listener generation, plus server-wide active-handler, queued-request, queue-full-rejection, and immutable handler-capacity, live-stream, protection, and trace-configuration diagnostics |
| Policy | Host and Origin checks, application admission, optional request limiting, mandatory fallback tool limiting for tool-bearing servers, bounded execution, and shared Soklet observation hosts |
| Schema | Closed, Java-first Soklet MCP Tool Schema Profile 1; no public hand-authored schema registration |

The remaining contract-fixed MCP aggregate projections and downstream
OpenTelemetry migration, structured trace-log emission and raw trace-ID
opt-in, and MCP simulation are not implemented yet. Public
descriptors already reserved for that remaining Phase 6 work are behaviorally
neutral and do not cause Soklet to advertise those capabilities.

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
owned. The bounded shutdown vertical publishes one
`McpMetricsEvent.ServerStopped` for every successfully started listener
generation that later stops successfully. Managed ordinary stops, startup
rollback, and unexpected-listener-termination normalization produce lifecycle
and metric outcomes in parity. A failed start produces neither; failed
asynchronous subscription-registration cleanup keeps the generation pending
until a successful retry; and repeated stop or eventual residual-handler exit
does not duplicate the outcome.

The bounded handler-capacity vertical records server-wide
`HandlerExecutionStarted`, `HandlerExecutionFinished`, `HandlerQueued`,
`HandlerDequeued`, and `HandlerCapacityRejected` events. Only a full admitted
handler queue is a capacity rejection; queued deadline, disconnect,
cancelation, and shutdown removal produce a matching dequeue instead. Compound
promotion order is globally `HandlerExecutionFinished`, `HandlerDequeued`, then
`HandlerExecutionStarted`.

`McpMetricsSnapshot` exposes the three nonnegative values through boxed
`Long` getters—`getActiveHandlerExecutions()`, `getHandlerQueueDepth()`, and
`getHandlerCapacityRejections()`—with matching boxed
`activeHandlerExecutions(Long)`, `handlerQueueDepth(Long)`, and
`handlerCapacityRejections(Long)` builder methods. The default collector
renders three exact label-free families:

- `soklet_mcp_handler_executions_active` (gauge);
- `soklet_mcp_handler_queue_depth` (gauge); and
- `soklet_mcp_handler_capacity_rejections_total` (counter).

The active-execution and queue-depth gauges describe live dispatcher state.
`reset()` therefore preserves their current values while clearing the
cumulative capacity-rejection counter; later finish/dequeue transitions return
the gauges to zero without underflow. On bounded shutdown, queued work is
dequeued, but a non-cooperative residual handler remains active until its
actual late exit, so the active gauge can correctly remain `1` after stop and
later become `0`. Previously returned snapshots remain immutable.

The tenth bounded Phase 6 production vertical resolved the full `AMB-003`
aggregate contract and implemented its first new coherent family: the MCP
transport boundary. At that checkpoint, `McpMetricsSnapshot` exposed five boxed
nonnegative `Long` values and two immutable fixed-enum maps. The additive
transport accessors are `getConnectionsAccepted()`,
`getConnectionsRejected()`, and `getTransportFailures()`; matching builder
methods are `connectionsAccepted(Long)`, `connectionsRejected(Long)`, and
`transportFailures(Map<MetricsCollector.TransportFailureReason, Long>)`.
The transport-failure map is defensive, enum-ordered, sparse in default
collector snapshots, and rejects null keys/values and negative counts.

`DefaultMetricsCollector` consumes `ConnectionAccepted`,
`ConnectionRejected`, and `TransportFailure` in addition to the previously
aggregated five handler variants and `ServerStopped`. At that checkpoint,
configured MCP collectors had seven rendered aggregate families. They render both
label-free connection counters even at zero; a direct transport
event activates the same paired rendering. The exact new families are
`soklet_mcp_connections_accepted_total` and
`soklet_mcp_connections_rejected_total`. MCP failures join the existing
`soklet_transport_failures_total` family with only the fixed labels
`server_type="MCP"` and `reason="<TransportFailureReason>"`, so mixed HTTP,
SSE, and MCP samples share one HELP/TYPE block rather than creating a parallel
MCP family. A filter that rejects every transport-failure sample emits no
orphaned family metadata. Prometheus and OpenMetrics rendering, all 18 fixed
reasons, direct and concurrent ingest, reset, and retained immutable snapshots
are covered by
`McpTransportMetricsAggregationTests#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
`#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
`#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
`#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

Reset clears both cumulative connection counters and the sparse MCP failure
map while preserving configured zero-family visibility; it cannot mutate a
previously returned snapshot. The two connection events are fieldless, and a
transport failure contributes only its fixed enum reason. No remote address,
request, throwable, header, trace ID, correlation token, key material,
tracestate, baggage, or application-controlled label enters these aggregate
or default-rendered values.

The eleventh bounded Phase 6 production vertical implements the contract-fixed
`ServerStarted` scalar. `McpMetricsSnapshot` adds boxed, nonnegative
`getServerStarts()` and matching `serverStarts(Long)`, bringing the provisional
surface to exactly eight getters and nine public builder methods including
`build()`: six boxed `Long` values and two immutable maps.
`DefaultMetricsCollector` counts the existing fieldless `ServerStarted` event,
whose lifecycle authority remains one event for each successfully started
listener generation. A failed staged start contributes none, an already-started
no-op contributes no duplicate, a managed rollback retains its successful start
before the matching stop, and a successful restart contributes a fresh start.

Configured collectors render the label-free counter
`soklet_mcp_server_starts_total` at zero. Either a direct `ServerStarted` or
`ServerStopped` event activates the same lifecycle family on an uninitialized
collector; a stop-only observation therefore renders a zero start counter plus
its shutdown sample. A rejecting filter suppresses the start sample and its
HELP/TYPE metadata. Reset clears the cumulative start count but preserves
configured or event-activated zero-family visibility, and it cannot mutate a
retained snapshot. Starts and shutdown outcomes are not a conservation or
complement pair at an arbitrary snapshot: a currently running generation has a
start but no stop yet. The fieldless source event and label-free aggregate
retain no request, remote address, endpoint, method, outcome, throwable,
header, trace ID, token, key material, tracestate, baggage, or application
label. Exact direct, configured, filter, OpenMetrics, reset, retained-snapshot,
and concurrent-ingest evidence is
`McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
`#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
and
`#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The twelfth bounded Phase 6 production vertical implements independent
`RequestAccepted` and `RequestRejected` request-boundary scalars.
`McpMetricsSnapshot` adds boxed, nonnegative `getRequestsAccepted()` and
`getRequestsRejected()` plus matching `requestsAccepted(Long)` and
`requestsRejected(Long)` builder methods. At that checkpoint, the provisional
surface had exactly ten getters and 11 public builder methods including
`build()`: eight boxed `Long` values and two immutable maps.

`RequestAccepted` is retained only after the bounded protocol processor
accepts `Executor.execute`; an execute rejection or throw identity-discards
the provisional accepted entry. `RequestRejected` is recorded exactly once for
a complete Handler request whose terminal wins before atomic observation-start
reservation. It can follow accepted on malformed or other terminal
pre-admission paths, or occur without a retained accepted event after execute
failure. The counters therefore are neither complements nor a conservation
equation. They exclude early transport/Microhttp failures, post-admission
outcomes, and handler-capacity rejection.

Configured MCP collectors render the paired label-free counters
`soklet_mcp_requests_accepted_total` with HELP text `Total MCP requests accepted
by the bounded protocol processor` and `soklet_mcp_requests_rejected_total`
with HELP text `Total MCP requests rejected before admitted semantic handling`,
including zeros. Either directly ingested event activates both families and
the unobserved peer remains zero. Per-sample filtering removes a rejected
family's sample and HELP/TYPE metadata, OpenMetrics terminates normally, and
reset clears both cumulative values while retaining configured or
event-activated paired-zero visibility. Previously returned snapshots remain
immutable, and post-quiescence concurrent direct ingest is lossless.

The source events are fieldless and the rendered counters have no labels. They
retain no request, remote address, endpoint, method, error code, outcome,
throwable, header, trace ID, token, key material, tracestate, baggage, or
application-controlled dimension. Exact aggregate tests are
`McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
`#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
and
`#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
The exact authority paths remain covered by
`McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
and
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.

The thirteenth bounded Phase 6 production vertical implements admitted-request
lifecycle aggregation. `McpMetricsSnapshot` adds boxed, nonnegative
`getActiveRequests()`, immutable
`Map<RequestOutcomeKey, Long> getRequests()`, and immutable request-duration
histograms from `getRequestDurations()`, with matching `activeRequests(Long)`,
`requests(Map)`, and `requestDurations(Map)` builder methods. The public nested,
thread-safe
`RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome)` record requires
non-null, nonempty routed strings and a non-null fixed `McpRequestOutcome`;
public construction does not validate registry membership. The provisional
snapshot now has 13 getters and 14 public builder methods including `build()`:
nine boxed `Long` values and four immutable maps. Count and duration maps are
independent sparse projections and carry no cross-map invariant.

The built-in authority increments `soklet_mcp_requests_active` exactly when an
admitted `RequestStarted` is delivered and decrements it for the exact terminal
`RequestFinished`. A finish contributes to `soklet_mcp_requests_total` and
`soklet_mcp_request_duration_nanos`, keyed only by bounded `endpoint`, `method`,
and lower-snake `outcome`. There are no standalone start or finish counters.
The duration histogram reuses the inclusive HTTP latency boundaries of 1, 2,
5, 10, 25, 50, 100, 200, 400, 800, 1,500, 3,000, 7,000, and 15,000
milliseconds plus overflow. Configured collectors and either lifecycle event
activate the live gauge; configured empty state renders gauge zero, while the
labeled counter and histogram remain sparse and emit no orphan HELP/TYPE
metadata when empty or fully filtered. Prometheus/OpenMetrics filters operate
per sample.

`reset()` preserves the live active-request gauge but clears completed counts
and duration histograms. A request that started before reset and finishes
afterward records its full original duration, not a reset-relative duration.
Previously returned snapshots and their maps remain immutable; balanced
post-quiescence concurrent ingest is lossless. This does not promise an atomic
cross-field snapshot during mutation, clamp or repair unmatched manually
ingested lifecycle events, or impose a relationship between independently
built public maps.

The runtime-produced key contains only a registered endpoint path, a recognized
method or `<unrecognized>`, and a fixed outcome. No request or remote identity,
raw unrecognized method, error detail, throwable, header, trace ID, token, key
material, tracestate, baggage, or application telemetry enters these built-in
aggregate or rendered dimensions. This is not a constraint on custom
collectors, generic HTTP metrics callbacks, application-created events/keys,
logs, or application telemetry. Exact focused evidence is
`McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
`#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
`#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
The producer authority and real-listener cardinality boundary remain covered by
`McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
`#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
`#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.

`McpServer.getDiagnostics()` returns server-wide handler-capacity, live-stream,
protection, and trace-configuration state without requiring a metrics
collector. `McpServerDiagnostics` now declares exactly 12 zero-argument
methods: lifecycle accessors `getStatus()` and `getBoundAddress()`, plus all ten
implemented diagnostic getters. The six numeric getters are the boxed,
`@NonNull Integer` methods `getRequestHandlerConcurrency()`,
`getRequestHandlerQueueCapacity()`, `getActiveHandlerExecutions()`,
`getQueuedRequests()`, `getActiveRequestStreams()`, and
`getActiveSubscriptions()`. The other four are `getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyRingFingerprint()`, and
`getTraceCorrelationConfigurationFingerprint()`; both fingerprint getters
return non-null `Optional` values with non-null payload types.

The configured numeric bounds are positive. Active and queued values are
current counts. `getActiveRequestStreams()` counts open request-scoped SSE
streams, while `getActiveSubscriptions()` counts the subset that are open
resource subscriptions. A subscription enters both counts once its
acknowledgment stream opens; neither count implies client receipt.

Lifecycle status, bound address, configured bounds, handler counts, and the
paired stream/subscription counts are captured by the runtime as one atomic
tuple across every endpoint. The four security fields are captured as a
separate atomic tuple by the server-owned security controls. Both tuples are
placed in one immutable public record, but they do not claim one shared global
linearization point. Configured values remain stable before first start and
across stop/restart; all current counts are
nonnegative, handler counts remain within their configured bounds, and
`0 <= activeSubscriptions <= activeRequestStreams`. A positive physical queue
implies all configured handler slots are occupied. Retaining a snapshot freezes
all of its values. An ordinary request SSE stream has pair `1/0`, an isolated
subscription has `1/1`, and opening both produces the server-wide pair `2/1`.

A completed clean stop reports active `0` and queued `0`. A completed residual
stop reports queued `0` but keeps a non-cooperative handler active until its
actual late exit, after which a fresh snapshot reports active `0`. During the
bounded transient between unexpected listener failure and completed cleanup,
a `STOPPED_WITH_RESIDUAL_HANDLERS` snapshot may still report the actual bounded
queue depth; cleanup then drains it without promoting work. A queue-full
rejection does not change either live handler diagnostic count. Disconnecting
the subscription in a combined `2/1` snapshot leaves `1/0`; disconnecting the
ordinary stream leaves `0/0`. Completed clean and residual-handler stops both
report stream pair `0/0`, even while a residual handler remains active until
late exit. During internal `FAILED` cleanup, the public residual status may
temporarily retain an open subscription pair `1/1`; completed cleanup reports
`STOPPED` with `0/0`.

The protection mode and custom-protector flag are fixed when the server is
built and remain stable across listener lifecycle transitions. The flag is
`true` exactly for `CUSTOM_PROTECTOR`; it reports selection of the custom
application-owned `McpRequestStateProtector` SPI, not whether an operation uses
`APPLICATION_PROTECTED` state. Application-protected opaque state needs no
framework protector and bypasses a configured custom protector.

The protection-ring fingerprint is present exactly for
`PRODUCTION_KEY_RING`; it is empty for unconfigured, custom-protector, and
development-ephemeral modes. The trace-configuration fingerprint is independent
of protection mode and is present exactly when trace correlation was enabled
at construction. Successful live protection-ring or trace-key rotation changes
only subsequently obtained diagnostics. Both values persist through listener
stop/restart, and retained snapshots never change.

Fingerprints are deterministic operational deployment-comparison metadata,
not authentication or token-derivation inputs. Diagnostics expose no raw key
material, key IDs, per-key fingerprint tags, custom-provider identity,
request-state cursors or epochs, or trace-correlation tokens. Operators must
still supply high-entropy keys: a fingerprint reveals configuration equality,
and rotation can create high-cardinality values, so fingerprints should not be
used as metric labels or emitted per request. The diagnostics vertical adds no
metric family, event type, wire field, label, or other observation dimension,
and collector reset cannot alter it.

The sixth bounded Phase 6 vertical established one context-aware, server-wide
deferred FIFO for the first 16 semantic event variants produced by the runtime:
`HandlerExecutionStarted`, `HandlerExecutionFinished`, `HandlerQueued`,
`HandlerDequeued`, `HandlerCapacityRejected`, `ServerStopped`, the nine
admitted `RequestStarted`, `RequestFinished`, `RequestStreamOpened`,
`RequestStreamClosed`, `SubscriptionOpened`, `SubscriptionClosed`,
`CancelationSignaled`, `ProgressEmitted`, and `KeepAliveEmitted` variants, and
exactly one `ServerStarted` for each successfully started listener generation.
A failed start leaves no staged `ServerStarted`, and an already-started no-op
does not duplicate it. Direct restart orders the old generation's
`ServerStopped` before the new generation's `ServerStarted`; managed startup
rollback orders that generation's `ServerStarted` before its `ServerStopped`.

The seventh vertical extended that FIFO to the 20 variants produced at that
checkpoint with `RequestAccepted`, `RequestRejected`, `ProtocolError`, and
`UnknownMirroredHeader`. `RequestAccepted` means successful submission to the
bounded protocol processor. If executor submission rejects, Soklet discards
the provisional accepted entry and emits only `RequestRejected` before the
fixed empty HTTP 503 response. A complete malformed request records
`RequestAccepted`, `ProtocolError(-32700)`, then `RequestRejected`. Strict
unknown-header and unresolved-method paths record `RequestAccepted`, one
`UnknownMirroredHeader` per occurrence, their fixed `ProtocolError`, then
`RequestRejected`. Application-owned rejection codes never become
`ProtocolError` dimensions.

Produced protocol-error metrics use exactly the fixed codes `-32700`,
`-32600`, `-32601`, `-32602`, `-32603`, `-32020`, `-32021`, `-32022`,
`-31999`, and `-31998`. Recording follows successful response encoding. A
streamed error is provisional until its terminal message is accepted; failed
terminal reservation discards it. Unknown-header events contain only the
registered endpoint path and a bounded recognized method or
`<unrecognized>`. They never contain the header name, value, or a raw
unrecognized method, and their per-occurrence count is independent of the
optional name-diagnostic quota.

Collector callbacks are serialized and drain after the relevant dispatcher,
exchange terminal/execution-boundary, progress-reporter, stream-transition,
request-control, runtime, MCP-server, and Soklet lifecycle locks or monitors
are released. Nonwaiting request-transition deferral preserves reentrant
collector liveness without moving callbacks under those locks. The four
pre-admission variants are request-free; only a fixed `ProtocolError` produced
after admitted request observation carries the exact `McpRequestContext` for
failure attribution. That context may be retained only for bounded pending
delivery and failure logging, is never rendered, and is not a metric
dimension. Collector failures are contained without stalling the FIFO. The
ordering guarantee is FIFO metric record/enqueue order; it is not a universal
cross-thread causal or per-request total-order guarantee for independently
racing producers.

The eighth bounded Phase 6 vertical adds `ConnectionAccepted`,
`ConnectionRejected`, and `TransportFailure` to the same FIFO, so the runtime
now produces and delivers all 23 declared `McpMetricsEvent` variants.
`ConnectionAccepted` is recorded after the operating system accepts the socket
and Soklet reserves capacity, but before connection-loop registration or any
request. A later setup failure may therefore follow it as
`TransportFailure(CONNECTION_SETUP_ERROR)`. `ConnectionRejected` is reserved
only for an accepted socket refused because the configured connection limit is
full; accept-loop and setup faults instead produce their typed transport
failure and never a capacity rejection.

`TransportFailure` is request-free and carries exactly one of the 18 bounded
`MetricsCollector.TransportFailureReason` values: `REQUEST_READ_TIMEOUT`,
`REQUEST_TOO_LARGE`, `MALFORMED_REQUEST`, `READ_ERROR`, `WRITE_ERROR`,
`RESPONSE_WRITE_IDLE_TIMEOUT`, `RESPONSE_READY_ERROR`,
`REQUEST_READ_TIMEOUT_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`,
`ACCEPT_LOOP_ERROR`, `CONNECTION_SETUP_ERROR`, `TASK_ERROR`,
`TIMEOUT_TASK_ERROR`, `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
`EVENT_LOOP_TERMINATED`, and `UNKNOWN`. The event and its failure log retain no
remote address, raw request, request context, throwable, payload, trace token,
or other unbounded value. Low-level transport authorities select the typed
reason directly; Soklet does not infer it from exception or log text.

Typed failure scopes stage a reason before a fallible asynchronous transport
transition, discard it on success, and retain it through synchronous close,
cancelation, and stream-terminal consequences on failure. A runtime-owned,
coalescing single-daemon-worker scheduler drains after connection-thread locks
are released, retries a rejected submission when a signal races it, and never
runs collector callbacks as a synchronous fallback on the connection thread.
Blocking lifecycle deferral safely adopts that pending delivery, so fatal
restart returns only after the old generation records
`EVENT_LOOP_TERMINATED`, `ServerStopped`, then the new `ServerStarted`.

A byte-free idle connection closes quietly, while a genuinely partial request
records `REQUEST_READ_TIMEOUT`. Malformed HTTP records `MALFORMED_REQUEST`;
a complete HTTP request containing malformed JSON instead follows the existing
`RequestAccepted`, `ProtocolError(-32700)`, `RequestRejected` path. The
request-SSE write-idle winner records exactly one `WRITE_TIMEOUT` before its
stream/request terminals; a losing or generic termination records no
`WRITE_TIMEOUT`, and channel-owned cancelation does not synthesize
`WRITE_ERROR`. The sole fatal
event-loop winner records `EVENT_LOOP_TERMINATED` before stop/wake publication
and retains that scope through runtime terminalization and sibling-loop
cleanup. These are record/enqueue-order guarantees at the owning authorities,
not a universal cross-thread causal ordering claim.

Separate from the first eight production observability and diagnostics
verticals,
a bounded Phase 6 MCP fuzz-registration and hardening checkpoint adds five new
Jazzer methods:
`McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
`McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
`McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
`McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
`McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
This fuzz checkpoint is unnumbered; at that point the completed production-
vertical count remained eight. Twenty-one checked-in synthetic text seeds cover these
targets, and the nightly matrix now declares 15 total one-method slots, five of
them new.

The envelope target uses production JSON limits and either classifies one of
the four envelope variants or observes only typed `McpWireDecodingException`;
it deliberately makes no unconditional encode-round-trip claim because
canonical output can expand. Mirrored-header decoding uses the production
default bound and permits only its uniform redacted `IllegalArgumentException`.
The Profile 1 target caps one input at 64 KiB, splits schema and optional
instance at a literal `---INSTANCE---` line, and requires typed compilation or
production-bounded evaluation outcomes. Cursor validation caps input at 64
KiB and cross-checks decoded UTF-8 and raw UTF-16 projections against the JDK
UTF-8 encoder in `REPORT` mode for a derived 1-to-256-byte limit. Request-state
plaintext uses a deterministic binding, clock, request ID, 4,096-byte bound,
15-minute lifetime, and three-round limit; rejection remains uniform and
redacted, while accepted input must re-encode byte-exactly. Its terminal-LF
copy is derived only for inputs of at most 4,097 bytes. The cursor helper is an
internal package-private validation seam shared by incoming and outgoing
cursors; it adds no public API.

An unnumbered internal trace-correlation derivation checkpoint first implemented the
frozen token construction. Trace correlation is disabled by default, and
disabled controls capture no token. Enabled controls
snapshot one complete active key ID and key-material pair under the shared
security lock, then perform HMAC-SHA-256 after releasing that lock over UTF-8
`soklet-mcp-trace-correlation-v1\0` followed by the decoded 16-byte trace ID.
The first 16 digest bytes are encoded as an unpadded, 22-character Base64URL
token. Invalid and all-zero trace IDs are rejected by `TraceContext` before
derivation; equal key/trace inputs agree across controls, changed key or trace
inputs differ, and concurrent rotation exposes only coherent old or new
`(keyId, token)` pairs. Copied key material and explicit derivation buffers are
zeroed, and the internal carrier retains only the nonsecret key ID and token
while redacting the token from diagnostic rendering.

The ninth bounded production vertical now invokes that derivation exactly once
for each admitted semantic request, before lifecycle and handler observation.
Only a valid MCP `_meta.traceparent` is eligible; disabled correlation,
invalid or all-zero MCP trace context, absent metadata, and a physical HTTP
trace header without valid MCP metadata all produce no carrier. The lifecycle
observer, interceptor, handler, and terminal callback share the same immutable
request context and hidden carrier. A request captured before rotation retains
its complete old `(keyId, token)` pair through terminal observation, while a
fresh request after rotation adopts the new pair. The raw-validated-trace-ID
option neither enables correlation nor changes token derivation. The final
package-private carrier retains only the nonsecret key ID and pseudonymous
token, not raw trace context or key material, and redacts the token from
rendering.

This request integration is the ninth vertical. At that point, the prior fuzz
and dormant derivation checkpoints remained unnumbered;
`SOK-TRACE-001`, `SOK-TRACE-002`, and `SOK-TRACE-003` were COMPLETE;
`SOK-TRACE-004` and `SOK-TRACE-005` were PLANNED; and `SOK-PRIV-001` was
PARTIAL. No public API or API-sketch source changed. There is no structured-log
carrier, field, emission point, cadence,
or new `LogEventType`; raw trace-ID logging remains unimplemented. The vertical
adds no metric, event, diagnostics/snapshot field, aggregate, label, or wire
dimension. Tokens remain pseudonymous high-cardinality operational metadata,
not anonymization, authentication, or authorization inputs. The carrier is not
cleared at finish, and no GC or application-reference lifetime is promised;
it naturally remains with an application-retained request context while core
security controls retain only the current key and expose no history API. This
is not comprehensive trace/baggage redaction, cardinality, privacy/security,
aggregate/`AMB-003`, simulator, release-readiness, or Phase 6 freeze evidence.

A third unnumbered Phase 6 checkpoint froze built-in MCP metric
dimensionality through
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
The sealed event hierarchy remains exactly 23 record variants, 11 fieldless;
its other components remain only endpoint path, bounded method, fixed outcome,
reason or protocol code, and nonnegative duration. Production supplies a
registered endpoint, a recognized method or `<unrecognized>`, the fixed ten
protocol codes, and fixed enums. Public event constructors still validate only
shape, nullability, nonempty routed strings, and nonnegative duration; they do
not enforce production registration, method vocabulary, or the protocol-code
allowlist for arbitrary application-created events. At that checkpoint,
`McpMetricsSnapshot` was exactly three boxed `Long` values plus the immutable
`Map<McpShutdownOutcome, Long>`. `DefaultMetricsCollector` aggregated only the
five handler variants and `ServerStopped`; a fresh collector ignored and
retained none of the other 17 variants.

The runtime gate sends 16 sequential admitted requests with distinct valid MCP
and HTTP trace IDs, tracestate, baggage, key/token canaries, correlation, and
raw-ID opt-in. Built-in MCP events, snapshot values, metric names and labels,
Prometheus, OpenMetrics, filter-observed samples, and reset rendering contain
none of those canaries. At that checkpoint, before reset, the exact MCP sample
set was the three label-free handler samples plus
`soklet_mcp_shutdowns_total{outcome="clean"}`; after reset, only the three
label-free handler samples remained. The production-vertical count remained nine,
and the fuzz-registration, dormant derivation, and metric-dimensionality
checkpoints were the three unnumbered checkpoints. `SOK-TRACE-001`,
`SOK-TRACE-002`, and `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` was
PLANNED; `SOK-TRACE-005` was PARTIAL for metric-dimension inventory and
default-collector evidence only; and `SOK-PRIV-001` was PARTIAL.
`SOK-METRIC-001` and `SOK-METRIC-004` remained PARTIAL; `AMB-003` remained
AMBIGUOUS.

That checkpoint changed no production source, public API, API sketch, owner or
signature inventory, aggregate family, label, event variant, or wire behavior.
It does not cover custom collectors; generic HTTP `MetricsCollector` callbacks
that receive a `Request`, request target, or `Throwable`; `LogEvent`,
application callbacks, handler telemetry, or arbitrary application-created
event vocabulary; structured-log fields/emission or raw-ID logging; future
aggregate families or `AMB-003`; comprehensive trace/baggage redaction; or
sustained cardinality, coverage-guided fuzz, corpus saturation, soak,
simulation, migration, release-candidate provenance, review, or Phase 6
freeze.

Transport aggregation is the tenth production vertical, server-start
aggregation is the eleventh, request-boundary aggregation is the twelfth, and
admitted-request lifecycle aggregation is the thirteenth; the three earlier
checkpoints remain unnumbered. The snapshot surface now has 13 getters with 13
matching builder properties (plus `build()`): nine boxed `Long` values and four
immutable maps. The default collector aggregates 14 of the 23 event variants
and ignores the remaining nine across 13 rendered aggregate families. The
16-request cardinality gate now observes 28 exact MCP-prefixed samples before
reset, including the active gauge plus one completed counter, 15 duration
buckets, count, and sum for the single complete request key; nine remain after
reset, when the sparse completed families and shutdown are absent.
Its transport-failure map remains empty and no trace canary enters the built-in
MCP or shared transport metric surfaces.

The next contract-fixed aggregate subset is request-stream lifecycle
aggregation: `RequestStreamOpened` and `RequestStreamClosed` feed a live stream
gauge plus an endpoint/method/reason duration histogram. Later rows use live
subscription gauges plus termination-keyed duration histograms;
endpoint/method cancelation and progress counters; a label-free keep-alive
scalar; a fixed-code protocol-error map; and an endpoint/method unknown-header
map that never contains header identity. There are no standalone
start/finish/open/close counters. Configured scalars render zero, maps and
histograms remain sparse, reset preserves the five live gauges and clears
cumulative/map/histogram state, and a duration crossing reset retains its
original start. Exact downstream OpenTelemetry names and attributes remain in
the authoritative Phase 6/V10 contract rather than being duplicated here.

`SOK-TRACE-005` remains PARTIAL for metric-only evidence, while
`SOK-PRIV-001`, `SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL.
`SOK-METRIC-002`, `SOK-METRIC-003`, and `SOK-SHUT-002` remain COMPLETE.
`AMB-003` is RESOLVED CONTRACT / IMPLEMENTATION PARTIAL: the
remaining contract-fixed core aggregate families and downstream snapshot-
compatible OpenTelemetry work are not implemented by this vertical.
It does not constrain custom collectors or application telemetry, promise an
atomic cross-field snapshot during active concurrent mutation, add structured-
log or raw-ID emission, complete trace/privacy/cardinality work, or provide
simulation, sustained, release-readiness, review, or Phase 6 freeze evidence.

The default collector separately exposes shutdown counts as an immutable,
enum-ordered `Map<McpShutdownOutcome, Long>`. It omits zero outcomes, returns
to an empty map after reset, and renders exactly
`soklet_mcp_shutdowns_total{outcome="clean"|"residual_handlers"}` in
Prometheus/OpenMetrics text. Default aggregation now covers `ServerStarted`,
`ServerStopped`, `RequestAccepted`, `RequestRejected`, `RequestStarted`,
`RequestFinished`, the five handler variants, and the transport trio. The
remaining contract-fixed families and
downstream OpenTelemetry work, structured-log
carrier/emission, raw-ID opt-in,
sustained cardinality, and broader privacy/redaction
work, simulator integration, scheduled/manual
coverage-guided and sustained fuzz gates, release-candidate work, and Phase 6
review/freeze remain open. The
seventh through ninth verticals added no public API, snapshot field, aggregate
family, label, event variant, or wire dimension. The tenth added three
provisional snapshot getters and three matching builder methods; the eleventh
adds one provisional getter/builder pair, the twelfth adds two, and the
thirteenth adds three plus `RequestOutcomeKey`. None adds an event variant or
wire dimension. Phase 6 remains provisional and unfrozen.

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
That acquisition was not itself a frozen profile set, Phase 5 verify pass, API
freeze, or release-candidate result. The bounded Phase 5 cross-feature
soak/resource-delta gate is separately green: complete four-test Maven smoke
runs pass on JDK 21 and JDK 26, and the four-test JDK 21 nightly run passes;
the strict verifier accepts exactly four scenarios and three suites for each
profile. Requests, streams, subscriptions, generations, and publisher
registrations balance, each MCP run ends `STOPPED`, and no active publisher
registration or client socket remains. Sustained/fleet/release-candidate
calibration remains later work. The later atomic closeout activates all 39 exact
profiles, preserves the 23 historical IDs, freezes the Phase 5 API, and advances
the harness to phase 5. A fresh 39-scenario development-candidate verify passes
all profiles, validates all 39 goldens, and records no bad outcome, standard-
error output, or non-clean fixture exit.

The focused admitted-request lifecycle aggregate/adjacent gate passes
72/0/0/0.
The prior focused five-target fuzz run remains 28/0/0/0 and was not rerun for
this checkpoint;
the prior deterministic full fuzz corpus replay on both JDKs remains
127/0/0/0 and was likewise not rerun. Exact-source full main suites on
Corretto 21.0.11 and 26.0.1 each report 1,481/0/0/4. Enforced static analysis
is green with existing advisory diagnostics; SpotBugs reports 0/0. Exact API-
freeze evidence reports 556 incompatibilities and 207 reviewed current-side
API owners, including the provisional `RequestOutcomeKey`; the unchanged
frozen inventories remain 1,049 Phase 4 records and 195 Phase 5 records with
the prior hashes. Candidate main,
source, and Javadoc packages plus standalone Javadoc are green using
offline-link resolution. All 167 API-sketch sources compile for Java 17 and
pass Javadoc doclint on JDK 26. All 104 files from pinned JSON Schema commit
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate. No scheduled or manual
coverage-guided nightly fuzz run occurred; deterministic seed replay is not
sustained, coverage, corpus-saturation, privacy, security, release-readiness,
or Phase 6 freeze proof. The remaining contract-fixed Phase 6 aggregate
families and downstream OpenTelemetry work, structured-log carrier/emission,
raw-ID opt-in,
broader privacy, sustained cardinality, and redaction work, simulator integration,
coverage-guided and sustained fuzz gates, broader
CI/provenance and release-candidate work, and Phase 6 API review/freeze remain
open. The next aggregate implementation is the contract-fixed request-stream
lifecycle family for `RequestStreamOpened` and `RequestStreamClosed`. Phase 6 remains
provisional and unfrozen.

Do not treat this snapshot guide as a release-conformance statement.
