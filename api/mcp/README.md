# MCP API compatibility and inventories

`current-incompatibilities.jsonl` is the reviewed, canonical set of public or
protected API incompatibilities between Soklet 3.5.1 and the current 3.6.0
source tree. The API-diff CI job regenerates the set from the checked-out code
and compares it in both directions, so an unexpected addition, removal, or
changed incompatibility fails the build.

`phase-0-incompatibilities.jsonl` preserves the exact reviewed removal surface
from deleting the legacy MCP implementation. It initially matches the current
set. The current set may evolve as the greenfield implementation introduces
new types or deliberately reuses names; the Phase 0 set remains unchanged.

`phase-0-shared-host-rationales.jsonl` explains every removed MCP-owned member
whose containing public type remains part of Soklet. The API-diff parser
self-test verifies that its identifiers exactly match the shared-host subset of
the Phase 0 set.

The `phase-4.includes`, `phase-5.includes`, `phase-6.includes`, and
`provisional.includes` files select the new MCP public and protected-extension
API. Each nonblank line is a fully qualified binary type name; lines are unique
and sorted by Unicode code point. They are intentionally empty while Phase 0
contains no MCP implementation.

`non-mcp-public-api.allowlist` uses the same format for reviewed public API
changes unrelated to MCP. The union of the four MCP include files drives the
targeted MCP Javadoc completeness test.

The released 3.5.1 artifact, the reviewed Phase 0 incompatibility set, and the
current bidirectional API-diff gate are the durable baseline. No source file
claims that an uncommitted or unreachable Git commit is authoritative.
