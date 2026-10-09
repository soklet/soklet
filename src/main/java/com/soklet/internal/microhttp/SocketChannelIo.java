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
package com.soklet.internal.microhttp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/** Identifies connection IO failures at the socket boundary, independently of OS message text. */
final class SocketChannelIo {
    private SocketChannelIo() { }

    static int read(SocketChannel channel, ByteBuffer buffer) throws IOException {
        try {
            return channel.read(buffer);
        } catch (IOException failure) {
            throw new SocketIoException(failure);
        }
    }

    static int write(SocketChannel channel, ByteBuffer buffer) throws IOException {
        try {
            return channel.write(buffer);
        } catch (IOException failure) {
            throw new SocketIoException(failure);
        }
    }

    static final class SocketIoException extends IOException {
        SocketIoException(IOException cause) {
            super("The connection could not complete socket IO", cause);
        }
    }
}
