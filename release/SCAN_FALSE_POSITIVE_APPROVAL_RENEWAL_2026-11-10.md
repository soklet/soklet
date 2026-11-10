# Renewal of scoped Gitleaks false positives

## Owner approval

The Soklet project owner asked, “OK, I pushed.  Can you fix the downstream pins and do the scan exceptions and run release validation?” This followed the release status identifying the 39 expired exceptions.

This renewal covers the same 39 exact historical identities reviewed in the [September 16 approval](SCAN_FALSE_POSITIVE_APPROVAL_2026-09-16.md). Each entry preserves its scanner, rule, commit, path, fingerprint, owner, and rationale. The renewed entries are recorded in the [exception registry](release-scan-exceptions.json).

The renewal was recorded at `2026-11-10T22:47:21Z` and expires at `2026-12-10T22:47:21Z`, 30 days later.

## Validation

Gitleaks 8.30.1 scanned the 1,326 commits reachable from `5827abbb1e300d40f2354d31abe3366c750f9fc8` using the release configuration. Its redacted JSON and SARIF reports agreed on all 56 finding identities and matched all 39 prior exceptions. The other 17 findings await an owner decision.

The release workflow evaluates the committed registry against its pinned scan artifacts.
