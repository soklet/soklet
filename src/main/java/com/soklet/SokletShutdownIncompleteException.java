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

/** Indicates that shutdown ended without complete termination proof. */
@NotThreadSafe
public final class SokletShutdownIncompleteException
		extends SokletLifecycleException {
	@Nullable
	private final Object retainedScopeEvidence;

	SokletShutdownIncompleteException(@NonNull ShutdownResult shutdownResult) {
		this(shutdownResult, null, null);
	}

	SokletShutdownIncompleteException(@NonNull ShutdownResult shutdownResult,
			@Nullable Object retainedScopeEvidence, @Nullable Throwable cause) {
		super("Soklet shutdown could not prove complete termination",
				requireNonNull(shutdownResult), cause);
		this.retainedScopeEvidence = retainedScopeEvidence;
		if (shutdownResult.getShutdownDisposition() != ShutdownDisposition.INCOMPLETE)
			throw new IllegalArgumentException(
					"SokletShutdownIncompleteException requires an incomplete result");
	}

	SokletShutdownIncompleteException(
			@NonNull InternalShutdownResult shutdownResult) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult)));
	}

	SokletShutdownIncompleteException(
			@NonNull InternalShutdownResult shutdownResult,
			@Nullable Object retainedScopeEvidence, @Nullable Throwable cause) {
		this(ShutdownResult.fromInternal(requireNonNull(shutdownResult)),
				retainedScopeEvidence, cause);
	}

	boolean retainsScopeEvidence(@NonNull Object candidate) {
		Object exactCandidate = requireNonNull(candidate);
		return this.retainedScopeEvidence == exactCandidate
				|| (this.retainedScopeEvidence
						instanceof SimulatorConfigurationScopeIdentity scopeIdentity
						&& exactCandidate instanceof SimulatorConfig simulatorConfig
						&& simulatorConfig.belongsTo(
								scopeIdentity.simulatorConfigurationIdentity()));
	}
}
