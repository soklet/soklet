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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
	 * Persists a newly-created session.
	 *
	 * @param session the session to create
	 */
	void create(@NonNull McpStoredSession session);

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
	 * Acquires the default in-memory session store using Soklet's default idle timeout.
	 * <p>
	 * Expired sessions are reclaimed opportunistically during subsequent write activity; exact deletion timing is therefore best-effort rather than timer-driven.
	 *
	 * @return a new in-memory session store
	 */
	@NonNull
	static McpSessionStore fromInMemory() {
		return new DefaultMcpSessionStore(ofHours(24));
	}

	/**
	 * Acquires the default in-memory session store using a caller-supplied idle timeout.
	 * <p>
	 * Expired sessions are reclaimed opportunistically during subsequent write activity; exact deletion timing is therefore best-effort rather than timer-driven.
	 *
	 * @param idleTimeout the idle timeout, or {@code Duration.ZERO} to disable idle expiry
	 * @return a new in-memory session store
	 */
	@NonNull
	static McpSessionStore fromInMemory(@NonNull Duration idleTimeout) {
		requireNonNull(idleTimeout);

		if (idleTimeout.isNegative())
			throw new IllegalArgumentException("Idle timeout must not be negative.");

		return new DefaultMcpSessionStore(idleTimeout);
	}
}

final class DefaultMcpSessionStore implements McpSessionStore {
	@NonNull
	private static final Duration DEFAULT_SWEEP_INTERVAL;
	@NonNull
	private final Duration idleTimeout;
	@NonNull
	private final ConcurrentMap<String, McpStoredSession> sessions;
	@NonNull
	private volatile Predicate<String> pinnedSessionPredicate;
	@NonNull
	private volatile Instant lastSweepAt;

	static {
		DEFAULT_SWEEP_INTERVAL = ofMinutes(1);
	}

	DefaultMcpSessionStore(@NonNull Duration idleTimeout) {
		requireNonNull(idleTimeout);
		this.idleTimeout = idleTimeout;
		this.sessions = new ConcurrentHashMap<>();
		this.pinnedSessionPredicate = sessionId -> false;
		this.lastSweepAt = Instant.EPOCH;
	}

	@Override
	public void create(@NonNull McpStoredSession session) {
		requireNonNull(session);
		maybeSweepExpiredSessions();

		McpStoredSession previous = this.sessions.putIfAbsent(session.sessionId(), session);

		if (previous != null)
			throw new IllegalStateException("Session with ID '%s' already exists".formatted(session.sessionId()));
	}

	@NonNull
	@Override
	public Optional<McpStoredSession> findBySessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);

		McpStoredSession storedSession = this.sessions.get(sessionId);

		if (storedSession == null)
			return Optional.empty();

		if (isExpired(storedSession))
			return Optional.empty();

		return Optional.of(storedSession);
	}

	@NonNull
	@Override
	public Boolean replace(@NonNull McpStoredSession expected,
												 @NonNull McpStoredSession updated) {
		requireNonNull(expected);
		requireNonNull(updated);
		maybeSweepExpiredSessions();

		if (!expected.sessionId().equals(updated.sessionId()))
			throw new IllegalArgumentException("Expected and updated sessions must have the same session ID.");

		if (updated.version().longValue() <= expected.version().longValue())
			throw new IllegalArgumentException("Updated session version must be strictly greater than expected version.");

		if (isExpired(expected)) {
			this.sessions.remove(expected.sessionId(), expected);
			return false;
		}

		return this.sessions.replace(expected.sessionId(), expected, updated);
	}

	@Override
	public void deleteBySessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);
		this.sessions.remove(sessionId);
	}

	void pinnedSessionPredicate(@NonNull Predicate<String> pinnedSessionPredicate) {
		requireNonNull(pinnedSessionPredicate);
		this.pinnedSessionPredicate = pinnedSessionPredicate;
	}

	boolean containsSessionId(@NonNull String sessionId) {
		requireNonNull(sessionId);
		return this.sessions.containsKey(sessionId);
	}

	@NonNull
	Optional<McpStoredSession> takeExpiredSession(@NonNull String sessionId) {
		requireNonNull(sessionId);

		McpStoredSession storedSession = this.sessions.get(sessionId);

		if (storedSession == null || !isExpired(storedSession))
			return Optional.empty();

		return this.sessions.remove(sessionId, storedSession)
				? Optional.of(storedSession)
				: Optional.empty();
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

	private void maybeSweepExpiredSessions() {
		if (ZERO.equals(this.idleTimeout))
			return;

		Instant now = Instant.now();
		Duration sweepInterval = sweepInterval();

		if (between(this.lastSweepAt, now).compareTo(sweepInterval) < 0)
			return;

		this.lastSweepAt = now;

		for (var entry : this.sessions.entrySet()) {
			McpStoredSession storedSession = entry.getValue();

			if (isExpired(storedSession))
				this.sessions.remove(entry.getKey(), storedSession);
		}
	}

	@NonNull
	private Duration sweepInterval() {
		return this.idleTimeout.compareTo(DEFAULT_SWEEP_INTERVAL) < 0 ? this.idleTimeout : DEFAULT_SWEEP_INTERVAL;
	}
}
