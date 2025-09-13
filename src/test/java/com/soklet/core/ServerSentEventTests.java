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

package com.soklet.core;

import com.soklet.Soklet;
import com.soklet.SokletConfiguration;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.ServerSentEventSource;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.DefaultServer;
import com.soklet.core.impl.DefaultServerSentEventServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class ServerSentEventTests {
	@Test
	public void serverSentEventServerSimulator() throws InterruptedException {
		SokletConfiguration configuration = SokletConfiguration.forTesting()
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(ServerSentEventSimulatorResource.class)))
				.build();

		Soklet.runSimulator(configuration, (simulator -> {
			simulator.registerServerSentEventConsumer(ResourcePath.of("/examples/abc"), (serverSentEvent -> {
				Assertions.assertEquals("example", serverSentEvent.getEvent().get(), "SSE event mismatch");
			}));

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
			ServerSentEventBroadcaster broadcaster = simulator.acquireServerSentEventBroadcaster(ResourcePath.of("/examples/abc"));
			broadcaster.broadcast(serverSentEvent);
		}));
	}

	@ThreadSafe
	public static class ServerSentEventSimulatorResource {
		@ServerSentEventSource("/examples/{exampleId}")
		public Response exampleServerSentEventSource(@Nonnull Request request,
																								 @Nonnull @PathParameter String exampleId) {
			return Response.withStatusCode(200).build();
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
		public Response exampleServerSentEventSource(@Nonnull Request request,
																								 @Nonnull @PathParameter String exampleId) {
			System.out.printf("Server-Sent Event Source connection initiated for %s with exampleId value %s\n", request.getId(), exampleId);
			return Response.withStatusCode(200).build();
		}

		@POST("/fire-server-sent-event")
		public void fireServerSentEvent() {
			ResourcePath resourcePath = ResourcePath.of("/examples/abc"); // Matches /examples/{exampleId}
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

			broadcaster.broadcast(serverSentEvent);
		}

		@POST("/shutdown")
		public void shutdown() {
			this.sokletStopper.run();
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	public void sse_startStop_doesNotHang() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle()) // no noise in test logs
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();
			// if stop hangs due to accept(), this test times out
		} // try-with-resources stops both HTTP and SSE servers
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	public void sse_handshakeHeaders_and_basicDelivery() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
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

				Map<String, String> headers = parseHeaders(headerLines);
				Assertions.assertTrue(headers.getOrDefault("content-type", "").toLowerCase().contains("text/event-stream"),
						"Missing text/event-stream");
				Assertions.assertEquals("no", headers.getOrDefault("x-accel-buffering", "").toLowerCase());
				Assertions.assertEquals("keep-alive", headers.getOrDefault("connection", "").toLowerCase());
				Assertions.assertEquals("no-cache", headers.getOrDefault("cache-control", "").toLowerCase());

				// Broadcast one event and verify frame formatting
				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/abc")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("test")
						.data("hello\nworld")
						.id("e1")
						.retry(Duration.ofSeconds(10))
						.build();
				b.broadcast(ev);

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
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	public void sse_largeEvent_isFullyWritten() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
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

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/large")).get();
				ServerSentEvent ev = ServerSentEvent.withEvent("big").id("big-1").data(bigData).build();
				b.broadcast(ev);

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
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	public void sse_broadcastMany_doesNotThrow_andEventuallyDeliversLast() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				socket.setSoTimeout(12000);

				writeHttpGet(socket, "/tests/backpressure", ssePort);
				// consume headers
				String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
				if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
				Assertions.assertNotNull(hdr);

				ServerSentEventBroadcaster b = sse.acquireBroadcaster(ResourcePath.of("/tests/backpressure")).get();

				// Rapidly broadcast a bunch of small events (some will be dropped under pressure, but last should survive)
				final int N = 1500;
				for (int i = 0; i < N; i++) {
					b.broadcast(ServerSentEvent.withEvent("bp").id(String.valueOf(i)).data("x").build());
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
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	public void sse_stopClosesConnection() throws Exception {
		int httpPort = findFreePort();
		int ssePort = findFreePort();

		DefaultServerSentEventServer sse = DefaultServerSentEventServer.withPort(ssePort)
				.host("127.0.0.1")
				.requestTimeout(Duration.ofSeconds(5))
				.build();

		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(httpPort).build())
				.serverSentEventServer(sse)
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(SseNetworkResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();

		try (Soklet app = new Soklet(cfg)) {
			app.start();

			Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000);
			socket.setSoTimeout(6000);

			writeHttpGet(socket, "/tests/closeme", ssePort);
			String hdr = readUntil(socket.getInputStream(), "\r\n\r\n", 4096);
			if (hdr == null) hdr = readUntil(socket.getInputStream(), "\n\n", 4096);
			Assertions.assertNotNull(hdr);

			// Kick one message through so the writer loop is active
			sse.acquireBroadcaster(ResourcePath.of("/tests/closeme")).get()
					.broadcast(ServerSentEvent.withEvent("one").id("1").data("a").build());
			readUntil(socket.getInputStream(), "\n\n", 4096); // consume it

			// Now stop the server; this should enqueue poison pills and close the channel
			app.close(); // stops both servers

			// Attempt to read again; we expect EOF (-1) within timeout
			boolean sawEof = waitForEof(socket, 6000);
			socket.close();
			Assertions.assertTrue(sawEof, "Connection did not close after server stop");
		}
	}

	// ---------- Helpers & mini resource ----------

	@ThreadSafe
	public static class SseNetworkResource {
		@ServerSentEventSource("/tests/{id}")
		public Response sseSource(@Nonnull Request request, @Nonnull @PathParameter String id) {
			return Response.withStatusCode(200).build();
		}
	}

	private static class QuietLifecycle implements LifecycleInterceptor {
		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }
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

	private static Map<String, String> parseHeaders(String[] headerLines) {
		java.util.HashMap<String, String> m = new java.util.HashMap<>();
		for (int i = 1; i < headerLines.length; i++) {
			String line = headerLines[i];
			int idx = line.indexOf(':');
			if (idx <= 0) continue;
			String k = line.substring(0, idx).trim().toLowerCase(java.util.Locale.ROOT);
			String v = line.substring(idx + 1).trim();
			m.put(k, v);
		}
		return m;
	}

	private static int findFreePort() throws IOException {
		try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
