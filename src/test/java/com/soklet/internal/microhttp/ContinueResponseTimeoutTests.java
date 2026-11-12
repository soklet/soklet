/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.soklet.internal.microhttp;

import com.soklet.MetricsCollector.TransportFailureReason;
import com.soklet.StreamTerminationReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual parser/deadline/writable turns, without listeners or event-loop threads. */
@Timeout(30)
class ContinueResponseTimeoutTests {
    private static final String CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n";
    private static final String EXPECT = "POST /continue HTTP/1.1\r\nHost: localhost\r\n"
            + "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n";
    private static final byte[] CUSTOM_TIMEOUT = ascii("HTTP/1.1 408 Request Timeout\r\n"
            + "Connection: close\r\nContent-Length: 0\r\nX-Timeout: custom\r\n\r\n");

    @Test
    void bodyDeadlineSerializesEveryPartialInterimAndFinalWriteCombination() throws Exception {
        for (Scenario scenario : List.of(
                new Scenario(0, 0, false),
                new Scenario(5, Integer.MAX_VALUE, false),
                new Scenario(0, Integer.MAX_VALUE, false),
                new Scenario(0, 8, false),
                new Scenario(5, 8, false),
                new Scenario(Integer.MAX_VALUE, Integer.MAX_VALUE, false),
                new Scenario(5, Integer.MAX_VALUE, true))) {
            int[] writes = scenario.finishBeforeTimeout
                    ? new int[]{scenario.firstWrite, Integer.MAX_VALUE, scenario.nextWrite}
                    : new int[]{scenario.firstWrite, scenario.nextWrite, Integer.MAX_VALUE};
            try (Fixture fixture = new Fixture(false, Duration.ofSeconds(3), writes)) {
                fixture.beginContinue();
                assertNotNull(field(fixture.connection, "requestReadTimeoutTask"));
                assertEquals(true, field(fixture.connection, "requestReadTimeoutBodyPhase"));
                if (scenario.finishBeforeTimeout) fixture.invoke("onWritable");
                fixture.expireNextDeadline();
                fixture.finishQueuedResponse();
                String response = fixture.socket.wire();
                String prefix = scenario.firstWrite == 0 ? "" : CONTINUE;
                assertTrue(response.startsWith(prefix + "HTTP/1.1 408 Request Timeout\r\n"), response);
                assertEquals(prefix.isEmpty() ? 1 : 2, response.split("HTTP/1.1 ", -1).length - 1, response);
                assertTrue(response.contains("\r\nConnection: close\r\n"), response);
                assertTrue(response.contains("\r\nContent-Length: 0\r\n"), response);
                assertNull(field(fixture.connection, "continueResponseBuffer"));
                assertNull(field(fixture.connection, "writableSource"));
                assertTrue(fixture.socket.outputShutdown);
                assertEquals(0, fixture.applicationCalls.get());
                assertEquals(List.of(TransportFailureReason.REQUEST_READ_TIMEOUT), fixture.failures());
            }
        }
    }

    @Test
    void queuedFinalResponseRenewsIdleDeadlineOnlyForPositiveInterimProgress() throws Exception {
        try (Fixture fixture = new Fixture(false, Duration.ofSeconds(3), 5, 0, 1, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            fixture.beginContinue();
            fixture.expireNextDeadline();
            Object original = field(fixture.connection, "responseWriteIdleTimeoutTask");
            assertNotNull(original);
            fixture.invoke("onWritable");
            Object progressed = field(fixture.connection, "responseWriteIdleTimeoutTask");
            assertNotNull(progressed);
            assertNotSame(original, progressed, "Interim bytes ahead of a queued final are socket-write progress");
            fixture.invoke("onWritable");
            assertSame(progressed, field(fixture.connection, "responseWriteIdleTimeoutTask"),
                    "A zero-byte write cannot extend the idle budget");
            fixture.finishQueuedResponse();
            assertTrue(fixture.socket.wire().startsWith(CONTINUE + "HTTP/1.1 408 Request Timeout\r\n"));
            assertNull(field(fixture.connection, "responseWriteIdleTimeoutTask"));
        }
    }

    @Test
    void disabledWriteIdleBudgetIsNotEnabledByInterimProgress() throws Exception {
        try (Fixture fixture = new Fixture(false, Duration.ZERO, 5, 0, 1, 0,
                Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            fixture.beginContinue();
            fixture.expireNextDeadline();
            assertNull(field(fixture.connection, "responseWriteIdleTimeoutTask"));
            assertEquals(0, ((Scheduler) field(fixture.loop, "timeoutQueue")).size());
            fixture.invoke("onWritable");
            assertNull(field(fixture.connection, "responseWriteIdleTimeoutTask"));
            assertEquals(0, ((Scheduler) field(fixture.loop, "timeoutQueue")).size());
            fixture.finishQueuedResponse();
            assertNull(field(fixture.connection, "responseWriteIdleTimeoutTask"));
            assertNotNull(field(fixture.connection, "lingeringCloseTimeoutTask"),
                    "The terminal close remains bounded independently of write-idle policy");
        }
    }

    @Test
    void aBlockedInterimAheadOfTheFinalResponseStillExpiresTheWriteIdleBudget() throws Exception {
        try (Fixture fixture = new Fixture(false, Duration.ofMillis(5), 5, 0)) {
            fixture.beginContinue();
            fixture.expireNextDeadline();
            assertNotNull(field(fixture.connection, "continueResponseBuffer"));
            assertNotNull(field(fixture.connection, "writableSource"));
            fixture.expireNextDeadline();
            fixture.assertClosedOnce();
            assertNull(field(fixture.connection, "continueResponseBuffer"));
            assertNull(field(fixture.connection, "writableSource"));
            assertNull(field(fixture.connection, "responseWriteIdleTimeoutTask"));
            assertEquals(List.of(TransportFailureReason.REQUEST_READ_TIMEOUT,
                    TransportFailureReason.RESPONSE_WRITE_IDLE_TIMEOUT), fixture.failures());
        }
    }

    @Test
    void resetAndForcedTerminalCloseReleaseBothPendingWritersExactlyOnce() throws Exception {
        for (boolean force : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(false, Duration.ofSeconds(3), 5, 0)) {
                fixture.beginContinue();
                fixture.expireNextDeadline();
                if (force) fixture.forceClose();
                else {
                    fixture.socket.ioFailure = new IOException("Expected peer reset");
                    fixture.invoke("onWritable");
                }
                fixture.assertClosedOnce();
                assertNull(field(fixture.connection, "continueResponseBuffer"));
                assertNull(field(fixture.connection, "writableSource"));
                assertEquals(0, ((Scheduler) field(fixture.loop, "timeoutQueue")).size());
                assertEquals(List.of(TransportFailureReason.REQUEST_READ_TIMEOUT), fixture.failures());
                fixture.forceClose();
                fixture.assertClosedOnce();
            }
        }
    }

    @Test
    void anAsynchronousUnparsedResponseUsesTheSameInterimFirstWriter() throws Exception {
        try (Fixture fixture = new Fixture(true, Duration.ofSeconds(3), 5, 0, Integer.MAX_VALUE)) {
            fixture.beginContinue();
            fixture.expireNextDeadline();
            assertEquals(0, fixture.key.interestOps());
            assertNull(field(fixture.connection, "writableSource"));
            fixture.rawCallback.get().accept(CUSTOM_TIMEOUT);
            fixture.runQueuedTasks();
            fixture.finishQueuedResponse();
            assertEquals(CONTINUE + new String(CUSTOM_TIMEOUT, StandardCharsets.US_ASCII), fixture.socket.wire());
            assertEquals(0, fixture.applicationCalls.get());
            assertTrue(fixture.socket.outputShutdown);
        }
    }

    private record Scenario(int firstWrite, int nextWrite, boolean finishBeforeTimeout) {}

    private static final class Fixture implements AutoCloseable {
        final ScriptedSocket socket;
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger admitted = new AtomicInteger(1);
        final AtomicInteger applicationCalls = new AtomicInteger();
        final AtomicReference<Consumer<byte[]>> rawCallback = new AtomicReference<>();
        final List<FailureObservation> observations = new ArrayList<>();
        final ConnectionEventLoop loop;
        final RecordingKey key;
        final Object connection;

        Fixture(boolean asynchronous, Duration idleTimeout, int... writes) throws Exception {
            socket = new ScriptedSocket(writes);
            Handler handler = new Handler() {
                @Override public void handle(MicrohttpRequest request, Consumer<MicrohttpResponse> response) {
                    applicationCalls.incrementAndGet();
                    fail("An incomplete request must never dispatch");
                }
                @Override public boolean handleUnparsedRequest(UnparsedRequestRejection rejection,
                        Consumer<byte[]> response) {
                    if (!asynchronous) return false;
                    rawCallback.set(response);
                    return true;
                }
            };
            Options options = OptionsBuilder.newBuilder().withPort(0).withConcurrency(1)
                    .withRequestHeaderTimeout(Duration.ofSeconds(5))
                    .withRequestBodyTimeout(Duration.ofMillis(10))
                    .withResponseWriteIdleTimeout(idleTimeout).build();
            loop = new ConnectionEventLoop(options, NoopLogger.instance(), handler, new AtomicLong(),
                    new AtomicBoolean(), new AtomicBoolean(), failure -> fail(failure), () -> {}, reason -> {
                        FailureObservation observation = new FailureObservation(reason);
                        observations.add(observation);
                        return observation;
                    });
            key = new RecordingKey(socket, (Selector) field(loop, "selector"));
            Constructor<EventLoop.ConnectionAdmission> admissionConstructor =
                    EventLoop.ConnectionAdmission.class.getDeclaredConstructor(AtomicInteger.class, Runnable.class);
            admissionConstructor.setAccessible(true);
            EventLoop.ConnectionAdmission admission = admissionConstructor.newInstance(admitted,
                    (Runnable) releases::incrementAndGet);
            Class<?> type = Class.forName(ConnectionEventLoop.class.getName() + "$Connection");
            Constructor<?> constructor = type.getDeclaredConstructor(ConnectionEventLoop.class,
                    SocketChannel.class, SelectionKey.class, EventLoop.ConnectionAdmission.class);
            constructor.setAccessible(true);
            connection = constructor.newInstance(loop, socket, key, admission);
            ((AtomicInteger) field(loop, "connectionCount")).set(1);
        }

        void beginContinue() throws Exception {
            ByteTokenizer tokenizer = (ByteTokenizer) field(connection, "byteTokenizer");
            tokenizer.add(ByteBuffer.wrap(ascii(EXPECT)));
            RequestParser parser = (RequestParser) field(connection, "requestParser");
            assertFalse(parser.parse());
            assertTrue(parser.readingBody());
            invoke("onPartialRequestParsed");
        }

        void expireNextDeadline() throws Exception {
            Scheduler scheduler = (Scheduler) field(loop, "timeoutQueue");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            List<Runnable> expired = List.of();
            while (expired.isEmpty() && System.nanoTime() < deadline) {
                expired = scheduler.expired();
                if (expired.isEmpty()) Thread.sleep(1);
            }
            assertFalse(expired.isEmpty(), "The configured transport deadline must expire");
            expired.forEach(Runnable::run);
        }

        void finishQueuedResponse() throws Exception {
            for (int turn = 0; turn < 8 && field(connection, "writableSource") != null; turn++) {
                assertTrue((key.interestOps() & SelectionKey.OP_WRITE) != 0,
                        "A pending final response must never be stranded in read mode");
                invoke("onWritable");
            }
            assertNull(field(connection, "writableSource"));
        }

        @SuppressWarnings("unchecked")
        void runQueuedTasks() throws Exception {
            Queue<Runnable> tasks = (Queue<Runnable>) field(loop, "taskQueue");
            for (int turn = 0; turn < 4; turn++) {
                Runnable task = tasks.poll();
                if (task == null) return;
                task.run();
            }
            assertTrue(tasks.isEmpty());
        }

        List<TransportFailureReason> failures() {
            return observations.stream().filter(observation -> observation.closed && !observation.discarded)
                    .map(observation -> observation.reason).toList();
        }

        void forceClose() throws Exception {
            Method method = connection.getClass().getDeclaredMethod("failSafeClose",
                    StreamTerminationReason.class, Throwable.class);
            method.setAccessible(true);
            method.invoke(connection, StreamTerminationReason.SERVER_STOPPING, null);
        }

        void invoke(String name) throws Exception {
            Method method = connection.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            try { method.invoke(connection); }
            catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof Exception exception) throw exception;
                if (wrapped.getCause() instanceof Error error) throw error;
                throw new AssertionError(wrapped.getCause());
            }
        }

        void assertClosedOnce() {
            assertFalse(socket.isOpen()); assertFalse(key.isValid());
            assertEquals(1, socket.closes); assertEquals(1, key.cancels);
            assertEquals(1, releases.get()); assertEquals(0, admitted.get());
            assertEquals(0, loop.numConnections());
        }

        @Override public void close() throws Exception {
            try { forceClose(); }
            finally { loop.closeBeforeStart(); }
            assertClosedOnce();
            assertTrue(loop.resourcesClosed());
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(instance);
    }

    private static final class FailureObservation implements TransportFailureObserver.Observation {
        final TransportFailureReason reason;
        boolean discarded;
        boolean closed;
        FailureObservation(TransportFailureReason reason) { this.reason = reason; }
        @Override public void discard() { discarded = true; }
        @Override public void close() { closed = true; }
    }

    private static final class RecordingKey extends SelectionKey {
        final SocketChannel socket; final Selector selector;
        boolean valid = true; int interests = SelectionKey.OP_READ; int cancels;
        RecordingKey(SocketChannel socket, Selector selector) { this.socket = socket; this.selector = selector; }
        @Override public SelectableChannel channel() { return socket; }
        @Override public Selector selector() { return selector; }
        @Override public boolean isValid() { return valid; }
        @Override public void cancel() { valid = false; cancels++; }
        @Override public int interestOps() { return interests; }
        @Override public SelectionKey interestOps(int value) { interests = value; return this; }
        @Override public int readyOps() { return interests; }
    }

    private static final class ScriptedSocket extends SocketChannel {
        final ArrayDeque<Integer> limits = new ArrayDeque<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        @Nullable IOException ioFailure;
        boolean outputShutdown; int closes;
        ScriptedSocket(int[] writes) { super(SelectorProvider.provider()); for (int count : writes) limits.add(count); }
        @Override public int write(ByteBuffer bytes) throws IOException {
            if (ioFailure != null) throw ioFailure;
            int count = Math.min(bytes.remaining(), limits.isEmpty() ? Integer.MAX_VALUE : limits.remove());
            byte[] written = new byte[count]; bytes.get(written); output.writeBytes(written); return count;
        }
        @Override public long write(ByteBuffer[] bytes, int offset, int length) throws IOException {
            long total = 0; for (int index = offset; index < offset + length; index++) total += write(bytes[index]); return total;
        }
        String wire() { return output.toString(StandardCharsets.US_ASCII); }
        @Override public int read(ByteBuffer bytes) { return 0; }
        @Override public long read(ByteBuffer[] bytes, int offset, int length) { return 0; }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { outputShutdown = true; return this; }
        @Override public Socket socket() { return new Socket(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override protected void implCloseSelectableChannel() { closes++; }
        @Override protected void implConfigureBlocking(boolean blocking) {}
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
