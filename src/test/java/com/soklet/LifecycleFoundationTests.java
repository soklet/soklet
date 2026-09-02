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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LifecycleFoundationTests {
	@Test
	void deadlineWaiterUsesTheOneSuppliedAbsoluteDeadlineWithoutSleeping()
			throws Exception {
		AtomicLong now = new AtomicLong(10L);
		AtomicInteger waits = new AtomicInteger();
		AtomicBoolean complete = new AtomicBoolean();
		DeadlineWaiter waiter = new DeadlineWaiter(now::get, (monitor, remaining) -> {
			Assertions.assertEquals(90L, remaining);
			waits.incrementAndGet();
			complete.set(true);
		});

		Assertions.assertEquals(DeadlineWaiter.Outcome.SATISFIED,
				waiter.await(100L, complete::get));
		Assertions.assertEquals(1, waits.get());

		DeadlineWaiter deadlineWaiter = new DeadlineWaiter(now::get,
				(monitor, remaining) -> now.addAndGet(remaining));
		Assertions.assertEquals(DeadlineWaiter.Outcome.DEADLINE_REACHED,
				deadlineWaiter.await(100L, () -> false));
		Assertions.assertEquals(100L, now.get());
	}

	@Test
	void deadlineArithmeticAndContextSnapshotsClampAndNeverOverflow() {
		Assertions.assertEquals(Long.MAX_VALUE,
				LifecycleDeadlines.after(Long.MAX_VALUE - 1L, Duration.ofNanos(2L)));
		Assertions.assertEquals(0L, LifecycleDeadlines.remainingNanos(5L, 6L));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				new InternalLifecyclePolicy(Optional.of(Duration.ofSeconds(-1)),
						Duration.ZERO, Duration.ZERO, Duration.ZERO));

		AtomicLong now = new AtomicLong(5L);
		AtomicBoolean cancelationRequested = new AtomicBoolean();
		StartupContext startup = new StartupContext(now::get,
				Optional.of(10L), 30L, cancelationRequested::get);
		Assertions.assertSame(Boolean.FALSE, startup.isCancelationRequested());
		Assertions.assertEquals(Duration.ofNanos(5L),
				startup.getRemainingTime().orElseThrow());
		cancelationRequested.set(true);
		Assertions.assertSame(Boolean.TRUE, startup.isCancelationRequested());
		Assertions.assertEquals(Duration.ofNanos(25L),
				startup.getRemainingTime().orElseThrow());
		now.set(40L);
		Assertions.assertEquals(Duration.ZERO,
				startup.getRemainingTime().orElseThrow());

		ShutdownContext shutdown = new ShutdownContext(ShutdownPhase.GRACEFUL,
				now::get, 50L);
		Assertions.assertEquals(ShutdownPhase.GRACEFUL,
				shutdown.getShutdownPhase());
		Assertions.assertEquals(Duration.ofNanos(10L),
				shutdown.getRemainingTime());
	}

	@Test
	void aggregationIsDeterministicAndRejectsDuplicateKinds() {
		InternalLifecycleComponentShutdownResult graceful = participant(InternalLifecycleComponentType.HTTP,
				InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION);
		InternalLifecycleComponentShutdownResult forced = participant(InternalLifecycleComponentType.SSE,
				InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION);
		InternalLifecycleComponentShutdownResult unknown = participant(InternalLifecycleComponentType.MCP,
				InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN);

		InternalShutdownResult result = new InternalShutdownResultAggregator().aggregate(
				InternalStartupDisposition.READY, List.of(unknown, forced, graceful));
		Assertions.assertEquals(InternalShutdownDisposition.INCOMPLETE, result.disposition());
		Assertions.assertEquals(List.of(InternalLifecycleComponentType.HTTP,
				InternalLifecycleComponentType.SSE, InternalLifecycleComponentType.MCP),
				result.participantResults().stream()
						.map(InternalLifecycleComponentShutdownResult::kind).toList());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				new InternalShutdownResult(InternalShutdownDisposition.GRACEFUL,
						InternalStartupDisposition.READY, List.of(graceful, graceful)));
	}

	@Test
	void publicShutdownResultRetainsTheExactStartupFailureCause() {
		IllegalStateException startupFailure = new IllegalStateException(
				"simulated startup failure");
		InternalShutdownResult internalResult = new InternalShutdownResult(
				InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.FAILED, List.of());

		ShutdownResult result = ShutdownResult.fromInternal(internalResult,
				startupFailure, null, null);

		Assertions.assertSame(startupFailure,
				result.getStartupFailureCause().orElseThrow());
		Assertions.assertTrue(ShutdownResult.fromInternal(internalResult)
				.getStartupFailureCause().isEmpty());
	}

	@Test
	void internalPublicationPrecedesTheCachedPublicHandoff() throws Exception {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		InternalLifecycleCompletion completion = new InternalLifecycleCompletion(workers);
		CompletionStage<InternalShutdownResult> publicStage = completion.publicStage();
		Assertions.assertSame(publicStage, completion.publicStage());
		CountDownLatch continuationEntered = new CountDownLatch(1);
		CountDownLatch releaseContinuation = new CountDownLatch(1);
		publicStage.thenRun(() -> {
			continuationEntered.countDown();
			awaitUninterruptibly(releaseContinuation);
		});
		InternalShutdownResult result = new InternalShutdownResult(
				InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.NOT_ATTEMPTED, List.of());

		completion.publish(result);
		Assertions.assertSame(result, completion.result().orElseThrow());
		Assertions.assertSame(result, completion.await());
		Thread handoff = new Thread(launcher.remove()::run, "foundation-public-handoff-test");
		handoff.start();
		Assertions.assertTrue(continuationEntered.await(2, TimeUnit.SECONDS));
		Assertions.assertSame(result, completion.await(),
				"A blocked public continuation must not block private waiters");
		releaseContinuation.countDown();
		handoff.join();
	}

	@Test
	void stateStartAndShutdownClaimsAreOneShot() throws Exception {
		InternalLifecycleStateMachine state = new InternalLifecycleStateMachine();
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		List<Thread> contenders = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			Thread contender = new Thread(() -> {
				awaitUninterruptibly(start);
				try {
					state.claimStart();
					successes.incrementAndGet();
				} catch (IllegalStateException ignored) {
					// Exactly seven contenders lose the one claim.
				}
			});
			contenders.add(contender);
			contender.start();
		}
		start.countDown();
		for (Thread contender : contenders)
			contender.join();
		Assertions.assertEquals(1, successes.get());
		Assertions.assertTrue(state.requestShutdown());
		Assertions.assertFalse(state.requestShutdown());
		state.publishClosed();
		Assertions.assertEquals(InternalLifecycleStateMachine.State.CLOSED, state.state());
	}

	@Test
	void terminationEventsAreOrthogonalOrderedAndReplayedOnCommit() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup group = new InternalTerminationGroup(fence, () -> {}, workers);
		InternalTerminationGroup.Member root = group.root();
		InternalTerminationGroup.Member child = group.registerChild(root);
		Throwable failure = new AssertionError("first");
		group.signalFailure(child, failure);
		group.signalTerminated(root);
		group.signalTerminated(child);
		Assertions.assertTrue(fence.isOpen(), "Precommit signals remain private");

		group.commit();
		Assertions.assertFalse(fence.isOpen());
		List<InternalTerminationEvent> events = group.primaryEventsInSequence();
		Assertions.assertEquals(List.of(InternalTerminationEvent.Type.FAILURE,
				InternalTerminationEvent.Type.PROOF, InternalTerminationEvent.Type.PROOF),
				events.stream().map(InternalTerminationEvent::type).toList());
		Assertions.assertSame(failure, events.get(0).cause().orElseThrow());
		Assertions.assertTrue(group.isBarrierComplete());

		Throwable late = new AssertionError("late");
		group.signalFailure(root, late);
		Assertions.assertSame(late, group.primaryEventsInSequence().stream()
				.filter(event -> event.member() == root)
				.filter(event -> event.type() == InternalTerminationEvent.Type.FAILURE)
				.findFirst().orElseThrow().cause().orElseThrow());
	}

	@Test
	void competingFailureDiagnosticIsBoundedAndFreezeAware() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup group = new InternalTerminationGroup(
				fence, () -> {}, workers);
		InternalTerminationGroup.Member root = group.root();
		Throwable primary = new AssertionError("primary");
		Throwable secondary = new AssertionError("secondary");
		Throwable capped = new AssertionError("capped");
		Throwable late = new AssertionError("late");

		group.signalFailure(root, primary);
		group.commit();
		Assertions.assertTrue(group.trySuppressFailureBeforeFreeze(
				root, primary, secondary));
		Assertions.assertFalse(group.trySuppressFailureBeforeFreeze(
				root, primary, capped));
		Assertions.assertArrayEquals(new Throwable[] { secondary },
				primary.getSuppressed());
		Assertions.assertEquals(1, group.primaryEventsInSequence().size());
		Assertions.assertSame(primary, group.primaryEventsInSequence().get(0)
				.cause().orElseThrow());

		group.freezeEvidence();
		Throwable[] frozenSuppressed = primary.getSuppressed();
		Assertions.assertFalse(group.trySuppressFailureBeforeFreeze(
				root, primary, late));
		Assertions.assertArrayEquals(frozenSuppressed, primary.getSuppressed());
		Assertions.assertEquals(1, group.primaryEventsInSequence().size());
		Assertions.assertSame(primary, group.primaryEventsInSequence().get(0)
				.cause().orElseThrow());
	}

	@Test
	void subtreeProofWaitsForCommitDescendantsAdmittedWorkAndTrackedCalls() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup group = new InternalTerminationGroup(fence, () -> {}, workers);
		InternalTerminationGroup.Member branch = group.registerChild(group.root());
		InternalTerminationGroup.Member descendant = group.registerChild(branch);
		CompletionStage<Void> branchProof = group.subtreeProofStage(branch);
		AdmissionFence.Admission admission = fence.tryAdmit().orElseThrow();
		InternalTerminationGroup.TrackedLifecycleCall tracked = group.trackLifecycleCall();
		group.signalTerminated(branch);
		group.signalTerminated(descendant);
		Assertions.assertFalse(branchProof.toCompletableFuture().isDone());
		group.commit();
		Assertions.assertFalse(branchProof.toCompletableFuture().isDone());
		admission.close();
		Assertions.assertFalse(branchProof.toCompletableFuture().isDone());
		tracked.close();
		Assertions.assertEquals(1, launcher.size());
		launcher.remove().run();
		Assertions.assertTrue(branchProof.toCompletableFuture().isDone());
	}

	@Test
	void subtreeProofReevaluatesWhenAdmittedWorkIsTheLastBarrier() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup group = new InternalTerminationGroup(
				fence, () -> {}, workers);
		InternalTerminationGroup.Member child = group.registerChild(group.root());
		CompletionStage<Void> childProof = group.subtreeProofStage(child);
		AdmissionFence.Admission admission = fence.tryAdmit().orElseThrow();
		group.signalTerminated(child);
		group.commit();

		Assertions.assertFalse(childProof.toCompletableFuture().isDone());
		Assertions.assertEquals(0, launcher.size());
		admission.close();
		Assertions.assertEquals(1, launcher.size(),
				"The admission zero transition must schedule the ready proof handoff");
		launcher.remove().run();
		Assertions.assertTrue(childProof.toCompletableFuture().isDone());
	}

	@Test
	void admittedWorkZeroReevaluatesEveryGroupSharingTheFence() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		AdmissionFence fence = new AdmissionFence();
		InternalTerminationGroup first = new InternalTerminationGroup(
				fence, () -> {}, workers);
		InternalTerminationGroup second = new InternalTerminationGroup(
				fence, () -> {}, workers);
		CompletionStage<Void> firstProof = first.subtreeProofStage(first.root());
		CompletionStage<Void> secondProof = second.subtreeProofStage(second.root());
		AdmissionFence.Admission admission = fence.tryAdmit().orElseThrow();
		first.signalTerminated(first.root());
		second.signalTerminated(second.root());
		first.commit();
		second.commit();

		Assertions.assertEquals(0, launcher.size());
		admission.close();
		Assertions.assertEquals(2, launcher.size());
		launcher.remove().run();
		launcher.remove().run();
		Assertions.assertTrue(firstProof.toCompletableFuture().isDone());
		Assertions.assertTrue(secondProof.toCompletableFuture().isDone());
	}

	@Test
	void proofHandoffLaunchFailureIsContainedWithoutCorruptingCommit() {
		IllegalStateException launchFailure =
				new IllegalStateException("proof handoff launch failed");
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			throw launchFailure;
		});
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> {}, workers);
		InternalTerminationGroup.Member child = group.registerChild(group.root());
		CompletionStage<Void> childProof = group.subtreeProofStage(child);
		group.signalTerminated(group.root());
		group.signalTerminated(child);

		Assertions.assertDoesNotThrow(group::commit);
		Assertions.assertTrue(group.isBarrierComplete(),
				"Proof handoff infrastructure is outside the participant barrier");
		Assertions.assertFalse(childProof.toCompletableFuture().isDone());
		Assertions.assertTrue(group.primaryEventsInSequence().stream()
				.flatMap(event -> event.cause().stream())
				.anyMatch(cause -> cause == launchFailure));
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.SUBTREE_PROOF_HANDOFF));
		Assertions.assertThrows(IllegalStateException.class,
				() -> group.registerChild(group.root()),
				"A contained launch failure must not roll commit back into OPEN");
	}

	@Test
	void discardedGroupLeavesAcquiredProofInertAndCreatesNoWorker() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> {}, workers);
		InternalTerminationGroup.Member child = group.registerChild(group.root());
		CompletionStage<Void> view = group.subtreeProofStage(child);
		group.signalTerminated(child);
		group.discard();
		Assertions.assertFalse(view.toCompletableFuture().isDone());
		Assertions.assertEquals(0, launcher.size());
		Assertions.assertThrows(IllegalStateException.class,
				() -> group.subtreeProofStage(child));
	}

	@Test
	void mediatorEnforcesUnarySameThreadDynamicExtentAndSlotConsumption() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		StartupContext startup = unboundedStartup();
		AdmissionFence fence = new AdmissionFence();
		AtomicReference<InternalTransportAttachmentContext<String>> captured =
				new AtomicReference<>();
		AtomicReference<InternalTransportEndpoint<String>> childRef = new AtomicReference<>();
		InternalTransportEndpoint<String> child = endpoint(identity, (context, ignored) -> {
			throw new IllegalArgumentException("child failed");
		});
		childRef.set(child);
		InternalTransportEndpoint<String> outer = endpoint(identity, (context, ignored) -> {
			captured.set(context);
			Assertions.assertThrows(IllegalArgumentException.class, () ->
					context.attachLifecycleOwningDelegate(childRef.get(), "wrapped"));
			IllegalStateException second = Assertions.assertThrows(IllegalStateException.class,
					() -> context.attachTransparentDelegate(null, null));
			Assertions.assertEquals("Transport attachment context already delegated",
					second.getMessage());
			return noopRuntime();
		});
		InternalTransportAttachmentSession<String> session =
				new InternalTransportAttachmentSession<>(new Object(), "root", identity,
						startup, fence, () -> {}, workers);
		Assertions.assertSame(noopRuntime().getClass(), session.attach(outer).getClass());
		Assertions.assertEquals(2, session.group().memberCount());
		Assertions.assertTrue(session.group().hasFailure());
		IllegalStateException inactive = Assertions.assertThrows(IllegalStateException.class,
				() -> captured.get().attachTransparentDelegate(child, "late"));
		Assertions.assertEquals(
				"Transport delegate attachment is not active on this attach thread",
				inactive.getMessage());
	}

	@Test
	void transparentDelegationPreservesExactSignalAndOwningDelegationForksIt() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		AtomicReference<InternalTransportTerminationSignal> rootSignal =
				new AtomicReference<>();
		AtomicReference<InternalTransportTerminationSignal> transparentSignal =
				new AtomicReference<>();
		AtomicReference<InternalTransportTerminationSignal> owningSignal =
				new AtomicReference<>();

		InternalTransportEndpoint<String> leaf = endpoint(identity, (context, startup) -> {
			owningSignal.set(context.terminationSignal());
			Assertions.assertSame(owningSignal.get(), context.terminationSignal());
			return noopRuntime();
		});
		InternalTransportEndpoint<String> transparent = endpoint(identity,
				(context, startup) -> {
					transparentSignal.set(context.terminationSignal());
					context.attachLifecycleOwningDelegate(leaf, "leaf-handler");
					return noopRuntime();
				});
		InternalTransportEndpoint<String> outer = endpoint(identity, (context, startup) -> {
			rootSignal.set(context.terminationSignal());
			return context.attachTransparentDelegate(transparent,
					"transparent-handler");
		});

		InternalTransportAttachmentSession<String> session =
				new InternalTransportAttachmentSession<>(new Object(), "root-handler",
						identity, unboundedStartup(), new AdmissionFence(), () -> {}, workers);
		Assertions.assertNotNull(session.attach(outer));
		Assertions.assertSame(rootSignal.get(), transparentSignal.get());
		Assertions.assertNotSame(rootSignal.get(), owningSignal.get());
		Assertions.assertEquals(2, session.group().memberCount());
	}

	@Test
	void mediatorPreflightIsOrderedAndDoesNotConsumeTheSlot() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		InternalTransportIdentity foreignIdentity = InternalTransportIdentity.create();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		AtomicInteger successfulAttachCalls = new AtomicInteger();
		InternalTransportEndpoint<String> valid = endpoint(identity, (context, startup) -> {
			successfulAttachCalls.incrementAndGet();
			return noopRuntime();
		});
		RuntimeException getterFailure = new IllegalStateException("identity getter failed");
		InternalTransportEndpoint<String> throwingIdentity = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				throw getterFailure;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				throw new AssertionError("Preflight must not invoke attach");
			}
		};
		InternalTransportEndpoint<String> nullIdentity = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			@SuppressWarnings("NullAway")
			public InternalTransportIdentity identity() {
				return null;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				throw new AssertionError("Preflight must not invoke attach");
			}
		};
		InternalTransportEndpoint<String> mismatch = endpoint(foreignIdentity,
				(context, startup) -> {
					throw new AssertionError("Preflight must not invoke attach");
				});
		AtomicInteger secondGetterCalls = new AtomicInteger();
		InternalTransportEndpoint<String> second = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				secondGetterCalls.incrementAndGet();
				return identity;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				throw new AssertionError("Consumed slot must not invoke attach");
			}
		};
		InternalTransportEndpoint<String> outer = endpoint(identity, (context, startup) -> {
			NullPointerException nullDelegate = Assertions.assertThrows(
					NullPointerException.class,
					() -> context.attachTransparentDelegate(null, "handler"));
			Assertions.assertEquals("delegate", nullDelegate.getMessage());
			NullPointerException nullHandler = Assertions.assertThrows(
					NullPointerException.class,
					() -> context.attachTransparentDelegate(valid, null));
			Assertions.assertEquals("delegateRequestHandler", nullHandler.getMessage());
			Assertions.assertSame(getterFailure, Assertions.assertThrows(
					RuntimeException.class,
					() -> context.attachTransparentDelegate(throwingIdentity, "handler")));
			NullPointerException nullToken = Assertions.assertThrows(
					NullPointerException.class,
					() -> context.attachTransparentDelegate(nullIdentity, "handler"));
			Assertions.assertEquals("delegate.getTransportIdentity()",
					nullToken.getMessage());
			IllegalArgumentException mismatchFailure = Assertions.assertThrows(
					IllegalArgumentException.class,
					() -> context.attachTransparentDelegate(mismatch, "handler"));
			Assertions.assertEquals(
					"Delegate transport identity does not match the configured transport graph",
					mismatchFailure.getMessage());

			InternalTransportRuntime runtime = context.attachTransparentDelegate(
					valid, "handler");
			Assertions.assertNotNull(runtime);
			IllegalStateException consumed = Assertions.assertThrows(
					IllegalStateException.class,
					() -> context.attachTransparentDelegate(second, null));
			Assertions.assertEquals("Transport attachment context already delegated",
					consumed.getMessage());
			return noopRuntime();
		});

		InternalTransportAttachmentSession<String> session =
				new InternalTransportAttachmentSession<>(new Object(), "root", identity,
						unboundedStartup(), new AdmissionFence(), () -> {}, workers);
		Assertions.assertNotNull(session.attach(outer));
		Assertions.assertEquals(1, successfulAttachCalls.get());
		Assertions.assertEquals(0, secondGetterCalls.get());
	}

	@Test
	void mediatorRejectsForeignAndReentrantCallsBeforeGetterEvaluation() {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		AtomicInteger forbiddenGetterCalls = new AtomicInteger();
		InternalTransportEndpoint<String> forbidden = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				forbiddenGetterCalls.incrementAndGet();
				return identity;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				throw new AssertionError("Rejected mediation must not invoke attach");
			}
		};
		AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
		AtomicReference<InternalTransportAttachmentContext<String>> contextRef =
				new AtomicReference<>();
		InternalTransportEndpoint<String> reentrantIdentity = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				IllegalStateException failure = Assertions.assertThrows(
						IllegalStateException.class,
						() -> contextRef.get().attachTransparentDelegate(
								forbidden, "reentrant"));
				Assertions.assertEquals(
						"Transport delegate attachment is not active on this attach thread",
						failure.getMessage());
				return identity;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				return noopRuntime();
			}
		};
		InternalTransportEndpoint<String> outer = endpoint(identity, (context, startup) -> {
			contextRef.set(context);
			Thread foreign = new Thread(() -> {
				try {
					context.attachTransparentDelegate(forbidden, "foreign");
				} catch (Throwable throwable) {
					foreignFailure.set(throwable);
				}
			}, "foreign-attachment-test");
			foreign.start();
			try {
				foreign.join();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
			Assertions.assertInstanceOf(IllegalStateException.class,
					foreignFailure.get());
			Assertions.assertNotNull(context.attachTransparentDelegate(
					reentrantIdentity, "same-thread"));
			return noopRuntime();
		});

		InternalTransportAttachmentSession<String> session =
				new InternalTransportAttachmentSession<>(new Object(), "root", identity,
						unboundedStartup(), new AdmissionFence(), () -> {}, workers);
		Assertions.assertNotNull(session.attach(outer));
		Assertions.assertEquals(0, forbiddenGetterCalls.get());
	}

	@Test
	void cancellationDuringIdentityPreflightConsumesNoSlotOrChildMember()
			throws Exception {
		InternalTransportIdentity identity = InternalTransportIdentity.create();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		CountDownLatch identityEntered = new CountDownLatch(1);
		CountDownLatch releaseIdentity = new CountDownLatch(1);
		AtomicInteger childAttachCalls = new AtomicInteger();
		InternalTransportEndpoint<String> child = new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				identityEntered.countDown();
				awaitUninterruptibly(releaseIdentity);
				return identity;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				childAttachCalls.incrementAndGet();
				return noopRuntime();
			}
		};
		InternalTransportEndpoint<String> outer = endpoint(identity, (context, startup) -> {
			context.attachLifecycleOwningDelegate(child, "child-handler");
			return noopRuntime();
		});
		InternalTransportAttachmentSession<String> session =
				new InternalTransportAttachmentSession<>(new Object(), "root-handler",
						identity, unboundedStartup(), new AdmissionFence(), () -> {}, workers);
		AtomicReference<Throwable> attachmentFailure = new AtomicReference<>();
		Thread attachment = new Thread(() -> {
			try {
				session.attach(outer);
			} catch (Throwable throwable) {
				attachmentFailure.set(throwable);
			}
		}, "cancelled-transport-attachment");
		attachment.start();
		Assertions.assertTrue(identityEntered.await(2, TimeUnit.SECONDS));
		session.group().discard();
		releaseIdentity.countDown();
		attachment.join();

		IllegalStateException failure = Assertions.assertInstanceOf(
				IllegalStateException.class, attachmentFailure.get());
		Assertions.assertEquals(
				"Transport delegate attachment is not active on this attach thread",
				failure.getMessage());
		Assertions.assertEquals(0, childAttachCalls.get());
		Assertions.assertEquals(1, session.group().memberCount(),
				"Cancellation that wins final preflight must not register a child");
	}

	@Test
	void signalAndCommitRaceLinearizesExactlyOnceAndRejectsLateChildren()
			throws Exception {
		for (int iteration = 0; iteration < 50; iteration++) {
			LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
			AdmissionFence fence = new AdmissionFence();
			InternalTerminationGroup group = new InternalTerminationGroup(
					fence, () -> {}, workers);
			InternalTerminationGroup.Member root = group.root();
			CountDownLatch start = new CountDownLatch(1);
			Thread commit = new Thread(() -> {
				awaitUninterruptibly(start);
				group.commit();
			});
			Thread signal = new Thread(() -> {
				awaitUninterruptibly(start);
				group.signalTerminated(root);
			});
			commit.start();
			signal.start();
			start.countDown();
			commit.join();
			signal.join();

			Assertions.assertFalse(fence.isOpen());
			Assertions.assertEquals(1, group.primaryEventsInSequence().size());
			Assertions.assertTrue(group.isBarrierComplete());
			Assertions.assertThrows(IllegalStateException.class,
					() -> group.registerChild(root));
		}
	}

	@Test
	void workerBoundsAreFiniteAndRolesRemainIsolated() {
		QueuedLauncher launcher = new QueuedLauncher();
		LifecycleWorkers workers = new LifecycleWorkers(launcher);
		for (int index = 0; index < 24; index++)
			workers.start(LifecycleWorkers.Role.LIFECYCLE_CALL,
					"bounded-call-" + index, () -> {});
		Assertions.assertThrows(IllegalStateException.class, () ->
				workers.start(LifecycleWorkers.Role.LIFECYCLE_CALL,
						"over-bound", () -> {}));
		workers.start(LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF,
				"isolated-handoff", () -> {});
		Assertions.assertEquals(24,
				workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
		Assertions.assertEquals(1,
				workers.active(LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));
		while (launcher.size() > 0)
			launcher.remove().run();
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL));
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF));
	}

	@Test
	void identityClaimsAreAtomicPermanentAndConservativeWhileTokenIsRetained() {
		TransportIdentityClaimRegistry registry = new TransportIdentityClaimRegistry();
		InternalTransportIdentity first = InternalTransportIdentity.create();
		InternalTransportIdentity second = InternalTransportIdentity.create();
		InternalTransportIdentity fresh = InternalTransportIdentity.create();
		Assertions.assertNotSame(first, second);
		Object owner = new Object();
		registry.claimAll(List.of(first, second), owner);
		Assertions.assertThrows(IllegalStateException.class, () ->
				registry.claimAll(List.of(fresh, second), new Object()));
		registry.claimAll(List.of(fresh), new Object());
		Assertions.assertThrows(IllegalStateException.class, () ->
				registry.claimAll(List.of(first), new Object()),
				"A still-retained token remains conservatively claimed");
	}

	@Test
	void retentionSummaryEscapesControlsCapsOutputAndNeverTraversesGraph() {
		Object graph = new Object() {
			@Override
			public String toString() {
				throw new AssertionError("Retention diagnostics must not stringify runtime graphs");
			}
		};
		String frameworkSummary = "line\n" + "x".repeat(2_000);
		LifecycleRetentionAnchor anchor = new LifecycleRetentionAnchor(graph,
				Map.of(InternalResidualActivityType.EVENT_LOOP, 1), frameworkSummary);
		LifecycleRetentionSummary summary = LifecycleRetentionDiagnostics.read(anchor);
		Assertions.assertTrue(anchor.retains(graph));
		Assertions.assertTrue(summary.summary().startsWith("line\\n"));
		Assertions.assertTrue(summary.summary().codePointCount(0,
				summary.summary().length()) <= 1_024);
		Assertions.assertEquals(1,
				summary.counts().get(InternalResidualActivityType.EVENT_LOOP));
	}

	@Test
	void lifecycleExecutionMarkerIsNestedAndRestored() {
		Assertions.assertFalse(LifecycleExecutionContext.isMarked());
		try (LifecycleExecutionContext.Scope ignored = LifecycleExecutionContext.enter()) {
			Assertions.assertTrue(LifecycleExecutionContext.isMarked());
			Assertions.assertThrows(IllegalStateException.class,
					LifecycleExecutionContext::requireNonReentrantWait);
			try (LifecycleExecutionContext.Scope ignoredNested =
						 LifecycleExecutionContext.enter()) {
				Assertions.assertTrue(LifecycleExecutionContext.isMarked());
			}
		}
		Assertions.assertFalse(LifecycleExecutionContext.isMarked());
	}

	@Test
	void lifecycleExecutionMarkerIsSpecificToTheExactOwnerToken() {
		Object firstOwner = new Object();
		Object secondOwner = new Object();
		Assertions.assertFalse(LifecycleExecutionContext.isMarked(firstOwner));
		Assertions.assertFalse(LifecycleExecutionContext.isMarked(secondOwner));

		try (LifecycleExecutionContext.Scope ignored =
					 LifecycleExecutionContext.enter(firstOwner)) {
			Assertions.assertTrue(LifecycleExecutionContext.isMarked(firstOwner));
			Assertions.assertFalse(LifecycleExecutionContext.isMarked(secondOwner));
			Assertions.assertThrows(IllegalStateException.class,
					() -> LifecycleExecutionContext.requireNonReentrantWait(firstOwner));
			Assertions.assertDoesNotThrow(
					() -> LifecycleExecutionContext.requireNonReentrantWait(secondOwner));
			try (LifecycleExecutionContext.Scope ignoredNested =
						 LifecycleExecutionContext.enter(secondOwner)) {
				Assertions.assertTrue(LifecycleExecutionContext.isMarked(firstOwner));
				Assertions.assertTrue(LifecycleExecutionContext.isMarked(secondOwner));
			}
			Assertions.assertTrue(LifecycleExecutionContext.isMarked(firstOwner));
			Assertions.assertFalse(LifecycleExecutionContext.isMarked(secondOwner));
		}
		Assertions.assertFalse(LifecycleExecutionContext.isMarked(firstOwner));
		Assertions.assertFalse(LifecycleExecutionContext.isMarked(secondOwner));
	}

	@Test
	void trackedLifecycleCallsCarryTheirTerminationGroupOwnerMarker() {
		Object owner = new Object();
		Object foreignOwner = new Object();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> runnable.run());
		InternalTerminationGroup group = new InternalTerminationGroup(
				new AdmissionFence(), () -> {}, workers, owner);
		AtomicBoolean exactOwnerMarked = new AtomicBoolean();
		AtomicBoolean foreignOwnerMarked = new AtomicBoolean(true);

		TrackedLifecycleCallRunner.Call<Void> call =
				new TrackedLifecycleCallRunner(workers).submit("owner-marker", group, () -> {
					exactOwnerMarked.set(LifecycleExecutionContext.isMarked(owner));
					foreignOwnerMarked.set(LifecycleExecutionContext.isMarked(foreignOwner));
					return null;
				});
		call.completion().toCompletableFuture().join();

		Assertions.assertTrue(exactOwnerMarked.get());
		Assertions.assertFalse(foreignOwnerMarked.get());
		Assertions.assertFalse(LifecycleExecutionContext.isMarked(owner));
	}

	@Test
	void blockedLifecycleCallDoesNotPreventAnotherParticipantPhaseSubmission()
			throws Exception {
		LifecycleWorkers workers = new LifecycleWorkers();
		TrackedLifecycleCallRunner runner = new TrackedLifecycleCallRunner(workers);
		DeadlineWaiter waiter = new DeadlineWaiter(NanoClock.system());
		InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
				NanoClock.system(), waiter, runner);
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		TestParticipant first = new TestParticipant(InternalLifecycleComponentType.HTTP,
				workers, waiter, () -> {
					firstEntered.countDown();
					awaitUninterruptibly(releaseFirst);
				});
		TestParticipant second = new TestParticipant(InternalLifecycleComponentType.SSE,
				workers, waiter, secondEntered::countDown);
		long grace = LifecycleDeadlines.after(System.nanoTime(), Duration.ofSeconds(2));
		long forced = LifecycleDeadlines.after(grace, Duration.ofSeconds(1));
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread coordinatorThread = new Thread(() -> {
			try {
				coordinator.shutdown(List.of(first, second), grace, forced);
			} catch (Throwable throwable) {
				failure.set(throwable);
			}
		}, "foundation-coordinator-test");
		coordinatorThread.start();
		Assertions.assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
		Assertions.assertTrue(secondEntered.await(1, TimeUnit.SECONDS),
				"The second quiesce must be submitted while the first remains blocked");
		releaseFirst.countDown();
		first.prove();
		second.prove();
		coordinatorThread.join();
		Assertions.assertNull(failure.get());
	}

	@RepeatedTest(25)
	void graceExpiryCancelsBlockedQuiesceBeforeSubmittingForce(
			@NonNull RepetitionInfo repetitionInfo)
			throws Exception {
		assertGraceExpiryCancelsBlockedQuiesceBeforeForce(
				repetitionInfo.getCurrentRepetition());
	}

	private void assertGraceExpiryCancelsBlockedQuiesceBeforeForce(int iteration)
			throws Exception {
		AtomicLong now = new AtomicLong();
		AtomicInteger waitCount = new AtomicInteger();
		CountDownLatch quiesceEntered = new CountDownLatch(1);
		CountDownLatch quiesceInterrupted = new CountDownLatch(1);
		CountDownLatch quiesceWrapperExited = new CountDownLatch(1);
		CountDownLatch neverReleased = new CountDownLatch(1);
		AtomicBoolean forceObservedInterruption = new AtomicBoolean();
		AtomicReference<Thread> quiesceWorker = new AtomicReference<>();
		List<String> events = new CopyOnWriteArrayList<>();
		DeadlineWaiter waiter = new DeadlineWaiter(now::get, (monitor, remaining) -> {
			waitCount.incrementAndGet();
			quiesceEntered.await();
			now.set(10L);
		});
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("lifecycle-quiesce-http")) {
				Thread worker = new Thread(() -> {
					try {
						runnable.run();
					} finally {
						events.add("quiesce-call-exited");
						quiesceWrapperExited.countDown();
					}
				}, "foundation-blocked-quiesce-" + iteration);
				worker.setDaemon(true);
				quiesceWorker.set(worker);
				worker.start();
				return;
			}
			if (name.equals("lifecycle-force-http")) {
				awaitUninterruptibly(quiesceWrapperExited);
				events.add("force-launch");
				runnable.run();
				return;
			}
			runnable.run();
		});
		TrackedLifecycleCallRunner runner = new TrackedLifecycleCallRunner(workers);
		AdmissionFence fence = new AdmissionFence(waiter::signal);
		InternalTerminationGroup group = new InternalTerminationGroup(
				fence, waiter::signal, workers);
		group.commit();
		InternalTransportRuntime runtime = new InternalTransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) {
			}

			@Override
			public void quiesce(@NonNull ShutdownContext context) {
				events.add("quiesce-enter");
				quiesceEntered.countDown();
				try {
					neverReleased.await();
				} catch (InterruptedException expected) {
					events.add("quiesce-interrupted");
					quiesceInterrupted.countDown();
				}
			}

			@Override
			public void force(@NonNull ShutdownContext context) {
				events.add("force-enter");
				forceObservedInterruption.set(
						quiesceInterrupted.getCount() == 0L
								&& quiesceWrapperExited.getCount() == 0L);
				group.signalTerminated(group.root());
			}
		};
		InternalLifecycleCoordinator.Participant participant =
				new InternalLifecycleCoordinator.Participant() {
					@Override
					public @NonNull InternalLifecycleComponentType kind() {
						return InternalLifecycleComponentType.HTTP;
					}

					@Override
					public @NonNull AdmissionFence admissionFence() {
						return fence;
					}

					@Override
					public @NonNull InternalTerminationGroup terminationGroup() {
						return group;
					}

					@Override
					public @NonNull InternalTransportRuntime runtime() {
						return runtime;
					}

					@Override
					public @NonNull Set<InternalResidualActivityType> residualActivity() {
						return Set.of();
					}
				};
		InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
				now::get, waiter, runner);

		InternalShutdownResult result = coordinator.shutdown(
				List.of(participant), 10L, 20L);
		Thread worker = quiesceWorker.get();
		Assertions.assertNotNull(worker);
		worker.join();

		Assertions.assertTrue(forceObservedInterruption.get(),
				"Iteration " + iteration
						+ ": force must follow quiesce cancellation and wrapper exit");
		Assertions.assertEquals(List.of("quiesce-enter", "quiesce-interrupted",
				"quiesce-call-exited", "force-launch", "force-enter"), events,
				"Iteration " + iteration);
		Assertions.assertEquals(1, waitCount.get(), "Iteration " + iteration);
		Assertions.assertEquals(0, group.trackedLifecycleCallCount(),
				"Iteration " + iteration);
		Assertions.assertEquals(0,
				workers.active(LifecycleWorkers.Role.LIFECYCLE_CALL),
				"Iteration " + iteration);
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION,
				result.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow()
						.disposition(), "Iteration " + iteration);
		Assertions.assertTrue(result.participantResult(InternalLifecycleComponentType.HTTP)
				.orElseThrow().residualActivity().isEmpty(), "Iteration " + iteration);
	}

	@Test
	void finalBoundaryCancelsDeferredForceBeforeEvidenceFreeze()
			throws Exception {
		AtomicReference<Runnable> deferredForce = new AtomicReference<>();
		LifecycleWorkers workers = new LifecycleWorkers((name, runnable) -> {
			if (name.equals("lifecycle-force-http")) {
				deferredForce.set(runnable);
			} else {
				runnable.run();
			}
		});
		AtomicLong now = new AtomicLong();
		DeadlineWaiter waiter = new DeadlineWaiter(now::get,
				(monitor, remaining) -> now.addAndGet(remaining));
		AdmissionFence fence = new AdmissionFence(waiter::signal);
		InternalTerminationGroup group = new InternalTerminationGroup(
				fence, waiter::signal, workers);
		group.commit();
		AtomicBoolean forceObservedCancellation = new AtomicBoolean();
		InternalTransportRuntime runtime = new InternalTransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) {
			}

			@Override
			public void quiesce(@NonNull ShutdownContext context) {
			}

			@Override
			public void force(@NonNull ShutdownContext context) {
				forceObservedCancellation.set(Thread.interrupted());
				group.signalTerminated(group.root());
			}
		};
		InternalLifecycleCoordinator.Participant participant =
				new InternalLifecycleCoordinator.Participant() {
					@Override
					public @NonNull InternalLifecycleComponentType kind() {
						return InternalLifecycleComponentType.HTTP;
					}

					@Override
					public @NonNull AdmissionFence admissionFence() {
						return fence;
					}

					@Override
					public @NonNull InternalTerminationGroup terminationGroup() {
						return group;
					}

					@Override
					public @NonNull InternalTransportRuntime runtime() {
						return runtime;
					}

					@Override
					public @NonNull Set<InternalResidualActivityType> residualActivity() {
						Runnable forceTask = deferredForce.getAndSet(null);
						Assertions.assertNotNull(forceTask,
								"Force must be submitted before evidence freezes");
						forceTask.run();
						return Set.of();
					}
				};
		InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
				now::get, waiter, new TrackedLifecycleCallRunner(workers));

		InternalShutdownResult result = coordinator.shutdown(
				List.of(participant), 10L, 20L);

		Assertions.assertTrue(forceObservedCancellation.get(),
				"The unfinished force call must observe cancellation before freeze");
		Assertions.assertEquals(
				InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION,
				result.participantResult(InternalLifecycleComponentType.HTTP).orElseThrow()
						.disposition());
		Assertions.assertTrue(result.participantResult(InternalLifecycleComponentType.HTTP)
				.orElseThrow().residualActivity().isEmpty());
	}

	@NonNull
	private static InternalLifecycleComponentShutdownResult participant(
			@NonNull InternalLifecycleComponentType kind,
			@NonNull InternalLifecycleComponentShutdownDisposition disposition) {
		return new InternalLifecycleComponentShutdownResult(kind, disposition, List.of(), Set.of());
	}

	@NonNull
	private static StartupContext unboundedStartup() {
		return new StartupContext(NanoClock.system(), Optional.empty(),
				Long.MAX_VALUE, () -> false);
	}

	@NonNull
	private static InternalTransportRuntime noopRuntime() {
		return new InternalTransportRuntime() {
			@Override
			public void start(@NonNull StartupContext context) {
			}

			@Override
			public void quiesce(@NonNull ShutdownContext context) {
			}

			@Override
			public void force(@NonNull ShutdownContext context) {
			}
		};
	}

	@NonNull
	private static InternalTransportEndpoint<String> endpoint(
			@NonNull InternalTransportIdentity identity,
			@NonNull AttachFunction attachFunction) {
		return new InternalTransportEndpoint<>() {
			@Override
			@NonNull
			public InternalTransportIdentity identity() {
				return identity;
			}

			@Override
			@NonNull
			public InternalTransportRuntime attach(
					@NonNull InternalTransportAttachmentContext<String> context,
					@NonNull StartupContext startupContext) {
				return attachFunction.attach(context, startupContext);
			}
		};
	}

	@FunctionalInterface
	private interface AttachFunction {
		@NonNull
		InternalTransportRuntime attach(
				@NonNull InternalTransportAttachmentContext<String> context,
				@NonNull StartupContext startupContext);
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

	private static final class QueuedLauncher implements LifecycleWorkers.Launcher {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public synchronized void launch(@NonNull String name, @NonNull Runnable runnable) {
			this.tasks.addLast(runnable);
		}

		synchronized int size() {
			return this.tasks.size();
		}

		@NonNull
		synchronized Runnable remove() {
			return this.tasks.removeFirst();
		}
	}

	private static final class TestParticipant
			implements InternalLifecycleCoordinator.Participant {
		private final InternalLifecycleComponentType kind;
		private final AdmissionFence fence;
		private final InternalTerminationGroup group;
		private final Runnable quiesce;
		private final InternalTransportRuntime runtime;

		private TestParticipant(InternalLifecycleComponentType kind, LifecycleWorkers workers,
				DeadlineWaiter waiter, Runnable quiesce) {
			this.kind = kind;
			this.fence = new AdmissionFence(waiter::signal);
			this.group = new InternalTerminationGroup(this.fence, waiter::signal, workers);
			this.group.commit();
			this.quiesce = quiesce;
			this.runtime = new InternalTransportRuntime() {
				@Override
				public void start(@NonNull StartupContext context) {
				}

				@Override
				public void quiesce(@NonNull ShutdownContext context) {
					TestParticipant.this.quiesce.run();
				}

				@Override
				public void force(@NonNull ShutdownContext context) {
					TestParticipant.this.quiesce.run();
				}
			};
		}

		void prove() {
			this.group.signalTerminated(this.group.root());
		}

		@Override
		public InternalLifecycleComponentType kind() {
			return this.kind;
		}

		@Override
		public AdmissionFence admissionFence() {
			return this.fence;
		}

		@Override
		public InternalTerminationGroup terminationGroup() {
			return this.group;
		}

		@Override
		public InternalTransportRuntime runtime() {
			return this.runtime;
		}

		@Override
		public Set<InternalResidualActivityType> residualActivity() {
			return Set.of();
		}
	}
}
