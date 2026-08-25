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
	static final class Generation {
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
		private final CompletableFuture<InternalShutdownResult> result;
		@NonNull
		private final AtomicBoolean shutdownClaimed;
		@NonNull
		private final AtomicReference<GenerationStartupState> startupState;
		@Nullable
		private volatile LifecycleRetentionAnchor retentionAnchor;
		private volatile long gracefulDeadlineNanos;
		private volatile long forcedDeadlineNanos;

		private Generation(@NonNull BuiltInTransportLifecycleAdapter owner,
				@NonNull DeadlineWaiter waiter) {
			this.waiter = requireNonNull(waiter);
			this.admissionFence = new AdmissionFence(false, waiter::signal);
			this.group = new InternalTerminationGroup(this.admissionFence,
					waiter::signal, owner.workers);
			this.signal = new InternalTransportTerminationSignal(this.group,
					this.group.root());
			this.runtime = new AdapterRuntime(owner, this);
			this.result = new CompletableFuture<>();
			this.shutdownClaimed = new AtomicBoolean();
			this.startupState = new AtomicReference<>(GenerationStartupState.STARTING);
			this.group.commit();
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
			this.owner.callRunner.submit("built-in-" +
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
	}

	@NonNull
	Generation beginStart() {
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

	void markReady(@NonNull Generation generation) {
		requireCurrent(generation);
		Optional<InternalTerminationEvent> premature = generation.group.controllingEvent();
		if (premature.isPresent()) {
			generation.startupState.compareAndSet(GenerationStartupState.STARTING,
					GenerationStartupState.FAILED);
			Throwable cause = premature.get().cause().orElseGet(() ->
					new IllegalStateException("Built-in transport terminated before readiness"));
			throw new IllegalStateException("Built-in transport terminated before readiness", cause);
		}
		if (!generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.READY)) {
			if (generation.startupState.get() == GenerationStartupState.READY)
				throw new IllegalStateException(
						"Built-in transport readiness was already published");
			throw new IllegalStateException(
					"Built-in transport shutdown began before readiness");
		}
		// The state CAS is the readiness linearization point.  If shutdown closes
		// the still-pending fence immediately afterward, readiness won but no work
		// is admitted into the concurrently stopping generation.
		generation.admissionFence.open();
	}

	void failedStart(@NonNull Generation generation, @NonNull Throwable cause,
			boolean terminationProven) {
		requireCurrent(generation);
		generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.FAILED);
		generation.signal.signalTerminationFailure(requireNonNull(cause));
		if (terminationProven)
			generation.signal.signalTerminated();
		requestShutdown(generation);
		awaitResultUninterruptibly(generation);
	}

	void signalUnexpectedFailure(@NonNull Generation generation,
			@NonNull Throwable cause) {
		if (this.current.get() != generation || generation.result.isDone())
			return;
		// Failure is recorded before any transport-wide lifecycle consequence.
		generation.startupState.compareAndSet(GenerationStartupState.STARTING,
				GenerationStartupState.FAILED);
		generation.signal.signalTerminationFailure(requireNonNull(cause));
		requestShutdown(generation);
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
		requestShutdown(generation);
		return generation;
	}

	void awaitStop(@Nullable Generation generation) {
		if (generation != null)
			awaitResultUninterruptibly(generation);
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
		return generation == null || !generation.result.isDone()
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
		this.workers.start(LifecycleWorkers.Role.COORDINATOR,
				"built-in-" + this.kind.name().toLowerCase(Locale.ROOT)
						+ "-lifecycle-coordinator",
				() -> coordinate(generation));
	}

	private void coordinate(@NonNull Generation generation) {
		try {
			InternalLifecycleCoordinator coordinator = new InternalLifecycleCoordinator(
					this.clock, generation.waiter, this.callRunner);
			InternalShutdownResult result = coordinator.shutdown(
					ListSupport.participants(new AdapterParticipant(generation)),
					generation.gracefulDeadlineNanos, generation.forcedDeadlineNanos);
			InternalStartupDisposition startupDisposition =
					generation.startupState.get() == GenerationStartupState.READY
							? InternalStartupDisposition.READY
							: InternalStartupDisposition.FAILED;
			result = new InternalShutdownResult(result.disposition(), startupDisposition,
					result.participantResults());
			publishResult(generation, result);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			generation.signal.signalTerminationFailure(exception);
			InternalParticipantShutdownResult participant =
					new InternalParticipantShutdownResult(this.kind,
							InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN,
							ListSupport.throwables(exception), this.operations.residualActivity());
			InternalStartupDisposition startupDisposition =
					generation.startupState.get() == GenerationStartupState.READY
							? InternalStartupDisposition.READY
							: InternalStartupDisposition.FAILED;
			publishResult(generation, new InternalShutdownResultAggregator().aggregate(
					startupDisposition, ListSupport.participants(participant)));
		}
	}

	private void publishResult(@NonNull Generation generation,
			@NonNull InternalShutdownResult result) {
		if (result.isComplete()) {
			if (generation.runtime.evidenceReleased.compareAndSet(false, true))
				this.operations.releaseTerminatedEvidence();
		} else {
			Set<InternalResidualActivityKind> residual = result
					.participantResult(this.kind)
					.map(InternalParticipantShutdownResult::residualActivity)
					.orElseGet(Set::of);
			EnumMap<InternalResidualActivityKind, Integer> counts =
					new EnumMap<>(InternalResidualActivityKind.class);
			for (InternalResidualActivityKind kind : residual)
				counts.put(kind, 1);
			generation.retentionAnchor = new LifecycleRetentionAnchor(generation,
					counts, "Built-in " + this.kind
							+ " transport retained because termination was not proven");
		}
		generation.result.complete(result);
	}

	private void requireCurrent(@NonNull Generation generation) {
		if (this.current.get() != requireNonNull(generation))
			throw new IllegalStateException("Stale built-in transport lifecycle generation");
	}

	private final class AdapterParticipant implements InternalLifecycleCoordinator.Participant {
		@NonNull
		private final Generation generation;

		private AdapterParticipant(@NonNull Generation generation) {
			this.generation = requireNonNull(generation);
		}

		@Override
		@NonNull
		public InternalParticipantKind kind() {
			return BuiltInTransportLifecycleAdapter.this.kind;
		}

		@Override
		@NonNull
		public AdmissionFence admissionFence() {
			return this.generation.admissionFence;
		}

		@Override
		@NonNull
		public InternalTerminationGroup terminationGroup() {
			return this.generation.group;
		}

		@Override
		@NonNull
		public InternalTransportRuntime runtime() {
			return this.generation.runtime;
		}

		@Override
		@NonNull
		public Set<InternalResidualActivityKind> residualActivity() {
			return BuiltInTransportLifecycleAdapter.this.operations.residualActivity();
		}
	}

	/** Avoid generic varargs arrays and keep package-private call sites concise. */
	private static final class ListSupport {
		private ListSupport() {
		}

		@NonNull
		static List<Throwable> throwables(@NonNull Throwable throwable) {
			return java.util.List.of(throwable);
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
