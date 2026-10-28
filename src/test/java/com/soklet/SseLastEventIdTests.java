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

import com.soklet.annotation.SseEventSource;
import com.soklet.exception.IllegalRequestException;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class SseLastEventIdTests {
	@Test
	public void rawLastEventIdDecodesUtf8WithoutChangingOtherHeaderValues() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		for (String id : List.of("123", "caf\u00e9-\u96ea-\ud83d\ude80", "\u00a0cursor\u3000")) {
			String raw = new String(replayRequest(80, id.getBytes(StandardCharsets.UTF_8)), StandardCharsets.ISO_8859_1);
			for (Request request : List.of(server.parseRequest(raw, null), server.parseTooLargeRequestForRawRequest(raw).orElseThrow())) {
				Assertions.assertEquals(List.of(id), request.getHeaders().get("Last-Event-ID"));
				Assertions.assertEquals(List.of("\u00e9"), request.getHeaders().get("X-Latin1"));
			}
			// Public map input is already decoded text and must never be reinterpreted as wire bytes.
			Assertions.assertEquals(List.of(id), Request.withPath(HttpMethod.GET, "/replay")
					.headers(Map.of("Last-Event-ID", List.of(id))).build().getHeaders().get("Last-Event-ID"));
		}
	}

	@Test
	public void malformedUtf8LastEventIdsCannotBecomeParsedRequests() {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		for (byte[] invalid : invalidLastEventIds()) {
			String raw = new String(replayRequest(80, invalid), StandardCharsets.ISO_8859_1);
			Assertions.assertThrows(IllegalRequestException.class, () -> server.parseRequest(raw, null));
			Assertions.assertTrue(server.parseTooLargeRequestForRawRequest(raw).isEmpty());
		}
	}

	@Test
	public void lastEventIdDecodingDoesNotReduceTheWireHeaderSizeLimit() {
		String raw = new String(replayRequest(80, "\u96ea\ud83d\ude80".getBytes(StandardCharsets.UTF_8)), StandardCharsets.ISO_8859_1);
		int wireHeaderBytes = raw.length() - raw.indexOf("\r\n") - 2;
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0)
				.maximumHeadersSizeInBytes(wireHeaderBytes - 1).build();
		Assertions.assertThrows(DefaultSseServer.RequestHeadersTooLargeIOException.class, () -> server.parseRequest(raw, null));
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void utf8LastEventIdsReachTheResourceIncludingSupplementaryCharacters() throws Exception {
		try (ReplayFixture fixture = new ReplayFixture()) {
			for (String id : List.of("123", "caf\u00e9-\u96ea-\ud83d\ude80", "\u00a0cursor\u3000")) {
				try (Socket socket = connectWithRetry("127.0.0.1", fixture.port, 2000)) {
					socket.setSoTimeout(2000);
					socket.getOutputStream().write(replayRequest(fixture.port, id.getBytes(StandardCharsets.UTF_8)));
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
					Assertions.assertTrue(reader.readLine().startsWith("HTTP/1.1 200"));
					Request request = ReplayResource.requests.poll(2, TimeUnit.SECONDS);
					Assertions.assertNotNull(request);
					Assertions.assertEquals(List.of(id), request.getHeaders().get("Last-Event-ID"));
					Assertions.assertEquals(List.of("\u00e9"), request.getHeaders().get("X-Latin1"));
				}
			}
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void malformedUtf8LastEventIdsGet400WithoutReachingTheResource() throws Exception {
		try (ReplayFixture fixture = new ReplayFixture()) {
			for (byte[] invalid : invalidLastEventIds()) {
				try (Socket socket = connectWithRetry("127.0.0.1", fixture.port, 2000)) {
					socket.setSoTimeout(2000);
					socket.getOutputStream().write(replayRequest(fixture.port, invalid));
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
					Assertions.assertTrue(reader.readLine().startsWith("HTTP/1.1 400"));
				}
			}
			Assertions.assertTrue(ReplayResource.requests.isEmpty(), "Malformed replay cursors must not reach application code");
		}
	}

	private static List<byte[]> invalidLastEventIds() {
		return List.of(new byte[]{(byte) 0x80}, new byte[]{(byte) 0xc0, (byte) 0xaf},
				new byte[]{(byte) 0xe2, (byte) 0x82}, new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
				new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, new byte[]{(byte) 0xe9});
	}

	private static byte[] replayRequest(int port, byte[] id) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes(("GET /replay HTTP/1.1\r\nHost: localhost:" + port
				+ "\r\nlAsT-eVeNt-Id: ").getBytes(StandardCharsets.ISO_8859_1));
		bytes.writeBytes(id);
		bytes.writeBytes("\r\nX-Latin1: \u00e9\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
		return bytes.toByteArray();
	}

	public static class ReplayResource {
		private static final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
		@SseEventSource("/replay")
		public SseHandshakeResult replay(Request request) {
			requests.add(request);
			return SseHandshakeResult.accept();
		}
	}

	private static final class ReplayFixture implements AutoCloseable {
		private final int port;
		private final Soklet soklet;
		private ReplayFixture() throws Exception {
			ReplayResource.requests.clear();
			this.port = findFreePort();
			this.soklet = Soklet.fromConfig(SokletConfig.withSseServer(SseServer.withPort(this.port)
					.host("127.0.0.1").verifyConnectionOnceEstablished(false).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ReplayResource.class)))
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(2))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { /* quiet malformed-input checks */ }
					}).build());
			this.soklet.start();
		}
		@Override public void close() { this.soklet.close(); }
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void lastEventIdHeaderIsVisibleToResource() throws Exception {
		int ssePort = findFreePort();

		SseServer sseServer = SseServer.withPort(ssePort)
				.verifyConnectionOnceEstablished(false)
				.build();

		SokletConfig config = SokletConfig.withSseServer(sseServer)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseResource.class)))
				.build();

		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();

			try (Socket socket = connectWithRetry("127.0.0.1", ssePort, 2000)) {
				writeHttpGet(socket, "/sse/abc", ssePort,
						"Accept: text/event-stream\r\n" +
								"Last-Event-ID: 123\r\n");

				BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				// First response line
				String status = br.readLine();
				Assertions.assertTrue(status.startsWith("HTTP/1.1 200"));

				// Headers
				String line;
				boolean sawEcho = false;
				while ((line = br.readLine()) != null) {
					if (line.isEmpty()) break; // end headers
				}

				// First SSE frame
				String data = br.readLine(); // e.g., "data: lastEventId=123"
				if (data != null && data.startsWith("data: ")) {
					sawEcho = data.contains("lastEventId=123");
				}

				Assertions.assertTrue(sawEcho, "HttpServer did not receive Last-Event-ID");
			}
		}
	}

	public static class SseResource {
		@SseEventSource("/sse/{id}")
		public SseHandshakeResult sse(@NonNull Request request,
															 @NonNull SseServer sseServer) {
			String last = request.getHeaders().getOrDefault("Last-Event-ID", List.of()).stream().findFirst().orElse("none");

			// Wait a bit and then broadcast
			new Thread(() -> {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}

				SseBroadcaster broadcaster = sseServer.acquireBroadcaster(ResourcePath.fromPath("/sse/abc")).get();
				broadcaster.broadcastEvent(SseEvent.withData("lastEventId=" + last).build());
			}).start();

			return SseHandshakeResult.accept();
		}
	}

	private static void writeHttpGet(Socket socket, String path, int port, String extraHeaders) throws IOException {
		String req = "GET " + path + " HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + port + "\r\n" +
				extraHeaders +
				"\r\n";
		socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
		socket.getOutputStream().flush();
	}
}
