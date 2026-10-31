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
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
	@Timeout(60)
	public void executorCompletionsRacingARejectedSubmissionCannotStrandReadyOwners() throws Exception {
		CountDownLatch activeEntered = new CountDownLatch(1), releaseActive = new CountDownLatch(1);
		CountDownLatch rejectionEntered = new CountDownLatch(1), releaseRejection = new CountDownLatch(1);
		CountDownLatch ordinaryWorkFinished = new CountDownLatch(2), projected = new CountDownLatch(1);
		AtomicReference<McpHttpServerRuntime.TaskNotificationProjectionScheduler> schedulerReference = new AtomicReference<>();
		AtomicInteger completions = new AtomicInteger(), runs = new AtomicInteger(), rejections = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(1), (task, pool) -> {
					rejectionEntered.countDown();
					await(releaseRejection);
					throw new RejectedExecutionException("synthetic saturation");
				}) {
			@Override protected void afterExecute(Runnable task, Throwable failure) {
				McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler = schedulerReference.get();
				if (scheduler != null) {
					completions.incrementAndGet();
					scheduler.executorMayAcceptWorker();
				}
				ordinaryWorkFinished.countDown();
			}
		};
		FutureTask<Void> submission = null;
		Thread submittingThread = null;
		try {
			executor.execute(() -> { activeEntered.countDown(); await(releaseActive); });
			Assertions.assertTrue(activeEntered.await(5, TimeUnit.SECONDS));
			executor.execute(() -> {});
			McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
					new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 1);
			schedulerReference.set(scheduler);
			Object owner = new Object();
			Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
			submission = new FutureTask<>(() -> {
				scheduler.execute(new McpHttpServerRuntime.TaskNotificationProjectionJob(owner,
						() -> { runs.incrementAndGet(); projected.countDown(); }, rejections::incrementAndGet));
				return null;
			});
			submittingThread = new Thread(submission);
			submittingThread.start();
			Assertions.assertTrue(rejectionEntered.await(5, TimeUnit.SECONDS));
			// Force both production-style afterExecute signals to arrive while
			// workersScheduled still includes the rejected submission.
			releaseActive.countDown();
			Assertions.assertTrue(ordinaryWorkFinished.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(2, completions.get());
			releaseRejection.countDown();
			submission.get(5, TimeUnit.SECONDS);
			Assertions.assertTrue(projected.await(5, TimeUnit.SECONDS),
					"No unrelated request or later publisher event should be needed to wake retained work");
			Assertions.assertEquals(1, runs.get());
			Assertions.assertEquals(0, rejections.get());
			Assertions.assertEquals(0, scheduler.queuedJobCount());
		} finally {
			releaseActive.countDown();
			releaseRejection.countDown();
			if (submittingThread != null) submittingThread.join(5000);
			executor.shutdownNow();
			Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	public void racedAvailabilityAllowsOneRetryButUnchangedSaturationDoesNotSpin() {
		AtomicReference<McpHttpServerRuntime.TaskNotificationProjectionScheduler> reference = new AtomicReference<>();
		AtomicInteger attempts = new AtomicInteger(), rejections = new AtomicInteger();
		Executor executor = task -> {
			int attempt = attempts.incrementAndGet();
			Assertions.assertTrue(attempt <= 2, "An unchanged full executor must not be retried repeatedly");
			if (attempt == 1) reference.get().executorMayAcceptWorker();
			throw new RejectedExecutionException("still saturated");
		};
		McpHttpServerRuntime.TaskNotificationProjectionScheduler scheduler =
				new McpHttpServerRuntime.TaskNotificationProjectionScheduler(executor, 1, 1);
		reference.set(scheduler);
		Object owner = new Object();
		Assertions.assertTrue(scheduler.tryReserveOwners(List.of(owner)));
		scheduler.execute(job(owner, new AtomicInteger(), rejections));
		Assertions.assertEquals(2, attempts.get());
		Assertions.assertEquals(1, scheduler.queuedJobCount());
		Assertions.assertEquals(0, rejections.get());
		scheduler.shutdown();
		Assertions.assertEquals(1, rejections.get());
	}

	private static void await(CountDownLatch latch) {
		try {
			Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "The scheduler test barrier timed out");
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
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
