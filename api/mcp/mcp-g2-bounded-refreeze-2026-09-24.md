# MCP-G2 bounded API review — September 24, 2026

**Disposition: partial signature acceptance; full MCP-G2 remains HOLD.** This
review records the current 3.5.1-to-4.0 compatibility comparison and accepts
only the already classified N0 naming, P1b subscription, and P2 Completion
signature changes. It does not qualify the Apps or Skills API, approve a release,
or replace an immutable candidate run. Historical checkpoint hashes and
rationales remain unchanged.

The fresh Java 17 build used the current source tree and produced main JAR
SHA-256 `f91ab625a5a7fb506d0066dfc73cc942e96ffa20f71fd6f368d945b9d701ce51`.
Its full japicmp report is SHA-256
`3783f228e098bc29007b5cb8973fd2cf57f7b50660b6032a94d1e906ef5651cd`.
These are local development receipts, not commit-bound release artifacts.

## Exact compatibility comparison

The 702-record September 22 ledger differed from the current japicmp output by
nine current-only and four reviewed-only MCP records. Each is an interface
getter with japicmp change type `METHOD_ADDED_TO_INTERFACE`, binary compatibility
`true`, and source compatibility `false`. The current source and default server
implementation agree on the getters. The review groups their descriptors as
follows:

| Workstream | Current getter records | Retired getter records | Review |
| --- | ---: | ---: | --- |
| N0 naming | 3 | 3 | `getLocalizationCatalogInvalidator`, `getProtectionKeyringManager`, and `getTraceCorrelationKeyManager` replace the three `*Control` getters without aliases. |
| P1b subscriptions | 2 | 0 | `getSubscriptionAuthorizer` and `getSubscriptionReconciler` expose the implemented subscription configuration. |
| P3 Apps/result sanitation | 2 | 1 | `McpResourceContents.getAppResourceMetadata` and `McpServer.getToolResultSanitizer` are present; the latter replaces `getToolOutputSanitizer`. Their signatures remain provisional for final Apps qualification. |
| P4 Skills | 2 | 0 | `getSkillAccessPolicy` and `getSkillVariantSelector` are present; final Skills qualification remains open. |

The reviewed compatibility ledger now has **707** canonical records, SHA-256
`24bbed473a6c4d9807cfce776a7353ffc3c99fd052d342fec5914355e1220808`.
The exact japicmp comparison passes in both directions. Recording present
development compatibility facts for P3 and P4 does not constitute their API
freeze. The two shifted `shutdownTimeout` ledger addresses were refreshed in
the separate lifecycle inventory; their line contents and hashes did not change.
The lifecycle closure inventory was subsequently refreshed against the
`defaultInstance()` test changes and the staged transport verifier. Its reviewed
scope classifications are unchanged except that the new shared-default test
replaces the old construction-only default test. The lifecycle verifier passes
locally; this remains development evidence until replayed from an owner commit.

## Accepted signature tranche

The September 22 [ID-level preflight](mcp-g2-preflight-delta-2026-09-22.json)
classified every N0, P1b, and P2 difference. Fresh extraction against the
current owner partition contained all 393 previously classified differences
with the same IDs and directions. Its only additional ID is the P3 Apps
`McpAppResourceMetadata.ContentSecurityPolicy.defaultInstance()` factory.
Common IDs have no changed records. The bounded update accepts exactly 161
current-only and 57 reviewed-only IDs from N0/P1b/P2, preserving canonical ID
order and leaving the other workstreams untouched.

| Snapshot | Previous reviewed | Bounded reviewed | Derived current | Remaining current-only / reviewed-only |
| --- | ---: | ---: | ---: | ---: |
| Phase 4 | 1,130 | 1,160 | 1,323 | 169 / 6 |
| Phase 5 | 194 | 240 | 240 | 0 / 0 |
| Phase 6 | 425 | 453 | 454 | 1 / 0 |
| Provisional Tasks | 98 | 98 | 98 | 0 / 0 |
| **Total** | **1,847** | **1,951** | **2,115** | **170 / 6** |

The remaining Phase 4 difference is P3 Apps/result sanitation **79/6** and P4
Skills **90/0**; Phase 6 has one P4 Skills addition. These are the only
unaccepted signature IDs. Phase 5 and provisional Tasks now match their
reviewed snapshots, but the aggregate freeze gate correctly remains red.

The current sealed `McpOperationResult` root has an explicit evolutionary
policy: only the framework may implement it, and a new permitted result family
requires operation-contract review. The roadmap openness inventory now follows
the renamed framework router and the bounded Skills YAML directive parser.
The evolution suppression fingerprint for the security-manager constructor now
resolves to its current source declaration.
These inventory decisions do not qualify the remaining Apps/Skills signatures.

## Remaining gate conditions

- P3 Apps needs an accepted real-host qualification scope and its final API
  review. The released Inspector run observed render/refresh but did not meet
  the full host gate after its authentication fallback.
- P4 Skills needs the supported YAML boundary and operational/host review,
  plus review of actual nightly CI fuzz receipts. The owner removed local
  24-hour runs as a qualification requirement.
- The evolution verifier still sees two Apps enum rows absent from the
  intentionally older Phase 4 signature snapshot. The aggregate freeze verifier
  must remain red until the qualified P3/P4 surface is finally reconciled.
- The separate transport dependency characterization now follows the explicit
  streaming-input policy and passes with 82 negative/self-test cases. Its
  refreshed baseline adds the `Handler` policy evidence and the direct
  `Soklet` to `StreamLifecycleCoordinator` dependency introduced by streaming.
  This is local development verification, not an immutable-candidate receipt.

The fresh Java 17 aggregate freeze run compiled and passed the 707-record
compatibility comparison and the 377-owner partition, then stopped at the
expected Phase 4 P3/P4 mismatch. Independently, Phase 5 and provisional Tasks
signature checks, the metadata-builder inventory, the protocol-profile evidence
check, and roadmap readiness all pass. Phase 6 has the one unaccepted Skills
localization-cursor getter. The evolution verifier still reports the two
provisional Apps enum rows described above.

The public-API reflection test's parameter-name expectations were updated to
the already-renamed source declarations. A local Java 17 full test run then
passed **3,508 tests, zero failures/errors, 105 skipped**. This is development
verification of the staged tree, not a candidate built from an owner commit.

No source commit, push, immutable-candidate receipt, or publication approval is
created by this review.
