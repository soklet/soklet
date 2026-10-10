package com.soklet;

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import static com.soklet.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
@EnabledForJreRange(min = JRE.JAVA_21)
class Round2SseRuntimeTests {
	@Test void bytesBeforeResetDoNotReportServerAcceptanceOrTransportErrors() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ofSeconds(3), 64)) {
			String prefix = "GET /events HTTP/1.1\r\nHost: localhost\r\nCookie: session=";
			int[] requestSizes = {3841, 8192, 16384};
			for (int attempt = 0; attempt < 16; attempt++) {
				try (Socket client = fixture.openSocket()) {
					client.setSoLinger(true, 0);
					int requestSize = requestSizes[attempt % requestSizes.length];
					client.getOutputStream().write((prefix + "x".repeat(requestSize - prefix.length()))
							.getBytes(StandardCharsets.ISO_8859_1));
				}
			}
			try (Socket complete = fixture.completeRequest()) { assertTrue(readHeaders(complete).startsWith("HTTP/1.1 200")); }
			assertTrue(fixture.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS).isComplete());
			assertEquals(1, fixture.resource.calls.get());
			assertTrue(fixture.logs.isEmpty(), fixture.logs.toString());
			assertTrue(fixture.connectionRejections.isEmpty(), fixture.connectionRejections.toString());
			assertEquals(0, fixture.rejectedRequests.get());
			assertTrue(fixture.readFailures.isEmpty(), fixture.readFailures.toString());
			assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
		}
	}

	@Test void immediateResetHealthChecksDoNotReportServerAcceptanceOrTransportErrors() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ofSeconds(3), 64)) {
			for (int attempt = 0; attempt < 16; attempt++) {
				try (Socket client = fixture.openSocket()) { client.setSoLinger(true, 0); }
			}
			// A succeeding later handshake proves the accept loop progressed past
			// the reset sockets; shutdown then joins any remaining header readers.
			try (Socket complete = fixture.completeRequest()) { assertTrue(readHeaders(complete).startsWith("HTTP/1.1 200")); }
			assertTrue(fixture.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS).isComplete());
			assertEquals(1, fixture.resource.calls.get());
			assertTrue(fixture.logs.isEmpty(), fixture.logs.toString());
			assertTrue(fixture.connectionRejections.isEmpty(), fixture.connectionRejections.toString());
			assertEquals(0, fixture.rejectedRequests.get());
			assertTrue(fixture.readFailures.isEmpty(), fixture.readFailures.toString());
			assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
		}
	}

	@Test void noncooperativeHandshakeReturningAfterForceDoesNotRecordAWriteTransportFailure() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ZERO, 1);
				Socket client = fixture.request("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
			fixture.resource.ignoreInterrupts = true;
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			var scheduler = fixture.server.getRequestHandlerTimeoutScheduler().orElseThrow();
			var shutdown = fixture.soklet.shutdown().toCompletableFuture();
			assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
			await(scheduler::isShutdown);
			fixture.resource.release.countDown();
			assertEquals("", read(client));
			ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.FORCED, result.getShutdownDisposition());
			assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
			assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE
					|| event.getLogEventType() == LogEventType.SSE_SERVER_WRITING_HANDSHAKE_RESPONSE_FAILED), fixture.logs.toString());
		}
	}

	@Test void partialHeaderReadersLeaveTheApplicationHandshakeWorkerAvailable() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ofSeconds(3), 3);
				Socket first = fixture.partialRequest(); Socket second = fixture.partialRequest()) {
			await(() -> fixture.reading.get() == 2);
			assertEquals(0, ((java.util.concurrent.ThreadPoolExecutor) fixture.server
					.getRequestHandlerExecutorService().orElseThrow()).getActiveCount());
			try (Socket complete = fixture.completeRequest()) {
				assertTrue(readHeaders(complete).startsWith("HTTP/1.1 200"));
				assertEquals(1, fixture.resource.initializers.get());
			}
			assertEquals(3, fixture.acceptedRequests.get());
			assertEquals(0, fixture.rejectedRequests.get());
		}
	}

	@Test void pendingHeaderAdmissionRetainsTheConfiguredBound() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ofSeconds(3), 1);
				Socket first = fixture.partialRequest(); Socket second = fixture.partialRequest()) {
			await(() -> fixture.reading.get() == 2);
			// The bound rejects the socket before header reading begins. Send no
			// unread request bytes that a TCP close could reset after the failsafe.
			for (int attempt = 0; attempt < 9; attempt++) {
				try (Socket excess = fixture.openSocket()) {
					assertTrue(read(excess).startsWith("HTTP/1.1 503"));
				}
			}
			assertEquals(2, fixture.acceptedRequests.get());
			assertEquals(9, fixture.rejectedRequests.get());
			assertEquals(0, fixture.resource.initializers.get());
			assertEquals(java.util.Collections.nCopies(9, ConnectionRejectionReason.MAX_CONNECTIONS), fixture.connectionRejections);
			assertEquals(4, fixture.logs.size(), "Overload logs are coalesced at 1, 2, 4 and 8 rejections");
			assertTrue(fixture.logs.stream().allMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_CONNECTION_REJECTED
					&& event.getMessage().equals("Pending SSE handshake capacity exhausted")), fixture.logs.toString());
			assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
		}
	}

	@Test void gracefulShutdownQuietlyClosesIdleSocketWithDefaultTimeoutsAndJoinsReaders() throws Exception {
		assertDefaultHeaderPhaseShutdown(false);
	}

	@Test void gracefulShutdownQuietlyClosesPartialHeaderWithDefaultTimeoutsAndJoinsReaders() throws Exception {
		assertDefaultHeaderPhaseShutdown(true);
	}

	private static void assertDefaultHeaderPhaseShutdown(boolean partial) throws Exception {
		// Header reads are connection preparation, not admitted application work.
		// Keep the real 60s header/15s graceful defaults: the old 500ms fixture
		// hid the forced-shutdown regression and asserted the wrong drain policy.
		try (ReaderFixture fixture = new ReaderFixture(null, null, 1);
				Socket client = partial ? fixture.partialRequest() : fixture.openSocket()) {
			assertEquals(Duration.ofSeconds(60), fixture.server.getRequestHeaderTimeout());
			await(() -> fixture.reading.get() == 1);
			var readerExecutor = fixture.server.getRequestReaderExecutorService().orElseThrow();
			ShutdownResult result = fixture.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
			assertEquals("", read(client), "Unparsed sockets close without synthetic error responses");
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
			assertTrue(readerExecutor.isTerminated());
			assertEquals(1, fixture.acceptedRequests.get());
			assertEquals(0, fixture.rejectedRequests.get());
			assertEquals(0, fixture.resource.calls.get());
			assertTrue(fixture.logs.isEmpty(), fixture.logs.toString());
			assertTrue(fixture.connectionRejections.isEmpty(), fixture.connectionRejections.toString());
			assertTrue(fixture.readFailures.isEmpty(), fixture.readFailures.toString());
			assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
		}
	}

	@Test void gracefulShutdownPreservesParsedQueuedHandshakesAndTheirResponse() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(null, null, 3);
				Socket running = fixture.request("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
			assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
			try (Socket queued = fixture.completeRequest()) {
				var executor = (java.util.concurrent.ThreadPoolExecutor) fixture.server.getRequestHandlerExecutorService().orElseThrow();
				await(() -> executor.getQueue().size() == 1);
				var shutdown = fixture.soklet.shutdown().toCompletableFuture();
				await(fixture.server::isStopping);
				assertFalse(shutdown.isDone(), "Admitted application work retains its response obligation");
				fixture.resource.release.countDown();
				assertTrue(read(running).startsWith("HTTP/1.1 503"));
				assertTrue(read(queued).startsWith("HTTP/1.1 503"));
				ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
				assertTrue(result.isComplete());
				assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				assertEquals(1, fixture.resource.calls.get(), "The parsed queued endpoint is still invoked during drain");
				assertEquals(0, fixture.resource.initializers.get());
				assertTrue(fixture.logs.isEmpty(), fixture.logs.toString());
				assertTrue(fixture.connectionRejections.isEmpty(), fixture.connectionRejections.toString());
			}
		}
	}

	@Test void requestFinishingParsingAfterQuiesceCannotEnterTheApplication() throws Exception {
		CountDownLatch constructingRequest = new CountDownLatch(1), releaseParsing = new CountDownLatch(1);
		IdGenerator<String> idGenerator = request -> {
			constructingRequest.countDown();
			try {
				if (!releaseParsing.await(3, TimeUnit.SECONDS)) throw new AssertionError("Parsing was not released");
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError(interrupted);
			}
			return "late-parsed-request";
		};
		try (ReaderFixture fixture = new ReaderFixture(null, null, 1, null, idGenerator);
				Socket client = fixture.completeRequest()) {
			try {
				assertTrue(constructingRequest.await(2, TimeUnit.SECONDS));
				var shutdown = fixture.soklet.shutdown().toCompletableFuture();
				await(fixture.server::isStopping);
				assertFalse(shutdown.isDone(), "Physical parsing work must still be joined");
				releaseParsing.countDown();
				assertEquals("", read(client));
				ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
				assertTrue(result.isComplete());
				assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				assertEquals(0, fixture.resource.calls.get());
				assertTrue(fixture.logs.isEmpty(), fixture.logs.toString());
				assertTrue(fixture.readFailures.isEmpty(), fixture.readFailures.toString());
				assertTrue(fixture.transportFailures.isEmpty(), fixture.transportFailures.toString());
			} finally { releaseParsing.countDown(); }
		}
	}

	@Test void malformedOversizedTargetsUseUnparsed413WithoutInternalFailures() throws Exception {
		for (String target : List.of("/ev%zzents", "http://[bad/events", "*")) {
			String prefix = "GET " + target + " HTTP/1.1\r\nHost: localhost\r\nX-Fill: ";
			// Cross the aggregate bound by exactly one byte. Extra unread bytes at
			// close can cause a TCP reset that hides the finite rejection response.
			String request = prefix + "a".repeat(1025 - prefix.length());
			try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(2), Duration.ofSeconds(3), 1, 1024);
					Socket client = fixture.request(request)) {
				String wire = read(client);
				assertTrue(wire.startsWith("HTTP/1.1 413"), target + ": " + wire);
				assertEquals(List.of(RequestReadFailureReason.REQUEST_READ_REJECTED), fixture.readFailures);
				assertEquals(List.of(MetricsCollector.TransportFailureReason.REQUEST_TOO_LARGE), fixture.transportFailures);
				assertEquals(0, fixture.resource.calls.get());
				assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_INTERNAL_ERROR
						|| event.getLogEventType() == LogEventType.SSE_SERVER_UNPARSEABLE_REQUEST), fixture.logs.toString());
			}
		}
	}

	@Test void forceShutdownClosesAdmittedHeaderSocketsAndJoinsTheirReaders() throws Exception {
		try (ReaderFixture fixture = new ReaderFixture(Duration.ofSeconds(10), Duration.ZERO, 1);
				Socket partial = fixture.partialRequest()) {
			await(() -> fixture.reading.get() == 1);
			var readerExecutor = fixture.server.getRequestReaderExecutorService().orElseThrow();
			ShutdownResult result = fixture.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
			assertTrue(result.isComplete());
			assertEquals(ShutdownDisposition.FORCED, result.getShutdownDisposition());
			assertTrue(readerExecutor.isTerminated());
			assertEquals(-1, partial.getInputStream().read());
			assertEquals(1, fixture.acceptedRequests.get());
			assertEquals(0, fixture.rejectedRequests.get());
		}
	}

	@Test void streamedInterceptorReplacementFailsClosedWithTruthfulFinite500InLiveAndSimulator() throws Exception {
		try (Fixture fixture = new Fixture(Duration.ofSeconds(3), -2)) {
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config), simulator -> {
				var failed = assertInstanceOf(SseRequestResult.RequestFailed.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
				assertEquals(500, failed.getHttpRequestResult().getMarshaledResponse().getStatusCode());
				assertFalse(failed.getHttpRequestResult().getMarshaledResponse().isStreaming());
			});
			fixture.soklet.start();
			try (Socket client = fixture.request("/events")) { assertTrue(read(client).startsWith("HTTP/1.1 500")); }
			assertEquals(List.of(500, 500), fixture.writes);
			assertEquals(List.of(500, 500), fixture.finishes);
			assertEquals(0, fixture.resource.initializers.get());
			assertEquals(0, fixture.resource.producerCalls.get());
			assertTrue(fixture.server.getGlobalConnections().isEmpty());
		}
	}
	@Test void interceptorFiniteReplacementCannotJoinTheBroadcasterInLiveOrSimulatedSse() throws Exception {
		for (int status : List.of(200, 401)) {
			try (Fixture fixture = new Fixture(Duration.ofSeconds(3), status)) {
				SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config), simulator -> {
					var failed = assertInstanceOf(SseRequestResult.RequestFailed.class,
							simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
					assertEquals(status, failed.getHttpRequestResult().getMarshaledResponse().getStatusCode());
					assertTrue(failed.getHttpRequestResult().getSseHandshakeResult().isEmpty());
				});
				fixture.soklet.start();
				try (Socket client = fixture.request("/events")) {
					String response = read(client);
					assertTrue(response.startsWith("HTTP/1.1 " + status), response);
					assertTrue(response.endsWith("denied"), response);
				}
				assertEquals(0, fixture.resource.initializers.get());
				assertTrue(fixture.server.getGlobalConnections().isEmpty());
				assertTrue(fixture.terminations.isEmpty());
			}
		}
	}

	@Test void initializerFailureAndOverflowEmitOneContextualDiagnosticWithTheElectedReason() throws Exception {
		for (boolean overflow : List.of(false, true)) {
			try (Fixture fixture = new Fixture(Duration.ofSeconds(3), null)) {
				fixture.resource.initializerMode = overflow ? "overflow" : "failure";
				fixture.soklet.start();
				try (Socket client = fixture.request("/events")) {
					assertTrue(read(client).startsWith("HTTP/1.1 200"));
				}
				await(() -> !fixture.terminations.isEmpty());
				assertEquals(overflow ? StreamTerminationReason.BACKPRESSURE : StreamTerminationReason.PRODUCER_FAILED,
						fixture.terminations.get(0).getReason());
				List<LogEvent> diagnostics = fixture.logs.stream().filter(event -> event.getMessage().startsWith("Server-Sent Event initialization terminated:")).toList();
				assertEquals(1, diagnostics.size());
				assertTrue(diagnostics.get(0).getRequest().isPresent());
				assertTrue(diagnostics.get(0).getResourceMethod().isPresent());
				if (!overflow) assertSame(fixture.resource.failure, diagnostics.get(0).getThrowable().orElseThrow());
			}
		}
	}

	@Test void compatibleInterceptorHeaderDecorationPreservesAcceptedSse() throws Exception {
		try (Fixture fixture = new Fixture(Duration.ofSeconds(3), -1)) {
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config), simulator -> {
				var accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
				assertEquals(List.of("decorated"), accepted.getHttpRequestResult().getMarshaledResponse().getHeaders().get("X-Test"));
			});
			fixture.soklet.start();
			try (Socket client = fixture.request("/events")) {
				StringBuilder head = new StringBuilder();
				while (!head.toString().endsWith("\r\n\r\n")) {
					int next = client.getInputStream().read();
					assertNotEquals(-1, next, "SSE response ended before complete headers");
					head.append((char) next);
				}
				assertTrue(head.toString().startsWith("HTTP/1.1 200"));
				assertTrue(head.toString().contains("X-Test: decorated"));
				await(() -> !fixture.server.getGlobalConnections().isEmpty());
			}
			assertEquals(2, fixture.resource.initializers.get());
		}
	}

	@Test void handshakeDeadlineSurvivesGracefulDrainAndDeliversItsUnavailableResponse() throws Exception {
		try (Fixture fixture = new Fixture(Duration.ofMillis(500), null)) {
			fixture.soklet.start();
			try (Socket client = fixture.request("/slow")) {
				assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
				var shutdown = fixture.soklet.shutdown().toCompletableFuture();
				assertTrue(read(client).startsWith("HTTP/1.1 503"));
				assertTrue(fixture.resource.interrupted.await(2, TimeUnit.SECONDS));
				ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
				assertTrue(result.isComplete());
				assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				assertTrue(fixture.connectionRejections.isEmpty(), fixture.connectionRejections.toString());
				assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_CONNECTION_REJECTED
						|| event.getLogEventType() == LogEventType.SSE_SERVER_UNPARSEABLE_REQUEST));
			}
		}
	}

	@Test void returningAcceptedHandshakeDuringDrainGetsTruthfulFinite503WithoutFalseCapacityDiagnostics() throws Exception {
		try (Fixture fixture = new Fixture(Duration.ofSeconds(3), null)) {
			fixture.soklet.start();
			try (Socket client = fixture.request("/slow")) {
				assertTrue(fixture.resource.entered.await(2, TimeUnit.SECONDS));
				var shutdown = fixture.soklet.shutdown().toCompletableFuture();
				await(fixture.server::isStopping);
				assertTrue(fixture.server.acquireBroadcaster(ResourcePath.fromPath("/events")).isPresent());
				fixture.resource.release.countDown();
				assertTrue(read(client).startsWith("HTTP/1.1 503"));
				assertTrue(shutdown.get(3, TimeUnit.SECONDS).isComplete());
				assertEquals(List.of(503), fixture.writes);
				assertEquals(List.of(503), fixture.finishes);
				assertTrue(fixture.connectionRejections.isEmpty());
				assertTrue(fixture.logs.stream().noneMatch(event -> event.getLogEventType() == LogEventType.SSE_SERVER_CONNECTION_REJECTED));
			}
		}
	}

	@Test void unsupportedAcceptedHeadersFailClosedInTheSimulatorAndTheLiveTransport() throws Exception {
		try (Fixture fixture = new Fixture(Duration.ofSeconds(3), null)) {
			fixture.resource.invalidHeader = true;
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(fixture.config), simulator -> {
				var result = assertInstanceOf(SseRequestResult.RequestFailed.class,
						simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/events")));
				assertEquals(500, result.getHttpRequestResult().getMarshaledResponse().getStatusCode());
			});
			fixture.soklet.start();
			try (Socket client = fixture.request("/events")) { assertTrue(read(client).startsWith("HTTP/1.1 500")); }
			assertEquals(0, fixture.resource.initializers.get());
			assertTrue(fixture.server.getGlobalConnections().isEmpty());
		}
	}

	private static String readHeaders(Socket client) throws Exception {
		StringBuilder headers = new StringBuilder();
		while (!headers.toString().endsWith("\r\n\r\n")) {
			int next = client.getInputStream().read();
			assertNotEquals(-1, next);
			headers.append((char) next);
		}
		return headers.toString();
	}

	private static final class ReaderFixture implements AutoCloseable {
		final int port = findFreePort();
		final Resource resource = new Resource();
		final DefaultSseServer server;
		final Soklet soklet;
		final AtomicInteger reading = new AtomicInteger(), acceptedRequests = new AtomicInteger(), rejectedRequests = new AtomicInteger();
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final List<ConnectionRejectionReason> connectionRejections = new CopyOnWriteArrayList<>();
		final List<RequestReadFailureReason> readFailures = new CopyOnWriteArrayList<>();
		final List<MetricsCollector.TransportFailureReason> transportFailures = new CopyOnWriteArrayList<>();
		ReaderFixture(Duration headerTimeout, Duration gracefulTimeout, int queueCapacity) throws Exception {
			this(headerTimeout, gracefulTimeout, queueCapacity, null);
		}
		ReaderFixture(Duration headerTimeout, Duration gracefulTimeout, int queueCapacity, Integer maximumRequestSize) throws Exception {
			this(headerTimeout, gracefulTimeout, queueCapacity, maximumRequestSize, null);
		}
		ReaderFixture(Duration headerTimeout, Duration gracefulTimeout, int queueCapacity, Integer maximumRequestSize,
				IdGenerator<?> idGenerator) throws Exception {
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
					.requestHandlerConcurrency(1).requestHandlerQueueCapacity(queueCapacity)
					.requestHeaderTimeout(headerTimeout).requestHandlerTimeout(Duration.ofSeconds(2))
					.maximumRequestSizeInBytes(maximumRequestSize)
					.idGenerator(idGenerator)
					.verifyConnectionOnceEstablished(false).build();
			SokletConfig config = SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}}).lifecyclePolicy(gracefulTimeout == null ? LifecyclePolicy.defaultInstance()
							: LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(gracefulTimeout).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.metricsCollector(new MetricsCollector() {
						@Override public void didRecordTransportFailure(ServerType type, TransportFailureReason reason, Throwable failure) {
							transportFailures.add(reason);
						}
					})
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void willReadRequest(ServerType type, java.net.InetSocketAddress remote, String target) { reading.incrementAndGet(); }
						@Override public void didAcceptRequest(ServerType type, java.net.InetSocketAddress remote, String target) { acceptedRequests.incrementAndGet(); }
						@Override public void didFailToAcceptRequest(ServerType type, java.net.InetSocketAddress remote, String target,
								RequestRejectionReason reason, Throwable failure) { rejectedRequests.incrementAndGet(); }
						@Override public void didFailToAcceptConnection(ServerType type, java.net.InetSocketAddress remote,
								ConnectionRejectionReason reason, Throwable failure) { connectionRejections.add(reason); }
						@Override public void didFailToReadRequest(ServerType type, java.net.InetSocketAddress remote, String target,
								RequestReadFailureReason reason, Throwable failure) { readFailures.add(reason); }
					}).build();
			soklet = Soklet.fromConfig(config);
			soklet.start();
		}
		Socket partialRequest() throws Exception { return request("GET /events HTTP/1.1\r\nHost: localhost\r\n"); }
		Socket completeRequest() throws Exception { return request("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"); }
		Socket openSocket() throws Exception {
			Socket client = connectWithRetry("127.0.0.1", port, 2000); client.setSoTimeout(3000); return client;
		}
		private Socket request(String bytes) throws Exception {
			Socket client = openSocket();
			client.getOutputStream().write(bytes.getBytes(StandardCharsets.ISO_8859_1)); return client;
		}
		@Override public void close() { resource.release.countDown(); soklet.close(); }
	}

	private static String read(Socket client) throws Exception { return new String(client.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1); }
	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean());
	}
	public static final class Resource {
		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
		final AtomicInteger initializers = new AtomicInteger();
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger producerCalls = new AtomicInteger();
		final IllegalStateException failure = new IllegalStateException("initializer failed");
		volatile String initializerMode = "normal";
		volatile boolean invalidHeader;
		volatile boolean ignoreInterrupts;
		@SseEventSource("/events") public SseHandshakeResult events() {
			calls.incrementAndGet();
			return SseHandshakeResult.Accepted.builder().headers(invalidHeader ? Map.of("Content-Length", List.of("0")) : Map.of())
					.clientInitializer(unicaster -> {
						initializers.incrementAndGet();
						if (initializerMode.equals("failure")) throw failure;
						if (initializerMode.equals("overflow")) {
							unicaster.unicastEvent(SseEvent.withEvent("event").data("one").build());
							unicaster.unicastEvent(SseEvent.withEvent("event").data("two").build());
						}
					}).build();
		}
		@SseEventSource("/slow") public SseHandshakeResult slow() {
			entered.countDown();
			boolean wasInterrupted = false;
			while (true) {
				try { release.await(); break; }
				catch (InterruptedException ignored) {
					interrupted.countDown();
					wasInterrupted = true;
					if (!ignoreInterrupts) break;
				}
			}
			if (wasInterrupted) Thread.currentThread().interrupt();
			return SseHandshakeResult.accept();
		}
	}
	private static final class Fixture implements AutoCloseable {
		final int port = findFreePort();
		final Resource resource = new Resource();
		final DefaultSseServer server;
		final SokletConfig config;
		final Soklet soklet;
		final List<LogEvent> logs = new CopyOnWriteArrayList<>();
		final List<StreamTermination> terminations = new CopyOnWriteArrayList<>();
		final List<ConnectionRejectionReason> connectionRejections = new CopyOnWriteArrayList<>();
		final List<Integer> writes = new CopyOnWriteArrayList<>(), finishes = new CopyOnWriteArrayList<>();
		Fixture(Duration timeout, Integer replacementStatus) throws Exception {
			server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1").requestHandlerTimeout(timeout)
					.connectionQueueCapacity(1).verifyConnectionOnceEstablished(false).build();
			SokletConfig.Builder builder = SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}}).lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(1))
							.gracefulShutdownTimeout(Duration.ofSeconds(3)).forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
						@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) { terminations.add(termination); }
						@Override public void didFailToAcceptConnection(ServerType type, java.net.InetSocketAddress remote, ConnectionRejectionReason reason, Throwable failure) { connectionRejections.add(reason); }
						@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) { writes.add(response.getStatusCode()); }
						@Override public void didFinishRequestHandling(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration, List<Throwable> failures) { finishes.add(response.getStatusCode()); }
					});
			if (replacementStatus != null) builder.requestInterceptor(new RequestInterceptor() {
				@Override public void interceptRequest(ServerType type, Request request, ResourceMethod method,
						Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
					MarshaledResponse generated = generator.apply(request);
					if (replacementStatus == -1) {
						var headers = new java.util.LinkedHashMap<>(generated.getHeaders());
						headers.put("X-Test", List.of("decorated"));
						writer.accept(generated.copy().headers(headers).finish());
					} else if (replacementStatus == -2) {
						writer.accept(MarshaledResponse.withStatusCode(200).stream(stream -> resource.producerCalls.incrementAndGet()).build());
					} else writer.accept(MarshaledResponse.withStatusCode(replacementStatus).body("denied".getBytes(StandardCharsets.UTF_8)).build());
				}
			});
			config = builder.build(); soklet = Soklet.fromConfig(config);
		}
		Socket request(String path) throws Exception {
			Socket client = connectWithRetry("127.0.0.1", port, 2000); client.setSoTimeout(3000);
			client.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			return client;
		}
		@Override public void close() { resource.release.countDown(); soklet.close(); }
	}
}
