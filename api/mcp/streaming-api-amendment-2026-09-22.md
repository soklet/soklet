# Streaming API amendment — September 22, 2026

**September 23 update:** the exact 706-record amendment and 47 added records
below are preserved as a historical checkpoint. The final 4.0 surface removes
the optional `ResponseStream.writeUtf8(String)` shorthand and the four proposed
`SseUnicaster` request/status/acquisition/termination methods. The checked
`SseClientInitializer` remains a one-time synchronous callback, and ongoing SSE
delivery uses `SseBroadcaster`. After the separate route amendment and those
five removals, the current ledger has 702 records, SHA-256
`0c997ec0c53f3891126ddd2a7e8eaf8d9d82c68722f5689c64eb72beee08e427`.

The selected 4.0 HTTP/SSE redesign deliberately breaks the old streaming surface,
with no compatibility aliases or deprecation layer. This amendment adds its
47 compiler-derived incompatibility records to the current reviewed non-MCP
surface. It does not refreeze MCP or rewrite any historical phase/provisional
signature ledger, phase-0 snapshot, or sealed D1p evidence.

The implementation and qualification are recorded in the milestone 3 and 5
reports under `docs/`; [milestone 5b](../../docs/streaming-api-milestone-5b.md)
closes SSE default qualification. The user selected breaking changes for 4.0 and
the `Cancelation` spelling, unified `ResponseStream`, `open`/`own`/`using`
ownership names, and narrow asynchronous SSE ownership surface.

| Selected change | Compatibility consequence |
| --- | --- |
| One-argument `StreamingResponseWriter` and unified `ResponseStream` | Remove `StreamingResponseContext`, move request/deadline/cancelation access to the owner, and change the writer descriptor. Newly required stream operations break custom implementations. |
| Explicit descriptor versus writer convenience | `stream(writer)` is the selected callback convenience; descriptor setters/getters become `streamingResponseBody` / `getStreamingResponseBody`, with corresponding copier/clear methods. |
| Checked shared resource factories | Replace `Supplier` descriptors with `StreamResourceFactory`; input/reader getters use `Factory` naming. No competing functional-interface overload remains. |
| Neutral callback removal handle | `CancelationToken.onCancel` returns `CallbackRegistration`; every core/MCP implementation migrated together. The change belongs to the non-MCP shared token owner, not a frozen MCP-specific signature. |
| SSE connection ownership | Add request/status/checked acquisition/termination operations to `SseUnicaster`, use `SseClientInitializer`, and remove the old unscoped concrete unicaster constructor/queue accessor. |

The [exact amendment](../../docs/streaming-api-evidence/milestone-6a-2026-09-22/api-streaming-amendment.json)
retains all 659 previous records verbatim and adds 47. It removes or changes no
previous record. The amended 706-record ledger has SHA-256
`d09dd3b74137e8a181fa9732ffa236dfd383d302ba22fa16a842cc287a5409b9`.
Compatible new methods/types and `CLEANUP_TIMEOUT` do not necessarily appear in
an incompatibility inventory; current-source ownership and the public contract
fixtures cover the complete selected surface separately.

The ownership inventory also classifies the existing
`ResourcePathDeclaration.Component` under the non-MCP route-value domain. This
fixes its unassigned-owner error; it does not accept that type's separate
factory-rename incompatibility. There are 294 current-source MCP owners and 85
non-MCP owners, with no overlap or unassigned types in the generated report.

The candidate comparison still contains 712 incompatibilities. After this
streaming amendment, **ten unexpected and four missing records remain**: nine
unexpected MCP members, four replaced MCP member descriptors, and the existing
route-component factory rename. Their independent disposition remains open.
The aggregate API gate therefore still fails. This amendment must not be used
as a claim that the earlier MCP-G2 refreeze, candidate acceptance, or publication
approval has occurred.
