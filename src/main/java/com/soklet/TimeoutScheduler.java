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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/**
 * Small hashed-wheel timeout scheduler for request handling deadlines.
 * <p>
 * Scheduling and cancellation are O(1), and assigned cancelled tasks are removed from their bucket immediately.
 */
@ThreadSafe
final class TimeoutScheduler {
	private static final Duration DEFAULT_TICK_DURATION = Duration.ofMillis(10);
	private static final int DEFAULT_TICKS_PER_WHEEL = 1024;
	private final long tickNanos;
	private final Bucket[] buckets;
	private final ConcurrentLinkedQueue<ScheduledTask> pendingTasks;
	private final AtomicBoolean shutdown;
	private final Thread workerThread;
	private volatile long currentTick;

	TimeoutScheduler(@NonNull ThreadFactory threadFactory) {
		this(threadFactory, DEFAULT_TICK_DURATION, DEFAULT_TICKS_PER_WHEEL);
	}

	TimeoutScheduler(@NonNull ThreadFactory threadFactory,
									 @NonNull Duration tickDuration,
									 int ticksPerWheel) {
		requireNonNull(threadFactory);
		requireNonNull(tickDuration);

		if (tickDuration.isZero() || tickDuration.isNegative())
			throw new IllegalArgumentException("Tick duration must be > 0.");

		if (ticksPerWheel < 2)
			throw new IllegalArgumentException("Ticks per wheel must be >= 2.");

		this.tickNanos = tickDuration.toNanos();
		this.buckets = new Bucket[ticksPerWheel];

		for (int i = 0; i < this.buckets.length; i++)
			this.buckets[i] = new Bucket();

		this.pendingTasks = new ConcurrentLinkedQueue<>();
		this.shutdown = new AtomicBoolean(false);
		this.workerThread = threadFactory.newThread(this::run);
		this.workerThread.start();
	}

	@NonNull
	ScheduledTask schedule(@NonNull Runnable task,
												 @NonNull Duration delay) {
		requireNonNull(task);
		requireNonNull(delay);

		if (delay.isNegative() || delay.isZero())
			throw new IllegalArgumentException("Delay must be > 0.");

		if (this.shutdown.get())
			throw new RejectedExecutionException("Timeout scheduler is shut down.");

		long deadlineTick = saturatedAdd(this.currentTick, ticksFor(delay));
		ScheduledTask scheduledTask = new ScheduledTask(task, deadlineTick);
		this.pendingTasks.add(scheduledTask);
		LockSupport.unpark(this.workerThread);
		return scheduledTask;
	}

	boolean isShutdown() {
		return this.shutdown.get();
	}

	void shutdown() {
		if (this.shutdown.compareAndSet(false, true))
			LockSupport.unpark(this.workerThread);
	}

	void shutdownNow() {
		shutdown();
		this.pendingTasks.clear();

		for (Bucket bucket : this.buckets)
			bucket.clear();

		this.workerThread.interrupt();
	}

	boolean awaitTermination(long timeout,
													 @NonNull TimeUnit unit) throws InterruptedException {
		requireNonNull(unit);

		long timeoutNanos = unit.toNanos(timeout);

		if (timeoutNanos <= 0L)
			return !this.workerThread.isAlive();

		long deadlineNanos = System.nanoTime() + timeoutNanos;
		long remainingNanos = timeoutNanos;

		while (this.workerThread.isAlive() && remainingNanos > 0L) {
			long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
			int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
			this.workerThread.join(millis, nanos);
			remainingNanos = deadlineNanos - System.nanoTime();
		}

		return !this.workerThread.isAlive();
	}

	private void run() {
		long nextTickAt = System.nanoTime() + this.tickNanos;

		while (!this.shutdown.get()) {
			long sleepNanos = nextTickAt - System.nanoTime();

			if (sleepNanos > 0L)
				LockSupport.parkNanos(this, sleepNanos);

			if (this.shutdown.get())
				break;

			long now = System.nanoTime();

			while (!this.shutdown.get() && nextTickAt - now <= 0L) {
				long tick = this.currentTick + 1L;
				this.currentTick = tick;
				transferPendingTasks();
				expireBucket(tick);
				nextTickAt += this.tickNanos;
				now = System.nanoTime();
			}
		}
	}

	private void transferPendingTasks() {
		ScheduledTask scheduledTask;

		while ((scheduledTask = this.pendingTasks.poll()) != null)
			if (!scheduledTask.isCancelled())
				bucketFor(scheduledTask.deadlineTick).add(scheduledTask);
	}

	private void expireBucket(long tick) {
		List<ScheduledTask> expiredTasks = bucketFor(tick).expire(tick);

		for (ScheduledTask expiredTask : expiredTasks)
			if (!expiredTask.isCancelled())
				expiredTask.task.run();
	}

	@NonNull
	private Bucket bucketFor(long tick) {
		return this.buckets[Math.floorMod(tick, this.buckets.length)];
	}

	private long ticksFor(@NonNull Duration delay) {
		long delayNanos = saturatedNanos(delay);
		long ticks = delayNanos / this.tickNanos;

		if (delayNanos % this.tickNanos != 0L)
			ticks++;

		return Math.max(1L, ticks);
	}

	private static long saturatedNanos(@NonNull Duration duration) {
		requireNonNull(duration);

		try {
			return duration.toNanos();
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	private static long saturatedAdd(long left,
																	 long right) {
		if (Long.MAX_VALUE - left < right)
			return Long.MAX_VALUE;

		return left + right;
	}

	static final class ScheduledTask {
		@NonNull
		private final Runnable task;
		private final long deadlineTick;
		private final AtomicBoolean cancelled;
		@Nullable
		private volatile Bucket bucket;
		@Nullable
		private ScheduledTask previous;
		@Nullable
		private ScheduledTask next;

		private ScheduledTask(@NonNull Runnable task,
													long deadlineTick) {
			requireNonNull(task);
			this.task = task;
			this.deadlineTick = deadlineTick;
			this.cancelled = new AtomicBoolean(false);
		}

		void cancel() {
			if (!this.cancelled.compareAndSet(false, true))
				return;

			Bucket bucket = this.bucket;

			if (bucket != null)
				bucket.remove(this);
		}

		private boolean isCancelled() {
			return this.cancelled.get();
		}
	}

	private static final class Bucket {
		@NonNull
		private final ReentrantLock lock;
		@Nullable
		private ScheduledTask head;

		private Bucket() {
			this.lock = new ReentrantLock();
		}

		private void add(@NonNull ScheduledTask scheduledTask) {
			requireNonNull(scheduledTask);
			this.lock.lock();
			try {
				if (scheduledTask.isCancelled())
					return;

				scheduledTask.bucket = this;
				scheduledTask.previous = null;
				scheduledTask.next = this.head;

				if (this.head != null)
					this.head.previous = scheduledTask;

				this.head = scheduledTask;
			} finally {
				this.lock.unlock();
			}
		}

		@NonNull
		private List<@NonNull ScheduledTask> expire(long tick) {
			List<ScheduledTask> expiredTasks = new ArrayList<>();
			this.lock.lock();
			try {
				ScheduledTask scheduledTask = this.head;

				while (scheduledTask != null) {
					ScheduledTask next = scheduledTask.next;

					if (scheduledTask.isCancelled()) {
						unlink(scheduledTask);
					} else if (scheduledTask.deadlineTick <= tick) {
						unlink(scheduledTask);
						expiredTasks.add(scheduledTask);
					}

					scheduledTask = next;
				}
			} finally {
				this.lock.unlock();
			}

			return expiredTasks;
		}

		private void remove(@NonNull ScheduledTask scheduledTask) {
			requireNonNull(scheduledTask);
			this.lock.lock();
			try {
				if (scheduledTask.bucket == this)
					unlink(scheduledTask);
			} finally {
				this.lock.unlock();
			}
		}

		private void clear() {
			this.lock.lock();
			try {
				ScheduledTask scheduledTask = this.head;

				while (scheduledTask != null) {
					ScheduledTask next = scheduledTask.next;
					scheduledTask.bucket = null;
					scheduledTask.previous = null;
					scheduledTask.next = null;
					scheduledTask = next;
				}

				this.head = null;
			} finally {
				this.lock.unlock();
			}
		}

		private void unlink(@NonNull ScheduledTask scheduledTask) {
			requireNonNull(scheduledTask);

			if (scheduledTask.previous != null) {
				scheduledTask.previous.next = scheduledTask.next;
			} else {
				this.head = scheduledTask.next;
			}

			if (scheduledTask.next != null)
				scheduledTask.next.previous = scheduledTask.previous;

			scheduledTask.bucket = null;
			scheduledTask.previous = null;
			scheduledTask.next = null;
		}
	}
}
