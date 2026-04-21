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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Runtime context for a streaming response producer.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface StreamingResponseContext {
	/**
	 * The cancelation token for this streaming response.
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
	Optional<Instant> getDeadline();

	/**
	 * The idle timeout for this stream, if one is configured.
	 *
	 * @return the streaming idle timeout, or {@link Optional#empty()} if disabled
	 */
	@NonNull
	Optional<Duration> getIdleTimeout();

	/**
	 * Is the streaming response canceled?
	 *
	 * @return {@code true} if canceled
	 */
	@NonNull
	default Boolean isCanceled() {
		return getCancelationToken().isCanceled();
	}

	/**
	 * The cancelation reason, if canceled.
	 *
	 * @return the cancelation reason, or {@link Optional#empty()} if not canceled
	 */
	@NonNull
	default Optional<StreamingResponseCancelationReason> getCancelationReason() {
		return getCancelationToken().getCancelationReason();
	}

	/**
	 * The underlying cancelation cause, if available.
	 *
	 * @return the underlying cause, or {@link Optional#empty()} if none is available
	 */
	@NonNull
	default Optional<Throwable> getCancelationCause() {
		return getCancelationToken().getCancelationCause();
	}

	/**
	 * Registers a callback that runs when the stream is canceled.
	 *
	 * @param callback the callback to run on cancelation
	 * @return a handle that removes the callback when closed
	 */
	@NonNull
	default AutoCloseable onCancel(@NonNull Runnable callback) {
		return getCancelationToken().onCancel(callback);
	}

	/**
	 * Throws if the stream has been canceled.
	 *
	 * @throws StreamingResponseCanceledException if canceled
	 */
	default void throwIfCanceled() throws StreamingResponseCanceledException {
		getCancelationToken().throwIfCanceled();
	}
}
