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

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.mcp.protocol.McpServerRuntimeBridge;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import javax.annotation.concurrent.ThreadSafe;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/**
 * CI-safe resource-leak tripwires. Long soak runs belong behind explicit/manual profiles; these tests
 * keep a small always-on signal for resource cleanup regressions.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ResourceLeakTests {
	@Test
	@Timeout(value = 240, unit = TimeUnit.SECONDS)
	public void httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown() throws Exception {
		int port = findFreePort();
		HttpServer httpServer = HttpServer.withPort(port)
				.concurrency(2)
				.requestHeaderTimeout(Duration.ofSeconds(3))
				.build();
		SokletConfig config = SokletConfig.withHttpServer(httpServer)
				.lifecyclePolicy(lifecyclePolicy(Duration.ofSeconds(2)))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ChurnResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();
		ResourceSnapshot runningBaseline;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			assertOkResponse(port);
			runningBaseline = ResourceSnapshot.captureAfterGc();

			for (int i = 0; i < 75; i++)
				assertOkResponse(port);

			ResourceSnapshot.assertReturnsNear(
					"HTTP connection churn while running",
					runningBaseline,
					Duration.ofSeconds(5),
					new ResourceSnapshot.ResourceTolerance(2L, 16L * 1024L * 1024L, 8));
		}

		DefaultHttpServer defaultHttpServer = (DefaultHttpServer) httpServer;
		Assertions.assertTrue(defaultHttpServer.getEventLoop().isEmpty(), "Event loop should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerExecutorService().isEmpty(),
				"Request handler executor should be cleared after stop");
		Assertions.assertTrue(defaultHttpServer.getRequestHandlerTimeoutScheduler().isEmpty(),
				"Timeout scheduler should be cleared after stop");
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	public void sseConnectionReturnsResourcesNearBaselineAfterShutdown() throws Exception {
		ResourceSnapshot stoppedBaseline = ResourceSnapshot.captureAfterGc();
		int port = findFreePort();
		SseServer sseServer = SseServer.withPort(port)
				.verifyConnectionOnceEstablished(false)
				.build();
		DefaultSseServer defaultSseServer = (DefaultSseServer) sseServer;
		SokletConfig config = SokletConfig.withSseServer(sseServer)
				.lifecyclePolicy(lifecyclePolicy(Duration.ofSeconds(2)))
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(SseResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
				socket.setSoTimeout(2_000);
				socket.getOutputStream().write(("""
						GET /events HTTP/1.1\r
						Host: 127.0.0.1:%s\r
						Accept: text/event-stream\r
						\r
						""").formatted(port).getBytes(StandardCharsets.ISO_8859_1));
				socket.getOutputStream().flush();

				BufferedReader reader = new BufferedReader(new InputStreamReader(
						socket.getInputStream(), StandardCharsets.ISO_8859_1));
				String status = reader.readLine();
				Assertions.assertNotNull(status);
				Assertions.assertTrue(status.startsWith("HTTP/1.1 200"),
						"Unexpected response: " + status);
				String header;
				do {
					header = reader.readLine();
					Assertions.assertNotNull(header,
							"SSE response ended before its headers were complete");
				} while (!header.isEmpty());

				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (defaultSseServer.getActiveConnectionCount() == 0
						&& System.nanoTime() < deadline)
					Thread.onSpinWait();
				Assertions.assertTrue(defaultSseServer.getActiveConnectionCount() > 0,
						"SSE connection did not register");
			}
		}

		Assertions.assertTrue(defaultSseServer.getGlobalConnections().isEmpty(),
				"Global SSE connections should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getBroadcastersByResourcePath().isEmpty(),
				"SSE broadcaster cache should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getIdleBroadcastersByResourcePath().isEmpty(),
				"Idle SSE broadcaster cache should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getResourcePathDeclarationsByResourcePathCache()
				.isEmpty(), "SSE resource path cache should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getRequestHandlerExecutorService().isEmpty(),
				"SSE request handler executor should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getRequestHandlerTimeoutScheduler().isEmpty(),
				"SSE timeout scheduler should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getRequestReaderExecutorService().isEmpty(),
				"SSE request reader executor should be cleared after stop");
		Assertions.assertTrue(defaultSseServer.getEventLoopThread().isEmpty(),
				"SSE event loop thread should be cleared after stop");
		ResourceSnapshot.assertReturnsNear("SSE connection after shutdown", stoppedBaseline,
				Duration.ofSeconds(5),
				new ResourceSnapshot.ResourceTolerance(4L, 24L * 1024L * 1024L, 12));
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void mcpListenerAndRequestReturnResourcesAfterCompleteShutdown()
			throws Exception {
		ResourceSnapshot stoppedBaseline = ResourceSnapshot.captureAfterGc();
		McpEndpoint endpoint = McpEndpoint.withPath("/mcp-resource-leak", McpImplementation.withNameAndVersion(
						"resource-leak", "4.0.0").build())
				.build();
		McpServer server = McpServer.withPort(0, McpEndpointRegistry.fromEndpoints(List.of(endpoint)), McpAdmissionController.acceptAllInstance())
				.host("127.0.0.1")
				.toolRateLimiter(context -> McpRateLimitDecision.allowed())
				.corsAuthorizer(CorsAuthorizer.rejectAllInstance())
				.allowedHosts(Set.of("127.0.0.1"))
				.build();
		SokletConfig config = SokletConfig.withMcpServer(server)
				.lifecyclePolicy(lifecyclePolicy(Duration.ofSeconds(2)))
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();
		InetSocketAddress address;

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			address = server.getDiagnostics().getBoundAddress().orElseThrow();
			assertMcpDiscovery(address.getPort());
		}

		Assertions.assertEquals(McpServerStatus.TERMINATED,
				server.getDiagnostics().getStatus());
		Assertions.assertEquals(address,
				server.getDiagnostics().getBoundAddress().orElseThrow());
		McpServerRuntimeBridge.LifecycleEvidence evidence = bridge(server)
				.getLifecycleEvidence();
		Assertions.assertFalse(evidence.eventLoop());
		Assertions.assertFalse(evidence.connection());
		Assertions.assertFalse(evidence.executorTask());
		Assertions.assertFalse(evidence.stream());
		Assertions.assertFalse(evidence.callback());
		Assertions.assertFalse(evidence.subscriptionRegistration());
		ResourceSnapshot.assertReturnsNear("MCP request after complete shutdown",
				stoppedBaseline, Duration.ofSeconds(5),
				new ResourceSnapshot.ResourceTolerance(
						4L, 24L * 1024L * 1024L, 12));
	}

	@NonNull
	private static LifecyclePolicy lifecyclePolicy(@NonNull Duration grace) {
		return LifecyclePolicy.builder()
				.gracefulShutdownTimeout(grace)
				.forcedShutdownTimeout(Duration.ofSeconds(2))
				.build();
	}

	private static void assertOkResponse(int port) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(2_000);
			socket.getOutputStream().write(("""
					GET /health HTTP/1.1\r
					Host: 127.0.0.1:%s\r
					Connection: close\r
					\r
					""").formatted(port).getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();

			String response = new String(readAll(socket.getInputStream()), StandardCharsets.ISO_8859_1);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200"), "Unexpected response: " + firstLine(response));
			Assertions.assertTrue(response.endsWith("ok"), "Unexpected response body: " + response);
		}
	}

	private static void assertMcpDiscovery(int port) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":\"leak\","
				+ "\"method\":\"server/discover\",\"params\":{\"_meta\":{"
				+ "\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
				+ "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(2_000);
			String head = "POST /mcp-resource-leak HTTP/1.1\r\n"
					+ "Host: 127.0.0.1:" + port + "\r\n"
					+ "Content-Type: application/json; charset=UTF-8\r\n"
					+ "Accept: application/json, text/event-stream\r\n"
					+ "MCP-Protocol-Version: 2026-07-28\r\n"
					+ "Mcp-Method: server/discover\r\n"
					+ "Content-Length: " + bodyBytes.length + "\r\n"
					+ "Connection: close\r\n\r\n";
			socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().write(bodyBytes);
			socket.getOutputStream().flush();
			String response = new String(readAll(socket.getInputStream()),
					StandardCharsets.UTF_8);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200"), response);
			Assertions.assertTrue(response.contains("\"id\":\"leak\""), response);
		}
	}

	@NonNull
	private static McpServerRuntimeBridge bridge(@NonNull McpServer server) {
		try {
			Field field = DefaultMcpServer.class.getDeclaredField("runtimeBridge");
			field.setAccessible(true);
			return (McpServerRuntimeBridge) field.get(server);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	@NonNull
	private static String firstLine(@NonNull String response) {
		int endOfLine = response.indexOf("\r\n");
		return endOfLine < 0 ? response : response.substring(0, endOfLine);
	}

	@ThreadSafe
	public static class ChurnResource {
		@GET("/health")
		public String health() {
			return "ok";
		}
	}

	@ThreadSafe
	public static class SseResource {
		@SseEventSource("/events")
		@NonNull
		public SseHandshakeResult events() {
			return SseHandshakeResult.accept();
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
			// Intentionally quiet for leak-tripwire tests.
		}
	}
}
