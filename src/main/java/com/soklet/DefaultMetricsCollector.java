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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

	private static final long[] SSE_CONNECTION_DURATION_BUCKETS_NANOS = nanosFromSeconds(
			1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600, 7200, 14400);

	private static final long[] SSE_TIME_TO_FIRST_EVENT_BUCKETS_NANOS = nanosFromMillis(
			1, 2, 5, 10, 25, 50, 100, 200, 500, 1000, 3000, 10000);

	private static final long[] SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS = nanosFromMillis(
			0, 1, 2, 5, 10, 25, 50, 100, 200, 400, 800, 1500, 3000, 7000, 15000, 30000);

	private static final long[] SSE_QUEUE_DEPTH_BUCKETS = new long[]{
			0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024
	};

	private static final long[] MCP_DURATION_BUCKETS_NANOS = nanosFromMillis(
			1, 2, 5, 10, 25, 50, 100, 200, 400, 800, 1500, 3000, 7000, 15000, 30000);

	private static final long[] MCP_SESSION_DURATION_BUCKETS_NANOS = nanosFromSeconds(
			1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600, 7200, 14400);

	private final ConcurrentHashMap<IdentityKey<Request>, RequestState> requestsInFlightByIdentity;
	private final ConcurrentHashMap<Object, RequestState> requestsInFlightById;
	private final ConcurrentLruMap<RequestReadFailureKey, LongAdder> httpRequestReadFailuresByReason;
	private final ConcurrentLruMap<RequestRejectionKey, LongAdder> httpRequestRejectionsByReason;
	private final ConcurrentLruMap<RequestReadFailureKey, LongAdder> sseRequestReadFailuresByReason;
	private final ConcurrentLruMap<RequestRejectionKey, LongAdder> sseRequestRejectionsByReason;
	private final ConcurrentLruMap<RequestReadFailureKey, LongAdder> mcpRequestReadFailuresByReason;
	private final ConcurrentLruMap<RequestRejectionKey, LongAdder> mcpRequestRejectionsByReason;
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
	private final ConcurrentLruMap<SseEventRouteTerminationKey, Histogram> sseConnectionDurationByRouteAndReason;
	private final ConcurrentLruMap<McpEndpointRequestOutcomeKey, LongAdder> mcpRequestsByOutcome;
	private final ConcurrentLruMap<McpEndpointRequestOutcomeKey, Histogram> mcpRequestDurationByOutcome;
	private final ConcurrentLruMap<McpEndpointSessionTerminationKey, Histogram> mcpSessionDurationByEndpointAndReason;
	private final ConcurrentLruMap<McpEndpointStreamTerminationKey, Histogram> mcpStreamDurationByEndpointAndReason;
	private final LongAdder activeRequests;
	private final LongAdder activeSseConnections;
	private final LongAdder activeMcpSessions;
	private final LongAdder activeMcpStreams;
	private final LongAdder httpConnectionsAccepted;
	private final LongAdder httpConnectionsRejected;
	private final LongAdder sseConnectionsAccepted;
	private final LongAdder sseConnectionsRejected;
	private final LongAdder mcpConnectionsAccepted;
	private final LongAdder mcpConnectionsRejected;
	private final AtomicBoolean includeSseMetrics;
	private final AtomicBoolean includeMcpMetrics;

	@NonNull
	public static DefaultMetricsCollector defaultInstance() {
		return new DefaultMetricsCollector();
	}

	private DefaultMetricsCollector() {
		this.requestsInFlightByIdentity = new ConcurrentHashMap<>();
		this.requestsInFlightById = new ConcurrentHashMap<>();
		this.httpRequestReadFailuresByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.httpRequestRejectionsByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseRequestReadFailuresByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.sseRequestRejectionsByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpRequestReadFailuresByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpRequestRejectionsByReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
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
		this.sseConnectionDurationByRouteAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpRequestsByOutcome = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpRequestDurationByOutcome = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpSessionDurationByEndpointAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.mcpStreamDurationByEndpointAndReason = new ConcurrentLruMap<>(DEFAULT_METRICS_MAP_CAPACITY);
		this.activeRequests = new LongAdder();
		this.activeSseConnections = new LongAdder();
		this.activeMcpSessions = new LongAdder();
		this.activeMcpStreams = new LongAdder();
		this.httpConnectionsAccepted = new LongAdder();
		this.httpConnectionsRejected = new LongAdder();
		this.sseConnectionsAccepted = new LongAdder();
		this.sseConnectionsRejected = new LongAdder();
		this.mcpConnectionsAccepted = new LongAdder();
		this.mcpConnectionsRejected = new LongAdder();
		this.includeSseMetrics = new AtomicBoolean(false);
		this.includeMcpMetrics = new AtomicBoolean(false);
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		this.includeSseMetrics.set(sokletConfig.getSseServer().isPresent());
		this.includeMcpMetrics.set(sokletConfig.getMcpServer().isPresent());
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
		else if (serverType == ServerType.MCP)
			this.mcpConnectionsAccepted.increment();
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
		else if (serverType == ServerType.MCP)
			this.mcpConnectionsRejected.increment();
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
		else if (serverType == ServerType.MCP)
			counterFor(this.mcpRequestReadFailuresByReason, key).increment();
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
		else if (serverType == ServerType.MCP)
			counterFor(this.mcpRequestRejectionsByReason, key).increment();
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

		this.activeRequests.increment();
		RequestState state = new RequestState(new IdentityKey<>(request), request.getId(), System.nanoTime(), method,
				routeContext.getRouteType(), routeContext.getRoute());
		this.requestsInFlightByIdentity.put(state.getIdentityKey(), state);
		this.requestsInFlightById.put(state.getRequestId(), state);

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

		long elapsedNanos = System.nanoTime() - state.getStartedAtNanos();
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

		this.activeRequests.decrement();
		removeRequestState(request);

		RouteContext routeContext = routeFor(resourceMethod);
		HttpMethod method = request.getHttpMethod();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		HttpServerRouteStatusKey key = new HttpServerRouteStatusKey(method, routeContext.getRouteType(),
				routeContext.getRoute(), statusClass);
		histogramFor(this.httpRequestDurationByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(duration.toNanos());

		long responseBodyBytes = marshaledResponse.getBodyLength();

		histogramFor(this.httpResponseBodyBytesByRouteStatus, key, HTTP_BODY_BYTES_BUCKETS)
				.record(responseBodyBytes);
	}

	@Override
	public void didCreateMcpSession(@NonNull Request request,
																	@NonNull Class<? extends McpEndpoint> endpointClass,
																	@NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		this.activeMcpSessions.increment();
	}

	@Override
	public void didTerminateMcpSession(@NonNull Class<? extends McpEndpoint> endpointClass,
																		 @NonNull String sessionId,
																		 @NonNull Duration sessionDuration,
																		 @NonNull McpSessionTerminationReason terminationReason,
																		 @Nullable Throwable throwable) {
		requireNonNull(endpointClass);
		requireNonNull(sessionId);
		requireNonNull(sessionDuration);
		requireNonNull(terminationReason);

		this.activeMcpSessions.decrement();
		histogramFor(this.mcpSessionDurationByEndpointAndReason,
				new McpEndpointSessionTerminationKey(endpointClass, terminationReason),
				MCP_SESSION_DURATION_BUCKETS_NANOS)
				.record(sessionDuration.toNanos());
	}

	@Override
	public void didStartMcpRequestHandling(@NonNull Request request,
																				 @NonNull Class<? extends McpEndpoint> endpointClass,
																				 @Nullable String sessionId,
																				 @NonNull String jsonRpcMethod,
																				 @Nullable McpJsonRpcRequestId jsonRpcRequestId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(jsonRpcMethod);
	}

	@Override
	public void didFinishMcpRequestHandling(@NonNull Request request,
																					@NonNull Class<? extends McpEndpoint> endpointClass,
																					@Nullable String sessionId,
																					@NonNull String jsonRpcMethod,
																					@Nullable McpJsonRpcRequestId jsonRpcRequestId,
																					@NonNull McpRequestOutcome requestOutcome,
																					@Nullable McpJsonRpcError jsonRpcError,
																					@NonNull Duration duration,
																					@NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(jsonRpcMethod);
		requireNonNull(requestOutcome);
		requireNonNull(duration);
		requireNonNull(throwables);

		McpEndpointRequestOutcomeKey key = new McpEndpointRequestOutcomeKey(endpointClass, jsonRpcMethod, requestOutcome);
		counterFor(this.mcpRequestsByOutcome, key).increment();
		histogramFor(this.mcpRequestDurationByOutcome, key, MCP_DURATION_BUCKETS_NANOS)
				.record(duration.toNanos());
	}

	@Override
	public void didEstablishMcpSseStream(@NonNull Request request,
																									 @NonNull Class<? extends McpEndpoint> endpointClass,
																									 @NonNull String sessionId) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);

		this.activeMcpStreams.increment();
	}

	@Override
	public void didTerminateMcpSseStream(@NonNull Request request,
																									 @NonNull Class<? extends McpEndpoint> endpointClass,
																									 @NonNull String sessionId,
																									 @NonNull Duration connectionDuration,
																									 @NonNull McpStreamTerminationReason terminationReason,
																									 @Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(endpointClass);
		requireNonNull(sessionId);
		requireNonNull(connectionDuration);
		requireNonNull(terminationReason);

		this.activeMcpStreams.decrement();
		histogramFor(this.mcpStreamDurationByEndpointAndReason,
				new McpEndpointStreamTerminationKey(endpointClass, terminationReason),
				MCP_SESSION_DURATION_BUCKETS_NANOS)
				.record(connectionDuration.toNanos());
	}

	@Override
	public void didEstablishSseConnection(@NonNull SseConnection sseConnection) {
		requireNonNull(sseConnection);

		RouteContext routeContext = routeFor(sseConnection);

		counterFor(this.sseHandshakesAcceptedByRoute,
				new SseEventRouteKey(routeContext.getRouteType(), routeContext.getRoute())).increment();
		this.activeSseConnections.increment();
		this.sseConnectionsByIdentity.put(new IdentityKey<>(sseConnection),
				new SseConnectionState(routeContext.getRouteType(), routeContext.getRoute(), System.nanoTime()));
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
			long elapsedNanos = System.nanoTime() - state.getEstablishedAtNanos();
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
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
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
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
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
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
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
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
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
																										@NonNull Duration connectionDuration,
																										SseConnection.@NonNull TerminationReason terminationReason,
																										@Nullable Throwable throwable) {
		requireNonNull(sseConnection);
		requireNonNull(connectionDuration);
		requireNonNull(terminationReason);

		this.activeSseConnections.decrement();
		this.sseConnectionsByIdentity.remove(new IdentityKey<>(sseConnection));

		RouteContext routeContext = routeFor(sseConnection);

		SseEventRouteTerminationKey key = new SseEventRouteTerminationKey(routeContext.getRouteType(),
				routeContext.getRoute(), terminationReason);

		histogramFor(this.sseConnectionDurationByRouteAndReason, key, SSE_CONNECTION_DURATION_BUCKETS_NANOS)
				.record(connectionDuration.toNanos());
	}

	@Override
	@NonNull
	public Optional<Snapshot> snapshot() {
		return Optional.of(Snapshot.builder()
				.activeRequests(getActiveRequests())
				.activeSseConnections(getActiveSseConnections())
				.activeMcpSessions(getActiveMcpSessions())
				.activeMcpStreams(getActiveMcpStreams())
				.httpConnectionsAccepted(getHttpConnectionsAccepted())
				.httpConnectionsRejected(getHttpConnectionsRejected())
				.sseConnectionsAccepted(getSseConnectionsAccepted())
				.sseConnectionsRejected(getSseConnectionsRejected())
				.mcpConnectionsAccepted(getMcpConnectionsAccepted())
				.mcpConnectionsRejected(getMcpConnectionsRejected())
				.httpRequestReadFailures(snapshotHttpRequestReadFailures())
				.httpRequestRejections(snapshotHttpRequestRejections())
				.sseRequestReadFailures(snapshotSseRequestReadFailures())
				.sseRequestRejections(snapshotSseRequestRejections())
				.mcpRequestReadFailures(snapshotMcpRequestReadFailures())
				.mcpRequestRejections(snapshotMcpRequestRejections())
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
				.sseConnectionDurations(snapshotSseConnectionDurations())
				.mcpRequests(snapshotMcpRequests())
				.mcpRequestDurations(snapshotMcpRequestDurations())
				.mcpSessionDurations(snapshotMcpSessionDurations())
				.mcpStreamDurations(snapshotMcpStreamDurations())
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
		appendCounter(sb, "soklet_http_request_read_failures_total", "Total HTTP request read failures",
				snapshot.getHttpRequestReadFailures(), DefaultMetricsCollector::labelsForRequestReadFailureKey, options);
		appendCounter(sb, "soklet_http_requests_rejected_total", "Total HTTP requests rejected before handling",
				snapshot.getHttpRequestRejections(), DefaultMetricsCollector::labelsForRequestRejectionKey, options);

		appendHistogram(sb, "soklet_http_request_duration_nanos", "HTTP request duration in nanoseconds",
				snapshot.getHttpRequestDurations(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_handler_duration_nanos", "HTTP handler duration in nanoseconds",
				snapshot.getHttpHandlerDurations(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_ttfb_nanos", "HTTP time to first byte in nanoseconds",
				snapshot.getHttpTimeToFirstByte(), DefaultMetricsCollector::labelsForHttpStatusKey, options);
		appendHistogram(sb, "soklet_http_request_body_bytes", "HTTP request body size in bytes",
				snapshot.getHttpRequestBodyBytes(), DefaultMetricsCollector::labelsForHttpRouteKey, options);
		appendHistogram(sb, "soklet_http_response_body_bytes", "HTTP response body size in bytes",
				snapshot.getHttpResponseBodyBytes(), DefaultMetricsCollector::labelsForHttpStatusKey, options);

		if (this.includeMcpMetrics.get()) {
			appendGauge(sb, "soklet_mcp_sessions_active", "Currently active MCP sessions",
					snapshot.getActiveMcpSessions(), options);
			appendGauge(sb, "soklet_mcp_streams_active", "Currently active MCP streams",
					snapshot.getActiveMcpStreams(), options);
			appendCounter(sb, "soklet_mcp_connections_accepted_total", "Total accepted MCP connections",
					snapshot.getMcpConnectionsAccepted(), options);
			appendCounter(sb, "soklet_mcp_connections_rejected_total", "Total rejected MCP connections",
					snapshot.getMcpConnectionsRejected(), options);
			appendCounter(sb, "soklet_mcp_request_read_failures_total", "Total MCP request read failures",
					snapshot.getMcpRequestReadFailures(), DefaultMetricsCollector::labelsForRequestReadFailureKey, options);
			appendCounter(sb, "soklet_mcp_requests_rejected_total", "Total MCP requests rejected before handling",
					snapshot.getMcpRequestRejections(), DefaultMetricsCollector::labelsForRequestRejectionKey, options);
			appendCounter(sb, "soklet_mcp_requests_total", "Total MCP requests by endpoint, method, and outcome",
					snapshot.getMcpRequests(), DefaultMetricsCollector::labelsForMcpRequestOutcomeKey, options);
			appendHistogram(sb, "soklet_mcp_request_duration_nanos", "MCP request duration in nanoseconds",
					snapshot.getMcpRequestDurations(), DefaultMetricsCollector::labelsForMcpRequestOutcomeKey, options);
			appendHistogram(sb, "soklet_mcp_session_duration_nanos", "MCP session duration in nanoseconds",
					snapshot.getMcpSessionDurations(), DefaultMetricsCollector::labelsForMcpSessionTerminationKey, options);
			appendHistogram(sb, "soklet_mcp_stream_duration_nanos", "MCP stream duration in nanoseconds",
					snapshot.getMcpStreamDurations(), DefaultMetricsCollector::labelsForMcpStreamTerminationKey, options);
		}

		if (this.includeSseMetrics.get()) {
			appendCounter(sb, "soklet_sse_connections_accepted_total", "Total accepted SSE connections",
					snapshot.getSseConnectionsAccepted(), options);
			appendCounter(sb, "soklet_sse_connections_rejected_total", "Total rejected SSE connections",
					snapshot.getSseConnectionsRejected(), options);
			appendCounter(sb, "soklet_sse_request_read_failures_total", "Total SSE request read failures",
					snapshot.getSseRequestReadFailures(), DefaultMetricsCollector::labelsForRequestReadFailureKey, options);
			appendCounter(sb, "soklet_sse_requests_rejected_total", "Total SSE requests rejected before handling",
					snapshot.getSseRequestRejections(), DefaultMetricsCollector::labelsForRequestRejectionKey, options);
			appendGauge(sb, "soklet_sse_connections_active", "Currently active SSE connections",
					snapshot.getActiveSseConnections(), options);
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

			appendHistogram(sb, "soklet_sse_connection_duration_nanos", "SSE connection duration in nanoseconds",
					snapshot.getSseConnectionDurations(), DefaultMetricsCollector::labelsForSseTerminationKey, options);
		}

		if (options.getMetricsFormat() == MetricsFormat.OPEN_METRICS_1_0)
			sb.append("# EOF\n");

		return Optional.of(sb.toString());
	}

	long getActiveRequests() {
		return this.activeRequests.sum();
	}

	long getActiveSseConnections() {
		return this.activeSseConnections.sum();
	}

	long getActiveMcpSessions() {
		return this.activeMcpSessions.sum();
	}

	long getActiveMcpStreams() {
		return this.activeMcpStreams.sum();
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

	long getMcpConnectionsAccepted() {
		return this.mcpConnectionsAccepted.sum();
	}

	long getMcpConnectionsRejected() {
		return this.mcpConnectionsRejected.sum();
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
	Map<@NonNull RequestReadFailureKey, @NonNull Long> snapshotMcpRequestReadFailures() {
		return snapshotCounterMap(this.mcpRequestReadFailuresByReason);
	}

	@NonNull
	Map<@NonNull RequestRejectionKey, @NonNull Long> snapshotMcpRequestRejections() {
		return snapshotCounterMap(this.mcpRequestRejectionsByReason);
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
	Map<@NonNull SseEventRouteTerminationKey, @NonNull HistogramSnapshot> snapshotSseConnectionDurations() {
		return snapshotMap(this.sseConnectionDurationByRouteAndReason);
	}

	@NonNull
	Map<@NonNull McpEndpointRequestOutcomeKey, @NonNull Long> snapshotMcpRequests() {
		return snapshotCounterMap(this.mcpRequestsByOutcome);
	}

	@NonNull
	Map<@NonNull McpEndpointRequestOutcomeKey, @NonNull HistogramSnapshot> snapshotMcpRequestDurations() {
		return snapshotMap(this.mcpRequestDurationByOutcome);
	}

	@NonNull
	Map<@NonNull McpEndpointSessionTerminationKey, @NonNull HistogramSnapshot> snapshotMcpSessionDurations() {
		return snapshotMap(this.mcpSessionDurationByEndpointAndReason);
	}

	@NonNull
	Map<@NonNull McpEndpointStreamTerminationKey, @NonNull HistogramSnapshot> snapshotMcpStreamDurations() {
		return snapshotMap(this.mcpStreamDurationByEndpointAndReason);
	}

	@Override
	public void reset() {
		this.activeRequests.reset();
		this.activeSseConnections.reset();
		this.activeMcpSessions.reset();
		this.activeMcpStreams.reset();
		this.httpConnectionsAccepted.reset();
		this.httpConnectionsRejected.reset();
		this.sseConnectionsAccepted.reset();
		this.sseConnectionsRejected.reset();
		this.mcpConnectionsAccepted.reset();
		this.mcpConnectionsRejected.reset();
		this.requestsInFlightByIdentity.clear();
		this.requestsInFlightById.clear();
		this.sseConnectionsByIdentity.clear();
		resetCounterMap(this.httpRequestReadFailuresByReason);
		resetCounterMap(this.httpRequestRejectionsByReason);
		resetCounterMap(this.sseRequestReadFailuresByReason);
		resetCounterMap(this.sseRequestRejectionsByReason);
		resetCounterMap(this.mcpRequestReadFailuresByReason);
		resetCounterMap(this.mcpRequestRejectionsByReason);
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
		resetMap(this.sseConnectionDurationByRouteAndReason);
		resetCounterMap(this.mcpRequestsByOutcome);
		resetMap(this.mcpRequestDurationByOutcome);
		resetMap(this.mcpSessionDurationByEndpointAndReason);
		resetMap(this.mcpStreamDurationByEndpointAndReason);
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

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" counter\n");
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

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" counter\n");
		sb.append(metricBody);
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

		if (histogramFormat == SnapshotTextOptions.HistogramFormat.FULL_BUCKETS) {
			int bucketCount = histogram.getBucketCount();

			for (int i = 0; i < bucketCount; i++) {
				long cumulativeCount = histogram.getBucketCumulativeCount(i);
				if (!includeZeroBuckets && cumulativeCount == 0L)
					continue;

				long boundary = histogram.getBucketBoundary(i);
				String le = boundary == Long.MAX_VALUE ? "+Inf" : String.valueOf(boundary);
				String labelsWithLe = labelsWithLe(labels.getEncoded(), le);
				String sampleName = name + "_bucket";

				if (!shouldEmitSample(options, sampleName, labels, le))
					continue;

				appendSample(sb, sampleName, labelsWithLe, cumulativeCount);
			}
		}

		if (histogramFormat != SnapshotTextOptions.HistogramFormat.NONE) {
			String countName = name + "_count";
			if (shouldEmitSample(options, countName, labels.getLabels())) {
				appendSample(sb, countName, labels.getEncoded(), histogram.getCount());
			}

			String sumName = name + "_sum";
			if (shouldEmitSample(options, sumName, labels.getLabels())) {
				appendSample(sb, sumName, labels.getEncoded(), histogram.getSum());
			}
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
		labels.put("method", key.method().name());
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("status_class", key.statusClass());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForHttpRouteKey(@NonNull HttpServerRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("method", key.method().name());
		labels.put("route", routeLabel(key.routeType(), key.route()));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForRequestReadFailureKey(@NonNull RequestReadFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("reason", key.reason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForRequestRejectionKey(@NonNull RequestRejectionKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("reason", key.reason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpRequestOutcomeKey(@NonNull McpEndpointRequestOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("endpoint_class", key.endpointClass().getName());
		labels.put("json_rpc_method", key.jsonRpcMethod());
		labels.put("request_outcome", key.requestOutcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpSessionTerminationKey(@NonNull McpEndpointSessionTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("endpoint_class", key.endpointClass().getName());
		labels.put("termination_reason", key.terminationReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForMcpStreamTerminationKey(@NonNull McpEndpointStreamTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("endpoint_class", key.endpointClass().getName());
		labels.put("termination_reason", key.terminationReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseRouteKey(@NonNull SseEventRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseEnqueueOutcomeKey(@NonNull SseEventRouteEnqueueOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("outcome", key.outcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentEnqueueOutcomeKey(@NonNull SseCommentRouteEnqueueOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		labels.put("outcome", key.outcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseDropKey(@NonNull SseEventRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("drop_reason", key.dropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentDropKey(@NonNull SseCommentRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		labels.put("drop_reason", key.dropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseHandshakeFailureKey(@NonNull SseEventRouteHandshakeFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("handshake_failure_reason", key.handshakeFailureReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentKey(@NonNull SseCommentRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseTerminationKey(@NonNull SseEventRouteTerminationKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("termination_reason", key.terminationReason().name());
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

		private RequestState(@NonNull IdentityKey<Request> identityKey,
												 @NonNull Object requestId,
												 long startedAtNanos,
												 @NonNull HttpMethod method,
												 @NonNull RouteType routeType,
												 @Nullable ResourcePathDeclaration route) {
			this.identityKey = requireNonNull(identityKey);
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
		}

		@NonNull
		IdentityKey<Request> getIdentityKey() {
			return this.identityKey;
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

	private RequestState requestStateFor(@NonNull Request request) {
		requireNonNull(request);

		IdentityKey<Request> identityKey = new IdentityKey<>(request);
		RequestState state = this.requestsInFlightByIdentity.get(identityKey);

		if (state != null)
			return state;

		state = this.requestsInFlightById.get(request.getId());

		if (state != null)
			this.requestsInFlightByIdentity.putIfAbsent(identityKey, state);

		return state;
	}

	private void removeRequestState(@NonNull Request request) {
		requireNonNull(request);

		RequestState state = requestStateFor(request);

		if (state == null)
			return;

		this.requestsInFlightByIdentity.remove(state.getIdentityKey(), state);
		this.requestsInFlightById.remove(state.getRequestId(), state);
	}

}
