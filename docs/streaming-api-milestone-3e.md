# Milestone 3e — Java I/O views and write helpers

Implemented 2026-09-21 in the isolated streaming worktree based on `c5d02871`.
This slice adds the selected output views and helpers. Shared scalar staging and
accepted-prefix accounting are included because they are necessary for the
views' ordering, memory, and interruption contracts.

## Implemented

The new public methods match the milestone 2 signatures and parameter names:

```java
void write(byte[] bytes, Integer offset, Integer length)
    throws IOException, InterruptedException;
void writeUtf8(String string) throws IOException, InterruptedException;
OutputStream asOutputStream();
```

Reference parameters are non-null. Native slices use the selected boxed
`Integer` convention; Java `OutputStream` overrides retain their standard
primitive signatures. Array bounds are validated before bytes are accepted or
older staging is drained. Native `ByteBuffer` calls preserve the caller's
position, limit, and mark, including during partial failure.

Each view has its own closed state, while all views use one producer-confined,
lazily allocated scalar buffer. Bulk/native writes drain older staged bytes
first, preserving mixed-call order. View close flushes shared staging and closes
that view even if flushing fails. Repeated close on its producer thread is a
no-op; other views and native output remain usable while the response permits
writes. A view cannot close the socket or seal the HTTP response.

Owned encoders finalize before the final shared flush. The runtime tests use
`own(new ZipOutputStream(responseStream.asOutputStream()))`, then open the
completed archive with `ZipFile` and verify entries, metadata, and content from
its central directory. An owned UTF-8 `OutputStreamWriter` also flushes its
buffered content during finalization. A finalizer that writes bytes and then
fails still cannot produce successful completion.

Closed/expired view writes and flushes throw `IOException`; native operations
outside their lifetime throw `IllegalStateException`. Both retain producer-thread
confinement. A rejected foreign-thread close leaves the valid owner's view open.
Empty writes and flushes still check cancelation and lifetime.

## Partial acceptance and interruption

Internal sinks now advance their supplied private `ByteBuffer` only by the
accepted prefix. HTTP advances at queue insertion, before later activity/wakeup
work can fail; copying into a temporary pending chunk is not acceptance. The
simulator advances after copying into its captured body. Native and publisher
entry points duplicate application buffers before passing them to these sinks.

An output view converts framework `InterruptedException` to
`InterruptedIOException`, retains the cause, restores the interrupt flag, and
reports accepted bytes from the current call in `bytesTransferred`. Older staged
bytes do not contribute. Interruption while draining old staging before a new
bulk/scalar write reports zero for the new call; flush and close also report
zero. Scalar writes drain a full stage before accepting the next byte.

A previously elected typed cancelation reason wins over bridge translation;
an observed interruption still restores its flag. An otherwise unclassified
interruption elects `APPLICATION_CANCELED` for both native and view output.
`SocketTimeoutException` remains its original I/O failure. I/O failure or
interruption during valid output remains terminal even when caught, and failed
staging is discarded without replaying a prefix. Argument, thread, and lifetime
validation failures do not invalidate otherwise usable output.

## Buffer and activity bounds

HTTP scalar staging is at most `min(8192, chunkSize, queueCapacity)` bytes per
response. The simulator uses `max(1, min(8192, captureLimit))`, so a zero capture
limit cannot create a zero-length buffer. Views allocate no separate payload
buffers. `writeUtf8` uses at most 8192 bytes of transient encoding scratch,
independent of queue size, and replaces malformed surrogate sequences with `?`
like `String.getBytes(UTF_8)`. One-byte queues can therefore process supplementary
characters without an encoder overflow loop.

The HTTP framework payload bound is the configured queued payload (including
the currently draining chunk), plus at most one pending chunk of
`min(chunkSize, queueCapacity)`, plus the lazy scalar stage, plus UTF-8 scratch
while that helper runs. Caller/provider buffers and Java object overhead are
additional. At 256 admitted responses, maximum 8 KiB staging adds at most 2 MiB;
simultaneously active UTF-8 helpers can add another 2 MiB. These are additional
bounds, not a rerun of the historical milestone 1 retained-heap measurement.

Scalar acceptance updates a monotonic activity timestamp. The idle timer checks
that timestamp and reschedules only when its current check expires, avoiding a
timer allocation per scalar byte. Normal queued writes use the same coalescing.
Cancelation prevents later idle checks from rearming the timer. Deadline and
idle timer publication share a locked stop state, closing an existing race where
response close could miss a concurrently scheduled deadline timer. Controlled
scheduler tests also cover callbacks already running at close and reentrant
termination before a scheduled future is returned.

Deterministic batching tests show 100 scalar writes through 100 views sharing
one four-byte buffer and producing 25 sink writes. The idle test stages 1000
bytes without scheduling another timer, then proves later scalar activity moves
the effective deadline. These are correctness/allocation-shape checks, not a
throughput or full-heap qualification claim.

## Validation

- **374 selected Java 17 tests pass** across 31 suites, with no failures, errors,
  or skips. Coverage includes managed output, HTTP/simulator views, accepted
  prefixes under interrupted queue waits, timer activity/publication races,
  ownership and publisher supervision, transport, generated code, and MCP
  simulation. See the [test manifest](streaming-api-evidence/milestone-3e-2026-09-21/tests.json)
  and [run log](streaming-api-evidence/milestone-3e-2026-09-21/tests.log).
- The clean package and paired API-report build pass, as do API-tool self-tests
  and report-pair validation. The two standalone migrated benchmark callers
  compile with Java 17. See the [build log](streaming-api-evidence/milestone-3e-2026-09-21/api-build.log)
  and [caller compilation](streaming-api-evidence/milestone-3e-2026-09-21/benchmark-callers.log).
- The [API delta](streaming-api-evidence/milestone-3e-2026-09-21/api-delta.json)
  adds exactly the three selected abstract methods, moving canonical report
  records from 701 to 704 with no changed or removed earlier records. All four
  [MCP signature inventories](streaming-api-evidence/milestone-3e-2026-09-21/mcp-signatures.json)
  remain unchanged from slice 3d.
- The reviewed aggregate API gate remains intentionally unreconciled: 49
  unexpected records (46 before this slice, plus these three additions), four
  missing, and no changed records. The inventory check retains its preexisting
  single unassigned `ResourcePathDeclaration.Component` owner. These failures
  remain visible in the [gate log](streaming-api-evidence/milestone-3e-2026-09-21/api-gate.log)
  and [inventory log](streaming-api-evidence/milestone-3e-2026-09-21/api-inventory.log);
  this is not release approval.

The [source identity](streaming-api-evidence/milestone-3e-2026-09-21/source-identity.json)
records this uncommitted slice, canonical plan, and evidence hashes. Only Javadoc
clarifications followed the passing regression run; the clean package/API build
includes those clarifications. No performance or retained-heap claim is inferred
from these correctness tests or benchmark caller compilation.

## Next work

Implement the three selected public server settings with their validation and
defaults. Complete throughput/allocation and full-capacity qualification of the
final buffering path before declaring milestone 3 complete. SSE ownership and
the independent passive-disconnect work remain separate milestones.

Existing release-gate drift, candidate-bound privacy inventory regeneration,
and the previously recorded full benchmark-module compile blocker remain release
work. Historical reports and reviewed API snapshots are preserved.
