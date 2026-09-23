/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.HttpMethod;
import com.soklet.Request;
import com.soklet.StreamTerminationReason;
import com.soklet.StreamingResponseBody;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StreamingPublisherLifecycleTests {
	@Test
	public void canceledPendingAcquisitionRetainsAdmissionAndTransfersToBlockedLateCancel() throws Exception {
		CountDownLatch cancelEntered = new CountDownLatch(1);
		CountDownLatch releaseCancel = new CountDownLatch(1);
		AtomicInteger demand = new AtomicInteger();
		AtomicInteger cancels = new AtomicInteger();
		ExecutorService callbacks = Executors.newSingleThreadExecutor();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.startPending();
				fixture.cancelAndAwaitProducerExit();
				Assertions.assertEquals(1, fixture.coordinator.snapshot().pendingPublisherAcquisitions());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				Assertions.assertFalse(fixture.coordinator.awaitTermination(System.nanoTime()));
				fixture.reservation.checkCleanupDeadline(fixture.reservation.cleanupDeadlineNanos());
				await(fixture.diagnostic);
				var evidence = Assertions.assertInstanceOf(StreamLifecycleCoordinator.CleanupDeadlineExceededException.class,
						fixture.diagnostics.get(0));
				Assertions.assertEquals(1, evidence.getPendingPublisherAcquisitions());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().overdue());
				var callback = callbacks.submit(() -> fixture.deliver(new Flow.Subscription() {
					@Override public void request(long count) { demand.incrementAndGet(); }
					@Override public void cancel() {
						cancels.incrementAndGet(); cancelEntered.countDown(); awaitUninterruptibly(releaseCancel);
					}
				}));
				await(cancelEntered);
				Assertions.assertEquals(0, fixture.coordinator.snapshot().pendingPublisherAcquisitions());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				Assertions.assertEquals(0, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				Assertions.assertEquals(0, demand.get());
				releaseCancel.countDown();
				callback.get(3, TimeUnit.SECONDS);
				until(() -> fixture.coordinator.snapshot().reservations() == 0);
				var replacement = fixture.coordinator.tryReserve();
				Assertions.assertNotNull(replacement);
				replacement.abandon();
				Assertions.assertEquals(1, cancels.get());
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
			} finally { releaseCancel.countDown(); }
		} finally { callbacks.shutdownNow(); Assertions.assertTrue(callbacks.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	@Test
	public void lateCancelFailureIsReportedBeforeTheObligationRetires() throws Exception {
		RuntimeException closeFailure = new IllegalStateException("late cancel failed");
		try (Fixture fixture = new Fixture()) {
			fixture.startPending();
			fixture.cancelAndAwaitProducerExit();
			fixture.deliver(new Flow.Subscription() {
				@Override public void request(long count) { Assertions.fail("Canceled acquisition requested data"); }
				@Override public void cancel() { throw closeFailure; }
			});
			await(fixture.diagnostic);
			Assertions.assertSame(closeFailure, fixture.diagnostics.get(0));
			until(() -> fixture.coordinator.snapshot().reservations() == 0);
			Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
		}
	}

	@Test
	public void successfulTerminalWaitsForTheEnclosingRequestFrame() throws Exception {
		CountDownLatch requestEntered = new CountDownLatch(1);
		CountDownLatch releaseRequest = new CountDownLatch(1);
		AtomicInteger cancels = new AtomicInteger();
		ExecutorService callbacks = Executors.newSingleThreadExecutor();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.startPending();
				var callback = callbacks.submit(() -> fixture.deliver(new Flow.Subscription() {
					@Override public void request(long count) {
						fixture.subscriber.get().onComplete();
						requestEntered.countDown(); awaitUninterruptibly(releaseRequest);
					}
					@Override public void cancel() { cancels.incrementAndGet(); }
				}));
				await(requestEntered);
				until(() -> fixture.reservation.cleanupDeadlineNanos() != 0);
				Assertions.assertFalse(fixture.reservation.isProductionComplete());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				releaseRequest.countDown();
				callback.get(3, TimeUnit.SECONDS);
				fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
				Assertions.assertTrue(fixture.reservation.isProductionComplete());
				Assertions.assertEquals(0, fixture.coordinator.snapshot().publisherLifetimes());
				Assertions.assertEquals(0, cancels.get());
			} finally { releaseRequest.countDown(); }
		} finally { callbacks.shutdownNow(); Assertions.assertTrue(callbacks.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	@Test
	public void completedPublisherWithBlockedRequestTailCanExpireWithoutHidingItsWork() throws Exception {
		CountDownLatch requestEntered = new CountDownLatch(1);
		CountDownLatch releaseRequest = new CountDownLatch(1);
		ExecutorService callbacks = Executors.newSingleThreadExecutor();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.startPending();
				var callback = callbacks.submit(() -> fixture.deliver(new Flow.Subscription() {
					@Override public void request(long count) {
						fixture.subscriber.get().onComplete();
						requestEntered.countDown(); awaitUninterruptibly(releaseRequest);
					}
					@Override public void cancel() { Assertions.fail("A terminal subscription needs no second cleanup"); }
				}));
				await(requestEntered);
				until(() -> fixture.reservation.cleanupDeadlineNanos() != 0);
				fixture.reservation.checkCleanupDeadline(fixture.reservation.cleanupDeadlineNanos());
				await(fixture.terminated);
				fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, fixture.reason.get());
				Assertions.assertEquals(0, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				releaseRequest.countDown();
				callback.get(3, TimeUnit.SECONDS);
				until(() -> fixture.coordinator.snapshot().reservations() == 0);
			} finally { releaseRequest.countDown(); }
		} finally { callbacks.shutdownNow(); Assertions.assertTrue(callbacks.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	@Test
	public void throwingSubscribeBeforeAcquisitionRetiresAndRejectsLaterProtocolViolation() throws Exception {
		RuntimeException failed = new IllegalArgumentException("subscribe failed");
		try (Fixture fixture = new Fixture()) {
			fixture.start(value -> { fixture.subscriber.set(value); throw failed; });
			await(fixture.terminated);
			fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
			until(() -> fixture.coordinator.snapshot().reservations() == 0);
			Assertions.assertSame(failed, fixture.reservation.cause().orElseThrow());
			Assertions.assertThrows(IllegalStateException.class, () -> fixture.deliver(new Flow.Subscription() {
				@Override public void request(long count) { Assertions.fail("Invalid late demand"); }
				@Override public void cancel() { Assertions.fail("Invalid late cleanup must not begin after retirement"); }
			}));
		}
	}

	@Test
	public void terminalBeforeSubscriptionFailsInsteadOfCompletingSuccessfully() throws Exception {
		try (Fixture fixture = new Fixture()) {
			fixture.start(Flow.Subscriber::onComplete);
			await(fixture.terminated);
			fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
			until(() -> fixture.coordinator.snapshot().reservations() == 0);
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, fixture.reason.get());
			Assertions.assertInstanceOf(IllegalStateException.class, fixture.reservation.cause().orElseThrow());
		}
	}

	@Test
	public void synchronousTerminalStartsCleanupBeforeSubscribePhysicallyReturns() throws Exception {
		CountDownLatch completed = new CountDownLatch(1);
		CountDownLatch releaseSubscribe = new CountDownLatch(1);
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.start(subscriber -> {
					subscriber.onSubscribe(new Flow.Subscription() {
						@Override public void request(long count) { subscriber.onComplete(); }
						@Override public void cancel() { Assertions.fail("Publisher already terminated"); }
					});
					completed.countDown();
					awaitUninterruptibly(releaseSubscribe);
				});
				await(completed);
				Assertions.assertNotEquals(0, fixture.reservation.cleanupDeadlineNanos());
				fixture.reservation.checkCleanupDeadline(fixture.reservation.cleanupDeadlineNanos());
				await(fixture.terminated);
				Assertions.assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, fixture.reason.get());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				Assertions.assertEquals(1, fixture.coordinator.snapshot().runningProducers());
				Assertions.assertNull(fixture.coordinator.tryReserve());
				releaseSubscribe.countDown();
				fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
				until(() -> fixture.coordinator.snapshot().reservations() == 0);
			} finally { releaseSubscribe.countDown(); }
		}
	}

	@Test
	public void duplicateOriginalSubscriptionFailsWithoutDoubleCancelOrSuccessfulEof() throws Exception {
		AtomicInteger cancels = new AtomicInteger();
		try (Fixture fixture = new Fixture()) {
			fixture.start(subscriber -> {
				Flow.Subscription subscription = new Flow.Subscription() {
					@Override public void request(long count) {}
					@Override public void cancel() { cancels.incrementAndGet(); }
				};
				subscriber.onSubscribe(subscription);
				subscriber.onSubscribe(subscription);
			});
			await(fixture.terminated);
			fixture.producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
			until(() -> fixture.coordinator.snapshot().reservations() == 0);
			Assertions.assertEquals(StreamTerminationReason.PRODUCER_FAILED, fixture.reason.get());
			Assertions.assertEquals(1, cancels.get());
		}
	}

	@Test
	public void providerFailureAfterCanceledProducerExitRetainsItsDiagnosticEvidence() throws Exception {
		RuntimeException requestFailure = new IllegalArgumentException("late request failure");
		CountDownLatch requestEntered = new CountDownLatch(1);
		CountDownLatch releaseRequest = new CountDownLatch(1);
		ExecutorService callbacks = Executors.newSingleThreadExecutor();
		try (Fixture fixture = new Fixture()) {
			try {
				fixture.startPending();
				var callback = callbacks.submit(() -> fixture.deliver(new Flow.Subscription() {
					@Override public void request(long count) {
						requestEntered.countDown(); awaitUninterruptibly(releaseRequest); throw requestFailure;
					}
					@Override public void cancel() {}
				}));
				await(requestEntered);
				fixture.cancelAndAwaitProducerExit();
				Assertions.assertEquals(1, fixture.coordinator.snapshot().publisherLifetimes());
				releaseRequest.countDown();
				callback.get(3, TimeUnit.SECONDS);
				await(fixture.diagnostic);
				Assertions.assertSame(requestFailure, fixture.diagnostics.get(0));
				until(() -> fixture.coordinator.snapshot().reservations() == 0);
				Assertions.assertEquals(StreamTerminationReason.CLIENT_DISCONNECTED, fixture.reason.get());
			} finally { releaseRequest.countDown(); }
		} finally { callbacks.shutdownNow(); Assertions.assertTrue(callbacks.awaitTermination(3, TimeUnit.SECONDS)); }
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS));
	}
	private static void until(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
		Assertions.assertTrue(condition.getAsBoolean());
	}
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
		if (interrupted) Thread.currentThread().interrupt();
	}

	private static final class Fixture implements AutoCloseable {
		private final ExecutorService producers = Executors.newSingleThreadExecutor();
		private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
		private final AtomicLong clock = new AtomicLong(100L);
		private final List<Throwable> diagnostics = new CopyOnWriteArrayList<>();
		private final CountDownLatch diagnostic = new CountDownLatch(1);
		private final StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1,
				Duration.ofSeconds(30), failure -> { diagnostics.add(failure); diagnostic.countDown(); }, clock::get);
		private final CountDownLatch terminated = new CountDownLatch(1);
		private final AtomicReference<StreamTerminationReason> reason = new AtomicReference<>();
		private final AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>();
		private StreamLifecycleCoordinator.Reservation reservation;
		private WritableSource source;
		private boolean delivered;
		private void startPending() throws Exception {
			CountDownLatch subscribed = new CountDownLatch(1);
			start(value -> { subscriber.set(value); subscribed.countDown(); });
			await(subscribed);
		}
		private void start(Flow.Publisher<ByteBuffer> publisher) throws Exception {
			reservation = coordinator.tryReserve();
			Assertions.assertNotNull(reservation);
			MicrohttpResponse response = StreamingMicrohttpResponses.withStreamingBody(200, "OK",
					List.of(new Header("Transfer-Encoding", "chunked")), Request.withPath(HttpMethod.GET, "/publisher").build(),
					StreamingResponseBody.fromPublisher(publisher), producers, timeouts, 1_024, 1_024,
					null, null, () -> false, (established, duration, reason, cause) -> {
						this.reason.set(reason); terminated.countDown();
					}, diagnostics::add, reservation);
			source = response.writableSource(response.serializeHead("HTTP/1.1", List.of()));
			source.start();
		}
		private void cancelAndAwaitProducerExit() throws Exception {
			source.close(StreamTerminationReason.CLIENT_DISCONNECTED, null);
			await(terminated);
			producers.submit(() -> {}).get(3, TimeUnit.SECONDS);
			Assertions.assertEquals(0, coordinator.snapshot().runningProducers());
		}
		private void deliver(Flow.Subscription subscription) { delivered = true; subscriber.get().onSubscribe(subscription); }
		@Override public void close() throws Exception {
			try {
				if (source != null) source.close(StreamTerminationReason.SERVER_STOPPING, null);
				if (!delivered && coordinator.snapshot().pendingPublisherAcquisitions() > 0 && subscriber.get() != null)
					deliver(new Flow.Subscription() {
						@Override public void request(long count) {}
						@Override public void cancel() {}
					});
				coordinator.force();
				producers.shutdownNow(); timeouts.shutdownNow();
				Assertions.assertTrue(producers.awaitTermination(3, TimeUnit.SECONDS));
				Assertions.assertTrue(coordinator.awaitTermination(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)));
			} finally { producers.shutdownNow(); timeouts.shutdownNow(); }
		}
	}
}
