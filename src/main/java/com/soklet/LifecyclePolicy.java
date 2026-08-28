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
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable startup and shutdown deadline policy shared by every configured
 * lifecycle participant.
 */
@ThreadSafe
public final class LifecyclePolicy {
	@Nullable
	private final Duration startupTimeout;
	@NonNull
	private final Duration startupCancellationTimeout;
	@NonNull
	private final Duration gracefulShutdownDuration;
	@NonNull
	private final Duration forcedShutdownDuration;

	private LifecyclePolicy(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.startupTimeout = exactBuilder.startupTimeout;
		if (this.startupTimeout != null)
			validate(this.startupTimeout, "startupTimeout");
		this.startupCancellationTimeout = validate(
				exactBuilder.startupCancellationTimeout,
				"startupCancellationTimeout");
		this.gracefulShutdownDuration = validate(
				exactBuilder.gracefulShutdownDuration,
				"gracefulShutdownDuration");
		this.forcedShutdownDuration = validate(
				exactBuilder.forcedShutdownDuration,
				"forcedShutdownDuration");
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

	/** @return startup timeout, or empty when startup is unbounded */
	@NonNull
	public Optional<@NonNull Duration> getStartupTimeout() {
		return Optional.ofNullable(this.startupTimeout);
	}

	/** @return budget for cancelling an in-flight startup call */
	@NonNull
	public Duration getStartupCancellationTimeout() {
		return this.startupCancellationTimeout;
	}

	/** @return graceful shutdown phase duration */
	@NonNull
	public Duration getGracefulShutdownDuration() {
		return this.gracefulShutdownDuration;
	}

	/** @return forced shutdown phase duration */
	@NonNull
	public Duration getForcedShutdownDuration() {
		return this.forcedShutdownDuration;
	}

	@NonNull
	InternalLifecyclePolicy toInternal() {
		return new InternalLifecyclePolicy(getStartupTimeout(),
				getStartupCancellationTimeout(), getGracefulShutdownDuration(),
				getForcedShutdownDuration());
	}

	@NonNull
	static LifecyclePolicy fromInternal(
			@NonNull InternalLifecyclePolicy lifecyclePolicy) {
		requireNonNull(lifecyclePolicy);
		Builder builder = builder()
				.startupCancellationTimeout(
						lifecyclePolicy.startupCancellationTimeout())
				.gracefulShutdownDuration(
						lifecyclePolicy.gracefulShutdownTimeout())
				.forcedShutdownDuration(
						lifecyclePolicy.forcedShutdownTimeout());
		lifecyclePolicy.startupTimeout().ifPresentOrElse(
				builder::startupTimeout, builder::noStartupTimeout);
		return builder.build();
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
		@Nullable
		private Duration startupTimeout;
		@NonNull
		private Duration startupCancellationTimeout;
		@NonNull
		private Duration gracefulShutdownDuration;
		@NonNull
		private Duration forcedShutdownDuration;

		private Builder() {
			this.startupTimeout = Duration.ofSeconds(30);
			this.startupCancellationTimeout = Duration.ofSeconds(2);
			this.gracefulShutdownDuration = Duration.ofSeconds(15);
			this.forcedShutdownDuration = Duration.ofSeconds(3);
		}

		/**
		 * Sets the startup timeout. Zero means an immediate boundary.
		 *
		 * @param duration startup timeout
		 * @return this builder
		 * @throws NullPointerException if {@code duration} is null
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder startupTimeout(@NonNull Duration duration) {
			this.startupTimeout = validate(requireNonNull(duration),
					"startupTimeout");
			return this;
		}

		/**
		 * Removes the startup timeout.
		 *
		 * @return this builder
		 */
		@NonNull
		public Builder noStartupTimeout() {
			this.startupTimeout = null;
			return this;
		}

		/**
		 * Sets the startup-cancellation budget.
		 *
		 * @param duration cancellation budget
		 * @return this builder
		 * @throws NullPointerException if {@code duration} is null
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder startupCancellationTimeout(@NonNull Duration duration) {
			this.startupCancellationTimeout = validate(requireNonNull(duration),
					"startupCancellationTimeout");
			return this;
		}

		/**
		 * Sets the graceful shutdown duration.
		 *
		 * @param duration graceful shutdown duration
		 * @return this builder
		 * @throws NullPointerException if {@code duration} is null
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder gracefulShutdownDuration(@NonNull Duration duration) {
			this.gracefulShutdownDuration = validate(requireNonNull(duration),
					"gracefulShutdownDuration");
			return this;
		}

		/**
		 * Sets the forced shutdown duration.
		 *
		 * @param duration forced shutdown duration
		 * @return this builder
		 * @throws NullPointerException if {@code duration} is null
		 * @throws IllegalArgumentException if negative or not representable as
		 * signed nanoseconds
		 */
		@NonNull
		public Builder forcedShutdownDuration(@NonNull Duration duration) {
			this.forcedShutdownDuration = validate(requireNonNull(duration),
					"forcedShutdownDuration");
			return this;
		}

		/** @return immutable lifecycle policy */
		@NonNull
		public LifecyclePolicy build() {
			return new LifecyclePolicy(this);
		}
	}
}
