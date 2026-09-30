# MCP Apps preparation review — September 29, 2026

**Disposition: the server checks and scoped corrected-host browser checks pass.**
The unchanged released Inspector still fails on its plain-403 recovery path.
The owner's direction in this chat permits that upstream defect to remain a
documented release limitation. Its raw failure remains a failure; the isolated
patch results remain experimental. Immutable candidate gates and publication
approval are still pending.

## What changed

The Apps qualification fixture now explicitly enables subscriptions for
`2026-07-28`. Its tools, resources and endpoint already named that version, but
the subscription selector was omitted after protocol versioning became explicit.
The fixture continues to deny subscriptions through its existing authorizer.

A new simulator regression and a live HTTP regression require that a valid
`subscriptions/listen` selection reaches HTTP 403, returns a bounded JSON-RPC
error and completes without `WWW-Authenticate`. The simulator regression failed
before the fixture correction and passed afterward. The strict contract runner
now requires all 13 scenarios and 35 requests, including this check, and rejects
the older incomplete 12/34 summary.

The four existing experimental runners now bind the exact current main JAR
instead of their earlier experimental artifact hashes. Their success conditions,
dependency pins, one-file Inspector patch, deadlines and cleanup requirements
remain unchanged. Lifecycle discovery and active version-context hashes are
refreshed for the reviewed edits; historical governance stays intact.

These are verification-source changes. The production Java sources, public API,
POM and packaged resources are unchanged.

## Exact inputs

The core artifact is from pushed owner commit
`ebc4aa720305f328d89e992086d8eeb30959966f`, tree
`52390bb1cdb17ae4a855a5804c838b48516cd518`. Independent clean builds at that
commit produced byte-identical main, source and Javadoc JARs. This slice used
the main JAR SHA-256
`64253678715421002d70ec2ea049650429a7af5ec31700cf03e6f5e1d784a727`
and original POM SHA-256
`90a0e38702ed10392a1d77ccc556995575d9eef7b601537b3d7a4a68790c7324`.
The uncommitted fixture/harness edits are separately identified in each receipt;
they are preparation evidence, not evidence from a final owner candidate commit.

Host inputs are Inspector 2.7.0, Apps SDK 2.0.0, the existing reviewed exact
dependency lock, Node 26.5.0, Corretto 17.0.20.10.1 and Chrome 154.0.8037.59.
The unchanged installed tree has SHA-256
`8c0b1ed101c4c7e7497aa4aaba7e953b03a44bc58179308db1613a988d5a2b8d`.
The independently copied patched tree has SHA-256
`6546d769cd9fd869b7608c774b9dcfc39b3050d851c57ad83b439cdcbb84ebcb`.
Exactly one backend file differs, using the previously reviewed 403-only patch;
its SHA-256 is
`405da5e71b887403bb53ff2e3984cec631a1138f50662ad199dfb8e536dcd47a`.
The patch preserves 401 and explicit Bearer insufficient-scope recovery.
The standalone shell has SHA-256
`3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60`.

Each browser run used a new disposable headless profile. Profiles ran
sequentially against loopback fixtures. All final receipts report unchanged
inputs, complete process cleanup and removal of owned private state.

## Fresh results

| Check | Recorded outcome | Observation |
| --- | --- | --- |
| Candidate public-API simulator | PASS | 13 scenarios / 35 requests, including the supported subscription's unchallenged denial. |
| Real HTTP authorization | PASS | 13 requests cover denial, tenant/language changes, resource access and revoked credentials. |
| Unchanged released Inspector | FAILED / `APPS_BROWSER_EXCEPTION` | App render and refresh were observed; after the correct policy 403, the trace retained 11 refused OAuth-related requests and the browser reported an exception. |
| Isolated patched render/refresh | Experimental PASS | Real DOM selection, SDK-backed refresh, literal hostile-looking text, server-selected data and zero browser exceptions. |
| Same-App caller transitions | Experimental PASS | Portuguese/beta replaces English/alpha; a later permission denial clears the view and requires reopening. |
| CSP empty allowlists | Experimental PASS | Six ordered browser observations; trusted enforcing `connect-src` and `img-src` violations; four successful live controls and zero App requests reach the canary. |
| CSP declared origin | Experimental PASS | Eight ordered observations; declared App requests reach the canary, undeclared host-origin requests produce trusted CSP violations; six live canary requests. |
| Apps capability OFF | Experimental PASS | Four direct controls plus ordinary tool rendering; App-only helper and Apps UI stay absent throughout observation. |
| MIME capability boundary | Experimental PASS, direct HTTP | 54 exact matrix observations across three fresh batches; 60 HTTP requests including credential controls. This uses no browser. |
| Requested geolocation | `BLOCKED_HOST_PERMISSION_POLICY` | Inspector receives the hint and allows it on the inner frame, but its outer sandbox does not delegate it. Camera, microphone and clipboard-write stay denied. No user grant or device access was requested. |
| Open-App credential revocation | `BLOCKED_HOST_AUTH_FALLBACK` | Soklet returns 401 on the next refresh and the same App clears its view. Inspector begins OAuth recovery; the disposable fixture has no OAuth service. No browser exception occurs. |

All patched browser receipts retain `experimental: true`,
`fullHostQualification: false` and `releaseCandidateEvidence: false`. The
direct MIME receipt retains `browserExercised: false` and
`hostQualification: false`.

The changed runner self-tests pass 43/43. The lifecycle inventory passes with
its existing 54 accepted occurrences, 8,392 discovery candidates and 441 paths.
The final-stage version inventory passes, along with its 46 fixture cases and
five external-boundary negatives. Its baseline governance hash is unchanged.

The released/patched comparison supports attributing the original denial-path
failure to Inspector's 403 handling: the isolated one-file correction eliminates
both OAuth traffic and the browser exception while the same candidate and App
render and refresh successfully. This is a scoped inference from the comparison,
not a claim that an upstream Inspector fix has shipped.

## Release scope and remaining work

The approved plain-403 exception permits release preparation to continue while
the upstream issue is open. Fresh evidence also distinguishes two separate
limitations: this host does not delegate the requested geolocation feature, and
the test fixture cannot complete OAuth recovery after a legitimate 401. Soklet's
metadata, authorization and data-clearing observations remain visible beside
those aggregate blocked outcomes.

Full unchanged-host qualification is still open. This review covers the named
host/browser profiles and two CSP directives; broader host permission support,
browser RTL/localization coverage, idle-view invalidation, production OAuth and
other real hosts have no new full qualification claim here.

Next, use the owner's pushed downstream commits for the release pins, commit
this verification tranche, and run the required immutable candidate gates from
those exact source identities. Current local PASS results cannot replace those
gates.

## Retained evidence

[`evidence/index.json`](evidence/index.json) binds byte-identical copies of each
sanitized receipt, trace and bounded regression output by SHA-256. The index
explicitly records preparation scope. Original failures and blocked outcomes
are retained alongside experimental passes. The complete temporary run directory
is `/private/tmp/soklet-apps-20260929.b0n4w0iz`.

The allowlist receipt is stored losslessly as gzip, with both file and decoded
payload hashes in the index. Its raw timestamp contains an incidental old-version
substring; compression preserves the original receipt without broadening the
version-transition verifier's reviewed text exceptions.
