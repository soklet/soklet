# Milestone 6d — P0-C check-level disposition

Status: **owner accepted the narrow classification on 2026-09-22; runner
integration and immutable-candidate proof remain pending**. The separate
[owner decision](../conformance/official/P0C_CHECK_DISPOSITION_2026-09-22.md)
adopts the check-level policy below without changing the official MCP suite,
its expected-success profile, or its failed verdict. This does not yet close
P0-C, MCP-G2, the conformance security review, or release qualification.

## Accepted classification

For the pinned `@modelcontextprotocol/conformance` `0.2.0-alpha.11` suite at
commit `a983ba93c91e0bb31d0b6849eeb52f0ad1083107`, with reviewed source-tree
SHA-256 `e63d6f13100504101afdfd5cfd084c92d801e2b4466d68965aa2e0c48a87998d`,
classify **only** the following two `server-stateless` results as an upstream
harness limitation when all conditions below are met:

| Check ID | Required raw result |
| --- | --- |
| `sep-2575-server-rejects-undeclared-capability` | `FAILURE`, `details.untestable: true`; the suite probes unregistered `test_missing_capability`, and request 401 receives JSON-RPC `-32602` rather than exercising missing-capability rejection. |
| `sep-2575-missing-capability-http-400` | `FAILURE`, `details.untestable: true`; the same unavailable diagnostic produces HTTP 400 without exercising `-32021`. |

This classification is not a pass for either assertion. The pinned official CLI
must still exit **1** and its two `FAILURE` rows must remain in the unmodified raw
`checks.json`. The existing `server-stateless.phase5.v1` expected-success
profile and strict `adjudicateChecks` remain unchanged and continue to fail.
The decision describes a reviewed limitation of those upstream probes,
not a clean official conformance pass.

The [pinned upstream assertions](https://github.com/modelcontextprotocol/conformance/blob/a983ba93c91e0bb31d0b6849eeb52f0ad1083107/src/scenarios/server/stateless.ts#L703-L814)
assume Sampling in this generic capability test. Soklet's
selected feature set instead supports Elicitation. A fabricated Sampling
response, alias for the unavailable tool, broader scenario exclusion, or
expected-failure flag would hide what the suite actually tested. An upstream
correction that can exercise a genuinely supported declared capability remains
the preferred resolution. A later suite repin requires its own complete review;
this proposal expires on any suite/source identity change.

## Conditions for a reviewable disposition

1. Verify the exact pinned suite source, built CLI, package/lockfile, protocol,
   scenario selection and frozen profile with the existing manifest verifier.
   Retain the complete, original 30-check `server-stateless` file, official
   stdout/stderr, exact official child exit status, and their hashes. A timeout,
   signal, missing output or transformed result is not acceptable.
2. Require exactly these two `FAILURE` occurrences with the diagnostic shape
   above. Every other check must match the frozen profile's complete multiset,
   including duplicate counts and the exact reasons for the two existing
   `SKIPPED` list-change checks. Any other `FAILURE`, `WARNING`, missing or
   extra check, changed failure detail, or wire harness error fails closed.
3. Against the **same live public-fixture endpoint and candidate JAR** used for the official
   run, retain an independently supervised real-socket request/response receipt
   for `test_missing_elicitation_capability`: with no declared client
   capability, HTTP 400, matching JSON-RPC ID, error `-32021`, and exact
   `requiredCapabilities.elicitation.form`; with form Elicitation declared,
   HTTP 200, matching ID and completed tool result without error. Both calls
   must carry the selected protocol metadata and be joined to the official
   run by explicit candidate, fixture and run identities. One supervised
   fixture lifetime must contain the official probe and both socket controls.
   This establishes the
   generic behavior that the Sampling-bound probe did not exercise.
4. Applying a later accepted classification to candidate conformance still
   requires a fresh full selected-scenario run and independent final-tag
   validation of all 48 golden messages. The other 45 scenario profiles must
   retain their ordinary strict result; this disposition has no effect on
   them. Existing `SKIPPED` results remain noncoverage. A targeted
   `server-stateless` capture alone cannot close the aggregate gate.

The sidecar proposal verifier in
`conformance/official/proposals/p0c-disposition.mjs` is deliberately separate
from the official runner. Its successful outcome means **proposal evidence is
reviewable**, not that the official gate passed or applied the owner's later
decision. Mutation tests must reject pin drift, extra/missing or reshaped
failures, all unaffected-check drift, altered skips, a zero or missing official
exit, missing socket controls, and mismatched candidate/fixture/run identities.

## Fresh current-candidate observation

The [final paired capture](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/proposal-assessment.json)
ran against one live public fixture and the exact reviewed suite. The suite
checkout's source, package/lockfile, vendored schema and built CLI passed the
existing pin verifier. Node `26.5.0` and npm `11.17.0` matched their pins, the
infrastructure and runner self-tests passed, and the independent final-tag
validator accepted all 48 golden messages. The current packaged JAR's SHA-256
was `3bd93c5f449d2ab5970152b4a45997b5d79b4399ae0c7dc0dbee0f448e51ba24`;
the fixture source and compiled-class-tree digests are in the receipt.

The [unaltered official checks](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/official/checks.json)
have SHA-256 `63106103f16aef80aa7cab71b8743130058adbd3a993dcbb3778510ddeff0d75`:
30 checks, consisting of 26 `SUCCESS`, the exact two `FAILURE` rows above,
and two `SKIPPED`. The sidecar verifies this copy byte-for-byte against the
suite's original result file. The official CLI exited **1**, with no timeout,
signal or output failure. The unchanged strict adjudicator still rejects the first
forbidden failure. Against that same fixture endpoint, the [raw Elicitation
control](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/control/control-receipt.json)
returned HTTP 400 / `-32021` with exact required form capability when
undeclared, and HTTP 200 / complete when declared. The fixture reported a
clean stop and exited zero. The official and control receipts share the run ID,
endpoint, candidate JAR, fixture source and compiled-class identity. The
[capture status](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/capture-status.json)
and [raw-control manifest](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/control/capture-raw-manifest.json)
retain the process outcome and byte hashes of the socket exchanges.

The separate [proposal assessment](streaming-api-evidence/milestone-6d-2026-09-22/p0c-capture-final-20260922/proposal-assessment.json)
reports `PROPOSAL_REVIEWABLE`, `adopted: false`, official `FAILURE`, and 28
unaffected checks matching their frozen multiset. Its mutation self-test
rejects 56 changed-pin, raw-output, exception-shape, unaffected-check, skip,
CLI-outcome, endpoint, fixture-identity and socket-control cases. The sidecar
also checks the retained socket body/header bytes against both capture
manifests. Its `adopted: false` value records the assessment tool's state when
the capture was produced; it is not changed by the later owner decision. This is
a development capture from the dirty streaming worktree, not immutable release
candidate evidence. The earlier first capture remains in
the evidence directory as a historical diagnostic; the final capture was
repeated after the capture source was frozen.

The [full current-candidate development replay](streaming-api-evidence/milestone-6d-2026-09-22/full-selected-replay-loopback-20260922/evidence.json)
then exercised all 46 selected official scenarios against the same packaged
JAR identity. **45 profiles matched and only `server-stateless` failed**, at
the unchanged first forbidden check. Its 46 retained raw files contain 181
`SUCCESS`, the same two `FAILURE`, three `SKIPPED`, and one `INFO` check; all
48 final-tag golden messages validated. The aggregate status and process exit
are `FAILED`/1. This run used a separate fresh fixture per official scenario,
as the existing runner requires. It is not the same fixture lifetime as the
targeted paired-control capture, but both runs used the same JAR and fixture
source and the compiled fixture class-tree digest matched. The first
full-replay attempt was blocked by sandbox socket-bind
permissions before exercising protocol behavior; its diagnostics are retained
under `full-selected-replay-sandbox-blocked-20260922/` and are not counted as
conformance observations.

## Historical evidence and remaining proof

The [September 19 retained retry](/Users/Shared/ai-shared/soklet/SOKLET_MCP_P2_COMPLETION_EVIDENCE_2026-09-19/README.md)
contains a byte-identical raw `server-stateless` `checks.json` (SHA-256
`776c7ac60b9f13c13a8afd2dfb7f6572c4a00124e291fdc13c09211f16dc6eca`)
and the full 46-scenario aggregate. Its 30 stateless checks are 26 `SUCCESS`,
the exact two `FAILURE` rows above, and two `SKIPPED`; 45 of 46 frozen profiles
matched and all 48 goldens validated. The aggregate is `FAILED` and remains
development-only evidence from a dirty candidate. The original runner did not
record the child CLI exit status before its strict adjudicator rejected the
first failure. It also did not preserve a same-candidate positive and negative
Elicitation socket exchange. The earlier September 17 investigation describes
both controls and exit 1, but its temporary raw files are no longer present.
Those prose findings cannot be substituted for the fresh raw receipts above.
The current streaming worktree's JAR and fixture source differ from the
September 19 run. The new paired and full-run observations made the specific
check-level proposal reviewable for the current development candidate, and the
owner accepted it on 2026-09-22. P0-C still requires narrow runner integration
and a later immutable-candidate replay; the official aggregate stays red. The
independent external-toolchain security disposition recorded in
`conformance/official/UPSTREAM_DEPENDENCY_REVIEW_2026-09-13.md` remains a
separate release blocker despite this check-level acceptance.

## Decision boundary and remaining work

The [owner disposition](../conformance/official/P0C_CHECK_DISPOSITION_2026-09-22.md)
accepts only the exact limited classification. The release runner does not yet
apply it: the strict official adjudicator rejects the raw `FAILURE` rows, and
the release contract requires terminal `PASSED`. Integrate this policy without
changing the raw official result or relabeling the official suite as passed,
then repeat the full selected run and controls against an immutable candidate.
A changed suite pin, changed source assertion, new failure, or failed independent
Elicitation control reopens the decision. MCP-G2 signature preflight can
proceed, but its final coordinated API refreeze and remaining
signature/inventory differences require P0-C resolution.
The [MCP-G2 preflight](../api/mcp/mcp-g2-preflight-2026-09-22.md) classifies
the current signature delta without changing the reviewed snapshots.
