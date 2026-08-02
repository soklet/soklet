# MCP API-diff gate

The gate compares the checked-out Soklet source with the released 3.5.1
artifact and turns japicmp 0.26.1 XML into a reviewed, bidirectional set of
removed or incompatible JVM symbols.

Run it with JDK 17, Maven, and Node.js available:

```sh
scripts/api-diff/verify.sh
```

CI selects JDK 17 explicitly. The Maven profile pins both the 3.5.1 baseline
coordinate and japicmp version. The wrapper writes generated evidence under
`target/japicmp/` and compares it with
`api/mcp/current-incompatibilities.jsonl` in both directions. It never updates
the reviewed file automatically.

The set includes every removed public or protected symbol even if japicmp
counterintuitively labels that removal binary- and source-compatible. Other
compatible `MODIFIED` containers are omitted; every change japicmp marks
binary- or source-incompatible remains included.

`scripts/api-diff/self-test.mjs` is dependency-free and exercises strict XML,
canonical symbols, and bidirectional comparison fixtures. It also verifies the
reviewed Phase 0 symbol counts, shared-host rationales, and the requirement that
the current set match Phase 0 while all phase/provisional MCP API inventories
remain empty. Once greenfield types enter an inventory, the current set may
evolve and is checked against the current build by the wrapper.
