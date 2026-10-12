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

import com.soklet.MarshaledResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileSourceFailureBoundaryTests {
    @Test
    void uncheckedMetadataFailuresCannotBorrowAnotherResponsesSocketProvenance(@TempDir Path directory) throws Exception {
        IOException foreign = new SocketChannelIo.SocketIoException(new IOException("A previous response's socket failed"));
        for (RuntimeException original : List.of(new UncheckedIOException("Metadata read failed", foreign),
                new IllegalStateException("Metadata read failed", foreign)))
            assertMetadataFailureRemainsDiagnostic(directory, original);
    }

    @Test
    void checkedMetadataFailuresRetainTheSameSourceBoundary(@TempDir Path directory) throws Exception {
        IOException foreign = new SocketChannelIo.SocketIoException(new IOException("A previous response's socket failed"));
        assertMetadataFailureRemainsDiagnostic(directory, new IOException("Metadata read failed", foreign));
    }

    private static void assertMetadataFailureRemainsDiagnostic(Path directory, Exception original) throws Exception {
        Path file = directory.resolve("source");
        Files.writeString(file, "x");
        try (MetadataFailureChannel channel = new MetadataFailureChannel(FileChannel.open(file), original);
             RecordingSocketChannel socket = new RecordingSocketChannel()) {
            // The public descriptor is initially valid; metadata fails later during transport.
            MarshaledResponse accepted = MarshaledResponse.withStatusCode(200).body(channel, 0L, 1L, false).build();
            assertEquals(1L, accepted.getBodyLength());
            FileChannelWritableSource source = new FileChannelWritableSource(channel, 0L, 1L, false);
            ResponseBodySourceException failure = assertThrows(ResponseBodySourceException.class,
                    () -> source.writeTo(socket, 1L));
            assertSame(original, failure.getCause());
            Method classifier = ConnectionEventLoop.class.getDeclaredMethod("isRemoteClose", Throwable.class);
            classifier.setAccessible(true);
            assertEquals(false, classifier.invoke(null, failure));
            assertEquals(0, socket.writes, "This connection had no socket failure to establish peer provenance");
            source.close();
            assertTrue(channel.isOpen(), "The transport borrows this caller-owned file channel");
        }
    }

    private static final class MetadataFailureChannel extends FileChannel {
        private final FileChannel delegate;
        private final Exception metadataFailure;
        private boolean transferAttempted;
        MetadataFailureChannel(FileChannel delegate, Exception metadataFailure) {
            this.delegate = delegate;
            this.metadataFailure = metadataFailure;
        }
        @Override public long transferTo(long position, long count, WritableByteChannel target) {
            transferAttempted = true;
            return 0L;
        }
        @Override public long size() throws IOException {
            if (!transferAttempted)
                return delegate.size();
            if (metadataFailure instanceof IOException checked)
                throw checked;
            throw (RuntimeException) metadataFailure;
        }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
        @Override public int read(ByteBuffer buffer) throws IOException { return delegate.read(buffer); }
        @Override public long read(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.read(buffers, offset, length); }
        @Override public int read(ByteBuffer buffer, long position) throws IOException { return delegate.read(buffer, position); }
        @Override public int write(ByteBuffer buffer) throws IOException { return delegate.write(buffer); }
        @Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException { return delegate.write(buffers, offset, length); }
        @Override public int write(ByteBuffer buffer, long position) throws IOException { return delegate.write(buffer, position); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
        @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
        @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
    }

    private static final class RecordingSocketChannel extends SocketChannel {
        int writes;
        RecordingSocketChannel() { super(SelectorProvider.provider()); }
        @Override public int write(ByteBuffer source) {
            writes++;
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }
        @Override public long write(ByteBuffer[] sources, int offset, int length) {
            long count = 0;
            for (int index = offset; index < offset + length; index++) count += write(sources[index]);
            return count;
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
