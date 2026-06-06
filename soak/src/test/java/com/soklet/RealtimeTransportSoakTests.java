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

import com.soklet.annotation.McpServerEndpoint;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.SseEventSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Longer-running SSE and MCP resource-leak soak tests.
 * <p>
 * Default mode is a short smoke run. Set {@code SOKLET_SOAK=1} for the higher-volume nightly run.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class RealtimeTransportSoakTests {
	private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
	private static final RealtimeSoakProfile PROFILE = RealtimeSoakProfile.fromEnvironment();

	@Test
	public void concurrentSseChurnReturnsResourcesAndActiveStreamsToBaseline() throws Exception {
		assumeVirtualThreadRuntime();

		long startedAt = System.nanoTime();
		MetricsCollector metricsCollector = MetricsCollector.defaultInstance();
		int port = findFreePort();
		SseServer sseServer = SseServer.withPort(port)
				.host("127.0.0.1")
				.requestHeaderTimeout(Duration.ofSeconds(2))
				.writeTimeout(Duration.ofSeconds(2))
				.heartbeatInterval(Duration.ofMillis(100))
				.concurrentConnectionLimit(PROFILE.sseConcurrentConnectionLimit())
				.shutdownTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig config = SokletConfig.withSseServer(sseServer)
				.metricsCollector(metricsCollector)
				.lifecycleObserver(new QuietLifecycle())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SoakSseResource.class)))
				.build();
		SoakResourceSnapshot baseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			openSseStreamTakeEventAndClose(port, "warmup");
			assertGaugeReturnsToZero("active SSE streams", () -> activeSseStreams(metricsCollector), Duration.ofSeconds(3));
			baseline = SoakResourceSnapshot.captureAfterGc();

			RunResult result = runConcurrent(PROFILE.concurrentClients(), PROFILE.sseStreamsPerClient(), (clientIndex, iteration) -> {
				openSseStreamTakeEventAndClose(port, clientIndex + "-" + iteration);
				sleep(PROFILE.sseInterStreamPause());
			});

			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.sseStreamsPerClient(), result.completed());
			Assertions.assertTrue(result.failures().isEmpty(), () -> "Unexpected SSE churn failures: " + result.failures());
			assertGaugeReturnsToZero("active SSE streams", () -> activeSseStreams(metricsCollector), PROFILE.settleTimeout());
			SoakResourceSnapshot finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"concurrent SSE churn",
					baseline,
					PROFILE.settleTimeout(),
					PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"concurrent SSE churn",
					"clients=%d, streamsPerClient=%d, concurrentConnectionLimit=%d, interStreamPause=%s, clientSocketTimeout=%s"
							.formatted(PROFILE.concurrentClients(), PROFILE.sseStreamsPerClient(),
									PROFILE.sseConcurrentConnectionLimit(), PROFILE.sseInterStreamPause(), PROFILE.clientSocketTimeout()),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Completed operations", Integer.toString(result.completed()),
							"Active SSE streams", activeSseStreams(metricsCollector).toString(),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		}

		assertSseServerStopped(sseServer);
	}

	@Test
	public void mcpAbandonedSessionChurnReturnsResourcesAndActiveSessionsToBaseline() throws Exception {
		assumeVirtualThreadRuntime();

		long startedAt = System.nanoTime();
		MetricsCollector metricsCollector = MetricsCollector.defaultInstance();
		int port = findFreePort();
		McpServer mcpServer = McpServer.withPort(port)
				.host("127.0.0.1")
				.requestHeaderTimeout(Duration.ofSeconds(2))
				.requestBodyTimeout(Duration.ofSeconds(2))
				.requestHandlerTimeout(Duration.ofSeconds(3))
				.concurrentConnectionLimit(PROFILE.mcpConcurrentConnectionLimit())
				.sessionStore(McpSessionStore.fromInMemory(PROFILE.mcpSessionIdleTimeout()))
				.handlerResolver(McpHandlerResolver.fromClasses(Set.of(SoakMcpEndpoint.class)))
				.shutdownTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig config = SokletConfig.withMcpServer(mcpServer)
				.metricsCollector(metricsCollector)
				.lifecycleObserver(new QuietLifecycle())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.build();
		Queue<String> sessionIds = new ConcurrentLinkedQueue<>();
		Queue<String> triggerSessionIds = new ConcurrentLinkedQueue<>();
		SoakResourceSnapshot baseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			String warmupSessionId = initializeMcpSession(port, "warmup");
			deleteMcpSession(port, warmupSessionId);
			assertGaugeReturnsToZero("active MCP sessions", () -> activeMcpSessions(metricsCollector), PROFILE.settleTimeout());
			baseline = SoakResourceSnapshot.captureAfterGc();

			RunResult createResult = runConcurrent(PROFILE.concurrentClients(), PROFILE.mcpSessionsPerClient(), (clientIndex, iteration) -> {
				String requestId = "session-" + clientIndex + "-" + iteration;
				sessionIds.add(initializeMcpSession(port, requestId));
			});

			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.mcpSessionsPerClient(), createResult.completed());
			Assertions.assertTrue(createResult.failures().isEmpty(), () -> "Unexpected MCP session-create failures: " + createResult.failures());
			assertGaugeEquals("active abandoned MCP sessions",
					PROFILE.concurrentClients() * PROFILE.mcpSessionsPerClient(),
					() -> activeMcpSessions(metricsCollector),
					PROFILE.settleTimeout());

			sleep(PROFILE.mcpSessionIdleTimeout().plusMillis(250));

			RunResult sweepTriggerResult = runConcurrent(PROFILE.concurrentClients(), PROFILE.mcpSessionsPerClient(), (clientIndex, iteration) -> {
				String requestId = "trigger-" + clientIndex + "-" + iteration;
				triggerSessionIds.add(initializeMcpSession(port, requestId));
			});

			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.mcpSessionsPerClient(), sweepTriggerResult.completed());
			Assertions.assertTrue(sweepTriggerResult.failures().isEmpty(), () -> "Unexpected MCP sweep-trigger failures: " + sweepTriggerResult.failures());
			DefaultMcpSessionStore sessionStore = (DefaultMcpSessionStore) mcpServer.getSessionStore();
			Assertions.assertEquals(0, sessionIds.stream()
					.filter(sessionStore::containsSessionId)
					.count(), "Abandoned sessions should have been swept by later session creates");
			assertGaugeEquals("active MCP sessions after abandoned-session sweep",
					PROFILE.concurrentClients() * PROFILE.mcpSessionsPerClient(),
					() -> activeMcpSessions(metricsCollector),
					PROFILE.settleTimeout());

			RunResult deleteResult = runConcurrent(PROFILE.concurrentClients(), PROFILE.mcpSessionsPerClient(), (clientIndex, iteration) -> {
				String sessionId = triggerSessionIds.poll();
				Assertions.assertNotNull(sessionId, "Expected a trigger session ID to delete");
				deleteMcpSession(port, sessionId);
			});

			Assertions.assertEquals(PROFILE.concurrentClients() * PROFILE.mcpSessionsPerClient(), deleteResult.completed());
			Assertions.assertTrue(deleteResult.failures().isEmpty(), () -> "Unexpected MCP trigger-session delete failures: " + deleteResult.failures());
			assertGaugeReturnsToZero("active MCP sessions", () -> activeMcpSessions(metricsCollector), PROFILE.settleTimeout());
			Assertions.assertEquals(Long.valueOf(0L), activeMcpSseStreams(metricsCollector));
			SoakResourceSnapshot finalSnapshot = SoakResourceSnapshot.assertReturnsNear(
					"MCP abandoned session churn",
					baseline,
					PROFILE.settleTimeout(),
					PROFILE.resourceTolerance());
			SoakReport.recordPassedScenario(
					"MCP abandoned session churn",
					"clients=%d, abandonedSessionsPerClient=%d, triggerSessionsPerClient=%d, concurrentConnectionLimit=%d, idleTimeout=%s"
							.formatted(PROFILE.concurrentClients(), PROFILE.mcpSessionsPerClient(), PROFILE.mcpSessionsPerClient(),
									PROFILE.mcpConcurrentConnectionLimit(), PROFILE.mcpSessionIdleTimeout()),
					Duration.ofNanos(System.nanoTime() - startedAt),
					baseline,
					finalSnapshot,
					PROFILE.resourceTolerance(),
					SoakReport.observations(
							"Created abandoned sessions", Integer.toString(createResult.completed()),
							"Created sweep-trigger sessions", Integer.toString(sweepTriggerResult.completed()),
							"Deleted trigger sessions", Integer.toString(deleteResult.completed()),
							"Active MCP sessions", activeMcpSessions(metricsCollector).toString(),
							"Active MCP SSE streams", activeMcpSseStreams(metricsCollector).toString(),
							"Settle timeout", PROFILE.settleTimeout().toString()));
		}
	}

	private static void openSseStreamTakeEventAndClose(int port,
																										 @NonNull String streamId) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(Math.toIntExact(PROFILE.clientSocketTimeout().toMillis()));
			writeAscii(socket, """
					GET /events/%s HTTP/1.1\r
					Host: 127.0.0.1:%s\r
					Accept: text/event-stream\r
					Connection: keep-alive\r
					\r
					""".formatted(streamId, port));

			String headers = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
			Assertions.assertNotNull(headers, "Missing SSE handshake response");
			Assertions.assertTrue(headers.startsWith("HTTP/1.1 200"), "Unexpected SSE response: " + firstLine(headers));
			Assertions.assertTrue(headers.toLowerCase(Locale.ROOT).contains("content-type: text/event-stream"),
					"Unexpected SSE headers: " + headers);

			String event = readUntil(socket.getInputStream(), "\n\n", 4096);
			Assertions.assertNotNull(event, "Missing initial SSE event");
			Assertions.assertTrue(event.contains("event: ready"), "Unexpected SSE event: " + event);
			Assertions.assertTrue(event.contains("data: " + streamId), "Unexpected SSE event: " + event);

			socket.setSoLinger(true, 0);
		}
	}

	private static void assumeVirtualThreadRuntime() {
		Assumptions.assumeTrue(Runtime.version().feature() >= 21,
				"SSE/MCP realtime soak tests require a virtual-thread-capable runtime");
	}

	@NonNull
	private static String initializeMcpSession(int port,
																						 @NonNull String requestId) throws Exception {
		McpPostResponse response = sendMcpPost(port, initializeJson(requestId), Map.of());
		Assertions.assertEquals(200, response.statusCode(), "Unexpected MCP initialize response");

		String sessionId = response.mcpSessionId();
		Assertions.assertNotNull(sessionId, "Missing MCP-Session-Id response header");
		return sessionId;
	}

	private static void deleteMcpSession(int port,
																			 @NonNull String sessionId) throws Exception {
		McpPostResponse response = sendMcpDelete(port, Map.of(
				"MCP-Session-Id", sessionId,
				"MCP-Protocol-Version", MCP_PROTOCOL_VERSION
		));
		Assertions.assertEquals(204, response.statusCode(), "Expected MCP session delete to return 204");
	}

	@NonNull
	private static McpPostResponse sendMcpPost(int port,
																						 @NonNull String body,
																						 @NonNull Map<String, String> extraHeaders) throws Exception {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setConnectTimeout(2_000);
		connection.setReadTimeout(2_000);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json, text/event-stream");
		connection.setRequestProperty("Connection", "close");

		for (Map.Entry<String, String> entry : extraHeaders.entrySet())
			connection.setRequestProperty(entry.getKey(), entry.getValue());

		try {
			try (var outputStream = connection.getOutputStream()) {
				outputStream.write(bodyBytes);
			}

			int statusCode = connection.getResponseCode();
			InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

			if (responseStream != null)
				responseStream.readAllBytes();

			return new McpPostResponse(statusCode, connection.getHeaderField("MCP-Session-Id"));
		} finally {
			connection.disconnect();
		}
	}

	@NonNull
	private static McpPostResponse sendMcpDelete(int port,
																						 @NonNull Map<String, String> extraHeaders) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
		connection.setRequestMethod("DELETE");
		connection.setConnectTimeout(2_000);
		connection.setReadTimeout(2_000);
		connection.setRequestProperty("Accept", "application/json, text/event-stream");
		connection.setRequestProperty("Connection", "close");

		for (Map.Entry<String, String> entry : extraHeaders.entrySet())
			connection.setRequestProperty(entry.getKey(), entry.getValue());

		try {
			int statusCode = connection.getResponseCode();
			InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

			if (responseStream != null)
				responseStream.readAllBytes();

			return new McpPostResponse(statusCode, connection.getHeaderField("MCP-Session-Id"));
		} finally {
			connection.disconnect();
		}
	}

	@NonNull
	private static String initializeJson(@NonNull String requestId) {
		return """
				{
				  "jsonrpc":"2.0",
				  "id":"%s",
				  "method":"initialize",
				  "params":{
				    "protocolVersion":"%s",
				    "capabilities":{},
				    "clientInfo":{"name":"soak-client","version":"1.0.0"}
				  }
				}
				""".formatted(requestId, MCP_PROTOCOL_VERSION);
	}

	private static RunResult runConcurrent(int clients,
																				 int iterationsPerClient,
																				 @NonNull SoakOperation operation) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(clients);
		CountDownLatch ready = new CountDownLatch(clients);
		CountDownLatch start = new CountDownLatch(1);
		Queue<String> failures = new ConcurrentLinkedQueue<>();
		AtomicInteger completed = new AtomicInteger();

		try {
			for (int client = 0; client < clients; client++) {
				int clientIndex = client;

				executorService.submit(() -> {
					ready.countDown();

					try {
						if (!start.await(10, TimeUnit.SECONDS))
							throw new AssertionError("Timed out waiting for start signal");

						for (int iteration = 0; iteration < iterationsPerClient; iteration++) {
							operation.run(clientIndex, iteration);
							completed.incrementAndGet();
						}
					} catch (Throwable throwable) {
						failures.add(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
					}
				});
			}

			Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS), "Clients did not become ready");
			start.countDown();
			executorService.shutdown();
			Assertions.assertTrue(executorService.awaitTermination(PROFILE.runTimeout().toSeconds(), TimeUnit.SECONDS),
					"Timed out waiting for soak clients to finish");
		} finally {
			executorService.shutdownNow();
		}

		return new RunResult(completed.get(), failures);
	}

	private static void assertGaugeReturnsToZero(@NonNull String name,
																						 @NonNull Supplier<Long> gauge,
																						 @NonNull Duration timeout) throws InterruptedException {
		assertGaugeEquals(name, 0L, gauge, timeout);
	}

	private static void assertGaugeEquals(@NonNull String name,
																				long expected,
																				@NonNull Supplier<Long> gauge,
																				@NonNull Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		Long value = gauge.get();

		while (System.nanoTime() < deadline) {
			if (value.longValue() == expected)
				return;

			Thread.sleep(100L);
			value = gauge.get();
		}

		Assertions.fail("%s did not reach %d within %s; last=%s".formatted(name, expected, timeout, value));
	}

	@NonNull
	private static Long activeSseStreams(@NonNull MetricsCollector metricsCollector) {
		return metricsCollector.snapshot()
				.map(MetricsCollector.Snapshot::getActiveSseStreams)
				.orElse(0L);
	}

	@NonNull
	private static Long activeMcpSessions(@NonNull MetricsCollector metricsCollector) {
		return metricsCollector.snapshot()
				.map(MetricsCollector.Snapshot::getActiveMcpSessions)
				.orElse(0L);
	}

	@NonNull
	private static Long activeMcpSseStreams(@NonNull MetricsCollector metricsCollector) {
		return metricsCollector.snapshot()
				.map(MetricsCollector.Snapshot::getActiveMcpSseStreams)
				.orElse(0L);
	}

	private static void assertSseServerStopped(@NonNull SseServer sseServer) {
		DefaultSseServer defaultSseServer = (DefaultSseServer) sseServer;

		Assertions.assertTrue(defaultSseServer.getGlobalConnections().isEmpty(), "SSE connections should be cleared after stop");
		Assertions.assertEquals(Integer.valueOf(0), defaultSseServer.getActiveConnectionCount(),
				"SSE active connection count should be zero after stop");
		Assertions.assertTrue(defaultSseServer.getConnectionExecutorService().isEmpty(),
				"SSE connection executor should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getRequestHandlerExecutorService().isEmpty(),
				"SSE request handler executor should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getRequestHandlerTimeoutScheduler().isEmpty(),
				"SSE timeout scheduler should be cleared after stop");
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			serverSocket.setReuseAddress(true);
			return serverSocket.getLocalPort();
		}
	}

	@NonNull
	private static Socket connectWithRetry(@NonNull String host,
																				 int port,
																				 int timeoutMillis) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		IOException last = null;

		while (System.currentTimeMillis() < deadline) {
			try {
				Socket socket = new Socket();
				socket.connect(new InetSocketAddress(host, port), Math.max(250, timeoutMillis / 2));
				return socket;
			} catch (IOException e) {
				last = e;
				Thread.sleep(30L);
			}
		}

		throw last != null ? last : new IOException("Unable to connect to " + host + ":" + port);
	}

	private static void writeAscii(@NonNull Socket socket,
																 @NonNull String value) throws IOException {
		socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1));
		socket.getOutputStream().flush();
	}

	@Nullable
	private static String readUntil(@NonNull InputStream inputStream,
																	@NonNull String terminator,
																	int maxBytes) throws IOException {
		byte[] terminatorBytes = terminator.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		int match = 0;
		int value;

		while (outputStream.size() < maxBytes && (value = inputStream.read()) != -1) {
			outputStream.write(value);

			if (value == terminatorBytes[match]) {
				match++;

				if (match == terminatorBytes.length)
					return outputStream.toString(StandardCharsets.UTF_8);
			} else {
				match = value == terminatorBytes[0] ? 1 : 0;
			}
		}

		return null;
	}

	@NonNull
	private static String firstLine(@NonNull String response) {
		int endOfLine = response.indexOf("\r\n");
		return endOfLine < 0 ? response : response.substring(0, endOfLine);
	}

	private static void sleep(@NonNull Duration duration) throws InterruptedException {
		Thread.sleep(duration.toMillis());
	}

	@ThreadSafe
	public static class SoakSseResource {
		@SseEventSource("/events/{streamId}")
		public SseHandshakeResult stream(@NonNull Request request,
																			@NonNull @PathParameter String streamId) {
			return SseHandshakeResult.Accepted.builder()
					.clientInitializer(unicaster -> unicaster.unicastEvent(SseEvent.withEvent("ready")
							.data(streamId)
							.build()))
					.build();
		}
	}

	@McpServerEndpoint(path = "/mcp", name = "soak", version = "1.0.0")
	public static class SoakMcpEndpoint implements McpEndpoint {}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Keep soak-test output focused on assertion failures.
		}
	}

	private record RunResult(int completed, @NonNull Queue<String> failures) {}

	private record McpPostResponse(int statusCode, @Nullable String mcpSessionId) {}

	@FunctionalInterface
	private interface SoakOperation {
		void run(int clientIndex, int iteration) throws Exception;
	}

	private record RealtimeSoakProfile(int concurrentClients,
																		 int sseStreamsPerClient,
																		 int mcpSessionsPerClient,
																		 int sseConcurrentConnectionLimit,
																		 int mcpConcurrentConnectionLimit,
																		 @NonNull Duration clientSocketTimeout,
																		 @NonNull Duration mcpSessionIdleTimeout,
																		 @NonNull Duration sseInterStreamPause,
																		 @NonNull Duration runTimeout,
																		 @NonNull Duration settleTimeout,
																		 SoakResourceSnapshot.ResourceTolerance resourceTolerance) {
		@NonNull
		private static RealtimeSoakProfile fromEnvironment() {
			boolean soak = "1".equals(System.getenv("SOKLET_SOAK"));

			if (soak) {
				return new RealtimeSoakProfile(
						16,
						100,
						4,
						256,
						128,
						Duration.ofSeconds(10),
						Duration.ofSeconds(2),
						Duration.ofMillis(20),
						Duration.ofSeconds(90),
						Duration.ofSeconds(15),
						new SoakResourceSnapshot.ResourceTolerance(12L, 96L * 1024L * 1024L, 64));
			}

			return new RealtimeSoakProfile(
					4,
					10,
					3,
					64,
					32,
					Duration.ofSeconds(5),
					Duration.ofSeconds(2),
					Duration.ofMillis(20),
					Duration.ofSeconds(30),
					Duration.ofSeconds(6),
					new SoakResourceSnapshot.ResourceTolerance(6L, 48L * 1024L * 1024L, 24));
		}
	}
}
