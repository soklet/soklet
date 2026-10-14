/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SseLateResponseOutcomeTests {
    @Test
    void lateHandlerDoesNotWaitForBlockedTimeoutMarshalerOrReportItsResponseAsWritten() throws Exception {
        exerciseTimeout(false);
    }

    @Test
    void lateHandlerAfterCustomTimeoutResponseIsWrittenStillReportsWriteFailureOnly() throws Exception {
        exerciseTimeout(true);
    }

    @Test
    void lateSseTimeoutResponseClosesOwnedFileChannel() throws Exception { assertLateBodyOwnership(true); }

    @Test
    void lateSseTimeoutResponsePreservesBorrowedFileChannel() throws Exception { assertLateBodyOwnership(false); }

    private void assertLateBodyOwnership(boolean owned) throws Exception {
        var path = java.nio.file.Files.createTempFile("soklet-late-sse-", ".txt");
        try {
            java.nio.file.Files.writeString(path, "discarded");
            try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
                exerciseTimeout(false, MarshaledResponse.withStatusCode(403).body(channel, 0L, 9L, owned).build());
                assertEquals(!owned, channel.isOpen(), "Only an owned suppressed channel must close");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
    }

    private void exerciseTimeout(boolean finishTimeoutFirst) throws Exception {
        exerciseTimeout(finishTimeoutFirst, null);
    }

    private void exerciseTimeout(boolean finishTimeoutFirst, MarshaledResponse lateResponse) throws Exception {
        try (Fixture fixture = new Fixture(lateResponse); Socket socket = new Socket()) {
            fixture.owner.start();
            socket.connect(new InetSocketAddress("127.0.0.1", fixture.port), 3_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write("GET /late HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            assertTrue(fixture.resource.entered.await(3, TimeUnit.SECONDS));
            fixture.timeoutHandshake();
            assertTrue(fixture.timeoutEntered.await(3, TimeUnit.SECONDS));
            String wire = null;
            if (finishTimeoutFirst) {
                fixture.releaseTimeout.countDown();
                wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            }
            fixture.resource.release.countDown();
            assertTrue(fixture.finished.await(3, TimeUnit.SECONDS),
                    "A suppressed handler must not wait for the timeout marshaler");
            if (!finishTimeoutFirst) {
                fixture.releaseTimeout.countDown();
                wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            }
            assertNotNull(wire);
            assertTrue(wire.startsWith("HTTP/1.1 429 "), wire);
            assertTrue(wire.endsWith("timeout winner"), wire);
            assertEquals(1, wire.split("HTTP/1.1 ", -1).length - 1, "Only the timeout response may reach the wire");
            assertEquals(0, fixture.written.get());
            assertEquals(0, fixture.metricWritten.get());
            assertEquals(1, fixture.writeFailures.size());
            Throwable failure = assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    fixture.writeFailures.get(0));
            assertEquals(List.of(failure), fixture.metricWriteFailures);
            assertEquals(List.of(failure), fixture.finishedFailures);
            assertEquals(List.of(lateResponse == null ? 200 : lateResponse.getStatusCode()), fixture.finishedStatuses,
                    "Finish describes the failed attempt, not an invented timeout response");
            assertEquals(List.of(SseConnection.HandshakeFailureReason.HANDSHAKE_TIMEOUT), fixture.handshakeFailures);
            assertEquals(0, fixture.resource.initializers.get());
            assertEquals(0, fixture.established.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final int port = TestSupport.findFreePort();
        final BlockingResource resource = new BlockingResource();
        final CountDownLatch timeoutEntered = new CountDownLatch(1);
        final CountDownLatch releaseTimeout = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger written = new AtomicInteger();
        final AtomicInteger metricWritten = new AtomicInteger();
        final AtomicInteger established = new AtomicInteger();
        final List<Throwable> writeFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> metricWriteFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> finishedFailures = new CopyOnWriteArrayList<>();
        final List<Integer> finishedStatuses = new CopyOnWriteArrayList<>();
        final List<SseConnection.HandshakeFailureReason> handshakeFailures = new CopyOnWriteArrayList<>();
        final DefaultSseServer server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
                .requestHandlerTimeout(Duration.ofSeconds(30)).verifyConnectionOnceEstablished(false).build();
        final Soklet owner;

        Fixture(MarshaledResponse lateResponse) throws Exception {
            owner = Soklet.fromConfig(SokletConfig.withSseServer(server)
                    .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BlockingResource.class)))
                    .instanceProvider(new InstanceProvider() {
                        @Override public <T> T provide(Class<T> type) {
                            return type == BlockingResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
                        }
                    })
                    .requestInterceptor(new RequestInterceptor() {
                        @Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
                                java.util.function.Function<Request, MarshaledResponse> generator,
                                java.util.function.Consumer<MarshaledResponse> writer) {
                            MarshaledResponse generated = generator.apply(request);
                            writer.accept(lateResponse == null ? generated : lateResponse);
                        }
                    })
                    .responseMarshaler(ResponseMarshaler.builder().serviceUnavailableHandler((request, method) -> {
                        timeoutEntered.countDown();
                        awaitIgnoringInterrupts(releaseTimeout);
                        return MarshaledResponse.withStatusCode(429).body("timeout winner".getBytes(StandardCharsets.US_ASCII)).build();
                    }).build())
                    .lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
                            .startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
                            .forcedShutdownTimeout(Duration.ofSeconds(1)).build())
                    .lifecycleObserver(new LifecycleObserver() {
                        @Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method,
                                MarshaledResponse response, Duration duration) { written.incrementAndGet(); }
                        @Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method,
                                MarshaledResponse response, Duration duration, Throwable failure) { writeFailures.add(failure); }
                        @Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method,
                                MarshaledResponse response, Duration duration, List<Throwable> failures) {
                            finishedStatuses.add(response.getStatusCode());
                            finishedFailures.addAll(failures);
                            finished.countDown();
                        }
                        @Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
                                SseConnection.HandshakeFailureReason reason, Throwable failure) { handshakeFailures.add(reason); }
                        @Override public void didEstablishSseConnection(SseConnection connection) { established.incrementAndGet(); }
                    })
                    .metricsCollector(new MetricsCollector() {
                        @Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method,
                                MarshaledResponse response, Duration duration) { metricWritten.incrementAndGet(); }
                        @Override public void didFailToWriteResponse(ServerType type, Request request, ResourceMethod method,
                                MarshaledResponse response, Duration duration, Throwable failure) { metricWriteFailures.add(failure); }
                    }).build());
        }

        void timeoutHandshake() throws Exception {
            Field activeField = DefaultSseServer.class.getDeclaredField("activeHandshakes");
            activeField.setAccessible(true);
            Map<?, ?> active = (Map<?, ?>) activeField.get(server);
            assertEquals(1, active.size());
            var admission = active.entrySet().iterator().next();
            Method contextMethod = admission.getValue().getClass().getDeclaredMethod("context");
            contextMethod.setAccessible(true);
            Object context = contextMethod.invoke(admission.getValue());
            Method timeoutMethod = DefaultSseServer.class.getDeclaredMethod("handleHandshakeTimeout", SocketChannel.class,
                    context.getClass());
            timeoutMethod.setAccessible(true);
            timeoutMethod.invoke(server, admission.getKey(), context);
        }

        @Override public void close() {
            resource.release.countDown();
            releaseTimeout.countDown();
            owner.close();
            assertTrue(owner.getShutdownResult().orElseThrow().isComplete());
        }
    }

    public static final class BlockingResource {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger initializers = new AtomicInteger();

        @SseEventSource("/late")
        public SseHandshakeResult events() {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return SseHandshakeResult.Accepted.builder().clientInitializer(unicaster -> initializers.incrementAndGet()).build();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean interrupted = false;
        try {
            while (latch.getCount() > 0) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "Fixture callback was not released");
                try {
                    assertTrue(latch.await(remaining, TimeUnit.NANOSECONDS), "Fixture callback was not released");
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
