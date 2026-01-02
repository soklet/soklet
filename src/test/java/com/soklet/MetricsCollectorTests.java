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

import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.MetricsCollector.ServerRouteKey;
import com.soklet.MetricsCollector.ServerRouteStatusKey;
import com.soklet.MetricsCollector.RouteType;
import com.soklet.MetricsCollector.ServerSentEventCommentRouteKey;
import com.soklet.MetricsCollector.ServerSentEventRouteKey;
import com.soklet.MetricsCollector.ServerSentEventRouteTerminationKey;
import com.soklet.MetricsCollector.HistogramSnapshot;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class MetricsCollectorTests {
	@Test
	public void httpMetricsSnapshotAndReset() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();
		ResourceMethod resourceMethod = resourceMethodFor("/widgets/{id}", HttpMethod.POST, "createWidget", false);
		Request request = Request.withPath(HttpMethod.POST, "/widgets/123")
				.body(new byte[]{1, 2, 3})
				.build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(201)
				.body(new byte[]{9, 8})
				.build();
		collector.willAcceptConnection(ServerType.STANDARD_HTTP, null);
		collector.didAcceptConnection(ServerType.STANDARD_HTTP, null);
		collector.didFailToAcceptConnection(ServerType.STANDARD_HTTP, null, ConnectionRejectionReason.MAX_CONNECTIONS, null);
		collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		collector.willWriteResponse(ServerType.STANDARD_HTTP, request, resourceMethod, response);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofMillis(5), List.of());

		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();

		ResourcePathDeclaration widgetRoute = ResourcePathDeclaration.withPath("/widgets/{id}");
		ServerRouteStatusKey statusKey = new ServerRouteStatusKey(HttpMethod.POST, RouteType.MATCHED, widgetRoute, "2xx");
		ServerRouteKey routeKey = new ServerRouteKey(HttpMethod.POST, RouteType.MATCHED, widgetRoute);

		HistogramSnapshot requestDurations = snapshot.getHttpRequestDurations().get(statusKey);
		assertNotNull(requestDurations);
		assertEquals(1L, requestDurations.getCount());

		HistogramSnapshot handlerDurations = snapshot.getHttpHandlerDurations().get(statusKey);
		assertNotNull(handlerDurations);
		assertEquals(1L, handlerDurations.getCount());

		HistogramSnapshot timeToFirstByte = snapshot.getHttpTimeToFirstByte().get(statusKey);
		assertNotNull(timeToFirstByte);
		assertEquals(1L, timeToFirstByte.getCount());

		HistogramSnapshot requestBodyBytes = snapshot.getHttpRequestBodyBytes().get(routeKey);
		assertNotNull(requestBodyBytes);
		assertEquals(1L, requestBodyBytes.getCount());
		assertEquals(3L, requestBodyBytes.getSum());

		HistogramSnapshot responseBodyBytes = snapshot.getHttpResponseBodyBytes().get(statusKey);
		assertNotNull(responseBodyBytes);
		assertEquals(1L, responseBodyBytes.getCount());
		assertEquals(2L, responseBodyBytes.getSum());

		assertEquals(1L, snapshot.getHttpConnectionsAccepted());
		assertEquals(1L, snapshot.getHttpConnectionsRejected());
		assertEquals(0L, snapshot.getActiveRequests());

		collector.reset();
		MetricsCollector.Snapshot resetSnapshot = collector.snapshot().orElseThrow();
		HistogramSnapshot resetRequestDurations = resetSnapshot.getHttpRequestDurations().get(statusKey);
		assertTrue(resetRequestDurations == null || resetRequestDurations.getCount() == 0L);
		assertEquals(0L, resetSnapshot.getHttpConnectionsAccepted());
		assertEquals(0L, resetSnapshot.getHttpConnectionsRejected());
		assertEquals(0L, resetSnapshot.getActiveRequests());
	}

	@Test
	public void snapshotTextRespectsSseConfiguration() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();
		MetricsCollector.SnapshotTextOptions prometheusOptions = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.build();

		SokletConfig noSseConfig = SokletConfig.withServer(Server.withPort(0).build())
				.metricsCollector(collector)
				.build();
		collector.initialize(noSseConfig);

		String noSseSnapshot = collector.snapshotText(prometheusOptions).orElseThrow();
		assertTrue(noSseSnapshot.contains("soklet_http_requests_active"));
		assertTrue(noSseSnapshot.contains("soklet_http_connections_accepted_total"));
		assertFalse(noSseSnapshot.contains("soklet_sse_connections_active"));
		assertFalse(noSseSnapshot.contains("soklet_sse_connections_accepted_total"));

		SokletConfig withSseConfig = SokletConfig.withServer(Server.withPort(0).build())
				.serverSentEventServer(ServerSentEventServer.withPort(0).build())
				.metricsCollector(collector)
				.build();
		collector.initialize(withSseConfig);

		String withSseSnapshot = collector.snapshotText(prometheusOptions).orElseThrow();
		assertTrue(withSseSnapshot.contains("soklet_sse_connections_active"));
		assertTrue(withSseSnapshot.contains("soklet_sse_connections_accepted_total"));
		assertTrue(withSseSnapshot.contains("soklet_sse_connections_rejected_total"));

		ResourceMethod resourceMethod = resourceMethodFor("/widgets/{id}", HttpMethod.GET, "createWidget", false);
		Request request = Request.withPath(HttpMethod.GET, "/widgets/123").build();
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).build();

		collector.didStartRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod);
		collector.willWriteResponse(ServerType.STANDARD_HTTP, request, resourceMethod, response);
		collector.didFinishRequestHandling(ServerType.STANDARD_HTTP, request, resourceMethod, response, Duration.ofSeconds(2), List.of());

		MetricsCollector.SnapshotTextOptions includeZeroBuckets = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.includeZeroBuckets(true)
				.build();
		String withZeroBucketsSnapshot = collector.snapshotText(includeZeroBuckets).orElseThrow();
		assertTrue(withZeroBucketsSnapshot.contains(
				"soklet_http_request_duration_nanos_bucket{method=\"GET\",route=\"/widgets/{id}\",status_class=\"2xx\",le=\"1000000\"}"));

		MetricsCollector.SnapshotTextOptions excludeZeroBuckets = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.includeZeroBuckets(false)
				.build();
		String withoutZeroBucketsSnapshot = collector.snapshotText(excludeZeroBuckets).orElseThrow();
		assertFalse(withoutZeroBucketsSnapshot.contains(
				"soklet_http_request_duration_nanos_bucket{method=\"GET\",route=\"/widgets/{id}\",status_class=\"2xx\",le=\"1000000\"}"));
		assertTrue(withoutZeroBucketsSnapshot.contains(
				"soklet_http_request_duration_nanos_bucket{method=\"GET\",route=\"/widgets/{id}\",status_class=\"2xx\",le=\"3000000000\"}"));

		MetricsCollector.SnapshotTextOptions countSumOnly = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.histogramFormat(MetricsCollector.SnapshotTextOptions.HistogramFormat.COUNT_SUM_ONLY)
				.build();
		String countSumSnapshot = collector.snapshotText(countSumOnly).orElseThrow();
		assertFalse(countSumSnapshot.contains("soklet_http_request_duration_nanos_bucket{"));
		assertTrue(countSumSnapshot.contains("soklet_http_request_duration_nanos_count{"));
		assertTrue(countSumSnapshot.contains("soklet_http_request_duration_nanos_sum{"));

		MetricsCollector.SnapshotTextOptions noHistograms = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.histogramFormat(MetricsCollector.SnapshotTextOptions.HistogramFormat.NONE)
				.build();
		String noHistogramSnapshot = collector.snapshotText(noHistograms).orElseThrow();
		assertFalse(noHistogramSnapshot.contains("soklet_http_request_duration_nanos"));

		Predicate<MetricsCollector.SnapshotTextOptions.MetricSample> httpOnlyFilter =
				sample -> sample.getName().equals("soklet_http_requests_active");
		MetricsCollector.SnapshotTextOptions filtered = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)
				.metricFilter(httpOnlyFilter)
				.build();
		String filteredSnapshot = collector.snapshotText(filtered).orElseThrow();
		assertTrue(filteredSnapshot.contains("soklet_http_requests_active"));
		assertFalse(filteredSnapshot.contains("soklet_sse_connections_active"));
		assertFalse(filteredSnapshot.contains("soklet_http_request_duration_nanos"));

		MetricsCollector.SnapshotTextOptions openMetricsOptions = MetricsCollector.SnapshotTextOptions
				.withMetricsFormat(MetricsCollector.MetricsFormat.OPEN_METRICS)
				.build();
		String openMetricsSnapshot = collector.snapshotText(openMetricsOptions).orElseThrow();
		assertTrue(openMetricsSnapshot.contains("# EOF"));
		assertFalse(withZeroBucketsSnapshot.contains("# EOF"));
	}

	@Test
	public void sseMetricsSnapshot() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();
		ResourceMethod resourceMethod = resourceMethodFor("/events/{id}", HttpMethod.GET, "events", true);
		Request request = Request.withPath(HttpMethod.GET, "/events/42").build();
		ServerSentEventConnection connection = new TestServerSentEventConnection(request, resourceMethod, Instant.now(), null);
		ServerSentEvent event = ServerSentEvent.withData("payload").build();
		ServerSentEventComment comment = ServerSentEventComment.withComment("ping");
		ServerSentEventComment heartbeat = ServerSentEventComment.heartbeatInstance();

		collector.didEstablishServerSentEventConnection(connection);
		collector.willWriteServerSentEvent(connection, event);
		collector.didWriteServerSentEvent(connection, event, Duration.ofMillis(2), Duration.ofNanos(500), 12, 3);
		collector.didWriteServerSentEventComment(connection, comment,
				Duration.ofMillis(1), Duration.ofNanos(250), 4, 1);
		collector.didWriteServerSentEventComment(connection, heartbeat,
				Duration.ofMillis(1), Duration.ofNanos(100), 3, 2);
		collector.didTerminateServerSentEventConnection(connection, Duration.ofSeconds(1),
				ServerSentEventConnection.TerminationReason.REMOTE_CLOSE, null);

		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();

		ResourcePathDeclaration eventsRoute = ResourcePathDeclaration.withPath("/events/{id}");
		ServerSentEventRouteKey routeKey = new ServerSentEventRouteKey(RouteType.MATCHED, eventsRoute);
		ServerSentEventCommentRouteKey commentKey = new ServerSentEventCommentRouteKey(RouteType.MATCHED, eventsRoute,
				ServerSentEventComment.CommentType.COMMENT);
		ServerSentEventCommentRouteKey heartbeatKey = new ServerSentEventCommentRouteKey(RouteType.MATCHED, eventsRoute,
				ServerSentEventComment.CommentType.HEARTBEAT);
		ServerSentEventRouteTerminationKey terminationKey = new ServerSentEventRouteTerminationKey(RouteType.MATCHED, eventsRoute,
				ServerSentEventConnection.TerminationReason.REMOTE_CLOSE);

		HistogramSnapshot timeToFirstEvent = snapshot.getSseTimeToFirstEvent().get(routeKey);
		assertNotNull(timeToFirstEvent);
		assertEquals(1L, timeToFirstEvent.getCount());

		HistogramSnapshot eventWriteDurations = snapshot.getSseEventWriteDurations().get(routeKey);
		assertNotNull(eventWriteDurations);
		assertEquals(1L, eventWriteDurations.getCount());

		HistogramSnapshot deliveryLag = snapshot.getSseEventDeliveryLag().get(routeKey);
		assertNotNull(deliveryLag);
		assertEquals(1L, deliveryLag.getCount());
		assertEquals(500L, deliveryLag.getSum());

		HistogramSnapshot eventSizes = snapshot.getSseEventSizes().get(routeKey);
		assertNotNull(eventSizes);
		assertEquals(1L, eventSizes.getCount());
		assertEquals(12L, eventSizes.getSum());

		HistogramSnapshot queueDepth = snapshot.getSseQueueDepth().get(routeKey);
		assertNotNull(queueDepth);
		assertEquals(1L, queueDepth.getCount());
		assertEquals(3L, queueDepth.getSum());

		HistogramSnapshot commentDeliveryLag = snapshot.getSseCommentDeliveryLag().get(commentKey);
		assertNotNull(commentDeliveryLag);
		assertEquals(1L, commentDeliveryLag.getCount());
		assertEquals(250L, commentDeliveryLag.getSum());

		HistogramSnapshot commentSizes = snapshot.getSseCommentSizes().get(commentKey);
		assertNotNull(commentSizes);
		assertEquals(1L, commentSizes.getCount());
		assertEquals(4L, commentSizes.getSum());

		HistogramSnapshot commentQueueDepth = snapshot.getSseCommentQueueDepth().get(commentKey);
		assertNotNull(commentQueueDepth);
		assertEquals(1L, commentQueueDepth.getCount());
		assertEquals(1L, commentQueueDepth.getSum());

		HistogramSnapshot heartbeatDeliveryLag = snapshot.getSseCommentDeliveryLag().get(heartbeatKey);
		assertNotNull(heartbeatDeliveryLag);
		assertEquals(1L, heartbeatDeliveryLag.getCount());
		assertEquals(100L, heartbeatDeliveryLag.getSum());

		HistogramSnapshot connectionDurations = snapshot.getSseConnectionDurations().get(terminationKey);
		assertNotNull(connectionDurations);
		assertEquals(1L, connectionDurations.getCount());

		assertEquals(0L, snapshot.getActiveSseConnections());
	}

	@Test
	public void httpMetricsSnapshot_overNetwork() throws Exception {
		int port = findFreePort();
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();

		SokletConfig config = SokletConfig.withServer(Server.withPort(port).requestTimeout(Duration.ofSeconds(3)).build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(HttpMetricsResource.class)))
				.metricsCollector(collector)
				.build();

		byte[] requestBody = "hello".getBytes(StandardCharsets.UTF_8);
		ResourcePathDeclaration httpMetricsRoute = ResourcePathDeclaration.withPath("/metrics/http/{id}");
		ServerRouteStatusKey statusKey = new ServerRouteStatusKey(HttpMethod.POST, RouteType.MATCHED, httpMetricsRoute, "2xx");
		ServerRouteKey routeKey = new ServerRouteKey(HttpMethod.POST, RouteType.MATCHED, httpMetricsRoute);

		try (Soklet app = Soklet.withConfig(config)) {
			app.start();

			URL url = new URL("http://127.0.0.1:" + port + "/metrics/http/123");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(2000);
			connection.setReadTimeout(2000);
			connection.setFixedLengthStreamingMode(requestBody.length);

			try (OutputStream out = connection.getOutputStream()) {
				out.write(requestBody);
				out.flush();
			}

			assertEquals(201, connection.getResponseCode());
			readAll(connection.getInputStream());
			connection.disconnect();
		}

		MetricsCollector.Snapshot snapshot = awaitSnapshot(collector,
				(metricsSnapshot) -> metricsSnapshot.getHttpRequestDurations().get(statusKey) != null,
				Duration.ofSeconds(2));

		HistogramSnapshot requestDurations = snapshot.getHttpRequestDurations().get(statusKey);
		assertNotNull(requestDurations);
		assertEquals(1L, requestDurations.getCount());

		HistogramSnapshot handlerDurations = snapshot.getHttpHandlerDurations().get(statusKey);
		assertNotNull(handlerDurations);
		assertEquals(1L, handlerDurations.getCount());

		HistogramSnapshot timeToFirstByte = snapshot.getHttpTimeToFirstByte().get(statusKey);
		assertNotNull(timeToFirstByte);
		assertEquals(1L, timeToFirstByte.getCount());

		HistogramSnapshot requestBodyBytes = snapshot.getHttpRequestBodyBytes().get(routeKey);
		assertNotNull(requestBodyBytes);
		assertEquals(1L, requestBodyBytes.getCount());
		assertEquals(requestBody.length, requestBodyBytes.getSum());

		HistogramSnapshot responseBodyBytes = snapshot.getHttpResponseBodyBytes().get(statusKey);
		assertNotNull(responseBodyBytes);
		assertEquals(1L, responseBodyBytes.getCount());
		assertEquals(HttpMetricsResource.RESPONSE_BODY.length, responseBodyBytes.getSum());
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	public void sseMetricsSnapshot_overNetwork() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();

		ServerSentEventServer serverSentEventServer = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.verifyConnectionOnceEstablished(false)
				.heartbeatInterval(Duration.ofMinutes(5))
				.requestTimeout(Duration.ofSeconds(3))
				.build();

		SokletConfig config = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(serverSentEventServer)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseMetricsResource.class)))
				.metricsCollector(collector)
				.build();

		ResourcePathDeclaration sseMetricsRoute = ResourcePathDeclaration.withPath("/metrics/sse/{id}");
		ServerSentEventRouteKey routeKey = new ServerSentEventRouteKey(RouteType.MATCHED, sseMetricsRoute);
		ServerSentEventCommentRouteKey commentKey = new ServerSentEventCommentRouteKey(RouteType.MATCHED, sseMetricsRoute,
				ServerSentEventComment.CommentType.COMMENT);

		try (Soklet app = Soklet.withConfig(config)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(3000);
				writeSseHttpGet(socket, "/metrics/sse/abc", ssePort);

				InputStream in = socket.getInputStream();
				String statusLine = readLineCRLF(in);
				assertTrue(statusLine.startsWith("HTTP/1.1 200"));
				readHeadersCRLF(in);

				ServerSentEventBroadcaster broadcaster = awaitBroadcasterWithClient(serverSentEventServer,
						"/metrics/sse/abc", Duration.ofSeconds(2));
				broadcaster.broadcastEvent(ServerSentEvent.withData("payload").build());
				broadcaster.broadcastComment(ServerSentEventComment.withComment("note"));

				boolean sawData = false;
				boolean sawComment = false;
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

				while (System.nanoTime() < deadline && (!sawData || !sawComment)) {
					String line = readLineLF(in);
					if (line == null) break;
					if (line.startsWith("data:")) sawData = true;
					if (line.startsWith(":")) sawComment = true;
				}

				assertTrue(sawData, "Did not receive SSE data");
				assertTrue(sawComment, "Did not receive SSE comment");
			}
		}

		MetricsCollector.Snapshot snapshot = awaitSnapshot(collector,
				(metricsSnapshot) -> metricsSnapshot.getSseEventWriteDurations().get(routeKey) != null
						&& metricsSnapshot.getSseCommentDeliveryLag().get(commentKey) != null,
				Duration.ofSeconds(3));

		HistogramSnapshot timeToFirstEvent = snapshot.getSseTimeToFirstEvent().get(routeKey);
		assertNotNull(timeToFirstEvent);
		assertEquals(1L, timeToFirstEvent.getCount());

		HistogramSnapshot eventWriteDurations = snapshot.getSseEventWriteDurations().get(routeKey);
		assertNotNull(eventWriteDurations);
		assertTrue(eventWriteDurations.getCount() >= 1L);

		HistogramSnapshot deliveryLag = snapshot.getSseEventDeliveryLag().get(routeKey);
		assertNotNull(deliveryLag);
		assertTrue(deliveryLag.getCount() >= 1L);

		HistogramSnapshot eventSizes = snapshot.getSseEventSizes().get(routeKey);
		assertNotNull(eventSizes);
		assertTrue(eventSizes.getCount() >= 1L);

		HistogramSnapshot queueDepth = snapshot.getSseQueueDepth().get(routeKey);
		assertNotNull(queueDepth);
		assertTrue(queueDepth.getCount() >= 1L);

		HistogramSnapshot commentDeliveryLag = snapshot.getSseCommentDeliveryLag().get(commentKey);
		assertNotNull(commentDeliveryLag);
		assertTrue(commentDeliveryLag.getCount() >= 1L);

		HistogramSnapshot commentSizes = snapshot.getSseCommentSizes().get(commentKey);
		assertNotNull(commentSizes);
		assertTrue(commentSizes.getCount() >= 1L);

		HistogramSnapshot commentQueueDepth = snapshot.getSseCommentQueueDepth().get(commentKey);
		assertNotNull(commentQueueDepth);
		assertTrue(commentQueueDepth.getCount() >= 1L);
	}

	private static ResourceMethod resourceMethodFor(@NonNull String path,
																									@NonNull HttpMethod method,
																									@NonNull String methodName,
																									boolean serverSentEventSource) {
		ResourcePathDeclaration resourcePathDeclaration = ResourcePathDeclaration.withPath(path);
		Method reflectedMethod = reflectedMethodFor(methodName);
		return ResourceMethod.withComponents(method, resourcePathDeclaration, reflectedMethod, serverSentEventSource);
	}

	private static Method reflectedMethodFor(@NonNull String methodName) {
		try {
			return TestResource.class.getDeclaredMethod(methodName);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final class TestResource {
		private void createWidget() {}

		private void events() {}
	}

	@ThreadSafe
	public static class HttpMetricsResource {
		static final byte[] RESPONSE_BODY = new byte[]{9, 8, 7};

		@POST("/metrics/http/{id}")
		public Response handleHttp(@NonNull @PathParameter String id) {
			return Response.withStatusCode(201)
					.body(RESPONSE_BODY)
					.build();
		}
	}

	@ThreadSafe
	public static class SseMetricsResource {
		@ServerSentEventSource("/metrics/sse/{id}")
		public HandshakeResult handleSse(@NonNull @PathParameter String id) {
			return HandshakeResult.accept();
		}
	}

	private static final class TestServerSentEventConnection implements ServerSentEventConnection {
		@NonNull
		private final Request request;
		@NonNull
		private final ResourceMethod resourceMethod;
		@NonNull
		private final Instant establishedAt;
		@Nullable
		private final Object clientContext;

		private TestServerSentEventConnection(@NonNull Request request,
																					@NonNull ResourceMethod resourceMethod,
																					@NonNull Instant establishedAt,
																					@Nullable Object clientContext) {
			this.request = request;
			this.resourceMethod = resourceMethod;
			this.establishedAt = establishedAt;
			this.clientContext = clientContext;
		}

		@NonNull
		@Override
		public Request getRequest() {
			return this.request;
		}

		@NonNull
		@Override
		public ResourceMethod getResourceMethod() {
			return this.resourceMethod;
		}

		@NonNull
		@Override
		public Instant getEstablishedAt() {
			return this.establishedAt;
		}

		@NonNull
		@Override
		public Optional<Object> getClientContext() {
			return Optional.ofNullable(this.clientContext);
		}
	}

	private static void writeSseHttpGet(@NonNull Socket socket,
																			@NonNull String path,
																			int port) throws IOException {
		requireNonNull(socket);
		requireNonNull(path);

		String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Accept: text/event-stream\r\n"
				+ "\r\n";

		OutputStream out = socket.getOutputStream();
		out.write(req.getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	private static String readLineCRLF(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int prev = -1;
		int cur;

		while ((cur = in.read()) != -1) {
			if (prev == '\r' && cur == '\n') break;
			if (cur != '\r') buf.write(cur);
			prev = cur;
		}

		return buf.toString(StandardCharsets.UTF_8);
	}

	private static String readLineLF(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int cur;

		while ((cur = in.read()) != -1) {
			if (cur == '\n') break;
			if (cur != '\r') buf.write(cur);
		}

		return cur == -1 && buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
	}

	private static void readHeadersCRLF(InputStream in) throws IOException {
		while (true) {
			String line = readLineCRLF(in);
			if (line.isEmpty()) return;
		}
	}

	private static ServerSentEventBroadcaster awaitBroadcasterWithClient(@NonNull ServerSentEventServer serverSentEventServer,
																																			 @NonNull String path,
																																			 @NonNull Duration timeout) throws InterruptedException {
		requireNonNull(serverSentEventServer);
		requireNonNull(path);
		requireNonNull(timeout);

		long deadline = System.nanoTime() + timeout.toNanos();

		while (true) {
			ServerSentEventBroadcaster broadcaster = serverSentEventServer.acquireBroadcaster(ResourcePath.withPath(path)).orElseThrow();
			if (broadcaster.getClientCount() > 0) return broadcaster;
			if (System.nanoTime() > deadline)
				throw new AssertionError("SSE connection not registered in time");
			Thread.sleep(10);
		}
	}

	private static MetricsCollector.Snapshot awaitSnapshot(@NonNull DefaultMetricsCollector collector,
																							 @NonNull Predicate<MetricsCollector.Snapshot> predicate,
																							 @NonNull Duration timeout) throws InterruptedException {
		requireNonNull(collector);
		requireNonNull(predicate);
		requireNonNull(timeout);

		long deadline = System.nanoTime() + timeout.toNanos();
		MetricsCollector.Snapshot snapshot = collector.snapshot().orElseThrow();

		while (System.nanoTime() < deadline) {
			if (predicate.test(snapshot))
				return snapshot;

			Thread.sleep(10);
			snapshot = collector.snapshot().orElseThrow();
		}

		throw new AssertionError("Timed out waiting for metrics snapshot");
	}
}
