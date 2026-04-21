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

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Writable byte sink for a streaming HTTP response.
 * <p>
 * This type is intentionally not thread-safe. A stream must have one active writer at a time; applications that
 * produce bytes from multiple threads should serialize those writes before calling this API.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public interface ResponseStream {
	/**
	 * Writes bytes to the response stream.
	 * <p>
	 * Implementations copy the provided bytes before this method returns.
	 *
	 * @param bytes the bytes to write
	 * @throws IOException if the bytes cannot be written
	 * @throws InterruptedException if the writer is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void write(@NonNull byte[] bytes) throws IOException, InterruptedException, StreamingResponseCanceledException;

	/**
	 * Writes the remaining bytes of a byte buffer to the response stream without mutating the caller's buffer.
	 * <p>
	 * Implementations copy the remaining bytes before this method returns.
	 *
	 * @param byteBuffer the byte buffer to write
	 * @throws IOException if the bytes cannot be written
	 * @throws InterruptedException if the writer is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException, StreamingResponseCanceledException;

	/**
	 * Hints that buffered bytes should be made available to the transport promptly.
	 * <p>
	 * Calling this method is not required for correctness. Soklet flushes any remaining bytes when the stream completes.
	 *
	 * @throws IOException if the stream cannot be flushed
	 * @throws InterruptedException if the writer is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void flush() throws IOException, InterruptedException, StreamingResponseCanceledException;

	/**
	 * Is this stream currently open for writes?
	 *
	 * @return {@code true} if open
	 */
	@NonNull
	Boolean isOpen();
}
