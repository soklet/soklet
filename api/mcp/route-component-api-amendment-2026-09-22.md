# Route component compatibility amendment — September 22, 2026

`ResourcePathDeclaration.Component.with(String, ComponentType)` was removed in
favor of `fromValueAndType(String, ComponentType)`. The source at the isolated
worktree's base commit already makes this change; the migration guide names the
replacement, and `PublicNamingContractTests` checks the new factory and the
absence of the old one. This is a deliberate route-value naming change, separate
from the HTTP/SSE streaming redesign and from the pending MCP refreeze.

The compiler-derived compatibility comparison reports one exact record for the
old method: `METHOD_REMOVED`, with binary and source compatibility both false.
The [current reviewed ledger](current-incompatibilities.jsonl) adds that record
in canonical identifier order, retaining all 706 records from the
[streaming checkpoint](streaming-api-amendment-2026-09-22.md) byte-for-byte. Its
new count is 707 and SHA-256 is
`8a9e988868b5be52c46c931e38d0e1911d3cd7c1d8bde5128417e8b1962736be`.
No production implementation or historical phase/provisional signature was
changed by this amendment.

The aggregate comparison still has nine unexpected and four missing MCP
records, with zero changed common records. Those thirteen records remain
subject to the independent P0-C disposition and coordinated MCP-G2 review.
The [source-specific review](../../docs/streaming-api-evidence/milestone-6c-2026-09-22/mcp-api-drift-review.md)
names each descriptor and its provenance. This amendment does not make the
aggregate API gate or release candidate pass.

The one inserted ledger line shifts an existing lifecycle legacy-exclusion
address from line 663 to 664. Its exact source line, line hash and exclusion
policy are unchanged; the current line-derived identifier is refreshed in the
lifecycle inventory. The inventory verifier must pass after this relocation.
