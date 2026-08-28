package com.soklet;

import com.soklet.internal.microhttp.EventLoop;
import com.soklet.internal.microhttp.Header;
import com.soklet.internal.microhttp.MicrohttpRequest;
import com.soklet.internal.microhttp.MicrohttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import static com.soklet.TestSupport.readAll;

public class DefaultHttpServerTests {
	private static final LifecyclePolicy TEST_LIFECYCLE_POLICY =
			LifecyclePolicy.builder()
					.startupTimeout(Duration.ofSeconds(5))
					.startupCancellationTimeout(Duration.ofSeconds(2))
					.gracefulShutdownDuration(Duration.ofSeconds(2))
					.forcedShutdownDuration(Duration.ofSeconds(1))
					.build();

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
	public void shutdownDeadlineBelongsToLifecyclePolicyNotTransportBuilder() {
		Assertions.assertThrows(NoSuchMethodException.class, () ->
				HttpServer.Builder.class.getMethod("shutdownTimeout",
						Duration.class));
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
				.headers(Map.of(
						"Content-Type", Set.of("text/plain"),
						"ETag", Set.of("\"v1\"")))
				.body(body)
				.build();

		MicrohttpResponse microhttpResponse = server.toMicrohttpResponse(request, null, marshaledResponse);

		Assertions.assertTrue(microhttpResponse.hasHeader("Content-Encoding"));
		Assertions.assertEquals("W/\"v1\"", headerValue(microhttpResponse, "ETag"));
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

	@Test
	public void httpServerCleansUpAfterUnexpectedEventLoopTermination() throws Exception {
		RecordingExecutorService requestHandlerExecutorService = new RecordingExecutorService();
		RecordingExecutorService streamingExecutorService = new RecordingExecutorService();
		CountDownLatch acceptFailureLatch = new CountDownLatch(1);
		CopyOnWriteArrayList<LogEvent> logEvents = new CopyOnWriteArrayList<>();
		DefaultMetricsCollector metricsCollector = DefaultMetricsCollector.defaultInstance();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerExecutorServiceSupplier(() -> requestHandlerExecutorService)
				.streamingExecutorServiceSupplier(() -> streamingExecutorService)
				.build();
		SokletConfig sokletConfig = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.metricsCollector(metricsCollector)
				.lifecycleObserver(new LifecycleObserver() {
					@Override
					public void didReceiveLogEvent(LogEvent logEvent) {
						logEvents.add(logEvent);
					}

					@Override
					public void didFailToAcceptConnection(ServerType serverType,
																								InetSocketAddress remoteAddress,
																								ConnectionRejectionReason reason,
																								Throwable throwable) {
						if (serverType == ServerType.STANDARD_HTTP && reason == ConnectionRejectionReason.INTERNAL_ERROR)
							acceptFailureLatch.countDown();
					}
				})
				.build();
		server.initialize(sokletConfig, (request, requestResultConsumer) -> {});

		try {
			server.start();

			EventLoop eventLoop = server.getEventLoop().orElseThrow();
			Field selectorField = EventLoop.class.getDeclaredField("selector");
			selectorField.setAccessible(true);
			((Selector) selectorField.get(eventLoop)).close();

			waitUntil(() -> !server.isStarted()
					&& requestHandlerExecutorService.isShutdown()
					&& streamingExecutorService.isShutdown()
					&& acceptFailureLatch.getCount() == 0, 2000);

			Assertions.assertFalse(server.isStarted());
			Assertions.assertTrue(requestHandlerExecutorService.isShutdown());
			Assertions.assertTrue(streamingExecutorService.isShutdown());
			Assertions.assertTrue(acceptFailureLatch.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(Long.valueOf(1L), metricsCollector.snapshot().orElseThrow().getTransportFailures()
					.get(new MetricsCollector.TransportFailureKey(
							ServerType.STANDARD_HTTP,
							MetricsCollector.TransportFailureReason.EVENT_LOOP_TERMINATED)));
			Assertions.assertTrue(logEvents.stream().anyMatch(logEvent ->
					logEvent.getLogEventType() == LogEventType.SERVER_TRANSPORT_FAILURE), logEvents.toString());
		} finally {
			server.stop();
			requestHandlerExecutorService.shutdownNow();
			streamingExecutorService.shutdownNow();
		}
	}

	@Test
	public void stopCannotPublishAnEmptyGenerationWhileStartInstallsHttpResources()
			throws Exception {
		CountDownLatch supplierEntered = new CountDownLatch(1);
		CountDownLatch releaseSupplier = new CountDownLatch(1);
		CountDownLatch stopReturned = new CountDownLatch(1);
		RecordingExecutorService requestExecutor = new RecordingExecutorService();
		RecordingExecutorService streamingExecutor = new RecordingExecutorService();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerExecutorServiceSupplier(() -> {
					supplierEntered.countDown();
					awaitUninterruptibly(releaseSupplier);
					return requestExecutor;
				})
				.streamingExecutorServiceSupplier(() -> streamingExecutor)
				.build();
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() {})
				.build(), (request, responseConsumer) -> {});
		AtomicReference<Throwable> startFailure = new AtomicReference<>();
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread startThread = new Thread(() -> {
			try {
				server.start();
			} catch (Throwable throwable) {
				startFailure.set(throwable);
			}
		}, "http-start-publication-race");
		Thread stopThread = new Thread(() -> {
			try {
				server.stop();
			} catch (Throwable throwable) {
				stopFailure.set(throwable);
			} finally {
				stopReturned.countDown();
			}
		}, "http-stop-publication-race");

		try {
			startThread.start();
			Assertions.assertTrue(supplierEntered.await(1, TimeUnit.SECONDS));
			stopThread.start();
			Assertions.assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS),
					"Stop must serialize behind generation resource publication");
			Assertions.assertTrue(server.getLifecycleAdapter().result().isEmpty(),
					"Stop must not prove the partially published generation empty");
		} finally {
			releaseSupplier.countDown();
		}
		startThread.join(3_000L);
		stopThread.join(3_000L);

		Assertions.assertFalse(startThread.isAlive());
		Assertions.assertFalse(stopThread.isAlive());
		Assertions.assertNull(startFailure.get());
		Assertions.assertNull(stopFailure.get());
		Assertions.assertFalse(server.isStarted());
		Assertions.assertTrue(server.getEventLoop().isEmpty());
		Assertions.assertTrue(server.getRequestHandlerExecutorService().isEmpty());
		Assertions.assertTrue(server.getStreamingExecutorService().isEmpty());
		Assertions.assertTrue(requestExecutor.isShutdown());
		Assertions.assertTrue(streamingExecutor.isShutdown());
	}

	@Test
	public void startRejectsRunningHttpGenerationWhileItsStopIsInProgress()
			throws Exception {
		CountDownLatch taskStarted = new CountDownLatch(1);
		CountDownLatch releaseTask = new CountDownLatch(1);
		ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
		requestExecutor.submit(() -> {
			taskStarted.countDown();
			awaitUninterruptibly(releaseTask);
		});
		RecordingExecutorService streamingExecutor = new RecordingExecutorService();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerExecutorServiceSupplier(() -> requestExecutor)
				.streamingExecutorServiceSupplier(() -> streamingExecutor)
				.build();
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.gracefulShutdownDuration(Duration.ofSeconds(5))
						.build())
				.lifecycleObserver(new LifecycleObserver() {})
				.build(), (request, responseConsumer) -> {});
		Thread stopThread = new Thread(server::stop, "http-running-start-stop-race");

		try {
			Assertions.assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
			server.start();
			stopThread.start();
			waitUntil(server.getLifecycleAdapter()::shutdownInProgress, 2_000L);

			IllegalStateException failure = Assertions.assertThrows(
					IllegalStateException.class, server::start);
			Assertions.assertEquals(
					"Cannot start HTTP server while shutdown is in progress",
					failure.getMessage());
			Assertions.assertTrue(stopThread.isAlive());
		} finally {
			releaseTask.countDown();
			stopThread.join(3_000L);
			server.stop();
			requestExecutor.shutdownNow();
		}

		Assertions.assertFalse(stopThread.isAlive());
		Assertions.assertFalse(server.isStarted());
	}

	@Test
	public void startupErrorRetainsItsIdentityAndRollsBackPartialHttpResources() {
		AssertionError startupError = new AssertionError("streaming executor creation failed");
		RecordingExecutorService requestExecutor = new RecordingExecutorService();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerExecutorServiceSupplier(() -> requestExecutor)
				.streamingExecutorServiceSupplier(() -> {
					throw startupError;
				})
				.build();
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecycleObserver(new LifecycleObserver() {})
				.build(), (request, responseConsumer) -> {});

		AssertionError thrown = Assertions.assertThrows(AssertionError.class, server::start);

		Assertions.assertSame(startupError, thrown);
		Assertions.assertTrue(requestExecutor.isShutdown());
		Assertions.assertTrue(server.getRequestHandlerExecutorService().isEmpty());
		Assertions.assertTrue(server.getStreamingExecutorService().isEmpty());
		InternalShutdownResult result = server.getLifecycleAdapter().result().orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				result.startupDisposition());
		Assertions.assertTrue(result.isComplete());
		Assertions.assertEquals(List.of(startupError), result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow().failures());
	}

	@Test
	public void earlySetupErrorRetainsIdentityCleansGenerationAndAllowsHttpRestart() {
		AssertionError startupError = new AssertionError("early HTTP setup failed");
		AtomicInteger setupAttempts = new AtomicInteger();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.build();
		server.setStartSetupHookForTests(() -> {
			if (setupAttempts.getAndIncrement() == 0)
				throw startupError;
		});
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.build(), (request, responseConsumer) -> {});

		AssertionError thrown = Assertions.assertThrows(AssertionError.class, server::start);

		Assertions.assertSame(startupError, thrown);
		InternalShutdownResult failedResult = server.getLifecycleAdapter().result()
				.orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				failedResult.startupDisposition());
		Assertions.assertTrue(failedResult.isComplete());
		Assertions.assertEquals(List.of(startupError), failedResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow().failures());
		Assertions.assertTrue(server.getEventLoop().isEmpty());
		Assertions.assertTrue(server.getRequestHandlerExecutorService().isEmpty());
		Assertions.assertTrue(server.getRequestHandlerTimeoutScheduler().isEmpty());

		try {
			server.start();
			Assertions.assertTrue(server.isStarted());
			Assertions.assertEquals(2, setupAttempts.get());
		} finally {
			server.stop();
		}
		Assertions.assertFalse(server.isStarted());
	}

	@Test
	public void liveHttpTimeoutSchedulerIsPositiveResidualEvidence() throws Exception {
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).build();
		TimeoutScheduler scheduler = new TimeoutScheduler(
				runnable -> new Thread(runnable, "http-residual-timeout-scheduler"));
		Field schedulerField = DefaultHttpServer.class.getDeclaredField(
				"requestHandlerTimeoutScheduler");
		schedulerField.setAccessible(true);
		schedulerField.set(server, scheduler);

		try {
			Assertions.assertFalse(scheduler.isTerminated());
			Assertions.assertTrue(lifecycleOperations(server.getLifecycleAdapter())
					.residualActivity().contains(
							InternalResidualActivityKind.EXECUTOR_TASK));
		} finally {
			scheduler.shutdownNow();
			Assertions.assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
		}
	}

	@Test
	public void staleHttpEventLoopFailureDoesNotClobberRestartedServer() throws Exception {
		RecordingExecutorService firstRequestHandlerExecutorService = new RecordingExecutorService();
		RecordingExecutorService secondRequestHandlerExecutorService = new RecordingExecutorService();
		RecordingExecutorService firstStreamingExecutorService = new RecordingExecutorService();
		RecordingExecutorService secondStreamingExecutorService = new RecordingExecutorService();
		AtomicInteger requestHandlerExecutorServiceIndex = new AtomicInteger();
		AtomicInteger streamingExecutorServiceIndex = new AtomicInteger();
		RecordingExecutorService[] requestHandlerExecutorServices = {
				firstRequestHandlerExecutorService,
				secondRequestHandlerExecutorService
		};
		RecordingExecutorService[] streamingExecutorServices = {
				firstStreamingExecutorService,
				secondStreamingExecutorService
		};
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0)
				.host("127.0.0.1")
				.requestHandlerExecutorServiceSupplier(() ->
						requestHandlerExecutorServices[requestHandlerExecutorServiceIndex.getAndIncrement()])
				.streamingExecutorServiceSupplier(() ->
						streamingExecutorServices[streamingExecutorServiceIndex.getAndIncrement()])
				.build();
		SokletConfig sokletConfig = SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(TEST_LIFECYCLE_POLICY)
				.lifecycleObserver(new LifecycleObserver() {})
				.build();
		server.initialize(sokletConfig, (request, requestResultConsumer) -> {});

		try {
			server.start();
			BuiltInTransportLifecycleAdapter.Generation staleGeneration = server
					.getLifecycleAdapter().generation().orElseThrow();
			server.stop();

			server.start();
			EventLoop restartedEventLoop = server.getEventLoop().orElseThrow();

			server.getLifecycleAdapter().signalUnexpectedFailure(staleGeneration,
					new AssertionError("stale event loop"));

			Assertions.assertTrue(server.isStarted());
			Assertions.assertSame(restartedEventLoop, server.getEventLoop().orElseThrow());
			Assertions.assertFalse(secondRequestHandlerExecutorService.isShutdown());
			Assertions.assertFalse(secondStreamingExecutorService.isShutdown());
		} finally {
			server.stop();
		}
	}

	private static byte[] gunzip(byte[] bytes) throws IOException {
		try (GZIPInputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
			return readAll(inputStream);
		}
	}

	private static String headerValue(MicrohttpResponse response, String name) {
		return response.headers().stream()
				.filter(header -> header.name().equalsIgnoreCase(name))
				.map(Header::value)
				.findFirst()
				.orElse(null);
	}

	private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return;

			Thread.sleep(10);
		}

		Assertions.fail("Timed out waiting for condition");
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static BuiltInTransportLifecycleAdapter.Operations lifecycleOperations(
			BuiltInTransportLifecycleAdapter adapter) throws Exception {
		Field operationsField = BuiltInTransportLifecycleAdapter.class
				.getDeclaredField("operations");
		operationsField.setAccessible(true);
		return (BuiltInTransportLifecycleAdapter.Operations) operationsField.get(adapter);
	}

	private static final class RecordingExecutorService extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean(false);

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
		public boolean awaitTermination(long timeout,
																		TimeUnit unit) {
			return true;
		}

		@Override
		public void execute(Runnable command) {
			command.run();
		}
	}
}
