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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Package-private owner of one direct Soklet generation.  Public lifecycle
 * methods remain descriptor-preserving adapters until D2.
 */
@ThreadSafe
final class SokletDirectLifecycle {
	@NonNull
	private static final TransportIdentityClaimRegistry IDENTITY_CLAIMS =
			new TransportIdentityClaimRegistry();
	@NonNull
	private static final LegacyTransportIdentities LEGACY_IDENTITIES =
			new LegacyTransportIdentities();

	@NonNull
	private final Soklet soklet;
	@NonNull
	private final SokletConfig config;
	@NonNull
	private final SokletFrameworkSetup frameworkSetup;
	@NonNull
	private final NanoClock clock;
	@NonNull
	private final DeadlineWaiter waiter;
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final TrackedLifecycleCallRunner callRunner;
	@NonNull
	private final InternalLifecycleCoordinator coordinator;
	@NonNull
	private final InternalLifecycleStateMachine stateMachine;
	@NonNull
	private final InternalLifecycleCompletion completion;
	@NonNull
	private final LifecycleTransitionDispatcher transitions;
	private final long lifecycleBeganNanos;
	@NonNull
	private final AtomicReference<InternalLifecycleCoreSnapshot> terminalCoreSnapshot;
	@NonNull
	private final Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher;
	@NonNull
	private final Object executionOwnerToken;
	@NonNull
	private final Object startMonitor;
	private long startSignalEpoch;
	@NonNull
	private final Object attachmentMonitor;
	@NonNull
	private final List<ParticipantControl> controls;
	@NonNull
	private final List<DirectParticipant> participants;
	@NonNull
	private final AtomicReference<List<DirectParticipant>> installedParticipants;
	@NonNull
	private final AtomicReference<TrackedLifecycleCallRunner.@Nullable Call<?>> activeStartupCall;
	@NonNull
	private final AtomicReference<InternalLifecycleCoordinator.@Nullable Participant>
			activeStartupParticipant;
	@NonNull
	private final AtomicReference<@Nullable Throwable> cancellationCause;
	@NonNull
	private final AtomicReference<@Nullable Throwable> shutdownIntentFailure;
	@NonNull
	private final AtomicReference<@Nullable Long> startupCancellationDeadlineNanos;
	@NonNull
	private final AtomicReference<@Nullable ShutdownSchedule> shutdownSchedule;
	@NonNull
	private final AtomicReference<@Nullable Throwable> startupFailure;
	@NonNull
	private final AtomicReference<InternalStartupDisposition> startupDisposition;
	@NonNull
	private final AtomicBoolean readyPublished;
	@NonNull
	private final AtomicBoolean terminalPublicationClaimed;
	@NonNull
	private final AtomicBoolean shutdownTransitionsSubmitted;
	@NonNull
	private final AtomicBoolean attachmentWindowOpen;
	@NonNull
	private final AtomicReference<@Nullable UnexpectedTerminationClaim>
			unexpectedTermination;
	@NonNull
	private final InternalControllingEventElection controllingEventElection;
	@Nullable
	private volatile UnexpectedTerminationClaim terminalUnexpectedTermination;
	@NonNull
	private final AtomicReference<@Nullable StartupStopReason> startupStopReason;
	@NonNull
	private final Runnable readyLinearizationHook;
	@NonNull
	private final Runnable afterFirstShutdownIntentPublished;
	@NonNull
	private final Runnable afterAttachmentSettled;
	private final boolean transitionObservationEnabled;

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup) {
		this(soklet, config, frameworkSetup, LifecycleRuntimeServices.system(),
				ignored -> { });
	}

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher) {
		this(soklet, config, frameworkSetup, services, () -> { }, () -> { },
				() -> { }, () -> { }, coreSnapshotPublisher);
	}

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull NanoClock clock, @NonNull LifecycleWorkers workers) {
		this(soklet, config, frameworkSetup, clock, workers, () -> { });
	}

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull NanoClock clock, @NonNull LifecycleWorkers workers,
			@NonNull Runnable readyLinearizationHook) {
		this(soklet, config, frameworkSetup, clock, workers,
				readyLinearizationHook, () -> { }, () -> { });
	}

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull NanoClock clock, @NonNull LifecycleWorkers workers,
			@NonNull Runnable readyLinearizationHook,
			@NonNull Runnable afterFirstShutdownIntentPublished,
			@NonNull Runnable completionWaitHook) {
		this(soklet, config, frameworkSetup, clock, workers,
				readyLinearizationHook, afterFirstShutdownIntentPublished,
				completionWaitHook, () -> { });
	}

	SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull NanoClock clock, @NonNull LifecycleWorkers workers,
			@NonNull Runnable readyLinearizationHook,
			@NonNull Runnable afterFirstShutdownIntentPublished,
			@NonNull Runnable completionWaitHook,
			@NonNull Runnable afterAttachmentSettled) {
		this(soklet, config, frameworkSetup,
				new LifecycleRuntimeServices(clock, workers),
				readyLinearizationHook, afterFirstShutdownIntentPublished,
				completionWaitHook, afterAttachmentSettled, ignored -> { });
	}

	private SokletDirectLifecycle(@NonNull Soklet soklet,
			@NonNull SokletConfig config,
			@NonNull SokletFrameworkSetup frameworkSetup,
			@NonNull LifecycleRuntimeServices services,
			@NonNull Runnable readyLinearizationHook,
			@NonNull Runnable afterFirstShutdownIntentPublished,
			@NonNull Runnable completionWaitHook,
			@NonNull Runnable afterAttachmentSettled,
			@NonNull Consumer<InternalLifecycleCoreSnapshot> coreSnapshotPublisher) {
		this.soklet = requireNonNull(soklet);
		this.config = requireNonNull(config);
		this.frameworkSetup = requireNonNull(frameworkSetup);
		LifecycleRuntimeServices exactServices = requireNonNull(services);
		this.clock = exactServices.clock();
		this.waiter = exactServices.waiter();
		this.workers = exactServices.workers();
		this.callRunner = new TrackedLifecycleCallRunner(this.workers);
		this.coordinator = new InternalLifecycleCoordinator(this.clock, this.waiter,
				this.callRunner);
		this.stateMachine = new InternalLifecycleStateMachine();
		this.completion = new InternalLifecycleCompletion(this.workers,
				requireNonNull(completionWaitHook));
		this.transitions = new LifecycleTransitionDispatcher(this.workers);
		this.lifecycleBeganNanos = this.clock.nanoTime();
		this.terminalCoreSnapshot = new AtomicReference<>();
		this.coreSnapshotPublisher = requireNonNull(coreSnapshotPublisher);
		this.executionOwnerToken = new Object();
		this.startMonitor = new Object();
		this.attachmentMonitor = new Object();
		this.installedParticipants = new AtomicReference<>(List.of());
		this.activeStartupCall = new AtomicReference<>();
		this.activeStartupParticipant = new AtomicReference<>();
		this.cancellationCause = new AtomicReference<>();
		this.shutdownIntentFailure = new AtomicReference<>();
		this.startupCancellationDeadlineNanos = new AtomicReference<>();
		this.shutdownSchedule = new AtomicReference<>();
		this.startupFailure = new AtomicReference<>();
		this.startupDisposition = new AtomicReference<>(
				InternalStartupDisposition.NOT_ATTEMPTED);
		this.readyPublished = new AtomicBoolean();
		this.terminalPublicationClaimed = new AtomicBoolean();
		this.shutdownTransitionsSubmitted = new AtomicBoolean();
		this.attachmentWindowOpen = new AtomicBoolean(true);
		this.unexpectedTermination = new AtomicReference<>();
		this.controllingEventElection =
				new InternalControllingEventElection();
		this.startupStopReason = new AtomicReference<>();
		this.readyLinearizationHook = requireNonNull(readyLinearizationHook);
		this.afterFirstShutdownIntentPublished = requireNonNull(
				afterFirstShutdownIntentPublished);
		this.afterAttachmentSettled = requireNonNull(afterAttachmentSettled);
		this.transitionObservationEnabled = config.getLifecycleObservers().stream()
				.anyMatch(observer -> observer != LifecycleObserver.defaultInstance());

		List<ParticipantControl> controls = createControls();
		IDENTITY_CLAIMS.claimAllDescriptors(controls.stream()
				.map(control -> new TransportIdentityClaimRegistry.ClaimDescriptor(
						control.identity(), control.kind(), control.transportClass()))
				.toList(), this);
		this.controls = List.copyOf(controls);
		this.participants = controls.stream().map(DirectParticipant::new).toList();
	}

	void start() {
		try {
			this.stateMachine.claimStart();
		} catch (IllegalStateException claimFailure) {
			if (this.stateMachine.shutdownWonNew()) {
				awaitCompletionUninterruptibly();
				throw startupException(this.completion.result().orElseThrow());
			}
			throw claimFailure;
		}
		// Once the start claim linearizes, a racing stop is startup cancellation,
		// not the close-before-start truth-table row.
		this.startupDisposition.set(InternalStartupDisposition.FAILED);
		dispatchStartIntent();

		AtomicReference<CoordinatorOutcome> outcome = new AtomicReference<>();
		try {
			this.workers.start(LifecycleWorkers.Role.COORDINATOR,
					"soklet-lifecycle-coordinator",
					() -> outcome.set(coordinate()),
					() -> publishCoordinatorOutcome(outcome.get()));
		} catch (RuntimeException | Error launchFailure) {
			this.startupFailure.compareAndSet(null, launchFailure);
			this.startupDisposition.set(InternalStartupDisposition.FAILED);
			publishCoordinatorOutcome(failedBeforeCoordination(launchFailure));
		}

		boolean interrupted = false;
		for (;;) {
			if (this.readyPublished.get())
				break;
			Optional<InternalShutdownResult> result = this.completion.result();
			if (result.isPresent()) {
				if (interrupted)
					Thread.currentThread().interrupt();
				throw startupException(result.orElseThrow());
			}
			try {
				synchronized (this.startMonitor) {
					long observedSignalEpoch = this.startSignalEpoch;
					while (!this.readyPublished.get()
							&& this.completion.result().isEmpty()
							&& observedSignalEpoch == this.startSignalEpoch)
						this.startMonitor.wait();
				}
			} catch (InterruptedException exception) {
				interrupted = true;
				this.cancellationCause.compareAndSet(null, exception);
				requestShutdownIntent();
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	void stop() {
		requestShutdownIntent();
		Optional<InternalShutdownResult> completed = this.completion.result();
		if (completed.isEmpty()) {
			if (this.completion.installedResult().isEmpty())
				LifecycleExecutionContext.requireNonReentrantWait(
						this.executionOwnerToken);
			awaitCompletionUninterruptibly();
			completed = this.completion.result();
		}
		throwIfUnsuccessfulShutdown(completed.orElseThrow());
	}

	/**
	 * Publishes shutdown intent without joining and returns the one cached
	 * package-private public-stage draft used by D1 acceptance coverage.
	 */
	@NonNull
	CompletionStage<InternalShutdownResult> shutdown() {
		requestShutdownIntent();
		return this.completion.publicStage();
	}

	void throwIfUnsuccessfulShutdown(
			@NonNull InternalShutdownResult result) {
		UnexpectedTerminationClaim unexpected =
				this.terminalUnexpectedTermination;
		if (result.startupDisposition() == InternalStartupDisposition.READY
				&& unexpected != null)
			throw new SokletTerminatedUnexpectedlyException(
					unexpected.event(), result, unexpected.failure());
		if (!result.isComplete())
			throw new ShutdownIncompleteException(result);
	}

	@NonNull
	Optional<RuntimeException> applicationTerminalFailure(
			@NonNull InternalShutdownResult result) {
		InternalShutdownResult exactResult = requireNonNull(result);
		if (exactResult.startupDisposition() == InternalStartupDisposition.FAILED
				|| exactResult.startupDisposition()
				== InternalStartupDisposition.TIMED_OUT)
			return Optional.of(startupException(exactResult));
		try {
			throwIfUnsuccessfulShutdown(exactResult);
			return Optional.empty();
		} catch (RuntimeException failure) {
			return Optional.of(failure);
		}
	}

	void requestShutdownIntent() {
		requestShutdownIntent(StartupStopReason.CANCELLED);
	}

	private void requestShutdownIntent(@NonNull StartupStopReason reason) {
		this.startupStopReason.compareAndSet(null, requireNonNull(reason));
		InternalLifecycleStateMachine.ShutdownRequest shutdownRequest =
				this.controllingEventElection.publishShutdownIntent(() ->
						this.stateMachine.requestShutdownDetailed(
								this.clock.nanoTime()));
		if (shutdownRequest.firstIntent())
			this.afterFirstShutdownIntentPublished.run();
		freezeAttachmentWindow();
		for (DirectParticipant participant : this.installedParticipants.get()) {
			try {
				participant.control().recordShutdownIntent();
			} catch (RuntimeException | Error failure) {
				retainShutdownIntentFailure(failure);
			}
		}
		InternalLifecycleCoordinator.Participant activeParticipant =
				this.activeStartupParticipant.get();
		if (activeParticipant != null) {
			try {
				activeParticipant.terminationGroup().recordShutdownIntent();
			} catch (RuntimeException | Error failure) {
				retainShutdownIntentFailure(failure);
			}
		}
		TrackedLifecycleCallRunner.Call<?> active = this.activeStartupCall.get();
		if (active != null)
			active.cancel();
		signalStartWaiters();

		if (shutdownRequest.firstIntent()
				&& shutdownRequest.priorState()
				== InternalLifecycleStateMachine.State.NEW)
			publishNotStartedWithoutCoordinator();
	}

	private void requestUnexpectedTermination(@NonNull ParticipantControl control) {
		ParticipantControl exactControl = requireNonNull(control);
		InternalLifecycleCoordinator.Participant activeParticipant =
				this.activeStartupParticipant.get();
		TrackedLifecycleCallRunner.Call<?> activeCall =
				this.activeStartupCall.get();
		if (activeParticipant != null
				&& activeParticipant.terminationGroup()
						== exactControl.terminationGroup()
				&& (activeCall == null || !activeCall.isDone())) {
			// A synchronous tracked-call throw is recorded into the participant
			// group before the call's completion bit.  Let runStartupCall observe
			// both facts together so it can distinguish that throw from an
			// independent premature transport signal.
			this.waiter.signal();
			return;
		}
		Optional<InternalTerminationEvent> controlling = exactControl
				.terminationGroup().controllingEvent();
		if (controlling.isEmpty()) {
			this.waiter.signal();
			return;
		}
		requestUnexpectedTermination(controlling.orElseThrow());
	}

	@NonNull
	private Throwable requestUnexpectedTermination(
			@NonNull InternalTerminationEvent controlling) {
		InternalTerminationEvent observedEvent = requireNonNull(controlling);
		InternalTerminationEvent exactEvent = this.controllingEventElection
				.firstEvent().orElse(observedEvent);
		UnexpectedTerminationClaim claim = retainUnexpectedTerminationClaim(
				exactEvent);
		requestShutdownIntent(StartupStopReason.UNEXPECTED);
		return claim.failure();
	}

	@Nullable
	private UnexpectedTerminationClaim terminalUnexpectedTerminationClaim() {
		Optional<InternalTerminationEvent> event =
				this.controllingEventElection.firstEvent();
		if (event.isEmpty())
			return null;
		return retainUnexpectedTerminationClaim(event.orElseThrow());
	}

	@NonNull
	private UnexpectedTerminationClaim retainUnexpectedTerminationClaim(
			@NonNull InternalTerminationEvent event) {
		InternalTerminationEvent exactEvent = requireNonNull(event);
		UnexpectedTerminationClaim existing = this.unexpectedTermination.get();
		if (existing != null)
			return existing;
		Throwable failure = exactEvent.cause().orElseGet(() ->
				new IllegalStateException(
						"A transport terminated before Soklet shutdown intent"));
		this.unexpectedTermination.compareAndSet(null,
				new UnexpectedTerminationClaim(exactEvent, failure));
		return requireNonNull(this.unexpectedTermination.get());
	}

	private void beginAttachment(@NonNull ParticipantControl control,
			@NonNull InternalStartupContext context) {
		synchronized (this.attachmentMonitor) {
			if (!this.attachmentWindowOpen.get())
				throw cancellationFailure();
			control.beginAttachment(context);
		}
	}

	private boolean settleAttachment(@NonNull ParticipantControl control) {
		synchronized (this.attachmentMonitor) {
			if (this.attachmentWindowOpen.get() && control.installAttached())
				return true;
			try {
				control.discardPendingAttachment();
			} catch (RuntimeException | Error failure) {
				retainShutdownIntentFailure(failure);
			}
			return false;
		}
	}

	private boolean claimAttachmentInvocation(@NonNull ParticipantControl control) {
		synchronized (this.attachmentMonitor) {
			return this.attachmentWindowOpen.get()
					&& control.claimAttachmentInvocation();
		}
	}

	@NonNull
	private List<DirectParticipant> commitInstalledParticipants() throws Throwable {
		synchronized (this.attachmentMonitor) {
			if (!this.attachmentWindowOpen.get()
					|| this.stateMachine.shutdownRequested())
				throw cancellationFailure();
			Throwable failure = null;
			for (ParticipantControl control : this.controls) {
				try {
					control.commit();
				} catch (Throwable throwable) {
					failure = throwable;
					break;
				}
			}
			List<DirectParticipant> installed = this.participants.stream()
					.filter(participant -> participant.control().isCommitted()).toList();
			this.installedParticipants.set(installed);
			if (failure != null)
				throw failure;
			return installed;
		}
	}

	private boolean claimParticipantStart(@NonNull DirectParticipant participant) {
		synchronized (this.attachmentMonitor) {
			return !this.stateMachine.shutdownRequested()
					&& requireNonNull(participant).startRunning()
							.compareAndSet(false, true);
		}
	}

	private void freezeAttachmentWindow() {
		synchronized (this.attachmentMonitor) {
			this.attachmentWindowOpen.set(false);
			for (ParticipantControl control : this.controls) {
				try {
					control.discardPendingAttachment();
				} catch (RuntimeException | Error failure) {
					retainShutdownIntentFailure(failure);
				}
			}
		}
	}

	private long firstShutdownIntentNanos() {
		if (!this.stateMachine.shutdownRequested())
			requestShutdownIntent();
		return this.stateMachine.shutdownIntentNanos();
	}

	private long startupCancellationDeadlineNanos(
			@NonNull InternalLifecyclePolicy policy) {
		Long observed = this.startupCancellationDeadlineNanos.get();
		if (observed != null)
			return observed;
		long calculated = LifecycleDeadlines.after(firstShutdownIntentNanos(),
				requireNonNull(policy).startupCancellationTimeout());
		this.startupCancellationDeadlineNanos.compareAndSet(null, calculated);
		return requireNonNull(this.startupCancellationDeadlineNanos.get());
	}

	private void retainShutdownIntentFailure(@NonNull Throwable failure) {
		Throwable exactFailure = requireNonNull(failure);
		Throwable primary = this.shutdownIntentFailure.get();
		if (primary == null
				&& this.shutdownIntentFailure.compareAndSet(null, exactFailure))
			return;
		primary = requireNonNull(this.shutdownIntentFailure.get());
		addSuppressedIfDistinct(primary, exactFailure);
	}

	private void retainShutdownIntentFailureSafely(@NonNull Throwable failure) {
		try {
			retainShutdownIntentFailure(requireNonNull(failure));
		} catch (Throwable ignored) {
			// Diagnostics are never the only path to lifecycle publication.
		}
	}

	private static void addSuppressedIfDistinct(@NonNull Throwable primary,
			@Nullable Throwable secondary) {
		Throwable exactSecondary = secondary;
		if (exactSecondary == null || primary == exactSecondary)
			return;
		synchronized (primary) {
			if (java.util.Arrays.stream(primary.getSuppressed())
					.noneMatch(candidate -> candidate == exactSecondary))
				primary.addSuppressed(exactSecondary);
		}
	}

	boolean isStarted() {
		return this.readyPublished.get() && this.stateMachine.state()
				== InternalLifecycleStateMachine.State.READY;
	}

	InternalLifecycleStateMachine.State state() {
		return this.stateMachine.state();
	}

	LifecycleExecutionContext.Scope enterExecution() {
		return LifecycleExecutionContext.enter(this.executionOwnerToken);
	}

	@NonNull
	Object executionOwnerToken() {
		return this.executionOwnerToken;
	}

	@NonNull
	InternalShutdownResult awaitCompletion() throws InterruptedException {
		if (this.completion.installedResult().isEmpty())
			LifecycleExecutionContext.requireNonReentrantWait(
					this.executionOwnerToken);
		return this.completion.await();
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		if (this.stateMachine.state()
				!= InternalLifecycleStateMachine.State.CLOSED)
			return Optional.empty();
		return this.completion.installedResult();
	}

	@NonNull
	InternalLifecycleCoreSnapshot terminalCoreSnapshot() {
		return requireNonNull(this.terminalCoreSnapshot.get(),
				"The terminal core snapshot is not published");
	}

	@NonNull
	LifecycleTransitionSnapshot transitionSnapshot() {
		return this.transitions.snapshot();
	}

	@NonNull
	SokletApplicationCoreDiagnostics applicationDiagnostics() {
		EnumMap<InternalParticipantKind,
				SokletApplicationParticipantDiagnostics> diagnostics =
				new EnumMap<>(InternalParticipantKind.class);
		for (ParticipantControl control : this.controls) {
			InternalTerminationGroup.DiagnosticSummary group = null;
			try {
				group = control.terminationGroup().diagnosticSummary();
			} catch (Throwable ignored) {
				// Terminal diagnostics cannot change lifecycle truth.
			}
			InternalTerminationAuthority authority = switch (control.kind()) {
				case MCP -> InternalTerminationAuthority.FRAMEWORK_PROVEN;
				case HTTP -> control.transportClass() == DefaultHttpServer.class
						? InternalTerminationAuthority.FRAMEWORK_PROVEN
						: InternalTerminationAuthority.TRANSPORT_ATTESTED;
				case SSE -> control.transportClass() == DefaultSseServer.class
						? InternalTerminationAuthority.FRAMEWORK_PROVEN
						: InternalTerminationAuthority.TRANSPORT_ATTESTED;
				case FRAMEWORK_STARTUP ->
						InternalTerminationAuthority.FRAMEWORK_PROVEN;
			};
			diagnostics.put(control.kind(),
					new SokletApplicationParticipantDiagnostics(authority,
							group == null ? 0 : group.memberCount(),
							group == null ? 0 : group.failedMembers(),
							group == null ? 0 : group.provenMembers(),
							group != null && group.truncated()));
		}
		InternalLifecycleCoreSnapshot coreSnapshot = this.terminalCoreSnapshot.get();
		if (coreSnapshot != null) {
			for (InternalParticipantShutdownResult participant
					: coreSnapshot.result().participantResults()) {
				if (diagnostics.containsKey(participant.kind()))
					continue;
				boolean proven = participant.disposition()
						!= InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
						&& participant.disposition()
						!= InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN;
				diagnostics.put(participant.kind(),
						new SokletApplicationParticipantDiagnostics(
								InternalTerminationAuthority.FRAMEWORK_PROVEN,
								1, participant.failures().isEmpty() ? 0 : 1,
								proven ? 1 : 0, false));
			}
		}
		return new SokletApplicationCoreDiagnostics(
				this.transitions.snapshot(), diagnostics,
				this.config.getInternalLifecyclePolicy(),
				this.lifecycleBeganNanos);
	}

	/** C's simulator uses the exact same setup/installation path without binding. */
	void initializeForSimulator(@NonNull InternalStartupContext startupContext,
			@NonNull DeadlineWaiter simulatorWaiter) {
		this.frameworkSetup.run(requireNonNull(startupContext),
				requireNonNull(simulatorWaiter));
		installLegacyHandlersOnly();
	}

	@NonNull
	private CoordinatorOutcome coordinate() {
		InternalLifecyclePolicy policy = this.config.getInternalLifecyclePolicy();
		long startupBegan = this.clock.nanoTime();
		Optional<Long> startupDeadline = policy.startupTimeout()
				.map(duration -> LifecycleDeadlines.after(startupBegan, duration));
		InternalStartupContext startupContext = new InternalStartupContext(
				this.clock, startupDeadline,
				() -> startupCancellationDeadlineNanos(policy),
				this.stateMachine::shutdownRequested);
		FrameworkAttempt setupAttempt = new FrameworkAttempt(
				InternalParticipantKind.FRAMEWORK_STARTUP);
		try {
			this.startupDisposition.set(InternalStartupDisposition.FAILED);
			runStartupCall("soklet-framework-setup", setupAttempt,
					() -> this.frameworkSetup.run(startupContext, this.waiter),
					startupDeadline);
			if (this.stateMachine.shutdownRequested())
				throw cancellationFailure();

			for (ParticipantControl control : this.controls) {
				beginAttachment(control, startupContext);
				AttachmentAttempt attempt = new AttachmentAttempt(control.kind());
				runStartupCall("soklet-attach-"
						+ control.kind().name().toLowerCase(Locale.ROOT), attempt,
						() -> {
							if (!claimAttachmentInvocation(control))
								throw cancellationFailure();
						control.attach(startupContext);
						if (!settleAttachment(control))
							throw cancellationFailure();
						this.afterAttachmentSettled.run();
						return null;
						}, startupDeadline);
				attempt.markInstalled();
				if (this.stateMachine.shutdownRequested())
					throw cancellationFailure();
			}

			List<DirectParticipant> installed = commitInstalledParticipants();

			for (DirectParticipant participant : installed) {
				if (!claimParticipantStart(participant))
					throw cancellationFailure();
				dispatchParticipantStartIntent(participant.kind());
				try {
				runStartupCall("soklet-start-"
							+ participant.kind().name().toLowerCase(Locale.ROOT), participant,
							() -> {
								try {
									participant.control().start(startupContext);
								} finally {
									participant.startRunning().set(false);
									participant.catchUpShutdownPhase();
								}
								return null;
							}, startupDeadline);
				} catch (Throwable failure) {
					dispatchParticipantStartFailure(participant.kind(), failure);
					throw failure;
				}
				dispatchParticipantStarted(participant.kind());
				if (this.stateMachine.shutdownRequested())
					throw cancellationFailure();
			}

			Optional<Throwable> preReadyFailure = unexpectedTerminationCause();
			if (preReadyFailure.isPresent())
				throw preReadyFailure.orElseThrow();
			if (this.stateMachine.shutdownRequested()
					|| !this.stateMachine.publishReady())
				throw cancellationFailure();
			this.readyLinearizationHook.run();
			boolean everyAdmissionOpened = true;
			for (DirectParticipant participant : installed) {
				if (!participant.control().openAdmission())
					everyAdmissionOpened = false;
			}
			this.startupDisposition.set(InternalStartupDisposition.READY);
			this.readyPublished.set(true);
			dispatch(() -> this.config.getAggregateLifecycleObserver()
					.didStartSoklet(this.soklet));
			for (DirectParticipant participant : installed) {
				try {
					participant.control().afterOwnerReadyPublished();
				} catch (Throwable failure) {
					retainShutdownIntentFailureSafely(failure);
				}
			}
			signalStartWaiters();
			if (!everyAdmissionOpened)
				requestShutdownIntent();

			awaitRunningShutdown(installed);
			return coordinateShutdown(installed,
					InternalStartupDisposition.READY,
					this.shutdownIntentFailure.get(), false);
		} catch (Throwable failure) {
			Throwable normalizedFailure = normalizeStartupFailure(failure);
			Throwable exactFailure = this.controllingEventElection.firstEvent()
					.isPresent()
					? unexpectedTerminationCause().orElse(normalizedFailure)
					: normalizedFailure;
			addSuppressedIfDistinct(exactFailure, normalizedFailure);
			addSuppressedIfDistinct(exactFailure, this.shutdownIntentFailure.get());
			this.startupFailure.compareAndSet(null, exactFailure);
			InternalStartupDisposition disposition = classifyStartupFailure(
					exactFailure, startupDeadline);
			this.startupDisposition.set(disposition);
			requestShutdownIntent();
			freezeAttachmentWindow();
			for (ParticipantControl control : this.controls) {
				try {
					control.commitIfAttachedForRollback();
				} catch (RuntimeException | Error commitFailure) {
					retainShutdownIntentFailure(commitFailure);
					addSuppressedIfDistinct(exactFailure, commitFailure);
					try {
						control.discardUncommittedForRollback();
					} catch (RuntimeException | Error discardFailure) {
						retainShutdownIntentFailure(discardFailure);
						addSuppressedIfDistinct(exactFailure, discardFailure);
					}
				}
			}
			List<DirectParticipant> installed = this.participants.stream()
					.filter(participant -> participant.control().isCommitted()).toList();
			this.installedParticipants.set(installed);
			for (DirectParticipant participant : installed) {
				try {
					participant.control().recordShutdownIntent();
				} catch (RuntimeException | Error shutdownFailure) {
					retainShutdownIntentFailure(shutdownFailure);
					addSuppressedIfDistinct(exactFailure, shutdownFailure);
				}
			}
			dispatch(() -> this.config.getAggregateLifecycleObserver()
					.didFailToStartSoklet(this.soklet, exactFailure));

			List<InternalLifecycleCoordinator.Participant> shutdownParticipants =
					new ArrayList<>(installed);
			@Nullable InternalParticipantKind attachmentAttemptKind = null;
			if (this.activeStartupCall.get() != null) {
				if (setupAttempt.isActive())
					shutdownParticipants.add(setupAttempt);
				else {
					AttachmentAttempt activeAttachment = AttachmentAttempt.active();
					if (activeAttachment != null) {
						shutdownParticipants.add(activeAttachment);
						attachmentAttemptKind = activeAttachment.transportKind();
					}
				}
			}
			return coordinateShutdown(shutdownParticipants, disposition,
					exactFailure, requiresCancellationBudget(exactFailure),
					attachmentAttemptKind);
		}
	}

	private void awaitRunningShutdown(
			@NonNull List<DirectParticipant> participants)
			throws InterruptedException {
		long effectivelyInfinite = Long.MAX_VALUE;
		this.waiter.await(effectivelyInfinite,
				() -> this.stateMachine.shutdownRequested()
						|| participants.stream().anyMatch(participant ->
						participant.terminationGroup().controllingEvent()
								.isPresent()));
		if (!this.stateMachine.shutdownRequested())
			requestShutdownIntent();
	}

	@NonNull
	private CoordinatorOutcome coordinateShutdown(
			@NonNull List<? extends InternalLifecycleCoordinator.Participant> participants,
			@NonNull InternalStartupDisposition disposition,
			@Nullable Throwable primaryFailure,
			boolean cancellationBudget) {
		return coordinateShutdown(participants, disposition, primaryFailure,
				cancellationBudget, null);
	}

	@NonNull
	private CoordinatorOutcome coordinateShutdown(
			@NonNull List<? extends InternalLifecycleCoordinator.Participant> participants,
			@NonNull InternalStartupDisposition disposition,
			@Nullable Throwable primaryFailure,
			boolean cancellationBudget,
			@Nullable InternalParticipantKind attachmentAttemptKind) {
		freezeAttachmentWindow();
		submitShutdownTransitions();
		InternalLifecyclePolicy policy = this.config.getInternalLifecyclePolicy();
		long intentNanos = firstShutdownIntentNanos();
		long gracefulBase = cancellationBudget
				? startupCancellationDeadlineNanos(policy) : intentNanos;
		ShutdownSchedule proposedSchedule = new ShutdownSchedule(
				LifecycleDeadlines.after(gracefulBase,
						policy.gracefulShutdownTimeout()), 0L);
		proposedSchedule = new ShutdownSchedule(proposedSchedule.gracefulDeadlineNanos(),
				LifecycleDeadlines.after(proposedSchedule.gracefulDeadlineNanos(),
						policy.forcedShutdownTimeout()));
		this.shutdownSchedule.compareAndSet(null, proposedSchedule);
		ShutdownSchedule exactSchedule = requireNonNull(this.shutdownSchedule.get());
		TrackedLifecycleCallRunner.Call<?> active = this.activeStartupCall.get();
		if (active != null)
			active.cancel();

		InternalShutdownResult coordinated;
		try {
			coordinated = this.coordinator.shutdown(participants,
					exactSchedule.gracefulDeadlineNanos(),
					exactSchedule.forcedDeadlineNanos());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			if (primaryFailure != null && primaryFailure != exception)
				primaryFailure.addSuppressed(exception);
			coordinated = unknownResult(participants, disposition,
					primaryFailure == null ? exception : primaryFailure);
		} catch (RuntimeException | Error failure) {
			if (primaryFailure != null && primaryFailure != failure)
				primaryFailure.addSuppressed(failure);
			coordinated = unknownResult(participants, disposition,
					primaryFailure == null ? failure : primaryFailure);
		}

		for (DirectParticipant participant : this.participants)
			participant.freezeCatchUp();
		InternalShutdownResult adjusted = adjustAndCompleteConfiguredResults(
				coordinated, disposition, primaryFailure, attachmentAttemptKind);
		return new CoordinatorOutcome(adjusted, primaryFailure);
	}

	@NonNull
	private InternalShutdownResult adjustAndCompleteConfiguredResults(
			@NonNull InternalShutdownResult coordinated,
			@NonNull InternalStartupDisposition disposition,
			@Nullable Throwable primaryFailure,
			@Nullable InternalParticipantKind attachmentAttemptKind) {
		EnumMap<InternalParticipantKind, InternalParticipantShutdownResult> byKind =
				new EnumMap<>(InternalParticipantKind.class);
		for (InternalParticipantShutdownResult result : coordinated
				.participantResults())
			byKind.put(result.kind(), result);

		InternalParticipantShutdownResult framework = byKind.get(
				InternalParticipantKind.FRAMEWORK_STARTUP);
		boolean projectAttachmentAttempt = attachmentAttemptKind != null
				&& isIncompleteParticipantResult(framework);
		for (ParticipantControl control : this.controls) {
			InternalParticipantShutdownResult result = byKind.get(control.kind());
			if (result == null) {
				result = new InternalParticipantShutdownResult(control.kind(),
						InternalParticipantShutdownDisposition.NOT_STARTED,
						primaryFailure == null ? List.of() : List.of(primaryFailure),
						Set.of());
			}
			if (projectAttachmentAttempt
					&& control.kind() == attachmentAttemptKind) {
				result = mergeAttachmentAttemptResult(result,
						requireNonNull(framework));
			} else {
				try {
					if (!control.startAttempted()
							&& result.disposition()
							== InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION)
						result = new InternalParticipantShutdownResult(control.kind(),
								InternalParticipantShutdownDisposition.NOT_STARTED,
								result.failures(), result.residualActivity());
				} catch (Throwable diagnosticFailure) {
					result = unknownParticipantResult(result, diagnosticFailure);
				}
			}
			byKind.put(control.kind(), result);
		}

		List<InternalParticipantShutdownResult> configured = new ArrayList<>(
				this.controls.stream()
				.map(control -> requireNonNull(byKind.get(control.kind())))
				.toList());
		if (!projectAttachmentAttempt && isIncompleteParticipantResult(framework))
			configured.add(framework);
		InternalShutdownResult result = new InternalShutdownResultAggregator()
				.aggregate(disposition, configured);

		for (ParticipantControl control : this.controls) {
			if (!control.isCommitted())
				continue;
			InternalParticipantShutdownResult participant = result
					.participantResult(control.kind()).orElseThrow();
			Throwable finalizationFailure = null;
			try {
				finalizationFailure = requireNonNull(
						control.finalizeEvidence(participant),
						"Participant evidence finalization returned null")
						.orElse(null);
			} catch (Throwable failure) {
				finalizationFailure = failure;
			}
			if (finalizationFailure == null)
				continue;
			Throwable exactFinalizationFailure = finalizationFailure;
			InternalParticipantShutdownResult downgraded = unknownParticipantResult(
					participant, exactFinalizationFailure);
			byKind.put(control.kind(), downgraded);
			configured = new ArrayList<>(this.controls.stream()
					.map(candidate -> requireNonNull(byKind.get(candidate.kind())))
					.toList());
			if (!projectAttachmentAttempt
					&& isIncompleteParticipantResult(framework))
				configured.add(framework);
			result = new InternalShutdownResultAggregator().aggregate(disposition,
					configured);
			try {
				Optional<Throwable> retentionFailure = requireNonNull(
						control.finalizeEvidence(downgraded),
						"Participant evidence retention returned null");
				retentionFailure.ifPresent(failure ->
						addSuppressedIfDistinct(exactFinalizationFailure, failure));
			} catch (Throwable retentionFailure) {
				addSuppressedIfDistinct(exactFinalizationFailure, retentionFailure);
			}
		}

		if (!result.isComplete()) {
			EnumMap<InternalResidualActivityKind, Integer> counts =
					new EnumMap<>(InternalResidualActivityKind.class);
			for (InternalParticipantShutdownResult participant : result
					.participantResults())
				for (InternalResidualActivityKind kind : participant.residualActivity())
					counts.merge(kind, 1, Integer::sum);
			result = result.withRetentionAnchor(new LifecycleRetentionAnchor(this,
					counts, "incomplete direct Soklet lifecycle"));
		}
		return result;
	}

	private static boolean isIncompleteParticipantResult(
			@Nullable InternalParticipantShutdownResult result) {
		return result != null && (result.disposition()
				== InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
				|| result.disposition()
				== InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
	}

	@NonNull
	private static InternalParticipantShutdownResult mergeAttachmentAttemptResult(
			@NonNull InternalParticipantShutdownResult transport,
			@NonNull InternalParticipantShutdownResult attempt) {
		List<Throwable> failures = new ArrayList<>(transport.failures());
		for (Throwable failure : attempt.failures())
			if (failures.stream().noneMatch(candidate -> candidate == failure))
				failures.add(failure);
		EnumSet<InternalResidualActivityKind> residual = EnumSet.noneOf(
				InternalResidualActivityKind.class);
		residual.addAll(transport.residualActivity());
		residual.addAll(attempt.residualActivity());
		InternalParticipantShutdownDisposition mergedDisposition =
				attempt.disposition()
						== InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
				|| transport.disposition()
						== InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
				? InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
				: InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN;
		return new InternalParticipantShutdownResult(transport.kind(),
				mergedDisposition, failures, residual);
	}

	@NonNull
	private static InternalParticipantShutdownResult unknownParticipantResult(
			@NonNull InternalParticipantShutdownResult participant,
			@NonNull Throwable failure) {
		List<Throwable> failures = new ArrayList<>(
				requireNonNull(participant).failures());
		Throwable exactFailure = requireNonNull(failure);
		if (failures.stream().noneMatch(candidate -> candidate == exactFailure))
			failures.add(exactFailure);
		return new InternalParticipantShutdownResult(participant.kind(),
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
				failures, participant.residualActivity());
	}

	private void publishCoordinatorOutcome(@Nullable CoordinatorOutcome outcome) {
		CoordinatorOutcome exactOutcome = outcome;
		if (exactOutcome == null) {
			IllegalStateException failure = new IllegalStateException(
					"Soklet lifecycle coordination produced no result");
			exactOutcome = failedBeforeCoordination(failure);
		}
		publishTerminal(exactOutcome.result());
	}

	private void publishTerminal(@NonNull InternalShutdownResult result) {
		InternalShutdownResult exactResult = requireNonNull(result);
		if (!this.terminalPublicationClaimed.compareAndSet(false, true))
			return;
		this.terminalUnexpectedTermination =
				terminalUnexpectedTerminationClaim();
		// Install the immutable result before publishing CLOSED.  Diagnostic
		// readers can therefore never observe CLOSED without its exact result,
		// while private joins remain gated until terminal records are accepted
		// and the dispatcher is sealed below.
		this.completion.install(exactResult);
		if (this.stateMachine.state()
				!= InternalLifecycleStateMachine.State.CLOSED)
			this.stateMachine.publishClosed();
		InternalLifecycleCoreSnapshot coreSnapshot =
				new InternalLifecycleCoreSnapshot(exactResult,
						this.clock.nanoTime());
		if (!this.terminalCoreSnapshot.compareAndSet(null, coreSnapshot))
			throw new IllegalStateException(
					"The terminal core snapshot was already published");
		try {
			for (ParticipantControl control : this.controls) {
				if (!control.isCommitted())
					continue;
				try {
					control.publishResult(exactResult);
				} catch (Throwable failure) {
					try {
						control.publishResultAfterFailure(exactResult);
					} catch (Throwable fallbackFailure) {
						addSuppressedIfDistinct(failure, fallbackFailure);
					}
					retainShutdownIntentFailureSafely(failure);
				}
			}
			try {
				submitTerminalTransitions(exactResult);
			} catch (Throwable failure) {
				retainShutdownIntentFailureSafely(failure);
			}
		} finally {
			// Once the immutable result is installed, optional transport/observer
			// work must never strand internal waiters or the public stage handoff.
			try {
				this.transitions.seal();
			} finally {
				try {
					this.coreSnapshotPublisher.accept(coreSnapshot);
				} catch (Throwable failure) {
					// The runner also publishes the same immutable snapshot after
					// its core join.  Finalization diagnostics must never strand
					// lifecycle waiters.
					retainShutdownIntentFailureSafely(failure);
				}
				this.completion.releaseInternalWaiters();
				try {
					signalStartWaiters();
				} finally {
					this.completion.startPublicHandoff();
				}
			}
		}
		for (ParticipantControl control : this.controls) {
			try {
				control.afterOwnerResultPublished();
			} catch (Throwable failure) {
				retainShutdownIntentFailureSafely(failure);
			}
		}
	}

	private void publishNotStartedWithoutCoordinator() {
		if (this.readyPublished.get() || this.terminalPublicationClaimed.get())
			return;
		submitShutdownTransitions();
		InternalShutdownResult result = new InternalShutdownResultAggregator()
				.aggregate(InternalStartupDisposition.NOT_ATTEMPTED,
						this.controls.stream().map(control ->
								new InternalParticipantShutdownResult(control.kind(),
										InternalParticipantShutdownDisposition.NOT_STARTED,
										List.of(), Set.of())).toList());
		publishTerminal(result);
	}

	private void installLegacyHandlersOnly() {
		this.config.getHttpServer().ifPresent(server -> server.initialize(this.config,
				(request, consumer) -> this.soklet.handleRequest(request,
						ServerType.STANDARD_HTTP, consumer)));
		this.config.getSseServer().ifPresent(server -> server.initialize(this.config,
				(request, consumer) -> this.soklet.handleRequest(request,
						ServerType.SSE, consumer)));
		this.config.getMcpServer().ifPresent(server -> {
			if (server instanceof DefaultMcpServer defaultServer) {
				defaultServer.initialize(this.config);
				defaultServer.installLifecycleExecutionOwner(
						this.executionOwnerToken);
			}
		});
	}

	@NonNull
	private List<ParticipantControl> createControls() {
		List<ParticipantControl> result = new ArrayList<>(3);
		this.config.getHttpServer().ifPresent(server -> result.add(
				new HttpControl(server,
						SokletConfig.unwrapHttpServer(server))));
		this.config.getSseServer().ifPresent(server -> result.add(
				new SseControl(server,
						SokletConfig.unwrapSseServer(server))));
		this.config.getMcpServer().ifPresent(server -> result.add(
				new McpControl((DefaultMcpServer) server)));
		return result;
	}

	private <T> T runStartupCall(@NonNull String name,
			InternalLifecycleCoordinator.Participant participant,
			java.util.concurrent.Callable<T> callable,
			@NonNull Optional<Long> startupDeadline) throws Throwable {
		AtomicReference<T> value = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		InternalLifecycleCoordinator.Participant exactParticipant =
				requireNonNull(participant);
		boolean syntheticAttempt = exactParticipant instanceof FrameworkAttempt;
		this.activeStartupParticipant.set(exactParticipant);
		TrackedLifecycleCallRunner.Call<T> call;
		try {
			call = this.callRunner.submit(name,
				exactParticipant.terminationGroup(), () -> {
					try {
						if (this.stateMachine.shutdownRequested()) {
							exactParticipant.terminationGroup().recordShutdownIntent();
							throw cancellationFailure();
						}
						T result = callable.call();
						value.set(result);
						return result;
					} catch (Throwable throwable) {
						failure.set(throwable);
						if (throwable instanceof DefaultResourceMethodResolver
								.StartupWaitCancelledException)
							return null;
						// A synchronous setup/attach/start throw owns rollback; publish
						// participant intent before the tracked-call runner records the
						// same failure, so it remains startup evidence rather than a
						// fabricated premature-termination event.  A transport signal
						// that already won keeps its earlier controlling event.
						exactParticipant.terminationGroup().recordShutdownIntent();
						if (throwable instanceof Exception exception)
							throw exception;
						throw (Error) throwable;
					}
				});
		} catch (RuntimeException | Error launchFailure) {
			this.activeStartupParticipant.compareAndSet(exactParticipant, null);
			throw launchFailure;
		}
		this.activeStartupCall.set(call);
		call.completion().whenComplete((ignoredValue, ignoredFailure) -> {
			if (exactParticipant instanceof FrameworkAttempt attempt)
				attempt.completed();
			this.activeStartupParticipant.compareAndSet(exactParticipant, null);
			this.activeStartupCall.compareAndSet(call, null);
			this.waiter.signal();
		});
		if (this.stateMachine.shutdownRequested()) {
			exactParticipant.terminationGroup().recordShutdownIntent();
			call.cancel();
		}
		long deadline = startupDeadline.orElse(Long.MAX_VALUE);
		DeadlineWaiter.Outcome outcome;
		try {
			outcome = this.waiter.await(deadline,
					() -> call.isDone() || this.stateMachine.shutdownRequested()
							|| !syntheticAttempt && failure.get() == null
							&& (firstInstalledControllingEvent().isPresent()
									|| exactParticipant.terminationGroup()
											.controllingEvent().isPresent()));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			this.cancellationCause.compareAndSet(null, exception);
			requestShutdownIntent();
			throw exception;
		} finally {
			if (call.isDone()) {
				this.activeStartupParticipant.compareAndSet(exactParticipant, null);
				this.activeStartupCall.compareAndSet(call, null);
			}
		}
		if (!call.isDone()) {
			if (outcome == DeadlineWaiter.Outcome.DEADLINE_REACHED
					&& (this.startupStopReason.compareAndSet(null,
							StartupStopReason.TIMED_OUT)
						|| this.startupStopReason.get()
							== StartupStopReason.TIMED_OUT)) {
				requestShutdownIntent(StartupStopReason.TIMED_OUT);
				throw new TimeoutException("Soklet startup deadline was reached");
			}
			call.cancel();
			Optional<InternalTerminationEvent> controlling = exactParticipant
					.terminationGroup().controllingEvent();
			if (controlling.isPresent()) {
				throw requestUnexpectedTermination(controlling.orElseThrow());
			}
			Optional<Throwable> unexpectedFailure = unexpectedTerminationCause();
			if (unexpectedFailure.isPresent())
				throw unexpectedFailure.orElseThrow();
			if (this.stateMachine.shutdownRequested())
				throw cancellationFailure();
		}
		Throwable exactFailure = failure.get();
		if (exactFailure instanceof DefaultResourceMethodResolver
				.StartupWaitCancelledException)
			throw cancellationFailure();
		if (exactFailure != null)
			throw exactFailure;
		if (!syntheticAttempt) {
			Optional<InternalTerminationEvent> controlling = exactParticipant
					.terminationGroup().controllingEvent();
			if (controlling.isPresent())
				throw requestUnexpectedTermination(controlling.orElseThrow());
		}
		return value.get();
	}

	@NonNull
	private RuntimeException cancellationFailure() {
		Throwable cause = this.cancellationCause.get();
		if (cause instanceof RuntimeException runtimeException)
			return runtimeException;
		return new StartupCancellationException(cause == null
				? new IllegalStateException("Soklet shutdown was requested during startup")
				: cause);
	}

	@NonNull
	private InternalStartupDisposition classifyStartupFailure(
			@NonNull Throwable failure, @NonNull Optional<Long> startupDeadline) {
		if (failure instanceof TimeoutException)
			return InternalStartupDisposition.TIMED_OUT;
		if (this.controllingEventElection.firstEvent().isPresent())
			return InternalStartupDisposition.FAILED;
		if (failure instanceof StartupCancellationException
				|| failure instanceof InterruptedException
				|| this.stateMachine.shutdownRequested())
			return InternalStartupDisposition.CANCELLED;
		if (startupDeadline.isPresent()
				&& this.clock.nanoTime() >= startupDeadline.orElseThrow())
			return InternalStartupDisposition.TIMED_OUT;
		return InternalStartupDisposition.FAILED;
	}

	@NonNull
	private Optional<Throwable> unexpectedTerminationCause() {
		UnexpectedTerminationClaim elected = this.unexpectedTermination.get();
		if (elected != null)
			return Optional.of(elected.failure());
		Optional<InternalTerminationEvent> first =
				this.controllingEventElection.firstEvent();
		if (first.isPresent())
			return Optional.of(retainUnexpectedTerminationClaim(
					first.orElseThrow()).failure());
		for (DirectParticipant participant : this.installedParticipants.get()) {
			Optional<InternalTerminationEvent> controlling = participant
					.terminationGroup().controllingEvent();
			if (controlling.isEmpty())
				continue;
			Optional<Throwable> cause = controlling.orElseThrow().cause();
			if (cause.isPresent())
				return cause;
		}
		return Optional.empty();
	}

	@NonNull
	private Optional<InternalTerminationEvent> firstInstalledControllingEvent() {
		Optional<InternalTerminationEvent> first =
				this.controllingEventElection.firstEvent();
		if (first.isPresent())
			return first;
		for (DirectParticipant participant : this.installedParticipants.get()) {
			Optional<InternalTerminationEvent> controlling = participant
					.terminationGroup().controllingEvent();
			if (controlling.isPresent())
				return controlling;
		}
		return Optional.empty();
	}

	private boolean requiresCancellationBudget(@NonNull Throwable failure) {
		return this.activeStartupCall.get() != null
				|| this.startupDisposition.get()
						== InternalStartupDisposition.TIMED_OUT
				|| this.startupDisposition.get()
						== InternalStartupDisposition.CANCELLED
				|| failure instanceof TimeoutException
				|| failure instanceof StartupCancellationException
				|| failure instanceof InterruptedException;
	}

	@NonNull
	private Throwable normalizeStartupFailure(@NonNull Throwable failure) {
		Throwable exact = requireNonNull(failure);
		if (exact instanceof StartupCancellationException
				&& exact.getCause() != null)
			return exact.getCause();
		if (exact instanceof BuiltInTransportLifecycleAdapter
				.PrematureTerminationException && exact.getCause() != null)
			return exact.getCause();
		return exact;
	}

	@NonNull
	private SokletStartupException startupException(
			@NonNull InternalShutdownResult result) {
		Throwable failure = this.startupFailure.get();
		if (failure == null && result.startupDisposition()
				== InternalStartupDisposition.NOT_ATTEMPTED)
			return new SokletStartupException(result.startupDisposition(), result);
		if (failure == null)
			failure = new IllegalStateException(
					"Soklet startup ended before readiness");
		return new SokletStartupException(result.startupDisposition(), result,
				failure);
	}

	private void awaitCompletionUninterruptibly() {
		boolean interrupted = false;
		for (;;) {
			try {
				this.completion.await();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private void signalStartWaiters() {
		synchronized (this.startMonitor) {
			this.startSignalEpoch++;
			this.startMonitor.notifyAll();
		}
		this.waiter.signal();
	}

	private void dispatchStartIntent() {
		dispatch(() -> this.config.getAggregateLifecycleObserver()
				.willStartSoklet(this.soklet));
	}

	private void dispatchParticipantStartIntent(
			@NonNull InternalParticipantKind kind) {
		dispatch(() -> {
			LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
			switch (kind) {
				case HTTP -> observer.willStartHttpServer(
						this.config.getHttpServer().orElseThrow());
				case SSE -> observer.willStartSseServer(
						this.config.getSseServer().orElseThrow());
				case MCP -> observer.willStartMcpServer(
						this.config.getMcpServer().orElseThrow());
				case FRAMEWORK_STARTUP -> { }
			}
		});
	}

	private void dispatchParticipantStarted(
			@NonNull InternalParticipantKind kind) {
		dispatch(() -> {
			LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
			switch (kind) {
				case HTTP -> observer.didStartHttpServer(
						this.config.getHttpServer().orElseThrow());
				case SSE -> observer.didStartSseServer(
						this.config.getSseServer().orElseThrow());
				case MCP -> observer.didStartMcpServer(
						this.config.getMcpServer().orElseThrow());
				case FRAMEWORK_STARTUP -> { }
			}
		});
	}

	private void dispatchParticipantStartFailure(
			@NonNull InternalParticipantKind kind, @NonNull Throwable failure) {
		dispatch(() -> {
			LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
			switch (kind) {
				case HTTP -> observer.didFailToStartHttpServer(
						this.config.getHttpServer().orElseThrow(), failure);
				case SSE -> observer.didFailToStartSseServer(
						this.config.getSseServer().orElseThrow(), failure);
				case MCP -> observer.didFailToStartMcpServer(
						this.config.getMcpServer().orElseThrow(), failure);
				case FRAMEWORK_STARTUP -> { }
			}
		});
	}

	private void submitShutdownTransitions() {
		if (!this.shutdownTransitionsSubmitted.compareAndSet(false, true))
			return;
		dispatch(() -> this.config.getAggregateLifecycleObserver()
				.willStopSoklet(this.soklet));
		for (ParticipantControl control : this.controls)
			dispatch(() -> {
				LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
				switch (control.kind()) {
					case HTTP -> observer.willStopHttpServer(
							this.config.getHttpServer().orElseThrow());
					case SSE -> observer.willStopSseServer(
							this.config.getSseServer().orElseThrow());
					case MCP -> observer.willStopMcpServer(
							this.config.getMcpServer().orElseThrow());
					case FRAMEWORK_STARTUP -> { }
				}
			});
	}

	private void submitTerminalTransitions(
			@NonNull InternalShutdownResult result) {
		for (ParticipantControl control : this.controls) {
			InternalParticipantShutdownResult participant = result
					.participantResult(control.kind()).orElseThrow();
			dispatch(() -> dispatchLegacyTerminal(control.kind(), participant));
		}
		dispatch(() -> {
			LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
			if (result.isComplete())
				observer.didStopSoklet(this.soklet);
			else
				observer.didFailToStopSoklet(this.soklet,
						new ShutdownIncompleteException(result));
		});
	}

	private void dispatchLegacyTerminal(@NonNull InternalParticipantKind kind,
			@NonNull InternalParticipantShutdownResult result) {
		LifecycleObserver observer = this.config.getAggregateLifecycleObserver();
		boolean complete = result.disposition()
				!= InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY
				&& result.disposition()
				!= InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN;
		Throwable failure = complete ? null : result.failures().isEmpty()
				? new ShutdownIncompleteException(new InternalShutdownResultAggregator()
						.aggregate(this.startupDisposition.get(), List.of(result)))
				: result.failures().get(0);
		switch (kind) {
			case HTTP -> {
				HttpServer server = this.config.getHttpServer().orElseThrow();
				if (complete)
					observer.didStopHttpServer(server);
				else
					observer.didFailToStopHttpServer(server,
							requireNonNull(failure));
			}
			case SSE -> {
				SseServer server = this.config.getSseServer().orElseThrow();
				if (complete)
					observer.didStopSseServer(server);
				else
					observer.didFailToStopSseServer(server,
							requireNonNull(failure));
			}
			case MCP -> {
				McpServer server = this.config.getMcpServer().orElseThrow();
				if (complete)
					observer.didStopMcpServer(server, mcpOutcome(result));
				else
					observer.didFailToStopMcpServer(server,
							requireNonNull(failure));
			}
			case FRAMEWORK_STARTUP -> { }
		}
	}

	@NonNull
	private static McpShutdownOutcome mcpOutcome(
			@NonNull InternalParticipantShutdownResult result) {
		return switch (result.disposition()) {
			case NOT_STARTED, GRACEFUL_TERMINATION, FORCED_TERMINATION,
					UNEXPECTED_TERMINATION -> McpShutdownOutcome.CLEAN;
			case RESIDUAL_ACTIVITY, TERMINATION_UNKNOWN ->
					McpShutdownOutcome.RESIDUAL_HANDLERS;
		};
	}

	private void dispatch(@NonNull Runnable callback) {
		if (!this.transitionObservationEnabled)
			return;
		try {
			this.transitions.dispatch(requireNonNull(callback));
		} catch (RuntimeException | Error ignored) {
			// Observation and its infrastructure never control core lifecycle.
		}
	}

	@NonNull
	private CoordinatorOutcome failedBeforeCoordination(
			@NonNull Throwable failure) {
		return new CoordinatorOutcome(new InternalShutdownResultAggregator()
				.aggregate(InternalStartupDisposition.FAILED,
						this.controls.stream().map(control ->
								new InternalParticipantShutdownResult(control.kind(),
										InternalParticipantShutdownDisposition.NOT_STARTED,
										List.of(failure), Set.of())).toList()), failure);
	}

	@NonNull
	private InternalShutdownResult unknownResult(
			@NonNull List<? extends InternalLifecycleCoordinator.Participant> participants,
			@NonNull InternalStartupDisposition disposition,
			@NonNull Throwable failure) {
		List<InternalParticipantShutdownResult> results = new ArrayList<>();
		for (InternalLifecycleCoordinator.Participant participant : participants) {
			List<Throwable> failures = new ArrayList<>();
			failures.add(requireNonNull(failure));
			Set<InternalResidualActivityKind> residual = Set.of();
			try {
				residual = requireNonNull(participant.residualActivity(),
						"Participant residual activity returned null");
			} catch (Throwable diagnosticFailure) {
				if (diagnosticFailure != failure)
					failures.add(diagnosticFailure);
				addSuppressedIfDistinct(failure, diagnosticFailure);
			}
			results.add(new InternalParticipantShutdownResult(participant.kind(),
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					failures, residual));
		}
		return new InternalShutdownResultAggregator().aggregate(disposition, results);
	}

	private record CoordinatorOutcome(@NonNull InternalShutdownResult result,
			@Nullable Throwable failure) {
		private CoordinatorOutcome {
			requireNonNull(result);
		}
	}

	private record ShutdownSchedule(long gracefulDeadlineNanos,
			long forcedDeadlineNanos) {
	}

	private record UnexpectedTerminationClaim(
			@NonNull InternalTerminationEvent event,
			@NonNull Throwable failure) {
		private UnexpectedTerminationClaim {
			requireNonNull(event);
			requireNonNull(failure);
		}
	}

	private enum StartupStopReason {
		CANCELLED,
		TIMED_OUT,
		UNEXPECTED
	}

	private static final class StartupCancellationException
			extends IllegalStateException {
		private StartupCancellationException(@NonNull Throwable cause) {
			super("Soklet startup was cancelled", requireNonNull(cause));
		}
	}

	private class FrameworkAttempt
			implements InternalLifecycleCoordinator.Participant {
		@NonNull
		private final InternalParticipantKind kind;
		@NonNull
		private final AdmissionFence admission;
		@NonNull
		private final InternalTerminationGroup group;
		@NonNull
		private final InternalTransportTerminationSignal signal;
		@NonNull
		private final AtomicBoolean active;

		private FrameworkAttempt(@NonNull InternalParticipantKind kind) {
			this.kind = requireNonNull(kind);
			this.admission = new AdmissionFence(false, waiter::signal);
			this.group = new InternalTerminationGroup(this.admission,
					waiter::signal, workers, executionOwnerToken);
			this.signal = new InternalTransportTerminationSignal(this.group,
					this.group.root());
			this.active = new AtomicBoolean(true);
			this.group.commit();
		}

		void completed() {
			if (this.active.compareAndSet(true, false)) {
				this.group.recordShutdownIntent();
				this.signal.signalTerminated();
			}
		}

		boolean isActive() {
			return this.active.get();
		}

		@Override @NonNull public InternalParticipantKind kind() { return this.kind; }
		@Override @NonNull public AdmissionFence admissionFence() { return this.admission; }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return this.group; }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return Set.of(); }
		@Override @NonNull public InternalTransportRuntime runtime() {
			return new InternalTransportRuntime() {
				@Override public void start(@NonNull InternalStartupContext context) { }
				@Override public void quiesce(@NonNull InternalShutdownContext context) { }
				@Override public void force(@NonNull InternalShutdownContext context) { }
			};
		}
		@Override public boolean startupCallActive() { return this.active.get(); }
	}

	private final class AttachmentAttempt extends FrameworkAttempt {
		@NonNull
		private static final ThreadLocal<AttachmentAttempt> ACTIVE =
				new ThreadLocal<>();
		@NonNull
		private final InternalParticipantKind transportKind;

		private AttachmentAttempt(@NonNull InternalParticipantKind transportKind) {
			super(InternalParticipantKind.FRAMEWORK_STARTUP);
			this.transportKind = requireNonNull(transportKind);
			ACTIVE.set(this);
		}

		@NonNull
		InternalParticipantKind transportKind() {
			return this.transportKind;
		}

		void markInstalled() {
			ACTIVE.remove();
		}

		@Nullable
		static AttachmentAttempt active() {
			return ACTIVE.get();
		}
	}

	private final class DirectParticipant
			implements InternalLifecycleCoordinator.Participant {
		private enum CatchUpState {
			OPEN,
			CLAIMED,
			FROZEN
		}

		@NonNull
		private final ParticipantControl control;
		@NonNull
		private final AtomicBoolean startRunning;
		@NonNull
		private final AtomicReference<InternalShutdownContext> requestedContext;
		@NonNull
		private final AtomicReference<CatchUpState> catchUpState;

		private DirectParticipant(@NonNull ParticipantControl control) {
			this.control = requireNonNull(control);
			this.startRunning = new AtomicBoolean();
			this.requestedContext = new AtomicReference<>();
			this.catchUpState = new AtomicReference<>(CatchUpState.OPEN);
		}

		@NonNull ParticipantControl control() { return this.control; }
		@NonNull AtomicBoolean startRunning() { return this.startRunning; }

		void catchUpShutdownPhase() {
			InternalShutdownContext context = this.requestedContext.get();
			if (context == null || !this.catchUpState.compareAndSet(
					CatchUpState.OPEN, CatchUpState.CLAIMED))
				return;
			if (context.phase() == InternalShutdownPhase.FORCED)
				this.control.runtime().force(context);
			else
				this.control.runtime().quiesce(context);
		}

		void freezeCatchUp() {
			this.catchUpState.getAndSet(CatchUpState.FROZEN);
		}

		@Override @NonNull public InternalParticipantKind kind() { return this.control.kind(); }
		@Override @NonNull public AdmissionFence admissionFence() { return this.control.admissionFence(); }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return this.control.terminationGroup(); }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return this.control.residualActivity(); }
		@Override public boolean startupCallActive() { return this.startRunning.get(); }
		@Override @NonNull public InternalTransportRuntime runtime() {
			return new InternalTransportRuntime() {
				@Override public void start(@NonNull InternalStartupContext context) {
					control.runtime().start(context);
				}
				@Override public void quiesce(@NonNull InternalShutdownContext context) {
					requestedContext.compareAndSet(null, context);
					if (!startRunning.get()
							|| control.acceptsShutdownPhaseIntentDuringStart())
						control.runtime().quiesce(context);
				}
				@Override public void force(@NonNull InternalShutdownContext context) {
					requestedContext.set(context);
					if (!startRunning.get()
							|| control.acceptsShutdownPhaseIntentDuringStart())
						control.runtime().force(context);
				}
			};
		}
	}

	private interface ParticipantControl {
		@NonNull InternalParticipantKind kind();
		@NonNull InternalTransportIdentity identity();
		@NonNull Class<?> transportClass();
		void beginAttachment(@NonNull InternalStartupContext context);
		void attach(@NonNull InternalStartupContext context);
		boolean claimAttachmentInvocation();
		boolean installAttached();
		void discardPendingAttachment();
		void commit();
		void commitIfAttachedForRollback();
		void discardUncommittedForRollback();
		boolean isCommitted();
		void start(@NonNull InternalStartupContext context);
		boolean openAdmission();
		void recordShutdownIntent();
		boolean startAttempted();
		default boolean acceptsShutdownPhaseIntentDuringStart() { return false; }
		@NonNull AdmissionFence admissionFence();
		@NonNull InternalTerminationGroup terminationGroup();
		@NonNull InternalTransportRuntime runtime();
		@NonNull Set<InternalResidualActivityKind> residualActivity();
		@NonNull Optional<Throwable> finalizeEvidence(
				@NonNull InternalParticipantShutdownResult result);
		void publishResult(@NonNull InternalShutdownResult result);
		void publishResultAfterFailure(@NonNull InternalShutdownResult result);
		void afterOwnerReadyPublished();
		void afterOwnerResultPublished();
	}

	private abstract class AbstractControl implements ParticipantControl {
		private enum AttachmentState {
			NOT_BEGUN,
			PENDING,
			ATTACHING,
			INSTALLED,
			DISCARDED
		}

		@NonNull
		private final AtomicReference<AttachmentState> attachmentState =
				new AtomicReference<>(AttachmentState.NOT_BEGUN);
		private final AtomicBoolean committed = new AtomicBoolean();
		private final AtomicBoolean startAttempted = new AtomicBoolean();

		void markAttachmentPending() {
			if (!this.attachmentState.compareAndSet(AttachmentState.NOT_BEGUN,
					AttachmentState.PENDING))
				throw new IllegalStateException("Transport attachment was already begun");
		}
		void markCommitted() { this.committed.set(true); }
		void markStartAttempted() { this.startAttempted.set(true); }
		boolean attached() {
			return this.attachmentState.get() == AttachmentState.INSTALLED;
		}
		@Override public boolean installAttached() {
			return this.attachmentState.compareAndSet(AttachmentState.ATTACHING,
					AttachmentState.INSTALLED);
		}
		@Override public boolean claimAttachmentInvocation() {
			return this.attachmentState.compareAndSet(AttachmentState.PENDING,
					AttachmentState.ATTACHING);
		}
		@Override public void discardPendingAttachment() {
			for (;;) {
				AttachmentState observed = this.attachmentState.get();
				if (observed != AttachmentState.PENDING
						&& observed != AttachmentState.ATTACHING)
					return;
				if (this.attachmentState.compareAndSet(observed,
						AttachmentState.DISCARDED)) {
					discardUncommittedAttachment();
					return;
				}
			}
		}
		abstract void discardUncommittedAttachment();
		@Override public boolean isCommitted() { return this.committed.get(); }
		@Override public boolean startAttempted() { return this.startAttempted.get(); }
		@Override public void commitIfAttachedForRollback() {
			if (attached() && !isCommitted())
				commit();
		}
		@Override public void discardUncommittedForRollback() {
			if (this.attachmentState.compareAndSet(AttachmentState.INSTALLED,
					AttachmentState.DISCARDED))
				discardUncommittedAttachment();
		}
		@Override @NonNull public Optional<Throwable> finalizeEvidence(
				@NonNull InternalParticipantShutdownResult result) { return Optional.empty(); }
		@Override public void publishResult(@NonNull InternalShutdownResult result) { }
		@Override public void publishResultAfterFailure(
				@NonNull InternalShutdownResult result) { }
		@Override public void afterOwnerReadyPublished() { }
		@Override public void afterOwnerResultPublished() { }
	}

	private final class HttpControl extends AbstractControl {
		@NonNull private final HttpServer exposed;
		@NonNull private final HttpServer real;
		@NonNull private final InternalTransportIdentity identity;
		private BuiltInTransportLifecycleAdapter.@Nullable Generation builtIn;
		private @Nullable LocalGeneration local;

		private HttpControl(@NonNull HttpServer exposed, @NonNull HttpServer real) {
			this.exposed = requireNonNull(exposed);
			this.real = requireNonNull(real);
			this.identity = real instanceof DefaultHttpServer defaultServer
					? defaultServer.getLifecycleAdapter().identity()
					: real instanceof InternalHttpTransportEndpoint endpoint
						? endpoint.identity() : LEGACY_IDENTITIES.identityFor(real);
		}
		@Override @NonNull public InternalParticipantKind kind() { return InternalParticipantKind.HTTP; }
		@Override @NonNull public InternalTransportIdentity identity() { return this.identity; }
		@Override @NonNull public Class<?> transportClass() {
			return this.real.getClass();
		}
		@Override public void beginAttachment(@NonNull InternalStartupContext context) {
			if (this.real instanceof DefaultHttpServer defaultServer)
				this.builtIn = defaultServer.getLifecycleAdapter()
						.newExternallyCoordinatedGeneration(waiter, workers,
								executionOwnerToken,
								SokletDirectLifecycle.this::requestShutdownIntent,
									() -> SokletDirectLifecycle.this
											.requestUnexpectedTermination(this),
									controllingEventElection);
			else
				this.local = new LocalGeneration(kind(), this.identity,
						context, this.real instanceof InternalHttpTransportEndpoint
								? (InternalHttpTransportEndpoint) this.real : null,
						() -> SokletDirectLifecycle.this
								.requestUnexpectedTermination(this));
			markAttachmentPending();
		}
		@Override public void attach(@NonNull InternalStartupContext context) {
			if (this.local != null && this.local.endpoint() != null)
				this.local.attachEndpoint((InternalHttpTransportEndpoint)
						this.local.endpoint(), guardedHttpHandler(this.local));
			else {
				this.exposed.initialize(config, guardedHttpHandler(this.local));
				if (this.local != null)
					this.local.installLegacyRuntime(this.exposed::start,
							this.exposed::stop, this.exposed::isStarted);
			}
		}
		@Override void discardUncommittedAttachment() {
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.discardExternallyCoordinatedGeneration(this.builtIn);
			else if (this.local != null)
				this.local.discard();
		}
		@Override public void commit() {
			if (isCommitted()) return;
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.commitExternallyCoordinatedGeneration(this.builtIn);
			else requireNonNull(this.local).commit();
			markCommitted();
		}
		@Override public void start(@NonNull InternalStartupContext context) {
			markStartAttempted();
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.runExternallyCoordinatedStart(this.builtIn, this.exposed::start);
			else requireNonNull(this.local).start(this.exposed::start);
		}
		@Override public boolean openAdmission() {
			return this.builtIn != null
					? ((DefaultHttpServer) this.real).getLifecycleAdapter()
						.openExternallyCoordinatedAdmission(this.builtIn)
					: requireNonNull(this.local).openAdmission();
		}
		@Override public void recordShutdownIntent() {
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.recordExternallyCoordinatedShutdownIntent(this.builtIn);
			else if (this.local != null) this.local.recordShutdownIntent();
		}
		@Override public boolean startAttempted() { return this.builtIn != null
				? this.builtIn.startAttempted() : super.startAttempted(); }
		@Override @NonNull public AdmissionFence admissionFence() { return generation().admissionFence(); }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return generation().terminationGroup(); }
		@Override @NonNull public InternalTransportRuntime runtime() { return generation().runtime(); }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return generation().residualActivity(); }
		@Override @NonNull public Optional<Throwable> finalizeEvidence(@NonNull InternalParticipantShutdownResult result) {
			return this.builtIn == null ? Optional.empty()
					: ((DefaultHttpServer) this.real).getLifecycleAdapter()
						.finalizeExternallyCoordinatedEvidence(this.builtIn, result);
		}
		@Override public void publishResult(@NonNull InternalShutdownResult result) {
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.publishExternallyCoordinatedResult(this.builtIn, result);
		}
		@Override public void publishResultAfterFailure(
				@NonNull InternalShutdownResult result) {
			if (this.builtIn != null)
				((DefaultHttpServer) this.real).getLifecycleAdapter()
						.publishExternallyCoordinatedOwnerResultAfterFailure(
								this.builtIn, result);
		}
		private InternalLifecycleCoordinator.Participant generation() {
			return this.builtIn != null ? this.builtIn : requireNonNull(this.local);
		}
	}

	private final class SseControl extends AbstractControl {
		@NonNull private final SseServer exposed;
		@NonNull private final SseServer real;
		@NonNull private final InternalTransportIdentity identity;
		private BuiltInTransportLifecycleAdapter.@Nullable Generation builtIn;
		private @Nullable LocalGeneration local;
		private SseControl(@NonNull SseServer exposed, @NonNull SseServer real) {
			this.exposed = requireNonNull(exposed); this.real = requireNonNull(real);
			this.identity = real instanceof DefaultSseServer defaultServer
					? defaultServer.getLifecycleAdapter().identity()
					: real instanceof InternalSseTransportEndpoint endpoint
						? endpoint.identity() : LEGACY_IDENTITIES.identityFor(real);
		}
		@Override @NonNull public InternalParticipantKind kind() { return InternalParticipantKind.SSE; }
		@Override @NonNull public InternalTransportIdentity identity() { return this.identity; }
		@Override @NonNull public Class<?> transportClass() {
			return this.real.getClass();
		}
		@Override public void beginAttachment(@NonNull InternalStartupContext context) {
			if (this.real instanceof DefaultSseServer defaultServer)
				this.builtIn = defaultServer.getLifecycleAdapter()
						.newExternallyCoordinatedGeneration(waiter, workers,
								executionOwnerToken,
								SokletDirectLifecycle.this::requestShutdownIntent,
									() -> SokletDirectLifecycle.this
											.requestUnexpectedTermination(this),
									controllingEventElection);
			else this.local = new LocalGeneration(kind(), this.identity, context,
					this.real instanceof InternalSseTransportEndpoint
							? (InternalSseTransportEndpoint) this.real : null,
					() -> SokletDirectLifecycle.this
							.requestUnexpectedTermination(this));
			markAttachmentPending();
		}
		@Override public void attach(@NonNull InternalStartupContext context) {
			if (this.local != null && this.local.endpoint() != null)
				this.local.attachEndpoint((InternalSseTransportEndpoint)
						this.local.endpoint(), guardedSseHandler(this.local));
			else {
				this.exposed.initialize(config, guardedSseHandler(this.local));
				if (this.local != null)
					this.local.installLegacyRuntime(this.exposed::start,
							this.exposed::stop, this.exposed::isStarted);
			}
		}
		@Override void discardUncommittedAttachment() {
			if (this.builtIn != null)
				((DefaultSseServer) this.real).getLifecycleAdapter()
						.discardExternallyCoordinatedGeneration(this.builtIn);
			else if (this.local != null)
				this.local.discard();
		}
		@Override public void commit() {
			if (isCommitted()) return;
			if (this.builtIn != null)
				((DefaultSseServer) this.real).getLifecycleAdapter()
						.commitExternallyCoordinatedGeneration(this.builtIn);
			else requireNonNull(this.local).commit();
			markCommitted();
		}
		@Override public void start(@NonNull InternalStartupContext context) {
			markStartAttempted();
			if (this.builtIn != null)
				((DefaultSseServer) this.real).getLifecycleAdapter()
						.runExternallyCoordinatedStart(this.builtIn, this.exposed::start);
			else requireNonNull(this.local).start(this.exposed::start);
		}
		@Override public boolean openAdmission() { return this.builtIn != null
				? ((DefaultSseServer) this.real).getLifecycleAdapter()
						.openExternallyCoordinatedAdmission(this.builtIn)
				: requireNonNull(this.local).openAdmission(); }
		@Override public void recordShutdownIntent() {
			if (this.builtIn != null) ((DefaultSseServer) this.real)
					.getLifecycleAdapter().recordExternallyCoordinatedShutdownIntent(this.builtIn);
			else if (this.local != null) this.local.recordShutdownIntent();
		}
		@Override public boolean startAttempted() { return this.builtIn != null
				? this.builtIn.startAttempted() : super.startAttempted(); }
		@Override @NonNull public AdmissionFence admissionFence() { return generation().admissionFence(); }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return generation().terminationGroup(); }
		@Override @NonNull public InternalTransportRuntime runtime() { return generation().runtime(); }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return generation().residualActivity(); }
		@Override @NonNull public Optional<Throwable> finalizeEvidence(@NonNull InternalParticipantShutdownResult result) { return this.builtIn == null ? Optional.empty() : ((DefaultSseServer) this.real).getLifecycleAdapter().finalizeExternallyCoordinatedEvidence(this.builtIn, result); }
		@Override public void publishResult(@NonNull InternalShutdownResult result) { if (this.builtIn != null) ((DefaultSseServer) this.real).getLifecycleAdapter().publishExternallyCoordinatedResult(this.builtIn, result); }
		@Override public void publishResultAfterFailure(@NonNull InternalShutdownResult result) { if (this.builtIn != null) ((DefaultSseServer) this.real).getLifecycleAdapter().publishExternallyCoordinatedOwnerResultAfterFailure(this.builtIn, result); }
		private InternalLifecycleCoordinator.Participant generation() { return this.builtIn != null ? this.builtIn : requireNonNull(this.local); }
	}

	private final class McpControl extends AbstractControl {
		@NonNull private final DefaultMcpServer server;
		@NonNull private final McpTransportLifecycleAdapter adapter;
		@NonNull private final AtomicBoolean startupMetricsDeferred =
				new AtomicBoolean();
		@NonNull private final Object shutdownMetricsLock = new Object();
		private boolean shutdownMetricsDeferred;
		private boolean ownerResultPublished;
		private McpTransportLifecycleAdapter.@Nullable Generation generation;
		private McpControl(@NonNull DefaultMcpServer server) {
			this.server = requireNonNull(server);
			this.adapter = server.getLifecycleAdapter();
		}
		@Override @NonNull public InternalParticipantKind kind() { return InternalParticipantKind.MCP; }
		@Override @NonNull public InternalTransportIdentity identity() { return this.adapter.identity(); }
		@Override @NonNull public Class<?> transportClass() {
			return this.server.getClass();
		}
		@Override public void beginAttachment(@NonNull InternalStartupContext context) {
			this.server.beginNonwaitingMcpMetricsDeferral();
			this.startupMetricsDeferred.set(true);
			this.generation = this.adapter.newExternallyCoordinatedGeneration(waiter,
					workers, executionOwnerToken,
					SokletDirectLifecycle.this::requestShutdownIntent,
						() -> SokletDirectLifecycle.this
								.requestUnexpectedTermination(this),
						controllingEventElection);
			markAttachmentPending();
		}
		@Override public void attach(@NonNull InternalStartupContext context) {
			this.server.initialize(config);
			this.server.installLifecycleExecutionOwner(executionOwnerToken);
		}
		@Override void discardUncommittedAttachment() {
			if (this.generation != null)
				this.adapter.discardExternallyCoordinatedGeneration(this.generation);
		}
		@Override public void commit() { if (!isCommitted()) { this.adapter.commitExternallyCoordinatedGeneration(requireNonNull(this.generation)); markCommitted(); } }
		@Override public void start(@NonNull InternalStartupContext context) { markStartAttempted(); this.adapter.runExternallyCoordinatedStart(requireNonNull(this.generation), () -> this.server.startForSoklet(ignored -> { })); }
		@Override public boolean openAdmission() { return this.adapter.openExternallyCoordinatedAdmission(requireNonNull(this.generation)); }
		@Override public void recordShutdownIntent() {
			synchronized (this.shutdownMetricsLock) {
				// Terminal publication may outrun the request thread's control fanout.
				// Linearize acquisition against a permanent terminal seal so a late or
				// repeated request cannot open a deferral with no remaining release.
				if (!this.ownerResultPublished && !this.shutdownMetricsDeferred) {
					this.server.beginNonwaitingMcpMetricsDeferral();
					this.shutdownMetricsDeferred = true;
				}
			}
			if (this.generation != null)
				this.adapter.recordExternallyCoordinatedShutdownIntent(this.generation);
		}
		@Override public boolean startAttempted() { return this.generation != null && this.generation.startAttempted(); }
		@Override public boolean acceptsShutdownPhaseIntentDuringStart() { return true; }
		@Override @NonNull public AdmissionFence admissionFence() { return requireNonNull(this.generation).admissionFence(); }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return requireNonNull(this.generation).terminationGroup(); }
		@Override @NonNull public InternalTransportRuntime runtime() { return requireNonNull(this.generation).runtime(); }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return requireNonNull(this.generation).residualActivity(); }
		@Override @NonNull public Optional<Throwable> finalizeEvidence(@NonNull InternalParticipantShutdownResult result) { return this.adapter.finalizeExternallyCoordinatedEvidence(requireNonNull(this.generation), result); }
		@Override public void publishResult(@NonNull InternalShutdownResult result) {
			McpTransportLifecycleAdapter.Generation exactGeneration =
					requireNonNull(this.generation);
			this.adapter.publishExternallyCoordinatedResult(exactGeneration, result);
			this.server.normalizeExternallyCoordinatedCompletionWhileMetricsDeferred(
					exactGeneration);
		}
		@Override public void publishResultAfterFailure(
				@NonNull InternalShutdownResult result) {
			McpTransportLifecycleAdapter.Generation exactGeneration =
					requireNonNull(this.generation);
			this.adapter.publishExternallyCoordinatedOwnerResultAfterFailure(
					exactGeneration, result);
			this.server.normalizeExternallyCoordinatedCompletionWhileMetricsDeferred(
					exactGeneration);
		}
		@Override public void afterOwnerReadyPublished() {
			if (this.startupMetricsDeferred.compareAndSet(true, false))
				startMetricsHandoff("soklet-mcp-started-metrics",
						this.server::endMcpMetricsDeferral);
		}
		@Override public void afterOwnerResultPublished() {
			int releases = 0;
			if (this.startupMetricsDeferred.compareAndSet(true, false))
				releases++;
			synchronized (this.shutdownMetricsLock) {
				this.ownerResultPublished = true;
				if (this.shutdownMetricsDeferred) {
					this.shutdownMetricsDeferred = false;
					releases++;
				}
			}
			if (releases == 0)
				return;
			int exactReleases = releases;
			startMetricsHandoff("soklet-mcp-terminal-metrics", () -> {
				for (int index = 0; index < exactReleases; index++)
					this.server.endMcpMetricsDeferral();
			});
		}
		private void startMetricsHandoff(@NonNull String name,
				@NonNull Runnable handoff) {
			Runnable exactHandoff = requireNonNull(handoff);
			try {
				workers.start(LifecycleWorkers.Role.MCP_METRICS_HANDOFF,
						requireNonNull(name), exactHandoff);
			} catch (RuntimeException | Error launchFailure) {
				try {
					Thread fallback = new Thread(exactHandoff, name + "-fallback");
					fallback.setDaemon(true);
					fallback.start();
				} catch (RuntimeException | Error fallbackFailure) {
					addSuppressedIfDistinct(launchFailure, fallbackFailure);
				}
			}
		}
	}

	private final class LocalGeneration
			implements InternalLifecycleCoordinator.Participant {
		@NonNull private final InternalParticipantKind kind;
		@NonNull private final InternalTransportIdentity identity;
		@NonNull private final AdmissionFence admission;
		@NonNull private final InternalTerminationGroup group;
		@NonNull private final InternalTransportTerminationSignal signal;
		@NonNull private final InternalStartupContext startupContext;
		@NonNull private final Runnable unexpectedTerminationCallback;
		private final @Nullable InternalTransportEndpoint<?> endpoint;
		private final AtomicReference<InternalTransportRuntime> runtime = new AtomicReference<>();
		private final AtomicBoolean committed = new AtomicBoolean();
		private final AtomicBoolean discarded = new AtomicBoolean();
		private final AtomicBoolean started = new AtomicBoolean();
		private final AtomicBoolean unexpectedCallbackPublished = new AtomicBoolean();

		private LocalGeneration(@NonNull InternalParticipantKind kind,
				@NonNull InternalTransportIdentity identity,
				@NonNull InternalStartupContext startupContext,
				@Nullable InternalTransportEndpoint<?> endpoint,
				@NonNull Runnable unexpectedTerminationCallback) {
			this.kind = requireNonNull(kind); this.identity = requireNonNull(identity);
			this.startupContext = requireNonNull(startupContext); this.endpoint = endpoint;
			this.unexpectedTerminationCallback = requireNonNull(
					unexpectedTerminationCallback);
			this.admission = new AdmissionFence(false, waiter::signal);
			this.group = new InternalTerminationGroup(this.admission,
					this::terminationGroupChanged,
					workers, executionOwnerToken, controllingEventElection);
			this.signal = new InternalTransportTerminationSignal(this.group,
					this.group.root());
		}
		private void terminationGroupChanged() {
			if (this.group.controllingEvent().isPresent()
					&& this.unexpectedCallbackPublished.compareAndSet(false, true))
				this.unexpectedTerminationCallback.run();
			waiter.signal();
		}
		@Nullable InternalTransportEndpoint<?> endpoint() { return this.endpoint; }
		<H> void attachEndpoint(@NonNull InternalTransportEndpoint<H> endpoint,
				@NonNull H handler) {
			InternalTransportAttachmentContext<H> context =
					new InternalTransportAttachmentContext<>(config, handler, this.identity,
							this.group, this.group.root(), this.startupContext);
			context.activate();
			try (InternalTerminationGroup.TrackedLifecycleCall ignored =
						this.group.trackLifecycleCall()) {
				InternalTransportRuntime attachedRuntime = requireNonNull(
						endpoint.attach(context, this.startupContext),
						"Configured attach(...) returned null");
				if (!this.discarded.get()) {
					this.runtime.set(attachedRuntime);
					if (this.discarded.get())
						this.runtime.compareAndSet(attachedRuntime, null);
				}
			} catch (RuntimeException | Error failure) {
				this.group.recordSyntheticAttachFailure(this.group.root(), failure);
				throw failure;
			} finally { context.deactivate(); }
		}
		void installLegacyRuntime(@NonNull Runnable start, @NonNull Runnable stop,
				java.util.function.BooleanSupplier isStarted) {
			InternalTransportRuntime legacyRuntime = new InternalTransportRuntime() {
				@Override public void start(@NonNull InternalStartupContext context) { start.run(); }
				@Override public void quiesce(@NonNull InternalShutdownContext context) { stopAndSignal(stop, isStarted); }
				@Override public void force(@NonNull InternalShutdownContext context) { stopAndSignal(stop, isStarted); }
			};
			if (!this.discarded.get()) {
				this.runtime.set(legacyRuntime);
				if (this.discarded.get())
					this.runtime.compareAndSet(legacyRuntime, null);
			}
		}
		private void stopAndSignal(@NonNull Runnable stop,
				java.util.function.BooleanSupplier isStarted) {
			try { stop.run(); if (!isStarted.getAsBoolean()) this.signal.signalTerminated(); }
			catch (RuntimeException | Error failure) { this.signal.signalTerminationFailure(failure); throw failure; }
		}
		void commit() { if (this.committed.compareAndSet(false, true)) this.group.commit(); }
		void discard() {
			this.discarded.set(true);
			this.runtime.set(null);
			this.group.discard();
		}
		void start(@NonNull Runnable legacyStart) { this.started.set(true); if (this.endpoint == null) requireNonNull(legacyStart).run(); else runtime().start(this.startupContext); }
		boolean openAdmission() { return this.admission.open(); }
		void recordShutdownIntent() { this.group.recordShutdownIntent(); }
		@Override @NonNull public InternalParticipantKind kind() { return this.kind; }
		@Override @NonNull public AdmissionFence admissionFence() { return this.admission; }
		@Override @NonNull public InternalTerminationGroup terminationGroup() { return this.group; }
		@Override @NonNull public InternalTransportRuntime runtime() { return requireNonNull(this.runtime.get(), "Transport runtime is not attached"); }
		@Override @NonNull public Set<InternalResidualActivityKind> residualActivity() { return Set.of(); }
	}

	private HttpServer.RequestHandler guardedHttpHandler(@Nullable LocalGeneration generation) {
		return (request, consumer) -> {
			AdmissionFence.Admission admission = generation == null ? null
					: generation.admissionFence().tryAdmit().orElse(null);
			if (generation != null && admission == null)
				return;
			try (AdmissionFence.Admission ignoredAdmission = admission;
				 LifecycleExecutionContext.Scope ignoredExecution = enterExecution()) {
				this.soklet.handleRequest(request, ServerType.STANDARD_HTTP, consumer);
			}
		};
	}

	private SseServer.RequestHandler guardedSseHandler(@Nullable LocalGeneration generation) {
		return (request, consumer) -> {
			AdmissionFence.Admission admission = generation == null ? null
					: generation.admissionFence().tryAdmit().orElse(null);
			if (generation != null && admission == null)
				return;
			try (AdmissionFence.Admission ignoredAdmission = admission;
				 LifecycleExecutionContext.Scope ignoredExecution = enterExecution()) {
				this.soklet.handleRequest(request, ServerType.SSE, consumer);
			}
		};
	}

	/** Weak identity keys and weak transport keys preserve freshness after GC. */
	@ThreadSafe
	private static final class LegacyTransportIdentities {
		@NonNull
		private final ReferenceQueue<Object> staleTransports = new ReferenceQueue<>();
		@NonNull
		private final Map<IdentityWeakReference, InternalTransportIdentity> identities =
				new java.util.HashMap<>();

		@NonNull
		synchronized InternalTransportIdentity identityFor(@NonNull Object transport) {
			expungeStaleTransports();
			Object exactTransport = requireNonNull(transport);
			InternalTransportIdentity existing = this.identities.get(
					new IdentityWeakReference(exactTransport));
			if (existing != null)
				return existing;
			InternalTransportIdentity created = InternalTransportIdentity.create();
			this.identities.put(new IdentityWeakReference(exactTransport,
					this.staleTransports), created);
			return created;
		}

		private void expungeStaleTransports() {
			WeakReference<?> stale;
			while ((stale = (WeakReference<?>) this.staleTransports.poll()) != null)
				this.identities.remove(stale);
		}

		private static final class IdentityWeakReference
				extends WeakReference<Object> {
			private final int identityHashCode;

			private IdentityWeakReference(@NonNull Object referent) {
				super(requireNonNull(referent));
				this.identityHashCode = System.identityHashCode(referent);
			}

			private IdentityWeakReference(@NonNull Object referent,
					@NonNull ReferenceQueue<Object> queue) {
				super(requireNonNull(referent), requireNonNull(queue));
				this.identityHashCode = System.identityHashCode(referent);
			}

			@Override
			public int hashCode() {
				return this.identityHashCode;
			}

			@Override
			public boolean equals(@Nullable Object object) {
				if (this == object)
					return true;
				if (!(object instanceof IdentityWeakReference other))
					return false;
				Object referent = get();
				return referent != null && referent == other.get();
			}
		}
	}
}

/** Temporary package-private projections of D2's typed endpoint kinds. */
interface InternalHttpTransportEndpoint
		extends InternalTransportEndpoint<HttpServer.RequestHandler> {
}

interface InternalSseTransportEndpoint
		extends InternalTransportEndpoint<SseServer.RequestHandler> {
}
