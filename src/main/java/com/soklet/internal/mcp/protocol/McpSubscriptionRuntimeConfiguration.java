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

package com.soklet.internal.mcp.protocol;

import com.soklet.McpSubscriptionAuthorizer;
import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable internal projection of the public MCP stream and subscription
 * bounds. Public server construction supplies the configured authorization
 * policy and exact bounds. Older package-private bridge seams retain these
 * defaults with authorization absent so their pre-authorization behavior stays
 * source-compatible; that absence is never used by {@code McpServer.Builder}.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpSubscriptionRuntimeConfiguration(int streamQueueCapacity,
		@NonNull Duration writeTimeout, @NonNull Duration keepAliveInterval,
		@NonNull Duration shutdownTimeout, int maximumSubscriptionsPerPartition,
		@NonNull Duration maximumSubscriptionDuration,
		@NonNull Duration catalogProjectionTimeout,
		@NonNull Duration authorizationTimeout,
		@NonNull Duration maximumAuthorizationDuration,
		@NonNull Optional<@NonNull McpSubscriptionAuthorizer> authorizer) {
	/**
	 * Preserves the authorization-free compatibility seam used by direct internal
	 * runtime tests. Public server construction always supplies an authorizer and
	 * the exact configured authorization bounds through the full constructor.
	 */
	McpSubscriptionRuntimeConfiguration(int streamQueueCapacity,
			@NonNull Duration writeTimeout, @NonNull Duration keepAliveInterval,
			@NonNull Duration shutdownTimeout,
			int maximumSubscriptionsPerPartition,
			@NonNull Duration maximumSubscriptionDuration) {
		this(streamQueueCapacity, writeTimeout, keepAliveInterval, shutdownTimeout,
				maximumSubscriptionsPerPartition, maximumSubscriptionDuration,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(1),
				Optional.empty());
	}

	McpSubscriptionRuntimeConfiguration {
		if (streamQueueCapacity < 1)
			throw new IllegalArgumentException("Stream queue capacity must be positive.");
		if (maximumSubscriptionsPerPartition < 1)
			throw new IllegalArgumentException(
					"Maximum subscriptions per partition must be positive.");
		writeTimeout = requirePositive(writeTimeout, "Write timeout");
		keepAliveInterval = requirePositive(keepAliveInterval,
				"Keep-alive interval");
		shutdownTimeout = requirePositive(shutdownTimeout, "Shutdown timeout");
		maximumSubscriptionDuration = requirePositive(maximumSubscriptionDuration,
				"Maximum subscription duration");
		catalogProjectionTimeout = requirePositive(catalogProjectionTimeout,
				"Subscription catalog projection timeout");
		authorizationTimeout = requirePositive(authorizationTimeout,
				"Subscription authorization timeout");
		maximumAuthorizationDuration = requirePositive(
				maximumAuthorizationDuration,
				"Maximum subscription authorization duration");
		requireNonNull(authorizer);
		if (keepAliveInterval.compareTo(writeTimeout) >= 0)
			throw new IllegalArgumentException(
					"Keep-alive interval must be shorter than write timeout.");
	}

	@NonNull
	static McpSubscriptionRuntimeConfiguration productionDefaults() {
		return new McpSubscriptionRuntimeConfiguration(128,
				Duration.ofSeconds(30), Duration.ofSeconds(15),
				Duration.ofSeconds(30), 32, Duration.ofHours(24),
				Duration.ofSeconds(5), Duration.ofSeconds(5),
				Duration.ofMinutes(1), Optional.empty());
	}

	@NonNull
	private static Duration requirePositive(@NonNull Duration value,
			@NonNull String description) {
		requireNonNull(value);
		requireNonNull(description);
		if (value.isNegative())
			throw new IllegalArgumentException(description + " must be positive.");
		try {
			if (value.toNanos() < 1L)
				throw new IllegalArgumentException(description + " must be positive.");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(description + " is too large.", exception);
		}
		return value;
	}
}
