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

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorStreamingOwnershipSupervisionTests {
	@Test
	public void expired_normal_close_keeps_slot_and_producer_thread_until_physical_exit() throws Exception {
		Fixture fixture = new Fixture();
		CountDownLatch diagnosed = new CountDownLatch(1);
		AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		try {
			SokletSimulator.run(config(fixture, Duration.ofSeconds(2)), simulator -> {
				Soklet.DefaultSimulator exactSimulator = (Soklet.DefaultSimulator) simulator;
				exactSimulator.setStreamLifecycleCoordinatorFactoryForTests(() -> new StreamLifecycleCoordinator(
						1, 1, Duration.ofMillis(100), failure -> {
							diagnostic.set(failure);
							diagnosed.countDown();
						}));
				fixture.start(simulator);
				await(fixture.closeEntered);
				await(diagnosed);
				StreamLifecycleCoordinator coordinator = exactSimulator.getStreamLifecycleCoordinatorForTests().orElseThrow();
				Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class, diagnostic.get());
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT,
						fixture.token.get().getCancelationReason().orElseThrow());
				Assertions.assertEquals(1, coordinator.snapshot().reservations());
				Assertions.assertEquals(1, coordinator.snapshot().runningProducers());
				Assertions.assertSame(fixture.producer, fixture.closeThread.get());
				Assertions.assertEquals(1, fixture.closes.get());
				Assertions.assertEquals(503, simulator.performHttpRequest(request()).getMarshaledResponse().getStatusCode());
				Assertions.assertEquals(1, fixture.entries.get(), "Admission failure must not invoke another writer");
				fixture.release.countDown();
				fixture.join();
				Assertions.assertInstanceOf(IllegalStateException.class, fixture.failure.get());
				Assertions.assertEquals(1, fixture.closes.get());
				Assertions.assertEquals(200, simulator.performHttpRequest(request()).getMarshaledResponse().getStatusCode());
				Assertions.assertEquals(2, fixture.entries.get(), "Physical retirement must make the slot available again");
			});
		} finally {
			fixture.release.countDown();
			fixture.join();
		}
	}

	@Test
	public void scoped_shutdown_reports_unfinished_http_cleanup_and_later_retires_it() throws Exception {
		Fixture fixture = new Fixture();
		AtomicReference<Soklet.DefaultSimulator> retained = new AtomicReference<>();
		try {
			SokletShutdownIncompleteException exception = Assertions.assertThrows(SokletShutdownIncompleteException.class, () ->
					SokletSimulator.run(config(fixture, Duration.ofMillis(100)), simulator -> {
						Soklet.DefaultSimulator exactSimulator = (Soklet.DefaultSimulator) simulator;
						retained.set(exactSimulator);
						exactSimulator.setStreamLifecycleCoordinatorFactoryForTests(() -> new StreamLifecycleCoordinator(
								1, 1, Duration.ofSeconds(5), ignored -> {}));
						fixture.start(simulator);
						await(fixture.closeEntered);
					}));
			InternalLifecycleComponentShutdownResult http = exception.getInternalShutdownResult()
					.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
			Assertions.assertTrue(http.residualActivity().contains(InternalResidualActivityType.STREAM));
			Assertions.assertTrue(http.residualActivity().contains(InternalResidualActivityType.EXECUTOR_TASK));
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING,
					fixture.token.get().getCancelationReason().orElseThrow());
			Assertions.assertSame(fixture.producer, fixture.closeThread.get());
			Assertions.assertEquals(1, fixture.closes.get());
			StreamLifecycleCoordinator coordinator = retained.get().getStreamLifecycleCoordinatorForTests().orElseThrow();
			Assertions.assertEquals(1, coordinator.snapshot().reservations());
			fixture.release.countDown();
			fixture.join();
			Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
			Assertions.assertEquals(0, coordinator.snapshot().reservations());
			Assertions.assertEquals(1, fixture.closes.get());
		} finally {
			fixture.release.countDown();
			fixture.join();
			if (retained.get() != null) {
				StreamLifecycleCoordinator coordinator = retained.get().getStreamLifecycleCoordinatorForTests().orElse(null);
				if (coordinator != null) {
					coordinator.force();
					Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
				}
			}
		}
	}

	private static Request request() {
		return Request.withPath(HttpMethod.GET, "/owned").build();
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled step did not complete");
	}

	private static SimulatorConfig config(Fixture fixture, Duration shutdownBudget) {
		return SimulatorConfig.builder().httpServer()
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(shutdownBudget)
						.forcedShutdownTimeout(shutdownBudget).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Fixture.class ? type.cast(fixture) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) {}
				}).build();
	}

	public static final class Fixture {
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger entries = new AtomicInteger();
		private final AtomicInteger closes = new AtomicInteger();
		private final AtomicReference<Thread> closeThread = new AtomicReference<>();
		private final AtomicReference<CancelationToken> token = new AtomicReference<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private Thread producer;

		@GET("/owned")
		public MarshaledResponse response() {
			return MarshaledResponse.withStatusCode(200).stream(responseStream -> {
				if (this.entries.incrementAndGet() != 1)
					return;
				this.token.set(responseStream.getCancelationToken());
				responseStream.own(() -> {
					this.closeThread.set(Thread.currentThread());
					this.closes.incrementAndGet();
					this.closeEntered.countDown();
					boolean interrupted = false;
					for (;;) {
						try { this.release.await(); break; }
						catch (InterruptedException ignored) { interrupted = true; }
					}
					if (interrupted) Thread.currentThread().interrupt();
				});
			}).build();
		}

		private void start(Simulator simulator) {
			this.producer = new Thread(() -> {
				try { simulator.performHttpRequest(request()); }
				catch (Throwable throwable) { this.failure.set(throwable); }
			}, "simulated-owned-producer");
			this.producer.start();
		}

		private void join() throws InterruptedException {
			if (this.producer != null) {
				this.producer.join(5000);
				Assertions.assertFalse(this.producer.isAlive(), "Physical cleanup did not exit");
			}
		}
	}
}
