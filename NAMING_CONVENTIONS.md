# Naming Conventions (Factories and Builders)

This document defines naming rules for Soklet public APIs related to static factories and builder entrypoints.
It exists to keep future naming decisions consistent and avoid repeated debate.

## Scope

- Applies to public static factories and builder entrypoints only.
- Does not cover instance methods or internal APIs.

## Rules

- **Builder entrypoints (no required inputs):** use `builder()`.
- **Builder entrypoints (required primary input):** use `withX(...)` and return a `Builder`.
- **Instance factories:** use `fromX(...)` and return a fully built instance (never a builder).
- **Builder convenience:** when a `withX(...)` builder is commonly used with only required inputs, add a `fromX(...)` convenience that calls `withX(...).build()`.
- **Shared singletons:** prefer names that include `Instance` (e.g., `defaultInstance()`, `disabledInstance()`), but this is not a hard requirement if readability benefits.
- **Defaults (fresh):** use `fromDefaults()` for a new instance configured with defaults.
- **Builder setters:** use property-name methods (`port(...)`, `requestHandlerQueueCapacity(...)`) or verbs
  (`addX`, `clearX`, `enableX`, `disableX`).
- **Avoid** `of*`, `create*`, `new*` for public APIs to keep the search surface uniform.
- **Renames:** when changing a public name, keep a deprecated alias for one release (docs should use the new name immediately).

## Examples

```java
// Builder entrypoints
Server server = Server.withPort(8080).build();
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
