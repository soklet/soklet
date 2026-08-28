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

/**
 * Indicates that a transport lifecycle graph is already owned by another
 * Soklet lifecycle.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
public final class TransportOwnershipException extends IllegalStateException {
	@NonNull
	private final ParticipantKind participantKind;
	@NonNull
	private final Class<?> transportClass;

	TransportOwnershipException(@NonNull InternalParticipantKind participantKind,
			@NonNull Class<?> transportClass) {
		this(ParticipantKind.valueOf(requireNonNull(participantKind).name()),
				transportClass);
	}

	TransportOwnershipException(@NonNull ParticipantKind participantKind,
			@NonNull Class<?> transportClass) {
		super("The " + requireNonNull(participantKind)
				+ " transport identity for "
				+ requireNonNull(transportClass).getName()
				+ " is already owned by another lifecycle");
		this.participantKind = participantKind;
		this.transportClass = transportClass;
	}

	/** @return the kind of transport whose identity is already owned */
	@NonNull
	public ParticipantKind getParticipantKind() {
		return this.participantKind;
	}

	/** @return the configured transport implementation class */
	@NonNull
	public Class<?> getTransportClass() {
		return this.transportClass;
	}
}
