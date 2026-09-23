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
 * Thread-safe cooperative cancelation signal for response producers and
 * request handlers.
 * <p>
 * Producers and handlers should check this token between expensive or blocking
 * operations and stop work when it becomes canceled. Soklet exposes it through
 * {@link ResponseStream#getCancelationToken()} and through
 * {@link McpInvocationFeatures#getCancelationToken()} for selected MCP
 * application handlers. Soklet cancels the token when the associated response
 * can no longer continue, such as when the client disconnects, forced shutdown
 * begins after the graceful-drain budget, the request HTTP version cannot
 * support streaming, or a response deadline or streaming timeout is reached.
 * Graceful shutdown by itself does not cancel already-admitted finite MCP work;
 * its unary or request-scoped progress response may finish within that budget.
 *
 * <p>Normal completion does not mark the token canceled. Tokens
 * release registered callbacks when the associated operation completes normally,
 * and callbacks registered afterward are inert.
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
	Optional<@NonNull StreamTerminationReason> getCancelationReason();

	/**
	 * The underlying cancelation cause, if available.
	 *
	 * @return the underlying cause, or {@link Optional#empty()} if no cause is available
	 */
	@NonNull
	Optional<@NonNull Throwable> getCancelationCause();

	/**
	 * Registers a callback that runs when the token is canceled.
	 * <p>
	 * Each registration is independent, including registrations of the same callback object. Closing the returned
	 * handle suppresses invocation if removal wins before callback claim; it does not wait for a claimed callback.
	 * If the token is already canceled, the callback may run before this method returns. If the associated operation
	 * already completed normally, the callback does not run and its reference is not retained.
	 * <p>
	 * Callback dispatch is selected by the runtime. HTTP streaming uses managed callback execution; other runtimes
	 * may defer delivery until their operation releases callbacks. Late delivery may run inline on the registering
	 * application thread. Keep callbacks fast and non-blocking. Each callback is invoked at most once; failures
	 * are isolated, and independent callbacks have no ordering guarantee.
	 *
	 * @param callback the callback to run on cancelation
	 * @return a handle that removes the callback when closed
	 */
	@NonNull
	CallbackRegistration onCancel(@NonNull Runnable callback);

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
