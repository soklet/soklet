# Streaming public API decisions — milestone 2

**September 23 amendment:** the SSE ownership/signature fixtures below are a
historical candidate. The final 4.0 direction keeps the checked
`SseClientInitializer` but limits `SseUnicaster` to synchronous initialization;
ongoing delivery uses `SseBroadcaster`. The original fixture receipt is preserved
as evidence of the candidate tested on September 21.

Date: 2026-09-21. This closes the public naming and compiled-handler gate for
the 4.0 streaming redesign with the restored `open`, `own`, and `using` family.
Production integration is milestone 3; SSE ownership
integration is milestone 5. These decisions deliberately break the previous API
without compatibility overloads or deprecation aliases.

The [signature fixtures](../prototypes/streaming-api-milestone-2/api/com/soklet),
[application examples](../prototypes/streaming-api-milestone-2/positive/fixtures),
and [alternative shapes](../prototypes/streaming-api-milestone-2/comparisons/fixtures/Alternatives.java)
compile against Java 17. They are compile-only declarations, not a second
implementation of lifecycle behavior. The decision preserves the bounded
supervision contracts qualified in [milestone 1](streaming-lifecycle-qualification.md).

## Ownership methods

Choose the following methods on `ResponseStream`:

| Operation | Ownership contract |
|---|---|
| `open(factory)` | Close may abort concurrent consumption. Normal finalization and cancelation coordinate one physical close attempt. |
| `open(factory, aborter)` | A separate provider abort operation may race use or an already-started final close. Abort and final close are each attempted once. |
| `own(resource)` | Transfer an already-created resource for finalization on the producer thread; no concurrent close-as-abort. |
| `using(factory, consumer)` | The coordinated close contract in a shorter lexical lifetime. |
| `using(factory, aborter, consumer)` | The separate-abort contract in a shorter lexical lifetime. |

Factories, aborters, consumers, and resources are non-null. Factories may throw
checked exceptions. One-argument `open` and two-argument `using` use coordinated
close-on-cancel; the overloads accepting an aborter use separate abort and final
close. A method name cannot verify provider safety: `AutoCloseable` alone does
not promise that close can safely interrupt consumption. The caller must choose
an operation supported by the resource provider.

`open(factory, Resource::close)` is legal Java, but it is not coordinated
close-on-cancel: that separate abort action and the final close may call close
twice. Do not document it as shorthand for `open(factory)` or suggest that
the type system prevents it.

Both `using` variants establish an ownership frame for **all** acquisitions and
adoptions inside their consumer, including further calls through the same
`responseStream`. Normal close runs in reverse ownership order before the call
returns. A page plus a parser therefore stays flat: acquire the page with
`using`, then `own` its non-owning parser inside that block.
Closing that parser must not independently close the separately owned page.
Resources acquired outside lexical blocks remain owned until root finalization.

Application code must not manually close an owned resource, wrap it in
try-with-resources, or use it after its owning lifetime. Identity-based duplicate
active ownership remains an error. Acquisition canceled before factory entry
does not call the factory. A resource returned after cancelation is disposed and
accounted for; a factory that throws before returning remains responsible for
its partially acquired state. These are runtime requirements for milestone 3,
not claims proven by compilation.

## Compared alternatives

The owner preferred the original `open`, `own`, and `using` family over the
longer `openWithCloseOnCancel` / `usingWithCloseOnCancel` experiment, which made
handlers more confusing. The restored overloads preserve the same concurrency,
close-once, and separate-abort contracts. The longer names are not kept as aliases.

The policy candidate uses `open(factory, ResourcePolicy.closeOnCancel())` and
`open(factory, ResourcePolicy.abortWith(Resource::cancel))`. Its contravariant
policy parameter preserves concrete `var` inference, including when a
`ResourcePolicy<AutoCloseable>` is reused. Both candidates compile correctly.
Select the direct overloads because they express the operation without
introducing a policy type and an extra policy argument at acquisition. The
provided HTTP, lexical, and SSE cases do not benefit from passing policies as
data. No rejected policy overload remains on the selected interface.

The lexical candidate uses a `within` block exposing the same `ResponseStream`
type; it need not invent a new public scope type. It works, but adds a block and
an acquisition statement for the single-page case. The selected `using` family
also handles page-plus-parser ownership in one block. Keep one lexical family,
with no permanent `within` alias or result-returning overload absent a use case.

## Checked callback types and response construction

Use top-level **`StreamResourceFactory<T extends AutoCloseable>`**, whose
`open()` returns non-null `T` and throws `Exception`. Acquisition is shared by
HTTP ownership, SSE ownership, and input-stream/reader descriptors. A domain
name keeps this API from introducing a general-purpose `CheckedSupplier` family.
Zero arguments support checked method references; providers that accept a token
receive it explicitly from a factory lambda.

Keep the HTTP-only callbacks nested in `ResponseStream`:

- `ResourceAborter<T extends AutoCloseable>.abort(T resource) throws Exception`
- `ResourceConsumer<T extends AutoCloseable>.accept(T resource) throws Exception`

Acquisition uses `StreamResourceFactory<? extends T>`; aborters and consumers
use `? super T`. Fixtures verify both subtype inference and typed callback reuse.
There are no consumer/function overload pairs that make expression lambdas
ambiguous.

`StreamingResponseWriter.writeTo(ResponseStream responseStream) throws Exception`
replaces the old two-argument callback. Output, request, cancelation token,
deadline, and idle-timeout access all live on that stream. Remove the obsolete
public `StreamingResponseContext` in milestone 3. Output and ownership operations
are confined to the producer thread; metadata and token queries remain
independently readable. A reusable descriptor can invoke the same callback
object concurrently for different responses, so captured state needs its own
concurrency discipline; the callback has no blanket thread-safety annotation.

`MarshaledResponse.Builder.stream(StreamingResponseWriter streamingResponseWriter)`
and the matching copier method require a non-null writer and follow the existing
streaming-body setter rules. They do not silently discard a conflicting buffered
body. Keep `withoutBody`, `withoutStreamingResponseBody`, builder `build()`, and
copier **`finish()`**. `StreamingResponseBody.fromWriter` and its `getWriter()`
remain; the direct builder call removes routine descriptor wrapping.

Input-stream and reader descriptor factories replace `Supplier` with
`StreamResourceFactory`; no competing old overload remains. Rename their getters
to **`getInputStreamFactory()`** and **`getReaderFactory()`**, with matching
parameter/field names. Factory invocation remains lazy, and the adapter requires
a provider that supports close racing read. Preserve existing factory/builder
return types, buffer settings, charset/error settings, and publisher signatures.
In particular, input/reader builder `build()` still returns `StreamingResponseBody`.

Native writes retain `IOException` and `InterruptedException`; cancelation's
`StreamingResponseCanceledException` remains an `IOException` subtype rather than
a redundant throws entry. The slice overload uses non-null `Integer offset` and
`Integer length`, consistent with the public numeric-parameter convention.
`writeUtf8(String string)`, `asOutputStream()`, and Boolean `isOpen()` complete the
output surface. Closing an output view flushes and closes that view without
closing or sealing the response. Managed encoder finalization still precedes
successful output sealing. Ordering, buffering, partial writes, and interruption
remain runtime qualification work.

## Registrations and SSE

Use shared top-level **`CallbackRegistration extends AutoCloseable`** with
unchecked `void close()`. `CancelationToken.onCancel(Runnable callback)` and
SSE termination observation return it. Closing is idempotent and does not wait:
it suppresses invocation only if it wins before callback claim. Keep the existing
default `CancelationToken.throwIfCanceled()` implementation. Ordinary token
callbacks remain `Runnable`, not checked resource aborters. Preserve the chosen
spelling **Cancelation** throughout and preserve MCP's separate callback gates.

Use top-level **`SseClientInitializer`**, with
`initialize(SseUnicaster sseUnicaster) throws Exception`. Replace the old
`Consumer<SseUnicaster>` setter/getter type directly. The builder's existing
`clientInitializer(null)` clearing behavior remains unambiguous; the getter
returns `Optional<SseClientInitializer>`. A shared initializer can serve multiple
clients concurrently. Initialization is bounded setup/catch-up, and returning
does not close connection-owned resources.

Add these capabilities to the retained, thread-safe `SseUnicaster`:

- `open(StreamResourceFactory<? extends T> streamResourceFactory)`
- `getRequest()` and Boolean `isOpen()`
- `onTermination(Consumer<StreamTermination> streamTerminationConsumer)` returning
  `CallbackRegistration`

Keep existing event/comment unicast and resource-path methods. SSE resources
belong to the connection, including pending initialization and establishment
failure. Cleanup runs on managed execution and may overlap provider callbacks;
it has no initializer-thread finalization affinity. SSE does not acquire HTTP's
`own`, lexical `using`, separate-abort overload, or producer-thread scope merely
for symmetry. Termination observation reports the connection outcome, not a
barrier proving every cleanup action has completed.

## Server settings

Use the **same three methods on both `HttpServer.Builder` and
`SseServer.Builder`**:

| Builder method | Type | Meaning |
|---|---|---|
| `streamingLifecycleCapacity` | `@Nullable Integer` | Maximum admitted streaming lifetimes, including still-accounted residual work. |
| `streamingCallbackConcurrency` | `@Nullable Integer` | Maximum managed callback workers; stuck callbacks do not trigger replacement workers. |
| `streamingCleanupTimeout` | `@Nullable Duration` | Grace for supervisory cleanup waiting, not a deadline that forcibly stops application code. |

These names match the surrounding `streaming...` builder family and use the
established `...Timeout` duration convention. Parameter names match the methods.
Each returns its non-null builder. `null` restores that server's default; resolve
defaults and validate the final combination at `build()`, so setter order does
not create an invalid intermediate configuration. No new public configuration
getters are required.

Capacity is positive and at most `Integer.MAX_VALUE / 2`. Callback concurrency
is positive and no greater than resolved capacity. Cleanup timeout is strictly
positive and representable in nanoseconds; zero does not disable supervision.
These bounds carry forward milestone 1's two-terminal-job accounting. Response
and shutdown deadlines remain separate budgets. Setting capacity below the
default callback concurrency requires also selecting a compatible concurrency;
there is no silent clamp.

HTTP defaults are **256 lifetimes, four callback workers, five seconds**, as
qualified in milestone 1. SSE names, placement, and validation semantics are
settled here, but its numerical defaults require connection-load qualification
in milestone 5. Explicit SSE values in compiler examples do not establish an
SSE default or performance claim.

## Verification and next step

**PASS on Corretto `javac 17.0.20.1`:** 12 API signature files, four positive
fixture files, and one comparison file compiled; all eight negative fixtures
failed exactly as intended. All 17 source hashes from the milestone 1 runtime
qualification still match, and the primary checkout remains clean.

Run the [verifier](../prototypes/streaming-api-milestone-2/verify.py) as described
in its [README](../prototypes/streaming-api-milestone-2/README.md).
The retained [verification receipt](streaming-api-milestone-2-verification.json)
records the Java version, baseline commit, compiler options, source hashes,
annotation dependency hashes, compiled-core class bundle, and exact expected
negative diagnostics. Compilation uses Java 17, an empty source path, and
disabled annotation processing. Unchanged public types come from compiled core;
the proposed signatures live in an isolated overlay placed first on the classpath.

The fixture coverage corresponds to the plan's advertised examples:

| Plan example/contract | Fixture |
|---|---|
| Common upstream and token forwarding | `StreamingHandlers.commonUpstream`, `forwardCancelationToken` |
| Preparable acquisition | `preparableAcquisition` |
| ZIP finalization | `zipArchive` |
| One page, page plus parser, explicit-abort lexical work | `onePageAtATime`, `pageAndParser`, `lexicalSeparateAbort` |
| SSE subscription | `subscription` |
| Checked descriptors and factory getter migration | `checkedIoAdapters`, `checkedFactoryGetters` |
| Concrete `var` inference and callback variance | `factoryVarianceAndInference`, `Alternatives` |
| Builder, copier, output metadata, registrations | Remaining `StreamingHandlers` methods |
| Default token method, typed/checked SSE initialization, null clearing | `SignatureContracts` |
| Both server builders, explicit values, null resets, one-slot limits | `ServerSettings` |

Eight negative fixtures reject the old writer arity, an old input-stream
`Supplier`, an old SSE initializer `Consumer`, a
checked token callback, unsupported SSE ownership operations, and a non-closeable
resource. Each must produce exactly one diagnostic on its marked line, matching
the expected diagnostic code and required message fragments; other compiler
failures do not count as a passing rejection.

One-argument `open`, two-argument `using`, their separate-abort overloads, `own`,
and SSE `open` are positive fixture cases. The previous two negatives rejecting
the short close-on-cancel overloads were removed because those calls are valid.

Compilation proves usable signatures, not concurrency safety, cleanup behavior,
builder validation, annotation-processor compatibility, or production integration.
No production source, lifecycle qualification result, or historical release
snapshot is changed by milestone 2. Existing milestone 1 release blockers remain.

**Next: milestone 3.** Implement this HTTP surface and simulator behavior on the
qualified coordinator; migrate the shared registration return type and MCP
implementations together; replace compile-only caller checks with checks against
the actual API. Validate ownership races, lazy suppression, encoder trailers,
output ordering/partial writes, and bounded accounting before moving to SSE.
The independent passive-disconnect work remains milestone 4 and does not gate
this integration.
