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

import static java.util.Objects.requireNonNull;

/**
 * Immutable specification for one bounded, synchronous cleanup action for an
 * ingress-exclusive application resource. Returning after merely delegating
 * asynchronous work is not proof that the resource was released.
 */
@ThreadSafe
public final class ShutdownCleanup {
	@NonNull
	private final Duration timeout;
	@NonNull
	private final Action action;

	private ShutdownCleanup(@NonNull Duration timeout,
			@NonNull Action action) {
		Duration exactTimeout = requireNonNull(timeout);
		final long timeoutNanos;
		try {
			timeoutNanos = exactTimeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout exceeds signed nanoseconds",
					exception);
		}
		if (timeoutNanos <= 0L)
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout must be greater than zero");
		this.timeout = exactTimeout;
		this.action = requireNonNull(action);
	}

	/**
	 * Creates a cleanup specification from its deadline budget and synchronous
	 * action.
	 *
	 * @param timeout positive cleanup deadline budget representable as signed
	 * nanoseconds
	 * @param action synchronous cleanup action
	 * @return immutable cleanup specification
	 * @throws IllegalArgumentException if {@code timeout} is non-positive or
	 * exceeds signed-nanosecond range
	 */
	@NonNull
	public static ShutdownCleanup fromTimeoutAndAction(
			@NonNull Duration timeout, @NonNull Action action) {
		return new ShutdownCleanup(timeout, action);
	}

	/** @return positive cleanup deadline budget */
	@NonNull
	public Duration getTimeout() {
		return this.timeout;
	}

	@NonNull
	Action action() {
		return this.action;
	}

	/**
	 * One synchronous cleanup action for an ingress-exclusive application
	 * resource.
	 */
	@FunctionalInterface
	public interface Action {
		/**
		 * Performs cleanup after complete core shutdown.
		 *
		 * @param shutdownResult exact complete core shutdown result
		 * @throws Exception if cleanup fails
		 */
		void performCleanup(@NonNull ShutdownResult shutdownResult)
				throws Exception;
	}
}
