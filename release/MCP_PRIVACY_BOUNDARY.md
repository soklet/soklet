# MCP privacy boundary

This document defines the privacy boundary for Soklet's built-in MCP
observability and simulation surfaces. It is a description of what core Soklet
does and does not retain or emit by default; it is not a claim that an
application, telemetry backend, or operator environment is anonymized.

The machine-checked source inventory is
[`conformance/mcp-privacy-boundary-inventory.json`](../conformance/mcp-privacy-boundary-inventory.json).

## Soklet-owned diagnostics

Built-in MCP log records do not attach a `Request` or `Throwable`. Fixed
failure records describe the failing framework boundary without rendering the
exception message, stack, cause, request, response, or application context.
The dedicated MCP listener also wires its internal HTTP engine to its no-op
logger, so the engine's ordinary and failure logger call sites emit no record
for MCP traffic. The inventory derives both those call sites and the no-op
wiring from production source.
Likewise, diagnostic rendering for `Request`, `McpRequestId`, request
propagation, and the inventoried request-bearing MCP runtime/bridge input
carriers preserves useful shape while replacing request-controlled values with
redacted placeholders. Other exact-value records are classified separately in
the inventory. The exact values remain available through their documented
accessors. Framework-created request-validation exception messages follow the
same redacted-message rule on the inventoried MCP, `Request` accessor,
multipart-boundary, URL-parsing, and default annotation-binding paths. Public
exception constructors and structured accessors can still carry exact
caller-supplied messages, causes, names, or values and therefore form an
application-owned boundary.

Two disabled-by-default log options deliberately expose limited
request-derived text:

- trace correlation emits one bounded `MCP_TRACE_CORRELATION` record at the
  admitted request's finish authority. It can contain the fixed token-format
  identifier, a bounded configured key ID, a pseudonymous token and, only when
  separately enabled, the validated lowercase trace ID. It never includes the
  full `traceparent`, span ID, flags, `tracestate`, baggage, request, throwable,
  method, or response;
- unknown mirrored-header name diagnostics emit the registered endpoint path
  and a sanitized header name, never a header value or request. Names are
  ASCII-bounded to 128 bytes and emission is limited to ten attempts per server
  in a monotonic 60-second window.

Both values are still sensitive, high-cardinality operational data. Enabling
either option transfers responsibility for access, export, and retention to
the application and operator.

This MCP-specific behavior does not redefine Soklet's existing generic HTTP
and SSE diagnostics. Generic `LogEvent` values may retain an exact message,
`Request`, `Throwable`, `ResourceMethod`, or `MarshaledResponse` for an
application observer to inspect. The default `LifecycleObserver` writes the
message and, when present, the complete throwable stack trace to standard
error. Those ordinary HTTP/SSE surfaces are exact core/application/operator
boundaries, not redacted MCP telemetry. Dedicated MCP log records use the
restricted behavior above, although their bounded messages are still emitted
by the configured observer and therefore remain subject to operator retention.

## Built-in metrics

Core defines 23 sealed `McpMetricsEvent` variants and the default collector
aggregates all of them into 22 families. Framework-produced events use only
fieldless counts, registered endpoint paths, recognized methods (or the fixed
`<unrecognized>` value), fixed outcomes and termination reasons, fixed
protocol-error codes, fixed transport-failure reasons, and nonnegative
durations. The resulting dimension set is finite for one server configuration.

Built-in metrics do not carry a `Request`, `Throwable`, raw request ID, progress
token or value, mirrored-header name or value, trace ID or token, `tracestate`,
baggage, principal, network address, request state, operation/resource URI, or
an arbitrary label bag.

Public event factories remain available for application-authored events. They
validate value shape, but they do not turn an application-supplied string into
a core-controlled privacy or cardinality vocabulary. Applications that create
events manually, install a custom `MetricsCollector`, or forward events to
another telemetry system own the values they create and retain.

The pre-existing generic `MetricsCollector` API is separate from
`McpMetricsEvent`. Its ordinary HTTP/SSE callbacks deliberately receive exact
request targets, network addresses, `Request` and `Throwable` instances, and
SSE values. `DefaultMetricsCollector` aggregates those inputs, while a custom
collector can retain or export them and therefore owns its privacy policy.

## Application-owned exact values

Soklet deliberately passes exact request and context values to application
code where policy or business logic needs them. This includes admission and
rate limiting, lifecycle observation, interceptors, handlers, output
sanitizers, localization hooks, request-state protection, and related MCP
callbacks. Terminal lifecycle observation may also receive the exact ordered
`Throwable` instances produced while handling the request.

These callback values are application-owned. Soklet does not control whether
application code logs, transforms, exports, or retains them. Applications
should apply their own allowlisting, redaction, access control, and retention
policy before sending a request, context, exception, or application-authored
value to telemetry.

## Simulator fixtures

The off-network MCP simulator intentionally preserves exact captured response
headers, JSON/body bytes, SSE frames, and terminal `Throwable` identities,
subject to its configured capture bounds. This exactness makes the simulator a
useful test fixture; it is not a redacted telemetry surface. The caller owns
the captured values, their disclosure, and their lifetime.

The generic HTTP and SSE simulators follow the same fixture rule: their result
objects expose and render exact captured responses and failures. None of the
simulator result types is an operational telemetry boundary.

## Delegated operational boundaries

Core Soklet makes no privacy claim for:

- custom collectors, manually constructed metric events, application logs, or
  application telemetry;
- operator log/metric access, export, storage, deletion, and retention policy;
- downstream OpenTelemetry attributes, SDK processors/exporters, or backend
  series retention; or
- application or fixture code that retains an exact `Request`, context,
  response, captured byte sequence, or `Throwable`.

The downstream OpenTelemetry projection remains owned by the `soklet-otel`
release gate. Sustained default-collector/cardinality proof remains owned by
`release-soak`. Operational retention history continues as advisory
post-release monitoring, not a release prerequisite. This document does not
substitute for either candidate-bound gate result.

## Checked boundary and release status

[`McpPrivacyBoundaryTests`](../src/test/java/com/soklet/McpPrivacyBoundaryTests.java)
places secret canaries in public request, request-ID, propagation, and bridge
carriers while proving that diagnostic rendering is redacted and exact
accessor behavior is preserved.
[`McpPrivacyBoundaryInternalTests`](../src/test/java/com/soklet/internal/mcp/protocol/McpPrivacyBoundaryInternalTests.java)
provides the corresponding protocol-runtime canary. Existing log, metric,
lifecycle, simulation, wire-error, and fallback tests supply the remaining
per-boundary evidence named in the machine-checked inventory.

`SOK-PRIV-001` is `RELEASE_GATED` in the final MCP-C conformance-matrix
closure. The complete residual evidence and matrix were regenerated as one
atomic unit. Exactly `release-soak` and `soklet-otel` remain required, in
release-manifest order; this local closure does not claim their future
candidate-bound PASS evidence.
