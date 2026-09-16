# Scoped scanner false-positive approval — 2026-09-16

## Owner approval

The project owner replied **“That's fine”** in this task after the explanation and
recommendation to approve the four specifically identified SpotBugs exclusions
and all 39 individually reviewed Gitleaks historical findings as false positives.
This record implements that authorization, not an approval inferred from a green
scanner run or written on the owner's behalf before permission was given.

- Owner: Soklet project owner (user in this task).
- Recorded approval time: `2026-09-16T14:26:19Z`.
- Scope: only the four SpotBugs selectors and 39 Gitleaks finding identities below.
- This does **not** authorize commits, history rewrites, publication, other
  exceptions, broader suppression, or acceptance of an unvalidated candidate.
  Existing historical security approvals and other release gates remain separate.

## SpotBugs

The owner approves the four exact false-positive exclusions described in
[the constructor-annotation analysis](../docs/spotbugs-jspecify-constructor-analysis.md):

1. `McpApplicationExecution$Exchange.<init>`, local `?` (displayed `$L3`).
2. The same exact constructor, local `request`.
3. The eight-argument `McpHttpServerRuntime.submitRequest`, local `lifecycleAdmission`.
4. The five-bytecode-argument `McpTypedSchemaScalar.<init>`, local `jsonType`.

Each selector retains its exact class, full method signature, local-variable name,
and `NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE` pattern. No new filter
bytes or broader rules were introduced by recording this approval.

Approved current `config/spotbugs-exclude.xml` SHA-256:
`c1005b521f8a047fdedaf418661a6c54d92d59e6c3ea1c0ab6fb24c710628ed5`.

The filter's existing comment about pending approval describes its preparation
state; this subsequent owner decision resolves that specific pending review.
Its bytes are intentionally preserved. This is not a blanket reapproval of every
existing filter or of historical scanner evidence.

These filters have no automatic expiry. Review them whenever the affected methods
or scanner change, and remove them once an upgraded engine correctly handles the
standalone reproducer. A new real finding matching the same exact selector could
otherwise be hidden; an unfiltered scan and the negative control remain necessary
when reassessing the analyzer defect.

## Gitleaks

The owner approves exactly rows **1–39** of
[the historical-scan triage](GITLEAKS_TRIAGE_2026-09-16.md), covering the scan of
HEAD `0fa219ded6704b08c7dbd671276996a0f9a548a6` and its full ancestry. The reviewed
triage bytes, before adding this approval-status cross-reference, had SHA-256
`da1813b3825faa89d43b3122d77d74db18a972f2951f5a214839fc2f4bb289ab`.

The machine-readable [exception registry](release-scan-exceptions.json) now holds
39 entries, each bound to its scanner, rule, historical commit, path, and SHA-256
fingerprint of the exact start/end line-and-column identity. The individual
classification rationale is retained in every entry; no raw matched value is
needed. The canonical registry SHA-256 is
`908fb3878d9aa16ee0defc2c8ddb3093882007074a06989ed3002070979ac5e6`.

- `approvedAt`: `2026-09-16T14:26:19Z`.
- `expiresAt`: **`2026-10-16T14:26:19Z`**, exactly 30 days later.
- `approvalReference`: this document's `#owner-approval` section.
- No wildcards, path-wide exclusions, generic-rule disablement, HIGH/CRITICAL
  exceptions, or approval of new occurrences is authorized.

An expired, missing, changed, additional, or unmatched approval continues to fail
the existing policy. Renewals require a new owner decision; dates must not be
silently extended. A subsequent candidate must include these files and rerun the
pinned workflow against its exact committed ancestry. This local approval record
does not claim that the Linux candidate scan or any release gate has passed.

## Local validation

A pinned Gitleaks rerun at the reviewed cutoff produced 39 redacted SARIF findings
whose identities exactly matched the existing JSON report and all 39 approvals.
The isolated approval-evaluation probe passed normalization and exception checks,
then deliberately stopped at a CodeQL bundle provenance mismatch. It emitted no
candidate evidence bundle or PASS receipt.

Seven negative probes rejected a missing approval, altered fingerprint, approval
at its exact expiry boundary, unmatched extra approval, new finding, disagreeing
JSON/SARIF identities, and HIGH-severity finding. The scanner-producer self-tests
(92 assertions), SpotBugs-exclusion self-tests, evidence-contract verification,
and final version-inventory verification also passed.
