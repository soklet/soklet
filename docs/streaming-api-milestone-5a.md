# Milestone 5a — SSE connection ownership and simulator integration

**September 23 amendment:** this report describes the superseded SSE
connection-ownership candidate. The final 4.0 design keeps synchronous checked
client initialization and connection admission, removes the added unicaster
ownership/status/termination operations, and uses broadcasters for ongoing
delivery. Measurements and source claims below remain historical.

Implemented 2026-09-22 in the isolated streaming worktree based on `c5d02871`.
The selected SSE ownership surface now works in the built-in server and simulator.
Milestone 5 remains open for connection-lifetime saturation, load, and memory
qualification of its defaults.

## Public API

`SseUnicaster` remains a retained, thread-safe handle. It now exposes:

```java
Request getRequest();
Boolean isOpen();
<T extends AutoCloseable> T open(
    StreamResourceFactory<? extends T> streamResourceFactory) throws Exception;
CallbackRegistration onTermination(
    Consumer<StreamTermination> streamTerminationConsumer);
```

`SseHandshakeResult.Accepted.Builder.clientInitializer(...)` and its corresponding
getter use the standalone checked `SseClientInitializer`, whose sole method is
`initialize(SseUnicaster sseUnicaster) throws Exception`. Passing `null` clears the
initializer. The former `Consumer<SseUnicaster>` overload is removed.

```java
return SseHandshakeResult.Accepted.builder()
    .clientInitializer(sseUnicaster -> {
        sseUnicaster.open(() -> eventBus.subscribe(sseUnicaster::unicastEvent));
    })
    .build();
```

The subscription survives initializer return. Connection termination schedules
one managed close attempt, which can overlap provider callbacks. Providers must
support that concurrent close contract. A factory that returns after termination
hands its result to managed disposal and the acquisition throws the winning
`StreamingResponseCanceledException`; capacity remains held until disposal exits.
Factories that throw before returning remain responsible for partial acquisition.
There is no SSE `own`, lexical `using`, or separate-abort operation.

`isOpen()` is advisory queue acceptance, including initialization. Successful
unicast is not delivery acknowledgment. Terminal unicast rejects; initializer or
active queue overflow elects `BACKPRESSURE`, even if application code catches the
exception. Initializers retain their bounded setup/catch-up role.

## Runtime and supervision

The server reserves lifecycle capacity before accepted handshake headers and
before initializer exposure. Exhaustion uses the configured service-unavailable
response. Handshake serialization still validates headers before the connection
owner takes socket ownership, preserving a failsafe HTTP 500 for invalid accepted
handshake headers.

One shared `ManagedSseLifecycle` implements ownership and termination in live and
simulated connections. The existing coordinator now supports retained physical
work proofs alongside its producer and terminal-job accounting. Live SSE retains
the entire admitted handshake, the initializer execution, and the asynchronous
connection execution envelope. Queued execution retires only after actual entry
and exit, executor rejection, or exact removal by `shutdownNow()`.

Activation and queue mutation share the connection's termination guard. A late
initializer cannot register a connection after termination. Initializer return
neither completes the connection nor closes its subscriptions. Generic retained
work also accounts for concurrent factories, resources, and simulator consumer
execution. Cleanup expiry records overdue work without returning its capacity.

The first termination outcome wins and remains immutable for late listeners.
Outcome reads also cover the gap between coordinator election and delivery of
its framework hook. Listener registrations have independent claim/removal state;
closing a registration suppresses only an unclaimed invocation. Listener failures
are isolated. Late registration may replay on its application caller.

Each connection publishes at most two terminal jobs: notification first, then
resource cleanup, using the coordinator's existing two-job admission bound.
Neither job waits for initializer exit. Cleanup closes available resources and
then awaits already-admitted factory results for disposal. A blocked resource
close does not precede that connection's notification in the dispatcher. Other
connections can still saturate the configured workers; this remains bounded,
accounted work, not a guarantee that arbitrary application callbacks finish.

Detected write failure and broadcaster overflow elect termination before invoking
application failure observers or metrics. Their blocking cannot postpone owned
resource cleanup or the termination notification. The transport hook closes the
socket and clears queued application payloads without calling user code.

SSE shutdown elects `SERVER_STOPPING` during quiesce, closes connections, and
interrupts entered initializers. Accepted events may be discarded. The old test
requiring two queued events to drain was updated to the selected contract while
retaining EOF, termination-reason, and worker-exit checks. Shutdown still uses its
configured graceful/forced deadlines to await physical work and reports `STREAM`,
`CALLBACK`, and executor residuals when appropriate.

## Settings and simulation

| `SseServer.Builder` setting | Current provisional default | Build-time validation |
|---|---|---|
| `streamingLifecycleCapacity(Integer streamingLifecycleCapacity)` | 256 | 1 through `Integer.MAX_VALUE / 2` |
| `streamingCallbackConcurrency(Integer streamingCallbackConcurrency)` | 4 | 1 through effective lifecycle capacity |
| `streamingCleanupTimeout(Duration streamingCleanupTimeout)` | Five seconds | Positive and representable in nanoseconds |

All accept `null` to restore defaults, resolve together independently of setter
order, and impose no construction-time ordering against response/shutdown
budgets. These settings now govern live and simulated SSE lifetimes. The existing
128-write default connection queue remains a separate bound. The HTTP default
qualification does not qualify these SSE defaults.

`SseRequestResult.HandshakeAccepted` now implements `AutoCloseable`. Its unchecked,
idempotent `close()` simulates `CLIENT_DISCONNECTED`; scope teardown elects
`SERVER_STOPPING` for remaining lifetimes, including accepted connections without
registered consumers. The first outcome wins. Both paths remove broadcaster
registration, clear buffered payloads and consumer references, and reject later
consumer registrations or writes while preserving handshake metadata.

Simulator derivation snapshots the built-in SSE server's three lifecycle settings
and connection queue capacity, including re-derivation. It does not retain/start
the source server; custom transports use SSE defaults. Initializer failures clean
up ownership before any result escapes. Capacity rejection preserves the configured
service-unavailable response. Synchronous consumer execution runs outside lifecycle
locks with physical-work accounting; broadcast and unicast error hooks retain
their distinct behavior. Blocked cleanup is exposed as shutdown residual activity.

## Verification

- **198 tests pass on Corretto 21.0.11.10.1**, including the existing 82-test SSE
  suite, live ownership/admission/shutdown races, queued executor removal,
  detected disconnect, write timeout, blocking failure observer, simulator parity,
  shared lifecycle, public contracts, and settings. No failures, errors, or skips.
  See the [Java 21 manifest](streaming-api-evidence/milestone-5a-2026-09-22/java21-tests.json)
  and [log](streaming-api-evidence/milestone-5a-2026-09-22/java21-tests.log).
- **453 selected tests pass on Corretto 17.0.20.1**, covering HTTP streaming,
  output/ownership/publisher supervision, simulator isolation, SSE simulation,
  shared coordinator changes, public contracts, and MCP regressions. Live SSE
  requires Java 21 and is covered by the run above. No failures, errors, or skips.
  See the [Java 17 manifest](streaming-api-evidence/milestone-5a-2026-09-22/java17-tests.json)
  and [log](streaming-api-evidence/milestone-5a-2026-09-22/java17-tests.log).
- The [clean Java 17 package/API-report build](streaming-api-evidence/milestone-5a-2026-09-22/api-build.log),
  API extraction/self-tests, and matched report-pair validation pass. The
  [incompatibility delta](streaming-api-evidence/milestone-5a-2026-09-22/api-delta.json)
  grows from 704 to 712 records: four added interface methods, replacement of the
  initializer setter and getter generic type, and removal of the old unscoped
  nested unicaster constructor and protected mutable-queue accessor. No previous
  record is removed or changed.
- All four [generated MCP signature inventories](streaming-api-evidence/milestone-5a-2026-09-22/mcp-signatures.json)
  remain unchanged. Historical signature snapshots and the reviewed aggregate
  incompatibility baseline were not rewritten. The aggregate gate still reports
  [57 unexpected/four missing records](streaming-api-evidence/milestone-5a-2026-09-22/api-gate.log),
  including this slice's eight deliberate additions to the prior 49 unexpected
  records. The ownership inventory retains only the preexisting unassigned
  [`ResourcePathDeclaration.Component`](streaming-api-evidence/milestone-5a-2026-09-22/api-inventory.log).
  These release gates are not represented as passing.

The [source identity](streaming-api-evidence/milestone-5a-2026-09-22/source-identity.json)
records this uncommitted implementation, canonical plan, and evidence hashes.

## Next slice

Qualify SSE connection-lifetime defaults with capacity/worker saturation,
long-lived connection load, retained subscription/queue footprint, and teardown
measurements. The current integration tests establish behavior, not default
sizing or performance. Milestone 5 is not complete until that evidence is recorded.

Passive HTTP disconnect detection remains the independent milestone 4 track.
Downstream consumer builds, development-JDK/release qualification, candidate privacy
inventory regeneration, aggregate API reconciliation, and the previously recorded
benchmark-module compile blocker remain milestone 6/release work.
