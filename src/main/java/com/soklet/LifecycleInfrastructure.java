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
import java.util.IdentityHashMap;
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
import java.util.concurrent.locks.ReentrantLock;

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
		MCP_METRICS_HANDOFF,
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
		limits.put(Role.MCP_METRICS_HANDOFF, 2);
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

/**
 * One internally consistent clock/waiter/worker set shared by a direct
 * lifecycle and its standalone-application finalization.
 */
@ThreadSafe
final class LifecycleRuntimeServices {
	@NonNull
	private final NanoClock clock;
	@NonNull
	private final DeadlineWaiter waiter;
	@NonNull
	private final LifecycleWorkers workers;

	LifecycleRuntimeServices(@NonNull NanoClock clock,
			@NonNull LifecycleWorkers workers) {
		this.clock = requireNonNull(clock);
		this.waiter = new DeadlineWaiter(clock);
		this.workers = requireNonNull(workers);
	}

	@NonNull
	static LifecycleRuntimeServices system() {
		return new LifecycleRuntimeServices(NanoClock.system(),
				new LifecycleWorkers());
	}

	@NonNull
	NanoClock clock() {
		return this.clock;
	}

	@NonNull
	DeadlineWaiter waiter() {
		return this.waiter;
	}

	@NonNull
	LifecycleWorkers workers() {
		return this.workers;
	}
}

/** Internal-first result publication plus one cached minimal public stage. */
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
	@NonNull
	private final Runnable beforeWait;
	@NonNull
	private volatile boolean internalReleased;
	@NonNull
	private final AtomicBoolean publicHandoffStarted;

	InternalLifecycleCompletion(@NonNull LifecycleWorkers workers) {
		this(workers, () -> { });
	}

	InternalLifecycleCompletion(@NonNull LifecycleWorkers workers,
			@NonNull Runnable beforeWait) {
		this.workers = requireNonNull(workers);
		this.result = new AtomicReference<>();
		this.publicHandoff = new CompletableFuture<>();
		this.publicView = this.publicHandoff.minimalCompletionStage();
		this.monitor = new Object();
		this.beforeWait = requireNonNull(beforeWait);
		this.publicHandoffStarted = new AtomicBoolean();
	}

	void publish(@NonNull InternalShutdownResult immutableResult) {
		install(requireNonNull(immutableResult));
		releaseInternalWaiters();
		startPublicHandoff();
	}

	void install(@NonNull InternalShutdownResult immutableResult) {
		requireNonNull(immutableResult);
		if (!this.result.compareAndSet(null, immutableResult))
			throw new IllegalStateException("Lifecycle result was already installed");
	}

	void releaseInternalWaiters() {
		if (this.result.get() == null)
			throw new IllegalStateException("Lifecycle result is not installed");
		synchronized (this.monitor) {
			if (this.internalReleased)
				throw new IllegalStateException(
						"Lifecycle internal waiters were already released");
			this.internalReleased = true;
			this.monitor.notifyAll();
		}
	}

	void startPublicHandoff() {
		InternalShutdownResult immutableResult = this.result.get();
		if (immutableResult == null || !this.internalReleased)
			throw new IllegalStateException(
					"Lifecycle result must be installed and internally released first");
		if (!this.publicHandoffStarted.compareAndSet(false, true))
			throw new IllegalStateException("Lifecycle public handoff was already started");
		try {
			this.workers.start(LifecycleWorkers.Role.PUBLIC_STAGE_HANDOFF,
					"soklet-shutdown-result-handoff",
					() -> this.publicHandoff.complete(immutableResult));
		} catch (RuntimeException | Error launchFailure) {
			try {
				Thread fallback = new Thread(
						() -> this.publicHandoff.complete(immutableResult),
						"soklet-shutdown-result-handoff-fallback");
				fallback.setDaemon(true);
				fallback.start();
			} catch (RuntimeException | Error fallbackFailure) {
				if (fallbackFailure != launchFailure)
					launchFailure.addSuppressed(fallbackFailure);
			}
		}
	}

	@NonNull
	InternalShutdownResult await() throws InterruptedException {
		synchronized (this.monitor) {
			if (!this.internalReleased)
				this.beforeWait.run();
			while (!this.internalReleased)
				this.monitor.wait();
		}
		return this.result.get();
	}

	@NonNull
	Optional<InternalShutdownResult> result() {
		return this.internalReleased
				? Optional.of(requireNonNull(this.result.get())) : Optional.empty();
	}

	/** Result visibility for terminal diagnostics, independent of join release. */
	@NonNull
	Optional<InternalShutdownResult> installedResult() {
		return Optional.ofNullable(this.result.get());
	}

	@NonNull
	CompletionStage<InternalShutdownResult> publicStage() {
		return this.publicView;
	}
}

/** Exact immutable core-result publication point used by runner deadlines. */
@Immutable
record InternalLifecycleCoreSnapshot(@NonNull InternalShutdownResult result,
		@NonNull ShutdownResult publicResult, long publicationNanos) {
	InternalLifecycleCoreSnapshot(@NonNull InternalShutdownResult result,
			long publicationNanos) {
		this(result, ShutdownResult.fromInternal(result), publicationNanos);
	}

	InternalLifecycleCoreSnapshot {
		requireNonNull(result);
		requireNonNull(publicResult);
		if (publicResult.internalResult() != result)
			throw new IllegalArgumentException(
					"Core snapshot results must describe the same lifecycle result");
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
	private final ReentrantLock transitionLock;
	@NonNull
	private final AtomicReference<State> state;
	@NonNull
	private final AtomicBoolean shutdownIntent;
	@NonNull
	private final AtomicReference<State> shutdownOrigin;
	private long shutdownIntentNanos;
	private boolean shutdownIntentNanosPresent;

	InternalLifecycleStateMachine() {
		this(new ReentrantLock());
	}

	InternalLifecycleStateMachine(@NonNull ReentrantLock transitionLock) {
		this.transitionLock = requireNonNull(transitionLock);
		this.state = new AtomicReference<>(State.NEW);
		this.shutdownIntent = new AtomicBoolean();
		this.shutdownOrigin = new AtomicReference<>();
	}

	void claimStart() {
		this.transitionLock.lock();
		try {
			if (!this.state.compareAndSet(State.NEW, State.STARTING))
				throw new IllegalStateException(
						"Soklet lifecycle start was already claimed");
		} finally {
			this.transitionLock.unlock();
		}
	}

	boolean publishReady() {
		this.transitionLock.lock();
		try {
			return this.state.compareAndSet(State.STARTING, State.READY);
		} finally {
			this.transitionLock.unlock();
		}
	}

	boolean requestShutdown() {
		return requestShutdownDetailed(System.nanoTime()).firstIntent();
	}

	@NonNull
	ShutdownRequest requestShutdownDetailed() {
		return requestShutdownDetailed(System.nanoTime());
	}

	@NonNull
	ShutdownRequest requestShutdownDetailed(long intentNanos) {
		this.transitionLock.lock();
		try {
			boolean first = this.shutdownIntent.compareAndSet(false, true);
			for (;;) {
				State current = this.state.get();
				if (current == State.SHUTTING_DOWN || current == State.CLOSED) {
					if (!this.shutdownIntentNanosPresent) {
						this.shutdownIntentNanos = intentNanos;
						this.shutdownIntentNanosPresent = true;
					}
					State origin = this.shutdownOrigin.get();
					return new ShutdownRequest(first,
							origin == null ? current : origin,
							this.shutdownIntentNanos);
				}
				if (this.state.compareAndSet(current, State.SHUTTING_DOWN)) {
					this.shutdownOrigin.compareAndSet(null, current);
					this.shutdownIntentNanos = intentNanos;
					this.shutdownIntentNanosPresent = true;
					return new ShutdownRequest(first, current, intentNanos);
				}
			}
		} finally {
			this.transitionLock.unlock();
		}
	}

	void publishClosed() {
		this.transitionLock.lock();
		try {
			State previous = this.state.getAndSet(State.CLOSED);
			if (previous == State.CLOSED)
				throw new IllegalStateException(
						"Soklet lifecycle was already closed");
		} finally {
			this.transitionLock.unlock();
		}
	}

	@NonNull
	State state() {
		return this.state.get();
	}

	boolean shutdownRequested() {
		return this.shutdownIntent.get();
	}

	boolean shutdownWonNew() {
		return this.shutdownOrigin.get() == State.NEW;
	}

	long shutdownIntentNanos() {
		this.transitionLock.lock();
		try {
			if (!this.shutdownIntentNanosPresent)
				throw new IllegalStateException(
						"Soklet shutdown intent is not published");
			return this.shutdownIntentNanos;
		} finally {
			this.transitionLock.unlock();
		}
	}

	record ShutdownRequest(boolean firstIntent, @NonNull State priorState,
			long intentNanos) {
		ShutdownRequest {
			requireNonNull(priorState);
		}
	}
}

/** Thread-local diagnostic used to fail fast on known lifecycle self-joins. */
final class LifecycleExecutionContext {
	@NonNull
	private static final Object LEGACY_OWNER_TOKEN = new Object();
	@NonNull
	private static final ThreadLocal<IdentityHashMap<Object, Integer>> DEPTHS =
			new ThreadLocal<>();

	private LifecycleExecutionContext() {
	}

	@NonNull
	static Scope enter() {
		return enter(LEGACY_OWNER_TOKEN);
	}

	@NonNull
	static Scope enter(@NonNull Object ownerToken) {
		Object exactOwnerToken = requireNonNull(ownerToken);
		IdentityHashMap<Object, Integer> depths = DEPTHS.get();
		if (depths == null) {
			depths = new IdentityHashMap<>();
			DEPTHS.set(depths);
		}
		depths.put(exactOwnerToken, depths.getOrDefault(exactOwnerToken, 0) + 1);
		return new Scope(exactOwnerToken);
	}

	static void requireNonReentrantWait() {
		requireNonReentrantWait(LEGACY_OWNER_TOKEN);
	}

	static void requireNonReentrantWait(@NonNull Object ownerToken) {
		if (isMarked(requireNonNull(ownerToken)))
			throw new IllegalStateException("Lifecycle wait cannot run from tracked lifecycle execution");
	}

	static boolean isMarked() {
		return isMarked(LEGACY_OWNER_TOKEN);
	}

	static boolean isMarked(@NonNull Object ownerToken) {
		IdentityHashMap<Object, Integer> depths = DEPTHS.get();
		return depths != null && depths.getOrDefault(requireNonNull(ownerToken), 0) != 0;
	}

	@NonNull
	static Object legacyOwnerToken() {
		return LEGACY_OWNER_TOKEN;
	}

	static final class Scope implements AutoCloseable {
		@NonNull
		private final Object ownerToken;
		@NonNull
		private final AtomicBoolean closed;

		private Scope(@NonNull Object ownerToken) {
			this.ownerToken = requireNonNull(ownerToken);
			this.closed = new AtomicBoolean();
		}

		@Override
		public void close() {
			if (!this.closed.compareAndSet(false, true))
				return;
			IdentityHashMap<Object, Integer> depths = DEPTHS.get();
			if (depths == null)
				throw new IllegalStateException("Lifecycle execution-context underflow");
			int remaining = depths.getOrDefault(this.ownerToken, 0) - 1;
			if (remaining < 0)
				throw new IllegalStateException("Lifecycle execution-context underflow");
			if (remaining == 0)
				depths.remove(this.ownerToken);
			else
				depths.put(this.ownerToken, remaining);
			if (depths.isEmpty())
				DEPTHS.remove();
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
			@NonNull Map<InternalResidualActivityType, Integer> counts,
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
	private final Map<InternalResidualActivityType, Integer> counts;
	@NonNull
	private final String summary;

	LifecycleRetentionSummary(@NonNull Map<InternalResidualActivityType, Integer> counts,
			@NonNull String frameworkSummary) {
		requireNonNull(counts);
		EnumMap<InternalResidualActivityType, Integer> copy =
				new EnumMap<>(InternalResidualActivityType.class);
		for (Map.Entry<InternalResidualActivityType, Integer> entry : counts.entrySet()) {
			InternalResidualActivityType kind = requireNonNull(entry.getKey());
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
	Map<InternalResidualActivityType, Integer> counts() {
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
	private static final int MAXIMUM_ACCEPTED_RECORDS = 16;
	@NonNull
	private final LifecycleWorkers workers;
	@NonNull
	private final ArrayDeque<Runnable> queue;
	@NonNull
	private final Object monitor;
	private boolean workerStarted;
	private boolean callbackActive;
	private boolean sealed;
	private boolean disabled;
	private int acceptedRecords;
	private int failedCallbacks;
	@Nullable
	private String firstFailureSummary;

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
			if (this.acceptedRecords == MAXIMUM_ACCEPTED_RECORDS)
				throw new IllegalStateException(
						"Lifecycle transition trace exceeds the protocol maximum of 16 records");
			this.queue.addLast(callback);
			this.acceptedRecords++;
			if (!this.workerStarted && !this.disabled) {
				this.workerStarted = true;
				startWorker = true;
			}
			this.monitor.notifyAll();
		}
		if (startWorker) {
			try {
				this.workers.start(LifecycleWorkers.Role.TRANSITION_OBSERVER,
						"soklet-lifecycle-observer", this::run);
			} catch (RuntimeException | Error ignored) {
				synchronized (this.monitor) {
					this.disabled = true;
					this.workerStarted = false;
					this.monitor.notifyAll();
				}
			}
		}
	}

	void seal() {
		synchronized (this.monitor) {
			this.sealed = true;
			this.monitor.notifyAll();
		}
	}

	@NonNull
	LifecycleTransitionSnapshot snapshot() {
		synchronized (this.monitor) {
			return new LifecycleTransitionSnapshot(this.acceptedRecords,
					this.queue.size(), this.callbackActive, this.sealed,
					this.disabled, this.failedCallbacks,
					Optional.ofNullable(this.firstFailureSummary));
		}
	}

	private void run() {
		for (;;) {
			Runnable callback;
			synchronized (this.monitor) {
				while (this.queue.isEmpty() && !this.sealed) {
					try {
						this.monitor.wait();
					} catch (InterruptedException ignored) {
						// Transition observation is never a framework cancellation
						// target.  In particular, a callback that interrupts its own
						// worker cannot strand records accepted later in the trace.
					}
				}
				if (this.queue.isEmpty())
					return;
				callback = this.queue.removeFirst();
				this.callbackActive = true;
			}
			try {
				callback.run();
			} catch (Throwable failure) {
				String failureSummary =
						DefaultLifecycleTerminalReporter.safeThrowable(failure);
				synchronized (this.monitor) {
					this.failedCallbacks++;
					if (this.firstFailureSummary == null)
						this.firstFailureSummary = failureSummary;
				}
			} finally {
				synchronized (this.monitor) {
					this.callbackActive = false;
					this.monitor.notifyAll();
				}
			}
		}
	}
}

/** Bounded observer-delivery diagnostics; no callback object is exposed. */
@Immutable
record LifecycleTransitionSnapshot(int acceptedRecords, int pendingRecords,
		boolean callbackActive, boolean sealed, boolean disabled,
		int failedCallbacks, @NonNull Optional<String> firstFailureSummary) {
	LifecycleTransitionSnapshot {
		requireNonNull(firstFailureSummary);
		if (acceptedRecords < 0 || acceptedRecords > 16)
			throw new IllegalArgumentException(
					"acceptedRecords must be between 0 and 16");
		if (pendingRecords < 0 || pendingRecords > acceptedRecords)
			throw new IllegalArgumentException(
					"pendingRecords must be between 0 and acceptedRecords");
		if (failedCallbacks < 0 || failedCallbacks > acceptedRecords)
			throw new IllegalArgumentException(
					"failedCallbacks must be between 0 and acceptedRecords");
		if ((failedCallbacks == 0) == firstFailureSummary.isPresent())
			throw new IllegalArgumentException(
					"Transition failure summary does not match its count");
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
			T value = null;
			Throwable failure = null;
			try (LifecycleExecutionContext.Scope ignoredContext =
							 LifecycleExecutionContext.enter(
									 this.group.executionOwnerToken())) {
				try {
					value = this.callable.call();
				} catch (Throwable throwable) {
					failure = throwable;
					try {
						this.group.signalFailure(this.group.root(), throwable);
					} catch (Throwable signalFailure) {
						if (signalFailure != throwable)
							throwable.addSuppressed(signalFailure);
					}
				}
			} catch (Throwable contextFailure) {
				if (failure == null)
					failure = contextFailure;
				else if (failure != contextFailure)
					failure.addSuppressed(contextFailure);
				try {
					this.group.signalFailure(this.group.root(), failure);
				} catch (Throwable signalFailure) {
					if (signalFailure != failure)
						failure.addSuppressed(signalFailure);
				}
			} finally {
				this.thread.set(null);
				try {
					this.tracked.close();
				} catch (Throwable closeFailure) {
					if (failure == null)
						failure = closeFailure;
					else if (failure != closeFailure)
						failure.addSuppressed(closeFailure);
				}
			}
			if (failure == null)
				this.completion.complete(value);
			else
				this.completion.completeExceptionally(failure);
		}
	}
}

/** Descriptor-neutral coordinator implementing the shared grace/force protocol. */
final class InternalLifecycleCoordinator {
	interface Participant {
		@NonNull
		InternalLifecycleComponentType kind();

		@NonNull
		AdmissionFence admissionFence();

		@NonNull
		InternalTerminationGroup terminationGroup();

		@NonNull
		InternalTransportRuntime runtime();

		@NonNull
		Set<InternalResidualActivityType> residualActivity();

		default boolean startupCallActive() {
			return false;
		}

		/** Freezes participant-local facts read by the classification pass. */
		default void freezeForClassification() {
		}
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

		ShutdownContext gracefulContext = new ShutdownContext(
				ShutdownPhase.GRACEFUL, this.clock, gracefulDeadlineNanos);
		Map<Participant, TrackedLifecycleCallRunner.Call<Void>> quiesceCalls =
				new java.util.IdentityHashMap<>();
		for (Participant participant : ordered) {
			try {
				TrackedLifecycleCallRunner.Call<Void> quiesceCall = this.callRunner.submit(
						"lifecycle-quiesce-"
								+ participant.kind().name().toLowerCase(Locale.ROOT),
						participant.terminationGroup(), () -> {
							participant.runtime().quiesce(gracefulContext);
							return null;
						});
				quiesceCalls.put(participant, quiesceCall);
			} catch (RuntimeException | Error launchFailure) {
				participant.terminationGroup().signalFailure(
						participant.terminationGroup().root(), launchFailure);
			}
		}

		this.waiter.await(gracefulDeadlineNanos,
				() -> ordered.stream().allMatch(candidate ->
						candidate.terminationGroup().isBarrierComplete()));

		Set<Participant> unresolvedAtGrace = Collections.newSetFromMap(
				new java.util.IdentityHashMap<>());
		Set<Participant> forceSubmitted = Collections.newSetFromMap(
				new java.util.IdentityHashMap<>());
		Map<Participant, TrackedLifecycleCallRunner.Call<Void>> forceCalls =
				new java.util.IdentityHashMap<>();
		ShutdownContext forcedContext = new ShutdownContext(
				ShutdownPhase.FORCED, this.clock, forcedDeadlineNanos);
		for (Participant participant : ordered) {
			InternalTerminationGroup group = participant.terminationGroup();
			if (!group.tryClaimForceSubmission())
				continue;
			unresolvedAtGrace.add(participant);
			TrackedLifecycleCallRunner.Call<Void> quiesceCall =
					quiesceCalls.get(participant);
			if (quiesceCall != null && !quiesceCall.isDone())
				quiesceCall.cancel();
			try {
				TrackedLifecycleCallRunner.Call<Void> forceCall = this.callRunner.submit(
						"lifecycle-force-"
								+ participant.kind().name().toLowerCase(Locale.ROOT),
						participant.terminationGroup(), () -> {
							participant.runtime().force(forcedContext);
							return null;
						});
				forceCalls.put(participant, forceCall);
				group.resolveForceSubmission();
				forceSubmitted.add(participant);
			} catch (RuntimeException | Error launchFailure) {
				group.resolveForceSubmission();
				group.signalFailure(group.root(), launchFailure);
			}
		}

		if (!unresolvedAtGrace.isEmpty()) {
			this.waiter.await(forcedDeadlineNanos,
					() -> ordered.stream().allMatch(candidate ->
							candidate.terminationGroup().isBarrierComplete()));
			for (Participant participant : forceSubmitted) {
				TrackedLifecycleCallRunner.Call<Void> forceCall = forceCalls.get(participant);
				if (forceCall != null && !forceCall.isDone())
					forceCall.cancel();
			}
		}

		for (Participant participant : ordered)
			participant.freezeForClassification();

		List<InternalLifecycleComponentShutdownResult> results = new ArrayList<>();
		for (Participant participant : ordered) {
			InternalTerminationGroup group = participant.terminationGroup();
			InternalLifecycleComponentShutdownDisposition disposition;
			Set<InternalResidualActivityType> reportedResidual =
					participant.residualActivity();
			InternalTerminationGroup.EvidenceSnapshot evidence =
					group.freezeEvidence();
			EnumSet<InternalResidualActivityType> residual =
					EnumSet.noneOf(InternalResidualActivityType.class);
			residual.addAll(reportedResidual);
			if (evidence.trackedLifecycleCalls() > 0)
				residual.add(InternalResidualActivityType.LIFECYCLE_CALL);
			if (evidence.admittedWork() > 0)
				residual.add(InternalResidualActivityType.CALLBACK);
			if (participant.startupCallActive()) {
				disposition = InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN;
			} else if (!residual.isEmpty()) {
				disposition = InternalLifecycleComponentShutdownDisposition.RESIDUAL_ACTIVITY;
			} else if (evidence.barrierComplete()) {
				if (forceSubmitted.contains(participant))
					disposition = InternalLifecycleComponentShutdownDisposition.FORCED_TERMINATION;
				else if (evidence.controllingEvent().isPresent())
					disposition = InternalLifecycleComponentShutdownDisposition.UNEXPECTED_TERMINATION;
				else
					disposition = InternalLifecycleComponentShutdownDisposition.GRACEFUL_TERMINATION;
			} else {
				disposition = InternalLifecycleComponentShutdownDisposition.TERMINATION_UNKNOWN;
			}
			List<Throwable> failures = evidence.primaryEvents().stream()
					.flatMap(event -> event.cause().stream())
					.toList();
			results.add(new InternalLifecycleComponentShutdownResult(participant.kind(), disposition,
					failures, residual));
		}
		return new InternalShutdownResultAggregator().aggregate(
				InternalStartupDisposition.READY, results);
	}
}

/** Process/finalization seams established additively for the later runner. */
interface LifecycleProcessAccess {
	@NonNull
	Optional<InputStream> standardInput();

	void addShutdownHook(@NonNull Thread hook);

	boolean removeShutdownHook(@NonNull Thread hook);

	void reportConfigurationWarning(@NonNull String message);
}

interface LifecycleFinalizationAction {
	void run() throws Exception;
}

interface LifecycleTerminalReporter {
	void report(@NonNull SokletApplicationTerminalSnapshot snapshot);
}
