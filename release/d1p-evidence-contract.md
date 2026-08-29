# D1p evidence contract

`release/d1p-evidence-config.json` freezes the deterministic inputs for the
cumulative D1p public-cutover preview. The generator and verifier are:

```sh
node scripts/generate-d1p-evidence.mjs --mode workspace \
  --external-root /absolute/path/to/the/shared-soklet-workspace
node scripts/verify-d1p-evidence-self-test.mjs
node scripts/verify-d1p-evidence.mjs --mode workspace \
  --external-root /absolute/path/to/the/shared-soklet-workspace
```

Generation is deliberately the last evidence-writing operation after the
candidate JAR, both japicmp reports, the generated incompatibility set, and
`target/mcp-api-freezes/*.signatures.jsonl` are final. It writes the sibling
external manifest, the three leaves, and then the root. It never commits,
stages, or writes a preview commit/tree identity. Run verification without
another clean build after generation.

Candidate mode is sibling-blind:

```sh
node scripts/verify-d1p-evidence.mjs --mode candidate --scope preparation
node scripts/verify-d1p-evidence.mjs --mode candidate --scope tracked
```

`preparation` derives the tracked-blob and current canonical-semantic leaves in
memory without requiring preview artifacts or generated manifests. `tracked`
additionally requires and verifies the tracked leaves and root, but
intentionally does not claim to reproduce the retained preview-evidence bytes,
the preview SNAPSHOT JAR, or the sibling manifest. A full Gate 8/D2 check uses
workspace mode on the current proposed preview. Candidate mode rejects
`--external-root` and has no code path that resolves a sibling path.

Accepted D1 remains the frozen base `B`. Before G3, `HEAD` may advance through
any number of ordinary commits, but `B..HEAD` must be one linear, non-merge,
first-parent chain. Generation and workspace/full verification derive the
complete cumulative delta from `B` to the current working tree, including
staged, unstaged, and nonignored untracked paths. Candidate/tracked
verification requires a clean committed descendant, requires every D1p root,
leaf, configuration, contract, and tooling path at `HEAD`, and rederives the
cumulative delta from raw `HEAD` blobs. A stale evidence set therefore fails
after a normal remediation commit; regenerating it and committing the refreshed
bytes normally produces the next valid proposed preview. G3 alone binds the
final exact `P`, `tree(P)`, and evidence tuple. A post-G3 change invalidates
that approval and requires refreshed evidence and a replacement G3 decision,
not history rewriting.

After D2, one dedicated ordinary child of `P` adds
`release/d1p-approved-preview.json` as its sole change. That canonical seal
records `P`, `tree(P)`, the durable G3 approval reference, and every raw
root/leaf/external digest in the approved tuple. The verifier authenticates the
seal from Git history: the path must have exactly one change, that change must
be its addition as the seal commit's only changed path, the seal commit must
have exactly `P` as its sole parent, and the seal must remain present and
byte-identical thereafter. Before the seal exists, candidate mode binds
`HEAD`; after it exists, candidate mode derives the tracked leaf from raw `P`
blobs, keeps the tracked root/leaves/configuration byte-identical to `P`, and
compares current compiler-derived and protected semantics with the semantic
leaf at `P`. This permits the specifically authorized post-D2 non-public
owners without rebinding or regenerating approved evidence. Workspace/full
generation and verification are pre-G3 operations and fail once the seal
exists.

After exact `P` has landed at D2, generate the sole seal byte set from the
durable G3 receipt:

```sh
node scripts/generate-d1p-approved-preview.mjs \
  --g3-approval-receipt /absolute/path/to/the/G3-receipt
```

The generator first requires clean candidate/tracked verification at `P`,
then parses a canonical G3 receipt with exactly `approvedAt`,
`approvedPreviewCommit`, `approvedPreviewTree`,
`canonicalSemanticManifestSha256`, `decision`, `externalEntrySetSha256`,
`externalManifestSha256`, `formatVersion`, `ownerApprovalReference`,
`previewEvidenceManifestSha256`, `rootManifestSha256`, and
`trackedBlobManifestSha256`. `formatVersion` is `1`, `decision` is `APPROVED`,
`approvedAt` is a real UTC whole-second instant, and every candidate identity
and digest must equal the verified `P` tuple. A stale-candidate, wrong-tuple,
noncanonical, pending, or structurally different receipt fails before output.
The generator hashes those exact receipt bytes and writes only the untracked
seal path. It never stages or commits. Commit that one file as the seal
commit's sole change.

## Canonical files

All JSON files use UTF-8, LF only, two-space indentation, recursively
lexicographic object keys, deterministic array order, and exactly one final
LF. Unknown, missing, reordered, or duplicate fields fail.

`release/d1p-tracked-blobs.sha256` has one record per line:

```text
<64 lowercase hexadecimal SHA-256><two ASCII spaces><POSIX core-relative path><LF>
```

Paths contain no control character, backslash, absolute component, `.` or
`..` component. They are unique and sorted by the unsigned UTF-8 bytes of the
path. The SHA-256 covers the exact candidate file bytes. The path set is every
regular, non-symlink candidate path that differs from `baseCoreCommit`, plus
every non-ignored untracked candidate path in workspace mode, excluding exactly
the three configured manifest paths. Deleted paths have no candidate blob and
are bound only by the final Git tree. Workspace rows derive from the complete
current working preview; candidate rows derive from raw blobs at exact `HEAD`.
After the approved-preview seal, candidate rows instead derive from raw blobs
at its recorded `P`. This deliberately broad rule includes every
reviewed API/freeze, reflection/nullability, active-ledger, conformance,
fixture, source, test, documentation, workflow, and tooling byte without a
manually maintained selective list.

`release/d1p-canonical-semantic-digests.json` has exactly `formatVersion` and
`tupleSets`. Each tuple-set object has exactly `count`, `name`, `sha256`,
`sourcePaths`, and `tuples`. Names and tuples are bytewise sorted and unique.
The digest is SHA-256 over every tuple's UTF-8 bytes followed by one LF. The
sets are independently derived from generated Phase 4/5/6 compiler signature
JSONL, reviewed include inventories, the generated and reviewed
incompatibility JSONL, the non-MCP allowlist, and the three exact
reflection/nullability constants in the reflection contract. Its additional
`protected.post-d2` tuple set binds `path|raw-sha256` for the frozen
`protectedPostD2Paths`: exact Phase ledgers, allowlist, incompatibility ledger,
reflection contract, official conformance build/runner, public fixture, and
transport-composition fixture bytes. Generated and
reviewed signature/incompatibility tuple sets must agree before a manifest can
be produced. The configuration also requires the accepted D1p cardinalities:
133/36/64/0 owners, 1,029/179/421 signatures, 618 incompatibility rows, and 39
non-MCP allowlist rows. The `freeze` set is a compact cross-check of all per-phase
counts/digests and the owner, allowlist, incompatibility, and reflection
digests. The API-freeze wrapper separately proves that the allowlist is the
exact partition of the generated full japicmp report; the allowlist tuple set
therefore names only the allowlist bytes it actually parses.

`target/d1p-preview-evidence.json` has exactly `artifacts` and
`formatVersion`. Each ASCII path-ordered artifact has exactly `path` and
`sha256`; the four paths are fixed by the configuration. The file is ignored
and untracked by Git in the proposed preview and every verified candidate, and hashes
neither itself nor another D1p manifest. Candidate verification does not
require this retained Gate 8 provenance leaf or its fixed SNAPSHOT JAR.

`mcp/SOKLET_4_0_D1P_EXTERNAL_MANIFEST.json` and
`release/d1p-public-cutover-manifest.json` use the exact schemas in the
integrated plans. The external top-level keys are exactly `baseCoreCommit`,
`baseCoreTree`, `entries`, and `formatVersion`; each entry has exactly
`allowedPostD2Owner`, `baseSha256`, `owner`, `path`, `previewSha256`, and
`reason`. The root top-level keys are exactly `baseCoreCommit`, `baseCoreTree`,
`canonicalSemanticManifest`, `externalEntrySetSha256`,
`externalManifestPath`, `externalManifestSha256`, `formatVersion`,
`previewEvidenceManifest`, and `trackedBlobManifest`; each leaf binding has
exactly `path` and `sha256`. The 16 external rows, their base hashes, ownership,
reasons, and later-writer policy are exact configuration, closing the former
free-text ambiguity. The configuration additionally carries the config-only
`changeKind` field, exactly one of `added`, `deleted`, or `modified`. It is
validated but stripped before policy comparison because it is deliberately not
part of the external-manifest schema. The 16 configured paths are ASCII ordered.
A modified entry has both hashes, an added entry has a null `baseSha256`, and a
deleted entry has a null `previewSha256`; both hashes may never be null. Workspace
mode requires added and modified paths to be present regular files, requires
deleted paths to be absent, and derives every non-null `previewSha256` from the
current sibling bytes. The entry-set digest is SHA-256 over compact canonical
`{"entries":[...]}` plus LF. The root is written last and binds the three raw
leaf hashes, external raw hash, and external entry-set hash. No leaf hashes
itself, another leaf, or the root; the root contains no self-hash and no D1p
preview commit/tree identity.

`release/d1p-approved-preview.json` does not exist before G3 or in `P`. Its
top-level keys are exactly `approvedPreviewCommit`, `approvedPreviewTree`,
`canonicalSemanticManifestSha256`, `externalEntrySetSha256`,
`externalManifestSha256`, `formatVersion`, `g3ApprovalReference`,
`previewEvidenceManifestSha256`, `rootManifestSha256`, and
`trackedBlobManifestSha256`. The six SHA-256 fields and both Git identities are
validated against raw blobs and tree identity at `P`; the remaining tuple
fields must equal the root manifest at `P`. `g3ApprovalReference` is exactly
`sha256:<64 lowercase hex>`, the raw digest of the durable G3 receipt supplied
to the seal generator. The D2 operator must match that digest to the already-
approved durable G3 decision before committing the seal; this local verifier
proves content and history, not owner identity, and the seal can never grant
approval. The seal is a post-D2 state marker,
not part of the approved D1p tree or manifests, and it can never be edited,
deleted, recreated, or used to authorize evidence regeneration.
