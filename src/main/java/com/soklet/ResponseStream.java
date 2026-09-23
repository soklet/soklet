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
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Response output, runtime metadata, and resource ownership for a streaming HTTP response producer.
 * <p>
 * Output and resource operations belong to the thread executing {@link StreamingResponseWriter#writeTo(ResponseStream)}.
 * After the callback returns, Soklet performs remaining normal finalization on that thread before sealing successful
 * output. Cancelation callbacks may already have closed resources opened with coordinated close-as-abort.
 * The request, deadline, idle timeout, and thread-safe cancelation token may be read from other threads.
 * Each response execution receives its own stream. Applications must not manually close owned resources or use
 * them after their owning lifetime. Duplicate active ownership of the same resource instance is rejected.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
public interface ResponseStream {
	/**
	 * The request that produced this streaming response.
	 *
	 * @return the request that produced this streaming response
	 */
	@NonNull
	Request getRequest();

	/**
	 * The thread-safe cancelation token for this streaming response.
	 *
	 * @return the cancelation token
	 */
	@NonNull
	CancelationToken getCancelationToken();

	/**
	 * The absolute deadline for this stream, if one is configured.
	 *
	 * @return the streaming deadline, or {@link Optional#empty()} if no deadline is configured
	 */
	@NonNull
	Optional<@NonNull Instant> getDeadline();

	/**
	 * The idle timeout for this stream, if one is configured.
	 *
	 * @return the streaming idle timeout, or {@link Optional#empty()} if disabled
	 */
	@NonNull
	Optional<@NonNull Duration> getIdleTimeout();

	/**
	 * Writes bytes to the response stream.
	 * <p>
	 * Implementations copy the provided bytes before this method returns.
	 *
	 * @param bytes the bytes to write
	 * @throws IOException if the bytes cannot be written
	 * @throws InterruptedException if the writer is already interrupted or is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void write(byte @NonNull [] bytes) throws IOException,
			InterruptedException;

	/**
	 * Writes a slice of a byte array, copying the bytes before this method returns.
	 * Bounds are validated before accepting bytes or draining earlier staged output.
	 *
	 * @param bytes the array containing the bytes to write
	 * @param offset the index of the first byte
	 * @param length the number of bytes to write
	 * @throws IOException if the bytes cannot be written
	 * @throws InterruptedException if the writer is already interrupted or is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 * @throws IndexOutOfBoundsException if the slice is outside the array
	 */
	void write(byte @NonNull [] bytes, @NonNull Integer offset, @NonNull Integer length) throws IOException, InterruptedException;

	/**
	 * Writes the remaining bytes of a byte buffer to the response stream without mutating the caller's buffer.
	 * <p>
	 * Implementations copy the remaining bytes before this method returns.
	 *
	 * @param byteBuffer the byte buffer to write
	 * @throws IOException if the bytes cannot be written
	 * @throws InterruptedException if the writer is already interrupted or is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException;

	/**
	 * Creates an independently closeable Java I/O view of this response stream.
	 * <p>
	 * Views share bounded scalar-write staging with this stream. Mixing views and native output preserves write order.
	 * Closing a view flushes staged bytes and closes only that view, even if flushing fails; repeated close is a no-op.
	 * Other views and native output remain usable while the response permits writes. Closing a view does not close the socket.
	 * Soklet closes owned wrappers before flushing remaining staging on successful response finalization.
	 * <p>
	 * An interrupted view operation throws {@link java.io.InterruptedIOException}, retains the original interruption as
	 * its cause, and restores the interrupt flag. Its {@code bytesTransferred} counts bytes accepted from that call,
	 * excluding bytes staged by earlier calls; acceptance is not acknowledgment by the client. An already elected
	 * cancelation is reported as {@link StreamingResponseCanceledException} instead. I/O failure or interruption during
	 * an otherwise valid output operation is terminal, including when application code catches the exception.
	 * Argument, thread, and lifetime validation failures do not invalidate otherwise usable output.
	 * <p>
	 * Writes and flushes on a closed view or outside the response lifetime throw {@link IOException}; native output
	 * outside its lifetime throws {@link IllegalStateException}. All output operations, including view close, belong
	 * to the producer thread; use from another thread throws {@link IllegalStateException}.
	 *
	 * @return a new output view
	 * @throws IllegalStateException if called outside the output lifetime or from another thread
	 */
	@NonNull
	OutputStream asOutputStream();

	/**
	 * Hints that buffered bytes should be made available to the transport promptly.
	 * <p>
	 * Calling this method is not required for correctness. Soklet flushes any remaining bytes when the stream completes successfully.
	 *
	 * @throws IOException if the stream cannot be flushed
	 * @throws InterruptedException if the writer is already interrupted or is interrupted while waiting for stream capacity
	 * @throws StreamingResponseCanceledException if the stream has been canceled
	 */
	void flush() throws IOException, InterruptedException;

	/**
	 * Is this stream currently open for writes?
	 *
	 * @return {@code true} if open
	 */
	@NonNull
	Boolean isOpen();

	/**
	 * Opens and owns a resource whose close operation can abort concurrent consumption.
	 * <p>
	 * Normal cleanup and cancelation coordinate one physical close attempt, including if close fails.
	 * The provider must support close racing use and unblocking that use; {@link AutoCloseable} alone does not
	 * establish this contract. Acquisition does not start if cancelation has already won. A resource returned
	 * after cancelation is disposed before it can be used.
	 *
	 * @param streamResourceFactory the checked resource factory
	 * @param <T> the resource type
	 * @return the resource owned by the innermost lexical lifetime, or the response lifetime
	 * @throws Exception if acquisition or disposal fails
	 */
	@NonNull
	<T extends AutoCloseable> T open(@NonNull StreamResourceFactory<? extends @NonNull T> streamResourceFactory) throws Exception;

	/**
	 * Opens and owns a resource with a separate provider-supported abort operation.
	 * <p>
	 * Abort and final close are each attempted once. Final close runs on the producer thread and waits for an
	 * already-started abort; an abort can race a close that has already started. The provider must support these
	 * interactions. Passing {@code Resource::close} as the aborter can close twice and is not equivalent to
	 * {@link #open(StreamResourceFactory)}.
	 *
	 * @param streamResourceFactory the checked resource factory
	 * @param resourceAborter the checked concurrent abort operation
	 * @param <T> the resource type
	 * @return the resource owned by the innermost lexical lifetime, or the response lifetime
	 * @throws Exception if acquisition or disposal fails
	 */
	@NonNull
	<T extends AutoCloseable> T open(@NonNull StreamResourceFactory<? extends @NonNull T> streamResourceFactory,
			@NonNull ResourceAborter<? super @NonNull T> resourceAborter) throws Exception;

	/**
	 * Transfers an existing resource for finalization on the producer thread, without concurrent close-as-abort.
	 * <p>
	 * Useful for encoders whose close writes final response bytes. During the active producer lifetime, an offered
	 * resource is disposed if cancelation has already won. Calls before production, during cleanup, after lifetime
	 * completion, or from another thread are rejected before ownership transfers; the caller remains responsible
	 * for the resource in those cases.
	 *
	 * @param resource the resource whose ownership is transferred
	 * @param <T> the resource type
	 * @return the same resource
	 * @throws Exception if disposal after cancelation fails
	 */
	@NonNull
	<T extends AutoCloseable> T own(@NonNull T resource) throws Exception;

	/**
	 * Uses a resource in a shorter lexical lifetime with coordinated close-as-abort.
	 * <p>
	 * Normal finalization closes the resource and every nested acquisition or adoption through this stream in reverse
	 * ownership order before the call returns. Cancelation callbacks have no guaranteed global abort order.
	 * The provider must support concurrent close as documented by
	 * {@link #open(StreamResourceFactory)}.
	 *
	 * @param streamResourceFactory the checked resource factory
	 * @param resourceConsumer the checked lexical body
	 * @param <T> the resource type
	 * @throws Exception if acquisition, the lexical body, or cleanup fails
	 */
	<T extends AutoCloseable> void using(@NonNull StreamResourceFactory<? extends @NonNull T> streamResourceFactory,
			@NonNull ResourceConsumer<? super @NonNull T> resourceConsumer) throws Exception;

	/**
	 * Uses a resource in a shorter lexical lifetime with separate abort and final-close operations.
	 * <p>
	 * All nested acquisitions and adoptions end with this lexical body. The provider must support the concurrency
	 * contract documented by {@link #open(StreamResourceFactory, ResourceAborter)}.
	 *
	 * @param streamResourceFactory the checked resource factory
	 * @param resourceAborter the checked concurrent abort operation
	 * @param resourceConsumer the checked lexical body
	 * @param <T> the resource type
	 * @throws Exception if acquisition, the lexical body, or cleanup fails
	 */
	<T extends AutoCloseable> void using(@NonNull StreamResourceFactory<? extends @NonNull T> streamResourceFactory,
			@NonNull ResourceAborter<? super @NonNull T> resourceAborter,
			@NonNull ResourceConsumer<? super @NonNull T> resourceConsumer) throws Exception;

	/**
	 * A provider-supported concurrent abort operation, distinct from final close.
	 * Implementations reused across responses must support concurrent invocation.
	 *
	 * @param <T> the resource type
	 */
	@ThreadSafe
	@FunctionalInterface
	interface ResourceAborter<T extends AutoCloseable> {
		/**
		 * Aborts use of the resource.
		 *
		 * @param resource the owned resource
		 * @throws Exception if abort fails
		 */
		void abort(@NonNull T resource) throws Exception;
	}

	/**
	 * A lexical resource body invoked on the response producer thread.
	 * Implementations reused across responses must support concurrent invocation of their shared state;
	 * each response still confines its resource consumption to its own producer thread.
	 *
	 * @param <T> the resource type
	 */
	@ThreadSafe
	@FunctionalInterface
	interface ResourceConsumer<T extends AutoCloseable> {
		/**
		 * Uses the resource within its lexical lifetime.
		 *
		 * @param resource the owned resource
		 * @throws Exception if the lexical body fails
		 */
		void accept(@NonNull T resource) throws Exception;
	}
}
