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

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;

import static java.util.Objects.requireNonNull;

/**
 * A {@link WritableSource} composed of sequential child sources.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
class CompositeWritableSource implements WritableSource {
    private final Queue<WritableSource> sources;

    CompositeWritableSource(Collection<? extends WritableSource> sources) {
        requireNonNull(sources);
        this.sources = new ArrayDeque<>(sources.size());
        for (WritableSource source : sources) {
            this.sources.add(requireNonNull(source));
        }
    }

    @Override
    public void start() throws IOException {
        for (WritableSource source : sources) {
            source.start();
        }
    }

    @Override
    public void writeReadyCallback(Runnable callback) {
        for (WritableSource source : sources) {
            source.writeReadyCallback(callback);
        }
    }

    @Override
    public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
        requireNonNull(socketChannel);

        if (maxBytes <= 0) {
            return 0L;
        }

        long totalWritten = 0L;
        while (totalWritten < maxBytes && !sources.isEmpty()) {
            WritableSource source = sources.peek();
            long written = source.writeTo(socketChannel, maxBytes - totalWritten);
            totalWritten += written;

            if (!source.hasRemaining()) {
                sources.remove();
                source.close();
                if (written == 0) {
                    continue;
                }
            }

            if (written == 0) {
                break;
            }
        }
        return totalWritten;
    }

    @Override
    public boolean hasRemaining() {
        for (WritableSource source : sources) {
            if (source.hasRemaining()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isReadyToWrite() {
        for (WritableSource source : sources) {
            if (source.hasRemaining()) {
                return source.isReadyToWrite();
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        close(null, null);
    }

    @Override
    public void close(StreamTerminationReason cancelationReason, Throwable cause) throws IOException {
        IOException firstException = null;
        while (!sources.isEmpty()) {
            try {
                WritableSource source = sources.remove();
                if (cancelationReason == null) {
                    source.close();
                } else {
                    source.close(cancelationReason, cause);
                }
            } catch (IOException e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }

        if (firstException != null) {
            throw firstException;
        }
    }
}
