# MCP API-diff gate

The gate compares the checked-out Soklet source with the released 3.5.1
artifact and turns japicmp 0.26.1 XML into a reviewed, bidirectional set of
removed or incompatible JVM symbols.

Run it with a supported JDK, Maven, and Node.js available:

```sh
scripts/api-diff/verify.sh
```

CI selects JDK 17 explicitly; the script itself uses the caller-selected JDK.
The Maven profile pins both the 3.5.1 baseline coordinate and japicmp version.
The wrapper writes generated evidence under `target/japicmp/` and compares it with
`api/mcp/current-incompatibilities.jsonl` in both directions. It never updates
the reviewed file automatically.

The set includes every removed public or protected symbol even if japicmp
counterintuitively labels that removal binary- and source-compatible. Other
compatible `MODIFIED` containers are omitted; every change japicmp marks
binary- or source-incompatible remains included.

`scripts/api-diff/self-test.mjs` is dependency-free and exercises strict XML,
canonical incompatibility and selected-signature records, baseline-derived
ownership, and bidirectional comparison fixtures. It also verifies the reviewed
Phase 0 symbol counts and shared-host rationales. The immutable Phase 0 set
remains historical while the current incompatibility set and ownership
inventories evolve under review.

The aggregate Phase freeze gate is:

```sh
scripts/verify-mcp-api-freezes.sh
```

It first runs the compatibility gate above. It then derives the authoritative
current owner universe from the full japicmp report
`target/japicmp/mcp-api-freeze.xml`:

- every current, non-internal published `Mcp`-named type;
- every current shared host whose public/protected API references an MCP type;
  and
- every other current, non-internal public/protected API delta, which must be
  assigned to the reviewed non-MCP allowlist when it is unrelated to MCP.

Every owner must appear exactly once across the MCP phase/provisional
inventories or the non-MCP allowlist. The full report also supplies selected
signatures, so a type or member restored with the same signature it had in
3.5.1 remains visible even when the modified-only report omits it. The separate
modified-only report `target/japicmp/mcp-api-diff.xml` remains the source of the
canonical incompatibility set. Removed-only containers with no current-side API
do not become current owners, and `com.soklet.internal.*` is excluded.

For every phase listed in `api/mcp/frozen-phases`, the gate extracts canonical
current signatures and compares them bidirectionally with the reviewed
`api/mcp/phase-N.signatures.jsonl` snapshot. Generated signatures are written
under `target/mcp-api-freezes/`; reviewed snapshots are never updated
automatically. `McpPublicApiInventoryTests` remains a fast, independent
source/class-tree guard, but it is not the authoritative baseline-derived owner
algorithm. CI invokes the aggregate gate on JDK 17, while local runs use the
caller's JDK.
