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
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Indicates that core shutdown completed but bounded application cleanup did not. */
@NotThreadSafe
public final class SokletShutdownCleanupException
		extends SokletLifecycleException {
	@NonNull
	private final ShutdownCleanupFailureReason shutdownCleanupFailureReason;
	@NonNull
	private final Duration shutdownCleanupTimeout;

	SokletShutdownCleanupException(
			@NonNull ShutdownCleanupFailureReason shutdownCleanupFailureReason,
			@NonNull Duration shutdownCleanupTimeout,
			@NonNull ShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super(cleanupFailureMessage(requireNonNull(shutdownCleanupFailureReason)),
				requireNonNull(shutdownResult), requireNonNull(cause));
		this.shutdownCleanupFailureReason = shutdownCleanupFailureReason;
		this.shutdownCleanupTimeout = requireNonNull(shutdownCleanupTimeout);
		if (!shutdownResult.isComplete())
			throw new IllegalArgumentException(
					"Cleanup failure requires a complete core result");
	}

	SokletShutdownCleanupException(
			@NonNull ShutdownCleanupFailureReason shutdownCleanupFailureReason,
			@NonNull Duration shutdownCleanupTimeout,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		this(shutdownCleanupFailureReason, shutdownCleanupTimeout,
				ShutdownResult.fromInternal(requireNonNull(shutdownResult)), cause);
	}

	/** @return cleanup failure reason */
	@NonNull
	public ShutdownCleanupFailureReason getShutdownCleanupFailureReason() {
		return this.shutdownCleanupFailureReason;
	}

	/** @return configured cleanup timeout */
	@NonNull
	public Duration getShutdownCleanupTimeout() {
		return this.shutdownCleanupTimeout;
	}

	@NonNull
	private static String cleanupFailureMessage(
			@NonNull ShutdownCleanupFailureReason shutdownCleanupFailureReason) {
		return switch (requireNonNull(shutdownCleanupFailureReason)) {
			case FAILED -> "Standalone Soklet cleanup failed";
			case TIMED_OUT -> "Standalone Soklet cleanup timed out; its daemon "
					+ "action may remain live";
		};
	}
}
