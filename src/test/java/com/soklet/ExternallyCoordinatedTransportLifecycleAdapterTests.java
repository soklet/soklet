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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ExternallyCoordinatedTransportLifecycleAdapterTests {
	@Test
	void externalGenerationDefersCommitAndAdmissionAndPublishesExactOwnerResult()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		List<String> workerNames = new CopyOnWriteArrayList<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			workerNames.add(name);
			runnable.run();
		});
		Object executionOwnerToken = new Object();
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		operations.onAwait = () -> Assertions.assertTrue(
				LifecycleExecutionContext.isMarked(executionOwnerToken));
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers,
						executionOwnerToken, () -> {}, () -> {});

		Assertions.assertTrue(generation.terminationGroup().isOpen());
		Assertions.assertFalse(generation.startAttempted());
		Assertions.assertFalse(generation.admissionFence().isOpen());
		Assertions.assertTrue(adapter.result(generation).isEmpty());

		adapter.commitExternallyCoordinatedGeneration(generation);
		Assertions.assertFalse(generation.terminationGroup().isOpen());
		AtomicReference<BuiltInTransportLifecycleAdapter.Generation> consumed =
				new AtomicReference<>();
		adapter.runExternallyCoordinatedStart(generation, () -> {
			consumed.set(adapter.beginStart());
			adapter.markReady(generation);
		});

		Assertions.assertSame(generation, consumed.get());
		Assertions.assertTrue(generation.startAttempted());
		Assertions.assertFalse(generation.admissionFence().isOpen(),
				"External readiness must not open admission participant-by-participant");
		Assertions.assertTrue(adapter.tryAdmit(generation).isEmpty());
		Assertions.assertTrue(adapter.openExternallyCoordinatedAdmission(generation));
		try (AdmissionFence.Admission ignored = adapter.tryAdmit(generation)
				.orElseThrow()) {
			// Exact-generation admission is live only after the owner's shared gate.
		}

		Assertions.assertTrue(
				adapter.recordExternallyCoordinatedShutdownIntent(generation));
		InternalShutdownResult exactResult = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				participant.disposition());
		Assertions.assertEquals(Optional.empty(),
				adapter.finalizeExternallyCoordinatedEvidence(generation, participant));
		adapter.publishExternallyCoordinatedResult(generation, exactResult);

		Assertions.assertSame(exactResult, adapter.result(generation).orElseThrow());
		Assertions.assertEquals(1, operations.releaseCount.get());
		Assertions.assertFalse(workerNames.contains(
				"built-in-http-lifecycle-coordinator"),
				"The transport adapter must not launch its standalone coordinator");
	}

	@Test
	void completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers,
						new Object(), () -> {}, () -> {});
		adapter.commitExternallyCoordinatedGeneration(generation);
		adapter.runExternallyCoordinatedStart(generation, () -> {
			Assertions.assertSame(generation, adapter.beginStart());
			adapter.markReady(generation);
		});
		adapter.recordExternallyCoordinatedShutdownIntent(generation);
		InternalShutdownResult exactResult = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, participant).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);

		Assertions.assertTrue(exactResult.isComplete());
		IllegalStateException standaloneFailure = Assertions.assertThrows(
				IllegalStateException.class, adapter::beginStart);
		Assertions.assertEquals(
				"Built-in transport lifecycle is permanently externally owned",
				standaloneFailure.getMessage());
		IllegalStateException secondOwnerFailure = Assertions.assertThrows(
				IllegalStateException.class,
				() -> adapter.newExternallyCoordinatedGeneration(
						new DeadlineWaiter(clock), workers, new Object(),
						() -> {}, () -> {}));
		Assertions.assertEquals(
				"Built-in transport lifecycle is already externally owned",
				secondOwnerFailure.getMessage());
	}

	@Test
	void discardedExternalCandidateRetainsPermanentOwnershipClaim() {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		BuiltInTransportLifecycleAdapter adapter = adapter(
				new RecordingOperations(true, Set.of()), clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers,
						new Object(), () -> {}, () -> {});

		adapter.discardExternallyCoordinatedGeneration(generation);

		Assertions.assertThrows(IllegalStateException.class, adapter::beginStart);
		Assertions.assertThrows(IllegalStateException.class,
				() -> adapter.newExternallyCoordinatedGeneration(
						new DeadlineWaiter(clock), workers, new Object(),
						() -> {}, () -> {}));
		Assertions.assertTrue(adapter.generation().isEmpty(),
				"Discarding an uncommitted candidate must not fabricate a current generation");
	}

	@Test
	void externalUnexpectedFailureRecordsBeforeOneOwnerCallbackWithoutCoordinating()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		AtomicInteger unexpectedCallbacks = new AtomicInteger();
		AtomicReference<List<InternalTerminationEvent>> eventsAtCallback =
				new AtomicReference<>();
		AtomicReference<BuiltInTransportLifecycleAdapter.Generation> generationRef =
				new AtomicReference<>();
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers, new Object(),
						() -> {}, () -> {
							unexpectedCallbacks.incrementAndGet();
							eventsAtCallback.set(adapter.terminationEvents(
									generationRef.get()));
						});
		generationRef.set(generation);
		adapter.commitExternallyCoordinatedGeneration(generation);
		adapter.runExternallyCoordinatedStart(generation, () -> {
			Assertions.assertSame(generation, adapter.beginStart());
			adapter.markReady(generation);
		});
		adapter.openExternallyCoordinatedAdmission(generation);
		Throwable firstFailure = new AssertionError("transport failed");
		Throwable competingFailure = new AssertionError(
				"competing startup failure");
		Throwable cappedFailure = new AssertionError(
				"capped startup failure");

		adapter.signalUnexpectedFailure(generation, firstFailure);
		adapter.signalUnexpectedFailure(generation, competingFailure);
		adapter.signalUnexpectedFailure(generation, cappedFailure);

		Assertions.assertEquals(1, unexpectedCallbacks.get());
		Assertions.assertEquals(1, eventsAtCallback.get().size());
		Assertions.assertSame(firstFailure,
				eventsAtCallback.get().get(0).cause().orElseThrow());
		Assertions.assertArrayEquals(new Throwable[] { competingFailure },
				firstFailure.getSuppressed(),
				"Exactly one pre-freeze competing failure is retained by identity");
		Assertions.assertFalse(generation.admissionFence().isOpen());
		Assertions.assertEquals(0, operations.quiesceCount.get());
		Assertions.assertTrue(adapter.result(generation).isEmpty(),
				"The owner, not the transport, publishes the result");

		InternalShutdownResult exactResult = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		Throwable[] frozenSuppressed = firstFailure.getSuppressed();
		adapter.signalUnexpectedFailure(generation,
				new AssertionError("late post-freeze failure"));
		Assertions.assertArrayEquals(frozenSuppressed,
				firstFailure.getSuppressed(),
				"A post-freeze failure cannot mutate the elected Throwable");
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION,
				participant.disposition());
		Assertions.assertEquals(List.of(firstFailure), participant.failures());
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, participant).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);
	}

	@Test
	void externalStartFailureRecordsExactCauseWithoutLaunchingCoordinator()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		List<String> workerNames = new CopyOnWriteArrayList<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			workerNames.add(name);
			runnable.run();
		});
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		AtomicInteger unexpectedCallbacks = new AtomicInteger();
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers, new Object(),
						() -> {}, unexpectedCallbacks::incrementAndGet);
		adapter.commitExternallyCoordinatedGeneration(generation);
		Throwable startupFailure = new IllegalStateException("bind failed");

		adapter.runExternallyCoordinatedStart(generation, () -> {
			Assertions.assertSame(generation, adapter.beginStart());
			adapter.failedStart(generation, startupFailure, true);
		});

		Assertions.assertTrue(generation.startAttempted());
		Assertions.assertTrue(adapter.result(generation).isEmpty());
		Assertions.assertEquals(0, operations.quiesceCount.get());
		Assertions.assertEquals(0, unexpectedCallbacks.get(),
				"The synchronous start owner already observes this failure directly");
		Assertions.assertEquals(List.of(
				InternalTerminationEvent.Type.FAILURE,
				InternalTerminationEvent.Type.PROOF),
				adapter.terminationEvents(generation).stream()
						.map(InternalTerminationEvent::type).toList());
		Assertions.assertSame(startupFailure,
				adapter.terminationEvents(generation).get(0).cause().orElseThrow());
		Assertions.assertFalse(workerNames.contains(
				"built-in-http-lifecycle-coordinator"));

		InternalShutdownResult coordinated = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		InternalShutdownResult exactResult = new InternalShutdownResult(
				coordinated.disposition(), InternalStartupDisposition.FAILED,
				coordinated.participantResults());
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(List.of(startupFailure), participant.failures());
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, participant).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);
		Assertions.assertSame(exactResult, adapter.result(generation).orElseThrow());
	}

	@Test
	void externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		Object executionOwnerToken = new Object();
		AtomicInteger shutdownCallbacks = new AtomicInteger();
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers,
						executionOwnerToken, shutdownCallbacks::incrementAndGet, () -> {});
		adapter.commitExternallyCoordinatedGeneration(generation);
		adapter.runExternallyCoordinatedStart(generation, () -> {
			adapter.beginStart();
			adapter.markReady(generation);
		});
		adapter.openExternallyCoordinatedAdmission(generation);

		try (LifecycleExecutionContext.Scope ignored =
				LifecycleExecutionContext.enter(executionOwnerToken)) {
			BuiltInTransportLifecycleAdapter.Generation requested =
					adapter.requestStop();
			Assertions.assertSame(generation, requested);
			Assertions.assertTrue(adapter.shutdownRequested(generation));
			Assertions.assertFalse(generation.admissionFence().isOpen());
			Assertions.assertThrows(IllegalStateException.class,
					() -> adapter.awaitStop(requested));
		}
		Assertions.assertEquals(1, shutdownCallbacks.get());
		Assertions.assertSame(generation, adapter.requestStop());
		Assertions.assertEquals(1, shutdownCallbacks.get(),
				"Repeated stop requests must not republish owner intent");

		InternalShutdownResult exactResult = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, participant).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);
		Assertions.assertNull(adapter.requestStop());
	}

	@Test
	void releaseFailureMustBeFoldedIntoDowngradedOwnerResultBeforePublication() {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		RuntimeException releaseFailure = new IllegalStateException(
				"evidence release failed");
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		operations.releaseFailure = releaseFailure;
		BuiltInTransportLifecycleAdapter adapter = adapter(operations, clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers, new Object(),
						() -> {}, () -> {});
		adapter.commitExternallyCoordinatedGeneration(generation);
		adapter.runExternallyCoordinatedStart(generation, () -> {
			adapter.beginStart();
			adapter.markReady(generation);
		});
		adapter.recordExternallyCoordinatedShutdownIntent(generation);
		InternalParticipantShutdownResult proven = participant(
				InternalParticipantKind.HTTP,
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				List.of(), Set.of());
		InternalShutdownResult obsoleteResult = new InternalShutdownResult(
				InternalShutdownDisposition.GRACEFUL, InternalStartupDisposition.READY,
				List.of(proven));

		Assertions.assertSame(releaseFailure,
				adapter.finalizeExternallyCoordinatedEvidence(generation, proven)
						.orElseThrow());
		Assertions.assertThrows(IllegalStateException.class,
				() -> adapter.publishExternallyCoordinatedResult(generation,
						obsoleteResult));

		InternalParticipantShutdownResult downgraded = participant(
				InternalParticipantKind.HTTP,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				List.of(releaseFailure), Set.of());
		InternalShutdownResult exactResult = new InternalShutdownResultAggregator()
				.aggregate(InternalStartupDisposition.READY, List.of(downgraded));
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, downgraded).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);

		Assertions.assertSame(exactResult, adapter.result(generation).orElseThrow());
		Assertions.assertEquals(1, operations.releaseCount.get());
		Assertions.assertTrue(adapter.retentionSummary().isPresent());
	}

	@Test
	void ownerFallbackPublicationReleasesWaitersAfterStrictValidationFailure() {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		BuiltInTransportLifecycleAdapter adapter = adapter(
				new RecordingOperations(true, Set.of()), clock, workers);
		BuiltInTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers,
						new Object(), () -> {}, () -> {});
		adapter.commitExternallyCoordinatedGeneration(generation);
		InternalParticipantShutdownResult participant = participant(
				InternalParticipantKind.HTTP,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				List.of(), Set.of());
		InternalShutdownResult exactResult = new InternalShutdownResultAggregator()
				.aggregate(InternalStartupDisposition.FAILED, List.of(participant));

		Assertions.assertThrows(IllegalStateException.class,
				() -> adapter.publishExternallyCoordinatedResult(generation,
						exactResult));
		adapter.publishExternallyCoordinatedOwnerResultAfterFailure(generation,
				exactResult);
		adapter.publishExternallyCoordinatedOwnerResultAfterFailure(generation,
				exactResult);

		Assertions.assertSame(exactResult,
				adapter.result(generation).orElseThrow());
	}

	@Test
	void mcpForwardsTheExactExternalGenerationAndParticipantEvidence()
			throws Exception {
		NanoClock clock = () -> 0L;
		DeadlineWaiter waiter = new DeadlineWaiter(clock);
		LifecycleWorkers workers = inlineWorkers();
		RecordingOperations operations = new RecordingOperations(true, Set.of());
		McpTransportLifecycleAdapter adapter = new McpTransportLifecycleAdapter(
				Duration.ZERO, Duration.ZERO, clock, workers, operations);
		InternalTransportIdentity identity = adapter.identity();
		McpTransportLifecycleAdapter.Generation generation =
				adapter.newExternallyCoordinatedGeneration(waiter, workers, new Object(),
						() -> {}, () -> {});
		adapter.commitExternallyCoordinatedGeneration(generation);
		AtomicReference<McpTransportLifecycleAdapter.Generation> consumed =
				new AtomicReference<>();

		adapter.runExternallyCoordinatedStart(generation, () -> {
			consumed.set(adapter.beginStart());
			adapter.markReady(generation);
		});

		Assertions.assertSame(generation, consumed.get());
		Assertions.assertSame(generation, adapter.currentGeneration());
		Assertions.assertTrue(generation.startAttempted());
		Assertions.assertFalse(generation.admissionFence().isOpen());
		Assertions.assertFalse(adapter.admissionOpen());
		Assertions.assertTrue(adapter.openExternallyCoordinatedAdmission(generation));
		Assertions.assertTrue(adapter.admissionOpen());
		Assertions.assertSame(identity, adapter.identity());
		adapter.recordExternallyCoordinatedShutdownIntent(generation);

		InternalShutdownResult exactResult = coordinator(clock, waiter, workers)
				.shutdown(List.of(generation), 0L, 0L);
		InternalParticipantShutdownResult participant = exactResult
				.participantResult(InternalParticipantKind.MCP).orElseThrow();
		Assertions.assertEquals(InternalParticipantKind.MCP, generation.kind());
		Assertions.assertTrue(adapter.finalizeExternallyCoordinatedEvidence(
				generation, participant).isEmpty());
		adapter.publishExternallyCoordinatedResult(generation, exactResult);

		Assertions.assertSame(exactResult, adapter.result(generation).orElseThrow());
		Assertions.assertFalse(adapter.admissionOpen());
		Assertions.assertEquals(1, operations.releaseCount.get());
	}

	@NonNull
	private static BuiltInTransportLifecycleAdapter adapter(
			@NonNull RecordingOperations operations, @NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers) {
		return new BuiltInTransportLifecycleAdapter(InternalParticipantKind.HTTP,
				operations, () -> Duration.ZERO, Duration.ZERO, clock, workers);
	}

	@NonNull
	private static InternalLifecycleCoordinator coordinator(@NonNull NanoClock clock,
			@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers) {
		return new InternalLifecycleCoordinator(clock, waiter,
				new TrackedLifecycleCallRunner(workers));
	}

	@NonNull
	private static LifecycleWorkers inlineWorkers() {
		return new LifecycleWorkers((name, runnable) -> runnable.run());
	}

	@NonNull
	private static InternalParticipantShutdownResult participant(
			@NonNull InternalParticipantKind kind,
			@NonNull InternalParticipantShutdownDisposition disposition,
			@NonNull List<? extends Throwable> failures,
			@NonNull Set<InternalResidualActivityKind> residual) {
		return new InternalParticipantShutdownResult(kind, disposition, failures,
				residual);
	}

	private static final class RecordingOperations
			implements BuiltInTransportLifecycleAdapter.Operations {
		private final boolean terminationProven;
		@NonNull
		private final Set<InternalResidualActivityKind> residual;
		private final AtomicInteger quiesceCount = new AtomicInteger();
		private final AtomicInteger releaseCount = new AtomicInteger();
		private volatile Runnable onAwait = () -> {};
		private volatile RuntimeException releaseFailure;

		private RecordingOperations(boolean terminationProven,
				@NonNull Set<InternalResidualActivityKind> residual) {
			this.terminationProven = terminationProven;
			this.residual = residual;
		}

		@Override
		public void quiesce() {
			this.quiesceCount.incrementAndGet();
		}

		@Override
		public void force() {
		}

		@Override
		public boolean awaitTermination(long absoluteDeadlineNanos) {
			this.onAwait.run();
			return this.terminationProven;
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityKind> residualActivity() {
			return this.residual;
		}

		@Override
		public void releaseTerminatedEvidence() {
			this.releaseCount.incrementAndGet();
			if (this.releaseFailure != null)
				throw this.releaseFailure;
		}
	}
}
