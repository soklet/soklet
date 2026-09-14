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
import com.soklet.annotation.SseEventSource;
import com.soklet.internal.spring.LinkedCaseInsensitiveMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

class HeaderLocaleTests {
	private static final String COOKIE = "session=locale; Max-Age=3600";
	private static final String REJECTION_BODY = "denied-\u2603";
	private static final byte[] REJECTION_BYTES = REJECTION_BODY.getBytes(StandardCharsets.UTF_8);
	private static final Instant FILE_MODIFIED = Instant.ofEpochSecond(1_757_800_000L);
	private static final String FILE_ENTITY_TAG = "W/\"mtime-1757800000-size-6\"";

	@Test
	@Timeout(150)
	void protocolHeaderNormalizationIsIndependentOfJvmLocale() throws Exception {
		String classpath = System.getProperty("surefire.test.class.path",
				System.getProperty("java.class.path"));
		// Five isolated probes have 25 seconds each plus at most two seconds of
		// forced cleanup. The outer 150-second guard includes a 15-second margin.
		Assertions.assertAll(List.of("tr", "az", "ar", "fa", "en").stream()
				.map(language -> (Executable) () -> runLocaleProbe(classpath, language)));
	}

	private static void runLocaleProbe(@NonNull String classpath, @NonNull String language) throws Exception {
		String country = language.equals("ar") ? "EG" : language.equals("fa") ? "IR" : "";
		Path outputFile = Files.createTempFile("soklet-locale-" + language + "-", ".log");
		try {
			Process process = new ProcessBuilder(
					Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-Duser.language=" + language, "-Duser.country=" + country,
					"-Duser.language.format=" + language, "-Duser.country.format=" + country, "-cp", classpath,
					HeaderLocaleTests.class.getName(), language)
					.redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
			try {
				Assertions.assertTrue(process.waitFor(25, TimeUnit.SECONDS),
						"Locale probe timed out: " + language);
				Assertions.assertTrue(Files.size(outputFile) <= 262_144, "Locale probe output exceeded its bound");
				String output = Files.readString(outputFile, StandardCharsets.UTF_8);
				Assertions.assertEquals(0, process.exitValue(), language + ": " + output);
			} finally {
				process.destroyForcibly();
				Assertions.assertTrue(process.waitFor(2, TimeUnit.SECONDS),
						"Locale probe failed to terminate: " + language);
			}
		} finally {
			Files.deleteIfExists(outputFile);
		}
	}

	public static void main(@NonNull String[] args) throws Exception {
		Assertions.assertEquals(args[0], Locale.getDefault().getLanguage());
		Assertions.assertEquals(args[0], Locale.getDefault(Locale.Category.FORMAT).getLanguage());
		String country = args[0].equals("ar") ? "EG" : args[0].equals("fa") ? "IR" : "";
		Assertions.assertEquals(country, Locale.getDefault(Locale.Category.FORMAT).getCountry());
		if (Set.of("ar", "fa").contains(args[0]))
			Assertions.assertNotEquals('0', DecimalFormatSymbols.getInstance().getZeroDigit(),
					"Numeric-locale probe must actually exercise non-Latin decimal digits");
		assertHeaderNormalization();
		Path directory = Files.createTempDirectory("soklet-locale-file-");
		Path file = directory.resolve("example.txt");
		try {
			Files.writeString(file, "abcdef", StandardCharsets.UTF_8);
			Files.setLastModifiedTime(file, FileTime.from(FILE_MODIFIED));
			LocaleResource.file = file;
			LocaleResource.staticFiles = StaticFiles.withRoot(directory).build();
			Assertions.assertAll("Protocol output under " + args[0],
					() -> assertFileValues(file),
					HeaderLocaleTests::assertCookieValues,
					HeaderLocaleTests::assertHttpWire,
					() -> {
						if (Runtime.version().feature() >= 21)
							assertSseWire();
					});
		} finally {
			LocaleResource.file = null;
			LocaleResource.staticFiles = null;
			Files.deleteIfExists(file);
			Files.deleteIfExists(directory);
		}
	}

	private static void assertHeaderNormalization() {
		LinkedCaseInsensitiveMap<String> headers = new LinkedCaseInsensitiveMap<>();
		headers.put("if-none-match", "first");
		Assertions.assertEquals("first", headers.get("If-None-Match"));
		Assertions.assertEquals("first", headers.put("IF-NONE-MATCH", "second"));
		Assertions.assertEquals(1, headers.size());
		Assertions.assertEquals("second", headers.clone().get("if-none-match"));
		Assertions.assertEquals("second", new LinkedCaseInsensitiveMap<>(headers).get("If-None-Match"));
		Assertions.assertEquals("second", headers.remove("If-None-Match"));
		Assertions.assertTrue(headers.isEmpty());
		Assertions.assertEquals(Locale.ROOT, new LinkedCaseInsensitiveMap<>(4).getLocale());
		Assertions.assertEquals(Locale.ROOT, new LinkedCaseInsensitiveMap<String>((Locale) null).getLocale());

		Locale turkish = Locale.forLanguageTag("tr");
		LinkedCaseInsensitiveMap<String> explicitLocale = new LinkedCaseInsensitiveMap<>(turkish);
		explicitLocale.put("I", "value");
		Assertions.assertEquals("value", explicitLocale.get("\u0131"));
		Assertions.assertNull(explicitLocale.get("i"));

		EntityTag entityTag = EntityTag.fromStrongValue("v1");
		Request notModified = Request.withRawUrl(HttpMethod.GET, "/locale")
				.headers(Map.of("if-none-match", Set.of("\"v1\""))).build();
		Assertions.assertEquals(304, ConditionalRequests.responseFor(notModified, entityTag, null)
				.orElseThrow().getStatusCode());
		Request stale = Request.withRawUrl(HttpMethod.PUT, "/locale")
				.headers(Map.of("if-match", Set.of("\"old\""))).build();
		Assertions.assertEquals(412, ConditionalRequests.responseFor(stale, entityTag, null)
				.orElseThrow().getStatusCode());
		Request matching = Request.withRawUrl(HttpMethod.PUT, "/locale")
				.headers(Map.of("if-match", Set.of("\"v1\""))).build();
		Assertions.assertTrue(ConditionalRequests.responseFor(matching, entityTag, null).isEmpty());
	}

	private static void assertFileValues(@NonNull Path file) {
		Assertions.assertAll(
				() -> Assertions.assertEquals("bytes 2-4/6",
						ByteRangeSelection.fromHeaderValue("bytes=2-4", 6L).getRange().orElseThrow()
								.toContentRangeHeaderValue(6L)),
				() -> {
					MarshaledResponse partial = MarshaledResponse.withFile(file, rangeRequest("bytes=2-4")).build();
					Assertions.assertEquals(206, partial.getStatusCode());
					Assertions.assertEquals(Set.of("bytes 2-4/6"), partial.getHeaders().get("Content-Range"));
				},
				() -> {
					MarshaledResponse unsatisfiable = MarshaledResponse.withFile(file, rangeRequest("bytes=9-10")).build();
					Assertions.assertEquals(416, unsatisfiable.getStatusCode());
					Assertions.assertEquals(Set.of("bytes */6"), unsatisfiable.getHeaders().get("Content-Range"));
				},
				() -> {
					MarshaledResponse response = requireNonNull(LocaleResource.staticFiles)
							.marshaledResponseFor("example.txt", Request.fromPath(HttpMethod.GET, "/static")).orElseThrow();
					Assertions.assertEquals(200, response.getStatusCode());
					Assertions.assertEquals(Set.of(FILE_ENTITY_TAG), response.getHeaders().get("ETag"));
				});
	}

	private static @NonNull Request rangeRequest(@NonNull String range) {
		return Request.withPath(HttpMethod.GET, "/file").headers(Map.of("Range", Set.of(range))).build();
	}

	private static void assertCookieValues() {
		Assertions.assertAll(
				() -> Assertions.assertEquals(COOKIE, cookie().toSetCookieHeaderRepresentation()),
				() -> Assertions.assertEquals("session=locale; Max-Age=0",
						ResponseCookie.with("session", "locale").maxAge(Duration.ZERO).build().toSetCookieHeaderRepresentation()),
				() -> Assertions.assertEquals("session=locale; Max-Age=9223372036854775807",
						ResponseCookie.with("session", "locale").maxAge(Duration.ofSeconds(Long.MAX_VALUE))
								.build().toSetCookieHeaderRepresentation()));
	}

	private static void assertHttpWire() throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
				.host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(LocaleResource.class)))
				.lifecyclePolicy(lifecyclePolicy())
				.build();
		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			assertWireStatus(port, "if-none-match: \"v1\"", "304 Not Modified");
			assertWireStatus(port, "if-match: \"old\"", "412 Precondition Failed");
			assertWireStatus(port, "if-match: \"v1\"", "200 OK");
			Assertions.assertAll(
					() -> assertResponse(exchange(port, "/file", "Range: bytes=2-4\r\n", ""),
							"HTTP/1.1 206 Partial Content", Map.of("Content-Range", "bytes 2-4/6", "Content-Length", "3"), "cde"),
					() -> assertResponse(exchange(port, "/file", "Range: bytes=9-10\r\n", ""),
							"HTTP/1.1 416 Range Not Satisfiable", Map.of("Content-Range", "bytes */6"), ""),
					() -> assertResponse(exchange(port, "/static", "", ""),
							"HTTP/1.1 200 OK", Map.of("ETag", FILE_ENTITY_TAG, "Content-Length", "6"), "abcdef"),
					() -> assertResponse(exchange(port, "/cookie", "", ""),
							"HTTP/1.1 200 OK", Map.of("Set-Cookie", COOKIE, "Content-Length", "6"), "cookie"));
		}
	}

	private static void assertSseWire() throws Exception {
		int httpPort = TestSupport.findFreePort();
		int ssePort;
		do { ssePort = TestSupport.findFreePort(); } while (ssePort == httpPort);
		final int port = ssePort;
		CountDownLatch established = new CountDownLatch(1);
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.throwableHandler((request, throwable, resourceMethod) -> MarshaledResponse
						.withStatusCode(request.getRawPath().equals("/sse/unknown") ? 599 : 400)
						.body(REJECTION_BYTES).cookies(Set.of(cookie())).build())
				.serviceUnavailableHandler((request, resourceMethod) -> MarshaledResponse.withStatusCode(503)
						.headers(Map.of("Content-Length", Set.of("10")))
						.body(REJECTION_BYTES).cookies(Set.of(cookie())).build())
				.build();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(httpPort).host("127.0.0.1").build())
				.sseServer(SseServer.withPort(port).host("127.0.0.1")
						.concurrentConnectionLimit(1).verifyConnectionOnceEstablished(false).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(LocaleResource.class, LocaleSseResource.class)))
				.responseMarshaler(marshaler)
				.lifecyclePolicy(lifecyclePolicy())
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didEstablishSseConnection(@NonNull SseConnection connection) {
						established.countDown();
					}
				}).build();
		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			Assertions.assertAll(
					() -> assertResponse(exchange(port, "/sse/reject", "", ""),
							"HTTP/1.1 403 Forbidden", Map.of("Content-Length", "10", "Set-Cookie", COOKIE), REJECTION_BODY),
					// The GET-body rejection calls the marshaler directly; the handshake
					// serializer must synthesize its own exact byte Content-Length.
					() -> assertResponse(exchange(port, "/sse/reject", "Content-Length: 1\r\n", "x"),
							"HTTP/1.1 400 Bad Request", Map.of("Content-Length", "10", "Set-Cookie", COOKIE), REJECTION_BODY),
					() -> assertResponse(exchange(port, "/sse/unknown", "Content-Length: 1\r\n", "x"),
							"HTTP/1.1 599", Map.of("Content-Length", "10", "Set-Cookie", COOKIE), REJECTION_BODY),
					() -> {
						try (Socket accepted = openRequest(port, "/sse/accepted", "", "")) {
							String head = readHead(accepted);
							Assertions.assertTrue(established.await(3, TimeUnit.SECONDS), "SSE did not establish");
							Assertions.assertAll(
									() -> Assertions.assertAll(
											() -> Assertions.assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"), head),
											() -> Assertions.assertTrue(head.contains("Set-Cookie: " + COOKIE + "\r\n"), head),
											() -> Assertions.assertFalse(head.toLowerCase(Locale.ROOT).contains("content-length:"), head)),
									() -> assertResponse(exchange(port, "/sse/accepted", "", ""),
											"HTTP/1.1 503", Map.of("Content-Length", "10", "Set-Cookie", COOKIE), REJECTION_BODY));
						}
					});
		}
	}

	private static @NonNull LifecyclePolicy lifecyclePolicy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(3))
				.gracefulShutdownTimeout(Duration.ofSeconds(2)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}

	private static @NonNull ResponseCookie cookie() {
		return ResponseCookie.with("session", "locale").maxAge(Duration.ofSeconds(3600)).build();
	}

	private static @NonNull Socket openRequest(int port, @NonNull String path, @NonNull String headers, @NonNull String body) throws Exception {
		Socket socket = TestSupport.connectWithRetry("127.0.0.1", port, 2_000);
		try {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost:" + port
					+ "\r\n" + headers + "Connection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			return socket;
		} catch (Throwable failure) {
			socket.close();
			throw failure;
		}
	}

	private static byte @NonNull [] exchange(int port, @NonNull String path, @NonNull String headers, @NonNull String body) throws Exception {
		try (Socket socket = openRequest(port, path, headers, body)) {
			byte[] response = socket.getInputStream().readNBytes(65_537);
			Assertions.assertTrue(response.length <= 65_536, "HTTP response exceeded its bound");
			return response;
		}
	}

	private static @NonNull String readHead(@NonNull Socket socket) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 16_384) {
			int value = socket.getInputStream().read();
			Assertions.assertNotEquals(-1, value, "EOF before SSE response headers");
			bytes.write(value);
			String head = bytes.toString(StandardCharsets.ISO_8859_1);
			if (head.endsWith("\r\n\r\n"))
				return head;
		}
		throw new AssertionError("SSE headers exceeded their bound");
	}

	private static void assertResponse(byte @NonNull [] bytes, @NonNull String status, @NonNull Map<String, String> headers, @NonNull String body) {
		String response = new String(bytes, StandardCharsets.ISO_8859_1);
		int separator = response.indexOf("\r\n\r\n");
		Assertions.assertTrue(separator >= 0, "Missing HTTP header/body separator: " + response);
		String head = response.substring(0, separator + 2);
		Assertions.assertAll(status,
				() -> Assertions.assertTrue(head.startsWith(status + "\r\n"), head),
				() -> Assertions.assertAll(headers.entrySet().stream().map(entry -> (Executable) () ->
						Assertions.assertTrue(head.contains("\r\n" + entry.getKey() + ": " + entry.getValue() + "\r\n"), head))),
				() -> Assertions.assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(bytes, separator + 4, bytes.length)));
	}

	private static void assertWireStatus(int port, @NonNull String header, @NonNull String status) throws Exception {
		try (Socket socket = TestSupport.connectWithRetry("127.0.0.1", port, 2_000)) {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("GET /locale HTTP/1.1\r\nHost: localhost\r\n"
					+ header + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 " + status + "\r\n"), response);
		}
	}

	public static class LocaleResource {
		private static @Nullable Path file;
		private static @Nullable StaticFiles staticFiles;

		@GET("/locale")
		public @NonNull Response locale(@NonNull Request request) {
			return ConditionalRequests.responseFor(request, EntityTag.fromStrongValue("v1"), null)
					.orElseGet(() -> Response.withStatusCode(200).body("matched").build());
		}

		@GET("/file")
		public @NonNull MarshaledResponse file(@NonNull Request request) {
			return MarshaledResponse.withFile(requireNonNull(file), request).build();
		}

		@GET("/static")
		public @NonNull MarshaledResponse staticFile(@NonNull Request request) {
			return requireNonNull(staticFiles).marshaledResponseFor("example.txt", request).orElseThrow();
		}

		@GET("/cookie")
		public @NonNull Response cookieResponse() {
			return Response.withStatusCode(200).cookies(Set.of(cookie())).body("cookie").build();
		}
	}

	public static class LocaleSseResource {
		@SseEventSource("/sse/reject")
		public @NonNull SseHandshakeResult reject() {
			return SseHandshakeResult.rejectWithResponse(Response.withStatusCode(403)
					.cookies(Set.of(cookie())).body(REJECTION_BODY).build());
		}

		@SseEventSource("/sse/accepted")
		public @NonNull SseHandshakeResult accept() {
			return SseHandshakeResult.Accepted.builder().cookies(Set.of(cookie())).build();
		}
	}
}
