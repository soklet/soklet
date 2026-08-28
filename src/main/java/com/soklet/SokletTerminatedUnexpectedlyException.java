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

import static java.util.Objects.requireNonNull;

/** Indicates that a participant terminated unexpectedly after readiness. */
public final class SokletTerminatedUnexpectedlyException
		extends SokletLifecycleException {
	@NonNull
	private final UnexpectedParticipantTermination unexpectedTermination;
	private final InternalTerminationEvent internalUnexpectedTermination;

	SokletTerminatedUnexpectedlyException(
			@NonNull ShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		this(null, shutdownResult, cause);
	}

	SokletTerminatedUnexpectedlyException(
			InternalTerminationEvent internalUnexpectedTermination,
			@NonNull ShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super("A Soklet transport terminated unexpectedly",
				requireNonNull(shutdownResult), requireNonNull(cause));
		this.unexpectedTermination = shutdownResult.getUnexpectedTermination()
				.orElseThrow(() -> new IllegalArgumentException(
						"Unexpected termination exception requires event evidence"));
		this.internalUnexpectedTermination = internalUnexpectedTermination;
	}

	SokletTerminatedUnexpectedlyException(
			@NonNull InternalTerminationEvent unexpectedTermination,
			@NonNull InternalShutdownResult shutdownResult) {
		this(unexpectedTermination, shutdownResult,
				requireNonNull(unexpectedTermination).cause().orElseGet(() ->
						new IllegalStateException(
								"A transport terminated without a failure cause")));
	}

	SokletTerminatedUnexpectedlyException(
			@NonNull InternalTerminationEvent unexpectedTermination,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		this(unexpectedTermination,
				ShutdownResult.fromInternal(requireNonNull(shutdownResult), null,
						inferParticipantKind(shutdownResult),
						requireNonNull(unexpectedTermination).cause().orElse(null)),
				cause);
	}

	/** @return the first premature participant termination */
	@NonNull
	public UnexpectedParticipantTermination getUnexpectedTermination() {
		return this.unexpectedTermination;
	}

	@NonNull
	InternalTerminationEvent getInternalUnexpectedTermination() {
		return requireNonNull(this.internalUnexpectedTermination,
				"No internal termination event is retained");
	}

	@NonNull
	private static ParticipantKind inferParticipantKind(
			@NonNull InternalShutdownResult result) {
		return requireNonNull(result).participantResults().stream()
				.filter(participant -> participant.disposition()
						== InternalParticipantShutdownDisposition
								.UNEXPECTED_TERMINATION)
				.map(participant -> ParticipantKind.valueOf(
						participant.kind().name())).findFirst()
				.orElseGet(() -> result.participantResults().stream()
						.map(participant -> ParticipantKind.valueOf(
								participant.kind().name())).findFirst()
						.orElse(ParticipantKind.FRAMEWORK_STARTUP));
	}
}
