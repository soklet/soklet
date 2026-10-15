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
import com.soklet.annotation.HEAD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** HEAD responses work with both prepared metadata and ordinary GET representations. */
@Timeout(60)
class DefaultHeadHandlingTests {
	private static final byte[] REPRESENTATION = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
	private static final String ETAG = "\"jwks-v1\"";

	@Test
	void preparedHeadRetainsItsExplicitRepresentationLengthAndMetadata() {
		for (String length : List.of("0", "1400", "9223372036854775808")) {
			MarshaledResponse prepared = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("content-length", List.of(length),
							"Content-Type", List.of("application/jwk-set+json"),
							"ETag", List.of(ETAG), "Cache-Control", List.of("public, max-age=3600"))).build();

			MarshaledResponse result = forHead(prepared);

			assertEquals(200, result.getStatusCode());
			assertEquals(prepared.getHeaders(), result.getHeaders());
			assertNoBody(result);
		}
	}

	@Test
	void absentRepresentationDoesNotImplyAnEmptyRepresentation() {
		for (int status : List.of(200, 304)) {
			MarshaledResponse result = forHead(MarshaledResponse.fromStatusCode(status));
			assertEquals(status, result.getStatusCode());
			assertFalse(result.getHeaders().containsKey("Content-Length"));
			assertNoBody(result);
		}
	}

	@Test
	void finiteBodyDeterminesTheLengthEvenWhenEmptyOrAnExplicitLengthDisagrees() {
		ByteBuffer buffer = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
		buffer.position(1);
		for (MarshaledResponseBody body : List.of(new MarshaledResponseBody.Bytes(new byte[0]),
				new MarshaledResponseBody.Bytes("café".getBytes(StandardCharsets.UTF_8)),
				new MarshaledResponseBody.ByteBuffer(buffer))) {
			MarshaledResponse representation = MarshaledResponse.withStatusCode(200)
					.headers(Map.of("Content-Length", List.of("1400"))).body(body).build();

			MarshaledResponse result = forHead(representation);

			assertEquals(List.of(Long.toString(body.getLength())), result.getHeaders().get("Content-Length"));
			assertNoBody(result);
		}
		assertEquals(1, buffer.position());
	}

	@Test
	void notModifiedRetainsAnExplicitSuccessfulRepresentationLength() {
		MarshaledResponse result = forHead(MarshaledResponse.withStatusCode(304)
				.headers(Map.of("Content-Length", List.of("1400"), "ETag", List.of(ETAG))).build());

		assertEquals(304, result.getStatusCode());
		assertEquals(List.of("1400"), result.getHeaders().get("Content-Length"));
		assertEquals(List.of(ETAG), result.getHeaders().get("ETag"));
		assertNoBody(result);
	}

	@Test
	void informationalAndNoContentStatusesNeverAdvertiseContentLength() {
		for (int status : List.of(100, 103, 199, 204)) {
			for (int length : List.of(0, 3)) {
				MarshaledResponse result = forHead(MarshaledResponse.withStatusCode(status)
						.headers(Map.of("Content-Length", List.of("1400"))).body(new byte[length]).build());

				assertEquals(status, result.getStatusCode());
				assertFalse(result.getHeaders().containsKey("Content-Length"), "HTTP " + status);
				assertNoBody(result);
			}
		}
	}

	@Test
	void resetContentKeepsItsZeroLengthNormalization() {
		for (MarshaledResponse original : List.of(MarshaledResponse.fromStatusCode(205),
				MarshaledResponse.withStatusCode(205).body(new byte[0])
						.headers(Map.of("Content-Length", List.of("1400"))).build())) {
			MarshaledResponse result = forHead(original);
			assertEquals(205, result.getStatusCode());
			assertEquals(List.of("0"), result.getHeaders().get("Content-Length"));
			assertNoBody(result);
		}
	}

	@Test
	void streamingHeadDoesNotAcquireTheSourceOrInventALength() {
		AtomicInteger acquisitions = new AtomicInteger();
		MarshaledResponse original = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", List.of("application/octet-stream")))
				.streamingResponseBody(StreamingResponseBody.fromInputStream(() -> {
					acquisitions.incrementAndGet();
					return new java.io.ByteArrayInputStream(new byte[]{1, 2, 3});
				})).build();

		MarshaledResponse result = forHead(original);

		assertFalse(result.getHeaders().containsKey("Content-Length"));
		assertEquals(original.getHeaders(), result.getHeaders());
		assertNoBody(result);
		assertEquals(0, acquisitions.get());
	}

	@Test
	void customHeadHandlerAndPostProcessorRetainTheirOrderAndControl() {
		AtomicInteger stage = new AtomicInteger();
		MarshaledResponse representation = MarshaledResponse.withStatusCode(200).body(REPRESENTATION).build();
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.headHandler((request, original) -> {
					assertSame(representation, original);
					assertEquals(0, stage.getAndIncrement());
					return MarshaledResponse.withStatusCode(202)
							.headers(Map.of("Content-Length", List.of("777"))).build();
				})
				.postProcessor(response -> {
					assertEquals(1, stage.getAndIncrement());
					assertEquals(202, response.getStatusCode());
					return response.copy().headers(headers -> headers.put("X-Processed", List.of("true"))).finish();
				}).build();

		MarshaledResponse result = marshaler.forHead(Request.fromPath(HttpMethod.HEAD, "/jwks"), representation);

		assertEquals(2, stage.get());
		assertEquals(202, result.getStatusCode());
		assertEquals(List.of("777"), result.getHeaders().get("Content-Length"));
		assertEquals(List.of("true"), result.getHeaders().get("X-Processed"));
		assertNoBody(result);
	}

	@Test
	void defaultSimulatorPreservesPreparedExplicitHeadMetadata() {
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			MarshaledResponse get = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/explicit")).getMarshaledResponse();
			HttpRequestResult headResult = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/explicit"));
			MarshaledResponse head = headResult.getMarshaledResponse();

			assertArrayEquals(REPRESENTATION, get.bodyBytesOrEmpty());
			assertEquals(get.getHeaders(), head.getHeaders());
			assertEquals(List.of(Integer.toString(REPRESENTATION.length)), head.getHeaders().get("Content-Length"));
			assertEquals("explicitHead", headResult.getResourceMethod().orElseThrow().getMethod().getName());
			assertEquals(1, resource.getCalls.get());
			assertEquals(1, resource.headCalls.get());
			assertNoBody(head);
		});
	}

	@Test
	void defaultSimulatorDerivesTheGetFallbackHeadLength() {
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/fallback"));
			MarshaledResponse head = result.getMarshaledResponse();

			assertEquals(200, head.getStatusCode());
			assertEquals(List.of(Integer.toString(REPRESENTATION.length)), head.getHeaders().get("Content-Length"));
			assertEquals("fallback", result.getResourceMethod().orElseThrow().getMethod().getName());
			assertEquals(1, resource.getCalls.get());
			assertEquals(0, resource.headCalls.get());
			assertNoBody(head);
		});
	}

	@Test
	void defaultSimulatorDoesNotInventLengthsForConditionalHeadResponses() {
		SokletSimulator.run(config(new Resource()), simulator -> {
			for (String path : List.of("/explicit", "/fallback")) {
				for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
					MarshaledResponse result = simulator.performHttpRequest(Request.withPath(method, path)
							.headers(Map.of("If-None-Match", List.of(ETAG))).build()).getMarshaledResponse();

					assertEquals(304, result.getStatusCode(), method + " " + path);
					assertFalse(result.getHeaders().containsKey("Content-Length"), method + " " + path);
					assertEquals(List.of(ETAG), result.getHeaders().get("ETag"));
					assertEquals(List.of("public, max-age=3600"), result.getHeaders().get("Cache-Control"));
					assertNoBody(result);
				}
			}
		});
	}

	@Test
	void defaultSimulatorKeepsUnknownPreparedHeadLengthUnspecified() {
		SokletSimulator.run(config(new Resource()), simulator -> {
			MarshaledResponse result = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/unknown")).getMarshaledResponse();
			assertEquals(200, result.getStatusCode());
			assertFalse(result.getHeaders().containsKey("Content-Length"));
			assertNoBody(result);
		});
	}

	@Test
	void defaultSimulatorSuppressesFiniteBodiesWithoutChangingChannelOwnership(@TempDir Path directory) throws Exception {
		Path file = Files.writeString(directory.resolve("body.txt"), "abcdef");
		Resource resource = new Resource();
		SokletSimulator.run(config(resource), simulator -> {
			for (boolean owned : List.of(false, true)) {
				for (long length : List.of(0L, 6L)) {
					try (FileChannel channel = FileChannel.open(file)) {
						resource.channelResponse = MarshaledResponse.withStatusCode(200)
								.headers(Map.of("Content-Length", List.of("1400")))
								.body(channel, 0L, length, owned).build();
						MarshaledResponse result = simulator.performHttpRequest(Request.fromPath(HttpMethod.HEAD, "/channel")).getMarshaledResponse();
						assertEquals(200, result.getStatusCode());
						assertEquals(List.of(Long.toString(length)), result.getHeaders().get("Content-Length"));
						assertEquals(!owned, channel.isOpen(), "Only a suppressed owned channel must close");
						assertNoBody(result);
					}
				}
			}
		});
	}

	private static MarshaledResponse forHead(MarshaledResponse original) {
		return ResponseMarshaler.defaultInstance().forHead(Request.fromPath(HttpMethod.HEAD, "/jwks"), original);
	}

	private static void assertNoBody(MarshaledResponse response) {
		assertTrue(response.getBody().isEmpty());
		assertTrue(response.getStreamingResponseBody().isEmpty());
		assertEquals(0L, response.getBodyLength());
	}

	private static SimulatorConfig config(Resource resource) {
		return SimulatorConfig.builder().httpServer()
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				}).lifecycleObserver(new LifecycleObserver() {}).build();
	}

	public static final class Resource {
		private final AtomicInteger getCalls = new AtomicInteger();
		private final AtomicInteger headCalls = new AtomicInteger();
		private MarshaledResponse channelResponse;

		@GET("/explicit") public MarshaledResponse explicitGet(Request request) {
			this.getCalls.incrementAndGet();
			return representation(request, false);
		}

		@HEAD("/explicit") public MarshaledResponse explicitHead(Request request) {
			this.headCalls.incrementAndGet();
			return representation(request, true);
		}

		@GET("/fallback") public MarshaledResponse fallback(Request request) {
			this.getCalls.incrementAndGet();
			return representation(request, false);
		}

		@HEAD("/unknown") public MarshaledResponse unknown() { return MarshaledResponse.fromStatusCode(200); }

		@GET("/channel") public MarshaledResponse channel() { return this.channelResponse; }

		private static MarshaledResponse representation(Request request, boolean preparedHead) {
			Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/jwk-set+json"),
					"ETag", List.of(ETAG), "Cache-Control", List.of("public, max-age=3600"));
			if (request.getHeader("If-None-Match").filter(ETAG::equals).isPresent())
				return MarshaledResponse.withStatusCode(304).headers(headers).build();
			Map<String, List<String>> successHeaders = new java.util.HashMap<>(headers);
			successHeaders.put("Content-Length", List.of(Integer.toString(REPRESENTATION.length)));
			MarshaledResponse.Builder result = MarshaledResponse.withStatusCode(200).headers(successHeaders);
			if (!preparedHead) result.body(REPRESENTATION);
			return result.build();
		}
	}
}
