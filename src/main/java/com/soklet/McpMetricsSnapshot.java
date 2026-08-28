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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Immutable aggregate of collected MCP metrics.
 * <p>
 * An empty instance permits the shared {@link MetricsCollector.Snapshot}
 * attachment to remain non-null when no MCP metrics have been observed. The
 * fixed core families deliberately expose no generic label map; applications
 * that construct custom event values remain responsible for the
 * confidentiality and cardinality of those values.
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
	private final Map<@NonNull ParticipantShutdownDisposition, @NonNull Long>
			shutdowns;
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
	@NonNull
	private final Long activeSubscriptions;
	@NonNull
	private final Map<@NonNull SubscriptionTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot> subscriptionDurations;
	@NonNull
	private final Map<@NonNull EndpointMethodKey, @NonNull Long>
			cancelationsSignaled;
	@NonNull
	private final Map<@NonNull EndpointMethodKey, @NonNull Long> progressEmitted;
	@NonNull
	private final Long keepAlivesEmitted;
	@NonNull
	private final Map<@NonNull Integer, @NonNull Long> protocolErrors;
	@NonNull
	private final Map<@NonNull EndpointMethodKey, @NonNull Long>
			unknownMirroredHeaders;

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
		this.activeSubscriptions = builder.activeSubscriptions;
		this.subscriptionDurations =
				copySubscriptionDurations(builder.subscriptionDurations);
		this.cancelationsSignaled = copyEndpointMethodCounts(
				builder.cancelationsSignaled,
				"MCP cancelation-signaled counts must not be negative.");
		this.progressEmitted = copyEndpointMethodCounts(builder.progressEmitted,
				"MCP progress-emitted counts must not be negative.");
		this.keepAlivesEmitted = builder.keepAlivesEmitted;
		this.protocolErrors = copyProtocolErrorCounts(builder.protocolErrors);
		this.unknownMirroredHeaders = copyEndpointMethodCounts(
				builder.unknownMirroredHeaders,
				"MCP unknown mirrored-header counts must not be negative.");
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
	private static Map<@NonNull ParticipantShutdownDisposition, @NonNull Long>
			copyShutdowns(@NonNull Map<@NonNull ParticipantShutdownDisposition,
					@NonNull Long> shutdowns) {
		EnumMap<ParticipantShutdownDisposition, Long> copied =
				new EnumMap<>(ParticipantShutdownDisposition.class);
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

	@NonNull
	private static Map<@NonNull SubscriptionTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot>
	copySubscriptionDurations(
			@NonNull Map<@NonNull SubscriptionTerminationKey,
					MetricsCollector.@NonNull HistogramSnapshot>
					subscriptionDurations) {
		Map<SubscriptionTerminationKey, MetricsCollector.HistogramSnapshot>
				copied = new LinkedHashMap<>();
		requireNonNull(subscriptionDurations).forEach((key, histogram) ->
				copied.put(requireNonNull(key), requireNonNull(histogram)));
		return Collections.unmodifiableMap(copied);
	}

	@NonNull
	private static Map<@NonNull EndpointMethodKey, @NonNull Long>
	copyEndpointMethodCounts(
			@NonNull Map<@NonNull EndpointMethodKey, @NonNull Long> counts,
			@NonNull String diagnostic) {
		Map<EndpointMethodKey, Long> copied = new LinkedHashMap<>();
		requireNonNull(counts).forEach((key, count) -> {
			requireNonNull(key);
			requireNonNull(count);
			if (count < 0L)
				throw new IllegalArgumentException(requireNonNull(diagnostic));
			copied.put(key, count);
		});
		return Collections.unmodifiableMap(copied);
	}

	@NonNull
	private static Map<@NonNull Integer, @NonNull Long>
	copyProtocolErrorCounts(
			@NonNull Map<@NonNull Integer, @NonNull Long> protocolErrors) {
		Map<Integer, Long> copied = new TreeMap<>();
		requireNonNull(protocolErrors).forEach((code, count) -> {
			requireNonNull(code);
			requireNonNull(count);
			if (count < 0L)
				throw new IllegalArgumentException(
						"MCP protocol-error counts must not be negative.");
			copied.put(code, count);
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
	public Map<@NonNull ParticipantShutdownDisposition, @NonNull Long>
			getShutdowns() {
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
	 * Returns the number of currently active MCP subscriptions.
	 *
	 * @return active MCP subscriptions
	 */
	@NonNull
	public Long getActiveSubscriptions() {
		return this.activeSubscriptions;
	}

	/**
	 * Returns subscription-duration histograms grouped by bounded endpoint and
	 * fixed termination reason dimensions.
	 *
	 * @return immutable subscription-duration histograms
	 */
	@NonNull
	public Map<@NonNull SubscriptionTerminationKey,
			MetricsCollector.@NonNull HistogramSnapshot> getSubscriptionDurations() {
		return this.subscriptionDurations;
	}

	/**
	 * Returns cooperative request-cancelation signals grouped by bounded
	 * endpoint and method dimensions.
	 *
	 * @return immutable cancelation-signaled counts
	 */
	@NonNull
	public Map<@NonNull EndpointMethodKey, @NonNull Long>
	getCancelationsSignaled() {
		return this.cancelationsSignaled;
	}

	/**
	 * Returns progress notifications accepted for delivery grouped by bounded
	 * endpoint and method dimensions.
	 *
	 * @return immutable progress-emitted counts
	 */
	@NonNull
	public Map<@NonNull EndpointMethodKey, @NonNull Long> getProgressEmitted() {
		return this.progressEmitted;
	}

	/**
	 * Returns the number of MCP keep-alive comments accepted for delivery.
	 *
	 * @return keep-alive comments accepted for delivery
	 */
	@NonNull
	public Long getKeepAlivesEmitted() {
		return this.keepAlivesEmitted;
	}

	/**
	 * Returns client-visible MCP protocol errors grouped by error code.
	 *
	 * @return immutable protocol-error counts
	 */
	@NonNull
	public Map<@NonNull Integer, @NonNull Long> getProtocolErrors() {
		return this.protocolErrors;
	}

	/**
	 * Returns unknown mirrored-header occurrences grouped by bounded endpoint
	 * and method dimensions.
	 *
	 * @return immutable unknown mirrored-header counts
	 */
	@NonNull
	public Map<@NonNull EndpointMethodKey, @NonNull Long>
	getUnknownMirroredHeaders() {
		return this.unknownMirroredHeaders;
	}

	/**
	 * Key for endpoint-and-method counter aggregates.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class EndpointMethodKey {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;

		/**
		 * Creates an endpoint-and-method aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @return endpoint-and-method aggregate key
		 */
		@NonNull
		public static EndpointMethodKey fromDimensions(
				@NonNull String endpointPath, @NonNull String jsonRpcMethod) {
			return new EndpointMethodKey(endpointPath, jsonRpcMethod);
		}

		private EndpointMethodKey(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod) {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			if (requireNonNull(jsonRpcMethod).isEmpty())
				throw new IllegalArgumentException(
						"JSON-RPC method must not be empty.");
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
		}

		/** @return registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return whether both aggregate dimensions are equal */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof EndpointMethodKey key))
				return false;
			return this.endpointPath.equals(key.endpointPath)
					&& this.jsonRpcMethod.equals(key.jsonRpcMethod);
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return Objects.hash(this.endpointPath, this.jsonRpcMethod);
		}

		/** @return dimension-redacted diagnostic rendering */
		@Override
		@NonNull
		public String toString() {
			return "EndpointMethodKey{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>}";
		}
	}

	/**
	 * Key for completed-request and request-duration aggregates.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class RequestOutcomeKey {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;
		@NonNull
		private final McpRequestOutcome outcome;

		/**
		 * Creates a request-outcome aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param outcome fixed terminal request outcome
		 * @return request-outcome aggregate key
		 */
		@NonNull
		public static RequestOutcomeKey fromDimensions(
				@NonNull String endpointPath, @NonNull String jsonRpcMethod,
				@NonNull McpRequestOutcome outcome) {
			return new RequestOutcomeKey(endpointPath, jsonRpcMethod, outcome);
		}

		private RequestOutcomeKey(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod,
				@NonNull McpRequestOutcome outcome) {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			if (requireNonNull(jsonRpcMethod).isEmpty())
				throw new IllegalArgumentException(
						"JSON-RPC method must not be empty.");
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
			this.outcome = requireNonNull(outcome);
		}

		/** @return registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return fixed terminal request outcome */
		@NonNull
		public McpRequestOutcome getOutcome() {
			return this.outcome;
		}

		/** @return whether all aggregate dimensions are equal */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof RequestOutcomeKey key))
				return false;
			return this.endpointPath.equals(key.endpointPath)
					&& this.jsonRpcMethod.equals(key.jsonRpcMethod)
					&& this.outcome == key.outcome;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return Objects.hash(this.endpointPath, this.jsonRpcMethod,
					this.outcome);
		}

		/** @return diagnostic rendering with application dimensions redacted */
		@Override
		@NonNull
		public String toString() {
			return "RequestOutcomeKey{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>, outcome=" + this.outcome + "}";
		}
	}

	/**
	 * Key for request-stream duration aggregates.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class RequestStreamTerminationKey {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final String jsonRpcMethod;
		@NonNull
		private final McpStreamTerminationReason reason;

		/**
		 * Creates a request-stream termination aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param jsonRpcMethod bounded JSON-RPC method dimension
		 * @param reason fixed request-stream termination reason
		 * @return request-stream termination aggregate key
		 */
		@NonNull
		public static RequestStreamTerminationKey fromDimensions(
				@NonNull String endpointPath, @NonNull String jsonRpcMethod,
				@NonNull McpStreamTerminationReason reason) {
			return new RequestStreamTerminationKey(endpointPath, jsonRpcMethod,
					reason);
		}

		private RequestStreamTerminationKey(@NonNull String endpointPath,
				@NonNull String jsonRpcMethod,
				@NonNull McpStreamTerminationReason reason) {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			if (requireNonNull(jsonRpcMethod).isEmpty())
				throw new IllegalArgumentException(
						"JSON-RPC method must not be empty.");
			this.endpointPath = endpointPath;
			this.jsonRpcMethod = jsonRpcMethod;
			this.reason = requireNonNull(reason);
		}

		/** @return registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return bounded JSON-RPC method dimension */
		@NonNull
		public String getJsonRpcMethod() {
			return this.jsonRpcMethod;
		}

		/** @return fixed request-stream termination reason */
		@NonNull
		public McpStreamTerminationReason getReason() {
			return this.reason;
		}

		/** @return whether all aggregate dimensions are equal */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof RequestStreamTerminationKey key))
				return false;
			return this.endpointPath.equals(key.endpointPath)
					&& this.jsonRpcMethod.equals(key.jsonRpcMethod)
					&& this.reason == key.reason;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return Objects.hash(this.endpointPath, this.jsonRpcMethod,
					this.reason);
		}

		/** @return diagnostic rendering with application dimensions redacted */
		@Override
		@NonNull
		public String toString() {
			return "RequestStreamTerminationKey{endpointPath=<redacted>, "
					+ "jsonRpcMethod=<redacted>, reason=" + this.reason + "}";
		}
	}

	/**
	 * Key for subscription-duration aggregates.
	 *
	 * @author <a href="https://www.revetkn.com">Mark Allen</a>
	 */
	@ThreadSafe
	public static final class SubscriptionTerminationKey {
		@NonNull
		private final String endpointPath;
		@NonNull
		private final McpStreamTerminationReason reason;

		/**
		 * Creates a subscription termination aggregate key.
		 *
		 * @param endpointPath registered endpoint-path declaration
		 * @param reason fixed subscription termination reason
		 * @return subscription termination aggregate key
		 */
		@NonNull
		public static SubscriptionTerminationKey fromDimensions(
				@NonNull String endpointPath,
				@NonNull McpStreamTerminationReason reason) {
			return new SubscriptionTerminationKey(endpointPath, reason);
		}

		private SubscriptionTerminationKey(@NonNull String endpointPath,
				@NonNull McpStreamTerminationReason reason) {
			if (requireNonNull(endpointPath).isEmpty())
				throw new IllegalArgumentException(
						"Endpoint path must not be empty.");
			this.endpointPath = endpointPath;
			this.reason = requireNonNull(reason);
		}

		/** @return registered endpoint-path declaration */
		@NonNull
		public String getEndpointPath() {
			return this.endpointPath;
		}

		/** @return fixed subscription termination reason */
		@NonNull
		public McpStreamTerminationReason getReason() {
			return this.reason;
		}

		/** @return whether both aggregate dimensions are equal */
		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other)
				return true;
			if (!(other instanceof SubscriptionTerminationKey key))
				return false;
			return this.endpointPath.equals(key.endpointPath)
					&& this.reason == key.reason;
		}

		/** @return value-based hash code */
		@Override
		public int hashCode() {
			return Objects.hash(this.endpointPath, this.reason);
		}

		/** @return diagnostic rendering with the application dimension redacted */
		@Override
		@NonNull
		public String toString() {
			return "SubscriptionTerminationKey{endpointPath=<redacted>, reason="
					+ this.reason + "}";
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
		private Map<@NonNull ParticipantShutdownDisposition, @NonNull Long>
				shutdowns;
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
		@NonNull
		private Long activeSubscriptions;
		@NonNull
		private Map<@NonNull SubscriptionTerminationKey,
				MetricsCollector.@NonNull HistogramSnapshot> subscriptionDurations;
		@NonNull
		private Map<@NonNull EndpointMethodKey, @NonNull Long>
				cancelationsSignaled;
		@NonNull
		private Map<@NonNull EndpointMethodKey, @NonNull Long> progressEmitted;
		@NonNull
		private Long keepAlivesEmitted;
		@NonNull
		private Map<@NonNull Integer, @NonNull Long> protocolErrors;
		@NonNull
		private Map<@NonNull EndpointMethodKey, @NonNull Long>
				unknownMirroredHeaders;

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
			this.activeSubscriptions = 0L;
			this.subscriptionDurations = Map.of();
			this.cancelationsSignaled = Map.of();
			this.progressEmitted = Map.of();
			this.keepAlivesEmitted = 0L;
			this.protocolErrors = Map.of();
			this.unknownMirroredHeaders = Map.of();
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
				@NonNull Map<@NonNull ParticipantShutdownDisposition,
						@NonNull Long> shutdowns) {
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
		 * Sets the number of currently active MCP subscriptions.
		 *
		 * @param activeSubscriptions active MCP subscriptions
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder activeSubscriptions(@NonNull Long activeSubscriptions) {
			this.activeSubscriptions = requireNonNegative(activeSubscriptions,
					"Active MCP subscription count must not be negative.");
			return this;
		}

		/**
		 * Sets subscription-duration histograms grouped by bounded endpoint and
		 * fixed termination reason dimensions.
		 *
		 * @param subscriptionDurations subscription-duration histograms
		 * @return this builder
		 */
		@NonNull
		public Builder subscriptionDurations(
				@NonNull Map<@NonNull SubscriptionTerminationKey,
						MetricsCollector.@NonNull HistogramSnapshot>
						subscriptionDurations) {
			this.subscriptionDurations =
					copySubscriptionDurations(subscriptionDurations);
			return this;
		}

		/**
		 * Sets nonnegative cooperative request-cancelation signals grouped by
		 * bounded endpoint and method dimensions.
		 *
		 * @param cancelationsSignaled cancelation-signaled counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder cancelationsSignaled(
				@NonNull Map<@NonNull EndpointMethodKey, @NonNull Long>
						cancelationsSignaled) {
			this.cancelationsSignaled = copyEndpointMethodCounts(
					cancelationsSignaled,
					"MCP cancelation-signaled counts must not be negative.");
			return this;
		}

		/**
		 * Sets nonnegative progress notifications accepted for delivery grouped by
		 * bounded endpoint and method dimensions.
		 *
		 * @param progressEmitted progress-emitted counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder progressEmitted(
				@NonNull Map<@NonNull EndpointMethodKey, @NonNull Long>
						progressEmitted) {
			this.progressEmitted = copyEndpointMethodCounts(progressEmitted,
					"MCP progress-emitted counts must not be negative.");
			return this;
		}

		/**
		 * Sets the number of MCP keep-alive comments accepted for delivery.
		 *
		 * @param keepAlivesEmitted keep-alive comments accepted for delivery
		 * @return this builder
		 * @throws IllegalArgumentException if the count is negative
		 */
		@NonNull
		public Builder keepAlivesEmitted(@NonNull Long keepAlivesEmitted) {
			this.keepAlivesEmitted = requireNonNegative(keepAlivesEmitted,
					"MCP keep-alive emitted count must not be negative.");
			return this;
		}

		/**
		 * Sets nonnegative client-visible MCP protocol-error counts grouped by
		 * error code.
		 *
		 * @param protocolErrors protocol-error counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder protocolErrors(
				@NonNull Map<@NonNull Integer, @NonNull Long> protocolErrors) {
			this.protocolErrors = copyProtocolErrorCounts(protocolErrors);
			return this;
		}

		/**
		 * Sets nonnegative unknown mirrored-header occurrences grouped by bounded
		 * endpoint and method dimensions.
		 *
		 * @param unknownMirroredHeaders unknown mirrored-header counts
		 * @return this builder
		 * @throws IllegalArgumentException if any count is negative
		 */
		@NonNull
		public Builder unknownMirroredHeaders(
				@NonNull Map<@NonNull EndpointMethodKey, @NonNull Long>
						unknownMirroredHeaders) {
			this.unknownMirroredHeaders = copyEndpointMethodCounts(
					unknownMirroredHeaders,
					"MCP unknown mirrored-header counts must not be negative.");
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
