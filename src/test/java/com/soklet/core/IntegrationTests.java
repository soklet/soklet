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
import com.soklet.annotation.GET;
import com.soklet.annotation.PathParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.core.impl.DefaultResourceMethodResolver;
import com.soklet.core.impl.DefaultServer;
import org.junit.Assert;
import org.junit.Test;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
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

	private static Soklet startApp(int port) {
		SokletConfiguration cfg = SokletConfiguration.withServer(DefaultServer.withPort(port).requestTimeout(Duration.ofSeconds(5)).build())
				.resourceMethodResolver(new DefaultResourceMethodResolver(Set.of(EchoResource.class)))
				.lifecycleInterceptor(new QuietLifecycle())
				.build();
		Soklet app = new Soklet(cfg);
		app.start();
		return app;
	}

	@Test
	public void varargs_pathRoundTrip_overNetwork() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port)) {
			// Basic varargs round-trip (no decoding assertions here)
			URL url = new URL("http://127.0.0.1:" + port + "/files/js/some/file/example.js");
			HttpURLConnection c = open("GET", url, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c.getResponseCode());
			String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			Assert.assertEquals("js/some/file/example.js", body);
		}
	}

	@Test
	public void queryDecoding_plusAndPercent() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port)) {
			// q=hello+world -> "hello world"
			URL u1 = new URL("http://127.0.0.1:" + port + "/q?q=hello+world");
			HttpURLConnection c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c1.getResponseCode());
			Assert.assertEquals("hello world", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

			// q=a%2Bb -> "a+b"
			URL u2 = new URL("http://127.0.0.1:" + port + "/q?q=a%2Bb");
			HttpURLConnection c2 = open("GET", u2, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c2.getResponseCode());
			Assert.assertEquals("a+b", new String(readAll(c2.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void pathDecoding_plusIsLiteral_andPercentPlusDecodes() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port)) {
			// Literal '+' must be preserved in PATH
			URL u1 = new URL("http://127.0.0.1:" + port + "/files/foo+bar");
			HttpURLConnection c1 = open("GET", u1, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c1.getResponseCode());
			Assert.assertEquals("foo+bar", new String(readAll(c1.getInputStream()), StandardCharsets.UTF_8));

			// Percent-encoded '+' should decode to '+'
			URL u2 = new URL("http://127.0.0.1:" + port + "/files/a%2Bb");
			HttpURLConnection c2 = open("GET", u2, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c2.getResponseCode());
			Assert.assertEquals("a+b", new String(readAll(c2.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void pathNormalization_dotSegments() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port)) {
			// We expect /files/a/b/../c -> "a/c" after normalization
			URL u = new URL("http://127.0.0.1:" + port + "/files/a/b/../c");
			HttpURLConnection c = open("GET", u, Map.of("Accept", "text/plain"));
			Assert.assertEquals(200, c.getResponseCode());
			Assert.assertEquals("a/c", new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void cookies_caseSensitiveNames() throws Exception {
		int port = findFreePort();
		try (Soklet app = startApp(port)) {
			URL u = new URL("http://127.0.0.1:" + port + "/cookie-echo");
			HttpURLConnection c = open("GET", u, Map.of(
					"Accept", "text/plain",
					"Cookie", "SID=1; sid=2"
			));
			Assert.assertEquals(200, c.getResponseCode());
			String names = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			// Expect both names to be distinct and preserved
			Set<String> set = Arrays.stream(names.split(",")).map(String::trim).collect(Collectors.toSet());
			Assert.assertTrue(set.contains("SID"));
			Assert.assertTrue(set.contains("sid"));
		}
	}
}
