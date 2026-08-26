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

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Truthful force attribution when lifecycle-worker submission is rejected. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class InternalLifecycleCoordinatorForceAttributionTests {
	@Test
	void rejectedForceLaunchDoesNotMakeLateGracefulProofForced()
			throws Throwable {
		IllegalStateException launchFailure = new IllegalStateException(
				"force worker rejected");
		AtomicLong now = new AtomicLong();
		AtomicInteger waitCalls = new AtomicInteger();
		AtomicInteger quiesceCalls = new AtomicInteger();
		AtomicInteger forceCalls = new AtomicInteger();
		AtomicInteger forceLaunchAttempts = new AtomicInteger();
		AtomicBoolean proofPublished = new AtomicBoolean();
		CountDownLatch quiesceEntered = new CountDownLatch(1);
		CountDownLatch quiesceInterrupted = new CountDownLatch(1);
		CountDownLatch releaseLateProof = new CountDownLatch(1);
		CountDownLatch quiesceExited = new CountDownLatch(1);
		CountDownLatch forceLaunchRejected = new CountDownLatch(1);
		CountDownLatch neverReleased = new CountDownLatch(1);
		AtomicReference<Thread> quiesceWorker = new AtomicReference<>();

		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("lifecycle-quiesce-http")) {
				Thread worker = new Thread(() -> {
					try {
						runnable.run();
					} finally {
						quiesceExited.countDown();
					}
				}, "force-attribution-quiesce");
				worker.setDaemon(true);
				quiesceWorker.set(worker);
				worker.start();
				return;
			}
			if (name.equals("lifecycle-force-http")) {
				forceLaunchAttempts.incrementAndGet();
				forceLaunchRejected.countDown();
				throw launchFailure;
			}
			throw new AssertionError("Unexpected lifecycle worker: " + name);
		});
		DeadlineWaiter waiter = new DeadlineWaiter(now::get,
				(monitor, remainingNanos) -> {
					int invocation = waitCalls.incrementAndGet();
					if (invocation == 1) {
						Assertions.assertTrue(quiesceEntered.await(2,
								TimeUnit.SECONDS));
						now.set(10L);
						return;
					}
					if (invocation == 2) {
						Assertions.assertTrue(forceLaunchRejected.await(2,
								TimeUnit.SECONDS));
						releaseLateProof.countDown();
						Assertions.assertTrue(quiesceExited.await(2,
								TimeUnit.SECONDS));
						return;
					}
					Assertions.fail("Unexpected lifecycle wait " + invocation);
				});
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup group = new InternalTerminationGroup(fence,
				() -> {}, workers);
		group.commit();
		InternalTransportRuntime runtime = new InternalTransportRuntime() {
			@Override
			public void start(@NonNull InternalStartupContext context) {
			}

			@Override
			public void quiesce(@NonNull InternalShutdownContext context) {
				quiesceCalls.incrementAndGet();
				quiesceEntered.countDown();
				try {
					neverReleased.await();
				} catch (InterruptedException expected) {
					quiesceInterrupted.countDown();
					awaitUninterruptibly(releaseLateProof);
					proofPublished.set(true);
					group.signalTerminated(group.root());
				}
			}

			@Override
			public void force(@NonNull InternalShutdownContext context) {
				forceCalls.incrementAndGet();
				group.signalTerminated(group.root());
			}
		};
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
				return fence;
			}

			@Override
			@NonNull
			public InternalTerminationGroup terminationGroup() {
				return group;
			}

			@Override
			@NonNull
			public InternalTransportRuntime runtime() {
				return runtime;
			}

			@Override
			@NonNull
			public Set<InternalResidualActivityKind> residualActivity() {
				return Set.of();
			}
		};
		InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
				now::get, waiter, new TrackedLifecycleCallRunner(workers));

		InternalShutdownResult result;
		Throwable primaryFailure = null;
		try {
			result = coordinator.shutdown(List.of(participant), 10L, 20L);
		} catch (Throwable failure) {
			primaryFailure = failure;
			throw failure;
		} finally {
			releaseLateProof.countDown();
			Thread worker = quiesceWorker.get();
			if (worker != null)
				worker.interrupt();
			Throwable cleanupFailure = null;
			try {
				if (!quiesceExited.await(2, TimeUnit.SECONDS))
					cleanupFailure = new AssertionError(
							"The test-owned quiesce worker must terminate");
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				cleanupFailure = interrupted;
			}
			if (cleanupFailure != null) {
				if (primaryFailure != null)
					primaryFailure.addSuppressed(cleanupFailure);
				else
					throw new AssertionError("Force-attribution cleanup failed",
							cleanupFailure);
			}
		}

		Assertions.assertEquals(2, waitCalls.get(),
				"A rejected force still requires forced-window observation");
		Assertions.assertEquals(1, quiesceCalls.get());
		Assertions.assertEquals(1, forceLaunchAttempts.get());
		Assertions.assertEquals(0, forceCalls.get(),
				"Rejected launch must never enter the transport force call");
		Assertions.assertEquals(0, quiesceInterrupted.getCount());
		Assertions.assertTrue(proofPublished.get());
		Assertions.assertTrue(group.isBarrierComplete());
		Assertions.assertTrue(group.controllingEvent().isEmpty());
		Assertions.assertEquals(0, group.trackedLifecycleCallCount());
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));

		List<InternalTerminationEvent> events = group.primaryEventsInSequence();
		Assertions.assertEquals(2, events.size());
		Assertions.assertEquals(InternalTerminationEvent.Type.FAILURE,
				events.get(0).type());
		Assertions.assertSame(launchFailure,
				events.get(0).cause().orElseThrow());
		Assertions.assertEquals(InternalTerminationEvent.Type.PROOF,
				events.get(1).type(),
				"Graceful proof must arrive after the rejected force launch");

		Assertions.assertEquals(InternalStartupDisposition.READY,
				result.startupDisposition());
		Assertions.assertEquals(InternalShutdownDisposition.GRACEFUL,
				result.disposition());
		Assertions.assertTrue(result.isComplete());
		InternalParticipantShutdownResult http = result
				.participantResult(InternalParticipantKind.HTTP).orElseThrow();
		Assertions.assertEquals(
				InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION,
				http.disposition(),
				"Only a successfully submitted force call may force attribution");
		Assertions.assertEquals(List.of(launchFailure), http.failures());
		Assertions.assertTrue(http.residualActivity().isEmpty());
	}

	private static void awaitUninterruptibly(@NonNull CountDownLatch latch) {
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
}
