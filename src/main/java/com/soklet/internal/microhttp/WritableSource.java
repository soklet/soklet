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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SocketChannel;

/**
 * A bounded response-body writer for the microhttp transport.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
interface WritableSource extends Closeable {
    default void start() throws IOException {
        // No-op by default
    }

    default void writeReadyCallback(Runnable callback) {
        // No-op by default
    }

    long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException;

    boolean hasRemaining();

    default boolean isReadyToWrite() {
        return hasRemaining();
    }

    default void close(StreamTerminationReason cancelationReason, Throwable cause) throws IOException {
        close();
    }
}
