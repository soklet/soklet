# Milestone 3a — registrations and checked source factories

Completed 2026-09-21 in the isolated streaming worktree based on `c5d02871`.
This is the first production integration slice of milestone 3. The selected
`open`, `own`, and `using` names remain unchanged.

## Implemented

`CancelationToken.onCancel(Runnable)` now returns the public
`CallbackRegistration`, whose idempotent `close()` has no checked exception.
HTTP, simulator, MCP state machines, wrappers, and anonymous token implementations
have migrated together. The existing `throwIfCanceled()` default stays intact.
The context delegate also returns the new type while the two-argument writer
still exists in this intermediate slice.

Each callback registration owns an independent claim/removal entry. Removing the
same handle twice cannot remove another registration of the same callback.
Removal after a batch has been detached still suppresses an unclaimed callback;
removal never waits for an already-claimed callback. Entries clear application
callback references on removal, invocation claim, or normal completion. MCP's
reason reservation, deferred delivery, pre-invocation discard, and completion
transitions remain separate from HTTP's state machine.

The simulator now completes its token before successful termination observation,
releases callbacks, and makes later registrations inert without marking the
token canceled. Callback failure and diagnostic failure cannot prevent delivery
to other registrations. HTTP token queries check production completion after
reading coordinator cancelation state, so a concurrent successful production
transition followed by a transport failure cannot become a producer-token
cancelation observation.

`StreamResourceFactory<T extends AutoCloseable>.open() throws Exception` replaces
`Supplier` for input-stream and reader response descriptors. The descriptor
getters are `getInputStreamFactory()` and `getReaderFactory()`. Both runtime
adapters invoke these checked factories lazily; construction and HEAD handling
do not acquire a source. Acquisition failures retain their original cause and
are classified as producer failures. There are no old supplier overloads.

For example, source acquisition can now propagate `IOException` directly:

```java
StreamingResponseBody body = StreamingResponseBody.fromInputStream(
    () -> Files.newInputStream(path));

CallbackRegistration registration = cancelationToken.onCancel(callback);
registration.close(); // No checked exception from removal.
```

## Validation

**150 tests passed, zero failures/errors/skips, on Amazon Corretto 17.0.20.1.**
The [test manifest](streaming-api-evidence/milestone-3a-2026-09-21/tests.json)
records all 15 suites, with the [full run log](streaming-api-evidence/milestone-3a-2026-09-21/tests.log).
All production and test sources compile; a subsequent clean package/API-report
build also passes on Java 17.

New checks cover duplicate registrations, removal after batch detachment,
non-waiting close during invocation, reentrant/late delivery, failure isolation,
callback-reference release, and the simulator's actual completion call before
its success observer. Checked-source tests exercise input streams and readers
through both real HTTP and simulation: lazy construction, repeated execution,
normal close, checked factory failure, null return, and HEAD suppression.
Existing supervised lifecycle, transport races, MCP application/progress,
processor-generated runtime, and API-inventory tests also pass.

The public API report adds exactly **eight intentional incompatibility records**
over the milestone 1 report: two registration return types, four source-factory
entry points, and two descriptor getters. The
[exact delta](streaming-api-evidence/milestone-3a-2026-09-21/api-delta.json)
contains no removed or altered preexisting incompatibility records. Seven new
non-MCP owner entries classify these types in the current ownership inventory.

The aggregate compatibility gate remains red: 23 unexpected records and four
missing records against the reviewed file, comprising the existing 15/four
drift plus this slice's eight deliberate changes. After assigning the seven
new owners, the inventory check has exactly the same four preexisting unassigned
owners. Generated signatures for all three MCP phase inventories and the
provisional inventory are byte-identical to milestone 1, as recorded in the
[signature comparison](streaming-api-evidence/milestone-3a-2026-09-21/mcp-signatures.json).
The historical signature snapshots and reviewed incompatibility set are
unchanged. This slice is not release qualification; their reconciliation remains
the documented candidate-validation work.

The [source identity](streaming-api-evidence/milestone-3a-2026-09-21/source-identity.json)
records the source and test hashes for this slice. Evidence is a checkpoint of
an uncommitted worktree, not a published or approved release.

## Next slice

Move request/token/deadline metadata onto `ResponseStream`, adopt the one-argument
writer, add the builder/copier convenience, and remove the public
`StreamingResponseContext` while migrating its internal callers. Then integrate
the selected ownership operations and output views with supervised finalization.

This slice does not implement public `open`/`own`/`using`, output-view buffering,
the new public server settings, or SSE connection ownership. In particular,
the simulator's full resource-close coordination and bounded cleanup supervision
remain part of the remaining milestone 3 work. No compatibility aliases or
replacement ownership names were introduced.
