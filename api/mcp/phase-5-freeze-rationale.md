# MCP Phase 5 API-freeze rationale

Date: 2026-08-08

Greenfield cohesion naming amendment reviewed: 2026-08-17

Greenfield localization-result simplification amendment reviewed: 2026-08-17

Greenfield localization-context builder amendment reviewed: 2026-08-17

Final greenfield API polish amendment reviewed: 2026-08-17

Greenfield public-record elimination amendment reviewed: 2026-08-18

Greenfield typed-request-state amendment reviewed: 2026-08-18

MCP value-contract amendment reviewed: 2026-09-03

Invocation and typed-input declaration amendment reviewed: 2026-09-03

This record approves the Phase 5 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a compatibility decision; it
does not by itself establish Phase 5 conformance or release-candidate status.

The durable repository-owned evidence is the
[Phase 5 owner inventory](phase-5.includes), the reviewed
[signature snapshot](phase-5.signatures.jsonl), the
[compatibility set](current-incompatibilities.jsonl), and the aggregate-gate
contract in the [API inventory README](README.md#running-the-gates). The
snapshot checked in here is byte-for-byte identical to the candidate approved
by the review summarized below.

## Compatibility and ownership model

At the original Phase 5 review, the incompatibility set contained exactly 556
canonical symbols and had SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
The matching full japicmp report establishes an exact owner universe of:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- six Phase 6 owners;
- 28 provisional owners; and
- 206 owners in total.

That list is the original Phase 5 checkpoint. The current exact owner
partition is 133 Phase 4, 37 Phase 5, 64 Phase 6, zero provisional, and 234
MCP owners; the 38-owner non-MCP allowlist brings current-side coverage to 272.

The 37 current Phase 5 owners are the exact sorted entries in
`phase-5.includes`. At the original Phase 5 checkpoint, the Phase 4 snapshot
and its 133-owner inventory were
unchanged, while Phase 6 and provisional owners remained unfrozen. Phase 6
later froze under its own snapshot; the reviewed telemetry amendment then
moved every former provisional owner into Phase 6 and emptied the provisional
inventory. The subsequent compatible trace-log amendment, rate-limit decision
factory naming amendment, and greenfield admission-controller naming amendment
changed only the Phase 4 snapshot, not this Phase 5 snapshot.
The later 2026-08-17 greenfield cohesion amendment is count-neutral in every
phase and advanced the complete compatibility set at that checkpoint to 564
records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.
The 2026-08-18 public-record elimination amendment retained the owner partition
and advanced that checkpoint to 565 records. Typed request state then removed
three Phase 5 carrier owners. The lifecycle and pre-G3 API corrections leave
Phase 5 byte-identical while the current released-3.5.1 comparison contains
621 records with SHA-256 `25c842a78adc9217d13d8c6a68a8aec996026923ba81fe9dded7234298098964`.

## Frozen Phase 5 snapshot

`phase-5.signatures.jsonl` contains exactly 190 canonical records:

- 37 classes;
- zero constructors;
- 19 fields; and
- 134 methods.

The reviewed file's SHA-256 is
`54a96f16d32096b4a4a68a29f727443853178e5da1f0dadacce2004cca70d420`.
The independent reflection contract freezes the Phase 5 JSpecify type-use
layout with SHA-256
`5c90b20e8b582931ca636d91ccf11c9fdc92734289bdad9b27eb9a529645db7f`.
The 37-entry `phase-5.includes` inventory has SHA-256
`97e1796b3972136dcba44dcd978e47df15ab8351138d080c1d52f8df58ae29f7`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 190 records and was byte-for-
byte identical to the reviewed candidate. The aggregate freeze gate compares
the Phase 4, Phase 5, and now Phase 6 snapshots bidirectionally on every run.

## 2026-08-17 greenfield cohesion naming amendment

The Phase 5-owned `McpSubscriptionEventSubscription` type becomes
`McpSubscriptionEventRegistration`. The new name matches the SPI contract: the
value is an idempotently closable listener registration returned by
`McpSubscriptionEventPublisher.subscribe(...)`, not the MCP resource
subscription itself. The old type is not retained as an alias because the 3.6
MCP API is greenfield.

This is a one-for-one owner and descriptor replacement. `phase-5.includes`
remains exactly 39 entries with SHA-256
`696d63fb09f9f8ff9c3d1af2cf52ea49532cc9b3e15a81584abaa5dbda7031fe`.
The Phase 5 snapshot remains exactly 195 records - 39 classes, six
constructors, 15 fields, and 135 methods - while its SHA-256 advances from
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`
to
`4105df142e671c704b341eec54a65b5cbdc8da931888cab43d85835f577e2a32`.
The identity-sensitive reflection digest advances from
`d52a424ac33e679e0a0632004ac931e59966b68641659e254214964d9144f8c7`
to
`a9c0a9311b6b0dff74b2813383b903a01dc185cbf7155c009edf1f6fb8e0d304`.
The complete generated compatibility ledger advances from the pre-amendment
562 records with SHA-256
`7255791d02be0cf7b0b9e601683a2da008bd41ee3a2e48b2ae8345f8bb8d85cd`
to 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`;
the generated ledger, rather than the number of renamed declarations, defines
that comparison delta.

## 2026-08-17 greenfield localization-result simplification amendment

The subsequent same-day review removes the Phase 6-owned
`McpLocalizationResult.Fallback` type and `fallback(String, Locale)` factory
without aliases. No Phase 5 owner or descriptor changes: `phase-5.includes`
remains at 39 entries, and `phase-5.signatures.jsonl` remains at 195 records
with the same include, signature, and reflection hashes recorded above.

At that simplification checkpoint, Phase 6 contained 64 owners and 420 records
(64 classes, 31 constructors, 40 fields, and 285 methods), so the exact owner
partition was
133/39/64/0 (236 total) and the phase-record partition was 1,053/195/420. Its
signature, include, and reflection SHA-256 values are respectively
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`,
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`,
and
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## 2026-08-17 greenfield localization-context builder amendment

The subsequent same-day review converts the Phase 6-owned
`McpLocalizationContext` interface into a Soklet-owned final immutable class
and adds `McpLocalizationContext.Builder`. Applications now provide only the
JDK `Function<McpLocalizableText, McpLocalizationResult>` callback and do not
define context subtypes. No compatibility alias is retained. No Phase 5 owner
or descriptor changes: its inventory, 195-record snapshot, and hashes remain
exactly as recorded above.

At that amendment checkpoint, Phase 6 contained 65 owners and 426 records - 65
classes, 31 constructors, 40 fields, and 290 methods - so the exact owner
partition was 133/39/65/0 (237 total) and the phase-record partition was
1,053/195/426. Its signature, include, and reflection SHA-256 values were
respectively
`7f264422a9e0a81718ae46bc5333a26d56d4c772ded5620d91335b4253734878`,
`474e1c3079501b286a9eb1b38dee06a532d263aef50b633b46d465813024dacc`,
and
`f6e0abeb94bf4e98822a57214c1fe459451fa207b377d99f10c3a562be2b9afa`.
The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## 2026-08-17 final greenfield API polish amendment

The final same-day review removes the redundant
`McpInputRequest.fromDeclaration(...)` factory. At that checkpoint,
`McpInputRequest` was a Soklet-owned record whose canonical constructor
validated its declaration and parameters, so applications used that
constructor directly. No factory alias was retained at that checkpoint.

The Phase 5 owner count and class, constructor, and field counts remain
unchanged. Removing the one method leaves 194 records - 39 classes, six
constructors, 15 fields, and 134 methods - with signature SHA-256
`19e0d0184d6c347e63689acfcef06222d6131d5d0a469740b627342b7ee24785`
and reflection/nullability digest
`d10c45dddd332f7308f6d731371b73412314a28560ba7f747a0e68071bfc59af`.
The owner partition at that checkpoint remained 133/39/65/0, the signature
partition was 1,053/194/426, and the generated compatibility ledger remained
exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## 2026-08-18 greenfield public-record elimination amendment

The subsequent review eliminates all 45 public MCP record shapes - nine
top-level and 36 nested - in favor of final Soklet-owned classes. Constructors
are private, applications construct values through named factories or
builders, component accessors become conventional getters, and each former
record has explicit equality, hash-code, and redacted or otherwise
data-minimizing diagnostic semantics. Fieldless variants use shared instances.
The unreleased greenfield API retains no canonical-constructor,
component-accessor, record-shape, or deprecated compatibility alias.

Phase 5 owns six of the converted shapes: `McpApplicationRequestState`,
`McpFrameworkRequestState`, `McpInputRequest`, and
`McpInputRequestDeclaration`, plus the nested
`McpSubscriptionEvent.ResourcesListChanged` and `ResourceUpdated` variants.
The request-state values use `fromValue(...)`; input declarations retain their
domain-specific factories; `McpInputRequest.fromDeclaration(...)` is restored
as the named construction boundary; and the subscription-event interface owns
the list-changed and resource-updated factories. Application-controlled state,
input parameters, and resource URIs remain redacted from diagnostics.

The Phase 5 owner count remains 39. Its snapshot now contains exactly 191
records - 39 classes, zero constructors, 15 fields, and 137 methods - with
SHA-256
`ea6d46dc055a57b2d31820cb937d89fe42bac5665c18c5fdb83eea75e79c82f5`.
The reflection/nullability digest is
`9c8c02a4eca29166a6a92956fa58033ea94939e6d7deef9e23a7ecd6d5babd3e`.
At that amendment checkpoint, the complete owner and signature partitions were
133/39/65/0 and 1,047/191/422. The same amendment makes the already-final
Phase 4-owned `McpCachePolicy` constructor private. The sole public constructor across all
three frozen phases is the throwable
`McpJsonRpcException(McpJsonRpcError)` constructor; non-throwable values are
factory- or builder-owned.

The complete released-3.5.1 compatibility ledger contains 565 records with
SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`.
No converted Phase 5 value adds a net incompatibility; the sole net-new entry
is the Phase 4-owned `McpPromptMessage` superclass change from
`java.lang.Record` to `java.lang.Object`.

The exact public-record-amendment tree passed a clean Corretto 26 verify with
1,671 tests, zero failures, zero errors, and four intentional skips; JDK 21 static
analysis succeeds, SpotBugs reports zero findings, the aggregate freeze gate
verifies all 565 compatibility records and 1,047/191/422 signatures, and the
maintained 182-source Java 17 API sketch passes compilation and Javadoc
doclint.

## 2026-08-18 greenfield typed-request-state amendment

The subsequent greenfield review removes the public sealed `McpRequestState`
carrier family, including the Phase 5-owned `McpApplicationRequestState` and
`McpFrameworkRequestState`, without aliases. Handlers and input-required
results expose application state directly as `Optional<String>` and framework
state directly as `Optional<McpJsonValue>` through
`getApplicationRequestState()` and `getFrameworkRequestState()`.

The builders retain `applicationRequestState(String)` and
`frameworkRequestState(McpJsonValue)`. The two forms remain mutually exclusive
and last-call-wins, but callers no longer construct an otherwise throwaway
wrapper object. Removing the carrier family deletes three owners and 13
signature records. Replacing `McpInputRequiredResult.getRequestState()` with
the two typed result getters adds one method, for a net Phase 5 change from 191
to 179 records.

The current Phase 5 snapshot contains 36 classes, zero constructors, 15
fields, and 128 methods, with signature SHA-256
`96f56fc34f81a9302d1387d437bee4caa36e465a07a40a8577eed4bd4313e5e4`,
reflection/nullability digest
`6569e3b106ae11e1d30da66c045d1a9bc23aa65016f36052df6b19fc320c06d9`,
and include SHA-256
`2009a66e210e89c43e157df0498b357a5e29fc8bc7144ca373ad07c57d1fce2a`.
The complete owner and signature partitions are 133/36/65/0 (234 total) and
1,048/179/422. Phase 6 descriptors are unchanged. The compatibility ledger
remains exactly 565 records with SHA-256
`3269b4a73d42c035a90735336462aaeb98bf6809d003fa858dbfa4a839e4c2e2`
because the removed family and changed host descriptors are unreleased
greenfield additions relative to 3.5.1. The focused reflection/inventory
contract passes 24/24 and the aggregate freeze gate verifies the exact updated
partitions. Fresh Corretto 26 clean verify passes 1,673 tests with zero
failures, zero errors, and four intentional skips over 462 main and 193 test
sources, and builds the main, sources, and Javadoc artifacts; the maintained
179-source Java 17 API sketch passes compilation, Javadoc doclint, and its
localization smoke test.

## 2026-09-01 non-MCP direct-run and cleanup-value amendment

The final application-runner shape uses `SokletApplication.fromConfig(...)`
to create the configured one-shot value and exposes direct `run(...)`
overloads for shutdown triggers and optional bounded cleanup.
`ShutdownCleanup` is an immutable value with `fromTimeoutAndAction(...)`,
`getTimeout()`, and the nested functional `ShutdownCleanup.Action` callback.
Swapping the discarded `SokletApplication.Builder` owner for
`ShutdownCleanup.Action` changes no Phase 5 descriptor; Phase 5 remains
byte-identical at 36 owners and 179 records. The exact non-MCP allowlist
remains at 38 owners with SHA-256
`f033df8701ffef4718fa0c62858ee02054910a0698503850670e80eafdddd6d6`;
the 233-owner MCP union therefore has 271 current-side owners. The
released-3.5.1 compatibility ledger remains 618 records with SHA-256
`3d9d68bbbdeabae63a78d40a50c9896d3f11f6d0d2305beff0c94bd86476928c`.

## Reviewed contract

The snapshot freezes the implemented progress, cooperative-cancelation,
resource-subscription, MRTR/input-response, and directly typed
application/framework request-state APIs. The cross-cutting review also fixed
the following public contracts:

- MCP scalar signatures use non-null reference types such as `Integer`,
  `Long`, `Boolean`, and `Double`; primitives remain available to internal
  implementation code.
- Protection-provider selection is exclusive at construction. Keyring,
  custom-protector, and development-ephemeral factories select the provider;
  their builder can tune limits but cannot replace it.
- Soklet-owned request-state diagnostics remain redacted. At the deliberate
  handler/result boundary, application `String` and framework `McpJsonValue`
  state are directly accessible without a public carrier wrapper.
- Subscription close is idempotent and registration-scoped. A delivery
  already selected or in flight may begin or finish after `close()` returns,
  but no later delivery may be selected for that registration.
- Soklet reuses the existing `CancelationToken` invocation feature and adds
  `McpProgressReporter` as a conditionally available Phase 5 feature without
  changing the Phase 4-frozen request-context surface.

The exact reflection and source contracts cover non-signature details:
36 owners, sealed permits, enum order, value-class construction and typed
request-state getter names, SPI parameter names, public string constants, the
`McpMayRequestInput` annotation, JSpecify nullability, standard author tags,
and exact thread-safety markers.

## Review evidence and boundary

The exact reviewed tree passed the 45-test focused API contract, complete
1,390-test JDK 21 and JDK 26 suites with four expected skips, the enforced
JDK 21 Error Prone checks, and JDK 21 SpotBugs with zero findings or errors.
The 167-source API sketch compiled for Java 17 and passed Javadoc doclint.
The aggregate API gate remained green for 556 reviewed incompatibilities,
206 exact current owners, and the unchanged 1,049-record Phase 4 snapshot.

Freezing the API is one part of the atomic Phase 5 closeout. Expected-profile
activation and a fresh exact 39-scenario `--phase 5 --mode verify` result are
separate conformance evidence and are not inferred from the signature snapshot.
Those gates subsequently passed with all 39 exact profiles active; the checked-
in profile and evidence verifiers preserve that separate result. JDK 17 and
JDK 25 CI and later release-candidate provenance remain separate obligations.
This rationale intentionally contains no commit identifier; repository
history and publication remain maintainer-owned.

## 2026-09-03 Revision 2 construction and collection amendment

`McpInputRequiredResult` no longer exposes an empty builder. Its three complete
factories begin with a first input request, framework-protected state, or
application-protected state, respectively; additional requests use
`addInputRequest(...)`.
Subscription configuration likewise begins with the event publisher and a
nonempty notification-type set, then uses `addNotificationType(...)` for
extensions. Input responses use `addResponse(...)` and `addResponses(...)`,
and protection keyrings use `addVerificationKey(...)` and
`addVerificationKeys(...)`. Passing `null` to the documented optional
protection bounds resets those values to their defaults.

The amended Phase 5 snapshot contains 181 records: 36 classes, no public
constructors, 15 fields, and 130 methods. Its signature SHA-256 is
`1bd7282469dd7aa41d2aa79a926f2a929518d421c4b2ac8a61ea8b97cdb27ffa`;
the reflection/nullability SHA-256 is
`e33c1f2b4f53603d359b04d76ea90a8286954c642e6f125cb01b3eb3f0b3bec8`;
and the include inventory remains
`2009a66e210e89c43e157df0498b357a5e29fc8bc7144ca373ad07c57d1fce2a`.
This amendment adds no separate released-3.5 incompatibility; the shared
compatibility ledger contains 622 records with SHA-256
`c83e4e13f40b8c1773aac64d0fc2b4854879391ab322438187a6f3807cbbf2b8`.

The aggregate API-freeze gate and the 23-test focused reflection contract
passed against this development tree. These checks do not substitute for
release-candidate provenance or publication evidence.

## 2026-09-03 MCP value-contract amendment

The owner-approved Revision 2 value pass makes
`McpRequestStateProtectionContext.fromComponents(...)` public so an
application-provided protector can be unit-tested without constructing a live
request. The factory defensively copies associated data and does not add any
new authority to production request handling.

The amended Phase 5 snapshot contains 182 records: 36 classes, no public
constructors, 15 fields, and 131 methods. Its signature SHA-256 is
`2a0ee1e0c68a6d0776a6f4d4afe6c2d105e66770ba351afefd0f1d510cc25a15`;
the reflection/nullability SHA-256 is
`79d9a62b4cbe482621bcc0eeaa9b9dd08908ebde899512dbdb7e49134836edbf`;
and the include inventory remains
`2009a66e210e89c43e157df0498b357a5e29fc8bc7144ca373ad07c57d1fce2a`.
The shared compatibility ledger contains 618 records with SHA-256
`5846923de47c75e2ac5b926f4efdfbcf78f8d88beab1d1f1095bf62d09804114`.
The owner partition is unchanged. The focused factory and reflection contracts
and aggregate API-freeze gate are local development checks, not
release-candidate provenance or publication evidence.

## 2026-09-03 invocation and typed-input declaration amendment

The owner-approved Revision 2 input-declaration pass adds the
`McpInputRequestType` enum with form elicitation, URL elicitation, sampling,
and roots choices. Each choice derives the JSON-RPC method and base
capability. `McpMayRequestInput.samplingCapabilities()` accepts only the two
optional sampling refinements and only for sampling; invalid or duplicate
annotation combinations fail during processing. Programmatic declarations
retain their four named factories, add `getInputRequestType()`, and expose the
derived wire name as `getJsonRpcMethod()` without retaining the former
`getMethod()` alias.

The generated registration path now preserves input declarations and
`requestStateMode` for tools, prompts, and resources. Annotated tools whose
return type belongs to `McpOperationResult` use the advanced registration path
and compile only their input schema. The generated digest contract records the
absence of one fixed output schema explicitly and the runtime loader validates
that absence fail-closedly.

The amended Phase 5 snapshot contains 190 records: 37 classes, no public
constructors, 19 fields, and 134 methods. Its signature,
reflection/nullability, and include-inventory SHA-256 values are respectively
`54a96f16d32096b4a4a68a29f727443853178e5da1f0dadacce2004cca70d420`,
`5c90b20e8b582931ca636d91ccf11c9fdc92734289bdad9b27eb9a529645db7f`,
and
`97e1796b3972136dcba44dcd978e47df15ab8351138d080c1d52f8df58ae29f7`.
The owner partition is now 133/37/64/0, for 234 MCP owners and 272 reviewed
current-side owners. Phase 6 remains unchanged.

The concurrent Phase 4 context amendment adds three reviewed interface-method
records relative to released 3.5.1, bringing the shared compatibility ledger
to 621 records with SHA-256
`25c842a78adc9217d13d8c6a68a8aec996026923ba81fe9dded7234298098964`.
Focused annotation-processor, generated-runtime, descriptor, and reflection
contracts and the aggregate API gate are local development checks, not
release-candidate or publication evidence.

## 2026-09-03 focused naming and subscription-publisher amendment

The owner-approved Revision 2 naming pass treats keyring as one word in every
Java identifier and current document, aligns protection limits and controls
with their public properties, and names prompt arguments as declarations. The
new secret-free keyring accessors expose only active and verification key IDs;
they never expose key bytes. The cryptographic domain-separation bytes remain
unchanged so the source-level rename does not alter fingerprint values.

Construction of Soklet's built-in process-local subscription publisher moves
to `McpSubscriptionEventPublisher.fromInMemoryDefaults()`. The concrete
default implementation is package-private, while custom distributed
publishers continue to implement the same public SPI. Removing that concrete
public owner reduces Phase 5 to 36 owners and 189 records: 36 classes, no
public constructors, 19 fields, and 134 methods.

The Phase 5 signature SHA-256 is
`0e3e2b7f9a644f28bed2215c652f2c25e2eaff9a171983ed058ee90fc0e617ed`;
the reflection/nullability SHA-256 is
`682eb068e722f49fca8329d39994bee747a98f1e93d9812d4186e341cf0356a7`;
and the canonically sorted include inventory has SHA-256
`0ac8338321ad8d28e40e63e8b49963fd2be0a18e6d4b7e130b75071ebf756bf6`.
The complete owner partition is now 133/36/64/0, or 233 MCP owners and 271
reviewed current-side owners.

The complete released-3.5.1 compatibility ledger remains 621 records and now
has SHA-256
`38356e712db3eb747e9b525a8f2645a95ea59c50fa8de25dcfb4c21e79dc3e2e`.
The focused reflection/Javadoc contracts and aggregate API-freeze gate passed
against this development tree. These are local development checks, not
release-candidate provenance or publication evidence.
