/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HeadReplacementCompressionTests {
	@Test
	void freshBodylessReplacementClearsHeadPlanningWithoutAPostprocessor() throws Exception {
		AtomicInteger plans = new AtomicInteger();
		int port = TestSupport.findFreePort();
		try (Soklet app = Soklet.fromConfig(config(new Resource("original-content-".repeat(5)), port,
				(request, response) -> {
					plans.incrementAndGet();
					return ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance());
				}).requestInterceptor(replacing(true, () -> MarshaledResponse.fromStatusCode(200))).build())) {
			app.start();
			WireResponse get = exchange(port, HttpMethod.GET, "gzip");
			WireResponse head = exchange(port, HttpMethod.HEAD, "gzip");
			assertEquals(get.status(), head.status());
			assertNull(get.header("Content-Encoding"));
			assertNull(head.header("Content-Encoding"));
			assertEquals(0, head.body().length);
			assertEquals(0, plans.get(), "The discarded generated body must not reach compression planning.");
		}
	}

	@Test
	@Timeout(90)
	void freshReplacementPlansTheFinalPostprocessedBodyForGetAndHead() throws Exception {
		for (boolean generate : List.of(false, true)) {
			for (String replacement : List.of("tiny", "replacement-content-".repeat(5))) {
				Resource resource = new Resource(replacement.length() < 16 ? "original-content-".repeat(5) : "old");
				List<String> plannedBodies = new CopyOnWriteArrayList<>();
				AtomicInteger codecCalls = new AtomicInteger();
				ResponseCompressionCodec codec = new ResponseCompressionCodec() {
					@Override public String getContentEncoding() { return "gzip"; }
					@Override public byte[] compress(ByteBuffer body) {
						codecCalls.incrementAndGet();
						return ResponseCompressionCodec.gzipInstance().compress(body);
					}
				};
				ResponseCompressor compressor = (request, response) -> {
					plannedBodies.add(new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8));
					return response.getBodyLength() >= 16 ? ResponseCompressionPlan.compress(codec) : ResponseCompressionPlan.none();
				};
				ResponseMarshaler marshaler = ResponseMarshaler.builder().postProcessor(response -> response.copy()
						.body(("post:" + new String(response.bodyBytesOrEmpty(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))
						.finish()).build();
				int port = TestSupport.findFreePort();
				try (Soklet app = Soklet.fromConfig(config(resource, port, compressor).responseMarshaler(marshaler)
						.requestInterceptor(replacing(generate, () -> bytes(replacement))).build())) {
					app.start();
					WireResponse get = exchange(port, HttpMethod.GET, "gzip");
					WireResponse head = exchange(port, HttpMethod.HEAD, "gzip");
					String expected = "post:" + replacement;
					assertEquals(List.of(expected, expected), plannedBodies);
					assertEquals(get.header("Content-Encoding"), head.header("Content-Encoding"));
					assertEquals(get.header("Vary"), head.header("Vary"));
					assertEquals(0, head.body().length);
					if (expected.length() >= 16) {
						assertEquals("gzip", head.header("Content-Encoding"));
						assertNull(head.header("Content-Length"), "HEAD must not calculate compressed bytes.");
						try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(get.body()))) {
							assertEquals(expected, new String(gzip.readAllBytes(), StandardCharsets.UTF_8));
						}
						assertEquals(1, codecCalls.get(), "Only GET may run the codec.");
					} else {
						assertNull(head.header("Content-Encoding"));
						assertEquals(Integer.toString(expected.length()), head.header("Content-Length"));
						assertEquals(expected, new String(get.body(), StandardCharsets.UTF_8));
						assertEquals(0, codecCalls.get());
					}
				}
			}
		}
	}

	@Test
	void freshHeadReplacementRetainsEncodingRejection() throws Exception {
		for (boolean generate : List.of(false, true)) {
			int port = TestSupport.findFreePort();
			try (Soklet app = Soklet.fromConfig(config(new Resource("old"), port,
					ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(16))
					.responseMarshaler(headerPostprocessor()).requestInterceptor(replacing(generate,
							() -> bytes("replacement-content-".repeat(5)))).build())) {
				app.start();
				WireResponse get = exchange(port, HttpMethod.GET, "gzip;q=0, identity;q=0");
				WireResponse head = exchange(port, HttpMethod.HEAD, "gzip;q=0, identity;q=0");
				assertTrue(get.status().startsWith("HTTP/1.1 406 "), get.status());
				assertEquals(get.status(), head.status());
				assertEquals(0, head.body().length);
			}
		}
	}

	@Test
	void freshBodylessFileAndStreamingReplacementsClearPriorHeadPlanning(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("replacement.txt"), "file body");
		AtomicInteger producers = new AtomicInteger();
		for (String kind : List.of("bodyless", "file", "stream")) {
			AtomicInteger plans = new AtomicInteger();
			int port = TestSupport.findFreePort();
			try (Soklet app = Soklet.fromConfig(config(new Resource("original-content-".repeat(5)), port,
					(request, response) -> {
						plans.incrementAndGet();
						return ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance());
					}).responseMarshaler(headerPostprocessor()).requestInterceptor(replacing(true, () -> switch (kind) {
						case "file" -> MarshaledResponse.withStatusCode(200).body(file).build();
						case "stream" -> MarshaledResponse.withStatusCode(200).stream(stream -> producers.incrementAndGet()).build();
						default -> MarshaledResponse.fromStatusCode(200);
					})).build())) {
				app.start();
				WireResponse head = exchange(port, HttpMethod.HEAD, "gzip");
				assertTrue(head.status().startsWith("HTTP/1.1 200 "), head.status());
				assertNull(head.header("Content-Encoding"), kind);
				assertEquals(0, plans.get(), kind);
				assertEquals(0, producers.get(), "HEAD must not execute a suppressed producer.");
				assertEquals(kind.equals("file") ? "9" : null, head.header("Content-Length"), kind);
			}
		}
	}

	private static ResponseMarshaler headerPostprocessor() {
		return ResponseMarshaler.builder().postProcessor(response -> response.copy()
				.headers(headers -> headers.put("X-Final", List.of("yes"))).finish()).build();
	}

	private static MarshaledResponse bytes(String value) {
		return MarshaledResponse.withStatusCode(200).headers(Map.of("Content-Type", List.of("text/plain")))
				.body(value.getBytes(StandardCharsets.UTF_8)).build();
	}

	private static RequestInterceptor replacing(boolean generate, java.util.function.Supplier<MarshaledResponse> replacement) {
		return new RequestInterceptor() {
			@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
					Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
				if (generate) generator.apply(request);
				writer.accept(replacement.get());
			}
		};
	}

	private static SokletConfig.Builder config(Resource resource, int port, ResponseCompressor compressor) {
		return SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").responseCompressor(compressor).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(2)).build());
	}

	private static WireResponse exchange(int port, HttpMethod method, String encoding) throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write((method + " /body HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: " + encoding
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			byte[] bytes = socket.getInputStream().readAllBytes();
			String wire = new String(bytes, StandardCharsets.ISO_8859_1);
			int boundary = wire.indexOf("\r\n\r\n");
			assertTrue(boundary > 0, wire);
			String[] lines = wire.substring(0, boundary).split("\r\n");
			Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (int index = 1; index < lines.length; index++) {
				int colon = lines[index].indexOf(':');
				headers.put(lines[index].substring(0, colon), lines[index].substring(colon + 1).trim());
			}
			return new WireResponse(lines[0], headers, Arrays.copyOfRange(bytes, boundary + 4, bytes.length));
		}
	}

	private record WireResponse(String status, Map<String, String> headers, byte[] body) {
		String header(String name) { return headers.get(name); }
	}

	public static final class Resource {
		private final String body;
		Resource(String body) { this.body = body; }
		@GET("/body") public String body() { return body; }
	}
}
