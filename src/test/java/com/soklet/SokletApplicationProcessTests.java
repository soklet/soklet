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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
public final class SokletApplicationProcessTests {
	@Test
	public void hookAndEnterRegistrationPrecedeStartAndRemovalFollowsReport()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		RunnerCall call = startRunner(optionsWithTrigger(), environment(
				runtimeFactory, process, triggers, reporter));

		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		triggers.trigger();
		call.await();

		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.gracefulResult,
				call.result.get());
		assertBefore(events, "hook-add", "trigger-register");
		assertBefore(events, "trigger-register", "runtime-start");
		assertBefore(events, "terminal-report", "trigger-unregister");
		assertBefore(events, "trigger-unregister", "hook-remove");
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
		Assertions.assertEquals(1, reporter.invocations.get());
	}

	@Test
	public void precommitFactoryFailureCreatesNoHookCleanupOrReport() {
		List<String> events = new CopyOnWriteArrayList<>();
		RuntimeException failure = new IllegalArgumentException("identity conflict");
		SokletApplicationRuntimeFactory factory = (config, services, publisher) -> {
			throw failure;
		};
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		AtomicInteger cleanupCalls = new AtomicInteger();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1),
						result -> cleanupCalls.incrementAndGet()).build();

		RuntimeException thrown = Assertions.assertThrows(RuntimeException.class,
				() -> SokletApplication.run(config(), options,
						environment(factory, process, triggers, reporter)));

		Assertions.assertSame(failure, thrown);
		Assertions.assertTrue(events.isEmpty());
		Assertions.assertEquals(0, cleanupCalls.get());
		Assertions.assertEquals(0, reporter.invocations.get());
	}

	@Test
	public void shutdownAlreadyInProgressAtHookRegistrationReturnsNotStarted()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		process.addFailure = new IllegalStateException("shutdown underway");
		RecordingReporter reporter = new RecordingReporter(events);
		AtomicReference<ShutdownResult> cleanupResult =
				new AtomicReference<>();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1), cleanupResult::set)
				.build();

		InternalShutdownResult result = SokletApplication.run(config(), options,
				environment(runtimeFactory, process,
						new RecordingTriggerRegistry(events), reporter));

		Assertions.assertSame(runtimeFactory.runtime.notStartedResult, result);
		Assertions.assertSame(result,
				requireNonNull(cleanupResult.get()).internalResult());
		Assertions.assertEquals(0, runtimeFactory.runtime.startCalls.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
		Assertions.assertEquals(1, reporter.invocations.get());
		Assertions.assertFalse(events.contains("hook-remove"));
	}

	@Test
	public void hookOwnershipFailureRetainsExactCauseAndSecondaryCleanupFailure()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		SecurityException registrationFailure =
				new SecurityException("hooks forbidden");
		process.addFailure = registrationFailure;
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1), result -> {
					throw cleanupFailure;
				}).build();

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletApplication.run(config(),
						options, environment(runtimeFactory, process,
								new RecordingTriggerRegistry(events),
								new RecordingReporter(events))));

		Assertions.assertSame(registrationFailure, thrown.getCause());
		Assertions.assertSame(runtimeFactory.runtime.notStartedResult,
				thrown.getInternalShutdownResult());
		Assertions.assertEquals(1, thrown.getSuppressed().length);
		SokletApplicationCleanupException suppressed =
				Assertions.assertInstanceOf(
						SokletApplicationCleanupException.class,
						thrown.getSuppressed()[0]);
		Assertions.assertSame(cleanupFailure, suppressed.getCause());
		Assertions.assertEquals(ShutdownCleanupFailure.FAILED,
				suppressed.getCleanupFailure());
		Assertions.assertEquals(0, runtimeFactory.runtime.startCalls.get());
	}

	@Test
	public void hookConstructionFailureUsesThePostcommitOwnershipPath()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RecordingReporter reporter = new RecordingReporter(events);
		IllegalStateException hookFailure = new IllegalStateException(
				"hook construction failed");
		AtomicReference<ShutdownResult> cleanupResult =
				new AtomicReference<>();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1), cleanupResult::set)
				.build();

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletApplication.run(config(),
						options, environment(runtimeFactory, process,
								new RecordingTriggerRegistry(events), reporter,
								(name, task) -> { throw hookFailure; })));

		Assertions.assertSame(hookFailure, thrown.getCause());
		Assertions.assertSame(runtimeFactory.runtime.notStartedResult,
				thrown.getInternalShutdownResult());
		Assertions.assertSame(thrown.getInternalShutdownResult(),
				requireNonNull(cleanupResult.get()).internalResult());
		Assertions.assertFalse(events.contains("hook-add"));
		Assertions.assertEquals(0, runtimeFactory.runtime.startCalls.get());
		SokletApplicationTerminalSnapshot snapshot =
				requireNonNull(reporter.snapshot.get());
		Assertions.assertEquals(
				SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
				snapshot.primaryOutcome());
		Assertions.assertSame(hookFailure,
				snapshot.primaryFailure().orElseThrow());
	}

	@Test
	public void hookBeforeStartClaimNormalizesNotAttempted() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.WAIT_IN_START);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		process.blockAdd = true;
		RunnerCall call = startRunner(SokletApplicationOptions.fromDefaults(),
				environment(runtimeFactory, process,
						new RecordingTriggerRegistry(events),
						new RecordingReporter(events)));
		Assertions.assertTrue(process.addEntered.await(5, TimeUnit.SECONDS));
		Thread hook = process.startCapturedHook();
		Assertions.assertTrue(runtimeFactory.runtime.corePublished.await(5,
				TimeUnit.SECONDS));
		process.allowAddReturn.countDown();

		call.await();
		hook.join(5_000);
		Assertions.assertFalse(hook.isAlive());
		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.notStartedResult,
				call.result.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.startCalls.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
	}

	@Test
	public void hookDuringStartClaimNormalizesCancelled() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.WAIT_IN_START);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RunnerCall call = startRunner(SokletApplicationOptions.fromDefaults(),
				environment(runtimeFactory, process,
						new RecordingTriggerRegistry(events),
						new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		Thread hook = process.startCapturedHook();

		call.await();
		hook.join(5_000);
		Assertions.assertFalse(hook.isAlive());
		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.cancelledResult,
				call.result.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
	}

	@Test
	public void explicitShutdownDuringStartReturnsCompleteCancellation()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.WAIT_IN_START);
		RunnerCall call = startRunner(SokletApplicationOptions.fromDefaults(),
				environment(runtimeFactory, new RecordingProcessAccess(events),
						new RecordingTriggerRegistry(events),
						new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		runtimeFactory.runtime.shutdown();
		call.await();

		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.cancelledResult,
				call.result.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
	}

	@Test
	public void triggerCancellationWithIncompleteRollbackThrowsIncomplete()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.WAIT_IN_START);
		InternalShutdownResult incompleteCancellation = result(
				InternalStartupDisposition.CANCELLED,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
		runtimeFactory.runtime.shutdownResultOverride = incompleteCancellation;
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RunnerCall call = startRunner(optionsWithTrigger(), environment(
				runtimeFactory, new RecordingProcessAccess(events), triggers,
				new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		triggers.trigger();
		call.await();

		ShutdownIncompleteException thrown = Assertions.assertInstanceOf(
				ShutdownIncompleteException.class, call.failure.get());
		Assertions.assertSame(incompleteCancellation,
				thrown.getInternalShutdownResult());
		Assertions.assertNull(call.result.get());
	}

	@Test
	public void runnerAndHookShareOneCleanupAndReporter() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RecordingReporter reporter = new RecordingReporter(events);
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicReference<ShutdownResult> cleanupResult =
				new AtomicReference<>();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(2), result -> {
					cleanupResult.set(result);
					cleanupCalls.incrementAndGet();
				}).build();
		RunnerCall call = startRunner(options, environment(runtimeFactory,
				process, new RecordingTriggerRegistry(events), reporter));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		Thread hook = process.startCapturedHook();

		call.await();
		hook.join(5_000);
		Assertions.assertFalse(hook.isAlive());
		Assertions.assertNull(call.failure.get());
		Assertions.assertEquals(1, cleanupCalls.get());
		Assertions.assertSame(call.result.get(),
				requireNonNull(cleanupResult.get()).internalResult());
		Assertions.assertEquals(1, reporter.invocations.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
	}

	@Test
	public void hookRemovalWaitsForReporterAndConcurrentShutdownRemovalIsBenign()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		process.removeFailure = new IllegalStateException("shutdown underway");
		RecordingReporter reporter = new RecordingReporter(events);
		reporter.block = true;
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RunnerCall call = startRunner(optionsWithTrigger(), environment(
				runtimeFactory, process, triggers, reporter));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		triggers.trigger();
		Assertions.assertTrue(reporter.entered.await(5, TimeUnit.SECONDS));
		Assertions.assertFalse(events.contains("hook-remove"));
		reporter.release.countDown();

		call.await();
		Assertions.assertNull(call.failure.get());
		Assertions.assertTrue(events.contains("hook-remove"));
	}

	@Test
	public void runnerInterruptionRequestsShutdownAndIsRestoredOnReturn()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RunnerCall call = startRunner(SokletApplicationOptions.fromDefaults(),
				environment(runtimeFactory, process,
						new RecordingTriggerRegistry(events),
						new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		call.thread.interrupt();

		call.await();
		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.gracefulResult,
				call.result.get());
		Assertions.assertTrue(call.interruptedOnExit.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
	}

	@Test
	public void independentStartupFailureStaysPrimaryWithOneCleanupSuppression()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.FAIL_STARTUP);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RuntimeException cleanupFailure = new RuntimeException("cleanup");
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1), result -> {
					throw cleanupFailure;
				}).build();

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletApplication.run(config(),
						options, environment(runtimeFactory, process,
								new RecordingTriggerRegistry(events),
								new RecordingReporter(events))));

		Assertions.assertSame(runtimeFactory.runtime.startupFailure, thrown);
		Assertions.assertEquals(1, thrown.getSuppressed().length);
		SokletApplicationCleanupException suppressed =
				Assertions.assertInstanceOf(
						SokletApplicationCleanupException.class,
						thrown.getSuppressed()[0]);
		Assertions.assertSame(cleanupFailure, suppressed.getCause());
	}

	@Test
	public void noCleanupStartupFailureReportUsesCanonicalCoreEvidence()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.FAIL_STARTUP);
		RecordingReporter reporter = new RecordingReporter(events);

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletApplication.run(config(),
						SokletApplicationOptions.fromDefaults(), environment(
								runtimeFactory, new RecordingProcessAccess(events),
								new RecordingTriggerRegistry(events), reporter)));

		Assertions.assertSame(runtimeFactory.runtime.startupFailure, thrown);
		SokletApplicationTerminalSnapshot snapshot =
				requireNonNull(reporter.snapshot.get());
		Assertions.assertEquals(SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
				snapshot.primaryOutcome());
		Assertions.assertSame(thrown.getCause(),
				snapshot.primaryFailure().orElseThrow());
	}

	@Test
	public void incompleteResultSkipsConfiguredCleanupAndThrowsExactResult()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		runtimeFactory.runtime.shutdownResultOverride =
				runtimeFactory.runtime.incompleteResult;
		AtomicInteger cleanupCalls = new AtomicInteger();
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RunnerCall call = startRunner(SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1),
						result -> cleanupCalls.incrementAndGet()).build(),
				environment(runtimeFactory, new RecordingProcessAccess(events),
						triggers, new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		triggers.triggerDirectlyIfRegistered();
		if (runtimeFactory.runtime.corePublished.getCount() != 0)
			runtimeFactory.runtime.shutdown();

		call.await();
		ShutdownIncompleteException thrown = Assertions.assertInstanceOf(
				ShutdownIncompleteException.class, call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.incompleteResult,
				thrown.getInternalShutdownResult());
		Assertions.assertEquals(0, cleanupCalls.get());
	}

	@Test
	public void unexpectedTerminationRemainsPrimaryWhenShutdownIsIncomplete()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		InternalShutdownResult incomplete = result(InternalStartupDisposition.READY,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
		IllegalStateException transportFailure = new IllegalStateException(
				"transport terminated");
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		InternalTerminationEvent event = new InternalTerminationEvent(1L,
				InternalTerminationEvent.Type.FAILURE, group.root(), transportFailure);
		SokletTerminatedUnexpectedlyException unexpected =
				new SokletTerminatedUnexpectedlyException(event, incomplete,
						transportFailure);
		runtimeFactory.runtime.shutdownResultOverride = incomplete;
		runtimeFactory.runtime.terminalFailureOverride = unexpected;
		AtomicInteger cleanupCalls = new AtomicInteger();
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		RunnerCall call = startRunner(SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(1),
						result -> cleanupCalls.incrementAndGet()).build(),
				environment(runtimeFactory, new RecordingProcessAccess(events),
						triggers, reporter));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		triggers.trigger();
		call.await();

		Assertions.assertSame(unexpected, call.failure.get());
		Assertions.assertEquals(0, cleanupCalls.get());
		SokletApplicationTerminalSnapshot snapshot =
				requireNonNull(reporter.snapshot.get());
		Assertions.assertEquals(
				SokletApplicationPrimaryOutcome.UNEXPECTED_TERMINATION,
				snapshot.primaryOutcome());
		Assertions.assertSame(unexpected,
				snapshot.primaryFailure().orElseThrow());
		Assertions.assertEquals(
				InternalShutdownCleanupDisposition.SKIPPED_INCOMPLETE_SHUTDOWN,
				snapshot.cleanupOutcome().disposition());
	}

	@Test
	@Timeout(120)
	public void startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback() {
		for (InternalStartupDisposition startupDisposition : List.of(
				InternalStartupDisposition.FAILED,
				InternalStartupDisposition.TIMED_OUT)) {
			List<String> events = new CopyOnWriteArrayList<>();
			FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
					StartMode.FAIL_STARTUP);
			IllegalStateException startupCause = new IllegalStateException(
					"startup " + startupDisposition.name().toLowerCase());
			InternalShutdownResult incomplete = result(startupDisposition,
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					startupCause);
			SokletStartupException exactFailure = new SokletStartupException(
					startupDisposition, incomplete, startupCause);
			runtimeFactory.runtime.shutdownResultOverride = incomplete;
			runtimeFactory.runtime.terminalFailureOverride = exactFailure;
			AtomicInteger cleanupCalls = new AtomicInteger();
			LifecycleWorkers workers = new LifecycleWorkers();
			LifecycleRuntimeServices services = new LifecycleRuntimeServices(
					NanoClock.system(), workers);
			RecordingReporter reporter = new RecordingReporter(events);
			SokletApplicationOptions options = SokletApplicationOptions.builder()
					.afterCompleteShutdown(Duration.ofSeconds(1),
							result -> cleanupCalls.incrementAndGet()).build();

			SokletStartupException thrown = Assertions.assertThrows(
					SokletStartupException.class, () -> SokletApplication.run(config(),
							options, environment(services, runtimeFactory,
									new RecordingProcessAccess(events),
									new RecordingTriggerRegistry(events), reporter,
									(name, task) -> new Thread(task, name))));

			Assertions.assertSame(exactFailure, thrown);
			Assertions.assertSame(incomplete, thrown.getInternalShutdownResult());
			Assertions.assertEquals(0, thrown.getSuppressed().length);
			Assertions.assertEquals(0, cleanupCalls.get());
			Assertions.assertEquals(0, workers.created(
					LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
			SokletApplicationTerminalSnapshot snapshot =
					requireNonNull(reporter.snapshot.get());
			Assertions.assertEquals(SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
					snapshot.primaryOutcome());
			Assertions.assertSame(startupCause,
					snapshot.primaryFailure().orElseThrow());
			Assertions.assertEquals(
					InternalShutdownCleanupDisposition.SKIPPED_INCOMPLETE_SHUTDOWN,
					snapshot.cleanupOutcome().disposition());
		}
	}

	@Test
	public void expectedCompleteCleanupFailureIsTheCallerPrimary()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RuntimeException cleanupCause = new RuntimeException("cleanup failed");
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		RunnerCall call = startRunner(SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(1), result -> {
					throw cleanupCause;
				}).build(), environment(runtimeFactory,
				new RecordingProcessAccess(events), triggers, reporter));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		triggers.trigger();
		call.await();

		SokletApplicationCleanupException thrown = Assertions.assertInstanceOf(
				SokletApplicationCleanupException.class, call.failure.get());
		Assertions.assertSame(cleanupCause, thrown.getCause());
		Assertions.assertSame(runtimeFactory.runtime.gracefulResult,
				thrown.getInternalShutdownResult());
		Assertions.assertEquals(0, thrown.getSuppressed().length);
		Assertions.assertEquals(ShutdownCleanupFailure.FAILED,
				thrown.getCleanupFailure());
		Assertions.assertEquals(SokletApplicationPrimaryOutcome.EXPECTED,
				reporter.snapshot.get().primaryOutcome());
		Assertions.assertEquals(InternalShutdownCleanupDisposition.FAILED,
				reporter.snapshot.get().cleanupOutcome().disposition());
	}

	@Test
	public void unexpectedCompleteRemainsPrimaryWithOneCleanupSuppression()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		IllegalStateException transportFailure = new IllegalStateException(
				"transport terminated");
		InternalShutdownResult completeUnexpected = result(
				InternalStartupDisposition.READY,
				InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION,
				transportFailure);
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		InternalTerminationEvent event = new InternalTerminationEvent(2L,
				InternalTerminationEvent.Type.FAILURE, group.root(), transportFailure);
		SokletTerminatedUnexpectedlyException exactUnexpected =
				new SokletTerminatedUnexpectedlyException(event, completeUnexpected,
						transportFailure);
		runtimeFactory.runtime.shutdownResultOverride = completeUnexpected;
		runtimeFactory.runtime.terminalFailureOverride = exactUnexpected;
		RuntimeException cleanupCause = new RuntimeException("cleanup failed");
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		RunnerCall call = startRunner(SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(1), result -> {
					throw cleanupCause;
				}).build(), environment(runtimeFactory,
				new RecordingProcessAccess(events), triggers, reporter));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		triggers.trigger();
		call.await();

		Assertions.assertSame(exactUnexpected, call.failure.get());
		Assertions.assertEquals(1, exactUnexpected.getSuppressed().length);
		SokletApplicationCleanupException suppressed = Assertions.assertInstanceOf(
				SokletApplicationCleanupException.class,
				exactUnexpected.getSuppressed()[0]);
		Assertions.assertSame(cleanupCause, suppressed.getCause());
		Assertions.assertSame(completeUnexpected,
				suppressed.getInternalShutdownResult());
		Assertions.assertEquals(
				SokletApplicationPrimaryOutcome.UNEXPECTED_TERMINATION,
				reporter.snapshot.get().primaryOutcome());
		Assertions.assertEquals(InternalShutdownCleanupDisposition.FAILED,
				reporter.snapshot.get().cleanupOutcome().disposition());
	}

	@Test
	public void interruptedRunnerRestoresFlagWhenCleanupFails() throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RuntimeException cleanupCause = new RuntimeException("cleanup failed");
		RunnerCall call = startRunner(SokletApplicationOptions.builder()
				.afterCompleteShutdown(Duration.ofSeconds(1), result -> {
					throw cleanupCause;
				}).build(), environment(runtimeFactory,
				new RecordingProcessAccess(events),
				new RecordingTriggerRegistry(events),
				new RecordingReporter(events)));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));

		requireNonNull(call.thread).interrupt();
		call.await();

		SokletApplicationCleanupException thrown = Assertions.assertInstanceOf(
				SokletApplicationCleanupException.class, call.failure.get());
		Assertions.assertSame(cleanupCause, thrown.getCause());
		Assertions.assertSame(runtimeFactory.runtime.gracefulResult,
				thrown.getInternalShutdownResult());
		Assertions.assertTrue(call.interruptedOnExit.get());
	}

	@Test
	public void triggerRegistrationFailureStillFinalizesPostcommitOwner() {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		IllegalStateException registrationFailure = new IllegalStateException(
				"stdin listener unavailable");
		triggers.registerFailure = registrationFailure;
		RecordingReporter reporter = new RecordingReporter(events);
		AtomicReference<ShutdownResult> cleanupResult =
				new AtomicReference<>();
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(1), cleanupResult::set)
				.build();

		SokletStartupException thrown = Assertions.assertThrows(
				SokletStartupException.class, () -> SokletApplication.run(config(),
						options, environment(runtimeFactory,
								new RecordingProcessAccess(events), triggers, reporter)));

		Assertions.assertSame(registrationFailure, thrown.getCause());
		Assertions.assertSame(runtimeFactory.runtime.notStartedResult,
				thrown.getInternalShutdownResult());
		Assertions.assertSame(thrown.getInternalShutdownResult(),
				requireNonNull(cleanupResult.get()).internalResult());
		Assertions.assertEquals(0, runtimeFactory.runtime.startCalls.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
		Assertions.assertEquals(1, reporter.invocations.get());
		Assertions.assertEquals(
				SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
				reporter.snapshot.get().primaryOutcome());
	}

	@Test
	public void concurrentHookEnterInterruptionAndExplicitShutdownShareOneAttempt()
			throws Exception {
		List<String> events = new CopyOnWriteArrayList<>();
		FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory(events,
				StartMode.RETURN_READY);
		RecordingProcessAccess process = new RecordingProcessAccess(events);
		RecordingTriggerRegistry triggers = new RecordingTriggerRegistry(events);
		RecordingReporter reporter = new RecordingReporter(events);
		CountDownLatch race = new CountDownLatch(1);
		CountDownLatch cleanupEntered = new CountDownLatch(1);
		CountDownLatch releaseCleanup = new CountDownLatch(1);
		AtomicInteger cleanupCalls = new AtomicInteger();
		AtomicReference<Throwable> raceFailure = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers();
		LifecycleRuntimeServices services = new LifecycleRuntimeServices(
				NanoClock.system(), workers);
		SokletApplicationOptions options = SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(Duration.ofSeconds(5), result -> {
					cleanupCalls.incrementAndGet();
					cleanupEntered.countDown();
					awaitUninterruptibly(releaseCleanup);
				}).build();
		SokletApplicationHookFactory hookFactory = (name, task) -> new Thread(() -> {
			awaitUninterruptibly(race);
			task.run();
		}, name);
		RunnerCall call = startRunner(options, environment(services, runtimeFactory,
				process, triggers, reporter, hookFactory));
		Assertions.assertTrue(runtimeFactory.runtime.startEntered.await(5,
				TimeUnit.SECONDS));
		Thread hook = process.startCapturedHook();
		Thread enter = startDaemon("racing-enter-trigger", () -> {
			awaitUninterruptibly(race);
			try {
				triggers.trigger();
			} catch (Throwable failure) {
				raceFailure.compareAndSet(null, failure);
			}
		});
		Thread explicit = startDaemon("racing-explicit-shutdown", () -> {
			awaitUninterruptibly(race);
			runtimeFactory.runtime.shutdown();
		});
		Thread interruption = startDaemon("racing-runner-interruption", () -> {
			awaitUninterruptibly(race);
			requireNonNull(call.thread).interrupt();
		});

		race.countDown();
		Assertions.assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
		join(enter);
		join(explicit);
		join(interruption);
		releaseCleanup.countDown();
		call.await();
		hook.join(5_000L);

		Assertions.assertFalse(hook.isAlive());
		Assertions.assertNull(raceFailure.get());
		Assertions.assertNull(call.failure.get());
		Assertions.assertSame(runtimeFactory.runtime.gracefulResult,
				call.result.get());
		Assertions.assertTrue(call.interruptedOnExit.get());
		Assertions.assertEquals(1, runtimeFactory.runtime.publicationCount.get());
		Assertions.assertEquals(1, cleanupCalls.get());
		Assertions.assertEquals(1, reporter.invocations.get());
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.SHUTDOWN_CLEANUP));
		Assertions.assertEquals(1, workers.created(
				LifecycleWorkers.Role.TERMINAL_REPORTER));
	}

	@NonNull
	private static RunnerCall startRunner(@NonNull SokletApplicationOptions options,
			@NonNull SokletApplicationEnvironment environment) {
		RunnerCall call = new RunnerCall();
		call.thread = new Thread(() -> {
			try {
				call.result.set(SokletApplication.run(config(), options,
						environment));
			} catch (Throwable failure) {
				call.failure.set(failure);
			} finally {
				call.interruptedOnExit.set(Thread.currentThread().isInterrupted());
				call.done.countDown();
			}
		}, "soklet-application-runner-test");
		call.thread.start();
		return call;
	}

	@NonNull
	private static SokletApplicationOptions optionsWithTrigger() {
		return SokletApplicationOptions.builder()
				.additionalTrigger(ShutdownTrigger.ENTER_KEY).build();
	}

	@NonNull
	private static SokletApplicationEnvironment environment(
			@NonNull SokletApplicationRuntimeFactory runtimeFactory,
			@NonNull RecordingProcessAccess process,
			@NonNull RecordingTriggerRegistry triggers,
			@NonNull RecordingReporter reporter) {
		return environment(runtimeFactory, process, triggers, reporter,
				(name, task) -> new Thread(task, name));
	}

	@NonNull
	private static SokletApplicationEnvironment environment(
			@NonNull SokletApplicationRuntimeFactory runtimeFactory,
			@NonNull RecordingProcessAccess process,
			@NonNull RecordingTriggerRegistry triggers,
			@NonNull RecordingReporter reporter,
			@NonNull SokletApplicationHookFactory hookFactory) {
		return environment(LifecycleRuntimeServices.system(), runtimeFactory,
				process, triggers, reporter, hookFactory);
	}

	@NonNull
	private static SokletApplicationEnvironment environment(
			@NonNull LifecycleRuntimeServices services,
			@NonNull SokletApplicationRuntimeFactory runtimeFactory,
			@NonNull RecordingProcessAccess process,
			@NonNull RecordingTriggerRegistry triggers,
			@NonNull RecordingReporter reporter,
			@NonNull SokletApplicationHookFactory hookFactory) {
		return new SokletApplicationEnvironment(
				requireNonNull(services), process, triggers, reporter, runtimeFactory,
				hookFactory);
	}

	@NonNull
	private static SokletConfig config() {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build()).build();
	}

	private static void assertBefore(@NonNull List<String> events,
			@NonNull String first, @NonNull String second) {
		int firstIndex = requireNonNull(events).indexOf(requireNonNull(first));
		int secondIndex = events.indexOf(requireNonNull(second));
		Assertions.assertTrue(firstIndex >= 0,
				() -> "Missing event " + first + " in " + events);
		Assertions.assertTrue(secondIndex >= 0,
				() -> "Missing event " + second + " in " + events);
		Assertions.assertTrue(firstIndex < secondIndex,
				() -> first + " did not precede " + second + ": " + events);
	}

	@NonNull
	private static Thread startDaemon(@NonNull String name,
			@NonNull Runnable task) {
		Thread thread = new Thread(requireNonNull(task), requireNonNull(name));
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static void join(@NonNull Thread thread) throws InterruptedException {
		requireNonNull(thread).join(5_000L);
		Assertions.assertFalse(thread.isAlive(),
				() -> "Thread did not finish: " + thread.getName());
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				requireNonNull(latch).await();
				break;
			} catch (InterruptedException ignored) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private enum StartMode {
		RETURN_READY,
		WAIT_IN_START,
		FAIL_STARTUP
	}

	private static final class FakeRuntimeFactory
			implements SokletApplicationRuntimeFactory {
		@NonNull
		private final FakeRuntime runtime;

		private FakeRuntimeFactory(@NonNull List<String> events,
				@NonNull StartMode startMode) {
			this.runtime = new FakeRuntime(events, startMode);
		}

		@NonNull
		@Override
		public SokletApplicationRuntime create(@NonNull SokletConfig config,
				@NonNull LifecycleRuntimeServices services,
				@NonNull Consumer<InternalLifecycleCoreSnapshot> publisher) {
			this.runtime.services = services;
			this.runtime.publisher = publisher;
			return this.runtime;
		}
	}

	private static final class FakeRuntime implements SokletApplicationRuntime {
		@NonNull
		private final List<String> events;
		@NonNull
		private final StartMode startMode;
		@NonNull
		private final AtomicInteger startCalls;
		@NonNull
		private final AtomicInteger publicationCount;
		@NonNull
		private final AtomicReference<InternalLifecycleCoreSnapshot> core;
		@NonNull
		private final CountDownLatch startEntered;
		@NonNull
		private final CountDownLatch corePublished;
		@NonNull
		private final AtomicBoolean startReturned;
		@NonNull
		private final InternalShutdownResult notStartedResult;
		@NonNull
		private final InternalShutdownResult cancelledResult;
		@NonNull
		private final InternalShutdownResult gracefulResult;
		@NonNull
		private final InternalShutdownResult startupFailedResult;
		@NonNull
		private final InternalShutdownResult incompleteResult;
		@NonNull
		private final SokletStartupException startupFailure;
		@Nullable
		private volatile InternalShutdownResult shutdownResultOverride;
		@Nullable
		private volatile RuntimeException terminalFailureOverride;
		@Nullable
		private volatile LifecycleRuntimeServices services;
		@Nullable
		private volatile Consumer<InternalLifecycleCoreSnapshot> publisher;

		private FakeRuntime(@NonNull List<String> events,
				@NonNull StartMode startMode) {
			this.events = requireNonNull(events);
			this.startMode = requireNonNull(startMode);
			this.startCalls = new AtomicInteger();
			this.publicationCount = new AtomicInteger();
			this.core = new AtomicReference<>();
			this.startEntered = new CountDownLatch(1);
			this.corePublished = new CountDownLatch(1);
			this.startReturned = new AtomicBoolean();
			this.notStartedResult = result(InternalStartupDisposition.NOT_ATTEMPTED,
					InternalParticipantShutdownDisposition.NOT_STARTED);
			this.cancelledResult = result(InternalStartupDisposition.CANCELLED,
					InternalParticipantShutdownDisposition.NOT_STARTED);
			this.gracefulResult = result(InternalStartupDisposition.READY,
					InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION);
			IllegalStateException startupCause = new IllegalStateException(
					"startup failed");
			this.startupFailedResult = result(InternalStartupDisposition.FAILED,
					InternalParticipantShutdownDisposition.NOT_STARTED, startupCause);
			this.incompleteResult = result(InternalStartupDisposition.READY,
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
			this.startupFailure = new SokletStartupException(
					InternalStartupDisposition.FAILED, this.startupFailedResult,
					startupCause);
		}

		@Override
		public void start() {
			this.events.add("runtime-start");
			this.startCalls.incrementAndGet();
			if (this.startMode == StartMode.FAIL_STARTUP) {
				this.startEntered.countDown();
				InternalShutdownResult failedResult =
						this.shutdownResultOverride == null
								? this.startupFailedResult
								: this.shutdownResultOverride;
				publish(failedResult);
				if (this.terminalFailureOverride
						instanceof SokletStartupException overriddenFailure)
					throw overriddenFailure;
				throw this.startupFailure;
			}
			if (this.core.get() != null) {
				this.startEntered.countDown();
				throw startupException(this.core.get().result());
			}
			if (this.startMode == StartMode.WAIT_IN_START) {
				this.startEntered.countDown();
				boolean interrupted = false;
				while (this.core.get() == null) {
					try {
						this.corePublished.await();
					} catch (InterruptedException exception) {
						interrupted = true;
						shutdown();
					}
				}
				if (interrupted)
					Thread.currentThread().interrupt();
				throw startupException(this.core.get().result());
			}
			this.startReturned.set(true);
			this.startEntered.countDown();
		}

		@Override
		public void shutdown() {
			this.events.add("runtime-shutdown");
			InternalShutdownResult selected = this.shutdownResultOverride;
			if (selected == null) {
				if (this.startCalls.get() == 0)
					selected = this.notStartedResult;
				else if (!this.startReturned.get()
						&& this.startMode == StartMode.WAIT_IN_START)
					selected = this.cancelledResult;
				else
					selected = this.gracefulResult;
			}
			publish(selected);
		}

		private void publish(@NonNull InternalShutdownResult result) {
			LifecycleRuntimeServices exactServices = requireNonNull(this.services);
			InternalLifecycleCoreSnapshot snapshot =
					new InternalLifecycleCoreSnapshot(requireNonNull(result),
							exactServices.clock().nanoTime());
			if (!this.core.compareAndSet(null, snapshot))
				return;
			this.publicationCount.incrementAndGet();
			requireNonNull(this.publisher).accept(snapshot);
			this.corePublished.countDown();
		}

		@NonNull
		@Override
		public InternalLifecycleCoreSnapshot awaitCore()
				throws InterruptedException {
			this.corePublished.await();
			return requireNonNull(this.core.get());
		}

		@NonNull
		@Override
		public Optional<RuntimeException> terminalFailure(
				@NonNull InternalShutdownResult result) {
			if (this.terminalFailureOverride != null)
				return Optional.of(this.terminalFailureOverride);
			if (requireNonNull(result).startupDisposition()
					== InternalStartupDisposition.FAILED
					|| result.startupDisposition()
					== InternalStartupDisposition.TIMED_OUT)
				return Optional.of(this.startupFailure);
			if (!result.isComplete())
				return Optional.of(new ShutdownIncompleteException(result));
			return Optional.empty();
		}

		@NonNull
		@Override
		public SokletApplicationCoreDiagnostics diagnostics() {
			return new SokletApplicationCoreDiagnostics(
					new LifecycleTransitionSnapshot(0, 0, false, true, false,
							0, Optional.empty()),
					Map.of(InternalParticipantKind.HTTP,
							new SokletApplicationParticipantDiagnostics(
									InternalTerminationAuthority.FRAMEWORK_PROVEN,
									1, 0, 1, false)),
					InternalLifecyclePolicy.defaults(), 0L);
		}

		@NonNull
		private static SokletStartupException startupException(
				@NonNull InternalShutdownResult result) {
			return new SokletStartupException(result.startupDisposition(), result);
		}
	}

	private static final class RecordingProcessAccess
			implements LifecycleProcessAccess {
		@NonNull
		private final List<String> events;
		@NonNull
		private final CountDownLatch addEntered;
		@NonNull
		private final CountDownLatch allowAddReturn;
		@Nullable
		private volatile RuntimeException addFailure;
		@Nullable
		private volatile RuntimeException removeFailure;
		private volatile boolean blockAdd;
		@Nullable
		private volatile Thread hook;

		private RecordingProcessAccess(@NonNull List<String> events) {
			this.events = requireNonNull(events);
			this.addEntered = new CountDownLatch(1);
			this.allowAddReturn = new CountDownLatch(1);
		}

		@NonNull
		@Override
		public Optional<InputStream> standardInput() {
			return Optional.empty();
		}

		@Override
		public void addShutdownHook(@NonNull Thread hook) {
			this.events.add("hook-add");
			this.hook = requireNonNull(hook);
			this.addEntered.countDown();
			if (this.blockAdd) {
				boolean interrupted = false;
				for (;;) {
					try {
						this.allowAddReturn.await();
						break;
					} catch (InterruptedException exception) {
						interrupted = true;
					}
				}
				if (interrupted)
					Thread.currentThread().interrupt();
			}
			if (this.addFailure != null)
				throw this.addFailure;
		}

		@Override
		public boolean removeShutdownHook(@NonNull Thread hook) {
			Assertions.assertSame(this.hook, requireNonNull(hook));
			this.events.add("hook-remove");
			if (this.removeFailure != null)
				throw this.removeFailure;
			return true;
		}

		@Override
		public void reportConfigurationWarning(@NonNull String message) {
			this.events.add("warning:" + requireNonNull(message));
		}

		@NonNull
		Thread startCapturedHook() {
			Thread captured = requireNonNull(this.hook);
			captured.start();
			return captured;
		}
	}

	private static final class RecordingTriggerRegistry
			implements SokletApplicationTriggerRegistry {
		@NonNull
		private final List<String> events;
		@NonNull
		private final CountDownLatch registered;
		@Nullable
		private volatile Runnable shutdownIntent;
		@Nullable
		private volatile RuntimeException registerFailure;

		private RecordingTriggerRegistry(@NonNull List<String> events) {
			this.events = requireNonNull(events);
			this.registered = new CountDownLatch(1);
		}

		@NonNull
		@Override
		public SokletApplicationTriggerRegistration register(
				@NonNull Runnable shutdownIntent) {
			this.events.add("trigger-register");
			if (this.registerFailure != null)
				throw this.registerFailure;
			this.shutdownIntent = requireNonNull(shutdownIntent);
			this.registered.countDown();
			return () -> this.events.add("trigger-unregister");
		}

		void trigger() throws InterruptedException {
			Assertions.assertTrue(this.registered.await(5, TimeUnit.SECONDS));
			requireNonNull(this.shutdownIntent).run();
		}

		void triggerDirectlyIfRegistered() {
			Runnable intent = this.shutdownIntent;
			if (intent != null)
				intent.run();
		}
	}

	private static final class RecordingReporter
			implements LifecycleTerminalReporter {
		@NonNull
		private final List<String> events;
		@NonNull
		private final AtomicInteger invocations;
		@NonNull
		private final CountDownLatch entered;
		@NonNull
		private final CountDownLatch release;
		@NonNull
		private final AtomicReference<SokletApplicationTerminalSnapshot> snapshot;
		private volatile boolean block;

		private RecordingReporter(@NonNull List<String> events) {
			this.events = requireNonNull(events);
			this.invocations = new AtomicInteger();
			this.entered = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
			this.snapshot = new AtomicReference<>();
		}

		@Override
		public void report(@NonNull SokletApplicationTerminalSnapshot snapshot) {
			this.snapshot.compareAndSet(null, requireNonNull(snapshot));
			this.events.add("terminal-report");
			this.invocations.incrementAndGet();
			this.entered.countDown();
			if (!this.block)
				return;
			boolean interrupted = false;
			for (;;) {
				try {
					this.release.await();
					break;
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	private static final class RunnerCall {
		@NonNull
		private final AtomicReference<InternalShutdownResult> result =
				new AtomicReference<>();
		@NonNull
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		@NonNull
		private final AtomicBoolean interruptedOnExit = new AtomicBoolean();
		@NonNull
		private final CountDownLatch done = new CountDownLatch(1);
		@Nullable
		private Thread thread;

		void await() throws InterruptedException {
			Assertions.assertTrue(this.done.await(10, TimeUnit.SECONDS),
					"Runner did not finish");
		}
	}

	@NonNull
	private static InternalShutdownResult result(
			@NonNull InternalStartupDisposition startup,
			@NonNull InternalParticipantShutdownDisposition participant) {
		return result(startup, participant, new Throwable[0]);
	}

	@NonNull
	private static InternalShutdownResult result(
			@NonNull InternalStartupDisposition startup,
			@NonNull InternalParticipantShutdownDisposition participant,
			@NonNull Throwable... failures) {
		return new InternalShutdownResultAggregator().aggregate(startup,
				List.of(new InternalParticipantShutdownResult(
						InternalParticipantKind.HTTP, participant, List.of(failures),
						Set.of())));
	}
}
