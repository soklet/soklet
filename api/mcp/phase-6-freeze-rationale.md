# MCP Phase 6 API-freeze rationale

Date: 2026-08-14

Telemetry amendment reviewed: 2026-08-15

Structured trace-log amendment reviewed: 2026-08-15

Phase 4 admission-controller naming amendment reviewed: 2026-08-16

Greenfield cohesion naming amendment reviewed: 2026-08-17

Greenfield localization-result simplification amendment reviewed: 2026-08-17

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

The reviewed current incompatibility set contains exactly 564 canonical
symbols and has SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.
The matching full japicmp report establishes an exact owner universe of:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- 64 Phase 6 owners;
- zero provisional owners; and
- 236 owners in total.

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
and leaves Phase 4, Phase 5, and the incompatibility ledger unchanged.

## Frozen Phase 6 snapshot

`phase-6.signatures.jsonl` contains exactly 420 canonical records:

- 64 classes;
- 31 constructors;
- 40 fields; and
- 285 methods.

The reviewed file's SHA-256 is
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`.
The independent reflection contract freezes the Phase 6 JSpecify type-use
layout with SHA-256
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
The 64-entry `phase-6.includes` inventory has SHA-256
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 420 records and was byte-for-
byte identical to the reviewed candidate. The aggregate freeze gate now
compares the Phase 4, Phase 5, and Phase 6 snapshots bidirectionally on every
run, and `frozen-phases` lists the contiguous sorted prefix `4`, `5`, `6`.

## Reviewed contract

The snapshot freezes the 17 remaining localization-owned API types described
below. After the 2026-08-15 telemetry amendment, it also freezes all 32 formerly provisional
telemetry owners alongside the previously assigned Phase 6 diagnostics,
shutdown, subscription-configuration, and simulator-adjacent owners. The
cross-cutting review fixed the following public contracts:

- Localization is library-neutral. `McpLocalizer` carries a fallback locale,
  an application `McpLocalizationContextProvider`, a whole-response failure
  policy, and a per-response callback bound; Soklet depends on no translation
  library, and the published jar retains zero runtime dependencies.
- `McpLocalizationContext` is an immutable, node-local, request-scoped value:
  one selected locale and one translation snapshot per admitted localizable
  operation, with no session identity and no cross-request reuse.
- `McpLocalizationResult` is a sealed three-variant family. `Localized`
  represents text successfully resolved by the provider, including its own
  parent-locale or terminal-fallback behavior; `UseDefaultText` is an
  intentional per-field outcome rather than a failure, and `Failure` is
  fieldless so it can carry no application data.
- Every localization value type redacts its `toString()`. Revision values,
  coordinate identities, default text, locales, and preferences never appear in
  renderings, framework logs, exception text, or metric labels.
- `McpTextCoordinate` exposes a stable structured identity plus one versioned,
  domain-separated external key. An adapter selects exactly one key strategy
  per catalog and never falls back between strategies.
- `McpLocalizationControl` is a local-server control plane: `isEnabled()` plus
  `catalogsChanged()`. It distributes nothing, carries no locale, tenant,
  revision, or key, and throws consistently when localization is disabled.
- The three reviewed Phase 4 host amendments remain exact: default
  `McpHandlerContinuation.getFeatures()`, abstract
  `McpServer.getLocalizationControl()`, and concrete
  `McpServer.Builder.localizer(McpLocalizer)`. No fourth localization-host
  descriptor was added, and the Phase 5 snapshot is untouched.
- No `com.soklet/localization` MCP extension exists. Soklet advertises no
  localization capability, reserves and interprets no request or result
  `_meta` key, emits no locale or revision metadata, and claims no positive
  locale-aware MCP caching.
- `McpMetricsEvent` remains a sealed 23-record hierarchy with no generic value
  or label bag. Framework-produced dimensions are registered endpoint paths,
  recognized methods or the fixed `<unrecognized>` sentinel, fixed outcomes,
  fixed termination reasons, fixed transport reasons, and defined protocol
  error codes. Direct application-created values retain their documented
  shape-only contract and application-owned confidentiality/cardinality.
- `McpMetricsSnapshot` retains 22 fixed default families, immutable defensive
  copies, nonnegative scalar/count validation, and four typed aggregate keys.
  Sparse family maps do not imply cross-map atomicity or conservation, and
  reset cannot mutate a retained snapshot.
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
six methods. `phase-6.includes` now contains exactly 64 owners with SHA-256
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`.
`phase-6.signatures.jsonl` now contains exactly 420 records - 64 classes, 31
constructors, 40 fields, and 285 methods - with SHA-256
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`.
The exact reflection digest is
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
Phase 4 remains at 1,053 records across 133 owners, Phase 5 remains at 195
records across 39 owners, and the provisional inventory remains empty. The
current union is therefore 236 owners with 1,053/195/420 phase records.

The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`;
the removed result family was an unreleased compatible addition relative to
3.5.1. A fresh full core verify passes 1,667 tests with zero failures, zero
errors, and four skips over 464 main and 193 test sources.

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
ToyStore migration is green locally at 13/13, including five MCP tests, but its
reviewed committed pin and checksum-matched immutable-candidate/JDK-25 proof
remain an explicit required 3.6.0 downstream release gate. The API freeze does
not satisfy or defer that gate.
