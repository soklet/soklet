# MCP Apps, Skills, and API freeze review — 2026-09-25

**Disposition: HOLD.** The scoped server-side Skills qualification supports a
bounded Skills signature freeze. The remaining API signature differences are
the previously classified Apps surface. This review does not accept the Apps
signatures, narrow the full Apps release gate, or approve publication.

## Candidate and checks

The local review used owner commit `4753ef23eb64986d61e7d0d2a27e960315752f3b`.
The Java 17 main JAR has SHA-256
`f91ab625a5a7fb506d0066dfc73cc942e96ffa20f71fd6f368d945b9d701ce51`.
The full japicmp report has SHA-256
`8ce09a0235b22980ce13ec6e87bcfdb1f732a9f14f80671441515a694a20a389`.
These are local development artifacts, not immutable release-candidate receipts.

The pushed [CI run #370](https://github.com/soklet/soklet/actions/runs/36161857442)
passed all three JDK test jobs, static analysis, packaged consumers, the MCP
conformance candidate job, soak smoke, and deterministic fuzz corpus replay.
The API freeze job failed as expected. Its nightly fuzz jobs were skipped because
this was a push run, not a scheduled or manually dispatched nightly run.
The later [push run on owner commit `fd52e566`](https://github.com/soklet/soklet/actions/runs/36174604692)
has the same disposition: the API freeze job stopped at the reviewed Phase 4
169-current/6-snapshot signature delta, every other executed job passed, and
nightly fuzzing was skipped. It is not a replacement for the missing
feature-branch manual nightly run.

The release-only lifecycle inventory and its 134-case self-test now pass. The
inventory includes the new bounded Skills development fixture. It also catches
up with two earlier CI test edits: a cancellation callback wait increases the
reviewed catalog-policy control allowance from 25 to 30 seconds, and the
subscription metrics test's changed assertions refresh its source identity.
The later Skills runner guard shifted four line-addressed discovery records
without changing their contents or the 8,360-candidate/437-path counts.
These edits do not change production behavior or make this local matrix an immutable
candidate gate result.

## Skills

The current Java 17 classes completed the pinned 402-case YAML corpus without
crashes: all 308 corpus-valid inputs syntax-parsed and all 94 corpus-invalid
inputs rejected. The approved metadata model rejected 43 valid YAML values;
three previously reviewed corpus/specification event disagreements remain
visible. The corpus receipt correctly says `qualified: false`. The Java 17
focused Skills selection passed 410 tests. The JDK 25 checked-in fuzz seed
replay passed 21 tests; no long local fuzz campaign was run.

The unmodified Inspector 2.7.0 CLI passed the example's nine Skills
retrieval/integrity probes against this JAR. The Skills proxy self-tests passed
14/14 after correcting a test race between the client's completed response and
the proxy's final delivery flag. Those probes alone do not establish agent
activation, consent, execution, or a paginated/authorized/localized host matrix.

A separate public-API fixture and the **unmodified Inspector 2.7.0 CLI** passed
the scoped operational host matrix against the same JAR; its sanitized receipt
is `evidence/skills-host-matrix.json` (SHA-256
`c0717694782e9e34b0449c5778a50926a3006b82a9e8e672d324e177c615b5d5`).
The CLI walked two pages for English and French admitted callers despite
opposing `Accept-Language` headers, saw an empty list for a denied caller,
verified four listed Skills including byte-identical shared/nested files, read
the caller's variant by exact URI, retrieved an authorized unlisted Skill, and
rejected tampered root frontmatter and digest. Direct live-HTTP controls checked
single-use caller-bound cursors, denied get/read, and revocation before another
file read; the CLI also failed after revocation. The fixture and runner used only
candidate public APIs, isolated client state, fixed bounds, and clean shutdown.
The runner rejects an existing or input-overlapping results directory before
creating private state; a negative existing-directory probe failed before
mutation. A restricted-sandbox attempt could not start the loopback listener;
the local-process-permitted rerun passed.
The result establishes this named CLI's server-side operations, not agent
selection, approval, activation, instruction execution, production OAuth, or an
immutable release-candidate receipt.

The reviewed Skills API tranche adds exactly 90 Phase 4 signature records and
the one Phase 6 `McpLocalizationRequest.getSkillListCursor()` record to the
checked-in snapshots. All 91 are current-only records from the same full
japicmp report; no common record changed and no reviewed Skills record was
removed. The tranche covers the 14 Skills types and nested types, the two
`McpOperationType` values, endpoint/server configuration, bundle and manifest
inspection, access/discovery callbacks, pagination, localization, and variant
selection. The source-level declarations and parameter names were checked
against `PUBLIC_API_SIGNATURES.md` and the approved implementation plan;
`McpSkillBundle` paths remain logical `String` values. This freezes Soklet's
server-side Skills surface for the scoped claim above, not any agent-host
activation behavior. Phase 6 now verifies exactly; Phase 4 remains red only for
Apps. The snapshot SHA-256 values are
`cdf12276e505dab48abddf06ac5ce76bd368b3cdc36ccdc859747fbbe7a2c24e`
(Phase 4) and
`ec59144e1e225add0ae0f19eaadb951d7c9c972f34af25001dad51ba79047770`
(Phase 6).

The owner already approved the YAML 1.2 core/JSON-compatible metadata boundary
in the [Skills implementation plan](../../../SOKLET_MCP_UPCOMING_IMPLEMENTATION_PLAN_2026-09-16.md)
on 2026-09-20. The current pinned corpus shows all 308 valid inputs pass syntax
and all 94 invalid inputs reject, with no crash. The 43 valid YAML values
rejected at metadata resolution are exactly the approved JSON-type and custom-tag
restrictions (27 `TYPE`, 16 `UNSUPPORTED_TAG`), not hidden syntax gaps. The three
normalized-event disagreements have separate source/specification reviews in
`verification/skills/README.md` and remain visible rather than counted as
matches. This accepts the documented metadata profile for the scoped server-side
Skills claim; the corpus's `qualified: false` remains correct because the
report-only runner does not certify general YAML compatibility.

The [feature-branch manual run #36176935272](https://github.com/soklet/soklet/actions/runs/36176935272)
completed successfully on owner commit `fd52e5661d9825a288685337d7bc9d9d6020cb75`.
Both five-minute Skills targets (`mcp-skill-frontmatter` and
`mcp-skill-yaml-stream`) passed their fuzz commands, canonical receipt producers,
and uploads. GitHub lists both retained target receipts and Jazzer artifacts.
The receipt artifact IDs are `10882313832` (frontmatter) and `10882970736`
(YAML stream); the corresponding Jazzer artifact IDs are `10882733403` and
`10882965709`. The history-assembly job also passed. Its complete seven-day
history bundle was not produced from this first run; that advisory history does
not block 4.0.
The owner supplied both target receipt ZIPs from the run. Each archive passed
integrity checks and contained exactly its expected canonical JSON receipt;
the exact payloads are retained in `evidence/skills-fuzz-frontmatter.json` and
`evidence/skills-fuzz-yaml-stream.json`. Both receipts match the registered
target IDs and ordinals, the 300-second policy, the pinned toolchain hash
`fc54799de37cf49536ae84c848424b4a06df522057e53fcf81e23319ddeeb849`,
and the registered Surefire report. Each records `PASS` and 301 measured
seconds. The supplied ZIP SHA-256 values are
`09c139bd757a0e0ff2c668b956df39ab18a3d94e3e181984bbc566ba4dbc0db4`
(frontmatter) and
`6a58324e6d597072119c6936ce55b0714f24333e1f72b2a86adde65ca8543fdf`
(YAML stream). Receipt-content review is complete. The separate Jazzer
artifacts, including raw corpus contents, were not supplied, so this review
does not independently inspect generated inputs or any raw finding files.

## Apps

The public-API server fixture passed 12 scenarios and 34 requests against the
same JAR. A separate real Soklet listener passed 12 authorization requests
across allowed, denied, changed-tenant/locale, and revoked states. The
unmodified Inspector 2.7.0 browser profile rendered and refreshed
the App, passed its checked UI and authentication controls, and cleaned up every
owned process and private profile. Its aggregate status remains
`BLOCKED_HOST_AUTH_FALLBACK`: after Soklet's intentional subscription-policy 403,
Inspector tried OAuth discovery. The receipt reports
`renderRefresh: PASSED_NARROW_OBSERVATION` and `fullHostQualification: false`.
This is neither a full real-host pass nor a general security/permissions claim.

The [Inspector 2.8.0 auth interceptor](https://github.com/modelcontextprotocol/inspector/blob/2.8.0/core/mcp/node/authChallengeFetch.ts)
still intercepts every HTTP 401/403, and its
[challenge parser](https://github.com/modelcontextprotocol/inspector/blob/2.8.0/core/auth/challenge.ts)
still maps a plain 403 to `unauthorized`. A 2.8.0 version change alone has no
reviewed evidence of resolving this blocker.

The owner retained the full Apps gate on 2026-09-25. OAuth is optional in the
[MCP authorization specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)
and is not intrinsic to the Apps extension. Soklet supplies per-request admission
and safe response-challenge transport; an application integrating OAuth owns
token validation, protected-resource metadata, scopes, and the authorization
server relationship. The fixture's unchallenged subscription-policy 403 is a
permanent denial, not a request for additional OAuth scopes. Adding an OAuth
service to the fixture would not resolve this host classification issue.

An uncommitted Inspector 2.8.0 patch and upstream issue draft are retained as
`inspector-2.8.0-plain-403.patch` and `INSPECTOR_403_UPSTREAM.md`. Three focused
regression tests failed against the untouched 2.8.0 tag and passed with the
patch; all 65 targeted parser/fetch tests, the TypeScript build, targeted ESLint,
and formatting checks passed. The patch makes a 403 with no
`WWW-Authenticate` header pass through rather than starting OAuth recovery. It
has not been submitted upstream or released, and cannot count as a real-host
qualification pass.

## API comparison

The exact released-3.5.1 incompatibility ledger passes with 707 records. The
current public-owner partition contains 377 owners. Phase 5, Phase 6, and
provisional Tasks signatures match their reviewed snapshots exactly. No common signature
record changed. The only remaining differences are:

| Snapshot | Current-only | Reviewed-only | Classification |
| --- | ---: | ---: | --- |
| Phase 4 | 79 | 6 | Apps/result sanitation |
| Phase 6 | 0 | 0 | Skills cursor accepted |

The Apps count includes the separately reviewed immutable
`ContentSecurityPolicy.defaultInstance()` addition. The public-evolution
verifier is consequently held by the two Apps enum owners absent from the old
Phase 4 snapshot. Roadmap, metadata-builder, and transport-dependency verifiers
pass.

## Remaining decisions and evidence

1. Complete the owner-retained full Apps gate. This requires an upstream host
   fix or another named real host plus the remaining security, permission,
   authorization, and localization matrix.
2. Preserve the scoped Skills operational/host matrix and approved YAML metadata
   boundary when the owner commits this tranche. Agent activation remains
   outside the claim. Inspect the retained Jazzer artifacts if raw corpus or
   finding-file review is required; the passing target receipts alone do not
   contain those files.
3. Review and capture the qualified final Apps signatures, then rerun
   the aggregate freeze and immutable candidate gates from the owner's commits.

The `evidence/` files retain reduced, sanitized local receipts for these runs.
The Apps copies omit the disposable host configuration and temporary shell paths.
The original complete local receipts remain under
`/private/tmp/soklet-skills-qual-2w4WlVjf/` for this workstation review.
