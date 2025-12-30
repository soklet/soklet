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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

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
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpRequestDurationByRouteStatus;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpHandlerDurationByRouteStatus;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpTimeToFirstByteByRouteStatus;
	private final ConcurrentHashMap<ServerRouteKey, Histogram> httpRequestBodyBytesByRoute;
	private final ConcurrentHashMap<ServerRouteStatusKey, Histogram> httpResponseBodyBytesByRouteStatus;
	private final ConcurrentHashMap<IdentityKey<ServerSentEventConnection>, SseConnectionState> sseConnectionsByIdentity;
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
	private final AtomicBoolean includeSseMetrics;

	@NonNull
	public static DefaultMetricsCollector withDefaults() {
		return new DefaultMetricsCollector();
	}

	private DefaultMetricsCollector() {
		this.requestsInFlightByIdentity = new ConcurrentHashMap<>();
		this.requestsInFlightById = new ConcurrentHashMap<>();
		this.httpRequestDurationByRouteStatus = new ConcurrentHashMap<>();
		this.httpHandlerDurationByRouteStatus = new ConcurrentHashMap<>();
		this.httpTimeToFirstByteByRouteStatus = new ConcurrentHashMap<>();
		this.httpRequestBodyBytesByRoute = new ConcurrentHashMap<>();
		this.httpResponseBodyBytesByRouteStatus = new ConcurrentHashMap<>();
		this.sseConnectionsByIdentity = new ConcurrentHashMap<>();
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
		this.includeSseMetrics = new AtomicBoolean(false);
	}

	void initialize(@NonNull SokletConfig sokletConfig) {
		requireNonNull(sokletConfig);
		this.includeSseMetrics.set(sokletConfig.getServerSentEventServer().isPresent());
	}

	@Override
	public void didStartRequestHandling(@NonNull Request request,
																			@Nullable ResourceMethod resourceMethod) {
		requireNonNull(request);

		RouteContext routeContext = routeFor(resourceMethod);
		HttpMethod method = request.getHttpMethod();

		this.activeRequests.increment();
		RequestState state = new RequestState(new IdentityKey<>(request), request.getId(), System.nanoTime(), method,
				routeContext.getRouteKind(), routeContext.getRoute());
		this.requestsInFlightByIdentity.put(state.getIdentityKey(), state);
		this.requestsInFlightById.put(state.getRequestId(), state);

		long requestBodyBytes = request.getBody()
				.map(body -> (long) body.length)
				.orElse(0L);

		Histogram requestBodyHistogram = histogramFor(this.httpRequestBodyBytesByRoute,
				new ServerRouteKey(method, routeContext.getRouteKind(), routeContext.getRoute()),
				HTTP_BODY_BYTES_BUCKETS);
		requestBodyHistogram.record(requestBodyBytes);
	}

	@Override
	public void willWriteResponse(@NonNull Request request,
																@Nullable ResourceMethod resourceMethod,
																@NonNull MarshaledResponse marshaledResponse) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);

		RequestState state = requestStateFor(request);

		if (state == null)
			return;

		if (!state.markHandlerDurationRecorded())
			return;

		long elapsedNanos = System.nanoTime() - state.getStartedAtNanos();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		ServerRouteStatusKey key = new ServerRouteStatusKey(state.getMethod(), state.getRouteKind(),
				state.getRoute(), statusClass);
		histogramFor(this.httpHandlerDurationByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(elapsedNanos);

		// TTFB is approximated as "time to start writing response"
		histogramFor(this.httpTimeToFirstByteByRouteStatus, key, HTTP_LATENCY_BUCKETS_NANOS)
				.record(elapsedNanos);
	}

	@Override
	public void didFinishRequestHandling(@NonNull Request request,
																			 @Nullable ResourceMethod resourceMethod,
																			 @NonNull MarshaledResponse marshaledResponse,
																			 @NonNull Duration duration,
																			 @NonNull List<@NonNull Throwable> throwables) {
		requireNonNull(request);
		requireNonNull(marshaledResponse);
		requireNonNull(duration);
		requireNonNull(throwables);

		this.activeRequests.decrement();
		removeRequestState(request);

		RouteContext routeContext = routeFor(resourceMethod);
		HttpMethod method = request.getHttpMethod();
		String statusClass = statusClassFor(marshaledResponse.getStatusCode());

		ServerRouteStatusKey key = new ServerRouteStatusKey(method, routeContext.getRouteKind(),
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

		this.activeSseConnections.increment();
		this.sseConnectionsByIdentity.put(new IdentityKey<>(serverSentEventConnection),
				new SseConnectionState(routeContext.getRouteKind(), routeContext.getRoute(), System.nanoTime()));
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
					new ServerSentEventRouteKey(state.getRouteKind(), state.getRoute()),
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
				new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
			histogramFor(this.sseEventDeliveryLagByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
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
		ServerSentEventCommentRouteKey key = new ServerSentEventCommentRouteKey(routeContext.getRouteKind(),
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
				new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
				SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(writeDuration.toNanos());

		if (deliveryLag != null) {
			long deliveryLagNanos = Math.max(0L, deliveryLag.toNanos());
			histogramFor(this.sseEventDeliveryLagByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
					SSE_EVENT_WRITE_DURATION_BUCKETS_NANOS).record(deliveryLagNanos);
		}

		if (payloadBytes != null) {
			histogramFor(this.sseEventSizeByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
					HTTP_BODY_BYTES_BUCKETS).record(payloadBytes);
		}

		if (queueDepth != null) {
			histogramFor(this.sseQueueDepthByRoute,
					new ServerSentEventRouteKey(routeContext.getRouteKind(), routeContext.getRoute()),
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
		ServerSentEventCommentRouteKey key = new ServerSentEventCommentRouteKey(routeContext.getRouteKind(),
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

		ServerSentEventRouteTerminationKey key = new ServerSentEventRouteTerminationKey(routeContext.getRouteKind(),
				routeContext.getRoute(), terminationReason);

		histogramFor(this.sseConnectionDurationByRouteAndReason, key, SSE_CONNECTION_DURATION_BUCKETS_NANOS)
				.record(connectionDuration.toNanos());
	}

	@Override
	@NonNull
	public Optional<MetricsSnapshot> snapshot() {
		return Optional.of(new MetricsSnapshot(
				getActiveRequests(),
				getActiveSseConnections(),
				snapshotHttpRequestDurations(),
				snapshotHttpHandlerDurations(),
				snapshotHttpTimeToFirstByte(),
				snapshotHttpRequestBodyBytes(),
				snapshotHttpResponseBodyBytes(),
				snapshotSseTimeToFirstEvent(),
				snapshotSseEventWriteDurations(),
				snapshotSseEventDeliveryLag(),
				snapshotSseEventSizes(),
				snapshotSseQueueDepth(),
				snapshotSseCommentDeliveryLag(),
				snapshotSseCommentSizes(),
				snapshotSseCommentQueueDepth(),
				snapshotSseConnectionDurations()));
	}

	@Override
	@NonNull
	public Optional<String> snapshotAsText() {
		MetricsSnapshot snapshot = snapshot().orElse(null);

		if (snapshot == null)
			return Optional.empty();

		StringBuilder sb = new StringBuilder(8192);

		appendGauge(sb, "soklet_http_requests_active", "Currently active HTTP requests",
				snapshot.getActiveRequests());

		appendHistogram(sb, "soklet_http_request_duration_nanos", "HTTP request duration in nanoseconds",
				snapshot.getHttpRequestDurations(), DefaultMetricsCollector::labelsForHttpStatusKey);
		appendHistogram(sb, "soklet_http_handler_duration_nanos", "HTTP handler duration in nanoseconds",
				snapshot.getHttpHandlerDurations(), DefaultMetricsCollector::labelsForHttpStatusKey);
		appendHistogram(sb, "soklet_http_ttfb_nanos", "HTTP time to first byte in nanoseconds",
				snapshot.getHttpTimeToFirstByte(), DefaultMetricsCollector::labelsForHttpStatusKey);
		appendHistogram(sb, "soklet_http_request_body_bytes", "HTTP request body size in bytes",
				snapshot.getHttpRequestBodyBytes(), DefaultMetricsCollector::labelsForHttpRouteKey);
		appendHistogram(sb, "soklet_http_response_body_bytes", "HTTP response body size in bytes",
				snapshot.getHttpResponseBodyBytes(), DefaultMetricsCollector::labelsForHttpStatusKey);

		if (this.includeSseMetrics.get()) {
			appendGauge(sb, "soklet_sse_connections_active", "Currently active SSE connections",
					snapshot.getActiveSseConnections());

			appendHistogram(sb, "soklet_sse_time_to_first_event_nanos", "SSE time to first event in nanoseconds",
					snapshot.getSseTimeToFirstEvent(), DefaultMetricsCollector::labelsForSseRouteKey);
			appendHistogram(sb, "soklet_sse_event_write_duration_nanos", "SSE event write duration in nanoseconds",
					snapshot.getSseEventWriteDurations(), DefaultMetricsCollector::labelsForSseRouteKey);
			appendHistogram(sb, "soklet_sse_event_delivery_lag_nanos", "SSE event delivery lag in nanoseconds",
					snapshot.getSseEventDeliveryLag(), DefaultMetricsCollector::labelsForSseRouteKey);
			appendHistogram(sb, "soklet_sse_event_size_bytes", "SSE event size in bytes",
					snapshot.getSseEventSizes(), DefaultMetricsCollector::labelsForSseRouteKey);
			appendHistogram(sb, "soklet_sse_queue_depth", "SSE queue depth",
					snapshot.getSseQueueDepth(), DefaultMetricsCollector::labelsForSseRouteKey);

			appendHistogram(sb, "soklet_sse_comment_delivery_lag_nanos", "SSE comment delivery lag in nanoseconds",
					snapshot.getSseCommentDeliveryLag(), DefaultMetricsCollector::labelsForSseCommentKey);
			appendHistogram(sb, "soklet_sse_comment_size_bytes", "SSE comment size in bytes",
					snapshot.getSseCommentSizes(), DefaultMetricsCollector::labelsForSseCommentKey);
			appendHistogram(sb, "soklet_sse_comment_queue_depth", "SSE comment queue depth",
					snapshot.getSseCommentQueueDepth(), DefaultMetricsCollector::labelsForSseCommentKey);

			appendHistogram(sb, "soklet_sse_connection_duration_nanos", "SSE connection duration in nanoseconds",
					snapshot.getSseConnectionDurations(), DefaultMetricsCollector::labelsForSseTerminationKey);
		}

		return Optional.of(sb.toString());
	}

	long getActiveRequests() {
		return this.activeRequests.sum();
	}

	long getActiveSseConnections() {
		return this.activeSseConnections.sum();
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> snapshotHttpRequestDurations() {
		return snapshotMap(this.httpRequestDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> snapshotHttpHandlerDurations() {
		return snapshotMap(this.httpHandlerDurationByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> snapshotHttpTimeToFirstByte() {
		return snapshotMap(this.httpTimeToFirstByteByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerRouteKey, @NonNull Snapshot> snapshotHttpRequestBodyBytes() {
		return snapshotMap(this.httpRequestBodyBytesByRoute);
	}

	@NonNull
	Map<@NonNull ServerRouteStatusKey, @NonNull Snapshot> snapshotHttpResponseBodyBytes() {
		return snapshotMap(this.httpResponseBodyBytesByRouteStatus);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> snapshotSseTimeToFirstEvent() {
		return snapshotMap(this.sseTimeToFirstEventByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> snapshotSseEventWriteDurations() {
		return snapshotMap(this.sseEventWriteDurationByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> snapshotSseEventDeliveryLag() {
		return snapshotMap(this.sseEventDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> snapshotSseEventSizes() {
		return snapshotMap(this.sseEventSizeByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteKey, @NonNull Snapshot> snapshotSseQueueDepth() {
		return snapshotMap(this.sseQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> snapshotSseCommentDeliveryLag() {
		return snapshotMap(this.sseCommentDeliveryLagByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> snapshotSseCommentSizes() {
		return snapshotMap(this.sseCommentSizeByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventCommentRouteKey, @NonNull Snapshot> snapshotSseCommentQueueDepth() {
		return snapshotMap(this.sseCommentQueueDepthByRoute);
	}

	@NonNull
	Map<@NonNull ServerSentEventRouteTerminationKey, @NonNull Snapshot> snapshotSseConnectionDurations() {
		return snapshotMap(this.sseConnectionDurationByRouteAndReason);
	}

	@Override
	public void reset() {
		this.activeRequests.reset();
		this.activeSseConnections.reset();
		this.requestsInFlightByIdentity.clear();
		this.requestsInFlightById.clear();
		this.sseConnectionsByIdentity.clear();
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
			return new RouteContext(RouteKind.UNMATCHED, null);

		return new RouteContext(RouteKind.MATCHED, resourceMethod.getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeFor(@NonNull ServerSentEventConnection serverSentEventConnection) {
		requireNonNull(serverSentEventConnection);
		return new RouteContext(RouteKind.MATCHED, serverSentEventConnection.getResourceMethod().getResourcePathDeclaration());
	}

	@NonNull
	private static RouteContext routeContextFor(@Nullable SseConnectionState state,
																							@NonNull ServerSentEventConnection serverSentEventConnection) {
		requireNonNull(serverSentEventConnection);

		if (state != null)
			return new RouteContext(state.getRouteKind(), state.getRoute());

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
	private static <K> Map<@NonNull K, @NonNull Snapshot> snapshotMap(@NonNull Map<K, Histogram> map) {
		requireNonNull(map);

		Map<K, Snapshot> snapshot = new ConcurrentHashMap<>(map.size());
		for (Map.Entry<K, Histogram> entry : map.entrySet())
			snapshot.put(entry.getKey(), entry.getValue().snapshot());
		return snapshot;
	}

	private static <K> void resetMap(@NonNull Map<K, Histogram> map) {
		requireNonNull(map);

		for (Histogram histogram : map.values())
			histogram.reset();
	}

	private static void appendGauge(@NonNull StringBuilder sb,
																	@NonNull String name,
																	@NonNull String help,
																	long value) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" gauge\n");
		sb.append(name).append(' ').append(value).append('\n');
	}

	private static <K> void appendHistogram(@NonNull StringBuilder sb,
																					@NonNull String name,
																					@NonNull String help,
																					@NonNull Map<K, Snapshot> histograms,
																					@NonNull Function<K, String> labelsProvider) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(help);
		requireNonNull(histograms);
		requireNonNull(labelsProvider);

		sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
		sb.append("# TYPE ").append(name).append(" histogram\n");

		histograms.forEach((key, histogram) -> {
			String labels = labelsProvider.apply(key);
			appendHistogramSamples(sb, name, labels, histogram);
		});
	}

	private static void appendHistogramSamples(@NonNull StringBuilder sb,
																						 @NonNull String name,
																						 @NonNull String labels,
																						 @NonNull Snapshot histogram) {
		requireNonNull(sb);
		requireNonNull(name);
		requireNonNull(labels);
		requireNonNull(histogram);

		int bucketCount = histogram.getBucketCount();

		for (int i = 0; i < bucketCount; i++) {
			long boundary = histogram.getBucketBoundary(i);
			String le = boundary == Long.MAX_VALUE ? "+Inf" : String.valueOf(boundary);

			sb.append(name).append("_bucket{").append(labels).append(",le=\"").append(le).append("\"} ")
					.append(histogram.getBucketCumulativeCount(i)).append('\n');
		}

		sb.append(name).append("_count{").append(labels).append("} ")
				.append(histogram.getCount()).append('\n');
		sb.append(name).append("_sum{").append(labels).append("} ")
				.append(histogram.getSum()).append('\n');
	}

	@NonNull
	private static String labelsForHttpStatusKey(@NonNull ServerRouteStatusKey key) {
		requireNonNull(key);

		return label("method", key.method().name())
				+ ',' + label("route", routeLabel(key.routeKind(), key.route()))
				+ ',' + label("status_class", key.statusClass());
	}

	@NonNull
	private static String labelsForHttpRouteKey(@NonNull ServerRouteKey key) {
		requireNonNull(key);

		return label("method", key.method().name())
				+ ',' + label("route", routeLabel(key.routeKind(), key.route()));
	}

	@NonNull
	private static String labelsForSseRouteKey(@NonNull ServerSentEventRouteKey key) {
		requireNonNull(key);

		return label("route", routeLabel(key.routeKind(), key.route()));
	}

	@NonNull
	private static String labelsForSseCommentKey(@NonNull ServerSentEventCommentRouteKey key) {
		requireNonNull(key);

		return label("route", routeLabel(key.routeKind(), key.route()))
				+ ',' + label("comment_type", key.commentType().name());
	}

	@NonNull
	private static String labelsForSseTerminationKey(@NonNull ServerSentEventRouteTerminationKey key) {
		requireNonNull(key);

		return label("route", routeLabel(key.routeKind(), key.route()))
				+ ',' + label("termination_reason", key.terminationReason().name());
	}

	@NonNull
	private static String routeLabel(@NonNull RouteKind routeKind,
																	 @Nullable ResourcePathDeclaration route) {
		requireNonNull(routeKind);

		if (routeKind == RouteKind.UNMATCHED || route == null)
			return "unmatched";

		return route.getPath();
	}

	@NonNull
	private static String label(@NonNull String name,
															@NonNull String value) {
		requireNonNull(name);
		requireNonNull(value);

		return name + "=\"" + escapeLabelValue(value) + "\"";
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
		private final RouteKind routeKind;
		@Nullable
		private final ResourcePathDeclaration route;

		private RouteContext(@NonNull RouteKind routeKind,
												 @Nullable ResourcePathDeclaration route) {
			this.routeKind = requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
			this.route = route;
		}

		@NonNull
		RouteKind getRouteKind() {
			return this.routeKind;
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
		private final RouteKind routeKind;
		@Nullable
		private final ResourcePathDeclaration route;
		@NonNull
		private final AtomicBoolean handlerDurationRecorded;

		private RequestState(@NonNull IdentityKey<Request> identityKey,
												 @NonNull Object requestId,
												 long startedAtNanos,
												 @NonNull HttpMethod method,
												 @NonNull RouteKind routeKind,
												 @Nullable ResourcePathDeclaration route) {
			this.identityKey = requireNonNull(identityKey);
			this.requestId = requireNonNull(requestId);
			this.startedAtNanos = startedAtNanos;
			this.method = requireNonNull(method);
			this.routeKind = requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
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
		RouteKind getRouteKind() {
			return this.routeKind;
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
		private final RouteKind routeKind;
		@Nullable
		private final ResourcePathDeclaration route;
		@NonNull
		private final AtomicBoolean firstEventRecorded;
		@NonNull
		private final LongAdder eventsSent;

		private SseConnectionState(@NonNull RouteKind routeKind,
															 @Nullable ResourcePathDeclaration route,
															 long establishedAtNanos) {
			this.routeKind = requireNonNull(routeKind);
			if (routeKind == RouteKind.MATCHED && route == null)
				throw new IllegalArgumentException("Route must be provided when RouteKind is MATCHED");
			if (routeKind == RouteKind.UNMATCHED && route != null)
				throw new IllegalArgumentException("Route must be null when RouteKind is UNMATCHED");
			this.route = route;
			this.establishedAtNanos = establishedAtNanos;
			this.firstEventRecorded = new AtomicBoolean(false);
			this.eventsSent = new LongAdder();
		}

		long getEstablishedAtNanos() {
			return this.establishedAtNanos;
		}

		@NonNull
		RouteKind getRouteKind() {
			return this.routeKind;
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
