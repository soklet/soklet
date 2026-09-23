# Streaming SpotBugs filter reconciliation — 2026-09-23

The streaming redesign removed `SseRequestResult.HandshakeAccepted.unregisterConsumers`.
The old filter still suppressed `UL_UNRELEASED_LOCK_EXCEPTION_PATH` for that exact
method, so the exact-commit Maven `verify` stopped at its exclusion-target check
after 3,507 passing tests (105 JDK 17 skips). The stale match has been removed.
No new or broader exclusion was added.

The filter SHA-256 changed from
`65e286664f80f29f803448f63c50620e23d6d1252d1536c8a3c7e98287511f58`
to `87fb30137fc2a7636caf59f0ab534e567a4eacd495d0ee04a1d880d16be3b808`.
The release-scan contract and producer pin now name the narrowed current filter.
The September 16 approval and September 18 selector amendment remain historical
records of their exact reviewed bytes; neither is rewritten as approval of this
new candidate identity. Exact-candidate release scans and owner acceptance are
still required before publication.

## Source-bound inventory repairs

The final SSE initializer moves one fixed `IllegalStateException` construction
from `DefaultSseUnicaster.enqueue` into `requireInitializingUnderLock`. The
privacy inventory now names that actual method and preserves the same boundary
classification. Its current semantic digest is
`d3126df2fe81cfd20aa8c879a17ae830b7651cb5778a5ea6a04424cdfe278833`;
the matrix closure verifier uses that digest and passes without changing the
matrix row universe.

The finite-bound test now reads eleven reviewed Skills limits from their
production constants. This moves one lifecycle test scope and seven discovery
line addresses without changing their classifications or reviewed limits. The
lifecycle inventory was updated only for those source hashes and locations;
its verifier and negative self-test pass. The current version census was
extended for eight authored streaming documents and the shifted test line.
Its historical baseline governance and removal dispositions are unchanged.

Raw development logs, compiled fixture classes, process IDs, and draft
inventories remain in the integration handoff rather than the candidate tree.
The release commit retains authored milestone summaries but does not treat
their development evidence as immutable-candidate receipts.
