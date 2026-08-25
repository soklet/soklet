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

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/** Lazy, daemon, role-bounded lifecycle worker topology. */
@ThreadSafe
final class LifecycleWorkers {
	@FunctionalInterface
	interface Launcher {
		void launch(@NonNull String name, @NonNull Runnable runnable);
	}

	enum Role {
		COORDINATOR,
		LIFECYCLE_CALL,
		PUBLIC_STAGE_HANDOFF,
		SUBTREE_PROOF_HANDOFF,
		TRANSITION_OBSERVER,
		SHUTDOWN_CLEANUP,
		TERMINAL_REPORTER
	}

	@NonNull
	private final Launcher launcher;
	@NonNull
	private final Map<Role, Integer> limits;
	@NonNull
	private final Map<Role, AtomicInteger> active;
	@NonNull
	private final Map<Role, AtomicInteger> created;

	LifecycleWorkers() {
		this(LifecycleWorkers::launchDaemon);
	}

	LifecycleWorkers(@NonNull Launcher launcher) {
		this.launcher = requireNonNull(launcher);
		EnumMap<Role, Integer> limits = new EnumMap<>(Role.class);
		limits.put(Role.COORDINATOR, 1);
		limits.put(Role.LIFECYCLE_CALL, 24);
		limits.put(Role.PUBLIC_STAGE_HANDOFF, 1);
		limits.put(Role.SUBTREE_PROOF_HANDOFF, 16);
		limits.put(Role.TRANSITION_OBSERVER, 1);
		limits.put(Role.SHUTDOWN_CLEANUP, 1);
		limits.put(Role.TERMINAL_REPORTER, 1);
		this.limits = Collections.unmodifiableMap(limits);
		this.active = counters();
		this.created = counters();
	}

	void start(@NonNull Role role, @NonNull String name,
			@NonNull Runnable runnable) {
		start(role, name, runnable, () -> {});
	}

	void start(@NonNull Role role, @NonNull String name,
			@NonNull Runnable runnable,
			@NonNull Runnable afterRoleRelease) {
		requireNonNull(role);
		requireNonNull(name);
		requireNonNull(runnable);
		requireNonNull(afterRoleRelease);
		AtomicInteger activeForRole = this.active.get(role);
		int current = activeForRole.incrementAndGet();
		if (current > this.limits.get(role)) {
			activeForRole.decrementAndGet();
			throw new IllegalStateException("Lifecycle worker bound exceeded for " + role);
		}
		this.created.get(role).incrementAndGet();
		AtomicBoolean roleReleased = new AtomicBoolean();
		try {
			this.launcher.launch(name, () -> {
				Throwable taskFailure = null;
				try {
					runnable.run();
				} catch (Throwable throwable) {
					taskFailure = throwable;
				} finally {
					if (roleReleased.compareAndSet(false, true))
						activeForRole.decrementAndGet();
				}
				try {
					afterRoleRelease.run();
				} catch (Throwable throwable) {
					if (taskFailure == null)
						taskFailure = throwable;
					else if (throwable != taskFailure)
						taskFailure.addSuppressed(throwable);
				}
				if (taskFailure instanceof RuntimeException runtimeException)
					throw runtimeException;
				if (taskFailure instanceof Error error)
					throw error;
				if (taskFailure != null)
					throw new IllegalStateException(
							"Lifecycle worker failed", taskFailure);
			});
		} catch (RuntimeException | Error throwable) {
			if (roleReleased.compareAndSet(false, true))
				activeForRole.decrementAndGet();
			throw throwable;
		}
	}

	int active(@NonNull Role role) {
		return this.active.get(requireNonNull(role)).get();
	}

	int created(@NonNull Role role) {
		return this.created.get(requireNonNull(role)).get();
	}

	@NonNull
	private static Map<Role, AtomicInteger> counters() {
		EnumMap<Role, AtomicInteger> counters = new EnumMap<>(Role.class);
		for (Role role : Role.values())
			counters.put(role, new AtomicInteger());
		return counters;
	}

	private static void launchDaemon(@NonNull String name,
			@NonNull Runnable runnable) {
		Thread worker = new Thread(runnable, name);
		worker.setDaemon(true);
		worker.start();
	}
}

/** Internal-first result publication plus one cached minimal public-stage draft. */
@ThreadSafe
final class InternalLifecycleCompletion {
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final AtomicReference<InternalShutdownResult> result;
	@NonNull
	private final CompletableFuture<InternalShutdownResult> publicHandoff;
	@NonNull
	private final CompletionStage<InternalShutdownResult> publicView;
	@NonNull
	private final Object monitor;

	InternalLifecycleCompletion(@NonNull LifecycleWorkers workers) {
		this.workers = requireNonNull(workers);
		this.result = new AtomicReference<>();
		this.publicHandoff = new CompletableFuture<>();
		this.publicView = this.publicHandoff.minimalCompletionStage();
		this.monitor = new Object();
	}

	void publish(@NonNull InternalShutdownResult immutableResult) {
		requireNonNull(immutableResult);
		synchronized (this.monitor) {
			if (!this.result.compareAndSet(null, immutableResult))
				throw new IllegalStateException("Lifecycle result was already published");
			this.monitor.notifyAll();
		}
		this.workers.start(LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF,
				"soklet-shutdown-result-handoff",
				() -> this.publicHandoff.complete(immutableResult));
	}

	@NonNull
	InternalShutdownResult await() throws InterruptedException {
		synchronized (this.monitor) {
			while (this.result.get() == null)
				this.monitor.wait();
		}
		return this.result.get();
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		return Optional.ofNullable(this.result.get());
	}

	@NonNull
	CompletionStage<InternalShutdownResult> publicStage() {
		return this.publicView;
	}
}

/** One-shot lifecycle state/start claim used by the D1 adapter later. */
@ThreadSafe
final class InternalLifecycleStateMachine {
	enum State {
		NEW,
		STARTING,
		READY,
		SHUTTING_DOWN,
		CLOSED
	}

	@NonNull
	private final AtomicReference<State> state;
	@NonNull
	private final AtomicBoolean startClaimed;
	@NonNull
	private final AtomicBoolean shutdownIntent;

	InternalLifecycleStateMachine() {
		this.state = new AtomicReference<>(State.NEW);
		this.startClaimed = new AtomicBoolean();
		this.shutdownIntent = new AtomicBoolean();
	}

	void claimStart() {
		if (!this.startClaimed.compareAndSet(false, true)
				|| !this.state.compareAndSet(State.NEW, State.STARTING))
			throw new IllegalStateException("Soklet lifecycle start was already claimed");
	}

	boolean publishReady() {
		return this.state.compareAndSet(State.STARTING, State.READY);
	}

	boolean requestShutdown() {
		boolean first = this.shutdownIntent.compareAndSet(false, true);
		for (;;) {
			State current = this.state.get();
			if (current == State.SHUTTING_DOWN || current == State.CLOSED)
				return first;
			if (this.state.compareAndSet(current, State.SHUTTING_DOWN))
				return first;
		}
	}

	void publishClosed() {
		State previous = this.state.getAndSet(State.CLOSED);
		if (previous == State.CLOSED)
			throw new IllegalStateException("Soklet lifecycle was already closed");
	}

	@NonNull
	State state() {
		return this.state.get();
	}

	boolean shutdownRequested() {
		return this.shutdownIntent.get();
	}
}

/** Thread-local diagnostic used to fail fast on known lifecycle self-joins. */
final class LifecycleExecutionContext {
	@NonNull
	private static final ThreadLocal<AtomicInteger> DEPTH =
			ThreadLocal.withInitial(AtomicInteger::new);

	private LifecycleExecutionContext() {
	}

	@NonNull
	static Scope enter() {
		DEPTH.get().incrementAndGet();
		return new Scope();
	}

	static void requireNonReentrantWait() {
		if (DEPTH.get().get() != 0)
			throw new IllegalStateException("Lifecycle wait cannot run from tracked lifecycle execution");
	}

	static boolean isMarked() {
		return DEPTH.get().get() != 0;
	}

	static final class Scope implements AutoCloseable {
		@NonNull
		private final AtomicBoolean closed;

		private Scope() {
			this.closed = new AtomicBoolean();
		}

		@Override
		public void close() {
			if (!this.closed.compareAndSet(false, true))
				return;
			int remaining = DEPTH.get().decrementAndGet();
			if (remaining < 0)
				throw new IllegalStateException("Lifecycle execution-context underflow");
			if (remaining == 0)
				DEPTH.remove();
		}
	}
}

/** Strong runtime retention plus a precomputed, bounded diagnostic summary. */
@ThreadSafe
final class LifecycleRetentionAnchor {
	@NonNull
	private final Object retainedGraph;
	@NonNull
	private final LifecycleRetentionSummary summary;

	LifecycleRetentionAnchor(@NonNull Object retainedGraph,
			@NonNull Map<InternalResidualActivityKind, Integer> counts,
			@NonNull String frameworkSummary) {
		this.retainedGraph = requireNonNull(retainedGraph);
		this.summary = new LifecycleRetentionSummary(counts, frameworkSummary);
	}

	@NonNull
	LifecycleRetentionSummary summary() {
		return this.summary;
	}

	boolean retains(@NonNull Object candidate) {
		return this.retainedGraph == requireNonNull(candidate);
	}
}

@Immutable
final class LifecycleRetentionSummary {
	private static final int MAXIMUM_SUMMARY_CODE_POINTS = 1_024;
	@NonNull
	private final Map<InternalResidualActivityKind, Integer> counts;
	@NonNull
	private final String summary;

	LifecycleRetentionSummary(@NonNull Map<InternalResidualActivityKind, Integer> counts,
			@NonNull String frameworkSummary) {
		requireNonNull(counts);
		EnumMap<InternalResidualActivityKind, Integer> copy =
				new EnumMap<>(InternalResidualActivityKind.class);
		for (Map.Entry<InternalResidualActivityKind, Integer> entry : counts.entrySet()) {
			InternalResidualActivityKind kind = requireNonNull(entry.getKey());
			Integer count = requireNonNull(entry.getValue());
			if (count < 0)
				throw new IllegalArgumentException("Residual activity count must be >= 0");
			if (count > 0)
				copy.put(kind, count);
		}
		this.counts = Collections.unmodifiableMap(copy);
		this.summary = escapeAndCap(requireNonNull(frameworkSummary));
	}

	@NonNull
	Map<InternalResidualActivityKind, Integer> counts() {
		return this.counts;
	}

	@NonNull
	String summary() {
		return this.summary;
	}

	@NonNull
	private static String escapeAndCap(@NonNull String value) {
		StringBuilder escaped = new StringBuilder();
		int emittedCodePoints = 0;
		for (int offset = 0; offset < value.length()
				&& emittedCodePoints < MAXIMUM_SUMMARY_CODE_POINTS;) {
			int codePoint = value.codePointAt(offset);
			offset += Character.charCount(codePoint);
			String replacement = switch (codePoint) {
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				case '\t' -> "\\t";
				default -> Character.isISOControl(codePoint)
						? String.format("\\u%04X", codePoint)
						: new String(Character.toChars(codePoint));
			};
			int remaining = MAXIMUM_SUMMARY_CODE_POINTS - emittedCodePoints;
			int replacementPoints = replacement.codePointCount(0, replacement.length());
			if (replacementPoints > remaining)
				break;
			escaped.append(replacement);
			emittedCodePoints += replacementPoints;
		}
		return escaped.toString();
	}
}

final class LifecycleRetentionDiagnostics {
	private LifecycleRetentionDiagnostics() {
	}

	@NonNull
	static LifecycleRetentionSummary read(@NonNull LifecycleRetentionAnchor anchor) {
		return requireNonNull(anchor).summary();
	}
}

/** Lazy serialized observer dispatcher; publication never waits for callbacks. */
@ThreadSafe
final class LifecycleTransitionDispatcher {
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final ArrayDeque<Runnable> queue;
	@NonNull
	private final Object monitor;
	private boolean workerStarted;
	private boolean sealed;

	LifecycleTransitionDispatcher(@NonNull LifecycleWorkers workers) {
		this.workers = requireNonNull(workers);
		this.queue = new ArrayDeque<>();
		this.monitor = new Object();
	}

	void dispatch(@NonNull Runnable callback) {
		requireNonNull(callback);
		boolean startWorker = false;
		synchronized (this.monitor) {
			if (this.sealed)
				throw new IllegalStateException("Lifecycle transition dispatcher is sealed");
			this.queue.addLast(callback);
			if (!this.workerStarted) {
				this.workerStarted = true;
				startWorker = true;
			}
			this.monitor.notifyAll();
		}
		if (startWorker)
			this.workers.start(LifecycleWorkers.Role.TRANSITION_OBSERVER,
					"soklet-lifecycle-observer", this::run);
	}

	void seal() {
		synchronized (this.monitor) {
			this.sealed = true;
			this.monitor.notifyAll();
		}
	}

	private void run() {
		for (;;) {
			Runnable callback;
			synchronized (this.monitor) {
				while (this.queue.isEmpty() && !this.sealed) {
					try {
						this.monitor.wait();
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (this.queue.isEmpty())
					return;
				callback = this.queue.removeFirst();
			}
			try {
				callback.run();
			} catch (Throwable ignored) {
				// Observation is isolated from core lifecycle progress.
			}
		}
	}
}

/** One independent daemon for each bounded external lifecycle call. */
@ThreadSafe
final class TrackedLifecycleCallRunner {
	@NonNull
	private final LifecycleWorkers workers;

	TrackedLifecycleCallRunner(@NonNull LifecycleWorkers workers) {
		this.workers = requireNonNull(workers);
	}

	@NonNull
	<T> Call<T> submit(@NonNull String name,
			@NonNull InternalTerminationGroup group, @NonNull Callable<T> callable) {
		requireNonNull(group);
		InternalTerminationGroup.TrackedLifecycleCall tracked =
				group.trackLifecycleCall();
		Call<T> call = new Call<>(name, group, callable, tracked);
		try {
			this.workers.start(LifecycleWorkers.Role.LIFECYCLE_CALL, name, call::run);
			return call;
		} catch (RuntimeException | Error throwable) {
			tracked.close();
			throw throwable;
		}
	}

	@ThreadSafe
	static final class Call<T> {
		@NonNull
		private final String name;
		@NonNull
		private final InternalTerminationGroup group;
		@NonNull
		private final Callable<T> callable;
		private final InternalTerminationGroup.@NonNull TrackedLifecycleCall tracked;
		@NonNull
		private final CompletableFuture<T> completion;
		@NonNull
		private final AtomicReference<Thread> thread;
		@NonNull
		private final AtomicBoolean cancellationRequested;

		private Call(@NonNull String name,
				@NonNull InternalTerminationGroup group, @NonNull Callable<T> callable,
				InternalTerminationGroup.@NonNull TrackedLifecycleCall tracked) {
			this.name = requireNonNull(name);
			this.group = requireNonNull(group);
			this.callable = requireNonNull(callable);
			this.tracked = requireNonNull(tracked);
			this.completion = new CompletableFuture<>();
			this.thread = new AtomicReference<>();
			this.cancellationRequested = new AtomicBoolean();
		}

		void cancel() {
			this.cancellationRequested.set(true);
			Thread active = this.thread.get();
			if (active != null)
				active.interrupt();
		}

		boolean isDone() {
			return this.completion.isDone();
		}

		@NonNull
		CompletionStage<T> completion() {
			return this.completion.minimalCompletionStage();
		}

		@NonNull
		String name() {
			return this.name;
		}

		private void run() {
			this.thread.set(Thread.currentThread());
			if (this.cancellationRequested.get())
				Thread.currentThread().interrupt();
			try (LifecycleExecutionContext.Scope ignoredContext =
							 LifecycleExecutionContext.enter()) {
				try {
					this.completion.complete(this.callable.call());
				} catch (Throwable throwable) {
					this.group.signalFailure(this.group.root(), throwable);
					this.completion.completeExceptionally(throwable);
				}
			} finally {
				this.thread.set(null);
				this.tracked.close();
			}
		}
	}
}

/** Descriptor-neutral coordinator implementing the shared grace/force protocol. */
final class InternalLifecycleCoordinator {
	interface Participant {
		@NonNull
		InternalParticipantKind kind();

		@NonNull
		AdmissionFence admissionFence();

		@NonNull
		InternalTerminationGroup terminationGroup();

		@NonNull
		InternalTransportRuntime runtime();

		@NonNull
		Set<InternalResidualActivityKind> residualActivity();
	}

	@NonNull
	private final NanoClock clock;
	@NonNull
	private final DeadlineWaiter waiter;
	@NonNull
	private final TrackedLifecycleCallRunner callRunner;

	InternalLifecycleCoordinator(@NonNull NanoClock clock,
			@NonNull DeadlineWaiter waiter,
			@NonNull TrackedLifecycleCallRunner callRunner) {
		this.clock = requireNonNull(clock);
		this.waiter = requireNonNull(waiter);
		this.callRunner = requireNonNull(callRunner);
	}

	@NonNull
	InternalShutdownResult shutdown(@NonNull List<? extends Participant> participants,
			long gracefulDeadlineNanos, long forcedDeadlineNanos) throws InterruptedException {
		requireNonNull(participants);
		List<Participant> ordered = new ArrayList<>(participants);
		ordered.sort((left, right) -> left.kind().compareTo(right.kind()));
		for (Participant participant : ordered)
			participant.terminationGroup().recordShutdownIntent();

		InternalShutdownContext gracefulContext = new InternalShutdownContext(
				InternalShutdownPhase.GRACEFUL, this.clock, gracefulDeadlineNanos);
		Map<Participant, TrackedLifecycleCallRunner.Call<Void>> quiesceCalls =
				new java.util.IdentityHashMap<>();
		for (Participant participant : ordered) {
			TrackedLifecycleCallRunner.Call<Void> quiesceCall = this.callRunner.submit(
					"lifecycle-quiesce-"
						+ participant.kind().name().toLowerCase(Locale.ROOT),
					participant.terminationGroup(), () -> {
						participant.runtime().quiesce(gracefulContext);
						return null;
					});
			quiesceCalls.put(participant, quiesceCall);
		}

		this.waiter.await(gracefulDeadlineNanos,
				() -> ordered.stream().allMatch(candidate ->
						candidate.terminationGroup().isBarrierComplete()));

		Set<Participant> forced = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Map<Participant, TrackedLifecycleCallRunner.Call<Void>> forceCalls =
				new java.util.IdentityHashMap<>();
		InternalShutdownContext forcedContext = new InternalShutdownContext(
				InternalShutdownPhase.FORCED, this.clock, forcedDeadlineNanos);
		for (Participant participant : ordered) {
			if (participant.terminationGroup().isBarrierComplete())
				continue;
			forced.add(participant);
			TrackedLifecycleCallRunner.Call<Void> quiesceCall =
					quiesceCalls.get(participant);
			if (quiesceCall != null && !quiesceCall.isDone())
				quiesceCall.cancel();
			TrackedLifecycleCallRunner.Call<Void> forceCall = this.callRunner.submit(
					"lifecycle-force-"
						+ participant.kind().name().toLowerCase(Locale.ROOT),
					participant.terminationGroup(), () -> {
						participant.runtime().force(forcedContext);
						return null;
					});
			forceCalls.put(participant, forceCall);
		}

		if (!forced.isEmpty()) {
			this.waiter.await(forcedDeadlineNanos,
					() -> ordered.stream().allMatch(candidate ->
							candidate.terminationGroup().isBarrierComplete()));
			for (Participant participant : forced) {
				TrackedLifecycleCallRunner.Call<Void> forceCall = forceCalls.get(participant);
				if (forceCall != null && !forceCall.isDone())
					forceCall.cancel();
			}
		}

		List<InternalParticipantShutdownResult> results = new ArrayList<>();
		for (Participant participant : ordered) {
			InternalTerminationGroup group = participant.terminationGroup();
			InternalParticipantShutdownDisposition disposition;
			Set<InternalResidualActivityKind> reportedResidual =
					participant.residualActivity();
			EnumSet<InternalResidualActivityKind> residual =
					EnumSet.noneOf(InternalResidualActivityKind.class);
			residual.addAll(reportedResidual);
			if (group.trackedLifecycleCallCount() > 0)
				residual.add(InternalResidualActivityKind.LIFECYCLE_CALL);
			if (participant.admissionFence().admittedWorkCount() > 0)
				residual.add(InternalResidualActivityKind.CALLBACK);
			if (group.isBarrierComplete()) {
				if (group.controllingEvent().isPresent())
					disposition = InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION;
				else if (forced.contains(participant))
					disposition = InternalParticipantShutdownDisposition.FORCED_TERMINATION;
				else
					disposition = InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION;
			} else if (!residual.isEmpty()) {
				disposition = InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY;
			} else {
				disposition = InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN;
			}
			List<Throwable> failures = group.primaryEventsInSequence().stream()
					.flatMap(event -> event.cause().stream())
					.toList();
			results.add(new InternalParticipantShutdownResult(participant.kind(), disposition,
					failures, residual));
		}
		return new InternalShutdownResultAggregator().aggregate(
				InternalStartupDisposition.READY, results);
	}
}

/** Process/finalization seams established additively for the later runner. */
interface LifecycleProcessAccess {
	@NonNull
	InputStream standardInput();

	void addShutdownHook(@NonNull Thread hook);

	boolean removeShutdownHook(@NonNull Thread hook);
}

interface LifecycleFinalizationAction {
	void run() throws Exception;
}

interface LifecycleTerminalReporter {
	void report(@NonNull InternalShutdownResult result,
			@NonNull Optional<LifecycleRetentionSummary> retentionSummary);
}
