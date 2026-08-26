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

import com.soklet.annotation.GET;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Direct-owner integration coverage for late attachment and start returns. */
@Timeout(value = 45, unit = TimeUnit.SECONDS)
final class SokletDirectLateStartupIntegrationTests {
	@NonNull
	private static final Duration LONG_STARTUP = Duration.ofSeconds(10);
	@NonNull
	private static final Duration FORCE_DELAY = Duration.ofMillis(150);
	@NonNull
	private static final Duration GRACEFUL_CATCH_UP_FORCE_DELAY =
			Duration.ofSeconds(20);
	@NonNull
	private static final Duration FORCED_OBSERVATION = Duration.ofSeconds(2);
	@NonNull
	private final Set<ExecutorService> executors =
			ConcurrentHashMap.newKeySet();

	@AfterEach
	void tearDown() {
		List<ExecutorService> snapshot = List.copyOf(this.executors);
		for (ExecutorService executor : snapshot)
			executor.shutdownNow();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		boolean interrupted = false;
		int unterminated = 0;
		for (ExecutorService executor : snapshot) {
			while (!executor.isTerminated()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L)
					break;
				try {
					executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
			if (!executor.isTerminated())
				unterminated++;
		}
		this.executors.clear();
		if (interrupted)
			Thread.currentThread().interrupt();
		Assertions.assertEquals(0, unterminated,
				"Late-startup test executors did not terminate");
	}

	@Test
	void attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted()
			throws Exception {
		RuntimeException pendingFailure = new IllegalStateException(
				"pending event from the uncommitted attachment");
		BlockingAttachHttpEndpoint http = new BlockingAttachHttpEndpoint(
				PendingEvent.FAILURE, pendingFailure);
		OwnerHarness harness = OwnerHarness.create(config(http, phasePolicy()).build(),
				new LifecycleWorkers(), () -> { });
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(harness.owner()::start));

		try (harness) {
			Assertions.assertTrue(http.awaitAttachEntered());
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			Assertions.assertSame(stage, harness.owner().shutdown());
			http.releaseAttach();
			Assertions.assertTrue(http.awaitAttachReturned());

			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			InternalShutdownResult result = stage.toCompletableFuture()
					.get(5, TimeUnit.SECONDS);
			Throwable exactCancellation = assertExactCancellation(startupFailure,
					result);

			Assertions.assertEquals(InternalShutdownDisposition.NOT_STARTED,
					result.disposition());
			InternalParticipantShutdownResult participant = assertParticipant(result,
					InternalParticipantKind.HTTP,
					InternalParticipantShutdownDisposition.NOT_STARTED);
			Assertions.assertEquals(List.of(exactCancellation),
					participant.failures());
			Assertions.assertTrue(participant.failures().stream()
					.noneMatch(failure -> failure == pendingFailure),
					"A discarded precommit event cannot escape as participant evidence");
			Assertions.assertEquals(0, exactCancellation.getSuppressed().length,
					"A discarded precommit event cannot escape as a suppressed cause");
			Assertions.assertTrue(participant.residualActivity().isEmpty());
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(0, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			Assertions.assertTrue(http.invoke("/late-startup").isEmpty());
			Assertions.assertFalse(harness.owner().isStarted());
			assertStableTerminalIdentity(harness.owner(), stage, result);
		} finally {
			http.releaseAttach();
			drainFuture(start);
		}
	}

	@Test
	void pendingAttachProofCannotCompleteCallStillLiveAtTerminalFreeze()
			throws Exception {
		BlockingAttachHttpEndpoint http = new BlockingAttachHttpEndpoint(
				PendingEvent.PROOF, null);
		ObservedLauncher launcher = new ObservedLauncher();
		OwnerHarness harness = OwnerHarness.create(config(http, phasePolicy()).build(),
				new LifecycleWorkers(launcher), () -> { });
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(harness.owner()::start));

		try (harness) {
			Assertions.assertTrue(http.awaitAttachEntered());
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			InternalShutdownResult result = stage.toCompletableFuture()
					.get(5, TimeUnit.SECONDS);
			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			Throwable exactCancellation = assertExactCancellation(startupFailure,
					result);

			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					result.disposition());
			InternalParticipantShutdownResult participant = assertParticipant(result,
					InternalParticipantKind.HTTP,
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
			Assertions.assertEquals(List.of(exactCancellation),
					participant.failures());
			Assertions.assertEquals(Set.of(
					InternalResidualActivityKind.LIFECYCLE_CALL),
					participant.residualActivity(),
					"Precommit proof cannot hide a still-live attachment call");
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(0, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			assertStableTerminalIdentity(harness.owner(), stage, result);

			http.releaseAttach();
			Assertions.assertTrue(http.awaitAttachReturned());
			Assertions.assertTrue(launcher.awaitCompleted("soklet-attach-http"),
					"The losing attachment worker did not finish discard handling");
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(0, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			Assertions.assertSame(result, harness.owner().result().orElseThrow());
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					harness.owner().result().orElseThrow().participantResult(
							InternalParticipantKind.HTTP).orElseThrow().disposition(),
					"A late losing return cannot replay its discarded proof");
		} finally {
			http.releaseAttach();
			drainFuture(start);
			launcher.awaitTermination();
		}
	}

	@Test
	void installedAttachmentGracefullyReleasedBeforeStartIsNotStarted()
			throws Exception {
		assertAttachedNeverStarted(ProofMode.GRACEFUL,
				InternalShutdownDisposition.NOT_STARTED,
				InternalParticipantShutdownDisposition.NOT_STARTED);
	}

	@Test
	void installedAttachmentProvenOnlyAfterForceIsForced() throws Exception {
		assertAttachedNeverStarted(ProofMode.FORCED,
				InternalShutdownDisposition.FORCED,
				InternalParticipantShutdownDisposition.FORCED_TERMINATION);
	}

	@Test
	void installedAttachmentMissingProofIsExactUnknown() throws Exception {
		assertAttachedNeverStarted(ProofMode.NEVER,
				InternalShutdownDisposition.INCOMPLETE,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
	}

	@Test
	void pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit()
			throws Exception {
		assertPendingAttachReturn(PendingEvent.PROOF);
		assertPendingAttachReturn(PendingEvent.FAILURE);
	}

	@Test
	void pendingAttachEventsCannotOverrideThrowOrNullPrecedence()
			throws Exception {
		RuntimeException proofThenThrow = new IllegalArgumentException(
				"configured attachment failed after its pending proof");
		assertPendingAttachFailure(PendingEvent.PROOF, AttachFailure.THROW,
				proofThenThrow);
		assertPendingAttachFailure(PendingEvent.PROOF, AttachFailure.NULL, null);
		RuntimeException failureThenThrow = new IllegalArgumentException(
				"configured attachment failed after its pending failure");
		assertPendingAttachFailure(PendingEvent.FAILURE, AttachFailure.THROW,
				failureThenThrow);
		assertPendingAttachFailure(PendingEvent.FAILURE, AttachFailure.NULL, null);
	}

	@Test
	void lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce()
			throws Exception {
		assertLateStartReturn(StartRelease.GRACEFUL);
	}

	@Test
	void lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase()
			throws Exception {
		assertLateStartReturn(StartRelease.FORCED);
	}

	@Test
	void startReturnAfterTerminalFreezeIsInertAndCannotRewriteUnknown()
			throws Exception {
		assertLateStartReturn(StartRelease.AFTER_FREEZE);
	}

	private void assertAttachedNeverStarted(@NonNull ProofMode proofMode,
			@NonNull InternalShutdownDisposition expectedAggregate,
			@NonNull InternalParticipantShutdownDisposition expectedParticipant)
			throws Exception {
		CountDownLatch attachmentSettled = new CountDownLatch(1);
		CountDownLatch releaseAttachmentWrapper = new CountDownLatch(1);
		AttachedNeverStartedHttpEndpoint http =
				new AttachedNeverStartedHttpEndpoint(proofMode);
		OwnerHarness harness = OwnerHarness.create(config(http, phasePolicy()).build(),
				new LifecycleWorkers(), () -> {
					attachmentSettled.countDown();
					awaitIgnoringInterrupts(releaseAttachmentWrapper);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(harness.owner()::start));

		try (harness) {
			Assertions.assertTrue(attachmentSettled.await(5, TimeUnit.SECONDS),
					"The attachment did not win installation before its wrapper gate");
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			Assertions.assertSame(stage, harness.owner().shutdown());
			Assertions.assertTrue(http.runtime().awaitQuiesce(),
					"An installed attachment was not quiesced while its wrapper was live");
			Assertions.assertEquals(0, http.runtime().startCalls());

			if (proofMode == ProofMode.GRACEFUL) {
				Assertions.assertEquals(0, http.runtime().forceCalls());
			} else {
				Assertions.assertTrue(http.runtime().awaitForce(),
						"The unresolved installed attachment did not receive force");
			}
			releaseAttachmentWrapper.countDown();

			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			InternalShutdownResult result = stage.toCompletableFuture()
					.get(5, TimeUnit.SECONDS);
			assertExactCancellation(startupFailure, result);
			Assertions.assertEquals(expectedAggregate, result.disposition());
			InternalParticipantShutdownResult participant = assertParticipant(result,
					InternalParticipantKind.HTTP, expectedParticipant);
			Assertions.assertTrue(participant.failures().isEmpty());
			Assertions.assertTrue(participant.residualActivity().isEmpty(),
					"The released wrapper cannot survive in terminal residual evidence");
			Assertions.assertEquals(1, http.runtime().quiesceCalls());
			Assertions.assertEquals(proofMode == ProofMode.GRACEFUL ? 0 : 1,
					http.runtime().forceCalls());
			Assertions.assertTrue(http.invoke("/late-startup").isEmpty());
			Assertions.assertFalse(harness.owner().isStarted());
			assertStableTerminalIdentity(harness.owner(), stage, result);

			if (proofMode == ProofMode.NEVER) {
				http.runtime().signalProof();
				Assertions.assertSame(result, harness.owner().result().orElseThrow());
				Assertions.assertEquals(
						InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
						harness.owner().result().orElseThrow().participantResult(
								InternalParticipantKind.HTTP).orElseThrow()
								.disposition(),
						"Late physical proof cannot rewrite missing-proof evidence");
			}
		} finally {
			releaseAttachmentWrapper.countDown();
			drainFuture(start);
		}
	}

	private void assertPendingAttachReturn(@NonNull PendingEvent pendingEvent)
			throws Exception {
		RuntimeException exactFailure = pendingEvent == PendingEvent.FAILURE
				? new IllegalStateException("pending failure before attachment commit")
				: null;
		PendingAttachHttpEndpoint http = new PendingAttachHttpEndpoint(pendingEvent,
				AttachFailure.NONE, exactFailure, null);
		CountDownLatch attachmentSettled = new CountDownLatch(1);
		CountDownLatch releaseAttachmentWrapper = new CountDownLatch(1);
		OwnerHarness harness = OwnerHarness.create(config(http, phasePolicy()).build(),
				new LifecycleWorkers(), () -> {
					attachmentSettled.countDown();
					awaitIgnoringInterrupts(releaseAttachmentWrapper);
				});
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(harness.owner()::start));
		try (harness) {
			Assertions.assertTrue(attachmentSettled.await(5, TimeUnit.SECONDS),
					"The attachment did not settle at the pre-commit gate");
			Assertions.assertFalse(start.isDone(),
					"A pending event cannot terminate startup before group commit");
			Assertions.assertTrue(harness.owner().result().isEmpty());
			Assertions.assertEquals(InternalLifecycleStateMachine.State.STARTING,
					harness.owner().state(),
					"A pending event cannot publish shutdown intent before group commit");
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(0, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			releaseAttachmentWrapper.countDown();

			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();
			Throwable startupCause = startupFailure.getCause();

			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			Assertions.assertSame(result, harness.owner().result().orElseThrow());
			if (pendingEvent == PendingEvent.FAILURE) {
				Assertions.assertSame(exactFailure, startupCause);
			} else {
				Assertions.assertEquals(IllegalStateException.class,
						startupCause.getClass());
				Assertions.assertEquals(
						"A transport terminated before Soklet shutdown intent",
						startupCause.getMessage());
				Assertions.assertNull(startupCause.getCause());
			}
			Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
					result.disposition());
			InternalParticipantShutdownResult participant = assertParticipant(result,
					InternalParticipantKind.HTTP,
					InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION);
			Assertions.assertEquals(pendingEvent == PendingEvent.FAILURE
					? List.of(exactFailure) : List.of(), participant.failures());
			Assertions.assertTrue(participant.residualActivity().isEmpty());
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(1, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
			assertStableTerminalIdentity(harness.owner(), stage, result);
			Assertions.assertDoesNotThrow(harness.owner()::stop,
					"Pre-ready termination must not become close-unexpected");
		} finally {
			releaseAttachmentWrapper.countDown();
			drainFuture(start);
		}
	}

	private void assertPendingAttachFailure(@NonNull PendingEvent pendingEvent,
			@NonNull AttachFailure attachFailure,
			@Nullable RuntimeException exactAttachFailure) throws Exception {
		RuntimeException pendingFailure = pendingEvent == PendingEvent.FAILURE
				? new IllegalStateException("discarded pending attachment failure")
				: null;
		PendingAttachHttpEndpoint http = new PendingAttachHttpEndpoint(pendingEvent,
				attachFailure, pendingFailure, exactAttachFailure);
		try (OwnerHarness harness = OwnerHarness.create(
				config(http, phasePolicy()).build(), new LifecycleWorkers(), () -> { })) {
			SokletStartupException startupFailure = Assertions.assertThrows(
					SokletStartupException.class, harness.owner()::start);
			Throwable exactFailure = startupFailure.getCause();
			InternalShutdownResult result = startupFailure.getInternalShutdownResult();

			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					startupFailure.getInternalStartupDisposition());
			if (attachFailure == AttachFailure.THROW) {
				Assertions.assertSame(exactAttachFailure, exactFailure);
			} else {
				Assertions.assertEquals(NullPointerException.class,
						exactFailure.getClass());
				Assertions.assertEquals("Configured attach(...) returned null",
						exactFailure.getMessage());
			}
			Assertions.assertNull(exactFailure.getCause());
			Assertions.assertEquals(0, exactFailure.getSuppressed().length,
					"A discarded pending event cannot escape as a suppressed cause");
			Assertions.assertEquals(InternalShutdownDisposition.NOT_STARTED,
					result.disposition());
			InternalParticipantShutdownResult participant = assertParticipant(result,
					InternalParticipantKind.HTTP,
					InternalParticipantShutdownDisposition.NOT_STARTED);
			Assertions.assertEquals(List.of(exactFailure), participant.failures());
			Assertions.assertTrue(participant.failures().stream()
					.noneMatch(failure -> failure == pendingFailure));
			Assertions.assertTrue(participant.residualActivity().isEmpty());
			Assertions.assertEquals(0, http.runtime().startCalls());
			Assertions.assertEquals(0, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			Assertions.assertSame(result,
					stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
			assertStableTerminalIdentity(harness.owner(), stage, result);
		}
	}

	private void assertLateStartReturn(@NonNull StartRelease release)
			throws Exception {
		ObservedLauncher launcher = new ObservedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		ImmediateHttpEndpoint http = new ImmediateHttpEndpoint();
		LateStartSseEndpoint sse = new LateStartSseEndpoint(switch (release) {
			case GRACEFUL -> ProofMode.GRACEFUL;
			case FORCED -> ProofMode.FORCED;
			case AFTER_FREEZE -> ProofMode.NEVER;
		});
		InternalLifecyclePolicy policy = release == StartRelease.GRACEFUL
				? gracefulCatchUpPolicy() : phasePolicy();
		OwnerHarness harness = OwnerHarness.create(config(http, policy)
				.sseServer(sse).build(), workers, () -> { });
		ExecutorService executor = newExecutor();
		Future<Throwable> start = executor.submit(() ->
				captureFailure(harness.owner()::start));

		try (harness) {
			Assertions.assertTrue(sse.awaitStartEntered(),
					"The later SSE start did not reach its gate");
			CompletionStage<InternalShutdownResult> stage = harness.owner().shutdown();
			Assertions.assertSame(stage, harness.owner().shutdown());

			Assertions.assertTrue(http.runtime().awaitQuiesce(),
					"The independent quiesce-safe HTTP ingress was not wound up");
			Assertions.assertTrue(launcher.awaitCompleted("lifecycle-quiesce-sse"),
					"The SSE graceful phase was not deferred behind its live start");
			Assertions.assertEquals(1, http.runtime().quiesceCalls());
			Assertions.assertEquals(0, http.runtime().forceCalls());
			Assertions.assertEquals(0, sse.runtime().quiesceCalls(),
					"Graceful shutdown must not enter a runtime with live start()");
			Assertions.assertEquals(0, sse.runtime().forceCalls());

			InternalShutdownResult result;
			if (release == StartRelease.GRACEFUL) {
				sse.releaseStart();
				Assertions.assertTrue(sse.runtime().awaitQuiesce(),
						"The returned start did not catch up to graceful shutdown");
				result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
			} else if (release == StartRelease.FORCED) {
				Assertions.assertTrue(launcher.awaitCompleted("lifecycle-force-sse"),
						"The unresolved live start did not retain the forced phase");
				Assertions.assertEquals(0, sse.runtime().quiesceCalls());
				Assertions.assertEquals(0, sse.runtime().forceCalls(),
						"Force must remain deferred while start() is live");
				sse.releaseStart();
				Assertions.assertTrue(sse.runtime().awaitForce(),
						"The returned start did not catch up to forced shutdown");
				result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
			} else {
				result = stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
				Assertions.assertEquals(0, sse.runtime().quiesceCalls());
				Assertions.assertEquals(0, sse.runtime().forceCalls());
			}

			SokletStartupException startupFailure = Assertions.assertInstanceOf(
					SokletStartupException.class,
					start.get(5, TimeUnit.SECONDS));
			assertExactCancellation(startupFailure, result);
			InternalParticipantShutdownResult httpResult = assertParticipant(result,
					InternalParticipantKind.HTTP,
					InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION);
			Assertions.assertTrue(httpResult.failures().isEmpty());
			Assertions.assertTrue(httpResult.residualActivity().isEmpty());

			InternalParticipantShutdownDisposition expectedSse = switch (release) {
				case GRACEFUL ->
						InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION;
				case FORCED ->
						InternalParticipantShutdownDisposition.FORCED_TERMINATION;
				case AFTER_FREEZE ->
						InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN;
			};
			InternalParticipantShutdownResult sseResult = assertParticipant(result,
					InternalParticipantKind.SSE, expectedSse);
			Assertions.assertTrue(sseResult.failures().isEmpty());
			if (release == StartRelease.AFTER_FREEZE) {
				Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
						result.disposition());
				Assertions.assertEquals(Set.of(
						InternalResidualActivityKind.LIFECYCLE_CALL),
						sseResult.residualActivity());
				sse.releaseStart();
				Assertions.assertTrue(sse.awaitStartReturned());
				Assertions.assertTrue(launcher.awaitCompleted("soklet-start-sse"),
						"The late start worker did not finish frozen catch-up handling");
				Assertions.assertEquals(0, sse.runtime().quiesceCalls());
				Assertions.assertEquals(0, sse.runtime().forceCalls(),
						"A return after terminal freeze cannot invoke a late phase");
				Assertions.assertSame(result, harness.owner().result().orElseThrow());
				Assertions.assertEquals(
						InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
						harness.owner().result().orElseThrow().participantResult(
								InternalParticipantKind.SSE).orElseThrow().disposition());
			} else {
				Assertions.assertTrue(sseResult.residualActivity().isEmpty());
				Assertions.assertEquals(release == StartRelease.GRACEFUL
						? InternalShutdownDisposition.GRACEFUL
						: InternalShutdownDisposition.FORCED,
						result.disposition());
				Assertions.assertTrue(sse.awaitStartReturned());
			}
			Assertions.assertEquals(release == StartRelease.GRACEFUL ? 1 : 0,
					sse.runtime().quiesceCalls());
			Assertions.assertEquals(release == StartRelease.FORCED ? 1 : 0,
					sse.runtime().forceCalls());
			if (release == StartRelease.FORCED) {
				Assertions.assertEquals(InternalShutdownPhase.FORCED,
						sse.runtime().firstUnderlyingPhase());
				Assertions.assertTrue(sse.runtime().forceSubsumedQuiesce());
			}
			Assertions.assertTrue(http.invoke("/late-startup").isEmpty());
			Assertions.assertTrue(sse.invoke("/late-startup").isEmpty());
			Assertions.assertFalse(harness.owner().isStarted());
			assertStableTerminalIdentity(harness.owner(), stage, result);
		} finally {
			sse.releaseStart();
			drainFuture(start);
			launcher.awaitTermination();
		}
	}

	@NonNull
	private static Throwable assertExactCancellation(
			@NonNull SokletStartupException startupFailure,
			@NonNull InternalShutdownResult result) {
		Assertions.assertEquals(InternalStartupDisposition.CANCELLED,
				startupFailure.getInternalStartupDisposition());
		Assertions.assertSame(result, startupFailure.getInternalShutdownResult());
		Throwable exactCancellation = startupFailure.getCause();
		Assertions.assertEquals(IllegalStateException.class,
				exactCancellation.getClass());
		Assertions.assertEquals("Soklet shutdown was requested during startup",
				exactCancellation.getMessage());
		Assertions.assertNull(exactCancellation.getCause());
		return exactCancellation;
	}

	@NonNull
	private static InternalParticipantShutdownResult assertParticipant(
			@NonNull InternalShutdownResult result,
			@NonNull InternalParticipantKind kind,
			@NonNull InternalParticipantShutdownDisposition disposition) {
		InternalParticipantShutdownResult participant = result
				.participantResult(kind).orElseThrow();
		Assertions.assertEquals(disposition, participant.disposition());
		return participant;
	}

	private static void assertStableTerminalIdentity(@NonNull SokletDirectLifecycle owner,
			@NonNull CompletionStage<InternalShutdownResult> stage,
			@NonNull InternalShutdownResult result) throws Exception {
		Assertions.assertSame(stage, owner.shutdown());
		Assertions.assertSame(result, owner.awaitCompletion());
		Assertions.assertSame(result, owner.result().orElseThrow());
		Assertions.assertSame(result,
				stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED,
				owner.state());
	}

	private static SokletConfig.@NonNull Builder config(@NonNull HttpServer http,
			@NonNull InternalLifecyclePolicy policy) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(LateStartupResource.class)))
				.internalLifecyclePolicy(policy);
	}

	@NonNull
	private static InternalLifecyclePolicy phasePolicy() {
		return new InternalLifecyclePolicy(Optional.of(LONG_STARTUP), Duration.ZERO,
				FORCE_DELAY, FORCED_OBSERVATION);
	}

	@NonNull
	private static InternalLifecyclePolicy gracefulCatchUpPolicy() {
		return new InternalLifecyclePolicy(Optional.of(LONG_STARTUP), Duration.ZERO,
				GRACEFUL_CATCH_UP_FORCE_DELAY, FORCED_OBSERVATION);
	}

	private ExecutorService newExecutor() {
		ExecutorService executor = Executors.newCachedThreadPool();
		this.executors.add(executor);
		return executor;
	}

	@Nullable
	private static Throwable captureFailure(@NonNull Runnable invocation) {
		try {
			invocation.run();
			return null;
		} catch (Throwable failure) {
			return failure;
		}
	}

	private static void drainFuture(@NonNull Future<?> future) {
		try {
			future.get(5, TimeUnit.SECONDS);
		} catch (Throwable ignored) {
			future.cancel(true);
		}
	}

	private static void awaitIgnoringInterrupts(@NonNull CountDownLatch latch) {
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

	private enum ProofMode {
		GRACEFUL,
		FORCED,
		NEVER
	}

	private enum PendingEvent {
		PROOF,
		FAILURE
	}

	private enum AttachFailure {
		NONE,
		THROW,
		NULL
	}

	private enum StartRelease {
		GRACEFUL,
		FORCED,
		AFTER_FREEZE
	}

	private record OwnerHarness(@NonNull Soklet callbackSoklet,
			@NonNull SokletDirectLifecycle owner) implements AutoCloseable {
		@NonNull
		private static OwnerHarness create(@NonNull SokletConfig ownerConfig,
				@NonNull LifecycleWorkers workers,
				@NonNull Runnable afterAttachmentSettled) {
			CallbackHttpEndpoint callbackHttp = new CallbackHttpEndpoint();
			Soklet callbackSoklet = Soklet.fromConfig(
					config(callbackHttp, phasePolicy()).build());
			SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
					ownerConfig, new SokletFrameworkSetup(ownerConfig),
					NanoClock.system(), workers, () -> { }, () -> { }, () -> { },
					afterAttachmentSettled);
			return new OwnerHarness(callbackSoklet, owner);
		}

		@Override
		public void close() throws Exception {
			try {
				this.owner.shutdown();
				this.owner.awaitCompletion();
			} finally {
				this.callbackSoklet.close();
			}
		}
	}

	private static final class ObservedLauncher
			implements LifecycleWorkers.Launcher {
		@NonNull
		private final ConcurrentMap<String, CountDownLatch> completions =
				new ConcurrentHashMap<>();
		@NonNull
		private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

		@Override
		public void launch(@NonNull String name, @NonNull Runnable runnable) {
			CountDownLatch completion = this.completions.computeIfAbsent(name,
					ignored -> new CountDownLatch(1));
			Thread thread = new Thread(() -> {
				try {
					runnable.run();
				} finally {
					completion.countDown();
				}
			}, name);
			thread.setDaemon(true);
			this.threads.add(thread);
			thread.start();
		}

		boolean awaitCompleted(@NonNull String name) throws InterruptedException {
			return this.completions.computeIfAbsent(name,
					ignored -> new CountDownLatch(1)).await(5, TimeUnit.SECONDS);
		}

		void awaitTermination() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			for (Thread thread : List.copyOf(this.threads)) {
				long remaining = deadline - System.nanoTime();
				if (remaining > 0L)
					thread.join(Math.max(1L,
							TimeUnit.NANOSECONDS.toMillis(remaining)));
			}
			long liveThreads = this.threads.stream().filter(Thread::isAlive).count();
			Assertions.assertEquals(0L, liveThreads,
					"Late-startup lifecycle workers did not terminate");
		}
	}

	private static final class PhaseRuntime implements InternalTransportRuntime {
		@NonNull
		private final ProofMode proofMode;
		@NonNull
		private final AtomicReference<InternalTransportTerminationSignal> signal =
				new AtomicReference<>();
		@NonNull
		private final AtomicInteger startCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull
		private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull
		private final CountDownLatch quiesceEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch forceEntered = new CountDownLatch(1);
		@NonNull
		private final AtomicReference<InternalShutdownPhase> firstUnderlyingPhase =
				new AtomicReference<>();
		@NonNull
		private final AtomicBoolean forceSubsumedQuiesce = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean proofSignalled = new AtomicBoolean();
		@NonNull
		private final AtomicBoolean started = new AtomicBoolean();

		private PhaseRuntime(@NonNull ProofMode proofMode) {
			this.proofMode = proofMode;
		}

		void install(@NonNull InternalTransportTerminationSignal signal) {
			this.signal.set(signal);
		}

		void markStart() {
			this.startCalls.incrementAndGet();
			this.started.set(true);
		}

		@Override
		public void start(@NonNull InternalStartupContext context) {
			markStart();
		}

		@Override
		public void quiesce(@NonNull InternalShutdownContext context) {
			this.firstUnderlyingPhase.compareAndSet(null, context.phase());
			this.quiesceCalls.incrementAndGet();
			this.quiesceEntered.countDown();
			this.started.set(false);
			if (this.proofMode == ProofMode.GRACEFUL)
				signalProof();
		}

		@Override
		public void force(@NonNull InternalShutdownContext context) {
			if (this.firstUnderlyingPhase.compareAndSet(null, context.phase()))
				this.forceSubsumedQuiesce.set(true);
			this.forceCalls.incrementAndGet();
			this.forceEntered.countDown();
			this.started.set(false);
			if (this.proofMode == ProofMode.FORCED)
				signalProof();
		}

		void signalProof() {
			this.started.set(false);
			if (this.proofSignalled.compareAndSet(false, true))
				requireSignal().signalTerminated();
		}

		int startCalls() {
			return this.startCalls.get();
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean started() {
			return this.started.get();
		}

		boolean awaitQuiesce() throws InterruptedException {
			return this.quiesceEntered.await(5, TimeUnit.SECONDS);
		}

		boolean awaitForce() throws InterruptedException {
			return this.forceEntered.await(5, TimeUnit.SECONDS);
		}

		@Nullable
		InternalShutdownPhase firstUnderlyingPhase() {
			return this.firstUnderlyingPhase.get();
		}

		boolean forceSubsumedQuiesce() {
			return this.forceSubsumedQuiesce.get();
		}

		@NonNull
		private InternalTransportTerminationSignal requireSignal() {
			return java.util.Objects.requireNonNull(this.signal.get(),
					"Transport signal is not attached");
		}
	}

	private abstract static class AbstractHttpEndpoint
			implements HttpServer, InternalHttpTransportEndpoint {
		@NonNull
		private final InternalTransportIdentity identity =
				InternalTransportIdentity.create();
		@NonNull
		private final AtomicReference<RequestHandler> requestHandler =
				new AtomicReference<>();

		final void captureHandler(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context) {
			this.requestHandler.set(context.requestHandler());
		}

		@NonNull
		final Optional<HttpRequestResult> invoke(@NonNull String path) {
			RequestHandler handler = this.requestHandler.get();
			if (handler == null)
				return Optional.empty();
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			handler.handleRequest(Request.withPath(HttpMethod.GET, path).build(),
					result::set);
			return Optional.ofNullable(result.get());
		}

		@Override
		@NonNull
		public final InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		public final void start() {
			throw new AssertionError("Direct endpoint startup uses its runtime");
		}

		@Override
		public void stop() { }

		@Override
		@NonNull
		public Boolean isStarted() {
			return false;
		}

		@Override
		public final void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct endpoint attachment uses attach(...)");
		}
	}

	private static final class BlockingAttachHttpEndpoint
			extends AbstractHttpEndpoint {
		@NonNull
		private final PendingEvent pendingEvent;
		@Nullable
		private final RuntimeException pendingFailure;
		@NonNull
		private final PhaseRuntime runtime = new PhaseRuntime(ProofMode.NEVER);
		@NonNull
		private final CountDownLatch attachEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch releaseAttach = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch attachReturned = new CountDownLatch(1);

		private BlockingAttachHttpEndpoint(@NonNull PendingEvent pendingEvent,
				@Nullable RuntimeException pendingFailure) {
			this.pendingEvent = pendingEvent;
			this.pendingFailure = pendingFailure;
		}

		@NonNull
		PhaseRuntime runtime() {
			return this.runtime;
		}

		boolean awaitAttachEntered() throws InterruptedException {
			return this.attachEntered.await(5, TimeUnit.SECONDS);
		}

		void releaseAttach() {
			this.releaseAttach.countDown();
		}

		boolean awaitAttachReturned() throws InterruptedException {
			return this.attachReturned.await(5, TimeUnit.SECONDS);
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			captureHandler(context);
			this.runtime.install(context.terminationSignal());
			if (this.pendingEvent == PendingEvent.PROOF)
				context.terminationSignal().signalTerminated();
			else
				context.terminationSignal().signalTerminationFailure(
						java.util.Objects.requireNonNull(this.pendingFailure));
			this.attachEntered.countDown();
			awaitIgnoringInterrupts(this.releaseAttach);
			this.attachReturned.countDown();
			return this.runtime;
		}
	}

	private static final class AttachedNeverStartedHttpEndpoint
			extends AbstractHttpEndpoint {
		@NonNull
		private final PhaseRuntime runtime;

		private AttachedNeverStartedHttpEndpoint(@NonNull ProofMode proofMode) {
			this.runtime = new PhaseRuntime(proofMode);
		}

		@NonNull
		PhaseRuntime runtime() {
			return this.runtime;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			captureHandler(context);
			this.runtime.install(context.terminationSignal());
			return this.runtime;
		}
	}

	private static final class PendingAttachHttpEndpoint
			extends AbstractHttpEndpoint {
		@NonNull
		private final PendingEvent pendingEvent;
		@NonNull
		private final AttachFailure attachFailure;
		@Nullable
		private final RuntimeException pendingFailure;
		@Nullable
		private final RuntimeException exactAttachFailure;
		@NonNull
		private final PhaseRuntime runtime = new PhaseRuntime(ProofMode.GRACEFUL);

		private PendingAttachHttpEndpoint(@NonNull PendingEvent pendingEvent,
				@NonNull AttachFailure attachFailure,
				@Nullable RuntimeException pendingFailure,
				@Nullable RuntimeException exactAttachFailure) {
			this.pendingEvent = pendingEvent;
			this.attachFailure = attachFailure;
			this.pendingFailure = pendingFailure;
			this.exactAttachFailure = exactAttachFailure;
		}

		@NonNull
		PhaseRuntime runtime() {
			return this.runtime;
		}

		@Override
		@NonNull
		@SuppressWarnings("NullAway")
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			captureHandler(context);
			this.runtime.install(context.terminationSignal());
			if (this.pendingEvent == PendingEvent.PROOF)
				context.terminationSignal().signalTerminated();
			else
				context.terminationSignal().signalTerminationFailure(
						java.util.Objects.requireNonNull(this.pendingFailure));

			return switch (this.attachFailure) {
				case NONE -> this.runtime;
				case THROW -> throw java.util.Objects.requireNonNull(
						this.exactAttachFailure);
				case NULL -> null;
			};
		}
	}

	private static final class ImmediateHttpEndpoint
			extends AbstractHttpEndpoint {
		@NonNull
		private final PhaseRuntime runtime = new PhaseRuntime(ProofMode.GRACEFUL);

		@NonNull
		PhaseRuntime runtime() {
			return this.runtime;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			captureHandler(context);
			this.runtime.install(context.terminationSignal());
			return this.runtime;
		}

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.runtime.started();
		}
	}

	private static final class CallbackHttpEndpoint
			extends AbstractHttpEndpoint {
		@NonNull
		private final PhaseRuntime runtime = new PhaseRuntime(ProofMode.GRACEFUL);

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			captureHandler(context);
			this.runtime.install(context.terminationSignal());
			return this.runtime;
		}
	}

	private static final class LateStartSseEndpoint
			implements SseServer, InternalSseTransportEndpoint {
		@NonNull
		private final InternalTransportIdentity identity =
				InternalTransportIdentity.create();
		@NonNull
		private final PhaseRuntime runtime;
		@NonNull
		private final AtomicReference<RequestHandler> requestHandler =
				new AtomicReference<>();
		@NonNull
		private final CountDownLatch startEntered = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch releaseStart = new CountDownLatch(1);
		@NonNull
		private final CountDownLatch startReturned = new CountDownLatch(1);

		private LateStartSseEndpoint(@NonNull ProofMode proofMode) {
			this.runtime = new PhaseRuntime(proofMode);
		}

		@NonNull
		PhaseRuntime runtime() {
			return this.runtime;
		}

		boolean awaitStartEntered() throws InterruptedException {
			return this.startEntered.await(5, TimeUnit.SECONDS);
		}

		void releaseStart() {
			this.releaseStart.countDown();
		}

		boolean awaitStartReturned() throws InterruptedException {
			return this.startReturned.await(5, TimeUnit.SECONDS);
		}

		@NonNull
		Optional<HttpRequestResult> invoke(@NonNull String path) {
			RequestHandler handler = this.requestHandler.get();
			if (handler == null)
				return Optional.empty();
			AtomicReference<HttpRequestResult> result = new AtomicReference<>();
			handler.handleRequest(Request.withPath(HttpMethod.GET, path).build(),
					result::set);
			return Optional.ofNullable(result.get());
		}

		@Override
		@NonNull
		public InternalTransportIdentity identity() {
			return this.identity;
		}

		@Override
		@NonNull
		public InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<RequestHandler> context,
				@NonNull InternalStartupContext startupContext) {
			this.requestHandler.set(context.requestHandler());
			this.runtime.install(context.terminationSignal());
			return new InternalTransportRuntime() {
				@Override
				public void start(@NonNull InternalStartupContext context) {
					runtime.markStart();
					startEntered.countDown();
					try {
						awaitIgnoringInterrupts(releaseStart);
					} finally {
						startReturned.countDown();
					}
				}

				@Override
				public void quiesce(@NonNull InternalShutdownContext context) {
					runtime.quiesce(context);
				}

				@Override
				public void force(@NonNull InternalShutdownContext context) {
					runtime.force(context);
				}
			};
		}

		@Override
		public void start() {
			throw new AssertionError("Direct endpoint startup uses its runtime");
		}

		@Override
		public void stop() { }

		@Override
		@NonNull
		public Boolean isStarted() {
			return this.runtime.started();
		}

		@Override
		@NonNull
		public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) {
			return Optional.empty();
		}

		@Override
		public void initialize(@NonNull SokletConfig sokletConfig,
				@NonNull RequestHandler requestHandler) {
			throw new AssertionError("Direct endpoint attachment uses attach(...)");
		}
	}

	public static final class LateStartupResource {
		@GET("/late-startup")
		@NonNull
		public String get() {
			return "late-startup";
		}
	}
}
