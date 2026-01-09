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

import com.soklet.MetricsCollector.HistogramSnapshot;
import com.soklet.MetricsCollector.RequestReadFailureKey;
import com.soklet.MetricsCollector.RequestRejectionKey;
import com.soklet.MetricsCollector.ServerSentEventBroadcastOutcome;
import com.soklet.MetricsCollector.ServerSentEventCommentRouteBroadcastOutcomeKey;
import com.soklet.MetricsCollector.ServerSentEventCommentRouteDropKey;
import com.soklet.MetricsCollector.ServerSentEventDropReason;
import com.soklet.MetricsCollector.ServerSentEventRouteBroadcastOutcomeKey;
import com.soklet.MetricsCollector.ServerSentEventRouteDropKey;
import com.soklet.MetricsCollector.Snapshot;
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

	private final ConcurrentHashMap<IdentityKey<Request>, RequestState> requestsInFlightByIdentity;
	private final ConcurrentHashMap<Object, RequestState> requestsInFlightById;
	private final ConcurrentHashMap<RequestReadFailureKey, LongAdder> httpRequestReadFailuresByReason;
	private final ConcurrentHashMap<RequestRejectionKey, LongAdder> httpRequestRejectionsByReason;
	private final ConcurrentHashMap<RequestReadFailureKey, LongAdder> sseRequestReadFailuresByReason;
	private final ConcurrentHashMap<RequestRejectionKey, LongAdder> sseRequestRejectionsByReason;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpRequestDurationByRouteStatus;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpHandlerDurationByRouteStatus;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpTimeToFirstByteByRouteStatus;
	private final ConcurrentHashMap<ServerRouteKey, Histogram> httpRequestBodyBytesByRoute;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpResponseBodyBytesByRouteStatus;
	private final ConcurrentHashMap<IdentityKey<ServerSentEventConnection>, SseConnectionState> sseConnectionsByIdentity;
	private final ConcurrentHashMap<ServerSentEventRouteKey, LongAdder> sseHandshakesAcceptedByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteHandshakeFailureKey, LongAdder> sseHandshakesRejectedByRouteAndReason;
	private final ConcurrentHashMap<ServerSentEventRouteBroadcastOutcomeKey, LongAdder> sseEventBroadcastOutcomesByRoute;
	private final ConcurrentHashMap<ServerSentEventCommentRouteBroadcastOutcomeKey, LongAdder> sseCommentBroadcastOutcomesByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteDropKey, LongAdder> sseEventDropsByRouteAndReason;
	private final ConcurrentHashMap<ServerSentEventCommentRouteDropKey, LongAdder> sseCommentDropsByRouteAndReason;
	private final ConcurrentHashMap<ServerSentEventRouteKey, Histogram> sseTimeToFirstEventByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteKey, Histogram> sseEventWriteDurationByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteKey, Histogram> sseEventDeliveryLagByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteKey, Histogram> sseEventSizeByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteKey, Histogram> sseQueueDepthByRoute;
	private final ConcurrentHashMap<ServerSentEventCommentRouteKey, Histogram> sseCommentDeliveryLagByRoute;
	private final ConcurrentHashMap<ServerSentEventCommentRouteKey, Histogram> sseCommentSizeByRoute;
	private final ConcurrentHashMap<ServerSentEventCommentRouteKey, Histogram> sseCommentQueueDepthByRoute;
	private final ConcurrentHashMap<ServerSentEventRouteTerminationKey, Histogram> sseConnectionDurationByRouteAndReason;
	private final LongAdder activeRequests;
	private final LongAdder activeSseConnections;
	private final LongAdder httpConnectionsAccepted;
	private final LongAdder httpConnectionsRejected;
	private final LongAdder sseConnectionsAccepted;
	private final LongAdder sseConnectionsRejected;
	private final AtomicBoolean includeSseMetrics;

	@NonNull
	public static DefaultMetricsCollector defaultInstance() {
		return new DefaultMetricsCollector();
	}

	private DefaultMetricsCollector() {
		this.requestsInFlightByIdentity = new ConcurrentHashMap<>();
		this.requestsInFlightById = new ConcurrentHashMap<>();
		this.httpRequestReadFailuresByReason = new ConcurrentHashMap<>();
		this.httpRequestRejectionsByReason = new ConcurrentHashMap<>();
		this.sseRequestReadFailuresByReason = new ConcurrentHashMap<>();
		this.sseRequestRejectionsByReason = new ConcurrentHashMap<>();
		this.httpRequestDurationByRouteStatus = new ConcurrentHashMap<>();
		this.httpHandlerDurationByRouteStatus = new ConcurrentHashMap<>();
		this.httpTimeToFirstByteByRouteStatus = new ConcurrentHashMap<>();
		this.httpRequestBodyBytesByRoute = new ConcurrentHashMap<>();
		this.httpResponseBodyBytesByRouteStatus = new ConcurrentHashMap<>();
		this.sseConnectionsByIdentity = new ConcurrentHashMap<>();
		this.sseHandshakesAcceptedByRoute = new ConcurrentHashMap<>();
		this.sseHandshakesRejectedByRouteAndReason = new ConcurrentHashMap<>();
		this.sseEventBroadcastOutcomesByRoute = new ConcurrentHashMap<>();
		this.sseCommentBroadcastOutcomesByRoute = new ConcurrentHashMap<>();
		this.sseEventDropsByRouteAndReason = new ConcurrentHashMap<>();
		this.sseCommentDropsByRouteAndReason = new ConcurrentHashMap<>();
		this.sseTimeToFirstEventByRoute = new ConcurrentHashMap<>();
		this.sseEventWriteDurationByRoute = new ConcurrentHashMap<>();
		this.sseEventDeliveryLagByRoute = new ConcurrentHashMap<>();
		this.sseEventSizeByRoute = new ConcurrentHashMap<>();
		this.sseQueueDepthByRoute = new ConcurrentHashMap<>();
		this.sseCommentDeliveryLagByRoute = new ConcurrentHashMap<>();
		this.sseCommentSizeByRoute = new ConcurrentHashMap<>();
		this.sseCommentQueueDepthByRoute = new ConcurrentHashMap<>();
		this.sseConnectionDurationByRouteAndReason = new ConcurrentHashMap<>();
		this.activeRequests = new LongAdder();
		this.activeSseConnections = new LongAdder();
		this.httpConnectionsAccepted = new LongAdder();
		this.httpConnectionsRejected = new LongAdder();
		this.sseConnectionsAccepted = new LongAdder();
		this.sseConnectionsRejected = new LongAdder();
		this.includeSseMetrics = new AtomicBoolean(false);
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		this.includeSseMetrics.set(sokletConfig.getServerSentEventServer().isPresent());
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
		else if (serverType == ServerType.SERVER_SENT_EVENT)
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
		else if (serverType == ServerType.SERVER_SENT_EVENT)
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
		else if (serverType == ServerType.SERVER_SENT_EVENT)
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
		else if (serverType == ServerType.SERVER_SENT_EVENT)
			counterFor(this.sseRequestRejectionsByReason, key).increment();
	}

	@Override
	public void didStartRequestHandling(@NonNull ServerType serverType,
																			@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod) {
		requireNonNull(serverType);
		requireNonNull(request);

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
				new ServerRouteKey(method, routeContext.getRouteType(), routeContext.getRoute()),
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

		RequestState state = requestStateFor(request);

		if (state == null)
			return;

		if (!state.markHandlerDurationRecorded())
			return;

		long elapsedNanos = System.nanoTime() - state.getStartedAtNanos();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		ServerRouteStatusKey key = new ServerRouteStatusKey(state.getMethod(), state.getRouteType(),
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

		this.activeRequests.decrement();
		removeRequestState(request);

		RouteContext routeContext = routeFor(resourceMethod);
		HttpMethod method = request.getHttpMethod();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		ServerRouteStatusKey key = new ServerRouteStatusKey(method, routeContext.getRouteType(),
				routeContext.getRoute(), statusClass);
		histogramFor(this.httpRequestDurationByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(duration.toNanos());

		long responseBodyBytes = marshaledResponse.getBody()
				.map(body -> (long) body.length)
				.orElse(0L);

		histogramFor(this.httpResponseBodyBytesByRouteStatus, key, HTTP_BODY_BYTES_BUCKETS)
				.record(responseBodyBytes);
	}

	@Override
	public void didEstablishServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection) {
		requireNonNull(serverSentEventConnection);

		RouteContext routeContext = routeFor(serverSentEventConnection);

		counterFor(this.sseHandshakesAcceptedByRoute,
				new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute())).increment();
		this.activeSseConnections.increment();
		this.sseConnectionsByIdentity.put(new IdentityKey<>(serverSentEventConnection),
				new SseConnectionState(routeContext.getRouteType(), routeContext.getRoute(), System.nanoTime()));
	}

	@Override
	public void didFailToEstablishServerSentEventConnection(@NonNull Request request,
																													 @Nullable ResourceMethod resourceMethod,
																													 ServerSentEventConnection.@NonNull HandshakeFailureReason reason,
																													 @Nullable Throwable throwable) {
		requireNonNull(request);
		requireNonNull(reason);

		RouteContext routeContext = routeFor(resourceMethod);

		counterFor(this.sseHandshakesRejectedByRouteAndReason,
				new ServerSentEventRouteHandshakeFailureKey(routeContext.getRouteType(), routeContext.getRoute(), reason))
				.increment();
	}

	@Override
	public void willWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			 @NonNull ServerSentEvent serverSentEvent) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));

		if (state == null)
			return;

		if (state.markFirstEventRecorded()) {
			long elapsedNanos = System.nanoTime() - state.getEstablishedAtNanos();
			histogramFor(this.sseTimeToFirstEventByRoute,
					new ServerSentEventRouteKey(state.getRouteType(), state.getRoute()),
					SSE_TIME_TO_FIRST_EVENT_BUCKETS_NANOS).record(elapsedNanos);
		}
	}

	@Override
	public void didWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																			@NonNull ServerSentEvent serverSentEvent,
																			@NonNull Duration writeDuration,
																			@Nullable Duration deliveryLag,
																			@Nullable Integer payloadBytes,
																			@Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);
		requireNonNull(writeDuration);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);

		if (state != null)
			state.incrementEventsSent();

		histogramFor(this.sseEventWriteDurationByRoute,
				new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
			histogramFor(this.sseEventDeliveryLagByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																						 @NonNull ServerSentEventComment serverSentEventComment,
																						 @NonNull Duration writeDuration,
																						 @Nullable Duration deliveryLag,
																						 @Nullable Integer payloadBytes,
																						 @Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEventComment);
		requireNonNull(writeDuration);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);
		ServerSentEventCommentRouteKey key = new ServerSentEventCommentRouteKey(routeContext.getRouteType(),
				routeContext.getRoute(), serverSentEventComment.getCommentType());

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
	public void didFailToWriteServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																						@NonNull ServerSentEvent serverSentEvent,
																						@NonNull Duration writeDuration,
																						@NonNull Throwable throwable,
																						@Nullable Duration deliveryLag,
																						@Nullable Integer payloadBytes,
																						@Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);

		histogramFor(this.sseEventWriteDurationByRoute,
				new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
			histogramFor(this.sseEventDeliveryLagByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteType(), routeContext.getRoute()),
					SSE_QUEUE_DEPTH_BUCKETS).record(queueDepth);
		}
	}

	@Override
	public void didFailToWriteServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																									 @NonNull ServerSentEventComment serverSentEventComment,
																									 @NonNull Duration writeDuration,
																									 @NonNull Throwable throwable,
																									 @Nullable Duration deliveryLag,
																									 @Nullable Integer payloadBytes,
																									 @Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEventComment);
		requireNonNull(writeDuration);
		requireNonNull(throwable);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);
		ServerSentEventCommentRouteKey key = new ServerSentEventCommentRouteKey(routeContext.getRouteType(),
				routeContext.getRoute(), serverSentEventComment.getCommentType());

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
	public void didDropServerSentEvent(@NonNull ServerSentEventConnection serverSentEventConnection,
																		 @NonNull ServerSentEvent serverSentEvent,
																		 @NonNull ServerSentEventDropReason reason,
																		 @Nullable Integer payloadBytes,
																		 @Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEvent);
		requireNonNull(reason);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);

		counterFor(this.sseEventDropsByRouteAndReason,
				new ServerSentEventRouteDropKey(routeContext.getRouteType(), routeContext.getRoute(), reason))
				.increment();
	}

	@Override
	public void didDropServerSentEventComment(@NonNull ServerSentEventConnection serverSentEventConnection,
																						@NonNull ServerSentEventComment serverSentEventComment,
																						@NonNull ServerSentEventDropReason reason,
																						@Nullable Integer payloadBytes,
																						@Nullable Integer queueDepth) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(serverSentEventComment);
		requireNonNull(reason);

		SseConnectionState state = this.sseConnectionsByIdentity.get(new IdentityKey<>(serverSentEventConnection));
		RouteContext routeContext = routeContextFor(state, serverSentEventConnection);

		counterFor(this.sseCommentDropsByRouteAndReason,
				new ServerSentEventCommentRouteDropKey(routeContext.getRouteType(), routeContext.getRoute(),
						serverSentEventComment.getCommentType(), reason))
				.increment();
	}

	@Override
	public void didBroadcastServerSentEvent(@NonNull ResourcePathDeclaration route,
																					int attempted,
																					int delivered,
																					int dropped) {
		requireNonNull(route);

		if (attempted > 0) {
			counterFor(this.sseEventBroadcastOutcomesByRoute,
					new ServerSentEventRouteBroadcastOutcomeKey(RouteType.MATCHED, route, ServerSentEventBroadcastOutcome.ATTEMPTED))
					.add(attempted);
		}

		if (delivered > 0) {
			counterFor(this.sseEventBroadcastOutcomesByRoute,
					new ServerSentEventRouteBroadcastOutcomeKey(RouteType.MATCHED, route, ServerSentEventBroadcastOutcome.DELIVERED))
					.add(delivered);
		}

		if (dropped > 0) {
			counterFor(this.sseEventBroadcastOutcomesByRoute,
					new ServerSentEventRouteBroadcastOutcomeKey(RouteType.MATCHED, route, ServerSentEventBroadcastOutcome.DROPPED))
					.add(dropped);
		}
	}

	@Override
	public void didBroadcastServerSentEventComment(@NonNull ResourcePathDeclaration route,
																									ServerSentEventComment.@NonNull CommentType commentType,
																									int attempted,
																									int delivered,
																									int dropped) {
		requireNonNull(route);
		requireNonNull(commentType);

		if (attempted > 0) {
			counterFor(this.sseCommentBroadcastOutcomesByRoute,
					new ServerSentEventCommentRouteBroadcastOutcomeKey(RouteType.MATCHED, route, commentType, ServerSentEventBroadcastOutcome.ATTEMPTED))
					.add(attempted);
		}

		if (delivered > 0) {
			counterFor(this.sseCommentBroadcastOutcomesByRoute,
					new ServerSentEventCommentRouteBroadcastOutcomeKey(RouteType.MATCHED, route, commentType, ServerSentEventBroadcastOutcome.DELIVERED))
					.add(delivered);
		}

		if (dropped > 0) {
			counterFor(this.sseCommentBroadcastOutcomesByRoute,
					new ServerSentEventCommentRouteBroadcastOutcomeKey(RouteType.MATCHED, route, commentType, ServerSentEventBroadcastOutcome.DROPPED))
					.add(dropped);
		}
	}

	@Override
	public void didTerminateServerSentEventConnection(@NonNull ServerSentEventConnection serverSentEventConnection,
																										@NonNull Duration connectionDuration,
																										ServerSentEventConnection.@NonNull TerminationReason terminationReason,
																										@Nullable Throwable throwable) {
		requireNonNull(serverSentEventConnection);
		requireNonNull(connectionDuration);
		requireNonNull(terminationReason);

		this.activeSseConnections.decrement();
		this.sseConnectionsByIdentity.remove(new IdentityKey<>(serverSentEventConnection));

		RouteContext routeContext = routeFor(serverSentEventConnection);

		ServerSentEventRouteTerminationKey key = new ServerSentEventRouteTerminationKey(routeContext.getRouteType(),
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
				.httpConnectionsAccepted(getHttpConnectionsAccepted())
				.httpConnectionsRejected(getHttpConnectionsRejected())
				.sseConnectionsAccepted(getSseConnectionsAccepted())
				.sseConnectionsRejected(getSseConnectionsRejected())
				.httpRequestReadFailures(snapshotHttpRequestReadFailures())
				.httpRequestRejections(snapshotHttpRequestRejections())
				.sseRequestReadFailures(snapshotSseRequestReadFailures())
				.sseRequestRejections(snapshotSseRequestRejections())
				.sseHandshakesAccepted(snapshotSseHandshakesAccepted())
				.sseHandshakesRejected(snapshotSseHandshakesRejected())
				.sseEventBroadcastOutcomes(snapshotSseEventBroadcastOutcomes())
				.sseCommentBroadcastOutcomes(snapshotSseCommentBroadcastOutcomes())
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
			appendCounter(sb, "soklet_sse_event_broadcasts_total", "Total SSE event broadcast outcomes",
					snapshot.getSseEventBroadcastOutcomes(), DefaultMetricsCollector::labelsForSseBroadcastOutcomeKey, options);
			appendCounter(sb, "soklet_sse_comment_broadcasts_total", "Total SSE comment broadcast outcomes",
					snapshot.getSseCommentBroadcastOutcomes(), DefaultMetricsCollector::labelsForSseCommentBroadcastOutcomeKey, options);
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
	Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpRequestDurations() {
		return snapshotMap(this.httpRequestDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpHandlerDurations() {
		return snapshotMap(this.httpHandlerDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpTimeToFirstByte() {
		return snapshotMap(this.httpTimeToFirstByteByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteKey, @NonNull HistogramSnapshot> snapshotHttpRequestBodyBytes() {
		return snapshotMap(this.httpRequestBodyBytesByRoute);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull HistogramSnapshot> snapshotHttpResponseBodyBytes() {
		return snapshotMap(this.httpResponseBodyBytesByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> snapshotSseTimeToFirstEvent() {
		return snapshotMap(this.sseTimeToFirstEventByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventWriteDurations() {
		return snapshotMap(this.sseEventWriteDurationByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventDeliveryLag() {
		return snapshotMap(this.sseEventDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> snapshotSseEventSizes() {
		return snapshotMap(this.sseEventSizeByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull HistogramSnapshot> snapshotSseQueueDepth() {
		return snapshotMap(this.sseQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentDeliveryLag() {
		return snapshotMap(this.sseCommentDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentSizes() {
		return snapshotMap(this.sseCommentSizeByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull HistogramSnapshot> snapshotSseCommentQueueDepth() {
		return snapshotMap(this.sseCommentQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Long> snapshotSseHandshakesAccepted() {
		return snapshotCounterMap(this.sseHandshakesAcceptedByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteHandshakeFailureKey, @NonNull Long> snapshotSseHandshakesRejected() {
		return snapshotCounterMap(this.sseHandshakesRejectedByRouteAndReason);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteBroadcastOutcomeKey, @NonNull Long> snapshotSseEventBroadcastOutcomes() {
		return snapshotCounterMap(this.sseEventBroadcastOutcomesByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteBroadcastOutcomeKey, @NonNull Long> snapshotSseCommentBroadcastOutcomes() {
		return snapshotCounterMap(this.sseCommentBroadcastOutcomesByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteDropKey, @NonNull Long> snapshotSseEventDrops() {
		return snapshotCounterMap(this.sseEventDropsByRouteAndReason);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteDropKey, @NonNull Long> snapshotSseCommentDrops() {
		return snapshotCounterMap(this.sseCommentDropsByRouteAndReason);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull HistogramSnapshot> snapshotSseConnectionDurations() {
		return snapshotMap(this.sseConnectionDurationByRouteAndReason);
	}

	@Override
	public void reset() {
		this.activeRequests.reset();
		this.activeSseConnections.reset();
		this.httpConnectionsAccepted.reset();
		this.httpConnectionsRejected.reset();
		this.sseConnectionsAccepted.reset();
		this.sseConnectionsRejected.reset();
		this.requestsInFlightByIdentity.clear();
		this.requestsInFlightById.clear();
		this.sseConnectionsByIdentity.clear();
		resetCounterMap(this.httpRequestReadFailuresByReason);
		resetCounterMap(this.httpRequestRejectionsByReason);
		resetCounterMap(this.sseRequestReadFailuresByReason);
		resetCounterMap(this.sseRequestRejectionsByReason);
		resetCounterMap(this.sseHandshakesAcceptedByRoute);
		resetCounterMap(this.sseHandshakesRejectedByRouteAndReason);
		resetCounterMap(this.sseEventBroadcastOutcomesByRoute);
		resetCounterMap(this.sseCommentBroadcastOutcomesByRoute);
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
	}

	@NonNull
	private static RouteContext routeFor(@Nullable ResourceMethod resourceMethod) {
		if (resourceMethod == null)
			return new RouteContext(RouteType.UNMATCHED, null);

		return new RouteContext(RouteType.MATCHED, resourceMethod.getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeFor(@NonNull ServerSentEventConnection serverSentEventConnection) {
		requireNonNull(serverSentEventConnection);
		return new RouteContext(RouteType.MATCHED, serverSentEventConnection.getResourceMethod().getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeContextFor(@Nullable SseConnectionState state,
																							@NonNull ServerSentEventConnection serverSentEventConnection) {
		requireNonNull(serverSentEventConnection);

		if (state != null)
			return new RouteContext(state.getRouteType(), state.getRoute());

		return routeFor(serverSentEventConnection);
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
	private static LabelSet labelsForHttpStatusKey(@NonNull ServerRouteStatusKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("method", key.method().name());
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("status_class", key.statusClass());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForHttpRouteKey(@NonNull ServerRouteKey key) {
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
	private static LabelSet labelsForSseRouteKey(@NonNull ServerSentEventRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(1);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseBroadcastOutcomeKey(@NonNull ServerSentEventRouteBroadcastOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("outcome", key.outcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentBroadcastOutcomeKey(@NonNull ServerSentEventCommentRouteBroadcastOutcomeKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		labels.put("outcome", key.outcome().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseDropKey(@NonNull ServerSentEventRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("drop_reason", key.dropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentDropKey(@NonNull ServerSentEventCommentRouteDropKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(3);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		labels.put("drop_reason", key.dropReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseHandshakeFailureKey(@NonNull ServerSentEventRouteHandshakeFailureKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("handshake_failure_reason", key.handshakeFailureReason().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseCommentKey(@NonNull ServerSentEventCommentRouteKey key) {
		requireNonNull(key);

		Map<String, String> labels = new LinkedHashMap<>(2);
		labels.put("route", routeLabel(key.routeType(), key.route()));
		labels.put("comment_type", key.commentType().name());
		return new LabelSet(labels);
	}

	@NonNull
	private static LabelSet labelsForSseTerminationKey(@NonNull ServerSentEventRouteTerminationKey key) {
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
