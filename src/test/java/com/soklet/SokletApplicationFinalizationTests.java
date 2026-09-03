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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SokletApplicationFinalizationTests {
	private static final String CLEANUP_WORKER_NAME =
			"soklet-application-cleanup";
	private static final String REPORTER_WORKER_NAME =
			"soklet-application-terminal-reporter";
	private static final Duration REPORTER_TIMEOUT = Duration.ofMillis(250);

	@Test
	void everyCompletePrimaryOutcomeRunsExactCleanupOutsidePublishingLock()
			throws Exception {
		Throwable startupFailure = new IllegalStateException("startup failed");
		Throwable unexpectedFailure = new IllegalStateException(
				"transport terminated");
		List<EligibleCase> cases = List.of(
				new EligibleCase(new InternalShutdownResult(
						InternalShutdownDisposition.NOT_STARTED,
						InternalStartupDisposition.NOT_ATTEMPTED, List.of()),
						SokletApplicationPrimaryOutcome.EXPECTED, null),
				new EligibleCase(aggregate(InternalStartupDisposition.READY,
						participant(InternalLifecycleComponentType.HTTP,
								InternalLifecycleComponentShutdownDisposition
										.GRACEFUL_TERMINATION)),
						SokletApplicationPrimaryOutcome.EXPECTED, null),
				new EligibleCase(aggregate(InternalStartupDisposition.READY,
						participant(InternalLifecycleComponentType.HTTP,
								InternalLifecycleComponentShutdownDisposition
										.FORCED_TERMINATION)),
						SokletApplicationPrimaryOutcome.EXPECTED, null),
				new EligibleCase(aggregate(InternalStartupDisposition.FAILED,
						participant(InternalLifecycleComponentType.FRAMEWORK,
								InternalLifecycleComponentShutdownDisposition
										.GRACEFUL_TERMINATION,
								startupFailure)),
						SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
						startupFailure),
				new EligibleCase(aggregate(InternalStartupDisposition.READY,
						participant(InternalLifecycleComponentType.HTTP,
								InternalLifecycleComponentShutdownDisposition
										.UNEXPECTED_TERMINATION,
								unexpectedFailure)),
						SokletApplicationPrimaryOutcome.UNEXPECTED_TERMINATION,
						unexpectedFailure));

		long publicationNanos = 100L;
		for (EligibleCase eligible : cases) {
			FakeClock clock = new FakeClock(publicationNanos++);
			QueuedLauncher launcher = new QueuedLauncher();
			AtomicInteger cleanupCalls = new AtomicInteger();
			AtomicReference<ShutdownResult> cleanupResult =
					new AtomicReference<>();
			AtomicReference<Thread> cleanupThread = new AtomicReference<>();
			AtomicBoolean publishingLockWasAvailable = new AtomicBoolean();
			AtomicReference<SokletApplicationTerminalSnapshot> reported =
					new AtomicReference<>();
			ReentrantLock publishingLock = new ReentrantLock();
			Thread publishingThread = Thread.currentThread();
			ShutdownCleanup.Action cleanupAction = result -> {
				cleanupCalls.incrementAndGet();
				cleanupResult.set(result);
				cleanupThread.set(Thread.currentThread());
				boolean acquired = publishingLock.tryLock();
				publishingLockWasAvailable.set(acquired);
				if (acquired)
					publishingLock.unlock();
			};
			Fixture fixture = fixture(clock, new LifecycleWorkers(launcher),
					cleanup(Duration.ofSeconds(5), cleanupAction), reported::set);

			publishingLock.lock();
			try {
				fixture.finalization().publishCoreSnapshot(new InternalLifecycleCoreSnapshot(
						eligible.result(), clock.nanoTime()));
				Assertions.assertEquals(0, cleanupCalls.get());
				Assertions.assertTrue(fixture.finalization().cleanupOutcome().isEmpty());
				Assertions.assertEquals(0, fixture.workers().created(
						LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
			} finally {
				publishingLock.unlock();
			}

			StartedTask<SokletApplicationFinalization.AwaitResult> waiter = start(
					"application-finalization-waiter",
					fixture.finalization()::awaitCompletion);
			LaunchedTask cleanupTask = launcher.take(CLEANUP_WORKER_NAME);
			StartedTask<Void> cleanupExecution = startRunnable(
					"application-cleanup-test-worker", cleanupTask.runnable());
			await(cleanupExecution);
			LaunchedTask reporterTask = launcher.take(REPORTER_WORKER_NAME);
			StartedTask<Void> reporterExecution = startRunnable(
					"application-reporter-test-worker", reporterTask.runnable());
			await(reporterExecution);
			SokletApplicationFinalization.AwaitResult joined = await(waiter);

			Assertions.assertEquals(InternalShutdownCleanupDisposition.SUCCEEDED,
					joined.cleanupOutcome().disposition());
			Assertions.assertEquals(1, cleanupCalls.get());
			Assertions.assertSame(eligible.result(),
					requireNonNull(cleanupResult.get()).internalResult());
			Assertions.assertTrue(publishingLockWasAvailable.get());
			Assertions.assertNotSame(publishingThread, cleanupThread.get());
			Assertions.assertNotSame(waiter.thread(), cleanupThread.get());
			SokletApplicationTerminalSnapshot terminal = reported.get();
			Assertions.assertNotNull(terminal);
			Assertions.assertSame(eligible.result(), terminal.coreSnapshot().result());
			Assertions.assertSame(terminal.coreSnapshot().publicResult(),
					cleanupResult.get());
			Assertions.assertSame(joined.cleanupOutcome(), terminal.cleanupOutcome());
			Assertions.assertEquals(eligible.primaryOutcome(),
					terminal.primaryOutcome());
			if (eligible.primaryFailure() == null)
				Assertions.assertTrue(terminal.primaryFailure().isEmpty());
			else
				Assertions.assertSame(eligible.primaryFailure(),
						terminal.primaryFailure().orElseThrow());
			Assertions.assertEquals(LifecycleDeadlines.after(
					joined.cleanupOutcome().publicationNanos(), REPORTER_TIMEOUT),
					terminal.reporterDeadlineNanos());
			Assertions.assertEquals(1, fixture.workers().created(
					LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
			Assertions.assertEquals(1, fixture.workers().created(
					LifecycleWorkers.Role.TERMINAL_REPORTER));
		}
	}

	@Test
	void notConfiguredAndIncompleteCleanupAreDistinctAndNeverLaunchCleanup()
			throws Exception {
		FakeClock notConfiguredClock = new FakeClock(200L);
		QueuedLauncher notConfiguredLauncher = new QueuedLauncher();
		AtomicReference<SokletApplicationTerminalSnapshot> notConfiguredReport =
				new AtomicReference<>();
		Fixture notConfigured = fixture(notConfiguredClock,
				new LifecycleWorkers(notConfiguredLauncher),
				null, notConfiguredReport::set);
		InternalShutdownResult complete = new InternalShutdownResult(
				InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.NOT_ATTEMPTED, List.of());
		notConfiguredClock.set(225L);
		notConfigured.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(complete, 200L));

		InternalShutdownCleanupOutcome notConfiguredOutcome = notConfigured
				.finalization().cleanupOutcome().orElseThrow();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				notConfiguredOutcome.disposition());
		Assertions.assertEquals(225L,
				notConfiguredOutcome.publicationNanos());
		Assertions.assertTrue(notConfiguredOutcome.configuredTimeout().isEmpty());
		Assertions.assertEquals(0, notConfigured.workers().created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, notConfigured.workers().created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		StartedTask<SokletApplicationFinalization.AwaitResult> notConfiguredWaiter =
				start("not-configured-waiter",
						notConfigured.finalization()::awaitCompletion);
		await(startRunnable("not-configured-reporter",
				notConfiguredLauncher.take(REPORTER_WORKER_NAME).runnable()));
		Assertions.assertSame(notConfiguredOutcome,
				await(notConfiguredWaiter).cleanupOutcome());
		Assertions.assertEquals(LifecycleDeadlines.after(225L, REPORTER_TIMEOUT),
				notConfiguredReport.get().reporterDeadlineNanos());

		FakeClock incompleteClock = new FakeClock(300L);
		QueuedLauncher incompleteLauncher = new QueuedLauncher();
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicReference<SokletApplicationTerminalSnapshot> incompleteReport =
				new AtomicReference<>();
		Duration configuredTimeout = Duration.ofSeconds(17);
		Fixture incomplete = fixture(incompleteClock,
				new LifecycleWorkers(incompleteLauncher),
				cleanup(configuredTimeout,
						result -> cleanupCalls.incrementAndGet()),
				incompleteReport::set);
		InternalShutdownResult incompleteResult = aggregate(
				InternalStartupDisposition.READY,
				new InternalLifecycleComponentShutdownResult(InternalLifecycleComponentType.HTTP,
						InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
						List.of(), Set.of(InternalResidualActivityType.CALLBACK)));
		incompleteClock.set(340L);
		incomplete.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(incompleteResult, 300L));

		InternalShutdownCleanupOutcome skipped = incomplete.finalization()
				.cleanupOutcome().orElseThrow();
		Assertions.assertEquals(
				InternalShutdownCleanupDisposition.SKIPPED_INCOMPLETE_SHUTDOWN,
				skipped.disposition());
		Assertions.assertEquals(Optional.of(configuredTimeout),
				skipped.configuredTimeout());
		Assertions.assertEquals(340L, skipped.publicationNanos());
		Assertions.assertEquals(0, cleanupCalls.get());
		Assertions.assertEquals(0, incomplete.workers().created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		StartedTask<SokletApplicationFinalization.AwaitResult> incompleteWaiter =
				start("incomplete-waiter",
						incomplete.finalization()::awaitCompletion);
		await(startRunnable("incomplete-reporter",
				incompleteLauncher.take(REPORTER_WORKER_NAME).runnable()));
		Assertions.assertSame(skipped,
				await(incompleteWaiter).cleanupOutcome());
		Assertions.assertEquals(0, cleanupCalls.get());
		Assertions.assertEquals(
				SokletApplicationPrimaryOutcome.INCOMPLETE_SHUTDOWN,
				incompleteReport.get().primaryOutcome());
		Assertions.assertEquals(LifecycleDeadlines.after(340L, REPORTER_TIMEOUT),
				incompleteReport.get().reporterDeadlineNanos(),
				"An ineligible cleanup budget must not precede the reporter budget");
	}

	@Test
	void publishedDeadlineConsumesSchedulingDelayAndAnotherWaiterCanTakeOver()
			throws Exception {
		FakeClock delayedClock = new FakeClock(400L);
		QueuedLauncher delayedLauncher = new QueuedLauncher();
		AtomicInteger delayedCalls = new AtomicInteger();
		AtomicReference<SokletApplicationTerminalSnapshot> delayedReport =
				new AtomicReference<>();
		Fixture delayed = fixture(delayedClock,
				new LifecycleWorkers(delayedLauncher),
				cleanup(Duration.ofNanos(10L),
						result -> delayedCalls.incrementAndGet()),
				delayedReport::set);
		delayed.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 400L));
		Assertions.assertTrue(delayed.finalization().cleanupOutcome().isEmpty());
		Assertions.assertEquals(0, delayed.workers().created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));

		delayedClock.set(410L);
		StartedTask<SokletApplicationFinalization.AwaitResult> delayedWaiter = start(
				"delayed-cleanup-waiter", delayed.finalization()::awaitCompletion);
		LaunchedTask delayedReporter = delayedLauncher.take(REPORTER_WORKER_NAME);
		Assertions.assertEquals(0, delayed.workers().created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		InternalShutdownCleanupOutcome delayedOutcome = delayed.finalization()
				.cleanupOutcome().orElseThrow();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.TIMED_OUT,
				delayedOutcome.disposition());
		Assertions.assertFalse(delayedOutcome.workerMayRemain());
		Assertions.assertEquals(410L, delayedOutcome.publicationNanos());
		await(startRunnable("delayed-reporter", delayedReporter.runnable()));
		Assertions.assertSame(delayedOutcome,
				await(delayedWaiter).cleanupOutcome());
		Assertions.assertEquals(0, delayedCalls.get());
		Assertions.assertEquals(LifecycleDeadlines.after(410L, REPORTER_TIMEOUT),
				delayedReport.get().reporterDeadlineNanos());

		FakeClock takeoverClock = new FakeClock(500L);
		AsyncLauncher delegate = new AsyncLauncher();
		CountDownLatch firstLaunchEntered = new CountDownLatch(1);
		CountDownLatch releaseFirstLaunch = new CountDownLatch(1);
		AtomicInteger takeoverCalls = new AtomicInteger();
		AtomicInteger deliveredInterrupts = new AtomicInteger();
		AtomicReference<ShutdownResult> takeoverResult =
				new AtomicReference<>();
		LifecycleWorkers takeoverWorkers = new LifecycleWorkers((name, task) -> {
			if (CLEANUP_WORKER_NAME.equals(name)) {
				firstLaunchEntered.countDown();
				awaitUninterruptibly(releaseFirstLaunch);
			}
			delegate.launch(name, task);
		});
		Fixture takeover = fixture(takeoverClock, takeoverWorkers,
				cleanup(Duration.ofNanos(10L), result -> {
					takeoverCalls.incrementAndGet();
					takeoverResult.set(result);
					if (Thread.interrupted())
						deliveredInterrupts.incrementAndGet();
				}), snapshot -> { });
		InternalShutdownResult exact = notStarted();
		takeover.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(exact, 500L));
		StartedTask<SokletApplicationFinalization.AwaitResult> runnerWaiter = start(
				"runner-finalization-waiter",
				takeover.finalization()::awaitCompletion);
		awaitLatch(firstLaunchEntered);
		CountDownLatch hookBegan = new CountDownLatch(1);
		StartedTask<SokletApplicationFinalization.AwaitResult> hookWaiter = start(
				"hook-finalization-waiter", () -> {
					hookBegan.countDown();
					return takeover.finalization().awaitCompletion();
				});
		awaitLatch(hookBegan);

		takeoverClock.set(510L);
		takeover.services().waiter().signal();
		SokletApplicationFinalization.AwaitResult hookJoined = await(hookWaiter);
		InternalShutdownCleanupOutcome takeoverOutcome =
				hookJoined.cleanupOutcome();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.TIMED_OUT,
				takeoverOutcome.disposition());
		Assertions.assertTrue(takeoverOutcome.workerMayRemain());
		Assertions.assertFalse(runnerWaiter.future().isDone(),
				"The deliberately suspended launcher still owns the first waiter");
		Assertions.assertEquals(0, takeoverCalls.get());
		Assertions.assertEquals(1, takeoverWorkers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(1, takeoverWorkers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));

		releaseFirstLaunch.countDown();
		delegate.awaitFinished(CLEANUP_WORKER_NAME);
		SokletApplicationFinalization.AwaitResult runnerJoined =
				await(runnerWaiter);
		Assertions.assertSame(takeoverOutcome, runnerJoined.cleanupOutcome());
		Assertions.assertEquals(1, takeoverCalls.get());
		Assertions.assertSame(exact,
				requireNonNull(takeoverResult.get()).internalResult());
		Assertions.assertEquals(1, deliveredInterrupts.get(),
				"A worker starting after timeout must receive the pending interrupt");
		Assertions.assertSame(takeoverOutcome,
				takeover.finalization().cleanupOutcome().orElseThrow());
	}

	@Test
	void waiterCanPublishImmediateOutcomeWhileFirstPublisherIsSuspended()
			throws Exception {
		BlockingSecondReadClock clock = new BlockingSecondReadClock(100L, 250L);
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		AtomicReference<SokletApplicationTerminalSnapshot> report =
				new AtomicReference<>();
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(
						null,
						new LifecycleRuntimeServices(clock, workers), report::set);
		finalization.diagnosticsSupplier(() -> new SokletApplicationCoreDiagnostics(
				new LifecycleTransitionSnapshot(0, 0, false, true, false,
						0, Optional.empty()),
				Map.of(), InternalLifecyclePolicy.defaults(), 100L));
		StartedTask<Void> publisher = startRunnable("suspended-core-publisher",
				() -> finalization.publishCoreSnapshot(
						new InternalLifecycleCoreSnapshot(notStarted(), 100L)));
		awaitLatch(clock.secondReadEntered);

		InternalShutdownCleanupOutcome outcome = finalization.awaitCompletion()
				.cleanupOutcome();

		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				outcome.disposition());
		Assertions.assertEquals(250L, outcome.publicationNanos());
		Assertions.assertEquals(LifecycleDeadlines.after(250L, REPORTER_TIMEOUT),
				report.get().reporterDeadlineNanos());
		Assertions.assertTrue(finalization.isComplete());
		clock.releaseSecondRead.countDown();
		await(publisher);
		Assertions.assertSame(outcome,
				finalization.cleanupOutcome().orElseThrow());
	}

	@Test
	void runnerAndHookWaitersShareOneSuccessfulCleanupAndReporter()
			throws Exception {
		FakeClock clock = new FakeClock(600L);
		AsyncLauncher launcher = new AsyncLauncher();
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		CountDownLatch reporterEntered = new CountDownLatch(1);
		CountDownLatch releaseReporter = new CountDownLatch(1);
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicInteger reporterCalls = new AtomicInteger();
		Fixture fixture = fixture(clock, new LifecycleWorkers(launcher),
				cleanup(Duration.ofSeconds(30), result -> {
					cleanupCalls.incrementAndGet();
					cleanupEntered.countDown();
					awaitUninterruptibly(releaseCleanup);
				}), snapshot -> {
				reporterCalls.incrementAndGet();
				reporterEntered.countDown();
				awaitUninterruptibly(releaseReporter);
			});
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 600L));
		CountDownLatch waitersReady = new CountDownLatch(2);
		CountDownLatch begin = new CountDownLatch(1);
		Callable<SokletApplicationFinalization.AwaitResult> join = () -> {
			waitersReady.countDown();
			begin.await();
			return fixture.finalization().awaitCompletion();
		};
		StartedTask<SokletApplicationFinalization.AwaitResult> runner = start(
				"ordinary-runner-waiter", join);
		StartedTask<SokletApplicationFinalization.AwaitResult> hook = start(
				"jvm-hook-waiter", join);
		awaitLatch(waitersReady);
		begin.countDown();

		awaitLatch(cleanupEntered);
		Assertions.assertEquals(1, cleanupCalls.get());
		releaseCleanup.countDown();
		awaitLatch(reporterEntered);
		Assertions.assertEquals(1, reporterCalls.get());
		releaseReporter.countDown();
		SokletApplicationFinalization.AwaitResult runnerJoined = await(runner);
		SokletApplicationFinalization.AwaitResult hookJoined = await(hook);

		Assertions.assertSame(runnerJoined.cleanupOutcome(),
				hookJoined.cleanupOutcome());
		Assertions.assertEquals(InternalShutdownCleanupDisposition.SUCCEEDED,
				runnerJoined.cleanupOutcome().disposition());
		Assertions.assertFalse(runnerJoined.interrupted());
		Assertions.assertFalse(hookJoined.interrupted());
		Assertions.assertEquals(1, fixture.workers().created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(1, fixture.workers().created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		launcher.awaitFinished(CLEANUP_WORKER_NAME);
		launcher.awaitFinished(REPORTER_WORKER_NAME);
	}

	@Test
	void blockedCleanupTimesOutAtBoundaryIsInterruptedOnceAndCannotRewrite()
			throws Exception {
		FakeClock clock = new FakeClock(700L);
		AsyncLauncher launcher = new AsyncLauncher();
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch firstInterrupt = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		AtomicInteger interrupts = new AtomicInteger();
		Exception lateFailure = new Exception("late cleanup failure");
		Fixture fixture = fixture(clock, new LifecycleWorkers(launcher),
				cleanup(Duration.ofNanos(20L), result -> {
					cleanupEntered.countDown();
					for (;;) {
						try {
							releaseCleanup.await();
							break;
						} catch (InterruptedException ignored) {
							interrupts.incrementAndGet();
							firstInterrupt.countDown();
						}
					}
					throw lateFailure;
				}), snapshot -> { });
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 700L));
		StartedTask<SokletApplicationFinalization.AwaitResult> waiter = start(
				"blocked-cleanup-waiter",
				fixture.finalization()::awaitCompletion);
		awaitLatch(cleanupEntered);

		clock.set(720L);
		fixture.services().waiter().signal();
		awaitLatch(firstInterrupt);
		SokletApplicationFinalization.AwaitResult joined = await(waiter);
		InternalShutdownCleanupOutcome timedOut = joined.cleanupOutcome();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.TIMED_OUT,
				timedOut.disposition());
		Assertions.assertEquals(720L, timedOut.publicationNanos());
		Assertions.assertTrue(timedOut.workerMayRemain());
		Assertions.assertInstanceOf(TimeoutException.class,
				timedOut.failure().orElseThrow());
		Assertions.assertTrue(timedOut.failure().orElseThrow().getMessage()
				.contains("may remain live"));
		Assertions.assertEquals(1, interrupts.get());

		releaseCleanup.countDown();
		launcher.awaitFinished(CLEANUP_WORKER_NAME);
		Assertions.assertEquals(1, interrupts.get());
		Assertions.assertSame(timedOut,
				fixture.finalization().cleanupOutcome().orElseThrow());
		Assertions.assertNotSame(lateFailure,
				fixture.finalization().cleanupOutcome().orElseThrow()
						.failure().orElseThrow());
		Assertions.assertEquals(0, fixture.workers().active(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
	}

	@Test
	void throwingCleanupInterruptCannotAbortReporterOrFinalization()
			throws Exception {
		FakeClock clock = new FakeClock(760L);
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		CountDownLatch cleanupFinished = new CountDownLatch(1);
		AtomicInteger interruptAttempts = new AtomicInteger();
		AtomicInteger reporterCalls = new AtomicInteger();
		SecurityException interruptFailure = new SecurityException(
				"interrupt denied");
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> {
			Runnable guarded = () -> {
				try {
					task.run();
				} finally {
					if (CLEANUP_WORKER_NAME.equals(name))
						cleanupFinished.countDown();
				}
			};
			Thread worker = CLEANUP_WORKER_NAME.equals(name)
					? new Thread(guarded, name) {
						@Override
						public void interrupt() {
							interruptAttempts.incrementAndGet();
							throw interruptFailure;
						}
					} : new Thread(guarded, name);
			worker.setDaemon(true);
			worker.start();
		});
		Fixture fixture = fixture(clock, workers,
				cleanup(Duration.ofNanos(20L), result -> {
					cleanupEntered.countDown();
					awaitUninterruptibly(releaseCleanup);
				}), snapshot -> reporterCalls.incrementAndGet());
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 760L));
		StartedTask<SokletApplicationFinalization.AwaitResult> waiter = start(
				"throwing-interrupt-finalization-waiter",
				fixture.finalization()::awaitCompletion);
		awaitLatch(cleanupEntered);

		clock.set(780L);
		fixture.services().waiter().signal();
		SokletApplicationFinalization.AwaitResult joined = await(waiter);

		Assertions.assertEquals(InternalShutdownCleanupDisposition.TIMED_OUT,
				joined.cleanupOutcome().disposition());
		Assertions.assertTrue(joined.cleanupOutcome().workerMayRemain());
		Assertions.assertEquals(1, interruptAttempts.get());
		Assertions.assertEquals(1, reporterCalls.get());
		Assertions.assertTrue(fixture.finalization().isComplete());
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));

		releaseCleanup.countDown();
		awaitLatch(cleanupFinished);
		Assertions.assertSame(joined.cleanupOutcome(),
				fixture.finalization().cleanupOutcome().orElseThrow());
		Assertions.assertEquals(1, interruptAttempts.get());
	}

	@Test
	void cleanupCompletionAtTheDeadlineCanWinTheBoundaryCas() {
		FakeClock clock = new FakeClock(800L);
		AtomicReference<SokletApplicationTerminalSnapshot> report =
				new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		Fixture fixture = fixture(clock, workers,
				cleanup(Duration.ofNanos(20L),
						result -> clock.set(820L)),
				report::set);
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 800L));

		SokletApplicationFinalization.AwaitResult joined =
				fixture.finalization().awaitCompletion();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.SUCCEEDED,
				joined.cleanupOutcome().disposition());
		Assertions.assertEquals(820L,
				joined.cleanupOutcome().publicationNanos());
		Assertions.assertTrue(joined.cleanupOutcome().failure().isEmpty());
		Assertions.assertEquals(LifecycleDeadlines.after(820L, REPORTER_TIMEOUT),
				report.get().reporterDeadlineNanos());
	}

	@Test
	void cleanupCompletionAfterTheDeadlineCannotPublishSuccess() {
		FakeClock clock = new FakeClock(850L);
		AtomicInteger cleanupCalls = new AtomicInteger();
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		Fixture fixture = fixture(clock, workers,
				cleanup(Duration.ofNanos(20L), result -> {
					cleanupCalls.incrementAndGet();
					clock.set(871L);
				}), snapshot -> { });
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 850L));

		InternalShutdownCleanupOutcome outcome = fixture.finalization()
				.awaitCompletion().cleanupOutcome();

		Assertions.assertEquals(1, cleanupCalls.get());
		Assertions.assertEquals(InternalShutdownCleanupDisposition.TIMED_OUT,
				outcome.disposition());
		Assertions.assertFalse(outcome.workerMayRemain(),
				"The synchronous action already returned before timeout publication");
		Assertions.assertInstanceOf(TimeoutException.class,
				outcome.failure().orElseThrow());
		SokletShutdownCleanupException callerFailure =
				SokletApplicationFinalization.cleanupException(notStarted(), outcome);
		Assertions.assertTrue(callerFailure.getMessage()
				.contains("daemon action may remain live"));
		Assertions.assertSame(outcome.failure().orElseThrow(),
				callerFailure.getCause());
	}

	@Test
	void cleanupLaunchAndActionFailuresRetainExactThrowableIdentity() {
		Error launchFailure = new AssertionError("cleanup launch failed");
		AtomicInteger unlaunchedActionCalls = new AtomicInteger();
		AtomicReference<SokletApplicationTerminalSnapshot> launchFailureReport =
				new AtomicReference<>();
		LifecycleWorkers launchFailureWorkers = new LifecycleWorkers((name, task) -> {
			if (CLEANUP_WORKER_NAME.equals(name))
				throw launchFailure;
			task.run();
		});
		Fixture launchFixture = fixture(new FakeClock(900L), launchFailureWorkers,
				cleanup(Duration.ofSeconds(1),
						result -> unlaunchedActionCalls.incrementAndGet()),
				launchFailureReport::set);
		launchFixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 900L));

		InternalShutdownCleanupOutcome failedLaunch = launchFixture.finalization()
				.awaitCompletion().cleanupOutcome();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.FAILED,
				failedLaunch.disposition());
		Assertions.assertSame(launchFailure,
				failedLaunch.failure().orElseThrow());
		Assertions.assertSame(launchFailure,
				launchFailureReport.get().cleanupOutcome().failure().orElseThrow());
		Assertions.assertEquals(0, unlaunchedActionCalls.get());
		Assertions.assertEquals(1, launchFailureWorkers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));

		Error actionFailure = new AssertionError("cleanup action failed");
		AtomicInteger actionCalls = new AtomicInteger();
		AtomicReference<SokletApplicationTerminalSnapshot> actionFailureReport =
				new AtomicReference<>();
		LifecycleWorkers actionFailureWorkers = new LifecycleWorkers(
				(name, task) -> task.run());
		Fixture actionFixture = fixture(new FakeClock(1_000L),
				actionFailureWorkers,
				cleanup(Duration.ofSeconds(1), result -> {
					actionCalls.incrementAndGet();
					throw actionFailure;
				}), actionFailureReport::set);
		actionFixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_000L));

		InternalShutdownCleanupOutcome failedAction = actionFixture.finalization()
				.awaitCompletion().cleanupOutcome();
		Assertions.assertEquals(InternalShutdownCleanupDisposition.FAILED,
				failedAction.disposition());
		Assertions.assertSame(actionFailure,
				failedAction.failure().orElseThrow());
		Assertions.assertSame(actionFailure,
				actionFailureReport.get().cleanupOutcome().failure().orElseThrow());
		Assertions.assertEquals(1, actionCalls.get());
		Assertions.assertEquals(1, actionFailureWorkers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
	}

	@Test
	void reporterHasIndependentDeadlineAndContainsStallThrowAndLaunchFailure()
			throws Exception {
		FakeClock stalledClock = new FakeClock(1_100L);
		QueuedLauncher stalledLauncher = new QueuedLauncher();
		CountDownLatch reporterEntered = new CountDownLatch(1);
		CountDownLatch releaseReporter = new CountDownLatch(1);
		AtomicInteger reporterInterrupts = new AtomicInteger();
		AtomicReference<SokletApplicationTerminalSnapshot> stalledSnapshot =
				new AtomicReference<>();
		Fixture stalled = fixture(stalledClock,
				new LifecycleWorkers(stalledLauncher),
				cleanup(Duration.ofNanos(100L),
						result -> stalledClock.set(1_150L)),
				snapshot -> {
					stalledSnapshot.set(snapshot);
					reporterEntered.countDown();
					for (;;) {
						try {
							releaseReporter.await();
							break;
						} catch (InterruptedException ignored) {
							reporterInterrupts.incrementAndGet();
						}
					}
				});
		stalled.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_100L));
		StartedTask<SokletApplicationFinalization.AwaitResult> stalledWaiter = start(
				"stalled-reporter-waiter",
				stalled.finalization()::awaitCompletion);
		await(startRunnable("successful-cleanup-worker",
				stalledLauncher.take(CLEANUP_WORKER_NAME).runnable()));
		StartedTask<Void> stalledReporter = startRunnable(
				"stalled-terminal-reporter",
				stalledLauncher.take(REPORTER_WORKER_NAME).runnable());
		awaitLatch(reporterEntered);
		InternalShutdownCleanupOutcome successfulCleanup = stalled.finalization()
				.cleanupOutcome().orElseThrow();
		Assertions.assertEquals(1_150L, successfulCleanup.publicationNanos());
		long reporterDeadline = LifecycleDeadlines.after(1_150L, REPORTER_TIMEOUT);
		Assertions.assertEquals(reporterDeadline,
				stalledSnapshot.get().reporterDeadlineNanos());

		stalledClock.set(reporterDeadline);
		stalled.services().waiter().signal();
		SokletApplicationFinalization.AwaitResult stalledJoined =
				await(stalledWaiter);
		Assertions.assertSame(successfulCleanup,
				stalledJoined.cleanupOutcome());
		Assertions.assertTrue(stalled.finalization().isComplete());
		Assertions.assertFalse(stalledReporter.future().isDone());
		Assertions.assertEquals(1, stalled.workers().active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertEquals(0, reporterInterrupts.get());
		releaseReporter.countDown();
		await(stalledReporter);
		Assertions.assertEquals(0, reporterInterrupts.get());
		Assertions.assertEquals(0, stalled.workers().active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));

		Error reporterFailure = new AssertionError("report rendering failed");
		AtomicInteger throwingReporterCalls = new AtomicInteger();
		LifecycleWorkers throwingWorkers = new LifecycleWorkers(
				(name, task) -> task.run());
		Fixture throwing = fixture(new FakeClock(1_200L), throwingWorkers,
				null, snapshot -> {
					throwingReporterCalls.incrementAndGet();
					throw reporterFailure;
				});
		throwing.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_200L));
		InternalShutdownCleanupOutcome beforeReporterThrow = throwing.finalization()
				.cleanupOutcome().orElseThrow();
		Assertions.assertSame(beforeReporterThrow,
				throwing.finalization().awaitCompletion().cleanupOutcome());
		Assertions.assertEquals(1, throwingReporterCalls.get());
		Assertions.assertTrue(throwing.finalization().isComplete());
		Assertions.assertTrue(beforeReporterThrow.failure().isEmpty());

		Error reporterLaunchFailure = new AssertionError(
				"reporter launch failed");
		AtomicInteger unlaunchedReporterCalls = new AtomicInteger();
		LifecycleWorkers reporterLaunchWorkers = new LifecycleWorkers((name, task) -> {
			if (REPORTER_WORKER_NAME.equals(name))
				throw reporterLaunchFailure;
			task.run();
		});
		Fixture failedLaunch = fixture(new FakeClock(1_300L),
				reporterLaunchWorkers, null,
				snapshot -> unlaunchedReporterCalls.incrementAndGet());
		failedLaunch.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_300L));
		InternalShutdownCleanupOutcome beforeLaunchFailure = failedLaunch
				.finalization().cleanupOutcome().orElseThrow();
		Assertions.assertSame(beforeLaunchFailure,
				failedLaunch.finalization().awaitCompletion().cleanupOutcome());
		Assertions.assertEquals(0, unlaunchedReporterCalls.get());
		Assertions.assertTrue(failedLaunch.finalization().isComplete());
		Assertions.assertTrue(beforeLaunchFailure.failure().isEmpty());
		Assertions.assertEquals(1, reporterLaunchWorkers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertEquals(0, reporterLaunchWorkers.active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
	}

	@Test
	void cleanupAndReporterWorkersAreLazyAndRoleBounded() throws Exception {
		FakeClock clock = new FakeClock(1_400L);
		QueuedLauncher launcher = new QueuedLauncher();
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicInteger reporterCalls = new AtomicInteger();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		Fixture fixture = fixture(clock, workers,
				cleanup(Duration.ofSeconds(5),
						result -> cleanupCalls.incrementAndGet()),
				snapshot -> reporterCalls.incrementAndGet());
		Assertions.assertEquals(0, workers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));

		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_400L));
		Assertions.assertEquals(0, workers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		StartedTask<SokletApplicationFinalization.AwaitResult> waiter = start(
				"lazy-worker-waiter", fixture.finalization()::awaitCompletion);

		LaunchedTask cleanup = launcher.take(CLEANUP_WORKER_NAME);
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(1, workers.active(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertThrows(IllegalStateException.class, () -> workers.start(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP,
				"second-cleanup-worker", () -> { }));
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		await(startRunnable("lazy-cleanup-worker", cleanup.runnable()));

		LaunchedTask reporter = launcher.take(REPORTER_WORKER_NAME);
		Assertions.assertEquals(0, workers.active(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertEquals(1, workers.active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertThrows(IllegalStateException.class, () -> workers.start(
				LifecycleWorkers.Role.TERMINAL_REPORTER,
				"second-reporter-worker", () -> { }));
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		await(startRunnable("lazy-reporter-worker", reporter.runnable()));

		Assertions.assertEquals(InternalShutdownCleanupDisposition.SUCCEEDED,
				await(waiter).cleanupOutcome().disposition());
		Assertions.assertEquals(1, cleanupCalls.get());
		Assertions.assertEquals(1, reporterCalls.get());
		Assertions.assertEquals(0, workers.active(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(0, workers.active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
	}

	@Test
	void interruptedWaiterCompletesAndRestoresItsInterruptFlag()
			throws Exception {
		FakeClock clock = new FakeClock(1_500L);
		QueuedLauncher launcher = new QueuedLauncher();
		Fixture fixture = fixture(clock, new LifecycleWorkers(launcher),
				null, snapshot -> { });
		fixture.finalization().publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_500L));
		StartedTask<InterruptionObservation> interruptedWaiter = start(
				"interrupted-finalization-waiter", () -> {
					Thread.currentThread().interrupt();
					SokletApplicationFinalization.AwaitResult joined =
							fixture.finalization().awaitCompletion();
					boolean restored = Thread.currentThread().isInterrupted();
					Thread.interrupted();
					return new InterruptionObservation(joined, restored);
				});

		LaunchedTask reporter = launcher.take(REPORTER_WORKER_NAME);
		Assertions.assertFalse(interruptedWaiter.future().isDone());
		await(startRunnable("interruption-test-reporter", reporter.runnable()));
		InterruptionObservation observed = await(interruptedWaiter);
		Assertions.assertTrue(observed.joined().interrupted());
		Assertions.assertTrue(observed.interruptFlagRestored());
		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				observed.joined().cleanupOutcome().disposition());
		Assertions.assertTrue(fixture.finalization().isComplete());
	}

	@Test
	void throwingDiagnosticSupplierCannotStrandReporterOrFinalization() {
		FakeClock clock = new FakeClock(1_600L);
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		AtomicReference<SokletApplicationTerminalSnapshot> reported =
				new AtomicReference<>();
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(
						null,
						new LifecycleRuntimeServices(clock, workers), reported::set);
		Error diagnosticFailure = new AssertionError("diagnostics unavailable");
		finalization.diagnosticsSupplier(() -> { throw diagnosticFailure; });

		Assertions.assertDoesNotThrow(() -> finalization.publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_600L)));
		InternalShutdownCleanupOutcome outcome =
				finalization.awaitCompletion().cleanupOutcome();

		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				outcome.disposition());
		Assertions.assertTrue(finalization.isComplete());
		Assertions.assertNotNull(reported.get());
		Assertions.assertTrue(reported.get().coreDiagnostics()
				.participantDiagnostics().isEmpty());
		Assertions.assertTrue(reported.get().coreDiagnostics()
				.transitionSnapshot().sealed());
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
	}

	@Test
	void blockingDiagnosticSupplierIsContainedInsideReporterDeadline()
			throws Exception {
		FakeClock clock = new FakeClock(1_700L);
		AsyncLauncher launcher = new AsyncLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(clock,
				workers);
		AtomicInteger reporterCalls = new AtomicInteger();
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(
						null, services,
						snapshot -> reporterCalls.incrementAndGet());
		CountDownLatch diagnosticsEntered = new CountDownLatch(1);
		CountDownLatch releaseDiagnostics = new CountDownLatch(1);
		finalization.diagnosticsSupplier(() -> {
			diagnosticsEntered.countDown();
			awaitUninterruptibly(releaseDiagnostics);
			return new SokletApplicationCoreDiagnostics(
					new LifecycleTransitionSnapshot(0, 0, false, true, false,
							0, Optional.empty()),
					Map.of(), InternalLifecyclePolicy.defaults(), 1_700L);
		});
		finalization.publishCoreSnapshot(
				new InternalLifecycleCoreSnapshot(notStarted(), 1_700L));
		StartedTask<SokletApplicationFinalization.AwaitResult> waiter = start(
				"blocking-diagnostics-waiter", finalization::awaitCompletion);
		awaitLatch(diagnosticsEntered);

		clock.set(LifecycleDeadlines.after(1_700L, REPORTER_TIMEOUT));
		services.waiter().signal();
		InternalShutdownCleanupOutcome outcome = await(waiter).cleanupOutcome();

		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				outcome.disposition());
		Assertions.assertTrue(finalization.isComplete());
		Assertions.assertEquals(0, reporterCalls.get());
		Assertions.assertEquals(1, workers.active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		releaseDiagnostics.countDown();
		launcher.awaitFinished(REPORTER_WORKER_NAME);
		Assertions.assertEquals(0, workers.active(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
		Assertions.assertEquals(1, reporterCalls.get(),
				"A late cooperative diagnostic return may finish the one attempt");
	}

	private static Fixture fixture(FakeClock clock, LifecycleWorkers workers,
			ShutdownCleanup cleanup,
			LifecycleTerminalReporter reporter) {
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(clock,
				workers);
		SokletApplicationFinalization finalization =
				new SokletApplicationFinalization(cleanup, services,
						reporter);
		finalization.diagnosticsSupplier(() -> new SokletApplicationCoreDiagnostics(
				new LifecycleTransitionSnapshot(0, 0, false, true, false,
						0, Optional.empty()),
				Map.of(), InternalLifecyclePolicy.defaults(), clock.nanoTime()));
		return new Fixture(workers, services, finalization);
	}

	private static ShutdownCleanup cleanup(Duration timeout,
			ShutdownCleanup.Action action) {
		return ShutdownCleanup.fromTimeoutAndAction(timeout, action);
	}

	private static InternalShutdownResult notStarted() {
		return new InternalShutdownResult(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.NOT_ATTEMPTED, List.of());
	}

	private static InternalShutdownResult aggregate(
			InternalStartupDisposition startupDisposition,
			InternalLifecycleComponentShutdownResult... participants) {
		return new InternalShutdownResultAggregator().aggregate(startupDisposition,
				List.of(participants));
	}

	private static InternalLifecycleComponentShutdownResult participant(
			InternalLifecycleComponentType kind,
			InternalLifecycleComponentShutdownDisposition disposition,
			Throwable... failures) {
		return new InternalLifecycleComponentShutdownResult(kind, disposition,
				List.of(failures), Set.of());
	}

	private static <T> StartedTask<T> start(String name, Callable<T> callable) {
		FutureTask<T> future = new FutureTask<>(callable);
		Thread thread = new Thread(future, name);
		thread.setDaemon(true);
		thread.start();
		return new StartedTask<>(future, thread);
	}

	private static StartedTask<Void> startRunnable(String name,
			Runnable runnable) {
		return start(name, () -> {
			runnable.run();
			return null;
		});
	}

	private static <T> T await(StartedTask<T> task) throws Exception {
		return task.future().get(5, TimeUnit.SECONDS);
	}

	private static void awaitLatch(CountDownLatch latch) throws Exception {
		Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private record EligibleCase(InternalShutdownResult result,
			SokletApplicationPrimaryOutcome primaryOutcome,
			Throwable primaryFailure) {
	}

	private record Fixture(LifecycleWorkers workers,
			LifecycleRuntimeServices services,
			SokletApplicationFinalization finalization) {
	}

	private record LaunchedTask(String name, Runnable runnable) {
	}

	private record StartedTask<T>(FutureTask<T> future, Thread thread) {
	}

	private record InterruptionObservation(
			SokletApplicationFinalization.AwaitResult joined,
			boolean interruptFlagRestored) {
	}

	private static final class FakeClock implements NanoClock {
		private final AtomicLong nanos;

		private FakeClock(long nanos) {
			this.nanos = new AtomicLong(nanos);
		}

		@Override
		public long nanoTime() {
			return this.nanos.get();
		}

		private void set(long nanos) {
			this.nanos.set(nanos);
		}
	}

	private static final class BlockingSecondReadClock implements NanoClock {
		private final long firstValue;
		private final long laterValue;
		private final AtomicInteger reads = new AtomicInteger();
		private final CountDownLatch secondReadEntered = new CountDownLatch(1);
		private final CountDownLatch releaseSecondRead = new CountDownLatch(1);

		private BlockingSecondReadClock(long firstValue, long laterValue) {
			this.firstValue = firstValue;
			this.laterValue = laterValue;
		}

		@Override
		public long nanoTime() {
			int read = this.reads.incrementAndGet();
			if (read == 1)
				return this.firstValue;
			if (read == 2) {
				this.secondReadEntered.countDown();
				awaitUninterruptibly(this.releaseSecondRead);
			}
			return this.laterValue;
		}
	}

	private static final class QueuedLauncher implements LifecycleWorkers.Launcher {
		private final BlockingQueue<LaunchedTask> tasks =
				new LinkedBlockingQueue<>();

		@Override
		public void launch(String name, Runnable runnable) {
			this.tasks.add(new LaunchedTask(name, runnable));
		}

		private LaunchedTask take(String expectedName) throws Exception {
			LaunchedTask task = this.tasks.poll(5, TimeUnit.SECONDS);
			if (task == null)
				throw new AssertionError("No lifecycle worker was launched");
			Assertions.assertEquals(expectedName, task.name());
			return task;
		}
	}

	private static final class AsyncLauncher implements LifecycleWorkers.Launcher {
		private final Map<String, CountDownLatch> started =
				new ConcurrentHashMap<>();
		private final Map<String, CountDownLatch> finished =
				new ConcurrentHashMap<>();

		@Override
		public void launch(String name, Runnable runnable) {
			CountDownLatch started = latch(this.started, name);
			CountDownLatch finished = latch(this.finished, name);
			Thread thread = new Thread(() -> {
				started.countDown();
				try {
					runnable.run();
				} finally {
					finished.countDown();
				}
			}, name);
			thread.setDaemon(true);
			thread.start();
		}

		private void awaitFinished(String name) throws Exception {
			Assertions.assertTrue(latch(this.finished, name)
					.await(5, TimeUnit.SECONDS));
		}

		private static CountDownLatch latch(
				Map<String, CountDownLatch> latches, String name) {
			return latches.computeIfAbsent(name, ignored -> new CountDownLatch(1));
		}
	}
}
