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

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable result of one Soklet lifecycle attempt.
 * <p>
 * A result is complete when every configured participant has affirmative
 * termination proof and no framework-tracked activity remains. Throwable
 * instances retain their exact identities and may contain application-sensitive
 * data; callers own any logging or disclosure decision.
 */
public final class ShutdownResult {
	@NonNull
	private final ShutdownDisposition disposition;
	@NonNull
	private final StartupDisposition startupDisposition;
	@NonNull
	private final List<@NonNull ParticipantShutdownResult> participantResults;
	@NonNull
	private final Map<@NonNull ParticipantKind,
			@NonNull ParticipantShutdownResult> participantResultsByKind;
	@Nullable
	private final Throwable startupCause;
	@Nullable
	private final UnexpectedParticipantTermination unexpectedTermination;
	@NonNull
	private final InternalShutdownResult internalResult;

	private ShutdownResult(@NonNull InternalShutdownResult internalResult,
			@Nullable Throwable startupCause,
			@Nullable ParticipantKind unexpectedParticipantKind,
			@Nullable Throwable unexpectedCause) {
		this.internalResult = requireNonNull(internalResult);
		this.disposition = ShutdownDisposition.valueOf(
				internalResult.disposition().name());
		this.startupDisposition = StartupDisposition.valueOf(
				internalResult.startupDisposition().name());
		String residualSummary = internalResult.retentionSummary()
				.map(LifecycleRetentionSummary::summary).orElse("");
		this.participantResults = internalResult.participantResults().stream()
				.map(result -> fromInternal(result, residualSummary)).toList();
		EnumMap<ParticipantKind, ParticipantShutdownResult> indexed =
				new EnumMap<>(ParticipantKind.class);
		for (ParticipantShutdownResult result : this.participantResults)
			indexed.put(result.getParticipantKind(), result);
		this.participantResultsByKind = Collections.unmodifiableMap(indexed);
		this.startupCause = startupCause;
		this.unexpectedTermination = unexpectedParticipantKind == null ? null
				: new UnexpectedParticipantTermination(unexpectedParticipantKind,
						unexpectedCause);
	}

	@NonNull
	static ShutdownResult fromInternal(
			@NonNull InternalShutdownResult internalResult) {
		return fromInternal(internalResult, null, null, null);
	}

	@NonNull
	static ShutdownResult fromInternal(
			@NonNull InternalShutdownResult internalResult,
			@Nullable Throwable startupCause,
			@Nullable ParticipantKind unexpectedParticipantKind,
			@Nullable Throwable unexpectedCause) {
		return new ShutdownResult(internalResult, startupCause,
				unexpectedParticipantKind, unexpectedCause);
	}

	@NonNull
	private static ParticipantShutdownResult fromInternal(
			@NonNull InternalParticipantShutdownResult internalResult,
			@NonNull String residualSummary) {
		Set<ResidualActivityKind> activityKinds = internalResult
				.residualActivity().stream()
				.map(kind -> ResidualActivityKind.valueOf(kind.name()))
				.collect(() -> EnumSet.noneOf(ResidualActivityKind.class),
						EnumSet::add, EnumSet::addAll);
		ResidualActivityEvidence evidence = activityKinds.isEmpty() ? null
				: new ResidualActivityEvidence(activityKinds,
						residualSummary.isEmpty()
								? "Residual activity remains: " + activityKinds
								: residualSummary);
		return new ParticipantShutdownResult(
				ParticipantKind.valueOf(internalResult.kind().name()),
				ParticipantShutdownDisposition.valueOf(
						internalResult.disposition().name()),
				internalResult.failures(), evidence);
	}

	/** @return aggregate shutdown disposition */
	@NonNull
	public ShutdownDisposition getDisposition() {
		return this.disposition;
	}

	/** @return startup disposition for the same lifecycle attempt */
	@NonNull
	public StartupDisposition getStartupDisposition() {
		return this.startupDisposition;
	}

	/**
	 * @return immutable participant results in {@link ParticipantKind} enum order
	 */
	@NonNull
	public List<@NonNull ParticipantShutdownResult> getParticipantResults() {
		return this.participantResults;
	}

	/**
	 * Looks up terminal evidence for one configured participant kind.
	 *
	 * @param kind participant kind
	 * @return participant result, otherwise empty when that kind was not configured
	 * @throws NullPointerException if {@code kind} is null
	 */
	@NonNull
	public Optional<@NonNull ParticipantShutdownResult> getParticipantResult(
			@NonNull ParticipantKind kind) {
		return Optional.ofNullable(this.participantResultsByKind.get(
				requireNonNull(kind)));
	}

	/** @return startup failure or cancellation cause, otherwise empty */
	@NonNull
	public Optional<@NonNull Throwable> getStartupCause() {
		return Optional.ofNullable(this.startupCause);
	}

	/** @return the first premature participant termination, otherwise empty */
	@NonNull
	public Optional<@NonNull UnexpectedParticipantTermination>
	getUnexpectedTermination() {
		return Optional.ofNullable(this.unexpectedTermination);
	}

	/**
	 * @return {@code true} exactly when the disposition is not
	 * {@link ShutdownDisposition#INCOMPLETE}
	 */
	public boolean isComplete() {
		return getDisposition() != ShutdownDisposition.INCOMPLETE;
	}

	@NonNull
	InternalShutdownResult internalResult() {
		return this.internalResult;
	}
}
