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

import com.soklet.annotation.SseEventSource;
import com.soklet.internal.microhttp.StreamLifecycleCoordinator;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;
import static java.util.concurrent.TimeUnit.SECONDS;

@Timeout(value = 60, unit = SECONDS)
public class SseInitializerRuntimeTests {
	@Test
	public void synchronousInitializerQueuesCatchupBeforeBroadcast() throws Exception {
		AtomicReference<SseUnicaster> initializerHandle = new AtomicReference<>();
		Fixture fixture = new Fixture(2, null);
		fixture.initializer("catchup", sseUnicaster -> {
			initializerHandle.set(sseUnicaster);
			sseUnicaster.unicastEvent(SseEvent.withData("catch-up").build());
		});
		Soklet.run(fixture.config, () -> {
			try (Socket socket = fixture.request("catchup")) {
				assertStatus(socket, 200);
				fixture.awaitClients(1);
				// The fixture has a one-entry write queue. Drain catch-up before
				// broadcasting so legitimate queue backpressure cannot close the client.
				Assertions.assertEquals("data: catch-up\n\n", readUntil(socket, "\n\n"));
				fixture.server.acquireBroadcaster(ResourcePath.fromPath("/ownership/catchup")).orElseThrow()
						.broadcastEvent(SseEvent.withData("live").build());
				Assertions.assertEquals("data: live\n\n", readUntil(socket, "\n\n"));
				Assertions.assertThrows(IllegalStateException.class,
						() -> initializerHandle.get().unicastEvent(SseEvent.withData("late").build()));
			}
		});
	}

	@Test
	public void checkedInitializerFailureTerminatesWithoutActivation() throws Exception {
		IOException failure = new IOException("setup failed");
		Fixture fixture = new Fixture(1, null);
		fixture.initializer("failure", ignored -> { throw failure; });
		Soklet.run(fixture.config, () -> {
			try (Socket socket = fixture.request("failure")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		});
	}

	@Test
	public void caughtInitializerOverflowStillTerminatesWithoutActivation() throws Exception {
		Fixture fixture = new Fixture(1, null);
		fixture.initializer("overflow", sseUnicaster -> {
			sseUnicaster.unicastEvent(SseEvent.withData("first").build());
			Assertions.assertThrows(IllegalStateException.class,
					() -> sseUnicaster.unicastEvent(SseEvent.withData("second").build()));
		});
		Soklet.run(fixture.config, () -> {
			try (Socket socket = fixture.request("overflow")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		});
	}

	@Test
	public void lifecycleCapacityRejectsBeforeAcceptedHeadersAndInitializer() throws Exception {
		AtomicInteger initialized = new AtomicInteger();
		Fixture fixture = new Fixture(1, null);
		fixture.initializer("capacity", ignored -> initialized.incrementAndGet());
		Soklet.run(fixture.config, () -> {
			try (Socket accepted = fixture.request("capacity")) {
				assertStatus(accepted, 200);
				fixture.awaitClients(1);
				try (Socket rejected = fixture.request("capacity")) {
					String headers = assertStatus(rejected, 503);
					Assertions.assertFalse(headers.contains("text/event-stream"));
				}
				Assertions.assertEquals(1, initialized.get());
			}
		});
	}

	@Test
	public void shutdownAccountsForBlockedInitializerUntilPhysicalExit() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Fixture fixture = new Fixture(1, null);
		// Keep the manual owner to freeze and inspect an incomplete shutdown result.
		Soklet soklet = Soklet.fromConfig(fixture.config);
		try {
			fixture.initializer("blocked", ignored -> {
				entered.countDown();
				awaitUninterruptibly(release);
			});
			soklet.start();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("blocked")) {
				assertStatus(socket, 200);
				await(entered);
				ShutdownResult report = soklet.shutdown().toCompletableFuture().get(3, SECONDS);
				Assertions.assertFalse(report.isComplete());
				Set<ResidualActivityType> residual = report.getShutdownComponentResult(ShutdownComponentType.SSE)
						.orElseThrow().getResidualActivityEvidence().orElseThrow().getResidualActivityTypes();
				Assertions.assertTrue(residual.contains(ResidualActivityType.STREAM));
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				release.countDown();
				Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + SECONDS.toNanos(3)));
			}
		} finally {
			release.countDown();
			if (soklet.getShutdownResult().map(result -> !result.isComplete()).orElse(false))
				Assertions.assertThrows(SokletShutdownIncompleteException.class, soklet::close);
			else
				soklet.close();
		}
	}

	@Test
	public void finDuringInitializerInterruptsItAndRetainsCapacityUntilExit() throws Exception {
		assertDisconnectDuringInitializer(false, false);
	}

	@Test
	public void resetDuringInitializerInterruptsItAndRetainsCapacityUntilExit() throws Exception {
		assertDisconnectDuringInitializer(true, false);
	}

	@Test
	public void finDuringInitializerDoesNotDependOnQueuedWriterExecutor() throws Exception {
		assertDisconnectDuringInitializer(false, true);
	}

	@Test
	public void resetDuringInitializerDoesNotDependOnQueuedWriterExecutor() throws Exception {
		assertDisconnectDuringInitializer(true, true);
	}

	private static void assertDisconnectDuringInitializer(boolean reset, boolean queuedWriter) throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		Fixture fixture = new Fixture(1, queuedWriter ? new QueuedExecutor() : null,
				Duration.ofHours(1), new LifecycleObserver() {
			@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
			@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
				reason.set(termination.getReason());
			}
		});
		fixture.initializer("disconnect-initializer", ignored -> {
			entered.countDown();
			try {
				release.await();
			} catch (InterruptedException failure) {
				interrupted.countDown();
				// Logical cancelation must not release the capacity while this
				// deliberately delayed physical initializer still owns work.
				awaitUninterruptibly(release);
				throw failure;
			}
		});
		Soklet.run(fixture.config, () -> {
			try {
				try (Socket socket = fixture.request("disconnect-initializer")) {
					assertStatus(socket, 200);
					await(entered);
					if (reset) socket.setSoLinger(true, 0);
				}
				await(interrupted);
				Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
				Assertions.assertEquals(1, fixture.coordinator().snapshot().runningProducers());
				Assertions.assertEquals(1, fixture.server.getActiveConnectionCount());
				try (Socket rejected = fixture.request("disconnect-initializer")) {
					assertStatus(rejected, 503);
				}
			} finally {
				release.countDown();
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, reason.get());
			Assertions.assertTrue(fixture.server.getGlobalConnections().isEmpty());
		});
	}

	@Test
	public void disconnectBeforeInitializerEntrySuppressesApplicationInitialization() throws Exception {
		CountDownLatch observing = new CountDownLatch(1);
		CountDownLatch releaseObserver = new CountDownLatch(1);
		AtomicInteger initialized = new AtomicInteger();
		AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		Fixture fixture = new Fixture(1, null, Duration.ofHours(1), new LifecycleObserver() {
			@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
			@Override public void willEstablishSseConnection(Request request, ResourceMethod resourceMethod) {
				observing.countDown();
				awaitUninterruptibly(releaseObserver);
			}
			@Override public void didTerminateSseConnection(SseConnection connection, StreamTermination termination) {
				reason.set(termination.getReason());
			}
		});
		fixture.initializer("before-initializer", ignored -> initialized.incrementAndGet());
		Soklet.run(fixture.config, () -> {
			try {
				try (Socket socket = fixture.request("before-initializer")) {
					assertStatus(socket, 200);
					await(observing);
					Assertions.assertEquals(2, fixture.coordinator().snapshot().retainedWork());
				}
				// The monitor exits, while the handshake callback remains physically
				// accounted for and cannot enter the canceled initializer afterward.
				eventually(() -> fixture.coordinator().snapshot().retainedWork() == 1);
				Assertions.assertEquals(1, fixture.coordinator().snapshot().reservations());
			} finally {
				releaseObserver.countDown();
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, initialized.get());
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, reason.get());
		});
	}

	@Test
	public void shutdownInterruptsCooperativeInitializerWithEarlyDisconnectMonitor() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch interrupted = new CountDownLatch(1);
		Fixture fixture = new Fixture(1, null);
		fixture.initializer("shutdown-initializer", ignored -> {
			entered.countDown();
			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException failure) {
				interrupted.countDown();
				throw failure;
			}
		});
		// Keep explicit lifecycle control for the shutdown assertions below.
		try (SokletTestScope lifecycle = SokletTestScope.of(Soklet.fromConfig(fixture.config))) {
			Soklet soklet = lifecycle.soklet();
			soklet.start();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("shutdown-initializer")) {
				assertStatus(socket, 200);
				await(entered);
				Assertions.assertTrue(soklet.shutdown().toCompletableFuture().get(3, SECONDS).isComplete());
				await(interrupted);
				Assertions.assertTrue(coordinator.isTerminated());
				Assertions.assertEquals(0, coordinator.snapshot().retainedWork());
			}
		}
	}

	@Test
	public void rejectedConnectionExecutorReleasesAdmission() throws Exception {
		Fixture fixture = new Fixture(1, new RejectingExecutor());
		fixture.initializer("rejected", ignored -> {});
		Soklet.run(fixture.config, () -> {
			try (Socket socket = fixture.request("rejected")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		});
	}

	@Test
	public void queuedConnectionEnvelopeIsRetiredOnlyWhenShutdownRemovesIt() throws Exception {
		QueuedExecutor executor = new QueuedExecutor();
		Fixture fixture = new Fixture(1, executor);
		fixture.initializer("queued", ignored -> {});
		// Keep explicit lifecycle control for the shutdown assertions below.
		try (SokletTestScope lifecycle = SokletTestScope.of(Soklet.fromConfig(fixture.config))) {
			Soklet soklet = lifecycle.soklet();
			soklet.start();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("queued")) {
				assertStatus(socket, 200);
				eventually(() -> executor.pending != null);
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				Assertions.assertTrue(soklet.shutdown().toCompletableFuture().get(3, SECONDS).isComplete());
				Assertions.assertNull(executor.pending);
				Assertions.assertTrue(coordinator.isTerminated());
				Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
			}
		}
	}

	@Test
	public void passiveClientDisconnectReleasesAdmissionOnHeartbeat() throws Exception {
		Fixture fixture = new Fixture(1, null, Duration.ofMillis(50));
		fixture.initializer("disconnect", ignored -> {});
		Soklet.run(fixture.config, () -> {
			try (Socket socket = fixture.request("disconnect")) {
				assertStatus(socket, 200);
				fixture.awaitClients(1);
				socket.setSoLinger(true, 0);
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		});
	}

	private static final class Fixture {
		private final int port = findFreePort();
		private final DefaultSseServer server;
		private final SokletConfig config;
		private final Map<String, SseClientInitializer> initializers = new ConcurrentHashMap<>();
		private Fixture(int capacity, ExecutorService connectionExecutor) throws Exception {
			this(capacity, connectionExecutor, Duration.ofSeconds(30));
		}
		private Fixture(int capacity, ExecutorService connectionExecutor, Duration heartbeatInterval) throws Exception {
			this(capacity, connectionExecutor, heartbeatInterval, new LifecycleObserver() {
				@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
			});
		}
		private Fixture(int capacity, ExecutorService connectionExecutor, Duration heartbeatInterval, LifecycleObserver observer) throws Exception {
			SseServer.Builder builder = SseServer.withPort(this.port).host("127.0.0.1")
					.streamingLifecycleCapacity(capacity).connectionQueueCapacity(1)
					.verifyConnectionOnceEstablished(false).heartbeatInterval(heartbeatInterval);
			this.server = (DefaultSseServer) builder.build();
			if (connectionExecutor != null) {
				java.lang.reflect.Field supplier = DefaultSseServer.class.getDeclaredField("connectionExecutorServiceSupplier");
				supplier.setAccessible(true);
				supplier.set(this.server, (Supplier<ExecutorService>) () -> connectionExecutor);
			}
			Resource resource = new Resource(this.initializers);
			this.config = SokletConfig.withHttpServer(HttpServer.withPort(findFreePort()).build())
					.sseServer(this.server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(observer)
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(100))
							.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build();
		}
		private void initializer(String name, SseClientInitializer initializer) { this.initializers.put("/ownership/" + name, initializer); }
		private StreamLifecycleCoordinator coordinator() { return this.server.getStreamLifecycleCoordinatorForTests().orElseThrow(); }
		private void awaitClients(int count) throws Exception { eventually(() -> this.server.getGlobalConnections().size() == count); }
		private Socket request(String name) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2_000); socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("GET /ownership/" + name + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			return socket;
		}
	}

	public static final class Resource {
		private final Map<String, SseClientInitializer> initializers;
		private Resource(Map<String, SseClientInitializer> initializers) { this.initializers = initializers; }
		@SseEventSource("/ownership/{name}") public SseHandshakeResult source(Request request) {
			return SseHandshakeResult.Accepted.builder().clientInitializer(this.initializers.get(request.getPath())).build();
		}
	}

	private static final class RejectingExecutor extends AbstractExecutorService {
		private volatile boolean stopped;
		@Override public void shutdown() { this.stopped = true; }
		@Override public List<Runnable> shutdownNow() { shutdown(); return List.of(); }
		@Override public boolean isShutdown() { return this.stopped; }
		@Override public boolean isTerminated() { return this.stopped; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return this.stopped; }
		@Override public void execute(Runnable runnable) { throw new RejectedExecutionException("test rejection"); }
	}
	private static final class QueuedExecutor extends AbstractExecutorService {
		private volatile boolean stopped;
		private volatile Runnable pending;
		@Override public void shutdown() { this.stopped = true; }
		@Override public synchronized List<Runnable> shutdownNow() {
			shutdown(); Runnable removed = this.pending; this.pending = null;
			return removed == null ? List.of() : List.of(removed);
		}
		@Override public boolean isShutdown() { return this.stopped; }
		@Override public boolean isTerminated() { return this.stopped && this.pending == null; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
		@Override public void execute(Runnable runnable) { this.pending = runnable; }
	}

	private static void await(CountDownLatch latch) throws InterruptedException { Assertions.assertTrue(latch.await(3, SECONDS)); }
	private static boolean awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
		if (interrupted) Thread.currentThread().interrupt();
		return interrupted;
	}
	private static void eventually(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
		Assertions.assertTrue(condition.getAsBoolean());
	}
	private static String assertStatus(Socket socket, int status) throws IOException {
		String headers = readUntil(socket, "\r\n\r\n");
		Assertions.assertTrue(headers.startsWith("HTTP/1.1 " + status + " "), headers);
		return headers;
	}
	private static String readUntil(Socket socket, String delimiter) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		while (bytes.size() < 16_384) {
			int value = socket.getInputStream().read(); if (value < 0) break; bytes.write(value);
			String result = bytes.toString(StandardCharsets.UTF_8);
			if (result.endsWith(delimiter)) return result;
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
