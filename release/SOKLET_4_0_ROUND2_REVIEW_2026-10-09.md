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

The October 9 verification report was then checked against pushed core
`64d0ee8d`, OTel `e67a60d` and website `6d87629`. The follow-up below corrects
the reproduced regressions and inaccurate disclosures. It adds no public
signatures beyond the two defaults already approved and committed.

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

## Verification follow-up

- **A1, N086, N107:** quiesce closes idle and incomplete SSE header phases,
  while parsed and queued handshakes retain graceful-drain ownership. Separate
  idle and partial-header regressions use the actual default shutdown policy.
- **A2, N074, F203:** file downloads and SSE reads use typed socket boundaries.
  File transfer copies through a socket adapter so a sink failure is distinct
  from a closed or truncated source. Original socket causes reach observations;
  OS message matching is unnecessary. This sacrifices the native sendfile path.
- **A3, A7, F045:** modern maintenance uses at most half the existing protocol
  and handler budgets when both pools have at least two slots, 16 at defaults.
  Its minimum of one cannot reserve peer capacity in a singleton pool.
  It does not promise arbitrary renewal
  waves will fit a lease. The legacy four-worker limit remains disclosed.
- **A4, N056:** GET observations reserve up to two lifetimes per allowed session
  (512 at defaults), separately from transient controls (132 at defaults).
  Capacity diagnostics are non-error and rate limited; start/finish pairing is
  preserved through transport close and blocked observer callbacks.
- **A5, N005:** a modern probe at a legacy-only endpoint receives an empty
  HTTP 400, permitting initialize fallback. Other unsupported selections retain
  a nonempty configured-revision error. Mixed-era frames remain rejected.
- **A6, N175, N065:** an expected canceled ZIP cleanup consequence is no longer
  attached where producer-evidence scanning can rediscover it; canceled adopted
  encoders discard their cleanup tail. Live HTTP ZIP and interruptible-upstream
  regressions supplement the coordinator tests. **F164 remains partial:** the
  narrow owned-ZIP/ended-Deflater classifier does not suppress arbitrary or
  ambiguous archive failures after cancellation.
- **B1–B13:** dispatcher progress, valid HEAD Content-Length, oversized SSE
  parsing, fresh decorator admission, proxy fallback, ForkJoin compensation
  rejection, 503 on exhausted authorization retries, session error precedence,
  SSE capacity diagnostics, interceptor parity and finite 426 Upgrade headers
  are corrected. HEAD 204 still has no Content-Length.
- **B15:** OTel replaces the global weak-set lock with a concurrent weak identity
  set, retaining duplicate-terminal protection. No whole-request performance
  improvement is claimed.
- **B17:** lifecycle scanner provenance, dynamic-node cardinality and qualified
  waits are reviewed against actual source. Tests use explicit ordinary policies
  or precise method guards for coherent sequential matrices. Socket helper
  deadlines and event-loop joins are bounded where the review found gaps.
- **F116, N030, N105, N117, N206:** reserved application metadata is checked in
  both remaining builders; canceled MCP startup stops its listener loop and is
  tested by natural child-process exit; terminal reports use residual-component
  labels; simulator omission categories and the SSE shutdown message are fixed.
- **A8, C1–C10, F228 and retained-limit disclosures:** source, migration and
  website text now match framing, forwarded-hop trust, callback scheduling,
  capacity, MIME validation and legacy setup behavior. Startup observers show
  notifications; required work uses startup sequencing and ShutdownCleanup.
  Website examples are compiled, including the formerly unchecked keyed SSE
  example. Transient screenshots and logs remain outside Git.

**Retained limitations:** N001's diagnostic-cause merging, N012's global-byte
reclamation progress gap and N051's template acknowledgement residual remain
partial. K008 still has the decorated-SSE lifetime gap; optional broadcaster
delivery is the disclosed remedy. N176 retains ambiguous post-cancel producer
evidence under one diagnostic allowance. B14 relies on native filesystem
normalization and does not promise canonical spelling on all mounts; B16/N130
retain cosmetic source-position limitations. Capacity/default, observation and
protocol decisions listed below remain as stated. These are not claimed as
complete fixes.

## Validation of the verification follow-up

Java builds run serially, offline, with 512 MiB heaps and two active processors
per JVM. Raw output stays in temporary storage.

| Check | Result |
| --- | --- |
| Core JDK 17 | 4,665 tests; zero failures/errors; 168 expected skips |
| Core JDK 21 and 25 | 4,701 tests each; zero failures/errors; four expected skips each |
| Final lifecycle harness edits on JDK 17, 21 and 25 | 56 focused tests per JDK; zero failures/errors/skips |
| Repaired JDK 17 full-suite failures | 73 focused tests; zero failures/errors; two expected skips |
| JDK 21 static analysis and SpotBugs | Passed; zero SpotBugs bugs; no exclusions added |
| JDK 17 API compatibility and MCP freezes | Passed; no additional public signatures in this follow-up |
| Servlet javax / Jakarta / OTel on JDK 17, 21 and 25 | 222 / 234 / 70 tests per JDK; all passed against the same fresh core JAR |
| Toy Store JDK 25 | 34 tests; zero failures/errors; one opt-in Docker smoke skipped |
| Privacy / finite-bound inventories | Passed against current main source: 8,032 sites / 285 candidates |
| Release matrix self-test | Passed |
| Documentation | API/source links, pinned Javadoc indexes, 32 compiled Java snippets and 47 negative controls passed; website regenerated |
| Lifecycle release harness inventory | Passed; 1,863 scopes across 286 lifecycle files, 69 source-bound helper proofs; full self-test passed (151 named cases) |

The first full JDK 17 run found obsolete assertions for the new observation
categories, maintenance capacity, admission precedence and metadata validation.
Those tests now assert the reviewed contracts while preserving coverage of
physical ownership, queued maintenance and secret-free errors. The new startup
child-process test also needed process-exit reserve beyond its five-second
lifecycle envelope; the child exited naturally after about 5.2 seconds.

The full matrix ran against the same frozen runtime and test source on all
three JDKs. The last test-only changes added exact outer guards and an explicit
ordinary fixture policy; their seven affected classes were then rebuilt and
verified on all three JDKs. No runtime source changed between these checks.

The lifecycle inventory checks registered lifecycle envelopes, source discovery
and reviewed control composition. Some inspected raw transport fixtures still
rely on the enclosing JUnit timeout for socket operations without aggregate
operation deadlines; this gate does not promise every blocking I/O operation
has its own wall-clock bound.

These are working-source checks. Historical candidate, D1 and release-history
artifacts retain their original commits. An immutable candidate still needs its
required exact-commit gates, including the opted-in Toy Store Docker smoke,
before publication approval. Historical host/profile evidence does not establish
fresh conformance or Apps qualification for this candidate.

## Every primary section 7 finding

“Implemented” identifies a code correction. “Docs” identifies documentation as the chosen remedy. “Retained” identifies a policy or limitation that remains; it does not mean fixed. The validation section distinguishes tested work from pending release gates.

### 7.1 — MCP protocol, adapter and sessions (22)

- Implemented: **K001, K002, N008, N049, N048, N170**. K001 uses a sanitized bounded diagnostic on POST; generic GET/DELETE finish callbacks still receive the exact Throwable. K002 uses known built-in listener authorities with conservative unresolved-host handling. N048 now defers operation-parameter errors on session-enabled 2025 non-initialize requests through admission, applicable limiting and session binding; envelope/profile/metadata checks remain earlier. N001 still merges some diagnostic causes; N012 still lacks a no-progress guard for the global-byte reclamation case; N051 still leaves the templates/list acknowledgement residual. These three are partial corrections, not full closure.
- Docs chosen: **N052, N006, N055, N185**. N052's own verifier explicitly accepts correcting setup guidance while retaining narrow headerless inference. Use a dedicated single-legacy-revision endpoint for hosts omitting selectors; static-header guidance must disclose initialize-mirror restrictions.
- Retained policy/design: **N010, N004, N009, N011, N014, N047, N053, N169**. N010 retains 4 simultaneous/64 attempts per sliding second/256 partitions, so tenant burst rejection remains. N009 retains a shared delivery quota. N011 retains legacy four-worker/64dispatch maintenance throughput. N014 retains the three-failure policy; N047 retains clean completion with client reconciliation duty. N053 retains explicit selector routing before negotiation. N169 retains total grant-lifetime expiry retiring the session; its CLIENT_CANCELED token classification is retained and now explicitly documented in the enum and observability guide. N004 retains the documented June prompt projection limit.
- Implemented diagnostic correction, retained framing: **N005**. The reviewed era-filtered retry correction introduced empty supported lists. The follow-up uses ordinary empty HTTP `400` for a modern probe at a legacy-only endpoint, allowing client initialize fallback, and preserves a nonempty configured-revision diagnostic for other unsupported selection. Mixed-era frames remain rejected. The fallback and supported-list contracts have regression coverage.

### 7.2 — MCP transport, subscriptions and lifecycle (12)

- Implemented: **N023, N027, N030, N032, N188**. N030 was partial at the reviewed commit: the listener port closed, but connection loops retained JVM liveness. Follow-up startup cancellation now stops the loop while preserving running graceful drain; its natural child-process-exit regression passes on JDK 17, 21 and 25. `TransportProcessOwnershipTests#standaloneHookJoinsCoreBeforeRunningCleanupOnSigterm` already tests an actual child process and signal; no duplicate fake test was added.
- Mitigated with explicit sizing limit: **N025**. Modern maintenance concurrency now derives from half the existing protocol/application budgets (defaults 16), rather than a fixed4 or nearly all handler slots. This addresses the reported wave but still cannot sustain arbitrary listen count/callback latency/lease combinations. `MCP.md:2437` discloses this. Legacy N011/N015 throughput remains.
- Docs chosen: **N019** (and alias N165), preserving explicit-host port relaxation while automatic loopback aliases use the bound port.
- Retained policy/design/debt: **N021, N022, N026, N029, N031**. N021 retains LDH hostname labels and rejects underscores; the public hostname Javadoc and deployment guidance now disclose the restriction; N022 retains input-FIN cancellation even for notifications/DELETE; its narrower exemption was not implemented; N026 retains immediate retirement on renewal failure, including framework handler-capacity rejection; this is now disclosed. N029's 10ms full subscription scan remains performance debt; MCP sizing guidance now discloses periodic scanning cost. N031 retains bounded/deferred metric delivery without a flush guarantee. Final metrics can arrive after cleanup; the collector, MCP and OTel docs now disclose that limit.

### 7.3 — MCP dispatch, values, tasks, localization, metrics and simulation (16)

- Implemented: **N033, N036, N057, N037, N039, N043, N044, N194, N196**. N033 fixes cascade and drains accepted tickets on existing physical workers; a genuinely new handoff may still reject during an external executor's physical return gap. Do not promise universal custom-executor acceptance. N043 fixes framework resource catalog localization; undocumented skills/list expansion remains absent.
- Retained policy/API/observation decisions: **N195, N040, N042, N058**. N195 retains header-free derived projection contexts; the original request, including headers, remains retained and available to the authorizer. No prior owner-approved security contract is asserted. MCP and public policy/localization Javadocs now describe current identity/applicationContext use. N040's result-only catalog revocation is scoped by `taskSnapshot`'s COMPLETED guard and now disclosed for INPUT_REQUIRED/update workflows; the report classifies this all-state extension low, not a cross-principal leak. N042 can use client-safe application error codes without a new factory. N058's synthetic503 after a pre-response abort remains semantically imperfect but requires an observation-contract decision.
- Docs chosen: **N060, N193**.
- Implemented: **N056**. Dedicated bounded workers deliver generic GET/DELETE finish observations away from selectors. At the reviewed commit, whole-stream reservations saturated at132 even though default sessions allowed256. The follow-up reserves up to two GET lifetimes per globally permitted session (default 512), separately from transient controls (default 132), with non-error once-per-minute saturation diagnostics and paired-observation regression coverage. Transport end during a blocked start is remembered and finish is delivered once start returns. Callback and executor residuals retain truthful shutdown evidence.

### 7.4 — HTTP streaming (23)

- Implemented: **N078, N079, N174, N175, K007, N065, N068, N069, N073, N074, N075, N076, N081, N082, N083**. At the reviewed commit N175 suppressed an expected ended-Deflater cleanup failure locally, but later producer-evidence scanning re-reported it. The follow-up removes that expected cleanup consequence from suppressed evidence and corrects N065 canceled disposal; live and coordinator regressions cover both paths. N073/N074 now distinguish typed socket transfer failures from actual file-source failures; SSE socket boundaries are typed too. N081 omits routine drain diagnostics and uses a non-error category for observation-capacity omissions. File-source negative controls and live file/ZIP/upstream abort regressions pass.
- Partial noise mitigation: **N176**. Typed cancellation remains quiet. An ambiguous `IOException` after cancellation is retained as contextual producer evidence through the existing single prepaid diagnostic allowance, after cleanup and physical owners exit. Actual close, abort and cleanup-deadline failures keep diagnostic priority; the producer evidence does not change the elected terminal outcome. Throwable graph inspection is bounded and iterative, and suppression attachment runs outside the coordinator lock on the counted diagnostic worker. Detected or uncertain back edges are skipped. Producer evidence arriving after the diagnostic worker has physically returned is not dispatched again under the single-event limit.
- Mitigated: **N061**. FJP waits now use ManagedBlocker, addressing common-pool starvation while keeping synchronous publisher writes. This is not an asynchronous demand/queue redesign and the pool's spare-thread ceiling still applies.
- Retained default/capacity policy: **N067**. Defaults still permit roughly256MiB copied queued payload plus overhead. `production-readiness.md:100` now gives the combined-capacity warning and smaller-queue guidance. An aggregate byte budget or changed defaults requires an explicit default/admission decision; this finding is disclosed, not fixed.
- Implemented in OTel and verified on JDK 17/21/25: **N085, N084, N166**. Routine cancelation/disconnect causes do not become server errors; finite replacements end at handoff and duplicate or reordered terminal callbacks do not create extra observations.
- Docs chosen: **N063, N167**.

### 7.5 — SSE (15)

- Implemented: **N086, N088, N094, N089, N090, N091, N093, N095, N096**. N086 uses bounded virtual header phases and existing application admission; N091 keeps write deadlines alive until writers exit. N094/N095/N096 preserve truthful final response/activation semantics in live and simulator paths.
- Retained callback/API/simulation semantics: **N097, N099, N177**. N092 was resolved by moving queue waiting after parsing: expiry is HANDSHAKE_TIMEOUT, and the queue fixture checks that outcome. N097 still lacks an externally readable handling-failure cause; RequestHandler Javadoc and the custom SSE guide now disclose the SPI limitation and transport duties without an accessor expansion. N099 synchronous simulated delivery is explicitly unlike production scheduling/backpressure. N177 matched-route HEAD/OPTIONS still count as handshake rejection; this preserves the current rejected-handshake metric semantics.
- Docs chosen: **N098, N100** (initializer exception contract and absent broadcast/drop metrics).

### 7.6 — Application, lifecycle, shutdown and observers (16)

- Implemented: **N107, N108, N103, N104, N114, N117, N202**. N114 now has an explicit distinct HTTP/SSE transport-identity diagnostic; N202 rejects `StreamingResponseBody` in `Response.body` with guidance to use `MarshaledResponse.stream`.
- Partial runtime mitigation plus completed docs alternative: **K008**. Direct built-in SSE keeps broadcaster lookup available through the outer HTTP drain. A decorated SSE transport can retire earlier, making lookup empty while sibling HTTP still drains. This narrower runtime residual remains. The report explicitly permits the chosen ifPresent/retained-handle documentation remedy. Source/Javadoc examples and website source now use optional notification delivery. Generated website artifacts and compiled snippet checks are refreshed and pass. The lifetime gap itself remains.
- Retained ownership/liveness/cause shape: **N116, K009, N101, N109**. N116 respects the approved explicit-observer sink ownership; examples must include an intended logging sink. K009's CompletionStage is a bounded asynchronous handoff, so callers requiring dependent completion must join it. N101's non-daemon listener can remain alive behind a blocked inline callback; current SokletApplication Javadoc now discloses that. N109 retains cause-wrapper differences after unified startup classification.
- Docs chosen: **N105, N112, N118**.
- Implemented: **N111**. Built-in HTTP/SSE delegates acquire the outer graph permit at dispatch, carry a private one-use handoff through queued work and copied requests, and release it on response, timeout, cancelation or failure. A decorator redispatching a copied request with a spent or foreign handoff must acquire fresh admission while the fence is OPEN. It cannot reuse the earlier permit, and the sealed drain fence rejects fresh dispatches.

### 7.7 — Core HTTP, routing, responses, metrics and processor (24)

- Implemented: **N119, N122, N132, N145, N181, K011, K012, K014, N120, N121, N127, N129, N130, N134, N141, N146, N183**. N122 selectively decodes only the named cookie while preserving strict whole-map/scalar-duplicate behavior. N132/N134 use fresh contained native realpaths without directory scans. Linux case-insensitive alias canonicalization has not been verified; public docs no longer promise canonical spelling on every filesystem. N130 improves safe declaration identity but keeps processor method-element diagnostics. K011/K012 fix wire framing/final status without public-builder expansion. N120 has the narrow core-construction400/PROTOCOL_ERROR catch around core Request construction in `McpHttpServerRuntime`.
- Source documentation/diagnostic corrections implemented: **N125, N131, N136, N204**.
- Retained protocol classification: **N139**. This SHOULD-level compatibility/category concern is not a demonstrated bypass; frozen enum expansion and distinct status categories would be policy/API changes. Migration and response-writing docs now state the current strict parser and categories.
- Website docs chosen: **N140, N148**.

### 7.8 — API, docs and tests (11)

- Implemented or already correct current HEAD: **N158, N159, N160, N161, N184**. N159 adds a real case-insensitive fixture requirement and macOS17/21 gate; a skipped/Linux-only result is insufficient. N160 is the deterministic buffered-request/observed-FIN regression, not a restored obsolete test. N161 was already corrected after the report commit. N184 is a source Javadoc correction. N206 was incorrectly claimed implemented at the reviewed commit; its SSE shutdown message is corrected in the follow-up with regression coverage.
- Docs chosen: **N151, N154, N155**.
- Current working-tree checks: **N149, N157**.
