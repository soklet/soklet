/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import com.soklet.StreamingResponseCanceledException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingDisconnectMonitorTests {
    private static final String STREAM_REQUEST = "GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String NEXT_REQUEST =
            "GET /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

    @Test
    void resetWhileCommittedStreamIsIdleClosesItAsClientDisconnected() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            socket.setSoLinger(true, 0);
            socket.close();

            Assertions.assertTrue(fixture.source.closed.await(3, TimeUnit.SECONDS),
                    "The idle source was not closed after the socket reset");
            Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                    fixture.source.closeReason.get());
            Assertions.assertEquals(0, fixture.nextCalls.get());
        }
    }

    @Test
    void resetReleasesProducerBlockedOnBoundedOutputQueue() throws Exception {
        try (QueuePressureFixture fixture = new QueuePressureFixture();
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertTrue(fixture.writerEntered.await(3, TimeUnit.SECONDS));

            // The outer response headers have reached the real socket. Pause only its body source,
            // so the real bounded stream queue cannot drain while selector reads remain active.
            PausedWritableSource pausedSource = fixture.source.get();
            Assertions.assertNotNull(pausedSource);
            pausedSource.pause();
            fixture.allowWrites.countDown();
            Assertions.assertTrue(fixture.attemptingSecondWrite.await(3, TimeUnit.SECONDS));
            awaitQueueCapacityWait(fixture.writerThread.get());
            Assertions.assertEquals(1, fixture.acceptedChunks.get());
            Assertions.assertEquals(1, fixture.writerExited.getCount());

            socket.setSoLinger(true, 0);
            socket.close();

            Assertions.assertTrue(fixture.sourceClosed.await(3, TimeUnit.SECONDS));
            Assertions.assertTrue(fixture.writerExited.await(3, TimeUnit.SECONDS),
                    "The socket reset did not release the producer's queue-capacity wait");
            Assertions.assertTrue(fixture.terminated.await(3, TimeUnit.SECONDS));
            Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                    fixture.terminationReason.get());
            Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                    pausedSource.closeReason.get());
            Assertions.assertInstanceOf(StreamingResponseCanceledException.class,
                    fixture.writerFailure.get());
            Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                    ((StreamingResponseCanceledException) fixture.writerFailure.get())
                            .getCancelationReason());
            Assertions.assertEquals(1, fixture.acceptedChunks.get(),
                    "The blocked second chunk must not be accepted after reset");
        }
    }

    @Test
    void emptyFlushFloodQueuesBoundedReadyWorkAndDoesNotPreventSocketProgress() throws Exception {
        try (EmptyFlushFixture fixture = new EmptyFlushFixture();
             Socket streamSocket = fixture.connect()) {
            send(streamSocket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(streamSocket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertTrue(fixture.writerEntered.await(3, TimeUnit.SECONDS));

            try (BlockedLoop blockedLoop = new BlockedLoop(fixture.eventLoop)) {
                fixture.allowFlushes.countDown();
                Assertions.assertTrue(fixture.flushesFinished.await(3, TimeUnit.SECONDS),
                        "Empty flushes must not wait for output queue capacity");
                Assertions.assertNull(fixture.writerFailure.get());
                Assertions.assertEquals(20_000, fixture.flushes.get());
                Assertions.assertTrue(blockedLoop.pendingTasks() <= 1,
                        "Repeated readiness notifications must coalesce while the loop is blocked");

                try (Socket nextSocket = fixture.connect()) {
                    send(nextSocket, NEXT_REQUEST);
                    streamSocket.setSoLinger(true, 0);
                    streamSocket.close();
                    blockedLoop.release();

                    Assertions.assertTrue(fixture.terminated.await(3, TimeUnit.SECONDS),
                            "Readiness work must not hide a real socket reset");
                    Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                            fixture.terminationReason.get());
                    Assertions.assertTrue(readHeaders(nextSocket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
                    Assertions.assertArrayEquals(ascii("next"), nextSocket.getInputStream().readNBytes(4));
                    Assertions.assertTrue(fixture.writerExited.await(3, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void continuouslyRearmedReadyWorkYieldsToSocketResetAndAnotherConnection() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket streamSocket = fixture.connect()) {
            send(streamSocket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(streamSocket.getInputStream()).startsWith("HTTP/1.1 200 OK"));

            try (BlockedLoop blockedLoop = new BlockedLoop(fixture.eventLoop);
                 Socket nextSocket = fixture.connect()) {
                // Each ready task queues its successor from writeTo. The queue cannot become
                // empty on its own, so draining it without a turn limit starves selector reads.
                fixture.source.startReadinessFlood();
                send(nextSocket, NEXT_REQUEST);
                streamSocket.setSoLinger(true, 0);
                streamSocket.close();
                blockedLoop.release();

                Assertions.assertTrue(fixture.source.closed.await(3, TimeUnit.SECONDS),
                        "A continuously nonempty ready queue must still allow reset detection");
                Assertions.assertTrue(fixture.source.readinessTurns.get() > 0,
                        "The readiness flood must actually run before reset detection");
                Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED,
                        fixture.source.closeReason.get());
                Assertions.assertTrue(readHeaders(nextSocket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
                Assertions.assertArrayEquals(ascii("next"), nextSocket.getInputStream().readNBytes(4));
                Assertions.assertEquals(1, fixture.nextCalls.get());
            }
        }
    }

    private static final class BlockedLoop implements AutoCloseable {
        private final CountDownLatch released = new CountDownLatch(1);
        private final Queue<Runnable> taskQueue;

        @SuppressWarnings("unchecked")
        BlockedLoop(EventLoop eventLoop) throws Exception {
            Field loopsField = EventLoop.class.getDeclaredField("connectionEventLoops");
            loopsField.setAccessible(true);
            List<ConnectionEventLoop> loops = (List<ConnectionEventLoop>) loopsField.get(eventLoop);
            Assertions.assertEquals(1, loops.size());
            ConnectionEventLoop loop = loops.get(0);
            Field queueField = ConnectionEventLoop.class.getDeclaredField("taskQueue");
            queueField.setAccessible(true);
            taskQueue = (Queue<Runnable>) queueField.get(loop);
            CountDownLatch entered = new CountDownLatch(1);
            taskQueue.add(() -> {
                entered.countDown();
                try {
                    Assertions.assertTrue(released.await(10, TimeUnit.SECONDS),
                            "Test did not release the blocked connection loop");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            loop.wakeup();
            try {
                Assertions.assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "Connection loop did not reach the test barrier");
            } catch (Throwable throwable) {
                released.countDown();
                throw throwable;
            }
        }

        int pendingTasks() {
            return taskQueue.size();
        }

        void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
        }
    }

    private static final class EmptyFlushFixture implements AutoCloseable {
        final CountDownLatch writerEntered = new CountDownLatch(1);
        final CountDownLatch allowFlushes = new CountDownLatch(1);
        final CountDownLatch flushesFinished = new CountDownLatch(1);
        final CountDownLatch keepProducerOpen = new CountDownLatch(1);
        final CountDownLatch writerExited = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        final AtomicInteger flushes = new AtomicInteger();
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        final AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
        final ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
        final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        final EventLoop eventLoop;

        EmptyFlushFixture() throws IOException {
            StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
                writerEntered.countDown();
                try {
                    allowFlushes.await();
                    for (int index = 0; index < 20_000; index++) {
                        responseStream.flush();
                        flushes.incrementAndGet();
                    }
                    flushesFinished.countDown();
                    keepProducerOpen.await();
                } catch (Exception exception) {
                    writerFailure.set(exception);
                    throw exception;
                } finally {
                    writerExited.countDown();
                }
            });
            Handler handler = new Handler() {
                @Override
                public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
                    if ("/next".equals(request.uri())) {
                        callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("next")));
                        return;
                    }
                    callback.accept(StreamingMicrohttpResponses.withStreamingBody(
                            200, "OK", List.of(), Request.withPath(HttpMethod.GET, "/stream").build(),
                            body, producerExecutor, timeoutExecutor, 1_024, 1_024,
                            null, null,
                            (establishedAt, duration, reason, cause) -> {
                                terminationReason.set(reason);
                                terminated.countDown();
                            }, throwable -> { }));
                }

                @Override
                public StreamingResponseInputPolicy streamingResponseInputPolicy(MicrohttpRequest request) {
                    return StreamingResponseInputPolicy.RETAIN;
                }
            };
            Options options = OptionsBuilder.newBuilder().withPort(0)
                    .withResolution(Duration.ofMillis(10)).withMaxRequestSize(512)
                    .withMaxRequestBodySize(512).withReadBufferSize(64)
                    .withConcurrency(1).build();
            eventLoop = new EventLoop(options, handler);
            eventLoop.start();
        }

        Socket connect() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 3_000);
            socket.setSoTimeout(3_000);
            return socket;
        }

        @Override
        public void close() throws Exception {
            allowFlushes.countDown();
            keepProducerOpen.countDown();
            eventLoop.stop();
            eventLoop.join();
            producerExecutor.shutdownNow();
            timeoutExecutor.shutdownNow();
            Assertions.assertTrue(producerExecutor.awaitTermination(3, TimeUnit.SECONDS));
            Assertions.assertTrue(timeoutExecutor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void awaitQueueCapacityWait(Thread writerThread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (writerThread != null && writerThread.getState() == Thread.State.WAITING) {
                for (StackTraceElement frame : writerThread.getStackTrace()) {
                    if (frame.getMethodName().equals("enqueue")
                            && frame.getClassName().contains("StreamingMicrohttpResponses"))
                        return;
                }
            }
            Thread.yield();
        }
        Assertions.fail("Producer never waited for the bounded output queue");
    }

    @Test
    void inputHalfCloseKeepsCommittedStreamWritableAndDoesNotSpinReadInterest() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            socket.shutdownOutput();
            await(() -> fixture.logger.halfCloses.get() == 1, "Input FIN was not observed");
            Assertions.assertEquals(1, fixture.source.closed.getCount(),
                    "Input FIN must not cancel the committed response");
            Thread.sleep(120);
            Assertions.assertEquals(1, fixture.logger.halfCloses.get(),
                    "Read interest must be disabled after the first input EOF");

            fixture.source.release();
            Assertions.assertEquals("x", readChunkedBody(socket.getInputStream()));
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(0, fixture.nextCalls.get());
        }
    }

    @Test
    void pipelinedRequestIsRetainedUntilCommittedStreamCompletes() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            send(socket, NEXT_REQUEST);
            await(() -> fixture.logger.retainedReads.get() > 0,
                    "Selector did not read the pipelined request during the idle stream");
            Assertions.assertEquals(0, fixture.nextCalls.get(),
                    "The next request must wait for the first response to finish");

            fixture.source.release();
            Assertions.assertEquals("x", readChunkedBody(socket.getInputStream()));
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertArrayEquals(ascii("next"), socket.getInputStream().readNBytes(4));
            Assertions.assertEquals(1, fixture.nextCalls.get());
        }
    }

    @Test
    void pipelinedRequestSurvivesInputHalfCloseDuringCommittedStream() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            send(socket, NEXT_REQUEST);
            socket.shutdownOutput();

            await(() -> fixture.logger.halfCloses.get() == 1, "Input FIN was not observed");
            Assertions.assertEquals(1, fixture.source.closed.getCount(),
                    "Input FIN must not cancel the committed response");
            Assertions.assertEquals(0, fixture.nextCalls.get());

            fixture.source.release();
            Assertions.assertEquals("x", readChunkedBody(socket.getInputStream()));
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            Assertions.assertArrayEquals(ascii("next"), socket.getInputStream().readNBytes(4));
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(1, fixture.nextCalls.get());
            Assertions.assertEquals(1, fixture.logger.halfCloses.get());
        }
    }

    @Test
    void closeAfterResponseStillRetainsBoundedInputWithoutDispatchingIt() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 512);
             Socket socket = fixture.connect()) {
            send(socket, "GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            send(socket, NEXT_REQUEST);
            await(() -> fixture.logger.retainedReads.get() > 0,
                    "The monitor did not retain bounded input after Connection: close");
            Assertions.assertEquals(0, fixture.logger.discardedReads.get(),
                    "RETAIN must not switch to DISCARD when the response will close the socket");

            fixture.source.release();
            Assertions.assertEquals("x", readChunkedBody(socket.getInputStream()));
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(0, fixture.nextCalls.get());
        }
    }

    @Test
    void pipelinedInputAboveRequestLimitClosesStreamWithoutDispatch() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN, 128);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            socket.getOutputStream().write(new byte[129]);
            socket.getOutputStream().flush();

            Assertions.assertTrue(fixture.source.closed.await(3, TimeUnit.SECONDS));
            Assertions.assertEquals(StreamTerminationReason.BACKPRESSURE,
                    fixture.source.closeReason.get());
            Assertions.assertEquals(0, fixture.nextCalls.get());
            Assertions.assertEquals(1, fixture.logger.readLimitFailures.get());
        }
    }

    @Test
    void coalescedInputAboveRequestLimitClosesBeforeCommittingStream() throws Exception {
        // EventLoop binds at construction. Queue all bytes before starting its accept thread so
        // the first socket read necessarily sees the completed request and its oversized tail.
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.RETAIN,
                128, 256, false);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST + "X".repeat(129));
            fixture.start();

            Assertions.assertTrue(fixture.source.closed.await(3, TimeUnit.SECONDS));
            Assertions.assertEquals(StreamTerminationReason.BACKPRESSURE,
                    fixture.source.closeReason.get());
            Assertions.assertEquals(-1, socket.getInputStream().read(),
                    "An oversized pre-commit tail must not produce a 200 response");
            Assertions.assertEquals(0, fixture.nextCalls.get());
            Assertions.assertEquals(1, fixture.logger.readLimitFailures.get());
        }
    }

    @Test
    void legacyMonitoringHookStillDiscardsPipelinedBytes() throws Exception {
        try (Fixture fixture = new Fixture(Handler.StreamingResponseInputPolicy.DISCARD, 512);
             Socket socket = fixture.connect()) {
            send(socket, STREAM_REQUEST);
            Assertions.assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            send(socket, NEXT_REQUEST);
            await(() -> fixture.logger.discardedReads.get() > 0,
                    "Legacy monitor did not read the extra client bytes");
            fixture.source.release();
            Assertions.assertEquals("x", readChunkedBody(socket.getInputStream()));
            Assertions.assertEquals(-1, socket.getInputStream().read());
            Assertions.assertEquals(0, fixture.nextCalls.get(),
                    "Legacy MCP-style monitoring must not dispatch pipelined requests");
        }
    }

    private static final class QueuePressureFixture implements AutoCloseable {
        final CountDownLatch allowWrites = new CountDownLatch(1);
        final CountDownLatch writerEntered = new CountDownLatch(1);
        final CountDownLatch attemptingSecondWrite = new CountDownLatch(1);
        final CountDownLatch writerExited = new CountDownLatch(1);
        final CountDownLatch sourceClosed = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        final AtomicInteger acceptedChunks = new AtomicInteger();
        final AtomicReference<Thread> writerThread = new AtomicReference<>();
        final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        final AtomicReference<StreamTerminationReason> terminationReason = new AtomicReference<>();
        final AtomicReference<PausedWritableSource> source = new AtomicReference<>();
        final ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
        final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        final EventLoop eventLoop;

        QueuePressureFixture() throws IOException {
            StreamingResponseBody body = StreamingResponseBody.fromWriter(responseStream -> {
                writerThread.set(Thread.currentThread());
                writerEntered.countDown();
                try {
                    allowWrites.await();
                    byte[] chunk = new byte[1_024];
                    responseStream.write(chunk);
                    acceptedChunks.incrementAndGet();
                    attemptingSecondWrite.countDown();
                    responseStream.write(chunk);
                    acceptedChunks.incrementAndGet();
                } catch (Exception exception) {
                    writerFailure.set(exception);
                    throw exception;
                } finally {
                    writerExited.countDown();
                }
            });
            Handler handler = new Handler() {
                @Override
                public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
                    MicrohttpResponse actual = StreamingMicrohttpResponses.withStreamingBody(
                            200, "OK", List.of(), Request.withPath(HttpMethod.GET, "/stream").build(),
                            body, producerExecutor, timeoutExecutor, 1_024, 1_024,
                            null, null,
                            (establishedAt, duration, reason, cause) -> {
                                terminationReason.set(reason);
                                terminated.countDown();
                            }, throwable -> { });
                    callback.accept(MicrohttpResponse.withStreamingBody(200, "OK", List.of(),
                            () -> {
                                PausedWritableSource wrapper = new PausedWritableSource(
                                        actual.writableSource(new byte[0]), sourceClosed);
                                source.set(wrapper);
                                return wrapper;
                            }));
                }

                @Override
                public StreamingResponseInputPolicy streamingResponseInputPolicy(MicrohttpRequest request) {
                    return StreamingResponseInputPolicy.RETAIN;
                }
            };
            Options options = OptionsBuilder.newBuilder().withPort(0)
                    .withResolution(Duration.ofMillis(10)).withMaxRequestSize(512)
                    .withMaxRequestBodySize(512).withReadBufferSize(64)
                    .withConcurrency(1).build();
            eventLoop = new EventLoop(options, handler);
            eventLoop.start();
        }

        Socket connect() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 3_000);
            socket.setSoTimeout(3_000);
            return socket;
        }

        @Override
        public void close() throws Exception {
            allowWrites.countDown();
            eventLoop.stop();
            eventLoop.join();
            producerExecutor.shutdownNow();
            timeoutExecutor.shutdownNow();
        }
    }

    private static final class PausedWritableSource implements WritableSource {
        private final WritableSource delegate;
        private final CountDownLatch closed;
        final AtomicReference<StreamTerminationReason> closeReason = new AtomicReference<>();
        private volatile boolean paused;

        PausedWritableSource(WritableSource delegate, CountDownLatch closed) {
            this.delegate = delegate;
            this.closed = closed;
        }

        void pause() {
            paused = true;
        }

        @Override
        public void start() throws IOException {
            delegate.start();
        }

        @Override
        public void writeReadyCallback(Runnable callback) {
            delegate.writeReadyCallback(callback);
        }

        @Override
        public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
            return paused ? 0 : delegate.writeTo(socketChannel, maxBytes);
        }

        @Override
        public boolean hasRemaining() {
            return delegate.hasRemaining();
        }

        @Override
        public boolean isReadyToWrite() {
            return !paused && delegate.isReadyToWrite();
        }

        @Override
        public void close() throws IOException {
            close(null, null);
        }

        @Override
        public void close(StreamTerminationReason reason, Throwable cause) throws IOException {
            closeReason.set(reason);
            try {
                delegate.close(reason, cause);
            } finally {
                closed.countDown();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HeldSource source = new HeldSource();
        final CountingLogger logger = new CountingLogger();
        final AtomicInteger nextCalls = new AtomicInteger();
        final EventLoop eventLoop;

        Fixture(Handler.StreamingResponseInputPolicy policy, int maxRequestSize) throws IOException {
            this(policy, maxRequestSize, 64, true);
        }

        Fixture(Handler.StreamingResponseInputPolicy policy, int maxRequestSize,
                int readBufferSize, boolean autoStart) throws IOException {
            Handler handler = new Handler() {
                @Override
                public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> callback) {
                    if ("/stream".equals(request.uri())) {
                        callback.accept(MicrohttpResponse.withStreamingBody(
                                200, "OK", List.of(), () -> source));
                    } else if ("/next".equals(request.uri())) {
                        nextCalls.incrementAndGet();
                        callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("next")));
                    } else {
                        callback.accept(new MicrohttpResponse(404, "Not Found", List.of(), new byte[0]));
                    }
                }

                @Override
                public boolean monitorClientDisconnectsDuringStreamingResponse(MicrohttpRequest request) {
                    return policy == StreamingResponseInputPolicy.DISCARD;
                }

                @Override
                public StreamingResponseInputPolicy streamingResponseInputPolicy(MicrohttpRequest request) {
                    return policy == StreamingResponseInputPolicy.RETAIN
                            ? StreamingResponseInputPolicy.RETAIN
                            : Handler.super.streamingResponseInputPolicy(request);
                }
            };
            Options options = OptionsBuilder.newBuilder().withPort(0)
                    .withResolution(Duration.ofMillis(10))
                    .withMaxRequestSize(maxRequestSize)
                    .withMaxRequestBodySize(maxRequestSize)
                    .withReadBufferSize(readBufferSize)
                    .withConcurrency(1).build();
            eventLoop = new EventLoop(options, logger, handler);
            if (autoStart) start();
        }

        void start() {
            eventLoop.start();
        }

        Socket connect() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 3_000);
            socket.setSoTimeout(3_000);
            return socket;
        }

        @Override
        public void close() throws Exception {
            source.release();
            eventLoop.stop();
            eventLoop.join();
        }
    }

    private static final class HeldSource implements WritableSource {
        private final ByteBuffer body = ByteBuffer.wrap(ascii("1\r\nx\r\n0\r\n\r\n"));
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<StreamTerminationReason> closeReason = new AtomicReference<>();
        final AtomicInteger readinessTurns = new AtomicInteger();
        private volatile Runnable readyCallback;
        private volatile boolean released;
        private volatile boolean readinessFlood;

        @Override
        public void writeReadyCallback(Runnable callback) {
            readyCallback = callback;
        }

        @Override
        public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
            if (readinessFlood && !released) {
                readinessTurns.incrementAndGet();
                readyCallback.run();
                return 0;
            }
            if (!released) return 0;
            return socketChannel.write(body);
        }

        @Override
        public boolean hasRemaining() {
            return !released || body.hasRemaining();
        }

        @Override
        public boolean isReadyToWrite() {
            return released && body.hasRemaining();
        }

        void release() {
            readinessFlood = false;
            released = true;
            Runnable callback = readyCallback;
            if (callback != null) callback.run();
        }

        void startReadinessFlood() {
            readinessFlood = true;
            readyCallback.run();
        }

        @Override
        public void close() {
            readinessFlood = false;
            closed.countDown();
        }

        @Override
        public void close(StreamTerminationReason reason, Throwable cause) {
            readinessFlood = false;
            closeReason.set(reason);
            closed.countDown();
        }
    }

    private static final class CountingLogger implements Logger {
        final AtomicInteger halfCloses = new AtomicInteger();
        final AtomicInteger retainedReads = new AtomicInteger();
        final AtomicInteger discardedReads = new AtomicInteger();
        final AtomicInteger readLimitFailures = new AtomicInteger();

        @Override public boolean enabled() { return true; }
        @Override public void log(LogEntry... entries) {
            for (LogEntry entry : entries) {
                if (!"event".equals(entry.key())) continue;
                switch (entry.value()) {
                    case "read_half_close_during_streaming_response" -> halfCloses.incrementAndGet();
                    case "read_pipelined_bytes_during_streaming_response" -> retainedReads.incrementAndGet();
                    case "read_bytes_during_streaming_response" -> discardedReads.incrementAndGet();
                    case "streaming_response_read_limit_close" -> readLimitFailures.incrementAndGet();
                    default -> { }
                }
            }
        }
        @Override public void log(Exception exception, LogEntry... entries) { log(entries); }
    }

    private static void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(ascii(request));
        socket.getOutputStream().flush();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        while (output.size() < 8_192) {
            int value = input.read();
            if (value < 0) throw new IOException("EOF before response headers");
            output.write(value);
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) return output.toString(StandardCharsets.US_ASCII);
        }
        throw new IOException("Response headers exceeded test bound");
    }

    private static String readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input);
            int size = Integer.parseInt(line, 16);
            if (size == 0) {
                Assertions.assertEquals("", readLine(input));
                return body.toString(StandardCharsets.UTF_8);
            }
            body.writeBytes(input.readNBytes(size));
            Assertions.assertEquals("", readLine(input));
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < 8_192) {
            int value = input.read();
            if (value < 0) throw new IOException("EOF before CRLF");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("Malformed CRLF");
                return output.toString(StandardCharsets.US_ASCII);
            }
            output.write(value);
        }
        throw new IOException("Line exceeded test bound");
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        Assertions.assertTrue(condition.getAsBoolean(), failure);
    }
}
