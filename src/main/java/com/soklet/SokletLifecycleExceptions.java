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

import javax.annotation.concurrent.Immutable;

import static java.util.Objects.requireNonNull;

/** Descriptor-neutral drafts of the D2 public lifecycle exception family. */
@Immutable
abstract class SokletLifecycleException extends IllegalStateException {
	@NonNull
	private final InternalShutdownResult shutdownResult;

	SokletLifecycleException(@NonNull String message,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super(requireNonNull(message), requireNonNull(cause));
		this.shutdownResult = requireNonNull(shutdownResult);
	}

	SokletLifecycleException(@NonNull String message,
			@NonNull InternalShutdownResult shutdownResult) {
		super(requireNonNull(message));
		this.shutdownResult = requireNonNull(shutdownResult);
	}

	@NonNull
	InternalShutdownResult getInternalShutdownResult() {
		return this.shutdownResult;
	}
}

@Immutable
final class SokletStartupException extends SokletLifecycleException {
	@NonNull
	private final InternalStartupDisposition startupDisposition;

	SokletStartupException(@NonNull InternalStartupDisposition startupDisposition,
			@NonNull InternalShutdownResult shutdownResult,
			@NonNull Throwable cause) {
		super("Soklet startup did not reach readiness: "
				+ requireNonNull(startupDisposition), shutdownResult, cause);
		this.startupDisposition = startupDisposition;
		if (shutdownResult.startupDisposition() != startupDisposition)
			throw new IllegalArgumentException(
					"Startup exception disposition must match its shutdown result");
	}

	SokletStartupException(@NonNull InternalStartupDisposition startupDisposition,
			@NonNull InternalShutdownResult shutdownResult) {
		super("Soklet startup did not reach readiness: "
				+ requireNonNull(startupDisposition), shutdownResult);
		this.startupDisposition = startupDisposition;
		if (shutdownResult.startupDisposition() != startupDisposition)
			throw new IllegalArgumentException(
					"Startup exception disposition must match its shutdown result");
	}

	@NonNull
	InternalStartupDisposition getInternalStartupDisposition() {
		return this.startupDisposition;
	}
}

@Immutable
final class SokletTerminatedUnexpectedlyException
		extends SokletLifecycleException {
	@NonNull
	private final InternalTerminationEvent unexpectedTermination;

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
		super("A Soklet transport terminated unexpectedly", shutdownResult,
				requireNonNull(cause));
		this.unexpectedTermination = requireNonNull(unexpectedTermination);
	}

	@NonNull
	InternalTerminationEvent getInternalUnexpectedTermination() {
		return this.unexpectedTermination;
	}
}

@Immutable
final class TransportOwnershipException extends IllegalStateException {
	@NonNull
	private final InternalParticipantKind participantKind;
	@NonNull
	private final Class<?> transportClass;

	TransportOwnershipException(@NonNull InternalParticipantKind participantKind,
			@NonNull Class<?> transportClass) {
		super("The " + requireNonNull(participantKind)
				+ " transport identity for " + requireNonNull(transportClass).getName()
				+ " is already owned by another lifecycle");
		this.participantKind = participantKind;
		this.transportClass = transportClass;
	}

	@NonNull
	InternalParticipantKind getInternalParticipantKind() {
		return this.participantKind;
	}

	@NonNull
	Class<?> getTransportClass() {
		return this.transportClass;
	}
}
