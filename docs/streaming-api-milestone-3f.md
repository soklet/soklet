# Milestone 3f — HTTP streaming server settings and qualification

Implemented 2026-09-22 in the isolated streaming worktree based on `c5d02871`.
The three selected HTTP builder settings now control the existing supervised
runtime. Their names and validation contract match the milestone 2 decision.

## Public configuration

| `HttpServer.Builder` method | Default | Effective value required at `build()` |
|---|---|---|
| `streamingLifecycleCapacity(Integer streamingLifecycleCapacity)` | 256 | 1 through `Integer.MAX_VALUE / 2` |
| `streamingCallbackConcurrency(Integer streamingCallbackConcurrency)` | 4 | 1 through the effective lifecycle capacity |
| `streamingCleanupTimeout(Duration streamingCleanupTimeout)` | Five seconds | Positive and representable in nanoseconds |

Each setter accepts `null` to restore its default. Defaults are resolved together
at build time; temporary invalid values and setter order do not constrain a
valid final configuration. Resetting concurrency means four workers, without
silently shrinking it to a lower capacity. Response and shutdown timeouts impose
no construction-time ordering on cleanup grace. Invalid builds allocate no
coordinator, worker, or callback queue.

Built servers retain immutable effective values. The production coordinator uses
those values when the server starts. Existing internal test factories remain
available but the new runtime acceptance tests use the public settings.

A lifecycle slot covers production, delivery, and outstanding cleanup/callback
work through physical exit. Expired cleanup does not release the slot. Admission
exhaustion produces HTTP 503 before streaming commitment or producer execution.
The callback setting bounds cancelation/termination workers separately from
producers; owned resource finalization still runs on the producer thread. A
cleanup timeout limits supervision, not arbitrary application execution.

## Simulator derivation

`SimulatorConfig.withSokletConfig(...)` snapshots these three effective values
from a built-in HTTP server into a fresh off-network server. It copies only
immutable values, without retaining or starting the source transport. Deriving
again from a simulator configuration preserves the snapshot. Default simulator
construction and arbitrary custom HTTP transports use the shared HTTP defaults;
the framework does not attempt to unwrap or introspect a custom transport.

Each simulation creates its own coordinator lazily from that snapshot. Admission,
callback concurrency, and cleanup expiry therefore reflect a built-in production
configuration without sharing its runtime state. No public simulator option or
transport getter is added.

## Validation

- **419 selected Java 17 tests pass** across 36 suites, with no failures, errors,
  or skips. The [manifest](streaming-api-evidence/milestone-3f-2026-09-22/tests.json)
  and [run log](streaming-api-evidence/milestone-3f-2026-09-22/tests.log) include
  builder bounds/reset/order/reuse, public signature names, HTTP and simulator
  admission/worker limits, blocked finalizers, transport lifecycle, output views,
  publisher accounting, and MCP regression coverage.
- Real HTTP tests configure three slots, two callback workers, and 125 ms grace:
  two blocked observers run, the third queues, all three become overdue, and
  admission remains full until physical return. A separate one-slot/100 ms
  finalizer case produces `CLEANUP_TIMEOUT`, retains capacity, and then recovers.
  Simulator behavior tests reproduce inherited limits without injecting a
  coordinator factory and verify that the source server stays untouched.
- The [clean package/API-report build](streaming-api-evidence/milestone-3f-2026-09-22/api-build.log),
  API self-tests, and matched report-pair validation pass. The three additive
  concrete builder methods create no new incompatibility records: the
  [704-record set is unchanged](streaming-api-evidence/milestone-3f-2026-09-22/api-delta.json).
  All four [MCP signature inventories](streaming-api-evidence/milestone-3f-2026-09-22/mcp-signatures.json)
  also remain unchanged.
- The aggregate reviewed gate still reports 49 unexpected and four missing
  records, with no changed records. The inventory check still reports only the
  preexisting unassigned `ResourcePathDeclaration.Component` owner. See the
  [gate log](streaming-api-evidence/milestone-3f-2026-09-22/api-gate.log) and
  [inventory log](streaming-api-evidence/milestone-3f-2026-09-22/api-inventory.log).
  Those release checks are not represented as passing.

Qualification found that scalar output repeatedly took the shared coordinator
lock to poll cancelation. The final runtime reads the monotonic cancelation and
production-completion flags through volatile fields; elections and paired
reason/cause getters keep their existing lock. The HTTP token takes that locked
exceptional path only after a positive status check. New controlled races cover
reserved cancelation before source-hook publication, late transport failure after
normal production, and status polling while another stream's transition is
paused. The [qualification report](streaming-api-qualification-2026-09-22.md)
records the before/after measurements and final memory evidence.

The [source identity](streaming-api-evidence/milestone-3f-2026-09-22/source-identity.json)
records this uncommitted slice, canonical plan, and evidence hashes.

## Remaining work

Performance/allocation and full-capacity memory qualification of the final
output path are recorded in the linked report. SSE receives these selected names with its
connection-lifetime integration and default qualification in milestone 5. The
passive-disconnect transport work remains an independent track.

Existing aggregate release-gate drift, candidate-bound privacy inventory
regeneration, and the previously recorded full benchmark-module compile blocker
remain release work. Historical reviewed signature snapshots remain unchanged.
