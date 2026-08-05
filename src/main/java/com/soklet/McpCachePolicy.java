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

import static java.util.Objects.requireNonNull;

/**
 * Immutable MCP application-cache policy.
 *
 * <p>The policy fixes both the cache scope and the default time to live for
 * its owning operation. A result may override only the time to live where the
 * operation's result type explicitly permits it.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpCachePolicy {
	@NonNull
	private static final McpCachePolicy PRIVATE_NO_CACHE =
			new McpCachePolicy(Duration.ZERO, McpCacheScope.PRIVATE);
	@NonNull
	private final Duration timeToLive;
	@NonNull
	private final McpCacheScope scope;

	/**
	 * Creates a cache policy.
	 *
	 * @param timeToLive nonnegative whole-millisecond default duration
	 * @param scope fixed cache scope
	 * @throws IllegalArgumentException if {@code timeToLive} is negative or
	 * has sub-millisecond precision
	 */
	public McpCachePolicy(@NonNull Duration timeToLive,
			@NonNull McpCacheScope scope) {
		this.timeToLive = requireTimeToLive(timeToLive);
		this.scope = requireNonNull(scope);
	}

	/**
	 * Returns the shared private policy with a zero time to live.
	 *
	 * @return private zero-TTL policy
	 */
	@NonNull
	public static McpCachePolicy privateNoCacheInstance() {
		return PRIVATE_NO_CACHE;
	}

	/**
	 * Creates a private policy with the supplied default time to live.
	 *
	 * @param timeToLive nonnegative whole-millisecond default duration
	 * @return private cache policy
	 */
	@NonNull
	public static McpCachePolicy fromPrivateTimeToLive(
			@NonNull Duration timeToLive) {
		return new McpCachePolicy(timeToLive, McpCacheScope.PRIVATE);
	}

	/**
	 * Creates a public policy with the supplied default time to live.
	 *
	 * <p>Callers must use public scope only when results do not vary by
	 * principal, tenant, authorization partition, or other private identity.
	 *
	 * @param timeToLive nonnegative whole-millisecond default duration
	 * @return public cache policy
	 */
	@NonNull
	public static McpCachePolicy fromPublicTimeToLive(
			@NonNull Duration timeToLive) {
		return new McpCachePolicy(timeToLive, McpCacheScope.PUBLIC);
	}

	/** @return nonnegative whole-millisecond default time to live */
	@NonNull
	public Duration getTimeToLive() {
		return this.timeToLive;
	}

	/** @return fixed cache scope */
	@NonNull
	public McpCacheScope getScope() {
		return this.scope;
	}

	@NonNull
	static Duration requireTimeToLive(@NonNull Duration timeToLive) {
		requireNonNull(timeToLive);
		if (timeToLive.isNegative()
				|| timeToLive.getNano() % 1_000_000 != 0)
			throw new IllegalArgumentException(
					"Cache time to live must be a nonnegative whole-millisecond duration.");
		try {
			return Duration.ofMillis(timeToLive.toMillis());
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"Cache time to live must fit in a signed 64-bit millisecond value.",
					exception);
		}
	}
}
