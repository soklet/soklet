# Milestone 3c — managed HTTP resource ownership

Implemented 2026-09-21 in the isolated streaming worktree based on `c5d02871`.
This completes the managed writer/input-stream/reader ownership slice of
milestone 3. Full milestone 3 qualification remains open.

## Implemented

`ResponseStream` now implements the five selected ownership operations without
renaming them or adding aliases:

```java
open(streamResourceFactory)
open(streamResourceFactory, resourceAborter)
own(resource)
using(streamResourceFactory, resourceConsumer)
using(streamResourceFactory, resourceAborter, resourceConsumer)
```

The checked `ResourceAborter` and `ResourceConsumer` callbacks are nested types
on `ResponseStream`; acquisition uses the existing `StreamResourceFactory`.
The shared internal `ManagedResponseStream` implements these lifetimes for
both real HTTP and simulation. Input-stream and reader adapters use the same
ownership code rather than maintaining separate source-closing paths.

Normal finalization closes resources in reverse ownership order. `using`
also finalizes every nested acquisition or adoption before returning, then
releases its entries and callback registrations. `open(factory)` coordinates
one physical close attempt between normal cleanup and close-as-abort, even
when close throws. The provider must support close racing and unblocking use.
The separate-abort overload coordinates one abort and one final close; final
close waits for an already-running abort, while an abort may release a close
that has already started. These are different provider contracts.

`own` finalizes only on the producer thread. It supports encoders whose close
writes trailing bytes: successful root finalization remains writable until
all owned resources close and the final flush succeeds. Callback return alone
does not complete production. Cancelation/failure invalidates output, and a
blocked producer-thread close is neither moved nor retried.

Output and resource operations enforce producer-thread confinement and reject
use after their lifetime. Metadata and the cancelation token remain readable.
Duplicate active ownership of the same object is rejected. Native output
failure is terminal even if application code catches it. Unclassified producer
interruption maps to `APPLICATION_CANCELED`; a previously elected termination
reason takes precedence. Cleanup temporarily clears and restores interruption,
including a secondary cleanup interruption that must not replace a primary
application failure. Suppressed exceptions retain primary failure evidence
without creating cycles in cause/suppression graphs.

A caught lexical body or factory exception remains recoverable when cleanup
succeeds. If lexical cleanup also fails, the body exception remains primary and
the response becomes terminal. A meaningful application exception after an
elected disconnect remains available as bounded diagnostic evidence without
replacing the disconnect outcome.

### Clarified ownership-transfer boundary

The canonical plan now explicitly rejects `own(resource)` before production,
during cleanup, after lifetime completion, or on the wrong thread **before
ownership transfers**. The caller remains responsible for that resource.
Automatically disposing a newly offered resource after lifetime retirement
would create uncounted cleanup. This is a deliberate correction to the earlier
prototype contract. An acquisition already admitted when cancelation wins
still disposes its late result under the retained reservation; adoption during
the active producer lifetime also disposes if cancelation has already won.

## Supervision and simulator integration

The shared owner uses the existing bounded coordinator rather than starting
per-resource workers. HTTP still reserves capacity before commitment.
Simulator HTTP scopes now own a finite coordinator with the qualified HTTP
defaults (256 slots, four callback workers, five seconds of cleanup grace).
The simulator admits the synchronous materialization envelope as inline
producer work, and rejects excess work with 503 before invoking the writer or
source factory.

Cancelation callback batches run on bounded workers, independently of the
supervisor. Successful simulator production completes the token before the
termination observer is delivered. Observer delivery is separately accounted,
and the synchronous caller waits for it. Scope shutdown now quiesces and forces
HTTP work and reports remaining stream/worker/callback obligations. An expired
cleanup deadline reports `CLEANUP_TIMEOUT` and retains capacity until physical
cleanup exits.

The simulator remains synchronous: arbitrary blocked user cleanup or an
observer can still block the request caller. Its independent supervisor and
scope teardown have bounded waiting; this does not promise a bounded return
from arbitrary user code.

Coordinator completion also now waits for already-claimed termination hooks
to publish their callback batch. A fast producer exit can no longer seal
callback publication before a delayed termination signal finishes publishing.

Simulator publisher cancelation now shares one physical cancel attempt across
the cancelation callback, data delivery, and producer finalization. A throwing
cancel preserves the original subscribe/output failure. A late subscription
receives no demand. This matches the current HTTP behavior, with the pending
asynchronous acquisition limitation below still open.

## Validation

**320 tests passed in 23 suites, zero failures/errors/skips, on Amazon Corretto
17.0.20.1.** The [test manifest](streaming-api-evidence/milestone-3c-2026-09-21/tests.json)
and [run log](streaming-api-evidence/milestone-3c-2026-09-21/tests.log) include
19 shared-owner tests, seven ownership cases exercised against both HTTP and
simulation, six HTTP ownership/supervision race tests, two simulator deadline/
shutdown tests, three publisher cancelation tests, and the affected coordinator,
source, transport, simulator, naming, generated-code, and MCP regressions.

The [clean package/API-report build](streaming-api-evidence/milestone-3c-2026-09-21/api-build.log)
compiles 583 production and 385 test sources with Java 17. Both previously
migrated standalone benchmark callers compile. The API parser self-tests and
report-pair consistency check pass. No throughput claim is made for this slice.

The [API delta](streaming-api-evidence/milestone-3c-2026-09-21/api-delta.json)
adds exactly the five selected abstract ownership methods to slice 3b's
incompatibility report: 696 records become 701, with no removed or changed
prior record. The two new callback owners are assigned to the non-MCP inventory.
All four generated MCP signature inventories remain byte-identical to earlier
milestones; see the [comparison](streaming-api-evidence/milestone-3c-2026-09-21/mcp-signatures.json).

The aggregate reviewed-compatibility gate remains red with 46 unexpected and
four missing records, including the five intentional additions in this slice.
The ownership inventory still reports the one preexisting unassigned owner,
`ResourcePathDeclaration$Component`. Historical snapshots and the reviewed
incompatibility set are unchanged. The
[source identity](streaming-api-evidence/milestone-3c-2026-09-21/source-identity.json)
records this uncommitted checkpoint.

## Remaining milestone 3 work

The next lifecycle slice should resolve asynchronous publisher acquisition.
As already recorded in milestone 1, a publisher may return from `subscribe`
without supplying its subscription. If cancelation and reservation retirement
occur first, later `onSubscribe` currently cancels on the publisher's calling
thread. A blocking late cancel is outside retained capacity, residual, and
shutdown accounting; its eventual exception can also miss retired diagnostics.
The simulator now has the same limitation. The no-demand test does not establish
containment of this late cleanup.

Define and implement the retained obligation for pending acquisition, its
transfer to late cancel execution, and behavior when `onSubscribe` never
arrives. Add deterministic HTTP/simulator tests for blocked late cancel,
retained admission/residuals, zero demand, and late failure evidence. This is a
required milestone 3 qualification item before claiming full ownership
containment or moving on to SSE ownership.

Output views, byte-slice/UTF-8 helpers, scalar-write buffering, accepted-prefix
interruption semantics, public server settings, and their qualification also
remain. ZIP validation must inspect the central directory, and performance/
allocation checks must qualify buffering. SSE ownership and the independent
passive-disconnect transport track remain separate milestones.

Release qualification must reconcile aggregate API/frozen-evidence drift and
regenerate the candidate-bound privacy inventory. The previously recorded full
benchmark-module compile blocker remains; standalone caller compilation does
not clear it. This slice is not release qualification.
