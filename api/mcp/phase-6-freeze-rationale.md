# MCP Phase 6 API-freeze rationale

Date: 2026-08-14

Telemetry amendment reviewed: 2026-08-15

Structured trace-log amendment reviewed: 2026-08-15

Phase 4 admission-controller naming amendment reviewed: 2026-08-16

Greenfield cohesion naming amendment reviewed: 2026-08-17

Greenfield localization-result simplification amendment reviewed: 2026-08-17

Greenfield localization-context builder amendment reviewed: 2026-08-17

Final greenfield API polish amendment reviewed: 2026-08-17

Greenfield public-record elimination amendment reviewed: 2026-08-18

Greenfield typed-request-state amendment reviewed: 2026-08-18

This record approves the Phase 6 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a compatibility decision; it
does not by itself establish Phase 6 conformance or release-candidate status.

The review decision is summarized below. Its durable repository-owned evidence
is the [Phase 6 owner inventory](phase-6.includes), the reviewed
[signature snapshot](phase-6.signatures.jsonl), the
[compatibility set](current-incompatibilities.jsonl), and the aggregate-gate
contract in the [API inventory README](README.md#running-the-gates). The
snapshot checked in here is byte-for-byte identical to a fresh extraction from
the current full japicmp report.

## Compatibility and ownership model

The reviewed current incompatibility set contains exactly 618 canonical
symbols and has SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.
The matching full japicmp report establishes an exact owner universe of:

- 133 Phase 4 owners;
- 36 Phase 5 owners;
- 64 Phase 6 owners;
- zero provisional owners; and
- 233 MCP owners, plus 39 reviewed non-MCP owners for 272 current-side owners.

The 64 Phase 6 owners are the exact sorted entries in `phase-6.includes`.
The Phase 4 owner inventory remains 133, while its signature snapshot includes
the one compatible
`LogEventType.MCP_TRACE_CORRELATION` field. Among the reviewed Phase 4 host
localization amendments, only `McpServer.getLocalizationControl()` added a
source incompatibility at that amendment checkpoint; it was one of exactly
three localization-host amendments. `provisional.includes` is intentionally
empty after the telemetry amendment described below.

The later greenfield admission-controller naming amendment replaces one
Phase 4 owner name and the corresponding server getter and builder input. It
does not add an owner or change any Phase 6 descriptor; its current Phase 4 and
compatibility hashes are recorded in the Phase 4 rationale.
The 2026-08-17 cohesion amendment then replaces names one-for-one in all three
frozen phases without changing their owner or signature counts. The later
same-day localization-result simplification removes one Phase 6 nested owner
and leaves Phase 4, Phase 5, and the incompatibility ledger unchanged. The
subsequent context-builder amendment restores one Phase 6 nested owner while
replacing the context interface with a framework-owned final class; the
compatibility ledger again remains unchanged. The 2026-08-18 public-record
elimination amendment converts values in all three phases without changing the
owner partition. The subsequent typed-request-state amendment removes three
Phase 5 carrier owners and changes no Phase 6 descriptor.

## Frozen Phase 6 snapshot

`phase-6.signatures.jsonl` contains exactly 421 canonical records:

- 64 classes;
- zero constructors;
- 42 fields; and
- 315 methods.

The reviewed file's SHA-256 is
`69b008b685dead8e1ae66691f0e9955688b9e43740281ea0f82497df22a4dda0`.
The independent reflection contract freezes the Phase 6 JSpecify type-use
layout with SHA-256
`d829563b135bae5a0e97559ecf5d1a8dd280c4b7792a74a2f10fcf8d8017d18b`.
The 64-entry `phase-6.includes` inventory has SHA-256
`640eda42f3dd1cf1c5d8bf50e461281bc3083992de5dc83bf77a0478617606bc`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 421 records and was byte-for-
byte identical to the reviewed candidate. The aggregate freeze gate now
compares the Phase 4, Phase 5, and Phase 6 snapshots bidirectionally on every
run, and `frozen-phases` lists the contiguous sorted prefix `4`, `5`, `6`.

## Reviewed contract

The snapshot freezes the 18 current localization-owned API types described
below. After the 2026-08-15 telemetry amendment, it also freezes all 32 formerly provisional
telemetry owners alongside the previously assigned Phase 6 diagnostics,
shutdown, subscription-configuration, and simulator-adjacent owners. The
cross-cutting review fixed the following public contracts:

- Localization is library-neutral. `McpLocalizer` carries a fallback locale,
  an application `McpLocalizationContextProvider`, a whole-response failure
  policy, and a per-response callback bound; Soklet depends on no translation
  library, and the published jar retains zero runtime dependencies.
- `McpLocalizationContext` is a Soklet-owned final immutable, node-local,
  request-scoped value: one selected locale and one translation snapshot per
  admitted localizable operation, with no session identity and no cross-request
  reuse. Applications build it through `withLocale(...)`, optionally attach a
  revision, and supply only a JDK `Function` localization callback; they do not
  implement or subtype the context.
- `McpLocalizationResult` is a sealed three-variant family of final classes
  constructed through named factories. `Localized` represents text
  successfully resolved by the provider, including its own parent-locale or
  terminal-fallback behavior; `UseDefaultText` is an intentional per-field
  outcome rather than a failure, and `Failure` is fieldless so it can carry no
  application data.
- Every localization value type redacts its `toString()`. Revision values,
  coordinate identities, default text, locales, and preferences never appear in
  renderings, framework logs, exception text, or metric labels.
- `McpTextCoordinate` exposes a stable structured identity plus one versioned,
  domain-separated external key. An adapter selects exactly one key strategy
  per catalog and never falls back between strategies.
- `McpLocalizationControl` is a local-server control plane: `isEnabled()` plus
  `catalogsChanged()`. It distributes nothing, carries no locale, tenant,
  revision, or key, and throws consistently when localization is disabled.
- The three reviewed Phase 4 host amendments remain exact as corrected: direct
  `McpInvocationFeatures` input to `McpHandlerInterceptor.interceptHandler(...)`,
  abstract `McpServer.getLocalizationControl()`, and concrete
  `McpServer.Builder.localizer(McpLocalizer)`. No fourth localization-host
  descriptor was added, and the Phase 5 snapshot remains untouched.
- No `com.soklet/localization` MCP extension exists. Soklet advertises no
  localization capability, reserves and interprets no request or result
  `_meta` key, emits no locale or revision metadata, and claims no positive
  locale-aware MCP caching.
- `McpMetricsEvent` remains a sealed 23-variant hierarchy with no generic value
  or label bag. Every variant is a final class constructed through an outer
  named factory; fieldless events use shared instances. Framework-produced
  dimensions are registered endpoint paths, recognized methods or the fixed
  `<unrecognized>` sentinel, fixed outcomes, fixed termination reasons, fixed
  transport reasons, and defined protocol error codes. Direct
  application-created values retain their documented shape-only contract and
  application-owned confidentiality/cardinality.
- `McpMetricsSnapshot` retains 22 fixed default families, immutable defensive
  copies, nonnegative scalar/count validation, and four final typed aggregate
  keys with private constructors and `fromDimensions(...)` factories. Sparse
  family maps do not imply cross-map atomicity or conservation, and reset
  cannot mutate a retained snapshot.
- `CancelationSignaled` remains endpoint-and-method only. The cooperative
  token exposes one bounded `StreamTerminationReason` to the handler while the
  default metric deliberately carries no cancellation-reason, throwable,
  trace, request, or identity dimension. This preserves the adopted V10
  cardinality/privacy contract instead of converting debugging state into a
  metric label.
- Structured trace logging uses the dedicated
  `LogEventType.MCP_TRACE_CORRELATION` value rather than a generic field bag or
  an unrelated configuration diagnostic. Its message has one exact bounded
  ASCII grammar; the pseudonymous token path carries token format, non-secret
  key ID, and token, while the independently opted-in raw path carries only a
  validated MCP-metadata trace ID. The event carries no request, throwable,
  resource method, or marshaled response and creates no metric dimension.

## 2026-08-15 telemetry amendment

The 32 owners formerly listed in `provisional.includes` were reviewed as one
coherent telemetry surface after production emission, default aggregation,
Prometheus/OpenMetrics rendering, reset behavior, concurrency, downstream
OpenTelemetry mapping, privacy canaries, and public reflection contracts were
all implemented. No descriptor changed during this review. The owners moved
as-is into sorted `phase-6.includes`, `provisional.includes` became empty, and
the Phase 6 snapshot grew by exactly 247 records: 32 classes, 27 constructors,
21 fields, and 167 methods. At that amendment checkpoint, the reviewed owner
universe was 237.

## 2026-08-15 structured trace-log amendment

The trace-log closeout uses the already frozen builder controls and admitted-
request finish authority. A package-private immutable carrier validates the
exact alphabets and 184-character maximum, redacts token/raw-ID values from
diagnostic rendering, and exposes them only at the deliberate log-message
boundary. One event is attempted at the exactly-once finish authority when a
captured pseudonymous token or independently enabled raw validated trace ID is
available. With both controls at their defaults no event is emitted; absent,
invalid, all-zero, and HTTP-only contexts also emit none. Key rotation
preserves the request's captured old/new pair, and the raw opt-in never enables
pseudonymous correlation.

The public portion is exactly one compatible field appended to the Phase 4-
owned `LogEventType`, preserving every prior enum ordinal:
`MCP_TRACE_CORRELATION`. The Phase 4 snapshot therefore contains 1,053 records
(133 classes, ten constructors, 79 fields, and 831 methods) with SHA-256
`d7e9d0c303897e898eab8c485d850caa0484c74ef8b1097be0b78904f1f0c9a3`.
The 559-record incompatibility set, Phase 5 snapshot, 428-record Phase 6
snapshot, 65-owner Phase 6 inventory, and empty provisional inventory are
unchanged by this one-field amendment. The reflection layout includes public
enum fields, so the Phase 4 nullability digest advances to
`1a2c745038a6cc51c3175b42ca20f39eeca7e8f5ea82912d387f17a92fef0cad`.
Focused carrier, live request-observation, and public observability tests pass
34/34.

Later reviewed Phase 4 naming amendments rename the two
`McpRateLimitDecision` factories and the complete public admission concept to
`McpAdmissionController`, `McpServer.getAdmissionController()`, and
`McpServer.Builder.admissionController(...)`. They change no Phase 6
descriptor. Their current Phase 4 snapshot and reflection hashes are recorded
in the Phase 4 freeze rationale; the hashes and 559-record comparison
immediately above remain the exact trace-log amendment checkpoint.

## 2026-08-17 greenfield cohesion naming amendment

The Phase 6-owned trace control becomes `McpTraceCorrelationControl` instead
of `McpTraceCorrelation`; the corresponding Phase 4 server getter becomes
`McpServer.getTraceCorrelationControl()`. Localization catalog extraction
becomes
`McpLocalizationCatalog.fromEndpointRegistry(McpEndpointRegistry)` instead of
`fromHandlerResolver(McpHandlerResolver)`. `McpLocalizationResult` replaces
`fromLocalizedText(...)`, `fromFallbackText(...)`, `fromDefaultText()`, and
`fromFailure()` with the direct outcome factories `localized(...)`,
`fallback(...)`, `useDefaultText()`, and `failure()`. No pre-amendment alias is
retained because Soklet 3.6 replaces the MCP API wholesale.

At that cohesion checkpoint, the Phase 6 owner inventory contained exactly 65
entries, and the provisional inventory was empty. `phase-6.includes` had
SHA-256
`889bbef3a49e6329da88709c000d039182dccf3f1bb2ee4a0e30b285b747cb6e`.
The snapshot contained exactly 428 records - 65 classes, 32 constructors, 40
fields, and 291 methods - while its SHA-256 advanced from
`b7eb4173c4aab687c2e8a7eda91f122f2131f08c545e1bc604e2cf3a7cfc30b8`
to
`bc2328168b1dfd90c8fe812d4e1a7a1cbe3a732f6ad84f2149494ea719ccb2e4`.
The identity-sensitive reflection digest advanced from
`4f26f50a8221dbefec45703fc39a4e0616fee5599b7159174ca5166b341c80c2`
to
`2a1d72bfb68d97f7e5a67d410bf606ad16a1799d5b2a9625ffb079f0a7b222c1`.
At that amendment checkpoint, the owner partition remained 133/39/65/0 and
the signature counts remained 1,053/195/428. The generated compatibility
ledger advanced from
562 records with SHA-256
`7255791d02be0cf7b0b9e601683a2da008bd41ee3a2e48b2ae8345f8bb8d85cd`
to 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.
The generated ledger is authoritative for that net change; it is not a count
of renamed declarations.

## 2026-08-17 greenfield localization-result simplification amendment

The subsequent same-day review removes
`McpLocalizationResult.Fallback` and
`McpLocalizationResult.fallback(String, Locale)` without compatibility
aliases. `McpLocalizationResult.localized(String)` now represents any text
successfully resolved by the application-owned provider, including a parent
locale or the localization library's terminal fallback. `UseDefaultText` and
the fieldless `Failure` retain their distinct intentional-miss and unexpected-
failure semantics.

The removed resolved-locale value did not affect response rendering,
`Content-Language`, cache policy, metrics, diagnostics, subscriptions, or
invalidation. A successful `Fallback` supplied its text through the same path
as `Localized`; requiring a separate result therefore imposed resolution-
provenance knowledge that not every localization library exposes, without a
corresponding Soklet behavior.

This no-alias simplification removes one Phase 6 owner, one constructor, and
six methods. At that checkpoint, `phase-6.includes` contained exactly 64
owners with SHA-256
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`.
`phase-6.signatures.jsonl` contained exactly 420 records - 64 classes, 31
constructors, 40 fields, and 285 methods - with SHA-256
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`.
The exact reflection digest is
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
Phase 4 remains at 1,053 records across 133 owners, Phase 5 remains at 195
records across 39 owners, and the provisional inventory remains empty. The
union at that checkpoint was therefore 236 owners with 1,053/195/420 phase
records.

The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`;
the removed result family was an unreleased compatible addition relative to
3.5.1. A fresh full core verify passes 1,667 tests with zero failures, zero
errors, and four skips over 464 main and 193 test sources.

## 2026-08-17 greenfield localization-context builder amendment

The subsequent same-day review converts `McpLocalizationContext` from an
application-implemented interface to a Soklet-owned final immutable class.
Applications construct each request-scoped context with
`McpLocalizationContext.withLocale(locale)`, may attach an immutable
`McpLocalizationRevision`, and supply the required
`Function<McpLocalizableText, McpLocalizationResult>` through `localizer(...)`
before `build()`. The callback can close over the application's immutable
translation snapshot; Soklet owns the context carrier and its validation.

No legacy interface, application-defined context subtype, or Soklet-specific
callback alias is retained. The greenfield surface therefore makes the normal
application integration explicit without asking every application to create a
throwaway implementation, while remaining localization-library neutral.

The new nested `McpLocalizationContext.Builder` adds one Phase 6 owner. The
revised context and builder add six net snapshot records, so
`phase-6.includes` contained exactly 65 owners at that checkpoint with SHA-256
`474e1c3079501b286a9eb1b38dee06a532d263aef50b633b46d465813024dacc`.
`phase-6.signatures.jsonl` contained exactly 426 records - 65 classes, 31
constructors, 40 fields, and 290 methods - with SHA-256
`7f264422a9e0a81718ae46bc5333a26d56d4c772ded5620d91335b4253734878`.
The exact reflection/nullability digest is
`f6e0abeb94bf4e98822a57214c1fe459451fa207b377d99f10c3a562be2b9afa`.
At that amendment checkpoint Phase 4 remained at 1,053 records across 133
owners, Phase 5 remained at 195 records across 39 owners, and the provisional
inventory remained empty. The union was therefore 237 owners with
1,053/195/426 phase records.

The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`;
both localization-context shapes are unreleased additions relative to 3.5.1.
A fresh full Corretto 26 verify passes 1,666 tests with zero failures, zero
errors, and four skips over 464 main and 193 test sources. JDK 21 static
analysis reports `BUILD SUCCESS`, and SpotBugs reports zero errors and zero
warnings.

## 2026-08-17 final greenfield API polish amendment

The final same-day review changes only Phase 4 and Phase 5 declarations. It
makes the Phase 4-owned endpoint registry a final immutable Soklet value,
clarifies converted-argument, named-limiter, pass-through-interceptor, and
tool-output builder names, and removes the redundant Phase 5 input-request
factory. No compatibility aliases are retained. At that checkpoint, Phase 6
stayed at exactly 65 owners and 426 records with the signature, include, and
reflection hashes recorded in the preceding dated amendment. The owner
partition was 133/39/65/0, the signature partition was 1,053/194/426, and the
compatibility ledger remained 564 records
with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## 2026-08-18 greenfield public-record elimination amendment

The subsequent review eliminates all 45 public MCP record shapes - nine
top-level and 36 nested - from the unreleased greenfield API. Each former
record is now a final Soklet-owned class with private constructors, named
factories or builders wherever public construction is supported, conventional
getters, explicit value equality and hash codes, and a redacted or otherwise
data-minimizing diagnostic rendering. Fieldless variants use shared instances.
No canonical-constructor, component-accessor, record-shape, or deprecated
compatibility alias is retained.

Phase 6 owns 31 of those conversions: the top-level
`McpTraceCorrelationConfigurationFingerprint`; the three nested
`McpLocalizationResult` variants; all 23 nested `McpMetricsEvent` variants; and
the four nested `McpMetricsSnapshot` aggregate-key types. The sealed result and
event interfaces own their named factories, their fieldless variants are
shared, and the aggregate keys use `fromDimensions(...)`. Framework-created
fingerprints keep private construction and expose their immutable value through
`getValue()`.

The Phase 6 owner count remains 65. Its snapshot now contains exactly 422
records - 65 classes, zero constructors, 40 fields, and 317 methods - with
SHA-256
`f7355c91a0131c4bb9ef7f9b49f0d54e9bbafa0042a16c5932722e5765cee774`.
The reflection/nullability digest is
`2f857d18ae3dfb641fadf00858fec19d594c0ac470c6ed6be70423596b340611`.
At that amendment checkpoint, the owner and signature partitions were
133/39/65/0 and 1,047/191/422. The same amendment makes the already-final
Phase 4-owned `McpCachePolicy` constructor private. The sole public constructor across all
three frozen phases is the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor; non-throwable values are
factory- or builder-owned.

The complete released-3.5.1 compatibility ledger contains 565 records with
SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`.
No converted Phase 6 value adds a net incompatibility. The sole net-new entry
is the Phase 4-owned `McpPromptMessage` superclass change from
`java.lang.Record` to `java.lang.Object`; its former `role()` and `content()`
record-component changes are now removals.

The exact public-record-amendment tree passed a clean Corretto 26 verify with
1,671 tests, zero failures, zero errors, and four intentional skips; JDK 21 static
analysis succeeds, SpotBugs reports zero findings, the aggregate freeze gate
verifies all 565 compatibility records and 1,047/191/422 signatures, and the
maintained 182-source Java 17 API sketch passes compilation and Javadoc
doclint.

## 2026-08-18 greenfield typed-request-state amendment

The subsequent greenfield review removes the public sealed `McpRequestState`
carrier family, including `McpApplicationRequestState` and
`McpFrameworkRequestState`, without aliases. The Phase 4 request-context and
Phase 5 input-required-result hosts now expose application state directly as
`Optional<String>` and framework state directly as
`Optional<McpJsonValue>`. Their builders retain the corresponding typed
setters with mutual-exclusion and last-call-wins behavior.

This amendment changes no Phase 6 owner or descriptor. The Phase 6 snapshot
therefore remains exactly 422 records across 65 owners, with signature
SHA-256
`f7355c91a0131c4bb9ef7f9b49f0d54e9bbafa0042a16c5932722e5765cee774`,
reflection/nullability digest
`2f857d18ae3dfb641fadf00858fec19d594c0ac470c6ed6be70423596b340611`,
and include SHA-256
`474e1c3079501b286a9eb1b38dee06a532d263aef50b633b46d465813024dacc`.
The complete owner and signature partitions become 133/36/65/0 (234 total)
and 1,048/179/422. The compatibility ledger remains exactly 565 records with
SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`
because every affected descriptor is an unreleased greenfield addition
relative to 3.5.1. The focused reflection/inventory contract passes 24/24 and
the aggregate freeze gate verifies the exact updated partitions. Fresh
Corretto 26 clean verify passes 1,673 tests with zero failures, zero errors,
and four intentional skips over 462 main and 193 test sources, and builds the
main, sources, and Javadoc artifacts; the maintained 179-source Java 17 API
sketch passes compilation, Javadoc doclint, and its localization smoke test.

## Why no conformance-profile activation was required

The Phase 5 freeze had to atomically activate 16 reviewed conformance profiles
and advance the harness to `--phase 5`. Phase 6 requires no equivalent step:
localization introduces no conformance-visible wire surface. It advertises no
capability, reserves and interprets no `_meta` key, defines no new method, and
emits no locale or revision metadata, so the official conformance harness
remains correct at its current phase. A search of `conformance/` finds no
localization profile, fixture, or expectation, which is the same negative
surface required by the reviewed contract above.

The localization-visible behavior changes that do exist - `Content-Language`,
the `Vary` merge, the private/zero cache clamp, and version-2 request state -
are all conditional on a configured localizer. With none configured, wire
output is byte-identical, which the golden-wire suites verify on every run.

## What this freeze does not decide

Freezing the signature and nullability layout does not establish completion of
runtime or release validation. Sustained soak and multi-node fleet
orchestration evidence, public Javadoc publication, and release provenance
remain open release gates outside the scope of this API-freeze decision. The
ToyStore migration is green locally at 14/14, including six MCP tests. Its
per-request credential proof accepts a valid request, then returns 401 for
malformed, missing, expired, and wrong-audience credentials and 403 for an
insufficient-scope credential, proving that prior identity and authorization
are never inherited. Its reviewed committed pin and checksum-matched
immutable-candidate/JDK-25 proof remain an explicit required 3.6.0 downstream
release gate. The API freeze does not satisfy or defer that gate.

## 2026-08-27 Soklet 4.0 lifecycle cutover

The 4.0 lifecycle review removes the MCP-specific shutdown projection in
favor of the general Soklet lifecycle/result vocabulary:

- the `McpShutdownOutcome` owner is removed without an alias;
- `McpServerStatus` replaces the direct-listener vocabulary with
  `NOT_STARTED`, `STARTING`, `RUNNING`, `SHUTTING_DOWN`, `TERMINATED`,
  `RESIDUAL_ACTIVITY`, and `TERMINATION_UNKNOWN`;
- `McpServerDiagnostics` retains its exact descriptors while its status and
  bound-address Javadocs and reflection expectations are revised. In
  particular, an off-network running simulator has no bound address;
- `McpMetricsEvent.serverStopped(...)` and
  `McpMetricsEvent.ServerStopped` now carry
  `ShutdownComponentDisposition`;
- the shutdown maps on `McpMetricsSnapshot` and its builder use that same
  general disposition type; and
- the shared `Simulator` host retains its exact descriptors. The new
  lifecycle-aware `SokletSimulator` API is owned by the non-MCP allowlist.

Removing `McpShutdownOutcome` leaves 64 Phase 6 owners. The regenerated
snapshot contains 421 records: 64 classes, zero constructors, 42 fields, and
315 methods. Its SHA-256 is
`5e8a4aac651374205e126ca8128ec5ca644b1c7f84ad6426d4462cd9712ff12b`;
the reflection/nullability digest is
`15f883e66b3194974887899a090e53d33aa27a08db793f4cfd7ff78212b67aaf`;
and the include-inventory SHA-256 is
`c14695a4bfea85e88fea713211320b4192db4ca421786ed716dd543d79ded4c5`.
Phase 4/5/6/provisional ownership is 132/36/64/0, for an exact 232-owner
MCP union. The separate 39-entry non-MCP allowlist owns the general lifecycle,
result, runner, simulator, and transport-SPI types; the complete current-side
inventory therefore contains 271 owners.

The regenerated released-3.5.1 compatibility ledger contains 617 records
with SHA-256
`302f68448fe14b1cc5ad179c076c5b84b16e81b0b21dca55e0cc5edcbaadea41`.

## 2026-09-01 shutdown-component naming amendment

The unreleased general terminal-evidence vocabulary now uses
`ShutdownComponentDisposition` in the Phase 6 metrics descriptors. This is the
same one-for-one naming amendment recorded in the Phase 4 rationale; it adds no
owner, method, or compatibility alias. The framework-owned synthetic type is
`ShutdownComponentType.FRAMEWORK` rather than a generic `OTHER` value.

The Phase 6 snapshot remains 421 records across 64 owners. Its SHA-256 is
`69b008b685dead8e1ae66691f0e9955688b9e43740281ea0f82497df22a4dda0`;
the reflection/nullability SHA-256 is
`d829563b135bae5a0e97559ecf5d1a8dd280c4b7792a74a2f10fcf8d8017d18b`;
and the include-inventory SHA-256 is
`640eda42f3dd1cf1c5d8bf50e461281bc3083992de5dc83bf77a0478617606bc`.
The released-3.5.1 compatibility ledger remains 618 records with SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.
Phase 5 remains byte-identical at 179 records.
