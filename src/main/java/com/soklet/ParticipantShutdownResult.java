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

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable terminal evidence for one configured lifecycle participant. */
public final class ParticipantShutdownResult {
	@NonNull
	private final ParticipantKind participantKind;
	@NonNull
	private final ParticipantShutdownDisposition disposition;
	@NonNull
	private final List<@NonNull Throwable> failures;
	@Nullable
	private final ResidualActivityEvidence residualActivityEvidence;

	ParticipantShutdownResult(@NonNull ParticipantKind participantKind,
			@NonNull ParticipantShutdownDisposition disposition,
			@NonNull List<? extends @NonNull Throwable> failures,
			@Nullable ResidualActivityEvidence residualActivityEvidence) {
		this.participantKind = requireNonNull(participantKind);
		this.disposition = requireNonNull(disposition);
		this.failures = List.copyOf(requireNonNull(failures));
		this.residualActivityEvidence = residualActivityEvidence;
	}

	/** @return participant kind */
	@NonNull
	public ParticipantKind getParticipantKind() {
		return this.participantKind;
	}

	/** @return participant shutdown disposition */
	@NonNull
	public ParticipantShutdownDisposition getDisposition() {
		return this.disposition;
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
