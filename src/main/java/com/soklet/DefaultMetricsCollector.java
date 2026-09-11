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

import com.soklet.internal.util.ConcurrentLruMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/**
 * A basic in-memory metrics collector.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class DefaultMetricsCollector implements MetricsCollector {
	private static final String UNKNOWN_STATUS_CLASS = "unknown";
	private static final int DEFAULT_METRICS_MAP_CAPACITY = 8_192;

	private static final long[] HTTP_LATENCY_BUCKETS_NANOS = nanosFromMillis(
			1, 2, 5, 10, 25, 50, 100, 200, 400, 800, 1500, 3000, 7000, 15000);

	private static final long[] HTTP_BODY_BYTES_BUCKETS = new long[]{
			0, 128, 256, 512, 1024, 2048, 4096, 8192,
			16384, 32768, 65536, 131072, 262144, 524288,
			1048576, 2097152, 4194304, 8388608
	};

	private static final long[] SSE_STREAM_DURATION_BUCKETS_NANOS = nanosFromSeconds(
			1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600, 7200, 14400);

	private static final long[] SSE_TIME_TO_FIRST_EVENT_BUCKETS_NANOS = nanosFromMillis(
			1, 2, 5, 10, 25, 50, 100, 200, 500, 1000, 3000, 10000);

	private static final long[] SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS = nanosFromMillis(
			0, 1, 2, 5, 10, 25, 50, 100, 200, 400, 800, 1500, 3000, 7000, 15000, 30000);

	private static final long[] SSE_QUEUE_DEPTH_BUCKETS = new long[]{
			0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024
	};


	private final ConcurrentHashMap<IdentityKey<Request>, RequestState> requestsInFlightByIdentity;
	private final ConcurrentHashMap<Object, RequestIdStateBucket> requestsInFlightById;
	private final ThreadLocal<RequestState> requestStateByThread;
	private final ConcurrentLruMap<RequestReadFailureKey, LongAdder> httpRequestReadFailuresByReason;
	private final ConcurrentLruMap<RequestRejectionKey, LongAdder> httpRequestRejectionsByReason;
	private final ConcurrentLruMap<RequestReadFailureKey, LongAdder> sseRequestReadFailuresByReason;
	private final ConcurrentLruMap<RequestRejectionKey, LongAdder> sseRequestRejectionsByReason;
	private final ConcurrentLruMap<HttpServerRouteStatusKey, Histogram> httpRequestDurationByRouteStatus;
	private final ConcurrentLruMap<HttpServerRouteStatusKey, Histogram> httpHandlerDurationByRouteStatus;
	private final ConcurrentLruMap<HttpServerRouteStatusKey, Histogram> httpTimeToFirstByteByRouteStatus;
	private final ConcurrentLruMap<HttpServerRouteKey, Histogram> httpRequestBodyBytesByRoute;
	private final ConcurrentLruMap<HttpServerRouteStatusKey, Histogram> httpResponseBodyBytesByRouteStatus;
	private final ConcurrentHashMap<IdentityKey<SseConnection>, SseConnectionState> sseConnectionsByIdentity;
	private final ConcurrentLruMap<SseEventRouteKey, LongAdder> sseHandshakesAcceptedByRoute;
	private final ConcurrentLruMap<SseEventRouteHandshakeFailureKey, LongAdder> sseHandshakesRejectedByRouteAndReason;
	private final ConcurrentLruMap<SseEventRouteEnqueueOutcomeKey, LongAdder> sseEventEnqueueOutcomesByRoute;
	private final ConcurrentLruMap<SseCommentRouteEnqueueOutcomeKey, LongAdder> sseCommentEnqueueOutcomesByRoute;
	private final ConcurrentLruMap<SseEventRouteDropKey, LongAdder> sseEventDropsByRouteAndReason;
	private final ConcurrentLruMap<SseCommentRouteDropKey, LongAdder> sseCommentDropsByRouteAndReason;
	private final ConcurrentLruMap<SseEventRouteKey, Histogram> sseTimeToFirstEventByRoute;
	private final ConcurrentLruMap<SseEventRouteKey, Histogram> sseEventWriteDurationByRoute;
	private final ConcurrentLruMap<SseEventRouteKey, Histogram> sseEventDeliveryLagByRoute;
	private final ConcurrentLruMap<SseEventRouteKey, Histogram> sseEventSizeByRoute;
	private final ConcurrentLruMap<SseEventRouteKey, Histogram> sseQueueDepthByRoute;
	private final ConcurrentLruMap<SseCommentRouteKey, Histogram> sseCommentDeliveryLagByRoute;
	private final ConcurrentLruMap<SseCommentRouteKey, Histogram> sseCommentSizeByRoute;
	private final ConcurrentLruMap<SseCommentRouteKey, Histogram> sseCommentQueueDepthByRoute;
	private final ConcurrentLruMap<SseStreamRouteTerminationKey, Histogram> sseStreamDurationByRouteAndReason;
	private final LongAdder activeRequests;
	private final LongAdder activeSseStreams;
	private final LongAdder httpConnectionsAccepted;
	private final LongAdder httpConnectionsRejected;
	private final LongAdder sseConnectionsAccepted;
	private final LongAdder sseConnectionsRejected;
	private final ConcurrentLruMap<TransportFailureKey, LongAdder> transportFailuresByServerTypeAndReason;
	private final AtomicLong mcpActiveHandlerExecutions;
	private final AtomicLong mcpHandlerQueueDepth;
	private final LongAdder mcpHandlerCapacityRejections;
	private final Map<ShutdownComponentDisposition, LongAdder>
			mcpServerStopsByDisposition;
	private final LongAdder mcpConnectionsAccepted;
	private final LongAdder mcpConnectionsRejected;
	private final Map<TransportFailureReason, LongAdder>
			mcpTransportFailuresByReason;
	private final LongAdder mcpServerStarts;
	private final LongAdder mcpRequestsAccepted;
	private final LongAdder mcpRequestsRejected;
	private final AtomicLong mcpActiveRequests;
	private final ConcurrentLruMap<McpMetricsSnapshot.RequestOutcomeKey,
			LongAdder> mcpRequestsByOutcome;
	private final ConcurrentLruMap<McpMetricsSnapshot.RequestOutcomeKey,
			Histogram> mcpRequestDurationsByOutcome;
	private final AtomicLong mcpActiveRequestStreams;
	private final ConcurrentLruMap<McpMetricsSnapshot.RequestStreamTerminationKey,
			Histogram> mcpRequestStreamDurationsByReason;
	private final AtomicLong mcpActiveSubscriptions;
	private final ConcurrentLruMap<McpMetricsSnapshot.SubscriptionTerminationKey,
			Histogram> mcpSubscriptionDurationsByReason;
	private final ConcurrentLruMap<McpMetricsSnapshot.EndpointMethodKey,
			LongAdder> mcpCancelationsSignaledByEndpointAndMethod;
	private final ConcurrentLruMap<McpMetricsSnapshot.EndpointMethodKey,
			LongAdder> mcpProgressEmittedByEndpointAndMethod;
	private final LongAdder mcpKeepAlivesEmitted;
	private final ConcurrentLruMap<Integer, LongAdder> mcpProtocolErrorsByCode;
	private final ConcurrentLruMap<McpMetricsSnapshot.EndpointMethodKey,
			LongAdder> mcpUnknownMirroredHeadersByEndpointAndMethod;
	private final AtomicBoolean includeSseMetrics;
	private final AtomicBoolean includeMcpHandlerMetrics;
	private final AtomicBoolean includeMcpTransportMetrics;
	private final AtomicBoolean includeMcpServerMetrics;
	private final AtomicBoolean includeMcpRequestBoundaryMetrics;
	private final AtomicBoolean includeMcpRequestLifecycleMetrics;
	private final AtomicBoolean includeMcpRequestStreamLifecycleMetrics;
	private final AtomicBoolean includeMcpSubscriptionLifecycleMetrics;
	private final AtomicBoolean includeMcpKeepAliveMetrics;

	@NonNull
	public static DefaultMetricsCollector defaultInstance() {
		return new DefaultMetricsCollector();
	}

	private DefaultMetricsCollector() {
		this.requestsInFlightByIdentity = new ConcurrentHashMap<>();
		this.requestsInFlightById = new ConcurrentHashMap<>();
		this.requestStateByThread = new ThreadLocal<>();
		this.httpRequestReadFailuresByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpRequestRejectionsByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseRequestReadFailuresByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseRequestRejectionsByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpRequestDurationByRouteStatus = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpHandlerDurationByRouteStatus = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpTimeToFirstByteByRouteStatus = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpRequestBodyBytesByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpResponseBodyBytesByRouteStatus = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseConnectionsByIdentity = new ConcurrentHashMap<>();
		this.sseHandshakesAcceptedByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseHandshakesRejectedByRouteAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseEventEnqueueOutcomesByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseCommentEnqueueOutcomesByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseEventDropsByRouteAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseCommentDropsByRouteAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseTimeToFirstEventByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseEventWriteDurationByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseEventDeliveryLagByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseEventSizeByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseQueueDepthByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseCommentDeliveryLagByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseCommentSizeByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseCommentQueueDepthByRoute = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseStreamDurationByRouteAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.activeRequests = new LongAdder();
		this.activeSseStreams = new LongAdder();
		this.httpConnectionsAccepted = new LongAdder();
		this.httpConnectionsRejected = new LongAdder();
		this.sseConnectionsAccepted = new LongAdder();
		this.sseConnectionsRejected = new LongAdder();
		this.transportFailuresByServerTypeAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpActiveHandlerExecutions = new AtomicLong();
		this.mcpHandlerQueueDepth = new AtomicLong();
		this.mcpHandlerCapacityRejections = new LongAdder();
		EnumMap<ShutdownComponentDisposition, LongAdder> mcpServerStops =
				new EnumMap<>(ShutdownComponentDisposition.class);
		for (ShutdownComponentDisposition disposition
				: ShutdownComponentDisposition.values())
			mcpServerStops.put(disposition, new LongAdder());
		this.mcpServerStopsByDisposition =
				Collections.unmodifiableMap(mcpServerStops);
		this.mcpConnectionsAccepted = new LongAdder();
		this.mcpConnectionsRejected = new LongAdder();
		EnumMap<TransportFailureReason, LongAdder> mcpTransportFailures =
				new EnumMap<>(TransportFailureReason.class);
		for (TransportFailureReason reason : TransportFailureReason.values())
			mcpTransportFailures.put(reason, new LongAdder());
		this.mcpTransportFailuresByReason =
				Collections.unmodifiableMap(mcpTransportFailures);
		this.mcpServerStarts = new LongAdder();
		this.mcpRequestsAccepted = new LongAdder();
		this.mcpRequestsRejected = new LongAdder();
		this.mcpActiveRequests = new AtomicLong();
		this.mcpRequestsByOutcome =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpRequestDurationsByOutcome =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpActiveRequestStreams = new AtomicLong();
		this.mcpRequestStreamDurationsByReason =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpActiveSubscriptions = new AtomicLong();
		this.mcpSubscriptionDurationsByReason =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpCancelationsSignaledByEndpointAndMethod =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpProgressEmittedByEndpointAndMethod =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpKeepAlivesEmitted = new LongAdder();
		this.mcpProtocolErrorsByCode =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpUnknownMirroredHeadersByEndpointAndMethod =
				new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.includeSseMetrics = new AtomicBoolean(false);
		this.includeMcpHandlerMetrics = new AtomicBoolean(false);
		this.includeMcpTransportMetrics = new AtomicBoolean(false);
		this.includeMcpServerMetrics = new AtomicBoolean(false);
		this.includeMcpRequestBoundaryMetrics = new AtomicBoolean(false);
		this.includeMcpRequestLifecycleMetrics = new AtomicBoolean(false);
		this.includeMcpRequestStreamLifecycleMetrics = new AtomicBoolean(false);
		this.includeMcpSubscriptionLifecycleMetrics = new AtomicBoolean(false);
		this.includeMcpKeepAliveMetrics = new AtomicBoolean(false);
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		this.includeSseMetrics.set(sokletConfig.getSseServer().isPresent());
		this.includeMcpHandlerMetrics.set(sokletConfig.getMcpServer().isPresent());
		this.includeMcpTransportMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpServerMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpRequestBoundaryMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpRequestLifecycleMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpRequestStreamLifecycleMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpSubscriptionLifecycleMetrics.set(
				sokletConfig.getMcpServer().isPresent());
		this.includeMcpKeepAliveMetrics.set(
				sokletConfig.getMcpServer().isPresent());
	}

	@Override
	public void willAcceptConnection(@NonNull ServerType serverType,
																	 @Nullable InetSocketAddress remoteAddress) {
		requireNonNull(serverType);
	}

	@Override
	public void didAcceptConnection(@NonNull ServerType serverType,
																	@Nullable InetSocketAddress remoteAddress) {
		requireNonNull(serverType);

		if (serverType == ServerType.STANDARD_HTTP)
			this.httpConnectionsAccepted.increment();
		else if (serverType == ServerType.SSE)
			this.sseConnectionsAccepted.increment();
	}

	@Override
	public void didFailToAcceptConnection(@NonNull ServerType serverType,
																				@Nullable InetSocketAddress remoteAddress,
																				@NonNull ConnectionRejectionReason reason,
																				@Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);

		if (serverType == ServerType.STANDARD_HTTP)
			this.httpConnectionsRejected.increment();
		else if (serverType == ServerType.SSE)
			this.sseConnectionsRejected.increment();
	}

	@Override
	public void willAcceptRequest(@NonNull ServerType serverType,
																@Nullable InetSocketAddress remoteAddress,
																@Nullable String requestTarget) {
		requireNonNull(serverType);
	}

	@Override
	public void didAcceptRequest(@NonNull ServerType serverType,
															 @Nullable InetSocketAddress remoteAddress,
															 @Nullable String requestTarget) {
		requireNonNull(serverType);
	}

	@Override
	public void willReadRequest(@NonNull ServerType serverType,
															@Nullable InetSocketAddress remoteAddress,
															@Nullable String requestTarget) {
		requireNonNull(serverType);
	}

	@Override
	public void didReadRequest(@NonNull ServerType serverType,
														 @Nullable InetSocketAddress remoteAddress,
														 @Nullable String requestTarget) {
		requireNonNull(serverType);
	}

	@Override
	public void didFailToReadRequest(@NonNull ServerType serverType,
																	 @Nullable InetSocketAddress remoteAddress,
																	 @Nullable String requestTarget,
																	 @NonNull RequestReadFailureReason reason,
																	 @Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);

		RequestReadFailureKey key = new RequestReadFailureKey(reason);

		if (serverType == ServerType.STANDARD_HTTP)
			counterFor(this.httpRequestReadFailuresByReason, key).increment();
		else if (serverType == ServerType.SSE)
			counterFor(this.sseRequestReadFailuresByReason, key).increment();
	}

	@Override
	public void didFailToAcceptRequest(@NonNull ServerType serverType,
																		 @Nullable InetSocketAddress remoteAddress,
																		 @Nullable String requestTarget,
																		 @NonNull RequestRejectionReason reason,
																		 @Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);

		RequestRejectionKey key = new RequestRejectionKey(reason);

		if (serverType == ServerType.STANDARD_HTTP)
			counterFor(this.httpRequestRejectionsByReason, key).increment();
		else if (serverType == ServerType.SSE)
			counterFor(this.sseRequestRejectionsByReason, key).increment();
	}

	@Override
	public void didRecordTransportFailure(@NonNull ServerType serverType,
																				@NonNull TransportFailureReason reason,
																				@Nullable Throwable throwable) {
		requireNonNull(serverType);
		requireNonNull(reason);

		counterFor(this.transportFailuresByServerTypeAndReason, new TransportFailureKey(serverType, reason)).increment();
	}

	@Override
	public void didStartRequestHandling(@NonNull ServerType serverType,
																		@NonNull Request request,
																		@Nullable ResourceMethod resourceMethod) {
		requireNonNull(serverType);
		requireNonNull(request);

		if (serverType != ServerType.STANDARD_HTTP)
			return;

		RouteContext routeContext = routeFor(resourceMethod);
		HttpMethod method = request.getHttpMethod();

		RequestState state = new RequestState(new IdentityKey<>(request), request.getId(), System.nanoTime(), method,
				routeContext.getRouteType(), routeContext.getRoute());
		RequestState existingState = this.requestsInFlightByIdentity
				.putIfAbsent(state.getIdentityKey(), state);
		if (existingState != null) {
			this.requestStateByThread.set(existingState);
			return;
		}
		registerRequestStateById(state);

		this.activeRequests.increment();
		this.requestStateByThread.set(state);

		long requestBodyBytes = request.getBody()
				.map(body -> (long) body.length)
				.orElse(0L);

		Histogram requestBodyHistogram = histogramFor(this.httpRequestBodyBytesByRoute,
				new HttpServerRouteKey(method, routeContext.getRouteType(), routeContext.getRoute()),
				HTTP_BODY_BYTES_BUCKETS);
		requestBodyHistogram.record(requestBodyBytes);
	}

	@Override
	public void willWriteResponse(@NonNull ServerType serverType,
																@NonNull Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		if (serverType != ServerType.STANDARD_HTTP)
			return;

		RequestState state = requestStateFor(request);

		if (state == null)
			return;

		if (!state.markHandlerDurationRecorded())
			return;

		long elapsedNanos = elapsedNanosSince(state.getStartedAtNanos());
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		HttpServerRouteStatusKey key = new HttpServerRouteStatusKey(state.getMethod(), state.getRouteType(),
				state.getRoute(), statusClass);
		histogramFor(this.httpHandlerDurationByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(elapsedNanos);

		// TTFB is approximated as "time to start writing response"
		histogramFor(this.httpTimeToFirstByteByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(elapsedNanos);
	}

	@Override
	public void didFinishRequestHandling(@NonNull ServerType serverType,
																			 @NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull MarshaledResponse marshaledResponse,
																			 @NonNull Duration duration,
																			 @NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(serverType);
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(duration);
		requireNonNull(throwables);

		if (serverType != ServerType.STANDARD_HTTP)
			return;

		RequestState state = removeRequestState(request);
		if (state == null)
			return;

		this.activeRequests.decrement();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		HttpServerRouteStatusKey key = new HttpServerRouteStatusKey(
				state.getMethod(), state.getRouteType(), state.getRoute(), statusClass);
		histogramFor(this.httpRequestDurationByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(nonNegativeNanos(duration));

		long responseBodyBytes = marshaledResponse.getBodyLength();

		histogramFor(this.httpResponseBodyBytesByRouteStatus, key, HTTP_BODY_BYTES_BUCKETS)
				.record(responseBodyBytes);
	}


	@Override
	public void didEstablishSseConnection(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);

		RouteContext routeContext = routeFor(sseConnection);

		IdentityKey<SseConnection> identityKey = new IdentityKey<>(sseConnection);
		SseConnectionState state = new SseConnectionState(
				routeContext.getRouteType(), routeContext.getRoute(), System.nanoTime());
		if (this.sseConnectionsByIdentity.putIfAbsent(identityKey, state) != null)
			return;

		counterFor(this.sseHandshakesAcceptedByRoute,
				new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute())).increment();
		this.activeSseStreams.increment();
	}

	@Override
	public void didFailToEstablishSseConnection(@NonNull Request request,
																													@Nullable ResourceMethod resourceMethod,
																													SseConnection.@NonNull HandshakeFailureReason reason,
																													@Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(reason);

		RouteContext routeContext = routeFor(resourceMethod);

		counterFor(this.sseHandshakesRejectedByRouteAndReason,
				new SseEventRouteHandshakeFailureKey(routeContext.getRouteType(), routeContext.getRoute(), reason))
				.increment();
	}

	@Override
	public void willWriteSseEvent(@NonNull SseConnection sseConnection,
																			 @NonNull SseEvent sseEvent) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));

		if (state == null)
			return;

		if (state.markFirstEventRecorded()) {
			long elapsedNanos = elapsedNanosSince(state.getEstablishedAtNanos());
			histogramFor(this.sseTimeToFirstEventByRoute,
					new SseEventRouteKey(state.getRouteType(), state.getRoute()),
					SSE_TIME_TO_FIRST_EVENT_BUCKETS_NANOS).record(elapsedNanos);
		}
	}

	@Override
	public void didWriteSseEvent(@NonNull SseConnection sseConnection,
																			@NonNull SseEvent sseEvent,
																			@NonNull Duration writeDuration,
																			@Nullable Duration deliveryLag,
																			@Nullable Integer payloadBytes,
																			@Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);

		if (state != null)
			state.incrementEventsSent();

		histogramFor(this.sseEventWriteDurationByRoute,
				new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(nonNegativeNanos(writeDuration));

		if (deliveryLag != null) {
			long deliveryLagNanos = nonNegativeNanos(deliveryLag);
			histogramFor(this.sseEventDeliveryLagByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didWriteSseComment(@NonNull SseConnection sseConnection,
																						 @NonNull SseComment sseComment,
																						 @NonNull Duration writeDuration,
																						 @Nullable Duration deliveryLag,
																						 @Nullable Integer payloadBytes,
																						 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);
		SseCommentRouteKey key = new SseCommentRouteKey(routeContext.getRouteType(),
				routeContext.getRoute(), sseComment.getCommentType());

		if (deliveryLag != null) {
			long deliveryLagNanos = nonNegativeNanos(deliveryLag);
			histogramFor(this.sseCommentDeliveryLagByRoute,
					key,
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseCommentSizeByRoute,
					key,
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseCommentQueueDepthByRoute,
					key,
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didFailToWriteSseEvent(@NonNull SseConnection sseConnection,
																						@NonNull SseEvent sseEvent,
																						@NonNull Duration writeDuration,
																						@NonNull Throwable throwable,
																						@Nullable Duration deliveryLag,
																						@Nullable Integer payloadBytes,
																						@Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);

		histogramFor(this.sseEventWriteDurationByRoute,
				new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(nonNegativeNanos(writeDuration));

		if (deliveryLag != null) {
			long deliveryLagNanos = nonNegativeNanos(deliveryLag);
			histogramFor(this.sseEventDeliveryLagByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didFailToWriteSseComment(@NonNull SseConnection sseConnection,
																									 @NonNull SseComment sseComment,
																									 @NonNull Duration writeDuration,
																									 @NonNull Throwable throwable,
																									 @Nullable Duration deliveryLag,
																									 @Nullable Integer payloadBytes,
																									 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);
		SseCommentRouteKey key = new SseCommentRouteKey(routeContext.getRouteType(),
				routeContext.getRoute(), sseComment.getCommentType());

		if (deliveryLag != null) {
			long deliveryLagNanos = nonNegativeNanos(deliveryLag);
			histogramFor(this.sseCommentDeliveryLagByRoute,
					key,
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseCommentSizeByRoute,
					key,
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseCommentQueueDepthByRoute,
					key,
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didDropSseEvent(@NonNull SseConnection sseConnection,
																		 @NonNull SseEvent sseEvent,
																		 @NonNull SseEventDropReason reason,
																		 @Nullable Integer payloadBytes,
																		 @Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseEvent);
		requireNonNull(reason);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);

		counterFor(this.sseEventDropsByRouteAndReason,
				new SseEventRouteDropKey(routeContext.getRouteType(), routeContext.getRoute(), reason))
				.increment();
	}

	@Override
	public void didDropSseComment(@NonNull SseConnection sseConnection,
																						@NonNull SseComment sseComment,
																						@NonNull SseEventDropReason reason,
																						@Nullable Integer payloadBytes,
																						@Nullable Integer queueDepth) {
		requireNonNull(sseConnection);
		requireNonNull(sseComment);
		requireNonNull(reason);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(sseConnection));
		RouteContext routeContext = routeContextFor(state, sseConnection);

		counterFor(this.sseCommentDropsByRouteAndReason,
				new SseCommentRouteDropKey(routeContext.getRouteType(), routeContext.getRoute(),
						sseComment.getCommentType(), reason))
				.increment();
	}

	@Override
	public void didBroadcastSseEvent(@NonNull ResourcePathDeclaration route,
																					int attempted,
																					int enqueued,
																					int dropped) {
		requireNonNull(route);

		if (attempted > 0) {
			counterFor(this.sseEventEnqueueOutcomesByRoute,
					new SseEventRouteEnqueueOutcomeKey(RouteType.MATCHED, route, SseEventEnqueueOutcome.ATTEMPTED))
					.add(attempted);
		}

		if (enqueued > 0) {
			counterFor(this.sseEventEnqueueOutcomesByRoute,
					new SseEventRouteEnqueueOutcomeKey(RouteType.MATCHED, route, SseEventEnqueueOutcome.ENQUEUED))
					.add(enqueued);
		}

		if (dropped > 0) {
			counterFor(this.sseEventEnqueueOutcomesByRoute,
					new SseEventRouteEnqueueOutcomeKey(RouteType.MATCHED, route, SseEventEnqueueOutcome.DROPPED))
					.add(dropped);
		}
	}

	@Override
	public void didBroadcastSseComment(@NonNull ResourcePathDeclaration route,
																								 SseComment.@NonNull CommentType commentType,
																								 int attempted,
																								 int enqueued,
																								 int dropped) {
		requireNonNull(route);
		requireNonNull(commentType);

		if (attempted > 0) {
			counterFor(this.sseCommentEnqueueOutcomesByRoute,
					new SseCommentRouteEnqueueOutcomeKey(RouteType.MATCHED, route, commentType, SseEventEnqueueOutcome.ATTEMPTED))
					.add(attempted);
		}

		if (enqueued > 0) {
			counterFor(this.sseCommentEnqueueOutcomesByRoute,
					new SseCommentRouteEnqueueOutcomeKey(RouteType.MATCHED, route, commentType, SseEventEnqueueOutcome.ENQUEUED))
					.add(enqueued);
		}

		if (dropped > 0) {
			counterFor(this.sseCommentEnqueueOutcomesByRoute,
					new SseCommentRouteEnqueueOutcomeKey(RouteType.MATCHED, route, commentType, SseEventEnqueueOutcome.DROPPED))
					.add(dropped);
		}
	}

	@Override
	public void didTerminateSseConnection(@NonNull SseConnection sseConnection,
																			@NonNull StreamTermination termination) {
		requireNonNull(sseConnection);
		requireNonNull(termination);

		SseConnectionState state = this.sseConnectionsByIdentity.remove(
				new IdentityKey<>(sseConnection));
		if (state == null)
			return;

		this.activeSseStreams.decrement();

		SseStreamRouteTerminationKey key = new SseStreamRouteTerminationKey(
				state.getRouteType(), state.getRoute(), termination.getReason());

		histogramFor(this.sseStreamDurationByRouteAndReason, key, SSE_STREAM_DURATION_BUCKETS_NANOS)
				.record(elapsedNanosSince(state.getEstablishedAtNanos()));
	}

	@Override
	public void didRecordMcpMetricsEvent(@NonNull McpMetricsEvent event) {
		requireNonNull(event);
		if (event instanceof McpMetricsEvent.ServerStarted) {
			this.includeMcpServerMetrics.set(true);
			this.mcpServerStarts.increment();
		} else if (event instanceof McpMetricsEvent.RequestAccepted) {
			this.includeMcpRequestBoundaryMetrics.set(true);
			this.mcpRequestsAccepted.increment();
		} else if (event instanceof McpMetricsEvent.RequestRejected) {
			this.includeMcpRequestBoundaryMetrics.set(true);
			this.mcpRequestsRejected.increment();
		} else if (event instanceof McpMetricsEvent.RequestStarted) {
			this.includeMcpRequestLifecycleMetrics.set(true);
			this.mcpActiveRequests.incrementAndGet();
		} else if (event instanceof McpMetricsEvent.RequestFinished requestFinished) {
			this.includeMcpRequestLifecycleMetrics.set(true);
			decrementIfPositive(this.mcpActiveRequests);
			McpMetricsSnapshot.RequestOutcomeKey key =
					McpMetricsSnapshot.RequestOutcomeKey.fromDimensions(
							requestFinished.getEndpointPath(),
							requestFinished.getJsonRpcMethod(),
							requestFinished.getOutcome());
			counterFor(this.mcpRequestsByOutcome, key).increment();
			histogramFor(this.mcpRequestDurationsByOutcome, key,
					HTTP_LATENCY_BUCKETS_NANOS)
					.record(nonNegativeNanos(requestFinished.getDuration()));
		} else if (event instanceof McpMetricsEvent.RequestStreamOpened) {
			this.includeMcpRequestStreamLifecycleMetrics.set(true);
			this.mcpActiveRequestStreams.incrementAndGet();
		} else if (event instanceof McpMetricsEvent.RequestStreamClosed requestStreamClosed) {
			this.includeMcpRequestStreamLifecycleMetrics.set(true);
			decrementIfPositive(this.mcpActiveRequestStreams);
			McpMetricsSnapshot.RequestStreamTerminationKey key =
					McpMetricsSnapshot.RequestStreamTerminationKey.fromDimensions(
							requestStreamClosed.getEndpointPath(),
							requestStreamClosed.getJsonRpcMethod(),
							requestStreamClosed.getReason());
			histogramFor(this.mcpRequestStreamDurationsByReason, key,
					SSE_STREAM_DURATION_BUCKETS_NANOS)
					.record(nonNegativeNanos(requestStreamClosed.getDuration()));
		} else if (event instanceof McpMetricsEvent.SubscriptionOpened) {
			this.includeMcpSubscriptionLifecycleMetrics.set(true);
			this.mcpActiveSubscriptions.incrementAndGet();
		} else if (event instanceof McpMetricsEvent.SubscriptionClosed subscriptionClosed) {
			this.includeMcpSubscriptionLifecycleMetrics.set(true);
			decrementIfPositive(this.mcpActiveSubscriptions);
			McpMetricsSnapshot.SubscriptionTerminationKey key =
					McpMetricsSnapshot.SubscriptionTerminationKey.fromDimensions(
							subscriptionClosed.getEndpointPath(),
							subscriptionClosed.getReason());
			histogramFor(this.mcpSubscriptionDurationsByReason, key,
					SSE_STREAM_DURATION_BUCKETS_NANOS)
					.record(nonNegativeNanos(subscriptionClosed.getDuration()));
		} else if (event instanceof McpMetricsEvent.CancelationSignaled
				cancelationSignaled) {
			McpMetricsSnapshot.EndpointMethodKey key =
					McpMetricsSnapshot.EndpointMethodKey.fromDimensions(
							cancelationSignaled.getEndpointPath(),
							cancelationSignaled.getJsonRpcMethod());
			counterFor(this.mcpCancelationsSignaledByEndpointAndMethod, key)
					.increment();
		} else if (event instanceof McpMetricsEvent.ProgressEmitted
				progressEmitted) {
			McpMetricsSnapshot.EndpointMethodKey key =
					McpMetricsSnapshot.EndpointMethodKey.fromDimensions(
							progressEmitted.getEndpointPath(),
							progressEmitted.getJsonRpcMethod());
			counterFor(this.mcpProgressEmittedByEndpointAndMethod, key)
					.increment();
		} else if (event instanceof McpMetricsEvent.KeepAliveEmitted) {
			this.includeMcpKeepAliveMetrics.set(true);
			this.mcpKeepAlivesEmitted.increment();
		} else if (event instanceof McpMetricsEvent.ProtocolError protocolError) {
			counterFor(this.mcpProtocolErrorsByCode, protocolError.getCode())
					.increment();
		} else if (event instanceof McpMetricsEvent.UnknownMirroredHeader
				unknownMirroredHeader) {
			McpMetricsSnapshot.EndpointMethodKey key =
					McpMetricsSnapshot.EndpointMethodKey.fromDimensions(
							unknownMirroredHeader.getEndpointPath(),
							unknownMirroredHeader.getJsonRpcMethod());
			counterFor(this.mcpUnknownMirroredHeadersByEndpointAndMethod, key)
					.increment();
		} else if (event instanceof McpMetricsEvent.ConnectionAccepted) {
			this.includeMcpTransportMetrics.set(true);
			this.mcpConnectionsAccepted.increment();
		} else if (event instanceof McpMetricsEvent.ConnectionRejected) {
			this.includeMcpTransportMetrics.set(true);
			this.mcpConnectionsRejected.increment();
		} else if (event instanceof McpMetricsEvent.TransportFailure transportFailure) {
			this.includeMcpTransportMetrics.set(true);
			requireNonNull(this.mcpTransportFailuresByReason.get(
					transportFailure.getReason())).increment();
		} else if (event instanceof McpMetricsEvent.HandlerExecutionStarted) {
			this.includeMcpHandlerMetrics.set(true);
			this.mcpActiveHandlerExecutions.incrementAndGet();
		} else if (event instanceof McpMetricsEvent.HandlerExecutionFinished) {
			this.includeMcpHandlerMetrics.set(true);
			decrementIfPositive(this.mcpActiveHandlerExecutions);
		} else if (event instanceof McpMetricsEvent.HandlerQueued) {
			this.includeMcpHandlerMetrics.set(true);
			this.mcpHandlerQueueDepth.incrementAndGet();
		} else if (event instanceof McpMetricsEvent.HandlerDequeued) {
			this.includeMcpHandlerMetrics.set(true);
			decrementIfPositive(this.mcpHandlerQueueDepth);
		} else if (event instanceof McpMetricsEvent.HandlerCapacityRejected) {
			this.includeMcpHandlerMetrics.set(true);
			this.mcpHandlerCapacityRejections.increment();
		} else if (event instanceof McpMetricsEvent.ServerStopped serverStopped) {
			this.includeMcpServerMetrics.set(true);
			requireNonNull(this.mcpServerStopsByDisposition.get(
					serverStopped.getShutdownComponentDisposition()))
					.increment();
		}
	}

	@Override
	@NonNull
	public Optional<Snapshot> snapshot() {
		return Optional.of(Snapshot.builder()
				.activeRequests(getActiveRequests())
				.activeSseStreams(getActiveSseStreams())
				.mcpMetrics(snapshotMcpMetrics())
				.httpConnectionsAccepted(getHttpConnectionsAccepted())
				.httpConnectionsRejected(getHttpConnectionsRejected())
				.sseConnectionsAccepted(getSseConnectionsAccepted())
				.sseConnectionsRejected(getSseConnectionsRejected())
				.transportFailures(snapshotTransportFailures())
				.httpRequestReadFailures(snapshotHttpRequestReadFailures())
				.httpRequestRejections(snapshotHttpRequestRejections())
				.sseRequestReadFailures(snapshotSseRequestReadFailures())
				.sseRequestRejections(snapshotSseRequestRejections())
				.sseHandshakesAccepted(snapshotSseHandshakesAccepted())
				.sseHandshakesRejected(snapshotSseHandshakesRejected())
				.sseEventEnqueueOutcomes(snapshotSseEventEnqueueOutcomes())
				.sseCommentEnqueueOutcomes(snapshotSseCommentEnqueueOutcomes())
				.sseEventDrops(snapshotSseEventDrops())
				.sseCommentDrops(snapshotSseCommentDrops())
				.httpRequestDurations(snapshotHttpRequestDurations())
				.httpHandlerDurations(snapshotHttpHandlerDurations())
				.httpTimeToFirstByte(snapshotHttpTimeToFirstByte())
				.httpRequestBodyBytes(snapshotHttpRequestBodyBytes())
				.httpResponseBodyBytes(snapshotHttpResponseBodyBytes())
				.sseTimeToFirstEvent(snapshotSseTimeToFirstEvent())
				.sseEventWriteDurations(snapshotSseEventWriteDurations())
				.sseEventDeliveryLag(snapshotSseEventDeliveryLag())
				.sseEventSizes(snapshotSseEventSizes())
				.sseQueueDepth(snapshotSseQueueDepth())
				.sseCommentDeliveryLag(snapshotSseCommentDeliveryLag())
				.sseCommentSizes(snapshotSseCommentSizes())
				.sseCommentQueueDepth(snapshotSseCommentQueueDepth())
				.sseStreamDurations(snapshotSseStreamDurations())
				.build());
	}

	@Override
	@NonNull
	public Optional<String> snapshotText(@NonNull SnapshotTextOptions options) {
		requireNonNull(options);

		Snapshot snapshot = snapshot().orElse(null);

		if (snapshot == null)
			return Optional.empty();

		StringBuilder sb = new StringBuilder(8192);

		appendGauge(sb, "soklet_http_requests_active", "Currently active HTTP requests",
				snapshot.getActiveRequests(), options);
		appendCounter(sb, "soklet_http_connections_accepted_total", "Total accepted HTTP connections",
				snapshot.getHttpConnectionsAccepted(), options);
		appendCounter(sb, "soklet_http_connections_rejected_total", "Total rejected HTTP connections",
				snapshot.getHttpConnectionsRejected(), options);
		appendTransportFailures(sb, snapshot.getTransportFailures(),
				snapshot.getMcpMetrics().getTransportFailures(), options);
		appendCounter(sb, "soklet_http_request_read_failures_total", "Total HTTP request read failures",
				snapshot.getHttpRequestReadFailures(), DefaultMetricsCollector::labelsForRequestReadFailureKey, options);
		appendCounter(sb, "soklet_http_requests_rejected_total", "Total HTTP requests rejected before handling",
				snapshot.getHttpRequestRejections(), DefaultMetricsCollector::labelsForRequestRejectionKey, options);
		if (this.includeMcpServerMetrics.get())
			appendCounter(sb, "soklet_mcp_server_starts_total",
					"Total successful MCP server starts",
					snapshot.getMcpMetrics().getServerStarts(), options);
		if (this.includeMcpRequestBoundaryMetrics.get()) {
			appendCounter(sb, "soklet_mcp_requests_accepted_total",
					"Total MCP requests accepted by the bounded protocol processor",
					snapshot.getMcpMetrics().getRequestsAccepted(), options);
			appendCounter(sb, "soklet_mcp_requests_rejected_total",
					"Total MCP requests rejected before admitted semantic handling",
					snapshot.getMcpMetrics().getRequestsRejected(), options);
		}
		if (this.includeMcpRequestLifecycleMetrics.get()) {
			appendGauge(sb, "soklet_mcp_requests_active",
					"Currently active admitted MCP requests",
					snapshot.getMcpMetrics().getActiveRequests(), options);
			appendCounter(sb, "soklet_mcp_requests_total",
					"Total completed MCP requests",
					snapshot.getMcpMetrics().getRequests(),
					DefaultMetricsCollector::labelsForMcpRequestOutcomeKey,
					options);
			appendHistogram(sb, "soklet_mcp_request_duration_nanos",
					"MCP request duration in nanoseconds",
					snapshot.getMcpMetrics().getRequestDurations(),
					DefaultMetricsCollector::labelsForMcpRequestOutcomeKey,
					options);
		}
		if (this.includeMcpRequestStreamLifecycleMetrics.get()) {
			appendGauge(sb, "soklet_mcp_request_streams_active",
					"Currently active MCP request streams",
					snapshot.getMcpMetrics().getActiveRequestStreams(), options);
			appendHistogram(sb, "soklet_mcp_request_stream_duration_nanos",
					"MCP request-stream duration in nanoseconds",
					snapshot.getMcpMetrics().getRequestStreamDurations(),
					DefaultMetricsCollector::labelsForMcpRequestStreamTerminationKey,
					options);
		}
		if (this.includeMcpSubscriptionLifecycleMetrics.get()) {
			appendGauge(sb, "soklet_mcp_subscriptions_active",
					"Currently active MCP subscriptions",
					snapshot.getMcpMetrics().getActiveSubscriptions(), options);
			appendHistogram(sb, "soklet_mcp_subscription_duration_nanos",
					"MCP subscription duration in nanoseconds",
					snapshot.getMcpMetrics().getSubscriptionDurations(),
					DefaultMetricsCollector::labelsForMcpSubscriptionTerminationKey,
					options);
		}
		appendCounter(sb, "soklet_mcp_cancelations_signaled_total",
				"Total cooperative MCP request cancelations signaled by endpoint and method",
				snapshot.getMcpMetrics().getCancelationsSignaled(),
				DefaultMetricsCollector::labelsForMcpEndpointMethodKey, options);
		appendCounter(sb, "soklet_mcp_progress_emitted_total",
				"Total MCP progress notifications accepted for delivery by endpoint and method",
				snapshot.getMcpMetrics().getProgressEmitted(),
				DefaultMetricsCollector::labelsForMcpEndpointMethodKey, options);
		if (this.includeMcpKeepAliveMetrics.get())
			appendCounter(sb, "soklet_mcp_keep_alives_emitted_total",
					"Total MCP keep-alive comments accepted for delivery",
					snapshot.getMcpMetrics().getKeepAlivesEmitted(), options);
		appendCounter(sb, "soklet_mcp_protocol_errors_total",
				"Total client-visible MCP protocol errors by fixed code",
				snapshot.getMcpMetrics().getProtocolErrors(),
				DefaultMetricsCollector::labelsForMcpProtocolErrorCode, options);
		appendCounter(sb, "soklet_mcp_unknown_mirrored_headers_total",
				"Total unknown MCP mirrored-header occurrences by endpoint and method",
				snapshot.getMcpMetrics().getUnknownMirroredHeaders(),
				DefaultMetricsCollector::labelsForMcpEndpointMethodKey, options);
		if (this.includeMcpTransportMetrics.get()) {
			appendCounter(sb, "soklet_mcp_connections_accepted_total",
					"Total accepted MCP connections admitted within the connection-capacity bound",
					snapshot.getMcpMetrics().getConnectionsAccepted(), options);
			appendCounter(sb, "soklet_mcp_connections_rejected_total",
					"Total MCP connections rejected because the connection-capacity bound was full",
					snapshot.getMcpMetrics().getConnectionsRejected(), options);
		}
		if (this.includeMcpHandlerMetrics.get()) {
			appendGauge(sb, "soklet_mcp_handler_executions_active",
					"Currently occupied MCP handler-execution slots",
					snapshot.getMcpMetrics().getActiveHandlerExecutions(), options);
			appendGauge(sb, "soklet_mcp_handler_queue_depth",
					"MCP application requests waiting for a handler slot",
					snapshot.getMcpMetrics().getHandlerQueueDepth(), options);
			appendCounter(sb, "soklet_mcp_handler_capacity_rejections_total",
					"Total MCP requests rejected because the handler queue was full",
					snapshot.getMcpMetrics().getHandlerCapacityRejections(), options);
		}
		appendCounter(sb, "soklet_mcp_shutdowns_total", "Total MCP server shutdowns by outcome",
				snapshot.getMcpMetrics().getServerStops(),
				DefaultMetricsCollector::labelsForMcpShutdownDisposition, options);

		appendHistogram(sb, "soklet_http_request_duration_nanos", "HTTP request duration in nanoseconds",
				snapshot.getHttpRequestDurations(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_handler_duration_nanos", "HTTP handler duration in nanoseconds",
				snapshot.getHttpHandlerDurations(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_ttfb_nanos",
				"HTTP handler-start to response-write-start duration in nanoseconds",
				snapshot.getHttpTimeToFirstByte(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_request_body_bytes", "HTTP request body size in bytes",
				snapshot.getHttpRequestBodyBytes(), DefaultMetricsCollector::labelsForHttpRouteKey, options);
		appendHistogram(sb, "soklet_http_response_body_bytes", "HTTP response body size in bytes",
				snapshot.getHttpResponseBodyBytes(), DefaultMetricsCollector::labelsForHttpStatusKey, options);


		if (this.includeSseMetrics.get()) {
			appendCounter(sb, "soklet_sse_connections_accepted_total", "Total accepted SSE connections",
					snapshot.getSseConnectionsAccepted(), options);
			appendCounter(sb, "soklet_sse_connections_rejected_total", "Total rejected SSE connections",
					snapshot.getSseConnectionsRejected(), options);
			appendCounter(sb, "soklet_sse_request_read_failures_total", "Total SSE request read failures",
					snapshot.getSseRequestReadFailures(), DefaultMetricsCollector::labelsForRequestReadFailureKey, options);
			appendCounter(sb, "soklet_sse_requests_rejected_total", "Total SSE requests rejected before handling",
					snapshot.getSseRequestRejections(), DefaultMetricsCollector::labelsForRequestRejectionKey, options);
			appendGauge(sb, "soklet_sse_streams_active", "Currently active SSE streams",
					snapshot.getActiveSseStreams(), options);
			appendCounter(sb, "soklet_sse_handshakes_accepted_total", "Total accepted SSE handshakes",
					snapshot.getSseHandshakesAccepted(), DefaultMetricsCollector::labelsForSseRouteKey, options);
			appendCounter(sb, "soklet_sse_handshakes_rejected_total", "Total rejected SSE handshakes",
					snapshot.getSseHandshakesRejected(), DefaultMetricsCollector::labelsForSseHandshakeFailureKey, options);
			appendCounter(sb, "soklet_sse_event_broadcasts_total", "Total SSE event enqueue outcomes",
					snapshot.getSseEventEnqueueOutcomes(), DefaultMetricsCollector::labelsForSseEnqueueOutcomeKey, options);
			appendCounter(sb, "soklet_sse_comment_broadcasts_total", "Total SSE comment enqueue outcomes",
					snapshot.getSseCommentEnqueueOutcomes(), DefaultMetricsCollector::labelsForSseCommentEnqueueOutcomeKey, options);
			appendCounter(sb, "soklet_sse_events_dropped_total", "Total SSE events dropped before enqueue",
					snapshot.getSseEventDrops(), DefaultMetricsCollector::labelsForSseDropKey, options);
			appendCounter(sb, "soklet_sse_comments_dropped_total", "Total SSE comments dropped before enqueue",
					snapshot.getSseCommentDrops(), DefaultMetricsCollector::labelsForSseCommentDropKey, options);

			appendHistogram(sb, "soklet_sse_time_to_first_event_nanos", "SSE time to first event in nanoseconds",
					snapshot.getSseTimeToFirstEvent(), DefaultMetricsCollector::labelsForSseRouteKey, options);
			appendHistogram(sb, "soklet_sse_event_write_duration_nanos", "SSE event write duration in nanoseconds",
					snapshot.getSseEventWriteDurations(), DefaultMetricsCollector::labelsForSseRouteKey, options);
			appendHistogram(sb, "soklet_sse_event_delivery_lag_nanos", "SSE event delivery lag in nanoseconds",
					snapshot.getSseEventDeliveryLag(), DefaultMetricsCollector::labelsForSseRouteKey, options);
			appendHistogram(sb, "soklet_sse_event_size_bytes", "SSE event size in bytes",
					snapshot.getSseEventSizes(), DefaultMetricsCollector::labelsForSseRouteKey, options);
			appendHistogram(sb, "soklet_sse_queue_depth", "SSE queue depth",
					snapshot.getSseQueueDepth(), DefaultMetricsCollector::labelsForSseRouteKey, options);

			appendHistogram(sb, "soklet_sse_comment_delivery_lag_nanos", "SSE comment delivery lag in nanoseconds",
					snapshot.getSseCommentDeliveryLag(), DefaultMetricsCollector::labelsForSseCommentKey, options);
			appendHistogram(sb, "soklet_sse_comment_size_bytes", "SSE comment size in bytes",
					snapshot.getSseCommentSizes(), DefaultMetricsCollector::labelsForSseCommentKey, options);
			appendHistogram(sb, "soklet_sse_comment_queue_depth", "SSE comment queue depth",
					snapshot.getSseCommentQueueDepth(), DefaultMetricsCollector::labelsForSseCommentKey, options);

			appendHistogram(sb, "soklet_sse_stream_duration_nanos", "SSE stream duration in nanoseconds",
					snapshot.getSseStreamDurations(), DefaultMetricsCollector::labelsForSseStreamTerminationKey, options);
		}

		if (options.getMetricsFormat() == MetricsFormat.OPEN_METRICS_1_0)
			sb.append("# EOF\n");

		return Optional.of(sb.toString());
	}

	long getActiveRequests() {
		return this.activeRequests.sum();
	}

	long getRequestsInFlightByIdentityCount() {
		return this.requestsInFlightByIdentity.size();
	}

	long getRequestsInFlightByIdCount() {
		return this.requestsInFlightById.values().stream()
				.mapToLong(RequestIdStateBucket::size)
				.sum();
	}

	long getActiveSseStreams() {
		return this.activeSseStreams.sum();
	}


	long getHttpConnectionsAccepted() {
		return this.httpConnectionsAccepted.sum();
	}

	long getHttpConnectionsRejected() {
		return this.httpConnectionsRejected.sum();
	}

	long getSseConnectionsAccepted() {
		return this.sseConnectionsAccepted.sum();
	}

	long getSseConnectionsRejected() {
		return this.sseConnectionsRejected.sum();
	}


	@NonNull
	Map<@NonNull TransportFailureKey, @NonNull Long> snapshotTransportFailures() {
		return snapshotCounterMap(this.transportFailuresByServerTypeAndReason);
	}

	@NonNull
	Map<@NonNull RequestReadFailureKey, @NonNull Long> snapshotHttpRequestReadFailures() {
		return snapshotCounterMap(this.httpRequestReadFailuresByReason);
	}

	@NonNull
	Map<@NonNull RequestRejectionKey, @NonNull Long> snapshotHttpRequestRejections() {
		return snapshotCounterMap(this.httpRequestRejectionsByReason);
	}

	@NonNull
	Map<@NonNull RequestReadFailureKey, @NonNull Long> snapshotSseRequestReadFailures() {
		return snapshotCounterMap(this.sseRequestReadFailuresByReason);
	}

	@NonNull
	Map<@NonNull RequestRejectionKey, @NonNull Long> snapshotSseRequestRejections() {
		return snapshotCounterMap(this.sseRequestRejectionsByReason);
	}


	@NonNull
	Map<@NonNull HttpServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpRequestDurations() {
		return snapshotMap(this.httpRequestDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull HttpServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpHandlerDurations() {
		return snapshotMap(this.httpHandlerDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull HttpServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpTimeToFirstByte() {
		return snapshotMap(this.httpTimeToFirstByteByRouteStatus);
	}

	@NonNull
	Map<@NonNull HttpServerRouteKey, @NonNull HistogramSnapshot> snapshotHttpRequestBodyBytes() {
		return snapshotMap(this.httpRequestBodyBytesByRoute);
	}

	@NonNull
	Map<@NonNull HttpServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpResponseBodyBytes() {
		return snapshotMap(this.httpResponseBodyBytesByRouteStatus);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull HistogramSnapshot> snapshotSseTimeToFirstEvent() {
		return snapshotMap(this.sseTimeToFirstEventByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventWriteDurations() {
		return snapshotMap(this.sseEventWriteDurationByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventDeliveryLag() {
		return snapshotMap(this.sseEventDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventSizes() {
		return snapshotMap(this.sseEventSizeByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull HistogramSnapshot> snapshotSseQueueDepth() {
		return snapshotMap(this.sseQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull SseCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentDeliveryLag() {
		return snapshotMap(this.sseCommentDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull SseCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentSizes() {
		return snapshotMap(this.sseCommentSizeByRoute);
	}

	@NonNull
	Map<@NonNull SseCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentQueueDepth() {
		return snapshotMap(this.sseCommentQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteKey, @NonNull Long> snapshotSseHandshakesAccepted() {
		return snapshotCounterMap(this.sseHandshakesAcceptedByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteHandshakeFailureKey, @NonNull Long> snapshotSseHandshakesRejected() {
		return snapshotCounterMap(this.sseHandshakesRejectedByRouteAndReason);
	}

	@NonNull
	Map<@NonNull SseEventRouteEnqueueOutcomeKey, @NonNull Long> snapshotSseEventEnqueueOutcomes() {
		return snapshotCounterMap(this.sseEventEnqueueOutcomesByRoute);
	}

	@NonNull
	Map<@NonNull SseCommentRouteEnqueueOutcomeKey, @NonNull Long> snapshotSseCommentEnqueueOutcomes() {
		return snapshotCounterMap(this.sseCommentEnqueueOutcomesByRoute);
	}

	@NonNull
	Map<@NonNull SseEventRouteDropKey, @NonNull Long> snapshotSseEventDrops() {
		return snapshotCounterMap(this.sseEventDropsByRouteAndReason);
	}

	@NonNull
	Map<@NonNull SseCommentRouteDropKey, @NonNull Long> snapshotSseCommentDrops() {
		return snapshotCounterMap(this.sseCommentDropsByRouteAndReason);
	}

	@NonNull
	Map<@NonNull SseStreamRouteTerminationKey, @NonNull HistogramSnapshot> snapshotSseStreamDurations() {
		return snapshotMap(this.sseStreamDurationByRouteAndReason);
	}

	@NonNull
	McpMetricsSnapshot snapshotMcpMetrics() {
		EnumMap<ShutdownComponentDisposition, Long> serverStops =
				new EnumMap<>(ShutdownComponentDisposition.class);
		this.mcpServerStopsByDisposition.forEach((disposition, counter) -> {
			long count = counter.sum();
			if (count != 0L)
				serverStops.put(disposition, count);
		});
		EnumMap<TransportFailureReason, Long> transportFailures =
				new EnumMap<>(TransportFailureReason.class);
		this.mcpTransportFailuresByReason.forEach((reason, counter) -> {
			long count = counter.sum();
			if (count != 0L)
				transportFailures.put(reason, count);
		});
		long activeHandlerExecutions = this.mcpActiveHandlerExecutions.get();
		long handlerQueueDepth = this.mcpHandlerQueueDepth.get();
		long handlerCapacityRejections =
				this.mcpHandlerCapacityRejections.sum();
		long connectionsAccepted = this.mcpConnectionsAccepted.sum();
		long connectionsRejected = this.mcpConnectionsRejected.sum();
		long serverStarts = this.mcpServerStarts.sum();
		long requestsAccepted = this.mcpRequestsAccepted.sum();
		long requestsRejected = this.mcpRequestsRejected.sum();
		long activeRequests = this.mcpActiveRequests.get();
		Map<McpMetricsSnapshot.RequestOutcomeKey, Long> requests =
				snapshotCounterMap(this.mcpRequestsByOutcome);
		Map<McpMetricsSnapshot.RequestOutcomeKey, HistogramSnapshot>
				requestDurations = snapshotMap(this.mcpRequestDurationsByOutcome);
		long activeRequestStreams = this.mcpActiveRequestStreams.get();
		Map<McpMetricsSnapshot.RequestStreamTerminationKey, HistogramSnapshot>
				requestStreamDurations =
				snapshotMap(this.mcpRequestStreamDurationsByReason);
		long activeSubscriptions = this.mcpActiveSubscriptions.get();
		Map<McpMetricsSnapshot.SubscriptionTerminationKey, HistogramSnapshot>
				subscriptionDurations =
				snapshotMap(this.mcpSubscriptionDurationsByReason);
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> cancelationsSignaled =
				snapshotCounterMap(this.mcpCancelationsSignaledByEndpointAndMethod);
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> progressEmitted =
				snapshotCounterMap(this.mcpProgressEmittedByEndpointAndMethod);
		long keepAlivesEmitted = this.mcpKeepAlivesEmitted.sum();
		Map<Integer, Long> protocolErrors =
				snapshotCounterMap(this.mcpProtocolErrorsByCode);
		Map<McpMetricsSnapshot.EndpointMethodKey, Long> unknownMirroredHeaders =
				snapshotCounterMap(
						this.mcpUnknownMirroredHeadersByEndpointAndMethod);
		if (activeHandlerExecutions == 0L && handlerQueueDepth == 0L
				&& handlerCapacityRejections == 0L && serverStops.isEmpty()
				&& connectionsAccepted == 0L && connectionsRejected == 0L
				&& transportFailures.isEmpty() && serverStarts == 0L
				&& requestsAccepted == 0L && requestsRejected == 0L
				&& activeRequests == 0L && requests.isEmpty()
				&& requestDurations.isEmpty() && activeRequestStreams == 0L
				&& requestStreamDurations.isEmpty() && activeSubscriptions == 0L
				&& subscriptionDurations.isEmpty()
				&& cancelationsSignaled.isEmpty() && progressEmitted.isEmpty()
				&& keepAlivesEmitted == 0L && protocolErrors.isEmpty()
				&& unknownMirroredHeaders.isEmpty())
			return McpMetricsSnapshot.emptyInstance();
		return McpMetricsSnapshot.builder()
				.activeHandlerExecutions(activeHandlerExecutions)
				.handlerQueueDepth(handlerQueueDepth)
				.handlerCapacityRejections(handlerCapacityRejections)
				.serverStops(serverStops)
				.connectionsAccepted(connectionsAccepted)
				.connectionsRejected(connectionsRejected)
				.transportFailures(transportFailures)
				.serverStarts(serverStarts)
				.requestsAccepted(requestsAccepted)
				.requestsRejected(requestsRejected)
				.activeRequests(activeRequests)
				.requests(requests)
				.requestDurations(requestDurations)
				.activeRequestStreams(activeRequestStreams)
				.requestStreamDurations(requestStreamDurations)
				.activeSubscriptions(activeSubscriptions)
				.subscriptionDurations(subscriptionDurations)
				.cancelationsSignaled(cancelationsSignaled)
				.progressEmitted(progressEmitted)
				.keepAlivesEmitted(keepAlivesEmitted)
				.protocolErrors(protocolErrors)
				.unknownMirroredHeaders(unknownMirroredHeaders)
				.build();
	}


	@Override
	public void reset() {
		// Live HTTP, SSE, and MCP gauges describe current runtime state rather
		// than cumulative observations. Preserve them and their identity
		// bookkeeping across reset so balanced terminal transitions cannot
		// underflow the new collection window.
		this.httpConnectionsAccepted.reset();
		this.httpConnectionsRejected.reset();
		this.sseConnectionsAccepted.reset();
		this.sseConnectionsRejected.reset();
		this.mcpHandlerCapacityRejections.reset();
		this.mcpServerStopsByDisposition.values().forEach(LongAdder::reset);
		this.mcpConnectionsAccepted.reset();
		this.mcpConnectionsRejected.reset();
		this.mcpTransportFailuresByReason.values().forEach(LongAdder::reset);
		this.mcpServerStarts.reset();
		this.mcpRequestsAccepted.reset();
		this.mcpRequestsRejected.reset();
		this.mcpRequestsByOutcome.clear();
		this.mcpRequestDurationsByOutcome.clear();
		this.mcpRequestStreamDurationsByReason.clear();
		this.mcpSubscriptionDurationsByReason.clear();
		this.mcpCancelationsSignaledByEndpointAndMethod.clear();
		this.mcpProgressEmittedByEndpointAndMethod.clear();
		this.mcpKeepAlivesEmitted.reset();
		this.mcpProtocolErrorsByCode.clear();
		this.mcpUnknownMirroredHeadersByEndpointAndMethod.clear();
		resetCounterMap(this.transportFailuresByServerTypeAndReason);
		resetCounterMap(this.httpRequestReadFailuresByReason);
		resetCounterMap(this.httpRequestRejectionsByReason);
		resetCounterMap(this.sseRequestReadFailuresByReason);
		resetCounterMap(this.sseRequestRejectionsByReason);
		resetCounterMap(this.sseHandshakesAcceptedByRoute);
		resetCounterMap(this.sseHandshakesRejectedByRouteAndReason);
		resetCounterMap(this.sseEventEnqueueOutcomesByRoute);
		resetCounterMap(this.sseCommentEnqueueOutcomesByRoute);
		resetCounterMap(this.sseEventDropsByRouteAndReason);
		resetCounterMap(this.sseCommentDropsByRouteAndReason);
		resetMap(this.httpRequestDurationByRouteStatus);
		resetMap(this.httpHandlerDurationByRouteStatus);
		resetMap(this.httpTimeToFirstByteByRouteStatus);
		resetMap(this.httpRequestBodyBytesByRoute);
		resetMap(this.httpResponseBodyBytesByRouteStatus);
		resetMap(this.sseTimeToFirstEventByRoute);
		resetMap(this.sseEventWriteDurationByRoute);
		resetMap(this.sseEventDeliveryLagByRoute);
		resetMap(this.sseEventSizeByRoute);
		resetMap(this.sseQueueDepthByRoute);
		resetMap(this.sseCommentDeliveryLagByRoute);
		resetMap(this.sseCommentSizeByRoute);
		resetMap(this.sseCommentQueueDepthByRoute);
		resetMap(this.sseStreamDurationByRouteAndReason);
	}

	@NonNull
	private static RouteContext routeFor(@Nullable ResourceMethod resourceMethod) {
		if (resourceMethod == null)
			return new RouteContext(RouteType.UNMATCHED, null);

		return new RouteContext(RouteType.MATCHED, resourceMethod.getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeFor(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);
		return new RouteContext(RouteType.MATCHED, sseConnection.getResourceMethod().getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeContextFor(@Nullable SseConnectionState state,
																							@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);

		if (state != null)
			return new RouteContext(state.getRouteType(), state.getRoute());

		return routeFor(sseConnection);
	}

	@NonNull
	private static String statusClassFor(int statusCode) {
		if (statusCode >= 100 && statusCode < 200)
			return "1xx";
		if (statusCode >= 200 && statusCode < 300)
			return "2xx";
		if (statusCode >= 300 && statusCode < 400)
			return "3xx";
		if (statusCode >= 400 && statusCode < 500)
			return "4xx";
		if (statusCode >= 500 && statusCode < 600)
			return "5xx";

		return UNKNOWN_STATUS_CLASS;
	}

	@NonNull
	private static long[] nanosFromMillis(int... millis) {
		long[] nanos = new long[millis.length];
		for (int i = 0; i < millis.length; i++)
			nanos[i] = millis[i] * 1_000_000L;
		return nanos;
	}

	@NonNull
	private static long[] nanosFromSeconds(int... seconds) {
		long[] nanos = new long[seconds.length];
		for (int i = 0; i < seconds.length; i++)
			nanos[i] = seconds[i] * 1_000_000_000L;
		return nanos;
	}

	private static long elapsedNanosSince(long startedAtNanos) {
		return Math.max(0L, System.nanoTime() - startedAtNanos);
	}

	private static long nonNegativeNanos(@NonNull Duration duration) {
		requireNonNull(duration);
		if (duration.isNegative())
			return 0L;

		try {
			return duration.toNanos();
		} catch (ArithmeticException ignored) {
			return Long.MAX_VALUE;
		}
	}

	private static boolean decrementIfPositive(@NonNull AtomicLong value) {
		requireNonNull(value);
		while (true) {
			long current = value.get();
			if (current <= 0L) {
				if (current == 0L || value.compareAndSet(current, 0L))
					return false;
				continue;
			}
			if (value.compareAndSet(current, current - 1L))
				return true;
		}
	}

	@NonNull
	private static <K> Histogram histogramFor(@NonNull Map<K, Histogram> map,
																						@NonNull K key,
																						@NonNull long[] buckets) {
		requireNonNull(map);
		requireNonNull(key);
		requireNonNull(buckets);

		return map.computeIfAbsent(key, ignored -> new Histogram(buckets));
	}

	@NonNull
	private static <K> LongAdder counterFor(@NonNull Map<K, LongAdder> map,
																					@NonNull K key) {
		requireNonNull(map);
		requireNonNull(key);

		return map.computeIfAbsent(key, ignored -> new LongAdder());
	}

	@NonNull
	private static <K> Map<@NonNull K, @NonNull HistogramSnapshot> snapshotMap(@NonNull Map<K, Histogram> map) {
		requireNonNull(map);

		Map<K, HistogramSnapshot> snapshot = new ConcurrentHashMap<>(map.size());
		for (Map.Entry<K, Histogram> entry : map.entrySet())
			snapshot.put(entry.getKey(), entry.getValue().snapshot());
		return snapshot;
	}

	@NonNull
	private static <K> Map<@NonNull K, @NonNull Long> snapshotCounterMap(@NonNull Map<K, LongAdder> map) {
		requireNonNull(map);

		Map<K, Long> snapshot = new ConcurrentHashMap<>(map.size());
		for (Map.Entry<K, LongAdder> entry : map.entrySet())
			snapshot.put(entry.getKey(), entry.getValue().sum());
		return snapshot;
	}

	private static <K> void resetMap(@NonNull Map<K, Histogram> map) {
		requireNonNull(map);

		for (Histogram histogram : map.values())
			histogram.reset();
	}

	private static <K> void resetCounterMap(@NonNull Map<K, LongAdder> map) {
		requireNonNull(map);

		for (LongAdder counter : map.values())
			counter.reset();
	}

	private static void appendGauge(@NonNull StringBuilder sb,
																	@NonNull String name,
																	@NonNull String help,
																	long value,
																	@NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);
		requireNonNull(options);

		if (!shouldEmitSample(options, name, Map.of()))
			return;

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" gauge\n");
		appendSample(sb, name, "", value);
	}

	private static void appendTransportFailures(@NonNull StringBuilder sb,
			@NonNull Map<@NonNull TransportFailureKey, @NonNull Long>
					transportFailures,
			@NonNull Map<@NonNull TransportFailureReason, @NonNull Long>
					mcpTransportFailures,
			@NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(transportFailures);
		requireNonNull(mcpTransportFailures);
		requireNonNull(options);

		if (transportFailures.isEmpty() && mcpTransportFailures.isEmpty())
			return;

		String name = "soklet_transport_failures_total";
		StringBuilder metricBody = new StringBuilder();
		transportFailures.forEach((key, value) -> {
			LabelSet labels = labelsForTransportFailureKey(key);
			if (shouldEmitSample(options, name, labels.getLabels()))
				appendSample(metricBody, name, labels.getEncoded(), value);
		});
		mcpTransportFailures.forEach((reason, value) -> {
			LabelSet labels = labelsForMcpTransportFailureReason(reason);
			if (shouldEmitSample(options, name, labels.getLabels()))
				appendSample(metricBody, name, labels.getEncoded(), value);
		});

		if (metricBody.length() == 0)
			return;

		String familyName = counterFamilyName(name, options);
		sb.append("# HELP ").append(familyName)
				.append(" Total low-level transport failures\n");
		sb.append("# TYPE ").append(familyName).append(" counter\n");
		sb.append(metricBody);
	}

	private static void appendCounter(@NonNull StringBuilder sb,
																		@NonNull String name,
																		@NonNull String help,
																		long value,
																		@NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);
		requireNonNull(options);

		if (!shouldEmitSample(options, name, Map.of()))
			return;

		String familyName = counterFamilyName(name, options);
		sb.append("# HELP ").append(familyName).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(familyName).append(" counter\n");
		appendSample(sb, name, "", value);
	}

	private static <K> void appendCounter(@NonNull StringBuilder sb,
																				@NonNull String name,
																				@NonNull String help,
																				@NonNull Map<K, Long> counters,
																				@NonNull Function<K, LabelSet> labelsProvider,
																				@NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);
		requireNonNull(counters);
		requireNonNull(labelsProvider);
		requireNonNull(options);

		if (counters.isEmpty())
			return;

		StringBuilder metricBody = new StringBuilder();

		counters.forEach((key, value) -> {
			LabelSet labels = labelsProvider.apply(key);
			if (!shouldEmitSample(options, name, labels.getLabels()))
				return;

			appendSample(metricBody, name, labels.getEncoded(), value);
		});

		if (metricBody.length() == 0)
			return;

		String familyName = counterFamilyName(name, options);
		sb.append("# HELP ").append(familyName).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(familyName).append(" counter\n");
		sb.append(metricBody);
	}

	@NonNull
	private static String counterFamilyName(@NonNull String sampleName,
			@NonNull SnapshotTextOptions options) {
		requireNonNull(sampleName);
		requireNonNull(options);

		if (options.getMetricsFormat() == MetricsFormat.OPEN_METRICS_1_0
				&& sampleName.endsWith("_total"))
			return sampleName.substring(0, sampleName.length() - "_total".length());

		return sampleName;
	}

	private static <K> void appendHistogram(@NonNull StringBuilder sb,
																					@NonNull String name,
																					@NonNull String help,
																					@NonNull Map<K, HistogramSnapshot> histograms,
																					@NonNull Function<K, LabelSet> labelsProvider,
																					@NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);
		requireNonNull(histograms);
		requireNonNull(labelsProvider);
		requireNonNull(options);

		if (options.getHistogramFormat() == SnapshotTextOptions.HistogramFormat.NONE)
			return;

		StringBuilder metricBody = new StringBuilder();

		histograms.forEach((key, histogram) -> {
			LabelSet labels = labelsProvider.apply(key);
			appendHistogramSamples(metricBody, name, labels, histogram, options);
		});

		if (metricBody.length() == 0)
			return;

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" histogram\n");
		sb.append(metricBody);
	}

	private static void appendHistogramSamples(@NonNull StringBuilder sb,
																						 @NonNull String name,
																						 @NonNull LabelSet labels,
																						 @NonNull HistogramSnapshot histogram,
																						 @NonNull SnapshotTextOptions options) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(labels);
		requireNonNull(histogram);
		requireNonNull(options);

		SnapshotTextOptions.HistogramFormat histogramFormat = options.getHistogramFormat();
		boolean includeZeroBuckets = options.getIncludeZeroBuckets();
		boolean openMetrics = options.getMetricsFormat()
				== MetricsFormat.OPEN_METRICS_1_0;
		int bucketCount = histogram.getBucketCount();
		String bucketName = name + "_bucket";
		String positiveInfinity = "+Inf";
		boolean emitPositiveInfinity = !openMetrics || shouldEmitSample(
				options, bucketName, labels, positiveInfinity);

		// An OpenMetrics histogram point is only valid when it contains its
		// +Inf bucket. If a per-sample filter rejects that required sample,
		// omit the point rather than emitting a malformed partial histogram.
		if (!emitPositiveInfinity)
			return;

		if (histogramFormat == SnapshotTextOptions.HistogramFormat.FULL_BUCKETS
				|| openMetrics) {
			int firstBucket = histogramFormat
					== SnapshotTextOptions.HistogramFormat.FULL_BUCKETS
					? 0 : bucketCount - 1;

			for (int i = firstBucket; i < bucketCount; i++) {
				long cumulativeCount = histogram.getBucketCumulativeCount(i);
				boolean overflowBucket = i == bucketCount - 1;
				if (!includeZeroBuckets && cumulativeCount == 0L
						&& !overflowBucket)
					continue;

				long boundary = histogram.getBucketBoundary(i);
				String le = boundary == Long.MAX_VALUE
						? positiveInfinity : String.valueOf(boundary);
				String labelsWithLe = labelsWithLe(labels.getEncoded(), le);

				if (!(openMetrics && overflowBucket)
						&& !shouldEmitSample(options, bucketName, labels, le))
					continue;

				appendSample(sb, bucketName, labelsWithLe, cumulativeCount);
			}
		}

		if (histogramFormat != SnapshotTextOptions.HistogramFormat.NONE) {
			String countName = name + "_count";
			String sumName = name + "_sum";
			boolean emitCount = shouldEmitSample(options, countName,
					labels.getLabels());
			boolean emitSum = shouldEmitSample(options, sumName,
					labels.getLabels());
			if (openMetrics && emitCount != emitSum) {
				// OpenMetrics requires these paired samples to remain structurally
				// coherent. A bucket-only point remains valid.
				emitCount = false;
				emitSum = false;
			}

			if (emitCount)
				appendSample(sb, countName, labels.getEncoded(), histogram.getCount());

			if (emitSum)
				appendSample(sb, sumName, labels.getEncoded(), histogram.getSum());
		}
	}

	private static void appendSample(@NonNull StringBuilder sb,
																	 @NonNull String name,
																	 @NonNull String labels,
																	 long value) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(labels);

		sb.append(name);
		if (!labels.isEmpty())
			sb.append('{').append(labels).append('}');
		sb.append(' ').append(value).append('\n');
	}

	private static boolean shouldEmitSample(@NonNull SnapshotTextOptions options,
																					@NonNull String name,
																					@NonNull Map<@NonNull String, @NonNull String> labels) {
		requireNonNull(options);
		requireNonNull(name);
		requireNonNull(labels);

		Predicate<SnapshotTextOptions.MetricSample> filter = options.getMetricFilter().orElse(null);
		if (filter == null)
			return true;

		return filter.test(new SnapshotTextOptions.MetricSample(name, labels));
	}

	private static boolean shouldEmitSample(@NonNull SnapshotTextOptions options,
																					@NonNull String name,
																					@NonNull LabelSet labels,
																					@Nullable String le) {
		requireNonNull(options);
		requireNonNull(name);
		requireNonNull(labels);

		Predicate<SnapshotTextOptions.MetricSample> filter = options.getMetricFilter().orElse(null);
		if (filter == null)
			return true;

		Map<String, String> labelMap = new LinkedHashMap<>(labels.getLabels());
		if (le != null)
			labelMap.put("le", le);

		return filter.test(new SnapshotTextOptions.MetricSample(name, labelMap));
	}

	@NonNull
	private static String labelsWithLe(@NonNull String labels,
																		 @NonNull String le) {
		requireNonNull(labels);
		requireNonNull(le);

		if (labels.isEmpty())
			return "le=\"" + le + "\"";

		return labels + ",le=\"" + le + "\"";
	}

	@NonNull
	private static LabelSet labelsForHttpStatusKey(@NonNull HttpServerRouteStatusKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("method", key.getMethod().name());
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("status_class", key.getStatusClass());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForHttpRouteKey(@NonNull HttpServerRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("method", key.getMethod().name());
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForTransportFailureKey(@NonNull TransportFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("server_type", key.getServerType().name());
		labels.put("reason", key.getReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpTransportFailureReason(
			@NonNull TransportFailureReason reason) {
		requireNonNull(reason);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("server_type", "MCP");
		labels.put("reason", reason.name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForRequestReadFailureKey(@NonNull RequestReadFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("reason", key.getReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForRequestRejectionKey(@NonNull RequestRejectionKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("reason", key.getReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpShutdownDisposition(
			@NonNull ShutdownComponentDisposition outcome) {
		requireNonNull(outcome);

		String outcomeLabel = switch (outcome) {
			case NOT_STARTED -> "not_started";
			case GRACEFUL_TERMINATION -> "graceful_termination";
			case FORCED_TERMINATION -> "forced_termination";
			case UNEXPECTED_TERMINATION -> "unexpected_termination";
			case RESIDUAL_ACTIVITY -> "residual_activity";
			case TERMINATION_UNKNOWN -> "termination_unknown";
		};
		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("outcome", outcomeLabel);
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpRequestOutcomeKey(
			McpMetricsSnapshot.@NonNull RequestOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("endpoint", key.getEndpointPath());
		labels.put("method", key.getJsonRpcMethod());
		labels.put("outcome", key.getOutcome().name().toLowerCase(Locale.ROOT));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpRequestStreamTerminationKey(
			McpMetricsSnapshot.@NonNull RequestStreamTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("endpoint", key.getEndpointPath());
		labels.put("method", key.getJsonRpcMethod());
		labels.put("reason", key.getReason().name().toLowerCase(Locale.ROOT));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpSubscriptionTerminationKey(
			McpMetricsSnapshot.@NonNull SubscriptionTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("endpoint", key.getEndpointPath());
		labels.put("reason", key.getReason().name().toLowerCase(Locale.ROOT));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpEndpointMethodKey(
			McpMetricsSnapshot.@NonNull EndpointMethodKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("endpoint", key.getEndpointPath());
		labels.put("method", key.getJsonRpcMethod());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpProtocolErrorCode(
			@NonNull Integer code) {
		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("code", requireNonNull(code).toString());
		return new LabelSet(labels);
	}


	@NonNull
	private static LabelSet labelsForSseRouteKey(@NonNull SseEventRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseEnqueueOutcomeKey(@NonNull SseEventRouteEnqueueOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("outcome", key.getOutcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentEnqueueOutcomeKey(@NonNull SseCommentRouteEnqueueOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("comment_type", key.getCommentType().name());
		labels.put("outcome", key.getOutcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseDropKey(@NonNull SseEventRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("drop_reason", key.getDropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentDropKey(@NonNull SseCommentRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("comment_type", key.getCommentType().name());
		labels.put("drop_reason", key.getDropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseHandshakeFailureKey(@NonNull SseEventRouteHandshakeFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("handshake_failure_reason",
				key.getHandshakeFailureReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentKey(@NonNull SseCommentRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("comment_type", key.getCommentType().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseStreamTerminationKey(@NonNull SseStreamRouteTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.getRouteType(), key.getRoute()));
		labels.put("termination_reason", key.getTerminationReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static String routeLabel(@NonNull RouteType routeType,
																	 @Nullable ResourcePathDeclaration route) {
		requireNonNull(routeType);

		if (routeType == RouteType.UNMATCHED || route == null)
			return "unmatched";

		return route.getPath();
	}

	private static final class LabelSet {
		@NonNull
		private final Map<@NonNull String, @NonNull String> labels;
		@NonNull
		private final String encoded;

		private LabelSet(@NonNull Map<@NonNull String, @NonNull String> labels) {
			this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(labels)));
			this.encoded = encodeLabels(this.labels);
		}

		@NonNull
		Map<@NonNull String, @NonNull String> getLabels() {
			return this.labels;
		}

		@NonNull
		String getEncoded() {
			return this.encoded;
		}
	}

	@NonNull
	private static String encodeLabels(@NonNull Map<@NonNull String, @NonNull String> labels) {
		requireNonNull(labels);

		if (labels.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder(labels.size() * 16);
		boolean first = true;

		for (Map.Entry<String, String> entry : labels.entrySet()) {
			if (!first)
				sb.append(',');
			first = false;

			sb.append(entry.getKey())
					.append("=\"")
					.append(escapeLabelValue(entry.getValue()))
					.append('"');
		}

		return sb.toString();
	}

	@NonNull
	private static String escapeLabelValue(@NonNull String value) {
		requireNonNull(value);

		StringBuilder sb = new StringBuilder(value.length() + 8);

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '\\')
				sb.append("\\\\");
			else if (c == '\n')
				sb.append("\\n");
			else if (c == '\r')
				sb.append("\\r");
			else if (c == '"')
				sb.append("\\\"");
			else
				sb.append(c);
		}

		return sb.toString();
	}

	private static final class RouteContext {
		@NonNull
		private final RouteType routeType;
		@Nullable
		private final ResourcePathDeclaration route;

		private RouteContext(@NonNull RouteType routeType,
												 @Nullable ResourcePathDeclaration route) {
			this.routeType = requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			this.route = route;
		}

		@NonNull
		RouteType getRouteType() {
			return this.routeType;
		}

		@Nullable
		ResourcePathDeclaration getRoute() {
			return this.route;
		}
	}

	private static final class RequestState {
		@NonNull
		private final IdentityKey<Request> identityKey;
		@NonNull
		private final Set<IdentityKey<Request>> identityKeys;
		@NonNull
		private final Object requestId;
		private final long startedAtNanos;
		@NonNull
		private final HttpMethod method;
		@NonNull
		private final RouteType routeType;
		@Nullable
		private final ResourcePathDeclaration route;
		@NonNull
		private final AtomicBoolean handlerDurationRecorded;
		@NonNull
		private final AtomicBoolean finished;

		private RequestState(@NonNull IdentityKey<Request> identityKey,
												 @NonNull Object requestId,
												 long startedAtNanos,
												 @NonNull HttpMethod method,
												 @NonNull RouteType routeType,
												 @Nullable ResourcePathDeclaration route) {
			this.identityKey = requireNonNull(identityKey);
			this.identityKeys = new HashSet<>();
			this.identityKeys.add(identityKey);
			this.requestId = requireNonNull(requestId);
			this.startedAtNanos = startedAtNanos;
			this.method = requireNonNull(method);
			this.routeType = requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			this.route = route;
			this.handlerDurationRecorded = new AtomicBoolean(false);
			this.finished = new AtomicBoolean(false);
		}

		@NonNull
		IdentityKey<Request> getIdentityKey() {
			return this.identityKey;
		}

		void addIdentityKey(@NonNull IdentityKey<Request> identityKey) {
			this.identityKeys.add(requireNonNull(identityKey));
		}

		@NonNull
		Set<IdentityKey<Request>> getIdentityKeys() {
			return this.identityKeys;
		}

		@NonNull
		Object getRequestId() {
			return this.requestId;
		}

		long getStartedAtNanos() {
			return this.startedAtNanos;
		}

		@NonNull
		HttpMethod getMethod() {
			return this.method;
		}

		@NonNull
		RouteType getRouteType() {
			return this.routeType;
		}

		@Nullable
		ResourcePathDeclaration getRoute() {
			return this.route;
		}

		boolean markHandlerDurationRecorded() {
			return this.handlerDurationRecorded.compareAndSet(false, true);
		}

		boolean markFinished() {
			return this.finished.compareAndSet(false, true);
		}

		boolean isFinished() {
			return this.finished.get();
		}
	}

	private static final class RequestIdStateBucket {
		@NonNull
		private final Set<RequestState> states;
		private boolean collisionObserved;

		private RequestIdStateBucket() {
			this.states = new HashSet<>();
		}

		synchronized void add(@NonNull RequestState state) {
			this.states.add(requireNonNull(state));
			if (this.states.size() > 1)
				this.collisionObserved = true;
		}

		synchronized void remove(@NonNull RequestState state) {
			this.states.remove(requireNonNull(state));
		}

		synchronized boolean isEmpty() {
			return this.states.isEmpty();
		}

		synchronized int size() {
			return this.states.size();
		}

		@Nullable
		synchronized RequestState uniqueState() {
			if (this.collisionObserved || this.states.size() != 1)
				return null;

			RequestState state = this.states.iterator().next();
			return state.isFinished() ? null : state;
		}
	}

	private static final class SseConnectionState {
		private final long establishedAtNanos;
		@NonNull
		private final RouteType routeType;
		@Nullable
		private final ResourcePathDeclaration route;
		@NonNull
		private final AtomicBoolean firstEventRecorded;
		@NonNull
		private final LongAdder eventsSent;

		private SseConnectionState(@NonNull RouteType routeType,
															 @Nullable ResourcePathDeclaration route,
															 long establishedAtNanos) {
			this.routeType = requireNonNull(routeType);
			if (routeType == RouteType.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteType is MATCHED");
			if (routeType == RouteType.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteType is UNMATCHED");
			this.route = route;
			this.establishedAtNanos = establishedAtNanos;
			this.firstEventRecorded = new AtomicBoolean(false);
			this.eventsSent = new LongAdder();
		}

		long getEstablishedAtNanos() {
			return this.establishedAtNanos;
		}

		@NonNull
		RouteType getRouteType() {
			return this.routeType;
		}

		@Nullable
		ResourcePathDeclaration getRoute() {
			return this.route;
		}

		boolean markFirstEventRecorded() {
			return this.firstEventRecorded.compareAndSet(false, true);
		}

		void incrementEventsSent() {
			this.eventsSent.increment();
		}
	}

	private static final class IdentityKey<T> {
		private final T value;
		private final int hash;

		private IdentityKey(@NonNull T value) {
			this.value = requireNonNull(value);
			this.hash = System.identityHashCode(value);
		}

		@Override
		public boolean equals(@Nullable Object object) {
			if (this == object)
				return true;
			if (!(object instanceof IdentityKey<?> identityKey))
				return false;
			return this.value == identityKey.value;
		}

		@Override
		public int hashCode() {
			return this.hash;
		}
	}

	@Nullable
	private RequestState requestStateFor(@NonNull Request request) {
		requireNonNull(request);

		IdentityKey<Request> identityKey = new IdentityKey<>(request);
		RequestState state = this.requestsInFlightByIdentity.get(identityKey);

		if (state != null && !state.isFinished())
			return state;

		state = uniqueRequestStateForId(request.getId());
		if (state != null)
			return associateRequestIdentity(identityKey, state);

		state = this.requestStateByThread.get();
		if (state != null) {
			RequestState associatedState = associateRequestIdentity(identityKey, state);
			if (associatedState != null)
				return associatedState;

			// A finished thread-local state belongs to a prior callback. Do not
			// fall back by a potentially reused request ID.
			this.requestStateByThread.remove();
			return null;
		}

		return null;
	}

	@Nullable
	private RequestState removeRequestState(@NonNull Request request) {
		requireNonNull(request);

		IdentityKey<Request> currentIdentityKey = new IdentityKey<>(request);
		RequestState state = this.requestsInFlightByIdentity.get(currentIdentityKey);

		if (state == null)
			state = uniqueRequestStateForId(request.getId());

		if (state == null) {
			state = this.requestStateByThread.get();
			if (state != null && state.isFinished()) {
				this.requestStateByThread.remove();
				return null;
			}
		}

		if (state == null) {
			this.requestStateByThread.remove();
			return null;
		}

		synchronized (state) {
			if (!state.markFinished()) {
				this.requestStateByThread.remove();
				return null;
			}

			for (IdentityKey<Request> identityKey : state.getIdentityKeys())
				this.requestsInFlightByIdentity.remove(identityKey, state);
			this.requestsInFlightByIdentity.remove(currentIdentityKey, state);
			unregisterRequestStateById(state);
		}

		this.requestStateByThread.remove();
		return state;
	}

	private void registerRequestStateById(@NonNull RequestState state) {
		requireNonNull(state);
		this.requestsInFlightById.compute(state.getRequestId(), (requestId, bucket) -> {
			RequestIdStateBucket effectiveBucket = bucket == null
					? new RequestIdStateBucket() : bucket;
			effectiveBucket.add(state);
			return effectiveBucket;
		});
	}

	private void unregisterRequestStateById(@NonNull RequestState state) {
		requireNonNull(state);
		this.requestsInFlightById.computeIfPresent(state.getRequestId(),
				(requestId, bucket) -> {
					bucket.remove(state);
					return bucket.isEmpty() ? null : bucket;
				});
	}

	@Nullable
	private RequestState uniqueRequestStateForId(@NonNull Object requestId) {
		requireNonNull(requestId);
		RequestIdStateBucket bucket = this.requestsInFlightById.get(requestId);
		return bucket == null ? null : bucket.uniqueState();
	}

	@Nullable
	private RequestState associateRequestIdentity(
			@NonNull IdentityKey<Request> identityKey,
			@NonNull RequestState state) {
		requireNonNull(identityKey);
		requireNonNull(state);

		synchronized (state) {
			if (state.isFinished())
				return null;

			RequestState existingState = this.requestsInFlightByIdentity
					.putIfAbsent(identityKey, state);
			if (existingState != null)
				return existingState.isFinished() ? null : existingState;

			state.addIdentityKey(identityKey);
			return state;
		}
	}

}
