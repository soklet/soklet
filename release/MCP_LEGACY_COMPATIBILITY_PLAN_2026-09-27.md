# MCP compatibility layer plan — 2026-09-27

**Status:** API design approved on 2026-09-28. The first synchronous-tools
increment is implemented in committed source. Local and cloud development
host checks are recorded in the [client compatibility matrix](MCP_CLIENT_COMPATIBILITY.md).
The bounded official 2025 tool subset passed a separate development run from
the pushed commit; the broader layer and exact-candidate qualification remain
open. This file does not record a release-candidate PASS; only completed and
tested adapters may be advertised.

## Development implementation checkpoint — 2026-09-28

- Required exact revision declarations now cover endpoints, core operations,
  Skills, Apps metadata, and endpoint Task/subscription gates. The annotated
  and programmatic paths both reject empty, mismatched, or unsupported sets.
- One endpoint URL now serves `2026-07-28` and, when explicitly selected,
  `2025-06-18` or `2025-11-25` initialization and synchronous
  `tools/list`/`tools/call`. The 2025 path reuses the application admission,
  catalog access, request/tool limits, handler, and output-sanitization path.
  `V2025_03_26` is a named enum value but cannot be selected for an endpoint
  until its different wire behavior is implemented.
- The local API freeze, public-evolution and roadmap checks, Javadoc build,
  and full Java 17/21/25 suites passed during implementation. Those are
  development results. Real-host tool smoke subsequently passed for the exact
  client versions recorded in the compatibility matrix. Five pinned official
  2025 scenarios per selected revision also passed in a separate development
  check; this is not full 2025 conformance or an exact-candidate gate.
- Prompts, resources, Skills, Apps UI, Tasks, subscriptions, and 2025-03-26
  batching remain outside the first 2025 adapter. Their version declarations
  fail preflight rather than exposing an incomplete operation.

## Goal

Let applications built with Soklet 4.0 serve useful MCP operations to clients
that still use the 2025-era `initialize` protocol, while retaining the current
`2026-07-28` endpoint and the same application registrations, admission policy,
authorization, and handlers. A developer should configure one application
operation once and explicitly declare each served protocol revision at one
stable MCP URL.
The result should be described by **exact protocol revision and tested host**,
not by the broad claim “supports legacy MCP.”

The first implementation increment is synchronous tools. The planned complete
compatibility surface also evaluates ordinary prompts and resources. Skills,
Apps, Tasks, subscriptions, server-initiated requests, multi-round input,
long-lived SSE, and the deprecated `2024-11-05` HTTP+SSE transport are outside
the first tools-only claim. This does not set the first shipped compatibility
claim: if a target host needs Apps, add and qualify its UI resource reads and
negotiated capability handling before claiming that host. A host requiring an
unimplemented feature remains marked unsupported.

## Proposed shape

```text
  /catalog/mcp ── explicit version-aware dispatcher ─┬→ 2026-07-28 runtime
                                                    └→ 2025-era HTTP/wire adapter
                            both use shared plans, policies, and handlers
```

- Make one stable URL the default. Its endpoint declaration names one or more
  **exact MCP protocol revisions**. The 2025 adapter is enabled only when a
  2025 revision is declared; no `legacy` or `latest` mode selects revisions on
  the application's behalf. Generated and manual registrations should work
  identically; an application should not copy handlers or define another
  endpoint class solely to serve 2025 clients. Additional URLs, if supported,
  are explicit deployments and must be configured in clients independently.
- Keep separate transport decoders and response encoders behind a strict
  request classifier on that URL. The modern runtime
  requires `Mcp-Method`, sometimes `Mcp-Name`, and per-request `_meta`; the
  older runtime gets its operation from JSON-RPC and begins with `initialize`.
  Merely adding 2025 versions to the modern profile registry would not bridge
  this difference. The modern runtime remains 2026-only internally; a modern
  `server/discover` response at the shared URL reports its declared supported
  revisions and capabilities for the responding revision. A legacy
  `initialize` request at that same URL offers only implemented 2025
  revisions. Reject ambiguous framing, conflicting version signals, and any
  request that tries to cross protocol eras mid-call.
- Reuse the existing execution plans built by `DefaultMcpServer` and
  `McpServerRuntimeBridge`. Route the legacy request through the same
  caller-aware catalog policy, admission controller, request and tool limits,
  interceptor, input decoder, handler, output sanitizer, and error policy. Do
  not invoke registration handlers through a shortcut that bypasses them.
- Use the explicit versioned public API proposed below for endpoint paths and
  eligible operations. Check its builder style, generated endpoints, simulation,
  and freeze snapshots before implementation. Keep protocol parsing and wire
  DTOs internal.

The current seams are [`McpEndpoint`](../src/main/java/com/soklet/McpEndpoint.java),
[`McpServer`](../src/main/java/com/soklet/McpServer.java),
[`DefaultMcpServer`](../src/main/java/com/soklet/DefaultMcpServer.java), and
[`McpServerRuntimeBridge`](../src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java).
The current HTTP contract is recorded in [`MCP.md`](../MCP.md#http-and-error-policy).

## Proposed public API and version selection

Require `protocolVersions()` on `@McpServerEndpoint`. One annotation on one
endpoint **class** can declare both 2026 and 2025 revisions at the same path.
Repeatable endpoint annotations remain a possible way to publish additional,
separately configured URLs, but are not required to build the compatibility
layer and should not split the two wire eras by default. Require the same
member on every annotation that declares an independently callable or
advertised core MCP operation: `@McpTool`, `@McpPrompt`, `@McpResource`,
`@McpResourceList`, `@McpPromptCompletion`, and `@McpResourceCompletion`.
Replace the earlier proposed `@McpCompatibility` annotation and
`McpTool.compatibilityIncluded` flag with these exact version lists. The
existing `McpServerEndpoint.version()` remains the server implementation
version reported to clients; it is not an MCP protocol revision.

```java
@McpServerEndpoint(
    path = "/catalog/mcp", name = "catalog", version = "1.0.0",
    protocolVersions = {
        McpProtocolVersion.V2026_07_28,
        McpProtocolVersion.V2025_06_18,
        McpProtocolVersion.V2025_11_25
    })
public final class CatalogMcpEndpoint {
    @McpTool(
        name = "catalog.search",
        protocolVersions = {
            McpProtocolVersion.V2026_07_28,
            McpProtocolVersion.V2025_06_18,
            McpProtocolVersion.V2025_11_25
        })
    public String search() {
        return "example result";
    }
}
```

`McpProtocolVersion` is a proposed public enum of exact wire revisions. Every
`protocolVersions()` member has **no default**: each endpoint and core
operation must name at least one revision, including a modern-only
application. Java requires the member to be written; the processor rejects an
empty array. There is no `LATEST`, inferred current revision, or automatic
expansion when Soklet adds another revision. Adding support later requires a
deliberate edit to the endpoint binding and each affected operation. This is
an intentional pre-release source change; migrate existing annotated code,
examples, and tests as part of the implementation.

The proposed complete-layer enum constants are `V2025_03_26`,
`V2025_06_18`, `V2025_11_25`, and `V2026_07_28`. A selectable constant
ships only when its adapter and tests are ready. `getWireValue()` returns the
exact date token.
The required `McpProtocolVersion[] protocolVersions();` annotation member is
added to `@McpTool`, `@McpPrompt`, `@McpResource`, `@McpResourceList`,
`@McpPromptCompletion`, and `@McpResourceCompletion`, as well as
`@McpServerEndpoint`. The processor rejects empty or duplicate sets and checks
operation and completion subsets against their owners.

The version selected for a request determines which operations appear in their
catalogs and can be invoked. An operation can support a subset of the revisions
at its endpoint. A completion declaration must select a subset of its owning
prompt's or resource template's revisions. Reject duplicate or empty version
lists, operation revisions not exposed by that endpoint, and declared
combinations that cannot be served faithfully. If repeatable endpoint
annotations are added for additional URLs, validate normalized path
uniqueness and define whether overlapping revision sets are allowed. The
current annotation processor and registry assume one endpoint per class, so
repeatability needs a separate design and implementation review.

Programmatic `McpEndpoint`, tool, prompt, resource, custom resource-list, and
completion registrations must have equivalent required, nonempty exact version
sets and no implicit default. Review their builder signatures alongside the
annotation API. The first adapter milestone still serves only tools for 2025
revisions; reject a 2025 version declaration on a prompt, resource, list, or
completion handler until that operation's adapter and tests exist. Do not
silently broaden them to 2025 simply because the endpoint also serves 2025.
Argument and schema objects inherit their owning operation's version
eligibility; they do not need separate version fields. Apps tool metadata is
an exception: it may be present for only a subset of a tool's revisions, so
its association needs its own explicit version set (see below). A server
hosting several endpoint bindings has no single server-wide protocol version
to configure.

The proposed programmatic signatures put each required version set in its
registration's starting factory, avoiding a builder that can be completed
without naming a revision. Existing shorter factories are replaced, not kept
with a modern-only default. Optional feature sets and handler-specific subsets
are then supplied on the owning builder:

```java
McpEndpoint.Builder McpEndpoint.withPath(
    String path, McpImplementation implementation,
    Set<McpProtocolVersion> protocolVersions);
McpToolRegistration.ArgumentTypeStage McpToolRegistration.withName(
    String name, Set<McpProtocolVersion> protocolVersions);
McpPromptRegistration.HandlerStage McpPromptRegistration.withName(
    String name, Set<McpProtocolVersion> protocolVersions);
McpResourceRegistration.ExactHandlerStage McpResourceRegistration.withUriAndName(
    URI uri, String name, Set<McpProtocolVersion> protocolVersions);
McpResourceRegistration.TemplateHandlerStage McpResourceRegistration.withUriTemplateAndName(
    String uriTemplate, String name, Set<McpProtocolVersion> protocolVersions);
McpSkillRegistration.Builder McpSkillRegistration.withUriAndSkillBundle(
    URI uri, McpSkillBundle skillBundle,
    Set<McpProtocolVersion> protocolVersions);

McpEndpoint.Builder taskProtocolVersions(Set<McpProtocolVersion> protocolVersions);
McpEndpoint.Builder subscriptionProtocolVersions(Set<McpProtocolVersion> protocolVersions);
McpEndpoint.Builder resourceListHandler(
    @Nullable McpResourceListHandler resourceListHandler,
    Set<McpProtocolVersion> protocolVersions);
McpEndpoint.Builder skillListHandler(
    @Nullable McpSkillListHandler skillListHandler,
    Set<McpProtocolVersion> protocolVersions);
McpPromptRegistration.Builder completionHandler(
    McpCompletionHandler completionHandler,
    Set<McpProtocolVersion> protocolVersions);
McpResourceRegistration.TemplateBuilder completionHandler(
    McpCompletionHandler completionHandler,
    Set<McpProtocolVersion> protocolVersions);
```

These are design signatures, not current code; final declarations retain
Soklet's nullness annotations. The two optional list-handler setters accept a
null handler with an empty set to restore automatic listing. A nonnull custom
list handler must cover every revision where the endpoint advertises that
list, so falling back to an unfiltered static list on another revision is not
implicit. The two completion bindings may select subsets of their owning
prompt or resource-template versions. Each custom handler receives a request
context with the selected revision and can branch there; different handlers
for the same endpoint and revision are not silently combined.
`McpSkillGroup` derives version eligibility from its registrations;
`McpSubscriptionConfig` does not duplicate the endpoint's subscription gate.

Apply the same explicit rule to the two endpoint features already present in
Soklet. Each `McpSkillRegistration` and a configured `skills/list` handler
binding must have a required, nonempty core `protocolVersions` set. A Skills
group derives eligibility from its member registrations; a bundle and its
generated file reads inherit the owning registration's eligibility. A custom list handler
must never widen the eligible revisions or return a registration outside the
selected revision. Subscriptions are selected once at the endpoint, through
`subscriptionProtocolVersions()`; `McpSubscriptionConfig` supplies an
application event source but does not carry a second, possibly conflicting
version list. The server's authorizer is invoked only for eligible
revisions. For 4.0, validate that Skills and the subscription facility select
only `V2026_07_28`. A later revision requires its own reviewed mapping and
tests before it can be declared. The Skills extension's own specification
version is separate from this core protocol revision; its stable specification
currently targets base MCP `2026-07-28` or later ([Skills specification](https://skills.extensions.modelcontextprotocol.io/specification/stable/skills)).

Generated-endpoint registry overlays for Skills and subscriptions currently
identify only the endpoint class. The shared URL must filter those features by
selected revision. If repeatable endpoint declarations later add multiple
paths, overlays must also select the intended path explicitly. Review the
exact builder and overlay signatures during the public API freeze.

## Client URL contract

An MCP client starts with a URL supplied by its user, administrator, or host
configuration. It does not discover sibling MCP URLs through the protocol.
`server/discover` reports supported versions and capabilities **at the URL
already in use**, not replacement URLs. A 2025 client sends `initialize` to
that URL when establishing a new MCP session and selects a supported 2025
revision. A 2026 client may call `server/discover` there or send a versioned
request directly;
there is no 2026 protocol session or required discovery on each reconnect.
Clients may cache discovery and era-detection results. The 2026 specification
expressly permits both eras on one endpoint and recommends caching HTTP-era
detection for an origin, making separate 2025-only and 2026-only paths on one
origin a poor default ([versioning](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning),
[discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover),
[2025 lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)).

Soklet dispatches a request at the configured URL to the matching wire adapter
and exposes only the operations declared for its actual revision. It does not
choose a different URL, add undeclared revisions, or silently use the latest
revision. If an application deliberately publishes additional URLs, its users
must configure the appropriate URL in each host and qualify that host against
it. The modern and legacy classifiers must be explicit and tested together on
the same URL, including dual-era client probes and malformed or conflicting
headers/body metadata.

## Exact revisions and wire behavior

Start by capturing the protocol revision actually negotiated by each target
host. The intended 2025-era target set is `2025-03-26`, `2025-06-18`, and
`2025-11-25`, implemented and claimed individually. Build the common
`2025-06-18`/`2025-11-25` adapter first; add the `2025-03-26` differences before
claiming that revision. Do not label a build compatible with an untested
revision just because its methods look similar.

| Concern | `2025-06-18` / `2025-11-25` | `2025-03-26` |
| --- | --- | --- |
| Startup | `initialize` result with selected version, server information, and only implemented capabilities; accept `notifications/initialized` | Same lifecycle |
| HTTP request | One JSON-RPC message per POST; version header on later requests | Older batch POSTs must be bounded and handled if this revision is claimed; no required version header |
| Simple response | JSON response to POST; notification returns empty 202; GET may return 405 | Same basic approach, with version-specific batch responses |
| Tool schema/result | Can represent `outputSchema` and `structuredContent`, with `content` fallback | Project results into supported `content`; omit unsupported descriptor/result fields and content kinds |

The server may omit `MCP-Session-Id`, so this design does not recreate the
3.5.1 session store. Accept `ping` and lifecycle notifications as required by
the selected revision. Return 405 for GET and DELETE; do not advertise change
notifications or SSE delivery. A well-formed `initialize` request naming an
unsupported version receives a normal initialization result offering a
supported version; malformed initialization parameters fail, and an invalid
or unsupported `MCP-Protocol-Version` header on a later request returns HTTP
400. On a stateless later request with no version header, handle the 2025
specification's `2025-03-26` fallback deliberately; never silently interpret
it as `2025-11-25`.

Sources: [2025-03-26 transport](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports),
[2025-06-18 transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports),
[2025-11-25 lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle),
[2025-11-25 transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports),
[2025-era tool formats](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), and
the [official TypeScript SDK's dual-era HTTP guidance](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/migration/support-2026-07-28.md).

## Eligible operations and request context

1. **Tools:** expose only registrations explicitly declaring the negotiated
   older revision and whose inputs and results can be represented in that
   revision. Exclude task-required tools and tools whose successful path needs
   a client capability unavailable after initialization, Apps-only/UI
   resources, multi-round input, and tools dependent on modern mirrored headers
   or request state. Fail at startup for an explicitly selected incompatible
   registration rather than quietly dropping it. For a dynamically branching
   handler, validate the actual result and return a safe, typed failure if it
   leaves the supported subset.
2. **Catalog consistency:** `tools/list` must return only operations both
   declared for the negotiated revision and visible to the admitted caller.
   `tools/call` must apply the same eligibility and fresh authorization; a
   hidden tool must not be callable by guessing its name. Preserve the current
   per-tool rate limit and result sanitizer. Project descriptors and outputs for
   the negotiated exact revision, including text fallback for structured output
   where required. Validate content kinds as well: an unsupported resource link,
   annotation, or other modern content needs an explicit faithful conversion
   or a safe failure, never silent omission.
3. **Prompts and resources:** after the tools path is solid, map ordinary
   `prompts/list`, `prompts/get`, `resources/list`,
   `resources/templates/list`, and `resources/read` where the older revision's
   schema can represent their results. Apply the existing prompt catalog
   policy and per-request admission; resource authorization remains with the
   application's resource handlers. Decide how a caller-aware resource list
   should be filtered before exposing resource discovery. Apply the same
   eligibility review to prompt/resource input requests, request state,
   pagination, custom list handlers, metadata, and explicit version
   declarations. Exclude subscriptions, Apps UI resources, Skills-specific
   behavior, and other modern-only extensions. If a particular operation
   cannot be mapped faithfully or safely authorized, omit its capability and
   do not advertise it.
4. **Context:** 2025 clients supply `clientInfo` and capabilities during
   `initialize`, not on every request. Without a session identifier, later
   requests cannot be linked safely to that declaration. The adapter must
   present **unknown/empty client capabilities** and absent client information
   to application handlers on later calls, while supplying the actual selected
   legacy protocol revision. Do not treat initialization metadata as identity.
   Document this meaning for `McpRequestContext`; revise the design if an
   essential use case truly needs retained negotiated capabilities.
5. **Cancellation:** a stateless HTTP request cannot reliably correlate a
   separate `notifications/cancelled` message to an in-flight call across
   clients that reuse JSON-RPC IDs. Define and test the limited behavior
   explicitly; enforce request deadlines, but do not treat an HTTP/SSE
   disconnect as a cancellation signal in the 2025 protocol. Long-running
   operations requiring reliable cross-request cancellation need a separate
   session-aware design and are not eligible for this first layer.

## Cross-cutting version gates

### One view per exact revision

At construction, build one immutable operation/capability view for each
`(endpoint path, McpProtocolVersion)` pair. The view contains the eligible
tools, prompts, resources, completions, Skills, Tasks, and subscriptions,
including wire projections for that revision. Construction fails if a
declared combination cannot be represented. The selected view is the common
source for capability claims, catalog responses, call routing, and final
result validation; never use the all-versions endpoint registry as a callable
catalog. Keep operation names unique across an endpoint even when declarations
have disjoint revision sets, at least for this first layer.

`server/discover` lists every implemented revision declared at that URL and
returns the capability object for the revision on the discover request. This
is Soklet's rule for the spec's single capability object; the spec does not
provide one capability object per `supportedVersions` entry. A 2025
`initialize` result instead reports the selected 2025 view. The corresponding
`tools/list`, `prompts/list`, `resources/list`, and calls must agree with those
claims. A custom resource-list handler receives descriptors filtered to the
selected revision, and Soklet validates its output before sending it. The
same principle applies to a custom Skills list handler. Extend
[`McpServerCapabilityRegistry`](../src/main/java/com/soklet/internal/mcp/protocol/McpServerCapabilityRegistry.java)
and the endpoint plans around these views.

### Optional endpoint facilities

The proposed annotation surface adds these members to
`@McpServerEndpoint`, alongside its required `protocolVersions()`:

```java
McpProtocolVersion[] taskProtocolVersions() default {};
McpProtocolVersion[] subscriptionProtocolVersions() default {};
```

An empty optional feature set means **disabled**, not “current” or “all.”
Each nonempty set must be a subset of the endpoint's required core revisions.
Programmatic endpoint construction gets equivalent typed sets. For 4.0 these
two optional facilities accept only `V2026_07_28`; adding a future core or
extension revision requires an explicit new mapping and tests.

`McpServer.Builder.taskManager(...)` remains the application-owned manager
dependency, not an instruction to advertise Tasks on every endpoint. A task
facility is available only where `taskProtocolVersions()` includes the
selected revision **and** a manager is configured. Gate the Tasks capability,
`tasks/get`, `tasks/update`, `tasks/cancel`, task notifications, and task
results together. Statically task-only tools must be declared only for task-
enabled revisions; dynamic task results are checked at runtime. Persist the
originating endpoint and exact revision with each task and check both on later
task operations. The experimental [2025-11-25 Tasks](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)
design is a different wire contract from the
[2026 Tasks extension](https://tasks.extensions.modelcontextprotocol.io/specification/draft/tasks)
and is not enabled by this gate.

`subscriptionProtocolVersions()` is the only version switch for the effective
`subscriptions/listen` facility. It covers an explicit `McpSubscriptionConfig`,
a task manager's event publisher, and localization invalidations. The sources
contribute only event families available in that selected view; merely
installing a task manager or localizer must not widen the listener's revisions.
Fail construction when an explicit subscription configuration has no enabled
subscription revision, or an enabled listener has no event source. A server
authorizer still makes the final permission decision for each eligible
listener. The 2025
[`resources/subscribe` and GET SSE mechanisms](https://modelcontextprotocol.io/specification/2025-11-25/server/resources)
are separate features and remain outside this first compatibility claim.

### Apps association and extension versions

`@McpAppTool` and programmatic `McpAppToolMetadata` get a required, nonempty
`protocolVersions` set. It must be a subset of the owning tool's revisions.
The proposed `@McpAppTool` member is
`McpProtocolVersion[] protocolVersions();`. Programmatic metadata starts with
`McpAppToolMetadata.withProtocolVersions(Set<McpProtocolVersion>)` and exposes
`getProtocolVersions()`. The existing no-argument metadata builder is removed
so it cannot imply an unspoken revision.
Where Apps metadata is emitted, an exact Apps MIME `ui://` resource must be
registered at the same endpoint and revision; the eventual read still needs
authorization for the requesting caller. This
lets one tool be a plain 2025 tool and an Apps-enhanced 2026 tool without
duplicating its handler. For the initial 2025 adapter, reject Apps metadata
that selects a 2025 revision; this is a release-scope limit, not a claim that
the [Apps extension](https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
cannot work on 2025 MCP. Reject a tool declared for 2025 if its Apps
association marks it app-only but excludes 2025: removing the metadata would
turn a helper into a model-visible tool. The application can narrow that
tool's core version set or register a separate, explicitly safe fallback. A
model-visible tool may use a text-only fallback where its result is faithfully
representable.

The 2026 Apps projection also depends on the **requesting client's** Apps
capability. A client without it receives a plain model-visible fallback or no
app-only tool. This caller/capability-dependent catalog must have matching
call-time authorization and cache scope. Core `McpProtocolVersion` values do
not stand for extension specification versions. Pin the implemented Apps,
Skills, and Tasks extension editions separately in documentation and evidence;
do not silently change an edition under an unchanged public configuration.
An explicit public extension-version selector is needed if Soklet later serves
multiple editions of one extension.

Raw `_meta.ui` fields must not bypass the typed association's version set.
Require a typed, versioned Apps tool association before accepting additional
raw `ui` members for that tool; reject a raw `ui` object without one. Strip the
entire `ui` namespace from projections where Apps is unavailable, and reject
an app-only tool rather than turning it into a plain model-visible tool.
Resource-content Apps metadata is a result value, not a registration: validate
or remove its `ui` fields against the selected resource revision and client
capability at serialization. The first tools-only 2025 adapter must reject an
Apps UI resource result instead of emitting an unusable partial response.

### Context, state, cache, and simulation

Change `McpRequestContext.getProtocolVersion()`,
`McpAdmissionContext.getProtocolVersion()`,
`McpRateLimitContext.getProtocolVersion()` (new), and the public
`McpRequestStateProtectionContext.getProtocolVersion()` to return the proposed
`McpProtocolVersion` enum. Its `getWireValue()` returns the exact protocol
token where an application needs text; the protection-context factory should
take the enum too, while its associated-data bytes remain canonical.
For a 2025 `initialize` that offers an unsupported revision, select the
supported response revision before creating these contexts; their getter
means **selected served revision**, not the client's offered token. Only
successfully classified, supported revisions reach application contexts.
Keep the actual request URL available from the request object.
The public `getEndpoint()` remains endpoint configuration, not a filtered
catalog; version-filtered descriptors belong in the relevant list contexts.
2025 later requests without retained session state have unknown/empty client
capabilities, even if an earlier `initialize` declared them. Handlers,
authorizers, policies, and rate limiters inherit the selected request fact;
they do not need separate configured version lists.

Partition any server-side catalog/result cache by endpoint, exact revision,
request arguments, admitted caller, and relevant client capabilities. Avoid
public caching when the projection can vary by caller or capability; use
conservative TTLs and invalidate only the affected views. Request state and
task-origin checks must retain their revision so a later call cannot cross
wire profiles. Public simulation uses a real `Request` at the configured URL
with the appropriate handshake or per-request metadata and headers; it does
not get a simulation-only version override. Verify both eras at one URL and
cross-version cache isolation with golden wire tests.

## Security and operational invariants

- Authenticate and authorize **every** legacy request, including catalog calls
  and each invocation. A previously visible tool can become forbidden before
  its call. Retain accurate 401 challenges and terminal 403 denials; do not
  convert a plain denial into an OAuth challenge.
- Retain Host and Origin validation, CORS, request/header/body limits, handler
  concurrency bounds, timeouts, `Cache-Control: no-store`, safe error output,
  and controlled shutdown. Legacy POSTs and any 2025-03-26 batches must not
  bypass per-message admission or rate limits. Do not create unbounded maps
  keyed by client-supplied request IDs.
- Do not issue or depend on session IDs or replay cursors for the stateless
  subset. Preserve the modern adapter's current session/replay-header policy and
  review the source inventory that intentionally forbids old session state.
- Record the selected wire adapter and supported revision in bounded
  diagnostics. Keep client-provided tool names, IDs, and claimed client
  identity out of unbounded metric labels.

## Build sequence and evidence

1. **Host/version inventory.** Probe named clients with pinned builds and
   capture their HTTP exchange, requested and negotiated revision, method
   sequence, response handling, authentication mode, and failure. Keep Claude
   Code distinct from Claude's cloud-hosted custom connector; keep VS Code
   distinct from Microsoft Copilot Studio. This selects the exact revisions
   and operations that must ship.
2. **Contract review.** Freeze the single-URL dual-era contract, required
   exact version sets on endpoint, core operation, Skills, and Apps metadata,
   optional endpoint task/subscription version gates, programmatic parity,
   optional multiple-URL behavior,
   operation eligibility, context semantics, and the tools-only first
   milestone. Review the Java API changes and source migration against Soklet
   naming and signature checks before implementing them. Add explicit
   `McpOperationType` cases for the known `initialize`,
   `notifications/initialized`, and `ping` methods; keep `OTHER` for unknown
   methods. Specify notification observation and rate limiting per semantic
   message, including each member of a 2025-03-26 batch.
3. **Transport and wire adapter.** Add strict era dispatch at the declared URL,
   then version-specific initialize, initialized, ping, POST framing, response
   projection, and errors behind the 2025 adapter. Implement 2025-03-26
   batching and result projection before
   enabling that revision: bound mixed request/notification arrays, preserve
   one response per request ID, omit responses to accepted notifications, and
   handle notification-only or unsupported response-only batches according to
   the transport rules. Keep the 2026 adapter's parsing and diagnostics unchanged.
4. **Shared application execution.** Feed eligible older operations into the
   existing plans and policy pipeline. Add caller-filtered catalogs and
   call-time authorization. Then add ordinary prompts/resources if their
   exact-revision mapping and security behavior pass review.
5. **Automated verification.** Use golden wire transcripts and real-socket
   tests for every claimed revision: startup, version mismatch, list/call,
   errors, empty/denied catalogs, revocation between list and call, actual
   request-path identity in admission and observation, 03 batches, malformed
   payloads, concurrent callers, no session or GET stream, shutdown, modern
   nonregression, and both eras on the same URL. Include dual-era client probes,
   cache/reconnect behavior, version-specific catalogs, and strict rejection of
   malformed mixed-era requests. Run schema checks, Java 17/21/25 CI, and the
   applicable pinned official `2025-11-25` conformance requirements without
   converting unsupported optional features into false passes. Fuzz the new
   parser/batch boundary with bounded inputs.
6. **Real-host qualification.** Exercise at least one local and one cloud
   client end to end, then expand to the hosts that matter for the release:
   Claude Code, Claude custom connectors, ChatGPT, VS Code/Copilot, Gemini CLI,
   and Microsoft Copilot Studio. For each, record the exact build, negotiated
   revision, tool list, one real call, denial and (if configured) OAuth
   recovery, disconnect, and outcome. A product's advertised “Streamable
   HTTP” support alone is not evidence of a particular revision. Use each
   host's own connection guide: [Claude Code](https://code.claude.com/docs/en/mcp),
   [Claude custom connectors](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp),
   [ChatGPT](https://developers.openai.com/plugins/deploy/connect-chatgpt),
   [VS Code](https://code.visualstudio.com/docs/agents/reference/mcp-configuration),
   [Gemini CLI](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md),
   and [Copilot Studio](https://learn.microsoft.com/en-us/microsoft-copilot-studio/mcp-add-existing-server-to-agent).
7. **Release integration.** If this ships in 4.0.0, update the public MCP and
   migration material, compatibility matrix, examples, API freeze snapshots,
   profile/readiness assertions, and affected release checks. Migrate all
   existing annotation and manual-registration call sites to required exact
   revisions; update the API-compatibility baseline deliberately. In particular,
   review [`McpLegacySessionNegativeInventoryTests`](../src/test/java/com/soklet/McpLegacySessionNegativeInventoryTests.java),
   the [modern profile evidence index](../conformance/official/protocol-profile-evidence.json),
   and the roadmap, transport-dependency, and release-matrix verifiers without
   changing their 2026-default claim by accident. Re-run the
   immutable candidate gates against the owner's exact final commit and update
   downstream pins as required. Keep the 2026 profile as the primary release claim and
   distinguish compatibility evidence from the existing 2026 conformance
   claim. No publication decision follows from this plan alone.

## Completion criteria

The layer is ready to claim only when every enabled revision has tested wire
behavior, an explicitly bounded operation set, the modern endpoint's prior
contract still passes, and at least one local host and one cloud host complete
discovery and an authorized tool call against the candidate artifact. Give
each additional target, including Microsoft Copilot Studio, its own recorded
PASS, FAIL, or NOT TESTED result. Claim “compatible with host X” only for a
pinned host version that passed; an unverified host or feature remains
unclaimed. The first tools-only milestone can be useful without pretending to
reproduce Soklet 3.5.1 or all of MCP.
