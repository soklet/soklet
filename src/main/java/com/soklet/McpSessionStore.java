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

import static java.time.Duration.ZERO;
import static java.time.Duration.between;
import static java.time.Duration.ofHours;
import static java.util.Objects.requireNonNull;

/**
 * Compare-and-set persistence contract for MCP sessions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public interface McpSessionStore {
	void create(@NonNull McpStoredSession session);

	@NonNull
	Optional<McpStoredSession> findBySessionId(@NonNull String sessionId);

	@NonNull
	Boolean replace(@NonNull McpStoredSession expected,
									@NonNull McpStoredSession updated);

	void deleteBySessionId(@NonNull String sessionId);

	@NonNull
	static McpSessionStore fromInMemory() {
		return new DefaultMcpSessionStore(ofHours(24));
	}

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
	private final Duration idleTimeout;
	@NonNull
	private final ConcurrentMap<String, McpStoredSession> sessions;

	DefaultMcpSessionStore(@NonNull Duration idleTimeout) {
		requireNonNull(idleTimeout);
		this.idleTimeout = idleTimeout;
		this.sessions = new ConcurrentHashMap<>();
	}

	@Override
	public void create(@NonNull McpStoredSession session) {
		requireNonNull(session);

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

		if (isExpired(storedSession)) {
			this.sessions.remove(sessionId, storedSession);
			return Optional.empty();
		}

		return Optional.of(storedSession);
	}

	@NonNull
	@Override
	public Boolean replace(@NonNull McpStoredSession expected,
												 @NonNull McpStoredSession updated) {
		requireNonNull(expected);
		requireNonNull(updated);

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

	private boolean isExpired(@NonNull McpStoredSession storedSession) {
		requireNonNull(storedSession);

		if (ZERO.equals(this.idleTimeout))
			return false;

		if (storedSession.terminatedAt() != null)
			return false;

		Duration idleDuration = between(storedSession.lastActivityAt(), Instant.now());
		return idleDuration.compareTo(this.idleTimeout) > 0;
	}
}
