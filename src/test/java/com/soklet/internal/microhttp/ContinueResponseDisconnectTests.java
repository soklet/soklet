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

package com.soklet.internal.microhttp;

import com.soklet.MetricsCollector.TransportFailureReason;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted socket writes exercise inline and later selector turns without a TCP timing race. */
class ContinueResponseDisconnectTests {
    private static final byte[] EXPECT_HEADERS = ascii("POST /continue HTTP/1.1\r\nHost: localhost\r\n"
            + "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n");
    private static final byte[] CONTINUE_RESPONSE = ascii("HTTP/1.1 100 Continue\r\n\r\n");

    @Test
    void inlineContinueSocketResetClosesWithoutTransportFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.socket.ioFailure = new IOException("scripted reset");
            fixture.beginContinue();
            fixture.assertQuietSocketClose();
        }
    }

    @Test
    void deferredContinueSocketResetClosesWithoutTransportFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.socket.firstWriteLimit = 0;
            fixture.beginContinue();
            assertEquals(SelectionKey.OP_WRITE, fixture.key.interestOps());
            fixture.socket.ioFailure = new IOException("scripted reset");
            fixture.invoke("onWritable");
            fixture.assertQuietSocketClose();
        }
    }

    @Test
    void partialHealthyContinueWritesExactlyOnceAndResumesRequestReads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.socket.firstWriteLimit = 5;
            fixture.beginContinue();
            assertEquals(SelectionKey.OP_WRITE, fixture.key.interestOps());
            fixture.invoke("onWritable");
            assertArrayEquals(CONTINUE_RESPONSE, fixture.socket.output.toByteArray());
            assertEquals(SelectionKey.OP_READ, fixture.key.interestOps());
            assertNull(field(fixture.connection, "continueResponseBuffer"));
            assertTrue(fixture.socket.isOpen());
            assertEquals(0, fixture.releases.get());
            assertTrue(fixture.failures.isEmpty());
            assertTrue(fixture.logger.failures.isEmpty());
        }
    }

    @Test
    void inlineNonSocketFailureRemainsDiagnostic() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RuntimeException failure = new IllegalStateException("scripted application failure");
            fixture.socket.runtimeFailure = failure;
            fixture.beginContinue();
            assertFalse(fixture.socket.isOpen());
            assertEquals(List.of(TransportFailureReason.WRITE_ERROR), fixture.failures);
            assertEquals(List.of(failure), fixture.logger.failures);
            assertEquals(List.of("write_error"), fixture.logger.failureEvents);
            assertEquals(1, fixture.releases.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingSocketChannel socket = new RecordingSocketChannel();
        final RecordingLogger logger = new RecordingLogger();
        final List<TransportFailureReason> failures = new ArrayList<>();
        final AtomicInteger releases = new AtomicInteger();
        final ConnectionEventLoop loop;
        final RecordingKey key;
        final Object connection;

        Fixture() throws Exception {
            Options options = OptionsBuilder.newBuilder().withPort(0)
                    .withRequestHeaderTimeout(Duration.ofSeconds(3))
                    .withRequestBodyTimeout(Duration.ofSeconds(3)).build();
            loop = new ConnectionEventLoop(options, logger,
                    (request, response) -> fail("An incomplete request must not dispatch"),
                    new AtomicLong(), new AtomicBoolean(), new AtomicBoolean(),
                    failure -> fail(failure), () -> {}, reason -> {
                        failures.add(reason);
                        return () -> {};
                    });
            key = new RecordingKey(socket, (Selector) field(loop, "selector"));
            Constructor<EventLoop.ConnectionAdmission> admissionConstructor =
                    EventLoop.ConnectionAdmission.class.getDeclaredConstructor(AtomicInteger.class, Runnable.class);
            admissionConstructor.setAccessible(true);
            EventLoop.ConnectionAdmission admission = admissionConstructor.newInstance(
                    new AtomicInteger(1), (Runnable) releases::incrementAndGet);
            Class<?> type = Class.forName(ConnectionEventLoop.class.getName() + "$Connection");
            Constructor<?> constructor = type.getDeclaredConstructor(ConnectionEventLoop.class,
                    SocketChannel.class, SelectionKey.class, InetSocketAddress.class,
                    EventLoop.ConnectionAdmission.class);
            constructor.setAccessible(true);
            connection = constructor.newInstance(loop, socket, key, null, admission);
            ((AtomicInteger) field(loop, "connectionCount")).set(1);
        }

        void beginContinue() throws Exception {
            ByteTokenizer tokenizer = (ByteTokenizer) field(connection, "byteTokenizer");
            tokenizer.add(ByteBuffer.wrap(EXPECT_HEADERS));
            RequestParser parser = (RequestParser) field(connection, "requestParser");
            assertFalse(parser.parse());
            assertTrue(parser.readingBody());
            invoke("onPartialRequestParsed");
        }

        void invoke(String name) throws Exception {
            Method method = connection.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            try {
                method.invoke(connection);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception exception)
                    throw exception;
                if (cause instanceof Error error)
                    throw error;
                throw new AssertionError(cause);
            }
        }

        void assertQuietSocketClose() throws Exception {
            assertFalse(socket.isOpen());
            assertFalse(key.isValid());
            assertNull(field(connection, "continueResponseBuffer"));
            assertEquals(1, releases.get());
            assertEquals(0, loop.numConnections());
            assertTrue(failures.isEmpty(), failures.toString());
            assertTrue(logger.failures.isEmpty());
            assertTrue(logger.failureEvents.isEmpty());
        }

        @Override
        public void close() throws Exception {
            try {
                invoke("failSafeClose");
            } finally {
                loop.closeBeforeStart();
            }
            assertTrue(loop.resourcesClosed());
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class RecordingLogger implements Logger {
        final List<Exception> failures = new ArrayList<>();
        final List<String> failureEvents = new ArrayList<>();
        @Override public boolean enabled() { return true; }
        @Override public void log(LogEntry... entries) {}
        @Override public void log(Exception failure, LogEntry... entries) {
            failures.add(failure);
            for (LogEntry entry : entries)
                if (entry.key().equals("event"))
                    failureEvents.add(entry.value());
        }
    }

    private static final class RecordingKey extends SelectionKey {
        private final SocketChannel socket;
        private final Selector selector;
        private boolean valid = true;
        private int interests = SelectionKey.OP_READ;
        RecordingKey(SocketChannel socket, Selector selector) { this.socket = socket; this.selector = selector; }
        @Override public SelectableChannel channel() { return socket; }
        @Override public Selector selector() { return selector; }
        @Override public boolean isValid() { return valid; }
        @Override public void cancel() { valid = false; }
        @Override public int interestOps() { return interests; }
        @Override public SelectionKey interestOps(int operations) { interests = operations; return this; }
        @Override public int readyOps() { return interests; }
    }

    private static final class RecordingSocketChannel extends SocketChannel {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        @Nullable IOException ioFailure;
        @Nullable RuntimeException runtimeFailure;
        int firstWriteLimit = Integer.MAX_VALUE;
        int writes;
        RecordingSocketChannel() { super(SelectorProvider.provider()); }
        @Override public int write(ByteBuffer buffer) throws IOException {
            if (ioFailure != null)
                throw ioFailure;
            if (runtimeFailure != null)
                throw runtimeFailure;
            int count = Math.min(buffer.remaining(), writes++ == 0 ? firstWriteLimit : Integer.MAX_VALUE);
            byte[] bytes = new byte[count];
            buffer.get(bytes);
            output.writeBytes(bytes);
            return count;
        }
        @Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException {
            long total = 0;
            for (int index = offset; index < offset + length; index++) total += write(buffers[index]);
            return total;
        }
        @Override public int read(ByteBuffer buffer) { return 0; }
        @Override public long read(ByteBuffer[] buffers, int offset, int length) { return 0; }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { return this; }
        @Override public Socket socket() { return new Socket(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getLocalAddress() { throw new UnsupportedOperationException(); }
        @Override protected void implCloseSelectableChannel() {}
        @Override protected void implConfigureBlocking(boolean blocking) {}
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
