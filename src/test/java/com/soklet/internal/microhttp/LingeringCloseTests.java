/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import com.soklet.MetricsCollector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class LingeringCloseTests {
    @Test
    void closingResponseHalfClosesBeforeDiscardingInputAndNeverDispatchesPipeline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger terminations = new AtomicInteger();
        AtomicReference<StreamTerminationReason> termination = new AtomicReference<>();
        Handler handler = (request, callback) -> {
            calls.incrementAndGet();
            byte[] body = ascii("complete");
            callback.accept(MicrohttpResponse.withWritableSourceBody(200, "OK", List.of(),
                    body.length, () -> new ByteBufferWritableSource(ByteBuffer.wrap(body)) {
                        @Override public void close() { closes.incrementAndGet(); }
                    }).withBodyTerminationListener((reason, cause) -> {
                        termination.set(reason);
                        terminations.incrementAndGet();
                    }));
        };
        EventLoop eventLoop = new EventLoop(options(), handler);
        try {
            eventLoop.start();
            try (Socket socket = connect(eventLoop)) {
                socket.getOutputStream().write(ascii("GET /first HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\n\r\nGET /buffered HTTP/1.1\r\nHost: localhost\r\n\r\n"));
                String response = readToEnd(socket);
                assertTrue(response.endsWith("complete"), response);
                assertEquals(1, eventLoop.numAdmittedConnections(), "FIN must precede physical close");
                await(() -> terminations.get() == 1);
                assertEquals(StreamTerminationReason.COMPLETED, termination.get());
                assertEquals(1, closes.get());
                socket.getOutputStream().write(ascii("GET /late HTTP/1.1\r\nHost: localhost\r\n\r\n"));
                socket.shutdownOutput();
                await(() -> eventLoop.numAdmittedConnections() == 0);
                assertEquals(1, calls.get());
                assertEquals(1, closes.get());
                assertEquals(1, terminations.get());
            }
        } finally {
            stop(eventLoop);
        }
    }

    @Test
    void oversizedUploadCompletesBeforeReadingThe413() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EventLoop eventLoop = new EventLoop(options(), (request, callback) -> {
            calls.incrementAndGet();
            assertTrue(request.contentTooLarge());
            callback.accept(new MicrohttpResponse(413, "Payload Too Large", List.of(), ascii("too-large")));
        });
        try {
            eventLoop.start();
            for (int size : new int[]{200_000, 1_000_000, 5_000_000}) {
                try (SocketChannel channel = SocketChannel.open()) {
                    channel.socket().connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 2_000);
                    channel.configureBlocking(false);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    write(channel, ByteBuffer.wrap(ascii("POST /large HTTP/1.1\r\nHost: localhost\r\n"
                            + "Content-Length: " + size + "\r\n\r\n")), deadline);
                    write(channel, ByteBuffer.wrap(new byte[size]), deadline);
                    channel.shutdownOutput();
                    String response = readToEnd(channel, deadline);
                    assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"), response);
                    assertTrue(response.endsWith("too-large"), response);
                }
            }
            await(() -> eventLoop.numAdmittedConnections() == 0);
            assertEquals(3, calls.get());
        } finally {
            stop(eventLoop);
        }
    }

    @Test
    void absoluteCloseDeadlineAppliesWithCoarseResolutionAndDisabledRequestTimeouts() throws Exception {
        Options options = OptionsBuilder.newBuilder().withHost("127.0.0.1").withPort(0)
                .withConcurrency(1).withResolution(Duration.ofSeconds(30))
                .withRequestHeaderTimeout(Duration.ZERO).withRequestBodyTimeout(Duration.ZERO)
                .withResponseWriteIdleTimeout(Duration.ZERO).build();
        EventLoop eventLoop = new EventLoop(options, (request, callback) ->
                callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("done"))));
        try {
            eventLoop.start();
            try (Socket socket = connect(eventLoop)) {
                socket.getOutputStream().write(ascii("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\nsurplus"));
                assertTrue(readToEnd(socket).endsWith("done"));
                assertEquals(1, eventLoop.numAdmittedConnections());
                // Progress during the terminal phase must not extend its absolute deadline.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (eventLoop.numAdmittedConnections() != 0 && System.nanoTime() < deadline) {
                    try { socket.getOutputStream().write(0); } catch (IOException closed) { break; }
                    Thread.sleep(20);
                }
                await(() -> eventLoop.numAdmittedConnections() == 0);
            }
        } finally {
            stop(eventLoop);
        }
    }

    @Test
    void terminalDrainHasAnExactByteCeilingEvenWhenNoTimerCanRun() throws Exception {
        try (ByteBudgetFixture fixture = new ByteBudgetFixture()) {
            ByteTokenizer tokenizer = (ByteTokenizer) reflectedField(fixture.connection, "byteTokenizer");
            tokenizer.add(ByteBuffer.wrap(new byte[1_024]));
            fixture.invoke("beginLingeringClose");
            assertEquals(1, fixture.socket.outputShutdowns);
            assertEquals(SelectionKey.OP_READ, fixture.key.interestOps());
            assertEquals(0, tokenizer.size());
            assertEquals(0, tokenizer.capacity());
            assertEquals(1, ((Scheduler) reflectedField(fixture.loop, "timeoutQueue")).size());

            // The loop is never started and its scheduler is never pumped. Only
            // the byte ceiling can close this virtual twenty-MiB input source.
            fixture.invoke("runReadableTask");
            assertTrue(fixture.socket.isOpen(), "A zero-byte read must simply await readiness");
            assertEquals(0, fixture.socket.bytesRead);
            for (int turn = 0; turn < 200 && fixture.socket.isOpen(); turn++)
                fixture.invoke("runReadableTask");

            assertFalse(fixture.socket.isOpen());
            assertEquals(16L * 1_024 * 1_024, fixture.socket.bytesRead);
            assertEquals(4L * 1_024 * 1_024, fixture.socket.remainingInput);
            assertEquals(76_715, fixture.socket.lastReadCapacity,
                    "The final read must be clipped to the remaining byte budget");
            assertEquals(169, fixture.socket.reads, "One zero read and 168 bounded data reads");
            assertEquals(1, fixture.socket.closes);
            assertEquals(1, fixture.key.cancels);
            assertEquals(1, fixture.releases.get());
            assertEquals(0, fixture.loop.numConnections());
            assertEquals(0, ((Scheduler) reflectedField(fixture.loop, "timeoutQueue")).size());
            assertEquals(0, fixture.handlerCalls.get());
            assertEquals(0, fixture.cancelCalls.get());
            assertEquals(0, fixture.socket.writes);
            assertTrue(fixture.observations.isEmpty());
            fixture.invoke("failSafeClose");
            assertEquals(1, fixture.releases.get());
            assertEquals(1, fixture.socket.closes);
        }
    }

    @Test
    void selectedTerminalReadsNeverObserveProvisionalFailuresAndOrdinaryErrorsRemainRecorded()
            throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            try (ByteBudgetFixture fixture = new ByteBudgetFixture()) {
                fixture.invoke("beginLingeringClose");
                if (mode == 1) fixture.socket.endOfInput = true;
                if (mode == 2) fixture.socket.ioFailure = new IOException("Expected terminal reset");
                if (mode == 3) fixture.socket.runtimeFailure = new IllegalStateException("Expected terminal read failure");
                fixture.invoke("runReadableTask");
                assertTrue(fixture.observations.isEmpty(),
                        "Terminal zero/EOF/reset turns must not begin even a provisional failure observation");
                assertEquals(0, fixture.handlerCalls.get());
                assertEquals(0, fixture.cancelCalls.get());
                if (mode == 0) {
                    assertTrue(fixture.socket.isOpen());
                    assertEquals(0, fixture.releases.get());
                } else {
                    assertFalse(fixture.socket.isOpen());
                    assertEquals(1, fixture.releases.get());
                    fixture.invoke("runReadableTask");
                    assertTrue(fixture.observations.isEmpty(), "Stale readiness after close is inert");
                }
            }
        }

        try (ByteBudgetFixture fixture = new ByteBudgetFixture()) {
            ((ByteTokenizer) reflectedField(fixture.connection, "byteTokenizer"))
                    .add(ByteBuffer.wrap(ascii("GET /partial")));
            fixture.socket.ioFailure = new IOException("Expected ordinary request read failure");
            fixture.invoke("runReadableTask");
            assertEquals(2, fixture.observations.size(), "Ordinary reads retain their provisional wrapper");
            assertTrue(fixture.observations.stream().allMatch(observation -> observation.closed));
            assertEquals(List.of(MetricsCollector.TransportFailureReason.READ_ERROR), fixture.observations.stream()
                    .filter(observation -> !observation.discarded).map(observation -> observation.reason).toList());
            assertEquals(1, fixture.releases.get());
            assertFalse(fixture.socket.isOpen());
        }
    }

    @Test
    void terminalDrainStopsAtItsByteLimitAndReleasesAdmission() throws Exception {
        EventLoop eventLoop = new EventLoop(options(), (request, callback) ->
                callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("done"))));
        try {
            eventLoop.start();
            try (SocketChannel channel = SocketChannel.open()) {
                channel.socket().connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 2_000);
                channel.configureBlocking(false);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                write(channel, ByteBuffer.wrap(ascii("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\nsurplus")), deadline);
                assertTrue(readToEnd(channel, deadline).endsWith("done"));
                // The finite discard ceiling must release capacity even when the peer never sends FIN.
                try { write(channel, ByteBuffer.wrap(new byte[20 * 1024 * 1024]), deadline); }
                catch (IOException closedAtBound) { /* A reset after the response is already consumed is expected. */ }
                await(() -> eventLoop.numAdmittedConnections() == 0);
            }
        } finally {
            stop(eventLoop);
        }
    }

    @Test
    void gracefulDrainWaitsForObservedSurplusInputAndForcedStopDoesNot() throws Exception {
        for (boolean force : new boolean[]{false, true}) {
            EventLoop eventLoop = new EventLoop(options(), (request, callback) ->
                    callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("done"))));
            try {
                eventLoop.start();
                int port = eventLoop.getPort();
                try (Socket socket = connect(eventLoop)) {
                    socket.getOutputStream().write(ascii("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\nsurplus"));
                    assertTrue(readToEnd(socket).endsWith("done"));
                    assertEquals(1, eventLoop.numAdmittedConnections());
                    eventLoop.stopAccepting();
                    eventLoop.beginDrain();
                    if (force) {
                        eventLoop.stopConnections();
                        assertTrue(eventLoop.join(Duration.ofMillis(500)), "Forced stop must not await lingering input");
                    } else {
                        assertFalse(eventLoop.join(Duration.ofMillis(50)), "Graceful drain retains the half-closed slot");
                        socket.shutdownOutput();
                        assertTrue(eventLoop.join(Duration.ofSeconds(5)));
                    }
                    assertEquals(0, eventLoop.numAdmittedConnections());
                }
                try (ServerSocketChannel rebound = ServerSocketChannel.open()) {
                    rebound.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
                    rebound.bind(new InetSocketAddress("127.0.0.1", port));
                }
            } finally {
                stop(eventLoop);
            }
        }
    }

    @Test
    void gracefulDrainClosesConsumedRequestsPromptlyAndRetainsEarlyUploads() throws Exception {
        for (String request : List.of("POST /fixed HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\nabc",
                "POST /chunked HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "3\r\nabc\r\n0\r\nX-Trailer: complete\r\n\r\n",
                "POST /early HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5000\r\n\r\n")) {
            AtomicReference<java.util.function.Consumer<MicrohttpResponse>> pending = new AtomicReference<>();
            EventLoop eventLoop = new EventLoop(options(), (parsed, callback) -> pending.set(callback));
            try {
                eventLoop.start();
                try (Socket socket = connect(eventLoop)) {
                    socket.getOutputStream().write(ascii(request));
                    await(() -> pending.get() != null);
                    eventLoop.stopAccepting();
                    eventLoop.beginDrain();
                    boolean earlyUpload = request.contains("/early");
                    pending.get().accept(new MicrohttpResponse(earlyUpload ? 413 : 200, "Response", List.of(), ascii("done")));
                    assertTrue(readToEnd(socket).endsWith("done"));
                    if (earlyUpload) {
                        assertFalse(eventLoop.join(Duration.ofMillis(200)),
                                "Early rejection must retain protection even during graceful drain");
                        assertEquals(1, eventLoop.numAdmittedConnections());
                        socket.shutdownOutput();
                        assertTrue(eventLoop.join(Duration.ofSeconds(5)));
                    } else {
                        assertTrue(eventLoop.join(Duration.ofMillis(200)),
                                "An entirely parsed request must not retain an idle one-second terminal drain");
                    }
                    assertEquals(0, eventLoop.numAdmittedConnections());
                }
            } finally {
                stop(eventLoop);
            }
        }
    }

    @Test
    void latePipelinedInputCannotTruncateALargeClosingResponse() throws Exception {
        for (boolean graceful : new boolean[]{false, true}) {
            AtomicReference<java.util.function.Consumer<MicrohttpResponse>> pending = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            byte[] body = new byte[256 * 1_024];
            Arrays.fill(body, (byte) 'x');
            EventLoop eventLoop = new EventLoop(options(), (request, callback) -> {
                calls.incrementAndGet();
                pending.set(callback);
            });
            try {
                eventLoop.start();
                try (Socket socket = new Socket()) {
                    socket.setReceiveBufferSize(65_536);
                    socket.connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 2_000);
                    socket.getOutputStream().write(ascii("GET /first HTTP/1.1\r\nHost: localhost\r\n"
                            + (graceful ? "" : "Connection: close\r\n") + "\r\n"));
                    await(() -> pending.get() != null);
                    // Standard handlers suspend reads after dispatch. This second request
                    // therefore remains in the socket, rather than the parsed tokenizer.
                    socket.getOutputStream().write(ascii("GET /late HTTP/1.1\r\nHost: localhost\r\n\r\n"));
                    Thread.sleep(100);
                    if (graceful) {
                        eventLoop.stopAccepting();
                        eventLoop.beginDrain();
                    }
                    pending.get().accept(new MicrohttpResponse(200, "OK", List.of(), body));
                    // Keep some response bytes queued in the server's TCP send buffer.
                    Thread.sleep(100);
                    byte[] response = readClosingResponse(socket, body.length);
                    String text = new String(response, StandardCharsets.US_ASCII);
                    int bodyStart = text.indexOf("\r\n\r\n") + 4;
                    assertTrue(text.startsWith("HTTP/1.1 200"));
                    assertTrue(text.contains("\r\nContent-Length: " + body.length + "\r\n"));
                    assertTrue(text.contains("\r\nConnection: close\r\n"));
                    assertArrayEquals(body, Arrays.copyOfRange(response, bodyStart, response.length));
                    assertEquals(1, eventLoop.numAdmittedConnections(), "Queued input must retain the terminal drain");
                    socket.shutdownOutput();
                    await(() -> eventLoop.numAdmittedConnections() == 0);
                    assertEquals(1, calls.get(), "Late input must never dispatch after a closing response");
                }
            } finally {
                stop(eventLoop);
            }
        }
    }

    @Test
    void halfClosedClientAndRawErrorResponseStillFinishCleanly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EventLoop eventLoop = new EventLoop(options(), (request, callback) -> {
            calls.incrementAndGet();
            callback.accept(new MicrohttpResponse(200, "OK", List.of(), ascii("done")));
        });
        try {
            eventLoop.start();
            try (Socket socket = connect(eventLoop)) {
                socket.getOutputStream().write(ascii("GET / HTTP/1.0\r\n\r\n"));
                socket.shutdownOutput();
                assertTrue(readToEnd(socket).endsWith("done"));
            }
            await(() -> eventLoop.numAdmittedConnections() == 0);
            try (Socket socket = connect(eventLoop)) {
                socket.getOutputStream().write(ascii("INVALID\r\n\r\n"));
                assertTrue(readToEnd(socket).startsWith("HTTP/1.1 400"));
                assertEquals(1, eventLoop.numAdmittedConnections());
                socket.shutdownOutput();
            }
            await(() -> eventLoop.numAdmittedConnections() == 0);
            assertEquals(1, calls.get());
        } finally {
            stop(eventLoop);
        }
    }

    private static final class ByteBudgetFixture implements AutoCloseable {
        final VirtualInputSocketChannel socket = new VirtualInputSocketChannel();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger handlerCalls = new AtomicInteger();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final List<RecordingFailureObservation> observations = new ArrayList<>();
        final ConnectionEventLoop loop;
        final RecordingKey key;
        final Object connection;

        ByteBudgetFixture() throws Exception {
            Options options = OptionsBuilder.newBuilder().withPort(0).withReadBufferSize(100_003).build();
            Handler handler = new Handler() {
                @Override public void handle(MicrohttpRequest request,
                        java.util.function.Consumer<MicrohttpResponse> callback) { handlerCalls.incrementAndGet(); }
                @Override public void cancel(MicrohttpRequest request, StreamTerminationReason reason,
                        Throwable cause) { cancelCalls.incrementAndGet(); }
            };
            loop = new ConnectionEventLoop(options, NoopLogger.instance(), handler,
                    new AtomicLong(), new AtomicBoolean(), new AtomicBoolean(),
                    failure -> fail(failure), () -> {}, reason -> {
                        RecordingFailureObservation observation = new RecordingFailureObservation(reason);
                        observations.add(observation);
                        return observation;
                    });
            key = new RecordingKey(socket, (Selector) reflectedField(loop, "selector"));
            Constructor<EventLoop.ConnectionAdmission> admissionConstructor =
                    EventLoop.ConnectionAdmission.class.getDeclaredConstructor(AtomicInteger.class, Runnable.class);
            admissionConstructor.setAccessible(true);
            EventLoop.ConnectionAdmission admission = admissionConstructor.newInstance(
                    new AtomicInteger(1), (Runnable) releases::incrementAndGet);
            Class<?> type = Class.forName(ConnectionEventLoop.class.getName() + "$Connection");
            Constructor<?> constructor = type.getDeclaredConstructor(ConnectionEventLoop.class,
                    SocketChannel.class, SelectionKey.class, EventLoop.ConnectionAdmission.class);
            constructor.setAccessible(true);
            connection = constructor.newInstance(loop, socket, key, admission);
            ((AtomicInteger) reflectedField(loop, "connectionCount")).set(1);
        }

        void invoke(String name) throws Exception {
            Method method = connection.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            try { method.invoke(connection); }
            catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new AssertionError(cause);
            }
        }

        @Override public void close() throws Exception {
            try { invoke("failSafeClose"); }
            finally { loop.closeBeforeStart(); }
            assertTrue(loop.resourcesClosed());
        }
    }

    private static Object reflectedField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static final class RecordingFailureObservation implements TransportFailureObserver.Observation {
        final MetricsCollector.TransportFailureReason reason;
        boolean discarded;
        boolean closed;
        RecordingFailureObservation(MetricsCollector.TransportFailureReason reason) { this.reason = reason; }
        @Override public void discard() { this.discarded = true; }
        @Override public void close() { this.closed = true; }
    }

    private static final class RecordingKey extends SelectionKey {
        private final SocketChannel socket;
        private final Selector selector;
        private boolean valid = true;
        private int interests = SelectionKey.OP_READ;
        int cancels;
        RecordingKey(SocketChannel socket, Selector selector) { this.socket = socket; this.selector = selector; }
        @Override public SelectableChannel channel() { return socket; }
        @Override public Selector selector() { return selector; }
        @Override public boolean isValid() { return valid; }
        @Override public void cancel() { valid = false; cancels++; }
        @Override public int interestOps() { return interests; }
        @Override public SelectionKey interestOps(int operations) { interests = operations; return this; }
        @Override public int readyOps() { return interests; }
    }

    private static final class VirtualInputSocketChannel extends SocketChannel {
        long remainingInput = 20L * 1_024 * 1_024;
        long bytesRead;
        int reads;
        int writes;
        int outputShutdowns;
        int closes;
        int lastReadCapacity;
        boolean endOfInput;
        @Nullable IOException ioFailure;
        @Nullable RuntimeException runtimeFailure;
        VirtualInputSocketChannel() { super(SelectorProvider.provider()); }
        @Override public int read(ByteBuffer buffer) throws IOException {
            if (ioFailure != null) throw ioFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            if (endOfInput) return -1;
            if (reads++ == 0) return 0;
            lastReadCapacity = buffer.remaining();
            int count = (int) Math.min(buffer.remaining(), remainingInput);
            buffer.position(buffer.position() + count);
            remainingInput -= count;
            bytesRead += count;
            return count;
        }
        @Override public long read(ByteBuffer[] buffers, int offset, int length) {
            throw new AssertionError("Terminal drain must use the shared bounded read buffer");
        }
        @Override public int write(ByteBuffer buffer) { writes++; return 0; }
        @Override public long write(ByteBuffer[] buffers, int offset, int length) { writes++; return 0; }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { throw new AssertionError("Input must remain readable until close"); }
        @Override public SocketChannel shutdownOutput() { outputShutdowns++; return this; }
        @Override public Socket socket() { throw new UnsupportedOperationException(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { throw new UnsupportedOperationException(); }
        @Override protected void implCloseSelectableChannel() { closes++; }
        @Override protected void implConfigureBlocking(boolean blocking) {}
    }

    private static Options options() {
        return OptionsBuilder.newBuilder().withHost("127.0.0.1").withPort(0).withConcurrency(1)
                .withResolution(Duration.ofMillis(10)).withMaxRequestSize(1_024)
                .withReadBufferSize(64 * 1_024).build();
    }

    private static Socket connect(EventLoop eventLoop) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", eventLoop.getPort()), 2_000);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static void stop(EventLoop eventLoop) throws InterruptedException {
        eventLoop.stop();
        assertTrue(eventLoop.join(Duration.ofSeconds(5)), "Connection loops must terminate");
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition did not become true before its deadline");
    }

    private static void write(SocketChannel channel, ByteBuffer bytes, long deadline) throws Exception {
        while (bytes.hasRemaining()) {
            if (System.nanoTime() >= deadline) fail("Client upload exceeded its deadline");
            if (channel.write(bytes) == 0) Thread.sleep(1);
        }
    }

    private static String readToEnd(Socket socket) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] bytes = new byte[8_192];
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) fail("Client response exceeded its deadline");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = socket.getInputStream().read(bytes);
            if (count < 0) return response.toString(StandardCharsets.US_ASCII);
            response.write(bytes, 0, count);
            if (response.size() > 64 * 1_024) fail("Unexpectedly large test response");
        }
    }

    private static byte[] readClosingResponse(Socket socket, int expectedBodyBytes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] bytes = new byte[8_192];
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) fail("Client response exceeded its deadline");
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                int count = socket.getInputStream().read(bytes);
                if (count < 0) break;
                response.write(bytes, 0, count);
                if (response.size() > expectedBodyBytes + 65_536) fail("Unexpected response size");
            }
        } catch (IOException failure) {
            // A reset after the complete framed response is harmless for this oracle.
            int headerEnd = response.toString(StandardCharsets.US_ASCII).indexOf("\r\n\r\n");
            if (headerEnd < 0 || response.size() - headerEnd - 4 != expectedBodyBytes)
                throw failure;
        }
        return response.toByteArray();
    }

    private static String readToEnd(SocketChannel channel, long deadline) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        ByteBuffer bytes = ByteBuffer.allocate(8_192);
        while (true) {
            if (System.nanoTime() >= deadline) fail("Client response exceeded its deadline");
            int count = channel.read(bytes);
            if (count < 0) return response.toString(StandardCharsets.US_ASCII);
            if (count == 0) { Thread.sleep(1); continue; }
            response.write(bytes.array(), 0, count);
            bytes.clear();
        }
    }

    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
