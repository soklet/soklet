# MCP 2025 compatibility expansion: implementation and review plan

**Status:** implementation plan with owner-agreed release scope, public API shape and naming; no expansion code is implemented by this document. Engineering verification and qualification remain outstanding.

**Date:** 2026-10-01.

**Source baseline:** Soklet core commit `bf9046f5384c367dfa2ddee2105f2f1ed3e4c8d1`.

**Exact target revisions:** `2025-06-18` and `2025-11-25`.

**Modern revision to preserve:** `2026-07-28`.

**Revision:** updated after the [external review](MCP_LEGACY_EXPANSION_PLAN_REVIEW_2026-10-01.md). Source checks confirmed important defects in the first draft. This remains a plan, not an implemented or qualified feature set.

**Release placement:** the full expansion targets **4.0.0**, including completion, POST progress, static pagination, sessions/remembered metadata/cancellation, and GET/DELETE with resource subscriptions and catalog-change delivery. The owner accepted this direction on 2026-10-01 after reviewing the external feedback, session benefits and public API footprint. This records scope; implementation, qualification and publication approval are separate.

This is a new expansion proposal. The [original compatibility plan](MCP_LEGACY_COMPATIBILITY_PLAN_2026-09-27.md) records earlier implementation and qualification checkpoints. Its historical statements are not rewritten here.

## 1. Goal and boundaries

Make Soklet's two implemented 2025 adapters more useful for ordinary MCP clients without changing how applications implement tools, prompts, resources, completion, progress, or cooperative cancellation. Keep wire adaptation, session tracking, connection management, and notification delivery inside Soklet.

Treat the 2025 adapters as a production interoperability path that may remain important for an extended period. The 4.0 feature set must be useful with released clients independently of an unknown modern-spec adoption date. All five feature groups below are part of the agreed release target. Sessions remain explicitly enabled by applications; implementing them does not make every legacy endpoint stateful.

The proposed sequence is:

1. Prompt and resource-template argument completion.
2. Progress notifications over the originating POST response's SSE stream.
3. Pagination of framework-produced static catalogs.
4. Optional sessions, remembered client metadata, and request cancellation.
5. GET SSE delivery, resource subscriptions, and catalog-change notifications.

**Tool results remain complete results.** A handler constructs one final result, and Soklet sends one final JSON-RPC response. SSE can carry progress before that response; it does not turn tool text, rows, or structured content into incremental result fragments. Transport writes may divide bytes, but there is no application-visible result-chunk API.

The session slice introduces two top-level types; the delivery slices add three more. The complete proposal has five new top-level types, eight public owners including nested builders and decision variants, five members on baseline existing owners, and five enum constants. Existing handler signatures remain unchanged. Implement and verify this surface in stages; each new public member must have a working implementation before inclusion in the release freeze. Development inventory maintenance and a release API freeze are separate steps.

### Intended compatibility claim

| Facility | Baseline: both 2025 revisions | After this plan |
| --- | --- | --- |
| Initialization and ping | Implemented, stateless | Preserved; optional session lifecycle |
| Tools and ordinary prompts | Implemented | Preserved |
| Ordinary resource reads and templates | Implemented within current URI-template profile | Preserved |
| Complete tool results and supported content | Implemented | Preserved; complete final result in JSON or POST SSE |
| Authored input schemas | Supported within Soklet's existing schema profile | Same profile; no new general schema interpreter |
| Prompt/template completion | Not enabled for 2025 | Existing completers enabled by exact revision |
| Progress | Not enabled for 2025 | Existing reporter on POST SSE when a valid progress token is supplied |
| Static tools/prompts/resources/template pagination | One page; cursor rejected | Framework pages and opaque continuation cursors |
| Custom resource-list pagination | Implemented, application-owned cursors | Preserved, not wrapped in framework pagination |
| Remembered client info/capabilities | Not retained between requests | Immutable initialization snapshot in enabled sessions |
| `notifications/cancelled` | Accepted but ignored | Cooperatively cancels the matching active request in an enabled session |
| GET / DELETE | `405` | Explicitly admitted GET streams / session termination in the separately qualified delivery package |
| Resource subscriptions and list-change delivery | Not enabled for 2025 | Session-owned resource grants and GET notification delivery |

Neither revision is described as a 100% implementation after this expansion. Existing revision differences, including June/November icon handling, remain explicit. The baseline's schema and URI-template limits remain in force.

### Review disposition and decisions reopened

The following corrections are accepted: per-revision completion plans; lazy SSE and explicit cancellation bytes; a legacy HTTP status table; pagination without a whole localized-view digest; publication before exposing a session ID; session CORS in the session slice; explicit anonymous ownership; paced/coalesced notification maintenance; measurable evidence and migration work.

Some review recommendations need narrower conclusions:

- Optional sessions introduce failure modes in deployments that enable them. They do not change existing stateless defaults. A promise that sessions can never fail where stateless succeeds is incompatible with bounded node-local correlation and revocation. Do not silently downgrade an issued/required session or a requested subscription.
- Client capabilities describe client advertisements, not server support. A bounded informational snapshot is legitimate; it must not enable excluded wire features. Execution contexts can carry it separately from message-local metadata.
- A global budget may be smaller than the sum of local ceilings. Define fair allocation and exhaustion, rather than treating every ceiling as reserved capacity.
- Source observations about SDK recovery and unreleased VS Code are qualification leads, not live-host PASS/FAIL evidence. No inspected-source claim is promoted to a product guarantee.
- The review's blanket assertion that no inspected client sends DELETE on close is incorrect: [Python SDK v1.30.0](https://github.com/modelcontextprotocol/python-sdk/blob/v1.30.0/src/mcp/client/streamable_http.py) defaults `terminate_on_close` to true. [TypeScript v1.29.0](https://github.com/modelcontextprotocol/typescript-sdk/blob/v1.29.0/src/client/streamableHttp.ts) separates `close()` from explicit `terminateSession()`. Capacity/recovery still need testing in actual hosts.
- The review's preference for 4.1 and demand-gating later facilities was a release/scope recommendation, not a protocol requirement. The owner selected the full expansion for 4.0.0; real consumer evidence remains necessary to qualify the resulting claims.

This document records the superseding release/design direction. Implementation slice 0 must amend the governing artifacts for the selected facilities before the corresponding runtime/API changes:

| Earlier decision | Selected revision and reason | Artifacts to amend |
| --- | --- | --- |
| No legacy session store/remembered metadata | Opt-in correlation for real cancellation, negotiated revision lookup, and delivery | Predecessor context/session clauses; NI-03/DF-03; pinned completion/planning authority |
| GET/DELETE `405`; subscription revisions mean modern `subscriptions/listen` | Explicit legacy delivery at selected revisions | Endpoint/processor contracts; transport baselines; website support matrix |
| No framework cursor codec | Static legacy catalog paging beyond response limits; custom resource cursors stay application-owned | SOK-NA-008 and affected cursor documentation/inventories |
| No session names/state in the API and transport scans | Precisely owned legacy modules and public carriers, with modern exclusion retained | Public/provisional owners and ledgers; negative inventory; transport verifier allowances |

Split NI-03's session/header exclusion from replay, `Last-Event-ID` recovery, and POST-result recovery, which remain excluded. Do not rewrite historical evidence or broadly disable a gate.

### Named consumers and qualification leads

| Slice | Consumer to qualify | Evidence needed, not yet obtained for this expansion |
| --- | --- | --- |
| Completion | Inspector legacy mode; VS Code released build | Prompt and template suggestions, including a listed target with no completer |
| POST progress | Inspector and a host that visibly presents progress | First update before terminal result; no-update JSON; cancellation after progress |
| Pagination | Codex CLI and VS Code; pinned SDK harness | Actually drain multiple pages, including localized/filtered catalogs |
| Sessions/cancellation | A TS-SDK host or VS Code; pinned TS/Python harnesses | Cancellation, lost-session recreation, cap/expiry behavior and absent version-header policy |
| GET/subscriptions | A named released 2025 host that acts on notifications | Real subscription update and catalog refresh, reconnect and credential renewal |

Use 2025-only fixture deployments when a host otherwise selects modern on a dual-era URL. Also retain mixed-era tests on the common URL. Check released VS Code against the baseline before relying on the review's `main`-branch header observations. Identify released consumers early and qualify their actual behavior as part of the full implementation. A CLI that merely logs an event is not UI refresh evidence. If a host cannot exercise a facility, record that limitation and pursue another consumer; do not silently drop an agreed feature or move it to a later release.

### Outside this plan

- Incremental tool-result chunks, text deltas, or proprietary streaming extensions.
- Event replay, retained event history, `Last-Event-ID` recovery, and POST-result recovery through GET.
- Distributed session storage, recovery after restart, or a cross-node cancellation bus.
- Roots, sampling, elicitation, and other server-to-client requests.
- Legacy Tasks, modern multi-round input/request state, and custom mirrored-header declarations.
- Legacy Apps or Skills support and qualification. Sessions may provide useful infrastructure for a separate proposal, but do not establish those claims.
- A general JSON Schema implementation, authored output schemas, or broader URI-template levels.
- MCP logging delivery; it can be considered after the delivery infrastructure exists.
- stdio, the deprecated 2024 HTTP+SSE transport, `2025-03-26`, and older revisions.

## 2. Public API proposal

Everything marked **proposed** below is a design to review, not an existing API. Existing signatures are separately identified and were read from the stated source baseline. The inventories show domain methods; ordinary `Object` overrides and implementation details are omitted.

Use Soklet's named builder factories, boxed public scalar types, `Duration`/`Instant`, descriptive parameter names, and JSpecify nullability. New immutable carriers have private constructors. Callbacks must be safe for concurrent invocation. Builders are intended for one thread.

### 2.1 Proposed API, staged by implemented package

Names do not hide revision selection behind an unversioned `legacy` switch. Session revisions are explicitly selected per endpoint, like other optional facilities. Only June and November are eligible initially. Server configuration supplies ownership and bounds; it does not enable sessions on every endpoint serving a revision.

**Naming decision:** retain the five proposed `McpSession*` type names without a `Legacy` prefix or additional marker annotation. Their Javadoc must prominently identify applicability to session-enabled `2025-06-18` and `2025-11-25` endpoints and explain that Soklet's `2026-07-28` implementation does not use these types. Validate the exact eligible revision set for both annotated and programmatic configuration; future revisions do not become eligible automatically. These are supported features, not deprecated APIs. Existing subscription APIs document their behavior by revision; the shared `CLIENT_CANCELED` reason remains transport-neutral.

#### Proposed: `McpSessionConfig`

```java
public final class McpSessionConfig {
    @NonNull
    public static Builder withOwnerKeyResolver(
        @NonNull McpSessionOwnerKeyResolver sessionOwnerKeyResolver);

    @NonNull public McpSessionOwnerKeyResolver getOwnerKeyResolver();
    @NonNull public Integer getMaximumSessions();
    @NonNull public Integer getMaximumSessionsPerOwner();
    @NonNull public Duration getMaximumSessionIdleDuration();
    @NonNull public Duration getMaximumSessionDuration();
    @NonNull public Integer getMaximumClientMetadataSizeInBytes();
    @NonNull public Boolean isAnonymousSessionsAllowed();

    public static final class Builder {
        @NonNull public Builder maximumSessions(@Nullable Integer maximumSessions);
        @NonNull public Builder maximumSessionsPerOwner(
            @Nullable Integer maximumSessionsPerOwner);
        @NonNull public Builder maximumSessionIdleDuration(
            @Nullable Duration maximumSessionIdleDuration);
        @NonNull public Builder maximumSessionDuration(
            @Nullable Duration maximumSessionDuration);
        @NonNull public Builder maximumClientMetadataSizeInBytes(
            @Nullable Integer maximumClientMetadataSizeInBytes);
        @NonNull public Builder anonymousSessionsAllowed(
            @Nullable Boolean anonymousSessionsAllowed);
        @NonNull public McpSessionConfig build();
    }
}
```

The required owner resolver cannot be null. Optional tuning arguments use null to restore a default. Numeric limits and durations must be positive and mutually consistent. Configurations retain callback reference identity; they do not make application callback state immutable. This first package has no transport-controller accessor.

#### Proposed: `McpSessionOwnerKeyResolver`

```java
@FunctionalInterface
public interface McpSessionOwnerKeyResolver {
    @NonNull
    String resolve(@NonNull McpAdmissionIdentity admissionIdentity) throws Exception;
}
```

The resolver is a fast, local, nonblocking mapping after fresh admission, inside the operation's bounded execution scope. It returns a stable, opaque owner key distinguishing subjects, including issuer and tenant where relevant. The key is nonblank and at most 256 UTF-8 bytes. It is not emitted in responses, logs, or metric labels. Do not perform remote authorization here; use the cancellable admission/authorization callbacks.

Soklet must not silently derive ownership from `principal.equals()`, principal object identity, the authorization partition key, or the rate-limit partition key. Those can represent a role, tenant, or group of users rather than one owner. Resolver failure, timeout, null, or an invalid key fails closed.

Anonymous means `!admissionIdentity.isAuthenticated()` and is disabled by default. Resolve keys for anonymous callers only after explicit opt-in, in a separate internal namespace. The canonical accept-all identity provides no client distinction: a resolver returning one public key deliberately puts all those callers under one quota. A trusted proxy-derived input may partition public clients, but is not a user identity. Anonymous owners also share a bounded server-wide sub-budget. Reject anonymous initialization with a neutral `403` when disabled; emit the existing implicit-accept-all diagnostic with session-specific guidance. Do not generate a fresh owner key per initialization to bypass per-owner limits.

#### Delivery package only: additions to `McpSessionConfig`

```java
// Added only when the delivery package is implemented:
@NonNull
public Optional<@NonNull McpSessionTransportAdmissionController>
    getTransportAdmissionController();

// McpSessionConfig.Builder:
@NonNull
public Builder transportAdmissionController(
    @Nullable McpSessionTransportAdmissionController
        sessionTransportAdmissionController);
```

The controller is cleared by null. Its three public types below are introduced with the delivery slices, after the session slice, within the agreed 4.0.0 target.

#### Proposed: HTTP admission context and controller

```java
public interface McpSessionTransportAdmissionContext {
    @NonNull Request getRequest();
    @NonNull McpEndpoint getEndpoint();
    @NonNull McpProtocolVersion getProtocolVersion();
    @NonNull Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes();
    @NonNull Boolean isReauthorization();
    @NonNull Instant getDeadline();
}

@FunctionalInterface
public interface McpSessionTransportAdmissionController {
    @NonNull
    McpSessionTransportAdmissionDecision admit(
        @NonNull McpSessionTransportAdmissionContext
            sessionTransportAdmissionContext,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}
```

`Request.getHttpMethod()` distinguishes GET and DELETE. `getNotificationTypes()` exposes the effective revision-specific offered family set, including eligible localization invalidations, and is empty for DELETE. The decision can narrow that set per caller. Features provide cooperative cancellation for deadline, disconnect, reconciliation and shutdown; progress is unavailable in this callback. `getDeadline()` is the queue-inclusive callback deadline, not grant expiry. A callback ignoring cancellation retains its execution reservation until physical exit.

#### Proposed: explicit expiry on HTTP admission

```java
public sealed interface McpSessionTransportAdmissionDecision
    permits McpSessionTransportAdmissionDecision.Accepted,
            McpSessionTransportAdmissionDecision.Rejected {
    @NonNull
    static Accepted accepted(
        @NonNull McpAdmissionIdentity admissionIdentity,
        @NonNull Instant validUntil,
        @NonNull Set<@NonNull McpSubscriptionNotificationType> notificationTypes);

    @NonNull
    static Rejected rejected(
        @NonNull McpAdmissionRejection admissionRejection);

    public final class Accepted implements McpSessionTransportAdmissionDecision {
        @NonNull public McpAdmissionIdentity getIdentity();
        @NonNull public Instant getValidUntil();
        @NonNull public Set<@NonNull McpSubscriptionNotificationType> getNotificationTypes();
    }

    public final class Rejected implements McpSessionTransportAdmissionDecision {
        @NonNull public McpAdmissionRejection getRejection();
    }
}
```

There is no expiry-free `accepted(identity)` overload. The existing RPC admission decision has no expiration, so reusing it with a fixed recheck interval could deliver beyond a credential's known expiry.

Accepted types must be a subset of the offered types; DELETE requires the empty set and a still-future expiry immediately before retirement. Null/expired/invalid decisions fail through the bounded hook-error path. Reauthorization may narrow delivery; it cannot exceed the original offered selection. Resource updates need both channel permission and a separately authorized URI grant.

A rejected HTTP decision reuses `McpAdmissionRejection`'s validated status and headers, including an application-selected `WWW-Authenticate` challenge. GET/DELETE have no JSON-RPC request ID; the rejection's JSON-RPC error is not rendered on these HTTP-only paths. Verify this deliberate reuse with wire tests. A separate general HTTP rejection carrier would be an amendment to the agreed proposed footprint, not an assumed extra type.

### 2.2 Existing owners changed and reason mapping

```java
// Proposed additions to McpServer:
@NonNull
Optional<@NonNull McpSessionConfig> getSessionConfig();

// Proposed addition to McpServer.Builder:
@NonNull
public Builder sessionConfig(@Nullable McpSessionConfig sessionConfig);

// Minimum session package, within @McpServerEndpoint:
@NonNull
McpProtocolVersion @NonNull [] sessionProtocolVersions() default {};

// McpEndpoint:
@NonNull public Set<@NonNull McpProtocolVersion> getSessionProtocolVersions();

// McpEndpoint.Builder:
@NonNull public Builder sessionProtocolVersions(
    @NonNull Set<@NonNull McpProtocolVersion> protocolVersions);

// Delivery package additions to McpOperationType:
RESOURCES_SUBSCRIBE,
RESOURCES_UNSUBSCRIBE

// Proposed transport-neutral addition to StreamTerminationReason:
CLIENT_CANCELED

// MCP-specific additions to McpStreamTerminationReason:
SESSION_EXPIRED,
SESSION_CLOSED
```

Do not put protocol-specific session constants on the ordinary streaming enum or revive 3.5.1's removed shared `SESSION_TERMINATED` identifier. The token still returns the shared enum, so one neutral client-cancellation value is proposed. It requires a shared-host rationale and a matching soklet-otel update: its exhaustive Java 17 switch otherwise fails recompilation. Moving all reasons to the MCP enum alone would not solve the token contract.

| Event winning before terminal result | Token reason | MCP stream reason | Admitted request outcome |
| --- | --- | --- | --- |
| Explicit request cancellation | `CLIENT_CANCELED` | Existing `REQUEST_CANCELED` | Existing `CANCELED` |
| Session deadline expiry | Existing `RESPONSE_TIMEOUT` | Proposed `SESSION_EXPIRED` | Existing `DEADLINE_EXCEEDED` |
| Verified DELETE | `CLIENT_CANCELED` | Proposed `SESSION_CLOSED` | Existing `CANCELED` |
| Forced server stop | Existing `SERVER_STOPPING` | Existing `SERVER_STOPPING` | Existing `CANCELED` |

Keep the session cause separately in internal MCP control state; do not infer it solely from the generic token reason. Cancellation/expiry that loses to terminal-response reservation does not replace its outcome. Review enum cardinality and downstream mapping before release; an existing jar's runtime failure depends on a new value reaching an exhaustive switch, not merely appearing in the enum.

Public owner inventory: `McpSessionConfig`, its `Builder`, and `McpSessionOwnerKeyResolver` arrive with sessions (three owners). The HTTP context/controller/decision and nested `Accepted`/`Rejected` arrive with delivery (five owners). Every domain method is shown above; also track changed existing owners, annotation members, enum constants, simulator contracts and documentation in provisional inventories. Freeze only the implemented release surface.

No session ID, wire DTO, session store, event-history object, or transport-specific handler argument becomes public. Applications still configure Soklet's managed MCP listener and unified lifecycle normally.

### 2.3 Current application signatures that remain unchanged

```java
public interface McpToolHandler<A> {
    @NonNull
    McpOperationResult handle(
        @NonNull McpRequestContext requestContext,
        @NonNull McpToolArguments<@NonNull A> toolArguments,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}

public interface McpPromptHandler {
    @NonNull
    McpOperationResult handle(
        @NonNull McpRequestContext requestContext,
        @NonNull McpPromptGetContext promptGetContext,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}

public interface McpResourceReadHandler {
    @NonNull
    McpOperationResult handle(
        @NonNull McpRequestContext requestContext,
        @NonNull McpResourceReadContext resourceReadContext,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}

public interface McpResourceListHandler {
    @NonNull
    McpResourcePage handle(
        @NonNull McpRequestContext requestContext,
        @NonNull McpResourceListContext resourceListContext,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}

public interface McpCompletionHandler {
    @NonNull
    McpArgumentCompletionResult handle(
        @NonNull McpRequestContext requestContext,
        @NonNull McpCompletionContext completionContext,
        @NonNull McpInvocationFeatures invocationFeatures) throws Exception;
}

// Existing methods, within McpInvocationFeatures:
@NonNull default CancelationToken getCancelationToken();
@NonNull default Optional<@NonNull McpProgressReporter> getProgressReporter();

public interface McpProgressReporter {
    void report(@NonNull McpProgressUpdate update);
}
```

Completion already has these programmatic registration signatures, within their respective builders:

```java
// McpPromptRegistration.Builder:
@NonNull
public Builder completionHandler(
    @NonNull McpCompletionHandler completionHandler,
    @NonNull Set<@NonNull McpProtocolVersion> protocolVersions);

// McpResourceRegistration.TemplateBuilder:
@NonNull
public TemplateBuilder completionHandler(
    @NonNull McpCompletionHandler completionHandler,
    @NonNull Set<@NonNull McpProtocolVersion> protocolVersions);
```

These are the existing annotations:

```java
public @interface McpPromptCompletion {
    @NonNull McpProtocolVersion @NonNull [] protocolVersions();
    @NonNull String name();
}

public @interface McpResourceCompletion {
    @NonNull McpProtocolVersion @NonNull [] protocolVersions();
    @NonNull String uri();
}
```

The implementation widens supported revisions in validation after the corresponding adapter is ready. It does not introduce alternate legacy completion handlers or alter `McpProgressUpdate`, which already supports finite floating-point progress values.

### 2.4 Existing contracts whose semantics expand

| Existing surface | Proposed change |
| --- | --- |
| `McpRequestContext.getClientInfo()` / `getClientCapabilities()` | In an enabled legacy session, expose the immutable initialization snapshot after fresh admission and owner verification. Modern and stateless behavior remains request-local. |
| `CancelationToken` / progress reporter lifecycle documentation | A committed legacy POST SSE disconnect detaches delivery. Finite/queued disconnect cancellation remains. Wake blocked reporters and make later reports inert. Preserve modern/ordinary HTTP semantics. |
| `McpAdmissionContext` | For subscribe, expose the one validated URI and true grant-selection flag. For unsubscribe keep the selection list/flag empty/false, and expose its validated target through `getOperationName()` on both operations. No fake GET/DELETE RPC methods or cached identity. |
| `McpEndpoint` / `@McpServerEndpoint.subscriptionProtocolVersions` | June/November opt into the legacy notification facilities below; modern `subscriptions/listen` keeps its current meaning. No new mandatory annotation members. |
| `McpSubscriptionConfig` / publisher / notification-type documentation | Reuse declared notification families and application-owned broadcast events; add internal legacy renderers and dispatch. Explain endpoint-level legacy invalidations versus modern caller-visible change detection. |
| `McpSubscriptionAuthorizer` / authorization context | Authorize and renew real legacy `resources/subscribe` grants using that operation's historical request context and selected URI. Existing expiry and reconciliation contracts apply. |
| `McpSubscriptionReconciler.reconcileSubscriptions()` | Also synchronously fence prior authorization for active and establishing legacy resource grants/GET streams, then schedule bounded reevaluation. |
| Existing server subscription limits | Extend documented applicability to legacy delivery registrations, GET lifetimes, URI-grant lifetimes, and authorization callbacks, as specified in Section 5. |
| Existing completion registration validation | Permit the two 2025 revisions, with the same explicit registration/subset checks. |
| `McpSimulation.close()` / `Simulator.startMcpRequest` | Apply the finite-versus-committed-SSE disconnect distinction and define session-aware POST correlation. GET/DELETE simulation must be explicitly supported or marked unavailable, with real-socket supplements. Any necessary additional public simulator signatures must be recorded as an API amendment; they are not included in the current footprint count. |

GET admission selects a caller-authorized subset of the endpoint's offered families; the subscription authorizer additionally governs individual URI grants. Catalog-only GET does not manufacture an initial RPC subscription context.

The snapshot is informational client advertisement, never identity, scope, or permission. `getClientInfo()`/`getClientCapabilities()` become effective verified session values; `getRequestMetadata()` and admission stay message-local. Pass separate fields after owner verification through the mapper/bridge/context seams. Do not inject the snapshot into incoming `_meta` or strip advertised features to pretend the client never offered them. Profile routing/result validation still prevents unsupported Apps, Tasks, or other legacy features.

## 3. Configuration and protocol selection

Applications still declare exact revisions on endpoints and operations. This example adds the proposed optional session member to the existing annotation:

```java
@McpServerEndpoint(
    path = "/catalog/mcp",
    name = "catalog",
    version = "1.0",
    protocolVersions = {
        McpProtocolVersion.V2025_06_18,
        McpProtocolVersion.V2025_11_25,
        McpProtocolVersion.V2026_07_28
    },
    sessionProtocolVersions = {
        McpProtocolVersion.V2025_06_18,
        McpProtocolVersion.V2025_11_25
    })
public final class CatalogEndpoint {
    // Existing explicitly versioned tool/prompt/resource declarations.
}
```

Session enablement is an explicit endpoint revision gate; generated and manual endpoints use the same runtime and server-wide bounds. Delivery additionally selects `subscriptionProtocolVersions` and configures the later HTTP controller. A minimum-session construction fragment is:

```java
McpSessionConfig sessionConfig =
    McpSessionConfig.withOwnerKeyResolver(sessionOwnerKeyResolver)
        .build();

// On the application's otherwise normally configured McpServer.Builder:
mcpServerBuilder.sessionConfig(sessionConfig);
```

The resolver is application-provided. Normal endpoint registry/admission/limiter setup remains required. The later delivery package adds the transport controller and event sources; a URI authorizer is required for `RESOURCE_UPDATED`, not for catalog-only delivery.

| Configuration | Behavior |
| --- | --- |
| No endpoint session revisions | Stateless 2025 correlation semantics; separately implemented completion/progress/pagination can work. No remembered metadata or cross-request cancellation. GET/DELETE remain `405`. |
| Endpoint selects a 2025 session revision and server config exists | Initialization issues a session ID; later requests for that endpoint/revision require it. Modern and other stateless endpoints never enter the store. |
| Configured sessions, transport controller absent | Minimum POST-only session/cancellation package. GET/DELETE remain `405`. |
| Legacy subscriptions selected | Require matching session revisions/config, explicit HTTP controller, and an effective event source/family set. Require a URI authorizer when URI updates are selected. Fail construction if incomplete. |
| Unsupported revision, inconsistent subset, or unimplemented facility | Fail construction or reject the wire request; no silent downgrade or automatic latest revision. |

Endpoint session revisions must be a supported subset of endpoint revisions. Reject an enabled endpoint without server configuration, or unused server configuration. Keep a stateless endpoint possible on the same server. Enabling/changing session mode requires clients to reconnect and a fleet rollout to drain old connections; do not run inconsistent modes behind one URL. Missing IDs are not silently downgraded, and full-store initialization is not silently advertised with different capabilities.

URLs remain application-selected stable paths. A session binds one exact path and negotiated revision. Negotiation does not pick another URL for the client, migrate a session between revisions, or expose an undeclared operation.

The current header requirement is explicit: non-initialize requests require a supported `MCP-Protocol-Version`, including GET/DELETE. The review's released-VS-Code question is a Slice 0 test; first try a documented client header configuration. Header-less hosts remain unsupported unless a separately reviewed admission design can infer a verified session revision without exposing session existence or inventing a pre-admission version. Do not silently use March, June, or November when no trustworthy selection exists.

## 4. Internal design

### 4.1 Keep the adapters isolated

Add internal profile-specific encoders and legacy session/delivery coordinators behind the existing version-aware dispatcher. Reuse execution plans, schema decoding, caller-aware policies, admission, request/tool limits, localization, interception, sanitization, and bounded executors.

Do not bolt sessions onto modern request state or reuse the modern subscription coordinator's ownership model unchanged. Modern `2026-07-28` remains stateless. A modern request cannot allocate, look up, renew, cancel, or terminate a legacy session, including at a shared URL.

Public negative-inventory tests currently prohibit session identifiers across the MCP implementation. Narrow those prohibitions only for reviewed legacy modules; retain structural tests proving modern isolation. Do not disable the inventory as a blanket exception.

### 4.2 Completion

Route `completion/complete` to the existing prompt or URI-template completer for the selected revision. Validate reference type, target, argument name, partial value, context arguments, and result limits. Ordinary required prompt arguments need not all exist during completion. Advertise the completion capability only when implemented and configured for that revision.

The baseline drops completion revisions from internal `CompletionPlan` and copies completion maps into revision views unfiltered. Carry the exact registration sets through the plan and filter both prompt/template maps, dispatch and capability advertisement per revision. Update processor validation, the legacy method allowlist and hand-built initialization capabilities together. A mixed endpoint with modern-only P and June-only Q must never call P's completer from June.

Run fresh RPC admission and request limiting. Prompt suggestions honor catalog policy; resource suggestions remain application-authorized. On legacy profiles a declared/visible target and declared argument with no completer returns empty values; unknown/hidden references and undeclared arguments remain neutral invalid-params errors. This is a chosen compatibility behavior, not a claim that the current template error violates a MUST. Modern behavior is preserved. See the [completion specification](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion).

### 4.3 POST SSE and progress

With a valid progress token and acceptable SSE transport, expose the existing reporter before invoking the handler. Preserve lazy commitment: the first accepted update opens SSE; a handler reporting no progress finishes through JSON. Without a token, or when progress is suppressed, return an empty reporter optional. Early errors retain normal HTTP/JSON handling.

Clients must use unique string/integer tokens across active requests. For a colliding token in one verified session, process the new request normally with progress suppressed; never redirect its reports into another request. Logical token activity ends at final-response reservation, cancellation fence or writer detachment, independently of retained worker capacity. Stateless tokens are POST-local. Serialize increasing updates, coalesce equals and stop after fencing. This receiver policy uses optional progress; the spec does not require this particular collision response. See the [progress specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress).

The originating POST receives its own progress and terminal response. GET does not receive that response or provide recovery if the POST disconnects. Emit one whole, sanitized, size-checked terminal result or error while the transport remains usable. Errors before stream commitment retain normal HTTP/JSON handling; after commitment use the selected legacy SSE encoder.

`McpRequestSseStream` currently uses modern envelope encoding, while `McpLegacyResponseWire` projects finite legacy results. Introduce an explicit profile-aware encoding seam: copying the modern stream's encoder would leak modern result discriminators or metadata. Compare JSON and SSE terminal projections with per-revision golden tests.

**Committed SSE disconnect is separate from cancellation.** Detach that writer, wake blocked reporters and suppress later reports/results without canceling work solely due to connection loss. Preserve current disconnect cancellation for finite/uncommitted or queued calls; this avoids removing the only cancellation path from stateless finite clients. Keep deadlines and physical worker reservations. Session registry logical eligibility and physical worker ownership are separate. A result for a lost SSE writer is discarded, not replayed. Modern disconnect semantics remain unchanged.

### 4.4 Static catalog pagination

Paginate framework-produced `tools/list`, `prompts/list`, static `resources/list`, and `resources/templates/list` results for both legacy revisions. Preserve custom resource-list handlers and their application-owned cursors. Initially preserve modern static catalog behavior.

At startup build a stable canonical key order and lookup index for each path/revision/catalog kind. On a page, admit freshly, resume after the cursor key, and evaluate only subsequent candidates needed to fill that page. Tools/prompts use current caller-aware policy; resources retain their admission/read-handler model. Authorize a cursor anchor neutrally before using it; unknown or newly hidden anchors both fail invalid-params. Arbitrary cursor positions cannot grant hidden entries.

Keep a catalog single-page when it fits existing byte, node and localization-slot budgets; do not impose the draft's new 128-entry split. Reserve envelope and possible `nextCursor` bytes. Localize only page owners, using an owner-indexed slot plan so it does not rescan every catalog slot. If localized output exceeds its budget, reduce the page prefix with bounded internal re-encoding retries; a single descriptor that cannot fit fails safely. Reuse one immutable request localization context and cached lookup outcomes across retries. Enforce `getMaximumLocalizableTextCountPerResponse()` across the whole response, not separately per attempt; shrinking a page must not repeat application lookups or reset their budget. Benchmark that path as well as the common one-render page.

**Revised cursor:** bounded format version, kind, startup fingerprint for exact path/revision/kind, last emitted canonical key, and negotiated locale tag where needed. Use a fixed-size opaque key representation/index so a long URI does not require an unbounded token. Hash sorted-key untranslated descriptors incrementally at startup, never a full localized/authorized view per page. Validate encoding/size cheaply before admission and anchor/catalog/locale after admission. Changed catalog fingerprint or locale, unknown/hidden anchor, wrong kind/path/revision, or corrupt input produces neutral `-32602`. A transient default-text fallback does not change the startup fingerprint. See the [pagination specification](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/pagination).

The cursor is navigation, not a grant, MAC or authorization snapshot. Apply fresh policy to every emitted entry. Permission changes may omit/add later entries; revoked anchors require restarting. Translation changes can affect later pages: no consistent snapshot is promised. Do not bind this codec to request-state protection keys. Cursors may fingerprint catalog structure and must be treated as sensitive, not logged; opaque hashing does not eliminate offline guessing.

Equivalent nodes need identical canonical fingerprint/order/key interpretation, not identical page sizes. Framework cursors have an independent fixed internal bound (proposed 2 KiB); custom resource-list cursors keep the application-configured cap, even if it is one byte. No shared signing key or session is required.

Keep aggregate fail-fast checks for every unpaged profile, especially modern on a mixed endpoint. Amend all three sites: server aggregate validation, bridge collection guards and runtime per-revision precompute/preflight. Only paged legacy views bypass whole-response checks; per-descriptor checks remain. This replaces the infeasible full-localized-view digest and avoids repeating all N policy/localization/encoding operations on every page. Test total enumeration cost, not just one page.

### 4.5 Sessions and remembered metadata

Use a bounded in-memory store owned by the MCP server's lifecycle. A record contains a cryptographically random 256-bit ID, owner key, server lifecycle generation, normalized exact endpoint path, negotiated revision, timestamps/deadlines, bounded initialization snapshot, active request registry, and eventual resource grants/GET connections.

Mint 256-bit, 43-character unpadded base64url IDs. Incoming validation accepts bounded visible-ASCII protocol syntax: another server's well-formed format is unknown, not malformed forever. Lookup/binding failure produces neutral `404` only after admission. IDs are not credentials; never log them or owner keys. The snapshot stores no current credential authority. GET/grant historical evidence intentionally retains bounded credentials/identities for renewal, as accounted in Section 5.

Initialization reserves capacity after admission/owner resolution. Insert a published-awaiting-acknowledgement record **before** offering bytes containing its ID; retire it on delivery failure unless accepted owner-bound use has already proven delivery. Never wait for a body-completion callback to make lookup possible. Validate the retained public client-info/capability projection against byte and node caps before publication; overflow returns a documented initialization error, not a silently incomplete snapshot.

After publication, permit fresh owner-bound ordinary POSTs and GET during the acknowledgement window; clients may open GET before sending initialized. Record acknowledgement before offering its empty `202`; repeated acknowledgements are harmless. Validated notification `_meta` is accepted. Record a persistent delivery-evidence bit on the first accepted owner-bound session use, even if that operation finishes before the handshake deadline. At that deadline retire only an unacknowledged record with no such evidence; evidenced records follow normal idle/hard lifetimes. A late initialization-writer failure must not invalidate demonstrated delivery. An `initialize` carrying a well-formed stale ID is a new admitted allocation, never mutation/fixation of that referenced record; malformed duplicate framing still fails.

Every later POST is freshly admitted and limited before it can use a session. Verify the owner, endpoint, exact negotiated revision, and lifecycle generation. Only then supply the initialization snapshot to the execution context. Do not reuse the original accepted identity or application context as current permission.

Proposed HTTP behavior:

| Condition | Result |
| --- | --- |
| Missing required session ID, duplicate/non-visible/oversize header, invalid version framing | `400`; no allocation/mutation |
| Fresh admission rejection | Preserve safe policy response; remap reserved application `400`/`404`/`405` to neutral `403` on session paths |
| Unknown, expired, wrong-owner or wrong-path ID after fresh admission | Same neutral `404`; no existence detail |
| Verified owner/path but mismatched stored revision | `400`; preserve the record; do not return `404` while leaking an occupied slot |
| Per-owner allocation cap | Reclaim expired and eligible same-owner quiescent records first; then `429` with bounded Retry-After |
| Global store capacity | `503` with bounded Retry-After; no implicit stateless downgrade or cross-owner live eviction |
| Successful admitted DELETE | Atomic retirement and empty successful HTTP response, proposed `204` |

These choices follow the session mechanisms in the [June transport specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports), while keeping application authentication mandatory for protected endpoints. Validate protocol framing separately from a well-formed but mismatched stored revision.

Idle means no in-flight admitted request, no GET holding a live authorization lease, and no accepted owner-bound message within the idle interval. Invalid/foreign requests and raw keepalive/publisher bytes do not refresh it. A hard lifetime is separately configurable and enforced; default values must pass real recovery checks. Reclaim expired records, then the same owner's oldest quiescent record past a minimum idle age before rejecting initialization. Do not evict active work or another owner's live state to accommodate a newcomer.

Retirement fences new use, grants, queued notifications and new delivery offers immediately. Ordinary expiry/DELETE preserves a POST terminal response whose reservation already won; privacy revocation is the explicit exception. Release the logical session-count slot, but keep residual callback/worker/evidence reservations until physical exit. Explicit request cancellation completes its HTTP exchange without a result. For server-chosen expiry/DELETE, reserve a neutral correlated `-32603` error on a usable POST path if its final response has not already won; do not silently leave SDK callers waiting at EOF. Revocation/privacy fencing may purge sensitive queued data rather than flush a reserved result. Graceful server stop keeps the existing bounded drain; forced stop follows its documented terminal path. Test each cause, not one unconditional close operation.

Sessions are node-local. Affinity must be learned from the initialization response or otherwise keep that first node: hashing a newly minted ID cannot route its initial request. Qualify learned cookie/header stickiness and all subsequent methods. Restart/node loss returns neutral `404`; the spec expects reinitialization, but pinned TS/Python source does not establish transparent recovery. Require named-host recovery evidence or document manual reconnection. No shared-store/failover claim.

### 4.6 Cancellation

Track active IDs inside one verified session, separated by direction. Distinguish integer `1` from string `"1"`. Different sessions may reuse IDs. Both exact base specifications require a requester not to reuse an ID anywhere in the same session, including after completion: see [June requests](https://modelcontextprotocol.io/specification/2025-06-18/basic) and [November requests](https://modelcontextprotocol.io/specification/2025-11-25/basic).

Enforce only active logical ID collisions, using the existing bounded admitted/queued/running registry. Reject a collision with `-32600` without replacing work. Do not keep mandatory lifetime ID history or retire a valid session at a request counter/byte ceiling. The requester still owns the no-reuse MUST; any bounded recent-ID window is only optional diagnostics, not comprehensive enforcement. Logical completion/cancellation makes a target uncancellable; physical worker capacity remains reserved until exit. A cancel overtaking target registration can be ignored, as permitted by the protocol; test and document that limitation.

A freshly admitted `notifications/cancelled` only targets that session's matching active client request. Unknown, already completed, or otherwise uncancellable targets are ignored without revealing existence. Initialization cannot be canceled. Free-form cancellation reasons are bounded and excluded from built-in logs/metrics.

An accepted cancellation POST always receives its own HTTP `202` with no body. Suppressing the canceled operation's result does not suppress that notification's transport acknowledgement.

Make cancellation reservation report whether it won; the baseline returns an indistinguishable no-op on a losing reservation and unconditionally closes transport. If it wins, signal `CLIENT_CANCELED`, remove queued work where possible and fence reports/results. If final-response reservation already won, preserve that response against client cancellation, including reserved-but-unwritten terminal bytes. Add finite and SSE race hooks; do not reuse the callback-dropping `RequestControl.cancel` unchanged. See the [cancellation specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation).

**Exact finite wire outcome:** a winning explicit cancellation before HTTP commitment offers `200`, `Content-Type: text/event-stream`, zero JSON-RPC events, and cleanly completes the body. Never return an empty successful JSON document or null/drop a live response callback. A committed SSE is cleanly ended without a result. This suppresses the canceled RPC response while completing its HTTP exchange; the cancellation POST still gets its own empty `202`. Add an internal no-message finish path, since the current outbound terminal primitive requires bytes. Qualify pinned SDK behavior and assert real socket completion.

Parse cancel notifications per revision; unusable request IDs are ignored with accepted `202`, and an invalid optional reason does not invalidate a usable target. Keep admission/limiter order; recommend applications allow cancellation under load rather than silently exempting it in the framework. Excluding free-form reasons from built-in logs is a deliberate privacy deviation from the logging recommendation; no new reason-string hook is proposed.

At this implementation checkpoint GET/DELETE remain disabled until the delivery slices land; this is not the agreed 4.0 release stopping point. Cancellation is not made reliable for stateless calls by a global request-ID lookup.

### 4.7 GET/DELETE admission and GET authorization leases

GET and DELETE have no JSON-RPC method. Keep `McpAdmissionController` for real POST messages and invoke the proposed HTTP transport controller for these methods. Do not invent `ping`, `subscriptions/listen`, or `transport/get` to satisfy an existing context constructor.

Before stream commitment or session mutation, apply host/origin/CORS/header checks, select an explicitly declared legacy revision, perform bounded fresh HTTP admission, resolve the owner, and verify the session binding. Reject nonempty GET/DELETE bodies and bound retained headers before admission/allocation. Session existence must not be observable before identity verification. Update method dispatch, `Allow`, and CORS request/response headers together. OPTIONS preflight does not grant access or create a session.

Without a transport controller GET/DELETE return `405`. A GET additionally requires a live published session, including the acknowledgement window described in Section 4.5, and selected legacy notification configuration. A controller alone does not enable unsolicited messaging. DELETE can be enabled for session cleanup without enabling subscriptions.

A GET authorization lease ends at the earliest of application `validUntil`, the existing maximum subscription authorization duration, the GET's total lifetime under `maximumSubscriptionDuration`, and applicable session deadlines. Schedule bounded reauthorization before expiry. Convert the bounded lease to monotonic deadlines safely; wall-clock adjustments or extremely distant instants must not produce overflow or an unbounded grant.

Renewal uses the original immutable GET request as credential input, calls the application again for a fresh identity/expiry, and resolves/verifies the owner again. The callback must check current authority and time; historical accepted identity is not proof. Expiry fences queued delivery even while a renewal callback is slow. A late callback cannot resurrect an expired or closed stream. Denial, timeout, owner change, or expiry closes that GET stream. Bytes already given to the transport cannot be recalled.

DELETE requires a currently valid freshly accepted decision immediately before atomic retirement; it retains no grant. Ordinary denial stays ordinary denial. Soklet only signals OAuth when the application explicitly supplies the appropriate validated challenge header.

The existing `McpRateLimiter` context describes actual RPC methods. Do not fabricate one for GET/DELETE. Bound HTTP control admission with global connection/executor limits and an internal per-partition control budget, including renewal demand. Review that budget against hostile connection churn. If applications need a configurable distributed HTTP limiter, propose that API separately rather than claiming current RPC rate limiting covers it.

### 4.8 Resource subscriptions and catalog invalidations

Add legacy `resources/subscribe` and `resources/unsubscribe` as real POST operations through normal admission, request limiting, and revision-aware routing. Admission receives the validated URI selection before a resource grant is created, so the application can return a scope challenge before any delivery.

Subscribe validates a readable route for the selected revision and obtains a bounded `McpSubscriptionAuthorization` lease for that URI. The authorizer owns permission to receive notifications; route existence is not permission. Repeated subscribe is idempotent under fresh authorization. Unsubscribe removes the existing grant idempotently; it cannot create or expand a grant.

A successful duplicate subscribe atomically replaces retained credential/context evidence under a new generation while preserving the original total grant lifetime. Old renewals cannot restore old evidence. Unsubscribe fences both establishing and active grants, so an overtaken subscribe cannot recreate the removed grant. A refreshed GET does not implicitly grant new URI permissions. Authorizers should check current application authority rather than treating the historical token as eternally valid; hosts requiring transparent token refresh/resubscription need explicit qualification.

Subscribe/unsubscribe are JSON-RPC requests: successful processing returns the matching request ID and an `EmptyResult`, ordinarily `result: {}`. They do not receive notification-style HTTP `202` handling. The exclusion of modern subscription acknowledgement messages below does not exclude these normal legacy responses.

Legacy resource grants belong to the session, not the POST response or an individual GET stream. A dropped GET leaves bounded grants intact until unsubscribe, lease denial/expiry, total grant lifetime, session retirement, or server shutdown. A reconnect obtains fresh GET admission before delivery. Renewal contexts retain the real subscribe request as historical evidence, not current authority; reuse the existing application-context replacement and deadline contracts. Do not silently redact or replace `getInitialRequestContext()` with a fabricated request to avoid acknowledging retention.

Preserve the existing reconciler's synchronous local guarantee: before `reconcileSubscriptions()` returns, prior authorization is fenced for all relevant active **and establishing** GET streams and URI grants, including grants with no connected GET. Then schedule bounded reevaluation. Temporary scheduling capacity failure follows the fenced, bounded retry policy below; terminal failure, denial or lease expiry closes/removes the affected registration. Generation checks prevent a concurrently completing establishment/renewal from reinstating the authorization that reconciliation just fenced.

Reuse the existing per-server publisher registration and source-generation fence; do not attach a second listener to a distributed bus. Compute one effective per-revision family set from explicit publisher families and eligible localization sources, driving advertisement, admission and dispatch. Apply era-aware predicates in server/bridge/processor wiring; never attach or route Tasks events solely because a legacy subscription set is nonempty. Render only legacy URI updates and list-change messages; no modern IDs/acknowledgements/terminal messages/result metadata. The [June](https://modelcontextprotocol.io/specification/2025-06-18/server/resources) and [November](https://modelcontextprotocol.io/specification/2025-11-25/server/resources) resource specifications are the wire references.

Advertise only configured, implemented notification capabilities for each selected revision: URI updates map to `resources.subscribe`, resource catalog changes to `resources.listChanged`, and tool/prompt changes to their respective `listChanged` fields. Catalog changes do not require an individual resource subscription. Modern `subscriptions/listen` retains its own independent coordinator and wire behavior.

Prefer the newest eligible authorized GET, avoiding alternating delivery into an old half-open socket. Eligible means live channel, current owner/lease/family permission and available bounded output capacity. A freshly authorized replacement may close the oldest same-session stream at the local cap with atomic reservation transfer; failure preserves the current stream and returns retryable capacity error. Offer a JSON-RPC message on only one selected stream per session. This is best effort, not proof of receipt.

Resource delivery requires both a GET lease and a URI grant. Coalesce at most one pending message per family/URI. Generation fences must reach the write boundary: purge wholly unwritten revoked keyed frames, or close the affected GET before it can drain them. Offer-time checks alone do not fence opaque queued bytes. Already-written bytes and a partially written frame cannot be recalled; close rather than finish a newly forbidden remainder. Removing one URI grant does not automatically retire unrelated work or the whole session.

Publisher catalog events are reevaluation hints. Suppress a hint when the corresponding catalog cannot change (immutable caller-independent view with no localization); otherwise coalesce one outstanding invalidation per session/family and rearm on its next admitted list request. Localized catalogs can change globally even without caller policy. No fake GET-based RPC context is created for catalog evaluation. Authorized legacy notifications remain coarse and may reveal timing; the GET decision can withhold individual families. Modern caller-visible detection remains unchanged.

**No event history, but bounded invalidation state:** keep one dirty bit per permitted catalog family and per live URI grant across GET gaps. A new freshly admitted GET may receive a newly synthesized coalesced invalidation for current state. Store no historical payload, event ID or POST result; this is not replay or lossless delivery. Clear/fence bits on unsubscribe/revocation and test replacement races. The protocol does not require clients to relist on every disconnect, so do not rely on that as a correctness guarantee.

Renewal/reconciliation uses paced due-maintenance with one deduplicated pending check per grant/GET and reserved execution capacity. Do not submit every lease at once to the existing four-worker/128-queue scheduler. Temporary scheduling failure keeps a fenced grant pending for bounded retry, never delivers past expiry, and is measured. Denial removes the affected grant. Legacy has no subscription-ended message: qualify and document re-subscription/loss behavior, rather than retiring an entire session to force clients to notice.

### 4.9 November SSE priming: a required review disposition

The [November transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) says the initial empty SSE event with an ID **SHOULD** be sent to prime reconnect/polling. General event IDs, replay/history, and resumability are **MAY** facilities; priming does not itself establish replay support. This plan excludes IDs/history/recovery and proposes persistent connections until result/termination, without intentional connection polling.

Proposed disposition: omit priming IDs for persistent, nonresumable streams, record the SHOULD exception and qualify actual clients. No legacy frame contains `id:`. A bounded `Last-Event-ID` on GET opens a freshly admitted stream with no recovery, never replays a POST response; document that it is ignored. No official scored priming evidence is assumed: the pinned polling scenario may be pending/inapplicable or fail to reach priming. Use golden frames and reference clients to verify the disposition. A required host behavior may still require reopening this scope.

### 4.10 HTTP and allocation outcomes

Accepted legacy JSON-RPC requests return normal results/errors as HTTP `200`, JSON before stream commitment and profile SSE afterward. Reserve `404` for verified session lookup failure; `405` means the selected HTTP facility is not offered. Modern status mappings remain unchanged. On session paths, remap application admission `400`/`404`/`405` to neutral `403` while preserving validated safe headers; do not turn an operation denial into a session-reset signal. Other valid policy/rate/transport statuses retain their documented semantics.

| Condition | Wire outcome | State/capacity effect |
| --- | --- | --- |
| Unknown/unadvertised RPC method or disabled completion/subscription | `200`, JSON-RPC `-32601` | Keep verified session; no handler/grant |
| Invalid params/cursor, undeclared completion argument | `200`, `-32602` | Keep session; no new allocation |
| Active request ID collision | `200`, `-32600` | Keep original work; do not replace registry entry |
| Progress-token collision | Process normally, no reporter | No cross-request progress delivery |
| Oversized retained initialization projection | `200`, `-32602`, no session header | Release provisional slot/evidence; no partial snapshot |
| Resolver/hook failure, null/invalid decision | POST `200`/`-32603`; HTTP-only `500` | No mutation/stream; retire physical callback reservation on exit |
| Resolver/hook deadline or server/global execution capacity | Retryable `503` with safe Retry-After | Keep existing session/grants; release uncommitted allocations |
| Anonymous sessions disabled | Neutral `403` | No allocation; startup guidance for implicit accept-all |
| Per-session call/URI quota or per-owner call/store/evidence quota | `429` with safe Retry-After | No unrelated eviction or session retirement |
| Global store/evidence/output allocation unavailable | `503` for new allocation | Keep existing sessions; notification pressure uses bounded coalescing/shedding |
| GET not offered / no controller / no effective families | `405`, correct selected-view Allow | No mutation; not a capacity response |
| GET replacement/capacity failure | `503` | Preserve old eligible stream/session |
| Subscribe URI invalid | `200`, `-32602` | No grant |
| Subscribe no route or permission denied | `200`, neutral `-32002` | No new grant; fence denied existing grant |
| Subscribe transient authorization capacity/deadline | Retryable `503` | No partial grant; pending fenced renewal follows its bounded retry policy |
| Well-formed unsubscribe | `200`, `result: {}` | Remove establishing/active grant; no existence oracle |
| Expired GET/DELETE acceptance | Neutral `403`; no SSE/DELETE | Keep session; stale callback cannot revive stream |
| Accepted cancel notification | Empty `202` | Target cancellation only if its atomic reservation wins |
| Winning cancel of live finite POST | Completed zero-event `200` SSE | Logical result suppressed; physical worker accounting retained |

Dispatch order for GET/DELETE: select an explicitly supported header revision; verify facility availability; validate bounded session framing; fresh HTTP admission/owner resolution; then lookup/bind/action. A modern or sessionless selected revision keeps its stateless header policy and cannot refresh/mutate a legacy record. `Allow` and CORS methods reflect the actual selected facilities; OPTIONS is never session activity. A published-awaiting-ack record is usable as above; unpublished reservations are invisible. Missing version selection fails `400`, not an invented revision.

### 4.11 Observability decisions

Real POSTs retain `LifecycleObserver` request callbacks and actual method labels. A successful cancellation signal uses the existing `CancelationSignaled` event for the targeted actual method. GET/DELETE produce no fake semantic RPC lifecycle event.

Use existing `SubscriptionOpened`/`SubscriptionClosed` metrics for eligible legacy GET lifetimes, with MCP-specific reasons. Request-stream metric events remain RPC-stream events; do not invent a JSON-RPC method for GET. Server diagnostics count physical GETs in total open response streams and subscriptions so their existing inequality remains valid; document the distinction from RPC-only metric stream counts. Detached URI grants and session counts remain internal bounded state in the first package; no new per-session instrument, label or public session-ID diagnostic is proposed. Test collector balance, reason/outcome mapping and the downstream OTel switch.

## 5. Resource bounds and lifecycle ownership

These are **provisional design defaults**, to validate with stress tests and memory measurements before freezing. They are not current constants or measured guarantees.

| Bound | Proposed starting point | Surface |
| --- | --- | --- |
| Live/provisional sessions per server | 256 | Session config |
| Sessions per resolved owner | 16 | Session config |
| Session idle lifetime | 24 hours of actual quiescence | Session config; verify recovery before freezing |
| Absolute session lifetime | 7 days | Session config; verify recovery before freezing |
| Initialization public client-info/capability projection | 64 KiB and 4,096 nodes, also existing JSON limits | Session config byte cap; explicit initialization error on overflow |
| Initialization acknowledgement wait | 30 seconds, capped by session deadlines | Internal |
| Active/queued calls per session | 32, also bounded by global handler concurrency/queue | Internal |
| Request IDs/progress tokens retained for session correlation | 256 UTF-8 bytes each; exact logical-active set only | Internal profile bound; no history-triggered retirement |
| GET streams per session | 2, also existing global connection limit | Internal |
| URI grants | 64 per session, 64 per owner, 512 globally, plus partition quota | Internal shared ceilings |
| Retained URI bytes per session | 64 KiB combined | Internal |
| Retained request evidence | Configured header cap + 16 KiB control-body allowance; measure parsed overhead too | Internal input/accounting limits |
| Evidence budgets | Session: max(1 MiB, 2 × request-evidence cap); owner: max(2 MiB, 2 × session cap); global: max(16 MiB, 8 × owner cap) | Internal derived, overflow-checked ceilings |
| Anonymous sessions | Up to 64 globally within configured store cap; normal owner quotas also apply | Internal sub-budget |
| Queued notification data | One keyed frame per family/URI; 2 MiB per owner and 16 MiB globally | Internal, exact encoded-byte reservations |
| Static page / framework cursor | Existing byte/node/slot budgets; independent 2 KiB cursor cap | Internal; no artificial descriptor count |
| Maintenance/control | 4 executing maintenance jobs at most; bounded deduplicated due queue; 64 dispatches/second target ceiling | Internal provisional; aggregate handler limit still applies |

Reuse existing request/handler queue deadlines, stream queue/write/keepalive settings, custom application cursor-size cap, and connection limits. Framework cursors use the independent bound above. The following expansions of existing subscription settings must be explicit in Javadoc:

- `maximumSubscriptionDuration` caps each GET connection's total lifetime and each URI grant's lifetime from original creation. Renewal and duplicate subscribe do not reset those lifetimes. Session deadlines can end them earlier.
- `maximumSubscriptionAuthorizationDuration` caps both transport and URI-grant leases; shorter application expiry wins.
- `subscriptionAuthorizationTimeout` bounds queue-inclusive GET/DELETE transport admission and URI authorization. Owner resolution shares the appropriate operation deadline; ordinary POST resolution remains within request handling deadlines.
- `maximumSubscriptionsPerPartition` counts one logical legacy delivery registration per session with an active/establishing GET or retained URI grant. Bind its quota partition using the first reservation's freshly admitted identity and the existing authorization/quota partition derivation. Later identities must match that fixed partition; mismatch denies the new action rather than silently migrating quota. Modern units remain unchanged; independent connection/URI/owner caps also apply. Explicitly test cross-era starvation at the common pool.
- `subscriptionCatalogProjectionTimeout` applies if existing bounded catalog projection is used during notification maintenance. Endpoint-level legacy catalog invalidations do not require a fabricated RPC context or promise per-user projection/change detection.

Pre-admission control execution is bounded globally by the existing processor/connection limits; after admission, charge owner/control budgets. Maintenance has a bounded deduplicated due set, paced dispatch, and a fair reserved share of the existing handler capacity, never extra uncounted application execution. At default one-minute leases, the upper GET/grant count implies roughly 34 renewals/second before churn; the provisional 64/second dispatch ceiling needs latency/saturation measurements. Reject new demand that cannot be maintained under the declared envelope. Slow or uncooperative callbacks can exhaust physical reservations; retain those reservations and fail new demand safely rather than grow an executor.

Account for historical subscribe/GET evidence: original headers/body/metadata, framework context objects, and references still held by pending/running callbacks. Charge shared evidence once while retaining it, and release its reservation only when its last framework/callback reference can be retired. Reject oversized subscribe evidence before creating a grant. Existing JSON node limits also bound parsed context complexity; measure object overhead and avoid duplicating raw/parsed evidence unnecessarily. Framework byte accounting cannot measure an arbitrary graph behind an opaque application principal/context: document application responsibility to supply small, safe retained objects, and bound their reference count/lifetime. Do not claim a total heap guarantee for application-owned graphs.

Local maxima are ceilings, not reserved entitlements and cannot all be saturated together. Raising `maximumSessions` raises a record-count ceiling, not a promise of proportionally reserved memory. Document effective derived byte/control caps at startup; reject arithmetic overflow or inconsistent configuration. Per-owner limits prevent one owner from consuming an entire global evidence/grant budget. Allocation is atomic across local/owner/global counters; global exhaustion rejects the new allocation without retiring an unrelated session. Charge metadata, URIs, queued work and callback-held references, and measure heap overhead beyond encoded bytes.

The notification budget covers legacy GET invalidations only. POST progress continues under existing per-stream/global connection/request bounds and must be included in the overall stress measurement. On GET output pressure coalesce first, then shed an overloaded stream with the largest pending allocation; retain bounded dirty bits. All shared-channel changes require modern regression tests.

No event payload/history accumulates while disconnected. No lease, request, or session reference leaks after physical retirement. Test cleanup under slow/ignoring handlers and blocked writers; report unsettled work honestly under the existing shutdown grace contract.

### Relationship to Soklet 3.5.1

| 3.5.1 facility | Proposed expansion / migration meaning |
| --- | --- |
| Sessions, GET SSE, DELETE, progress and cancellation | Selected wire facilities return behind explicit endpoint revisions and fresh ownership/admission; no automatic source migration |
| Pluggable `McpSessionStore` / shared store | Not restored; node-local storage and learned affinity are required |
| Custom session-ID generator | Not restored; framework-minted opaque IDs, no routing-prefix promise |
| `McpSessionContext` application state | Not restored; use application-owned state keyed by your authenticated domain identity |
| 8,192 sessions / 24-hour idle and zero disabling limits | New bounded defaults; zero is invalid, null restores a default; compare effective caps before migration |
| Shared `SESSION_TERMINATED` reason | Not revived; generic token and MCP-specific reason mapping is explicit above |

Update the migration guide even if there are no known users of those old facilities. Do not claim shared-store, source, or session-state compatibility merely because wire operations return.

## 6. Implementation slices and exit criteria

Do these on the current branch. Keep changes reviewable; stage them for the owner. Do not commit, push, publish, or submit anything externally on the owner's behalf.

| Slice | Work | Exit criteria |
| --- | --- | --- |
| 0. Design preparation | Carry out the recorded 4.0 scope/API/naming decisions; superseding authority/gates; outcome/reason/default verification; released VS Code baseline smoke | Governing exclusions amended precisely; initial host/header risks recorded; agreed API surface and outstanding verification distinguished |
| 1. Completion | Exact revision-bearing plans/maps; allowlist/capability/processor; chosen empty-result behavior | Mixed-revision negative tests plus both profiles; existing completion suites pass; update affected docs |
| 2. POST progress | Profile SSE, lazy reporter, collision policy, finite/SSE disconnect distinction, simulator contract | Socket observes update before terminal; no-update JSON; cancel after progress; named display evidence; docs in this slice |
| 3. Static pagination | Resume-key cursor; page-local owner-indexed localization; preserve unpaged checks | Fit-one-page preserved; enumerate at 1,000/10,000 descriptors; independently exceed byte/node/slot budgets; measured policy/render counts; custom pages/modern unchanged |
| 4. Minimum sessions | Endpoint gate/config, owner store, projected snapshot, publish/ack, session CORS, cancellation bytes/races | Anonymous/binding/cap/recovery/socket checks; bounded churn/memory measurement; provisional API contains no delivery types; docs/migration updated |
| 5. HTTP transport control | Later controller/features/subsets; expiring GET/DELETE; replacement; method/CORS matrix | Denial/challenge/expiry/late callback; DELETE ownership; header policy; named usable GET consumer; no false `405` under capacity |
| 6. Notifications | Paced grants, coalescing/dirty bits, publisher/localization sources, newest-stream delivery, write-boundary fences | Actual host refresh, credential renewal and reconnect; saturation/reconcile/unsubscribe races; measured maintenance demand; modern subscription suites pass |
| 7. Release qualification | Final implemented API freeze; applicable official scenarios plus local/reference-client supplements; bounded host checks; compiled website examples | Exact-commit evidence for every claimed feature; explicit unsupported/untested cells; release gate selections include expanded legacy claims |

Completion is the smallest increment; pagination and POST progress are moderate refactors. Sessions and delivery are larger due to ownership and revocation races. Slice 4 is an implementation checkpoint; the agreed 4.0 target also includes HTTP transport control and notifications. Do not treat a completed checkpoint as completion of the full expansion. Publication approval still follows qualification of the implemented scope.

### Main source seams at the baseline

- [`McpEndpoint`](../src/main/java/com/soklet/McpEndpoint.java), [`McpServer`](../src/main/java/com/soklet/McpServer.java), and [`McpServerEndpoint`](../src/main/java/com/soklet/annotation/McpServerEndpoint.java): explicit version/configuration validation.
- [`SokletProcessor`](../src/main/java/com/soklet/SokletProcessor.java): generated endpoint/handler validation and exact revision subsets.
- [`DefaultMcpServer`](../src/main/java/com/soklet/DefaultMcpServer.java): execution plans and whole-catalog budget validation.
- [`McpHttpServerRuntime`](../src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java): classifier, admission, HTTP methods/CORS, request-control lifetime and delivery.
- [`McpServerRuntimeBridge`](../src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java): existing handler features and shared execution.
- [`Mcp2025ProtocolProfile`](../src/main/java/com/soklet/internal/mcp/protocol/Mcp2025ProtocolProfile.java), [`McpLegacyResponseWire`](../src/main/java/com/soklet/internal/mcp/protocol/McpLegacyResponseWire.java), and [`McpRequestSseStream`](../src/main/java/com/soklet/internal/mcp/protocol/McpRequestSseStream.java): selected-revision wire projection and SSE encoding.
- [`McpLegacySessionNegativeInventoryTests`](../src/test/java/com/soklet/McpLegacySessionNegativeInventoryTests.java): narrowly reviewed legacy allowance while preserving modern isolation.
- [`McpApplicationRequestRouter`](../src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java): per-revision completion views, atomic response/cancellation reservation, logical/physical ownership.
- [`McpLegacyRequestWireMapper`](../src/main/java/com/soklet/internal/mcp/protocol/McpLegacyRequestWireMapper.java): message-local adaptation and explicit effective snapshot handoff.
- [`McpOutboundChannel`](../src/main/java/com/soklet/internal/mcp/transport/McpOutboundChannel.java): clean no-message finish, keyed coalescing, byte reservations and write-boundary revocation.
- Localization/capability registry and canonical slot-plan/rendering seams: page-owner indexing, one effective notification-family set, all unpaged preflights.
- Metrics collector, diagnostics, bounded method allowlists and soklet-otel reason switches: balance and cardinality, without synthetic RPC labels.

These paths are starting points, not a requirement to put all new logic in the existing runtime class. New bounded internal coordinators should have clear ownership and independently testable state transitions.

### Documentation and governance work in each slice

Update the claims each slice changes as it lands: MCP.md, CHANGELOG.md, SECURITY.md, MIGRATING_TO_4_0.md, MCP_CLIENT_COMPATIBILITY.md, Javadoc and the corresponding website MCP/security/OAuth/operations/readiness pages. OAuth examples must authorize initialization/initialized/cancel/new subscription operations and return token expiry from the HTTP controller. Register every new/changed website Java fence with the snippet compiler and compile against the current qualified JAR.

| Slice | Reviewed artifacts/gates |
| --- | --- |
| 0, 4 | NI-03/DF-03 split and policy generator; SHA-pinned planning authority/completion plan; scoped transport and negative-inventory exceptions |
| Each API change | `api/mcp/provisional.includes`, owner/signature/reflection/public-evolution inventories; final includes/freeze only for implemented release surface |
| 2, 4–6 | Transport dependency baselines; lifecycle/version/privacy/finite-bound rows; exact cancellation and ordinary/legacy termination mappings; OTel compilation |
| 3 | SOK-NA-008 legacy exception, limits/accounting rows and whole-catalog preflight provenance; independent framework cursor codec/fuzz target |
| 4–6 | Separate session-enabled fixture/profile; legacy churn/maintenance soak supplements; keep existing stateless optional-session INFO assertion |
| 7 | Applicable conformance requirements/scenarios/supplements; compatibility host matrix; active-text census; snippet compilation; candidate workflow and upstream selection for expanded claims |

Use precise new legacy rows/allowances, not edits that weaken modern safety. Existing soak/profile assertions do not all fail merely because opt-in code exists; add evidence for the new claims while retaining their old valid scope.

## 7. Verification and claim gates

Use simulator tests for deterministic state/races and real-socket tests for stream commitment, headers, disconnection, and cleanup. Verify generated annotation endpoints and manual registrations use the same capabilities, policies, and wire output.

| Area | Required cases |
| --- | --- |
| Revision boundaries | Both exact 2025 profiles; mixed modern/legacy URL; conflicting framing; no undeclared revision/operation; no modern session lookup or wire leakage |
| Completion | Prompt/template targets; Unicode/partial/context arguments; limits; hidden target; forbidden suggestions; missing/disabled completer |
| POST SSE | Token/no token; integer/string tokens; collisions; monotonic progress; finite values; one final result; sanitizer/size limits; early/late errors; no modern fields |
| Cancellation/work ownership | Both reservation interleavings; finite zero-event SSE socket completion; cancel after first progress; logical IDs/tokens versus physical workers; cross-session reuse; pre-registration cancel; no initialize cancellation; notification's own empty `202`; no history-triggered retirement |
| Disconnect | Committed legacy SSE detaches; finite/queued disconnect still cancels; simulator parity; GET loss preserves bounded grants; modern unchanged |
| Sessions | Anonymous defaults/namespaces; issuer/tenant collisions; resolver/hook failure/deadline; foreign-format ID; malformed framing; owner/path/revision mismatch; publication and initialized `202`; GET-before-ack; initialized `_meta`; snapshot overflow/separation; live-work/GET quiescence; hard expiry/recreation; logical slot versus residual worker cleanup |
| GET/DELETE | Real HTTP admission; plain denial versus OAuth challenge; invalid Origin; preflight/Allow/exposed headers; fresh DELETE; renew before expiry; late callback cannot revive delivery |
| Grants/notifications | `{}` request responses; denied/missing URI neutral errors; effective localization family set; caller subset; duplicate-subscribe evidence replacement; establishing unsubscribe/reconcile races; paced saturation; newest GET; dirty-bit reconnect; coalescing; write-boundary purge; no Tasks/replay/URI leaks |
| Pagination | Fit-one-page unchanged; 1,000/10,000 descriptor enumeration and measured callback/encode work; independent byte/node/slot overflow catalogs; page-local localization; canonical cross-JVM keys; hidden/stale anchors; permission/locale/fallback changes; tiny application cursor cap; custom pages and unpaged modern fail-fast unchanged |
| Lifecycle/privacy | Shutdown with active callbacks/handlers/writers; synchronous reconciliation of establishing/active/detached grants; retained request/context byte caps and physical callback retirement; bounded global framework memory; no IDs/credentials/free-form reasons in logs or metric labels |

Run the existing supported JDK 17/21/25 CI matrix, API compatibility/freezes, public-evolution and lifecycle/version inventories, and modern MCP/Apps/Skills regressions affected by shared code. Broaden tests to address concrete shared-code risks, not to generate duplicate evidence. Local stress/fuzz checks are bounded; nightly CI owns longer fuzzing.

Maintain two fixtures: existing stateless mode with its optional-session INFO expectation, and a session-enabled mode with an explicit test identity or anonymous opt-in. Owner-binding/security tests are separate local real-socket evidence, not an official conformance claim.

| Evidence | Scope |
| --- | --- |
| Applicable pinned official completion/progress/session scenarios | Record RUN/PASS/FAIL or NOT_APPLICABLE by exact revision; applicability skips and CLI exit zero are not PASS |
| Static paging, cancellation, URI/list invalidations, security/bounds | Local supplements and pinned SDK real-socket harnesses where the suite has no scenario |
| November SSE/polling cases | Verify whether they actually exercise SSE; JSON/INFO or pending/not-scored does not establish priming/progress |
| Named released clients | Demonstrate actual display/refresh/cancellation/recreation on the claimed topology and record the selected revision |

Check fixture capabilities and independently validate schemas. Official selected passes remain selected evidence, not full-spec conformance. Pin applicable requirements and scenarios rather than inventing an official case for every feature.

Use concrete existing regression cohorts: `McpCompletionPublicRuntimeTests`, `McpCompletionWireParityTests`, `McpCompletionLocalizationPublicRuntimeTests`, `McpProgressAndCancelationRuntimeTests`, `McpSubscriptionPublicRuntimeTests`, authorization/scheduling/offer-boundary cohorts, `McpLocalizationSubscriptionRuntimeTests`, simulation capture/lifecycle cohorts and `SokletDirectMcpLifecycleTests`. New legacy fixtures supplement these; modern expectations remain intact.

Slices 4/6 require bounded churn/stress evidence before defaults freeze: initialization/recreation, slow and cancellation-ignoring callbacks/workers, GET reconnect, publish storms, saturation and shutdown. Measure retained framework bytes, object overhead, callback latency and total paged-enumeration/SSE cost; counts return to zero after physical cleanup. Apply existing resource-delta ceilings. This adds bounded local checks and nightly CI profiles, not another 24-hour local fuzz run.

Qualify named clients using only features they actually exercise: terminal results, observable progress, completion where the host exposes it, remembered capabilities, cancel during real work, GET updates, and session recreation after expiry. Record unavailable host features as untested rather than a Soklet pass. Use disposable fixtures and bounded runs; obtain any required account/network authorization before interacting with external hosts.

Keep concise durable conclusions, tool versions, source commit IDs, and reproducible commands in Git. Screenshots, raw traces, generated output, and run archives belong in ignored local output or CI artifacts, not the source repository.

The expansion reopens the affected API freeze and qualification surface. Final release pins and immutable candidate gates belong **after** core scope and fixes settle, using artifacts built from those exact commits. This plan does not claim the current release is approved or require the owner to repeatedly repin every development slice.

## 8. Recorded decisions and implementation verification

The owner has selected the full 4.0 expansion and the public API shape/naming described above. The checks below guide implementation and qualification; they do not reopen those decisions or request repeated owner approval. For a newly discovered issue, identify the section, a concrete failure scenario, severity, and the smallest effective correction. Record any necessary scope/public API amendment explicitly.

1. **Release scope:** Full expansion targets 4.0.0. Update exact superseding artifacts and implement every selected slice; retain the listed exclusions and distinguish target scope from qualified release claims.
2. **API:** Verify staged owners, explicit endpoint session revisions, the owner resolver, caller family subsets, and the neutral-token/MCP-reason mapping including downstream OTel compatibility. Include exact revision applicability in Javadoc and reject unsupported selections. Track implementation of every member in the agreed footprint.
3. **HTTP:** Test reserved-status remapping and completed zero-event finite cancellation. Verify SDK behavior, both reservation races, server retirement/revocation disposition and default recovery. These are explicit designs with required evidence.
4. **Host/version policy:** Can released VS Code supply the required version header? If not, qualify it unsupported or separately design authenticated version inference without a synthetic admission version/existence leak.
5. **Pagination:** Validate startup canonical/key-index representation, page-only slot lookup, budget fitting and measured total cost. No MAC or authorization snapshot is claimed; stronger protection would require a concrete threat, not opacity alone.
6. **Delivery:** Qualify actual resource updates and catalog refresh in named released 2025 consumers, supplemented by pinned SDK harnesses. Validate publisher hints, coarse caller-selected families, dirty bits, current authority across refreshed credentials, and the lack of a legacy subscription-ended notification. Report host limitations separately from server protocol behavior.
7. **Bounds/defaults:** Validate the proposed longer lifetimes, quiescent eviction, per-owner/shared ceilings, evidence sizing, aggregate maintenance capacity and cleanup under uncooperative callbacks. Freeze measured, qualified values, not estimates.
8. **Observability/deployment:** Verify GET metrics/diagnostic semantics without fake RPC labels; learned affinity and manual reconnect limitations; exact migration/claim wording and applicable evidence selections.

Suggested reviewer response format:

```text
Section:
Severity: blocking / important / suggestion
Concern:
Concrete request or race that demonstrates it:
Recommended correction:
Test or specification reference:
```

Reviewers can evaluate this document without executing the repository or receiving raw host traces/credentials. External review is advisory. This plan records the selected implementation direction; it does not authorize external submissions, commits, pushes or publication. Stage reviewable changes on the current branch for the owner throughout implementation.

## 9. Reference set

The design uses official specifications and the stated Soklet source baseline. Pin the exact schema/conformance-tool revisions when implementing; a live documentation page is not an immutable release artifact.

- [June transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) and [November transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
- [June resources](https://modelcontextprotocol.io/specification/2025-06-18/server/resources) and [November resources](https://modelcontextprotocol.io/specification/2025-11-25/server/resources).
- [November completion](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/completion), [progress](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress), [pagination](https://modelcontextprotocol.io/specification/2025-11-25/server/utilities/pagination), and [cancellation](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation). Verify the corresponding June definitions too; do not assume that one revision's schema proves the other.
- [Immutable Soklet source baseline](https://github.com/soklet/soklet/tree/bf9046f5384c367dfa2ddee2105f2f1ed3e4c8d1).
