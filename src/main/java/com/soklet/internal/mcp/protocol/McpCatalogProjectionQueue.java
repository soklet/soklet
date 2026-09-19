/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.protocol;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;

import static java.util.Objects.requireNonNull;

/**
 * Per-subscription catalog-projection state. Access is serialized by the owning
 * subscription lock.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@NotThreadSafe
final class McpCatalogProjectionQueue {
	private static final int SHA_256_BYTE_COUNT = 32;

	enum Family {
		TOOLS,
		PROMPTS
	}

	enum RequestResult {
		SUBMIT,
		COALESCED
	}

	/** Immutable SHA-256 catalog identity. */
	@ThreadSafe
	static final class Digest {
		private final byte @NonNull [] bytes;

		Digest(byte @NonNull [] bytes) {
			byte[] requiredBytes = requireNonNull(bytes);
			if (requiredBytes.length != SHA_256_BYTE_COUNT)
				throw new IllegalArgumentException(
						"A catalog projection digest must contain 32 bytes.");
			this.bytes = requiredBytes.clone();
		}

		byte @NonNull [] bytes() {
			return this.bytes.clone();
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof Digest digest))
				return false;
			return MessageDigest.isEqual(this.bytes, digest.bytes);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(this.bytes);
		}

		@Override
		@NonNull
		public String toString() {
			return "Digest{}";
		}
	}

	/** One identity-owned projection snapshot. */
	static final class Projection {
		@NonNull
		private final Family family;
		@NonNull
		private final State state;
		private final long generation;
		private final long deadlineNanos;
		@NonNull
		private final Digest baseline;

		private Projection(@NonNull Family family, @NonNull State state,
				long generation, long deadlineNanos, @NonNull Digest baseline) {
			this.family = requireNonNull(family);
			this.state = requireNonNull(state);
			this.generation = generation;
			this.deadlineNanos = deadlineNanos;
			this.baseline = requireNonNull(baseline);
		}

		@NonNull
		Family family() {
			return this.family;
		}

		long generation() {
			return this.generation;
		}

		long deadlineNanos() {
			return this.deadlineNanos;
		}

		@NonNull
		Digest baseline() {
			return this.baseline;
		}
	}

	@NonNull
	private final EnumMap<Family, State> states;
	@NonNull
	private final ArrayDeque<Family> pendingFamilies;
	private @Nullable Projection active;
	private boolean jobOutstanding;

	McpCatalogProjectionQueue() {
		this.states = new EnumMap<>(Family.class);
		for (Family family : Family.values())
			this.states.put(family, new State());
		this.pendingFamilies = new ArrayDeque<>(Family.values().length);
	}

	void establishBaseline(@NonNull Family family, @NonNull Digest baseline) {
		State state = state(family);
		if (state.baseline != null)
			throw new IllegalStateException(
					"A catalog projection baseline is already established.");
		state.baseline = requireNonNull(baseline);
		state.dirty = false;
	}

	@NonNull
	RequestResult request(@NonNull Family family, long fixedDeadlineNanos) {
		request(state(family), fixedDeadlineNanos);
		return reserveJobIfNeeded();
	}

	@NonNull
	RequestResult retryAll(long fixedDeadlineNanos) {
		for (Family family : Family.values()) {
			State state = state(family);
			if (state.baseline != null)
				request(state, fixedDeadlineNanos);
		}
		return reserveJobIfNeeded();
	}

	@Nullable
	Projection poll() {
		if (!this.jobOutstanding)
			throw new IllegalStateException(
					"No catalog projection job is outstanding.");
		if (this.active != null)
			throw new IllegalStateException(
					"A catalog projection is already active.");

		Family family = this.pendingFamilies.pollFirst();
		if (family == null) {
			this.jobOutstanding = false;
			return null;
		}

		State state = state(family);
		state.pending = false;
		if (!state.dirty || state.baseline == null)
			throw new IllegalStateException(
					"A queued catalog projection must have a dirty baseline.");
		this.active = new Projection(family, state, state.requestedGeneration,
				state.deadlineNanos, state.baseline);
		return this.active;
	}

	boolean owns(@NonNull Projection projection) {
		return this.active == requireNonNull(projection);
	}

	boolean jobOutstanding() {
		return this.jobOutstanding;
	}

	/**
	 * Releases a scheduler reservation before projection begins while preserving
	 * every dirty family. The authorization revision invalidates the queued
	 * deadline so the next authorized request receives a fresh projection budget,
	 * without running stale-context application callbacks in the meantime.
	 */
	void deferOutstandingJob() {
		if (!this.jobOutstanding)
			throw new IllegalStateException(
					"No catalog projection job is outstanding.");
		if (this.active != null)
			throw new IllegalStateException(
					"An active catalog projection cannot be deferred.");
		markAuthorizationChanged();
		this.jobOutstanding = false;
	}

	/** Marks queued or active work stale to an authorization revision change. */
	void markAuthorizationChanged() {
		for (State state : this.states.values())
			if (state.baseline != null)
				state.refreshDeadlineOnNextRequest = true;
	}

	/**
	 * Installs a digest only after the projection's notification is accepted by
	 * the transport. Ownership is enforced here; acceptance is a caller-owned
	 * transport outcome.
	 */
	void advanceBaseline(@NonNull Projection projection,
			@NonNull Digest baseline) {
		Projection requiredProjection = requireNonNull(projection);
		if (!owns(requiredProjection))
			throw new IllegalStateException(
					"Only the active catalog projection may advance its baseline.");
		requiredProjection.state.baseline = requireNonNull(baseline);
	}

	/**
	 * Marks a projection whose application callback chain still owns its physical
	 * dispatcher slot after logical completion. The first newer invalidation keeps
	 * its own fixed deadline for the eventual successor instead of inheriting the
	 * completed generation's deadline.
	 */
	void markActiveCompletionDeferred(@NonNull Projection projection) {
		Projection requiredProjection = requireNonNull(projection);
		if (!owns(requiredProjection))
			throw new IllegalStateException(
					"Only the active catalog projection may defer completion.");
		State state = requiredProjection.state;
		state.activeCompletionDeferred = true;
		if (state.requestedGeneration != requiredProjection.generation
				&& state.successorDeadlineSet)
			state.deadlineNanos = state.successorDeadlineNanos;
	}

	boolean finish(@NonNull Projection projection, boolean ownerActive,
			boolean clean) {
		Projection requiredProjection = requireNonNull(projection);
		if (!owns(requiredProjection))
			return false;
		if (!ownerActive) {
			reset();
			return false;
		}

		this.active = null;
		State state = requiredProjection.state;
		boolean newerGeneration = state.requestedGeneration
				!= requiredProjection.generation;
		if (newerGeneration && state.successorDeadlineRefresh)
			state.deadlineNanos = state.successorDeadlineNanos;
		state.dirty = !clean || newerGeneration;
		state.activeCompletionDeferred = false;
		state.successorDeadlineSet = false;
		state.successorDeadlineRefresh = false;
		state.successorDeadlineNanos = 0L;
		if (newerGeneration) {
			enqueue(state);
		} else if (clean) {
			state.deadlineNanos = 0L;
		}

		if (this.pendingFamilies.isEmpty()) {
			this.jobOutstanding = false;
			return false;
		}
		return true;
	}

	void reset() {
		this.active = null;
		this.pendingFamilies.clear();
		this.jobOutstanding = false;
		for (State state : this.states.values()) {
			state.baseline = null;
			state.requestedGeneration = 0L;
			state.deadlineNanos = 0L;
			state.dirty = false;
			state.pending = false;
			state.activeCompletionDeferred = false;
			state.successorDeadlineSet = false;
			state.successorDeadlineRefresh = false;
			state.successorDeadlineNanos = 0L;
			state.refreshDeadlineOnNextRequest = false;
		}
	}

	private void request(@NonNull State state, long fixedDeadlineNanos) {
		State requiredState = requireNonNull(state);
		if (requiredState.baseline == null)
			throw new IllegalStateException(
					"A catalog projection baseline has not been established.");
		if (requiredState.requestedGeneration == Long.MAX_VALUE)
			throw new IllegalStateException(
					"A catalog projection generation cannot overflow.");
		boolean activeForState = this.active != null
				&& this.active.state == requiredState;
		boolean projectionInFlight = requiredState.pending || activeForState;
		if (requiredState.refreshDeadlineOnNextRequest) {
			if (activeForState) {
				requiredState.successorDeadlineSet = true;
				requiredState.successorDeadlineRefresh = true;
				requiredState.successorDeadlineNanos = fixedDeadlineNanos;
				if (requiredState.activeCompletionDeferred)
					requiredState.deadlineNanos = fixedDeadlineNanos;
			} else {
				requiredState.deadlineNanos = fixedDeadlineNanos;
			}
			requiredState.refreshDeadlineOnNextRequest = false;
		} else if (!requiredState.dirty || !projectionInFlight)
			requiredState.deadlineNanos = fixedDeadlineNanos;
		else if (activeForState
				&& requiredState.requestedGeneration
						== requireNonNull(this.active).generation) {
			requiredState.successorDeadlineSet = true;
			requiredState.successorDeadlineNanos = fixedDeadlineNanos;
			if (requiredState.activeCompletionDeferred)
				requiredState.deadlineNanos = fixedDeadlineNanos;
		}
		requiredState.requestedGeneration++;
		requiredState.dirty = true;
		enqueue(requiredState);
	}

	private void enqueue(@NonNull State state) {
		State requiredState = requireNonNull(state);
		if (requiredState.pending
				|| (this.active != null && this.active.state == requiredState))
			return;
		requiredState.pending = true;
		this.pendingFamilies.addLast(family(requiredState));
	}

	@NonNull
	private RequestResult reserveJobIfNeeded() {
		if (this.jobOutstanding || this.pendingFamilies.isEmpty())
			return RequestResult.COALESCED;
		this.jobOutstanding = true;
		return RequestResult.SUBMIT;
	}

	@NonNull
	private State state(@NonNull Family family) {
		return requireNonNull(this.states.get(requireNonNull(family)));
	}

	@NonNull
	private Family family(@NonNull State state) {
		State requiredState = requireNonNull(state);
		for (Family family : Family.values())
			if (this.states.get(family) == requiredState)
				return family;
		throw new IllegalStateException(
				"Catalog projection state does not belong to this queue.");
	}

	private static final class State {
		private @Nullable Digest baseline;
		private long requestedGeneration;
		private long deadlineNanos;
		private boolean dirty;
		private boolean pending;
		private boolean activeCompletionDeferred;
		private boolean successorDeadlineSet;
		private boolean successorDeadlineRefresh;
		private long successorDeadlineNanos;
		private boolean refreshDeadlineOnNextRequest;
	}
}
