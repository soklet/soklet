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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class RequestTextDecodingTransportTests {
	@Test
	void malformedClientTextReceivesRedacted400AndValidUnicodeAndRawBytesRemainUsable() throws Exception {
		int port = TestSupport.findFreePort();
		try (Soklet soklet = Soklet.fromConfig(config(port))) {
			soklet.start();
			assertRejected(exchange(port, "GET", "/text/secret%FF", "", new byte[0]));
			assertRejected(exchange(port, "GET", "/query?selected=ok&secret=%FF", "", new byte[0]));
			assertRejected(exchange(port, "GET", "/cookie", "Cookie: secret=%FF\r\n", new byte[0]));
			assertRejected(exchange(port, "POST", "/body", "Content-Type: text/plain; charset=UTF-8\r\n", new byte[]{(byte) 0xff}));
			assertRejected(exchange(port, "POST", "/form", "Content-Type: application/x-www-form-urlencoded\r\n", "secret=%FF".getBytes(StandardCharsets.US_ASCII)));
			assertRejected(exchange(port, "POST", "/form", "Content-Type: application/x-www-form-urlencoded\r\n", new byte[]{'s', 'e', 'c', 'r', 'e', 't', '=', (byte) 0xff}));
			byte[] multipart = ("--test\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\n"
					+ "ÿ\r\n--test--\r\n").getBytes(StandardCharsets.ISO_8859_1);
			assertRejected(exchange(port, "POST", "/multipart", "Content-Type: multipart/form-data; boundary=test\r\n", multipart));
			Response valid = exchange(port, "GET", "/text/%C3%A9%F0%9F%8D%AA%EF%BF%BD", "", new byte[0]);
			assertEquals(200, valid.statusCode());
			assertEquals("/text/é🍪\uFFFD", valid.body());
			Response binary = exchange(port, "POST", "/bytes", "", new byte[]{(byte) 0xff});
			assertEquals(200, binary.statusCode());
			assertEquals("1", binary.body());
		}
	}

	@Test
	void originFormLeadingSlashesCannotSelectARouteAfterDroppingAComponent() throws Exception {
		int port = TestSupport.findFreePort();
		try (Soklet soklet = Soklet.fromConfig(config(port))) {
			soklet.start();
			Response response = exchange(port, "GET", "//x/admin?selected=ok", "", new byte[0]);
			assertEquals(200, response.statusCode());
			assertEquals("//x/admin?selected=ok|/x/admin|ok", response.body());
			Response repeated = exchange(port, "GET", "///x//admin/?selected=ok", "", new byte[0]);
			assertEquals(200, repeated.statusCode());
			assertEquals("///x//admin/?selected=ok|/x/admin|ok", repeated.body());
			Response root = exchange(port, "GET", "//?selected=ok", "", new byte[0]);
			assertEquals(200, root.statusCode());
			assertEquals("//?selected=ok|/|ok", root.body());
			assertRejected(exchange(port, "GET", "//x%2Fadmin", "", new byte[0]));
		}
	}

	@Test
	void formLiteralsAndBlankOptionalBindingSurviveRealHttpParsing() throws Exception {
		int port = TestSupport.findFreePort();
		String headers = "Content-Type: application/x-www-form-urlencoded\r\n";
		try (Soklet soklet = Soklet.fromConfig(config(port))) {
			soklet.start();
			String form = "id= one#two \"{}|\\?=+%2B%26%3D&id=last&tail=three";
			Response literal = exchange(port, "POST", "/form-pairs", headers, form.getBytes(StandardCharsets.US_ASCII));
			assertEquals(200, literal.statusCode());
			assertEquals(" one#two \"{}|\\?= +&=|last;three", literal.body());
			Response bound = exchange(port, "POST", "/bound-form-pairs", headers, form.getBytes(StandardCharsets.US_ASCII));
			assertEquals(200, bound.statusCode());
			assertEquals("one#two \"{}|\\?= +&=|last;three", bound.body());
			for (String blank : List.of("", "id", "id=", "id= ", "id=+")) {
				Response response = exchange(port, "POST", "/optional-form", headers, blank.getBytes(StandardCharsets.US_ASCII));
				assertEquals(200, response.statusCode(), blank);
				assertEquals("absent", response.body(), blank);
			}
			for (String repeated : List.of("id&id", "id=&id=", "id=+&id=%20"))
				assertEquals(400, exchange(port, "POST", "/optional-form", headers, repeated.getBytes(StandardCharsets.US_ASCII)).statusCode(), repeated);
			assertRejected(exchange(port, "POST", "/form", headers, "secret=ok#tail&unselected=%FF".getBytes(StandardCharsets.US_ASCII)));
		}
	}

	@Test
	void realHttpUsesLateCharsetParametersAndRejectsAmbiguousCharsets() throws Exception {
		int port = TestSupport.findFreePort();
		try (Soklet soklet = Soklet.fromConfig(config(port))) {
			soklet.start();
			Response body = exchange(port, "POST", "/body", "Content-Type: text/plain; profile=\"x;y\"; charset=ISO-8859-1\r\n", new byte[]{(byte) 0xff});
			assertEquals(200, body.statusCode());
			assertEquals("ÿ", body.body());
			for (String form : List.of("secret=ÿ", "secret=%FF")) {
				Response response = exchange(port, "POST", "/form", "Content-Type: application/x-www-form-urlencoded; profile=test; charset=ISO-8859-1\r\n",
						form.getBytes(StandardCharsets.ISO_8859_1));
				assertEquals(200, response.statusCode());
				assertEquals("ÿ", response.body());
			}
			for (String charsets : List.of("charset=UTF-8; charset=UTF-8", "charset=UTF-8; charset=ISO-8859-1"))
				assertRejected(exchange(port, "POST", "/body", "Content-Type: text/plain; " + charsets + "; secret=value\r\n", new byte[0]));
		}
	}

	private static SokletConfig config(int port) {
		return SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TextResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
				}).build();
	}

	private static void assertRejected(Response response) {
		assertEquals(400, response.statusCode());
		assertFalse(response.body().contains("secret"));
		assertFalse(response.body().contains("%FF"));
		assertFalse(response.body().contains("MalformedInputException"));
	}

	private static Response exchange(int port, String method, String target, String headers, byte[] body) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
			socket.setSoTimeout(1000);
			socket.getOutputStream().write((method + " " + target + " HTTP/1.1\r\nHost: localhost\r\n"
					+ "Connection: close\r\nContent-Length: " + body.length + "\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().write(body);
			socket.getOutputStream().flush();
			byte[] bytes = socket.getInputStream().readNBytes(16384);
			if (bytes.length == 16384) throw new IOException("Test response exceeded its bound.");
			String wire = new String(bytes, StandardCharsets.UTF_8);
			int statusCode = Integer.parseInt(wire.substring(wire.indexOf(' ') + 1, wire.indexOf(' ') + 4));
			return new Response(statusCode, wire.substring(wire.indexOf("\r\n\r\n") + 4));
		}
	}

	private record Response(int statusCode, String body) {}

	public static class TextResource {
		@GET("/x/admin") public String preservedPath(Request request) { return pathAndQuery(request); }
		@GET("/admin") public String admin() { return "wrong-route"; }
		@GET("/") public String root(Request request) { return pathAndQuery(request); }
		@GET("/text/{id}") public String text(Request request) { return request.getPath(); }
		@GET("/query") public String query(Request request) { return request.getQueryParameter("selected").orElse(""); }
		@GET("/cookie") public String cookie(Request request) { return request.getCookie("secret").orElse(""); }
		@POST("/body") public String body(Request request) { return request.getBodyAsString().orElse(""); }
		@POST("/form") public String form(Request request) { return request.getFormParameter("secret").orElse(""); }
		@POST("/form-pairs") public String formPairs(Request request) { return String.join("|", request.getFormParameters().get("id")) + ";" + request.getFormParameter("tail").orElseThrow(); }
		@POST("/bound-form-pairs") public String boundFormPairs(@FormParameter(name="id") List<String> ids, @FormParameter(name="tail") String tail) { return String.join("|", ids) + ";" + tail; }
		@POST("/optional-form") public String optionalForm(@FormParameter(name="id") Optional<String> id) { return id.orElse("absent"); }
		@POST("/multipart") public String multipart(Request request) { return request.getMultipartFields().get("field").iterator().next().getDataAsString().orElse(""); }
		@POST("/bytes") public String bytes(Request request) { return Integer.toString(request.getBody().orElseThrow().length); }
		private static String pathAndQuery(Request request) { return request.getRawPathAndQuery() + "|" + request.getPath() + "|" + request.getQueryParameter("selected").orElse(""); }
	}
}
