# MCP roadmap readiness policy

Generated deterministically by `scripts/verify-mcp-roadmap-readiness.mjs` from
`conformance/roadmap-readiness-deferred-features.json`. Do not edit by hand.

Supported profile: `2026-07-28`

Planning source: `SOKLET_4_0_COMPLETION_PLAN.md` (`cc172c07527c71a99894e228742333443bee6bd8047d228f7615aae7db58b0da`)

Planning-authority snapshot SHA-256: `b89fc7b70aec8b938b17e854372cc35908e093bb3066ee94fe51dcd3241c1831`

## Negative inventory

| ID | 4.0 status | Statement | Rationale |
| --- | --- | --- | --- |
| NI-01 | ABSENT_IN_4_0_0 | support for `2025-11-25` or an earlier/session-era revision | Soklet 4.0 is intentionally scoped to the modern 2026 profile and does not revive session-era behavior. |
| NI-02 | ABSENT_IN_4_0_0 | more than one production protocol profile | A second production profile requires the deferred R2C dispatcher decomposition and separate approval. |
| NI-03 | ABSENT_IN_4_0_0 | `initialize`, sessions, GET/SSE replay, `MCP-Session-Id`, or `Last-Event-ID` behavior | The supported transport remains request-scoped and sessionless. |
| NI-04 | ABSENT_IN_4_0_0 | a public protocol-version enum, codec/profile SPI, arbitrary supported-versions builder, or service loader | Profile selection stays internal and bounded to the sole production revision. |
| NI-05 | ABSENT_IN_4_0_0 | automatic Java `@Deprecated` annotations or Javadoc `@deprecated` tags derived only from MCP feature-lifecycle status | MCP feature lifecycle and Soklet Java API lifecycle remain independent axes. |
| NI-06 | ABSENT_IN_4_0_0 | Soklet-owned task persistence or workers, Triggers & Events, or a general server-event family | The Tasks extension delegates durable state and execution to the application; Soklet still owns no task store, worker runtime, general trigger/event lifecycle, or arbitrary server-event family. |
| NI-07 | ABSENT_IN_4_0_0 | arbitrary server-side extension advertisement or an arbitrary-method router | Soklet advertises and routes the explicitly implemented Tasks extension only; opaque client settings do not create arbitrary server support or routing. |
| NI-08 | ABSENT_IN_4_0_0 | dynamic/scoped tool and prompt catalogs, progressive discovery, or a generalized catalog provider SPI | Tool and prompt catalogs remain immutable and caller-neutral in 4.0. |
| NI-09 | ABSENT_IN_4_0_0 | ETags, `If-None-Match`, `304`, uploads, range reads, or hierarchy | Resource representation and transfer evolution remains outside the 4.0 scope. |
| NI-10 | ABSENT_IN_4_0_0 | built-in OAuth, DPoP, workload identity, delegation, or human-presence policy | Authentication and identity policy remain application-owned rather than built into core Soklet. |
| NI-11 | ABSENT_IN_4_0_0 | stdio, HTTP/2, or a public transport abstraction | McpServer remains a dedicated HTTP/1.1 listener without a public transport SPI. |
| NI-12 | ABSENT_IN_4_0_0 | TLS termination, which remains Soklet's longstanding product non-goal and is not contingent on the MCP transport roadmap | TLS termination remains Soklet's longstanding deployment-boundary non-goal. |
| NI-13 | ABSENT_IN_4_0_0 | renaming the existing subscription API | The existing subscription API name remains stable for 4.0. |
| NI-14 | ABSENT_IN_4_0_0 | closure of unrelated inherited blockers, scheduled evidence, or unrelated downstream work | MCP roadmap closure does not imply completion of unrelated release work. |

## Deferred features

### DF-01 — Same-revision spec/conformance growth, including Tasks gate coverage

- Trigger: A release decision to gate the existing Tasks scenarios, or a newly published upstream package/scenario, erratum, or compatible addition for `2026-07-28`
- Landing zone: Existing 2026 profile plus reviewed openness disposition and regenerated pins/goldens; for Tasks, repin to a reviewed Tasks-capable suite commit, add the runnable scenario profiles, and record the notification scenario through reviewed-SKIPPED until the upstream runner observes its stream
- Pre-release hedge: The alpha.10 release gate is documented as pre-Tasks; the separate alpha.11 nine-scenario Tasks run is retained only as local non-gated evidence, alongside the R2A index, openness inventory, and RC upstream-release check
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

### DF-03 — Legacy/session-era compatibility

- Trigger: Concrete demand and separate product/release decision
- Landing zone: Separate pipeline, artifact, server mode, or major line; never a string in the modern registry
- Pre-release hedge: Explicit non-goal and maintenance policy
- Evidence classification: `planned`
- Test evidence: None.
- Negative-inventory keys: `NI-01`, `NI-03`, `NI-04`
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
