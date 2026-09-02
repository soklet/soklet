# MCP Phase 4 API-freeze rationale

Date: 2026-08-06

Post-freeze correction reviewed: 2026-08-07

Localization host amendment reviewed: 2026-08-12

Structured trace-log amendment reviewed: 2026-08-15

Rate-limit decision factory naming amendment reviewed: 2026-08-15

Admission-controller naming amendment reviewed: 2026-08-16

Greenfield cohesion naming amendment reviewed: 2026-08-17

Greenfield localization-result simplification amendment reviewed: 2026-08-17

Greenfield localization-context builder amendment reviewed: 2026-08-17

Final greenfield API polish amendment reviewed: 2026-08-17

Greenfield public-record elimination amendment reviewed: 2026-08-18
Greenfield typed-request-state amendment reviewed: 2026-08-18
Reserved application-metadata behavioral amendment reviewed: 2026-08-24; pre-G3 public API correction reviewed: 2026-08-28

This record approves the Phase 4 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a scoped API decision; it is
not a Phase 5/6 implementation, full conformance, or release-candidate claim.

## Compatibility and report model

At the 2026-08-07 wrapper correction, the reviewed incompatibility set
contained exactly 556 canonical symbols and had SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
After the later Phase 5/6 additions, localization and trace-log host
amendments, the naming reviews through the final greenfield polish, and the
greenfield public-record elimination and typed-request-state amendments, the
current reviewed set contains exactly 618 canonical symbols and has SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.
The current lifecycle and pre-G3 API corrections are included in that set.
`target/japicmp/mcp-api-diff.xml` is the modified-only report used to derive
that set. It deliberately omits compatible unchanged/restored containers.

`target/japicmp/mcp-api-freeze.xml` is the matching full report. Ownership and
selected-signature discovery use the full report so an MCP type whose JVM
surface was restored identically to 3.5.1 cannot disappear merely because it
is absent from modified-only output. The aggregate verifier first proves that
the reports have the same baseline and current archives, then applies their
separate roles.

The exact reviewed owner universe at the original Phase 4 review was:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- six Phase 6 owners;
- 28 provisional owners; and
- 206 owners in total.

At that original review, `Simulator` was conceptually assigned to Phase 6 but
was not yet in the then-current inventory because no MCP descriptor had landed
on that shared host. It was later added to and frozen in Phase 6 when its MCP
simulation descriptors landed.

After the telemetry, greenfield, lifecycle, and pre-G3 API amendments, the
current exact MCP owner partition is 133 Phase 4, 36 Phase 5, 64 Phase 6, zero
provisional, and 233 total; the exact non-MCP allowlist adds 38 owners for 271
current-side owners. The cohesion naming amendment was one-for-one; result and
context changes adjusted Phase 6; record conversion did not alter ownership.
The typed-state amendment removed three Phase 5 carrier owners. The lifecycle
cutover removed one Phase 4 and one Phase 6 owner; the annotation split then
added one Phase 4 owner. The current partition and exact transitions are
recorded in the lifecycle and pre-G3 correction sections below, with no
compatibility alias retained.

## Frozen Phase 4 snapshot

`phase-4.signatures.jsonl` contains exactly 1,029 canonical records:

- 133 classes;
- one constructor;
- 79 fields; and
- 816 methods.

The reviewed file's SHA-256 is
`2b50fb6e08d2b9eccf3a45d4020cbf4738a79517102602e69067f8a198158516`.
The independent reflection contract freezes the Phase 4 JSpecify type-use
layout with SHA-256
`dc83138dd80f93c003ec527fec24a9fd7e09633417f0f5a6430ac254421797b1`.
The 133-entry `phase-4.includes` inventory has SHA-256
`fd3293a1089845a3c90c22cda8bd59986b8a975c3cb10211ab3ea8831a7e5021`.

### Post-freeze wrapper correction

The pre-freeze Phase 5 wrapper audit exposed one cross-cutting Soklet convention
that the initial freeze had not applied consistently: exported API should use
reference wrappers for scalar values, reserving primitives for internal code.
Because the MCP API is still unreleased in 3.6.0, the Phase 4 surface was
deliberately corrected rather than preserving the inconsistency.

Exactly 49 Phase 4 signatures changed from primitive scalars to non-null
reference wrappers. At that correction, the regenerated snapshot still
contained the same 1,049 records with the same class, constructor, field, and
method counts. Five of the
49 corrections restore wrapper signatures already present in released 3.5.1,
which reduces the reviewed baseline incompatibility set from 561 to 556
records. Review found no unrelated signature delta.

### Localization host amendment

The 2026-08-12 L1 localization review deliberately reopened three descriptors
on frozen Phase 4 hosts while the new localization-owned types were still
unfrozen in Phase 6:

- compatible default `McpHandlerInvocation.getFeatures()` preserves the
  functional-interface shape and exposes the exact invocation feature carrier
  from the built-in runtime continuation;
- abstract `McpServer.getLocalizationControl()` adds the sole new current
  source incompatibility (`METHOD_ADDED_TO_INTERFACE`, binary compatible and
  source incompatible); and
- concrete `McpServer.Builder.localizer(McpLocalizer)` installs the immutable
  server localization policy.

The generated Phase 4 candidate differs from the previous snapshot by exactly
those three method IDs, with no removal or changed record. It therefore moves
from 1,049 to 1,052 records and from 828 to 831 methods while retaining 133
classes, ten constructors, and 78 fields. The generated incompatibility set
differs by exactly the one abstract interface method. The Phase 5 195-record
snapshot and its nullability digest are unchanged.

### Structured trace-log amendment

The 2026-08-15 trace-log review deliberately adds exactly one compatible
field to the frozen shared `LogEventType` host:

- `MCP_TRACE_CORRELATION` identifies the bounded, machine-readable finish
  record for an admitted MCP request carrying an enabled pseudonymous
  correlation token or separately opted-in raw validated trace ID.

The new constant is appended after every existing enum constant, preserving
all pre-amendment ordinals. Its public Javadoc freezes the exact delimiter-safe
ASCII message grammar, field order, value alphabets, 184-character maximum,
independent raw-ID opt-in, and empty throwable/request/resource-method/
marshaled-response attachments. The amendment deliberately does not add a
generic structured-field bag to `LogEvent` or change any other public
descriptor.

The generated candidate differs from the localization-amended snapshot by
exactly
`F:com/soklet/LogEventType#MCP_TRACE_CORRELATION:Lcom/soklet/LogEventType;`.
It therefore moves from 1,052 to 1,053 records and from 78 to 79 fields while
retaining 133 classes, ten constructors, and 831 methods. Because the enum
field is a compatible addition, the incompatibility set at this amendment
checkpoint remains 559 records with SHA-256
`c0c4b4c68d93e77500b4ffeae07d1cb0bea46bf858c917ef44bbaa6adb61fee4`.
Because the reflection contract includes public enum fields, the Phase 4
JSpecify layout digest moves from
`627be93f6c759e194645c022ab854c2fde73d916b4c787f05e7c18b49cbfb197`
to
`1a2c745038a6cc51c3175b42ca20f39eeca7e8f5ea82912d387f17a92fef0cad`.
The Phase 5 and Phase 6 snapshots and their nullability digests are unchanged.

### Rate-limit decision factory naming amendment

The 2026-08-15 API naming review deliberately renames the two static factories
on the still-unreleased `McpRateLimitDecision` surface:

- `fromAllowed()` becomes `allowed()`; and
- `fromDenied(Duration)` becomes `denied(Duration)`.

The old names are not retained as aliases. A decision describes one acquisition
result, so the concise factories name the result directly; no duplicative
always-allowing `McpRateLimiter` singleton is added. The generated candidate
diff contains exactly those two removed method IDs and their two renamed
replacements. Record and component counts remain 1,053 total, 133 classes, ten
constructors, 79 fields, and 831 methods. The Phase 4 signature SHA-256 advances
from
`d7e9d0c303897e898eab8c485d850caa0484c74ef8b1097be0b78904f1f0c9a3`
to
`47129993cc61be86801e13aafd9ffeb9289b23c4e584706c4b8ecae0992f6877`.
Because the reflection digest includes method identities, its SHA-256 advances
from
`1a2c745038a6cc51c3175b42ca20f39eeca7e8f5ea82912d387f17a92fef0cad`
to
`425a40989aaa9d7cb40ce362a9981e1d59f61a9fb1290d7cb411cc8e9edfd294`;
the nullness annotations and types themselves do not change. At that amendment
checkpoint, the comparison against released 3.5.1, owner inventories, Phase 5
snapshot, and Phase 6 snapshot were unchanged.

### Admission-controller naming amendment

The 2026-08-16 greenfield naming review renames the complete public admission
concept instead of preserving a policy-shaped name inherited from the
replaced MCP API:

- `McpRequestAdmissionPolicy` becomes `McpAdmissionController`;
- `McpServer.getRequestAdmissionPolicy()` becomes
  `McpServer.getAdmissionController()`; and
- `McpServer.Builder.requestAdmissionPolicy(McpRequestAdmissionPolicy)`
  becomes `McpServer.Builder.admissionController(McpAdmissionController)`.

The controller's functional method remains
`admit(McpAdmissionContext)`, and its convenient permissive factory remains
`acceptAllInstance()`. The old type, getter, and builder method are not retained
as aliases: Soklet 3.6 replaces the MCP implementation wholesale, so carrying
parallel pre-3.6 names would add permanent ambiguity without preserving a
supported 3.6 API contract.

The generated Phase 4 candidate replaces exactly five records - the class,
its two methods, the server getter, and the builder method - with their five
controller-named counterparts. The owner inventory remains 133 and the
snapshot remains 1,053 records: 133 classes, ten constructors, 79 fields, and
831 methods. Its SHA-256 advances from
`47129993cc61be86801e13aafd9ffeb9289b23c4e584706c4b8ecae0992f6877`
to
`f76f3bcfea8e9d231c1da292cc309f09336a3a94a9c3a1ca8cd02ca902117890`,
and the identity-sensitive reflection digest advances from
`425a40989aaa9d7cb40ce362a9981e1d59f61a9fb1290d7cb411cc8e9edfd294`
to
`a7a0c227de5e130aa36d5c93da09116bf98d9faba26c15d2ff1b91740776c035`.
Nullness and type-use structure are otherwise unchanged.

Against released 3.5.1, the removal records for the old public class, getter,
and builder method and the incompatible addition of the new abstract server
getter enter the canonical comparison, while the former added-interface-method
record for `McpRequestAdmissionPolicy.admit(...)` disappears with its
container. The net result is three additional records: 562 with SHA-256
`7255791d02be0cf7b0b9e601683a2da008bd41ee3a2e48b2ae8345f8bb8d85cd`.
The Phase 5 and Phase 6 snapshots and their reflection digests are unchanged.

### Greenfield cohesion naming amendment

The 2026-08-17 review applies Soklet's role, value, and collection naming
conventions across the complete still-unreleased MCP surface. No old-name
alias is retained because 3.6 replaces the MCP API wholesale. The Phase 4
owned type families change one-for-one:

- `McpHandlerResolver` becomes `McpEndpointRegistry`, its package-private
  implementation becomes `DefaultMcpEndpointRegistry`, and the public server
  getter and builder input become `getEndpointRegistry()` and
  `endpointRegistry(McpEndpointRegistry)`;
- `McpToolCallContext<A>` becomes `McpToolArguments<A>`, and the documented
  public tool-handler parameter name becomes `arguments` rather than `call`;
- `McpHandlerInvocation` becomes `McpHandlerContinuation`, its one-shot
  operation becomes `proceed()`, and the interceptor parameter name becomes
  `continuation`;
- `McpSchema`, `McpResourceHandler`, `McpListResources`, and
  `McpRequestRejection` become `McpToolSchema`, `McpResourceReadHandler`,
  `McpResourceList`, and `McpAdmissionRejection`; and
- the Phase 6-owned `McpTraceCorrelation` becomes
  `McpTraceCorrelationControl`, so the Phase 4-owned server accessor becomes
  `getTraceCorrelationControl()`.

The same review gives `McpAdmissionDecision` the direct factories
`accepted()`, `accepted(McpAdmissionIdentity)`, and
`rejected(McpAdmissionRejection)` in place of `fromAnonymousIdentity()`,
`fromAcceptedIdentity(...)`, and `fromRejection(...)`. Resource cache members
are singularized consistently: `getResourcesListCachePolicy()` and
`resourcesListCachePolicy(...)` become `getResourceListCachePolicy()` and
`resourceListCachePolicy(...)`; `getResourceTemplatesListCachePolicy()` and
`resourceTemplatesListCachePolicy(...)` become
`getResourceTemplateListCachePolicy()` and
`resourceTemplateListCachePolicy(...)`. The corresponding
`McpServerEndpoint` annotation members become `resourceListCacheTtlMs`,
`resourceListCacheScope`, `resourceTemplateListCacheTtlMs`, and
`resourceTemplateListCacheScope`.

The Phase 4 owner inventory remains 133 entries, now with include SHA-256
`8c0c7f3a0b17cd824d292969b1dd4eb4b52bc64929f65b562a197e8dcf510b6b`.
Its snapshot remains exactly 1,053 records - 133 classes, ten constructors,
79 fields, and 831 methods - while its SHA-256 advances from
`f76f3bcfea8e9d231c1da292cc309f09336a3a94a9c3a1ca8cd02ca902117890`
to
`ea33203fe502b026d56d7711ffae816c3a68909efe2ae2bdd5a822093f881ef7`.
The identity- and parameter-name-sensitive reflection digest advances from
`a7a0c227de5e130aa36d5c93da09116bf98d9faba26c15d2ff1b91740776c035`
to
`d55b5e00570ca13de3168c2e77deb65003ae26d8e991a6eef96f21fa5f958d08`.

Across the complete generated comparison, the canonical incompatibility
ledger advances from 562 records with SHA-256
`7255791d02be0cf7b0b9e601683a2da008bd41ee3a2e48b2ae8345f8bb8d85cd`
to 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.
That generated ledger is authoritative; its net increase of two is not a
count of renamed source declarations. At that cohesion checkpoint, Phase 5
and Phase 6 were count-neutral under their separately recorded one-for-one
naming amendments.

### Greenfield localization-result simplification amendment

The subsequent 2026-08-17 review removes the Phase 6-owned
`McpLocalizationResult.Fallback` type and `fallback(String, Locale)` factory
without aliases. The Phase 4 owner inventory, snapshot, include hash, and
reflection digest do not change. Phase 5 is likewise unchanged. At that
simplification checkpoint, Phase 6 contained 64 owners and 420 records, so the
exact owner union was 133/39/64/0 (236 total) and the phase-record partition
was 1,053/195/420.

At that checkpoint, the Phase 6 signature SHA-256 was
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`,
its include SHA-256 was
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`,
and its reflection digest was
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

### Greenfield localization-context builder amendment

The subsequent same-day review converts the Phase 6-owned
`McpLocalizationContext` interface into a Soklet-owned final immutable class
and adds its nested `Builder`. Applications now supply only a JDK `Function`
localization callback and do not implement or subtype the context. No old
shape or callback alias is retained. The Phase 4 owner inventory, snapshot,
include hash, and reflection digest do not change; Phase 5 is likewise
unchanged.

At that amendment checkpoint Phase 6 contained 65 owners and 426 records, so
the exact owner
union is 133/39/65/0 (237 total) and the phase-record partition is
1,053/195/426. The Phase 6 signature SHA-256 was
`7f264422a9e0a81718ae46bc5333a26d56d4c772ded5620d91335b4253734878`,
its include SHA-256 is
`474e1c3079501b286a9eb1b38dee06a532d263aef50b633b46d465813024dacc`,
and its reflection digest is
`f6e0abeb94bf4e98822a57214c1fe459451fa207b377d99f10c3a562be2b9afa`.
The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

### Final greenfield API polish amendment

The final same-day review removes the last pre-freeze convenience and SPI
ambiguities without retaining aliases. `McpEndpointRegistry` is now a final,
immutable Soklet-owned class rather than an application-implemented interface;
its public factories and copy operations are unchanged. Tool handlers read the
converted value through `McpToolArguments.getConvertedArguments()`.
String-valued builder overloads are explicit
`McpEndpoint.Builder.toolRateLimiterName(String)` and
`McpToolRegistration.Builder.rateLimiterName(String)` (including the complete
builder), while direct `McpRateLimiter` overloads and annotation elements keep
their existing names. `McpHandlerInterceptor.passThroughInstance()` and
`McpToolOutput.Builder.error(Boolean)` describe their values directly.

No pre-amendment alias remains. At that checkpoint, the Phase 4 owner and
component counts stayed at
1,053 records across 133 owners, while the canonical signature SHA-256 becomes
`3fd2ead5b1e1dfa98686b722dc6ed274a073a9bccbe55d0ac2a215f5d17dfa9f`
and the reflection/nullability digest becomes
`fc06dda2a4b0d2300136b9173e05db0e4a573c1a9755855cf1c155cecf331be9`.
Phase 5 independently removes one redundant factory as recorded in its
rationale; Phase 6 is unchanged. The owner partition at that checkpoint was
133/39/65/0, the signature partition was 1,053/194/426, and the generated
compatibility ledger remained exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

### 2026-08-18 greenfield public-record elimination amendment

The subsequent greenfield review eliminates all 45 public MCP record shapes -
nine top-level and 36 nested - instead of making record canonical constructors
and component accessors permanent parts of the unreleased 3.6 API. Every
former record is now a final Soklet-owned class with private constructors,
construction through named factories or builders wherever public construction
is supported, conventional getters, explicit value equality and hash codes,
and a redacted or otherwise data-minimizing diagnostic rendering. Fieldless
variants use shared instances. No canonical-constructor, component-accessor,
record-shape, or deprecated compatibility alias is retained.

Phase 4 owns eight of the converted shapes: the top-level `McpJsonBoolean`,
`McpJsonNumber`, `McpJsonString`, and `McpPromptMessage` values; the nested
`McpAdmissionDecision.Accepted` and `Rejected` decisions; and the nested
`McpRateLimitDecision.Allowed` and `Denied` decisions. JSON values use
`fromValue(...)`; prompt messages retain their role-specific named factories;
and the sealed decision interfaces expose their accepted/rejected and
allowed/denied factories while keeping variant constructors private.

The same amendment applies that construction policy to the already-final
`McpCachePolicy`: its remaining public constructor is private, while
`privateNoCacheInstance()`, `fromPrivateTimeToLive(...)`, and
`fromPublicTimeToLive(...)` remain the complete public construction surface.
Across all three frozen phases, the sole public constructor is the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor. Non-throwable frozen values
are factory- or builder-owned.

The owner count remains 133. The Phase 4 snapshot now contains exactly 1,047
records - 133 classes, one constructor, 79 fields, and 834 methods - with
SHA-256
`dc733de19433200065526bd02f985b56ca69f658aefd116e80446b5c885f035b`.
Its reflection/nullability digest is
`581038cefbc8e65845e38001632ed0678a83efe55446e4f25f233e874eef3f39`.
At that amendment checkpoint, the complete owner and signature partitions were
133/39/65/0 and 1,047/191/422.

The released-3.5.1 compatibility ledger now contains 565 records with SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`.
Its sole net-new incompatibility is the `McpPromptMessage` superclass change
from `java.lang.Record` to `java.lang.Object`; the former `role()` and
`content()` record-component changes are now method removals rather than new
compatibility entries.

The exact public-record-amendment tree passed a clean Corretto 26 verify with
1,671 tests, zero failures, zero errors, and four intentional skips; JDK 21 static
analysis succeeds, SpotBugs reports zero findings, the aggregate freeze gate
verifies all 565 compatibility records and 1,047/191/422 signatures, and the
maintained 182-source Java 17 API sketch passes compilation and Javadoc
doclint.

### 2026-08-18 greenfield typed-request-state amendment

The subsequent greenfield review removes the public sealed `McpRequestState`
carrier family, including `McpApplicationRequestState` and
`McpFrameworkRequestState`, without aliases. Applications no longer construct
throwaway wrapper values: request contexts expose application state directly
as `Optional<String>` through `getApplicationRequestState()` and framework
state directly as `Optional<McpJsonValue>` through
`getFrameworkRequestState()`.

On the Phase 4-owned `McpRequestContext`, the two typed default getters replace
the former single `getRequestState()` default getter. The owner count remains
133, while the snapshot gains one method and now contains exactly 1,048
records - 133 classes, one constructor, 79 fields, and 835 methods - with
SHA-256
`0efe130ce6da63230f2bbf5f4c50889209a53bd49995f7da1a42ff713c7f60d4`.
Its reflection/nullability digest is
`1d33a5deb35adb467feccac10ffce635eae903437a096ed63a8c17a1b57d2309`.

Phase 5 removes the three carrier owners and their 13 signature records, then
adds one net host method by replacing one result getter with the same two typed
getters. The complete current owner and signature partitions are therefore
133/36/65/0 (234 total) and 1,048/179/422. Phase 6 descriptors are unchanged.
The compatibility ledger remains exactly 565 records with SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`
because every affected descriptor is an unreleased greenfield addition
relative to 3.5.1. The focused reflection/inventory contract passes 24/24 and
the aggregate freeze gate verifies the exact updated partitions. Fresh
Corretto 26 clean verify passes 1,673 tests with zero failures, zero errors,
and four intentional skips over 462 main and 193 test sources, and builds the
main, sources, and Javadoc artifacts; the maintained 179-source Java 17 API
sketch passes compilation, Javadoc doclint, and its localization smoke test.

### 2026-08-24 reserved application-metadata behavioral amendment

The 4.0 review deliberately tightens construction behavior without changing
the frozen public/protected descriptor set. Every public builder in the
mechanically derived `mcp-metadata-builder-inventory.json` now rejects an
application-authored `_meta` key whose DNS-style prefix has `mcp` or
`modelcontextprotocol` as its second label. The exact seven reviewed owners
are `McpTextContent`, `McpImageContent`, `McpAudioContent`, `McpResourceLink`,
`McpEmbeddedResource`, `McpTextResourceContents`, and
`McpBlobResourceContents`.

One internal prefix validator supplies both these public-package construction
checks and the pre-existing protocol-side application-metadata validators.
Inbound protocol-owned metadata remains accepted and preserved. Legal
application namespaces, including `com.example.mcp/...`, and empty metadata
remain accepted and are preserved by identity. Validation occurs while the
immutable value is built, before a tool, prompt, or resource response can be
constructed or serialized; nested text and blob resource contents inside an
`McpEmbeddedResource` follow the same rule.

The Node verifier derives eligible owner/method identities exclusively from
the current-side generated Phase 4/5/6 signature files, treats those files as
read-only, and requires exact equality with the checked-in inventory. The
named Java behavior test loads the same inventory, requires exact adapter-key
parity, exercises every reserved-prefix family and every inventoried builder,
and proves handler failures yield only the generic protocol error without a
partial result, exception detail, or handler-output leak. Existing valid wire
goldens are unchanged. Because this is a behavioral tightening with no public
member, modifier, annotation, hierarchy, or JSpecify change, the reviewed
1,048/179/422 signature partitions, reflection/nullability digests, and
565-record released-3.5.1 compatibility ledger remain unchanged.

### 2026-08-27 Soklet 4.0 lifecycle cutover

The 4.0 lifecycle review deliberately changes the frozen Phase 4 hosts rather
than retaining compatibility aliases for the unreleased intermediate API:

- all four `LifecycleObserver.didStop*` methods now carry the exact aggregate
  or participant shutdown result, and all four `didFailToStop*` methods are
  removed because failure evidence is part of those results;
- `SokletConfig` and its builder gain the shared `LifecyclePolicy`, while the
  redundant `SokletConfig.Copier` owner and `copy()` surface are removed;
- `McpLogLevel` loses the Java `@Deprecated` annotation, Javadoc
  `@deprecated` block tag, and generated annotation record because SEP-2577
  wire lifecycle is independent from Soklet Java API lifecycle;
- `McpServer` and its builder lose direct lifecycle, `AutoCloseable`, status,
  and `shutdownTimeout` descriptors so the Soklet owner is the sole lifecycle
  authority; and
- `MetricsCollector`, `MetricsCollector.Snapshot`, and its builder retain
  their exact descriptors. Their use of the revised Phase 6 disposition type
  does not create another Phase 4 host change.

Removing `SokletConfig.Copier` leaves 132 Phase 4 owners. The regenerated
snapshot contains 1,025 records: 132 classes, one constructor, 79 fields, and
813 methods. Its SHA-256 is
`89360e07e7813f349b01ae860e19865b62ff35ff40c9bb59c8be4f1fce226658`;
the reflection/nullability digest is
`8030cb36ddb7aad8534c601b173e37b99e4b164942f2fd30628f9adde1e35eb3`;
and the include-inventory SHA-256 is
`adbdfe675205831336fd0d95f44911b9b4ab94de24bd7ade232805f02b52b785`.
Phase 5 remains byte-identical at 36 owners and 179 records. The revised
Phase 6 rationale records its own cutover changes.

The general lifecycle, result, runner, simulator, and transport-SPI owners
remain outside artificial MCP phase ownership in the exact 39-entry non-MCP
allowlist. The complete MCP union is 232 owners, while current-side inventory
verification covers 271 owners including that allowlist. The regenerated
released-3.5.1 compatibility ledger contains 617 records with SHA-256
`302f68448fe14b1cc5ad179c076c5b84b16e81b0b21dca55e0cc5edcbaadea41`.

The snapshot includes every final descriptor that a later phase needs on a
Phase 4-owned host:

- endpoint subscription configuration, endpoint copying, and exact
  endpoint-class overlay through `McpEndpointRegistry`;
- the mandatory admission controller, its server getter, and its builder
  input;
- request-context input responses and directly typed application/framework
  request-state accessors;
- tool, prompt, and resource registration input declarations and request-state
  mode, plus the equivalent handler-annotation elements and defaults;
- server protection-control and trace-correlation accessors;
- builder inputs for protection configuration, the dedicated trace key, and
  raw-validated-trace-ID logging;
- the server localizer input and localization-control accessor, plus the
  interceptor's direct invocation-feature parameter;
- the dedicated structured trace-correlation log-event type;
- stream-queue, write-timeout, keep-alive, shutdown-timeout,
  per-authorization-partition-subscription and subscription-duration controls;
  and
- the existing lifecycle/metrics shared-host attachment descriptors.

The six operational defaults are positive and finite:

- stream queue capacity: 128;
- write timeout: 30 seconds;
- keep-alive interval: 15 seconds;
- shutdown timeout: 30 seconds;
- maximum subscriptions per endpoint and authorization/quota partition: 32;
  and
- maximum subscription duration: 24 hours.

At the Phase 4 freeze, the referenced Phase 5/6 types' own members remained
unfrozen until their owning phases froze. Phase 5 and Phase 6 later froze under
their own snapshots. At the L1 localization boundary, the new
localization attachments are behaviorally neutral with respect to MCP wire
output. The other later-phase attachments retain their reviewed activation
rules: a descriptor alone does not advertise a later capability, subscribe to
an event publisher, execute MRTR, protect request state, or perform trace
correlation.

The reviewed surface deliberately excludes an initial-protection-config
getter, configurable invalid-trace-context policy, a server-level server-
information switch, the rejected legacy raw transport knobs, and Phase 4
`Simulator` MCP members. These are exclusions, not deferred additions to a
frozen Phase 4 host.

### Pre-G3 public API correction

The 2026-08-28 review corrected three unreleased API seams before the D1p
preview is eligible for G3:

- `McpToolArgument` now targets method parameters only, while the new
  `McpToolProperty` targets typed input/output record components and carries
  the same name, title, and description metadata. The processor and runtime
  schema frontends reject the annotations on each other's targets rather than
  retaining a dual-purpose compatibility alias.
- `McpHandlerInterceptor.interceptHandler(...)` receives the exact
  `McpInvocationFeatures` instance as its second argument. The synchronous
  one-shot `McpHandlerContinuation` is again a single-method `proceed()`
  carrier, so composed wrappers cannot silently fall back to an empty default
  feature lookup.
- `McpAdmissionContext.getRequestedResourceSubscriptionUris()` exposes an
  immutable, validated, first-encounter-order URI projection for
  `subscriptions/listen` admission. Other methods receive an empty list;
  admission rejection still precedes subscription activation, and capability
  acceptance still controls delivery.

The annotation split adds one Phase 4 owner. The combined snapshot contains
1,029 records across 133 owners: 133 classes, one constructor, 79 fields, and
816 methods. Its SHA-256 is
`ba976dbbe4d72d2b38a7f167bb88164e321064fea5847cc45f6992220b735e2f`;
the Phase 4 reflection/nullability digest is
`9cfe146213f1c96cfdd1de6fe05caa58d8055f7abdb491b6141491f2dc8de646`;
and the include-inventory SHA-256 is
`fd3293a1089845a3c90c22cda8bd59986b8a975c3cb10211ab3ea8831a7e5021`.
Phase 5 and Phase 6 remain byte-identical at 179 and 421 records. The complete
owner partition is 133/36/64/0, with 233 MCP owners and 272 current-side
owners after the exact 39-entry non-MCP allowlist. Adding the admission method
advances the released-3.5.1 compatibility ledger to 618 records with SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.
Compiler extraction, bidirectional inventory/signature checks, focused
processor/runtime/schema/interceptor/subscription tests, public reflection and
Javadoc checks, and the executable Java-17 sketch validate the correction.
These are local D1p development facts, not G3 or release-candidate evidence.

### 2026-09-01 shutdown-component naming amendment

The unreleased lifecycle-cutover vocabulary now identifies terminal evidence
with the `ShutdownComponent*` family instead of the broader
`LifecycleComponent*` family. `LifecycleObserver` and `LifecyclePolicy` retain
their names because they span startup, running, and shutdown behavior. The
synthetic framework-owned type is `ShutdownComponentType.FRAMEWORK`; no
`OTHER` bucket or compatibility alias exists.

The amendment changes names one-for-one and leaves the Phase 4 owner and
record counts unchanged. The Phase 4 signature SHA-256 is
`2b50fb6e08d2b9eccf3a45d4020cbf4738a79517102602e69067f8a198158516`;
the reflection/nullability SHA-256 is
`dc83138dd80f93c003ec527fec24a9fd7e09633417f0f5a6430ac254421797b1`;
and the include-inventory SHA-256 remains
`fd3293a1089845a3c90c22cda8bd59986b8a975c3cb10211ab3ea8831a7e5021`.
Phase 5 remains byte-identical. The released-3.5.1 compatibility ledger remains
618 records with SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.

The same-day non-MCP application-runner refinement removes
`SokletApplicationOptions` and its nested builder and vends the one-shot
`SokletApplication.Builder` from the already-owned `SokletApplication` type.
No Phase 4 owner or descriptor changes. The non-MCP allowlist now contains 38
owners with SHA-256
`077e568a38d3b63d187fdd4c1989f386145028c5e662c876ea7642e6c9c8aba2`,
so the complete current-side owner inventory contains 271 types.

## Historical local verification

The original 2026-08-06 closeout pass, before the wrapper correction and the
current Phase 5 runtime slices, produced this historical local evidence:

- `scripts/verify-mcp-api-freezes.sh` passed bidirectionally with 561 reviewed
  incompatibilities, 200 exact owners, and 1,049 exact Phase 4 signatures;
- the full JDK 21 suite passed 1,246 tests with zero failures, zero errors, and
  four expected skips;
- a clean JDK 26 package passed the same suite and built the main, source, and
  Javadoc artifacts;
- the JDK 21 Error Prone/NullAway compile passed at the configured severity;
  pre-existing warnings remain and NullAway is in advisory `WARN` mode, so
  this is not a warning-free claim;
- JDK 21 SpotBugs reported zero bugs and zero errors;
- the compile-only API sketch compiled all 167 sources for Java 17 and passed
  its Javadoc doclint gate;
- the pinned JSON Schema corpus verification and official conformance-runner
  self-test passed; and
- the JDK 21 benchmark sources compiled.

One full-suite run exposed a test-only ordering race in the transport
containment fixture: two independent connections could acquire the only
handler slot in the opposite order from their construction. The fixture now
proves the active request has acquired its slot before opening the queued
connection. The focused 30-case containment suite and subsequent complete JDK
21 and JDK 26 suites passed; no production scheduling change was made.

The later wrapper review regenerated the then-current 556-record compatibility
set and 1,049-record Phase 4 signature snapshot. The localization amendment
then regenerated the 559-record current set and the 1,052-record Phase 4
snapshot. The structured trace-log amendment retained the same 559-record
compatibility set and moved only the Phase 4 field/signature counts to 79 and
1,053, with its checkpoint snapshot and JSpecify digests recorded in the dated
amendment above.
The rate-limit factory amendment then changed only two method names; the
admission-controller amendment replaced five snapshot records without changing
the counts and advanced the canonical compatibility set to 562. The
greenfield cohesion amendment then replaced the reviewed naming families
one-for-one without changing Phase 4 counts and advanced the canonical
compatibility set to 564. The later localization-result simplification changes
only Phase 6 and leaves that 564-record set unchanged. The final greenfield API
polish changes the Phase 4 signature and reflection hashes without changing
its counts or that compatibility set. The subsequent public-record elimination
changes all three phase snapshots and advances the compatibility set to 565;
current Phase 4 hashes are recorded in the frozen-snapshot section above.
The 2026-08-16 aggregate compatibility, ownership, and freeze gate passed with
the then-current 562-record counts and hashes. The preceding focused trace-log
carrier,
request-observation, observability, L1 API,
extraction, interception, inventory, Javadoc, source-convention, and unchanged-
wire tests passed. This rationale does not infer a fresh full package, static-
analysis, benchmark, or official-conformance run from those focused API
artifact checks.

## Evidence that remains open

JDK 17 and JDK 25 results for the current tree remain CI-authoritative. The
earlier exact 23-scenario candidate-artifact result remains historical Phase 4
implementation evidence. The final local development artifact now passes both
the artifact-backed simulator and pinned live official CLI at 39/39; immutable
release-candidate provenance and release-mode execution remain open.

Subsequent local development implemented MRTR input-required and retry
paths, application- and framework-protected request state, request-scoped
progress/cancelation, and resource subscriptions. Those APIs were later
reviewed and frozen under the separate Phase 5 rationale. The historical Phase
4 freeze decision does not itself establish the later Phase 5/final 39-row
gate, current-tree package or static-analysis sign-off, release-candidate
JAR/POM provenance, complete Phase 4 exit, or Phase 6 runtime behavior. The
current development revalidation above is additive and intentionally contains
no release commit identifier; repository history and publication remain
maintainer-owned.
