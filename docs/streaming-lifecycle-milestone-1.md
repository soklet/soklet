# Streaming lifecycle implementation checkpoint — 2026-09-21

This is the isolated implementation experiment for milestone 1 of the
[streaming API plan](/Users/Shared/ai-shared/soklet/SOKLET_STREAMING_API_PROPOSED_PLAN_2026-09-20.md),
based on core commit `c5d02871`. Public writer/ownership API migration has not started. These
results are development evidence, not release-candidate approval. The
[qualification follow-up](streaming-lifecycle-qualification.md) completes this
bounded-runtime feasibility checkpoint, selects defaults, and records the
remaining performance cost and preexisting release-check failures.

## Implementation

`DefaultHttpServer` owns a `StreamLifecycleCoordinator` for each runtime
generation. Before returning a streaming response to the transport, it acquires
a lifecycle reservation and submits a producer execution envelope. The envelope
waits for transport activation before invoking the writer, acquiring a source,
or subscribing to a publisher. Capacity exhaustion and executor rejection return
the existing failsafe `503` response before streaming headers are committed.
An executor that attempts to execute the envelope inline is rejected before it
can invoke application code or wait for transport activation.

Each reservation tracks accepted producer work and terminal callback work until
physical exit or proven removal from the producer executor. Canceling a future
is not retirement evidence. A canceled task retained by a custom executor keeps
its reservation until the envelope runs and skips application entry. The exact
envelopes returned by `shutdownNow()` can be retired immediately.

Cancelation records the winning reason, invalidates output, wakes waiters, and
interrupts an active producer before publishing application callbacks to a
separate bounded executor. The supervisor and diagnostic executor are also
server-owned. Arbitrary application callbacks and logging do not execute on the
timer or event-loop thread. Shutdown stops admission, preserves terminal-work
publication for admitted streams, and waits only to its enclosing deadline.
Retained work contributes `STREAM`, `CALLBACK`, and `EXECUTOR_TASK` evidence as
appropriate. Late physical exit retires accounting without rewriting a sealed
shutdown result.

The stream timeout service remains available during graceful drain, so an
admitted producer can continue writing and renew its idle deadline. It shuts down
after producer and transport termination, or immediately in the forced phase.

The input-stream and reader adapters now share one physical close claim between
abort and normal finalization. An internal `beginFinalization(context)` bridge
starts supervision before producer-thread cleanup; milestone 3 will connect this
to the public ownership scope. Existing arbitrary writer code cannot have its
internal cleanup boundary inferred automatically.

Read, encoding, and publisher-subscription failures are reserved before cleanup
can block. Source close and subscription cancel failures use the bounded
diagnostic path without replacing an already chosen transport reason.

## Contracts clarified by integration

- A completed producer token remains completed when later transport delivery
  fails. Its old callbacks are released and late registrations are inert.
- Normal finalization and cancelation share one monotonic cleanup deadline.
  Repeated signals and individual resource actions do not restart it. Response
  timeout does not consume the cleanup grace in advance.
- Normal-finalization expiry elects `CLEANUP_TIMEOUT` if no prior failure won,
  with a separate `CleanupDeadlineExceededException` diagnostic identifying the
  reservation and outstanding work. A previously chosen reason retains
  precedence. Cleanup may still be physically running.
- Once production completes normally, a slow healthy transport is governed by
  response/write-idle policy. The producer cleanup budget must not impose an
  extra timeout on delivery. A later termination observer receives a separate
  observation grace, because it could not have run during producer cleanup.
  Cancelation while production is active retains the original deadline for all
  its terminal work.
- This experiment admits two terminal jobs per reservation: cancelation work
  and transport-observer notification. Keeping separate jobs allows an available
  worker to report termination while another callback blocks. If every worker
  blocks, subsequent jobs remain queued and count against admission.
- The lifecycle reservation currently lasts through transport termination and
  observer completion. This is a conservative bound, and means a slow receiver
  consumes lifecycle capacity even after its producer exits. Separating producer
  capacity from transport-observer capacity requires an explicit bounded design;
  early release is not implemented by forgetting the observer obligation.

## Selected defaults and qualification

The qualified HTTP defaults are 256 lifecycle slots, four callback workers, and
a five-second cleanup grace. The initial 1,024-slot experiment has been reduced
after measuring full output queues; see the qualification follow-up for the
selection rationale and validation contract. Public builder names and placement
remain milestone 2 work. Tests inject small per-server settings without
process-global state.
Callback queue capacity is twice lifecycle capacity, with at most two jobs per
reservation. The diagnostic queue admits at most one diagnostic per reservation.
Deadline timers remain bounded by admitted lifetimes and remove canceled tasks.
Diagnostic retention is limited to the first reported failure per reservation.
A subsequent deadline still marks the live reservation overdue, but does not
enqueue a second diagnostic behind a blocked diagnostic observer.

The bounded-runtime review supports proceeding to compiled public naming
fixtures. No backcompat layer or frozen MCP signature baseline has been changed.
Passive disconnect detection and SSE ownership remain separate work packages.
The qualification report preserves the tiny-response throughput regression and
identical baseline/current API-gate failures; this is not release approval.

The publisher adapter still needs an explicit asynchronous acquisition contract
in milestone 3. If a publisher returns from `subscribe()` and delivers its first
`onSubscribe()` only after cancelation and retirement, the adapter cancels that
late subscription on the publisher's calling thread and never requests data.
That external callback is not represented by a retained framework acquisition
obligation in this experiment. Do not claim full publisher lifetime containment
from the producer/callback accounting tests. Resolve pending-acquisition
accounting, late callbacks, and simulator parity before qualifying the new public
ownership API.

## Verification and remaining effort

The Java 17 regression batch passes **176 tests** with no failures, errors, or
skips. Eight benchmark parser/diagnostic tests also pass in isolation. The full
benchmark-module build and aggregate API freeze checks have verified preexisting
failures documented in the [qualification report](streaming-lifecycle-qualification.md).
Reviewed release snapshots were not regenerated to hide those failures.

The qualification compares tiny, bulk, and paced streams against pushed
`c5d02871`: tiny throughput remains about 14% lower, bulk throughput about 1.5%
lower, and paced throughput effectively unchanged. A 128-client check with a
suitable explicit listen backlog validates 289,676 responses without errors.
Three full-buffer probes retain about 261 MiB for 256 streams, then release all
reservations and owned threads. The earlier idle-reservation and throughput
artifacts remain in `streaming-lifecycle-evidence/` as historical development
measurements; `qualification-2026-09-21/` contains the follow-up evidence.

Milestone 1's bounded-runtime feasibility checkpoint is complete. The remaining
passes are public naming and compiled examples; HTTP ownership integration;
adapter/simulator parity including asynchronous publisher acquisition; SSE
connection ownership; and consumer/documentation/performance/release
qualification. Passive disconnect detection remains independent. The full
redesign is not implemented yet, and these passes are an effort breakdown,
not a promised calendar completion date.
