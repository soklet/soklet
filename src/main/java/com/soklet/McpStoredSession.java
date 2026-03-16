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

import javax.annotation.concurrent.Immutable;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * Immutable stored representation of an MCP session.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
public record McpStoredSession(
		@NonNull String sessionId,
		@NonNull Class<? extends McpEndpoint> endpointClass,
		@NonNull Instant createdAt,
		@NonNull Instant lastActivityAt,
		@NonNull Boolean initialized,
		@NonNull Boolean initializedNotificationReceived,
		@Nullable String protocolVersion,
		@Nullable McpClientCapabilities clientCapabilities,
		@Nullable McpNegotiatedCapabilities negotiatedCapabilities,
		@NonNull McpSessionContext sessionContext,
		@Nullable Instant terminatedAt,
		@NonNull Long version
) {
	public McpStoredSession {
		requireNonNull(sessionId);
		requireNonNull(endpointClass);
		requireNonNull(createdAt);
		requireNonNull(lastActivityAt);
		requireNonNull(initialized);
		requireNonNull(initializedNotificationReceived);
		requireNonNull(sessionContext);
		requireNonNull(version);

		if (version.longValue() < 0L)
			throw new IllegalArgumentException("Session version must be non-negative.");
	}
}
