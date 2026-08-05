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
 * rate limiter. This type intentionally has no unlimited or disabled form.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpTokenBucketConfig {
	private final long capacity;
	private final long refillTokens;
	@NonNull
	private final Duration refillPeriod;

	/**
	 * Returns a configuration with capacity {@code 20} that replenishes
	 * {@code 60} tokens per minute.
	 *
	 * @return default finite token-bucket configuration
	 */
	@NonNull
	public static McpTokenBucketConfig fromDefaults() {
		return withCapacity(20L)
				.refillTokens(60L)
				.refillPeriod(Duration.ofMinutes(1))
				.build();
	}

	/**
	 * Vends a builder primed with the bucket's maximum token capacity.
	 *
	 * @param capacity positive maximum number of tokens
	 * @return token-bucket configuration builder
	 */
	@NonNull
	public static Builder withCapacity(long capacity) {
		return new Builder().capacity(capacity);
	}

	private McpTokenBucketConfig(@NonNull Builder builder) {
		this.capacity = requirePositive(builder.capacity, "capacity");
		this.refillTokens = requirePositive(builder.refillTokens, "refillTokens");
		this.refillPeriod = requireNonNull(builder.refillPeriod, "refillPeriod");

		if (this.refillPeriod.isZero() || this.refillPeriod.isNegative())
			throw new IllegalArgumentException("refillPeriod must be positive");
		try {
			if (this.refillPeriod.toNanos() <= 0)
				throw new IllegalArgumentException("refillPeriod must be at least one nanosecond");
		} catch (ArithmeticException exception) {
			throw new IllegalArgumentException(
					"refillPeriod must fit in a signed 64-bit nanosecond count", exception);
		}
	}

	/**
	 * Returns the maximum number of tokens held by one bucket.
	 *
	 * @return positive token capacity
	 */
	public long getCapacity() {
		return this.capacity;
	}

	/**
	 * Returns the number of tokens replenished during one refill period.
	 *
	 * @return positive refill quantity
	 */
	public long getRefillTokens() {
		return this.refillTokens;
	}

	/**
	 * Returns the refill period.
	 *
	 * @return positive refill period
	 */
	@NonNull
	public Duration getRefillPeriod() {
		return this.refillPeriod;
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
		private long refillTokens;
		@Nullable
		private Duration refillPeriod;

		private Builder() {
		}

		/**
		 * Sets the maximum number of tokens held by one bucket.
		 *
		 * @param capacity positive token capacity
		 * @return this builder
		 */
		@NonNull
		public Builder capacity(long capacity) {
			this.capacity = capacity;
			return this;
		}

		/**
		 * Sets the number of tokens replenished during one refill period.
		 *
		 * @param refillTokens positive refill quantity
		 * @return this builder
		 */
		@NonNull
		public Builder refillTokens(long refillTokens) {
			this.refillTokens = refillTokens;
			return this;
		}

		/**
		 * Sets the positive refill period.
		 *
		 * @param refillPeriod positive refill period
		 * @return this builder
		 */
		@NonNull
		public Builder refillPeriod(@NonNull Duration refillPeriod) {
			this.refillPeriod = requireNonNull(refillPeriod);
			return this;
		}

		/**
		 * Builds an immutable finite configuration.
		 *
		 * @return token-bucket configuration
		 * @throws IllegalArgumentException if a numeric value is not positive or
		 *                                  the refill period cannot be represented
		 *                                  in nanoseconds
		 * @throws NullPointerException if no refill period was configured
		 */
		@NonNull
		public McpTokenBucketConfig build() {
			return new McpTokenBucketConfig(this);
		}
	}
}
