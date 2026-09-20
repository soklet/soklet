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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static java.nio.file.StandardOpenOption.READ;

class UnparsedRequestTransportTests {
	private static final int CAPTURE_LIMIT_IN_BYTES = 64 * 1_024;
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancelationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownTimeout(Duration.ofSeconds(2))
					.forcedShutdownTimeout(Duration.ofSeconds(1))
					.build();

	@Test
	void directlySerializedUnparsedResponsesAddDateAndPreserveExplicitDate() throws Exception {
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		java.lang.reflect.Method serializer = DefaultHttpServer.class.getDeclaredMethod(
				"serializeUnparsedRequestResponse", MarshaledResponse.class);
		serializer.setAccessible(true);
		String explicitDate = "Thu, 01 Jan 1970 00:00:00 GMT";
		for (boolean explicit : List.of(false, true)) {
			MarshaledResponse response = MarshaledResponse.withStatusCode(400)
					.headers(explicit ? Map.of("dAtE", Set.of(explicitDate)) : Map.of()).build();
			String wire = new String((byte[]) serializer.invoke(server, response), StandardCharsets.ISO_8859_1);
			List<String> values = wire.substring(0, wire.indexOf("\r\n\r\n")).lines()
					.filter(line -> line.regionMatches(true, 0, "Date:", 0, 5))
					.map(line -> line.substring(5).trim()).toList();
			Assertions.assertEquals(1, values.size(), wire);
			if (explicit) {
				Assertions.assertEquals(explicitDate, values.get(0));
			} else {
				java.time.Instant date = HttpDate.fromHeaderValue(values.get(0)).orElseThrow();
				Assertions.assertTrue(Math.abs(Duration.between(date, java.time.Instant.now()).toSeconds()) <= 2, wire);
			}
		}
	}

	@Test
	void pipelinedFragmentedHeaderLimitRejectsOnlyTheOffendingRequest()
			throws Exception {
		assertPipelinedSectionRejected("GET /bad-request HTTP/1.1\r\nHost: a\r\nX: ",
				"a".repeat(25) + "\r\n", 32);
	}

	@Test
	void pipelinedFragmentedTrailerLimitRejectsOnlyTheOffendingRequest()
			throws Exception {
		assertPipelinedSectionRejected("POST /bad-request HTTP/1.1\r\nHost: a\r\n"
				+ "Transfer-Encoding: chunked\r\n\r\n0\r\nX: ",
				"a".repeat(60) + "\r\n", 64);
	}

	@Test
	void invalidContentLengthsUseTheMalformedRequestObserverAndResponsePath()
			throws Exception {
		int port = findFreePort();
		List<UnparsedRequest> rejectedRequests = new CopyOnWriteArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(@NonNull UnparsedRequest request) {
				rejectedRequests.add(request);
			}
		};
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(observer)
				.build();
		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			for (String value : List.of("+1", "-0", "9223372036854775808")) {
				String safePrefix = "POST /bad-request HTTP/1.1\r\nHost: a\r\nContent-Length: "
						+ value + "\r\n";
				RawResponse response = exchange(port, safePrefix + "\r\nx"
						+ "GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n");
				Assertions.assertEquals("HTTP/1.1 400 Bad Request", response.statusLine());
				Assertions.assertEquals("close", response.headers().get("connection"));
				UnparsedRequest rejected = rejectedRequests.get(rejectedRequests.size() - 1);
				Assertions.assertEquals(UnparsedRequestReason.MALFORMED_REQUEST, rejected.getReason());
				Assertions.assertArrayEquals(safePrefix.getBytes(StandardCharsets.US_ASCII),
						remainingBytes(rejected.getCapturedBytes()));
				Assertions.assertEquals(safePrefix.length(), rejected.getObservedByteCount());
			}
		}
		Assertions.assertEquals(3, rejectedRequests.size());
	}

	private static void assertPipelinedSectionRejected(@NonNull String prefix,
			@NonNull String offendingLineSuffix, int maximumHeadersSize) throws Exception {
		int port = findFreePort();
		List<UnparsedRequest> rejectedRequests = new CopyOnWriteArrayList<>();
		AtomicInteger marshaledRejections = new AtomicInteger();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(@NonNull UnparsedRequest request) {
				rejectedRequests.add(request);
			}
		};
		String firstRequest = "GET /hello HTTP/1.1\r\nHost: a\r\n\r\n";
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.maximumHeadersSizeInBytes(maximumHeadersSize)
						.requestReadBufferSizeInBytes(firstRequest.length() + prefix.length())
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(observer)
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							marshaledRejections.incrementAndGet();
							return MarshaledResponse.withStatusCode(431)
									.headers(Map.of("X-Unparsed-Reason", Set.of(request.getReason().name())))
									.build();
						})
						.build())
				.build();
		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000)) {
				socket.setSoTimeout(4_000);
				OutputStream output = socket.getOutputStream();
				InputStream input = socket.getInputStream();
				output.write((firstRequest + prefix).getBytes(StandardCharsets.US_ASCII));
				output.flush();
				Assertions.assertEquals("HTTP/1.1 200 OK", readResponse(input).statusLine());
				output.write((offendingLineSuffix + "\r\n"
						+ "GET /following-secret HTTP/1.1\r\nHost: secret\r\n\r\n")
						.getBytes(StandardCharsets.US_ASCII));
				output.flush();
				RawResponse rejection = readResponse(input);
				Assertions.assertEquals("HTTP/1.1 431 Request Header Fields Too Large", rejection.statusLine());
				Assertions.assertEquals("REQUEST_HEADERS_TOO_LARGE",
						rejection.headers().get("x-unparsed-reason"));
				Assertions.assertEquals("close", rejection.headers().get("connection"));
				try {
					Assertions.assertEquals(-1, input.read());
				} catch (SocketException expected) {
					// Closing with unread pipelined bytes may reset the socket
					// after the complete rejection response has been delivered.
				}
			}
		}
		Assertions.assertEquals(1, rejectedRequests.size());
		Assertions.assertEquals(1, marshaledRejections.get());
		UnparsedRequest rejected = rejectedRequests.get(0);
		Assertions.assertEquals(UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE, rejected.getReason());
		String captured = new String(remainingBytes(rejected.getCapturedBytes()), StandardCharsets.US_ASCII);
		// An over-limit incomplete line may be rejected before its CRLF arrives;
		// either way neither adjacent request belongs to this safe capture.
		Assertions.assertTrue(captured.startsWith(prefix), captured);
		Assertions.assertTrue((prefix + offendingLineSuffix).startsWith(captured), captured);
		Assertions.assertFalse(captured.contains("/hello"));
		Assertions.assertFalse(captured.contains("secret"));
		Assertions.assertEquals(captured.length(), rejected.getObservedByteCount());
		Assertions.assertFalse(rejected.isCaptureTruncated());
	}

	@Test
	void allParserRejectionsAreObservedAndMarshaledOffTheEventLoop()
			throws Exception {
		int port = findFreePort();
		List<String> order = new CopyOnWriteArrayList<>();
		List<String> callbackThreads = new CopyOnWriteArrayList<>();
		List<UnparsedRequest> requests = new CopyOnWriteArrayList<>();
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(@NonNull UnparsedRequest request) {
				requests.add(request);
				order.add("observer:" + request.getReason());
				callbackThreads.add("observer:" + Thread.currentThread().getName());
				throw new IllegalStateException("observer failure is contained");
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				logEvents.add(logEvent);
			}
		};
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					order.add("marshaler:" + request.getReason());
					callbackThreads.add("marshaler:" + Thread.currentThread().getName());
					byte[] body = ("custom-" + request.getReason())
							.getBytes(StandardCharsets.US_ASCII);
					return MarshaledResponse.withStatusCode(498)
							.headers(Map.of(
									"Connection", Set.of("keep-alive, X-Hop"),
									"Content-Length", Set.of("999"),
									"Keep-Alive", Set.of("timeout=5"),
									"Transfer-Encoding", Set.of("chunked"),
									"X-Hop", Set.of("secret"),
									"X-Unparsed-Reason", Set.of(request.getReason().name())))
							.body(body)
							.build();
				})
				.postProcessor(response -> response.copy()
						.headers(headers -> headers.put(
								"X-Postprocessed", Set.of("true")))
						.finish())
				.build();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.maximumHeaderCount(2)
						.maximumRequestTargetLengthInBytes(5)
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(observer)
				.responseMarshaler(responseMarshaler)
				.build();

		List<Scenario> scenarios = List.of(
				new Scenario(UnparsedRequestReason.MALFORMED_REQUEST,
						"BROKEN\r\n"
								+ "GET /pipelined-secret HTTP/1.1\r\n"
								+ "Authorization: boundary-secret\r\n\r\n",
						"BROKEN\r\n"),
				new Scenario(UnparsedRequestReason.MALFORMED_REQUEST,
						"GET /x HTTX"
								+ "GET /pipelined-secret HTTP/1.1\r\n"
								+ "Authorization: boundary-secret\r\n\r\n",
						"GET /x HTTX"),
				new Scenario(UnparsedRequestReason.MALFORMED_REQUEST,
						"POST /x HTTP/1.1\r\n"
								+ "Host: 127.0.0.1\r\n"
								+ "Transfer-Encoding: chunked\r\n\r\n"
								+ "1\r\naX"
								+ "GET /pipelined-secret HTTP/1.1\r\n"
								+ "Authorization: boundary-secret\r\n\r\n",
						"POST /x HTTP/1.1\r\n"
								+ "Host: 127.0.0.1\r\n"
								+ "Transfer-Encoding: chunked\r\n\r\n"
								+ "1\r\naX"),
				new Scenario(UnparsedRequestReason.REQUEST_TARGET_TOO_LONG,
						"GET /12345 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
						"GET /12345 "),
				new Scenario(UnparsedRequestReason.REQUEST_TARGET_TOO_LONG,
						"GET /12345"
								+ "TAIL-MUST-NOT-APPEAR".repeat(128),
						"GET /12345"),
				new Scenario(UnparsedRequestReason.EXPECTATION_FAILED,
						"POST /x HTTP/1.1\r\nHost: 127.0.0.1\r\n"
								+ "Expect: unsupported\r\n\r\nBODY-SECRET",
						"POST /x HTTP/1.1\r\nHost: 127.0.0.1\r\n"
								+ "Expect: unsupported\r\n\r\n"),
				new Scenario(UnparsedRequestReason.REQUEST_HEADERS_TOO_LARGE,
						"GET /x HTTP/1.1\r\nHost: 127.0.0.1\r\nX-One: 1\r\n"
								+ "X-Offending: 2\r\n\r\nGET /pipelined-secret HTTP/1.1\r\n\r\n",
						"GET /x HTTP/1.1\r\nHost: 127.0.0.1\r\nX-One: 1\r\n"
								+ "X-Offending: 2\r\n"));

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			for (Scenario scenario : scenarios) {
				RawResponse response = exchange(port, scenario.wireBytes());
				byte[] expectedBody = ("custom-" + scenario.reason())
						.getBytes(StandardCharsets.US_ASCII);
				Assertions.assertEquals("HTTP/1.1 498 Unknown", response.statusLine());
				Assertions.assertEquals("close", response.headers().get("connection"));
				Assertions.assertEquals(Integer.toString(expectedBody.length),
						response.headers().get("content-length"));
				Assertions.assertEquals("true",
						response.headers().get("x-postprocessed"));
				Assertions.assertEquals(scenario.reason().name(),
						response.headers().get("x-unparsed-reason"));
				Assertions.assertFalse(response.headers().containsKey("keep-alive"));
				Assertions.assertFalse(response.headers().containsKey("transfer-encoding"));
				Assertions.assertFalse(response.headers().containsKey("x-hop"));
				Assertions.assertArrayEquals(expectedBody, response.body());
			}
		}

		Assertions.assertEquals(scenarios.size(), requests.size());
		for (int index = 0; index < scenarios.size(); index++) {
			Scenario scenario = scenarios.get(index);
			UnparsedRequest request = requests.get(index);
			byte[] expectedCapture = scenario.expectedCapture()
					.getBytes(StandardCharsets.US_ASCII);
			Assertions.assertEquals(ServerType.HTTP,
					request.getServerType());
			Assertions.assertEquals(scenario.reason(), request.getReason());
			Assertions.assertTrue(request.getRemoteAddress().isPresent());
			Assertions.assertArrayEquals(expectedCapture,
					remainingBytes(request.getCapturedBytes()));
			Assertions.assertEquals((long) expectedCapture.length,
					request.getObservedByteCount());
			Assertions.assertFalse(request.isCaptureTruncated());
			Assertions.assertEquals("observer:" + scenario.reason(),
					order.get(index * 2));
			Assertions.assertEquals("marshaler:" + scenario.reason(),
					order.get(index * 2 + 1));
		}
		Assertions.assertEquals(scenarios.size() * 2, callbackThreads.size());
		Assertions.assertTrue(callbackThreads.stream()
				.noneMatch(thread -> thread.endsWith("connection-event-loop")));
		Assertions.assertEquals(scenarios.size(), logEvents.stream()
				.filter(event -> event.getLogEventType()
						== LogEventType.LIFECYCLE_OBSERVER_DID_REJECT_UNPARSED_REQUEST_FAILED)
				.count());
	}

	@Test
	void requestCaptureIsTruncatedAt64KiBAndReportsTheFullSafeBoundary()
			throws Exception {
		int port = findFreePort();
		AtomicReference<UnparsedRequest> capturedRequest = new AtomicReference<>();
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					capturedRequest.set(request);
					return MarshaledResponse.fromStatusCode(414);
				})
				.build();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.maximumRequestTargetLengthInBytes(CAPTURE_LIMIT_IN_BYTES - 1)
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.responseMarshaler(responseMarshaler)
				.build();
		String target = "/" + "a".repeat(70_000);
		String requestLinePrefix = "GET " + target + " ";

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			RawResponse response = exchange(port,
					requestLinePrefix + "HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
			Assertions.assertEquals("HTTP/1.1 414 URI Too Long",
					response.statusLine());
		}

		UnparsedRequest request = capturedRequest.get();
		Assertions.assertNotNull(request);
		byte[] captured = remainingBytes(request.getCapturedBytes());
		Assertions.assertEquals(CAPTURE_LIMIT_IN_BYTES, captured.length);
		Assertions.assertEquals((long) requestLinePrefix.length(),
				request.getObservedByteCount());
		Assertions.assertTrue(request.isCaptureTruncated());
		Assertions.assertArrayEquals(requestLinePrefix.substring(0,
				CAPTURE_LIMIT_IN_BYTES).getBytes(StandardCharsets.US_ASCII), captured);
	}

	@Test
	void parseableContentTooLargeStillUsesItsRequestAwareMarshalerPath()
			throws Exception {
		int port = findFreePort();
		AtomicInteger contentTooLargeInvocations = new AtomicInteger();
		AtomicInteger unparsedInvocations = new AtomicInteger();
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.contentTooLargeHandler((request, resourceMethod) -> {
					contentTooLargeInvocations.incrementAndGet();
					return MarshaledResponse.withStatusCode(419)
							.body("request-aware".getBytes(StandardCharsets.US_ASCII))
							.build();
				})
				.unparsedRequestHandler(request -> {
					unparsedInvocations.incrementAndGet();
					return MarshaledResponse.fromStatusCode(499);
				})
				.build();
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.maximumRequestSizeInBytes(4_096)
						.maximumRequestBodySizeInBytes(4)
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.responseMarshaler(responseMarshaler)
				.build();
		String request = "POST /body HTTP/1.1\r\nHost: 127.0.0.1\r\n"
				+ "Content-Length: 5\r\n\r\n";

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			RawResponse response = exchange(port, request);
			Assertions.assertEquals("HTTP/1.1 419 Unknown", response.statusLine());
			Assertions.assertEquals("request-aware",
					new String(response.body(), StandardCharsets.US_ASCII));
		}
		Assertions.assertEquals(1, contentTooLargeInvocations.get());
		Assertions.assertEquals(0, unparsedInvocations.get());
	}

	@Test
	void unsafeOrFailedApplicationResponsesUseTheBodylessBuiltInFallback(
			@org.junit.jupiter.api.io.TempDir Path temporaryDirectory)
			throws Exception {
		int port = findFreePort();
		AtomicInteger invocation = new AtomicInteger();
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		Path responseFile = Files.writeString(
				temporaryDirectory.resolve("unparsed-response.txt"), "unsafe");
		long responseFileSize = Files.size(responseFile);
		FileChannel responseChannel = FileChannel.open(responseFile, READ);
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> switch (invocation.getAndIncrement()) {
					case 0 -> throw new IllegalStateException("marshaler failed");
					case 1 -> MarshaledResponse.withStatusCode(200)
							.body(new byte[CAPTURE_LIMIT_IN_BYTES]).build();
					case 2 -> MarshaledResponse.withStatusCode(200)
							.streamingResponseBody(StreamingResponseBody.fromWriter(
									(output, context) -> {})).build();
					case 3 -> MarshaledResponse.withStatusCode(200)
							.body(responseFile).build();
					case 4 -> MarshaledResponse.withStatusCode(200)
							.body(responseChannel, 0L, responseFileSize, true)
							.build();
					case 5 -> MarshaledResponse.withStatusCode(204)
							.body("illegal".getBytes(StandardCharsets.US_ASCII)).build();
					case 6 -> MarshaledResponse.fromStatusCode(101);
					case 7 -> MarshaledResponse.fromStatusCode(600);
					case 8 -> MarshaledResponse.withStatusCode(205)
							.body("illegal".getBytes(StandardCharsets.US_ASCII)).build();
					default -> throw new AssertionError("unexpected invocation");
				})
				.build();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				logEvents.add(logEvent);
			}
		};
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(port)
						.requestHeaderTimeout(Duration.ofSeconds(5))
						.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(observer)
				.responseMarshaler(responseMarshaler)
				.build();

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			for (int index = 0; index < 9; index++) {
				RawResponse response = exchange(port, "GET /x HTTP/9.9\r\n");
				assertBodylessFallback(response, 400, "Bad Request");
			}
		}

		Assertions.assertEquals(9, invocation.get());
		Assertions.assertFalse(responseChannel.isOpen());
		Assertions.assertEquals(9, logEvents.stream()
				.filter(event -> event.getLogEventType()
						== LogEventType.RESPONSE_MARSHALER_FOR_UNPARSED_REQUEST_FAILED)
				.count());
	}

	@Test
	void blockingFailureLogDoesNotDelayFallbackOrLaterTimeout()
			throws Exception {
		int port = findFreePort();
		AtomicInteger invocations = new AtomicInteger();
		CountDownLatch failureLogEntered = new CountDownLatch(1);
		CountDownLatch releaseFailureLog = new CountDownLatch(1);
		CountDownLatch timeoutHandlerEntered = new CountDownLatch(1);
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					if (invocations.getAndIncrement() == 0)
						throw new IllegalStateException("marshaler failed");
					timeoutHandlerEntered.countDown();
					try {
						new CountDownLatch(1).await(2, TimeUnit.SECONDS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					return MarshaledResponse.fromStatusCode(499);
				})
				.build();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getLogEventType()
						!= LogEventType
						.RESPONSE_MARSHALER_FOR_UNPARSED_REQUEST_FAILED)
					return;
				failureLogEntered.countDown();
				try {
					releaseFailureLog.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		};
		SokletConfig config = SokletConfig.withHttpServer(
					HttpServer.withPort(port)
							.requestHandlerConcurrency(2)
							.requestHandlerTimeout(Duration.ofMillis(200))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(observer)
				.responseMarshaler(responseMarshaler)
				.build();

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			try {
				RawResponse failedResponse = exchange(port,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(failedResponse, 400, "Bad Request");
				Assertions.assertTrue(failureLogEntered.await(
						1, TimeUnit.SECONDS));

				RawResponse timedOutResponse = exchange(port,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(timedOutResponse, 400, "Bad Request");
				Assertions.assertTrue(timeoutHandlerEntered.await(
						1, TimeUnit.SECONDS));
			} finally {
				releaseFailureLog.countDown();
			}
		}
		Assertions.assertEquals(2, invocations.get());
	}

	@Test
	void timeoutsAndUnsafeExecutorDispatchUseTheBuiltInFallback()
			throws Exception {
		int timeoutPort = findFreePort();
		CountDownLatch handlerEntered = new CountDownLatch(2);
		CountDownLatch timeoutLogEntered = new CountDownLatch(1);
		CountDownLatch releaseTimeoutLog = new CountDownLatch(1);
		AtomicReference<String> handlerThread = new AtomicReference<>();
		ResponseMarshaler timeoutMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					handlerThread.set(Thread.currentThread().getName());
					handlerEntered.countDown();
					try {
						new CountDownLatch(1).await(2, TimeUnit.SECONDS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					return MarshaledResponse.fromStatusCode(499);
				})
				.build();
		LifecycleObserver timeoutObserver = new LifecycleObserver() {
			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				if (logEvent.getThrowable()
						.filter(java.util.concurrent.TimeoutException.class::isInstance)
						.isEmpty())
					return;
				timeoutLogEntered.countDown();
				try {
					releaseTimeoutLog.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		};
		SokletConfig timeoutConfig = SokletConfig.withHttpServer(
					HttpServer.withPort(timeoutPort)
							.requestHandlerTimeout(Duration.ofMillis(200))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(timeoutObserver)
				.responseMarshaler(timeoutMarshaler)
				.build();

		try (Soklet app = Soklet.fromConfig(timeoutConfig)) {
			app.start();
			try {
				RawResponse firstResponse = exchange(timeoutPort,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(firstResponse, 400, "Bad Request");
				RawResponse secondResponse = exchange(timeoutPort,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(secondResponse, 400, "Bad Request");
				Assertions.assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
			} finally {
				releaseTimeoutLog.countDown();
			}
		}
		Assertions.assertNotNull(handlerThread.get());
		Assertions.assertNotEquals("connection-event-loop", handlerThread.get());
		Assertions.assertEquals(1, timeoutLogEntered.getCount());

		int observerTimeoutPort = findFreePort();
		CountDownLatch observerEntered = new CountDownLatch(1);
		AtomicInteger observerTimeoutMarshalerInvocations = new AtomicInteger();
		List<LogEvent> observerTimeoutLogEvents = new CopyOnWriteArrayList<>();
		LifecycleObserver blockingObserver = new LifecycleObserver() {
			@Override
			public void didRejectUnparsedRequest(
					@NonNull UnparsedRequest request) {
				observerEntered.countDown();
				try {
					new CountDownLatch(1).await(2, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}

			@Override
			public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
				observerTimeoutLogEvents.add(logEvent);
			}
		};
		SokletConfig observerTimeoutConfig = SokletConfig.withHttpServer(
					HttpServer.withPort(observerTimeoutPort)
							.requestHandlerTimeout(Duration.ofMillis(200))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(blockingObserver)
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							observerTimeoutMarshalerInvocations.incrementAndGet();
							return MarshaledResponse.fromStatusCode(499);
						})
						.build())
				.build();
		try (Soklet app = Soklet.fromConfig(observerTimeoutConfig)) {
			app.start();
			RawResponse response = exchange(observerTimeoutPort,
					"GET /x HTTP/9.9\r\n");
			assertBodylessFallback(response, 400, "Bad Request");
			Assertions.assertTrue(observerEntered.await(1, TimeUnit.SECONDS));
		}
		Assertions.assertEquals(0, observerTimeoutMarshalerInvocations.get());
		Assertions.assertEquals(0, observerTimeoutLogEvents.stream()
				.filter(event -> event.getThrowable()
						.filter(java.util.concurrent.TimeoutException.class::isInstance)
						.isPresent())
				.count());

		int rejectionPort = findFreePort();
		AtomicInteger observerInvocations = new AtomicInteger();
		AtomicInteger marshalerInvocations = new AtomicInteger();
		SokletConfig rejectionConfig = SokletConfig.withHttpServer(
					HttpServer.withPort(rejectionPort)
							.requestHandlerExecutorServiceSupplier(
									RejectingExecutorService::new)
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didRejectUnparsedRequest(
							@NonNull UnparsedRequest request) {
						observerInvocations.incrementAndGet();
					}
				})
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							marshalerInvocations.incrementAndGet();
							return MarshaledResponse.fromStatusCode(499);
						})
						.build())
				.build();
		try (Soklet app = Soklet.fromConfig(rejectionConfig)) {
			app.start();
			RawResponse response = exchange(rejectionPort, "GET /x HTTP/9.9\r\n");
			assertBodylessFallback(response, 400, "Bad Request");
		}
		Assertions.assertEquals(0, observerInvocations.get());
		Assertions.assertEquals(0, marshalerInvocations.get());

		int throwingPort = findFreePort();
		AtomicInteger throwingObserverInvocations = new AtomicInteger();
		AtomicInteger throwingMarshalerInvocations = new AtomicInteger();
		SokletConfig throwingConfig = SokletConfig.withHttpServer(
					HttpServer.withPort(throwingPort)
							.requestHandlerExecutorServiceSupplier(
									ThrowingExecutorService::new)
							.requestHandlerTimeout(Duration.ofSeconds(5))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didRejectUnparsedRequest(
							@NonNull UnparsedRequest request) {
						throwingObserverInvocations.incrementAndGet();
					}
				})
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							throwingMarshalerInvocations.incrementAndGet();
							return MarshaledResponse.fromStatusCode(499);
						})
						.build())
				.build();
		try (Soklet app = Soklet.fromConfig(throwingConfig)) {
			app.start();
			RawResponse response = exchange(throwingPort,
					"GET /x HTTP/9.9\r\n");
			assertBodylessFallback(response, 400, "Bad Request");
		}
		Assertions.assertEquals(0, throwingObserverInvocations.get());
		Assertions.assertEquals(0, throwingMarshalerInvocations.get());

		int directPort = findFreePort();
		AtomicInteger directObserverInvocations = new AtomicInteger();
		AtomicInteger directMarshalerInvocations = new AtomicInteger();
		SokletConfig directConfig = SokletConfig.withHttpServer(
					HttpServer.withPort(directPort)
							.requestHandlerExecutorServiceSupplier(
									DirectExecutorService::new)
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didRejectUnparsedRequest(
							@NonNull UnparsedRequest request) {
						directObserverInvocations.incrementAndGet();
					}
				})
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							directMarshalerInvocations.incrementAndGet();
							return MarshaledResponse.fromStatusCode(499);
						})
						.build())
				.build();
		try (Soklet app = Soklet.fromConfig(directConfig)) {
			app.start();
			RawResponse response = exchange(directPort, "GET /x HTTP/9.9\r\n");
			assertBodylessFallback(response, 400, "Bad Request");
		}
		Assertions.assertEquals(0, directObserverInvocations.get());
		Assertions.assertEquals(0, directMarshalerInvocations.get());
	}

	@Test
	void customExecutorFutureCannotWedgeTimeoutFallbacks() throws Exception {
		int port = findFreePort();
		BlockingCancelExecutorService executor =
				new BlockingCancelExecutorService();
		CountDownLatch handlersEntered = new CountDownLatch(2);
		ResponseMarshaler responseMarshaler = ResponseMarshaler.builder()
				.unparsedRequestHandler(request -> {
					handlersEntered.countDown();
					try {
						new CountDownLatch(1).await(2, TimeUnit.SECONDS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					return MarshaledResponse.fromStatusCode(499);
				})
				.build();
		SokletConfig config = SokletConfig.withHttpServer(
					HttpServer.withPort(port)
							.requestHandlerExecutorServiceSupplier(() -> executor)
							.requestHandlerTimeout(Duration.ofMillis(200))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.responseMarshaler(responseMarshaler)
				.build();

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			try {
				RawResponse firstResponse = exchange(port,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(firstResponse, 400, "Bad Request");
				RawResponse secondResponse = exchange(port,
						"GET /x HTTP/9.9\r\n");
				assertBodylessFallback(secondResponse, 400, "Bad Request");
				Assertions.assertTrue(handlersEntered.await(
						1, TimeUnit.SECONDS));
			} finally {
				executor.releaseBlockingCancel();
			}
		}

		Assertions.assertEquals(1, executor.cancelEntered().getCount());
	}

	@Test
	void queuedTimeoutUsesTransportFailureClassification() throws Exception {
		int port = findFreePort();
		AtomicInteger observerInvocations = new AtomicInteger();
		AtomicInteger marshalerInvocations = new AtomicInteger();
		List<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		SokletConfig config = SokletConfig.withHttpServer(
					HttpServer.withPort(port)
							.requestHandlerExecutorServiceSupplier(
									HoldingExecutorService::new)
							.requestHandlerTimeout(Duration.ofMillis(200))
							.requestHeaderTimeout(Duration.ofSeconds(5))
							.build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(IntegrationTests.EchoResource.class)))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didRejectUnparsedRequest(
							@NonNull UnparsedRequest request) {
						observerInvocations.incrementAndGet();
					}

					@Override
					public void didReceiveLogEvent(@NonNull LogEvent logEvent) {
						logEvents.add(logEvent);
					}
				})
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							marshalerInvocations.incrementAndGet();
							return MarshaledResponse.fromStatusCode(499);
						})
						.build())
				.build();

		try (Soklet app = Soklet.fromConfig(config)) {
			app.start();
			RawResponse response = exchange(port, "GET /x HTTP/9.9\r\n");
			assertBodylessFallback(response, 400, "Bad Request");
		}

		Assertions.assertEquals(0, observerInvocations.get());
		Assertions.assertEquals(0, marshalerInvocations.get());
		Assertions.assertEquals(0, logEvents.stream()
				.filter(event -> event.getThrowable()
						.filter(java.util.concurrent.TimeoutException.class::isInstance)
						.isPresent())
				.count());
	}

	@Test
	void unparsedWorkOwnsOneLifecycleAdmissionUntilItCompletes()
			throws Exception {
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerTimeout(Duration.ofSeconds(5))
				.build();
		SokletConfig config = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.responseMarshaler(ResponseMarshaler.builder()
						.unparsedRequestHandler(request -> {
							handlerEntered.countDown();
							try {
								Assertions.assertTrue(releaseHandler.await(
										3, TimeUnit.SECONDS));
							} catch (InterruptedException exception) {
								Thread.currentThread().interrupt();
								throw new AssertionError(exception);
							}
							return MarshaledResponse.fromStatusCode(400);
						})
						.build())
				.build();
		server.initialize(config, (request, callback) -> {});
		Thread stopThread = new Thread(server::stop, "unparsed-stop-test");

		try (Socket socket = connectAfterStart(server)) {
			socket.setSoTimeout(3_000);
			socket.getOutputStream().write(
					"GET /x HTTP/9.9\r\n".getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			Assertions.assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
			BuiltInTransportLifecycleAdapter.Generation generation = server
					.getLifecycleAdapter().generation().orElseThrow();
			Assertions.assertEquals(1,
					generation.admissionFence().admittedWorkCount());

			stopThread.start();
			waitUntil(server.getLifecycleAdapter()::shutdownInProgress, 2_000L);
			Assertions.assertTrue(stopThread.isAlive());
			Assertions.assertEquals(1,
					generation.admissionFence().admittedWorkCount());

			releaseHandler.countDown();
			RawResponse response = readResponse(socket.getInputStream());
			assertBodylessFallback(response, 400, "Bad Request");
			stopThread.join(3_000L);
			Assertions.assertFalse(stopThread.isAlive());
			Assertions.assertEquals(0,
					generation.admissionFence().admittedWorkCount());
		} finally {
			releaseHandler.countDown();
			stopThread.join(3_000L);
			server.stop();
		}
	}

	private static Socket connectAfterStart(@NonNull DefaultHttpServer server)
			throws IOException, InterruptedException {
		server.start();
		return connectWithRetry("127.0.0.1",
				server.getEventLoop().orElseThrow().getPort(), 2_000);
	}

	private static void waitUntil(@NonNull BooleanSupplier condition,
			long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime()
				+ TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(5L);
		Assertions.assertTrue(condition.getAsBoolean());
	}

	private static void assertBodylessFallback(@NonNull RawResponse response,
			int status, @NonNull String reason) {
		Assertions.assertEquals("HTTP/1.1 " + status + " " + reason,
				response.statusLine());
		Assertions.assertEquals("close", response.headers().get("connection"));
		Assertions.assertEquals("0", response.headers().get("content-length"));
		Assertions.assertEquals(0, response.body().length);
	}

	private static byte @NonNull [] remainingBytes(@NonNull ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	private static RawResponse exchange(int port, @NonNull String request)
			throws IOException, InterruptedException {
		return exchange(port, request.getBytes(StandardCharsets.US_ASCII));
	}

	private static RawResponse exchange(int port, byte @NonNull [] request)
			throws IOException, InterruptedException {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 2_000);
				 OutputStream output = socket.getOutputStream();
				 InputStream input = socket.getInputStream()) {
			socket.setSoTimeout(4_000);
			output.write(request);
			output.flush();
			return readResponse(input);
		}
	}

	private static RawResponse readResponse(@NonNull InputStream input)
			throws IOException {
		String statusLine = readLine(input);
		if (statusLine == null)
			throw new EOFException("Unexpected EOF while reading status line");
		Map<String, String> headers = new LinkedHashMap<>();
		String line;
		while ((line = readLine(input)) != null && !line.isEmpty()) {
			int colon = line.indexOf(':');
			if (colon > 0)
				headers.put(line.substring(0, colon).trim()
						.toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
		}
		int contentLength = Integer.parseInt(
				headers.getOrDefault("content-length", "0"));
		byte[] body = input.readNBytes(contentLength);
		if (body.length != contentLength)
			throw new EOFException("Short response body");
		return new RawResponse(statusLine, headers, body);
	}

	private static String readLine(@NonNull InputStream input) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		int previous = -1;
		int current;
		while ((current = input.read()) != -1) {
			if (previous == '\r' && current == '\n')
				return bytes.toString(StandardCharsets.ISO_8859_1);
			if (previous != -1)
				bytes.write(previous);
			previous = current;
		}
		if (previous != -1)
			bytes.write(previous);
		return bytes.size() == 0 ? null
				: bytes.toString(StandardCharsets.ISO_8859_1);
	}

	private record Scenario(@NonNull UnparsedRequestReason reason,
			@NonNull String wireBytes, @NonNull String expectedCapture) {}

	private record RawResponse(@NonNull String statusLine,
			@NonNull Map<String, String> headers, byte @NonNull [] body) {}

	private static final class RejectingExecutorService
					extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void shutdown() {
			this.shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
			return this.shutdown.get();
		}

		@Override
		public void execute(@NonNull Runnable command) {
			throw new RejectedExecutionException("intentionally saturated");
		}
	}

	private static final class DirectExecutorService
				extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void shutdown() {
			this.shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
			return this.shutdown.get();
		}

		@Override
		public void execute(@NonNull Runnable command) {
			command.run();
		}
	}

	private static final class ThrowingExecutorService
				extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void shutdown() {
			this.shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown.set(true);
			return List.of();
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
			return this.shutdown.get();
		}

		@Override
		public void execute(@NonNull Runnable command) {
			throw new IllegalStateException("intentionally failed execution");
		}
	}

	private static final class HoldingExecutorService
				extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();
		private final List<Runnable> commands = new CopyOnWriteArrayList<>();

		@Override
		public void shutdown() {
			this.shutdown.set(true);
			this.commands.clear();
		}

		@Override
		public List<Runnable> shutdownNow() {
			this.shutdown.set(true);
			List<Runnable> pending = List.copyOf(this.commands);
			this.commands.clear();
			return pending;
		}

		@Override
		public boolean isShutdown() {
			return this.shutdown.get();
		}

		@Override
		public boolean isTerminated() {
			return this.shutdown.get();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
			return this.shutdown.get();
		}

		@Override
		public void execute(@NonNull Runnable command) {
			if (isShutdown())
				throw new RejectedExecutionException("executor is shut down");
			this.commands.add(command);
		}
	}

	private static final class BlockingCancelExecutorService
				extends AbstractExecutorService {
		private final ExecutorService delegate = Executors.newFixedThreadPool(2);
		private final CountDownLatch cancelEntered = new CountDownLatch(1);
		private final CountDownLatch releaseCancel = new CountDownLatch(1);

		@Override
		public void shutdown() {
			this.delegate.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return this.delegate.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return this.delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return this.delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, @NonNull TimeUnit unit)
				throws InterruptedException {
			return this.delegate.awaitTermination(timeout, unit);
		}

		@Override
		public void execute(@NonNull Runnable command) {
			this.delegate.execute(command);
		}

		@Override
		public Future<?> submit(@NonNull Runnable command) {
			FutureTask<Void> hostileFuture = new FutureTask<>(command, null) {
				@Override
				public boolean cancel(boolean mayInterruptIfRunning) {
					cancelEntered.countDown();
					try {
						releaseCancel.await(5, TimeUnit.SECONDS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					throw new IllegalStateException(
							"Custom Future.cancel must not be invoked");
				}
			};
			this.delegate.execute(hostileFuture);
			return hostileFuture;
		}

		void releaseBlockingCancel() {
			this.releaseCancel.countDown();
		}

		@NonNull
		CountDownLatch cancelEntered() {
			return this.cancelEntered;
		}
	}
}
