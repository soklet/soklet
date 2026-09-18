# Scoped SpotBugs selector-retarget approval — 2026-09-18

## Owner approval

After the project owner was told that two previously approved
`McpApplicationExecution$Exchange.<init>` exclusions no longer resolved only
because that constructor gained catalog-access and locale-state parameters, the
owner replied **“Sure”** to this exact proposal:

> update two existing, narrowly scoped false-positive rules so they point at
> the constructor's current parameter list

This record implements that authorization. It does not infer approval from a
scanner result.

- Owner: Soklet project owner (user in this task).
- Recorded approval time: `2026-09-18T16:32:38Z`.
- Scope: only the two `McpApplicationExecution$Exchange.<init>` selectors below.
- This does **not** authorize additional exclusions, broader suppression,
  publication, candidate acceptance, or any change to Gitleaks approvals.

## Approved retarget

The two approved selectors retain the exact class
`com.soklet.internal.mcp.protocol.McpApplicationExecution$Exchange`, the exact
locals `?` and `request`, and only the bug pattern
`NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE`.

Their current exact parameter list is:

```text
com.soklet.internal.mcp.protocol.McpApplicationExecution,long,com.soklet.internal.microhttp.MicrohttpRequest,com.soklet.Request,com.soklet.internal.mcp.protocol.McpJsonRpcMessage$Request,com.soklet.internal.mcp.protocol.McpProtocolProfile,com.soklet.McpRequestContext,com.soklet.internal.mcp.protocol.McpEffectiveAdmissionIdentity,java.util.Optional,java.util.Optional,java.util.concurrent.atomic.AtomicReference,com.soklet.internal.mcp.protocol.McpApplicationRequestHandler,com.soklet.internal.mcp.protocol.McpApplicationRequestInterceptor,com.soklet.internal.mcp.protocol.McpApplicationEntryGate,long,com.soklet.internal.mcp.protocol.McpApplicationResponseWriter,java.lang.Runnable
```

Relative to the September 16 approval, the only selector change is insertion of
`java.util.Optional` for `catalogAccessView` and
`java.util.concurrent.atomic.AtomicReference` for `selectedLocaleSlot`. No
class, method, local, bug pattern, wildcard, package, category, or
overload-family scope was added.

Approved current `config/spotbugs-exclude.xml` SHA-256:

`65e286664f80f29f803448f63c50620e23d6d1252d1536c8a3c7e98287511f58`.

This amendment supersedes the September 16 approval only for the two old
`Exchange` constructor signatures. The original approval remains unchanged for
the other two SpotBugs selectors and all 39 Gitleaks finding identities,
including their existing expiry. The prior filter hash remains historical and
is not a current-filter identity.

## Validation basis

A clean empty-filter JDK 21 analysis using SpotBugs Maven plugin 4.10.4.1 and
engine 4.10.4 reproduced both findings at the current `Exchange` constructor
descriptor with zero analyzer errors. After three separate genuine
current-source findings were fixed without suppressions, the normal baseline
filter with the stale Exchange selectors reported exactly two findings and no
analyzer errors or missing classes: local `?` (displayed `$L3`) and local
`request`. These are the same constructor-annotation indexing false positives
covered by the September 16 technical analysis; the constructor's added state
parameters did not change their classification.

After retargeting only those two exact selectors, a clean JDK 21
`spotbugs:check` completed with `BugInstance size is 0`, `Error size is 0`, and
no analyzer warnings.

This is local working-tree evidence, not a candidate-bound scan receipt. Remove
the exclusions when a future SpotBugs engine passes the standalone constructor
annotation reproducer and a clean empty-filter core scan no longer emits them.
