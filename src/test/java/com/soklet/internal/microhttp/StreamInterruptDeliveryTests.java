/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet.internal.microhttp;

import com.soklet.StreamTerminationReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class StreamInterruptDeliveryTests {
	@Test
	void queuedDeliverySkipsAProducerThatExitedAndReusedItsPoolThread() throws Exception {
		CountDownLatch deliveryEntered = new CountDownLatch(1), releaseDelivery = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = StreamLifecycleCoordinator.withCallbackExecution(1, 1, Duration.ofSeconds(5), ignored -> {}, action -> {
			if (Thread.currentThread().getName().startsWith("stream-interrupt-")) {
				deliveryEntered.countDown(); awaitUninterruptibly(releaseDelivery);
			}
			action.run();
		});
		ExecutorService pool = Executors.newSingleThreadExecutor();
		CountDownLatch producerEntered = new CountDownLatch(1), releaseProducer = new CountDownLatch(1);
		CountDownLatch nextEntered = new CountDownLatch(1), releaseNext = new CountDownLatch(1);
		AtomicBoolean nextInterrupted = new AtomicBoolean();
		var reservation = coordinator.tryReserve(); assertNotNull(reservation);
		reservation.bindTermination((reason, cause) -> reservation.complete());
		try {
			reservation.execute(pool, () -> { producerEntered.countDown(); awaitUninterruptibly(releaseProducer); });
			await(producerEntered);
			assertEquals(0, interrupts(coordinator).getPoolSize(), "Healthy execution creates no interrupt worker");
			assertTrue(reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null));
			await(deliveryEntered);
			releaseProducer.countDown();
			pool.execute(() -> {
				nextEntered.countDown();
				for (;;) {
					try { releaseNext.await(); break; }
					catch (InterruptedException unexpected) { nextInterrupted.set(true); }
				}
			});
			await(nextEntered);
			coordinator.force(); releaseDelivery.countDown();
			assertTrue(coordinator.awaitTermination(deadline()));
			assertFalse(nextInterrupted.get(), "A delayed interrupt must not reach unrelated work on the reused thread");
		} finally {
			releaseDelivery.countDown(); releaseProducer.countDown(); releaseNext.countDown();
			pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
			coordinator.force(); assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	@Test
	void enteredDeliveryRetainsItsTargetAndDoesNotBlockAnotherReservation() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(2, 1, Duration.ofSeconds(5), ignored -> {});
		CountDownLatch interruptEntered = new CountDownLatch(1), releaseInterrupt = new CountDownLatch(1);
		AtomicInteger interruptCalls = new AtomicInteger();
		ExecutorService firstPool = Executors.newSingleThreadExecutor(action -> new Thread(action, "blocked-interrupt-target") {
			@Override public void interrupt() { interruptCalls.incrementAndGet(); interruptEntered.countDown(); awaitUninterruptibly(releaseInterrupt); super.interrupt(); }
		});
		ExecutorService secondPool = Executors.newSingleThreadExecutor();
		CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), nextEntered = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1), secondInterrupted = new CountDownLatch(1);
		var first = coordinator.tryReserve(); var second = coordinator.tryReserve(); assertNotNull(first); assertNotNull(second);
		first.bindTermination((reason, cause) -> first.complete()); second.bindTermination((reason, cause) -> second.complete());
		try {
			first.execute(firstPool, () -> { firstEntered.countDown(); awaitUninterruptibly(releaseFirst); });
			second.execute(secondPool, () -> { secondEntered.countDown(); try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { secondInterrupted.countDown(); } });
			await(firstEntered); await(secondEntered);
			assertTrue(first.cancel(StreamTerminationReason.SERVER_STOPPING, null)); await(interruptEntered);
			assertFalse(first.cancel(StreamTerminationReason.CLIENT_DISCONNECTED, null));
			releaseFirst.countDown(); firstPool.execute(nextEntered::countDown);
			assertTrue(second.cancel(StreamTerminationReason.SERVER_STOPPING, null)); await(secondInterrupted);
			assertEquals(1, interruptCalls.get());
			long secondExitDeadline = deadline();
			while (coordinator.snapshot().runningProducers() != 1 && System.nanoTime() - secondExitDeadline < 0) Thread.yield();
			assertEquals(1, coordinator.snapshot().runningProducers());
			assertTrue(coordinator.snapshot().retainedWork() > 0);
			assertFalse(nextEntered.await(30, TimeUnit.MILLISECONDS), "A target pool thread cannot exit its producer envelope while interrupt is in flight");
			coordinator.force(); assertFalse(coordinator.awaitTermination(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)));
			releaseInterrupt.countDown(); await(nextEntered);
			assertTrue(coordinator.awaitTermination(deadline()));
		} finally {
			releaseInterrupt.countDown(); releaseFirst.countDown();
			firstPool.shutdown(); secondPool.shutdown();
			assertTrue(firstPool.awaitTermination(5, TimeUnit.SECONDS)); assertTrue(secondPool.awaitTermination(5, TimeUnit.SECONDS));
			coordinator.force(); assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	@Test
	void cleanupDeadlineRequestsInterruptWithoutHoldingCoordinatorOrCaller() throws Exception {
		CountDownLatch interruptEntered = new CountDownLatch(1), releaseInterrupt = new CountDownLatch(1);
		CountDownLatch producerEntered = new CountDownLatch(1), releaseProducer = new CountDownLatch(1), deadlineReturned = new CountDownLatch(1);
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(5), ignored -> {});
		var reservation = coordinator.tryReserve(); assertNotNull(reservation);
		reservation.bindTermination((reason, cause) -> reservation.complete());
		Thread producer = new Thread(() -> reservation.executeInline(() -> {
			reservation.beginCleanup(); producerEntered.countDown(); awaitUninterruptibly(releaseProducer);
		}), "deadline-interrupt-target") {
			@Override public void interrupt() { interruptEntered.countDown(); awaitUninterruptibly(releaseInterrupt); super.interrupt(); }
		};
		producer.setDaemon(true);
		Thread expiry = new Thread(() -> { reservation.checkCleanupDeadline(reservation.cleanupDeadlineNanos()); deadlineReturned.countDown(); }, "deadline-interrupt-request");
		expiry.setDaemon(true);
		try {
			producer.start(); await(producerEntered); expiry.start(); await(interruptEntered); await(deadlineReturned);
			assertEquals(StreamTerminationReason.CLEANUP_TIMEOUT, reservation.reason().orElseThrow());
			assertEquals(1, coordinator.snapshot().runningProducers());
			assertTrue(coordinator.snapshot().retainedWork() > 0);
			coordinator.force(); assertFalse(coordinator.awaitTermination(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)));
		} finally {
			releaseInterrupt.countDown(); releaseProducer.countDown(); producer.join(5_000); expiry.join(5_000);
			assertFalse(producer.isAlive()); assertFalse(expiry.isAlive()); coordinator.force(); assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	@Test
	void rejectedInterruptDeliveryNeverRunsInlineOrRetiresTheRunningProducer() throws Exception {
		StreamLifecycleCoordinator coordinator = new StreamLifecycleCoordinator(1, 1, Duration.ofSeconds(5), ignored -> {});
		ExecutorService pool = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		var reservation = coordinator.tryReserve(); assertNotNull(reservation);
		reservation.bindTermination((reason, cause) -> reservation.complete());
		LateRejectingExecutor rejectedDelivery = new LateRejectingExecutor();
		try {
			reservation.execute(pool, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException unexpected) { interrupted.set(true); } });
			await(entered);
			ThreadPoolExecutor original = interrupts(coordinator); original.shutdown(); assertTrue(original.awaitTermination(5, TimeUnit.SECONDS));
			Field executorField = StreamLifecycleCoordinator.class.getDeclaredField("interruptExecutor"); executorField.setAccessible(true);
			executorField.set(coordinator, rejectedDelivery);
			assertTrue(reservation.cancel(StreamTerminationReason.SERVER_STOPPING, null));
			assertNotNull(rejectedDelivery.delivery);
			rejectedDelivery.delivery.run();
			assertFalse(interrupted.get()); assertEquals(1, coordinator.snapshot().runningProducers());
			assertEquals(0, coordinator.snapshot().retainedWork());
			coordinator.force(); assertFalse(coordinator.awaitTermination(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)));
			release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
			assertTrue(coordinator.awaitTermination(deadline()));
		} finally {
			release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
			coordinator.force(); assertTrue(coordinator.awaitTermination(deadline()));
		}
	}

	private static final class LateRejectingExecutor extends ThreadPoolExecutor {
		Runnable delivery;
		LateRejectingExecutor() { super(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
		@Override public void execute(Runnable action) { this.delivery = action; throw new RejectedExecutionException("Expected submission failure"); }
	}

	private static ThreadPoolExecutor interrupts(StreamLifecycleCoordinator coordinator) throws Exception {
		Field field = StreamLifecycleCoordinator.class.getDeclaredField("interruptExecutor"); field.setAccessible(true);
		return (ThreadPoolExecutor) field.get(coordinator);
	}
	private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }
	private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) { try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
		if (interrupted) Thread.currentThread().interrupt();
	}
}
