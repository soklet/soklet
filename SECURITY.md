# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by emailing security@revetware.com.

Include the affected Soklet version, a concise description of the issue, and any reproduction steps or proof-of-concept details that can be shared safely. Please do not open a public GitHub issue for suspected vulnerabilities until we have coordinated disclosure.

You should receive an acknowledgment within 3 business days. We will work with you on a coordinated disclosure timeline appropriate to the severity of the issue.

## Supported Versions

Security fixes are prioritized for the latest released version of `com.soklet:soklet`. Snapshot builds are development artifacts and may change without notice.

## Scope

Soklet has zero runtime dependencies, so vulnerabilities in the released artifact are limited to first-party code. Reports against the embedded HTTP, SSE, and MCP transports — including request parsing, connection lifecycle, and resource-limit enforcement — are especially appreciated.

## MCP Deployment Security

Soklet's MCP 2026-07-28 support runs on a dedicated `McpServer` listener. It is
not mounted on the ordinary HTTP or SSE listener. The MCP listener binds to
`127.0.0.1` by default; a container or remote deployment must opt into a
reachable bind host and provide appropriate network controls. Soklet does not
terminate TLS, so expose a non-loopback listener only behind suitable TLS
termination and access controls.

Host and Origin checks are independent. Soklet validates `Host`, including its
effective port, and `McpServer.Builder.allowedHosts(...)` adds deployment-
specific hostnames or IP literals. A request without `Origin` is allowed by
default, unless `McpAbsentOriginPolicy.REQUIRE_ORIGIN` is configured. A request
with `Origin` is rejected unless the shared `CorsAuthorizer` explicitly
authorizes it; omitting an authorizer is reject-all for present origins. Do not
treat browser CORS response headers as a substitute for authentication or
network isolation.

Every MCP server requires an explicit `McpRequestAdmissionPolicy`. Production
applications should authenticate and authorize there and return stable,
bounded rate-limit and authorization partition keys in the accepted
`McpAdmissionIdentity`. `McpRequestAdmissionPolicy.acceptAllInstance()` is an
explicit anonymous policy, not a production authentication mechanism.
Client information, client capabilities, request `_meta`, and advertised
server information are self-reported or informational metadata. Never use
them as authenticated identity or as an authorization or rate-limit partition
key.

Request-wide rate limiting is optional. A tool-bearing server must configure a
fallback tool limiter; named endpoint and tool overrides replace that fallback.
The built-in token bucket is bounded but local to one JVM. Multi-instance
deployments that require fleet-wide enforcement should supply their own
thread-safe `McpRateLimiter`, backed by a distributed service, and should fail
closed when that service is unavailable.

`@McpHeader` deliberately requires a registered `Mcp-Param-*` request-header
value to agree with the corresponding property already parsed from the JSON
tool arguments. It never supplies an absent or null argument from a header.
Treat both values as untrusted application input and avoid placing secrets in
mirrored headers unless every intermediary and application log is configured
accordingly. Unregistered mirrored headers are ignored and never trusted by
default; strict request rejection is available through
`McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS`. Optional name-bearing
diagnostics are disabled by default and can still disclose received header
names to application-owned logging and retention systems, although Soklet
never includes their values.

Pagination cursors are opaque, application-owned strings. Soklet enforces type
and UTF-8 byte bounds but does not mint, decode, sign, encrypt, authorize, or
make cursors portable between instances. A custom resource-list handler owns
cursor integrity, expiry, authorization binding, backing snapshot semantics,
and fleet portability. Do not put confidential data in a cursor unless the
application protects it appropriately.

Tools, prompt gets, and resource reads may now perform multi-round-trip
`input_required` exchanges. The operation must declare every client request it
may emit. Required capabilities are checked before admission; conditional
capabilities are checked only when emitted, but still before output parameters,
metadata, request state, or a custom protector is processed. Client
`inputResponses` remain untrusted input and must be authorized and validated in
the handler even after Soklet validates their protocol shape.

Request-state protection has two distinct trust boundaries:

- `APPLICATION_PROTECTED` is exact opaque-string pass-through. Soklet enforces
  nonempty/type and a 65,536-byte UTF-8 limit, but supplies no confidentiality,
  integrity, expiry, authorization binding, replay protection, or fleet
  portability. The application must provide every one of those properties it
  needs; do not place secrets in the value without application encryption.
- `FRAMEWORK_PROTECTED` lets the handler supply JSON while Soklet owns canonical
  serialization, context binding, protection, lifetime, rounds, and immediate
  prior-request-ID freshness. A server with any such operation fails to build
  or start without `McpProtectionConfig`.

Production deployments should use
`McpProtectionConfig.withKeyRing(...)` with operator-generated, purpose-specific
key material containing at least 256 bits of cryptographic entropy. Soklet's
built-in versioned envelope uses authenticated encryption, copies the initial
ring into server-owned state, redacts key material from public surfaces, and
supports live stage/activate/remove rotation through `McpProtectionControl`.
For a fleet, stage the identical new key everywhere, compare secret-free
snapshots, activate it everywhere, wait at least the configured state lifetime
and for outstanding sealing reservations, then remove the former key.

`withDevelopmentEphemeralProtection()` is an explicit development convenience.
Its state is process-local and becomes unreadable after restart or on another
instance; the startup diagnostic is intentional. Never use it when a client
may retry through a different process. A thread-safe
`McpRequestStateProtector` is the alternative for application-owned or
distributed protection. It must authenticate the exact associated-data bytes
from `McpRequestStateProtectionContext`, return fresh plaintext arrays, avoid
retaining call-confined plaintext, and collapse all invalid/tampered/context-
mismatched input into `INVALID_STATE`. Report only temporary provider outages
as `PROTECTOR_UNAVAILABLE`; do not expose backend diagnostics in the checked
exception.

Framework state is bound to endpoint path, protocol version, JSON-RPC method,
the admitted authorization partition, and stable validated parameters. Retry-
only fields and transient progress/trace/baggage metadata are excluded from the
parameter digest; application operation arguments and identity partition are
not. Wire shape and size are checked before capability/admission side effects,
but structurally valid state is opened only after admission, preventing an
unauthenticated cryptographic validity oracle. Invalid, tampered, expired, or
wrong-bound state is a sanitized HTTP 400 / JSON-RPC `-32602`; temporary
protection unavailability is HTTP 503 / `-32603`. Invalid-state reports and
malformed, noncanonical, empty, or oversized custom-open plaintext collapse to
the same 400 / `-32602` response. Null or unexpected provider behavior and
invalid sealing/server output fail as HTTP 500 / `-32603`.

The first framework state starts the configured lifetime and round count.
Re-emission preserves that original expiry, increments the round, and records
the emitting request ID; the next retry must use a different ID. This is not a
single-use replay database. Workflows that require one-time approval or
consumption must store and enforce that fact in application infrastructure.
Input-required results have no protocol cache hints, and completed resource
retries are forced to private, zero-TTL cache policy; the HTTP transport remains
`Cache-Control: no-store`.

Progress reporting, cooperative cancelation, and resource-subscription delivery
are implemented. Five bounded Phase 6 verticals are also implemented: shutdown
observation, handler-capacity metrics, handler diagnostics, live
stream/subscription diagnostics, and protection/trace diagnostics. Shutdown
metrics have only the fixed
`McpShutdownOutcome`-derived
`clean`/`residual_handlers` label. The exact handler-capacity families—
`soklet_mcp_handler_executions_active`, `soklet_mcp_handler_queue_depth`, and
`soklet_mcp_handler_capacity_rejections_total`—are label-free. They contain
only server-wide counts and no endpoint, method, request, principal, URI,
header, trace, baggage, state, or application-controlled value.

The public aggregate represents these nonnegative counts with boxed `Long`
snapshot getters and builder methods. Reset preserves the live active-handler
and queue-depth gauges while clearing cumulative queue-full rejections. A
residual handler remains visible as active after bounded shutdown until it
actually exits; queued deadline, disconnect, cancelation, and shutdown removal
are dequeues, not capacity rejections. These semantics prevent reset or late
exit from manufacturing a negative gauge.

`McpServerDiagnostics` now declares exactly 12 zero-argument methods:
`getStatus()` and `getBoundAddress()`, plus all ten implemented diagnostic
getters. Six are boxed `@NonNull Integer` values from
`getRequestHandlerConcurrency()`, `getRequestHandlerQueueCapacity()`,
`getActiveHandlerExecutions()`, `getQueuedRequests()`,
`getActiveRequestStreams()`, and `getActiveSubscriptions()`. The remaining four
are `getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyRingFingerprint()`, and
`getTraceCorrelationConfigurationFingerprint()`; both fingerprint getters
return non-null `Optional` containers with non-null payload types.

The numeric fields cover configured handler bounds, occupied handler slots,
physically queued requests, open request-scoped SSE streams, and their
resource-subscription subset. They are server-wide counts with no endpoint,
method, identity, request, header, URI, trace, state, or application-controlled
label or text. A subscription enters both stream counts once its acknowledgment
stream opens; the values do not claim client receipt, and
`0 <= activeSubscriptions <= activeRequestStreams`.

Lifecycle status, bound address, configured bounds, handler counts, and the
paired stream/subscription counts are captured atomically as one runtime tuple.
Protection and trace fields are captured atomically as a separate security-
controls tuple. Both enter one immutable result, but no invariant joins them at
one global linearization point. Retained snapshots do not change. Ordinary,
subscription-only, and combined open states produce `1/0`, `1/1`, and `2/1`.
Disconnect cleanup moves `2/1` to `1/0` and then `0/0`. Completed clean and
residual-handler stops both expose stream pair `0/0`, even while a residual
handler remains active until actual exit. During internal `FAILED` cleanup,
public residual status may transiently retain `1/1`; completed cleanup exposes
`STOPPED` with `0/0`. These diagnostics do not expose queue contents, rejection
causes, stream contents, or subscription filters.

The two live-stream fields add no metric family, event type, label, or other
observation dimension, and collector reset cannot alter them.

The protection mode and custom-protector flag are fixed at server construction
and stable across listener stop/restart. The flag is `true` exactly in
`CUSTOM_PROTECTOR` mode and reports the custom application-owned
`McpRequestStateProtector` SPI. It does not report whether any operation selects
`APPLICATION_PROTECTED`; that application-owned opaque mode needs no framework
protector and bypasses one even when configured.

The protection-ring fingerprint is present exactly for
`PRODUCTION_KEY_RING`. It is absent for no framework keys, custom protection,
and development-ephemeral protection. The trace-configuration fingerprint is
independent of protection mode and present exactly when trace correlation was
enabled at construction. Successful live ring/key rotations appear in fresh
snapshots, survive listener lifecycle transitions, and do not change retained
snapshots.

Both fingerprints are deterministic operational deployment-comparison
metadata, not authentication, authorization, or token-derivation inputs. The
diagnostics contain no raw key material, key IDs, per-key fingerprint tags,
custom-provider identity, request-state cursors or epochs, or trace-correlation
tokens. They do not compensate for low-entropy key material: equality remains
observable, and rotation can create high-cardinality values. Operators should
therefore provision high-entropy keys, keep fingerprints out of metric labels,
and avoid per-request logging or unbounded retention of them.

Handler transitions and `McpMetricsEvent.ServerStopped` alone use the shared
deferred FIFO, which delivers callbacks after dispatcher, request, runtime,
server, and Soklet lifecycle locks are released and contains collector
failures. This fifth diagnostics vertical adds no metric family, event type,
wire field, label, or other observation dimension. Phase 6 remains provisional
and unfrozen. The remaining telemetry/event hierarchy, broader operational
trace-correlation and redaction work, MCP simulation, sustained/fuzz gates,
CI/provenance and release-candidate work, and Phase 6 API review/freeze remain
open.
