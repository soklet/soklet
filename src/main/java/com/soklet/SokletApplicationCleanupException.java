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

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/** Indicates that core shutdown completed but bounded application cleanup did not. */
public final class SokletApplicationCleanupException
		extends SokletLifecycleException {
	@NonNull
	private final ShutdownCleanupFailure shutdownCleanupFailure;
	@NonNull
	private final Duration shutdownCleanupTimeout;

	SokletApplicationCleanupException(
			@NonNull ShutdownCleanupFailure cleanupFailure,
			@NonNull Duration cleanupTimeout,
			@NonNull ShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super(cleanupFailureMessage(requireNonNull(cleanupFailure)),
				requireNonNull(shutdownResult), requireNonNull(cause));
		this.shutdownCleanupFailure = cleanupFailure;
		this.shutdownCleanupTimeout = requireNonNull(cleanupTimeout);
		if (!shutdownResult.isComplete())
			throw new IllegalArgumentException(
					"Cleanup failure requires a complete core result");
	}

	SokletApplicationCleanupException(
			@NonNull ShutdownCleanupFailure cleanupFailure,
			@NonNull Duration cleanupTimeout,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		this(cleanupFailure, cleanupTimeout,
				ShutdownResult.fromInternal(requireNonNull(shutdownResult)), cause);
	}

	/** @return cleanup failure classification */
	@NonNull
	public ShutdownCleanupFailure getShutdownCleanupFailure() {
		return this.shutdownCleanupFailure;
	}

	/** @return configured cleanup timeout */
	@NonNull
	public Duration getShutdownCleanupTimeout() {
		return this.shutdownCleanupTimeout;
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
