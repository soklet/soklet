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

package com.soklet;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPOutputStream;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultGzipResponseCompressionCodec implements ResponseCompressionCodec {
	@NonNull
	private static final DefaultGzipResponseCompressionCodec INSTANCE;

	static {
		INSTANCE = new DefaultGzipResponseCompressionCodec();
	}

	private DefaultGzipResponseCompressionCodec() {}

	@NonNull
	static DefaultGzipResponseCompressionCodec defaultInstance() {
		return INSTANCE;
	}

	@Override
	@NonNull
	public String getContentEncoding() {
		return "gzip";
	}

	@Override
	public byte @NonNull [] compress(@NonNull ByteBuffer uncompressedBody) {
		requireNonNull(uncompressedBody);
		ByteBuffer body = uncompressedBody.asReadOnlyBuffer();

		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.max(32, body.remaining() / 2));
			try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
				byte[] chunk = new byte[Math.min(8_192, body.remaining())];

				while (body.hasRemaining()) {
					int length = Math.min(chunk.length, body.remaining());
					body.get(chunk, 0, length);
					gzipOutputStream.write(chunk, 0, length);
				}
			}
			return outputStream.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to gzip response body.", e);
		}
	}
}
