# MCP API compatibility and inventories

This directory contains the reviewed, repository-owned evidence for Soklet's
MCP public/protected API. The [Phase 4 freeze rationale](phase-4-freeze-rationale.md)
records the 2026-08-06 decision and the subsequently reviewed wrapper
correction, 2026-08-12 localization host amendment, and 2026-08-15 structured
trace-log and rate-limit decision factory naming amendments, followed by the
2026-08-16 admission-controller naming amendment and the 2026-08-17 greenfield
cohesion naming amendment, followed by the 2026-08-17 greenfield
localization-result simplification amendment and the 2026-08-17 greenfield
localization-context builder amendment, followed by the 2026-08-17 final
greenfield API polish amendment and the 2026-08-18 greenfield public-record
elimination amendment, followed by the 2026-08-18 greenfield typed-request-
state amendment, the 2026-08-27 lifecycle cutover, the 2026-08-28 pre-G3 API
correction, and the 2026-09-01 shutdown-component, direct-run, and cleanup-value
amendments, followed by the 2026-09-03 application-wide instance-provider,
MCP value-contract, invocation/input-declaration, and focused naming/surface
amendments. The
[Phase 5 freeze rationale](phase-5-freeze-rationale.md) and
[Phase 6 freeze rationale](phase-6-freeze-rationale.md) record their exact
compatibility snapshots and the limits of each freeze decision.

`current-incompatibilities.jsonl` is the canonical set of incompatibilities
between the released `com.soklet:soklet:3.5.1` artifact and the current
4.0.0 source tree. It currently contains 621 records and has SHA-256
`38356e712db3eb747e9b525a8f2645a95ea59c50fa8de25dcfb4c21e79dc3e2e`.
The API-diff gate regenerates the set and compares it in both directions, so an unexpected addition, removal, or changed record fails.

The aggregate API-freeze wrapper also runs the MCP metadata-builder inventory and the independent protocol-profile evidence verifier/self-test. The latter binds the sole package-private production `2026-07-28` profile authority to its specification, schema, official-conformance, scenario, golden, and interoperability pins.
It changes no public descriptor or freeze owner: a test-only registry seam is package-private and unreachable from public configuration or production defaults.

The public protection API is reconciled with the exact runtime contract in the
[request-state security profile](../../release/MCP_REQUEST_STATE_SECURITY_PROFILE.md)
and [key-rotation runbook](../../release/MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md).
Those documents bind `McpProtectionConfig`, `McpProtectionControl`, and
`McpRequestStateProtectionContext` to the production crypto vectors,
rejection tests, and node-local publication/race tests without adding an API
owner or changing a frozen descriptor.

The [MCP privacy boundary](../../release/MCP_PRIVACY_BOUNDARY.md) separately
records which exact request, diagnostic, metric, exception, and simulator
surfaces are core-redacted or deliberately application- and operator-owned.

`phase-0-incompatibilities.jsonl` is the immutable 566-record historical removal surface from deleting the legacy MCP implementation. It initially
matched the then-current set, but it intentionally does not evolve as the
greenfield implementation reuses legacy names or adds new API.
`phase-0-shared-host-rationales.jsonl` explains every removed MCP-owned member
whose containing public type remains part of Soklet.

## 4.0 catalog compatibility boundary

After admission, static `tools/list` and `prompts/list` catalogs are immutable
and caller-neutral; Soklet does not authorization-filter their descriptors. A
registered tool remains listed when it declares a required client capability,
but the matching call can receive `-32021` before admission when that capability
is absent. These list responses retain private, zero-TTL protocol cache hints
and HTTP `Cache-Control: no-store`; this list/call distinction is not an
authorization boundary or a promise of ETag-based dynamic catalogs.

## Reviewed ownership

Every current exported MCP type and every shared public/protected host in
scope has exactly one owner:

| Inventory | Entries | Meaning |
| --- | ---: | --- |
| `phase-4.includes` | 133 | frozen Phase 4 types and shared hosts |
| `phase-5.includes` | 36 | frozen Phase 5 types |
| `phase-6.includes` | 64 | frozen Phase 6 types |
| `provisional.includes` | 0 | empty after the reviewed telemetry amendment |
| `non-mcp-public-api.allowlist` | 38 | reviewed lifecycle, runner, and transport-SPI owners |

The 233-entry MCP union plus the 38-entry non-MCP allowlist owns exactly 271 current types.
Ownership records when a type is intended to stabilize; they do not themselves freeze it.
The current Phase 4, Phase 5, and Phase 6 include inventories have respective
SHA-256 values
`f028ced0c56d597aea55d1a43a96a518a3445b4bd66e1a08d997f5bb8a83cb64`,
`0ac8338321ad8d28e40e63e8b49963fd2be0a18e6d4b7e130b75071ebf756bf6`,
and
`29428cf561632aec4400785ae7a1f73d980c85e1d368e9d3a1cb1e520aa9ae01`.
`McpPublicApiInventoryTests` is a fast, independent source/class-tree guard
for exported MCP types, reviewed shared hosts, sorting, overlap, and existence.
It complements the baseline comparison; it is not the authoritative
compatibility inventory.

## Current local evidence

The 2026-08-16 local refresh was green at 562 incompatibilities, 237 exact
owners, and 1,053/195/428 Phase 4/5/6 signature records. Core clean verify
passes 1,669/0/0/4 and builds Javadocs; JDK 21 static analysis reports `BUILD
SUCCESS`, and SpotBugs reports zero findings. The artifact-backed local
simulator and pinned live official CLI each pass 39/39 in development mode.
These results revalidate the frozen API and local development artifact; they
are not immutable release-candidate provenance, public Javadoc publication,
or sustained operational evidence.

The 2026-08-17 greenfield cohesion naming amendment subsequently regenerated
the count-neutral 1,053/195/428 phase snapshots and the same 133/39/65/0 owner
partition. At that amendment checkpoint, the compatibility ledger contained
564 records. This API artifact refresh does not by itself repeat or replace
the broader 2026-08-16 runtime, static-analysis, simulator, or official-CLI
evidence.

The later 2026-08-17 greenfield localization-result simplification removes
the unused `McpLocalizationResult.Fallback` owner and `fallback(...)` factory
without an alias. At that simplification checkpoint, the partition was
133/39/64/0 (236 owners), and the Phase 4/5/6 snapshots contained
1,053/195/420 records. The compatibility
ledger remains 564 records with the same SHA-256 because the removed surface
was an unreleased compatible addition relative to 3.5.1. A fresh full core
verify passes 1,667 tests with zero failures, zero errors, and four skips over
464 main and 193 test sources.

The subsequent 2026-08-17 greenfield localization-context builder amendment
converts `McpLocalizationContext` from an application-implemented interface to
a Soklet-owned final immutable class and adds its nested `Builder`. Applications
now supply only the per-context `Function<McpLocalizableText,
McpLocalizationResult>` callback; no custom context subtype or compatibility
alias remains. At that amendment checkpoint the partition was 133/39/65/0
(237 owners), and the Phase 4/5/6 snapshots contained 1,053/195/426 records.
The compatibility
ledger remains the same 564 records because both context shapes are unreleased
additions relative to 3.5.1. A fresh full Corretto 26 verify passes 1,666 tests
with zero failures, zero errors, and four skips over 464 main and 193 test
sources; JDK 21 static analysis reports `BUILD SUCCESS`, and SpotBugs reports
zero errors and zero warnings.

The final 2026-08-17 greenfield API polish amendment makes
`McpEndpointRegistry` a final immutable Soklet-owned class, clarifies converted
tool arguments, named rate-limiter setters, the pass-through interceptor, and
the tool-output error setter, and removes the redundant
`McpInputRequest.fromDeclaration(...)` factory. No pre-amendment aliases are
retained. At that checkpoint, the owner partition was 133/39/65/0 (237
owners), and the Phase 4/5/6 snapshots contained 1,053/194/426 records with
respective SHA-256 values
`3fd2ead5b1e1dfa98686b722dc6ed274a073a9bccbe55d0ac2a215f5d17dfa9f`,
`19e0d0184d6c347e63689acfcef06222d6131d5d0a469740b627342b7ee24785`,
and
`7f264422a9e0a81718ae46bc5333a26d56d4c772ded5620d91335b4253734878`.
Their reflection/nullability digests are
`fc06dda2a4b0d2300136b9173e05db0e4a573c1a9755855cf1c155cecf331be9`,
`d10c45dddd332f7308f6d731371b73412314a28560ba7f747a0e68071bfc59af`,
and
`f6e0abeb94bf4e98822a57214c1fe459451fa207b377d99f10c3a562be2b9afa`.
The 564-record compatibility ledger and its SHA-256 remain unchanged.
The final Corretto 26 clean verify passes 1,666 tests with zero failures, zero
errors, and four skips over 464 main and 193 test sources and builds Javadocs;
the JDK 21 static-analysis build succeeds, SpotBugs reports zero findings, the
aggregate API gate is green, and the reflection contract passes 19/19.

The subsequent 2026-08-18 greenfield public-record elimination amendment
replaces all 45 public MCP record shapes - nine top-level and 36 nested - with
Soklet-owned final classes. Construction now uses private constructors behind
named factories or builders, access uses conventional getters, and each
former record has deliberate value semantics and a data-minimizing diagnostic
rendering. No canonical-constructor or component-accessor alias is retained.
The same amendment makes the already-final `McpCachePolicy` factory-owned by
privatizing its remaining public value constructor. Across all three frozen
phases, the sole public constructor is now the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor; non-throwable values are
factory- or builder-owned. At that amendment checkpoint, the exact owner
partition remained 133/39/65/0 (237 owners), and the Phase 4/5/6 snapshots
contained 1,047/191/422 records with
respective SHA-256 values
`dc733de19433200065526bd02f985b56ca69f658aefd116e80446b5c885f035b`,
`ea6d46dc055a57b2d31820cb937d89fe42bac5665c18c5fdb83eea75e79c82f5`,
and
`f7355c91a0131c4bb9ef7f9b49f0d54e9bbafa0042a16c5932722e5765cee774`.
Their reflection/nullability digests are
`581038cefbc8e65845e38001632ed0678a83efe55446e4f25f233e874eef3f39`,
`9c8c02a4eca29166a6a92956fa58033ea94939e6d7deef9e23a7ecd6d5babd3e`,
and
`2f857d18ae3dfb641fadf00858fec19d594c0ac470c6ed6be70423596b340611`.
The released-3.5.1 comparison now contains 565 records with SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`.
Its sole net-new incompatibility is the `McpPromptMessage` superclass change
from `java.lang.Record` to `java.lang.Object`; its former canonical record
components are removals, not compatibility aliases.

The exact public-record-amendment tree passed a clean Corretto 26 verify with
1,671 tests, zero failures, zero errors, and four intentional skips over 464 main
and 193 test sources, and builds the main, source, and Javadoc artifacts. The
JDK 21 static-analysis build succeeds with the reviewed advisory warnings,
SpotBugs reports zero findings, the aggregate freeze gate verifies all 565
compatibility records and 1,047/191/422 signatures, and the maintained
182-source Java 17 API sketch passes compilation, Javadoc doclint, and its
localization smoke test.

The subsequent 2026-08-18 greenfield typed-request-state amendment removes
the public sealed `McpRequestState` carrier family, including
`McpApplicationRequestState` and `McpFrameworkRequestState`, without aliases.
Handlers and input-required results now expose application state directly as
`Optional<String>` through `getApplicationRequestState()` and framework state
directly as `Optional<McpJsonValue>` through
`getFrameworkRequestState()`. Their builders retain the correspondingly typed
setters; the two state forms remain mutually exclusive, and the last setter
called wins.

The Phase 4 request-context host replaces one default getter with two, adding
one method without changing its 133 owners. Phase 5 removes the three carrier
owners and their 13 records, while the input-required-result getter replacement
adds one method, for a net change from 191 to 179 records. The exact current
owner partition is 133/36/65/0 (234 total), and the signature partition is
1,048/179/422. Phase 4 has 133 classes, one constructor, 79 fields, and 835
methods, with signature SHA-256
`0efe130ce6da63230f2bbf5f4c50889209a53bd49995f7da1a42ff713c7f60d4`
and reflection/nullability digest
`1d33a5deb35adb467feccac10ffce635eae903437a096ed63a8c17a1b57d2309`.
Phase 5 has 36 classes, zero constructors, 15 fields, and 128 methods, with
signature SHA-256
`96f56fc34f81a9302d1387d437bee4caa36e465a07a40a8577eed4bd4313e5e4`,
reflection/nullability digest
`6569e3b106ae11e1d30da66c045d1a9bc23aa65016f36052df6b19fc320c06d9`,
and include SHA-256
`2009a66e210e89c43e157df0498b357a5e29fc8bc7144ca373ad07c57d1fce2a`.
The Phase 6 snapshot, include inventory, and reflection digest are unchanged.
The released-3.5.1 comparison likewise remains at 565 records with SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`
because every removed carrier and changed host descriptor is part of the
unreleased greenfield surface.

A clean Corretto 26 verify passes 1,673 tests with zero failures, zero errors,
and four intentional skips over 462 main and 193 test sources, and builds the
main, sources, and Javadoc artifacts. The focused reflection and inventory
contracts pass 24/24, the focused request-state/runtime set passes 50/50, the
aggregate freeze gate
verifies all 565 compatibility records and 1,048/179/422 signatures, and the
maintained 179-source Java 17 API sketch passes compilation, Javadoc doclint,
and its localization smoke test.

That 1,673 result remains the typed-request-state amendment checkpoint. The
1,676/0/0/4 result over 462 main and 194 test sources remains the rate-limit
identity/trusted-proxy checkpoint. The independent-request direction-boundary
result remains 1,678/0/0/4 over 462 main and 195 test sources, with its focused
protocol gate at 35/35. At the localization-fleet checkpoint, Corretto 26 clean
verify passed 1,681/0/0/4 over 462 main and 196 test sources and built the main,
sources, and Javadoc artifacts; the fixture passes 3/3 and
its related localization regression set passes 24/24. The preceding Corretto
17 clean-test run passed 1,659/0/0/72 before the rate-limit identity,
independent-request, and localization-fleet runtime test sources were added, so
it remains prior supported-JDK evidence rather than a current 196-source
result. The exact six-scenario smoke soak
passes 6/6 with its strict verifier. Resource subscriptions become
publisher-visible before the transport can expose their already-queued
acknowledgment, closing the acknowledgment-to-activation notification-loss
window; request-metrics tests also wait for the serialized finish event before
inspecting collector state.

Those 1,681-test results remain the localization-fleet and initial JDK 21 gate
checkpoints. Current post-fix Corretto 21 validation passes core `clean test`
at 1,682/0/0/4 over the unchanged 462 main and 196 test sources. The focused
terminal/subscription regression set passes 32/32, a clean smoke soak passes
6/6 with its strict verifier and verifier self-test, and the cross-feature
smoke method passes 10/10 repeated stress runs. Fast inline application streams
retain transport-callback ownership after reserving a terminal response, so
protocol-task return cannot preempt the terminal write or exact cleanup; no
timeout or expected-count bound was relaxed. The subscription activation
regression checks wire order directly instead of treating asynchronous metric
delivery as an activation barrier. The internal order remains acknowledgment
queued, subscription activated, and then response handed to the transport
callback.
Current supported-JDK revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes `mvn -B -ntp clean test` at 1,667/0/0/72 over the
same 462 main and 196 test sources. The two corrected methods pass 2/2 once
and 20/20 across ten combined repetitions. Both corrections are test-only
synchronization: live transport smoke waits for the complete idle snapshot,
and observation containment waits for actual typed failure-log publication
before exact inspection. The original exact counts and timeout assertions
remain; production behavior, public API, and frozen inventories are unchanged.

A subsequent containment revalidation on the pinned Amazon Corretto
21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact
`mvn -B -ntp clean test` at 1,682/0/0/4 over 462 main and 196 test sources.
The focused platform-plus-virtual-thread containment matrix passes 30/30, and
20/20 complete repetitions cover 600 dynamic cases; the affected JDK 17
platform-thread matrix also passes 15/15. This is test-only synchronization:
containment waits now include the exact expected cleanup count before returning.
Expected cleanup counts, timeout bounds, and assertions are unchanged;
production behavior, public API, and frozen inventories are unchanged. These
local snapshot checks are not immutable-candidate release evidence.

A later subscription observer-scope revalidation on the same pinned Amazon
Corretto 21.0.12.9.1 toolchain (`java 21.0.12.1`) passes the exact full
`mvn -B -ntp clean test` at 1,682/0/0/4 over the unchanged 462 main and 196
test sources. The affected method passes 1/1 focused and 20/20 repeated runs;
`McpSubscriptionPublicRuntimeTests` plus
`McpSubscriptionRuntimeBoundaryTests` pass 26/26. The test-only correction
sets the per-authorization-partition subscription cap to one and holds the recovery
subscription open while the original disconnect observer's exact-once count
is asserted, preventing that recovery request's legitimate finish from
entering the first request's observation phase. No production behavior,
public API, Phase 4/5/6 freeze inventory, timeout, or asserted count changed.
This is local snapshot evidence, not an immutable release-candidate PASS
receipt.

Current JDK 17 application-execution revalidation on local Amazon Corretto
17.0.20.1+10-LTS passes the exact `mvn -B -ntp clean test` at 1,667/0/0/72
over the unchanged 462 main and 196 test sources. The affected method passes
1/1 focused and 20/20 repeated runs, and the full
`McpApplicationExecutionTests` class passes 10/10. The same affected method
also passes 1/1 on the pinned Corretto 21.0.12.9.1 toolchain. The test-only
correction uses an exact post-observer stable fence requiring both
`retainedExchanges == 1` and `queuedCleanups == 1` before inspecting the
dequeued snapshot. Existing timeout bounds and expected counts are unchanged;
production behavior, public API, and the Phase 4/5/6 freeze inventories are
unchanged. This is local snapshot evidence, not immutable release-candidate
evidence.

The authoritative owner inventory comes from the full japicmp report
`target/japicmp/mcp-api-freeze.xml`. It includes:

- every current, non-internal published `Mcp`-named type;
- every current shared host whose public/protected API references an MCP type;
- every other current, non-internal public/protected API delta, which must
  appear in the non-MCP allowlist if it is unrelated to MCP.

The full report is required because a public type or member restored with the
same signature it had in 3.5.1 can be absent from a modified-only report.
`target/japicmp/mcp-api-diff.xml` remains the separate modified-only source for
the canonical incompatibility set. Removed-only containers with no current-side
API do not become current owners, and `com.soklet.internal.*` is excluded from
the ownership inventory.

## Current Phase 5 checkpoint

The local Phase 5 implementation now includes the public MRTR values and
declared outbound `input_required` runtime for tools, prompts, and resource
reads; method-specific embedded-parameter validation; inbound
`inputResponses`; and directly typed application and framework request state.
Framework protection includes authenticated state reopening, operation and
authorization binding, expiry/round checks, and originating-request-ID
evidence. Request-scoped progress and cooperative cancelation are also live on
application handler paths.

Configured endpoints can additionally host framework-owned `subscriptions/listen` POST/SSE streams for resource-list changes
and updates to requested resource URIs. Application-owned publishers emit coarse identity-free broadcasts: Soklet matches the accepted URI
filter, while the admitted authorization partition scopes registration, quota accounting, and stream isolation rather than event targeting or semantic URI authorization.
Applications authorize confidential or capability-bearing URIs from the immutable validated, deduplicated URI list on `McpAdmissionContext`.
Soklet owns filtering, coalescing, stream bounds, and wire serialization. The checked-in final-tag corpus contains 39 production-derived messages, including progress and subscription exchanges.

Deterministic MRTR termination coverage now includes blocked custom protector
open/seal paths, conditional-capability holds, and independent fresh-ID
branches across shutdown, deadline, and disconnect outcomes. Public listener
tests also prove same-key/same-authorization-partition cross-instance state
continuation and bounded residual-handler shutdown/restart recovery.

The Phase 5 public API is frozen. The bounded cross-feature soak/resource-delta
gate is green: complete Maven smoke runs pass on JDK 21 and JDK 26, the complete
JDK 21 nightly run passes, and the strict verifier requires exactly four
scenarios across three Surefire suites. Sustained/fleet/release-candidate
calibration remains later work. The packaged
fixture and standalone public-API contract cover every Phase 5 scenario row,
and a controlled observation-only run exercised all 39 applicable pinned
scenarios with 147 `SUCCESS`, two exact reviewed `server-stateless` `SKIPPED`,
one reviewed `server-sse-streams-functional` `INFO`, and no bad outcome.
Thirty-six automatic wire successes covered 103 messages, and the prior 23
profiles reproduced exactly. That acquisition was not a profile freeze,
Phase 5 verify pass, API freeze, or release-candidate result. The API snapshot
also does not establish conformance by itself. The later atomic closeout
activated all 39 profiles and passed the fresh exact 39-scenario verify; that
separate evidence is recorded below.

## Active freeze

`frozen-phases` contains the contiguous, sorted prefix of frozen phases. It
currently contains Phase 4, Phase 5, and Phase 6. `phase-4.signatures.jsonl` freezes
1,058 canonical records across all 133 selected owners: 133 classes, one
constructor, 79 fields, and 845 methods. Its SHA-256 is
`41c717baa9353bfe794601f9ee5da1ebf5e3317afb9a656343683287da88290c`.
`phase-5.signatures.jsonl` freezes 189 canonical records across all 36
selected owners: 36 classes, zero constructors, 19 fields, and 134 methods.
Its SHA-256 is
`0e3e2b7f9a644f28bed2215c652f2c25e2eaff9a171983ed058ee90fc0e617ed`.
`phase-6.signatures.jsonl` freezes 423 canonical records across all 64
selected owners: 64 classes, zero constructors, 41 fields, and 318 methods.
Its SHA-256 is
`991ebeeacc476ef06a127db5127da421b79900dbd3d3c405d2886776ffa671f7`.
Their current reflection/nullability digests are respectively
`7f5fe43e23b6da1cc3f18d431e9a4576aa57cad8ac83a7fae050a249e9e9d04f`,
`682eb068e722f49fca8329d39994bee747a98f1e93d9812d4186e341cf0356a7`,
and
`3df4ec35547cde4f6ad5a2816824bfcd65a5c8145aa50f07ab1857b6c17c7b60`.
The reviewed 2026-08-15 telemetry amendment moved all 32 former provisional
owners into Phase 6 without changing their descriptors;
`provisional.includes` is now empty.

The snapshot includes a deliberate 2026-08-07 post-freeze correction to Soklet's
unreleased `3.6.0` MCP API: 49 Phase 4 scalar signatures now use non-null
reference wrappers instead of primitives. Five of those corrections restore
the wrapper signatures already present in 3.5.1, so the reviewed baseline
incompatibility set decreased from 561 to 556 records. Regeneration found no
unrelated signature delta; at that correction the Phase 4 snapshot retained
the same 1,049 records and component counts.

A second reviewed amendment on 2026-08-12 adds exactly three descriptors to
frozen Phase 4 hosts: default `McpHandlerInvocation.getFeatures()`, abstract
`McpServer.getLocalizationControl()`, and concrete
`McpServer.Builder.localizer(McpLocalizer)`. The generated snapshot has no
other delta. The one abstract interface method is the sole additional current
source incompatibility; the default interface and concrete builder methods are
compatible additions. The Phase 5 snapshot and nullability digest are
unchanged. See the dated amendment in the
[Phase 4 freeze rationale](phase-4-freeze-rationale.md).

A third reviewed amendment on 2026-08-15 appends exactly one compatible enum
field, `LogEventType.MCP_TRACE_CORRELATION`, without shifting any existing
ordinal. Its public Javadoc freezes the bounded machine-readable trace-log
grammar and empty attachment contract. The generated Phase 4 snapshot changes
only by that one field, from 1,052 to 1,053 records and from 78 to 79 fields;
the 559-record incompatibility set is unchanged. The reflection layout includes
public enum fields, so the Phase 4 nullability digest advances with the exact
one-field addition.
See the dated amendment in the
[Phase 4 freeze rationale](phase-4-freeze-rationale.md).

A fourth reviewed amendment on 2026-08-15 renames the still-unreleased
`McpRateLimitDecision.fromAllowed()` and `fromDenied(Duration)` factories to
`allowed()` and `denied(Duration)`, without compatibility aliases or a
duplicative always-allowing limiter singleton. Counts and the released-3.5.1
comparison remain unchanged; the exact Phase 4 snapshot and reflection hashes
advance because both canonical forms include method identities. See the dated
amendment in the [Phase 4 freeze rationale](phase-4-freeze-rationale.md).

A fifth reviewed amendment on 2026-08-16 renames the complete greenfield
admission concept from `McpRequestAdmissionPolicy` to
`McpAdmissionController`, together with
`McpServer.getAdmissionController()` and
`McpServer.Builder.admissionController(McpAdmissionController)`. The functional
`admit(...)` method and `acceptAllInstance()` factory remain. No old-name alias
is retained because 3.6 replaces the MCP API wholesale. One Phase 4 owner and
five snapshot records are replaced in place, so the owner, signature, and
component counts remain unchanged; the identity-sensitive signature and
reflection hashes advance. The released-3.5.1 comparison advances from 559 to
562 records. See the dated amendment in the
[Phase 4 freeze rationale](phase-4-freeze-rationale.md).

A sixth reviewed amendment on 2026-08-17 applies one count-neutral naming pass
to the still-unreleased greenfield API. Phase 4 replaces the handler-resolver,
tool-call-context, handler-invocation, generic-schema, resource-handler,
list-resources annotation, and request-rejection families with endpoint-
registry, tool-arguments, handler-continuation, tool-schema, resource-read-
handler, resource-list annotation, and admission-rejection names. It also
renames the continuation operation to `proceed()`, uses direct
`accepted(...)`/`rejected(...)` admission factories, singularizes the resource-
list and resource-template-list cache member families, and carries endpoint-
registry and trace-control terminology through `McpServer`. Phase 5 replaces
`McpSubscriptionEventSubscription` with
`McpSubscriptionEventRegistration`. Phase 6 replaces
`McpTraceCorrelation` with `McpTraceCorrelationControl`, renames catalog
extraction for `McpEndpointRegistry`, and gives `McpLocalizationResult` direct
`localized(...)`, `fallback(...)`, `useDefaultText()`, and `failure()`
factories. No pre-amendment alias is retained. All three phase owner,
signature, and component counts remain unchanged; the generated comparison
against released 3.5.1 advances from 562 to 564 records. The dated amendments
in all three phase rationales record the exact before and after hashes.

A seventh reviewed amendment on 2026-08-17 simplifies the still-unreleased
localization result family. `McpLocalizationResult.Fallback` and
`fallback(String, Locale)` are removed without aliases; `localized(String)`
now represents any text successfully resolved by the application provider,
including parent-locale or localization-library fallback resolution. Soklet
never used the per-field resolved locale to alter rendering, headers, caching,
metrics, diagnostics, or control flow beyond validating the redundant result
variant. Phase 4 and Phase 5 remain unchanged. Phase 6 moves from 65 to 64
owners and from 428 to 420 records: 64 classes, 31 constructors, 40 fields,
and 285 methods. The complete incompatibility ledger remains exactly 564
records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

An eighth reviewed amendment on 2026-08-17 converts the still-unreleased
`McpLocalizationContext` interface into a Soklet-owned final immutable class
constructed through `withLocale(...)`, `localizer(...)`, and `build()`. The
optional revision remains a context value, while applications
provide only the JDK `Function` callback that performs localization against
their captured immutable snapshot. No application context subtype, custom
callback interface, or compatibility alias is retained. The nested `Builder`
adds one Phase 6 owner and the revised class/builder surface adds six records,
producing 65 owners and 426 records: 65 classes, 31 constructors, 40 fields,
and 290 methods. The Phase 6 signature SHA-256 is
`7f264422a9e0a81718ae46bc5333a26d56d4c772ded5620d91335b4253734878`,
and its exact reflection/nullability digest is
`f6e0abeb94bf4e98822a57214c1fe459451fa207b377d99f10c3a562be2b9afa`.
The incompatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

A ninth reviewed amendment on 2026-08-17 performs the final no-alias polish of
the still-unreleased greenfield API. `McpEndpointRegistry` becomes a final
immutable Soklet-owned class; `McpToolArguments.getConvertedArguments()`
names the converted value explicitly; the String-valued Java builder methods
become `toolRateLimiterName(...)` and `rateLimiterName(...)` while the direct
limiter overloads and annotation elements remain unchanged;
`McpHandlerInterceptor.passThroughInstance()` and
`McpToolOutput.Builder.error(Boolean)` use value-oriented names; and the
redundant `McpInputRequest.fromDeclaration(...)` factory is removed. Phase 4
remains 1,053 records, Phase 5 becomes 194, and Phase 6 remains 426. The exact
snapshot and reflection hashes are recorded in the three phase rationales.
The owner partition and 564-record released-3.5.1 comparison do not change.

A tenth reviewed amendment on 2026-08-18 eliminates every public MCP record
shape from the still-unreleased greenfield API. The nine top-level records and
36 nested records become final classes with private constructors, named
factories or builders where applications construct values, conventional
getters, explicit value semantics, and diagnostic renderings that redact
application-controlled data. Data-free variants are shared singletons. No
record constructor, component accessor, or deprecated compatibility alias is
retained. The phase distribution is eight former records in Phase 4, six in
Phase 5, and 31 in Phase 6. The owner partition remains 133/39/65/0. The same
amendment makes the already-final `McpCachePolicy` constructor private and
keeps its existing named factories as the public construction boundary.
At that amendment checkpoint, the signature partition became
1,047/191/422. The only public
constructor across the frozen surface is the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor; non-throwable values are
factory- or builder-owned. The compatibility ledger contains 565 records with
SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`;
the sole net-new released-3.5.1 incompatibility is the
`McpPromptMessage` superclass change from `java.lang.Record` to
`java.lang.Object`, while its former record components are recorded as
removals.

An eleventh reviewed amendment on 2026-08-18 removes the public sealed
`McpRequestState` carrier family without aliases. Application state is exposed
directly as `Optional<String>` and framework state as
`Optional<McpJsonValue>` on `McpRequestContext` and
`McpInputRequiredResult`; their builders retain the typed setters with the
same mutual-exclusion and last-call-wins rule. Phase 4 replaces one getter with
two, while Phase 5 removes the three carrier owners and replaces its one host
getter with two. The current owner partition is therefore 133/36/65/0 and the
signature partition is 1,048/179/422. Phase 6 is descriptor-identical to the
prior amendment, and the released-3.5.1 ledger remains 565 records because the
entire affected surface is greenfield.

The snapshots protect the complete public/protected signatures of every owner
in all three frozen phases, including shared hosts. A descriptor on a frozen
host is frozen even when it names a type owned by a later phase. Targeted
reflection and source-contract tests cover important
details that japicmp does not reliably model, including sealed hierarchies,
public primitive constant values, MCP enum order, value-class construction and
parameter names, annotation defaults, exact JSpecify type-use nullability, and
thread-safety markers.

## Current bounded Phase 6 checkpoint

Twenty-one bounded Phase 6 verticals are implemented. The nineteenth is the
downstream-only `soklet-otel` metric migration; the twentieth adds modern
admitted-request spans; the twenty-first adds bounded off-network MCP
simulation. V19 and V20 leave the core owner inventory unchanged; V21 assigns
the shared `Simulator`, seven top-level simulation types, and
`McpSimulationOptions.Builder` to Phase 6.

The [Phase 6 freeze rationale](phase-6-freeze-rationale.md) records the
localization API review and freeze. Twelve top-level localization types and
six nested owners now provide immutable configuration, a framework-owned
request-context value and application callback, closed
results, revisions, stable text coordinates, catalog extraction, and local
control-plane shapes. Construction-time extraction operates on the final
`McpEndpointRegistry`, produces deterministic opaque external keys and
schema-aware response-local slot plans, enforces bounded callback counts, and
preserves the application-owned custom resource-list boundary. The built-in
handler interceptor receives the exact downstream invocation-feature carrier;
its continuation retains the thread, one-shot, and call-lifetime rules. L1 does
not invoke a localization provider or alter MCP wire output; request-time
rendering begins in L2. The original 18-owner surface first grew
`phase-6.includes` to 33; the later telemetry amendment grew it to 65, and the
2026-08-17 result simplification removed one redundant nested owner, and the
later context-builder amendment added the framework-owned builder. The current
inventory is 64 Phase 6 owners in an exact 234-owner reviewed MCP union.
Phase 6 is frozen: see the
[Phase 6 freeze rationale](phase-6-freeze-rationale.md).
`McpServerDiagnostics` remains
the completed protection and trace diagnostics projection.
`McpServerDiagnostics` now has exactly 12 zero-argument methods: lifecycle
`getStatus()` and `getBoundAddress()`, plus all ten implemented diagnostic
getters. Six use boxed `@NonNull Integer` values:
`getRequestHandlerConcurrency()`, `getRequestHandlerQueueCapacity()`,
`getActiveHandlerExecutions()`, `getRequestHandlerQueueDepth()`,
`getActiveRequestStreams()`, and `getActiveSubscriptions()`. The other four are
`@NonNull McpProtectionMode getProtectionMode()`, boxed
`@NonNull Boolean isApplicationRequestStateProtectorConfigured()`,
`getProtectionKeyringFingerprint()`, and
`getTraceCorrelationFingerprint()`; both fingerprint accessors
return non-null `Optional` values with non-null payloads.

Lifecycle, bound address, configured counts, current handler/queue counts, and
the stream/subscription pair form one runtime-owned atomic tuple. Protection
mode, custom-protector presence, production-ring fingerprint, and trace-
configuration fingerprint form a separate security-controls atomic tuple. One
immutable diagnostics result carries both, without claiming one shared global
linearization point. Configured values remain stable before start and across
stop/restart; handler values are bounded server-wide dispatcher counts; and
`0 <= activeSubscriptions <= activeRequestStreams`. A subscription enters both
stream counts once its acknowledgment stream opens, without claiming client
receipt. Retained snapshots never change.

Ordinary, subscription-only, and combined open states report `1/0`, `1/1`,
and `2/1`. Disconnect cleanup moves `2/1` through `1/0` to `0/0`. Completed
clean and residual-handler stops report stream pair `0/0`, even while a
residual handler remains active until actual exit. During internal `FAILED`
cleanup, public residual status may transiently retain `1/1`; completed cleanup
reports `STOPPED` with `0/0`.

Protection mode and the custom-protector flag are fixed at construction and
stable across listener lifecycle; the flag is true exactly for
`CUSTOM_PROTECTOR`. It identifies selection of the custom application-owned
`McpRequestStateProtector`, not an operation's `APPLICATION_PROTECTED` state
mode. The production-ring fingerprint is present exactly in
`PRODUCTION_KEYRING` mode. The independent trace fingerprint is present
exactly when trace correlation was enabled. Successful live rotations change
only fresh snapshots and persist across listener stop/restart.

Fingerprints are deterministic operational deployment-comparison metadata,
not authentication or token-derivation inputs. Diagnostics expose no raw key
material, key IDs, per-key tags, provider identity, cursors/epochs, or trace
tokens. Equality remains observable and rotations can create high-cardinality
values, so strong operator key entropy, bounded retention, and exclusion from
metric labels and per-request logs remain necessary. This diagnostics vertical
adds no metric family, event type, wire field, label, or other observation
dimension.

The sixth vertical established one context-aware deferred FIFO for the first 16
semantic event variants produced by the runtime: the five handler transitions,
`ServerStopped`, the nine admitted request, stream, subscription, cancelation,
progress, and keep-alive variants, and exact-once `ServerStarted`. The seventh
extended that same FIFO to the 20 variants produced at that checkpoint with
`RequestAccepted`, `RequestRejected`, `ProtocolError`, and
`UnknownMirroredHeader`.

`RequestAccepted` is retained only after successful bounded processor
submission. Executor rejection identity-discards its provisional accepted
entry, then records only `RequestRejected` before the empty 503 response.
Malformed complete requests record accepted, fixed `-32700` protocol error, and
rejected in that order. Strict unknown-header rejection and ignored-header
unresolved-method handling record accepted, one unknown-header event per
occurrence, the applicable fixed protocol error, and rejected in FIFO enqueue
order. Protocol-error production is limited to `-32700`, `-32600`, `-32601`,
`-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998`;
application-owned error codes do not produce this metric event. A fixed error
is recorded only after successful encoding. A streamed `ErrorResponse` keeps
its record provisional until terminal acceptance and discards it if terminal
delivery loses or fails.

Each unknown-header occurrence carries only the endpoint path and a bounded
recognized method or `<unrecognized>`; it carries no header name or value and
no raw unrecognized method. Its occurrence count is independent of the
optional diagnostic-name quota. Pre-admission quartet entries are request-free.
Only an admitted fixed protocol error recorded after request observation may
retain the exact public `McpRequestContext` and originating `Request` for
bounded failure attribution. Pending entries may transiently hold that context
only for delivery and failure logging, and it is never rendered.

Collector callbacks run after the relevant internal locks or monitors are
released. Request-transition deferral is nonwaiting, which preserves liveness
under reentrant collector callbacks. Server-event failures remain request-free,
and all collector failures are contained.

Direct restart orders the old generation's `ServerStopped` before the new
`ServerStarted`; managed startup rollback orders `ServerStarted` before
`ServerStopped`. The FIFO guarantee is metric record/enqueue order only, not a
universal cross-thread causal or per-request total order for independently
racing producers. No public API, diagnostics/snapshot field, aggregate family,
label, event variant, or wire dimension was added.

The eighth vertical extends the same FIFO to all 23 declared variants with
`ConnectionAccepted`, `ConnectionRejected`, and `TransportFailure`.
`ConnectionAccepted` follows operating-system accept and capacity reservation,
before registration or request processing, so a later setup failure may follow
it. `ConnectionRejected` means only that an accepted socket encountered the
configured connection-capacity limit; accept/setup faults instead emit their
typed transport failure without a capacity rejection.

`TransportFailure` is request-free and carries exactly one bounded enum value:
`REQUEST_READ_TIMEOUT`, `REQUEST_TOO_LARGE`, `MALFORMED_REQUEST`, `READ_ERROR`,
`WRITE_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT`, `RESPONSE_READY_ERROR`,
`REQUEST_READ_TIMEOUT_ERROR`, `RESPONSE_WRITE_IDLE_TIMEOUT_ERROR`,
`ACCEPT_LOOP_ERROR`, `CONNECTION_SETUP_ERROR`, `TASK_ERROR`,
`TIMEOUT_TASK_ERROR`, `SELECTION_KEY_ERROR`, `REGISTER_ERROR`, `WRITE_TIMEOUT`,
`EVENT_LOOP_TERMINATED`, or `UNKNOWN`. Neither the event nor its collector-
failure log retains a remote address, raw request/context, throwable, payload,
trace token, or other unbounded dimension. Typed low-level authorities select
the reason without parsing strings.

Typed provisional scopes and a coalescing single-daemon-worker scheduler drain
after transport locks, never synchronously fall back to collector invocation on
a connection thread, and preserve a racing signal across executor rejection.
Blocking lifecycle deferral safely adopts pending delivery. A byte-free idle
close is quiet while a partial request produces `REQUEST_READ_TIMEOUT`;
transport-malformed HTTP stays distinct from complete malformed JSON-RPC. The
request-SSE write-idle winner records one `WRITE_TIMEOUT` before terminals; a
losing/generic close records no `WRITE_TIMEOUT`, and channel cancelation does
not synthesize `WRITE_ERROR`. Fatal `EVENT_LOOP_TERMINATED` is recorded before
stop/wake,
remains scoped through sibling cleanup, and precedes old `ServerStopped` and
new `ServerStarted` before restart returns.

Separate from the first eight production observability and diagnostics
verticals,
a bounded Phase 6 MCP fuzz-registration and hardening checkpoint adds five new
Jazzer methods:
`McpJsonRpcEnvelopeCodecFuzzTest#decodeClassifiesOrRejectsOnlyWithTypedWireFailure`,
`McpMirroredHeaderCodecFuzzTest#decodeStringOnlyRejectsWithRedactedIllegalArgumentException`,
`McpToolSchemaProfileFuzzTest#compileAndEvaluateRemainTypedAndBounded`,
`McpCursorValidatorFuzzTest#cursorValidationIsUtf8ExactAndTotal`, and
`McpRequestStatePlaintextCodecFuzzTest#decodeOnlyRejectsWithUniformRedactedIllegalArgumentException`.
This fuzz checkpoint remains unnumbered; at that point the production count
remained eight. Twenty-one checked-in synthetic text seeds cover the new
targets, and the nightly workflow now declares 15 total one-method slots, five
of them new.

The envelope target applies production JSON limits and accepts only classified
success or typed `McpWireDecodingException`, without an unconditional encode
round trip. Mirrored-header decoding applies the production default bound and
only its uniform redacted `IllegalArgumentException`. The Profile 1 target caps
schema/instance input at 64 KiB and requires stage-typed compilation or
production-bounded evaluation outcomes. The cursor target caps input at 64
KiB and cross-checks decoded UTF-8 and raw UTF-16 projections against the JDK
UTF-8 encoder in `REPORT` mode for a derived 1-to-256-byte limit. The request-
state plaintext target fixes its binding, clock, request ID, 4,096-byte bound,
15-minute lifetime, and three-round limit; successful plaintext must re-encode
byte-exactly, rejection remains uniformly redacted, and terminal-LF copying is
limited to 4,097 input bytes. The cursor validator is an internal,
package-private seam shared by incoming and outgoing cursor checks and adds no
public API.

The seeds are synthetic protocol values rather than captured requests,
protected deployment state, secrets, credentials, or raw trace context. No
scheduled or manual coverage-guided nightly run occurred. Deterministic replay
is not sustained, coverage, corpus-saturation, privacy, security,
release-readiness, or Phase 6 freeze proof.

An unnumbered internal trace-correlation derivation checkpoint implements the
frozen token construction. Trace correlation is disabled by default, and
disabled controls capture no token. Enabled controls
snapshot one complete active key ID and key-material pair under the shared
security lock, derive after releasing it with HMAC-SHA-256 over UTF-8
`soklet-mcp-trace-correlation-v1\0` plus the decoded 16-byte trace ID, truncate
to the first 16 digest bytes, and encode an unpadded 22-character Base64URL
token. Invalid and all-zero trace IDs are rejected before derivation; equal
key/trace inputs agree, changed key or trace inputs differ, and concurrent
rotation exposes only coherent old or new `(keyId, token)` pairs. Copied key
material and explicit derivation buffers are zeroed, and the internal carrier
retains only the nonsecret key ID and token while redacting the token from
rendering.

The ninth bounded production vertical now captures one carrier exactly once
for each admitted semantic request before lifecycle and handler observation.
Only a valid MCP `_meta.traceparent` is eligible. Disabled correlation,
invalid or all-zero MCP trace context, absent metadata, and valid physical HTTP
trace without valid MCP metadata all produce no carrier. Lifecycle,
interceptor, handler, and terminal observation share the same immutable
request context and carrier. A pre-rotation request retains its old
`(keyId, token)` through terminal observation, while a fresh post-rotation
request adopts the new pair. Raw validated trace-ID opt-in neither enables nor
changes correlation. The hidden final carrier retains only nonsecret key ID
and token, never raw trace context or key material, and redacts the token from
rendering.

At that point, following the ninth vertical, the prior fuzz and dormant
derivation checkpoints remained unnumbered. `SOK-TRACE-001`, `SOK-TRACE-002`,
and `SOK-TRACE-003` were COMPLETE; `SOK-TRACE-004` and `SOK-TRACE-005` were
PLANNED; and `SOK-PRIV-001` was PARTIAL. The carrier, accessor, and construction
path are package-private, absent from `phase-6.includes` and public snapshots, and add no public
API or API-sketch source. No structured-log carrier, field, emission point,
cadence, or new `LogEventType` exists; raw trace-ID logging remains
unimplemented. No metric, event, diagnostics/snapshot field, aggregate, label,
or wire dimension was added. Tokens remain pseudonymous high-cardinality
operational metadata, not anonymization, authentication, or authorization
inputs. The carrier is not finish-cleared and has no GC or application-
reference lifetime guarantee; an application-retained context naturally
retains it, while core controls retain only the current key and expose no
history API. This is not comprehensive trace/baggage redaction, cardinality,
privacy/security, aggregate/`AMB-003`, simulator, release-readiness, or Phase 6
freeze evidence.

A third unnumbered Phase 6 checkpoint was covered by
`McpObservabilityPublicApiTests#metricSchemaHasExactFiniteNonTraceDimensions`
and
`McpRequestObservationPublicRuntimeTests#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.
The exact sealed inventory remains 23 event records, including 11 fieldless
variants; all other components are endpoint path, bounded method, fixed
outcome/reason/code, or nonnegative duration. Production supplies registered
endpoints, recognized methods or `<unrecognized>`, ten fixed codes, and fixed
enums. At that checkpoint, the public record constructors accepted arbitrary
application-created nonempty routed strings and non-null codes. The snapshot
was three boxed `Long` values plus immutable
`Map<ShutdownComponentDisposition, Long>`; the default collector aggregated only five
handler variants and `ServerStopped`, ignoring and retaining none of the other
17 variants.

The runtime gate sends 16 sequential admitted requests carrying distinct valid
MCP and HTTP trace IDs, tracestate, baggage, derived tokens, and key canaries.
None appears in built-in MCP event/snapshot state, metric names or labels,
filter-observed samples, Prometheus, OpenMetrics, or reset output. At that
checkpoint, exactly three label-free handler samples plus clean shutdown
appeared before reset; exactly the three label-free samples remained afterward.
The production-vertical count remained nine, and fuzz registration, dormant
derivation, and metric
dimensionality were the three unnumbered checkpoints. `SOK-TRACE-001/002/003`
were COMPLETE; `SOK-TRACE-004` was PLANNED; `SOK-TRACE-005` was PARTIAL
for metric-dimension inventory/default-collector evidence only; and
`SOK-PRIV-001` was PARTIAL. `SOK-METRIC-001` and `SOK-METRIC-004`
remained PARTIAL; `AMB-003` remained AMBIGUOUS.

That checkpoint changed no production source, public API or sketch, owner or
signature inventory, metric family, label, event, or wire behavior. It does
not cover custom collectors; generic HTTP `MetricsCollector` callbacks that
receive a `Request`, request target, or `Throwable`; `LogEvent`, application
callbacks, handler telemetry, or arbitrary application-created event
vocabulary; structured logging or raw-ID emission; future aggregates;
comprehensive trace/baggage redaction; sustained cardinality, fuzz, or soak;
simulator, migration, release-candidate provenance, review, or Phase 6 freeze.

Transport aggregation is the tenth production vertical, server-start
aggregation is the eleventh, and request-boundary aggregation is the twelfth;
the three earlier checkpoints remain unnumbered.
At the tenth checkpoint, `McpMetricsSnapshot` declared seven getters and its
builder eight public methods including `build()`.
The three additive getter/builder pairs expose boxed accepted and rejected
connection counts plus an immutable sparse
`Map<MetricsCollector.TransportFailureReason, Long>`. This is provisional
Phase 6 public API; the frozen Phase 4/5 inventories and hashes are unchanged.

At that checkpoint the default collector aggregated nine variants; 14 variants
were still ignored.
It renders label-free `soklet_mcp_connections_accepted_total` and
`soklet_mcp_connections_rejected_total`, including configured or event-
activated zeros, and merges MCP failure samples into the existing
`soklet_transport_failures_total` family with only `server_type="MCP"` and a
fixed reason. These make seven implemented rendered aggregate families. HTTP,
SSE, and MCP share one HELP/TYPE block; an all-rejecting
sample filter suppresses the block. Reset clears cumulative transport values
without mutating retained snapshots. The 16-request gate consequently has six
MCP-prefixed samples before reset and five after reset, while its sparse
failure map stays empty and trace canaries remain absent.
The exact transport gates are
`McpTransportMetricsAggregationTests#snapshotContractUsesBoxedConnectionCountsAndImmutableBoundedTransportFailures`,
`#defaultCollectorAggregatesRendersFiltersAndResetsTransportBoundaryFamilies`,
`#sharedTransportFamilyCombinesServerTypesWithSingleMetadataBlock`, and
`#concurrentDirectIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The eleventh vertical added boxed, nonnegative `getServerStarts()` and
`serverStarts(Long)`. At that checkpoint the provisional snapshot had eight getters and its
builder nine public methods including `build()`: six boxed `Long` values and
two immutable maps. `DefaultMetricsCollector` consumes the existing fieldless
`ServerStarted` event under its exact lifecycle authority—one per successful
listener generation, none for failed staged or already-started no-op attempts,
the successful start before rollback stop, and one for each fresh restart.
Configured collectors render label-free `soklet_mcp_server_starts_total` at
zero. A direct `ServerStarted` or `ServerStopped` activates the lifecycle
subset, so a stop-only collector renders zero starts plus shutdown. Filtering
the start sample suppresses its HELP/TYPE block; reset clears the cumulative
count while preserving zero-family visibility; retained snapshots are
immutable. Start and shutdown totals are not complementary or conserved while
a generation remains running.

The fieldless event and label-free family retain no request, remote identity,
endpoint, method, outcome, throwable, header, trace ID, token, key, tracestate,
baggage, or application label. At that checkpoint the default collector
aggregated 10 of 23 variants and ignored 13, with eight rendered families. The 16-request gate had
seven MCP-prefixed samples before reset and six after. Exact coverage is
`McpServerStartMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeServerStarts`,
`#defaultCollectorAggregatesConfiguredAndDirectServerStartsAcrossRenderFilterAndReset`,
and
`#concurrentDirectServerStartIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.

The twelfth vertical added boxed, nonnegative `getRequestsAccepted()` and
`getRequestsRejected()` with matching `requestsAccepted(Long)` and
`requestsRejected(Long)`. At that checkpoint the provisional snapshot had ten
getters and 11 public builder methods including `build()`: eight boxed `Long`
values and two immutable maps.

`RequestAccepted` becomes durable only after the bounded processor accepts
`Executor.execute`; rejection or throw identity-discards the provisional
accepted entry. `RequestRejected` is exact once for a complete Handler request
whose terminal wins before atomic observation-start reservation. A terminal
pre-admission path can produce both, while execute failure can produce rejected
without retained accepted. These are independent request-boundary counts, not
complements or a conservation equation, and exclude early transport/Microhttp,
post-admission outcome, and handler-capacity rejection.

Configured collectors and either direct event activate paired label-free
families: `soklet_mcp_requests_accepted_total` with HELP `Total MCP requests
accepted by the bounded protocol processor`, and
`soklet_mcp_requests_rejected_total` with HELP `Total MCP requests rejected
before admitted semantic handling`. Both render zero when unobserved. Filtering
removes rejected family metadata with its sample; reset clears cumulative
counts but retains configured/event-activated paired visibility. OpenMetrics,
retained immutable snapshots, and post-quiescence concurrent ingest are
covered by
`McpRequestAdmissionMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeRequestAdmissionCounts`,
`#defaultCollectorAggregatesConfiguredAndDirectRequestAdmissionEventsAcrossRenderFilterAndReset`,
and
`#concurrentDirectRequestAdmissionIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Exact producer authority is also covered by
`McpHttpServerApplicationExecutionTests#protocol_processor_submission_records_two_accepted_then_one_rejected_outside_request_control_lock`
and
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`.

The thirteenth vertical implements admitted-request lifecycle aggregation.
Boxed, nonnegative `getActiveRequests()`, immutable `getRequests()` and
`getRequestDurations()` maps, and matching builder methods expand the
provisional snapshot to 13 getters and 14 public builder methods including
`build()`: nine boxed `Long` values and four maps. The new public, thread-safe
`McpMetricsSnapshot.RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome)`
rejects nulls and empty routed strings but does not validate registry
membership. Built-in keys contain only a registered endpoint, recognized
method or `<unrecognized>`, and fixed outcome; count and histogram maps are
independently sparse.

Exact `RequestStarted`/`RequestFinished` delivery drives the active gauge
`soklet_mcp_requests_active`, completed counter `soklet_mcp_requests_total`,
and `soklet_mcp_request_duration_nanos` histogram. Completed samples use only
`endpoint`, `method`, and lower-snake `outcome`; histogram boundaries are 1, 2,
5, 10, 25, 50, 100, 200, 400, 800, 1,500, 3,000, 7,000, and 15,000
milliseconds plus overflow. No standalone start/finish counters exist.
Configured empty state renders only gauge zero; sparse families and HELP/TYPE
metadata remain absent when empty or fully filtered. Reset preserves active
state, clears completed maps/histograms, and a request crossing reset records
its full original duration. Retained snapshots are immutable; balanced
post-quiescence concurrent ingest is lossless.

No request/network identity, raw unrecognized method, error detail, throwable,
header, trace/token/key, tracestate, baggage, or application telemetry enters
these built-in dimensions. This does not constrain custom collectors, generic
HTTP metrics callbacks, logs, application-created events/keys, or telemetry;
promise cross-field atomicity during mutation; or repair unmatched manual
events. Exact tests are
`McpRequestLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestLifecycleFamilies`,
`#resetPreservesActiveRequestsAndLateFinishRecordsFullOriginalDuration`, and
`#concurrentBalancedRequestLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Authority/cardinality tests include
`McpRequestObservationPublicRuntimeTests#admittedDiscoveryPublishesLifecycleAndMetricsWithoutInterception`,
`#admissionRejectionDoesNotPublishAdmittedRequestObservation`, and
`#distinctTraceMetadataDoesNotCreateMetricDimensionsOrLeakIntoRendering`.

The fourteenth vertical implements request-stream lifecycle aggregation.
Boxed, nonnegative `getActiveRequestStreams()`, immutable
`getRequestStreamDurations()`, and matching builders expand the provisional
snapshot to 15 getters and 16 public builder methods including `build()`: ten
boxed `Long` values and five maps. The public, thread-safe
`McpMetricsSnapshot.RequestStreamTerminationKey(endpointPath, jsonRpcMethod,
reason)` rejects null/empty shape but does not validate registry membership.

Exact `RequestStreamOpened`/`RequestStreamClosed` delivery drives
`soklet_mcp_request_streams_active` with HELP `Currently active MCP request
streams` and `soklet_mcp_request_stream_duration_nanos` with HELP `MCP
request-stream duration in nanoseconds`. The transition records open before
accepted progress/keepalive observations and the single close before terminal
`RequestFinished`; this is FIFO record/enqueue order, not a universal
cross-thread total order. Samples use bounded `endpoint`,
`method`, and lower-snake `reason`: `completed`, `client_disconnected`,
`request_canceled`, `deadline_exceeded`, `write_failed`, `backpressure`,
`server_stopped`, `simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`. The
13 inclusive buckets are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600,
7,200, and 14,400 seconds plus overflow. No standalone open/close counters
exist.

Configured collectors and either direct event activate gauge-zero visibility;
the duration family stays sparse and emits no orphan HELP/TYPE metadata when
empty or fully filtered. Prometheus/OpenMetrics filtering, reset preserving
the live gauge while clearing histograms, full duration across reset, immutable
retained snapshots, and balanced post-quiescence concurrent ingest are covered
by
`McpRequestStreamLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableRequestStreamLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersRequestStreamLifecycleFamilies`,
`#resetPreservesActiveRequestStreamsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedRequestStreamLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
and
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`.

Built-in keys retain only registered endpoint, recognized method or
`<unrecognized>`, and fixed reason. No request/network identity, error detail,
throwable, header, trace/token/key, tracestate, baggage, or application
telemetry enters these dimensions. This does not constrain custom collectors,
generic HTTP/SSE metrics, logs, application-created events/keys, or telemetry;
promise cross-field or concurrent-reset atomicity, repair unmatched manual
events, equate metrics with diagnostics, expose subscription breakdown,
promise canonical order, add OpenTelemetry/trace emission, or prove sustained,
simulator, privacy, release-readiness, or Phase 6 freeze.

The fifteenth vertical implements subscription lifecycle aggregation. Boxed,
nonnegative `getActiveSubscriptions()`, immutable
`getSubscriptionDurations()`, and matching builders expand the provisional
snapshot to 17 getters and 18 public builder methods including `build()`: 11
boxed `Long` values and six maps. The public, thread-safe
`SubscriptionTerminationKey(endpointPath, reason)` rejects null/empty shape but
does not validate registry membership.

Exact `SubscriptionOpened`/`SubscriptionClosed` delivery drives
`soklet_mcp_subscriptions_active` with HELP `Currently active MCP subscriptions`
and `soklet_mcp_subscription_duration_nanos` with HELP `MCP subscription
duration in nanoseconds`. Samples use bounded `endpoint` and lower-snake
`reason`: `completed`, `client_disconnected`, `request_canceled`,
`deadline_exceeded`, `write_failed`, `backpressure`, `server_stopped`,
`simulator_capture_item_limit_exceeded`,
`simulator_capture_byte_limit_exceeded`, and `internal_error`. The 13 buckets
are 1, 5, 10, 30, 60, 120, 300, 600, 1,800, 3,600, 7,200, and 14,400 seconds
plus overflow; there are no standalone open/close counters.

Produced FIFO order is `RequestStreamOpened`, `SubscriptionOpened`, then at
termination `RequestStreamClosed`, `SubscriptionClosed`, and
`RequestFinished`; it is not universal cross-thread ordering or an atomic
relationship between gauges. Configured/direct zero visibility, sparse
no-orphan metadata, Prometheus/OpenMetrics filtering, reset preserving the
gauge while clearing histograms, full duration across reset, retained
immutability, and balanced post-quiescence concurrency are covered by
`McpSubscriptionLifecycleMetricsAggregationTests#snapshotContractUsesReferenceTypedImmutableSubscriptionLifecycleState`,
`#defaultCollectorAggregatesRendersAndFiltersSubscriptionLifecycleFamilies`,
`#resetPreservesActiveSubscriptionsAndLateCloseRecordsFullOriginalDuration`,
and
`#concurrentBalancedSubscriptionLifecycleIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

Built-in keys retain only registered endpoint and fixed reason—never method,
resource URI, filter, request/network identity, error detail, throwable,
header, trace/token/key, tracestate, baggage, or application telemetry. This
does not constrain custom collectors, generic HTTP/SSE metrics, logs,
application-created events/keys, or telemetry; promise cross-field or
concurrent-reset atomicity, repair unmatched manual events, equate metrics with
diagnostics, promise canonical order or conservation with stream gauges, add
OpenTelemetry/trace emission, or prove sustained, simulator, comprehensive
privacy, release-readiness, or Phase 6 freeze.

The sixteenth vertical implements independent progress and
cooperative-cancelation aggregation. Immutable
`Map<EndpointMethodKey, Long> getCancelationsSignaled()` and
`getProgressEmitted()` plus matching builders expand the provisional snapshot
to 19 getters and 20 public builder methods including `build()`: 11 boxed
`Long` values and eight maps. The public, thread-safe
`EndpointMethodKey(endpointPath, jsonRpcMethod)` rejects null/empty shape but
accepts arbitrary nonempty application-created values.

Exact delivered `CancelationSignaled` drives
`soklet_mcp_cancelations_signaled_total{endpoint,method}` with HELP `Total
cooperative MCP request cancelations signaled by endpoint and method`; exact
`ProgressEmitted` drives
`soklet_mcp_progress_emitted_total{endpoint,method}` with HELP `Total MCP
progress notifications accepted for delivery by endpoint and method`. They are
independent counters, not complements or a conservation equation. Configured
empty state emits neither samples nor metadata, direct events populate only
their own sparse family, all-rejected filters leave no orphan HELP/TYPE block,
OpenMetrics retains one EOF, and reset clears both maps. Defensive copies,
explicit application zeros, retained immutability, and post-quiescence
concurrent losslessness do not imply cross-map atomicity.

Live authority in
`McpProgressPublicRuntimeTests#disconnectCancelsSameFeatureInstanceAndRunsCallback`
proves two accepted progress events, one cooperative-cancelation event,
serialized delivery outside the reporter monitor, and no post-cancel progress;
it does not impose universal cross-thread terminal order. Built-in labels
contain only registered endpoint and bounded method—never progress
token/value/total/message, cancelation reason, request/network identity,
throwable, header, trace ID/token/key material, tracestate, baggage, or
application telemetry. Exact tests are
`McpProgressAndCancelationMetricsAggregationTests#snapshotContractUsesSharedImmutableEndpointMethodCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProgressAndCancelationFamilies`,
`#resetClearsSparseProgressAndCancelationCountersWithoutLeavingFamilyMetadata`,
and
`#concurrentDirectProgressAndCancelationIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs,
application-created events/keys, or telemetry; prove coverage of every live
cancelation cause, canonical order, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator or release evidence, or Phase 6
freeze.

The seventeenth vertical implements fieldless keep-alive aggregation. Boxed,
nonnegative `@NonNull Long getKeepAlivesEmitted()` and matching
`keepAlivesEmitted(Long)` expand the provisional snapshot to 20 getters and 21
public builder methods including `build()`: 12 boxed `Long` values and eight
immutable maps.

Each exact FIFO-delivered `KeepAliveEmitted` drives the label-free
`soklet_mcp_keep_alives_emitted_total` counter with HELP `Total MCP keep-alive
comments accepted for delivery`. Configured MCP and direct events activate the
family; configured and post-reset states render zero. Filters receive an empty
label map, full rejection leaves no sample or orphan HELP/TYPE metadata,
OpenMetrics emits one EOF, reset clears the count while retaining visibility,
retained snapshots remain immutable, and post-quiescence concurrent direct
ingest is lossless.

Live authority is bounded by
`McpSubscriptionPublicRuntimeTests#keepAliveAcceptanceSharesStreamTransitionWithCloseObservation`
and
`McpSubscriptionRuntimeBoundaryTests#maximumDurationIsAbsoluteAcrossKeepAlivesAndEvents`.
They freeze accepted wire-observation/transition order and an exact-one
deterministic boundary, not timer attempts or client/intermediary receipt; no
conservation with subscriptions, streams, or terminal events is claimed. The
fieldless built-in event retains no request, endpoint, method, remote identity,
duration, reason, throwable, header, trace ID/token/key, tracestate, baggage,
or application label. Exact tests are
`McpKeepAliveMetricsAggregationTests#snapshotContractUsesBoxedNonnegativeKeepAliveCount`,
`#defaultCollectorAggregatesConfiguredAndDirectKeepAlivesAcrossRenderFilterAndReset`,
and
`#concurrentDirectKeepAliveIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
This does not constrain custom collectors, generic HTTP/SSE metrics, logs, or
application telemetry; promise universal cross-thread order, delivery/receipt,
cross-field or concurrent-reset atomicity, OpenTelemetry/trace emission,
comprehensive privacy, sustained/simulator or release evidence, or Phase 6
freeze.

The eighteenth bounded Phase 6 production vertical completes the provisional
core snapshot aggregate surface with immutable
`Map<Integer, Long> getProtocolErrors()` and
`Map<EndpointMethodKey, Long> getUnknownMirroredHeaders()`, plus matching
`protocolErrors(Map)` and `unknownMirroredHeaders(Map)` builder methods. The
surface now has 22 getters and 23 public builder methods including `build()`:
12 boxed `Long` values and ten maps. No new public owner is introduced, so the
32-entry provisional inventory and 210-owner reviewed union are unchanged. The
three fuzz, dormant-derivation, and metric-dimensionality checkpoints remain
unnumbered.

The default text families are
`soklet_mcp_protocol_errors_total{code}` with HELP `Total client-visible MCP
protocol errors by fixed code` and
`soklet_mcp_unknown_mirrored_headers_total{endpoint,method}` with HELP `Total
unknown MCP mirrored-header occurrences by endpoint and method`. The two maps
are independent and sparse: configuration alone emits no family metadata, a
direct event affects only its own map, fully rejected filters leave no orphan
HELP/TYPE, OpenMetrics retains one EOF, and reset removes all samples and
metadata. Built snapshots are defensive and immutable and preserve explicit
zero counts.

Framework production uses exactly `-32700`, `-32600`, `-32601`, `-32602`,
`-32603`, `-32020`, `-32021`, `-32022`, `-31999`, and `-31998` after successful
client-visible encoding or accepted streamed-terminal reservation. Failed
provisional terminal entries, application codes, tool-result `isError`, and
empty-notification HTTP errors are excluded. Unknown headers contribute once
per occurrence under IGNORE and REJECT and use only registered endpoint and a
recognized core method or `<unrecognized>`, never header name/value or raw
unrecognized method. Pre-admission errors are request-free; admitted fixed
errors use the exact admitted context only for bounded delivery/failure
attribution.

The two default maps independently retain at most 8,192 dimensions. Public
builder maps are uncapped value carriers and accept arbitrary non-null Integer
codes and shape-valid nonempty `EndpointMethodKey` values with nonnegative
counts, including explicit zero. Protocol maps iterate in natural Integer
order; no canonical order is promised for EndpointMethodKey maps. The exact
ten codes and bounded live method vocabulary therefore do not constrain
arbitrary public/manual construction.

Exact aggregate coverage is
`McpProtocolAndUnknownHeaderMetricsAggregationTests#snapshotContractUsesImmutableProtocolAndUnknownHeaderCounterMaps`,
`#defaultCollectorAggregatesRendersAndFiltersProtocolAndUnknownHeaderFamilies`,
`#resetClearsSparseProtocolAndUnknownHeaderCountersWithoutLeavingFamilyMetadata`,
`#manualDimensionRetentionIsIndependentlyBoundedPerFamily`, and
`#concurrentDirectProtocolAndUnknownHeaderIngestIsLosslessAndRetainedSnapshotsRemainImmutable`.
Live authority remains covered by
`McpPreAdmissionMetricsEventPublicRuntimeTests#acceptedMalformedRequestEmitsExactProtocolErrorThenRejectionWithoutAdmission`,
`#applicationCodesAreExcludedWhileAdmittedFixedErrorsRetainExactRequestContext`,
`#unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies`,
`#preAdmissionQuartetDeliveryIsReentrantAndSerializedWithoutCrossRequestOrderClaim`,
`McpHttpServerApplicationExecutionTests#produced_protocol_error_metric_allowlist_is_exact_and_excludes_application_codes`,
and `#failed_stream_terminal_discards_provisional_protocol_error_metric`.

Built-in dimensions contain no header identity, request, throwable, payload,
remote identity, trace ID/token/key material, tracestate, baggage, or generic
application label. Accepted/unknown/error/rejected and admitted
started/error/finished sequences promise FIFO record/enqueue order only. This
does not constrain arbitrary manual vocabulary, custom collectors, generic
HTTP/log/Request/Throwable/application telemetry, or structured/raw-ID
emission; implement downstream OpenTelemetry mapping; prove sustained, soak,
simulator or release-candidate behavior; or freeze Phase 6.

The nineteenth bounded Phase 6 production vertical is downstream-only: it
changes no core API owner, signature, snapshot, sketch, event, label, canary,
or wire inventory. `soklet-otel:1.4.0-SNAPSHOT`, using
`soklet:3.6.0-SNAPSHOT` by default, adds
`didRecordMcpMetricsEvent(McpMetricsEvent)` and maps all 23 variants to exactly
22 instruments—21 MCP-specific instruments plus shared transport failures.
The existing core ledgers therefore remain 22 snapshot getters, 23 public
builder methods including `build()`, 12 boxed `Long` values, ten maps, 32
provisional owners, and a 210-owner reviewed union.

The downstream schema uses seven fixed MCP attributes and the shared
transport type/reason pair, exact lower-snake enum values, and 14 request plus
12 long-lived finite duration bucket boundaries in seconds. For framework-
produced events, the integration adds no dedicated attributes for trace/raw
request IDs, progress token/value/message, mirrored-header identity, request
objects, throwables, operation/resource URIs, principal/address, tracestate,
baggage, or generic bags. Framework-generated vocabulary remains bounded;
arbitrary valid values in direct application-created events may contain
sensitive text, so applications own their confidentiality and cardinality.
OpenTelemetry SDK series retention is not the core default collector's
8,192-entry policy.

At the V19 boundary, this sibling-artifact migration deliberately removed all obsolete MCP
request/session/SSE tracing callbacks, span-policy knobs, and span-naming
methods. The reviewed downstream public comparison then contained exactly 15
removed legacy methods and one added metrics callback. It also removed the
four legacy MCP session instruments and their endpoint-class,
session-termination/identity, and request-ID-presence attributes. Modern MCP
lifecycle callbacks then remained inherited no-ops; no replacement spans were added.
Consumers of the former 1.3.1/3.5.1 MCP tracing surface must migrate
deliberately, while HTTP/SSE telemetry remains supported.

Exact downstream tests are
`OpenTelemetryMetricsCollectorTests#allTwentyThreeMcpEventsMapToExactTwentyTwoInstrumentsAndTransitions`,
`#mcpInstrumentContractUsesExactKindsUnitsAttributesAndBuckets`,
`#mcpEnumAndManualDimensionsUseExactTypedVocabularyWithoutSensitiveAttributes`,
`#mcpSchemaIgnoresHttpNamingStrategyRemovesLegacySessionsAndPreservesFailureBoundary`,
`#handlesConcurrentMcpMetricEventsWithoutLoss`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
At that point, the complete downstream suite passed 28/0/0/0 on both JDK 21
and JDK 26.
`AMB-003` is now RESOLVED CONTRACT 2026-08-10 / CORE IMPLEMENTATION COMPLETE /
DOWNSTREAM METRIC IMPLEMENTATION COMPLETE. Snapshot/reset/filter/OpenMetrics
parity, SDK retention caps, modern MCP spans, structured logs, sustained
cardinality, simulator/release evidence, and Phase 6 freeze remain outside
that V19 result. Modern `McpRequestContext` span semantics were the next
contract slice.

The twentieth bounded production vertical implements those admitted-request
spans in the same unreleased `soklet-otel:1.4.0-SNAPSHOT` against
`soklet:3.6.0-SNAPSHOT`. Boxed `recordMcpRequestSpans` policy defaults true;
the additive context-shaped default naming method preserves existing
three-method strategies. Default name and `rpc.method` expose only the exact
ten core methods or `<unrecognized>`, never an original raw unsupported method.
Custom naming receives the full context and remains application-owned.

One SERVER span covers each admitted request/notification through stream or
subscription lifetime to exact terminal observation. Parentage uses only
validated MCP `_meta.traceparent`/`tracestate`; HTTP headers, ambient context,
and baggage do not backfill it. Start attributes are MCP server type, JSON-RPC
system, bounded method, and endpoint. Physical client address and Soklet
request ID remain off-by-default opt-ins; the latter never uses JSON-RPC ID.

Finish always records lower-snake outcome. A JSON-RPC error writes its decimal
code as string response status and `error.type` and marks ERROR. Without an
error, six fixed error outcomes mark ERROR; complete/input-required/canceled/
client-disconnected remain UNSET. Throwables create no event or material.
Duration overflow uses a plain-end fallback. Disabled policy, missing/late
finish, duplicate direct starts, close drain, concurrent publication/close,
telemetry failure containment, and concurrent context isolation are covered.

Built-in spans exclude JSON-RPC ID, metadata, operation/path/capability/
admission data, baggage, HTTP trace fallback, error message/data, throwable,
and exception events apart from intended MCP parentage and explicit physical
address/request-ID opt-ins. No legacy session/stream/subscription span,
custom-namer safety, structured/raw-ID emission, comprehensive privacy,
sustained cardinality, simulator/release, or Phase 6 freeze is claimed.

Exact V20 tests are
`OpenTelemetryMcpLifecycleObserverTests#mcpMetadataTraceContextIsTheOnlyRemoteParentAndPreservesTraceState`,
`#mcpSpanUsesExactDefaultAndCustomNamesAttributesAndTerminalSemantics`,
`#allMcpRequestOutcomesMapToExactStatusAndErrorVocabulary`,
`#mcpRequestSpanStaysOpenUntilTerminalFinishAcrossStreamAndSubscriptionLifetimes`,
`#mcpPolicyAndNamingAreModernAdditiveAndLegacySessionControlsRemainAbsent`,
`#mcpTelemetryFailuresAreContainedAndReleaseStateExactlyOnce`,
`#concurrentMcpSpansRemainContextIsolatedAndCloseDrainsEveryState`,
`#mcpSpanProjectionExcludesSensitiveContextAndHttpFallbackCanaries`, and
`OpenTelemetryLifecycleObserverTests#legacyMcpSessionTracingSurfacesRemainAbsentAndModernRequestCallbacksAreImplemented`.
Core authority is
`McpRequestObservationPublicRuntimeTests#successfulToolSharesOneContextAndFinishesExactlyOnce`,
`#traceCaptureUsesOnlyValidMcpMetadataWithoutHttpFallback`,
`#handlerFailurePublishesExactInternalErrorAndImmutableThrowable`,
`#unsupportedNotificationRetainsRawLifecycleMethodAndBoundsMetrics`,
`#throwingObservationCallbacksAreContainedLoggedAndPartitioned`,
`McpRequestPropagationTests#validatedMetadataReachesAdmissionAndToolHandlersInsteadOfHttpTraceHeaders`,
`#invalidOrMistypedMetadataIsOmittedWithoutFallingBackToHttpHeaders`,
`#baggageParsingIsBoundedDecodedAndImmutable`,
`McpSubscriptionPublicRuntimeTests#configuredMaximumDurationPublishesExactLifecycleAndMetrics`,
and `#clientDisconnectReleasesStateAndPublishesExactlyOnce`.

V20 adds five declared downstream methods relative to V19; the reviewed
`1.3.1` to current diff is 13 removals and four additions. Core inventories
remain 23/23 variants, 22 families, 22 snapshot getters, 23 builder methods,
12 `Long` values, ten maps, 31/12 canary samples, 32 provisional owners, and
210 reviewed owners. `MCP-BASE-026` is COMPLETE. `AMB-003` remains resolved/
core-complete/downstream-metric-complete; metric-only `SOK-TRACE-005`,
`SOK-PRIV-001`, `SOK-METRIC-001`, and `SOK-METRIC-004` remain PARTIAL;
`SOK-TRACE-004` remains PLANNED. At that V20 boundary, MCP simulator
integration was next.

The twenty-first bounded production vertical assigns the shared public
`Simulator` host to Phase 6 and adds its two abstract
`startMcpRequest(Request)` and
`startMcpRequest(Request, McpSimulationOptions)` methods. The seven top-level
public types are `McpSimulation`, `McpSimulationOptions`,
`McpSimulationResponse`, `McpSimulationCompletion`,
`McpSimulationStreamItem`, `McpSimulationBodyType`, and
`McpSimulationStreamItemType`; the eighth new owner is
`McpSimulationOptions.Builder`. The reflection gate freezes exact reference
nullability, enum order, record-free interface shapes, method names, parameter
names, defaults, and thread-safety markers.

The API represents an asynchronous off-network MCP POST. Default options bound
pending SSE capture to 128 items and cumulative response/frame capture to
10,485,760 bytes. The simulation handle provides repeatable response and
completion waits, destructive FIFO item reads, boxed `isComplete()`, and
idempotent close. Response, item, and completion collections are
immutable; returned body/frame arrays are defensive copies. Completion retains
the exact public stream reason, an optional terminal message, and an immutable
ordered list of the same Throwable identities.

The production seam uses the real request processor and lifecycle but no live
listener. Public status stays `STOPPED`, bound address and diagnostics remain
empty/zero, and no server/connection/transport event is produced. Caller Host,
Origin, headers, and body are not repaired; configured port `0` requires a
literal `:0` Host authority. Item capacity precedes cumulative bytes, equality
is allowed, dequeuing refunds only a slot, terminal JSON is one counted item
and one no-extra-cost completion reference, and exact JSON/SSE overflow retains
the response head while excluding the offending bytes.

`close()` and scope exit use the existing request terminal reservation and
publish `CLIENT_DISCONNECTED` only when they win. Bounded residual cleanup,
suppression, restart blocking, overflow-safe waits, interruption without
cancelation, MRTR continuation, subscription replay, and close-versus-terminal
first-winner behavior are production-tested. These semantics do not make the
public Request, headers/body, or retained Throwables non-sensitive; disclosure
remains application-owned.

Representative exact citations from the full 46-test simulator/API gate are
`McpSimulationPublicApiTests#simulationSurfaceHasExactReferenceNullabilityAndClosedEnums`,
`McpPublicApiReflectionContractTests#phaseSixInventoryAndSharedHostDescriptorsAreExact`,
`McpSimulatorPublicRuntimeTests#startMcpRequestRejectsMissingServerConfiguration`,
`#defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero`,
`#multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest`,
`#subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder`,
`#mcpSimulationCompletionRetainsStreamCaptureFailures`,
`#noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression`,
`#waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently`, and
`McpSimulationCaptureRuntimeTests#closeAndTerminalRacePublishesOneCoherentFirstWinner`.

At the V21 boundary, `phase-6.includes` had 15 owners,
`provisional.includes` had 32, and the reviewed union had 219. The canonical
comparison had 558 records and
SHA-256
`d40004fa92cc5d095404de2133cf04fcd2b5574e9326eb680f571a017ef33671`.
At that boundary, frozen Phase 4/5 inventories remained 1,049/195 with
unchanged hashes. The core
metric surface is unchanged at 23/23 events, 22 families, 22 snapshot getters,
23 builder methods, 12 boxed `Long` values plus ten maps, and 31/12 canary
samples.

At the V21 boundary, `SOK-SIM-001` was COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE but did not yet claim every public simulator operation,
the 39-scenario suite through
simulation, live-network fidelity, stress/soak, sustained fuzz, comprehensive
privacy/security, release provenance, or Phase 6 freeze. Other statuses remain
unchanged at that checkpoint. The next planned work there was the first
complete release-workflow dry run and the remaining sustained, review, and
freeze gates; Phase 6 later froze as recorded below.

**Fourth unnumbered Phase 6 every-operation simulator, bounded capture-fuzz,
and off-network soak hardening checkpoint.** It hardens V21 without changing any public
descriptor, API sketch, owner inventory, comparison record, metric/event/
snapshot surface, wire contract, or numbered production vertical.
`McpSimulatorEveryOperationTests#recognizedRequestMethodsReplayExactJsonOrSseShapes`
reports nine request cases, while
`#cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation`
and `#concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained` cover the
ignored compatibility notification and deterministic concurrent isolation.
The exact 11-case class and six-class 57/0/0/0 selector freeze status, header
order, canonical JSON/SSE, same-context lifecycle, metrics, `STOPPED`
diagnostics, and no server/connection/transport events.

Internal capture-state-machine-only fuzz coverage comes from the methods
`McpSimulationCaptureFuzzTest#captureStateMachineRemainsBoundedTerminalAndIdempotent`
and `#curatedSeedsReachJsonSseLimitCancelAndCompletionBranches`, which add bounded
state-machine replay with six ASCII seeds: `json-complete.actions`,
`sse-terminal.actions`, `item-limit.actions`, `byte-limit.actions`,
`cancel.actions`, and `duplicate-terminal.actions`. The bounds are 65,536 input
bytes, 64 actions, 256 payload bytes, 16 pending items, and 4,096 captured
bytes. Focused replay passes 8/0/0/0; deterministic full replay passes
135/0/0/0 across 16 methods, 15 classes, and 27 MCP seeds. A five-second
coverage-guided launch was host-blocked before target execution and supplies no
coverage result; the declared `maxDuration=2m` is a registration bound, not
executed-run evidence.

`McpCrossFeatureSoakTests#mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup`
runs 24 fixed cycles over eight cases repeated three times with item/byte bounds
4/4,096 and one residual wave. It balances request/stream/subscription/handler
counts at 38/38, 24/24, 4/4, and 34/34, with residual 1, transport 0, listener
lifecycle 0, and final `STOPPED`. The JDK 26 smoke profile passes 5/0/0/0 across
three suites/five scenarios, its verifier SHA-256 is
`eaa1f52aad86dc2765200273a468801e938f5a6be1719845358c9aa57879bcd6`,
and the broadened JDK 26 selector passes 226/0/0/0. Clean exact-source full
suites on Corretto 21.0.11 and 26.0.1 each pass 1,539/0/0/4 across 166 suites,
compiling 440 main and 176 test Java sources. A separate local JDK 26 nightly-
shaped execution passes 5/0/0/0 with verifier SHA-256
`a20a70d6adb1fd2cb5909be76b219e38fc112524a12fc06552b26bdd8ec76d99`.
It runs 200 cycles over eight cases repeated 25 times and balances requests
236/236, streams 156/156, subscriptions 26/26, and handlers 210/210, with
residual 1, transport 0, listener lifecycle 0, final `STOPPED`, file-
descriptor delta 0, heap delta +15,272 bytes, and thread delta -1. This was a
local nightly-shaped execution, not scheduled CI, sustained, fleet, or
release-candidate evidence. V21 static-analysis, SpotBugs, packaging/Javadoc,
API-verifier, sketch, and schema evidence is carried forward and was not rerun
for this checkpoint.

At that fourth checkpoint, the ledger remained 21 numbered production
verticals and had four unnumbered checkpoints. `SOK-SIM-001` remains COMPLETE BOUNDED PHASE 6
IMPLEMENTATION EVIDENCE and now includes deterministic every-operation
evidence. This is not the strict local 39-scenario driver, every parameter/
error permutation, live-network fidelity, scheduled/manual or sustained
coverage-guided fuzz, corpus saturation, long/fleet soak, comprehensive
privacy/security, release provenance, or Phase 6 review/freeze.
`SOK-VALID-002` and `SOK-PRIV-001` advance narrowly but remain PARTIAL; all
other statuses remained unchanged. The next slice was a strict 39-row LOCAL
off-network driver tied byte-for-row and name-for-name to the pinned
`CLI/scenarios.json` manifest ordinal order; it was not the official CLI or a
live-network run.

**Fifth unnumbered Phase 6 candidate-artifact/public-API-only local 39-row
simulator-driver checkpoint.** `conformance/official/run-local-simulator.mjs`
validates and follows pinned `CLI/scenarios.json` manifest ordinal order for
the exact 39 active `RUN` rows at ordinals 1 and 3 through 40. Using only the
compiled fixture classes and candidate JAR, it invokes
`McpLocalSimulatorScenarioDriver#runManifestRowsOffNetwork`. Each ordinal/name
pair gets a fresh scenario configuration and simulator scope and performs
bounded public-API work. The package-private fixture source symbol
`McpConformanceFixture#simulationConfigForScenario` supplies the registrations
without extending the public or production surface.

The wrapper byte-compares exactly one
`PASS\t<ordinal>\t<name>\n` record per row in manifest ordinal order and
requires empty standard error and a clean exit. On Corretto 21 and 26, the
fixture and driver compile with `--release 17 -Xlint:all -Werror`, the fixture
contract main passes, `jdeps` finds no `com.soklet.internal` dependency, and
all 39 rows pass. The adversarial
`conformance/official/local-simulator-self-test.mjs` rejects reordered,
duplicate, missing, failed-spawn, nonzero-exit, signaled, standard-error,
wrong-output, `FAIL`, CRLF, and unterminated transcripts.

No production source, public API or sketch, owner/signature inventory,
metric/event/snapshot surface, wire behavior, or numbered vertical changes.
At that fifth checkpoint, the ledger was 21 numbered production verticals plus
five unnumbered checkpoints. The API comparison remained 558 records with the
same hash and had 15 Phase 6 owners, 32 provisional owners, and a 219-owner
reviewed union. The
23/23-event, 22-family, 22-getter/23-builder, and 31/12-canary surfaces remain
unchanged. `SOK-SIM-001` stays COMPLETE BOUNDED PHASE 6 IMPLEMENTATION
EVIDENCE; all other status rows stay unchanged.

This is not the official CLI or an official expected-check multiset replay,
and it opens no live network path. It does not prove listener/kernel behavior,
socket backpressure or write-idle handling, release provenance, sustained
operation, comprehensive privacy/security, or Phase 6 review/freeze. At that
historical checkpoint, the next planned work was scheduled coverage-guided
fuzz and sustained soak/stress gates, followed by structured-log, privacy, and
API review/freeze work; the later Phase 6 freeze is recorded below.

The fieldless request-boundary events and label-free families retain no request,
remote identity, endpoint, method, code, outcome, throwable, header, trace ID,
token, key, tracestate, baggage, or application label. With the eighteenth
vertical, the default collector aggregates the full 23/23 variants across 22 text
families, leaving zero core variants unaggregated. The nonsubscription
16-request gate remains exactly 31 MCP-prefixed samples before reset and 12
after reset because the final two map families are sparse on that clean path.

The rest of the resolved contract defines bounded scalar, live-gauge,
endpoint/method/outcome/reason/code map, and duration-histogram families, with
no standalone start/finish/open/close counters and no unknown-header identity.
Configured scalars render zero; maps/histograms are sparse; reset preserves
five live gauges while clearing cumulative state. The authoritative Phase
6/V10 mapping is now implemented downstream without changing this core
surface.
At that metric/downstream checkpoint, `SOK-TRACE-005` was PARTIAL for metric-
only evidence; `SOK-PRIV-001`, `SOK-METRIC-001`, and `SOK-METRIC-004` were
PARTIAL; `SOK-METRIC-002`, `SOK-METRIC-003`, and `SOK-SHUT-002` were COMPLETE;
and `SOK-TRACE-004` was PLANNED. `AMB-003` was RESOLVED CONTRACT 2026-08-10 /
CORE IMPLEMENTATION COMPLETE / DOWNSTREAM METRIC IMPLEMENTATION COMPLETE, and
`MCP-BASE-026` was COMPLETE. That checkpoint did not constrain custom
collectors or application telemetry, promise an atomic cross-field snapshot
during active concurrent mutation, add structured-log or raw-ID emission,
complete privacy/cardinality work, or prove every parameter/error variant,
sustained operation, release readiness, review, or Phase 6 freeze.

The 2026-08-15 structured trace-log closeout now implements the remaining core
trace-log slice without adding a generic public field bag. With correlation
enabled, an admitted request carrying valid MCP trace metadata captures one
immutable pseudonymous `(keyId, token)` pair. When that token or an independently
opted-in raw validated trace ID is available, Soklet attempts exactly one
`MCP_TRACE_CORRELATION` event at the request's exactly-once finish authority.
With both controls at their defaults no event is emitted; absent, invalid, all-
zero, and HTTP-only contexts also emit none. Rotation preserves an in-flight
request's captured pair. Raw logging never enables correlation and never
carries the full `traceparent`, parent/span ID, flags, `tracestate`, or
`baggage`.

The event message uses one frozen delimiter-safe ASCII grammar with exact field
order and a 184-character maximum. Throwable, request, resource-method, and
marshaled-response attachments are absent; observer failure is contained; and
no token or raw ID enters metric dimensions, exception messages, or
conformance artifacts. Focused carrier, live request-observation, and public
observability tests pass 34/34. The only public API change is the compatible
Phase 4 enum field appended after every existing constant, so existing ordinals
are preserved and the incompatibility set at that amendment checkpoint remains
559 records.

These are FIFO record/enqueue-order guarantees, not a universal cross-thread
causal total order. Default aggregation now covers `ServerStarted`,
`ServerStopped`, `RequestAccepted`, `RequestRejected`, `RequestStarted`,
`RequestFinished`, `RequestStreamOpened`, `RequestStreamClosed`, the five
handler variants, `SubscriptionOpened`, `SubscriptionClosed`,
`CancelationSignaled`, `ProgressEmitted`, `KeepAliveEmitted`, `ProtocolError`,
`UnknownMirroredHeader`, and the transport trio. Broader privacy/redaction and
sustained-cardinality evidence, coverage-guided and sustained fuzz gates, and
release-candidate work remain open. Structured trace-log carrier/emission and
the raw-ID opt-in are implemented as described above, and Phase 6 review/freeze
is complete as recorded below. The seventh
through ninth verticals added no public API, snapshot field, aggregate family,
label, event variant, or wire dimension. The tenth added three provisional
getter/builder pairs, the eleventh adds one, the twelfth adds two, the
thirteenth adds three plus `RequestOutcomeKey`, the fourteenth adds two plus
`RequestStreamTerminationKey`, the fifteenth adds two plus
`SubscriptionTerminationKey`, and the sixteenth adds two plus
`EndpointMethodKey`; the seventeenth adds one provisional getter/builder pair;
and the eighteenth adds two provisional map getter/builder pairs.
The nineteenth downstream vertical adds one `soklet-otel` callback while
changing no core event variant, snapshot member, owner, label, or wire
dimension.
The twentieth downstream vertical adds five declared methods relative to V19
while likewise changing no core inventory.
The twenty-first adds seven top-level simulation types,
`McpSimulationOptions.Builder`, and two abstract `Simulator` methods while
leaving the metric/snapshot/canary inventories unchanged.

Phase 6 was frozen on 2026-08-14 after the localization program's L8 review.
`frozen-phases` now lists the contiguous sorted prefix `4`, `5`, `6`, and
`phase-6.signatures.jsonl` is verified bidirectionally on every run alongside
the Phase 4 and Phase 5 snapshots. The 2026-08-15 telemetry amendment moved all
32 former provisional owners into frozen Phase 6, leaving the provisional
inventory empty; the same day's trace-log amendment changed only the one
Phase 4 enum field described above.

## Running the gates

Run the aggregate compatibility, ownership, and freeze gate with:

```sh
scripts/verify-mcp-api-freezes.sh
```

The aggregate first runs `scripts/api-diff/verify.sh`, verifies the exact owner
union from the full report, and then regenerates and bidirectionally compares
the signature snapshot for every phase named by `frozen-phases`. Generated
evidence is written under `target/japicmp/` and
`target/mcp-api-freezes/`. Neither script updates a reviewed file.

CI runs the aggregate on JDK 17; the scripts themselves use the
caller-selected JDK. On the exact current source, the aggregate gate covers
621 reviewed incompatibilities across 271 owners: 233 MCP and 38 non-MCP.
The provisional inventory is empty; `EndpointMethodKey`, `RequestOutcomeKey`,
`RequestStreamTerminationKey`, and `SubscriptionTerminationKey` are now frozen
Phase 6 owners. The amended frozen inventories contain 1,058 Phase 4, 189
Phase 5, and 423 Phase 6 signatures. Phase 4 contains 133 classes, one
constructor, 79 fields, and 845 methods, with SHA-256
`41c717baa9353bfe794601f9ee5da1ebf5e3317afb9a656343683287da88290c`
and exact nullability digest
`7f5fe43e23b6da1cc3f18d431e9a4576aa57cad8ac83a7fae050a249e9e9d04f`.
Phase 5 contains 36 classes, zero constructors, 19 fields, and 134 methods,
with SHA-256
`0e3e2b7f9a644f28bed2215c652f2c25e2eaff9a171983ed058ee90fc0e617ed`
and exact nullability digest
`682eb068e722f49fca8329d39994bee747a98f1e93d9812d4186e341cf0356a7`.
Phase 6 contains 64 classes, zero constructors, 41 fields, and 318 methods,
with SHA-256
`991ebeeacc476ef06a127db5127da421b79900dbd3d3c405d2886776ffa671f7`
and exact nullability digest
`3df4ec35547cde4f6ad5a2816824bfcd65a5c8145aa50f07ab1857b6c17c7b60`.
The sole public constructor in the frozen surface is the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor; all non-throwable values
are constructed through factories or builders.

The L1-exit checkpoint tree passed a clean Corretto 26 verify at 1,557/0/0/4
over 456 main and 179 test sources. The exact 2026-08-13 L2 framework-catalog
completed-L2 localization tree (request-scoped context creation for framework
catalogs, all four handler families, and subscription terminal pre-render;
fail-atomic rendering for all five framework catalogs; no new public
`com.soklet` types; freeze gate unchanged) passed 1,620/0/0/4 over 462 main
and 186 test sources, and the L3 HTTP/cache-boundary tree (private/zero
clamping, `Content-Language`, the non-preflight `Vary` merge, and
application-boundary proofs) passed 1,624/0/0/4 over 462 main and 187 test
sources, and the L4 MRTR-continuity tree (version-2 `selectedLocale` request
state with exact `20 + N` accounting, byte-identical version-1 emission when
localization is absent, retry-time exact-locale enforcement, and
cross-instance continuity; operational note: deploy version-2 readers
everywhere before enabling any localized emitter, since a
localization-disabled reader fails sanitized rather than downgrading) passes
1,635/0/0/4 over 462 main and 189 test sources, and the L5 reload-control
tree (composed framework/application catalog-change delivery, truthful
per-family list-change advertisement, stale pre-render release, node-local
generation-fenced `invalidateCatalogs()`) passed 1,641/0/0/4 over 463 main and
190 test sources, and the L6-core tree (localization-primitive fuzzing, full
preference corpora, provider inertness for non-admitted work, zero-cardinality
tag flood, and selection-versus-invalidation races) passes 1,647/0/0/4 after
compiling 463 main and 191 test sources, alongside 139 fuzz-module seed tests.
The JDK 21
Error Prone/NullAway profile passes with the existing advisory-warning
inventory, and SpotBugs reports zero bugs and zero errors. The 181-source
Java-17 API sketch and Javadoc/doclint smoke pass. At that
checkpoint, exact-tree supported-JDK CI, the remaining L6 soak and fleet legs,
public Javadocs, and L8 freeze/release evidence were still open. Phase 6 has
since frozen, the localization soak and nightly fuzz selectors are wired, and
`verification/localization/verify.sh` now compiles and runs a library-neutral
generic provider against the packaged jar alone and enforces zero Soklet
runtime dependencies. Translation-library adapters remain application-owned
documentation examples rather than a Soklet verification surface. The later
`McpLocalizationFleetPublicRuntimeTests` fixture covers failed reload, rolling
revision drift without within-response mixing, node loss, fresh-context
subscription reconnect, node-local delivery, and cleanup through two
simultaneous real loopback listeners. The format-v2 release contract now
enumerates exactly 26 ordered gates. At the initial JDK 21 gate checkpoint, a
same-version macOS arm64 Corretto 21.0.12.9.1 run passed the full core
`clean test` at 1,681/0/0/4; static analysis reports `BUILD SUCCESS` with the
existing advisory inventory after the `SelfAssignment` fix, and SpotBugs
reports zero bugs and errors. The exact
checksum-pinned Corretto 21.0.12.9.1 toolchain now drives `core-jdk-21`,
`static-analysis`, and `spotbugs`. Twenty gates are dispatch-configured
with executable `READY` paths, and none remain `BLOCKED_HARNESS_MISSING`; the
six downstreams remain `BLOCKED_UNCOMMITTED_LOCAL_MIGRATION`, leaving six
fail-closed blockers. `READY` means configured, never passed. The matrix-closure
hook is `READY`, and the candidate-contained registry and residual evidence
produce a canonical `PASSED` report at 113 `CORE_COMPLETE`, 119
`RELEASE_GATED`, 12 `APPLICATION_OWNED`, 19 `NOT_APPLICABLE`, and zero
`UNRESOLVED`. Only the exact candidate workflow can record its typed PASS
receipt. Release-soak evidence, release scans, benchmarks, published
downstream pins, and immutable release-candidate provenance and conformance
remain open. Scheduled fuzz, nightly soak, and operational histories remain
advisory post-release monitoring rather than release prerequisites. Candidate Javadoc
generation/completeness is configured; public deployment is post-validation
publication work. The bounded two-listener fixture is the Soklet-owned fleet
gate, while production multi-host coordination remains application/deployment-
owned. ToyStore's local migration passes
14/14, including six
MCP tests. Its per-request credential proof accepts a valid request, then
returns 401 for malformed, missing, expired, and wrong-audience credentials and
403 for an insufficient-scope credential, proving that prior identity and
authorization are never inherited. Its reviewed committed pin and immutable-
candidate/JDK-25 proof remain a required fail-closed 4.0.0 downstream release
gate.

At the V21 boundary, the focused simulator/API gate passed 46/0/0/0 and the
broadened adjacent authority selector passed 215/0/0/0. Clean exact-source
suites on Corretto
21.0.11 and 26.0.1 each pass 1,528/0/0/4 across 165 suites, compiling 440 main
and 175 test Java sources. Enforced static analysis is green with existing
advisory diagnostics; SpotBugs reports 0/0. Candidate main, sources, and
Javadoc JARs plus standalone Javadoc are green using offline-link resolution.
All 167 API-sketch sources compile for Java 17 and pass Javadoc doclint on JDK
26. All 104 files from pinned JSON Schema commit
`0c7b65dc16dd8eaa7bd83e21099c76610c3b246a` validate.

The V20 downstream focus at 23/0/0/0 and full `soklet-otel` suite at 36/0/0/0
on each JDK were carried forward and not rerun for V21. The prior focused fuzz
gate remains 28/0/0/0 and deterministic full corpus replay remains 127/0/0/0
on both JDKs; neither was rerun. Prior benchmark-module evidence was also not
rerun for V21.

The conformance runner/infrastructure self-tests and scenario/supplement-
manifest gates are green. The manifest now binds 48 production-derived golden
messages; after the four-message Phase 3 unknown-method and Phase 5 missing-
capability addition, the current five-message addition supplies a Phase 3
rate-limited tool request/error pair and rate-limited notification plus a Phase
4 strict-unknown-header request/error pair. The focused live golden-wire suite
passes 11 tests with no failure, error, or skip. Final-tag Ajv validation of
the expanded corpus remains with the pinned candidate-conformance path because
its official-suite checkout was not available for this local slice.
The separate clean observation supplied the 16 Phase 5 profile candidates. The
later atomic activation retained all 23 historical IDs, activated all 39 exact
profiles at implementation phase 5, and passed the fresh 39-scenario verify
with 150 exact outcomes, 36 wire successes over 103 messages, all 39 then-
current goldens, empty standard error, and 39 clean exits. Evidence SHA-256 is
`082d841697f472da97a822c4dba35e922378f170a7050eca400b32a3eeaf6fc1`.
It is `CANDIDATE_ARTIFACT_DEVELOPMENT_ONLY` evidence with
`releaseCandidateEvidence: false`, not release sign-off. Final JDK 17 and JDK
25 CI results for this exact tree remain open.

The preceding 2026-08-20 protocol/capability golden reconciliation passed the
focused live golden suite 9/9 on local Corretto 17 and the pinned Corretto 21,
the broader protocol/capability gate 86/86 on Corretto 17, and the runner and
local-simulator self-tests. Full Corretto 17 clean verify passed 1,671/0/0/72
over 462 main and 196 test sources and built the main, sources, and Javadoc
JARs. These are local snapshot checks, not immutable-candidate evidence.

The previous policy/error reconciliation checkpoint was test- and golden-only.
Its exact slice passes 27/27 on the pinned local Corretto 17 and Corretto 21
toolchains, and the adjacent policy regression set passes 59/59 on each.
`McpFinalTagGoldenWireProductionTests` passes 11/11 on each JDK, and the
manifest now binds 48 production-derived messages. An unsigned Corretto 17
`clean verify` passes 1,677/0/0/72 over 462 main and 197 test sources and
builds the main, sources, and Javadoc JARs. No production behavior, public API,
or Phase 4/5/6 freeze inventory changed. At that checkpoint, the canonical
matrix was deliberately `FAILED`: 95 rows were `CORE_COMPLETE`, 116 were
`RELEASE_GATED`, four were `APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and
29 remained `UNRESOLVED`. Final-tag Ajv validation of the expanded 48-message
corpus had not been rerun locally and remained owned by candidate conformance.
These are local snapshot checks, not immutable-candidate evidence.

The preceding five-row compatibility reconciliation closed the core rows for
admitted identity versus client self-report, unknown client-extension
fallback, Bearer challenge transport, authorization/CORS response-head
behavior, and legacy session/replay-header containment. A real listener keeps
credential-selected identity authoritative despite forged client metadata.
Valid unknown extension settings remain opaque admission input without
inventing or advertising core support; malformed settings fail before
admission. A safe Bearer challenge can carry an absolute `resource_metadata`
URI and operation scopes, but the application owns their meaning and standards
compliance. The independent CORS goldens cover `Authorization`, modern and
registered MCP headers, `WWW-Authenticate` exposure, exact order and
multiplicity, and fail-closed legacy-header rejection.

The focused compatibility slice passed 33/33 on the pinned local Corretto 17
and Corretto 21 toolchains. The separate authorization/CORS HTTP-head manifest
at `conformance/golden-http-head/authorization-cors/manifest.sha256` binds
three raw production response-head fixtures.
`McpAuthorizationIntegrationTests` contains two test methods: one reads and
verifies those goldens, while the other asserts request and notification
challenge semantics. This separate corpus does not alter the final-schema
corpus, which remains 48 JSON messages with 11 focused
golden tests. An unsigned Corretto 17 `clean verify` passed 1,685/0/0/72 over
462 main and 201 test sources and built the main, sources, and Javadoc JARs.
The only production change was an internal policy-response denylist for legacy
MCP session/replay headers; a negative
production-source inventory confines those names to that denylist. Public API,
signatures, and the Phase 4/5/6 freeze inventories were unchanged. At that
checkpoint, the canonical matrix remained deliberately `FAILED`: 100 rows
were `CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were
`APPLICATION_OWNED`, 18 were `NOT_APPLICABLE`, and 24 were `UNRESOLVED`.
Final-tag Ajv validation of the
expanded 48-message corpus was not rerun locally and remains owned by candidate
conformance. These are local snapshot checks, not immutable-candidate evidence.

The preceding four-row HTTP-contract reconciliation closed readable
`initialize` and validated-unsupported-selector rejection diagnostics,
unsupported classified-notification handling, universal MCP HTTP `no-store`,
and exact request/notification validation precedence. Its separate 22-response
complete-HTTP corpus is bound
by `conformance/golden-http-contract/precedence-no-store/manifest.sha256` at
SHA-256
`273e83945e5bae949c4a2eee85993883abb1350ef7234b98548d1134d0f7af02`.
Five contract tests comprise three real-listener goldens, one exhaustive response-authority inventory, and one six-document manifest-digest parity gate;
four diagnostic tests cover the positive post-JSON and negative pre-JSON/
unreadable-method boundary. Those two classes pass 9/9 in the current focused
execution.

Full clean test passes 1,693/0/0/72 and 1,708/0/0/4, respectively, over 462
main and 203 test sources; the JDK 21 total includes 15 extra virtual-thread
containment cases. A subsequent local Corretto 17 package validation built the
main, sources, and Javadoc JARs after allowing configured external Javadoc
links. This corpus is separate from the unchanged official 48-message/11-test
and auth/CORS three-head/two-test corpora. The narrow internal change preserves
readable `initialize` after strict JSON and adds a bounded diagnostic only after unsupported-selector form and membership validation; it implements no
handshake or session. Public API and freeze inventories remain unchanged. At
that checkpoint, the matrix remained deliberately `FAILED`: 104 rows were
`CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18
were `NOT_APPLICABLE`, and 20 remained `UNRESOLVED`. These are local snapshot results, not immutable-candidate
evidence or results from the release-pinned Corretto 21.0.12.9.1 toolchain.

The subsequent 2026-08-21 core-result/error closure binds two independent
production corpora. The 25-fixture core result-envelope manifest at
`conformance/golden-result-envelope/live/manifest.sha256` has SHA-256
`d2eaa03c24927d45ef350b187624f50448d78a6531a26dedbbe07ee327b91b14`.
Four live tests and a checksum/source-authority inventory exhaust Soklet 3.6's
core `complete` and `input_required` JSON/SSE envelope authorities; extension
result types remain separately bounded by `MCP-BASE-006`. The twelve-fixture
canonical complete-HTTP error manifest at
`conformance/golden-error-mapping/live/manifest.sha256` has SHA-256
`bfaecadaba283df430026504b94f71640c0c56a830159100f9be9179a7ce4e2d`.
Two live-listener tests cover the eight frozen ordinary mapping families,
including both required and conditional `-32021`; readable-`initialize` and
path-specific error evidence remain explicit supplements. Five deterministic
tests freeze both progress/error enqueue orders and the mapped-error/
cancellation boundaries, including late pre-body cancellation only after a
nonstream mapped response owns its terminal.

The combined focused suite passes 21/21 and the adjacent group passes 195/195
on pinned Corretto 17.0.20.1 and local Corretto 21.0.11. Full clean test passes
1,704/0/0/72 and 1,719/0/0/4 over 462 main and 205 test sources; Corretto 17
package validation builds the main, sources, and Javadoc JARs. API diff/parser/
freezes remain green at 565 reviewed incompatibilities and unchanged Phase
4/5/6 signature counts 1,048/179/422. No production behavior, public API,
freeze, or version changes; the sole production-source diff is a package-
private no-op test hook at the existing-stream enqueue boundary. At that
checkpoint, the matrix remained deliberately `FAILED`: 106 rows were
`CORE_COMPLETE`, 116 were `RELEASE_GATED`, four were `APPLICATION_OWNED`, 18
were `NOT_APPLICABLE`, and 18 remained `UNRESOLVED`. These are local snapshot
results, not immutable-candidate evidence or results from the release-pinned
Corretto 21.0.12.9.1 toolchain.

The subsequent application-semantic closure adds two public-API-only examples:
the [durable-handle and secured-prompt patterns](../../src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
and [resource, URI, filesystem, and cursor patterns](../../src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java).
Their eight executable tests prove the documented application boundary without
adding behavior or public signatures: Soklet does not provide the durable
repository, prompt semantic policy, canonical filesystem mapper, delivery-
intent URI allowlist, cursor integrity key, or retained distributed snapshot
store. Public Javadocs now document these existing application-owned
boundaries; frozen inventories are unchanged. The evidence moves
`MCP-BASE-015`, `MCP-PROMPT-006`, `MCP-RESOURCE-006/007`, and
`MCP-PAGE-004/007` to `APPLICATION_OWNED`; distributed portable-cursor evidence
remains open. Focused owner evidence on Amazon Corretto 17.0.20.1+10-LTS is two
separate 4/4 class runs (eight tests total); the direct combined suite is 8/8
on local Amazon Corretto 21.0.11.10.1 (OpenJDK 21.0.11+10-LTS). The adjacent
12-class suite passes 66/66 on each JDK. Full `mvn -B -ntp clean test` passes
1,712 tests with zero failures, zero errors, and 72 skips on Corretto 17, and
1,727 tests with zero failures, zero errors, and four skips on local Corretto
21; both compile 462 main and 207 test sources. At that application-pattern
checkpoint, the matrix remained deliberately `FAILED`: 106 rows were
`CORE_COMPLETE`, 116 were `RELEASE_GATED`, 10 were `APPLICATION_OWNED`, 18
were `NOT_APPLICABLE`, and 12 remained `UNRESOLVED`.

The subsequent conditional-capability proxy closure adds the
[real loopback intermediary fixture](../../src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java).
Its single test drives a two-leg socket proxy with a manual monotonic idle
clock. With conditional support absent, the proxy observes zero backend and
client-visible response bytes before expiring at its exact configured boundary;
the reset produces one exact client-disconnect outcome and one cooperative
cancelation. The handler remains accounted until explicitly released, and its
late result emits no bytes. With support present, the same proxy forwards the
SSE head, progress notification, and terminal result byte-for-byte. The
focused/adjacent gate passes 33/33 on local Amazon Corretto
17.0.20.1+10-LTS and local Amazon Corretto 21.0.11.10.1 (OpenJDK
21.0.11+10-LTS). Full `mvn -B -ntp clean test` passes 1,713 tests with zero
failures, zero errors, and 72 skips on Corretto 17, and 1,728 tests with zero
failures, zero errors, and four skips on local Corretto 21; both compile 462
main and 208 test sources. A narrow internal production
fix preserves an outer cancel transition's exact observation reason and cause
instead of publishing a generic cancelation fallback. Public API, signatures,
freeze inventories, and the version are unchanged. This evidence models one
configured loopback intermediary; it does not establish a wall-clock
production timeout, universal proxy behavior, or prompt non-cooperative
application-code exit. At that checkpoint, `MCP-MRTR-011` became
`CORE_COMPLETE`; the matrix remained deliberately `FAILED` at 107
`CORE_COMPLETE`, 116
`RELEASE_GATED`, 10 `APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 11
`UNRESOLVED`. These are local snapshot results, not immutable-candidate
evidence; the Corretto 21 run is not release-pinned.

The subsequent queued-execution winner-election closure adds
[deterministic queue ownership evidence](../../src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java).
One method stages promotion, exact-boundary deadline, and client disconnect,
then enumerates all six total orders with a monotonic manual clock and FIFO
manual executor. Deadline before promotion while the request remains writable
returns the exact queued HTTP 503/JSON-RPC `-32603` response; disconnect writes
nothing; promotion first ends the queued state and follows the separately
provisional active-deadline path. A second cross-layer case holds the exact
observer-deferral gap after the application layer reserves a queued deadline,
then makes the outer request control unwritable by disconnect before response
handoff. It observes zero callback bytes, exactly one `CLIENT_DISCONNECTED`
finish and one dequeue/gauge removal. One deadline-expiration occurrence and
one abandoned response account for the reserved-but-unwritable attempt, not a
second terminal outcome. No queued interceptor or handler runs, cleanup occurs
once per request, and all framework state returns to baseline. The focused
class passes 2/2 on pinned Amazon Corretto 17.0.20.1+10-LTS and local Amazon
Corretto 21.0.11.10.1; the adjacent Corretto 17 execution bundle passes 53/53.
Full `mvn -B -ntp clean test` passes 1,715/0/0/72 on Corretto 17 and
1,730/0/0/4 on local Corretto 21 over 462 main and 209 test sources. This
slice changes no production behavior, public API, signature/freeze inventory,
or version. It closes `SOK-EXEC-005`; the current matrix remains deliberately
`FAILED` at 108 `CORE_COMPLETE`, 116 `RELEASE_GATED`, 10
`APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 10 `UNRESOLVED`. These are
bounded local ordering results, not proof of every scheduler/network
interleaving or immutable-candidate evidence; the Corretto 21 run is not
release-pinned.

The subsequent off-network simulation boundary closure adds deterministic
internal and public evidence with source SHA-256 values
`7ab30148451fbef7e8a8131486cb67989ac133271797502920ef4aa2f1db6bd5` and
`b666ad1bcb6a3bca6e3af46505fe46b7365042b06189cc8daf41d5fb51e05350`.
Off-network capture never arms live write idle; non-drained item/byte limits
preserve retained frames, omit the offender, and remain immutable once-only
simulator outcomes. An unrelated simulation completes while the limited
handler still owns its slot, with balanced accounting and no transport event.
Separate real-listener tests remain the authorities for bounded slow-reader
TCP backpressure and actual response-write-idle closure/interruption. The two
selectors pass 2/0/0/0, both affected classes pass 25/0/0/0, and the adjacent
loopback/simulator bundle passes 26/0/0/0 on pinned Corretto 17 and local
Corretto 21. Full clean test passes 1,717/0/0/72 and 1,732/0/0/4 over 462 main
and 209 test sources. No production behavior, API, freeze inventory, manifest,
or version changes. `SOK-SIM-001` is now `RELEASE_GATED` by the exact seven
named gates; the current matrix is 108 `CORE_COMPLETE`, 117 `RELEASE_GATED`,
10 `APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 9 `UNRESOLVED`, with a
117/117/10/18/0 synthetic all-resolved report. This is deliberate simulator/
live separation, not kernel, TCP, or live write-idle equivalence.

The subsequent localized-cursor fleet application-pattern closure adds
`McpLocalizedCursorFleetApplicationPatternsTests` at final source SHA-256
`10d872127f2a25632137899986ea75cfdfe838eb2d6fbfa395283285b678d567`.
Its two public-API-only methods transfer only the exact opaque cursor between
independently configured simulator nodes; preserve bounded, stable, unique
traversal of a retained snapshot after another node activates a replacement
catalog; bind snapshot/catalog and locale/localization revisions plus expiry,
offset, and authorization; and preserve the same bytes from provider
preselection through full handler authentication. Every exercised invalid
classification produces one fixed no-data application `-32602`/400 error with
zero lifecycle throwables. The selector passes 2/0/0/0, the adjacent six-class
set passes 30/0/0/0, and full clean test passes 1,719/0/0/72 and
1,734/0/0/4 on pinned Corretto 17 and local Corretto 21 over 462 main and 210
test sources. No production behavior, API, freeze inventory, manifest, or
version changes. `MCP-PAGE-006` and `SOK-L10N-007` are now
`APPLICATION_OWNED`; the matrix is 108/117/12/18/7 and the synthetic report
is 115/117/12/18/0. The two-node fixture models application replication; it is
not Soklet-provided storage, key management, replication, affinity, or a
positive cache-TTL claim.

The subsequent `MCP-BASE-011` notification-identifier boundary closure adds
`src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java` at final
source SHA-256
`ce10724e565470bdcd6f005ad3d332ea473698f7c7754c765c3bfc73a8c3a3f5`.
Its two public-API-only methods prove that classified inbound notifications
always have an empty HTTP transport body and bypass application request-
handler and handler-interceptor stages. Malformed JSON that fails before
notification classification is outside this claim. Outbound progress,
subscription-acknowledgment, and list-changed notification frames carry a
method and omit top-level `id`; nested `progressToken`,
`io.modelcontextprotocol/subscriptionId`, and cancellation `requestId`
parameter members remain legitimate. Only the method-free terminal result
retains the initiating request's top-level `id`. Soklet 3.6 registers no
extension-notification handler and exposes no arbitrary extension-notification
handler API. The exact selector passes 2/0/0/0, the adjacent set passes
83/0/0/0 on both JDKs, and full clean test passes 1,721/0/0/72 and
1,736/0/0/4 on pinned Corretto 17 and local Corretto 21 over 462 main and 211
test sources. No production behavior, API, freeze inventory, manifest,
version, or official 48-message/11-test corpus changes. `MCP-BASE-011` is now
`CORE_COMPLETE`; the current report remains `FAILED` at 109 `CORE_COMPLETE`,
117 `RELEASE_GATED`, 12 `APPLICATION_OWNED`, 18 `NOT_APPLICABLE`, and 6
`UNRESOLVED`, while the synthetic all-resolved report is 115/117/12/18/0.
The remaining IDs are `MCP-HTTP-020`, `SOK-VALID-002`, `SOK-STATE-002`,
`SOK-STATE-007`, `SOK-PRIV-001`, and `AMB-002`.

The subsequent 2026-08-22 `MCP-HTTP-020` closure strengthens the existing
public listener fixture at final source SHA-256
`2c3b912484bd96d0f2f73fc4c3b85fdf9760e22d895acf4145b962bd8fc0b303`.
An unannotated `privilege` body property carries `reader` while unknown
`Mcp-Param-Privilege` carries `administrator-canary`; converted and raw
arguments remain exactly body-authoritative, and the response excludes the
canary. Exact fixtures prove name diagnostics are off by default; opt-in
permits ten attempted sanitized-name-only events per server per monotonic
60-second window, truncates at 128 ASCII bytes, and attaches neither values
nor requests. Per-occurrence default aggregation uses registered endpoint and
bounded method only, never header identity, under an independent 8,192-
dimension cap and the same downstream OpenTelemetry shape. Public/manual
metric inputs remain application-controlled.

The focused class passes 6/0/0/0, the adjacent five-class set passes 29/0/0/0,
and full clean test passes 1,721/0/0/72 and 1,736/0/0/4 on pinned Corretto 17
and local Corretto 21 over 462 main and 211 test sources. No production
behavior, public API, freeze inventory, manifest, version, official result, or
official corpus changed. The pinned 40-scenario inventory has no exact
scenario for this policy, so this adds no official-suite claim.
`MCP-HTTP-020` is now `CORE_COMPLETE`; the report is 110/117/12/19/5 and
the remaining IDs are `SOK-VALID-002`, `SOK-STATE-002`, `SOK-STATE-007`,
`SOK-PRIV-001`, and `AMB-002`. Generic `Request`, `Throwable`,
custom-collector, and application-telemetry privacy remain owned by
`SOK-PRIV-001`.

### 2026-08-27 lifecycle cutover

`mcp-public-evolution-inventory.json` classifies every reviewed public MCP
sealed root and enum and records independent MCP and Soklet Java API lifecycle
axes. Its verifier and Javadoc test enforce bidirectional Java annotation/block
tag policy plus the removal of the 18 historical `McpLogLevel`-induced
deprecation suppressions. SEP-2577's protocol deprecation therefore does not
silently become a Java API-removal decision.

The reviewed lifecycle cutover removes the Phase 4 `SokletConfig.Copier` and
Phase 6 `McpShutdownOutcome` owners, leaving the exact Phase 4/5/6/provisional
partition at 132/36/64/0 and the MCP union at 232. The separate non-MCP
allowlist contains the exact 39 lifecycle, result, runner, simulator, and
transport-SPI owners, so current-side inventory verification covers 271
owners in total.

The regenerated Phase 4/5/6 snapshots contain 1,025/179/421 records. Their
class/constructor/field/method breakdowns are 132/1/79/813,
36/0/15/128, and 64/0/42/315, with respective signature SHA-256 values
`89360e07e7813f349b01ae860e19865b62ff35ff40c9bb59c8be4f1fce226658`,
`96f56fc34f81a9302d1387d437bee4caa36e465a07a40a8577eed4bd4313e5e4`,
and
`5e8a4aac651374205e126ca8128ec5ca644b1c7f84ad6426d4462cd9712ff12b`.
Their reflection/nullability SHA-256 values are
`8030cb36ddb7aad8534c601b173e37b99e4b164942f2fd30628f9adde1e35eb3`,
`6569e3b106ae11e1d30da66c045d1a9bc23aa65016f36052df6b19fc320c06d9`,
and
`15f883e66b3194974887899a090e53d33aa27a08db793f4cfd7ff78212b67aaf`.
The exact include SHA-256 values are
`adbdfe675205831336fd0d95f44911b9b4ab94de24bd7ade232805f02b52b785`,
`2009a66e210e89c43e157df0498b357a5e29fc8bc7144ca373ad07c57d1fce2a`,
and
`c14695a4bfea85e88fea713211320b4192db4ca421786ed716dd543d79ded4c5`.

The released-3.5.1 compatibility ledger now contains 617 records with
SHA-256
`302f68448fe14b1cc5ad179c076c5b84b16e81b0b21dca55e0cc5edcbaadea41`.
The aggregate compatibility, ownership, frozen-signature, metadata-builder,
public-evolution, and protocol-profile gate passes against these reviewed
artifacts. A clean local Corretto 26 test run passes 2,082 tests with zero
failures, zero errors, and four intentional skips over 506 main and 249 test
sources. This is local development evidence, not immutable release-candidate
or supported-JDK CI provenance.

### 2026-08-28 pre-G3 public API correction

The final pre-G3 review separates parameter metadata
(`McpToolArgument`) from typed record-component metadata
(`McpToolProperty`), passes the exact invocation-feature carrier directly to
each `McpHandlerInterceptor`, reduces `McpHandlerContinuation` to its one-shot
`proceed()` contract, and adds the immutable validated resource-subscription
URI projection to `McpAdmissionContext`. The processor, runtime schema
frontends, admission pipeline, public reflection/Javadoc contracts,
conformance fixture, guides, and executable API sketch carry the same model.
Annotation icons and tool hints remain deliberately deferred rather than
appearing only in the sketch.

The exact Phase 4/5/6/provisional owner partition is now 133/36/64/0,
or 233 MCP owners; the 39-entry non-MCP allowlist brings full current-side
ownership to 272. The snapshots are 1,029/179/421 records. Phase 4's
class/constructor/field/method breakdown is 133/1/79/816, with signature
SHA-256
`ba976dbbe4d72d2b38a7f167bb88164e321064fea5847cc45f6992220b735e2f`,
reflection/nullability SHA-256
`9cfe146213f1c96cfdd1de6fe05caa58d8055f7abdb491b6141491f2dc8de646`,
and include SHA-256
`fd3293a1089845a3c90c22cda8bd59986b8a975c3cb10211ab3ea8831a7e5021`.
Phase 5 and Phase 6 snapshots are byte-identical to the lifecycle-cutover
versions. The released-3.5.1 compatibility ledger contains 618 records with
SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.
The aggregate gate regenerates and verifies these exact values; their local
success does not approve G3 or create immutable release-candidate evidence.

### 2026-09-01 shutdown-component naming amendment

The unreleased terminal-evidence API now uses the `ShutdownComponent*` family
instead of `LifecycleComponent*`. The enum is
`ShutdownComponentType { HTTP, SSE, MCP, FRAMEWORK }`; `FRAMEWORK` is the
closed framework-owned setup/attachment bucket, not a generic `OTHER` value.
`LifecycleObserver` and `LifecyclePolicy` retain their names because they span
the complete lifecycle. No compatibility aliases were added.

The owner and record counts remain 133/36/64/0 and 1,029/179/421. The Phase 4
signature and reflection/nullability SHA-256 values are respectively
`2b50fb6e08d2b9eccf3a45d4020cbf4738a79517102602e69067f8a198158516`
and
`dc83138dd80f93c003ec527fec24a9fd7e09633417f0f5a6430ac254421797b1`.
The corresponding Phase 6 values are
`69b008b685dead8e1ae66691f0e9955688b9e43740281ea0f82497df22a4dda0`
and
`d829563b135bae5a0e97559ecf5d1a8dd280c4b7792a74a2f10fcf8d8017d18b`.
Phase 5 remains byte-identical. The final application-runner shape has no
application builder: `SokletApplication.fromConfig(...)` returns the
configured one-shot value, whose `run(...)` overloads accept shutdown triggers
and an optional immutable `ShutdownCleanup`. `ShutdownCleanup` exposes
`fromTimeoutAndAction(...)` and `getTimeout()`, while its nested functional
`ShutdownCleanup.Action` owns the synchronous callback contract. Swapping the
discarded `SokletApplication.Builder` owner for `ShutdownCleanup.Action`
changes no MCP phase owner or frozen descriptor and leaves the exact non-MCP
allowlist at 38 entries with SHA-256
`f033df8701ffef4718fa0c62858ee02054910a0698503850670e80eafdddd6d6`,
and the released-3.5.1 compatibility ledger remains 618 records with SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.

A compatible addition to a frozen owner requires deliberate review, a
snapshot update, and an update to the freeze rationale. An incompatible change
requires an explicit compatibility plan and version decision. No generated
snapshot is accepted automatically, and no Git commit identifier is treated as
the compatibility baseline.

### 2026-09-03 Revision 2 construction and collection amendment

The owner-approved Revision 2 pass makes MCP construction entrypoints complete,
adopts one explicit `addX`/`addXs` grammar for collection appenders, and applies
null-as-clear/default only where a legitimate optional or documented default
state exists. In particular, server, endpoint, resource-output,
input-required-result, localizer, localization-context, and subscription
construction now begins with the required values identified by the API;
`McpTokenBucketConfig.withCapacity(...)` is immediately buildable with the
documented 60-token/one-minute refill defaults; and `refillPeriod` is now
`refillInterval`.

The Phase 6 nested `McpLocalizer.ContextProviderStage` owner is replaced by the
top-level, thread-safe functional `McpLocalizationLookup` owner. No compatibility
aliases remain for the incomplete entrypoints or renamed appenders because all
of these surfaces are unreleased. The sole new released-3.5.1 incompatibility in
this amendment is removal of the old one-argument `McpServer.withPort(Integer)`
descriptor; the reviewed compatibility ledger therefore contains 622 records
with SHA-256
`c83e4e13f40b8c1773aac64d0fc2b4854879391ab322438187a6f3807cbbf2b8`.

At that checkpoint, the Phase 4/5/6 snapshots contained 1,034/181/422 records with signature
SHA-256 values
`54e5bbc1c2649c2964dab253b413b21525289f063621af3cf5a8a22ad9b55ed1`,
`1bd7282469dd7aa41d2aa79a926f2a929518d421c4b2ac8a61ea8b97cdb27ffa`,
and
`afe117cb8580b0e1cb9270dc881107c8311f692b60714f803bf23d7685124ccb`.
Their exact reflection/nullability SHA-256 values are
`4ed20d1503fed9bc85085152a550039115887fada67b2fa44b4e791f194e15c8`,
`e33c1f2b4f53603d359b04d76ea90a8286954c642e6f125cb01b3eb3f0b3bec8`,
and
`7524969d683aafbc04c3eabffad6769fa5005e5d33255c8acbb2101650acf023`.
The exact 133/36/64/0 owner partition remains unchanged; the Phase 6 include
inventory changes only by the one-for-one owner replacement and has SHA-256
`9103918bad58c5b6d6d41384803518876f3dfc6ae7363bf3d9084e3fab37f139`.

The aggregate compatibility, ownership, signature, metadata-builder,
public-evolution, transport-dependency, profile-evidence, and roadmap-readiness
gate passes against these reviewed artifacts. The focused reflection contract
passes 23 tests, including exact factory parameter names, JSpecify nullability,
and removal of the superseded entrypoints. These are local development checks,
not release-candidate or supported-JDK CI provenance.

### 2026-09-03 application-wide instance-provider amendment

Generated MCP endpoint discovery is now provider-neutral. The owning
`SokletConfig#getInstanceProvider()` is consulted at each generated tool,
prompt, exact-resource, template-resource, or resource-list handler invocation,
so one application configuration governs all annotation-created handler
instances. Catalog discovery and framework-owned static listing do not create
endpoint instances; a custom annotated resource-list handler is an invocation
and therefore does. The provider is neither exposed through `McpRequestContext`
nor captured by a shareable endpoint registry. Direct invocation of a generated
registration handler outside a managed request retains the prior
default-provider behavior.

The two provider-taking `McpEndpointRegistry` factory overloads are removed as
unreleased, redundant configuration roots. The owner partition and the
released-3.5.1 compatibility ledger remain unchanged. The generated endpoint
index advances from format 3 to format 4 so stale providers with the former
binary method descriptor fail closed and require a clean rebuild.

The amended Phase 4 snapshot contains 1,032 records: 133 classes, one
constructor, 79 fields, and 819 methods. Its signature SHA-256 is
`e6d91c184ec45de87f83dc13e452a1232b87f86d8e12e3713c29cbba13549b9b`;
its reflection/nullability SHA-256 is
`13e20c7525698c527e7c253caf2f54df29e5797eccde6bf7aa91aa78832a4063`.
The Phase 5 and Phase 6 snapshots and digests are unchanged.

### 2026-09-03 MCP value-contract amendment

The owner-approved Revision 2 value pass gives the immutable JSON object and
array types structural equality. Object member insertion order is irrelevant;
array element order remains significant. The five `McpContentBlock` variants
now compare all content, annotations, metadata, icons, and embedded resource
contents structurally, including content-based comparison and hashing for image,
audio, and embedded-blob bytes. No content-revealing `toString()` method was
added.

`McpContentBlock` now exposes the common `getAnnotations()` and `getMetadata()`
properties directly. Targeted construction conveniences add the shared empty
JSON array, numeric array appenders, plain-text prompt-message factories,
lossless resource-descriptor conversion, public component factories for
request-state protection and localization values, and public construction of
client capabilities from JSON. The component factories make application SPI
code unit-testable without widening live-request authority.

Four methods that are present in released 3.5.1 are restored by this amendment:
the two plain-text prompt-message factories and `McpTextContent` structural
`equals()`/`hashCode()`. The compatibility ledger therefore decreases to 618
records with SHA-256
`5846923de47c75e2ac5b926f4efdfbcf78f8d88beab1d1f1095bf62d09804114`.

The amended Phase 4 snapshot contains 1,056 records: 133 classes, one
constructor, 79 fields, and 843 methods. Its signature SHA-256 is
`b330247a5c4b744d4516bbd2b891af17ecd689fa747592fe76f535210cb04462`;
its reflection/nullability SHA-256 is
`713f560dd52778e4389e9b8fd23d0aa6b796515fcc8df80fcd3d2c0451a53951`.
Phase 5 contains 182 records, with signature SHA-256
`2a0ee1e0c68a6d0776a6f4d4afe6c2d105e66770ba351afefd0f1d510cc25a15`
and reflection/nullability SHA-256
`79d9a62b4cbe482621bcc0eeaa9b9dd08908ebde899512dbdb7e49134836edbf`.
Phase 6 contains 424 records, with signature SHA-256
`09d69ee536b2408917836ab570b28c975f937c81ddc89c3ca94ab2118a4742ae`
and reflection/nullability SHA-256
`01eba9130dd61536076431c633833dc66b41f5ec605870cfb6f3a29a183db930`.
The 133/36/64/0 owner partition and all phase include inventories remain
unchanged.

The focused value and reflection contracts and aggregate API-freeze gate are
the local development checks for this amendment. They do not establish
release-candidate provenance or publication evidence.

### 2026-09-03 invocation and typed-input declaration amendment

The owner-approved Revision 2 invocation pass adds direct built-in accessors
for the always-present cooperative cancelation token and optional progress
reporter. `McpInvocationFeatures.find(...)` and `require(...)` remain the
extension mechanism, and the new accessors delegate to that exact-class lookup.
The three multi-round-trip data accessors on framework-owned
`McpRequestContext` are now abstract so an incomplete implementation cannot
silently discard retry responses or protected request state.

`McpInputRequestType` replaces raw annotation method/capability pairing with
four typed choices. Each choice derives its JSON-RPC method and base client
capability; only `SAMPLING_CONTEXT` and `SAMPLING_TOOLS` may be added to a
sampling declaration. The annotation processor rejects invalid and duplicate
combinations, emits declarations and request-state mode for generated tools,
prompts, and resources, and uses the operation-result tool path whenever an
annotated tool returns `McpOperationResult`. Generated schema verification
therefore binds the input schema while explicitly and fail-closedly recording
that this flexible result path has no single output schema.

The Phase 4 snapshot now contains 1,058 records: 133 classes, one constructor,
79 fields, and 845 methods. Its signature and reflection/nullability SHA-256
values are
`46f03620674c6312fd097cba643b5eeff1a11830df74393137ac85787e8552e0`
and
`f5819565d29698091e76af24ec02023aa15a4d17dc1f53cd9f56061d466a199b`.
The Phase 4 include inventory is unchanged.

Phase 5 now contains 190 records: 37 classes, no public constructors, 19
fields, and 134 methods. Its signature, reflection/nullability, and include
SHA-256 values are respectively
`54a96f16d32096b4a4a68a29f727443853178e5da1f0dadacce2004cca70d420`,
`5c90b20e8b582931ca636d91ccf11c9fdc92734289bdad9b27eb9a529645db7f`,
and
`97e1796b3972136dcba44dcd978e47df15ab8351138d080c1d52f8df58ae29f7`.
Phase 6 remains byte-identical at 424 records. The owner partition is now
133/37/64/0, for 234 MCP owners and 272 reviewed current-side owners.

Making the three request-context methods abstract adds exactly three
source-incompatible, binary-compatible interface-method records relative to
released 3.5.1. The shared compatibility ledger therefore contains 621
records with SHA-256
`25c842a78adc9217d13d8c6a68a8aec996026923ba81fe9dded7234298098964`.
The focused processor/runtime and reflection contracts and aggregate API gate
are local development checks, not release-candidate or publication evidence.

### 2026-09-03 focused naming and surface amendment

The owner-approved Revision 2 naming pass hard-renames the unreleased API to
use property-aligned endpoint, subscription, resource-size, cache, protection,
localization, diagnostics, metrics, and annotation vocabulary. Keyring is one
word throughout Java identifiers and current prose. Tool, prompt, and resource
construction now names incomplete types as stages; typed tool construction
selects argument and output types explicitly. No deprecated aliases preserve
the superseded pre-release names.

The built-in process-local subscription publisher is now obtained through
`McpSubscriptionEventPublisher.fromInMemoryDefaults()`. Its concrete
implementation is package-private, reducing the Phase 5 owner inventory by one
while leaving custom distributed implementations on the same public SPI. The
trace-correlation fingerprint's implementation-version constant is no longer
public. The protection fingerprint domain-separation bytes remain unchanged so
this source-level naming amendment does not alter stored fingerprint values.

The amended frozen snapshots contain 1,058/189/423 records across
133/36/64 owners. Their signature SHA-256 values are respectively
`41c717baa9353bfe794601f9ee5da1ebf5e3317afb9a656343683287da88290c`,
`0e3e2b7f9a644f28bed2215c652f2c25e2eaff9a171983ed058ee90fc0e617ed`,
and
`991ebeeacc476ef06a127db5127da421b79900dbd3d3c405d2886776ffa671f7`.
Their reflection/nullability SHA-256 values are respectively
`7f5fe43e23b6da1cc3f18d431e9a4576aa57cad8ac83a7fae050a249e9e9d04f`,
`682eb068e722f49fca8329d39994bee747a98f1e93d9812d4186e341cf0356a7`,
and
`3df4ec35547cde4f6ad5a2816824bfcd65a5c8145aa50f07ab1857b6c17c7b60`.
The complete owner partition is 133/36/64/0, or 233 MCP owners and 271
reviewed current-side owners. The released-3.5.1 compatibility ledger remains
621 records and has SHA-256
`38356e712db3eb747e9b525a8f2645a95ea59c50fa8de25dcfb4c21e79dc3e2e`.

The focused reflection/Javadoc contracts and aggregate API-freeze gate passed
against this development tree. They remain local development checks rather
than release-candidate provenance or publication evidence.
