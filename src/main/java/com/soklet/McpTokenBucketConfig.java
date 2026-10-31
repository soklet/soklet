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

import static java.util.Objects.requireNonNull;

/**
 * Immutable finite configuration for Soklet's built-in in-memory token-bucket
 * rate limiter. Capacity, refill quantity, and refill interval must all be
 * explicitly configured. This type has no unlimited or disabled form.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTokenBucketConfig {
	private final long capacity;
	private final long refillTokens;
	@NonNull
	private final Duration refillInterval;

	/**
	 * Vends a builder primed with the bucket's maximum token capacity.
	 *
	 * @param capacity positive maximum number of tokens
	 * @return token-bucket configuration builder
	 * @throws NullPointerException if {@code capacity} is null
	 */
	@NonNull
	public static Builder withCapacity(@NonNull Long capacity) {
		return new Builder().capacity(capacity);
	}

	private McpTokenBucketConfig(@NonNull Builder builder) {
		this.capacity = requirePositive(builder.capacity, "capacity");
		if (builder.refillTokens == null || builder.refillInterval == null)
			throw new IllegalStateException(
					"refillTokens and refillInterval must be explicitly configured");
		this.refillTokens = requirePositive(builder.refillTokens, "refillTokens");
		this.refillInterval = requireNonNull(builder.refillInterval,
				"refillInterval");

		if (this.refillInterval.isZero() || this.refillInterval.isNegative())
			throw new IllegalArgumentException("refillInterval must be positive");
		try {
			if (this.refillInterval.toNanos() <= 0)
				throw new IllegalArgumentException(
						"refillInterval must be at least one nanosecond");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"refillInterval must fit in a signed 64-bit nanosecond count",
					exception);
		}
	}

	/**
	 * Returns the maximum number of tokens held by one bucket.
	 *
	 * @return positive token capacity
	 */
	@NonNull
	public Long getCapacity() {
		return this.capacity;
	}

	/**
	 * Returns the number of tokens replenished during one refill interval.
	 *
	 * @return positive refill quantity
	 */
	@NonNull
	public Long getRefillTokens() {
		return this.refillTokens;
	}

	/**
	 * Returns the refill interval.
	 *
	 * @return positive refill interval
	 */
	@NonNull
	public Duration getRefillInterval() {
		return this.refillInterval;
	}

	/** @return whether every token-bucket setting is equal */
	@Override
	public boolean equals(@Nullable Object other) {
		return this == other
				|| other instanceof McpTokenBucketConfig configuration
				&& this.capacity == configuration.capacity
				&& this.refillTokens == configuration.refillTokens
				&& this.refillInterval.equals(configuration.refillInterval);
	}

	/** @return structural token-bucket configuration hash code */
	@Override
	public int hashCode() {
		int result = Long.hashCode(this.capacity);
		result = 31 * result + Long.hashCode(this.refillTokens);
		result = 31 * result + this.refillInterval.hashCode();
		return result;
	}

	private static long requirePositive(long value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive");
		return value;
	}

	/**
	 * Single-threaded builder for immutable token-bucket configurations.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private long capacity;
		@Nullable
		private Long refillTokens;
		@Nullable
		private Duration refillInterval;

		private Builder() {}

		/**
		 * Sets the maximum number of tokens held by one bucket.
		 *
		 * @param capacity positive token capacity
		 * @return this builder
		 * @throws NullPointerException if {@code capacity} is null
		 */
		@NonNull
		public Builder capacity(@NonNull Long capacity) {
			this.capacity = requireNonNull(capacity);
			return this;
		}

		/**
		 * Sets the number of tokens replenished during one refill interval.
		 * This value is required and has no default.
		 *
		 * @param refillTokens positive refill quantity
		 * @return this builder
		 * @throws NullPointerException if {@code refillTokens} is null
		 */
		@NonNull
		public Builder refillTokens(@NonNull Long refillTokens) {
			this.refillTokens = requireNonNull(refillTokens);
			return this;
		}

		/**
		 * Sets the positive refill interval. This value is required and has no
		 * default.
		 *
		 * @param refillInterval positive refill interval
		 * @return this builder
		 * @throws NullPointerException if {@code refillInterval} is null
		 */
		@NonNull
		public Builder refillInterval(@NonNull Duration refillInterval) {
			this.refillInterval = requireNonNull(refillInterval);
			return this;
		}

		/**
		 * Builds an immutable finite configuration.
		 *
		 * @return token-bucket configuration
		 * @throws IllegalStateException if refill quantity or interval is absent
		 * @throws IllegalArgumentException if a numeric value is not positive or
		 *                                  the refill interval cannot be represented
		 *                                  in nanoseconds
		 */
		@NonNull
		public McpTokenBucketConfig build() {
			return new McpTokenBucketConfig(this);
		}
	}
}
