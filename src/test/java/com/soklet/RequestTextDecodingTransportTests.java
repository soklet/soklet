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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class RequestTextDecodingTransportTests {
	@Test
	void malformedClientTextReceivesRedacted400AndValidUnicodeAndRawBytesRemainUsable() throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TextResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(2)).gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
				}).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
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
		@GET("/text/{id}") public String text(Request request) { return request.getPath(); }
		@GET("/query") public String query(Request request) { return request.getQueryParameter("selected").orElse(""); }
		@GET("/cookie") public String cookie(Request request) { return request.getCookie("secret").orElse(""); }
		@POST("/body") public String body(Request request) { return request.getBodyAsString().orElse(""); }
		@POST("/form") public String form(Request request) { return request.getFormParameter("secret").orElse(""); }
		@POST("/multipart") public String multipart(Request request) { return request.getMultipartFields().get("field").iterator().next().getDataAsString().orElse(""); }
		@POST("/bytes") public String bytes(Request request) { return Integer.toString(request.getBody().orElseThrow().length); }
	}
}
