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
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Immutable startup and shutdown deadline policy shared by every configured
 * lifecycle component.
 */
@ThreadSafe
public final class LifecyclePolicy {
	@NonNull
	private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);
	@NonNull
	private static final Duration DEFAULT_STARTUP_CANCELATION_TIMEOUT =
			Duration.ofSeconds(2);
	@NonNull
	private static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT =
			Duration.ofSeconds(15);
	@NonNull
	private static final Duration DEFAULT_FORCED_SHUTDOWN_TIMEOUT =
			Duration.ofSeconds(3);

	@NonNull
	private final Duration startupTimeout;
	@NonNull
	private final Duration startupCancelationTimeout;
	@NonNull
	private final Duration gracefulShutdownTimeout;
	@NonNull
	private final Duration forcedShutdownTimeout;

	private LifecyclePolicy(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.startupTimeout = validate(exactBuilder.startupTimeout,
				"startupTimeout");
		this.startupCancelationTimeout = validate(
				exactBuilder.startupCancelationTimeout,
				"startupCancelationTimeout");
		this.gracefulShutdownTimeout = validate(
				exactBuilder.gracefulShutdownTimeout,
				"gracefulShutdownTimeout");
		this.forcedShutdownTimeout = validate(
				exactBuilder.forcedShutdownTimeout,
				"forcedShutdownTimeout");
	}

	/** @return the default lifecycle policy */
	@NonNull
	public static LifecyclePolicy fromDefaults() {
		return builder().build();
	}

	/** @return a mutable builder initialized with the default policy */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/** @return startup timeout */
	@NonNull
	public Duration getStartupTimeout() {
		return this.startupTimeout;
	}

	/** @return budget for canceling an in-flight startup call */
	@NonNull
	public Duration getStartupCancelationTimeout() {
		return this.startupCancelationTimeout;
	}

	/** @return graceful shutdown timeout */
	@NonNull
	public Duration getGracefulShutdownTimeout() {
		return this.gracefulShutdownTimeout;
	}

	/** @return forced shutdown timeout */
	@NonNull
	public Duration getForcedShutdownTimeout() {
		return this.forcedShutdownTimeout;
	}

	@NonNull
	InternalLifecyclePolicy toInternal() {
		return new InternalLifecyclePolicy(getStartupTimeout(),
				getStartupCancelationTimeout(), getGracefulShutdownTimeout(),
				getForcedShutdownTimeout());
	}

	@NonNull
	static LifecyclePolicy fromInternal(
			@NonNull InternalLifecyclePolicy lifecyclePolicy) {
		requireNonNull(lifecyclePolicy);
		return builder()
				.startupTimeout(lifecyclePolicy.startupTimeout())
				.startupCancelationTimeout(
						lifecyclePolicy.startupCancelationTimeout())
				.gracefulShutdownTimeout(
						lifecyclePolicy.gracefulShutdownTimeout())
				.forcedShutdownTimeout(
						lifecyclePolicy.forcedShutdownTimeout())
				.build();
	}

	@NonNull
	private static Duration validate(@NonNull Duration duration,
			@NonNull String name) {
		requireNonNull(duration, requireNonNull(name));
		if (duration.isNegative())
			throw new IllegalArgumentException(name + " must be >= 0");
		try {
			long validatedNanoseconds = duration.toNanos();
			if (validatedNanoseconds < 0L)
				throw new IllegalArgumentException(name + " must be >= 0");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					name + " exceeds signed nanoseconds", exception);
		}
		return duration;
	}

	/** Mutable builder for an immutable {@link LifecyclePolicy}. */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Duration startupTimeout;
		@NonNull
		private Duration startupCancelationTimeout;
		@NonNull
		private Duration gracefulShutdownTimeout;
		@NonNull
		private Duration forcedShutdownTimeout;

		private Builder() {
			this.startupTimeout = DEFAULT_STARTUP_TIMEOUT;
			this.startupCancelationTimeout =
					DEFAULT_STARTUP_CANCELATION_TIMEOUT;
			this.gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
			this.forcedShutdownTimeout = DEFAULT_FORCED_SHUTDOWN_TIMEOUT;
		}

		/**
		 * Sets the startup timeout. Zero means an immediate boundary.
		 *
		 * Passing {@code null} restores the built-in default.
		 *
		 * @param startupTimeout startup timeout, or {@code null} to use the default
		 * @return this builder
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder startupTimeout(@Nullable Duration startupTimeout) {
			this.startupTimeout = startupTimeout == null
					? DEFAULT_STARTUP_TIMEOUT
					: validate(startupTimeout, "startupTimeout");
			return this;
		}

		/**
		 * Sets the startup-cancelation budget.
		 *
		 * Passing {@code null} restores the built-in default.
		 *
		 * @param startupCancelationTimeout cancelation budget, or {@code null}
		 * to use the default
		 * @return this builder
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder startupCancelationTimeout(
				@Nullable Duration startupCancelationTimeout) {
			this.startupCancelationTimeout = startupCancelationTimeout == null
					? DEFAULT_STARTUP_CANCELATION_TIMEOUT
					: validate(startupCancelationTimeout,
							"startupCancelationTimeout");
			return this;
		}

		/**
		 * Sets the graceful shutdown timeout. Passing {@code null} restores the
		 * built-in default.
		 *
		 * @param gracefulShutdownTimeout graceful shutdown timeout, or
		 * {@code null} to use the default
		 * @return this builder
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder gracefulShutdownTimeout(
				@Nullable Duration gracefulShutdownTimeout) {
			this.gracefulShutdownTimeout = gracefulShutdownTimeout == null
					? DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT
					: validate(gracefulShutdownTimeout,
							"gracefulShutdownTimeout");
			return this;
		}

		/**
		 * Sets the forced shutdown timeout. Passing {@code null} restores the
		 * built-in default.
		 *
		 * @param forcedShutdownTimeout forced shutdown timeout, or {@code null}
		 * to use the default
		 * @return this builder
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder forcedShutdownTimeout(
				@Nullable Duration forcedShutdownTimeout) {
			this.forcedShutdownTimeout = forcedShutdownTimeout == null
					? DEFAULT_FORCED_SHUTDOWN_TIMEOUT
					: validate(forcedShutdownTimeout, "forcedShutdownTimeout");
			return this;
		}

		/** @return immutable lifecycle policy */
		@NonNull
		public LifecyclePolicy build() {
			return new LifecyclePolicy(this);
		}
	}
}
