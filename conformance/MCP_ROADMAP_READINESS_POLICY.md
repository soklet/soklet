# MCP roadmap readiness policy

Generated deterministically by `scripts/verify-mcp-roadmap-readiness.mjs` from
`conformance/roadmap-readiness-deferred-features.json`. Do not edit by hand.

Supported profile: `2026-07-28`

Planning source: `SOKLET_4_0_COMPLETION_PLAN.md` (`cc172c07527c71a99894e228742333443bee6bd8047d228f7615aae7db58b0da`)

Planning-authority snapshot SHA-256: `cb8a38ddd5e95e2a97032e1a4a7b106fc016c089e5b5bd528cdb4390eb2bb868`

Approved scope supplement: [release/MCP_LEGACY_EXPANSION_PLAN_2026-10-01.md](../release/MCP_LEGACY_EXPANSION_PLAN_2026-10-01.md) (`a2287f8db3a01c9c890c6f5f21f31fde9c20a7ed2489f9f9dea37ef7c6ff46a0`).

The supplement selects the complete 2025 expansion for 4.0.0. Planned scope does not establish implementation, client qualification, or a candidate release pass. Original authority and approval pins remain historical.

## Negative inventory

| ID | 4.0 status | Statement | Rationale |
| --- | --- | --- | --- |
| NI-01 | ABSENT_IN_4_0_0 | full implementation of any 2025 or earlier protocol revision, including `2025-03-26` and the deprecated 2024 HTTP+SSE transport | The exact 2025-06-18/2025-11-25 adapters implement selected facilities. The October 1 scope supplement adds reviewed expansion work to the 4.0 target without claiming a complete older-revision implementation; excluded legacy requests, extensions and transports remain unsupported. |
| NI-02 | ABSENT_IN_4_0_0 | more than one complete core protocol profile | The 2026-07-28 profile remains the sole complete core profile. Exact 2025 compatibility adapters and their approved expansion are not another modern core profile; a second modern core profile still requires R2C review. |
| NI-03 | ABSENT_IN_4_0_0 | `2026-07-28` session lifecycle or `MCP-Session-Id`; event replay, `Last-Event-ID` recovery, or POST-result recovery at any revision | Modern requests remain stateless and cannot use legacy session state. The October 1 scope supplement selects opt-in 2025 sessions and GET delivery separately from replay/history/recovery, which remain excluded for every revision. |
| NI-04 | ABSENT_IN_4_0_0 | a public codec/profile SPI, arbitrary supported-versions builder, or service loader | `McpProtocolVersion` is a public enum of exact revisions, but codec/profile implementation and routing remain internal and bounded to declared compatible operations. |
| NI-05 | ABSENT_IN_4_0_0 | automatic Java `@Deprecated` annotations or Javadoc `@deprecated` tags derived only from MCP feature-lifecycle status | MCP feature lifecycle and Soklet Java API lifecycle remain independent axes. The owner explicitly approved removing the Roots, Sampling, and unused Logging APIs before publication; those product decisions do not automatically retire other API based on upstream lifecycle status. |
| NI-06 | ABSENT_IN_4_0_0 | Soklet-owned task persistence or workers, Triggers & Events, or a general server-event family | The Tasks extension delegates durable state and execution to the application; Soklet still owns no task store, worker runtime, general trigger/event lifecycle, or arbitrary server-event family. |
| NI-07 | ABSENT_IN_4_0_0 | arbitrary server-side extension advertisement or an arbitrary-method router | Soklet advertises and routes the explicitly implemented Tasks extension only; opaque client settings do not create arbitrary server support or routing. |
| NI-08 | ABSENT_IN_4_0_0 | dynamic/scoped tool and prompt catalogs, progressive discovery, or a generalized catalog provider SPI | Tool and prompt catalogs remain immutable and caller-neutral in 4.0. |
| NI-09 | ABSENT_IN_4_0_0 | ETags, `If-None-Match`, `304`, uploads, range reads, or hierarchy | Resource representation and transfer evolution remains outside the 4.0 scope. |
| NI-10 | ABSENT_IN_4_0_0 | built-in OAuth, DPoP, workload identity, delegation, or human-presence policy | Authentication and identity policy remain application-owned rather than built into core Soklet. |
| NI-11 | ABSENT_IN_4_0_0 | stdio, HTTP/2, or a public transport abstraction | McpServer remains a dedicated HTTP/1.1 listener without a public transport SPI. |
| NI-12 | ABSENT_IN_4_0_0 | TLS termination, which remains Soklet's longstanding product non-goal and is not contingent on the MCP transport roadmap | TLS termination remains Soklet's longstanding deployment-boundary non-goal. |
| NI-13 | ABSENT_IN_4_0_0 | renaming the existing subscription API | The existing subscription API name remains stable for 4.0. |
| NI-14 | ABSENT_IN_4_0_0 | closure of unrelated inherited blockers, scheduled evidence, or unrelated downstream work | MCP roadmap closure does not imply completion of unrelated release work. |
| NI-15 | PLANNED_IN_4_0_0 | selected 2025 completion, POST progress, static catalog pagination, opt-in sessions and remembered metadata/cancellation, GET/DELETE and resource/catalog notification delivery | The owner selected the complete expansion for 4.0.0 on October 1. This row records approved planned scope, with implementation and qualification tracked separately. It grants no current session/delivery capability or candidate release pass and never enables modern session state. |

## Deferred features

### DF-01 — Future same-revision spec/conformance growth

- Trigger: A newly published upstream package/scenario, replacement of the Tasks notification skip, erratum, or compatible addition for `2026-07-28`
- Landing zone: Existing 2026 profile plus reviewed openness disposition and regenerated pins/goldens; alpha.11 Tasks coverage is now selected, and notification coverage must be re-reviewed when upstream replaces its explicit harness skip
- Pre-release hedge: The alpha.11 release gate selects 49 reviewed profiles including ten Tasks rows; tasks-status-notifications remains an exact reviewed-SKIPPED upstream harness gap with independent production-listener supplements. The R2A index, openness inventory, toolchain-risk review and RC upstream-release check remain required.
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: None.
- Reviewed no-mapping reason: Same-revision growth and missing gate coverage are evidence work rather than features explicitly absent from the frozen 4.0 scope.

### DF-02 — Next modern MCP revision

- Trigger: Stable published revision plus Soklet support decision
- Landing zone: Complete R2C first; then add an internal core profile with independent pins/goldens and coexistence evidence
- Pre-release hedge: R2A/R2B-bind; explicit second-profile prohibition until R2C
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-02`, `NI-04`
- Reviewed no-mapping reason: Not applicable.

### DF-03 — Selected 2025 expansion and excluded older facilities

- Trigger: The owner selected the full 2025-06-18/2025-11-25 expansion for 4.0.0 on 2026-10-01; complete the reviewed slices and qualification before making their release claims
- Landing zone: The 4.0.0 development branch, beginning with exact-revision completion and continuing with POST progress, static pagination, opt-in sessions/cancellation and GET/DELETE notification delivery under the pinned scope supplement
- Pre-release hedge: Scope approval is separate from implementation and evidence. Each facility needs a working exact-revision adapter, focused tests and applicable qualification. Sessions remain application opt-in; modern session state, event replay/history, Last-Event-ID and POST-result recovery, distributed session storage, 2025-03-26 and excluded legacy extensions remain out of scope.
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-01`, `NI-03`, `NI-04`, `NI-15`
- Reviewed no-mapping reason: Not applicable.

### DF-04 — Future Tasks extension evolution

- Trigger: A stable Tasks extension revision adds settings, supported request types, task operations, lifecycle semantics, or a standardized localized task-projection contract; core absorption also triggers DF-02/R2C
- Landing zone: Additive extension-owned settings, operations, or result forms with explicit capability, routing, lifecycle, and manager-SPI review; localized task fields additionally require a two-phase origin-resolution/localized-projection SPI that first resolves the durable origin and authorized application state, then constructs a request-scoped localization context for projection without exposing framework-owned origin state; core absorption only in a later profile after R2C
- Pre-release hedge: The implemented SEP-2663 surface uses closed task-method routing, an open result discriminator, explicit capability negotiation, and an application-owned manager SPI; Soklet 4.0 keeps the creation locale opaque and leaves later task localization application-owned, while future acceptance requires cross-node gates proving the exact creation locale survives durable origin resolution and is used consistently for `tasks/get`, `tasks/update`, `tasks/cancel`, and task-subscription projections
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-06`, `NI-07`
- Reviewed no-mapping reason: Not applicable.

### DF-05 — Triggers & Events/webhooks

- Trigger: Accepted targeting, cancellation, error, delivery, and security contract
- Landing zone: Shared operation/event lifecycle with explicit identity target; distinct from the current resource-only publisher/API
- Pre-release hedge: R1 ownership correction and bounded-stream characterization
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-06`
- Reviewed no-mapping reason: Not applicable.

### DF-06 — Formal SDK extension contract

- Trigger: Stable role, packaging, version, capability, lifecycle, and auth rules
- Landing zone: Internal extension descriptor followed by reviewed supported opt-in API
- Pre-release hedge: Current client-negotiated extension path and non-reflection tests
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-07`
- Reviewed no-mapping reason: Not applicable.

### DF-07 — Progressive/capability-scoped discovery (SEP-2575 follow-on)

- Trigger: Stable query/filter/cursor/scoping and capability contract
- Landing zone: Tool/prompt provider APIs symmetric with `McpResourceListHandler`; caller-aware cache keys
- Pre-release hedge: Static list/call divergence and private/zero cache characterization
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-08`
- Reviewed no-mapping reason: Not applicable.

### DF-08 — Standardized errors across surfaces

- Trigger: Accepted allocation/envelope/HTTP mapping contract
- Landing zone: Common bootstrap or profile/extension-owned error contributor according to scope
- Pre-release hedge: Current allocation documented; no renumbering
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: None.
- Reviewed no-mapping reason: Standardizing existing error allocation is deferred design work, not a separately advertised 4.0 feature absence.

### DF-09 — Secure server configuration

- Trigger: Accepted threat model/configuration contract and deployment demand
- Landing zone: Typed secret-safe provider/admission surface with explicit identity, lifecycle, and observability rules, including any generic authentication request/response-header, CORS/preflight-allowlist, or exposed-header policy
- Pre-release hedge: Raw admission context, protected state, no generic configuration bag
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-10`
- Reviewed no-mapping reason: Not applicable.

### DF-10 — ETags/entity validators

- Trigger: Accepted MCP/HTTP representation semantics
- Landing zone: Profile-aware identity including revision, auth partition, localization/`Vary` equivalent, capability/query, and resource revision
- Pre-release hedge: Deterministic rendering and conservative cache policy; optional upstream localization report
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-09`
- Reviewed no-mapping reason: Not applicable.

### DF-11 — Tool-result redesign

- Trigger: Accepted replacement shape and migration rules
- Landing zone: Profile-specific adapter over stable typed/advanced application models
- Pre-release hedge: R3B copy-builder and R3C renderer boundary
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: None.
- Reviewed no-mapping reason: A replacement tool-result shape is deferred API evolution, not an unimplemented capability claimed by 4.0.

### DF-12 — Content-annotation retirement

- Trigger: Accepted lifecycle transition
- Landing zone: Separately approved Soklet API lifecycle/removal process; no automatic mapping from MCP lifecycle
- Pre-release hedge: Two-axis evolution policy; no foundational auth/routing dependence
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: None.
- Reviewed no-mapping reason: Retirement of an existing compatibility surface is a future lifecycle decision, not a feature absent from 4.0.

### DF-13 — Upload/range/hierarchy

- Trigger: Accepted request/result/resource semantics
- Landing zone: Invocation features and additive result/page builders
- Pre-release hedge: Type-keyed feature lookup and builder/value-carrier design
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-09`
- Reviewed no-mapping reason: Not applicable.

### DF-14 — HTTP over stdio/HTTP2

- Trigger: Accepted framing, multiplexing, lifecycle, and security contract
- Landing zone: New frontend/pipeline reusing profiles/router/results where valid
- Pre-release hedge: Mechanical dependency baseline, socket floor, committed-stream limitation
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-11`
- Reviewed no-mapping reason: Not applicable.

### DF-15 — DPoP/delegation/workload identity

- Trigger: Concrete deployment/policy need or stable MCP integration contract, according to R5a/R5b
- Landing zone: Admission/security evidence and reviewed response-header contribution
- Pre-release hedge: Raw non-browser headers, safe rejection headers, protected-state binding, honest browser limitation
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-10`
- Reviewed no-mapping reason: Not applicable.

### DF-16 — Typed-schema and annotation expressiveness

- Trigger: 4.1 typed-schema and annotation API design, including a reviewed diagnostic privacy envelope and parity across runtime reflection, annotation processing, generated registration, and JSON binding
- Landing zone: Additive 4.1 annotation constraints and formats shared by the reflection and annotation-processing schema frontends; UUID and selected java.time scalar bindings with exact format/parse gates; bounded instance-free validation-diagnostic projection; and an annotation-native typed-output plus advanced inline-result declaration
- Pre-release hedge: 4.0 exposes the already bounded Profile 1 authored input-schema path when Java derivation cannot express a constraint, advertises finite float/double bounds, documents the closed scalar family and generic validation failure, and supports typed-output inline isError results through the programmatic builder
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: None.
- Reviewed no-mapping reason: Richer derived-schema constraints, scalar conveniences, validation diagnostics, and annotation-native typed-output advanced results are deferred API evolution rather than capabilities advertised for the documented 4.0 closed Java family.
