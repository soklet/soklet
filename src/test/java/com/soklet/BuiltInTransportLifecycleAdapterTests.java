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

		InternalParticipantShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
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
				InternalParticipantShutdownDisposition.FORCED_TERMINATION,
				participant(adapter).disposition());
		Assertions.assertEquals(List.of(0L, 0L), operations.observedDeadlines);
		Assertions.assertEquals(1, operations.quiesceCount.get());
		Assertions.assertEquals(1, operations.forceCount.get());
		Assertions.assertEquals(1, operations.releaseCount.get());
	}

	@Test
	void positiveResidualAndUnknownBothRetainEvidenceWithoutRelease() {
		RecordingOperations residualOperations = new RecordingOperations(
				attempt -> false, Set.of(InternalResidualActivityKind.EVENT_LOOP));
		BuiltInTransportLifecycleAdapter residualAdapter = adapter(residualOperations);
		BuiltInTransportLifecycleAdapter.Generation residualGeneration =
				residualAdapter.beginStart();
		residualAdapter.markReady(residualGeneration);
		residualAdapter.stop();

		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
				participant(residualAdapter).disposition());
		Assertions.assertEquals(Set.of(InternalResidualActivityKind.EVENT_LOOP),
				participant(residualAdapter).residualActivity());
		Assertions.assertEquals(0, residualOperations.releaseCount.get());
		Assertions.assertEquals(1, residualAdapter.retentionSummary().orElseThrow()
				.counts().get(InternalResidualActivityKind.EVENT_LOOP));
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
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
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

		InternalParticipantShutdownResult participant = participant(adapter);
		Assertions.assertEquals(InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
				participant.disposition());
		Assertions.assertEquals(Set.of(InternalResidualActivityKind.CALLBACK),
				participant.residualActivity());
		Assertions.assertEquals(1, adapter.retentionSummary().orElseThrow()
				.counts().get(InternalResidualActivityKind.CALLBACK));
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

		InternalParticipantShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION,
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
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
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
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
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
				InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION,
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

		InternalParticipantShutdownResult participant = participant(adapter);
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				participant.disposition());
		Assertions.assertEquals(List.of(failure), participant.failures());
		Assertions.assertTrue(adapter.retentionSummary().isPresent());
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter adapter(
			@NonNull RecordingOperations operations) {
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		return new BuiltInTransportLifecycleAdapter(InternalParticipantKind.HTTP,
				operations, () -> Duration.ZERO, Duration.ZERO, () -> 0L, workers);
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter adapter(
			@NonNull RecordingOperations operations, @NonNull Duration gracefulTimeout,
			@NonNull Duration forcedTimeout) {
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		return new BuiltInTransportLifecycleAdapter(InternalParticipantKind.HTTP,
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
	private static InternalParticipantShutdownResult participant(
			@NonNull BuiltInTransportLifecycleAdapter adapter) {
		return adapter.result().orElseThrow()
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
	}

	private static final class RecordingOperations
			implements BuiltInTransportLifecycleAdapter.Operations {
		private final IntPredicate proofOnAttempt;
		private final Set<InternalResidualActivityKind> residual;
		private final AtomicInteger awaitCount = new AtomicInteger();
		private final AtomicInteger quiesceCount = new AtomicInteger();
		private final AtomicInteger forceCount = new AtomicInteger();
		private final AtomicInteger releaseCount = new AtomicInteger();
		private final List<Long> observedDeadlines = new ArrayList<>();
		private volatile Runnable onQuiesce = () -> {};
		private volatile Runnable onAwait = () -> {};
		private volatile RuntimeException quiesceFailure;

		private RecordingOperations(@NonNull IntPredicate proofOnAttempt,
				@NonNull Set<InternalResidualActivityKind> residual) {
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
		public Set<InternalResidualActivityKind> residualActivity() {
			return this.residual;
		}

		@Override
		public void releaseTerminatedEvidence() {
			this.releaseCount.incrementAndGet();
		}
	}
}
