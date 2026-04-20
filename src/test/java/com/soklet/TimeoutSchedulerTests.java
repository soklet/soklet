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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TimeoutSchedulerTests {
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
	public void cancelledTaskDoesNotRun() throws Exception {
		TimeoutScheduler scheduler = newScheduler();

		try {
			AtomicBoolean ran = new AtomicBoolean(false);
			TimeoutScheduler.ScheduledTask scheduledTask = scheduler.schedule(() -> ran.set(true), Duration.ofMillis(20));

			scheduledTask.cancel();
			Thread.sleep(100L);

			assertFalse(ran.get(), "Cancelled task should not run");
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
