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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic state-boundary coverage for installed-start phase delivery. */
final class DirectParticipantPhaseGateTests {
	@NonNull
	private static final NanoClock CLOCK = () -> 0L;

	@Test
	void phaseRequestedDuringStartIsClaimedExactlyOnceOnReturn() {
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		InternalShutdownContext graceful = context(InternalShutdownPhase.GRACEFUL);

		Assertions.assertTrue(gate.claimStart());
		Assertions.assertTrue(gate.startupCallActive());
		Assertions.assertNull(gate.requestPhase(graceful));
		Assertions.assertSame(graceful, gate.completeStartCall());
		Assertions.assertFalse(gate.startupCallActive());
		Assertions.assertNull(gate.requestPhase(graceful));
		Assertions.assertNull(gate.completeStartCall());
	}

	@Test
	void forcedRequestSupersedesDeferredGracefulRequest() {
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		InternalShutdownContext graceful = context(InternalShutdownPhase.GRACEFUL);
		InternalShutdownContext forced = context(InternalShutdownPhase.FORCED);

		Assertions.assertTrue(gate.claimStart());
		Assertions.assertNull(gate.requestPhase(graceful));
		Assertions.assertNull(gate.requestPhase(forced));
		Assertions.assertSame(forced, gate.completeStartCall());
		Assertions.assertNull(gate.requestPhase(graceful));
		Assertions.assertNull(gate.requestPhase(forced));
	}

	@Test
	void phaseAfterStartReturnAndUpgradeEachHaveOneClaim() {
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		InternalShutdownContext graceful = context(InternalShutdownPhase.GRACEFUL);
		InternalShutdownContext forced = context(InternalShutdownPhase.FORCED);

		Assertions.assertTrue(gate.claimStart());
		Assertions.assertNull(gate.completeStartCall());
		Assertions.assertSame(graceful, gate.requestPhase(graceful));
		Assertions.assertNull(gate.requestPhase(graceful));
		Assertions.assertSame(forced, gate.requestPhase(forced));
		Assertions.assertNull(gate.requestPhase(forced));
	}

	@Test
	void classificationFreezeRetainsActiveStartAndRejectsLateCatchUp() {
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		InternalShutdownContext forced = context(InternalShutdownPhase.FORCED);

		Assertions.assertTrue(gate.claimStart());
		Assertions.assertNull(gate.requestPhase(forced));
		gate.freezeForClassification();
		Assertions.assertTrue(gate.startupCallActive());
		Assertions.assertNull(gate.completeStartCall());
		Assertions.assertTrue(gate.startupCallActive(),
				"Classification must retain the active-at-freeze fact");
		Assertions.assertNull(gate.requestPhase(forced));
	}

	@Test
	void coordinatorFreezesGateBeforeReadingEvidence() throws Exception {
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		DeadlineWaiter waiter = new DeadlineWaiter(CLOCK);
		DirectParticipantPhaseGate gate = new DirectParticipantPhaseGate();
		AdmissionFence admission = new AdmissionFence(false, waiter::signal);
		InternalTerminationGroup group = new InternalTerminationGroup(admission,
				waiter::signal, workers);
		group.commit();
		InternalTerminationGroup.TrackedLifecycleCall liveStart =
				group.trackLifecycleCall();
		AtomicReference<InternalShutdownContext> lateDelivery =
				new AtomicReference<>();
		AtomicBoolean secondFrozen = new AtomicBoolean();
		AtomicBoolean everyGateFrozenBeforeResidual = new AtomicBoolean();
		Assertions.assertTrue(gate.claimStart());

		InternalLifecycleCoordinator.Participant participant =
				new InternalLifecycleCoordinator.Participant() {
					@Override
					@NonNull
					public InternalParticipantKind kind() {
						return InternalParticipantKind.HTTP;
					}

					@Override
					@NonNull
					public AdmissionFence admissionFence() {
						return admission;
					}

					@Override
					@NonNull
					public InternalTerminationGroup terminationGroup() {
						return group;
					}

					@Override
					@NonNull
					public InternalTransportRuntime runtime() {
						return new InternalTransportRuntime() {
							@Override
							public void start(@NonNull InternalStartupContext context) {
							}

							@Override
							public void quiesce(
									@NonNull InternalShutdownContext context) {
								gate.requestPhase(context);
							}

							@Override
							public void force(
									@NonNull InternalShutdownContext context) {
								gate.requestPhase(context);
							}
						};
					}

					@Override
					@NonNull
					public Set<InternalResidualActivityKind> residualActivity() {
						everyGateFrozenBeforeResidual.set(secondFrozen.get());
						return Set.of();
					}

					@Override
					public boolean startupCallActive() {
						return gate.startupCallActive();
					}

					@Override
					public void freezeForClassification() {
						gate.freezeForClassification();
						lateDelivery.set(gate.completeStartCall());
					}
				};
		AdmissionFence secondAdmission = new AdmissionFence(false, waiter::signal);
		InternalTerminationGroup secondGroup = new InternalTerminationGroup(
				secondAdmission, waiter::signal, workers);
		secondGroup.commit();
		InternalLifecycleCoordinator.Participant secondParticipant =
				new InternalLifecycleCoordinator.Participant() {
					@Override
					@NonNull
					public InternalParticipantKind kind() {
						return InternalParticipantKind.SSE;
					}

					@Override
					@NonNull
					public AdmissionFence admissionFence() {
						return secondAdmission;
					}

					@Override
					@NonNull
					public InternalTerminationGroup terminationGroup() {
						return secondGroup;
					}

					@Override
					@NonNull
					public InternalTransportRuntime runtime() {
						return new InternalTransportRuntime() {
							@Override
							public void start(
									@NonNull InternalStartupContext context) {
							}

							@Override
							public void quiesce(
									@NonNull InternalShutdownContext context) {
							}

							@Override
							public void force(
									@NonNull InternalShutdownContext context) {
							}
						};
					}

					@Override
					@NonNull
					public Set<InternalResidualActivityKind> residualActivity() {
						return Set.of();
					}

					@Override
					public void freezeForClassification() {
						secondFrozen.set(true);
					}
				};

		try {
			InternalShutdownResult result = new InternalLifecycleCoordinator(CLOCK,
					waiter, new TrackedLifecycleCallRunner(workers)).shutdown(
					List.of(participant, secondParticipant), 0L, 0L);
			InternalParticipantShutdownResult http = result.participantResult(
					InternalParticipantKind.HTTP).orElseThrow();

			Assertions.assertNull(lateDelivery.get());
			Assertions.assertTrue(everyGateFrozenBeforeResidual.get(),
					"All participant gates must freeze before any evidence read");
			Assertions.assertEquals(
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					http.disposition());
			Assertions.assertEquals(Set.of(
					InternalResidualActivityKind.LIFECYCLE_CALL),
					http.residualActivity());
		} finally {
			liveStart.close();
		}
	}

	@NonNull
	private static InternalShutdownContext context(
			@NonNull InternalShutdownPhase phase) {
		return new InternalShutdownContext(phase, CLOCK, 1L);
	}
}
