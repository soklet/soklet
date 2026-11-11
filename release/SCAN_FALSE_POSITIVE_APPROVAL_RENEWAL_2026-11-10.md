# Renewal of scoped Gitleaks false positives

## Owner approval

The Soklet project owner asked, “OK, I pushed.  Can you fix the downstream pins and do the scan exceptions and run release validation?” This followed the release status identifying the 39 expired exceptions.

This renewal covers the same 39 exact historical identities reviewed in the [September 16 approval](SCAN_FALSE_POSITIVE_APPROVAL_2026-09-16.md). Each entry preserves its scanner, rule, commit, path, fingerprint, owner, and rationale. The renewed entries are recorded in the [exception registry](release-scan-exceptions.json).

The renewal was recorded at `2026-11-10T22:47:21Z` and expires at `2026-12-10T22:47:21Z`, 30 days later.

## Validation

Gitleaks 8.30.1 scanned the 1,326 commits reachable from `5827abbb1e300d40f2354d31abe3366c750f9fc8` using the release configuration. Its redacted JSON and SARIF reports agreed on all 56 finding identities and matched all 39 prior exceptions. The other 17 findings received the supplemental approval below.

The release workflow evaluates the committed registry against its pinned scan artifacts.

## Supplemental owner approval

In response to the request for a decision on the 17 reviewed findings, the Soklet project owner directed, “OK, let's resolve the 17 scan findings.” The approval covers those exact Gitleaks `generic-api-key` findings. The registry records each historical commit, path, fingerprint, and review rationale.

The findings comprise 11 lifecycle verifier metadata entries, three expected trace-log token-format identifiers, one generated API compatibility entry, one public SHA-256 digest, and one disposable Inspector self-test value.

The approval was recorded at `2026-11-10T23:32:56Z` and expires at `2026-12-10T23:32:56Z`, 30 days later.

Gitleaks 8.30.1 scanned 1,327 commits at `ac6448e43c213480a9fdf319df051b7326cb001c` with the pinned release configuration. Its redacted JSON and SARIF reports agreed on the same 56 exact identities from the prior scan. The renewed and supplemental entries cover all 56 findings.
