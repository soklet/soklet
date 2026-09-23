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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorPublisherLifecycleTests {
	@Test
	@Timeout(value = 90, unit = TimeUnit.SECONDS)
	public void late_blocked_cancel_retains_capacity_after_the_request_producer_exits() throws Exception {
		Fixture fixture = new Fixture(true, null);
		try {
			SokletSimulator.run(config(fixture), simulator -> {
				try {
					fixture.install(simulator, Duration.ofMillis(100));
					fixture.start(simulator);
					await(fixture.subscribed);
					fixture.producer.interrupt();
					fixture.joinProducer();
					Assertions.assertInstanceOf(IllegalStateException.class, fixture.requestFailure.get());
					Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, fixture.termination.get().getReason());
					assertRetainedPublisher(fixture, 1);
					fixture.deliverLateSubscription();
					await(fixture.cancelEntered);
					await(fixture.diagnosed);
					Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
							fixture.diagnostic.get());
					assertRetainedPublisher(fixture, 0);
					Assertions.assertEquals(0, fixture.requests.get());
					Assertions.assertEquals(1, fixture.cancels.get());
					Assertions.assertEquals(503, simulator.performHttpRequest(request()).getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(1, fixture.subscriptions.get(), "Rejected admission must not call subscribe");
					fixture.releaseCancel.countDown();
					fixture.joinPublisher();
					awaitRetirement(fixture.coordinator);
					Assertions.assertNull(fixture.publisherFailure.get());
					Assertions.assertEquals(200, simulator.performHttpRequest(request()).getMarshaledResponse().getStatusCode());
					Assertions.assertEquals(2, fixture.subscriptions.get());
					Assertions.assertEquals(1, fixture.cancels.get());
				} finally {
					fixture.resolve();
				}
			});
		} finally {
			fixture.cleanUp();
		}
	}

	@Test
	public void a_missing_subscription_is_reported_as_publisher_work_at_bounded_shutdown() throws Exception {
		Fixture fixture = new Fixture(false, null);
		try {
			SokletShutdownIncompleteException exception = Assertions.assertThrows(SokletShutdownIncompleteException.class,
					() -> SokletSimulator.run(config(fixture), simulator -> {
						fixture.install(simulator, Duration.ofSeconds(5));
						fixture.start(simulator);
						await(fixture.subscribed);
						fixture.coordinator.force();
						fixture.joinProducer();
						Assertions.assertInstanceOf(IllegalStateException.class, fixture.requestFailure.get());
						assertRetainedPublisher(fixture, 1);
						Assertions.assertFalse(fixture.coordinator.awaitTermination(
								System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30)));
					}));
			InternalLifecycleComponentShutdownResult http = exception.getInternalShutdownResult()
					.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
			Assertions.assertTrue(http.residualActivity().contains(InternalResidualActivityType.STREAM));
			Assertions.assertTrue(http.residualActivity().contains(InternalResidualActivityType.CALLBACK));
			Assertions.assertFalse(http.residualActivity().contains(InternalResidualActivityType.EXECUTOR_TASK),
					"An outstanding publisher subscription must not impersonate an executing producer");
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, fixture.termination.get().getReason());
			assertRetainedPublisher(fixture, 1);
			fixture.resolve();
			Assertions.assertTrue(fixture.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			Assertions.assertEquals(0, fixture.coordinator.snapshot().reservations());
			Assertions.assertEquals(0, fixture.requests.get());
			Assertions.assertEquals(1, fixture.cancels.get());
		} finally {
			fixture.cleanUp();
		}
	}

	@Test
	@Timeout(value = 90, unit = TimeUnit.SECONDS)
	public void a_throwing_late_cancel_is_diagnosed_without_replacing_the_cancelation_reason() throws Exception {
		IllegalStateException cancelFailure = new IllegalStateException("late cancel failed");
		Fixture fixture = new Fixture(false, cancelFailure);
		try {
			SokletSimulator.run(config(fixture), simulator -> {
				try {
					fixture.install(simulator, Duration.ofSeconds(5));
					fixture.start(simulator);
					await(fixture.subscribed);
					fixture.producer.interrupt();
					fixture.joinProducer();
					assertRetainedPublisher(fixture, 1);
					fixture.deliverLateSubscription();
					fixture.joinPublisher();
					await(fixture.diagnosed);
					awaitRetirement(fixture.coordinator);
					Assertions.assertSame(cancelFailure, fixture.diagnostic.get());
					Assertions.assertNull(fixture.publisherFailure.get());
					Assertions.assertEquals(1, fixture.diagnosticCount.get());
					Assertions.assertEquals(StreamTerminationReason.APPLICATION_CANCELED, fixture.termination.get().getReason());
					Assertions.assertInstanceOf(InterruptedException.class, fixture.termination.get().getCause().orElseThrow());
					Assertions.assertEquals(0, fixture.requests.get());
					Assertions.assertEquals(1, fixture.cancels.get());
					Assertions.assertEquals(200, simulator.performHttpRequest(request()).getMarshaledResponse().getStatusCode());
				} finally {
					fixture.resolve();
				}
			});
		} finally {
			fixture.cleanUp();
		}
	}

	private static void assertRetainedPublisher(Fixture fixture, int pendingAcquisitions) {
		StreamLifecycleCoordinator.Snapshot snapshot = fixture.coordinator.snapshot();
		Assertions.assertEquals(1, snapshot.reservations());
		Assertions.assertEquals(1, snapshot.publisherLifetimes());
		Assertions.assertEquals(pendingAcquisitions, snapshot.pendingPublisherAcquisitions());
		Assertions.assertEquals(0, snapshot.runningProducers());
		Assertions.assertEquals(0, snapshot.queuedProducers());
		Set<InternalResidualActivityType> residual = fixture.simulator.httpScopeResidualActivity();
		Assertions.assertTrue(residual.contains(InternalResidualActivityType.STREAM));
		Assertions.assertTrue(residual.contains(InternalResidualActivityType.CALLBACK));
		Assertions.assertFalse(residual.contains(InternalResidualActivityType.EXECUTOR_TASK));
	}

	private static void awaitRetirement(StreamLifecycleCoordinator coordinator) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (coordinator.snapshot().reservations() != 0 && System.nanoTime() < deadline)
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
		Assertions.assertEquals(0, coordinator.snapshot().reservations(), "Physical publisher and diagnostic work must retire");
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "Controlled publisher step did not complete");
	}

	private static Request request() {
		return Request.withPath(HttpMethod.GET, "/publisher-lifecycle").build();
	}

	private static SimulatorConfig config(Fixture fixture) {
		return SimulatorConfig.builder().httpServer()
				.lifecyclePolicy(LifecyclePolicy.builder().gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Fixture.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Fixture.class ? type.cast(fixture) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
						fixture.termination.compareAndSet(null, termination);
					}
					@Override public void didReceiveLogEvent(LogEvent event) {}
				}).build();
	}

	public static final class Fixture {
		private final boolean blockCancel;
		private final RuntimeException cancelFailure;
		private final CountDownLatch subscribed = new CountDownLatch(1);
		private final CountDownLatch cancelEntered = new CountDownLatch(1);
		private final CountDownLatch releaseCancel = new CountDownLatch(1);
		private final CountDownLatch diagnosed = new CountDownLatch(1);
		private final AtomicInteger subscriptions = new AtomicInteger();
		private final AtomicInteger requests = new AtomicInteger();
		private final AtomicInteger cancels = new AtomicInteger();
		private final AtomicInteger diagnosticCount = new AtomicInteger();
		private final AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>();
		private final AtomicReference<Throwable> requestFailure = new AtomicReference<>();
		private final AtomicReference<Throwable> publisherFailure = new AtomicReference<>();
		private final AtomicReference<Throwable> diagnostic = new AtomicReference<>();
		private final AtomicReference<StreamTermination> termination = new AtomicReference<>();
		private Soklet.DefaultSimulator simulator;
		private StreamLifecycleCoordinator coordinator;
		private Thread producer;
		private Thread publisher;

		private Fixture(boolean blockCancel, RuntimeException cancelFailure) {
			this.blockCancel = blockCancel;
			this.cancelFailure = cancelFailure;
		}

		@GET("/publisher-lifecycle")
		public MarshaledResponse response() {
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(StreamingResponseBody.fromPublisher(value -> {
				if (this.subscriptions.incrementAndGet() == 1) {
					this.subscriber.set(value);
					this.subscribed.countDown();
				} else {
					value.onSubscribe(new Flow.Subscription() {
						@Override public void request(long count) { value.onComplete(); }
						@Override public void cancel() {}
					});
				}
			})).build();
		}

		private void install(Simulator simulator, Duration cleanupTimeout) {
			this.simulator = (Soklet.DefaultSimulator) simulator;
			this.coordinator = new StreamLifecycleCoordinator(1, 1, cleanupTimeout, failure -> {
				this.diagnostic.compareAndSet(null, failure);
				this.diagnosticCount.incrementAndGet();
				this.diagnosed.countDown();
			});
			this.simulator.setStreamLifecycleCoordinatorFactoryForTests(() -> this.coordinator);
		}

		private void start(Simulator simulator) {
			this.producer = new Thread(() -> {
				try { simulator.performHttpRequest(request()); }
				catch (Throwable throwable) { this.requestFailure.set(throwable); }
			}, "simulator-publisher-producer");
			this.producer.start();
		}

		private void deliverLateSubscription() {
			if (this.publisher != null || this.subscriber.get() == null)
				return;
			this.publisher = new Thread(() -> {
				try {
					this.subscriber.get().onSubscribe(new Flow.Subscription() {
						@Override public void request(long count) { requests.incrementAndGet(); }
						@Override public void cancel() {
							cancels.incrementAndGet();
							cancelEntered.countDown();
							boolean interrupted = false;
							try {
								while (blockCancel) {
									try { releaseCancel.await(); break; }
									catch (InterruptedException ignored) { interrupted = true; }
								}
								if (cancelFailure != null)
									throw cancelFailure;
							} finally {
								if (interrupted)
									Thread.currentThread().interrupt();
							}
						}
					});
				} catch (Throwable throwable) {
					this.publisherFailure.set(throwable);
				}
			}, "simulator-late-publisher-subscription");
			this.publisher.start();
		}

		private void joinProducer() throws InterruptedException {
			join(this.producer);
		}

		private void joinPublisher() throws InterruptedException {
			join(this.publisher);
		}

		private static void join(Thread thread) throws InterruptedException {
			if (thread != null) {
				thread.join(3000);
				Assertions.assertFalse(thread.isAlive(), "Controlled publisher thread did not exit");
			}
		}

		private void resolve() throws InterruptedException {
			this.releaseCancel.countDown();
			if (this.producer != null && this.producer.isAlive())
				this.producer.interrupt();
			joinProducer();
			deliverLateSubscription();
			joinPublisher();
		}

		private void cleanUp() throws InterruptedException {
			resolve();
			if (this.coordinator != null) {
				this.coordinator.force();
				Assertions.assertTrue(this.coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			}
		}
	}
}
