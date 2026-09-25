# MCP Apps, Skills, and API freeze review — 2026-09-25

**Disposition: HOLD.** The remaining API signature differences are the
previously classified Apps and Skills surface. This review does not accept those
signatures, narrow the release claim, or approve publication.

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
the proxy's final delivery flag. This does not establish agent activation,
consent, execution, or a paginated/authorized/localized host matrix.

The requested candidate-specific nightly Skills fuzz receipts are still absent.
The latest successful scheduled workflow ran on older `master` source without
the Skills targets. The earlier feature-branch manual run executed the targets
but failed to produce their canonical receipts; its producer fix is in the
current commit. A fresh manual dispatch on this feature branch is required for
that evidence and any finding review.

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

## API comparison

The exact released-3.5.1 incompatibility ledger passes with 707 records. The
current public-owner partition contains 377 owners. Phase 5 and provisional
Tasks signatures match their reviewed snapshots exactly. No common signature
record changed. The only remaining differences are:

| Snapshot | Current-only | Reviewed-only | Classification |
| --- | ---: | ---: | --- |
| Phase 4 | 169 | 6 | Apps 79/6; Skills 90/0 |
| Phase 6 | 1 | 0 | Skills localization cursor |

The Apps count includes the separately reviewed immutable
`ContentSecurityPolicy.defaultInstance()` addition. The public-evolution
verifier is consequently held by the two Apps enum owners absent from the old
Phase 4 snapshot. Roadmap, metadata-builder, and transport-dependency verifiers
pass.

## Remaining decisions and evidence

1. Set the supported Apps host claim. The full gate requires an upstream host
   fix or another named real host plus the remaining security, permission,
   authorization, and localization matrix. A narrower claim requires an explicit
   reviewed scope and its own passing matrix.
2. Obtain successful feature-branch nightly Skills fuzz target receipts and
   review findings. Finish the scoped operational/host matrix and accept the
   documented YAML metadata boundary.
3. Review and capture the qualified final Apps/Skills signatures, then rerun
   the aggregate freeze and immutable candidate gates from the owner's commits.

The `evidence/` files retain reduced, sanitized local receipts for these runs.
The Apps copies omit the disposable host configuration and temporary shell paths.
The original complete local receipts remain under
`/private/tmp/soklet-skills-qual-2w4WlVjf/` for this workstation review.
