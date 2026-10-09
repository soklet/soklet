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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static com.soklet.TestSupport.connectWithRetry;
import static com.soklet.TestSupport.findFreePort;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingObserverIsolationRuntimeTests {
	@Test
	@Timeout(value = 110, unit = TimeUnit.SECONDS)
	void blockedHttpCancelBatchLeavesHealthyStreamingAdmissionAvailable() throws Exception { assertHttpIsolation(Hook.CANCEL); }
	@Test
	@Timeout(value = 110, unit = TimeUnit.SECONDS)
	void blockedHttpTerminationObserverLeavesHealthyStreamingAdmissionAvailable() throws Exception { assertHttpIsolation(Hook.TERMINATION); }
	@Test
	@Timeout(value = 110, unit = TimeUnit.SECONDS)
	void blockedHttpCleanupLoggerLeavesOtherCleanupDiagnosticsDeliverable() throws Exception { assertHttpIsolation(Hook.DIAGNOSTIC); }

	private static void assertHttpIsolation(Hook hook) throws Exception {
		Resource resource = new Resource(hook);
		int port = findFreePort();
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(port).host("127.0.0.1").concurrency(1)
				.streamingLifecycleCapacity(4).streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(Duration.ofSeconds(30)).build();
		Soklet soklet = Soklet.fromConfig(config(server, resource));
		StreamLifecycleCoordinator owner = null;
		try {
			soklet.start();
			owner = server.getStreamLifecycleCoordinatorForTests().orElseThrow();
			Assertions.assertTrue(request(port, "/blocked").startsWith("HTTP/1.1 200 OK"));
			await(resource.entered);
			for (int index = 0; index < 8; index++) {
				String wire = request(port, "/healthy");
				Assertions.assertTrue(wire.startsWith("HTTP/1.1 200 OK"), wire);
				if (hook != Hook.DIAGNOSTIC) Assertions.assertTrue(wire.contains("ok"), wire);
				awaitReservations(owner, 1);
				Assertions.assertEquals(index + 1, resource.healthyTerminations.get());
				Assertions.assertEquals(hook == Hook.DIAGNOSTIC ? StreamTerminationReason.PRODUCER_FAILED
						: StreamTerminationReason.COMPLETED, resource.healthyReason.get());
				if (hook == Hook.DIAGNOSTIC) Assertions.assertEquals(index + 1, resource.healthyDiagnostics.get());
				else Assertions.assertTrue(wire.endsWith("0\r\n\r\n"), "Healthy body did not finish");
			}
			Assertions.assertEquals(1, resource.hookCalls.get());
			ShutdownResult result = soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
			Assertions.assertFalse(result.isComplete(), "The original blocked hook remains physical work");
			Assertions.assertFalse(owner.isTerminated());
		} finally {
			resource.release.countDown();
			soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS);
			if (owner != null) Assertions.assertTrue(owner.awaitTermination(deadline(5000)));
		}
		Assertions.assertEquals(1, resource.hookCalls.get());
		Assertions.assertEquals(1, resource.blockedTerminations.get());
	}

	@Test
	@Timeout(value = 75, unit = TimeUnit.SECONDS)
	void simulatorWaitsForItsOwnObserverWithoutBlockingOtherRequests() throws Exception {
		Resource resource = new Resource(Hook.TERMINATION);
		HttpServer source = HttpServer.withPort(0).streamingLifecycleCapacity(4).streamingCallbackConcurrency(1).build();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger completed = new AtomicInteger();
		AtomicReference<Thread> caller = new AtomicReference<>();
		SokletSimulator.run(SimulatorConfig.fromSokletConfig(config(source, resource)), simulator -> {
			try {
				Thread blocked = new Thread(() -> {
					try {
						simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/blocked"));
						completed.incrementAndGet();
					} catch (Throwable throwable) { failure.set(throwable); }
				}, "simulator-blocked-observer-caller");
				caller.set(blocked);
				blocked.start();
				await(resource.entered);
				StreamLifecycleCoordinator owner = ((Soklet.DefaultSimulator) simulator).getStreamLifecycleCoordinatorForTests().orElseThrow();
				for (int index = 0; index < 8; index++) {
					HttpRequestResult result = simulator.performHttpRequest(Request.fromPath(HttpMethod.GET, "/healthy"));
					Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(index + 1, resource.healthyTerminations.get());
					awaitReservations(owner, 1);
				}
				Assertions.assertEquals(0, completed.get(), "Simulator must still wait for its own blocked observer");
				Assertions.assertTrue(blocked.isAlive());
			} finally {
				resource.release.countDown();
				if (caller.get() != null) {
					caller.get().join(3000);
					Assertions.assertFalse(caller.get().isAlive());
				}
			}
		});
		Assertions.assertNull(failure.get());
		Assertions.assertEquals(1, completed.get());
		Assertions.assertEquals(1, resource.hookCalls.get());
	}

	private static SokletConfig config(HttpServer server, Resource resource) {
		return SokletConfig.withHttpServer(server).resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Resource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(resource)
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build()).build();
	}

	private static String request(int port, String path) throws Exception {
		try (Socket socket = connectWithRetry("127.0.0.1", port, 3000)) {
			socket.setSoTimeout(3000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.ISO_8859_1));
			return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Blocked hook did not enter");
	}
	private static void awaitReservations(StreamLifecycleCoordinator owner, int expected) {
		long deadline = deadline(3000);
		while (owner.snapshot().reservations() != expected && System.nanoTime() - deadline < 0L)
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		Assertions.assertEquals(expected, owner.snapshot().reservations(), "Healthy observation did not release its own slot");
	}
	private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
	private static void hold(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; }
			catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}
	private enum Hook { CANCEL, TERMINATION, DIAGNOSTIC }

	public static final class Resource implements LifecycleObserver {
		private final Hook hook;
		private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		private final AtomicInteger hookCalls = new AtomicInteger(), healthyTerminations = new AtomicInteger();
		private final AtomicInteger healthyDiagnostics = new AtomicInteger(), blockedTerminations = new AtomicInteger();
		private final AtomicReference<StreamTerminationReason> healthyReason = new AtomicReference<>();
		private Resource(Hook hook) { this.hook = hook; }
		private void block() { this.hookCalls.incrementAndGet(); this.entered.countDown(); hold(this.release); }
		@GET("/blocked") public MarshaledResponse blocked() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> {
				if (this.hook == Hook.CANCEL) {
					stream.getCancelationToken().onCancel(this::block);
					throw new StreamingResponseCanceledException(StreamTerminationReason.APPLICATION_CANCELED);
				}
				if (this.hook == Hook.DIAGNOSTIC) stream.own(() -> { throw new IOException("blocked cleanup logger"); });
				stream.write("blocked".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
		@GET("/healthy") public MarshaledResponse healthy() {
			return MarshaledResponse.withStatusCode(200).stream(stream -> {
				if (this.hook == Hook.DIAGNOSTIC) stream.own(() -> { throw new IOException("independent cleanup logger"); });
				stream.write("ok".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
		@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
			if (handle.getRequest().getPath().equals("/blocked")) {
				this.blockedTerminations.incrementAndGet();
				if (this.hook == Hook.TERMINATION) block();
			} else {
				this.healthyReason.set(termination.getReason());
				this.healthyTerminations.incrementAndGet();
			}
		}
		@Override public void didReceiveLogEvent(LogEvent event) {
			if (event.getLogEventType() != LogEventType.RESPONSE_STREAM_CLOSE_FAILED) return;
			if (event.getRequest().orElseThrow().getPath().equals("/blocked")) block();
			else this.healthyDiagnostics.incrementAndGet();
		}
	}
}
