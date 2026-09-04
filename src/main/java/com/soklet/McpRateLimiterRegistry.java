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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable registry used to resolve annotation and programmatic rate-limiter
 * names while an {@link McpServer} is built.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpRateLimiterRegistry {
	@NonNull
	private static final McpRateLimiterRegistry EMPTY =
			new McpRateLimiterRegistry(Map.of());
	@NonNull
	private final Map<@NonNull String, @NonNull McpRateLimiter> rateLimiters;

	/**
	 * Returns the canonical empty registry.
	 *
	 * @return empty registry
	 */
	@NonNull
	public static McpRateLimiterRegistry emptyInstance() {
		return EMPTY;
	}

	/**
	 * Vends a new registry builder.
	 *
	 * @return registry builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	private McpRateLimiterRegistry(
			@NonNull Map<@NonNull String, @NonNull McpRateLimiter> rateLimiters) {
		this.rateLimiters = Map.copyOf(rateLimiters);
	}

	/**
	 * Returns the immutable name-to-limiter mapping.
	 *
	 * @return registered rate limiters
	 */
	@NonNull
	public Map<@NonNull String, @NonNull McpRateLimiter> getRateLimiters() {
		return this.rateLimiters;
	}

	/**
	 * Finds a rate limiter by name.
	 *
	 * @param name nonblank limiter name
	 * @return registered limiter, or the empty optional
	 */
	@NonNull
	public Optional<@NonNull McpRateLimiter> find(@NonNull String name) {
		return Optional.ofNullable(this.rateLimiters.get(requireName(name)));
	}

	@NonNull
	private static String requireName(@NonNull String name) {
		requireNonNull(name);
		if (name.isBlank())
			throw new IllegalArgumentException("Rate-limiter name must not be blank.");
		return name;
	}

	/**
	 * Single-threaded builder for immutable rate-limiter registries.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private final Map<@NonNull String, @NonNull McpRateLimiter> rateLimiters;

		private Builder() {
			this.rateLimiters = new LinkedHashMap<>();
		}

		/**
		 * Adds one uniquely named rate limiter.
		 *
		 * @param name nonblank limiter name
		 * @param rateLimiter application-owned limiter
		 * @return this builder
		 * @throws IllegalArgumentException if the name is blank or duplicated
		 */
		@NonNull
		public Builder addRateLimiter(@NonNull String name,
				@NonNull McpRateLimiter rateLimiter) {
			String validatedName = requireName(name);
			requireNonNull(rateLimiter);
			if (this.rateLimiters.putIfAbsent(validatedName, rateLimiter) != null)
				throw new IllegalArgumentException(
						"Duplicate rate limiter: " + validatedName);
			return this;
		}

		/**
		 * Builds an immutable registry.
		 *
		 * @return rate-limiter registry
		 */
		@NonNull
		public McpRateLimiterRegistry build() {
			return new McpRateLimiterRegistry(this.rateLimiters);
		}
	}
}
