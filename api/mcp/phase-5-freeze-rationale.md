# MCP Phase 5 API-freeze rationale

Date: 2026-08-08

This record approves the Phase 5 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a compatibility decision; it
does not by itself establish Phase 5 conformance or release-candidate status.

The preceding
[public-API review checkpoint](../../../mcp/PHASE_5_API_REVIEW_CHECKPOINT_2026-08-08.md)
records the complete review decision and its test, static-analysis, and API-
sketch evidence. The snapshot checked in here is byte-for-byte identical to
the candidate approved by that review.

## Compatibility and ownership model

The reviewed current incompatibility set contains exactly 556 canonical
symbols and has SHA-256
`c3313a6f690429f833f4b8e09ab84e92ab187255ab83f5944818c68cdd6dfe8e`.
The matching full japicmp report establishes an exact owner universe of:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- six Phase 6 owners;
- 28 provisional owners; and
- 206 owners in total.

The 39 Phase 5 owners are the exact sorted entries in `phase-5.includes`.
The Phase 4 snapshot and its 133-owner inventory remain unchanged. Phase 6
and provisional owners remain unfrozen.

## Frozen Phase 5 snapshot

`phase-5.signatures.jsonl` contains exactly 195 canonical records:

- 39 classes;
- six constructors;
- 15 fields; and
- 135 methods.

The reviewed file's SHA-256 is
`c6862ed49a9bc9565ba2284190c49605928270fb8a6fb73f75070452f909e75f`.
The independent reflection contract freezes the Phase 5 JSpecify type-use
layout with SHA-256
`d52a424ac33e679e0a0632004ac931e59966b68641659e254214964d9144f8c7`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 195 records and was byte-for-
byte identical to the reviewed candidate. The aggregate freeze gate compares
both Phase 4 and Phase 5 snapshots bidirectionally on every run.

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
separate conformance evidence and are not inferred from this file. Those gates
subsequently passed and are recorded in the external
[activation/verification checkpoint](../../../mcp/PHASE_5_ACTIVATION_AND_VERIFICATION_2026-08-08.md).
JDK 17 and JDK 25 CI and later release-candidate provenance remain separate
obligations. This rationale intentionally contains no commit identifier;
repository history and publication remain maintainer-owned.
