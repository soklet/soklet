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
 * A result is complete when every configured lifecycle component has affirmative
 * termination proof and no framework-tracked activity remains. Throwable
 * instances retain their exact identities and may contain application-sensitive
 * data; callers own any logging or disclosure decision.
 */
@ThreadSafe
public final class ShutdownResult {
	@NonNull
	private final ShutdownDisposition shutdownDisposition;
	@NonNull
	private final StartupDisposition startupDisposition;
	@NonNull
	private final List<@NonNull ShutdownComponentResult>
			shutdownComponentResults;
	@NonNull
	private final Map<@NonNull ShutdownComponentType,
			@NonNull ShutdownComponentResult>
			shutdownComponentResultsByType;
	@Nullable
	private final Throwable startupFailureCause;
	@Nullable
	private final UnexpectedShutdownComponentTermination
			unexpectedShutdownComponentTermination;
	@NonNull
	private final InternalShutdownResult internalResult;

	private ShutdownResult(@NonNull InternalShutdownResult internalResult,
			@Nullable Throwable startupFailureCause,
			@Nullable ShutdownComponentType unexpectedShutdownComponentType,
			@Nullable Throwable unexpectedCause) {
		this.internalResult = requireNonNull(internalResult);
		this.shutdownDisposition = ShutdownDisposition.valueOf(
				internalResult.disposition().name());
		this.startupDisposition = StartupDisposition.valueOf(
				internalResult.startupDisposition().name());
		String residualSummary = internalResult.retentionSummary()
				.map(LifecycleRetentionSummary::summary).orElse("");
		this.shutdownComponentResults = internalResult.participantResults()
				.stream()
				.map(result -> fromInternal(result, residualSummary)).toList();
		EnumMap<ShutdownComponentType, ShutdownComponentResult> indexed =
				new EnumMap<>(ShutdownComponentType.class);
		for (ShutdownComponentResult result
				: this.shutdownComponentResults)
			indexed.put(result.getShutdownComponentType(), result);
		this.shutdownComponentResultsByType = Collections.unmodifiableMap(
				indexed);
		this.startupFailureCause = startupFailureCause;
		this.unexpectedShutdownComponentTermination =
				unexpectedShutdownComponentType == null ? null
				: new UnexpectedShutdownComponentTermination(unexpectedShutdownComponentType,
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
			@Nullable Throwable startupFailureCause,
			@Nullable ShutdownComponentType unexpectedShutdownComponentType,
			@Nullable Throwable unexpectedCause) {
		return new ShutdownResult(internalResult, startupFailureCause,
				unexpectedShutdownComponentType, unexpectedCause);
	}

	@NonNull
	private static ShutdownComponentResult fromInternal(
			@NonNull InternalLifecycleComponentShutdownResult internalResult,
			@NonNull String residualSummary) {
		Set<ResidualActivityType> residualActivityTypes = internalResult
				.residualActivity().stream()
				.map(kind -> ResidualActivityType.valueOf(kind.name()))
				.collect(() -> EnumSet.noneOf(ResidualActivityType.class),
						EnumSet::add, EnumSet::addAll);
		ResidualActivityEvidence evidence = residualActivityTypes.isEmpty() ? null
				: new ResidualActivityEvidence(residualActivityTypes,
						residualSummary.isEmpty()
								? "Residual activity remains: "
										+ residualActivityTypes
								: residualSummary);
		return new ShutdownComponentResult(
				ShutdownComponentType.valueOf(internalResult.kind().name()),
				ShutdownComponentDisposition.valueOf(
						internalResult.disposition().name()),
				internalResult.failures(), evidence);
	}

	/** @return aggregate shutdown disposition */
	@NonNull
	public ShutdownDisposition getShutdownDisposition() {
		return this.shutdownDisposition;
	}

	/** @return startup disposition for the same lifecycle attempt */
	@NonNull
	public StartupDisposition getStartupDisposition() {
		return this.startupDisposition;
	}

	/**
	 * @return immutable shutdown component results in
	 * {@link ShutdownComponentType} enum order
	 */
	@NonNull
	public List<@NonNull ShutdownComponentResult> getShutdownComponentResults() {
		return this.shutdownComponentResults;
	}

	/**
	 * Looks up terminal evidence for one configured shutdown component type.
	 *
	 * @param shutdownComponentType shutdown component type
	 * @return shutdown component result, otherwise empty when that type
	 * was not configured
	 * @throws NullPointerException if {@code shutdownComponentType} is null
	 */
	@NonNull
	public Optional<@NonNull ShutdownComponentResult> getShutdownComponentResult(
			@NonNull ShutdownComponentType shutdownComponentType) {
		return Optional.ofNullable(this.shutdownComponentResultsByType.get(
				requireNonNull(shutdownComponentType)));
	}

	/**
	 * @return the cause associated with a failed, timed-out, or canceled startup
	 * attempt, otherwise empty
	 */
	@NonNull
	public Optional<@NonNull Throwable> getStartupFailureCause() {
		return Optional.ofNullable(this.startupFailureCause);
	}

	/** @return the first premature shutdown component termination, otherwise empty */
	@NonNull
	public Optional<@NonNull UnexpectedShutdownComponentTermination>
	getUnexpectedShutdownComponentTermination() {
		return Optional.ofNullable(this.unexpectedShutdownComponentTermination);
	}

	/**
	 * @return {@code true} exactly when the disposition is not
	 * {@link ShutdownDisposition#INCOMPLETE}
	 */
	@NonNull
	public Boolean isComplete() {
		return getShutdownDisposition() != ShutdownDisposition.INCOMPLETE;
	}

	@NonNull
	InternalShutdownResult internalResult() {
		return this.internalResult;
	}
}
