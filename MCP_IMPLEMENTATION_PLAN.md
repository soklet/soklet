# MCP Implementation Plan for Soklet

Status: draft design

Last updated: 2026-03-14

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
- `@McpListResources` methods return `McpListResourcesResult`
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

To support pagination cleanly, `@McpListResources` returns `McpListResourcesResult` rather than a bare `List<McpListedResource>`.

`tools/list` and `prompts/list` remain framework-generated in v1 and are not paginated. They always return the full, sorted list and omit `nextCursor`.

### 9. Programmatic escape hatch

For tools with argument types outside the whitelist, or for dynamically-registered handlers, provide `McpToolHandler` / `McpPromptHandler` / `McpResourceHandler` / `McpResourceListHandler` programmatic interfaces. These can be registered alongside annotation-discovered handlers:

```java
McpHandlerResolver.fromClasspathIntrospection()
    .withTool(new ComplexSchemaTool(service), MyExampleMcpEndpoint.class)
```

The endpoint class argument scopes the programmatic handler to that endpoint. With multi-endpoint routing, this ensures a tool registered for the tenant endpoint is not visible on the admin endpoint's sessions.

The programmatic interfaces use an explicit `McpSchema.object()` fluent builder for schemas:

```java
McpSchema.object()
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

This means a single `McpServer` port can serve both `/tenants/{tenantId}/mcp` and `/admin/mcp` with completely separate capabilities and initialization logic, while using one server-wide `McpSessionStore` partitioned by endpoint class.

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
@ThreadSafe
public interface McpRequestInterceptor {
    @Nullable
    default Object interceptRequest(@NonNull McpRequestContext context,
                                    @NonNull McpHandlerInvocation invocation) throws Exception {
        return invocation.invoke();
    }
}

@ThreadSafe
@FunctionalInterface
public interface McpHandlerInvocation {
    @Nullable
    Object invoke() throws Exception;
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

### 14. Conservative v1 capability profile

Although the spec target is `2025-11-25`, this proposal intentionally implements a conservative subset in v1 and omits optional metadata and capabilities until Soklet has a coherent public API for them.

Server capability advertisement in v1:

- `tools: {}` when at least one tool is available
- `prompts: {}` when at least one prompt is available
- `resources: {}` when at least one resource or resource-list handler is available

Not advertised in v1:

- `tools.listChanged`
- `prompts.listChanged`
- `resources.listChanged`
- `resources.subscribe`
- `logging`
- `completions`
- `tasks`
- `experimental`

Protocol fields intentionally omitted from responses in v1 unless later added to the public API:

- `serverInfo.title`
- `serverInfo.description`
- `serverInfo.icons`
- `serverInfo.websiteUrl`
- prompt titles and icons
- resource titles, icons, annotations, and size metadata
- resource templates
- tool annotations, execution metadata, and output schema

Deterministic ordering rules:

- `tools/list` returns tools sorted by tool name
- `prompts/list` returns prompts sorted by prompt name
- `resources/list` preserves application order from `McpListResourcesResult`

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
@ThreadSafe
public interface McpEndpoint {
    @NonNull
    default McpSessionContext initialize(@NonNull McpInitializationContext context,
                                         @NonNull McpSessionContext session) {
        return session;
    }

    @NonNull
    default McpToolResult handleToolError(@NonNull Throwable throwable,
                                          @NonNull McpToolCallContext context) {
        return McpToolResult.fromErrorMessage(throwable.getMessage());
    }

    @NonNull
    default McpJsonRpcError handleError(@NonNull Throwable throwable,
                                        @NonNull McpRequestContext context) {
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
- `@McpListResources` — method must return `McpListResourcesResult`; no `@McpArgument` allowed

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

Minimum fields exposed by the context types:

- `McpRequestContext` — underlying Soklet `Request`, resolved endpoint class, JSON-RPC method name, JSON-RPC request ID if present, session ID if present, and negotiated protocol version if present
- `McpToolCallContext` — `McpRequestContext` plus progress token if the client supplied one
- `McpInitializationContext` — protocol version, client capabilities, client info, endpoint path parameters, and the underlying `Request`
- `McpListResourcesContext` — pagination cursor, request metadata, and session/endpoint context

Concrete public contracts:

All public MCP strategy interfaces are expected to be safely reusable across concurrent requests. Value and record-like carrier types are modeled as immutable snapshots; request-scoped accessors are exposed via `@ThreadSafe` interfaces.

```java
@ThreadSafe
public interface McpSessionContext {
    @NonNull
    Optional<@NonNull Object> get(@NonNull String key);

    @NonNull
    <T> Optional<@NonNull T> get(@NonNull String key,
                                 @NonNull Class<T> type);

    @NonNull
    Boolean contains(@NonNull String key);

    @NonNull
    McpSessionContext with(@NonNull String key,
                           @NonNull Object value);

    @NonNull
    McpSessionContext without(@NonNull String key);

    @NonNull
    Map<@NonNull String, @NonNull Object> asMap();

    @NonNull
    static McpSessionContext fromBlankSlate() { ... }

    @NonNull
    static McpSessionContext fromValues(@NonNull Map<@NonNull String, @NonNull Object> values) { ... }
}

@Immutable
public record McpClientInfo(
    @NonNull String name,
    @Nullable String version
) {}

@Immutable
public record McpClientCapabilities(
    @NonNull McpObject value
) {}

@ThreadSafe
public interface McpRequestContext {
    @NonNull
    Request getRequest();

    @NonNull
    Class<? extends McpEndpoint> getEndpointClass();

    @NonNull
    String getJsonRpcMethod();

    @NonNull
    Optional<@NonNull Object> getJsonRpcRequestId();

    @NonNull
    Optional<@NonNull String> getSessionId();

    @NonNull
    Optional<@NonNull String> getProtocolVersion();
}

@ThreadSafe
public interface McpToolCallContext {
    @NonNull
    McpRequestContext getRequestContext();

    @NonNull
    Optional<@NonNull Object> getProgressToken();
}

@ThreadSafe
public interface McpInitializationContext {
    @NonNull
    Request getRequest();

    @NonNull
    String getProtocolVersion();

    @NonNull
    McpClientCapabilities getClientCapabilities();

    @NonNull
    Optional<@NonNull McpClientInfo> getClientInfo();

    @NonNull
    Optional<@NonNull String> getEndpointPathParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getEndpointPathParameter(@NonNull String name,
                                                      @NonNull Class<T> type);
}

@ThreadSafe
public interface McpListResourcesContext {
    @NonNull
    McpRequestContext getRequestContext();

    @NonNull
    Optional<@NonNull String> getCursor();
}
```

### Parameter injection priority

1. Known injectable framework type → inject by type, no annotation needed
2. `@McpEndpointPathParameter` → bind from HTTP endpoint path
3. `@McpArgument` → bind from MCP JSON arguments
4. `@McpUriParameter` → bind from resource URI template
5. Otherwise → compile error (caught by processor) and startup error

### Argument binding semantics

- A missing required `@McpArgument` produces a JSON-RPC invalid-params error.
- A missing optional `@McpArgument(optional = true)` binds to Java `null`.
- Explicit JSON `null` is only accepted for optional reference-typed parameters; it is rejected for primitive parameters and for required arguments.
- Type mismatches during binding produce a JSON-RPC invalid-params error.
- Enum arguments are matched case-sensitively against enum constant names.
- Annotation-derived tool and prompt schemas are root-level object schemas with `additionalProperties: false`. Unexpected arguments are rejected as invalid params.
- `McpSchema.object()` follows the same default: root object plus `additionalProperties: false` unless a future API explicitly opts into looser validation.

### Result types

```java
// Tool result
McpToolResult.builder()
    .structuredContent(payload)          // marshaled by McpResponseMarshaler
    .content(McpTextContent.fromText("..."))
    .build()

// Tool error result (isError = true)
McpToolResult.fromErrorMessage("Something went wrong.")

// Prompt result
McpPromptResult.fromMessages(
    McpPromptMessage.fromSystemText("..."),
    McpPromptMessage.fromUserText("..."),
    McpPromptMessage.fromAssistantText("...")
)

// Resource contents
McpResourceContents.fromText(uri, text, mimeType)
McpResourceContents.fromBlob(uri, base64Data, mimeType)

// Resource list entry
McpListedResource.fromComponents(uri, name, mimeType)

// Resource list result
McpListResourcesResult.fromResources(resources)
McpListResourcesResult.fromResourcesAndNextCursor(resources, nextCursor)

// JSON-RPC error (for handleError() hook)
McpJsonRpcError.fromCodeAndMessage(-32603, "Internal error")
```

`McpListResourcesResult` is a small immutable type:

```java
@Immutable
public record McpListResourcesResult(
    @NonNull List<@NonNull McpListedResource> resources,
    @Nullable String nextCursor
) {
    @NonNull
    static McpListResourcesResult fromResources(
            @NonNull List<@NonNull McpListedResource> resources) { ... }

    @NonNull
    static McpListResourcesResult fromResourcesAndNextCursor(
            @NonNull List<@NonNull McpListedResource> resources,
            @NonNull String nextCursor) { ... }
}
```

Programmatic handlers and `McpResponseMarshaler` use a small public MCP value model rather than the internal `Json*` types:

```java
@ThreadSafe
public sealed interface McpValue permits McpObject, McpArray, McpString, McpNumber, McpBoolean, McpNull {}

@Immutable
public record McpObject(
    @NonNull Map<@NonNull String, @NonNull McpValue> values
) implements McpValue {
    @NonNull
    public Optional<@NonNull McpValue> get(@NonNull String name) { ... }
}

@Immutable
public record McpArray(
    @NonNull List<@NonNull McpValue> values
) implements McpValue {}

@Immutable
public record McpString(
    @NonNull String value
) implements McpValue {}

@Immutable
public record McpNumber(
    @NonNull BigDecimal value
) implements McpValue {}

@Immutable
public record McpBoolean(
    @NonNull Boolean value
) implements McpValue {}

@Immutable
public enum McpNull implements McpValue {
    INSTANCE
}
```

`McpObject` preserves insertion order. All public MCP value types are immutable snapshots.

### `McpServer` builder surface

- `port(Integer)` — required
- `host(String)`
- `handlerResolver(McpHandlerResolver)` — required
- `requestInterceptor(McpRequestInterceptor)`
- `responseMarshaler(McpResponseMarshaler)`
- `originPolicy(McpOriginPolicy)` — defaults to `McpOriginPolicy.nonBrowserClientsOnlyInstance()`
- `sessionStore(McpSessionStore)` — defaults to `McpSessionStore.fromInMemory()`
- `requestTimeout(Duration)`
- `requestHandlerTimeout(Duration)`
- `requestHandlerConcurrency(Integer)`
- `requestHandlerQueueCapacity(Integer)`
- `requestHandlerExecutorServiceSupplier(Supplier<ExecutorService>)`
- `maximumRequestSizeInBytes(Integer)`
- `requestReadBufferSizeInBytes(Integer)`
- `concurrentConnectionLimit(Integer)`
- `connectionQueueCapacity(Integer)` — per-stream outbound queue capacity
- `shutdownTimeout(Duration)`
- `writeTimeout(Duration)`
- `heartbeatInterval(Duration)`
- `idGenerator(IdGenerator<String>)` — session IDs must be visible ASCII for `MCP-Session-Id`

Intentionally absent from `McpServer.Builder`:

- no `multipartParser(...)` — MCP requests are JSON only
- no `broadcasterCacheCapacity(...)` or `resourcePathCacheCapacity(...)` — unlike Soklet SSE, MCP streams are session-bound, not resource-path-bound

### `McpHandlerResolver`

- `McpHandlerResolver.fromClasspathIntrospection()` — reads processor-generated table; JVM-wide singleton
- `McpHandlerResolver.fromClasses(Set<Class<?>>)` — runtime reflection; for tests and processor-free builds
- `.withTool(McpToolHandler, Class<? extends McpEndpoint>)` — programmatic escape hatch scoped to an endpoint class; composable on either factory result
- `.withPrompt(McpPromptHandler, Class<? extends McpEndpoint>)`
- `.withResource(McpResourceHandler, Class<? extends McpEndpoint>)`
- `.withResourceList(McpResourceListHandler, Class<? extends McpEndpoint>)`

Resolver composition rules:

- `fromClasspathIntrospection()` returns an immutable singleton base resolver.
- `.withTool(...)`, `.withPrompt(...)`, `.withResource(...)`, and `.withResourceList(...)` never mutate that singleton; they return a new immutable composite resolver that delegates to the base resolver first and then overlays programmatic handlers.
- A duplicate tool, prompt, or resource name within the same endpoint is a startup error even if the duplicate comes from mixing annotations and programmatic handlers.

### Programmatic handler contracts

Programmatic handlers participate in discovery exactly like annotated handlers. They appear in endpoint-scoped list and dispatch operations for the endpoint class they are registered against.

The contract is intentionally JSON-first:

```java
@ThreadSafe
public interface McpToolHandler {
    @NonNull
    String getName();

    @NonNull
    String getDescription();

    @NonNull
    McpSchema getInputSchema();

    @NonNull
    McpToolResult handle(@NonNull McpToolHandlerContext context) throws Exception;
}

@ThreadSafe
public interface McpPromptHandler {
    @NonNull
    String getName();

    @NonNull
    String getDescription();

    @NonNull
    McpSchema getArgumentsSchema();

    @NonNull
    McpPromptResult handle(@NonNull McpPromptHandlerContext context) throws Exception;
}

@ThreadSafe
public interface McpResourceHandler {
    @NonNull
    String getUri();

    @NonNull
    String getName();

    @NonNull
    String getMimeType();

    @NonNull
    default Optional<@NonNull String> getDescription() { return Optional.empty(); }

    @NonNull
    McpResourceContents handle(@NonNull McpResourceHandlerContext context) throws Exception;
}

@ThreadSafe
public interface McpResourceListHandler {
    @NonNull
    McpListResourcesResult handle(@NonNull McpResourceListHandlerContext context) throws Exception;
}
```

Programmatic handler context contracts:

```java
@ThreadSafe
public interface McpToolHandlerContext {
    @NonNull
    McpToolCallContext getToolCallContext();

    @NonNull
    McpSessionContext getSessionContext();

    @NonNull
    McpClientCapabilities getClientCapabilities();

    @NonNull
    McpObject getArguments();

    @NonNull
    Optional<@NonNull String> getEndpointPathParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getEndpointPathParameter(@NonNull String name,
                                                      @NonNull Class<T> type);
}

@ThreadSafe
public interface McpPromptHandlerContext {
    @NonNull
    McpRequestContext getRequestContext();

    @NonNull
    McpSessionContext getSessionContext();

    @NonNull
    McpClientCapabilities getClientCapabilities();

    @NonNull
    McpObject getArguments();

    @NonNull
    Optional<@NonNull String> getEndpointPathParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getEndpointPathParameter(@NonNull String name,
                                                      @NonNull Class<T> type);
}

@ThreadSafe
public interface McpResourceHandlerContext {
    @NonNull
    McpRequestContext getRequestContext();

    @NonNull
    McpSessionContext getSessionContext();

    @NonNull
    String getRequestedUri();

    @NonNull
    Optional<@NonNull String> getUriParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getUriParameter(@NonNull String name,
                                             @NonNull Class<T> type);

    @NonNull
    Optional<@NonNull String> getEndpointPathParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getEndpointPathParameter(@NonNull String name,
                                                      @NonNull Class<T> type);
}

@ThreadSafe
public interface McpResourceListHandlerContext {
    @NonNull
    McpListResourcesContext getListResourcesContext();

    @NonNull
    McpSessionContext getSessionContext();

    @NonNull
    Optional<@NonNull String> getEndpointPathParameter(@NonNull String name);

    @NonNull
    <T> Optional<@NonNull T> getEndpointPathParameter(@NonNull String name,
                                                      @NonNull Class<T> type);
}
```

This keeps the escape hatch truly general-purpose and avoids reflection-heavy edge cases leaking back into the primary annotation path.

### `McpResponseMarshaler`

Separate from HTTP `ResponseMarshaler`. Responsible only for marshaling application objects supplied to `McpToolResult.structuredContent(...)` into `McpValue`.

```java
@ThreadSafe
public interface McpResponseMarshaler {
    @NonNull
    McpValue marshalStructuredContent(@Nullable Object value,
                                      @NonNull McpStructuredContentContext context);

    @NonNull
    static McpResponseMarshaler defaultInstance() { ... }
}

@ThreadSafe
public interface McpStructuredContentContext {
    @NonNull
    Class<? extends McpEndpoint> getEndpointClass();

    @NonNull
    String getToolName();

    @NonNull
    McpToolCallContext getToolCallContext();

    @NonNull
    McpSessionContext getSessionContext();
}
```

Default behavior:

- if `value` is `null`, return `McpNull.INSTANCE`
- if `value` is already an `McpValue`, pass it through unchanged
- otherwise fail fast with `IllegalArgumentException`

`McpResponseMarshaler` is not used for:

- JSON-RPC envelopes and protocol DTOs
- prompt messages
- resource contents returned from `McpResourceContents.fromText(...)` or `.fromBlob(...)`

Applications that want Gson/Jackson-backed structured content supply a custom `McpResponseMarshaler` that maps domain objects into `McpValue` trees.

### `McpOriginPolicy`

```java
@ThreadSafe
public interface McpOriginPolicy {
    @NonNull
    Boolean isAllowed(@NonNull McpOriginCheckContext context);

    @NonNull
    static McpOriginPolicy rejectAllInstance() { ... }

    @NonNull
    static McpOriginPolicy nonBrowserClientsOnlyInstance() { ... }

    @NonNull
    static McpOriginPolicy acceptAllInstance() { ... }

    @NonNull
    static McpOriginPolicy fromWhitelistedOrigins(
            @NonNull Set<@NonNull String> whitelistedOrigins) { ... }

    @NonNull
    static McpOriginPolicy fromOriginAuthorizer(
            @NonNull Predicate<@NonNull McpOriginCheckContext> originAuthorizer) { ... }
}
```

```java
@Immutable
public record McpOriginCheckContext(
    @NonNull Request request,
    @NonNull Class<? extends McpEndpoint> endpointClass,
    @NonNull HttpMethod httpMethod,
    @Nullable String origin,
    @Nullable String sessionId
) {}
```

- Invoked before MCP protocol dispatch on `POST`, `GET`, and `DELETE`.
- `origin == null` means the client did not send an `Origin` header. The default `nonBrowserClientsOnlyInstance()` allows this case and rejects any request that does send `Origin`.
- `Origin: null` is treated as the literal string `"null"` and is rejected unless explicitly permitted.
- Rejection becomes `403 Forbidden`; `McpOriginPolicy` does not write CORS headers or attempt preflight handling.
- `fromWhitelistedOrigins(...)` allows requests with no `Origin` and also allows requests whose `Origin` matches the provided normalized whitelist.
- Origin normalization in v1 lowercases scheme and host, strips a trailing slash, and elides default ports (`:80` for `http`, `:443` for `https`).
- The policy is endpoint-aware because `McpOriginCheckContext` includes the resolved endpoint class. A single `McpServer` can therefore allow browser access for `/admin/mcp` and reject it for `/tenants/{tenantId}/mcp`, or vice versa.

### `McpSessionStore`

```java
@ThreadSafe
public interface McpSessionStore {
    void create(@NonNull McpStoredSession session);

    @NonNull
    Optional<@NonNull McpStoredSession> findBySessionId(@NonNull String sessionId);

    @NonNull
    Boolean replace(@NonNull McpStoredSession expected,
                    @NonNull McpStoredSession updated);

    void deleteBySessionId(@NonNull String sessionId);

    @NonNull
    static McpSessionStore fromInMemory() { ... }
}
```

```java
@Immutable
public record McpStoredSession(
    @NonNull String sessionId,
    @NonNull Class<? extends McpEndpoint> endpointClass,
    @NonNull Instant createdAt,
    @NonNull Instant lastActivityAt,
    @NonNull Boolean initialized,
    @NonNull Boolean initializedNotificationReceived,
    @Nullable String protocolVersion,
    @Nullable McpClientCapabilities clientCapabilities,
    @NonNull McpSessionContext sessionContext,
    @Nullable Instant terminatedAt,
    @NonNull Long version
) {}
```

- `McpSessionStore` is a threadsafe persistence contract over immutable `McpStoredSession` snapshots. The store is responsible for durability and atomic replacement; `McpSessionManager` is responsible for protocol state transitions and SSE cleanup.
- `create(...)` inserts a new session record and fails fast if the session ID already exists.
- `replace(expected, updated)` is compare-and-set. It succeeds only if `expected` is still the current stored value, typically by matching `version`. This is the concurrency boundary for `POST`, `GET`, and `DELETE`.
- `deleteBySessionId(...)` physically removes a session record after protocol teardown. Normal session shutdown is modeled first as a successful `replace(...)` that sets `terminatedAt`, then a later delete once SSE streams are closed.
- `fromInMemory()` returns a new in-process store for a single JVM. It is the default implementation for v1 and is not suitable for cross-JVM sharing without a custom store.
- Endpoint isolation is part of the stored record: the session is bound to one endpoint class at creation time, and later requests must match that endpoint or be treated as invalid.
- `McpSessionContext` values are opaque application objects. `fromInMemory()` can store arbitrary values. A custom out-of-process store is responsible for its own serialization policy and may therefore impose stricter constraints.

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
- `AnnotatedMcpToolHandler` / `AnnotatedMcpPromptHandler` / `AnnotatedMcpResourceHandler` / `AnnotatedMcpResourceListHandler`
- `McpParameterBinder`

### JSON layer

Small internal JSON tree and codec. No third-party dependency.

Types: `JsonValue`, `JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBoolean`, `JsonNull`, `JsonParser`, `JsonWriter`

Rules: no reflection-based mapping; no general-purpose serializer; explicit mapping between JSON trees and MCP DTOs.

`McpSchema.object()` is the public fluent schema builder used by the programmatic escape hatch. The internal `Json*` types remain internal implementation details and are adapted to/from the public `McpValue` model at the API boundary.

## MCP endpoint HTTP behavior

Assume `path("/mcp")`.

### `POST /mcp`

- Validate `Content-Type: application/json`, `Accept`, `Origin`, `MCP-Protocol-Version`, and `MCP-Session-Id` as applicable to the current lifecycle stage
- Parse exactly one JSON-RPC message
- Enforce lifecycle and capability rules
- Dispatch to handler

Response policy:

- notifications-only or responses-only input → `202 Accepted` with no body
- request input with only terminal responses to send → `200 OK` with `application/json`
- request input that must interleave server messages or multiple responses over time → `200 OK` with `text/event-stream`
- Invalid request → protocol-appropriate JSON-RPC error payload

Header and lifecycle rules:

- `Accept` on `POST` must allow both `application/json` and `text/event-stream`
- `initialize` must be a non-batched JSON-RPC request and must not include `MCP-Session-Id`
- successful `initialize` responses include `MCP-Session-Id` and establish the session
- after `initialize`, all client HTTP requests must include both `MCP-Session-Id` and `MCP-Protocol-Version`
- `MCP-Protocol-Version` must match the negotiated session version or the request is rejected with `400`
- missing required `MCP-Session-Id` on non-initialize requests is `400`
- unknown, dead, or endpoint-mismatched `MCP-Session-Id` is `404`
- before `notifications/initialized` is received, only `initialize`, `notifications/initialized`, and `ping` are accepted
- JSON-RPC batch arrays are rejected with `400` in v1

### `GET /mcp`

- Validate `Accept: text/event-stream`, `Origin`, `MCP-Protocol-Version`, `MCP-Session-Id`, and `Last-Event-ID`
- Open SSE stream for outbound server-to-client messages

GET behavior:

- `Accept` on `GET` must allow `text/event-stream`
- `McpServer` always supports `GET` for configured MCP endpoints, so it does not use `405 Method Not Allowed` for normal endpoint paths
- multiple live GET streams per session are allowed
- each server-originated JSON-RPC message is written to exactly one stream, never broadcast to all streams
- request-scoped progress or related server messages stay on the POST response stream if that POST was upgraded to SSE
- unsolicited session-scoped server messages, if any, go to the most recently established live GET stream
- v1 does not implement resumability or redelivery; SSE events therefore omit `id`, and a GET request carrying `Last-Event-ID` is rejected with `400 Bad Request`

### `DELETE /mcp`

- Validate `Origin`, `MCP-Protocol-Version`, and `MCP-Session-Id`
- Terminate session and close active SSE streams
- Return `204 No Content`

DELETE semantics:

- v1 has no built-in principal model, so "session ownership" means possession of a valid session ID for the resolved endpoint path
- applications that authenticate users should enforce user-to-session binding in `McpRequestInterceptor` and/or their custom `McpSessionStore`
- deleting an already-terminated or unknown session returns `404`

## Session model

Sessions are stateful in v1. Each session is scoped to a specific endpoint — a session established on `/tenants/{tenantId}/mcp` is entirely separate from one on `/admin/mcp`. Each session holds:

- session ID (from `IdGenerator<String>`)
- endpoint class reference (which `@McpServerEndpoint` this session belongs to)
- creation and last-activity timestamps
- initialization status
- whether `notifications/initialized` has been received
- negotiated protocol version and capabilities
- typed key/value bag for application data (e.g., `tenantId`)
- termination status

`McpSessionContext` is immutable. `initialize()` returns an updated copy via `.with(key, value)`.

`McpSessionStore` is a compare-and-set store over immutable `McpStoredSession` snapshots. `DefaultMcpSessionStore` is the in-process implementation returned by `McpSessionStore.fromInMemory()`.

Typical runtime flow:

- `initialize` creates an uninitialized session record, invokes `McpEndpoint.initialize(...)`, then persists the initialized `McpSessionContext` and negotiated capabilities via `replace(...)`
- `notifications/initialized` flips `initializedNotificationReceived` to `true`
- subsequent `POST` and `GET` requests load the record, verify endpoint match, negotiated protocol version, and non-terminated state, then update `lastActivityAt` via `replace(...)`
- `DELETE` marks the session terminated via `replace(...)`, closes active SSE streams, and finally removes the record with `deleteBySessionId(...)`

The configured `McpSessionStore` is server-wide, not endpoint-specific. Endpoint isolation comes from the stored `endpointClass` field and runtime validation against the resolved endpoint path.

## SSE model

`McpServer` manages its own SSE streams (session-bound, not path-bound). Internal SSE helpers (framing, heartbeats, write queues, backpressure) are extracted and shared rather than copied from `DefaultServerSentEventServer`.

## Security

Minimum:

- Validate `Origin`; reject invalid origins with `403`
- Require `MCP-Session-Id` and `MCP-Protocol-Version` on all non-initialize requests
- Isolate sessions by endpoint and session ID
- Reject unknown/dead sessions with `404`
- Reject unsupported protocol versions with `400`

`McpOriginPolicy` is intentionally simpler than HTTP CORS. It is a request admission policy, not a response-header policy. The default behavior is "allow non-browser clients; reject browser-style `Origin` headers unless explicitly whitelisted."

Authorization support is designed for but deferred. In v1, authenticated applications are expected to layer authorization via `McpRequestInterceptor`, endpoint code, and/or a custom `McpSessionStore` rather than through a Soklet-owned auth abstraction.

## Implementation phases

### Phase 1: Public API skeleton

- Add `McpServer`, `McpHandlerResolver`, `McpEndpoint` interface, `@McpServerEndpoint`
- Add `McpTool`, `McpPrompt`, `McpResource`, `McpListResources`, parameter annotations
- Add result types (`McpToolResult`, `McpPromptResult`, `McpResourceContents`, `McpListedResource`, `McpListResourcesResult`)
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
- `McpSchema.object()` fluent schema builder and `McpType` enum
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
- Programmatic `McpResourceListHandler` interface
- `.withTool(...)` / `.withPrompt(...)` / `.withResource(...)` / `.withResourceList(...)` composition

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
- Wrong return type on `@McpListResources` → compile error
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
- JSON-RPC batch arrays are rejected with `400`
- `McpOriginPolicy.nonBrowserClientsOnlyInstance()` allows missing `Origin` and rejects explicit browser-style origins
- Session state transitions
- `McpSessionStore.replace(...)` enforces compare-and-set semantics under concurrent updates
- `McpHandlerResolver.fromClasspathIntrospection()` remains immutable when composed with programmatic handlers
- `McpParameterBinder` — all parameter sources
- Type whitelist schema generation
- `McpResponseMarshaler.defaultInstance()` passes through `McpValue` and fails fast on arbitrary objects
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
- Programmatic `McpToolHandler` / `McpPromptHandler` / `McpResourceHandler` / `McpResourceListHandler` dispatch
- Notification POST returning `202`
- JSON-RPC batch array on POST → `400`
- Missing `MCP-Session-Id` on non-initialize request → `400`
- Mismatched `MCP-Protocol-Version` → `400`
- GET SSE stream establishment
- GET with `Last-Event-ID` → `400` in v1
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
