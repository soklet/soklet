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

import static java.util.Objects.requireNonNull;

/**
 * Bounded capture controls for one simulated MCP response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSimulationOptions {
	@NonNull
	private static final McpSimulationOptions DEFAULT_INSTANCE = builder().build();
	private final int streamItemQueueCapacity;
	private final int maximumCapturedBytes;

	private McpSimulationOptions(@NonNull Builder builder) {
		this.streamItemQueueCapacity = requirePositive(
				builder.streamItemQueueCapacity, "streamItemQueueCapacity");
		this.maximumCapturedBytes = requirePositive(
				builder.maximumCapturedBytes, "maximumCapturedBytes");
	}

	/**
	 * @return the shared options instance with a 128-item queue and a
	 * 10,485,760-byte cumulative capture bound
	 */
	@NonNull
	public static McpSimulationOptions defaultInstance() {
		return DEFAULT_INSTANCE;
	}

	/** @return a new mutable builder initialized to the documented defaults */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/** @return the maximum number of pending captured SSE items */
	@NonNull
	public Integer getStreamItemQueueCapacity() {
		return this.streamItemQueueCapacity;
	}

	/**
	 * @return the cumulative byte bound across captured response or SSE bytes;
	 * consuming an SSE item does not refund bytes
	 */
	@NonNull
	public Integer getMaximumCapturedBytes() {
		return this.maximumCapturedBytes;
	}

	private static int requirePositive(int value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive");
		return value;
	}

	/**
	 * Mutable builder for {@link McpSimulationOptions}; not thread-safe.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		private int streamItemQueueCapacity = 128;
		private int maximumCapturedBytes = 10 * 1024 * 1024;

		private Builder() {
		}

		/**
		 * Sets the maximum pending SSE-item count.
		 *
		 * @param streamItemQueueCapacity positive queue capacity
		 * @return this builder
		 * @throws NullPointerException if {@code streamItemQueueCapacity} is null
		 */
		@NonNull
		public Builder streamItemQueueCapacity(
				@NonNull Integer streamItemQueueCapacity) {
			this.streamItemQueueCapacity = requireNonNull(streamItemQueueCapacity);
			return this;
		}

		/**
		 * Sets the cumulative captured-byte bound. Consuming items never refunds
		 * this budget.
		 *
		 * @param maximumCapturedBytes positive byte limit
		 * @return this builder
		 * @throws NullPointerException if {@code maximumCapturedBytes} is null
		 */
		@NonNull
		public Builder maximumCapturedBytes(
				@NonNull Integer maximumCapturedBytes) {
			this.maximumCapturedBytes = requireNonNull(maximumCapturedBytes);
			return this;
		}

		/**
		 * Validates positive bounds and creates an immutable options value.
		 *
		 * @return immutable options
		 * @throws IllegalArgumentException if either configured bound is not positive
		 */
		@NonNull
		public McpSimulationOptions build() {
			return new McpSimulationOptions(this);
		}
	}
}
