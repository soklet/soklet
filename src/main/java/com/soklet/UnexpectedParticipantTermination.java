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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable description of the first participant to terminate prematurely. */
public final class UnexpectedParticipantTermination {
	@NonNull
	private final ParticipantKind participantKind;
	@Nullable
	private final Throwable cause;

	UnexpectedParticipantTermination(@NonNull ParticipantKind participantKind,
			@Nullable Throwable cause) {
		this.participantKind = requireNonNull(participantKind);
		this.cause = cause;
	}

	/** @return kind of the first participant to terminate prematurely */
	@NonNull
	public ParticipantKind getParticipantKind() {
		return this.participantKind;
	}

	/**
	 * Returns the participant-supplied failure cause when one was signalled.
	 *
	 * @return original termination cause, otherwise empty
	 */
	@NonNull
	public Optional<@NonNull Throwable> getCause() {
		return Optional.ofNullable(this.cause);
	}
}
