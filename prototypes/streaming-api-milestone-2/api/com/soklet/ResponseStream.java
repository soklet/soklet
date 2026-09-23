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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * HTTP producer output, metadata, and ownership. Output and resource operations
 * belong to the producer thread; metadata and token queries remain independently
 * readable. Applications must not manually close owned resources or use them
 * after their owning lifetime. Active ownership is unique by resource identity.
 * This interface is not AutoCloseable and has no public child-scope object.
 */
@NotThreadSafe
public interface ResponseStream {
	void write(byte @NonNull [] bytes) throws IOException, InterruptedException;
	void write(byte @NonNull [] bytes, @NonNull Integer offset, @NonNull Integer length)
			throws IOException, InterruptedException;
	void write(@NonNull ByteBuffer byteBuffer) throws IOException, InterruptedException;
	void writeUtf8(@NonNull String string) throws IOException, InterruptedException;
	void flush() throws IOException, InterruptedException;
	/** Closing a view flushes and closes that view; it neither closes nor seals the response. */
	@NonNull OutputStream asOutputStream();
	@NonNull Boolean isOpen();
	@NonNull Request getRequest();
	@NonNull CancelationToken getCancelationToken();
	@NonNull Optional<@NonNull Instant> getDeadline();
	@NonNull Optional<@NonNull Duration> getIdleTimeout();

	/**
	 * Acquires a resource whose provider supports close racing consumption.
	 * Normal finalization and cancelation share one physical close attempt;
	 * failed close is not retried. AutoCloseable alone does not establish safety.
	 * @param streamResourceFactory checked acquisition, suppressed before entry if canceled
	 * @param <T> resource type
	 * @return owned resource in the innermost lexical lifetime
	 * @throws Exception if acquisition or disposal of a late resource fails
	 */
	@NonNull <T extends AutoCloseable> T open(
			@NonNull StreamResourceFactory<? extends T> streamResourceFactory) throws Exception;

	/**
	 * Acquires a resource with a separate concurrent abort operation. Abort is
	 * attempted once; final close is separately attempted once on the producer.
	 * Providers must support abort racing use and an already-started final close.
	 * Passing Resource::close as the aborter can call close twice and is not the
	 * coordinated close-on-cancel operation.
	 * @param streamResourceFactory checked resource acquisition
	 * @param resourceAborter checked, provider-supported concurrent abort action
	 * @param <T> resource type
	 * @return owned resource in the innermost lexical lifetime
	 * @throws Exception if acquisition or disposal of a late resource fails
	 */
	@NonNull <T extends AutoCloseable> T open(
			@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceAborter<? super T> resourceAborter) throws Exception;

	/**
	 * Owns an already-created resource for producer-thread finalization only.
	 * Does not use close as a concurrent abort. Nested inside using, ownership
	 * ends with that lexical block; otherwise it ends at root finalization.
	 * @param resource resource whose lifetime is transferred to the stream
	 * @param <T> resource type
	 * @return the same resource
	 * @throws Exception if disposal after a completed lifetime fails
	 */
	@NonNull <T extends AutoCloseable> T own(@NonNull T resource) throws Exception;

	/**
	 * Runs a shorter lifetime with explicit coordinated close-on-cancel ownership.
	 * The supplied resource and all other resources acquired/owned inside the
	 * consumer are finalized in reverse ownership order before this call returns.
	 * The provider must support concurrent close, just as for the one-argument open overload.
	 * @param streamResourceFactory checked resource acquisition
	 * @param resourceConsumer checked lexical body
	 * @param <T> resource type
	 * @throws Exception if acquisition, the body, or finalization fails
	 */
	<T extends AutoCloseable> void using(
			@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceConsumer<? super T> resourceConsumer) throws Exception;

	/**
	 * Runs a shorter lifetime with separate abort and final close. All nested
	 * acquisitions belong to this lexical block, including own(resource).
	 * Resource::close as the aborter is not coordinated close-on-cancel.
	 * @param streamResourceFactory checked resource acquisition
	 * @param resourceAborter provider-supported concurrent abort action
	 * @param resourceConsumer checked lexical body
	 * @param <T> resource type
	 * @throws Exception if acquisition, the body, or finalization fails
	 */
	<T extends AutoCloseable> void using(
			@NonNull StreamResourceFactory<? extends T> streamResourceFactory,
			@NonNull ResourceAborter<? super T> resourceAborter,
			@NonNull ResourceConsumer<? super T> resourceConsumer) throws Exception;

	/** Checked provider abort; invocation/threading is governed by the ownership operation. */
	@FunctionalInterface
	interface ResourceAborter<T extends AutoCloseable> {
		void abort(@NonNull T resource) throws Exception;
	}

	/** Checked lexical body, invoked on the HTTP producer thread. */
	@FunctionalInterface
	interface ResourceConsumer<T extends AutoCloseable> {
		void accept(@NonNull T resource) throws Exception;
	}
}
