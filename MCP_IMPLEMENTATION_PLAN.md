# MCP Implementation Plan for Soklet

Status: draft design

Last updated: 2026-03-13

Spec target: MCP Streamable HTTP transport, version `2025-11-25`

Primary references:

- https://modelcontextprotocol.io/specification/2025-11-25/basic
- https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
- https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
- https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- https://modelcontextprotocol.io/specification/2025-11-25/server/resources
- https://modelcontextprotocol.io/specification/2025-11-25/server/prompts

## Summary

Add an optional `McpServer` that runs on its own port and serves one or more MCP endpoint paths. The public API is the hybrid model: `@McpServerEndpoint` declares class-level metadata, required `McpEndpoint` interface provides the behavioral contract, and `@McpTool` / `@McpPrompt` / `@McpResource` / `@McpListResources` annotate handler methods. `SokletProcessor` is extended to validate MCP annotations at compile time and write a discovery table, enabling `McpHandlerResolver.fromClasspathIntrospection()` as the primary production API.

This should be:

- additive — no changes to the existing HTTP or SSE transport
- compile-time validated — MCP declaration errors are caught by the annotation processor, not at startup
- framework-owned endpoint — the raw `POST` / `GET` / `DELETE` MCP HTTP handling is internal
- dependency-free — includes a small internal JSON tree; no third-party JSON library required
- not opinionated about application JSON serialization — `McpResponseMarshaler` is pluggable

## Architectural decisions

### 1. Add `McpServer` alongside existing servers

Keep `Server` and `ServerSentEventServer` unchanged. Add an optional `McpServer`.

Do not:

- replace the existing HTTP/SSE transport model
- force same-port HTTP + SSE + MCP
- proxy one internal server type into another

### 2. Framework owns the MCP HTTP endpoint

User code does not write a raw `@POST("/mcp")` handler. The framework owns the endpoint because one MCP path is a protocol multiplexer handling `initialize`, `tools/call`, `resources/read`, `prompts/get`, session lifecycle, SSE streams, and more.

### 3. Hybrid annotation model

`@McpServerEndpoint` is the right home for static declarative metadata (path, name, version, instructions) because those values have no behavior and never change. `McpEndpoint` is the right home for `initialize()` because it is real code with types, overridable by default, and directly unit-testable.

Every endpoint class must implement `McpEndpoint`. This is a hard requirement, not optional. Requiring it from day one means future methods can be added to the interface as deliberate overrides rather than silent default inheritances.

```java
@McpServerEndpoint(
    path = "/tenants/{tenantId}/mcp",
    name = "my-server",
    version = "1.0.0"
)
public final class MyMcpEndpoint implements McpEndpoint {
    @Override
    public McpSessionContext initialize(McpInitializationContext context,
                                        McpSessionContext session) {
        UUID tenantId = context.getEndpointPathParameter("tenantId", UUID.class).orElseThrow();
        return session.with("tenantId", tenantId);
    }

    @McpTool(name = "search", description = "Searches tenant content.")
    public McpToolResult search(
            @McpEndpointPathParameter("tenantId") UUID tenantId,
            @McpArgument("query") String query) { ... }
}
```

`McpEndpoint.initialize()` has a default no-op implementation. Endpoint classes that need no custom initialization implement `McpEndpoint` and simply do not override `initialize()`.

### 4. Compile-time annotation processing via `SokletProcessor`

Extend the existing `SokletProcessor` rather than adding a new processor. The infrastructure — `collect()` loops, `mergeAndWriteIndex()`, sidecar caching, incremental handling — is already there. MCP is more annotation types going through the same pipeline.

The processor writes a separate lookup table at `META-INF/soklet/mcp-endpoint-lookup-table`. Each row identifies one `@McpServerEndpoint` class. At runtime, `McpHandlerResolver.fromClasspathIntrospection()` reads this table and resolves classes exactly as `fromClasses(...)` would, without the caller needing to enumerate them.

The table format (one row per endpoint class):

```
b64(className)|b64(path)|b64(name)|b64(version)|b64(instructions)
```

`instructions` may be empty. All fields are base-64 encoded, consistent with `resource-method-lookup-table`.

### 5. `McpHandlerResolver.fromClasspathIntrospection()` as primary production API

This mirrors `ResourceMethodResolver.fromClasspathIntrospection()`. Users do not need to pass `Set.of(MyMcpEndpoint.class)` in production config; the processor-generated table provides discovery automatically.

`fromClasses(Set<Class<?>>)` is still available and is the right choice for tests and for applications that do not run the annotation processor.

### 6. Argument schema from explicit type whitelist

The processor and runtime both use a documented whitelist to map `@McpArgument` parameter types to JSON Schema. There is no reflection-based schema synthesis. Types outside the whitelist cause a compile error.

| Java type              | JSON Schema                                    |
|------------------------|------------------------------------------------|
| `String`               | `{ "type": "string" }`                        |
| `Integer` / `int`      | `{ "type": "integer" }`                       |
| `Long` / `long`        | `{ "type": "integer" }`                       |
| `Double` / `double`    | `{ "type": "number" }`                        |
| `Float` / `float`      | `{ "type": "number" }`                        |
| `BigDecimal`           | `{ "type": "number" }`                        |
| `Boolean` / `boolean`  | `{ "type": "boolean" }`                       |
| `UUID`                 | `{ "type": "string", "format": "uuid" }`      |
| `Enum` subtype         | `{ "type": "string", "enum": [...values] }`   |

`optional = true` on `@McpArgument` omits the parameter from the `required` array.

Tools with arguments outside this list must use the programmatic `McpToolHandler` escape hatch (see §Programmatic escape hatch).

### 7. Compile-time validations

The extended `SokletProcessor` validates:

- `@McpServerEndpoint` is on a class (not a method or field)
- The class annotated with `@McpServerEndpoint` implements `McpEndpoint`
- `@McpServerEndpoint` `path`, `name`, `version` are non-blank
- Path template braces in `@McpServerEndpoint(path=...)` are balanced and non-empty
- `@McpEndpointPathParameter` names in handler methods exist as placeholders in the endpoint path
- `@McpTool`, `@McpPrompt` have non-blank `name` and `description`
- `@McpResource` has non-blank `uri`, `name`, `mimeType`; `description` is optional
- URI template braces in `@McpResource(uri=...)` are balanced
- `@McpUriParameter` names in resource methods match placeholders in `@McpResource(uri=...)`
- `@McpTool` methods return `McpToolResult`
- `@McpPrompt` methods return `McpPromptResult`
- `@McpResource` methods return `McpResourceContents`
- `@McpListResources` methods return `List<McpListedResource>` (or raw `List`)
- `@McpListResources` methods have no `@McpArgument` parameters
- `@McpArgument` parameter types are within the whitelist
- No duplicate tool, prompt, or resource names within the same endpoint class
- Handler methods are public and non-static
- `McpToolCallContext` only appears as a parameter on `@McpTool` methods
- `McpListResourcesContext` only appears as a parameter on `@McpListResources` methods
- Warning: overlapping `@McpServerEndpoint` paths across classes (startup error if not resolvable)

These map directly onto the existing `error(element, ...)` pattern in `SokletProcessor`.

### 8. `@McpListResources` takes no user arguments

The framework generates `tools/list` and `prompts/list` responses automatically from annotation metadata — no user code required. Only `resources/list` requires a user-provided `@McpListResources` method because resource lists can be dynamic (e.g., populated from a database query).

`resources/list` in the MCP spec accepts only an optional pagination cursor, not arbitrary caller arguments. `@McpListResources` methods may only receive `@McpEndpointPathParameter` bindings and injectable framework types. `@McpArgument` is a compile error on these methods.

### 9. Programmatic escape hatch

For tools with argument types outside the whitelist, or for dynamically-registered handlers, provide a `McpToolHandler` / `McpPromptHandler` / `McpResourceHandler` programmatic interface. These can be registered alongside annotation-discovered handlers:

```java
McpHandlerResolver.fromClasspathIntrospection()
    .withTool(new ComplexSchemaTool(service), MyExampleMcpEndpoint.class)
```

The endpoint class argument scopes the programmatic handler to that endpoint. With multi-endpoint routing, this ensures a tool registered for the tenant endpoint is not visible on the admin endpoint's sessions.

The programmatic interfaces use an explicit `Json.object()` fluent builder for schemas:

```java
Json.object()
    .required("locationId", McpType.UUID)
    .optional("threshold", McpType.INTEGER)
    .build()
```

This escape hatch exists for edge cases. The primary path is annotations.

### 10. `InstanceProvider` and `ValueConverterRegistry` from `SokletConfig`

`McpServer` does not have its own `instanceProvider(...)` or `valueConverterRegistry(...)` builder methods. These are configured once on `SokletConfig` and shared across all server types (HTTP, SSE, MCP). This mirrors how the HTTP pipeline works: `Soklet` reads `InstanceProvider` and `ValueConverterRegistry` from `SokletConfig` and passes them to whoever needs them.

At runtime, endpoint classes are instantiated per-request via `InstanceProvider.provide(endpointClass)`, exactly as HTTP resource classes are (`Soklet.java:1064`). The `InstanceProvider` decides whether to cache or create fresh instances. Endpoint classes with constructor dependencies (like `MyExampleMcpEndpoint`'s seven services) require a DI-backed `InstanceProvider` (Guice, Dagger, etc.).

`McpParameterBinder` reuses `ValueConverterRegistry` for `@McpEndpointPathParameter` and `@McpUriParameter` string-to-type conversion, consistent with how `DefaultResourceMethodParameterProvider` converts `@PathParameter` and `@QueryParameter` values for HTTP.

### 11. Multi-endpoint routing

One `McpServer` = one port, multiple `@McpServerEndpoint` classes with distinct paths. This is the same model as HTTP: one `Server` port routes to many `@GET`/`@POST` resource classes based on path.

Path-based routing uses the same matching algorithm as HTTP path matching. Overlapping or ambiguous endpoint paths across `@McpServerEndpoint` classes are a startup error (and a compile-time warning where detectable).

Sessions are scoped per endpoint. A session established on `/tenants/{tenantId}/mcp` only sees tools, prompts, and resources from that endpoint class. `tools/list`, `prompts/list`, and `resources/list` are scoped to the endpoint the session was established on.

This means a single `McpServer` port can serve both `/tenants/{tenantId}/mcp` and `/admin/mcp` with completely separate capabilities, session stores, and initialization logic.

Tool, prompt, and resource names only need to be unique within a single endpoint class, not across the entire `McpServer`.

### 12. `McpRequestInterceptor` on `McpServer.Builder`

MCP handler methods need the same cross-cutting wrapper that HTTP resource methods get via `RequestInterceptor` — primarily transaction management (open transaction, invoke handler, commit on success, rollback on failure).

`McpRequestInterceptor` is a separate interface configured on `McpServer.Builder`, consistent with how HTTP's `RequestInterceptor` is configured on `SokletConfig`. It is server-wide, not per-endpoint. If per-endpoint differentiation is needed, the interceptor can inspect `McpRequestContext` to determine which endpoint is handling the request.

```java
McpServer.withPort(8082)
    .requestInterceptor(myMcpRequestInterceptor)
    .handlerResolver(McpHandlerResolver.fromClasspathIntrospection())
    .responseMarshaler(myMcpResponseMarshaler)
    .build()
```

```java
public interface McpRequestInterceptor {
    default Object interceptRequest(McpRequestContext context,
                                     McpHandlerInvocation invocation) throws Exception {
        return invocation.invoke();
    }
}
```

### 13. Extend `LifecycleObserver` and `MetricsCollector` with MCP events

Rather than separate MCP-specific observer and collector interfaces, extend the existing `LifecycleObserver` and `MetricsCollector` with MCP-specific default methods. These are configured once on `SokletConfig` and shared across HTTP, SSE, and MCP servers, consistent with the pattern from §10.

All new methods have no-op defaults, so existing implementations are unaffected.

MCP lifecycle events for `LifecycleObserver`:

- MCP server starting / started / stopping / stopped
- MCP session created / destroyed
- MCP request received / completed
- MCP SSE stream opened / closed

MCP metrics events for `MetricsCollector`:

- MCP request count, duration, error rate (by method: `tools/call`, `resources/read`, etc.)
- Active session count
- Active SSE stream count

## Public API

### Soklet configuration (production)

```java
SokletConfig config = SokletConfig.withServer(Server.withPort(8080).build())
    .serverSentEventServer(ServerSentEventServer.withPort(8081).build())
    .instanceProvider(myInstanceProvider)
    .mcpServer(McpServer.withPort(8082)
        .handlerResolver(McpHandlerResolver.fromClasspathIntrospection())
        .responseMarshaler(myMcpResponseMarshaler)
        .build())
    .build();
```

### Soklet configuration (tests / no processor)

```java
McpServer.withPort(8082)
    .handlerResolver(McpHandlerResolver.fromClasses(Set.of(MyMcpEndpoint.class)))
    .responseMarshaler(myMcpResponseMarshaler)
    .build()
```

### `McpEndpoint` interface

```java
public interface McpEndpoint {
    default McpSessionContext initialize(McpInitializationContext context,
                                         McpSessionContext session) {
        return session;
    }

    default McpToolResult handleToolError(Throwable throwable,
                                           McpToolCallContext context) {
        return McpToolResult.error(throwable.getMessage());
    }

    default McpJsonRpcError handleError(Throwable throwable,
                                         McpRequestContext context) {
        return McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error");
    }
}
```

Required on every `@McpServerEndpoint` class. The defaults are sensible no-ops. The interface is required rather than optional so that future methods can be added as deliberate overrides, not silent default inheritances.

- `initialize()` — override for custom session initialization (e.g., extracting tenant ID from the endpoint path)
- `handleToolError()` — override to customize how exceptions thrown by `@McpTool` methods become tool error results (`isError: true`). The default exposes the exception message.
- `handleError()` — override to customize how exceptions thrown by `@McpPrompt` and `@McpResource` methods become JSON-RPC errors. The default returns `-32603 Internal error`, hiding exception details for safety. Override to map domain exceptions to specific codes and messages (e.g., `NotFoundException → -32002, "Recipe not found"`).

`McpJsonRpcError` is a small immutable type with `code` (int) and `message` (String).

### Annotation reference

**Class-level:**

- `@McpServerEndpoint(path, name, version, instructions)` — declares the endpoint; `instructions` is optional

**Method-level:**

- `@McpTool(name, description)` — required attributes; method must return `McpToolResult`
- `@McpPrompt(name, description)` — required attributes; method must return `McpPromptResult`
- `@McpResource(uri, name, mimeType, description)` — `uri`, `name`, `mimeType` required; `description` optional; method must return `McpResourceContents`
- `@McpListResources` — method must return `List<McpListedResource>`; no `@McpArgument` allowed

**Parameter-level:**

- `@McpEndpointPathParameter("param")` — binds from the endpoint HTTP path
- `@McpArgument("param")` — binds from MCP JSON tool or prompt arguments; supports `optional = true`
- `@McpUriParameter("param")` — binds from the MCP resource URI template

**Injectable by type (no annotation needed):**

- `McpSessionContext` — session data bag (tenant ID, user state, etc.)
- `McpRequestContext` — current JSON-RPC request metadata (request ID, method name)
- `McpToolCallContext` — tool-specific request data (request ID, progress token); only available in `@McpTool` methods
- `McpInitializationContext` — initialization-time data (protocol version, endpoint path parameters, client info); only available in `initialize()`
- `McpClientCapabilities` — negotiated client capabilities
- `McpListResourcesContext` — pagination cursor and request metadata; only available in `@McpListResources` methods

These are flat, independently injectable types — no inheritance hierarchy between them. Context types like `McpToolCallContext` and `McpListResourcesContext` are only available in the method types where they make sense (enforced at compile time by the processor). General-purpose types like `McpSessionContext`, `McpRequestContext`, and `McpClientCapabilities` can be injected in any handler method.

### Parameter injection priority

1. Known injectable framework type → inject by type, no annotation needed
2. `@McpEndpointPathParameter` → bind from HTTP endpoint path
3. `@McpArgument` → bind from MCP JSON arguments
4. `@McpUriParameter` → bind from resource URI template
5. Otherwise → compile error (caught by processor) and startup error

### Result types

```java
// Tool result
McpToolResult.builder()
    .structuredContent(payload)          // marshaled by McpResponseMarshaler
    .content(McpTextContent.text("..."))
    .build()

// Tool error result (isError = true)
McpToolResult.error("Something went wrong.")

// Prompt result
McpPromptResult.withMessages(
    McpPromptMessage.system("..."),
    McpPromptMessage.user("..."),
    McpPromptMessage.assistant("...")
)

// Resource contents
McpResourceContents.text(uri, text, mimeType)
McpResourceContents.blob(uri, base64Data, mimeType)

// Resource list entry
McpListedResource.of(uri, name, mimeType)

// JSON-RPC error (for handleError() hook)
McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error")
```

### `McpServer` builder surface

- `port(Integer)` — required
- `host(String)`
- `handlerResolver(McpHandlerResolver)` — required
- `requestInterceptor(McpRequestInterceptor)`
- `responseMarshaler(McpResponseMarshaler)`
- `originPolicy(McpOriginPolicy)` — defaults to `McpOriginPolicy.nonBrowserClientsOnlyInstance()`
- `sessionStore(McpSessionStore)` — defaults to `McpSessionStore.inMemoryInstance()`
- `requestHandlerConcurrency(Integer)`
- `requestHandlerQueueCapacity(Integer)`
- `shutdownTimeout(Duration)`
- `writeTimeout(Duration)`
- `heartbeatInterval(Duration)`
- `idGenerator(IdGenerator<?>)`

### `McpHandlerResolver`

- `McpHandlerResolver.fromClasspathIntrospection()` — reads processor-generated table; JVM-wide singleton
- `McpHandlerResolver.fromClasses(Set<Class<?>>)` — runtime reflection; for tests and processor-free builds
- `.withTool(McpToolHandler, Class<? extends McpEndpoint>)` — programmatic escape hatch scoped to an endpoint class; composable on either factory result
- `.withPrompt(McpPromptHandler, Class<? extends McpEndpoint>)`
- `.withResource(McpResourceHandler, Class<? extends McpEndpoint>)`

### `McpResponseMarshaler`

Separate from HTTP `ResponseMarshaler`. Responsible for marshaling application objects into MCP tool `structuredContent`. Applications plug in their own Gson/Jackson implementation. Default implementation passes through explicit MCP result types unchanged and fails fast on arbitrary objects.

### `McpOriginPolicy`

```java
public interface McpOriginPolicy {
    boolean isAllowed(McpOriginCheckContext context);

    static McpOriginPolicy rejectAllInstance() { ... }

    static McpOriginPolicy nonBrowserClientsOnlyInstance() { ... }

    static McpOriginPolicy acceptAllInstance() { ... }

    static McpOriginPolicy fromWhitelistedOrigins(Set<String> whitelistedOrigins) { ... }

    static McpOriginPolicy fromOriginAuthorizer(
            Predicate<McpOriginCheckContext> originAuthorizer) { ... }
}
```

```java
public record McpOriginCheckContext(
    Request request,
    Class<? extends McpEndpoint> endpointClass,
    HttpMethod httpMethod,
    @Nullable String origin,
    @Nullable String sessionId
) {}
```

- Invoked before MCP protocol dispatch on `POST`, `GET`, and `DELETE`.
- `origin == null` means the client did not send an `Origin` header. The default `nonBrowserClientsOnlyInstance()` allows this case and rejects any request that does send `Origin`.
- `Origin: null` is treated as the literal string `"null"` and is rejected unless explicitly permitted.
- Rejection becomes `403 Forbidden`; `McpOriginPolicy` does not write CORS headers or attempt preflight handling.
- `fromWhitelistedOrigins(...)` allows requests with no `Origin` and also allows requests whose `Origin` matches the provided normalized whitelist.
- The policy is endpoint-aware because `McpOriginCheckContext` includes the resolved endpoint class. A single `McpServer` can therefore allow browser access for `/admin/mcp` and reject it for `/tenants/{tenantId}/mcp`, or vice versa.

### `McpSessionStore`

```java
public interface McpSessionStore {
    void create(McpStoredSession session);

    Optional<McpStoredSession> findBySessionId(String sessionId);

    boolean replace(McpStoredSession expected,
                    McpStoredSession updated);

    void deleteBySessionId(String sessionId);

    static McpSessionStore inMemoryInstance() { ... }
}
```

```java
public record McpStoredSession(
    String sessionId,
    Class<? extends McpEndpoint> endpointClass,
    Instant createdAt,
    Instant lastActivityAt,
    boolean initialized,
    @Nullable String protocolVersion,
    @Nullable McpClientCapabilities clientCapabilities,
    McpSessionContext sessionContext,
    @Nullable Instant terminatedAt,
    long version
) {}
```

- `McpSessionStore` is a threadsafe persistence contract over immutable `McpStoredSession` snapshots. The store is responsible for durability and atomic replacement; `McpSessionManager` is responsible for protocol state transitions and SSE cleanup.
- `create(...)` inserts a new session record and fails fast if the session ID already exists.
- `replace(expected, updated)` is compare-and-set. It succeeds only if `expected` is still the current stored value, typically by matching `version`. This is the concurrency boundary for `POST`, `GET`, and `DELETE`.
- `deleteBySessionId(...)` physically removes a session record after protocol teardown. Normal session shutdown is modeled first as a successful `replace(...)` that sets `terminatedAt`, then a later delete once SSE streams are closed.
- `inMemoryInstance()` returns a new in-process store for a single JVM. It is the default implementation for v1 and is not suitable for cross-JVM sharing without a custom store.
- Endpoint isolation is part of the stored record: the session is bound to one endpoint class at creation time, and later requests must match that endpoint or be treated as invalid.

## Internal design

### Packages

Public:

- `com.soklet.mcp`

Internal:

- `com.soklet.internal.mcp.protocol`
- `com.soklet.internal.mcp.http`
- `com.soklet.internal.mcp.json`
- `com.soklet.internal.mcp.annotation`

### Core internal classes

Protocol/runtime:

- `DefaultMcpRuntime`
- `McpDispatcher`
- `McpSessionManager` / `McpSessionState`
- `McpProtocolValidator`
- `McpCapabilitiesNegotiator`
- `DefaultMcpResponseMarshaler`

Transport:

- `DefaultMcpServer`
- `McpEndpointHandler`
- `McpSseConnection` / `McpSseBroadcaster`

Annotation adapter:

- `AnnotationMcpHandlerResolver` — implements `McpHandlerResolver`; used by both `fromClasspathIntrospection()` and `fromClasses()`
- `AnnotatedMcpToolHandler` / `AnnotatedMcpPromptHandler` / `AnnotatedMcpResourceHandler`
- `McpParameterBinder`

### JSON layer

Small internal JSON tree and codec. No third-party dependency.

Types: `JsonValue`, `JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBoolean`, `JsonNull`, `JsonParser`, `JsonWriter`

Rules: no reflection-based mapping; no general-purpose serializer; explicit mapping between JSON trees and MCP DTOs.

`Json.object()` is a public fluent builder for schemas, used by the programmatic escape hatch.

## MCP endpoint HTTP behavior

Assume `path("/mcp")`.

### `POST /mcp`

- Validate `Content-Type: application/json`, `Accept`, `Origin`
- Parse one JSON-RPC message
- Enforce lifecycle and capability rules
- Dispatch to handler

Response policy:

- JSON-RPC request → `200 OK` with `application/json`
- Notification or client response → `202 Accepted`
- Invalid request → protocol-appropriate JSON-RPC error payload

### `GET /mcp`

- Validate `Accept: text/event-stream`, `Origin`, session
- Open SSE stream for outbound server-to-client messages (notifications, progress)

### `DELETE /mcp`

- Validate `Origin` and session ownership
- Terminate session and close active SSE streams
- Return `204 No Content`

## Session model

Sessions are stateful in v1. Each session is scoped to a specific endpoint — a session established on `/tenants/{tenantId}/mcp` is entirely separate from one on `/admin/mcp`. Each session holds:

- session ID (from `IdGenerator`)
- endpoint class reference (which `@McpServerEndpoint` this session belongs to)
- creation and last-activity timestamps
- initialization status
- negotiated protocol version and capabilities
- typed key/value bag for application data (e.g., `tenantId`)
- termination status

`McpSessionContext` is immutable. `initialize()` returns an updated copy via `.with(key, value)`.

`McpSessionStore` is a compare-and-set store over immutable `McpStoredSession` snapshots. `DefaultMcpSessionStore` is the in-process implementation returned by `McpSessionStore.inMemoryInstance()`.

Typical runtime flow:

- `initialize` creates an uninitialized session record, invokes `McpEndpoint.initialize(...)`, then persists the initialized `McpSessionContext` and negotiated capabilities via `replace(...)`
- subsequent `POST` and `GET` requests load the record, verify endpoint match and non-terminated state, then update `lastActivityAt` via `replace(...)`
- `DELETE` marks the session terminated via `replace(...)`, closes active SSE streams, and finally removes the record with `deleteBySessionId(...)`

## SSE model

`McpServer` manages its own SSE streams (session-bound, not path-bound). Internal SSE helpers (framing, heartbeats, write queues, backpressure) are extracted and shared rather than copied from `DefaultServerSentEventServer`.

## Security

Minimum:

- Validate `Origin`; reject invalid origins with `403`
- Isolate session access by identity where applicable
- Reject unknown/dead sessions with `404`
- Reject unsupported protocol versions with `400`

`McpOriginPolicy` is intentionally simpler than HTTP CORS. It is a request admission policy, not a response-header policy. The default behavior is "allow non-browser clients; reject browser-style `Origin` headers unless explicitly whitelisted."

Authorization support is designed for but deferred.

## Implementation phases

### Phase 1: Public API skeleton

- Add `McpServer`, `McpHandlerResolver`, `McpEndpoint` interface, `@McpServerEndpoint`
- Add `McpTool`, `McpPrompt`, `McpResource`, `McpListResources`, parameter annotations
- Add result types (`McpToolResult`, `McpPromptResult`, `McpResourceContents`, `McpListedResource`)
- Add context types (`McpSessionContext`, `McpRequestContext`, `McpToolCallContext`, `McpInitializationContext`, `McpListResourcesContext`)
- Add `McpJsonRpcError`
- Add `McpRequestInterceptor`, `McpHandlerInvocation`
- Add MCP default methods to `LifecycleObserver` and `MetricsCollector`
- Add `SokletConfig.Builder#mcpServer(...)`

### Phase 2: Annotation processor extension

- Extend `SokletProcessor` to process MCP annotations
- Implement all compile-time validations listed in §Compile-time validations
- Write `META-INF/soklet/mcp-endpoint-lookup-table`
- Implement `McpHandlerResolver.fromClasspathIntrospection()`
- Implement `McpHandlerResolver.fromClasses(Set<Class<?>>)` (runtime reflection)

### Phase 3: Protocol and JSON runtime

- Internal JSON tree and parser
- `Json.object()` fluent schema builder and `McpType` enum
- JSON-RPC DTOs
- MCP lifecycle handling
- Capability negotiation
- Session manager and store

### Phase 4: HTTP transport

- `DefaultMcpServer`
- `POST`, `GET`, `DELETE` endpoint handling
- SSE stream management
- `LifecycleObserver` and `MetricsCollector` hooks

### Phase 5: Handler dispatch

- `AnnotationMcpHandlerResolver` — bind discovered classes to handler contracts
- `McpParameterBinder` — resolve all parameter sources
- `tools/list`, `tools/call`, `prompts/list`, `prompts/get`, `resources/list`, `resources/read`
- Programmatic `McpToolHandler` / `McpPromptHandler` / `McpResourceHandler` interfaces
- `.withTool(...)` / `.withPrompt(...)` / `.withResource(...)` composition

### Phase 6: Documentation and examples

- README section
- Javadoc on all public types
- Update examples

## Testing plan

### Processor tests (compile-testing library)

- Valid endpoint compiles cleanly
- Missing `description` on `@McpTool` → compile error
- Unsupported `@McpArgument` type → compile error
- `@McpArgument` on `@McpListResources` → compile error
- Wrong return type on `@McpTool` → compile error
- URI template parameter mismatch on `@McpResource` → compile error
- Endpoint path parameter mismatch → compile error
- Duplicate tool name within a class → compile error
- `McpToolCallContext` on non-`@McpTool` method → compile error
- `McpListResourcesContext` on non-`@McpListResources` method → compile error
- Lookup table written correctly
- Multiple endpoint classes produce correct multi-row table
- Incremental compile updates table correctly

### Unit tests

- JSON parser and writer
- JSON-RPC validation
- MCP lifecycle enforcement
- `McpOriginPolicy.nonBrowserClientsOnlyInstance()` allows missing `Origin` and rejects explicit browser-style origins
- Session state transitions
- `McpSessionStore.replace(...)` enforces compare-and-set semantics under concurrent updates
- `McpParameterBinder` — all parameter sources
- Type whitelist schema generation
- Default `handleToolError` produces `isError: true` with exception message
- Custom `handleToolError` override is invoked
- Default `handleError` produces `-32603` with `"Internal error"`
- Custom `handleError` override maps domain exceptions to specific codes
- `McpRequestInterceptor` wraps handler invocation (verify invoke order)

### Integration tests (via `Simulator` or equivalent)

- `initialize` / `notifications/initialized`
- `tools/list` and `tools/call`
- `prompts/list` and `prompts/get`
- `resources/list` and `resources/read`
- Notification POST returning `202`
- GET SSE stream establishment
- DELETE session teardown
- Invalid origin → `403`
- Invalid session → `404`
- Endpoint path parameter extraction in handler methods
- Multiple `@McpServerEndpoint` classes on one `McpServer`, each with distinct tools
- Session isolation: tools from endpoint A not visible on endpoint B's session
- `InstanceProvider` called per-request for endpoint instantiation
- `ValueConverterRegistry` used for `@McpEndpointPathParameter` conversion
- Tool method exception → `isError: true` via `handleToolError`
- Prompt method exception → JSON-RPC `-32603` error
- Resource method exception → JSON-RPC `-32603` error
- `McpRequestInterceptor` invoked around handler dispatch
- `LifecycleObserver` MCP callbacks fire at correct lifecycle points
- `MetricsCollector` MCP callbacks track request count and duration
