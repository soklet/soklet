package com.soklet.internal.streaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class StateChangeWaitersForkJoinTests {
	@Test void exhaustedCompensationKeepsWaitingUntilTheOwnerSignals() throws Exception {
		Object monitor = new Object();
		StateChangeWaiters waiters = new StateChangeWaiters(monitor);
		AtomicBoolean blocked = new AtomicBoolean(true);
		CountDownLatch registered = new CountDownLatch(1);
		ForkJoinPool pool = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null,
				false, 1, 1, 1, null, 60, TimeUnit.SECONDS);
		try {
			var producer = pool.submit(() -> {
				try { waiters.awaitWhile(() -> { registered.countDown(); return blocked.get(); }); }
				catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
			});
			assertTrue(registered.await(2, TimeUnit.SECONDS));
			assertThrows(java.util.concurrent.TimeoutException.class, () -> producer.get(100, TimeUnit.MILLISECONDS));
			synchronized (monitor) { blocked.set(false); waiters.signalAll(); }
			producer.get(2, TimeUnit.SECONDS);
		} finally {
			synchronized (monitor) { blocked.set(false); waiters.signalAll(); }
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
		}
	}
	@Test void blockedPublisherWorkerAllowsUnrelatedPoolWorkAndReleasesOnSignal() throws Exception {
		Object monitor = new Object();
		StateChangeWaiters waiters = new StateChangeWaiters(monitor);
		AtomicBoolean blocked = new AtomicBoolean(true);
		CountDownLatch registered = new CountDownLatch(1);
		ForkJoinPool pool = new ForkJoinPool(1);
		try {
			var producer = pool.submit(() -> {
				try {
					waiters.awaitWhile(() -> {
						assertTrue(Thread.holdsLock(monitor));
						registered.countDown();
						return blocked.get();
					});
				} catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
			});
			assertTrue(registered.await(2, TimeUnit.SECONDS));
			assertEquals("ran", pool.submit(() -> "ran").get(2, TimeUnit.SECONDS));
			assertFalse(producer.isDone());
			synchronized (monitor) { blocked.set(false); waiters.signalAll(); }
			producer.get(2, TimeUnit.SECONDS);
		} finally {
			synchronized (monitor) { blocked.set(false); waiters.signalAll(); }
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
		}
	}
}
