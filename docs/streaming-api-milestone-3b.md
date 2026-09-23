# Milestone 3b — one response stream per writer

Implemented 2026-09-21 in the isolated streaming worktree based on `c5d02871`.
This is the second production integration slice of milestone 3. The selected
`open`, `own`, and `using` names remain unchanged.

## Implemented

`StreamingResponseWriter.writeTo(ResponseStream responseStream)` now receives
one argument. `ResponseStream` exposes the originating `Request`, its stable
`CancelationToken`, and optional deadline and idle timeout. Cancelation checks
and registrations go through the token. The public `StreamingResponseContext`
and both private runtime context classes are removed without aliases.

HTTP and simulator writers receive output and metadata on the same object.
The HTTP adapter retains its existing deadline, idle timeout, token, request,
and lifecycle reservation; internal source/publisher cleanup still starts
supervision before finalization. Simulator metadata reports its request/token
and empty timing policy, and successful materialization still completes the
token before the termination observer runs.

Builders and copiers now accept `.stream(StreamingResponseWriter)`. Registration
is lazy and delegates to the existing descriptor setter, retaining body/header/
status validation. A known-length body must be explicitly removed when switching
to streaming. `streamingResponseBody(null)` and `withoutStreamingResponseBody()`
still clear the descriptor; `.stream(null)` is rejected. Copiers use `finish()`.

```java
return MarshaledResponse.withStatusCode(200)
    .stream(responseStream -> {
        responseStream.getCancelationToken().throwIfCanceled();
        responseStream.write(responseStream.getRequest().getId().toString()
            .getBytes(StandardCharsets.UTF_8));
        responseStream.flush();
    })
    .build();
```

The writer interface no longer declares all implementations thread-safe. Its
contract distinguishes a reusable callback object (which may have concurrent
response invocations) from each response's producer-thread output operations.
Metadata and the thread-safe token can be shared. Runtime confinement checks and
full post-lifetime invalidation will land with managed ownership/finalization;
this signature migration does not claim those remaining behaviors are complete.

All active Java writer callers, including benchmark callers, have migrated.
The README, migration guide, and changelog describe the implemented surface.
Historical prototype and milestone evidence remain unchanged.

## Validation

**225 tests passed, zero failures/errors/skips, on Amazon Corretto 17.0.20.1.**
The [test manifest](streaming-api-evidence/milestone-3b-2026-09-21/tests.json)
records 15 suites across the [regression run](streaming-api-evidence/milestone-3b-2026-09-21/tests.log)
and [new runtime tests](streaming-api-evidence/milestone-3b-2026-09-21/runtime-tests.log).
These cover body selection and copying, lazy/HEAD suppression, request identity,
stable token identity, fresh per-execution streams/tokens, configured/disabled
HTTP timeouts, simulator metadata, existing cancelation/lifecycle races, request
transport, generated processor code, and public naming contracts.

The clean Java 17 package/API-report build compiles all 582 production and 380
test sources. Both migrated standalone benchmark callers compile against those
production classes. The API parser self-tests and report-pair consistency check
pass. Reflection tests confirm the removed context is absent after a clean build.

The [API delta](streaming-api-evidence/milestone-3b-2026-09-21/api-delta.json)
adds 18 report records over slice 3a and changes one existing record: the old
context's `onCancel` return-type change is now a method removal. The additions
cover the removed context/members, writer arity, four abstract metadata getters,
and three redundant checked-subtype declarations removed from write/flush.
Those operations still throw `IOException`, including its
`StreamingResponseCanceledException` subtype; the report flags the declaration
change separately. No other prior incompatibility record changes or disappears.

The aggregate reviewed-compatibility gate remains red with 41 unexpected and
four missing records (696 total incompatibility records). Assigning the touched
non-MCP types and removing the deleted context's owner entry leaves one existing
unassigned owner, `ResourcePathDeclaration$Component`, versus four before this
slice. All four generated MCP signature inventories are byte-identical to
milestones 1 and 3a; see the [comparison](streaming-api-evidence/milestone-3b-2026-09-21/mcp-signatures.json).
Historical signature snapshots and the reviewed incompatibility set are unchanged.

The [source identity](streaming-api-evidence/milestone-3b-2026-09-21/source-identity.json)
records this uncommitted checkpoint, including the explicit file deletion.
This slice is not release qualification.

## Remaining milestone 3 work

Integrate the selected `open`/`own`/`using` resource lifetimes and coordinated
close/abort behavior with supervised finalization, in HTTP and simulation.
Then complete output views, buffering, public server settings, and simulator
supervision/parity qualification. No ownership names are being reconsidered.

Release qualification must reconcile the existing aggregate API/frozen-evidence
drift and regenerate the candidate-bound privacy inventory: it still records
the removed context carriers and must record their replacements. Historical
candidate inventories are not hand-edited to bless this intermediate slice.
The separately recorded full benchmark-module build blocker remains; compiling
the two migrated standalone callers does not clear that blocker.
