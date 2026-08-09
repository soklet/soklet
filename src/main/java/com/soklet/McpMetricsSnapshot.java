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
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Immutable aggregate of collected MCP metrics.
 * <p>
 * The complete metric family is provisional until MCP telemetry is finalized.
 * An empty instance permits the shared {@link MetricsCollector.Snapshot}
 * attachment to remain non-null when no MCP metrics have been observed.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public final class McpMetricsSnapshot {
	@NonNull
	private static final McpMetricsSnapshot EMPTY = builder().build();
	@NonNull
	private final Long activeHandlerExecutions;
	@NonNull
	private final Long handlerQueueDepth;
	@NonNull
	private final Long handlerCapacityRejections;
	@NonNull
	private final Map<@NonNull McpShutdownOutcome, @NonNull Long> shutdowns;

	private McpMetricsSnapshot(@NonNull Builder builder) {
		requireNonNull(builder);
		this.activeHandlerExecutions = builder.activeHandlerExecutions;
		this.handlerQueueDepth = builder.handlerQueueDepth;
		this.handlerCapacityRejections = builder.handlerCapacityRejections;
		this.shutdowns = copyShutdowns(builder.shutdowns);
	}

	@NonNull
	private static Long requireNonNegative(@NonNull Long value,
			@NonNull String diagnostic) {
		long requiredValue = requireNonNull(value);
		if (requiredValue < 0L)
			throw new IllegalArgumentException(requireNonNull(diagnostic));
		return requiredValue;
	}

	@NonNull
	private static Map<@NonNull McpShutdownOutcome, @NonNull Long> copyShutdowns(
			@NonNull Map<@NonNull McpShutdownOutcome, @NonNull Long> shutdowns) {
		EnumMap<McpShutdownOutcome, Long> copied =
				new EnumMap<>(McpShutdownOutcome.class);
		requireNonNull(shutdowns).forEach((outcome, count) -> {
			requireNonNull(outcome);
			requireNonNull(count);
			if (count < 0L)
				throw new IllegalArgumentException(
						"MCP shutdown counts must not be negative.");
			copied.put(outcome, count);
		});
		return Collections.unmodifiableMap(copied);
	}

	/**
	 * Returns the shared snapshot containing zero or empty MCP metric values.
	 *
	 * @return empty MCP metrics snapshot
	 */
	@NonNull
	public static McpMetricsSnapshot emptyInstance() {
		return EMPTY;
	}

	/**
	 * Vends a builder for an MCP metrics snapshot.
	 *
	 * @return MCP metrics snapshot builder
	 */
	@NonNull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the number of occupied MCP application-handler execution slots.
	 *
	 * @return active handler executions
	 */
	@NonNull
	public Long getActiveHandlerExecutions() {
		return this.activeHandlerExecutions;
	}

	/**
	 * Returns the number of MCP application requests waiting for a handler slot.
	 *
	 * @return handler queue depth
	 */
	@NonNull
	public Long getHandlerQueueDepth() {
		return this.handlerQueueDepth;
	}

	/**
	 * Returns the number of MCP application requests rejected because the
	 * bounded handler queue was full.
	 *
	 * @return handler capacity rejections
	 */
	@NonNull
	public Long getHandlerCapacityRejections() {
		return this.handlerCapacityRejections;
	}

	/**
	 * Returns nonnegative shutdown counts grouped by fixed shutdown outcome.
	 *
	 * @return immutable, enum-ordered shutdown counts
	 */
	@NonNull
	public Map<@NonNull McpShutdownOutcome, @NonNull Long> getShutdowns() {
		return this.shutdowns;
	}

	/**
	 * Builder for immutable {@link McpMetricsSnapshot} instances.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@NotThreadSafe
	public static final class Builder {
		@NonNull
		private Long activeHandlerExecutions;
		@NonNull
		private Long handlerQueueDepth;
		@NonNull
		private Long handlerCapacityRejections;
		@NonNull
		private Map<@NonNull McpShutdownOutcome, @NonNull Long> shutdowns;

		private Builder() {
			this.activeHandlerExecutions = 0L;
			this.handlerQueueDepth = 0L;
			this.handlerCapacityRejections = 0L;
			this.shutdowns = Map.of();
		}

		/**
		 * Sets the number of occupied MCP application-handler execution slots.
		 *
		 * @param activeHandlerExecutions active handler executions
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder activeHandlerExecutions(
				@NonNull Long activeHandlerExecutions) {
			this.activeHandlerExecutions = requireNonNegative(
					activeHandlerExecutions,
					"Active MCP handler executions must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP application requests waiting for a handler slot.
		 *
		 * @param handlerQueueDepth handler queue depth
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder handlerQueueDepth(@NonNull Long handlerQueueDepth) {
			this.handlerQueueDepth = requireNonNegative(handlerQueueDepth,
					"MCP handler queue depth must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP application requests rejected because the
		 * bounded handler queue was full.
		 *
		 * @param handlerCapacityRejections handler capacity rejections
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder handlerCapacityRejections(
				@NonNull Long handlerCapacityRejections) {
			this.handlerCapacityRejections = requireNonNegative(
					handlerCapacityRejections,
					"MCP handler capacity rejections must not be negative.");
			return this;
		}

		/**
		 * Sets nonnegative shutdown counts grouped by fixed shutdown outcome.
		 *
		 * @param shutdowns nonnegative shutdown counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder shutdowns(
				@NonNull Map<@NonNull McpShutdownOutcome, @NonNull Long> shutdowns) {
			this.shutdowns = copyShutdowns(shutdowns);
			return this;
		}

		/**
		 * Builds an immutable MCP metrics snapshot.
		 *
		 * @return built snapshot
		 */
		@NonNull
		public McpMetricsSnapshot build() {
			return new McpMetricsSnapshot(this);
		}
	}
}
