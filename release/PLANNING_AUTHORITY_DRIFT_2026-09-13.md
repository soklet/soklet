# Historical-source provenance and current review anchors — 2026-09-13

This records the follow-up required by the owner-approved September 13 release
implementation plan. It does not replace the frozen planning snapshot or assert
that mutable source files are the bytes approved historically.

## Planning-authority snapshot

The read-only comparison of all nine sources named by
`conformance/soklet-4.0-planning-authority.json` found eight matching SHA-256
values: the approval document, all three active authority documents, and four
of the five immutable input documents.

The sole mismatch is `mcp/MCP_IMPLEMENTATION_PLAN_V10.md`, outside the core Git
repository:

- frozen historical SHA-256:
  `7f06083391cc9e436b1a044b97b99b62beabc0f1b98815a7ec1fc3943b5b05c4`;
- source SHA-256 observed on September 13:
  `6adec4a8b70e4739d2190bc89e93dea9b18f14b1fff0e237ccf434f482461184`.

Disposition: preserve the original snapshot and approval identities. The current
V10 file must not substitute for the historically approved input. Candidate
verification continues to consume the committed frozen snapshot, not mutable
sibling paths; a passing snapshot verifier does not certify that external V10
source bytes still match. No checksum has been repinned to conceal the drift.

The historical V10 bytes have not been recovered by this pass. Before the final
publication decision, the owner must either supply an archive matching the
frozen hash or explicitly accept this recorded historical-source retention gap.
That decision must be attached to release evidence rather than editing the old
approval. Current product fixes and tests do not depend on treating the changed
V10 text as new authority.

The owner's separate September 13 instruction requiring Soklet 4.0.0 in both
servlet adapters is reflected in current release contracts and adapter 2.0.0
documentation; it does not retroactively rewrite earlier compatibility receipts.

## Separate matrix-closure provenance pointer

The nine-source comparison above does not include the separate external matrix
named by `release/mcp-conformance-matrix-closure.json`. That registry and its
verifier preserve this September 1 provenance tuple:

- path: `mcp/MCP_CONFORMANCE_MATRIX.md`;
- recorded last-updated date: `2026-09-01`;
- frozen SHA-256:
  `e30767960da9d1ce7cad608faaa53dff85e59f1acffdd30b4a0073af08bec7ac`;
- external source SHA-256 observed on September 13:
  `818205f63f03b398f1b1109766380c803f260c1dd3966b8e6d79a5bb636bea6e`.

The candidate verifier checks the committed registry, its exact row
attributions, and current candidate-contained evidence. It deliberately does
not open this mutable sibling file. A passing matrix-closure result therefore
does not certify equality with the current external matrix. The frozen hash
has not been replaced, and the observed source must not be substituted for the
historical input. Recovery of the exact frozen matrix bytes remains unproven;
the owner must supply a matching archive or explicitly accept this separate
historical-source retention gap before publication.

## Sealed D1p preview: preserved evidence, separate documentation drift

D1p describes an already approved historical preview, not the final exact-
version release candidate. `release/d1p-approved-preview.json` binds preview
`95594f6594eddc499f3dc789d7a19dadf8efccf9`, tree
`6c3369484ff14786774186f93cb378fb06111709`. Its sole addition was the only
change in commit `e03ccf5dae3c1df02bd5feefb30fb5f4666ed4f4`, whose sole parent
is that preview.

The September 13 read-only check rederived all 278 tracked-blob rows from raw
preview blobs, matching the sealed digest
`413c01c6941c7baae757607a05c5cb9c079007d45c4b4261bf1fdf0d32ebf920`.
The root and canonical-semantic manifests, configuration, and historical
generator/verifier bytes still match the preview; the external manifest also
matches its sealed digest. Later source changes do not make these historical
tracked rows stale. This check did not reproduce the ignored preview JAR or
its generated report artifacts and is not a full D1p acceptance rerun.

There is a distinct, pre-existing documentation discrepancy. Commit
`a3b139ffed6dd1623f996a8face54972264dfd47` on September 6 changed the
cardinalities in `release/d1p-evidence-contract.md` from the preview's
133/36/64/0 owners, 1,029/179/421 signatures, 618 incompatibilities, and 39
allowlist rows to later values. The contract's preview SHA-256 is
`432e664c40be449f3c30dc20299fd07754464bbaf3279d47c6bcbf7baac1ca78`;
its current committed SHA-256 is
`7ed704698f032b10984e896e308c261aa39672f66b008a9d900d8bdcedf47ec5`.
Because that contract is itself on the historical verifier's immutable-path
list, candidate/tracked verification at current HEAD rejects its Git identity.
This pass neither restores nor rewrites that contract and makes no claim that
the entire present tree passes the historical verifier.

The historical contract's release-validator invocation instructions describe
the preview workflow. Current operation is documented in
[release validation](README.md#d1p-historical-preview-scope): final acceptance
uses the current exact-version API/freeze checks, transition census, and typed
26-gate evidence. Workspace generation/full verification is pre-G3 only and
explicitly rejects the existing seal. Do not regenerate the D1p manifests,
change the seal, or apply its historical linear-history rule as a new K/L
release requirement. Preserve the original preview and record the existing
contract discrepancy in the owner disposition rather than hiding it with new
hashes.

## Current finite-bound and privacy semantic anchors

These are current executable review anchors, not historical approval hashes.
The authorized A/B fixes and J inventory reconciliation intentionally changed
the two strict constants in `scripts/verify-release-matrix-closure.mjs`:

| Current inventory | Previous reviewed semantic SHA-256 | New reviewed semantic SHA-256 |
| --- | --- | --- |
| `conformance/mcp-finite-bound-inventory.json` | `919b9316cf17ea9bdcf9202077c0297a1602136b39f4df2cd54e24b4ede1aa3e` | `a7e4ecfe1942d541609f75a5ea2e50afa5a769144b50b48e6d0301c60754d97c` |
| `conformance/mcp-privacy-boundary-inventory.json` | `7f2d8704bffec7084cb5c94f7d9d1cd2872d7536a3d2b2f3cbce854c639d27d9` | `1693bae8d44192fd78fbe15ed1e87d9b6f485f218e7c3b9a475c5002f90e4f58` |

Finite-bound changes add the parser's compaction/framing regressions and
describe bounded notification comparison state and cleanup. Numeric limits and
the separately pinned finite exclusions are unchanged. Privacy changes classify
the private notification-comparison record's client-visible values, update
exact exception/signature occurrences, and classify six inspected queue-cleanup
calls as exact false-positive telemetry matches. They do not broaden matcher
rules, declare application values sanitized, or exempt arbitrary reset calls.

The new digests were derived after reviewing those source and inventory
changes; source completeness and strict semantic equality checks remain active.
The relevant behavioral tests, inventory verification, and negative self-tests
passed as development checks. They are not immutable-candidate receipts.

## Required owner disposition record

No historical-source retention-gap acceptance is asserted by this document.
Before the publication decision, retain an external, append-only owner record
that identifies this document and its SHA-256, the candidate commit/tree, the
owner and UTC decision time, and separately:

1. the V10 frozen/observed hashes and either a verified archive reference or
   explicit acceptance of the unrecovered historical-source gap;
2. the matrix frozen/observed hashes and the same archive-or-acceptance decision;
3. acknowledgment of the sealed D1p preview scope and the September 6 contract
   discrepancy, without claiming current D1p verification or modifying its seal.

Retain the record's SHA-256 with G4/U9 evidence and require it in the
[G5 authorization record](G5_RELEASE_RUNBOOK.md#required-g5-authorization-record).
Missing or ambiguous decisions stop publication. This operator requirement does
not add a release gate, change any gate status, waive external-toolchain
security review, or replace the approved artifact/downstream freeze sequence.
