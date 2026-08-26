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

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Temporary descriptor-preserving bridge used by the built-in HTTP and SSE
 * implementations before the combined public lifecycle cutover.
 */
@ThreadSafe
final class BuiltInTransportLifecycleAdapter {
	private enum GenerationStartupState {
		STARTING,
		READY,
		FAILED
	}

	private enum CoordinationMode {
		STANDALONE,
		EXTERNAL
	}

	interface Operations {
		/** Prompt, signal-only graceful wind-up. */
		void quiesce();

		/** Prompt owned-execution cancellation; must subsume quiesce. */
		void force();

		/** Observe all proof-bearing runtime resources against this exact deadline. */
		boolean awaitTermination(long absoluteDeadlineNanos) throws InterruptedException;

		/** Positive residual evidence at result freeze. */
		@NonNull
		Set<InternalResidualActivityKind> residualActivity();

		/** Release proof-bearing references only after affirmative termination. */
		void releaseTerminatedEvidence();
	}

	@ThreadSafe
	static final class Generation
			implements InternalLifecycleCoordinator.Participant {
		@NonNull
		private final BuiltInTransportLifecycleAdapter owner;
		@NonNull
		private final CoordinationMode coordinationMode;
		@NonNull
		private final AdmissionFence admissionFence;
		@NonNull
		private final DeadlineWaiter waiter;
		@NonNull
		private final InternalTerminationGroup group;
		@NonNull
		private final InternalTransportTerminationSignal signal;
		@NonNull
		private final AdapterRuntime runtime;
		@NonNull
		private final TrackedLifecycleCallRunner callRunner;
		@NonNull
		private final CompletableFuture<InternalShutdownResult> result;
		@NonNull
		private final AtomicBoolean shutdownClaimed;
		@NonNull
		private final AtomicReference<GenerationStartupState> startupState;
		@NonNull
		private final AtomicReference<@Nullable Throwable> startupFailure;
		@NonNull
		private final AtomicBoolean resultPublicationClaimed;
		@NonNull
		private final AtomicBoolean transportStartClaimed;
		@NonNull
		private final AtomicBoolean unexpectedCallbackPublished;
		@NonNull
		private final Runnable externalShutdownRequested;
		@NonNull
		private final Runnable externalUnexpectedTermination;
		@Nullable
		private volatile LifecycleRetentionAnchor retentionAnchor;
		@Nullable
		private volatile InternalParticipantShutdownResult finalizedParticipantResult;
		private volatile boolean externallyCommitted;
		private volatile boolean externallyDiscarded;
		private volatile long gracefulDeadlineNanos;
		private volatile long forcedDeadlineNanos;

		private Generation(@NonNull BuiltInTransportLifecycleAdapter owner,
				@NonNull DeadlineWaiter waiter) {
			this(owner, waiter, owner.workers, null, () -> {}, () -> {},
					CoordinationMode.STANDALONE, null);
		}

		private Generation(@NonNull BuiltInTransportLifecycleAdapter owner,
				@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
				@NonNull Object executionOwnerToken,
				@NonNull Runnable externalShutdownRequested,
				@NonNull Runnable externalUnexpectedTermination,
				@NonNull InternalControllingEventElection ownerEventElection) {
			this(owner, waiter, workers, executionOwnerToken,
					externalShutdownRequested, externalUnexpectedTermination,
					CoordinationMode.EXTERNAL, ownerEventElection);
		}

		private Generation(@NonNull BuiltInTransportLifecycleAdapter owner,
				@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
				@Nullable Object executionOwnerToken,
				@NonNull Runnable externalShutdownRequested,
				@NonNull Runnable externalUnexpectedTermination,
				@NonNull CoordinationMode coordinationMode,
				@Nullable InternalControllingEventElection ownerEventElection) {
			this.owner = requireNonNull(owner);
			this.coordinationMode = requireNonNull(coordinationMode);
			this.waiter = requireNonNull(waiter);
			this.externalShutdownRequested = requireNonNull(
					externalShutdownRequested);
			this.externalUnexpectedTermination = requireNonNull(
					externalUnexpectedTermination);
			this.unexpectedCallbackPublished = new AtomicBoolean();
			this.admissionFence = new AdmissionFence(false, waiter::signal);
			Object exactExecutionOwnerToken = executionOwnerToken == null
					? LifecycleExecutionContext.legacyOwnerToken()
					: executionOwnerToken;
			this.group = new InternalTerminationGroup(this.admissionFence,
					this::terminationGroupChanged, requireNonNull(workers),
					exactExecutionOwnerToken, ownerEventElection);
			this.signal = new InternalTransportTerminationSignal(this.group,
					this.group.root());
			this.runtime = new AdapterRuntime(owner, this);
			this.callRunner = coordinationMode == CoordinationMode.STANDALONE
					? owner.callRunner : new TrackedLifecycleCallRunner(workers);
			this.result = new CompletableFuture<>();
			this.shutdownClaimed = new AtomicBoolean();
			this.startupState = new AtomicReference<>(GenerationStartupState.STARTING);
			this.startupFailure = new AtomicReference<>();
			this.resultPublicationClaimed = new AtomicBoolean();
			this.transportStartClaimed = new AtomicBoolean();
			if (coordinationMode == CoordinationMode.STANDALONE) {
				this.group.commit();
				this.transportStartClaimed.set(true);
			}
		}

		private void terminationGroupChanged() {
			if (this.coordinationMode == CoordinationMode.EXTERNAL
					&& this.group.controllingEvent().isPresent()
					&& this.unexpectedCallbackPublished.compareAndSet(false, true))
				runOwnerCallback(this.externalUnexpectedTermination);
			this.waiter.signal();
		}

		@Override
		@NonNull
		public InternalParticipantKind kind() {
			return this.owner.kind;
		}

		@Override
		@NonNull
		public AdmissionFence admissionFence() {
			return this.admissionFence;
		}

		@Override
		@NonNull
		public InternalTerminationGroup terminationGroup() {
			return this.group;
		}

		@Override
		@NonNull
		public InternalTransportRuntime runtime() {
			return this.runtime;
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityKind> residualActivity() {
			return this.owner.operations.residualActivity();
		}

		private boolean externallyCoordinated() {
			return this.coordinationMode == CoordinationMode.EXTERNAL;
		}

		boolean startAttempted() {
			return this.transportStartClaimed.get();
		}
	}

	@ThreadSafe
	private static final class AdapterRuntime implements InternalTransportRuntime {
		@NonNull
		private final BuiltInTransportLifecycleAdapter owner;
		@NonNull
		private final Generation generation;
		@NonNull
		private final AtomicBoolean quiesced;
		@NonNull
		private final AtomicBoolean forced;
		@NonNull
		private final AtomicBoolean gracefulObserverStarted;
		@NonNull
		private final AtomicBoolean forcedObserverStarted;
		@NonNull
		private final AtomicBoolean evidenceReleased;

		private AdapterRuntime(@NonNull BuiltInTransportLifecycleAdapter owner,
				@NonNull Generation generation) {
			this.owner = requireNonNull(owner);
			this.generation = requireNonNull(generation);
			this.quiesced = new AtomicBoolean();
			this.forced = new AtomicBoolean();
			this.gracefulObserverStarted = new AtomicBoolean();
			this.forcedObserverStarted = new AtomicBoolean();
			this.evidenceReleased = new AtomicBoolean();
		}

		@Override
		public void start(@NonNull InternalStartupContext context) {
			requireNonNull(context);
			// Binding remains in the descriptor-preserving server start() adapter.
		}

		@Override
		public void quiesce(@NonNull InternalShutdownContext context) {
			requireNonNull(context);
			if (this.quiesced.compareAndSet(false, true))
				this.owner.operations.quiesce();
			if (this.gracefulObserverStarted.compareAndSet(false, true))
				startProofObserver(context.absoluteDeadlineNanos(), "graceful");
		}

		@Override
		public void force(@NonNull InternalShutdownContext context) {
			requireNonNull(context);
			if (this.quiesced.compareAndSet(false, true))
				this.owner.operations.quiesce();
			if (this.forced.compareAndSet(false, true))
				this.owner.operations.force();
			if (this.forcedObserverStarted.compareAndSet(false, true))
				startProofObserver(context.absoluteDeadlineNanos(), "forced");
		}

		private void startProofObserver(long absoluteDeadlineNanos,
				@NonNull String phase) {
			this.generation.callRunner.submit("built-in-" +
					this.owner.kind.name().toLowerCase(Locale.ROOT) + "-" + phase + "-proof",
					this.generation.group, () -> {
						try {
							if (this.owner.operations.awaitTermination(absoluteDeadlineNanos))
								this.generation.signal.signalTerminated();
						} catch (RuntimeException | Error throwable) {
							this.generation.signal.signalTerminationFailure(throwable);
							throw throwable;
						}
						return null;
					});
		}
	}

	@NonNull
	private final InternalParticipantKind kind;
	@NonNull
	private final InternalTransportIdentity identity;
	@NonNull
	private final Operations operations;
	@NonNull
	private final Supplier<Duration> gracefulTimeout;
	@NonNull
	private final Duration forcedTimeout;
	@NonNull
	private final NanoClock clock;
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final TrackedLifecycleCallRunner callRunner;
	@NonNull
	private final AtomicReference<Generation> current;
	@NonNull
	private final AtomicReference<Generation> externalCandidate;
	@NonNull
	private final AtomicBoolean externalOwnershipClaimed;
	@NonNull
	private final ThreadLocal<Generation> externalStartInvocation;

	BuiltInTransportLifecycleAdapter(@NonNull InternalParticipantKind kind,
			@NonNull Operations operations, @NonNull Supplier<Duration> gracefulTimeout) {
		this(kind, operations, gracefulTimeout, Duration.ofSeconds(3),
				NanoClock.system(), new LifecycleWorkers());
	}

	BuiltInTransportLifecycleAdapter(@NonNull InternalParticipantKind kind,
			@NonNull Operations operations, @NonNull Supplier<Duration> gracefulTimeout,
			@NonNull Duration forcedTimeout, @NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers) {
		this.kind = requireNonNull(kind);
		this.operations = requireNonNull(operations);
		this.gracefulTimeout = requireNonNull(gracefulTimeout);
		this.forcedTimeout = requireNonNull(forcedTimeout);
		if (forcedTimeout.isNegative())
			throw new IllegalArgumentException("forcedTimeout must be >= 0");
		this.clock = requireNonNull(clock);
		this.workers = requireNonNull(workers);
		this.callRunner = new TrackedLifecycleCallRunner(workers);
		this.identity = InternalTransportIdentity.create();
		this.current = new AtomicReference<>();
		this.externalCandidate = new AtomicReference<>();
		this.externalOwnershipClaimed = new AtomicBoolean();
		this.externalStartInvocation = new ThreadLocal<>();
	}

	@NonNull
	synchronized Generation beginStart() {
		Generation externalGeneration = this.externalStartInvocation.get();
		if (externalGeneration != null) {
			requireExternallyCoordinated(externalGeneration);
			if (this.current.get() != externalGeneration
					|| !externalGeneration.externallyCommitted)
				throw new IllegalStateException(
						"Externally coordinated transport generation is not committed");
			if (!externalGeneration.transportStartClaimed.compareAndSet(false, true))
				throw new IllegalStateException(
						"Externally coordinated transport start was already claimed");
			return externalGeneration;
		}
		if (this.externalOwnershipClaimed.get())
			throw new IllegalStateException(
					"Built-in transport lifecycle is permanently externally owned");

		Generation previous = this.current.get();
		if (previous != null && !previous.result.isDone())
			throw new IllegalStateException("Built-in transport lifecycle generation is still active");
		if (previous != null && !previous.result.join().isComplete())
			throw new IllegalStateException(
					"Built-in transport with retained termination evidence cannot restart");
		Generation generation = new Generation(this, new DeadlineWaiter(this.clock));
		if (!this.current.compareAndSet(previous, generation))
			throw new IllegalStateException("Concurrent built-in transport start is not supported");
		return generation;
	}

	@NonNull
	synchronized Generation newExternallyCoordinatedGeneration(
			@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken,
			@NonNull Runnable externalShutdownRequested,
			@NonNull Runnable externalUnexpectedTermination) {
		return newExternallyCoordinatedGeneration(waiter, workers,
				executionOwnerToken, externalShutdownRequested,
				externalUnexpectedTermination,
				new InternalControllingEventElection());
	}

	@NonNull
	synchronized Generation newExternallyCoordinatedGeneration(
			@NonNull DeadlineWaiter waiter, @NonNull LifecycleWorkers workers,
			@NonNull Object executionOwnerToken,
			@NonNull Runnable externalShutdownRequested,
			@NonNull Runnable externalUnexpectedTermination,
			@NonNull InternalControllingEventElection ownerEventElection) {
		DeadlineWaiter exactWaiter = requireNonNull(waiter);
		LifecycleWorkers exactWorkers = requireNonNull(workers);
		Object exactExecutionOwnerToken = requireNonNull(executionOwnerToken);
		Runnable exactExternalShutdownRequested = requireNonNull(
				externalShutdownRequested);
		Runnable exactExternalUnexpectedTermination = requireNonNull(
				externalUnexpectedTermination);
		if (this.externalOwnershipClaimed.get())
			throw new IllegalStateException(
					"Built-in transport lifecycle is already externally owned");
		if (this.current.get() != null)
			throw new IllegalStateException(
					"Built-in transport lifecycle was already started outside the external owner");

		Generation generation = new Generation(this, exactWaiter,
				exactWorkers, exactExecutionOwnerToken,
				exactExternalShutdownRequested,
				exactExternalUnexpectedTermination,
				requireNonNull(ownerEventElection));
		if (!this.externalOwnershipClaimed.compareAndSet(false, true))
			throw new IllegalStateException(
					"Built-in transport lifecycle is already externally owned");
		if (!this.externalCandidate.compareAndSet(null, generation))
			throw new IllegalStateException(
					"An externally coordinated transport generation is already pending");
		return generation;
	}

	synchronized void commitExternallyCoordinatedGeneration(
			@NonNull Generation generation) {
		requireExternalCandidate(generation);
		Generation previous = this.current.get();
		validatePreviousGeneration(previous);
		generation.group.commit();
		generation.externallyCommitted = true;
		this.current.set(generation);
		this.externalCandidate.compareAndSet(generation, null);
	}

	void discardExternallyCoordinatedGeneration(
			@NonNull Generation generation) {
		requireExternalCandidate(generation);
		generation.group.discard();
		generation.externallyDiscarded = true;
		this.externalCandidate.compareAndSet(generation, null);
	}

	void runExternallyCoordinatedStart(@NonNull Generation generation,
			@NonNull Runnable startAction) {
		requireExternallyCoordinated(generation);
		requireCurrent(generation);
		if (!generation.externallyCommitted || generation.externallyDiscarded)
			throw new IllegalStateException(
					"Externally coordinated transport generation is not active");
		if (this.externalStartInvocation.get() != null)
			throw new IllegalStateException(
					"Externally coordinated transport start is already active on this thread");

		boolean returned = false;
		this.externalStartInvocation.set(generation);
		try {
			requireNonNull(startAction).run();
			returned = true;
		} finally {
			this.externalStartInvocation.remove();
		}
		if (returned && !generation.transportStartClaimed.get())
			throw new IllegalStateException(
					"Transport start did not consume the externally coordinated generation");
	}

	boolean openExternallyCoordinatedAdmission(
			@NonNull Generation generation) {
		requireExternallyCoordinated(generation);
		requireCurrent(generation);
		if (generation.startupState.get() != GenerationStartupState.READY)
			throw new IllegalStateException(
					"Externally coordinated transport is not ready");
		return generation.admissionFence.open();
	}

	boolean recordExternallyCoordinatedShutdownIntent(
			@NonNull Generation generation) {
		requireExternallyCoordinated(generation);
		requireCurrent(generation);
		return recordExternalShutdownIntent(generation);
	}

	void markReady(@NonNull Generation generation) {
		requireCurrent(generation);
		Optional<InternalTerminationEvent> premature = generation.group.controllingEvent();
		if (premature.isPresent()) {
			generation.startupState.compareAndSet(GenerationStartupState.STARTING,
					GenerationStartupState.FAILED);
			Throwable cause = premature.get().cause().orElse(null);
			if (cause != null)
				throw new PrematureTerminationException(cause);
			throw new IllegalStateException(
					"Built-in transport terminated before readiness");
		}
		if (!generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.READY)) {
			if (generation.startupState.get() == GenerationStartupState.READY)
				throw new IllegalStateException(
						"Built-in transport readiness was already published");
			Throwable startupFailure = generation.startupFailure.get();
			if (startupFailure != null)
				throw new PrematureTerminationException(startupFailure);
			throw new IllegalStateException(
					"Built-in transport shutdown began before readiness");
		}
		// The state CAS is the readiness linearization point.  If shutdown closes
		// the still-pending fence immediately afterward, readiness won but no work
		// is admitted into the concurrently stopping generation.
		if (!generation.externallyCoordinated())
			generation.admissionFence.open();
	}

	void failedStart(@NonNull Generation generation, @NonNull Throwable cause,
			boolean terminationProven) {
		requireCurrent(generation);
		Throwable exactCause = requireNonNull(cause);
		if (generation.externallyCoordinated()
				&& this.externalStartInvocation.get() == generation)
			// The direct owner's tracked call records this synchronous failure
			// after the transport call unwinds.  Establish rollback intent first
			// so the exact event remains evidence without becoming an unexpected
			// termination controlling event.
			generation.group.recordShutdownIntent();
		recordStartupFailure(generation, exactCause);
		if (terminationProven)
			generation.signal.signalTerminated();
		if (generation.externallyCoordinated())
			return;
		requestShutdown(generation);
		awaitResultUninterruptibly(generation);
	}

	void signalUnexpectedFailure(@NonNull Generation generation,
			@NonNull Throwable cause) {
		if (this.current.get() != generation || generation.result.isDone())
			return;
		// Failure is recorded before any transport-wide lifecycle consequence.
		Throwable exactCause = requireNonNull(cause);
		recordStartupFailure(generation, exactCause);
		if (generation.externallyCoordinated())
			return;
		requestShutdown(generation);
	}

	private void recordStartupFailure(@NonNull Generation generation,
			@NonNull Throwable cause) {
		Throwable exactCause = requireNonNull(cause);
		synchronized (requireNonNull(generation)) {
			generation.startupFailure.compareAndSet(null, exactCause);
			Throwable primary = requireNonNull(generation.startupFailure.get());
			generation.startupState.compareAndSet(GenerationStartupState.STARTING,
					GenerationStartupState.FAILED);
			if (primary == exactCause)
				generation.signal.signalTerminationFailure(primary);
			else
				generation.group.trySuppressFailureBeforeFreeze(
						generation.group.root(), primary, exactCause);
		}
	}

	static final class PrematureTerminationException extends IllegalStateException {
		private PrematureTerminationException(@NonNull Throwable cause) {
			super("Built-in transport terminated before readiness",
					requireNonNull(cause));
		}
	}

	void stop() {
		Generation generation = requestStop();
		awaitStop(generation);
	}

	@Nullable
	Generation requestStop() {
		Generation generation = this.current.get();
		if (generation == null || generation.result.isDone())
			return null;
		if (generation.externallyCoordinated()) {
			if (recordExternalShutdownIntent(generation))
			runOwnerCallback(generation.externalShutdownRequested);
			return generation;
		}
		requestShutdown(generation);
		return generation;
	}

	void awaitStop(@Nullable Generation generation) {
		if (generation != null) {
			requireOwned(generation);
			if (generation.externallyCoordinated())
				LifecycleExecutionContext.requireNonReentrantWait(
						generation.group.executionOwnerToken());
			awaitResultUninterruptibly(generation);
		}
	}

	@NonNull
	Optional<Throwable> finalizeExternallyCoordinatedEvidence(
			@NonNull Generation generation,
			@NonNull InternalParticipantShutdownResult participantResult) {
		requireExternallyCoordinated(generation);
		requireCurrent(generation);
		InternalParticipantShutdownResult exactParticipant =
				requireNonNull(participantResult);
		if (exactParticipant.kind() != this.kind)
			throw new IllegalArgumentException(
					"Externally coordinated participant result kind does not match adapter");

		synchronized (generation) {
			if (generation.finalizedParticipantResult != null) {
				if (generation.finalizedParticipantResult != exactParticipant)
					throw new IllegalStateException(
							"Externally coordinated evidence was already finalized for another result");
				return Optional.empty();
			}

			if (participantTerminationIsProven(exactParticipant)) {
				if (generation.runtime.evidenceReleased.compareAndSet(false, true)) {
					try {
						this.operations.releaseTerminatedEvidence();
					} catch (Throwable releaseFailure) {
						generation.runtime.evidenceReleased.set(false);
						generation.signal.signalTerminationFailure(releaseFailure);
						return Optional.of(releaseFailure);
					}
				}
			} else {
				retainIncompleteParticipant(generation, exactParticipant);
			}
			generation.finalizedParticipantResult = exactParticipant;
			return Optional.empty();
		}
	}

	void publishExternallyCoordinatedResult(@NonNull Generation generation,
			@NonNull InternalShutdownResult exactResult) {
		requireExternallyCoordinated(generation);
		requireCurrent(generation);
		InternalShutdownResult result = requireNonNull(exactResult);
		InternalParticipantShutdownResult participant = result
				.participantResult(this.kind)
				.orElseThrow(() -> new IllegalArgumentException(
						"Externally coordinated result is missing adapter participant"));
		if (generation.finalizedParticipantResult != participant)
			throw new IllegalStateException(
					"Externally coordinated evidence must be finalized before result publication");
		completeExternallyCoordinatedResult(generation, result);
	}

	/** Owner-only last resort: core publication must release transport waiters. */
	void publishExternallyCoordinatedOwnerResultAfterFailure(
			@NonNull Generation generation,
			@NonNull InternalShutdownResult exactResult) {
		requireExternallyCoordinated(generation);
		completeExternallyCoordinatedResult(generation,
				requireNonNull(exactResult));
	}

	private void completeExternallyCoordinatedResult(
			@NonNull Generation generation,
			@NonNull InternalShutdownResult result) {
		if (generation.resultPublicationClaimed.compareAndSet(false, true)) {
			generation.result.complete(result);
			return;
		}
		if (!generation.result.isDone() || generation.result.join() != result)
			throw new IllegalStateException(
					"A different externally coordinated result was already published");
	}

	@NonNull
	Optional<AdmissionFence.Admission> tryAdmit(@NonNull Generation generation) {
		requireNonNull(generation);
		if (this.current.get() != generation || generation.result.isDone())
			return Optional.empty();
		return generation.admissionFence.tryAdmit();
	}

	boolean admissionOpen(@NonNull Generation generation) {
		requireNonNull(generation);
		return this.current.get() == generation && !generation.result.isDone()
				&& generation.admissionFence.isOpen();
	}

	boolean shutdownRequested(@NonNull Generation generation) {
		requireNonNull(generation);
		return this.current.get() != generation || generation.result.isDone()
				|| generation.shutdownClaimed.get();
	}

	private void awaitResultUninterruptibly(@NonNull Generation generation) {
		boolean interrupted = false;
		for (;;) {
			try {
				generation.result.get();
				break;
			} catch (InterruptedException exception) {
				interrupted = true;
			} catch (java.util.concurrent.ExecutionException exception) {
				break;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	@NonNull
	InternalTransportIdentity identity() {
		return this.identity;
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		Generation generation = this.current.get();
		return generation == null ? Optional.empty() : result(generation);
	}

	@NonNull
	Optional<InternalShutdownResult> result(@NonNull Generation generation) {
		requireOwned(generation);
		return !generation.result.isDone()
				? Optional.empty() : Optional.of(generation.result.join());
	}

	@NonNull
	Optional<Generation> generation() {
		return Optional.ofNullable(this.current.get());
	}

	boolean shutdownInProgress() {
		Generation generation = this.current.get();
		return generation != null && generation.shutdownClaimed.get()
				&& !generation.result.isDone();
	}

	@NonNull
	List<InternalTerminationEvent> terminationEvents(@NonNull Generation generation) {
		requireCurrent(generation);
		return generation.group.primaryEventsInSequence();
	}

	@NonNull
	Optional<LifecycleRetentionSummary> retentionSummary() {
		Generation generation = this.current.get();
		return generation == null || generation.retentionAnchor == null
				? Optional.empty()
				: Optional.of(LifecycleRetentionDiagnostics.read(generation.retentionAnchor));
	}

	private void requestShutdown(@NonNull Generation generation) {
		if (generation.externallyCoordinated())
			throw new IllegalStateException(
					"Externally coordinated shutdown belongs to the Soklet owner");
		if (!generation.shutdownClaimed.compareAndSet(false, true))
			return;
		generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.FAILED);
		// Publish/fence intent on the caller before the coordinator can be
		// delayed by scheduling.  Unexpected-failure callers deliberately record
		// their event first, so the group-local ordering remains authoritative.
		generation.group.recordShutdownIntent();
		long intentNanos = this.clock.nanoTime();
		Duration grace = requireNonNull(this.gracefulTimeout.get(), "gracefulTimeout.get()");
		generation.gracefulDeadlineNanos = LifecycleDeadlines.after(intentNanos, grace);
		generation.forcedDeadlineNanos = LifecycleDeadlines.after(
				generation.gracefulDeadlineNanos, this.forcedTimeout);
		AtomicReference<InternalShutdownResult> coordinatedResult =
				new AtomicReference<>();
		try {
			this.workers.start(LifecycleWorkers.Role.COORDINATOR,
					"built-in-" + this.kind.name().toLowerCase(Locale.ROOT)
							+ "-lifecycle-coordinator",
					() -> coordinatedResult.set(coordinate(generation)),
					() -> {
						InternalShutdownResult result = coordinatedResult.get();
						if (result == null) {
							IllegalStateException failure = new IllegalStateException(
									"Built-in lifecycle coordination produced no result");
							generation.signal.signalTerminationFailure(failure);
							result = coordinationFailureResult(generation, failure);
						}
						publishResult(generation, result);
					});
		} catch (RuntimeException | Error launchFailure) {
			if (generation.result.isDone())
				return;
			generation.signal.signalTerminationFailure(launchFailure);
			publishResult(generation,
					coordinationFailureResult(generation, launchFailure));
		}
	}

	@NonNull
	private InternalShutdownResult coordinate(@NonNull Generation generation) {
		try {
			InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
					this.clock, generation.waiter, this.callRunner);
			InternalShutdownResult result = coordinator.shutdown(
					ListSupport.participants(generation),
					generation.gracefulDeadlineNanos, generation.forcedDeadlineNanos);
			InternalStartupDisposition startupDisposition =
					generation.startupState.get() == GenerationStartupState.READY
							? InternalStartupDisposition.READY
							: InternalStartupDisposition.FAILED;
			return new InternalShutdownResult(result.disposition(), startupDisposition,
					result.participantResults());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			generation.signal.signalTerminationFailure(exception);
			return coordinationFailureResult(generation, exception);
		} catch (RuntimeException | Error failure) {
			generation.signal.signalTerminationFailure(failure);
			return coordinationFailureResult(generation, failure);
		}
	}

	@NonNull
	private InternalShutdownResult coordinationFailureResult(
			@NonNull Generation generation, @NonNull Throwable failure) {
		Set<InternalResidualActivityKind> residual;
		try {
			residual = requireNonNull(this.operations.residualActivity(),
					"operations.residualActivity()");
		} catch (Throwable diagnosticFailure) {
			if (diagnosticFailure != failure)
				failure.addSuppressed(diagnosticFailure);
			residual = Set.of();
		}
		InternalParticipantShutdownResult participant =
				new InternalParticipantShutdownResult(this.kind,
						InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
						coordinationFailures(generation, failure), residual);
		InternalStartupDisposition startupDisposition =
				generation.startupState.get() == GenerationStartupState.READY
						? InternalStartupDisposition.READY
						: InternalStartupDisposition.FAILED;
		return new InternalShutdownResultAggregator().aggregate(startupDisposition,
				ListSupport.participants(participant));
	}

	@NonNull
	private List<@NonNull Throwable> coordinationFailures(
			@NonNull Generation generation, @NonNull Throwable failure) {
		List<Throwable> failures = new ArrayList<>(generation.group
				.primaryEventsInSequence().stream()
				.flatMap(event -> event.cause().stream())
				.toList());
		if (failures.stream().noneMatch(candidate -> candidate == failure))
			failures.add(failure);
		return List.copyOf(failures);
	}

	private void publishResult(@NonNull Generation generation,
			@NonNull InternalShutdownResult result) {
		if (!generation.resultPublicationClaimed.compareAndSet(false, true))
			return;
		InternalShutdownResult publishedResult = requireNonNull(result);
		if (publishedResult.isComplete()
				&& generation.runtime.evidenceReleased.compareAndSet(false, true)) {
			try {
				this.operations.releaseTerminatedEvidence();
			} catch (Throwable releaseFailure) {
				generation.runtime.evidenceReleased.set(false);
				generation.signal.signalTerminationFailure(releaseFailure);
				publishedResult = coordinationFailureResult(generation,
						releaseFailure);
			}
		}
		if (!publishedResult.isComplete())
			retainIncompleteResult(generation, publishedResult);
		generation.result.complete(publishedResult);
	}

	private void retainIncompleteResult(@NonNull Generation generation,
			@NonNull InternalShutdownResult result) {
		InternalParticipantShutdownResult participant = requireNonNull(result)
				.participantResult(this.kind).orElse(null);
		if (participant == null)
			participant = new InternalParticipantShutdownResult(this.kind,
					InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
					List.of(), Set.of());
		retainIncompleteParticipant(generation, participant);
	}

	private void retainIncompleteParticipant(@NonNull Generation generation,
			@NonNull InternalParticipantShutdownResult participant) {
		Set<InternalResidualActivityKind> residual = requireNonNull(participant)
				.residualActivity();
		EnumMap<InternalResidualActivityKind, Integer> counts =
				new EnumMap<>(InternalResidualActivityKind.class);
		for (InternalResidualActivityKind kind : residual)
			counts.put(kind, 1);
		generation.retentionAnchor = new LifecycleRetentionAnchor(generation,
				counts, "Built-in " + this.kind
						+ " transport retained because termination was not proven");
	}

	private boolean recordExternalShutdownIntent(@NonNull Generation generation) {
		boolean first = generation.shutdownClaimed.compareAndSet(false, true);
		generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.FAILED);
		generation.group.recordShutdownIntent();
		return first;
	}

	private static boolean participantTerminationIsProven(
			@NonNull InternalParticipantShutdownResult result) {
		return switch (requireNonNull(result).disposition()) {
			case NOT_STARTED, GRACEFUL_TERMINATION, FORCED_TERMINATION,
					UNEXPECTED_TERMINATION -> true;
			case RESIDUAL_ACTIVITY, TERMINATION_UNKNOWN -> false;
		};
	}

	private static void runOwnerCallback(@NonNull Runnable callback) {
		try {
			requireNonNull(callback).run();
		} catch (Throwable ignored) {
			// Owner callbacks are lifecycle consequences, not failure evidence.
		}
	}

	private void requireCurrent(@NonNull Generation generation) {
		requireOwned(generation);
		if (this.current.get() != generation)
			throw new IllegalStateException("Stale built-in transport lifecycle generation");
	}

	private void requireOwned(@NonNull Generation generation) {
		if (requireNonNull(generation).owner != this)
			throw new IllegalStateException("Foreign built-in transport lifecycle generation");
	}

	private void requireExternallyCoordinated(@NonNull Generation generation) {
		requireOwned(generation);
		if (!generation.externallyCoordinated())
			throw new IllegalStateException(
					"Built-in transport lifecycle generation is not externally coordinated");
	}

	private void requireExternalCandidate(@NonNull Generation generation) {
		requireExternallyCoordinated(generation);
		if (this.externalCandidate.get() != generation)
			throw new IllegalStateException(
					"Externally coordinated transport generation is not pending");
	}

	private void validatePreviousGeneration(@Nullable Generation previous) {
		if (previous != null && !previous.result.isDone())
			throw new IllegalStateException(
					"Built-in transport lifecycle generation is still active");
		if (previous != null && !previous.result.join().isComplete())
			throw new IllegalStateException(
					"Built-in transport with retained termination evidence cannot restart");
	}

	/** Avoid generic varargs arrays and keep package-private call sites concise. */
	private static final class ListSupport {
		private ListSupport() {
		}

		@NonNull
		static List<InternalParticipantShutdownResult> participants(
				@NonNull InternalParticipantShutdownResult result) {
			return java.util.List.of(result);
		}

		@NonNull
		static List<InternalLifecycleCoordinator.Participant> participants(
				InternalLifecycleCoordinator.@NonNull Participant participant) {
			return java.util.List.of(participant);
		}
	}
}
