# Milestone 3d — asynchronous publisher lifecycle accounting

Implemented 2026-09-21 in the isolated streaming worktree based on `c5d02871`.
This closes the pending-publisher acquisition gap recorded in milestones 1 and
3c. Public streaming method names and signatures are unchanged.

## Implemented

HTTP and simulation now use one internal `PublisherResponseStream` adapter.
Before calling application `subscribe`, the adapter retains one publisher
obligation on the existing lifecycle reservation. It does not allocate another
admission slot, worker, queue, or timer.

If `subscribe` returns normally before the first `onSubscribe`, acquisition
remains pending. Cancelation can terminate transport and let the producer exit,
but cannot retire this obligation. A late subscription receives no demand and
shares one physical cancel attempt. Pending acquisition becomes active provider
work without releasing the reservation between those states. The obligation
ends only after cancel and all entered provider/callback frames physically exit.

If the subscription never arrives, the slot remains occupied. Cleanup expiry
marks the reservation overdue and reports pending acquisition; bounded shutdown
reports outstanding stream/callback work. After cancelation, pending acquisition does not retain a
waiting producer or callback worker. Admission resumes only after the obligation
actually resolves. This is the same deliberate capacity tradeoff used for a
provider that never returns from resource cleanup.

Callback frames matter independently of subscription state. A `request` or
`subscribe` call can synchronously publish `onComplete` and then remain blocked.
Terminal notification now starts cleanup supervision immediately, while the
enclosing physical call remains counted. Successful production waits for those
calls to return. Expiry cancels the response with `CLEANUP_TIMEOUT` when no
earlier reason won, without pretending the provider call exited. A demand call
claimed before cancelation may finish concurrently with cancel; a subscription
first delivered after cancelation receives no demand.

The coordinator exposes pending/active publisher counts in its internal
snapshot and cleanup diagnostic. HTTP and simulator residual reporting includes
publisher work as `STREAM` and `CALLBACK`, without inventing an executing
producer. Successful production cannot be elected with an outstanding publisher
obligation. Retirement still waits for existing callback/diagnostic work.

The original subscribe/output/cancelation outcome remains primary. A later
exception from an already-entered provider call or late cancel is submitted
before its obligation releases, using the existing bounded first-diagnostic
policy. If a cleanup deadline or another failure already claimed that one
diagnostic, it remains the retained evidence; this slice adds no unbounded
exception collection or diagnostic queue.

## Publisher protocol boundary

The adapter supports asynchronous delivery of the first subscription after a
normal `subscribe` return. A throwing `subscribe` before the first subscription
is instead a definitive failed acquisition: the publisher owns cleanup of its
partial resources and must not subsequently deliver a subscription. This avoids
retaining a permanently pending slot for a synchronously failed acquisition.

Data or a terminal signal before the first subscription is a protocol failure.
Subscription delivery after failed acquisition or a completed lifetime is
rejected before invoking provider methods, leaving cleanup with the publisher.
While the lifetime is admitted, a distinct duplicate subscription is canceled
under its existing obligation. Repeated delivery of the original fails the
response and shares its once-only cancel attempt. Data/terminal signals after
stopping are ignored. These boundaries allow finite retirement without claiming
ownership of arbitrarily late protocol-invalid resources.

The public publisher factory Javadoc, README, migration guide, and canonical
plan now state these requirements and the never-arrives capacity consequence.

## Validation

**337 tests passed in 25 suites, zero failures/errors/skips, on Amazon Corretto
17.0.20.1.** The [manifest](streaming-api-evidence/milestone-3d-2026-09-21/tests.json)
and [run log](streaming-api-evidence/milestone-3d-2026-09-21/tests.log) include:

- Nine HTTP adapter tests covering pending acquisition, late blocked/throwing
  cancel, retained admission and recovery, synchronous and asynchronous terminal
  tails, late provider failure, duplicate delivery, and failed acquisition.
- Three simulator tests covering late blocked cancel, 503 admission while work
  remains, restored admission after physical exit, missing-subscription shutdown
  residuals, zero demand, and late diagnostics.
- Five new coordinator tests for retained publisher obligations, expiry,
  diagnostics, release, and acquisition/completion guards; plus the affected
  ownership, callback, transport, simulation, naming, generated-code, and MCP
  regressions.

The expanded run exposed an existing test-fixture race: the callback-failure
fixture closed its registration immediately after a failed write, which could
legitimately suppress an unclaimed asynchronous callback. The fixture now keeps
that callback registered for the response lifetime. Production registration
semantics are unchanged.

The [clean package/API-report build](streaming-api-evidence/milestone-3d-2026-09-21/api-build.log)
passes on Java 17. API parser self-tests and report-pair consistency pass. The
[API delta](streaming-api-evidence/milestone-3d-2026-09-21/api-delta.json)
has zero added, removed, or changed incompatibility records from slice 3c
(701 records). The generated
[MCP signature inventories](streaming-api-evidence/milestone-3d-2026-09-21/mcp-signatures.json)
remain byte-identical. The aggregate compatibility gate retains its existing
46 unexpected/four missing records, and ownership inventory retains the existing
unassigned `ResourcePathDeclaration$Component`. No historical baseline or
reviewed incompatibility set was rewritten.

The [source identity](streaming-api-evidence/milestone-3d-2026-09-21/source-identity.json)
records this uncommitted checkpoint. This slice makes no new throughput claim
and is not release qualification.

## Next work

Implement output views and write helpers, then complete scalar-write buffering,
accepted-prefix interruption semantics, public server settings, and associated
parity/performance qualification. ZIP tests must validate the central directory.
The overall milestone 3 exit remains open; SSE ownership and independent
passive-disconnect detection remain separate milestones.

Release qualification still needs the recorded API/evidence reconciliation,
candidate-bound privacy inventory regeneration, and full benchmark-module build
blocker resolved. Historical milestone reports retain their original scope and
limitations as evidence of those earlier checkpoints.
