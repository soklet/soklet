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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Immutable options for the standalone {@link SokletApplication} runner. */
@ThreadSafe
public final class SokletApplicationOptions {
	@NonNull
	private final Set<ShutdownTrigger> additionalTriggers;
	@Nullable
	private final ShutdownCleanup shutdownCleanup;
	@Nullable
	private final Duration shutdownCleanupTimeout;

	private SokletApplicationOptions(@NonNull Builder builder) {
		Builder exactBuilder = requireNonNull(builder);
		this.additionalTriggers = Collections.unmodifiableSet(
				exactBuilder.additionalTriggers.isEmpty()
						? EnumSet.noneOf(ShutdownTrigger.class)
						: EnumSet.copyOf(exactBuilder.additionalTriggers));
		this.shutdownCleanup = exactBuilder.shutdownCleanup;
		this.shutdownCleanupTimeout = validateCleanupConfiguration(
				exactBuilder.shutdownCleanupTimeout, exactBuilder.shutdownCleanup);
	}

	/**
	 * Acquires options with no additional triggers and no cleanup action.
	 *
	 * @return default runner options
	 */
	@NonNull
	public static SokletApplicationOptions fromDefaults() {
		return builder().build();
	}

	/**
	 * Vends a new mutable options builder.
	 *
	 * @return a new builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Additional runner-scoped shutdown triggers.
	 *
	 * @return an immutable trigger set
	 */
	@NonNull
	public Set<@NonNull ShutdownTrigger> getAdditionalTriggers() {
		return this.additionalTriggers;
	}

	/**
	 * The cleanup action eligible to run after a complete core shutdown.
	 *
	 * @return the configured cleanup action, if any
	 */
	@NonNull
	public Optional<@NonNull ShutdownCleanup> getShutdownCleanup() {
		return Optional.ofNullable(this.shutdownCleanup);
	}

	/**
	 * The explicit deadline budget paired with the cleanup action.
	 *
	 * @return the cleanup timeout, if cleanup is configured
	 */
	@NonNull
	public Optional<@NonNull Duration> getShutdownCleanupTimeout() {
		return Optional.ofNullable(this.shutdownCleanupTimeout);
	}

	@Nullable
	private static Duration validateCleanupConfiguration(
			@Nullable Duration timeout, @Nullable ShutdownCleanup cleanup) {
		if (timeout == null && cleanup == null)
			return null;
		if (timeout == null || cleanup == null)
			throw new IllegalArgumentException(
					"Shutdown cleanup and its timeout must be configured together");
		final long timeoutNanos;
		try {
			timeoutNanos = timeout.toNanos();
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout exceeds signed nanoseconds",
					exception);
		}
		if (timeoutNanos <= 0L)
			throw new IllegalArgumentException(
					"Shutdown cleanup timeout must be greater than zero");
		return timeout;
	}

	/** Mutable only until {@link #build()} snapshots its values. */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final EnumSet<ShutdownTrigger> additionalTriggers;
		@Nullable
		private ShutdownCleanup shutdownCleanup;
		@Nullable
		private Duration shutdownCleanupTimeout;

		private Builder() {
			this.additionalTriggers = EnumSet.noneOf(ShutdownTrigger.class);
		}

		/**
		 * Adds a runner-scoped shutdown trigger.
		 *
		 * @param trigger the trigger to add
		 * @return this builder
		 */
		@NonNull
		public Builder additionalTrigger(@NonNull ShutdownTrigger trigger) {
			this.additionalTriggers.add(requireNonNull(trigger));
			return this;
		}

		/**
		 * Configures one at-most-once cleanup action. The action is invoked only
		 * after a complete core result and must finish synchronously within the
		 * supplied positive duration. Zero, negative, or durations that cannot be
		 * represented as signed nanoseconds are rejected by {@link #build()}.
		 *
		 * @param timeout explicit cleanup deadline budget
		 * @param cleanup cleanup action for ingress-exclusive application resources
		 * @return this builder
		 */
		@NonNull
		public Builder afterCompleteShutdown(@NonNull Duration timeout,
				@NonNull ShutdownCleanup cleanup) {
			this.shutdownCleanupTimeout = requireNonNull(timeout);
			this.shutdownCleanup = requireNonNull(cleanup);
			return this;
		}

		/**
		 * Builds an immutable snapshot of these options.
		 *
		 * @return immutable runner options
		 * @throws IllegalArgumentException if cleanup configuration is incomplete,
		 * non-positive, or exceeds signed-nanosecond range
		 */
		@NonNull
		public SokletApplicationOptions build() {
			return new SokletApplicationOptions(this);
		}
	}
}
