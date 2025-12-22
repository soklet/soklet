/*
 * Copyright 2022-2025 Revetware LLC.
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

import com.soklet.ServerSentEventRequestResult.HandshakeAccepted;
import com.soklet.ServerSentEventRequestResult.HandshakeRejected;
import com.soklet.ServerSentEventRequestResult.RequestFailed;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.ServerSentEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ServerSentEventTests {
	@ThreadSafe
	public static class ServerSentEventSimulatorResource {
		@ServerSentEventSource("/examples/{exampleId}")
		public HandshakeResult example(@Nonnull Request request,
																	 @Nonnull @PathParameter String exampleId) {
			return HandshakeResult.acceptWithDefaults()
					.headers(Map.of(
							"X-Soklet-Example", Set.of(exampleId)
					))
					.cookies(Set.of(
							ResponseCookie.with("cookie-test", exampleId).build()
					))
					.clientInitializer(unicaster -> {
						unicaster.unicastComment("Unicast comment");
						unicaster.unicastEvent(ServerSentEvent.withEvent("initial")
								.data("unicast")
								.build()
						);
					})
					.build();
		}
	}

	@Test
	public void serverSentEventServerSimulator() {
		SokletConfig configuration = SokletConfig.forSimulatorTesting()
				.corsAuthorizer(CorsAuthorizer.withWhitelistedOrigins(Set.of("https://www.revetkn.com")))
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(ServerSentEventSimulatorResource.class)))
				.build();

		List<ServerSentEvent> events = new ArrayList<>();
		List<String> comments = new ArrayList<>();

		Soklet.runSimulator(configuration, (simulator -> {
			// Perform initial handshake with /examples/abc and verify 200 response
			Request request = Request.withPath(HttpMethod.GET, "/examples/abc")
					.headers(Map.of("Origin", Set.of("https://www.revetkn.com")))
					.build();

			ServerSentEventRequestResult requestResult = simulator.performServerSentEventRequest(request);

			if (requestResult instanceof HandshakeAccepted handshakeAccepted) {
				MarshaledResponse marshaledResponse = handshakeAccepted.getRequestResult().getMarshaledResponse();

				Assertions.assertEquals(200, marshaledResponse.getStatusCode(), "Unexpected HTTP status code for accepted handshake");

				// Verify the headers and cookies came through
				Set<String> headerValues = marshaledResponse.getHeaders().entrySet().stream()
						.filter(entry -> entry.getKey().equals("X-Soklet-Example"))
						.map(entry -> entry.getValue())
						.findAny()
						.orElse(Set.of());

				Assertions.assertEquals(Set.of("abc"), headerValues, "Unexpected X-Soklet-Example header value");

				// Verify that CORS headers were applied
				Assertions.assertEquals(Set.of("https://www.revetkn.com"), marshaledResponse.getHeaders().get("Access-Control-Allow-Origin"), "Unexpected Access-Control-Allow-Origin header value");

				ResponseCookie testCookie = marshaledResponse.getCookies().stream()
						.filter(responseCookie -> responseCookie.getName().equals("cookie-test"))
						.findAny()
						.orElse(null);

				Assertions.assertEquals("abc", testCookie.getValue().orElse(null), "Unexpected cookie-test value");

				handshakeAccepted.registerEventConsumer((event) -> {
					events.add(event);
				});

				handshakeAccepted.registerCommentConsumer((comment) -> {
					comments.add(comment);
				});

				// Create a Server-Sent Event...
				ServerSentEvent serverSentEvent = ServerSentEvent.withEvent("example")
						.data("data")
						.id("abc")
						.retry(Duration.ofSeconds(10))
						.build();

				// ...and broadcast it to all /examples/abc listeners
				ServerSentEventBroadcaster broadcaster = configuration.getServerSentEventServer().get().acquireBroadcaster(ResourcePath.withPath("/examples/abc")).get();
				broadcaster.broadcastEvent(serverSentEvent);

				// Now try a comment
				broadcaster.broadcastComment("just a test");
			} else if (requestResult instanceof HandshakeRejected handshakeRejected) {
				Assertions.fail("SSE handshake rejected: " + handshakeRejected);
			} else if (requestResult instanceof RequestFailed requestFailed) {
				Assertions.fail("SSE request failed: " + requestFailed);
			} else {
				// Should never happen
				throw new IllegalStateException(format("Unexpected SSE result: %s", requestResult.getClass()));
			}
		}));

		Assertions.assertEquals(2, events.size(), "Wrong number of events");
		Assertions.assertEquals(2, comments.size(), "Wrong number of comments");
		Assertions.assertEquals("unicast", events.get(0).getData().get(), "Unexpected unicast event data");
		Assertions.assertEquals("data", events.get(1).getData().get(), "Unexpected broadcast event data");
		Assertions.assertEquals("Unicast comment", comments.get(0), "Unexpected unicast comment");
		Assertions.assertEquals("just a test", comments.get(1), "Unexpected broadcast comment");
	}

	@ThreadSafe
	protected static class ServerSentEventResource {
		@Nonnull
		private final ServerSentEventServer serverSentEventServer;
		@Nonnull
		private final Runnable sokletStopper;

		public ServerSentEventResource(@Nonnull ServerSentEventServer serverSentEventServer,
																	 @Nonnull Runnable sokletStopper) {
			requireNonNull(serverSentEventServer);
			requireNonNull(sokletStopper);

			this.serverSentEventServer = serverSentEventServer;
			this.sokletStopper = sokletStopper;
		}

		@ServerSentEventSource("/examples/{exampleId}")
		public HandshakeResult exampleServerSentEventSource(@Nonnull Request request,
																												@Nonnull @PathParameter String exampleId) {
			System.out.printf("Server-Sent Event Source connection initiated for %s with exampleId value %s\n", request.getId(), exampleId);
			return HandshakeResult.accept();
		}

		@POST("/fire-server-sent-event")
		public void fireServerSentEvent() {
			ResourcePath resourcePath = ResourcePath.withPath("/examples/abc"); // Matches /examples/{exampleId}
			ServerSentEventBroadcaster broadcaster = this.serverSentEventServer.acquireBroadcaster(resourcePath).get();

			ServerSentEvent serverSentEvent = ServerSentEvent.withEvent("test")
					.data("""
							{
							  "testing": 123,
							  "value": "abc"
							}
							""")
					.id(UUID.randomUUID().toString())
					.retry(Duration.ofSeconds(5))
					.build();

			broadcaster.broadcastEvent(serverSentEvent);
		}

		@POST("/shutdown")
		public void shutdown() {
			this.sokletStopper.run();
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sse_startStop_doesNotHang() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(3))
				.shutdownTimeout(Duration.ofSeconds(1))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).shutdownTimeout(Duration.ofSeconds(1)).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle()) // no noise in test logs
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			// if stop hangs due to accept(), this test times out
		} // try-with-resources stops both HTTP and SSE servers
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sse_stop_allowsIsStartedDuringShutdownWait() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.shutdownTimeout(Duration.ofSeconds(2))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle()) // no noise in test logs
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			DefaultServerSentEventServer internal = (DefaultServerSentEventServer) sse;
			ExecutorService handlerExecutor = internal.getRequestHandlerExecutorService().orElseThrow();

			CountDownLatch taskStarted = new CountDownLatch(1);
			AtomicBoolean allowExit = new AtomicBoolean(false);

			handlerExecutor.submit(() -> {
				taskStarted.countDown();
				while (!allowExit.get()) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException ignored) {
						// Keep waiting until allowExit flips.
					}
				}
			});

			Assertions.assertTrue(taskStarted.await(2, SECONDS), "Background task did not start");

			Thread stopThread = new Thread(app::stop, "sse-stop-test");
			stopThread.start();

			Field stoppingField = DefaultServerSentEventServer.class.getDeclaredField("stopping");
			stoppingField.setAccessible(true);

			boolean sawStopping = false;
			long deadline = System.currentTimeMillis() + 1000;

			while (System.currentTimeMillis() < deadline) {
				Boolean stopping = (Boolean) stoppingField.get(internal);
				if (Boolean.TRUE.equals(stopping)) {
					sawStopping = true;
					break;
				}
				Thread.sleep(5);
			}

			Assertions.assertTrue(sawStopping, "Stop did not enter stopping state in time");

			ExecutorService probe = Executors.newSingleThreadExecutor();
			try {
				Future<Boolean> startedFuture = probe.submit(internal::isStarted);
				Boolean started = startedFuture.get(200, TimeUnit.MILLISECONDS);
				Assertions.assertTrue(stopThread.isAlive(), "Stop completed before probe");
				Assertions.assertTrue(started, "Expected isStarted to return while stop is in progress");
			} finally {
				probe.shutdownNow();
			}

			allowExit.set(true);
			stopThread.join(3000);
			Assertions.assertFalse(stopThread.isAlive(), "Stop did not complete after releasing task");
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sse_handshakeHeaders_and_basicDelivery() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.verifyConnectionOnceEstablished(false)
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);

				// Handshake
				writeHttpGet(socket, "/tests/abc", ssePort);
				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);

				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				String[] headerLines = rawHeaders.split("\r?\n");
				Assertions.assertTrue(headerLines[0].startsWith("HTTP/1.1 200"), "Non-200 handshake");

				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(headerLines));
				Assertions.assertTrue(singleHeaderValue("content-type", headers).get().toLowerCase().contains("text/event-stream"),
						"Missing text/event-stream");
				Assertions.assertEquals("no", singleHeaderValue("x-accel-buffering", headers).get().toLowerCase());
				Assertions.assertEquals("keep-alive", singleHeaderValue("connection", headers).get().toLowerCase());
				Assertions.assertTrue(headerValues("cache-control", headers).stream().map(String::toLowerCase).collect(Collectors.toSet()).contains("no-cache"), "Missing no-cache");
				Assertions.assertTrue(headerValues("cache-control", headers).stream().map(String::toLowerCase).collect(Collectors.toSet()).contains("no-transform"), "Missing no-transform");

				// Broadcast one event and verify frame formatting
				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.withPath("/tests/abc")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("test")
						.data("hello\nworld")
						.id("e1")
						.retry(Duration.ofSeconds(10))
						.build();
				b.broadcastEvent(ev);

				String block = readUntil(socket.getInputStream(), "\n\n", 8192);
				Assertions.assertNotNull(block, "Did not receive first SSE event");
				List<String> lines = block.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();

				Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("event: test")));
				Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("id: e1")));
				Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("retry: 10000")));
				Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("data: hello")));
				Assertions.assertTrue(lines.stream().anyMatch(s -> s.equals("data: world")));
			}
		}
	}

	@Test
	@Timeout(value = 20, unit = SECONDS)
	public void sse_largeEvent_isFullyWritten() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.verifyConnectionOnceEstablished(false)
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/large", ssePort);
				// consume headers
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				// Build a ~128KiB payload split across many lines
				String line = "A".repeat(64);
				int linesCount = 2048; // 2048 * 64 ~= 131072
				String bigData = java.util.stream.Stream.generate(() -> line).limit(linesCount).collect(java.util.stream.Collectors.joining("\n"));

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.withPath("/tests/large")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("big").id("big-1").data(bigData).build();
				b.broadcastEvent(ev);

				// Read exactly one event block
				String block = readUntil(socket.getInputStream(), "\n\n", (64 + 8) * linesCount + 8192);
				Assertions.assertNotNull(block, "Did not receive large event");

				// Reconstruct data lines
				String reconstructed = block.lines()
						.filter(l -> l.startsWith("data: "))
						.map(l -> l.substring("data: ".length()))
						.collect(java.util.stream.Collectors.joining("\n"));

				Assertions.assertEquals(bigData.length(), reconstructed.length(), "Large SSE payload corrupted or truncated");
				Assertions.assertEquals(bigData, reconstructed, "Large SSE payload mismatch");
			}
		}
	}

	@Test
	@Timeout(value = 20, unit = SECONDS)
	public void sse_broadcastMany_underBackpressure_eitherDeliversOrCloses() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/backpressure", ssePort);
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.withPath("/tests/backpressure")).get();

				final int N = 1500;
				for (int i = 0; i < N; i++) {
					b.broadcastEvent(ServerSentEvent.withEvent("bp").id(String.valueOf(i)).data("x").build());
				}

				long deadline = System.currentTimeMillis() + 12000;
				boolean sawLast = false;
				boolean sawClosure = false;

				InputStream in = socket.getInputStream();
				while (System.currentTimeMillis() < deadline) {
					try {
						String block = readUntil(in, "\n\n", 4096);

						if (block == null) { // indicates EOF/closed or timeout-ish behavior
							sawClosure = true;
							break;
						}
						String idLine = block.lines().filter(l -> l.startsWith("id: ")).reduce((a, b2) -> b2).orElse(null);
						if (idLine != null && idLine.trim().equals("id: " + (N - 1))) {
							sawLast = true;
							break;
						}
					} catch (SocketTimeoutException e) {
						// keep looping until deadline
					} catch (EOFException e) {
						sawClosure = true;
						break;
					}
				}

				// New contract: either we kept up and saw the last event OR the server proactively closed us due to backpressure
				Assertions.assertTrue(sawLast || sawClosure,
						"Expected either to observe the last id OR have the connection closed due to backpressure");
			}
		}
	}

	@Test
	@Timeout(value = 20, unit = SECONDS)
	public void sse_backpressure_setsTerminationReason() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		BackpressureLifecycle lifecycle = new BackpressureLifecycle();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.connectionQueueCapacity(1)
				.heartbeatInterval(Duration.ofSeconds(30))
				.verifyConnectionOnceEstablished(false)
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(lifecycle)
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/backpressure-reason", ssePort);
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				ServerSentEventBroadcaster broadcaster = sse.acquireBroadcaster(ResourcePath.withPath("/tests/backpressure-reason")).get();
				awaitClientConnection(broadcaster, 2000);

				// First event will block in willWriteServerSentEvent.
				broadcaster.broadcastEvent(ServerSentEvent.withEvent("bp").id("1").data("a").build());
				Assertions.assertTrue(lifecycle.awaitWriteStarted(5, SECONDS), "Write did not start in time");

				// Fill the queue and then overflow to trigger backpressure.
				broadcaster.broadcastEvent(ServerSentEvent.withEvent("bp").id("2").data("b").build());
				broadcaster.broadcastEvent(ServerSentEvent.withEvent("bp").id("3").data("c").build());

				Assertions.assertTrue(lifecycle.awaitTermination(10, SECONDS), "Termination not observed");
				Assertions.assertEquals(ServerSentEventConnectionTerminationReason.BACKPRESSURE, lifecycle.getReason());
				lifecycle.releaseWriter();
			}
		}
	}

	@Test
	@Timeout(value = 15, unit = SECONDS)
	public void sse_stopClosesConnection() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000);
			socket.setSoTimeout(6000);

			writeHttpGet(socket, "/tests/closeme", ssePort);
			String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
			if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
			Assertions.assertNotNull(hdr);

			// Kick one message through so the writer loop is active
			sse.acquireBroadcaster(ResourcePath.withPath("/tests/closeme")).get()
					.broadcastEvent(ServerSentEvent.withEvent("one").id("1").data("a").build());
			readUntil(socket.getInputStream(), "\n\n", 4096); // consume it

			// Now stop the server; this should enqueue poison pills and close the channel
			app.close(); // stops both servers

			// Attempt to read again; we expect EOF (-1) within timeout
			boolean sawEof = waitForEof(socket, 6000);
			socket.close();
			Assertions.assertTrue(sawEof, "Connection did not close after server stop");
		}
	}

	@Test
	@Timeout(value = 15, unit = SECONDS)
	public void sse_stop_setsTerminationReason_serverStop() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		TerminationReasonLifecycle lifecycle = new TerminationReasonLifecycle();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(lifecycle)
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);

				writeHttpGet(socket, "/tests/terminate", ssePort);
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				ServerSentEventBroadcaster broadcaster = sse.acquireBroadcaster(ResourcePath.withPath("/tests/terminate")).get();
				awaitClientConnection(broadcaster, 2000);

				app.stop();
				Assertions.assertTrue(lifecycle.awaitTermination(5, SECONDS), "didTerminate not invoked");
				Assertions.assertEquals(ServerSentEventConnectionTerminationReason.SERVER_STOP, lifecycle.getReason());
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sse_concurrentConnectionLimit_rejectsExtraHandshakes() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		BlockingHandshakeResource.prepare(2);

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.verifyConnectionOnceEstablished(false)
				.concurrentConnectionLimit(1)
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(BlockingHandshakeResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket1 = connectWithRetry("127.0.0.1", ssePort, 2000);
					 Socket socket2 = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket1.setSoTimeout(4000);
				socket2.setSoTimeout(4000);

				writeHttpGet(socket1, "/sse/limit", ssePort);
				writeHttpGet(socket2, "/sse/limit", ssePort);

				BlockingHandshakeResource.awaitReady(5, SECONDS);
				BlockingHandshakeResource.release();

				String h1 = readUntil(socket1.getInputStream(), "\r\n\r\n", 4096);
				if (h1 == null) h1 = readUntil(socket1.getInputStream(), "\n\n", 4096);
				String h2 = readUntil(socket2.getInputStream(), "\r\n\r\n", 4096);
				if (h2 == null) h2 = readUntil(socket2.getInputStream(), "\n\n", 4096);

				Assertions.assertNotNull(h1, "No handshake response on socket1");
				Assertions.assertNotNull(h2, "No handshake response on socket2");

				boolean h1Ok = h1.startsWith("HTTP/1.1 200");
				boolean h2Ok = h2.startsWith("HTTP/1.1 200");
				boolean h1Busy = h1.startsWith("HTTP/1.1 503");
				boolean h2Busy = h2.startsWith("HTTP/1.1 503");

				Assertions.assertTrue(h1Ok ^ h2Ok, "Expected exactly one 200 handshake");
				Assertions.assertTrue(h1Busy ^ h2Busy, "Expected exactly one 503 handshake");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejected_writes_status_body_and_cookies() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		// Resource returns a REJECTED handshake with 403, a body, a header, and a Set-Cookie
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(RejectingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/reject", ssePort);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");

				String[] headerLines = rawHeaders.split("\r?\n");
				Assertions.assertTrue(headerLines[0].startsWith("HTTP/1.1 403"), "Expected 403 for rejected handshake");

				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(headerLines));
				// Body should be present and connection closed
				Assertions.assertEquals("close", firstOrEmpty(headers, "connection").toLowerCase(Locale.ROOT));
				// Our custom header survived
				Assertions.assertEquals("nope", firstOrEmpty(headers, "x-why"));

				// Body length is set (either by server or by our explicit header)
				int contentLength = Integer.parseInt(firstOrEmpty(headers, "content-length"));
				Assertions.assertTrue(contentLength > 0, "Missing/invalid Content-Length");

				// Cookies should be emitted — this is currently missing in the SSE code path and will FAIL until fixed
				boolean sawSetCookie = headers.containsKey("set-cookie") && headers.get("set-cookie").stream().anyMatch(v -> v.contains("session=sse-reject"));
				Assertions.assertTrue(sawSetCookie, "Missing Set-Cookie in SSE rejected handshake");

				// Read the body and ensure it matches
				byte[] body = readN(socket.getInputStream(), contentLength, 4000);
				Assertions.assertEquals("denied", new String(body, StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_unknown_path_returns_404_and_closes() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);

				// Path isn't mapped by @ServerSentEventSource -> should return 404 (currently the server can throw internally)
				writeHttpGet(socket, "/sse-does-not-exist", ssePort);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");

				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 404"), "Expected 404 on unknown SSE path");
				// Connection should close after rejected/normal response
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after 404 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejects_transfer_encoding() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				String req = "GET /sse/abc HTTP/1.1\r\n"
						+ "Host: 127.0.0.1:" + ssePort + "\r\n"
						+ "Accept: text/event-stream\r\n"
						+ "Transfer-Encoding: chunked\r\n"
						+ "\r\n";
				socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 400"), "Expected 400 for Transfer-Encoding");
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after 400 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejects_nonzero_content_length() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				String req = "GET /sse/abc HTTP/1.1\r\n"
						+ "Host: 127.0.0.1:" + ssePort + "\r\n"
						+ "Accept: text/event-stream\r\n"
						+ "Content-Length: 1\r\n"
						+ "\r\n";
				socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 400"), "Expected 400 for non-zero Content-Length");
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after 400 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejects_missing_host() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				String req = "GET /sse/abc HTTP/1.1\r\n"
						+ "Accept: text/event-stream\r\n"
						+ "\r\n";
				socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
				socket.getOutputStream().flush();

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 400"), "Expected 400 for missing Host header");
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after 400 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejects_control_char_header_value() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				ByteArrayOutputStream req = new ByteArrayOutputStream();
				req.write(("GET /sse/abc HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("Host: 127.0.0.1:" + ssePort + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("Accept: text/event-stream\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("X-Test: ok").getBytes(StandardCharsets.ISO_8859_1));
				req.write(0x01); // control character
				req.write(("bad\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				socket.getOutputStream().write(req.toByteArray());
				socket.getOutputStream().flush();

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 400"), "Expected 400 for control character in header value");
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after 400 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_allows_obsText_in_header_value() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				ByteArrayOutputStream req = new ByteArrayOutputStream();
				req.write(("GET /sse/abc HTTP/1.1\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("Host: 127.0.0.1:" + ssePort + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("Accept: text/event-stream\r\n").getBytes(StandardCharsets.ISO_8859_1));
				req.write(("X-Obs: a").getBytes(StandardCharsets.ISO_8859_1));
				req.write(0x85); // obs-text (NEL)
				req.write(("b\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				socket.getOutputStream().write(req.toByteArray());
				socket.getOutputStream().flush();

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 200"), "Expected 200 for obs-text in header value");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_times_out_returns_503_and_closes() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		BlockingHandshakeResource.prepare(1);

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.requestHandlerTimeout(Duration.ofMillis(200))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(BlockingHandshakeResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/limit", ssePort);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders, "Did not receive HTTP response headers");
				Assertions.assertTrue(rawHeaders.startsWith("HTTP/1.1 503"), "Expected 503 on handshake timeout");
				Assertions.assertTrue(waitForEof(socket, 3000), "Connection did not close after timeout response");
			}
		} finally {
			BlockingHandshakeResource.release();
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void handshake_rejected_respects_explicit_content_length() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(RejectWithExplicitContentLength.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/reject-explicit-cl", ssePort);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (rawHeaders == null) rawHeaders = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(rawHeaders);

				String[] lines = rawHeaders.split("\r?\n");
				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(lines));

				Set<String> cls = headers.getOrDefault("content-length", Set.of());
				Assertions.assertEquals(1, cls.size(), "Expected exactly one Content-Length header");
				Assertions.assertEquals("3", cls.stream().findFirst().get());
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sseAccepted_includesCorsHeaders_whenAllOriginsAuthorizer() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();
		String origin = "https://app.example";

		// Accepts any Origin; credentials=true implies ACAO "*" -> normalized to request Origin + "Vary: Origin"
		CorsAuthorizer cors = CorsAuthorizer.withAcceptAllPolicy();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.corsAuthorizer(cors)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseCorsResource.class)))
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2500)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/cors-ok", ssePort, origin);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(rawHeaders, "No HTTP response received");
				String[] lines = rawHeaders.split("\r?\n");

				// 200 OK handshake
				Assertions.assertTrue(lines[0].startsWith("HTTP/1.1 200"), "Expected 200 OK SSE handshake");

				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(lines));
				// Should echo Origin (because credentials=true)
				Assertions.assertEquals(origin, firstOrEmpty(headers, "access-control-allow-origin"));
				Assertions.assertEquals("true", firstOrEmpty(headers, "access-control-allow-credentials").toLowerCase(Locale.ROOT));

				// Since "*" was normalized to the concrete Origin, Vary: Origin must be present
				String vary = firstOrEmpty(headers, "vary").toLowerCase(Locale.ROOT);
				Assertions.assertTrue(vary.contains("origin"), "Missing 'Vary: Origin' header");

				// SSE accepted handshakes should keep the connection open; do not wait for EOF.
				// We deliberately don't read further (heartbeats may arrive later).
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sseRejected_includesCorsHeaders_whenAllOriginsAuthorizer() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();
		String origin = "https://app.example";

		CorsAuthorizer cors = CorsAuthorizer.withAcceptAllPolicy();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.corsAuthorizer(cors)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(RejectingSseCorsResource.class)))
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2500)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/cors-reject", ssePort, origin);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(rawHeaders, "No HTTP response received");
				String[] lines = rawHeaders.split("\r?\n");

				// Rejected handshake returns a non-200 status (we use 403 in the resource)
				Assertions.assertTrue(lines[0].startsWith("HTTP/1.1 403"), "Expected 403 for rejected handshake");

				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(lines));
				// CORS must still be applied to the error response
				Assertions.assertEquals(origin, firstOrEmpty(headers, "access-control-allow-origin"));
				Assertions.assertEquals("true", firstOrEmpty(headers, "access-control-allow-credentials").toLowerCase(Locale.ROOT));

				String vary = firstOrEmpty(headers, "vary").toLowerCase(Locale.ROOT);
				Assertions.assertTrue(vary.contains("origin"), "Missing 'Vary: Origin' header on rejected handshake");

				// Body may be present; we don't need to read/validate it for CORS, so we stop here.
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sseAccepted_omitsCorsHeaders_whenOriginNotWhitelisted() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();
		String origin = "https://not-allowed.example";

		// Only allow https://ok.example
		CorsAuthorizer cors = CorsAuthorizer.withWhitelistAuthorizer(o -> "https://ok.example".equalsIgnoreCase(o));

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.corsAuthorizer(cors)
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(AcceptingSseCorsResource.class)))
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2500)) {
				socket.setSoTimeout(4000);
				writeHttpGet(socket, "/sse/cors-ok", ssePort, origin);

				String rawHeaders = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);
				Assertions.assertNotNull(rawHeaders, "No HTTP response received");
				String[] lines = rawHeaders.split("\r?\n");

				Assertions.assertTrue(lines[0].startsWith("HTTP/1.1 200"), "Expected 200 OK SSE handshake");

				Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(lines));
				// Origin not authorized => no CORS headers
				Assertions.assertEquals("", firstOrEmpty(headers, "access-control-allow-origin"));
				Assertions.assertEquals("", firstOrEmpty(headers, "access-control-allow-credentials"));
				Assertions.assertEquals("", firstOrEmpty(headers, "vary"));
			}
		}
	}

	public static class AcceptingSseCorsResource {
		@ServerSentEventSource("/sse/cors-ok")
		public HandshakeResult ok(@Nonnull Request request) {
			// Standard SSE accepted handshake
			return HandshakeResult.accept();
		}
	}

	public static class RejectingSseCorsResource {
		@ServerSentEventSource("/sse/cors-reject")
		public HandshakeResult reject(@Nonnull Request request) {
			// Reject with a simple body; CORS should still be applied
			return HandshakeResult.rejectWithResponse(
					Response.withStatusCode(403)
							.headers(Map.of("Content-Type", Set.of("text/plain; charset=utf-8")))
							.body("denied")
							.build()
			);
		}
	}

	public static class RejectingSseResource {
		@ServerSentEventSource("/sse/reject")
		public HandshakeResult handshake(@Nonnull Request request) {
			// Rejected SSE handshake with a body, header, and a cookie
			ResponseCookie cookie = ResponseCookie.with("session", "sse-reject").path("/").build();
			Response response = Response.withStatusCode(403)
					.headers(Map.of("X-Why", Set.of("nope"),
							"Content-Type", Set.of("text/plain; charset=UTF-8")))
					.cookies(Set.of(cookie))
					.body("denied")
					.build();
			return HandshakeResult.rejectWithResponse(response);
		}
	}

	public static class AcceptingSseResource {
		@ServerSentEventSource("/sse/{id}")
		public HandshakeResult ok(@Nonnull Request request, @Nonnull @PathParameter String id) {
			return HandshakeResult.accept();
		}
	}

	public static class RejectWithExplicitContentLength {
		@ServerSentEventSource("/sse/reject-explicit-cl")
		public HandshakeResult reject(@Nonnull Request request) {
			Response response = Response.withStatusCode(418)
					.headers(Map.of("Content-Type", Set.of("text/plain; charset=UTF-8"),
							"Content-Length", Set.of("3")))
					.body("abc")
					.build();
			return HandshakeResult.rejectWithResponse(response);
		}
	}

	@ThreadSafe
	public static class SseNetworkResource {
		@ServerSentEventSource("/tests/{id}")
		public HandshakeResult sseSource(@Nonnull Request request, @Nonnull @PathParameter String id) {
			return HandshakeResult.accept();
		}
	}

	@ThreadSafe
	public static class BlockingHandshakeResource {
		private static volatile CountDownLatch ready;
		private static volatile CountDownLatch release;

		static void prepare(int connections) {
			ready = new CountDownLatch(connections);
			release = new CountDownLatch(1);
		}

		static void awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
			if (!ready.await(timeout, unit))
				throw new IllegalStateException("Timed out waiting for concurrent handshakes");
		}

		static void release() {
			release.countDown();
		}

		@ServerSentEventSource("/sse/limit")
		public HandshakeResult sseLimit(@Nonnull Request request) {
			ready.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return HandshakeResult.accept();
		}
	}

	@ThreadSafe
	public static class SseBasicHandshakeResource {
		@ServerSentEventSource("/sse")
		public HandshakeResult sse() {
			// accept and later broadcast from the test thread
			return HandshakeResult.accept();
		}
	}

	@Test
	public void ssePreservesBlankLines() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig config = SokletConfig
				.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.verifyConnectionOnceEstablished(false)
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// handshake
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(3000);
				OutputStream out = socket.getOutputStream();
				InputStream in = socket.getInputStream();
				out.write((
						"GET /sse HTTP/1.1\r\n" +
								"Host: 127.0.0.1:" + ssePort + "\r\n" +
								"Accept: text/event-stream\r\n" +
								"\r\n").getBytes());
				out.flush();

				// read headers
				readHeadersCRLF(in);

				// broadcast an event with a blank line in the middle and trailing newline
				ServerSentEvent evt = ServerSentEvent.withEvent("demo")
						.data("L1\n\nL3\n") // inner blank line + trailing newline
						.id("abc")
						.retry(Duration.ofSeconds(5))
						.build();

				ServerSentEventServer serverSentEventServer = soklet.getSokletConfig().getServerSentEventServer().get();

				// Wait until the broadcaster exists and has at least one connection
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
				ServerSentEventBroadcaster broadcaster;

				while (true) {
					broadcaster = serverSentEventServer.acquireBroadcaster(ResourcePath.withPath("/sse")).get();
					if (broadcaster.getClientCount() > 0) break;
					if (System.nanoTime() > deadline) throw new AssertionError("SSE connection not registered in time");
					Thread.sleep(10);
				}

				broadcaster.broadcastEvent(evt);

				// read lines on the wire
				List<String> block = new ArrayList<>();
				while (true) {
					String line = readLineLF(in);
					if (line == null) throw new IOException("stream closed");
					if (line.isEmpty()) break;        // event terminator
					block.add(line);
				}

				// Must have the metadata lines
				Assertions.assertTrue(block.contains("event: demo"));
				Assertions.assertTrue(block.contains("id: abc"));
				Assertions.assertTrue(block.contains("retry: 5000"));

				// Exactly four data lines, including blanks
				List<String> dataLines = block.stream()
						.filter(s -> s.startsWith("data:"))
						.toList();

				Assertions.assertEquals(4, dataLines.size());
				Assertions.assertEquals("data: L1", dataLines.get(0));
				Assertions.assertEquals("data: ", dataLines.get(1)); // blank line
				Assertions.assertEquals("data: L3", dataLines.get(2));
				Assertions.assertEquals("data: ", dataLines.get(3)); // trailing newline
			}
		}
	}

	@Test
	public void sseCommentFormatting_preservesBlankLinesAndSeparators() {
		DefaultServerSentEventServer server = (DefaultServerSentEventServer) ServerSentEventServer.withPort(0).build();
		String comment = "one\r\ntwo\nthree\rfour\n";
		String formatted = server.formatCommentForResponse(comment);

		Assertions.assertEquals(": one\n: two\n: three\n: four\n:\n\n", formatted);
		Assertions.assertEquals(":\n\n", server.formatCommentForResponse(""));
	}

	@Test
	public void idContainingNull_isRejected() {
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ServerSentEvent.withDefaults().id("abc\u0000def").build());
	}

	@Test
	public void sseHandlesNonGetsCorrectly() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig config = SokletConfig
				.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort).build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// handshake
			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(3000);
				OutputStream out = socket.getOutputStream();
				out.write((
						"POST /sse HTTP/1.1\r\n" +
								"Host: 127.0.0.1:" + ssePort + "\r\n" +
								"Accept: text/event-stream\r\n" +
								"\r\n").getBytes());
				out.flush();

				String response = readUntil(socket.getInputStream(), "\r\n\r\n", 8192);

				Assertions.assertTrue(response.startsWith("HTTP/1.1 405"), "Not a 405 response");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sseHandshake_allowsExtraSpacesInRequestLine() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2500)) {
				socket.setSoTimeout(4000);
				// Note the extra spaces after GET and before HTTP/1.1
				String req = "GET   /sse   HTTP/1.1\r\nHost: 127.0.0.1:" + ssePort + "\r\nAccept: text/event-stream\r\n\r\n";
				socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));

				String raw = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (raw == null) raw = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(raw, "No HTTP response headers received");
				Assertions.assertTrue(raw.startsWith("HTTP/1.1 200"), "Expected 200 OK SSE handshake");
			}
		}
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sseHandshake_acceptsAbsoluteRequestTarget() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.withClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2500)) {
				socket.setSoTimeout(4000);
				String req = "GET http://127.0.0.1:" + ssePort + "/sse HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n";
				socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));

				String raw = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (raw == null) raw = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(raw, "No HTTP response headers received");
				Assertions.assertTrue(raw.startsWith("HTTP/1.1 200"), "Expected 200 OK SSE handshake");
			}
		}
	}

	@Test
	public void writeMarshaledResponseToChannel_handlesPartialWrites() throws Exception {
		DefaultServerSentEventServer server = (DefaultServerSentEventServer) ServerSentEventServer.withPort(0).build();
		ResponseCookie cookie = ResponseCookie.with("session", "abc").path("/").build();

		MarshaledResponse response = MarshaledResponse.withStatusCode(503)
				.headers(Map.of(
						"X-Test", Set.of("one"),
						"Content-Type", Set.of("text/plain")
				))
				.cookies(Set.of(cookie))
				.body("payload".getBytes(StandardCharsets.UTF_8))
				.build();

		PartialWriteSocketChannel channel = new PartialWriteSocketChannel(3);

		Method method = DefaultServerSentEventServer.class.getDeclaredMethod("writeMarshaledResponseToChannel", SocketChannel.class, MarshaledResponse.class);
		method.setAccessible(true);
		method.invoke(server, channel, response);

		String output = new String(channel.getWrittenBytes(), StandardCharsets.UTF_8);
		String[] parts = output.split("\\r?\\n\\r?\\n", 2);

		Assertions.assertEquals(2, parts.length, "Missing header/body separator");

		String[] lines = parts[0].split("\\r?\\n");
		Assertions.assertTrue(lines[0].startsWith("HTTP/1.1 503"), "Expected 503 status line");

		Map<String, Set<String>> headers = Utilities.extractHeadersFromRawHeaderLines(Arrays.asList(lines).subList(1, lines.length));

		Assertions.assertEquals("one", firstOrEmpty(headers, "x-test"));
		Assertions.assertEquals("text/plain", firstOrEmpty(headers, "content-type"));

		String setCookie = firstOrEmpty(headers, "set-cookie");
		Assertions.assertTrue(setCookie.contains("session=abc"), "Missing Set-Cookie header");

		Assertions.assertEquals("payload", parts[1]);
	}

	private static class PartialWriteSocketChannel extends SocketChannel {
		private final ByteArrayOutputStream output;
		private final int maxBytesPerWrite;

		protected PartialWriteSocketChannel(int maxBytesPerWrite) {
			super(SelectorProvider.provider());
			this.output = new ByteArrayOutputStream();
			this.maxBytesPerWrite = maxBytesPerWrite;
		}

		byte[] getWrittenBytes() {
			return output.toByteArray();
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			int remaining = src.remaining();
			if (remaining == 0)
				return 0;

			int toWrite = Math.min(remaining, maxBytesPerWrite);
			byte[] buf = new byte[toWrite];
			src.get(buf);
			output.write(buf);
			return toWrite;
		}

		@Override
		public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
			long total = 0;
			for (int i = offset; i < offset + length; i++)
				total += write(srcs[i]);
			return total;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			throw new UnsupportedOperationException();
		}

		@Override
		public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
			throw new UnsupportedOperationException();
		}

		@Override
		public SocketChannel bind(SocketAddress local) {
			return this;
		}

		@Override
		public <T> SocketChannel setOption(SocketOption<T> name, T value) {
			return this;
		}

		@Override
		public <T> T getOption(SocketOption<T> name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Set<SocketOption<?>> supportedOptions() {
			return Set.of();
		}

		@Override
		public SocketChannel shutdownInput() {
			return this;
		}

		@Override
		public SocketChannel shutdownOutput() {
			return this;
		}

		@Override
		public Socket socket() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isConnected() {
			return true;
		}

		@Override
		public boolean isConnectionPending() {
			return false;
		}

		@Override
		public boolean connect(SocketAddress remote) {
			return true;
		}

		@Override
		public boolean finishConnect() {
			return true;
		}

		@Override
		public SocketAddress getRemoteAddress() {
			return null;
		}

		@Override
		public SocketAddress getLocalAddress() {
			return null;
		}

		@Override
		protected void implCloseSelectableChannel() {
			// nothing to close
		}

		@Override
		protected void implConfigureBlocking(boolean block) {
			// no-op
		}
	}

	private static class TerminationReasonLifecycle implements LifecycleInterceptor {
		private final CountDownLatch establishedLatch;
		private final CountDownLatch terminatedLatch;
		private final AtomicReference<ServerSentEventConnectionTerminationReason> reason;

		private TerminationReasonLifecycle() {
			this.establishedLatch = new CountDownLatch(1);
			this.terminatedLatch = new CountDownLatch(1);
			this.reason = new AtomicReference<>();
		}

		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }

		@Override
		public void didEstablishServerSentEventConnection(@Nonnull ServerSentEventConnection serverSentEventConnection) {
			this.establishedLatch.countDown();
		}

		@Override
		public void didTerminateServerSentEventConnection(@Nonnull ServerSentEventConnection serverSentEventConnection,
																											@Nonnull Duration connectionDuration,
																											@Nonnull ServerSentEventConnectionTerminationReason terminationReason,
																											@Nullable Throwable throwable) {
			this.reason.compareAndSet(null, terminationReason);
			this.terminatedLatch.countDown();
		}

		boolean awaitEstablished(long timeout, TimeUnit unit) throws InterruptedException {
			return this.establishedLatch.await(timeout, unit);
		}

		boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return this.terminatedLatch.await(timeout, unit);
		}

		ServerSentEventConnectionTerminationReason getReason() {
			return this.reason.get();
		}
	}

	private static class BackpressureLifecycle implements LifecycleInterceptor {
		private final CountDownLatch writeStarted;
		private final CountDownLatch allowWrite;
		private final CountDownLatch terminatedLatch;
		private final AtomicReference<ServerSentEventConnectionTerminationReason> reason;
		private final AtomicBoolean blocking;

		private BackpressureLifecycle() {
			this.writeStarted = new CountDownLatch(1);
			this.allowWrite = new CountDownLatch(1);
			this.terminatedLatch = new CountDownLatch(1);
			this.reason = new AtomicReference<>();
			this.blocking = new AtomicBoolean(false);
		}

		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }

		@Override
		public void willWriteServerSentEvent(@Nonnull ServerSentEventConnection serverSentEventConnection,
																				 @Nonnull ServerSentEvent serverSentEvent) {
			if (this.blocking.compareAndSet(false, true)) {
				this.writeStarted.countDown();
				try {
					this.allowWrite.await(5, SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public void didTerminateServerSentEventConnection(@Nonnull ServerSentEventConnection serverSentEventConnection,
																											@Nonnull Duration connectionDuration,
																											@Nonnull ServerSentEventConnectionTerminationReason terminationReason,
																											@Nullable Throwable throwable) {
			this.reason.compareAndSet(null, terminationReason);
			this.terminatedLatch.countDown();
		}

		boolean awaitWriteStarted(long timeout, TimeUnit unit) throws InterruptedException {
			return this.writeStarted.await(timeout, unit);
		}

		void releaseWriter() {
			this.allowWrite.countDown();
		}

		boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return this.terminatedLatch.await(timeout, unit);
		}

		ServerSentEventConnectionTerminationReason getReason() {
			return this.reason.get();
		}
	}

	private static String readLineCRLF(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int prev = -1, cur;
		while ((cur = in.read()) != -1) {
			if (prev == '\r' && cur == '\n') break;
			if (cur != '\r') buf.write(cur);
			prev = cur;
		}
		return buf.toString("UTF-8");
	}

	private static String readLineLF(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') break;       // LF ends the line
			if (b != '\r') buf.write(b); // drop any stray CR
		}
		return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private static void readHeadersCRLF(InputStream in) throws IOException {
		while (true) {
			String line = readLineCRLF(in);
			if (line.isEmpty()) return;
		}
	}

	private static class QuietLifecycle implements LifecycleInterceptor {
		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }
	}

	private static byte[] readN(InputStream in, int n, int timeoutMs) throws IOException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		byte[] out = new byte[n];
		int off = 0;
		while (off < n && System.currentTimeMillis() < deadline) {
			int r = in.read(out, off, n - off);
			if (r == -1) break;
			off += r;
		}
		if (off != n) throw new EOFException("short read");
		return out;
	}

	private static String firstOrEmpty(Map<String, Set<String>> headers, String key) {
		Set<String> v = headers.getOrDefault(key.toLowerCase(Locale.ROOT), Set.of());
		return v.isEmpty() ? "" : v.stream().findFirst().get();
	}

	private static void writeHttpGet(Socket socket, String path, int port) throws IOException {
		String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Accept: text/event-stream\r\n"
				+ "Connection: keep-alive\r\n"
				+ "\r\n";
		socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static void writeHttpGet(Socket socket, String path, int port, String origin) throws IOException {
		String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Accept: text/event-stream\r\n"
				+ "Connection: keep-alive\r\n"
				+ "Origin: " + origin + "\r\n"
				+ "\r\n";
		socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
		socket.getOutputStream().flush();
	}

	private static String readUntil(java.io.InputStream in, String terminator, int maxBytes) throws IOException {
		byte[] term = terminator.getBytes(StandardCharsets.UTF_8);
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		int b;
		int match = 0;
		while (buf.size() < maxBytes && (b = in.read()) != -1) {
			buf.write(b);
			if (b == term[match]) {
				match++;
				if (match == term.length) {
					return buf.toString(StandardCharsets.UTF_8);
				}
			} else {
				match = (b == term[0]) ? 1 : 0;
			}
		}
		return null;
	}

	private static void awaitClientConnection(ServerSentEventBroadcaster broadcaster, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (broadcaster.getClientCount() > 0)
				return;
			Thread.sleep(10);
		}
		throw new IllegalStateException("SSE connection not registered in time");
	}

	private static boolean waitForEof(Socket socket, int timeoutMs) throws IOException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		java.io.InputStream in = socket.getInputStream();
		byte[] tmp = new byte[1];
		while (System.currentTimeMillis() < deadline) {
			try {
				int n = in.read(tmp);
				if (n == -1) return true;
			} catch (java.net.SocketTimeoutException e) {
				// keep trying until deadline
			}
		}
		return false;
	}

	@Nonnull
	private static Optional<String> singleHeaderValue(String name, Map<String, Set<String>> headers) {
		Set<String> values = headers.get(name);

		if (values == null || values.size() == 0)
			return Optional.empty();

		if (values.size() > 1)
			throw new IllegalArgumentException(format("Tried to extract a single value for header named '%s', but it has more than 1 value: %s", name, values));

		return Optional.of(new ArrayList<>(values).get(0));
	}

	@Nonnull
	private static Set<String> headerValues(String name, Map<String, Set<String>> headers) {
		Set<String> values = headers.get(name);

		if (values == null || values.size() == 0)
			return Set.of();

		return values;
	}

}
