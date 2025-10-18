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

import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.ServerSentEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ServerSentEventTests {
	@Test
	public void serverSentEventServerSimulator() throws InterruptedException {
		SokletConfig configuration = SokletConfig.forTesting()
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(ServerSentEventSimulatorResource.class)))
				.build();

		Soklet.runSimulator(configuration, (simulator -> {
			simulator.registerServerSentEventConsumers(ResourcePath.withPath("/examples/abc"),
					(serverSentEvent) -> {
						Assertions.assertEquals("example", serverSentEvent.getEvent().get(), "SSE event mismatch");
					},
					(comment) -> {
						// Nothing to do for now
					});

			// Perform initial handshake with /examples/abc and verify 200 response
			Request request = Request.with(HttpMethod.GET, "/examples/abc").build();
			RequestResult requestResult = simulator.performRequest(request);

			Assertions.assertEquals(Integer.valueOf(200), requestResult.getMarshaledResponse().getStatusCode());

			// Create a server-sent event...
			ServerSentEvent serverSentEvent = ServerSentEvent.withEvent("example")
					.data("data")
					.id("abc")
					.retry(Duration.ofSeconds(10))
					.build();

			// ...and broadcast it to all /examples/abc listeners
			ServerSentEventBroadcaster broadcaster = simulator.acquireServerSentEventBroadcaster(ResourcePath.withPath("/examples/abc"));
			broadcaster.broadcastEvent(serverSentEvent);
		}));
	}

	@ThreadSafe
	public static class ServerSentEventSimulatorResource {
		@ServerSentEventSource("/examples/{exampleId}")
		public HandshakeResult exampleServerSentEventSource(@Nonnull Request request,
																												@Nonnull @PathParameter String exampleId) {
			return HandshakeResult.accept();
		}
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle()) // no noise in test logs
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();
			// if stop hangs due to accept(), this test times out
		} // try-with-resources stops both HTTP and SSE servers
	}

	@Test
	@Timeout(value = 10, unit = SECONDS)
	public void sse_handshakeHeaders_and_basicDelivery() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseNetworkResource.class)))
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
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseNetworkResource.class)))
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
	public void sse_broadcastMany_doesNotThrow_andEventuallyDeliversLast() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		ServerSentEventServer sse = ServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.withConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/backpressure", ssePort);
				// consume headers
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.withPath("/tests/backpressure")).get();

				// Rapidly broadcast a bunch of small events (some will be dropped under pressure, but last should survive)
				final int N = 1500;
				for (int i = 0; i < N; i++) {
					b.broadcastEvent(ServerSentEvent.withEvent("bp").id(String.valueOf(i)).data("x").build());
				}

				// Now read until we observe the last id (or time out)
				long deadline = System.currentTimeMillis() + 12000;
				boolean sawLast = false;
				while (System.currentTimeMillis() < deadline) {
					String block = readUntil(socket.getInputStream(), "\n\n", 4096);
					if (block == null) continue;
					String idLine = block.lines().filter(l -> l.startsWith("id: ")).reduce((a, b2) -> b2).orElse(null);
					if (idLine != null && idLine.trim().equals("id: " + (N - 1))) {
						sawLast = true;
						break;
					}
				}

				Assertions.assertTrue(sawLast, "Did not observe the last broadcast id under backpressure");
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseNetworkResource.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(RejectingSseResource.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(AcceptingSseResource.class)))
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
	public void handshake_rejected_respects_explicit_content_length() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(httpPort).build())
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort)
						.host("127.0.0.1")
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(RejectWithExplicitContentLength.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(AcceptingSseCorsResource.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(RejectingSseCorsResource.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(AcceptingSseCorsResource.class)))
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
				.serverSentEventServer(ServerSentEventServer.withPort(ssePort).build())
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// handshake
			try (Socket socket = new Socket("127.0.0.1", ssePort)) {
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseBasicHandshakeResource.class)))
				.build();

		try (Soklet soklet = Soklet.withConfig(config)) {
			soklet.start();

			// handshake
			try (Socket socket = new Socket("127.0.0.1", ssePort)) {
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseBasicHandshakeResource.class)))
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
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(Set.of(SseBasicHandshakeResource.class)))
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

	private static Socket connectWithRetry(String host, int port, int timeoutMs) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		IOException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				Socket s = new Socket();
				s.connect(new java.net.InetSocketAddress(host, port), Math.max(250, timeoutMs / 2));
				return s;
			} catch (IOException e) {
				last = e;
				Thread.sleep(30);
			}
		}
		throw (last != null ? last : new IOException("Unable to connect to " + host + ":" + port));
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

	private static int findFreePort() throws IOException {
		try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
