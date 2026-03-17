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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

@ThreadSafe
public class McpServerLifecycleTests {
	@Test
	public void mcpServerBuilderRequiresHandlerResolver() {
		Assertions.assertThrows(IllegalStateException.class, () -> McpServer.withPort(0).build());
	}

	@Test
	public void mcpOnlyConfigDoesNotRequireHttpResourceMethodsAndStartsConfiguredMcpServer() throws Exception {
		FakeMcpServer fakeMcpServer = new FakeMcpServer();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(fakeMcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			Assertions.assertTrue(fakeMcpServer.getInitialized().get());
			soklet.start();
			Assertions.assertTrue(fakeMcpServer.getStarted().get());
		}

		Assertions.assertTrue(fakeMcpServer.getStopped().get());
	}

	@Test
	public void sokletMcpHandlerOpensGetStreamsForReadySessions() throws Exception {
		FakeMcpServer fakeMcpServer = new FakeMcpServer();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(fakeMcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			AtomicReference<RequestResult> requestResultHolder = new AtomicReference<>();
			Request initializeRequest = Request.withPath(HttpMethod.POST, "/mcp")
					.headers(Map.of("Content-Type", Set.of("application/json")))
					.body("""
							{
							  "jsonrpc":"2.0",
							  "id":"req-1",
							  "method":"initialize",
							  "params":{
							    "protocolVersion":"2025-11-25",
							    "capabilities":{},
							    "clientInfo":{"name":"test-client","version":"1.0.0"}
							  }
							}
							""".getBytes(StandardCharsets.UTF_8))
					.build();
			fakeMcpServer.getRequestHandler().orElseThrow().handleRequest(initializeRequest, requestResultHolder::set);
			RequestResult initializeResult = requestResultHolder.get();
			String sessionId = initializeResult.getMarshaledResponse().getHeaders().get("MCP-Session-Id").iterator().next();

			requestResultHolder.set(null);
			fakeMcpServer.getRequestHandler().orElseThrow().handleRequest(Request.withPath(HttpMethod.POST, "/mcp")
					.headers(Map.of(
							"Content-Type", Set.of("application/json"),
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25")
					))
					.body("""
							{
							  "jsonrpc":"2.0",
							  "method":"notifications/initialized",
							  "params":{}
							}
							""".getBytes(StandardCharsets.UTF_8))
					.build(), requestResultHolder::set);

			requestResultHolder.set(null);
			fakeMcpServer.getRequestHandler().orElseThrow()
					.handleRequest(Request.withPath(HttpMethod.GET, "/mcp")
							.headers(Map.of(
									"MCP-Session-Id", Set.of(sessionId),
									"MCP-Protocol-Version", Set.of("2025-11-25"),
									"Accept", Set.of("text/event-stream")
							))
							.build(), requestResultHolder::set);

			RequestResult requestResult = requestResultHolder.get();
			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());
			Assertions.assertEquals("text/event-stream; charset=UTF-8",
					requestResult.getMarshaledResponse().getHeaders().get("Content-Type").iterator().next());
		}
	}

	@Test
	public void defaultMcpServerProvidesDefaultStrategies() {
		McpHandlerResolver handlerResolver = McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class));
		McpServer mcpServer = McpServer.withPort(0)
				.handlerResolver(handlerResolver)
				.build();

		Assertions.assertSame(handlerResolver, mcpServer.getHandlerResolver());
		Assertions.assertNotNull(mcpServer.getRequestAdmissionPolicy());
		Assertions.assertNotNull(mcpServer.getRequestInterceptor());
		Assertions.assertNotNull(mcpServer.getResponseMarshaler());
		Assertions.assertNotNull(mcpServer.getCorsAuthorizer());
		Assertions.assertNotNull(mcpServer.getSessionStore());
		Assertions.assertNotNull(mcpServer.getIdGenerator());
	}

	@Test
	public void startedDefaultMcpServerServesCorsPreflightForWhitelistedOrigin() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.corsAuthorizer(McpCorsAuthorizer.fromWhitelistedOrigins(Set.of("https://chat.openai.com"), origin -> true))
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				String request = "OPTIONS /mcp HTTP/1.1\r\n"
						+ "Host: 127.0.0.1:" + mcpPort + "\r\n"
						+ "Origin: https://chat.openai.com\r\n"
						+ "Access-Control-Request-Method: POST\r\n"
						+ "Access-Control-Request-Headers: Authorization, MCP-Session-Id, MCP-Protocol-Version, Content-Type\r\n"
						+ "Connection: close\r\n"
						+ "\r\n";
				writeRawRequest(socket, request);

				String response = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(response);
				String normalizedResponse = response.toLowerCase(Locale.ROOT);
				Assertions.assertTrue(normalizedResponse.startsWith("http/1.1 204"));
				Assertions.assertTrue(normalizedResponse.contains("access-control-allow-origin: https://chat.openai.com"));
				Assertions.assertTrue(normalizedResponse.contains("access-control-allow-credentials: true"));
				Assertions.assertTrue(normalizedResponse.contains("access-control-allow-methods:"));
				Assertions.assertTrue(normalizedResponse.contains("post"));
				Assertions.assertTrue(normalizedResponse.contains("access-control-allow-headers:"));
				Assertions.assertTrue(normalizedResponse.contains("authorization"));
				Assertions.assertTrue(normalizedResponse.contains("mcp-session-id"));
			}
		}
	}

	@Test
	public void startedDefaultMcpServerKeepsGetStreamsOpenAndSendsHeartbeats() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.heartbeatInterval(Duration.ofMillis(100))
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			String sessionId = initializedSessionId(mcpPort);

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				socket.setSoTimeout(2500);
				writeMcpGet(socket, mcpPort, sessionId);

				String handshake = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(handshake);
				Assertions.assertTrue(handshake.startsWith("HTTP/1.1 200"));
				Assertions.assertTrue(handshake.toLowerCase(Locale.ROOT).contains("content-type: text/event-stream"));
				Assertions.assertFalse(handshake.toLowerCase(Locale.ROOT).contains("content-length:"));

				String heartbeat = readUntil(socket.getInputStream(), "\n\n", 64);
				Assertions.assertEquals(":\n\n", heartbeat);
			}
		}
	}

	@Test
	public void startedDefaultMcpServerClosesLiveGetStreamsAfterDelete() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.heartbeatInterval(Duration.ofMillis(100))
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			String sessionId = initializedSessionId(mcpPort);

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				socket.setSoTimeout(2500);
				writeMcpGet(socket, mcpPort, sessionId);
				Assertions.assertNotNull(readUntil(socket.getInputStream(), "\r\n\r\n", 8192));

				HttpURLConnection deleteConnection = (HttpURLConnection) new URL("http://127.0.0.1:" + mcpPort + "/mcp").openConnection();
				deleteConnection.setRequestMethod("DELETE");
				deleteConnection.setConnectTimeout(2000);
				deleteConnection.setReadTimeout(2000);
				deleteConnection.setRequestProperty("MCP-Session-Id", sessionId);
				deleteConnection.setRequestProperty("MCP-Protocol-Version", "2025-11-25");
				Assertions.assertEquals(204, deleteConnection.getResponseCode());

				Assertions.assertTrue(waitForEof(socket, 3000));
			}
		}
	}

	@Test
	public void startedDefaultMcpServerRoutesInternalSessionMessagesToMostRecentLiveGetStream() throws Exception {
		int mcpPort = findFreePort();
		DefaultMcpServer defaultMcpServer = (DefaultMcpServer) McpServer.withPort(mcpPort)
				.host("127.0.0.1")
				.heartbeatInterval(Duration.ofSeconds(5))
				.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
				.build();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(defaultMcpServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			String sessionId = initializedSessionId(mcpPort);
			Assertions.assertFalse(defaultMcpServer.publishSessionMessage(sessionId, internalSessionNotification("no-stream")));

			try (Socket firstSocket = connectWithRetry("127.0.0.1", mcpPort, 2000);
					 Socket secondSocket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				firstSocket.setSoTimeout(300);
				secondSocket.setSoTimeout(2000);
				writeMcpGet(firstSocket, mcpPort, sessionId);
				Assertions.assertNotNull(readUntil(firstSocket.getInputStream(), "\r\n\r\n", 8192));

				writeMcpGet(secondSocket, mcpPort, sessionId);
				Assertions.assertNotNull(readUntil(secondSocket.getInputStream(), "\r\n\r\n", 8192));

				Assertions.assertTrue(defaultMcpServer.publishSessionMessage(sessionId, internalSessionNotification("latest")));

				String messageFrame = readUntil(secondSocket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(messageFrame);
				Assertions.assertEquals("latest", sessionNotificationValue(messageFrame));
				Assertions.assertThrows(java.net.SocketTimeoutException.class, () -> firstSocket.getInputStream().read());
			}
		}
	}

	@Test
	public void startedDefaultMcpServerRejectsTransferEncodingRequests() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				socket.setSoTimeout(2000);
				writeRawRequest(socket, """
						POST /mcp HTTP/1.1\r
						Host: 127.0.0.1:%d\r
						Content-Type: application/json\r
						Transfer-Encoding: chunked\r
						\r
						""".formatted(mcpPort));

				String response = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(response);
				Assertions.assertTrue(response.startsWith("HTTP/1.1 400"));
			}
		}
	}

	@Test
	public void startedDefaultMcpServerRejectsOversizedRequestBodiesWith413() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.maximumRequestSizeInBytes(8)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				socket.setSoTimeout(2000);
				writeRawRequest(socket, """
						POST /mcp HTTP/1.1\r
						Host: 127.0.0.1:%d\r
						Content-Type: application/json\r
						Content-Length: 1024\r
						\r
						""".formatted(mcpPort));

				String response = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(response);
				Assertions.assertTrue(response.startsWith("HTTP/1.1 413"));
			}
		}
	}

	@Test
	public void stoppingDefaultMcpServerClosesLiveGetStreams() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.heartbeatInterval(Duration.ofSeconds(5))
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();

			String sessionId = initializedSessionId(mcpPort);

			try (Socket socket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				socket.setSoTimeout(2500);
				writeMcpGet(socket, mcpPort, sessionId);
				Assertions.assertNotNull(readUntil(socket.getInputStream(), "\r\n\r\n", 8192));

				soklet.stop();

				Assertions.assertTrue(waitForEof(socket, 3000));
			}
		}
	}

	@Test
	public void startedDefaultMcpServerEnforcesConcurrentConnectionLimitForGetStreams() throws Exception {
		int mcpPort = findFreePort();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(mcpPort)
						.host("127.0.0.1")
						.concurrentConnectionLimit(1)
						.heartbeatInterval(Duration.ofSeconds(5))
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			soklet.start();
			String sessionId = initializedSessionId(mcpPort);

			try (Socket firstSocket = connectWithRetry("127.0.0.1", mcpPort, 2000);
					 Socket secondSocket = connectWithRetry("127.0.0.1", mcpPort, 2000)) {
				firstSocket.setSoTimeout(2000);
				secondSocket.setSoTimeout(2000);
				writeMcpGet(firstSocket, mcpPort, sessionId);
				String firstHandshake = readUntil(firstSocket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(firstHandshake);
				Assertions.assertTrue(firstHandshake.startsWith("HTTP/1.1 200"));

				writeMcpGet(secondSocket, mcpPort, sessionId);
				String secondHandshake = readUntil(secondSocket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(secondHandshake);
				Assertions.assertTrue(secondHandshake.startsWith("HTTP/1.1 503"));
			}
		}
	}

	@Test
	public void defaultMcpServerWriteTimeoutClosesSlowSocketWrites() throws Exception {
		DefaultMcpServer defaultMcpServer = (DefaultMcpServer) McpServer.withPort(0)
				.writeTimeout(Duration.ofMillis(50))
				.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
				.build();
		ScheduledExecutorService timeoutExecutor = new ScheduledThreadPoolExecutor(1);
		Field timeoutExecutorField = DefaultMcpServer.class.getDeclaredField("requestHandlerTimeoutExecutorService");
		timeoutExecutorField.setAccessible(true);
		timeoutExecutorField.set(defaultMcpServer, timeoutExecutor);

		Method writeMethod = DefaultMcpServer.class.getDeclaredMethod("writeMarshaledResponse", Socket.class, MarshaledResponse.class, Boolean.class);
		writeMethod.setAccessible(true);
		BlockingSocket blockingSocket = new BlockingSocket();

		try {
			InvocationTargetException invocationTargetException = Assertions.assertThrows(InvocationTargetException.class,
					() -> writeMethod.invoke(defaultMcpServer,
							blockingSocket,
							MarshaledResponse.withStatusCode(200)
									.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8")))
									.body("hello".getBytes(StandardCharsets.UTF_8))
									.build(),
							Boolean.TRUE));

			Assertions.assertInstanceOf(SocketTimeoutException.class, invocationTargetException.getCause());
			Assertions.assertTrue(blockingSocket.closed.get());
		} finally {
			timeoutExecutor.shutdownNow();
			timeoutExecutor.awaitTermination(1, TimeUnit.SECONDS);
		}
	}

	@Test
	public void simulatorCanPerformMcpRequestsThroughConfiguredMcpServer() {
		SokletConfig sokletConfig = SokletConfig.withMcpServer(McpServer.withPort(0)
						.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		Soklet.runSimulator(sokletConfig, simulator -> {
			McpRequestResult.ResponseCompleted initializeResult = (McpRequestResult.ResponseCompleted) simulator.performMcpRequest(
					Request.withPath(HttpMethod.POST, "/mcp")
							.headers(Map.of("Content-Type", Set.of("application/json")))
							.body("""
									{
									  "jsonrpc":"2.0",
									  "id":"req-1",
									  "method":"initialize",
									  "params":{
									    "protocolVersion":"2025-11-25",
									    "capabilities":{},
									    "clientInfo":{"name":"test-client","version":"1.0.0"}
									  }
									}
									""".getBytes(StandardCharsets.UTF_8))
							.build());
			String sessionId = initializeResult.getRequestResult().getMarshaledResponse().getHeaders().get("MCP-Session-Id").iterator().next();

			simulator.performMcpRequest(Request.withPath(HttpMethod.POST, "/mcp")
					.headers(Map.of(
							"Content-Type", Set.of("application/json"),
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25")
					))
					.body("""
							{
							  "jsonrpc":"2.0",
							  "method":"notifications/initialized",
							  "params":{}
							}
							""".getBytes(StandardCharsets.UTF_8))
					.build());

			McpRequestResult requestResult = simulator.performMcpRequest(Request.withPath(HttpMethod.GET, "/mcp")
					.headers(Map.of(
							"MCP-Session-Id", Set.of(sessionId),
							"MCP-Protocol-Version", Set.of("2025-11-25"),
							"Accept", Set.of("text/event-stream")
					))
					.build());
			Assertions.assertInstanceOf(McpRequestResult.StreamOpened.class, requestResult);
		});
	}

	@Test
	public void simulatorTreatsEventStreamResponsesAsOpenMcpStreams() {
		Soklet.MockHttpServer mockServer = new Soklet.MockHttpServer();
		Soklet.MockMcpServer mockMcpServer = new Soklet.MockMcpServer(new FakeMcpServer());
		Soklet.DefaultSimulator simulator = new Soklet.DefaultSimulator(mockServer, null, mockMcpServer);

		mockMcpServer.initialize(SokletConfig.withMcpServer(new FakeMcpServer())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build(), (request, requestResultConsumer) -> requestResultConsumer.accept(
				RequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/event-stream; charset=UTF-8")))
						.build())));

		McpRequestResult requestResult = simulator.performMcpRequest(Request.fromPath(HttpMethod.GET, "/mcp"));
		Assertions.assertInstanceOf(McpRequestResult.StreamOpened.class, requestResult);
	}

	@Test
	public void simulatorRoutesMcpStreamConsumerErrorsThroughDedicatedHandler() {
		Soklet.MockHttpServer mockServer = new Soklet.MockHttpServer();
		Soklet.MockMcpServer mockMcpServer = new Soklet.MockMcpServer(new FakeMcpServer());
		Soklet.DefaultSimulator simulator = new Soklet.DefaultSimulator(mockServer, null, mockMcpServer);
		AtomicReference<Throwable> streamErrorHolder = new AtomicReference<>();

		mockMcpServer.initialize(SokletConfig.withMcpServer(new FakeMcpServer())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build(), (request, requestResultConsumer) -> requestResultConsumer.accept(
				RequestResult.fromMarshaledResponse(MarshaledResponse.withStatusCode(200)
						.headers(Map.of("Content-Type", Set.of("text/event-stream; charset=UTF-8")))
						.build())));

		simulator.onMcpStreamError(streamErrorHolder::set);

		McpRequestResult.StreamOpened streamOpened = (McpRequestResult.StreamOpened) simulator.performMcpRequest(Request.fromPath(HttpMethod.GET, "/mcp"));
		streamOpened.registerMessageConsumer(message -> {
			throw new IllegalStateException("boom");
		});
		streamOpened.emitMessage(new McpObject(Map.of("messages", new McpArray(List.of(new McpString("one"))))));

		Assertions.assertNotNull(streamErrorHolder.get());
		Assertions.assertEquals("boom", streamErrorHolder.get().getMessage());
	}

	@Test
	public void mockHttpServerInitializeStoresSokletConfig() {
		Soklet.MockHttpServer mockHttpServer = new Soklet.MockHttpServer();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(new FakeMcpServer())
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		mockHttpServer.initialize(sokletConfig, (request, requestResultConsumer) -> {
			throw new UnsupportedOperationException("unused");
		});

		Assertions.assertEquals(sokletConfig, mockHttpServer.getSokletConfig().orElseThrow());
	}

	@Test
	public void sokletRollsBackAlreadyStartedServersWhenLaterServerFailsToStart() {
		FakeHttpServer fakeHttpServer = new FakeHttpServer();
		FakeSseServer fakeSseServer = new FakeSseServer();
		FailingStartMcpServer failingMcpServer = new FailingStartMcpServer();
		SokletConfig sokletConfig = SokletConfig.withMcpServer(failingMcpServer)
				.httpServer(fakeHttpServer)
				.sseServer(fakeSseServer)
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet soklet = Soklet.fromConfig(sokletConfig)) {
			Assertions.assertThrows(IllegalStateException.class, soklet::start);
		}

		Assertions.assertFalse(fakeHttpServer.isStarted());
		Assertions.assertTrue(fakeHttpServer.getStopped().get());
		Assertions.assertFalse(fakeSseServer.isStarted());
		Assertions.assertTrue(fakeSseServer.getStopped().get());
		Assertions.assertFalse(failingMcpServer.isStarted());
	}

	@Test
	public void mcpAcceptLoopContinuesAfterUnexpectedRuntimeException() throws Exception {
		DefaultMcpServer server = (DefaultMcpServer) McpServer.withPort(0)
				.handlerResolver(McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class)))
				.build();
		Field stopPoisonPillField = DefaultMcpServer.class.getDeclaredField("stopPoisonPill");
		Field serverSocketField = DefaultMcpServer.class.getDeclaredField("serverSocket");
		Method acceptLoopMethod = DefaultMcpServer.class.getDeclaredMethod("acceptLoop");
		stopPoisonPillField.setAccessible(true);
		serverSocketField.setAccessible(true);
		acceptLoopMethod.setAccessible(true);

		AtomicBoolean stopPoisonPill = (AtomicBoolean) stopPoisonPillField.get(server);
		serverSocketField.set(server, new RuntimeThenStopServerSocket(stopPoisonPill));

		Assertions.assertDoesNotThrow(() -> {
			try {
				acceptLoopMethod.invoke(server);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException runtimeException)
					throw runtimeException;
				if (cause instanceof Error error)
					throw error;
				throw new RuntimeException(cause);
			}
		});
	}

	@McpServerEndpoint(path = "/mcp", name = "example", version = "1.0.0")
	public static class ExampleMcpEndpoint implements McpEndpoint {}

	private static class FakeMcpServer implements McpServer {
		private final AtomicBoolean initialized;
		private final AtomicBoolean started;
		private final AtomicBoolean stopped;
		private final McpSessionStore sessionStore;
		private final IdGenerator<String> idGenerator;
		private SokletConfig sokletConfig;
		private RequestHandler requestHandler;

		private FakeMcpServer() {
			this.initialized = new AtomicBoolean();
			this.started = new AtomicBoolean();
			this.stopped = new AtomicBoolean();
			this.sessionStore = McpSessionStore.fromInMemory();
			this.idGenerator = IdGenerator.defaultInstance();
		}

		@Override
		public void start() {
			this.started.set(true);
		}

		@Override
		public void stop() {
			this.stopped.set(true);
			this.started.set(false);
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
			this.initialized.set(true);
		}

		@NonNull
		@Override
		public McpHandlerResolver getHandlerResolver() {
			return McpHandlerResolver.fromClasses(Set.of(ExampleMcpEndpoint.class));
		}

		@NonNull
		@Override
		public McpRequestAdmissionPolicy getRequestAdmissionPolicy() {
			return McpRequestAdmissionPolicy.defaultInstance();
		}

		@NonNull
		@Override
		public McpRequestInterceptor getRequestInterceptor() {
			return new McpRequestInterceptor() {};
		}

		@NonNull
		@Override
		public McpResponseMarshaler getResponseMarshaler() {
			return McpResponseMarshaler.defaultInstance();
		}

		@NonNull
		@Override
		public McpCorsAuthorizer getCorsAuthorizer() {
			return McpCorsAuthorizer.nonBrowserClientsOnlyInstance();
		}

		@NonNull
		@Override
		public McpSessionStore getSessionStore() {
			return this.sessionStore;
		}

		@NonNull
		@Override
		public IdGenerator<String> getIdGenerator() {
			return this.idGenerator;
		}

		@NonNull
		protected AtomicBoolean getInitialized() {
			return this.initialized;
		}

		@NonNull
		protected AtomicBoolean getStarted() {
			return this.started;
		}

		@NonNull
		protected AtomicBoolean getStopped() {
			return this.stopped;
		}

		@NonNull
		protected Optional<SokletConfig> getSokletConfig() {
			return Optional.ofNullable(this.sokletConfig);
		}

		@NonNull
		protected Optional<RequestHandler> getRequestHandler() {
			return Optional.ofNullable(this.requestHandler);
		}
	}

	private static final class FakeHttpServer implements HttpServer {
		private final AtomicBoolean started;
		private final AtomicBoolean stopped;
		private SokletConfig sokletConfig;
		private RequestHandler requestHandler;

		private FakeHttpServer() {
			this.started = new AtomicBoolean(false);
			this.stopped = new AtomicBoolean(false);
		}

		@Override
		public void start() {
			this.started.set(true);
		}

		@Override
		public void stop() {
			this.stopped.set(true);
			this.started.set(false);
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return this.started.get();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
		}

		@NonNull
		protected AtomicBoolean getStopped() {
			return this.stopped;
		}
	}

	private static final class FakeSseServer implements SseServer {
		private final AtomicBoolean started;
		private final AtomicBoolean stopped;
		private SokletConfig sokletConfig;
		private RequestHandler requestHandler;

		private FakeSseServer() {
			this.started = new AtomicBoolean(false);
			this.stopped = new AtomicBoolean(false);
		}

		@Override
		public void start() {
			this.started.set(true);
		}

		@Override
		public void stop() {
			this.stopped.set(true);
			this.started.set(false);
		}

		@NonNull
		@Override
		public Boolean isStarted() {
			return this.started.get();
		}

		@NonNull
		@Override
		public Optional<? extends SseBroadcaster> acquireBroadcaster(ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
													 @NonNull RequestHandler requestHandler) {
			this.sokletConfig = sokletConfig;
			this.requestHandler = requestHandler;
		}

		@NonNull
		protected AtomicBoolean getStopped() {
			return this.stopped;
		}
	}

	private static final class FailingStartMcpServer extends FakeMcpServer {
		@Override
		public void start() {
			throw new IllegalStateException("boom");
		}
	}

	private static String initializedSessionId(int mcpPort) throws IOException {
		HttpURLConnection initializeConnection = (HttpURLConnection) new URL("http://127.0.0.1:" + mcpPort + "/mcp").openConnection();
		initializeConnection.setRequestMethod("POST");
		initializeConnection.setDoOutput(true);
		initializeConnection.setConnectTimeout(2000);
		initializeConnection.setReadTimeout(2000);
		initializeConnection.setRequestProperty("Content-Type", "application/json");
		initializeConnection.setRequestProperty("Accept", "application/json, text/event-stream");

		try (OutputStream outputStream = initializeConnection.getOutputStream()) {
			outputStream.write("""
					{
					  "jsonrpc":"2.0",
					  "id":"req-1",
					  "method":"initialize",
					  "params":{
					    "protocolVersion":"2025-11-25",
					    "capabilities":{},
					    "clientInfo":{"name":"test-client","version":"1.0.0"}
					  }
					}
					""".getBytes(StandardCharsets.UTF_8));
		}

		Assertions.assertEquals(200, initializeConnection.getResponseCode());
		readAll(initializeConnection.getInputStream());
		String sessionId = initializeConnection.getHeaderField("MCP-Session-Id");
		Assertions.assertNotNull(sessionId);

		HttpURLConnection initializedConnection = (HttpURLConnection) new URL("http://127.0.0.1:" + mcpPort + "/mcp").openConnection();
		initializedConnection.setRequestMethod("POST");
		initializedConnection.setDoOutput(true);
		initializedConnection.setConnectTimeout(2000);
		initializedConnection.setReadTimeout(2000);
		initializedConnection.setRequestProperty("Content-Type", "application/json");
		initializedConnection.setRequestProperty("Accept", "application/json, text/event-stream");
		initializedConnection.setRequestProperty("MCP-Session-Id", sessionId);
		initializedConnection.setRequestProperty("MCP-Protocol-Version", "2025-11-25");

		try (OutputStream outputStream = initializedConnection.getOutputStream()) {
			outputStream.write("""
					{
					  "jsonrpc":"2.0",
					  "method":"notifications/initialized",
					  "params":{}
					}
					""".getBytes(StandardCharsets.UTF_8));
		}

		Assertions.assertEquals(202, initializedConnection.getResponseCode());
		return sessionId;
	}

	private static void writeMcpGet(Socket socket, int port, String sessionId) throws IOException {
		String request = "GET /mcp HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Accept: text/event-stream\r\n"
				+ "MCP-Session-Id: " + sessionId + "\r\n"
				+ "MCP-Protocol-Version: 2025-11-25\r\n"
				+ "Connection: keep-alive\r\n"
				+ "\r\n";
		socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static void writeRawRequest(Socket socket, String request) throws IOException {
		socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static String readUntil(InputStream inputStream, String terminator, int maxBytes) throws IOException {
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

	private static boolean waitForEof(Socket socket, int timeoutMs) throws IOException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		socket.setSoTimeout(Math.max(100, timeoutMs));
		InputStream inputStream = socket.getInputStream();
		byte[] buffer = new byte[1];

		while (System.currentTimeMillis() < deadline) {
			try {
				if (inputStream.read(buffer) == -1)
					return true;
			} catch (java.net.SocketTimeoutException ignored) {
				// Keep trying until the deadline is reached.
			}
		}

		return false;
	}

	private static McpObject internalSessionNotification(String value) {
		return new McpObject(Map.of(
				"jsonrpc", new McpString("2.0"),
				"method", new McpString("notifications/test"),
				"params", new McpObject(Map.of("value", new McpString(value)))
		));
	}

	private static String sessionNotificationValue(String frame) {
		Assertions.assertTrue(frame.startsWith("data: "));
		McpObject payload = (McpObject) McpJsonCodec.parse(frame.substring("data: ".length(), frame.length() - 2).getBytes(StandardCharsets.UTF_8));
		McpObject params = (McpObject) payload.get("params").orElseThrow();
		return ((McpString) params.get("value").orElseThrow()).value();
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* no-op */ }
	}

	private static final class RuntimeThenStopServerSocket extends java.net.ServerSocket {
		private final AtomicBoolean stopPoisonPill;
		private int acceptCount;

		private RuntimeThenStopServerSocket(@NonNull AtomicBoolean stopPoisonPill) throws IOException {
			super();
			this.stopPoisonPill = stopPoisonPill;
		}

		@Override
		public Socket accept() throws IOException {
			if (this.acceptCount++ == 0)
				return new RuntimeKeepAliveSocket();

			this.stopPoisonPill.set(true);
			throw new SocketException("stop");
		}
	}

	private static final class RuntimeKeepAliveSocket extends Socket {
		@Override
		public void setKeepAlive(boolean on) {
			throw new IllegalStateException("boom");
		}
	}

	private static final class BlockingSocket extends Socket {
		private final AtomicBoolean closed;
		private final OutputStream outputStream;

		private BlockingSocket() {
			this.closed = new AtomicBoolean(false);
			this.outputStream = new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					write(new byte[]{(byte) b}, 0, 1);
				}

				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

					while (!closed.get() && System.nanoTime() < deadline)
						LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));

					if (closed.get())
						throw new SocketException("Socket closed");

					throw new IOException("Timed test write did not unblock");
				}
			};
		}

		@Override
		public OutputStream getOutputStream() {
			return this.outputStream;
		}

		@Override
		public synchronized void close() {
			this.closed.set(true);
		}
	}
}
