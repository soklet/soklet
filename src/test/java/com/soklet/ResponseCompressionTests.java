package com.soklet;

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

public class ResponseCompressionTests {
	@Test
	public void gzipCodecCompressesOnlyRemainingBytesWithoutChangingBufferCursor() throws IOException {
		byte[] body = "only the selected bytes".getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocateDirect(body.length + 12);
		buffer.position(5);
		buffer.put(body);
		buffer.limit(buffer.position());
		buffer.position(5);
		buffer.mark();

		byte[] compressed = ResponseCompressionCodec.gzipInstance().compress(buffer);

		Assertions.assertArrayEquals(body, gunzip(compressed));
		Assertions.assertEquals(5, buffer.position());
		Assertions.assertEquals(body.length + 5, buffer.limit());
		buffer.reset();
		Assertions.assertEquals(5, buffer.position());
		Assertions.assertArrayEquals(body,
				gunzip(ResponseCompressionCodec.gzipInstance().compress(buffer.asReadOnlyBuffer())));
	}

	@Test
	public void cacheReusesBodyBytesWhileHeadersAndCookiesRemainPerResponse() throws IOException {
		byte[] body = "same version of the representation".repeat(40).getBytes(StandardCharsets.UTF_8);
		AtomicInteger compressionCalls = new AtomicInteger();
		AtomicInteger providerCalls = new AtomicInteger();
		AtomicReference<byte[]> cachedBody = new AtomicReference<>();
		ResponseCompressionCodec codec = codec("gzip", uncompressedBody -> {
			compressionCalls.incrementAndGet();
			return ResponseCompressionCodec.gzipInstance().compress(uncompressedBody);
		});
		ResponseCompressionPlan plan = ResponseCompressionPlan.compress(codec, compressedBodySupplier -> {
			providerCalls.incrementAndGet();
			if (cachedBody.get() == null) {
				byte[] compressed = compressedBodySupplier.get();
				Assertions.assertSame(compressed, compressedBodySupplier.get(),
						"The codec supplier is memoized within this response");
				cachedBody.set(compressed);
			}
			return cachedBody.get();
		});
		DefaultHttpServer server = server((request, marshaledResponse) -> plan);
		Request request = requestAccepting("gzip");
		MarshaledResponse first = response(body).copy()
				.headers(headers -> {
					headers.put("ETag", Set.of("\"content-version\""));
					headers.put("Vary", Set.of("Origin"));
					headers.put("X-Request-Id", Set.of("first"));
				})
				.cookies(Set.of(ResponseCookie.with("session", "first").build()))
				.finish();
		MarshaledResponse second = first.copy()
				.headers(headers -> headers.put("X-Request-Id", Set.of("second")))
				.cookies(Set.of(ResponseCookie.with("session", "second").build()))
				.finish();

		MicrohttpResponse firstResult = server.toMicrohttpResponse(request, null, first);
		MicrohttpResponse secondResult = server.toMicrohttpResponse(request, null, second);

		Assertions.assertEquals(1, compressionCalls.get());
		Assertions.assertEquals(2, providerCalls.get());
		Assertions.assertSame(firstResult.body(), secondResult.body());
		Assertions.assertArrayEquals(body, gunzip(secondResult.body()));
		Assertions.assertEquals("W/\"content-version\"", headerValue(secondResult, "ETag"));
		Assertions.assertEquals("Origin, Accept-Encoding", headerValue(secondResult, "Vary"));
		Assertions.assertEquals("first", headerValue(firstResult, "X-Request-Id"));
		Assertions.assertEquals("second", headerValue(secondResult, "X-Request-Id"));
		Assertions.assertEquals("session=first", headerValue(firstResult, "Set-Cookie"));
		Assertions.assertEquals("session=second", headerValue(secondResult, "Set-Cookie"));
		Assertions.assertNull(headerValue(secondResult, "Content-Length"),
				"The uncompressed length must be removed before transport framing");
		Assertions.assertEquals("\"content-version\"", first.getHeaders().get("ETag").iterator().next());

		cachedBody.set(null);
		server.toMicrohttpResponse(request, null, second);
		Assertions.assertEquals(2, compressionCalls.get(), "Memoization must not become a server-wide body cache");
	}

	@Test
	public void customCodecUsesExplicitAndWildcardAcceptEncodingPreferences() {
		byte[] original = "uncompressed".getBytes(StandardCharsets.UTF_8);
		byte[] encoded = "custom encoded body".getBytes(StandardCharsets.UTF_8);
		AtomicInteger codecCalls = new AtomicInteger();
		AtomicInteger providerCalls = new AtomicInteger();
		ResponseCompressionCodec codec = codec("X-TEST", buffer -> {
			codecCalls.incrementAndGet();
			return encoded;
		});
		DefaultHttpServer server = server((request, marshaledResponse) ->
				ResponseCompressionPlan.compress(codec, compressedBodySupplier -> {
					providerCalls.incrementAndGet();
					return compressedBodySupplier.get();
				}));
		MarshaledResponse response = response(original).copy()
				.headers(headers -> headers.put("ETag", Set.of("\"v1\"")))
				.finish();

		List<String> accepted = List.of("x-test", "X-TEST;q=0.5", "*;q=0.8", "gzip;q=1, x-test;q=0.5");
		for (String acceptEncoding : accepted) {
			MicrohttpResponse result = server.toMicrohttpResponse(requestAccepting(acceptEncoding), null, response);
			Assertions.assertEquals("x-test", headerValue(result, "Content-Encoding"), acceptEncoding);
			Assertions.assertSame(encoded, result.body(), acceptEncoding);
			Assertions.assertEquals("W/\"v1\"", headerValue(result, "ETag"), acceptEncoding);
		}

		List<String> rejected = List.of("*;q=1, X-TEST;q=0", "x-test;q=0", "gzip", "");
		for (String acceptEncoding : rejected) {
			MicrohttpResponse result = server.toMicrohttpResponse(requestAccepting(acceptEncoding), null, response);
			Assertions.assertNull(headerValue(result, "Content-Encoding"), acceptEncoding);
			Assertions.assertArrayEquals(original, result.body(), acceptEncoding);
			Assertions.assertEquals("Accept-Encoding", headerValue(result, "Vary"), acceptEncoding);
			Assertions.assertEquals("\"v1\"", headerValue(result, "ETag"), acceptEncoding);
			Assertions.assertEquals(Integer.toString(original.length), headerValue(result, "Content-Length"));
		}
		MicrohttpResponse absentHeader = server.toMicrohttpResponse(
				Request.withPath(HttpMethod.GET, "/compression").build(), null, response);
		Assertions.assertArrayEquals(original, absentHeader.body());
		Assertions.assertEquals("Accept-Encoding", headerValue(absentHeader, "Vary"));
		for (String acceptEncoding : List.of("x-test;q=0, identity;q=0", "*;q=0")) {
			MicrohttpResponse result = server.toMicrohttpResponse(requestAccepting(acceptEncoding), null, response);
			Assertions.assertEquals(406, result.status());
			Assertions.assertEquals(0, result.body().length);
			Assertions.assertFalse(result.hasHeader("Content-Encoding"));
			Assertions.assertEquals("Accept-Encoding", headerValue(result, "Vary"));
		}
		Assertions.assertEquals(accepted.size(), codecCalls.get());
		Assertions.assertEquals(accepted.size(), providerCalls.get(),
				"An unacceptable encoding must not trigger either cache lookup or compression");
	}

	@Test
	public void byteBufferResponseSuppliesOnlyItsSliceToCodecAndPreservesCursor() throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap("prefixPAYLOADsuffix".getBytes(StandardCharsets.UTF_8));
		buffer.position(6);
		buffer.limit(13);
		AtomicInteger codecCalls = new AtomicInteger();
		ResponseCompressionCodec codec = codec("gzip", input -> {
			codecCalls.incrementAndGet();
			Assertions.assertTrue(input.isReadOnly());
			Assertions.assertEquals(7, input.remaining());
			byte[] bytes = new byte[input.remaining()];
			input.get(bytes);
			return ResponseCompressionCodec.gzipInstance().compress(ByteBuffer.wrap(bytes));
		});
		DefaultHttpServer server = server((request, marshaledResponse) -> ResponseCompressionPlan.compress(codec));
		MarshaledResponse response = MarshaledResponse.withStatusCode(200).body(buffer).build();

		MicrohttpResponse result = server.toMicrohttpResponse(requestAccepting("gzip"), null, response);

		Assertions.assertArrayEquals("PAYLOAD".getBytes(StandardCharsets.UTF_8), gunzip(result.body()));
		Assertions.assertEquals(1, codecCalls.get());
		Assertions.assertEquals(6, buffer.position());
		Assertions.assertEquals(13, buffer.limit());
		Assertions.assertEquals(7L, response.getBodyLength());
	}

	@Test
	public void nonePlanAndNullBuilderResetLeaveBodyUncompressed() {
		byte[] body = "plain body".getBytes(StandardCharsets.UTF_8);
		DefaultHttpServer none = server((request, marshaledResponse) -> ResponseCompressionPlan.none());
		DefaultHttpServer reset = (DefaultHttpServer) HttpServer.withPort(0)
				.responseCompressor(ResponseCompressor.fromDefaultsWithMinimumBodySizeInBytes(0))
				.responseCompressor(null)
				.build();
		Assertions.assertSame(ResponseCompressor.disabledInstance(), reset.getResponseCompressor());
		for (DefaultHttpServer server : List.of(none, reset)) {
			MicrohttpResponse result = server.toMicrohttpResponse(requestAccepting("gzip"), null, response(body));
			Assertions.assertArrayEquals(body, result.body());
			Assertions.assertFalse(result.hasHeader("Content-Encoding"));
			Assertions.assertEquals(server == none ? "Accept-Encoding" : null, headerValue(result, "Vary"));
		}
		MicrohttpResponse identityForbidden = none.toMicrohttpResponse(
				requestAccepting("identity;q=0"), null, response(body));
		Assertions.assertEquals(406, identityForbidden.status());
		Assertions.assertEquals(0, identityForbidden.body().length);
	}

	@Test
	public void compressionSupplierRejectsUseOnAnotherThreadOrAfterProviderReturns() {
		AtomicReference<Supplier<byte[]>> escapedSupplier = new AtomicReference<>();
		AtomicInteger compressionCalls = new AtomicInteger();
		ResponseCompressionCodec codec = codec("gzip", body -> {
			compressionCalls.incrementAndGet();
			return ResponseCompressionCodec.gzipInstance().compress(body);
		});
		DefaultHttpServer server = server((request, marshaledResponse) ->
				ResponseCompressionPlan.compress(codec, compressedBodySupplier -> {
					escapedSupplier.set(compressedBodySupplier);
					CompletableFuture.runAsync(() -> Assertions.assertThrows(IllegalStateException.class,
							compressedBodySupplier::get)).join();
					return compressedBodySupplier.get();
				}));

		server.toMicrohttpResponse(requestAccepting("gzip"), null, response("body".getBytes(StandardCharsets.UTF_8)));

		Assertions.assertThrows(IllegalStateException.class, () -> escapedSupplier.get().get());
		Assertions.assertEquals(1, compressionCalls.get());
	}

	@Test
	public void notAcceptableResponsePreservesCorsAndCachePolicyHeaders() {
		DefaultHttpServer server = server((request, marshaledResponse) ->
				ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance()));
		MarshaledResponse original = response("private data".getBytes(StandardCharsets.UTF_8)).copy()
				.headers(headers -> {
					headers.put("Access-Control-Allow-Origin", Set.of("https://client.example"));
					headers.put("Access-Control-Allow-Credentials", Set.of("true"));
					headers.put("Cache-Control", Set.of("private, no-store"));
					headers.put("Vary", Set.of("Origin"));
				})
				.finish();

		MicrohttpResponse response = server.toMicrohttpResponse(
				requestAccepting("gzip;q=0, identity;q=0"), null, original);

		Assertions.assertEquals(406, response.status());
		Assertions.assertEquals(0, response.bodyLength());
		Assertions.assertEquals("https://client.example", headerValue(response, "Access-Control-Allow-Origin"));
		Assertions.assertEquals("true", headerValue(response, "Access-Control-Allow-Credentials"));
		Assertions.assertEquals("private, no-store", headerValue(response, "Cache-Control"));
		Assertions.assertEquals("Origin, Accept-Encoding", headerValue(response, "Vary"));
		Assertions.assertNotEquals("12", headerValue(response, "Content-Length"));
	}

	@Test
	public void protocolExclusionsNeverInvokeCompressor() {
		DefaultHttpServer server = server((request, marshaledResponse) -> {
			throw new AssertionError("An excluded response must not invoke the compressor");
		});
		byte[] body = "body".getBytes(StandardCharsets.UTF_8);
		List<MarshaledResponse> excluded = List.of(
				MarshaledResponse.withStatusCode(200).build(),
				response(new byte[0]),
				MarshaledResponse.withStatusCode(103).build(),
				MarshaledResponse.withStatusCode(204).build(),
				MarshaledResponse.withStatusCode(206).body(body).build(),
				MarshaledResponse.withStatusCode(304).build(),
				response(body).copy().headers(Map.of("Content-Encoding", Set.of("br"))).finish(),
				response(body).copy().headers(Map.of("Content-Range", Set.of("bytes 0-3/4"))).finish(),
				response(body).copy().headers(Map.of("Transfer-Encoding", Set.of("chunked"))).finish());
		for (MarshaledResponse response : excluded)
			Assertions.assertDoesNotThrow(() -> server.toMicrohttpResponse(requestAccepting("gzip"), null, response));
		Assertions.assertDoesNotThrow(() -> server.toMicrohttpResponse(response(body)));
	}

	@Test
	public void planRejectsInvalidAndReservedContentCodingsBeforeInvokingCodec() {
		for (String coding : List.of("", " ", " gzip", "gzip ", "identity", "IDENTITY", "*", "gzip, br", "gzip; q=1", "gzip\r\nX-Test: yes")) {
			ResponseCompressionCodec codec = codec(coding, body -> {
				throw new AssertionError("Invalid codec must not run");
			});
			Assertions.assertThrows(IllegalArgumentException.class,
					() -> ResponseCompressionPlan.compress(codec), coding);
		}
	}

	@Test
	public void nullPlansAndNullCompressedBodiesFailInsteadOfSendingIncorrectContentEncoding() {
		Request request = requestAccepting("gzip");
		MarshaledResponse response = response("body".getBytes(StandardCharsets.UTF_8));
		DefaultHttpServer nullPlan = server((req, marshaledResponse) -> null);
		DefaultHttpServer nullCodecBody = server((req, marshaledResponse) ->
				ResponseCompressionPlan.compress(codec("gzip", body -> null)));
		DefaultHttpServer nullProviderBody = server((req, marshaledResponse) ->
				ResponseCompressionPlan.compress(ResponseCompressionCodec.gzipInstance(), supplier -> null));
		for (DefaultHttpServer server : List.of(nullPlan, nullCodecBody, nullProviderBody))
			Assertions.assertThrows(NullPointerException.class, () -> server.toMicrohttpResponse(request, null, response));
	}

	@Test
	public void providerFailurePropagatesWithoutInvokingCodec() {
		RuntimeException failure = new IllegalStateException("cache unavailable");
		ResponseCompressionCodec codec = codec("gzip", body -> {
			throw new AssertionError("Failed provider must not invoke codec");
		});
		DefaultHttpServer server = server((request, marshaledResponse) ->
				ResponseCompressionPlan.compress(codec, supplier -> { throw failure; }));
		Assertions.assertSame(failure, Assertions.assertThrows(IllegalStateException.class, () ->
				server.toMicrohttpResponse(requestAccepting("gzip"), null,
						response("body".getBytes(StandardCharsets.UTF_8)))));
	}

	private static DefaultHttpServer server(ResponseCompressor compressor) {
		return (DefaultHttpServer) HttpServer.withPort(0).responseCompressor(compressor).build();
	}

	private static Request requestAccepting(String contentEncoding) {
		return Request.withPath(HttpMethod.GET, "/compression")
				.headers(Map.of("Accept-Encoding", Set.of(contentEncoding))).build();
	}

	private static MarshaledResponse response(byte[] body) {
		return MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain"), "Content-Length", Set.of(Integer.toString(body.length))))
				.body(body).build();
	}

	private static ResponseCompressionCodec codec(String contentEncoding, Function<ByteBuffer, byte[]> compress) {
		return new ResponseCompressionCodec() {
			@Override
			public String getContentEncoding() { return contentEncoding; }

			@Override
			public byte[] compress(ByteBuffer uncompressedBody) { return compress.apply(uncompressedBody); }
		};
	}

	private static byte[] gunzip(byte[] bytes) throws IOException {
		try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return input.readAllBytes();
		}
	}

	private static String headerValue(MicrohttpResponse response, String name) {
		return response.headers().stream().filter(header -> header.name().equalsIgnoreCase(name))
				.map(Header::value).findFirst().orElse(null);
	}
}
