/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.mcp.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class McpChunkGatheringTests {
    @Test
    public void framingAndPayloadUseOneGatheringWrite() throws Exception {
        Chunk chunk = Chunk.payload("hello");
        try (RecordingSocketChannel socket = new RecordingSocketChannel(Integer.MAX_VALUE)) {
            assertEquals(10L, chunk.writeTo(socket, 1024));
            assertEquals("5\r\nhello\r\n", socket.text());
            assertEquals(1, socket.gatheringWrites);
            assertEquals(0, socket.singleWrites);
            assertTrue(chunk.isComplete());
        }
    }

    @Test
    public void byteBudgetsCanEndInsideAnyFramingOrPayloadBuffer() throws Exception {
        Chunk chunk = Chunk.payload("hello");
        try (RecordingSocketChannel socket = new RecordingSocketChannel(Integer.MAX_VALUE)) {
            assertEquals(0L, chunk.writeTo(socket, 0));
            assertEquals(0, socket.gatheringWrites);
            assertEquals(2L, chunk.writeTo(socket, 2));
            assertEquals("5\r", socket.text());
            assertEquals(3L, chunk.writeTo(socket, 3));
            assertEquals("5\r\nhe", socket.text());
            assertEquals(4L, chunk.writeTo(socket, 4));
            assertEquals("5\r\nhello\r", socket.text());
            assertFalse(chunk.isComplete());
            assertEquals(1L, chunk.writeTo(socket, Long.MAX_VALUE));
            assertTrue(chunk.isComplete());
            assertEquals("5\r\nhello\r\n", socket.text());
        }
    }

    @Test
    public void partialAndZeroWritesPreserveRemainingBytesAndBudget() throws Exception {
        Chunk chunk = Chunk.payload("hello");
        try (RecordingSocketChannel socket = new RecordingSocketChannel(2)) {
            socket.zeroOnCall = 2;
            assertEquals(2L, chunk.writeTo(socket, 5));
            assertEquals("5\r", socket.text());
            assertFalse(chunk.isComplete());
            assertEquals(5L, chunk.writeTo(socket, 5));
            assertEquals("5\r\nhell", socket.text());
            assertEquals(3L, chunk.writeTo(socket, 1024));
            assertEquals("5\r\nhello\r\n", socket.text());
            assertTrue(chunk.isComplete());
        }
    }

    @Test
    public void gatheringFailureKeepsItsCauseAndRestoresTheTemporaryLimit() throws Exception {
        Chunk chunk = Chunk.payload("hello");
        IOException original = new IOException("controlled socket failure");
        try (RecordingSocketChannel socket = new RecordingSocketChannel(Integer.MAX_VALUE)) {
            socket.failure = original;
            IOException failure = assertThrows(IOException.class,
                    () -> chunk.writeTo(socket, 5));
            assertSame(original, failure.getCause());
            socket.failure = null;
            assertEquals(10L, chunk.writeTo(socket, 1024));
            assertEquals("5\r\nhello\r\n", socket.text());
            assertTrue(chunk.isComplete());
        }
    }

    @Test
    public void terminalChunkHonorsTheSameByteBudget() throws Exception {
        Chunk chunk = Chunk.terminal();
        try (RecordingSocketChannel socket = new RecordingSocketChannel(Integer.MAX_VALUE)) {
            assertEquals(2L, chunk.writeTo(socket, 2));
            assertFalse(chunk.isComplete());
            assertEquals(3L, chunk.writeTo(socket, 1024));
            assertEquals("0\r\n\r\n", socket.text());
            assertTrue(chunk.isComplete());
        }
    }

    @Test
    public void terminalPayloadAndTerminatorCanUseOneGatheringWrite() throws Exception {
        Chunk chunk = new Chunk(Chunk.invoke(null, "terminal", new Class<?>[] {byte[].class},
                (Object) "hello".getBytes(StandardCharsets.US_ASCII)));
        try (RecordingSocketChannel socket = new RecordingSocketChannel(Integer.MAX_VALUE)) {
            assertEquals(15L, chunk.writeTo(socket, Long.MAX_VALUE));
            assertEquals("5\r\nhello\r\n0\r\n\r\n", socket.text());
            assertEquals(1, socket.gatheringWrites);
            assertEquals(0, socket.singleWrites);
            assertTrue(chunk.isComplete());
        }
    }

    @Test
    public void guardIsCheckedBeforeEachGatheringWriteAndRevocationStopsPartialFrames() throws Exception {
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        Chunk chunk = new Chunk(Chunk.invoke(null, "payload",
                new Class<?>[] {byte[].class, Object.class, java.util.function.BooleanSupplier.class, Runnable.class, Runnable.class},
                "hello".getBytes(StandardCharsets.US_ASCII), null,
                (java.util.function.BooleanSupplier) () -> checks.incrementAndGet() <= 1, null, null));
        try (RecordingSocketChannel socket = new RecordingSocketChannel(2)) {
            assertEquals(2L, chunk.writeTo(socket, Long.MAX_VALUE));
            assertEquals("5\r", socket.text());
            assertEquals(1, socket.gatheringWrites);
            assertEquals(0, socket.singleWrites);
            assertEquals(2, checks.get());
            assertFalse(chunk.isComplete());
            assertEquals(0L, chunk.writeTo(socket, Long.MAX_VALUE));
            assertEquals("5\r", socket.text());
            assertEquals(1, socket.gatheringWrites);
        }
    }

    // Exercise the private queue item without producer threads or a network timing race.
    private record Chunk(Object value) {
        private static final Class<?> TYPE;
        static {
            try {
                TYPE = Class.forName("com.soklet.internal.mcp.transport.McpOutboundChannel$Chunk");
            } catch (ClassNotFoundException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
        static Chunk payload(String text) throws Exception {
            return new Chunk(invoke(null, "payload", new Class<?>[] {byte[].class, Object.class, java.util.function.BooleanSupplier.class, Runnable.class, Runnable.class},
                    text.getBytes(StandardCharsets.US_ASCII), null, null, null, null));
        }
        static Chunk terminal() throws Exception {
            return new Chunk(invoke(null, "terminalWithoutPayload", new Class<?>[0]));
        }
        long writeTo(SocketChannel socket, long budget) throws Exception {
            return (Long) invoke(value, "writeTo", new Class<?>[] {SocketChannel.class, long.class}, socket, budget);
        }
        boolean isComplete() throws Exception {
            return (Boolean) invoke(value, "isComplete", new Class<?>[0]);
        }
        private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
                throws Exception {
            Method method = TYPE.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause)
                    throw cause;
                throw failure;
            }
        }
    }

    private static final class RecordingSocketChannel extends SocketChannel {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int perWriteLimit;
        int gatheringWrites;
        int singleWrites;
        int zeroOnCall = -1;
        IOException failure;

        RecordingSocketChannel(int perWriteLimit) {
            super(SelectorProvider.provider());
            this.perWriteLimit = perWriteLimit;
        }
        String text() { return output.toString(StandardCharsets.US_ASCII); }
        @Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException {
            gatheringWrites++;
            if (failure != null) throw failure;
            if (gatheringWrites == zeroOnCall) return 0;
            int remaining = perWriteLimit;
            int written = 0;
            for (int i = offset; i < offset + length && remaining > 0; i++) {
                int size = Math.min(remaining, buffers[i].remaining());
                byte[] bytes = new byte[size];
                buffers[i].get(bytes);
                output.writeBytes(bytes);
                written += size;
                remaining -= size;
            }
            return written;
        }
        @Override public int write(ByteBuffer buffer) throws IOException {
            singleWrites++;
            return (int) write(new ByteBuffer[] {buffer}, 0, 1);
        }
        @Override public int read(ByteBuffer buffer) { return -1; }
        @Override public long read(ByteBuffer[] buffers, int offset, int length) { return -1; }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { return this; }
        @Override public Socket socket() { return new Socket(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public <T> T getOption(SocketOption<T> option) { return null; }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override protected void implCloseSelectableChannel() { }
        @Override protected void implConfigureBlocking(boolean block) { }
    }
}
