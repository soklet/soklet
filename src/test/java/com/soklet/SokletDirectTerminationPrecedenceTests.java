/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Direct-owner precedence for the orthogonal unexpected-termination event. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletDirectTerminationPrecedenceTests {
	@NonNull
	private static final Duration PHASE_BUDGET = Duration.ofSeconds(30);
	@NonNull
	private static final InternalLifecyclePolicy POLICY =
			new InternalLifecyclePolicy(PHASE_BUDGET, PHASE_BUDGET,
					PHASE_BUDGET, PHASE_BUDGET);

	@Test
	void terminalEvidenceSnapshotIsOneShotAcrossLateSignals() {
		AdmissionFence admission = new AdmissionFence();
		InternalControllingEventElection ownerElection =
				new InternalControllingEventElection();
		InternalTerminationGroup group = new InternalTerminationGroup(admission,
				() -> { }, new LifecycleWorkers(),
				LifecycleExecutionContext.legacyOwnerToken(), ownerElection);
		InternalTerminationGroup.Member root = group.root();
		group.commit();
		AdmissionFence.Admission admitted = admission.tryAdmit().orElseThrow();
		InternalTerminationGroup.TrackedLifecycleCall tracked =
				group.trackLifecycleCall();
		group.signalTerminated(root);

		InternalTerminationGroup.EvidenceSnapshot frozen =
				group.freezeEvidence();
		Assertions.assertFalse(frozen.barrierComplete());
		Assertions.assertEquals(1, frozen.trackedLifecycleCalls());
		Assertions.assertEquals(1, frozen.admittedWork());
		Assertions.assertSame(frozen.controllingEvent().orElseThrow(),
				ownerElection.firstEvent().orElseThrow(),
				"Owner event election must occur at the group CAS boundary");
		Assertions.assertEquals(InternalTerminationEvent.Type.PROOF,
				frozen.primaryEvents().get(0).type());

		admitted.close();
		tracked.close();
		group.signalFailure(root,
				new AssertionError("failure after evidence freeze"));
		Assertions.assertTrue(group.isBarrierComplete(),
				"The live diagnostic group may retain bounded late releases");
		Assertions.assertEquals(2, group.primaryEventsInSequence().size(),
				"The live group may retain one bounded late failure diagnostic");
		Assertions.assertSame(frozen, group.freezeEvidence());
		Assertions.assertFalse(frozen.barrierComplete(),
				"Late releases cannot rewrite the terminal classification boundary");
		Assertions.assertEquals(1, frozen.trackedLifecycleCalls());
		Assertions.assertEquals(1, frozen.admittedWork());
		Assertions.assertEquals(1, frozen.primaryEvents().size());
	}

	@Test
	void positiveResidualOverridesCompleteTerminationProof() throws Exception {
		AtomicLong now = new AtomicLong();
		DeadlineWaiter waiter = new DeadlineWaiter(now::get,
				(monitor, remaining) -> now.addAndGet(remaining));
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		AdmissionFence admission = new AdmissionFence(waiter::signal);
		InternalTerminationGroup group = new InternalTerminationGroup(admission,
				waiter::signal, workers);
		group.commit();
		AtomicInteger forceCalls = new AtomicInteger();
		InternalTransportRuntime runtime = new InternalTransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) { }

			@Override
			public void shutdownGracefully(@NonNull ShutdownContext context) {
				group.signalTerminated(group.root());
			}

			@Override
			public void shutdownForcibly(@NonNull ShutdownContext context) {
				forceCalls.incrementAndGet();
				throw new AssertionError("Complete proof must not receive force");
			}
		};
		InternalLifecycleCoordinator.Participant participant =
				new InternalLifecycleCoordinator.Participant() {
					@Override @NonNull public InternalLifecycleComponentType kind() {
						return InternalLifecycleComponentType.HTTP;
					}
					@Override @NonNull public AdmissionFence admissionFence() {
						return admission;
					}
					@Override @NonNull public InternalTerminationGroup terminationGroup() {
						return group;
					}
					@Override @NonNull public InternalTransportRuntime runtime() {
						return runtime;
					}
					@Override @NonNull public Set<InternalResidualActivityType>
					residualActivity() {
						return Set.of(InternalResidualActivityType.STREAM);
					}
				};
		InternalLifecycleCoordinator coordinator =
				new InternalLifecycleCoordinator(now::get, waiter,
						new TrackedLifecycleCallRunner(workers));

		InternalShutdownResult result = coordinator.shutdown(
				List.of(participant), 10L, 20L);
		InternalLifecycleComponentShutdownResult http = result.participantResult(
				InternalLifecycleComponentType.HTTP).orElseThrow();

		Assertions.assertEquals(0, forceCalls.get());
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
				result.disposition());
		Assertions.assertFalse(result.isComplete());
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY,
				http.disposition());
		Assertions.assertEquals(Set.of(InternalResidualActivityType.STREAM),
				http.residualActivity());
		Assertions.assertTrue(http.failures().isEmpty());
	}

	@Test
	void forceSubmissionClaimAndProofShareOneBarrierOrder() {
		InternalTerminationGroup proofWinner = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		proofWinner.commit();
		proofWinner.signalTerminated(proofWinner.root());
		Assertions.assertFalse(proofWinner.tryClaimForceSubmission(),
				"Complete proof must prevent a later force claim");

		InternalTerminationGroup forceWinner = new InternalTerminationGroup(
				new AdmissionFence(), () -> { }, new LifecycleWorkers());
		forceWinner.commit();
		Assertions.assertTrue(forceWinner.tryClaimForceSubmission());
		forceWinner.signalTerminated(forceWinner.root());
		Assertions.assertFalse(forceWinner.isBarrierComplete(),
				"Proof cannot cross an unresolved force-submission claim");
		forceWinner.resolveForceSubmission();
		Assertions.assertTrue(forceWinner.isBarrierComplete());
	}

	@Test
	void ownerShutdownIntentWinsFormerGroupFanoutGap() throws Exception {
		AtomicLong now = new AtomicLong();
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now,
				ProofPhase.NEVER);
		CountDownLatch intentPublished = new CountDownLatch(1);
		CountDownLatch releaseIntent = new CountDownLatch(1);
		AtomicBoolean hookArmed = new AtomicBoolean();
		SokletConfig ownerConfig = config(http)
				.internalLifecyclePolicy(POLICY).build();
		Soklet callbackSoklet = Soklet.fromConfig(config(
				new PrecedenceHttpEndpoint(new AtomicLong(),
						ProofPhase.GRACEFUL)).build());
		SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
				ownerConfig, new SokletFrameworkSetup(ownerConfig), now::get,
				new LifecycleWorkers(), () -> { }, () -> {
					if (hookArmed.get()) {
						intentPublished.countDown();
						awaitIgnoringInterrupts(releaseIntent);
					}
				}, () -> { });
		AtomicReference<Throwable> stopFailure = new AtomicReference<>();
		Thread stopper = new Thread(() -> stopFailure.set(
				captureFailure(() -> joinShutdown(owner))),
				"precedence-owner-intent-gap");
		stopper.setDaemon(true);

		try {
			owner.start();
			hookArmed.set(true);
			stopper.start();
			Assertions.assertTrue(intentPublished.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(
					InternalLifecycleStateMachine.State.SHUTTING_DOWN,
					owner.state());

			http.signalProof();
			releaseIntent.countDown();
			stopper.join(TimeUnit.SECONDS.toMillis(3));
			Assertions.assertFalse(stopper.isAlive());
			Assertions.assertNull(stopFailure.get(),
					"The owner intent won before the transport proof");

			InternalShutdownResult result = owner.result().orElseThrow();
			Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
					result.disposition());
			assertParticipant(result, InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
					List.of());
		} finally {
			releaseIntent.countDown();
			if (stopper.isAlive()) {
				stopper.interrupt();
				stopper.join(TimeUnit.SECONDS.toMillis(3));
			}
			try {
				owner.shutdown();
				owner.awaitCompletion();
			} finally {
				callbackSoklet.close();
			}
		}
	}

	@Test
	void proofDuringGraceIsUnexpectedAndRepeatedStopRetainsExactIdentities()
			throws Exception {
		AtomicLong now = new AtomicLong();
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now,
				ProofPhase.GRACEFUL);
		PrecedenceSseEndpoint sse = new PrecedenceSseEndpoint(now,
				ProofPhase.GRACEFUL);
		try (OwnerHarness harness = OwnerHarness.create(http, sse, now)) {
			harness.owner().start();
			Throwable failure = new AssertionError("HTTP failed after readiness");

			http.signalFailure(failure);
			SokletUnexpectedTerminationException first = Assertions.assertThrows(
					SokletUnexpectedTerminationException.class,
					() -> joinShutdown(harness.owner()));
			SokletUnexpectedTerminationException second = Assertions.assertThrows(
					SokletUnexpectedTerminationException.class,
					() -> joinShutdown(harness.owner()));

			InternalShutdownResult result = harness.owner().result().orElseThrow();
			Assertions.assertNotSame(first, second,
					"Each stop must surface a fresh lifecycle exception");
			Assertions.assertSame(result, first.getInternalShutdownResult());
			Assertions.assertSame(result, second.getInternalShutdownResult());
			Assertions.assertSame(first.getInternalUnexpectedTermination(),
					second.getInternalUnexpectedTermination());
			Assertions.assertSame(failure, first.getCause());
			Assertions.assertSame(failure, second.getCause());
			Assertions.assertSame(failure, first.getInternalUnexpectedTermination()
					.cause().orElseThrow());
			Assertions.assertEquals(InternalStartupDisposition.READY,
					result.startupDisposition());
			Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
					result.disposition());
			assertParticipant(result, InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
					List.of(failure));
			assertParticipant(result, InternalLifecycleComponentType.SSE,
					InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION,
					List.of());
			Assertions.assertTrue(http.awaitQuiesce());
			Assertions.assertTrue(sse.awaitQuiesce());
			Assertions.assertEquals(0, http.forceCalls());
			Assertions.assertEquals(0, sse.forceCalls());
		}
	}

	@Test
	void proofOnlyUnexpectedTerminationRetainsOneSyntheticCause()
			throws Exception {
		AtomicLong now = new AtomicLong();
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now,
				ProofPhase.NEVER);
		try (OwnerHarness harness = OwnerHarness.create(http, null, now)) {
			harness.owner().start();
			http.signalProof();

			SokletUnexpectedTerminationException first = Assertions.assertThrows(
					SokletUnexpectedTerminationException.class,
					() -> joinShutdown(harness.owner()));
			SokletUnexpectedTerminationException second = Assertions.assertThrows(
					SokletUnexpectedTerminationException.class,
					() -> joinShutdown(harness.owner()));
			InternalShutdownResult result = harness.owner().result().orElseThrow();

			Assertions.assertNotSame(first, second);
			Assertions.assertSame(result, first.getInternalShutdownResult());
			Assertions.assertSame(result, second.getInternalShutdownResult());
			Assertions.assertSame(first.getInternalUnexpectedTermination(),
					second.getInternalUnexpectedTermination());
			Assertions.assertTrue(first.getInternalUnexpectedTermination()
					.cause().isEmpty());
			Assertions.assertSame(first.getCause(), second.getCause(),
					"Fresh close exceptions must retain one synthetic cause");
			Assertions.assertInstanceOf(IllegalStateException.class,
					first.getCause());
			Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
					result.disposition());
			assertParticipant(result, InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
					List.of());
		}
	}

	@Test
	void proofAfterActualForceIsForcedWhileCloseRemainsUnexpected()
			throws Exception {
		AtomicLong now = new AtomicLong();
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now,
				ProofPhase.FORCED);
		try (OwnerHarness harness = OwnerHarness.create(http, null, now)) {
			harness.owner().start();
			Throwable failure = new AssertionError(
					"HTTP failed before forced shutdown");

			http.signalFailure(failure);
			SokletUnexpectedTerminationException unexpected =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							() -> joinShutdown(harness.owner()));

			InternalShutdownResult result = harness.owner().result().orElseThrow();
			Assertions.assertTrue(http.awaitForce(),
					"The proof must be causally downstream of force entry");
			Assertions.assertEquals(1, http.quiesceCalls());
			Assertions.assertEquals(1, http.forceCalls());
			Assertions.assertEquals(InternalShutdownDisposition.FORCED,
					result.disposition());
			assertParticipant(result, InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION,
					List.of(failure));
			Assertions.assertSame(result, unexpected.getInternalShutdownResult());
			Assertions.assertSame(failure, unexpected.getCause());
			Assertions.assertSame(failure, unexpected
					.getInternalUnexpectedTermination().cause().orElseThrow());
		}
	}

	@Test
	void failureWithoutProofIsIncompleteButUnexpectedStillWins()
			throws Exception {
		AtomicLong now = new AtomicLong();
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now,
				ProofPhase.NEVER);
		try (OwnerHarness harness = OwnerHarness.create(http, null, now)) {
			harness.owner().start();
			Throwable failure = new AssertionError("HTTP failure without proof");

			http.signalFailure(failure);
			SokletUnexpectedTerminationException unexpected =
					Assertions.assertThrows(
							SokletUnexpectedTerminationException.class,
							() -> joinShutdown(harness.owner()),
							"Unexpected termination precedes incomplete shutdown");

			InternalShutdownResult result = harness.owner().result().orElseThrow();
			Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
					result.disposition());
			Assertions.assertFalse(result.isComplete());
			assertParticipant(result, InternalLifecycleComponentType.HTTP,
					InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
					List.of(failure));
			Assertions.assertSame(result, unexpected.getInternalShutdownResult());
			Assertions.assertSame(failure, unexpected.getCause());
			Assertions.assertTrue(http.awaitForce());
			Assertions.assertEquals(1, http.forceCalls());
		}
	}

	@Test
	void prematureTerminationBeforeReadinessNeverBecomesCloseUnexpected()
			throws Exception {
		assertPreReadyOutcome(ProofPhase.START, true);
		assertPreReadyOutcome(ProofPhase.NEVER, false);
	}

	private static void assertPreReadyOutcome(@NonNull ProofPhase proofPhase,
			boolean complete) throws Exception {
		AtomicLong now = new AtomicLong();
		Throwable failure = new AssertionError("HTTP failed before readiness");
		PrecedenceHttpEndpoint http = new PrecedenceHttpEndpoint(now, proofPhase);
		http.failDuringStart(failure);
		try (OwnerHarness harness = OwnerHarness.create(http, null, now)) {
			SokletStartupException startup = Assertions.assertThrows(
					SokletStartupException.class, harness.owner()::start);
			InternalShutdownResult result = harness.owner().result().orElseThrow();
			Assertions.assertSame(failure, startup.getCause());
			Assertions.assertSame(result, startup.getInternalShutdownResult());
			Assertions.assertEquals(InternalStartupDisposition.FAILED,
					result.startupDisposition());

			Throwable stopFailure = captureFailure(
					() -> joinShutdown(harness.owner()));
			Assertions.assertFalse(stopFailure
					instanceof SokletUnexpectedTerminationException,
					"Only termination after READY may surface as close-unexpected");
			if (complete) {
				Assertions.assertNull(stopFailure);
				Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
						result.disposition());
				assertParticipant(result, InternalLifecycleComponentType.HTTP,
						InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION,
						List.of(failure));
			} else {
				SokletShutdownIncompleteException incomplete = Assertions.assertInstanceOf(
						SokletShutdownIncompleteException.class, stopFailure);
				Assertions.assertSame(result,
						incomplete.getInternalShutdownResult());
				Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE,
						result.disposition());
				assertParticipant(result, InternalLifecycleComponentType.HTTP,
						InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN,
						List.of(failure));
			}
		}
	}

	private static void assertParticipant(@NonNull InternalShutdownResult result,
			@NonNull InternalLifecycleComponentType kind,
			@NonNull InternalLifecycleComponentShutdownDisposition disposition,
			@NonNull List<? extends Throwable> failures) {
		InternalLifecycleComponentShutdownResult participant = result
				.participantResult(kind).orElseThrow();
		Assertions.assertEquals(disposition, participant.disposition());
		Assertions.assertEquals(failures, participant.failures());
		Assertions.assertTrue(participant.residualActivity().isEmpty());
	}

	private static void joinShutdown(@NonNull SokletDirectLifecycle owner) {
		ShutdownResult result = owner.shutdown().toCompletableFuture().join();
		owner.throwIfUnsuccessfulShutdown(result);
	}

	@Nullable
	private static Throwable captureFailure(@NonNull Runnable operation) {
		try {
			operation.run();
			return null;
		} catch (Throwable failure) {
			return failure;
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

	private enum ProofPhase {
		START,
		GRACEFUL,
		FORCED,
		NEVER
	}

	private record OwnerHarness(@NonNull Soklet callbackSoklet,
			@NonNull SokletDirectLifecycle owner) implements AutoCloseable {
		@NonNull
		private static OwnerHarness create(@NonNull PrecedenceHttpEndpoint http,
				@Nullable PrecedenceSseEndpoint sse, @NonNull AtomicLong now) {
			SokletConfig.Builder ownerBuilder = config(http)
					.internalLifecyclePolicy(POLICY);
			if (sse != null)
				ownerBuilder.sseServer(sse);
			SokletConfig ownerConfig = ownerBuilder.build();
			Soklet callbackSoklet = Soklet.fromConfig(
					config(new PrecedenceHttpEndpoint(new AtomicLong(),
							ProofPhase.GRACEFUL)).build());
			SokletDirectLifecycle owner = new SokletDirectLifecycle(callbackSoklet,
					ownerConfig, new SokletFrameworkSetup(ownerConfig), now::get,
					new LifecycleWorkers());
			return new OwnerHarness(callbackSoklet, owner);
		}

		@Override
		public void close() throws Exception {
			this.owner.shutdown();
			this.owner.awaitCompletion();
			this.callbackSoklet.close();
		}
	}

	private static SokletConfig.@NonNull Builder config(
			@NonNull HttpServer http) {
		return SokletConfig.withHttpServer(http)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(
						Set.of(PrecedenceResource.class)));
	}

	private static final class PhaseControl {
		@NonNull private final AtomicLong now;
		@NonNull private final ProofPhase proofPhase;
		@NonNull private final AtomicBoolean proofSignalled = new AtomicBoolean();
		@NonNull private final AtomicInteger quiesceCalls = new AtomicInteger();
		@NonNull private final AtomicInteger forceCalls = new AtomicInteger();
		@NonNull private final CountDownLatch quiesceEntered = new CountDownLatch(1);
		@NonNull private final CountDownLatch forceEntered = new CountDownLatch(1);
		@NonNull private final AtomicReference<TransportTerminationSignal>
				signal = new AtomicReference<>();
		@NonNull private final AtomicReference<Throwable> startFailure =
				new AtomicReference<>();

		private PhaseControl(@NonNull AtomicLong now,
				@NonNull ProofPhase proofPhase) {
			this.now = now;
			this.proofPhase = proofPhase;
		}

		void install(@NonNull TransportTerminationSignal signal) {
			this.signal.set(signal);
		}

		void failDuringStart(@NonNull Throwable failure) {
			this.startFailure.set(failure);
		}

		void signalFailure(@NonNull Throwable failure) {
			requireSignal().signalTerminationFailure(failure);
		}

		void signalProof() {
			terminate();
		}

		@NonNull
		TransportRuntime runtime() {
			return new TransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
					Throwable failure = startFailure.get();
					if (failure != null)
						signalFailure(failure);
					if (proofPhase == ProofPhase.START)
						terminate();
				}

				@Override
				public void shutdownGracefully(@NonNull ShutdownContext context) {
					quiesceCalls.incrementAndGet();
					quiesceEntered.countDown();
					if (proofPhase == ProofPhase.GRACEFUL
							|| proofPhase == ProofPhase.START)
						terminate();
					else
						advanceBy(context.getRemainingTime());
				}

				@Override
				public void shutdownForcibly(@NonNull ShutdownContext context) {
					forceCalls.incrementAndGet();
					forceEntered.countDown();
					if (proofPhase == ProofPhase.FORCED)
						terminate();
					else
						advanceBy(context.getRemainingTime());
				}
			};
		}

		int quiesceCalls() {
			return this.quiesceCalls.get();
		}

		int forceCalls() {
			return this.forceCalls.get();
		}

		boolean awaitQuiesce() throws InterruptedException {
			return this.quiesceEntered.await(2, TimeUnit.SECONDS);
		}

		boolean awaitForce() throws InterruptedException {
			return this.forceEntered.await(2, TimeUnit.SECONDS);
		}

		private void terminate() {
			if (this.proofSignalled.compareAndSet(false, true))
				requireSignal().signalTerminated();
		}

		private void advanceBy(@NonNull Duration remainingTime) {
			this.now.addAndGet(remainingTime.toNanos());
		}

		@NonNull
		private TransportTerminationSignal requireSignal() {
			return java.util.Objects.requireNonNull(this.signal.get(),
					"Transport signal is not attached");
		}
	}

	private static final class PrecedenceHttpEndpoint implements HttpServer {
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final PhaseControl phase;

		private PrecedenceHttpEndpoint(@NonNull AtomicLong now,
				@NonNull ProofPhase proofPhase) {
			this.phase = new PhaseControl(now, proofPhase);
		}

		void failDuringStart(@NonNull Throwable failure) {
			this.phase.failDuringStart(failure);
		}

		void signalFailure(@NonNull Throwable failure) {
			this.phase.signalFailure(failure);
		}

		void signalProof() {
			this.phase.signalProof();
		}

		int quiesceCalls() { return this.phase.quiesceCalls(); }
		int forceCalls() { return this.phase.forceCalls(); }
		boolean awaitQuiesce() throws InterruptedException {
			return this.phase.awaitQuiesce();
		}
		boolean awaitForce() throws InterruptedException {
			return this.phase.awaitForce();
		}

		@Override @NonNull public TransportIdentity getTransportIdentity() {
			return this.identity;
		}
		@Override @NonNull public TransportRuntime attach(
				@NonNull HttpTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.phase.install(context.getTerminationSignal());
			return this.phase.runtime();
		}
	}

	private static final class PrecedenceSseEndpoint implements SseServer {
		@NonNull private final TransportIdentity identity =
				TransportIdentity.create();
		@NonNull private final PhaseControl phase;

		private PrecedenceSseEndpoint(@NonNull AtomicLong now,
				@NonNull ProofPhase proofPhase) {
			this.phase = new PhaseControl(now, proofPhase);
		}

		int forceCalls() { return this.phase.forceCalls(); }
		boolean awaitQuiesce() throws InterruptedException {
			return this.phase.awaitQuiesce();
		}

		@Override @NonNull public TransportIdentity getTransportIdentity() {
			return this.identity;
		}
		@Override @NonNull public TransportRuntime attach(
				@NonNull SseTransportAttachmentContext context,
				@NonNull StartupContext startupContext) {
			this.phase.install(context.getTerminationSignal());
			return this.phase.runtime();
		}
		@Override @NonNull public Optional<? extends SseBroadcaster> acquireBroadcaster(
				@Nullable ResourcePath resourcePath) { return Optional.empty(); }
	}

	public static final class PrecedenceResource {
		@GET("/precedence")
		@NonNull
		public String get() {
			return "precedence";
		}
	}
}
