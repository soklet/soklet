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
import java.util.Optional;

/**
 * Thread-safe cancelation signal for response stream producers.
 * <p>
 * Producers should check this token between expensive or blocking operations and stop producing when it becomes
 * canceled. Soklet cancels the token when a streaming response can no longer continue, such as when the client
 * disconnects, the server shuts down, the request HTTP version cannot support streaming, or a streaming timeout is
 * reached.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface CancelationToken {
	/**
	 * Is the associated operation canceled?
	 *
	 * @return {@code true} if canceled
	 */
	@NonNull
	Boolean isCanceled();

	/**
	 * The cancelation reason, if cancelation has occurred.
	 *
	 * @return the cancelation reason, or {@link Optional#empty()} if not canceled
	 */
	@NonNull
	Optional<StreamTerminationReason> getCancelationReason();

	/**
	 * The underlying cancelation cause, if available.
	 *
	 * @return the underlying cause, or {@link Optional#empty()} if no cause is available
	 */
	@NonNull
	Optional<Throwable> getCancelationCause();

	/**
	 * Registers a callback that runs when the token is canceled.
	 * <p>
	 * The returned handle removes the callback when closed. If the token is already canceled, the callback may run
	 * before this method returns.
	 * <p>
	 * Callbacks run synchronously on the thread that performs cancelation. Keep callbacks fast and non-blocking; if
	 * cleanup may take meaningful time, dispatch it to an application-owned executor from the callback.
	 *
	 * @param callback the callback to run on cancelation
	 * @return a handle that removes the callback when closed
	 */
	@NonNull
	AutoCloseable onCancel(@NonNull Runnable callback);

	/**
	 * Throws if the token has been canceled.
	 *
	 * @throws StreamingResponseCanceledException if canceled
	 */
	default void throwIfCanceled() throws StreamingResponseCanceledException {
		StreamTerminationReason reason = getCancelationReason().orElse(null);

		if (reason != null)
			throw new StreamingResponseCanceledException(reason, getCancelationCause().orElse(null));
	}
}
