# MCP Phase 5 API-freeze rationale

Date: 2026-08-08

Greenfield cohesion naming amendment reviewed: 2026-08-17

Greenfield localization-result simplification amendment reviewed: 2026-08-17

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
partition is 133 Phase 4, 39 Phase 5, 64 Phase 6, zero provisional, and 236
total.

The 39 Phase 5 owners are the exact sorted entries in `phase-5.includes`.
At this checkpoint, the Phase 4 snapshot and its 133-owner inventory were
unchanged, while Phase 6 and provisional owners remained unfrozen. Phase 6
later froze under its own snapshot; the reviewed telemetry amendment then
moved every former provisional owner into Phase 6 and emptied the provisional
inventory. The subsequent compatible trace-log amendment, rate-limit decision
factory naming amendment, and greenfield admission-controller naming amendment
changed only the Phase 4 snapshot, not this Phase 5 snapshot.
The later 2026-08-17 greenfield cohesion amendment is count-neutral in every
phase and advances the complete current compatibility set to 564 records with
SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## Frozen Phase 5 snapshot

`phase-5.signatures.jsonl` contains exactly 195 canonical records:

- 39 classes;
- six constructors;
- 15 fields; and
- 135 methods.

The reviewed file's SHA-256 is
`4105df142e671c704b341eec54a65b5cbdc8da931888cab43d85835f577e2a32`.
The independent reflection contract freezes the Phase 5 JSpecify type-use
layout with SHA-256
`a9c0a9311b6b0dff74b2813383b903a01dc185cbf7155c009edf1f6fb8e0d304`.
The 39-entry `phase-5.includes` inventory has SHA-256
`696d63fb09f9f8ff9c3d1af2cf52ea49532cc9b3e15a81584abaa5dbda7031fe`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 195 records and was byte-for-
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

Phase 6 now contains 64 owners and 420 records - 64 classes, 31 constructors,
40 fields, and 285 methods - so the current exact owner partition is
133/39/64/0 (236 total) and the phase-record partition is 1,053/195/420. Its
signature, include, and reflection SHA-256 values are respectively
`2fa052e8f6370d9cff7497e70d23136b9b91ca3eda304f038325f7a8811fe435`,
`2f6fa1c71302923ac9ffc0695005f509b46a6c722552c88cb03beaf3fc261979`,
and
`6fa774d10bf9c8a6ab4274f7989ef55eb8032d37a7d58e8a6243c4123706edc9`.
The generated compatibility ledger remains exactly 564 records with SHA-256
`6e14bcc0ad652b774a62613332cc7b71c93def649ecdd43e603f7d10e8974136`.

## Reviewed contract

The snapshot freezes the implemented progress, cooperative-cancelation,
resource-subscription, MRTR/input-response, and application/framework request-
state API families. The cross-cutting review also fixed the following public
contracts:

- MCP scalar signatures use non-null reference types such as `Integer`,
  `Long`, `Boolean`, and `Double`; primitives remain available to internal
  implementation code.
- Protection-provider selection is exclusive at construction. Key-ring,
  custom-protector, and development-ephemeral factories select the provider;
  their builder can tune limits but cannot replace it.
- Sensitive request-state, input-parameter, and resource-update renderings
  are exact and redacted rather than exposing their values.
- Subscription close is idempotent and registration-scoped. A delivery
  already selected or in flight may begin or finish after `close()` returns,
  but no later delivery may be selected for that registration.
- Soklet reuses the existing `CancelationToken` invocation feature and adds
  `McpProgressReporter` as a conditionally available Phase 5 feature without
  changing the Phase 4-frozen request-context surface.

The exact reflection and source contracts cover non-signature details:
39 owners, sealed permits, enum order, record shapes, SPI parameter names,
public string constants, the `McpMayRequestInput` annotation, JSpecify
nullability, standard author tags, and exact thread-safety markers.

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
