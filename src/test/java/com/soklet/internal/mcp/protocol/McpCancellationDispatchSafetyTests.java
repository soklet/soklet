/* Copyright 2022-2026 Revetware LLC. Licensed under the Apache License, Version 2.0. */
package com.soklet.internal.mcp.protocol;

import com.soklet.StreamTerminationReason;
import com.soklet.LifecycleObserver;
import com.soklet.LogEvent;
import com.soklet.LogEventType;
import com.soklet.McpEndpoint;
import com.soklet.McpEndpointRegistry;
import com.soklet.McpImplementation;
import com.soklet.McpProtocolVersion;
import com.soklet.McpServer;
import com.soklet.internal.microhttp.MicrohttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class McpCancellationDispatchSafetyTests {
    @Test
    void cancellationDispatchDiagnosticOmitsUntrustedFailureDetails() throws Exception {
        McpEndpoint endpoint = McpEndpoint.withPath("/mcp", McpImplementation.withNameAndVersion("diagnostic", "1").build(),
                Set.of(McpProtocolVersion.V2026_07_28)).build();
        McpServer server = McpServer.withPort(0).endpointRegistry(McpEndpointRegistry.fromEndpoints(List.of(endpoint))).build();
        AtomicReference<LogEvent> event = new AtomicReference<>();
        Field observerField = server.getClass().getDeclaredField("lifecycleObserver");
        observerField.setAccessible(true);
        observerField.set(server, new LifecycleObserver() {
            @Override public void didReceiveLogEvent(LogEvent received) { event.set(received); }
        });
        Method observerMethod = server.getClass().getDeclaredMethod("applicationExecutionObserver");
        observerMethod.setAccessible(true);
        McpApplicationExecutionObserver observer = (McpApplicationExecutionObserver) observerMethod.invoke(server);
        observer.didFailCancellationDispatch(new RejectedExecutionException("secret cancellation payload"));
        assertEquals(LogEventType.SERVER_INTERNAL_ERROR, event.get().getLogEventType());
        assertTrue(event.get().getMessage().contains(RejectedExecutionException.class.getName()));
        assertFalse(event.get().getMessage().contains("secret"));
        assertTrue(event.get().getThrowable().isEmpty());
    }

    @Test
    void cancellationElectionRetainsAHandlerThatAlreadyObservedItsReason() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), returned = new CountDownLatch(1);
            CountDownLatch callbackEntered = new CountDownLatch(1), releaseCallback = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
            Thread canceler = new Thread(() -> {
                try { fixture.cancel(); } catch (Throwable failure) { cancellationFailure.set(failure); }
            }, "mcp-cancellation-election-test");
            try {
                fixture.dispatch(call -> {
                    worker.set(Thread.currentThread());
                    call.cancelationToken().onCancel(() -> { callbackEntered.countDown(); hold(releaseCallback); });
                    entered.countDown();
                    while (!call.cancelationToken().isCanceled()) Thread.onSpinWait();
                    returned.countDown();
                    return completed();
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                Object dispatcherLock = field(field(fixture.execution, "dispatcher"), "lock");
                synchronized (dispatcherLock) {
                    canceler.start();
                    assertTrue(returned.await(3, TimeUnit.SECONDS));
                    // The election published the token but cannot yet reserve its
                    // sidecar. The handler must join terminalLock, not retire.
                    await(() -> worker.get().getState() == Thread.State.BLOCKED
                            && hasFrame(worker.get(), "awaitCancellationCallbacks"));
                    assertFalse(hasFrame(worker.get(), "onHandlerExited"));
                }
                canceler.join(3_000L);
                assertFalse(canceler.isAlive());
                assertNull(cancellationFailure.get());
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                assertEquals(1, fixture.execution.snapshot().activeHandlerSlots());
                assertEquals(1, fixture.execution.snapshot().retainedExchanges());
                assertEquals(0, fixture.physicalExits.get());
                releaseCallback.countDown();
                await(() -> {
                    McpApplicationExecutionSnapshot snapshot = fixture.execution.snapshot();
                    return fixture.physicalExits.get() == 1 && snapshot.activeHandlerSlots() == 0
                            && snapshot.retainedExchanges() == 0;
                });
                assertEquals(0, fixture.execution.snapshot().activeHandlerSlots());
                assertEquals(0, fixture.execution.snapshot().retainedExchanges());
            } finally {
                releaseCallback.countDown();
                canceler.join(3_000L);
                assertFalse(canceler.isAlive());
            }
        }
    }

    @Test
    void rejectedExchangeSidecarStillCompletesCancellationAndPhysicalDrain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            try {
                fixture.dispatch(call -> {
                    call.cancelationToken().onCancel(callbacks::incrementAndGet);
                    entered.countDown(); hold(release); return completed();
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.rejectSidecars();
                fixture.cancel();
                assertEquals(1, fixture.diagnostics.get());
                assertFalse(fixture.diagnosticUnderLock.get());
                assertEquals(0, callbacks.get(), "An unaccepted callback must not run inline.");
                assertEquals(1, fixture.execution.snapshot().activeHandlerSlots());
                release.countDown();
                await(() -> {
                    McpApplicationExecutionSnapshot snapshot = fixture.execution.snapshot();
                    return fixture.physicalExits.get() == 1 && snapshot.activeHandlerSlots() == 0
                            && snapshot.retainedExchanges() == 0 && snapshot.retainedTransportLeases() == 0;
                });
                assertEquals(0, fixture.execution.snapshot().retainedExchanges());
                assertEquals(0, fixture.execution.snapshot().retainedTransportLeases());
                assertEquals(1, fixture.execution.snapshot().abandonedResponses());
            } finally { release.countDown(); }
        }
    }

    @Test
    void rejectedPolicySidecarReleasesItsPhysicalCallbackGate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger(), physicalExits = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            McpApplicationExecution.BoundedPolicyCancellation cancellation = fixture.execution.newBoundedPolicyCancellation();
            cancellation.onCancel(callbacks::incrementAndGet);
            Thread caller = new Thread(() -> {
                try {
                    fixture.execution.invokeBoundedPolicy(() -> {
                        entered.countDown(); hold(release); return "completed";
                    }, System.nanoTime() + TimeUnit.SECONDS.toNanos(20), cancellation, physicalExits::incrementAndGet);
                } catch (Throwable observed) { failure.set(observed); }
            }, "mcp-policy-rejection-test");
            try {
                caller.start();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                fixture.rejectSidecars();
                cancellation.cancel(StreamTerminationReason.CLIENT_CANCELED);
                assertEquals(1, fixture.diagnostics.get());
                assertFalse(fixture.diagnosticUnderLock.get());
                assertEquals(0, callbacks.get());
                assertEquals(0, physicalExits.get());
                release.countDown(); caller.join(3_000L);
                assertFalse(caller.isAlive());
                assertNull(failure.get(), "An active policy may finish after observing cancellation.");
                assertEquals(StreamTerminationReason.CLIENT_CANCELED, cancellation.reason().orElseThrow());
                await(() -> physicalExits.get() == 1);
                assertEquals(0, fixture.execution.snapshot().activeHandlerSlots());
            } finally {
                release.countDown(); cancellation.cancel(StreamTerminationReason.SERVER_STOPPING);
                caller.join(3_000L); assertFalse(caller.isAlive());
            }
        }
    }

    private static McpApplicationDispatchOutcome completed() {
        return McpApplicationDispatchOutcome.completed(McpWireResult.complete(McpJsonObject.empty()));
    }

    private static boolean hasFrame(Thread thread, String method) {
        for (StackTraceElement frame : thread.getStackTrace()) if (frame.getMethodName().equals(method)) return true;
        return false;
    }

    private static void hold(CountDownLatch release) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing release.");
                    return;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() - deadline < 0L) Thread.sleep(1);
        assertTrue(condition.getAsBoolean(), "Cancellation state did not settle.");
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger diagnostics = new AtomicInteger(), physicalExits = new AtomicInteger();
        private final AtomicBoolean diagnosticUnderLock = new AtomicBoolean();
        private final McpApplicationExecution execution;
        private final MicrohttpRequest transport = new MicrohttpRequest("POST", "/mcp", "HTTP/1.1", List.of(), new byte[0], false,
                new InetSocketAddress("127.0.0.1", 12345));
        private Object terminalLock;
        private Fixture() {
            McpApplicationExecutionObserver disabled = McpApplicationExecutionObserver.disabledInstance();
            McpApplicationExecutionObserver observer = (McpApplicationExecutionObserver) Proxy.newProxyInstance(
                    disabled.getClass().getClassLoader(), new Class<?>[]{McpApplicationExecutionObserver.class}, (proxy, method, args) -> {
                        if (method.getName().equals("didFailCancellationDispatch")) {
                            diagnostics.incrementAndGet();
                            diagnosticUnderLock.set(Thread.holdsLock(field(execution(), "executionBoundaryLock"))
                                    || terminalLock != null && Thread.holdsLock(terminalLock));
                        }
                        return method.invoke(disabled, args);
                    });
            execution = new McpApplicationExecution(new McpApplicationExecutionConfiguration(1, 1,
                    Duration.ofSeconds(20), Duration.ofDays(1)), McpApplicationClock.SYSTEM,
                    McpApplicationHandlerExecutorFactory.production(), null, observer);
            execution.start();
        }
        private McpApplicationExecution execution() { return execution; }
        private void rejectSidecars() throws Exception { ((ExecutorService) field(execution, "cancellationCallbackExecutor")).shutdown(); }
        private void dispatch(McpApplicationRequestHandler handler) throws Exception {
            McpProtocolProfile profile = Mcp2025ProtocolProfile.NOVEMBER_25;
            McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
                    McpImplementationMetadata.withNameAndVersion("cancellation-safety", "1")).build();
            McpJsonLimits limits = McpJsonLimits.productionDefaults();
            McpJsonRpcEnvelope.Request envelope = (McpJsonRpcEnvelope.Request) new McpJsonRpcEnvelopeCodec(new McpJsonCodec(limits))
                    .decode("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"run\",\"arguments\":{}}}".getBytes(StandardCharsets.UTF_8));
            execution.dispatch(transport, profile.mapRequest(new McpRequestWireMapper(limits), envelope), profile,
                    McpEffectiveAdmissionIdentity.resolve(endpoint, "/mcp", McpAdmissionIdentity.anonymousInstance()), handler,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(20), new McpApplicationResponseWriter() {
                        @Override public boolean write(McpApplicationResponse response) { return true; }
                        @Override public void didFinishPhysicalWork() { physicalExits.incrementAndGet(); }
                    }, () -> {});
            terminalLock = field(((Map<?, ?>) field(execution, "requestsByIdentity")).get(transport), "terminalLock");
        }
        private void cancel() { execution.tryReserveCancellation(transport, StreamTerminationReason.CLIENT_CANCELED).complete().run(); }
        @Override public void close() throws Exception {
            execution.stop(); assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
        }
    }
}
