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

/**
 * Bounded capture controls for one simulated MCP response.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpSimulationOptions {
	private static final int DEFAULT_STREAM_ITEM_QUEUE_CAPACITY = 128;
	private static final int DEFAULT_CAPTURED_SIZE_IN_BYTES = 10 * 1024 * 1024;
	@NonNull
	private static final McpSimulationOptions DEFAULT_INSTANCE = builder().build();
	private final int streamItemQueueCapacity;
	private final int maximumCapturedSizeInBytes;

	private McpSimulationOptions(@NonNull Builder builder) {
		this.streamItemQueueCapacity = requirePositive(
				builder.streamItemQueueCapacity, "streamItemQueueCapacity");
		this.maximumCapturedSizeInBytes = requirePositive(
				builder.maximumCapturedSizeInBytes,
				"maximumCapturedSizeInBytes");
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
	public Integer getMaximumCapturedSizeInBytes() {
		return this.maximumCapturedSizeInBytes;
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
		private int streamItemQueueCapacity =
				DEFAULT_STREAM_ITEM_QUEUE_CAPACITY;
		private int maximumCapturedSizeInBytes = DEFAULT_CAPTURED_SIZE_IN_BYTES;

		private Builder() {
		}

		/**
		 * Sets the maximum pending SSE-item count. The default is {@code 128}.
		 *
		 * @param streamItemQueueCapacity positive queue capacity, or {@code null}
		 *                                to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder streamItemQueueCapacity(
				@Nullable Integer streamItemQueueCapacity) {
			this.streamItemQueueCapacity = streamItemQueueCapacity == null
					? DEFAULT_STREAM_ITEM_QUEUE_CAPACITY : streamItemQueueCapacity;
			return this;
		}

		/**
		 * Sets the cumulative captured-byte bound. Consuming items never refunds
		 * this budget. The default is 10,485,760 bytes.
		 *
		 * @param maximumCapturedSizeInBytes positive byte limit, or {@code null}
		 *                                   to restore the default
		 * @return this builder
		 */
		@NonNull
		public Builder maximumCapturedSizeInBytes(
				@Nullable Integer maximumCapturedSizeInBytes) {
			this.maximumCapturedSizeInBytes = maximumCapturedSizeInBytes == null
					? DEFAULT_CAPTURED_SIZE_IN_BYTES : maximumCapturedSizeInBytes;
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
