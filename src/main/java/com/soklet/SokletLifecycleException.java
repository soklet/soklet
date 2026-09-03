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

import static java.util.Objects.requireNonNull;

/**
 * Sealed base class for exceptions that carry an immutable lifecycle result.
 * Exception instances retain {@link Throwable}'s mutable state and are not
 * thread-safe; their {@link ShutdownResult} values remain immutable.
 */
@NotThreadSafe
public abstract sealed class SokletLifecycleException extends RuntimeException
		permits SokletShutdownCleanupException,
		SokletShutdownIncompleteException, SokletStartupException,
		SokletUnexpectedTerminationException {
	@NonNull
	private final ShutdownResult shutdownResult;

	SokletLifecycleException(@NonNull String message,
			@NonNull ShutdownResult shutdownResult) {
		super(requireNonNull(message));
		this.shutdownResult = requireNonNull(shutdownResult);
	}

	SokletLifecycleException(@NonNull String message,
			@NonNull ShutdownResult shutdownResult,
			@Nullable Throwable cause) {
		super(requireNonNull(message), cause);
		this.shutdownResult = requireNonNull(shutdownResult);
	}

	/** @return the exact immutable result for the failed lifecycle attempt */
	@NonNull
	public ShutdownResult getShutdownResult() {
		return this.shutdownResult;
	}

	@NonNull
	InternalShutdownResult getInternalShutdownResult() {
		return this.shutdownResult.internalResult();
	}
}
