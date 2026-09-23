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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static java.util.Objects.requireNonNull;

/** Tests lifecycle admission and physical cleanup through a real HTTP connection. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingLifecycleTests {
	private static final Duration WAIT = Duration.ofSeconds(3);
	private static final LifecyclePolicy SHUTDOWN_POLICY = LifecyclePolicy.builder()
			.gracefulShutdownTimeout(Duration.ofMillis(200))
			.forcedShutdownTimeout(Duration.ofMillis(200)).build();

	@Test
	public void gracefulShutdownAllowsAdmittedWriterToRenewIdleTimeoutAndComplete()
			throws Exception {
		CountDownLatch releaseSecondChunk = new CountDownLatch(1);
		LifecyclePolicy drainingPolicy = LifecyclePolicy.builder()
				.gracefulShutdownTimeout(Duration.ofSeconds(3))
				.forcedShutdownTimeout(Duration.ofSeconds(1)).build();
		Fixture fixture = new Fixture(1, 1, Duration.ofSeconds(5), Duration.ZERO,
				null, Duration.ofSeconds(2), drainingPolicy);
		fixture.body("draining", StreamingResponseBody.fromWriter(responseStream -> {
			responseStream.write("first".getBytes(StandardCharsets.UTF_8));
			responseStream.flush();
			releaseSecondChunk.await();
			responseStream.write("second".getBytes(StandardCharsets.UTF_8));
			responseStream.flush();
		}));
		try {
			fixture.start();
			try (Socket socket = fixture.request("/stream/draining")) {
				assertStatus(socket, 200);
				Assertions.assertEquals("5\r\nfirst\r\n", new String(
						socket.getInputStream().readNBytes(10), StandardCharsets.ISO_8859_1));
				CompletionStage<ShutdownResult> shutdown = fixture.soklet.shutdown();
				// This scheduler is stopped at the end of HTTP quiesce. Its state is a
				// barrier proving the second write occurs after that phase's timer policy.
				assertEventually(() -> fixture.server.getRequestHandlerTimeoutScheduler()
						.orElseThrow().isShutdown(), "HTTP quiesce did not finish initiating drain");
				Assertions.assertFalse(shutdown.toCompletableFuture().isDone(),
						"The admitted writer must still be draining");
				releaseSecondChunk.countDown();
				Assertions.assertEquals("6\r\nsecond\r\n0\r\n\r\n", readRemainder(socket),
						"Renewing the idle timer during drain must not truncate the response");
				ShutdownResult result = shutdown.toCompletableFuture()
						.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
				Assertions.assertTrue(result.isComplete());
				Assertions.assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				Assertions.assertEquals(ShutdownComponentDisposition.GRACEFUL_TERMINATION,
						result.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow()
								.getShutdownComponentDisposition());
				Assertions.assertEquals(StreamTerminationReason.COMPLETED,
						fixture.terminations.get("/stream/draining").getReason());
			}
		} finally {
			releaseSecondChunk.countDown();
			fixture.shutdown();
		}
	}

	@Test
	public void saturatedCustomProducerExecutorRejectsBeforeStreamingHeaders()
			throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger rejectedWriterCalls = new AtomicInteger();
		Fixture fixture = new Fixture(2, 1, Duration.ofSeconds(1), Duration.ZERO,
				() -> new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
						new SynchronousQueue<>(), runnable -> {
					Thread worker = new Thread(runnable, "streaming-lifecycle-test-producer");
					worker.setDaemon(true);
					return worker;
				}, new ThreadPoolExecutor.AbortPolicy()));
		fixture.body("held", StreamingResponseBody.fromWriter(responseStream -> {
			entered.countDown();
			awaitUninterruptibly(release);
		}));
		fixture.body("rejected", StreamingResponseBody.fromWriter(responseStream ->
				rejectedWriterCalls.incrementAndGet()));
		try {
			fixture.start();
			try (Socket held = fixture.request("/stream/held")) {
				assertStatus(held, 200);
				await(entered, "The custom executor's one worker did not enter production");
				try (Socket rejected = fixture.request("/stream/rejected")) {
					String headers = assertStatus(rejected, 503);
					Assertions.assertFalse(headers.toLowerCase().contains("transfer-encoding:"), headers);
					readRemainder(rejected);
				}
				Assertions.assertEquals(0, rejectedWriterCalls.get());
				assertEventually(() -> fixture.coordinator().snapshot().reservations() == 1,
						"Executor rejection leaked the rejected response's reservation");
				release.countDown();
				readRemainder(held);
			}
		} finally {
			release.countDown();
			fixture.shutdown();
		}
	}

	@Test
	public void inlineCustomExecutorRejectsWithoutEnteringApplicationOrStallingHttp()
			throws Exception {
		AtomicInteger producerCalls = new AtomicInteger();
		CountDownLatch releaseUnexpectedProducer = new CountDownLatch(1);
		Fixture fixture = new Fixture(1, 1, Duration.ofSeconds(1), Duration.ZERO,
				InlineExecutor::new);
		fixture.body("inline", StreamingResponseBody.fromWriter(responseStream -> {
			producerCalls.incrementAndGet();
			awaitUninterruptibly(releaseUnexpectedProducer);
		}));
		try {
			fixture.start();
			try (Socket rejected = fixture.request("/stream/inline")) {
				assertStatus(rejected, 503);
				readRemainder(rejected);
			}
			Assertions.assertEquals(0, producerCalls.get(),
					"Inline executor entry must be rejected before any application code runs");
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"Inline execution rejection leaked lifecycle capacity");
			try (Socket health = fixture.request("/health")) {
				assertStatus(health, 200);
				Assertions.assertEquals("ok", readRemainder(health));
			}
		} finally {
			releaseUnexpectedProducer.countDown();
			fixture.shutdown();
		}
	}

	@Test
	public void suppressedHeadAndUnsupportedProtocolReleaseAdmissionWithoutAcquisition()
			throws Exception {
		AtomicInteger sourceCalls = new AtomicInteger();
		Fixture fixture = new Fixture(1, 1, Duration.ofMillis(100), Duration.ZERO);
		fixture.body("suppressed", StreamingResponseBody.fromInputStream(() -> {
			sourceCalls.incrementAndGet();
			return new ByteArrayInputStream(new byte[0]);
		}));
		try {
			fixture.start();
			try (Socket head = request(fixture.port, "/stream/suppressed", "HEAD", "HTTP/1.1")) {
				assertStatus(head, 200);
				Assertions.assertEquals("", readRemainder(head));
			}
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"HEAD suppression leaked a reservation or an accepted producer envelope");
			Assertions.assertEquals(0, sourceCalls.get());
			try (Socket unsupported = request(fixture.port, "/stream/suppressed", "GET", "HTTP/1.0")) {
				assertStatus(unsupported, "HTTP/1.0", 505);
				readRemainder(unsupported);
			}
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"Protocol rejection leaked a reservation or an accepted producer envelope");
			Assertions.assertEquals(0, sourceCalls.get());
		} finally {
			fixture.shutdown();
		}
	}

	@Test
	public void exhaustedLifecycleCapacityRejectsBeforeHeadersWithoutInvokingBodies()
			throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger writerCalls = new AtomicInteger();
		AtomicInteger inputStreamCalls = new AtomicInteger();
		AtomicInteger readerCalls = new AtomicInteger();
		AtomicInteger publisherCalls = new AtomicInteger();
		Fixture fixture = new Fixture(1, 1, Duration.ofSeconds(1), Duration.ZERO);
		fixture.body("held", StreamingResponseBody.fromWriter(responseStream -> {
			entered.countDown();
			awaitUninterruptibly(release);
		}));
		fixture.body("writer", StreamingResponseBody.fromWriter(responseStream -> {
			writerCalls.incrementAndGet();
			responseStream.write("accepted".getBytes(StandardCharsets.UTF_8));
		}));
		fixture.body("input", StreamingResponseBody.fromInputStream(() -> {
			inputStreamCalls.incrementAndGet();
			return new ByteArrayInputStream(new byte[0]);
		}));
		fixture.body("reader", StreamingResponseBody.fromReader(() -> {
			readerCalls.incrementAndGet();
			return new StringReader("");
		}, StandardCharsets.UTF_8));
		fixture.body("publisher", StreamingResponseBody.fromPublisher(subscriber ->
				publisherCalls.incrementAndGet()));

		try {
			fixture.start();
			try (Socket held = fixture.request("/stream/held")) {
				assertStatus(held, 200);
				await(entered, "The admitted writer did not start");
				Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
				for (String name : new String[]{"writer", "input", "reader", "publisher"}) {
					try (Socket rejected = fixture.request("/stream/" + name)) {
						String headers = assertStatus(rejected, 503);
						Assertions.assertFalse(headers.toLowerCase().contains("transfer-encoding:"),
								"A rejected stream must not commit streaming headers: " + headers);
						readRemainder(rejected);
					}
				}
				Assertions.assertEquals(0, writerCalls.get(), "Rejected writer was invoked");
				Assertions.assertEquals(0, inputStreamCalls.get(), "Rejected source was acquired");
				Assertions.assertEquals(0, readerCalls.get(), "Rejected reader was acquired");
				Assertions.assertEquals(0, publisherCalls.get(), "Rejected publisher was subscribed");
				release.countDown();
				readRemainder(held);
			}
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"Normal completion did not release lifecycle capacity");
			try (Socket accepted = fixture.request("/stream/writer")) {
				assertStatus(accepted, 200);
				Assertions.assertTrue(readRemainder(accepted).contains("accepted"));
			}
			Assertions.assertEquals(1, writerCalls.get());
		} finally {
			release.countDown();
			fixture.shutdown();
		}
	}

	@Test
	public void blockedSourceFinalizerDoesNotHoldTransportOrShutdownPastDeadline()
			throws Exception {
		BlockingCloseSource source = new BlockingCloseSource();
		Fixture fixture = new Fixture(1, 1, Duration.ofMillis(100), Duration.ZERO);
		fixture.body("blocked-close", StreamingResponseBody.fromInputStream(() -> source));
		StreamLifecycleCoordinator coordinator = null;
		try {
			fixture.start();
			coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("/stream/blocked-close")) {
				assertStatus(socket, 200);
				await(source.closeEntered, "The source finalizer did not start");
				readRemainder(socket);
				assertEventually(() -> fixture.terminations.containsKey("/stream/blocked-close"),
						"Transport termination was hidden behind a blocked finalizer");
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT,
						fixture.terminations.get("/stream/blocked-close").getReason(),
						"Cleanup expiry must remain distinct from a total response timeout");
				Assertions.assertEquals(1L, source.closeReturned.getCount(),
						"The fixture must still be physically blocked");
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
				Assertions.assertEquals(1, coordinator.snapshot().overdue());
				Assertions.assertSame(source.readThread.get(), source.closeThread.get(),
						"Normal source finalization must remain on its producer thread");
				Assertions.assertEquals(1, source.closeCalls.get(),
						"Cancelation raced normal finalization into a second close");
				assertEventually(() -> fixture.diagnostics.stream().anyMatch(failure ->
						failure instanceof StreamLifecycleCoordinator.CleanupDeadlineExceededException),
						"Overdue physical cleanup was not diagnosed");
			}

			ShutdownResult result = fixture.shutdown();
			Assertions.assertFalse(result.isComplete(), "Blocked cleanup must remain visible");
			ShutdownComponentResult http = result
					.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow();
			Assertions.assertEquals(ShutdownComponentDisposition.RESIDUAL_ACTIVITY,
					http.getShutdownComponentDisposition());
			Assertions.assertTrue(http.getResidualActivityEvidence().orElseThrow()
					.getResidualActivityTypes().contains(ResidualActivityType.STREAM));
			Assertions.assertFalse(coordinator.isTerminated());

			source.releaseClose.countDown();
			await(source.closeReturned, "The released source finalizer did not return");
			StreamLifecycleCoordinator retainedCoordinator = coordinator;
			assertEventually(() -> retainedCoordinator.snapshot().reservations() == 0,
					"Physical cleanup did not clear the retained reservation");
			assertEventually(retainedCoordinator::isTerminated,
					"Released lifecycle workers did not terminate");
			Assertions.assertEquals(1, source.closeCalls.get());
			Assertions.assertSame(result, fixture.soklet.getShutdownResult().orElseThrow());
			Assertions.assertFalse(result.isComplete(),
					"A sealed shutdown result must not be rewritten after late cleanup");
		} finally {
			source.releaseClose.countDown();
			fixture.shutdown();
			if (coordinator != null) {
				StreamLifecycleCoordinator retainedCoordinator = coordinator;
				assertEventually(retainedCoordinator::isTerminated,
						"The test left lifecycle workers behind");
			}
		}
	}

	@Test
	public void blockedCancelationCallbackCannotStallAnotherStreamsTimeout()
			throws Exception {
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		CountDownLatch firstProducerExited = new CountDownLatch(1);
		CountDownLatch secondProducerExited = new CountDownLatch(1);
		AtomicReference<Thread> firstProducerThread = new AtomicReference<>();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		Fixture fixture = new Fixture(2, 1, Duration.ofMillis(100),
				Duration.ofMillis(250));
		fixture.body("blocked-callback", StreamingResponseBody.fromWriter(responseStream -> {
			firstProducerThread.set(Thread.currentThread());
			responseStream.getCancelationToken().onCancel(() -> {
				callbackThread.set(Thread.currentThread());
				callbackEntered.countDown();
				awaitUninterruptibly(releaseCallback);
			});
			try {
				new CountDownLatch(1).await();
			} finally {
				firstProducerExited.countDown();
			}
		}));
		fixture.body("second-timeout", StreamingResponseBody.fromWriter(responseStream -> {
			try {
				new CountDownLatch(1).await();
			} finally {
				secondProducerExited.countDown();
			}
		}));

		try {
			fixture.start();
			try (Socket first = fixture.request("/stream/blocked-callback")) {
				assertStatus(first, 200);
				await(callbackEntered, "The cancelation callback did not start");
				readRemainder(first);
				await(firstProducerExited, "Blocked callback prevented producer interruption");
				Assertions.assertNotSame(firstProducerThread.get(), callbackThread.get());

				try (Socket second = fixture.request("/stream/second-timeout")) {
					assertStatus(second, 200);
					readRemainder(second);
					await(secondProducerExited, "Another stream's timeout stopped making progress");
				}
				Assertions.assertEquals(1L, releaseCallback.getCount());
				Assertions.assertEquals(2, fixture.coordinator().snapshot().reservations(),
						"Queued or running terminal callbacks must retain their lifetime slots");
				try (Socket rejected = fixture.request("/stream/second-timeout")) {
					assertStatus(rejected, 503);
					readRemainder(rejected);
				}
				try (Socket health = fixture.request("/health")) {
					assertStatus(health, 200);
					Assertions.assertEquals("ok", readRemainder(health));
				}
			}

			releaseCallback.countDown();
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"Released callbacks did not drain the bounded terminal queue");
			Assertions.assertEquals(StreamTerminationReason.RESPONSE_TIMEOUT,
					fixture.terminations.get("/stream/second-timeout").getReason());
		} finally {
			releaseCallback.countDown();
			fixture.shutdown();
		}
	}

	@Test
	public void retainedCleanupPreventsRestartAndClearsOnlyAfterPhysicalExit()
			throws Exception {
		int port = findFreePort();
		BlockingCloseSource source = new BlockingCloseSource();
		DefaultHttpServer server = server(port, Duration.ZERO);
		server.setStreamLifecycleCoordinatorFactoryForTests(() ->
				new StreamLifecycleCoordinator(1, 1, Duration.ofMillis(100), failure -> {}));
		ResourceMethod resourceMethod = ResourceMethod.fromComponents(HttpMethod.GET,
				ResourcePathDeclaration.fromPath("/stream"),
				StreamingResource.class.getDeclaredMethod("stream", Request.class), false);
		server.initialize(SokletConfig.forSimulatorTesting()
				.resourceMethodResolver(ResourceMethodResolver.fromMethods(Set.of()))
				.lifecyclePolicy(SHUTDOWN_POLICY)
				.lifecycleObserver(new QuietObserver()).build(), (request, response) ->
				response.accept(HttpRequestResult.withMarshaledResponse(
						MarshaledResponse.withStatusCode(200)
								.streamingResponseBody(StreamingResponseBody.fromInputStream(() -> source))
								.build()).resourceMethod(resourceMethod).build()));
		StreamLifecycleCoordinator coordinator = null;
		try {
			server.start();
			coordinator = server.getStreamLifecycleCoordinatorForTests().orElseThrow();
			try (Socket socket = request(port, "/stream")) {
				assertStatus(socket, 200);
				await(source.closeEntered, "The source finalizer did not start");
				readRemainder(socket);
			}
			Assertions.assertTimeout(WAIT, server::stop,
					"Shutdown must not join an uncooperative source finalizer indefinitely");
			InternalShutdownResult sealedResult = server.getLifecycleAdapter().result().orElseThrow();
			Assertions.assertFalse(sealedResult.isComplete());
			IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
					server::start);
			Assertions.assertTrue(failure.getMessage().contains("retained termination evidence"),
					failure.getMessage());
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			source.releaseClose.countDown();
			await(source.closeReturned, "The released source finalizer did not return");
			StreamLifecycleCoordinator retainedCoordinator = coordinator;
			assertEventually(retainedCoordinator::isTerminated,
					"Physical exit did not clear retained lifecycle workers");
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
			Assertions.assertSame(sealedResult, server.getLifecycleAdapter().result().orElseThrow());
		} finally {
			source.releaseClose.countDown();
			server.stop();
			if (coordinator != null) {
				StreamLifecycleCoordinator retainedCoordinator = coordinator;
				assertEventually(retainedCoordinator::isTerminated,
						"The test left lifecycle workers behind");
			}
		}
	}

	private static final class Fixture {
		private final int port;
		private final DefaultHttpServer server;
		private final Soklet soklet;
		private final Map<String, StreamingResponseBody> bodies = new ConcurrentHashMap<>();
		private final Map<String, StreamTermination> terminations = new ConcurrentHashMap<>();
		private final ConcurrentLinkedQueue<Throwable> diagnostics = new ConcurrentLinkedQueue<>();

		private Fixture(int capacity, int callbackConcurrency, Duration cleanupGrace,
				Duration responseTimeout) throws IOException {
			this(capacity, callbackConcurrency, cleanupGrace, responseTimeout, null);
		}

		private Fixture(int capacity, int callbackConcurrency, Duration cleanupGrace,
				Duration responseTimeout, Supplier<ExecutorService> executorSupplier) throws IOException {
			this(capacity, callbackConcurrency, cleanupGrace, responseTimeout, executorSupplier,
					Duration.ZERO, SHUTDOWN_POLICY);
		}

		private Fixture(int capacity, int callbackConcurrency, Duration cleanupGrace,
				Duration responseTimeout, Supplier<ExecutorService> executorSupplier,
				Duration idleTimeout, LifecyclePolicy lifecyclePolicy) throws IOException {
			this.port = findFreePort();
			this.server = (DefaultHttpServer) HttpServer.withPort(this.port).host("127.0.0.1")
					.streamingResponseTimeout(responseTimeout).streamingResponseIdleTimeout(idleTimeout)
					.streamingExecutorServiceSupplier(executorSupplier).build();
			this.server.setStreamLifecycleCoordinatorFactoryForTests(() ->
					new StreamLifecycleCoordinator(capacity, callbackConcurrency, cleanupGrace,
							this.diagnostics::add));
			StreamingResource resource = new StreamingResource(this.bodies);
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override
						public <T> T provide(@NonNull Class<@NonNull T> instanceClass) {
							return instanceClass == StreamingResource.class
									? instanceClass.cast(resource)
									: InstanceProvider.defaultInstance().provide(instanceClass);
						}
					})
					.lifecyclePolicy(lifecyclePolicy)
					.lifecycleObserver(new QuietObserver() {
						@Override
						public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
								@NonNull StreamTermination termination) {
							terminations.put(handle.getRequest().getPath(), termination);
						}
					}).build());
		}

		private void body(String name, StreamingResponseBody body) {
			this.bodies.put("/stream/" + name, body);
		}

		private void start() {
			this.soklet.start();
		}

		private Socket request(String path) throws Exception {
			return StreamingLifecycleTests.request(this.port, path);
		}

		private StreamLifecycleCoordinator coordinator() {
			return this.server.getStreamLifecycleCoordinatorForTests().orElseThrow();
		}

		private ShutdownResult shutdown() throws Exception {
			return this.soklet.shutdown().toCompletableFuture().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	public static final class StreamingResource {
		private final Map<String, StreamingResponseBody> bodies;

		private StreamingResource(Map<String, StreamingResponseBody> bodies) {
			this.bodies = bodies;
		}

		@GET("/stream/{name}")
		public MarshaledResponse stream(Request request) {
			return MarshaledResponse.withStatusCode(200)
					.streamingResponseBody(requireNonNull(this.bodies.get(request.getPath()))).build();
		}

		@GET("/health")
		public String health() {
			return "ok";
		}
	}

	private static final class BlockingCloseSource extends InputStream {
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		private final CountDownLatch releaseClose = new CountDownLatch(1);
		private final CountDownLatch closeReturned = new CountDownLatch(1);
		private final AtomicInteger closeCalls = new AtomicInteger();
		private final AtomicReference<Thread> readThread = new AtomicReference<>();
		private final AtomicReference<Thread> closeThread = new AtomicReference<>();

		@Override
		public int read() {
			this.readThread.compareAndSet(null, Thread.currentThread());
			return -1;
		}

		@Override
		public void close() {
			this.closeThread.compareAndSet(null, Thread.currentThread());
			this.closeCalls.incrementAndGet();
			this.closeEntered.countDown();
			awaitUninterruptibly(this.releaseClose);
			this.closeReturned.countDown();
		}
	}

	private static class QuietObserver implements LifecycleObserver {
		@Override
		public void didReceiveLogEvent(@NonNull LogEvent event) {
			// Expected timeout and cleanup diagnostics are checked through lifecycle evidence.
		}
	}

	/** A valid executor shape whose caller-runs behavior is unsuitable for HTTP producers. */
	private static final class InlineExecutor extends AbstractExecutorService {
		private final AtomicBoolean shutdown = new AtomicBoolean();

		@Override
		public void execute(Runnable command) {
			if (this.shutdown.get())
				throw new RejectedExecutionException("Executor is shut down");
			command.run();
		}

		@Override
		public void shutdown() {
			this.shutdown.set(true);
		}

		@Override
		public List<Runnable> shutdownNow() {
			shutdown();
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
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return isTerminated();
		}
	}

	private static DefaultHttpServer server(int port, Duration responseTimeout) {
		return (DefaultHttpServer) HttpServer.withPort(port).host("127.0.0.1")
				.streamingResponseTimeout(responseTimeout).streamingResponseIdleTimeout(Duration.ZERO)
				.build();
	}

	private static Socket request(int port, String path) throws Exception {
		return request(port, path, "GET", "HTTP/1.1");
	}

	private static Socket request(int port, String path, String method, String version) throws Exception {
		Socket socket = connectWithRetry("127.0.0.1", port, 2_000);
		socket.setSoTimeout((int) WAIT.toMillis());
		socket.getOutputStream().write((method + " " + path + " " + version + "\r\nHost: localhost\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
		socket.getOutputStream().flush();
		return socket;
	}

	private static String assertStatus(Socket socket, int status) throws IOException {
		return assertStatus(socket, "HTTP/1.1", status);
	}

	private static String assertStatus(Socket socket, String version, int status) throws IOException {
		InputStream input = socket.getInputStream();
		ByteArrayOutputStream headers = new ByteArrayOutputStream();
		byte[] delimiter = {'\r', '\n', '\r', '\n'};
		int matched = 0;
		while (headers.size() < 16_384) {
			int value = input.read();
			if (value < 0)
				break;
			headers.write(value);
			matched = value == delimiter[matched] ? matched + 1 : value == delimiter[0] ? 1 : 0;
			if (matched == delimiter.length)
				break;
		}
		String result = headers.toString(StandardCharsets.ISO_8859_1);
		Assertions.assertTrue(result.startsWith(version + " " + status + " "), result);
		Assertions.assertTrue(result.endsWith("\r\n\r\n"), "Incomplete response headers: " + result);
		return result;
	}

	private static String readRemainder(Socket socket) throws IOException {
		return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
	}

	private static void await(CountDownLatch latch, String message) throws InterruptedException {
		Assertions.assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), message);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					latch.await();
					return;
				} catch (InterruptedException ignored) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static void assertEventually(BooleanSupplier condition, String message)
			throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(10);
		Assertions.assertTrue(condition.getAsBoolean(), message);
	}
}
