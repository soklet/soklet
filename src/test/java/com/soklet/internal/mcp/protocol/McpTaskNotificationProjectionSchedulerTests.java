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

package com.soklet.internal.mcp.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class McpTaskNotificationProjectionSchedulerTests {
	@Test
	public void oneSubscriberOverflowCannotRejectAnUnrelatedSubscriber() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 2);
		Object noisySubscriber = new Object();
		Object quietSubscriber = new Object();
		AtomicInteger noisyRuns = new AtomicInteger();
		AtomicInteger noisyRejections = new AtomicInteger();
		AtomicInteger quietRuns = new AtomicInteger();
		AtomicInteger quietRejections = new AtomicInteger();

		for (int index = 0; index < 3; index++)
			scheduler.execute(job(noisySubscriber, noisyRuns, noisyRejections));
		Assertions.assertEquals(3, noisyRejections.get(),
				"Overflow must retire the subscriber that occupied the bounded queue.");
		Assertions.assertEquals(0, scheduler.queuedJobCount());

		scheduler.execute(job(quietSubscriber, quietRuns, quietRejections));
		Assertions.assertEquals(1, scheduler.queuedJobCount(),
				"The global queue must remain bounded while admitting the quiet peer.");
		executor.runNext();

		Assertions.assertEquals(0, noisyRuns.get());
		Assertions.assertEquals(1, quietRuns.get());
		Assertions.assertEquals(0, quietRejections.get(),
				"Another subscriber must not inherit the noisy subscriber's overflow.");
		Assertions.assertEquals(0, scheduler.queuedJobCount());
	}

	@Test
	public void lightSubscriberOverflowRetiresTheSubscriberMonopolizingTheQueue() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 4);
		Object heavySubscriber = new Object();
		Object lightSubscriber = new Object();
		AtomicInteger heavyRuns = new AtomicInteger();
		AtomicInteger heavyRejections = new AtomicInteger();
		AtomicInteger lightRuns = new AtomicInteger();
		AtomicInteger lightRejections = new AtomicInteger();

		for (int index = 0; index < 3; index++)
			scheduler.execute(job(heavySubscriber, heavyRuns, heavyRejections));
		scheduler.execute(job(lightSubscriber, lightRuns, lightRejections));
		scheduler.execute(job(lightSubscriber, lightRuns, lightRejections));

		Assertions.assertEquals(3, heavyRejections.get(),
				"Overflow must retire the owner monopolizing the bounded queue.");
		Assertions.assertEquals(0, lightRejections.get(),
				"A lighter incoming owner must not be selected over a heavier owner.");
		Assertions.assertEquals(2, scheduler.queuedJobCount());

		executor.runNext();
		executor.runNext();
		Assertions.assertEquals(0, heavyRuns.get());
		Assertions.assertEquals(2, lightRuns.get());
		Assertions.assertEquals(0, scheduler.queuedJobCount());
	}

	@Test
	public void transientExecutorRejectionRetainsBoundedProjectionWorkForRetry() {
		RejectFirstExecutor executor = new RejectFirstExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 2);
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();

		scheduler.execute(job(new Object(), runs, rejections));
		Assertions.assertEquals(1, scheduler.queuedJobCount());
		Assertions.assertEquals(0, rejections.get(),
				"A momentarily full executor must not fail queued subscribers.");

		scheduler.executorMayAcceptWorker();
		executor.runNext();
		Assertions.assertEquals(1, runs.get());
		Assertions.assertEquals(0, rejections.get());
		Assertions.assertEquals(0, scheduler.queuedJobCount());
	}

	private static McpHttpServerRuntime.TaskNotificationProjectionJob job(
			Object owner, AtomicInteger runs, AtomicInteger rejections) {
		return new McpHttpServerRuntime.TaskNotificationProjectionJob(
				owner, runs::incrementAndGet, rejections::incrementAndGet);
	}

	private static class CapturingExecutor implements Executor {
		private final Queue<Runnable> submissions = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			this.submissions.add(command);
		}

		void runNext() {
			Runnable command = this.submissions.poll();
			Assertions.assertNotNull(command, "No projection worker was submitted.");
			command.run();
		}
	}

	private static final class RejectFirstExecutor extends CapturingExecutor {
		private final AtomicBoolean rejected = new AtomicBoolean();

		@Override
		public void execute(Runnable command) {
			if (this.rejected.compareAndSet(false, true))
				throw new RejectedExecutionException("synthetic saturation");
			super.execute(command);
		}
	}
}
