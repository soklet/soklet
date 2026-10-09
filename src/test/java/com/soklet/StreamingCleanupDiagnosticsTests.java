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
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** Application cleanup diagnostics carry the originating stream's context in both HTTP adapters. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingCleanupDiagnosticsTests {
	@Test
	void httpFinalizerFailureHasCleanupClassificationAndOriginalStreamContext() throws Exception {
		assertFinalizerFailure(false);
	}

	@Test
	void simulatorFinalizerFailureHasCleanupClassificationAndOriginalStreamContext() throws Exception {
		assertFinalizerFailure(true);
	}

	private static void assertFinalizerFailure(boolean simulated) throws Exception {
		IOException failure = new IOException("application close failed");
		AtomicInteger closes = new AtomicInteger();
		Observation observation = new Observation(false);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream ->
				stream.open(() -> (AutoCloseable) () -> { closes.incrementAndGet(); throw failure; })));
		if (simulated) {
			SokletSimulator.run(simulatorConfig(resource, observation), simulator -> {
				IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class,
						() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/stream")));
				Assertions.assertSame(failure, exception.getCause());
			});
		} else {
			try (HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofSeconds(5))) {
				fixture.start();
				String wire = fixture.request("/stream");
				Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK"), wire);
				Assertions.assertFalse(wire.endsWith("0\r\n\r\n"), "Failed cleanup must not report a complete body");
			}
		}
		Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
		Assertions.assertEquals(1, closes.get());
		Assertions.assertEquals(1, observation.terminationCalls.get());
		Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, observation.termination.get().getReason());
		Assertions.assertSame(failure, observation.termination.get().getCause().orElseThrow());
		assertCleanupDiagnostic(observation, resource, failure);
	}

	@Test
	void abortFailureKeepsThePreviouslyElectedTimeoutAndReportsCleanupContext() throws Exception {
		IOException failure = new IOException("application abort failed");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch releaseWriter = new CountDownLatch(1);
		AtomicInteger aborts = new AtomicInteger();
		AtomicInteger closes = new AtomicInteger();
		Observation observation = new Observation(false);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream -> {
			stream.open(() -> (AutoCloseable) closes::incrementAndGet, value -> { aborts.incrementAndGet(); throw failure; });
			entered.countDown();
			awaitUninterruptibly(releaseWriter);
		}));
		HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofMillis(150));
		try (fixture) {
			fixture.start();
			String wire = fixture.request("/stream");
			Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK"), wire);
			Assertions.assertEquals(0, entered.getCount());
			Assertions.assertTrue(observation.cleanupEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, observation.termination.get().getReason());
			Assertions.assertTrue(observation.termination.get().getCause().isEmpty());
			assertCleanupDiagnostic(observation, resource, failure);
		} finally { releaseWriter.countDown(); fixture.awaitPhysicalExit(); }
		Assertions.assertEquals(1, aborts.get());
		Assertions.assertEquals(1, closes.get());
	}

	@Test
	// Live HTTP and simulator owners run sequentially with separate cleanup budgets.
	@Timeout(115)
	void ordinaryWriterFailureIsStillAProducerFailureWithoutACleanupDiagnostic() throws Exception {
		for (boolean simulated : List.of(false, true)) {
			IOException failure = new IOException("application writer failed");
			Observation observation = new Observation(false);
			TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream -> { throw failure; }));
			if (simulated) {
				SokletSimulator.run(simulatorConfig(resource, observation), simulator ->
						Assertions.assertThrows(IllegalStateException.class,
								() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/stream"))));
			} else {
				try (HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofSeconds(5))) {
					fixture.start();
					Assertions.assertTrue(fixture.request("/stream").startsWith("HTTP/1.1 200 OK"));
				}
			}
			Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, observation.termination.get().getReason());
			Assertions.assertSame(failure, observation.termination.get().getCause().orElseThrow());
			Assertions.assertTrue(observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED).isEmpty());
			Assertions.assertTrue(observation.logs(LogEventType.SERVER_INTERNAL_ERROR).isEmpty());
			Assertions.assertEquals(1, observation.logs(LogEventType.RESPONSE_STREAM_FAILED).size());
		}
	}

	@Test
	void simulatorTypedDeadlineCancelationLogsBeforeItsTerminalCallback() throws Exception {
		Observation observation = new Observation(false);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream -> {
			// The simulator has no wall-clock response timer. A cooperative producer
			// can report a typed deadline winner, retaining the live diagnostic shape.
			throw new StreamingResponseCanceledException(StreamTerminationReason.RESPONSE_TIMEOUT);
		}));
		SokletConfig config = SokletConfig.withHttpServer(HttpServer.withPort(0).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TestResource.class)))
				.instanceProvider(provider(resource)).lifecycleObserver(observation).build();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config), simulator ->
				Assertions.assertThrows(IllegalStateException.class,
						() -> simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/stream"))));
		Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, observation.termination.get().getReason());
		List<LogEvent> diagnostics = observation.logs(LogEventType.RESPONSE_STREAM_CANCELED);
		Assertions.assertEquals(1, diagnostics.size());
		Assertions.assertEquals("Streaming response terminated: RESPONSE_TIMEOUT", diagnostics.get(0).getMessage());
		Assertions.assertEquals("/stream", diagnostics.get(0).getRequest().orElseThrow().getPath());
		Assertions.assertTrue(observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED).isEmpty());
	}

	@Test
	void cleanupDeadlineStillReportsFrameworkSupervisionAndRetainsPhysicalWork() throws Exception {
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		Observation observation = new Observation(false);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream ->
				stream.open(() -> (AutoCloseable) () -> { closeEntered.countDown(); awaitUninterruptibly(releaseClose); })));
		HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofSeconds(5), Duration.ofMillis(100));
		try (fixture) {
			fixture.start();
			Assertions.assertTrue(fixture.request("/stream").startsWith("HTTP/1.1 200 OK"));
			Assertions.assertEquals(0, closeEntered.getCount());
			Assertions.assertTrue(observation.frameworkEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, observation.termination.get().getReason());
			Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
					observation.logs(LogEventType.SERVER_INTERNAL_ERROR).get(0).getThrowable().orElseThrow());
			Assertions.assertTrue(observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED).isEmpty());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().runningProducers());
			Assertions.assertTrue(fixture.request("/health").endsWith("ok"));
		} finally { releaseClose.countDown(); fixture.awaitPhysicalExit(); }
	}

	@Test
	void untypedUpstreamExitAfterCancelationIsProducerEvidenceAndNotACleanupFailure() throws Exception {
		CountDownLatch upstreamClosed = new CountDownLatch(1);
		IOException failure = new IOException("upstream iteration ended because cancelation closed it");
		Observation observation = new Observation(false);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream -> {
			stream.open(() -> (AutoCloseable) upstreamClosed::countDown);
			awaitUninterruptibly(upstreamClosed);
			throw failure;
		}));
		HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofMillis(150));
		try (fixture) {
			fixture.start();
			Assertions.assertTrue(fixture.request("/stream").startsWith("HTTP/1.1 200 OK"));
			Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, observation.termination.get().getReason());
			fixture.coordinator().requestGracefulShutdown();
			fixture.awaitPhysicalExit();
			Assertions.assertTrue(observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED).isEmpty());
			Assertions.assertTrue(observation.logs(LogEventType.SERVER_INTERNAL_ERROR).isEmpty());
			// An untyped application IOException cannot prove whether the owned close
			// caused the failure. Preserve it as producer evidence with its context.
			List<LogEvent> producerFailures = observation.logs(LogEventType.RESPONSE_STREAM_FAILED);
			Assertions.assertEquals(1, producerFailures.size());
			Assertions.assertSame(failure, producerFailures.get(0).getThrowable().orElseThrow());
			Assertions.assertSame(resource.request.get(), producerFailures.get(0).getRequest().orElseThrow());
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT, observation.termination.get().getReason());
		}
	}

	@Test
	void blockedCleanupLogObserverRetainsItsSlotAndShutdownEvidenceWithoutHoldingTheTransport() throws Exception {
		IOException failure = new IOException("close failed while logger blocks");
		Observation observation = new Observation(true);
		TestResource resource = new TestResource(StreamingResponseBody.fromWriter(stream ->
				stream.open(() -> (AutoCloseable) () -> { throw failure; })));
		HttpFixture fixture = new HttpFixture(resource, observation, Duration.ofSeconds(5));
		try (fixture) {
			fixture.start();
			Assertions.assertTrue(fixture.request("/stream").startsWith("HTTP/1.1 200 OK"));
			Assertions.assertTrue(observation.cleanupEntered.await(3, TimeUnit.SECONDS));
			Assertions.assertTrue(observation.terminated.await(3, TimeUnit.SECONDS));
			assertCleanupDiagnostic(observation, resource, failure);
			Assertions.assertEquals(1, fixture.coordinator().snapshot().diagnostics());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
			Assertions.assertTrue(fixture.request("/health").endsWith("ok"));
			ShutdownResult result = fixture.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
			Assertions.assertFalse(result.isComplete(), "A blocked application logger remains physical work");
			Assertions.assertFalse(fixture.coordinator().isTerminated());
		} finally { observation.releaseCleanup.countDown(); fixture.awaitPhysicalExit(); }
		Assertions.assertEquals(1, observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED).size());
	}

	private static void assertCleanupDiagnostic(Observation observation, TestResource resource, Throwable failure) {
		List<LogEvent> diagnostics = observation.logs(LogEventType.RESPONSE_STREAM_CLOSE_FAILED);
		Assertions.assertEquals(1, diagnostics.size(), "Application cleanup needs one typed diagnostic");
		LogEvent event = diagnostics.get(0);
		Assertions.assertSame(failure, event.getThrowable().orElseThrow());
		Assertions.assertTrue(event.getRequest().isPresent(), "Cleanup diagnostic lost the request");
		Assertions.assertTrue(event.getResourceMethod().isPresent(), "Cleanup diagnostic lost the resource method");
		Assertions.assertTrue(event.getMarshaledResponse().isPresent(), "Cleanup diagnostic lost the response");
		Assertions.assertSame(resource.request.get(), event.getRequest().orElseThrow());
		StreamingResponseHandle handle = observation.handle.get();
		Assertions.assertSame(handle.getRequest(), event.getRequest().orElseThrow());
		Assertions.assertSame(handle.getResourceMethod().orElseThrow(), event.getResourceMethod().orElseThrow());
		Assertions.assertSame(handle.getMarshaledResponse(), event.getMarshaledResponse().orElseThrow());
		Assertions.assertTrue(event.getMarshaledResponse().orElseThrow().isStreaming());
		Assertions.assertTrue(observation.logs(LogEventType.SERVER_INTERNAL_ERROR).isEmpty());
	}

	private static SimulatorConfig simulatorConfig(TestResource resource, Observation observation) {
		return SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TestResource.class)))
				.instanceProvider(provider(resource)).lifecycleObserver(observation).build();
	}

	private static InstanceProvider provider(TestResource resource) {
		return new InstanceProvider() {
			@Override public <T> T provide(Class<T> type) {
				return type == TestResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
			}
		};
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static final class HttpFixture implements AutoCloseable {
		private final int port = findFreePort();
		private final DefaultHttpServer server;
		private final Soklet soklet;
		private StreamLifecycleCoordinator coordinator;
		private HttpFixture(TestResource resource, Observation observation, Duration responseTimeout) throws IOException {
			this(resource, observation, responseTimeout, Duration.ofSeconds(5));
		}
		private HttpFixture(TestResource resource, Observation observation, Duration responseTimeout, Duration cleanupTimeout) throws IOException {
			this.server = (DefaultHttpServer) HttpServer.withPort(this.port).host("127.0.0.1").concurrency(1)
					.streamingLifecycleCapacity(1).streamingCallbackConcurrency(1)
					.streamingResponseTimeout(responseTimeout).streamingCleanupTimeout(cleanupTimeout).build();
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(TestResource.class)))
					.instanceProvider(provider(resource)).lifecycleObserver(observation)
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(100))
							.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build());
		}
		private void start() {
			this.soklet.start();
			this.coordinator = this.server.getStreamLifecycleCoordinatorForTests().orElseThrow();
		}
		private StreamLifecycleCoordinator coordinator() { return this.coordinator; }
		private String request(String path) throws Exception {
			try (Socket socket = connectWithRetry("127.0.0.1", this.port, 3_000)) {
				socket.setSoTimeout(3_000);
				socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
				return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
			}
		}
		private void awaitPhysicalExit() throws Exception {
			Assertions.assertTrue(coordinator().awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
		}
		@Override public void close() throws Exception {
			this.soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
		}
	}

	private static final class Observation implements LifecycleObserver {
		private final List<LogEvent> events = new CopyOnWriteArrayList<>();
		private final CountDownLatch cleanupEntered = new CountDownLatch(1);
		private final CountDownLatch frameworkEntered = new CountDownLatch(1);
		private final CountDownLatch releaseCleanup;
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final AtomicReference<StreamingResponseHandle> handle = new AtomicReference<>();
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private final AtomicInteger terminationCalls = new AtomicInteger();
		private Observation(boolean blockCleanup) { this.releaseCleanup = new CountDownLatch(blockCleanup ? 1 : 0); }
		private List<LogEvent> logs(LogEventType type) {
			return this.events.stream().filter(event -> event.getLogEventType() == type).toList();
		}
		@Override public void didReceiveLogEvent(LogEvent event) {
			this.events.add(event);
			if (event.getLogEventType() == LogEventType.RESPONSE_STREAM_CLOSE_FAILED) {
				this.cleanupEntered.countDown(); awaitUninterruptibly(this.releaseCleanup);
			}
			if (event.getLogEventType() == LogEventType.SERVER_INTERNAL_ERROR) this.frameworkEntered.countDown();
		}
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
			this.handle.set(handle); this.termination.set(termination); this.terminationCalls.incrementAndGet();
			this.terminated.countDown();
		}
	}

	public static final class TestResource {
		private final StreamingResponseBody body;
		private final AtomicReference<Request> request = new AtomicReference<>();
		private TestResource(StreamingResponseBody body) { this.body = body; }
		@GET("/stream") public MarshaledResponse stream(Request request) {
			this.request.set(request);
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(this.body).build();
		}
		@GET("/health") public String health() { return "ok"; }
	}
}
