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

Task value diagnostics follow the same split. `McpTask`,
`McpTaskCreatedResult`, and `McpTaskOrigin` redact task identifiers,
application state, and result data from their diagnostic rendering while
retaining exact values behind their documented accessors and persistence
methods. `McpTaskNotFoundException` uses the same fixed message for an unknown
task and an unauthorized task and retains no task ID, authorization detail, or
application cause, so the exception does not disclose task existence.

The private task-notification delivery comparator retains only the exact
client-visible fields needed to distinguish delivered nonterminal updates,
never the private task origin, persisted arguments, full task, or completed
result. Its accessors and implicit record rendering are classified as exact
internal values, not redacted telemetry; built-in logs and metrics do not emit
the record. Variable-size messages, input requests, and metadata are retained
only after bounded notification encoding, for at most 256 task IDs per
subscription. Terminal delivery retains only a marker, and owner cleanup
releases comparison state even while an old worker remains referenced.

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

The shared HTTP/SSE streaming supervisor has a narrower framework-created
diagnostic: cleanup deadline evidence contains a numeric reservation ID, fixed
phase and execution-state vocabulary, and counts of outstanding work. Its
snapshot contains counts and an admission flag. Neither renders the retained
producer, request, application failure, or resource payload. A secret-seeding
test checks the diagnostic message, stack, cause/suppressed chain and snapshot
while application work and an exact failure remain retained. This property
applies to that framework-created evidence only: cleanup failures reported as
application Throwables and application callbacks still carry exact values.
Internal resource, consumer and delivery records likewise are not redacted
merely because they are private or short-lived.

## Built-in metrics

The default collector aggregates the sealed `McpMetricsEvent` hierarchy.
Framework-produced events use only fieldless counts, registered endpoint
paths, recognized methods (or the fixed `<unrecognized>` value), fixed outcomes,
termination reasons and subscription-maintenance work, fixed protocol-error
codes, fixed transport-failure reasons, and nonnegative durations. The
resulting dimension set is finite for one server configuration. The current
[metric reference](../MCP.md#default-metric-families) lists the exact families,
kinds and labels, including the shared HTTP/SSE/MCP transport-failure family.

Built-in metrics do not carry a `Request`, `Throwable`, raw request ID, progress
token or value, mirrored-header name or value, trace ID or token, `tracestate`,
baggage, principal, network address, request state, operation/resource URI, or
an arbitrary label bag.

Public event factories remain available for application-authored events. They
validate value shape, but they do not turn an application-supplied string into
a core-controlled privacy or cardinality vocabulary. Applications that create
events manually, install a custom `MetricsCollector`, or forward events to
another telemetry system own the values they create and retain.

Semantic delivery has a 4,096-record pending bound and can omit ordinary
records on overflow. Dimensioned default-collector maps have an 8,192-key
capacity with approximate LRU eviction; new dimensions can evict older
aggregates. These bounds do not constrain custom collectors or telemetry
backends, and observed aggregates are not an authoritative instantaneous
live-state view. The immutable server diagnostics remain available separately.

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

Intentional resource-not-found responses can include the requested resource
URI in `data.uri`, whether the handler returns a resource-not-found result or
throws the corresponding typed JSON-RPC error. Soklet selects the error code
for the configured protocol revision. That URI is deliberate client-visible
protocol output; the built-in log and metric projections do not render it.
Interceptor-authored errors, copies, wrappers and stale handler exceptions
still fail closed. Only the unchanged intentional handler exception from the
current invocation retains its client-visible error; normal result validation
also applies when an interceptor recovers.

The complete-tool-result sanitizer is a deliberate exception to retaining an
application failure object: Soklet discards a sanitizer's thrown exception,
including its message, cause, and suppressed exceptions, and reports only a
fixed framework failure. It also discards the original and partial result.
Only the returned complete result's payload and metadata proceed to output
validation and writing. The hook sees the current polling identity on each
authorized detailed completed-task read without changing the stored result.
Task-status metadata and progress are outside this hook. UI-directed result
metadata is not a secret channel and is never copied into model-visible text
as a fallback for non-Apps clients.

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

## Verification and release boundaries

The source verifier derives the current production and tracked fixture paths,
requires exactly one reviewed classification or precise exclusion for every
match, resolves each referenced Java test and optional method, and checks the
reviewed semantic-attribution seal. A removed or renamed declaration must be
reconciled explicitly. Conservative exception-construction and exact-carrier
classifications remain intentional even where an individual current message
is fixed. Broad scanner vocabulary also finds unrelated operations such as
byte-buffer reset and response copying; exact receiver-specific exclusions
do not weaken the scanner for future sites.

[`McpPrivacyBoundaryTests`](../src/test/java/com/soklet/McpPrivacyBoundaryTests.java)
places secret canaries in public request, request-ID, propagation, and bridge
carriers while proving that diagnostic rendering is redacted and exact
accessor behavior is preserved.
[`McpPrivacyBoundaryInternalTests`](../src/test/java/com/soklet/internal/mcp/protocol/McpPrivacyBoundaryInternalTests.java)
provides the corresponding protocol-runtime canary. Existing log, metric,
lifecycle, simulation, wire-error, and fallback tests supply the remaining
per-boundary evidence named in the machine-checked inventory.
[`McpTaskPublicApiTests`](../src/test/java/com/soklet/McpTaskPublicApiTests.java)
and
[`McpInMemoryTaskManagerTests`](../src/test/java/com/soklet/McpInMemoryTaskManagerTests.java)
provide the task-value redaction and non-disclosing authorization canaries.
Strict decoding, intentional resource errors, subscription maintenance,
writer-stall classification and bounded simulator capture have their own
current-source tests referenced in the same inventory. A source classification
and a passing local canary establish their checked boundary; they do not prove
every deployment or application telemetry policy.

`SOK-PRIV-001` remains `RELEASE_GATED`. Final `release-soak` and `soklet-otel`
qualification must use the exact final candidate commits and artifacts. A
current-source inventory check does not supply either gate's candidate-bound
PASS evidence or authorize publication.
