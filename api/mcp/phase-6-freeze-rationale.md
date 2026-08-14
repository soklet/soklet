# MCP Phase 6 API-freeze rationale

Date: 2026-08-14

This record approves the Phase 6 public/protected API snapshot for Soklet
`3.6.0-SNAPSHOT`. The comparison baseline is released Soklet `3.5.1`, and the
comparison tool is japicmp `0.26.1`. It records a compatibility decision; it
does not by itself establish Phase 6 conformance or release-candidate status.

The preceding
[localization implementation plan](../../../mcp/MCP_LOCALIZATION_IMPLEMENTATION_PLAN.md)
and its
[focused L0 API review](../../../mcp/MCP_LOCALIZATION_API_REVIEW_2026-08-12.md)
record the complete review decision and its test, static-analysis, sketch, and
downstream-adapter evidence. The snapshot checked in here is byte-for-byte
identical to a fresh extraction from the current full japicmp report.

## Compatibility and ownership model

The reviewed current incompatibility set contains exactly 559 canonical
symbols and has SHA-256
`c0c4b4c68d93e77500b4ffeae07d1cb0bea46bf858c917ef44bbaa6adb61fee4`.
The matching full japicmp report establishes an exact owner universe of:

- 133 Phase 4 owners;
- 39 Phase 5 owners;
- 33 Phase 6 owners;
- 32 provisional owners; and
- 237 owners in total.

The 33 Phase 6 owners are the exact sorted entries in `phase-6.includes`. The
Phase 4 and Phase 5 snapshots and their 133- and 39-owner inventories remain
unchanged; only `McpServer.getLocalizationControl()` adds a current source
incompatibility, and it was approved as one of exactly three reviewed Phase 4
host amendments. Provisional owners remain unfrozen.

## Frozen Phase 6 snapshot

`phase-6.signatures.jsonl` contains exactly 181 canonical records:

- 33 classes;
- five constructors;
- 19 fields; and
- 124 methods.

The reviewed file's SHA-256 is
`7f6c76e62a5f6e20c6c8f6b9599ed7c4d84c169d2896fcefdfd9744c9627b2bc`.
The independent reflection contract freezes the Phase 6 JSpecify type-use
layout with SHA-256
`1a1b18a9f24a4c28ef15b51545163f7140c1661515669fbaa3dcf32befdaddc8`.

Immediately before the snapshot was checked in, a fresh extraction from the
current full japicmp report produced the same 181 records and was byte-for-
byte identical to the reviewed candidate. The aggregate freeze gate now
compares the Phase 4, Phase 5, and Phase 6 snapshots bidirectionally on every
run, and `frozen-phases` lists the contiguous sorted prefix `4`, `5`, `6`.

## Reviewed contract

The snapshot freezes the localization API family - the 18 localization-owned
types reviewed in L0 - alongside the previously assigned Phase 6 diagnostics,
shutdown, subscription-configuration, and simulator-adjacent owners. The
cross-cutting review fixed the following public contracts:

- Localization is library-neutral. `McpLocalizer` carries a fallback locale,
  an application `McpLocalizationContextProvider`, a whole-response failure
  policy, and a per-response callback bound; Soklet depends on no translation
  library, and the published jar retains zero runtime dependencies.
- `McpLocalizationContext` is an immutable, node-local, request-scoped value:
  one selected locale and one translation snapshot per admitted localizable
  operation, with no session identity and no cross-request reuse.
- `McpLocalizationResult` is a sealed four-variant family. `Fallback` is
  invalid when its resolved locale equals the selected locale, `UseDefaultText`
  is an intentional per-field outcome rather than a failure, and `Failure` is
  fieldless so it can carry no application data.
- Every localization value type redacts its `toString()`. Revision values,
  coordinate identities, default text, locales, and preferences never appear in
  renderings, framework logs, exception text, or metric labels.
- `McpTextCoordinate` exposes a stable structured identity plus one versioned,
  domain-separated external key. An adapter selects exactly one key strategy
  per catalog and never falls back between strategies.
- `McpLocalizationControl` is a local-server control plane: `isEnabled()` plus
  `catalogsChanged()`. It distributes nothing, carries no locale, tenant,
  revision, or key, and throws consistently when localization is disabled.
- The three reviewed Phase 4 host amendments remain exact: default
  `McpHandlerInvocation.getFeatures()`, abstract
  `McpServer.getLocalizationControl()`, and concrete
  `McpServer.Builder.localizer(McpLocalizer)`. No fourth host descriptor was
  added, and the Phase 5 snapshot is untouched.
- No `com.soklet/localization` MCP extension exists. Soklet advertises no
  localization capability, reserves and interprets no request or result
  `_meta` key, emits no locale or revision metadata, and claims no positive
  locale-aware MCP caching.

## Why no conformance-profile activation was required

The Phase 5 freeze had to atomically activate 16 reviewed conformance profiles
and advance the harness to `--phase 5`. Phase 6 requires no equivalent step:
localization introduces no conformance-visible wire surface. It advertises no
capability, reserves and interprets no `_meta` key, defines no new method, and
emits no locale or revision metadata, so the official conformance harness
remains correct at its current phase. A search of `conformance/` finds no
localization profile, fixture, or expectation, which is the same negative
surface SOK-L10N-010 requires.

The localization-visible behavior changes that do exist - `Content-Language`,
the `Vary` merge, the private/zero cache clamp, and version-2 request state -
are all conditional on a configured localizer. With none configured, wire
output is byte-identical, which the golden-wire suites verify on every run.

## What this freeze does not decide

Freezing the signature and nullability layout does not close the remaining
localization conformance rows. Sustained soak and multi-node fleet
orchestration evidence, the ToyStore end-to-end migration proof, public
Javadoc publication, and release provenance remain open and are tracked in the
localization plan and conformance matrix.
