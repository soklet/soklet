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

import static java.util.Objects.requireNonNull;

/**
 * A {@link WritableSource} backed by a {@link FileChannel}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class FileChannelWritableSource implements WritableSource {
    private final FileChannel fileChannel;
    private final boolean closeOnComplete;
    private long position;
    private long remaining;

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
    }

    @Override
    public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
        requireNonNull(socketChannel);

        if (maxBytes <= 0 || !hasRemaining()) {
            return 0L;
        }

        long bytesToWrite = Math.min(maxBytes, remaining);
        long written = fileChannel.transferTo(position, bytesToWrite, socketChannel);
        if (written > 0) {
            position += written;
            remaining -= written;
            return written;
        }

        if (fileChannel.size() <= position) {
            throw new EOFException("File ended before the expected response body length was written.");
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
}
