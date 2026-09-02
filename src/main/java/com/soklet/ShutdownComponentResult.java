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

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable terminal evidence for one framework-managed shutdown component. */
@ThreadSafe
public final class ShutdownComponentResult {
	@NonNull
	private final ShutdownComponentType shutdownComponentType;
	@NonNull
	private final ShutdownComponentDisposition
			shutdownComponentDisposition;
	@NonNull
	private final List<@NonNull Throwable> failures;
	@Nullable
	private final ResidualActivityEvidence residualActivityEvidence;

	ShutdownComponentResult(
			@NonNull ShutdownComponentType shutdownComponentType,
			@NonNull ShutdownComponentDisposition
					shutdownComponentDisposition,
			@NonNull List<? extends @NonNull Throwable> failures,
			@Nullable ResidualActivityEvidence residualActivityEvidence) {
		this.shutdownComponentType = requireNonNull(shutdownComponentType);
		this.shutdownComponentDisposition = requireNonNull(
				shutdownComponentDisposition);
		this.failures = List.copyOf(requireNonNull(failures));
		this.residualActivityEvidence = residualActivityEvidence;
	}

	/** @return shutdown component type */
	@NonNull
	public ShutdownComponentType getShutdownComponentType() {
		return this.shutdownComponentType;
	}

	/** @return shutdown component disposition */
	@NonNull
	public ShutdownComponentDisposition
	getShutdownComponentDisposition() {
		return this.shutdownComponentDisposition;
	}

	/**
	 * Returns failures in deterministic observation order. Throwable instances
	 * retain their exact identities and may contain application-sensitive data.
	 *
	 * @return immutable failure list
	 */
	@NonNull
	public List<@NonNull Throwable> getFailures() {
		return this.failures;
	}

	/** @return residual-activity evidence, otherwise empty */
	@NonNull
	public Optional<@NonNull ResidualActivityEvidence>
	getResidualActivityEvidence() {
		return Optional.ofNullable(this.residualActivityEvidence);
	}
}
