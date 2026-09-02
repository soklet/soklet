# Migrating from Soklet 3.5.1 to 4.0.0

Soklet 4.0.0 is a deliberate breaking release. Upgrade the lifecycle first,
then HTTP/SSE integration and simulation, and finally MCP. Do not place the
4.0.0 JAR under an unchanged 3.5.1 application and expect binary compatibility.
The machine-readable [incompatibility ledger](api/mcp/current-incompatibilities.jsonl)
is an audit aid; this guide is the migration path.

## Supported release lines

Until 4.0.0 is published, 3.5.1 remains the latest supported release. On the
date 4.0.0 is published, the entire 3.x line reaches end of life: it receives
no new features, compatibility work, maintenance releases, or promised
security fixes. Published 3.x artifacts remain available, but applications
that stay on them do so without project support.

After publication, only the latest 4.x patch release is supported. Snapshots,
older 4.x patches, and unreleased source builds are not supported releases.
This policy is intentionally explicit because 4.0.0 has no adapter for the
legacy MCP protocol or Java API.

## Recommended migration order

1. Move the build to Java 17 or later and set the Soklet coordinate to
   `com.soklet:soklet:4.0.0`.
2. Replace direct transport lifecycle calls and per-server shutdown settings
   with the Soklet-wide lifecycle.
3. Choose either `SokletApplication` for a standalone process or direct
   `Soklet` ownership for an embedder.
4. Rebuild simulation configuration from the scope-owned transports.
5. Recompile all annotation-driven code with `-parameters` and
   `SokletProcessor` enabled.
6. Rebuild MCP registrations and handlers for the exact `2026-07-28` profile.
7. Update deployment termination budgets, lifecycle observers, metrics, and
   downstream integrations.
8. Exercise the application through a real loopback listener in addition to
   off-network simulation.

## Lifecycle and process ownership

### One lifecycle owns all transports

`Soklet` is now a one-shot aggregate lifecycle. `HttpServer`, `SseServer`, and
`McpServer` are configured components; their public `start()`, `stop()`,
`isStarted()`, `close()`, and `AutoCloseable` contracts are gone. Start and
shut down the containing `Soklet` instead. A stopped instance cannot restart;
construct a new configuration and a new `Soklet` for a new generation.

The old synchronous, void `Soklet.stop()` stopped transports before returning
but provided no aggregate terminal evidence. It is replaced by:

```java
CompletionStage<ShutdownResult> completion = soklet.shutdown();
ShutdownResult result = soklet.awaitShutdown();
```

`shutdown()` promptly publishes intent and always returns the same read-only
completion stage. `awaitShutdown()` takes no shutdown trigger and returns the
immutable terminal result. `Soklet.close()` remains available for direct
embedders; it requests shutdown, joins it uninterruptibly, restores interrupt
status, and throws if the result is unsuccessful.

### Standalone applications use the runner

The old pattern put process concerns into `Soklet.awaitShutdown(trigger)`:

```java
try (Soklet soklet = Soklet.fromConfig(config)) {
  soklet.start();
  soklet.awaitShutdown(ShutdownTrigger.ENTER_KEY);
}
```

For a standalone process, replace it with:

```java
ShutdownResult result =
    SokletApplication.run(config, ShutdownTrigger.ENTER_KEY);
```

`SokletApplication` owns the JVM shutdown hook and optional runner-scoped
`ENTER_KEY` trigger. The core lifecycle itself does not read standard input or
own process hooks. When a standalone process owns application resources too,
configure one one-shot application and supply the bounded cleanup and any
additional triggers to its run:

```java
ShutdownResult result = SokletApplication.fromConfig(config).run(
    ShutdownCleanup.fromTimeoutAndAction(
        Duration.ofSeconds(5),
        shutdownResult -> applicationResources.close()),
    ShutdownTrigger.ENTER_KEY);
```

After any run attempt begins, the application cannot be run a second time or
concurrently. Use cleanup only for a resource that is application-owned,
ingress-exclusive, safe to clean after a complete core shutdown, and bounded by
an explicit timeout. Stateful observers are not automatically safe cleanup
targets: they need an application-defined delivery barrier first. Cleanup is
skipped when core shutdown is incomplete. If you tested an earlier 4.0
snapshot, remove `SokletApplicationOptions`; create the one-shot application
with `SokletApplication.fromConfig(config)` and pass triggers and cleanup to its
`run(...)` invocation instead.

Embedders that already own process signals should continue to use
`Soklet.fromConfig(config)`, `start()`, `shutdown()`, and `awaitShutdown()` and
should not add the standalone runner's process ownership.

### Shared lifecycle policy and changed defaults

The three transport-specific shutdown-deadline setters are removed. Configure
one `LifecyclePolicy` on `SokletConfig`; it has no hidden HTTP, SSE, or MCP
graceful cap.

| Boundary | 3.5.1 default/guidance | 4.0.0 default | Migration effect |
| --- | ---: | ---: | --- |
| Normal startup | Unbounded transport-specific behavior | 30 s | Startup now has a shared deadline; use `noStartupTimeout()` only deliberately. |
| Cancelation of live startup after shutdown intent | Not a shared phase | 2 s | A non-cooperative startup can produce an incomplete result after this boundary. |
| HTTP graceful shutdown | 5 s default; 30 s production guidance | 15 s | More time than the old default, less than the old guidance. |
| SSE graceful shutdown | 1 s | 15 s | Idle streams close promptly; outstanding writes, loops, and executors share the 15 s boundary. |
| MCP graceful shutdown | 5 s | 15 s | MCP receives three times the old graceful interval; recalculate any explicit deployment budget. |
| Forced shutdown | Not a shared phase | 3 s | Owned work is interrupted/canceled and observed within this separate phase. |

For example:

```java
LifecyclePolicy policy = LifecyclePolicy.builder()
    .startupTimeout(Duration.ofSeconds(30))
    .startupCancelationTimeout(Duration.ofSeconds(2))
    .gracefulShutdownDuration(Duration.ofSeconds(20))
    .forcedShutdownDuration(Duration.ofSeconds(3))
    .build();

SokletConfig config = SokletConfig.withHttpServer(httpServer)
    .lifecyclePolicy(policy)
    .build();
```

Review the builder Javadocs before selecting zero-duration phases or unbounded
startup. A normal running shutdown with defaults is bounded by 18 seconds;
shutdown intent during startup is bounded by 20 seconds from that intent.

### Kubernetes and orchestrator budget

The platform must allow more time than Soklet's internal phases:

```text
termination grace
  > preStop/load-balancer delay
  + startup cancellation (when termination can arrive during boot)
  + graceful shutdown
  + forced shutdown
  + configured application cleanup (when present)
  + 250 ms terminal-report attempt
  + other JVM hooks and VM halt
  + safety reserve
```

With the defaults, a five-second external drain, no application cleanup, two
seconds for other hooks/VM halt, and a three-second reserve totals 30.25
seconds. Round up to at least 31 seconds. The commonly documented 35-second
setting retains a reserve. Adding a five-second cleanup budget raises the same
example's minimum to 36 seconds, so use at least 40 seconds or reduce a measured
component.

### Observer and result changes

`LifecycleObserver.didFailToStopSoklet(...)` and the three transport-specific
`didFailToStop...` callbacks are removed. The corresponding `didStop...`
callback now receives `ShutdownResult` or `ShutdownComponentResult`, which
is the terminal evidence for successful, forced, unexpected, residual, and
unknown termination. Observer callbacks are observational: exceptions are
contained and do not rewrite lifecycle results.

Metrics and downstream OpenTelemetry projections must replace the old MCP
`clean`/`residual_handlers` vocabulary with exactly:

- `not_started`
- `graceful_termination`
- `forced_termination`
- `unexpected_termination`
- `residual_activity`
- `termination_unknown`

Do not infer this set dynamically from enum constants; use an exhaustive
mapping so a future enum addition cannot silently change metric cardinality.

## HTTP, SSE, and custom transports

Custom transport SPIs now participate in an aggregate lifecycle rather than
being independently started and stopped. Migrate custom implementations to
the current transport identity, attachment/runtime, lifecycle context, and
termination-proof contracts. A decorator must preserve stable identity and
must distinguish framework-mediated transparent delegation from a
lifecycle-owning root. Soklet can validate honest evidence presented through
those contracts; it cannot detect a custom transport that lies about its own
attestation or behavior.

`HttpServer` is no longer an injectable resource-method parameter. Remove it
from resource method signatures and acquire application services through the
application's own dependency injection. `SseServer` injection remains
available so a resource method can acquire its `SseBroadcaster`.

`SokletConfig.copy()` and `SokletConfig.Copier` are removed. Build each config
explicitly with one of the public `withHttpServer(...)`, `withSseServer(...)`,
or `withMcpServer(...)` entry points. Reusing a transport object across
lifecycle generations is not a replacement for copying: each generation needs
fresh one-shot transports.

## Simulator migration

`Soklet.runSimulator(...)` is removed. Each simulation now supplies a
scope-bound `SimulatorConfig.Builder` that installs its fresh, off-network
transports and builds the application configuration:

```java
ShutdownResult result = SokletSimulator.run(config -> config
        .httpServer()
        .sseServer()
        .resourceMethodResolver(resourceMethods)
        .build(), simulator -> {
          HttpRequestResult response = simulator.performHttpRequest(request);
          // assertions
        });
```

For simulated MCP, configure and build the server through the scope-bound MCP
builder:

```java
config.mcpServer(port, mcpServerBuilder -> mcpServerBuilder
    .endpointRegistry(endpointRegistry)
    .build())
```

The supplied MCP builder belongs to that simulation scope. Do not capture a
live server or reuse a `SimulatorConfig`, its builder, or a simulated transport
between scopes.
`SimulatorOptions` controls materialization and capture behavior and is supplied
with `config.simulatorOptions(options)`. Set lifecycle deadlines with
`config.lifecyclePolicy(policy)`.
Simulation is deterministic and off-network, so it does not prove kernel TCP,
proxy, TLS, or live write-idle behavior.

## MCP wire migration

Treat MCP as a new integration, not a rename exercise. Soklet 4.0.0 supports
exactly the modern `2026-07-28` profile. There is no automatic “latest” mode,
fallback, or legacy adapter.

| 3.5.1 behavior | 4.0.0 behavior |
| --- | --- |
| `initialize` negotiation followed by initialized state | First request may be `server/discover`; no initialization state exists. |
| Server-managed session IDs and session store | Stateless per-request metadata and application-owned durable state. |
| `GET` event stream plus `DELETE` session termination | Streamable HTTP `POST`; `GET` and `DELETE` return 405. |
| `MCP-Session-Id` / `Last-Event-ID` | Ignored and never emitted; attempts to add legacy headers fail closed. |
| Legacy SSE stream and request-result carriers | A POST response stream owns progress, input requests, and its terminal result. |
| Session-scoped client/capability state | Validated protocol metadata and client capabilities are supplied per request. |
| Legacy operation set | `server/discover`, current list/read/get/call methods, input responses, listening/subscriptions, and profile-defined notifications. |

A readable legacy `initialize` request receives a narrow modern-only migration
diagnostic naming `2026-07-28`. It is not negotiation or a compatibility
handshake. Malformed transport/JSON and unrelated methods do not acquire that
diagnostic.

## MCP Java API migration

The 3.5.1 MCP Java API was removed and rebuilt. Important migration patterns
are:

- Replace `McpArray`, `McpObject`, `McpString`, `McpNumber`, `McpBoolean`,
  `McpNull`, and `McpValue` with the immutable `McpJsonArray`,
  `McpJsonObject`, `McpJsonString`, `McpJsonNumber`, `McpJsonBoolean`,
  `McpJsonNull`, and `McpJsonValue` family.
- Replace session, initialization, stored-session, legacy SSE-stream, response-
  marshaler, legacy schema, and old request-result types with current request
  contexts, operation registrations/results, response features, and
  application-owned durable state.
- Build endpoints with `McpEndpoint`/`McpEndpointRegistry` or generated
  `@McpServerEndpoint` descriptors. Capabilities are derived from registrations.
- Use Java-first typed schemas. Soklet derives the closed Tool Schema Profile 1
  schema from supported records, maps, lists, arrays, scalars, enums, and
  bounded optional properties. Applications cannot install a hand-authored
  schema, and Profile 1 is not universal JSON Schema Draft 2020-12 support.
- Replace old handler/context pairs with the operation-specific current
  contexts and registrations. Interceptors receive an explicit
  `McpRequestContext`, `McpInvocationFeatures`, and
  `McpHandlerContinuation`; the continuation only proceeds downstream.
- Replace request-admission/session policy with `McpAdmissionController`.
  Authentication, token verification, authorization rules, protected resource
  metadata, and identity-provider behavior remain application-owned.
- Replace `McpShutdownOutcome`-style reporting with aggregate
  `ShutdownResult`, `ShutdownComponentResult`, and
  `ShutdownComponentDisposition` evidence.

The [MCP quickstart](MCP_QUICKSTART.md) is a copy/paste starting point and
[MCP.md](MCP.md) is the full current API and wire reference.

## Annotation-processing migration

Enable `SokletProcessor` and `-parameters` for every module containing Soklet
annotations. Runtime handler-method classpath scanning is not a fallback.
Generated resources must survive shading and packaging.

Common annotation replacements include:

| 3.5.1 | 4.0.0 |
| --- | --- |
| `@McpArgument` | `@McpToolArgument` on a tool parameter |
| Hand-described record properties | `@McpToolProperty` on record components when metadata is needed |
| `@McpListResources` | `@McpResourceList` |
| `@McpEndpointPathParameter` / `@McpUriParameter` | `@McpResourceUriParameter` on a resource URI-template parameter |

The processor rejects unsupported or ambiguous method/record shapes at build
time. This can surface errors that 3.5.1 deferred until runtime.

## Request diagnostics and privacy

Framework-created diagnostics no longer embed request-controlled IDs, paths,
headers, cookies, query/form values, multipart fields, bodies, or malformed raw
URLs. Malformed URL failures no longer retain an input-bearing
`URISyntaxException` cause, and default annotation binding does not retain an
input-bearing conversion failure as a cause. If application code parsed
`Request.toString()`, exception messages, or cause chains, replace that with
typed `Request` and structured exception accessors and apply application-owned
redaction before logging.

## Final verification checklist

- A fresh clean compile succeeds with annotation processing enabled.
- No server builder uses a removed transport-specific shutdown-deadline setter.
- No application calls transport `start()`, `stop()`, `close()`, or
  `isStarted()`.
- Standalone and embedded lifecycle ownership are not mixed.
- Deployment termination grace is larger than the complete documented sum.
- Simulator configurations use only scope-vended transports.
- MCP clients use Streamable HTTP and the exact `2026-07-28` profile.
- Authentication and authorization failures reveal no token or protected
  resource value.
- A real localhost listener passes discovery, list, call/read/get as applicable,
  and clean shutdown/port-release smoke.
- Dashboards and alerts use the six current shutdown outcome labels.
