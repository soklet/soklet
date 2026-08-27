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

/** Descriptor-neutral draft of the standalone application options. */
@ThreadSafe
final class SokletApplicationOptions {
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

	@NonNull
	static SokletApplicationOptions fromDefaults() {
		return builder().build();
	}

	@NonNull
	static Builder builder() {
		return new Builder();
	}

	@NonNull
	Set<ShutdownTrigger> getAdditionalTriggers() {
		return this.additionalTriggers;
	}

	@NonNull
	Optional<ShutdownCleanup> getShutdownCleanup() {
		return Optional.ofNullable(this.shutdownCleanup);
	}

	@NonNull
	Optional<Duration> getShutdownCleanupTimeout() {
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
	static final class Builder {
		@NonNull
		private final EnumSet<ShutdownTrigger> additionalTriggers;
		@Nullable
		private ShutdownCleanup shutdownCleanup;
		@Nullable
		private Duration shutdownCleanupTimeout;

		private Builder() {
			this.additionalTriggers = EnumSet.noneOf(ShutdownTrigger.class);
		}

		@NonNull
		Builder additionalTrigger(@NonNull ShutdownTrigger trigger) {
			this.additionalTriggers.add(requireNonNull(trigger));
			return this;
		}

		@NonNull
		Builder afterCompleteShutdown(@NonNull Duration timeout,
				@NonNull ShutdownCleanup cleanup) {
			this.shutdownCleanupTimeout = requireNonNull(timeout);
			this.shutdownCleanup = requireNonNull(cleanup);
			return this;
		}

		@NonNull
		SokletApplicationOptions build() {
			return new SokletApplicationOptions(this);
		}
	}
}

/**
 * One synchronous, ingress-exclusive cleanup action.  Returning after merely
 * delegating asynchronous work is not proof that the resource was released.
 */
@FunctionalInterface
interface ShutdownCleanup {
	void cleanUp(@NonNull InternalShutdownResult completeResult) throws Exception;
}

enum ShutdownCleanupFailure {
	FAILED,
	TIMED_OUT
}

/** Descriptor-neutral draft of the public cleanup failure exception. */
final class SokletApplicationCleanupException extends SokletLifecycleException {
	@NonNull
	private final ShutdownCleanupFailure cleanupFailure;
	@NonNull
	private final Duration cleanupTimeout;

	SokletApplicationCleanupException(
			@NonNull ShutdownCleanupFailure cleanupFailure,
			@NonNull Duration cleanupTimeout,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super(cleanupFailureMessage(requireNonNull(cleanupFailure)),
				shutdownResult, requireNonNull(cause));
		this.cleanupFailure = cleanupFailure;
		this.cleanupTimeout = requireNonNull(cleanupTimeout);
		if (!shutdownResult.isComplete())
			throw new IllegalArgumentException(
					"Cleanup failure requires a complete core result");
	}

	@NonNull
	ShutdownCleanupFailure getCleanupFailure() {
		return this.cleanupFailure;
	}

	@NonNull
	Duration getCleanupTimeout() {
		return this.cleanupTimeout;
	}

	@NonNull
	private static String cleanupFailureMessage(
			@NonNull ShutdownCleanupFailure failure) {
		return switch (requireNonNull(failure)) {
			case FAILED -> "Standalone Soklet cleanup failed";
			case TIMED_OUT -> "Standalone Soklet cleanup timed out; its daemon "
					+ "action may remain live";
		};
	}
}
