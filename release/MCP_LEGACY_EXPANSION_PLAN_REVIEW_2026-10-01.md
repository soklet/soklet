# Review: MCP 2025 compatibility expansion plan

**Reviewed document:** [`MCP_LEGACY_EXPANSION_PLAN_2026-10-01.md`](MCP_LEGACY_EXPANSION_PLAN_2026-10-01.md), cited below as `plan:NNN`.

**Date:** 2026-10-01.

**Source baseline:** core `bf9046f5384c367dfa2ddee2105f2f1ed3e4c8d1` on `feature/mcp-2026-07-28`. This is the plan's own baseline; the tree was clean apart from the staged plan file. 3.5.1 behavior was read from `master`.

**Specifications:** the 2025-06-18 and 2025-11-25 spec pages and `schema.ts` from `modelcontextprotocol/modelcontextprotocol` at commit `3098fe94caa1b9e0afaaa6d30e040b61d5802471`. Citations look like `June transports:190`, meaning `docs/specification/2025-06-18/basic/transports.mdx` line 190 at that commit. `Nov` means 2025-11-25.

**Client evidence:** source read at pinned refs:

| Client | Ref |
| --- | --- |
| TypeScript SDK | v1.29.0 (`e12cbd70`) and v2.2.0 |
| Python SDK | v1.30.0 (`8c2fa6ea`) |
| rmcp | at Codex's pin `3e636cab` |
| Codex | `57a38c1b` |
| VS Code | `main` @ `c353edbf`, **not** the released 1.139.1 build |

Claude Code and the Claude cloud backend are closed source. Their behavior comes only from Soklet's own September 29 wire captures.

**Path abbreviations:**

- `Runtime` = `src/main/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntime.java`
- `Bridge` = `src/main/java/com/soklet/internal/mcp/protocol/McpServerRuntimeBridge.java`
- `Router` = `src/main/java/com/soklet/internal/mcp/protocol/McpApplicationRequestRouter.java`
- Every other path is relative to the core repo root.

**Method:**

1. 16 independent reviewers each took one lens: existing-API truth, current behavior, transport/lifecycle spec, utility spec, real clients, API conventions, freeze ripple, concurrency, security, bounds, pagination, modern interaction, internal consistency, verification, scope, and 3.5.1 history. They produced 187 raw findings.
2. Deduplication reduced these to 103.
3. Every finding then went through three adversarial verifiers: factual truth (default to refuted), already-settled (searching the plan and the project's decision records), and real consequence. A finding survived only if the truth lens confirmed it and at least two of the three lenses declined to refute it. 99 survived and 4 were refuted (§8).
4. Two critics then looked for gaps and answered the plan's §8 questions. Their 8 findings went through the same verification, and all 8 survived.
5. I hand-checked every blocking claim against source myself before writing this. In one place two verifiers were wrong; see §8.
6. Nothing was built or run, because there is no Java on PATH. All behavioral claims come from reading the code and specs.

---

## 1. Verdict

The plan's protocol instincts are good, and most of its factual claims check out:

- **Results:** results stay complete, with no chunk API and no replay.
- **Revisions:** every switch names an exact revision; nothing is controlled by a generic "legacy" flag.
- **Admission:** every request and every GET is freshly admitted, and a session ID is never treated as a credential.
- **Ownership:** a wrong-owner ID gets a neutral 404.
- **No invented protocol:** no RPC contexts are fabricated for GET or DELETE, and the RPC admission decision is not reused without an expiry.
- **Honesty about limits:** the plan says plainly that sessions are node-local, and it multiplies out its own provisional limits.
- **Baseline claims:** every §2.3 signature and every §1 baseline claim checked out. Section 6 lists what was verified.

**It is not ready to implement.** There are four problems.

1. **Several wire-level outcomes are undefined.** In combination with existing code, those gaps break clients the moment sessions exist. Examples:
   - The legacy adapter already uses HTTP 404 for method-not-found, and 404 is the spec's "your session is dead" signal.
   - Cancelling a finite JSON request has no defined response, and the existing cancel primitive leaves that connection open with no response.
   - Session termination reasons are placed on a shared, non-MCP enum that soklet-otel switches over exhaustively.
2. **With the proposed defaults, enabling sessions makes 2025 service *less* reliable than today's stateless mode.** Each of these can turn a working stateless client into a failing one: 1-hour absolute expiry, retiring a session after 4,096 request IDs, per-owner caps with no eviction and no DELETE, idle expiry that ignores open GETs, mandatory session IDs for clients that never received one, and missing CORS. The TypeScript and Python SDKs do not re-initialize on 404.
3. **The pagination design cannot deliver its own goal.** For catalogs too large for one response, a digest over the full localized view cannot be computed with the existing whole-response localizer. Recomputing that digest on every page makes full enumeration quadratic.
4. **Scope and governance are unresolved.**
   - No slice names a client that benefits from it.
   - The release target is never stated, yet the work lands on the 4.0.0 branch.
   - The plan contradicts governed 4.0 artifacts (NI-03, DF-03, and the pinned planning authority's "legacy session mode" exclusion) without a superseding decision.
   - It proposes public names that two existing CI gates reject.
   - It freezes all five new types at once, even though Slice 4 is described as a valid stopping point.

**Recommendation: split the plan.**

| Slice | Recommendation |
| --- | --- |
| 1 (completion), 2 (POST progress) | Proceed after the specific fixes in §4. Neither needs new public API. |
| 3 (pagination) | Redesign (§5) or drop. |
| 4 (sessions, cancellation) | Rework around the principle "a session must never fail where stateless would have succeeded" (§2, item 3). Pair it with an explicit release target, probably 4.1. |
| 5–6 (GET, subscriptions) | Gate behind evidence that a named 2025 host acts on GET notifications. No such host is recorded today. |

## 2. Fix before implementation

These are the items I would resolve in the plan text before any slice that touches them starts. Section 4 has the full detail for each.

1. **Reserve HTTP 404 (and 405) for session state** (§4 F1). This blocks Slice 4.
   - Every unadvertised legacy method today returns `404` with `-32601`: `Runtime:7791-7801` with the allowlist at `Runtime:4552-4556`. Catalog methods return the same on revisions where that catalog is absent.
   - `McpAdmissionRejection` also accepts any 400–599 status (`McpAdmissionRejection.java:88`).
   - Once an `Mcp-Session-Id` exists, a 404 obliges the client to discard the session and re-initialize (June transports:189-192). A host that calls `tools/list` on a prompts-only endpoint would loop until it hits the per-owner cap.
   - Fix: on session-bound legacy requests, use 404 only for session-lookup failure. Return JSON-RPC errors for accepted requests as HTTP 200. Remap or reject application 404/405 there.
2. **Define the bytes sent for a cancelled or terminated request** (§4 G1, G2). This blocks Slice 4.
   - `RequestControl.cancel` sets `responseCallback = null` without ever invoking it (`Runtime:12026-12033`). Microhttp has no handler-side abort and no timer for a dispatch in the HANDLING state, so a cancelled finite POST stays parked until the client gives up.
   - The plan's "final-response reservation wins" rule cannot be built on this primitive either.
   - Fix: specify a clean zero-event SSE response, or a 404 when the session is retired. Make the cancel conditional on winning the exchange reservation.
3. **Make sessions degrade, never fail harder than stateless** (§4 C1, F2–F7, G3). This blocks Slice 4. Concrete changes:
   - Serve a request without a session ID statelessly instead of returning 400.
   - When the store is full, issue no session ID rather than returning 429.
   - Before rejecting, evict the same owner's quiescent sessions.
   - Count an attached GET and any in-flight request as liveness.
   - Make the absolute lifetime long or off by default.
   - Drop the lifetime history of seen IDs, or store IDs as ranges.
   - Send a terminal error before closing POST SSE on server-initiated termination.
   - Publish the session before its ID goes on the wire.
   - Allow and expose `Mcp-Session-Id` in CORS in Slice 4, not Slice 5.
4. **Put session termination reasons on the MCP enum** (§4 B1). This blocks the API freeze.
   - `StreamTerminationReason` is non-MCP API. 4.0 removed `SESSION_TERMINATED` from it with a recorded rationale (`api/mcp/phase-0-shared-host-rationales.jsonl:12`).
   - soklet-otel has an exhaustive switch expression over it with no default branch (`soklet-otel/.../OpenTelemetryLifecycleObserver.java:657-661`, compiled with `release 17` against 4.0.0). Adding constants breaks that build. A released soklet-otel jar running against a newer core would throw `IncompatibleClassChangeError` the first time a span ends with a new reason.
5. **Define "anonymous" and per-owner accounting** (§4 B18). This blocks freezing `McpSessionConfig`.
   - The default admission controller accepts every caller as one shared anonymous identity.
   - The resolver sees only that identity, so it cannot tell clients apart.
   - As written, enabling sessions on a default server either rejects every `initialize` or collapses all clients into a single owner.
6. **Replace the pagination cursor design, or drop Slice 3** (§4 E1–E6). Use a resume key into the canonical order, fill each page to the existing byte and node budgets, localize only the page, and keep the whole-catalog startup checks for every revision that does not page.
7. **Make the local and global caps arithmetically consistent and owner-fair** (§4 I1–I3). Today, one owner within quota can exhaust the global request-ID and evidence budgets. This matters for Slices 4–6.
8. **Route completion per revision** (§4 D1). This must be fixed in Slice 1.
   - `CompletionPlan` drops the registration's completion revisions (`DefaultMcpServer.java:655-668`), and the per-revision view copies completion routes unfiltered (`Router:722-742`).
   - Widening validation alone would expose completers on revisions the application never enabled.
9. **Decide the release target and supersede the governed exclusions** (§4 A7, A8, J1, B3). This must be recorded in Slice 0.

## 3. Themes

**A. The plan specifies behavior in prose but leaves wire outcomes undefined.** The internal design text is careful. What is frequently missing is the exact response a client sees: the status code, the JSON-RPC code, the content type, and whether the connection closes. Examples:

- progress-token collision
- a repeated request ID
- a request arriving when the ID-history cap is reached
- each per-session cap
- the metadata cap
- owner-resolver failure
- cancel of an uncommitted request
- DELETE of a provisional session
- GET when no legacy family is configured
- `initialize` carrying a session ID
- subscribe failures

This matters more than usual here, because real 2025 clients treat status codes as protocol state: 400 and 404 trigger a session reset in VS Code, 404 means re-initialize in every spec-following client, and any status of 300 or above takes VS Code's connection to Error. **An outcome table keyed by condition** (§4 C3) would close most of these at once.

**B. Defaults are tuned for safety bounds, not for how clients actually behave.**

- The TypeScript SDK (1.29 and 2.2 legacy mode) and the Python SDK (1.30) do not re-initialize on 404. Only rmcp/Codex and VS Code do.
- No inspected client sends DELETE on close; TypeScript's `close()` only aborts.
- TypeScript stops reopening GET after two failed reconnects.
- Python waits forever on a POST SSE that closes without a response.

Each session expiry or retirement the plan introduces therefore becomes a user-visible failure in the most common SDK families. That is a regression relative to the stateless adapter those same hosts already pass on.

**C. The plan reverses approved decisions without naming them.** The predecessor plan (approved 2026-09-28) and the 4.0 governance records decided all of the following:

- no session store (predecessor:391-393)
- GET/DELETE return 405
- later requests carry empty client capabilities, pending an essential use case (predecessor:442-449)
- `subscriptionProtocolVersions` controls `subscriptions/listen` only (predecessor:513-523)
- no framework cursor codec (SOK-NA-008; V10 §9)
- `ABSENT_IN_4_0_0` for session-bound initialization and `MCP-Session-Id` (NI-03)
- "legacy session mode" excluded for 4.0 (`SOKLET_4_0_COMPLETION_PLAN.md:300`, SHA-pinned in `conformance/soklet-4.0-planning-authority.json`)

The plan may well be right to revisit some of these. It should do so in a short "decisions superseded" section, with the use case that justifies each reversal.

**D. The plan never mentions Soklet 3.5.1, though it largely restores 3.5.1's session feature set.** 3.5.1 had a pluggable `McpSessionStore`, a custom `sessionIdGenerator`, `McpSessionContext`, GET SSE, DELETE, progress, and cancellation. 3.5.1 users will meet this design as their direct migration path. The plan needs a mapping table that says what returns, what changes (for example, `0` no longer means "disabled"), and what is gone with no replacement (per-session application state, custom IDs, shared stores). It should also note that `SESSION_TERMINATED` is coming back with a narrower meaning.

**E. The governance ripple is much larger than §6/§7 suggest.** Slice 4 trips several gates and inventories that the plan does not name:

- `McpLegacySessionNegativeInventoryTests`, whose regex matches every proposed type name and also scans the signature ledgers
- `verify-mcp-transport-dependencies.mjs`, which rejects any field-bearing class or field whose name contains `session`
- the roadmap-readiness verifier, which forces NI-03 to remain `ABSENT_IN_4_0_0`
- the finite-bound, privacy-boundary, and limits inventories
- the frozen legacy conformance profile
- soak evidence
- the active-text census

None of these lets a defect through silently, because all of them fail closed. But they are real schedule cost, and the gates are the place where the reversals in theme C will actually be adjudicated.

## 4. Findings by section

Severity tags:

- **[Blocking]**: a protocol or interop break, a security or permission gap, unbounded resource use, or a wrong public API that would be frozen.
- **[Important]**: materially wrong, underspecified, or costly in a way an implementer would get wrong.
- **[Suggestion]**: an improvement.

Where verifiers narrowed a finding, the narrowed version is what appears here.

### A. Scope, value, sequencing (§1, §6)

**A1 [Important] No slice names a client that benefits from it.** (plan:17-47, 317-329, 578)

The §3 example is a dual-era URL. On such a URL, Claude Code selects `2026-07-28` through `server/discover` (`release/MCP_CLIENT_COMPATIBILITY.md:155`), so no legacy slice ever reaches it. The Claude cloud backend probes discover first as well; its dual-era choice is inferred, not observed. The real 2025 consumers differ sharply:

- **VS Code** speaks 2025-11-25 only. It uses completion, displays progress, sends `notifications/cancelled`, follows cursors, calls `resources/subscribe`, and handles list_changed (`mcpServerRequestHandler.ts:240, 267-278, 483, 544`; `mcpServer.ts:1359`). It is listed NOT TESTED in every compatibility round.
- **Codex** defaults to legacy 2025-06-18 (`protocol_mode.rs:11-21`), follows cursors and recovers from 404. It only *logs* progress, list_changed, and resource updates (`logging_client_handler.rs:63-91`).
- **Python SDK 1.30** never sends `notifications/cancelled`.

**Fix:** add a slice → consumer → evidence table to §1/§6. Make Slice 6 conditional on a confirmed consumer. Test Claude hosts only on 2025-only URLs.

**A2 [Important] VS Code, the most feature-complete 2025 consumer, probably cannot use the legacy adapter today.** (plan:136-141, 420, 452; pre-existing)

- VS Code sets no `MCP-Protocol-Version` on POST, GET, or DELETE (`extHostMcp.ts:466-475, 598-609, 426-429`; the header appears only for auth-metadata fetches at `:838`).
- Soklet answers any notification without exactly one version header with 400 (`Runtime:6433-6435`).
- VS Code moves to Error on any POST response of 300 or above (`extHostMcp.ts:509-519`).

So `notifications/initialized` probably fails at the baseline. This was inferred from code, not observed. VS Code is breaking a client MUST (June transports:241-243). But the spec also tells servers to use "the protocol version negotiated during initialization" when they can (June transports:251-254), and **sessions are the one mechanism that makes that possible**. This is a benefit the plan does not claim.

The plan's ordering makes it worse: it selects the revision from the header *before* GET/DELETE admission (plan:452), and `getProtocolVersion()` is non-null (plan:138). VS Code's GET and DELETE would therefore fail too.

**Fix:** smoke-test VS Code against the baseline in Slice 0. Then decide explicitly between two options:

- (a) when a well-formed `Mcp-Session-Id` is present and the version header is absent, use the session's negotiated revision. This needs a decision on rendering unknown IDs before admission (plan:455).
- (b) document header-less hosts as unsupported.

**A3 [Important] Remembered capabilities have no in-scope use.** (plan:44, 298, 310)

Every 2025 client capability (`roots`, `sampling`, `elicitation`, and November's `tasks`) belongs to a feature this plan excludes. That fails the predecessor's own "essential use case" test (predecessor:444-449). Exposing the raw snapshot through `McpClientCapabilities` would also report support the legacy adapter cannot serve. The Claude cloud 2025-11-25 `initialize` advertises `capabilities.extensions["io.modelcontextprotocol/ui"]`, so a shared handler would see Apps support on a legacy session.

**Fix:** drop remembered capabilities, keeping `clientInfo` if wanted. Or define a stripped, per-revision projection and test it.

**A4 [Important] Sessions add failure modes for hosts already qualified stateless.** (plan:355, 423, 430, 436, 544)

Once Slices 1–3 work without sessions (plan:354), Slice 4 adds cooperative cancellation plus remembered metadata (see A3). It also makes the ID mandatory for every request on that revision. That introduces 404s after restart, idle expiry, or node loss; 429s at caps; mandatory affinity; and retirement at the ID-history cap. VS Code and the TS-SDK clients do send cancellations, so the demand is real.

**Fix:** treat the session package as opt-in infrastructure whose failure modes are bounded by the §2 item 3 changes. Document the affinity requirement at the configuration switch.

**A5 [Important] Slices 5–6 carry most of the cost and should be demand-gated.** (plan:448-484, 505-525, 540-541)

These slices add three public types, two operation constants, GET leases with renewal, URI-grant leases, synchronous reconciliation of establishing registrations, round-robin delivery, a new quota unit, and most of §5's bounds. Delivery would still be best-effort with no history. The only recorded 2025 GET behavior is Claude Code receiving 405 and carrying on (`release/mcp-legacy-qualification-2026-09-29/REVIEW.md:82`). No recorded 2025 host acts on `list_changed` or `resources/updated`.

**Fix:** put an explicit gate before Slice 5: a pinned host version that observably refreshes after a GET-delivered notification. Tie it to DF-03's existing trigger wording.

**A6 [Important] The plan never states its release target, yet it works on the 4.0.0 branch.** (plan:531, 582)

- `pom.xml` is 4.0.0.
- The release README says no candidate gate has passed yet (`release/README.md:35-38`).
- `MIGRATING_TO_4_0.md:14-17`: 3.x reaches end of life when 4.0.0 publishes.
- The project already defers surfaces to 4.1 (`MCP.md:606, 617, 621, 719`).
- The predecessor plan was explicitly conditional: "If this ships in 4.0.0".

**Fix:** add a release-target line per slice, and state that 4.0.0 go-live does not wait on any of them. Slices 1–2 could join 4.0.0 only if they need no freeze or verifier-baseline change and the owner accepts the added qualification scope. Slice 4 likely belongs to 4.1, with its own freeze amendment (`verify-mcp-transport-dependencies.mjs:846-859` already says "outside the 4.0 baseline").

**A7 [Important] Sessions in 4.0 contradict NI-03, DF-03, and the pinned planning authority.** (plan:19-25, 531)

- NI-03 records session-bound initialization and `MCP-Session-Id` as `ABSENT_IN_4_0_0`, and `scripts/verify-mcp-roadmap-readiness.mjs:344-353, 392-395` refuses any other status.
- DF-03 sends session state to "a separately approved deployment or major-line design" (the same text appears in `mcp/MCP_IMPLEMENTATION_PLAN_V11.md:3196`). Its pre-release hedge is already stale, because it still says tools-only.
- `SOKLET_4_0_COMPLETION_PLAN.md:300` excludes "legacy session mode" and is SHA-pinned.

**Fix:** require a Slice 0 owner decision record that supersedes the exclusion, or retarget Slices 4–6. Split NI-03 so that replay, `Last-Event-ID`, and POST-result recovery stay ABSENT. Regenerate `conformance/MCP_ROADMAP_READINESS_POLICY.md`, and update `soklet.com/.../mcp-compatibility.md:12-22`.

**A8 [Important] The plan never mentions 3.5.1.** (plan:13-61, 542)

See theme D.

- **Docs that become false:** `CHANGELOG.md:123-131` ("does not issue session IDs, open GET/DELETE SSE"), `MCP.md:111, 126-129, 1917-1934`, `server-configuration.md:266`, and `MCP_CLIENT_COMPATIBILITY.md:388-391`.
- **Docs that lack a section:** `MIGRATING_TO_4_0.md` has no MCP-session section at all.
- **Ledger impact:** re-adding `SESSION_TERMINATED` fails `scripts/api-diff/verify.sh` until `api/mcp/current-incompatibilities.jsonl:107` is regenerated. So the ledger cannot go stale silently, but the plan should plan for that update.

**Fix:** add a "Relationship to 3.5.1" mapping table, and extend Slice 7 to cover CHANGELOG, MCP.md, the migration guide, and the ledger.

**A9 [Suggestion] Add a "decisions superseded" section.**

Theme C lists each reversed decision. For each, state the decision, its source, the reason for reversal, and the artifacts to amend.

### B. Public API (§2)

**B1 [Blocking] Session and cancellation reasons are placed on the non-MCP `StreamTerminationReason`, the MCP enum is left out, and a removed 3.5.1 constant comes back with a new meaning.** (plan:200-206)

- `StreamTerminationReason` belongs to the non-MCP allowlist (`api/mcp/non-mcp-public-api.allowlist:73`). 4.0 removed `SESSION_TERMINATED` because it "does not describe any non-MCP SSE termination path" (`api/mcp/phase-0-shared-host-rationales.jsonl:12`). In 3.5.1 the constant meant *any* session termination; the plan uses it for a successful DELETE only.
- MCP streams, subscriptions, and metrics report through the frozen `McpStreamTerminationReason`, which the plan never mentions.
  - It has `REQUEST_CANCELED`, `SUBSCRIPTION_AUTHORIZATION_EXPIRED`, and `SUBSCRIPTION_AUTHORIZATION_DENIED`.
  - It has nothing for session expiry or DELETE.
  - The exhaustive bridge switch (`Bridge:5126-5141`) and outcome switch (`Runtime:6576-6586`) have no target for the new values.
- **soklet-otel has an exhaustive switch expression over `StreamTerminationReason` with no default branch** (`OpenTelemetryLifecycleObserver.java:657-661`, `isError`). Adding constants breaks its build. If the constants arrive after soklet-otel 2.0.0 has shipped, an existing jar throws at runtime the first time a stream ends with a new reason.

**Fix:** add a §2.2 mapping table: token reason → `McpStreamTerminationReason` → `McpRequestOutcome`.

- Map client cancellation to the existing `REQUEST_CANCELED` / `CANCELED`.
- Put session-lifecycle reasons, if they are needed at all, on `McpStreamTerminationReason`, subject to the cardinality review that `mcp-public-evolution-inventory.json` requires.
- If the token enum really needs a value, add one transport-neutral `CLIENT_CANCELED`, amend the shared-host rationale, and coordinate a soklet-otel release.
- Do not revive the identifier `SESSION_TERMINATED`.

**B2 [Important] The observability surfaces are left undecided.** (plan:185-208, 572)

Sessions, GET streams, and grants are Soklet's first long-lived server-owned state. The plan does not decide:

- whether legacy GETs emit `SubscriptionOpened`/`SubscriptionClosed`
- whether accepted cancellations emit `CancelationSignaled`
- how GET/DELETE requests, which have no `McpRequestContext`, reach `LifecycleObserver`
- whether `McpServerDiagnostics` reports live sessions

`MCP.md:2622-2627` and soklet-otel tests currently promise that session instruments are gone (`OpenTelemetryLifecycleObserverTests.java:397-409`). Instrument counts are pinned (COUNT-001; `McpObservabilityPublicApiTests`).

**Fix:** decide in Slice 0: reuse the existing subscription events with the B1 mapping, or add reviewed session events, or record "no session metrics" as a decision. Add a positive observability row to §7.

**B3 [Important] Freezing `McpSessionConfig` freezes the whole transport-admission API.** (plan:91-92, 106-108, 535, 542, 582)

`McpSessionConfig.getTransportAdmissionController()` pulls in the context, the sealed decision, and the reuse of `McpAdmissionRejection`. So shipping Slice 4 freezes GET/DELETE API that has no runtime behind it. Meanwhile `McpPublicApiInventoryTests` and the reflection contracts fail locally on any unowned public type.

**Fix:** stage the freeze, using `api/mcp/provisional.includes` as Tasks did.

- Slices 1–3 change no signatures.
- Slice 4 adds `McpSessionConfig` without the controller (see B18 on anonymous), the owner-key mechanism (B9), `McpServer.getSessionConfig()`/`Builder.sessionConfig()`, and only the reasons Slice 4 can produce.
- The controller setter and getter (an additive change to a final class), the three transport types, and the subscribe operation constants arrive only if Slice 5 is approved.
- Each slice lands its own includes, structural-owner row, and reflection-test updates.

**B4 [Important] A server-wide set of session revisions duplicates per-endpoint gating and forces sessions onto every endpoint serving that revision.** (plan:71-82, 335-360)

Every other optional facility in 4.0 uses server-level callbacks plus a *per-endpoint* revision gate (`taskProtocolVersions`, `subscriptionProtocolVersions`, list-handler versions). The predecessor rejected a second, possibly conflicting version list (predecessor:273-275, 325, 333-336).

Scenario: `/admin/mcp` wants June cancellation; `/public/mcp` is a stateless, load-balanced catalog. Enabling June sessions for one endpoint turns them on for both. Requests to `/public` that land on another node get 404 and loop.

**Fix:** add `sessionProtocolVersions()` to `@McpServerEndpoint` and `McpEndpoint.Builder` as a legacy-only subset, and remove the revision set from `McpSessionConfig`.

**B5 [Important] One context and one decision serve both GET and DELETE, which leaves DELETE semantics undefined.** (plan:135-181, 460, 517)

For DELETE, `getNotificationTypes()` is empty and `isReauthorization()` is always false. `validUntil` has no meaning, and no status is defined for an `accepted(identity, now)` that has already expired by the time of the "currently valid immediately before retirement" check (plan:460). The DELETE timeout is borrowed from `subscriptionAuthorizationTimeout`.

**Fix:** keep DELETE at 405 in the first increment (spec-permitted: June transports:196-197) and make the trio GET-only. If DELETE is kept, define `validUntil` for it and the status for an expired acceptance, and give DELETE a session-owned timeout.

**B6 [Important] The transport controller and the owner resolver receive no cancellation token.** (plan:120-150, 458, 462)

GET renewals call the application repeatedly in the background. Reconciliation, stream close, DELETE, and shutdown all need to stop those callbacks. `McpSubscriptionAuthorizer` receives `McpInvocationFeatures` for exactly this purpose (`McpSubscriptionAuthorizer.java:33-36`; `Runtime:8993-8995`, `12036-12040`).

**Fix:** use `admit(context, McpInvocationFeatures)`. Document the owner resolver as a fast, local mapping that runs inside the same cancellable, deadline-bounded scope. This only helps callbacks that cooperate, but it costs nothing to freeze correctly.

**B7 [Important] Accepting a GET authorizes every configured catalog family, with no way to narrow per caller.** (plan:153, 302, 308, 482)

On the same endpoint, modern subscriptions notify only when the caller's own view changes; the public enum says "caller-visible". A multi-tenant application cannot accept a GET for resource updates while withholding tool or prompt `list_changed`. Its only options are to disclose timing to every tenant or to disable the family for everyone. The plan acknowledges the disclosure (plan:482 and Q2); what is missing is the hook.

**Fix:** add `accepted(identity, validUntil, Set<McpSubscriptionNotificationType>)`, validated as a subset of `getNotificationTypes()`. Update the enum Javadoc for legacy semantics.

**B8 [Important] The remembered snapshot has no channel apart from request `_meta`.** (plan:298, 310)

Client capabilities reach the context, interceptors, and handlers only through the mapped request metadata. The legacy mapper deliberately injects empty `clientCapabilities` into `_meta` (`McpLegacyRequestWireMapper.java:29-30, 73-77`). About eight bridge sites read `requestMetadata.clientCapabilities()`. Following plan:310 literally makes `getRequestMetadata()` disagree with `getClientCapabilities()`.

**Fix:** decide which surface is authoritative and name the mapper as a seam. This becomes moot if A3 drops the snapshot.

**B9 [Suggestion] Consider putting the owner key on `McpAdmissionIdentity` instead of adding `McpSessionOwnerKeyResolver`.** (plan:116-130)

The resolver is a pure function of the identity the application's controller just built. It runs on every session POST and every GET renewal, and it needs its own failure, timeout, and null semantics. An optional, validated `sessionOwnerKey` on the identity would follow the existing partition-key pattern (`McpAdmissionIdentity.java:47, 93`). It would remove one type and one callback, and would give "anonymous" a precise meaning: an identity with no owner key.

Trade-off: it adds session vocabulary to a frozen type shared with the modern path. `DerivedSubscriptionRequestContext` copies identity fields by hand (`Runtime:13118-13126`).

**Fix:** decide in Slice 0, either way. One reviewer preferred keeping the resolver, so that the two controllers producing identities share one derivation.

**B10 [Suggestion] Context shape.**

- `getNotificationTypes()` repeats `getEndpoint().getSubscriptionConfig()`, and it includes `RESOURCE_UPDATED`, which accepting a GET does not authorize.
- `isReauthorization()` diverges from the existing `getPreviousValidUntil()` (`McpSubscriptionAuthorizationContext.java:66`).
- `getEndpointPathParameters()` is missing.

**B11 [Suggestion] Naming.**

- "Transport" is defensible, since the spec calls this layer the Streamable HTTP transport.
- If DELETE is dropped, consider `McpSessionStreamAdmission*`.
- Define "client metadata" in Javadoc, or rename `maximumClientMetadataSizeInBytes`.
- `getOwnerKeyResolver()` versus the parameter name `sessionOwnerKeyResolver` is a small inconsistency.

**B12 [Suggestion] The "five new public types" count understates the freeze.**

By ledger accounting the proposal adds 8 owners (`$Builder`, `$Accepted`, and `$Rejected` are separate), about 31 members, and 5 enum constants, one of them on the non-MCP allowlist. The B1/B2 surfaces, and the simulator's documented "off-network MCP POST simulation" contract (`Simulator.java:143-163`), are not inventoried.

**Fix:** replace the prose count with an exact inventory.

**B13 [Important] Reusing `subscriptionProtocolVersions` reverses an approved decision and misses two code facts.** (plan:301, 314-333, 546-553)

1. `SokletProcessor` rejects any non-2026 value at compile time (`SokletProcessor.java:1036-1038, 3622-3627`), yet it is missing from the seams list.
2. The bridge and server wire event sources whenever the set is merely non-empty (`Bridge:1170-1199`; `DefaultMcpServer.java:631-636`). A legacy-only selection would therefore register the task and localization publishers on an endpoint where no revision can deliver task notifications.

**Fix:** record the supersession of predecessor:513-523. Use per-era predicates. Add `SokletProcessor` and its tests to the seams, and list every Javadoc and website surface to rewrite.

**B14 [Important] Turning on legacy subscriptions also attaches the framework localization source, which the plan's family selection ignores.** (plan:153, 357, 476-482)

The capability registry advertises `listChanged` for any localized catalog kind, whether or not that family is in `McpSubscriptionConfig` (`McpServerCapabilityRegistry.java:196-230, 260-272`). Legacy `initialize` could therefore advertise `tools.listChanged`, while `getNotificationTypes()` omits it. The result is either a false capability or delivery the GET's admission never covered.

**Fix:** compute one family set that drives advertisement, `getNotificationTypes()`, and dispatch, and state whether it includes the localization source. Say that task events are never routed to legacy sessions.

**B15 [Suggestion] Unsubscribe breaks the `McpAdmissionContext` invariant.** (plan:300)

The plan exposes the URI in `getRequestedResourceSubscriptionUris()` while `isResourceSubscriptionsIncluded()` is false. The current Javadoc pairs the two (`McpAdmissionContext.java:100-115`).

**Fix:** leave the list empty for unsubscribe and expose the target through `getOperationName()`, as `resources/read` already does. Specify `getOperationName()` for both subscribe and unsubscribe.

**B16 [Suggestion] Owner-key collisions share the per-owner cap across tenants and undo the spec's user-to-session binding.** (plan:126)

**Fix:** document a reference resolver built from the existing HMAC(issuer‖tenant‖subject) deriver in `mcp-oauth.md:93-100`. Add a §7 case where two issuers share a subject value. Skip the "resolver returns the partition key" diagnostic suggested in one report: the documented pattern is a valid owner key, so that diagnostic would flag correct code.

**B17 [Suggestion] An explicit subscription authorizer is required even when no legacy family ever calls it.** (plan:357)

For catalog-only legacy families the authorizer is never invoked. An explicit `denyAllInstance()` satisfies the check safely.

**Fix:** require the authorizer only when a legacy revision selects `RESOURCE_UPDATED`, or document deny-all as the intended choice.

**B18 [Blocking] "Anonymous" is undefined, and with the default accept-all admission controller, enabling sessions breaks or collapses ownership.** (plan:89, 104-105, 118-130, 352-358, 498-499)

Soklet already has three meanings of anonymous:

- no principal (`isAuthenticated()`)
- no authorization partition key
- the shared `ANONYMOUS` instance that `acceptAllInstance()` returns for every caller (`McpAdmissionIdentity.java:51-52`; `McpServer.java:391-393`)

The resolver sees only the identity. So on a default server:

- If anonymous means `!isAuthenticated()`, every `initialize` is rejected, and the plan defines no status for that.
- If anonymous sessions are allowed with an obvious resolver, every client in the world becomes one owner, and the 17th concurrent session anywhere gets 429.
- If each anonymous session is its own owner, the per-owner cap does nothing.

The plan does flag the "anonymous policy" as open (plan:535, 590). What it misses is that the term itself has no definition to decide on.

**Fix, in Slice 0:**

- Define anonymous as `!identity.isAuthenticated()`.
- State whether the resolver runs for anonymous identities, and namespace its result.
- Give anonymous owners a sub-cap below `maximumSessions`.
- Specify the HTTP refusal.
- Extend the existing implicit-accept-all startup diagnostic (`McpServer.java:1081-1082`) to session configuration.
- Document that public endpoints must put a client-distinguishing input, such as the documented trusted-proxy IP partition, into the identity.

### C. Configuration and dispatch (§3)

**C1 [Important] Making the session ID mandatory strands every client that initialized before sessions were enabled, and the 400 has no client recovery.** (plan:355, 420, 430)

Consider a client that initialized while the server, or the node that served it, was stateless. It holds no ID. The spec's re-initialize rule applies only to a 404 on a request that *carried* an ID (June transports:189-192), and no inspected client re-initializes on 400:

- TS `streamableHttp.ts:480-541`
- Python `streamable_http.py:372-382`
- VS Code `extHostMcp.ts:509-518`
- rmcp only on a session-expired 404

So enabling sessions, or rolling that change through a fleet, breaks every live 2025 connection until each user reconnects by hand. Session IDs are not credentials (plan:410), so requiring them adds no security.

**Fix (preferred):** on a session-enabled revision, serve a non-`initialize` request without an ID statelessly: no snapshot, cancellation ignored, and GET/subscribe/DELETE rejected. Keep 400 for malformed or duplicate headers. Make the fallback observable (a metric or debug log), because it also hides header-stripping misconfigurations such as a missing CORS expose.

**Fix (minimum):** document that enabling sessions requires all clients to reconnect and that mixed fleets must drain. Add a §7 case either way.

**C2 [Important] The GET/DELETE dispatch matrix is incomplete.** (plan:354-357, 420, 452, 454)

Undefined cases:

- a GET when a controller exists but the revision or endpoint has no legacy notification configuration (the spec requires SSE or 405: June transports:126-128)
- GET/DELETE without a version header when the session knows its revision
- a header naming a sessionless legacy revision, or the modern revision, at a shared URL
- the `Allow` value when DELETE is enabled for June only
- `Mcp-Session-Id` sent on a sessionless revision (today it is "ignored and never stored", `MCP.md:1926`; say so)

Era classification today needs a POST body (`McpLegacyHttpWire.classify`).

**Fix:** add a decision table keyed by method × version header × controller × notification configuration × session ID. Separate "SSE not offered" (405 with `Allow`) from session errors (400/404) and admission errors (401/403).

**C3 [Important] The outcome table is missing.** (plan:128, 384, 436, 502, 504-507)

There is no wire result for any of these:

- progress-token collision
- repeated request ID
- the request that hits the ID-history cap, and its running work
- the 32-call, 2-GET, and 64-URI per-session caps
- the 16 KiB metadata cap
- resolver failure or timeout

The plan does specify per-owner and global allocation caps (plan:423) and bad cursors (plan:400).

**Fix:** add one table with the condition, the HTTP status or JSON-RPC code, the side effects (session kept or retired, work signaled), and the termination reason. §7 cannot assert outcomes that are unspecified.

### D. Completion and POST progress (§4.2–4.3)

**D1 [Important] Completion routes are endpoint-wide, not per revision.** (plan:292, 306, 376-378, 536)

- `CompletionPlan` is built without `getCompletionProtocolVersions()` (`DefaultMcpServer.java:655-668`).
- `resourceView` passes completion routes unfiltered (`Router:722-742`).
- The only gate is the per-view `completions()` boolean (`Runtime:4717`), which is safe today only because every completer is 2026-only.

Scenario: prompt P is declared for {June, 2026} with completion for {2026}. Prompt Q has completion for {June}. The June view now advertises completions, and P's completer becomes callable from June. Template completers have no revision check at all.

**Fix:** carry completion revisions in `CompletionPlan` and build per-revision route maps. Add `completion/complete` to the legacy allowlist (`Runtime:4552-4556`) and `completions` to the hand-built legacy `initialize` capabilities (`Runtime:5520-5530`). Add cross-revision subset tests.

**D2 [Important] A listed template without a completer returns HTTP 400 with `-32602`.** (plan:376-378, 564)

Advertisement is endpoint-wide. A prompt without a completer returns `values: []` with 200, but a listed template without one returns `-32602` with HTTP 400. This is deliberate, and tested in `McpCompletionPublicRuntimeTests.java:156-185`. VS Code requests completion for template variables from its picker and treats any status of 300 or above as a connection error. The spec lists `-32602` for an invalid prompt name, not for a template that has no suggestions (Nov completion:175-179).

**Fix:** on 2025 profiles, return `{"completion":{"values":[]}}` for a template or argument that is listed for the revision but has no completer. Keep `-32602` for unknown or hidden references. Say how limiter denial is surfaced, consistent with F1. Rate limiting is already mandatory whenever completion exists (`DefaultMcpServer.java:361-364`).

**D3 [Important] "Disconnect is not cancellation" changes documented baseline behavior and removes the only cancel path stateless clients have.** (plan:299, 390, 537)

Today every MCP POST, legacy included, is cancelled when the client disconnects (`Runtime:1736-1746`; the cancel at `Runtime:12016-12045` cancels the task and removes queued work). The docs say so (`mcp-progress-and-subscriptions.md:29`; `mcp-server-operations.md:108`). The spec's SHOULD NOT applies only once the server has opened an SSE stream (June transports:101, 114).

Slice 2 introduces the change before sessions exist. Stateless legacy calls would therefore lose all cancellation, and queued work would run just to produce discarded results.

**Fix:** limit detach-without-cancel to POSTs already committed to SSE, and to session-bound requests where `notifications/cancelled` exists. Keep disconnect-cancels for stateless finite calls and for queued work that has not started. List this as a behavior change, and correct the §1 and §3 "Preserved" and "current behavior" cells.

**D4 [Important] A progress-token collision has no defined outcome, and "active" may outlive the request from the client's point of view.** (plan:384, 390, 436, 565)

Both specs put token uniqueness on the sender and let the receiver skip progress (June progress:17-18, 64-65). The plan keeps registry entries until the handler *physically* exits (plan:390, 436). A client that reuses a token after cancellation or after a final response is acting legally, yet would be flagged.

**Fix:** on collision, process the request normally with an inert reporter. Never reject it, cancel it, or reroute its progress. Define token activity as running from registration until the earlier of final-response reservation or the cancellation fence. Add both cases to §7 with these expected results.

**D5 [Suggestion] Say that legacy POST SSE commits lazily, as the existing stream does.** (plan:382, 386)

A token only enables the reporter; the first accepted update commits SSE (`Runtime:11736-11806`; `McpRequestSseStream.java:38-41`). Otherwise, an error thrown before any progress lands inside a 200 `text/event-stream`, which contradicts plan:386 and the golden HTTP contracts.

**D6 [Suggestion] Define simulator `close()` semantics for legacy.** (plan:390, 559)

`McpSimulation.close()` "simulates a client disconnect" and cancels (`McpSimulationRuntime.java:257-286`). §4.3 changes what disconnect means for legacy calls.

**Fix:** list `McpSimulation.close()` and `Simulator.startMcpRequest` in §2.4. Define close and completion semantics for legacy POSTs, and say whether sessions and GET/DELETE can be simulated, or that they explicitly cannot. A single `SimulationGeneration` already spans calls (`Soklet.java:1343-1354`), so sessions are achievable in simulation.

### E. Static pagination (§4.4)

**E1 [Blocking for Slice 3] For the catalogs this slice exists to unlock, a digest over the full localized view cannot be computed.** (plan:396-400)

The existing localizer renders one whole response and fails before its first callback when the untouched document is larger than `maximumResponseBytes`, which defaults to 4 MiB (`McpLocalizationRenderer.java:123-127`). It also fails when the number of resolved slots exceeds 32,768 (`DefaultMcpServer.java:1163-1166`). Its `Outcome` returns one document, so "hash/scan incrementally" does not fit it either. For catalogs past either ceiling, every page is either served in the default text or fails, depending on policy. Catalogs under the ceilings still localize, at the cost in E2.

**Fix:** do not digest localized bytes. Localize only the selected page slice; slot resolution already rebuilds positions by owner identity (`McpCanonicalLocalizationPlan.java:119-135`). If you want one locale across pages, carry it as a claim to compare. Never route it through `getContinuationLocale()` or `getResourceListCursor()`.

**E2 [Important] Recomputing the whole view on every page makes enumeration O(N²/P).** (plan:398-400, 538, 571)

Each page re-runs the caller-aware evaluator over all N registrations, then localizes, encodes, and hashes all N, to return P entries (`Runtime:5276-5311`).

| Catalog | Pages | Evaluator calls per enumeration |
| --- | ---: | ---: |
| 1,000 tools (fits in one response today) | 8 | 8,000 |
| 10,000 tools | 79 | 790,000, plus ~870 MB encoded and hashed (estimate) |

With slow evaluators, even page 1 can miss the request deadline, so "Large catalogs enumerate completely" is unreachable for the very catalogs targeted. Per-request cost is no worse than today's unpaged list. The new costs are the total per enumeration and the removal of the old size ceiling.

**Fix:** use a resume-key cursor containing: format version, kind, a startup fingerprint per (path, revision, kind) over canonical descriptors, and the last emitted stable key. On each page, evaluate only the registrations after that key. Reject unknown or invisible keys with the same neutral `-32602`, which closes a hidden-name existence oracle. Precompute pages outright for static catalogs with no localization and no caller-aware policy; the registry already holds the unfiltered list (`McpServerCapabilityRegistry.java:356-358`).

**E3 [Important] The full-view digest invalidates cursors spuriously.** (plan:400, 404)

Clearest case: a transient `USE_DEFAULT_TEXT` fallback, or a page rendered canonically at the terminal boundary, invalidates the next cursor even though nothing changed. Also, "canonical" is never defined. The only existing catalog digest hashes wire bytes (`Runtime:6863`), and application schemas built with `McpJsonObject.fromMembers(Map.of(...))` keep `Map.of` iteration order, which can differ per JVM. That breaks cross-node continuation. A resume key avoids all of this. If any digest remains, compute it at startup over sorted-key canonical JSON of the untranslated descriptors (`McpRequestStateCanonicalJson`).

**E4 [Important] A fixed cap of 128 descriptors per page splits catalogs that fit in one page today.** (plan:394, 398, 511)

Today a legacy catalog of up to 100,000 JSON nodes is served in one page. Some clients do not follow `nextCursor`:

- TS 1.29 `listTools()` (`index.ts:836-843`)
- TS 1.29's built-in `listChanged` refresh (`:275-279`)
- Python 1.30 `list_tools()` (`session.py:532-560`)

These are page-level primitives, so any naive host or script built on them silently loses tool 129 and beyond.

**Fix:** fill each page up to the existing byte and node budgets with no fixed count, so catalogs that fit stay single-page with no `nextCursor`.

**E5 [Important] Whole-catalog checks live in three places, and relaxing them can break modern `tools/list`.** (plan:394, 398, 549)

Only `DefaultMcpServer.java:644, 3015-3050` and `Bridge:1133-1143` ignore revision. The runtime precompute and preflight (`Runtime:863-925, 970-1012`) run per revision. Modern stays single-page (plan:394), so relaxing the endpoint-wide checks would let a dual-era endpoint start with a catalog that every 2026 `tools/list` then fails to serve. Claude hosts on that URL would lose their tool list. Note that the aggregate check is already skipped when a catalog access policy or an Apps projection is configured (`DefaultMcpServer.java:644-645`).

**Fix:** state that whole-catalog fail-fast stays in force for every (endpoint, revision) that serves an unpaged catalog. Name all three seams. Replace the precomputed legacy list responses with per-page construction.

**E6 [Important] Framework cursors cannot reuse the application cursor cap.** (plan:513)

`maximumCursorSizeInBytes` accepts any value of 1 or more (tests use 8), and it applies to both incoming and outgoing cursors (`MCP.md:892-905`). A framework cursor carrying a digest is about 46–50 characters, so a server that set a small cap for its own cursors would fail every legacy page.

**Fix:** give framework cursors a fixed internal bound, or fail construction when the configured cap is too small.

**E7 [Suggestion] The plan reverses the settled "no framework cursor" decision without saying so.**

SOK-NA-008 (`mcp/MCP_CONFORMANCE_MATRIX.md:1957`, pinned by `verify-release-matrix-closure.mjs:95`) and V10 §9 (`mcp/MCP_IMPLEMENTATION_PLAN_V10.md:2815-2821`) both say there is no framework cursor codec. `MCP.md:135, 154, 905` and `mcp-compatibility.md:174` say cursors are application-owned. MCP-PAGE-005 and SOK-LIST-001 are modern-only rows and stay valid.

**Fix:** add a short reversal note scoped to 2025 static catalogs. State that framework cursors never use `McpProtectionConfig` or request-state keys. That also answers Q5 (§7).

**E8 [Suggestion] An unkeyed view digest is a stable fingerprint of the caller's authorization view.** (plan:400-404)

A per-cursor nonce stops cross-session linking but not offline guessing against a leaked cursor. A resume key without a view digest avoids the issue. Otherwise, document that cursors are sensitive and should not be logged.

**E9 [Suggestion] Wording fix.** The cursor carries an absolute position, so nodes need not share page limits. Drop "page limits" from plan:404, and reserve room for the bytes of `nextCursor` when fitting a page.

**E10 [Suggestion] Keep cheap cursor rejection before admission.** Run syntactic checks (presence, UTF-8, size, encoding, format version) before admission, and view-dependent checks after it.

### F. Sessions (§4.5)

**F1 [Blocking] 404 collisions.** (plan:355, 416-424)

There are three sources of 404 or session-reset-inducing statuses on session-bearing requests:

1. **Soklet's own method-not-found.** It returns 404 (`Runtime:7791-7801`) for:
   - anything off the legacy allowlist (`Runtime:4552-4556`), which today includes `completion/complete` and `resources/subscribe`
   - a missing catalog (`Runtime:4607-4616, 4645-4650, 4686-4689`)
   - `resources/read` routing (`Runtime:4857-4863`)
2. **Application admission rejections.** These may be any status from 400 to 599 (`McpAdmissionRejection.java:88`) and are rendered verbatim (`Runtime:7115-7149`). An application 404 means "session gone" to the client. An application 405 on GET means "no SSE at this endpoint", and on DELETE "termination not allowed" (June transports:126-128, 196-197).
3. **Soklet's 400s for ordinary JSON-RPC errors.** Invalid params and resource-not-found are returned as 400 (`Runtime:7806-7838`). VS Code treats a 400 or 404 on a session-bearing POST as session expiry, re-initializes, and retries once (`extHostMcp.ts:510-519`; `mcpServer.ts:1414-1416`). Without a session it already moves to Error, so the 400 mapping already hurts VS Code today.

**Fix:** on session-bound legacy POSTs:

- Use 404 only for session-lookup failure.
- Return JSON-RPC errors for requests accepted for processing as HTTP 200 with `application/json` (or in-stream after SSE commitment).
- Reserve 400 for framing errors and a missing ID.
- Treat application 404/405 as framework-reserved: remap to 403, or fail closed through the policy-hook error path, and document this in `McpAdmissionRejection`.
- Correct plan:421 ("Application-selected validated `401`/`403`") to the real 400–599 range.
- Record this as a legacy-profile exception to `MCP.md`'s modern status table.
- Add a revision-boundary test asserting that no session-bearing legacy response other than the neutral unknown-session case uses 404.

**F2 [Important] Expiry and retirement surface as unrecovered 404s in TS-SDK and Python-SDK hosts.** (plan:428, 430, 436, 499-505)

- **TS 1.29:** throws on a non-OK response and never clears `_sessionId` (`streamableHttp.ts:486-551`). `connect()` on the same transport skips `initialize` (`index.ts:485-489`).
- **TS 2.2:** has no 404 session recovery outside auth.
- **Python 1.30:** turns the 404 into "Session terminated" (`streamable_http.py:372-378`).
- **rmcp and VS Code:** re-initialize and retry.

Every rule that generates these 404s (the 1-hour absolute lifetime that "always wins", 15-minute idle expiry that ignores an attached GET, retirement at 4,096 IDs) becomes a recurring, user-visible failure in the most common SDK families. In stateless mode the same hosts never fail this way. These clients violate a MUST (June transports:189-192), but that does not help the user.

**Fix, before defaults are frozen:**

- Count an attached, authorized GET and any in-flight request as liveness.
- Make the absolute lifetime long or off by default; 3.5.1 used a 24-hour idle and no absolute cap.
- Never retire while work is active; stop new admissions and drain instead.
- Store sequential integer IDs as ranges.
- Make TS-SDK and Python-SDK expiry-recreation runs explicit §7 gates for the default values.

**F3 [Important] Idle expiry ignores open GET streams.** (plan:428, 456, 472, 500)

A host that subscribes and then just listens is expired at 15 minutes. Its GET is closed and its grants are removed, with no notification. TS reconnects the GET, gets 404 twice, and stops (`streamableHttp.js:141-145, 221-229`). Clients that ping more often than the idle window are unaffected (June ping:57). The in-flight case applies only when `requestTimeout` exceeds the idle duration.

**Fix:** define idle as: no in-flight request, no admitted GET holding a valid lease, and no client message for the idle duration.

**F4 [Important] Caps with no eviction, plus reclamation only by idle expiry, lock out owners and then the whole server.** (plan:356, 423, 436, 498-500)

No inspected client sends DELETE on close, and in the POST-only package DELETE returns 405 anyway. Every client restart therefore pins a slot for 15 minutes. A service principal running more than 16 agents, or one developer reloading a host 17 times in 15 minutes, gets 429. Global exhaustion needs roughly 17 abandoned sessions per minute across 16 or more owners. Separately, 429 is the wrong status for global capacity: existing capacity rejections use 503 (`Runtime:7019-7031, 7063-7075`), and 429 is used for rate limiting (`:7165`).

**Fix:**

- Before rejecting, reclaim expired records, then evict the same owner's least-recently-used *quiescent* session (no active entries, no attached or establishing GET) past a minimum idle age. The plan forbids only cross-owner eviction.
- On global exhaustion, answer `initialize` with no `Mcp-Session-Id` and omit `resources.subscribe` and legacy `listChanged` (spec-legal: "MAY assign", June transports:176). Or return 503 with `Retry-After`.
- Keep 429 with `Retry-After` for the per-owner cap.
- Release the session-count slot at *logical* retirement and leave worker accounting to the dispatcher. Without this, a token-ignoring handler blocks re-initialization at the cap.

**F5 [Important] Server-initiated termination closes POST SSE without a JSON-RPC response.** (plan:386, 428, 442-444)

The spec permits this on session expiry (June transports:107-109; Nov:121-123). Client reality: Python 1.30 has no default read timeout and reconnects only if it saw an event ID, so the caller waits forever (`session.py:284-291`; `streamable_http.py:455-458`). TS waits out its request timeout. Shutdown in this way is existing behavior, but expiry, ID-history retirement, and a DELETE racing in-flight work are new.

**Fix:** close silently only on explicit client cancellation. For server-chosen termination, either let in-flight requests drain under their own deadlines, or reserve the terminal slot and send a sanitized JSON-RPC error on the stream if it is still usable. Reconcile plan:428 with the existing graceful-drain contract.

**F6 [Important] Session publication and activation race the client's next request.** (plan:412, 422, 454)

For a finite response, completion is observed only through a body-termination listener (`Runtime:12402-12432`). Publishing at that point opens a window in which the client already holds the ID, misses the lookup, gets 404, and must re-initialize. Separately:

- No status is defined for ordinary requests on a published but unacknowledged session.
- Activation is not required to be visible before the 202 for `initialized`.
- Python calls `start_get_stream()` before POSTing `initialized` (`streamable_http.py:567-569`).
- VS Code opens GET immediately after the `initialize` response and disables GET for good on any status of 400 or above (`extHostMcp.ts:526-527, 593, 627`).

**Fix:**

- Insert the record, in an awaiting-acknowledgement state, before the `InitializeResult` bytes are handed to the transport. Retire it on delivery failure or at the handshake deadline.
- Make activation visible before the 202 is offered.
- Once published, accept ordinary requests and GET, treating `initialized` as an acknowledgement rather than a gate. If a gate is kept, never answer with the neutral 404; 3.5.1 used a non-404 JSON-RPC error.

**F7 [Important] Status-table edge cases.** (plan:410, 420, 422)

1. **Foreign IDs.** A spec-valid ID in a format other than Soklet's (visible ASCII, from a previous server at the same URL) gets 400 forever, where 404 would trigger re-initialization.
2. **Revision mismatch.** An owner-verified revision mismatch gets 404 while the server keeps the session, which leaks a per-owner slot on every loop.

**Fix:** use 400 only for spec-level header violations: missing where required, duplicate, empty, non-visible-ASCII, or oversized. Treat other well-formed IDs as unknown (404 after admission, no lookup). For an owner- and path-verified version mismatch, either return 400 or retire the session together with the 404.

**F8 [Suggestion] Define `initialize` that carries an `Mcp-Session-Id`.** (plan:412, 418-424)

Fixation is ruled out because IDs are server-minted. What is missing is a table row and a definition of "contradictory reinitialization".

**Fix:** prefer ignoring the header on `initialize` (no lookup, no mutation) over returning 400. The TS SDK keeps sending a stale ID on re-initialize (`streamableHttp.js:67-68, 309-311`). Run owner resolution unconditionally before lookup on every path.

**F9 [Important] Session CORS is deferred to Slice 5, but Slice 4 already needs it.** (plan:355, 452, 539)

- `MCP_PREFLIGHT_REQUEST_HEADERS` excludes `mcp-session-id`.
- The exposed headers are only `WWW-Authenticate` (`Runtime:353-358, 7289-7295, 7441-7443`).
- The docs promise that legacy-header preflights fail closed (`mcp-security-and-policy.md:157`), and raw goldens pin that.

With sessions on, a browser-hosted client cannot read the ID (so it gets 400), or cannot send it (so preflight gets 403).

**Fix:** move session CORS into Slice 4. Allow `mcp-session-id` in preflight and expose `Mcp-Session-Id` whenever sessions are configured for the endpoint. Update the docs and goldens. A GET preflight for `Last-Event-ID` belongs to Slice 5.

**F10 [Important] Affinity guidance.** (plan:208, 410, 430)

Pure hash routing on `Mcp-Session-Id` cannot work, because `initialize` carries no ID. Learned stickiness does work with opaque IDs, for example HAProxy `stick store-response res.hdr(Mcp-Session-Id)` or Envoy's stateful-session filter. 3.5.1's custom ID generator and shared store are gone with no replacement. Website pages that say "no sticky sessions" (`mcp.md:13, 17`) must be qualified for session-enabled 2025 revisions.

**Fix:** document which load-balancer modes work and which fail, and add a deployment test or doc example. An optional, validated, non-secret routing prefix on `McpSessionConfig` is the 3.5.1-equivalent escape hatch. Add it only if a deployer needs hash-based routing.

**F11 [Important] The 16 KiB metadata cap.** (plan:298, 502)

The outcome on overflow is unspecified: reject `initialize` (a new interop failure) or drop the snapshot. November `clientInfo` can carry `data:` icons (Nov `schema.ts:464-477, 550`), but the public `McpImplementation` has no icons field, so the cap's scope is also undefined. A retained tree with one `LinkedHashMap` per object can hold about 0.5 MiB of heap per session within 16 KiB of JSON (an unmeasured estimate).

**Fix:** measure the cap over the retained public projection. On oversize, keep the session without a snapshot. Add a node-count cap, and measure heap in Slice 4.

**F12 [Suggestion] Legacy `notifications/initialized` rejects a spec-valid `params._meta` with 400** (`Runtime:6458-6461`).

This is pre-existing and hypothetical for current clients. Slice 4 makes that notification load-bearing, so accept `_meta` that passes `validateNotificationMetadata` when the path is rewritten.

### G. Cancellation (§4.6)

**G1 [Blocking] Cancelling a finite JSON request has no defined HTTP response, and reusing the existing cancel leaves the POST connection parked.** (plan:442-444)

The only existing primitive, `RequestControl.cancel`, marks the control terminal and nulls `responseCallback` without invoking it (`Runtime:12026-12033`). Microhttp has no handler-side abort and no HANDLING-state timer. A null response is a `RESPONSE_READY_ERROR`, not a clean abort. TS 1.29 sends `notifications/cancelled` on another pooled connection without aborting the original fetch, so this is the normal client path. A host that cancels routinely accumulates parked connections against the 8,192-connection limit. DELETE, expiry, and retirement of an uncommitted in-flight POST have the same gap.

**Fix:** define the bytes.

- For client cancellation of an uncommitted request, commit `text/event-stream` with zero events and end it cleanly. Clients MUST accept SSE (June transports:97), and the cancellation spec says not to send a response.
- For session retirement, use the same or a neutral 404.
- Implement this as a `RequestControl` transition that always delivers a response and never drops the callback on a live connection.
- Add a real-socket test asserting that the cancelled POST's connection completes.

**G2 [Important] The "final-response reservation wins" rule cannot be built on the existing transport cancel.** (plan:428, 442)

The application layer already reserves (`Router:2794-2810`, `RESPONSE_OFFERED`), and a losing `reserveCancellation` returns a no-op `Runnable` that is indistinguishable from a win (`Router:2898-2899`). But `RequestControl.cancel` terminates the HTTP path unconditionally. On SSE, `stream.close` clears a terminal chunk that was reserved but not yet written (`McpOutboundChannel.java:584-588, 712`). The response that "won" is lost.

**Fix:** have `reserveCancellation` report whether it won, and terminate the HTTP path only on a win. Do not clear a reserved terminal on a client-cancel close. Decide whether DELETE and expiry preserve a reserved terminal; the spec permits dropping it on expiry. Test both interleavings with `McpRequestSseStream.TestHooks.beforeTerminalReservation` and an equivalent finite-path hook.

**G3 [Important] A lifetime history of seen IDs with retire-at-capacity is not required by the spec, and it forces session loss.** (plan:434-436, 505)

The no-reuse rule binds the *requester* (June basic:48). The cap of 64 KiB per session averages 16 bytes per ID, so clients using UUID string IDs hit it after about 1,800 requests (around 30 minutes at 1 request per second), and integer-ID clients hit the count cap. Retirement fences in-flight work and drops grants. The 4 MiB global ID cap is smaller than the 16 MiB that 256 sessions can each claim, so other owners can trigger it. The benefit is narrow: a late cancel hitting a reused ID affects only the session that broke the MUST.

**Fix:** reject only an ID that collides with a *currently active* request in the same session; that set is already bounded by the 32-call cap. Optionally keep a short evicting window of recently completed IDs. Never retire a session because of ID history. Specify the collision rejection as HTTP 400 with `-32600`.

**G4 [Suggestion] A cancel that arrives before its target is registered is lost.** (plan:436-438)

Admission and owner resolution run before registry reservation (`Runtime:4995-5025`; `Router:1885-1900`). A cancel that overtakes its target on another connection is ignored, and the tool runs to completion. This is spec-legal (Nov cancellation:41-42).

**Fix:** document it as an accepted limitation and test it. Bounded per-session pending-cancel tombstones are an optional improvement; keep them out of the seen-ID history and consume them atomically at registration.

**G5 [Suggestion] The request rate limiter can deny `notifications/cancelled`**, which loses the cancellation exactly under load (`Runtime:6508-6533`). Applications can already exempt `NOTIFICATIONS_CANCELED`.

**Fix:** document in §4.6 and the limiter Javadoc that session cancellation now has an effect, and recommend not denying it. A framework exemption would change the frozen SOK-RATE-003 order.

**G6 [Suggestion] Specify per-revision parsing.** June requires `requestId`; November makes it optional (Nov `schema.ts:215-229`). A missing, null, or wrongly typed `requestId` should get 202 and be ignored. An over-length or non-string reason should be dropped without voiding a valid `requestId`. This continues the existing parameter-tolerant 202 policy.

**G7 [Suggestion] Record the logging deviation.** Excluding reasons from built-in logs departs from the spec's "SHOULD log cancellation reasons" (June cancellation:71). Record it as a deliberate deviation and cite `MCP_PRIVACY_BOUNDARY.md`. 3.5.1 exposed the reason through `McpCancelationToken`; decide explicitly whether to restore a bounded hook.

### H. GET, subscriptions, invalidations, priming (§4.7–4.9)

These matter only if Slices 5–6 go ahead (A5).

**H1 [Important] Per-URI grant leases overflow the authorization scheduler they would share, and overflow silently drops grants.** (plan:468, 474, 507, 516)

Existing checks run through one scheduler with 4 workers and a 128-entry queue (`Runtime:322-324, 9475-9508`), and then take a slot on the 32+128 handler dispatcher (`Router:2152-2153`). Renewals fire at half the lease, and the lease is 1 minute (`McpServer.java:296-297`). At 256 sessions × 66 leases that is about 550 checks per second. `reconcileSubscriptions()` schedules everything at once, so anything past about 132 is rejected and the grant removed. 2025 has no "subscription ended" message, so clients never learn they should resubscribe. Modern subscriptions overflow the same way above about 132, but the closure is visible to the client.

**Fix:** use a paced legacy maintenance queue sized to the grant cap. On capacity rejection or timeout, keep the fenced grant and retry until the lease expires, deleting only on DENIED. State a renewal-rate budget in §5. If a grant must be dropped for a non-authorization reason, retire the session, because a 404 forces resubscription.

**H2 [Important] URI grants renew only against the original subscribe credential, so token expiry silently drops them.** (plan:458, 472, 480, 516)

With 15-minute tokens and 1-minute leases, an authorizer that checks token expiry denies the renewal at about T+15m. Meanwhile the client refreshed its token and its GET was re-admitted, so it believes it is still subscribed. Modern renewal also uses the initial request; the legacy-specific defect is that the loss is invisible.

**Fix:** a successful duplicate subscribe atomically replaces the retained evidence and releases the old evidence. On grant denial, retire the session so the loss becomes visible.

**H3 [Important] Round-robin over a session's GET streams sends events into half-open sockets.** (plan:480, 506)

After a network change, the client reconnects at once, but the server cannot detect that the old GET is dead until a write fails. Keepalive writes can sit in the kernel buffer for minutes, so alternating events are lost for good. Any at-cap rejection, including 405, ends TS GET listening.

**Fix:** newest-wins. Deliver each event to the most recently established eligible stream, falling back to an older one only if the newest is fenced or full. At the cap, admit a freshly authorized same-owner GET and close the oldest.

**H4 [Important] Legacy delivery omits the existing per-key coalescing.** (plan:480, 484, 510, 525)

The modern path coalesces per family and per URI (`Runtime:10827-10896`). Without coalescing, bursts close healthy but slow GETs, and the client misses state with no replay. The global budget needs reserve and release hooks in `McpOutboundChannel`, which is shared with modern streams and is not listed as a seam. Whether POST SSE counts against the 16 MiB budget is unstated.

**Fix:** require session-level coalescing: at most one pending frame per family and per URI, about 71 KiB per session. Add global reserve/release to `McpOutboundChannel` and list it as a seam. State the scope of POST SSE.

**H5 [Important] Endpoint-level legacy dispatch turns documented "reevaluation hints" into broadcast notifications.** (plan:302, 476, 482)

`ToolsListChanged` and `PromptsListChanged` are documented as requests to re-check that do not assert a change (`McpSubscriptionEvent.java:176-178, 211-213`; `McpSubscriptionEventPublisher.java:37-41`). Applications following that contract publish liberally. Under the plan, each publication becomes a `list_changed` to every legacy session, even when nothing visible changed or nothing *can* change. One role edit could trigger about 200 freshly admitted relists.

**Fix:**

- Coalesce per session and family.
- Suppress events when the family's view does not depend on the caller.
- Allow at most one outstanding `list_changed` per session and family, re-armed by that session's next admitted list request.
- Fix the Javadoc, and the stale website claim that tools and prompts "have no application publisher API" (`mcp-progress-and-subscriptions.md:91`; `mcp-compatibility.md:152`).

**H6 [Important] Events dropped while no GET is eligible are lost for good.** (plan:472, 484)

"Clients must relist" (plan:484) is not a duty either revision imposes. The plan opens several silent gaps inside a live session: renewal timeout or denial, slow-client overflow, reconnect gaps, and the window between `initialize` and the first GET.

**Fix:** keep a bounded, payload-free dirty bit per family (at most 3) and per live grant (at most 64). Flush one coalesced invalidation each on the next freshly admitted GET, only for families that GET authorizes, and with generation checks. These are invalidations, not replay, so they stay inside §1's exclusions. Rewrite plan:484 to state best-effort semantics.

**H7 [Important] No wire errors are specified for subscribe and unsubscribe.** (plan:466-470)

**Fix:**

| Condition | Result |
| --- | --- |
| Malformed or missing `uri` | `-32602` |
| Capability not advertised for this revision or session | `-32601` |
| No route, or authorizer Denied | The same neutral `-32002` that `resources/read` uses |
| Authorizer timeout or capacity | Keep the transient 503 / `-32603` mappings, so clients can retry |
| Well-formed unsubscribe | `{}`, with no route check |

**H8 [Important] Legacy GETs share the per-partition subscription quota (32 by default), and the partition they charge is undefined.** (plan:454-456, 518)

Common 2025 clients open GET automatically after `initialize` (REVIEW.md:82), so every legacy session takes a unit whether or not it uses notifications. Each era can starve the other. A session's identities come from three callbacks (initialize/subscribe, GET, renewals).

**Fix:** bind the partition when the first GET or grant is reserved, and deny on a later mismatch rather than migrating, mirroring modern's fixed reservation. Specify the GET capacity status: a retryable 503, not 405, and it does not retire the session. Consider a separate legacy counter.

**H9 [Suggestion] An unsubscribe that overtakes an in-progress subscribe for the same URI is undone.** Make establishing subscribes visible to unsubscribe, scoped to the active registry. The stray grant is otherwise bounded by the lease.

**H10 [Suggestion] Generation checks cannot reach frames already in the outbound channel.** `McpOutboundChannel.writeTo` drains opaque bytes. Fence at the offer boundary with per-URI coalescing keys, and reword the §7 gate to "no new offer after the fence; at most one buffered frame per URI may follow".

**H11 [Suggestion] Reuse the existing per-server publisher registration.** Feed legacy delivery from `publishSubscriptionEvent` under the same `SubscriptionEventSourceGeneration` fence, rather than registering a second listener on a possibly distributed bus. Include `LocalizationCatalogsChanged`, and ignore task events.

**H12 [Suggestion] §4.9: record "no priming" as decided.** (plan:486-490)

Priming exists only to seed `Last-Event-ID` resumption. November treats every server-initiated close as a resume trigger, so emitting an ID without replay would make each cancel or expiry close into a resume the server cannot honor. This is a justified SHOULD exception.

Add the following:

- a golden-test invariant that no legacy SSE event carries an `id:`
- a rule that a GET carrying `Last-Event-ID` ignores it and opens a fresh stream (3.5.1's 400 broke reconnects)
- optionally, one conservative `retry:` before planned GET closes

**H13 [Suggestion] DELETE admission would be governed by `subscriptionAuthorizationTimeout`** even on servers with no subscriptions. The plan already commits to re-documenting it (plan:513-517). A session-owned timeout would be clearer. This becomes moot if DELETE stays at 405 (B5).

**H14 [Suggestion] Define the undefined terms:**

- "HTTP-control budget": keyed before or after admission? Before admission it can only be bounded globally, as POST is today.
- "Logical legacy delivery registration": which identity's partition owns it?
- "Eligible stream": it should exclude streams under backpressure.

### I. Bounds (§5)

**I1 [Blocking for Slices 4–6] Global caps are 1/4 to 1/32 of the summed local caps; one owner within quota can exhaust each; exhaustion behavior is undefined.** (plan:505, 509, 525)

| Budget | Sum of local caps | Global cap |
| --- | ---: | ---: |
| Request-ID bytes | 256 × 64 KiB = 16 MiB | 4 MiB |
| Retained evidence | 256 × 256 KiB = 64 MiB | 4 MiB |

For retained evidence, one owner's 16 sessions at their cap equal the whole global. String IDs are bounded only by the 1 MiB output limit (`McpJsonRpcEnvelopeCodec.java:182-224`), so four owners sending 64 requests with 65,000-byte IDs fill the ID global, and the next request from any other session either retires that session or is rejected. Notifications are less exposed if legacy delivery reuses coalescing (H4).

**Fix:**

- Cap session-bound request IDs and progress tokens at about 256 UTF-8 bytes, rejected before any history write.
- Give each global a per-owner share, or validate global ≥ `maximumSessionsPerOwner` × per-session cap × intended owner count.
- When the global (not the local) cap binds, reject the allocation and never retire.
- If the notification global binds, shed the stream holding the most pending bytes.

**I2 [Important] The internal global and local caps are fixed while `maximumSessions` is configurable.** (plan:114, 497-525)

Raising `maximumSessions` shrinks each session's share of the fixed 4 MiB and 16 MiB globals without warning. The HTTP-control budget has no numbers at all. 3.5.1 defaulted to 8,192 sessions with a 24-hour idle (`master:McpSessionStore.java:229-231`), so migrating users will raise the limit.

**Fix:** derive the globals from configured values with a stated overcommit ratio, and fail `build()` when they cannot serve the configured maximums. Give the control budget concrete constants in Slice 0.

**I3 [Important] The evidence caps conflict with the header limit and with realistic tokens.** (plan:509, 523)

The 64 KiB per-request cap equals the default `maximumHeadersSizeInBytes` (`McpServer.java:279`), which operators can raise. Each subscribe POST carries its own Authorization and cookie headers, and "charge shared evidence once" does not deduplicate them across requests. With enterprise tokens of 4–14 KiB, the 64-grant cap is unreachable at 256 KiB per session. The subscribe failure response is unspecified.

**Fix:** make the per-request cap at least the configured header cap plus a body allowance. State the assumed evidence size so the caps are consistent. Add a per-owner evidence cap. Specify the subscribe error. Content-addressed header deduplication is optional.

**I4 [Suggestion] The per-session cap of 32 calls equals the default handler concurrency, so it is a registry bound, not tenant isolation.** One owner can already fill the shared dispatcher today. If fairness is wanted, add a per-owner active-plus-queued cap (now possible, because sessions resolve an owner), and consider reserving dispatcher capacity for subscription maintenance in both eras.

### J. Governance, ripple, documentation (§6)

**J1 [Important] The proposed public names and session state fail two frozen gates, and the plan names only one of them, framing it as internal.** (plan:69-183, 372, 553)

- `McpLegacySessionNegativeInventoryTests` (regex at `:49-54`) scans `Mcp*.java`, `internal/mcp`, **and the phase-4/5/6 signature ledgers**. It matches all five proposed type names. It even matches `maximumSessionIdleDuration`, because "SessionId" is a prefix of "SessionIdle".
- `scripts/verify-mcp-transport-dependencies.mjs` runs inside the CI freeze job and rejects any field-bearing class or field whose name contains `session` (`STATE_DOMAIN_TERMS`, `:68`; rules at `:815-859`). Its baseline also says notifications are request-scoped (MCP-TRANSPORT-001, -005).

"Narrow the prohibition for reviewed legacy modules" cannot exempt public types or the ledger scan.

**Fix:** decide the gate amendments in Slice 0, alongside the names. Either confine new state to an `internal/mcp/legacy/**` package with an explicit exemption, or add reviewed allowlist rows, while keeping the modern-isolation scans. Replace MCP-TRANSPORT-001 and -005 with characterizations scoped to legacy.

**J2 [Important] Documentation goes stale slice by slice, and Slice 7 omits the primary documents.** (plan:536-544)

Statements that become false as early as Slice 1 or Slice 3:

- `MCP.md:108-160, 905, 1917-1934`
- `CHANGELOG.md:123-130`
- `SECURITY.md:113-114`, which also needs the affinity and "session ID is not a credential" guidance
- `soklet.com`: `mcp.md:13, 17`, `mcp-compatibility.md:13-32, 82, 174`, `mcp-progress-and-subscriptions.md:37, 91`, `production-readiness.md:226, 403, 416`, `mcp-api-reference.md:663`
- the published OAuth controller (`mcp-oauth.md:182-194`), which maps none of the legacy lifecycle operations and none of the new subscribe operations, so they fall through to `default -> unmappedOperation()` (403). This is pre-existing for 2025 `initialize`, but the plan makes `NOTIFICATIONS_INITIALIZED`, `NOTIFICATIONS_CANCELED`, and the new subscribe operations load-bearing.

**Fix:** each slice updates the statements it falsifies, with wording conditional on configuration. Slice 7 names CHANGELOG, MCP.md, SECURITY.md, and MIGRATING. Extend the OAuth example, and add examples for the transport controller (returning the token's `exp`) and the owner key.

**J3 [Important] §7 does not name the reviewed inventories and verifiers that fail closed.** (plan:557-582)

- the finite-bound inventory (`FINITE-MATCH-003`; data rows only, since session bounds fit the existing TIME, CONNECTION, and QUEUE_STREAM categories)
- limits and accounting (`LIMITS-SERIALIZED-RESULT` cites the whole-catalog preflight that E5 changes)
- privacy boundary (`PRIV-MATCH-005` covers `getRequest()` on the new context)
- the frozen legacy conformance profile (`conformance/official/legacy/run.mjs:19-55`, which asserts the optional-session INFO verbatim)
- soak evidence (`verify-soak-evidence.mjs:76-94`)
- the active-text census (`LIFECYCLE-001` rejects the capitalized tokens Roots, Sampling, and Logging)

**Fix:** add a governance checklist to §7, keyed to slices.

**J4 [Suggestion] The seams list is incomplete.** Annotation validation lives in `SokletProcessor` (`:1034-1038, 2318, 3622-3627`), not in the annotation itself. Also missing:

- the bridge's per-revision view builder (`Bridge:1665`, "Completion and extensions retain their 2026-only adapters")
- the legacy allowlist and the hand-built `initialize` capabilities
- `processNotification` (`Runtime:6418-6538`)
- `McpLegacyHttpWire`'s mirror allowlist (`:142-145`)
- `McpServerCapabilityRegistry`
- `McpOutboundChannel`
- `DefaultMcpServer.BOUNDED_METRIC_METHODS` (`:125-129`)

**J5 [Suggestion] Register Slice 7 website examples with the snippet compiler.** `soklet.com/scripts/verify-documentation-snippets.mjs` compiles only fences registered by marker, and it is a manual script. The go-live review found three published examples that did not compile (`SOKLET_4_0_GOLIVE_REVIEW_2026-09-15.md:162-170`). Make "every new or changed session, transport-admission, and subscription fence is registered and compiled against the refrozen JAR" an exit item.

**J6 [Important] §6 exit criteria are not measurable.**

- "Reviewed decisions recorded" has no location.
- "Disconnect preserves bounded work" and "Large catalogs enumerate completely" give no size.
- "Modern subscriptions unchanged" names no suite.
- "Bounded named-host checks" names no hosts.

The predecessor had a concrete bar: at least one local and one cloud host, with PASS, FAIL, or NOT TESTED recorded per target (predecessor:688-695).

**Fix:** answer Q8 with a minimum host and version per advertised feature (K3), and name the decision file and the regression suites.

### K. Verification and qualification (§7)

**K1 [Important] The gate "official scenarios for each new feature at each revision" cannot be met as written.** (plan:576)

The pinned alpha.11 suite (`a983ba93`) has no 2025 scenario for:

- static-catalog pagination
- `notifications/cancelled`
- GET-delivered `resources/updated` or `list_changed`
- the missing-session 400
- the wrong-owner 404

Existing scenarios are shallow:

- `completion-complete` sends only a prompt ref.
- `tools-call-with-progress` uses only a string token.
- The two November SSE scenarios send no `progressToken`, so under §4.3 Soklet answers with JSON and they log INFO without ever exercising SSE.
- The CLI exits 0 on an inapplicable scenario/version pair.
- `server-session-lifecycle` does cover the 404 after DELETE at both revisions.

**Fix:** add a per-feature evidence table in the existing `scenarios.json` `localSupplements` style, with RUN or NOT_APPLICABLE against `requirements/2025-11-25.yaml`. For features with no scenario, use a pinned TS/Python SDK reference-client harness over real sockets. Label the SSE scenarios as transport-only evidence.

**K2 [Important] Two fixture modes are needed.** (plan:354, 563-568)

The legacy runner freezes the optional-session INFO, and the suite sends no credentials.

**Fix:** keep the stateless fixture byte-for-byte as a regression gate. Local JUnit tests already assert that no `Mcp-Session-Id` is sent (`McpPromptPublicRuntimeTests.java:103-107`; `McpHttpServerConnectionContractTests.java:176-226`). Add a separate session-enabled fixture with its own frozen profile, using anonymous opt-in or a fixed test identity. State that owner-binding evidence is local-only.

**K3 [Important] Named-host qualification needs a per-feature host matrix and a topology.** (plan:578, 595)

On the §3 dual-era fixture, Claude Code negotiates modern, so no legacy path would be exercised. VS Code exercises every expansion feature and has never been tested.

**Fix:** before Slice 4, fix a minimum bar per advertised feature:

- **VS Code:** pagination, completion, cancellation, subscribe, re-session. This first depends on A2.
- **Codex CLI:** dual-era legacy selection, pagination.
- **Claude Code / Claude Desktop on 2025-only URLs:** progress, cancellation.
- **Inspector in legacy mode:** completion, progress.

Run both topologies and record the era each host selected.

**K4 [Important] Missing §7 cases.**

- anonymous opt-in and namespaces
- resolver failure, timeout, null, and oversized key
- snapshot visible only after owner verification, and absent from `McpAdmissionContext`
- `initialized` 202, repeated acknowledgement, contradictory reinitialization
- the cancellation POST's own 202
- subscribe returning `EmptyResult`, not 202
- non-empty GET/DELETE bodies
- `validUntil` overflow
- the new `McpOperationType` values in admission and rate-limit contexts
- asserted termination reasons
- a modern request carrying a live legacy `Mcp-Session-Id` at a shared URL (outcome and no idle refresh)
- 404 then re-initialization at the per-owner cap while an expired session's handler is still running
- DELETE and GET on a provisional session
- metadata-cap overflow
- public simulator coverage (D6)

**K5 [Important] §5 says to measure before freezing, but no slice requires it.** (plan:494, 525, 542)

The soak module has no 2025 traffic. Plan:527 and :572 require cleanup and bounded-memory tests, but no exit criterion turns the promise into a soak run or a measurement.

**Fix:** add a legacy soak profile to the Slice 4/6 exits: initialize/expire/re-initialize churn, slow and token-ignoring handlers, GET drop and reconnect, publish storms. Hold it to the existing resource-delta ceilings and assert that counts return to zero. Record measured per-session retained bytes before Slice 7 documents defaults. Benchmark paged enumeration and legacy POST SSE.

**K6 [Important] Priming cannot be evidenced by official conformance.** (plan:486-490, 576)

`server-sse-polling` is November-only and marked pending/not_scored, and it never reaches its priming checks against Soklet: either 400 then WARNING, or JSON then INFO (`sse-polling.ts:146-216`). Priming without replay would not fix client hangs anyway (F5).

**Fix:** record in Slice 0 that official evidence is unavailable. Add pinned-SDK tests that document what clients surface on a server-initiated POST SSE close.

**K7 [Suggestion] Bind expanded 2025 claims to the candidate gate.** Push CI already runs the legacy runner (`ci.yml:468-480`), but `release-validation.yml` and `upstream-pins.json` contain no 2025 selection. Either add one, or label the expanded 2025 claims development-qualified on the public page.

**K8 [Suggestion] Add fuzz targets.** The framework cursor decoder is the high-value one; session-ID validation is a fixed format and low value. Add nightly targets with push-replayed corpora, in the style of `McpRequestStatePlaintextCodecFuzzTest`.

## 5. Suggested re-sequencing

| Step | Contents | Public API | Gate to start |
| --- | --- | --- | --- |
| **0. Decisions** | Release target per slice (A6); superseding record for NI-03, DF-03, and the planning authority (A7); status-code policy for legacy (F1); cancel wire result (G1, G2); termination-reason mapping and soklet-otel plan (B1); anonymous definition (B18); per-endpoint session revisions (B4); outcome table (C3); 3.5.1 mapping (A8); VS Code baseline smoke test and header policy (A2); gate amendments (J1) | None yet | Owner review |
| **1. Completion** | Per-revision routing (D1); empty values for templates without a completer (D2); allowlist and capability | None | Step 0 status policy |
| **2. POST progress** | Lazy SSE (D5); disconnect scoped to SSE-committed work (D3); collision outcome (D4); simulator semantics (D6) | None | Step 0 |
| **3. Pagination** | Resume-key cursor, fill to budget, page-only localization, per-revision whole-catalog checks (E1–E6); reversal note (E7). Or drop it. | None | A named host that needs more than one page |
| **4. Sessions + cancellation** | All of §2 item 3; F6, F7, F9, F10, F11; G3–G7; I1, I2; staged API (B3); observability (B2) | `McpSessionConfig` (no controller), owner key, `McpServer` accessors | Step 0 plus release target (likely 4.1) |
| **5–6. GET + subscriptions** | B5–B7, B13–B15, C2, H1–H14, I3 | Transport trio, operation constants | A named 2025 host that acts on GET notifications (A5) |
| **7. Qualification** | Per-slice documentation (J2) and snippets (J5); evidence table (K1); fixtures (K2); host matrix (K3); soak (K5); candidate binding (K7) | Refreeze per slice | — |

## 6. What the plan gets right

These were checked and need no further discussion:

- **Existing API:**
  - Every §2.3 signature matches the baseline exactly: the five handler interfaces, the `McpInvocationFeatures` defaults, `McpProgressReporter.report`, both completion-registration builders, and both completion annotations.
  - `McpProgressUpdate` already supports finite doubles, and the reporter already serializes updates, coalesces equal values, rejects decreasing values, and goes inert after cancellation.
- **§1 baseline claims:**
  - 2025 initialization and ping are stateless (`Runtime:5520-5546`).
  - Completion is rejected at registration, annotation, and runtime.
  - Legacy progress is disabled (`Bridge:4099-4103`).
  - Static catalogs reject any cursor before admission.
  - Custom resource-list cursors work.
  - Capabilities are deliberately empty on later requests.
  - `notifications/cancelled` returns 202 after admission and the limiter, with no other effect.
  - GET and DELETE return 405 with `Allow: POST, OPTIONS`.
  - Subscriptions are modern-only.
- **§4.3:** `McpRequestSseStream` encodes through the modern envelope codec, and `McpLegacyResponseWire` strips `resultType` and modern `_meta`, so a profile-aware seam really is needed.
- **Spec readings:**
  - A 43-character base64url ID satisfies the visible-ASCII rule.
  - The 400/404/DELETE-405 rules are read correctly (June transports:176-197).
  - Disconnect is not cancellation once SSE is open.
  - Priming is a November-only SHOULD, and replay is a MAY.
  - Both revisions forbid ID reuse within a session (basic:48).
  - `initialize` cannot be cancelled.
  - Subscribe and unsubscribe return `EmptyResult`.
  - The capability mapping is correct.
  - One copy of a message per session is a MUST (June transports:143).
  - The completion and progress wire shapes are identical in June and November.
  - Exactly four list methods paginate, and `-32602` is the error for a bad cursor.
- **Design choices that hold up:**
  - A neutral 404 for wrong-owner or wrong-path IDs is correct.
  - Fresh admission on every request, plus owner binding, implements the spec's session-hijacking mitigations (security best practices:576, 584-590).
  - `McpAdmissionDecision` has no expiry, so requiring `validUntil` on the HTTP decision is justified. The sealed shape mirrors it and compiles in house style.
  - Rendering an HTTP rejection without its JSON-RPC body has precedent in `notificationAdmissionRejection` (`Runtime:7134-7149`). Only the status range is a problem (F1).
  - `McpOperationType` is documented as growable, and no exhaustive switch over it exists in core or soklet-otel.
  - `McpServer` is sealed, so adding a getter is safe.
  - Neither servlet adapter needs changes; MCP runs on its own listener.
  - The modern lease conversion already clamps and guards against overflow, so plan:456 has a pattern to reuse.
  - Logical cancellation already keeps the worker reserved until physical exit (`Router:3026-3035`).

## 7. Answers to the plan's §8 questions

**Q1. API footprint.** Not as drafted.

- `McpSessionConfig` is warranted, but per-endpoint revision gating should replace its revision set (B4).
- Decide between the owner-key resolver and an owner-key field on the identity (B9).
- The HTTP context/controller/decision trio belongs only with Slice 5, and only for GET (B3, B5).
- `CLIENT_CANCELED` is defensible on the token enum. `SESSION_EXPIRED` and `SESSION_TERMINATED` belong on `McpStreamTerminationReason` (B1).
- The footprint is 8 owners plus undecided observability and simulator surface, not 5 types (B2, B12).
- Naming otherwise fits house conventions.
- The smallest honest API for the first session increment is `McpServer.Builder.sessionConfig`, a session config with no transport types, and an owner key.

**Q2. HTTP admission.** Yes, an explicit `validUntil` is the right primitive.

- Revocation before `validUntil` still depends on the process-local `reconcileSubscriptions()`. Document that.
- Reusing `McpAdmissionRejection` without its JSON-RPC body has precedent. The real problem is that its 400–599 range lets applications emit 404 and 405, which become session-state signals (F1).
- The catalog-family split does need a different contract: per-caller narrowing on `Accepted` (B7), coalescing and suppression for reevaluation hints (H5), and one computed family set that includes localization (B14).
- Selecting the revision from the header before admission breaks VS Code (A2).

**Q3. Ownership.** A separate owner key is necessary, because partition keys can be tenant-scoped (`mcp-security-and-policy.md:39`).

- Keep anonymous sessions opt-in rather than excluded. Every qualified host connected locally without credentials to a default 127.0.0.1 accept-all server, so excluding anonymous sessions would make sessions unusable exactly where they would be tried first.
- Define anonymous precisely, namespace it, and sub-cap it (B18).
- Document a collision-free reference resolver (B16).

**Q4. Protocol details.** Several are wrong or undefined:

- the 404 collisions (F1)
- the finite-JSON cancel response (G1, G2)
- server-initiated SSE close (F5)
- foreign IDs and revision mismatch (F7)
- full-store 429 (F4)
- `initialize` carrying an ID (F8)
- the publication race (F6)
- token collisions (D4)
- lifetime ID history (G3)
- per-revision cancel parsing (G6)
- subscribe errors (H7)
- the dispatch and outcome tables (C2, C3)

Resolve priming as "no priming, ignore `Last-Event-ID`, optional `retry:`" (H12). Official conformance cannot evidence it (K6).

**Q5. Pagination.** A cursor that selects a resume point inside a freshly authorized view grants nothing. Neither integrity nor subject binding is needed, and no key distribution is needed either; plan:402 already argues this, so close Q5 from it. The full-view digest is the wrong mechanism (E1–E3). Use a keyset/resume cursor with a startup catalog fingerprint, fill pages to budget (E4), keep per-revision fail-fast (E5), and give the cursor its own bound (E6). Or drop Slice 3.

**Q6. Lifetimes.** Not as written:

- The reservation-wins rule needs a conditional cancel (G2).
- A finite cancel parks the connection (G1).
- Pre-registration cancels are lost (G4).
- Publication races the next request (F6).
- An unsubscribe can be undone (H9).
- Queued frames escape fences (H10).
- Idle expiry ignores GET and in-flight work (F3).
- Grants vanish silently on renewal failure or scheduler overflow (H1, H2).

Capacity release is right in one respect: worker reservations already persist until physical exit. But session-count slots should be released at *logical* retirement (F4).

**Q7. Bounds.** They cannot be enforced as written:

- Global caps are smaller than summed local caps (I1).
- The caps that bind are fixed internals (I2).
- Evidence caps conflict with the header limit (I3).
- Grant leases overrun the scheduler (H1).
- The cursor cap cannot be shared (E6).
- The quota partition is undefined (H8).
- Seen-ID retirement forces session loss (G3).

No new application-facing limiter is needed for the first increment. POST admission already runs before the rate limiter, so GET adds no new class of pre-authentication amplification. Run GET/DELETE admission on the same bounded processor, key the post-admission budget by owner, and reserve renewal capacity.

**Q8. Deployment and claims.** These are not clear enough:

- **Affinity:** header-hash load balancers fail; learned stickiness works (F10).
- **Resync:** "clients must relist" is not a spec duty and not SDK behavior (H6).
- **Recovery:** expiry and retirement surface as unrecovered 404s in TS and Python hosts (F2).
- **Enablement:** enabling sessions wedges clients that initialized statelessly (C1).
- **Docs:** documentation goes stale slice by slice (J2, A8).
- **Governance:** NI-03 is contradicted (A7), and the release target is unstated (A6).

Minimum host bar per advertised feature:

| Feature | Host evidence required |
| --- | --- |
| Progress | A host that displays progress |
| Cancellation | A host that sends `notifications/cancelled` during real work (VS Code or a TS-SDK host) |
| Pagination | A host that drains more than one page |
| Slices 5–6 | A host that acts on a GET-delivered notification (none recorded today) |

Run each on the topology it will be deployed on (K3).

## 8. Corrections and refuted findings

**I corrected one point where two verifiers were wrong.** The settled and consequence lenses on B1, and one verified-correct entry, said soklet-otel has no exhaustive switch over `StreamTerminationReason`. It does: `OpenTelemetryLifecycleObserver.java:657-661`, `isError`, a switch expression with no default branch, compiled with `<release>17</release>` against `soklet.version` 4.0.0. The generic `enumValue(...)` at `:641` is a different call site. B1's soklet-otel ripple therefore applies to both compilation and binary compatibility.

**Refuted during verification (not raised):**

- *Completion rate limiting depends on an optional limiter.* False. Construction fails without a request limiter whenever completion exists (`DefaultMcpServer.java:361-364`).
- *Claude cloud backends might not echo `Mcp-Session-Id`.* Unsupported; echoing it is a client MUST. The related, deterministic problem of clients that never received an ID survived as C1.
- *Raw qualification evidence needs a durable in-repo home.* Plan:580 follows the deliberate September 30 convention (`.gitignore`; REVIEW.md:3-5).
- *November POST priming is left out of Slice 2.* §4.9 is gated in Slice 0 (plan:535) before any SSE coding (plan:490).

## 9. Coverage and limits

- **No build or tests.** Nothing was compiled or run, because there is no Java on PATH. Compile-break claims come from reading exhaustive switches. Heap and throughput figures are estimates, labeled as such.
- **Client behavior.** It comes from SDK and client source at the refs listed in the header, not from live runs. VS Code findings come from `main`, not the 1.139.1 release in the matrix; the A2 baseline failure should be confirmed with a smoke test. Claude Code and Claude cloud behavior beyond Soklet's stateless captures (404 handling, cursor following, progress display) is unknown. Cursor was not examined.
- **Not deeply reviewed:**
  - the full modern subscription state machine (`Runtime` ~8600–10900) beyond the paths cited
  - soak and benchmark sources beyond grep
  - release-time manifests (d1p digests, `release-validation-manifest.json`), which plan:582 reasonably defers to candidate time
  - golden HTTP-head corpora that session response heads would touch
  - website pages beyond those cited
- **Whether 3.5.1 session features had real users is unknown.** The original MCP plan said "no known users". That weakens the migration-impact findings, but not the documentation or affinity points.
