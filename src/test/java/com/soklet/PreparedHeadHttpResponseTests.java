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
import com.soklet.annotation.HEAD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prepared HEAD metadata must survive the default pipeline and finite HTTP transport. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PreparedHeadHttpResponseTests {

	@Test
	void explicitHeadPreservesPreparedGetLengthWithoutWritingBodyBytes() throws Exception {
		try (Fixture fixture = new Fixture()) {
			WireResponse get = fixture.pipeline("GET", "/prepared", Map.of());
			WireResponse head = fixture.pipeline("HEAD", "/prepared", Map.of());
			assertEquals(200, head.status());
			assertEquals(Resource.REPRESENTATION, get.body());
			assertEquals(List.of(Integer.toString(Resource.REPRESENTATION_BYTES.length)), get.headers().get("content-length"));
			assertEquals(get.headers().get("content-length"), head.headers().get("content-length"));
			assertEquals(get.headers().get("content-type"), head.headers().get("content-type"));
			assertEquals(get.headers().get("etag"), head.headers().get("etag"));
			assertEquals("", head.body());
		}
	}

	@Test
	void explicitHeadWithoutRepresentationLengthOmitsContentLength() throws Exception {
		try (Fixture fixture = new Fixture()) {
			WireResponse response = fixture.pipeline("HEAD", "/unknown", Map.of());
			assertEquals(200, response.status());
			assertFalse(response.headers().containsKey("content-length"), response.headers().toString());
			assertEquals("", response.body());
		}
	}

	@Test
	void conditionalGetAndHeadDoNotInventContentLengthForNotModified() throws Exception {
		try (Fixture fixture = new Fixture()) {
			for (String method : List.of("GET", "HEAD")) {
				WireResponse response = fixture.pipeline(method, "/prepared", Map.of("If-None-Match", Resource.ETAG));
				assertEquals(304, response.status(), method);
				assertEquals(List.of(Resource.ETAG), response.headers().get("etag"), method);
				assertFalse(response.headers().containsKey("content-length"), method + ": " + response.headers());
				assertEquals("", response.body(), method);
			}
		}
	}

	@Test
	void implicitHeadStillCalculatesLengthFromGetRepresentation() throws Exception {
		try (Fixture fixture = new Fixture()) {
			WireResponse get = fixture.pipeline("GET", "/fallback", Map.of());
			WireResponse head = fixture.pipeline("HEAD", "/fallback", Map.of());
			assertEquals(200, head.status());
			assertEquals("fallback body", get.body());
			assertEquals(List.of("13"), head.headers().get("content-length"));
			assertEquals(get.headers().get("content-length"), head.headers().get("content-length"));
			assertEquals("", head.body());
		}
	}

	public static final class Resource {
		private static final String REPRESENTATION = "{\"keys\":[]}";
		private static final byte[] REPRESENTATION_BYTES = REPRESENTATION.getBytes(StandardCharsets.US_ASCII);
		private static final String ETAG = "\"prepared-jwks\"";

		@GET("/prepared")
		@HEAD("/prepared")
		public MarshaledResponse prepared(Request request) {
			if (request.getHeader("If-None-Match").filter(ETAG::equals).isPresent())
				return MarshaledResponse.withStatusCode(304).headers(Map.of("ETag", List.of(ETAG))).build();

			var builder = MarshaledResponse.withStatusCode(200).headers(Map.of(
					"Content-Type", List.of("application/jwk-set+json"),
					"content-length", List.of(Integer.toString(REPRESENTATION_BYTES.length)),
					"ETag", List.of(ETAG)));
			if (request.getHttpMethod() != HttpMethod.HEAD)
				builder.body(REPRESENTATION_BYTES);
			return builder.build();
		}

		@HEAD("/unknown")
		public MarshaledResponse unknown() {
			return MarshaledResponse.withStatusCode(200).build();
		}

		@GET("/fallback")
		public String fallback() {
			return "fallback body";
		}

		@GET("/after")
		public String after() {
			return "next response";
		}
	}

	private record WireResponse(int status, Map<String, List<String>> headers, String body) {}

	private static final class Fixture implements AutoCloseable {
		private final int port;
		private final Soklet soklet;

		private Fixture() throws Exception {
			this.port = findFreePort();
			Resource resource = new Resource();
			SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(this.port).host("127.0.0.1").build())
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					})
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) {}
					})
					.build();
			this.soklet = Soklet.fromConfig(config);
			this.soklet.start();
		}

		private WireResponse pipeline(String method, String path, Map<String, String> headers) throws Exception {
			try (Socket socket = connectWithRetry("127.0.0.1", this.port, 2_000)) {
				StringBuilder request = new StringBuilder(method).append(' ').append(path)
						.append(" HTTP/1.1\r\nHost: localhost\r\n");
				headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
				request.append("\r\nGET /after HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
				socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));

				// Read the complete bounded exchange before parsing. A body on HEAD/304 would
				// corrupt the following response's status line or leave extra bytes behind.
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				ByteArrayOutputStream wire = new ByteArrayOutputStream();
				byte[] buffer = new byte[1_024];
				InputStream input = socket.getInputStream();
				while (true) {
					long remaining = deadline - System.nanoTime();
					assertTrue(remaining > 0, "Pipelined exchange exceeded five seconds");
					socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
					int count = input.read(buffer);
					if (count == -1)
						break;
					assertTrue(wire.size() + count <= 8_192, "Pipelined exchange exceeded 8 KiB");
					wire.write(buffer, 0, count);
				}

				ByteArrayInputStream completed = new ByteArrayInputStream(wire.toByteArray());
				WireResponse first = readResponse(completed, method.equals("HEAD"));
				WireResponse next = readResponse(completed, false);
				assertEquals(200, next.status());
				assertEquals("next response", next.body());
				assertEquals(-1, completed.read(), "Unexpected bytes after the pipelined responses");
				return first;
			}
		}

		@Override public void close() {
			this.soklet.close();
		}
	}

	private static WireResponse readResponse(InputStream input, boolean head) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		String text;
		do {
			int next = input.read();
			assertTrue(next >= 0, "Response ended before headers");
			bytes.write(next);
			assertTrue(bytes.size() <= 4_096, "Response headers exceeded 4 KiB");
			text = bytes.toString(StandardCharsets.ISO_8859_1);
		} while (!text.endsWith("\r\n\r\n"));

		String[] lines = text.split("\r\n");
		assertTrue(lines[0].startsWith("HTTP/1.1 "), "Malformed status line (unexpected body bytes): " + lines[0]);
		int status = Integer.parseInt(lines[0].split(" ")[1]);
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (int i = 1; i < lines.length; i++) {
			int separator = lines[i].indexOf(':');
			assertTrue(separator > 0, lines[i]);
			headers.computeIfAbsent(lines[i].substring(0, separator).toLowerCase(Locale.ROOT), name -> new ArrayList<>())
					.add(lines[i].substring(separator + 1).trim());
		}
		assertFalse(headers.containsKey("transfer-encoding"), headers.toString());
		int length = Integer.parseInt(headers.getOrDefault("content-length", List.of("0")).get(0));
		assertTrue(length >= 0 && length <= 4_096, "Representation length exceeded fixture bound");
		int bodyLength = head || status == 304 ? 0 : length;
		byte[] body = input.readNBytes(bodyLength);
		assertEquals(bodyLength, body.length);
		return new WireResponse(status, headers, new String(body, StandardCharsets.US_ASCII));
	}
}
