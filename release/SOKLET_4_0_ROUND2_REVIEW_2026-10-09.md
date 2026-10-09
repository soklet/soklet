# Soklet 4.0 Round 2 review — October 9, 2026

## Scope

Reviewed `SOKLET_4_0_DEEP_REVIEW_ROUND2_2026-10-08.md` against core HEAD
`2c0a6e41f7f383ab0551f795a0303e9ae19d93fa` and the current working tree.
The report reviewed older commit `b76a218b`; its stale test and API assertions
were checked against the intervening fixes before making changes.

All 139 primary section 7 findings (36 medium, 103 low) have a disposition
below. Related aliases and round 1 residuals are grouped with their correction.
This does not claim every suggested feature or policy change was implemented.
Raw runs, probes and screenshots are not added to Git.

## Changes with the largest effect

- Finite HTTP responses use transport-owned framing and valid final status codes.
- Streaming cancellation, cleanup, accounting and observer ordering are corrected.
- Slow SSE headers no longer monopolize application handler slots; read, handler
  and write deadlines stay active through admitted graceful drain.
- MCP session, grant, invalidation, numeric conversion and dispatch races are corrected.
- Request parsing, proxy trust, static-file identity and incremental route indexes
  preserve strict security checks with more accurate behavior and diagnostics.
- Source, migration and website documentation match the current implementation.

## Approved public API amendment

Exactly two default methods were approved and added, with no new public types:

```java
// MetricsCollector
default void willWriteResponseStream(
     @NonNull StreamingResponseHandle streamingResponseHandle);

// LifecycleObserver
default void willWriteResponseStream(
     @NonNull StreamingResponseHandle streamingResponseHandle);
```

A transport invokes preparation only when it guarantees a later terminal callback,
using the same handle and original dispatched request. Preparation precedes
handling finish; a terminal callback arriving early is buffered without waiting.
Without preparation, collectors finish at response handoff. Finite replacements
and duplicate callbacks do not leak state or create a second observation.
Prepared stream lifetimes use a captured monotonic start; metrics and lifecycle
receive the same handle and aligned terminal duration despite source clock changes
or delayed handling-finish callbacks.
The phase 4 signature ledger and reflection digest record only this amendment.

## Validation

Final checks passed against the frozen working source. Java builds ran serially,
offline, with 512 MiB heaps and two active processors per JVM.

| Check | Result |
| --- | --- |
| Core JDK 17 | 4,640 tests; zero failures/errors; 163 expected skips |
| Core JDK 21 and 25 | 4,676 tests each; zero failures/errors; four expected skips each |
| JDK 21 static analysis and SpotBugs | Passed; no new SpotBugs exclusions |
| JDK 17 API compatibility and MCP freezes | Passed; exactly two approved default methods; 776 reviewed incompatibilities unchanged |
| Servlet javax / Jakarta / OTel on JDK 17, 21 and 25 | 222 / 234 / 70 tests per JDK; all passed against the same fresh core JAR |
| Toy Store JDK 25 | 34 tests; zero failures/errors; one opt-in Docker smoke skipped |
| Current release matrix and self-test | Passed; privacy and finite-bound inventories match current source |
| Documentation | API/source links, pinned Javadoc indexes, 31 compiled Java snippets and snippet negative controls passed |

The OTel exporter regression also verifies actual terminal span timestamps after
delayed simulator handling finish. Current API coverage is 304 MCP owners and
2,231 signatures including the two cross-cutting signatures.
These are working-source checks. Historical candidate, D1 and release-history
artifacts retain their original commits and were not refreshed. An immutable
candidate still needs its required exact-commit gates, including the opted-in
Toy Store Docker smoke, before publication approval.

The current lifecycle release harness inventory also remains unchanged. Its
verifier and full self-test fail the source-bound shutdown-timeout exclusion
closure check. Current source witnesses, missing inventory rows and semantic
scope fits must be reconciled before candidate acceptance and publication.
The constructor-delegation scanner regression passes independently, and outer
test guards were corrected without changing local deadlines or assertions.
Future candidate acceptance still requires complete lifecycle inventory and
semantic closure; these development checks do not establish that release gate.

## Every primary section 7 finding

“Implemented” identifies a code correction. “Docs” identifies documentation as the chosen remedy. “Retained” identifies a policy or limitation that remains; it does not mean fixed. Validation below distinguishes tested work from pending checks.

### 7.1 — MCP protocol, adapter and sessions (22)

- Implemented: **K001, K002, N008, N049, N001, N012, N048, N051, N170**. K001 deliberately uses a sanitized bounded diagnostic rather than attaching sensitive Throwable/Request carriers. K002 uses known built-in listener authorities with conservative unresolved-host handling. N048 keeps malformed-envelope validation and request-limiter precedence.
- Docs chosen: **N052, N006, N055, N185**. N052's own verifier explicitly accepts correcting setup guidance while retaining narrow headerless inference. Use a dedicated single-legacy-revision endpoint for hosts omitting selectors; static-header guidance must disclose initialize-mirror restrictions.
- Retained policy/design: **N010, N004, N009, N011, N014, N047, N053, N169**. N010 retains 4 simultaneous/64 attempts per sliding second/256 partitions, so tenant burst rejection remains. N009 retains a shared delivery quota. N011 retains legacy four-worker/64dispatch maintenance throughput. N014 retains the three-failure policy; N047 retains clean completion with client reconciliation duty. N053 retains explicit selector routing before negotiation. N169 retains total grant-lifetime expiry retiring the session; its CLIENT_CANCELED classification is still a disclosed ergonomic residual. N004 retains the documented June prompt projection limit.
- Implemented diagnostic correction, retained framing: **N005**. Unsupported-version retry lists now contain only profiles matching the selected wire era. Mixed-era frames remain rejected, and the rejected legacy selector is not offered back as a retry.

### 7.2 — MCP transport, subscriptions and lifecycle (12)

- Implemented: **N023, N027, N030, N032, N188**. `TransportProcessOwnershipTests#standaloneHookJoinsCoreBeforeRunningCleanupOnSigterm` already tests an actual child process and signal; no duplicate fake test was added.
- Mitigated with explicit sizing limit: **N025**. Modern maintenance concurrency now derives from existing protocol/application budgets (defaults31), rather than a fixed4. This addresses the reported wave but still cannot sustain arbitrary listen count/callback latency/lease combinations. `MCP.md:2437` discloses this. Legacy N011/N015 throughput remains.
- Docs chosen: **N019** (and alias N165), preserving explicit-host port relaxation while automatic loopback aliases use the bound port.
- Retained policy/design/debt: **N021, N022, N026, N029, N031**. N021 broadens security host grammar; N022 would reverse explicit input-FIN cancellation; N026 would change fail-closed modern reauthorization. N029's 10ms full subscription scan is genuine scheduler performance debt. N031 is the accepted bounded/deferred metric-delivery ownership tradeoff, not a flush guarantee.

### 7.3 — MCP dispatch, values, tasks, localization, metrics and simulation (16)

- Implemented: **N033, N036, N057, N037, N039, N043, N044, N194, N196**. N033 fixes cascade and drains accepted tickets on existing physical workers; a genuinely new handoff may still reject during an external executor's physical return gap. Do not promise universal custom-executor acceptance. N043 fixes framework resource catalog localization; undocumented skills/list expansion remains absent.
- Retained policy/API/observation decisions: **N195, N040, N042, N058**. N195 matches the deliberate security contract that long-lived contexts discard headers/body/trace/baggage; `MCP.md:2431` directs policies to current principal/partition/applicationContext. N040's result-only catalog revocation is explicitly scoped in `DefaultMcpServer.java:977–980` and `taskSnapshot`'s COMPLETED guard; the report classifies this all-state extension low, not a cross-principal leak. N042 can use client-safe application error codes without a new factory. N058's synthetic503 after a pre-response abort remains semantically imperfect but requires an observation-contract decision.
- Docs chosen: **N060, N193**.
- Implemented: **N056**. Dedicated bounded workers deliver generic GET/DELETE finish observations away from selectors. A reservation is acquired before start and retained through physical finish; saturation skips the pair with a fixed diagnostic. Transport end during a blocked start is remembered and finish is delivered once start returns. Callback and executor residuals retain truthful shutdown evidence.

### 7.4 — HTTP streaming (23)

- Implemented: **N078, N079, N174, N175, K007, N065, N068, N069, N073, N074, N075, N076, N081, N082, N083**. N175 is intentionally limited to typed cancellation plus the identified JDK ended-Deflater retry; independent cleanup failures remain observable. N073/N074 conservatively keep ambiguous file/transfer failures WRITE_FAILED rather than suppressing source defects.
- Partial noise mitigation: **N176**. Typed cancellation remains quiet. An ambiguous `IOException` after cancellation is retained as contextual producer evidence through the existing single prepaid diagnostic allowance, after cleanup and physical owners exit. Actual close, abort and cleanup-deadline failures keep diagnostic priority; the producer evidence does not change the elected terminal outcome. Throwable graph inspection is bounded and iterative, and suppression attachment runs outside the coordinator lock on the counted diagnostic worker. Detected or uncertain back edges are skipped. Producer evidence arriving after the diagnostic worker has physically returned is not dispatched again under the single-event limit.
- Mitigated: **N061**. FJP waits now use ManagedBlocker, addressing common-pool starvation while keeping synchronous publisher writes. This is not an asynchronous demand/queue redesign and the pool's spare-thread ceiling still applies.
- Retained default/capacity policy: **N067**. Defaults still permit roughly256MiB copied queued payload plus overhead. `production-readiness.md:100` now gives the combined-capacity warning and smaller-queue guidance. An aggregate byte budget or changed defaults requires an explicit default/admission decision; this finding is disclosed, not fixed.
- Implemented in OTel and verified on JDK 17/21/25: **N085, N084, N166**. Routine cancelation/disconnect causes do not become server errors; finite replacements end at handoff and duplicate or reordered terminal callbacks do not create extra observations.
- Docs chosen: **N063, N167**.

### 7.5 — SSE (15)

- Implemented: **N086, N088, N094, N089, N090, N091, N093, N095, N096**. N086 uses bounded virtual header phases and existing application admission; N091 keeps write deadlines alive until writers exit. N094/N095/N096 preserve truthful final response/activation semantics in live and simulator paths.
- Retained callback/API/simulation semantics: **N092, N097, N099, N177**. Accepted-then-queue-rejected callbacks remain N092's classification debt. N097 still lacks an externally readable handling-failure cause but can document the SPI limitation without an accessor expansion. N099 synchronous simulated delivery is explicitly unlike production scheduling/backpressure. N177 matched-route HEAD/OPTIONS still count as handshake rejection; state the metric semantics if retained.
- Docs chosen: **N098, N100** (initializer exception contract and absent broadcast/drop metrics).

### 7.6 — Application, lifecycle, shutdown and observers (16)

- Implemented: **N107, N108, N103, N104, N114, N117, N202**. N114 now has an explicit distinct HTTP/SSE transport-identity diagnostic; N202 rejects `StreamingResponseBody` in `Response.body` with guidance to use `MarshaledResponse.stream`.
- Partial runtime mitigation plus completed docs alternative: **K008**. During started/quiescing lifetime, matched stable handle lookup works without creating connections. After SSE termination, `releaseTerminatedEvidence` sets started=false and clears maps (`DefaultSseServer.java:4586`); `acquireBroadcaster` then returns empty even if sibling HTTP still drains. This runtime residual is real. The report explicitly permits the chosen ifPresent/retained-handle documentation remedy. Source/Javadoc examples and generated website artifacts now use optional notification delivery. The lifetime gap itself remains.
- Retained ownership/liveness/cause shape: **N116, K009, N101, N109**. N116 respects the approved explicit-observer sink ownership; examples must include an intended logging sink. K009's CompletionStage is a bounded asynchronous handoff, so callers requiring dependent completion must join it. N101's non-daemon listener can remain alive behind a blocked inline callback; current SokletApplication Javadoc now discloses that. N109 retains cause-wrapper differences after unified startup classification.
- Docs chosen: **N105, N112, N118**.
- Implemented: **N111**. Built-in HTTP/SSE delegates acquire the outer graph permit at dispatch, carry a private one-use handoff through queued work and copied requests, and release it on response, timeout, cancelation or failure. Generic custom requests still use the sealed admission fence; replayed, closed and foreign-owner tokens fail closed.

### 7.7 — Core HTTP, routing, responses, metrics and processor (24)

- Implemented: **N119, N122, N132, N145, N181, K011, K012, K014, N120, N121, N127, N129, N130, N134, N141, N146, N183**. N122 selectively decodes only the named cookie while preserving strict whole-map/scalar-duplicate behavior. N132/N134 use fresh contained native realpaths without directory scans. N130 improves safe declaration identity but keeps processor method-element diagnostics. K011/K012 fix wire framing/final status without public-builder expansion. N120 has the narrow core-construction400/PROTOCOL_ERROR catch at `McpHttpServerRuntime.java:4694`.
- Source documentation/diagnostic corrections implemented: **N125, N131, N136, N204**.
- Retained protocol classification: **N139**. This SHOULD-level compatibility/category concern is not a demonstrated bypass; frozen enum expansion and distinct status categories would be policy/API changes. State the current strict parser and categories accurately.
- Website docs chosen: **N140, N148**.

### 7.8 — API, docs and tests (11)

- Implemented or already correct current HEAD: **N158, N159, N160, N161, N184, N206**. N159 adds a real case-insensitive fixture requirement and macOS17/21 gate; a skipped/Linux-only result is insufficient. N160 is the deterministic buffered-request/observed-FIN regression, not a restored obsolete test. N161 was already corrected after the report commit. N184/N206 are source Javadoc/message corrections.
- Docs chosen: **N151, N154, N155**.
- Current working-tree checks: **N149, N157**.
