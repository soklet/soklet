package com.soklet;

import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static com.soklet.TestSupport.readAll;

public class DefaultHttpServerTests {
	@Test
	public void defaultConcurrentConnectionLimitIsBoundedAndCanBeDisabled() {
		DefaultHttpServer defaultServer = (DefaultHttpServer) HttpServer.withPort(0).build();
		DefaultHttpServer disabledServer = (DefaultHttpServer) HttpServer.withPort(0)
				.concurrentConnectionLimit(0)
				.build();

		Assertions.assertEquals(8_192, defaultServer.getConcurrentConnectionLimit());
		Assertions.assertEquals(0, disabledServer.getConcurrentConnectionLimit());
	}

	@Test
	public void maximumHeadersSizeDefaultsCanBeCustomizedAndMustBePositive() {
		DefaultHttpServer defaultServer = (DefaultHttpServer) HttpServer.withPort(0).build();
		DefaultHttpServer customServer = (DefaultHttpServer) HttpServer.withPort(0)
				.maximumHeadersSizeInBytes(1_024)
				.build();

		Assertions.assertEquals(64 * 1_024, defaultServer.getMaximumHeadersSizeInBytes());
		Assertions.assertEquals(1_024, customServer.getMaximumHeadersSizeInBytes());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				HttpServer.withPort(0).maximumHeadersSizeInBytes(0).build());
	}

	@Test
	public void responseGzipPolicyDefaultsCanBeCustomizedAndFactoryMinimumMustBeNonNegative() {
		DefaultHttpServer defaultServer = (DefaultHttpServer) HttpServer.withPort(0).build();
		ResponseGzipPolicy responseGzipPolicy = ResponseGzipPolicy.fromDefaultsWithMinimumBodySizeInBytes(2_048);
		DefaultHttpServer customServer = (DefaultHttpServer) HttpServer.withPort(0)
				.responseGzipPolicy(responseGzipPolicy)
				.build();

		Assertions.assertSame(ResponseGzipPolicy.disabledInstance(), defaultServer.getResponseGzipPolicy());
		Assertions.assertSame(responseGzipPolicy, customServer.getResponseGzipPolicy());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ResponseGzipPolicy.fromDefaultsWithMinimumBodySizeInBytes(-1));
	}

	@Test
	public void defaultResponseGzipPolicyHonorsMinimumBodySizeAndCompressibleContentTypes() {
		Request request = Request.withPath(HttpMethod.GET, "/large").build();
		ResponseGzipPolicy responseGzipPolicy = ResponseGzipPolicy.fromDefaultsWithMinimumBodySizeInBytes(8);
		MarshaledResponse jsonResponse = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("Application/Problem+JSON; charset=UTF-8")))
				.body("long enough".getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.build();
		MarshaledResponse octetStreamResponse = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("application/octet-stream")))
				.body("long enough".getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.build();
		MarshaledResponse smallTextResponse = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain")))
				.body("short".getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.build();

		Assertions.assertTrue(responseGzipPolicy.shouldGzip(request, jsonResponse));
		Assertions.assertFalse(responseGzipPolicy.shouldGzip(request, octetStreamResponse));
		Assertions.assertFalse(responseGzipPolicy.shouldGzip(request, smallTextResponse));
	}

	@Test
	public void toMicrohttpResponseAppliesResponseGzipToEligibleByteArrayBody() throws IOException {
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.responseGzipPolicy((request, response) -> true)
				.build();
		byte[] body = "abcdefghijklmnopqrstuvwxyz".repeat(64).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Request request = Request.withPath(HttpMethod.GET, "/large")
				.headers(Map.of("Accept-Encoding", Set.of("br;q=1, gzip;q=0.8")))
				.build();
		MarshaledResponse marshaledResponse = MarshaledResponse.withStatusCode(200)
				.headers(Map.of("Content-Type", Set.of("text/plain")))
				.body(body)
				.build();

		MicrohttpResponse microhttpResponse = server.toMicrohttpResponse(request, null, marshaledResponse);

		Assertions.assertTrue(microhttpResponse.hasHeader("Content-Encoding"));
		Assertions.assertArrayEquals(body, gunzip(microhttpResponse.body()));
	}

	@Test
	public void headersFromMicrohttpRequestPreservesNormalizedHeaderBehavior() {
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		MicrohttpRequest microhttpRequest = new MicrohttpRequest(
				"GET",
				"/",
				"HTTP/1.1",
				List.of(
						new Header("Cache-Control", "no-cache, no-store"),
						new Header("Set-Cookie", "session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"),
						new Header("X-Empty", "   "),
						new Header("X-Trace-Id", " abc123 ")),
				new byte[0],
				false,
				new InetSocketAddress("127.0.0.1", 12345));

		Map<String, Set<String>> headers = server.headersFromMicrohttpRequest(microhttpRequest);

		Assertions.assertEquals(new LinkedHashSet<>(List.of("no-cache", "no-store")), headers.get("Cache-Control"));
		Assertions.assertEquals(
				List.of("session=xyz; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Path=/"),
				new ArrayList<>(headers.get("Set-Cookie")));
		Assertions.assertEquals(Set.of("abc123"), headers.get("x-trace-id"));
		Assertions.assertFalse(headers.containsKey("X-Empty"));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> headers.put("X-Test", Set.of("value")));
		Assertions.assertThrows(UnsupportedOperationException.class, () -> headers.get("X-Trace-Id").add("def456"));
	}

	private static byte[] gunzip(byte[] bytes) throws IOException {
		try (GZIPInputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return readAll(inputStream);
		}
	}
}
