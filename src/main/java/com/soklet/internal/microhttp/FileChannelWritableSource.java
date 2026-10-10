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

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;
import static com.soklet.internal.ObjectIdentity.sameInstance;

/**
 * A {@link WritableSource} backed by a {@link FileChannel}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class FileChannelWritableSource implements WritableSource {
    private static final int MAX_SOCKET_CAUSE_DEPTH = 16;
    private final FileChannel fileChannel;
    private final boolean closeOnComplete;
    private final boolean nativeTransfer;
    private long position;
    private long remaining;
    private @Nullable SocketSink socketSink;

    FileChannelWritableSource(FileChannel fileChannel, long offset, long count, boolean closeOnComplete) {
        this.fileChannel = requireNonNull(fileChannel);
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be >= 0.");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Count must be >= 0.");
        }
        if (Long.MAX_VALUE - offset < count) {
            throw new IllegalArgumentException("Offset plus count exceeds maximum supported file position.");
        }

        this.position = offset;
        this.remaining = count;
        this.closeOnComplete = closeOnComplete;
        Class<?> channelType = fileChannel.getClass();
        this.nativeTransfer = "sun.nio.ch.FileChannelImpl".equals(channelType.getName())
                && sameInstance(channelType.getModule(), FileChannel.class.getModule());
    }

    @Override
    public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
        requireNonNull(socketChannel);

        if (maxBytes <= 0 || !hasRemaining()) {
            return 0L;
        }

        long bytesToWrite = Math.min(maxBytes, remaining);
        if (!fileChannel.isOpen())
            throw new ResponseBodySourceException("Response file channel is closed", new ClosedChannelException());
        long written;
        try {
            if (nativeTransfer) {
                // Keep the real socket target so the JDK can use native file transfer.
                written = fileChannel.transferTo(position, bytesToWrite, socketChannel);
            } else {
                if (socketSink == null || !sameInstance(socketSink.channel, socketChannel))
                    socketSink = new SocketSink(socketChannel);
                // Custom channels may throw after partial progress. Keep their
                // sink failures typed at the original write boundary, without
                // any later write whose response offset would be uncertain.
                written = fileChannel.transferTo(position, bytesToWrite, socketSink);
            }
        } catch (IOException failure) {
            if (!nativeTransfer) {
                SocketChannelIo.@Nullable SocketIoException socketFailure = socketIoCause(failure);
                if (socketFailure != null)
                    throw socketFailure;
                throw new ResponseBodySourceException(fileChannel.isOpen()
                        ? "Unable to transfer the response file"
                        : "Response file channel closed during delivery", failure);
            }
            throw classifyTransferFailure(socketChannel, failure);
        }
        if (written > 0) {
            position += written;
            remaining -= written;
            return written;
        }

        try {
            if (fileChannel.size() <= position)
                throw new ResponseBodySourceException("File ended before the expected response body length was written",
                        new EOFException("Response file was truncated"));
        } catch (ResponseBodySourceException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ResponseBodySourceException("Unable to inspect the response file", failure);
        }

        return 0L;
    }

    @Override
    public boolean hasRemaining() {
        return remaining > 0;
    }

    @Override
    public void close() throws IOException {
        if (closeOnComplete) {
            fileChannel.close();
        }
    }

    private static SocketChannelIo.@Nullable SocketIoException socketIoCause(Throwable failure) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_SOCKET_CAUSE_DEPTH; depth++) {
            if (visited.put(current, Boolean.TRUE) != null)
                return null;
            // An independently identified source failure remains diagnostic,
            // even if its application-supplied cause mentions a socket failure.
            if (current instanceof ResponseBodySourceException)
                return null;
            if (current instanceof SocketChannelIo.SocketIoException)
                return (SocketChannelIo.SocketIoException) current;
            current = current.getCause();
        }
        return null;
    }

    private IOException classifyTransferFailure(SocketChannel socketChannel, IOException failure) {
        if (!fileChannel.isOpen())
            return new ResponseBodySourceException("Response file channel closed during delivery", failure);

        ResponseBodySourceException sourceFailure =
                new ResponseBodySourceException("Unable to transfer the response file", failure);
        ByteBuffer probe = ByteBuffer.allocate(1);
        try {
            if (fileChannel.read(probe, position) <= 0)
                return sourceFailure;
        } catch (IOException probeFailure) {
            sourceFailure.addSuppressed(probeFailure);
            return sourceFailure;
        }

        // A readable byte does not prove that the native failure came from the
        // socket. Only an independently failing typed socket write establishes
        // peer failure. A successful or zero-byte write leaves the original
        // native failure visible as a response-source error.
        //
        // The JDK implementation returns a byte count instead of throwing after
        // partial progress while the source remains open. Custom FileChannels
        // do not have that guarantee and never reach this failure-only probe.
        // The probe writes at most one byte; delivery always stops afterwards.
        probe.flip();
        try {
            SocketChannelIo.write(socketChannel, probe);
        } catch (IOException probeFailure) {
            if (!(probeFailure instanceof SocketChannelIo.SocketIoException)) {
                sourceFailure.addSuppressed(probeFailure);
                return sourceFailure;
            }
            SocketChannelIo.SocketIoException socketFailure = new SocketChannelIo.SocketIoException(failure);
            socketFailure.addSuppressed(probeFailure);
            return socketFailure;
        }
        return sourceFailure;
    }

    private static final class SocketSink implements WritableByteChannel {
        private final SocketChannel channel;
        private SocketSink(SocketChannel channel) { this.channel = channel; }
        @Override public int write(ByteBuffer buffer) throws IOException {
            return SocketChannelIo.write(channel, buffer);
        }
        // The adapter borrows the socket. Checking its state here could bypass
        // the typed write boundary; ownership remains with the connection.
        @Override public boolean isOpen() { return true; }
        @Override public void close() { /* The connection owns the borrowed socket. */ }
    }
}
