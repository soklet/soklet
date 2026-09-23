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

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorStreamingServerSettingsTests {
	@Test
	public void inherited_capacity_and_cleanup_timeout_keep_the_slot_until_owned_close_exits() throws Exception {
		OwnedResource resource = new OwnedResource();
		DefaultHttpServer sourceHttpServer = (DefaultHttpServer) HttpServer.withPort(0)
				.streamingLifecycleCapacity(1).streamingCallbackConcurrency(1)
				.streamingCleanupTimeout(Duration.ofMillis(100)).build();
		CountDownLatch diagnosed = new CountDownLatch(1);
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		SokletConfig sourceConfig = sourceConfig(sourceHttpServer, resource, new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) {
				event.getThrowable().filter(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class::isInstance)
						.ifPresent(throwable -> {
							diagnostic.set(throwable);
							diagnosed.countDown();
						});
			}
		});
		AtomicReference<StreamLifecycleCoordinator> retainedCoordinator = new AtomicReference<>();
		try {
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(sourceConfig), simulator -> {
				try {
					Assertions.assertFalse(sourceHttpServer.isStarted());
					resource.start(simulator);
					await(resource.closeEntered);
					StreamLifecycleCoordinator coordinator = ((Soklet.DefaultSimulator) simulator)
							.getStreamLifecycleCoordinatorForTests().orElseThrow();
					retainedCoordinator.set(coordinator);
					await(diagnosed);
					Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class, diagnostic.get());
					Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT,
							resource.token.get().getCancelationReason().orElseThrow());
					Assertions.assertEquals(1, coordinator.snapshot().reservations());
					Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
					Assertions.assertEquals(1, resource.closes.get());
					Assertions.assertSame(resource.producer, resource.closeThread.get());
					Assertions.assertEquals(503, simulator.performHttpRequest(request("/inherited-owned"))
							.getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(1, resource.entries.get(), "Full inherited capacity must prevent another producer entry");
					resource.release.countDown();
					resource.join();
					Assertions.assertInstanceOf(IllegalStateException.class, resource.failure.get());
					awaitCondition(() -> coordinator.snapshot().reservations() == 0);
					Assertions.assertEquals(200, simulator.performHttpRequest(request("/inherited-owned"))
							.getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(2, resource.entries.get());
					Assertions.assertEquals(1, resource.closes.get());
					Assertions.assertFalse(sourceHttpServer.isStarted());
					Assertions.assertTrue(sourceHttpServer.getStreamLifecycleCoordinatorForTests().isEmpty());
				} finally {
					resource.release.countDown();
					resource.join();
				}
			});
		} finally {
			resource.release.countDown();
			resource.join();
			forceAndAwait(retainedCoordinator.get());
		}
		Assertions.assertFalse(sourceHttpServer.isStarted());
	}

	@Test
	@Timeout(value = 90, unit = TimeUnit.SECONDS)
	public void inherited_callback_concurrency_runs_two_observers_and_queues_the_third() throws Exception {
		CallbackResource resource = new CallbackResource();
		DefaultHttpServer sourceHttpServer = (DefaultHttpServer) HttpServer.withPort(0)
				.streamingLifecycleCapacity(3).streamingCallbackConcurrency(2)
				.streamingCleanupTimeout(Duration.ofSeconds(5)).build();
		CountDownLatch twoObserversEntered = new CountDownLatch(2);
		CountDownLatch releaseObservers = new CountDownLatch(1);
		AtomicInteger activeObservers = new AtomicInteger();
		AtomicInteger maximumObservers = new AtomicInteger();
		AtomicInteger observerEntries = new AtomicInteger();
		List<Thread> producers = new ArrayList<>();
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		AtomicInteger successfulRequests = new AtomicInteger();
		AtomicReference<StreamLifecycleCoordinator> retainedCoordinator = new AtomicReference<>();
		SokletConfig sourceConfig = sourceConfig(sourceHttpServer, resource, new LifecycleObserver() {
			@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
				observerEntries.incrementAndGet();
				maximumObservers.accumulateAndGet(activeObservers.incrementAndGet(), Math::max);
				twoObserversEntered.countDown();
				try {
					awaitUninterruptibly(releaseObservers);
				} finally {
					activeObservers.decrementAndGet();
				}
			}
			@Override public void didReceiveLogEvent(LogEvent event) {}
		});
		try {
			SokletSimulator.run(SimulatorConfig.fromSokletConfig(sourceConfig), simulator -> {
				try {
					for (int index = 0; index < 3; index++) {
						Thread producer = new Thread(() -> {
							try {
								HttpRequestResult result = simulator.performHttpRequest(request("/inherited-callback"));
								Assertions.assertEquals(200, result.getMarshaledResponse().getStatusCode());
								successfulRequests.incrementAndGet();
							} catch (Throwable throwable) {
								failures.add(throwable);
							}
						}, "inherited-callback-producer-" + index);
						producers.add(producer);
						producer.start();
					}
					await(twoObserversEntered);
					StreamLifecycleCoordinator coordinator = ((Soklet.DefaultSimulator) simulator)
							.getStreamLifecycleCoordinatorForTests().orElseThrow();
					retainedCoordinator.set(coordinator);
					awaitCondition(() -> coordinator.snapshot().callbacks() == 3);
					StreamLifecycleCoordinator.Snapshot snapshot = coordinator.snapshot();
					Assertions.assertEquals(3, snapshot.reservations());
					Assertions.assertEquals(3, snapshot.callbacks());
					Assertions.assertEquals(1, snapshot.queuedCallbacks());
					Assertions.assertEquals(2, activeObservers.get());
					Assertions.assertEquals(2, observerEntries.get());
					Assertions.assertEquals(2, maximumObservers.get());
					Assertions.assertEquals(503, simulator.performHttpRequest(request("/inherited-callback"))
							.getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(3, resource.entries.get());
					releaseObservers.countDown();
					for (Thread producer : producers)
						join(producer);
					Assertions.assertTrue(failures.isEmpty(), failures.toString());
					Assertions.assertEquals(3, successfulRequests.get());
					Assertions.assertEquals(3, observerEntries.get());
					Assertions.assertEquals(2, maximumObservers.get());
					awaitCondition(() -> coordinator.snapshot().reservations() == 0);
					Assertions.assertEquals(200, simulator.performHttpRequest(request("/inherited-callback"))
							.getMarshaledResponse().getStatusCode());
					Assertions.assertFalse(sourceHttpServer.isStarted());
					Assertions.assertTrue(sourceHttpServer.getStreamLifecycleCoordinatorForTests().isEmpty());
				} finally {
					releaseObservers.countDown();
					for (Thread producer : producers)
						join(producer);
				}
			});
		} finally {
			releaseObservers.countDown();
			for (Thread producer : producers)
				join(producer);
			forceAndAwait(retainedCoordinator.get());
		}
	}

	@Test
	public void repeated_derivation_copies_settings_into_fresh_immutable_mock_transports() throws Exception {
		DefaultHttpServer sourceHttpServer = (DefaultHttpServer) HttpServer.withPort(0)
				.streamingLifecycleCapacity(7).streamingCallbackConcurrency(3)
				.streamingCleanupTimeout(Duration.ofMillis(700)).build();
		SokletConfig sourceConfig = sourceConfig(sourceHttpServer, new CallbackResource(), new LifecycleObserver() {});
		SimulatorConfig first = SimulatorConfig.fromSokletConfig(sourceConfig);
		SimulatorConfig second = SimulatorConfig.fromSokletConfig(first.getSokletConfig());
		HttpServer firstHttpServer = first.getSokletConfig().getHttpServer().orElseThrow();
		HttpServer secondHttpServer = second.getSokletConfig().getHttpServer().orElseThrow();
		Assertions.assertNotSame(sourceHttpServer, firstHttpServer);
		Assertions.assertNotSame(firstHttpServer, secondHttpServer);
		Assertions.assertNotSame(firstHttpServer.getTransportIdentity(), secondHttpServer.getTransportIdentity());
		assertSettings(firstHttpServer, 7, 3, Duration.ofMillis(700));
		assertSettings(secondHttpServer, 7, 3, Duration.ofMillis(700));
		Assertions.assertFalse(sourceHttpServer.isStarted());
		Assertions.assertTrue(sourceHttpServer.getStreamLifecycleCoordinatorForTests().isEmpty());
	}

	@Test
	public void standalone_and_custom_transport_derivation_use_the_shared_http_defaults() throws Exception {
		SimulatorConfig standalone = SimulatorConfig.builder().httpServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(CallbackResource.class))).build();
		AtomicInteger customAttachments = new AtomicInteger();
		HttpServer customHttpServer = new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();
			@Override public TransportIdentity getTransportIdentity() { return this.identity; }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext attachmentContext, StartupContext startupContext) {
				customAttachments.incrementAndGet();
				throw new AssertionError("Simulator derivation must not attach its source transport");
			}
		};
		SimulatorConfig fromCustom = SimulatorConfig.fromSokletConfig(
				sourceConfig(customHttpServer, new CallbackResource(), new LifecycleObserver() {}));
		for (SimulatorConfig simulatorConfig : List.of(standalone, fromCustom))
			assertSettings(simulatorConfig.getSokletConfig().getHttpServer().orElseThrow(),
					DefaultHttpServer.DEFAULT_STREAMING_LIFECYCLE_CAPACITY,
					DefaultHttpServer.DEFAULT_STREAMING_CALLBACK_CONCURRENCY,
					DefaultHttpServer.DEFAULT_STREAMING_CLEANUP_TIMEOUT);
		Assertions.assertEquals(0, customAttachments.get());
	}

	private static void assertSettings(HttpServer httpServer, int capacity, int concurrency, Duration cleanupTimeout) throws Exception {
		Assertions.assertInstanceOf(Soklet.MockHttpServer.class, httpServer);
		Assertions.assertEquals(capacity, immutableField(httpServer, "streamingLifecycleCapacity"));
		Assertions.assertEquals(concurrency, immutableField(httpServer, "streamingCallbackConcurrency"));
		Assertions.assertEquals(cleanupTimeout, immutableField(httpServer, "streamingCleanupTimeout"));
	}

	private static Object immutableField(HttpServer httpServer, String name) throws Exception {
		var field = Soklet.MockHttpServer.class.getDeclaredField(name);
		Assertions.assertTrue(Modifier.isFinal(field.getModifiers()));
		field.setAccessible(true);
		return field.get(httpServer);
	}

	private static SokletConfig sourceConfig(HttpServer httpServer, Object resource, LifecycleObserver lifecycleObserver) {
		return SokletConfig.withHttpServer(httpServer)
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofSeconds(2))
						.forcedShutdownTimeout(Duration.ofSeconds(2)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(resource.getClass())))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == resource.getClass() ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(lifecycleObserver).build();
	}

	private static Request request(String path) {
		return Request.withPath(HttpMethod.GET, path).build();
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled simulator step did not complete");
	}

	private static void awaitCondition(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		Assertions.assertTrue(condition.getAsBoolean(), "Coordinator accounting did not reach the expected state");
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try { latch.await(); return; }
				catch (InterruptedException ignored) { interrupted = true; }
			}
		} finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static void join(Thread thread) throws InterruptedException {
		if (thread != null) {
			thread.join(3000);
			Assertions.assertFalse(thread.isAlive(), "Controlled producer did not exit");
		}
	}

	private static void forceAndAwait(StreamLifecycleCoordinator coordinator) throws InterruptedException {
		if (coordinator != null) {
			coordinator.force();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
		}
	}

	public static final class OwnedResource {
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger entries = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();
		private final AtomicReference<Thread> closeThread = new AtomicReference<>();
		private final AtomicReference<CancelationToken> token = new AtomicReference<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private Thread producer;

		@GET("/inherited-owned")
		public MarshaledResponse response() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				if (this.entries.incrementAndGet() != 1)
					return;
				this.token.set(responseStream.getCancelationToken());
				responseStream.own(() -> {
					this.closeThread.set(Thread.currentThread());
					this.closes.incrementAndGet();
					this.closeEntered.countDown();
					awaitUninterruptibly(this.release);
				});
			}).build();
		}

		private void start(Simulator simulator) {
			this.producer = new Thread(() -> {
				try { simulator.performHttpRequest(request("/inherited-owned")); }
				catch (Throwable throwable) { this.failure.set(throwable); }
			}, "inherited-settings-owned-producer");
			this.producer.start();
		}

		private void join() throws InterruptedException { SimulatorStreamingServerSettingsTests.join(this.producer); }
	}

	public static final class CallbackResource {
		private final AtomicInteger entries = new AtomicInteger();
		@GET("/inherited-callback")
		public MarshaledResponse response() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				this.entries.incrementAndGet();
				responseStream.write("ok".getBytes(StandardCharsets.UTF_8));
			}).build();
		}
	}
}
