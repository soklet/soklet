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
import com.soklet.annotation.FormParameter;
import com.soklet.annotation.QueryParameter;
import com.soklet.annotation.RequestCookie;
import com.soklet.annotation.RequestHeader;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpValueListTransportTests {
	@Test
	void realHttpPreservesRequestAndResponseOccurrencesAndRejectsRepeatedScalarParameters() throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ValueResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
				}).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			String wire = exchange(port, "GET", "/lists?id=one&id=one&id=two",
					"X-Value: one\r\nx-value: one\r\nX-Value: two\r\nCookie: id=one; id=one; id=two\r\n", "");
			assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
			assertEquals("one,one,two|one,one,two|one,one,two", body(wire));
			assertEquals(List.of("second", "first", "first"), headerValues(wire, "X-Value"));
			assertEquals(List.of("id=one", "id=one"), headerValues(wire, "Set-Cookie"));
			assertTrue(exchange(port, "GET", "/scalar-query?id=one&id=one", "", "").startsWith("HTTP/1.1 400"));
			assertTrue(exchange(port, "GET", "/scalar-header", "X-Value: one\r\nx-value: one\r\n", "").startsWith("HTTP/1.1 400"));
			assertTrue(exchange(port, "GET", "/scalar-cookie", "Cookie: id=one; id=one\r\n", "").startsWith("HTTP/1.1 400"));
			String form = exchange(port, "POST", "/form-lists", "Content-Type: application/x-www-form-urlencoded\r\n", "id=one&id=one&id=two");
			assertEquals("one,one,two", body(form));
			assertTrue(exchange(port, "POST", "/scalar-form", "Content-Type: application/x-www-form-urlencoded\r\n", "id=one&id=one").startsWith("HTTP/1.1 400"));
		}
	}

	private static String exchange(int port, String method, String target, String headers, String body) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
			socket.setSoTimeout(1000);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: "
					+ bytes.length + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().write(bytes);
			socket.getOutputStream().flush();
			byte[] response = socket.getInputStream().readNBytes(16384);
			assertTrue(response.length < 16384, "Response exceeded test bound");
			return new String(response, StandardCharsets.UTF_8);
		}
	}

	private static String body(String wire) { return wire.substring(wire.indexOf("\r\n\r\n") + 4); }
	private static List<String> headerValues(String wire, String name) {
		return Arrays.stream(wire.substring(0, wire.indexOf("\r\n\r\n")).split("\r\n"))
				.filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
				.map(line -> line.substring(name.length() + 1).trim()).toList();
	}

	public static class ValueResource {
		@GET("/lists")
		public Response lists(@QueryParameter(name="id") List<String> ids,
				@RequestHeader(name="X-Value") List<String> values, @RequestCookie(name="id") List<String> cookies) {
			ResponseCookie cookie = ResponseCookie.with("id", "one").build();
			return Response.withStatusCode(200).headers(Map.of("X-Value", List.of("second", "first", "first")))
					.cookies(List.of(cookie, cookie)).body(String.join(",", ids) + "|" + String.join(",", values) + "|" + String.join(",", cookies)).build();
		}
		@GET("/scalar-query") public String query(@QueryParameter(name="id") String id) { return id; }
		@GET("/scalar-header") public String header(@RequestHeader(name="X-Value") String value) { return value; }
		@GET("/scalar-cookie") public String cookie(@RequestCookie(name="id") String id) { return id; }
		@POST("/form-lists") public String formLists(@FormParameter(name="id") List<String> ids) { return String.join(",", ids); }
		@POST("/scalar-form") public String form(@FormParameter(name="id") String id) { return id; }
	}
}
