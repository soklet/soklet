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

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import static com.soklet.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SseHandshakeOutcomeRuntimeTests {
	@Test void simulatedInterceptorFramingHeadersCannotActivateAnAcceptedHandshake() throws Exception { assertInterceptorFramingRejected(false); }
	@Test @EnabledForJreRange(min = JRE.JAVA_21)
	void liveInterceptorFramingHeadersCannotActivateAnAcceptedHandshake() throws Exception { assertInterceptorFramingRejected(true); }
	private static void assertInterceptorFramingRejected(boolean live) throws Exception {
		for (String header : List.of("Content-Length", "Transfer-Encoding")) {
			RequestInterceptor interceptor = new RequestInterceptor() {
				@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
						java.util.function.Function<Request, MarshaledResponse> generator,
						java.util.function.Consumer<MarshaledResponse> writer) {
					MarshaledResponse response = generator.apply(request);
					Map<String, List<String>> headers = new java.util.LinkedHashMap<>(response.getHeaders());
					headers.put(header, List.of(header.equals("Content-Length") ? "1" : "chunked"));
					writer.accept(response.copy().headers(headers).finish());
				}
			};
			try (Fixture fixture = new Fixture(8, 0, ResponseMarshaler.defaultInstance(), Duration.ofSeconds(10), interceptor)) {
				if (live) {
					fixture.app.start();
					try (Socket socket = fixture.request(HttpMethod.GET, "ok")) { assertTrue(readHead(socket).startsWith("HTTP/1.1 500")); }
				} else {
					SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config), simulator -> {
						SseRequestResult.RequestFailed result = assertInstanceOf(SseRequestResult.RequestFailed.class,
								simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events/ok")));
						assertEquals(500, result.getHttpRequestResult().getMarshaledResponse().getStatusCode());
					});
				}
				assertEquals(0, fixture.resource.initializers.get());
				assertNull(fixture.establishedConnection.get());
			}
		}
	}
	@Test
	void simulatedHeadRejectsWithoutInvokingTheEventSourceOrInitializer() throws Exception {
		Fixture f = new Fixture(8, 0, ResponseMarshaler.defaultInstance());
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(f.config), simulator -> {
			SseRequestResult result = simulator.performSseRequest(Request.withPath(HttpMethod.HEAD, "/events/ok").build());
			SseRequestResult.RequestFailed failed = assertInstanceOf(SseRequestResult.RequestFailed.class, result);
			assertEquals(405, failed.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			assertFalse(failed.getHttpRequestResult().getMarshaledResponse().getBody().isPresent());
			assertEquals(0, f.resource.calls.get());
			assertEquals(0, f.resource.initializers.get());
		});
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void headAndOptionsAdvertiseOnlyGetAndOptionsWithoutStreamAdmission() throws Exception {
		try (Fixture f = started(8, 0, ResponseMarshaler.defaultInstance())) {
			for (HttpMethod method : List.of(HttpMethod.HEAD, HttpMethod.OPTIONS)) {
				try (Socket socket = f.request(method, "ok")) {
					String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
					assertTrue(response.startsWith("HTTP/1.1 " + (method == HttpMethod.HEAD ? 405 : 204)), response);
					assertEquals(Set.of("GET", "OPTIONS"), allow(response));
					assertTrue(response.endsWith("\r\n\r\n"), response);
				}
			}
			assertEquals(0, f.resource.calls.get());
			assertEquals(0, f.resource.initializers.get());
			assertTrue(f.streamEvents.isEmpty());
			assertTrue(f.logs.isEmpty(), f.logs.toString());
			assertEquals(0, f.server.getStreamLifecycleCoordinatorForTests().orElseThrow().snapshot().reservations());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void customMethodNotAllowedResponseStillUsesTheExistingHookAndCannotSendHeadContent() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		ResponseMarshaler marshaler = ResponseMarshaler.builder().methodNotAllowedHandler((request, methods) -> {
			calls.incrementAndGet();
			assertEquals(Set.of(HttpMethod.GET, HttpMethod.OPTIONS), methods);
			return MarshaledResponse.withStatusCode(499).body("representation".getBytes(StandardCharsets.UTF_8)).build();
		}).build();
		try (Fixture f = started(8, 0, marshaler); Socket socket = f.request(HttpMethod.HEAD, "ok")) {
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(response.startsWith("HTTP/1.1 499"), response);
			assertTrue(response.endsWith("\r\n\r\n"), response);
			assertEquals(1, calls.get());
			assertEquals(0, f.resource.calls.get());
		}
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> headAndOptionsDoNotConsumeOrRequireStreamCapacity() {
		return Stream.of(true, false).map(fast -> DynamicTest.dynamicTest(fast ? "connection cap" : "lifecycle cap", () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			try (Fixture f = started(fast ? 8 : 1, fast ? 1 : 0, ResponseMarshaler.defaultInstance());
					 Socket held = f.request(HttpMethod.GET, "ok")) {
				assertTrue(readHead(held).startsWith("HTTP/1.1 200"));
				await(() -> f.server.getActiveConnectionCount() == 1 && !f.streamEvents.isEmpty());
				for (HttpMethod method : List.of(HttpMethod.HEAD, HttpMethod.OPTIONS)) {
					try (Socket socket = f.request(method, "ok")) {
						String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
						assertTrue(response.startsWith("HTTP/1.1 " + (method == HttpMethod.HEAD ? 405 : 204)), response);
						assertEquals(Set.of("GET", "OPTIONS"), allow(response));
						assertTrue(response.endsWith("\r\n\r\n"), response);
					}
				}
				assertEquals(1, f.resource.calls.get());
				assertEquals(1, f.resource.initializers.get());
				assertEquals(1, f.server.getActiveConnectionCount());
				assertEquals(1, f.server.getStreamLifecycleCoordinatorForTests().orElseThrow().snapshot().reservations());
				assertTrue(f.connectionRejections.isEmpty());
			}
		})));
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void headFailureFallbackCannotSendContent() throws Exception {
		ResponseMarshaler marshaler = ResponseMarshaler.builder()
				.methodNotAllowedHandler((request, methods) -> { throw new IllegalStateException("failed policy"); })
				.throwableHandler((request, failure, method) -> { throw new IllegalStateException("failed marshaler"); }).build();
		try (Fixture f = started(8, 0, marshaler); Socket socket = f.request(HttpMethod.HEAD, "ok")) {
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(response.startsWith("HTTP/1.1 500"), response);
			assertTrue(response.endsWith("\r\n\r\n"), response);
			assertEquals(0, f.resource.calls.get());
		}
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> headTimeoutResponsesCannotSendContent() {
		return Stream.of(false, true).map(failing -> DynamicTest.dynamicTest(failing ? "failsafe" : "custom", () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			ResponseMarshaler marshaler = ResponseMarshaler.builder()
					.methodNotAllowedHandler((request, methods) -> {
						try { Thread.sleep(3000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
						return MarshaledResponse.fromStatusCode(405);
					})
					.serviceUnavailableHandler((request, method) -> {
						if (failing) throw new IllegalStateException("failed timeout response");
						return MarshaledResponse.withStatusCode(503).body("timeout".getBytes(StandardCharsets.UTF_8)).build();
					}).build();
			try (Fixture f = new Fixture(8, 0, marshaler, Duration.ofMillis(100))) {
				f.app.start();
				try (Socket socket = f.request(HttpMethod.HEAD, "ok")) {
					String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
					assertTrue(response.startsWith("HTTP/1.1 503"), response);
					assertTrue(response.endsWith("\r\n\r\n"), response);
				}
				await(() -> f.handshakeFailures.size() == 1);
				assertEquals("HANDSHAKE_TIMEOUT", f.handshakeFailures.get(0).reason());
				assertEquals(0, f.resource.calls.get());
				assertTrue(f.streamEvents.isEmpty());
			}
		})));
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> initializerFailuresReportEstablishedThenElectedTerminationWithoutInternalRejection() {
		return Stream.of("checked", "overflow", "caught").map(mode -> DynamicTest.dynamicTest(mode, () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			try (Fixture f = started(8, 0, ResponseMarshaler.defaultInstance()); Socket socket = f.request(HttpMethod.GET, mode)) {
				assertTrue(readHead(socket).startsWith("HTTP/1.1 200"));
				assertEquals(-1, socket.getInputStream().read());
				await(() -> f.streamEvents.size() == 3 && f.metricEvents.size() == 3);
				String reason = mode.equals("checked") ? "PRODUCER_FAILED" : "BACKPRESSURE";
				assertEquals(List.of("established", "will:" + reason, "did:" + reason), f.streamEvents);
				assertEquals(f.streamEvents, f.metricEvents);
				assertTrue(f.handshakeFailures.isEmpty(), f.handshakeFailures.toString());
				assertTrue(f.connectionRejections.isEmpty(), f.connectionRejections.toString());
				assertTrue(f.logs.stream().noneMatch(e -> e.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE), f.logs.toString());
				if (mode.equals("checked")) assertSame(f.resource.failure, f.terminations.get(0).getCause().orElseThrow());
				assertEquals(reason, f.terminations.get(0).getReason().name());
				await(() -> f.logs.stream().anyMatch(e -> e.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR));
				List<LogEvent> diagnostics = f.logs.stream().filter(e -> e.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR).toList();
				assertEquals(1, diagnostics.size());
				LogEvent diagnostic = diagnostics.get(0);
				assertEquals("Server-Sent Event initialization terminated: " + reason, diagnostic.getMessage());
				assertSame(f.terminations.get(0).getCause().orElseThrow(), diagnostic.getThrowable().orElseThrow());
				if (!mode.equals("checked"))
					assertEquals("SseInitializerQueueCapacityExceededException", diagnostic.getThrowable().orElseThrow().getClass().getSimpleName());
				SseConnection established = f.establishedConnection.get();
				assertNotNull(established);
				assertSame(established.getRequest(), diagnostic.getRequest().orElseThrow());
				assertEquals("/events/" + mode, diagnostic.getRequest().orElseThrow().getPath());
				assertSame(established.getResourceMethod(), diagnostic.getResourceMethod().orElseThrow());
				await(() -> f.server.getStreamLifecycleCoordinatorForTests().orElseThrow().snapshot().reservations() == 0);
				assertEquals(0, f.server.getActiveConnectionCount());
			}
		})));
	}

	@TestFactory
	@EnabledForJreRange(min = JRE.JAVA_21)
	Stream<DynamicTest> bothCapacityBoundariesReportCapacityRatherThanApplicationRejection() {
		return Stream.of(true, false).map(fast -> DynamicTest.dynamicTest(fast ? "connection cap" : "lifecycle cap", () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
			try (Fixture f = started(fast ? 8 : 1, fast ? 1 : 0, ResponseMarshaler.defaultInstance()); Socket held = f.request(HttpMethod.GET, "ok")) {
				assertTrue(readHead(held).startsWith("HTTP/1.1 200")); await(() -> f.server.getActiveConnectionCount() == 1 && !f.streamEvents.isEmpty());
				try (Socket rejected = f.request(HttpMethod.GET, "ok")) { assertTrue(readHead(rejected).startsWith("HTTP/1.1 503")); }
				await(() -> f.handshakeFailures.size() == 1);
				assertEquals("CAPACITY_EXCEEDED", f.handshakeFailures.get(0).reason());
				assertEquals(fast ? 1 : 2, f.resource.calls.get());
			assertEquals(1, f.resource.initializers.get());
			}
		})));
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void handlerFailureKeepsItsActualCause() throws Exception {
		try (Fixture f = started(8, 0, ResponseMarshaler.defaultInstance()); Socket socket = f.request(HttpMethod.GET, "handler")) {
			assertTrue(readHead(socket).startsWith("HTTP/1.1 500")); await(() -> f.handshakeFailures.size() == 1);
			assertEquals("INTERNAL_ERROR", f.handshakeFailures.get(0).reason());
			assertSame(f.resource.failure, f.handshakeFailures.get(0).cause());
			assertTrue(f.streamEvents.isEmpty());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void explicitApplicationRejectionRemainsARejection() throws Exception {
		try (Fixture f = started(8, 0, ResponseMarshaler.defaultInstance()); Socket socket = f.request(HttpMethod.GET, "reject")) {
			assertTrue(readHead(socket).startsWith("HTTP/1.1 403")); await(() -> f.handshakeFailures.size() == 1);
			assertEquals("HANDSHAKE_REJECTED", f.handshakeFailures.get(0).reason());
			assertNull(f.handshakeFailures.get(0).cause());
			assertTrue(f.streamEvents.isEmpty());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void headPreparationFailureKeepsItsCauseBeforeAnyStreamIsEstablished() throws Exception {
		try (Fixture f = started(8, 0, ResponseMarshaler.defaultInstance()); Socket socket = f.request(HttpMethod.GET, "badheader")) {
			assertTrue(readHead(socket).startsWith("HTTP/1.1 500")); await(() -> f.handshakeFailures.size() == 1);
			assertEquals("INTERNAL_ERROR", f.handshakeFailures.get(0).reason());
			assertNotNull(f.handshakeFailures.get(0).cause());
			assertTrue(f.streamEvents.isEmpty());
		}
	}

	@Test
	@EnabledForJreRange(min = JRE.JAVA_21)
	void failedAcceptedHeadWriteKeepsItsCauseAndDoesNotEstablishAStream() throws Exception {
		try (Fixture f = new Fixture(8, 0, ResponseMarshaler.defaultInstance())) {
			f.failHeadWrite = true;
			f.app.start();
			try (Socket socket = f.request(HttpMethod.GET, "ok")) { assertEquals(-1, socket.getInputStream().read()); }
			await(() -> f.handshakeFailures.size() == 1);
			assertEquals("INTERNAL_ERROR", f.handshakeFailures.get(0).reason());
			assertInstanceOf(IOException.class, f.handshakeFailures.get(0).cause());
			assertTrue(f.streamEvents.isEmpty());
		}
	}

	private record Failure(String reason, Throwable cause) {}

	private static Fixture started(int capacity, int connections, ResponseMarshaler marshaler) throws Exception {
		Fixture fixture = new Fixture(capacity, connections, marshaler);
		fixture.app.start();
		return fixture;
	}

	private static Set<String> allow(String response) {
		return response.lines().filter(line -> line.startsWith("Allow: "))
				.flatMap(line -> Stream.of(line.substring(7).split(",\\s*")))
				.collect(Collectors.toSet());
	}

	private static String readHead(Socket socket) throws Exception {
		StringBuilder head = new StringBuilder();
		int value;
		while ((value = socket.getInputStream().read()) >= 0) {
			head.append((char) value);
			if (head.toString().endsWith("\r\n\r\n"))
				break;
		}
		return head.toString();
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long end = System.nanoTime() + 3_000_000_000L;
		while (!condition.getAsBoolean() && System.nanoTime() < end)
			Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}

	private static final class Fixture implements AutoCloseable {
		final int port = findFreePort();
		final DefaultSseServer server;
		final Resource resource = new Resource();
		final SokletConfig config;
		final Soklet app;
		final List<String> streamEvents = new CopyOnWriteArrayList<>();
		final List<String> metricEvents = new CopyOnWriteArrayList<>();
		final List<String> connectionRejections = new CopyOnWriteArrayList<>();
		final List<Failure> handshakeFailures = new CopyOnWriteArrayList<>();
		final List<StreamTermination> terminations = new CopyOnWriteArrayList<>();
		final AtomicReference<SseConnection> establishedConnection = new AtomicReference<>();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		boolean failHeadWrite;

		Fixture(int capacity, int connections, ResponseMarshaler marshaler) throws Exception {
			this(capacity, connections, marshaler, Duration.ofSeconds(10));
		}

		Fixture(int capacity, int connections, ResponseMarshaler marshaler, Duration timeout) throws Exception {
			this(capacity, connections, marshaler, timeout, RequestInterceptor.defaultInstance());
		}
		Fixture(int capacity, int connections, ResponseMarshaler marshaler, Duration timeout, RequestInterceptor interceptor) throws Exception {
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.streamingLifecycleCapacity(capacity).concurrentConnectionLimit(connections)
					.connectionQueueCapacity(1).requestHandlerTimeout(timeout)
					.verifyConnectionOnceEstablished(false).build();
			config = SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					})
					.responseMarshaler(marshaler)
					.requestInterceptor(interceptor)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(10)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofMillis(300))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent logEvent) { logs.add(logEvent); }
						@Override public void didEstablishSseConnection(SseConnection connection) {
							establishedConnection.set(connection); streamEvents.add("established");
						}
						@Override public void willTerminateSseConnection(SseConnection connection, StreamTermination termination) {
							streamEvents.add("will:" + termination.getReason());
						}
						@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
							terminations.add(termination);
							streamEvents.add("did:" + termination.getReason());
						}
						@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod resourceMethod,
								SseConnection.HandshakeFailureReason reason, Throwable failure) {
							handshakeFailures.add(new Failure(reason.name(), failure));
						}
						@Override public void didFailToAcceptConnection(ServerType serverType, java.net.InetSocketAddress remoteAddress,
								ConnectionRejectionReason reason, Throwable failure) {
							connectionRejections.add(reason.name());
						}
						@Override public void willWriteResponse(ServerType serverType, Request request,
								ResourceMethod resourceMethod, MarshaledResponse response) {
							if (!failHeadWrite)
								return;
							// Close the admitted channel before the transport's head write, avoiding a timing-dependent TCP reset.
							try {
								var field = DefaultSseServer.class.getDeclaredField("activeHandshakes");
								field.setAccessible(true);
								for (Object key : ((Map<?, ?>) field.get(server)).keySet())
									((SocketChannel) key).close();
							} catch (ReflectiveOperationException | IOException failure) {
								throw new IllegalStateException(failure);
							}
						}
					})
					.metricsCollector(new MetricsCollector() {
						@Override public void didEstablishSseConnection(SseConnection connection) { metricEvents.add("established"); }
						@Override public void willTerminateSseConnection(SseConnection connection, StreamTermination termination) {
							metricEvents.add("will:" + termination.getReason());
						}
						@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
							metricEvents.add("did:" + termination.getReason());
						}
					}).build();
			app = Soklet.fromConfig(config);
		}

		Socket request(HttpMethod httpMethod, String mode) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", port, 2000);
			socket.setSoTimeout(3000);
			socket.getOutputStream().write((httpMethod + " /events/" + mode + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			return socket;
		}

		@Override public void close() {
			app.close();
			assertTrue(app.getShutdownResult().orElseThrow().isComplete());
		}
	}

	public static final class Resource {
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger initializers = new AtomicInteger();
		final IOException failure = new IOException("application replay failure");

		@SseEventSource("/events/{mode}")
		public SseHandshakeResult events(Request request) throws Exception {
			calls.incrementAndGet();
			String mode = request.getPath().substring(request.getPath().lastIndexOf('/') + 1);
			if (mode.equals("handler"))
				throw failure;
			if (mode.equals("reject"))
				return SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(403));
			return SseHandshakeResult.Accepted.builder()
					.headers(mode.equals("badheader") ? Map.of("Content-Length", List.of("1")) : Map.of())
					.clientInitializer(unicaster -> {
						initializers.incrementAndGet();
						if (mode.equals("checked"))
							throw failure;
						if (mode.equals("overflow") || mode.equals("caught")) {
							unicaster.unicastEvent(SseEvent.withData("first").build());
							if (mode.equals("caught")) {
								try { unicaster.unicastEvent(SseEvent.withData("second").build()); }
								catch (IllegalStateException expected) { /* Termination must survive a caught overflow. */ }
							} else {
								unicaster.unicastEvent(SseEvent.withData("second").build());
							}
						}
					}).build();
		}
	}
}
