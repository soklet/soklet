# Public API Naming Conventions

This document defines naming rules for Soklet public APIs, including factories, builders, properties, and callback parameters.
It exists to keep future naming decisions consistent and avoid repeated debate.

## Scope

- Applies to public API design and the explicitly approved 4.0 naming migration.
- Does not require blanket renaming of internal APIs, protocol fields, or established exceptions below.

## Rules

- **Builder entrypoints (no required inputs):** use `builder()`.
- **Builder entrypoints (required primary input):** use `withX(...)` and return a `Builder`.
- **Instance factories:** use `fromX(...)` and return a fully built instance (never a builder).
- **Builder convenience:** when a `withX(...)` builder is commonly used with only required inputs, add a `fromX(...)` convenience that calls `withX(...).build()`.
- **Shared singletons:** prefer names that include `Instance` (e.g., `defaultInstance()`, `disabledInstance()`), but this is not a hard requirement if readability benefits.
- **Defaults (fresh):** use `fromDefaults()` for a new instance configured with defaults.
- **Builder setters:** prefer property-name methods (`port(...)`, `requestHandlerQueueCapacity(...)`).
  The migrated MCP collection properties accept complete lists, snapshot them before assignment, and replace previous values.
  Optional collections accept `null` or an empty list to clear; required collections retain their nonempty invariants.
  Callers accumulating values should assemble a list and assign it once, not call a replacement setter per item.
- **Properties and parameters:** use the full domain name where it distinguishes the value, for example
  `streamingResponseBody`, `resourcePathDeclaration`, `requestContext`, and `invocationFeatures`.
- **Role types:** name their capability explicitly: `McpProtectionKeyringManager`,
  `McpTraceCorrelationKeyManager`, and invocation-scoped `McpTaskCreationContext`.
- **4.0 replacements:** removed MCP additive setters and renamed APIs do not retain compatibility aliases.
  `McpToolRegistration.toolAnnotations(...)` / `getToolAnnotations()` distinguish tool annotations
  from resource/content `annotations(...)` / `getAnnotations()`.
- **Avoid** `of*`, `create*`, `new*` for public APIs to keep the search surface uniform.
- **Renames:** prefer a deprecated alias for one release once an API is established, but do a clean hard rename early if adoption is still low and compatibility backfills would just preserve ambiguity.

## Retained exceptions

Owner-qualified names such as cookies, `StreamTermination.getReason()`,
`ResourcePathDeclaration.Component.getType()`, `ByteRangeSelection.getType()` / `getRange()`,
and body `getChannel()` / `getBuffer()` / `getWriter()` remain concise.
Metrics failure/rejection reasons and SSE event outcome/drop-reason accessors remain unchanged;
SSE comment-route metrics use `getEventEnqueueOutcome()` / `getEventDropReason()`.
Metric wire labels and protocol field names are not Java API names and remain stable.

Dedicated construction operations such as `McpJsonArray.Builder.add(...)`, and existing
resource-descriptor/resource-link `addIcon(...)` methods, are outside the collection migration.
Legacy resolver and endpoint-registry `with...` instance factories remain unchanged.
Servlet-mandated method names remain unchanged; only the response factory accepting
`HttpServletRequest` becomes `fromHttpServletRequest(...)`, while the Soklet
`Request` plus `ServletContext` overload remains `fromRequest(...)`.

## Examples

```java
// Builder entrypoints
HttpServer httpServer = HttpServer.withPort(8080).build();
MetricsCollector.Snapshot.Builder snapshot = MetricsCollector.Snapshot.builder();

// Instance factories
CorsAuthorizer cors = CorsAuthorizer.acceptAllInstance();
ResourcePathDeclaration decl = ResourcePathDeclaration.fromPath("/accounts/{id}");
ValueConverterRegistry registry = ValueConverterRegistry.fromBlankSlate();
SokletHttpServletRequest httpRequest = SokletHttpServletRequest.fromRequest(request);

// Defaults
MetricsCollector metrics = MetricsCollector.defaultInstance();
SokletServletContext context = SokletServletContext.fromDefaults();
```
