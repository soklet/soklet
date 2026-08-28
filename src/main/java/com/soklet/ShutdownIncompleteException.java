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

import static java.util.Objects.requireNonNull;

/** Indicates that shutdown ended without complete termination proof. */
public final class ShutdownIncompleteException extends SokletLifecycleException {
	@Nullable
	private final Object retainedScopeEvidence;

	ShutdownIncompleteException(@NonNull ShutdownResult shutdownResult) {
		this(shutdownResult, null, null);
	}

	ShutdownIncompleteException(@NonNull ShutdownResult shutdownResult,
			@Nullable Object retainedScopeEvidence, @Nullable Throwable cause) {
		super("Soklet shutdown could not prove complete termination",
				requireNonNull(shutdownResult), cause);
		this.retainedScopeEvidence = retainedScopeEvidence;
		if (shutdownResult.getDisposition() != ShutdownDisposition.INCOMPLETE)
			throw new IllegalArgumentException(
					"ShutdownIncompleteException requires an incomplete result");
	}

	ShutdownIncompleteException(
			@NonNull InternalShutdownResult shutdownResult) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult)));
	}

	ShutdownIncompleteException(
			@NonNull InternalShutdownResult shutdownResult,
			@Nullable Object retainedScopeEvidence, @Nullable Throwable cause) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult)),
				retainedScopeEvidence, cause);
	}

	boolean retainsScopeEvidence(@NonNull Object candidate) {
		return this.retainedScopeEvidence == requireNonNull(candidate);
	}
}
