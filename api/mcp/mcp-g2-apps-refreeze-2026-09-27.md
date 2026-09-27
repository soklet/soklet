# MCP-G2 Apps signature review — September 27, 2026

**Disposition: accept the exact current Phase 4 Apps and result-handling API
signatures.** The real-host Apps qualification remains open. The released
Inspector's handling of an unchallenged subscription-policy 403 is an upstream
host issue; its existing render/refresh observation and the local isolated patch
do not establish a full host pass. That issue does not change the Java signatures
reviewed here. The owner-retained full Apps requirement and immutable candidate
gates still apply.

This review used owner commit `392bc894f43d20fcaf1f42887341f707e1c50628`
and a Java 17 build. The main JAR SHA-256 is
`9d64201d2821c045ba0caf818bf6ac0bc791e8e557033a0adb86b795370541b3`;
the full japicmp freeze XML SHA-256 is
`bafab5cdf1301d2be56232870eda83fdb8dc9dae34ddb7afcfa17920316babe0`.
These are development artifacts, not an immutable candidate receipt.

The Phase 4 snapshot moves from 1,257 to 1,330 canonical records: exactly 79
current-only records are accepted and six obsolete records are removed. All
1,251 common records are byte-for-byte unchanged. The resulting
[`phase-4.signatures.jsonl`](phase-4.signatures.jsonl) has SHA-256
`7ffb82ff4b7a0b61005385d5050d54e4c4819806be698185a8ef38989f177798`.
The Phase 5, Phase 6, and provisional snapshots remain unchanged.

The accepted records have these source-level contracts:

| Surface | Review |
| --- | --- |
| `McpAppToolMetadata`, its builder, and `Visibility` | Immutable tool association and audience value. The optional resource association uses `URI`; visibility is a replacement `Set<Visibility>` with an explicit empty meaning. Neither is an authorization grant. |
| `McpAppResourceMetadata`, its builder, `Permission`, and nested `ContentSecurityPolicy`/builder | Immutable resource hints and canonical origin allowlists. Optional domain and border values preserve omission; the shared `defaultInstance()` has four empty immutable allowlists. Host enforcement remains the host's responsibility. |
| `@McpAppTool` and registration/content accessors | The annotation and programmatic registration share Apps metadata validation. Resource contents expose optional metadata through their common interface, text and blob values, and builders. |
| `McpClientCapabilities.supportsAppMimeType(String)` | Reports a structurally valid Apps MIME capability; malformed or missing peer settings do not assert support. |
| `McpCompleteResult.Builder` and `McpToolResultSanitizer` | `withToolOutput`, `withPromptOutput`, `withResourceOutput`, and `toBuilder()` replace the old metadata-copy helper. The server hook now receives and returns a complete result so payload and result metadata can be sanitized together. It has a shared `nonSanitizingInstance()` default. |

The six removed records are the `McpToolOutputSanitizer` type, its two methods,
`McpServer.getToolOutputSanitizer()`,
`McpServer.Builder.toolOutputSanitizer(McpToolOutputSanitizer)`, and
`McpCompleteResult.withMetadata(McpJsonObject)`. Their reviewed replacements
are present in the current snapshot; compatibility aliases are intentionally
absent. The exact 3.5.1 incompatibility ledger, its 713 records, and the
380-owner public partition already matched the built API before this snapshot
update, so neither needed a change.

The Java 17 aggregate API-freeze command now passes all three frozen phases,
the provisional snapshot, metadata-builder, public-evolution, transport, and
roadmap checks. This is a signature acceptance only. The separate full Apps
real-host security, permission, authorization, and localization matrix remains
open, along with the exact-commit release candidate checks.
