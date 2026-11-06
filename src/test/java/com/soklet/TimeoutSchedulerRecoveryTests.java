package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class TimeoutSchedulerRecoveryTests {
	@Test
	void transferAllocationFailureRetainsAffectedAndLaterDeadlines() throws Exception {
		transientFailure(1);
	}

	@Test
	void expiryAllocationFailureRetriesAffectedTickAndLaterDeadlines() throws Exception {
		transientFailure(2);
	}

	private static void transientFailure(int failOnCall) throws Exception {
		try (Fixture fixture = new Fixture(512)) { // 2.56 s rotation exceeds the 1 s recovery guard.
			AtomicInteger affected = new AtomicInteger(), later = new AtomicInteger();
			CountDownLatch done = new CountDownLatch(2);
			var task = fixture.scheduler.schedule(() -> { affected.incrementAndGet(); done.countDown(); }, Duration.ofMillis(10));
			FaultLock lock = new FaultLock(fixture.worker, failOnCall,
					new OutOfMemoryError("Synthetic one-shot housekeeping allocation failure"));
			replaceBucketLock(fixture.scheduler, task, lock);
			fixture.start.countDown();
			assertTrue(lock.failed.await(1, TimeUnit.SECONDS));
			fixture.scheduler.schedule(() -> { later.incrementAndGet(); done.countDown(); }, Duration.ofMillis(10));
			assertTrue(done.await(1, TimeUnit.SECONDS), "Affected and later deadlines must both survive");
			assertEquals(1, affected.get());
			assertEquals(1, later.get());
			assertFalse(fixture.scheduler.isTerminated());
			assertNull(fixture.failure.get());
		}
	}

	@Test
	void detachedCancellationAndLongDelaySurviveMultipleWheelTurns() throws Exception {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger canceledCalls = new AtomicInteger(), longCalls = new AtomicInteger(), detachedActiveCalls = new AtomicInteger();
			CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
			var constructor = TimeoutScheduler.ScheduledTask.class.getDeclaredConstructor(Runnable.class, long.class);
			constructor.setAccessible(true);
			var activeTail = constructor.newInstance((Runnable) detachedActiveCalls::incrementAndGet, 1L);
			var canceled = constructor.newInstance((Runnable) canceledCalls::incrementAndGet, 1L);
			var blocking = constructor.newInstance((Runnable) () -> {
				entered.countDown();
				try { if (!release.await(1, TimeUnit.SECONDS)) throw new AssertionError("Missing release"); }
				catch (InterruptedException failure) { throw new AssertionError(failure); }
			}, 1L);
			@SuppressWarnings("unchecked") Collection<TimeoutScheduler.ScheduledTask> pending =
					(Collection<TimeoutScheduler.ScheduledTask>) field(fixture.scheduler, "pendingTasks");
			pending.add(activeTail);
			pending.add(canceled);
			pending.add(blocking); // Buckets prepend; this callback runs first.
			long startedAt = System.nanoTime();
			AtomicLong elapsed = new AtomicLong();
			fixture.scheduler.schedule(() -> {
				longCalls.incrementAndGet(); elapsed.set(System.nanoTime() - startedAt); done.countDown();
			}, Duration.ofMillis(100)); // 2.5 wheel rotations.
			fixture.start.countDown();
			try {
				assertTrue(entered.await(1, TimeUnit.SECONDS));
				assertNull(field(canceled, "bucket"), "The due batch must already be detached");
				canceled.cancel();
			} finally { release.countDown(); }
			assertTrue(done.await(1, TimeUnit.SECONDS));
			assertEquals(0, canceledCalls.get());
			assertEquals(1, detachedActiveCalls.get(), "Cancellation of one detached task cannot lose the remaining chain");
			assertEquals(1, longCalls.get());
			assertTrue(elapsed.get() >= TimeUnit.MILLISECONDS.toNanos(95), "Long deadline ran early");
			assertNull(fixture.failure.get());
		}
	}

	@Test
	void shutdownDuringPendingTransferCannotRelinkTaskAfterBucketClear() throws Exception {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger calls = new AtomicInteger();
			var task = fixture.scheduler.schedule(calls::incrementAndGet, Duration.ofMillis(10));
			BlockingLock lock = new BlockingLock(fixture.worker);
			replaceBucketLock(fixture.scheduler, task, lock);
			fixture.start.countDown();
			assertTrue(lock.entered.await(1, TimeUnit.SECONDS));
			try {
				fixture.scheduler.shutdownNow();
			} finally { lock.release.countDown(); }
			assertTrue(fixture.scheduler.awaitTermination(2, TimeUnit.SECONDS));
			assertTrue(fixture.scheduler.isShutdown());
			assertNull(field(task, "bucket"), "The worker cannot relink after shutdown cleared the bucket");
			assertTrue(((Collection<?>) field(fixture.scheduler, "pendingTasks")).isEmpty());
			assertEquals(0, calls.get());
			assertNull(fixture.failure.get());
		}
	}

	@Test
	void unrelatedVmFailureStopsWorkerAndRejectsNewDeadlinesWithExactCause() throws Exception {
		try (Fixture fixture = new Fixture()) {
			AtomicInteger calls = new AtomicInteger();
			var task = fixture.scheduler.schedule(calls::incrementAndGet, Duration.ofMillis(10));
			InternalError expected = new InternalError("Synthetic terminal housekeeping failure");
			replaceBucketLock(fixture.scheduler, task, new FaultLock(fixture.worker, 1, expected));
			fixture.start.countDown();
			assertTrue(fixture.failed.await(1, TimeUnit.SECONDS));
			assertTrue(fixture.scheduler.awaitTermination(2, TimeUnit.SECONDS));
			assertSame(expected, fixture.failure.get());
			assertFalse(fixture.scheduler.isShutdown(), "Worker failure must not masquerade as explicit shutdown");
			int pendingBefore = ((Collection<?>) field(fixture.scheduler, "pendingTasks")).size();
			RejectedExecutionException rejected = assertThrows(RejectedExecutionException.class,
					() -> fixture.scheduler.schedule(calls::incrementAndGet, Duration.ofMillis(10)));
			assertSame(expected, rejected.getCause());
			assertEquals(pendingBefore, ((Collection<?>) field(fixture.scheduler, "pendingTasks")).size());
			assertEquals(0, calls.get());
		}
	}

	@Test
	void applicationCallbackOutOfMemoryDoesNotPreventLaterDeadline() throws Exception {
		try (Fixture fixture = new Fixture()) {
			CountDownLatch done = new CountDownLatch(1);
			fixture.scheduler.schedule(() -> { throw new OutOfMemoryError("Synthetic callback failure"); }, Duration.ofMillis(5));
			fixture.scheduler.schedule(done::countDown, Duration.ofMillis(20));
			fixture.start.countDown();
			assertTrue(done.await(1, TimeUnit.SECONDS));
			assertNull(fixture.failure.get());
			assertFalse(fixture.scheduler.isTerminated());
		}
	}

	private static void replaceBucketLock(TimeoutScheduler scheduler, TimeoutScheduler.ScheduledTask task,
			ReentrantLock lock) throws Exception {
		Object[] buckets = (Object[]) field(scheduler, "buckets");
		long deadline = (long) field(task, "deadlineTick");
		Object bucket = buckets[Math.floorMod(deadline, buckets.length)];
		Field target = bucket.getClass().getDeclaredField("lock");
		target.setAccessible(true); target.set(bucket, lock);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true); return field.get(target);
	}

	private static class FaultLock extends ReentrantLock {
		private static final long serialVersionUID = 1L;
		private final AtomicReference<Thread> worker;
		private final int failOnCall;
		private final Error failure;
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch failed = new CountDownLatch(1);
		private FaultLock(AtomicReference<Thread> worker, int failOnCall, Error failure) {
			this.worker = worker; this.failOnCall = failOnCall; this.failure = failure;
		}
		@Override public void lock() {
			if (Thread.currentThread() == this.worker.get() && this.calls.incrementAndGet() == this.failOnCall) {
				this.failed.countDown(); throw this.failure;
			}
			super.lock();
		}
	}

	private static final class BlockingLock extends ReentrantLock {
		private static final long serialVersionUID = 1L;
		private final AtomicReference<Thread> worker;
		private final AtomicInteger calls = new AtomicInteger();
		private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		private BlockingLock(AtomicReference<Thread> worker) { this.worker = worker; }
		@Override public void lock() {
			if (Thread.currentThread() == this.worker.get() && this.calls.getAndIncrement() == 0) {
				this.entered.countDown();
				boolean interrupted = false;
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				try {
					while (this.release.getCount() != 0) {
						try { if (!this.release.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
							throw new AssertionError("Missing transfer release"); }
						catch (InterruptedException ignored) { interrupted = true; }
					}
				} finally { if (interrupted) Thread.currentThread().interrupt(); }
			}
			super.lock();
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final CountDownLatch start = new CountDownLatch(1), failed = new CountDownLatch(1);
		private final AtomicReference<Thread> worker = new AtomicReference<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private final TimeoutScheduler scheduler;
		private Fixture() { this(8); }
		private Fixture(int ticksPerWheel) {
			this.scheduler = new TimeoutScheduler(task -> {
			Thread thread = new Thread(() -> {
				try { if (!this.start.await(2, TimeUnit.SECONDS)) throw new AssertionError("Missing worker release"); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
				task.run();
			}, "scheduler-recovery-test");
			thread.setDaemon(true);
			thread.setUncaughtExceptionHandler((ignored, problem) -> { this.failure.set(problem); this.failed.countDown(); });
			this.worker.set(thread); return thread;
			}, Duration.ofMillis(5), ticksPerWheel);
		}
		@Override public void close() throws Exception {
			this.start.countDown(); this.scheduler.shutdownNow();
			assertTrue(this.scheduler.awaitTermination(2, TimeUnit.SECONDS));
		}
	}
}
