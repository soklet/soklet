/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.PUT;
import com.soklet.annotation.PathParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP exchanges: simulator-only tests cannot exercise transport ETag weakening. */
class ConditionalRequestCompressionTests {
	private static final String BODY = "A finalized representation whose ETag protects a conditional update. ".repeat(64);
	private static final Instant MODIFIED = Instant.parse("2026-05-04T01:02:03Z");

	@Test
	@Timeout(75)
	void automaticCompressionWeakensOnlyEncodedEtagsOnTheRealServer() throws Exception {
		withServer(ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(1024), (client, base) -> {
			HttpResponse<byte[]> compressed = get(client, base.resolve("strong"), "gzip", null);
			assertEquals(200, compressed.statusCode());
			assertEquals("gzip", header(compressed, "Content-Encoding"));
			assertEquals("W/\"v1\"", header(compressed, "ETag"));
			assertEquals(BODY, gunzip(compressed.body()));
			assertTrue(header(compressed, "Vary").contains("Accept-Encoding"));

			HttpResponse<byte[]> identity = get(client, base.resolve("strong"), "identity", null);
			assertEquals(200, identity.statusCode());
			assertEquals("\"v1\"", header(identity, "ETag"));
			assertNull(header(identity, "Content-Encoding"));
			assertEquals(BODY, new String(identity.body(), StandardCharsets.UTF_8));

			HttpResponse<byte[]> small = get(client, base.resolve("small"), "gzip", null);
			assertEquals("\"v1\"", header(small, "ETag"));
			assertNull(header(small, "Content-Encoding"));
			assertEquals("small", new String(small.body(), StandardCharsets.UTF_8));
			assertEquals(412, put(client, base.resolve("strong"), header(compressed, "ETag")).statusCode(),
					"A weak wire validator must not become an accepted strong If-Match");
		});
	}

	@Test
	@Timeout(75)
	void applicationWrapperPreservesStrongTagsWithoutChangingWeakCacheValidation() throws Exception {
		ResponseCompressor gzip = ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(1024);
		ResponseCompressor preserving = (request, response) -> {
			boolean strongTag = response.getHeaders().entrySet().stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase("ETag"))
					.flatMap(entry -> entry.getValue().stream())
					.map(EntityTag::fromHeaderValue)
					.flatMap(Optional::stream)
					.anyMatch(tag -> !tag.isWeak());
			return strongTag ? ResponseCompressionPlan.none() : gzip.plan(request, response);
		};
		withServer(preserving, (client, base) -> {
			HttpResponse<byte[]> strong = get(client, base.resolve("strong"), "gzip", null);
			assertEquals(200, strong.statusCode());
			assertEquals("\"v1\"", header(strong, "ETag"));
			assertNull(header(strong, "Content-Encoding"));
			assertEquals(BODY, new String(strong.body(), StandardCharsets.UTF_8));
			assertEquals(204, put(client, base.resolve("strong"), header(strong, "ETag")).statusCode());

			HttpResponse<byte[]> weak = get(client, base.resolve("weak"), "gzip", null);
			assertEquals(200, weak.statusCode());
			assertEquals("W/\"v1\"", header(weak, "ETag"));
			assertEquals("gzip", header(weak, "Content-Encoding"));
			assertEquals(BODY, gunzip(weak.body()));

			HttpResponse<byte[]> forbidden = get(client, base.resolve("strong"), "gzip, identity;q=0", null);
			assertEquals(406, forbidden.statusCode());
			assertEquals(0, forbidden.body().length);
			assertNull(header(forbidden, "Content-Encoding"));

			HttpResponse<byte[]> cached = get(client, base.resolve("weak"), "gzip", header(weak, "ETag"));
			assertEquals(304, cached.statusCode());
			assertEquals("W/\"v1\"", header(cached, "ETag"));
			assertEquals("private, max-age=60", header(cached, "Cache-Control"));
			assertTrue(header(cached, "Vary").contains("Accept-Language"));
			assertTrue(header(cached, "Vary").contains("Accept-Encoding"));
			assertEquals(0, cached.body().length);
			assertNull(header(cached, "Content-Length"));
		});
	}

	private static void withServer(ResponseCompressor compressor, WireAssertions assertions) throws Exception {
		int port = TestSupport.findFreePort();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
				.host("127.0.0.1").responseCompressor(compressor).build())
				.jvmShutdownHookEnabled(false)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(CompressionResource.class)))
				.build();
		Soklet.run(config, () -> {
			try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
					.version(HttpClient.Version.HTTP_1_1).build()) {
				assertions.run(client, URI.create("http://127.0.0.1:" + port + "/conditional-compression/"));
			}
		});
	}

	private static HttpResponse<byte[]> get(HttpClient client, URI uri, String encoding, String ifNoneMatch) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
				.header("Accept-Encoding", encoding).GET();
		if (ifNoneMatch != null)
			builder.header("If-None-Match", ifNoneMatch);
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static HttpResponse<byte[]> put(HttpClient client, URI uri, String ifMatch) throws Exception {
		return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
				.header("If-Match", ifMatch).PUT(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String header(HttpResponse<?> response, String name) {
		return response.headers().firstValue(name).orElse(null);
	}

	private static String gunzip(byte[] bytes) throws Exception {
		try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@FunctionalInterface
	private interface WireAssertions {
		void run(HttpClient client, URI base) throws Exception;
	}

	public static class CompressionResource {
		@GET("/conditional-compression/{kind}")
		public Response get(Request request, @PathParameter String kind) {
			EntityTag tag = tag(kind);
			Map<String, List<String>> cacheHeaders = Map.of("Cache-Control", List.of("private, max-age=60"),
					"Vary", List.of("Accept-Language"));
			return ConditionalRequests.responseFor(request, tag, MODIFIED, cacheHeaders).orElseGet(() ->
					Response.withStatusCode(200).headers(Map.of(
							"eTaG", List.of(tag.toHeaderValue()), "Content-Type", List.of("text/plain; charset=UTF-8"),
							"Cache-Control", cacheHeaders.get("Cache-Control"), "Vary", cacheHeaders.get("Vary")))
							.body(kind.equals("small") ? "small" : BODY).build());
		}

		@PUT("/conditional-compression/{kind}")
		public Response put(Request request, @PathParameter String kind) {
			return ConditionalRequests.responseFor(request, tag(kind), MODIFIED)
					.orElseGet(() -> Response.fromStatusCode(204));
		}

		private static EntityTag tag(String kind) {
			return kind.equals("weak") ? EntityTag.fromWeakValue("v1") : EntityTag.fromStrongValue("v1");
		}
	}
}
