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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Ownership, launch-failure, and independence checks for reserved interrupt delivery. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class LifecycleInterruptDeliveryTests {
	@Test
	void ordinaryReturnRetiresUnusedDeliveryWithoutWaitingForItsDaemon() throws Exception {
		AtomicReference<Runnable> targetTask = new AtomicReference<>();
		AtomicReference<Runnable> deliveryTask = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, task) -> targetTask.set(task), (name, task) -> deliveryTask.set(task));
		InternalTerminationGroup group = group(workers);
		AtomicInteger actionCalls = new AtomicInteger();
		TrackedLifecycleCallRunner.Call<String> call = new TrackedLifecycleCallRunner(workers)
				.submit("ordinary-return", group, () -> {
					actionCalls.incrementAndGet();
					return "complete";
				});
		Assertions.assertEquals(2, group.trackedLifecycleCallCount(),
				"The action and reserved interruption each own one tracked ticket");
		try {
			targetTask.get().run();
			Assertions.assertEquals("complete", call.completion().toCompletableFuture()
					.get(5, TimeUnit.SECONDS));
			Assertions.assertEquals(1, actionCalls.get());
			Assertions.assertEquals(0, group.trackedLifecycleCallCount(),
					"An unused interrupt ticket must retire synchronously with its action");
			Assertions.assertEquals(1, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY),
					"The reserved daemon has deliberately not been allowed to run yet");
			call.cancel();
			Assertions.assertFalse(Thread.currentThread().isInterrupted(),
					"Canceling an already completed call must not interrupt its old target");
		} finally {
			// This queued daemon can now return immediately without touching its target.
			deliveryTask.get().run();
		}
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
	}

	@Test
	void reservationFailureDoesNotLaunchTheActionOrLeakTrackedOwnership() {
		AtomicInteger targetLaunches = new AtomicInteger();
		AtomicInteger actionCalls = new AtomicInteger();
		RuntimeException launchFailure = new SecurityException("test denies interrupt reservation");
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, task) -> targetLaunches.incrementAndGet(),
				(name, task) -> { throw launchFailure; });
		InternalTerminationGroup group = group(workers);
		Assertions.assertSame(launchFailure, Assertions.assertThrows(SecurityException.class,
				() -> new TrackedLifecycleCallRunner(workers).submit(
						"unreserved-action", group, actionCalls::incrementAndGet)));
		Assertions.assertEquals(0, targetLaunches.get());
		Assertions.assertEquals(0, actionCalls.get());
		Assertions.assertEquals(0, group.trackedLifecycleCallCount());
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
	}

	@Test
	void targetLaunchFailureRetiresTheReservedDaemonAndBothTrackedTickets() throws Exception {
		Map<String, Thread> deliveryThreads = new ConcurrentHashMap<>();
		AtomicInteger actionCalls = new AtomicInteger();
		RuntimeException launchFailure = new SecurityException("test denies action launch");
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, task) -> { throw launchFailure; },
				(name, task) -> launchDaemon(deliveryThreads, name, task));
		InternalTerminationGroup group = group(workers);
		try {
			Assertions.assertSame(launchFailure, Assertions.assertThrows(SecurityException.class,
					() -> new TrackedLifecycleCallRunner(workers).submit(
							"unlaunched-action", group, actionCalls::incrementAndGet)));
			Assertions.assertEquals(0, actionCalls.get());
			Assertions.assertEquals(0, group.trackedLifecycleCallCount());
			Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
		} finally {
			joinAll(deliveryThreads);
		}
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
	}

	@Test
	void cleanupReservationFailureIsFailedCleanupWithoutCallingTheAction() throws Exception {
		Map<String, Thread> normalThreads = new ConcurrentHashMap<>();
		AtomicInteger actionCalls = new AtomicInteger();
		RuntimeException launchFailure = new SecurityException("test denies cleanup interrupt reservation");
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, task) -> launchDaemon(normalThreads, name, task),
				(name, task) -> { throw launchFailure; });
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(NanoClock.system(), workers);
		SokletApplicationFinalization finalization = new SokletApplicationFinalization(
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(5),
						result -> actionCalls.incrementAndGet()),
				services, snapshot -> { });
		finalization.publishCoreSnapshot(new InternalLifecycleCoreSnapshot(
				new InternalShutdownResult(InternalShutdownDisposition.NOT_STARTED,
						InternalStartupDisposition.NOT_ATTEMPTED, List.of()), services.clock().nanoTime()));
		try {
			SokletApplicationFinalization.AwaitResult result = finalization.awaitCompletion();
			Assertions.assertEquals(InternalShutdownCleanupDisposition.FAILED,
					result.cleanupOutcome().disposition());
			Assertions.assertSame(launchFailure, result.cleanupOutcome().failure().orElseThrow());
			Assertions.assertFalse(result.cleanupOutcome().workerMayRemain());
			Assertions.assertEquals(0, actionCalls.get());
			Assertions.assertEquals(0, workers.created(LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
			Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
		} finally {
			joinAll(normalThreads);
		}
	}

	@Test
	void blockedDeliveryDoesNotStarveAnotherTargetAndRemainsTrackedUntilItReturns() throws Exception {
		Map<String, Thread> deliveryThreads = new ConcurrentHashMap<>();
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, task) -> Assertions.fail("Only interrupt workers are expected"),
				(name, task) -> launchDaemon(deliveryThreads, name, task));
		CountDownLatch releaseTargets = new CountDownLatch(1);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		CountDownLatch firstFinished = new CountDownLatch(1);
		CountDownLatch secondFinished = new CountDownLatch(1);
		AtomicInteger firstCalls = new AtomicInteger();
		AtomicInteger secondCalls = new AtomicInteger();
		Thread firstTarget = new Thread(() -> awaitUninterruptibly(releaseTargets), "first-interrupt-target") {
			@Override
			public void interrupt() {
				firstCalls.incrementAndGet();
				firstEntered.countDown();
				awaitUninterruptibly(releaseFirst);
			}
		};
		Thread secondTarget = new Thread(() -> awaitUninterruptibly(releaseTargets), "second-interrupt-target") {
			@Override
			public void interrupt() {
				secondCalls.incrementAndGet();
				secondEntered.countDown();
			}
		};
		firstTarget.setDaemon(true);
		secondTarget.setDaemon(true);
		firstTarget.start();
		secondTarget.start();
		LifecycleInterruptDelivery first = new LifecycleInterruptDelivery(
				workers, "blocked-interrupt-delivery", firstFinished::countDown);
		LifecycleInterruptDelivery second = new LifecycleInterruptDelivery(
				workers, "independent-interrupt-delivery", secondFinished::countDown);
		try {
			first.bind(firstTarget);
			second.bind(secondTarget);
			first.request();
			Assertions.assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
			first.request();
			first.retire();
			Assertions.assertEquals(1L, firstFinished.getCount(),
					"Retiring an in-flight interrupt cannot release its tracked ownership early");
			second.request();
			second.request();
			Assertions.assertTrue(secondEntered.await(5, TimeUnit.SECONDS),
					"One blocked close callback cannot serialize unrelated interruption");
			Assertions.assertTrue(secondFinished.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(1L, releaseFirst.getCount());
			Assertions.assertEquals(1L, firstFinished.getCount());
			Assertions.assertEquals(1, firstCalls.get());
			Assertions.assertEquals(1, secondCalls.get());
		} finally {
			releaseFirst.countDown();
			releaseTargets.countDown();
			first.retire();
			second.retire();
			joinAll(deliveryThreads);
			firstTarget.join(5_000);
			secondTarget.join(5_000);
			Assertions.assertFalse(firstTarget.isAlive());
			Assertions.assertFalse(secondTarget.isAlive());
		}
		Assertions.assertEquals(0L, firstFinished.getCount());
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
	}

	@Test
	void cancellationBeforeTargetBindingPublishesInterruptionBeforeTheAction() throws Exception {
		Map<String, Thread> deliveryThreads = new ConcurrentHashMap<>();
		AtomicReference<Runnable> targetTask = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> targetTask.set(task),
				(name, task) -> launchDaemon(deliveryThreads, name, task));
		InternalTerminationGroup group = group(workers);
		AtomicBoolean interruptedAtEntry = new AtomicBoolean();
		TrackedLifecycleCallRunner.Call<Boolean> call = new TrackedLifecycleCallRunner(workers)
				.submit("late-bound-action", group, () -> {
					interruptedAtEntry.set(Thread.currentThread().isInterrupted());
					return Thread.interrupted();
				});
		call.cancel();
		Thread target = new Thread(targetTask.get(), "late-bound-action-target");
		target.setDaemon(true);
		target.start();
		try {
			Assertions.assertTrue(call.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
			Assertions.assertTrue(interruptedAtEntry.get());
			Assertions.assertEquals(0, group.trackedLifecycleCallCount());
		} finally {
			target.join(5_000);
			Assertions.assertFalse(target.isAlive());
			joinAll(deliveryThreads);
		}
		Assertions.assertEquals(0, workers.active(LifecycleWorkers.Role.INTERRUPT_DELIVERY));
	}

	private static InternalTerminationGroup group(LifecycleWorkers workers) {
		return new InternalTerminationGroup(new AdmissionFence(false, () -> { }), () -> { }, workers);
	}

	private static void launchDaemon(Map<String, Thread> threads, String name, Runnable action) {
		Thread thread = new Thread(action, name);
		thread.setDaemon(true);
		threads.put(name, thread);
		thread.start();
	}

	private static void joinAll(Map<String, Thread> threads) throws InterruptedException {
		for (Thread thread : threads.values()) {
			thread.join(5_000);
			Assertions.assertFalse(thread.isAlive(), () -> thread.getName() + " did not retire");
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}
}
