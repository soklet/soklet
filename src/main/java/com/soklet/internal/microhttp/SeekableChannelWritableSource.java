/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.SocketChannel;

import static java.util.Objects.requireNonNull;

/** Buffered adapter for secure-directory channels that are not FileChannels. */
final class SeekableChannelWritableSource implements WritableSource {
	private final SeekableByteChannel channel;
	private final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
	private long remaining;

	SeekableChannelWritableSource(SeekableByteChannel channel, long offset, long count) throws IOException {
		this.channel = requireNonNull(channel);
		this.remaining = count;
		buffer.limit(0);
		channel.position(offset);
	}

	@Override
	public long writeTo(SocketChannel socketChannel, long maxBytes) throws IOException {
		if (maxBytes <= 0 || !hasRemaining())
			return 0;
		if (!buffer.hasRemaining()) {
			buffer.clear();
			buffer.limit((int) Math.min(buffer.capacity(), remaining));
			int read;
			try {
				read = channel.read(buffer);
			} catch (IOException | RuntimeException failure) {
				throw new ResponseBodySourceException("Unable to read the response file", failure);
			}
			if (read < 0)
				throw new ResponseBodySourceException("File ended before the expected response body length was written",
						new EOFException("Response file was truncated"));
			if (read == 0)
				return 0;
			buffer.flip();
		}
		int originalLimit = buffer.limit();
		buffer.limit(buffer.position() + (int) Math.min(maxBytes, buffer.remaining()));
		try {
			int written = SocketChannelIo.write(socketChannel, buffer);
			remaining -= written;
			return written;
		} finally {
			buffer.limit(originalLimit);
		}
	}

	@Override
	public boolean hasRemaining() {
		return remaining > 0;
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
