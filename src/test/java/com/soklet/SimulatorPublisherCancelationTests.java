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

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class SimulatorPublisherCancelationTests {
	@Test
	public void limitCancelationAndProducerFinalizationShareOnePhysicalCancelAttempt() throws Exception {
		AtomicInteger cancels = new AtomicInteger();
		CountDownLatch cancelEntered = new CountDownLatch(1);
		CountDownLatch releaseCancel = new CountDownLatch(1);
		AtomicReference<StreamTermination> termination = new AtomicReference<>();
		PublisherResource resource = new PublisherResource(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
			@Override public void request(long count) { subscriber.onNext(ByteBuffer.wrap(new byte[]{1, 2})); }
			@Override public void cancel() {
				cancels.incrementAndGet();
				cancelEntered.countDown();
				awaitUninterruptibly(releaseCancel);
			}
		}));
		ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
		try {
			SokletSimulator.run(config(resource, termination), simulator -> {
				var request = requestExecutor.submit(() -> simulator.performHttpRequest(request()));
				try {
					Assertions.assertTrue(cancelEntered.await(3, TimeUnit.SECONDS));
					Assertions.assertFalse(request.isDone(), "The physical cancel attempt must keep production retained");
				} finally {
					releaseCancel.countDown();
				}
				java.util.concurrent.ExecutionException failure = Assertions.assertThrows(
						java.util.concurrent.ExecutionException.class, () -> request.get(3, TimeUnit.SECONDS));
				Assertions.assertInstanceOf(IllegalStateException.class, failure.getCause());
			});
			Assertions.assertEquals(1, cancels.get());
			Assertions.assertEquals(StreamTerminationReason.SIMULATOR_LIMIT_EXCEEDED, termination.get().getReason());
		} finally {
			releaseCancel.countDown();
			requestExecutor.shutdownNow();
			Assertions.assertTrue(requestExecutor.awaitTermination(3, TimeUnit.SECONDS));
		}
	}

	@Test
	public void throwingCancelCannotReplaceTheOriginalSubscribeFailure() {
		AtomicInteger cancels = new AtomicInteger();
		AtomicReference<StreamTermination> termination = new AtomicReference<>();
		IllegalArgumentException subscribeFailure = new IllegalArgumentException("subscribe failed");
		PublisherResource resource = new PublisherResource(subscriber -> {
			subscriber.onSubscribe(new Flow.Subscription() {
				@Override public void request(long count) {}
				@Override public void cancel() {
					cancels.incrementAndGet();
					throw new IllegalStateException("cancel failed");
				}
			});
			throw subscribeFailure;
		});
		SokletSimulator.run(config(resource, termination), simulator -> {
			IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
					() -> simulator.performHttpRequest(request()));
			Assertions.assertSame(subscribeFailure, failure.getCause());
		});
		Assertions.assertEquals(1, cancels.get());
		Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, termination.get().getReason());
		Assertions.assertSame(subscribeFailure, termination.get().getCause().orElseThrow());
	}

	@Test
	@Timeout(value = 90, unit = TimeUnit.SECONDS)
	public void lateSubscriptionAfterScopeCancelationReceivesNoDemand() throws Exception {
		AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>();
		CountDownLatch subscribed = new CountDownLatch(1);
		AtomicInteger cancels = new AtomicInteger();
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<StreamTermination> termination = new AtomicReference<>();
		PublisherResource resource = new PublisherResource(value -> {
			subscriber.set(value);
			subscribed.countDown();
		});
		AtomicReference<StreamLifecycleCoordinator> retainedCoordinator = new AtomicReference<>();
		AtomicBoolean delivered = new AtomicBoolean();
		Runnable deliverSubscription = () -> {
			Flow.Subscriber<? super ByteBuffer> retainedSubscriber = subscriber.get();
			if (retainedSubscriber != null && delivered.compareAndSet(false, true))
				retainedSubscriber.onSubscribe(new Flow.Subscription() {
					@Override public void request(long count) { requests.incrementAndGet(); }
					@Override public void cancel() { cancels.incrementAndGet(); }
				});
		};
		ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
		try {
			SokletSimulator.run(config(resource, termination), simulator -> {
				var request = requestExecutor.submit(() -> simulator.performHttpRequest(request()));
				try {
					Assertions.assertTrue(subscribed.await(3, TimeUnit.SECONDS));
					StreamLifecycleCoordinator coordinator = ((Soklet.DefaultSimulator) simulator)
							.getStreamLifecycleCoordinatorForTests().orElseThrow();
					retainedCoordinator.set(coordinator);
					coordinator.force();
					Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
							() -> request.get(3, TimeUnit.SECONDS));
					Assertions.assertEquals(0, coordinator.snapshot().runningProducers());
					Assertions.assertEquals(1, coordinator.snapshot().reservations());
					Assertions.assertEquals(1, coordinator.snapshot().publisherLifetimes());
					Assertions.assertEquals(1, coordinator.snapshot().pendingPublisherAcquisitions());
				} finally {
					((Soklet.DefaultSimulator) simulator).forceHttpScope();
					deliverSubscription.run();
				}
			});
			Assertions.assertEquals(0, requests.get());
			Assertions.assertEquals(1, cancels.get());
			Assertions.assertEquals(StreamTerminationReason.SERVER_STOPPING, termination.get().getReason());
		} finally {
			requestExecutor.shutdownNow();
			Assertions.assertTrue(requestExecutor.awaitTermination(3, TimeUnit.SECONDS));
			deliverSubscription.run();
			StreamLifecycleCoordinator coordinator = retainedCoordinator.get();
			if (coordinator != null) {
				coordinator.force();
				Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			}
		}
	}

	private static Request request() { return Request.withPath(HttpMethod.GET, "/publisher").build(); }

	private static SimulatorConfig config(PublisherResource resource, AtomicReference<StreamTermination> termination) {
		return SimulatorConfig.builder().httpServer()
				.simulatorOptions(SimulatorOptions.builder().streamingResponseBodyLimitInBytes(1).build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(PublisherResource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == PublisherResource.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didTerminateResponseStream(@NonNull StreamingResponseHandle handle,
							@NonNull StreamTermination result) { termination.set(result); }
					@Override public void didReceiveLogEvent(@NonNull LogEvent event) {}
				}).build();
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					if (!latch.await(5, TimeUnit.SECONDS))
						throw new AssertionError("Cancel attempt was not released");
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

	public static final class PublisherResource {
		private final Flow.Publisher<ByteBuffer> publisher;
		private PublisherResource(Flow.Publisher<ByteBuffer> publisher) { this.publisher = publisher; }
		@GET("/publisher") public MarshaledResponse publisher() {
			return MarshaledResponse.withStatusCode(200)
					.streamingResponseBody(StreamingResponseBody.fromPublisher(this.publisher)).build();
		}
	}
}
