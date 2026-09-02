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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BuiltInTransportLifecycleAdapterTests {
	@Test
	void admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt() {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation readyGeneration = adapter.beginStart();

		Assertions.assertTrue(adapter.tryAdmit(readyGeneration).isEmpty());
		Assertions.assertFalse(adapter.admissionOpen(readyGeneration));
		adapter.markReady(readyGeneration);
		AdmissionFence.Admission admission = adapter.tryAdmit(readyGeneration).orElseThrow();
		admission.close();
		adapter.stop();
		Assertions.assertTrue(adapter.tryAdmit(readyGeneration).isEmpty());

		BuiltInTransportLifecycleAdapter.Generation stoppedGeneration = adapter.beginStart();
		Assertions.assertTrue(adapter.tryAdmit(stoppedGeneration).isEmpty());
		adapter.stop();
		IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
				() -> adapter.markReady(stoppedGeneration));
		Assertions.assertEquals("Built-in transport shutdown began before readiness",
				failure.getMessage());
		Assertions.assertTrue(adapter.tryAdmit(stoppedGeneration).isEmpty());
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				adapter.result().orElseThrow().startupDisposition());
	}

	@Test
	void promptGracefulProofReleasesEvidenceAndPreservesStableIdentity() {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		InternalTransportIdentity identity = adapter.identity();
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.stop();

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
				participant.disposition());
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(0, operations.forceCount.get());
		Assertions.assertEquals(1, operations.releaseCount.get());
		Assertions.assertSame(identity, adapter.identity());
		Assertions.assertTrue(adapter.retentionSummary().isEmpty());
	}

	@Test
	void proofOnlyAfterForceClassifiesForcedAndUsesOneDeadlinePerPhase() {
		RecordingOperations operations = new RecordingOperations(
				attempt -> attempt == 2, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.stop();

		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION,
				participant(adapter).disposition());
		Assertions.assertEquals(List.of(0L, 0L), operations.observedDeadlines);
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(1, operations.forceCount.get());
		Assertions.assertEquals(1, operations.releaseCount.get());
	}

	@Test
	void positiveResidualAndUnknownBothRetainEvidenceWithoutRelease() {
		RecordingOperations residualOperations = new RecordingOperations(
				attempt -> false, Set.of(InternalResidualActivityType.EVENT_LOOP));
		BuiltInTransportLifecycleAdapter residualAdapter = adapter(residualOperations);
		BuiltInTransportLifecycleAdapter.Generation residualGeneration =
				residualAdapter.beginStart();
		residualAdapter.markReady(residualGeneration);
		residualAdapter.stop();

		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
				participant(residualAdapter).disposition());
		Assertions.assertEquals(Set.of(InternalResidualActivityType.EVENT_LOOP),
				participant(residualAdapter).residualActivity());
		Assertions.assertEquals(0, residualOperations.releaseCount.get());
		Assertions.assertEquals(1, residualAdapter.retentionSummary().orElseThrow()
				.counts().get(InternalResidualActivityType.EVENT_LOOP));
		Assertions.assertThrows(IllegalStateException.class,
				residualAdapter::beginStart);

		RecordingOperations unknownOperations = new RecordingOperations(
				attempt -> false, Set.of());
		BuiltInTransportLifecycleAdapter unknownAdapter = adapter(unknownOperations);
		BuiltInTransportLifecycleAdapter.Generation unknownGeneration =
				unknownAdapter.beginStart();
		unknownAdapter.markReady(unknownGeneration);
		unknownAdapter.stop();

		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				participant(unknownAdapter).disposition());
		Assertions.assertTrue(unknownAdapter.retentionSummary().orElseThrow()
				.counts().isEmpty());
		Assertions.assertEquals(0, unknownOperations.releaseCount.get());
	}

	@Test
	void admittedWorkPreventsPrematureProofAndFreezesCallbackResidual() {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);
		AdmissionFence.Admission admission = adapter.tryAdmit(generation).orElseThrow();

		adapter.stop();

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
				participant.disposition());
		Assertions.assertEquals(Set.of(InternalResidualActivityType.CALLBACK),
				participant.residualActivity());
		Assertions.assertEquals(1, adapter.retentionSummary().orElseThrow()
				.counts().get(InternalResidualActivityType.CALLBACK));
		admission.close();
		Assertions.assertEquals(0, operations.releaseCount.get(),
				"Late work completion cannot rewrite a frozen incomplete result");
	}

	@Test
	void unexpectedFailureIsRecordedOnceBeforeCoordinatorQuiesceAndThenProven() {
		AtomicReference<BuiltInTransportLifecycleAdapter> adapterRef =
				new AtomicReference<>();
		AtomicReference<BuiltInTransportLifecycleAdapter.Generation> generationRef =
				new AtomicReference<>();
		Throwable failure = new AssertionError("event loop failed");
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		operations.onQuiesce = () -> {
			List<InternalTerminationEvent> events = adapterRef.get()
					.terminationEvents(generationRef.get());
			Assertions.assertEquals(1, events.size());
			Assertions.assertSame(failure, events.get(0).cause().orElseThrow());
		};
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		adapterRef.set(adapter);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		generationRef.set(generation);
		adapter.markReady(generation);

		adapter.signalUnexpectedFailure(generation, failure);
		adapter.signalUnexpectedFailure(generation, new AssertionError("duplicate"));

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
				participant.disposition());
		Assertions.assertEquals(List.of(failure), participant.failures());
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(0, operations.forceCount.get());
	}

	@Test
	void failureRacingNormalShutdownIsRetainedWithoutReclassifyingRequestedProof()
			throws Exception {
		CountDownLatch proofEntered = new CountDownLatch(1);
		CountDownLatch releaseProof = new CountDownLatch(1);
		Throwable failure = new AssertionError("failure racing stop");
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		operations.onAwait = () -> {
			proofEntered.countDown();
			awaitUninterruptibly(releaseProof);
		};
		BuiltInTransportLifecycleAdapter adapter = adapter(operations,
				Duration.ofSeconds(2), Duration.ZERO);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		Thread stopThread = new Thread(adapter::stop, "adapter-normal-stop-race");
		stopThread.start();
		Assertions.assertTrue(proofEntered.await(1, TimeUnit.SECONDS));
		adapter.signalUnexpectedFailure(generation, failure);
		releaseProof.countDown();
		stopThread.join(2_000L);

		Assertions.assertFalse(stopThread.isAlive());
		Assertions.assertEquals(List.of(
				InternalTerminationEvent.Type.FAILURE,
				InternalTerminationEvent.Type.PROOF),
				adapter.terminationEvents(generation).stream()
						.map(InternalTerminationEvent::type).toList());
		Assertions.assertEquals(List.of(failure), participant(adapter).failures());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
				participant(adapter).disposition());
	}

	@Test
	void requestedProofThenLateFailureBeforeFreezePreservesBothInSequence()
			throws Exception {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations,
				Duration.ofSeconds(2), Duration.ZERO);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);
		AdmissionFence.Admission admission = adapter.tryAdmit(generation).orElseThrow();
		Thread stopThread = new Thread(adapter::stop, "adapter-proof-late-failure");

		stopThread.start();
		awaitEventCount(adapter, generation, 1);
		Assertions.assertEquals(InternalTerminationEvent.Type.PROOF,
				adapter.terminationEvents(generation).get(0).type());
		Throwable lateFailure = new AssertionError("late failure before freeze");
		adapter.signalUnexpectedFailure(generation, lateFailure);
		admission.close();
		stopThread.join(2_000L);

		Assertions.assertFalse(stopThread.isAlive());
		Assertions.assertEquals(List.of(
				InternalTerminationEvent.Type.PROOF,
				InternalTerminationEvent.Type.FAILURE),
				adapter.terminationEvents(generation).stream()
						.map(InternalTerminationEvent::type).toList());
		Assertions.assertEquals(List.of(lateFailure), participant(adapter).failures());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
				participant(adapter).disposition());
		Assertions.assertEquals(0, operations.forceCount.get());
	}

	@Test
	void failureBeforeReadinessFreezesFailedStartupWithCompleteProof() {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		Throwable failure = new IllegalStateException("failed before ready");

		adapter.signalUnexpectedFailure(generation, failure);

		InternalShutdownResult result = adapter.result().orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				result.startupDisposition());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
				participant(adapter).disposition());
		Assertions.assertThrows(IllegalStateException.class,
				() -> adapter.markReady(generation));
	}

	@Test
	void synchronousStartFailureUsesTheSameCoordinatorAndRetainsItsCause() {
		RecordingOperations operations = new RecordingOperations(attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		Throwable failure = new IllegalArgumentException("bind failed");

		adapter.failedStart(generation, failure, false);

		InternalShutdownResult result = adapter.result().orElseThrow();
		Assertions.assertEquals(InternalStartupDisposition.FAILED,
				result.startupDisposition());
		Assertions.assertEquals(List.of(failure), participant(adapter).failures());
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(1, operations.releaseCount.get());
	}

	@Test
	void operationsFailureIsEvidenceButNeverTerminationProof() {
		RuntimeException failure = new IllegalStateException("quiesce failed");
		RecordingOperations operations = new RecordingOperations(attempt -> false, Set.of());
		operations.quiesceFailure = failure;
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.stop();

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertEquals(List.of(failure), participant.failures());
		Assertions.assertTrue(adapter.retentionSummary().isPresent());
	}

	@Test
	void completedResultIsPublishedOnlyAfterCoordinatorRoleRelease() {
		LifecycleWorkers workers = new LifecycleWorkers(
				(name, runnable) -> runnable.run());
		RecordingOperations operations = new RecordingOperations(
				attempt -> true, Set.of());
		operations.onRelease = () -> Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.COORDINATOR),
				"Restart eligibility cannot precede coordinator role release.");
		BuiltInTransportLifecycleAdapter adapter =
				new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
						operations, () -> Duration.ZERO, Duration.ZERO,
						() -> 0L, workers);

		BuiltInTransportLifecycleAdapter.Generation first = adapter.beginStart();
		adapter.markReady(first);
		adapter.stop();
		BuiltInTransportLifecycleAdapter.Generation second = adapter.beginStart();
		adapter.markReady(second);
		adapter.stop();

		Assertions.assertNotSame(first, second);
		Assertions.assertEquals(2, operations.releaseCount.get());
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.COORDINATOR));
	}

	@Test
	void exactGenerationOperationsRejectForeignTokensWithoutMutation() {
		RecordingOperations firstOperations = new RecordingOperations(
				attempt -> true, Set.of());
		RecordingOperations secondOperations = new RecordingOperations(
				attempt -> true, Set.of());
		BuiltInTransportLifecycleAdapter first = adapter(firstOperations);
		BuiltInTransportLifecycleAdapter second = adapter(secondOperations);
		BuiltInTransportLifecycleAdapter.Generation firstGeneration =
				first.beginStart();
		BuiltInTransportLifecycleAdapter.Generation secondGeneration =
				second.beginStart();
		first.markReady(firstGeneration);
		second.markReady(secondGeneration);

		IllegalStateException resultFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> first.result(secondGeneration));
		IllegalStateException waitFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> first.awaitStop(secondGeneration));
		Assertions.assertEquals("Foreign built-in transport lifecycle generation",
				resultFailure.getMessage());
		Assertions.assertEquals("Foreign built-in transport lifecycle generation",
				waitFailure.getMessage());
		Assertions.assertTrue(first.result(firstGeneration).isEmpty());
		Assertions.assertTrue(second.result(secondGeneration).isEmpty());
		Assertions.assertEquals(0, firstOperations.quiesceCount.get());
		Assertions.assertEquals(0, secondOperations.quiesceCount.get());

		first.stop();
		second.stop();
		Assertions.assertTrue(first.result(firstGeneration).orElseThrow().isComplete());
		Assertions.assertTrue(second.result(secondGeneration).orElseThrow().isComplete());
	}

	@Test
	void coordinatorLaunchFailureFreezesUnknownResultInsteadOfStrandingStop() {
		RuntimeException launchFailure = new IllegalStateException(
				"expected coordinator launch failure");
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			throw launchFailure;
		});
		RecordingOperations operations = new RecordingOperations(
				attempt -> false, Set.of(InternalResidualActivityType.EVENT_LOOP));
		BuiltInTransportLifecycleAdapter adapter =
				new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
						operations, () -> Duration.ZERO, Duration.ZERO,
						() -> 0L, workers);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.stop();

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertEquals(List.of(launchFailure), participant.failures());
		Assertions.assertEquals(Set.of(InternalResidualActivityType.EVENT_LOOP),
				participant.residualActivity());
		Assertions.assertFalse(adapter.result(generation).orElseThrow().isComplete());
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.COORDINATOR));
	}

	@Test
	void launchedThenThrowingCoordinatorCannotRepublishOrReleaseEvidence()
			throws Exception {
		RuntimeException launchFailure = new IllegalStateException(
				"expected post-launch failure");
		CountDownLatch releaseLaunchedWorker = new CountDownLatch(1);
		AtomicReference<Thread> launchedWorker = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (!name.endsWith("lifecycle-coordinator")) {
				runnable.run();
				return;
			}
			Thread worker = new Thread(() -> {
				awaitUninterruptibly(releaseLaunchedWorker);
				runnable.run();
			}, "adapter-launched-then-throwing-coordinator");
			worker.setDaemon(true);
			launchedWorker.set(worker);
			worker.start();
			throw launchFailure;
		});
		RecordingOperations operations = new RecordingOperations(
				attempt -> true, Set.of(InternalResidualActivityType.EVENT_LOOP));
		BuiltInTransportLifecycleAdapter adapter =
				new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
						operations, () -> Duration.ZERO, Duration.ZERO,
						() -> 0L, workers);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.stop();
		InternalShutdownResult frozen = adapter.result(generation).orElseThrow();
		releaseLaunchedWorker.countDown();
		Thread worker = launchedWorker.get();
		Assertions.assertNotNull(worker);
		worker.join(TimeUnit.SECONDS.toMillis(2));

		Assertions.assertFalse(worker.isAlive());
		Assertions.assertSame(frozen, adapter.result(generation).orElseThrow());
		Assertions.assertEquals(List.of(launchFailure),
				participant(adapter).failures());
		Assertions.assertEquals(0, operations.releaseCount.get(),
				"A losing late publisher must not release retained evidence.");
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.COORDINATOR));
	}

	@Test
	void evidenceReleaseFailureFreezesUnknownAndPreservesEarlierFailure() {
		Throwable transportFailure = new IllegalStateException(
				"expected transport failure");
		RuntimeException releaseFailure = new IllegalStateException(
				"expected evidence release failure");
		RecordingOperations operations = new RecordingOperations(
				attempt -> true, Set.of());
		operations.onRelease = () -> operations.residual =
				Set.of(InternalResidualActivityType.EVENT_LOOP);
		operations.releaseFailure = releaseFailure;
		BuiltInTransportLifecycleAdapter adapter = adapter(operations);
		BuiltInTransportLifecycleAdapter.Generation generation = adapter.beginStart();
		adapter.markReady(generation);

		adapter.signalUnexpectedFailure(generation, transportFailure);

		InternalLifecycleComponentShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertEquals(List.of(transportFailure, releaseFailure),
				participant.failures());
		Assertions.assertEquals(Set.of(InternalResidualActivityType.EVENT_LOOP),
				participant.residualActivity());
		Assertions.assertEquals(1, operations.releaseCount.get());
		Assertions.assertFalse(adapter.result(generation).orElseThrow().isComplete());
		Assertions.assertThrows(IllegalStateException.class, adapter::beginStart);
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter adapter(
			@NonNull RecordingOperations operations) {
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		return new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
				operations, () -> Duration.ZERO, Duration.ZERO, () -> 0L, workers);
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter adapter(
			@NonNull RecordingOperations operations, @NonNull Duration gracefulTimeout,
			@NonNull Duration forcedTimeout) {
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		return new BuiltInTransportLifecycleAdapter(InternalLifecycleComponentType.HTTP,
				operations, () -> gracefulTimeout, forcedTimeout, NanoClock.system(), workers);
	}

	private static void awaitEventCount(@NonNull BuiltInTransportLifecycleAdapter adapter,
			BuiltInTransportLifecycleAdapter.@NonNull Generation generation,
			int expectedCount) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
		while (adapter.terminationEvents(generation).size() < expectedCount
				&& System.nanoTime() < deadline)
			Thread.onSpinWait();
		Assertions.assertTrue(adapter.terminationEvents(generation).size() >= expectedCount,
				"Timed out waiting for termination event");
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	@NonNull
	private static InternalLifecycleComponentShutdownResult participant(
			@NonNull BuiltInTransportLifecycleAdapter adapter) {
		return adapter.result().orElseThrow()
				.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow();
	}

	private static final class RecordingOperations
			implements BuiltInTransportLifecycleAdapter.Operations {
		private final IntPredicate proofOnAttempt;
		private volatile Set<InternalResidualActivityType> residual;
		private final AtomicInteger awaitCount = new AtomicInteger();
		private final AtomicInteger quiesceCount = new AtomicInteger();
		private final AtomicInteger forceCount = new AtomicInteger();
		private final AtomicInteger releaseCount = new AtomicInteger();
		private final List<Long> observedDeadlines = new ArrayList<>();
		private volatile Runnable onQuiesce = () -> {};
		private volatile Runnable onAwait = () -> {};
		private volatile Runnable onRelease = () -> {};
		private volatile RuntimeException quiesceFailure;
		private volatile RuntimeException releaseFailure;

		private RecordingOperations(@NonNull IntPredicate proofOnAttempt,
				@NonNull Set<InternalResidualActivityType> residual) {
			this.proofOnAttempt = proofOnAttempt;
			this.residual = residual;
		}

		@Override
		public void quiesce() {
			this.quiesceCount.incrementAndGet();
			this.onQuiesce.run();
			if (this.quiesceFailure != null)
				throw this.quiesceFailure;
		}

		@Override
		public void force() {
			this.forceCount.incrementAndGet();
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos) {
			this.observedDeadlines.add(absoluteDeadlineNanos);
			this.onAwait.run();
			return this.proofOnAttempt.test(this.awaitCount.incrementAndGet());
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityType> residualActivity() {
			return this.residual;
		}

		@Override
		public void releaseTerminatedEvidence() {
			this.releaseCount.incrementAndGet();
			this.onRelease.run();
			if (this.releaseFailure != null)
				throw this.releaseFailure;
		}
	}
}
