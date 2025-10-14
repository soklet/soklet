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

import com.soklet.annotation.GET;
import com.soklet.annotation.POST;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
		public String echoQuery(@Nonnull @QueryParameter String q) {
			return q;
		}

		// Varargs echo to observe decoded path
		@GET("/files/{path*}")
		public String echoVarargs(@Nonnull @PathParameter String path) {
			return path;
		}

		// A route that just returns all cookie names it sees
		@GET("/cookie-echo")
		public String cookieEcho(@Nonnull Request request) {
			Map<String, Set<String>> cookies = request.getCookies();
			return cookies.keySet().stream().sorted().collect(Collectors.joining(","));
		}

		@GET("/multivalued-headers")
		public Response multivaluedHeaders(@Nonnull Request request) {
			return Response.withStatusCode(200)
					.headers(Map.of("multi", Set.of("one", "two")))
					.cookies(Set.of(
							ResponseCookie.with("a", "b").build(),
							ResponseCookie.with("a", "c").build()
					))
					.build();
		}
	}

	private static class QuietLifecycle implements LifecycleInterceptor {
		@Override
		public void didReceiveLogEvent(@Nonnull LogEvent logEvent) { /* no-op */ }
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
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

	private static byte[] readAll(InputStream in) throws IOException {
		if (in == null) return new byte[0];
		try (in) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
			return bos.toByteArray();
		}
	}

	private static Soklet startApp(int port, Set<Class<?>> resourceClasses) {
		SokletConfig cfg = SokletConfig.withServer(Server.withPort(port).requestTimeout(Duration.ofSeconds(5)).build())
				.resourceMethodResolver(ResourceMethodResolver.withResourceClasses(resourceClasses))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();
		Soklet app = Soklet.withConfig(cfg);
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

			try (Socket socket = new Socket(host, port);
					 OutputStream out = socket.getOutputStream()) {
				// Send raw HTTP request
				out.write(("GET " + path + " HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
				out.write(("Host: " + host + "\r\n").getBytes(StandardCharsets.UTF_8));
				out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
				out.flush();

				// Read raw response
				try (InputStream in = socket.getInputStream()) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					// Read status line
					String status = readLineCRLF(in);
					Assertions.assertNotNull(status);
					System.out.println(status);

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

	@Test
	public void queryDecoding_plusAndPercent() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// q=hello+world -> "hello world"
			URL u1 = new URL("http://127.0.0.1:" + port + "/q?q=hello+world");
			HttpURLConnection c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c1.getResponseCode());
			Assertions.assertEquals("hello world", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

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
	public void pathNormalization_dotSegments() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(EchoResource.class))) {
			// We expect /files/a/b/../c -> "a/c" after normalization
			URL u = new URL("http://127.0.0.1:" + port + "/files/a/b/../c");
			HttpURLConnection c = open("GET", u, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, c.getResponseCode());
			Assertions.assertEquals("a/c", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
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
		public String echoQuery(@Nonnull @QueryParameter String q) {return q;}

		@GET("/files/{path*}")
		public String echoVarargs(@Nonnull @PathParameter String path) {return path;}

		@POST("/len")
		public String len(@Nonnull Request request) {
			byte[] b = request.getBody().orElse(new byte[0]);
			return Integer.toString(b.length);
		}

		@GET("/cookie-value")
		public String cookieValue(@Nonnull Request request, @Nonnull @QueryParameter String name) {
			Map<String, Set<String>> cookies = request.getCookies();
			Set<String> values = cookies.getOrDefault(name, Collections.emptySet());
			return values.stream().sorted().collect(Collectors.joining("|"));
		}
	}

	@ThreadSafe
	public static class UsersResource {
		@GET("/users/me")
		public String me() {return "literal";}

		@GET("/users/{id}")
		public String byId(@Nonnull @PathParameter String id) {return "param:" + id;}
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
	public void pathDecoding_plusLiteral_queryPlusIsSpace() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port, Set.of(Echo2Resource.class))) {
			URL upath = new URL("http://127.0.0.1:" + port + "/files/a+b");
			HttpURLConnection cp = open("GET", upath, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, cp.getResponseCode());
			Assertions.assertEquals("a+b", new String(readAll(cp.getInputStream()), StandardCharsets.UTF_8));

			URL uquery = new URL("http://127.0.0.1:" + port + "/q?q=a+b");
			HttpURLConnection cq = open("GET", uquery, Map.of("Accept", "text/plain"));
			Assertions.assertEquals(200, cq.getResponseCode());
			Assertions.assertEquals("a b", new String(readAll(cq.getInputStream()), StandardCharsets.UTF_8));
		}
	}
}
