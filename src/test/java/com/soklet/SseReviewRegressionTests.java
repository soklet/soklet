package com.soklet;

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class SseReviewRegressionTests {
	public static class Events {
		@SseEventSource("/events")
		public SseHandshakeResult events() { return SseHandshakeResult.accept(); }
	}

	private static final RequestInterceptor REWRITE = new RequestInterceptor() {
		@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> processor) {
			processor.accept(request.getPath().equals("/v1/events") ? request.copy().path("/events").finish() : request);
		}
	};

	@Test
	@Timeout(120)
	void cleanAndResetDisconnectsReleaseCapacityBeforeTheHeartbeat() throws Exception {
		for (boolean reset : List.of(false, true)) {
			int port = TestSupport.findFreePort();
			DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
			List<StreamTerminationReason> reasons = new CopyOnWriteArrayList<>();
			SseServer server = SseServer.withPort(port).host("127.0.0.1")
					.heartbeatInterval(Duration.ofHours(1)).streamingLifecycleCapacity(2)
					.concurrentConnectionLimit(1).build();
			Soklet.run(SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
					.lifecyclePolicy(shortLifecyclePolicy())
					.metricsCollector(metrics).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { }
						@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
							reasons.add(termination.getReason());
						}
					}).build(), () -> {
				for (int index = 1; index <= 4; index++) {
					try (Socket client = open(port, "/events")) {
						assertTrue(readUntil(client.getInputStream(), "\r\n\r\n").startsWith("HTTP/1.1 200"));
						// Consume the initial verification write before FIN; otherwise a
						// close with unread response bytes may be implemented as a reset.
						assertEquals(":\n\n", readUntil(client.getInputStream(), "\n\n"));
						if (reset) client.setSoLinger(true, 0);
					}
					int expected = index;
					await(() -> reasons.size() == expected && metrics.getActiveSseStreams() == 0);
				}
				assertEquals(4, reasons.size());
				assertTrue(reasons.stream().allMatch(reason -> reason == StreamTerminationReason.CLIENT_DISCONNECTED), reasons.toString());
			});
		}
	}


	@Test
	void singleWorkerWriterExecutorDoesNotDelayPeerCloseMonitoring() throws Exception {
		assertWriterExecutorDoesNotRunDisconnectMonitor(false);
	}

	@Test
	void saturatedCallerRunsWriterExecutorDoesNotRunDisconnectMonitoringInline() throws Exception {
		assertWriterExecutorDoesNotRunDisconnectMonitor(true);
	}

	private static void assertWriterExecutorDoesNotRunDisconnectMonitor(boolean callerRuns) throws Exception {
		java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(1, 1,
				0L, TimeUnit.MILLISECONDS, callerRuns ? new java.util.concurrent.SynchronousQueue<>()
						: new java.util.concurrent.LinkedBlockingQueue<>(), new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
		int port = TestSupport.findFreePort();
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(port).host("127.0.0.1")
				.heartbeatInterval(Duration.ofHours(1)).build();
		java.lang.reflect.Field supplier = DefaultSseServer.class.getDeclaredField("connectionExecutorServiceSupplier");
		supplier.setAccessible(true);
		supplier.set(server, (java.util.function.Supplier<java.util.concurrent.ExecutorService>) () -> executor);
		CountDownLatch established = new CountDownLatch(1), terminated = new CountDownLatch(1);
		try {
			Soklet.run(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
				.lifecyclePolicy(shortLifecyclePolicy()).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void didEstablishSseConnection(SseConnection connection) { established.countDown(); }
					@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
						assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, termination.getReason());
						terminated.countDown();
					}
				}).build(), () -> {
				try (Socket client = open(port, "/events")) {
					assertTrue(readUntil(client.getInputStream(), "\r\n\r\n").startsWith("HTTP/1.1 200"));
					assertEquals(":\n\n", readUntil(client.getInputStream(), "\n\n"));
					assertTrue(established.await(3, TimeUnit.SECONDS));
					server.acquireBroadcaster(ResourcePath.fromPath("/events")).orElseThrow()
							.broadcastEvent(SseEvent.withData("writer-available").build());
					assertEquals("data: writer-available\n\n", readUntil(client.getInputStream(), "\n\n"));
				}
				assertTrue(terminated.await(3, TimeUnit.SECONDS), "The writer executor must not delay peer-close monitoring");
			});
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	void rewrittenLiveHandshakeJoinsTheEffectivePathBroadcaster() throws Exception {
		int port = TestSupport.findFreePort();
		CountDownLatch established = new CountDownLatch(1);
		AtomicReference<Request> willEstablishRequest = new AtomicReference<>();
		AtomicReference<SseConnection> connection = new AtomicReference<>();
		SseServer server = SseServer.withPort(port).host("127.0.0.1").build();
		Soklet.run(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
				.lifecyclePolicy(shortLifecyclePolicy()).requestInterceptor(REWRITE).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void willEstablishSseConnection(Request request, ResourceMethod method) { willEstablishRequest.set(request); }
					@Override public void didEstablishSseConnection(SseConnection current) { connection.set(current); established.countDown(); }
				}).build(), () -> {
			try (Socket client = open(port, "/v1/events")) {
				assertTrue(readUntil(client.getInputStream(), "\r\n\r\n").startsWith("HTTP/1.1 200"));
				assertTrue(established.await(3, TimeUnit.SECONDS));
				assertEquals("/events", willEstablishRequest.get().getPath());
				assertEquals("/events", connection.get().getRequest().getPath());
				server.acquireBroadcaster(ResourcePath.fromPath("/events")).orElseThrow()
						.broadcastEvent(SseEvent.withData("rewritten").build());
				assertEquals(":\n\n", readUntil(client.getInputStream(), "\n\n"));
				assertEquals("data: rewritten\n\n", readUntil(client.getInputStream(), "\n\n"));
			}
		});
	}

	@Test
	void rewrittenSimulatedHandshakeJoinsTheEffectivePathBroadcaster() {
		AtomicReference<SseServer> server = new AtomicReference<>();
		List<SseEvent> events = new CopyOnWriteArrayList<>();
		SokletSimulator.run(SimulatorConfig.builder().sseServer(server::set)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class)))
				.requestInterceptor(REWRITE).build(), simulator -> {
			SseRequestResult.HandshakeAccepted accepted = assertInstanceOf(SseRequestResult.HandshakeAccepted.class,
					simulator.performSseRequest(Request.fromPath(HttpMethod.GET, "/v1/events")));
			accepted.registerEventConsumer(events::add);
			server.get().acquireBroadcaster(ResourcePath.fromPath("/events")).orElseThrow()
					.broadcastEvent(SseEvent.withData("rewritten").build());
			assertEquals("rewritten", events.getFirst().getData().orElseThrow());
		});
	}


	public static class OverlappingEvents {
		@SseEventSource("/feed/{id}") public SseHandshakeResult generic() { return SseHandshakeResult.accept(); }
		@SseEventSource("/feed/live") public SseHandshakeResult live() { return SseHandshakeResult.accept(); }
	}

	@Test
	void handshakeRouteAttributionUsesTheMostSpecificDeclaration() {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(OverlappingEvents.class)))
				.build(), (request, consumer) -> {});
		assertEquals("/feed/live", server.matchingResourcePath(ResourcePath.fromPath("/feed/live")).orElseThrow().getPath());
		assertEquals("/feed/{id}", server.matchingResourcePath(ResourcePath.fromPath("/feed/other")).orElseThrow().getPath());
	}

	@Test
	void largeAdmissionAndQueueLimitsDoNotEagerlyAllocateTheirMaximum() throws Exception {
		int port = TestSupport.findFreePort();
		SseServer server = SseServer.withPort(port).host("127.0.0.1")
				.concurrentConnectionLimit(Integer.MAX_VALUE).connectionQueueCapacity(Integer.MAX_VALUE).build();
		Soklet.run(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Events.class))).build(), () -> {
			try (Socket client = open(port, "/events")) {
				assertTrue(readUntil(client.getInputStream(), "\r\n\r\n").startsWith("HTTP/1.1 200"));
				assertEquals(":\n\n", readUntil(client.getInputStream(), "\n\n"));
			}
		});
	}

	@Test
	void sustainedAcceptFailuresCountEveryFailureAndCoalesceTheirLogs() throws Exception {
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).build();
		DefaultMetricsCollector metrics = DefaultMetricsCollector.defaultInstance();
		List<LogEvent> logs = new CopyOnWriteArrayList<>();
		server.initialize(SokletConfig.forSimulatorTesting().metricsCollector(metrics).lifecycleObserver(new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { logs.add(event); }
		}).build(), (request, consumer) -> {});
		java.lang.reflect.Method failed = DefaultSseServer.class.getDeclaredMethod("handleAcceptLoopIOException", java.io.IOException.class);
		failed.setAccessible(true);
		for (int index = 0; index < 6; index++) failed.invoke(server, new java.io.IOException("accept failed"));
		assertEquals(6L, metrics.snapshot().orElseThrow().getTransportFailures().get(new MetricsCollector.TransportFailureKey(
				ServerType.SSE, MetricsCollector.TransportFailureReason.ACCEPT_LOOP_ERROR)));
		assertEquals(3L, logs.stream().filter(event -> event.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE).count());
	}

	@Test
	void stalledRejectionLeavesVirtualThreadCarriersAvailable() throws Exception {
		Path output = Files.createTempFile("soklet-sse-carrier-", ".log");
		try {
			Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-Xmx256m", "-XX:ActiveProcessorCount=1", "-Djdk.virtualThreadScheduler.parallelism=1",
					"-Djdk.virtualThreadScheduler.maxPoolSize=1", "-cp",
					System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
					SseReviewRegressionTests.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try {
				assertTrue(process.waitFor(15, TimeUnit.SECONDS), "SSE carrier probe timed out");
				assertEquals(0, process.exitValue(), Files.readString(output));
			} finally { process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS)); }
		} finally { Files.deleteIfExists(output); }
	}

	public static class LargeRejection {
		@SseEventSource("/reject")
		public SseHandshakeResult reject() {
			return SseHandshakeResult.rejectWithResponse(Response.withStatusCode(403).body("x".repeat(8 * 1024 * 1024)).build());
		}
	}

	public static void main(String[] arguments) throws Exception {
		int port = TestSupport.findFreePort();
		CountDownLatch writing = new CountDownLatch(1);
		AtomicInteger written = new AtomicInteger();
		SseServer server = SseServer.withPort(port).host("127.0.0.1").writeTimeout(Duration.ofSeconds(8)).build();
		Soklet.run(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(LargeRejection.class)))
				.lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void willWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response) { writing.countDown(); }
					@Override public void didWriteResponse(ServerType type, Request request, ResourceMethod method, MarshaledResponse response, Duration duration) { written.incrementAndGet(); }
				}).build(), () -> {
			try (Socket client = new Socket()) {
				client.setReceiveBufferSize(1024);
				client.connect(new java.net.InetSocketAddress("127.0.0.1", port));
				send(client, "/reject");
				assertTrue(writing.await(3, TimeUnit.SECONDS));
				Thread.sleep(250);
				assertEquals(0, written.get(), "The nonreading client must still be blocking its response");
				CountDownLatch ran = new CountDownLatch(1);
				Thread probe = Thread.ofVirtual().start(ran::countDown);
				assertTrue(ran.await(1, TimeUnit.SECONDS), "A stalled SSE write pinned the sole virtual-thread carrier");
				probe.join();
			}
		});
	}


	private static LifecyclePolicy shortLifecyclePolicy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(1))
				.startupCancelationTimeout(Duration.ofSeconds(1))
				.gracefulShutdownTimeout(Duration.ofSeconds(1)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}

	private static Socket open(int port, String path) throws Exception {
		Socket client = TestSupport.connectWithRetry("127.0.0.1", port, 2000);
		client.setSoTimeout(3000);
		send(client, path);
		return client;
	}
	private static void send(Socket client, String path) throws Exception {
		client.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		client.getOutputStream().flush();
	}
	private static String readUntil(InputStream input, String terminator) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (int count = 0; count < 16_384; count++) {
			int value = input.read();
			assertTrue(value >= 0, "Unexpected EOF");
			bytes.write(value);
			String text = bytes.toString(StandardCharsets.UTF_8);
			if (text.endsWith(terminator)) return text;
		}
		throw new AssertionError("Response exceeded test bound");
	}
	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(condition.getAsBoolean(), "Disconnected SSE client retained its slot or metric");
	}
}
