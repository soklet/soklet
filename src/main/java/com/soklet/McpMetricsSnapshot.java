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
import java.util.LinkedHashMap;
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
	@NonNull
	private final Long connectionsAccepted;
	@NonNull
	private final Long connectionsRejected;
	@NonNull
	private final Map<MetricsCollector.@NonNull TransportFailureReason,
			@NonNull Long> transportFailures;
	@NonNull
	private final Long serverStarts;
	@NonNull
	private final Long requestsAccepted;
	@NonNull
	private final Long requestsRejected;
	@NonNull
	private final Long activeRequests;
	@NonNull
	private final Map<@NonNull RequestOutcomeKey, @NonNull Long> requests;
	@NonNull
	private final Map<@NonNull RequestOutcomeKey,
			MetricsCollector.@NonNull HistogramSnapshot> requestDurations;
	@NonNull
	private final Long activeRequestStreams;
	@NonNull
	private final Map<@NonNull RequestStreamTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot> requestStreamDurations;

	private McpMetricsSnapshot(@NonNull Builder builder) {
		requireNonNull(builder);
		this.activeHandlerExecutions = builder.activeHandlerExecutions;
		this.handlerQueueDepth = builder.handlerQueueDepth;
		this.handlerCapacityRejections = builder.handlerCapacityRejections;
		this.shutdowns = copyShutdowns(builder.shutdowns);
		this.connectionsAccepted = builder.connectionsAccepted;
		this.connectionsRejected = builder.connectionsRejected;
		this.transportFailures = copyTransportFailures(builder.transportFailures);
		this.serverStarts = builder.serverStarts;
		this.requestsAccepted = builder.requestsAccepted;
		this.requestsRejected = builder.requestsRejected;
		this.activeRequests = builder.activeRequests;
		this.requests = copyRequests(builder.requests);
		this.requestDurations = copyRequestDurations(builder.requestDurations);
		this.activeRequestStreams = builder.activeRequestStreams;
		this.requestStreamDurations =
				copyRequestStreamDurations(builder.requestStreamDurations);
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

	@NonNull
	private static Map<MetricsCollector.@NonNull TransportFailureReason,
			@NonNull Long> copyTransportFailures(
			@NonNull Map<MetricsCollector.@NonNull TransportFailureReason,
					@NonNull Long> transportFailures) {
		EnumMap<MetricsCollector.TransportFailureReason, Long> copied =
				new EnumMap<>(MetricsCollector.TransportFailureReason.class);
		requireNonNull(transportFailures).forEach((reason, count) -> {
			requireNonNull(reason);
			requireNonNull(count);
			if (count < 0L)
				throw new IllegalArgumentException(
						"MCP transport failure counts must not be negative.");
			copied.put(reason, count);
		});
		return Collections.unmodifiableMap(copied);
	}

	@NonNull
	private static Map<@NonNull RequestOutcomeKey, @NonNull Long> copyRequests(
			@NonNull Map<@NonNull RequestOutcomeKey, @NonNull Long> requests) {
		Map<RequestOutcomeKey, Long> copied = new LinkedHashMap<>();
		requireNonNull(requests).forEach((key, count) -> {
			requireNonNull(key);
			requireNonNull(count);
			if (count < 0L)
				throw new IllegalArgumentException(
						"MCP completed request counts must not be negative.");
			copied.put(key, count);
		});
		return Collections.unmodifiableMap(copied);
	}

	@NonNull
	private static Map<@NonNull RequestOutcomeKey,
			MetricsCollector.@NonNull HistogramSnapshot> copyRequestDurations(
			@NonNull Map<@NonNull RequestOutcomeKey,
					MetricsCollector.@NonNull HistogramSnapshot> requestDurations) {
		Map<RequestOutcomeKey, MetricsCollector.HistogramSnapshot> copied =
				new LinkedHashMap<>();
		requireNonNull(requestDurations).forEach((key, histogram) ->
				copied.put(requireNonNull(key), requireNonNull(histogram)));
		return Collections.unmodifiableMap(copied);
	}

	@NonNull
	private static Map<@NonNull RequestStreamTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot>
	copyRequestStreamDurations(
			@NonNull Map<@NonNull RequestStreamTerminationKey,
					MetricsCollector.@NonNull HistogramSnapshot>
					requestStreamDurations) {
		Map<RequestStreamTerminationKey, MetricsCollector.HistogramSnapshot>
				copied = new LinkedHashMap<>();
		requireNonNull(requestStreamDurations).forEach((key, histogram) ->
				copied.put(requireNonNull(key), requireNonNull(histogram)));
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
	 * Returns the number of successfully accepted MCP connections admitted
	 * within the configured connection-capacity bound.
	 *
	 * @return accepted MCP connections
	 */
	@NonNull
	public Long getConnectionsAccepted() {
		return this.connectionsAccepted;
	}

	/**
	 * Returns the number of MCP connections rejected because the configured
	 * connection-capacity bound was full.
	 *
	 * @return rejected MCP connections
	 */
	@NonNull
	public Long getConnectionsRejected() {
		return this.connectionsRejected;
	}

	/**
	 * Returns nonnegative MCP transport-failure counts grouped by fixed reason.
	 *
	 * @return immutable, enum-ordered MCP transport-failure counts
	 */
	@NonNull
	public Map<MetricsCollector.@NonNull TransportFailureReason,
			@NonNull Long> getTransportFailures() {
		return this.transportFailures;
	}

	/**
	 * Returns the number of successful MCP server starts.
	 *
	 * @return successful MCP server starts
	 */
	@NonNull
	public Long getServerStarts() {
		return this.serverStarts;
	}

	/**
	 * Returns the number of MCP requests accepted by the bounded protocol
	 * processor. Processor acceptance can precede a later pre-admission
	 * rejection, so this count is independent of {@link #getRequestsRejected()}.
	 *
	 * @return accepted MCP requests
	 */
	@NonNull
	public Long getRequestsAccepted() {
		return this.requestsAccepted;
	}

	/**
	 * Returns the number of MCP requests rejected before admitted semantic
	 * handling. This count is not the complement of
	 * {@link #getRequestsAccepted()}.
	 *
	 * @return rejected MCP requests
	 */
	@NonNull
	public Long getRequestsRejected() {
		return this.requestsRejected;
	}

	/**
	 * Returns the number of currently active admitted MCP requests.
	 *
	 * @return active admitted MCP requests
	 */
	@NonNull
	public Long getActiveRequests() {
		return this.activeRequests;
	}

	/**
	 * Returns nonnegative completed-request counts grouped by bounded endpoint,
	 * method, and terminal outcome dimensions.
	 *
	 * @return immutable completed-request counts
	 */
	@NonNull
	public Map<@NonNull RequestOutcomeKey, @NonNull Long> getRequests() {
		return this.requests;
	}

	/**
	 * Returns request-duration histograms grouped by bounded endpoint, method,
	 * and terminal outcome dimensions.
	 *
	 * @return immutable request-duration histograms
	 */
	@NonNull
	public Map<@NonNull RequestOutcomeKey,
			MetricsCollector.@NonNull HistogramSnapshot> getRequestDurations() {
		return this.requestDurations;
	}

	/**
	 * Returns the number of currently active MCP request streams.
	 *
	 * @return active MCP request streams
	 */
	@NonNull
	public Long getActiveRequestStreams() {
		return this.activeRequestStreams;
	}

	/**
	 * Returns request-stream duration histograms grouped by bounded endpoint,
	 * method, and fixed termination reason dimensions.
	 *
	 * @return immutable request-stream duration histograms
	 */
	@NonNull
	public Map<@NonNull RequestStreamTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot>
	getRequestStreamDurations() {
		return this.requestStreamDurations;
	}

	/**
	 * Key for completed-request and request-duration aggregates.
	 *
	 * @param endpointPath registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param outcome fixed terminal request outcome
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RequestOutcomeKey(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod,
			@NonNull McpRequestOutcome outcome) {
		/**
		 * Creates a request-outcome aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param outcome fixed terminal request outcome
		 */
		public RequestOutcomeKey {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			if (requireNonNull(jsonRpcMethod).isEmpty())
				throw new IllegalArgumentException(
						"JSON-RPC method must not be empty.");
			requireNonNull(outcome);
		}
	}

	/**
	 * Key for request-stream duration aggregates.
	 *
	 * @param endpointPath registered endpoint-path declaration
	 * @param jsonRpcMethod bounded JSON-RPC method dimension
	 * @param reason fixed request-stream termination reason
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public record RequestStreamTerminationKey(@NonNull String endpointPath,
			@NonNull String jsonRpcMethod,
			@NonNull McpStreamTerminationReason reason) {
		/**
		 * Creates a request-stream termination aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param reason fixed request-stream termination reason
		 */
		public RequestStreamTerminationKey {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			if (requireNonNull(jsonRpcMethod).isEmpty())
				throw new IllegalArgumentException(
						"JSON-RPC method must not be empty.");
			requireNonNull(reason);
		}
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
		@NonNull
		private Long connectionsAccepted;
		@NonNull
		private Long connectionsRejected;
		@NonNull
		private Map<MetricsCollector.@NonNull TransportFailureReason,
				@NonNull Long> transportFailures;
		@NonNull
		private Long serverStarts;
		@NonNull
		private Long requestsAccepted;
		@NonNull
		private Long requestsRejected;
		@NonNull
		private Long activeRequests;
		@NonNull
		private Map<@NonNull RequestOutcomeKey, @NonNull Long> requests;
		@NonNull
		private Map<@NonNull RequestOutcomeKey,
				MetricsCollector.@NonNull HistogramSnapshot> requestDurations;
		@NonNull
		private Long activeRequestStreams;
		@NonNull
		private Map<@NonNull RequestStreamTerminationKey,
				MetricsCollector.@NonNull HistogramSnapshot> requestStreamDurations;

		private Builder() {
			this.activeHandlerExecutions = 0L;
			this.handlerQueueDepth = 0L;
			this.handlerCapacityRejections = 0L;
			this.shutdowns = Map.of();
			this.connectionsAccepted = 0L;
			this.connectionsRejected = 0L;
			this.transportFailures = Map.of();
			this.serverStarts = 0L;
			this.requestsAccepted = 0L;
			this.requestsRejected = 0L;
			this.activeRequests = 0L;
			this.requests = Map.of();
			this.requestDurations = Map.of();
			this.activeRequestStreams = 0L;
			this.requestStreamDurations = Map.of();
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
		 * Sets the number of successfully accepted MCP connections admitted within
		 * the configured connection-capacity bound.
		 *
		 * @param connectionsAccepted accepted MCP connections
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder connectionsAccepted(@NonNull Long connectionsAccepted) {
			this.connectionsAccepted = requireNonNegative(connectionsAccepted,
					"Accepted MCP connection count must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP connections rejected because the configured
		 * connection-capacity bound was full.
		 *
		 * @param connectionsRejected rejected MCP connections
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder connectionsRejected(@NonNull Long connectionsRejected) {
			this.connectionsRejected = requireNonNegative(connectionsRejected,
					"Rejected MCP connection count must not be negative.");
			return this;
		}

		/**
		 * Sets nonnegative MCP transport-failure counts grouped by fixed reason.
		 *
		 * @param transportFailures nonnegative MCP transport-failure counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder transportFailures(
				@NonNull Map<MetricsCollector.@NonNull TransportFailureReason,
						@NonNull Long> transportFailures) {
			this.transportFailures = copyTransportFailures(transportFailures);
			return this;
		}

		/**
		 * Sets the number of successful MCP server starts.
		 *
		 * @param serverStarts successful MCP server starts
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder serverStarts(@NonNull Long serverStarts) {
			this.serverStarts = requireNonNegative(serverStarts,
					"MCP server start count must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP requests accepted by the bounded protocol
		 * processor.
		 *
		 * @param requestsAccepted accepted MCP requests
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder requestsAccepted(@NonNull Long requestsAccepted) {
			this.requestsAccepted = requireNonNegative(requestsAccepted,
					"Accepted MCP request count must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP requests rejected before admitted semantic
		 * handling.
		 *
		 * @param requestsRejected rejected MCP requests
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder requestsRejected(@NonNull Long requestsRejected) {
			this.requestsRejected = requireNonNegative(requestsRejected,
					"Rejected MCP request count must not be negative.");
			return this;
		}

		/**
		 * Sets the number of currently active admitted MCP requests.
		 *
		 * @param activeRequests active admitted MCP requests
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder activeRequests(@NonNull Long activeRequests) {
			this.activeRequests = requireNonNegative(activeRequests,
					"Active MCP request count must not be negative.");
			return this;
		}

		/**
		 * Sets nonnegative completed-request counts grouped by bounded endpoint,
		 * method, and terminal outcome dimensions.
		 *
		 * @param requests completed-request counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder requests(
				@NonNull Map<@NonNull RequestOutcomeKey, @NonNull Long> requests) {
			this.requests = copyRequests(requests);
			return this;
		}

		/**
		 * Sets request-duration histograms grouped by bounded endpoint, method,
		 * and terminal outcome dimensions.
		 *
		 * @param requestDurations request-duration histograms
		 * @return this builder
		 */
		@NonNull
		public Builder requestDurations(
				@NonNull Map<@NonNull RequestOutcomeKey,
						MetricsCollector.@NonNull HistogramSnapshot> requestDurations) {
			this.requestDurations = copyRequestDurations(requestDurations);
			return this;
		}

		/**
		 * Sets the number of currently active MCP request streams.
		 *
		 * @param activeRequestStreams active MCP request streams
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder activeRequestStreams(@NonNull Long activeRequestStreams) {
			this.activeRequestStreams = requireNonNegative(activeRequestStreams,
					"Active MCP request-stream count must not be negative.");
			return this;
		}

		/**
		 * Sets request-stream duration histograms grouped by bounded endpoint,
		 * method, and fixed termination reason dimensions.
		 *
		 * @param requestStreamDurations request-stream duration histograms
		 * @return this builder
		 */
		@NonNull
		public Builder requestStreamDurations(
				@NonNull Map<@NonNull RequestStreamTerminationKey,
						MetricsCollector.@NonNull HistogramSnapshot>
						requestStreamDurations) {
			this.requestStreamDurations =
					copyRequestStreamDurations(requestStreamDurations);
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
