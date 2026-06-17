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
import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static java.time.Duration.ZERO;
import static java.time.Duration.between;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.util.Objects.requireNonNull;

/**
 * Compare-and-set persistence contract for MCP sessions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSessionStore {
	/**
	 * Creates and admits a new session for the given request and endpoint class.
	 * <p>
	 * Implementations are responsible for generating a valid MCP session ID and
	 * enforcing their own concurrency limits atomically with persistence. Returning
	 * {@link Optional#empty()} declines admission before session state is created
	 * (for example, when a concurrent-session limit is reached).
	 * <p>
	 * Returned sessions must be new, uninitialized, unterminated sessions for
	 * {@code endpointClass}. Soklet will complete initialization with
	 * {@link #replace(McpStoredSession, McpStoredSession)} after the endpoint's
	 * {@link McpEndpoint#initialize(McpInitializationContext, McpSessionContext)}
	 * callback succeeds.
	 *
	 * @param request the initialize request
	 * @param endpointClass the MCP endpoint class that will own the session
	 * @return the newly-created stored session, or empty if the store declined admission
	 */
	@NonNull
	Optional<McpStoredSession> create(@NonNull Request request,
																		@NonNull Class<? extends McpEndpoint> endpointClass);

	/**
	 * Loads a session by ID.
	 *
	 * @param sessionId the session ID
	 * @return the stored session, if it exists and has not expired
	 */
	@NonNull
	Optional<McpStoredSession> findBySessionId(@NonNull String sessionId);

	/**
	 * Replaces a session using compare-and-set semantics.
	 *
	 * @param expected the currently-stored session snapshot
	 * @param updated the replacement session snapshot
	 * @return {@code true} if the replacement succeeded
	 */
	@NonNull
	Boolean replace(@NonNull McpStoredSession expected,
									@NonNull McpStoredSession updated);

	/**
	 * Deletes a session by ID.
	 *
	 * @param sessionId the session ID to delete
	 */
	void deleteBySessionId(@NonNull String sessionId);

	/**
	 * Acquires a builder for Soklet's default in-memory MCP session store.
	 *
	 * @return a new MCP session store builder
	 */
	@NonNull
	static Builder builder() {
		return new Builder();
	}

	/**
	 * Acquires Soklet's default in-memory MCP session store.
	 *
	 * @return a new in-memory session store
	 */
	@NonNull
	static McpSessionStore fromDefaults() {
		return builder().build();
	}

	/**
	 * Acquires the default in-memory session store using Soklet's default idle timeout.
	 * <p>
	 * Expired sessions are reclaimed opportunistically during lookup and subsequent
	 * session-creation activity; exact deletion timing is therefore best-effort
	 * rather than timer-driven.
	 *
	 * @return a new in-memory session store
	 */
	@NonNull
	static McpSessionStore fromInMemory() {
		return fromDefaults();
	}

	/**
	 * Builder for Soklet's default in-memory MCP session store.
	 */
	@NotThreadSafe
	final class Builder {
		@Nullable
		Duration idleTimeout;
		@Nullable
		IdGenerator<String> sessionIdGenerator;
		@Nullable
		Integer concurrentSessionLimit;

		private Builder() {}

		/**
		 * Sets the idle timeout, or {@link Duration#ZERO} to disable idle expiry.
		 *
		 * @param idleTimeout the idle timeout, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder idleTimeout(@Nullable Duration idleTimeout) {
			this.idleTimeout = idleTimeout;
			return this;
		}

		/**
		 * Sets the generator used for newly-created MCP session IDs.
		 * <p>
		 * Custom generators must return globally unique, cryptographically strong,
		 * visible-ASCII IDs suitable for {@code MCP-Session-Id} header values.
		 *
		 * @param sessionIdGenerator the session ID generator, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder sessionIdGenerator(@Nullable IdGenerator<String> sessionIdGenerator) {
			this.sessionIdGenerator = sessionIdGenerator;
			return this;
		}

		/**
		 * Sets the concurrent MCP session limit.
		 * <p>
		 * A value of {@code 0} disables the in-memory store's session cap.
		 *
		 * @param concurrentSessionLimit the concurrent MCP session limit, or {@code null} for the default
		 * @return this builder
		 */
		@NonNull
		public Builder concurrentSessionLimit(@Nullable Integer concurrentSessionLimit) {
			this.concurrentSessionLimit = concurrentSessionLimit;
			return this;
		}

		/**
		 * Builds the MCP session store.
		 *
		 * @return the built MCP session store
		 */
		@NonNull
		public McpSessionStore build() {
			return new DefaultMcpSessionStore(this);
		}
	}
}

final class DefaultMcpSessionStore implements McpSessionStore {
	@NonNull
	private static final Duration DEFAULT_IDLE_TIMEOUT;
	@NonNull
	private static final Duration DEFAULT_SWEEP_INTERVAL;
	@NonNull
	private static final Integer DEFAULT_CONCURRENT_SESSION_LIMIT;
	@NonNull
	private final Duration idleTimeout;
	@NonNull
	private final IdGenerator<String> sessionIdGenerator;
	@NonNull
	private final Integer concurrentSessionLimit;
	@NonNull
	private final ConcurrentMap<String, McpStoredSession> sessions;
	@NonNull
	private final ConcurrentMap<String, Boolean> activeLimitedSessionIds;
	@NonNull
	private final AtomicInteger activeLimitedSessionCount;
	@NonNull
	private volatile Predicate<String> pinnedSessionPredicate;
	@NonNull
	private volatile Instant lastSweepAt;

	static {
		DEFAULT_IDLE_TIMEOUT = ofHours(24);
		DEFAULT_SWEEP_INTERVAL = ofMinutes(1);
		DEFAULT_CONCURRENT_SESSION_LIMIT = 8_192;
	}

	DefaultMcpSessionStore(McpSessionStore.@NonNull Builder builder) {
		requireNonNull(builder);
		this.idleTimeout = builder.idleTimeout != null ? builder.idleTimeout : DEFAULT_IDLE_TIMEOUT;
		this.sessionIdGenerator = builder.sessionIdGenerator != null ? builder.sessionIdGenerator : IdGenerator.defaultSessionInstance();
		this.concurrentSessionLimit = builder.concurrentSessionLimit != null ? builder.concurrentSessionLimit : DEFAULT_CONCURRENT_SESSION_LIMIT;
		this.sessions = new ConcurrentHashMap<>();
		this.activeLimitedSessionIds = new ConcurrentHashMap<>();
		this.activeLimitedSessionCount = new AtomicInteger(0);
		this.pinnedSessionPredicate = sessionId -> false;
		this.lastSweepAt = Instant.EPOCH;

		if (this.idleTimeout.isNegative())
			throw new IllegalArgumentException("Idle timeout must not be negative.");

		if (this.concurrentSessionLimit < 0)
			throw new IllegalArgumentException("Concurrent session limit must be >= 0");
	}

	@NonNull
	@Override
	public synchronized Optional<McpStoredSession> create(@NonNull Request request,
																												@NonNull Class<? extends McpEndpoint> endpointClass) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		takeExpiredSessionsIfSweepDue();

		if (!reserveSessionSlot())
			return Optional.empty();

		String sessionId = this.sessionIdGenerator.generateId(request);

		if (!DefaultMcpRuntime.isValidMcpSessionId(sessionId)) {
			releaseReservedSessionSlot();
			throw new IllegalStateException("MCP session ID generator produced an invalid session ID.");
		}

		takeExpiredSession(sessionId);

		Instant now = Instant.now();
		McpStoredSession session = new McpStoredSession(
				sessionId,
				endpointClass,
				now,
				now,
				false,
				false,
				null,
				null,
				null,
				McpSessionContext.fromBlankSlate(),
				null,
				0L
		);

		try {
			put(session);
		} catch (Throwable throwable) {
			releaseReservedSessionSlot();
			throw throwable;
		}

		this.activeLimitedSessionIds.put(sessionId, Boolean.TRUE);
		return Optional.of(session);
	}

	synchronized void create(@NonNull McpStoredSession session) {
		requireNonNull(session);
		takeExpiredSessionsIfSweepDue();

		boolean slotReserved = false;

		if (session.terminatedAt() == null) {
			if (!reserveSessionSlot())
				throw new IllegalStateException("MCP session limit reached.");

			slotReserved = true;
		}

		try {
			put(session);
		} catch (Throwable throwable) {
			if (slotReserved)
				releaseReservedSessionSlot();

			throw throwable;
		}

		if (slotReserved)
			this.activeLimitedSessionIds.put(session.sessionId(), Boolean.TRUE);
	}

	@NonNull
	@Override
	public synchronized Optional<McpStoredSession> findBySessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);

		McpStoredSession storedSession = this.sessions.get(sessionId);

		if (storedSession == null)
			return Optional.empty();

		if (isExpired(storedSession)) {
			if (this.sessions.remove(sessionId, storedSession))
				releaseSessionSlot(sessionId);

			return Optional.empty();
		}

		return Optional.of(storedSession);
	}

	@NonNull
	@Override
	public synchronized Boolean replace(@NonNull McpStoredSession expected,
																			 @NonNull McpStoredSession updated) {
		requireNonNull(expected);
		requireNonNull(updated);

		if (!expected.sessionId().equals(updated.sessionId()))
			throw new IllegalArgumentException("Expected and updated sessions must have the same session ID.");

		if (updated.version().longValue() <= expected.version().longValue())
			throw new IllegalArgumentException("Updated session version must be strictly greater than expected version.");

		if (isExpired(expected))
			return false;

		boolean replaced = this.sessions.replace(expected.sessionId(), expected, updated);

		if (replaced && expected.terminatedAt() == null && updated.terminatedAt() != null)
			releaseSessionSlot(updated.sessionId());

		return replaced;
	}

	@Override
	public synchronized void deleteBySessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);

		if (this.sessions.remove(sessionId) != null)
			releaseSessionSlot(sessionId);
	}

	synchronized void pinnedSessionPredicate(@NonNull Predicate<String> pinnedSessionPredicate) {
		requireNonNull(pinnedSessionPredicate);
		this.pinnedSessionPredicate = pinnedSessionPredicate;
	}

	synchronized boolean containsSessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);
		return this.sessions.containsKey(sessionId);
	}

	@NonNull
	synchronized Optional<McpStoredSession> takeExpiredSession(@NonNull String sessionId) {
		requireNonNull(sessionId);

		McpStoredSession storedSession = this.sessions.get(sessionId);

		if (storedSession == null || !isExpired(storedSession))
			return Optional.empty();

		if (!this.sessions.remove(sessionId, storedSession))
			return Optional.empty();

		releaseSessionSlot(sessionId);
		return Optional.of(storedSession);
	}

	@NonNull
	synchronized List<McpStoredSession> takeExpiredSessionsIfSweepDue() {
		if (ZERO.equals(this.idleTimeout))
			return List.of();

		Instant now = Instant.now();
		Duration sweepInterval = sweepInterval();

		if (between(this.lastSweepAt, now).compareTo(sweepInterval) < 0)
			return List.of();

		this.lastSweepAt = now;

		List<McpStoredSession> expiredSessions = new ArrayList<>();

		for (var entry : this.sessions.entrySet()) {
			McpStoredSession storedSession = entry.getValue();

			if (isExpired(storedSession) && this.sessions.remove(entry.getKey(), storedSession)) {
				expiredSessions.add(storedSession);
				releaseSessionSlot(entry.getKey());
			}
		}

		return expiredSessions;
	}

	private void put(@NonNull McpStoredSession session) {
		requireNonNull(session);

		McpStoredSession previous = this.sessions.putIfAbsent(session.sessionId(), session);

		if (previous != null)
			throw new IllegalStateException("Session with ID '%s' already exists".formatted(session.sessionId()));
	}

	private boolean reserveSessionSlot() {
		if (this.concurrentSessionLimit == 0)
			return true;

		while (true) {
			int current = this.activeLimitedSessionCount.get();

			if (current >= this.concurrentSessionLimit)
				return false;

			if (this.activeLimitedSessionCount.compareAndSet(current, current + 1))
				return true;
		}
	}

	private void releaseSessionSlot(@NonNull String sessionId) {
		requireNonNull(sessionId);

		if (this.activeLimitedSessionIds.remove(sessionId) != null)
			releaseReservedSessionSlot();
	}

	private void releaseReservedSessionSlot() {
		while (true) {
			int current = this.activeLimitedSessionCount.get();

			if (current <= 0)
				return;

			if (this.activeLimitedSessionCount.compareAndSet(current, current - 1))
				return;
		}
	}

	private boolean isExpired(@NonNull McpStoredSession storedSession) {
		requireNonNull(storedSession);

		if (ZERO.equals(this.idleTimeout))
			return false;

		if (storedSession.terminatedAt() != null)
			return false;

		if (this.pinnedSessionPredicate.test(storedSession.sessionId()))
			return false;

		Duration idleDuration = between(storedSession.lastActivityAt(), Instant.now());
		return idleDuration.compareTo(this.idleTimeout) > 0;
	}

	@NonNull
	private Duration sweepInterval() {
		return this.idleTimeout.compareTo(DEFAULT_SWEEP_INTERVAL) < 0 ? this.idleTimeout : DEFAULT_SWEEP_INTERVAL;
	}
}
