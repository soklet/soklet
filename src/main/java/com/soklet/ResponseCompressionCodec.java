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
import java.nio.ByteBuffer;

/**
 * A response-compression implementation and its HTTP content coding.
 * <p>
 * Soklet supplies {@link #gzipInstance()} using the JDK's gzip implementation without runtime
 * dependencies. Applications can supply other codecs, including codecs backed by optional external
 * libraries, without adding those libraries to Soklet itself. A codec is selected by a
 * {@link ResponseCompressionPlan}; it does not decide request eligibility, negotiate with a client,
 * or write HTTP headers.
 * <p>
 * Implementations must be thread-safe. Soklet can reuse one codec concurrently for many responses,
 * and does not synchronize access to it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface ResponseCompressionCodec {
	/**
	 * Acquires the shared built-in {@code gzip} codec.
	 * <p>
	 * The codec uses the JDK's default gzip compression settings and does not cache results.
	 *
	 * @return a thread-safe gzip codec
	 */
	@NonNull
	static ResponseCompressionCodec gzipInstance() {
		return DefaultGzipResponseCompressionCodec.defaultInstance();
	}

	/**
	 * The single HTTP content coding produced by this codec, such as {@code gzip} or {@code br}.
	 * <p>
	 * Return a nonempty ASCII HTTP token without whitespace, parameters, or a coding chain. The reserved
	 * values {@code identity} and {@code *} are not compression codecs. A plan validates this value and
	 * snapshots its lowercase form at construction; the codec must continue to produce that coding for
	 * the lifetime of every plan using it.
	 *
	 * @return the non-null content coding
	 */
	@NonNull
	String getContentEncoding();

	/**
	 * Compresses the bytes between the supplied buffer's position and limit.
	 * <p>
	 * Soklet supplies a read-only buffer view confined to this invocation. An implementation may advance
	 * that view's position but must not retain the view, access it after returning, or mutate the underlying
	 * response bytes. Compression must complete synchronously on the invoking request-handling thread.
	 * <p>
	 * Return a non-null complete encoding of exactly the supplied bytes, using this codec's content coding.
	 * The returned array is not defensively copied. Neither the codec nor any caller may mutate it while a
	 * response is writing it or a cache retains it for reuse. A null result or an exception is a
	 * response-processing failure; Soklet does not silently send partially encoded or uncompressed bytes.
	 *
	 * @param uncompressedBody the read-only view of the finalized uncompressed body
	 * @return the non-null compressed bytes
	 */
	byte @NonNull [] compress(@NonNull ByteBuffer uncompressedBody);
}
