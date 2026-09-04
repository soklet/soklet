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

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Immutable internal projection of the public MCP stream and subscription
 * bounds. Production construction supplies the configured values; older
 * package-private bridge seams retain these defaults.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
record McpSubscriptionRuntimeConfiguration(int streamQueueCapacity,
		@NonNull Duration writeTimeout, @NonNull Duration keepAliveInterval,
		@NonNull Duration shutdownTimeout, int maximumSubscriptionsPerPartition,
		@NonNull Duration maximumSubscriptionDuration) {
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
		if (keepAliveInterval.compareTo(writeTimeout) >= 0)
			throw new IllegalArgumentException(
					"Keep-alive interval must be shorter than write timeout.");
	}

	@NonNull
	static McpSubscriptionRuntimeConfiguration productionDefaults() {
		return new McpSubscriptionRuntimeConfiguration(128,
				Duration.ofSeconds(30), Duration.ofSeconds(15),
				Duration.ofSeconds(30), 32, Duration.ofHours(24));
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
