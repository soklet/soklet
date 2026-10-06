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
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

public class McpTaskNotificationProjectionSchedulerTests {
	@Test
	public void activationRetainsOnlyAcceptedEventIdentitiesInEventOrder() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(3);
		queue.request("private");
		queue.request("beta");
		queue.request("alpha");
		queue.request("beta");
		Assertions.assertTrue(queue.activatePending(Set.of("alpha", "beta")));
		Assertions.assertEquals(2, queue.pendingTaskIdCount());
		McpHttpServerRuntime.TaskNotificationProjection beta = projection(queue);
		Assertions.assertEquals("beta", beta.taskId());
		Assertions.assertEquals(2, beta.generation());
		Assertions.assertTrue(queue.finish(beta, true));
		McpHttpServerRuntime.TaskNotificationProjection alpha = projection(queue);
		Assertions.assertEquals("alpha", alpha.taskId());
		Assertions.assertFalse(queue.finish(alpha, true));
	}

	@Test
	public void activationWithNoAcceptedPendingEventsDoesNotInventAnInitialSnapshot() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(1);
		queue.request("private");
		Assertions.assertFalse(queue.activatePending(Set.of("accepted")));
		Assertions.assertEquals(0, queue.pendingTaskIdCount());
		Assertions.assertFalse(queue.jobOutstanding());
		Assertions.assertTrue(queue.request("accepted"), "Discarded private identities must not retain the filter capacity.");
	}
	@Test
	public void admittedOwnerFanoutSurvivesBusyProjectionWorkers() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 8);
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();
		for (int index = 0; index < 8; index++) {
			Object owner = new Object();
			Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
			scheduler.execute(job(owner, runs, rejections));
		}
		Assertions.assertEquals(0, rejections.get(),
				"One fan-out must not disconnect previously admitted peers.");
		for (int index = 0; index < 8; index++)
			executor.runNext();
		Assertions.assertEquals(8, runs.get());
	}

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
	public void deferredSchedulerReservationPreservesTaskStateAndOrder() {
		McpHttpServerRuntime.TaskNotificationProjectionQueue queue =
				new McpHttpServerRuntime.TaskNotificationProjectionQueue(3);
		Assertions.assertTrue(queue.request("alpha"));
		Assertions.assertFalse(queue.request("beta"));

		queue.deferOutstandingJob();
		Assertions.assertFalse(queue.jobOutstanding());
		Assertions.assertTrue(queue.request("alpha"));
		McpHttpServerRuntime.TaskNotificationProjection alpha = projection(queue);
		Assertions.assertEquals("alpha", alpha.taskId());
		Assertions.assertEquals(2L, alpha.generation());
		Assertions.assertTrue(queue.finish(alpha, true));
		McpHttpServerRuntime.TaskNotificationProjection beta = projection(queue);
		Assertions.assertEquals("beta", beta.taskId());
		Assertions.assertFalse(queue.finish(beta, true));

		Assertions.assertThrows(IllegalStateException.class,
				queue::deferOutstandingJob);
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
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(rejectedQueue)));
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
	public void duplicateOwnerWorkCoalescesWithoutDisplacingPeers() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 2, 2);
		Object noisy = new Object();
		Object quiet = new Object();
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(noisy, quiet)));
		for (int index = 0; index < 20; index++) scheduler.execute(job(noisy, runs, rejections));
		scheduler.execute(job(quiet, runs, rejections));
		Assertions.assertEquals(2, scheduler.queuedJobCount());
		executor.runNext();
		executor.runNext();
		Assertions.assertEquals(2, runs.get());
		Assertions.assertEquals(0, rejections.get());
	}

	@Test
	public void ownerReservationIsAtomicAndRetirementReclaimsCapacity() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 2);
		Object first = new Object();
		Object second = new Object();
		Object third = new Object();
		AtomicInteger rejections = new AtomicInteger();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(first)));
		Assertions.assertFalse(scheduler.tryReserveOwners(List.of(second, third)));
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(second)));
		scheduler.execute(job(first, new AtomicInteger(), rejections));
		scheduler.releaseOwners(List.of(first));
		Assertions.assertEquals(0, scheduler.queuedJobCount());
		Assertions.assertEquals(1, rejections.get());
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(third)));
		scheduler.execute(job(first, new AtomicInteger(), rejections));
		Assertions.assertEquals(2, rejections.get());
		executor.runNext();
	}

	@Test
	public void runningOwnerContinuationCannotRunOnAnotherWorker() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 2, 2);
		Object owner = new Object();
		Object peer = new Object();
		List<String> order = new ArrayList<>();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner, peer)));
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(owner, () -> {
			order.add("owner-start");
			scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(owner,
					() -> order.add("owner-continuation"), Assertions::fail));
			executor.runNext();
			Assertions.assertEquals(List.of("owner-start", "peer"), order);
			order.add("owner-finish");
		}, Assertions::fail));
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(peer,
				() -> order.add("peer"), Assertions::fail));
		executor.runNext();
		executor.runNext();
		Assertions.assertEquals(List.of("owner-start", "peer", "owner-finish", "owner-continuation"), order);
	}

	@Test
	public void shutdownRejectsContinuationBehindRunningOwner() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 1);
		Object owner = new Object();
		AtomicInteger rejected = new AtomicInteger();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(owner, () -> {
			scheduler.execute(job(owner, new AtomicInteger(), rejected));
			scheduler.shutdown();
		}, Assertions::fail));
		executor.runNext();
		Assertions.assertEquals(1, rejected.get());
		Assertions.assertEquals(0, scheduler.queuedJobCount());
		Assertions.assertFalse(scheduler.tryReserveOwners(List.of(new Object())));
	}

	@Test
	public void retiredRunningOwnerCannotClearAReusedIdentity() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 1);
		Object owner = new Object();
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
		scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(owner, () -> {
			scheduler.releaseOwners(List.of(owner));
			Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
			scheduler.execute(job(owner, runs, rejected));
		}, Assertions::fail));
		executor.runNext();
		executor.runNext();
		Assertions.assertEquals(1, runs.get());
		Assertions.assertEquals(0, rejected.get());
	}

	@Test
	public void temporaryOwnerReleasesReservationAfterPhysicalJobExit() {
		CapturingExecutor executor = new CapturingExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 1);
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		scheduler.executeTemporary(job(new Object(), runs, rejected));
		scheduler.executeTemporary(job(new Object(), runs, rejected));
		Assertions.assertEquals(1, rejected.get());
		executor.runNext();
		scheduler.executeTemporary(job(new Object(), runs, rejected));
		executor.runNext();
		Assertions.assertEquals(2, runs.get());
	}

	@Test
	public void transientExecutorRejectionRetainsBoundedProjectionWorkForRetry() {
		RejectFirstExecutor executor = new RejectFirstExecutor();
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(
						executor, 1, 2);
		AtomicInteger runs = new AtomicInteger();
		AtomicInteger rejections = new AtomicInteger();

		Object owner = new Object();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
		scheduler.execute(job(owner, runs, rejections));
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
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(firstOwner, secondOwner)));

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
