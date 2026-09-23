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
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

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

@EnabledForJreRange(min = JRE.JAVA_21)
@Timeout(value = 60, unit = SECONDS)
public class SseInitializerRuntimeTests {
	@Test
	public void synchronousInitializerQueuesCatchupBeforeBroadcast() throws Exception {
		AtomicReference<SseUnicaster> initializerHandle = new AtomicReference<>();
		try (Fixture fixture = new Fixture(2, null)) {
			fixture.initializer("catchup", sseUnicaster -> {
				initializerHandle.set(sseUnicaster);
				sseUnicaster.unicastEvent(SseEvent.withData("catch-up").build());
			});
			fixture.start();
			try (Socket socket = fixture.request("catchup")) {
				assertStatus(socket, 200);
				fixture.awaitClients(1);
				fixture.server.acquireBroadcaster(ResourcePath.fromPath("/ownership/catchup")).orElseThrow()
						.broadcastEvent(SseEvent.withData("live").build());
				Assertions.assertEquals("data: catch-up\n\n", readUntil(socket, "\n\n"));
				Assertions.assertEquals("data: live\n\n", readUntil(socket, "\n\n"));
				Assertions.assertThrows(IllegalStateException.class,
						() -> initializerHandle.get().unicastEvent(SseEvent.withData("late").build()));
			}
		}
	}

	@Test
	public void checkedInitializerFailureTerminatesWithoutActivation() throws Exception {
		IOException failure = new IOException("setup failed");
		try (Fixture fixture = new Fixture(1, null)) {
			fixture.initializer("failure", ignored -> { throw failure; });
			fixture.start();
			try (Socket socket = fixture.request("failure")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		}
	}

	@Test
	public void caughtInitializerOverflowStillTerminatesWithoutActivation() throws Exception {
		try (Fixture fixture = new Fixture(1, null)) {
			fixture.initializer("overflow", sseUnicaster -> {
				sseUnicaster.unicastEvent(SseEvent.withData("first").build());
				Assertions.assertThrows(IllegalStateException.class,
						() -> sseUnicaster.unicastEvent(SseEvent.withData("second").build()));
			});
			fixture.start();
			try (Socket socket = fixture.request("overflow")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		}
	}

	@Test
	public void lifecycleCapacityRejectsBeforeAcceptedHeadersAndInitializer() throws Exception {
		AtomicInteger initialized = new AtomicInteger();
		try (Fixture fixture = new Fixture(1, null)) {
			fixture.initializer("capacity", ignored -> initialized.incrementAndGet());
			fixture.start();
			try (Socket accepted = fixture.request("capacity")) {
				assertStatus(accepted, 200);
				fixture.awaitClients(1);
				try (Socket rejected = fixture.request("capacity")) {
					String headers = assertStatus(rejected, 503);
					Assertions.assertFalse(headers.contains("text/event-stream"));
				}
				Assertions.assertEquals(1, initialized.get());
			}
		}
	}

	@Test
	public void shutdownAccountsForBlockedInitializerUntilPhysicalExit() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Fixture fixture = new Fixture(1, null);
		try {
			fixture.initializer("blocked", ignored -> {
				entered.countDown();
				awaitUninterruptibly(release);
			});
			fixture.start();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("blocked")) {
				assertStatus(socket, 200);
				await(entered);
				ShutdownResult report = fixture.soklet.shutdown().toCompletableFuture().get(3, SECONDS);
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
			if (fixture.soklet.getShutdownResult().map(result -> !result.isComplete()).orElse(false))
				Assertions.assertThrows(SokletShutdownIncompleteException.class, fixture.soklet::close);
			else
				fixture.soklet.close();
		}
	}

	@Test
	public void rejectedConnectionExecutorReleasesAdmission() throws Exception {
		try (Fixture fixture = new Fixture(1, new RejectingExecutor())) {
			fixture.initializer("rejected", ignored -> {});
			fixture.start();
			try (Socket socket = fixture.request("rejected")) {
				assertStatus(socket, 200);
				Assertions.assertEquals(-1, socket.getInputStream().read());
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		}
	}

	@Test
	public void queuedConnectionEnvelopeIsRetiredOnlyWhenShutdownRemovesIt() throws Exception {
		QueuedExecutor executor = new QueuedExecutor();
		try (Fixture fixture = new Fixture(1, executor)) {
			fixture.initializer("queued", ignored -> {});
			fixture.start();
			StreamLifecycleCoordinator coordinator = fixture.coordinator();
			try (Socket socket = fixture.request("queued")) {
				assertStatus(socket, 200);
				eventually(() -> executor.pending != null);
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				Assertions.assertTrue(fixture.soklet.shutdown().toCompletableFuture().get(3, SECONDS).isComplete());
				Assertions.assertNull(executor.pending);
				Assertions.assertTrue(coordinator.isTerminated());
				Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
			}
		}
	}

	@Test
	public void passiveClientDisconnectReleasesAdmissionOnHeartbeat() throws Exception {
		try (Fixture fixture = new Fixture(1, null, Duration.ofMillis(50))) {
			fixture.initializer("disconnect", ignored -> {});
			fixture.start();
			try (Socket socket = fixture.request("disconnect")) {
				assertStatus(socket, 200);
				fixture.awaitClients(1);
				socket.setSoLinger(true, 0);
			}
			eventually(() -> fixture.coordinator().snapshot().reservations() == 0);
			Assertions.assertEquals(0, fixture.server.getActiveConnectionCount());
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final int port = findFreePort();
		private final DefaultSseServer server;
		private final Soklet soklet;
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
			this.soklet = Soklet.fromConfig(SokletConfig.withHttpServer(HttpServer.withPort(findFreePort()).build())
					.sseServer(this.server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(observer)
					.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(100))
							.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build());
		}
		private void initializer(String name, SseClientInitializer initializer) { this.initializers.put("/ownership/" + name, initializer); }
		private void start() { this.soklet.start(); }
		private StreamLifecycleCoordinator coordinator() { return this.server.getStreamLifecycleCoordinatorForTests().orElseThrow(); }
		private void awaitClients(int count) throws Exception { eventually(() -> this.server.getGlobalConnections().size() == count); }
		private Socket request(String name) throws Exception {
			Socket socket = connectWithRetry("127.0.0.1", this.port, 2_000); socket.setSoTimeout(3_000);
			socket.getOutputStream().write(("GET /ownership/" + name + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			return socket;
		}
		@Override public void close() throws Exception { this.soklet.close(); }
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
