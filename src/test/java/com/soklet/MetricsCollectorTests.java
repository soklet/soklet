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
import com.soklet.MetricsCollector.HttpMethodRouteKey;
import com.soklet.MetricsCollector.HttpMethodRouteStatusKey;
import com.soklet.MetricsCollector.RouteKind;
import com.soklet.MetricsCollector.ServerSentEventRouteKey;
import com.soklet.MetricsCollector.ServerSentEventRouteTerminationKey;
import com.soklet.MetricsCollector.Snapshot;
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

		collector.didStartRequestHandling(request, resourceMethod);
		collector.willWriteResponse(request, resourceMethod, response);
		collector.didFinishRequestHandling(request, resourceMethod, response, Duration.ofMillis(5), List.of());

		MetricsSnapshot snapshot = collector.snapshot().orElseThrow();

		ResourcePathDeclaration widgetRoute = ResourcePathDeclaration.withPath("/widgets/{id}");
		HttpMethodRouteStatusKey statusKey = new HttpMethodRouteStatusKey(HttpMethod.POST, RouteKind.MATCHED, widgetRoute, "2xx");
		HttpMethodRouteKey routeKey = new HttpMethodRouteKey(HttpMethod.POST, RouteKind.MATCHED, widgetRoute);

		Snapshot requestDurations = snapshot.getHttpRequestDurations().get(statusKey);
		assertNotNull(requestDurations);
		assertEquals(1L, requestDurations.getCount());

		Snapshot handlerDurations = snapshot.getHttpHandlerDurations().get(statusKey);
		assertNotNull(handlerDurations);
		assertEquals(1L, handlerDurations.getCount());

		Snapshot timeToFirstByte = snapshot.getHttpTimeToFirstByte().get(statusKey);
		assertNotNull(timeToFirstByte);
		assertEquals(1L, timeToFirstByte.getCount());

		Snapshot requestBodyBytes = snapshot.getHttpRequestBodyBytes().get(routeKey);
		assertNotNull(requestBodyBytes);
		assertEquals(1L, requestBodyBytes.getCount());
		assertEquals(3L, requestBodyBytes.getSum());

		Snapshot responseBodyBytes = snapshot.getHttpResponseBodyBytes().get(statusKey);
		assertNotNull(responseBodyBytes);
		assertEquals(1L, responseBodyBytes.getCount());
		assertEquals(2L, responseBodyBytes.getSum());

		assertEquals(0L, snapshot.getActiveRequests());

		collector.reset();
		MetricsSnapshot resetSnapshot = collector.snapshot().orElseThrow();
		Snapshot resetRequestDurations = resetSnapshot.getHttpRequestDurations().get(statusKey);
		assertTrue(resetRequestDurations == null || resetRequestDurations.getCount() == 0L);
		assertEquals(0L, resetSnapshot.getActiveRequests());
	}

	@Test
	public void sseMetricsSnapshot() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.withDefaults();
		ResourceMethod resourceMethod = resourceMethodFor("/events/{id}", HttpMethod.GET, "events", true);
		Request request = Request.withPath(HttpMethod.GET, "/events/42").build();
		ServerSentEventConnection connection = new TestServerSentEventConnection(request, resourceMethod, Instant.now(), null);
		ServerSentEvent event = ServerSentEvent.withData("payload").build();

		collector.didEstablishServerSentEventConnection(connection);
		collector.willWriteServerSentEvent(connection, event);
		collector.didWriteServerSentEvent(connection, event, Duration.ofMillis(2), Duration.ofNanos(500), 12, 3);
		collector.didWriteServerSentEventComment(connection, "ping", Duration.ofMillis(1), Duration.ofNanos(250), 4, 1);
		collector.didTerminateServerSentEventConnection(connection, Duration.ofSeconds(1),
				ServerSentEventConnection.TerminationReason.REMOTE_CLOSE, null);

		MetricsSnapshot snapshot = collector.snapshot().orElseThrow();

		ResourcePathDeclaration eventsRoute = ResourcePathDeclaration.withPath("/events/{id}");
		ServerSentEventRouteKey routeKey = new ServerSentEventRouteKey(RouteKind.MATCHED, eventsRoute);
		ServerSentEventRouteTerminationKey terminationKey = new ServerSentEventRouteTerminationKey(RouteKind.MATCHED, eventsRoute,
				ServerSentEventConnection.TerminationReason.REMOTE_CLOSE);

		Snapshot timeToFirstEvent = snapshot.getSseTimeToFirstEvent().get(routeKey);
		assertNotNull(timeToFirstEvent);
		assertEquals(1L, timeToFirstEvent.getCount());

		Snapshot eventWriteDurations = snapshot.getSseEventWriteDurations().get(routeKey);
		assertNotNull(eventWriteDurations);
		assertEquals(1L, eventWriteDurations.getCount());

		Snapshot deliveryLag = snapshot.getSseEventDeliveryLag().get(routeKey);
		assertNotNull(deliveryLag);
		assertEquals(2L, deliveryLag.getCount());
		assertEquals(750L, deliveryLag.getSum());

		Snapshot eventSizes = snapshot.getSseEventSizes().get(routeKey);
		assertNotNull(eventSizes);
		assertEquals(2L, eventSizes.getCount());
		assertEquals(16L, eventSizes.getSum());

		Snapshot queueDepth = snapshot.getSseQueueDepth().get(routeKey);
		assertNotNull(queueDepth);
		assertEquals(2L, queueDepth.getCount());
		assertEquals(4L, queueDepth.getSum());

		Snapshot connectionDurations = snapshot.getSseConnectionDurations().get(terminationKey);
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
		HttpMethodRouteStatusKey statusKey = new HttpMethodRouteStatusKey(HttpMethod.POST, RouteKind.MATCHED, httpMetricsRoute, "2xx");
		HttpMethodRouteKey routeKey = new HttpMethodRouteKey(HttpMethod.POST, RouteKind.MATCHED, httpMetricsRoute);

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

		MetricsSnapshot snapshot = awaitSnapshot(collector,
				(metricsSnapshot) -> metricsSnapshot.getHttpRequestDurations().get(statusKey) != null,
				Duration.ofSeconds(2));

		Snapshot requestDurations = snapshot.getHttpRequestDurations().get(statusKey);
		assertNotNull(requestDurations);
		assertEquals(1L, requestDurations.getCount());

		Snapshot handlerDurations = snapshot.getHttpHandlerDurations().get(statusKey);
		assertNotNull(handlerDurations);
		assertEquals(1L, handlerDurations.getCount());

		Snapshot timeToFirstByte = snapshot.getHttpTimeToFirstByte().get(statusKey);
		assertNotNull(timeToFirstByte);
		assertEquals(1L, timeToFirstByte.getCount());

		Snapshot requestBodyBytes = snapshot.getHttpRequestBodyBytes().get(routeKey);
		assertNotNull(requestBodyBytes);
		assertEquals(1L, requestBodyBytes.getCount());
		assertEquals(requestBody.length, requestBodyBytes.getSum());

		Snapshot responseBodyBytes = snapshot.getHttpResponseBodyBytes().get(statusKey);
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
		ServerSentEventRouteKey routeKey = new ServerSentEventRouteKey(RouteKind.MATCHED, sseMetricsRoute);

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
				broadcaster.broadcastComment("note");

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

		MetricsSnapshot snapshot = awaitSnapshot(collector,
				(metricsSnapshot) -> metricsSnapshot.getSseEventWriteDurations().get(routeKey) != null,
				Duration.ofSeconds(3));

		Snapshot timeToFirstEvent = snapshot.getSseTimeToFirstEvent().get(routeKey);
		assertNotNull(timeToFirstEvent);
		assertEquals(1L, timeToFirstEvent.getCount());

		Snapshot eventWriteDurations = snapshot.getSseEventWriteDurations().get(routeKey);
		assertNotNull(eventWriteDurations);
		assertTrue(eventWriteDurations.getCount() >= 1L);

		Snapshot deliveryLag = snapshot.getSseEventDeliveryLag().get(routeKey);
		assertNotNull(deliveryLag);
		assertTrue(deliveryLag.getCount() >= 2L);

		Snapshot eventSizes = snapshot.getSseEventSizes().get(routeKey);
		assertNotNull(eventSizes);
		assertTrue(eventSizes.getCount() >= 2L);

		Snapshot queueDepth = snapshot.getSseQueueDepth().get(routeKey);
		assertNotNull(queueDepth);
		assertTrue(queueDepth.getCount() >= 2L);
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

	private static MetricsSnapshot awaitSnapshot(@NonNull DefaultMetricsCollector collector,
																							 @NonNull Predicate<MetricsSnapshot> predicate,
																							 @NonNull Duration timeout) throws InterruptedException {
		requireNonNull(collector);
		requireNonNull(predicate);
		requireNonNull(timeout);

		long deadline = System.nanoTime() + timeout.toNanos();
		MetricsSnapshot snapshot = collector.snapshot().orElseThrow();

		while (System.nanoTime() < deadline) {
			if (predicate.test(snapshot))
				return snapshot;

			Thread.sleep(10);
			snapshot = collector.snapshot().orElseThrow();
		}

		throw new AssertionError("Timed out waiting for metrics snapshot");
	}
}
