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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HttpValueListTransportTests {
	@Test
	void realHttpPreservesRequestAndResponseOccurrencesAndRejectsRepeatedScalarParameters() throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = config(port);
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

	@Test
	void singleCommaFieldsAndRepeatedBlankFieldsAgreeBetweenHttpAndSimulation() throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = config(port);
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			SokletSimulator.run(config, simulator -> {
				for (Map.Entry<String, String> entry : Map.of("Accept", "text/plain, application/json", "Accept-Encoding", "gzip, deflate, br",
						"Accept-Language", "en-US,en;q=0.9", "Warning", "199 example \"quoted,comma\", 299 example \"other\"", "X-Empty", "").entrySet()) {
					String target = "/scalar-field?name=" + entry.getKey();
					String value = entry.getValue();
					String wire = exchange(port, "GET", target, entry.getKey() + ": \t" + value + " \t\r\n", "");
					assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
					assertEquals("field:" + value, body(wire));
					MarshaledResponse simulated = simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/scalar-field")
							.queryParameters(Map.of("name", List.of(entry.getKey()))).headers(Map.of(entry.getKey(), List.of(" \t" + value + " \t"))).build()).getMarshaledResponse();
					assertEquals(200, simulated.getStatusCode().intValue());
					assertEquals(body(wire), new String(simulated.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				}
				String encoding = "gzip, deflate, br";
				String bound = exchange(port, "GET", "/bound-header", "Accept-Encoding: " + encoding + "\r\n", "");
				assertTrue(bound.startsWith("HTTP/1.1 200"), bound);
				assertEquals(encoding, body(bound));
				assertEquals(encoding, new String(simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/bound-header")
						.headers(Map.of("Accept-Encoding", List.of(encoding))).build()).getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				String languages = exchange(port, "GET", "/header-list", "Accept-Language: en-US,en;q=0.9\r\naccept-language: fr,de;q=0.5\r\n", "");
				assertTrue(languages.startsWith("HTTP/1.1 200"), languages);
				assertEquals("en-US,en;q=0.9|fr,de;q=0.5", body(languages));
				String repeatedEncoding = exchange(port, "GET", "/bound-header", "Accept-Encoding: " + encoding + "\r\naccept-encoding: " + encoding + "\r\n", "");
				assertTrue(repeatedEncoding.startsWith("HTTP/1.1 400"), repeatedEncoding);
				assertEquals(400, simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/bound-header")
						.headers(Map.of("Accept-Encoding", List.of(encoding, encoding))).build()).getMarshaledResponse().getStatusCode().intValue());
				for (List<String> values : List.of(List.<String>of(), List.of(""), List.of("\t "))) {
					String headers = values.isEmpty() ? "" : "X-Blank: " + values.get(0) + "\r\n";
					String wire = exchange(port, "GET", "/optional-header", headers, "");
					assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
					assertEquals("absent", body(wire));
					assertEquals("absent", new String(simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/optional-header")
							.headers(Map.of("X-Blank", values)).build()).getMarshaledResponse().bodyBytesOrEmpty(), StandardCharsets.UTF_8));
				}
				for (List<String> values : List.of(List.of("", ""), List.of("", "value"), List.of("value", "value"))) {
					String wire = exchange(port, "GET", "/optional-header", "X-Blank: " + values.get(0) + "\r\nx-blank: " + values.get(1) + "\r\n", "");
					assertTrue(wire.startsWith("HTTP/1.1 400"), wire);
					assertEquals(400, simulator.performHttpRequest(Request.withPath(HttpMethod.GET, "/optional-header")
							.headers(Map.of("X-Blank", values)).build()).getMarshaledResponse().getStatusCode().intValue());
				}
			});
		}
	}

	private static SokletConfig config(int port) {
		return SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ValueResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
				}).build();
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
		@GET("/scalar-field") public String field(Request request, @QueryParameter(name="name") String name) { return "field:" + request.getHeader(name).orElse("missing"); }
		@GET("/bound-header") public String boundHeader(@RequestHeader(name="Accept-Encoding") String value) { return value; }
		@GET("/header-list") public String headerList(@RequestHeader(name="Accept-Language") List<String> values) { return String.join("|", values); }
		@GET("/optional-header") public String optionalHeader(@RequestHeader(name="X-Blank") Optional<String> value) { return value.orElse("absent"); }
		@GET("/scalar-cookie") public String cookie(@RequestCookie(name="id") String id) { return id; }
		@POST("/form-lists") public String formLists(@FormParameter(name="id") List<String> ids) { return String.join(",", ids); }
		@POST("/scalar-form") public String form(@FormParameter(name="id") String id) { return id; }
	}
}
