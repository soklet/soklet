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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static java.util.Objects.requireNonNull;

/**
 * A {@link WritableSource} backed by a {@link ByteBuffer}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class ByteBufferWritableSource implements WritableSource {
    private final ByteBuffer buffer;

    ByteBufferWritableSource(ByteBuffer buffer) {
        this.buffer = requireNonNull(buffer).slice();
    }

    @Override
    public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
        requireNonNull(socketChannel);

        if (maxBytes <= 0 || !hasRemaining()) {
            return 0L;
        }

        int originalLimit = buffer.limit();
        int maxBytesThisWrite = (int) Math.min(maxBytes, (long) buffer.remaining());
        buffer.limit(buffer.position() + maxBytesThisWrite);
        try {
            return socketChannel.write(buffer);
        } finally {
            buffer.limit(originalLimit);
        }
    }

    @Override
    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    @Override
    public void close() {
        // nothing to close
    }
}
