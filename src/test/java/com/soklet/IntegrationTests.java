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
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static com.soklet.TestSupport.readAll;

/**
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
public class IntegrationTests {
	@ThreadSafe
	public static class EchoResource {
		@GET("/hello")
		public String hello() {
			return "hello world";
		}

		@GET("/q")
		public String echoQuery(@NonNull @QueryParameter String q) {
			return q;
		}

		// Varargs echo to observe decoded path
		@GET("/files/{path*}")
		public String echoVarargs(@NonNull @PathParameter String path) {
			return path;
		}

		// A route that just returns all cookie names it sees
		@GET("/cookie-echo")
		public String cookieEcho(@NonNull Request request) {
			Map<String, Set<String>> cookies = request.getCookies();
			return cookies.keySet().stream().sorted().collect(Collectors.joining(","));
		}

		@GET("/multivalued-headers")
		public Response multivaluedHeaders(@NonNull Request request) {
			return Response.withStatusCode(200)
					.headers(Map.of("multi", Set.of("one", "two")))
					.cookies(Set.of(
							ResponseCookie.with("a", "b").build(),
							ResponseCookie.with("a", "c").build()
					))
					.build();
		}
	}

	private static class QuietLifecycle implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent logEvent) { /* no-op */ }
	}

	private static HttpURLConnection open(String method, URL url, Map<String, String> headers) throws IOException {
		HttpURLConnection c = (HttpURLConnection) url.openConnection();
		c.setRequestMethod(method);
		c.setInstanceFollowRedirects(false);
		c.setConnectTimeout(3000);
		c.setReadTimeout(3000);
		if (headers != null) {
			for (var e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
		}
		if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
			c.setDoOutput(true);
		}
		return c;
	}

	private static Soklet startApp(int port, Set<Class<?>> resourceClasses) {
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port).requestTimeout(Duration.ofSeconds(5)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(resourceClasses))
				.lifecycleObserver(new QuietLifecycle())
				.build();
		Soklet app = Soklet.fromConfig(cfg);
		app.start();
		return app;
	}

	@Test
	public void varargs_pathRoundTrip_overNetwork() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// Basic varargs round-trip (no decoding assertions here)
			URL url = new URL("http://127.0.0.1:" + port + "/files/js/some/file/example.js");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c.getResponseCode());
			String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			Assertions.assertEquals("js/some/file/example.js", body);
		}
	}

	@Test
	public void multivalueHeadersAreSplitCorrectly() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			URL url = new URL("http://127.0.0.1:" + port + "/multivalued-headers");

			String host = "127.0.0.1";
			String path = "/multivalued-headers";

			try (Socket socket = connectWithRetry(host, port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				// Send raw HTTP request
				out.write(("GET " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Host: " + host + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.flush();

				// Read raw response
				try (InputStream in = socket.getInputStream()) {
					// Read status line
					String status = readLineCRLF(in);
					Assertions.assertNotNull(status);

					// Read headers until blank line
					StringBuilder rawHeaders = new StringBuilder();
					String line;
					while ((line = readLineCRLF(in)) != null && !line.isEmpty()) {
						rawHeaders.append(line).append("\r\n");
					}

					// Now parse/verify
					String headers = rawHeaders.toString();
					Assertions.assertTrue(headers.contains("Content-Length: 0"));
					Assertions.assertTrue(headers.contains("Content-Type: text/plain; charset=UTF-8"));
					// Multi-valued headers preserved on the wire:
					Assertions.assertTrue(headers.contains("Set-Cookie: a=b"));
					Assertions.assertTrue(headers.contains("Set-Cookie: a=c"));
					Assertions.assertTrue(headers.contains("multi: one"));
					Assertions.assertTrue(headers.contains("multi: two"));

					// Since we don't need the body, just stop here.  If we do need the body later, read content-length bytes
				}
			}
		}
	}

	@Test
	public void malformedRequest_withNonAsciiHeaderName_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				String request = "GET /hello HTTP/1.1\r\n"
						+ "Host: 127.0.0.1\r\n"
						+ "X-\u00C4: 1\r\n"
						+ "\r\n";
				out.write(request.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for malformed header name");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void malformedRequest_withControlCharHeaderValue_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				ByteArrayOutputStream req = new ByteArrayOutputStream();
				req.write("GET /hello HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
				req.write("Host: 127.0.0.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
				req.write("X-Test: ok".getBytes(StandardCharsets.ISO_8859_1));
				req.write(0x01); // control character
				req.write("bad\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				out.write(req.toByteArray());
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for malformed header value");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void malformedRequest_missingHost_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				String request = "GET /hello HTTP/1.1\r\n"
						+ "Connection: close\r\n"
						+ "\r\n";
				out.write(request.getBytes(StandardCharsets.UTF_8));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for missing Host header");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void malformedRequest_invalidHost_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				String request = "GET /hello HTTP/1.1\r\n"
						+ "Host: 127.0.0.1:99999\r\n"
						+ "Connection: close\r\n"
						+ "\r\n";
				out.write(request.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for invalid Host header");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void malformedRequest_invalidHttpVersion_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				String request = "GET /hello HTTP/2.0\r\n"
						+ "Host: 127.0.0.1\r\n"
						+ "Connection: close\r\n"
						+ "\r\n";
				out.write(request.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for unsupported HTTP version");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void malformedRequest_expect100Continue_returns400() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream()) {
				socket.setSoTimeout(3000);
				String request = "POST /hello HTTP/1.1\r\n"
						+ "Host: 127.0.0.1\r\n"
						+ "Expect: 100-continue\r\n"
						+ "Content-Length: 5\r\n"
						+ "\r\n";
				out.write(request.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for Expect: 100-continue");
				Assertions.assertEquals("close", response.headers().get("connection"));
			}
		}
	}

	@Test
	public void maximumConnections_rejectsExcessConnections() throws Exception {
		int port = findFreePort();
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.maximumConnections(1)
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(EchoResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();

			try (Socket socket1 = connectWithRetry("127.0.0.1", port, 2000)) {
				socket1.setSoTimeout(3000);
				OutputStream out1 = socket1.getOutputStream();
				out1.write(("GET /hello HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
				out1.write(("Host: 127.0.0.1\r\n").getBytes(StandardCharsets.UTF_8));
				out1.write("Connection: keep-alive\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out1.flush();

				RawResponse response1 = readResponse(socket1.getInputStream());
				Assertions.assertTrue(response1.statusLine().startsWith("HTTP/1.1 200"), "Expected first connection to succeed");

				try (Socket socket2 = connectWithRetry("127.0.0.1", port, 2000)) {
					socket2.setSoTimeout(2000);
					int b = socket2.getInputStream().read();
					Assertions.assertEquals(-1, b, "Expected second connection to be closed when max connections reached");
				}
			}
		}
	}

	private static String readLineCRLF(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
		int prev = -1, cur;
		while ((cur = in.read()) != -1) {
			if (prev == '\r' && cur == '\n') break;
			if (prev != -1) buf.write(prev);
			prev = cur;
		}
		if (cur == -1 && prev == -1) return null; // EOF before any bytes
		return buf.toString(StandardCharsets.US_ASCII);
	}

	private static RawResponse readResponse(InputStream in) throws IOException {
		String statusLine = readLineCRLF(in);
		if (statusLine == null)
			throw new EOFException("Unexpected EOF while reading status line");

		Map<String, String> headers = new LinkedHashMap<>();
		String line;

		while ((line = readLineCRLF(in)) != null && !line.isEmpty()) {
			int idx = line.indexOf(':');
			if (idx <= 0)
				continue;
			String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
			String value = line.substring(idx + 1).trim();
			headers.put(name, value);
		}

		int contentLength = 0;
		String contentLengthValue = headers.get("content-length");
		if (contentLengthValue != null && !contentLengthValue.isEmpty())
			contentLength = Integer.parseInt(contentLengthValue);

		byte[] body = readN(in, contentLength);
		return new RawResponse(statusLine, headers, body);
	}

	private static byte[] readN(InputStream in, int n) throws IOException {
		if (n == 0)
			return new byte[0];

		byte[] out = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(out, off, n - off);
			if (r == -1)
				throw new EOFException("short read");
			off += r;
		}
		return out;
	}

	private record RawResponse(String statusLine, Map<String, String> headers, byte[] body) {}

	@Test
	public void queryDecoding_plusAndPercent() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// q=hello+world -> "hello+world"
			URL u1 = new URL("http://127.0.0.1:" + port + "/q?q=hello+world");
			HttpURLConnection c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c1.getResponseCode());
			Assertions.assertEquals("hello+world", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

			// q=a%2Bb -> "a+b"
			URL u2 = new URL("http://127.0.0.1:" + port + "/q?q=a%2Bb");
			HttpURLConnection c2 = open("GET", u2, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c2.getResponseCode());
			Assertions.assertEquals("a+b", new String(readAll(c2.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void pathDecoding_plusIsLiteral_andPercentPlusDecodes() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// Literal '+' must be preserved in PATH
			URL u1 = new URL("http://127.0.0.1:" + port + "/files/foo+bar");
			HttpURLConnection c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c1.getResponseCode());
			Assertions.assertEquals("foo+bar", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

			// Percent-encoded '+' should decode to '+'
			URL u2 = new URL("http://127.0.0.1:" + port + "/files/a%2Bb");
			HttpURLConnection c2 = open("GET", u2, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c2.getResponseCode());
			Assertions.assertEquals("a+b", new String(readAll(c2.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void pathDecoding_encodedSlashIsRejected() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				OutputStream out = socket.getOutputStream();
				out.write(("GET /files/a%2Fb HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Host: 127.0.0.1:" + port + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Accept: text/plain\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 400"), "Expected 400 for encoded slash in path");
			}
		}
	}

	@Test
	public void pathNormalization_dotSegments() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// We expect /files/a/b/../c -> "a/c" after normalization
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000)) {
				socket.setSoTimeout(3000);
				OutputStream out = socket.getOutputStream();
				out.write(("GET /files/a/b/../c HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Host: 127.0.0.1:" + port + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Accept: text/plain\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.flush();

				RawResponse response = readResponse(socket.getInputStream());
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 200"), "Expected 200 OK");
				Assertions.assertEquals("a/c", new String(response.body(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	public void cookies_caseSensitiveNames() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			URL u = new URL("http://127.0.0.1:" + port + "/cookie-echo");
			HttpURLConnection c = open("GET", u, Map.of(
					"Accept", "text/plain",
					"Cookie", "SID=1; sid=2"
			));
			Assertions.assertEquals(200, c.getResponseCode());
			String names = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			// Expect both names to be distinct and preserved
			Set<String> set = Arrays.stream(names.split(",")).map(String::trim).collect(Collectors.toSet());
			Assertions.assertTrue(set.contains("SID"));
			Assertions.assertTrue(set.contains("sid"));
		}
	}

	@ThreadSafe
	public static class Echo2Resource {
		@GET("/hello")
		public String hello() {return "hello";}

		@GET("/q")
		public String echoQuery(@NonNull @QueryParameter String q) {return q;}

		@GET("/files/{path*}")
		public String echoVarargs(@NonNull @PathParameter String path) {return path;}

		@POST("/len")
		public String len(@NonNull Request request) {
			byte[] b = request.getBody().orElse(new byte[0]);
			return Integer.toString(b.length);
		}

		@GET("/cookie-value")
		public String cookieValue(@NonNull Request request, @NonNull @QueryParameter String name) {
			Map<String, Set<String>> cookies = request.getCookies();
			Set<String> values = cookies.getOrDefault(name, Collections.emptySet());
			return values.stream().sorted().collect(Collectors.joining("|"));
		}
	}

	@ThreadSafe
	public static class BlockingResource {
		@GET("/block")
		public String block() {
			try {
				Thread.sleep(10_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "late";
		}
	}

	@ThreadSafe
	public static class QueueBlockingResource {
		private static final AtomicInteger startedCount = new AtomicInteger(0);
		private static volatile CountDownLatch firstStartedLatch = new CountDownLatch(1);
		private static volatile CountDownLatch releaseFirstLatch = new CountDownLatch(1);

		static void reset() {
			startedCount.set(0);
			firstStartedLatch = new CountDownLatch(1);
			releaseFirstLatch = new CountDownLatch(1);
		}

		static boolean awaitFirstStarted(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
			return firstStartedLatch.await(timeout, unit);
		}

		static void releaseFirst() {
			releaseFirstLatch.countDown();
		}

		@GET("/queue-block")
		public String block() {
			if (startedCount.incrementAndGet() == 1) {
				firstStartedLatch.countDown();
				try {
					releaseFirstLatch.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}

			return "ok";
		}
	}

	@ThreadSafe
	public static class UsersResource {
		@GET("/users/me")
		public String me() {return "literal";}

		@GET("/users/{id}")
		public String byId(@NonNull @PathParameter String id) {return "param:" + id;}
	}

	@Test
	public void routing_literalBeatsParam() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(UsersResource.class))) {
			var u1 = new URL("http://127.0.0.1:" + port + "/users/me");
			var c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c1.getResponseCode());
			Assertions.assertEquals("literal", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

			var u2 = new URL("http://127.0.0.1:" + port + "/users/123");
			var c2 = open("GET", u2, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c2.getResponseCode());
			Assertions.assertEquals("param:123", new String(readAll(c2.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void headSemantics_noBody_contentLengthPresent() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			URL u = new URL("http://127.0.0.1:" + port + "/hello");
			HttpURLConnection c = open("HEAD", u, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c.getResponseCode());
			String cl = c.getHeaderField("Content-Length");
			Assertions.assertEquals("5", cl); // "hello"
			byte[] b = readAll(c.getInputStream());
			Assertions.assertEquals(0, b.length);
		}
	}

	@Test
	public void chunkedExtensionsAndTrailers_areConsumed() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream();
					 InputStream in = socket.getInputStream()) {
				socket.setSoTimeout(4000);

				out.write((
						"POST /len HTTP/1.1\r\n" +
								"Host: 127.0.0.1\r\n" +
								"Transfer-Encoding: chunked\r\n" +
								"\r\n" +
								"4;ext=1\r\n" +
								"ABCD\r\n" +
								"0\r\n" +
								"X-Trailer: value\r\n" +
								"\r\n" +
								"GET /hello HTTP/1.1\r\n" +
								"Host: 127.0.0.1\r\n" +
								"Connection: close\r\n" +
								"\r\n"
				).getBytes(StandardCharsets.UTF_8));
				out.flush();

				RawResponse first = readResponse(in);
				Assertions.assertTrue(first.statusLine().startsWith("HTTP/1.1 200"));
				Assertions.assertEquals("4", new String(first.body(), StandardCharsets.UTF_8));

				RawResponse second = readResponse(in);
				Assertions.assertTrue(second.statusLine().startsWith("HTTP/1.1 200"));
				Assertions.assertEquals("hello", new String(second.body(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	public void requestHandlerTimeout_closesConnection() throws Exception {
		int port = findFreePort();

		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port)
						.requestTimeout(Duration.ofSeconds(5))
						.requestHandlerTimeout(Duration.ofMillis(200))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(BlockingResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();

			try (Socket socket = connectWithRetry("127.0.0.1", port, 2000);
					 OutputStream out = socket.getOutputStream();
					 InputStream in = socket.getInputStream()) {
				socket.setSoTimeout(2000);
				out.write((
						"GET /block HTTP/1.1\r\n" +
								"Host: 127.0.0.1\r\n" +
								"Connection: keep-alive\r\n" +
								"\r\n"
				).getBytes(StandardCharsets.UTF_8));
				out.flush();

				RawResponse response = readResponse(in);
				Assertions.assertTrue(response.statusLine().startsWith("HTTP/1.1 503"));
				String connection = response.headers().get("connection");
				Assertions.assertNotNull(connection);
				Assertions.assertTrue(connection.toLowerCase(Locale.ROOT).contains("close"));

				try {
					int next = in.read();
					Assertions.assertEquals(-1, next, "Connection did not close after timeout response");
				} catch (SocketTimeoutException e) {
					Assertions.fail("Connection did not close after timeout response");
				}
			}
		}
	}

	@Test
	public void requestHandlerQueueCapacity_rejectsWhenFull() throws Exception {
		int port = findFreePort();
		QueueBlockingResource.reset();

		Server server = Server.withPort(port)
						.concurrency(1)
						.requestHandlerConcurrency(1)
						.requestHandlerQueueCapacity(1)
						.requestHandlerTimeout(Duration.ofSeconds(5))
						.build();

		SokletConfig cfg = SokletConfig.withServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(QueueBlockingResource.class)))
				.lifecycleObserver(new QuietLifecycle())
				.build();

		try (Soklet app = Soklet.fromConfig(cfg)) {
			app.start();

			ExecutorService requestExecutor = ((DefaultServer) server).getRequestHandlerExecutorService().orElse(null);
			if (requestExecutor instanceof ThreadPoolExecutor threadPoolExecutor) {
				threadPoolExecutor.prestartAllCoreThreads();
			}

			Socket socket1 = connectWithRetry("127.0.0.1", port, 2000);
			socket1.setSoTimeout(4000);
			OutputStream out1 = socket1.getOutputStream();
			out1.write((
					"GET /queue-block HTTP/1.1\r\n" +
							"Host: 127.0.0.1\r\n" +
							"Connection: close\r\n" +
							"\r\n"
			).getBytes(StandardCharsets.UTF_8));
			out1.flush();

			Assertions.assertTrue(QueueBlockingResource.awaitFirstStarted(5, TimeUnit.SECONDS),
					"Expected first request to enter handler");

			Socket socket2 = connectWithRetry("127.0.0.1", port, 2000);
			socket2.setSoTimeout(4000);
			OutputStream out2 = socket2.getOutputStream();
			out2.write((
					"GET /queue-block HTTP/1.1\r\n" +
							"Host: 127.0.0.1\r\n" +
							"Connection: close\r\n" +
							"\r\n"
			).getBytes(StandardCharsets.UTF_8));
			out2.flush();

			Thread.sleep(200);

			try (Socket socket3 = connectWithRetry("127.0.0.1", port, 2000);
					 InputStream in3 = socket3.getInputStream();
					 OutputStream out3 = socket3.getOutputStream()) {
				socket3.setSoTimeout(4000);
				out3.write((
						"GET /queue-block HTTP/1.1\r\n" +
								"Host: 127.0.0.1\r\n" +
								"Connection: close\r\n" +
								"\r\n"
				).getBytes(StandardCharsets.UTF_8));
				out3.flush();

				RawResponse response3 = readResponse(in3);
				Assertions.assertTrue(response3.statusLine().startsWith("HTTP/1.1 503"),
						"Expected 503 for full request handler queue");
			} finally {
				QueueBlockingResource.releaseFirst();
			}

			RawResponse response1 = readResponse(socket1.getInputStream());
			Assertions.assertTrue(response1.statusLine().startsWith("HTTP/1.1 200"));

			RawResponse response2 = readResponse(socket2.getInputStream());
			Assertions.assertTrue(response2.statusLine().startsWith("HTTP/1.1 200"));

			socket1.close();
			socket2.close();
		}
	}

	@Test
	public void methodNotAllowed_405_includesAllow() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(UsersResource.class))) {
			URL u = new URL("http://127.0.0.1:" + port + "/users/123");
			HttpURLConnection c = open("POST", u, Map.of("Accept", "text/plain"));
			c.getOutputStream().write("x".getBytes(StandardCharsets.UTF_8));
			int code = c.getResponseCode();
			Assertions.assertEquals(405, code);
			List<String> allow = c.getHeaderFields().get("Allow");
			Assertions.assertNotNull(allow);
			Assertions.assertTrue(allow.contains("GET"));
		}
	}

	@Test
	public void methodNotAllowed_405_excludesHeadWithoutGet() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			URL u = new URL("http://127.0.0.1:" + port + "/len");
			HttpURLConnection c = open("GET", u, Map.of("Accept", "text/plain"));
			int code = c.getResponseCode();
			Assertions.assertEquals(405, code);
			List<String> allow = c.getHeaderFields().get("Allow");
			Assertions.assertNotNull(allow);
			Assertions.assertTrue(allow.contains("POST"));
			Assertions.assertTrue(allow.contains("OPTIONS"));
			Assertions.assertFalse(allow.contains("HEAD"));
		}
	}

	@Test
	public void cookies_quotedValueAndMultipleHeaders() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			URL u = new URL("http://127.0.0.1:" + port + "/cookie-value?name=flavor");
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Accept", "text/plain");
			// Send two Cookie headers; include a quoted value with a space
			headers.put("Cookie", "flavor=\"choc chip\"");
			HttpURLConnection c = open("GET", u, headers);
			// And a second Cookie header with another value of same name
			c.addRequestProperty("Cookie", "flavor=vanilla");
			Assertions.assertEquals(200, c.getResponseCode());
			String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			// Order is not guaranteed; ensure both values are visible
			Set<String> vals = Arrays.stream(body.split("\\|")).collect(Collectors.toSet());
			Assertions.assertTrue(vals.contains("choc chip"));
			Assertions.assertTrue(vals.contains("vanilla"));
		}
	}

	@Test
	public void pathDecoding_plusLiteral_queryPlusIsNotSpace() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			URL upath = new URL("http://127.0.0.1:" + port + "/files/a+b");
			HttpURLConnection cp = open("GET", upath, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, cp.getResponseCode());
			Assertions.assertEquals("a+b", new String(readAll(cp.getInputStream()), StandardCharsets.UTF_8));

			URL uquery = new URL("http://127.0.0.1:" + port + "/q?q=a+b");
			HttpURLConnection cq = open("GET", uquery, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, cq.getResponseCode());
			Assertions.assertEquals("a+b", new String(readAll(cq.getInputStream()), StandardCharsets.UTF_8));
		}
	}
}
