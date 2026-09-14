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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

public class McpTaskNotificationProjectionSchedulerTests {
	@Test
	public void oneOwnerJobCoalescesQueuedAndRunningTaskGenerations() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(3);

		Assertions.assertTrue(queue.request("alpha"));
		Assertions.assertFalse(queue.request("alpha"),
				"A duplicate queued task must share the owner's scheduler job.");
		Assertions.assertEquals(1, queue.pendingTaskIdCount());
		McpHttpServerRuntime.TaskNotificationProjection first = projection(queue);
		Assertions.assertEquals("alpha", first.taskId());
		Assertions.assertEquals(2L, first.generation());

		Assertions.assertFalse(queue.request("alpha"),
				"An event racing an active lookup must not submit a second owner job.");
		Assertions.assertTrue(queue.finish(first, true),
				"The later generation must receive one follow-up projection.");
		McpHttpServerRuntime.TaskNotificationProjection followUp = projection(queue);
		Assertions.assertEquals("alpha", followUp.taskId());
		Assertions.assertEquals(3L, followUp.generation());
		Assertions.assertFalse(queue.finish(followUp, true));
		Assertions.assertFalse(queue.jobOutstanding());
		Assertions.assertEquals(0, queue.pendingTaskIdCount());
	}

	@Test
	public void uniqueTaskIdsRetainFirstEventOrderInOneOwnerSlot() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(3);

		Assertions.assertTrue(queue.request("alpha"));
		Assertions.assertFalse(queue.request("beta"));
		Assertions.assertFalse(queue.request("alpha"));
		Assertions.assertFalse(queue.request("gamma"));
		Assertions.assertEquals(3, queue.pendingTaskIdCount());

		McpHttpServerRuntime.TaskNotificationProjection alpha = projection(queue);
		Assertions.assertEquals("alpha", alpha.taskId());
		Assertions.assertEquals(2L, alpha.generation());
		Assertions.assertTrue(queue.finish(alpha, true));
		McpHttpServerRuntime.TaskNotificationProjection beta = projection(queue);
		Assertions.assertEquals("beta", beta.taskId());
		Assertions.assertTrue(queue.finish(beta, true));
		McpHttpServerRuntime.TaskNotificationProjection gamma = projection(queue);
		Assertions.assertEquals("gamma", gamma.taskId());
		Assertions.assertFalse(queue.finish(gamma, true));
	}

	@Test
	public void rejectionAndInactiveCompletionDrainOwnerState() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 2);
		McpHttpServerRuntime.TaskNotificationProjectionQueue rejectedQueue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(2);
		Assertions.assertTrue(rejectedQueue.request("alpha"));
		rejectedQueue.request("beta");
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(
				rejectedQueue, () -> Assertions.fail("Rejected work ran."),
				rejectedQueue::reset));

		scheduler.shutdown();
		Assertions.assertFalse(rejectedQueue.jobOutstanding());
		Assertions.assertEquals(0, rejectedQueue.pendingTaskIdCount(),
				"Scheduler rejection must not strand the owner as scheduled.");
		executor.runNext();

		McpHttpServerRuntime.TaskNotificationProjectionQueue canceledQueue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(2);
		Assertions.assertTrue(canceledQueue.request("alpha"));
		McpHttpServerRuntime.TaskNotificationProjection active =
				projection(canceledQueue);
		canceledQueue.request("beta");
		Assertions.assertFalse(canceledQueue.finish(active, false),
				"An inactive subscription must never re-enter the scheduler.");
		Assertions.assertFalse(canceledQueue.jobOutstanding());
		Assertions.assertEquals(0, canceledQueue.pendingTaskIdCount());
	}

	@Test
	public void resetReleasesTaskIdentitiesAndFencesOutstandingWorkers() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(2);
		Assertions.assertTrue(queue.request("old-alpha"));
		queue.request("old-beta");
		McpHttpServerRuntime.TaskNotificationProjection oldWorker =
				projection(queue);

		queue.reset();
		Assertions.assertFalse(queue.owns(oldWorker));
		Assertions.assertTrue(queue.request("new-alpha"),
				"Reset must release old task identities, not just pending work.");
		McpHttpServerRuntime.TaskNotificationProjection newWorker =
				projection(queue);
		Assertions.assertFalse(queue.finish(oldWorker, false));
		Assertions.assertTrue(queue.owns(newWorker),
				"A late old worker must not clear a newer owner's active state.");
		Assertions.assertFalse(queue.finish(newWorker, true));
	}

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

	@Test
	public void ownerContinuationReturnsToTailBehindAWaitingPeer() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 3);
		Object firstOwner = new Object();
		Object secondOwner = new Object();
		List<String> order = new ArrayList<>();

		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(
				firstOwner, () -> {
					order.add("first-1");
					scheduler.execute(new McpHttpServerRuntime
							.TaskNotificationProjectionJob(firstOwner,
							() -> order.add("first-2"), Assertions::fail));
				}, Assertions::fail));
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(
				secondOwner, () -> order.add("second"), Assertions::fail));

		executor.runNext();
		executor.runNext();
		executor.runNext();
		Assertions.assertEquals(List.of("first-1", "second", "first-2"), order,
				"An owner continuation must return behind an already-waiting peer.");
	}

	private static McpHttpServerRuntime.TaskNotificationProjection projection(
			McpHttpServerRuntime.TaskNotificationProjectionQueue queue) {
		return requireNonNull(queue.poll());
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
