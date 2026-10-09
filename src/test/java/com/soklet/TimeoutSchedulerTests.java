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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeoutSchedulerTests {
	@Test
	@SuppressWarnings("unchecked")
	public void pendingPublicationAfterItsTickExpiredRunsOnTheNextTick() throws Exception {
		TimeoutScheduler scheduler = new TimeoutScheduler(new DefaultHttpServer.NonvirtualThreadFactory("overdue-timeout-test"),
				Duration.ofMillis(10), 512);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), overdueRan = new CountDownLatch(1);
		try {
			scheduler.schedule(() -> {
				entered.countDown();
				try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}, Duration.ofMillis(1));
			assertTrue(entered.await(1, TimeUnit.SECONDS));
			// Reproduce a scheduling thread paused after calculating tick1, while
			// the worker has expired tick1 and is delivering its callback.
			Constructor<TimeoutScheduler.ScheduledTask> constructor = TimeoutScheduler.ScheduledTask.class
					.getDeclaredConstructor(Runnable.class, long.class);
			constructor.setAccessible(true);
			Field pending = TimeoutScheduler.class.getDeclaredField("pendingTasks");
			pending.setAccessible(true);
			((ConcurrentLinkedQueue<TimeoutScheduler.ScheduledTask>) pending.get(scheduler))
					.add(constructor.newInstance((Runnable) overdueRan::countDown, 1L));
			release.countDown();
			assertTrue(overdueRan.await(1, TimeUnit.SECONDS), "An overdue timeout must not wait the5.12s wheel rotation");
		} finally {
			release.countDown();
			scheduler.shutdownNow();
			assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	@Test
	public void scheduledTaskRunsAfterDelay() throws Exception {
		TimeoutScheduler scheduler = newScheduler();

		try {
			CountDownLatch latch = new CountDownLatch(1);

			scheduler.schedule(latch::countDown, Duration.ofMillis(20));

			assertTrue(latch.await(1L, TimeUnit.SECONDS), "Scheduled task should run");
		} finally {
			scheduler.shutdownNow();
		}
	}

	@Test
	public void canceledTaskDoesNotRun() throws Exception {
		TimeoutScheduler scheduler = newScheduler();

		try {
			AtomicBoolean ran = new AtomicBoolean(false);
			TimeoutScheduler.ScheduledTask scheduledTask = scheduler.schedule(() -> ran.set(true), Duration.ofMillis(20));

			scheduledTask.cancel();
			Thread.sleep(100L);

			assertFalse(ran.get(), "Canceled task should not run");
		} finally {
			scheduler.shutdownNow();
		}
	}

	@Test
	public void throwingTaskDoesNotStopScheduler() throws Exception {
		TimeoutScheduler scheduler = newScheduler();

		try {
			CountDownLatch laterTaskRan = new CountDownLatch(1);

			scheduler.schedule(() -> {
				throw new RuntimeException("boom");
			}, Duration.ofMillis(20));
			scheduler.schedule(laterTaskRan::countDown, Duration.ofMillis(40));

			assertTrue(laterTaskRan.await(1L, TimeUnit.SECONDS), "Later task should still run");
		} finally {
			scheduler.shutdownNow();
		}
	}

	@Test
	public void shutdownRejectsNewTasks() throws Exception {
		TimeoutScheduler scheduler = newScheduler();

		try {
			scheduler.shutdown();

			assertThrows(RejectedExecutionException.class,
					() -> scheduler.schedule(() -> {
					}, Duration.ofMillis(20)));
		} finally {
			scheduler.shutdownNow();
			scheduler.awaitTermination(1L, TimeUnit.SECONDS);
		}
	}

	private static TimeoutScheduler newScheduler() {
		return new TimeoutScheduler(new DefaultHttpServer.NonvirtualThreadFactory("timeout-scheduler-test"),
				Duration.ofMillis(5),
				16);
	}
}
