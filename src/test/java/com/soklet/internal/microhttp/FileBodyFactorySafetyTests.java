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

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileBodyFactorySafetyTests {
    private static final long[][] INVALID_REGIONS = {
            {-1, 0}, {0, -1}, {Long.MAX_VALUE, 1}, {Long.MAX_VALUE - 1, 2}
    };

    @Test
    void invalidPathRegionsAreRejectedBeforeALazyFactoryCanOpenAFile(@TempDir Path directory) {
        // A metadata error must be rejected even before an absent file could be opened.
        Path absent = directory.resolve("must-not-be-opened");
        for (long[] region : INVALID_REGIONS)
            assertThrows(IllegalArgumentException.class, () -> MicrohttpResponse.withFileBody(
                    200, "OK", List.of(), absent, region[0], region[1]));
        assertFalse(Files.exists(absent));
    }

    @Test
    void invalidChannelRegionsAreRejectedWithoutTakingCallerOwnership(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("body");
        Files.writeString(file, "abcdef");
        for (boolean owned : List.of(false, true)) {
            for (long[] region : INVALID_REGIONS) {
                try (FileChannel channel = FileChannel.open(file)) {
                    assertThrows(IllegalArgumentException.class, () -> MicrohttpResponse.withFileChannelBody(
                            200, "OK", List.of(), channel, region[0], region[1], owned));
                    assertTrue(channel.isOpen(), "A rejected factory has not accepted ownership");
                }
            }
        }
    }

    @Test
    void validArithmeticBoundaryCanBeDeclaredWithoutOpeningAFile(@TempDir Path directory) {
        MicrohttpResponse zero = MicrohttpResponse.withFileBody(200, "OK", List.of(),
                directory.resolve("absent"), Long.MAX_VALUE, 0L);
        assertEquals(0L, zero.bodyLength());
        MicrohttpResponse maximum = MicrohttpResponse.withFileBody(200, "OK", List.of(),
                directory.resolve("absent"), 0L, Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, maximum.bodyLength());
    }

    @Test
    void acceptedFileRegionStreamsAndRespectsChannelOwnership(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("body");
        Files.writeString(file, "abcdef");
        for (boolean owned : List.of(false, true)) {
            try (FileChannel channel = FileChannel.open(file);
                 RecordingSocketChannel socket = new RecordingSocketChannel()) {
                MicrohttpResponse response = MicrohttpResponse.withFileChannelBody(
                        200, "OK", List.of(), channel, 1L, 3L, owned);
                try (WritableSource source = response.writableSource(new byte[0])) {
                    assertEquals(2L, source.writeTo(socket, 2));
                    assertEquals(1L, source.writeTo(socket, 2));
                    assertFalse(source.hasRemaining());
                    assertEquals("bcd", socket.text());
                }
                assertEquals(!owned, channel.isOpen());
                assertTrue(socket.isOpen(), "The body source borrows the connection socket");
            }
        }
    }

    @Test
    void bodylessStatusDiscardClosesOwnedFiniteSourcesBeforeDroppingTheirFactory(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("body");
        Files.writeString(file, "x");
        for (int status : new int[]{204, 205, 304}) {
            for (boolean owned : List.of(false, true)) {
                try (FileChannel channel = FileChannel.open(file)) {
                    MicrohttpResponse original = MicrohttpResponse.withFileChannelBody(
                            status, "Status", List.of(new Header("Content-Length", "1")), channel, 0L, 1L, owned);
                    // These are the transport's two ordered steps for bodyless statuses.
                    original.closeBody(StreamTerminationReason.PROTOCOL_UNSUPPORTED, null);
                    MicrohttpResponse bodyless = original.withoutBodyOrFramingHeaders();
                    assertEquals(!owned, channel.isOpen());
                    assertEquals(0L, bodyless.bodyLength());
                    assertFalse(bodyless.hasHeader("Content-Length"));
                }
            }
        }
    }

    private static final class RecordingSocketChannel extends SocketChannel {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        RecordingSocketChannel() { super(SelectorProvider.provider()); }
        String text() { return output.toString(StandardCharsets.US_ASCII); }
        @Override public int write(ByteBuffer source) {
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            output.writeBytes(bytes);
            return bytes.length;
        }
        @Override public long write(ByteBuffer[] sources, int offset, int length) {
            long written = 0;
            for (int index = offset; index < offset + length; index++) written += write(sources[index]);
            return written;
        }
        @Override public int read(ByteBuffer target) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] targets, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public SocketChannel bind(SocketAddress address) { return this; }
        @Override public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        @Override public <T> T getOption(SocketOption<T> option) { throw new UnsupportedOperationException(); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public SocketChannel shutdownInput() { return this; }
        @Override public SocketChannel shutdownOutput() { return this; }
        @Override public Socket socket() { throw new UnsupportedOperationException(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionPending() { return false; }
        @Override public boolean connect(SocketAddress address) { return true; }
        @Override public boolean finishConnect() { return true; }
        @Override public SocketAddress getRemoteAddress() { throw new UnsupportedOperationException(); }
        @Override public SocketAddress getLocalAddress() { throw new UnsupportedOperationException(); }
        @Override protected void implCloseSelectableChannel() {}
        @Override protected void implConfigureBlocking(boolean block) {}
    }
}
