# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by emailing security@revetware.com.

Include the affected Soklet version, a concise description of the issue, and any reproduction steps or proof-of-concept details that can be shared safely. Please do not open a public GitHub issue for suspected vulnerabilities until we have coordinated disclosure.

You should receive an acknowledgment within 3 business days. We will work with you on a coordinated disclosure timeline appropriate to the severity of the issue.

## Supported Versions

Until 4.0.0 is published, 3.5.1 remains the latest supported release. On the
date 4.0.0 is published, the entire 3.x line reaches end of life and receives
no promised maintenance or security fixes. After publication, only the latest
4.x patch release is supported. Older 4.x patches, snapshots, and unreleased
source builds are unsupported.

| Release line | Status |
| --- | --- |
| Latest published 4.x patch | Supported after 4.0.0 publication |
| 3.5.1 / all 3.x | Supported only until 4.0.0 publication; EOL immediately afterward |
| Snapshots and unreleased source | Unsupported development artifacts |

See the [migration guide](MIGRATING_TO_4_0.md#supported-release-lines) for the
same user-facing policy.

## Scope

Soklet resolves no external runtime dependencies, but the released artifact
includes credited, repackaged third-party source. Security reports may concern
either Soklet-authored behavior or that embedded code. Reports against the HTTP,
SSE, and MCP transports—including request parsing, connection lifecycle, and
resource-limit enforcement—are especially appreciated. See the tracked
[third-party audit](release/THIRD_PARTY_AUDIT.md) and [`NOTICE`](NOTICE).

## Security Boundary and Non-Claims

Soklet supplies bounded parsing, validation, lifecycle, transport, and selected
cryptographic mechanisms inside the documented framework boundary. It does not
claim to secure an application or deployment end to end. In particular:

- Soklet does not implement an OAuth authorization server, access-token/JWT
  verifier, introspection client, dynamic client registration, protected
  resource metadata hosting, consent flow, identity-provider policy, or
  business authorization. See the worked
  [application-owned OAuth pattern](release/MCP_OAUTH_RESOURCE_SERVER.md).
- Lifecycle and transport attestation can validate evidence supplied through
  an honest custom implementation; Soklet cannot detect a custom transport or
  decorator that lies about its identity, delegation, termination, or resource
  ownership.
- MCP Tool Schema Profile 1 is a closed, bounded subset for Java-derived and
  explicitly authored tool-input schemas. It is not
  universal JSON Schema safety, semantic sensitive-data classification,
  protection against prompt injection, or validation of application business
  rules. JSON decoded-string and escaped-token limits are independent UTF-16
  bounds; aggregate transport bytes do not widen either. Schema work can exhaust
  its budget even for a semantically valid value within the JSON ceilings. See
  [JSON and schema limits](MCP.md#json-and-schema-limits) for accounting and the
  fixed, revision-specific rejection results. No submitted values or exception
  details are included in those errors.
- Public MCP text content, text resource contents, JSON strings/member names,
  prompt-output descriptions, and argument-completion values reject unpaired
  UTF-16 surrogates at construction without reflecting supplied text in the
  exception. Image/audio MIME syntax is validated without decoding or sniffing
  application bytes. Binary items retain the fixed 786,432-byte raw wire ceiling
  and the aggregate JSON response ceiling. See
  [JSON and content boundaries](MCP.md#json-and-schema-limits). Construction
  does not guarantee that a complete response fits; escaping application
  construction failures and output-limit failures remain private internal errors.
- Public MCP icon declarations require absolute sources with well-formed UTF-16,
  valid ASCII MIME syntax, and decimal `WxH` or `any` size hints. They are
  declaration checks, not trusted-source or rendering protections: Soklet does
  not fetch icons, restrict schemes/domains, validate image bytes or sanitize
  SVG. Incoming client metadata remains informational and untrusted under its
  existing wire checks. See [Icon declarations](MCP.md#icon-declarations).
- The built-in request-state and trace-correlation cryptography has frozen
  profiles, vectors, and implementation tests. It has not received an
  independent cryptographic audit, formal verification, or certification.
- Host, Origin, header, request, state, cursor, URI, filesystem, and proxy
  protections end at their documented boundary. Network topology, TLS,
  identity systems, key custody, logs, databases, downstream services,
  application handlers, custom code, and data retention remain deployment or
  application responsibilities.
- Conformance suites, simulators, goldens, fuzzing, soak runs, static analysis,
  and compatibility smoke are evidence for their stated cases. They are not a
  penetration test, a proof of absence of vulnerabilities, or protection
  against every scheduler, network, proxy, or hostile-input behavior.

The dated [security-claims audit](release/SECURITY_CLAIMS_AUDIT.md) records the
release wording that was deliberately accepted, rejected, or narrowed.

## MCP Deployment Security

See the [MCP privacy boundary](release/MCP_PRIVACY_BOUNDARY.md) for the exact
division between Soklet-owned redacted diagnostics and built-in metrics,
application callback values, simulator fixtures, and operator retention.
The 4.0.0 release pairing is `com.soklet:soklet:4.0.0` with
`com.soklet:soklet-otel:2.0.0`.

Soklet's MCP 2026-07-28 support runs on a dedicated `McpServer` listener. It is
not mounted on the ordinary HTTP or SSE listener. The MCP listener binds to
`127.0.0.1` by default; a container or remote deployment must opt into a
reachable bind host and provide appropriate network controls. Soklet does not
terminate TLS, so expose a non-loopback listener only behind suitable TLS
termination and access controls.

Host and Origin checks are independent. Soklet validates `Host`, including its
effective port. A loopback bind literal or `localhost` seeds the listener's
effective authority; every non-loopback bind must configure at least one
deployment hostname or IP literal with `McpServer.Builder.allowedHosts(...)`,
or server construction fails. A request without `Origin` is allowed by default,
unless `McpAbsentOriginPolicy.REQUIRE_ORIGIN` is configured. A request with
`Origin` is rejected unless the shared `CorsAuthorizer` explicitly authorizes
it; omitting an authorizer is reject-all for present origins. Do not treat
browser CORS response headers as a substitute for authentication or network
isolation.

Validation precedence is a security boundary. Transport limits and endpoint routing run first, followed by Host, Origin/CORS, POST/media negotiation,
strict JSON, and JSON-RPC envelope classification.
Requests then traverse mirrored-header form and method/name agreement; a read-only nested body-version probe and exact registry profile selection; then selected-profile required
metadata/extensions, universal-spine validation, and post-map header/body version agreement. Cheap wire structure precedes admission. Caller-neutral target/descriptor lookup, tool custom mirrored-header policy, request-state shape and required capabilities follow successful admission, before request/tool limiting,
bounded dispatch, interception, full input validation, handler execution, output processing in the exact order of preliminary result-shape recognition, applicable sanitization,
and remaining result/output-schema validation, and then writing. Notifications instead validate selector cardinality/form and registry membership before any selected-profile
present metadata, admission, and the optional request limiter after the common transport prefix, then terminate with an empty response; identifiable
`notifications/cancelled` skips parameter/present-metadata validation only.
Compound failures never move application callbacks ahead of their documented stage.

A readable post-JSON `initialize` method on a modern-only endpoint receives a
rejection diagnostic whose supported-version list names only `2026-07-28`.
On an endpoint explicitly declaring a compatible 2025 revision, the stateless
adapter accepts `initialize` without creating a session unless exact 2025
session revisions and server ownership/bounds are explicitly configured.
Session IDs are correlation handles, never credentials; every later use is
freshly admitted before owner/path/revision/generation verification. For modern requests,
a selector that has passed cardinality/plain-string validation and is absent
from the immutable 2026 profile registry is the only additive trigger for
other methods. Pre-JSON failures, unparseable JSON, unreadable methods,
and row-1 failures for other methods receive no selector-derived diagnostic.
Rejected header/metadata values or secret canaries are not reflected beyond the defined request-ID and unsupported-version `requested` fields.
Every MCP HTTP response family—including early parser errors, fixed empty/JSON/preflight responses, and SSE—carries exactly one `Cache-Control: no-store`.
An application-authored attempt to replace that header fails closed.

An MCP server without an explicitly configured `McpAdmissionController`
accepts requests and notifications anonymously and emits a startup diagnostic.
It does not fail closed merely because admission configuration was omitted.
Production applications that require authentication or authorization must
configure a controller, enforce those policies there, and return stable,
bounded rate-limit and authorization partition keys in the accepted
`McpAdmissionIdentity`. `McpAdmissionController.acceptAllInstance()` is the
default anonymous policy, not a production authentication mechanism.
Admission and rate-limit decisions are created only through the named sealed-
root factories (`accepted(...)`, `rejected(...)`, `allowed()`, and
`denied(...)`). Their nested final variants have private constructors and
remain public only for typed pattern matching; inspect them through
`Accepted.getIdentity()`, `Rejected.getRejection()`, and
`Denied.getRetryAfter()` rather than record-style component accessors.
Client information, client capabilities, request `_meta`, and advertised
server information are self-reported or informational metadata. Never use
them as authenticated identity or as an authorization or rate-limit partition
key.

Resource-subscription publishers emit coarse identity-free broadcasts.
Soklet matches those events against each accepted URI filter, but the stored
authorization partition only scopes registration, quota accounting, and stream isolation; it is not an event target or semantic URI-authorization check.
Admission receives the validated, deduplicated resource-subscription URIs via
`McpAdmissionContext.getRequestedResourceSubscriptionUris()`; it need not
reparse the bounded request body.
Authorize confidential or capability-bearing subscription URIs during admission, and do not treat an unguessable URI as a secrecy boundary.
A rejected admission activates no subscription even though the generation's shared publisher listener may already exist.
Accept-all anonymous callers on one endpoint share one empty authorization/quota partition,
so one caller can exhaust their common bucket.

Soklet validates response-header safety and transports application-owned
authentication decisions and challenges, including Bearer challenges with an
absolute `resource_metadata` URI and operation scopes. The typed
`BearerAuthenticationChallenge` validates and renders Bearer challenge syntax;
manually supplied header values receive response-header safety checks. Soklet
does not publish OAuth protected-resource metadata or choose an authorization
server. A deployment claiming MCP Authorization owns
the referenced metadata, authorization-server selection, scope semantics, and
RFC compliance, including RFC 9728 protected-resource metadata with at least
one authorization server; it must not require `offline_access` as a protected-
resource scope. Transporting a challenge does not by itself make core Soklet or
the deployment conformant with MCP Authorization.

`Forwarded` and `X-Forwarded-For` are also ordinary untrusted request headers;
they never alter `McpAdmissionIdentity` by themselves. If an application
deliberately derives an anonymous rate-limit partition from client IP, do so in
the admission controller with `EffectiveClientIpResolver` and an explicit
`EffectiveOriginResolver.TrustPolicy`. Use `TRUST_NONE` for direct traffic, or
`TRUST_PROXY_ALLOWLIST` with an exact allowlist covering every possible
physical socket peer and every trusted proxy-hop address expected in the
forwarding chain, never end-client addresses. When the physical peer is not
trusted, the resolver ignores the forwarding headers and uses the raw socket
peer when available. The trusted proxy or network edge must strip or overwrite
both `Forwarded` and `X-Forwarded-For`; usable `Forwarded: for=` values take
precedence. Do not use `TRUST_ALL` on a listener reachable by untrusted clients,
do not treat the allowlist as a replacement for network controls that prevent
proxy bypass, and never derive a partition from the request-controlled MCP
`clientInfo` returned by `McpAdmissionContext.getClientInfo()`.

Request-wide rate limiting is optional. A tool-bearing server must configure a
fallback tool limiter; named endpoint and tool overrides replace that fallback.
The built-in token bucket is bounded but local to one JVM. Multi-instance
deployments that require fleet-wide enforcement should supply their own
thread-safe `McpRateLimiter`, backed by a distributed service, and should fail
closed when that service is unavailable.

The built-in limiter partitions only on the admitted identity. A custom limiter
also receives the raw request through `McpRateLimitContext.getRequest()` and
must not treat its forwarding headers or self-reported MCP metadata as trusted
partition input unless application policy deliberately resolves them under the
same proxy boundary.

Localization contexts and catalog snapshots are node-local; they are not
authentication state or a distributed session. Every request reconstructs its
context from the request's bounded preferences and authenticated application
policy. For a rolling reload, build and validate the complete candidate off the
request path, atomically install it on one node, and only then call that node's
`invalidateCatalogs()` control; repeat explicitly for every applicable node and
expect temporary cross-node revision drift. If the deployment requires a
fleet-atomic cutover, stage and validate the candidate everywhere before any
node mutates, then use an application-owned coordinator, proxy, or traffic
switch to activate it. A failed candidate must produce neither a swap nor an
invalidation. After node loss, a client reconnects and repeats its credentials,
preferences, and any portable protected state; Soklet recovers no localization
session from the lost process.

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

Custom-list pagination cursors are opaque, application-owned strings. Soklet enforces type
and UTF-8 byte bounds but does not mint, decode, sign, encrypt, authorize, or
make cursors portable between instances. A custom resource-list handler owns
cursor integrity, expiry, authorization binding, backing snapshot semantics,
catalog revision and page-position binding, and fleet portability. Tampered,
expired, cross-principal, missing-snapshot, wrong-revision, and malformed
cursors should collapse to one neutral error without diagnostic data. Do not
put confidential data in a cursor unless the application protects it
appropriately.

Framework static catalog pagination on explicitly selected `2025-06-18` and
`2025-11-25` uses separate unsigned navigation cursors with a fixed 2,048-byte
ceiling, independent of the configured application-cursor limit. Every page
is freshly admitted and request-limited; tools and prompts also use current
catalog permissions. A cursor grants no access and carries no MAC or retained
session/snapshot. Endpoint, revision, catalog, negotiated-locale, malformed,
unknown, and caller-hidden-anchor mismatches collapse to neutral JSON-RPC
`-32602`. Listing does not authorize a subsequent call or resource read.
Modern static catalogs remain unpaged. Applications still own resource
authorization and all protection of custom-list cursors.

Tools, prompt gets, and resource reads may now perform multi-round-trip
`input_required` exchanges. The operation must declare every client request it
may emit. Required capabilities are checked after successful admission and before execution; conditional
capabilities are checked only when emitted, but still before output parameters,
metadata, request state, or a custom protector is processed. Client
`inputResponses` remain untrusted input and must be authorized and validated in
the handler even after Soklet validates their protocol shape.

In particular, applications must correlate each response key with the request
they emitted, distinguish missing, `accept`, `decline`, and `cancel` outcomes,
and validate accepted form content against the exact requested schema and
business policy before a side effect. Form elicitation must reject semantic
secret fields such as passwords, keys, tokens, and payment credentials rather
than assuming structural schema validation can classify them. URL-mode flows
should use a server-owned HTTPS destination with an opaque state handle bound
to the verified initiating user; do not put identity, credentials, userinfo, a
pre-authenticated bearer capability, query data, or fragments in the emitted
URL. Applications also own `toRealPath()`-based containment, symlink policy,
and authorization when handling filesystem paths supplied through ordinary
tool arguments or resource URIs. The public-API-only
[MCP input-security patterns](src/test/java/examples/mcp/McpInputSecurityApplicationPatternsTests.java)
exercise each of these fail-closed boundaries without claiming a universal
semantic classifier or a real downstream authorization deployment. The
[durable-handle and prompt-security patterns](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
add an application-owned durable repository boundary, exact admitted-context
binding, prompt business allowlisting, authorization-before-resource-access,
and neutral failures. The
[resource and cursor-security patterns](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java)
add canonical filesystem containment, delivery-intent URI allowlists, and
signed snapshot/revision/expiry-bound cursors. Their in-memory repositories and
fixed canaries are test doubles and deployment examples, not Soklet services
or universal security classifiers.
The
[localized cursor fleet pattern](src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java)
adds independently configured nodes with copied application keyrings and
retained snapshots. It proves exact cursor-byte preservation through provider
preselection and handler authentication, authorization binding as HMAC
associated data, locale/catalog/localization revision checks, exact expiry,
and one no-data `-32602` result for every exercised invalid classification.
The separately populated repositories model application replication; Soklet
still supplies no distributed cursor store, key distribution, replication,
or routing affinity.

MCP Tasks create a separate durable authorization boundary. A configured
[`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html)
must atomically authorize every lookup, input update, cancelation request, and
notification projection against the current admitted identity and endpoint.
Possession of a task ID is never authorization. Generate high-entropy,
non-enumerable IDs and make unknown and unauthorized tasks externally
indistinguishable through the fixed manager contract; never include task IDs,
operation names, or task contents in unbounded metric dimensions or default
logs.

Before returning
[`McpTaskCreatedResult`](https://javadoc.soklet.com/com/soklet/McpTaskCreatedResult.html),
application code must atomically persist its durable work description,
authorization binding, and the complete framework-supplied
[`McpTaskOrigin`](https://javadoc.soklet.com/com/soklet/McpTaskOrigin.html),
then publish work through an outbox, recovery scan, or equivalent mechanism.
The origin may contain validated arguments and must receive the same
confidentiality and integrity controls as the task. Do not log it, expose it to
clients, interpret or rewrite its opaque state, or retain the original request
context, handler, continuation, or request cancelation token as durable work.

A production worker path should assume at-least-once execution. Use
application-specific idempotency and deduplication for side effects and leases
with fencing where multiple nodes can claim work. `tasks/cancel` records
cooperative durable intent; it is not an interrupt and may lose a race with
completion or failure. Soklet shutdown likewise does not cancel durable tasks
or prove that application workers have stopped.

Workers must validate application output and input requests before storing
task state. Public task construction and in-memory worker mutations validate
status-specific shape, not the origin output schema or input declarations.
Soklet validates those contracts at delivery time. Invalid stored payloads
fail polling with a fixed internal error and suppress notification projection;
they do not change authoritative state, publish a transition, or notify the
worker. Current authorization, sanitization, or transport limits can also
prevent delivery. A failed read therefore does not prove that the work failed
and must not be used to infer a durable `FAILED` transition.

Task input delivery also checks the current caller's capabilities after the
authorized manager lookup. A polling client lacking an outstanding elicitation
mode receives HTTP 400 / `-32021` with the missing capability names only;
Soklet sends no task input payload or partial snapshot and does not change
durable state. Unknown and unauthorized task IDs remain indistinguishable
before capability checks. Capabilities from creation or earlier polls do not
authorize input delivery on a later request.

Task notifications are advisory. An application-provided
[`McpTaskEventPublisher`](https://javadoc.soklet.com/com/soklet/McpTaskEventPublisher.html)
publishes task IDs only after the corresponding state is durable and must use
broadcast semantics across eligible nodes. Soklet performs a fresh authorized
manager lookup for each live subscription before emitting a snapshot, but
events may be delayed, duplicated, or lost; `tasks/get` polling remains the
recovery authority. Treat event channels and task IDs as sensitive
application data.

The public
[`McpInMemoryTaskManager`](https://javadoc.soklet.com/com/soklet/McpInMemoryTaskManager.html)
is intentionally limited to development, tests, simulation, and deliberately
ephemeral single-process use. It has no durable storage, worker, outbox,
replication, leases, fencing, failover, or crash recovery, and all state is
lost at JVM shutdown. It must not be mistaken for a production task backend.

Request-state protection has two distinct trust boundaries:

- `APPLICATION_PROTECTED` is exact opaque-string pass-through. Soklet enforces
  nonempty/type and a 65,536-byte UTF-8 limit, but supplies no confidentiality,
  integrity, expiry, authorization binding, replay protection, or fleet
  portability. The application must provide every one of those properties it
  needs; do not place secrets in the value without application encryption.
  For durable continuation, store the state in an application-owned durable
  repository, expose only an unguessable handle, bind it to the admitted
  principal and authorization context, rotate it atomically as required, and
  require the current handle on every retry and new connection.
- `FRAMEWORK_PROTECTED` lets the handler supply JSON while Soklet owns canonical
  serialization, context binding, protection, lifetime, rounds, and immediate
  prior-request-ID freshness. A server with any such operation fails to build
  or start without `McpProtectionConfig`.

Production deployments should use
`McpProtectionConfig.withKeyring(...)` with operator-generated, purpose-specific
key material containing at least 256 bits of cryptographic entropy. Soklet's
built-in versioned envelope uses authenticated encryption, copies the initial
ring into server-owned state, redacts key material from public surfaces, and
supports live stage/activate/remove rotation through `McpProtectionKeyringManager`.
The initial `McpProtectionKeyring` exposes only its non-secret active and
verification key IDs; live inspection likewise returns only a secret-free
`McpProtectionKeyringSnapshot`.
For a fleet, stage the identical new key everywhere, compare secret-free
snapshots, activate it everywhere, wait at least the configured state lifetime
and for outstanding sealing reservations, then remove the former key.
The exact [built-in cryptographic profile](release/MCP_REQUEST_STATE_SECURITY_PROFILE.md)
and [production rotation runbook](release/MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md)
define the frozen labels, envelope, binding, vectors, publication boundary,
drain check, rollback, and emergency-revocation procedure. In particular, a
ring fingerprint proves complete configuration equality but does not prove
that sealing reservations have drained; removal is the authoritative drain
check and must be retried after `McpProtectionKeyInUseException`.

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
not. Registration-dependent state shape and size are checked after successful
admission and before required capability checks; structurally valid state is
opened only after authorization-partition resolution, preventing an
unauthenticated cryptographic validity oracle. Invalid, tampered, expired, or
wrong-bound state is a sanitized HTTP 400 / JSON-RPC `-32602`; temporary
protection unavailability is HTTP 503 / `-32603`. Invalid-state reports and
malformed, noncanonical, empty, or oversized custom-open plaintext collapse to
the same 400 / `-32602` response. Null or unexpected provider behavior and
invalid sealing/server output fail as HTTP 500 / `-32603`.

Canonical parameter binding uses the internal accepted-parameter profile,
with 32 MiB output headroom and unchanged production structural/scalar limits.
It does not impose the 4 MiB response limit on accepted request parameters.
MCP request-body acceptance remains 10 MiB by default, configurable up to
16 MiB; encoded/decoded state limits remain separate. The protected binding
contains the parameter digest rather than a copy of the operation arguments.

The first framework state starts the configured lifetime and round count.
Re-emission preserves that original expiry, increments the round, and records
the emitting request ID; the next retry must use a different ID. This is not a
single-use replay database. Workflows that require one-time approval or
consumption must store and enforce that fact in application infrastructure.
The last allowed round is valid for completion. Attempting another framework
emission at that round, or after expiry during a valid retry's handler, fails
at sealing after handler execution with a sanitized internal error. No new
state is returned and application side effects are not rolled back. The
public state accessor does not expose the framework's round or expiry; keep
application workflow counters/deadlines in the protected JSON when needed.
Input-required results have no protocol cache hints, and completed resource
retries are forced to private, zero-TTL cache policy; the HTTP transport remains
`Cache-Control: no-store`.

Progress reporting, cooperative cancelation, localization, and resource-
subscription delivery are implemented. Every selected MCP application handler
receives one framework token whose cancellation category is a fixed
`StreamTerminationReason`; the framework supplies no underlying cause through
`CancelationToken.getCancelationCause()` or
`StreamingResponseCanceledException`. An application may retain that fixed
category under its own policy, but must not substitute attacker-controlled
free-form text or make a cancellation detail a metric dimension. Incoming HTTP
`notifications/cancelled` remains a compatibility no-op on modern and
session-disabled views after admission and request limiting. Within a verified
2025 session it may target matching active client work if its terminal
reservation wins, using only the neutral `CLIENT_CANCELED` token reason.
Deadline, shutdown, and response-stream failure also drive cooperative
cancellation. Unknown/completed targets remain indistinguishable. The exact
`McpProgressAndCancelationRuntimeTests#every_cancelation_category_is_bounded_observable_and_carries_no_framework_cause`
gate iterates every non-`COMPLETED` category and proves the fixed reason, empty
cause, and bounded exception message.

On explicitly selected 2025 revisions, progress uses the originating POST's
bounded SSE stream and one complete sanitized terminal result. After commitment,
client disconnect or lost-writer write failure detaches that writer, wakes
blocked reporters, and discards later output without itself canceling
application work. Deadlines and physical
worker reservations remain in force; finite/uncommitted or queued legacy calls
and modern calls retain disconnect cancellation. Stateless tokens stay
POST-local; enabled 2025 sessions suppress reporters for active token collisions
without rejecting calls. Session IDs, owner keys, remembered metadata, and
free-form cancellation text stay out of built-in logs/metric labels. Snapshots
and request evidence have independent count/byte/lifetime accounting, including
residual callback references after logical retirement; opaque application graphs
remain application responsibility. Anonymous allocation is disabled by default
and has a separate namespace/sub-budget when explicitly enabled. Only the
framework may publish a session ID; policy-authored session/replay response
headers remain forbidden. There are no event IDs, replay/history, or lost-result
recovery. Optional 2025 GET/DELETE admission receives the original HTTP request
and must reconstruct current permission and stable ownership. Acceptances need
an explicit expiry and only offered families; DELETE requires none. GET lease
renewal uses the original credentials and cannot extend its fixed total lifetime
or revive a fenced stream. Shared partition quotas and retained request evidence
outlive logical replacement until physical cleanup. Legacy URI subscribe requests
require fresh admission plus independent readable-route and notification permission
checks. Session-owned grants retain the real subscribe credentials/context through
physical callback exit; duplicate subscribe replaces evidence without resetting
total lifetime, and unsubscribe/reconciliation fence late renewals. A fresh GET
never grants a URI implicitly. Queued notifications check current GET/grant
generations and expiry before every socket write; revoked unwritten frames are
purged, while a partially written revoked frame closes before further bytes.
Coalesced dirty bits survive GET gaps without retaining event history. Grant counts,
URI bytes, queued encoded bytes, and maintenance demand have independent caps;
opaque application graphs remain application responsibility. Bounded Last-Event-ID input is
allowed in preflight only where legacy GET is configured and is ignored.
Generic HTTP observers receive exact Request/Throwable values under application
retention policy; built-in metric labels contain neither session nor owner IDs.
Applications must retain idempotency controls when retrying after
delivery loss; disconnect does not prove that side effects did not occur.

Trace correlation is default-off. With a configured trace-correlation key,
Soklet attempts one bounded `MCP_TRACE_CORRELATION` log record at the admitted
request's exactly-once finish authority; a separate
`logRawValidatedTraceIds(true)` opt-in may add only the validated lowercase MCP
trace ID. The event never carries the full `traceparent`, parent/span ID, trace
flags, `tracestate`, baggage, request, throwable, method, or marshaled response,
and trace values never become built-in metric dimensions. The pseudonymous
token and any opted-in raw ID are still sensitive, high-cardinality correlation
data. Restrict log access and retention, and do not treat validation or
pseudonymization as authentication or anonymization.

### Telemetry and diagnostic privacy

Framework-produced MCP metric dimensions are limited to registered endpoint
paths, recognized methods or `<unrecognized>`, fixed enums and fixed protocol
error codes. No dedicated dimension contains operation names, resource URIs,
session IDs or owners, request IDs, principals, header identity, arguments,
results, trace context, correlation tokens, keys or throwables. Unknown
mirrored-header metrics count occurrences by endpoint and method, including
when the header is ignored; header-name diagnostics are a separate default-off
opt-in. Accepted progress/keep-alive delivery and cooperative cancelation
signals do not prove client receipt or physical handler termination.

Built-in semantic metric delivery retains at most 4,096 pending records.
Overflow can omit ordinary records; listener start/stop records can displace
an ordinary record. The default collector's dimensioned maps have an
8,192-key capacity with approximate LRU eviction; new dimensions can evict
older aggregates. These bounds do not cap an application collector, an
OpenTelemetry SDK or its backend, nor do they make loss-affected aggregates
an authoritative live-state view. Collector callbacks must be nonblocking.

Public events and snapshot builders are application value carriers. A
manually supplied endpoint, method or protocol code can contain sensitive
text or create new series. Generic HTTP callbacks, including configured 2025
GET/DELETE paths, and custom telemetry remain application-owned. Application
error codes and tool-result `isError` values do not enter the built-in fixed
protocol-error metric family. Labeled maps are sparse, reset clears
cumulative values while preserving live gauges, and retained snapshots are
immutable. Neither histogram sampling nor multiple instruments provide a
shared transaction; large `Double` duration sums can lose integer precision.

`McpServer.getDiagnostics()` remains available with metrics disabled. Status,
address, configured bounds and live counts form a runtime-owned atomic tuple;
protection and trace configuration form a separately owned atomic tuple.
There is no global linearization claim across those tuples. The bound address
is retained after shutdown, and residual handlers remain counted until their
physical exit. An immutable shutdown result does not prove that residual work
has stopped or change when that work later exits.

Diagnostic fingerprints expose configuration equality but no raw keys, key
IDs, per-key tags or correlation tokens. Production-keyring fingerprints use
encoding `v2` to compare exact raw bytes; compare version, profile and value
together. Earlier `v1` fingerprints could conflate distinct keys and different
versions are incomparable during rollout. Trace fingerprints are independent
of request-state protection mode. Rotation changes new snapshots only.
Fingerprints are operational comparison values, not authentication inputs,
and should not be per-request labels. High-entropy keys remain required.

The built-in MCP observer/collector/transport failure log events omit request,
response and throwable attachments and use fixed messages. Exact context,
application errors and throwables supplied to lifecycle callbacks remain
sensitive application-owned objects. Aggregate owner-transition failures,
generic HTTP/SSE callbacks and application-created `LogEvent` instances do
not inherit that MCP redaction boundary. Configure an explicit policy for
logging, exporting and retaining them.

With the OpenTelemetry observer installed, admitted MCP request spans use
only validated MCP metadata as the remote parent. Physical HTTP trace headers,
ambient context and baggage are not fallback parents. Built-in span projection
does not export JSON-RPC request IDs, error messages/data or lifecycle
throwables as attributes, descriptions or exception events. Physical client
address and Soklet-request-ID attributes are separate default-off opt-ins.
Custom naming receives the full context; applications own its safety. The
OpenTelemetry SDK and backend own sampling, retention, flushing and export.

Off-network MCP simulation runs the real processor and policy path with bounded
capture, without a listening socket. Captured request/response bodies, error
data, SSE messages and exact observed throwables are deliberate test values,
not sanitized production diagnostics. Keep sensitive fixtures and recordings
under application retention policy; simulator parity does not establish
kernel/network behavior or deployment security.

Task request metrics cover the protocol/manager-call boundary. Instrument
durable repositories, leases, queues and worker transitions in application
infrastructure, with bounded dimensions that exclude task IDs and contents.
Trace-correlation tokens and separately opted-in raw IDs remain sensitive
log data under the access/retention policy described above. See
[MCP lifecycle and metrics](MCP.md#lifecycle-and-metrics) for the exact metric
and diagnostic contracts, and the
[privacy boundary](release/MCP_PRIVACY_BOUNDARY.md) for channel ownership.

### Protocol scope and unsupported features

SEP-2577 marks Roots, Sampling, and Logging deprecated in MCP `2026-07-28`.
Soklet does not implement these features or expose Java APIs for requesting
them. Applications should pass files or directories through explicit tool
parameters, resource URIs, or server configuration, integrate directly with a
model provider, and use application logging and Soklet's observability APIs.
Deprecated peer capability and log-level metadata may still be structurally
validated without enabling corresponding server behavior. Elicitation and
the shared multi-round-trip request machinery remain supported. No
negotiation-triggered warning is emitted.
