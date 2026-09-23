# P0-C check-level owner disposition — 2026-09-22

## Owner decision

The project owner replied **“I accept.”** on 2026-09-22 to the
specific question of accepting the narrow P0-C classification after reviewing
the two Sampling-bound checks and agreeing not to add Sampling solely to satisfy
them. No precise acceptance time is asserted. This records approval of the
classification below, not approval of a release or a clean official
conformance result.

For the pinned `@modelcontextprotocol/conformance` `0.2.0-alpha.11` suite at
commit `a983ba93c91e0bb31d0b6849eeb52f0ad1083107` and reviewed source-tree
SHA-256 `e63d6f13100504101afdfd5cfd084c92d801e2b4466d68965aa2e0c48a87998d`,
the owner accepts classifying **only** these two `server-stateless` results as
upstream-harness limitations when all conditions in the
[P0-C proposal](../../docs/streaming-api-milestone-6d.md#conditions-for-a-reviewable-disposition)
are met:

| Check ID | Exact raw result required |
| --- | --- |
| `sep-2575-server-rejects-undeclared-capability` | `FAILURE`, `details.untestable: true`; the probe calls unregistered `test_missing_capability`, and request 401 receives JSON-RPC `-32602` instead of exercising missing-capability rejection. |
| `sep-2575-missing-capability-http-400` | `FAILURE`, `details.untestable: true`; the same probe receives HTTP 400 without exercising JSON-RPC `-32021`. |

The generic capability rule remains covered by a separate, real-socket
Elicitation negative and positive control against the same live fixture and
candidate JAR. The undeclared request must produce HTTP 400 and JSON-RPC
`-32021` naming `elicitation.form`; the declared request must complete with
HTTP 200. All other official checks and selected scenarios retain their strict
expected profiles, including exact skip reasons and occurrence counts.

## Evidence reviewed and limits

The [paired development capture](../../docs/streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/proposal-assessment.json)
records the exact suite identity, 30 original checks, official CLI exit **1**,
and both Elicitation controls. The unmodified
[official checks](../../docs/streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/official/checks.json)
have SHA-256 `63106103f16aef80aa7cab71b8743130058adbd3a993dcbb3778510ddeff0d75`:
26 `SUCCESS`, these two `FAILURE`, and two `SKIPPED`. The
[control receipt](../../docs/streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/control/control-receipt.json)
and [capture status](../../docs/streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/capture-status.json)
bind the socket exchanges to the same fixture lifetime. The separate
[full selected-scenario development replay](../../docs/streaming-api-evidence/milestone-6d-2026-09-22/full-selected-replay-loopback-20260922/evidence.json)
matched 45 of 46 frozen profiles, with only `server-stateless` failing, and
validated all 48 final-tag golden messages. These are dirty-worktree
development observations, not immutable-candidate release evidence.

The paired assessment's `adopted: false` describes the diagnostic tool's
proposal-only state when that artifact was generated. This later owner
decision does not rewrite that raw assessment or change its verifier. The
official CLI result remains exit **1**, its two raw `FAILURE` rows remain
visible, and the current strict adjudicator and release runner still fail
on them. This decision does not authorize a fabricated Sampling response, a
Sampling implementation, a whole-scenario exclusion, a changed expected-check
profile, or a claim that the official suite passed. The separate
[conformance toolchain security review](UPSTREAM_DEPENDENCY_REVIEW_2026-09-13.md)
remains open.

The classification is invalid on any suite or source-assertion identity drift,
new or reshaped official failure or warning, missing or changed Elicitation
control, changed fixture/candidate binding, or other failed condition in the
proposal. A later immutable candidate must repeat the complete selected
official run, retain the raw failure verdict, satisfy the exact check-level
policy and independent socket control, and validate the final-tag goldens.
Runner integration and that candidate proof remain pending; neither is
supplied by this owner record.
