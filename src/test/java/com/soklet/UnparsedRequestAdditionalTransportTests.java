/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static org.junit.jupiter.api.Assertions.*;

/** Real-wire coverage of rejections outside the original four parser reasons. */
@Timeout(30)
class UnparsedRequestAdditionalTransportTests {

	@TestFactory
	Stream<DynamicTest> httpRejectionsBeforeRequestConstructionAreCustomizable() throws Exception {
		return Stream.of(
				new Case("header timeout", "REQUEST_READ_TIMEOUT", "GET /ok HTTP/1.1\r\nHost: localhost\r\n", 4096, 1024, 8192),
				new Case("body timeout", "REQUEST_READ_TIMEOUT", "POST /ok HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nabc", 4096, 1024, 8192),
				new Case("trailer timeout", "REQUEST_READ_TIMEOUT", "POST /ok HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nX-Secret: trailer-canary", 4096, 1024, 8192),
				new Case("aggregate before request line", "REQUEST_TOO_LARGE", "GET /" + "x".repeat(128), 64, 1024, 8192),
				new Case("invalid UTF-8 query", "MALFORMED_REQUEST", "GET /ok?token=%C0%AF HTTP/1.1\r\nHost: localhost\r\n\r\n", 4096, 1024, 8192),
				new Case("unsupported method", "MALFORMED_REQUEST", "UNKNOWN /ok HTTP/1.1\r\nHost: localhost\r\n\r\n", 4096, 1024, 8192),
				new Case("unsupported coding", "UNSUPPORTED_CONTENT_ENCODING", "POST /ok HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: br\r\nContent-Length: 1\r\n\r\nx", 4096, 1024, 8192),
				new Case("malformed gzip", "REQUEST_BODY_DECOMPRESSION_FAILED", "POST /ok HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: 1\r\n\r\nx", 4096, 1024, 8192)
		).map(c -> DynamicTest.dynamicTest(c.name(), () -> assertCustomized(ServerType.HTTP, c)));
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> ssePreRequestRejectionsUseTheSameHook() {
		return Stream.of(
				new Case("malformed", "MALFORMED_REQUEST", "GET /events HTTP/1.1\r\nHost: localhost\r\nBroken-Header\r\n\r\n", 4096, 1024, 8192),
				new Case("unsupported expectation", "MALFORMED_REQUEST", "GET /events HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\n\r\n", 4096, 1024, 8192),
				new Case("header count", "REQUEST_HEADERS_TOO_LARGE", "GET /events HTTP/1.1\r\nHost: localhost\r\nX-A: a\r\nX-B: b\r\n\r\n", 4096, 1024, 8192),
				new Case("header bytes", "REQUEST_HEADERS_TOO_LARGE", "GET /events HTTP/1.1\r\nHost: localhost\r\nX-A: " + "x".repeat(80) + "\r\n\r\n", 4096, 40, 8192),
				new Case("target", "REQUEST_TARGET_TOO_LONG", "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n", 4096, 1024, 3),
				new Case("aggregate before request line", "REQUEST_TOO_LARGE", "GET /" + "x".repeat(128), 64, 1024, 8192),
				new Case("partial timeout", "REQUEST_READ_TIMEOUT", "GET /events HTTP/1.1\r\nHost: localhost\r\n", 4096, 1024, 8192)
		).map(c -> DynamicTest.dynamicTest(c.name(), () -> assertCustomized(ServerType.SSE, c)));
	}

	private void assertCustomized(ServerType serverType, Case c) throws Exception {
		try (Fixture fixture = new Fixture(serverType, c, request -> custom(request)); Socket socket = fixture.open()) {
			fixture.send(socket, c.input());
			String response = readResponse(socket);
			assertTrue(response.startsWith("HTTP/1.1 498 "), response);
			assertTrue(response.contains("X-Unparsed-Reason: " + c.reason() + "\r\n"), response);
			assertTrue(response.contains("X-Postprocessed: true\r\n"), response);
			assertTrue(response.contains("Connection: close\r\n"), response);
			assertFalse(response.contains("X-Hop:"), response);
			assertFalse(response.contains("Transfer-Encoding:"), response);
			assertFalse(response.contains("Content-Length: 999\r\n"), response);
			assertEquals(List.of("observer", "marshaler"), fixture.order);
			assertEquals(1, fixture.requests.size());
			UnparsedRequest request = fixture.requests.get(0);
			assertEquals(serverType, request.getServerType());
			assertEquals(c.reason(), request.getReason().name());
			assertEquals(0, fixture.resource.calls.get());
			assertTrue(fixture.threads.stream().noneMatch(t -> t.contains("event-loop") || t.contains("timeout")), fixture.threads.toString());
			if (c.name().equals("invalid UTF-8 query") || c.name().equals("unsupported method") || c.name().contains("coding") || c.name().contains("gzip")) {
				assertEquals(0, request.getCapturedBytes().remaining());
				assertEquals((long)c.input().length(), request.getObservedByteCount());
				assertTrue(request.isCaptureTruncated());
			} else {
				String capture = captured(request);
				assertTrue(c.input().startsWith(capture), capture);
				assertTrue(request.getObservedByteCount() > 0);
			}
			assertFalse(request.toString().contains("canary"));
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void sseLfHeaderBoundaryExcludesFollowingCrLfRequest() throws Exception {
		String rejected = "GET /events HTTP/1.1\nHost: localhost\nBroken-Header\n\n";
		Case c = new Case("malformed LF", "MALFORMED_REQUEST", rejected, 4096, 1024, 8192);
		try (Fixture fixture = new Fixture(ServerType.SSE, c, UnparsedRequestAdditionalTransportTests::custom); Socket socket = fixture.open()) {
			fixture.send(socket, rejected + "GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
			assertTrue(readResponse(socket).startsWith("HTTP/1.1 498 "));
			UnparsedRequest request = fixture.requests.get(0);
			assertEquals(rejected, captured(request));
			assertEquals((long)rejected.length(), request.getObservedByteCount());
			assertFalse(request.isCaptureTruncated());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void uncooperativeSseUnparsedHandlerPreventsCompleteShutdownUntilItActuallyReturns() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		Case c = new Case("malformed", "MALFORMED_REQUEST", "GET /events HTTP/1.1\r\nHost: localhost\r\nBroken-Header\r\n\r\n", 4096, 1024, 8192);
		try (Fixture fixture = new Fixture(ServerType.SSE, c, request -> {
			entered.countDown();
			while (release.getCount() > 0) {
				try { release.await(); } catch (InterruptedException ignored) { /* Deliberately uncooperative. */ }
			}
			return custom(request);
		}); Socket socket = fixture.open()) {
			try {
				fixture.send(socket, c.input());
				assertTrue(entered.await(2, TimeUnit.SECONDS));
				assertTrue(readResponse(socket).startsWith("HTTP/1.1 400 "));
				fixture.expectComplete = false;
				assertThrows(SokletShutdownIncompleteException.class, fixture.app::close);
				assertFalse(fixture.app.getShutdownResult().orElseThrow().isComplete());
				assertFalse(fixture.handlerExecutor.isTerminated());
			} finally {
				release.countDown();
				try { fixture.app.close(); } catch (SokletShutdownIncompleteException expected) { /* Frozen result remains incomplete. */ }
				assertTrue(fixture.handlerExecutor.awaitTermination(2, TimeUnit.SECONDS));
			}
		} finally { release.countDown(); }
	}

	@Test
	void decodedSizeFailureKeepsTheExistingParsedRequestHook() throws Exception {
		byte[] gzip;
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
			try (GZIPOutputStream output = new GZIPOutputStream(bytes)) { output.write(new byte[100]); }
			gzip = bytes.toByteArray();
		}
		String input = "POST /ok HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: " + gzip.length + "\r\n\r\n" + new String(gzip, StandardCharsets.ISO_8859_1);
		Case c = new Case("decoded body too large", "", input, 4096, 1024, 8192);
		try (Fixture fixture = new Fixture(ServerType.HTTP, c, UnparsedRequestAdditionalTransportTests::custom); Socket socket = fixture.open()) {
			fixture.send(socket, input);
			assertTrue(readResponse(socket).startsWith("HTTP/1.1 497 "));
			assertEquals(1, fixture.parsedSizeRejections.get());
			assertTrue(fixture.requests.isEmpty());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void sseCaptureExcludesFollowingRequestAndCapsRetainedBytes() throws Exception {
		String rejected = "GET /events HTTP/1.1\r\nHost: localhost\r\nX-A: " + "a".repeat(70_000) + "\r\nBroken-Header\r\n\r\n";
		Case c = new Case("large malformed", "MALFORMED_REQUEST", rejected, 200_000, 100_000, 8192);
		try (Fixture fixture = new Fixture(ServerType.SSE, c, UnparsedRequestAdditionalTransportTests::custom); Socket socket = fixture.open()) {
			fixture.send(socket, rejected + "GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
			assertTrue(readResponse(socket).startsWith("HTTP/1.1 498 "));
			UnparsedRequest request = fixture.requests.get(0);
			assertEquals(64 * 1024, request.getCapturedBytes().remaining());
			assertEquals((long)rejected.length(), request.getObservedByteCount());
			assertTrue(request.isCaptureTruncated());
			assertFalse(captured(request).contains("following-secret"));
		}
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> sseInvalidOrFailedMarshalingUsesTheOriginalFallback() {
		List<Function<UnparsedRequest, MarshaledResponse>> handlers = List.of(
				request -> { throw new IllegalStateException("application failure"); },
				request -> null,
				request -> MarshaledResponse.withStatusCode(498).body(new byte[70_000]).build());
		return Stream.iterate(0, i -> i + 1).limit(handlers.size()).map(i -> DynamicTest.dynamicTest("fallback " + i, () -> {
			Case c = new Case("malformed", "MALFORMED_REQUEST", "GET /events HTTP/1.1\r\nHost: localhost\r\nBroken-Header\r\n\r\n", 4096, 1024, 8192);
			try (Fixture fixture = new Fixture(ServerType.SSE, c, handlers.get(i)); Socket socket = fixture.open()) {
				fixture.send(socket, c.input());
				assertTrue(readResponse(socket).startsWith("HTTP/1.1 400 "));
				assertEquals(1, fixture.requests.size());
				assertEquals(1, fixture.logs.stream().filter(e -> e.getLogEventType() == LogEventType.RESPONSE_MARSHALER_FOR_UNPARSED_REQUEST_FAILED).count());
			}
		}));
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void stalledSseMarshalerReceivesInterruptionAndCannotWriteALateResponse() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
		Case c = new Case("timeout", "REQUEST_READ_TIMEOUT", "GET /events HTTP/1.1\r\nHost: localhost\r\n", 4096, 1024, 8192);
		try (Fixture fixture = new Fixture(ServerType.SSE, c, request -> {
			entered.countDown();
			try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
			return custom(request);
		}); Socket socket = fixture.open()) {
			fixture.send(socket, c.input());
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			String response = readResponse(socket);
			assertTrue(response.startsWith("HTTP/1.1 408 "), response);
			assertFalse(response.contains("498"), response);
			assertTrue(interrupted.await(2, TimeUnit.SECONDS));
		} finally { release.countDown(); }
	}

	@Test
	void laterHttpConstructionFailureUsesItsOriginalRejectionWhenMarshalingTimesOut() throws Exception {
		CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
		Case c = new Case("invalid UTF-8 query", "MALFORMED_REQUEST", "GET /ok?token=%C0%AF HTTP/1.1\r\nHost: localhost\r\n\r\n", 4096, 1024, 8192);
		try (Fixture fixture = new Fixture(ServerType.HTTP, c, request -> {
			entered.countDown();
			try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
			return custom(request);
		}); Socket socket = fixture.open()) {
			fixture.send(socket, c.input());
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			String response = readResponse(socket);
			assertTrue(response.startsWith("HTTP/1.1 400 "), response);
			assertFalse(response.contains("498"), response);
			assertTrue(interrupted.await(2, TimeUnit.SECONDS));
			assertEquals(List.of("observer", "marshaler"), fixture.order);
		} finally { release.countDown(); }
	}

	private record Case(String name, String reason, String input, int aggregateLimit, int headerLimit, int targetLimit) {}
	private static MarshaledResponse custom(UnparsedRequest request) {
		return MarshaledResponse.withStatusCode(498).headers(Map.of(
				"X-Unparsed-Reason", List.of(request.getReason().name()),
				"Connection", List.of("keep-alive, X-Hop"), "X-Hop", List.of("secret"),
				"Transfer-Encoding", List.of("chunked"), "Content-Length", List.of("999")))
				.body(("custom-" + request.getReason()).getBytes(StandardCharsets.US_ASCII)).build();
	}
	private static String captured(UnparsedRequest request) {
		ByteBuffer buffer = request.getCapturedBytes(); byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}
	private static String readResponse(Socket socket) throws Exception {
		InputStream input = socket.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int value; while ((value = input.read()) >= 0) {
			bytes.write(value);
			String response = bytes.toString(StandardCharsets.ISO_8859_1);
			int end = response.indexOf("\r\n\r\n");
			if (end < 0) continue;
			String header = response.substring(0, end);
			String length = header.lines().filter(s -> s.regionMatches(true, 0, "Content-Length:", 0, 15)).findFirst().orElse("Content-Length: 0");
			int bodyLength = Integer.parseInt(length.substring(15).trim());
			if (bytes.size() == end + 4 + bodyLength) return response;
		}
		return bytes.toString(StandardCharsets.ISO_8859_1);
	}
	private static final class Fixture implements AutoCloseable {
		final int port = findFreePort();
		final Resource resource;
		final AtomicInteger parsedSizeRejections = new AtomicInteger();
		final List<UnparsedRequest> requests = new CopyOnWriteArrayList<>();
		final List<String> order = new CopyOnWriteArrayList<>(), threads = new CopyOnWriteArrayList<>();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final Soklet app;
		final ExecutorService handlerExecutor;
		boolean expectComplete = true;
		Fixture(ServerType type, Case c, Function<UnparsedRequest, MarshaledResponse> handler) throws Exception {
			resource = type == ServerType.SSE ? new SseResource() : new HttpResource();
			SokletConfig.Builder builder;
			if (type == ServerType.SSE) {
				builder = SokletConfig.withSseServer(SseServer.withPort(port).host("127.0.0.1")
						.requestHeaderTimeout(Duration.ofMillis(200)).requestHandlerTimeout(Duration.ofMillis(500))
						.maximumRequestSizeInBytes(c.aggregateLimit()).maximumHeadersSizeInBytes(c.headerLimit())
						.maximumHeaderCount(c.name().equals("header count") ? 2 : 64).maximumRequestTargetLengthInBytes(c.targetLimit()).build());
			} else {
				builder = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1")
						.requestHeaderTimeout(Duration.ofMillis(200)).requestBodyTimeout(Duration.ofMillis(200))
						.requestHandlerTimeout(Duration.ofMillis(500)).maximumRequestSizeInBytes(c.aggregateLimit())
						.maximumHeadersSizeInBytes(c.headerLimit()).maximumRequestTargetLengthInBytes(c.targetLimit())
						.requestDecompressionPolicy(RequestDecompressionPolicy.builder().maximumDecompressedBodySizeInBytes(10).build()).build());
			}
			app = Soklet.fromConfig(builder.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(resource.getClass())))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) { return type == resource.getClass() ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type); }
					}).lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(300)).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didRejectUnparsedRequest(UnparsedRequest request) { requests.add(request); order.add("observer"); threads.add(Thread.currentThread().getName()); }
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
					}).responseMarshaler(ResponseMarshaler.builder().unparsedRequestHandler(request -> {
						order.add("marshaler"); threads.add(Thread.currentThread().getName()); return handler.apply(request);
					}).contentTooLargeHandler((request, resourceMethod) -> {
						parsedSizeRejections.incrementAndGet(); return MarshaledResponse.fromStatusCode(497);
					}).postProcessor(response -> response.copy().headers(headers -> headers.put("X-Postprocessed", List.of("true"))).finish()).build()).build());
			app.start();
			handlerExecutor = type == ServerType.SSE
					? ((DefaultSseServer)app.getSokletConfig().getSseServer().orElseThrow()).getRequestHandlerExecutorService().orElseThrow()
					: ((DefaultHttpServer)app.getSokletConfig().getHttpServer().orElseThrow()).getRequestHandlerExecutorService().orElseThrow();
		}
		Socket open() throws Exception { Socket socket = connectWithRetry("127.0.0.1", port, 2000); socket.setSoTimeout(3000); return socket; }
		void send(Socket socket, String input) throws Exception { socket.getOutputStream().write(input.getBytes(StandardCharsets.ISO_8859_1)); socket.getOutputStream().flush(); }
		@Override public void close() {
			try { app.close(); }
			catch (SokletShutdownIncompleteException expected) { if (expectComplete) throw expected; }
			assertEquals(expectComplete, app.getShutdownResult().orElseThrow().isComplete());
		}
	}
	public static class Resource {
		final AtomicInteger calls = new AtomicInteger();
	}
	public static final class HttpResource extends Resource {
		@GET("/ok") public Response ok() { calls.incrementAndGet(); return Response.fromStatusCode(200); }
	}
	public static final class SseResource extends Resource {
		@SseEventSource("/events") public SseHandshakeResult events() { calls.incrementAndGet(); return SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(200)); }
	}
}
