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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

/** Public settings must govern both configuration validation and real HTTP lifecycle work. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingServerSettingsTests {
	private static final Duration WAIT = Duration.ofSeconds(3);

	@Test
	public void defaultsAndNullResetsResolveIndependently() {
		assertSettings(server(builder()), 256, 4, Duration.ofSeconds(5));
		HttpServer.Builder builder = builder().streamingLifecycleCapacity(8)
				.streamingCallbackConcurrency(2).streamingCleanupTimeout(Duration.ofMillis(25));
		assertSettings(server(builder), 8, 2, Duration.ofMillis(25));
		assertSettings(server(builder.streamingLifecycleCapacity(null)), 256, 2, Duration.ofMillis(25));
		assertSettings(server(builder.streamingLifecycleCapacity(8).streamingCallbackConcurrency(null)),
				8, 4, Duration.ofMillis(25));
		assertSettings(server(builder.streamingCleanupTimeout(null)), 8, 4, Duration.ofSeconds(5));
		assertSettings(server(builder.streamingLifecycleCapacity(null).streamingCallbackConcurrency(null)
				.streamingCleanupTimeout(null)), 256, 4, Duration.ofSeconds(5));
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> builder().streamingLifecycleCapacity(1).streamingCallbackConcurrency(null).build(),
				"Null callback concurrency resets to four; it does not silently shrink to fit capacity");
	}

	@Test
	public void invalidEffectiveValuesAreRejectedAtBuild() {
		for (int capacity : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE / 2 + 1, Integer.MAX_VALUE}) {
			HttpServer.Builder builder = builder().streamingLifecycleCapacity(capacity).streamingCallbackConcurrency(1);
			Assertions.assertThrows(IllegalArgumentException.class, builder::build, "capacity=" + capacity);
		}
		for (int concurrency : new int[]{Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE}) {
			HttpServer.Builder builder = builder().streamingLifecycleCapacity(4).streamingCallbackConcurrency(concurrency);
			Assertions.assertThrows(IllegalArgumentException.class, builder::build, "concurrency=" + concurrency);
		}
		Assertions.assertThrows(IllegalArgumentException.class,
				() -> builder().streamingCallbackConcurrency(257).build());
		for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofNanos(-1),
				Duration.ofNanos(Long.MAX_VALUE).plusNanos(1), Duration.ofSeconds(Long.MAX_VALUE)}) {
			HttpServer.Builder builder = builder().streamingCleanupTimeout(timeout);
			Assertions.assertThrows(IllegalArgumentException.class, builder::build, "timeout=" + timeout);
		}
	}

	@Test
	public void exactBoundsAreAcceptedWithoutStartingInfrastructure() {
		assertSettings(server(builder().streamingLifecycleCapacity(1).streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(Duration.ofNanos(1))), 1, 1, Duration.ofNanos(1));
		int maximumCapacity = Integer.MAX_VALUE / 2;
		assertSettings(server(builder().streamingLifecycleCapacity(maximumCapacity)
				.streamingCallbackConcurrency(maximumCapacity).streamingCleanupTimeout(Duration.ofNanos(Long.MAX_VALUE))),
				maximumCapacity, maximumCapacity, Duration.ofNanos(Long.MAX_VALUE));
	}

	@Test
	public void setterOrderAndIntermediateInvalidValuesDoNotConstrainTheFinalConfiguration() {
		assertSettings(server(builder().streamingLifecycleCapacity(1).streamingCallbackConcurrency(1)),
				1, 1, Duration.ofSeconds(5));
		assertSettings(server(builder().streamingCallbackConcurrency(1).streamingLifecycleCapacity(1)),
				1, 1, Duration.ofSeconds(5));
		HttpServer.Builder builder = builder().streamingLifecycleCapacity(0)
				.streamingCallbackConcurrency(0).streamingCleanupTimeout(Duration.ZERO);
		assertSettings(server(builder.streamingCallbackConcurrency(2).streamingLifecycleCapacity(2)
				.streamingCleanupTimeout(Duration.ofSeconds(1))), 2, 2, Duration.ofSeconds(1));
	}

	@Test
	public void reusableBuildersDoNotMutatePreviouslyBuiltServers() {
		HttpServer.Builder builder = builder().streamingLifecycleCapacity(5)
				.streamingCallbackConcurrency(2).streamingCleanupTimeout(Duration.ofMillis(25));
		DefaultHttpServer first = server(builder);
		DefaultHttpServer second = server(builder.streamingLifecycleCapacity(8)
				.streamingCallbackConcurrency(3).streamingCleanupTimeout(Duration.ofMillis(50)));
		DefaultHttpServer reset = server(builder.streamingLifecycleCapacity(null)
				.streamingCallbackConcurrency(null).streamingCleanupTimeout(null));
		assertSettings(first, 5, 2, Duration.ofMillis(25));
		assertSettings(second, 8, 3, Duration.ofMillis(50));
		assertSettings(reset, 256, 4, Duration.ofSeconds(5));
	}

	@Test
	public void cleanupTimeoutIsIndependentOfResponseAndShutdownBudgets() {
		DefaultHttpServer longerCleanup = server(builder().streamingCleanupTimeout(Duration.ofSeconds(5))
				.streamingResponseTimeout(Duration.ofMillis(1)).streamingResponseIdleTimeout(Duration.ZERO));
		DefaultHttpServer shorterCleanup = server(builder().streamingCleanupTimeout(Duration.ofNanos(1))
				.streamingResponseTimeout(Duration.ofSeconds(30)));
		Assertions.assertEquals(Duration.ofSeconds(5), longerCleanup.getStreamingCleanupTimeout());
		Assertions.assertEquals(Duration.ofNanos(1), shorterCleanup.getStreamingCleanupTimeout());
		Assertions.assertDoesNotThrow(() -> SokletConfig.withHttpServer(longerCleanup)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(10))
						.forcedShutdownTimeout(Duration.ofMillis(10)).build()).build());
	}

	@Test
	public void publicSettingsBoundCallbackWorkersAndRetainExpiredObserversInAdmission() throws Exception {
		CountDownLatch observersEntered = new CountDownLatch(2);
		CountDownLatch releaseObservers = new CountDownLatch(1);
		AtomicInteger activeObservers = new AtomicInteger();
		AtomicInteger maximumObservers = new AtomicInteger();
		AtomicInteger observerCalls = new AtomicInteger();
		AtomicInteger producerCalls = new AtomicInteger();
		Fixture fixture = new Fixture(3, 2, Duration.ofMillis(125), new QuietObserver() {
			@Override public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
					@NonNull StreamTermination termination) {
				observerCalls.incrementAndGet();
				int active = activeObservers.incrementAndGet();
				maximumObservers.accumulateAndGet(active, Math::max);
				observersEntered.countDown();
				try { awaitUninterruptibly(releaseObservers); }
				finally { activeObservers.decrementAndGet(); }
			}
		});
		fixture.body("ready", responseStream -> {
			producerCalls.incrementAndGet();
			responseStream.write("ok".getBytes(StandardCharsets.UTF_8));
		});
		try {
			fixture.start();
			for (int index = 0; index < 3; ++index) {
				try (Socket socket = fixture.request("ready")) {
					assertStatus(socket, 200);
					Assertions.assertEquals("2\r\nok\r\n0\r\n\r\n", readRemainder(socket));
				}
			}
			await(observersEntered, "The configured two callback workers did not start");
			assertEventually(() -> fixture.coordinator().snapshot().queuedCallbacks() == 1,
					"The third observer must queue behind the configured two workers");
			Assertions.assertEquals(2, observerCalls.get());
			Assertions.assertEquals(2, maximumObservers.get());
			Assertions.assertEquals(3, fixture.coordinator().snapshot().reservations());
			assertEventually(() -> fixture.coordinator().snapshot().overdue() == 3,
					"The configured cleanup timeout must supervise running and queued observers");
			try (Socket rejected = fixture.request("ready")) {
				String headers = assertStatus(rejected, 503);
				Assertions.assertFalse(headers.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding:"), headers);
				readRemainder(rejected);
			}
			Assertions.assertEquals(3, producerCalls.get(), "Rejection must happen before the writer is invoked");
			releaseObservers.countDown();
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"Physical observer completion must release admission");
			Assertions.assertEquals(3, observerCalls.get());
			try (Socket recovered = fixture.request("ready")) {
				assertStatus(recovered, 200);
				Assertions.assertEquals("2\r\nok\r\n0\r\n\r\n", readRemainder(recovered));
			}
			Assertions.assertEquals(4, producerCalls.get());
			Assertions.assertEquals(2, maximumObservers.get());
		} finally {
			releaseObservers.countDown();
			fixture.close();
		}
	}

	@Test
	public void publicCleanupTimeoutEndsDeliveryWithoutReleasingABlockedFinalizer() throws Exception {
		CountDownLatch closeEntered = new CountDownLatch(1);
		CountDownLatch releaseClose = new CountDownLatch(1);
		AtomicInteger closeCalls = new AtomicInteger();
		Map<String, StreamTermination> terminations = new ConcurrentHashMap<>();
		Fixture fixture = new Fixture(1, 1, Duration.ofMillis(100), new QuietObserver() {
			@Override public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
					@NonNull StreamTermination termination) {
				terminations.put(handle.getRequest().getPath(), termination);
			}
		});
		fixture.body("blocked", responseStream -> {
			responseStream.own((AutoCloseable) () -> {
				closeCalls.incrementAndGet();
				closeEntered.countDown();
				awaitUninterruptibly(releaseClose);
			});
			responseStream.write("partial".getBytes(StandardCharsets.UTF_8));
			responseStream.flush();
		});
		fixture.body("ready", responseStream -> responseStream.write("ok".getBytes(StandardCharsets.UTF_8)));
		try {
			fixture.start();
			try (Socket socket = fixture.request("blocked")) {
				assertStatus(socket, 200);
				await(closeEntered, "Owned finalization did not start");
				String incompleteBody = readRemainder(socket);
				Assertions.assertFalse(incompleteBody.endsWith("0\r\n\r\n"), incompleteBody);
			}
			assertEventually(() -> terminations.containsKey("/settings/blocked"), "Cleanup expiry did not terminate delivery");
			Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT,
					terminations.get("/settings/blocked").getReason());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().runningProducers());
			Assertions.assertEquals(1, fixture.coordinator().snapshot().overdue());
			Assertions.assertEquals(1, closeCalls.get());
			try (Socket rejected = fixture.request("ready")) {
				assertStatus(rejected, 503);
				readRemainder(rejected);
			}
			releaseClose.countDown();
			assertEventually(() -> fixture.coordinator().snapshot().reservations() == 0,
					"The finalizer's physical return must release its reservation");
			try (Socket recovered = fixture.request("ready")) {
				assertStatus(recovered, 200);
				Assertions.assertEquals("2\r\nok\r\n0\r\n\r\n", readRemainder(recovered));
			}
			Assertions.assertEquals(1, closeCalls.get());
		} finally {
			releaseClose.countDown();
			fixture.close();
		}
	}

	private static HttpServer.Builder builder() { return HttpServer.withPort(8080); }
	private static DefaultHttpServer server(HttpServer.Builder builder) { return (DefaultHttpServer) builder.build(); }

	private static void assertSettings(DefaultHttpServer server, int capacity, int concurrency, Duration timeout) {
		Assertions.assertEquals(capacity, server.getStreamingLifecycleCapacity());
		Assertions.assertEquals(concurrency, server.getStreamingCallbackConcurrency());
		Assertions.assertEquals(timeout, server.getStreamingCleanupTimeout());
	}

	private static final class Fixture implements AutoCloseable {
		private final int port;
		private final DefaultHttpServer server;
		private final Soklet soklet;
		private final Map<String, StreamingResponseWriter> writers = new ConcurrentHashMap<>();

		private Fixture(int capacity, int concurrency, Duration timeout, LifecycleObserver observer) throws IOException {
			this.port = findFreePort();
			this.server = (DefaultHttpServer) HttpServer.withPort(this.port).host("127.0.0.1")
					.streamingLifecycleCapacity(capacity).streamingCallbackConcurrency(concurrency)
					.streamingCleanupTimeout(timeout).streamingResponseTimeout(Duration.ZERO)
					.streamingResponseIdleTimeout(Duration.ZERO).build();
			StreamingResource resource = new StreamingResource(this.writers);
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(this.server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(StreamingResource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == StreamingResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(observer)
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(500))
							.forcedShutdownTimeout(Duration.ofMillis(500)).build()).build());
		}

		private void body(String name, StreamingResponseWriter writer) { this.writers.put("/settings/" + name, writer); }
		private void start() { this.soklet.start(); }
		private StreamLifecycleCoordinator coordinator() { return this.server.getStreamLifecycleCoordinatorForTests().orElseThrow(); }
		private Socket request(String name) throws IOException, InterruptedException {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2_000);
			socket.setSoTimeout((int) WAIT.toMillis());
			socket.getOutputStream().write(("GET /settings/" + name + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			return socket;
		}
		@Override public void close() throws Exception {
			Assertions.assertTrue(this.soklet.shutdown().toCompletableFuture().get(WAIT.toMillis(), TimeUnit.MILLISECONDS).isComplete());
		}
	}

	public static final class StreamingResource {
		private final Map<String, StreamingResponseWriter> writers;
		private StreamingResource(Map<String, StreamingResponseWriter> writers) { this.writers = writers; }
		@GET("/settings/{name}") public MarshaledResponse settings(Request request) {
			return MarshaledResponse.withStatusCode(200).stream(this.writers.get(request.getPath())).build();
		}
	}

	private static class QuietObserver implements LifecycleObserver {
		@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
	}

	private static String assertStatus(Socket socket, int status) throws IOException {
		InputStream input = socket.getInputStream();
		ByteArrayOutputStream headers = new ByteArrayOutputStream();
		byte[] delimiter = {'\r', '\n', '\r', '\n'};
		int matched = 0;
		while (headers.size() < 16_384) {
			int value = input.read();
			if (value < 0) break;
			headers.write(value);
			matched = value == delimiter[matched] ? matched + 1 : value == delimiter[0] ? 1 : 0;
			if (matched == delimiter.length) break;
		}
		String result = headers.toString(StandardCharsets.ISO_8859_1);
		Assertions.assertTrue(result.startsWith("HTTP/1.1 " + status + " "), result);
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
				try { latch.await(); return; }
				catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static void assertEventually(BooleanSupplier condition, String message) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			Thread.sleep(10);
		Assertions.assertTrue(condition.getAsBoolean(), message);
	}
}
